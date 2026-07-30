package com.smaliscope

import com.smaliscope.adb.AdbClient
import com.smaliscope.jdwp.JdwpConnection
import com.smaliscope.jdwp.VirtualMachine

private const val USAGE = """
SmaliScope —— 面向新手的 DEX/smali 指令级断点调试器

用法:
  smoke [pid]                    连通冒烟：握手并打印 VM 信息
  apps                           列出设备上的第三方应用与环境探测结果
  dump <包名> [类名] [方法名]     反汇编：列类 / 列方法 / dump 带 dex_pc 的 smali
  debug <包名> <类名> <方法名> [步数] [into|over]
                                 命令行调试：下断点 → 挂起启动 → 命中 → 单步看寄存器变化
  audit <包名> <类名> [方法名]     统计寄存器可读率：逐指令单步走完，量「应读 / 实读」
  serve [--port 8080]            启动本地 Web 调试工作台（默认）
  mcp                            以 MCP server 运行（JSON-RPC over stdio），供 AI agent 驱动调试
  mcp-install                    把自己注册进本机 MCP 客户端（grok / Claude Code）的配置
  config [键 [值]] [--test]       查看 / 修改配置（大模型接口地址与 key 等）
  zygisk <status|install|add|remove|list> [参数]
                                 管理 Zygisk 模块，让未改造的第三方应用可调（需 root）
"""

/** APK 本地缓存目录。 */
val cacheDir: java.io.File = java.io.File(System.getProperty("user.home"), ".smaliscope/apks")

/** 本可执行文件所在的 bin 目录（installDist 布局），供终端把 smaliscope 放进 PATH。 */
fun launcherDir(): String? = runCatching {
    val jar = java.io.File(object {}.javaClass.protectionDomain.codeSource.location.toURI())
    jar.parentFile?.parentFile?.resolve("bin")?.takeIf { it.isDirectory }?.absolutePath
}.getOrNull()

fun main(args: Array<String>) {
    // 兼容 README 的 `./gradlew run --args="5678"`：纯数字参数即冒烟测试的目标 pid。
    val argv = args.toList()
    val first = argv.firstOrNull()
    when {
        first == null -> cmdServe(emptyList())
        first.toIntOrNull() != null -> cmdSmoke(argv)
        first == "smoke" -> cmdSmoke(argv.drop(1))
        first == "apps" -> cmdApps()
        first == "dump" -> cmdDump(argv.drop(1))
        first == "debug" -> cmdDebug(argv.drop(1))
        first == "audit" -> cmdAudit(argv.drop(1))
        first == "serve" -> cmdServe(argv.drop(1))
        first == "mcp" -> cmdMcp()
        first == "mcp-install" -> cmdMcpInstall()
        first == "config" -> cmdConfig(argv.drop(1))
        first == "zygisk" -> cmdZygisk(argv.drop(1))
        first == "-h" || first == "--help" -> println(USAGE.trim())
        else -> {
            System.err.println("未知命令: $first")
            println(USAGE.trim())
            kotlin.system.exitProcess(2)
        }
    }
}

private fun cmdApps() {
    val adb = AdbClient()
    val device = adb.pickDevice()
    val apps = com.smaliscope.session.DeviceApps(adb, device.serial)
    val env = apps.probeEnvironment()

    println("设备: ${device.serial}（Android SDK ${env.sdk}${if (env.isEmulator) "，模拟器" else ""}）")
    println("接入路径: ${env.path} —— ${env.summary}")
    println()

    val debuggablePids = adb.jdwpPids(device.serial).toSet()
    println("第三方应用:")
    for (pkg in apps.listPackages()) {
        val pid = apps.pidOf(pkg)
        val mark = when {
            pid != null && pid in debuggablePids -> "● 可调试 (pid $pid)"
            pid != null -> "○ 运行中，不可调试"
            else -> "  未运行"
        }
        println("  %-45s %s".format(pkg, mark))
    }
}

