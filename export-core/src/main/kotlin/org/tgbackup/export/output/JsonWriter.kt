// Port of tdesktop Telegram/SourceFiles/export/output/export_output_json.cpp
@file:Suppress("FunctionName", "LocalVariableName")

package org.tgbackup.export.output

import org.tgbackup.export.Environment
import org.tgbackup.export.Settings
import org.tgbackup.export.data.*
import org.tgbackup.export.data.File.SkipReason as SR
import java.time.Instant
import java.time.ZonedDateTime

internal class JsonContext {
    // true = object, false = array.
    val nesting = ArrayList<Boolean>()

    companion object {
        const val kObject = true
        const val kArray = false
    }
}

private typealias Ctx = JsonContext
private typealias Values = MutableList<Pair<String, String>>

private class RichSerializeContext(val json: Ctx, val message: RichMessage)

internal fun SerializeString(value: String): String {
    val result = StringBuilder(2 + value.length * 2)
    result.append('"')
    for (ch in value) {
        when {
            ch == '\n' -> result.append("\\n")
            ch == '\r' -> result.append("\\r")
            ch == '\t' -> result.append("\\t")
            ch == '"' -> result.append("\\\"")
            ch == '\\' -> result.append("\\\\")
            ch.code < 32 -> {
                result.append("\\x").append('0' + (ch.code shr 4))
                val left = ch.code and 0x0F
                if (left >= 10) result.append('A' + (left - 10)) else result.append('0' + left)
            }
            ch == ' ' -> result.append("\\u2028")
            ch == ' ' -> result.append("\\u2029")
            else -> result.append(ch)
        }
    }
    result.append('"')
    return result.toString()
}

/** QDateTime::fromSecsSinceEpoch(date).toString(Qt::ISODate) in local time. */
internal fun SerializeDate(date: TimeId): String {
    val v = ZonedDateTime.ofInstant(Instant.ofEpochSecond(date.toLong()), ExportTimeZone.zone)
    return SerializeString(String.format(java.util.Locale.ROOT, "%04d-%02d-%02dT%02d:%02d:%02d", v.year, v.monthValue, v.dayOfMonth, v.hour, v.minute, v.second))
}

internal fun SerializeDateRaw(date: TimeId): String = SerializeString(date.toString())

private fun StringAllowEmpty(data: String): String = if (data.isEmpty()) data else SerializeString(data)
private fun StringAllowNull(data: String): String = if (data.isEmpty()) "null" else SerializeString(data)

private fun Indentation(size: Int): String = " ".repeat(size)
private fun Indentation(context: Ctx): String = Indentation(context.nesting.size)

private fun SerializeObject(context: Ctx, values: List<Pair<String, String>>): String {
    val indent = Indentation(context)
    context.nesting.add(Ctx.kObject)
    try {
        val next = "\n" + Indentation(context)
        var first = true
        val result = StringBuilder()
        result.append('{')
        for ((key, value) in values) {
            if (value.isEmpty()) continue
            if (first) first = false else result.append(',')
            result.append(next).append(SerializeString(key)).append(": ")
            result.append(value)
        }
        result.append('\n').append(indent).append("}")
        return result.toString()
    } finally {
        context.nesting.removeAt(context.nesting.size - 1)
    }
}

private fun SerializeArray(context: Ctx, values: List<String>): String {
    val indent = Indentation(context.nesting.size)
    val next = "\n" + Indentation(context.nesting.size + 1)
    var first = true
    val result = StringBuilder()
    result.append('[')
    for (value in values) {
        if (first) first = false else result.append(',')
        result.append(next).append(value)
    }
    result.append('\n').append(indent).append("]")
    return result.toString()
}

private inline fun <T> Ctx.with(type: Boolean, block: () -> T): T {
    nesting.add(type)
    try {
        return block()
    } finally {
        nesting.removeAt(nesting.size - 1)
    }
}

private fun SerializeText(context: Ctx, data: List<TextPart>, serializeToObjects: Boolean = false): String {
    if (data.isEmpty()) return if (serializeToObjects) "[]" else SerializeString("")
    context.nesting.add(Ctx.kArray)
    val text = data.map { part ->
        if (part.type == TextPart.Type.Text && !serializeToObjects) return@map SerializeString(part.text)
        val typeString = when (part.type) {
            TextPart.Type.Unknown -> "unknown"
            TextPart.Type.Mention -> "mention"
            TextPart.Type.Hashtag -> "hashtag"
            TextPart.Type.BotCommand -> "bot_command"
            TextPart.Type.Url -> "link"
            TextPart.Type.Email -> "email"
            TextPart.Type.Bold -> "bold"
            TextPart.Type.Italic -> "italic"
            TextPart.Type.Code -> "code"
            TextPart.Type.Pre -> "pre"
            TextPart.Type.Text -> "plain"
            TextPart.Type.TextUrl -> "text_link"
            TextPart.Type.MentionName -> "mention_name"
            TextPart.Type.Phone -> "phone"
            TextPart.Type.Cashtag -> "cashtag"
            TextPart.Type.Underline -> "underline"
            TextPart.Type.Strike -> "strikethrough"
            TextPart.Type.Blockquote -> "blockquote"
            TextPart.Type.BankCard -> "bank_card"
            TextPart.Type.Spoiler -> "spoiler"
            TextPart.Type.CustomEmoji -> "custom_emoji"
        }
        val additionalName = when (part.type) {
            TextPart.Type.MentionName -> "user_id"
            TextPart.Type.CustomEmoji -> "document_id"
            TextPart.Type.Pre -> "language"
            TextPart.Type.TextUrl -> "href"
            TextPart.Type.Blockquote -> "collapsed"
            else -> "none"
        }
        val additionalValue = when (part.type) {
            TextPart.Type.MentionName -> part.additional
            TextPart.Type.Pre, TextPart.Type.TextUrl, TextPart.Type.CustomEmoji -> SerializeString(part.additional)
            TextPart.Type.Blockquote -> if (part.additional.isEmpty()) "false" else "true"
            else -> ""
        }
        SerializeObject(
            context,
            listOf(
                "type" to SerializeString(typeString),
                "text" to SerializeString(part.text),
                additionalName to additionalValue,
            ),
        )
    }
    context.nesting.removeAt(context.nesting.size - 1)
    if (!serializeToObjects) {
        if (data.size == 1 && data[0].type == TextPart.Type.Text) return text[0]
    }
    return SerializeArray(context, text)
}

private fun FormatUsername(username: String): String = if (username.isEmpty()) username else "@$username"

private fun FormatFilePath(file: org.tgbackup.export.data.File): String = file.relativePath

private fun SerializeRichBool(value: Boolean): String = if (value) "true" else "false"
private fun SerializeRichNumberString(value: Long): String = SerializeString(NumberToString(value))
private fun SerializeRichUNumberString(value: Long): String = SerializeString(java.lang.Long.toUnsignedString(value))
private fun UNumberToString(value: Long): String = java.lang.Long.toUnsignedString(value)

private fun FindRichPhoto(context: RichSerializeContext, photoId: Long): Photo? = context.message.photos[photoId.toULong()]
private fun FindRichDocument(context: RichSerializeContext, documentId: Long): Document? = context.message.documents[documentId.toULong()]

private fun AppendRichFileAvailability(values: Values, file: org.tgbackup.export.data.File?, pathKey: String, reasonKey: String) {
    if (file != null) {
        when (file.skipReason) {
            SR.Unavailable -> {
                values.add(reasonKey to SerializeString("unavailable"))
                return
            }
            SR.FileType -> {
                values.add(reasonKey to SerializeString("file_type"))
                return
            }
            SR.FileSize -> {
                values.add(reasonKey to SerializeString("file_size"))
                return
            }
            SR.DateLimits -> {
                values.add(reasonKey to SerializeString("date_limits"))
                return
            }
            SR.None -> if (file.relativePath.isNotEmpty()) {
                values.add(pathKey to SerializeString(file.relativePath))
                return
            }
        }
    }
    values.add(reasonKey to SerializeString("unavailable"))
}

private fun AppendRichPhotoMetadata(
    values: Values,
    photo: Photo?,
    pathKey: String,
    reasonKey: String,
    sizeKey: String,
    widthKey: String,
    heightKey: String,
) {
    AppendRichFileAvailability(values, photo?.image?.file, pathKey, reasonKey)
    if (photo == null) return
    values.add(sizeKey to NumberToString(photo.image.file.size))
    if (photo.image.width != 0 && photo.image.height != 0) {
        values.add(widthKey to NumberToString(photo.image.width))
        values.add(heightKey to NumberToString(photo.image.height))
    }
}

