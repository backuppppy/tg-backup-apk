// Port of tdesktop Telegram/SourceFiles/export/data/export_data_types.h
@file:Suppress("PropertyName")

package org.tgbackup.export.data

import org.tgbackup.mtproto.tl.MTPDinputPeerEmpty
import org.tgbackup.mtproto.tl.MTPDinputUserEmpty
import org.tgbackup.mtproto.tl.MTPInputFileLocation
import org.tgbackup.mtproto.tl.MTPInputPeer
import org.tgbackup.mtproto.tl.MTPInputUser

typealias TimeId = Int
typealias BareId = Long

/**
 * PeerId as in tdesktop data_peer_id.h: the bare id with the peer type
 * (0 user, 1 chat, 2 channel) in bits 48..55.
 */
typealias PeerId = Long

object Peer_ {
    const val kChatTypeMask: Long = 0xFFFFFFFFFFFFL
    fun fromUser(bare: Long): PeerId = bare
    fun fromChat(bare: Long): PeerId = bare or (1L shl 48)
    fun fromChannel(bare: Long): PeerId = bare or (2L shl 48)
    fun type(peer: PeerId): Long = (peer ushr 48) and 0xFF
    fun isUser(peer: PeerId) = type(peer) == 0L
    fun isChat(peer: PeerId) = type(peer) == 1L
    fun isChannel(peer: PeerId) = type(peer) == 2L
    fun toUser(peer: PeerId): Long = if (isUser(peer)) peer and kChatTypeMask else 0
    fun toChat(peer: PeerId): Long = if (isChat(peer)) peer and kChatTypeMask else 0
    fun toChannel(peer: PeerId): Long = if (isChannel(peer)) peer and kChatTypeMask else 0
}

class TextPart(
    var type: Type = Type.Text,
    var text: String = "",
    var additional: String = "",
) {
    enum class Type {
        Text, Unknown, Mention, Hashtag, BotCommand, Url, Email, Bold, Italic, Code, Pre, TextUrl,
        MentionName, Phone, Cashtag, Underline, Strike, Blockquote, BankCard, Spoiler, CustomEmoji,
    }

    companion object {
        fun UnavailableEmoji(): String = "(unavailable)"
    }
}

enum class InlineButtonPeerType { SameBotPM, PM, Chat, Megagroup, Broadcast, BotPM }

class InlineButtonAction {
    enum class Type {
        Url, Auth, WebView, Callback, CallbackWithPassword, Game, Buy, SwitchInline, SwitchInlineSame,
        UserProfile, CopyText, Disabled,
    }

    var url: String = ""
    var forwardText: String? = null
    var callbackData: ByteArray = ByteArray(0)
    var query: String = ""
    var peerTypes: MutableList<InlineButtonPeerType>? = null
    var copyText: String = ""
    var userId: Long = 0
    var buttonId: Int = 0
    var type: Type = Type.Url
    var requiresPassword = false
    var samePeer = false
}

enum class RichButtonStyle { Default, Primary, Success, Danger, Link }
enum class RichButtonAlignment { Stretch, Left, Center, Right }

class RichButtonPayload(val action: InlineButtonAction, val style: RichButtonStyle?)

class RichText {
    enum class Type {
        Empty, Plain, Concat, Bold, Italic, Underline, Strike, Fixed, Url, Email, Phone, Subscript, Superscript,
        Marked, Anchor, Math, CustomEmoji, Spoiler, Mention, Hashtag, BotCommand, Cashtag, AutoUrl, AutoEmail,
        AutoPhone, BankCard, MentionName, FormattedDate, InlineImage, Diff, Button,
    }

    var text: String = ""
    var data: String = ""
    var customEmojiData: String = ""
    var children: MutableList<RichText> = ArrayList()
    var oldChildren: MutableList<RichText> = ArrayList()
    var button: RichButtonPayload? = null
    var id: Long = 0
    var date: TimeId = 0
    var width: Int = 0
    var height: Int = 0
    var type: Type = Type.Empty
    var relative = false
    var shortTime = false
    var longTime = false
    var shortDate = false
    var longDate = false
    var dayOfWeek = false
    var unsupported = false
}

