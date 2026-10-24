// Port of HtmlWriter / HtmlWriter::Wrap from tdesktop
// Telegram/SourceFiles/export/output/export_output_html.cpp
@file:Suppress("FunctionName")

package org.tgbackup.export.output

import org.tgbackup.export.Environment
import org.tgbackup.export.Settings
import org.tgbackup.export.data.*
import org.tgbackup.export.data.File.SkipReason as SR

/** Data::WriteImageThumb: returns the relative thumb path and its size, or ("", null). */
internal fun WriteImageThumb(
    thumbs: ImageThumbs,
    basePath: String,
    largePath: String,
    convertSize: (QSize) -> QSize,
    format: String? = null,
    quality: Int? = null,
    postfix: String = "_thumb",
): Pair<String, QSize?> {
    if (largePath.isEmpty()) return "" to null
    val path = basePath + largePath
    val (w, h) = thumbs.size(path) ?: return "" to null
    val size = QSize(w, h)
    if (size.isEmpty() || size.width >= 10000 || size.height >= 10000) return "" to null
    val finalSize = convertSize(size)
    if (finalSize.isEmpty()) return "" to null
    val lastSlash = largePath.lastIndexOf('/')
    val firstDot = largePath.indexOf('.', lastSlash + 1)
    val thumb = if (firstDot >= 0) {
        largePath.substring(0, firstDot) + postfix + largePath.substring(firstDot)
    } else {
        largePath + postfix
    }
    val result = File.PrepareRelativePath(basePath, thumb)
    if (!thumbs.scaleAndSave(path, finalSize.width, finalSize.height, basePath + result, format, quality ?: -1)) {
        return "" to null
    }
    return result to finalSize
}

internal fun WriteImageThumb(
    thumbs: ImageThumbs,
    basePath: String,
    largePath: String,
    width: Int,
    height: Int,
    postfix: String = "_thumb",
): String = WriteImageThumb(thumbs, basePath, largePath, { QSize(width, height) }, null, null, postfix).first

internal class MessageInfo {
    enum class Type { Service, Default }

    var id = 0
    var type = Type.Service
    var fromId: PeerId = 0
    var viaBotId: Long = 0
    var date: TimeId = 0
    var forwardedFromId: PeerId = 0
    var forwardedFromName = ""
    var forwarded = false
    var showForwardedAsOriginal = false
    var forwardedDate: TimeId = 0

    fun copy(): MessageInfo = MessageInfo().also {
        it.id = id
        it.type = type
        it.fromId = fromId
        it.viaBotId = viaBotId
        it.date = date
        it.forwardedFromId = forwardedFromId
        it.forwardedFromName = forwardedFromName
        it.forwarded = forwarded
        it.showForwardedAsOriginal = showForwardedAsOriginal
        it.forwardedDate = forwardedDate
    }
}

private fun FillUserpicNames(data: UserpicData, peer: Peer) {
    val user = peer.user()
    if (user != null) {
        data.firstName = user.info.firstName
        data.lastName = user.info.lastName
    } else if (peer.chat() != null) {
        data.firstName = peer.name()
    }
}

private fun FillUserpicNames(data: UserpicData, full: String) {
    val names = full.split(' ')
    data.firstName = names[0]
    for (i in 1 until names.size) {
        if (names[i].isEmpty()) continue
        if (data.lastName.isNotEmpty()) data.lastName += ' '
        data.lastName += names[i]
    }
}

private fun ComposeName(data: UserpicData, empty: String): String =
    if (data.firstName.isEmpty() && data.lastName.isEmpty()) empty else data.firstName + ' ' + data.lastName

private fun UserpicData.copy(): UserpicData = UserpicData().also {
    it.colorIndex = colorIndex
    it.pixelSize = pixelSize
    it.imageLink = imageLink
    it.largeLink = largeLink
    it.firstName = firstName
    it.lastName = lastName
    it.tooltip = tooltip
}

internal class Wrap(path: String, base: String, stats: Stats?, private val thumbs: ImageThumbs) {
    private val file = File(path, stats)
    private var closed = false
    private val base: String
    private val context = HtmlContext()
    private val composedStart: String

    init {
        check(base.endsWith('/'))
        check(path.startsWith(base))
        val left = path.substring(base.length)
        val nesting = left.count { it == '/' }
        this.base = "../".repeat(nesting)
        composedStart = composeStart()
    }

    fun empty(): Boolean = file.empty()

    fun pushTag(tag: String, attributes: Attrs = Attrs()): String = context.pushTag(tag, attributes)
    fun popTag(): String = context.popTag()
    fun indent(): String = context.indent()

    fun pushDiv(className: String, style: String = ""): String =
        if (style.isEmpty()) context.pushTag("div", attrs("class" to className))
        else context.pushTag("div", attrs("class" to className, "style" to style))

    private fun WriteUserpicThumb(basePath: String, largePath: String, userpic: UserpicData, postfix: String = "_thumb"): String =
        WriteImageThumb(thumbs, basePath, largePath, userpic.pixelSize * 2, userpic.pixelSize * 2, postfix)

    fun pushUserpic(userpic: UserpicData): String {
        val size = NumberToString(userpic.pixelSize) + "px"
        val result = StringBuilder()
        if (userpic.largeLink.isNotEmpty()) {
            result.append(pushTag("a", attrs("class" to "userpic_link", "href" to relativePath(userpic.largeLink))))
        }
        val sizeStyle = "width: $size; height: $size"
        if (userpic.imageLink.isNotEmpty()) {
            result.append(
                pushTag("img", attrs("class" to "userpic", "style" to sizeStyle, "src" to relativePath(userpic.imageLink), "empty" to "")),
            )
        } else {
            result.append(
                pushTag("div", attrs("class" to "userpic userpic" + NumberToString(userpic.colorIndex + 1), "style" to sizeStyle)),
            )
            if (userpic.tooltip.isEmpty()) {
                result.append(pushDiv("initials", "line-height: $size"))
            } else {
                result.append(pushTag("div", attrs("class" to "initials", "style" to "line-height: $size", "title" to userpic.tooltip)))
            }
            fun character(from: String): String {
                val utf = QTrimmed(from)
                return if (utf.isEmpty()) "" else SerializeHtml(utf.substring(0, 1))
            }
            result.append(character(userpic.firstName))
            result.append(character(userpic.lastName))
            result.append(popTag())
            result.append(popTag())
        }
        if (userpic.largeLink.isNotEmpty()) result.append(popTag())
        return result.toString()
    }

    fun pushListEntry(userpic: UserpicData, name: String, details: String, info: String, link: String = ""): String =
        pushGenericListEntry(link, userpic, name, "", listOf(details), info)

    private fun pushGenericListEntry(
        link: String,
        userpic: UserpicData,
        name: String,
        subname: String,
        details: List<String>,
        info: String,
    ): String {
        val result = StringBuilder(
            if (link.isEmpty()) {
                pushDiv("entry clearfix")
            } else {
                pushTag("a", attrs("class" to "entry block_link clearfix", "href" to relativePath(link) + "#allow_back"))
            },
        )
        result.append(pushDiv("pull_left userpic_wrap"))
        result.append(pushUserpic(userpic))
        result.append(popTag())
        result.append(pushDiv("body"))
        if (info.isNotEmpty()) {
            result.append(pushDiv("pull_right info details"))
            result.append(SerializeHtml(info))
            result.append(popTag())
        }
        if (name.isNotEmpty()) {
            result.append(pushDiv("name bold"))
            result.append(SerializeHtml(name))
            result.append(popTag())
        }
        if (subname.isNotEmpty()) {
            result.append(pushDiv("subname bold"))
            result.append(SerializeHtml(subname))
            result.append(popTag())
        }
        for (detail in details) {
            result.append(pushDiv("details_entry details"))
            result.append(SerializeHtml(detail))
            result.append(popTag())
        }
        result.append(popTag())
        result.append(popTag())
        return result.toString()
    }

    fun writeBlock(block: String): Result {
        check(!closed)
        val result = when {
            block.isEmpty() -> file.writeBlock(block)
            file.empty() -> file.writeBlock(composedStart + block)
            else -> file.writeBlock(block)
        }
        if (!result.isSuccess()) closed = true
        return result
    }

    fun pushHeader(header: String, path: String = ""): String {
        val result = StringBuilder(pushDiv("page_header"))
        result.append(
            if (path.isEmpty()) {
                pushDiv("content")
            } else {
                pushTag("a", attrs("class" to "content block_link", "href" to relativePath(path), "onclick" to "return GoBack(this)"))
            },
        )
        result.append(pushDiv("text bold"))
        result.append(SerializeHtml(header))
        result.append(popTag())
        result.append(popTag())
        result.append(popTag())
        return result.toString()
    }

    fun pushSection(header: String, type: String, count: Int, link: String): String {
        val result = StringBuilder(pushTag("a", attrs("class" to "section block_link $type", "href" to "$link#allow_back")))
        result.append(pushDiv("counter details"))
        result.append(NumberToString(count))
        result.append(popTag())
        result.append(pushDiv("label bold"))
        result.append(SerializeHtml(header))
        result.append(popTag())
        result.append(popTag())
        return result.toString()
    }

    fun pushAbout(text: String, withDivider: Boolean = false): String {
        val result = StringBuilder(pushDiv(if (withDivider) "page_about details with_divider" else "page_about details"))
        result.append(MakeLinks(SerializeHtml(text)))
        result.append(popTag())
        return result.toString()
    }

