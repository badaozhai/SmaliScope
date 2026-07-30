package com.smaliscope.server

import com.smaliscope.session.Debugger
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * 本地 Web 工作台：HTTP + SSE 事件推流，全部跑在 127.0.0.1。
 * 真正的调试逻辑在 [Debugger] 里，这里只负责把它翻成 HTTP 与 JSON。
 *
 * 设计方案写的是 WebSocket；这里用 Server-Sent Events：状态推送本来就是单向的
 * （内核 → 前端），命令走普通 HTTP 请求。SSE 在 JDK 自带的 HttpServer 上二十行就能实现，
 * 而手写 RFC 6455 的分帧、掩码、心跳和关闭握手要多几百行，对本项目没有任何额外收益。
 */
fun startWorkbench(port: Int) {
    val wb = Workbench(Debugger(com.smaliscope.cacheDir))
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    server.executor = Executors.newCachedThreadPool { r ->
        Thread(r, "smaliscope-http").apply { isDaemon = true }
    }
    wb.install(server)
    server.start()

    val url = "http://127.0.0.1:$port"
    println("SmaliScope 工作台已启动：$url")
    println("（按 Ctrl+C 退出）")
    runCatching {
        if (java.awt.Desktop.isDesktopSupported()) {
            java.awt.Desktop.getDesktop().browse(java.net.URI(url))
        }
    }

    Runtime.getRuntime().addShutdownHook(Thread { runCatching { wb.close() } })
    Thread.currentThread().join()
}

class Workbench(private val dbg: Debugger) : AutoCloseable {

    private val sse = SseHub()
    // 内嵌终端：跑在一个每次会话都重建的目录里，里面放 CONTEXT.md（当前调试上下文），
    // 并把 smaliscope 自己放进 PATH，方便在终端里直接用。
    /** 终端连接代次：刷新页面会开新连接，用它避免旧连接的收尾把新终端关掉。 */
    private val termGen = java.util.concurrent.atomic.AtomicLong(0)
    private val termCwd = java.io.File(System.getProperty("user.home"), ".smaliscope/term-cwd")
    private val term = com.smaliscope.term.TerminalBridge(
        cwd = termCwd,
        extraEnv = buildMap {
            com.smaliscope.launcherDir()?.let { put("PATH", "$it${java.io.File.pathSeparator}${System.getenv("PATH") ?: ""}") }
        },
    )

    init {
        dbg.onState = { st ->
            sse.send("state", Json.of(st))
            sse.send("breakpoints", Json.arr(dbg.breakpoints().map { Json.of(it) }))
        }
        dbg.onLog = { sse.send("log", Json.str(it)) }
    }