class RichCaption(var text: RichText = RichText(), var credit: RichText = RichText())

enum class RichTaskState { None, Unchecked, Checked }
enum class RichListKind { Bullet, Ordered }
enum class RichListItemContent { Text, Blocks }
enum class RichQuoteContent { Text, Blocks }

class RichOrderedList {
    var type: String? = null
    var start: Int? = null
    var reversed = false
}

class RichListItem {
    var text: RichText? = null
    var blocks: MutableList<RichBlock> = ArrayList()
    var num: String? = null
    var type: String? = null
    var value: Int? = null
    var taskState = RichTaskState.None
    var content = RichListItemContent.Text
}

enum class RichTableAlignment { Left, Center, Right }
enum class RichTableVerticalAlignment { Top, Middle, Bottom }

class RichTableCell {
    var text: RichText? = null
    var colspan: Int? = null
    var rowspan: Int? = null
    var alignment = RichTableAlignment.Left
    var verticalAlignment = RichTableVerticalAlignment.Top
    var header = false
}

class RichTableRow {
    var cells: MutableList<RichTableCell> = ArrayList()
}

class RichRelatedArticle {
    var url: String = ""
    var title: String? = null
    var description: String? = null
    var author: String? = null
    var photoId: Long? = null
    var publishedDate: TimeId? = null
    var webpageId: Long = 0
}

class RichChannel {
    enum class Source { ChatEmpty, Chat, ChatForbidden, Channel, ChannelForbidden, Community, CommunityForbidden }

    var title: String? = null
    var username: String? = null
    var accessHash: Long? = null
    var id: Long = 0
    var source = Source.ChatEmpty
    var broadcast = false
    var megagroup = false
    var monoforum = false
}

class RichMapPoint {
    enum class Source { GeoPointEmpty, GeoPoint, InputGeoPointEmpty, InputGeoPoint }

    var accessHash: Long? = null
    var accuracyRadius: Int? = null
    var latitude = 0.0
    var longitude = 0.0
    var source = Source.GeoPointEmpty
}

class RichBlock {
    enum class Kind {
        Unsupported, Heading, Paragraph, Footer, Thinking, AuthorDate, Code, Divider, Anchor, List, Quote, Photo,
        Video, Cover, Embed, EmbedPost, Collage, Slideshow, Channel, Audio, File, Math, Table, Details,
        RelatedArticles, Map, InputMap, ButtonRow, Unknown,
    }

    var text = RichText()
    var quoteCaption = RichText()
    var caption = RichCaption()
    var orderedList = RichOrderedList()
    var channel = RichChannel()
    var mapPoint = RichMapPoint()
    var language: String = ""
    var formula: String = ""
    var name: String = ""
    var url: String = ""
    var author: String = ""
    var optionalUrl: String? = null
    var html: String? = null
    var blocks: MutableList<RichBlock> = ArrayList()
    var listItems: MutableList<RichListItem> = ArrayList()
    var tableRows: MutableList<RichTableRow> = ArrayList()
    var relatedArticles: MutableList<RichRelatedArticle> = ArrayList()
    var buttons: MutableList<RichText> = ArrayList()
    var optionalWebpageId: Long? = null
    var posterPhotoId: Long? = null
    var width: Int? = null
    var height: Int? = null
    var photoId: Long = 0
    var documentId: Long = 0
    var webpageId: Long = 0
    var authorPhotoId: Long = 0
    var date: TimeId = 0
    var headingLevel = 0
    var zoom = 0
    var mapWidth = 0
    var mapHeight = 0
    var kind = Kind.Unknown
    var listKind = RichListKind.Bullet
    var quoteContent = RichQuoteContent.Text
    var buttonAlignment = RichButtonAlignment.Stretch
    var unsupported = false
    var fullWidth = false
    var allowScrolling = false
    var autoplay = false
    var loop = false
    var spoiler = false
    var open = false
    var bordered = false
    var striped = false
    var compact = false
    var pullquote = false
}

