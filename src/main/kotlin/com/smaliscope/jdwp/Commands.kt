package com.smaliscope.jdwp

data class JdwpMethod(
    val methodId: Long,
    val name: String,
    val signature: String,
    val modBits: Int,
)

data class JdwpField(
    val fieldId: Long,
    val name: String,
    val signature: String,
    val modBits: Int,
) {
    val isStatic: Boolean get() = modBits and 0x0008 != 0
}

data class FrameInfo(val frameId: Long, val location: Location)

/** cmdSet 2：类元信息。 */
class RefTypeCmds(private val conn: JdwpConnection) {

    fun signature(typeId: Long): String {
        val data = conn.writer().writeRefTypeId(typeId).toByteArray()
        return conn.send(CmdSet.REFERENCE_TYPE, 1, data).readString()
    }

    fun fields(typeId: Long): List<JdwpField> {
        val data = conn.writer().writeRefTypeId(typeId).toByteArray()
        val r = conn.send(CmdSet.REFERENCE_TYPE, 4, data)
        val n = r.readInt()
        return (0 until n).map {
            JdwpField(r.readFieldId(), r.readString(), r.readString(), r.readInt())
        }
    }

    fun methods(typeId: Long): List<JdwpMethod> {
        val data = conn.writer().writeRefTypeId(typeId).toByteArray()
        val r = conn.send(CmdSet.REFERENCE_TYPE, 5, data)
        val n = r.readInt()
        return (0 until n).map {
            JdwpMethod(r.readMethodId(), r.readString(), r.readString(), r.readInt())
        }
    }

    fun sourceFile(typeId: Long): String? = runCatching {
        val data = conn.writer().writeRefTypeId(typeId).toByteArray()
        conn.send(CmdSet.REFERENCE_TYPE, 7, data).readString()
    }.getOrNull()

    /** 静态字段读取。 */
    fun getValues(typeId: Long, fieldIds: List<Long>): List<JdwpValue> {
        if (fieldIds.isEmpty()) return emptyList()
        val w = conn.writer().writeRefTypeId(typeId).writeInt(fieldIds.size)
        fieldIds.forEach { w.writeFieldId(it) }
        val r = conn.send(CmdSet.REFERENCE_TYPE, 6, w.toByteArray())
        val n = r.readInt()
        return (0 until n).map { r.readTaggedValue() }
    }
}

/** cmdSet 3：类继承关系，对象图要靠它把父类字段也展开出来。 */
class ClassTypeCmds(private val conn: JdwpConnection) {
    /** 返回父类 typeId；java.lang.Object 返回 0。 */
    fun superclass(classId: Long): Long {
        val data = conn.writer().writeRefTypeId(classId).toByteArray()
        return conn.send(3, 1, data).readRefTypeId()
    }
}

/** cmdSet 6：方法字节码与调试信息。 */
class MethodCmds(private val conn: JdwpConnection) {

    /** 真机上实际加载的 dex 字节码，可用来与 dexlib2 的静态解析结果交叉校验。 */
    fun bytecodes(typeId: Long, methodId: Long): ByteArray {
        val data = conn.writer().writeRefTypeId(typeId).writeMethodId(methodId).toByteArray()
        val r = conn.send(CmdSet.METHOD, 3, data)
        val n = r.readInt()
        return ByteArray(n) { r.readByte().toByte() }
    }

    /** 行号表。无调试信息时抛 ABSENT_INFORMATION——本项目不依赖它。 */
    fun lineTable(typeId: Long, methodId: Long): List<Pair<Long, Int>> = runCatching {
        val data = conn.writer().writeRefTypeId(typeId).writeMethodId(methodId).toByteArray()
        val r = conn.send(CmdSet.METHOD, 1, data)
        r.readLong(); r.readLong()
        val n = r.readInt()
        (0 until n).map { r.readLong() to r.readInt() }
    }.getOrDefault(emptyList())
}

/** cmdSet 9：对象读取，对象图展开的基础。 */
class ObjectCmds(private val conn: JdwpConnection) {

    fun referenceType(objectId: Long): Pair<Byte, Long> {
        val data = conn.writer().writeObjectId(objectId).toByteArray()
        val r = conn.send(CmdSet.OBJECT_REFERENCE, 1, data)
        return r.readByte().toByte() to r.readRefTypeId()
    }