    fun install(server: HttpServer) {
        server.createContext("/") { ex -> handle(ex) { serveStatic(ex) } }
        server.createContext("/api/events") { ex -> sse.attach(ex) }
        server.createContext("/api/bootstrap") { ex -> handle(ex) { json(ex, bootstrap(query(ex)["serial"])) } }
        server.createContext("/api/session") { ex -> handle(ex) { json(ex, openSession(query(ex))) } }
        server.createContext("/api/classes") { ex -> handle(ex) { json(ex, classes(query(ex))) } }
        server.createContext("/api/methods") { ex -> handle(ex) { json(ex, methods(query(ex))) } }
        server.createContext("/api/method") { ex -> handle(ex) { json(ex, methodView(query(ex))) } }
        server.createContext("/api/bp") { ex -> handle(ex) { json(ex, addBp(query(ex))) } }
        server.createContext("/api/bp/remove") { ex -> handle(ex) { json(ex, removeBp(query(ex))) } }
        server.createContext("/api/bp/cond") { ex -> handle(ex) { json(ex, setCond(query(ex))) } }
        server.createContext("/api/breakpoints") { ex -> handle(ex) { json(ex, breakpoints()) } }
        server.createContext("/api/templates") { ex -> handle(ex) { json(ex, templates()) } }
        server.createContext("/api/template") { ex -> handle(ex) { json(ex, applyTemplate(query(ex))) } }
        server.createContext("/api/start") { ex -> handle(ex) { dbg.startAsync(); json(ex, ok()) } }
        server.createContext("/api/control") { ex ->
            handle(ex) { dbg.control(query(ex)["action"] ?: error("缺少 action")); json(ex, ok()) }
        }
        server.createContext("/api/object") { ex -> handle(ex) { json(ex, expand(query(ex))) } }
        server.createContext("/api/frame") { ex -> handle(ex) { json(ex, frame(query(ex))) } }
        server.createContext("/api/setreg") { ex -> handle(ex) { json(ex, setReg(query(ex))) } }
        server.createContext("/api/java") { ex -> handle(ex) { json(ex, javaSource(query(ex))) } }
        server.createContext("/api/timeline") { ex ->
            handle(ex) { json(ex, Json.arr(dbg.timeline().map { Json.of(it) })) }
        }
        server.createContext("/api/state") { ex -> handle(ex) { json(ex, Json.of(dbg.state)) } }
        server.createContext("/api/explain") { ex -> handle(ex) { json(ex, explain(query(ex))) } }
        server.createContext("/api/config") { ex -> handle(ex) { json(ex, config(ex, query(ex))) } }
        server.createContext("/api/config/test") { ex -> handle(ex) { json(ex, testLlm()) } }
        server.createContext("/api/term/open") { ex -> termOpen(ex) }
        server.createContext("/api/term/input") { ex -> handle(ex) { term.write(readBody(ex)); json(ex, ok()) } }
        server.createContext("/api/term/resize") { ex ->
            handle(ex) {
                val q = query(ex)
                term.resize(q["cols"]?.toIntOrNull() ?: 120, q["rows"]?.toIntOrNull() ?: 32)
                json(ex, ok())
            }
        }
        server.createContext("/api/term/close") { ex -> handle(ex) { term.close(); json(ex, ok()) } }
    }

    private fun ok() = Json.obj("ok" to Json.bool(true))

    private fun bootstrap(serial: String?): String {
        val b = dbg.bootstrap(serial)
        if (!b.ok) {
            return Json.obj(
                "ok" to Json.bool(false),
                "message" to Json.str(b.message),
                "devices" to Json.arr(emptyList()),
            )
        }
        val env = b.env!!
        return Json.obj(
            "ok" to Json.bool(true),
            "serial" to Json.str(b.serial),
            "devices" to Json.strArr(b.devices),
            "env" to Json.obj(
                "path" to Json.str(env.path),
                "summary" to Json.str(env.summary),
                "sdk" to Json.num(env.sdk),
                "emulator" to Json.bool(env.isEmulator),
                "roDebuggable" to Json.bool(env.roDebuggable),
            ),
            "apps" to Json.arr(b.apps.map {
                Json.obj(
                    "pkg" to Json.str(it.pkg),
                    "pid" to Json.num(it.pid),
                    "debuggable" to Json.bool(it.debuggable),
                )
            }),
            // 已有会话时一并回给前端，刷新页面才能恢复现场——
            // 状态本来只从 SSE 推来，新打开的页面在下一次事件之前是空的。
            // 没配 API key 时前端连按钮都不显示。
            "llm" to Json.bool(dbg.llmEnabled()),
            "session" to (dbg.pkg?.let {
                Json.obj(
                    "pkg" to Json.str(it),
                    "classCount" to Json.num(dbg.apk?.classCount ?: 0),
                )
            } ?: "null"),
        )
    }

    private fun openSession(q: Map<String, String>): String {
        val p = q["pkg"] ?: error("缺少 pkg 参数")
        val n = dbg.loadApp(p)
        return Json.obj("ok" to Json.bool(true), "pkg" to Json.str(p), "classCount" to Json.num(n))
    }

    private fun classes(q: Map<String, String>): String = Json.strArr(dbg.classNames(q["filter"]))

    private fun methods(q: Map<String, String>): String {
        val cls = q["class"] ?: return Json.arr(emptyList())
        return Json.arr(dbg.methodsOf(cls).map { (name, sig, count) ->
            Json.obj(
                "name" to Json.str(name),
                "signature" to Json.str(sig),
                "insnCount" to Json.num(count),
            )
        })
    }

    private fun methodView(q: Map<String, String>): String {
        val v = dbg.methodView(
            q["class"] ?: return "null",
            q["method"] ?: return "null",
            q["sig"] ?: return "null",
            q["pc"]?.toIntOrNull(),
        ) ?: return "null"
        return Json.of(v)
    }

