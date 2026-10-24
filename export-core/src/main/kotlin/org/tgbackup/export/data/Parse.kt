// Port of tdesktop Telegram/SourceFiles/export/data/export_data_types.cpp
@file:Suppress("FunctionName", "LocalVariableName")

package org.tgbackup.export.data

import org.tgbackup.export.Settings
import org.tgbackup.mtproto.tl.*

private const val kMigratedMessagesIdShift = -1_000_000_000

internal fun PrepareFileNameDatePart(date: TimeId): String =
    if (date != 0) "@" + FormatDateTime(date, false, '-', '-', '_') else ""

internal fun PreparePhotoFileName(index: Int, date: TimeId): String =
    "photo_" + index + PrepareFileNameDatePart(date) + ".jpg"

internal fun PrepareStoryFileName(index: Int, date: TimeId, extension: String): String =
    "story_" + index + PrepareFileNameDatePart(date) + extension

private fun ButtonRowsFromTL(data: MTPDreplyInlineMarkup): MutableList<MutableList<HistoryMessageMarkupButton>> {
    val list = data.vrows
    if (list.isEmpty()) return ArrayList()
    val rows = ArrayList<MutableList<HistoryMessageMarkupButton>>(list.size)
    for (tlRow in list) {
        val row = ArrayList<HistoryMessageMarkupButton>()
        for (button in (tlRow as MTPDkeyboardInlineButtonRow).vbuttons) {
            val fields = button as MTPDkeyboardInlineButton
            val text = fields.vtext
            when (val d = fields.vtype) {
                is MTPDinlineButtonTypeUrl -> row.add(HistoryMessageMarkupButton(HistoryMessageMarkupButton.Type.Url, text, d.vurl.toByteArray()))
                is MTPDinlineButtonTypeUrlAuth -> row.add(
                    HistoryMessageMarkupButton(HistoryMessageMarkupButton.Type.Auth, text, d.vurl.toByteArray(), d.vfwd_text ?: "", d.vbutton_id.toLong()),
                )
                is MTPDinputInlineButtonTypeUrlAuth -> Unit
                is MTPDinlineButtonTypeWebView -> row.add(HistoryMessageMarkupButton(HistoryMessageMarkupButton.Type.WebView, text, d.vurl.toByteArray()))
                is MTPDinlineButtonTypeCallback -> row.add(
                    HistoryMessageMarkupButton(if (d.is_requires_password) HistoryMessageMarkupButton.Type.CallbackWithPassword else HistoryMessageMarkupButton.Type.Callback, text, d.vdata),
                )
                MTPDinlineButtonTypeGame -> row.add(HistoryMessageMarkupButton(HistoryMessageMarkupButton.Type.Game, text))
                MTPDinlineButtonTypeBuy -> row.add(HistoryMessageMarkupButton(HistoryMessageMarkupButton.Type.Buy, text))
                is MTPDinlineButtonTypeSwitchInline -> row.add(
                    HistoryMessageMarkupButton(if (d.is_same_peer) HistoryMessageMarkupButton.Type.SwitchInlineSame else HistoryMessageMarkupButton.Type.SwitchInline, text, d.vquery.toByteArray()),
                )
                is MTPDinlineButtonTypeUserProfile -> row.add(
                    HistoryMessageMarkupButton(HistoryMessageMarkupButton.Type.UserProfile, text, d.vuser_id.toString().toByteArray()),
                )
                is MTPDinputInlineButtonTypeUserProfile -> Unit
                is MTPDinlineButtonTypeCopy -> row.add(HistoryMessageMarkupButton(HistoryMessageMarkupButton.Type.CopyText, text, d.vcopy_text.toByteArray()))
                MTPDinlineButtonTypeDisabled -> row.add(HistoryMessageMarkupButton(HistoryMessageMarkupButton.Type.Disabled, text))
            }
        }
        if (row.isNotEmpty()) rows.add(row)
    }
    return rows
}

fun InlineButtonActionTypeToString(action: InlineButtonAction): String = when (action.type) {
    InlineButtonAction.Type.Url -> "url"
    InlineButtonAction.Type.Auth -> "auth"
    InlineButtonAction.Type.WebView -> "web_view"
    InlineButtonAction.Type.Callback -> "callback"
    InlineButtonAction.Type.CallbackWithPassword -> "callback_with_password"
    InlineButtonAction.Type.Game -> "game"
    InlineButtonAction.Type.Buy -> "buy"
    InlineButtonAction.Type.SwitchInline -> "switch_inline"
    InlineButtonAction.Type.SwitchInlineSame -> "switch_inline_same"
    InlineButtonAction.Type.UserProfile -> "user_profile"
    InlineButtonAction.Type.CopyText -> "copy_text"
    InlineButtonAction.Type.Disabled -> "disabled"
}

fun InlineButtonPeerTypeToString(type: InlineButtonPeerType): String = when (type) {
    InlineButtonPeerType.SameBotPM -> "same_bot_pm"
    InlineButtonPeerType.PM -> "pm"
    InlineButtonPeerType.Chat -> "chat"
    InlineButtonPeerType.Megagroup -> "megagroup"
    InlineButtonPeerType.Broadcast -> "broadcast"
    InlineButtonPeerType.BotPM -> "bot_pm"
}

fun RichButtonStyleToString(style: RichButtonStyle): String = when (style) {
    RichButtonStyle.Default -> "default"
    RichButtonStyle.Primary -> "primary"
    RichButtonStyle.Success -> "success"
    RichButtonStyle.Danger -> "danger"
    RichButtonStyle.Link -> "link"
}

fun RichButtonAlignmentToString(alignment: RichButtonAlignment): String = when (alignment) {
    RichButtonAlignment.Stretch -> "stretch"
    RichButtonAlignment.Left -> "left"
    RichButtonAlignment.Center -> "center"
    RichButtonAlignment.Right -> "right"
}

fun PeerColorIndex(bareId: BareId): Int {
    val map = intArrayOf(0, 7, 4, 1, 6, 3, 5)
    return map[java.lang.Long.remainderUnsigned(bareId, map.size.toLong()).toInt()]
}

fun PeerToBareId(peerId: PeerId): BareId = peerId and Peer_.kChatTypeMask

fun PeerColorIndexOfPeer(peerId: PeerId): Int = PeerColorIndex(PeerToBareId(peerId))

fun StringBarePeerId(data: String): BareId {
    var result = 0xFFL
    for (ch in data.toByteArray(Charsets.UTF_8)) {
        result *= 239
        result += ch.toLong() // `char` is signed on the platforms Desktop ships for.
        result = result and 0xFF
    }
    return result
}

fun ApplicationColorIndex(applicationId: Int): Int {
    val official = mapOf(1 to 0, 7 to 0, 6 to 1, 21724 to 1, 2834 to 2, 2496 to 3, 2040 to 4, 1429 to 5)
    official[applicationId]?.let { return it }
    return PeerColorIndex(applicationId.toLong())
}

fun DomainApplicationId(data: String): Int = (0x1000 + StringBarePeerId(data)).toInt()

fun ParsePeerId(data: MTPPeer): PeerId = when (data) {
    is MTPDpeerUser -> Peer_.fromUser(data.vuser_id)
    is MTPDpeerChat -> Peer_.fromChat(data.vchat_id)
    is MTPDpeerChannel -> Peer_.fromChannel(data.vchannel_id)
}

/** QString::mid semantics (UTF-16 based, clamped). */
internal fun qMid(s: String, pos: Int, n: Int = -1): String {
    if (pos >= s.length || pos < 0 && n >= 0 && pos + n <= 0) return ""
    var p = pos
    var len = if (n < 0) s.length - p else n
    if (p < 0) {
        len += p
        p = 0
    }
    if (len < 0 || p + len > s.length) len = s.length - p
    return s.substring(p, p + len)
}

internal fun utf8Size(s: String): Int {
    var n = 0
    var i = 0
    while (i < s.length) {
        val c = s[i]
        n += when {
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                i++
                4
            }
            else -> 3
        }
        i++
    }
    return n
}

private fun entityOffset(e: MTPMessageEntity): Int = when (e) {
    is MTPDmessageEntityUnknown -> e.voffset
    is MTPDmessageEntityMention -> e.voffset
    is MTPDmessageEntityHashtag -> e.voffset
    is MTPDmessageEntityBotCommand -> e.voffset
    is MTPDmessageEntityUrl -> e.voffset
    is MTPDmessageEntityEmail -> e.voffset
    is MTPDmessageEntityBold -> e.voffset
    is MTPDmessageEntityItalic -> e.voffset
    is MTPDmessageEntityCode -> e.voffset
    is MTPDmessageEntityPre -> e.voffset
    is MTPDmessageEntityTextUrl -> e.voffset
    is MTPDmessageEntityMentionName -> e.voffset
    is MTPDinputMessageEntityMentionName -> e.voffset
    is MTPDmessageEntityPhone -> e.voffset
    is MTPDmessageEntityCashtag -> e.voffset
    is MTPDmessageEntityUnderline -> e.voffset
    is MTPDmessageEntityStrike -> e.voffset
    is MTPDmessageEntityBankCard -> e.voffset
    is MTPDmessageEntitySpoiler -> e.voffset
    is MTPDmessageEntityCustomEmoji -> e.voffset
    is MTPDmessageEntityBlockquote -> e.voffset
    is MTPDmessageEntityFormattedDate -> e.voffset
    is MTPDmessageEntityDiffInsert -> e.voffset
    is MTPDmessageEntityDiffReplace -> e.voffset
    is MTPDmessageEntityDiffDelete -> e.voffset
}

private fun entityLength(e: MTPMessageEntity): Int = when (e) {
    is MTPDmessageEntityUnknown -> e.vlength
    is MTPDmessageEntityMention -> e.vlength
    is MTPDmessageEntityHashtag -> e.vlength
    is MTPDmessageEntityBotCommand -> e.vlength
    is MTPDmessageEntityUrl -> e.vlength
    is MTPDmessageEntityEmail -> e.vlength
    is MTPDmessageEntityBold -> e.vlength
    is MTPDmessageEntityItalic -> e.vlength
    is MTPDmessageEntityCode -> e.vlength
    is MTPDmessageEntityPre -> e.vlength
    is MTPDmessageEntityTextUrl -> e.vlength
    is MTPDmessageEntityMentionName -> e.vlength
    is MTPDinputMessageEntityMentionName -> e.vlength
    is MTPDmessageEntityPhone -> e.vlength
    is MTPDmessageEntityCashtag -> e.vlength
    is MTPDmessageEntityUnderline -> e.vlength
    is MTPDmessageEntityStrike -> e.vlength
    is MTPDmessageEntityBankCard -> e.vlength
    is MTPDmessageEntitySpoiler -> e.vlength
    is MTPDmessageEntityCustomEmoji -> e.vlength
    is MTPDmessageEntityBlockquote -> e.vlength
    is MTPDmessageEntityFormattedDate -> e.vlength
    is MTPDmessageEntityDiffInsert -> e.vlength
    is MTPDmessageEntityDiffReplace -> e.vlength
    is MTPDmessageEntityDiffDelete -> e.vlength
}

