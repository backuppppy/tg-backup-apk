package org.tgbackup.mtproto

import kotlin.test.Test
import kotlin.test.assertEquals

class SentLogTest {
    private fun idAt(ms: Long): Long = (ms / 1000) shl 32

    @Test
    fun describesTheRefusedMessage() {
        var t = 1_000_000L
        val s = SentLog { t }
        val id = idAt(t)
        s.sent(id, "request MTPmessages_GetHistory")
        t += 312_000
        assertEquals("request MTPmessages_GetHistory (pending), sent 312 s ago, id 312 s old", s.describe(id, true, t))
        assertEquals("unknown message (not sent by this session lately), id 5 s old", s.describe(idAt(t - 5_000), false, t))
        assertEquals(
            "request MTPmessages_GetHistory (pending), sent 312 s ago, id 312 s old | unknown message (not sent by this session lately), id 5 s old",
            s.recentRefused(),
        )
    }

    @Test
    fun keepsTheLastSent() {
        var t = 0L
        val s = SentLog(keep = 2) { t }
        s.sent(1, "a"); s.sent(2, "b"); s.sent(3, "c")
        assertEquals(true, s.describe(1, false, 0).startsWith("unknown"))
        assertEquals("c, sent 0 s ago, id 0 s old", s.describe(3, false, 0))
    }
}
