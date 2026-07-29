package com.smaliscope

import com.smaliscope.server.JsonParse
import com.smaliscope.server.Jv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpTest {

    @Test
    fun `JSON 解析覆盖 JSON-RPC 请求的各种形状`() {
        val v = JsonParse.parse(
            """{"jsonrpc":"2.0","id":7,"method":"tools/call",
               "params":{"name":"step","arguments":{"mode":"over","count":3,"wait":true,"x":null}}}"""
        )
        assertEquals("2.0", v["jsonrpc"]?.string)
        assertEquals(7, v["id"]?.int)
        assertEquals("tools/call", v["method"]?.string)
        val args = v["params"]?.get("arguments")!!
        assertEquals("over", args["mode"]?.string)
        assertEquals(3, args["count"]?.int)
        assertEquals(true, args["wait"]?.bool)
        assertEquals(Jv.Null, args["x"])
        assertNull(args["缺席的键"])
    }

    @Test
    fun `字符串转义与中文都能正确还原`() {
        val v = JsonParse.parse("""{"s":"引号\" 反斜杠\\ 换行\n 制表\t 中文 smali 你好"}""")
        assertEquals("引号\" 反斜杠\\ 换行\n 制表\t 中文 smali 你好", v["s"]?.string)
    }

    @Test
    fun `数组、嵌套与负数、小数`() {
        val v = JsonParse.parse("""[1,-2,3.5,{"a":[true,false]},[]]""")
        assertEquals(5, v.list.size)
        assertEquals(1, v.list[0].int)
        assertEquals(-2, v.list[1].int)
        assertEquals(3.5, (v.list[2] as Jv.Num).v)
        assertEquals(true, v.list[3]["a"]?.list?.get(0)?.bool)
        assertTrue(v.list[4].list.isEmpty())
    }

    @Test
    fun `畸形 JSON 抛异常而不是静默返回错值`() {
        assertFailsWith<Exception> { JsonParse.parse("{") }
        assertFailsWith<Exception> { JsonParse.parse("""{"a":1,}""") }
        assertFailsWith<Exception> { JsonParse.parse("""{"a" 1}""") }
        assertFailsWith<Exception> { JsonParse.parse("""{"a":1} 多余""") }
    }

    @Test
    fun `写出再读回，内容一致`() {
        val text = com.smaliscope.server.Json.obj(
            "name" to com.smaliscope.server.Json.str("含\"引号\"与\n换行"),
            "n" to com.smaliscope.server.Json.num(42),
            "flag" to com.smaliscope.server.Json.bool(true),
        )
        val back = JsonParse.parse(text)
        assertEquals("含\"引号\"与\n换行", back["name"]?.string)
        assertEquals(42, back["n"]?.int)
        assertEquals(true, back["flag"]?.bool)
    }

    // ── MCP 客户端配置写入 ──────────────────────────────────────────────────

    @Test
    fun `TOML section 不存在时追加`() {
        val out = replaceTomlSection("[other]\nkey = 1\n", "mcp_servers.smaliscope", "[mcp_servers.smaliscope]\ncommand = \"x\"")
        assertTrue(out.contains("[other]"))
        assertTrue(out.contains("[mcp_servers.smaliscope]"))
        assertTrue(out.indexOf("[other]") < out.indexOf("[mcp_servers.smaliscope]"))
    }

    @Test
    fun `TOML section 已存在时就地替换，且不动其它 section`() {
        val original = """
            [mcp_servers.other]
            command = "keep-me"

            [mcp_servers.smaliscope]
            command = "旧路径"
            enabled = false

            [ui]
            theme = "dark"
        """.trimIndent()
        val out = replaceTomlSection(
            original, "mcp_servers.smaliscope",
            "[mcp_servers.smaliscope]\ncommand = \"新路径\"\nenabled = true",
        )
        assertTrue(out.contains("keep-me"), "同级别的其它 server 不能被改掉")
        assertTrue(out.contains("theme = \"dark\""), "后面的 section 不能被吞掉")
        assertTrue(out.contains("新路径"))
        assertTrue(!out.contains("旧路径"))
        assertEquals(1, Regex("\\[mcp_servers\\.smaliscope]").findAll(out).count(), "不能写重复的 section")
    }

    @Test
    fun `TOML 空配置也能写入`() {
        val out = replaceTomlSection("", "mcp_servers.smaliscope", "[mcp_servers.smaliscope]\ncommand = \"x\"")
        assertTrue(out.trimStart().startsWith("[mcp_servers.smaliscope]"))
    }
}
