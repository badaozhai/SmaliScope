package com.smaliscope.frame

import com.smaliscope.analysis.MethodModel
import com.smaliscope.analysis.RegKind
import com.smaliscope.jdwp.ArrayCmds
import com.smaliscope.jdwp.ClassTypeCmds
import com.smaliscope.jdwp.FrameCmds
import com.smaliscope.jdwp.JdwpConnection
import com.smaliscope.jdwp.JdwpValue
import com.smaliscope.jdwp.ObjectCmds
import com.smaliscope.jdwp.RefTypeCmds
import com.smaliscope.jdwp.StringCmds
import com.smaliscope.jdwp.Tag
import com.smaliscope.jdwp.ThreadCmds
import com.smaliscope.session.FrameView
import com.smaliscope.session.ObjectField
import com.smaliscope.session.ObjectNode
import com.smaliscope.session.RegisterView
import com.smaliscope.session.RuntimeIndex

/**
 * 帧、寄存器、对象图的读取。
 *
 * 核心难点是「读寄存器要给对的 tag」：dex 寄存器无类型，tag 猜错不报错、只返回垃圾。
 * 因此这里只读 [MethodModel] 推导出可读类型的寄存器，推不出类型的老实标为「未初始化」，
 * 而不是瞎猜一个 tag 去读——宁可不显示，也不显示错的。
 */
private const val TYPE_MISMATCH = 34

