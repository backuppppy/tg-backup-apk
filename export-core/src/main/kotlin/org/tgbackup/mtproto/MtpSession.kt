package org.tgbackup.mtproto

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tgbackup.mtproto.tl.InitConnection
import org.tgbackup.mtproto.tl.InvokeWithLayer
import org.tgbackup.mtproto.tl.MTPDmsgs_ack
import org.tgbackup.mtproto.tl.MTPPing_delay_disconnect
import org.tgbackup.mtproto.tl.TlFunction
import org.tgbackup.mtproto.tl.TlReader
import org.tgbackup.mtproto.tl.TlWriter
import org.tgbackup.mtproto.tl.Tl
import java.util.concurrent.ConcurrentHashMap

open class MtpException(message: String) : Exception(message)

/** An RPC error returned by Telegram, e.g. 420 FLOOD_WAIT_30. */
class RpcError(val code: Int, val type: String) : MtpException("$code $type") {
    /** The number after the last '_' of e.g. FLOOD_WAIT_30 / FILE_MIGRATE_4. */
    val argument: Int? get() = type.substringAfterLast('_').toIntOrNull()
    fun startsWith(prefix: String) = type.startsWith(prefix)
}

class AuthKeyInvalidException : MtpException("auth key is not registered on the server (-404)")

data class DcOption(val id: Int, val host: String, val port: Int = 443)

data class AppInfo(
    val apiId: Int,
    val deviceModel: String,
    val systemVersion: String,
    val appVersion: String,
    val langCode: String = "en",
)

internal const val API_LAYER = 229

/**
 * One MTProto session (session_id) over one TCP connection, reconnecting and
 * re-sending unanswered requests as needed.
 */