fun ParseText(data: String, entities: List<MTPMessageEntity>): MutableList<TextPart> {
    val text = data
    // tdesktop compares entity bounds with the UTF-8 byte size of the text.
    val size = utf8Size(data)
    fun mid(offset: Int, length: Int) = qMid(text, offset, length)
    val result = ArrayList<TextPart>()
    var offset = 0
    fun addTextPart(till: Int) {
        if (till > offset) {
            result.add(TextPart(text = mid(offset, till - offset)))
            offset = till
        }
    }
    for (entity in entities) {
        val start = entityOffset(entity)
        val length = entityLength(entity)
        if (start < offset || length <= 0 || start + length > size) continue
        addTextPart(start)
        val part = TextPart()
        part.type = when (entity) {
            is MTPDmessageEntityUnknown -> TextPart.Type.Unknown
            is MTPDmessageEntityMention -> TextPart.Type.Mention
            is MTPDmessageEntityHashtag -> TextPart.Type.Hashtag
            is MTPDmessageEntityBotCommand -> TextPart.Type.BotCommand
            is MTPDmessageEntityUrl -> TextPart.Type.Url
            is MTPDmessageEntityEmail -> TextPart.Type.Email
            is MTPDmessageEntityBold -> TextPart.Type.Bold
            is MTPDmessageEntityItalic -> TextPart.Type.Italic
            is MTPDmessageEntityCode -> TextPart.Type.Code
            is MTPDmessageEntityPre -> TextPart.Type.Pre
            is MTPDmessageEntityTextUrl -> TextPart.Type.TextUrl
            is MTPDmessageEntityMentionName -> TextPart.Type.MentionName
            is MTPDinputMessageEntityMentionName -> TextPart.Type.MentionName
            is MTPDmessageEntityPhone -> TextPart.Type.Phone
            is MTPDmessageEntityCashtag -> TextPart.Type.Cashtag
            is MTPDmessageEntityUnderline -> TextPart.Type.Underline
            is MTPDmessageEntityStrike -> TextPart.Type.Strike
            is MTPDmessageEntityBlockquote -> TextPart.Type.Blockquote
            is MTPDmessageEntityBankCard -> TextPart.Type.BankCard
            is MTPDmessageEntitySpoiler -> TextPart.Type.Spoiler
            is MTPDmessageEntityCustomEmoji -> TextPart.Type.CustomEmoji
            is MTPDmessageEntityFormattedDate -> TextPart.Type.Unknown
            is MTPDmessageEntityDiffInsert -> TextPart.Type.Unknown
            is MTPDmessageEntityDiffReplace -> TextPart.Type.Unknown
            is MTPDmessageEntityDiffDelete -> TextPart.Type.Unknown
        }
        part.text = mid(start, length)
        part.additional = when (entity) {
            is MTPDmessageEntityPre -> entity.vlanguage
            is MTPDmessageEntityTextUrl -> entity.vurl
            is MTPDmessageEntityMentionName -> entity.vuser_id.toString()
            is MTPDmessageEntityCustomEmoji -> entity.vdocument_id.toString()
            is MTPDmessageEntityBlockquote -> if (entity.is_collapsed) "1" else ""
            else -> ""
        }
        result.add(part)
        offset = start + length
    }
    addTextPart(size)
    return result
}

fun ParseText(text: MTPTextWithEntities): MutableList<TextPart> {
    val d = text as MTPDtextWithEntities
    return ParseText(d.vtext, d.ventities)
}

private fun ParseInlineButtonPeerType(type: MTPInlineQueryPeerType): InlineButtonPeerType = when (type) {
    MTPDinlineQueryPeerTypeSameBotPM -> InlineButtonPeerType.SameBotPM
    MTPDinlineQueryPeerTypePM -> InlineButtonPeerType.PM
    MTPDinlineQueryPeerTypeChat -> InlineButtonPeerType.Chat
    MTPDinlineQueryPeerTypeMegagroup -> InlineButtonPeerType.Megagroup
    MTPDinlineQueryPeerTypeBroadcast -> InlineButtonPeerType.Broadcast
    MTPDinlineQueryPeerTypeBotPM -> InlineButtonPeerType.BotPM
}

private fun ParseInlineButtonAction(type: MTPInlineButtonType): InlineButtonAction? {
    return when (type) {
        is MTPDinlineButtonTypeUrl -> InlineButtonAction().apply {
            this.type = InlineButtonAction.Type.Url
            url = type.vurl
        }
        is MTPDinlineButtonTypeUrlAuth -> InlineButtonAction().apply {
            this.type = InlineButtonAction.Type.Auth
            url = type.vurl
            buttonId = type.vbutton_id
            type.vfwd_text?.let { forwardText = it }
        }
        is MTPDinputInlineButtonTypeUrlAuth -> null
        is MTPDinlineButtonTypeWebView -> InlineButtonAction().apply {
            this.type = InlineButtonAction.Type.WebView
            url = type.vurl
        }
        is MTPDinlineButtonTypeCallback -> InlineButtonAction().apply {
            requiresPassword = type.is_requires_password
            this.type = if (requiresPassword) InlineButtonAction.Type.CallbackWithPassword else InlineButtonAction.Type.Callback
            callbackData = type.vdata
        }
        MTPDinlineButtonTypeGame -> InlineButtonAction().apply { this.type = InlineButtonAction.Type.Game }
        MTPDinlineButtonTypeBuy -> InlineButtonAction().apply { this.type = InlineButtonAction.Type.Buy }
        is MTPDinlineButtonTypeSwitchInline -> InlineButtonAction().apply {
            samePeer = type.is_same_peer
            this.type = if (samePeer) InlineButtonAction.Type.SwitchInlineSame else InlineButtonAction.Type.SwitchInline
            query = type.vquery
            type.vpeer_types?.let { list -> peerTypes = list.map { ParseInlineButtonPeerType(it) }.toMutableList() }
        }
        is MTPDinlineButtonTypeUserProfile -> InlineButtonAction().apply {
            this.type = InlineButtonAction.Type.UserProfile
            userId = type.vuser_id
        }
        is MTPDinputInlineButtonTypeUserProfile -> null
        is MTPDinlineButtonTypeCopy -> InlineButtonAction().apply {
            this.type = InlineButtonAction.Type.CopyText
            copyText = type.vcopy_text
        }
        MTPDinlineButtonTypeDisabled -> InlineButtonAction().apply { this.type = InlineButtonAction.Type.Disabled }
    }
}

private fun RichButtonLinkStyleAllowed(type: InlineButtonAction.Type): Boolean =
    type == InlineButtonAction.Type.Callback ||
        type == InlineButtonAction.Type.CallbackWithPassword ||
        type == InlineButtonAction.Type.Disabled

private fun ParseRichButtonStyle(style: MTPRichButtonStyle?, actionType: InlineButtonAction.Type, inlineButton: Boolean): RichButtonStyle? {
    if (style == null) return null
    val data = style as MTPDrichButtonStyle
    if (inlineButton && data.is_link && RichButtonLinkStyleAllowed(actionType)) return RichButtonStyle.Link
    return when {
        data.is_bg_danger -> RichButtonStyle.Danger
        data.is_bg_primary -> RichButtonStyle.Primary
        data.is_bg_success -> RichButtonStyle.Success
        else -> RichButtonStyle.Default
    }
}

private fun ParseRichButton(text: MTPRichText, type: MTPInlineButtonType, style: MTPRichButtonStyle?, inlineButton: Boolean): RichText? {
    val action = ParseInlineButtonAction(type) ?: return null
    val result = RichText()
    result.type = RichText.Type.Button
    result.children.add(ParseRichText(text))
    val buttonStyle = ParseRichButtonStyle(style, action.type, inlineButton)
    result.button = RichButtonPayload(action, buttonStyle)
    return result
}

private fun ParseRichTextWrapper(type: RichText.Type, child: MTPRichText): RichText {
    val result = RichText()
    result.type = type
    result.children.add(ParseRichText(child))
    return result
}

fun ParseRichText(text: MTPRichText): RichText {
    return when (text) {
        MTPDtextEmpty -> RichText().apply { type = RichText.Type.Empty }
        is MTPDtextPlain -> RichText().apply {
            type = RichText.Type.Plain
            this.text = text.vtext
        }
        is MTPDtextBold -> ParseRichTextWrapper(RichText.Type.Bold, text.vtext)
        is MTPDtextItalic -> ParseRichTextWrapper(RichText.Type.Italic, text.vtext)
        is MTPDtextUnderline -> ParseRichTextWrapper(RichText.Type.Underline, text.vtext)
        is MTPDtextStrike -> ParseRichTextWrapper(RichText.Type.Strike, text.vtext)
        is MTPDtextFixed -> ParseRichTextWrapper(RichText.Type.Fixed, text.vtext)
        is MTPDtextUrl -> ParseRichTextWrapper(RichText.Type.Url, text.vtext).apply {
            data = text.vurl
            id = text.vwebpage_id
        }
        is MTPDtextEmail -> ParseRichTextWrapper(RichText.Type.Email, text.vtext).apply { data = text.vemail }
        is MTPDtextConcat -> RichText().apply {
            type = RichText.Type.Concat
            for (t in text.vtexts) children.add(ParseRichText(t))
        }
        is MTPDtextSubscript -> ParseRichTextWrapper(RichText.Type.Subscript, text.vtext)
        is MTPDtextSuperscript -> ParseRichTextWrapper(RichText.Type.Superscript, text.vtext)
        is MTPDtextMarked -> ParseRichTextWrapper(RichText.Type.Marked, text.vtext)
        is MTPDtextPhone -> ParseRichTextWrapper(RichText.Type.Phone, text.vtext).apply { data = text.vphone }
        is MTPDtextImage -> RichText().apply {
            type = RichText.Type.InlineImage
            id = text.vdocument_id
            width = text.vw
            height = text.vh
        }
        is MTPDtextAnchor -> ParseRichTextWrapper(RichText.Type.Anchor, text.vtext).apply { data = text.vname }
        is MTPDtextMath -> RichText().apply {
            type = RichText.Type.Math
            data = text.vsource
        }
        is MTPDtextCustomEmoji -> RichText().apply {
            type = RichText.Type.CustomEmoji
            this.text = text.valt
            id = text.vdocument_id
            customEmojiData = java.lang.Long.toUnsignedString(id)
        }
        is MTPDtextSpoiler -> ParseRichTextWrapper(RichText.Type.Spoiler, text.vtext)
        is MTPDtextMention -> ParseRichTextWrapper(RichText.Type.Mention, text.vtext)
        is MTPDtextHashtag -> ParseRichTextWrapper(RichText.Type.Hashtag, text.vtext)
        is MTPDtextBotCommand -> ParseRichTextWrapper(RichText.Type.BotCommand, text.vtext)
        is MTPDtextCashtag -> ParseRichTextWrapper(RichText.Type.Cashtag, text.vtext)
        is MTPDtextAutoUrl -> ParseRichTextWrapper(RichText.Type.AutoUrl, text.vtext)
        is MTPDtextAutoEmail -> ParseRichTextWrapper(RichText.Type.AutoEmail, text.vtext)
        is MTPDtextAutoPhone -> ParseRichTextWrapper(RichText.Type.AutoPhone, text.vtext)
        is MTPDtextBankCard -> ParseRichTextWrapper(RichText.Type.BankCard, text.vtext)
        is MTPDtextMentionName -> ParseRichTextWrapper(RichText.Type.MentionName, text.vtext).apply { id = text.vuser_id }
        is MTPDtextDate -> ParseRichTextWrapper(RichText.Type.FormattedDate, text.vtext).apply {
            date = text.vdate
            relative = text.is_relative
            shortTime = text.is_short_time
            longTime = text.is_long_time
            shortDate = text.is_short_date
            longDate = text.is_long_date
            dayOfWeek = text.is_day_of_week
        }
        is MTPDtextDiff -> RichText().apply {
            type = RichText.Type.Diff
            unsupported = true
            children.add(ParseRichText(text.vtext))
            oldChildren.add(ParseRichText(text.vold_text))
        }
        is MTPDtextButton -> ParseRichButton(text.vtext, text.vtype, text.vstyle, true) ?: ParseRichText(text.vtext)
    }
}

