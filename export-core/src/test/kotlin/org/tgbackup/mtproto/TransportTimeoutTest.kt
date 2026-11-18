package org.tgbackup.mtproto

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A dead or silent DC must end the export session's connect and key creation in time, never hang them. */
class TransportTimeoutTest {

    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

    private fun session(addresses: List<DcOption>, log: (String) -> Unit, attempts: Int = 8) = MtpSession(
        addresses, AuthKey(ByteArray(256)), 0L, MtpClock(), AppInfo(1, "test", "1", "1"), log,
        connectAttempts = attempts, backoffMs = { 1L },
    )

    @Test
    fun aSilentServerFailsKeyCreationInTime() {
        ServerSocket(0).use { server ->
            val accepted = Collections.synchronizedList(ArrayList<Socket>())
            // Accepts and never writes a byte back.
            thread(isDaemon = true) { runCatching { while (true) accepted += server.accept() } }
            val started = System.currentTimeMillis()
            val tcp = ObfuscatedTcp("127.0.0.1", server.localPort, 2).also { it.connect(readTimeoutMs = 500) }
            try {
                assertFailsWith<java.io.IOException> { AuthKeyCreator(tcp, 2).create() }
            } finally {
                tcp.close()
                accepted.forEach { runCatching { it.close() } }
            }
            val took = System.currentTimeMillis() - started
            assertTrue(took < 3_000, "key creation gave up after $took ms")
        }
    }

    @Test
    fun aClosedPortGivesUpAfterTheAttempts() = runBlocking {
        val lines = Collections.synchronizedList(ArrayList<String>())
        val s = session(listOf(DcOption(2, "127.0.0.1", closedPort())), { lines += it }, attempts = 3)
        try {
            val e = assertFailsWith<MtpException> { withTimeout(10_000) { s.ensureConnected() } }
            assertTrue("אחרי 3 ניסיונות" in (e.message ?: ""), e.message)
            assertEquals("אין חיבור ל-DC2 אחרי 3 ניסיונות", e.message)
            assertEquals(3, lines.count { it.startsWith("connect to DC2 failed") }, lines.toString())
        } finally {
            s.close()
        }
    }

    @Test
    fun addressesAreTriedInTurn() = runBlocking {
        ServerSocket(0).use { server ->
            val accepted = Collections.synchronizedList(ArrayList<Socket>())
            thread(isDaemon = true) { runCatching { while (true) accepted += server.accept() } }
            val lines = Collections.synchronizedList(ArrayList<String>())
            val s = session(
                listOf(DcOption(2, "127.0.0.1", closedPort()), DcOption(2, "127.0.0.1", server.localPort)),
                { lines += it },
            )
            try {
                withTimeout(10_000) { s.ensureConnected() }
                // One failure on the first address, then connected to the second (attempt 2).
                assertEquals(listOf(true), lines.filter { it.startsWith("connect to DC2") }.map { "retry 1" in it }, lines.toString())
                val deadline = System.currentTimeMillis() + 3_000
                while (accepted.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
                assertEquals(1, accepted.size)
            } finally {
                s.close()
                accepted.forEach { runCatching { it.close() } }
            }
        }
    }
}
