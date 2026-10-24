// Port of the free helpers at the top of tdesktop
// Telegram/SourceFiles/export/output/export_output_html.cpp
@file:Suppress("FunctionName")

package org.tgbackup.export.output

import org.tgbackup.export.data.*
import org.tgbackup.export.data.File.SkipReason as SR
import java.time.Instant
import java.time.ZonedDateTime
import java.util.TreeMap

internal const val kMessagesInFile = 1000
internal const val kPersonalUserpicSize = 90
internal const val kEntryUserpicSize = 48
internal const val kServiceMessagePhotoSize = 60
internal const val kHistoryUserpicSize = 42
internal const val kSavedMessagesColorIndex = 3
internal const val kJoinWithinSeconds = 900
internal const val kPhotoMaxWidth = 520
internal const val kPhotoMaxHeight = 520
internal const val kPhotoMinWidth = 80
internal const val kPhotoMinHeight = 80
internal const val kStickerMaxWidth = 384
internal const val kStickerMaxHeight = 384
internal const val kStickerMinWidth = 80
internal const val kStickerMinHeight = 80
internal const val kStoryThumbWidth = 45
internal const val kStoryThumbHeight = 80
internal const val kGroupWidthMax = 430
internal const val kGroupWidthMin = 100
internal const val kGroupSkip = 4

internal const val kLineBreak = "<br>"

internal typealias Attrs = TreeMap<String, String>

internal fun attrs(vararg pairs: Pair<String, String>): Attrs = Attrs().also { m ->
    // std::map initializer lists keep the first of duplicate keys.
    for ((k, v) in pairs) m.putIfAbsent(k, v)
}

/** std::map::emplace: does nothing if the key exists. */
internal fun Attrs.emplace(key: String, value: String) {
    putIfAbsent(key, value)
}

internal fun IsGlobalLink(link: String): Boolean =
    link.startsWith("http://", ignoreCase = true) || link.startsWith("https://", ignoreCase = true)

internal fun NoFileDescription(reason: SR): String = when (reason) {
    SR.Unavailable -> "Unavailable, please try again later."
    SR.FileSize -> "Exceeds maximum size, change data exporting settings to download."
    SR.FileType -> "Not included, change data exporting settings to download."
    SR.None -> ""
    SR.DateLimits -> throw IllegalStateException("Skip reason in NoFileDescription.")
}

internal fun RichFilePresentation(file: org.tgbackup.export.data.File?): org.tgbackup.export.data.File {
    val result = file?.copy() ?: org.tgbackup.export.data.File()
    if (result.relativePath.isNotEmpty()) {
        result.skipReason = SR.None
    } else {
        when (result.skipReason) {
            SR.FileType, SR.FileSize -> Unit
            SR.None, SR.Unavailable, SR.DateLimits -> result.skipReason = SR.Unavailable
        }
    }
    return result
}

internal fun RichPhotoPresentation(photo: Photo?): Photo {
    val result = Photo()
    if (photo != null) {
        result.id = photo.id
        result.date = photo.date
        result.spoilered = photo.spoilered
        result.image.width = photo.image.width
        result.image.height = photo.image.height
    }
    result.image.file = RichFilePresentation(photo?.image?.file)
    return result
}

internal fun RichDocumentPresentation(document: Document?): Document {
    val result = Document()
    if (document != null) {
        result.id = document.id
        result.date = document.date
        result.thumb.width = document.thumb.width
        result.thumb.height = document.thumb.height
        result.name = document.name
        result.mime = document.mime
        result.width = document.width
        result.height = document.height
        result.stickerEmoji = document.stickerEmoji
        result.songPerformer = document.songPerformer
        result.songTitle = document.songTitle
        result.duration = document.duration
        result.isSticker = document.isSticker
        result.isAnimated = document.isAnimated
        result.isVideoMessage = document.isVideoMessage
        result.isVoiceMessage = document.isVoiceMessage
        result.isVideoFile = document.isVideoFile
        result.isAudioFile = document.isAudioFile
        result.spoilered = document.spoilered
    }
    result.file = RichFilePresentation(document?.file)
    result.thumb.file = RichFilePresentation(document?.thumb?.file)
    return result
}

