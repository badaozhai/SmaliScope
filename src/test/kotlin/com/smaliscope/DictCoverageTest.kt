package com.smaliscope

import com.android.tools.smali.dexlib2.Opcode
import com.smaliscope.dict.SmaliDict
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 词典覆盖率：dexlib2 的 Opcode 枚举是 dex 指令的权威全集，
 * 逐个检查每条指令在 SmaliDict 里都能查到解释（ROADMAP 第 4.1 项验收）。
 */
class DictCoverageTest {

    /**
     * 会出现在普通 APK dex 里的指令。用 dexlib2 自己的 `odexOnly()` 判定，
     * 比按名字猜可靠：*-quick / *-volatile / execute-inline 这些优化专用形式
     * 只在 odex/vdex 里出现，正常反编译 APK 见不到，词典不为它们写条目。
     * payload 是数据块不是指令，UI 不会把它当一行代码显示，一并排除。
     */
    private fun normalDexOpcodes(): List<String> =
        Opcode.values().asSequence()
            .filter { it.name != null && !it.odexOnly() }
            .map { it.name!! }
            .filter { !it.endsWith("-payload") }
            .distinct()
            .sorted()
            .toList()

    @Test
    fun `每条普通 dex 指令都有中文解释`() {
        val opcodes = normalDexOpcodes()
        val missing = opcodes.filter { SmaliDict.lookup(it) == null }
        if (missing.isNotEmpty()) {
            println("共 ${opcodes.size} 条，未覆盖 ${missing.size} 条：")
            missing.forEach { println("  $it") }
        }
        assertTrue(missing.isEmpty(), "有 ${missing.size} 条指令没有词典解释")
    }

    /**
     * 抽样核对解释是否「对」，而不只是「非空」。
     * `-` 前缀匹配比较宽，重点验证易冲突的指令落到的是正确的族，
     * 没有被更短的前缀误抓（如 const-method-handle 不能落到 const）。
     */
    @Test
    fun `抽样核对：变体落到正确的解释而非被短前缀误抓`() {
        val cases = mapOf(
            // 字段/数组访问用 `-` 分隔类型变体
            "iget-object" to "读实例字段", "iput-boolean" to "写实例字段",
            "sget-wide" to "读静态字段", "sput-object" to "写静态字段",
            "aget-byte" to "读数组元素", "aput-wide" to "写数组元素",
            // 精确基名 + `/` 变体（这类此前查不到，是本次修的重点）
            "goto/16" to "无条件跳转", "goto/32" to "无条件跳转",
            "filled-new-array/range" to "构造一个数组",
            "const-string/jumbo" to "字符串常量",
            // 不能被更短前缀误抓
            "const-method-handle" to "MethodHandle", "const-method-type" to "MethodType",
            "rsub-int/lit8" to "反向减",
            // 补齐的 long / float / double 算术
            "ushr-long/2addr" to "逻辑右移", "and-long/2addr" to "按位与",
            "neg-float" to "取负", "rem-double/2addr" to "取余",
            "double-to-float" to "double 转 float", "float-to-long" to "float 转 long",
        )
        val wrong = cases.filter { (op, kw) -> SmaliDict.describe(op)?.contains(kw) != true }
        wrong.forEach { (op, kw) -> println("  $op 期望含「$kw」，实际：${SmaliDict.describe(op)}") }
        assertTrue(wrong.isEmpty(), "${wrong.size} 条抽样解释不符合预期")
    }
}
