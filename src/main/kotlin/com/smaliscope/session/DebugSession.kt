package com.smaliscope.session

import com.smaliscope.adb.AdbClient
import com.smaliscope.analysis.ApkIndex
import com.smaliscope.analysis.MethodModel
import com.smaliscope.breakpoint.BreakpointEngine
import com.smaliscope.dict.SmaliDict
import com.smaliscope.frame.FrameReader
import com.smaliscope.jdwp.EventSet
import com.smaliscope.jdwp.JdwpConnection
import com.smaliscope.jdwp.JdwpEvent
import com.smaliscope.jdwp.SuspendPolicy
import com.smaliscope.jdwp.ThreadCmds
import com.smaliscope.jdwp.VirtualMachine
import com.smaliscope.jdwp.parseComposite
import com.smaliscope.stepping.StepEngine
import com.smaliscope.stepping.StepMode
import com.smaliscope.stepping.StepPlan
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 调试会话编排：把 adb 连接、JDWP 引擎、断点、单步、帧读取、静态分析串成一条链。
 *
 * 线程模型：JDWP 读线程只负责解包，绝不在其中发 JDWP 命令——回包要靠它自己读，
 * 在读线程里同步等回包会直接死锁。所以事件一律转投 [eventExec] 单线程处理。
 */