private fun ParseRichCaption(caption: MTPPageCaption): RichCaption {
    val data = caption as MTPDpageCaption
    return RichCaption(ParseRichText(data.vtext), ParseRichText(data.vcredit))
}

private fun ParseRichTaskState(checkbox: Boolean, checked: Boolean): RichTaskState =
    if (!checkbox) RichTaskState.None else if (checked) RichTaskState.Checked else RichTaskState.Unchecked

private fun ParseRichListItem(item: MTPPageListItem): RichListItem = when (item) {
    is MTPDpageListItemText -> RichListItem().apply {
        content = RichListItemContent.Text
        taskState = ParseRichTaskState(item.is_checkbox, item.is_checked)
        text = ParseRichText(item.vtext)
    }
    is MTPDpageListItemBlocks -> RichListItem().apply {
        content = RichListItemContent.Blocks
        taskState = ParseRichTaskState(item.is_checkbox, item.is_checked)
        blocks = ParseRichBlocks(item.vblocks)
    }
}

private fun ParseRichOrderedListItem(item: MTPPageListOrderedItem): RichListItem = when (item) {
    is MTPDpageListOrderedItemText -> RichListItem().apply {
        content = RichListItemContent.Text
        taskState = ParseRichTaskState(item.is_checkbox, item.is_checked)
        item.vnum?.let { num = it }
        item.vvalue?.let { value = it }
        item.vtype?.let { type = it }
        text = ParseRichText(item.vtext)
    }
    is MTPDpageListOrderedItemBlocks -> RichListItem().apply {
        content = RichListItemContent.Blocks
        taskState = ParseRichTaskState(item.is_checkbox, item.is_checked)
        item.vnum?.let { num = it }
        item.vvalue?.let { value = it }
        item.vtype?.let { type = it }
        blocks = ParseRichBlocks(item.vblocks)
    }
}

private fun ParseRichTableCell(cell: MTPPageTableCell): RichTableCell {
    val data = cell as MTPDpageTableCell
    return RichTableCell().apply {
        header = data.is_header
        alignment = when {
            data.is_align_right -> RichTableAlignment.Right
            data.is_align_center -> RichTableAlignment.Center
            else -> RichTableAlignment.Left
        }
        verticalAlignment = when {
            data.is_valign_bottom -> RichTableVerticalAlignment.Bottom
            data.is_valign_middle -> RichTableVerticalAlignment.Middle
            else -> RichTableVerticalAlignment.Top
        }
        data.vtext?.let { text = ParseRichText(it) }
        data.vcolspan?.let { colspan = it }
        data.vrowspan?.let { rowspan = it }
    }
}

private fun ParseRichTableRow(row: MTPPageTableRow): RichTableRow {
    val cells = (row as MTPDpageTableRow).vcells
    return RichTableRow().apply { cells.forEach { this.cells.add(ParseRichTableCell(it)) } }
}

private fun ParseRichRelatedArticle(article: MTPPageRelatedArticle): RichRelatedArticle {
    val data = article as MTPDpageRelatedArticle
    return RichRelatedArticle().apply {
        url = data.vurl
        webpageId = data.vwebpage_id
        data.vtitle?.let { title = it }
        data.vdescription?.let { description = it }
        data.vphoto_id?.let { photoId = it }
        data.vauthor?.let { author = it }
        data.vpublished_date?.let { publishedDate = it }
    }
}

private fun ParseRichChannel(chat: MTPChat): RichChannel {
    return when (chat) {
        is MTPDchatEmpty -> RichChannel().apply {
            source = RichChannel.Source.ChatEmpty
            id = chat.vid
        }
        is MTPDchat -> RichChannel().apply {
            source = RichChannel.Source.Chat
            id = chat.vid
            title = chat.vtitle
        }
        is MTPDchatForbidden -> RichChannel().apply {
            source = RichChannel.Source.ChatForbidden
            id = chat.vid
            title = chat.vtitle
        }
        is MTPDchannel -> RichChannel().apply {
            source = RichChannel.Source.Channel
            id = chat.vid
            title = chat.vtitle
            broadcast = chat.is_broadcast
            megagroup = chat.is_megagroup
            monoforum = chat.is_monoforum
            chat.vaccess_hash?.let { accessHash = it }
            chat.vusername?.let { username = it }
        }
        is MTPDchannelForbidden -> RichChannel().apply {
            source = RichChannel.Source.ChannelForbidden
            id = chat.vid
            title = chat.vtitle
            accessHash = chat.vaccess_hash
            broadcast = chat.is_broadcast
            megagroup = chat.is_megagroup
            monoforum = chat.is_monoforum
        }
        is MTPDcommunity -> RichChannel().apply {
            source = RichChannel.Source.Community
            id = chat.vid
            title = chat.vtitle
            chat.vaccess_hash?.let { accessHash = it }
        }
        is MTPDcommunityForbidden -> RichChannel().apply {
            source = RichChannel.Source.CommunityForbidden
            id = chat.vid
            title = chat.vtitle
            chat.vaccess_hash?.let { accessHash = it }
        }
    }
}

private fun ParseRichMapPoint(point: MTPGeoPoint): RichMapPoint = when (point) {
    MTPDgeoPointEmpty -> RichMapPoint().apply { source = RichMapPoint.Source.GeoPointEmpty }
    is MTPDgeoPoint -> RichMapPoint().apply {
        source = RichMapPoint.Source.GeoPoint
        latitude = point.vlat
        longitude = point.vlong
        accessHash = point.vaccess_hash
        point.vaccuracy_radius?.let { accuracyRadius = it }
    }
}

private fun ParseRichMapPoint(point: MTPInputGeoPoint): RichMapPoint = when (point) {
    MTPDinputGeoPointEmpty -> RichMapPoint().apply { source = RichMapPoint.Source.InputGeoPointEmpty }
    is MTPDinputGeoPoint -> RichMapPoint().apply {
        source = RichMapPoint.Source.InputGeoPoint
        latitude = point.vlat
        longitude = point.vlong
        point.vaccuracy_radius?.let { accuracyRadius = it }
    }
}

private fun ParseRichSupportedBlock(kind: RichBlock.Kind): RichBlock = RichBlock().apply { this.kind = kind }

private fun ParseRichTextBlock(kind: RichBlock.Kind, text: MTPRichText): RichBlock = RichBlock().apply {
    this.kind = kind
    this.text = ParseRichText(text)
}

private fun ParseRichHeadingBlock(level: Int, text: MTPRichText): RichBlock =
    ParseRichTextBlock(RichBlock.Kind.Heading, text).apply { headingLevel = level }

private fun ParseRichUnsupportedBlock(kind: RichBlock.Kind): RichBlock =
    ParseRichSupportedBlock(kind).apply { unsupported = true }

private fun ParseRichBlock(block: MTPPageBlock): RichBlock {
    return when (block) {
        MTPDpageBlockUnsupported -> ParseRichUnsupportedBlock(RichBlock.Kind.Unsupported)
        is MTPDpageBlockTitle -> ParseRichHeadingBlock(1, block.vtext)
        is MTPDpageBlockSubtitle -> ParseRichHeadingBlock(2, block.vtext)
        is MTPDpageBlockAuthorDate -> ParseRichTextBlock(RichBlock.Kind.AuthorDate, block.vauthor).apply { date = block.vpublished_date }
        is MTPDpageBlockHeader -> ParseRichHeadingBlock(3, block.vtext)
        is MTPDpageBlockSubheader -> ParseRichHeadingBlock(4, block.vtext)
        is MTPDpageBlockParagraph -> ParseRichTextBlock(RichBlock.Kind.Paragraph, block.vtext)
        is MTPDpageBlockPreformatted -> ParseRichTextBlock(RichBlock.Kind.Code, block.vtext).apply { language = block.vlanguage }
        is MTPDpageBlockFooter -> ParseRichTextBlock(RichBlock.Kind.Footer, block.vtext)
        MTPDpageBlockDivider -> RichBlock().apply { kind = RichBlock.Kind.Divider }
        is MTPDpageBlockAnchor -> RichBlock().apply {
            kind = RichBlock.Kind.Anchor
            name = block.vname
        }
        is MTPDpageBlockList -> RichBlock().apply {
            kind = RichBlock.Kind.List
            listKind = RichListKind.Bullet
            block.vitems.forEach { listItems.add(ParseRichListItem(it)) }
        }
        is MTPDpageBlockBlockquote -> ParseRichTextBlock(RichBlock.Kind.Quote, block.vtext).apply {
            quoteContent = RichQuoteContent.Text
            quoteCaption = ParseRichText(block.vcaption)
        }
        is MTPDpageBlockPullquote -> ParseRichTextBlock(RichBlock.Kind.Quote, block.vtext).apply {
            quoteContent = RichQuoteContent.Text
            quoteCaption = ParseRichText(block.vcaption)
            pullquote = true
        }
        is MTPDpageBlockPhoto -> ParseRichSupportedBlock(RichBlock.Kind.Photo).apply {
            photoId = block.vphoto_id
            caption = ParseRichCaption(block.vcaption)
            spoiler = block.is_spoiler
            block.vurl?.let { optionalUrl = it }
            block.vwebpage_id?.let { optionalWebpageId = it }
        }
        is MTPDpageBlockVideo -> ParseRichSupportedBlock(RichBlock.Kind.Video).apply {
            documentId = block.vvideo_id
            caption = ParseRichCaption(block.vcaption)
            autoplay = block.is_autoplay
            loop = block.is_loop
            spoiler = block.is_spoiler
        }
        is MTPDpageBlockCover -> ParseRichSupportedBlock(RichBlock.Kind.Cover).apply { blocks.add(ParseRichBlock(block.vcover)) }
        is MTPDpageBlockEmbed -> ParseRichSupportedBlock(RichBlock.Kind.Embed).apply {
            fullWidth = block.is_full_width
            allowScrolling = block.is_allow_scrolling
            caption = ParseRichCaption(block.vcaption)
            block.vurl?.let { optionalUrl = it }
            block.vhtml?.let { html = it }
            block.vposter_photo_id?.let { posterPhotoId = it }
            block.vw?.let { width = it }
            block.vh?.let { height = it }
        }
        is MTPDpageBlockEmbedPost -> ParseRichSupportedBlock(RichBlock.Kind.EmbedPost).apply {
            url = block.vurl
            webpageId = block.vwebpage_id
            authorPhotoId = block.vauthor_photo_id
            author = block.vauthor
            date = block.vdate
            blocks = ParseRichBlocks(block.vblocks)
            caption = ParseRichCaption(block.vcaption)
        }
        is MTPDpageBlockCollage -> ParseRichSupportedBlock(RichBlock.Kind.Collage).apply {
            blocks = ParseRichBlocks(block.vitems)
            caption = ParseRichCaption(block.vcaption)
        }
        is MTPDpageBlockSlideshow -> ParseRichSupportedBlock(RichBlock.Kind.Slideshow).apply {
            blocks = ParseRichBlocks(block.vitems)
            caption = ParseRichCaption(block.vcaption)
        }
        is MTPDpageBlockChannel -> ParseRichSupportedBlock(RichBlock.Kind.Channel).apply { channel = ParseRichChannel(block.vchannel) }
        is MTPDpageBlockAudio -> ParseRichSupportedBlock(RichBlock.Kind.Audio).apply {
            documentId = block.vaudio_id
            caption = ParseRichCaption(block.vcaption)
        }
        is MTPDpageBlockKicker -> ParseRichHeadingBlock(5, block.vtext)
        is MTPDpageBlockTable -> ParseRichTextBlock(RichBlock.Kind.Table, block.vtitle).apply {
            bordered = block.is_bordered
            striped = block.is_striped
            compact = block.is_compact
            block.vrows.forEach { tableRows.add(ParseRichTableRow(it)) }
        }
        is MTPDpageBlockOrderedList -> RichBlock().apply {
            kind = RichBlock.Kind.List
            listKind = RichListKind.Ordered
            orderedList.reversed = block.is_reversed
            block.vstart?.let { orderedList.start = it }
            block.vtype?.let { orderedList.type = it }
            block.vitems.forEach { listItems.add(ParseRichOrderedListItem(it)) }
        }
        is MTPDpageBlockDetails -> ParseRichTextBlock(RichBlock.Kind.Details, block.vtitle).apply {
            open = block.is_open
            blocks = ParseRichBlocks(block.vblocks)
        }
        is MTPDpageBlockRelatedArticles -> ParseRichSupportedBlock(RichBlock.Kind.RelatedArticles).apply {
            text = ParseRichText(block.vtitle)
            block.varticles.forEach { relatedArticles.add(ParseRichRelatedArticle(it)) }
        }
        is MTPDpageBlockMap -> ParseRichSupportedBlock(RichBlock.Kind.Map).apply {
            mapPoint = ParseRichMapPoint(block.vgeo)
            zoom = block.vzoom
            mapWidth = block.vw
            mapHeight = block.vh
            caption = ParseRichCaption(block.vcaption)
        }
        is MTPDpageBlockHeading1 -> ParseRichHeadingBlock(1, block.vtext)
        is MTPDpageBlockHeading2 -> ParseRichHeadingBlock(2, block.vtext)
        is MTPDpageBlockHeading3 -> ParseRichHeadingBlock(3, block.vtext)
        is MTPDpageBlockHeading4 -> ParseRichHeadingBlock(4, block.vtext)
        is MTPDpageBlockHeading5 -> ParseRichHeadingBlock(5, block.vtext)
        is MTPDpageBlockHeading6 -> ParseRichHeadingBlock(6, block.vtext)
        is MTPDpageBlockMath -> RichBlock().apply {
            kind = RichBlock.Kind.Math
            formula = block.vsource
        }
        is MTPDpageBlockThinking -> ParseRichTextBlock(RichBlock.Kind.Thinking, block.vtext)
        is MTPDinputPageBlockMap -> ParseRichSupportedBlock(RichBlock.Kind.InputMap).apply {
            mapPoint = ParseRichMapPoint(block.vgeo)
            zoom = block.vzoom
            mapWidth = block.vw
            mapHeight = block.vh
            caption = ParseRichCaption(block.vcaption)
        }
        is MTPDpageBlockBlockquoteBlocks -> RichBlock().apply {
            kind = RichBlock.Kind.Quote
            quoteContent = RichQuoteContent.Blocks
            blocks = ParseRichBlocks(block.vblocks)
            quoteCaption = ParseRichText(block.vcaption)
        }
        is MTPDpageBlockButtonRow -> ParseRichSupportedBlock(RichBlock.Kind.ButtonRow).apply {
            buttonAlignment = when {
                block.is_align_left -> RichButtonAlignment.Left
                block.is_align_center -> RichButtonAlignment.Center
                block.is_align_right -> RichButtonAlignment.Right
                else -> RichButtonAlignment.Stretch
            }
            for (button in block.vbuttons) {
                val fields = button as MTPDpageButton
                ParseRichButton(fields.vtext, fields.vtype, fields.vstyle, false)?.let { buttons.add(it) }
            }
        }
        is MTPDpageBlockDocument -> ParseRichSupportedBlock(RichBlock.Kind.File).apply {
            documentId = block.vdocument_id
            caption = ParseRichCaption(block.vcaption)
        }
    }
}