internal fun RichFileDescription(file: org.tgbackup.export.data.File?): String =
    NoFileDescription(RichFilePresentation(file).skipReason)

/** QSize::scaled(w, h, Qt::KeepAspectRatio). */
internal fun ScaledKeepAspect(size: QSize, maxWidth: Int, maxHeight: Int): QSize {
    if (size.width == 0 || size.height == 0) return QSize(maxWidth, maxHeight)
    val rw = (maxHeight.toLong() * size.width / size.height).toInt()
    return if (rw <= maxWidth) {
        QSize(rw, maxHeight)
    } else {
        QSize(maxWidth, (maxWidth.toLong() * size.height / size.width).toInt())
    }
}

internal fun CalculateThumbSize(
    maxWidth: Int,
    maxHeight: Int,
    minWidth: Int,
    minHeight: Int,
    expandForRetina: Boolean = false,
): (QSize) -> QSize = { largeSize ->
    val multiplier = if (expandForRetina) 2 else 1
    val checkWidth = largeSize.width * multiplier
    val checkHeight = largeSize.height * multiplier
    val smallSize = if (checkWidth > maxWidth || checkHeight > maxHeight) {
        ScaledKeepAspect(largeSize, maxWidth, maxHeight)
    } else {
        largeSize
    }
    val retinaSize = QSize(smallSize.width and 0x01.inv(), smallSize.height and 0x01.inv())
    if (retinaSize.width < kPhotoMinWidth || retinaSize.height < kPhotoMinHeight) QSize(0, 0) else retinaSize
}

internal fun SerializeHtml(value: String): String {
    val result = StringBuilder(value.length + 16)
    for (ch in value) {
        when {
            ch == '\n' -> result.append("<br>")
            ch == '"' -> result.append("&quot;")
            ch == '&' -> result.append("&amp;")
            ch == '\'' -> result.append("&apos;")
            ch == '<' -> result.append("&lt;")
            ch == '>' -> result.append("&gt;")
            ch.code < 32 -> {
                result.append("&#x").append('0' + (ch.code shr 4))
                val left = ch.code and 0x0F
                if (left >= 10) result.append('A' + (left - 10)) else result.append('0' + left)
                result.append(';')
            }
            ch == ' ' || ch == ' ' -> result.append("<br>")
            else -> result.append(ch)
        }
    }
    return result.toString()
}

internal fun SerializeList(values: List<String>): String {
    val count = values.size
    if (count == 1) return values[0]
    if (count > 1) {
        val result = StringBuilder(values[0])
        for (i in 1 until count - 1) result.append(", ").append(values[i])
        return result.append(" and ").append(values[count - 1]).toString()
    }
    return ""
}

internal fun MakeLinks(value: String): String {
    val domain = "https://telegram.org/"
    val result = StringBuilder()
    var offset = 0
    while (true) {
        val start = value.indexOf(domain, offset)
        if (start < 0) break
        var end = start + domain.length
        while (end != value.length) {
            val ch = value[end]
            if ((ch < 'a' || ch > 'z') && (ch < 'A' || ch > 'Z') && (ch < '0' || ch > '9') &&
                ch != '-' && ch != '_' && ch != '/'
            ) break
            ++end
        }
        if (start > offset) {
            val link = value.substring(start, end)
            result.append(value, offset, start)
            result.append("<a href=\"").append(link).append("\">")
            result.append(link)
            result.append("</a>")
            offset = end
        } else {
            // tdesktop would loop forever here; stop instead.
            break
        }
    }
    if (result.isEmpty()) return value
    if (offset < value.length) result.append(value.substring(offset))
    return result.toString()
}

internal fun JoinList(separator: String, list: List<String>): String = when {
    list.isEmpty() -> ""
    list.size == 1 -> list[0]
    else -> list.joinToString(separator)
}

internal fun FormatTextLink(text: String, target: String): String {
    val href = SafeMessageHref(target)
    return if (href != null) "<a href=\"" + SerializeHtml(href) + "\">" + text + "</a>" else text
}

internal fun FormatCustomEmoji(customEmoji: String, text: String, relativeLinkBase: String): String =
    (
        when {
            customEmoji.isEmpty() -> "<a href=\"\" onclick=\"return ShowNotLoadedEmoji();\">"
            customEmoji == TextPart.UnavailableEmoji() -> "<a href=\"\" onclick=\"return ShowNotAvailableEmoji();\">"
            else -> "<a href = \"" + relativeLinkBase + customEmoji + "\">"
        }
        ) + text + "</a>"

