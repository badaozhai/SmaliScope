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
        return EnvProbe(roDebuggable, sdk, isEmulator, hasSu)
    }
}

data class EnvProbe(
    val roDebuggable: Boolean,
    val sdk: Int,
    val isEmulator: Boolean,
    val hasSu: Boolean,
) {
    /** P0/P1/P2：路径只决定「怎么让进程可调」，不改变「怎么调」。 */
    val path: String get() = when {
        roDebuggable -> "P0"
        hasSu -> "P1"
        else -> "P2"
    }

    val summary: String get() = when {
        roDebuggable -> "设备已全局可调试，所有应用都能直接下断点，无需任何准备。"
        hasSu -> "设备已 root 但未全局开放调试，可由工具自动准备（需重启一次）。"
        else -> "设备既未开放调试也无 root：只有自身带 debuggable 标记的应用可以调试。" +
            "新手建议改用模拟器的非 Play 镜像（Google APIs），那种镜像默认全局可调试。"
    }
}
