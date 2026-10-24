package org.tgbackup.app.engine

import dev.g000sha256.tdl.dto.Chat
import dev.g000sha256.tdl.dto.Message
import dev.g000sha256.tdl.dto.MessageText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tgbackup.app.data.Job
import org.tgbackup.app.data.JobMode
import org.tgbackup.app.data.Progress
import org.tgbackup.app.data.Store
import org.tgbackup.app.tg.AccountClient
import org.tgbackup.app.tg.SendOutcome
import org.tgbackup.app.tg.getOrNull
import org.tgbackup.app.tg.serverId
import kotlin.random.Random
import io.sentry.Sentry
import io.sentry.SentryLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class RateLimiter(private val perMinute: Int) {
    private val stamps = ArrayDeque<Long>()
    private val mutex = Mutex()

    suspend fun acquire(onWait: (Long) -> Unit) {
        if (perMinute <= 0) return
        while (true) {
            val wait = mutex.withLock {
                val now = System.currentTimeMillis()
                while (stamps.isNotEmpty() && now - stamps.first() >= 60_000) stamps.removeFirst()
                if (stamps.size < perMinute) {
                    stamps.addLast(now)
                    0L
                } else {
                    60_000 - (now - stamps.first())
                }
            }
            if (wait <= 0) return
            onWait(wait)
            delay(wait)
        }
    }
}

/** Callbacks from a runner to the UI-facing job status. */
interface JobReporter {
    fun phase(text: String)
    fun position(lastId: Long)
    fun copied()

    /** A message was sent to [chatId]; [serverId] is its id as shown in t.me links. */
    fun delivered(chatId: Long, serverId: Long)
    fun failed()
    fun sourceInfo(chatId: Long, title: String, isProtected: Boolean)
}

/**
 * Runs one job until cancelled. Progress is persisted after every message, so a
 * restart (crash, reboot, manual stop) resumes exactly where it left off.
 */