class FrameReader(
    conn: JdwpConnection,
    private val runtime: RuntimeIndex,
) {
    private val frames = FrameCmds(conn)
    private val threads = ThreadCmds(conn)
    private val objects = ObjectCmds(conn)
    private val strings = StringCmds(conn)
    private val arrays = ArrayCmds(conn)
    private val refTypes = RefTypeCmds(conn)
    private val classTypes = ClassTypeCmds(conn)

    /** 读取整条调用栈。只有栈顶帧读寄存器，其余帧点开时再按需读。 */
    fun readStack(threadId: Long, topOnly: Boolean = true): List<FrameView> {
        val stack = threads.frames(threadId)
        return stack.mapIndexed { depth, f ->
            val (fqcn, name, sig) = runtime.describeLocation(f.location)
            val model = runtime.modelOf(f.location.classId, f.location.methodId)
            FrameView(
                frameId = f.frameId,
                depth = depth,
                fqcn = fqcn,
                method = name,
                signature = sig,
                dexPc = f.location.dexPc,
                hasModel = model != null,
                registers = if (depth == 0 || !topOnly) {
                    readRegisters(threadId, f.frameId, model, f.location.dexPc)
                } else emptyList(),
            )
        }
    }

    /**
     * 读一帧的全部寄存器。可读的一次往返批量取，保证同一帧内数据一致。
     */
    fun readRegisters(
        threadId: Long,
        frameId: Long,
        model: MethodModel?,
        dexPc: Int,
    ): List<RegisterView> {
        if (model == null) return emptyList()

        val kinds = model.registerKindsAt(dexPc)
        val slots = ArrayList<Pair<Int, Int>>()
        kinds.forEachIndexed { reg, kind ->
            kind.jdwpTag?.let { slots += reg to it }
        }

        val values = HashMap<Int, JdwpValue>()
        val errors = HashMap<Int, String>()
        if (slots.isNotEmpty()) {
            val read = runCatching { frames.getValues(threadId, frameId, slots) }.getOrNull()
            if (read != null && read.size == slots.size) {
                slots.forEachIndexed { i, (reg, _) -> values[reg] = read[i] }
            } else {
                // 批量失败常见于个别 slot 在当前位置不可读；退化为逐个读，
                // 让一个坏寄存器不至于把整个面板打空。
                slots.forEach { (reg, tag) ->
                    val (v, err) = readOne(threadId, frameId, reg, tag)
                    if (v != null) values[reg] = v else errors[reg] = err ?: "读取失败"
                }
            }
        }

        return kinds.mapIndexed { reg, kind ->
            val v = values[reg]
            val (display, objId) = when {
                kind.isWideHigh -> "（${model.regName(reg - 1)} 的高半部）" to null
                !kind.readable -> "—" to null
                v == null -> (errors[reg] ?: "读取失败") to null
                else -> formatValue(v, kind)
            }
            RegisterView(
                reg = reg,
                name = model.regName(reg),
                type = kind.cn,
                value = display,
                changed = false,
                readable = kind.readable && v != null,
                objectId = objId,
                expandable = objId != null && objId != 0L,
                hint = model.paramHint(reg),
            )
        }
    }

    /**
     * 读单个寄存器，带一次类型回退。
     *
     * ART 校验读取用的 tag 时，依据的是 dex 调试信息里该 slot「声明」的类型，
     * 而不是当前 dex_pc 上它实际持有的类型。d8 会把声明为 int 的参数寄存器
     * 复用来存对象（本项目测试应用的 Point.<init> 里 p1 就是如此），
     * 这时按推导出的引用类型去读会被拒为 TYPE_MISMATCH(34)。
     *
     * 回退成按 INT 读能拿到原始位模式，但那对用户没有意义（是个堆内偏移），
     * 所以这里只把它用来区分「确实读不了」和「类型对不上」，给出可理解的说明。
     */
    private fun readOne(threadId: Long, frameId: Long, reg: Int, tag: Int): Pair<JdwpValue?, String?> {
        try {
            return frames.getValues(threadId, frameId, listOf(reg to tag)).firstOrNull() to null
        } catch (e: com.smaliscope.jdwp.JdwpException) {
            if (e.errorCode != TYPE_MISMATCH) {
                return null to com.smaliscope.jdwp.JdwpError.describe(e.errorCode)
            }
        } catch (e: Throwable) {
            return null to (e.message ?: "读取失败")
        }
        val alt = if (tag == Tag.OBJECT) Tag.INT else Tag.OBJECT
        val ok = runCatching {
            frames.getValues(threadId, frameId, listOf(reg to alt)).firstOrNull()
        }.getOrNull() != null
        return null to if (ok) "该寄存器被复用，类型与声明不符，暂不可读" else "此位置不可读"
    }

    /** 把 JDWP 值渲染成新手能看懂的字符串，返回 (显示文本, 对象 ID)。 */
    fun formatValue(v: JdwpValue, kind: RegKind): Pair<String, Long?> = when (v) {
        is JdwpValue.Int32 -> when (kind) {
            RegKind.BOOLEAN -> (if (v.v != 0) "true" else "false") to null
            RegKind.CHAR -> "'${v.v.toChar()}' (${v.v})" to null
            RegKind.NULL -> (if (v.v == 0) "0 或 null" else v.v.toString()) to null
            else -> v.v.toString() to null
        }
        is JdwpValue.Int64 -> v.v.toString() to null
        is JdwpValue.Float32 -> v.v.toString() to null
        is JdwpValue.Float64 -> v.v.toString() to null
        is JdwpValue.Bool -> v.v.toString() to null
        is JdwpValue.Byte8 -> v.v.toString() to null
        is JdwpValue.Char16 -> "'${v.v}'" to null
        is JdwpValue.Short16 -> v.v.toString() to null
        is JdwpValue.Obj -> formatObject(v)
        JdwpValue.Void -> "void" to null
    }

    private fun formatObject(v: JdwpValue.Obj): Pair<String, Long?> {
        if (v.isNull) return "null" to null
        return when (v.tag) {
            Tag.STRING -> {
                val s = runCatching { strings.value(v.id) }.getOrNull()
                (if (s == null) "String@${v.id}" else "\"${s.take(120)}\"") to v.id
            }
            Tag.ARRAY -> {
                val len = runCatching { arrays.length(v.id) }.getOrNull()
                val type = typeNameOf(v.id)
                (if (len == null) "$type@${v.id}" else "$type(长度 $len)") to v.id
            }
            else -> "${typeNameOf(v.id)}@${v.id}" to v.id
        }
    }

    private fun typeNameOf(objectId: Long): String = runCatching {
        val (_, typeId) = objects.referenceType(objectId)
        prettyType(refTypes.signature(typeId))
    }.getOrDefault("对象")

    /** Lcom/foo/Bar; → Bar，[I → int[]，方便面板窄列显示。 */
    private fun prettyType(sig: String): String = when {
        sig.startsWith("[") -> prettyType(sig.substring(1)) + "[]"
        sig.startsWith("L") && sig.endsWith(";") ->
            sig.substring(1, sig.length - 1).replace('/', '.').substringAfterLast('.')
        sig == "I" -> "int"
        sig == "J" -> "long"
        sig == "Z" -> "boolean"
        sig == "B" -> "byte"
        sig == "C" -> "char"
        sig == "S" -> "short"
        sig == "F" -> "float"
        sig == "D" -> "double"
        else -> sig
    }

    /**
     * 展开一个对象为字段列表。懒加载：只展开一层，子节点点开时再取。
     * 数组按元素展开，最多取前 [maxElements] 个。
     */
    fun expandObject(objectId: Long, maxElements: Int = 100): ObjectNode {
        if (objectId == 0L) return ObjectNode(0, "null", "null", emptyList())

        val (_, typeId) = objects.referenceType(objectId)
        val sig = refTypes.signature(typeId)
        val typeName = prettyType(sig)

        if (sig.startsWith("[")) {
            val len = arrays.length(objectId)
            val take = minOf(len, maxElements)
            val elems = if (take > 0) arrays.getValues(objectId, 0, take) else emptyList()
            val elemKind = kindOfSignature(sig.substring(1))
            return ObjectNode(
                objectId = objectId,
                label = "$typeName(长度 $len)",
                type = typeName,
                arrayLength = len,
                truncated = take < len,
                fields = elems.mapIndexed { i, value ->
                    val (display, oid) = formatValue(value, elemKind)
                    ObjectField("[$i]", prettyType(sig.substring(1)), display, oid, oid != null)
                },
            )
        }

        // 沿继承链把父类字段也收进来，否则只能看到子类自己声明的那几个。
        val fieldDefs = ArrayList<com.smaliscope.jdwp.JdwpField>()
        var cur = typeId
        var guard = 0
        while (cur != 0L && guard++ < 12) {
            fieldDefs += runCatching { refTypes.fields(cur) }.getOrDefault(emptyList())
                // shadow$_klass_ / shadow$_monitor_ 是 ART 挂在 java.lang.Object 上的内部字段，
                // 属于运行时实现细节，对用户没有意义。
                .filter { !it.isStatic && !it.name.startsWith("shadow$") }
            cur = runCatching { classTypes.superclass(cur) }.getOrDefault(0L)
        }
        val distinct = fieldDefs.distinctBy { it.fieldId }
        val values = runCatching { objects.getValues(objectId, distinct.map { it.fieldId }) }
            .getOrDefault(emptyList())

        return ObjectNode(
            objectId = objectId,
            label = "$typeName@$objectId",
            type = typeName,
            fields = distinct.mapIndexed { i, f ->
                val value = values.getOrNull(i)
                val kind = kindOfSignature(f.signature)
                val (display, oid) = if (value == null) "—" to null else formatValue(value, kind)
                ObjectField(f.name, prettyType(f.signature), display, oid, oid != null)
            },
        )
    }

    private fun kindOfSignature(sig: String): RegKind = when {
        sig.startsWith("L") || sig.startsWith("[") -> RegKind.REFERENCE
        sig == "Z" -> RegKind.BOOLEAN
        sig == "B" -> RegKind.BYTE
        sig == "C" -> RegKind.CHAR
        sig == "S" -> RegKind.SHORT
        sig == "I" -> RegKind.INT
        sig == "J" -> RegKind.LONG_LO
        sig == "F" -> RegKind.FLOAT
        sig == "D" -> RegKind.DOUBLE_LO
        else -> RegKind.UNKNOWN
    }
}
