package com.smaliscope.decompile

import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * jadx 的 Java 视图。**按需**反编译当前打开的类——全量反编译在混淆过的大 App 上要几十秒，
 * 而用户一次只看一个类。
 *
 * Java 视图只用来「看懂逻辑」，断点仍然下在 smali 侧：
 * Java 行 ↔ dex_pc 的映射对本项目是过度工程，而且混淆后经常对不准。
 */
class JadxService(private val apkFiles: List<File>) : AutoCloseable {

    private val cache = ConcurrentHashMap<String, String>()

    @Volatile
    private var decompiler: JadxDecompiler? = null

    @Volatile
    private var loadError: String? = null

    /** jadx 加载整个 APK 比较慢，第一次用到时才初始化，且只初始化一次。 */
    private fun ensureLoaded(): JadxDecompiler? {
        decompiler?.let { return it }
        loadError?.let { return null }
        synchronized(this) {
            decompiler?.let { return it }
            if (loadError != null) return null
            return try {
                val args = JadxArgs().apply {
                    setInputFiles(apkFiles)
                    isShowInconsistentCode = true
                    isUseImports = true
                    isSkipResources = true
                    threadsCount = Runtime.getRuntime().availableProcessors().coerceAtMost(4)
                }
                JadxDecompiler(args).also {
                    it.load()
                    decompiler = it
                }
            } catch (t: Throwable) {
                loadError = t.message ?: t.toString()
                null
            }
        }
    }

    /** 返回该类的 Java 源码；反编译不了时返回 null（UI 退回纯 smali）。 */
    fun javaOf(fqcn: String): String? {
        cache[fqcn]?.let { return it }
        val jadx = ensureLoaded() ?: return null
        return try {
            val cls = jadx.classesWithInners.firstOrNull { it.fullName == fqcn }
                ?: jadx.classes.firstOrNull { it.fullName == fqcn }
                ?: return null
            cls.code.also { cache[fqcn] = it }
        } catch (t: Throwable) {
            null
        }
    }

    /** jadx 实际看到了多少个类。为 0 通常意味着输入插件没生效。 */
    fun classCount(): Int = runCatching { ensureLoaded()?.classes?.size ?: 0 }.getOrDefault(0)

    val error: String? get() = loadError

    override fun close() {
        runCatching { decompiler?.close() }
        decompiler = null
        cache.clear()
    }
}