/** M2 验收：把设备上的 APK 拉下来，dump 出带 dex_pc 偏移的 smali。 */
private fun cmdDump(args: List<String>) {
    val pkg = args.getOrNull(0) ?: run {
        System.err.println("用法: dump <包名> [类名] [方法名]")
        kotlin.system.exitProcess(2)
    }
    val dbg = com.smaliscope.session.Debugger(cacheDir)
    dbg.onLog = { println("  · $it") }
    dbg.loadApp(pkg)
    val index = dbg.apk ?: run {
        System.err.println("APK 解析失败")
        kotlin.system.exitProcess(1)
    }
    println()

    val className = args.getOrNull(1)
    if (className == null) {
        println("应用自身的类:")
        index.appClassNames(pkg.substringBeforeLast('.').takeIf { it.isNotBlank() })
            .ifEmpty { index.appClassNames() }
            .forEach { println("  $it") }
        println()
        println("继续: dump $pkg <类名>")
        return
    }

    val fqcn = dbg.resolveClass(className) ?: run {
        System.err.println("未找到类 $className")
        kotlin.system.exitProcess(1)
    }

    val methodName = args.getOrNull(2)
    if (methodName == null) {
        println("$fqcn 的方法:")
        index.concreteMethodsOf(fqcn).forEach { m ->
            val model = index.model(m)
            println("  %-30s %-40s %d 条指令".format(m.name, m.signature, model?.instructions?.size ?: 0))
        }
        println()
        println("继续: dump $pkg $className <方法名>")
        return
    }

    val model = index.findMethod(fqcn, methodName) ?: run {
        System.err.println("未找到方法 $fqcn.$methodName")
        kotlin.system.exitProcess(1)
    }

    println("── $model ──")
    println("参数寄存器: ${model.paramRegisterCount} / 总寄存器: ${model.registerCount}")
    model.analysisError?.let { println("⚠ 类型推导未完成: $it") }
    println()
    println("%-8s %-46s %-16s %s".format("dex_pc", "smali", "读→写", "后继"))
    println("─".repeat(100))
    for (ins in model.instructions) {
        val flow = buildString {
            if (ins.reads.isNotEmpty()) append(ins.reads.joinToString(",") { model.regName(it) })
            append(" → ")
            if (ins.writes.isNotEmpty()) append(ins.writes.joinToString(",") { model.regName(it) })
        }.trim()
        println(
            "%-8d %-46s %-16s %s".format(
                ins.dexPc, ins.text, flow, ins.successors.joinToString(",")
            )
        )
    }

    println()
    println("基本块 (CFG 节点 ${model.basicBlocks.size} 个):")
    model.basicBlocks.forEach { b ->
        println("  #${b.id}  pc ${b.startPc}..${b.endPc}  → ${b.successors.joinToString(",").ifEmpty { "（出口）" }}")
    }

    println()
    println("入口处寄存器类型推导:")
    val first = model.instructions.firstOrNull()
    if (first != null) {
        model.registerKindsAt(first.dexPc).forEachIndexed { reg, kind ->
            val hint = model.paramHint(reg)?.let { " ($it)" } ?: ""
            println("  %-5s %-12s tag=%s%s".format(
                model.regName(reg), kind.cn,
                kind.jdwpTag?.let { "'${it.toChar()}'" } ?: "—", hint))
        }
    }
}

/** M1 验收：连上一个可调试进程的 JDWP，握手，打印 VM 版本。 */
private fun cmdSmoke(args: List<String>) {
    val wantPid = args.firstOrNull()?.toIntOrNull()
    val adb = AdbClient()

    val device = adb.pickDevice()
    println("设备: ${device.serial}")

    val pids = adb.jdwpPids(device.serial)
    if (pids.isEmpty()) {
        System.err.println(
            """
            未发现可调试进程。

            模拟器上 ro.debuggable=${adb.shell(device.serial, "getprop ro.debuggable").trim()}，
            若为 0 则只有自身带 android:debuggable="true" 的应用可被调试。
            可以先装上项目自带的测试应用：
              ./testapp/build.sh && adb install -r testapp/build/smaliscope-test.apk
            然后在模拟器里打开它。
            """.trimIndent()
        )
        kotlin.system.exitProcess(1)
    }
    println("可调试进程 pid: $pids")

    val pid = wantPid ?: pids.last()
    if (wantPid != null && wantPid !in pids) {
        System.err.println("pid $wantPid 不在可调试列表中")
        kotlin.system.exitProcess(1)
    }

    val port = adb.forwardJdwp(device.serial, pid)
    println("已转发 tcp:$port -> jdwp:$pid，连接中…")

    try {
        JdwpConnection.attach(port = port).use { conn ->
            val vm = VirtualMachine(conn)
            val v = vm.version()
            val ids = conn.idSizes
            println()
            println("── 连接成功 ──")
            println("描述      : ${v.description.lineSequence().first()}")
            println("JDWP 版本 : ${v.jdwpMajor}.${v.jdwpMinor}")
            println("VM        : ${v.vmName}  ${v.vmVersion}")
            println(
                "ID 宽度   : field=${ids.fieldId} method=${ids.methodId} " +
                    "object=${ids.objectId} refType=${ids.refTypeId} frame=${ids.frameId}"
            )
            println("线程数    : ${vm.allThreads().size}")
        }
    } finally {
        adb.removeForward(device.serial, port)
    }
}

