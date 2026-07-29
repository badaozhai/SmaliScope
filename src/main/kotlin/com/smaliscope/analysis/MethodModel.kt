package com.smaliscope.analysis

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.analysis.AnalyzedInstruction
import com.android.tools.smali.dexlib2.analysis.ClassPath
import com.android.tools.smali.dexlib2.analysis.MethodAnalyzer
import com.android.tools.smali.dexlib2.analysis.RegisterType
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.Reference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.smaliscope.jdwp.Tag

/** 寄存器的推导类型。由 dexlib2 的 RegisterType 归并而来，直接决定读寄存器时用哪个 JDWP tag。 */
enum class RegKind(val cn: String) {
    UNKNOWN("未知"),
    UNINIT("未初始化"),

    /** 不同分支带来的类型对不上，该寄存器在此处不可用——通常是被复用前的空档。 */
    CONFLICTED("此处不可用"),
    BOOLEAN("boolean"),
    BYTE("byte"),
    CHAR("char"),
    SHORT("short"),
    INT("int"),
    FLOAT("float"),
    LONG_LO("long"),
    LONG_HI("long(高半)"),
    DOUBLE_LO("double"),
    DOUBLE_HI("double(高半)"),
    REFERENCE("引用"),

    /**
     * dex 里 `const/4 v0, 0` 这类字面量 0 天生二义：可能是整数 0，也可能是空引用，
     * 要等后续指令怎么用它才能定下来。这不是分析失败，是 dex 本身如此。
     */
    NULL("未定型");

    /** 该类型对应的 JDWP tag；null 表示不可读（未初始化/未知）。 */
    val jdwpTag: Int?
        get() = when (this) {
            // 所有 32 位整型族统一按 INT 读：ART 上按窄类型读容易触发类型校验失败，
            // 而窄类型的语义（true/false、字符）在 UI 层按 RegKind 渲染即可。
            BOOLEAN, BYTE, CHAR, SHORT, INT -> Tag.INT
            FLOAT -> Tag.FLOAT
            LONG_LO -> Tag.LONG
            DOUBLE_LO -> Tag.DOUBLE
            REFERENCE -> Tag.OBJECT
            // 未定型的值按 INT 读：位模式相同，但读成 INT 不会凭空造出一个对象引用。
            NULL -> Tag.INT
            LONG_HI, DOUBLE_HI -> null   // 高半部随低半一起读出，不单独取
            UNKNOWN, UNINIT, CONFLICTED -> null
        }

    val isWideHigh: Boolean get() = this == LONG_HI || this == DOUBLE_HI
    val readable: Boolean get() = jdwpTag != null
}

private fun RegisterType.toKind(): RegKind = when (category) {
    RegisterType.UNKNOWN -> RegKind.UNKNOWN
    RegisterType.UNINIT -> RegKind.UNINIT
    RegisterType.NULL -> RegKind.NULL
    RegisterType.ONE, RegisterType.BOOLEAN -> RegKind.BOOLEAN
    RegisterType.BYTE, RegisterType.POS_BYTE -> RegKind.BYTE
    RegisterType.SHORT, RegisterType.POS_SHORT -> RegKind.SHORT
    RegisterType.CHAR -> RegKind.CHAR
    RegisterType.INTEGER -> RegKind.INT
    RegisterType.FLOAT -> RegKind.FLOAT
    RegisterType.LONG_LO -> RegKind.LONG_LO
    RegisterType.LONG_HI -> RegKind.LONG_HI
    RegisterType.DOUBLE_LO -> RegKind.DOUBLE_LO
    RegisterType.DOUBLE_HI -> RegKind.DOUBLE_HI
    RegisterType.UNINIT_REF, RegisterType.UNINIT_THIS, RegisterType.REFERENCE -> RegKind.REFERENCE
    RegisterType.CONFLICTED -> RegKind.CONFLICTED
    else -> RegKind.UNKNOWN
}

