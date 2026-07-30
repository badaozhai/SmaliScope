package com.smaliscope.session

import com.smaliscope.server.JsonParse
import com.smaliscope.server.Jv
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 把调试操作转发到**正在运行的工作台**，而不是自己再开一个 JDWP 会话。
 *
 * 这样 `smaliscope mcp`（被 grok / codex 等 agent 拉起）和用户浏览器里的工作台
 * 就落在**同一个活会话**上：agent 下的断点会点亮界面里的断点面板，
 * agent 读寄存器读到的就是用户眼前那一帧。否则两边各持一个 Debugger，
 * 是两个互不知情的平行调试器——agent 看到的永远是个空调试器。
 *
 * 复用工作台已有的 HTTP 端点，不为此新增协议。探测不到工作台时，
 * 调用方（cmdMcp）会退回进程内的 [Debugger]。
 */
class RemoteDebugger(private val baseUrl: String) : DebugFacade {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    // ── HTTP 基础 ───────────────────────────────────────────────────────────

    private fun url(path: String, params: Map<String, Any?> = emptyMap()): URI {
        val q = params.entries
            .filter { it.value != null }
            .joinToString("&") {
                "${it.key}=${URLEncoder.encode(it.value.toString(), "UTF-8")}"
            }
        return URI.create(baseUrl + path + if (q.isEmpty()) "" else "?$q")
    }

