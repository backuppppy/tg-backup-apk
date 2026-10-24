// Ports of the helpers export_data_types.cpp borrows from the rest of
// tdesktop: ui/text/format_values.cpp, countries/countries_instance.cpp,
// core/mime_type.cpp and lib_base's FileNameFromUserString.
@file:Suppress("FunctionName")

package org.tgbackup.export.data

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Time zone used for dates in the export (Desktop uses the system one). */
object ExportTimeZone {
    @Volatile
    var zone: ZoneId = ZoneId.systemDefault()
}

fun FormatDateTime(
    date: TimeId,
    hasTimeZone: Boolean = false,
    dateSeparator: Char = '.',
    timeSeparator: Char = ':',
    separator: Char = ' ',
): String {
    if (date == 0) return ""
    val value = ZonedDateTime.ofInstant(Instant.ofEpochSecond(date.toLong()), ExportTimeZone.zone)
    val timeZoneOffset = if (hasTimeZone) separator + StandardOffsetName(value) else ""
    fun two(v: Int) = if (v < 10) "0$v" else v.toString()
    return two(value.dayOfMonth) + dateSeparator + two(value.monthValue) + dateSeparator + value.year +
        separator + two(value.hour) + timeSeparator + two(value.minute) + timeSeparator + two(value.second) +
        timeZoneOffset
}

/** QTimeZone::displayName(StandardTime, OffsetName): "UTC+03:00" ("UTC" for zero). */
private fun StandardOffsetName(value: ZonedDateTime): String {
    val seconds = value.zone.rules.getStandardOffset(value.toInstant()).totalSeconds
    if (seconds == 0) return "UTC"
    val sign = if (seconds < 0) '-' else '+'
    val abs = Math.abs(seconds)
    val h = abs / 3600
    val m = (abs % 3600) / 60
    return "UTC" + sign + (if (h < 10) "0$h" else "$h") + ":" + (if (m < 10) "0$m" else "$m")
}

fun FormatMoneyAmount(amount: Long, currency: String): String = FillAmountAndCurrency(amount, currency)

fun FormatFileSize(size: Long): String = FormatSizeText(size)

fun FormatDuration(seconds: Long): String = FormatDurationText(seconds)

fun FormatPhoneNumber(phoneNumber: String): String = if (phoneNumber.isEmpty()) "" else FormatPhone(phoneNumber)

fun FormatSizeText(size: Long): String {
    if (size >= 1024 * 1024) {
        val sizeTenthMb = size * 10 / (1024 * 1024)
        return (sizeTenthMb / 10).toString() + "." + (sizeTenthMb % 10) + " MB"
    }
    if (size >= 1024) {
        val sizeTenthKb = size * 10 / 1024
        return (sizeTenthKb / 10).toString() + "." + (sizeTenthKb % 10) + " KB"
    }
    return "$size B"
}

fun FormatDurationText(duration: Long): String {
    val hours = duration / 3600
    val minutes = (duration % 3600) / 60
    val seconds = duration % 60
    return (if (hours != 0L) "$hours:" else "") + (if (minutes >= 10) "" else "0") + minutes + ":" +
        (if (seconds >= 10) "" else "0") + seconds
}

/** Lang::FormatCountDecimal with the default (en-US) QLocale. */
fun FormatCountDecimal(number: Long): String =
    java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(number)

internal class CurrencyRule(
    val international: String = "",
    val thousands: Char = ',',
    val decimal: Char = '.',
    val left: Boolean = true,
    val space: Boolean = false,
    val exponent: Int = 2,
    val stripDotZero: Boolean = false,
)

fun FillAmountAndCurrency(amount: Long, currency: String, forceStripDotZero: Boolean = false): String {
    require(amount != Long.MIN_VALUE)
    if (currency == "XTR") {
        return "⭐" + FormatCountDecimal(Math.abs(amount))
    }
    val rule = LookupCurrencyRule(currency)
    val prefix = if (amount < 0) "−" else ""
    val value = Math.abs(amount) / Math.pow(10.0, rule.exponent.toDouble())
    val name = if (rule.international.isNotEmpty()) rule.international else currency
    val result = StringBuilder(prefix)
    if (rule.left) {
        result.append(name)
        if (rule.space) result.append(' ')
    }
    val precision = if ((!rule.stripDotZero && !forceStripDotZero) || Math.floor(value) != value) rule.exponent else 0
    result.append(FormatWithSeparators(value, precision, rule.decimal, rule.thousands))
    if (!rule.left) {
        if (rule.space) result.append(' ')
        result.append(name)
    }
    return result.toString()
}

/** std::fixed with a numpunct of grouping "\3". */
internal fun FormatWithSeparators(amount: Double, precision: Int, decimal: Char, thousands: Char): String {
    val fixed = java.math.BigDecimal(amount).setScale(precision, java.math.RoundingMode.HALF_EVEN).toPlainString()
    val negative = fixed.startsWith("-")
    val body = if (negative) fixed.substring(1) else fixed
    val dot = body.indexOf('.')
    val intPart = if (dot >= 0) body.substring(0, dot) else body
    val fracPart = if (dot >= 0) body.substring(dot + 1) else ""
    val grouped = StringBuilder()
    for ((i, c) in intPart.withIndex()) {
        if (i > 0 && (intPart.length - i) % 3 == 0 && thousands != '\u0000') grouped.append(thousands)
        grouped.append(c)
    }
    val sb = StringBuilder()
    if (negative) sb.append('-')
    sb.append(grouped)
    if (precision > 0) sb.append(decimal).append(fracPart)
    return sb.toString()
}

