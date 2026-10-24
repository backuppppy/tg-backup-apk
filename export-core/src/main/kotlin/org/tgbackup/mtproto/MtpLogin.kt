package org.tgbackup.mtproto

import kotlinx.coroutines.delay
import org.tgbackup.mtproto.tl.MTPDaccount_password
import org.tgbackup.mtproto.tl.MTPDauth_authorization
import org.tgbackup.mtproto.tl.MTPDauth_authorizationSignUpRequired
import org.tgbackup.mtproto.tl.MTPDauth_loginToken
import org.tgbackup.mtproto.tl.MTPDauth_loginTokenMigrateTo
import org.tgbackup.mtproto.tl.MTPDauth_loginTokenSuccess
import org.tgbackup.mtproto.tl.MTPDinputCheckPasswordSRP
import org.tgbackup.mtproto.tl.MTPDpasswordKdfAlgoSHA256SHA256PBKDF2HMACSHA512iter100000SHA256ModPow
import org.tgbackup.mtproto.tl.MTPDuser
import org.tgbackup.mtproto.tl.MTPaccount_GetPassword
import org.tgbackup.mtproto.tl.MTPauth_Authorization
import org.tgbackup.mtproto.tl.MTPauth_CheckPassword
import org.tgbackup.mtproto.tl.MTPauth_ExportLoginToken
import org.tgbackup.mtproto.tl.MTPauth_ImportLoginToken
import org.tgbackup.mtproto.tl.MTPauth_LoginToken
import org.tgbackup.mtproto.tl.MTPusers_GetUsers
import org.tgbackup.mtproto.tl.MTPDinputUserSelf
import java.math.BigInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class LoginException(message: String) : Exception(message)

/**
 * Logs the export session in with a QR login token that an already logged
 * in client confirms (TG Backup's TDLib session), plus the 2FA password if
 * the account has one.
 */
class MtpLogin(private val client: MtpClient) {

    val isLoggedIn: Boolean get() = client.storage.userId != 0L

    /** Checks that the stored session still works; clears it if not. */
    suspend fun checkSession(): Boolean {
        if (!isLoggedIn) return false
        return try {
            val users = client.invoke(MTPusers_GetUsers(listOf(MTPDinputUserSelf)))
            users.any { it is MTPDuser && it.is_self }
        } catch (e: RpcError) {
            if (e.code == 401) {
                client.storage.userId = 0
                false
            } else throw e
        }
    }

    /**
     * [confirm] receives a "tg://login?token=..." link and must have it
     * accepted by a logged in session. [password] is asked when 2FA is on.
     */
    suspend fun loginWithQr(
        expectedUserId: Long,
        confirm: suspend (String) -> Unit,
        password: suspend (hint: String) -> String,
    ) {
        client.loadConfig()
        var confirmed = false
        repeat(20) {
            val result = try {
                client.invoke(MTPauth_ExportLoginToken(client.app.apiId, client.apiHash, emptyList()))
            } catch (e: RpcError) {
                if (e.type == "SESSION_PASSWORD_NEEDED") {
                    checkPassword(password)
                    finish(expectedUserId)
                    return
                }
                throw e
            }
            when (val r = handleToken(result, password)) {
                is MTPDauth_loginToken -> {
                    if (!confirmed) {
                        val link = "tg://login?token=" + org.tgbackup.mtproto.B64.urlNoPad(r.vtoken)
                        confirm(link)
                        confirmed = true
                    }
                    delay(1500)
                }
                null -> {
                    finish(expectedUserId)
                    return
                }
                else -> Unit
            }
        }
        throw LoginException("QR login was not confirmed")
    }

    /** Returns the token to wait on, or null when logged in. */
    private suspend fun handleToken(result: MTPauth_LoginToken, password: suspend (String) -> String): MTPDauth_loginToken? {
        return when (result) {
            is MTPDauth_loginToken -> result
            is MTPDauth_loginTokenMigrateTo -> {
                client.setMainDc(result.vdc_id)
                val imported = try {
                    client.invoke(MTPauth_ImportLoginToken(result.vtoken))
                } catch (e: RpcError) {
                    if (e.type == "SESSION_PASSWORD_NEEDED") {
                        checkPassword(password)
                        return null
                    }
                    throw e
                }
                handleToken(imported, password)
            }
            is MTPDauth_loginTokenSuccess -> {
                authorized(result.vauthorization)
                null
            }
        }
    }