private fun AppendRichDocumentMetadata(values: Values, document: Document?, includeDimensions: Boolean) {
    AppendRichFileAvailability(values, document?.file, "file", "file_skip_reason")
    if (document == null) return
    if (document.name.isNotEmpty()) values.add("file_name" to SerializeString(document.name))
    values.add("file_size" to NumberToString(document.file.size))
    if (document.thumb.width > 0) {
        AppendRichFileAvailability(values, document.thumb.file, "thumbnail", "thumbnail_skip_reason")
        values.add("thumbnail_file_size" to NumberToString(document.thumb.file.size))
    }
    if (document.isSticker) {
        values.add("media_type" to SerializeString("sticker"))
        if (document.stickerEmoji.isNotEmpty()) values.add("sticker_emoji" to SerializeString(document.stickerEmoji))
    } else if (document.isVideoMessage) {
        values.add("media_type" to SerializeString("video_message"))
    } else if (document.isVoiceMessage) {
        values.add("media_type" to SerializeString("voice_message"))
    } else if (document.isAnimated) {
        values.add("media_type" to SerializeString("animation"))
    } else if (document.isVideoFile) {
        values.add("media_type" to SerializeString("video_file"))
    } else if (document.isAudioFile) {
        values.add("media_type" to SerializeString("audio_file"))
        if (document.songPerformer.isNotEmpty()) values.add("performer" to SerializeString(document.songPerformer))
        if (document.songTitle.isNotEmpty()) values.add("title" to SerializeString(document.songTitle))
    }
    if (document.mime.isNotEmpty()) values.add("mime_type" to SerializeString(document.mime))
    if (document.duration != 0) values.add("duration_seconds" to NumberToString(document.duration))
    if (includeDimensions && document.width != 0 && document.height != 0) {
        values.add("width" to NumberToString(document.width))
        values.add("height" to NumberToString(document.height))
    }
}

private fun <V> SerializeRichArray(context: Ctx, data: List<V>, serializer: (V) -> String): String {
    if (data.isEmpty()) return "[]"
    val values = context.with(Ctx.kArray) { data.map(serializer) }
    return SerializeArray(context, values)
}

private fun RichTextTypeToString(type: RichText.Type): String = when (type) {
    RichText.Type.Empty -> "empty"
    RichText.Type.Plain -> "plain"
    RichText.Type.Concat -> "concat"
    RichText.Type.Bold -> "bold"
    RichText.Type.Italic -> "italic"
    RichText.Type.Underline -> "underline"
    RichText.Type.Strike -> "strikethrough"
    RichText.Type.Fixed -> "code"
    RichText.Type.Url -> "text_link"
    RichText.Type.Email -> "email"
    RichText.Type.Phone -> "phone"
    RichText.Type.Subscript -> "subscript"
    RichText.Type.Superscript -> "superscript"
    RichText.Type.Marked -> "marked"
    RichText.Type.Anchor -> "anchor"
    RichText.Type.Math -> "math"
    RichText.Type.CustomEmoji -> "custom_emoji"
    RichText.Type.Spoiler -> "spoiler"
    RichText.Type.Mention -> "mention"
    RichText.Type.Hashtag -> "hashtag"
    RichText.Type.BotCommand -> "bot_command"
    RichText.Type.Cashtag -> "cashtag"
    RichText.Type.AutoUrl -> "link"
    RichText.Type.AutoEmail -> "email"
    RichText.Type.AutoPhone -> "phone"
    RichText.Type.BankCard -> "bank_card"
    RichText.Type.MentionName -> "mention_name"
    RichText.Type.FormattedDate -> "formatted_date"
    RichText.Type.InlineImage -> "inline_image"
    RichText.Type.Diff -> "diff"
    RichText.Type.Button -> "button"
}

private fun Base64Url(data: ByteArray): String =
    org.tgbackup.mtproto.B64.urlNoPad(data)

private fun SerializeRichButtonAction(context: Ctx, action: InlineButtonAction): String {
    val values: Values = mutableListOf("type" to SerializeString(InlineButtonActionTypeToString(action)))
    context.with(Ctx.kObject) {
        when (action.type) {
            InlineButtonAction.Type.Url, InlineButtonAction.Type.WebView -> values.add("url" to SerializeString(action.url))
            InlineButtonAction.Type.Auth -> {
                values.add("url" to SerializeString(action.url))
                action.forwardText?.let { values.add("forward_text" to SerializeString(it)) }
                values.add("button_id" to NumberToString(action.buttonId))
            }
            InlineButtonAction.Type.Callback, InlineButtonAction.Type.CallbackWithPassword -> {
                values.add("requires_password" to SerializeRichBool(action.requiresPassword))
                values.add("dataBase64" to SerializeString(Base64Url(action.callbackData)))
                values.add("data" to SerializeString(""))
            }
            InlineButtonAction.Type.Game, InlineButtonAction.Type.Buy, InlineButtonAction.Type.Disabled -> Unit
            InlineButtonAction.Type.SwitchInline, InlineButtonAction.Type.SwitchInlineSame -> {
                values.add("query" to SerializeString(action.query))
                values.add("same_peer" to SerializeRichBool(action.samePeer))
                action.peerTypes?.let { types ->
                    values.add(
                        "peer_types" to SerializeRichArray(context, types) { SerializeString(InlineButtonPeerTypeToString(it)) },
                    )
                }
            }
            InlineButtonAction.Type.UserProfile -> values.add("user_id" to SerializeRichUNumberString(action.userId))
            InlineButtonAction.Type.CopyText -> values.add("copy_text" to SerializeString(action.copyText))
        }
    }
    return SerializeObject(context, values)
}

private fun AppendRichButtonFields(values: Values, context: RichSerializeContext, button: RichText) {
    check(button.type == RichText.Type.Button && button.children.size == 1 && button.button != null)
    values.add("text" to SerializeRichText(context, button.children.first()))
    values.add("button" to SerializeRichButtonAction(context.json, button.button!!.action))
    button.button!!.style?.let { values.add("style" to SerializeString(RichButtonStyleToString(it))) }
}

private fun SerializeRichTextChild(context: RichSerializeContext, children: List<RichText>): String {
    check(children.size == 1)
    return SerializeRichText(context, children.first())
}

private fun SerializeRichText(context: RichSerializeContext, data: RichText): String {
    val values: Values = mutableListOf("type" to SerializeString(RichTextTypeToString(data.type)))
    context.json.with(Ctx.kObject) {
        when (data.type) {
            RichText.Type.Empty -> Unit
            RichText.Type.Plain -> values.add("text" to SerializeString(data.text))
            RichText.Type.Concat -> values.add("text" to SerializeRichTexts(context, data.children))
            RichText.Type.Bold, RichText.Type.Italic, RichText.Type.Underline, RichText.Type.Strike, RichText.Type.Fixed, RichText.Type.Subscript, RichText.Type.Superscript, RichText.Type.Marked, RichText.Type.Spoiler,
            RichText.Type.Mention, RichText.Type.Hashtag, RichText.Type.BotCommand, RichText.Type.Cashtag, RichText.Type.AutoUrl, RichText.Type.AutoEmail, RichText.Type.AutoPhone, RichText.Type.BankCard,
            -> values.add("text" to SerializeRichTextChild(context, data.children))
            RichText.Type.Url -> {
                values.add("href" to SerializeString(data.data))
                if (data.id != 0L) values.add("webpage_id" to SerializeRichUNumberString(data.id))
                values.add("text" to SerializeRichTextChild(context, data.children))
            }
            RichText.Type.Email -> {
                values.add("email" to SerializeString(data.data))
                values.add("text" to SerializeRichTextChild(context, data.children))
            }
            RichText.Type.Phone -> {
                values.add("phone" to SerializeString(data.data))
                values.add("text" to SerializeRichTextChild(context, data.children))
            }
            RichText.Type.Anchor -> {
                values.add("name" to SerializeString(data.data))
                values.add("text" to SerializeRichTextChild(context, data.children))
            }
            RichText.Type.Math -> values.add("source" to SerializeString(data.data))
            RichText.Type.CustomEmoji -> {
                values.add("text" to SerializeString(data.text))
                values.add("document_id" to SerializeString(data.customEmojiData))
            }
            RichText.Type.MentionName -> {
                values.add("user_id" to SerializeRichUNumberString(data.id))
                values.add("text" to SerializeRichTextChild(context, data.children))
            }
            RichText.Type.FormattedDate -> {
                values.add("text" to SerializeRichTextChild(context, data.children))
                values.add("date" to SerializeDate(data.date))
                values.add("date_unixtime" to SerializeDateRaw(data.date))
                values.add("relative" to SerializeRichBool(data.relative))
                values.add("short_time" to SerializeRichBool(data.shortTime))
                values.add("long_time" to SerializeRichBool(data.longTime))
                values.add("short_date" to SerializeRichBool(data.shortDate))
                values.add("long_date" to SerializeRichBool(data.longDate))
                values.add("day_of_week" to SerializeRichBool(data.dayOfWeek))
            }
            RichText.Type.InlineImage -> {
                values.add("document_id" to SerializeRichUNumberString(data.id))
                AppendRichDocumentMetadata(values, FindRichDocument(context, data.id), false)
                values.add("width" to NumberToString(data.width))
                values.add("height" to NumberToString(data.height))
            }
            RichText.Type.Diff -> {
                values.add("unsupported" to "true")
                values.add("text" to SerializeRichTextChild(context, data.children))
                values.add("old_text" to SerializeRichTextChild(context, data.oldChildren))
            }
            RichText.Type.Button -> AppendRichButtonFields(values, context, data)
        }
    }
    return SerializeObject(context.json, values)
}

