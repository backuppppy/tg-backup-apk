// Ports of tdesktop export/output: export_output_result.h,
// export_output_stats.*, export_output_file.* and export_output_abstract.*
package org.tgbackup.export.output

import org.tgbackup.export.Environment
import org.tgbackup.export.Settings
import org.tgbackup.export.data.ContactsList
import org.tgbackup.export.data.DialogInfo
import org.tgbackup.export.data.DialogsInfo
import org.tgbackup.export.data.MessagesSlice
import org.tgbackup.export.data.PersonalInfo
import org.tgbackup.export.data.ProfileMusicInfo
import org.tgbackup.export.data.ProfileMusicSlice
import org.tgbackup.export.data.SessionsList
import org.tgbackup.export.data.StoriesInfo
import org.tgbackup.export.data.StoriesSlice
import org.tgbackup.export.data.UserpicsInfo
import org.tgbackup.export.data.UserpicsSlice
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

enum class Format { Html, Json, HtmlAndJson }

class Result(val type: Type, val path: String) {
    enum class Type { Success, Error, FatalError }

    fun isSuccess() = type == Type.Success
    fun isError() = type == Type.Error || type == Type.FatalError
    fun isFatalError() = type == Type.FatalError

    companion object {
        fun Success() = Result(Type.Success, "")
    }
}

class Stats {
    private val files = AtomicInteger()
    private val bytes = AtomicLong()

    fun incrementFiles() {
        files.incrementAndGet()
    }

    fun incrementBytes(count: Int) {
        bytes.addAndGet(count.toLong())
    }

    fun filesCount(): Int = files.get()
    fun bytesCount(): Long = bytes.get()
}

/** Text written by the writers: tdesktop builds QByteArray blocks of UTF-8. */
typealias Block = String

class File(private val path: String, private val stats: Stats?) {
    private var offset = 0L
    private var out: FileOutputStream? = null
    private var inStats = false

    fun size(): Long = offset
    fun empty(): Boolean = offset == 0L

    fun writeBlock(block: String): Result = writeBlock(block.toByteArray(Charsets.UTF_8))

    fun writeBlock(block: ByteArray): Result {
        val result = writeBlockAttempt(block)
        if (!result.isSuccess()) {
            runCatching { out?.close() }
            out = null
        }
        return result
    }

    private fun writeBlockAttempt(block: ByteArray): Result {
        if (stats != null && !inStats) {
            inStats = true
            stats.incrementFiles()
        }
        val reopened = reopen()
        if (!reopened.isSuccess()) return reopened
        val size = block.size
        if (size == 0) return Result.Success()
        return try {
            out!!.write(block)
            out!!.flush()
            offset += size
            stats?.incrementBytes(size)
            Result.Success()
        } catch (e: java.io.IOException) {
            error()
        }
    }

    private fun reopen(): Result {
        if (out != null) return Result.Success()
        val file = java.io.File(path)
        if (file.exists()) {
            if (file.length() < offset) {
                return fatalError()
            }
            try {
                RandomAccessFile(file, "rw").use { it.setLength(offset) }
            } catch (e: java.io.IOException) {
                return error()
            }
        } else if (offset > 0) {
            return fatalError()
        }
        return try {
            out = FileOutputStream(file, true)
            Result.Success()
        } catch (e: java.io.IOException) {
            val dir = file.absoluteFile.parentFile
            if (dir != null && !dir.exists() && dir.mkdirs()) {
                try {
                    out = FileOutputStream(file, true)
                    Result.Success()
                } catch (e2: java.io.IOException) {
                    error()
                }
            } else {
                error()
            }
        }
    }

    fun close() {
        runCatching { out?.close() }
        out = null
    }

    private fun error() = Result(Result.Type.Error, path)
    private fun fatalError() = Result(Result.Type.FatalError, path)

