package org.tgbackup.mtproto

import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.crypto.Cipher

class TransportException(message: String, val code: Int = 0) : IOException(message)

/**
 * TCP connection to a Telegram DC using the obfuscated "intermediate"
 * transport (the same family Telegram's own apps use). Blocking API; callers
 * run it on an IO dispatcher.
 */
internal class ObfuscatedTcp(private val host: String, private val port: Int, private val dcId: Int) {
    private lateinit var socket: Socket
    private lateinit var input: DataInputStream
    private lateinit var output: OutputStream
    private lateinit var encryptor: Cipher
    private lateinit var decryptor: Cipher
    private val writeLock = Any()

    @Volatile
    var closed = false
        private set

    fun connect(timeoutMs: Int = 15_000) {
        socket = Socket()
        socket.tcpNoDelay = true
        socket.keepAlive = true
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        socket.soTimeout = 0
        input = DataInputStream(socket.getInputStream().buffered(64 * 1024))
        output = socket.getOutputStream()

        var init: ByteArray
        while (true) {
            init = Crypto.randomBytes(64)
            val first = init[0].toInt() and 0xff
            val w0 = le32(init, 0)
            val w1 = le32(init, 4)
            if (first == 0xef) continue
            if (w0 == 0x44414548 || w0 == 0x54534f50 || w0 == 0x20544547 || w0 == 0x4954504f ||
                w0 == 0xeeeeeeee.toInt() || w0 == 0xdddddddd.toInt() || w0 == 0x02010316
            ) continue
            if (w1 == 0) continue
            break
        }
        // Protocol tag: intermediate.
        init[56] = 0xee.toByte(); init[57] = 0xee.toByte(); init[58] = 0xee.toByte(); init[59] = 0xee.toByte()
        init[60] = dcId.toByte()
        init[61] = (dcId shr 8).toByte()

        val reversed = init.copyOfRange(8, 56).reversedArray()
        encryptor = Crypto.aesCtr(init.copyOfRange(8, 40), init.copyOfRange(40, 56))
        decryptor = Crypto.aesCtr(reversed.copyOfRange(0, 32), reversed.copyOfRange(32, 48))
        val encrypted = encryptor.update(init)
        System.arraycopy(encrypted, 56, init, 56, 8)
        output.write(init)
        output.flush()
    }

    fun send(payload: ByteArray) {
        val frame = ByteArray(4 + payload.size)
        frame[0] = payload.size.toByte()
        frame[1] = (payload.size shr 8).toByte()
        frame[2] = (payload.size shr 16).toByte()
        frame[3] = (payload.size shr 24).toByte()
        System.arraycopy(payload, 0, frame, 4, payload.size)
        synchronized(writeLock) {
            if (closed) throw TransportException("connection closed")
            output.write(encryptor.update(frame))
            output.flush()
        }
    }

    /** Reads one packet. Throws [TransportException] on a transport-level error code. */
    fun receive(): ByteArray {
        val header = readDecrypted(4)
        val len = le32(header, 0)
        if (len < 0 || len > 64 * 1024 * 1024) throw TransportException("bad packet length $len")
        val body = readDecrypted(len)
        if (len == 4) {
            val code = le32(body, 0)
            throw TransportException("transport error $code", code)
        }
        return body
    }

    private fun readDecrypted(n: Int): ByteArray {
        val buf = ByteArray(n)
        input.readFully(buf)
        return decryptor.update(buf) ?: ByteArray(0)
    }

    fun close() {
        closed = true
        runCatching { socket.close() }
    }

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or ((b[off + 3].toInt() and 0xff) shl 24)
}
