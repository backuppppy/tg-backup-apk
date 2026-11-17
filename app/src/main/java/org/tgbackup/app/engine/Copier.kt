package org.tgbackup.app.engine

import dev.g000sha256.tdl.dto.FormattedText
import dev.g000sha256.tdl.dto.InputFileLocal
import dev.g000sha256.tdl.dto.InputMessageAnimation
import dev.g000sha256.tdl.dto.InputMessageAudio
import dev.g000sha256.tdl.dto.InputMessageContent
import dev.g000sha256.tdl.dto.InputMessageDocument
import dev.g000sha256.tdl.dto.InputMessageForwarded
import dev.g000sha256.tdl.dto.InputMessagePhoto
import dev.g000sha256.tdl.dto.InputMessageText
import dev.g000sha256.tdl.dto.InputMessageVideo
import dev.g000sha256.tdl.dto.InputMessageVideoNote
import dev.g000sha256.tdl.dto.InputMessageVoiceNote
import dev.g000sha256.tdl.dto.InputAnimation
import dev.g000sha256.tdl.dto.InputAudio
import dev.g000sha256.tdl.dto.InputDocument
import dev.g000sha256.tdl.dto.InputPhoto
import dev.g000sha256.tdl.dto.InputVideo
import dev.g000sha256.tdl.dto.InputVideoNote
import dev.g000sha256.tdl.dto.InputVoiceNote
import dev.g000sha256.tdl.dto.Message
import dev.g000sha256.tdl.dto.MessageAnimation
import dev.g000sha256.tdl.dto.MessageAudio
import dev.g000sha256.tdl.dto.MessageContact
import dev.g000sha256.tdl.dto.MessageContent
import dev.g000sha256.tdl.dto.MessageCopyOptions
import dev.g000sha256.tdl.dto.MessageDice
import dev.g000sha256.tdl.dto.MessageDocument
import dev.g000sha256.tdl.dto.MessageLocation
import dev.g000sha256.tdl.dto.MessagePhoto
import dev.g000sha256.tdl.dto.MessagePoll
import dev.g000sha256.tdl.dto.MessageSticker
import dev.g000sha256.tdl.dto.MessageText
import dev.g000sha256.tdl.dto.MessageVenue
import dev.g000sha256.tdl.dto.MessageVideo
import dev.g000sha256.tdl.dto.MessageVideoNote
import dev.g000sha256.tdl.dto.MessageVoiceNote
import dev.g000sha256.tdl.dto.TextEntity
import dev.g000sha256.tdl.dto.File as TdFile
import org.tgbackup.app.data.MediaType
import org.tgbackup.app.tg.AccountClient
import org.tgbackup.app.tg.SendOutcome

/** Classification of a source message for the media-type filter. */
fun MessageContent.matches(type: MediaType): Boolean {
    val isFileLike = this is MessageVideo || this is MessageDocument || this is MessageAudio ||
        this is MessageAnimation || this is MessageVoiceNote || this is MessageVideoNote
    val isCopyable = this is MessageText || this is MessagePhoto || isFileLike || this is MessageSticker ||
        this is MessagePoll || this is MessageLocation || this is MessageVenue || this is MessageContact || this is MessageDice
    return when (type) {
        MediaType.VIDEO_DOC -> isFileLike
        MediaType.PHOTO -> this is MessagePhoto
        MediaType.ALL_MEDIA -> isCopyable && this !is MessageText && this !is MessageSticker
        MediaType.TEXT -> this is MessageText
        MediaType.ALL -> isCopyable
    }
}

private val linkRegex = Regex("""https?://\S+|www\.\S+|t\.me/\S+|@\S+""")

fun cleanText(text: FormattedText, strip: Boolean): FormattedText {
    if (!strip) return text
    // Removing links shifts every offset, so formatting entities are dropped together with them.
    return FormattedText(linkRegex.replace(text.text, "").trim(), emptyArray<TextEntity>())
}

fun MessageContent.caption(): FormattedText? = when (this) {
    is MessagePhoto -> caption
    is MessageVideo -> caption
    is MessageDocument -> caption
    is MessageAudio -> caption
    is MessageAnimation -> caption
    is MessageVoiceNote -> caption
    else -> null
}

/** The main file of a media message, which must be downloaded for re-upload from protected chats. */
fun MessageContent.mainFile(): TdFile? = when (this) {
    is MessagePhoto -> photo.sizes.maxByOrNull { it.width.toLong() * it.height }?.photo
    is MessageVideo -> video.video
    is MessageDocument -> document.document
    is MessageAudio -> audio.audio
    is MessageAnimation -> animation.animation
    is MessageVoiceNote -> voiceNote.voice
    is MessageVideoNote -> videoNote.video
    else -> null
}

object Copier {

    /** Server-side copy: no download, no "forwarded from" header, optionally with a cleaned caption. */
    fun copyContent(msg: Message, strip: Boolean, captionOverride: FormattedText? = null): InputMessageContent? {
        val content = msg.content
        if (content is MessageText) {
            // Messages with a link preview keep their link — the link is the content.
            val text = if (content.linkPreview != null) content.text else cleanText(content.text, strip)
            if (text.text.isBlank()) return null
            return InputMessageText(text, null, false)
        }
        val caption = content.caption()
        val replace = captionOverride != null || (strip && caption != null)
        return InputMessageForwarded(
            fromChatId = msg.chatId,
            messageId = msg.id,
            inGameShare = false,
            replaceVideoStartTimestamp = false,
            newVideoStartTimestamp = 0,
            copyOptions = MessageCopyOptions(
                sendCopy = true,
                replaceCaption = replace,
                newCaption = captionOverride ?: if (replace) cleanText(caption!!, true) else null,
                newShowCaptionAboveMedia = false,
            ),
        )
    }

