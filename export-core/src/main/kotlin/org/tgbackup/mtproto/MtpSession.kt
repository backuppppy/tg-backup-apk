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
    /** The DC's addresses, tried in turn: each failed connect moves to the next one. */
    private val addresses: List<DcOption>,
    val authKey: AuthKey,
    @Volatile var serverSalt: Long,
    private val clock: MtpClock,
    private val app: AppInfo,
    private val log: (String) -> Unit,
    private val onBadMsgStorm: (BadMsgStorm) -> Unit = {},
    /** Failed connects in a row before [ensureConnected] gives up with an [MtpException]. */
    private val connectAttempts: Int = 8,
    /** The wait after the n-th failed connect. */
    private val backoffMs: (Int) -> Long = { attempt -> minOf(1000L * attempt, 10_000L) },
) {
    init {
        require(addresses.isNotEmpty()) { "no address" }
    }

    private val dc: DcOption = addresses.first()

    private class Pending(
        val body: ByteArray,
        val parse: (TlReader) -> Any?,
        val result: CompletableDeferred<Any?>,
        val wrapped: Boolean,
        /** The request's name, for the diagnostics. */
        val name: String = "",
        val createdAt: Long = System.currentTimeMillis(),
        @Volatile var resends: Int = 0,
    )

    private val badMsgStorms = BadMsgStormDetector()

    /** What each recently sent message was and when it went out (the bad_msg diagnostics: which message was refused). */
    private val sentLog = SentLog()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<Long, Pending>()
    private val connectLock = Mutex()
    private val ackQueue = java.util.concurrent.ConcurrentLinkedQueue<Long>()

    /** msg_id, seq_no and session_id, always given out together (see [MsgIds]). */
    private val ids = MsgIds { System.currentTimeMillis() + clock.offsetMs }

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
        val p = Pending(query.toBytes(), { r -> fn.parseResult(r) }, CompletableDeferred(), wrap, fn::class.simpleName.orEmpty())
        ensureConnected()
        send(p)
        @Suppress("UNCHECKED_CAST")
        return p.result.await() as R
    }

    /**
     * Connects if not connected, trying [addresses] in turn; after [connectAttempts] failed connects in a row
     * it throws, so a dead DC ends the caller's request instead of holding it for ever.
     */
    internal suspend fun ensureConnected() {
        if (invalidKey) throw AuthKeyInvalidException()
        if (tcp?.closed == false) return
        connectLock.withLock {
            if (tcp?.closed == false) return
            var attempt = 0
            while (true) {
                if (closed) throw MtpException("session closed")
                val address = addresses[attempt % addresses.size]
                try {
                    val t = ObfuscatedTcp(address.host, address.port, dc.id)
                    withContext(Dispatchers.IO) { t.connect() }
                    tcp = t
                    readerJob = scope.launch { readLoop(t) }
                    if (pingJob == null) pingJob = scope.launch { pingLoop() }
                    return
                } catch (e: java.io.IOException) {
                    attempt++
                    log("connect to DC${dc.id} failed (${e.message}), retry $attempt")
                    if (attempt >= connectAttempts) throw MtpException("אין חיבור ל-DC${dc.id} אחרי $attempt ניסיונות")
                    delay(backoffMs(attempt))
                }
            }
        }
    }

    private suspend fun send(p: Pending) {
        val id = ids.next(content = true)
        val msgId = id.msgId
        pending[msgId] = p
        sentLog.sent(msgId, "request ${p.name}" + if (p.resends > 0) " (resend ${p.resends})" else "")
        try {
            sendRaw(id, p.body)
        } catch (e: java.io.IOException) {
            // Reconnect and resend everything that is pending.
            val t = tcp
            dropConnection(t)
            if (t == null) scope.launch { reconnectAndResend() }
        }
    }

    private suspend fun sendRaw(id: MsgIds.Ids, body: ByteArray) {
        val t = tcp ?: throw java.io.IOException("not connected")
        val packet = encrypt(id, body)
        withContext(Dispatchers.IO) { t.send(packet) }
    }

    private fun encrypt(id: MsgIds.Ids, body: ByteArray): ByteArray {
        val w = TlWriter()
        w.long(serverSalt)
        w.long(id.sessionId)
        w.long(id.msgId)
        w.int(id.seq)
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

    @Volatile
    private var lastReceived = System.currentTimeMillis()

    private suspend fun readLoop(t: ObfuscatedTcp) {
        lastReceived = System.currentTimeMillis()
        try {
            while (scope.isActive && !t.closed) {
                val packet = t.receive()
                lastReceived = System.currentTimeMillis()
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
            p.resends++
            send(p)
        }
    }

    private suspend fun resend(badMsgId: Long) {
        val p = pending.remove(badMsgId) ?: return
        p.resends++
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
        // Answers to requests sent before a new session (bad_msg 32/33) still come in the old one: they are taken, so
        // those requests are neither stuck nor sent twice (19/11: 255 answers were dropped in a 34-minute run).
        if (!ids.isOurs(sid)) {
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
                val verdict = badMsgStorms.record(code)
                // Which of our messages was refused: its kind, how long ago it went out, and its id's own age.
                val what = sentLog.describe(badId, pending.containsKey(badId), System.currentTimeMillis() + clock.offsetMs)
                if (verdict is BadMsgStormDetector.Verdict.Log) log("DC${dc.id}: bad_msg_notification $code: $what")
                val offsetBefore = clock.offsetMs
                when (code) {
                    16, 17 -> {
                        clock.offsetMs = (msgId ushr 32) * 1000 - System.currentTimeMillis()
                        resend(badId)
                    }
                    32, 33 -> {
                        // Only the refused message is sent again, in a new session. The other pending requests were
                        // accepted in the old one and are answered there (see isOurs): sending them all again executed
                        // them twice, and a request sent in the middle of the switch could wait for ever.
                        ids.newSession()
                        connectionInited = false
                        resend(badId)
                    }
                    else -> pending.remove(badId)?.result?.completeExceptionally(MtpException("bad_msg_notification $code"))
                }
                if (verdict is BadMsgStormDetector.Verdict.Storm) reportStorm(code, verdict, offsetBefore)
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

    private fun reportStorm(code: Int, storm: BadMsgStormDetector.Verdict.Storm, offsetBefore: Long) {
        log("DC${dc.id}: bad_msg_notification $code x${storm.count} in ${storm.spanMs / 1000}s")
        runCatching {
            val now = System.currentTimeMillis()
            val all = pending.values.toList()
            onBadMsgStorm(
                BadMsgStorm(
                    dcId = dc.id,
                    code = code,
                    count = storm.count,
                    windowMs = storm.spanMs,
                    offsetBeforeMs = offsetBefore,
                    offsetAfterMs = clock.offsetMs,
                    pendingCount = all.size,
                    oldestRequestAgeMs = all.minOfOrNull { it.createdAt }?.let { now - it } ?: 0L,
                    maxResends = all.maxOfOrNull { it.resends } ?: 0,
                    refused = sentLog.recentRefused(),
                    pendingNames = all.joinToString(",") { it.name },
                ),
            )
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
        runCatching {
            val id = this.ids.next(content = false)
            sentLog.sent(id.msgId, "ack x${ids.size}")
            sendRaw(id, MTPDmsgs_ack(ids).toBytes())
        }
    }

    private suspend fun pingLoop() {
        while (scope.isActive && !closed) {
            delay(30_000)
            val t = tcp ?: continue
            if (System.currentTimeMillis() - lastReceived > 75_000) {
                // No pong for a long time: the connection is dead even if the socket is not.
                log("DC${dc.id}: no data for 75s, reconnecting")
                dropConnection(t)
                continue
            }
            runCatching {
                val id = ids.next(content = true)
                sentLog.sent(id.msgId, "ping")
                sendRaw(id, MTPPing_delay_disconnect(Crypto.random.nextLong(), 75).toBytes())
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

/**
 * The bad_msg diagnostics: what each recently sent message was ([sent], the last [keep]) and the description of the
 * last refused ones. On 2-18/11 Sentry saw storms of 20 code 16 refusals while one request waited 4-10 minutes.
 */
internal class SentLog(private val keep: Int = 500, private val now: () -> Long = System::currentTimeMillis) {
    private val sent = LinkedHashMap<Long, Pair<String, Long>>()
    private val refused = ArrayDeque<String>()

    @Synchronized
    fun sent(msgId: Long, kind: String) {
        sent[msgId] = kind to now()
        while (sent.size > keep) sent.remove(sent.keys.first())
    }

    /**
     * "request X (pending), sent 312 s ago, id 315 s old" for refused [msgId]; [serverNowMs] is now on the server's clock
     * (local time + the session's offset), against which the id's own time is measured.
     */
    @Synchronized
    fun describe(msgId: Long, isPending: Boolean, serverNowMs: Long): String {
        val idAge = (serverNowMs - (msgId ushr 32) * 1000) / 1000
        val entry = sent[msgId]
        val d = if (entry == null) "unknown message (not sent by this session lately), id ${idAge} s old"
        else "${entry.first}${if (isPending) " (pending)" else ""}, sent ${(now() - entry.second) / 1000} s ago, id ${idAge} s old"
        refused.addLast(d)
        while (refused.size > 20) refused.removeFirst()
        return d
    }

    @Synchronized
    fun recentRefused(): String = refused.joinToString(" | ")
}

/**
 * Gives out each outgoing message's msg_id and seq_no together, under one lock, with the session_id they belong to.
 * On 19/11 a live takeout job got a bad_msg_notification 33 (msg_seqno too high) every few seconds: msg_id and seq_no
 * were taken in two steps, so a message sent meanwhile (an ack, a ping, another request) could get a higher msg_id
 * but a lower seq_no. Each 33 started a new session and dropped the server's answers to the old one.
 */
internal class MsgIds(private val now: () -> Long) {
    class Ids(val msgId: Long, val seq: Int, val sessionId: Long)

    private var lastMsgId = 0L
    private var seqNo = 0

    @Volatile
    var sessionId: Long = Crypto.random.nextLong()
        private set

    /** The sessions before the current one (the last few), whose answers are still taken. */
    private val previous = ArrayDeque<Long>()

    /** The next ids; [content] messages (requests, pings) count in seq_no, acks do not. */
    @Synchronized
    fun next(content: Boolean): Ids {
        val t = now()
        var id = ((t / 1000) shl 32) or (((t % 1000) * 4294967L) and 0xfffffffcL)
        if (id <= lastMsgId) id = lastMsgId + 4
        lastMsgId = id
        val seq = if (content) seqNo++ * 2 + 1 else seqNo * 2
        return Ids(id, seq, sessionId)
    }

    /** A new session (after bad_msg_notification 32/33): seq_no starts over, msg_id keeps growing. */
    @Synchronized
    fun newSession() {
        previous.addLast(sessionId)
        while (previous.size > KEEP_PREVIOUS) previous.removeFirst()
        sessionId = Crypto.random.nextLong()
        seqNo = 0
    }

    /** True for this session and the last few before it. */
    @Synchronized
    fun isOurs(sid: Long): Boolean = sid == sessionId || sid in previous

    private companion object {
        const val KEEP_PREVIOUS = 4
    }
}
