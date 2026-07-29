package com.smaliscope.session

import com.smaliscope.adb.AdbClient
import com.smaliscope.analysis.ApkIndex
import com.smaliscope.decompile.JadxService
import com.smaliscope.dict.SmaliDict
import com.smaliscope.stepping.StepMode
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 调试器门面：把「选设备 → 载入应用 → 下断点 → 启动 → 命中 → 单步」这条链
 * 收成一组与传输协议无关的方法。
 *
 * Web 工作台（HTTP + SSE）和 MCP server（JSON-RPC over stdio）都只是它的薄壳，
 * 两边共用同一份会话状态——否则一个进程里会出现两套互相不知道的调试状态。
 */
class Debugger(
    private val cacheDir: File,
    private val adb: AdbClient = AdbClient(),
) : AutoCloseable {

    data class AppEntry(val pkg: String, val pid: Int?, val debuggable: Boolean)

    data class Bootstrap(
        val ok: Boolean,
        val message: String?,
        val serial: String?,
        val devices: List<String>,
        val env: EnvProbe?,
        val apps: List<AppEntry>,
    )

    @Volatile var serial: String? = null; private set
    @Volatile var pkg: String? = null; private set
    @Volatile var apk: ApkIndex? = null; private set
    @Volatile private var session: DebugSession? = null
    @Volatile private var jadx: JadxService? = null

    val logs = CopyOnWriteArrayList<String>()

    /** 状态变化时回调（Web 层用来推 SSE）。 */
    @Volatile var onState: ((DebugState) -> Unit)? = null
    @Volatile var onLog: ((String) -> Unit)? = null

    private val stopLock = ReentrantLock()
    private val stopCond = stopLock.newCondition()

    @Volatile
    var state: DebugState = DebugState("idle", "尚未开始调试")
        private set

    /**
     * 每次停下来（断点命中 / 单步完成）自增。
     * MCP 那边没有事件流，只能「发起动作 → 等下一次停下」，靠这个序号区分
     * 「新的一次停下」和「本来就停着」，避免拿旧状态当结果。
     */
    private var stopSeq: Long = 0

    // ── 设备与应用 ──────────────────────────────────────────────────────────

    fun bootstrap(): Bootstrap {
        val devices = adb.devices().filter { it.isOnline }
        if (devices.isEmpty()) {
            return Bootstrap(
                ok = false,
                message = "未发现在线设备。请先启动模拟器（推荐 AVD 的非 Play 镜像），或用 USB 连接手机。",
                serial = null, devices = emptyList(), env = null, apps = emptyList(),
            )
        }
        val dev = devices.firstOrNull { it.isEmulator } ?: devices.first()
        serial = dev.serial
        val apps = DeviceApps(adb, dev.serial)
        val env = apps.probeEnvironment()
        val jdwp = adb.jdwpPids(dev.serial).toSet()
        val procs = runCatching { apps.runningProcesses() }.getOrDefault(emptyMap())
        return Bootstrap(
            ok = true,
            message = null,
            serial = dev.serial,
            devices = devices.map { it.serial },
            env = env,
            apps = apps.listPackages().map { p ->
                val pid = procs[p]
                AppEntry(p, pid, pid != null && pid in jdwp)
            },
        )
    }

    /** 选定应用：拉 APK、静态分析、建会话（此时还没连上进程）。 */
    fun loadApp(packageName: String): Int {
        val s = serial ?: bootstrap().serial ?: error("未发现在线设备")
        session?.let { runCatching { it.close() } }
        runCatching { jadx?.close() }

        log("正在获取 $packageName 的 APK…")
        val apps = DeviceApps(adb, s)
        val files = apps.pullApks(packageName, cacheDir)
        val env = apps.probeEnvironment()
        val index = ApkIndex(files, env.sdk.coerceIn(21, 35))
        log("已解析 ${index.classCount} 个类")

        apk = index
        pkg = packageName
        jadx = JadxService(files)

        val sess = DebugSession(adb, s, packageName, index)
        sess.onLog = { log(it) }
        sess.onState = { st -> publish(st) }
        session = sess

        publish(DebugState("idle", "已载入 $packageName，选一条指令下断点后开始调试"))
        return index.classCount
    }

    private fun publish(st: DebugState) = stopLock.withLock {
        state = st
        if (st.status == "suspended") {
            stopSeq++
            stopCond.signalAll()
        }
        onState?.invoke(st)
    }

    private fun log(msg: String) {
        logs += msg
        if (logs.size > 500) logs.removeAt(0)
        onLog?.invoke(msg)
    }

    // ── 静态浏览 ────────────────────────────────────────────────────────────

    fun classNames(filter: String? = null, limit: Int = 500): List<String> {
        val index = apk ?: return emptyList()
        val own = index.appClassNames(pkg?.substringBeforeLast('.')?.takeIf { it.isNotBlank() })
            .ifEmpty { index.appClassNames() }
        val f = filter?.lowercase()
        return (if (f.isNullOrBlank()) own else own.filter { it.lowercase().contains(f) }).take(limit)
    }

    fun methodsOf(fqcn: String): List<Triple<String, String, Int>> {
        val index = apk ?: return emptyList()
        return index.concreteMethodsOf(fqcn).map {
            Triple(it.name, it.signature, index.model(it)?.instructions?.size ?: 0)
        }
    }

    fun methodView(fqcn: String, method: String, signature: String, pc: Int?): MethodView? {
        session?.methodView(fqcn, method, signature, pc)?.let { return it }
        // 还没建会话时也能浏览 smali（此时没有「走过的路」信息）。
        val m = apk?.model(fqcn, method, signature) ?: return null
        return MethodView(
            fqcn = fqcn, method = method, signature = signature,
            registerCount = m.registerCount,
            registerNames = (0 until m.registerCount).map { m.regName(it) },
            analysisWarning = m.analysisError?.let { "寄存器类型推导未完全成功（$it）" },
            instructions = m.instructions.map {
                InsnView(
                    it.dexPc, it.index, it.text, it.opcodeName, it.reads, it.writes,
                    it.isBranch, it.isInvoke, it.isReturn, SmaliDict.describe(it.opcodeName),
                )
            },
            blocks = m.basicBlocks.map { b ->
                BlockView(b.id, b.startPc, b.endPc, b.successors, false, false)
            },
        )
    }

    /** 解析用户给的类名：允许只写简名（如 Calc）。 */
    fun resolveClass(name: String): String? {
        val index = apk ?: return null
        return index.classNames().firstOrNull { it == name || it.endsWith(".$name") }
    }

    /** 解析方法：不给签名时取第一个有实现的重载。 */
    fun resolveMethod(fqcn: String, method: String, signature: String?): String? {
        val index = apk ?: return null
        if (signature != null) return signature
        return index.concreteMethodsOf(fqcn).firstOrNull { it.name == method }?.signature
    }

    fun javaSource(fqcn: String): Pair<String?, String?> {
        val svc = jadx ?: return null to "请先载入应用"
        val code = svc.javaOf(fqcn)
        return code to (
            if (code != null) null
            else svc.error?.let { "反编译器初始化失败：$it" }
                ?: "jadx 无法反编译该类（它一共识别出 ${svc.classCount()} 个类），可以继续看 smali。"
            )
    }

    // ── 断点与执行控制 ──────────────────────────────────────────────────────

    fun addBreakpoint(fqcn: String, method: String, signature: String, dexPc: Int): BreakpointView {
        val sess = session ?: error("请先载入要调试的应用")
        return sess.addBreakpoint(fqcn, method, signature, dexPc)
    }

    fun removeBreakpoint(id: Int) {
        session?.removeBreakpoint(id)
    }

    fun breakpoints(): List<BreakpointView> = session?.listBreakpoints() ?: emptyList()

    /** 挂起启动并 attach。阻塞直到 attach 完成（不等断点命中）。 */
    fun start() {
        val sess = session ?: error("请先载入要调试的应用")
        sess.launchSuspended()
    }

    /** 后台启动，进度经状态回调推出去（Web 层用）。 */
    fun startAsync() {
        val sess = session ?: error("请先载入要调试的应用")
        Thread({
            runCatching { sess.launchSuspended() }.onFailure {
                log("启动失败：${it.message}")
                publish(DebugState("idle", "启动失败：${it.message}"))
            }
        }, "smaliscope-launch").start()
    }

    fun control(action: String) {
        val sess = session ?: error("尚未开始调试")
        when (action) {
            "resume" -> sess.resume()
            "into" -> sess.step(StepMode.INTO)
            "over" -> sess.step(StepMode.OVER)
            "out" -> sess.step(StepMode.OUT)
            "stop" -> { sess.close(); session = null }
            else -> error("未知操作：$action")
        }
    }

    private fun currentStopSeq(): Long = stopLock.withLock { stopSeq }

    /** 等到 [afterSeq] 之后的下一次停下。超时返回 null。 */
    private fun awaitStopAfter(afterSeq: Long, timeoutMs: Long): DebugState? = stopLock.withLock {
        var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (stopSeq <= afterSeq && remaining > 0) {
            remaining = stopCond.awaitNanos(remaining)
        }
        if (stopSeq > afterSeq) state else null
    }

    /**
     * 发起一个动作，然后等它停下来。
     * MCP 侧的 agent 没有事件流，一次请求就该拿到结果，否则它得自己轮询。
     */
    fun actAndWait(timeoutMs: Long, action: () -> Unit): DebugState? {
        val seq = currentStopSeq()
        action()
        return awaitStopAfter(seq, timeoutMs)
    }

    /** 已经停着就直接返回，否则等下一次停下。 */
    fun waitForStop(timeoutMs: Long): DebugState? {
        stopLock.withLock { if (state.status == "suspended") return state }
        return awaitStopAfter(currentStopSeq(), timeoutMs)
    }

    // ── AI 解释（可选功能，没配 API key 时整个不存在）──────────────────────

    @Volatile
    private var explainerRef: com.smaliscope.explain.Explainer? = null

    /** 每次读配置，方便用户改完 key 不必重启。 */
    private fun explainer(): com.smaliscope.explain.Explainer =
        explainerRef ?: com.smaliscope.explain.Explainer(this).also { explainerRef = it }

    fun llmEnabled(): Boolean = com.smaliscope.config.Settings.llm().enabled

    /** 配置变更后丢弃旧客户端。 */
    fun reloadLlm() { explainerRef = null }

    fun explain(fqcn: String, method: String, signature: String, dexPc: Int?): String =
        explainer().explainMethod(fqcn, method, signature, dexPc)

    fun nameRegisters(fqcn: String, method: String, signature: String): String =
        explainer().nameRegisters(fqcn, method, signature)

    fun expandObject(objectId: Long): ObjectNode? = session?.expandObject(objectId)

    fun readFrame(depth: Int): FrameView? = session?.readFrame(depth)

    fun timeline(): List<StepSnapshot> = session?.timeline?.toList() ?: emptyList()

    override fun close() {
        runCatching { session?.close() }
        runCatching { jadx?.close() }
        session = null
        jadx = null
    }
}
