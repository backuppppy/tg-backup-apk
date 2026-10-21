package org.tgbackup.app.tg

import android.os.Build
import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.AuthorizationState
import dev.g000sha256.tdl.dto.AuthorizationStateClosed
import dev.g000sha256.tdl.dto.AuthorizationStateClosing
import dev.g000sha256.tdl.dto.AuthorizationStateLoggingOut
import dev.g000sha256.tdl.dto.AuthorizationStateReady
import dev.g000sha256.tdl.dto.AuthorizationStateWaitCode
import dev.g000sha256.tdl.dto.AuthorizationStateWaitEmailAddress
import dev.g000sha256.tdl.dto.AuthorizationStateWaitEmailCode
import dev.g000sha256.tdl.dto.AuthorizationStateWaitPassword
import dev.g000sha256.tdl.dto.AuthorizationStateWaitPhoneNumber
import dev.g000sha256.tdl.dto.AuthorizationStateWaitRegistration
import dev.g000sha256.tdl.dto.AuthorizationStateWaitTdlibParameters
import dev.g000sha256.tdl.dto.Chat
import dev.g000sha256.tdl.dto.ChatListArchive
import dev.g000sha256.tdl.dto.ChatListMain
import dev.g000sha256.tdl.dto.File as TdFile
import dev.g000sha256.tdl.dto.InputMessageContent
import dev.g000sha256.tdl.dto.Message
import dev.g000sha256.tdl.dto.UpdateAuthorizationState
import dev.g000sha256.tdl.dto.UpdateMessageSendFailed
import dev.g000sha256.tdl.dto.UpdateMessageSendSucceeded
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.tgbackup.app.BuildConfig
import org.tgbackup.app.data.Account
import org.tgbackup.app.engine.LogBus
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

sealed class AuthUi(val label: String) {
    data object Starting : AuthUi("מאתחל…")
    data object NeedPhone : AuthUi("מנותק")
    data object NeedCode : AuthUi("ממתין לקוד")
    data class NeedPassword(val hint: String) : AuthUi("ממתין לסיסמת אימות דו-שלבי")
    data class Ready(val display: String) : AuthUi("מחובר")
    data class Unsupported(val why: String) : AuthUi(why)
    data object Closed : AuthUi("נסגר")
}

sealed class SendOutcome {
    data class Sent(val messageId: Long) : SendOutcome()
    data class Failed(val code: Int, val message: String, val retryAfterSec: Int) : SendOutcome()
}

fun <T> TdlResult<T>.getOrNull(): T? = (this as? TdlResult.Success)?.result
fun TdlResult<*>.errorText(): String? = (this as? TdlResult.Failure)?.let { "${it.code}: ${it.message}" }