class UserpicsInfo(var count: Int = 0)
class StoriesInfo(var count: Int = 0)
class ProfileMusicInfo(var count: Int = 0)

class FileLocation(var dcId: Int = 0, var data: MTPInputFileLocation? = null) {
    val isValid: Boolean get() = dcId != 0
    fun copy() = FileLocation(dcId, data)
}

class File {
    enum class SkipReason { None, Unavailable, FileType, FileSize, DateLimits }

    var location = FileLocation()
    var size: Long = 0
    var content: ByteArray = ByteArray(0)
    var suggestedPath: String = ""
    var relativePath: String = ""
    var skipReason = SkipReason.None

    fun assign(other: File) {
        location = other.location.copy()
        size = other.size
        content = other.content
        suggestedPath = other.suggestedPath
        relativePath = other.relativePath
        skipReason = other.skipReason
    }

    fun copy(): File = File().also { it.assign(this) }
}

class Image {
    var width = 0
    var height = 0
    var file = File()
}

class ContactInfo {
    var userId: Long = 0
    var firstName: String = ""
    var lastName: String = ""
    var phoneNumber: String = ""
    var date: TimeId = 0
    var colorIndex: Int = 0

    fun name(): String =
        if (firstName.isEmpty()) {
            if (lastName.isEmpty()) "" else lastName
        } else {
            if (lastName.isEmpty()) firstName else "$firstName $lastName"
        }
}

class Photo : MediaContent {
    var id: Long = 0
    var date: TimeId = 0
    var spoilered = false
    var image = Image()
}

class Document : MediaContent {
    var id: Long = 0
    var date: TimeId = 0
    var file = File()
    var thumb = Image()
    var name: String = ""
    var mime: String = ""
    var width = 0
    var height = 0
    var stickerEmoji: String = ""
    var songPerformer: String = ""
    var songTitle: String = ""
    var duration = 0
    var isSticker = false
    var isAnimated = false
    var isVideoMessage = false
    var isVoiceMessage = false
    var isVideoFile = false
    var isAudioFile = false
    var spoilered = false
}

class RichMessage {
    var blocks: MutableList<RichBlock> = ArrayList()
    val photos = java.util.TreeMap<ULong, Photo>()
    val documents = java.util.TreeMap<ULong, Document>()
    var rtl = false
    var part = false
}

class SharedContact : MediaContent {
    var info = ContactInfo()
    var vcard = File()
}

class GeoPoint : MediaContent {
    var latitude = 0.0
    var longitude = 0.0
    var valid = false
}

class Venue : MediaContent {
    var point = GeoPoint()
    var title: String = ""
    var address: String = ""
}

class Game : MediaContent {
    var id: Long = 0
    var shortName: String = ""
    var title: String = ""
    var description: String = ""
    var botId: Long = 0
}

class Invoice : MediaContent {
    var title: String = ""
    var description: String = ""
    var currency: String = ""
    var amount: Long = 0
    var receiptMsgId: Int = 0
}

class PaidMedia : MediaContent {
    var stars: Long = 0
    var extended: MutableList<Media?> = ArrayList()
}

class Poll : MediaContent {
    class Answer {
        var text: MutableList<TextPart> = ArrayList()
        var option: ByteArray = ByteArray(0)
        var votes = 0
        var my = false
    }

    var id: Long = 0
    var question: MutableList<TextPart> = ArrayList()
    var answers: MutableList<Answer> = ArrayList()
    var totalVotes = 0
    var closed = false
}

class TodoListItem {
    var text: MutableList<TextPart> = ArrayList()
    var id = 0
}

class TodoList : MediaContent {
    var othersCanAppend = false
    var othersCanComplete = false
    var title: MutableList<TextPart> = ArrayList()
    var items: MutableList<TodoListItem> = ArrayList()
}