    fun pushServiceMessage(messageId: Int, dialog: DialogInfo, basePath: String, serialized: String, photo: Photo? = null): String {
        val result = StringBuilder(pushTag("div", attrs("class" to "message service", "id" to "message" + NumberToString(messageId))))
        result.append(pushDiv("body details"))
        result.append(serialized)
        result.append(popTag())
        if (photo != null) {
            val userpic = UserpicData()
            userpic.colorIndex = dialog.colorIndex
            userpic.firstName = dialog.name
            userpic.lastName = dialog.lastName
            userpic.pixelSize = kServiceMessagePhotoSize
            userpic.largeLink = photo.image.file.relativePath
            userpic.imageLink = WriteUserpicThumb(basePath, userpic.largeLink, userpic)
            result.append(pushDiv("userpic_wrap"))
            result.append(pushUserpic(userpic))
            result.append(popTag())
        }
        result.append(popTag())
        return result.toString()
    }

    fun pushMessage(
        message: Message,
        previous: MessageInfo?,
        dialog: DialogInfo,
        basePath: String,
        peers: PeersMap,
        internalLinksDomain: String,
        wrapMessageLink: (Int, String) -> String,
    ): Pair<MessageInfo, String> {
        val info = MessageInfo()
        info.id = message.id
        info.fromId = message.fromId
        info.viaBotId = message.viaBotId
        info.date = message.date
        info.forwardedFromId = message.forwardedFromId
        info.forwardedFromName = message.forwardedFromName
        info.forwardedDate = message.forwardedDate
        info.forwarded = message.forwarded
        info.showForwardedAsOriginal = message.showForwardedAsOriginal
        if (message.media.content === UnsupportedMedia) {
            return info to pushServiceMessage(
                message.id,
                dialog,
                basePath,
                "This message is not supported by this version of AyuGram Desktop. Please update the application.",
            )
        }
        fun wrapReplyToLink(text: String) = wrapMessageLink(message.replyToMsgId, text)
        val isChannel = dialog.type == DialogInfo.Type.PrivateChannel || dialog.type == DialogInfo.Type.PublicChannel
        val serviceFrom = peers.wrapPeerName(message.fromId)
        val serviceText: String = when (val data = message.action.content) {
            is ActionChatCreate -> serviceFrom + " created group &laquo;" + SerializeHtml(data.title) + "&raquo;" +
                (if (data.userIds.isEmpty()) "" else " with members " + peers.wrapUserNames(data.userIds))
            is ActionChatEditTitle -> if (isChannel) {
                "Channel title changed to &laquo;" + SerializeHtml(data.title) + "&raquo;"
            } else {
                serviceFrom + " changed group title to &laquo;" + SerializeHtml(data.title) + "&raquo;"
            }
            is ActionChatEditPhoto -> if (isChannel) "Channel photo changed" else "$serviceFrom changed group photo"
            ActionChatDeletePhoto -> if (isChannel) "Channel photo removed" else "$serviceFrom removed group photo"
            is ActionChatAddUser -> serviceFrom + " invited " + peers.wrapUserNames(data.userIds)
            is ActionChatDeleteUser -> serviceFrom + " removed " + peers.wrapUserName(data.userId)
            is ActionChatJoinedByLink -> serviceFrom + " joined group by link from " + peers.wrapUserName(data.inviterId)
            is ActionChannelCreate -> "Channel &laquo;" + SerializeHtml(data.title) + "&raquo; created"
            is ActionChatMigrateTo -> "$serviceFrom converted this group to a supergroup"
            is ActionChannelMigrateFrom -> serviceFrom + " converted a basic group to this supergroup " +
                "&laquo;" + SerializeHtml(data.title) + "&raquo;"
            ActionPinMessage -> serviceFrom + " pinned " + wrapReplyToLink("this message")
            ActionHistoryClear -> "History cleared"
            is ActionGameScore -> serviceFrom + " scored " + NumberToString(data.score) + " in " + wrapReplyToLink("this game")
            is ActionPaymentSent -> {
                val amount = FormatMoneyAmount(data.amount, data.currency)
                if (data.recurringUsed) {
                    "You were charged $amount via recurring payment"
                } else {
                    var r = "You have successfully transferred " + amount + " for " + wrapReplyToLink("this invoice")
                    if (data.recurringInit) r += " and allowed future recurring payments"
                    r
                }
            }
            is ActionPhoneCall -> ""
            ActionScreenshotTaken -> "$serviceFrom took a screenshot"
            is ActionCustomAction -> data.message
            is ActionBotAllowed -> when {
                data.attachMenu -> "You allowed this bot to message you when you added it in the attachment menu."
                data.fromRequest -> "You allowed this bot to message you in his web-app."
                data.app.isEmpty() -> "You allowed this bot to message you when you opened " + SerializeHtml(data.app)
                else -> "You allowed this bot to message you when you logged in on " + SerializeHtml(data.domain)
            }
            is ActionSecureValuesSent -> {
                val T = ActionSecureValuesSent.Type.Email.javaClass
                @Suppress("UNUSED_VARIABLE") val unused = T
                val list = data.types.map { type ->
                    when (type) {
                        ActionSecureValuesSent.Type.PersonalDetails -> "Personal details"
                        ActionSecureValuesSent.Type.Passport -> "Passport"
                        ActionSecureValuesSent.Type.DriverLicense -> "Driver license"
                        ActionSecureValuesSent.Type.IdentityCard -> "Identity card"
                        ActionSecureValuesSent.Type.InternalPassport -> "Internal passport"
                        ActionSecureValuesSent.Type.Address -> "Address information"
                        ActionSecureValuesSent.Type.UtilityBill -> "Utility bill"
                        ActionSecureValuesSent.Type.BankStatement -> "Bank statement"
                        ActionSecureValuesSent.Type.RentalAgreement -> "Rental agreement"
                        ActionSecureValuesSent.Type.PassportRegistration -> "Passport registration"
                        ActionSecureValuesSent.Type.TemporaryRegistration -> "Temporary registration"
                        ActionSecureValuesSent.Type.Phone -> "Phone number"
                        ActionSecureValuesSent.Type.Email -> "Email"
                    }
                }
                "You have sent the following documents: " + SerializeList(list)
            }
            ActionContactSignUp -> "$serviceFrom joined Telegram"
            is ActionGeoProximityReached -> {
                val fromName = peers.wrapPeerName(data.fromId)
                val toName = peers.wrapPeerName(data.toId)
                val distance = when {
                    data.distance >= 1000 -> QNumber((10 * (data.distance / 10)) / 1000.0) + " km"
                    data.distance == 1 -> "1 meter"
                    else -> data.distance.toString() + " meters"
                }
                when {
                    data.fromSelf -> "You are now within $distance from $toName"
                    data.toSelf -> "$fromName is now within $distance from you"
                    else -> "$fromName is now within $distance from $toName"
                }
            }
            ActionPhoneNumberRequest -> "$serviceFrom requested your phone number"
            is ActionGroupCall -> {
                val durationText = if (data.duration != 0) " (" + data.duration + " seconds)" else ""
                if (isChannel) "Voice chat$durationText" else "$serviceFrom started voice chat$durationText"
            }
            is ActionInviteToGroupCall -> serviceFrom + " invited " + peers.wrapUserNames(data.userIds) + " to the voice chat"
            is ActionSetMessagesTTL -> {
                val periodText = when (data.period) {
                    7 * 86400 -> "7 days"
                    86400 -> "24 hours"
                    else -> ""
                }
                if (isChannel) {
                    if (data.period != 0) "New messages will auto-delete in $periodText" else "New messages will not auto-delete"
                } else {
                    if (data.period != 0) "$serviceFrom has set messages to auto-delete in $periodText"
                    else "$serviceFrom has set messages not to auto-delete"
                }
            }
            is ActionGroupCallScheduled -> {
                val dateText = FormatDateTime(data.date)
                if (isChannel) "Voice chat scheduled for $dateText" else "$serviceFrom scheduled a voice chat for $dateText"
            }
            is ActionSetChatTheme -> if (data.emoji.isEmpty()) {
                if (isChannel) "Channel theme was disabled" else "$serviceFrom disabled chat theme"
            } else {
                if (isChannel) "Channel theme was changed to " + data.emoji else serviceFrom + " changed chat theme to " + data.emoji
            }
            ActionChatJoinedByRequest -> "$serviceFrom joined group by request"
            is ActionChatJoinedViaCommunity -> "$serviceFrom joined group via a community"
            is ActionWebViewDataSent -> "You have just successfully transferred data from the &laquo;" +
                SerializeHtml(data.text) + "&raquo; button to the bot"
            is ActionGiftPremium -> if (data.days == 0 || data.cost.isEmpty()) {
                "$serviceFrom sent you a gift."
            } else {
                serviceFrom + " sent you a gift for " + data.cost + ": Telegram Premium for " + data.days + " days."
            }
            is ActionTopicCreate -> serviceFrom + " created topic &laquo;" + SerializeHtml(data.title) + "&raquo;"
            is ActionTopicEdit -> {
                val parts = ArrayList<String>()
                if (data.title.isNotEmpty()) parts.add("title to &laquo;" + SerializeHtml(data.title) + "&raquo;")
                data.iconEmojiId?.let { parts.add("icon to &laquo;" + java.lang.Long.toUnsignedString(it) + "&raquo;") }
                serviceFrom + " changed topic " + parts.joinToString(",")
            }
            is ActionSuggestProfilePhoto -> "$serviceFrom suggests to use this photo"
            is ActionRequestedPeer -> "requested: "
            is ActionSetChatWallPaper -> serviceFrom + if (data.same) {
                " set " + wrapReplyToLink("the same background") + " for this chat"
            } else {
                " set a new background for this chat"
            }
            is ActionGiftCode -> {
                val days = NumberToString(data.days) + if (data.days > 1) " days" else " day"
                when {
                    data.unclaimed -> "This is an unclaimed Telegram Premium for $days prize in a giveaway organized by a channel."
                    data.viaGiveaway -> "You won a Telegram Premium for $days prize in a giveaway organized by a channel."
                    else -> "You've received a Telegram Premium for $days gift from a channel."
                }
            }
            ActionGiveawayLaunch -> "$serviceFrom just started a giveaway of Telegram Premium subscriptions to its followers."
            is ActionGiveawayResults -> when {
                data.winners == 0 -> "No winners of the giveaway could be selected."
                data.credits && data.unclaimed != 0 ->
                    "Some winners of the giveaway were randomly selected by Telegram and received their prize."
                !data.credits && data.unclaimed != 0 ->
                    "Some winners of the giveaway were randomly selected by Telegram and received private messages with giftcodes."
                data.credits && data.unclaimed == 0 ->
                    NumberToString(data.winners) + " of the giveaway was randomly selected by Telegram and received their prize."
                else -> NumberToString(data.winners) +
                    " of the giveaway was randomly selected by Telegram and received private messages with giftcodes."
            }
            is ActionBoostApply -> serviceFrom + " boosted the group " + data.boosts + (if (data.boosts > 1) " times" else " time")
            is ActionPaymentRefunded -> peers.wrapPeerName(data.peerId) + " refunded back " + FormatMoneyAmount(data.amount, data.currency)
            is ActionGiftCredits -> if (data.amount.empty() || data.cost.isEmpty()) {
                "$serviceFrom sent you a gift."
            } else {
                serviceFrom + " sent you a gift for " + data.cost + ": " + QNumber(data.amount.value()) +
                    (if (data.amount.ton) " TON." else " Telegram Stars.")
            }
            is ActionPrizeStars -> "You won a prize in a giveaway organized by " + peers.wrapPeerName(data.peerId) +
                ".\n Your prize is " + java.lang.Long.toUnsignedString(data.amount) + " Telegram Stars."
            is ActionStarGift -> serviceFrom + " sent you a gift of " + data.stars + " Telegram Stars."
            is ActionPaidMessagesRefunded -> if (message.out) {
                "You refunded " + data.stars + " Stars for " + data.messages + " messages to " + peers.wrapPeerName(dialog.peerId)
            } else {
                peers.wrapPeerName(dialog.peerId) + " refunded " + data.stars + " Stars for " + data.messages + " messages to you"
            }
            is ActionPaidMessagesPrice -> if (isChannel) {
                if (!data.broadcastAllowed) "Direct messages were disabled."
                else "Price per direct message changed to " + data.stars + " Telegram Stars."
            } else {
                "Price per message changed to " + data.stars + " Telegram Stars."
            }
            is ActionTodoCompletions -> {
                val completed = data.completed.map { it.toString() }
                val incompleted = data.incompleted.map { it.toString() }
                fun list(v: List<String>) = when {
                    v.isEmpty() -> ""
                    v.size > 1 -> v.subList(0, v.size - 1).joinToString(", ") + " and " + v.last()
                    else -> v.first()
                }
                if (completed.isEmpty() && incompleted.isNotEmpty()) {
                    serviceFrom + " marked " + list(incompleted) + " as not done yet in " + wrapReplyToLink("this todo list") + "."
                } else if (completed.isNotEmpty() && incompleted.isEmpty()) {
                    serviceFrom + " marked " + list(completed) + " as done in " + wrapReplyToLink("this todo list") + "."
                } else {
                    serviceFrom + " marked " + list(completed) + " as done and " + list(incompleted) +
                        " as not done yet in " + wrapReplyToLink("this todo list") + "."
                }
            }
            is ActionTodoAppendTasks -> serviceFrom + " added tasks: " +
                data.items.joinToString(", ") { "&quot;" + FormatText(it.text, internalLinksDomain, base) + "&quot;" }
            is ActionPollAppendAnswer -> serviceFrom + " added &quot;" + data.option + "&quot; to the poll."
            is ActionPollDeleteAnswer -> serviceFrom + " removed &quot;" + data.option + "&quot; from the poll."
            is ActionSuggestedPostApproval -> serviceFrom + (if (data.rejected) " rejected " else " approved ") +
                "your suggested post" +
                (if (!data.price.empty()) ", for " + QNumber(data.price.value()) + (if (data.price.ton) " TON" else " stars") else "") +
                (if (data.scheduleDate != 0) ", " + FormatDateText(data.scheduleDate) + " at " + FormatTimeText(data.scheduleDate) else "") +
                (if (data.rejectComment.isEmpty()) "." else ", with comment: &quot;" + SerializeHtml(data.rejectComment) + "&quot;")
            is ActionSuggestedPostSuccess -> "The paid post was shown for 24 hours and " + QNumber(data.price.value()) +
                (if (data.price.ton) " TON" else " stars") + " were transferred to the channel."
            is ActionSuggestedPostRefund -> if (data.payerInitiated) {
                "The user refunded the payment, post was deleted."
            } else {
                "The admin deleted the post early, the payment was refunded."
            }
            is ActionSuggestBirthday -> serviceFrom + " suggests to add a date of birth: " + data.birthday.day() +
                when (data.birthday.month()) {
                    1 -> " January"
                    2 -> " February"
                    3 -> " March"
                    4 -> " April"
                    5 -> " May"
                    6 -> " June"
                    7 -> " July"
                    8 -> " August"
                    9 -> " September"
                    10 -> " October"
                    11 -> " November"
                    12 -> " December"
                    else -> ""
                } + (if (data.birthday.year() != 0) " " + data.birthday.year() else "")
            is ActionNoForwardsToggle -> serviceFrom +
                if (data.newValue) " disabled sharing in this chat" else " enabled sharing in this chat"
            is ActionNoForwardsRequest -> "$serviceFrom requested to enable sharing in this chat"
            is ActionNewCreatorPending -> peers.wrapUserName(data.newCreatorId) +
                " will become the new main admin in 7 days if " + serviceFrom + " does not return"
            is ActionChangeCreator -> serviceFrom + " made " + peers.wrapUserName(data.newCreatorId) + " the new main admin of the group"
            is ActionManagedBotCreated -> serviceFrom + " created a bot " + peers.wrapUserName(data.botId)
            null -> ""
        }

        if (serviceText.isNotEmpty()) {
            val content = message.action.content
            val photo = when (content) {
                is ActionChatEditPhoto -> content.photo
                is ActionSuggestProfilePhoto -> content.photo
                else -> null
            }
            return info to pushServiceMessage(message.id, dialog, basePath, serviceText, photo)
        }
        info.type = MessageInfo.Type.Default

        val wrap = messageNeedsWrap(message, previous)
        val fromPeerId = message.fromId
        val showForwardedInfo = message.forwarded && !message.showForwardedAsOriginal
        val forwardedUserpic = UserpicData()
        if (message.forwarded) {
            forwardedUserpic.colorIndex = if (message.forwardedFromId != 0L) {
                PeerColorIndexOfPeer(message.forwardedFromId)
            } else {
                PeerColorIndex(message.id.toLong())
            }
            forwardedUserpic.pixelSize = kHistoryUserpicSize
            if (message.forwardedFromId != 0L) {
                FillUserpicNames(forwardedUserpic, peers.peer(message.forwardedFromId))
            } else {
                FillUserpicNames(forwardedUserpic, message.forwardedFromName)
            }
        }
        var userpic = UserpicData()
        if (message.showForwardedAsOriginal) {
            userpic = forwardedUserpic.copy()
        } else {
            userpic.colorIndex = PeerColorIndexOfPeer(fromPeerId)
            userpic.pixelSize = kHistoryUserpicSize
            FillUserpicNames(userpic, peers.peer(fromPeerId))
        }
        val via = run {
            if (message.viaBotId != 0L) {
                val user = peers.user(message.viaBotId)
                if (user.username.isNotEmpty()) return@run SerializeHtml(user.username)
            }
            ""
        }
        val className = if (wrap) "message default clearfix" else "message default clearfix joined"
        val block = StringBuilder(pushTag("div", attrs("class" to className, "id" to "message" + NumberToString(message.id))))
        if (wrap) {
            block.append(pushDiv("pull_left userpic_wrap"))
            block.append(pushUserpic(userpic))
            block.append(popTag())
        }
        block.append(pushDiv("body"))
        block.append(pushTag("div", attrs("class" to "pull_right date details", "title" to FormatDateTime(message.date, true))))
        block.append(FormatTimeText(message.date))
        block.append(popTag())
        if (wrap) {
            block.append(pushDiv("from_name"))
            block.append(SerializeHtml(ComposeName(userpic, "Deleted Account")))
            if (via.isNotEmpty() && (!message.forwarded || message.showForwardedAsOriginal)) block.append(" via @$via")
            block.append(popTag())
        }
        if (showForwardedInfo) {
            val forwardedWrap = forwardedNeedsWrap(message, previous)
            if (forwardedWrap) {
                block.append(pushDiv("pull_left forwarded userpic_wrap"))
                block.append(pushUserpic(forwardedUserpic))
                block.append(popTag())
            }
            block.append(pushDiv("forwarded body"))
            if (forwardedWrap) {
                block.append(pushDiv("from_name"))
                block.append(SerializeHtml(ComposeName(forwardedUserpic, "Deleted Account")))
                if (via.isNotEmpty()) block.append(" via @$via")
                block.append(
                    pushTag(
                        "span",
                        attrs("class" to "date details", "title" to FormatDateTime(message.forwardedDate, true), "inline" to ""),
                    ),
                )
                block.append(' ' + FormatDateTime(message.forwardedDate))
                block.append(popTag())
                block.append(popTag())
            }
        }
        if (message.replyToMsgId != 0) {
            block.append(pushDiv("reply_to details"))
            if (message.replyToPeerId != 0L) {
                block.append("In reply to a message in another chat")
            } else {
                block.append("In reply to ")
                block.append(wrapReplyToLink("this message"))
            }
            block.append(popTag())
        }

        block.append(pushMedia(message, basePath, peers, internalLinksDomain, wrapMessageLink))

        val rich = message.richMessage
        if (rich != null) {
            val callbacks = RichMediaCallbacks(
                photo = { pushRichPhotoMedia(it, basePath) },
                video = { pushRichVideoMedia(it, basePath) },
                audio = { pushRichAudioMedia(it) },
                file = { pushRichFileMedia(it) },
                generic = { pushGenericMedia(it) },
                photoCard = { data, photo -> pushRichReferenceMedia(data, photo, basePath) },
            )
            block.append(RenderRichMessage(context, rich, message.id, internalLinksDomain, base, callbacks))
        } else {
            val text = FormatText(message.text, internalLinksDomain, base)
            if (text.isNotEmpty()) {
                block.append(pushDiv("text"))
                block.append(text)
                block.append(popTag())
            }
        }
        if (message.inlineButtonRows.isNotEmpty()) {
            val endline = " | "
            block.append(pushTag("table", attrs("class" to "bot_buttons_table")))
            block.append(pushTag("tbody"))
            for (row in message.inlineButtonRows) {
                block.append(pushTag("tr"))
                block.append(pushTag("td", attrs("class" to "bot_button_row")))
                for ((index, button) in row.withIndex()) {
                    val dataText = String(button.data, Charsets.UTF_8)
                    val content = (if (button.data.isNotEmpty()) "Data: $dataText$endline" else "") +
                        (if (button.forwardText.isNotEmpty()) "Forward text: " + button.forwardText + endline else "") +
                        ("Type: " + HistoryMessageMarkupButton.TypeToString(button))
                    val link = if (button.type == HistoryMessageMarkupButton.Type.Url) dataText else ""
                    val onclick = if (button.type != HistoryMessageMarkupButton.Type.Url) {
                        "return ShowTextCopied('" + content.replace("\\", "\\\\").replace("'", "\\'") + "');"
                    } else ""
                    block.append(pushTag("div", attrs("class" to "bot_button")))
                    block.append(
                        pushTag(
                            "a",
                            attrs(
                                if (link.isEmpty()) "" to "" else "href" to link,
                                if (onclick.isEmpty()) "" to "" else "onclick" to onclick,
                            ),
                        ),
                    )
                    block.append(pushTag("div"))
                    block.append(SerializeHtml(button.text))
                    block.append(popTag())
                    block.append(popTag())
                    block.append(popTag())
                    if (index != row.size - 1) {
                        block.append(pushTag("div", attrs("class" to "bot_button_column_separator")))
                        block.append(popTag())
                    }
                }
                block.append(popTag())
                block.append(popTag())
            }
            block.append(popTag())
            block.append(popTag())
        }
        if (message.signature.isNotEmpty()) {
            block.append(pushDiv("signature details"))
            block.append(SerializeHtml(message.signature))
            block.append(popTag())
        }
        if (showForwardedInfo) block.append(popTag())
        if (message.reactions.isNotEmpty()) {
            block.append(pushTag("span", attrs("class" to "reactions")))
            for (reaction in message.reactions) {
                var reactionClass = "reaction"
                for (recent in reaction.recent) {
                    val peer = peers.peer(recent.peerId)
                    if (peer.user()?.isSelf == true) {
                        reactionClass += " active"
                        break
                    }
                }
                if (reaction.type == Reaction.Type.Paid) reactionClass += " paid"
                block.append(pushTag("span", attrs("class" to reactionClass)))
                block.append(pushTag("span", attrs("class" to "emoji")))
                when (reaction.type) {
                    Reaction.Type.Emoji -> block.append(SerializeHtml(reaction.emoji))
                    Reaction.Type.CustomEmoji -> block.append(FormatCustomEmoji(reaction.documentId, "👋", base))
                    Reaction.Type.Paid -> block.append(SerializeHtml("⭐"))
                    else -> Unit
                }
                block.append(popTag())
                if (reaction.recent.isNotEmpty()) {
                    block.append(pushTag("span", attrs("class" to "userpics")))
                    for (recent in reaction.recent) {
                        val peer = peers.peer(recent.peerId)
                        val u = UserpicData()
                        u.colorIndex = peer.colorIndex()
                        u.pixelSize = 20
                        u.firstName = peer.user()?.info?.firstName ?: peer.name()
                        u.lastName = peer.user()?.info?.lastName ?: ""
                        u.tooltip = peer.name()
                        block.append(pushUserpic(u))
                    }
                    block.append(popTag())
                }
                if (reaction.recent.isEmpty() || reaction.count > reaction.recent.size) {
                    block.append(pushTag("span", attrs("class" to "count")))
                    block.append(NumberToString(reaction.count))
                    block.append(popTag())
                }
                block.append(popTag())
            }
            block.append(popTag())
        }
        block.append(popTag())
        block.append(popTag())
        return info to block.toString()
    }