/** One TDLib instance bound to one Telegram account, with its own database directory. */
class AccountClient(
    initial: Account,
    val baseDir: File,
    private val log: LogBus,
) {
    /** Display fields (name) may change while the session stays alive. */
    @Volatile
    var account: Account = initial
    val client: TdlClient = TdlClient.create()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _auth = MutableStateFlow<AuthUi>(AuthUi.Starting)
    val auth: StateFlow<AuthUi> = _auth.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val pendingSends = ConcurrentHashMap<Long, CompletableDeferred<SendOutcome>>()
    private val earlySends = ConcurrentHashMap<Long, SendOutcome>()
    private val chatCache = ConcurrentHashMap<String, Chat>()

    val tag: String get() = account.name.ifBlank { account.phone }

    fun start() {
        // Subscribe before the first request so the initial authorization updates aren't missed.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.allUpdates.collect { update ->
                when (update) {
                    is UpdateAuthorizationState -> onAuthState(update.authorizationState)
                    is UpdateMessageSendSucceeded -> completeSend(update.oldMessageId, SendOutcome.Sent(update.message.id))
                    is UpdateMessageSendFailed -> completeSend(
                        update.oldMessageId,
                        SendOutcome.Failed(update.error.code, update.error.message, retryAfter(update.error.code, update.error.message)),
                    )
                    else -> Unit
                }
            }
        }
        scope.launch { client.getOption("version") }
    }

    private fun onAuthState(state: AuthorizationState) {
        when (state) {
            is AuthorizationStateWaitTdlibParameters -> scope.launch { sendParameters() }
            is AuthorizationStateWaitPhoneNumber -> _auth.value = AuthUi.NeedPhone
            is AuthorizationStateWaitCode -> _auth.value = AuthUi.NeedCode
            is AuthorizationStateWaitPassword -> _auth.value = AuthUi.NeedPassword(state.passwordHint)
            is AuthorizationStateReady -> scope.launch {
                val me = client.getMe().getOrNull()
                val display = me?.let { u ->
                    val user = u.usernames?.activeUsernames?.firstOrNull()?.let { " (@$it)" } ?: ""
                    "${u.firstName} ${u.lastName}".trim() + user
                } ?: tag
                _lastError.value = null
                _auth.value = AuthUi.Ready(display)
                log.success("[$tag] מחובר לטלגרם כ-$display")
            }
            is AuthorizationStateWaitEmailAddress, is AuthorizationStateWaitEmailCode ->
                _auth.value = AuthUi.Unsupported("טלגרם דורש אימות מייל — התחבר פעם אחת מאפליקציית טלגרם הרשמית ונסה שוב")
            is AuthorizationStateWaitRegistration ->
                _auth.value = AuthUi.Unsupported("המספר לא רשום בטלגרם")
            is AuthorizationStateLoggingOut, is AuthorizationStateClosing -> Unit
            is AuthorizationStateClosed -> _auth.value = AuthUi.Closed
            else -> _auth.value = AuthUi.Unsupported("מצב התחברות לא נתמך: ${state::class.simpleName}")
        }
    }

    private suspend fun sendParameters() {
        val db = File(baseDir, "db").apply { mkdirs() }
        val files = File(baseDir, "files").apply { mkdirs() }
        val r = client.setTdlibParameters(
            useTestDc = false,
            databaseDirectory = db.absolutePath,
            filesDirectory = files.absolutePath,
            databaseEncryptionKey = ByteArray(0),
            useFileDatabase = true,
            useChatInfoDatabase = true,
            useMessageDatabase = false,
            useSecretChats = false,
            apiId = account.apiId,
            apiHash = account.apiHash,
            systemLanguageCode = "he",
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            systemVersion = "Android ${Build.VERSION.RELEASE}",
            applicationVersion = BuildConfig.VERSION_NAME,
        )
        r.errorText()?.let {
            _auth.value = AuthUi.Unsupported("API ID / API Hash לא תקינים ($it)")
            log.error("[$tag] אתחול נכשל: $it")
        }
    }

    // ── Auth actions (return an error text, or null on success) ─────────────

    suspend fun sendPhone(): String? {
        var phone = account.phone.replace(" ", "").replace("-", "")
        if (phone.isNotEmpty() && !phone.startsWith("+")) phone = "+$phone"
        return client.setAuthenticationPhoneNumber(phone).errorText().also { _lastError.value = it }
    }

    suspend fun sendCode(code: String): String? =
        client.checkAuthenticationCode(code.trim()).errorText().also { _lastError.value = it }

    suspend fun sendPassword(password: String): String? =
        client.checkAuthenticationPassword(password).errorText().also { _lastError.value = it }

    /** Logs out (if logged in) and waits for the client to close. */
    suspend fun logOutAndClose() {
        if (_auth.value is AuthUi.Ready) client.logOut() else client.close()
        withTimeoutOrNull(15.seconds) { auth.first { it is AuthUi.Closed } }
    }

    suspend fun close() {
        client.close()
        withTimeoutOrNull(10.seconds) { auth.first { it is AuthUi.Closed } }
        scope.cancel()
    }

    fun dispose() = scope.cancel()

    suspend fun awaitReady() = auth.first { it is AuthUi.Ready }

    val isReady: Boolean get() = _auth.value is AuthUi.Ready

    // ── Chats ────────────────────────────────────────────────────────────────

    /**
     * Accepts a numeric chat id (-100…), @username, t.me/username or a t.me/c/<id>/… link.
     */
    suspend fun resolveChat(raw: String): Result<Chat> {
        val input = raw.trim()
        chatCache[input]?.let { return Result.success(it) }
        val chat = findChat(input)
        if (chat != null) chatCache[input] = chat
        return chat?.let { Result.success(it) }
            ?: Result.failure(IllegalArgumentException("הצ'אט '$input' לא נמצא — ודא שהחשבון $tag חבר בו"))
    }

    private suspend fun findChat(input: String): Chat? {
        Regex("""t\.me/c/(\d+)""").find(input)?.let { m ->
            return chatById(-1_000_000_000_000L - m.groupValues[1].toLong())
        }
        input.toLongOrNull()?.let { return chatById(it) }
        val username = Regex("""(?:t\.me/|@)?([A-Za-z][A-Za-z0-9_]{3,})/?$""").find(input.removePrefix("https://"))
            ?.groupValues?.get(1) ?: return null
        return client.searchPublicChat(username).getOrNull()
    }

    private suspend fun chatById(id: Long): Chat? {
        client.getChat(id).getOrNull()?.let { return it }
        when {
            id <= -1_000_000_000_000L -> {
                val sg = -id - 1_000_000_000_000L
                if (client.getSupergroup(sg).getOrNull() != null) {
                    client.createSupergroupChat(sg, false).getOrNull()?.let { return it }
                }
            }
            id < 0 -> client.createBasicGroupChat(-id, false).getOrNull()?.let { return it }
            else -> client.createPrivateChat(id, false).getOrNull()?.let { return it }
        }
        // The chat may simply not be loaded into TDLib yet — load the chat lists and retry.
        for (list in listOf(ChatListMain(), ChatListArchive())) {
            for (page in 0 until 30) {
                // Fails with 404 once the whole list is loaded.
                if (client.loadChats(list, 200) is TdlResult.Failure) break
            }
        }
        return client.getChat(id).getOrNull()
    }

    suspend fun refreshChat(chatId: Long): Chat? = client.getChat(chatId).getOrNull()

    // ── History ──────────────────────────────────────────────────────────────

    /** Up to [limit] messages strictly after server id [afterServerId], oldest first. */
    suspend fun historyAfter(chatId: Long, afterServerId: Long, limit: Int = 50): Result<List<Message>> {
        val from = (afterServerId + 1) shl 20
        var lastErr: String? = null
        repeat(4) { attempt ->
            when (val r = client.getChatHistory(chatId, from, -(limit - 1), limit, false)) {
                is TdlResult.Success -> {
                    val msgs = r.result.messages.filterNotNull()
                        .filter { it.id.serverId() > afterServerId }
                        .sortedBy { it.id }
                    if (msgs.isNotEmpty()) return Result.success(msgs)
                }
                is TdlResult.Failure -> lastErr = "${r.code}: ${r.message}"
            }
            delay(700L * (attempt + 1))
        }
        return if (lastErr != null) Result.failure(RuntimeException(lastErr)) else Result.success(emptyList())
    }

    suspend fun latestServerId(chatId: Long): Long? {
        val r = client.getChatHistory(chatId, 0, 0, 1, false).getOrNull() ?: return null
        return r.messages.filterNotNull().maxOfOrNull { it.id.serverId() } ?: 0L
    }

    // ── Sending ──────────────────────────────────────────────────────────────

    /** Sends and waits until Telegram confirms (or rejects) the message. */
    suspend fun sendAndWait(chatId: Long, content: InputMessageContent): SendOutcome {
        val tmp = when (val r = client.sendMessage(chatId = chatId, inputMessageContent = content)) {
            is TdlResult.Failure -> return SendOutcome.Failed(r.code, r.message, retryAfter(r.code, r.message))
            is TdlResult.Success -> r.result
        }
        if (tmp.sendingState == null) return SendOutcome.Sent(tmp.id)
        val waiter = CompletableDeferred<SendOutcome>()
        pendingSends[tmp.id] = waiter
        earlySends.remove(tmp.id)?.let { early ->
            pendingSends.remove(tmp.id)
            return early
        }
        return withTimeoutOrNull(3.hours) { waiter.await() }
            ?: SendOutcome.Failed(-1, "timeout", 0).also { pendingSends.remove(tmp.id) }
    }

    private fun completeSend(oldId: Long, outcome: SendOutcome) {
        val waiter = pendingSends.remove(oldId)
        if (waiter != null) waiter.complete(outcome) else earlySends[oldId] = outcome
    }

    // ── Files ────────────────────────────────────────────────────────────────

    suspend fun download(file: TdFile): Result<String> {
        if (file.local.isDownloadingCompleted && File(file.local.path).exists()) return Result.success(file.local.path)
        return when (val r = client.downloadFile(file.id, 16, 0, 0, true)) {
            is TdlResult.Success ->
                if (r.result.local.isDownloadingCompleted) Result.success(r.result.local.path)
                else Result.failure(RuntimeException("ההורדה לא הושלמה"))
            is TdlResult.Failure -> Result.failure(RuntimeException("${r.code}: ${r.message}"))
        }
    }

    suspend fun deleteLocal(fileId: Int) {
        client.deleteFile(fileId)
    }

    companion object {
        private val retryRegex = Regex("""retry after (\d+)""", RegexOption.IGNORE_CASE)

        fun retryAfter(code: Int, message: String): Int {
            if (code != 429 && !message.contains("FLOOD", ignoreCase = true)) return 0
            return retryRegex.find(message)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(\d+)""").find(message)?.value?.toIntOrNull() ?: 30
        }
    }
}

fun Long.serverId(): Long = this shr 20
