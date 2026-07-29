package com.smaliscope.jdwp

data class VmVersion(
    val description: String,
    val jdwpMajor: Int,
    val jdwpMinor: Int,
    val vmVersion: String,
    val vmName: String,
)

data class ClassInfo(
    val refTypeTag: Byte,
    val typeId: Long,
    val signature: String,
    val status: Int,
) {
    /** Lcom/foo/Bar; → com.foo.Bar */
    val fqcn: String
        get() = if (signature.startsWith("L") && signature.endsWith(";")) {
            signature.substring(1, signature.length - 1).replace('/', '.')
        } else signature
}

/** cmdSet 1：连接、类枚举、全局挂起恢复。 */
class VirtualMachine(private val conn: JdwpConnection) {

    fun version(): VmVersion {
        val r = conn.send(CmdSet.VIRTUAL_MACHINE, 1)
        return VmVersion(
            description = r.readString(),
            jdwpMajor = r.readInt(),
            jdwpMinor = r.readInt(),
            vmVersion = r.readString(),
            vmName = r.readString(),
        )
    }

    /** 按签名找已加载的类，如 "Lcom/smaliscope/testapp/Calc;"。未加载时返回空表。 */
    fun classesBySignature(signature: String): List<ClassInfo> {
        val data = conn.writer().writeString(signature).toByteArray()
        val r = conn.send(CmdSet.VIRTUAL_MACHINE, 2, data)
        val n = r.readInt()
        return (0 until n).map {
            ClassInfo(
                refTypeTag = r.readByte().toByte(),
                typeId = r.readRefTypeId(),
                signature = signature,
                status = r.readInt(),
            )
        }
    }

    fun allClasses(): List<ClassInfo> {
        val r = conn.send(CmdSet.VIRTUAL_MACHINE, 3, timeoutMs = 60_000)
        val n = r.readInt()
        return (0 until n).map {
            val tag = r.readByte().toByte()
            val id = r.readRefTypeId()
            val sig = r.readString()
            val status = r.readInt()
            ClassInfo(tag, id, sig, status)
        }
    }

    fun allThreads(): List<Long> {
        val r = conn.send(CmdSet.VIRTUAL_MACHINE, 4)
        val n = r.readInt()
        return (0 until n).map { r.readObjectId() }
    }

    fun idSizes(): IdSizes {
        val r = conn.send(CmdSet.VIRTUAL_MACHINE, 7)
        return IdSizes(
            fieldId = r.readInt(),
            methodId = r.readInt(),
            objectId = r.readInt(),
            refTypeId = r.readInt(),
            frameId = r.readInt(),
        )
    }

    fun suspend() { conn.send(CmdSet.VIRTUAL_MACHINE, 8) }

    fun resume() { conn.send(CmdSet.VIRTUAL_MACHINE, 9) }

    fun dispose() { runCatching { conn.send(CmdSet.VIRTUAL_MACHINE, 6, timeoutMs = 3_000) } }
}
