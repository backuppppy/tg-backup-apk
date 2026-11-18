package org.tgbackup.mtproto

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.tgbackup.mtproto.tl.MTPDconfig
import org.tgbackup.mtproto.tl.MTPDdcOption
import org.tgbackup.mtproto.tl.MTPauth_ExportAuthorization
import org.tgbackup.mtproto.tl.MTPauth_ImportAuthorization
import org.tgbackup.mtproto.tl.MTPhelp_GetConfig
import org.tgbackup.mtproto.tl.TlFunction
import java.io.File
import java.util.Properties

/** Persists auth keys per DC, the main DC and the logged in user. */
class MtpStorage(private val file: File) {
    private val props = Properties()

    init {
        if (file.exists()) file.inputStream().use { props.load(it) }
    }

    @Synchronized
    fun get(key: String): String? = props.getProperty(key)

    @Synchronized
    fun set(key: String, value: String?) {
        if (value == null) props.remove(key) else props.setProperty(key, value)
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.outputStream().use { props.store(it, null) }
        tmp.renameTo(file)
    }

    var mainDc: Int
        get() = get("main_dc")?.toIntOrNull() ?: 2
        set(v) = set("main_dc", v.toString())

    var userId: Long
        get() = get("user_id")?.toLongOrNull() ?: 0L
        set(v) = set("user_id", v.toString())

    fun authKey(dc: Int): ByteArray? = get("key_$dc")?.let { hexToBytes(it) }
    fun setAuthKey(dc: Int, key: ByteArray?) = set("key_$dc", key?.let { bytesToHex(it) })
    fun isAuthorized(dc: Int): Boolean = get("authorized_$dc") == "1"
    fun setAuthorized(dc: Int, v: Boolean) = set("authorized_$dc", if (v) "1" else null)

    fun clear() {
        synchronized(this) { props.clear() }
        file.delete()
    }