/** 一条 smali 指令的完整静态信息。 */
data class SmaliInsn(
    val index: Int,
    /** code unit 偏移，即 JDWP Location.index。 */
    val dexPc: Int,
    val codeUnits: Int,
    val opcodeName: String,
    val text: String,
    /** 读取的寄存器（含 wide 的高半部）。数据流箭头的「源」。 */
    val reads: List<Int>,
    /** 写入的寄存器。数据流箭头的「汇」。 */
    val writes: List<Int>,
    /** 所有可能后继的 dex_pc：顺序下一条 + 跳转目标 + 异常处理器入口。 */
    val successors: List<Int>,
    val isInvoke: Boolean,
    val isReturn: Boolean,
    val isBranch: Boolean,
    val isPayload: Boolean,
    val canContinue: Boolean,
    /** invoke-* 的目标方法，供 step-into 定位。 */
    val invokeTarget: MethodRef?,
)

data class MethodRef(val fqcn: String, val name: String, val signature: String) {
    override fun toString() = "$fqcn.$name$signature"
}

/** 基本块，CFG 的节点。 */
data class BasicBlock(
    val id: Int,
    val startPc: Int,
    val endPc: Int,
    val insnIndices: IntRange,
    val successors: List<Int>,
)

/**
 * 一个方法的完整静态模型：dex_pc 偏移表、CFG、以及最关键的逐指令寄存器类型推导。
 *
 * 类型推导是整个调试器的地基：dex 寄存器无类型，而 JDWP 读寄存器要 (slot, tag) 二元组，
 * tag 猜错不会报错、只会读到垃圾值。这里用 dexlib2 的 MethodAnalyzer 做数据流分析拿到真值。
 */
class MethodModel(
    val fqcn: String,
    val name: String,
    val signature: String,
    val isStatic: Boolean,
    val registerCount: Int,
    val paramRegisterCount: Int,
    val instructions: List<SmaliInsn>,
    val basicBlocks: List<BasicBlock>,
    private val analyzed: List<AnalyzedInstruction>?,
    /** MethodAnalyzer 未能完成时的原因，UI 用来解释「为什么寄存器类型是未知」。 */
    val analysisError: String?,
) {
    val byDexPc: Map<Int, SmaliInsn> = instructions.associateBy { it.dexPc }
    private val indexByDexPc: Map<Int, Int> = instructions.associate { it.dexPc to it.index }

    val key: MethodRef get() = MethodRef(fqcn, name, signature)

    /** smali 习惯：最后 paramRegisterCount 个寄存器叫 p0..pN，其余叫 v0..vN。 */
    fun regName(reg: Int): String {
        val firstParam = registerCount - paramRegisterCount
        return if (paramRegisterCount > 0 && reg >= firstParam) "p${reg - firstParam}" else "v$reg"
    }

    /** 参数寄存器在源码里的名字提示，例如实例方法的 p0 就是 this。 */
    fun paramHint(reg: Int): String? {
        val firstParam = registerCount - paramRegisterCount
        if (reg < firstParam) return null
        if (!isStatic && reg == firstParam) return "this"
        return null
    }

    /** 执行到 dexPc「之前」，寄存器 reg 的推导类型。 */
    fun registerTypeAt(dexPc: Int, reg: Int): RegKind {
        val ai = analyzedAt(dexPc) ?: return RegKind.UNKNOWN
        if (reg >= registerCount) return RegKind.UNKNOWN
        return runCatching { ai.getPreInstructionRegisterType(reg).toKind() }
            .getOrDefault(RegKind.UNKNOWN)
    }

    /** 该位置上所有寄存器的类型快照，寄存器面板据此决定读哪些、用什么 tag。 */
    fun registerKindsAt(dexPc: Int): List<RegKind> =
        (0 until registerCount).map { registerTypeAt(dexPc, it) }

    private fun analyzedAt(dexPc: Int): AnalyzedInstruction? {
        val list = analyzed ?: return null
        val idx = indexByDexPc[dexPc] ?: return null
        // MethodAnalyzer 会在真实指令前插一条虚拟的「方法入口」指令，按长度差对齐。
        val offset = list.size - instructions.size
        return list.getOrNull(idx + offset)
    }

    fun insnAt(dexPc: Int): SmaliInsn? = byDexPc[dexPc]

    fun blockContaining(dexPc: Int): BasicBlock? =
        basicBlocks.firstOrNull { dexPc >= it.startPc && dexPc <= it.endPc }

    override fun toString() = "$fqcn.$name$signature (${instructions.size} 条指令, $registerCount 寄存器)"
}