    fun getValues(objectId: Long, fieldIds: List<Long>): List<JdwpValue> {
        if (fieldIds.isEmpty()) return emptyList()
        val w = conn.writer().writeObjectId(objectId).writeInt(fieldIds.size)
        fieldIds.forEach { w.writeFieldId(it) }
        val r = conn.send(CmdSet.OBJECT_REFERENCE, 2, w.toByteArray())
        val n = r.readInt()
        return (0 until n).map { r.readTaggedValue() }
    }
}

/** cmdSet 10：字符串取值，寄存器面板显示字面量用。 */
class StringCmds(private val conn: JdwpConnection) {
    fun value(objectId: Long): String {
        val data = conn.writer().writeObjectId(objectId).toByteArray()
        return conn.send(CmdSet.STRING_REFERENCE, 1, data).readString()
    }
}

/** cmdSet 11：线程与调用栈。 */
class ThreadCmds(private val conn: JdwpConnection) {

    fun name(threadId: Long): String {
        val data = conn.writer().writeObjectId(threadId).toByteArray()
        return conn.send(CmdSet.THREAD_REFERENCE, 1, data).readString()
    }

    fun suspend(threadId: Long) {
        conn.send(CmdSet.THREAD_REFERENCE, 2, conn.writer().writeObjectId(threadId).toByteArray())
    }

    fun resume(threadId: Long) {
        conn.send(CmdSet.THREAD_REFERENCE, 3, conn.writer().writeObjectId(threadId).toByteArray())
    }

    /** 返回 (threadStatus, suspendStatus)。suspendStatus 位 0 置位表示已挂起。 */
    fun status(threadId: Long): Pair<Int, Int> {
        val data = conn.writer().writeObjectId(threadId).toByteArray()
        val r = conn.send(CmdSet.THREAD_REFERENCE, 4, data)
        return r.readInt() to r.readInt()
    }

    fun frameCount(threadId: Long): Int {
        val data = conn.writer().writeObjectId(threadId).toByteArray()
        return conn.send(CmdSet.THREAD_REFERENCE, 7, data).readInt()
    }

    /** length = -1 表示取到栈底。 */
    fun frames(threadId: Long, start: Int = 0, length: Int = -1): List<FrameInfo> {
        val data = conn.writer().writeObjectId(threadId).writeInt(start).writeInt(length).toByteArray()
        val r = conn.send(CmdSet.THREAD_REFERENCE, 6, data)
        val n = r.readInt()
        return (0 until n).map { FrameInfo(r.readFrameId(), r.readLocation()) }
    }
}

/** cmdSet 13：数组。 */
class ArrayCmds(private val conn: JdwpConnection) {

    fun length(arrayId: Long): Int {
        val data = conn.writer().writeObjectId(arrayId).toByteArray()
        return conn.send(CmdSet.ARRAY_REFERENCE, 1, data).readInt()
    }

    fun getValues(arrayId: Long, first: Int, count: Int): List<JdwpValue> {
        if (count <= 0) return emptyList()
        val data = conn.writer().writeObjectId(arrayId).writeInt(first).writeInt(count).toByteArray()
        val r = conn.send(CmdSet.ARRAY_REFERENCE, 2, data)
        // ArrayRegion：byte tag + int length + values。对象型元素带 tag，基本类型不带。
        val tag = r.readByte()
        val n = r.readInt()
        return (0 until n).map {
            if (Tag.isObject(tag)) r.readTaggedValue() else r.readUntaggedValue(tag)
        }
    }
}

/** cmdSet 16：帧内寄存器。这里的 slot 在 ART 上就是 dex 寄存器号。 */
class FrameCmds(private val conn: JdwpConnection) {

    /**
     * 按 (slot, tag) 批量读寄存器。
     *
     * ⚠️ dex 寄存器无类型，tag 必须由 dexlib2 的 MethodAnalyzer 静态推导得出；
     * 猜错不会报错，只会读到垃圾值。见 analysis/TypeInference。
     */
    fun getValues(threadId: Long, frameId: Long, slots: List<Pair<Int, Int>>): List<JdwpValue> {
        if (slots.isEmpty()) return emptyList()
        val w = conn.writer().writeObjectId(threadId).writeFrameId(frameId).writeInt(slots.size)
        slots.forEach { (slot, tag) -> w.writeInt(slot).writeByte(tag) }
        val r = conn.send(CmdSet.STACK_FRAME, 1, w.toByteArray())
        val n = r.readInt()
        return (0 until n).map { r.readTaggedValue() }
    }

