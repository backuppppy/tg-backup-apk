package org.tgbackup.mtproto

import org.tgbackup.mtproto.tl.MTPDclient_DH_inner_data
import org.tgbackup.mtproto.tl.MTPDdh_gen_fail
import org.tgbackup.mtproto.tl.MTPDdh_gen_ok
import org.tgbackup.mtproto.tl.MTPDdh_gen_retry
import org.tgbackup.mtproto.tl.MTPDp_q_inner_data_dc
import org.tgbackup.mtproto.tl.MTPDresPQ
import org.tgbackup.mtproto.tl.MTPDserver_DH_inner_data
import org.tgbackup.mtproto.tl.MTPDserver_DH_params_fail
import org.tgbackup.mtproto.tl.MTPDserver_DH_params_ok
import org.tgbackup.mtproto.tl.MTPReq_DH_params
import org.tgbackup.mtproto.tl.MTPReq_pq_multi
import org.tgbackup.mtproto.tl.MTPSet_client_DH_params
import org.tgbackup.mtproto.tl.TlFunction
import org.tgbackup.mtproto.tl.TlReader
import org.tgbackup.mtproto.tl.TlWriter
import java.math.BigInteger

class AuthKey(val key: ByteArray) {
    val id: Long = run {
        val h = Crypto.sha1(key)
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (h[12 + i].toLong() and 0xff)
        v
    }
}

internal class AuthKeyResult(val key: AuthKey, val serverSalt: Long, val timeOffset: Int)

/** Creates a permanent auth key with the DH exchange from the MTProto spec. */
internal class AuthKeyCreator(private val tcp: ObfuscatedTcp, private val dcId: Int) {
    private var lastMsgId = 0L

    private fun <R> plain(fn: TlFunction<R>): R {
        val body = fn.toBytes()
        val w = TlWriter()
        w.long(0)
        w.long(nextMsgId())
        w.int(body.size)
        w.raw(body)
        tcp.send(w.toByteArray())
        val resp = tcp.receive()
        val r = TlReader(resp)
        val keyId = r.long()
        if (keyId != 0L) throw MtpException("unexpected encrypted message during key exchange")
        r.long()
        val len = r.int()
        if (len > r.remaining) throw MtpException("bad plain message length")
        return fn.parseResult(r)
    }

    private fun nextMsgId(): Long {
        val now = System.currentTimeMillis()
        var id = ((now / 1000) shl 32) or (((now % 1000) * 4294967L) and 0xfffffffcL)
        if (id <= lastMsgId) id = lastMsgId + 4
        lastMsgId = id
        return id
    }