// ── 构建 ──────────────────────────────────────────────────────────────────────

/** 由参数签名算出参数占用的寄存器数（long/double 占两个，实例方法额外算上 this）。 */
fun paramRegisterCount(parameterTypes: List<String>, isStatic: Boolean): Int {
    var n = if (isStatic) 0 else 1
    for (t in parameterTypes) n += if (t == "J" || t == "D") 2 else 1
    return n
}

fun jvmSignature(parameterTypes: List<String>, returnType: String): String =
    "(${parameterTypes.joinToString("")})$returnType"

fun typeToFqcn(type: String): String =
    if (type.startsWith("L") && type.endsWith(";")) {
        type.substring(1, type.length - 1).replace('/', '.')
    } else type

/**
 * 把一个 dexlib2 Method 解析成 [MethodModel]。
 *
 * classPath 为 null 时跳过类型推导（仍可反汇编和下断点，只是寄存器读不出来）。
 */
fun buildMethodModel(method: Method, classPath: ClassPath?): MethodModel? {
    val impl = method.implementation ?: return null
    val isStatic = method.accessFlags and 0x0008 != 0
    val fqcn = typeToFqcn(method.definingClass)
    val sig = jvmSignature(method.parameterTypes.map { it.toString() }, method.returnType)

    // 第一遍：算 dex_pc 偏移表。
    val raw = ArrayList<Pair<Int, Instruction>>()
    var pc = 0
    for (insn in impl.instructions) {
        raw += pc to insn
        pc += insn.codeUnits
    }
    val instructionAt = raw.toMap()

    // 类型推导。失败不致命：降级为「类型未知」，断点与单步仍可用。
    var analyzedList: List<AnalyzedInstruction>? = null
    var analysisError: String? = null
    if (classPath != null) {
        try {
            val analyzer = MethodAnalyzer(classPath, method, null, false)
            analyzedList = analyzer.analyzedInstructions
            analyzer.analysisException?.let { analysisError = it.message ?: it.toString() }
        } catch (t: Throwable) {
            analysisError = t.message ?: t.toString()
        }
    } else {
        analysisError = "未提供 classPath"
    }

    val regCount = impl.registerCount
    val paramRegs = paramRegisterCount(method.parameterTypes.map { it.toString() }, isStatic)

    val nameOf = { reg: Int ->
        val firstParam = regCount - paramRegs
        if (paramRegs > 0 && reg >= firstParam) "p${reg - firstParam}" else "v$reg"
    }

    // 异常处理器入口：落在 try 区间内且可能抛异常的指令，都要把 handler 当作后继。
    val handlersFor = HashMap<Int, MutableList<Int>>()
    for (tb in impl.tryBlocks) {
        val start = tb.startCodeAddress
        val end = start + tb.codeUnitCount
        val targets = tb.exceptionHandlers.map { it.handlerCodeAddress }
        for ((p, _) in raw) {
            if (p in start until end) handlersFor.getOrPut(p) { mutableListOf() }.addAll(targets)
        }
    }

    val insns = ArrayList<SmaliInsn>(raw.size)
    raw.forEachIndexed { index, (dexPc, insn) ->
        val op = insn.opcode
        val isPayload = op == Opcode.PACKED_SWITCH_PAYLOAD ||
            op == Opcode.SPARSE_SWITCH_PAYLOAD ||
            op == Opcode.ARRAY_PAYLOAD

        val writes = if (!isPayload && op.setsRegister()) {
            val a = (insn as OneRegisterInstruction).registerA
            if (op.setsWideRegister()) listOf(a, a + 1) else listOf(a)
        } else emptyList()
        val reads = if (isPayload) emptyList() else sourceRegisters(insn)

        val successors = ArrayList<Int>()
        if (!isPayload) {
            val next = dexPc + insn.codeUnits
            if (op.canContinue() && instructionAt.containsKey(next)) successors += next
            if (insn is OffsetInstruction) {
                when (op) {
                    Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH -> {
                        val payload = instructionAt[dexPc + insn.codeOffset]
                        if (payload is SwitchPayload) {
                            payload.switchElements.forEach { successors += dexPc + it.offset }
                        }
                    }
                    // fill-array-data 的 offset 指向数据载荷，不是跳转。
                    Opcode.FILL_ARRAY_DATA -> Unit
                    else -> successors += dexPc + insn.codeOffset
                }
            }
            if (op.canThrow()) handlersFor[dexPc]?.let { successors += it }
        }

        val invokeTarget = (insn as? com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction)
            ?.reference
            ?.let { it as? MethodReference }
            ?.let { MethodRef(typeToFqcn(it.definingClass), it.name, jvmSignature(it.parameterTypes.map { p -> p.toString() }, it.returnType)) }

        insns += SmaliInsn(
            index = index,
            dexPc = dexPc,
            codeUnits = insn.codeUnits,
            opcodeName = op.name,
            text = formatInsn(insn, dexPc, nameOf),
            reads = reads,
            writes = writes,
            successors = successors.distinct().sorted(),
            isInvoke = op.name.startsWith("invoke-"),
            isReturn = op.name.startsWith("return"),
            isBranch = insn is OffsetInstruction && op != Opcode.FILL_ARRAY_DATA,
            isPayload = isPayload,
            canContinue = op.canContinue(),
            invokeTarget = if (op.name.startsWith("invoke-")) invokeTarget else null,
        )
    }

    // wide 类型的读，把高半部补进来（靠推导出的类型判断，不用逐 opcode 硬编码表）。
    val withWide = if (analyzedList != null) {
        val tmp = MethodModel(fqcn, method.name, sig, isStatic, regCount, paramRegs, insns, emptyList(), analyzedList, analysisError)
        insns.map { ins ->
            val extra = ins.reads.filter { r ->
                val k = tmp.registerTypeAt(ins.dexPc, r)
                (k == RegKind.LONG_LO || k == RegKind.DOUBLE_LO) && (r + 1) !in ins.reads
            }.map { it + 1 }
            if (extra.isEmpty()) ins else ins.copy(reads = (ins.reads + extra).distinct().sorted())
        }
    } else insns

    return MethodModel(
        fqcn = fqcn,
        name = method.name,
        signature = sig,
        isStatic = isStatic,
        registerCount = regCount,
        paramRegisterCount = paramRegs,
        instructions = withWide,
        basicBlocks = buildBasicBlocks(withWide),
        analyzed = analyzedList,
        analysisError = analysisError,
    )
}