    private fun sameLocalDate(a: TimeId, b: TimeId): Boolean = !DisplayDate(a, b)

    private fun messageNeedsWrap(message: Message, previous: MessageInfo?): Boolean = when {
        previous == null -> true
        previous.type != MessageInfo.Type.Default -> true
        message.fromId == 0L || previous.fromId != message.fromId -> true
        message.viaBotId != previous.viaBotId -> true
        !sameLocalDate(message.date, previous.date) -> true
        message.forwarded != previous.forwarded ||
            message.showForwardedAsOriginal != previous.showForwardedAsOriginal ||
            message.forwardedFromId != previous.forwardedFromId ||
            message.forwardedFromName != previous.forwardedFromName -> true
        Math.abs(message.date - previous.date) >
            (if (message.forwardedFromId != 0L || message.forwardedFromName.isNotEmpty()) 1 else kJoinWithinSeconds) -> true
        else -> false
    }

    private fun pushMedia(
        message: Message,
        basePath: String,
        peers: PeersMap,
        internalLinksDomain: String,
        wrapMessageLink: (Int, String) -> String,
    ): String {
        val data = prepareMediaData(message, basePath, peers, internalLinksDomain)
        if (data.classes.isNotEmpty()) return pushGenericMedia(data)
        return when (val content = message.media.content) {
            is Document -> {
                check(message.media.ttl == 0)
                when {
                    content.isSticker -> pushStickerMedia(content, basePath)
                    content.isAnimated -> pushAnimatedMedia(content, basePath)
                    content.isVideoFile -> pushVideoFileMedia(content, basePath)
                    else -> throw IllegalStateException("Non generic document in HtmlWriter::Wrap::pushMedia.")
                }
            }
            is Photo -> {
                check(message.media.ttl == 0)
                pushPhotoMedia(content, basePath)
            }
            is Poll -> pushPoll(content, internalLinksDomain, base)
            is TodoList -> pushTodoList(content, internalLinksDomain, base)
            is GiveawayStart -> pushGiveaway(peers, content)
            is GiveawayResults -> pushGiveaway(peers, content, wrapMessageLink)
            else -> {
                check(content == null)
                ""
            }
        }
    }