    private fun addBp(q: Map<String, String>): String {
        val bp = dbg.addBreakpoint(
            q["class"] ?: error("缺少 class"),
            q["method"] ?: error("缺少 method"),
            q["sig"] ?: error("缺少 sig"),
            q["pc"]?.toIntOrNull() ?: error("缺少 pc"),
        )
        sse.send("breakpoints", breakpoints())
        return Json.of(bp)
    }

    private fun removeBp(q: Map<String, String>): String {
        dbg.removeBreakpoint(q["id"]?.toIntOrNull() ?: error("缺少 id"))
        sse.send("breakpoints", breakpoints())
        return ok()
    }

    private fun setCond(q: Map<String, String>): String {
        val id = q["id"]?.toIntOrNull() ?: error("缺少 id")
        // 空的三个参数 = 清除条件
        val cond = com.smaliscope.session.BpCondition(
            skip = q["skip"]?.toIntOrNull() ?: 0,
            reg = q["reg"]?.toIntOrNull(),
            equals = q["eq"]?.takeIf { it.isNotEmpty() },
        )
        val okSet = dbg.setBreakpointCondition(id, cond.takeUnless { it.isEmpty })
        sse.send("breakpoints", breakpoints())
        return Json.obj("ok" to Json.bool(okSet))
    }

    private fun breakpoints(): String = Json.arr(dbg.breakpoints().map { Json.of(it) })

    private fun templates(): String = Json.arr(dbg.breakpointTemplates().map {
        Json.obj(
            "id" to Json.str(it.id),
            "label" to Json.str(it.label),
            "count" to Json.num(it.count),
            "hint" to Json.str(it.hint),
        )
    })

    private fun applyTemplate(q: Map<String, String>): String {
        val added = dbg.applyTemplate(q["id"] ?: error("缺少 id"))
        sse.send("breakpoints", breakpoints())
        return Json.obj("ok" to Json.bool(true), "added" to Json.num(added.size))
    }

    private fun expand(q: Map<String, String>): String {
        val id = q["id"]?.toLongOrNull() ?: error("缺少 id")
        return dbg.expandObject(id)?.let { Json.of(it) } ?: "null"
    }

    private fun frame(q: Map<String, String>): String =
        dbg.readFrame(q["depth"]?.toIntOrNull() ?: 0)?.let { Json.of(it) } ?: "null"

    private fun setReg(q: Map<String, String>): String {
        val f = dbg.writeRegister(
            q["depth"]?.toIntOrNull() ?: 0,
            q["reg"]?.toIntOrNull() ?: error("缺少 reg"),
            q["value"] ?: error("缺少 value"),
        )
        return Json.obj("ok" to Json.bool(true), "frame" to Json.of(f))
    }

    /**
     * AI 讲解。慢（要往外发一次请求），所以只在用户点按钮时才会走到这里，
     * 绝不出现在单步路径上。
     */
    private fun explain(q: Map<String, String>): String {
        if (!dbg.llmEnabled()) {
            return Json.obj(
                "ok" to Json.bool(false),
                "message" to Json.str(
                    "尚未配置 API key。命令行执行 `smaliscope config llm.apiKey <你的key>` 后刷新页面。"
                ),
            )
        }
        val cls = q["class"] ?: error("缺少 class")
        val method = q["method"] ?: error("缺少 method")
        val sig = q["sig"] ?: error("缺少 sig")
        val text = when (q["mode"]) {
            "registers" -> dbg.nameRegisters(cls, method, sig)
            else -> dbg.explain(cls, method, sig, q["pc"]?.toIntOrNull())
        }
        return Json.obj("ok" to Json.bool(true), "text" to Json.str(text))
    }

    /** GET 返回当前 AI 配置（key 脱敏）；POST（带参数）保存后返回新状态。 */
    private fun config(ex: HttpExchange, q: Map<String, String>): String {
        if (ex.requestMethod == "POST" || q.containsKey("save")) {
            dbg.saveLlmConfig(
                baseUrl = q["baseUrl"],
                model = q["model"],
                // apiKey 缺省 = 不改动；显式传空串 = 清除。前端留空即不发这个参数。
                apiKey = if (q.containsKey("apiKey")) q["apiKey"] else null,
            )
        }
        val c = dbg.llmConfig()
        return Json.obj(
            "baseUrl" to Json.str(c.baseUrl),
            "model" to Json.str(c.model),
            "hasKey" to Json.bool(c.enabled),
            "maskedKey" to Json.str(c.maskedKey),
            "endpoint" to Json.str(c.chatEndpoint),
            "enabled" to Json.bool(c.enabled),
        )
    }

