package com.smaliscope.jdwp

/**
 * JDWP 协议常量。命令集编号见设计方案附录 A。
 *
 * 注意 Android 11 (R) 起 ART 内置 JDWP 实现已被移除，改由 adbconnection 把 OpenJDK 的
 * libjdwp 以 JVMTI agent 形式加载进目标进程。对我们而言协议不变，且两个关键前提仍然成立：
 *   - Location.index 就是 dex_pc（JVMTI 的 jlocation 在 ART 上即 dex pc）
 *   - StackFrame 的 slot 就是 dex 寄存器号
 */
object CmdSet {
    const val VIRTUAL_MACHINE = 1
    const val REFERENCE_TYPE = 2
    const val METHOD = 6
    const val OBJECT_REFERENCE = 9
    const val STRING_REFERENCE = 10
    const val THREAD_REFERENCE = 11
    const val ARRAY_REFERENCE = 13
    const val EVENT_REQUEST = 15
    const val STACK_FRAME = 16
    const val EVENT = 64
}

object EventKind {
    const val SINGLE_STEP = 1
    const val BREAKPOINT = 2
    const val FRAME_POP = 3
    const val EXCEPTION = 4
    const val USER_DEFINED = 5
    const val THREAD_START = 6
    const val THREAD_DEATH = 7
    const val CLASS_PREPARE = 8
    const val CLASS_UNLOAD = 9
    const val CLASS_LOAD = 10
    const val FIELD_ACCESS = 20
    const val FIELD_MODIFICATION = 21
    const val EXCEPTION_CATCH = 30
    const val METHOD_ENTRY = 40
    const val METHOD_EXIT = 41
    const val VM_START = 90
    const val VM_DEATH = 99

    fun name(kind: Int): String = when (kind) {
        SINGLE_STEP -> "SINGLE_STEP"
        BREAKPOINT -> "BREAKPOINT"
        THREAD_START -> "THREAD_START"
        THREAD_DEATH -> "THREAD_DEATH"
        CLASS_PREPARE -> "CLASS_PREPARE"
        CLASS_UNLOAD -> "CLASS_UNLOAD"
        METHOD_ENTRY -> "METHOD_ENTRY"
        METHOD_EXIT -> "METHOD_EXIT"
        EXCEPTION -> "EXCEPTION"
        VM_START -> "VM_START"
        VM_DEATH -> "VM_DEATH"
        else -> "EVENT_$kind"
    }
}

object SuspendPolicy {
    const val NONE = 0
    const val EVENT_THREAD = 1
    const val ALL = 2
}

/** EventRequest.Set 的 modifier 种类。 */
object ModifierKind {
    const val COUNT = 1
    const val CONDITIONAL = 2
    const val THREAD_ONLY = 3
    const val CLASS_ONLY = 4
    const val CLASS_MATCH = 5
    const val CLASS_EXCLUDE = 6
    const val LOCATION_ONLY = 7
    const val EXCEPTION_ONLY = 8
    const val FIELD_ONLY = 9
    const val STEP = 10
    const val INSTANCE_ONLY = 11
}

/**
 * JDWP 值 tag。这些字母就是 JVM 类型签名的首字符，
 * 除了 STRING/THREAD 等几个「对象的细分种类」。
 */
object Tag {
    const val ARRAY = '['.code
    const val BYTE = 'B'.code
    const val CHAR = 'C'.code
    const val OBJECT = 'L'.code
    const val FLOAT = 'F'.code
    const val DOUBLE = 'D'.code
    const val INT = 'I'.code
    const val LONG = 'J'.code
    const val SHORT = 'S'.code
    const val VOID = 'V'.code
    const val BOOLEAN = 'Z'.code
    const val STRING = 's'.code
    const val THREAD = 't'.code
    const val THREAD_GROUP = 'g'.code
    const val CLASS_LOADER = 'l'.code
    const val CLASS_OBJECT = 'c'.code

    fun isObject(tag: Int): Boolean = when (tag) {
        ARRAY, OBJECT, STRING, THREAD, THREAD_GROUP, CLASS_LOADER, CLASS_OBJECT -> true
        else -> false
    }

    /** 由 JVM 类型签名推出 tag。 */
    fun fromSignature(sig: String): Int {
        if (sig.isEmpty()) return OBJECT
        return when (sig[0]) {
            '[' -> ARRAY
            'L' -> if (sig == "Ljava/lang/String;") STRING else OBJECT
            else -> sig[0].code
        }
    }
}

object TypeTag {
    const val CLASS: Byte = 1
    const val INTERFACE: Byte = 2
    const val ARRAY: Byte = 3
}

/** JDWP 错误码，只列我们会专门处理或需要给用户解释的。 */
object JdwpError {
    const val NONE = 0
    const val INVALID_THREAD = 10
    const val INVALID_OBJECT = 20
    const val INVALID_CLASS = 21
    const val CLASS_NOT_PREPARED = 22
    const val INVALID_METHOD = 23
    const val INVALID_LOCATION = 24
    const val INVALID_SLOT = 35
    const val THREAD_NOT_SUSPENDED = 13
    const val OPAQUE_FRAME = 32
    const val ABSENT_INFORMATION = 101
    const val INVALID_FRAMEID = 507

    fun describe(code: Int): String = when (code) {
        INVALID_THREAD -> "线程无效"
        INVALID_OBJECT -> "对象已失效"
        INVALID_CLASS -> "类无效"
        CLASS_NOT_PREPARED -> "类尚未加载（应转为 pending 断点）"
        INVALID_METHOD -> "方法无效"
        INVALID_LOCATION -> "位置无效：dex_pc 不是一条指令的起始偏移"
        INVALID_SLOT -> "寄存器槽位无效"
        THREAD_NOT_SUSPENDED -> "线程未挂起，无法读取帧"
        OPAQUE_FRAME -> "本地帧，无法读取寄存器"
        ABSENT_INFORMATION -> "缺少调试信息"
        INVALID_FRAMEID -> "帧 ID 已失效（线程已恢复执行）"
        else -> "JDWP 错误 $code"
    }
}

/** 各类 ID 的字节宽度，由 VirtualMachine.IDSizes 在握手后立刻取得。 */
data class IdSizes(
    val fieldId: Int = 8,
    val methodId: Int = 8,
    val objectId: Int = 8,
    val refTypeId: Int = 8,
    val frameId: Int = 8,
)

/** 运行时位置。index 在 Android 上即 dex_pc（单位 code unit = 2 字节）。 */
data class Location(
    val typeTag: Byte,
    val classId: Long,
    val methodId: Long,
    val index: Long,
) {
    val dexPc: Int get() = index.toInt()
}

class JdwpException(val errorCode: Int, message: String) : RuntimeException(message)