    fun pushGenericMedia(data: MediaData): String {
        val result = StringBuilder(pushDiv("media_wrap clearfix"))
        if (data.link.isEmpty()) {
            result.append(pushDiv("media clearfix pull_left " + data.classes))
        } else {
            result.append(
                pushTag(
                    "a",
                    attrs(
                        "class" to "media clearfix pull_left block_link " + data.classes,
                        "href" to (if (IsGlobalLink(data.link)) data.link else relativePath(data.link)),
                    ),
                ),
            )
        }
        if (data.thumb.isEmpty()) {
            result.append(pushDiv("fill pull_left"))
            result.append(popTag())
        } else {
            result.append(pushTag("img", attrs("class" to "thumb pull_left", "src" to relativePath(data.thumb), "empty" to "")))
        }
        result.append(pushDiv("body"))
        if (data.title.isNotEmpty()) {
            result.append(pushDiv("title bold"))
            result.append(SerializeHtml(data.title))
            result.append(popTag())
        }
        if (data.description.isNotEmpty()) {
            result.append(pushDiv("description"))
            result.append(SerializeHtml(data.description))
            result.append(popTag())
        }
        if (data.status.isNotEmpty()) {
            result.append(pushDiv("status details"))
            result.append(SerializeHtml(data.status))
            result.append(popTag())
        }
        result.append(popTag())
        result.append(popTag())
        result.append(popTag())
        return result.toString()
    }