internal fun LookupCurrencyRule(currency: String): CurrencyRule = CurrencyRules[currency] ?: CurrencyRule()

private val CurrencyRules: Map<String, CurrencyRule> = mapOf(
    "AED" to CurrencyRule("", ',', '.', true, true),
    "AFN" to CurrencyRule(),
    "ALL" to CurrencyRule("", '.', ',', false),
    "AMD" to CurrencyRule("", ',', '.', false, true),
    "ARS" to CurrencyRule("", '.', ',', true, true),
    "AUD" to CurrencyRule("AU$"),
    "AZN" to CurrencyRule("", ' ', ',', false, true),
    "BAM" to CurrencyRule("", '.', ',', false, true),
    "BDT" to CurrencyRule("", ',', '.', true, true),
    "BGN" to CurrencyRule("", ' ', ',', false, true),
    "BHD" to CurrencyRule("", ',', '.', true, true, 3),
    "BND" to CurrencyRule("", '.', ','),
    "BOB" to CurrencyRule("", '.', ',', true, true),
    "BRL" to CurrencyRule("R$", '.', ',', true, true),
    "BYN" to CurrencyRule("", ' ', ',', false, true),
    "CAD" to CurrencyRule("CA$"),
    "CHF" to CurrencyRule("", '\'', '.', false, true),
    "CLP" to CurrencyRule("", '.', ',', true, true, 0),
    "CNY" to CurrencyRule("CN¥"),
    "COP" to CurrencyRule("", '.', ',', true, true),
    "CRC" to CurrencyRule("", '.', ','),
    "CZK" to CurrencyRule("", ' ', ',', false, true),
    "DKK" to CurrencyRule("", '\u0000', ',', false, true),
    "DOP" to CurrencyRule(),
    "DZD" to CurrencyRule("", ',', '.', true, true),
    "EGP" to CurrencyRule("", ',', '.', true, true),
    "ETB" to CurrencyRule(),
    "EUR" to CurrencyRule("€", ' ', ',', false, true),
    "GBP" to CurrencyRule("£"),
    "GEL" to CurrencyRule("", ' ', ',', false, true),
    "GHS" to CurrencyRule(),
    "GTQ" to CurrencyRule(),
    "HKD" to CurrencyRule("HK$"),
    "HNL" to CurrencyRule("", ',', '.', true, true),
    "HRK" to CurrencyRule("", '.', ',', false, true),
    "HUF" to CurrencyRule("", ' ', ',', false, true),
    "IDR" to CurrencyRule("", '.', ','),
    "ILS" to CurrencyRule("₪", ',', '.', true, true),
    "INR" to CurrencyRule("₹"),
    "IQD" to CurrencyRule("", ',', '.', true, true, 3),
    "IRR" to CurrencyRule("", ',', '/', false, true),
    "ISK" to CurrencyRule("", '.', ',', false, true, 0),
    "JMD" to CurrencyRule(),
    "JOD" to CurrencyRule("", ',', '.', true, false, 3),
    "JPY" to CurrencyRule("¥", ',', '.', true, false, 0),
    "KES" to CurrencyRule(),
    "KGS" to CurrencyRule("", ' ', '-', false, true),
    "KRW" to CurrencyRule("₩", ',', '.', true, false, 0),
    "KZT" to CurrencyRule("", ' ', '-'),
    "LBP" to CurrencyRule("", ',', '.', true, true),
    "LKR" to CurrencyRule("", ',', '.', true, true),
    "MAD" to CurrencyRule("", ',', '.', true, true),
    "MDL" to CurrencyRule("", ',', '.', false, true),
    "MMK" to CurrencyRule(),
    "MNT" to CurrencyRule("", ' ', ','),
    "MOP" to CurrencyRule(),
    "MUR" to CurrencyRule(),
    "MVR" to CurrencyRule("", ',', '.', false, true),
    "MXN" to CurrencyRule("MX$"),
    "MYR" to CurrencyRule(),
    "MZN" to CurrencyRule(),
    "NGN" to CurrencyRule(),
    "NIO" to CurrencyRule("", ',', '.', true, true),
    "NOK" to CurrencyRule("", ' ', ',', true, true),
    "NPR" to CurrencyRule(),
    "NZD" to CurrencyRule("NZ$"),
    "PAB" to CurrencyRule("", ',', '.', true, true),
    "PEN" to CurrencyRule("", ',', '.', true, true),
    "PHP" to CurrencyRule(),
    "PKR" to CurrencyRule(),
    "PLN" to CurrencyRule("", ' ', ',', false, true),
    "PYG" to CurrencyRule("", '.', ',', true, true, 0),
    "QAR" to CurrencyRule("", ',', '.', true, true),
    "RON" to CurrencyRule("", '.', ',', false, true),
    "RSD" to CurrencyRule("", '.', ',', false, true),
    "RUB" to CurrencyRule("", ' ', ',', false, true),
    "SAR" to CurrencyRule("", ',', '.', true, true),
    "SEK" to CurrencyRule("", '.', ',', false, true),
    "SGD" to CurrencyRule(),
    "SYP" to CurrencyRule("", ',', '.', true, true),
    "THB" to CurrencyRule("฿"),
    "TJS" to CurrencyRule("", ' ', ';', false, true),
    "TRY" to CurrencyRule("", '.', ',', false, true),
    "TTD" to CurrencyRule(),
    "TWD" to CurrencyRule("NT$"),
    "TZS" to CurrencyRule(),
    "UAH" to CurrencyRule("", ' ', ',', false),
    "UGX" to CurrencyRule("", ',', '.', true, false, 0),
    "USD" to CurrencyRule("$"),
    "UYU" to CurrencyRule("", '.', ',', true, true),
    "UZS" to CurrencyRule("", ' ', ',', false, true),
    "VEF" to CurrencyRule("", '.', ',', true, true),
    "VND" to CurrencyRule("₫", '.', ',', false, true, 0),
    "YER" to CurrencyRule("", ',', '.', true, true),
    "ZAR" to CurrencyRule("", ',', '.', true, true),
)

