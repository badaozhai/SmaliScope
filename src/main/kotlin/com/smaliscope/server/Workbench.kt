package com.smaliscope.server

import com.smaliscope.adb.AdbClient
import com.smaliscope.analysis.ApkIndex
import com.smaliscope.session.DebugSession
import com.smaliscope.session.DebugState
import com.smaliscope.session.DeviceApps
import com.smaliscope.stepping.StepMode
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * 本地 Web 工作台：HTTP + SSE 事件推流，全部跑在 127.0.0.1。
 *
 * 设计方案写的是 WebSocket；这里用 Server-Sent Events：状态推送本来就是单向的
 * （内核 → 前端），命令走普通 HTTP 请求。SSE 在 JDK 自带的 HttpServer 上二十行就能实现，
 * 而手写 RFC 6455 的分帧、掩码、心跳和关闭握手要多几百行，对本项目没有任何额外收益。
 */
fun startWorkbench(port: Int) {
    val wb = Workbench()
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
    // 主线程挂住，服务由 HttpServer 的线程池承载。
    Thread.currentThread().join()
}

class Workbench : AutoCloseable {

    private val adb = AdbClient()
    private val sse = SseHub()

    @Volatile private var serial: String? = null
    @Volatile private var pkg: String? = null
    @Volatile private var apk: ApkIndex? = null
    @Volatile private var session: DebugSession? = null
    @Volatile private var jadx: com.smaliscope.decompile.JadxService? = null
    @Volatile private var lastState: DebugState = DebugState("idle", "尚未开始调试")