    private fun pushStickerMedia(data: Document, basePath: String): String {
        val (thumb, size) = WriteImageThumb(
            thumbs,
            basePath,
            data.file.relativePath,
            CalculateThumbSize(kStickerMaxWidth, kStickerMaxHeight, kStickerMinWidth, kStickerMinHeight),
            "PNG",
            -1,
        )
        if (thumb.isEmpty()) {
            val generic = MediaData()
            generic.title = "Sticker"
            generic.status = data.stickerEmoji
            if (data.file.relativePath.isEmpty()) {
                if (generic.status.isNotEmpty()) generic.status += ", "
                generic.status += FormatFileSize(data.file.size)
            } else {
                generic.link = data.file.relativePath
            }
            generic.description = NoFileDescription(data.file.skipReason)
            generic.classes = "media_photo"
            return pushGenericMedia(generic)
        }
        val result = StringBuilder(pushDiv("media_wrap clearfix"))
        result.append(pushTag("a", attrs("class" to "sticker_wrap clearfix pull_left", "href" to relativePath(data.file.relativePath))))
        val sizeStyle = "width: " + NumberToString(size!!.width / 2) + "px; height: " + NumberToString(size.height / 2) + "px"
        result.append(pushTag("img", attrs("class" to "sticker", "style" to sizeStyle, "src" to relativePath(thumb), "empty" to "")))
        result.append(popTag())
        result.append(popTag())
        return result.toString()
    }

    private fun pushAnimatedMedia(data: Document, basePath: String): String {
        val size = QSize(data.width, data.height)
        val thumbSize = CalculateThumbSize(kPhotoMaxWidth, kPhotoMaxHeight, kPhotoMinWidth, kPhotoMinHeight, true)(size)
        if (data.thumb.file.relativePath.isEmpty() || data.file.relativePath.isEmpty() || thumbSize.width == 0 || thumbSize.height == 0) {
            val generic = MediaData()
            generic.title = "Animation"
            generic.status = FormatFileSize(data.file.size)
            generic.link = data.file.relativePath
            generic.description = NoFileDescription(data.file.skipReason)
            generic.classes = "media_video"
            return pushGenericMedia(generic)
        }
        val result = StringBuilder(pushDiv("media_wrap clearfix"))
        result.append(pushTag("a", attrs("class" to "animated_wrap clearfix pull_left", "href" to relativePath(data.file.relativePath))))
        result.append(pushDiv("video_play_bg"))
        result.append(pushDiv("gif_play"))
        result.append("GIF")
        result.append(popTag())
        result.append(popTag())
        val sizeStyle = "width: " + NumberToString(thumbSize.width / 2) + "px; height: " + NumberToString(thumbSize.height / 2) + "px"
        result.append(
            pushTag("img", attrs("class" to "animated", "style" to sizeStyle, "src" to relativePath(data.thumb.file.relativePath), "empty" to "")),
        )
        result.append(popTag())
        result.append(popTag())
        return result.toString()
    }

    private fun pushVideoFileMedia(data: Document, basePath: String): String {
        val size = QSize(data.width, data.height)
        val thumbSize = CalculateThumbSize(kPhotoMaxWidth, kPhotoMaxHeight, kPhotoMinWidth, kPhotoMinHeight, true)(size)
        if (data.thumb.file.relativePath.isEmpty() || data.file.relativePath.isEmpty() || thumbSize.width == 0 || thumbSize.height == 0) {
            val generic = MediaData()
            generic.title = "Video file"
            generic.status = FormatDuration(data.duration.toLong())
            if (data.file.relativePath.isEmpty()) {
                generic.status += ", " + FormatFileSize(data.file.size)
            } else {
                generic.link = data.file.relativePath
            }
            generic.description = NoFileDescription(data.file.skipReason)
            generic.classes = "media_video"
            return pushGenericMedia(generic)
        }
        val result = StringBuilder(pushDiv("media_wrap clearfix"))
        result.append(pushTag("a", attrs("class" to "video_file_wrap clearfix pull_left", "href" to relativePath(data.file.relativePath))))
        result.append(pushDiv("video_play_bg"))
        result.append(pushDiv("video_play"))
        result.append(popTag())
        result.append(popTag())
        result.append(pushDiv("video_duration"))
        result.append(FormatDuration(data.duration.toLong()))
        result.append(popTag())
        val sizeStyle = "width: " + NumberToString(thumbSize.width / 2) + "px; height: " + NumberToString(thumbSize.height / 2) + "px"
        result.append(
            pushTag("img", attrs("class" to "video_file", "style" to sizeStyle, "src" to relativePath(data.thumb.file.relativePath), "empty" to "")),
        )
        result.append(popTag())
        result.append(popTag())
        return result.toString()
    }

    private fun pushPhotoMedia(data: Photo, basePath: String): String {
        val (thumb, size) = WriteImageThumb(
            thumbs,
            basePath,
            data.image.file.relativePath,
            CalculateThumbSize(kPhotoMaxWidth, kPhotoMaxHeight, kPhotoMinWidth, kPhotoMinHeight),
        )
        if (thumb.isEmpty()) {
            val generic = MediaData()
            generic.title = "Photo"
            generic.status = data.image.width.toString() + "×" + data.image.height
            if (data.image.file.relativePath.isEmpty()) {
                generic.status += ", " + FormatFileSize(data.image.file.size)
            } else {
                generic.link = data.image.file.relativePath
            }
            generic.description = NoFileDescription(data.image.file.skipReason)
            generic.classes = "media_photo"
            return pushGenericMedia(generic)
        }
        val result = StringBuilder(pushDiv("media_wrap clearfix"))
        result.append(pushTag("a", attrs("class" to "photo_wrap clearfix pull_left", "href" to relativePath(data.image.file.relativePath))))
        val sizeStyle = "width: " + NumberToString(size!!.width / 2) + "px; height: " + NumberToString(size.height / 2) + "px"
        result.append(pushTag("img", attrs("class" to "photo", "style" to sizeStyle, "src" to relativePath(thumb), "empty" to "")))
        result.append(popTag())
        result.append(popTag())
        return result.toString()
    }

    private fun pushRichPhotoMedia(data: Photo?, basePath: String): String = pushPhotoMedia(RichPhotoPresentation(data), basePath)

    private fun pushRichVideoMedia(data: Document?, basePath: String): String {
        val presentation = RichDocumentPresentation(data)
        val thumbPath = presentation.thumb.file.relativePath
        if (thumbPath.isNotEmpty() && thumbs.size(basePath + thumbPath) == null) {
            presentation.thumb.file.relativePath = ""
        }
        return pushVideoFileMedia(presentation, basePath)
    }

    private fun pushRichAudioMedia(data: Document?): String = pushGenericMedia(PrepareAudioMediaData(RichDocumentPresentation(data)))

    private fun pushRichFileMedia(data: Document?): String = pushGenericMedia(PrepareFileMediaData(RichDocumentPresentation(data)))

    private fun pushRichReferenceMedia(data: MediaData, photo: Photo?, basePath: String): String {
        val presentation = RichPhotoPresentation(photo)
        val path = presentation.image.file.relativePath
        data.link = path
        data.thumb = if (path.isEmpty()) {
            ""
        } else {
            WriteImageThumb(thumbs, basePath, path, kEntryUserpicSize * 2, kEntryUserpicSize * 2, "_rich_card_thumb")
        }
        return pushGenericMedia(data)
    }

    private fun pushPoll(data: Poll, internalLinksDomain: String, relativeLinkBase: String): String {
        val result = StringBuilder(pushDiv("media_wrap clearfix"))
        result.append(pushDiv("media_poll"))
        result.append(pushDiv("question bold"))
        result.append(FormatText(data.question, internalLinksDomain, relativeLinkBase))
        result.append(popTag())
        result.append(pushDiv("details"))
        result.append(SerializeHtml(if (data.closed) "Final results" else "Anonymous poll"))
        result.append(popTag())
        fun votes(count: Int): String = when {
            count > 1 -> NumberToString(count) + " votes"
            count > 0 -> NumberToString(count) + " vote"
            else -> "No votes"
        }
        fun details(answer: Poll.Answer): String = when {
            answer.votes == 0 -> ""
            !answer.my -> " <span class=\"details\">" + votes(answer.votes) + "</span>"
            else -> " <span class=\"details\">" + votes(answer.votes) + ", chosen vote</span>"
        }
        for (answer in data.answers) {
            result.append(pushDiv("answer"))
            result.append("- " + FormatText(answer.text, internalLinksDomain, relativeLinkBase) + details(answer))
            result.append(popTag())
        }
        result.append(pushDiv("total details\t"))
        result.append(votes(data.totalVotes))
        result.append(popTag())
        result.append(popTag())
        result.append(popTag())
        return result.toString()
    }

