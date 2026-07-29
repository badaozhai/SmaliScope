package com.smaliscope.explain

import com.smaliscope.config.Settings
import com.smaliscope.server.Json
import com.smaliscope.server.JsonParse
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * OpenAI 兼容的对话接口客户端（xAI 官方 `api.x.ai`、各类中转、以及任何兼容实现都能用）。
 *
 * 用 JDK 自带的 HttpClient，不引第三方 SDK——请求体就一个 messages 数组，
 * 为它拖进一整套 SDK 不划算。
 *
 * ⚠️ 隐私：调用会把 smali、反编译出的 Java 以及运行时寄存器值发到所配置的地址。
 * 用户调试的往往是**别人的** APK，所以这个功能必须是显式开启（没配 key 就不存在），
 * 且只在用户主动点击时触发，绝不进单步热路径。
 */
class LlmClient(private val cfg: Settings.Llm = Settings.llm()) {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    val enabled: Boolean get() = cfg.enabled

    class LlmException(message: String) : RuntimeException(message)

    /**
     * 发一轮对话，返回文本。
     * 失败一律抛 [LlmException] 并带上可读原因——这个功能是锦上添花，
     * 不能因为它出错就把调试流程搅乱。
     */
    fun chat(system: String, user: String, maxTokens: Int = 1200, timeoutSec: Long = 90): String {
        if (!cfg.enabled) throw LlmException("尚未配置 API key，AI 解释功能未启用")

        val body = Json.obj(
            "model" to Json.str(cfg.model),
            "messages" to Json.arr(
                listOf(
                    Json.obj("role" to Json.str("system"), "content" to Json.str(system)),
                    Json.obj("role" to Json.str("user"), "content" to Json.str(user)),
                )
            ),
            "temperature" to Json.num(0.2),
            "max_tokens" to Json.num(maxTokens),
            "stream" to Json.bool(false),
        )

        val req = HttpRequest.newBuilder(URI.create(cfg.chatEndpoint))
            .timeout(Duration.ofSeconds(timeoutSec))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
            .build()

        val resp: HttpResponse<String> = try {
            http.send(req, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw LlmException("连接 ${cfg.chatEndpoint} 失败：${e.message}")
        }

        if (resp.statusCode() !in 200..299) {
            throw LlmException("接口返回 ${resp.statusCode()}：${extractError(resp.body())}")
        }

        val parsed = runCatching { JsonParse.parse(resp.body()) }.getOrNull()
            ?: throw LlmException("接口返回的不是合法 JSON（前 200 字：${resp.body().take(200)}）")

        val content = parsed["choices"]?.list?.firstOrNull()
            ?.get("message")?.get("content")?.string
        return content?.takeIf { it.isNotBlank() }
            ?: throw LlmException("接口没有返回内容：${resp.body().take(200)}")
    }

    /** 尽量从错误响应里挖出人能看懂的一句话。 */
    private fun extractError(body: String): String {
        val j = runCatching { JsonParse.parse(body) }.getOrNull() ?: return body.take(200)
        return j["error"]?.get("message")?.string
            ?: j["error"]?.string
            ?: j["message"]?.string
            ?: body.take(200)
    }

    /** 连通性自检，供 `smaliscope config --test` 用。 */
    fun ping(): String = chat(
        system = "你是一个连通性测试端点。",
        user = "只回复两个字：正常",
        maxTokens = 16,
        timeoutSec = 30,
    ).trim()
}
