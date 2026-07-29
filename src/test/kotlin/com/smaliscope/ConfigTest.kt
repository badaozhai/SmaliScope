package com.smaliscope

import com.smaliscope.config.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 只测纯逻辑，不碰真实的 ~/.smaliscope/config.properties——
 * 测试不该依赖也不该改动开发者本机的配置。
 */
class ConfigTest {

    private fun llm(url: String, key: String? = "k", model: String = "grok-4") =
        Settings.Llm(baseUrl = url, apiKey = key, model = model)

    @Test
    fun `接口地址归一化：裸域名、带 v1、完整 endpoint 都要能用`() {
        val want = "https://claudegpt.org/v1/chat/completions"
        assertEquals(want, llm("https://claudegpt.org").chatEndpoint)
        assertEquals(want, llm("https://claudegpt.org/").chatEndpoint)
        assertEquals(want, llm("https://claudegpt.org/v1").chatEndpoint)
        assertEquals(want, llm("https://claudegpt.org/v1/").chatEndpoint)
        assertEquals(want, llm("https://claudegpt.org/v1/chat/completions").chatEndpoint)
    }

    @Test
    fun `换成 xAI 官方地址同样成立`() {
        assertEquals("https://api.x.ai/v1/chat/completions", llm("https://api.x.ai").chatEndpoint)
        assertEquals("https://api.x.ai/v1/chat/completions", llm("https://api.x.ai/v1").chatEndpoint)
    }

    @Test
    fun `带路径前缀的中转地址不能被吃掉`() {
        assertEquals(
            "https://proxy.example.com/relay/v1/chat/completions",
            llm("https://proxy.example.com/relay").chatEndpoint,
        )
    }

    @Test
    fun `没有 key 就是未启用`() {
        assertFalse(llm("https://x", key = null).enabled)
        assertFalse(llm("https://x", key = "").enabled)
        assertFalse(llm("https://x", key = "   ").enabled)
        assertTrue(llm("https://x", key = "sk-abc").enabled)
    }

    @Test
    fun `key 一律脱敏，绝不整段回显`() {
        assertEquals("（未设置）", llm("https://x", key = null).maskedKey)
        assertEquals("****", llm("https://x", key = "short").maskedKey)
        val masked = llm("https://x", key = "sk-1234567890abcdef").maskedKey
        assertEquals("sk-1…cdef", masked)
        assertFalse(masked.contains("567890"), "中间部分不能出现在脱敏结果里")
    }

    @Test
    fun `已知配置项就是这三个`() {
        assertEquals(listOf("llm.baseUrl", "llm.apiKey", "llm.model"), Settings.knownKeys)
    }
}
