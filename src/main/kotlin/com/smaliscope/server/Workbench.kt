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
        server.createContext("/api/bootstrap") { ex -> handle(ex) { json(ex, bootstrap()) } }
        server.createContext("/api/session") { ex -> handle(ex) { json(ex, openSession(query(ex))) } }
        server.createContext("/api/classes") { ex -> handle(ex) { json(ex, classes(query(ex))) } }
        server.createContext("/api/methods") { ex -> handle(ex) { json(ex, methods(query(ex))) } }
        server.createContext("/api/method") { ex -> handle(ex) { json(ex, methodView(query(ex))) } }
        server.createContext("/api/bp") { ex -> handle(ex) { json(ex, addBp(query(ex))) } }
        server.createContext("/api/bp/remove") { ex -> handle(ex) { json(ex, removeBp(query(ex))) } }
        server.createContext("/api/breakpoints") { ex -> handle(ex) { json(ex, breakpoints()) } }
        server.createContext("/api/start") { ex -> handle(ex) { dbg.startAsync(); json(ex, ok()) } }
        server.createContext("/api/control") { ex ->
            handle(ex) { dbg.control(query(ex)["action"] ?: error("缺少 action")); json(ex, ok()) }
        }
        server.createContext("/api/object") { ex -> handle(ex) { json(ex, expand(query(ex))) } }
        server.createContext("/api/frame") { ex -> handle(ex) { json(ex, frame(query(ex))) } }
        server.createContext("/api/java") { ex -> handle(ex) { json(ex, javaSource(query(ex))) } }
        server.createContext("/api/timeline") { ex ->
            handle(ex) { json(ex, Json.arr(dbg.timeline().map { Json.of(it) })) }
        }
        server.createContext("/api/state") { ex -> handle(ex) { json(ex, Json.of(dbg.state)) } }
    }

    private fun ok() = Json.obj("ok" to Json.bool(true))

    private fun bootstrap(): String {
        val b = dbg.bootstrap()
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

    private fun breakpoints(): String = Json.arr(dbg.breakpoints().map { Json.of(it) })

    private fun expand(q: Map<String, String>): String {
        val id = q["id"]?.toLongOrNull() ?: error("缺少 id")
        return dbg.expandObject(id)?.let { Json.of(it) } ?: "null"
    }

    private fun frame(q: Map<String, String>): String =
        dbg.readFrame(q["depth"]?.toIntOrNull() ?: 0)?.let { Json.of(it) } ?: "null"

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