    private fun send(method: String, path: String, params: Map<String, Any?> = emptyMap(),
                     timeoutSec: Long = 300): Jv {
        val req = HttpRequest.newBuilder(url(path, params))
            .timeout(Duration.ofSeconds(timeoutSec))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build()
        val resp: HttpResponse<String> =
            http.send(req, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        val body = resp.body()
        if (resp.statusCode() !in 200..299) {
            // 工作台把错误放在 {"error": "..."} 里，尽量把那句中文原样透出去
            val msg = runCatching { JsonParse.parse(body)["error"]?.string }.getOrNull()
            error(msg ?: "工作台返回 ${resp.statusCode()}")
        }
        return if (body.isBlank()) Jv.Null else JsonParse.parse(body)
    }

    private fun get(path: String, params: Map<String, Any?> = emptyMap(), timeoutSec: Long = 300) =
        send("GET", path, params, timeoutSec)

    private fun post(path: String, params: Map<String, Any?> = emptyMap(), timeoutSec: Long = 300) =
        send("POST", path, params, timeoutSec)

    // ── 反序列化（把工作台的 JSON 还原成视图对象）──────────────────────────

    private fun toRegister(j: Jv) = RegisterView(
        reg = j["reg"]?.int ?: 0,
        name = j["name"]?.string ?: "",
        type = j["type"]?.string ?: "",
        value = j["value"]?.string ?: "",
        changed = j["changed"]?.bool ?: false,
        readable = j["readable"]?.bool ?: false,
        error = j["error"]?.string,
        objectId = j["objectId"]?.long,
        expandable = j["expandable"]?.bool ?: false,
        hint = j["hint"]?.string,
    )

    private fun toFrame(j: Jv) = FrameView(
        frameId = j["frameId"]?.long ?: 0,
        depth = j["depth"]?.int ?: 0,
        fqcn = j["fqcn"]?.string ?: "",
        method = j["method"]?.string ?: "",
        signature = j["signature"]?.string ?: "",
        dexPc = j["dexPc"]?.int ?: 0,
        hasModel = j["hasModel"]?.bool ?: false,
        registers = j["registers"]?.list.orEmpty().map { toRegister(it) },
    )

    private fun toState(j: Jv) = DebugState(
        status = j["status"]?.string ?: "idle",
        message = j["message"]?.string ?: "",
        reason = j["reason"]?.string,
        deoptWarning = j["deoptWarning"]?.bool ?: false,
        frames = j["frames"]?.list.orEmpty().map { toFrame(it) },
    )

    private fun toBreakpoint(j: Jv) = BreakpointView(
        id = j["id"]?.int ?: 0,
        fqcn = j["fqcn"]?.string ?: "",
        method = j["method"]?.string ?: "",
        signature = j["signature"]?.string ?: "",
        dexPc = j["dexPc"]?.int ?: 0,
        state = j["state"]?.string ?: "",
        hitCount = j["hitCount"]?.int ?: 0,
        note = j["note"]?.string,
        condition = j["condition"]?.string,
    )

    private fun toMethodView(j: Jv) = MethodView(
        fqcn = j["fqcn"]?.string ?: "",
        method = j["method"]?.string ?: "",
        signature = j["signature"]?.string ?: "",
        registerCount = j["registerCount"]?.int ?: 0,
        registerNames = j["registerNames"]?.list.orEmpty().mapNotNull { it.string },
        analysisWarning = j["analysisWarning"]?.string,
        instructions = j["instructions"]?.list.orEmpty().map {
            InsnView(
                dexPc = it["dexPc"]?.int ?: 0,
                index = it["index"]?.int ?: 0,
                text = it["text"]?.string ?: "",
                opcode = it["opcode"]?.string ?: "",
                reads = it["reads"]?.list.orEmpty().mapNotNull { r -> r.int },
                writes = it["writes"]?.list.orEmpty().mapNotNull { w -> w.int },
                isBranch = it["isBranch"]?.bool ?: false,
                isInvoke = it["isInvoke"]?.bool ?: false,
                isReturn = it["isReturn"]?.bool ?: false,
                doc = it["doc"]?.string,
            )
        },
        blocks = j["blocks"]?.list.orEmpty().map {
            BlockView(
                id = it["id"]?.int ?: 0,
                startPc = it["startPc"]?.int ?: 0,
                endPc = it["endPc"]?.int ?: 0,
                successors = it["successors"]?.list.orEmpty().mapNotNull { s -> s.int },
                visited = it["visited"]?.bool ?: false,
                current = it["current"]?.bool ?: false,
            )
        },
    )

    // ── DebugFacade ─────────────────────────────────────────────────────────

    override val state: DebugState get() = runCatching { toState(get("/api/state")) }
        .getOrDefault(DebugState("idle", "无法读取工作台状态"))

    override val pkg: String?
        get() = runCatching { get("/api/bootstrap")["session"]?.get("pkg")?.string }.getOrNull()

    override fun bootstrap(want: String?): Debugger.Bootstrap {
        val j = get("/api/bootstrap", mapOf("serial" to want))
        if (j["ok"]?.bool != true) {
            return Debugger.Bootstrap(false, j["message"]?.string, null, emptyList(), null, emptyList())
        }
        val e = j["env"]
        return Debugger.Bootstrap(
            ok = true,
            message = null,
            serial = j["serial"]?.string,
            devices = j["devices"]?.list.orEmpty().mapNotNull { it.string },
            // 工作台只发出 EnvProbe 的展示字段，这里按它给的值还原。
            // rootKind/hasZygisk 它没发（MCP 侧的工具也不看），保守填。
            env = EnvProbe(
                roDebuggable = e?.get("roDebuggable")?.bool ?: false,
                sdk = e?.get("sdk")?.int ?: 0,
                isEmulator = e?.get("emulator")?.bool ?: false,
                hasSu = false,
            ),
            apps = j["apps"]?.list.orEmpty().map {
                Debugger.AppEntry(
                    it["pkg"]?.string ?: "",
                    it["pid"]?.int,
                    it["debuggable"]?.bool ?: false,
                )
            },
        )
    }

    override fun loadApp(packageName: String): Int =
        post("/api/session", mapOf("pkg" to packageName))["classCount"]?.int ?: 0

    override fun classNames(filter: String?, limit: Int): List<String> =
        get("/api/classes", mapOf("filter" to filter)).list.mapNotNull { it.string }.take(limit)

    override fun methodsOf(fqcn: String): List<Triple<String, String, Int>> =
        get("/api/methods", mapOf("class" to fqcn)).list.map {
            Triple(it["name"]?.string ?: "", it["signature"]?.string ?: "", it["insnCount"]?.int ?: 0)
        }

    override fun methodView(fqcn: String, method: String, signature: String, pc: Int?): MethodView? {
        val j = get("/api/method", mapOf("class" to fqcn, "method" to method, "sig" to signature, "pc" to pc))
        return if (j == Jv.Null) null else toMethodView(j)
    }

    /** 类名解析在远端没有专用端点，用类表自己匹配（和本地实现同一规则）。 */
    override fun resolveClass(name: String): String? =
        runCatching { get("/api/classes").list.mapNotNull { it.string } }
            .getOrDefault(emptyList())
            .firstOrNull { it == name || it.endsWith(".$name") }

    override fun resolveMethod(fqcn: String, method: String, signature: String?): String? {
        if (signature != null) return signature
        return methodsOf(fqcn).firstOrNull { it.first == method }?.second
    }

    override fun javaSource(fqcn: String): Pair<String?, String?> {
        val j = get("/api/java", mapOf("class" to fqcn))
        return j["code"]?.string to j["message"]?.string
    }

    override fun addBreakpoint(
        fqcn: String, method: String, signature: String, dexPc: Int, condition: BpCondition?,
    ): BreakpointView {
        val bp = toBreakpoint(post("/api/bp", mapOf(
            "class" to fqcn, "method" to method, "sig" to signature, "pc" to dexPc,
        )))
        // 条件走单独端点（工作台的 /api/bp 不收条件参数）
        if (condition != null && !condition.isEmpty) {
            setBreakpointCondition(bp.id, condition)
            return breakpoints().firstOrNull { it.id == bp.id } ?: bp
        }
        return bp
    }

    override fun setBreakpointCondition(id: Int, condition: BpCondition?): Boolean =
        post("/api/bp/cond", mapOf(
            "id" to id,
            "skip" to (condition?.skip ?: 0),
            "reg" to condition?.reg,
            "eq" to condition?.equals,
        ))["ok"]?.bool ?: false

    override fun removeBreakpoint(id: Int) { post("/api/bp/remove", mapOf("id" to id)) }

    override fun breakpoints(): List<BreakpointView> =
        get("/api/breakpoints").list.map { toBreakpoint(it) }

    override fun breakpointTemplates(): List<Debugger.BpTemplate> =
        get("/api/templates").list.map {
            Debugger.BpTemplate(
                it["id"]?.string ?: "", it["label"]?.string ?: "",
                it["count"]?.int ?: 0, it["hint"]?.string,
            )
        }

    override fun applyTemplate(id: String): List<BreakpointView> {
        post("/api/template", mapOf("id" to id))
        return breakpoints()
    }

    override fun start() { post("/api/start") }

    override fun control(action: String) { post("/api/control", mapOf("action" to action)) }

    /**
     * 远端没有事件流，用轮询实现「发起动作 → 等下一次停下」：
     * 先记下当前是否已挂起，触发动作后等到出现**新的**挂起。
     * 靠 (status, dexPc, 栈深) 的变化判断，避免把「本来就停着」当成新落点。
     */
    override fun actAndWait(timeoutMs: Long, action: () -> Unit): DebugState? {
        fun stamp(s: DebugState) =
            "${s.status}|${s.frames.firstOrNull()?.dexPc}|${s.frames.size}|${s.reason}"
        val before = stamp(state)
        action()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val s = state
            if (s.status == "suspended" && stamp(s) != before) return s
            Thread.sleep(200)
        }
        return null
    }

