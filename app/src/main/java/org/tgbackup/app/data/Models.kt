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
)

@Serializable
enum class MediaType(val label: String) {
    VIDEO_DOC("וידאו + קבצים"),
    PHOTO("תמונות בלבד"),
    ALL_MEDIA("כל המדיה (ללא מדבקות)"),
    TEXT("טקסט בלבד"),
    ALL("הכל"),
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
    val perMinuteLimit: Int = 25,
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
)