    fun create(): AuthKeyResult {
        val nonce = Crypto.randomBytes(16)
        val resPq = plain(MTPReq_pq_multi(nonce)) as MTPDresPQ
        if (!resPq.vnonce.contentEquals(nonce)) throw MtpException("nonce mismatch")
        val key = RsaKey.production.firstOrNull { k -> resPq.vserver_public_key_fingerprints.any { it == k.fingerprint } }
            ?: throw MtpException("no known RSA key in ${resPq.vserver_public_key_fingerprints}")

        val pq = BigInteger(1, resPq.vpq).toLong()
        val (p, q) = Crypto.factorize(pq)
        val pBytes = Crypto.toUnsignedBytes(BigInteger.valueOf(p), 4)
        val qBytes = Crypto.toUnsignedBytes(BigInteger.valueOf(q), 4)
        val newNonce = Crypto.randomBytes(32)
        val inner = MTPDp_q_inner_data_dc(
            vpq = resPq.vpq,
            vp = pBytes,
            vq = qBytes,
            vnonce = nonce,
            vserver_nonce = resPq.vserver_nonce,
            vnew_nonce = newNonce,
            vdc = dcId,
        )
        val encrypted = rsaPad(inner.toBytes(), key)
        val dhParams = plain(
            MTPReq_DH_params(
                vnonce = nonce,
                vserver_nonce = resPq.vserver_nonce,
                vp = pBytes,
                vq = qBytes,
                vpublic_key_fingerprint = key.fingerprint,
                vencrypted_data = encrypted,
            ),
        )
        val ok = when (dhParams) {
            is MTPDserver_DH_params_ok -> dhParams
            is MTPDserver_DH_params_fail -> throw MtpException("server_DH_params_fail")
        }
        val serverNonce = resPq.vserver_nonce
        val tmpKey = Crypto.sha1(newNonce, serverNonce) + Crypto.sha1(serverNonce, newNonce).copyOfRange(0, 12)
        val tmpIv = Crypto.sha1(serverNonce, newNonce).copyOfRange(12, 20) + Crypto.sha1(newNonce, newNonce) + newNonce.copyOfRange(0, 4)
        val answerWithHash = Crypto.igeDecrypt(ok.vencrypted_answer, tmpKey, tmpIv)
        val ar = TlReader(answerWithHash, 20)
        val dhInner = ar.obj() as MTPDserver_DH_inner_data
        val answerLen = ar.position - 20
        if (!Crypto.sha1(answerWithHash.copyOfRange(20, 20 + answerLen)).contentEquals(answerWithHash.copyOfRange(0, 20))) {
            throw MtpException("DH answer hash mismatch")
        }
        if (!dhInner.vnonce.contentEquals(nonce) || !dhInner.vserver_nonce.contentEquals(serverNonce)) {
            throw MtpException("DH inner nonce mismatch")
        }
        val dhPrime = BigInteger(1, dhInner.vdh_prime)
        val gA = BigInteger(1, dhInner.vg_a)
        if (!DhPrime.isGood(dhPrime, dhInner.vg)) throw MtpException("bad DH prime")
        if (!DhPrime.isGoodModExp(gA, dhPrime)) throw MtpException("bad g_a")
        val timeOffset = dhInner.vserver_time - (System.currentTimeMillis() / 1000).toInt()

        val g = BigInteger.valueOf(dhInner.vg.toLong())
        var retryId = 0L
        while (true) {
            val b = BigInteger(1, Crypto.randomBytes(256))
            val gB = g.modPow(b, dhPrime)
            if (!DhPrime.isGoodModExp(gB, dhPrime)) continue
            val authKey = Crypto.toUnsignedBytes(gA.modPow(b, dhPrime), 256)
            val clientInner = MTPDclient_DH_inner_data(
                vnonce = nonce,
                vserver_nonce = serverNonce,
                vretry_id = retryId,
                vg_b = Crypto.toUnsignedBytes(gB, 256),
            ).toBytes()
            var dataWithHash = Crypto.sha1(clientInner) + clientInner
            val pad = (16 - dataWithHash.size % 16) % 16
            dataWithHash += Crypto.randomBytes(pad)
            val enc = Crypto.igeEncrypt(dataWithHash, tmpKey, tmpIv)
            val answer = plain(MTPSet_client_DH_params(nonce, serverNonce, enc))
            val auxHash = Crypto.sha1(authKey).copyOfRange(0, 8)
            fun expect(n: Int, got: ByteArray) {
                val h = Crypto.sha1(newNonce, byteArrayOf(n.toByte()), auxHash).copyOfRange(4, 20)
                if (!h.contentEquals(got)) throw MtpException("new_nonce_hash$n mismatch")
            }
            when (answer) {
                is MTPDdh_gen_ok -> {
                    expect(1, answer.vnew_nonce_hash1)
                    val salt = Crypto.xor(newNonce.copyOfRange(0, 8), serverNonce.copyOfRange(0, 8))
                    var s = 0L
                    for (i in 7 downTo 0) s = (s shl 8) or (salt[i].toLong() and 0xff)
                    return AuthKeyResult(AuthKey(authKey), s, timeOffset)
                }
                is MTPDdh_gen_retry -> {
                    expect(2, answer.vnew_nonce_hash2)
                    var v = 0L
                    for (i in 7 downTo 0) v = (v shl 8) or (auxHash[i].toLong() and 0xff)
                    retryId = v
                }
                is MTPDdh_gen_fail -> throw MtpException("dh_gen_fail")
            }
        }
    }

    /** RSA_PAD from the MTProto spec (used with req_DH_params). */
    private fun rsaPad(data: ByteArray, key: RsaKey): ByteArray {
        require(data.size <= 144)
        val dataWithPadding = data + Crypto.randomBytes(192 - data.size)
        val reversed = dataWithPadding.reversedArray()
        while (true) {
            val tempKey = Crypto.randomBytes(32)
            val dataWithHash = reversed + Crypto.sha256(tempKey, dataWithPadding)
            val aesEncrypted = Crypto.igeEncrypt(dataWithHash, tempKey, ByteArray(32))
            val tempKeyXor = Crypto.xor(tempKey, Crypto.sha256(aesEncrypted))
            val keyAesEncrypted = tempKeyXor + aesEncrypted
            if (BigInteger(1, keyAesEncrypted) >= key.n) continue
            return key.encrypt(keyAesEncrypted)
        }
    }
}
