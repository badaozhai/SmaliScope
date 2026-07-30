package com.smaliscope.adb

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * 直连 adb server（默认 127.0.0.1:5037）走二进制协议，不用 Runtime.exec 解析文本输出。
 *
 * 请求格式：4 位十六进制长度 + ASCII 载荷；响应先 4 字节 "OKAY"/"FAIL"。
 * host: 开头的请求由 server 处理；选中 transport 之后，同一条 socket 上的后续请求发往设备。
 */
class AdbClient(
    private val host: String = "127.0.0.1",
    private val port: Int = 5037,
) {

    data class Device(val serial: String, val state: String) {
        val isOnline: Boolean get() = state == "device"
        val isEmulator: Boolean get() = serial.startsWith("emulator-")
    }

    // 只尝试拉起 adb server 一次：失败通常意味着环境缺东西，反复 fork 进程没有意义。
    private val serverStartAttempted = java.util.concurrent.atomic.AtomicBoolean(false)

    // 不要写成 Socket().apply { connect(InetSocketAddress(host, port), …) }：
    // apply 块里的 port 会解析到 Socket.getPort()（未连接时为 0），而不是本类的字段。
    private fun open(timeoutMs: Int = 10_000): Socket {
        try {
            return connect(timeoutMs)
        } catch (e: IOException) {
            // 打包成桌面应用双击运行时，adb server 往往根本没起来。
            // 这里主动拉一次再重试，而不是把「连不上 127.0.0.1:5037」这种话丢给用户。
            if (!startServerOnce()) throw noServerError(e)
            return try {
                connect(timeoutMs)
            } catch (e2: IOException) {
                throw noServerError(e2)
            }
        }
    }

    private fun connect(timeoutMs: Int): Socket {
        val sock = Socket()
        sock.connect(InetSocketAddress(host, port), timeoutMs)
        sock.tcpNoDelay = true
        return sock
    }

    private fun noServerError(cause: IOException) = IOException(
        "连不上 adb（$host:$port）。请确认已安装 Android platform-tools 并执行过 " +
            "`adb start-server`；若用的是模拟器，先把模拟器启动起来。",
        cause,
    )

    private fun startServerOnce(): Boolean {
        if (!serverStartAttempted.compareAndSet(false, true)) return false
        val adb = findAdbBinary() ?: return false
        return runCatching {
            val p = ProcessBuilder(adb.absolutePath, "start-server")
                .redirectErrorStream(true).start()
            p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)
            // adb server 起来后要一小会儿才开始 listen
            Thread.sleep(600)
            true
        }.getOrDefault(false)
    }

    /** 在常见位置找 adb。刻意不做「自动下载 platform-tools」——那要联网，留给使用者决定。 */
    private fun findAdbBinary(): java.io.File? {
        val name = if (System.getProperty("os.name").lowercase().contains("win")) "adb.exe" else "adb"
        val candidates = ArrayList<java.io.File>()

        System.getenv("ANDROID_SDK_ROOT")?.let { candidates += java.io.File(it, "platform-tools/$name") }
        System.getenv("ANDROID_HOME")?.let { candidates += java.io.File(it, "platform-tools/$name") }
        System.getenv("PATH")?.split(java.io.File.pathSeparator)?.forEach {
            candidates += java.io.File(it, name)
        }
        val home = System.getProperty("user.home")
        candidates += listOf(
            "$home/Library/Android/sdk/platform-tools/$name",   // macOS 默认
            "$home/Android/Sdk/platform-tools/$name",           // Linux 默认
            "$home/AppData/Local/Android/Sdk/platform-tools/$name", // Windows 默认
            "/usr/local/bin/$name",
            "/opt/homebrew/bin/$name",
        ).map { java.io.File(it) }

        return candidates.firstOrNull { it.isFile && it.canExecute() }
    }

    private fun request(out: OutputStream, payload: String) {
        val body = payload.toByteArray(Charsets.UTF_8)
        out.write(String.format("%04x", body.size).toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(buf, read, n - read)
            if (r < 0) throw IOException("adb 连接提前结束（已读 $read/$n 字节）")
            read += r
        }
        return buf
    }

    /** 读 OKAY/FAIL；FAIL 时把错误信息读出来抛出。 */
    private fun expectOkay(input: InputStream, what: String) {
        val status = String(readExactly(input, 4), Charsets.US_ASCII)
        when (status) {
            "OKAY" -> return
            "FAIL" -> {
                val len = String(readExactly(input, 4), Charsets.US_ASCII).toInt(16)
                val msg = String(readExactly(input, len), Charsets.UTF_8)
                throw IOException("adb 拒绝「$what」: $msg")
            }
            else -> throw IOException("adb 响应异常「$what」: $status")
        }
    }

    /** 读 4 位十六进制长度前缀的数据块。 */
    private fun readBlock(input: InputStream): String {
        val len = String(readExactly(input, 4), Charsets.US_ASCII).toInt(16)
        return String(readExactly(input, len), Charsets.UTF_8)
    }

    /** adb server 版本，用来确认 server 在跑。 */
    fun serverVersion(): Int = open().use { sock ->
        request(sock.getOutputStream(), "host:version")
        expectOkay(sock.getInputStream(), "host:version")
        readBlock(sock.getInputStream()).trim().toInt(16)
    }

    fun devices(): List<Device> = open().use { sock ->
        request(sock.getOutputStream(), "host:devices")
        expectOkay(sock.getInputStream(), "host:devices")
        readBlock(sock.getInputStream()).lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split('\t', limit = 2)
                if (parts.size == 2) Device(parts[0].trim(), parts[1].trim()) else null
            }
            .toList()
    }

    /**
     * 选设备：显式指定 → `ANDROID_SERIAL`（adb 的惯例）→ 只有一台就用它 →
     * 多台时优先模拟器。
     *
     * 「插着手机又开着模拟器」很常见，早先无条件优先模拟器会让用户怎么试都连不到手机，
     * 而且看不出原因。
     */
    fun pickDevice(preferredSerial: String? = null): Device {
        val list = devices().filter { it.isOnline }
        if (list.isEmpty()) throw IOException("未发现在线设备，请先启动模拟器或连接手机后重试")
        val want = preferredSerial ?: System.getenv("ANDROID_SERIAL")?.takeIf { it.isNotBlank() }
        want?.let { w ->
            return list.firstOrNull { it.serial == w }
                ?: throw IOException("未找到设备 $w，当前在线: ${list.joinToString { it.serial }}")
        }
        return list.singleOrNull() ?: list.firstOrNull { it.isEmulator } ?: list.first()
    }

    private fun transport(sock: Socket, serial: String) {
        request(sock.getOutputStream(), "host:transport:$serial")
        expectOkay(sock.getInputStream(), "transport:$serial")
    }

    /** 在设备上执行 shell 命令，返回合并后的输出。 */
    fun shell(serial: String, command: String, timeoutMs: Int = 30_000): String =
        open().use { sock ->
            sock.soTimeout = timeoutMs
            transport(sock, serial)
            request(sock.getOutputStream(), "shell:$command")
            expectOkay(sock.getInputStream(), "shell")
            val buf = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            try {
                while (true) {
                    val n = sock.getInputStream().read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                }
            } catch (_: SocketTimeoutException) {
                // shell 命令有输出但不结束时按已读内容返回
            }
            buf.toString("UTF-8")
        }

    /**
     * 列出可调试进程的 pid。
     *
     * `jdwp` 是一条长连接命令：server 会持续推送 pid 列表且不主动关闭，
     * 因此按一个短窗口收集后关闭 socket。
     */
    fun jdwpPids(serial: String, windowMs: Int = 1200): List<Int> = open().use { sock ->
        sock.soTimeout = windowMs
        transport(sock, serial)
        request(sock.getOutputStream(), "jdwp")
        expectOkay(sock.getInputStream(), "jdwp")
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        try {
            while (true) {
                val n = sock.getInputStream().read(chunk)
                if (n < 0) break
                buf.write(chunk, 0, n)
            }
        } catch (_: SocketTimeoutException) {
            // 预期：窗口到点就停
        }
        buf.toString("UTF-8").lineSequence()
            .mapNotNull { it.trim().toIntOrNull() }
            .distinct()
            .sorted()
            .toList()
    }

    /** 建立端口转发 tcp:local → jdwp:pid，返回本地端口。 */
    fun forwardJdwp(serial: String, pid: Int, localPort: Int = freePort()): Int {
        open().use { sock ->
            request(sock.getOutputStream(), "host-serial:$serial:forward:tcp:$localPort;jdwp:$pid")
            val input = sock.getInputStream()
            expectOkay(input, "forward tcp:$localPort→jdwp:$pid")
            // forward 会回两个 OKAY：第一个是命令被接受，第二个是转发建立成功。
            runCatching { expectOkay(input, "forward 确认") }
        }
        return localPort
    }

    fun removeForward(serial: String, localPort: Int) {
        runCatching {
            open().use { sock ->
                request(sock.getOutputStream(), "host-serial:$serial:killforward:tcp:$localPort")
                runCatching { expectOkay(sock.getInputStream(), "killforward") }
            }
        }
    }

    /** 从设备拉文件到本地。走 sync 服务的 RECV 协议。 */
    fun pull(serial: String, remotePath: String, localFile: java.io.File) {
        open(30_000).use { sock ->
            sock.soTimeout = 120_000
            transport(sock, serial)
            request(sock.getOutputStream(), "sync:")
            expectOkay(sock.getInputStream(), "sync")

            val out = sock.getOutputStream()
            val input = sock.getInputStream()
            val pathBytes = remotePath.toByteArray(Charsets.UTF_8)
            out.write("RECV".toByteArray(Charsets.US_ASCII))
            out.write(le32(pathBytes.size))
            out.write(pathBytes)
            out.flush()

            localFile.parentFile?.mkdirs()
            localFile.outputStream().buffered().use { fileOut ->
                while (true) {
                    val id = String(readExactly(input, 4), Charsets.US_ASCII)
                    when (id) {
                        "DATA" -> {
                            val n = readLe32(readExactly(input, 4))
                            fileOut.write(readExactly(input, n))
                        }
                        "DONE" -> {
                            readExactly(input, 4)
                            return@use
                        }
                        "FAIL" -> {
                            val n = readLe32(readExactly(input, 4))
                            throw IOException("拉取 $remotePath 失败: ${String(readExactly(input, n))}")
                        }
                        else -> throw IOException("sync 协议异常: $id")
                    }
                }
            }
        }
    }

    // su 的调用形式按设备而异，探测结果按设备缓存（每次都探会让每条命令都多一次往返）。
    private val suForm = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * 以 root 执行一条命令。
     *
     * `su` 的语法不统一，必须探测而不能假定：
     *  - Magisk / KernelSU / APatch 的 su 接受 `su -c '<cmd>'`；
     *  - AOSP（userdebug 镜像自带）的 su 是 `su [用户] [命令]`，喂 `-c` 会报
     *    "invalid uid/gid '-c'"。
     * `su root sh -c '<cmd>'` 两边都认，所以作为兜底形式。
     *
     * @return 命令输出；没有 root 或都失败时返回空串（调用方按「探测不到」处理即可）。
     */
    fun suShell(serial: String, cmd: String): String {
        val quoted = cmd.replace("'", "'\\''")
        val form = suForm.getOrPut(serial) {
            when {
                shell(serial, "su -c 'id -u'").trim() == "0" -> "-c"
                shell(serial, "su root sh -c 'id -u'").trim() == "0" -> "root"
                else -> "none"
            }
        }
        return when (form) {
            "-c" -> shell(serial, "su -c '$quoted'")
            "root" -> shell(serial, "su root sh -c '$quoted'")
            else -> ""
        }
    }

    /** 用 sync 协议把本地文件推到设备。mode 是目标文件权限（八进制，如 0o755）。 */
    fun push(serial: String, localFile: java.io.File, remotePath: String, mode: Int = "755".toInt(8)) {
        require(localFile.isFile) { "找不到本地文件 ${localFile.absolutePath}" }
        open(30_000).use { sock ->
            sock.soTimeout = 120_000
            transport(sock, serial)
            request(sock.getOutputStream(), "sync:")
            expectOkay(sock.getInputStream(), "sync")

            val out = sock.getOutputStream()
            val input = sock.getInputStream()
            // SEND 的参数是 "<路径>,<权限>"
            val spec = "$remotePath,$mode".toByteArray(Charsets.UTF_8)
            out.write("SEND".toByteArray(Charsets.US_ASCII))
            out.write(le32(spec.size))
            out.write(spec)

            localFile.inputStream().buffered().use { fin ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = fin.read(buf)
                    if (n <= 0) break
                    out.write("DATA".toByteArray(Charsets.US_ASCII))
                    out.write(le32(n))
                    out.write(buf, 0, n)
                }
            }
            out.write("DONE".toByteArray(Charsets.US_ASCII))
            out.write(le32((localFile.lastModified() / 1000).toInt())) // mtime
            out.flush()

            when (val id = String(readExactly(input, 4), Charsets.US_ASCII)) {
                "OKAY" -> readExactly(input, 4)
                "FAIL" -> {
                    val n = readLe32(readExactly(input, 4))
                    throw IOException("推送 $remotePath 失败: ${String(readExactly(input, n))}")
                }
                else -> throw IOException("sync 协议异常: $id")
            }
        }
    }

    private fun le32(v: Int) = byteArrayOf(
        v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte()
    )

    private fun readLe32(b: ByteArray): Int =
        (b[0].toInt() and 0xff) or ((b[1].toInt() and 0xff) shl 8) or
            ((b[2].toInt() and 0xff) shl 16) or ((b[3].toInt() and 0xff) shl 24)

    companion object {
        fun freePort(): Int = ServerSocket(0).use { it.localPort }
    }
}
