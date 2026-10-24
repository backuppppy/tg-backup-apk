package org.tgbackup.mtproto

import kotlinx.coroutines.runBlocking
import org.tgbackup.mtproto.tl.MTPhelp_GetNearestDc
import kotlin.test.Test

/** Talks to the real Telegram servers; run with -Dlive=true. */
class LiveHandshakeTest {
    @Test
    fun handshakeAndEncryptedCall() {
        if (System.getProperty("live") != "true") return
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val client = MtpClient(MtpStorage(java.io.File(dir, "s.properties")), AppInfo(1, "test", "1", "1"), "x", ::println)
        runBlocking {
            val r = runCatching { client.invoke(MTPhelp_GetNearestDc()) }
            println("RESULT: " + (r.exceptionOrNull()?.let { "error $it" } ?: r.getOrNull()?.javaClass?.simpleName))
        }
        client.close()
    }
}
