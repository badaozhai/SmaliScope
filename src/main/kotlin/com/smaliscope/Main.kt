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
  serve [--port 8080]            启动本地 Web 调试工作台（默认）
"""

/** APK 本地缓存目录。 */
val cacheDir: java.io.File = java.io.File(System.getProperty("user.home"), ".smaliscope/apks")

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
        first == "serve" -> cmdServe(argv.drop(1))
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
    val adb = AdbClient()
    val device = adb.pickDevice()
    val apps = com.smaliscope.session.DeviceApps(adb, device.serial)

    println("正在获取 $pkg 的 APK…")
    val apks = apps.pullApks(pkg, cacheDir)
    apks.forEach { println("  ${it.name}  ${it.length() / 1024} KB") }

    val sdk = apps.probeEnvironment().sdk.coerceIn(21, 35)
    val index = com.smaliscope.analysis.ApkIndex(apks, sdk)
    println("已解析 ${index.classCount} 个类")
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

    val fqcn = index.classNames().firstOrNull { it == className || it.endsWith(".$className") }
        ?: run {
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
    val mode = if (args.getOrNull(4)?.lowercase() == "into")
        com.smaliscope.stepping.StepMode.INTO else com.smaliscope.stepping.StepMode.OVER

    val adb = AdbClient()
    val device = adb.pickDevice()
    val apps = com.smaliscope.session.DeviceApps(adb, device.serial)
    val env = apps.probeEnvironment()
    println("设备: ${device.serial}  接入路径: ${env.path}")

    val apks = apps.pullApks(pkg, cacheDir)
    val index = com.smaliscope.analysis.ApkIndex(apks, env.sdk.coerceIn(21, 35))
    val fqcn = index.classNames().firstOrNull { it == className || it.endsWith(".$className") }
        ?: run { System.err.println("未找到类 $className"); kotlin.system.exitProcess(1) }
    val model = index.findMethod(fqcn, methodName)
        ?: run { System.err.println("未找到方法 $fqcn.$methodName"); kotlin.system.exitProcess(1) }
    val entryPc = model.instructions.first().dexPc

    val suspended = java.util.concurrent.LinkedBlockingQueue<com.smaliscope.session.DebugState>()
    val session = com.smaliscope.session.DebugSession(adb, device.serial, pkg, index)
    session.onLog = { println("  · $it") }
    session.onState = { st -> if (st.status == "suspended") suspended.offer(st) }

    Runtime.getRuntime().addShutdownHook(Thread { runCatching { session.close() } })

    session.use { s ->
        s.addBreakpoint(fqcn, methodName, model.signature, entryPc)
        println("断点: $fqcn.$methodName${model.signature} @ dex_pc $entryPc")
        println()
        s.launchSuspended()

        val first = suspended.poll(40, java.util.concurrent.TimeUnit.SECONDS)
        if (first == null) {
            System.err.println("等待断点命中超时")
            kotlin.system.exitProcess(1)
        }
        printStop(first, index, model.signature)

        repeat(steps) {
            s.step(mode)
            val st = suspended.poll(20, java.util.concurrent.TimeUnit.SECONDS)
            if (st == null) {
                println("（单步未在预期时间内落点，停止）")
                return@repeat
            }
            printStop(st, index, model.signature)
        }

        println()
        println("执行轨迹共记录 ${s.timeline.size} 个快照（可用于时间线回放）")
        s.resume()
    }
}

private fun printStop(
    st: com.smaliscope.session.DebugState,
    index: com.smaliscope.analysis.ApkIndex,
    @Suppress("UNUSED_PARAMETER") sig: String,
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
