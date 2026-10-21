package org.tgbackup.app.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Level { INFO, SUCCESS, WARN, ERROR }

data class LogEntry(val seq: Long, val time: String, val level: Level, val msg: String)

/** In-memory ring buffer for the UI, mirrored to a small rotating file for debugging. */
class LogBus(dir: File) {
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    private val file = File(dir, "log.txt")
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var seq = 0L

    fun info(msg: String) = add(Level.INFO, msg)
    fun success(msg: String) = add(Level.SUCCESS, msg)
    fun warn(msg: String) = add(Level.WARN, msg)
    fun error(msg: String) = add(Level.ERROR, msg)

    @Synchronized
    private fun add(level: Level, msg: String) {
        val entry = LogEntry(seq++, fmt.format(Date()), level, msg)
        _entries.update { (it + entry).takeLast(MAX) }
        runCatching {
            if (file.length() > 1_000_000) file.renameTo(File(file.parentFile, "log.old.txt"))
            file.appendText("${entry.time} ${level.name} $msg\n")
        }
    }

    fun clear() = _entries.update { emptyList() }

    private companion object {
        const val MAX = 500
    }
}
