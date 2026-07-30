package com.smaliscope.term

import java.io.File
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 内嵌终端的后端：起一个真正的伪终端（PTY）跑用户的 shell，
 * 前端用 xterm.js 显示。于是可以在工作台里直接跑 grok / codex / adb / smaliscope 等任何命令行。
 *
 * PTY 用 python3 起——JVM 没有内建伪终端，而 python 的 `pty` 模块能 forkpty，
 * 于是不必引入任何 Java 原生库（pty4j 之类）就能拿到真 tty。python3 在 mac/linux 上必有，
 * 项目本来也用它跑 e2e 脚本，是既有假设。
 *
 * 传输沿用项目既有的 SSE（输出）+ HTTP POST（输入）那套，不引 WebSocket：
 * 本地 127.0.0.1 上按键 POST 是亚毫秒级，跑 agent CLI（打个 prompt 看输出）完全够用。
 */
class TerminalBridge(
    /** 终端的工作目录——放当前调试会话的 CONTEXT.md 等，让 grok/codex 一进来就有上下文。 */
    private val cwd: File,
    /** 额外注入 shell 的环境变量（如把 smaliscope 放进 PATH）。 */
    private val extraEnv: Map<String, String> = emptyMap(),
) {
    private var proc: Process? = null
    private var stdin: OutputStream? = null
    private val alive = AtomicBoolean(false)

    val isAlive: Boolean get() = alive.get() && proc?.isAlive == true

    /**
     * 起 PTY。[onOutput] 收到 shell 的原始字节（含 ANSI）；[onExit] 在 shell 退出时回调。
     * 已经起着就先关掉再起（一个 bridge 一个会话）。
     *
     * @param bootCommand 终端就绪后自动执行的命令（如 `grok`），null 表示只给个 shell。
     */
    @Synchronized
    fun start(
        cols: Int, rows: Int,
        onOutput: (ByteArray) -> Unit,
        onExit: () -> Unit,
        bootCommand: String? = null,
        /** 每次打开时算出来的环境变量（Key/地址可能刚在设置里改过，不能构造时定死）。 */
        env: Map<String, String> = emptyMap(),
    ) {
        close()
        cwd.mkdirs()

        val py = File.createTempFile("smaliscope-pty", ".py").apply {
            writeText(PTY_HELPER); deleteOnExit()
        }
        val pb = ProcessBuilder("python3", py.absolutePath, cols.toString(), rows.toString())
            .directory(cwd)
            .redirectErrorStream(true)
        pb.environment().apply {
            put("TERM", "xterm-256color")
            put("LANG", System.getenv("LANG") ?: "en_US.UTF-8")
            extraEnv.forEach { (k, v) -> put(k, v) }
            env.forEach { (k, v) -> put(k, v) }
        }
        val p = try {
            pb.start()
        } catch (t: Throwable) {
            System.err.println("[term] 启动 python3 失败：${t.message}")
            onOutput("\r\n无法启动终端：找不到或无法运行 python3（$t）\r\n".toByteArray())
            alive.set(false); runCatching { onExit() }; return
        }
        proc = p
        stdin = p.outputStream
        alive.set(true)

        Thread({
            val buf = ByteArray(8192)
            try {
                val ins = p.inputStream
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    if (n > 0) onOutput(buf.copyOf(n))
                }
            } catch (t: Throwable) {
                System.err.println("[term] 读线程异常：${t.message}")
            } finally {
                alive.set(false)
                runCatching { onExit() }
            }
        }, "smaliscope-term-out").apply { isDaemon = true; start() }

        // 自动执行开场命令（默认进 grok）。等一下让 shell 把 rc 文件读完、提示符画出来，
        // 否则命令会被 zsh 的初始化吃掉。
        if (!bootCommand.isNullOrBlank()) {
            Thread({
                Thread.sleep(700)
                write("$bootCommand\n".toByteArray())
            }, "smaliscope-term-boot").apply { isDaemon = true; start() }
        }
    }

    /** 把前端的按键写进 PTY。 */
    fun write(data: ByteArray) {
        runCatching { stdin?.apply { write(data); flush() } }
    }

    /** 改终端尺寸。走一段 APC 控制序列，助手识别后 ioctl 设窗口大小，且不转发给 shell。 */
    fun resize(cols: Int, rows: Int) {
        // ESC _ ss-resize;C;R ESC \  —— APC 串，正常按键里不会出现，用它当带内控制通道；
        // python 助手识别后 ioctl 改窗口大小，并把这段从输入里剥掉，不喂给 shell。
        val esc = "\u001b"
        write("${esc}_ss-resize;$cols;$rows$esc\\".toByteArray(Charsets.US_ASCII))
    }

    @Synchronized
    fun close() {
        runCatching { stdin?.close() }
        proc?.let { runCatching { it.destroy() } }
        proc = null; stdin = null; alive.set(false)
    }

    companion object {
        /**
         * python PTY 桥：forkpty 起 shell，把 master 端与自己的 stdin/stdout 对接。
         * stdin 关掉后不立刻退出，继续把 shell 剩余输出抽干到它自己退出——
         * 否则前端一断开就会截断输出。
         * 识别 APC 控制序列 `\x1b_ss-resize;C;R\x1b\` 来实时改窗口大小。
         */
        private val PTY_HELPER = """
            import os, pty, sys, select, fcntl, termios, struct, re
            cols, rows = int(sys.argv[1]), int(sys.argv[2])
            shell = os.environ.get("SHELL", "/bin/bash")
            pid, fd = pty.fork()
            if pid == 0:
                os.execvp(shell, [shell, "-i"])
            else:
                def setsize(c, r):
                    fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", r, c, 0, 0))
                setsize(cols, rows)
                RS = re.compile(rb"\x1b_ss-resize;(\d+);(\d+)\x1b\\")
                stdin_open = True
                while True:
                    watch = [fd] + ([0] if stdin_open else [])
                    try:
                        r, _, _ = select.select(watch, [], [])
                    except Exception:
                        break
                    if fd in r:
                        try:
                            d = os.read(fd, 8192)
                        except OSError:
                            break
                        if not d:
                            break
                        os.write(1, d)
                    if stdin_open and 0 in r:
                        d = os.read(0, 8192)
                        if not d:
                            stdin_open = False
                            continue
                        m = RS.search(d)
                        if m:
                            setsize(int(m.group(1)), int(m.group(2)))
                            d = RS.sub(b"", d)
                        if d:
                            os.write(fd, d)
        """.trimIndent()
    }
}
