package com.smaliscope.config

import java.io.File
import java.util.Properties

/**
 * 本地配置：`~/.smaliscope/config.properties`。
 *
 * 环境变量优先于文件，方便临时覆盖与 CI：
 *   SMALISCOPE_LLM_BASE_URL / SMALISCOPE_LLM_API_KEY / SMALISCOPE_LLM_MODEL
 */
object Settings {

    private const val DEFAULT_BASE_URL = "https://claudegpt.org"
    private const val DEFAULT_MODEL = "grok-4"

    val dir: File = File(System.getProperty("user.home"), ".smaliscope")
    val file: File = File(dir, "config.properties")

    data class Llm(
        val baseUrl: String,
        val apiKey: String?,
        val model: String,
    ) {
        /** 没配 key 就整个功能不存在：界面上不出现、MCP 里不注册。 */
        val enabled: Boolean get() = !apiKey.isNullOrBlank()

        /**
         * OpenAI 兼容接口的完整地址。
         * 用户可能填 `https://host`、`https://host/v1` 或直接给完整 endpoint，都要能用。
         */
        val chatEndpoint: String
            get() {
                val b = baseUrl.trimEnd('/')
                return when {
                    b.endsWith("/chat/completions") -> b
                    b.endsWith("/v1") -> "$b/chat/completions"
                    else -> "$b/v1/chat/completions"
                }
            }

        /** 给用户看的脱敏信息，绝不回显完整 key。 */
        val maskedKey: String
            get() = apiKey?.let {
                if (it.length <= 8) "****" else it.take(4) + "…" + it.takeLast(4)
            } ?: "（未设置）"
    }

    private fun read(): Properties {
        val p = Properties()
        if (file.isFile) file.inputStream().use { p.load(it) }
        return p
    }

    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    fun llm(): Llm {
        val p = read()
        return Llm(
            baseUrl = env("SMALISCOPE_LLM_BASE_URL")
                ?: p.getProperty("llm.baseUrl")?.takeIf { it.isNotBlank() }
                ?: DEFAULT_BASE_URL,
            apiKey = env("SMALISCOPE_LLM_API_KEY")
                ?: p.getProperty("llm.apiKey")?.takeIf { it.isNotBlank() },
            model = env("SMALISCOPE_LLM_MODEL")
                ?: p.getProperty("llm.model")?.takeIf { it.isNotBlank() }
                ?: DEFAULT_MODEL,
        )
    }

    /** 写入一个配置项。值为空则删除该项。 */
    fun set(key: String, value: String?) {
        val p = read()
        if (value.isNullOrBlank()) p.remove(key) else p.setProperty(key, value)
        dir.mkdirs()
        file.outputStream().use { p.store(it, "SmaliScope 配置") }
        // 文件里存着 API key，别让同机其他用户读到。
        runCatching { file.setReadable(false, false); file.setReadable(true, true) }
        runCatching { file.setWritable(false, false); file.setWritable(true, true) }
    }

    fun all(): Map<String, String> = read().entries.associate {
        it.key.toString() to it.value.toString()
    }

    val knownKeys = listOf("llm.baseUrl", "llm.apiKey", "llm.model")
}
