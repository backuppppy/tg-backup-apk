package org.tgbackup.app.engine

import org.tgbackup.app.data.Job
import org.tgbackup.app.data.MediaType
import org.tgbackup.copy.CopyKind

/** "pdf, .ZIP rar" → {pdf, zip, rar}: comma or space separated, case-insensitive, a leading dot optional. */
fun parseExtensions(s: String): Set<String> =
    s.split(Regex("[,;\\s\u00A0]+")).map { it.trim().trimStart('.').lowercase() }.filter { it.isNotEmpty() }.toSet()

/**
 * Which messages a job copies: those matching one of its content types, or a file with one of its extensions.
 * "הכל" ignores the extensions (everything is copied anyway).
 */
class ContentFilter(val types: Set<MediaType>, val extensions: Set<String>) {
    private val all = MediaType.ALL in types

    /** [typeMatch] answers whether the message matches one type; [fileName] is its file's name, if it has one. */
    fun accepts(typeMatch: (MediaType) -> Boolean, fileName: String?): Boolean {
        if (all) return typeMatch(MediaType.ALL)
        if (types.any(typeMatch)) return true
        if (extensions.isEmpty() || fileName == null) return false
        val name = fileName.lowercase()
        // endsWith, not the last dot only: "tar.gz" matches too.
        return extensions.any { name.length > it.length + 1 && name.endsWith(".$it") }
    }

    fun accepts(kind: CopyKind, fileName: String?): Boolean = accepts({ kind.matches(it) }, fileName)
}

/** The job's content types: the editor's multi-select, or the single type of a job saved before it. */
fun Job.contentTypes(): Set<MediaType> = mediaTypes?.toSet() ?: setOf(mediaType)

fun Job.contentFilter() = ContentFilter(contentTypes(), parseExtensions(extensions))

private fun MediaType.choiceLabel(): String = when (this) {
    MediaType.PHOTO -> "תמונות"
    MediaType.TEXT -> "טקסט"
    else -> label
}

/** For the log: "תמונות + קבצים + .pdf"; a job saved before the multi-select shows its old label. */
fun Job.contentLabel(): String {
    val types = mediaTypes ?: return mediaType.label
    if (MediaType.ALL in types) return MediaType.ALL.label
    return (types.map { it.choiceLabel() } + parseExtensions(extensions).map { ".$it" }).joinToString(" + ")
}

/** The closest single type, saved next to [Job.mediaTypes] so an older version opening the config still works. */
fun legacyMediaType(types: List<MediaType>): MediaType = when {
    types.size == 1 && types[0] in oldTypes -> types[0]
    MediaType.ALL in types -> MediaType.ALL
    types == listOf(MediaType.PHOTO) -> MediaType.PHOTO
    types == listOf(MediaType.TEXT) -> MediaType.TEXT
    types.all { it == MediaType.PHOTO || it in fileTypes } && MediaType.PHOTO in types -> MediaType.ALL_MEDIA
    else -> MediaType.VIDEO_DOC
}

private val oldTypes = setOf(MediaType.VIDEO_DOC, MediaType.PHOTO, MediaType.ALL_MEDIA, MediaType.TEXT, MediaType.ALL)

private val fileTypes = setOf(MediaType.VIDEO, MediaType.FILE, MediaType.MUSIC, MediaType.VOICE)

/** The editor's choices: "הכל" alone, or the chosen types in a fixed order. */
fun normalizeTypes(types: List<MediaType>): List<MediaType> =
    if (MediaType.ALL in types) listOf(MediaType.ALL) else MediaType.entries.filter { it in types }

/** True when a job copies nothing: no content type and no extension. */
fun copiesNothing(types: List<MediaType>, extensions: String) = types.isEmpty() && parseExtensions(extensions).isEmpty()