internal fun FormatText(data: List<TextPart>, internalLinksDomain: String, relativeLinkBase: String): String =
    JoinList(
        "",
        data.map { part ->
            val text = SerializeHtml(part.text)
            when (part.type) {
                TextPart.Type.Text -> text
                TextPart.Type.Unknown -> text
                TextPart.Type.Mention -> "<a href=\"" + internalLinksDomain + qMid(text, 1) + "\">" + text + "</a>"
                TextPart.Type.Hashtag -> "<a href=\"\" onclick=\"return ShowHashtag(" +
                    SerializeHtml("\"" + qMid(text, 1) + "\"") + ")\">" + text + "</a>"
                TextPart.Type.BotCommand -> "<a href=\"\" onclick=\"return ShowBotCommand(" +
                    SerializeHtml("\"" + qMid(text, 1) + "\"") + ")\">" + text + "</a>"
                TextPart.Type.Url -> FormatTextLink(text, part.text)
                TextPart.Type.Email -> "<a href=\"mailto:$text\">$text</a>"
                TextPart.Type.Bold -> "<strong>$text</strong>"
                TextPart.Type.Italic -> "<em>$text</em>"
                TextPart.Type.Code -> "<code>$text</code>"
                TextPart.Type.Pre -> "<pre>$text</pre>"
                TextPart.Type.TextUrl -> FormatTextLink(text, part.additional)
                TextPart.Type.MentionName -> "<a href=\"\" onclick=\"return ShowMentionName()\">$text</a>"
                TextPart.Type.Phone -> "<a href=\"tel:$text\">$text</a>"
                TextPart.Type.Cashtag -> "<a href=\"\" onclick=\"return ShowCashtag(" +
                    SerializeHtml("\"" + qMid(text, 1) + "\"") + ")\">" + text + "</a>"
                TextPart.Type.Underline -> "<u>$text</u>"
                TextPart.Type.Strike -> "<s>$text</s>"
                TextPart.Type.Blockquote -> "<blockquote>$text</blockquote>"
                TextPart.Type.BankCard -> text
                TextPart.Type.Spoiler -> "<span class=\"spoiler hidden\" onclick=\"ShowSpoiler(this)\">" +
                    "<span aria-hidden=\"true\">" + text + "</span></span>"
                TextPart.Type.CustomEmoji -> FormatCustomEmoji(part.additional, text, relativeLinkBase)
            }
        },
    )

internal fun FormatUsernameHtml(username: String): String = if (username.isEmpty()) username else "@$username"

private fun localDate(date: TimeId): ZonedDateTime =
    ZonedDateTime.ofInstant(Instant.ofEpochSecond(date.toLong()), ExportTimeZone.zone)

internal fun DisplayDate(date: TimeId, previousDate: TimeId): Boolean {
    if (previousDate == 0) return true
    return localDate(date).toLocalDate() != localDate(previousDate).toLocalDate()
}

internal fun FormatDateText(date: TimeId): String {
    val parsed = localDate(date)
    val month = when (parsed.monthValue) {
        1 -> "January"
        2 -> "February"
        3 -> "March"
        4 -> "April"
        5 -> "May"
        6 -> "June"
        7 -> "July"
        8 -> "August"
        9 -> "September"
        10 -> "October"
        11 -> "November"
        12 -> "December"
        else -> "Unknown"
    }
    return NumberToString(parsed.dayOfMonth) + ' ' + month + ' ' + NumberToString(parsed.year)
}

internal fun FormatTimeText(date: TimeId): String {
    val parsed = localDate(date)
    return NumberToString(parsed.hour, 2) + ':' + NumberToString(parsed.minute, 2)
}

/** QDateTime::fromSecsSinceEpoch(date).toString(Qt::ISODate) in local time. */
internal fun IsoDate(date: TimeId): String {
    val v = localDate(date)
    return String.format(java.util.Locale.ROOT, "%04d-%02d-%02dT%02d:%02d:%02d", v.year, v.monthValue, v.dayOfMonth, v.hour, v.minute, v.second)
}