private fun ParseRichBlocks(blocks: List<MTPPageBlock>): MutableList<RichBlock> =
    blocks.mapTo(ArrayList(blocks.size)) { ParseRichBlock(it) }

private fun ParseRichMessageRoot(context: ParseMediaContext, message: MTPRichMessage, mediaFolder: String, messageDate: TimeId): RichMessage {
    val data = message as MTPDrichMessage
    val result = RichMessage()
    result.rtl = data.is_rtl
    result.part = data.is_part
    for (photo in data.vphotos) {
        ++context.photos
        val parsed = ParsePhoto(photo, mediaFolder + "photos/" + PreparePhotoFileName(context.photos, messageDate))
        result.photos[parsed.id.toULong()] = parsed
    }
    for (document in data.vdocuments) {
        val parsed = ParseDocument(context, document, mediaFolder, messageDate)
        result.documents[parsed.id.toULong()] = parsed
    }
    result.blocks = ParseRichBlocks(data.vblocks)
    return result
}

fun ParseRichMessage(context: ParseMediaContext, data: MTPRichMessage, folder: String, date: TimeId): RichMessage =
    ParseRichMessageRoot(context, data, folder, date)

private fun ParseReaction(reaction: MTPReaction): Reaction {
    val result = Reaction()
    when (reaction) {
        is MTPDreactionEmoji -> {
            result.type = Reaction.Type.Emoji
            result.emoji = reaction.vemoticon
        }
        is MTPDreactionCustomEmoji -> {
            result.type = Reaction.Type.CustomEmoji
            result.documentId = reaction.vdocument_id.toString()
        }
        MTPDreactionPaid -> result.type = Reaction.Type.Paid
        MTPDreactionEmpty -> result.type = Reaction.Type.Empty
    }
    return result
}

private fun ParseReactions(data: MTPMessageReactions): MutableList<Reaction> {
    val d = data as MTPDmessageReactions
    // std::map<QString, Reaction> keyed by id; order is kept separately.
    val reactionsMap = HashMap<String, Reaction>()
    val reactionsOrder = ArrayList<String>()
    for (single in d.vresults) {
        val s = single as MTPDreactionCount
        val reaction = ParseReaction(s.vreaction)
        reaction.count = s.vcount.toLong() and 0xffffffffL
        val id = Reaction.Id(reaction)
        if (!reactionsMap.containsKey(id)) {
            reactionsMap[id] = reaction
            reactionsOrder.add(id)
        }
    }
    d.vrecent_reactions?.let { list ->
        for (single in list) {
            val s = single as MTPDmessagePeerReaction
            val reaction = ParseReaction(s.vreaction)
            val id = Reaction.Id(reaction)
            val existing = reactionsMap[id] ?: reaction.also {
                reactionsMap[id] = it
                reactionsOrder.add(id)
            }
            existing.recent.add(Reaction.Recent(ParsePeerId(s.vpeer_id), s.vdate))
        }
    }
    return reactionsOrder.mapTo(ArrayList()) { reactionsMap[it]!! }
}

fun FillLeft(data: String, length: Int, filler: Char): String {
    if (length <= data.length) return data
    val sb = StringBuilder(length)
    repeat(length - data.length) { sb.append(filler) }
    sb.append(data)
    return sb.toString()
}

fun NumberToString(value: Long, length: Int = 0, filler: Char = '0'): String =
    FillLeft(value.toString(), length, filler).replace(',', '.')

fun NumberToString(value: Int, length: Int = 0, filler: Char = '0'): String = NumberToString(value.toLong(), length, filler)

/** std::to_string(double): "%f" formatting. */
fun NumberToString(value: Double): String = String.format(java.util.Locale.ROOT, "%f", value).replace(',', '.')

fun RefreshFileReference(to: FileLocation, from: FileLocation): Boolean {
    val t = to.data
    val f = from.data
    if (to.dcId != from.dcId || t == null || f == null || t.constructorId != f.constructorId) return false
    if (t is MTPDinputPhotoFileLocation && f is MTPDinputPhotoFileLocation) {
        if (t.vid != f.vid || t.vthumb_size != f.vthumb_size) return false
        to.dcId = from.dcId
        to.data = from.data
        return true
    } else if (t is MTPDinputDocumentFileLocation && f is MTPDinputDocumentFileLocation) {
        if (t.vid != f.vid || t.vthumb_size != f.vthumb_size) return false
        to.dcId = from.dcId
        to.data = from.data
        return true
    }
    return false
}

private fun ParseMaxImage(photo: MTPDphoto, suggestedPath: String): Image {
    val result = Image()
    result.file.suggestedPath = suggestedPath
    var maxArea = 0L
    for (size in photo.vsizes) {
        val (w, h, type) = when (size) {
            is MTPDphotoSizeEmpty, is MTPDphotoStrippedSize, is MTPDphotoPathSize -> continue
            is MTPDphotoSize -> Triple(size.vw, size.vh, size.vtype)
            is MTPDphotoCachedSize -> Triple(size.vw, size.vh, size.vtype)
            is MTPDphotoSizeProgressive -> Triple(size.vw, size.vh, size.vtype)
        }
        val area = w * h.toLong()
        if (area > maxArea) {
            result.width = w
            result.height = h
            result.file.location = FileLocation(
                photo.vdc_id,
                MTPDinputPhotoFileLocation(photo.vid, photo.vaccess_hash, photo.vfile_reference, type),
            )
            when (size) {
                is MTPDphotoCachedSize -> {
                    result.file.content = size.vbytes
                    result.file.size = result.file.content.size.toLong()
                }
                is MTPDphotoSizeProgressive -> {
                    if (size.vsizes.isEmpty()) continue
                    result.file.content = ByteArray(0)
                    result.file.size = size.vsizes.last().toLong()
                }
                is MTPDphotoSize -> {
                    result.file.content = ByteArray(0)
                    result.file.size = size.vsize.toLong()
                }
                else -> Unit
            }
            maxArea = area
        }
    }
    return result
}

fun ParsePhoto(data: MTPPhoto, suggestedPath: String): Photo {
    val result = Photo()
    when (data) {
        is MTPDphoto -> {
            result.id = data.vid
            result.date = data.vdate
            result.image = ParseMaxImage(data, suggestedPath)
        }
        is MTPDphotoEmpty -> result.id = data.vid
    }
    return result
}

private fun ParseAttributes(result: Document, attributes: List<MTPDocumentAttribute>) {
    for (value in attributes) {
        when (value) {
            is MTPDdocumentAttributeImageSize -> {
                result.width = value.vw
                result.height = value.vh
            }
            MTPDdocumentAttributeAnimated -> result.isAnimated = true
            is MTPDdocumentAttributeSticker -> {
                result.isSticker = true
                result.stickerEmoji = value.valt
            }
            is MTPDdocumentAttributeCustomEmoji -> {
                result.isSticker = true
                result.stickerEmoji = value.valt
            }
            is MTPDdocumentAttributeVideo -> {
                if (value.is_round_message) result.isVideoMessage = true else result.isVideoFile = true
                result.width = value.vw
                result.height = value.vh
                result.duration = value.vduration.toInt()
            }
            is MTPDdocumentAttributeAudio -> {
                if (value.is_voice) result.isVoiceMessage = true else result.isAudioFile = true
                value.vperformer?.let { result.songPerformer = it }
                value.vtitle?.let { result.songTitle = it }
                result.duration = value.vduration
            }
            is MTPDdocumentAttributeFilename -> result.name = value.vfile_name
            MTPDdocumentAttributeHasStickers -> Unit
        }
    }
}

private fun ComputeDocumentName(context: ParseMediaContext, data: Document, date: TimeId): String {
    if (data.name.isNotEmpty()) return data.name
    val mimeString = data.mime
    val pattern = MimeGlobPatterns(mimeString).firstOrNull() ?: ""
    return if (data.isVoiceMessage) {
        val isMP3 = mimeString.equals("audio/mp3", ignoreCase = true)
        "audio_" + (++context.audios) + PrepareFileNameDatePart(date) + (if (isMP3) ".mp3" else ".ogg")
    } else if (data.isVideoFile) {
        val extension = if (pattern.isEmpty()) ".mov" else pattern.replace("*", "")
        "video_" + (++context.videos) + PrepareFileNameDatePart(date) + extension
    } else {
        val extension = if (pattern.isEmpty()) ".unknown" else pattern.replace("*", "")
        "file_" + (++context.files) + PrepareFileNameDatePart(date) + extension
    }
}

