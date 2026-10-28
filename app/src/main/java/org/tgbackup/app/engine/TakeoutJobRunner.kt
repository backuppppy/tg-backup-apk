package org.tgbackup.app.engine

import io.sentry.Sentry
import io.sentry.SentryLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.tgbackup.app.data.Job
import org.tgbackup.app.data.JobMode
import org.tgbackup.app.data.MediaType
import org.tgbackup.app.data.Progress
import org.tgbackup.app.data.Store
import org.tgbackup.app.export.ExportManager
import org.tgbackup.app.tg.AccountClient
import org.tgbackup.app.tg.SendOutcome
import org.tgbackup.app.tg.getOrNull
import org.tgbackup.app.tg.serverId
import org.tgbackup.copy.CopyKind
import org.tgbackup.copy.CopyPeer
import org.tgbackup.copy.TakeoutCopier
import org.tgbackup.mtproto.RpcError
import org.tgbackup.mtproto.tl.MTPDmessage
import kotlin.coroutines.coroutineContext
import java.io.File
import kotlin.random.Random

/** Media-type filter for messages read over MTProto (same meaning as [matches] for TDLib messages). */
fun CopyKind.matches(type: MediaType): Boolean {
    val fileLike = this == CopyKind.VIDEO || this == CopyKind.DOCUMENT || this == CopyKind.AUDIO ||
        this == CopyKind.ANIMATION || this == CopyKind.VOICE || this == CopyKind.ROUND
    return when (type) {
        MediaType.VIDEO_DOC -> fileLike
        MediaType.PHOTO -> this == CopyKind.PHOTO
        MediaType.ALL_MEDIA -> fileLike || this == CopyKind.PHOTO
        MediaType.TEXT -> this == CopyKind.TEXT
        MediaType.ALL -> true
    }
}

/**
 * Copies without downloading: reads the source through the account's export (takeout)
 * session and sends every file by reference. Works for protected and restricted
 * channels too. Supports multiple accounts in FAST mode; ORDERED mode uses the first
 * account only. Progress is shared with [JobRunner].
 *
 * Telegram still refuses some files of protected channels (CHAT_FORWARDS_RESTRICTED —
 * seen for files that came into the channel from elsewhere). Those messages fall back
 * to TDLib download + upload using the corresponding account, like [JobRunner].
 */