/** QMimeDatabase().mimeTypeForName(..).globPatterns() via Core::MimeTypeForName. */
fun MimeGlobPatterns(mime: String): List<String> {
    when (mime) {
        "image/webp" -> return listOf("*.webp")
        "application/x-tgsticker" -> return listOf("*.tgs")
        "application/x-tgwallpattern" -> return listOf("*.tgv")
        "application/x-tdesktop-theme", "application/x-tgtheme-tdesktop" -> return listOf("*.tdesktop-theme")
        "application/x-tdesktop-palette" -> return listOf("*.tdesktop-palette")
    }
    val name = if (mime == "audio/mpeg3") "audio/mp3" else mime
    val canonical = MimeTable.aliases[name] ?: name
    return MimeTable.firstGlob[canonical]?.let { listOf(it) } ?: emptyList()
}

/** base::FileNameFromUserString (the Linux flavor, which keeps the name). */
fun FileNameFromUserString(input: String): String {
    val bad = charArrayOf(
        '‎', '‏', '‪', '‫', '‭', '‮', '⁦', '⁧',
        '/', '\\', '<', '>', ':', '"', '|', '?', '*',
    )
    val sb = StringBuilder(input.length + 1)
    for (ch in input) sb.append(if (ch.code < 32 || ch in bad) '_' else ch)
    if (sb.isEmpty() || sb.endsWith(' ') || sb.endsWith('.')) sb.append('_')
    return sb.toString()
}

// ---- Countries (phone formatting) ------------------------------------------

class CallingCodeInfo(val callingCode: String, val prefixes: List<String>, val patterns: List<String>)
class CountryInfo(val name: String, val iso2: String, val alternativeName: String, val codes: List<CallingCodeInfo>, val isHidden: Boolean)

object Countries {
    @Volatile
    private var list: List<CountryInfo>? = null

    /** Same as Countries::Manager after help.getCountriesList. */
    fun setList(infos: List<CountryInfo>) {
        list = infos
    }

    fun list(): List<CountryInfo> = list ?: FallbackList

    /** CountriesInstance::format for the plain "formatted" result. */
    fun format(phoneNumber: String): String {
        if (phoneNumber.isEmpty()) return ""
        var bestCallingCode: CallingCodeInfo? = null
        var bestLength = 0
        for (country in list()) {
            for (callingCode in country.codes) {
                if (phoneNumber.startsWith(callingCode.callingCode)) {
                    val codeSize = callingCode.callingCode.length
                    val rest = phoneNumber.substring(codeSize)
                    for (prefix in callingCode.prefixes) {
                        if (codeSize + prefix.length > bestLength && rest.startsWith(prefix)) {
                            bestCallingCode = callingCode
                            bestLength = codeSize + prefix.length
                        }
                    }
                }
            }
        }
        val best = bestCallingCode ?: return phoneNumber
        val codeSize = best.callingCode.length
        val formattedPart = qMid(phoneNumber, codeSize)
        var formattedResult = formattedPart
        var maxMatchedDigits = 0
        for (pattern in best.patterns) {
            val result = StringBuilder()
            var currentPatternPos = 0
            var isFailedMatch = false
            var matchedDigits = 0
            for (c in formattedPart) {
                while (currentPatternPos < pattern.length &&
                    pattern[currentPatternPos] != 'X' &&
                    !pattern[currentPatternPos].isDigit()
                ) {
                    result.append(pattern[currentPatternPos])
                    currentPatternPos++
                }
                if (currentPatternPos >= pattern.length || pattern[currentPatternPos] == 'X') {
                    currentPatternPos++
                    result.append(c)
                } else {
                    if (c == pattern[currentPatternPos]) {
                        matchedDigits++
                        currentPatternPos++
                        result.append(c)
                    } else {
                        isFailedMatch = true
                        break
                    }
                }
            }
            if (!isFailedMatch && matchedDigits >= maxMatchedDigits) {
                maxMatchedDigits = matchedDigits
                formattedResult = result.toString()
            }
        }
        return "+" + best.callingCode + " " + formattedResult
    }

    private fun I(name: String, iso2: String, alt: String, codes: List<CallingCodeInfo>, hidden: Boolean) =
        CountryInfo(name, iso2, alt, codes, hidden)

    private fun C(code: String, prefixes: List<String>, patterns: List<String>) = CallingCodeInfo(code, prefixes, patterns)