private fun DocumentFolder(data: Document): String = when {
    data.isVideoFile -> "video_files"
    data.isAnimated -> "animations"
    data.isSticker -> "stickers"
    data.isVoiceMessage -> "voice_messages"
    data.isVideoMessage -> "round_video_messages"
    else -> "files"
}

private fun ParseDocumentThumb(document: MTPDdocument, documentPath: String): Image {
    val list = document.vthumbs ?: return Image()
    fun area(size: MTPPhotoSize): Int = when (size) {
        is MTPDphotoSizeEmpty, is MTPDphotoStrippedSize, is MTPDphotoPathSize -> 0
        is MTPDphotoSize -> size.vw * size.vh
        is MTPDphotoCachedSize -> size.vw * size.vh
        is MTPDphotoSizeProgressive -> size.vw * size.vh
    }
    if (list.isEmpty()) return Image()
    // ranges::max_element returns the first of equal maxima.
    var best = list[0]
    for (s in list) if (area(s) > area(best)) best = s
    fun located(w: Int, h: Int, type: String): Image {
        val result = Image()
        result.width = w
        result.height = h
        result.file.location = FileLocation(
            document.vdc_id,
            MTPDinputDocumentFileLocation(document.vid, document.vaccess_hash, document.vfile_reference, type),
        )
        return result
    }
    val result = when (best) {
        is MTPDphotoSizeEmpty, is MTPDphotoStrippedSize, is MTPDphotoPathSize -> return Image()
        is MTPDphotoCachedSize -> located(best.vw, best.vh, best.vtype).apply {
            file.content = best.vbytes
            file.size = file.content.size.toLong()
        }
        is MTPDphotoSizeProgressive -> {
            if (best.vsizes.isEmpty()) return Image()
            located(best.vw, best.vh, best.vtype).apply {
                file.content = ByteArray(0)
                file.size = best.vsizes.last().toLong()
            }
        }
        is MTPDphotoSize -> located(best.vw, best.vh, best.vtype).apply {
            file.content = ByteArray(0)
            file.size = best.vsize.toLong()
        }
    }
    result.file.suggestedPath = documentPath + "_thumb.jpg"
    return result
}

fun ParseDocument(context: ParseMediaContext, data: MTPDocument, suggestedFolder: String, date: TimeId): Document {
    val result = Document()
    when (data) {
        is MTPDdocument -> {
            result.id = data.vid
            result.date = data.vdate
            result.mime = data.vmime_type
            ParseAttributes(result, data.vattributes)
            result.file.size = data.vsize
            result.file.location.dcId = data.vdc_id
            result.file.location.data = MTPDinputDocumentFileLocation(data.vid, data.vaccess_hash, data.vfile_reference, "")
            result.file.suggestedPath = suggestedFolder + DocumentFolder(result) + '/' +
                FileNameFromUserString(ComputeDocumentName(context, result, date))
            result.thumb = ParseDocumentThumb(data, result.file.suggestedPath)
        }
        is MTPDdocumentEmpty -> result.id = data.vid
    }
    return result
}

private fun ParseSharedContact(context: ParseMediaContext, data: MTPDmessageMediaContact, suggestedFolder: String): SharedContact {
    val result = SharedContact()
    result.info.userId = data.vuser_id
    result.info.firstName = data.vfirst_name
    result.info.lastName = data.vlast_name
    result.info.phoneNumber = data.vphone_number
    if (data.vvcard.isNotEmpty()) {
        result.vcard.content = data.vvcard.toByteArray(Charsets.UTF_8)
        result.vcard.size = result.vcard.content.size.toLong()
        result.vcard.suggestedPath = suggestedFolder + "contacts/contact_" + (++context.contacts) + ".vcard"
    }
    return result
}

private fun ParseGeoPoint(data: MTPGeoPoint): GeoPoint {
    val result = GeoPoint()
    if (data is MTPDgeoPoint) {
        result.latitude = data.vlat
        result.longitude = data.vlong
        result.valid = true
    }
    return result
}

private fun ParseVenue(data: MTPDmessageMediaVenue): Venue = Venue().apply {
    point = ParseGeoPoint(data.vgeo)
    title = data.vtitle
    address = data.vaddress
}

private fun ParseGame(data: MTPGame, botId: Long): Game {
    val d = data as MTPDgame
    return Game().apply {
        id = d.vid
        title = d.vtitle
        description = d.vdescription
        shortName = d.vshort_name
        this.botId = botId
    }
}

private fun ParseInvoice(data: MTPDmessageMediaInvoice): Invoice = Invoice().apply {
    title = data.vtitle
    description = data.vdescription
    currency = data.vcurrency
    amount = data.vtotal_amount
    data.vreceipt_msg_id?.let { receiptMsgId = it }
}

private fun ParsePaidMedia(context: ParseMediaContext, data: MTPDmessageMediaPaidMedia, folder: String, date: TimeId): PaidMedia {
    val result = PaidMedia()
    result.stars = data.vstars_amount
    for (extended in data.vextended_media) {
        result.extended.add(
            when (extended) {
                is MTPDmessageExtendedMediaPreview -> null
                is MTPDmessageExtendedMedia -> ParseMedia(context, extended.vmedia, folder, date)
            },
        )
    }
    return result
}

private fun ParsePoll(data: MTPDmessageMediaPoll): Poll {
    val result = Poll()
    val poll = data.vpoll as MTPDpoll
    result.id = poll.vid
    result.question = ParseText(poll.vquestion)
    result.closed = poll.is_closed
    result.answers = poll.vanswers.mapTo(ArrayList()) { answer ->
        val a = Poll.Answer()
        if (answer is MTPDpollAnswer) {
            a.text = ParseText(answer.vtext)
            a.option = answer.voption
        }
        a
    }
    val results = data.vresults as MTPDpollResults
    results.vtotal_voters?.let { result.totalVotes = it }
    results.vresults?.let { list ->
        for (single in list) {
            val voters = single as MTPDpollAnswerVoters
            val i = result.answers.firstOrNull { it.option.contentEquals(voters.voption) } ?: continue
            voters.vvoters?.let { i.votes = it }
            if (voters.is_chosen) i.my = true
        }
    }
    return result
}

private fun ParseTodoListItem(item: MTPTodoItem): TodoListItem {
    val data = item as MTPDtodoItem
    return TodoListItem().apply {
        text = ParseText(data.vtitle)
        id = data.vid
    }
}

private fun ParseTodoList(data: MTPDmessageMediaToDo): TodoList {
    val result = TodoList()
    val d = data.vtodo as MTPDtodoList
    result.title = ParseText(d.vtitle)
    result.othersCanAppend = d.is_others_can_append
    result.othersCanComplete = d.is_others_can_complete
    result.items = d.vlist.mapTo(ArrayList()) { ParseTodoListItem(it) }
    return result
}

private fun ParseGiveaway(data: MTPDmessageMediaGiveaway): GiveawayStart {
    val result = GiveawayStart()
    result.untilDate = data.vuntil_date
    result.credits = data.vstars ?: 0
    result.quantity = data.vquantity
    result.months = data.vmonths ?: 0
    result.all = !data.is_only_new_subscribers
    for (id in data.vchannels) result.channels.add(id)
    data.vcountries_iso2?.let { result.countries.addAll(it) }
    data.vprize_description?.let { result.additionalPrize = it }
    return result
}

private fun ParseGiveaway(data: MTPDmessageMediaGiveawayResults): GiveawayResults {
    val result = GiveawayResults()
    result.channel = data.vchannel_id
    result.untilDate = data.vuntil_date
    result.launchId = data.vlaunch_msg_id
    result.additionalPeersCount = data.vadditional_peers_count ?: 0
    result.winnersCount = data.vwinners_count
    result.unclaimedCount = data.vunclaimed_count
    result.months = data.vmonths ?: 0
    result.credits = data.vstars ?: 0
    result.refunded = data.is_refunded
    result.all = !data.is_only_new_subscribers
    for (id in data.vwinners) result.winners.add(Peer_.fromUser(id))
    data.vprize_description?.let { result.additionalPrize = it }
    return result
}

fun ParseUserpicsSlice(data: List<MTPPhoto>, baseIndex: Int): UserpicsSlice {
    var index = baseIndex
    val result = UserpicsSlice()
    for (photo in data) {
        val suggestedPath = "profile_pictures/" + PreparePhotoFileName(++index, if (photo is MTPDphoto) photo.vdate else 0)
        result.list.add(ParsePhoto(photo, suggestedPath))
    }
    return result
}

private fun ParseStarGift(gift: MTPStarGift): ActionStarGift = when (gift) {
    is MTPDstarGift -> ActionStarGift().apply {
        giftId = gift.vid
        stars = gift.vstars
        limited = gift.is_limited
    }
    is MTPDstarGiftUnique -> ActionStarGift().apply { giftId = gift.vid }
}

fun ParseStoriesSlice(data: List<MTPStoryItem>, baseIndex: Int): StoriesSlice {
    var index = baseIndex
    val result = StoriesSlice()
    for (story in data) {
        result.lastId = when (story) {
            is MTPDstoryItem -> story.vid
            is MTPDstoryItemDeleted -> story.vid
            is MTPDstoryItemSkipped -> story.vid
        }
        ++result.skipped
        if (story !is MTPDstoryItem) continue
        val date = story.vdate
        val media = Media()
        when (val m = story.vmedia) {
            is MTPDmessageMediaPhoto -> {
                val suggestedPath = "stories/" + PrepareStoryFileName(++index, date, ".jpg")
                val content = m.vphoto?.let { ParsePhoto(it, suggestedPath) } ?: Photo()
                content.spoilered = m.is_spoiler
                media.content = content
            }
            is MTPDmessageMediaDocument -> {
                val fake = ParseMediaContext()
                val content = m.vdocument?.let { ParseDocument(fake, it, "stories", date) } ?: Document()
                val extension = when (content.mime) {
                    "image/jpeg" -> ".jpg"
                    "image/png" -> ".png"
                    else -> MimeGlobPatterns(content.mime).firstOrNull()?.replace("*", "") ?: ""
                }
                content.file.suggestedPath = "stories/" + PrepareStoryFileName(++index, date, extension)
                val path = content.file.suggestedPath
                content.thumb.file.suggestedPath = path + "_thumb.jpg"
                content.spoilered = m.is_spoiler
                media.content = content
            }
            else -> media.content = UnsupportedMedia
        }
        if (media.content !== UnsupportedMedia) {
            result.list.add(
                Story().apply {
                    id = story.vid
                    this.date = date
                    expires = story.vexpire_date
                    this.media = media
                    pinned = story.is_pinned
                    caption = story.vcaption?.let { ParseText(it, story.ventities ?: emptyList()) } ?: ArrayList()
                },
            )
            --result.skipped
        }
    }
    return result
}

fun ParseContactInfo(data: MTPUser): ContactInfo {
    val result = ContactInfo()
    when (data) {
        is MTPDuser -> {
            result.userId = data.vid
            result.colorIndex = PeerColorIndex(result.userId)
            (data.vcolor as? MTPDpeerColor)?.vcolor?.let { result.colorIndex = it }
            data.vfirst_name?.let { result.firstName = it }
            data.vlast_name?.let { result.lastName = it }
            data.vphone?.let { result.phoneNumber = it }
        }
        is MTPDuserEmpty -> {
            result.userId = data.vid
            result.colorIndex = PeerColorIndex(result.userId)
        }
    }
    return result
}

fun ContactColorIndex(data: ContactInfo): Int = data.colorIndex

