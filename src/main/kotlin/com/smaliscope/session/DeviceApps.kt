package com.smaliscope.session

import com.smaliscope.adb.AdbClient
import java.io.File

data class InstalledApp(val pkg: String, val apkPaths: List<String>)

/** 设备上的应用探测与 APK 获取。每次调试都从设备现取，原包不动、不改造、不重装。 */
class DeviceApps(private val adb: AdbClient, private val serial: String) {

    fun listPackages(thirdPartyOnly: Boolean = true): List<String> {
        val flag = if (thirdPartyOnly) "-3" else ""
        return adb.shell(serial, "pm list packages $flag")
            .lineSequence()
            .mapNotNull { it.trim().removePrefix("package:").takeIf(String::isNotBlank) }
            .sorted()
            .toList()
    }

    /** base.apk 及其 split 的设备端路径。 */
    fun apkPathsOf(pkg: String): List<String> =
        adb.shell(serial, "pm path $pkg")
            .lineSequence()
            .mapNotNull { it.trim().removePrefix("package:").takeIf(String::isNotBlank) }
            .toList()

    fun pidOf(pkg: String): Int? =
        adb.shell(serial, "pidof $pkg").trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull()

    /** 一次取回全部进程名→pid，避免为每个包名各发一次 pidof。 */
    fun runningProcesses(): Map<String, Int> =
        adb.shell(serial, "ps -A -o PID,NAME")
            .lineSequence()
            .drop(1)
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 2)
                if (parts.size == 2) parts[1].trim().let { it to (parts[0].toIntOrNull() ?: return@mapNotNull null) }
                else null
            }
            .toMap()

    /** 判断进程是否可被 JDWP 调试（在 adb jdwp 列表里）。 */
    fun isDebuggable(pkg: String): Boolean {
        val pid = pidOf(pkg) ?: return false
        return pid in adb.jdwpPids(serial)
    }

    /** 拉取 APK 到本地缓存目录，已存在且大小一致则复用。 */
    fun pullApks(pkg: String, cacheDir: File): List<File> {
        val paths = apkPathsOf(pkg)
        if (paths.isEmpty()) error("未找到应用 $pkg，请确认包名或先安装该应用")
        val dir = File(cacheDir, pkg).apply { mkdirs() }
        return paths.map { remote ->
            val local = File(dir, remote.substringAfterLast('/'))
            val remoteSize = adb.shell(serial, "stat -c %s $remote").trim().toLongOrNull()
            if (!local.exists() || (remoteSize != null && local.length() != remoteSize)) {
                adb.pull(serial, remote, local)
            }
            local
        }
    }

    /**
     * 探测环境，决定 debuggable 启用路径（设计方案 §2.3）。
     * 只做探测与解释，不擅自改设备状态。
     */
    fun probeEnvironment(): EnvProbe {
        val roDebuggable = adb.shell(serial, "getprop ro.debuggable").trim() == "1"
        val sdk = adb.shell(serial, "getprop ro.build.version.sdk").trim().toIntOrNull() ?: 0
        val isEmulator = serial.startsWith("emulator-") ||
            adb.shell(serial, "getprop ro.kernel.qemu").trim() == "1" ||
            adb.shell(serial, "getprop ro.build.characteristics").contains("emulator")
        val hasSu = adb.shell(serial, "which su").isNotBlank()

        // root 方案与 Zygisk 的判断必须经 su：/data/adb 只有 root 能读，
        // 用普通 shell 去 ls 会一律得到空结果，从而误判成「没装」。
        var rootKind = if (hasSu) "su" else "none"
        var hasZygisk = false
        if (hasSu) {
            val probe = runCatching {
                adb.shell(serial, "su -c 'ls -d /data/adb/magisk /data/adb/ksu /data/adb/ap " +
                    "/data/adb/modules/zygisksu /data/adb/modules/rezygisk 2>/dev/null'")
            }.getOrDefault("")
            rootKind = when {
                probe.contains("/data/adb/magisk") -> "Magisk"
                probe.contains("/data/adb/ksu") -> "KernelSU"
                probe.contains("/data/adb/ap") -> "APatch"
                else -> "su"
            }
            // Magisk 自带 Zygisk（可能被关闭）；KernelSU / APatch 需要 ZygiskNext 之类的模块。
            hasZygisk = probe.contains("zygisksu") || probe.contains("rezygisk") ||
                rootKind == "Magisk"
        }
        return EnvProbe(roDebuggable, sdk, isEmulator, hasSu, rootKind, hasZygisk)
    }
}

data class EnvProbe(
    val roDebuggable: Boolean,
    val sdk: Int,
    val isEmulator: Boolean,
    val hasSu: Boolean,
    /** Magisk / KernelSU / APatch / su（有 su 但认不出方案）/ none。 */
    val rootKind: String = if (hasSu) "su" else "none",
    /** Zygisk API 是否可用——决定了逐应用打可调试标记的方案能不能落地。 */
    val hasZygisk: Boolean = false,
) {
    /**
     * ⚠️ 设计方案假设「非 Play 镜像 ro.debuggable=1 → 所有应用天生可 JDWP 调（P0 零配置）」。
     * **这个前提在现代 Android 上不成立**，已实测证伪，见 docs/p0-path-findings.md：
     * 在 Android 14 的 google_apis userdebug 镜像上，即使 `ro.debuggable=1` 且
     * `ro.force.debuggable=1`，未带 `android:debuggable="true"` 的 release 包依然不出现在
     * `adb jdwp` 里，连 `am set-debug-app -w` 也无法让它可调。Android 16 上同样如此。
     *
     * 所以这里不再按系统属性宣称「所有应用都能调」——真正可靠的判断是
     * 「该应用的进程在不在 adb jdwp 列表里」，那是 [DeviceApps.isDebuggable] 在做的事。
     *
     * 让未改造的第三方应用可调，选定的方案是 root 下用 Zygisk 模块在 fork 时
     * 给目标进程置 DEBUG_ENABLE_JDWP，原包一字不动（见 ROADMAP 第 2 项）。
     * 明确不采用重打包重签名：改签名会让应用自带的签名校验失效、必须卸载重装丢数据，
     * 而且修改的是被研究对象本身。
     */
    val path: String get() = when {
        hasZygisk -> "$rootKind + Zygisk"
        hasSu -> rootKind
        roDebuggable -> "userdebug"
        else -> "user"
    }

    val summary: String get() = buildString {
        append("只有自身带 debuggable 标记的应用可以调试——应用列表里带 ● 的就是。")
        if (roDebuggable) {
            // 只声称实测过的版本。旧 Android 上 ro.debuggable=1 确实曾让所有应用可调，
            // 具体从哪一版开始变的没有实测，不写死。
            append("本机 ro.debuggable=1，但实测 Android 14 / 16 上它已不再让普通 release 包变为可调试。")
        }
        append(
            when {
                hasZygisk -> "本机有 $rootKind 且 Zygisk 可用，" +
                    "装上配套模块后即可给指定应用打上可调试标记（模块尚未实现），原包一字不动。"
                hasSu -> "本机有 $rootKind 但未发现 Zygisk（Magisk 自带；KernelSU / APatch 需装 ZygiskNext）。" +
                    "逐应用打可调试标记要靠它。"
                else -> "要调试未改造的第三方应用需要 root + Zygisk（模块尚未实现）。"
            }
        )
    }
}
