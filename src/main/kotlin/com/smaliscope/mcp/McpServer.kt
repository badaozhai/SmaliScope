package com.smaliscope.mcp

import com.smaliscope.server.Json
import com.smaliscope.server.JsonParse
import com.smaliscope.server.Jv
import com.smaliscope.session.DebugState
import com.smaliscope.session.DebugFacade
import java.io.BufferedReader
import java.io.InputStream
import java.io.OutputStream

private const val DEFAULT_PROTOCOL = "2025-06-18"

/**
 * 标准 MCP server：JSON-RPC 2.0 over stdio，一行一条消息。
 *
 * 刻意做成通用 MCP 而不是给某一家 agent 定制的适配器——MCP 是开放协议，
 * 做一次就能被 grok-build（`xai-grok-mcp`）、Claude Code、Cursor 等任何 MCP 客户端使用，
 * 绑单一厂商没有收益。
 *
 * ⚠️ stdout 是协议通道，除了 JSON-RPC 消息不能往里写任何东西；所有日志一律走 stderr。
 */
class McpServer(private val dbg: DebugFacade) {

    fun serve(input: InputStream, output: OutputStream) {
        // 日志回调只有进程内实现才有（远端模式下日志在工作台那边）。
        (dbg as? com.smaliscope.session.Debugger)?.onLog = { System.err.println("[smaliscope] $it") }
        val reader = BufferedReader(input.reader(Charsets.UTF_8))
        val writer = output.writer(Charsets.UTF_8)

        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue

            val msg = runCatching { JsonParse.parse(line) }.getOrNull()
            if (msg == null) {
                write(writer, errorResponse(Jv.Null, -32700, "JSON 解析失败"))
                continue
            }

            val id = msg["id"]
            val method = msg["method"]?.string
            if (method == null) continue  // 是响应或无效消息，忽略

            // 通知（没有 id）不需要回复。
            if (id == null) {
                if (method == "notifications/initialized") continue
                continue
            }

            val response = try {
                when (method) {
                    "initialize" -> initialize(id, msg["params"])
                    "ping" -> result(id, "{}")
                    "tools/list" -> result(id, Json.obj("tools" to Tools.schemaJson()))
                    "tools/call" -> callTool(id, msg["params"])
                    else -> errorResponse(id, -32601, "未实现的方法：$method")
                }
            } catch (t: Throwable) {
                errorResponse(id, -32603, t.message ?: t.toString())
            }
            write(writer, response)
        }
    }

    private fun write(writer: java.io.Writer, json: String) {
        // 单行 JSON + 换行，就是 MCP 的 stdio 分帧方式。
        writer.write(json)
        writer.write("\n")
        writer.flush()
    }

    private fun initialize(id: Jv, params: Jv?): String {
        // 回声客户端请求的协议版本以最大化兼容；客户端不认可会自行断开。
        val version = params?.get("protocolVersion")?.string ?: DEFAULT_PROTOCOL
        return result(
            id,
            Json.obj(
                "protocolVersion" to Json.str(version),
                "capabilities" to Json.obj("tools" to Json.obj("listChanged" to Json.bool(false))),
                "serverInfo" to Json.obj(
                    "name" to Json.str("smaliscope"),
                    "version" to Json.str("0.1.0"),
                ),
                "instructions" to Json.str(
                    "SmaliScope 是 Android 的 smali 指令级调试器。典型用法：" +
                        "list_apps 找到可调试的应用 → load_app 载入并做静态分析 → " +
                        "disassemble 看带 dex_pc 的指令 → set_breakpoint 在某条指令上下断点 → " +
                        "start_debug 挂起启动并等待命中 → step 单步并读回寄存器实际值。" +
                        "它的价值在于能验证假设，而不是靠读反编译代码猜：想知道某个寄存器运行时到底是什么，" +
                        "就在那条指令上断下来读。注意寄存器值可能因目标 APK 缺少调试信息而不可读，" +
                        "此时字段会写明原因，不要把不可读当成 0 或 null。"
                ),
            ),
        )
    }

    private fun callTool(id: Jv, params: Jv?): String {
        val name = params?.get("name")?.string ?: return errorResponse(id, -32602, "缺少 name")
        val args = params["arguments"] ?: Jv.Obj(emptyMap())
        return try {
            val text = Tools.dispatch(dbg, name, args)
            result(id, toolContent(text, isError = false))
        } catch (t: Throwable) {
            // 工具执行失败按 MCP 约定返回 isError 的正常结果，让模型能读到原因并自行纠正，
            // 而不是抛协议级错误把它挡在外面。
            result(id, toolContent("操作失败：${t.message ?: t}", isError = true))
        }
    }

    private fun toolContent(text: String, isError: Boolean): String = Json.obj(
        "content" to Json.arr(
            listOf(Json.obj("type" to Json.str("text"), "text" to Json.str(text)))
        ),
        "isError" to Json.bool(isError),
    )

    private fun result(id: Jv, resultJson: String): String =
        """{"jsonrpc":"2.0","id":${idJson(id)},"result":$resultJson}"""

    private fun errorResponse(id: Jv, code: Int, message: String): String =
        """{"jsonrpc":"2.0","id":${idJson(id)},"error":{"code":$code,"message":${Json.str(message)}}}"""

    private fun idJson(id: Jv): String = when (id) {
        is Jv.Str -> Json.str(id.v)
        is Jv.Num -> if (id.v == id.v.toLong().toDouble()) id.v.toLong().toString() else id.v.toString()
        else -> "null"
    }
}

/** 把停下来的状态渲染成给模型看的紧凑文本。 */
internal fun renderStop(dbg: DebugFacade, st: DebugState?, prefix: String): String {
    if (st == null) {
        return "$prefix：等待超时，目标仍在运行。可能是断点没被执行到，" +
            "或者应用正等待用户操作。可以用 list_breakpoints 检查断点是否已生效。"
    }
    val f = st.frames.firstOrNull() ?: return "$prefix：${st.message}（没有帧信息）"
    val sb = StringBuilder()
    sb.append("$prefix：${st.reason ?: st.message}\n")
    sb.append("位置：${f.fqcn}.${f.method}${f.signature}  dex_pc=${f.dexPc}  栈深=${st.frames.size}\n")

    dbg.methodView(f.fqcn, f.method, f.signature, f.dexPc)?.let { mv ->
        mv.instructions.firstOrNull { it.dexPc == f.dexPc }?.let { ins ->
            sb.append("当前指令：${ins.text}\n")
            ins.doc?.let { sb.append("指令含义：$it\n") }
            if (ins.reads.isNotEmpty() || ins.writes.isNotEmpty()) {
                val nm = { i: Int -> mv.registerNames.getOrElse(i) { "v$i" } }
                sb.append("数据流：读 ${ins.reads.joinToString(",", transform = nm).ifEmpty { "—" }}")
                sb.append(" → 写 ${ins.writes.joinToString(",", transform = nm).ifEmpty { "—" }}\n")
            }
        }
    }

    sb.append("寄存器：\n")
    if (f.registers.isEmpty()) {
        sb.append("  （该方法没有静态模型，多半是系统方法，读不到寄存器）\n")
    }
    for (r in f.registers) {
        val mark = if (r.changed) "  ← 本步变化" else ""
        val hint = r.hint?.let { " ($it)" } ?: ""
        sb.append("  ${r.name.padEnd(4)} ${r.type.padEnd(8)} ${r.value}$hint$mark\n")
    }
    if (st.deoptWarning) {
        sb.append("提示：目标方法已被 deoptimize 为解释执行，应用会变慢，属正常现象。\n")
    }
    return sb.toString().trimEnd()
}
