// Port of RichHtmlRenderer from tdesktop
// Telegram/SourceFiles/export/output/export_output_html.cpp
@file:Suppress("FunctionName")

package org.tgbackup.export.output

import org.tgbackup.export.data.*
import java.util.IdentityHashMap
import java.util.TreeMap

internal class RichMediaCallbacks(
    val photo: (Photo?) -> String,
    val video: (Document?) -> String,
    val audio: (Document?) -> String,
    val file: (Document?) -> String,
    val generic: (MediaData) -> String,
    val photoCard: (MediaData, Photo?) -> String,
)

private enum class RichSourceMetaKind { Source, EmbedPost }
private enum class RichTailState { Empty, Media, Other }

private fun RichBlockAttributes(classes: String, kind: String): Attrs =
    attrs("class" to "rich_block $classes", "data-rich-kind" to kind)

private fun RichHeadingTag(level: Int): String = when (level) {
    1 -> "h1"
    2 -> "h2"
    3 -> "h3"
    4 -> "h4"
    5 -> "h5"
    else -> "h6"
}

private fun RichBoolAttribute(value: Boolean) = if (value) "true" else "false"

private fun RichButtonMetadata(value: String): String = PercentEncoding(value)

private fun RichButtonPeerTypes(types: List<InlineButtonPeerType>): String {
    val result = StringBuilder("[")
    var first = true
    for (type in types) {
        if (!first) result.append(',')
        first = false
        result.append('"').append(InlineButtonPeerTypeToString(type)).append('"')
    }
    return RichButtonMetadata(result.append(']').toString())
}

private fun RichButtonAttributes(action: InlineButtonAction, style: RichButtonStyle?, inlineButton: Boolean): Attrs {
    val T = InlineButtonAction.Type.Url.javaClass
    val resolvedStyle = style ?: RichButtonStyle.Default
    val styleName = RichButtonStyleToString(resolvedStyle)
    val classes = "rich_button " + (if (inlineButton) "rich_button_inline" else "rich_button_block") +
        " rich_button_style_" + styleName + " rich_button_action_" + InlineButtonActionTypeToString(action)
    val result = attrs(
        "class" to classes,
        "data-button-type" to InlineButtonActionTypeToString(action),
        "inline" to "",
    )
    if (style != null) result.emplace("data-button-style", styleName)
    when (action.type) {
        InlineButtonAction.Type.Url, InlineButtonAction.Type.Auth, InlineButtonAction.Type.WebView -> {
            result.emplace("data-button-url", RichButtonMetadata(action.url))
            if (action.type == InlineButtonAction.Type.Auth) {
                action.forwardText?.let { result.emplace("data-button-forward-text", RichButtonMetadata(it)) }
                result.emplace("data-button-id", NumberToString(action.buttonId))
            }
        }
        InlineButtonAction.Type.Callback, InlineButtonAction.Type.CallbackWithPassword -> {
            result.emplace(
                "data-callback-data-base64",
                org.tgbackup.mtproto.B64.urlNoPad(action.callbackData),
            )
            result.emplace("data-button-requires-password", RichBoolAttribute(action.requiresPassword))
        }
        InlineButtonAction.Type.Game, InlineButtonAction.Type.Buy, InlineButtonAction.Type.Disabled -> Unit
        InlineButtonAction.Type.SwitchInline, InlineButtonAction.Type.SwitchInlineSame -> {
            result.emplace("data-button-query", RichButtonMetadata(action.query))
            result.emplace("data-button-same-peer", RichBoolAttribute(action.samePeer))
            action.peerTypes?.let { result.emplace("data-button-peer-types", RichButtonPeerTypes(it)) }
        }
        InlineButtonAction.Type.UserProfile ->
            result.emplace("data-button-user-id", java.lang.Long.toUnsignedString(action.userId))
        InlineButtonAction.Type.CopyText -> result.emplace("data-copy-text", RichButtonMetadata(action.copyText))
    }
    if (action.type == InlineButtonAction.Type.Disabled) result.emplace("aria-disabled", "true")
    @Suppress("UNUSED_VARIABLE") val unused = T
    return result
}

private fun RichPercentValue(value: Int, total: Int): String = FixedNumber(value * 100.0 / total, 3) + '%'

private fun RichAspectRatioStyle(size: QSize): String =
    "aspect-ratio: " + NumberToString(size.width) + " / " + NumberToString(size.height)

private fun RichTextHasOutput(text: RichText): Boolean = when (text.type) {
    RichText.Type.Empty -> false
    RichText.Type.Plain -> text.text.isNotEmpty()
    RichText.Type.Concat -> text.children.any { RichTextHasOutput(it) }
    else -> true
}

private fun RichCaptionHasOutput(caption: RichCaption): Boolean =
    RichTextHasOutput(caption.text) || RichTextHasOutput(caption.credit)

private fun RichChannelSourceName(source: RichChannel.Source): String = when (source) {
    RichChannel.Source.ChatEmpty -> "chat_empty"
    RichChannel.Source.Chat -> "chat"
    RichChannel.Source.ChatForbidden -> "chat_forbidden"
    RichChannel.Source.Channel -> "channel"
    RichChannel.Source.ChannelForbidden -> "channel_forbidden"
    RichChannel.Source.Community -> "community"
    RichChannel.Source.CommunityForbidden -> "community_forbidden"
}

private fun RichChannelStatus(channel: RichChannel): String {
    val label = when (channel.source) {
        RichChannel.Source.ChatEmpty, RichChannel.Source.Chat -> "Chat"
        RichChannel.Source.ChatForbidden -> "Chat unavailable"
        RichChannel.Source.Channel -> "Channel"
        RichChannel.Source.ChannelForbidden -> "Channel unavailable"
        RichChannel.Source.Community -> "Community"
        RichChannel.Source.CommunityForbidden -> "Community unavailable"
    }
    val username = channel.username
    return if (username != null && username.isNotEmpty()) "@$username, $label" else label
}

private fun RichMapPointSourceName(source: RichMapPoint.Source): String = when (source) {
    RichMapPoint.Source.GeoPointEmpty -> "geo_point_empty"
    RichMapPoint.Source.GeoPoint -> "geo_point"
    RichMapPoint.Source.InputGeoPointEmpty -> "input_geo_point_empty"
    RichMapPoint.Source.InputGeoPoint -> "input_geo_point"
}

private class RichMapCoordinateValues(val latitude: String, val longitude: String)

private fun RichMapCoordinates(point: RichMapPoint): RichMapCoordinateValues? {
    when (point.source) {
        RichMapPoint.Source.GeoPointEmpty, RichMapPoint.Source.InputGeoPointEmpty -> return null
        else -> Unit
    }
    if (!point.latitude.isFinite() || !point.longitude.isFinite() ||
        point.latitude < -90.0 || point.latitude > 90.0 || point.longitude < -180.0 || point.longitude > 180.0
    ) return null
    return RichMapCoordinateValues(NumberToString(point.latitude), NumberToString(point.longitude))
}

private fun AppendPlainTarget(result: StringBuilder, value: String): Boolean {
    val size = utf8Size(result.toString())
    if (size > kRichTargetMaxBytes || utf8Size(value) > kRichTargetMaxBytes - size) return false
    result.append(value)
    return true
}

private fun AppendPlainTarget(result: StringBuilder, texts: List<RichText>): Boolean {
    for (text in texts) if (!AppendPlainTarget(result, text)) return false
    return true
}

