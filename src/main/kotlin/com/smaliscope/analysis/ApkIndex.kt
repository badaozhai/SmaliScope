package com.smaliscope.analysis

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.analysis.ClassPath
import com.android.tools.smali.dexlib2.analysis.DexClassProvider
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.DexFile
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 一个 APK（含 split）的静态索引：类表、方法表、以及按需构建并缓存的 [MethodModel]。
 *
 * classPath 只喂了 APK 自身的 dex，没有 Android framework。对类型推导而言这足够：
 * 我们只需要区分 int/long/float/double/引用 这几档来选 JDWP tag，
 * 而需要 framework 的只有「两个不同引用类型在控制流汇合处求公共父类」这一种情况，
 * 求不出时 dexlib2 会退化成 Object，仍然是引用，tag 不变。
 */
class ApkIndex(val apkFiles: List<File>, val api: Int = 34) {

    constructor(apk: File, api: Int = 34) : this(listOf(apk), api)

    private val opcodes: Opcodes = Opcodes.forApi(api)

    private val dexFiles: List<DexFile> = apkFiles.flatMap { apk ->
        val container = DexFileFactory.loadDexContainer(apk, opcodes)
        container.dexEntryNames.mapNotNull { name ->
            runCatching { container.getEntry(name)?.dexFile }.getOrNull()
        }
    }

    val classPath: ClassPath? = runCatching {
        ClassPath(dexFiles.map { DexClassProvider(it) })
    }.getOrNull()

    /** fqcn → ClassDef。 */
    val classDefs: Map<String, ClassDef> = LinkedHashMap<String, ClassDef>().apply {
        dexFiles.forEach { dex ->
            dex.classes.forEach { cd -> putIfAbsent(typeToFqcn(cd.type), cd) }
        }
    }

    private val modelCache = ConcurrentHashMap<MethodRef, Optional>()

    private class Optional(val model: MethodModel?)

    val classCount: Int get() = classDefs.size

    fun classNames(): List<String> = classDefs.keys.sorted()

    /** 过滤掉 framework/kotlin 等噪音，只留应用自身的类，供新手浏览。 */
    fun appClassNames(packagePrefix: String? = null): List<String> {
        val noise = listOf("android.", "androidx.", "kotlin.", "kotlinx.", "java.", "javax.", "com.google.")
        return classDefs.keys
            .filter { fqcn ->
                if (packagePrefix != null) fqcn.startsWith(packagePrefix)
                else noise.none { fqcn.startsWith(it) }
            }
            .sorted()
    }

    fun methodsOf(fqcn: String): List<MethodRef> {
        val cd = classDefs[fqcn] ?: return emptyList()
        return cd.methods.map {
            MethodRef(fqcn, it.name, jvmSignature(it.parameterTypes.map { p -> p.toString() }, it.returnType))
        }
    }

    /** 有方法体（可下断点）的方法。抽象/native 方法没有 dex 代码。 */
    fun concreteMethodsOf(fqcn: String): List<MethodRef> {
        val cd = classDefs[fqcn] ?: return emptyList()
        return cd.methods.filter { it.implementation != null }.map {
            MethodRef(fqcn, it.name, jvmSignature(it.parameterTypes.map { p -> p.toString() }, it.returnType))
        }
    }

    fun model(ref: MethodRef): MethodModel? = modelCache.computeIfAbsent(ref) { key ->
        val cd = classDefs[key.fqcn]
        val m = cd?.methods?.firstOrNull {
            it.name == key.name &&
                jvmSignature(it.parameterTypes.map { p -> p.toString() }, it.returnType) == key.signature
        }
        Optional(m?.let { buildMethodModel(it, classPath) })
    }.model

    fun model(fqcn: String, name: String, signature: String): MethodModel? =
        model(MethodRef(fqcn, name, signature))

    /** 按名字找方法（不指定签名时取第一个有实现的重载）。 */
    fun findMethod(fqcn: String, name: String): MethodModel? {
        val cd = classDefs[fqcn] ?: return null
        val m = cd.methods.firstOrNull { it.name == name && it.implementation != null } ?: return null
        return model(fqcn, name, jvmSignature(m.parameterTypes.map { it.toString() }, m.returnType))
    }
}
