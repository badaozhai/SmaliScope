package com.smaliscope.grok

import com.smaliscope.server.JsonParse
import java.io.BufferedReader
import java.io.File

/**
 * 把本机的 grok-build CLI 封装成工作台里的对话窗口。
 *
 * 每一轮把 grok 作为子进程跑起来，用 `--output-format streaming-json` 拿到逐 token 的
 * 事件流，转发给前端聊天面板。刻意用**真正的 grok-build CLI**而不是自己直连模型接口——
 * grok-build 自带完整的 agent 能力（规划、工具循环、审批、会话记忆），我们只是把它的对话
 * 搬进 SmaliScope 的界面。
 *
 * grok 里已经注册了 smaliscope 的 MCP（`smaliscope mcp-install` 写进 ~/.grok/config.toml），
 * 所以在这个聊天窗口里说「下断点看看 p1 是多少」，grok 会真的驱动调试器。
 *
 * 几个刻意的选择：
 * - **跑在一个隔离的空目录里**：grok 是编码 agent，带改文件/执行命令的工具；给它专用 cwd，
 *   免得它的文件操作碰到本仓库或用户其它项目。
 * - **多轮续接靠 grok 自己的 sessionId**：首轮不带 session，从 `end` 事件里拿到 sessionId，
 *   后续轮用 `-r <id>` 续上。
 * - **--always-approve**：聊天窗口里没法逐个点审批，MCP 工具必须能自动执行。界面上会写明这点。
 */
class GrokBridge {

    @Volatile private var sessionId: String? = null
    @Volatile private var current: Process? = null

    private val workdir: File =
        File(System.getProperty("user.home"), ".smaliscope/grok-cwd").apply { mkdirs() }

    /** grok 可执行文件：先 ~/.grok/bin/grok，再 PATH。 */
    private fun locate(): File? {
        File(System.getProperty("user.home"), ".grok/bin/grok").let { if (it.canExecute()) return it }
        System.getenv("PATH")?.split(File.pathSeparator)?.forEach { dir ->
            File(dir, "grok").let { if (it.canExecute()) return it }
        }
        return null
    }

    data class Status(val available: Boolean, val message: String, val hasSession: Boolean)

    fun status(): Status {
        val bin = locate()
        return if (bin == null)
            Status(false, "未检测到 grok-build。装好它并执行 `smaliscope mcp-install` 后即可在此对话。", false)
        else Status(true, "grok-build 已就绪（$bin）", sessionId != null)
    }

    /** 新开一轮对话（丢弃已有会话）。 */
    fun reset() { sessionId = null }

    /** 打断正在进行的一轮。 */
    fun stop() { current?.let { runCatching { it.destroy() } } }

    sealed class Event {
        data class Thought(val text: String) : Event()
        data class Text(val text: String) : Event()
        data class Done(val stopReason: String?, val tokens: Int?) : Event()
        data class Failed(val message: String) : Event()
    }

    /** 跑一轮对话。阻塞直到结束，中途通过 [onEvent] 推事件。由上层放到工作线程里调用。 */
    fun send(message: String, onEvent: (Event) -> Unit) {
        val bin = locate() ?: run { onEvent(Event.Failed("未检测到 grok-build。")); return }

        val cmd = mutableListOf(
            bin.absolutePath, "-p", message,
            "--output-format", "streaming-json", "--always-approve",
        )
        sessionId?.let { cmd += listOf("-r", it) }   // 有会话就续接

        val proc = ProcessBuilder(cmd).directory(workdir).redirectErrorStream(false).start()
        current = proc

        val err = StringBuilder()
        val errThread = Thread { proc.errorStream.bufferedReader().forEachLine { err.appendLine(it) } }
            .apply { isDaemon = true; start() }

        var sawAnything = false
        try {
            proc.inputStream.bufferedReader().use { r ->
                var line = r.readLine()
                while (line != null) {
                    val l = line.trim()
                    if (l.isNotEmpty()) {
                        runCatching { JsonParse.parse(l) }.getOrNull()?.let { ev ->
                            when (ev["type"]?.string) {
                                "thought" -> ev["data"]?.string?.let { sawAnything = true; onEvent(Event.Thought(it)) }
                                "text" -> ev["data"]?.string?.let { sawAnything = true; onEvent(Event.Text(it)) }
                                "end" -> {
                                    ev["sessionId"]?.string?.let { sessionId = it }
                                    onEvent(Event.Done(ev["stopReason"]?.string, ev["usage"]?.get("output_tokens")?.int))
                                }
                                else -> Unit   // 忽略未知事件，别让新版本字段把面板搞崩
                            }
                        }
                    }
                    line = r.readLine()
                }
            }
            proc.waitFor()
            errThread.join(500)
            if (!sawAnything && proc.exitValue() != 0) {
                onEvent(Event.Failed(err.lines().firstOrNull { it.isNotBlank() } ?: "grok 退出码 ${proc.exitValue()}"))
            }
        } catch (t: Throwable) {
            onEvent(Event.Failed(t.message ?: "grok 执行失败"))
        } finally {
            current = null
        }
    }
}
