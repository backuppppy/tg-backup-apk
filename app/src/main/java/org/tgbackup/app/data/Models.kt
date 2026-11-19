package org.tgbackup.app.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class Account(
    val id: String = UUID.randomUUID().toString().take(8),
    val name: String = "",
    val apiId: Int = 0,
    val apiHash: String = "",
    val phone: String = "",
    /** A bot's token from @BotFather; blank for a regular account. A bot logs in with it (no phone, no code). */
    val botToken: String = "",
) {
    /** A bot helps a job send; it cannot read a chat's history, load chat lists, or open an export session. */
    val isBot: Boolean get() = botToken.isNotBlank()
}

@Serializable
enum class MediaType(val label: String) {
    VIDEO_DOC("וידאו + קבצים"),
    PHOTO("תמונות בלבד"),
    ALL_MEDIA("כל המדיה (ללא מדבקות)"),
    TEXT("טקסט בלבד"),
    ALL("הכל"),
    VIDEO("סרטונים"),
    FILE("קבצים"),
    MUSIC("מוזיקה"),
    VOICE("הודעות קוליות"),
}

@Serializable
enum class JobMode(val label: String) {
    ORDERED("📑 מסודר — שומר על הסדר של המקור"),
    FAST("⚡ מהיר — כל חשבון מטפל בקטע משלו (סדר מבולגן)"),
}

@Serializable
data class Job(
    val id: String = UUID.randomUUID().toString().take(8),
    val name: String = "",
    val source: String = "",
    val target: String = "",
    val mediaType: MediaType = MediaType.VIDEO_DOC,
    val mode: JobMode = JobMode.ORDERED,
    val accountIds: List<String> = emptyList(),
    /** One-shot: when > 0 the next run starts from this message id (inclusive) and the field is cleared. */
    val startFromId: Long = 0,
    val stripLinks: Boolean = true,
    /** Skip messages whose text or caption contains Arabic letters. */
    val skipArabic: Boolean = false,
    val perMinuteLimit: Int = 25,
    /** Copy without downloading: read through the export (takeout) session and send files by reference. */
    val viaTakeout: Boolean = false,
    /** Content types chosen in the editor (multi-select); null = a job saved before this field: use [mediaType]. */
    val mediaTypes: List<MediaType>? = null,
    /** Optional file extensions ("pdf, zip"): such files are copied in addition to [mediaTypes]. */
    val extensions: String = "",
    /** Ordered mode with several senders: messages each account sends in a row before the turn passes on (1..10). */
    val messagesPerTurn: Int = 3,
)

@Serializable
data class Config(
    val accounts: List<Account> = emptyList(),
    val jobs: List<Job> = emptyList(),
    /** Jobs that were running when the app last stopped; restarted automatically. */
    val autoResume: Set<String> = emptySet(),
)

/** Persistent per-job progress, in Telegram server message ids (the ids shown in t.me links). */
@Serializable
data class Progress(
    val lastId: Long = 0,
    val waveStart: Long = 0,
    val waveEnd: Long = 0,
    val workerLast: Map<Int, Long> = emptyMap(),
    /** True when this progress was written by TakeoutJobRunner (viaTakeout mode). */
    val viaTakeout: Boolean = false,
    /** TDLib chat id of the source this progress belongs to; 0 = unknown (progress written before this field existed). */
    val sourceId: Long = 0,
    /** Messages copied by this job over all its runs (a stop does not clear it; "reset progress" does). */
    val copiedTotal: Long = 0,
    /** Time the job spent running over all its runs, pauses excluded. */
    val runMillis: Long = 0,
    /** Server id of the source's newest message when it was last read; 0 = not known yet. */
    val latestId: Long = 0,
    /** Fast mode: the slice size the saved wave was opened with; 0 = saved before this field (25). */
    val waveChunk: Int = 0,
) {
    /** This progress with the run totals of [prev]: for the places that start the position over. */
    fun withTotalsOf(prev: Progress) =
        copy(copiedTotal = prev.copiedTotal, runMillis = prev.runMillis, latestId = prev.latestId)
}