fun ParseUser(data: MTPUser): User {
    val result = User()
    result.info = ParseContactInfo(data)
    when (data) {
        is MTPDuser -> {
            result.bareId = data.vid
            result.colorIndex = PeerColorIndex(result.bareId)
            (data.vcolor as? MTPDpeerColor)?.vcolor?.let { result.colorIndex = it }
            data.vusername?.let { result.username = it }
            if (data.vbot_info_version != null) result.isBot = true
            if (data.is_self) {
                result.isSelf = true
            } else if (data.vid == 1271266957L) {
                result.isReplies = true
            } else if (data.vid == 489000L) {
                result.isVerifyCodes = true
            }
            result.input = MTPDinputUser(data.vid, data.vaccess_hash ?: 0)
        }
        is MTPDuserEmpty -> result.input = MTPDinputUser(data.vid, 0)
    }
    return result
}

fun ParseUsersList(data: List<MTPUser>): java.util.TreeMap<Long, User> {
    val result = java.util.TreeMap<Long, User>()
    for (user in data) {
        val parsed = ParseUser(user)
        result.putIfAbsent(parsed.info.userId, parsed)
    }
    return result
}

fun ParseChat(data: MTPChat): Chat {
    val result = Chat()
    when (data) {
        is MTPDchat -> {
            result.bareId = data.vid
            result.title = data.vtitle
            result.input = MTPDinputPeerChat(result.bareId)
            data.vmigrated_to?.let { m -> result.migratedToChannelId = (m as? MTPDinputChannel)?.vchannel_id ?: 0 }
        }
        is MTPDchatEmpty -> {
            result.bareId = data.vid
            result.input = MTPDinputPeerChat(result.bareId)
        }
        is MTPDchatForbidden -> {
            result.bareId = data.vid
            result.title = data.vtitle
            result.input = MTPDinputPeerChat(result.bareId)
        }
        is MTPDchannel -> {
            result.bareId = data.vid
            result.colorIndex = PeerColorIndex(result.bareId)
            (data.vcolor as? MTPDpeerColor)?.vcolor?.let { result.colorIndex = it }
            result.isMonoforum = data.is_monoforum
            result.isBroadcast = data.is_broadcast
            result.isSupergroup = data.is_megagroup
            result.hasMonoforumAdminRights = data.is_broadcast &&
                (data.is_creator || ((data.vadmin_rights as? MTPDchatAdminRights)?.is_manage_direct_messages == true))
            result.monoforumLinkId = data.vlinked_monoforum_id ?: 0
            result.title = data.vtitle
            data.vusername?.let { result.username = it }
            result.input = MTPDinputPeerChannel(result.bareId, data.vaccess_hash ?: 0)
        }
        is MTPDchannelForbidden -> {
            result.bareId = data.vid
            result.isBroadcast = data.is_broadcast
            result.isSupergroup = data.is_megagroup
            result.isMonoforum = data.is_monoforum
            result.title = data.vtitle
            result.input = MTPDinputPeerChannel(result.bareId, data.vaccess_hash)
        }
        is MTPDcommunity -> {
            result.bareId = data.vid
            result.title = data.vtitle
            result.input = MTPDinputPeerChannel(result.bareId, data.vaccess_hash ?: 0)
        }
        is MTPDcommunityForbidden -> {
            result.bareId = data.vid
            result.title = data.vtitle
            result.input = MTPDinputPeerChannel(result.bareId, data.vaccess_hash ?: 0)
        }
    }
    return result
}

fun ParsePeersLists(users: List<MTPUser>, chats: List<MTPChat>): java.util.TreeMap<PeerId, Peer> {
    val result = java.util.TreeMap<PeerId, Peer>()
    for (user in users) {
        val parsed = ParseUser(user)
        result.putIfAbsent(Peer_.fromUser(parsed.info.userId), Peer(parsed))
    }
    for (chat in chats) {
        val parsed = ParseChat(chat)
        result.putIfAbsent(parsed.id(), Peer(parsed))
    }
    for ((_, parsed) in result) {
        val chat = parsed.chat() ?: continue
        if (chat.isMonoforum) {
            val i = result[Peer_.fromChannel(chat.monoforumLinkId)]
            if (i != null) {
                chat.isMonoforumAdmin = i.chat()!!.hasMonoforumAdminRights
                chat.isMonoforumOfPublicBroadcast = i.chat()!!.username.isNotEmpty()
            }
        }
    }
    return result
}

fun EmptyUser(userId: Long): User = ParseUser(MTPDuserEmpty(userId))
fun EmptyChat(chatId: Long): Chat = ParseChat(MTPDchatEmpty(chatId))

fun EmptyPeer(peerId: PeerId): Peer = when {
    Peer_.isUser(peerId) -> Peer(EmptyUser(Peer_.toUser(peerId)))
    Peer_.isChat(peerId) -> Peer(EmptyChat(Peer_.toChat(peerId)))
    // tdesktop calls peerToChat() here too, which yields 0 for channels.
    Peer_.isChannel(peerId) -> Peer(EmptyChat(Peer_.toChat(peerId)))
    else -> throw IllegalStateException("PeerId in EmptyPeer.")
}

fun ParseMedia(context: ParseMediaContext, data: MTPMessageMedia, folder: String, date: TimeId): Media {
    require(folder.isEmpty() || folder.endsWith('/'))
    val result = Media()
    when (data) {
        is MTPDmessageMediaPhoto -> {
            val content = data.vphoto?.let {
                ParsePhoto(it, folder + "photos/" + PreparePhotoFileName(++context.photos, date))
            } ?: Photo()
            content.spoilered = data.is_spoiler
            data.vttl_seconds?.let {
                result.ttl = it
                content.image.file = File()
            }
            result.content = content
        }
        is MTPDmessageMediaGeo -> result.content = ParseGeoPoint(data.vgeo)
        is MTPDmessageMediaContact -> result.content = ParseSharedContact(context, data, folder)
        MTPDmessageMediaUnsupported -> result.content = UnsupportedMedia
        is MTPDmessageMediaDocument -> {
            val content = data.vdocument?.let { ParseDocument(context, it, folder, date) } ?: Document()
            data.vttl_seconds?.let {
                result.ttl = it
                content.file = File()
            }
            content.spoilered = data.is_spoiler
            result.content = content
        }
        is MTPDmessageMediaWebPage -> Unit // Ignore web pages.
        is MTPDmessageMediaVenue -> result.content = ParseVenue(data)
        is MTPDmessageMediaGame -> result.content = ParseGame(data.vgame, context.botId)
        is MTPDmessageMediaInvoice -> result.content = ParseInvoice(data)
        is MTPDmessageMediaGeoLive -> {
            result.content = ParseGeoPoint(data.vgeo)
            result.ttl = data.vperiod
        }
        is MTPDmessageMediaPoll -> result.content = ParsePoll(data)
        is MTPDmessageMediaToDo -> result.content = ParseTodoList(data)
        is MTPDmessageMediaDice -> Unit // #TODO dice
        is MTPDmessageMediaStory -> Unit // #TODO export stories
        is MTPDmessageMediaGiveaway -> result.content = ParseGiveaway(data)
        is MTPDmessageMediaGiveawayResults -> result.content = ParseGiveaway(data)
        is MTPDmessageMediaPaidMedia -> result.content = ParsePaidMedia(context, data, folder, date)
        is MTPDmessageMediaVideoStream -> Unit // Live stories.
        MTPDmessageMediaEmpty -> Unit
    }
    return result
}

fun CreditsAmountFromTL(amount: MTPStarsAmount?): CreditsAmount = when (amount) {
    null -> CreditsAmount()
    is MTPDstarsAmount -> CreditsAmount(amount.vamount, amount.vnanos.toLong(), false)
    is MTPDstarsTonAmount -> {
        val isNegative = amount.vamount < 0
        val absValue = if (isNegative) (amount.vamount.inv() + 1).toULong() else amount.vamount.toULong()
        val result = CreditsAmount((absValue / 1_000_000_000uL).toLong(), (absValue % 1_000_000_000uL).toLong(), true)
        if (isNegative) CreditsAmount(0, 0, true) - result else result
    }
}

