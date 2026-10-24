package org.tgbackup.mtproto

/** Base64 without java.util.Base64 (Android API < 26). */
object B64 {
    private const val STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** QByteArray::toBase64(Base64UrlEncoding | OmitTrailingEquals). */
    fun urlNoPad(data: ByteArray): String = encode(data, URL, false)

    fun encode(data: ByteArray, alphabet: String = STD, pad: Boolean = true): String {
        val sb = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < data.size) {
            val n = ((data[i].toInt() and 0xff) shl 16) or ((data[i + 1].toInt() and 0xff) shl 8) or (data[i + 2].toInt() and 0xff)
            sb.append(alphabet[n shr 18 and 63]).append(alphabet[n shr 12 and 63]).append(alphabet[n shr 6 and 63]).append(alphabet[n and 63])
            i += 3
        }
        val rest = data.size - i
        if (rest == 1) {
            val n = (data[i].toInt() and 0xff) shl 16
            sb.append(alphabet[n shr 18 and 63]).append(alphabet[n shr 12 and 63])
            if (pad) sb.append("==")
        } else if (rest == 2) {
            val n = ((data[i].toInt() and 0xff) shl 16) or ((data[i + 1].toInt() and 0xff) shl 8)
            sb.append(alphabet[n shr 18 and 63]).append(alphabet[n shr 12 and 63]).append(alphabet[n shr 6 and 63])
            if (pad) sb.append('=')
        }
        return sb.toString()
    }

    fun decode(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in text) {
            val v = when (c) {
                in 'A'..'Z' -> c - 'A'
                in 'a'..'z' -> c - 'a' + 26
                in '0'..'9' -> c - '0' + 52
                '+', '-' -> 62
                '/', '_' -> 63
                else -> continue
            }
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xff)
            }
        }
        return out.toByteArray()
    }
}
