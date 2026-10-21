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
    fun copyContent(msg: Message, strip: Boolean): InputMessageContent? {
        val content = msg.content
        if (content is MessageText) {
            // Messages with a link preview keep their link — the link is the content.
            val text = if (content.linkPreview != null) content.text else cleanText(content.text, strip)
            if (text.text.isBlank()) return null
            return InputMessageText(text, null, false)
        }
        val caption = content.caption()
        val replace = strip && caption != null
        return InputMessageForwarded(
            fromChatId = msg.chatId,
            messageId = msg.id,
            inGameShare = false,
            replaceVideoStartTimestamp = false,
            newVideoStartTimestamp = 0,
            copyOptions = MessageCopyOptions(
                sendCopy = true,
                replaceCaption = replace,
                newCaption = if (replace) cleanText(caption!!, true) else null,
                newShowCaptionAboveMedia = false,
            ),
        )
    }

    /** Builds an upload of a file that was downloaded from a protected chat. */
    fun uploadContent(msg: Message, path: String, strip: Boolean): InputMessageContent? {
        val c = msg.content
        val caption = c.caption()?.let { cleanText(it, strip) }
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