private fun SerializeRichTexts(context: RichSerializeContext, data: List<RichText>): String =
    SerializeRichArray(context.json, data) { SerializeRichText(context, it) }

private fun SerializeRichButton(context: RichSerializeContext, data: RichText): String {
    val values: Values = mutableListOf()
    context.json.with(Ctx.kObject) { AppendRichButtonFields(values, context, data) }
    return SerializeObject(context.json, values)
}

private fun SerializeRichButtons(context: RichSerializeContext, data: List<RichText>): String =
    SerializeRichArray(context.json, data) { SerializeRichButton(context, it) }

private fun SerializeRichCaption(context: RichSerializeContext, data: RichCaption): String {
    val values: Values = mutableListOf()
    context.json.with(Ctx.kObject) {
        values.add("text" to SerializeRichText(context, data.text))
        values.add("credit" to SerializeRichText(context, data.credit))
    }
    return SerializeObject(context.json, values)
}

private fun RichTaskStateToString(state: RichTaskState) = when (state) {
    RichTaskState.None -> "none"
    RichTaskState.Unchecked -> "unchecked"
    RichTaskState.Checked -> "checked"
}

private fun RichListKindToString(kind: RichListKind) = when (kind) {
    RichListKind.Bullet -> "bullet"
    RichListKind.Ordered -> "ordered"
}

private fun RichListItemContentToString(content: RichListItemContent) = when (content) {
    RichListItemContent.Text -> "text"
    RichListItemContent.Blocks -> "blocks"
}

private fun RichQuoteContentToString(content: RichQuoteContent) = when (content) {
    RichQuoteContent.Text -> "text"
    RichQuoteContent.Blocks -> "blocks"
}

private fun RichTableAlignmentToString(a: RichTableAlignment) = when (a) {
    RichTableAlignment.Left -> "left"
    RichTableAlignment.Center -> "center"
    RichTableAlignment.Right -> "right"
}

private fun RichTableVerticalAlignmentToString(a: RichTableVerticalAlignment) = when (a) {
    RichTableVerticalAlignment.Top -> "top"
    RichTableVerticalAlignment.Middle -> "middle"
    RichTableVerticalAlignment.Bottom -> "bottom"
}

private fun RichChannelSourceToString(s: RichChannel.Source) = when (s) {
    RichChannel.Source.ChatEmpty -> "chat_empty"
    RichChannel.Source.Chat -> "chat"
    RichChannel.Source.ChatForbidden -> "chat_forbidden"
    RichChannel.Source.Channel -> "channel"
    RichChannel.Source.ChannelForbidden -> "channel_forbidden"
    RichChannel.Source.Community -> "community"
    RichChannel.Source.CommunityForbidden -> "community_forbidden"
}

private fun RichMapPointSourceToString(s: RichMapPoint.Source) = when (s) {
    RichMapPoint.Source.GeoPointEmpty -> "geo_point_empty"
    RichMapPoint.Source.GeoPoint -> "geo_point"
    RichMapPoint.Source.InputGeoPointEmpty -> "input_geo_point_empty"
    RichMapPoint.Source.InputGeoPoint -> "input_geo_point"
}

private fun SerializeRichListItem(context: RichSerializeContext, data: RichListItem): String {
    val values: Values = mutableListOf(
        "task_state" to SerializeString(RichTaskStateToString(data.taskState)),
        "content" to SerializeString(RichListItemContentToString(data.content)),
    )
    context.json.with(Ctx.kObject) {
        when (data.content) {
            RichListItemContent.Text -> values.add("text" to SerializeRichText(context, data.text ?: RichText()))
            RichListItemContent.Blocks -> values.add("blocks" to SerializeRichBlocks(context, data.blocks))
        }
    }
    data.num?.let { values.add("num" to SerializeString(it)) }
    data.value?.let { values.add("value" to NumberToString(it)) }
    data.type?.let { values.add("item_type" to SerializeString(it)) }
    return SerializeObject(context.json, values)
}

private fun SerializeRichListItems(context: RichSerializeContext, data: List<RichListItem>): String =
    SerializeRichArray(context.json, data) { SerializeRichListItem(context, it) }

private fun SerializeRichTableCell(context: RichSerializeContext, data: RichTableCell): String {
    val values: Values = mutableListOf()
    data.text?.let { text ->
        context.json.with(Ctx.kObject) { values.add("text" to SerializeRichText(context, text)) }
    }
    data.colspan?.let { values.add("colspan" to NumberToString(it)) }
    data.rowspan?.let { values.add("rowspan" to NumberToString(it)) }
    values.add("header" to SerializeRichBool(data.header))
    values.add("align" to SerializeString(RichTableAlignmentToString(data.alignment)))
    values.add("vertical_align" to SerializeString(RichTableVerticalAlignmentToString(data.verticalAlignment)))
    return SerializeObject(context.json, values)
}

private fun SerializeRichTableRow(context: RichSerializeContext, data: RichTableRow): String {
    val cells = context.json.with(Ctx.kObject) {
        SerializeRichArray(context.json, data.cells) { SerializeRichTableCell(context, it) }
    }
    return SerializeObject(context.json, listOf("cells" to cells))
}

private fun SerializeRichRelatedArticle(context: RichSerializeContext, data: RichRelatedArticle): String {
    val values: Values = mutableListOf(
        "url" to SerializeString(data.url),
        "webpage_id" to SerializeRichUNumberString(data.webpageId),
    )
    data.title?.let { values.add("title" to SerializeString(it)) }
    data.description?.let { values.add("description" to SerializeString(it)) }
    data.photoId?.let { photoId ->
        values.add("photo_id" to SerializeRichUNumberString(photoId))
        AppendRichPhotoMetadata(values, FindRichPhoto(context, photoId), "photo", "photo_skip_reason", "photo_file_size", "photo_width", "photo_height")
    }
    data.author?.let { values.add("author" to SerializeString(it)) }
    data.publishedDate?.let {
        values.add("published_date" to SerializeDate(it))
        values.add("published_date_unixtime" to SerializeDateRaw(it))
    }
    return SerializeObject(context.json, values)
}

private fun SerializeRichChannel(context: RichSerializeContext, data: RichChannel): String {
    val values: Values = mutableListOf(
        "source_type" to SerializeString(RichChannelSourceToString(data.source)),
        "id" to SerializeRichUNumberString(data.id),
    )
    data.accessHash?.let { values.add("access_hash" to SerializeRichNumberString(it)) }
    data.title?.let { values.add("title" to SerializeString(it)) }
    data.username?.let { values.add("username" to SerializeString(it)) }
    if (data.source == RichChannel.Source.Channel || data.source == RichChannel.Source.ChannelForbidden) {
        values.add("broadcast" to SerializeRichBool(data.broadcast))
        values.add("megagroup" to SerializeRichBool(data.megagroup))
        values.add("monoforum" to SerializeRichBool(data.monoforum))
    }
    return SerializeObject(context.json, values)
}

private fun SerializeRichMapPoint(context: RichSerializeContext, data: RichMapPoint): String {
    val values: Values = mutableListOf("source_type" to SerializeString(RichMapPointSourceToString(data.source)))
    when (data.source) {
        RichMapPoint.Source.GeoPointEmpty, RichMapPoint.Source.InputGeoPointEmpty -> Unit
        RichMapPoint.Source.GeoPoint, RichMapPoint.Source.InputGeoPoint -> {
            values.add("latitude" to NumberToString(data.latitude))
            values.add("longitude" to NumberToString(data.longitude))
        }
    }
    data.accessHash?.let { values.add("access_hash" to SerializeRichNumberString(it)) }
    data.accuracyRadius?.let { values.add("accuracy_radius" to NumberToString(it)) }
    return SerializeObject(context.json, values)
}

private fun RichBlockKindToString(kind: RichBlock.Kind): String = when (kind) {
    RichBlock.Kind.Unsupported -> "unsupported"
    RichBlock.Kind.Heading -> "heading"
    RichBlock.Kind.Paragraph -> "paragraph"
    RichBlock.Kind.Footer -> "footer"
    RichBlock.Kind.Thinking -> "thinking"
    RichBlock.Kind.AuthorDate -> "author_date"
    RichBlock.Kind.Code -> "code"
    RichBlock.Kind.Divider -> "divider"
    RichBlock.Kind.Anchor -> "anchor"
    RichBlock.Kind.List -> "list"
    RichBlock.Kind.Quote -> "quote"
    RichBlock.Kind.Photo -> "photo"
    RichBlock.Kind.Video -> "video"
    RichBlock.Kind.Cover -> "cover"
    RichBlock.Kind.Embed -> "embed"
    RichBlock.Kind.EmbedPost -> "embed_post"
    RichBlock.Kind.Collage -> "collage"
    RichBlock.Kind.Slideshow -> "slideshow"
    RichBlock.Kind.Channel -> "channel"
    RichBlock.Kind.Audio -> "audio"
    RichBlock.Kind.File -> "file"
    RichBlock.Kind.Math -> "math"
    RichBlock.Kind.Table -> "table"
    RichBlock.Kind.Details -> "details"
    RichBlock.Kind.RelatedArticles -> "related_articles"
    RichBlock.Kind.Map -> "map"
    RichBlock.Kind.InputMap -> "input_map"
    RichBlock.Kind.ButtonRow -> "button_row"
    RichBlock.Kind.Unknown -> "unsupported"
}

