package com.smaliscope.mcp

import com.smaliscope.server.Json
import com.smaliscope.server.Jv
import com.smaliscope.session.DebugFacade

/**
 * 暴露给 MCP 客户端的工具集。
 *
 * 返回值一律是给模型看的紧凑文本而非原始 JSON：模型读表格式文本更省 token 也更不容易读错，
 * 而且能顺带把「为什么读不出来」这类解释带上——这一点对本项目尤其重要，
 * 寄存器不可读时必须让模型知道那是不可读，而不是把它当成 0。
 */
object Tools {

    private data class Tool(
        val name: String,
        val description: String,
        val schema: String,
        /** 需要 LLM 配置的工具，没配 key 时不注册——省得 agent 去调一个必然失败的工具。 */
        val requiresLlm: Boolean = false,
        val run: (DebugFacade, Jv) -> String,
    )

    private fun prop(name: String, type: String, desc: String) =
        "${Json.str(name)}:${Json.obj("type" to Json.str(type), "description" to Json.str(desc))}"

    private fun schema(vararg props: String, required: List<String> = emptyList()) = Json.obj(
        "type" to Json.str("object"),
        "properties" to "{${props.joinToString(",")}}",
        "required" to Json.strArr(required),
    )

    private val TOOLS: List<Tool> = listOf(
        Tool(
            "list_apps",
            "列出设备、环境探测结果，以及设备上的应用（标出哪些当前可被 JDWP 调试）。先调这个。",
            schema(),
        ) { dbg, _ ->
            val b = dbg.bootstrap()
            if (!b.ok) return@Tool b.message ?: "未发现设备"
            val env = b.env!!
            buildString {
                append("设备：${b.serial}  Android SDK ${env.sdk}")
                append(if (env.isEmulator) "  模拟器\n" else "\n")
                append("接入路径 ${env.path}：${env.summary}\n\n")
                append("应用（● 表示当前可调试）：\n")
                b.apps.sortedByDescending { it.debuggable }.forEach {
                    append(if (it.debuggable) "  ● " else "    ")
                    append(it.pkg)
                    it.pid?.let { p -> append("  pid=$p") }
                    append("\n")
                }
            }
        },

        Tool(
            "load_app",
            "载入一个应用：从设备拉取 APK 并做静态分析（类表、dex_pc 偏移表、CFG、寄存器类型推导）。下断点前必须先调这个。",
            schema(prop("package", "string", "应用包名，例如 com.example.app"), required = listOf("package")),
        ) { dbg, a ->
            val pkg = a["package"]?.string ?: error("缺少 package")
            val n = dbg.loadApp(pkg)
            "已载入 $pkg，解析出 $n 个类。用 list_classes 浏览。"
        },

        Tool(
            "list_classes",
            "列出已载入应用中属于该应用自身的类（已滤掉 framework 与 kotlin 等噪音）。",
            schema(prop("filter", "string", "可选，按子串过滤类名")),
        ) { dbg, a ->
            val list = dbg.classNames(a["filter"]?.string)
            if (list.isEmpty()) "没有匹配的类（是否还没 load_app？）"
            else "共 ${list.size} 个类：\n" + list.joinToString("\n") { "  $it" }
        },

        Tool(
            "list_methods",
            "列出某个类中有方法体（可下断点）的方法。抽象与 native 方法没有 dex 代码，不会列出。",
            schema(prop("class", "string", "类名，可用全名或简名"), required = listOf("class")),
        ) { dbg, a ->
            val fqcn = resolveClass(dbg, a)
            val ms = dbg.methodsOf(fqcn)
            if (ms.isEmpty()) "$fqcn 没有可下断点的方法"
            else "$fqcn:\n" + ms.joinToString("\n") { (n, s, c) -> "  $n$s  ($c 条指令)" }
        },

        Tool(
            "disassemble",
            "反汇编一个方法，给出每条指令的 dex_pc、smali 文本、读写的寄存器、后继指令，以及基本块划分。" +
                "set_breakpoint 用的 dexPc 就取自这里。",
            schema(
                prop("class", "string", "类名"),
                prop("method", "string", "方法名"),
                prop("signature", "string", "可选，JVM 方法签名如 (II)I；重载时用来消歧"),
                required = listOf("class", "method"),
            ),
        ) { dbg, a ->
            val (fqcn, name, sig) = resolveMethod(dbg, a)
            val v = dbg.methodView(fqcn, name, sig, null) ?: error("找不到方法 $fqcn.$name$sig")
            buildString {
                append("$fqcn.$name$sig  共 ${v.registerCount} 个寄存器")
                append("（${v.registerNames.joinToString(" ")}）\n")
                v.analysisWarning?.let { append("⚠ $it\n") }
                append("\ndex_pc  指令 / 读→写 / 后继\n")
                for (i in v.instructions) {
                    val nm = { r: Int -> v.registerNames.getOrElse(r) { "v$r" } }
                    append("%-7d %s".format(i.dexPc, i.text))
                    if (i.reads.isNotEmpty() || i.writes.isNotEmpty()) {
                        append("   [读 ${i.reads.joinToString(",", transform = nm).ifEmpty { "—" }}")
                        append(" → 写 ${i.writes.joinToString(",", transform = nm).ifEmpty { "—" }}]")
                    }
                    append("\n")
                }
                append("\n基本块：\n")
                v.blocks.forEach { b ->
                    append("  块 ${b.id}  pc ${b.startPc}..${b.endPc} → ")
                    append(b.successors.joinToString(",").ifEmpty { "（出口）" })
                    if (b.visited) append("  [已执行过]")
                    append("\n")
                }
            }
        },

        Tool(
            "decompile_java",
            "用 jadx 把一个类反编译成 Java，用来快速看懂逻辑。断点仍然要下在 smali 侧的 dex_pc 上。",
            schema(prop("class", "string", "类名"), required = listOf("class")),
        ) { dbg, a ->
            val fqcn = resolveClass(dbg, a)
            val (code, msg) = dbg.javaSource(fqcn)
            code ?: (msg ?: "无法反编译")
        },

        Tool(
            "set_breakpoint",
            "在某条 smali 指令上下断点。dexPc 必须是 disassemble 里出现过的偏移。" +
                "类还没加载时会自动转为 pending，等类加载后生效，无需额外处理。" +
                "可选条件断点（二期）：skip 跳过前 N 次命中；whenReg + equals 只在某寄存器等于某值时才停。",
            schema(
                prop("class", "string", "类名"),
                prop("method", "string", "方法名"),
                prop("signature", "string", "可选，JVM 方法签名"),
                prop("dexPc", "integer", "指令偏移，取自 disassemble"),
                prop("skip", "integer", "可选，跳过前 N 次命中（循环里定位第 N 圈很有用）"),
                prop("whenReg", "integer", "可选，寄存器号；配合 equals，只在它等于 equals 时才停"),
                prop("equals", "string", "可选，whenReg 要匹配的值（按面板显示的样子，如 5 / true）"),
                required = listOf("class", "method", "dexPc"),
            ),
        ) { dbg, a ->
            val (fqcn, name, sig) = resolveMethod(dbg, a)
            val pc = a["dexPc"]?.int ?: error("缺少 dexPc")
            val cond = com.smaliscope.session.BpCondition(
                skip = a["skip"]?.int ?: 0,
                reg = a["whenReg"]?.int,
                equals = a["equals"]?.string,
            ).takeUnless { it.isEmpty }
            val bp = dbg.addBreakpoint(fqcn, name, sig, pc, cond)
            "断点 #${bp.id} 已设置：$fqcn.$name @ dex_pc $pc，状态 ${bp.state}" +
                (bp.condition?.let { "，条件：$it" } ?: "") + (bp.note?.let { "（$it）" } ?: "")
        },

        Tool(
            "set_breakpoint_condition",
            "给一个已存在的断点设置或清除条件（二期）。三个条件参数都留空即清除条件、恢复为每次都停。",
            schema(
                prop("id", "integer", "断点编号"),
                prop("skip", "integer", "跳过前 N 次命中"),
                prop("whenReg", "integer", "寄存器号，配合 equals"),
                prop("equals", "string", "whenReg 要匹配的值"),
                required = listOf("id"),
            ),
        ) { dbg, a ->
            val id = a["id"]?.int ?: error("缺少 id")
            val cond = com.smaliscope.session.BpCondition(
                skip = a["skip"]?.int ?: 0,
                reg = a["whenReg"]?.int,
                equals = a["equals"]?.string,
            ).takeUnless { it.isEmpty }
            if (!dbg.setBreakpointCondition(id, cond)) "没有编号为 $id 的断点"
            else if (cond == null) "已清除断点 #$id 的条件" else "断点 #$id 的条件已设为：${cond.describe()}"
        },

        Tool(
            "set_breakpoint_template",
            "一键在常见入口批量下断点，省去自己翻类名。可用模板由 list_apps 之后的 load_app 决定：" +
                "activity-oncreate（所有 Activity 的 onCreate）、application-oncreate（Application.onCreate，" +
                "应用最早的自有代码）。不带 id 时列出当前应用可用的模板及各自会下多少个断点。",
            schema(prop("id", "string", "模板 id；留空则只列出可用模板")),
        ) { dbg, a ->
            val id = a["id"]?.string
            if (id.isNullOrBlank()) {
                val t = dbg.breakpointTemplates()
                if (t.isEmpty()) "当前应用没有可用模板（可能没有 Activity/Application 子类，或还没 load_app）"
                else "可用模板：\n" + t.joinToString("\n") {
                    "  ${it.id}  ——  ${it.label}（${it.count} 个）" + (it.hint?.let { h -> "；$h" } ?: "")
                }
            } else {
                val added = dbg.applyTemplate(id)
                if (added.isEmpty()) "模板 $id 没有匹配到任何方法"
                else "已下 ${added.size} 个断点：\n" + added.joinToString("\n") {
                    "  #${it.id} ${it.fqcn}.${it.method} @ ${it.dexPc}  ${it.state}"
                }
            }
        },

        Tool(
            "list_breakpoints",
            "列出当前所有断点及其状态与命中次数。",
            schema(),
        ) { dbg, _ ->
            val bps = dbg.breakpoints()
            if (bps.isEmpty()) "尚未设置断点"
            else bps.joinToString("\n") {
                "#${it.id} ${it.fqcn}.${it.method} @ ${it.dexPc}  ${it.state}  命中 ${it.hitCount} 次" +
                    (it.condition?.let { c -> "  条件：$c" } ?: "") +
                    (it.note?.let { n -> "  （$n）" } ?: "")
            }
        },

        Tool(
            "remove_breakpoint",
            "删除一个断点。",
            schema(prop("id", "integer", "断点编号"), required = listOf("id")),
        ) { dbg, a ->
            dbg.removeBreakpoint(a["id"]?.int ?: error("缺少 id"))
            "已删除断点"
        },

        Tool(
            "start_debug",
            "挂起启动目标应用并 attach，然后等待第一次断点命中。返回停下的位置与全部寄存器的值。" +
                "调用前应先 set_breakpoint，否则应用会正常跑起来但不会停下。",
            schema(prop("timeoutMs", "integer", "等待命中的超时毫秒数，默认 60000")),
        ) { dbg, a ->
            val timeout = (a["timeoutMs"]?.long ?: 60_000L).coerceIn(1_000L, 300_000L)
            if (dbg.breakpoints().isEmpty()) {
                return@Tool "还没有设置任何断点。请先用 set_breakpoint，否则应用会正常启动但不会停下来。"
            }
            val st = dbg.actAndWait(timeout) { dbg.start() }
            renderStop(dbg, st, "已启动并命中")
        },

        Tool(
            "step",
            "指令级单步：over 步过（不进入被调方法）、into 步入、out 步出。" +
                "每步之后返回新的位置和寄存器值，变化的寄存器会被标出来。" +
                "count 可以一次走多步，返回途经的 dex_pc 轨迹和最终状态。",
            schema(
                prop("mode", "string", "into / over / out，默认 over"),
                prop("count", "integer", "连续走几步，默认 1，最大 50"),
                prop("timeoutMs", "integer", "每步的超时毫秒数，默认 20000"),
            ),
        ) { dbg, a ->
            val mode = (a["mode"]?.string ?: "over").lowercase()
            require(mode in setOf("into", "over", "out")) { "mode 只能是 into / over / out" }
            val count = (a["count"]?.int ?: 1).coerceIn(1, 50)
            val timeout = (a["timeoutMs"]?.long ?: 20_000L).coerceIn(1_000L, 120_000L)

            val trace = ArrayList<String>()
            var last = dbg.state
            var stopped = false
            repeat(count) {
                if (stopped) return@repeat
                val st = dbg.actAndWait(timeout) { dbg.control(mode) }
                if (st == null) { stopped = true; return@repeat }
                last = st
                st.frames.firstOrNull()?.let {
                    trace += "${it.method}@${it.dexPc}"
                }
            }
            val head = if (trace.size > 1) "轨迹：${trace.joinToString(" → ")}\n\n" else ""
            head + renderStop(dbg, if (stopped) null else last, "单步完成")
        },

        Tool(
            "resume",
            "继续运行。默认会等待下一次断点命中并返回新位置；把 wait 设为 false 则立刻返回。",
            schema(
                prop("wait", "boolean", "是否等待下一次命中，默认 true"),
                prop("timeoutMs", "integer", "等待超时毫秒数，默认 60000"),
            ),
        ) { dbg, a ->
            val wait = a["wait"]?.bool ?: true
            if (!wait) {
                dbg.control("resume")
                return@Tool "已继续运行。"
            }
            val timeout = (a["timeoutMs"]?.long ?: 60_000L).coerceIn(1_000L, 300_000L)
            val st = dbg.actAndWait(timeout) { dbg.control("resume") }
            renderStop(dbg, st, "已继续并再次命中")
        },

        Tool(
            "read_registers",
            "读取当前停留位置的寄存器（类型 + 实际值）。只有在挂起状态下才可用。" +
                "值显示为「此处不可用」「该寄存器被复用…」时表示真的读不出来，不要当成 0 或 null。",
            schema(prop("depth", "integer", "栈帧深度，0 是栈顶，默认 0")),
        ) { dbg, a ->
            val depth = a["depth"]?.int ?: 0
            if (dbg.state.status != "suspended") return@Tool "目标未处于挂起状态，先命中断点或单步。"
            val f = dbg.readFrame(depth) ?: return@Tool "取不到第 $depth 层帧"
            buildString {
                append("${f.fqcn}.${f.method}${f.signature}  dex_pc=${f.dexPc}\n")
                if (!f.hasModel) append("（系统方法，没有静态模型，读不到寄存器）\n")
                f.registers.forEach {
                    append("  ${it.name.padEnd(4)} ${it.type.padEnd(8)} ${it.value}")
                    it.hint?.let { h -> append(" ($h)") }
                    append("\n")
                }
            }
        },

        Tool(
            "write_register",
            "改写当前停留位置某个寄存器的值（二期）。只能改类型推得出的寄存器，" +
                "boolean 写 true/false，char 写单个字符，数值直接写，引用型只能写 null 清空。" +
                "改错 tag 会破坏帧，所以类型未定的寄存器会被拒绝。改完返回该帧的最新寄存器。",
            schema(
                prop("reg", "integer", "寄存器号（vN 的 N；pN 在末尾，可先用 read_registers 看编号）"),
                prop("value", "string", "要写入的值"),
                prop("depth", "integer", "栈帧深度，默认 0（栈顶）"),
                required = listOf("reg", "value"),
            ),
        ) { dbg, a ->
            val reg = a["reg"]?.int ?: error("缺少 reg")
            val f = dbg.writeRegister(a["depth"]?.int ?: 0, reg, a["value"]?.string ?: error("缺少 value"))
            val r = f.registers.firstOrNull { it.reg == reg }
            "已写入。${r?.let { "${it.name} = ${it.value}" } ?: ""}\n当前寄存器：\n" +
                f.registers.joinToString("\n") { "  ${it.name.padEnd(4)} ${it.type.padEnd(8)} ${it.value}" }
        },

        Tool(
            "read_stack",
            "读取当前调用栈。带 [无模型] 的帧是 framework 方法，不在目标 APK 里。",
            schema(),
        ) { dbg, _ ->
            val st = dbg.state
            if (st.status != "suspended") return@Tool "目标未处于挂起状态。"
            st.frames.mapIndexed { i, f ->
                "#$i ${f.fqcn}.${f.method}${f.signature} @ ${f.dexPc}" +
                    if (f.hasModel) "" else "  [无模型]"
            }.joinToString("\n")
        },

        Tool(
            "expand_object",
            "展开一个对象引用的字段（数组则展开元素）。objectId 取自寄存器值里的对象。",
            schema(prop("objectId", "integer", "对象 ID"), required = listOf("objectId")),
        ) { dbg, a ->
            val id = a["objectId"]?.long ?: error("缺少 objectId")
            val n = dbg.expandObject(id) ?: return@Tool "该对象已不可读（线程可能已继续运行）"
            buildString {
                append("${n.label}\n")
                n.fields.forEach { append("  ${it.name.padEnd(16)} ${it.type.padEnd(12)} ${it.value}\n") }
                if (n.truncated) append("（元素较多，仅显示前 ${n.fields.size} 个）\n")
            }
        },

        Tool(
            "stop_debug",
            "结束调试会话，清掉断点并断开连接，让目标应用恢复正常运行。",
            schema(),
        ) { dbg, _ ->
            dbg.control("stop")
            "已结束调试会话。"
        },

        Tool(
            "explain_code",
            "让配置好的大模型讲解一个方法在做什么。会把 smali、jadx 反编译出的 Java " +
                "以及（若正停在该方法上）寄存器的真实值一起作为上下文。仅在需要自然语言概述时使用；" +
                "要精确事实请直接用 disassemble / read_registers。",
            schema(
                prop("class", "string", "类名"),
                prop("method", "string", "方法名"),
                prop("signature", "string", "可选，JVM 方法签名"),
                prop("dexPc", "integer", "可选，重点讲解这条指令"),
                required = listOf("class", "method"),
            ),
            requiresLlm = true,
        ) { dbg, a ->
            val (fqcn, name, sig) = resolveMethod(dbg, a)
            dbg.explain(fqcn, name, sig, a["dexPc"]?.int)
        },

        Tool(
            "suggest_register_names",
            "针对混淆过的方法，结合数据流与调用到的 framework API，为 v0/v1 这类无意义的寄存器" +
                "猜测语义名。结果是推测，不是事实，不要拿它当依据继续推理。",
            schema(
                prop("class", "string", "类名"),
                prop("method", "string", "方法名"),
                prop("signature", "string", "可选，JVM 方法签名"),
                required = listOf("class", "method"),
            ),
            requiresLlm = true,
        ) { dbg, a ->
            val (fqcn, name, sig) = resolveMethod(dbg, a)
            dbg.nameRegisters(fqcn, name, sig)
        },
    )

