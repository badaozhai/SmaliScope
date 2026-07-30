package com.smaliscope.session

import com.smaliscope.adb.AdbClient
import java.io.File
import java.util.Base64

/**
 * 配套 Zygisk 模块的管理：查状态、装模块、维护目标名单。
 *
 * 模块本身在 `zygisk/`（C++）。它做的事只有一件：在 zygote fork 时给名单里的进程
 * 置上 `DEBUG_ENABLE_JDWP | DEBUG_JAVA_DEBUGGABLE`，从而让未改造的 release 包
 * 也能被 JDWP 调试，而 APK 一字不动——签名、数据、更新链路全部保留。
 * 为什么必须这么做，见 docs/p0-path-findings.md。
 *
 * 所有操作都经 `su`：`/data/adb` 只有 root 能读写，用普通 shell 会静默拿到空结果。
 */
class ZygiskModule(private val adb: AdbClient, private val serial: String) {

    companion object {
        const val MODULE_ID = "smaliscope_debuggable"
        const val TARGETS = "/data/adb/smaliscope/targets"
        private const val MODULE_DIR = "/data/adb/modules/$MODULE_ID"
    }

    data class Status(
        val hasSu: Boolean,
        val rootKind: String,
        val hasZygisk: Boolean,
        val installed: Boolean,
        val enabled: Boolean,
        val version: String?,
        val targets: List<String>,
    ) {
        /** 一句话说明下一步该干什么——这是新手最需要的。 */
        val advice: String get() = when {
            !hasSu -> "设备没有 root，装不了模块。只能调试自身带 debuggable 标记的应用。"
            !hasZygisk -> "有 $rootKind 但没有 Zygisk。Magisk 自带（确认已开启）；" +
                "KernelSU / APatch 需要先装 ZygiskNext 之类的模块。"
            !installed -> "Zygisk 就绪，但还没装 SmaliScope 模块。先 ./zygisk/build.sh，" +
                "再 smaliscope zygisk install zygisk/build/smaliscope-zygisk.zip。"
            !enabled -> "模块已安装但处于禁用状态，请在 root 管理器里启用它并重启。"
            targets.isEmpty() -> "模块已就绪。用 smaliscope zygisk add <包名> 把目标加进名单，" +
                "然后强杀该应用重新打开即可调试。"
            else -> "模块已就绪，名单里有 ${targets.size} 个目标。改完名单要强杀目标应用再打开才生效。"
        }
    }

    // su 的语法按 root 方案而异，交给 AdbClient.suShell 去探测与适配。
    private fun su(cmd: String): String =
        runCatching { adb.suShell(serial, cmd) }.getOrDefault("")

    fun status(): Status {
        val hasSu = adb.shell(serial, "which su").isNotBlank()
        if (!hasSu) return Status(false, "none", false, false, false, null, emptyList())

        val probe = su(
            "ls -d /data/adb/magisk /data/adb/ksu /data/adb/ap " +
                "/data/adb/modules/zygisksu /data/adb/modules/rezygisk 2>/dev/null"
        )
        val rootKind = when {
            probe.contains("/data/adb/magisk") -> "Magisk"
            probe.contains("/data/adb/ksu") -> "KernelSU"
            probe.contains("/data/adb/ap") -> "APatch"
            else -> "su"
        }
        val hasZygisk = probe.contains("zygisksu") || probe.contains("rezygisk") || rootKind == "Magisk"

        val prop = su("cat $MODULE_DIR/module.prop 2>/dev/null")
        val installed = prop.contains("id=$MODULE_ID")
        // Magisk / KernelSU 都用 disable 这个空文件表示「已安装但禁用」
        val enabled = installed && su("ls $MODULE_DIR/disable 2>/dev/null").isBlank()
        val version = prop.lineSequence()
            .firstOrNull { it.startsWith("version=") }?.removePrefix("version=")?.trim()

        return Status(hasSu, rootKind, hasZygisk, installed, enabled, version, readTargets())
    }

    fun readTargets(): List<String> = su("cat $TARGETS 2>/dev/null")
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .toList()

    /**
     * 覆盖写名单。
     *
     * 内容走 base64 再在设备上解码，而不是把包名拼进 shell 命令里——
     * 经 `adb shell su -c '...'` 的多层引号极易被吃掉或被注入。
     */
    private fun writeTargets(lines: List<String>) {
        val body = buildString {
            appendLine("# SmaliScope 目标名单：一行一个进程名（通常就是包名），# 起头为注释。")
            appendLine("# 改完强杀目标应用再打开即生效，不必重启手机。")
            lines.forEach { appendLine(it) }
        }
        val b64 = Base64.getEncoder().encodeToString(body.toByteArray())
        val tmp = "/data/local/tmp/.smaliscope-targets"
        adb.shell(serial, "echo $b64 | base64 -d > $tmp")
        su("mkdir -p /data/adb/smaliscope && cp $tmp $TARGETS && " +
            "chmod 700 /data/adb/smaliscope && chmod 600 $TARGETS")
        adb.shell(serial, "rm -f $tmp")
    }

    /** @return true 表示确实加进去了，false 表示本来就在名单里 */
    fun addTarget(pkg: String): Boolean {
        val cur = readTargets()
        if (pkg in cur) return false
        writeTargets(cur + pkg)
        return true
    }

    fun removeTarget(pkg: String): Boolean {
        val cur = readTargets()
        if (pkg !in cur) return false
        writeTargets(cur - pkg)
        return true
    }

    /** 推送并安装模块 zip。装完必须重启一次 Zygisk 才会加载它。 */
    fun install(zip: File): String {
        require(zip.isFile) { "找不到模块包 ${zip.absolutePath}，先跑 ./zygisk/build.sh" }
        val remote = "/data/local/tmp/${zip.name}"
        adb.push(serial, zip, remote)

        val st = status()
        val cmd = when (st.rootKind) {
            "Magisk" -> "magisk --install-module $remote"
            "KernelSU" -> "ksud module install $remote"
            "APatch" -> "apd module install $remote"
            else -> return "无法判断 root 方案，请手动在 root 管理器里刷入 $remote"
        }
        val out = su(cmd)
        adb.shell(serial, "rm -f $remote")
        return out.ifBlank { "安装命令已执行（$cmd），无输出" }
    }
}
