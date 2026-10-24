package org.tgbackup.mtproto

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object Crypto {
    val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun sha1(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-1")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    fun sha512(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-512")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    fun xor(a: ByteArray, b: ByteArray): ByteArray = ByteArray(a.size) { (a[it].toInt() xor b[it].toInt()).toByte() }

    /** AES-256 in IGE mode, as MTProto uses it. [iv] is 32 bytes. */
    fun igeEncrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray = ige(data, key, iv, true)

    fun igeDecrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray = ige(data, key, iv, false)

    private fun ige(data: ByteArray, key: ByteArray, iv: ByteArray, encrypt: Boolean): ByteArray {
        require(data.size % 16 == 0) { "IGE data must be a multiple of 16 bytes" }
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        val out = ByteArray(data.size)
        // For encryption x = previous ciphertext, y = previous plaintext; swapped for decryption.
        val x = if (encrypt) iv.copyOfRange(0, 16) else iv.copyOfRange(16, 32)
        val y = if (encrypt) iv.copyOfRange(16, 32) else iv.copyOfRange(0, 16)
        val block = ByteArray(16)
        val tmp = ByteArray(16)
        var off = 0
        while (off < data.size) {
            for (i in 0 until 16) block[i] = (data[off + i].toInt() xor x[i].toInt()).toByte()
            cipher.update(block, 0, 16, tmp, 0)
            for (i in 0 until 16) {
                val o = (tmp[i].toInt() xor y[i].toInt()).toByte()
                out[off + i] = o
            }
            System.arraycopy(out, off, x, 0, 16)
            System.arraycopy(data, off, y, 0, 16)
            off += 16
        }
        return out
    }

    fun aesCtr(key: ByteArray, iv: ByteArray): Cipher =
        Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }

    fun toUnsignedBytes(v: BigInteger, size: Int): ByteArray {
        val raw = v.toByteArray()
        return when {
            raw.size == size -> raw
            raw.size > size -> raw.copyOfRange(raw.size - size, raw.size)
            else -> ByteArray(size - raw.size) + raw
        }
    }

    fun unsigned(b: ByteArray): BigInteger = BigInteger(1, b)

    /** Splits the pq product (< 2^63) into its two prime factors, smaller first. */
    fun factorize(pq: Long): Pair<Long, Long> {
        if (pq % 2 == 0L) return 2L to pq / 2
        val n = BigInteger.valueOf(pq)
        val rnd = java.util.Random()
        while (true) {
            var y = BigInteger.valueOf((rnd.nextLong() and Long.MAX_VALUE) % (pq - 1) + 1)
            val c = BigInteger.valueOf((rnd.nextLong() and Long.MAX_VALUE) % (pq - 1) + 1)
            val m = 128
            var g = BigInteger.ONE
            var r = 1
            var q = BigInteger.ONE
            var x = y
            var ys = y
            while (g == BigInteger.ONE) {
                x = y
                repeat(r) { y = y.multiply(y).add(c).mod(n) }
                var k = 0
                while (k < r && g == BigInteger.ONE) {
                    ys = y
                    for (i in 0 until minOf(m, r - k)) {
                        y = y.multiply(y).add(c).mod(n)
                        q = q.multiply(x.subtract(y).abs()).mod(n)
                    }
                    g = q.gcd(n)
                    k += m
                }
                r *= 2
            }
            if (g == n) {
                do {
                    ys = ys.multiply(ys).add(c).mod(n)
                    g = x.subtract(ys).abs().gcd(n)
                } while (g == BigInteger.ONE)
            }
            if (g != n) {
                val a = g.toLong()
                val b = pq / a
                return minOf(a, b) to maxOf(a, b)
            }
        }
    }
}

internal class RsaKey(val n: BigInteger, val e: BigInteger) {
    val fingerprint: Long

    init {
        val w = org.tgbackup.mtproto.tl.TlWriter()
        w.bytes(Crypto.toUnsignedBytes(n, (n.bitLength() + 7) / 8))
        w.bytes(Crypto.toUnsignedBytes(e, (e.bitLength() + 7) / 8))
        val h = Crypto.sha1(w.toByteArray())
        var fp = 0L
        for (i in 7 downTo 0) fp = (fp shl 8) or (h[12 + i].toLong() and 0xff)
        fingerprint = fp
    }

    fun encrypt(data: ByteArray): ByteArray = Crypto.toUnsignedBytes(Crypto.unsigned(data).modPow(e, n), 256)