private fun SerializeRichBlock(context: RichSerializeContext, data: RichBlock): String {
    val values: Values = mutableListOf("type" to SerializeString(RichBlockKindToString(data.kind)))
    context.json.with(Ctx.kObject) {
        when (data.kind) {
            RichBlock.Kind.Unsupported -> {
                values.add("source_type" to SerializeString("page_block_unsupported"))
                values.add("unsupported" to "true")
            }
            RichBlock.Kind.Heading -> {
                values.add("level" to NumberToString(data.headingLevel))
                values.add("text" to SerializeRichText(context, data.text))
            }
            RichBlock.Kind.Paragraph, RichBlock.Kind.Footer, RichBlock.Kind.Thinking -> values.add("text" to SerializeRichText(context, data.text))
            RichBlock.Kind.AuthorDate -> {
                values.add("author" to SerializeRichText(context, data.text))
                values.add("date" to SerializeDate(data.date))
                values.add("date_unixtime" to SerializeDateRaw(data.date))
            }
            RichBlock.Kind.Code -> {
                values.add("text" to SerializeRichText(context, data.text))
                values.add("language" to SerializeString(data.language))
            }
            RichBlock.Kind.Divider -> Unit
            RichBlock.Kind.Anchor -> values.add("name" to SerializeString(data.name))
            RichBlock.Kind.List -> {
                values.add("kind" to SerializeString(RichListKindToString(data.listKind)))
                if (data.listKind == RichListKind.Ordered) {
                    values.add("reversed" to SerializeRichBool(data.orderedList.reversed))
                    data.orderedList.start?.let { values.add("start" to NumberToString(it)) }
                    data.orderedList.type?.let { values.add("list_type" to SerializeString(it)) }
                }
                values.add("items" to SerializeRichListItems(context, data.listItems))
            }
            RichBlock.Kind.Quote -> {
                values.add("content" to SerializeString(RichQuoteContentToString(data.quoteContent)))
                values.add("pullquote" to SerializeRichBool(data.pullquote))
                when (data.quoteContent) {
                    RichQuoteContent.Text -> values.add("text" to SerializeRichText(context, data.text))
                    RichQuoteContent.Blocks -> values.add("blocks" to SerializeRichBlocks(context, data.blocks))
                }
                values.add("caption" to SerializeRichText(context, data.quoteCaption))
            }
            RichBlock.Kind.Photo -> {
                values.add("photo_id" to SerializeRichUNumberString(data.photoId))
                AppendRichPhotoMetadata(values, FindRichPhoto(context, data.photoId), "photo", "photo_skip_reason", "photo_file_size", "width", "height")
                values.add("spoiler" to SerializeRichBool(data.spoiler))
                data.optionalUrl?.let { values.add("url" to SerializeString(it)) }
                data.optionalWebpageId?.let { values.add("webpage_id" to SerializeRichUNumberString(it)) }
                values.add("caption" to SerializeRichCaption(context, data.caption))
            }
            RichBlock.Kind.Video -> {
                values.add("document_id" to SerializeRichUNumberString(data.documentId))
                AppendRichDocumentMetadata(values, FindRichDocument(context, data.documentId), true)
                values.add("autoplay" to SerializeRichBool(data.autoplay))
                values.add("loop" to SerializeRichBool(data.loop))
                values.add("spoiler" to SerializeRichBool(data.spoiler))
                values.add("caption" to SerializeRichCaption(context, data.caption))
            }
            RichBlock.Kind.Cover -> {
                check(data.blocks.size == 1)
                values.add("block" to SerializeRichBlock(context, data.blocks.first()))
            }
            RichBlock.Kind.Embed -> {
                data.optionalUrl?.let { values.add("url" to SerializeString(it)) }
                data.html?.let { values.add("html" to SerializeString(it)) }
                data.posterPhotoId?.let { id ->
                    values.add("poster_photo_id" to SerializeRichUNumberString(id))
                    AppendRichPhotoMetadata(values, FindRichPhoto(context, id), "poster_photo", "poster_photo_skip_reason", "poster_photo_file_size", "poster_photo_width", "poster_photo_height")
                }
                data.width?.let { values.add("width" to NumberToString(it)) }
                data.height?.let { values.add("height" to NumberToString(it)) }
                values.add("full_width" to SerializeRichBool(data.fullWidth))
                values.add("allow_scrolling" to SerializeRichBool(data.allowScrolling))
                values.add("caption" to SerializeRichCaption(context, data.caption))
            }
            RichBlock.Kind.EmbedPost -> {
                values.add("url" to SerializeString(data.url))
                values.add("webpage_id" to SerializeRichUNumberString(data.webpageId))
                values.add("author_photo_id" to SerializeRichUNumberString(data.authorPhotoId))
                AppendRichPhotoMetadata(values, FindRichPhoto(context, data.authorPhotoId), "author_photo", "author_photo_skip_reason", "author_photo_file_size", "author_photo_width", "author_photo_height")
                values.add("author" to SerializeString(data.author))
                values.add("date" to SerializeDate(data.date))
                values.add("date_unixtime" to SerializeDateRaw(data.date))
                values.add("blocks" to SerializeRichBlocks(context, data.blocks))
                values.add("caption" to SerializeRichCaption(context, data.caption))
            }
            RichBlock.Kind.Collage, RichBlock.Kind.Slideshow -> {
                values.add("items" to SerializeRichBlocks(context, data.blocks))
                values.add("caption" to SerializeRichCaption(context, data.caption))
            }
            RichBlock.Kind.Channel -> values.add("channel" to SerializeRichChannel(context, data.channel))
            RichBlock.Kind.Audio, RichBlock.Kind.File -> {
                values.add("document_id" to SerializeRichUNumberString(data.documentId))
                AppendRichDocumentMetadata(values, FindRichDocument(context, data.documentId), true)
                values.add("caption" to SerializeRichCaption(context, data.caption))
            }
            RichBlock.Kind.Math -> values.add("formula" to SerializeString(data.formula))
            RichBlock.Kind.Table -> {
                values.add("title" to SerializeRichText(context, data.text))
                values.add("bordered" to SerializeRichBool(data.bordered))
                values.add("striped" to SerializeRichBool(data.striped))
                values.add("compact" to SerializeRichBool(data.compact))
                values.add("rows" to SerializeRichArray(context.json, data.tableRows) { SerializeRichTableRow(context, it) })
            }
            RichBlock.Kind.Details -> {
                values.add("title" to SerializeRichText(context, data.text))
                values.add("open" to SerializeRichBool(data.open))
                values.add("blocks" to SerializeRichBlocks(context, data.blocks))
            }
            RichBlock.Kind.RelatedArticles -> {
                values.add("title" to SerializeRichText(context, data.text))
                values.add("articles" to SerializeRichArray(context.json, data.relatedArticles) { SerializeRichRelatedArticle(context, it) })
            }
            RichBlock.Kind.Map, RichBlock.Kind.InputMap -> {
                values.add("geo" to SerializeRichMapPoint(context, data.mapPoint))
                values.add("zoom" to NumberToString(data.zoom))
                values.add("width" to NumberToString(data.mapWidth))
                values.add("height" to NumberToString(data.mapHeight))
                values.add("caption" to SerializeRichCaption(context, data.caption))
            }
            RichBlock.Kind.ButtonRow -> {
                values.add("alignment" to SerializeString(RichButtonAlignmentToString(data.buttonAlignment)))
                values.add("buttons" to SerializeRichButtons(context, data.buttons))
            }
            RichBlock.Kind.Unknown -> {
                values.add("source_type" to SerializeString("unknown"))
                values.add("unsupported" to "true")
            }
        }
    }
    return SerializeObject(context.json, values)
}

private fun SerializeRichBlocks(context: RichSerializeContext, data: List<RichBlock>): String =
    SerializeRichArray(context.json, data) { SerializeRichBlock(context, it) }

private fun SerializeRichMessage(context: Ctx, data: RichMessage): String {
    val richContext = RichSerializeContext(context, data)
    val values: Values = mutableListOf(
        "rtl" to SerializeRichBool(data.rtl),
        "part" to SerializeRichBool(data.part),
    )
    context.with(Ctx.kObject) { values.add("blocks" to SerializeRichBlocks(richContext, data.blocks)) }
    return SerializeObject(context, values)
}