class DebugSession(
    private val adb: AdbClient,
    val serial: String,
    val pkg: String,
    val apk: ApkIndex,
) : AutoCloseable {

    var onState: ((DebugState) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null

    private var conn: JdwpConnection? = null
    private var localPort: Int? = null

    lateinit var runtime: RuntimeIndex; private set
    lateinit var breakpoints: BreakpointEngine; private set
    private lateinit var frameReader: FrameReader
    private lateinit var stepEngine: StepEngine
    private lateinit var threads: ThreadCmds
    private lateinit var vm: VirtualMachine

    private val eventExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "smaliscope-events").apply { isDaemon = true }
    }
    private val lock = ReentrantLock()

    @Volatile
    var state: DebugState = DebugState("detached", "未连接")
        private set

    private var suspendedThread: Long? = null
    private var currentPlan: StepPlan? = null

    /** 上一次停留位置的寄存器快照，用于算出「这一步改变了哪些寄存器」。 */
    private var prevFrameKey: String? = null
    private var prevValues: Map<Int, String> = emptyMap()

    /** 每个方法执行过的 dex_pc，CFG 用来给「走过的路」着色。 */
    private val visitedPcs = ConcurrentHashMap<String, MutableSet<Int>>()

    /** 执行轨迹时间线（time-travel-lite：回放快照，不是真的逆执行）。 */
    val timeline: MutableList<StepSnapshot> = Collections.synchronizedList(ArrayList())
    private var seq = 0

    private var anyBreakpointActive = false

    /**
     * 连接建立前设下的断点。用户是先选好断点位置再点「开始调试」的，
     * 那时还没有 JDWP 连接，必须先记下来，attach 时在放行应用之前装上——
     * 否则应用早就跑过断点位置了。
     */
    private data class BpSpec(val id: Int, val fqcn: String, val method: String, val signature: String, val dexPc: Int)

    private val preSpecs = LinkedHashMap<Int, BpSpec>()

    /** 断点编号的唯一发号处：attach 前后共用，用户拿到的编号不会失效。 */
    private val bpIdGen = java.util.concurrent.atomic.AtomicInteger(1)

    private fun log(msg: String) {
        onLog?.invoke(msg)
    }

    private fun publish(s: DebugState) {
        state = s
        onState?.invoke(s)
    }

    // ── 接入 ────────────────────────────────────────────────────────────────

    /**
     * 挂起启动并 attach：应用启动时停住等调试器，这样才断得到入口方法。
     * 用户视角只是「点了一下应用名」。
     */
    fun launchSuspended(timeoutMs: Long = 25_000) {
        publish(DebugState("connecting", "正在准备应用…"))
        val apps = DeviceApps(adb, serial)

        adb.shell(serial, "am clear-debug-app")
        adb.shell(serial, "am force-stop $pkg")
        // -w：应用启动后停住等调试器接入
        adb.shell(serial, "am set-debug-app -w $pkg")
        log("已设置 $pkg 启动时等待调试器")

        val component = resolveLauncher(pkg)
        if (component != null) {
            adb.shell(serial, "am start -n $component")
            log("已启动 $component")
        } else {
            adb.shell(serial, "monkey -p $pkg -c android.intent.category.LAUNCHER 1")
            log("已通过默认入口启动 $pkg")
        }

        val pid = waitForDebuggablePid(apps, timeoutMs)
            ?: run {
                adb.shell(serial, "am clear-debug-app")
                throw IllegalStateException(
                    "等待 $pkg 变为可调试超时。请确认该应用是 debuggable 的：" +
                        "模拟器若用的是 Play 商店镜像，ro.debuggable=0，只有自带 debuggable 标记的应用能调试。"
                )
            }
        attachTo(pid)
        // 清掉，免得下次手动启动这个应用时又被挂起。
        adb.shell(serial, "am clear-debug-app")
    }

    /** 直接连上一个已在运行的可调试进程。 */
    fun attachTo(pid: Int) {
        publish(DebugState("connecting", "正在连接调试通道…"))
        val port = adb.forwardJdwp(serial, pid)
        localPort = port
        val c = JdwpConnection.attach(port = port)
        conn = c

        runtime = RuntimeIndex(c, apk)
        breakpoints = BreakpointEngine(c, runtime)
        frameReader = FrameReader(c, runtime)
        stepEngine = StepEngine(runtime, breakpoints)
        threads = ThreadCmds(c)
        vm = VirtualMachine(c)

        c.onEvent = { reader ->
            val set = runCatching { parseComposite(reader) }.getOrNull()
            if (set != null) eventExec.submit { runCatching { handleEvents(set) }.onFailure {
                log("事件处理出错: ${it.message}")
            } }
        }
        c.onDisconnect = { t ->
            publish(DebugState("detached", "调试连接已断开：${t?.message ?: "目标进程退出"}"))
        }

        val v = c.version()
        log("已连接 pid $pid（${v.vmName} ${v.vmVersion}）")

        // 全局挂起一下，把已有断点装上再放行，避免应用跑过了断点位置。
        runCatching {
            vm.suspend()
            preSpecs.values.forEach { s ->
                val bp = breakpoints.add(s.id, s.fqcn, s.method, s.signature, s.dexPc)
                if (bp.state == BreakpointEngine.State.ACTIVE) anyBreakpointActive = true
            }
            preSpecs.clear()
            breakpoints.reinstallPending()
            vm.resume()
        }

        publish(DebugState("running", "应用运行中，等待断点命中"))
    }

    private fun JdwpConnection.version() = VirtualMachine(this).version()

    private fun resolveLauncher(pkg: String): String? {
        val out = adb.shell(
            serial,
            "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $pkg"
        )
        return out.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.contains('/') && it.startsWith(pkg) }
    }

    private fun waitForDebuggablePid(apps: DeviceApps, timeoutMs: Long): Int? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val pid = apps.pidOf(pkg)
            if (pid != null && pid in adb.jdwpPids(serial, windowMs = 600)) return pid
            Thread.sleep(300)
        }
        return null
    }

    // ── 事件处理（跑在 eventExec 单线程上）────────────────────────────────────

    private fun handleEvents(set: EventSet) = lock.withLock {
        var stayaSuspended = false
        var threadToResume: Long? = null

        for (ev in set.events) {
            when (ev) {
                is JdwpEvent.ClassPrepare -> {
                    val activated = breakpoints.onClassPrepared(ev.fqcn, ev.typeId, ev.signature)
                    if (activated) {
                        log("类 ${ev.fqcn} 已加载，等待中的断点已生效")
                        anyBreakpointActive = true
                    }
                    threadToResume = ev.threadId
                }

                is JdwpEvent.Breakpoint -> {
                    if (breakpoints.isTemp(ev.requestId)) {
                        val depth = runCatching { threads.frameCount(ev.threadId) }.getOrDefault(0)
                        val plan = currentPlan
                        if (plan != null && !plan.accepts(depth)) {
                            // 递归导致命中了同一批临时断点，但深度不对，不是我们要的落点。
                            threadToResume = ev.threadId
                        } else {
                            stepEngine.finish()
                            currentPlan = null
                            onSuspended(ev.threadId, "单步完成")
                            stayaSuspended = true
                        }
                    } else {
                        breakpoints.byRequest(ev.requestId)?.let { it.hitCount++ }
                        if (currentPlan != null) {
                            stepEngine.finish()
                            currentPlan = null
                        }
                        onSuspended(ev.threadId, "断点命中")
                        stayaSuspended = true
                    }
                }

                is JdwpEvent.VmDeath -> {
                    publish(DebugState("detached", "目标进程已退出"))
                    return@withLock
                }

                is JdwpEvent.VmStart -> threadToResume = ev.threadId
                is JdwpEvent.MethodEntry -> threadToResume = ev.threadId
                is JdwpEvent.SingleStep -> threadToResume = ev.threadId
                is JdwpEvent.ThreadStart, is JdwpEvent.ThreadDeath -> Unit
            }
        }

        if (!stayaSuspended) {
            when (set.suspendPolicy) {
                SuspendPolicy.ALL -> runCatching { vm.resume() }
                SuspendPolicy.EVENT_THREAD -> threadToResume?.let { t -> runCatching { threads.resume(t) } }
                else -> Unit
            }
        }
    }

    /** 命中后在一次往返内取齐位置、栈、寄存器，保证前端各视图同一帧内一致。 */
    private fun onSuspended(threadId: Long, reason: String) {
        suspendedThread = threadId
        val threadName = runCatching { threads.name(threadId) }.getOrDefault("?")
        val frames = runCatching { frameReader.readStack(threadId) }.getOrDefault(emptyList())
        val top = frames.firstOrNull()

        val diffed = if (top == null) emptyList() else {
            val key = "${top.fqcn}#${top.method}${top.signature}@${frames.size}"
            val marked = top.registers.map { r ->
                val old = prevValues[r.reg]
                r.copy(changed = prevFrameKey == key && old != null && old != r.value)
            }
            prevFrameKey = key
            prevValues = top.registers.associate { it.reg to it.value }
            marked
        }

        top?.let {
            visitedPcs.computeIfAbsent(methodKey(it.fqcn, it.method, it.signature)) {
                Collections.synchronizedSet(HashSet())
            }.add(it.dexPc)

            timeline += StepSnapshot(
                seq = ++seq,
                fqcn = it.fqcn,
                method = it.method,
                dexPc = it.dexPc,
                registers = diffed,
                stackDepth = frames.size,
            )
        }

        val framesOut = if (top == null) frames else
            listOf(top.copy(registers = diffed)) + frames.drop(1)

        publish(
            DebugState(
                status = "suspended",
                message = top?.let { "已停在 ${it.fqcn.substringAfterLast('.')}.${it.method}" } ?: "已挂起",
                threadId = threadId,
                threadName = threadName,
                frames = framesOut,
                currentFrame = 0,
                reason = reason,
                deoptWarning = anyBreakpointActive,
            )
        )
    }

    private fun methodKey(fqcn: String, method: String, signature: String) = "$fqcn#$method$signature"

    // ── 控制 ────────────────────────────────────────────────────────────────

    fun resume() = lock.withLock {
        val t = suspendedThread ?: return@withLock
        stepEngine.finish()
        currentPlan = null
        suspendedThread = null
        runCatching { threads.resume(t) }
        publish(DebugState("running", "已继续运行，等待下一次命中", deoptWarning = anyBreakpointActive))
    }

    fun step(mode: StepMode) = lock.withLock {
        val t = suspendedThread ?: return@withLock
        val stack = runCatching { threads.frames(t) }.getOrDefault(emptyList())
        val plan = stepEngine.plan(mode, stack)
        if (plan == null) {
            log("无法在当前位置单步：该方法没有可用的静态模型（多半是系统方法）")
            return@withLock
        }
        currentPlan = plan
        suspendedThread = null
        publish(DebugState("running", "单步执行中…", deoptWarning = anyBreakpointActive))
        runCatching { threads.resume(t) }
    }

    fun addBreakpoint(fqcn: String, method: String, signature: String, dexPc: Int): BreakpointView {
        lock.withLock {
            if (!::breakpoints.isInitialized) {
                preSpecs.values.firstOrNull {
                    it.fqcn == fqcn && it.method == method && it.signature == signature && it.dexPc == dexPc
                }?.let {
                    return BreakpointView(it.id, fqcn, method, signature, dexPc, "pending", 0, "等待连接建立")
                }
                val id = bpIdGen.getAndIncrement()
                preSpecs[id] = BpSpec(id, fqcn, method, signature, dexPc)
                log("断点已设置：${fqcn.substringAfterLast('.')}.$method（将在连接建立时生效）")
                return BreakpointView(id, fqcn, method, signature, dexPc, "pending", 0, "等待连接建立")
            }
            val bp = breakpoints.add(bpIdGen.getAndIncrement(), fqcn, method, signature, dexPc)
            if (bp.state == BreakpointEngine.State.ACTIVE) anyBreakpointActive = true
            log(
                if (bp.state == BreakpointEngine.State.PENDING)
                    "断点已设置（该类还没加载，会在加载后自动生效）"
                else "断点已设置：${fqcn.substringAfterLast('.')}.$method"
            )
            return breakpoints.list().first { it.id == bp.id }
        }
    }

    fun removeBreakpoint(id: Int) = lock.withLock {
        if (id < 0) preSpecs.remove(id) else if (::breakpoints.isInitialized) breakpoints.remove(id)
        Unit
    }

    fun listBreakpoints(): List<BreakpointView> = lock.withLock {
        val live = if (::breakpoints.isInitialized) breakpoints.list() else emptyList()
        val queued = preSpecs.values.map {
            BreakpointView(it.id, it.fqcn, it.method, it.signature, it.dexPc, "pending", 0, "等待连接建立")
        }
        live + queued
    }

    // ── 供 UI 的查询 ────────────────────────────────────────────────────────

    /** 方法的完整视图：指令表 + CFG + 走过的路 + 中文解释。 */
    fun methodView(fqcn: String, method: String, signature: String, currentPc: Int?): MethodView? {
        val model: MethodModel = apk.model(fqcn, method, signature) ?: return null
        val visited = visitedPcs[methodKey(fqcn, method, signature)] ?: emptySet<Int>()
        return MethodView(
            fqcn = fqcn,
            method = method,
            signature = signature,
            registerCount = model.registerCount,
            registerNames = (0 until model.registerCount).map { model.regName(it) },
            analysisWarning = model.analysisError?.let { "寄存器类型推导未完全成功，部分寄存器可能显示为未知（$it）" },
            instructions = model.instructions.map {
                InsnView(
                    dexPc = it.dexPc,
                    index = it.index,
                    text = it.text,
                    opcode = it.opcodeName,
                    reads = it.reads,
                    writes = it.writes,
                    isBranch = it.isBranch,
                    isInvoke = it.isInvoke,
                    isReturn = it.isReturn,
                    doc = SmaliDict.describe(it.opcodeName),
                )
            },
            blocks = model.basicBlocks.map { b ->
                BlockView(
                    id = b.id,
                    startPc = b.startPc,
                    endPc = b.endPc,
                    successors = b.successors,
                    visited = visited.any { it in b.startPc..b.endPc },
                    current = currentPc != null && currentPc in b.startPc..b.endPc,
                )
            },
        )
    }

    fun registerNames(fqcn: String, method: String, signature: String): List<String> =
        apk.model(fqcn, method, signature)
            ?.let { m -> (0 until m.registerCount).map { m.regName(it) } }
            ?: emptyList()

    fun expandObject(objectId: Long): ObjectNode? =
        if (!::frameReader.isInitialized) null
        else runCatching { frameReader.expandObject(objectId) }.getOrNull()

    /** 点开非栈顶帧时按需读它的寄存器。 */
    fun readFrame(depth: Int): FrameView? = lock.withLock {
        val t = suspendedThread ?: return@withLock null
        val stack = runCatching { threads.frames(t) }.getOrDefault(emptyList())
        val f = stack.getOrNull(depth) ?: return@withLock null
        val model = runtime.modelOf(f.location.classId, f.location.methodId)
        val (fqcn, name, sig) = runtime.describeLocation(f.location)
        FrameView(
            frameId = f.frameId,
            depth = depth,
            fqcn = fqcn,
            method = name,
            signature = sig,
            dexPc = f.location.dexPc,
            hasModel = model != null,
            registers = frameReader.readRegisters(t, f.frameId, model, f.location.dexPc),
        )
    }

    override fun close() {
        runCatching { if (::breakpoints.isInitialized) breakpoints.clearAll() }
        runCatching { if (::vm.isInitialized) vm.dispose() }
        runCatching { conn?.close() }
        runCatching { localPort?.let { adb.removeForward(serial, it) } }
        runCatching { adb.shell(serial, "am clear-debug-app") }
        eventExec.shutdownNow()
        publish(DebugState("detached", "已断开调试"))
    }
}