class TakeoutJobRunner(
    private val job: Job,
    private val accounts: List<AccountClient>,
    private val exports: ExportManager,
    private val store: Store,
    private val log: LogBus,
    private val paused: StateFlow<Boolean>,
    private val report: JobReporter,
    private val clearStartFrom: () -> Unit,
) {
    private val tag = job.name.ifBlank { job.id }
    private val limiters = accounts.associate { it.account.id to RateLimiter(job.perMinuteLimit) }
    private var sourceChatId = 0L
    private var targetChatId = 0L

    private inner class Worker(
        val account: AccountClient,
        val copier: TakeoutCopier,
        val src: CopyPeer,
        val dst: CopyPeer,
    )

    suspend fun run() = coroutineScope {
        for (ac in accounts) {
            if (!ac.isReady) {
                report.phase("ממתין לחיבור החשבון ${ac.tag}")
                ac.awaitReady()
            }
        }
        report.phase("מאתר ערוצים")
        val source = accounts[0].resolveChat(job.source).getOrThrow()
        val target = accounts[0].resolveChat(job.target).getOrThrow()
        report.sourceInfo(source.id, source.title, source.hasProtectedContent)
        sourceChatId = source.id
        targetChatId = target.id
        // An opened chat keeps its loaded messages in TDLib memory, which the re-upload fallback reads.
        accounts.forEach { it.client.openChat(source.id) }

        report.phase(if (accounts.size > 1) "מחבר ${accounts.size} סשני ייצוא" else "מחבר את סשן הייצוא")
        val workers = accounts.map { ac ->
            val client = exports.copyClient(ac.account).getOrElse { e ->
                error("סשן הייצוא לא מחובר עבור ${ac.tag}: ${e.message}")
            }
            val copier = TakeoutCopier(client) { log.info("[${tag}·${ac.tag}] $it") }
            val peers = copier.resolve(listOf(bare(source.id), bare(target.id)))
            val src = peers[bare(source.id)] ?: error("ערוץ המקור לא נמצא בסשן הייצוא של ${ac.tag}")
            val dst = peers[bare(target.id)] ?: error("ערוץ היעד לא נמצא בסשן הייצוא של ${ac.tag}")
            Worker(ac, copier, src, dst)
        }

        try {
            if (job.startFromId > 0) {
                store.saveProgress(job.id) { Progress(lastId = job.startFromId - 1, viaTakeout = true) }
                clearStartFrom()
                log.info("[$tag] מתחיל מהודעה ${job.startFromId}")
            } else {
                val existing = store.progress(job.id)
                if (!existing.viaTakeout && existing.lastId > 0) {
                    store.saveProgress(job.id) { Progress(viaTakeout = true) }
                    log.info("[$tag] מאפס progress ממצב רגיל (lastId=${existing.lastId}) — מתחיל מההתחלה")
                }
            }

            val fast = job.mode == JobMode.FAST && accounts.size > 1
            log.info(
                "[$tag] פעיל בלי הורדה (סשן ייצוא): ${workers[0].src.title} → ${workers[0].dst.title} | " +
                    "${job.mediaType.label} | ${accounts.size} חשבונות | ${if (fast) "מהיר" else "מסודר"}",
            )
            if (fast) runFast(workers) else loop(workers[0])
        } finally {
            withContext(NonCancellable) { workers.forEach { it.copier.finish() } }
        }
    }

    // ── Ordered mode: first account only ──────────────────────────────────────

    private suspend fun loop(w: Worker) {
        var last = store.progress(job.id).lastId
        report.position(last)
        while (true) {
            coroutineContext.ensureActive()
            awaitNotPaused()
            report.phase("סורק הודעות אחרי $last")
            val batch = try {
                w.copier.historyAfter(w.src, last.toInt())
            } catch (e: CancellationException) {
                throw e
            } catch (e: RpcError) {
                if (e.type.startsWith("TAKEOUT_INIT_DELAY")) {
                    report.phase("ממתין לאישור הייצוא בטלגרם")
                    log.warn("[$tag] טלגרם מבקש לאשר את הייצוא — אשר בהודעת השירות של טלגרם, ממשיך אוטומטית אחרי האישור")
                    delay(60_000)
                    continue
                }
                log.error("[$tag] קריאת היסטוריה נכשלה: ${e.message}")
                report.phase("שגיאה — מנסה שוב")
                delay(15_000)
                continue
            } catch (e: Exception) {
                log.error("[$tag] קריאת היסטוריה נכשלה: ${e.message}")
                report.phase("שגיאת רשת — מנסה שוב")
                delay(15_000)
                continue
            }
            if (batch.isEmpty()) {
                val latest = try {
                    w.copier.latestId(w.src).toLong()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("[$tag] שגיאת רשת בקריאת ID אחרון: ${e.message}")
                    report.phase("שגיאת רשת — מנסה שוב")
                    delay(15_000)
                    continue
                }
                if (latest <= last) {
                    report.phase("מעודכן — ממתין להודעות חדשות")
                    delay(60_000)
                    continue
                }
                // Messages exist beyond 'last' but historyAfter returned nothing — gap of deleted ids.
                val next = minOf(last + 50, latest)
                last = next
                store.saveProgress(job.id) { it.copy(lastId = last, viaTakeout = true) }
                report.position(last)
                continue
            }
            for (m in batch) {
                coroutineContext.ensureActive()
                awaitNotPaused()
                val id = TakeoutCopier.idOf(m)
                if (m is MTPDmessage) {
                    val kind = TakeoutCopier.kindOf(m)
                    if (kind.matches(job.mediaType)) deliver(w, m)
                    else log.info("[$tag] דילוג על $id (לא תואם לסינון)")
                }
                last = id.toLong()
                store.saveProgress(job.id) { it.copy(lastId = last, viaTakeout = true) }
                report.position(last)
            }
        }
    }

    // ── Fast mode: each account handles its own slice ──────────────────────────

    private suspend fun runFast(workers: List<Worker>) = coroutineScope {
        val n = workers.size
        val chunk = 25
        while (true) {
            coroutineContext.ensureActive()
            awaitNotPaused()
            var p = store.progress(job.id)
            report.position(p.lastId)
            if (p.waveEnd <= p.lastId) {
                val latest = try {
                    workers[0].copier.latestId(workers[0].src).toLong()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("[$tag] שגיאת רשת — מנסה שוב")
                    report.phase("שגיאת רשת — מנסה שוב")
                    delay(30_000)
                    continue
                }
                if (latest <= p.lastId) {
                    report.phase("מעודכן — ממתין להודעות חדשות")
                    delay(60_000)
                    continue
                }
                // Jump past runs of missing ids (e.g. a channel whose first message is 34446) instead of
                // spending a whole wave on each empty slice.
                val next = try {
                    workers[0].copier.historyAfter(workers[0].src, p.lastId.toInt(), 1)
                        .firstOrNull()?.let { TakeoutCopier.idOf(it).toLong() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("[$tag] שגיאת רשת — מנסה שוב")
                    report.phase("שגיאת רשת — מנסה שוב")
                    delay(30_000)
                    continue
                }
                val ws = next ?: (p.lastId + 1)
                if (ws > p.lastId + 1) log.info("[$tag] מדלג על IDs ריקים ${p.lastId + 1}–${ws - 1}")
                val we = minOf(ws + n * chunk - 1, latest)
                store.saveProgress(job.id) { it.copy(waveStart = ws, waveEnd = we, workerLast = emptyMap(), viaTakeout = true) }
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
                async { rangeWorker(i, workers[i], rs, re) }
            }.awaitAll()
            store.saveProgress(job.id) {
                it.copy(lastId = it.waveEnd, waveStart = 0, waveEnd = 0, workerLast = emptyMap(), viaTakeout = true)
            }
            report.position(wave.waveEnd)
        }
    }

    private suspend fun rangeWorker(i: Int, w: Worker, rs: Long, re: Long) {
        var last = maxOf(rs - 1, store.progress(job.id).workerLast[i] ?: 0)
        while (last < re) {
            awaitNotPaused()
            val batch = try {
                w.copier.historyAfter(w.src, last.toInt())
            } catch (e: CancellationException) {
                throw e
            } catch (e: RpcError) {
                if (e.type.startsWith("TAKEOUT_INIT_DELAY")) {
                    report.phase("ממתין לאישור הייצוא בטלגרם")
                    log.warn("[$tag·${w.account.tag}] טלגרם מבקש לאשר את הייצוא — אשר בהודעת השירות")
                    delay(60_000)
                    continue
                }
                log.error("[$tag·${w.account.tag}] קריאת היסטוריה נכשלה: ${e.message}")
                delay(15_000)
                continue
            } catch (e: Exception) {
                log.error("[$tag·${w.account.tag}] קריאת היסטוריה נכשלה: ${e.message}")
                delay(15_000)
                continue
            }
            if (batch.isEmpty()) break  // MTProto skips deleted ids natively; empty = range exhausted
            val inRange = batch.filter { TakeoutCopier.idOf(it) <= re.toInt() }
            if (inRange.isEmpty()) break
            for (m in inRange) {
                coroutineContext.ensureActive()
                awaitNotPaused()
                val id = TakeoutCopier.idOf(m)
                if (m is MTPDmessage && TakeoutCopier.kindOf(m).matches(job.mediaType)) {
                    deliver(w, m)
                } else if (m is MTPDmessage) {
                    log.info("[$tag·${w.account.tag}] דילוג על $id (לא תואם לסינון)")
                }
                last = id.toLong()
                store.saveProgress(job.id) { it.copy(workerLast = it.workerLast + (i to last), viaTakeout = true) }
            }
        }
    }

    // ── Delivery ───────────────────────────────────────────────────────────────

    private suspend fun deliver(w: Worker, m: MTPDmessage) {
        val who = if (accounts.size > 1) "$tag·${w.account.tag}" else tag
        var attempts = 0
        while (true) {
            awaitNotPaused()
            limiters.getValue(w.account.account.id).acquire { ms ->
                report.phase("תקרת ${job.perMinuteLimit} הודעות לדקה — ממתין ${ms / 1000} שנ'")
            }
            report.phase("מעתיק הודעה ${m.vid}")
            try {
                when (val r = w.copier.send(w.src, w.dst, m, job.stripLinks)) {
                    is TakeoutCopier.Sent.Ok -> {
                        report.copied()
                        if (r.messageId > 0) report.delivered(targetChatId, r.messageId.toLong())
                        log.success("[$who] הועתקה הודעה ${m.vid} (בלי הורדה)")
                        delay(Random.nextLong(1_400, 3_800))
                    }
                    is TakeoutCopier.Sent.Skipped -> log.info("[$who] דילוג על ${m.vid} (${r.why})")
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: TakeoutCopier.ForwardsRestricted) {
                log.warn("[$who] טלגרם חוסם שליחה בלי הורדה של הודעה ${m.vid} (${e.reason}) — מוריד ומעלה מחדש")
                reportReupload(w, m, e.reason, e.details, e.cause?.message ?: "")
                reupload(w.account, m.vid)
                return
            } catch (e: RpcError) {
                if (e.type != "CHAT_FORWARDS_RESTRICTED") {
                    if (fail(m, who, "${e.code} ${e.type}", ++attempts)) return
                    continue
                }
                log.warn("[$who] טלגרם חוסם שליחה בלי הורדה של הודעה ${m.vid} — מוריד ומעלה מחדש")
                reportReupload(w, m, "restricted-without-check", emptyMap(), "${e.code} ${e.type}")
                reupload(w.account, m.vid)
                return
            } catch (e: Exception) {
                if (fail(m, who, e.message ?: e.javaClass.simpleName, ++attempts)) return
            }
        }
    }

    /** Every fallback to download+upload goes to Sentry, grouped by reason and media kind. */
    private fun reportReupload(w: Worker, m: MTPDmessage, reason: String, details: Map<String, String>, error: String) {
        val kind = TakeoutCopier.kindOf(m).name
        Sentry.captureMessage("Takeout copy fell back to download+upload: $reason ($kind)", SentryLevel.WARNING) { s ->
            s.setFingerprint(listOf("takeout-reupload", reason, kind))
            s.setTag("job", tag)
            s.setTag("reason", reason)
            s.setTag("kind", kind)
            s.setTag("source_protected", w.src.isNoForwards.toString())
            s.setTag("account_id", w.account.account.id)
            s.setExtra("message_id", m.vid.toString())
            s.setExtra("source", "${w.src.title} (${w.src.bareId})")
            s.setExtra("link", "https://t.me/c/${w.src.bareId}/${m.vid}")
            s.setExtra("target", "${w.dst.title} (${w.dst.bareId})")
            s.setExtra("error", error)
            details.forEach { (k, v) -> s.setExtra(k, v) }
        }
    }

    /** Logs a failed send; returns true when the message is given up on. */
    private suspend fun fail(m: MTPDmessage, who: String, text: String, attempts: Int): Boolean {
        log.error("[$who] שליחת הודעה ${m.vid} נכשלה: $text")
        if (attempts >= 3) {
            Sentry.captureMessage("Takeout send failed: $text", SentryLevel.WARNING) { s ->
                s.setTag("job", tag)
                s.setExtra("message_id", m.vid.toString())
            }
            report.failed()
            return true
        }
        delay(3_000L * attempts)
        return false
    }

    /** Downloads message [id] of the source with TDLib and uploads it to [targetChatId]. */
    private suspend fun reupload(account: AccountClient, id: Int) {
        val msg = account.client.getMessage(sourceChatId, id.toLong() shl 20).getOrNull()
        val file = msg?.content?.mainFile()
        if (msg == null || file == null) {
            log.error("[$tag] הודעה $id לא נטענה ב-TDLib — לא ניתן להוריד אותה")
            report.failed()
            return
        }
        var fileId = 0
        var copy: File? = null
        try {
            var attempts = 0
            while (true) {
                awaitNotPaused()
                report.phase("מוריד הודעה $id")
                val path = account.download(file).getOrElse { e ->
                    log.error("[$tag] הורדת הודעה $id נכשלה: ${e.message}")
                    null
                }
                if (path == null) {
                    if (++attempts >= 3) {
                        report.failed()
                        return
                    }
                    delay(5_000)
                    continue
                }
                fileId = file.id
                // Upload from a fresh copy: given TDLib's cached path, TDLib would reuse the protected remote file.
                val c = copy ?: withContext(Dispatchers.IO) {
                    val src = File(path)
                    val dir = File(account.baseDir, "upload/$id").apply { mkdirs() }
                    File(dir, src.name).also { src.copyTo(it, overwrite = true) }
                }.also { copy = it }
                val content = Copier.uploadContent(msg, c.absolutePath, job.stripLinks) ?: run {
                    log.info("[$tag] דילוג על $id (סוג שלא ניתן להעלות)")
                    return
                }
                report.phase("מעלה הודעה $id")
                when (val r = account.sendAndWait(targetChatId, content)) {
                    is SendOutcome.Sent -> {
                        report.copied()
                        report.delivered(targetChatId, r.messageId.serverId())
                        log.success("[$tag] הועתקה הודעה $id (הורדה והעלאה)")
                        return
                    }
                    is SendOutcome.Failed -> {
                        if (r.retryAfterSec > 0) {
                            report.phase("FloodWait — ממתין ${r.retryAfterSec} שנ'")
                            delay((r.retryAfterSec + 1) * 1000L)
                            continue
                        }
                        log.error("[$tag] העלאת הודעה $id נכשלה: ${r.code} ${r.message}")
                        if (++attempts >= 3) {
                            report.failed()
                            return
                        }
                        delay(3_000L * attempts)
                    }
                }
            }
        } finally {
            if (fileId != 0) runCatching { account.deleteLocal(fileId) }
            copy?.parentFile?.deleteRecursively()
        }
    }

    private suspend fun awaitNotPaused() {
        if (paused.value) {
            report.phase("מושהה")
            paused.first { !it }
        }
    }

    /** TDLib chat id → bare MTProto id (-100xxxx → xxxx, -xxxx → xxxx). */
    private fun bare(chatId: Long): Long = if (chatId <= -CHANNEL_SHIFT) -chatId - CHANNEL_SHIFT else -chatId

    private companion object {
        const val CHANNEL_SHIFT = 1_000_000_000_000L
    }
}