    private fun pushTodoList(data: TodoList, internalLinksDomain: String, relativeLinkBase: String): String {
        val result = StringBuilder(pushDiv("media_wrap clearfix"))
        result.append(pushDiv("media_poll"))
        result.append(pushDiv("question bold"))
        result.append(FormatText(data.title, internalLinksDomain, relativeLinkBase))
        result.append(popTag())
        result.append(pushDiv("details"))
        result.append(SerializeHtml("To-do List"))
        result.append(popTag())
        for (item in data.items) {
            result.append(pushDiv("answer"))
            result.append("- " + FormatText(item.text, internalLinksDomain, relativeLinkBase) + "")
            result.append(popTag())
        }
        result.append(popTag())
        result.append(popTag())
        return result.toString()
    }

    private fun pushGiveaway(peers: PeersMap, data: GiveawayStart): String {
        val result = StringBuilder(pushDiv("media_wrap clearfix"))
        result.append(pushDiv("media_giveaway"))
        result.append(pushDiv("section_title bold"))
        result.append(SerializeHtml(if (data.quantity > 1) "Giveaway Prizes" else "Giveaway Prize"))
        result.append(popTag())
        result.append(pushDiv("section_body"))
        result.append("<b>" + NumberToString(data.quantity) + "</b> " + SerializeHtml(data.additionalPrize))
        result.append(popTag())
        result.append(pushDiv("section_title bold"))
        result.append(SerializeHtml("with"))
        result.append(popTag())
        result.append(pushDiv("section_body"))
        if (data.credits > 0) {
            result.append(
                "<b>" + java.lang.Long.toUnsignedString(data.credits) +
                    SerializeHtml(if (data.credits == 1L) " Star" else " Stars") +
                    "</b> " + SerializeHtml("will be distributed ") +
                    if (data.quantity == 1) {
                        SerializeHtml("to ") + "<b>" + NumberToString(data.quantity) + "</b> " + SerializeHtml("winner.")
                    } else {
                        SerializeHtml("among ") + "<b>" + NumberToString(data.quantity) + "</b> " + SerializeHtml("winners.")
                    },
            )
        } else {
            result.append(
                "<b>" + NumberToString(data.quantity) + "</b> " +
                    SerializeHtml(if (data.quantity > 1) "Telegram Premium Subscriptions" else "Telegram Premium Subscription") +
                    " for <b>" + NumberToString(data.months) + "</b> " + (if (data.months > 1) "months." else "month."),
            )
        }
        result.append(popTag())
        result.append(pushDiv("section_title bold"))
        result.append(SerializeHtml("Participants"))
        result.append(popTag())
        result.append(pushDiv("section_body"))
        val channels = ArrayList<String>()
        var anyChannel = false
        var anyGroup = false
        for (channel in data.channels) {
            val peerId = Peer_.fromChannel(channel)
            val chat = peers.peer(peerId).chat()
            if (chat != null) {
                if (chat.isBroadcast) anyChannel = true else if (chat.isSupergroup) anyGroup = true
            }
            channels.add("<b>" + peers.wrapPeerName(peerId) + "</b>")
        }
        val n = channels.size
        val participants = when {
            data.all && !anyGroup && anyChannel && n == 1 -> "All subscribers of the channel:"
            data.all && !anyGroup && anyChannel && n > 1 -> "All subscribers of the channels:"
            data.all && anyGroup && !anyChannel && n == 1 -> "All members of the group:"
            data.all && anyGroup && !anyChannel && n > 1 -> "All members of the groups:"
            data.all && anyGroup && anyChannel && n == 1 -> "All members of the group:"
            data.all && anyGroup && anyChannel && n > 1 -> "All members of the groups and channels:"
            !data.all && !anyGroup && anyChannel && n == 1 -> "All users who joined the channel below after this date:"
            !data.all && !anyGroup && anyChannel && n > 1 -> "All users who joined the channels below after this date:"
            !data.all && anyGroup && !anyChannel && n == 1 -> "All users who joined the group below after this date:"
            !data.all && anyGroup && !anyChannel && n > 1 -> "All users who joined the groups below after this date:"
            !data.all && anyGroup && anyChannel && n == 1 -> "All users who joined the group below after this date:"
            !data.all && anyGroup && anyChannel && n > 1 -> "All users who joined the groups and channels below after this date:"
            else -> ""
        }
        // tdesktop drops the channel names here (`append(...) + join`).
        result.append(SerializeHtml(participants))
        result.append(popTag())
        val countries = data.countries.map { Countries.flagEmojiByISO2(it) + ' ' + Countries.countryNameByISO2(it) }
        if (countries.isNotEmpty()) {
            var united = countries.first()
            for (i in 1 until countries.size) {
                united = if (i + 1 == countries.size) "$united and ${countries[i]}" else "$united, ${countries[i]}"
            }
            result.append(pushDiv("section_body"))
            result.append(SerializeHtml("from $united"))
            result.append(popTag())
        }
        result.append(pushDiv("section_title bold"))
        result.append(SerializeHtml("Winners Selection Date"))
        result.append(popTag())
        result.append(pushDiv("section_body"))
        result.append(FormatDateTime(data.untilDate))
        result.append(popTag())
        result.append(popTag())
        result.append(popTag())
        return result.toString()
    }

    private fun pushGiveaway(peers: PeersMap, data: GiveawayResults, wrapMessageLink: (Int, String) -> String): String {
        val result = StringBuilder(pushDiv("media_wrap clearfix"))
        result.append(pushDiv("media_giveaway"))
        result.append(pushDiv("section_title bold"))
        result.append(SerializeHtml(if (data.winnersCount > 1) "Winners Selected!" else "Winner Selected!"))
        result.append(popTag())
        result.append(pushDiv("section_body"))
        result.append(
            "<b>" + NumberToString(data.winnersCount) + "</b> " + SerializeHtml(if (data.winnersCount > 1) "winners" else "winner") +
                " of the " + wrapMessageLink(data.launchId, "Giveaway") + " was randomly selected by Telegram.",
        )
        result.append(popTag())
        result.append(pushDiv("section_title bold"))
        result.append(SerializeHtml(if (data.winnersCount > 1) "Winners" else "Winner"))
        result.append(popTag())
        result.append(pushDiv("section_body"))
        val winners = data.winners.map { "<b>" + peers.wrapPeerName(it) + "</b>" }
        val size = data.winners.size
        val andMore = if (data.winnersCount > size) {
            SerializeHtml(" and ") + NumberToString(data.winnersCount - size) + SerializeHtml(" more!")
        } else ""
        result.append(winners.joinToString(", ") + andMore)
        result.append(popTag())
        result.append(pushDiv("section_body"))
        val singleStar = data.credits == 1L
        val prize = when {
            data.credits != 0L && data.winnersCount == 1 -> SerializeHtml("The winner received ") + "<b>" +
                java.lang.Long.toUnsignedString(data.credits) + "</b>" + SerializeHtml(if (singleStar) " Star." else " Stars.")
            data.credits != 0L && data.winnersCount > 1 -> SerializeHtml("All winners received ") + "<b>" +
                java.lang.Long.toUnsignedString(data.credits) + "</b>" +
                SerializeHtml(if (singleStar) " Star in total." else " Stars in total.")
            data.unclaimedCount != 0 -> SerializeHtml("Some winners couldn't be selected.")
            data.winnersCount == 1 -> SerializeHtml("The winner received their gift link in a private message.")
            data.winnersCount > 1 -> SerializeHtml("All winners received gift links in private messages.")
            else -> ""
        }
        result.append(prize)
        result.append(popTag())
        result.append(popTag())
        result.append(popTag())
        return result.toString()
    }