    /** Builds an upload of a file that was downloaded from a protected chat. */
    fun uploadContent(msg: Message, path: String, strip: Boolean, captionOverride: FormattedText? = null): InputMessageContent? {
        val c = msg.content
        val caption = captionOverride ?: c.caption()?.let { cleanText(it, strip) }
        val file = InputFileLocal(path)
        return when (c) {
            is MessagePhoto -> {
                val size = c.photo.sizes.maxByOrNull { it.width.toLong() * it.height }
                InputMessagePhoto(InputPhoto(file, null, null, IntArray(0), size?.width ?: 0, size?.height ?: 0), caption, false, null, false)
            }
            is MessageVideo -> InputMessageVideo(
                InputVideo(file, null, null, 0, IntArray(0), c.video.duration, c.video.width, c.video.height, c.video.supportsStreaming),
                caption, false, null, false,
            )
            is MessageDocument -> InputMessageDocument(InputDocument(file, null, false), caption)
            is MessageAudio -> InputMessageAudio(InputAudio(file, null, c.audio.duration, c.audio.title, c.audio.performer), caption)
            is MessageAnimation -> InputMessageAnimation(
                InputAnimation(file, null, IntArray(0), c.animation.duration, c.animation.width, c.animation.height),
                caption, false, false,
            )
            is MessageVoiceNote -> InputMessageVoiceNote(InputVoiceNote(file, c.voiceNote.duration, c.voiceNote.waveform), caption, null)
            is MessageVideoNote -> InputMessageVideoNote(InputVideoNote(file, null, c.videoNote.duration, c.videoNote.length), null)
            else -> null
        }
    }

    fun isProtectedError(message: String): Boolean {
        val m = message.lowercase()
        return "protected" in m || "can't be forwarded" in m || "can't be copied" in m ||
            "chat_forwards_restricted" in m || "noforwards" in m
    }

    /** A downloaded file waiting to be uploaded; [owner] is the account whose cache holds it. */
    data class Downloaded(val owner: AccountClient, val fileId: Int, val path: String)
}

/**
 * [text] cut in two so the head has at most [max] UTF-16 units: at the last whitespace in its second half, else at
 * [max] (never inside a surrogate pair). Entities follow their text (one that crosses the cut is clipped to both
 * parts). The rest is null when nothing is left. No text is dropped except the whitespace at the cut.
 */
fun splitFormatted(text: FormattedText, max: Int): Pair<FormattedText, FormattedText?> {
    val s = text.text
    if (s.length <= max) return text to null
    var headEnd = (max downTo max / 2).firstOrNull { it < s.length && s[it].isWhitespace() } ?: max
    if (headEnd == max && headEnd > 0 && s[headEnd - 1].isHighSurrogate()) headEnd--
    val head = s.substring(0, headEnd).trimEnd()
    var restStart = headEnd
    while (restStart < s.length && s[restStart].isWhitespace()) restStart++
    val rest = s.substring(restStart)
    val headEntities = text.entities.mapNotNull { e ->
        val end = minOf(e.offset + e.length, head.length)
        if (end > e.offset) TextEntity(e.offset, end - e.offset, e.type) else null
    }
    val restEntities = text.entities.mapNotNull { e ->
        val start = maxOf(e.offset, restStart)
        val end = e.offset + e.length
        if (end > start) TextEntity(start - restStart, end - start, e.type) else null
    }
    return FormattedText(head, headEntities.toTypedArray()) to
        rest.takeIf { it.isNotEmpty() }?.let { FormattedText(it, restEntities.toTypedArray()) }
}

/** [text] in parts of at most [max] UTF-16 units each (see [splitFormatted]). */
fun splitAll(text: FormattedText, max: Int): List<FormattedText> {
    val out = ArrayList<FormattedText>()
    var left: FormattedText? = text
    while (left != null) {
        val (head, rest) = splitFormatted(left, max)
        out += head
        left = rest
    }
    return out
}

/** Telegram refused a caption over the sender's limit (1024, 4096 with Premium). */
fun isCaptionTooLong(message: String): Boolean {
    val m = message.lowercase()
    return "caption" in m && ("too long" in m || "too_long" in m)
}

/**
 * The rest of a too-long caption, sent by [sender] to [target] as text messages right after its media (parts of at
 * most 4096). Each part is tried 3 times; [warn] gets each failure. False when a part could not be sent.
 */
suspend fun sendCaptionRest(sender: AccountClient, target: Long, rest: FormattedText, warn: (String) -> Unit): Boolean {
    var all = true
    for (part in splitAll(rest, 4096)) {
        var sent = false
        for (attempt in 0 until 3) {
            when (val r = sender.sendAndWait(target, InputMessageText(part, null, false))) {
                is SendOutcome.Sent -> sent = true
                is SendOutcome.Failed -> {
                    warn("${r.code} ${r.message}")
                    kotlinx.coroutines.delay(((r.retryAfterSec.takeIf { it > 0 } ?: 3) + 1) * 1000L * (attempt + 1))
                }
            }
            if (sent) break
        }
        if (!sent) all = false
    }
    return all
}