/** 切分基本块：跳转目标与分支指令的下一条都是块边界。 */
private fun buildBasicBlocks(insns: List<SmaliInsn>): List<BasicBlock> {
    if (insns.isEmpty()) return emptyList()
    val code = insns.filter { !it.isPayload }
    if (code.isEmpty()) return emptyList()

    val leaders = sortedSetOf(code.first().dexPc)
    for (ins in code) {
        if (ins.isBranch || ins.isReturn || !ins.canContinue) {
            ins.successors.forEach { leaders += it }
            val next = ins.dexPc + ins.codeUnits
            if (code.any { it.dexPc == next }) leaders += next
        }
    }

    val leaderList = leaders.filter { pc -> code.any { it.dexPc == pc } }
    val blocks = ArrayList<BasicBlock>()
    leaderList.forEachIndexed { i, start ->
        val nextLeader = leaderList.getOrNull(i + 1)
        val body = code.filter { it.dexPc >= start && (nextLeader == null || it.dexPc < nextLeader) }
        if (body.isNotEmpty()) {
            blocks += BasicBlock(
                id = i,
                startPc = start,
                endPc = body.last().dexPc,
                insnIndices = body.first().index..body.last().index,
                successors = body.last().successors,
            )
        }
    }
    return blocks
}

/**
 * 指令的「源」寄存器。
 *
 * 不能写成「全部引用的寄存器减去写入的」——像 `add-int/lit8 v0, v0, 1`（i = i + 1）
 * 读写同一个寄存器，集合相减会把这条读边整个吃掉，数据流箭头就断了。
 * 按操作数位置判定：B/C 位以及 invoke 的参数列表恒为读，A 位仅在不写入时为读。
 */