    private fun available(): List<Tool> {
        val llm = com.smaliscope.config.Settings.llm().enabled
        return TOOLS.filter { llm || !it.requiresLlm }
    }

    // ── 参数解析辅助 ────────────────────────────────────────────────────────

    private fun resolveClass(dbg: DebugFacade, a: Jv): String {
        val raw = a["class"]?.string ?: error("缺少 class")
        return dbg.resolveClass(raw) ?: error("找不到类 $raw（是否还没 load_app？可用 list_classes 查看）")
    }

    private fun resolveMethod(dbg: DebugFacade, a: Jv): Triple<String, String, String> {
        val fqcn = resolveClass(dbg, a)
        val name = a["method"]?.string ?: error("缺少 method")
        val sig = dbg.resolveMethod(fqcn, name, a["signature"]?.string)
            ?: error("找不到方法 $fqcn.$name（可用 list_methods 查看）")
        return Triple(fqcn, name, sig)
    }

    // ── 对外 ────────────────────────────────────────────────────────────────

    fun schemaJson(): String = Json.arr(
        available().map {
            Json.obj(
                "name" to Json.str(it.name),
                "description" to Json.str(it.description),
                "inputSchema" to it.schema,
            )
        }
    )

    fun dispatch(dbg: DebugFacade, name: String, args: Jv): String {
        val tools = available()
        val tool = tools.firstOrNull { it.name == name }
            ?: if (TOOLS.any { it.name == name }) {
                error("工具 $name 需要先配置大模型 API key（smaliscope config llm.apiKey <key>）")
            } else {
                error("未知工具 $name。可用：${tools.joinToString(", ") { it.name }}")
            }
        return tool.run(dbg, args)
    }
}