    private fun prepareMediaData(message: Message, basePath: String, peers: PeersMap, internalLinksDomain: String): MediaData {
        var result = MediaData()
        val call = message.action.content as? ActionPhoneCall
        if (call != null) {
            result.classes = "media_call"
            result.title = peers.peer(if (message.out) message.peerId else message.selfId).name()
            val state = call.state
            result.status = when {
                state == ActionPhoneCall.State.Invitation -> "Invitation"
                state == ActionPhoneCall.State.Active -> "Ongoing"
                message.out -> if (state == ActionPhoneCall.State.Missed) "Cancelled" else "Outgoing"
                state == ActionPhoneCall.State.Missed -> "Missed"
                state == ActionPhoneCall.State.Busy -> "Declined"
                else -> "Incoming"
            }
            if (call.duration > 0) {
                result.classes += " success"
                result.status += " (" + NumberToString(call.duration) + " seconds)"
            }
            return result
        }
        when (val data = message.media.content) {
            is Photo -> if (message.media.ttl != 0) {
                result.title = "Self-destructing photo"
                result.status = if (data.id != 0L) "Please view it on your mobile" else "Expired"
                result.classes = "media_photo"
            }
            is Document -> if (message.media.ttl != 0) {
                result.title = "Self-destructing video"
                result.status = if (data.id != 0L) "Please view it on your mobile" else "Expired"
                result.classes = "media_video"
            } else {
                val hasFile = data.file.relativePath.isNotEmpty()
                result.link = data.file.relativePath
                result.description = NoFileDescription(data.file.skipReason)
                if (data.isSticker) {
                    // At least try to pushStickerMedia.
                } else if (data.isVideoMessage) {
                    result.title = "Video message"
                    result.status = FormatDuration(data.duration.toLong())
                    if (!hasFile) result.status += ", " + FormatFileSize(data.file.size)
                    result.thumb = data.thumb.file.relativePath
                    result.classes = "media_video"
                } else if (data.isVoiceMessage) {
                    result.title = "Voice message"
                    result.status = FormatDuration(data.duration.toLong())
                    if (!hasFile) result.status += ", " + FormatFileSize(data.file.size)
                    result.classes = "media_voice_message"
                } else if (data.isAnimated) {
                    // At least try to pushAnimatedMedia.
                } else if (data.isVideoFile) {
                    // At least try to pushVideoFileMedia.
                } else if (data.isAudioFile) {
                    result = PrepareAudioMediaData(data)
                } else {
                    result = PrepareFileMediaData(data)
                }
            }
            is SharedContact -> {
                result.title = data.info.firstName + ' ' + data.info.lastName
                result.classes = "media_contact"
                result.status = FormatPhoneNumber(data.info.phoneNumber)
                if (data.vcard.content.isNotEmpty()) {
                    result.status += " - vCard"
                    result.link = data.vcard.relativePath
                }
            }
            is GeoPoint -> {
                if (message.media.ttl != 0) {
                    result.classes = "media_live_location"
                    result.title = "Live location"
                    result.status = ""
                } else {
                    result.classes = "media_location"
                    result.title = "Location"
                }
                if (data.valid) {
                    val latitude = NumberToString(data.latitude)
                    val longitude = NumberToString(data.longitude)
                    val coords = "$latitude,$longitude"
                    result.status = "$latitude, $longitude"
                    result.link = "https://maps.google.com/maps?q=$coords&ll=$coords&z=16"
                }
            }
            is Venue -> {
                result.classes = "media_venue"
                result.title = data.title
                result.description = data.address
                if (data.point.valid) {
                    val coords = NumberToString(data.point.latitude) + ',' + NumberToString(data.point.longitude)
                    result.link = "https://maps.google.com/maps?q=$coords&ll=$coords&z=16"
                }
            }
            is Game -> {
                result.classes = "media_game"
                result.title = data.title
                result.description = data.description
                if (data.botId != 0L && data.shortName.isNotEmpty()) {
                    val bot = peers.user(data.botId)
                    if (bot.isBot && bot.username.isNotEmpty()) {
                        val link = internalLinksDomain + bot.username + "?game=" + data.shortName
                        result.link = link
                        result.status = link
                    }
                }
            }
            is Invoice -> {
                result.classes = "media_invoice"
                result.title = data.title
                result.description = data.description
                result.status = FormatMoneyAmount(data.amount, data.currency)
            }
            is Poll, is TodoList, is GiveawayStart, is GiveawayResults -> Unit
            is PaidMedia -> {
                result.classes = "media_invoice"
                result.status = FormatMoneyAmount(data.stars, "XTR")
            }
            UnsupportedMedia -> throw IllegalStateException("Unsupported message.")
            null -> Unit
        }
        return result
    }

    private fun forwardedNeedsWrap(message: Message, previous: MessageInfo?): Boolean {
        check(message.forwarded)
        return when {
            messageNeedsWrap(message, previous) -> true
            message.forwardedFromId == 0L || message.forwardedFromId != previous!!.forwardedFromId -> true
            !Peer_.isUser(message.forwardedFromId) -> true
            Math.abs(message.forwardedDate - previous.forwardedDate) > kJoinWithinSeconds -> true
            else -> false
        }
    }

    fun close(): Result {
        if (!closed) {
            closed = true
            if (!file.empty()) {
                val block = StringBuilder()
                while (!context.empty()) block.append(context.popTag())
                val result = file.writeBlock(block.toString())
                file.close()
                return result
            }
        }
        file.close()
        return Result.Success()
    }

    fun relativePath(path: String): String = base + path

    private fun composeStart(): String {
        val result = StringBuilder("<!DOCTYPE html>" + context.pushTag("html"))
        result.append(pushTag("head"))
        result.append(pushTag("meta", attrs("charset" to "utf-8", "empty" to "")))
        result.append(pushTag("title", attrs("inline" to "")))
        result.append("Exported Data")
        result.append(popTag())
        result.append(
            context.pushTag("meta", attrs("name" to "viewport", "content" to "width=device-width, initial-scale=1.0", "empty" to "")),
        )
        result.append(context.pushTag("link", attrs("href" to base + "css/style.css", "rel" to "stylesheet", "empty" to "")))
        result.append(context.pushTag("script", attrs("src" to base + "js/script.js", "type" to "text/javascript")))
        result.append(context.popTag())
        result.append(popTag())
        result.append(pushTag("body", attrs("onload" to "CheckLocation();")))
        result.append(pushDiv("page_wrap"))
        return result.toString()
    }

    companion object {
        /** QString::trimmed. */
        fun QTrimmed(s: String): String {
            fun sp(c: Char) = c == ' ' || c in '\t'..'\r' || c == '\u0085' || c == ' ' || Character.isSpaceChar(c)
            var b = 0
            var e = s.length
            while (b < e && sp(s[b])) b++
            while (e > b && sp(s[e - 1])) e--
            return s.substring(b, e)
        }
    }
}

class HtmlWriter(private val thumbs: ImageThumbs) : AbstractWriter {
    private enum class DialogsMode { None, Chats, Left }

    private class SavedSection(val priority: Int, val label: String, val type: String, val count: Int, val path: String)

    private var settings = Settings()
    private var environment = Environment()
    private var stats: Stats? = null
    private val savedSections = ArrayList<SavedSection>()
    private var summary: Wrap? = null
    private var summaryNeedDivider = false
    private var haveSections = false
    private var dialogsRelativePath = ""
    private var dialog = DialogInfo()
    private var dialogsMode = DialogsMode.None
    private var messagesCount = 0
    private var lastMessageInfo: MessageInfo? = null
    private var dateMessageId = 0
    private var chats: Wrap? = null
    private var chat: Wrap? = null
    private val lastMessageIdsPerFile = ArrayList<Int>()
    private var chatFileEmpty = false

    override fun format() = Format.Html

    override fun start(settings: Settings, environment: Environment, stats: Stats?): Result {
        check(settings.path.endsWith('/'))
        this.settings = settings.copy()
        this.environment = environment
        this.stats = stats
        val files = listOf(
            "css/style.css",
            "images/back.png",
            "images/media_call.png",
            "images/media_contact.png",
            "images/media_file.png",
            "images/media_game.png",
            "images/media_location.png",
            "images/media_music.png",
            "images/media_photo.png",
            "images/media_shop.png",
            "images/media_video.png",
            "images/media_voice.png",
            "images/section_calls.png",
            "images/section_chats.png",
            "images/section_contacts.png",
            "images/section_frequent.png",
            "images/section_music.png",
            "images/section_other.png",
            "images/section_photos.png",
            "images/section_sessions.png",
            "images/section_stories.png",
            "images/section_web.png",
            "js/script.js",
        )
        for (name in files) {
            val result = copyResource(name)
            if (!result.isSuccess()) return result
            val png = name.indexOf(".png")
            if (png > 0) {
                val x2 = name.substring(0, png) + "@2x.png"
                val r2 = copyResource(x2)
                if (!r2.isSuccess()) return r2
            }
        }
        if (this.settings.onlySinglePeer()) return Result.Success()
        summary = fileWithRelativePath(mainFileRelativePath())
        val block = summary!!.pushHeader("Exported Data") + summary!!.pushDiv("page_body")
        return summary!!.writeBlock(block)
    }

