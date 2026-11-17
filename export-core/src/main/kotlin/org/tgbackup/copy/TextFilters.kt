package org.tgbackup.copy

/** True when [text] has at least one Arabic-script letter. Arabic punctuation and digits alone don't count. */
fun containsArabic(text: String): Boolean {
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        if (Character.isLetter(cp) && isArabicBlock(cp)) return true
        i += Character.charCount(cp)
    }
    return false
}

private fun isArabicBlock(cp: Int): Boolean =
    cp in 0x0600..0x06FF || cp in 0x0750..0x077F || cp in 0x08A0..0x08FF ||
        cp in 0xFB50..0xFDFF || cp in 0xFE70..0xFEFF

/**
 * [text] cut so the head has at most [max] UTF-16 units: at the last whitespace in its second half, else at [max]
 * (never inside a surrogate pair). The rest is null when nothing is left; only the whitespace at the cut is dropped.
 */
fun splitText(text: String, max: Int): Pair<String, String?> {
    if (text.length <= max) return text to null
    var headEnd = (max downTo max / 2).firstOrNull { it < text.length && text[it].isWhitespace() } ?: max
    if (headEnd == max && headEnd > 0 && text[headEnd - 1].isHighSurrogate()) headEnd--
    val rest = text.substring(headEnd).trimStart()
    return text.substring(0, headEnd).trimEnd() to rest.ifEmpty { null }
}

/** [text] in parts of at most [max] UTF-16 units each (see [splitText]). */
fun splitTextAll(text: String, max: Int): List<String> {
    val out = ArrayList<String>()
    var left: String? = text
    while (left != null) {
        val (head, rest) = splitText(left, max)
        out += head
        left = rest
    }
    return out
}