    companion object {
        fun PrepareRelativePath(folder: String, suggested: String): String {
            if (!java.io.File(folder + suggested).exists()) return suggested
            // Not lastIndexOf('.') so that "file.tar.xz" won't be messed up.
            val position = suggested.indexOf('.')
            val base = if (position >= 0) suggested.substring(0, position) else suggested
            val extension = if (position >= 0) suggested.substring(position) else ""
            var attempt = 0
            while (true) {
                val relativePath = "$base (${++attempt})$extension"
                if (!java.io.File(folder + relativePath).exists()) return relativePath
            }
        }

        fun Copy(source: String, path: String, stats: Stats?): Result {
            val f = java.io.File(source)
            if (!f.exists()) return Result(Result.Type.FatalError, source)
            val bytes = try {
                f.readBytes()
            } catch (e: java.io.IOException) {
                return Result(Result.Type.FatalError, source)
            }
            val file = File(path, stats)
            val result = file.writeBlock(bytes)
            file.close()
            return result
        }
    }
}

fun NormalizePath(settings: Settings): String {
    val folder = java.io.File(settings.path)
    val path = folder.absolutePath
    var result = if (path.endsWith('/')) path else "$path/"
    if (!folder.exists() && !settings.forceSubPath) return result
    val list = folder.listFiles()?.filter { it.name != "." && it.name != ".." } ?: emptyList()
    if (list.isEmpty() && !settings.forceSubPath) return result
    val date = LocalDate.now()
    val base = (if (settings.onlySinglePeer()) "ChatExport_" else "DataExport_") + date.toString()
    fun add(i: Int) = base + if (i != 0) " ($i)" else ""
    var index = 0
    while (java.io.File(result + add(index)).exists()) ++index
    result += add(index) + '/'
    return result
}

interface AbstractWriter {
    fun format(): Format
    fun start(settings: Settings, environment: Environment, stats: Stats?): Result
    fun writePersonal(data: PersonalInfo): Result
    fun writeUserpicsStart(data: UserpicsInfo): Result
    fun writeUserpicsSlice(data: UserpicsSlice): Result
    fun writeUserpicsEnd(): Result
    fun writeStoriesStart(data: StoriesInfo): Result
    fun writeStoriesSlice(data: StoriesSlice): Result
    fun writeStoriesEnd(): Result
    fun writeProfileMusicStart(data: ProfileMusicInfo): Result
    fun writeProfileMusicSlice(data: ProfileMusicSlice): Result
    fun writeProfileMusicEnd(): Result
    fun writeContactsList(data: ContactsList): Result
    fun writeSessionsList(data: SessionsList): Result
    fun writeOtherData(data: org.tgbackup.export.data.File): Result
    fun writeDialogsStart(data: DialogsInfo): Result
    fun writeDialogStart(data: DialogInfo): Result
    fun writeDialogSlice(data: MessagesSlice): Result
    fun writeDialogEnd(): Result
    fun writeDialogsEnd(): Result
    fun finish(): Result
    fun mainFilePath(): String
}

fun CreateWriter(format: Format, thumbs: ImageThumbs): AbstractWriter = when (format) {
    Format.Html -> HtmlWriter(thumbs)
    Format.Json -> JsonWriter()
    Format.HtmlAndJson -> HtmlAndJsonWriter(thumbs)
}

/**
 * Image decoding/scaling for thumbnails (QImageReader + QImage::scaled +
 * QImage::save in tdesktop). Implemented by the platform.
 */
interface ImageThumbs {
    /** Size of the image at [path], or null if it can't be read. */
    fun size(path: String): Pair<Int, Int>?

    /**
     * Scales the image at [path] to exactly [width]x[height] (smooth) and
     * saves it to [target] in [format] ("JPG"/"PNG"/null for the source
     * format) with [quality] (-1 for default). Returns success.
     */
    fun scaleAndSave(path: String, width: Int, height: Int, target: String, format: String?, quality: Int): Boolean
}