    private val FallbackList: List<CountryInfo> by lazy {
        listOf(
            I("Andorra", "AD", "", listOf(C("376", listOf(), listOf("XX XX XX"))), false),
            I("United Arab Emirates", "AE", "", listOf(C("971", listOf(), listOf("XX XXX XXXX"))), false),
            I("Afghanistan", "AF", "", listOf(C("93", listOf(), listOf("XXX XXX XXX"))), false),
            I("Antigua & Barbuda", "AG", "", listOf(C("1268", listOf(), listOf("XXX XXXX"))), false),
            I("Anguilla", "AI", "", listOf(C("1264", listOf(), listOf("XXX XXXX"))), false),
            I("Albania", "AL", "", listOf(C("355", listOf(), listOf("XX XXX XXXX"))), false),
            I("Armenia", "AM", "", listOf(C("374", listOf(), listOf("XX XXX XXX"))), false),
            I("Angola", "AO", "", listOf(C("244", listOf(), listOf("XXX XXX XXX"))), false),
            I("Argentina", "AR", "", listOf(C("54", listOf(), listOf())), false),
            I("American Samoa", "AS", "", listOf(C("1684", listOf(), listOf("XXX XXXX"))), false),
            I("Austria", "AT", "", listOf(C("43", listOf(), listOf("X XXXXXXXX"))), false),
            I("Australia", "AU", "", listOf(C("61", listOf(), listOf("X XXXX XXXX"))), false),
            I("Aruba", "AW", "", listOf(C("297", listOf(), listOf("XXX XXXX"))), false),
            I("Azerbaijan", "AZ", "", listOf(C("994", listOf(), listOf("XX XXX XXXX"))), false),
            I("Bosnia & Herzegovina", "BA", "", listOf(C("387", listOf(), listOf("XX XXX XXX"))), false),
            I("Barbados", "BB", "", listOf(C("1246", listOf(), listOf("XXX XXXX"))), false),
            I("Bangladesh", "BD", "", listOf(C("880", listOf(), listOf("XX XXX XXX"))), false),
            I("Belgium", "BE", "", listOf(C("32", listOf(), listOf("XXX XX XX XX"))), false),
            I("Burkina Faso", "BF", "", listOf(C("226", listOf(), listOf("XX XX XX XX"))), false),
            I("Bulgaria", "BG", "", listOf(C("359", listOf(), listOf())), false),
            I("Bahrain", "BH", "", listOf(C("973", listOf(), listOf("XXXX XXXX"))), false),
            I("Burundi", "BI", "", listOf(C("257", listOf(), listOf("XX XX XXXX"))), false),
            I("Benin", "BJ", "", listOf(C("229", listOf(), listOf("XX XXX XXX"))), false),
            I("Bermuda", "BM", "", listOf(C("1441", listOf(), listOf("XXX XXXX"))), false),
            I("Brunei Darussalam", "BN", "", listOf(C("673", listOf(), listOf("XXX XXXX"))), false),
            I("Bolivia", "BO", "", listOf(C("591", listOf(), listOf("X XXX XXXX"))), false),
            I("Bonaire, Sint Eustatius & Saba", "BQ", "", listOf(C("599", listOf(), listOf())), false),
            I("Brazil", "BR", "", listOf(C("55", listOf(), listOf("XX XXXXX XXXX"))), false),
            I("Bahamas", "BS", "", listOf(C("1242", listOf(), listOf("XXX XXXX"))), false),
            I("Bhutan", "BT", "", listOf(C("975", listOf(), listOf("XX XXX XXX"))), false),
            I("Botswana", "BW", "", listOf(C("267", listOf(), listOf("XX XXX XXX"))), false),
            I("Belarus", "BY", "", listOf(C("375", listOf(), listOf("XX XXX XXXX"))), false),
            I("Belize", "BZ", "", listOf(C("501", listOf(), listOf())), false),
            I("Canada", "CA", "", listOf(C("1", listOf("403"), listOf("XXX XXX XXXX"))), false),
            I("Congo (Dem. Rep.)", "CD", "", listOf(C("243", listOf(), listOf("XX XXX XXXX"))), false),
            I("Central African Rep.", "CF", "", listOf(C("236", listOf(), listOf("XX XX XX XX"))), false),
            I("Congo (Rep.)", "CG", "", listOf(C("242", listOf(), listOf("XX XXX XXXX"))), false),
            I("Switzerland", "CH", "", listOf(C("41", listOf(), listOf("XX XXX XXXX"))), false),
            I("Côte d'Ivoire", "CI", "", listOf(C("225", listOf(), listOf("XX XX XX XXXX"))), false),
            I("Cook Islands", "CK", "", listOf(C("682", listOf(), listOf())), false),
            I("Chile", "CL", "", listOf(C("56", listOf(), listOf("X XXXX XXXX"))), false),
            I("Cameroon", "CM", "", listOf(C("237", listOf(), listOf("XXXX XXXX"))), false),
            I("China", "CN", "", listOf(C("86", listOf(), listOf("XXX XXXX XXXX"))), false),
            I("Colombia", "CO", "", listOf(C("57", listOf(), listOf("XXX XXX XXXX"))), false),
            I("Costa Rica", "CR", "", listOf(C("506", listOf(), listOf("XXXX XXXX"))), false),
            I("Cuba", "CU", "", listOf(C("53", listOf(), listOf("X XXX XXXX"))), false),
            I("Cape Verde", "CV", "", listOf(C("238", listOf(), listOf("XXX XXXX"))), false),
            I("Curaçao", "CW", "", listOf(C("599", listOf("9"), listOf())), false),
            I("Cyprus", "CY", "", listOf(C("357", listOf(), listOf("XXXX XXXX"))), false),
            I("Czech Republic", "CZ", "", listOf(C("420", listOf(), listOf("XXX XXX XXX"))), false),
            I("Germany", "DE", "", listOf(C("49", listOf(), listOf("XXXX XXXXXXX"))), false),
            I("Djibouti", "DJ", "", listOf(C("253", listOf(), listOf("XX XX XX XX"))), false),
            I("Denmark", "DK", "", listOf(C("45", listOf(), listOf("XXXX XXXX"))), false),
            I("Dominica", "DM", "", listOf(C("1767", listOf(), listOf("XXX XXXX"))), false),
            I("Dominican Rep.", "DO", "", listOf(C("1809", listOf(), listOf("XXX XXXX"))), false),
            I("Algeria", "DZ", "", listOf(C("213", listOf(), listOf("XXX XX XX XX"))), false),
            I("Ecuador", "EC", "", listOf(C("593", listOf(), listOf("XX XXX XXXX"))), false),
            I("Estonia", "EE", "", listOf(C("372", listOf(), listOf("XXXX XXXX"))), false),
            I("Egypt", "EG", "", listOf(C("20", listOf(), listOf("XX XXXX XXXX"))), false),
            I("Eritrea", "ER", "", listOf(C("291", listOf(), listOf("X XXX XXX"))), false),
            I("Spain", "ES", "", listOf(C("34", listOf(), listOf("XXX XXX XXX"))), false),
            I("Ethiopia", "ET", "", listOf(C("251", listOf(), listOf("XX XXX XXXX"))), false),
            I("Finland", "FI", "", listOf(C("358", listOf(), listOf())), false),
            I("Fiji", "FJ", "", listOf(C("679", listOf(), listOf("XXX XXXX"))), false),
            I("Falkland Islands", "FK", "", listOf(C("500", listOf(), listOf())), false),
            I("Micronesia", "FM", "", listOf(C("691", listOf(), listOf())), false),
            I("Faroe Islands", "FO", "", listOf(C("298", listOf(), listOf("XXX XXX"))), false),
            I("France", "FR", "", listOf(C("33", listOf(), listOf("X XX XX XX XX"))), false),
            I("Gabon", "GA", "", listOf(C("241", listOf(), listOf("X XX XX XX"))), false),
            I("United Kingdom", "GB", "", listOf(C("44", listOf(), listOf("XXXX XXXXXX"))), false),
            I("Grenada", "GD", "", listOf(C("1473", listOf(), listOf("XXX XXXX"))), false),
            I("Georgia", "GE", "", listOf(C("995", listOf(), listOf("XXX XXX XXX"))), false),
            I("French Guiana", "GF", "", listOf(C("594", listOf(), listOf())), false),
            I("Ghana", "GH", "", listOf(C("233", listOf(), listOf("XX XXX XXXX"))), false),
            I("Gibraltar", "GI", "", listOf(C("350", listOf(), listOf("XXXX XXXX"))), false),
            I("Greenland", "GL", "", listOf(C("299", listOf(), listOf("XXX XXX"))), false),
            I("Gambia", "GM", "", listOf(C("220", listOf(), listOf("XXX XXXX"))), false),
            I("Guinea", "GN", "", listOf(C("224", listOf(), listOf("XXX XXX XXX"))), false),
            I("Guadeloupe", "GP", "", listOf(C("590", listOf(), listOf("XXX XX XX XX"))), false),
            I("Equatorial Guinea", "GQ", "", listOf(C("240", listOf(), listOf("XXX XXX XXX"))), false),
            I("Greece", "GR", "", listOf(C("30", listOf(), listOf("XXX XXX XXXX"))), false),
            I("Guatemala", "GT", "", listOf(C("502", listOf(), listOf("X XXX XXXX"))), false),
            I("Guam", "GU", "", listOf(C("1671", listOf(), listOf("XXX XXXX"))), false),
            I("Guinea-Bissau", "GW", "", listOf(C("245", listOf(), listOf("XXX XXXX"))), false),
            I("Guyana", "GY", "", listOf(C("592", listOf(), listOf())), false),
            I("Hong Kong", "HK", "", listOf(C("852", listOf(), listOf("X XXX XXXX"))), false),
            I("Honduras", "HN", "", listOf(C("504", listOf(), listOf("XXXX XXXX"))), false),
            I("Croatia", "HR", "", listOf(C("385", listOf(), listOf("XX XXX XXX"))), false),
            I("Haiti", "HT", "", listOf(C("509", listOf(), listOf("XXXX XXXX"))), false),
            I("Hungary", "HU", "", listOf(C("36", listOf(), listOf("XXX XXX XXX"))), false),
            I("Indonesia", "ID", "", listOf(C("62", listOf(), listOf("XXX XXXXXX"))), false),
            I("Ireland", "IE", "", listOf(C("353", listOf(), listOf("XX XXX XXXX"))), false),
            I("Israel", "IL", "", listOf(C("972", listOf(), listOf("XX XXX XXXX"))), false),
            I("India", "IN", "", listOf(C("91", listOf(), listOf("XXXXX XXXXX"))), false),
            I("Diego Garcia", "IO", "", listOf(C("246", listOf(), listOf("XXX XXXX"))), false),
            I("Iraq", "IQ", "", listOf(C("964", listOf(), listOf("XXX XXX XXXX"))), false),
            I("Iran", "IR", "", listOf(C("98", listOf(), listOf("XXX XXX XXXX"))), false),
            I("Iceland", "IS", "", listOf(C("354", listOf(), listOf("XXX XXXX"))), false),
            I("Italy", "IT", "", listOf(C("39", listOf(), listOf("XXX XXX XXX"))), false),
            I("Jamaica", "JM", "", listOf(C("1876", listOf(), listOf("XXX XXXX"))), false),
            I("Jordan", "JO", "", listOf(C("962", listOf(), listOf("X XXXX XXXX"))), false),
            I("Japan", "JP", "", listOf(C("81", listOf(), listOf("XX XXXX XXXX"))), false),
            I("Kenya", "KE", "", listOf(C("254", listOf(), listOf("XXX XXX XXX"))), false),
            I("Kyrgyzstan", "KG", "", listOf(C("996", listOf(), listOf("XXX XXXXXX"))), false),
            I("Cambodia", "KH", "", listOf(C("855", listOf(), listOf("XX XXX XXX"))), false),
            I("Kiribati", "KI", "", listOf(C("686", listOf(), listOf("XXXX XXXX"))), false),
            I("Comoros", "KM", "", listOf(C("269", listOf(), listOf("XXX XXXX"))), false),
            I("Saint Kitts & Nevis", "KN", "", listOf(C("1869", listOf(), listOf("XXX XXXX"))), false),
            I("North Korea", "KP", "", listOf(C("850", listOf(), listOf())), false),
            I("South Korea", "KR", "", listOf(C("82", listOf(), listOf("XX XXXX XXX"))), false),
            I("Kuwait", "KW", "", listOf(C("965", listOf(), listOf("XXXX XXXX"))), false),
            I("Cayman Islands", "KY", "", listOf(C("1345", listOf(), listOf("XXX XXXX"))), false),
            I("Kazakhstan", "KZ", "", listOf(C("7", listOf("6"), listOf("XXX XXX XX XX"))), false),
            I("Laos", "LA", "", listOf(C("856", listOf(), listOf("XX XX XXX XXX"))), false),
            I("Lebanon", "LB", "", listOf(C("961", listOf(), listOf("XX XXX XXX"))), false),
            I("Saint Lucia", "LC", "", listOf(C("1758", listOf(), listOf("XXX XXXX"))), false),
            I("Liechtenstein", "LI", "", listOf(C("423", listOf(), listOf("XXX XXXX"))), false),
            I("Sri Lanka", "LK", "", listOf(C("94", listOf(), listOf("XX XXX XXXX"))), false),
            I("Liberia", "LR", "", listOf(C("231", listOf(), listOf("XX XXX XXXX"))), false),
            I("Lesotho", "LS", "", listOf(C("266", listOf(), listOf("XX XXX XXX"))), false),
            I("Lithuania", "LT", "", listOf(C("370", listOf(), listOf("XXX XXXXX"))), false),
            I("Luxembourg", "LU", "", listOf(C("352", listOf(), listOf("XXX XXX XXX"))), false),
            I("Latvia", "LV", "", listOf(C("371", listOf(), listOf("XXX XXXXX"))), false),
            I("Libya", "LY", "", listOf(C("218", listOf(), listOf("XX XXX XXXX"))), false),
            I("Morocco", "MA", "", listOf(C("212", listOf(), listOf("XX XXX XXXX"))), false),
            I("Monaco", "MC", "", listOf(C("377", listOf(), listOf("XXXX XXXX"))), false),
            I("Moldova", "MD", "", listOf(C("373", listOf(), listOf("XX XXX XXX"))), false),
            I("Montenegro", "ME", "", listOf(C("382", listOf(), listOf())), false),
            I("Madagascar", "MG", "", listOf(C("261", listOf(), listOf("XX XX XXX XX"))), false),
            I("Marshall Islands", "MH", "", listOf(C("692", listOf(), listOf())), false),
            I("North Macedonia", "MK", "", listOf(C("389", listOf(), listOf("XX XXX XXX"))), false),
            I("Mali", "ML", "", listOf(C("223", listOf(), listOf("XXXX XXXX"))), false),
            I("Myanmar", "MM", "", listOf(C("95", listOf(), listOf())), false),
            I("Mongolia", "MN", "", listOf(C("976", listOf(), listOf("XX XX XXXX"))), false),
            I("Macau", "MO", "", listOf(C("853", listOf(), listOf("XXXX XXXX"))), false),
            I("Northern Mariana Islands", "MP", "", listOf(C("1670", listOf(), listOf("XXX XXXX"))), false),
            I("Martinique", "MQ", "", listOf(C("596", listOf(), listOf())), false),
            I("Mauritania", "MR", "", listOf(C("222", listOf(), listOf("XXXX XXXX"))), false),
            I("Montserrat", "MS", "", listOf(C("1664", listOf(), listOf("XXX XXXX"))), false),
            I("Malta", "MT", "", listOf(C("356", listOf(), listOf("XX XX XX XX"))), false),
            I("Mauritius", "MU", "", listOf(C("230", listOf(), listOf("XXXX XXXX"))), false),
            I("Maldives", "MV", "", listOf(C("960", listOf(), listOf("XXX XXXX"))), false),
            I("Malawi", "MW", "", listOf(C("265", listOf(), listOf("XX XXX XXXX"))), false),
            I("Mexico", "MX", "", listOf(C("52", listOf(), listOf())), false),
            I("Malaysia", "MY", "", listOf(C("60", listOf(), listOf("XX XXXX XXXX"))), false),
            I("Mozambique", "MZ", "", listOf(C("258", listOf(), listOf("XX XXX XXXX"))), false),
            I("Namibia", "NA", "", listOf(C("264", listOf(), listOf("XX XXX XXXX"))), false),
            I("New Caledonia", "NC", "", listOf(C("687", listOf(), listOf())), false),
            I("Niger", "NE", "", listOf(C("227", listOf(), listOf("XX XX XX XX"))), false),
            I("Norfolk Island", "NF", "", listOf(C("672", listOf(), listOf())), false),
            I("Nigeria", "NG", "", listOf(C("234", listOf(), listOf("XX XXXX XXXX"))), false),
            I("Nicaragua", "NI", "", listOf(C("505", listOf(), listOf("XXXX XXXX"))), false),
            I("Netherlands", "NL", "", listOf(C("31", listOf(), listOf("X XX XX XX XX"))), false),
            I("Norway", "NO", "", listOf(C("47", listOf(), listOf("XXXX XXXX"))), false),
            I("Nepal", "NP", "", listOf(C("977", listOf(), listOf("XX XXXX XXXX"))), false),
            I("Nauru", "NR", "", listOf(C("674", listOf(), listOf())), false),
            I("Niue", "NU", "", listOf(C("683", listOf(), listOf())), false),
            I("New Zealand", "NZ", "", listOf(C("64", listOf(), listOf("XXXX XXXX"))), false),
            I("Oman", "OM", "", listOf(C("968", listOf(), listOf("XXXX XXXX"))), false),
            I("Panama", "PA", "", listOf(C("507", listOf(), listOf("XXXX XXXX"))), false),
            I("Peru", "PE", "", listOf(C("51", listOf(), listOf("XXX XXX XXX"))), false),
            I("French Polynesia", "PF", "", listOf(C("689", listOf(), listOf())), false),
            I("Papua New Guinea", "PG", "", listOf(C("675", listOf(), listOf())), false),
            I("Philippines", "PH", "", listOf(C("63", listOf(), listOf("XXX XXX XXXX"))), false),
            I("Pakistan", "PK", "", listOf(C("92", listOf(), listOf("XXX XXX XXXX"))), false),
            I("Poland", "PL", "", listOf(C("48", listOf(), listOf("XXX XXX XXX"))), false),
            I("Saint Pierre & Miquelon", "PM", "", listOf(C("508", listOf(), listOf())), false),
            I("Puerto Rico", "PR", "", listOf(C("1787", listOf(), listOf("XXX XXXX"))), false),
            I("Palestine", "PS", "", listOf(C("970", listOf(), listOf("XXX XX XXXX"))), false),
            I("Portugal", "PT", "", listOf(C("351", listOf(), listOf("XXX XXX XXX"))), false),
            I("Palau", "PW", "", listOf(C("680", listOf(), listOf())), false),
            I("Paraguay", "PY", "", listOf(C("595", listOf(), listOf("XXX XXX XXX"))), false),
            I("Qatar", "QA", "", listOf(C("974", listOf(), listOf("XX XXX XXX"))), false),
            I("Réunion", "RE", "", listOf(C("262", listOf(), listOf("XXX XXX XXX"))), false),
            I("Romania", "RO", "", listOf(C("40", listOf(), listOf("XXX XXX XXX"))), false),
            I("Serbia", "RS", "", listOf(C("381", listOf(), listOf("XX XXX XXXX"))), false),
            I("Russian Federation", "RU", "", listOf(C("7", listOf(), listOf("XXX XXX XXXX"))), false),
            I("Rwanda", "RW", "", listOf(C("250", listOf(), listOf("XXX XXX XXX"))), false),
            I("Saudi Arabia", "SA", "", listOf(C("966", listOf(), listOf("XX XXX XXXX"))), false),
            I("Solomon Islands", "SB", "", listOf(C("677", listOf(), listOf())), false),
            I("Seychelles", "SC", "", listOf(C("248", listOf(), listOf("X XX XX XX"))), false),
            I("Sudan", "SD", "", listOf(C("249", listOf(), listOf("XX XXX XXXX"))), false),
            I("Sweden", "SE", "", listOf(C("46", listOf(), listOf("XX XXX XXXX"))), false),
            I("Singapore", "SG", "", listOf(C("65", listOf(), listOf("XXXX XXXX"))), false),
            I("Saint Helena", "SH", "", listOf(C("247", listOf(), listOf())), false),
            I("Slovenia", "SI", "", listOf(C("386", listOf(), listOf("XX XXX XXX"))), false),
            I("Slovakia", "SK", "", listOf(C("421", listOf(), listOf("XXX XXX XXX"))), false),
            I("Sierra Leone", "SL", "", listOf(C("232", listOf(), listOf("XX XXX XXX"))), false),
            I("San Marino", "SM", "", listOf(C("378", listOf(), listOf("XXX XXX XXXX"))), false),
            I("Senegal", "SN", "", listOf(C("221", listOf(), listOf("XX XXX XXXX"))), false),
            I("Somalia", "SO", "", listOf(C("252", listOf(), listOf("XX XXX XXX"))), false),
            I("Suriname", "SR", "", listOf(C("597", listOf(), listOf("XXX XXXX"))), false),
            I("South Sudan", "SS", "", listOf(C("211", listOf(), listOf("XX XXX XXXX"))), false),
            I("São Tomé & Príncipe", "ST", "", listOf(C("239", listOf(), listOf("XX XXXXX"))), false),
            I("El Salvador", "SV", "", listOf(C("503", listOf(), listOf("XXXX XXXX"))), false),
            I("Sint Maarten", "SX", "", listOf(C("1721", listOf(), listOf("XXX XXXX"))), false),
            I("Syria", "SY", "", listOf(C("963", listOf(), listOf("XXX XXX XXX"))), false),
            I("Eswatini", "SZ", "", listOf(C("268", listOf(), listOf("XXXX XXXX"))), false),
            I("Turks & Caicos Islands", "TC", "", listOf(C("1649", listOf(), listOf("XXX XXXX"))), false),
            I("Chad", "TD", "", listOf(C("235", listOf(), listOf("XX XX XX XX"))), false),
            I("Togo", "TG", "", listOf(C("228", listOf(), listOf("XX XXX XXX"))), false),
            I("Thailand", "TH", "", listOf(C("66", listOf(), listOf("X XXXX XXXX"))), false),
            I("Tajikistan", "TJ", "", listOf(C("992", listOf(), listOf("XX XXX XXXX"))), false),
            I("Tokelau", "TK", "", listOf(C("690", listOf(), listOf())), false),
            I("Timor-Leste", "TL", "", listOf(C("670", listOf(), listOf())), false),
            I("Turkmenistan", "TM", "", listOf(C("993", listOf(), listOf("XX XXXXXX"))), false),
            I("Tunisia", "TN", "", listOf(C("216", listOf(), listOf("XX XXX XXX"))), false),
            I("Tonga", "TO", "", listOf(C("676", listOf(), listOf())), false),
            I("Turkey", "TR", "", listOf(C("90", listOf(), listOf("XXX XXX XXXX"))), false),
            I("Trinidad & Tobago", "TT", "", listOf(C("1868", listOf(), listOf("XXX XXXX"))), false),
            I("Tuvalu", "TV", "", listOf(C("688", listOf(), listOf())), false),
            I("Taiwan", "TW", "", listOf(C("886", listOf(), listOf("XXX XXX XXX"))), false),
            I("Tanzania", "TZ", "", listOf(C("255", listOf(), listOf("XX XXX XXXX"))), false),
            I("Ukraine", "UA", "", listOf(C("380", listOf(), listOf("XX XXX XX XX"))), false),
            I("Uganda", "UG", "", listOf(C("256", listOf(), listOf("XX XXX XXXX"))), false),
            I("USA", "US", "United States of America", listOf(C("1", listOf(), listOf("XXX XXX XXXX"))), false),
            I("Uruguay", "UY", "", listOf(C("598", listOf(), listOf("X XXX XXXX"))), false),
            I("Uzbekistan", "UZ", "", listOf(C("998", listOf(), listOf("XX XXX XX XX"))), false),
            I("Saint Vincent & the Grenadines", "VC", "", listOf(C("1784", listOf(), listOf("XXX XXXX"))), false),
            I("Venezuela", "VE", "", listOf(C("58", listOf(), listOf("XXX XXX XXXX"))), false),
            I("British Virgin Islands", "VG", "", listOf(C("1284", listOf(), listOf("XXX XXXX"))), false),
            I("US Virgin Islands", "VI", "", listOf(C("1340", listOf(), listOf("XXX XXXX"))), false),
            I("Vietnam", "VN", "", listOf(C("84", listOf(), listOf())), false),
            I("Vanuatu", "VU", "", listOf(C("678", listOf(), listOf())), false),
            I("Wallis & Futuna", "WF", "", listOf(C("681", listOf(), listOf())), false),
            I("Samoa", "WS", "", listOf(C("685", listOf(), listOf())), false),
            I("Kosovo", "XK", "", listOf(C("383", listOf(), listOf("XXXX XXXX"))), false),
            I("Yemen", "YE", "", listOf(C("967", listOf(), listOf("XXX XXX XXX"))), false),
            I("South Africa", "ZA", "", listOf(C("27", listOf(), listOf("XX XXX XXXX"))), false),
            I("Zambia", "ZM", "", listOf(C("260", listOf(), listOf("XX XXX XXXX"))), false),
            I("Zimbabwe", "ZW", "", listOf(C("263", listOf(), listOf("XX XXX XXXX"))), false),        )
    }
}

fun FormatPhone(input: String): String {
    if (input.isEmpty()) return ""
    if (input[0] == '0') return input
    val phone = input.replace(" ", "")
    return Countries.format(if (phone.isNotEmpty() && phone[0] == '+') phone.substring(1) else phone)
}
