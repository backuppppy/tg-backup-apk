package org.tgbackup.app.engine

import io.sentry.Sentry
import io.sentry.SentryLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.tgbackup.app.data.Job
import org.tgbackup.app.data.MediaType
import org.tgbackup.app.data.Progress
import org.tgbackup.app.data.Store
import org.tgbackup.app.export.ExportManager
import org.tgbackup.app.tg.AccountClient
import org.tgbackup.copy.CopyKind
import org.tgbackup.copy.CopyPeer
import org.tgbackup.copy.TakeoutCopier
import org.tgbackup.mtproto.RpcError
import org.tgbackup.mtproto.tl.MTPDmessage
import kotlin.coroutines.coroutineContext
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
        MediaType.ALL -> this != CopyKind.OTHER
    }
}

/**
 * Copies without downloading: reads the source through the account's export (takeout)
 * session and sends every file by reference. Works for protected and restricted
 * channels too. Uses only the job's first account; progress is shared with [JobRunner].
 */
class TakeoutJobRunner(
    private val job: Job,
    private val account: AccountClient,
    private val exports: ExportManager,
    private val store: Store,
    private val log: LogBus,
    private val paused: StateFlow<Boolean>,
    private val report: JobReporter,
    private val clearStartFrom: () -> Unit,
) {
    private val tag = job.name.ifBlank { job.id }
    private val limiter = RateLimiter(job.perMinuteLimit)

    suspend fun run() {
        if (!account.isReady) {
            report.phase("ממתין לחיבור החשבון ${account.tag}")
            account.awaitReady()
        }
        report.phase("מאתר ערוצים")
        val source = account.resolveChat(job.source).getOrThrow()
        val target = account.resolveChat(job.target).getOrThrow()
        report.sourceInfo(source.id, source.title, source.hasProtectedContent)

        report.phase("מחבר את סשן הייצוא")
        val client = exports.copyClient(account.account).getOrElse { e ->
            error("סשן הייצוא לא מחובר: ${e.message}")
        }
        val copier = TakeoutCopier(client) { log.info("[$tag] $it") }
        try {
            val peers = copier.resolve(listOf(bare(source.id), bare(target.id)))
            val src = peers[bare(source.id)] ?: error("ערוץ המקור לא נמצא בסשן הייצוא")
            val dst = peers[bare(target.id)] ?: error("ערוץ היעד לא נמצא בסשן הייצוא")

            if (job.startFromId > 0) {
                store.saveProgress(job.id) { Progress(lastId = job.startFromId - 1) }
                clearStartFrom()
                log.info("[$tag] מתחיל מהודעה ${job.startFromId}")
            }
            log.info("[$tag] פעיל בלי הורדה (סשן ייצוא): ${src.title} → ${dst.title} | ${job.mediaType.label}")
            loop(copier, src, dst, target.id)
        } finally {
            withContext(NonCancellable) { copier.finish() }
        }
    }

    private suspend fun loop(copier: TakeoutCopier, src: CopyPeer, dst: CopyPeer, targetChatId: Long) {
        var last = store.progress(job.id).lastId
        report.position(last)
        while (true) {
            coroutineContext.ensureActive()
            awaitNotPaused()
            report.phase("סורק הודעות אחרי $last")
            val batch = try {
                copier.historyAfter(src, last.toInt())
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
                report.phase("מעודכן — ממתין להודעות חדשות")
                delay(60_000)
                continue
            }
            for (m in batch) {
                coroutineContext.ensureActive()
                awaitNotPaused()
                val id = TakeoutCopier.idOf(m)
                if (m is MTPDmessage) {
                    val kind = TakeoutCopier.kindOf(m)
                    if (kind.matches(job.mediaType)) deliver(copier, src, dst, targetChatId, m)
                    else log.info("[$tag] דילוג על $id (לא תואם לסינון)")
                }
                last = id.toLong()
                store.saveProgress(job.id) { it.copy(lastId = last) }
                report.position(last)
            }
        }
    }

    private suspend fun deliver(copier: TakeoutCopier, src: CopyPeer, dst: CopyPeer, targetChatId: Long, m: MTPDmessage) {
        var attempts = 0
        while (true) {
            awaitNotPaused()
            limiter.acquire { ms -> report.phase("תקרת ${job.perMinuteLimit} הודעות לדקה — ממתין ${ms / 1000} שנ'") }
            report.phase("מעתיק הודעה ${m.vid}")
            try {
                when (val r = copier.send(src, dst, m, job.stripLinks)) {
                    is TakeoutCopier.Sent.Ok -> {
                        report.copied()
                        if (r.messageId > 0) report.delivered(targetChatId, r.messageId.toLong())
                        log.success("[$tag] הועתקה הודעה ${m.vid} (בלי הורדה)")
                        delay(Random.nextLong(1_400, 3_800))
                    }
                    is TakeoutCopier.Sent.Skipped -> log.info("[$tag] דילוג על ${m.vid} (${r.why})")
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val text = (e as? RpcError)?.let { "${it.code} ${it.type}" } ?: (e.message ?: e.javaClass.simpleName)
                log.error("[$tag] שליחת הודעה ${m.vid} נכשלה: $text")
                if (++attempts >= 3) {
                    Sentry.captureMessage("Takeout send failed: $text", SentryLevel.WARNING) { s ->
                        s.setTag("job", tag)
                        s.setExtra("message_id", m.vid.toString())
                    }
                    report.failed()
                    return
                }
                delay(3_000L * attempts)
            }
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