class GiveawayStart : MediaContent {
    var countries: MutableList<String> = ArrayList()
    var channels: MutableList<Long> = ArrayList()
    var additionalPrize: String = ""
    var untilDate: TimeId = 0
    var credits: Long = 0
    var quantity = 0
    var months = 0
    var all = false
}

class GiveawayResults : MediaContent {
    var channel: Long = 0
    var winners: MutableList<PeerId> = ArrayList()
    var additionalPrize: String = ""
    var untilDate: TimeId = 0
    var launchId = 0
    var additionalPeersCount = 0
    var winnersCount = 0
    var unclaimedCount = 0
    var months = 0
    var credits: Long = 0
    var refunded = false
    var all = false
}

object UnsupportedMedia : MediaContent

/** One of the alternatives of tdesktop's Media::content variant (null = v::null_t). */
sealed interface MediaContent

class UserpicsSlice(var list: MutableList<Photo> = ArrayList())

class User {
    fun id(): PeerId = Peer_.fromUser(bareId)

    var bareId: BareId = 0
    var info = ContactInfo()
    var username: String = ""
    var colorIndex: Int = 0
    var isBot = false
    var isSelf = false
    var isReplies = false
    var isVerifyCodes = false
    var input: MTPInputUser = MTPDinputUserEmpty

    fun name(): String = info.name()
}

class Chat {
    fun id(): PeerId = if (isBroadcast || isSupergroup) Peer_.fromChannel(bareId) else Peer_.fromChat(bareId)

    var bareId: BareId = 0
    var migratedToChannelId: Long = 0
    var title: String = ""
    var username: String = ""
    var colorIndex: Int = 0
    var isMonoforum = false
    var isBroadcast = false
    var isSupergroup = false
    var isMonoforumAdmin = false
    var hasMonoforumAdminRights = false
    var isMonoforumOfPublicBroadcast = false
    var monoforumLinkId: BareId = 0
    var input: MTPInputPeer = MTPDinputPeerEmpty
    var monoforumBroadcastInput: MTPInputPeer = MTPDinputPeerEmpty
}

class Peer(val data: Any /* User or Chat */) {
    fun user(): User? = data as? User
    fun chat(): Chat? = data as? Chat

    fun id(): PeerId = user()?.let { Peer_.fromUser(it.info.userId) } ?: chat()!!.id()
    fun name(): String = user()?.name() ?: chat()!!.title
    fun input(): MTPInputPeer {
        user()?.let { u ->
            val input = u.input
            if (input is org.tgbackup.mtproto.tl.MTPDinputUser) {
                return org.tgbackup.mtproto.tl.MTPDinputPeerUser(input.vuser_id, input.vaccess_hash)
            }
            return MTPDinputPeerEmpty
        }
        return chat()!!.input
    }

    fun colorIndex(): Int = user()?.colorIndex ?: chat()!!.colorIndex
}

class PersonalInfo {
    var user = User()
    var bio: String = ""
}

class TopPeer(var peer: Peer, var rating: Double = 0.0)

class ContactsList {
    var list: MutableList<ContactInfo> = ArrayList()
    var correspondents: MutableList<TopPeer> = ArrayList()
    var inlineBots: MutableList<TopPeer> = ArrayList()
    var phoneCalls: MutableList<TopPeer> = ArrayList()
}

class Session {
    var applicationId = 0
    var platform: String = ""
    var deviceModel: String = ""
    var systemVersion: String = ""
    var applicationName: String = ""
    var applicationVersion: String = ""
    var created: TimeId = 0
    var lastActive: TimeId = 0
    var ip: String = ""
    var country: String = ""
    var region: String = ""
}

class WebSession {
    var botUsername: String = ""
    var domain: String = ""
    var browser: String = ""
    var platform: String = ""
    var created: TimeId = 0
    var lastActive: TimeId = 0
    var ip: String = ""
    var region: String = ""
}