/**
 * M3~M6 的命令行验收：下断点 → 挂起启动 → 命中 → 指令级单步，每步打印变化的寄存器。
 * Web 工作台把同样的能力做成可视化，这里是同一套内核的纯文本视图。
 */
private fun cmdDebug(args: List<String>) {
    if (args.size < 3) {
        System.err.println("用法: debug <包名> <类名> <方法名> [步数] [into|over]")
        kotlin.system.exitProcess(2)
    }
    val pkg = args[0]
    val className = args[1]
    val methodName = args[2]
    val steps = args.getOrNull(3)?.toIntOrNull() ?: 14
    val mode = if (args.getOrNull(4)?.lowercase() == "into") "into" else "over"

    val dbg = com.smaliscope.session.Debugger(cacheDir)
    dbg.onLog = { println("  · $it") }
    Runtime.getRuntime().addShutdownHook(Thread { runCatching { dbg.close() } })

    dbg.use {
        val b = dbg.bootstrap()
        if (!b.ok) { System.err.println(b.message); kotlin.system.exitProcess(1) }
        println("设备: ${b.serial}  接入路径: ${b.env?.path}")
        dbg.loadApp(pkg)

        val index = dbg.apk!!
        val fqcn = dbg.resolveClass(className)
            ?: run { System.err.println("未找到类 $className"); kotlin.system.exitProcess(1) }
        val sig = dbg.resolveMethod(fqcn, methodName, null)
            ?: run { System.err.println("未找到方法 $fqcn.$methodName"); kotlin.system.exitProcess(1) }
        val entryPc = index.model(fqcn, methodName, sig)!!.instructions.first().dexPc

        dbg.addBreakpoint(fqcn, methodName, sig, entryPc)
        println("断点: $fqcn.$methodName$sig @ dex_pc $entryPc")
        println()

        val first = dbg.actAndWait(40_000) { dbg.start() }
        if (first == null) {
            System.err.println("等待断点命中超时")
            kotlin.system.exitProcess(1)
        }
        printStop(first, index)

        repeat(steps) {
            val st = dbg.actAndWait(20_000) { dbg.control(mode) }
            if (st == null) {
                println("（单步未在预期时间内落点，停止）")
                return@repeat
            }
            printStop(st, index)
        }

        println()
        println("执行轨迹共记录 ${dbg.timeline().size} 个快照（可用于时间线回放）")
        dbg.control("resume")
    }
}

private fun printStop(
    st: com.smaliscope.session.DebugState,
    index: com.smaliscope.analysis.ApkIndex,
) {
    val f = st.frames.firstOrNull() ?: run { println("（无帧信息）"); return }
    val model = index.model(f.fqcn, f.method, f.signature)
    val insn = model?.insnAt(f.dexPc)
    val short = f.fqcn.substringAfterLast('.')

    println("▸ ${st.reason}  $short.${f.method}  dex_pc=${f.dexPc}  栈深=${st.frames.size}")
    if (insn != null) {
        println("    ${insn.text}")
        com.smaliscope.dict.SmaliDict.describe(insn.opcodeName)?.let { println("    ↳ $it") }
    }
    val shown = f.registers.filter { it.readable || it.changed }
    if (shown.isEmpty()) {
        println("    （该位置没有已初始化的寄存器）")
    } else {
        shown.forEach { r ->
            val mark = if (r.changed) " ←变化" else ""
            val hint = r.hint?.let { " ($it)" } ?: ""
            println("    %-5s %-10s %s%s%s".format(r.name, r.type, r.value, hint, mark))
        }
    }
    println()
}

