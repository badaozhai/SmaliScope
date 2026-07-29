package com.smaliscope.explain

import com.smaliscope.session.Debugger
import java.util.concurrent.ConcurrentHashMap

/**
 * 把「这段 smali 在干什么」讲成人话。
 *
 * 上下文条件在本项目里格外好：带 dex_pc 的 smali、jadx 反编译出的 Java、
 * 以及**运行时寄存器的真实值**可以一起喂进去——最后这一项是别的静态工具给不出的。
 *
 * 边界（写死在这里，别放宽）：
 *  - 只在用户主动触发时调用，绝不进单步热路径——实时性是本项目的卖点，
 *    加一次网络往返就毁了；
 *  - 不用它替代寄存器类型推导。那必须是确定性的，猜错会静默读出垃圾值；
 *  - 不让它决定断点位置或判断「有没有漏洞」，那越过了项目边界，
 *    而新手最容易误信这类输出。
 */
class Explainer(
    private val dbg: Debugger,
    private val llm: LlmClient = LlmClient(),
) {
    val enabled: Boolean get() = llm.enabled

    private val cache = ConcurrentHashMap<String, String>()

    private val system = """
        你是一位讲解 Android 字节码的老师，面向的是刚接触 smali 的新手。要求：
        1. 全程用中文，简洁、具体，不要客套话和总结性废话。
        2. 先用两三句说清这段代码整体在做什么，再挑关键的几条指令解释。
        3. 如果给了寄存器的运行时值，务必结合实际值来讲，这是最有价值的部分。
        4. 寄存器值标注为「此处不可用」或「该寄存器被复用…」时，表示**读不出来**，
           不是 0 也不是 null，不要基于它做任何推断，也不要假装知道它的值。
        5. 不要编造代码里没有的东西。信息不足就直说哪里看不出来。
        6. 不要给安全结论（有无漏洞、是否恶意），那不是你的任务。
    """.trimIndent()

    /** 讲解一个方法；给了 dexPc 就重点讲那一条指令及其上下文。 */
    fun explainMethod(fqcn: String, method: String, signature: String, dexPc: Int?): String {
        val key = "$fqcn#$method$signature@${dexPc ?: -1}#${liveStamp()}"
        cache[key]?.let { return it }

        val view = dbg.methodView(fqcn, method, signature, dexPc)
            ?: throw LlmClient.LlmException("找不到方法 $fqcn.$method$signature")

        val sb = StringBuilder()
        sb.appendLine("类：$fqcn")
        sb.appendLine("方法：$method$signature（${view.registerCount} 个寄存器：${view.registerNames.joinToString(" ")}）")
        sb.appendLine()

        dbg.javaSource(fqcn).first?.let { java ->
            // Java 视图是整类的，只截取够用的一段，别把 token 全花在无关代码上。
            sb.appendLine("jadx 反编译出的 Java（可能不完全准确，仅供参考）：")
            sb.appendLine("```java")
            sb.appendLine(java.take(4000))
            sb.appendLine("```")
            sb.appendLine()
        }

        sb.appendLine("smali 指令（左侧是 dex_pc 偏移）：")
        sb.appendLine("```")
        for (i in view.instructions.take(200)) {
            val here = if (i.dexPc == dexPc) "  ← 当前停在这里" else ""
            sb.appendLine("%-6d %s%s".format(i.dexPc, i.text, here))
        }
        if (view.instructions.size > 200) sb.appendLine("…（还有 ${view.instructions.size - 200} 条未列出）")
        sb.appendLine("```")

        liveRegisters(fqcn, method, signature)?.let {
            sb.appendLine()
            sb.appendLine("此刻寄存器的真实值：")
            sb.appendLine("```")
            sb.append(it)
            sb.appendLine("```")
        }

        sb.appendLine()
        sb.appendLine(
            if (dexPc != null) "请重点讲解 dex_pc $dexPc 这条指令在做什么，以及它前后的上下文。"
            else "请讲解这个方法整体在做什么。"
        )

        return llm.chat(system, sb.toString()).also { cache[key] = it }
    }

    /**
     * 给混淆过的方法猜寄存器的语义名。
     * 混淆包里 v0/v1 毫无意义，而数据流和被调用的 framework API 往往足以推出用途。
     */
    fun nameRegisters(fqcn: String, method: String, signature: String): String {
        val view = dbg.methodView(fqcn, method, signature, null)
            ?: throw LlmClient.LlmException("找不到方法 $fqcn.$method$signature")

        val sb = StringBuilder()
        sb.appendLine("方法：$fqcn.$method$signature")
        sb.appendLine("寄存器：${view.registerNames.joinToString(" ")}")
        sb.appendLine()
        sb.appendLine("指令与数据流（读→写）：")
        sb.appendLine("```")
        for (i in view.instructions.take(200)) {
            val nm = { r: Int -> view.registerNames.getOrElse(r) { "v$r" } }
            sb.append("%-6d %-52s".format(i.dexPc, i.text))
            if (i.reads.isNotEmpty() || i.writes.isNotEmpty()) {
                sb.append("[读 ${i.reads.joinToString(",", transform = nm).ifEmpty { "—" }}")
                sb.append(" → 写 ${i.writes.joinToString(",", transform = nm).ifEmpty { "—" }}]")
            }
            sb.appendLine()
        }
        sb.appendLine("```")

        liveRegisters(fqcn, method, signature)?.let {
            sb.appendLine()
            sb.appendLine("其中部分寄存器此刻的真实值：")
            sb.appendLine("```")
            sb.append(it)
            sb.appendLine("```")
        }

        sb.appendLine()
        sb.appendLine(
            "请为每个寄存器猜一个有意义的名字（例如 索引 / 用户名 / 校验结果 / 临时缓冲），" +
                "一行一个，格式「v0 = 名字 —— 依据」。依据要引用具体指令或调用的 API。" +
                "推不出来的就写「无法判断」，不要硬猜。"
        )

        return llm.chat(system, sb.toString())
    }

    /** 当前若正停在该方法上，取其寄存器实际值。 */
    private fun liveRegisters(fqcn: String, method: String, signature: String): String? {
        val st = dbg.state
        if (st.status != "suspended") return null
        val f = st.frames.firstOrNull() ?: return null
        if (f.fqcn != fqcn || f.method != method || f.signature != signature) return null
        if (f.registers.isEmpty()) return null
        return buildString {
            appendLine("停在 dex_pc ${f.dexPc}")
            f.registers.forEach {
                append("  ${it.name.padEnd(4)} ${it.type.padEnd(8)} ${it.value}")
                it.hint?.let { h -> append(" ($h)") }
                appendLine()
            }
        }
    }

    /** 停留位置变了，讲解也该重新生成，所以把它并进缓存键。 */
    private fun liveStamp(): String {
        val st = dbg.state
        if (st.status != "suspended") return "static"
        val f = st.frames.firstOrNull() ?: return "static"
        return "${f.fqcn}.${f.method}@${f.dexPc}"
    }
}