class SessionsList {
    var list: MutableList<Session> = ArrayList()
    var webList: MutableList<WebSession> = ArrayList()
}

class Media {
    var content: MediaContent? = null
    var ttl: TimeId = 0

    fun file(): File = when (val c = content) {
        is Photo -> c.image.file
        is Document -> c.file
        is SharedContact -> c.vcard
        else -> File()
    }

    fun thumb(): Image = when (val c = content) {
        is Document -> c.thumb
        else -> Image()
    }

    /** `media.file() = File()` in C++. */
    fun resetFile() {
        when (val c = content) {
            is Photo -> c.image.file = File()
            is Document -> c.file = File()
            is SharedContact -> c.vcard = File()
            else -> Unit
        }
    }

    fun resetThumbFile() {
        (content as? Document)?.thumb?.file = File()
    }
}

class ParseMediaContext(
    var selfPeerId: PeerId = 0,
    var photos: Int = 0,
    var audios: Int = 0,
    var videos: Int = 0,
    var files: Int = 0,
    var contacts: Int = 0,
    var botId: Long = 0,
)

class CreditsAmount(whole: Long = 0, nano: Long = 0, val ton: Boolean = false) {
    var whole: Long = whole
        private set
    var nano: Long = nano
        private set

    init {
        normalize()
    }

    fun value(): Double = whole.toDouble() + nano.toDouble() / 1_000_000_000L
    fun stars(): Boolean = !ton
    fun empty(): Boolean = whole == 0L && nano == 0L

    operator fun minus(other: CreditsAmount): CreditsAmount =
        CreditsAmount(whole - other.whole, nano - other.nano, ton)

    private fun normalize() {
        val one = 1_000_000_000L
        if (nano < 0) {
            val shifts = (-nano + one - 1) / one
            nano += shifts * one
            whole -= shifts
        } else if (nano >= one) {
            val shifts = nano / one
            nano -= shifts * one
            whole += shifts
        }
    }
}

class Birthday(day: Int = 0, month: Int = 0, year: Int = 0) {
    private val value: Int = if (validate(day, month, year)) day + month * 100 + year * 10000 else 0

    fun valid() = value != 0
    fun day() = value % 100
    fun month() = (value / 100) % 100
    fun year() = value / 10000

    private fun validate(day: Int, month: Int, year: Int): Boolean {
        if (year != 0 && (year < 1875 || year > 2100)) return false
        if (day < 1) return false
        return when (month) {
            2 -> if (day == 29) year == 0 || (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) else day <= 28
            4, 6, 9, 11 -> day <= 30
            in 1..12 -> day <= 31
            else -> false
        }
    }
}

sealed interface ServiceActionContent

class ActionChatCreate : ServiceActionContent {
    var title: String = ""
    var userIds: MutableList<Long> = ArrayList()
}

class ActionChatEditTitle(var title: String = "") : ServiceActionContent
class ActionChatEditPhoto(var photo: Photo = Photo()) : ServiceActionContent
object ActionChatDeletePhoto : ServiceActionContent
class ActionChatAddUser(var userIds: MutableList<Long> = ArrayList()) : ServiceActionContent
class ActionChatDeleteUser(var userId: Long = 0) : ServiceActionContent
class ActionChatJoinedByLink(var inviterId: Long = 0) : ServiceActionContent
class ActionChannelCreate(var title: String = "") : ServiceActionContent
class ActionChatMigrateTo(var channelId: Long = 0) : ServiceActionContent
class ActionChannelMigrateFrom(var title: String = "", var chatId: Long = 0) : ServiceActionContent
object ActionPinMessage : ServiceActionContent
object ActionHistoryClear : ServiceActionContent
class ActionGameScore(var gameId: Long = 0, var score: Int = 0) : ServiceActionContent

class ActionPaymentSent : ServiceActionContent {
    var currency: String = ""
    var amount: Long = 0
    var recurringInit = false
    var recurringUsed = false
}

