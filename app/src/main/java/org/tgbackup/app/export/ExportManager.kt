package org.tgbackup.app.export

import android.os.Build
import android.os.Environment as AndroidEnvironment
import dev.g000sha256.tdl.TdlResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.tgbackup.app.BuildConfig
import org.tgbackup.app.data.Account
import org.tgbackup.app.engine.LogBus
import org.tgbackup.app.tg.AccountManager
import org.tgbackup.app.tg.getOrNull
import org.tgbackup.export.ChatEntry
import org.tgbackup.export.Controller
import org.tgbackup.export.Environment
import org.tgbackup.export.ExportState
import org.tgbackup.export.ExportSupport
import org.tgbackup.export.MediaSettings
import org.tgbackup.export.Settings
import org.tgbackup.export.TopicEntry
import org.tgbackup.export.data.ExportTimeZone
import org.tgbackup.export.output.Format
import org.tgbackup.mtproto.AppInfo
import org.tgbackup.mtproto.MtpClient
import org.tgbackup.mtproto.MtpLogin
import org.tgbackup.mtproto.MtpStorage
import java.io.File

/** The export options remembered between exports (Desktop's ExportSettings). */
@Serializable
data class ExportPrefs(
    val mediaTypes: Int = MediaSettings.DefaultTypes(),
    val sizeLimit: Long = 8L * 1024 * 1024,
    val format: String = "Html",
    val path: String = "",
    /** TAKEOUT_INIT_DELAY: unixtime when the export becomes available, per account. */
    val availableAt: Map<String, Int> = emptyMap(),
)

/** Asked from the UI when the export session needs the 2FA password. */
class PasswordRequest(val hint: String, val answer: CompletableDeferred<String?>)

class RunningExport(
    val accountId: String,
    val title: String,
    val controller: Controller,
    val job: Job,
)

/**
 * Owns one MTProto export session per TG Backup account (separate from its
 * TDLib session) and runs the takeout export.
 */