    // ── 内嵌终端 ───────────────────────────────────────────────────────────
    /**
     * 打开终端：这是一条 SSE 长连接。连上就起 PTY，shell 的原始输出（含 ANSI）逐块
     * base64 后经 `out` 事件推给前端 xterm.js；连接断开就关掉 PTY。
     */
    private fun termOpen(ex: HttpExchange) {
        val q = query(ex)
        val cols = q["cols"]?.toIntOrNull() ?: 120
        val rows = q["rows"]?.toIntOrNull() ?: 32
        ex.responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
        ex.responseHeaders.add("Cache-Control", "no-cache")
        ex.sendResponseHeaders(200, 0)
        val os = ex.responseBody
        val enc = java.util.Base64.getEncoder()
        fun emit(event: String, data: String) = synchronized(os) {
            runCatching { os.write("event: $event\ndata: $data\n\n".toByteArray(Charsets.UTF_8)); os.flush() }
        }
        writeSessionContext()   // 每次开终端刷新 CONTEXT.md，让 grok/codex 一进来就有上下文

        // 只有一个 term 实例，但可能被打开多次（刷新页面、重开）。用代次号认领：
        // 旧连接的收尾逻辑不能把新终端关掉——否则一刷新页面就「终端已退出」。
        val myGen = termGen.incrementAndGet()
        term.start(cols, rows,
            onOutput = { bytes -> if (termGen.get() == myGen) emit("out", enc.encodeToString(bytes)) },
            onExit = { if (termGen.get() == myGen) { emit("exit", "{}"); runCatching { ex.close() } } },
        )
        try {
            // 占住请求线程，SSE 不被关闭；顺便当断连探测。
            while (term.isAlive && termGen.get() == myGen) Thread.sleep(500)
        } catch (_: Throwable) {
        } finally {
            // 自己还是当前代次才真正关；被新连接取代的话什么都不做。
            if (termGen.get() == myGen) term.close()
            runCatching { ex.close() }
        }
    }

    private fun readBody(ex: HttpExchange): ByteArray = ex.requestBody.use { it.readBytes() }

    /**
     * 把当前调试上下文写进终端目录的 CONTEXT.md（功能②）。
     * grok/codex 会自动读 cwd 里的上下文文件，于是它们一进终端就知道
     * 「用户此刻在调什么、停在哪、寄存器是什么」，而不必自己瞎摸。
     */
    private fun writeSessionContext() {
        val sb = StringBuilder()
        sb.appendLine("# 当前 SmaliScope 调试上下文")
        sb.appendLine()
        sb.appendLine("你在一个内嵌于 SmaliScope（smali 指令级调试器）的终端里。")
        sb.appendLine("已注册的 `smaliscope` MCP 能驱动这个调试器；本机命令 `smaliscope` 也可直接用。")
        sb.appendLine()
        val pkg = dbg.pkg
        if (pkg == null) {
            sb.appendLine("- 当前**尚未载入**任何应用。可先 `list_apps` 看设备，再 `load_app`。")
        } else {
            sb.appendLine("- 已载入应用：`$pkg`（${dbg.apk?.classCount ?: 0} 个类）")
            val st = dbg.state
            if (st.status == "suspended") {
                val f = st.frames.firstOrNull()
                sb.appendLine("- **当前停在断点**：`${f?.fqcn}.${f?.method}${f?.signature}` dex_pc=${f?.dexPc}")
                f?.registers?.take(12)?.forEach {
                    sb.appendLine("    - ${it.name} ${it.type} = ${it.value}${if (it.readable) "" else "（不可读）"}")
                }
                sb.appendLine("  想核实这些值，直接用 MCP 的 `read_registers` / `read_stack`——和界面是同一个会话。")
            } else {
                sb.appendLine("- 状态：${st.message}")
            }
        }
        sb.appendLine()
        sb.appendLine("> 注意：寄存器显示「不可读 / 此处不可用」表示真读不出来，不是 0/null，别据此推断。")
        runCatching {
            termCwd.mkdirs()
            java.io.File(termCwd, "CONTEXT.md").writeText(sb.toString())
            // codex 读 AGENTS.md，grok 读 GROK.md / AGENTS.md，都指过去。
            java.io.File(termCwd, "AGENTS.md").writeText(sb.toString())
        }
    }