    /**
     * 写回若干寄存器（StackFrame.SetValues，cmdSet 16 cmd 2）。
     *
     * ⚠️ 与读同理，tag 必须与该 slot 实际持有的类型一致；写错 tag 不会报错，
     * 只会悄悄破坏帧。所以调用方只应传由类型推导得出的 tag。
     * 每项编码为：int slot、byte tag、随后是按 tag 定长的**未加标签**值。
     */
    fun setValues(threadId: Long, frameId: Long, updates: List<Triple<Int, Int, JdwpValue>>) {
        if (updates.isEmpty()) return
        val w = conn.writer().writeObjectId(threadId).writeFrameId(frameId).writeInt(updates.size)
        updates.forEach { (slot, tag, value) ->
            w.writeInt(slot).writeByte(tag)
            when (value) {
                is JdwpValue.Int32 -> w.writeInt(value.v)
                is JdwpValue.Int64 -> w.writeLong(value.v)
                is JdwpValue.Float32 -> w.writeInt(java.lang.Float.floatToRawIntBits(value.v))
                is JdwpValue.Float64 -> w.writeLong(java.lang.Double.doubleToRawLongBits(value.v))
                is JdwpValue.Obj -> w.writeObjectId(value.id)
                is JdwpValue.Bool -> w.writeByte(if (value.v) 1 else 0)
                is JdwpValue.Byte8 -> w.writeByte(value.v.toInt())
                is JdwpValue.Char16 -> w.writeShort(value.v.code)
                is JdwpValue.Short16 -> w.writeShort(value.v.toInt())
                JdwpValue.Void -> error("不能写 void")
            }
        }
        conn.send(CmdSet.STACK_FRAME, 2, w.toByteArray())   // 回包为空，出错会抛 JdwpException
    }

    fun thisObject(threadId: Long, frameId: Long): JdwpValue {
        val data = conn.writer().writeObjectId(threadId).writeFrameId(frameId).toByteArray()
        return conn.send(CmdSet.STACK_FRAME, 3, data).readTaggedValue()
    }
}

/** cmdSet 15：事件注册。断点与单步的临时断点都走这里。 */
class EventRequestCmds(private val conn: JdwpConnection) {

    /** 在精确的 (classID, methodID, dex_pc) 上下断点，返回 requestID。 */
    fun setBreakpoint(location: Location, suspendPolicy: Int = SuspendPolicy.EVENT_THREAD): Int {
        val w = conn.writer()
            .writeByte(EventKind.BREAKPOINT)
            .writeByte(suspendPolicy)
            .writeInt(1)
            .writeByte(ModifierKind.LOCATION_ONLY)
            .writeLocation(location)
        return conn.send(CmdSet.EVENT_REQUEST, 1, w.toByteArray()).readInt()
    }

    /** 注册 CLASS_PREPARE，classPattern 支持前后通配，如 "com.foo.*"。 */
    fun setClassPrepare(classPattern: String?, suspendPolicy: Int = SuspendPolicy.EVENT_THREAD): Int {
        val w = conn.writer()
            .writeByte(EventKind.CLASS_PREPARE)
            .writeByte(suspendPolicy)
        if (classPattern == null) {
            w.writeInt(0)
        } else {
            w.writeInt(1).writeByte(ModifierKind.CLASS_MATCH).writeString(classPattern)
        }
        return conn.send(CmdSet.EVENT_REQUEST, 1, w.toByteArray()).readInt()
    }

    /** METHOD_ENTRY 必须限定类范围，否则事件洪水会把连接打满。 */
    fun setMethodEntry(classPattern: String, suspendPolicy: Int = SuspendPolicy.EVENT_THREAD): Int {
        val w = conn.writer()
            .writeByte(EventKind.METHOD_ENTRY)
            .writeByte(suspendPolicy)
            .writeInt(1)
            .writeByte(ModifierKind.CLASS_MATCH)
            .writeString(classPattern)
        return conn.send(CmdSet.EVENT_REQUEST, 1, w.toByteArray()).readInt()
    }

    fun clear(eventKind: Int, requestId: Int) {
        runCatching {
            val w = conn.writer().writeByte(eventKind).writeInt(requestId)
            conn.send(CmdSet.EVENT_REQUEST, 2, w.toByteArray())
        }
    }

    fun clearAllBreakpoints() {
        runCatching { conn.send(CmdSet.EVENT_REQUEST, 3) }
    }
}
