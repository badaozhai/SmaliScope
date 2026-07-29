package com.smaliscope.jdwp

import java.io.ByteArrayOutputStream

/**
 * JDWP packet 的 data 段读写辅助。所有多字节整数为大端序。
 * 变长 ID（objectID/methodID/…）的宽度来自 IDSizes，故读写都要带上 [IdSizes]。
 */
class DataReader(private val buf: ByteArray, private val ids: IdSizes = IdSizes()) {
    var pos: Int = 0
        private set

    val remaining: Int get() = buf.size - pos

    fun readByte(): Int {
        require(pos < buf.size) { "读越界: pos=$pos size=${buf.size}" }
        return buf[pos++].toInt()
    }

    fun readBoolean(): Boolean = readByte() != 0

    fun readShort(): Int = (readUByte() shl 8) or readUByte()

    private fun readUByte(): Int {
        require(pos < buf.size) { "读越界: pos=$pos size=${buf.size}" }
        return buf[pos++].toInt() and 0xff
    }

    fun readInt(): Int {
        var v = 0
        repeat(4) { v = (v shl 8) or readUByte() }
        return v
    }

    fun readLong(): Long {
        var v = 0L
        repeat(8) { v = (v shl 8) or readUByte().toLong() }
        return v
    }

    /** 按给定字节宽度读一个 ID。 */
    fun readId(width: Int): Long {
        var v = 0L
        repeat(width) { v = (v shl 8) or readUByte().toLong() }
        return v
    }

    fun readObjectId(): Long = readId(ids.objectId)
    fun readRefTypeId(): Long = readId(ids.refTypeId)
    fun readMethodId(): Long = readId(ids.methodId)
    fun readFieldId(): Long = readId(ids.fieldId)
    fun readFrameId(): Long = readId(ids.frameId)

    /** JDWP 字符串：int 长度（UTF-8 字节数）+ 字节，无结尾 NUL。 */
    fun readString(): String {
        val len = readInt()
        require(len >= 0 && pos + len <= buf.size) { "字符串长度异常: $len" }
        val s = String(buf, pos, len, Charsets.UTF_8)
        pos += len
        return s
    }

    fun readLocation(): Location = Location(
        typeTag = readByte().toByte(),
        classId = readRefTypeId(),
        methodId = readMethodId(),
        index = readLong(),
    )

    /** 带 tag 的值。 */
    fun readTaggedValue(): JdwpValue {
        val tag = readByte()
        return readUntaggedValue(tag)
    }

    /** 不带 tag 的值，tag 由调用方给出。 */
    fun readUntaggedValue(tag: Int): JdwpValue = when (tag) {
        Tag.VOID -> JdwpValue.Void
        Tag.BOOLEAN -> JdwpValue.Bool(readByte() != 0)
        Tag.BYTE -> JdwpValue.Byte8(readByte().toByte())
        Tag.CHAR -> JdwpValue.Char16(readShort().toChar())
        Tag.SHORT -> JdwpValue.Short16(readShort().toShort())
        Tag.INT -> JdwpValue.Int32(readInt())
        Tag.LONG -> JdwpValue.Int64(readLong())
        Tag.FLOAT -> JdwpValue.Float32(Float.fromBits(readInt()))
        Tag.DOUBLE -> JdwpValue.Float64(Double.fromBits(readLong()))
        else -> JdwpValue.Obj(tag, readObjectId())
    }

    fun readRemaining(): ByteArray = buf.copyOfRange(pos, buf.size).also { pos = buf.size }
}

class DataWriter(private val ids: IdSizes = IdSizes()) {
    private val out = ByteArrayOutputStream()

    fun writeByte(v: Int) = apply { out.write(v and 0xff) }

    fun writeShort(v: Int) = apply {
        out.write((v ushr 8) and 0xff); out.write(v and 0xff)
    }

    fun writeInt(v: Int) = apply {
        for (s in intArrayOf(24, 16, 8, 0)) out.write((v ushr s) and 0xff)
    }

    fun writeLong(v: Long) = apply {
        for (s in intArrayOf(56, 48, 40, 32, 24, 16, 8, 0)) out.write(((v ushr s) and 0xff).toInt())
    }

    fun writeId(v: Long, width: Int) = apply {
        for (i in width - 1 downTo 0) out.write(((v ushr (i * 8)) and 0xff).toInt())
    }

    fun writeObjectId(v: Long) = writeId(v, ids.objectId)
    fun writeRefTypeId(v: Long) = writeId(v, ids.refTypeId)
    fun writeMethodId(v: Long) = writeId(v, ids.methodId)
    fun writeFieldId(v: Long) = writeId(v, ids.fieldId)
    fun writeFrameId(v: Long) = writeId(v, ids.frameId)

    fun writeString(s: String) = apply {
        val b = s.toByteArray(Charsets.UTF_8)
        writeInt(b.size)
        out.write(b)
    }

    fun writeLocation(loc: Location) = apply {
        writeByte(loc.typeTag.toInt())
        writeRefTypeId(loc.classId)
        writeMethodId(loc.methodId)
        writeLong(loc.index)
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

/** JDWP 值的类型化表示。 */
sealed class JdwpValue {
    abstract val tag: Int

    object Void : JdwpValue() {
        override val tag = Tag.VOID
        override fun toString() = "void"
    }

    data class Bool(val v: Boolean) : JdwpValue() {
        override val tag = Tag.BOOLEAN
        override fun toString() = v.toString()
    }

    data class Byte8(val v: Byte) : JdwpValue() {
        override val tag = Tag.BYTE
        override fun toString() = v.toString()
    }

    data class Char16(val v: Char) : JdwpValue() {
        override val tag = Tag.CHAR
        override fun toString() = "'$v'"
    }

    data class Short16(val v: Short) : JdwpValue() {
        override val tag = Tag.SHORT
        override fun toString() = v.toString()
    }

    data class Int32(val v: Int) : JdwpValue() {
        override val tag = Tag.INT
        override fun toString() = v.toString()
    }

    data class Int64(val v: Long) : JdwpValue() {
        override val tag = Tag.LONG
        override fun toString() = v.toString()
    }

    data class Float32(val v: Float) : JdwpValue() {
        override val tag = Tag.FLOAT
        override fun toString() = v.toString()
    }

    data class Float64(val v: Double) : JdwpValue() {
        override val tag = Tag.DOUBLE
        override fun toString() = v.toString()
    }

    /** 对象/数组/字符串等引用型。id == 0 表示 null。 */
    data class Obj(override val tag: Int, val id: Long) : JdwpValue() {
        val isNull: Boolean get() = id == 0L
        override fun toString() = if (isNull) "null" else "${tag.toChar()}@$id"
    }
}
