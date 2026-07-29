package com.smaliscope.stepping

import com.smaliscope.analysis.MethodModel
import com.smaliscope.breakpoint.BreakpointEngine
import com.smaliscope.jdwp.FrameInfo
import com.smaliscope.jdwp.Location
import com.smaliscope.jdwp.TypeTag
import com.smaliscope.session.RuntimeIndex

enum class StepMode { INTO, OVER, OUT }

/**
 * 一次单步的计划：在哪些位置下了临时断点，以及命中时该满足的栈深条件。
 * 栈深条件用来滤掉递归——同一个方法被递归调用时会命中同一批临时断点，
 * 但那不是我们要的「下一条指令」。
 */
data class StepPlan(
    val mode: StepMode,
    val baseDepth: Int,
    val targets: List<Int>,
) {
    fun accepts(depth: Int): Boolean = when (mode) {
        StepMode.OVER -> depth <= baseDepth
        StepMode.OUT -> depth < baseDepth
        StepMode.INTO -> true
    }
}

/**
 * 指令级单步引擎。
 *
 * 不用 JDWP 原生的 STEP 事件：它按源码行走，没有 line table 时会退化成「跨过整个方法」，
 * 而我们要的是一条 smali 指令。做法是自己算后继、下临时断点、Resume、命中后清理。
 */
class StepEngine(
    private val runtime: RuntimeIndex,
    private val breakpoints: BreakpointEngine,
) {

    /**
     * 为当前停留位置规划一次单步。返回 null 表示无法规划（例如当前帧没有静态模型）。
     * 调用方随后 Resume 线程即可。
     */
    fun plan(mode: StepMode, stack: List<FrameInfo>): StepPlan? {
        if (stack.isEmpty()) return null
        val top = stack.first()
        val model = runtime.modelOf(top.location.classId, top.location.methodId)
        val depth = stack.size

        val targets = LinkedHashSet<Location>()

        when (mode) {
            StepMode.OUT -> addReturnSite(stack, targets)

            StepMode.OVER, StepMode.INTO -> {
                val insn = model?.insnAt(top.location.dexPc)
                if (model == null || insn == null) {
                    // 当前在 framework 方法里，没有静态模型可算后继，只能退化成「跑回调用者」。
                    addReturnSite(stack, targets)
                } else {
                    insn.successors.forEach { pc ->
                        targets += Location(TypeTag.CLASS, top.location.classId, top.location.methodId, pc.toLong())
                    }
                    // 返回指令没有方法内后继，落点在调用者。
                    if (insn.successors.isEmpty()) addReturnSite(stack, targets)

                    if (mode == StepMode.INTO && insn.isInvoke) {
                        val entered = addCalleeEntry(insn.invokeTarget, targets)
                        if (!entered) {
                            // 目标方法不在本 APK（多半是 framework），步入无意义，按步过处理。
                            return plan(StepMode.OVER, stack)
                        }
                    }
                }
            }
        }

        if (targets.isEmpty()) return null
        val ids = targets.mapNotNull { breakpoints.setTemp(it) }
        if (ids.isEmpty()) return null
        return StepPlan(mode, depth, targets.map { it.dexPc })
    }

    /** 调用者帧中 invoke 的下一条指令，即本次调用的返回落点。 */
    private fun addReturnSite(stack: List<FrameInfo>, out: MutableSet<Location>) {
        val caller = stack.getOrNull(1) ?: return
        val callerModel = runtime.modelOf(caller.location.classId, caller.location.methodId) ?: return
        val callSite = callerModel.insnAt(caller.location.dexPc) ?: return
        // 调用者的 dex_pc 停在 invoke 上，返回后从它的下一条继续。
        val next = caller.location.dexPc + callSite.codeUnits
        val target = callerModel.insnAt(next) ?: return
        out += Location(TypeTag.CLASS, caller.location.classId, caller.location.methodId, target.dexPc.toLong())
    }

    /** 被调方法的入口（dex_pc 0）。类未加载或不在本 APK 时返回 false。 */
    private fun addCalleeEntry(
        target: com.smaliscope.analysis.MethodRef?,
        out: MutableSet<Location>,
    ): Boolean {
        if (target == null) return false
        val calleeModel: MethodModel = runtime.apk.model(target) ?: return false
        val entry = calleeModel.instructions.firstOrNull() ?: return false
        val loc = runtime.locationOf(target.fqcn, target.name, target.signature, entry.dexPc) ?: return false
        out += loc
        return true
    }

    fun finish() = breakpoints.clearTemps()
}