/**
 * 量清楚真实 APK 上的寄存器可读率（ROADMAP 第 0 项）。
 *
 * ART 校验读寄存器的 tag 用的是 dex 调试信息里「声明」的类型，
 * 所以在缺少调试信息或经过混淆的包上，寄存器面板还能剩下多少内容是个未知数——
 * 而寄存器面板是本项目的头号卖点。这个命令用实测数字回答它。
 */
private fun cmdAudit(args: List<String>) {
    if (args.size < 2) {
        System.err.println("用法: audit <包名> <类名> [方法名]")
        kotlin.system.exitProcess(2)
    }
    val pkg = args[0]
    val className = args[1]
    val onlyMethod = args.getOrNull(2)

    val dbg = com.smaliscope.session.Debugger(cacheDir)
    dbg.onLog = { println("  · $it") }
    Runtime.getRuntime().addShutdownHook(Thread { runCatching { dbg.close() } })

    dbg.use {
        val b = dbg.bootstrap()
        if (!b.ok) { System.err.println(b.message); kotlin.system.exitProcess(1) }
        println("设备：${b.serial}")
        dbg.loadApp(pkg)

        val fqcn = dbg.resolveClass(className)
            ?: run { System.err.println("未找到类 $className"); kotlin.system.exitProcess(1) }
        val targets = dbg.apk!!.concreteMethodsOf(fqcn)
            .filter { onlyMethod == null || it.name == onlyMethod }
            // 构造函数在 <clinit>/字段初始化里被调用，走位不稳定，默认跳过以免拖长审计。
            .filter { onlyMethod != null || !it.name.startsWith("<") }
        if (targets.isEmpty()) {
            System.err.println("没有可审计的方法")
            kotlin.system.exitProcess(1)
        }
        println("待审计方法：${targets.joinToString { it.name + it.signature }}")
        println()

        val results = com.smaliscope.session.Auditor(dbg)
            .audit(targets, onProgress = { println("  · $it") })

        println()
        println(com.smaliscope.session.Auditor.report("$fqcn（$pkg）", results))
    }
}

/**
 * 以 MCP server 运行。stdout 是协议通道，这里不能打印任何东西——
 * 所有面向人的输出都走 stderr。
 */
private fun cmdMcp() {
    // 本机已有工作台在跑就接上去，和用户界面共用同一个调试会话——
    // 这样 agent 下的断点会点亮界面的断点面板，读寄存器读到的就是用户眼前那一帧。
    // 否则各开一个 Debugger，是两个互不知情的平行调试器。
    val remote = com.smaliscope.session.RemoteDebugger.discover()
    if (remote != null) {
        System.err.println("SmaliScope MCP server 已就绪（stdio）——已接入正在运行的工作台，与界面共用同一个调试会话")
        com.smaliscope.mcp.McpServer(remote).serve(System.`in`, System.out)
        return
    }
    val dbg = com.smaliscope.session.Debugger(cacheDir)
    Runtime.getRuntime().addShutdownHook(Thread { runCatching { dbg.close() } })
    System.err.println("SmaliScope MCP server 已就绪（stdio）——未发现工作台，使用独立调试会话")
    com.smaliscope.mcp.McpServer(dbg).serve(System.`in`, System.out)
}

/**
 * 查看 / 修改配置。大模型接口是可选功能：没配 key 时，
 * Web 界面上不出现按钮、MCP 里也不注册相关工具。
 */
