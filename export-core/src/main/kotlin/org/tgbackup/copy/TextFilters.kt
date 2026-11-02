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