private fun SerializeMessage(
    context: Ctx,
    message: Message,
    peers: Map<PeerId, Peer>,
    internalLinksDomain: String,
): String {
    if (message.media.content === UnsupportedMedia && message.richMessage == null) {
        return SerializeObject(
            context,
            listOf("id" to NumberToString(message.id), "type" to SerializeString("unsupported")),
        )
    }
    val emptyPeer = Peer(User())
    val emptyUser = User()
    fun peer(peerId: PeerId): Peer = peers[peerId] ?: emptyPeer
    fun user(userId: Long): User = peer(Peer_.fromUser(userId)).user() ?: emptyUser

    val values: Values = mutableListOf(
        "id" to NumberToString(message.id),
        "type" to SerializeString(if (message.action.content != null) "service" else "message"),
        "date" to SerializeDate(message.date),
        "date_unixtime" to SerializeDateRaw(message.date),
    )
    context.nesting.add(Ctx.kObject)
    fun serialized(): String {
        context.nesting.removeAt(context.nesting.size - 1)
        return SerializeObject(context, values)
    }

    fun pushBare(key: String, value: String) {
        if (value.isNotEmpty()) values.add(key to value)
    }
    if (message.edited != 0) {
        pushBare("edited", SerializeDate(message.edited))
        pushBare("edited_unixtime", SerializeDateRaw(message.edited))
    }
    fun wrapPeerId(peerId: PeerId): String {
        val chat = Peer_.toChat(peerId)
        if (chat != 0L) return SerializeString("chat" + UNumberToString(chat))
        val channel = Peer_.toChannel(peerId)
        if (channel != 0L) return SerializeString("channel" + UNumberToString(channel))
        return SerializeString("user" + UNumberToString(Peer_.toUser(peerId)))
    }
    // The type-dispatching `push` of tdesktop, split by value type.
    fun pushBool(key: String, value: Boolean) = pushBare(key, if (value) "true" else "false")
    fun pushNum(key: String, value: Long) = pushBare(key, NumberToString(value))
    fun pushNum(key: String, value: Int) = pushBare(key, NumberToString(value))
    fun pushUNum(key: String, value: Long) = pushBare(key, UNumberToString(value))
    fun pushPeer(key: String, value: PeerId) = pushBare(key, wrapPeerId(value))
    fun pushStr(key: String, value: String) {
        if (value.isNotEmpty()) pushBare(key, SerializeString(value))
    }
    fun wrapPeerName(peerId: PeerId) = StringAllowNull(peer(peerId).name())
    fun wrapUserName(userId: Long) = StringAllowNull(user(userId).name())
    fun pushFrom(label: String = "from") {
        if (message.fromId != 0L) {
            pushBare(label, wrapPeerName(message.fromId))
            pushPeer(label + "_id", message.fromId)
        }
    }
    fun pushReplyToMsgId(label: String = "reply_to_message_id") {
        if (message.replyToMsgId != 0) {
            pushNum(label, message.replyToMsgId)
            if (message.replyToPeerId != 0L) pushPeer("reply_to_peer_id", message.replyToPeerId)
        }
    }
    fun pushUserNames(data: List<Long>, label: String = "members") {
        pushBare(label, SerializeArray(context, data.map { wrapUserName(it) }))
    }
    fun pushActor() = pushFrom("actor")
    fun pushAction(action: String) = pushStr("action", action)
    fun pushTTL(label: String = "self_destruct_period_seconds") {
        val ttl = message.media.ttl
        if (ttl != 0) pushNum(label, ttl)
    }
    fun pushPath(file: org.tgbackup.export.data.File, label: String, name: String = "") {
        check(file.relativePath.isNotEmpty() || file.skipReason != SR.None)
        val pre = if (name.isEmpty()) "" else "$name "
        pushStr(
            label,
            when (file.skipReason) {
                SR.Unavailable -> pre + "(File unavailable, please try again later)"
                SR.FileSize -> pre + "(File exceeds maximum size. Change data exporting settings to download.)"
                SR.FileType -> pre + "(File not included. Change data exporting settings to download.)"
                SR.None -> FormatFilePath(file)
                SR.DateLimits -> throw IllegalStateException("Skip reason while writing file path.")
            },
        )
    }
    fun pushPhoto(image: Image) {
        pushPath(image.file, "photo")
        pushNum("photo_file_size", image.file.size)
        if (image.width != 0 && image.height != 0) {
            pushNum("width", image.width)
            pushNum("height", image.height)
        }
    }
    fun pushSpoiler(spoilered: Boolean) {
        if (spoilered) pushBool("media_spoiler", true)
    }

    when (val data = message.action.content) {
        is ActionChatCreate -> {
            pushActor()
            pushAction("create_group")
            pushStr("title", data.title)
            pushUserNames(data.userIds)
        }
        is ActionChatEditTitle -> {
            pushActor()
            pushAction("edit_group_title")
            pushStr("title", data.title)
        }
        is ActionChatEditPhoto -> {
            pushActor()
            pushAction("edit_group_photo")
            pushPhoto(data.photo.image)
            pushSpoiler(data.photo.spoilered)
        }
        ActionChatDeletePhoto -> {
            pushActor()
            pushAction("delete_group_photo")
        }
        is ActionChatAddUser -> {
            pushActor()
            pushAction("invite_members")
            pushUserNames(data.userIds)
        }
        is ActionChatDeleteUser -> {
            pushActor()
            pushAction("remove_members")
            pushUserNames(listOf(data.userId))
        }
        is ActionChatJoinedByLink -> {
            pushActor()
            pushAction("join_group_by_link")
            pushBare("inviter", wrapUserName(data.inviterId))
        }
        is ActionChannelCreate -> {
            pushActor()
            pushAction("create_channel")
            pushStr("title", data.title)
        }
        is ActionChatMigrateTo -> {
            pushActor()
            pushAction("migrate_to_supergroup")
        }
        is ActionChannelMigrateFrom -> {
            pushActor()
            pushAction("migrate_from_group")
            pushStr("title", data.title)
        }
        ActionPinMessage -> {
            pushActor()
            pushAction("pin_message")
            pushReplyToMsgId("message_id")
        }
        ActionHistoryClear -> {
            pushActor()
            pushAction("clear_history")
        }
        is ActionGameScore -> {
            pushActor()
            pushAction("score_in_game")
            pushReplyToMsgId("game_message_id")
            pushNum("score", data.score)
        }
        is ActionPaymentSent -> {
            pushAction("send_payment")
            pushUNum("amount", data.amount)
            pushStr("currency", data.currency)
            pushReplyToMsgId("invoice_message_id")
            if (data.recurringUsed) pushStr("recurring", "used") else if (data.recurringInit) pushStr("recurring", "init")
        }
        is ActionPhoneCall -> {
            pushActor()
            pushAction(if (data.conferenceId != 0L) "conference_call" else "phone_call")
            if (data.duration != 0) pushNum("duration_seconds", data.duration)
            if (data.conferenceId != 0L) {
                pushBool("is_active", data.state == ActionPhoneCall.State.Active)
                pushBool("is_missed", data.state == ActionPhoneCall.State.Missed)
            } else {
                pushStr(
                    "discard_reason",
                    when (data.state) {
                        ActionPhoneCall.State.Busy -> "busy"
                        ActionPhoneCall.State.Disconnect -> "disconnect"
                        ActionPhoneCall.State.Hangup -> "hangup"
                        ActionPhoneCall.State.Missed -> "missed"
                        ActionPhoneCall.State.MigrateConferenceCall -> "migrate_conference_all"
                        else -> ""
                    },
                )
            }
        }
        ActionScreenshotTaken -> {
            pushActor()
            pushAction("take_screenshot")
        }
        is ActionCustomAction -> {
            pushActor()
            pushStr("information_text", data.message)
        }
        is ActionBotAllowed -> {
            if (data.attachMenu) {
                pushAction("attach_menu_bot_allowed")
            } else if (data.fromRequest) {
                pushAction("web_app_bot_allowed")
            } else if (data.appId != 0L) {
                pushAction("allow_sending_messages")
                pushUNum("reason_app_id", data.appId)
                pushStr("reason_app_name", data.app)
            } else {
                pushAction("allow_sending_messages")
                pushStr("reason_domain", data.domain)
            }
        }
        is ActionSecureValuesSent -> {
            pushAction("send_passport_values")
            val list = data.types.map { type ->
                SerializeString(
                    when (type) {
                        ActionSecureValuesSent.Type.PersonalDetails -> "personal_details"
                        ActionSecureValuesSent.Type.Passport -> "passport"
                        ActionSecureValuesSent.Type.DriverLicense -> "driver_license"
                        ActionSecureValuesSent.Type.IdentityCard -> "identity_card"
                        ActionSecureValuesSent.Type.InternalPassport -> "internal_passport"
                        ActionSecureValuesSent.Type.Address -> "address_information"
                        ActionSecureValuesSent.Type.UtilityBill -> "utility_bill"
                        ActionSecureValuesSent.Type.BankStatement -> "bank_statement"
                        ActionSecureValuesSent.Type.RentalAgreement -> "rental_agreement"
                        ActionSecureValuesSent.Type.PassportRegistration -> "passport_registration"
                        ActionSecureValuesSent.Type.TemporaryRegistration -> "temporary_registration"
                        ActionSecureValuesSent.Type.Phone -> "phone_number"
                        ActionSecureValuesSent.Type.Email -> "email"
                    },
                )
            }
            pushBare("values", SerializeArray(context, list))
        }
        ActionContactSignUp -> {
            pushActor()
            pushAction("joined_telegram")
        }
        is ActionGeoProximityReached -> {
            pushAction("proximity_reached")
            if (data.fromId != 0L) {
                pushBare("from", wrapPeerName(data.fromId))
                pushPeer("from_id", data.fromId)
            }
            if (data.toId != 0L) {
                pushBare("to", wrapPeerName(data.toId))
                pushPeer("to_id", data.toId)
            }
            pushNum("distance", data.distance)
        }
        ActionPhoneNumberRequest -> {
            pushActor()
            pushAction("requested_phone_number")
        }
        is ActionGroupCall -> {
            pushActor()
            pushAction("group_call")
            if (data.duration != 0) pushNum("duration", data.duration)
        }
        is ActionInviteToGroupCall -> {
            pushActor()
            pushAction("invite_to_group_call")
            pushUserNames(data.userIds)
        }
        is ActionSetMessagesTTL -> {
            pushActor()
            pushAction("set_messages_ttl")
            pushNum("period", data.period)
        }
        is ActionGroupCallScheduled -> {
            pushActor()
            pushAction("group_call_scheduled")
            pushNum("schedule_date", data.date)
        }
        is ActionSetChatTheme -> {
            pushActor()
            pushAction("edit_chat_theme")
            if (data.emoji.isNotEmpty()) pushStr("emoticon", data.emoji)
        }
        ActionChatJoinedByRequest -> {
            pushActor()
            pushAction("join_group_by_request")
        }
        is ActionChatJoinedViaCommunity -> {
            pushActor()
            pushAction("join_group_via_community")
            pushUNum("community_id", data.communityId)
        }
        is ActionWebViewDataSent -> {
            pushAction("send_webview_data")
            pushStr("text", data.text)
        }
        is ActionGiftPremium -> {
            pushActor()
            pushAction("send_premium_gift")
            if (data.cost.isNotEmpty()) pushStr("cost", data.cost)
            if (data.days != 0) pushNum("days", data.days)
        }
        is ActionTopicCreate -> {
            pushActor()
            pushAction("topic_created")
            pushStr("title", data.title)
        }
        is ActionTopicEdit -> {
            pushActor()
            pushAction("topic_edit")
            if (data.title.isNotEmpty()) pushStr("new_title", data.title)
            data.iconEmojiId?.let { pushUNum("new_icon_emoji_id", it) }
        }
        is ActionSuggestProfilePhoto -> {
            pushActor()
            pushAction("suggest_profile_photo")
            pushPhoto(data.photo.image)
            pushSpoiler(data.photo.spoilered)
        }
        is ActionRequestedPeer -> {
            pushActor()
            pushAction("requested_peer")
            pushNum("button_id", data.buttonId)
            val list = data.peers.map { UNumberToString(it) }
            // tdesktop pushes the serialized array as a string value.
            pushStr("peers", SerializeArray(context, list))
        }
        is ActionGiftCode -> {
            pushAction("gift_code_prize")
            pushStr("gift_code", data.code)
            if (data.boostPeerId != 0L) pushPeer("boost_peer_id", data.boostPeerId)
            pushNum("days", data.days)
            pushBool("unclaimed", data.unclaimed)
            pushBool("via_giveaway", data.viaGiveaway)
        }
        ActionGiveawayLaunch -> pushAction("giveaway_launch")
        is ActionGiveawayResults -> {
            pushAction("giveaway_results")
            pushNum("winners", data.winners)
            pushNum("unclaimed", data.unclaimed)
            pushBool("stars", data.credits)
        }
        is ActionSetChatWallPaper -> {
            pushActor()
            pushAction(if (data.same) "set_same_chat_wallpaper" else "set_chat_wallpaper")
            pushReplyToMsgId("message_id")
        }
        is ActionBoostApply -> {
            pushActor()
            pushAction("boost_apply")
            pushNum("boosts", data.boosts)
        }
        is ActionPaymentRefunded -> {
            pushAction("refunded_payment")
            pushUNum("amount", data.amount)
            pushStr("currency", data.currency)
            pushBare("peer_name", wrapPeerName(data.peerId))
            pushPeer("peer_id", data.peerId)
            pushStr("charge_id", data.transactionId)
        }
        is ActionGiftCredits -> {
            pushActor()
            pushAction(if (data.amount.ton) "send_ton_gift" else "send_stars_gift")
            if (data.cost.isNotEmpty()) pushStr("cost", data.cost)
            if (!data.amount.empty()) {
                pushNum("amount_whole", data.amount.whole)
                pushNum("amount_nano", data.amount.nano)
            }
        }
        is ActionPrizeStars -> {
            pushActor()
            pushAction("stars_prize")
            pushPeer("boost_peer_id", data.peerId)
            pushBare("boost_peer_name", wrapPeerName(data.peerId))
            pushUNum("stars", data.amount)
            pushBool("is_unclaimed", data.isUnclaimed)
            pushNum("giveaway_msg_id", data.giveawayMsgId)
            pushStr("transaction_id", data.transactionId)
        }
        is ActionStarGift -> {
            pushActor()
            pushAction("send_star_gift")
            pushUNum("gift_id", data.giftId)
            pushNum("stars", data.stars)
            pushBool("is_limited", data.limited)
            pushBool("is_anonymous", data.anonymous)
            pushBare("gift_text", SerializeText(context, data.text))
        }
        is ActionPaidMessagesRefunded -> {
            pushActor()
            pushAction("paid_messages_refund")
            pushNum("messages_count", data.messages)
            pushNum("stars_count", data.stars)
        }
        is ActionPaidMessagesPrice -> {
            pushActor()
            pushAction("paid_messages_price_change")
            pushNum("price_stars", data.stars)
            pushBool("is_broadcast_messages_allowed", data.broadcastAllowed)
        }
        is ActionTodoCompletions -> {
            pushActor()
            pushAction("todo_completions")
            pushBare("completed", "[" + data.completed.joinToString(",") + "]")
            pushBare("incompleted", "[" + data.incompleted.joinToString(",") + "]")
        }
        is ActionTodoAppendTasks -> {
            pushActor()
            pushAction("todo_append_tasks")
            val items = data.items.map { item ->
                context.with(Ctx.kArray) {
                    SerializeObject(
                        context,
                        listOf("text" to SerializeText(context, item.text), "id" to NumberToString(item.id)),
                    )
                }
            }
            pushBare("items", SerializeArray(context, items))
        }
        is ActionPollAppendAnswer -> {
            pushActor()
            pushAction("poll_append_answer")
            pushStr("option", data.option)
        }
        is ActionPollDeleteAnswer -> {
            pushActor()
            pushAction("poll_delete_answer")
            pushStr("option", data.option)
        }
        is ActionSuggestedPostApproval -> {
            pushActor()
            pushAction("process_suggested_post")
            if (data.rejected) {
                pushBare("rejected", "true")
                if (data.rejectComment.isNotEmpty()) pushStr("comment", data.rejectComment)
            } else {
                pushStr("price_amount_whole", NumberToString(data.price.whole))
                pushStr("price_amount_nano", NumberToString(data.price.nano))
                pushStr("price_currency", if (data.price.ton) "TON" else "Stars")
                pushNum("scheduled_date", data.scheduleDate)
            }
        }
        is ActionSuggestedPostSuccess -> {
            pushActor()
            pushAction("suggested_post_success")
            pushStr("price_amount_whole", NumberToString(data.price.whole))
            pushStr("price_amount_nano", NumberToString(data.price.nano))
            pushStr("price_currency", if (data.price.ton) "TON" else "Stars")
        }
        is ActionSuggestedPostRefund -> {
            pushActor()
            pushAction("suggested_post_refund")
            pushBool("user_initiated", data.payerInitiated)
        }
        is ActionSuggestBirthday -> {
            pushActor()
            pushAction("suggest_birthday")
            pushNum("day", data.birthday.day())
            pushNum("month", data.birthday.month())
            val year = data.birthday.year()
            if (year != 0) pushNum("year", year)
        }
        is ActionNoForwardsToggle -> {
            pushActor()
            pushAction("no_forwards_toggle")
            pushBool("new_value", data.newValue)
        }
        is ActionNoForwardsRequest -> {
            pushActor()
            pushAction("no_forwards_request")
            pushBool("expired", data.expired)
            pushBool("new_value", data.newValue)
        }
        is ActionNewCreatorPending -> {
            pushActor()
            pushAction("new_creator_pending")
            pushBare("new_creator", wrapUserName(data.newCreatorId))
        }
        is ActionChangeCreator -> {
            pushActor()
            pushAction("change_creator")
            pushBare("new_creator", wrapUserName(data.newCreatorId))
        }
        is ActionManagedBotCreated -> {
            pushActor()
            pushAction("managed_bot_created")
            pushBare("bot", wrapUserName(data.botId))
        }
        null -> Unit
    }

    if (message.action.content == null) {
        pushFrom()
        pushStr("author", message.signature)
        if (message.forwardedFromId != 0L) {
            pushBare("forwarded_from", wrapPeerName(message.forwardedFromId))
            pushPeer("forwarded_from_id", message.forwardedFromId)
        } else if (message.forwardedFromName.isNotEmpty()) {
            pushBare("forwarded_from", StringAllowNull(message.forwardedFromName))
        }
        if (message.savedFromChatId != 0L) pushBare("saved_from", wrapPeerName(message.savedFromChatId))
        pushReplyToMsgId()
        if (message.viaBotId != 0L) {
            val username = FormatUsername(user(message.viaBotId).username)
            if (username.isNotEmpty()) pushStr("via_bot", username)
        }
    }

    when (val data = message.media.content) {
        is Photo -> {
            pushPhoto(data.image)
            pushSpoiler(data.spoilered)
            pushTTL()
        }
        is Document -> {
            pushPath(data.file, "file")
            pushStr("file_name", data.name)
            pushNum("file_size", data.file.size)
            if (data.thumb.width > 0) {
                pushPath(data.thumb.file, "thumbnail")
                pushNum("thumbnail_file_size", data.thumb.file.size)
            }
            fun pushType(value: String) = pushStr("media_type", value)
            if (data.isSticker) {
                pushType("sticker")
                pushStr("sticker_emoji", data.stickerEmoji)
            } else if (data.isVideoMessage) {
                pushType("video_message")
            } else if (data.isVoiceMessage) {
                pushType("voice_message")
            } else if (data.isAnimated) {
                pushType("animation")
            } else if (data.isVideoFile) {
                pushType("video_file")
            } else if (data.isAudioFile) {
                pushType("audio_file")
                pushStr("performer", data.songPerformer)
                pushStr("title", data.songTitle)
            }
            pushStr("mime_type", data.mime)
            if (data.duration != 0) pushNum("duration_seconds", data.duration)
            if (data.width != 0 && data.height != 0) {
                pushNum("width", data.width)
                pushNum("height", data.height)
            }
            pushSpoiler(data.spoilered)
            pushTTL()
        }
        is SharedContact -> {
            pushBare(
                "contact_information",
                SerializeObject(
                    context,
                    listOf(
                        "first_name" to SerializeString(data.info.firstName),
                        "last_name" to SerializeString(data.info.lastName),
                        "phone_number" to SerializeString(FormatPhoneNumber(data.info.phoneNumber)),
                    ),
                ),
            )
            if (data.vcard.content.isNotEmpty()) {
                pushPath(data.vcard, "contact_vcard")
                pushNum("contact_vcard_file_size", data.vcard.size)
            }
        }
        is GeoPoint -> {
            pushBare(
                "location_information",
                if (data.valid) {
                    SerializeObject(
                        context,
                        listOf("latitude" to NumberToString(data.latitude), "longitude" to NumberToString(data.longitude)),
                    )
                } else {
                    "null"
                },
            )
            pushTTL("live_location_period_seconds")
        }
        is Venue -> {
            pushStr("place_name", data.title)
            pushStr("address", data.address)
            if (data.point.valid) {
                pushBare(
                    "location_information",
                    SerializeObject(
                        context,
                        listOf(
                            "latitude" to NumberToString(data.point.latitude),
                            "longitude" to NumberToString(data.point.longitude),
                        ),
                    ),
                )
            }
        }
        is Game -> {
            pushStr("game_title", data.title)
            pushStr("game_description", data.description)
            if (data.botId != 0L && data.shortName.isNotEmpty()) {
                val bot = user(data.botId)
                if (bot.isBot && bot.username.isNotEmpty()) {
                    pushStr("game_link", internalLinksDomain + bot.username + "?game=" + data.shortName)
                }
            }
        }
        is Invoice -> {
            // tdesktop pushes the serialized object as a string value.
            pushStr(
                "invoice_information",
                SerializeObject(
                    context,
                    listOf(
                        "title" to SerializeString(data.title),
                        "description" to SerializeString(data.description),
                        "amount" to UNumberToString(data.amount),
                        "currency" to SerializeString(data.currency),
                        "receipt_message_id" to (if (data.receiptMsgId != 0) NumberToString(data.receiptMsgId) else ""),
                    ),
                ),
            )
        }
        is Poll -> {
            context.nesting.add(Ctx.kObject)
            val answers = data.answers.map { answer ->
                context.with(Ctx.kArray) {
                    SerializeObject(
                        context,
                        listOf(
                            "text" to SerializeText(context, answer.text),
                            "voters" to NumberToString(answer.votes),
                            "chosen" to (if (answer.my) "true" else "false"),
                        ),
                    )
                }
            }
            val serialized = SerializeArray(context, answers)
            context.nesting.removeAt(context.nesting.size - 1)
            pushBare(
                "poll",
                SerializeObject(
                    context,
                    listOf(
                        "question" to SerializeText(context, data.question),
                        "closed" to (if (data.closed) "true" else "false"),
                        "total_voters" to NumberToString(data.totalVotes),
                        "answers" to serialized,
                    ),
                ),
            )
        }
        is TodoList -> {
            context.nesting.add(Ctx.kObject)
            val items = data.items.map { item ->
                context.with(Ctx.kArray) {
                    SerializeObject(context, listOf("text" to SerializeText(context, item.text), "id" to NumberToString(item.id)))
                }
            }
            val serialized = SerializeArray(context, items)
            context.nesting.removeAt(context.nesting.size - 1)
            pushBare(
                "todo_list",
                SerializeObject(
                    context,
                    listOf(
                        "title" to SerializeText(context, data.title),
                        "others_can_append" to (if (data.othersCanAppend) "true" else "false"),
                        "others_can_complete" to (if (data.othersCanComplete) "true" else "false"),
                        "answers" to serialized,
                    ),
                ),
            )
        }
        is GiveawayStart -> {
            val serialized = context.with(Ctx.kArray) { SerializeArray(context, data.channels.map { UNumberToString(it) }) }
            val serializedCountries = context.with(Ctx.kArray) { SerializeArray(context, data.countries.map { SerializeString(it) }) }
            pushBare(
                "giveaway_information",
                SerializeObject(
                    context,
                    listOf(
                        "quantity" to NumberToString(data.quantity),
                        "months" to NumberToString(data.months),
                        "until_date" to SerializeDate(data.untilDate),
                        "channels" to serialized,
                        "countries" to serializedCountries,
                        "additional_prize" to SerializeString(data.additionalPrize),
                        "stars" to UNumberToString(data.credits),
                        "is_only_new_subscribers" to (if (!data.all) "true" else "false"),
                    ),
                ),
            )
        }
        is GiveawayResults -> {
            val serialized = context.with(Ctx.kArray) { SerializeArray(context, data.winners.map { UNumberToString(it) }) }
            pushBare(
                "giveaway_results",
                SerializeObject(
                    context,
                    listOf(
                        "channel" to UNumberToString(data.channel),
                        "winners" to serialized,
                        "additional_prize" to SerializeString(data.additionalPrize),
                        "until_date" to SerializeDate(data.untilDate),
                        "launch_message_id" to NumberToString(data.launchId),
                        "additional_peers_count" to NumberToString(data.additionalPeersCount),
                        "winners_count" to NumberToString(data.winnersCount),
                        "unclaimed_count" to NumberToString(data.unclaimedCount),
                        "months" to NumberToString(data.months),
                        "stars" to UNumberToString(data.credits),
                        "is_refunded" to (if (data.refunded) "true" else "false"),
                        "is_only_new_subscribers" to (if (!data.all) "true" else "false"),
                    ),
                ),
            )
        }
        is PaidMedia -> pushUNum("paid_stars_amount", data.stars)
        UnsupportedMedia -> check(message.richMessage != null)
        null -> Unit
    }

    val rich = message.richMessage
    if (rich != null) {
        pushBare("rich_message", SerializeRichMessage(context, rich))
    } else {
        pushBare("text", SerializeText(context, message.text))
        pushBare("text_entities", SerializeText(context, message.text, true))
    }

    if (message.inlineButtonRows.isNotEmpty()) {
        fun serializeRow(row: List<HistoryMessageMarkupButton>): String {
            context.nesting.add(Ctx.kArray)
            val buttons = row.map { entry ->
                val pairs = ArrayList<Pair<String, String>>()
                pairs.add("type" to SerializeString(HistoryMessageMarkupButton.TypeToString(entry)))
                if (entry.text.isNotEmpty()) pairs.add("text" to SerializeString(entry.text))
                if (entry.data.isNotEmpty()) {
                    val isCallback = entry.type == HistoryMessageMarkupButton.Type.Callback ||
                        entry.type == HistoryMessageMarkupButton.Type.CallbackWithPassword
                    if (isCallback) {
                        pairs.add("dataBase64" to SerializeString(Base64Url(entry.data)))
                        pairs.add("data" to SerializeString(""))
                    } else {
                        pairs.add("data" to SerializeString(String(entry.data, Charsets.UTF_8)))
                    }
                }
                if (entry.forwardText.isNotEmpty()) pairs.add("forward_text" to SerializeString(entry.forwardText))
                if (entry.buttonId != 0L) pairs.add("button_id" to NumberToString(entry.buttonId))
                SerializeObject(context, pairs)
            }
            context.nesting.removeAt(context.nesting.size - 1)
            return SerializeArray(context, buttons)
        }
        context.nesting.add(Ctx.kArray)
        val rows = message.inlineButtonRows.map { serializeRow(it) }
        context.nesting.removeAt(context.nesting.size - 1)
        pushBare("inline_bot_buttons", SerializeArray(context, rows))
    }

    if (message.reactions.isNotEmpty()) {
        fun serializeReaction(reaction: Reaction): String = context.with(Ctx.kObject) {
            val pairs = ArrayList<Pair<String, String>>()
            pairs.add("type" to SerializeString(Reaction.TypeToString(reaction)))
            pairs.add("count" to NumberToString(reaction.count))
            when (reaction.type) {
                Reaction.Type.Emoji -> pairs.add("emoji" to SerializeString(reaction.emoji))
                Reaction.Type.CustomEmoji -> pairs.add("document_id" to SerializeString(reaction.documentId))
                else -> Unit
            }
            if (reaction.recent.isNotEmpty()) {
                context.nesting.add(Ctx.kArray)
                val recents = reaction.recent.map { recent ->
                    context.with(Ctx.kArray) {
                        SerializeObject(
                            context,
                            listOf(
                                "from" to wrapPeerName(recent.peerId),
                                "from_id" to wrapPeerId(recent.peerId),
                                "date" to SerializeDate(recent.date),
                            ),
                        )
                    }
                }
                pairs.add("recent" to SerializeArray(context, recents))
                context.nesting.removeAt(context.nesting.size - 1)
            }
            SerializeObject(context, pairs)
        }
        context.nesting.add(Ctx.kArray)
        val reactions = message.reactions.map { serializeReaction(it) }
        pushBare("reactions", SerializeArray(context, reactions))
        context.nesting.removeAt(context.nesting.size - 1)
    }

    return serialized()
}