private fun cmdConfig(args: List<String>) {
    val S = com.smaliscope.config.Settings

    if (args.firstOrNull() == "--test") {
        val llm = S.llm()
        if (!llm.enabled) {
            System.err.println("尚未配置 llm.apiKey，无法测试")
            kotlin.system.exitProcess(1)
        }
        println("正在连接 ${llm.chatEndpoint} （模型 ${llm.model}）…")
        try {
            println("✅ 连通正常，返回：${com.smaliscope.explain.LlmClient().ping()}")
        } catch (t: Throwable) {
            System.err.println("❌ ${t.message}")
            kotlin.system.exitProcess(1)
        }
        return
    }

    if (args.isNotEmpty()) {
        val key = args[0]
        if (key !in S.knownKeys) {
            System.err.println("未知配置项 $key，可用：${S.knownKeys.joinToString(", ")}")
            kotlin.system.exitProcess(2)
        }
        if (args.size < 2) {
            System.err.println("用法: config $key <值>（值留空用 '' 表示清除）")
            kotlin.system.exitProcess(2)
        }
        S.set(key, args[1])
        println("已写入 ${S.file}")
    }

    val llm = S.llm()
    println()
    println("大模型接口（可选，用于「解释这段代码」与「猜寄存器语义名」）")
    println("  llm.baseUrl : ${llm.baseUrl}")
    println("  llm.model   : ${llm.model}")
    println("  llm.apiKey  : ${llm.maskedKey}")
    println("  实际请求地址 : ${llm.chatEndpoint}")
    println("  状态        : ${if (llm.enabled) "已启用" else "未启用（没有 key 时该功能整体隐藏）"}")
    println()
    println("配置文件：${S.file}")
    println("也可用环境变量覆盖：SMALISCOPE_LLM_BASE_URL / SMALISCOPE_LLM_API_KEY / SMALISCOPE_LLM_MODEL")
    println()
    println("⚠ 调用会把 smali、反编译出的 Java 和运行时寄存器值发送到上面配置的地址。")
    println("  你调试的往往是别人的 APK，请确认该地址可信后再启用。")
    println()
    println("设置示例：")
    println("  smaliscope config llm.apiKey  <你的key>")
    println("  smaliscope config llm.baseUrl https://api.x.ai      # 换成 xAI 官方接口")
    println("  smaliscope config --test                            # 测试连通性")
    println()
    println("让 AI agent 驱动调试器（与上面的接口配置无关）：smaliscope mcp-install")
}

/**
 * 管理 Zygisk 模块：让未改造的第三方 release 包也能被调试（需 root）。
 * 为什么必须走这条路见 docs/p0-path-findings.md。
 */
private fun cmdZygisk(args: List<String>) {
    val adb = AdbClient()
    val device = adb.pickDevice()
    val z = com.smaliscope.session.ZygiskModule(adb, device.serial)
    val sub = args.firstOrNull() ?: "status"

    when (sub) {
        "status" -> {
            val s = z.status()
            println("设备: ${device.serial}")
            println("  root      : ${if (s.hasSu) s.rootKind else "无"}")
            println("  Zygisk    : ${if (s.hasZygisk) "可用" else "不可用"}")
            println("  模块      : ${if (!s.installed) "未安装" else if (s.enabled) "已安装 ${s.version ?: ""}" else "已安装但被禁用"}")
            println("  目标名单  : ${s.targets.ifEmpty { listOf("（空）") }.joinToString("、")}")
            println()
            println("→ ${s.advice}")
        }
        "install" -> {
            val zip = args.getOrNull(1)?.let { java.io.File(it) }
                ?: java.io.File("zygisk/build/smaliscope-zygisk.zip")
            println("正在安装 ${zip.path} …")
            println(z.install(zip))
            println()
            println("装完需要重启一次设备，Zygisk 才会加载模块。之后：")
            println("  smaliscope zygisk add <包名>")
        }
        "add" -> {
            val pkg = args.getOrNull(1) ?: run {
                System.err.println("用法: zygisk add <包名>"); kotlin.system.exitProcess(2)
            }
            if (z.addTarget(pkg)) println("已把 $pkg 加入名单。") else println("$pkg 本来就在名单里。")
            println("强杀并重新打开该应用即可生效：adb shell am force-stop $pkg")
        }
        "remove" -> {
            val pkg = args.getOrNull(1) ?: run {
                System.err.println("用法: zygisk remove <包名>"); kotlin.system.exitProcess(2)
            }
            if (z.removeTarget(pkg)) println("已从名单移除 $pkg。") else println("$pkg 不在名单里。")
        }
        "list" -> {
            val t = z.readTargets()
            println(if (t.isEmpty()) "名单为空。" else t.joinToString("\n"))
        }
        else -> {
            System.err.println("未知子命令 $sub，可用: status / install / add / remove / list")
            kotlin.system.exitProcess(2)
        }
    }
}

/** 本可执行文件的绝对路径，写进 MCP 客户端配置用。 */
private fun launcherPath(): java.io.File? {
    val jar = runCatching {
        java.io.File(
            object {}.javaClass.protectionDomain.codeSource.location.toURI()
        )
    }.getOrNull() ?: return null
    // installDist 的布局是 <root>/lib/*.jar 与 <root>/bin/smaliscope
    val bin = jar.parentFile?.parentFile?.resolve("bin/smaliscope")
    return bin?.takeIf { it.canExecute() }
}