fun ParseServiceAction(context: ParseMediaContext, data: MTPMessageAction, mediaFolder: String, date: TimeId): ServiceAction {
    val result = ServiceAction()
    when (data) {
        is MTPDmessageActionChatCreate -> result.content = ActionChatCreate().apply {
            title = data.vtitle
            userIds.addAll(data.vusers)
        }
        is MTPDmessageActionChatEditTitle -> result.content = ActionChatEditTitle(data.vtitle)
        is MTPDmessageActionChatEditPhoto -> result.content = ActionChatEditPhoto(
            ParsePhoto(data.vphoto, mediaFolder + "photos/" + PreparePhotoFileName(++context.photos, date)),
        )
        MTPDmessageActionChatDeletePhoto -> result.content = ActionChatDeletePhoto
        is MTPDmessageActionChatAddUser -> result.content = ActionChatAddUser(data.vusers.toMutableList())
        is MTPDmessageActionChatDeleteUser -> result.content = ActionChatDeleteUser(data.vuser_id)
        is MTPDmessageActionChatJoinedByLink -> result.content = ActionChatJoinedByLink(data.vinviter_id)
        is MTPDmessageActionChannelCreate -> result.content = ActionChannelCreate(data.vtitle)
        is MTPDmessageActionChatMigrateTo -> result.content = ActionChatMigrateTo(data.vchannel_id)
        is MTPDmessageActionChannelMigrateFrom -> result.content = ActionChannelMigrateFrom(data.vtitle, data.vchat_id)
        MTPDmessageActionPinMessage -> result.content = ActionPinMessage
        MTPDmessageActionHistoryClear -> result.content = ActionHistoryClear
        is MTPDmessageActionGameScore -> result.content = ActionGameScore(data.vgame_id, data.vscore)
        is MTPDmessageActionPaymentSentMe -> Unit // Should not be in user inbox.
        is MTPDmessageActionPaymentSent -> result.content = ActionPaymentSent().apply {
            currency = data.vcurrency
            amount = data.vtotal_amount
            recurringInit = data.is_recurring_init
            recurringUsed = data.is_recurring_used
        }
        is MTPDmessageActionPhoneCall -> result.content = ActionPhoneCall().apply {
            data.vduration?.let { duration = it }
            data.vreason?.let { reason ->
                state = when (reason) {
                    MTPDphoneCallDiscardReasonMissed -> ActionPhoneCall.State.Missed
                    MTPDphoneCallDiscardReasonDisconnect -> ActionPhoneCall.State.Disconnect
                    MTPDphoneCallDiscardReasonHangup -> ActionPhoneCall.State.Hangup
                    MTPDphoneCallDiscardReasonBusy -> ActionPhoneCall.State.Busy
                    is MTPDphoneCallDiscardReasonMigrateConferenceCall -> ActionPhoneCall.State.MigrateConferenceCall
                }
            }
        }
        MTPDmessageActionScreenshotTaken -> result.content = ActionScreenshotTaken
        is MTPDmessageActionCustomAction -> result.content = ActionCustomAction(data.vmessage)
        is MTPDmessageActionBotAllowed -> result.content = ActionBotAllowed().apply {
            (data.vapp as? MTPDbotApp)?.let {
                appId = it.vid
                app = it.vtitle
            }
            data.vdomain?.let { domain = it }
            attachMenu = data.is_attach_menu
            fromRequest = data.is_from_request
        }
        is MTPDmessageActionSecureValuesSentMe -> Unit // Should not be in user inbox.
        is MTPDmessageActionSecureValuesSent -> result.content = ActionSecureValuesSent().apply {
            for (type in data.vtypes) {
                types.add(
                    when (type) {
                        MTPDsecureValueTypePersonalDetails -> ActionSecureValuesSent.Type.PersonalDetails
                        MTPDsecureValueTypePassport -> ActionSecureValuesSent.Type.Passport
                        MTPDsecureValueTypeDriverLicense -> ActionSecureValuesSent.Type.DriverLicense
                        MTPDsecureValueTypeIdentityCard -> ActionSecureValuesSent.Type.IdentityCard
                        MTPDsecureValueTypeInternalPassport -> ActionSecureValuesSent.Type.InternalPassport
                        MTPDsecureValueTypeAddress -> ActionSecureValuesSent.Type.Address
                        MTPDsecureValueTypeUtilityBill -> ActionSecureValuesSent.Type.UtilityBill
                        MTPDsecureValueTypeBankStatement -> ActionSecureValuesSent.Type.BankStatement
                        MTPDsecureValueTypeRentalAgreement -> ActionSecureValuesSent.Type.RentalAgreement
                        MTPDsecureValueTypePassportRegistration -> ActionSecureValuesSent.Type.PassportRegistration
                        MTPDsecureValueTypeTemporaryRegistration -> ActionSecureValuesSent.Type.TemporaryRegistration
                        MTPDsecureValueTypePhone -> ActionSecureValuesSent.Type.Phone
                        MTPDsecureValueTypeEmail -> ActionSecureValuesSent.Type.Email
                    },
                )
            }
        }
        MTPDmessageActionContactSignUp -> result.content = ActionContactSignUp
        is MTPDmessageActionGeoProximityReached -> result.content = ActionGeoProximityReached().apply {
            fromId = ParsePeerId(data.vfrom_id)
            if (fromId == context.selfPeerId) fromSelf = true
            toId = ParsePeerId(data.vto_id)
            if (toId == context.selfPeerId) toSelf = true
            distance = data.vdistance
        }
        is MTPDmessageActionGroupCall -> result.content = ActionGroupCall().apply { data.vduration?.let { duration = it } }
        is MTPDmessageActionInviteToGroupCall -> result.content = ActionInviteToGroupCall(data.vusers.toMutableList())
        is MTPDmessageActionSetMessagesTTL -> result.content = ActionSetMessagesTTL(data.vperiod)
        is MTPDmessageActionGroupCallScheduled -> result.content = ActionGroupCallScheduled(data.vschedule_date)
        is MTPDmessageActionSetChatTheme -> result.content = when (val theme = data.vtheme) {
            is MTPDchatTheme -> ActionSetChatTheme(theme.vemoticon)
            is MTPDchatThemeUniqueGift -> ActionSetChatTheme()
        }
        MTPDmessageActionChatJoinedByRequest -> result.content = ActionChatJoinedByRequest
        is MTPDmessageActionWebViewDataSentMe -> Unit // Should not be in user inbox.
        is MTPDmessageActionWebViewDataSent -> result.content = ActionWebViewDataSent(data.vtext)
        is MTPDmessageActionGiftPremium -> result.content = ActionGiftPremium(
            FillAmountAndCurrency(data.vamount, data.vcurrency),
            data.vdays,
        )
        is MTPDmessageActionTopicCreate -> result.content = ActionTopicCreate(data.vtitle)
        is MTPDmessageActionTopicEdit -> result.content = ActionTopicEdit().apply {
            data.vtitle?.let { title = it }
            data.vicon_emoji_id?.let { iconEmojiId = it }
        }
        is MTPDmessageActionSuggestProfilePhoto -> result.content = ActionSuggestProfilePhoto(
            ParsePhoto(data.vphoto, mediaFolder + "photos/" + PreparePhotoFileName(++context.photos, date)),
        )
        is MTPDmessageActionSetChatWallPaper -> result.content = ActionSetChatWallPaper(data.is_same, data.is_for_both)
        is MTPDmessageActionRequestedPeer -> result.content = ActionRequestedPeer().apply {
            for (peer in data.vpeers) peers.add(ParsePeerId(peer))
            buttonId = data.vbutton_id
        }
        is MTPDmessageActionGiftCode -> result.content = ActionGiftCode().apply {
            boostPeerId = data.vboost_peer?.let { ParsePeerId(it) } ?: 0
            viaGiveaway = data.is_via_giveaway
            unclaimed = data.is_unclaimed
            days = data.vdays
            code = data.vslug
        }
        MTPDmessageActionGiveawayLaunch -> result.content = ActionGiveawayLaunch
        is MTPDmessageActionGiveawayLaunch -> result.content = ActionGiveawayLaunch
        is MTPDmessageActionGiveawayResults -> result.content = ActionGiveawayResults().apply {
            winners = data.vwinners_count
            unclaimed = data.vunclaimed_count
            credits = data.is_stars
        }
        is MTPDmessageActionBoostApply -> result.content = ActionBoostApply(data.vboosts)
        is MTPDmessageActionRequestedPeerSentMe -> Unit // Should not be in user inbox.
        is MTPDmessageActionPaymentRefunded -> result.content = ActionPaymentRefunded().apply {
            currency = data.vcurrency
            amount = data.vtotal_amount
            peerId = ParsePeerId(data.vpeer)
            transactionId = (data.vcharge as MTPDpaymentCharge).vid
        }
        is MTPDmessageActionGiftStars -> result.content = ActionGiftCredits().apply {
            cost = FillAmountAndCurrency(data.vamount, data.vcurrency)
            amount = CreditsAmount(data.vstars, 0, false)
        }
        is MTPDmessageActionGiftTon -> result.content = ActionGiftCredits().apply {
            cost = FillAmountAndCurrency(data.vamount, data.vcurrency)
            val a = data.vamount.toULong()
            amount = CreditsAmount((a / 1_000_000_000uL).toLong(), (a % 1_000_000_000uL).toLong(), true)
        }
        is MTPDmessageActionPrizeStars -> result.content = ActionPrizeStars().apply {
            peerId = ParsePeerId(data.vboost_peer)
            amount = data.vstars
            transactionId = data.vtransaction_id
            giveawayMsgId = data.vgiveaway_msg_id
            isUnclaimed = data.is_unclaimed
        }
        is MTPDmessageActionStarGift -> result.content = ParseStarGift(data.vgift).apply {
            text = data.vmessage?.let { m ->
                val d = m as MTPDtextWithEntities
                ParseText(d.vtext, d.ventities)
            } ?: ArrayList()
            anonymous = data.is_name_hidden
        }
        is MTPDmessageActionStarGiftUnique -> result.content = ParseStarGift(data.vgift)
        is MTPDmessageActionPaidMessagesRefunded -> result.content = ActionPaidMessagesRefunded(data.vcount, data.vstars)
        is MTPDmessageActionPaidMessagesPrice -> result.content = ActionPaidMessagesPrice(
            data.vstars.toInt(),
            data.is_broadcast_messages_allowed,
        )
        is MTPDmessageActionTodoCompletions -> result.content = ActionTodoCompletions().apply {
            completed = data.vcompleted.toMutableList()
            incompleted = data.vincompleted.toMutableList()
        }
        is MTPDmessageActionTodoAppendTasks -> result.content = ActionTodoAppendTasks(
            data.vlist.mapTo(ArrayList()) { ParseTodoListItem(it) },
        )
        // tdesktop matches messageActionPollAppendAnswer twice; the first
        // (empty) handler is the one that runs.
        is MTPDmessageActionPollAppendAnswer -> result.content = ActionPollAppendAnswer()
        is MTPDmessageActionSuggestedPostApproval -> result.content = ActionSuggestedPostApproval().apply {
            rejectComment = data.vreject_comment ?: ""
            scheduleDate = data.vschedule_date ?: 0
            price = CreditsAmountFromTL(data.vprice)
            rejected = data.is_rejected
            balanceTooLow = data.is_balance_too_low
        }
        is MTPDmessageActionSuggestedPostSuccess -> result.content = ActionSuggestedPostSuccess(CreditsAmountFromTL(data.vprice))
        is MTPDmessageActionSuggestedPostRefund -> result.content = ActionSuggestedPostRefund(data.is_payer_initiated)
        is MTPDmessageActionConferenceCall -> result.content = ActionPhoneCall().apply {
            conferenceId = data.vcall_id
            data.vduration?.let { duration = it }
            state = when {
                data.is_missed -> ActionPhoneCall.State.Missed
                data.is_active -> ActionPhoneCall.State.Active
                (data.vduration ?: 0) != 0 -> ActionPhoneCall.State.Hangup
                else -> ActionPhoneCall.State.Invitation
            }
        }
        is MTPDmessageActionSuggestBirthday -> result.content = ActionSuggestBirthday().apply {
            val fields = data.vbirthday as MTPDbirthday
            birthday = Birthday(fields.vday, fields.vmonth, fields.vyear ?: 0)
        }
        is MTPDmessageActionStarGiftPurchaseOffer -> result.content = ParseStarGift(data.vgift).apply {
            offer = true
            offerPrice = CreditsAmountFromTL(data.vprice)
            offerExpireAt = data.vexpires_at
            offerAccepted = data.is_accepted
            offerDeclined = data.is_declined
        }
        is MTPDmessageActionStarGiftPurchaseOfferDeclined -> result.content = ParseStarGift(data.vgift).apply {
            offer = true
            offerDeclined = true
            offerExpired = data.is_expired
            offerPrice = CreditsAmountFromTL(data.vprice)
        }
        is MTPDmessageActionNewCreatorPending -> result.content = ActionNewCreatorPending(data.vnew_creator_id)
        is MTPDmessageActionChangeCreator -> result.content = ActionChangeCreator(data.vnew_creator_id)
        is MTPDmessageActionNoForwardsToggle -> result.content = ActionNoForwardsToggle(data.vnew_value)
        is MTPDmessageActionNoForwardsRequest -> result.content = ActionNoForwardsRequest(data.is_expired, data.vnew_value)
        is MTPDmessageActionManagedBotCreated -> result.content = ActionManagedBotCreated(data.vbot_id)
        is MTPDmessageActionPollDeleteAnswer -> result.content = ActionPollDeleteAnswer().apply {
            (data.vanswer as? MTPDpollAnswer)?.let { option = (it.vtext as MTPDtextWithEntities).vtext }
        }
        is MTPDmessageActionChangeCommunity -> Unit
        is MTPDmessageActionChatJoinedViaCommunity -> result.content = ActionChatJoinedViaCommunity(data.vcommunity_id)
        MTPDmessageActionEmpty -> Unit
        else -> Unit
    }
    return result
}