    private fun testLlm(): String = try {
        Json.obj("ok" to Json.bool(true), "reply" to Json.str(dbg.testLlm()))
    } catch (t: Throwable) {
        Json.obj("ok" to Json.bool(false), "message" to Json.str(t.message ?: t.toString()))
    }

    private fun javaSource(q: Map<String, String>): String {
        val cls = q["class"] ?: error("缺少 class")
        val (code, message) = dbg.javaSource(cls)
        return Json.obj(
            "ok" to Json.bool(code != null),
            "code" to Json.str(code),
            "message" to Json.str(message),
        )
    }

    // ── HTTP 基础设施 ────────────────────────────────────────────────────────

    private inline fun handle(ex: HttpExchange, body: () -> Unit) {
        try {
            body()
        } catch (t: Throwable) {
            runCatching {
                val payload = Json.obj(
                    "ok" to Json.bool(false),
                    "error" to Json.str(t.message ?: t.toString()),
                ).toByteArray(Charsets.UTF_8)
                ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
                ex.sendResponseHeaders(500, payload.size.toLong())
                ex.responseBody.use { it.write(payload) }
            }
        } finally {
            runCatching { ex.close() }
        }
    }

    private fun json(ex: HttpExchange, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun query(ex: HttpExchange): Map<String, String> {
        val raw = ex.requestURI.rawQuery ?: return emptyMap()
        return raw.split('&').mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else
                URLDecoder.decode(it.substring(0, i), "UTF-8") to
                    URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }.toMap()
    }

    private fun serveStatic(ex: HttpExchange) {
        val path = ex.requestURI.path.let { if (it == "/" || it.isBlank()) "/index.html" else it }
        if (path.contains("..")) {
            ex.sendResponseHeaders(400, -1); return
        }
        val stream = javaClass.getResourceAsStream("/web$path")
        if (stream == null) {
            ex.sendResponseHeaders(404, -1); return
        }
        val bytes = stream.use { it.readBytes() }
        val type = when {
            path.endsWith(".html") -> "text/html; charset=utf-8"
            path.endsWith(".css") -> "text/css; charset=utf-8"
            path.endsWith(".js") -> "application/javascript; charset=utf-8"
            path.endsWith(".svg") -> "image/svg+xml"
            else -> "application/octet-stream"
        }
        ex.responseHeaders.add("Content-Type", type)
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    override fun close() {
        runCatching { term.close() }
        runCatching { dbg.close() }
        sse.closeAll()
    }
}

/** SSE 广播：每个前端一条长连接，内核状态变化时逐个写。 */
class SseHub {
    private val clients = CopyOnWriteArrayList<OutputStream>()

    fun attach(ex: HttpExchange) {
        ex.responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
        ex.responseHeaders.add("Cache-Control", "no-cache")
        ex.responseHeaders.add("Connection", "keep-alive")
        ex.sendResponseHeaders(200, 0)
        val os = ex.responseBody
        clients += os
        try {
            os.write(": connected\n\n".toByteArray()); os.flush()
            // 占住这条请求线程，连接才不会被 HttpServer 关掉；心跳兼作断连探测。
            while (true) {
                Thread.sleep(15_000)
                synchronized(os) { os.write(": ping\n\n".toByteArray()); os.flush() }
            }
        } catch (_: Throwable) {
            // 前端关闭页面即走到这里
        } finally {
            clients -= os
            runCatching { ex.close() }
        }
    }

    fun send(event: String, data: String) {
        val payload = "event: $event\ndata: $data\n\n".toByteArray(Charsets.UTF_8)
        for (c in clients) {
            try {
                synchronized(c) { c.write(payload); c.flush() }
            } catch (_: Throwable) {
                clients -= c
            }
        }
    }

    fun closeAll() {
        clients.forEach { runCatching { it.close() } }
        clients.clear()
    }
}
