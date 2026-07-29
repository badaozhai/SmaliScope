package com.smaliscope.jdwp

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val HANDSHAKE = "JDWP-Handshake"

private const val FLAG_REPLY = 0x80

/**
 * 一条 JDWP 连接：握手 → 单独一条读线程分发 reply/event → 同步 command 发送。
 *
 * reply 按 packet id 回填 CompletableFuture；Event.Composite（cmdSet 64）交给 [onEvent]，
 * 由上层解析后经 Flow 推给会话编排。读线程与写操作分离，写加锁。
 */
class JdwpConnection private constructor(
    private val socket: Socket,
) : AutoCloseable {

    private val input = DataInputStream(socket.getInputStream().buffered())
    private val output = socket.getOutputStream()
    private val writeLock = Any()

    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableFuture<ByteArray>>()

    @Volatile
    private var closed = false

    @Volatile
    var idSizes: IdSizes = IdSizes()
        private set

    /** 收到 Event.Composite 时调用，参数是原始 data 段。 */
    @Volatile
    var onEvent: ((DataReader) -> Unit)? = null

    /** 连接意外断开时调用。 */
    @Volatile
    var onDisconnect: ((Throwable?) -> Unit)? = null

    private lateinit var readerThread: Thread

    companion object {
        /** 连接并完成握手 + IDSizes 协商。 */
        fun attach(host: String = "127.0.0.1", port: Int, timeoutMs: Int = 10_000): JdwpConnection {
            val sock = Socket()
            sock.connect(java.net.InetSocketAddress(host, port), timeoutMs)
            sock.tcpNoDelay = true
            val conn = JdwpConnection(sock)
            try {
                conn.handshake()
                conn.startReader()
                // IDSizes 必须最先取：之后所有变长 ID 的读写都依赖它。
                conn.idSizes = VirtualMachine(conn).idSizes()
                return conn
            } catch (t: Throwable) {
                runCatching { sock.close() }
                throw t
            }
        }
    }

    private fun handshake() {
        output.write(HANDSHAKE.toByteArray(Charsets.US_ASCII))
        output.flush()
        val buf = ByteArray(HANDSHAKE.length)
        input.readFully(buf)
        val got = String(buf, Charsets.US_ASCII)
        if (got != HANDSHAKE) {
            throw IOException("JDWP 握手失败，收到: $got")
        }
    }

    private fun startReader() {
        readerThread = Thread({ readLoop() }, "jdwp-reader").apply {
            isDaemon = true
            start()
        }
    }

    private fun readLoop() {
        try {
            while (!closed) {
                val length = input.readInt()
                if (length < 11) throw IOException("packet 长度非法: $length")
                val id = input.readInt()
                val flags = input.readUnsignedByte()
                val body: ByteArray
                if (flags and FLAG_REPLY != 0) {
                    val errorCode = input.readUnsignedShort()
                    body = ByteArray(length - 11).also { input.readFully(it) }
                    val fut = pending.remove(id)
                    if (errorCode != JdwpError.NONE) {
                        fut?.completeExceptionally(
                            JdwpException(errorCode, JdwpError.describe(errorCode))
                        )
                    } else {
                        fut?.complete(body)
                    }
                } else {
                    val cmdSet = input.readUnsignedByte()
                    val cmd = input.readUnsignedByte()
                    body = ByteArray(length - 11).also { input.readFully(it) }
                    if (cmdSet == CmdSet.EVENT && cmd == 100) {
                        runCatching { onEvent?.invoke(DataReader(body, idSizes)) }
                            .onFailure { System.err.println("事件处理异常: $it") }
                    }
                }
            }
        } catch (e: Throwable) {
            if (!closed) {
                val err = if (e is EOFException) IOException("目标进程已断开 JDWP 连接") else e
                pending.values.forEach { it.completeExceptionally(err) }
                pending.clear()
                onDisconnect?.invoke(err)
            }
        }
    }

    /** 发送 command 并等待 reply。失败抛 [JdwpException]。 */
    fun send(cmdSet: Int, cmd: Int, data: ByteArray = ByteArray(0), timeoutMs: Long = 15_000): DataReader {
        check(!closed) { "连接已关闭" }
        val id = nextId.getAndIncrement()
        val fut = CompletableFuture<ByteArray>()
        pending[id] = fut

        val len = 11 + data.size
        val header = ByteArray(11)
        header[0] = (len ushr 24).toByte()
        header[1] = (len ushr 16).toByte()
        header[2] = (len ushr 8).toByte()
        header[3] = len.toByte()
        header[4] = (id ushr 24).toByte()
        header[5] = (id ushr 16).toByte()
        header[6] = (id ushr 8).toByte()
        header[7] = id.toByte()
        header[8] = 0
        header[9] = cmdSet.toByte()
        header[10] = cmd.toByte()

        try {
            synchronized(writeLock) {
                output.write(header)
                if (data.isNotEmpty()) output.write(data)
                output.flush()
            }
        } catch (e: IOException) {
            pending.remove(id)
            throw e
        }

        return try {
            DataReader(fut.get(timeoutMs, TimeUnit.MILLISECONDS), idSizes)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        } catch (e: java.util.concurrent.TimeoutException) {
            pending.remove(id)
            throw IOException("JDWP 命令超时: cmdSet=$cmdSet cmd=$cmd")
        }
    }

    fun writer(): DataWriter = DataWriter(idSizes)

    override fun close() {
        if (closed) return
        closed = true
        runCatching { socket.close() }
    }
}