    override fun readFrame(depth: Int): FrameView? {
        val j = get("/api/frame", mapOf("depth" to depth))
        return if (j == Jv.Null) null else toFrame(j)
    }

    override fun writeRegister(depth: Int, reg: Int, text: String): FrameView =
        toFrame(post("/api/setreg", mapOf("depth" to depth, "reg" to reg, "value" to text))["frame"]!!)

    override fun expandObject(objectId: Long): ObjectNode? {
        val j = get("/api/object", mapOf("id" to objectId))
        if (j == Jv.Null) return null
        return ObjectNode(
            objectId = j["objectId"]?.long ?: objectId,
            label = j["label"]?.string ?: "",
            type = j["type"]?.string ?: "",
            arrayLength = j["arrayLength"]?.int,
            fields = j["fields"]?.list.orEmpty().map {
                ObjectField(
                    name = it["name"]?.string ?: "",
                    type = it["type"]?.string ?: "",
                    value = it["value"]?.string ?: "",
                    objectId = it["objectId"]?.long,
                    expandable = it["expandable"]?.bool ?: false,
                )
            },
            truncated = j["truncated"]?.bool ?: false,
        )
    }

    override fun llmEnabled(): Boolean =
        runCatching { get("/api/config")["enabled"]?.bool }.getOrNull() ?: false

    override fun explain(fqcn: String, method: String, signature: String, dexPc: Int?): String {
        val j = get("/api/explain", mapOf(
            "class" to fqcn, "method" to method, "sig" to signature, "pc" to dexPc, "mode" to "code",
        ))
        return j["text"]?.string ?: j["message"]?.string ?: "没有返回内容"
    }

    override fun nameRegisters(fqcn: String, method: String, signature: String): String {
        val j = get("/api/explain", mapOf(
            "class" to fqcn, "method" to method, "sig" to signature, "mode" to "registers",
        ))
        return j["text"]?.string ?: j["message"]?.string ?: "没有返回内容"
    }

    companion object {
        /**
         * 探测本机是否已有工作台在跑。有就返回一个连它的 RemoteDebugger。
         * 端口顺序：显式指定 → 默认 8080。探测用很短的超时，别拖慢 MCP 启动。
         */
        fun discover(port: Int? = null): RemoteDebugger? {
            val ports = listOfNotNull(
                port,
                System.getenv("SMALISCOPE_PORT")?.toIntOrNull(),
                8080,
            ).distinct()
            for (p in ports) {
                val base = "http://127.0.0.1:$p"
                val ok = runCatching {
                    val c = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(600)).build()
                    val r = c.send(
                        HttpRequest.newBuilder(URI.create("$base/api/state"))
                            .timeout(Duration.ofMillis(900)).GET().build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )
                    r.statusCode() in 200..299
                }.getOrDefault(false)
                if (ok) return RemoteDebugger(base)
            }
            return null
        }
    }
}