class JsonWriter : AbstractWriter {
    private enum class DialogsMode { None, Chats, Left }

    private var settings = Settings()
    private var environment = Environment()
    private var stats: Stats? = null
    private val context = JsonContext()
    private var currentNestingHadItem = false
    private var dialogsMode = DialogsMode.None
    private var output: File? = null

    override fun format() = Format.Json

    override fun start(settings: Settings, environment: Environment, stats: Stats?): Result {
        check(output == null)
        check(settings.path.endsWith('/'))
        this.settings = settings.copy()
        this.environment = environment
        this.stats = stats
        output = fileWithRelativePath(mainFileRelativePath())
        if (this.settings.onlySinglePeer()) return Result.Success()
        val block = StringBuilder(pushNesting(Ctx.kObject))
        block.append(prepareObjectItemStart("about"))
        block.append(SerializeString(environment.aboutTelegram))
        return output!!.writeBlock(block.toString())
    }

    private fun pushNesting(type: Boolean): String {
        context.nesting.add(type)
        currentNestingHadItem = false
        return if (type == Ctx.kObject) "{" else "["
    }

    private fun prepareObjectItemStart(key: String): String {
        val result = (if (currentNestingHadItem) ",\n" else "\n") + Indentation(context) + SerializeString(key) + ": "
        currentNestingHadItem = true
        return result
    }