class ActionPhoneCall : ServiceActionContent {
    enum class State { Unknown, Missed, Disconnect, Hangup, Busy, MigrateConferenceCall, Invitation, Active }

    var conferenceId: Long = 0
    var state = State.Unknown
    var duration = 0
}

object ActionScreenshotTaken : ServiceActionContent
class ActionCustomAction(var message: String = "") : ServiceActionContent

class ActionBotAllowed : ServiceActionContent {
    var appId: Long = 0
    var app: String = ""
    var domain: String = ""
    var attachMenu = false
    var fromRequest = false
}

class ActionSecureValuesSent : ServiceActionContent {
    enum class Type {
        PersonalDetails, Passport, DriverLicense, IdentityCard, InternalPassport, Address, UtilityBill,
        BankStatement, RentalAgreement, PassportRegistration, TemporaryRegistration, Phone, Email,
    }

    var types: MutableList<Type> = ArrayList()
}

object ActionContactSignUp : ServiceActionContent
object ActionPhoneNumberRequest : ServiceActionContent

class ActionGeoProximityReached : ServiceActionContent {
    var fromId: PeerId = 0
    var toId: PeerId = 0
    var distance = 0
    var fromSelf = false
    var toSelf = false
}

class ActionGroupCall(var duration: Int = 0) : ServiceActionContent
class ActionInviteToGroupCall(var userIds: MutableList<Long> = ArrayList()) : ServiceActionContent
class ActionSetMessagesTTL(var period: TimeId = 0) : ServiceActionContent
class ActionGroupCallScheduled(var date: TimeId = 0) : ServiceActionContent
class ActionSetChatTheme(var emoji: String = "") : ServiceActionContent
object ActionChatJoinedByRequest : ServiceActionContent
class ActionChatJoinedViaCommunity(var communityId: Long = 0) : ServiceActionContent
class ActionWebViewDataSent(var text: String = "") : ServiceActionContent
class ActionGiftPremium(var cost: String = "", var days: Int = 0) : ServiceActionContent
class ActionTopicCreate(var title: String = "") : ServiceActionContent

class ActionTopicEdit : ServiceActionContent {
    var title: String = ""

    // std::optional<uint64> iconEmojiId = 0 -> engaged with 0 by default.
    var iconEmojiId: Long? = 0
}

class ActionSuggestProfilePhoto(var photo: Photo = Photo()) : ServiceActionContent
class ActionSetChatWallPaper(var same: Boolean = false, var both: Boolean = false) : ServiceActionContent

class ActionGiftCode : ServiceActionContent {
    var code: String = ""
    var boostPeerId: PeerId = 0
    var days = 0
    var viaGiveaway = false
    var unclaimed = false
}

class ActionRequestedPeer : ServiceActionContent {
    var peers: MutableList<PeerId> = ArrayList()
    var buttonId = 0
}

object ActionGiveawayLaunch : ServiceActionContent

class ActionGiveawayResults : ServiceActionContent {
    var winners = 0
    var unclaimed = 0
    var credits = false
}

class ActionBoostApply(var boosts: Int = 0) : ServiceActionContent

class ActionPaymentRefunded : ServiceActionContent {
    var peerId: PeerId = 0
    var currency: String = ""
    var amount: Long = 0
    var transactionId: String = ""
}

class ActionGiftCredits : ServiceActionContent {
    var cost: String = ""
    var amount = CreditsAmount()
}

class ActionPrizeStars : ServiceActionContent {
    var peerId: PeerId = 0
    var amount: Long = 0
    var transactionId: String = ""
    var giveawayMsgId = 0
    var isUnclaimed = false
}

class ActionStarGift : ServiceActionContent {
    var giftId: Long = 0
    var stars: Long = 0
    var text: MutableList<TextPart> = ArrayList()
    var anonymous = false
    var limited = false
    var offerPrice = CreditsAmount()
    var offerExpireAt: TimeId = 0
    var offer = false
    var offerAccepted = false
    var offerDeclined = false
    var offerExpired = false
}

