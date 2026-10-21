package org.tgbackup.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

class Store(private val dir: File) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val configFile = File(dir, "config_v2.json")
    private val progressDir = File(dir, "progress").apply { mkdirs() }
    private val lock = Any()

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<Config> = _config.asStateFlow()

    /** Set when settings were imported from the old Python app on this launch. */
    var migratedFromLegacy = false
        private set

    private fun loadConfig(): Config {
        if (configFile.exists()) {
            runCatching { return json.decodeFromString(Config.serializer(), configFile.readText()) }
        }
        val legacy = runCatching { importLegacy() }.getOrNull()
        if (legacy != null) {
            migratedFromLegacy = true
            writeConfig(legacy)
            return legacy
        }
        return Config()
    }

    private fun writeConfig(cfg: Config) {
        val tmp = File(dir, "config_v2.json.tmp")
        tmp.writeText(json.encodeToString(Config.serializer(), cfg))
        tmp.renameTo(configFile)
    }

    fun updateConfig(transform: (Config) -> Config) {
        synchronized(lock) {
            _config.update(transform)
            writeConfig(_config.value)
        }
    }

    fun progress(jobId: String): Progress = synchronized(lock) {
        val f = File(progressDir, "$jobId.json")
        if (!f.exists()) return Progress()
        runCatching { json.decodeFromString(Progress.serializer(), f.readText()) }.getOrDefault(Progress())
    }

    fun saveProgress(jobId: String, transform: (Progress) -> Progress) = synchronized(lock) {
        val next = transform(progress(jobId))
        val tmp = File(progressDir, "$jobId.json.tmp")
        tmp.writeText(json.encodeToString(Progress.serializer(), next))
        tmp.renameTo(File(progressDir, "$jobId.json"))
    }

    fun clearProgress(jobId: String) = synchronized(lock) {
        File(progressDir, "$jobId.json").delete()
    }

    /**
     * The old Python app kept its config.json and last_id_*.txt files in the same
     * files directory (same package name), so accounts, jobs and progress carry over.
     * Telegram sessions don't — Telethon and TDLib use different formats — so every
     * account has to log in once again.
     */
    private fun importLegacy(): Config? {
        val old = File(dir, "config.json")
        if (!old.exists()) return null
        val root = json.parseToJsonElement(old.readText()).jsonObject
        val accounts = root["accounts"]?.jsonArray.orEmpty().mapIndexed { i, el ->
            val o = el.jsonObject
            Account(
                id = "acc$i",
                name = o.str("client_name").ifBlank { "חשבון ${i + 1}" },
                apiId = o["api_id"]?.jsonPrimitive?.let { it.intOrNull ?: it.content.toIntOrNull() } ?: 0,
                apiHash = o.str("api_hash"),
                phone = o.str("phone_number"),
            )
        }
        val jobs = root["jobs"]?.jsonArray.orEmpty().map { el ->
            val o = el.jsonObject
            val id = o.str("id").ifBlank { java.util.UUID.randomUUID().toString().take(8) }
            val source = o.str("source_chat_id")
            val indices = o["account_indices"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.intOrNull }
            val mode = if (o.str("mode") == "fast") JobMode.FAST else JobMode.ORDERED
            val job = Job(
                id = id,
                name = o.str("name"),
                source = source,
                target = o.str("target_chat_id"),
                mediaType = when (o.str("media_type")) {
                    "photo" -> MediaType.PHOTO
                    "all_media" -> MediaType.ALL_MEDIA
                    "text" -> MediaType.TEXT
                    "all" -> MediaType.ALL
                    else -> MediaType.VIDEO_DOC
                },
                mode = mode,
                accountIds = indices.filter { it in accounts.indices }.map { accounts[it].id },
            )
            legacyLastId(id, source, mode)?.let { last -> saveProgress(id) { Progress(lastId = last) } }
            job
        }
        if (accounts.isEmpty() && jobs.isEmpty()) return null
        return Config(accounts = accounts, jobs = jobs)
    }

    private fun legacyLastId(jobId: String, source: String, mode: JobMode): Long? {
        val main = File(dir, "last_id_job${jobId}_$source.txt").readLongOrNull()
        if (mode == JobMode.ORDERED) return main
        val parts = dir.listFiles { f -> f.name.startsWith("last_id_job${jobId}_${source}_part") }
            .orEmpty().mapNotNull { it.readLongOrNull() }
        // Fast-mode workers each finish their own range; the lowest one is the
        // safe point to resume from without skipping anything.
        return parts.minOrNull()?.let { maxOf(it, main ?: 0) } ?: main
    }

    private fun File.readLongOrNull(): Long? =
        if (exists()) runCatching { readText().trim().toLong() }.getOrNull() else null

    private fun JsonObject.str(key: String): String =
        this[key]?.jsonPrimitive?.let { p -> p.longOrNull?.toString() ?: p.content }?.takeIf { it != "null" } ?: ""
}
