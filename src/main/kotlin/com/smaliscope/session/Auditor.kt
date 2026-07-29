package com.smaliscope.session

import com.smaliscope.analysis.MethodRef

/**
 * 寄存器可读率审计。
 *
 * 回答的是 ROADMAP 第 0 项那个问题：ART 校验读寄存器的 tag 时用的是 dex 调试信息里
 * 「声明」的类型，那么在真实（缺少调试信息、经过混淆的）APK 上，
 * 寄存器面板还能剩下多少内容？
 *
 * 做法是在方法入口下断点，然后逐条单步走完整个方法，
 * 在每一个停留位置记录每个寄存器的三种状态：
 *   - 没尝试读：静态类型推导就说该位置没有有效值（未初始化 / 类型冲突），这不算失败；
 *   - 读到了：正常；
 *   - 尝试了但被拒：这才是我们要量的东西。
 */
class Auditor(private val dbg: Debugger) {

    data class Sample(
        val method: String,
        val dexPc: Int,
        val reg: Int,
        val name: String,
        /** smali 里 pN 是参数寄存器，vN 是局部寄存器——两者的调试信息来源不同，必须分开统计。 */
        val isParam: Boolean,
        val kind: String,
        val attempted: Boolean,
        val ok: Boolean,
        val error: String?,
    )

    data class MethodResult(
        val method: String,
        val stops: Int,
        val samples: List<Sample>,
        val note: String? = null,
    )

    /**
     * 审计若干方法。会为每个方法在入口下断点，然后启动应用；
     * 命中哪个就走完哪个，直到全部走完或轮次用尽。
     */
    fun audit(
        targets: List<MethodRef>,
        maxStepsPerMethod: Int = 300,
        timeoutMs: Long = 60_000,
        onProgress: (String) -> Unit = {},
    ): List<MethodResult> {
        val entryPc = HashMap<MethodRef, Int>()
        val bpIds = HashMap<MethodRef, Int>()

        for (t in targets) {
            val model = dbg.apk?.model(t) ?: continue
            val pc = model.instructions.firstOrNull()?.dexPc ?: continue
            entryPc[t] = pc
            bpIds[t] = dbg.addBreakpoint(t.fqcn, t.name, t.signature, pc).id
        }
        if (entryPc.isEmpty()) return emptyList()

        val results = ArrayList<MethodResult>()
        val remaining = entryPc.keys.toMutableSet()

        onProgress("启动应用并等待命中…")
        var st = dbg.actAndWait(timeoutMs) { dbg.start() }

        // 目标方法可能分散在多次调用里（本例中 runAll 会依次调用三个方法，
        // 且 onResume 会再触发一轮），所以允许多绕几圈。
        var rounds = 0
        while (remaining.isNotEmpty() && rounds++ < targets.size * 3) {
            if (st == null) break
            val f = st.frames.firstOrNull() ?: break
            val here = remaining.firstOrNull {
                it.fqcn == f.fqcn && it.name == f.method && it.signature == f.signature
            }
            if (here == null) {
                // 停在了别处（比如已审计完的方法又被调用），放行继续等。
                st = dbg.actAndWait(timeoutMs) { dbg.control("resume") }
                continue
            }

            onProgress("正在走完 ${here.name}${here.signature} …")
            results += walkThrough(here, maxStepsPerMethod, timeoutMs)
            remaining -= here
            bpIds[here]?.let { dbg.removeBreakpoint(it) }

            if (remaining.isEmpty()) break
            st = dbg.actAndWait(timeoutMs) { dbg.control("resume") }
        }

        remaining.forEach { miss ->
            results += MethodResult(miss.name + miss.signature, 0, emptyList(), "未被执行到，无数据")
            bpIds[miss]?.let { dbg.removeBreakpoint(it) }
        }
        return results
    }

    /** 从当前停留位置开始逐条单步，直到离开该方法。 */
    private fun walkThrough(target: MethodRef, maxSteps: Int, timeoutMs: Long): MethodResult {
        val samples = ArrayList<Sample>()
        var stops = 0
        var st: DebugState? = dbg.state

        while (stops < maxSteps) {
            val f = st?.frames?.firstOrNull() ?: break
            val stillHere = f.fqcn == target.fqcn && f.method == target.name && f.signature == target.signature
            if (!stillHere) break

            stops++
            for (r in f.registers) {
                val attempted = r.readable || r.error != null
                samples += Sample(
                    method = target.name + target.signature,
                    dexPc = f.dexPc,
                    reg = r.reg,
                    name = r.name,
                    isParam = r.name.startsWith("p"),
                    kind = r.type,
                    attempted = attempted,
                    ok = r.readable,
                    error = r.error,
                )
            }
            st = dbg.actAndWait(timeoutMs) { dbg.control("over") } ?: break
        }
        return MethodResult(target.name + target.signature, stops, samples)
    }

    companion object {

        /** 把结果渲染成一份能直接贴进文档的报告。 */
        fun report(label: String, results: List<MethodResult>): String = buildString {
            val all = results.flatMap { it.samples }
            val attempted = all.filter { it.attempted }

            appendLine("══ $label ══")
            appendLine()
            appendLine("%-34s %6s %8s %8s %8s".format("方法", "停留点", "应读", "实读", "可读率"))
            appendLine("─".repeat(70))
            for (r in results) {
                if (r.note != null) {
                    appendLine("%-34s %6s %8s %8s %8s   %s".format(r.method, "—", "—", "—", "—", r.note))
                    continue
                }
                val a = r.samples.count { it.attempted }
                val o = r.samples.count { it.ok }
                appendLine(
                    "%-34s %6d %8d %8d %7s".format(
                        r.method, r.stops, a, o, if (a == 0) "—" else "%.1f%%".format(100.0 * o / a)
                    )
                )
            }

            appendLine()
            val ok = attempted.count { it.ok }
            appendLine("合计：应读 ${attempted.size} 个（寄存器 × 停留点），实读 $ok 个，" +
                "可读率 ${pct(ok, attempted.size)}")

            // 这一段是本次审计真正要回答的问题。
            val params = attempted.filter { it.isParam }
            val locals = attempted.filter { !it.isParam }
            appendLine()
            appendLine("按寄存器种类拆分：")
            appendLine("  参数寄存器 pN：${params.count { it.ok }}/${params.size}  ${pct(params.count { it.ok }, params.size)}")
            appendLine("  局部寄存器 vN：${locals.count { it.ok }}/${locals.size}  ${pct(locals.count { it.ok }, locals.size)}")

            val failures = attempted.filter { !it.ok }
            if (failures.isNotEmpty()) {
                appendLine()
                appendLine("失败原因分布：")
                failures.groupingBy { it.error ?: "未知" }.eachCount()
                    .toList().sortedByDescending { it.second }
                    .forEach { (reason, n) -> appendLine("  %-40s %d".format(reason, n)) }

                val byReg = failures.groupingBy { it.name }.eachCount().toList().sortedByDescending { it.second }
                appendLine()
                appendLine("失败集中在哪些寄存器：")
                byReg.take(8).forEach { (name, n) -> appendLine("  %-6s %d 次".format(name, n)) }
            }

            // 「没尝试读」不算失败，但要说明它占多大比例，否则容易被误读成可读率低。
            val notAttempted = all.size - attempted.size
            appendLine()
            appendLine(
                "另有 $notAttempted 个（寄存器 × 停留点）静态分析就判定此处没有有效值" +
                    "（未初始化 / 类型冲突），不计入可读率。"
            )
        }

        private fun pct(a: Int, b: Int): String = if (b == 0) "—" else "%.1f%%".format(100.0 * a / b)
    }
}