    private fun prepareArrayItemStart(): String {
        val result = (if (currentNestingHadItem) ",\n" else "\n") + Indentation(context)
        currentNestingHadItem = true
        return result
    }

    private fun popNesting(): String {
        check(context.nesting.isNotEmpty())
        val type = context.nesting.removeAt(context.nesting.size - 1)
        currentNestingHadItem = true
        return "\n" + Indentation(context) + (if (type == Ctx.kObject) '}' else ']')
    }

    private fun unsupported(): Nothing = throw UnsupportedOperationException("Only single chat export is supported.")

    override fun writePersonal(data: PersonalInfo): Result = unsupported()
    override fun writeUserpicsStart(data: UserpicsInfo): Result = unsupported()
    override fun writeUserpicsSlice(data: UserpicsSlice): Result = unsupported()
    override fun writeUserpicsEnd(): Result = unsupported()
    override fun writeStoriesStart(data: StoriesInfo): Result = unsupported()
    override fun writeStoriesSlice(data: StoriesSlice): Result = unsupported()
    override fun writeStoriesEnd(): Result = unsupported()
    override fun writeProfileMusicStart(data: ProfileMusicInfo): Result = unsupported()
    override fun writeProfileMusicSlice(data: ProfileMusicSlice): Result = unsupported()
    override fun writeProfileMusicEnd(): Result = unsupported()
    override fun writeContactsList(data: ContactsList): Result = unsupported()
    override fun writeSessionsList(data: SessionsList): Result = unsupported()
    override fun writeOtherData(data: org.tgbackup.export.data.File): Result = unsupported()