class ActionPaidMessagesRefunded(var messages: Int = 0, var stars: Long = 0) : ServiceActionContent
class ActionPaidMessagesPrice(var stars: Int = 0, var broadcastAllowed: Boolean = false) : ServiceActionContent

class ActionTodoCompletions : ServiceActionContent {
    var completed: MutableList<Int> = ArrayList()
    var incompleted: MutableList<Int> = ArrayList()
}

class ActionTodoAppendTasks(var items: MutableList<TodoListItem> = ArrayList()) : ServiceActionContent
class ActionPollAppendAnswer(var option: String = "") : ServiceActionContent
class ActionPollDeleteAnswer(var option: String = "") : ServiceActionContent

class ActionSuggestedPostApproval : ServiceActionContent {
    var rejectComment: String = ""
    var scheduleDate: TimeId = 0
    var price = CreditsAmount()
    var rejected = false
    var balanceTooLow = false
}

class ActionSuggestedPostSuccess(var price: CreditsAmount = CreditsAmount()) : ServiceActionContent
class ActionSuggestedPostRefund(var payerInitiated: Boolean = false) : ServiceActionContent
class ActionSuggestBirthday(var birthday: Birthday = Birthday()) : ServiceActionContent
class ActionNoForwardsToggle(var newValue: Boolean = false) : ServiceActionContent
class ActionNoForwardsRequest(var expired: Boolean = false, var newValue: Boolean = false) : ServiceActionContent
class ActionNewCreatorPending(var newCreatorId: Long = 0) : ServiceActionContent
class ActionChangeCreator(var newCreatorId: Long = 0) : ServiceActionContent
class ActionManagedBotCreated(var botId: Long = 0) : ServiceActionContent

class ServiceAction {
    var content: ServiceActionContent? = null
}

class Reaction {
    enum class Type { Empty, Emoji, CustomEmoji, Paid }

    class Recent(var peerId: PeerId = 0, var date: TimeId = 0)

    var type = Type.Empty
    var emoji: String = ""
    var documentId: String = ""
    var count: Long = 0
    var recent: MutableList<Recent> = ArrayList()

    companion object {
        fun TypeToString(reaction: Reaction): String = when (reaction.type) {
            Type.Empty -> "empty"
            Type.Emoji -> "emoji"
            Type.CustomEmoji -> "custom_emoji"
            Type.Paid -> "paid"
        }

        fun Id(reaction: Reaction): String {
            val id = when (reaction.type) {
                Type.Emoji -> reaction.emoji
                Type.CustomEmoji -> reaction.documentId
                else -> ""
            }
            return TypeToString(reaction) + id
        }
    }
}

data class MessageId(val channelId: Long, val msgId: Int) : Comparable<MessageId> {
    override fun compareTo(other: MessageId): Int =
        if (channelId != other.channelId) channelId.compareTo(other.channelId) else msgId.compareTo(other.msgId)
}

class HistoryMessageMarkupButton(
    var type: Type,
    var text: String = "",
    var data: ByteArray = ByteArray(0),
    var forwardText: String = "",
    var buttonId: Long = 0,
) {
    enum class Type {
        Default, Url, Callback, CallbackWithPassword, RequestPhone, RequestLocation, RequestPoll, RequestPeer,
        SwitchInline, SwitchInlineSame, Game, Buy, Auth, UserProfile, WebView, SimpleWebView, CopyText, Disabled,
    }

    companion object {
        fun TypeToString(button: HistoryMessageMarkupButton): String = when (button.type) {
            Type.Default -> "default"
            Type.Url -> "url"
            Type.Callback -> "callback"
            Type.CallbackWithPassword -> "callback_with_password"
            Type.RequestPhone -> "request_phone"
            Type.RequestLocation -> "request_location"
            Type.RequestPoll -> "request_poll"
            Type.RequestPeer -> "request_peer"
            Type.SwitchInline -> "switch_inline"
            Type.SwitchInlineSame -> "switch_inline_same"
            Type.Game -> "game"
            Type.Buy -> "buy"
            Type.Auth -> "auth"
            Type.UserProfile -> "user_profile"
            Type.WebView -> "web_view"
            Type.SimpleWebView -> "simple_web_view"
            Type.CopyText -> "copy_text"
            Type.Disabled -> "disabled"
        }
    }
}