internal class MtpSession(
    private val dc: DcOption,
    val authKey: AuthKey,
    @Volatile var serverSalt: Long,
    private val clock: MtpClock,
    private val app: AppInfo,
    private val log: (String) -> Unit,
) {
    private class Pending(
        val body: ByteArray,
        val parse: (TlReader) -> Any?,
        val result: CompletableDeferred<Any?>,
        val wrapped: Boolean,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<Long, Pending>()
    private val connectLock = Mutex()
    private val ackQueue = java.util.concurrent.ConcurrentLinkedQueue<Long>()

    private var sessionId = Crypto.random.nextLong()
    private var seqNo = 0
    private var lastMsgId = 0L
    private val stateLock = Any()

    @Volatile
    private var connectionInited = false

    @Volatile
    private var tcp: ObfuscatedTcp? = null
    private var readerJob: Job? = null
    private var pingJob: Job? = null

    @Volatile
    private var closed = false

    @Volatile
    var invalidKey = false
        private set

    suspend fun <R> invoke(fn: TlFunction<R>): R {
        if (closed) throw MtpException("session closed")
        val wrap = !connectionInited
        val query: TlFunction<R> = if (wrap) {
            InvokeWithLayer(
                API_LAYER,
                InitConnection(app.apiId, app.deviceModel, app.systemVersion, app.appVersion, app.langCode, "", app.langCode, fn),
            )
        } else fn
        val p = Pending(query.toBytes(), { r -> fn.parseResult(r) }, CompletableDeferred(), wrap)
        ensureConnected()
        send(p)
        @Suppress("UNCHECKED_CAST")
        return p.result.await() as R
    }

    private suspend fun ensureConnected() {
        if (invalidKey) throw AuthKeyInvalidException()
        if (tcp?.closed == false) return
        connectLock.withLock {
            if (tcp?.closed == false) return
            var attempt = 0
            while (true) {
                if (closed) throw MtpException("session closed")
                try {
                    val t = ObfuscatedTcp(dc.host, dc.port, dc.id)
                    withContext(Dispatchers.IO) { t.connect() }
                    tcp = t
                    readerJob = scope.launch { readLoop(t) }
                    if (pingJob == null) pingJob = scope.launch { pingLoop() }
                    return
                } catch (e: java.io.IOException) {
                    attempt++
                    log("connect to DC${dc.id} failed (${e.message}), retry $attempt")
                    delay(minOf(1000L * attempt, 10_000L))
                }
            }
        }
    }

    private fun nextMsgId(): Long = synchronized(stateLock) {
        val now = System.currentTimeMillis() + clock.offsetMs
        var id = ((now / 1000) shl 32) or (((now % 1000) * 4294967L) and 0xfffffffcL)
        if (id <= lastMsgId) id = lastMsgId + 4
        lastMsgId = id
        id
    }

    private fun nextSeq(content: Boolean): Int = synchronized(stateLock) {
        if (content) {
            val s = seqNo * 2 + 1
            seqNo++
            s
        } else seqNo * 2
    }

    private suspend fun send(p: Pending) {
        val msgId = nextMsgId()
        pending[msgId] = p
        try {
            sendRaw(msgId, nextSeq(true), p.body)
        } catch (e: java.io.IOException) {
            // The reader will reconnect and resend everything that is pending.
            dropConnection(tcp)
        }
    }

    private suspend fun sendRaw(msgId: Long, seq: Int, body: ByteArray) {
        val t = tcp ?: throw java.io.IOException("not connected")
        val packet = encrypt(msgId, seq, body)
        withContext(Dispatchers.IO) { t.send(packet) }
    }

    private fun encrypt(msgId: Long, seq: Int, body: ByteArray): ByteArray {
        val w = TlWriter()
        w.long(serverSalt)
        w.long(sessionId)
        w.long(msgId)
        w.int(seq)
        w.int(body.size)
        w.raw(body)
        val unpadded = w.size
        var pad = 16 - (unpadded % 16)
        if (pad < 12) pad += 16
        w.raw(Crypto.randomBytes(pad))
        val plain = w.toByteArray()
        val key = authKey.key
        val msgKeyLarge = Crypto.sha256(key.copyOfRange(88, 120), plain)
        val msgKey = msgKeyLarge.copyOfRange(8, 24)
        val (aesKey, aesIv) = kdf(key, msgKey, 0)
        val enc = Crypto.igeEncrypt(plain, aesKey, aesIv)
        val out = TlWriter()
        out.long(authKey.id)
        out.raw(msgKey)
        out.raw(enc)
        return out.toByteArray()
    }

    private fun kdf(key: ByteArray, msgKey: ByteArray, x: Int): Pair<ByteArray, ByteArray> {
        val a = Crypto.sha256(msgKey, key.copyOfRange(x, x + 36))
        val b = Crypto.sha256(key.copyOfRange(40 + x, 40 + x + 36), msgKey)
        val aesKey = a.copyOfRange(0, 8) + b.copyOfRange(8, 24) + a.copyOfRange(24, 32)
        val aesIv = b.copyOfRange(0, 8) + a.copyOfRange(8, 24) + b.copyOfRange(24, 32)
        return aesKey to aesIv
    }

    private suspend fun readLoop(t: ObfuscatedTcp) {
        try {
            while (scope.isActive && !t.closed) {
                val packet = t.receive()
                handlePacket(packet)
            }
        } catch (e: TransportException) {
            if (e.code == -404) {
                invalidKey = true
                log("DC${dc.id}: auth key not found on server")
                failAll(AuthKeyInvalidException())
            } else {
                log("DC${dc.id}: ${e.message}")
                if (e.code == -429) delay(2000)
            }
        } catch (e: Exception) {
            if (!t.closed) log("DC${dc.id} connection lost: ${e.javaClass.simpleName} ${e.message}")
        }
        dropConnection(t)
        if (!closed && !invalidKey && pending.isNotEmpty()) {
            scope.launch { reconnectAndResend() }
        }
    }

    private fun dropConnection(t: ObfuscatedTcp?) {
        if (t == null) return
        t.close()
        if (tcp === t) tcp = null
    }

    private suspend fun reconnectAndResend() {
        try {
            ensureConnected()
        } catch (e: Exception) {
            failAll(e)
            return
        }
        resendAll()
    }

    private suspend fun resendAll() {
        val old = pending.keys.toList()
        for (id in old) {
            val p = pending.remove(id) ?: continue
            send(p)
        }
    }

    private suspend fun resend(badMsgId: Long) {
        val p = pending.remove(badMsgId) ?: return
        send(p)
    }

    private fun failAll(e: Throwable) {
        val all = pending.keys.toList()
        for (id in all) pending.remove(id)?.result?.completeExceptionally(e)
    }

    private suspend fun handlePacket(packet: ByteArray) {
        val r = TlReader(packet)
        val keyId = r.long()
        if (keyId != authKey.id) throw MtpException("auth key id mismatch")
        val msgKey = r.raw(16)
        val (aesKey, aesIv) = kdf(authKey.key, msgKey, 8)
        val plain = Crypto.igeDecrypt(packet.copyOfRange(24, packet.size), aesKey, aesIv)
        val check = Crypto.sha256(authKey.key.copyOfRange(96, 128), plain).copyOfRange(8, 24)
        if (!check.contentEquals(msgKey)) throw MtpException("msg_key mismatch")
        val pr = TlReader(plain)
        pr.long() // salt
        val sid = pr.long()
        if (sid != sessionId) {
            log("DC${dc.id}: message for another session ignored")
            return
        }
        val msgId = pr.long()
        val seq = pr.int()
        val len = pr.int()
        if (len < 0 || len > pr.remaining) throw MtpException("bad inner length")
        val body = plain.copyOfRange(pr.position, pr.position + len)
        handleMessage(msgId, seq, body)
        flushAcks()
    }

    private suspend fun handleMessage(msgId: Long, seq: Int, body: ByteArray) {
        if (seq and 1 == 1) ackQueue.add(msgId)
        val r = TlReader(body)
        when (val id = r.int()) {
            0x73f1f8dc -> { // msg_container
                val n = r.int()
                repeat(n) {
                    val innerId = r.long()
                    val innerSeq = r.int()
                    val len = r.int()
                    val inner = r.raw(len)
                    handleMessage(innerId, innerSeq, inner)
                }
            }
            Tl.GZIP_PACKED -> handleMessage(msgId, seq, Tl.gunzip(r.bytes()))
            0xf35c6d01.toInt() -> { // rpc_result
                val reqId = r.long()
                handleResult(reqId, body.copyOfRange(r.position, body.size))
            }
            0xedab447b.toInt() -> { // bad_server_salt
                val badId = r.long()
                r.int()
                r.int()
                serverSalt = r.long()
                resend(badId)
            }
            0xa7eff811.toInt() -> { // bad_msg_notification
                val badId = r.long()
                r.int()
                val code = r.int()
                log("DC${dc.id}: bad_msg_notification $code")
                when (code) {
                    16, 17 -> {
                        clock.offsetMs = (msgId ushr 32) * 1000 - System.currentTimeMillis()
                        resend(badId)
                    }
                    32, 33 -> {
                        synchronized(stateLock) {
                            sessionId = Crypto.random.nextLong()
                            seqNo = 0
                        }
                        connectionInited = false
                        resendAll()
                    }
                    else -> pending.remove(badId)?.result?.completeExceptionally(MtpException("bad_msg_notification $code"))
                }
            }
            0x9ec20908.toInt() -> { // new_session_created
                r.long()
                r.long()
                serverSalt = r.long()
            }
            else -> {
                // Acks, pongs, detailed infos and updates need no action here.
                if (id == 0x62d6b459 || id == 0x347773c5 || id == 0x276d3ec6 || id == 0x809db6df.toInt()) return
            }
        }
    }

    private fun handleResult(reqId: Long, data: ByteArray) {
        val p = pending.remove(reqId) ?: return
        try {
            var r = TlReader(data)
            if (r.peekInt() == Tl.GZIP_PACKED) {
                r.int()
                r = TlReader(Tl.gunzip(r.bytes()))
            }
            if (r.peekInt() == 0x2144ca19) {
                r.int()
                val code = r.int()
                val msg = String(r.bytes(), Charsets.UTF_8)
                p.result.completeExceptionally(RpcError(code, msg))
                return
            }
            if (p.wrapped) connectionInited = true
            p.result.complete(p.parse(r))
        } catch (e: Throwable) {
            p.result.completeExceptionally(e)
        }
    }

    private suspend fun flushAcks() {
        if (ackQueue.isEmpty()) return
        val ids = ArrayList<Long>()
        while (true) ids.add(ackQueue.poll() ?: break)
        if (ids.isEmpty()) return
        runCatching { sendRaw(nextMsgId(), nextSeq(false), MTPDmsgs_ack(ids).toBytes()) }
    }

    private suspend fun pingLoop() {
        while (scope.isActive && !closed) {
            delay(30_000)
            val t = tcp ?: continue
            runCatching {
                sendRaw(nextMsgId(), nextSeq(true), MTPPing_delay_disconnect(Crypto.random.nextLong(), 75).toBytes())
            }.onFailure { dropConnection(t) }
        }
    }

    fun close() {
        closed = true
        dropConnection(tcp)
        failAll(MtpException("session closed"))
        scope.cancel()
    }
}

internal class MtpClock {
    @Volatile
    var offsetMs: Long = 0
}