    override fun writeDialogsStart(data: DialogsInfo): Result = Result.Success()

    override fun writeDialogStart(data: DialogInfo): Result {
        checkNotNull(output)
        if (!settings.onlySinglePeer()) {
            val result = validateDialogsMode(data.isLeftChannel)
            if (!result.isSuccess()) return result
        }
        val typeString = when (data.type) {
            DialogInfo.Type.Unknown -> ""
            DialogInfo.Type.Self -> "saved_messages"
            DialogInfo.Type.Replies -> "replies"
            DialogInfo.Type.VerifyCodes -> "verification_codes"
            DialogInfo.Type.Personal -> "personal_chat"
            DialogInfo.Type.Bot -> "bot_chat"
            DialogInfo.Type.PrivateGroup -> "private_group"
            DialogInfo.Type.PrivateSupergroup -> "private_supergroup"
            DialogInfo.Type.PublicSupergroup -> "public_supergroup"
            DialogInfo.Type.PrivateChannel -> "private_channel"
            DialogInfo.Type.PublicChannel -> "public_channel"
        }
        val block = StringBuilder(if (settings.onlySinglePeer()) "" else prepareArrayItemStart())
        block.append(pushNesting(Ctx.kObject))
        if (data.type != DialogInfo.Type.Self && data.type != DialogInfo.Type.Replies && data.type != DialogInfo.Type.VerifyCodes) {
            block.append(prepareObjectItemStart("name") + StringAllowNull(data.name))
        }
        block.append(prepareObjectItemStart("type") + StringAllowNull(typeString))
        block.append(prepareObjectItemStart("id") + UNumberToString(PeerToBareId(data.peerId)))
        block.append(prepareObjectItemStart("messages"))
        block.append(pushNesting(Ctx.kArray))
        return output!!.writeBlock(block.toString())
    }

    private fun validateDialogsMode(isLeftChannel: Boolean): Result {
        val mode = if (isLeftChannel) DialogsMode.Left else DialogsMode.Chats
        if (dialogsMode == mode) return Result.Success()
        if (dialogsMode != DialogsMode.None) {
            val result = writeChatsEnd()
            if (!result.isSuccess()) return result
        }
        dialogsMode = mode
        return writeChatsStart(
            if (isLeftChannel) "left_chats" else "chats",
            if (isLeftChannel) environment.aboutLeftChats else environment.aboutChats,
        )
    }

    override fun writeDialogSlice(data: MessagesSlice): Result {
        checkNotNull(output)
        val block = StringBuilder()
        for (message in data.list) {
            if (SkipMessageByDate(message, settings)) continue
            block.append(prepareArrayItemStart() + SerializeMessage(context, message, data.peers, environment.internalLinksDomain))
        }
        return if (block.isEmpty()) Result.Success() else output!!.writeBlock(block.toString())
    }

    override fun writeDialogEnd(): Result {
        val block = popNesting()
        return output!!.writeBlock(block + popNesting())
    }

    override fun writeDialogsEnd(): Result {
        if (settings.onlySinglePeer()) return Result.Success()
        return writeChatsEnd()
    }

    private fun writeChatsStart(listName: String, about: String): Result {
        val block = StringBuilder(prepareObjectItemStart(listName))
        block.append(pushNesting(Ctx.kObject))
        block.append(prepareObjectItemStart("about"))
        block.append(SerializeString(about))
        block.append(prepareObjectItemStart("list"))
        return output!!.writeBlock(block.toString() + pushNesting(Ctx.kArray))
    }

    private fun writeChatsEnd(): Result {
        val block = popNesting()
        return output!!.writeBlock(block + popNesting())
    }

    override fun finish(): Result {
        checkNotNull(output)
        if (settings.onlySinglePeer()) {
            check(context.nesting.isEmpty())
            output!!.close()
            return Result.Success()
        }
        val block = popNesting()
        check(context.nesting.isEmpty())
        val result = output!!.writeBlock(block)
        output!!.close()
        return result
    }

    override fun mainFilePath(): String = pathWithRelativePath(mainFileRelativePath())

    private fun mainFileRelativePath(): String = "result.json"
    private fun pathWithRelativePath(path: String): String = settings.path + path
    private fun fileWithRelativePath(path: String): File = File(pathWithRelativePath(path), stats)
}