class Message {
    var id = 0
    var date: TimeId = 0
    var edited: TimeId = 0
    var fromId: PeerId = 0
    var peerId: PeerId = 0
    var selfId: PeerId = 0
    var forwardedFromId: PeerId = 0
    var forwardedFromName: String = ""
    var forwardedDate: TimeId = 0
    var forwarded = false
    var showForwardedAsOriginal = false
    var savedFromChatId: PeerId = 0
    var signature: String = ""
    var viaBotId: Long = 0
    var replyToMsgId = 0
    var replyToPeerId: PeerId = 0
    var text: MutableList<TextPart> = ArrayList()
    var reactions: MutableList<Reaction> = ArrayList()
    var media = Media()
    var action = ServiceAction()
    var out = false
    var inlineButtonRows: MutableList<MutableList<HistoryMessageMarkupButton>> = ArrayList()
    var richMessage: RichMessage? = null

    fun file(): File {
        val c = action.content
        if (c is ActionChatEditPhoto) return c.photo.image.file
        if (c is ActionSuggestProfilePhoto) return c.photo.image.file
        return media.file()
    }

    fun thumb(): Image = media.thumb()
}

class FileOrigin {
    var split = 0
    var peer: MTPInputPeer = MTPDinputPeerEmpty
    var messageId = 0
    var storyId = 0
    var customEmojiId: Long = 0
    var richMessage = false
}

class Story {
    var id = 0
    var date: TimeId = 0
    var expires: TimeId = 0
    var media = Media()
    var pinned = false
    var caption: MutableList<TextPart> = ArrayList()

    fun file(): File = media.file()
    fun thumb(): Image = media.thumb()
}

class StoriesSlice {
    var list: MutableList<Story> = ArrayList()
    var lastId = 0
    var skipped = 0
}

class ProfileMusicSlice {
    var list: MutableList<Message> = ArrayList()
    var skipped = 0
}

class DialogInfo {
    enum class Type {
        Unknown, Self, Replies, VerifyCodes, Personal, Bot, PrivateGroup, PrivateSupergroup, PublicSupergroup,
        PrivateChannel, PublicChannel,
    }

    var type = Type.Unknown
    var name: String = ""
    var lastName: String = ""
    var input: MTPInputPeer = MTPDinputPeerEmpty
    var topMessageId = 0
    var topMessageDate: TimeId = 0
    var peerId: PeerId = 0
    var colorIndex = 0
    var migratedFromInput: MTPInputPeer = MTPDinputPeerEmpty
    var migratedToChannelId: Long = 0
    var monoforumBroadcastInput: MTPInputPeer = MTPDinputPeerEmpty

    // User messages splits which contained that dialog.
    var splits: MutableList<Int> = ArrayList()

    // Filled after the whole dialogs list is accumulated.
    var onlyMyMessages = false
    var isLeftChannel = false
    var isMonoforum = false
    var relativePath: String = ""

    // Filled when requesting dialog messages.
    var messagesCountPerSplit: MutableList<Int> = ArrayList()
}

class DialogsInfo {
    var chats: MutableList<DialogInfo> = ArrayList()
    var left: MutableList<DialogInfo> = ArrayList()

    fun item(index: Int): DialogInfo? {
        val chatsCount = chats.size
        return when {
            index < 0 -> null
            index < chatsCount -> chats[index]
            index - chatsCount < left.size -> left[index - chatsCount]
            else -> null
        }
    }
}

class MessagesSlice {
    var list: MutableList<Message> = ArrayList()
    var peers: java.util.TreeMap<PeerId, Peer> = java.util.TreeMap()
}