private fun AppendPlainTarget(result: StringBuilder, text: RichText): Boolean = when (text.type) {
    RichText.Type.Empty -> true
    RichText.Type.Plain -> AppendPlainTarget(result, text.text)
    RichText.Type.Button -> false
    RichText.Type.CustomEmoji -> AppendPlainTarget(result, text.text)
    RichText.Type.Diff -> AppendPlainTarget(result, text.children)
    RichText.Type.Math, RichText.Type.InlineImage -> false
    else -> AppendPlainTarget(result, text.children)
}

private fun PlainTarget(text: RichText): String? {
    val result = StringBuilder()
    return if (AppendPlainTarget(result, text)) result.toString() else null
}

internal class RichHtmlRenderer(
    private val context: HtmlContext,
    private val message: RichMessage,
    private val messageId: Int,
    private val internalLinksDomain: String,
    private val relativeBase: String,
    private val media: RichMediaCallbacks,
) {
    private var linkActive = false
    private val firstAnchorIds = TreeMap<String, String>()
    private val anchorOccurrences = TreeMap<String, Int>()
    private val textAnchorIds = IdentityHashMap<RichText, String>()
    private val blockAnchorIds = IdentityHashMap<RichBlock, String>()

    private inline fun <T> withLink(block: () -> T): T {
        val previous = linkActive
        linkActive = true
        try {
            return block()
        } finally {
            linkActive = previous
        }
    }

    private fun findPhoto(id: Long): Photo? = if (id == 0L) null else message.photos[id.toULong()]
    private fun findDocument(id: Long): Document? = if (id == 0L) null else message.documents[id.toULong()]

    private fun mediaItemSize(block: RichBlock): QSize? {
        fun valid(size: QSize) = if (!size.isEmpty()) size else null
        if (block.kind == RichBlock.Kind.Photo) {
            val photo = findPhoto(block.photoId)
            return if (photo != null && photo.image.file.relativePath.isNotEmpty()) {
                valid(QSize(photo.image.width, photo.image.height))
            } else null
        } else if (block.kind == RichBlock.Kind.Video) {
            val document = findDocument(block.documentId)
            return if (document != null && document.file.relativePath.isNotEmpty() &&
                document.thumb.file.relativePath.isNotEmpty()
            ) valid(QSize(document.width, document.height)) else null
        }
        return null
    }

    private fun mediaGroupSizes(blocks: List<RichBlock>): List<QSize> {
        if (blocks.isEmpty()) return emptyList()
        val result = ArrayList<QSize>()
        for (block in blocks) {
            val size = mediaItemSize(block)
            if (size == null || RichCaptionHasOutput(block.caption) ||
                (block.optionalUrl != null && block.optionalUrl!!.isNotEmpty())
            ) return emptyList()
            result.add(size)
        }
        return result
    }

    private fun tailState(blocks: List<RichBlock>): RichTailState {
        for (i in blocks.indices.reversed()) {
            val state = tailState(blocks[i])
            if (state != RichTailState.Empty) return state
        }
        return RichTailState.Empty
    }

    private fun tailState(block: RichBlock): RichTailState {
        val K = RichBlock.Kind.Heading.javaClass
        @Suppress("UNUSED_VARIABLE") val unused = K
        return when (block.kind) {
            RichBlock.Kind.Unsupported, RichBlock.Kind.Heading, RichBlock.Kind.Paragraph, RichBlock.Kind.Footer,
            RichBlock.Kind.Thinking, RichBlock.Kind.AuthorDate, RichBlock.Kind.Code, RichBlock.Kind.Divider,
            RichBlock.Kind.Math, RichBlock.Kind.Table, RichBlock.Kind.ButtonRow, RichBlock.Kind.Unknown,
            -> RichTailState.Other
            RichBlock.Kind.Anchor -> RichTailState.Empty
            RichBlock.Kind.List -> {
                if (block.listItems.isEmpty()) return RichTailState.Empty
                val item = block.listItems.last()
                if (item.content == RichListItemContent.Blocks) {
                    val state = tailState(item.blocks)
                    if (state != RichTailState.Empty) return state
                }
                RichTailState.Other
            }
            RichBlock.Kind.Quote -> {
                if (RichTextHasOutput(block.quoteCaption)) return RichTailState.Other
                if (block.quoteContent == RichQuoteContent.Blocks) {
                    val state = tailState(block.blocks)
                    if (state != RichTailState.Empty) return state
                }
                RichTailState.Other
            }
            RichBlock.Kind.Photo ->
                if (RichCaptionHasOutput(block.caption) || (block.optionalUrl != null && block.optionalUrl!!.isNotEmpty())) {
                    RichTailState.Other
                } else RichTailState.Media
            RichBlock.Kind.Video, RichBlock.Kind.Audio, RichBlock.Kind.File, RichBlock.Kind.Map, RichBlock.Kind.InputMap ->
                if (RichCaptionHasOutput(block.caption)) RichTailState.Other else RichTailState.Media
            RichBlock.Kind.Cover -> tailState(block.blocks)
            RichBlock.Kind.Embed ->
                if (RichCaptionHasOutput(block.caption) ||
                    (block.posterPhotoId != null && block.optionalUrl != null && block.optionalUrl!!.isNotEmpty())
                ) RichTailState.Other else RichTailState.Media
            RichBlock.Kind.EmbedPost -> {
                if (RichCaptionHasOutput(block.caption)) return RichTailState.Other
                val state = tailState(block.blocks)
                if (state != RichTailState.Empty) return state
                if (block.url.isEmpty()) RichTailState.Media else RichTailState.Other
            }
            RichBlock.Kind.Collage, RichBlock.Kind.Slideshow ->
                if (RichCaptionHasOutput(block.caption)) RichTailState.Other else tailState(block.blocks)
            RichBlock.Kind.Channel -> RichTailState.Media
            RichBlock.Kind.Details -> {
                if (block.open) {
                    val state = tailState(block.blocks)
                    if (state != RichTailState.Empty) return state
                }
                RichTailState.Other
            }
            RichBlock.Kind.RelatedArticles -> {
                if (block.relatedArticles.isEmpty()) {
                    if (RichTextHasOutput(block.text)) RichTailState.Other else RichTailState.Empty
                } else {
                    val article = block.relatedArticles.last()
                    if (article.url.isNotEmpty()) return RichTailState.Other
                    val description = article.description ?: ""
                    val photoId = article.photoId
                    if (photoId != null && description.isNotEmpty()) {
                        val photo = findPhoto(photoId)
                        if (RichFileDescription(photo?.image?.file).isNotEmpty()) return RichTailState.Other
                    }
                    RichTailState.Media
                }
            }
        }
    }

    fun renderMessage(): String {
        collectAnchors()
        val result = StringBuilder(
            context.pushTag(
                "div",
                attrs(
                    "class" to "text rich_message",
                    "data-rich-message" to NumberToString(messageId),
                    "data-rich-part" to RichBoolAttribute(message.part),
                    "dir" to (if (message.rtl) "rtl" else "auto"),
                    "inline" to "",
                ),
            ),
        )
        result.append(renderBlocks(message.blocks))
        result.append(context.popTag())
        return result.toString()
    }

    private fun renderCaption(caption: RichCaption): String {
        val text = renderText(caption.text)
        val credit = renderText(caption.credit)
        if (text.isEmpty() && credit.isEmpty()) return ""
        val result = StringBuilder(context.pushTag("figcaption", attrs("class" to "rich_media_caption", "dir" to "auto")))
        if (text.isNotEmpty()) {
            result.append(context.pushTag("div", attrs("class" to "rich_caption_text")))
            result.append(text)
            result.append(context.popTag())
        }
        if (credit.isNotEmpty()) {
            result.append(context.pushTag("cite", attrs("class" to "rich_caption_credit")))
            result.append(credit)
            result.append(context.popTag())
        }
        result.append(context.popTag())
        return result.toString()
    }

    private fun renderSourceMeta(source: String, kind: RichSourceMetaKind): String {
        if (source.isEmpty()) return ""
        val containerClass = when (kind) {
            RichSourceMetaKind.Source -> "rich_source_meta"
            RichSourceMetaKind.EmbedPost -> "rich_embed_post_meta"
        }
        val result = StringBuilder(context.pushTag("div", attrs("class" to containerClass)))
        val href = SafeHttpHref(source)
        if (href != null && !linkActive) {
            result.append(context.pushTag("a", attrs("class" to "rich_source_link", "href" to href, "inline" to "")))
            withLink { result.append(SerializeHtml(source)) }
            result.append(context.popTag())
        } else {
            result.append(context.pushTag("span", attrs("class" to "rich_source_text", "inline" to "")))
            result.append(SerializeHtml(source))
            result.append(context.popTag())
        }
        result.append(context.popTag())
        return result.toString()
    }

    private fun renderPhoto(block: RichBlock): String {
        val attributes = RichBlockAttributes("rich_media_item", "photo")
        attributes.emplace("data-photo-id", java.lang.Long.toUnsignedString(block.photoId))
        attributes.emplace("data-spoiler", RichBoolAttribute(block.spoiler))
        block.optionalWebpageId?.let { attributes.emplace("data-webpage-id", java.lang.Long.toUnsignedString(it)) }
        val result = StringBuilder(context.pushTag("figure", attributes))
        result.append(media.photo(findPhoto(block.photoId)))
        block.optionalUrl?.let { result.append(renderSourceMeta(it, RichSourceMetaKind.Source)) }
        result.append(renderCaption(block.caption))
        result.append(context.popTag())
        return result.toString()
    }

    private fun renderVideo(block: RichBlock): String {
        val attributes = RichBlockAttributes("rich_media_item", "video")
        attributes.emplace("data-document-id", java.lang.Long.toUnsignedString(block.documentId))
        attributes.emplace("data-autoplay", RichBoolAttribute(block.autoplay))
        attributes.emplace("data-loop", RichBoolAttribute(block.loop))
        attributes.emplace("data-spoiler", RichBoolAttribute(block.spoiler))
        val result = StringBuilder(context.pushTag("figure", attributes))
        result.append(media.video(findDocument(block.documentId)))
        result.append(renderCaption(block.caption))
        result.append(context.popTag())
        return result.toString()
    }

    private fun renderAudio(block: RichBlock): String {
        val attributes = RichBlockAttributes("rich_media_item", "audio")
        attributes.emplace("data-document-id", java.lang.Long.toUnsignedString(block.documentId))
        val result = StringBuilder(context.pushTag("figure", attributes))
        result.append(media.audio(findDocument(block.documentId)))
        result.append(renderCaption(block.caption))
        result.append(context.popTag())
        return result.toString()
    }

    private fun renderFile(block: RichBlock): String {
        val attributes = RichBlockAttributes("rich_media_item", "file")
        attributes.emplace("data-document-id", java.lang.Long.toUnsignedString(block.documentId))
        val result = StringBuilder(context.pushTag("figure", attributes))
        result.append(media.file(findDocument(block.documentId)))
        result.append(renderCaption(block.caption))
        result.append(context.popTag())
        return result.toString()
    }

    private fun renderMediaGroup(block: RichBlock, kind: String, renderGroupCaption: Boolean): String {
        val attributes = RichBlockAttributes("rich_media_group", kind)
        attributes.emplace("data-rich-has-items", RichBoolAttribute(block.blocks.isNotEmpty()))
        attributes.emplace("data-rich-items-end-media", RichBoolAttribute(tailState(block.blocks) == RichTailState.Media))
        val result = StringBuilder(context.pushTag("section", attributes))
        result.append(context.pushTag("div", attrs("class" to "rich_media_group_items")))
        result.append(renderBlocks(block.blocks))
        result.append(context.popTag())
        if (renderGroupCaption) result.append(renderCaption(block.caption))
        result.append(context.popTag())
        return result.toString()
    }

    private fun renderCollage(block: RichBlock): String {
        val sizes = mediaGroupSizes(block.blocks)
        if (sizes.isEmpty()) return renderMediaGroup(block, "collage", true)
        val layout = LayoutMediaGroupGeometry(sizes, kGroupWidthMax, kGroupWidthMin, kGroupSkip)
        var full = QRect.NULL
        for (part in layout) full = full.united(part)
        if (layout.size != sizes.size || full.isEmpty()) return renderMediaGroup(block, "collage", true)
        val attributes = RichBlockAttributes("rich_media_group", "collage")
        attributes.emplace("data-rich-has-items", RichBoolAttribute(true))
        attributes.emplace("data-rich-items-end-media", RichBoolAttribute(true))
        val result = StringBuilder(context.pushTag("section", attributes))
        result.append(
            context.pushTag(
                "div",
                attrs("class" to "rich_collage_box", "style" to RichAspectRatioStyle(QSize(full.width, full.height))),
            ),
        )
        for (i in block.blocks.indices) {
            val geometry = layout[i]
            result.append(
                context.pushTag(
                    "div",
                    attrs(
                        "class" to "rich_collage_item",
                        "style" to "left: " + RichPercentValue(geometry.x - full.x, full.width) +
                            "; top: " + RichPercentValue(geometry.y - full.y, full.height) +
                            "; width: " + RichPercentValue(geometry.width, full.width) +
                            "; height: " + RichPercentValue(geometry.height, full.height),
                    ),
                ),
            )
            result.append(renderBlock(block.blocks[i]))
            result.append(context.popTag())
        }
        result.append(context.popTag())
        result.append(renderCaption(block.caption))
        result.append(context.popTag())
        return result.toString()
    }

    private fun renderSlideshow(block: RichBlock): String {
        val sizes = mediaGroupSizes(block.blocks)
        if (sizes.isEmpty()) return renderMediaGroup(block, "slideshow", true)
        var widest = sizes.first()
        for (size in sizes) {
            if (size.width.toLong() * widest.height > widest.width.toLong() * size.height) widest = size
        }
        val count = block.blocks.size
        val controls = count > 1
        val attributes = RichBlockAttributes("rich_media_group", "slideshow")
        attributes.emplace("data-rich-has-items", RichBoolAttribute(true))
        attributes.emplace("data-rich-items-end-media", RichBoolAttribute(true))
        val result = StringBuilder(context.pushTag("section", attributes))
        result.append(context.pushTag("div", attrs("class" to "rich_slideshow_box", "style" to RichAspectRatioStyle(widest))))
        result.append(context.pushTag("div", attrs("class" to "rich_slideshow_track")))
        for (item in block.blocks) {
            result.append(context.pushTag("div", attrs("class" to "rich_slide")))
            result.append(renderBlock(item))
            result.append(context.popTag())
        }
        result.append(context.popTag())
        if (controls) {
            result.append(context.pushTag("button", attrs("class" to "rich_slideshow_prev", "type" to "button", "inline" to "")))
            result.append("&#8592;")
            result.append(context.popTag())
            result.append(context.pushTag("button", attrs("class" to "rich_slideshow_next", "type" to "button", "inline" to "")))
            result.append("&#8594;")
            result.append(context.popTag())
            result.append(context.pushTag("div", attrs("class" to "rich_slideshow_dots")))
            repeat(count) {
                result.append(context.pushTag("button", attrs("class" to "rich_slideshow_dot", "type" to "button", "inline" to "")))
                result.append(context.popTag())
            }
            result.append(context.popTag())
        }
        result.append(context.popTag())
        result.append(renderCaption(block.caption))
        result.append(context.popTag())
        return result.toString()
    }

    private fun renderEmbed(block: RichBlock): String {
        val hasPoster = block.posterPhotoId != null
        val hasMeta = hasPoster && block.optionalUrl != null && block.optionalUrl!!.isNotEmpty()
        val attributes = RichBlockAttributes("rich_media_item rich_embed", "embed")
        attributes.emplace("data-full-width", RichBoolAttribute(block.fullWidth))
        attributes.emplace("data-allow-scrolling", RichBoolAttribute(block.allowScrolling))
        attributes.emplace("data-rich-has-meta", RichBoolAttribute(hasMeta))
        block.posterPhotoId?.let { attributes.emplace("data-poster-photo-id", java.lang.Long.toUnsignedString(it)) }
        block.width?.let { attributes.emplace("data-source-width", NumberToString(it)) }
        block.height?.let { attributes.emplace("data-source-height", NumberToString(it)) }
        val result = StringBuilder(context.pushTag("figure", attributes))
        if (hasPoster) {
            result.append(media.photo(findPhoto(block.posterPhotoId!!)))
            block.optionalUrl?.let { result.append(renderSourceMeta(it, RichSourceMetaKind.Source)) }
        } else {
            val m = MediaData()
            m.title = "Embed"
            m.classes = "media_file"
            block.optionalUrl?.let { url ->
                m.status = url
                SafeHttpHref(url)?.let { m.link = it }
            }
            result.append(media.generic(m))
        }
        result.append(renderCaption(block.caption))
        result.append(context.popTag())
        return result.toString()
    }

    private fun renderEmbedPost(block: RichBlock): String {
        val photo = findPhoto(block.authorPhotoId)
        val hasMeta = block.url.isNotEmpty()
        val attributes = RichBlockAttributes("rich_embed_post", "embed-post")
        attributes.emplace("data-webpage-id", java.lang.Long.toUnsignedString(block.webpageId))
        attributes.emplace("data-author-photo-id", java.lang.Long.toUnsignedString(block.authorPhotoId))
        attributes.emplace("data-date", NumberToString(block.date))
        attributes.emplace("data-rich-has-meta", RichBoolAttribute(hasMeta))
        attributes.emplace("data-rich-has-items", RichBoolAttribute(block.blocks.isNotEmpty()))
        attributes.emplace("data-rich-items-end-media", RichBoolAttribute(tailState(block.blocks) == RichTailState.Media))
        val result = StringBuilder(context.pushTag("section", attributes))
        result.append(context.pushTag("div", attrs("class" to "rich_embed_post_author")))
        val m = MediaData()
        m.title = if (block.author.isEmpty()) "Embed post" else block.author
        m.description = RichFileDescription(photo?.image?.file)
        m.status = if (block.date != 0) FormatDateTime(block.date) else ""
        m.classes = "media_contact"
        result.append(media.photoCard(m, photo))
        result.append(context.popTag())
        result.append(renderSourceMeta(block.url, RichSourceMetaKind.EmbedPost))
        result.append(context.pushTag("div", attrs("class" to "rich_embed_post_blocks")))
        result.append(renderBlocks(block.blocks))
        result.append(context.popTag())
        result.append(renderCaption(block.caption))
        result.append(context.popTag())
        return result.toString()
    }

    private fun renderChannel(block: RichBlock): String {
        val channel = block.channel
        val attributes = RichBlockAttributes("rich_reference_block", "channel")
        attributes.emplace("data-channel-source", RichChannelSourceName(channel.source))
        attributes.emplace("data-channel-id", java.lang.Long.toUnsignedString(channel.id))
        attributes.emplace("data-broadcast", RichBoolAttribute(channel.broadcast))
        attributes.emplace("data-megagroup", RichBoolAttribute(channel.megagroup))
        attributes.emplace("data-monoforum", RichBoolAttribute(channel.monoforum))
        channel.accessHash?.let { attributes.emplace("data-access-hash", NumberToString(it)) }
        val result = StringBuilder(context.pushTag("section", attributes))
        val m = MediaData()
        m.title = if (channel.title != null && channel.title!!.isNotEmpty()) channel.title!! else "Channel"
        m.status = RichChannelStatus(channel)
        m.classes = "media_contact"
        val username = channel.username
        if (username != null && IsAsciiWord(username, kRichUsernameMinLength, kRichUsernameMaxLength)) {
            SafeHttpHref(internalLinksDomain + username)?.let { m.link = it }
        }
        result.append(media.generic(m))
        result.append(context.popTag())
        return result.toString()
    }

    private fun renderMap(block: RichBlock, kind: String): String {
        val point = block.mapPoint
        val attributes = RichBlockAttributes("rich_reference_block", kind)
        attributes.emplace("data-point-source", RichMapPointSourceName(point.source))
        attributes.emplace("data-zoom", NumberToString(block.zoom))
        attributes.emplace("data-source-width", NumberToString(block.mapWidth))
        attributes.emplace("data-source-height", NumberToString(block.mapHeight))
        point.accessHash?.let { attributes.emplace("data-access-hash", NumberToString(it)) }
        point.accuracyRadius?.let { attributes.emplace("data-accuracy-radius", NumberToString(it)) }
        val result = StringBuilder(context.pushTag("figure", attributes))
        val m = MediaData()
        m.title = "Location"
        m.classes = "media_location"
        RichMapCoordinates(point)?.let { c ->
            val joined = c.latitude + ',' + c.longitude
            m.status = c.latitude + ", " + c.longitude
            val target = "https://maps.google.com/maps?q=$joined&ll=$joined&z=16"
            SafeHttpHref(target)?.let { m.link = it }
        }
        result.append(media.generic(m))
        result.append(renderCaption(block.caption))
        result.append(context.popTag())
        return result.toString()
    }

    private fun renderRelatedArticle(article: RichRelatedArticle, index: Int): String {
        val attributes = attrs(
            "class" to "rich_related_article",
            "data-rich-article-index" to NumberToString(index),
            "data-webpage-id" to java.lang.Long.toUnsignedString(article.webpageId),
        )
        article.photoId?.let { attributes.emplace("data-photo-id", java.lang.Long.toUnsignedString(it)) }
        article.publishedDate?.let { attributes.emplace("data-published-date", NumberToString(it)) }
        val result = StringBuilder(context.pushTag("article", attributes))
        val m = MediaData()
        m.title = if (article.title != null && article.title!!.isNotEmpty()) article.title!! else "Related article"
        if (article.author != null && article.author!!.isNotEmpty()) m.status = article.author!!
        val publishedDate = article.publishedDate?.let { FormatDateTime(it) } ?: ""
        if (publishedDate.isNotEmpty()) {
            if (m.status.isNotEmpty()) m.status += ", "
            m.status += publishedDate
        }
        val description = article.description ?: ""
        var displacedDescription = false
        val photoId = article.photoId
        if (photoId != null) {
            val photo = findPhoto(photoId)
            val fileDescription = RichFileDescription(photo?.image?.file)
            m.classes = "media_photo"
            m.description = if (fileDescription.isEmpty()) description else fileDescription
            displacedDescription = fileDescription.isNotEmpty() && description.isNotEmpty()
            result.append(media.photoCard(m, photo))
        } else {
            m.classes = "media_file"
            m.description = description
            result.append(media.generic(m))
        }
        if (displacedDescription) {
            result.append(context.pushTag("div", attrs("class" to "rich_article_description")))
            result.append(SerializeHtml(description))
            result.append(context.popTag())
        }
        result.append(renderSourceMeta(article.url, RichSourceMetaKind.Source))
        result.append(context.popTag())
        return result.toString()
    }

    private fun renderRelatedArticles(block: RichBlock): String {
        val title = renderText(block.text)
        val result = StringBuilder(context.pushTag("section", RichBlockAttributes("rich_related_articles", "related-articles")))
        if (title.isNotEmpty()) {
            result.append(context.pushTag("div", attrs("class" to "rich_related_title", "dir" to "auto")))
            result.append(title)
            result.append(context.popTag())
        }
        result.append(context.pushTag("div", attrs("class" to "rich_related_items")))
        var index = 0
        for (article in block.relatedArticles) result.append(renderRelatedArticle(article, index++))
        result.append(context.popTag())
        result.append(context.popTag())
        return result.toString()
    }

    private fun collectAnchors() {
        firstAnchorIds.clear()
        anchorOccurrences.clear()
        textAnchorIds.clear()
        blockAnchorIds.clear()
        collectBlockAnchors(message.blocks)
    }

    private fun registerAnchor(name: String): String {
        val base = "rich-message-" + NumberToString(messageId) + "-anchor-" + AnchorToken(name)
        val occurrence = (anchorOccurrences[base] ?: 0) + 1
        anchorOccurrences[base] = occurrence
        val result = base + if (occurrence > 1) "-" + NumberToString(occurrence) else ""
        firstAnchorIds.putIfMissing(name, result)
        return result
    }

    private fun collectTextAnchors(text: RichText) {
        when (text.type) {
            RichText.Type.Anchor -> {
                textAnchorIds.putIfMissing(text, registerAnchor(text.data))
                collectTextAnchors(text.children)
            }
            RichText.Type.Diff -> {
                collectTextAnchors(text.children)
                collectTextAnchors(text.oldChildren)
            }
            RichText.Type.Empty, RichText.Type.Plain, RichText.Type.Math, RichText.Type.CustomEmoji,
            RichText.Type.InlineImage,
            -> Unit
            else -> collectTextAnchors(text.children)
        }
    }

    private fun collectTextAnchors(texts: List<RichText>) {
        for (text in texts) collectTextAnchors(text)
    }

    private fun collectCaptionAnchors(caption: RichCaption) {
        collectTextAnchors(caption.text)
        collectTextAnchors(caption.credit)
    }

    private fun collectBlockAnchors(blocks: List<RichBlock>) {
        for (block in blocks) collectBlockAnchors(block)
    }

    private fun collectBlockAnchors(block: RichBlock) {
        when (block.kind) {
            RichBlock.Kind.Heading, RichBlock.Kind.Paragraph, RichBlock.Kind.Footer, RichBlock.Kind.Thinking,
            RichBlock.Kind.AuthorDate, RichBlock.Kind.Code,
            -> collectTextAnchors(block.text)
            RichBlock.Kind.Anchor -> blockAnchorIds.putIfMissing(block, registerAnchor(block.name))
            RichBlock.Kind.List -> for (item in block.listItems) {
                when (item.content) {
                    RichListItemContent.Text -> item.text?.let { collectTextAnchors(it) }
                    RichListItemContent.Blocks -> collectBlockAnchors(item.blocks)
                }
            }
            RichBlock.Kind.Quote -> {
                when (block.quoteContent) {
                    RichQuoteContent.Text -> collectTextAnchors(block.text)
                    RichQuoteContent.Blocks -> collectBlockAnchors(block.blocks)
                }
                collectTextAnchors(block.quoteCaption)
            }
            RichBlock.Kind.Photo, RichBlock.Kind.Video, RichBlock.Kind.Audio, RichBlock.Kind.File, RichBlock.Kind.Embed,
            RichBlock.Kind.Map, RichBlock.Kind.InputMap,
            -> collectCaptionAnchors(block.caption)
            RichBlock.Kind.EmbedPost -> {
                collectBlockAnchors(block.blocks)
                collectCaptionAnchors(block.caption)
            }
            RichBlock.Kind.Cover -> collectBlockAnchors(block.blocks)
            RichBlock.Kind.Collage, RichBlock.Kind.Slideshow -> {
                collectBlockAnchors(block.blocks)
                collectCaptionAnchors(block.caption)
            }
            RichBlock.Kind.Table -> {
                collectTextAnchors(block.text)
                for (row in block.tableRows) for (cell in row.cells) cell.text?.let { collectTextAnchors(it) }
            }
            RichBlock.Kind.Details -> {
                collectTextAnchors(block.text)
                collectBlockAnchors(block.blocks)
            }
            RichBlock.Kind.RelatedArticles -> collectTextAnchors(block.text)
            RichBlock.Kind.ButtonRow -> for (button in block.buttons) collectTextAnchors(button)
            RichBlock.Kind.Unsupported, RichBlock.Kind.Divider, RichBlock.Kind.Channel, RichBlock.Kind.Math,
            RichBlock.Kind.Unknown,
            -> Unit
        }
    }

    private fun fragmentHref(target: String): String? {
        if (!IsStrictTarget(target) || target[0] != '#') return null
        return firstAnchorIds[target.substring(1)]?.let { "#$it" }
    }

    private fun renderTexts(texts: List<RichText>): String {
        val result = StringBuilder()
        for (text in texts) result.append(renderText(text))
        return result.toString()
    }

    private fun renderTextChildren(text: RichText): String {
        if (text.children.isNotEmpty()) return renderTexts(text.children)
        val result = StringBuilder(
            context.pushTag(
                "span",
                attrs("class" to "rich_inline_fallback", "data-rich-kind" to "unsupported-text", "inline" to ""),
            ),
        )
        result.append("Unsupported rich text")
        result.append(context.popTag())
        return result.toString()
    }

    private fun renderTextLink(text: RichText, href: String?, attributes: Attrs): String {
        attributes.emplace("inline", "")
        if (href == null || linkActive) {
            attributes.remove("href")
            attributes.remove("onclick")
            attributes.emplace("class", "rich_inert_link")
            val result = StringBuilder(context.pushTag("span", attributes))
            result.append(renderTextChildren(text))
            result.append(context.popTag())
            return result.toString()
        }
        attributes.emplace("href", href)
        val result = StringBuilder(context.pushTag("a", attributes))
        withLink { result.append(renderTextChildren(text)) }
        result.append(context.popTag())
        return result.toString()
    }

    private fun renderButton(button: RichText, inlineButton: Boolean): String {
        check(button.type == RichText.Type.Button && button.children.size == 1 && button.button != null)
        val action = button.button!!.action
        val attributes = RichButtonAttributes(action, button.button!!.style, inlineButton)
        var tag = "span"
        val nested = linkActive
        val url = if (action.type == InlineButtonAction.Type.Url) SafeMessageHref(action.url) else null
        if (!nested && url != null) {
            tag = "a"
            attributes.emplace("href", url)
        } else if (!nested && action.type == InlineButtonAction.Type.CopyText) {
            tag = "button"
            attributes.emplace("type", "button")
            attributes.emplace("onclick", "return ShowTextCopied(decodeURIComponent(this.dataset.copyText))")
        }
        val result = StringBuilder(context.pushTag(tag, attributes))
        withLink { result.append(renderText(button.children.first())) }
        result.append(context.popTag())
        return result.toString()
    }

    private fun renderButtonRow(block: RichBlock): String {
        val alignment = RichButtonAlignmentToString(block.buttonAlignment)
        val attributes = RichBlockAttributes("rich_button_row rich_button_align_$alignment", "button-row")
        attributes.emplace("data-button-alignment", alignment)
        val result = StringBuilder(context.pushTag("div", attributes))
        result.append(context.pushTag("div", attrs("class" to "rich_button_group")))
        for (button in block.buttons) result.append(renderButton(button, false))
        result.append(context.popTag())
        result.append(context.popTag())
        return result.toString()
    }

    private fun renderCustomEmojiLink(alt: String, attributes: Attrs): String {
        attributes.emplace("inline", "")
        if (linkActive) {
            attributes.remove("href")
            attributes.remove("onclick")
            attributes.emplace("class", "rich_inert_link")
            val result = StringBuilder(context.pushTag("span", attributes))
            result.append(alt)
            result.append(context.popTag())
            return result.toString()
        }
        val result = StringBuilder(context.pushTag("a", attributes))
        withLink { result.append(alt) }
        result.append(context.popTag())
        return result.toString()
    }

    private fun renderText(text: RichText): String {
        fun wrapChildren(tag: String, attributes: Attrs): String {
            attributes.emplace("inline", "")
            val result = StringBuilder(context.pushTag(tag, attributes))
            result.append(renderTextChildren(text))
            result.append(context.popTag())
            return result.toString()
        }
        return when (text.type) {
            RichText.Type.Empty -> ""
            RichText.Type.Plain -> SerializeHtml(text.text)
            RichText.Type.Concat -> renderTexts(text.children)
            RichText.Type.Bold -> wrapChildren("strong", Attrs())
            RichText.Type.Italic -> wrapChildren("em", Attrs())
            RichText.Type.Underline -> wrapChildren("u", Attrs())
            RichText.Type.Strike -> wrapChildren("s", Attrs())
            RichText.Type.Fixed -> wrapChildren("code", attrs("class" to "rich_inline_code"))
            RichText.Type.Url -> {
                val href = if (text.data.startsWith('#')) fragmentHref(text.data) else SafeHttpHref(text.data)
                val attributes = Attrs()
                if (text.id != 0L) attributes.emplace("data-webpage-id", java.lang.Long.toUnsignedString(text.id))
                renderTextLink(text, href, attributes)
            }
            RichText.Type.Email -> renderTextLink(text, SafeEmailHref(text.data), Attrs())
            RichText.Type.Phone -> renderTextLink(text, SafePhoneHref(text.data), Attrs())
            RichText.Type.Subscript -> wrapChildren("sub", Attrs())
            RichText.Type.Superscript -> wrapChildren("sup", Attrs())
            RichText.Type.Marked -> wrapChildren("mark", Attrs())
            RichText.Type.Anchor -> {
                val attributes = attrs("class" to "rich_reference", "inline" to "")
                textAnchorIds[text]?.let { attributes.emplace("id", it) }
                val result = StringBuilder(context.pushTag("span", attributes))
                result.append(renderTexts(text.children))
                result.append(context.popTag())
                result.toString()
            }
            RichText.Type.Math -> {
                val result = StringBuilder(
                    context.pushTag(
                        "span",
                        attrs("class" to "rich_math_inline", "data-rich-kind" to "inline-math", "dir" to "ltr", "inline" to ""),
                    ),
                )
                result.append(SerializeHtml(text.data))
                result.append(context.popTag())
                result.toString()
            }
            RichText.Type.CustomEmoji -> {
                val result = StringBuilder(
                    context.pushTag(
                        "span",
                        attrs(
                            "class" to "rich_custom_emoji",
                            "data-document-id" to java.lang.Long.toUnsignedString(text.id),
                            "inline" to "",
                        ),
                    ),
                )
                val alt = SerializeHtml(text.text)
                val unavailable = text.customEmojiData == TextPart.UnavailableEmoji()
                val notLoaded = text.customEmojiData.isEmpty() || IsAsciiDecimal(text.customEmojiData)
                val href = if (unavailable || notLoaded) null else SafeRelativeEmojiHref(text.customEmojiData, relativeBase)
                if (href != null) {
                    result.append(renderCustomEmojiLink(alt, attrs("href" to href)))
                } else if (unavailable || notLoaded) {
                    result.append(
                        renderCustomEmojiLink(
                            alt,
                            attrs(
                                "href" to "",
                                "onclick" to (if (unavailable) "return ShowNotAvailableEmoji()" else "return ShowNotLoadedEmoji()"),
                            ),
                        ),
                    )
                } else {
                    result.append(alt)
                }
                result.append(context.popTag())
                result.toString()
            }
            RichText.Type.Spoiler -> {
                val result = StringBuilder(
                    context.pushTag("span", attrs("class" to "spoiler hidden", "inline" to "", "onclick" to "ShowSpoiler(this)")),
                )
                result.append(context.pushTag("span", attrs("aria-hidden" to "true", "inline" to "")))
                result.append(renderTextChildren(text))
                result.append(context.popTag())
                result.append(context.popTag())
                result.toString()
            }
            RichText.Type.Mention -> {
                val target = PlainTarget(text)
                val href = target?.let { SafeMentionHref(it, internalLinksDomain) }
                renderTextLink(text, href, Attrs())
            }
            RichText.Type.Hashtag, RichText.Type.BotCommand, RichText.Type.Cashtag -> {
                val target = PlainTarget(text)
                val prefix = when (text.type) {
                    RichText.Type.Hashtag -> '#'
                    RichText.Type.BotCommand -> '/'
                    else -> '$'
                }
                val action = target?.let { SafeActionData(it, prefix) }
                if (action == null) {
                    renderTextLink(text, null, Attrs())
                } else {
                    val attribute = if (text.type == RichText.Type.BotCommand) "data-command" else "data-tag"
                    val callback = when (text.type) {
                        RichText.Type.Hashtag -> "return ShowHashtag(this.dataset.tag)"
                        RichText.Type.BotCommand -> "return ShowBotCommand(this.dataset.command)"
                        else -> "return ShowCashtag(this.dataset.tag)"
                    }
                    renderTextLink(text, "", attrs(attribute to action, "onclick" to callback))
                }
            }
            RichText.Type.AutoUrl -> renderTextLink(text, PlainTarget(text)?.let { SafeHttpHref(it) }, Attrs())
            RichText.Type.AutoEmail -> renderTextLink(text, PlainTarget(text)?.let { SafeEmailHref(it) }, Attrs())
            RichText.Type.AutoPhone -> renderTextLink(text, PlainTarget(text)?.let { SafePhoneHref(it) }, Attrs())
            RichText.Type.BankCard -> wrapChildren("span", attrs("class" to "rich_bank_card"))
            RichText.Type.MentionName -> renderTextLink(
                text,
                "",
                attrs("data-user-id" to java.lang.Long.toUnsignedString(text.id), "onclick" to "return ShowMentionName()"),
            )
            RichText.Type.FormattedDate -> {
                val result = StringBuilder(
                    context.pushTag(
                        "time",
                        attrs(
                            "class" to "rich_formatted_date",
                            "data-date" to NumberToString(text.date),
                            "data-day-of-week" to RichBoolAttribute(text.dayOfWeek),
                            "data-long-date" to RichBoolAttribute(text.longDate),
                            "data-long-time" to RichBoolAttribute(text.longTime),
                            "data-relative" to RichBoolAttribute(text.relative),
                            "data-short-date" to RichBoolAttribute(text.shortDate),
                            "data-short-time" to RichBoolAttribute(text.shortTime),
                            "datetime" to IsoDate(text.date),
                            "dir" to "ltr",
                            "inline" to "",
                            "title" to FormatDateTime(text.date),
                        ),
                    ),
                )
                result.append(renderTextChildren(text))
                result.append(context.popTag())
                result.toString()
            }
            RichText.Type.InlineImage -> {
                val result = StringBuilder(
                    context.pushTag(
                        "span",
                        attrs(
                            "class" to "rich_inline_fallback",
                            "data-document-id" to java.lang.Long.toUnsignedString(text.id),
                            "data-rich-kind" to "inline-image",
                            "data-source-height" to NumberToString(text.height),
                            "data-source-width" to NumberToString(text.width),
                            "inline" to "",
                        ),
                    ),
                )
                result.append("Inline image")
                result.append(context.popTag())
                result.toString()
            }
            RichText.Type.Diff -> {
                val result = StringBuilder(
                    context.pushTag("span", attrs("class" to "rich_diff", "data-rich-kind" to "diff", "inline" to "")),
                )
                result.append(renderTextChildren(text))
                result.append(context.pushTag("del", attrs("class" to "rich_diff_previous", "inline" to "")))
                result.append("Previous: ")
                if (text.oldChildren.isEmpty()) {
                    result.append(
                        context.pushTag(
                            "span",
                            attrs("class" to "rich_inline_fallback", "data-rich-kind" to "unsupported-text", "inline" to ""),
                        ),
                    )
                    result.append("Unsupported rich text")
                    result.append(context.popTag())
                } else {
                    result.append(renderTexts(text.oldChildren))
                }
                result.append(context.popTag())
                result.append(context.popTag())
                result.toString()
            }
            RichText.Type.Button -> renderButton(text, true)
        }
    }

    private fun renderBlocks(blocks: List<RichBlock>): String {
        val result = StringBuilder()
        for (block in blocks) result.append(renderBlock(block))
        return result.toString()
    }

    private fun renderTextBlock(tag: String, attributes: Attrs, text: RichText): String {
        val result = StringBuilder(context.pushTag(tag, attributes))
        result.append(renderText(text))
        result.append(context.popTag())
        return result.toString()
    }

    private fun renderAuthorDate(block: RichBlock): String {
        val attributes = RichBlockAttributes("rich_author_date", "author-date")
        attributes.emplace("dir", "auto")
        val result = StringBuilder(context.pushTag("address", attributes))
        result.append(renderText(block.text))
        if (block.date != 0) {
            result.append(" • ")
            result.append(
                context.pushTag(
                    "time",
                    attrs(
                        "data-date" to NumberToString(block.date),
                        "datetime" to IsoDate(block.date),
                        "dir" to "ltr",
                        "inline" to "",
                    ),
                ),
            )
            result.append(SerializeHtml(FormatDateTime(block.date)))
            result.append(context.popTag())
        }
        result.append(context.popTag())
        return result.toString()
    }

    private fun renderCode(block: RichBlock): String {
        val attributes = RichBlockAttributes("rich_code", "code")
        attributes.emplace("dir", "auto")
        attributes.emplace("inline", "")
        if (block.language.isNotEmpty()) attributes.emplace("data-language", block.language)
        val result = StringBuilder(context.pushTag("pre", attributes))
        result.append(context.pushTag("code", attrs("class" to "rich_code_content", "inline" to "")))
        result.append(renderText(block.text))
        result.append(context.popTag())
        result.append(context.popTag())
        return result.toString()
    }

    private fun renderList(block: RichBlock): String {
        val ordered = block.listKind == RichListKind.Ordered
        val attributes = RichBlockAttributes("rich_list", if (ordered) "ordered-list" else "list")
        if (ordered) {
            if (block.orderedList.reversed) attributes.emplace("reversed", "")
            block.orderedList.start?.let { attributes.emplace("start", NumberToString(it)) }
            block.orderedList.type?.let { type ->
                val native = type.length == 1 && type[0] in "1aAiI"
                attributes.emplace(if (native) "type" else "data-list-type", type)
            }
        }
        val result = StringBuilder(context.pushTag(if (ordered) "ol" else "ul", attributes))
        for (item in block.listItems) result.append(renderListItem(item, ordered))
        result.append(context.popTag())
        return result.toString()
    }

    private fun renderListItem(item: RichListItem, ordered: Boolean): String {
        val task = item.taskState != RichTaskState.None
        val checked = item.taskState == RichTaskState.Checked
        val customMarker = ordered && !task && item.num != null && item.num!!.isNotEmpty()
        var classes = "rich_list_item"
        if (task) classes += " rich_task_item" else if (customMarker) classes += " rich_order_item"
        val attributes = attrs(
            "class" to classes,
            "data-rich-content" to (if (item.content == RichListItemContent.Blocks) "blocks" else "text"),
        )
        item.num?.let { attributes.emplace("data-num", it) }
        item.type?.let { attributes.emplace("data-item-type", it) }
        item.value?.let { attributes.emplace("value", NumberToString(it)) }
        val result = StringBuilder(context.pushTag("li", attributes))
        if (task) {
            result.append(
                context.pushTag(
                    "span",
                    attrs("aria-checked" to RichBoolAttribute(checked), "class" to "rich_task_marker", "inline" to "", "role" to "checkbox"),
                ),
            )
            result.append(if (checked) "☑" else "☐")
            result.append(context.popTag())
        } else if (customMarker) {
            result.append(context.pushTag("span", attrs("class" to "rich_order_marker", "inline" to "")))
            result.append(SerializeHtml(item.num!!))
            result.append(context.popTag())
        }
        result.append(
            context.pushTag(
                if (item.content == RichListItemContent.Blocks) "div" else "span",
                attrs("class" to "rich_list_content", "inline" to ""),
            ),
        )
        when (item.content) {
            RichListItemContent.Text -> item.text?.let { result.append(renderText(it)) }
            RichListItemContent.Blocks -> result.append(renderBlocks(item.blocks))
        }
        result.append(context.popTag())
        result.append(context.popTag())
        return result.toString()
    }

    private fun renderQuote(block: RichBlock): String {
        val kind = when {
            block.pullquote -> "pullquote"
            block.quoteContent == RichQuoteContent.Blocks -> "quote-blocks"
            else -> "quote"
        }
        val classes = if (block.pullquote) "rich_quote rich_pullquote" else "rich_quote"
        val result = StringBuilder(context.pushTag(if (block.pullquote) "aside" else "blockquote", RichBlockAttributes(classes, kind)))
        result.append(context.pushTag("div", attrs("class" to "rich_quote_body")))
        when (block.quoteContent) {
            RichQuoteContent.Text -> result.append(renderText(block.text))
            RichQuoteContent.Blocks -> result.append(renderBlocks(block.blocks))
        }
        result.append(context.popTag())
        val caption = renderText(block.quoteCaption)
        if (caption.isNotEmpty()) {
            result.append(context.pushTag("cite", attrs("class" to "rich_quote_caption")))
            result.append(caption)
            result.append(context.popTag())
        }
        result.append(context.popTag())
        return result.toString()
    }

    private fun renderDetails(block: RichBlock): String {
        val attributes = RichBlockAttributes("rich_details", "details")
        if (block.open) attributes.emplace("open", "")
        val result = StringBuilder(context.pushTag("details", attributes))
        result.append(context.pushTag("summary", Attrs()))
        result.append(renderText(block.text))
        result.append(context.popTag())
        result.append(context.pushTag("div", attrs("class" to "rich_details_body")))
        result.append(renderBlocks(block.blocks))
        result.append(context.popTag())
        result.append(context.popTag())
        return result.toString()
    }

    private fun renderTable(block: RichBlock): String {
        val wrapperAttributes = RichBlockAttributes("rich_table_wrap", "table")
        wrapperAttributes.emplace("tabindex", "0")
        val result = StringBuilder(context.pushTag("div", wrapperAttributes))
        var classes = "rich_table"
        if (block.bordered) classes += " bordered"
        if (block.striped) classes += " striped"
        if (block.compact) classes += " compact"
        result.append(context.pushTag("table", attrs("class" to classes)))
        val caption = renderText(block.text)
        if (caption.isNotEmpty()) {
            result.append(context.pushTag("caption", Attrs()))
            result.append(caption)
            result.append(context.popTag())
        }
        result.append(context.pushTag("tbody", Attrs()))
        for (row in block.tableRows) result.append(renderTableRow(row))
        result.append(context.popTag())
        result.append(context.popTag())
        result.append(context.popTag())
        return result.toString()
    }

    private fun renderTableRow(row: RichTableRow): String {
        val result = StringBuilder(context.pushTag("tr", Attrs()))
        for (cell in row.cells) result.append(renderTableCell(cell))
        result.append(context.popTag())
        return result.toString()
    }

    private fun renderTableCell(cell: RichTableCell): String {
        val horizontalClass = when (cell.alignment) {
            RichTableAlignment.Left -> "rich_align_left"
            RichTableAlignment.Center -> "rich_align_center"
            RichTableAlignment.Right -> "rich_align_right"
        }
        val verticalClass = when (cell.verticalAlignment) {
            RichTableVerticalAlignment.Top -> "rich_valign_top"
            RichTableVerticalAlignment.Middle -> "rich_valign_middle"
            RichTableVerticalAlignment.Bottom -> "rich_valign_bottom"
        }
        val attributes = attrs("class" to "$horizontalClass $verticalClass")
        cell.colspan?.let { source ->
            if (source > 0) {
                val span = if (source > kRichTableSpanMax) kRichTableSpanMax else source
                attributes.emplace("colspan", NumberToString(span))
                if (span != source) attributes.emplace("data-source-colspan", NumberToString(source))
            }
        }
        cell.rowspan?.let { source ->
            if (source > 0) {
                val span = if (source > kRichTableSpanMax) kRichTableSpanMax else source
                attributes.emplace("rowspan", NumberToString(span))
                if (span != source) attributes.emplace("data-source-rowspan", NumberToString(source))
            }
        }
        val result = StringBuilder(context.pushTag(if (cell.header) "th" else "td", attributes))
        cell.text?.let { result.append(renderText(it)) }
        result.append(context.popTag())
        return result.toString()
    }

    private fun renderFallback(kind: String, label: String): String {
        val result = StringBuilder(context.pushTag("div", RichBlockAttributes("rich_fallback", kind)))
        result.append(SerializeHtml(label))
        result.append(context.popTag())
        return result.toString()
    }

    private fun renderBlock(block: RichBlock): String = when (block.kind) {
        RichBlock.Kind.Unsupported -> renderFallback("unsupported", "Unsupported rich content")
        RichBlock.Kind.Heading -> {
            val attributes = RichBlockAttributes("rich_heading", "heading")
            attributes.emplace("data-level", NumberToString(block.headingLevel))
            renderTextBlock(RichHeadingTag(block.headingLevel), attributes, block.text)
        }
        RichBlock.Kind.Paragraph -> {
            val attributes = RichBlockAttributes("rich_paragraph", "paragraph")
            attributes.emplace("dir", "auto")
            renderTextBlock("p", attributes, block.text)
        }
        RichBlock.Kind.Footer -> {
            val attributes = RichBlockAttributes("rich_footer", "footer")
            attributes.emplace("dir", "auto")
            renderTextBlock("footer", attributes, block.text)
        }
        RichBlock.Kind.Thinking -> {
            val attributes = RichBlockAttributes("rich_thinking", "thinking")
            attributes.emplace("dir", "auto")
            renderTextBlock("p", attributes, block.text)
        }
        RichBlock.Kind.AuthorDate -> renderAuthorDate(block)
        RichBlock.Kind.Code -> renderCode(block)
        RichBlock.Kind.Divider -> {
            val attributes = RichBlockAttributes("rich_divider", "divider")
            attributes.emplace("empty", "")
            context.pushTag("hr", attributes)
        }
        RichBlock.Kind.Anchor -> {
            val id = blockAnchorIds[block]
            if (id == null) {
                renderFallback("malformed", "Malformed rich content")
            } else {
                val attributes = RichBlockAttributes("rich_anchor", "anchor")
                attributes.emplace("id", id)
                attributes.emplace("inline", "")
                context.pushTag("span", attributes) + context.popTag()
            }
        }
        RichBlock.Kind.List -> renderList(block)
        RichBlock.Kind.Quote -> renderQuote(block)
        RichBlock.Kind.Photo -> renderPhoto(block)
        RichBlock.Kind.Video -> renderVideo(block)
        RichBlock.Kind.Cover -> renderMediaGroup(block, "cover", false)
        RichBlock.Kind.Embed -> renderEmbed(block)
        RichBlock.Kind.EmbedPost -> renderEmbedPost(block)
        RichBlock.Kind.Collage -> renderCollage(block)
        RichBlock.Kind.Slideshow -> renderSlideshow(block)
        RichBlock.Kind.Channel -> renderChannel(block)
        RichBlock.Kind.Audio -> renderAudio(block)
        RichBlock.Kind.File -> renderFile(block)
        RichBlock.Kind.Math -> {
            val attributes = RichBlockAttributes("rich_math_display", "math")
            attributes.emplace("dir", "ltr")
            attributes.emplace("inline", "")
            context.pushTag("div", attributes) + SerializeHtml(block.formula) + context.popTag()
        }
        RichBlock.Kind.Table -> renderTable(block)
        RichBlock.Kind.Details -> renderDetails(block)
        RichBlock.Kind.RelatedArticles -> renderRelatedArticles(block)
        RichBlock.Kind.Map -> renderMap(block, "map")
        RichBlock.Kind.InputMap -> renderMap(block, "input-map")
        RichBlock.Kind.ButtonRow -> renderButtonRow(block)
        RichBlock.Kind.Unknown -> renderFallback("unknown", "Unknown rich content")
    }
}

internal fun RenderRichMessage(
    context: HtmlContext,
    message: RichMessage,
    messageId: Int,
    internalLinksDomain: String,
    relativeBase: String,
    media: RichMediaCallbacks,
): String = RichHtmlRenderer(context, message, messageId, internalLinksDomain, relativeBase, media).renderMessage()