/**
 * 把 SmaliScope 注册进本机的 MCP 客户端。
 *
 * 做的是「注册」而不是「打包」：MCP 客户端（grok / Claude Code / Cursor）各自独立安装、
 * 各自升级，把它们的二进制塞进我们的发行包只会带来体积、授权与版本三重负担，
 * 而收益——「装完就能用 agent 驱动调试」——注册同样能拿到。
 */
private fun cmdMcpInstall() {
    val bin = launcherPath() ?: run {
        System.err.println(
            "无法定位可执行文件路径。请先 ./gradlew installDist，" +
                "然后用 build/install/smaliscope/bin/smaliscope mcp-install 运行。"
        )
        kotlin.system.exitProcess(1)
    }
    println("SmaliScope 可执行文件：$bin")
    println()

    // ── grok-build：~/.grok/config.toml 的 [mcp_servers.<name>] ──
    val grokConfig = java.io.File(System.getProperty("user.home"), ".grok/config.toml")
    val section = """
        [mcp_servers.smaliscope]
        command = "${bin.absolutePath}"
        args = ["mcp"]
        enabled = true
        startup_timeout_sec = 30
        # start_debug 要重启应用并等断点命中，给足时间
        tool_timeouts = { start_debug = 300, step = 180, resume = 300 }
    """.trimIndent()

    if (grokConfig.exists() || java.io.File(System.getProperty("user.home"), ".grok").isDirectory) {
        grokConfig.parentFile.mkdirs()
        val old = if (grokConfig.exists()) grokConfig.readText() else ""
        val updated = replaceTomlSection(old, "mcp_servers.smaliscope", section)
        grokConfig.writeText(updated)
        println("✅ 已写入 grok 配置：$grokConfig")
    } else {
        println("未发现 ~/.grok，跳过 grok 注册。装好 grok 后重跑本命令，或手动把下面这段加进 ~/.grok/config.toml：")
        println()
        println(section.prependIndent("    "))
    }
    println()

    // ── Claude Code：交给它自己的 CLI，避免我们去猜它的配置文件格式 ──
    println("Claude Code / Cursor 等其它 MCP 客户端，用各自的注册命令即可，例如：")
    println()
    println("    claude mcp add smaliscope -- ${bin.absolutePath} mcp")
    println()
    println("注册完成后，在 agent 里让它调 list_apps 就能开始。")
}

/**
 * 替换 TOML 里的一个 section；不存在就追加。
 * 只按行改，不解析整份 TOML——用户配置里可能有我们不认识的内容，全量重写风险更大。
 *
 * ⚠️ 边界要把**子表**算进本段：`[mcp_servers.x]` 的下一段可能是
 * `[mcp_servers.x.tool_timeouts]`，那是它的子表。早先的实现在任何 `[` 处就停，
 * 于是替换 `[mcp_servers.x]` 时把子表留在后面、结构错乱，实测把用户的 MCP 配置弄丢过。
 * 现在只在遇到「不属于本段的段头」时才结束。
 */
internal fun replaceTomlSection(original: String, sectionName: String, replacement: String): String {
    val header = "[$sectionName]"
    val childPrefix = "[$sectionName."          // 子表，如 [mcp_servers.x.tool_timeouts]
    val lines = original.lines()
    val start = lines.indexOfFirst { it.trim() == header }
    if (start < 0) {
        val sep = if (original.isBlank()) "" else if (original.endsWith("\n")) "\n" else "\n\n"
        return original + sep + replacement + "\n"
    }
    var end = lines.size
    for (i in start + 1 until lines.size) {
        val t = lines[i].trimStart()
        // 段头，且不是本段的子表 → 本段到此为止
        if (t.startsWith("[") && !t.startsWith(childPrefix)) { end = i; break }
    }
    return (lines.subList(0, start) + replacement.lines() + lines.subList(end, lines.size))
        .joinToString("\n")
}

private fun cmdServe(args: List<String>) {
    var port = 8080
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--port" -> port = args.getOrNull(++i)?.toIntOrNull()
                ?: error("--port 需要一个端口号")
            else -> error("未知参数: ${args[i]}")
        }
        i++
    }
    com.smaliscope.server.startWorkbench(port)
}