    /** copyFile(":/export/" + name, name): the files are bundled as resources. */
    private fun copyResource(name: String): Result {
        val bytes = HtmlWriter::class.java.getResourceAsStream("/export/$name")?.use { it.readBytes() }
            ?: return Result(Result.Type.FatalError, ":/export/$name")
        val file = File(pathWithRelativePath(name), stats)
        val result = file.writeBlock(bytes)
        file.close()
        return result
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

    override fun writeDialogsStart(data: DialogsInfo): Result {
        check(chats == null)
        if (data.chats.isEmpty() && data.left.isEmpty()) return Result.Success()
        if (settings.onlySinglePeer()) return Result.Success()
        dialogsRelativePath = "lists/chats.html"
        chats = fileWithRelativePath(dialogsRelativePath)
        val block = chats!!.pushHeader("Chats", mainFileRelativePath()) + chats!!.pushDiv("page_body list_page")
        val result = chats!!.writeBlock(block)
        if (!result.isSuccess()) return result
        pushSection(0, "Chats", "chats", data.chats.size + data.left.size, "lists/chats.html")
        return writeSections()
    }

    override fun writeDialogStart(data: DialogInfo): Result {
        check(chat == null)
        chat = fileWithRelativePath(data.relativePath + messagesFile(0))
        chatFileEmpty = true
        messagesCount = 0
        dateMessageId = 0
        lastMessageInfo = null
        lastMessageIdsPerFile.clear()
        dialog = data
        return Result.Success()
    }

    override fun writeDialogSlice(data: MessagesSlice): Result {
        val chat = checkNotNull(chat)
        check(data.list.isNotEmpty())
        val peers = PeersMap(data.peers)
        var oldIndex = if (messagesCount > 0) (messagesCount - 1) / kMessagesInFile else 0
        var previous = lastMessageInfo
        var saved: MessageInfo? = null
        var block = StringBuilder()
        var current = chat
        for (message in data.list) {
            if (SkipMessageByDate(message, settings)) continue
            val newIndex = messagesCount / kMessagesInFile
            if (oldIndex != newIndex) {
                val result = current.writeBlock(block.toString())
                if (!result.isSuccess()) return result
                val next = switchToNextChatFile(newIndex)
                if (!next.isSuccess()) return next
                current = this.chat!!
                check(saved != null || lastMessageInfo != null)
                lastMessageIdsPerFile.add(saved?.id ?: lastMessageInfo!!.id)
                block = StringBuilder()
                lastMessageInfo = null
                previous = null
                saved = null
                oldIndex = newIndex
            }
            if (chatFileEmpty) {
                val result = writeDialogOpening(oldIndex)
                if (!result.isSuccess()) return result
                chatFileEmpty = false
            }
            val date = message.date
            if (DisplayDate(date, previous?.date ?: 0)) {
                block.append(current.pushServiceMessage(--dateMessageId, dialog, settings.path, FormatDateText(date)))
            }
            val (info, content) = current.pushMessage(
                message,
                previous,
                dialog,
                settings.path,
                peers,
                environment.internalLinksDomain,
            ) { id, text -> wrapMessageLink(id, text) }
            block.append(content)
            ++messagesCount
            saved = info
            previous = saved
        }
        if (saved != null) lastMessageInfo = saved.copy()
        return if (block.isEmpty()) Result.Success() else current.writeBlock(block.toString())
    }

    private fun writeEmptySinglePeer(): Result {
        val chat = checkNotNull(chat)
        if (!settings.onlySinglePeer() || messagesCount != 0) return Result.Success()
        check(chatFileEmpty)
        val result = writeDialogOpening(0)
        if (!result.isSuccess()) return result
        return chat.writeBlock(chat.pushServiceMessage(--dateMessageId, dialog, settings.path, "No exported messages"))
    }

    override fun writeDialogEnd(): Result {
        check(settings.onlySinglePeer() || chats != null)
        checkNotNull(chat)
        val empty = writeEmptySinglePeer()
        if (!empty.isSuccess()) return empty
        val closed = chat!!.close()
        chat = null
        if (!closed.isSuccess()) return closed
        if (settings.onlySinglePeer()) return Result.Success()

        val T = DialogInfo.Type.Self.javaClass
        @Suppress("UNUSED_VARIABLE") val unused = T
        val typeString = when (dialog.type) {
            DialogInfo.Type.Unknown -> "unknown"
            DialogInfo.Type.Self, DialogInfo.Type.Replies, DialogInfo.Type.VerifyCodes, DialogInfo.Type.Personal -> "private"
            DialogInfo.Type.Bot -> "bot"
            DialogInfo.Type.PrivateGroup, DialogInfo.Type.PrivateSupergroup, DialogInfo.Type.PublicSupergroup -> "group"
            DialogInfo.Type.PrivateChannel, DialogInfo.Type.PublicChannel -> "channel"
        }
        val deletedString = when (dialog.type) {
            DialogInfo.Type.Unknown, DialogInfo.Type.Self, DialogInfo.Type.Replies, DialogInfo.Type.VerifyCodes,
            DialogInfo.Type.Personal, DialogInfo.Type.Bot,
            -> "Deleted Account"
            DialogInfo.Type.PrivateGroup, DialogInfo.Type.PrivateSupergroup, DialogInfo.Type.PublicSupergroup -> "Deleted Group"
            DialogInfo.Type.PrivateChannel, DialogInfo.Type.PublicChannel -> "Deleted Channel"
        }
        val nameString = when (dialog.type) {
            DialogInfo.Type.Self -> "Saved messages"
            DialogInfo.Type.Replies -> "Replies"
            DialogInfo.Type.VerifyCodes -> "Verification Codes"
            else -> dialog.name
        }
        val lastNameString = if (dialog.type != DialogInfo.Type.Personal && dialog.type != DialogInfo.Type.Bot) "" else dialog.lastName
        fun countString(count: Int, outgoing: Boolean): String = when (count) {
            1 -> if (outgoing) "1 outgoing message" else "1 message"
            0 -> if (outgoing) "No outgoing messages" else "No messages"
            else -> NumberToString(count) + if (outgoing) " outgoing messages" else " messages"
        }
        val userpic = UserpicData()
        userpic.colorIndex = if (dialog.type == DialogInfo.Type.Self || dialog.type == DialogInfo.Type.Replies ||
            dialog.type == DialogInfo.Type.VerifyCodes
        ) kSavedMessagesColorIndex else PeerColorIndexOfPeer(dialog.peerId)
        userpic.pixelSize = kEntryUserpicSize
        userpic.firstName = nameString
        userpic.lastName = lastNameString
        val result = validateDialogsMode(dialog.isLeftChannel)
        if (!result.isSuccess()) return result
        return chats!!.writeBlock(
            chats!!.pushListEntry(
                userpic,
                ComposeName(userpic, deletedString),
                countString(messagesCount, dialog.onlyMyMessages),
                typeString,
                if (messagesCount > 0) dialog.relativePath + "messages.html" else "",
            ),
        )
    }

    private fun validateDialogsMode(isLeftChannel: Boolean): Result {
        val mode = if (isLeftChannel) DialogsMode.Left else DialogsMode.Chats
        if (dialogsMode == mode) return Result.Success()
        if (dialogsMode != DialogsMode.None) {
            val result = chats!!.writeBlock(chats!!.popTag())
            if (!result.isSuccess()) return result
        }
        dialogsMode = mode
        val block = chats!!.pushAbout(if (isLeftChannel) environment.aboutLeftChats else environment.aboutChats) +
            chats!!.pushDiv("entry_list")
        return chats!!.writeBlock(block)
    }

    override fun writeDialogsEnd(): Result {
        val c = chats ?: return Result.Success()
        chats = null
        return c.close()
    }

    private fun writeDialogOpening(index: Int): Result {
        val chat = this.chat!!
        val name = if (dialog.name.isEmpty() && dialog.lastName.isEmpty()) "Deleted Account" else dialog.name + ' ' + dialog.lastName
        val block = StringBuilder(chat.pushHeader(name, if (settings.onlySinglePeer()) "" else dialogsRelativePath))
        block.append(chat.pushDiv("page_body chat_page"))
        block.append(chat.pushDiv("history"))
        if (index > 0) {
            val previousPath = messagesFile(index - 1)
            block.append(chat.pushTag("a", attrs("class" to "pagination block_link", "href" to previousPath)))
            block.append("Previous messages")
            block.append(chat.popTag())
        }
        return chat.writeBlock(block.toString())
    }

    private fun pushSection(priority: Int, label: String, type: String, count: Int, path: String) {
        savedSections.add(SavedSection(priority, label, type, count, path))
    }

    private fun writeSections(): Result {
        val summary = checkNotNull(summary)
        if (savedSections.isEmpty()) return Result.Success()
        if (!haveSections) {
            val result = summary.writeBlock(summary.pushDiv(if (summaryNeedDivider) "sections with_divider" else "sections"))
            if (!result.isSuccess()) return result
            haveSections = true
            summaryNeedDivider = false
        }
        val block = StringBuilder()
        val sorted = savedSections.sortedBy { it.priority }
        savedSections.clear()
        for (section in sorted) {
            block.append(summary.pushSection(section.label, section.type, section.count, summary.relativePath(section.path)))
        }
        return summary.writeBlock(block.toString())
    }

    private fun wrapMessageLink(messageId: Int, text: String): String {
        val index = lastMessageIdsPerFile.indexOfFirst { messageId <= it }
        return if (index < 0) {
            "<a href=\"#go_to_message" + NumberToString(messageId) + "\" onclick=\"return GoToMessage(" +
                NumberToString(messageId) + ")\">" + text + "</a>"
        } else {
            "<a href=\"" + messagesFile(index) + "#go_to_message" + NumberToString(messageId) + "\">" + text + "</a>"
        }
    }

    private fun switchToNextChatFile(index: Int): Result {
        val chat = checkNotNull(chat)
        val nextPath = messagesFile(index)
        val next = chat.pushTag("a", attrs("class" to "pagination block_link", "href" to nextPath)) + "Next messages" + chat.popTag()
        val result = chat.writeBlock(next)
        if (!result.isSuccess()) return result
        val end = chat.close()
        if (!end.isSuccess()) return end
        this.chat = fileWithRelativePath(dialog.relativePath + nextPath)
        chatFileEmpty = true
        return Result.Success()
    }

    override fun finish(): Result {
        check(settings.onlySinglePeer() || summary != null)
        if (settings.onlySinglePeer()) return Result.Success()
        val sections = writeSections()
        if (!sections.isSuccess()) return sections
        val summary = summary!!
        val block = StringBuilder()
        if (haveSections) {
            block.append(summary.popTag())
            summaryNeedDivider = true
            haveSections = false
        }
        block.append(summary.pushAbout(environment.aboutTelegram, summaryNeedDivider))
        val result = summary.writeBlock(block.toString())
        if (!result.isSuccess()) return result
        return summary.close()
    }

    override fun mainFilePath(): String =
        pathWithRelativePath(if (settings.onlySinglePeer()) messagesFile(0) else mainFileRelativePath())

    private fun mainFileRelativePath(): String = "export_results.html"
    private fun pathWithRelativePath(path: String): String = settings.path + path
    private fun messagesFile(index: Int): String = "messages" + (if (index > 0) (index + 1).toString() else "") + ".html"
    private fun fileWithRelativePath(path: String): Wrap = Wrap(pathWithRelativePath(path), settings.path, stats, thumbs)
}
