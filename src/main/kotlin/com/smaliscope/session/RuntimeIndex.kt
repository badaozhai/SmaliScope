package com.smaliscope.session

import com.smaliscope.analysis.ApkIndex
import com.smaliscope.analysis.MethodModel
import com.smaliscope.analysis.jvmSignature
import com.smaliscope.jdwp.ClassInfo
import com.smaliscope.jdwp.JdwpConnection
import com.smaliscope.jdwp.JdwpMethod
import com.smaliscope.jdwp.Location
import com.smaliscope.jdwp.RefTypeCmds
import com.smaliscope.jdwp.TypeTag
import com.smaliscope.jdwp.VirtualMachine
import java.util.concurrent.ConcurrentHashMap

/**
 * 运行时 ID 与静态模型之间的桥。
 *
 * JDWP 只给 (classID, methodID, dex_pc) 这种裸数据，静态侧只认 (fqcn, 方法名, 签名, dex_pc)。
 * 两边靠类签名和方法签名对上，结果全部缓存——每次单步都要做这个翻译，不能每次都往设备上问。
 */
class RuntimeIndex(
    private val conn: JdwpConnection,
    val apk: ApkIndex,
) {
    private val vm = VirtualMachine(conn)
    private val refTypes = RefTypeCmds(conn)

    private val signatureByClassId = ConcurrentHashMap<Long, String>()
    private val methodsByClassId = ConcurrentHashMap<Long, Map<Long, JdwpMethod>>()
    private val classIdByFqcn = ConcurrentHashMap<String, Long>()

    fun signatureOf(classId: Long): String =
        signatureByClassId.computeIfAbsent(classId) {
            runCatching { refTypes.signature(it) }.getOrDefault("L?;")
        }

    fun fqcnOf(classId: Long): String {
        val sig = signatureOf(classId)
        return if (sig.startsWith("L") && sig.endsWith(";")) {
            sig.substring(1, sig.length - 1).replace('/', '.')
        } else sig
    }

    private fun methodsOf(classId: Long): Map<Long, JdwpMethod> =
        methodsByClassId.computeIfAbsent(classId) {
            runCatching { refTypes.methods(it).associateBy { m -> m.methodId } }
                .getOrDefault(emptyMap())
        }

    fun methodOf(classId: Long, methodId: Long): JdwpMethod? = methodsOf(classId)[methodId]

    /** 类已加载则返回其 runtime classId；未加载返回 null（调用方应转 pending）。 */
    fun resolveClassId(fqcn: String): Long? {
        classIdByFqcn[fqcn]?.let { return it }
        val sig = "L${fqcn.replace('.', '/')};"
        val found: List<ClassInfo> = runCatching { vm.classesBySignature(sig) }.getOrDefault(emptyList())
        // 同名类可能被多个 ClassLoader 加载，取第一个已 prepare 的。
        val cls = found.firstOrNull { it.status and 0x2 != 0 } ?: found.firstOrNull() ?: return null
        classIdByFqcn[fqcn] = cls.typeId
        signatureByClassId[cls.typeId] = sig
        return cls.typeId
    }

    /** CLASS_PREPARE 回调里直接登记，省一次 ClassesBySignature 往返。 */
    fun registerClass(fqcn: String, classId: Long, signature: String) {
        classIdByFqcn[fqcn] = classId
        signatureByClassId[classId] = signature
    }

    fun resolveMethodId(classId: Long, name: String, signature: String): Long? =
        methodsOf(classId).values.firstOrNull { it.name == name && it.signature == signature }?.methodId

    /** 静态位置 →  JDWP Location。类未加载时返回 null。 */
    fun locationOf(fqcn: String, method: String, signature: String, dexPc: Int): Location? {
        val classId = resolveClassId(fqcn) ?: return null
        val methodId = resolveMethodId(classId, method, signature) ?: return null
        return Location(TypeTag.CLASS, classId, methodId, dexPc.toLong())
    }

    /** 运行时位置 → 静态模型。framework 方法不在 APK 里，返回 null。 */
    fun modelOf(classId: Long, methodId: Long): MethodModel? {
        val fqcn = fqcnOf(classId)
        val m = methodOf(classId, methodId) ?: return null
        return apk.model(fqcn, m.name, m.signature)
    }

    fun describeLocation(loc: Location): Triple<String, String, String> {
        val fqcn = fqcnOf(loc.classId)
        val m = methodOf(loc.classId, loc.methodId)
        return Triple(fqcn, m?.name ?: "?", m?.signature ?: "?")
    }

    /** 给定 dexlib2 的方法签名，找出运行时同名方法（供 step-into 定位被调方法）。 */
    fun signatureFor(parameterTypes: List<String>, returnType: String): String =
        jvmSignature(parameterTypes, returnType)
}
