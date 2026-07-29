package com.smaliscope.jdwp

/** 内核事件对象：Event.Composite 解析后的结果。 */
sealed class JdwpEvent {
    abstract val requestId: Int

    data class Breakpoint(
        override val requestId: Int,
        val threadId: Long,
        val location: Location,
    ) : JdwpEvent()

    data class SingleStep(
        override val requestId: Int,
        val threadId: Long,
        val location: Location,
    ) : JdwpEvent()

    data class MethodEntry(
        override val requestId: Int,
        val threadId: Long,
        val location: Location,
    ) : JdwpEvent()

    data class ClassPrepare(
        override val requestId: Int,
        val threadId: Long,
        val refTypeTag: Byte,
        val typeId: Long,
        val signature: String,
        val status: Int,
    ) : JdwpEvent() {
        val fqcn: String
            get() = if (signature.startsWith("L") && signature.endsWith(";")) {
                signature.substring(1, signature.length - 1).replace('/', '.')
            } else signature
    }

    data class ThreadStart(override val requestId: Int, val threadId: Long) : JdwpEvent()
    data class ThreadDeath(override val requestId: Int, val threadId: Long) : JdwpEvent()
    data class VmStart(override val requestId: Int, val threadId: Long) : JdwpEvent()
    data class VmDeath(override val requestId: Int) : JdwpEvent()
}

data class EventSet(val suspendPolicy: Int, val events: List<JdwpEvent>)

/**
 * 解析 Event.Composite（cmdSet 64 / cmd 100）。
 *
 * 一个 Composite 里可能有多条事件，且各种类字段长度不同——遇到未识别的种类必须停止解析，
 * 因为无法安全跳过其可变长载荷，继续读会把后面的事件全部读错位。
 */
fun parseComposite(r: DataReader): EventSet {
    val suspendPolicy = r.readByte()
    val count = r.readInt()
    val events = ArrayList<JdwpEvent>(count)

    for (i in 0 until count) {
        val kind = r.readByte()
        val requestId = r.readInt()
        val ev = when (kind) {
            EventKind.BREAKPOINT ->
                JdwpEvent.Breakpoint(requestId, r.readObjectId(), r.readLocation())

            EventKind.SINGLE_STEP ->
                JdwpEvent.SingleStep(requestId, r.readObjectId(), r.readLocation())

            EventKind.METHOD_ENTRY ->
                JdwpEvent.MethodEntry(requestId, r.readObjectId(), r.readLocation())

            EventKind.CLASS_PREPARE -> JdwpEvent.ClassPrepare(
                requestId = requestId,
                threadId = r.readObjectId(),
                refTypeTag = r.readByte().toByte(),
                typeId = r.readRefTypeId(),
                signature = r.readString(),
                status = r.readInt(),
            )

            EventKind.THREAD_START -> JdwpEvent.ThreadStart(requestId, r.readObjectId())
            EventKind.THREAD_DEATH -> JdwpEvent.ThreadDeath(requestId, r.readObjectId())
            EventKind.VM_START -> JdwpEvent.VmStart(requestId, r.readObjectId())
            EventKind.VM_DEATH -> JdwpEvent.VmDeath(requestId)

            else -> {
                System.err.println("未识别的事件种类 $kind，停止解析本批剩余 ${count - i - 1} 条")
                return EventSet(suspendPolicy, events)
            }
        }
        events += ev
    }
    return EventSet(suspendPolicy, events)
}