internal class UserpicData {
    var colorIndex = 0
    var pixelSize = 0
    var imageLink = ""
    var largeLink = ""
    var firstName = ""
    var lastName = ""
    var tooltip = ""
}

internal class StoryData {
    var imageLink = ""
    var largeLink = ""
}

internal class PeersMap(private val data: Map<PeerId, Peer>) {
    private val emptyPeer = Peer(User())
    private val emptyUser = User()

    fun peer(peerId: PeerId): Peer = data[peerId] ?: emptyPeer
    fun user(userId: Long): User = peer(Peer_.fromUser(userId)).user() ?: emptyUser

    fun wrapPeerName(peerId: PeerId): String {
        val result = peer(peerId).name()
        return if (result.isEmpty()) "Deleted" else SerializeHtml(result)
    }

    fun wrapUserName(userId: Long): String {
        val result = user(userId).name()
        return if (result.isEmpty()) "Deleted Account" else SerializeHtml(result)
    }

    fun wrapUserNames(data: List<Long>): String = SerializeList(data.map { wrapUserName(it) })
}

internal class MediaData {
    var title = ""
    var description = ""
    var status = ""
    var classes = ""
    var thumb = ""
    var link = ""
}

internal class HtmlContext {
    private class Tag(val name: String, val block: Boolean)

    private val tags = ArrayList<Tag>()

    fun pushTag(tag: String, attributes: Attrs = Attrs()): String {
        var block = true
        var empty = false
        val inner = StringBuilder()
        for ((name, value) in attributes) {
            when (name) {
                "inline" -> block = false
                "empty" -> empty = true
                else -> inner.append(' ').append(name).append("=\"").append(SerializeHtml(value)).append("\"")
            }
        }
        val result = (if (block) "\n" + indent() else "") + "<" + tag + inner + (if (empty) "/" else "") + ">" +
            (if (block) "\n" else "")
        if (!empty) tags.add(Tag(tag, block))
        return result
    }

    fun popTag(): String {
        check(tags.isNotEmpty())
        val data = tags.removeAt(tags.size - 1)
        return (if (data.block) "\n" + indent() else "") + "</" + data.name + ">" + (if (data.block) "\n" else "")
    }

    fun indent(): String = " ".repeat(tags.size)
    fun empty(): Boolean = tags.isEmpty()
}

internal fun PrepareAudioMediaData(data: Document): MediaData {
    val hasFile = data.file.relativePath.isNotEmpty()
    val result = MediaData()
    result.title = when {
        data.songPerformer.isNotEmpty() && data.songTitle.isNotEmpty() -> data.songPerformer + " – " + data.songTitle
        data.name.isNotEmpty() -> data.name
        else -> "Audio file"
    }
    result.status = FormatDuration(data.duration.toLong())
    if (!hasFile) result.status += ", " + FormatFileSize(data.file.size)
    result.classes = "media_audio_file"
    result.link = data.file.relativePath
    result.description = NoFileDescription(data.file.skipReason)
    return result
}

internal fun PrepareFileMediaData(data: Document): MediaData {
    val result = MediaData()
    result.title = if (data.name.isEmpty()) "File" else data.name
    result.status = FormatFileSize(data.file.size)
    result.classes = "media_file"
    result.link = data.file.relativePath
    result.description = NoFileDescription(data.file.skipReason)
    return result
}

// ---- Link safety ------------------------------------------------------------

internal const val kRichTargetMaxBytes = 4096
internal const val kRichAnchorDirectBytes = 72
internal const val kRichAnchorPrefixBytes = 48
internal const val kRichTableSpanMax = 1000
internal const val kRichHashtagMinLength = 2
internal const val kRichHashtagMaxLength = 64
internal const val kRichCommandMaxLength = 64
internal const val kRichCashtagMaxLength = 8
internal const val kRichUsernameMinLength = 5
internal const val kRichUsernameMaxLength = 32

private fun qIsSpace(c: Char): Boolean =
    c == ' ' || c in '\t'..'\r' || c == '\u0085' || c == ' ' || Character.isSpaceChar(c)

private fun qTrimmed(s: String): String {
    var b = 0
    var e = s.length
    while (b < e && qIsSpace(s[b])) b++
    while (e > b && qIsSpace(s[e - 1])) e--
    return s.substring(b, e)
}