    private fun authorized(authorization: MTPauth_Authorization) {
        when (authorization) {
            is MTPDauth_authorization -> {
                val user = authorization.vuser as? MTPDuser ?: throw LoginException("Unexpected user")
                client.storage.userId = user.vid
            }
            is MTPDauth_authorizationSignUpRequired -> throw LoginException("Sign up required")
        }
    }

    private suspend fun finish(expectedUserId: Long) {
        if (client.storage.userId == 0L) {
            val users = client.invoke(MTPusers_GetUsers(listOf(MTPDinputUserSelf)))
            val self = users.firstOrNull { it is MTPDuser && it.is_self } as? MTPDuser ?: throw LoginException("No self user")
            client.storage.userId = self.vid
        }
        if (expectedUserId != 0L && client.storage.userId != expectedUserId) {
            client.storage.userId = 0
            throw LoginException("Logged in to a different account")
        }
    }

    private suspend fun checkPassword(password: suspend (String) -> String) {
        while (true) {
            val state = client.invoke(MTPaccount_GetPassword()) as MTPDaccount_password
            val entered = password(state.vhint ?: "")
            val check = Srp.compute(state, entered)
            try {
                authorized(client.invoke(MTPauth_CheckPassword(check)))
                return
            } catch (e: RpcError) {
                if (e.type == "PASSWORD_HASH_INVALID") continue
                throw e
            }
        }
    }
}

/** SRP-2048 password check from https://core.telegram.org/api/srp */
internal object Srp {
    private fun sha256(vararg parts: ByteArray) = Crypto.sha256(*parts)

    private fun sh(data: ByteArray, salt: ByteArray) = sha256(salt, data, salt)

    private fun pbkdf2Sha512(password: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(password, "HmacSHA512"))
        mac.update(salt)
        mac.update(byteArrayOf(0, 0, 0, 1))
        var u = mac.doFinal()
        val result = u.copyOf()
        for (i in 1 until iterations) {
            u = mac.doFinal(u)
            for (j in result.indices) result[j] = (result[j].toInt() xor u[j].toInt()).toByte()
        }
        return result
    }

    private fun pad(b: BigInteger) = Crypto.toUnsignedBytes(b, 256)

    fun compute(state: MTPDaccount_password, password: String): MTPDinputCheckPasswordSRP {
        val algo = state.vcurrent_algo as? MTPDpasswordKdfAlgoSHA256SHA256PBKDF2HMACSHA512iter100000SHA256ModPow
            ?: throw LoginException("Unsupported password algorithm")
        val srpB = state.vsrp_B ?: throw LoginException("No srp_B")
        val srpId = state.vsrp_id ?: throw LoginException("No srp_id")
        val g = BigInteger.valueOf(algo.vg.toLong())
        val p = BigInteger(1, algo.vp)
        val gB = BigInteger(1, srpB)
        if (!DhPrime.isGood(p, algo.vg)) throw LoginException("Bad password prime")

        val ph1 = sh(sh(password.toByteArray(Charsets.UTF_8), algo.vsalt1), algo.vsalt2)
        val ph2 = sh(pbkdf2Sha512(ph1, algo.vsalt1, 100000), algo.vsalt2)
        val x = BigInteger(1, ph2)
        val v = g.modPow(x, p)
        val k = BigInteger(1, sha256(pad(p), pad(g)))
        val kv = k.multiply(v).mod(p)
        while (true) {
            val a = BigInteger(1, Crypto.randomBytes(256))
            val gA = g.modPow(a, p)
            if (!DhPrime.isGoodModExp(gA, p)) continue
            val u = BigInteger(1, sha256(pad(gA), pad(gB)))
            if (u.signum() == 0) continue
            var t = gB.subtract(kv).mod(p)
            if (t.signum() < 0) t = t.add(p)
            val sA = t.modPow(a.add(u.multiply(x)), p)
            val kA = sha256(pad(sA))
            val m1 = sha256(
                Crypto.xor(sha256(pad(p)), sha256(pad(g))),
                sha256(algo.vsalt1),
                sha256(algo.vsalt2),
                pad(gA),
                pad(gB),
                kA,
            )
            return MTPDinputCheckPasswordSRP(srpId, pad(gA), m1)
        }
    }
}