private fun sourceRegisters(insn: Instruction): List<Int> {
    val out = ArrayList<Int>(5)
    when (insn) {
        is FiveRegisterInstruction -> {
            val regs = intArrayOf(insn.registerC, insn.registerD, insn.registerE, insn.registerF, insn.registerG)
            for (i in 0 until insn.registerCount) out += regs[i]
        }
        is RegisterRangeInstruction ->
            for (i in 0 until insn.registerCount) out += insn.startRegister + i
        else -> {
            if (insn is ThreeRegisterInstruction) out += insn.registerC
            if (insn is TwoRegisterInstruction) out += insn.registerB
            if (insn is OneRegisterInstruction) {
                // A 位在不写入时是读（如 iput/aput/if-*/return），
                // 写入时若为 /2addr 形式（vA = vA op vB）也仍然是读。
                if (!insn.opcode.setsRegister() || insn.opcode.name.endsWith("/2addr")) {
                    out += insn.registerA
                }
            }
        }
    }
    return out.distinct().sorted()
}

/** 生成 smali 文本。自己拼而不用 baksmali 的整类输出，是为了逐指令带上 dex_pc 与寄存器命名。 */
private fun formatInsn(insn: Instruction, dexPc: Int, regName: (Int) -> String): String {
    val op = insn.opcode
    val parts = ArrayList<String>(4)

    when (insn) {
        is FiveRegisterInstruction -> {
            val regs = intArrayOf(insn.registerC, insn.registerD, insn.registerE, insn.registerF, insn.registerG)
            parts += (0 until insn.registerCount).joinToString(", ", "{", "}") { regName(regs[it]) }
        }
        is RegisterRangeInstruction -> {
            val n = insn.registerCount
            parts += if (n == 0) "{}" else {
                val a = insn.startRegister
                "{${regName(a)} .. ${regName(a + n - 1)}}"
            }
        }
        else -> {
            if (insn is OneRegisterInstruction) parts += regName(insn.registerA)
            if (insn is TwoRegisterInstruction) parts += regName(insn.registerB)
            if (insn is ThreeRegisterInstruction) parts += regName(insn.registerC)
        }
    }

    when {
        insn is WideLiteralInstruction && hasLiteral(op) -> parts += formatLiteral(insn.wideLiteral)
        insn is NarrowLiteralInstruction && hasLiteral(op) -> parts += formatLiteral(insn.narrowLiteral.toLong())
    }

    if (insn is com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction) {
        parts += formatReference(insn.reference)
    }

    if (insn is OffsetInstruction) {
        parts += ":pc_${dexPc + insn.codeOffset}"
    }

    return if (parts.isEmpty()) op.name else "${op.name} ${parts.joinToString(", ")}"
}

/**
 * 该指令格式是否带字面量。必须白名单枚举：OffsetInstruction 也实现了 NarrowLiteralInstruction，
 * 靠后缀猜会把跳转偏移当字面量打印（Format21t 的 't' 与 Format21s 的 's' 无从区分）。
 */
private val LITERAL_FORMATS = setOf(
    "Format11n",   // const/4
    "Format21s",   // const/16
    "Format21h",   // 兼容旧枚举名
    "Format21ih",  // const/high16
    "Format21lh",  // const-wide/high16
    "Format22b",   // add-int/lit8
    "Format22s",   // add-int/lit16
    "Format31i",   // const
    "Format51l",   // const-wide
)

private fun hasLiteral(op: Opcode): Boolean = op.format.name in LITERAL_FORMATS

private fun formatLiteral(v: Long): String =
    if (v in -9..9) v.toString() else "0x${java.lang.Long.toHexString(v)}"

private fun formatReference(ref: Reference): String = when (ref) {
    is MethodReference ->
        "${ref.definingClass}->${ref.name}(${ref.parameterTypes.joinToString("")})${ref.returnType}"
    is FieldReference -> "${ref.definingClass}->${ref.name}:${ref.type}"
    is StringReference -> "\"${escape(ref.string)}\""
    is TypeReference -> ref.type
    else -> ref.toString()
}

private fun escape(s: String): String = buildString {
    for (c in s) when (c) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> append(c)
    }
}