internal fun HexValue(value: Char): Int = when (value) {
    in '0'..'9' -> value - '0'
    in 'a'..'f' -> value - 'a' + 10
    in 'A'..'F' -> value - 'A' + 10
    else -> -1
}

/** Works on the UTF-8 bytes, like tdesktop (only ASCII is inspected). */
internal fun HasEncodedControl(value: String): Boolean {
    var i = 0
    while (i + 2 < value.length) {
        if (value[i] == '%') {
            val high = HexValue(value[i + 1])
            val low = HexValue(value[i + 2])
            if (high >= 0 && low >= 0) {
                val decoded = (high shl 4) or low
                if (decoded < 32 || decoded == 127) return true
            }
        }
        i++
    }
    return false
}

internal fun IsStrictTarget(value: String): Boolean {
    if (value.isEmpty() || utf8Size(value) > kRichTargetMaxBytes) return false
    for (c in value) if (c.code < 32 || c.code == 127) return false
    if (qTrimmed(value) != value) return false
    var i = 0
    while (i < value.length) {
        val cp = value.codePointAt(i)
        val type = Character.getType(cp)
        if (type == Character.CONTROL.toInt() || type == Character.LINE_SEPARATOR.toInt() ||
            type == Character.PARAGRAPH_SEPARATOR.toInt()
        ) return false
        i += Character.charCount(cp)
    }
    return true
}

/** A small emulation of QUrl::fromEncoded(StrictMode) + toEncoded(FullyEncoded). */
private object QUrlLike {
    class Parsed(val scheme: String, val host: String?, val encoded: String)

    private fun isSchemeChar(c: Char, first: Boolean) =
        c in 'a'..'z' || c in 'A'..'Z' || (!first && (c in '0'..'9' || c == '+' || c == '-' || c == '.'))

    private const val ALLOWED = "-._~!$&'()*+,;=:@/?#[]%"

    fun parse(input: String): Parsed? {
        // StrictMode: only ASCII URL characters, '%' must start a valid escape.
        for ((i, c) in input.withIndex()) {
            if (c.code >= 0x7F || c.code < 0x21) return null
            if (!(c.isLetterOrDigit() || c in ALLOWED)) return null
            if (c == '%') {
                if (i + 2 >= input.length || HexValue(input[i + 1]) < 0 || HexValue(input[i + 2]) < 0) return null
            }
        }
        var scheme = ""
        var rest = input
        val colon = input.indexOf(':')
        if (colon > 0) {
            val candidate = input.substring(0, colon)
            if (candidate.withIndex().all { (i, c) -> isSchemeChar(c, i == 0) } &&
                !candidate.contains('/') && !candidate.contains('?') && !candidate.contains('#')
            ) {
                scheme = candidate.lowercase()
                rest = input.substring(colon + 1)
            }
        }
        var host: String? = null
        var out = StringBuilder()
        if (scheme.isNotEmpty()) out.append(scheme).append(':')
        if (rest.startsWith("//")) {
            val afterSlashes = rest.substring(2)
            var end = afterSlashes.length
            for ((i, c) in afterSlashes.withIndex()) if (c == '/' || c == '?' || c == '#') {
                end = i
                break
            }
            val authority = afterSlashes.substring(0, end)
            val path = afterSlashes.substring(end)
            val at = authority.lastIndexOf('@')
            val userInfo = if (at >= 0) authority.substring(0, at + 1) else ""
            var hostPort = if (at >= 0) authority.substring(at + 1) else authority
            var port = ""
            if (!hostPort.startsWith("[")) {
                val pc = hostPort.lastIndexOf(':')
                if (pc >= 0) {
                    port = hostPort.substring(pc)
                    hostPort = hostPort.substring(0, pc)
                    if (port.length > 1 && !port.substring(1).all { it.isDigit() }) return null
                }
            }
            val h = decodePercent(hostPort).lowercase()
            if (!h.startsWith("[") && h.any { !(it.isLetterOrDigit() || it == '-' || it == '.' || it == '_') }) return null
            val ace = try {
                if (h.startsWith("[")) h else java.net.IDN.toASCII(h, java.net.IDN.ALLOW_UNASSIGNED)
            } catch (e: Exception) {
                return null
            }
            host = ace
            out.append("//").append(normalize(userInfo)).append(ace).append(port).append(normalize(path))
        } else {
            out.append(normalize(rest))
        }
        return Parsed(scheme, host, out.toString())
    }