fun ParseMessage(context: ParseMediaContext, data: MTPMessage, mediaFolder: String): Message {
    val result = Message()
    fun commonHeader(date: Int, out: Boolean, peer: MTPPeer, from: MTPPeer?, replyTo: MTPMessageReplyHeader?) {
        result.date = date
        result.out = out
        result.selfId = context.selfPeerId
        result.peerId = ParsePeerId(peer)
        result.fromId = if (from != null) ParsePeerId(from) else result.peerId
        if (replyTo is MTPDmessageReplyHeader) {
            replyTo.vreply_to_msg_id?.let { replyToMsg ->
                result.replyToMsgId = replyToMsg
                result.replyToPeerId = replyTo.vreply_to_peer_id?.let { ParsePeerId(it) } ?: 0
                if (result.replyToPeerId == result.peerId) result.replyToPeerId = 0
            }
        }
    }
    when (data) {
        is MTPDmessage -> {
            result.id = data.vid
            commonHeader(data.vdate, data.is_out, data.vpeer_id, data.vfrom_id, data.vreply_to)
        }
        is MTPDmessageService -> {
            result.id = data.vid
            commonHeader(data.vdate, data.is_out, data.vpeer_id, data.vfrom_id, data.vreply_to)
        }
        is MTPDmessageEmpty -> result.id = data.vid
    }
    when (data) {
        is MTPDmessage -> {
            data.vedit_date?.let { result.edited = it }
            (data.vfwd_from as? MTPDmessageFwdHeader)?.let { fwd ->
                result.forwardedFromId = fwd.vfrom_id?.let { ParsePeerId(it) } ?: 0
                result.forwardedFromName = fwd.vfrom_name ?: ""
                result.forwardedDate = fwd.vdate
                result.savedFromChatId = fwd.vsaved_from_peer?.let { ParsePeerId(it) } ?: 0
                result.forwarded = result.forwardedFromId != 0L || result.forwardedFromName.isNotEmpty()
                result.showForwardedAsOriginal = result.forwarded && result.savedFromChatId != 0L
            }
            data.vpost_author?.let { result.signature = it }
            (data.vreply_to as? MTPDmessageReplyHeader)?.let { replyTo ->
                replyTo.vreply_to_msg_id?.let { replyToMsg ->
                    result.replyToMsgId = replyToMsg
                    result.replyToPeerId = replyTo.vreply_to_peer_id?.let { ParsePeerId(it) } ?: 0
                }
            }
            data.vvia_bot_id?.let { result.viaBotId = it }
            data.vmedia?.let { media ->
                context.botId = when {
                    result.viaBotId != 0L -> result.viaBotId
                    Peer_.isUser(result.forwardedFromId) -> Peer_.toUser(result.forwardedFromId)
                    else -> Peer_.toUser(result.fromId)
                }
                result.media = ParseMedia(context, media, mediaFolder, result.date)
                if (result.media.ttl != 0 && !data.is_out) {
                    result.media.resetFile()
                    result.media.resetThumbFile()
                }
                context.botId = 0
            }
            (data.vreply_markup as? MTPDreplyInlineMarkup)?.let { result.inlineButtonRows = ButtonRowsFromTL(it) }
            result.text = ParseText(data.vmessage, data.ventities ?: emptyList())
            data.vreactions?.let { result.reactions = ParseReactions(it) }
            data.vrich_message?.let { result.richMessage = ParseRichMessage(context, it, mediaFolder, result.date) }
        }
        is MTPDmessageService -> result.action = ParseServiceAction(context, data.vaction, mediaFolder, result.date)
        is MTPDmessageEmpty -> result.id = data.vid
    }
    return result
}

fun ParseMessagesList(selfId: PeerId, data: List<MTPMessage>, mediaFolder: String): java.util.TreeMap<MessageId, Message> {
    val context = ParseMediaContext(selfPeerId = selfId)
    val result = java.util.TreeMap<MessageId, Message>()
    for (message in data) {
        val parsed = ParseMessage(context, message, mediaFolder)
        result.putIfAbsent(MessageId(Peer_.toChannel(parsed.peerId), parsed.id), parsed)
    }
    return result
}

fun DialogTypeFromChat(chat: Chat): DialogInfo.Type {
    return when {
        chat.isMonoforum && !chat.isMonoforumAdmin -> DialogInfo.Type.Personal
        chat.isMonoforumAdmin && chat.isMonoforumOfPublicBroadcast -> DialogInfo.Type.PublicSupergroup
        chat.isMonoforumAdmin -> DialogInfo.Type.PrivateSupergroup
        chat.username.isEmpty() -> when {
            chat.isBroadcast -> DialogInfo.Type.PrivateChannel
            chat.isSupergroup -> DialogInfo.Type.PrivateSupergroup
            else -> DialogInfo.Type.PrivateGroup
        }
        else -> if (chat.isBroadcast) DialogInfo.Type.PublicChannel else DialogInfo.Type.PublicSupergroup
    }
}

fun DialogTypeFromUser(user: User): DialogInfo.Type = when {
    user.isSelf -> DialogInfo.Type.Self
    user.isReplies -> DialogInfo.Type.Replies
    user.isVerifyCodes -> DialogInfo.Type.VerifyCodes
    user.isBot -> DialogInfo.Type.Bot
    else -> DialogInfo.Type.Personal
}

private fun DialogInfoFromUser(data: User): DialogInfo = DialogInfo().apply {
    input = Peer(data).input()
    name = data.info.firstName
    lastName = data.info.lastName
    peerId = data.id()
    topMessageDate = 0
    topMessageId = 0
    type = DialogTypeFromUser(data)
    isLeftChannel = false
}

private fun DialogInfoFromChat(data: Chat): DialogInfo = DialogInfo().apply {
    input = data.input
    name = data.title
    peerId = data.id()
    topMessageDate = 0
    topMessageId = 0
    type = DialogTypeFromChat(data)
    migratedToChannelId = data.migratedToChannelId
    isMonoforum = data.isMonoforum
    if (data.isMonoforumAdmin) monoforumBroadcastInput = data.monoforumBroadcastInput
}

fun ParseLeftChannelsInfo(data: MTPmessages_Chats): DialogsInfo {
    val result = DialogsInfo()
    val chats = when (data) {
        is MTPDmessages_chats -> data.vchats
        is MTPDmessages_chatsSlice -> data.vchats
    }
    for (single in chats) {
        val info = DialogInfoFromChat(ParseChat(single))
        info.isLeftChannel = true
        result.left.add(info)
    }
    return result
}

fun ParseDialogsInfo(singlePeer: MTPInputPeer, data: List<MTPUser>): DialogsInfo {
    val singleId: Long = when (singlePeer) {
        is MTPDinputPeerUser -> singlePeer.vuser_id
        MTPDinputPeerSelf -> 0
        else -> throw IllegalStateException("Single peer type in ParseDialogsInfo(users).")
    }
    val result = DialogsInfo()
    for (single in data) {
        val userId = when (single) {
            is MTPDuser -> single.vid
            is MTPDuserEmpty -> single.vid
        }
        if (userId != singleId && (singleId != 0L || single !is MTPDuser || !single.is_self)) continue
        result.chats.add(DialogInfoFromUser(ParseUser(single)))
    }
    return result
}

fun ParseDialogsInfo(singlePeer: MTPInputPeer, data: MTPmessages_Chats): DialogsInfo {
    val singleId: PeerId = when (singlePeer) {
        is MTPDinputPeerChat -> Peer_.fromChat(singlePeer.vchat_id)
        is MTPDinputPeerChannel -> Peer_.fromChannel(singlePeer.vchannel_id)
        else -> throw IllegalStateException("Single peer type in ParseDialogsInfo(chats).")
    }
    val result = DialogsInfo()
    val chats = when (data) {
        is MTPDmessages_chats -> data.vchats
        is MTPDmessages_chatsSlice -> data.vchats
    }
    for (single in chats) {
        val peerId = when (single) {
            is MTPDchannel -> Peer_.fromChannel(single.vid)
            is MTPDchannelForbidden -> Peer_.fromChannel(single.vid)
            is MTPDcommunity -> Peer_.fromChannel(single.vid)
            is MTPDcommunityForbidden -> Peer_.fromChannel(single.vid)
            is MTPDchat -> Peer_.fromChat(single.vid)
            is MTPDchatEmpty -> Peer_.fromChat(single.vid)
            is MTPDchatForbidden -> Peer_.fromChat(single.vid)
        }
        if (peerId != singleId) continue
        val info = DialogInfoFromChat(ParseChat(single))
        info.isLeftChannel = false
        result.chats.add(info)
    }
    return result
}

fun AddMigrateFromSlice(to: DialogInfo, from: DialogInfo, splitIndex: Int, splitsCount: Int): Boolean {
    require(splitIndex < splitsCount)
    val good = when (val m = to.migratedFromInput) {
        MTPDinputPeerEmpty -> true
        is MTPDinputPeerChat -> Peer_.fromChat(m.vchat_id) == from.peerId
        else -> false
    }
    if (!good) return false
    check(from.splits.size == from.messagesCountPerSplit.size)
    for (i in from.splits.indices) {
        check(from.splits[i] < splitsCount)
        to.splits.add(from.splits[i] - splitsCount)
        to.messagesCountPerSplit.add(from.messagesCountPerSplit[i])
    }
    to.migratedFromInput = from.input
    to.splits.add(splitIndex - splitsCount)
    to.messagesCountPerSplit.add(0)
    return true
}

fun FinalizeDialogsInfo(info: DialogsInfo, settings: Settings) {
    val chats = info.chats
    val left = info.left
    val fullCount = chats.size + left.size
    val digits = NumberToString((fullCount - 1).toLong()).length
    var index = 0
    for (dialog in chats) {
        val number = NumberToString((++index).toLong(), digits, '0')
        dialog.relativePath = if (settings.onlySinglePeer()) "" else "chats/chat_$number/"
        val setting = when (dialog.type) {
            DialogInfo.Type.Self, DialogInfo.Type.Personal -> Settings.Type.PersonalChats
            DialogInfo.Type.Bot -> Settings.Type.BotChats
            DialogInfo.Type.PrivateGroup, DialogInfo.Type.PrivateSupergroup -> Settings.Type.PrivateGroups
            DialogInfo.Type.PrivateChannel -> Settings.Type.PrivateChannels
            DialogInfo.Type.PublicSupergroup -> Settings.Type.PublicGroups
            DialogInfo.Type.PublicChannel -> Settings.Type.PublicChannels
            else -> throw IllegalStateException("Type in ApiWrap::onlyMyMessages.")
        }
        dialog.onlyMyMessages = dialog.type != DialogInfo.Type.Personal && (settings.fullChats and setting) != setting
        dialog.splits.sort()
    }
    for (dialog in left) {
        check(!settings.onlySinglePeer())
        val number = NumberToString((++index).toLong(), digits, '0')
        dialog.relativePath = "chats/chat_$number/"
        dialog.onlyMyMessages = true
    }
}

fun ParseMessagesSlice(
    context: ParseMediaContext,
    data: List<MTPMessage>,
    users: List<MTPUser>,
    chats: List<MTPChat>,
    mediaFolder: String,
): MessagesSlice {
    val result = MessagesSlice()
    for (i in data.indices.reversed()) {
        result.list.add(ParseMessage(context, data[i], mediaFolder))
    }
    result.peers = ParsePeersLists(users, chats)
    return result
}

fun AdjustMigrateMessageIds(slice: MessagesSlice): MessagesSlice {
    for (message in slice.list) {
        message.id += kMigratedMessagesIdShift
        if (message.replyToMsgId != 0 && message.replyToPeerId == 0L) {
            message.replyToMsgId += kMigratedMessagesIdShift
        }
    }
    return slice
}

private fun SingleMessageDate(data: MTPmessages_Messages): TimeId {
    val list = when (data) {
        is MTPDmessages_messagesNotModified -> return 0
        is MTPDmessages_messages -> data.vmessages
        is MTPDmessages_messagesSlice -> data.vmessages
        is MTPDmessages_channelMessages -> data.vmessages
    }
    if (list.isEmpty()) return 0
    return when (val m = list[0]) {
        is MTPDmessageEmpty -> 0
        is MTPDmessage -> m.vdate
        is MTPDmessageService -> m.vdate
    }
}

fun SingleMessageBefore(data: MTPmessages_Messages, date: TimeId): Boolean {
    val single = SingleMessageDate(data)
    return single > 0 && single < date
}

fun SingleMessageAfter(data: MTPmessages_Messages, date: TimeId): Boolean {
    val single = SingleMessageDate(data)
    return single > 0 && single > date
}

fun SkipMessageByDate(message: Message, settings: Settings): Boolean {
    val goodFrom = settings.singlePeerFrom <= 0 || settings.singlePeerFrom <= message.date
    val goodTill = settings.singlePeerTill <= 0 || message.date < settings.singlePeerTill
    return !goodFrom || !goodTill
}
