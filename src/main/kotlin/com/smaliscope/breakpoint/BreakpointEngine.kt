package com.smaliscope.breakpoint

import com.smaliscope.jdwp.EventKind
import com.smaliscope.jdwp.EventRequestCmds
import com.smaliscope.jdwp.JdwpConnection
import com.smaliscope.jdwp.Location
import com.smaliscope.jdwp.SuspendPolicy
import com.smaliscope.session.BreakpointView
import com.smaliscope.session.RuntimeIndex

/**
 * 断点引擎。
 *
 * 用户只会看到「断点已设置」，看不到底下这套机制：类已加载就直接下 EventRequest；
 * 类还没加载（新手最容易懵的场景）就自动转成 pending，注册 CLASS_PREPARE，
 * 等类加载回调里再补下真实断点。
 */
class BreakpointEngine(
    conn: JdwpConnection,
    private val runtime: RuntimeIndex,
) {
    enum class State { PENDING, ACTIVE, ERROR }

    class Bp(
        val id: Int,
        val fqcn: String,
        val method: String,
        val signature: String,
        val dexPc: Int,
    ) {
        var state: State = State.PENDING
        var requestId: Int? = null
        var hitCount: Int = 0
        var note: String? = null
        /** 条件断点：null 表示无条件。命中判定见 DebugSession.handleEvents。 */
        var condition: com.smaliscope.session.BpCondition? = null
    }

    private val events = EventRequestCmds(conn)

    private val breakpoints = LinkedHashMap<Int, Bp>()
    private val byRequestId = HashMap<Int, Bp>()
    /** fqcn → CLASS_PREPARE requestId，用于类加载后补下断点。 */
    private val classPrepareRequests = HashMap<String, Int>()

    /** 单步用的临时断点，与用户断点分开管理，命中后整批清掉。 */
    private val tempRequestIds = HashSet<Int>()

    /**
     * ID 由 [com.smaliscope.session.DebugSession] 统一分配并传进来，本类不自己发号。
     * 因为断点可以在 attach 之前就设下（那时还没有本引擎），如果两边各发各的号，
     * 用户先拿到的编号会在连接建立后失效。
     */
    @Synchronized
    fun add(
        id: Int, fqcn: String, method: String, signature: String, dexPc: Int,
        condition: com.smaliscope.session.BpCondition? = null,
    ): Bp {
        breakpoints.values.firstOrNull {
            it.fqcn == fqcn && it.method == method && it.signature == signature && it.dexPc == dexPc
        }?.let { existing ->
            // 已有同位置断点：允许更新条件，方便「先下断点再加条件」。
            if (condition != null) existing.condition = condition.takeUnless { it.isEmpty }
            return existing
        }

        val bp = Bp(id, fqcn, method, signature, dexPc)
        bp.condition = condition?.takeUnless { it.isEmpty }
        breakpoints[bp.id] = bp
        install(bp)
        return bp
    }

    /** 给已存在的断点设/清条件（传 null 或空条件即清除）。 */
    @Synchronized
    fun setCondition(id: Int, condition: com.smaliscope.session.BpCondition?): Boolean {
        val bp = breakpoints[id] ?: return false
        bp.condition = condition?.takeUnless { it.isEmpty }
        return true
    }

    private fun install(bp: Bp) {
        val loc = runtime.locationOf(bp.fqcn, bp.method, bp.signature, bp.dexPc)
        if (loc == null) {
            bp.state = State.PENDING
            bp.note = "类尚未加载，已自动等待其加载后生效"
            ensureClassPrepare(bp.fqcn)
            return
        }
        try {
            val rid = events.setBreakpoint(loc, SuspendPolicy.EVENT_THREAD)
            bp.requestId = rid
            bp.state = State.ACTIVE
            bp.note = null
            byRequestId[rid] = bp
        } catch (t: Throwable) {
            bp.state = State.ERROR
            bp.note = t.message
        }
    }

    private fun ensureClassPrepare(fqcn: String) {
        if (classPrepareRequests.containsKey(fqcn)) return
        runCatching { events.setClassPrepare(fqcn, SuspendPolicy.EVENT_THREAD) }
            .onSuccess { classPrepareRequests[fqcn] = it }
    }

    /** 类加载完成：把该类上所有 pending 断点补成真实断点。返回是否有断点被激活。 */
    @Synchronized
    fun onClassPrepared(fqcn: String, classId: Long, signature: String): Boolean {
        runtime.registerClass(fqcn, classId, signature)
        val pending = breakpoints.values.filter { it.fqcn == fqcn && it.state != State.ACTIVE }
        if (pending.isEmpty()) return false
        pending.forEach { install(it) }
        // 该类的断点都装上了，CLASS_PREPARE 就没用了，撤掉以免继续产生事件。
        if (breakpoints.values.none { it.fqcn == fqcn && it.state == State.PENDING }) {
            classPrepareRequests.remove(fqcn)?.let { events.clear(EventKind.CLASS_PREPARE, it) }
        }
        return pending.any { it.state == State.ACTIVE }
    }

    @Synchronized
    fun remove(id: Int) {
        val bp = breakpoints.remove(id) ?: return
        bp.requestId?.let {
            events.clear(EventKind.BREAKPOINT, it)
            byRequestId.remove(it)
        }
    }

    @Synchronized
    fun byRequest(requestId: Int): Bp? = byRequestId[requestId]

    @Synchronized
    fun list(): List<BreakpointView> = breakpoints.values.map {
        BreakpointView(
            id = it.id,
            fqcn = it.fqcn,
            method = it.method,
            signature = it.signature,
            dexPc = it.dexPc,
            state = when (it.state) {
                State.PENDING -> "pending"
                State.ACTIVE -> "active"
                State.ERROR -> "error"
            },
            hitCount = it.hitCount,
            note = it.note,
            condition = it.condition?.describe(),
        )
    }

    /** 重新尝试安装所有 pending 断点（attach 后、或类加载后批量调用）。 */
    @Synchronized
    fun reinstallPending() {
        breakpoints.values.filter { it.state != State.ACTIVE }.forEach { install(it) }
    }

    // ── 单步用的临时断点 ────────────────────────────────────────────────────

    @Synchronized
    fun setTemp(location: Location): Int? = runCatching {
        events.setBreakpoint(location, SuspendPolicy.EVENT_THREAD).also { tempRequestIds += it }
    }.getOrNull()

    @Synchronized
    fun isTemp(requestId: Int): Boolean = requestId in tempRequestIds

    @Synchronized
    fun clearTemps() {
        tempRequestIds.forEach { events.clear(EventKind.BREAKPOINT, it) }
        tempRequestIds.clear()
    }

    @Synchronized
    fun clearAll() {
        clearTemps()
        breakpoints.values.forEach { bp ->
            bp.requestId?.let { events.clear(EventKind.BREAKPOINT, it) }
        }
        breakpoints.clear()
        byRequestId.clear()
        classPrepareRequests.values.forEach { events.clear(EventKind.CLASS_PREPARE, it) }
        classPrepareRequests.clear()
    }
}