    companion object {
        /** Parses a PKCS#1 "BEGIN RSA PUBLIC KEY" PEM. */
        fun fromPem(pem: String): RsaKey {
            val b64 = pem.lines().filter { !it.startsWith("-----") }.joinToString("").trim()
            val der = java.util.Base64.getDecoder().decode(b64)
            var p = 0
            fun readLen(): Int {
                var l = der[p++].toInt() and 0xff
                if (l and 0x80 != 0) {
                    val cnt = l and 0x7f
                    l = 0
                    repeat(cnt) { l = (l shl 8) or (der[p++].toInt() and 0xff) }
                }
                return l
            }
            fun readInt(): BigInteger {
                check(der[p++].toInt() == 0x02)
                val l = readLen()
                val v = BigInteger(1, der.copyOfRange(p, p + l))
                p += l
                return v
            }
            check(der[p++].toInt() == 0x30)
            readLen()
            val n = readInt()
            val e = readInt()
            return RsaKey(n, e)
        }

        val production: List<RsaKey> by lazy {
            listOf(
                fromPem(
                    """
                    -----BEGIN RSA PUBLIC KEY-----
                    MIIBCgKCAQEA6LszBcC1LGzyr992NzE0ieY+BSaOW622Aa9Bd4ZHLl+TuFQ4lo4g
                    5nKaMBwK/BIb9xUfg0Q29/2mgIR6Zr9krM7HjuIcCzFvDtr+L0GQjae9H0pRB2OO
                    62cECs5HKhT5DZ98K33vmWiLowc621dQuwKWSQKjWf50XYFw42h21P2KXUGyp2y/
                    +aEyZ+uVgLLQbRA1dEjSDZ2iGRy12Mk5gpYc397aYp438fsJoHIgJ2lgMv5h7WY9
                    t6N/byY9Nw9p21Og3AoXSL2q/2IJ1WRUhebgAdGVMlV1fkuOQoEzR7EdpqtQD9Cs
                    5+bfo3Nhmcyvk5ftB0WkJ9z6bNZ7yxrP8wIDAQAB
                    -----END RSA PUBLIC KEY-----
                    """.trimIndent(),
                ),
            )
        }
    }
}

internal object DhPrime {
    val good: BigInteger = BigInteger(
        "c71caeb9c6b1c9048e6c522f70f13f73980d40238e3e21c14934d037563d930f48198a0aa7c14058229493d22530f4db" +
            "fa336f6e0ac925139543aed44cce7c3720fd51f69458705ac68cd4fe6b6b13abdc9746512969328454f18faf8c595f64" +
            "2477fe96bb2a941d5bcd1d4ac8cc49880708fa9b378e3c4f3a9060bee67cf9a4a4a695811051907e162753b56b0f6b41" +
            "0dba74d8a84b2a14b3144e0ef1284754fd17ed950d5965b4b9dd46582db1178d169c6bc465b0d6ff9ca3928fef5b9ae4" +
            "e418fc15e83ebea0f87fa9ff5eed70050ded2849f47bf959d956850ce929851f0d8115f635b105ee2e4e15d04b2454bf" +
            "6f4fadf034b10403119cd8e3b92fcc5b",
        16,
    )

    /** Same checks as tdesktop's IsPrimeAndGood. */
    fun isGood(p: BigInteger, g: Int): Boolean {
        if (p == good) return g in 2..7 && gMatches(p, g)
        if (p.bitLength() != 2048 || !p.isProbablePrime(64)) return false
        if (!gMatches(p, g)) return false
        return p.subtract(BigInteger.ONE).shiftRight(1).isProbablePrime(64)
    }

    private fun gMatches(p: BigInteger, g: Int): Boolean = when (g) {
        2 -> p.mod(BigInteger.valueOf(8)).toInt() == 7
        3 -> p.mod(BigInteger.valueOf(3)).toInt() == 2
        4 -> true
        5 -> p.mod(BigInteger.valueOf(5)).toInt().let { it == 1 || it == 4 }
        6 -> p.mod(BigInteger.valueOf(24)).toInt().let { it == 19 || it == 23 }
        7 -> p.mod(BigInteger.valueOf(7)).toInt().let { it == 3 || it == 5 || it == 6 }
        else -> false
    }

    /** 1 < x < p - 1 and 2^{2048-64} < x < p - 2^{2048-64}. */
    fun isGoodModExp(x: BigInteger, p: BigInteger): Boolean {
        if (x.signum() <= 0 || x >= p.subtract(BigInteger.ONE)) return false
        val bound = BigInteger.ONE.shiftLeft(2048 - 64)
        return x > bound && x < p.subtract(bound)
    }
}