class ExportManager(
    private val rootDir: File,
    private val accounts: AccountManager,
    private val log: LogBus,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clients = HashMap<String, MtpClient>()
    private val lock = Mutex()
    private val prefsFile = File(rootDir, "export/prefs.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _prefs = MutableStateFlow(loadPrefs())
    val prefs: StateFlow<ExportPrefs> = _prefs.asStateFlow()

    private val _password = MutableStateFlow<PasswordRequest?>(null)
    val password: StateFlow<PasswordRequest?> = _password.asStateFlow()

    private val _running = MutableStateFlow<RunningExport?>(null)
    val running: StateFlow<RunningExport?> = _running.asStateFlow()

    private fun loadPrefs(): ExportPrefs = runCatching {
        json.decodeFromString(ExportPrefs.serializer(), prefsFile.readText())
    }.getOrDefault(ExportPrefs())

    fun updatePrefs(change: (ExportPrefs) -> ExportPrefs) {
        val next = change(_prefs.value)
        _prefs.value = next
        runCatching {
            prefsFile.parentFile?.mkdirs()
            prefsFile.writeText(json.encodeToString(ExportPrefs.serializer(), next))
        }
    }

    /** Desktop's default: Downloads/<AppName>. */
    fun defaultPath(): String =
        File(AndroidEnvironment.getExternalStoragePublicDirectory(AndroidEnvironment.DIRECTORY_DOWNLOADS), "AyuGram Desktop").absolutePath

    fun exportPath(): String = _prefs.value.path.ifBlank { defaultPath() }

    private suspend fun client(account: Account): MtpClient = lock.withLock {
        clients[account.id]?.let { return it }
        val storage = MtpStorage(File(rootDir, "export/${account.id}/session.properties"))
        val app = AppInfo(
            apiId = account.apiId,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            systemVersion = "Android ${Build.VERSION.RELEASE}",
            appVersion = "${BuildConfig.VERSION_NAME} (export)",
        )
        val client = MtpClient(storage, app, account.apiHash, { log.info("[export] $it") })
        clients[account.id] = client
        client
    }

    suspend fun isLoggedIn(account: Account): Boolean = MtpLogin(client(account)).isLoggedIn

    /** Logs the export session in by confirming a QR token with the account's TDLib session. */
    suspend fun login(account: Account): Result<Unit> = runCatching {
        val tdlib = accounts[account.id] ?: error("החשבון לא מחובר ב-TG Backup")
        if (!tdlib.isReady) error("החשבון לא מחובר ב-TG Backup")
        val me = tdlib.client.getMe().getOrNull() ?: error("לא ניתן לקרוא את פרטי החשבון")
        val c = client(account)
        val login = MtpLogin(c)
        if (login.isLoggedIn && login.checkSession()) return@runCatching
        login.loginWithQr(
            expectedUserId = me.id,
            confirm = { link ->
                when (val r = tdlib.client.confirmQrCodeAuthentication(link)) {
                    is TdlResult.Failure -> error("אישור ההתחברות נכשל: ${r.code} ${r.message}")
                    is TdlResult.Success -> log.info("[${tdlib.tag}] סשן הייצוא אושר")
                }
            },
            password = { hint ->
                val request = PasswordRequest(hint, CompletableDeferred())
                _password.value = request
                try {
                    request.answer.await() ?: error("ההתחברות בוטלה")
                } finally {
                    _password.value = null
                }
            },
        )
        log.success("[${tdlib.tag}] סשן הייצוא מחובר")
    }

    /** The account's export session, logged in, for copying jobs (see TakeoutJobRunner). */
    suspend fun copyClient(account: Account): Result<MtpClient> = login(account).map { client(account) }

    suspend fun loadChats(account: Account, progress: (Int) -> Unit): List<ChatEntry> =
        ExportSupport.loadDialogs(client(account), progress)

    suspend fun loadTopics(account: Account, chat: ChatEntry): List<TopicEntry> =
        ExportSupport.loadTopics(client(account), chat.input)

    fun start(account: Account, chat: ChatEntry, topic: TopicEntry?, from: Int, till: Int, onStarted: () -> Unit) {
        if (_running.value != null) return
        val prefs = _prefs.value
        val settings = Settings()
        settings.path = exportPath()
        settings.format = Format.valueOf(prefs.format)
        settings.media.types = prefs.mediaTypes
        settings.media.sizeLimit = prefs.sizeLimit
        settings.singlePeer = chat.input
        settings.singlePeerFrom = from
        settings.singlePeerTill = till
        val title = topic?.title ?: chat.title
        _running.value = null
        scope.launch {
            val c = client(account)
            val controller = Controller(
                client = c,
                thumbs = AndroidImageThumbs(),
                topicRootId = topic?.id ?: 0,
                topicPeerId = if (topic != null) chat.peerId else 0,
                topicTitle = topic?.title ?: "",
                log = { log.info("[export] $it") },
            )
            val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                runExport(account, c, controller, settings, title)
            }
            _running.value = RunningExport(account.id, title, controller, job)
            onStarted()
            job.start()
        }
    }

    private suspend fun runExport(account: Account, c: MtpClient, controller: Controller, settings: Settings, title: String) {
        ExportTimeZone.zone = java.util.TimeZone.getDefault()
        val environment = Environment()
        environment.aboutTelegram = "Here is the data you requested. Remember: Telegram is ad free, it doesn't use your data for ad targeting and doesn't sell it to others. Telegram only keeps the information it needs to function as a secure and feature-rich cloud service.\n\nCheck out Settings > Privacy & Security on Telegram's mobile apps for the relevant settings."
        environment.aboutChats = "This page lists all chats from this export."
        environment.aboutLeftChats = "Below are the supergroups and channels from this export that you've left or where you were banned.\n\nNote that when you leave a channel or supergroup you've created, you have the option to either delete it, or simply leave (in case you want to rejoin later, or keep the community alive despite not being a member)."
        runCatching { environment.internalLinksDomain = ExportSupport.internalLinksDomain(c) }
        runCatching { ExportSupport.loadCountries(c) }
        File(settings.path).mkdirs()
        log.info("[export] מתחיל ייצוא של \"$title\" אל ${settings.path}")
        try {
            controller.run(settings, environment)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("[export] הייצוא נכשל: ${e.javaClass.simpleName} ${e.message}")
            throw e
        }
        when (val s = controller.state.value) {
            is ExportState.Finished -> log.success("[export] הייצוא הושלם: ${s.filesCount} קבצים, ${s.bytesCount} בתים")
            is ExportState.ApiError -> {
                log.error("[export] שגיאת API: ${s.code} ${s.type}")
                if (s.type.startsWith("TAKEOUT_INIT_DELAY_")) {
                    val seconds = maxOf(s.type.removePrefix("TAKEOUT_INIT_DELAY_").toIntOrNull() ?: 1, 1)
                    val at = (System.currentTimeMillis() / 1000).toInt() + seconds
                    updatePrefs { it.copy(availableAt = it.availableAt + (account.id to at)) }
                }
            }
            is ExportState.OutputError -> log.error("[export] שגיאת כתיבה: ${s.path}")
            else -> Unit
        }
    }

    fun skipFile() {
        val running = _running.value ?: return
        val state = running.controller.state.value as? ExportState.Processing ?: return
        running.controller.skipFile(state.state.bytesRandomId)
    }

    fun stop() {
        val running = _running.value ?: return
        scope.launch {
            running.controller.cancelExportFast()
            running.job.cancel()
        }
    }

    /** Clears a finished / failed export from the screen. */
    fun dismiss() {
        val running = _running.value ?: return
        if (running.job.isActive) return
        _running.value = null
    }

    fun isActive(): Boolean = _running.value?.job?.isActive == true

    /** Drops the export session of an account (e.g. after it was removed). */
    suspend fun forget(accountId: String) = lock.withLock {
        clients.remove(accountId)?.close()
        File(rootDir, "export/$accountId").deleteRecursively()
    }
}
