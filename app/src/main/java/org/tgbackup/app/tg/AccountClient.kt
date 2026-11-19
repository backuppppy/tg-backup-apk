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
import dev.g000sha256.tdl.dto.UpdateConnectionState
import dev.g000sha256.tdl.dto.UpdateChatHasProtectedContent
import dev.g000sha256.tdl.dto.UpdateMessageSendFailed
import dev.g000sha256.tdl.dto.UpdateMessageSendSucceeded
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
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

/** Telegram's notice that [chatId]'s forwarding protection is now [isProtected]. */
data class ProtectionNotice(val chatId: Long, val isProtected: Boolean)

fun <T> TdlResult<T>.getOrNull(): T? = (this as? TdlResult.Success)?.result
/** Telegram's answer to a bot token it does not know (as opposed to a network error or a FloodWait). */
internal fun isBadBotToken(error: String): Boolean =
    error.startsWith("401") || error.contains("TOKEN", ignoreCase = true) || error.startsWith("400")

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

    private val _protectionNotices = MutableSharedFlow<ProtectionNotice>(extraBufferCapacity = 64)

    /** Every change of a chat's forwarding protection, as TDLib reports it (a running job follows its source's). */
    val protectionNotices: SharedFlow<ProtectionNotice> = _protectionNotices.asSharedFlow()

    val tag: String get() = account.name.ifBlank { account.phone.ifBlank { if (account.isBot) "בוט" else "" } }

    /** TDLib's connection to Telegram over time (for the slow chat-finding report). */
    val connection = ConnectionLog()

    fun start() {
        // Subscribe before the first request so the initial authorization updates aren't missed.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.allUpdates.collect { update ->
                when (update) {
                    is UpdateAuthorizationState -> onAuthState(update.authorizationState)
                    is UpdateConnectionState -> connection.record(connectionLabel(update.state))
                    is UpdateChatHasProtectedContent ->
                        _protectionNotices.tryEmit(ProtectionNotice(update.chatId, update.hasProtectedContent))
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
            is AuthorizationStateWaitPhoneNumber -> if (account.isBot) {
                // A bot has no phone or code: it logs in with its token at once.
                scope.launch {
                    while (true) {
                        val error = client.checkAuthenticationBotToken(account.botToken.trim()).errorText()
                        _lastError.value = error
                        if (error == null) break
                        // A wrong token is final; a network error or a FloodWait is tried again in half a minute.
                        if (isBadBotToken(error)) {
                            _auth.value = AuthUi.Unsupported("טוקן הבוט לא תקין ($error)")
                            log.error("[$tag] התחברות הבוט נכשלה: $error")
                            break
                        }
                        log.warn("[$tag] התחברות הבוט נכשלה ($error) — מנסה שוב בעוד 30 שנ'")
                        delay(30_000)
                    }
                }
            } else {
                _auth.value = AuthUi.NeedPhone
            }
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
        // The cache keeps how [raw] maps to a chat; the chat itself is read again, so its flags (protection) are current.
        chatCache[input]?.let { cached ->
            val fresh = client.getChat(cached.id).getOrNull() ?: cached
            chatCache[input] = fresh
            return Result.success(fresh)
        }
        val chat = findChat(input)
        if (chat != null) chatCache[input] = chat
        return chat?.let { Result.success(it) }
            ?: Result.failure(IllegalArgumentException("הצ'אט '$input' לא נמצא — ודא שהחשבון $tag חבר בו"))
    }

    private suspend fun findChat(input: String): Chat? {
        Regex("""t\.me/c/(\d+)""").find(input)?.let { m ->
            return chatByIds(listOf(-CHANNEL_SHIFT - m.groupValues[1].toLong()))
        }
        // Invite link (t.me/+hash, t.me/joinchat/hash): TDLib returns the chat id when the account is a member.
        if (Regex("""t\.me/(\+|joinchat/)|tg://join\?invite=""").containsMatchIn(input)) {
            val link = if (input.contains("://")) input else "https://$input"
            val chatId = client.checkChatInviteLink(link).getOrNull()?.chatId ?: 0L
            return if (chatId != 0L) client.getChat(chatId).getOrNull() else null
        }
        input.toLongOrNull()?.let { n -> return chatByIds(idCandidates(n)) }
        val username = Regex("""(?:t\.me/|@)?([A-Za-z][A-Za-z0-9_]{3,})/?$""").find(input.removePrefix("https://"))
            ?.groupValues?.get(1) ?: return null
        return client.searchPublicChat(username).getOrNull()
    }

    /**
     * The same chat can be written in several ways: -1001922547087 (full), 1922547087
     * (as in t.me/c/ links and some bots) or -1922547087. Bare numbers are tried as a
     * channel/supergroup first, then as a group, then as a user.
     */
    private fun idCandidates(n: Long): List<Long> = when {
        n <= -CHANNEL_SHIFT -> listOf(n)
        n < 0 -> listOf(-CHANNEL_SHIFT + n, n)
        else -> listOf(-CHANNEL_SHIFT - n, -n, n)
    }

    private suspend fun chatByIds(ids: List<Long>): Chat? {
        for (id in ids) chatById(id)?.let { return it }
        // A bot has no chat lists to load: it knows the chats it is in.
        if (account.isBot) return null
        // The chat may simply not be loaded into TDLib yet: load the chat lists page by page and stop as soon as it
        // is there. Loading every page first took minutes for an account with many chats, and made TDLib fetch the
        // full info of each one (dozens of requests to Telegram, FLOOD_WAITs).
        for (list in listOf(ChatListMain(), ChatListArchive())) {
            for (page in 0 until 30) {
                // Fails with 404 once the whole list is loaded.
                val more = client.loadChats(list, 100) !is TdlResult.Failure
                for (id in ids) client.getChat(id).getOrNull()?.let { return it }
                if (!more) break
            }
        }
        return null
    }

    private suspend fun chatById(id: Long): Chat? {
        client.getChat(id).getOrNull()?.let { return it }
        return when {
            id <= -CHANNEL_SHIFT -> {
                val sg = -id - CHANNEL_SHIFT
                if (client.getSupergroup(sg).getOrNull() != null) client.createSupergroupChat(sg, false).getOrNull() else null
            }
            id < 0 -> client.createBasicGroupChat(-id, false).getOrNull()
            else -> client.createPrivateChat(id, false).getOrNull()
        }
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

    /**
     * All messages with server ids in (afterServerId, afterServerId + span], oldest first,
     * read backwards page by page so nothing in the range is missed. Used to confirm that a
     * range is really empty before skipping over it.
     */
    suspend fun range(chatId: Long, afterServerId: Long, span: Long = 50): Result<List<Message>> {
        val out = ArrayList<Message>()
        var anchor = afterServerId + span + 1
        while (anchor > afterServerId + 1) {
            val page = when (val r = client.getChatHistory(chatId, anchor shl 20, 0, 50, false)) {
                is TdlResult.Success -> r.result.messages.filterNotNull().filter {
                    it.id.serverId() in (afterServerId + 1) until anchor
                }
                is TdlResult.Failure -> return Result.failure(RuntimeException("${r.code}: ${r.message}"))
            }
            if (page.isEmpty()) break
            out += page
            anchor = page.minOf { it.id.serverId() }
        }
        return Result.success(out.distinctBy { it.id }.sortedBy { it.id })
    }

    /**
     * The source's newest server id. TDLib keeps the chat's last message current from updates, so this is read from
     * memory; only when it has none is one history page asked from the server.
     */
    suspend fun latestServerId(chatId: Long): Long? {
        client.getChat(chatId).getOrNull()?.lastMessage?.let { return it.id.serverId() }
        val r = client.getChatHistory(chatId, 0, 0, 1, false).getOrNull() ?: return null
        return r.messages.filterNotNull().maxOfOrNull { it.id.serverId() } ?: 0L
    }

    /**
     * The messages with these server ids, oldest first, in one request per 100 ids (getMessages): a job knows which
     * ids it holds, so it never browses history (that costs Telegram many requests and FLOOD_WAITs). A deleted id is
     * simply missing from the result.
     */
    suspend fun messagesByIds(chatId: Long, serverIds: LongRange): Result<List<Message>> {
        val out = ArrayList<Message>()
        for (chunk in serverIds.chunked(100)) {
            when (val r = client.getMessages(chatId, LongArray(chunk.size) { chunk[it] shl 20 })) {
                is TdlResult.Success -> out += r.result.messages.filterNotNull()
                is TdlResult.Failure -> return Result.failure(RuntimeException("${r.code}: ${r.message}"))
            }
        }
        return Result.success(out.distinctBy { it.id }.sortedBy { it.id })
    }

    // ── Sending ──────────────────────────────────────────────────────────────

    /**
     * Sends and waits until Telegram confirms (or rejects) the message. With [stuckAfterMs], a send still pending after
     * that long is cancelled (TDLib waits out a FLOOD_WAIT on sends by itself and never says so): the pending message is
     * deleted, which stops TDLib from sending it, and [SEND_STUCK] comes back so the caller hands it to another account.
     * A request already on its way to Telegram may still land: then a duplicate, never a lost message.
     */
    suspend fun sendAndWait(chatId: Long, content: InputMessageContent, stuckAfterMs: Long? = null): SendOutcome {
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
        if (stuckAfterMs != null) {
            withTimeoutOrNull(stuckAfterMs) { waitSend(waiter) }?.let { out ->
                if (out is SendOutcome.Failed) pendingSends.remove(tmp.id)
                return out
            }
            // Deleting a pending message stops TDLib from sending it. If the request was already on its way to Telegram it
            // still lands, unseen: then the next account sends a duplicate (the user's rule: a duplicate beats a gap).
            client.deleteMessages(chatId, longArrayOf(tmp.id), true).errorText()?.let {
                log.warn("[$tag] ביטול שליחה תקועה נכשל ($it) — ייתכן שתיווצר כפילות")
            }
            withTimeoutOrNull(5.seconds) { waiter.await() }?.let { out ->
                if (out is SendOutcome.Sent) return out
            }
            pendingSends.remove(tmp.id)
            return SendOutcome.Failed(SEND_STUCK, "send stuck, cancelled", 0)
        }
        // A plain 3-hour timeout is not cancelled: TDLib may still send it (see SEND_TIMEOUT).
        return withTimeoutOrNull(3.hours) { waitSend(waiter) }?.also { if (it is SendOutcome.Failed) pendingSends.remove(tmp.id) }
            ?: SendOutcome.Failed(SEND_TIMEOUT, "timeout", 0).also { pendingSends.remove(tmp.id) }
    }

    /**
     * The send's answer, or [SEND_DISCONNECTED] when the account logs out or closes first (its TDLib queue is gone,
     * so the message will not go out later).
     */
    private suspend fun waitSend(waiter: CompletableDeferred<SendOutcome>): SendOutcome = coroutineScope {
        val lost = async {
            auth.first { it !is AuthUi.Ready }
            SendOutcome.Failed(SEND_DISCONNECTED, "account disconnected", 0)
        }
        select<SendOutcome> {
            waiter.onAwait { it }
            lost.onAwait { it }
        }.also { lost.cancel() }
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
        /** [SendOutcome.Failed] code: no answer for 3 hours; TDLib may still send it, yet it is retried (a duplicate beats a gap). */
        const val SEND_TIMEOUT = -1

        /** [SendOutcome.Failed] code: the account logged out or closed while the message was waiting (not sent). */
        const val SEND_DISCONNECTED = -2

        /** [SendOutcome.Failed] code: pending longer than the caller's limit, then cancelled (not sent). */
        const val SEND_STUCK = -3

        private const val CHANNEL_SHIFT = 1_000_000_000_000L
        private val retryRegex = Regex("""retry after (\d+)""", RegexOption.IGNORE_CASE)

        fun retryAfter(code: Int, message: String): Int {
            if (code != 429 && !message.contains("FLOOD", ignoreCase = true)) return 0
            return retryRegex.find(message)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(\d+)""").find(message)?.value?.toIntOrNull() ?: 30
        }
    }
}

fun Long.serverId(): Long = this shr 20

/** The Hebrew name of a TDLib connection state. */
fun connectionLabel(state: dev.g000sha256.tdl.dto.ConnectionState): String = when (state) {
    is dev.g000sha256.tdl.dto.ConnectionStateReady -> "מחובר"
    is dev.g000sha256.tdl.dto.ConnectionStateUpdating -> "מעדכן"
    is dev.g000sha256.tdl.dto.ConnectionStateConnecting -> "מתחבר"
    is dev.g000sha256.tdl.dto.ConnectionStateConnectingToProxy -> "מתחבר לפרוקסי"
    is dev.g000sha256.tdl.dto.ConnectionStateWaitingForNetwork -> "ממתין לרשת"
    else -> state::class.simpleName.orEmpty()
}

/**
 * The connection states TDLib reported, with their times (the last [keep]). Answers what the connection did during a
 * span: on 17-18/11 finding a chat took 157-167 s while the user's network dropped connections to Telegram.
 */
class ConnectionLog(private val keep: Int = 50, private val now: () -> Long = System::currentTimeMillis) {
    private val changes = ArrayDeque<Pair<Long, String>>()

    @Synchronized
    fun record(label: String) {
        changes.addLast(now() to label)
        while (changes.size > keep) changes.removeFirst()
    }

    /** The state now ("לא ידוע" before TDLib reported any). */
    @Synchronized
    fun current(): String = changes.lastOrNull()?.second ?: "לא ידוע"

    /**
     * The state it was in at [sinceMs] when nothing changed since; else that state and the changes after it in order,
     * each with its second from [sinceMs] ("לא ידוע" before TDLib reported any).
     */
    @Synchronized
    fun since(sinceMs: Long): String {
        val after = changes.filter { it.first >= sinceMs }
        val before = changes.lastOrNull { it.first < sinceMs }?.second
        if (after.isEmpty()) return before ?: "לא ידוע"
        val head = before?.let { "$it ← " }.orEmpty()
        return head + after.joinToString(" ← ") { (t, l) -> "$l (${(t - sinceMs) / 1000} שנ')" }
    }
}