    companion object {
        fun bytesToHex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        fun hexToBytes(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

/**
 * A small MTProto client: one main session plus extra sessions for other DCs
 * and for media downloads (like tdesktop's export media "shift").
 */
class MtpClient(
    val storage: MtpStorage,
    val app: AppInfo,
    val apiHash: String,
    private val log: (String) -> Unit,
    /** Called while waiting out a FLOOD_WAIT, with the number of seconds. */
    var onFloodWait: (Int) -> Unit = {},
    /** Called once when a burst of bad_msg_notification starts; observation only. */
    private val onBadMsgStorm: (BadMsgStorm) -> Unit = {},
) {
    enum class Kind { Main, Media }

    private val clock = MtpClock()
    private val sessions = HashMap<Pair<Int, Kind>, MtpSession>()
    private val sessionsLock = Mutex()
    private val authLock = Mutex()
    private var dcOptions: List<MTPDdcOption> = emptyList()

    val mainDc: Int get() = storage.mainDc

    private fun builtIn(dc: Int): DcOption = builtInAddresses(dc).first()

    /** The config's plain-TCP options for [dc]: IPv4, not CDN, not TCPO-only, without a secret. */
    private fun configOptions(dc: Int): List<MTPDdcOption> =
        dcOptions.filter { it.vid == dc && !it.is_ipv6 && !it.is_cdn && !it.is_tcpo_only && it.vsecret == null }

    /** The session's addresses for [dc], best first (media-only ones first for [Kind.Media]), then the built-in one. */
    private fun addresses(dc: Int, kind: Kind): List<DcOption> {
        val candidates = configOptions(dc)
        val ordered = if (kind == Kind.Media) candidates.filter { it.is_media_only } + candidates.filter { !it.is_media_only }
        else candidates.filter { !it.is_media_only }
        return (ordered.map { DcOption(dc, it.vip_address, it.vport) } +
            dcAddresses(dc, configOptions(dc).filter { !it.is_media_only }.map { it.vip_address })).distinctBy { it.host to it.port }
    }

    /** Where to create [dc]'s auth key: the built-in address, then the config's non-media options. */
    private fun keyAddresses(dc: Int): List<DcOption> =
        dcAddresses(dc, configOptions(dc).filter { !it.is_media_only }.map { it.vip_address })

    /**
     * Creates [dc]'s auth key at the first of [keyAddresses] that answers, going through them [KEY_ROUNDS] times: on
     * 18/11 the user's network dropped connections to Telegram's addresses at random (an address that timed out
     * answered a minute later). Each connect waits at most 6 s, each read 15 s.
     */
    private suspend fun createAuthKey(dc: Int): AuthKeyResult {
        repeat(KEY_ROUNDS) { round ->
            for (address in keyAddresses(dc)) {
                try {
                    return withContext(Dispatchers.IO) {
                        val t = ObfuscatedTcp(address.host, address.port, dc)
                        try {
                            t.connect(timeoutMs = 6_000, readTimeoutMs = 15_000)
                            AuthKeyCreator(t, dc).create()
                        } finally {
                            t.close()
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("creating auth key for DC$dc at ${address.host}:${address.port} failed (${e.javaClass.simpleName} ${e.message})")
                }
            }
            if (round < KEY_ROUNDS - 1) log("no address of DC$dc answered — trying them all again")
        }
        throw MtpException("DC$dc לא ענה בזמן יצירת המפתח")
    }

    private suspend fun session(dc: Int, kind: Kind): MtpSession {
        sessionsLock.withLock {
            sessions[dc to kind]?.let { if (!it.invalidKey) return it }
            sessions.remove(dc to kind)?.close()
            var key = storage.authKey(dc)
            var salt = 0L
            if (key == null) {
                log("creating auth key for DC$dc")
                val res = createAuthKey(dc)
                key = res.key.key
                salt = res.serverSalt
                clock.offsetMs = res.timeOffset * 1000L
                storage.setAuthKey(dc, key)
                storage.setAuthorized(dc, false)
            }
            val s = MtpSession(addresses(dc, kind), AuthKey(key), salt, clock, app, log, onBadMsgStorm)
            sessions[dc to kind] = s
            return s
        }
    }

    /** Sends [fn] to [dc] (main DC by default), waiting out flood waits and transient errors. */
    suspend fun <R> invoke(fn: TlFunction<R>, dc: Int = mainDc, kind: Kind = Kind.Main): R {
        var internalErrors = 0
        var keyResets = 0
        while (true) {
            if (dc != mainDc) ensureAuthorized(dc)
            val s = session(dc, kind)
            try {
                return s.invoke(fn)
            } catch (e: RpcError) {
                when {
                    e.code == 420 && (e.startsWith("FLOOD_WAIT_") || e.startsWith("FLOOD_PREMIUM_WAIT_")) -> {
                        val secs = e.argument ?: 5
                        log("flood wait $secs s")
                        onFloodWait(secs)
                        delay(secs * 1000L + 500)
                    }
                    e.code == 500 || e.code == -500 || e.type == "RPC_CALL_FAIL" || e.type.endsWith("_TIMEOUT") -> {
                        if (++internalErrors > 5) throw e
                        delay(1000L * internalErrors)
                    }
                    e.code == 401 && dc != mainDc && e.type == "AUTH_KEY_UNREGISTERED" -> {
                        storage.setAuthorized(dc, false)
                        if (++internalErrors > 3) throw e
                    }
                    else -> throw e
                }
            } catch (e: AuthKeyInvalidException) {
                // The server forgot this key: drop it and start over on this DC.
                if (++keyResets > 1 || dc == mainDc) throw e
                storage.setAuthKey(dc, null)
                storage.setAuthorized(dc, false)
            }
        }
    }

    private suspend fun ensureAuthorized(dc: Int) {
        if (storage.isAuthorized(dc)) return
        authLock.withLock {
            if (storage.isAuthorized(dc)) return
            val exported = invoke(MTPauth_ExportAuthorization(dc))
            val d = exported as org.tgbackup.mtproto.tl.MTPDauth_exportedAuthorization
            session(dc, Kind.Main).invoke(MTPauth_ImportAuthorization(d.vid, d.vbytes))
            storage.setAuthorized(dc, true)
        }
    }

    /** Fetches the DC list so other DCs (and media-only addresses) can be reached. */
    suspend fun loadConfig() {
        val cfg = invoke(MTPhelp_GetConfig()) as MTPDconfig
        dcOptions = cfg.vdc_options.filterIsInstance<MTPDdcOption>()
    }

    /** Switches the main DC (after a *_MIGRATE error during login). */
    fun setMainDc(dc: Int) {
        storage.mainDc = dc
    }

    fun close() {
        synchronized(sessions) {
            sessions.values.forEach { it.close() }
            sessions.clear()
        }
    }
}

/**
 * Telegram's addresses of each DC, the first one being the main one: the ones its desktop app keeps built in, and the
 * ones help.getConfig published on 18/11/2026 (so a login works even before the config is read, or when it is old).
 */
private val BUILT_IN_HOSTS: Map<Int, List<String>> = mapOf(
    1 to listOf("149.154.175.50", "149.154.175.53", "149.154.175.57"),
    2 to listOf("149.154.167.51", "95.161.76.100", "149.154.167.41"),
    3 to listOf("149.154.175.100"),
    4 to listOf("149.154.167.91", "149.154.167.92"),
    5 to listOf("149.154.171.5", "91.108.56.130", "91.108.56.181"),
)

/** How many times the auth key creation goes through all of a DC's addresses. */
private const val KEY_ROUNDS = 2

/** The ports Telegram's servers answer on, the usual one first. */
private val DC_PORTS = listOf(443, 80, 5222)

/**
 * Every built-in address of [dc], best first: each host on port 443, then each host on 80 and 5222. On 17-18/11 the
 * first login of a new account failed because 149.154.167.51:443 did not answer, from the phone and from the PC,
 * while TDLib (which tries other addresses and ports) connected.
 */
fun builtInAddresses(dc: Int): List<DcOption> = dcAddresses(dc, emptyList())

/**
 * [dc]'s built-in hosts and the config's [configHosts] (not yet known ones added after the built-in ones), each on
 * every port: all hosts on 443 first, then on 80, then on 5222.
 */
fun dcAddresses(dc: Int, configHosts: List<String>): List<DcOption> {
    val builtIn = BUILT_IN_HOSTS[dc] ?: throw MtpException("unknown DC $dc")
    val hosts = (builtIn + configHosts).distinct()
    return DC_PORTS.flatMap { port -> hosts.map { DcOption(dc, it, port) } }
}