class JobRunner(
    private val job: Job,
    private val accounts: List<AccountClient>,
    private val store: Store,
    private val log: LogBus,
    private val paused: StateFlow<Boolean>,
    private val report: JobReporter,
    private val clearStartFrom: () -> Unit,
) {
    private val tag = job.name.ifBlank { job.id }
    private var protected = false
    private val limiters = accounts.associate { it.account.id to RateLimiter(job.perMinuteLimit) }

    private class Pair2(val source: Chat, val target: Chat)

    suspend fun run() = coroutineScope {
        for (ac in accounts) {
            if (!ac.isReady) {
                report.phase("ממתין לחיבור החשבון ${ac.tag}")
                log.warn("[$tag] החשבון ${ac.tag} לא מחובר — ממתין לחיבור")
                ac.awaitReady()
            }
        }
        report.phase("מאתר ערוצים")
        val chats = accounts.map { ac ->
            val source = ac.resolveChat(job.source).getOrThrow()
            val target = ac.resolveChat(job.target).getOrThrow()
            Pair2(source, target)
        }
        // An opened chat keeps its loaded messages in TDLib memory, which copying relies on.
        accounts.forEachIndexed { i, ac -> ac.client.openChat(chats[i].source.id) }
        val source = chats[0].source
        protected = source.hasProtectedContent
        report.sourceInfo(source.id, source.title, protected)
        if (protected) log.warn("[$tag] ערוץ המקור מוגן — מצב הורדה והעלאה מחדש")

        if (job.startFromId > 0) {
            store.saveProgress(job.id) { Progress(lastId = job.startFromId - 1) }
            clearStartFrom()
            log.info("[$tag] מתחיל מהודעה ${job.startFromId}")
        }

        val fast = job.mode == JobMode.FAST && accounts.size > 1
        log.info(
            "[$tag] פעיל: ${source.title} → ${chats[0].target.title} | ${job.mediaType.label} | " +
                "${accounts.size} חשבונות | ${if (fast) "מהיר" else "מסודר"}",
        )
        if (fast) runFast(chats) else runOrdered(chats)
    }

    private suspend fun awaitNotPaused() {
        if (paused.value) {
            report.phase("מושהה")
            paused.first { !it }
        }
    }

    // ── Ordered: one sender keeps the source order; with a protected source the
    //    other accounts download ahead of it in parallel. ─────────────────────────

    private suspend fun runOrdered(chats: List<Pair2>) = coroutineScope {
        val sender = accounts[0]
        val src = chats[0].source.id
        val dst = chats[0].target.id
        var last = store.progress(job.id).lastId
        report.position(last)

        while (true) {
            ensureActive()
            awaitNotPaused()
            report.phase("סורק הודעות אחרי $last")
            var batch = sender.historyAfter(src, last).getOrElse { e ->
                log.error("[$tag] קריאת היסטוריה נכשלה: ${e.message}")
                report.phase("שגיאת רשת — מנסה שוב")
                delay(15_000)
                null
            } ?: continue

            if (batch.isEmpty()) {
                val latest = sender.latestServerId(src) ?: last
                if (latest <= last) {
                    report.phase("מעודכן — ממתין להודעות חדשות")
                    delay(60_000)
                    continue
                }
                // Newer messages exist but none came back: check the next range explicitly.
                val next = minOf(last + 50, latest)
                batch = sender.range(src, last, next - last).getOrElse { e ->
                    log.error("[$tag] קריאת היסטוריה נכשלה: ${e.message}")
                    delay(15_000)
                    null
                } ?: continue
                if (batch.isEmpty()) {
                    // A run of deleted message ids.
                    last = next
                    store.saveProgress(job.id) { it.copy(lastId = last) }
                    report.position(last)
                    continue
                }
            }

            val wanted = batch.filter { it.content.matches(job.mediaType) }
            val prefetch = HashMap<Long, Deferred<Result<Copier.Downloaded>>>()
            val window = if (protected) maxOf(2, accounts.size * 2) else 0

            for (msg in batch) {
                ensureActive()
                awaitNotPaused()
                val idx = wanted.indexOf(msg)
                if (idx >= 0) {
                    if (window > 0) startPrefetch(this, wanted, idx, window, prefetch)
                    report.phase("מעתיק הודעה ${msg.id.serverId()}")
                    deliver(sender, dst, msg, prefetch.remove(msg.id))
                } else {
                    log.info("[$tag] דילוג על ${msg.id.serverId()} (לא תואם לסינון)")
                }
                last = msg.id.serverId()
                store.saveProgress(job.id) { it.copy(lastId = last) }
                report.position(last)
            }
            prefetch.values.forEach { it.cancel() }
        }
    }

    private fun startPrefetch(
        scope: CoroutineScope,
        wanted: List<Message>,
        from: Int,
        window: Int,
        prefetch: HashMap<Long, Deferred<Result<Copier.Downloaded>>>,
    ) {
        for (j in from until minOf(from + window, wanted.size)) {
            val m = wanted[j]
            if (m.id in prefetch || !needsUpload(m)) continue
            val helper = accounts[j % accounts.size]
            prefetch[m.id] = scope.async { downloadWith(helper, accounts[0], m) }
        }
    }

    private fun needsUpload(m: Message) = (protected || !m.canBeSaved) && m.content.mainFile() != null

    /**
     * Downloads the main file of [m] (which was read by [owner]) using account [ac].
     * File ids are local to each TDLib instance, so a different account loads its own copy of the message.
     */
    private suspend fun downloadWith(ac: AccountClient, owner: AccountClient, m: Message): Result<Copier.Downloaded> {
        val own = if (ac === owner) m else ac.client.getMessage(m.chatId, m.id).getOrNull() ?: m
        val holder = if (own === m) owner else ac
        val file = own.content.mainFile() ?: return Result.failure(RuntimeException("אין קובץ בהודעה"))
        return holder.download(file).map { Copier.Downloaded(holder, file.id, it) }
    }

    // ── Fast: each account copies its own slice of every wave of messages. ───────

    private suspend fun runFast(chats: List<Pair2>) = coroutineScope {
        val n = accounts.size
        val chunk = 25
        while (true) {
            ensureActive()
            awaitNotPaused()
            var p = store.progress(job.id)
            report.position(p.lastId)
            if (p.waveEnd <= p.lastId) {
                val latest = accounts[0].latestServerId(chats[0].source.id)
                if (latest == null) {
                    report.phase("שגיאת רשת — מנסה שוב")
                    delay(30_000)
                    continue
                }
                if (latest <= p.lastId) {
                    report.phase("מעודכן — ממתין להודעות חדשות")
                    delay(60_000)
                    continue
                }
                val ws = p.lastId + 1
                val we = minOf(p.lastId + n * chunk, latest)
                store.saveProgress(job.id) { it.copy(waveStart = ws, waveEnd = we, workerLast = emptyMap()) }
                p = store.progress(job.id)
                log.info("[$tag] סבב חדש: הודעות $ws–$we")
            } else {
                log.info("[$tag] ממשיך סבב קיים: ${p.waveStart}–${p.waveEnd}")
            }
            val wave = p
            report.phase("סבב ${wave.waveStart}–${wave.waveEnd}")
            (0 until n).mapNotNull { i ->
                val rs = wave.waveStart + i * chunk
                if (rs > wave.waveEnd) return@mapNotNull null
                val re = minOf(rs + chunk - 1, wave.waveEnd)
                async { rangeWorker(i, chats[i], rs, re) }
            }.awaitAll()
            store.saveProgress(job.id) { it.copy(lastId = it.waveEnd, waveStart = 0, waveEnd = 0, workerLast = emptyMap()) }
            report.position(wave.waveEnd)
        }
    }

    private suspend fun rangeWorker(i: Int, chats: Pair2, rs: Long, re: Long) {
        val ac = accounts[i]
        var last = maxOf(rs - 1, store.progress(job.id).workerLast[i] ?: 0)
        while (last < re) {
            awaitNotPaused()
            val batch = ac.historyAfter(chats.source.id, last).getOrElse { e ->
                log.error("[$tag · ${ac.tag}] קריאת היסטוריה נכשלה: ${e.message}")
                delay(15_000)
                null
            } ?: continue
            var inRange = batch.filter { it.id.serverId() <= re }
            if (batch.isEmpty()) {
                // Nothing came back — read the rest of the range explicitly before calling it done.
                inRange = ac.range(chats.source.id, last, re - last).getOrElse { e ->
                    log.error("[$tag · ${ac.tag}] קריאת היסטוריה נכשלה: ${e.message}")
                    delay(15_000)
                    null
                } ?: continue
            }
            if (inRange.isEmpty()) break
            for (msg in inRange) {
                awaitNotPaused()
                if (msg.content.matches(job.mediaType)) {
                    deliver(ac, chats.target.id, msg, null)
                }
                last = msg.id.serverId()
                store.saveProgress(job.id) { it.copy(workerLast = it.workerLast + (i to last)) }
            }
        }
    }

    // ── Delivery of one message, with FloodWait handled by retrying the same message. ──

    private suspend fun deliver(
        sender: AccountClient,
        target: Long,
        msg: Message,
        prefetched: Deferred<Result<Copier.Downloaded>>?,
    ): Boolean {
        val sid = msg.id.serverId()
        val who = if (accounts.size > 1) "$tag · ${sender.tag}" else tag
        var attempts = 0
        var downloaded: Copier.Downloaded? = null
        var uploadCopy: File? = null
        // Set when a server-side copy of this message failed: retry it as download + upload.
        var forceUpload = false
        try {
            while (true) {
                awaitNotPaused()
                val upload = needsUpload(msg) || (forceUpload && msg.content.mainFile() != null)
                if (protected && !upload && msg.content !is MessageText) {
                    log.warn("[$who] הודעה $sid מסוג שלא ניתן להעתיק מערוץ מוגן — דילוג")
                    return false
                }
                val content = if (upload) {
                    if (downloaded == null) {
                        report.phase("מוריד הודעה $sid")
                        downloaded = (prefetched?.await() ?: downloadWith(sender, sender, msg)).getOrElse { e ->
                            log.error("[$who] הורדת הודעה $sid נכשלה: ${e.message}")
                            null
                        }
                    }
                    val d = downloaded
                    if (d == null) {
                        if (++attempts >= 3) {
                            report.failed()
                            return false
                        }
                        delay(5_000)
                        continue
                    }
                    // Upload from a fresh copy: given TDLib's own cached path, TDLib would reuse the
                    // protected remote file and Telegram rejects it as "can't be forwarded".
                    val copy = uploadCopy ?: freshCopy(sender, sid, d.path).getOrElse { e ->
                        log.error("[$who] הכנת קובץ להעלאה נכשלה: ${e.message}")
                        report.failed()
                        return false
                    }.also { uploadCopy = it }
                    Copier.uploadContent(msg, copy.absolutePath, job.stripLinks)
                } else {
                    Copier.copyContent(msg, job.stripLinks)
                }
                if (content == null) {
                    log.info("[$who] דילוג על $sid (ריק אחרי ניקוי קישורים)")
                    return false
                }

                limiters.getValue(sender.account.id).acquire { ms ->
                    report.phase("תקרת ${job.perMinuteLimit} הודעות לדקה — ממתין ${ms / 1000} שנ'")
                }
                report.phase(if (upload) "מעלה הודעה $sid" else "מעתיק הודעה $sid")
                when (val r = sender.sendAndWait(target, content)) {
                    is SendOutcome.Sent -> {
                        report.copied()
                        report.delivered(target, r.messageId.serverId())
                        log.success("[$who] הועתקה הודעה $sid")
                        if (!protected) delay(Random.nextLong(1_400, 3_800))
                        return true
                    }
                    is SendOutcome.Failed -> {
                        if (r.retryAfterSec > 0) {
                            log.warn("[$who] FloodWait ${r.retryAfterSec} שנ' — ממתין ומנסה שוב את הודעה $sid")
                            report.phase("FloodWait — ממתין ${r.retryAfterSec} שנ'")
                            delay((r.retryAfterSec + 1) * 1000L)
                            continue
                        }
                        if (!upload && msg.content.mainFile() != null) {
                            forceUpload = true
                            if (Copier.isProtectedError(r.message)) {
                                protected = true
                                log.warn("[$who] זוהה ערוץ מוגן — עובר למצב הורדה והעלאה")
                            } else {
                                log.warn("[$who] העתקה ישירה של $sid נכשלה (${r.message}) — מנסה בהורדה והעלאה")
                            }
                            continue
                        }
                        log.error("[$who] שליחת הודעה $sid נכשלה: ${r.code} ${r.message}")
                        if (attempts == 2) {
                            Sentry.captureMessage("Send failed: ${r.code} ${r.message}", SentryLevel.WARNING) { s ->
                                s.setTag("job", tag)
                                s.setExtra("message_id", sid.toString())
                                s.setExtra("upload", upload.toString())
                            }
                        }
                        // Reload the source message in case TDLib dropped it from memory.
                        sender.client.getMessage(msg.chatId, msg.id)
                        if (++attempts >= 3) {
                            report.failed()
                            return false
                        }
                        delay(3_000L * attempts)
                    }
                }
            }
        } finally {
            downloaded?.let { runCatching { it.owner.deleteLocal(it.fileId) } }
            uploadCopy?.parentFile?.deleteRecursively()
        }
    }

    private suspend fun freshCopy(sender: AccountClient, sid: Long, path: String): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val src = File(path)
                val dir = File(sender.baseDir, "upload/$sid").apply { mkdirs() }
                File(dir, src.name).also { src.copyTo(it, overwrite = true) }
            }
        }
}