    private fun decodePercent(s: String): String {
        if (!s.contains('%')) return s
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            if (s[i] == '%' && i + 2 < s.length) {
                bytes.write((HexValue(s[i + 1]) shl 4) or HexValue(s[i + 2]))
                i += 3
            } else {
                bytes.write(s[i].code)
                i++
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    /** Decodes escapes of unreserved characters and uppercases the rest. */
    private fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val v = (HexValue(s[i + 1]) shl 4) or HexValue(s[i + 2])
                val ch = v.toChar()
                if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '-' || ch == '.' || ch == '_' || ch == '~') {
                    sb.append(ch)
                } else {
                    sb.append('%').append(s[i + 1].uppercaseChar()).append(s[i + 2].uppercaseChar())
                }
                i += 3
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}

internal fun SafeMessageHref(value: String): String? {
    if (!IsStrictTarget(value) || value.startsWith("//") || HasEncodedControl(value)) return null
    var url = QUrlLike.parse(value)
    if (url == null || url.scheme.isEmpty()) {
        url = QUrlLike.parse("https://$value")
    }
    if (url == null) return null
    val scheme = url.scheme.lowercase()
    val allowed = listOf("http", "https", "mailto", "tel", "tg")
    val hostSchemes = listOf("http", "https")
    if (scheme.isEmpty() || scheme !in allowed || (scheme in hostSchemes && url.host.isNullOrEmpty())) return null
    val result = url.encoded
    return if (IsStrictTarget(result) && !HasEncodedControl(result)) result else null
}

internal fun SafeHttpHref(value: String): String? {
    if (!IsStrictTarget(value) || value.startsWith("//") || HasEncodedControl(value)) return null
    val url = QUrlLike.parse(value) ?: return null
    val scheme = url.scheme
    if (scheme.isEmpty() || url.host.isNullOrEmpty() ||
        (!scheme.equals("http", ignoreCase = true) && !scheme.equals("https", ignoreCase = true))
    ) return null
    val result = url.encoded
    return if (IsStrictTarget(result) && !HasEncodedControl(result)) result else null
}

internal fun SafeEmailHref(address: String): String? {
    if (!IsStrictTarget(address)) return null
    val separator = address.indexOf('@')
    if (separator <= 0 || separator != address.lastIndexOf('@') || separator + 1 == address.length) return null
    for (c in address) if (qIsSpace(c)) return null
    for (forbidden in "/\\:%?#&=") if (address.contains(forbidden)) return null
    val result = "mailto:$address"
    return if (IsStrictTarget(result)) result else null
}

internal fun SafePhoneHref(phone: String): String? {
    if (!IsStrictTarget(phone)) return null
    var hasDigit = false
    for (c in phone) {
        if (c in '0'..'9') {
            hasDigit = true
        } else if (c != '+' && c != '-' && c != ' ' && c != '.' && c != '(' && c != ')') {
            return null
        }
    }
    val result = "tel:$phone"
    return if (hasDigit && IsStrictTarget(result)) result else null
}

internal fun SafeMentionHref(mention: String, internalLinksDomain: String): String? {
    if (!IsStrictTarget(mention) || utf8Size(mention) < 2 || mention[0] != '@') return null
    val username = mention.substring(1)
    for (c in username) {
        if ((c < 'a' || c > 'z') && (c < 'A' || c > 'Z') && (c < '0' || c > '9') && c != '_') return null
    }
    return SafeHttpHref(internalLinksDomain + username)
}

internal fun IsAsciiWord(value: String, minimumLength: Int, maximumLength: Int): Boolean {
    val size = utf8Size(value)
    if (size < minimumLength || size > maximumLength) return false
    for (c in value) {
        if ((c < 'a' || c > 'z') && (c < 'A' || c > 'Z') && (c < '0' || c > '9') && c != '_') return false
    }
    return true
}

internal fun IsUnicodeHashtagWord(value: String): Boolean {
    val characters = ArrayList<Int>()
    var ci = 0
    while (ci < value.length) {
        val cp = value.codePointAt(ci)
        characters.add(cp)
        ci += Character.charCount(cp)
    }
    if (characters.size < kRichHashtagMinLength || characters.size > kRichHashtagMaxLength) return false
    var allDecimalDigits = true
    for (cp in characters) {
        val type = Character.getType(cp)
        val isMark = type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()
        if (!Character.isLetterOrDigit(cp) && !(type == Character.LETTER_NUMBER.toInt() || type == Character.OTHER_NUMBER.toInt()) &&
            type != Character.CONNECTOR_PUNCTUATION.toInt() && !isMark
        ) return false
        allDecimalDigits = allDecimalDigits && Character.isDigit(cp)
    }
    return !allDecimalDigits
}

private fun IsHashtagAction(value: String): Boolean {
    val separator = value.indexOf('@')
    val hashtag = if (separator < 0) value else value.substring(0, separator)
    if (!IsUnicodeHashtagWord(hashtag)) return false
    return separator < 0 ||
        (separator == value.lastIndexOf('@') && IsAsciiWord(value.substring(separator + 1), 1, kRichUsernameMaxLength))
}

private fun IsBotCommandAction(value: String): Boolean {
    val separator = value.indexOf('@')
    val command = if (separator < 0) value else value.substring(0, separator)
    if (!IsAsciiWord(command, 1, kRichCommandMaxLength)) return false
    return separator < 0 ||
        (separator == value.lastIndexOf('@') && IsAsciiWord(value.substring(separator + 1), kRichUsernameMinLength, kRichUsernameMaxLength))
}

private fun IsCashtagAction(value: String): Boolean {
    if (value.isEmpty() || utf8Size(value) > kRichCashtagMaxLength) return false
    for (c in value) if (c < 'A' || c > 'Z') return false
    return true
}

private fun IsValidActionValue(value: String, prefix: Char): Boolean = when (prefix) {
    '#' -> IsHashtagAction(value)
    '/' -> IsBotCommandAction(value)
    '$' -> IsCashtagAction(value)
    else -> false
}

internal fun SafeActionData(target: String, prefix: Char): String? {
    if (!IsStrictTarget(target) || target[0] != prefix || utf8Size(target) < 2) return null
    val result = target.substring(1)
    return if (IsValidActionValue(result, prefix) && IsStrictTarget(result)) result else null
}

internal fun IsAsciiDecimal(value: String): Boolean {
    if (value.isEmpty()) return false
    for (c in value) if (c < '0' || c > '9') return false
    return true
}

internal fun SafeRelativeEmojiHref(path: String, relativeBase: String): String? {
    if (!IsStrictTarget(path) || path.startsWith('/') || path.contains('\\') || path.contains(':') ||
        path.contains('%') || path.contains('?') || path.contains('#') ||
        path == TextPart.UnavailableEmoji() || IsAsciiDecimal(path)
    ) return null
    for (component in path.split('/')) {
        if (component.isEmpty() || component == "." || component == "..") return null
    }
    val result = relativeBase + path
    return if (IsStrictTarget(result)) result else null
}

internal fun AnchorToken(name: String): String {
    if (name.isEmpty()) return "empty"
    val bytes = name.toByteArray(Charsets.UTF_8)
    if (bytes.size <= kRichAnchorDirectBytes) return org.tgbackup.mtproto.B64.urlNoPad(bytes)
    val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
    val hex = hash.joinToString("") { "%02x".format(it) }.substring(0, 16)
    return org.tgbackup.mtproto.B64.urlNoPad(bytes.copyOfRange(0, kRichAnchorPrefixBytes)) + '-' + hex
}

/** QUrl::toPercentEncoding. */
internal fun PercentEncoding(value: String): String {
    val sb = StringBuilder()
    for (b in value.toByteArray(Charsets.UTF_8)) {
        val c = b.toInt() and 0xff
        val ch = c.toChar()
        if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '-' || ch == '.' || ch == '_' || ch == '~') {
            sb.append(ch)
        } else {
            sb.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
        }
    }
    return sb.toString()
}

/** QByteArray::number(value, 'f', 3). */
internal fun FixedNumber(value: Double, precision: Int): String =
    java.math.BigDecimal(value).setScale(precision, java.math.RoundingMode.HALF_UP).toPlainString()