    private val logs = CopyOnWriteArrayList<String>()

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
        server.createContext("/api/start") { ex -> handle(ex) { json(ex, start()) } }
        server.createContext("/api/control") { ex -> handle(ex) { json(ex, control(query(ex))) } }
        server.createContext("/api/object") { ex -> handle(ex) { json(ex, expand(query(ex))) } }
        server.createContext("/api/frame") { ex -> handle(ex) { json(ex, frame(query(ex))) } }
        server.createContext("/api/java") { ex -> handle(ex) { json(ex, javaSource(query(ex))) } }
        server.createContext("/api/timeline") { ex -> handle(ex) { json(ex, timeline()) } }
        server.createContext("/api/state") { ex -> handle(ex) { json(ex, Json.of(lastState)) } }
    }

    // ── API ─────────────────────────────────────────────────────────────────

    private fun bootstrap(): String {
        val devices = adb.devices().filter { it.isOnline }
        if (devices.isEmpty()) {
            return Json.obj(
                "ok" to Json.bool(false),
                "message" to Json.str("未发现在线设备。请先启动模拟器（推荐 AVD 的非 Play 镜像），或用 USB 连接手机。"),
                "devices" to Json.arr(emptyList()),
            )
        }
        val dev = devices.firstOrNull { it.isEmulator } ?: devices.first()
        serial = dev.serial
        val apps = DeviceApps(adb, dev.serial)
        val env = apps.probeEnvironment()
        val jdwp = adb.jdwpPids(dev.serial).toSet()
        val procs = runCatching { apps.runningProcesses() }.getOrDefault(emptyMap())

        val list = apps.listPackages().map { p ->
            val pid = procs[p]
            Json.obj(
                "pkg" to Json.str(p),
                "pid" to Json.num(pid),
                "debuggable" to Json.bool(pid != null && pid in jdwp),
            )
        }
        return Json.obj(
            "ok" to Json.bool(true),
            "serial" to Json.str(dev.serial),
            "devices" to Json.strArr(devices.map { it.serial }),
            "env" to Json.obj(
                "path" to Json.str(env.path),
                "summary" to Json.str(env.summary),
                "sdk" to Json.num(env.sdk),
                "emulator" to Json.bool(env.isEmulator),
                "roDebuggable" to Json.bool(env.roDebuggable),
            ),
            "apps" to Json.arr(list),
        )
    }

    /** 选定应用：拉 APK、做静态分析、建会话（此时还没连上进程）。 */
    private fun openSession(q: Map<String, String>): String {
        val s = serial ?: error("尚未选择设备")
        val p = q["pkg"] ?: error("缺少 pkg 参数")
        session?.let { runCatching { it.close() } }

        log("正在获取 $p 的 APK…")
        val apps = DeviceApps(adb, s)
        val files = apps.pullApks(p, com.smaliscope.cacheDir)
        val env = apps.probeEnvironment()
        val index = ApkIndex(files, env.sdk.coerceIn(21, 35))
        log("已解析 ${index.classCount} 个类")

        apk = index
        pkg = p
        runCatching { jadx?.close() }
        jadx = com.smaliscope.decompile.JadxService(files)
        val sess = DebugSession(adb, s, p, index)
        sess.onLog = { log(it) }
        sess.onState = { st ->
            lastState = st
            sse.send("state", Json.of(st))
            sse.send("breakpoints", breakpoints())
        }
        session = sess
        lastState = DebugState("idle", "已载入 $p，选一条指令下断点后点「开始调试」")
        sse.send("state", Json.of(lastState))

        return Json.obj(
            "ok" to Json.bool(true),
            "pkg" to Json.str(p),
            "classCount" to Json.num(index.classCount),
        )
    }

    private fun classes(q: Map<String, String>): String {
        val index = apk ?: return Json.arr(emptyList())
        val filter = q["filter"]?.lowercase()
        val own = index.appClassNames(pkg?.substringBeforeLast('.')?.takeIf { it.isNotBlank() })
            .ifEmpty { index.appClassNames() }
        val list = if (filter.isNullOrBlank()) own else own.filter { it.lowercase().contains(filter) }
        return Json.strArr(list.take(500))
    }

    private fun methods(q: Map<String, String>): String {
        val index = apk ?: return Json.arr(emptyList())
        val cls = q["class"] ?: return Json.arr(emptyList())
        return Json.arr(index.concreteMethodsOf(cls).map { m ->
            Json.obj(
                "name" to Json.str(m.name),
                "signature" to Json.str(m.signature),
                "insnCount" to Json.num(index.model(m)?.instructions?.size ?: 0),
            )
        })
    }

    private fun methodView(q: Map<String, String>): String {
        val sess = session
        val index = apk ?: return "null"
        val cls = q["class"] ?: return "null"
        val name = q["method"] ?: return "null"
        val sig = q["sig"] ?: return "null"
        val pc = q["pc"]?.toIntOrNull()
        val view = sess?.methodView(cls, name, sig, pc)
            ?: index.model(cls, name, sig)?.let { m ->
                // 还没建会话时也能浏览 smali（此时没有「走过的路」信息）。
                com.smaliscope.session.MethodView(
                    fqcn = cls, method = name, signature = sig,
                    registerCount = m.registerCount,
                    registerNames = (0 until m.registerCount).map { r -> m.regName(r) },
                    analysisWarning = m.analysisError?.let { "寄存器类型推导未完全成功（$it）" },
                    instructions = m.instructions.map {
                        com.smaliscope.session.InsnView(
                            it.dexPc, it.index, it.text, it.opcodeName, it.reads, it.writes,
                            it.isBranch, it.isInvoke, it.isReturn,
                            com.smaliscope.dict.SmaliDict.describe(it.opcodeName),
                        )
                    },
                    blocks = m.basicBlocks.map { b ->
                        com.smaliscope.session.BlockView(b.id, b.startPc, b.endPc, b.successors, false, false)
                    },
                )
            }
            ?: return "null"
        return Json.of(view)
    }

    private fun addBp(q: Map<String, String>): String {
        val sess = session ?: error("请先选择要调试的应用")
        val bp = sess.addBreakpoint(
            q["class"] ?: error("缺少 class"),
            q["method"] ?: error("缺少 method"),
            q["sig"] ?: error("缺少 sig"),
            q["pc"]?.toIntOrNull() ?: error("缺少 pc"),
        )
        sse.send("breakpoints", breakpoints())
        return Json.of(bp)
    }

    private fun removeBp(q: Map<String, String>): String {
        session?.removeBreakpoint(q["id"]?.toIntOrNull() ?: error("缺少 id"))
        sse.send("breakpoints", breakpoints())
        return Json.obj("ok" to Json.bool(true))
    }

    private fun breakpoints(): String =
        Json.arr(session?.listBreakpoints()?.map { Json.of(it) } ?: emptyList())

    /** 挂起启动比较慢（要重启应用并等它变为可调试），放后台跑，进度经 SSE 推。 */
    private fun start(): String {
        val sess = session ?: error("请先选择要调试的应用")
        Thread({
            runCatching { sess.launchSuspended() }
                .onFailure {
                    log("启动失败：${it.message}")
                    lastState = DebugState("idle", "启动失败：${it.message}")
                    sse.send("state", Json.of(lastState))
                }
        }, "smaliscope-launch").start()
        return Json.obj("ok" to Json.bool(true))
    }

    private fun control(q: Map<String, String>): String {
        val sess = session ?: error("尚未开始调试")
        when (q["action"]) {
            "resume" -> sess.resume()
            "into" -> sess.step(StepMode.INTO)
            "over" -> sess.step(StepMode.OVER)
            "out" -> sess.step(StepMode.OUT)
            "stop" -> { sess.close(); session = null }
            else -> error("未知操作")
        }
        return Json.obj("ok" to Json.bool(true))
    }

    private fun expand(q: Map<String, String>): String {
        val id = q["id"]?.toLongOrNull() ?: error("缺少 id")
        val node = session?.expandObject(id) ?: return "null"
        return Json.of(node)
    }

    private fun frame(q: Map<String, String>): String {
        val depth = q["depth"]?.toIntOrNull() ?: 0
        val f = session?.readFrame(depth) ?: return "null"
        return Json.of(f)
    }

    /** Java 视图：只为「看懂逻辑」，断点仍下在 smali 侧。 */
    private fun javaSource(q: Map<String, String>): String {
        val cls = q["class"] ?: error("缺少 class")
        val svc = jadx ?: return Json.obj(
            "ok" to Json.bool(false), "message" to Json.str("请先载入应用"))
        val code = svc.javaOf(cls)
        return Json.obj(
            "ok" to Json.bool(code != null),
            "code" to Json.str(code),
            "message" to Json.str(
                code?.let { null }
                    ?: svc.error?.let { "反编译器初始化失败：$it" }
                    ?: "jadx 无法反编译该类（它一共识别出 ${svc.classCount()} 个类），可以继续看 smali。"
            ),
        )
    }

    private fun timeline(): String =
        Json.arr(session?.timeline?.toList()?.map { Json.of(it) } ?: emptyList())

    private fun log(msg: String) {
        logs += msg
        if (logs.size > 500) logs.removeAt(0)
        sse.send("log", Json.str(msg))
    }

    // ── HTTP 基础设施 ────────────────────────────────────────────────────────

    private inline fun handle(ex: HttpExchange, body: () -> Unit) {
        try {
            body()
        } catch (t: Throwable) {
            val msg = t.message ?: t.toString()
            runCatching {
                val payload = Json.obj("ok" to Json.bool(false), "error" to Json.str(msg))
                    .toByteArray(Charsets.UTF_8)
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
        runCatching { session?.close() }
        runCatching { jadx?.close() }
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
