package org.tgbackup.mtproto

import org.tgbackup.mtproto.tl.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TlRoundTripTest {
    @Test
    fun messageRoundTrip() {
        val msg = MTPDmessage(
            is_out = true,
            is_offline = true,
            vid = 42,
            vfrom_id = MTPDpeerUser(777),
            vpeer_id = MTPDpeerChannel(123),
            vdate = 1700000000,
            vmessage = "שלום עולם 👋",
            ventities = listOf(MTPDmessageEntityBold(0, 4)),
            vviews = 10,
            vforwards = 3,
            veffect = 99L,
        )
        val bytes = msg.toBytes()
        val parsed = TlReader(bytes).obj() as MTPDmessage
        assertEquals(42, parsed.vid)
        assertTrue(parsed.is_out)
        assertTrue(parsed.is_offline)
        assertEquals("שלום עולם 👋", parsed.vmessage)
        assertEquals(10, parsed.vviews)
        assertEquals(3, parsed.vforwards)
        assertEquals(99L, parsed.veffect)
        assertEquals(777L, (parsed.vfrom_id as MTPDpeerUser).vuser_id)
        assertEquals(1, parsed.ventities!!.size)
        assertEquals(null, parsed.vmedia)
        // Re-serializing gives identical bytes.
        assertTrue(bytes.contentEquals(parsed.toBytes()))
    }

    @Test
    fun takeoutWrapper() {
        val req = InvokeWithTakeout(5L, MTPupload_GetFile(vlocation = MTPDinputTakeoutFileLocation, voffset = 1048576, vlimit = 1048576))
        val r = TlReader(req.toBytes())
        assertEquals(0xaca9fd2e.toInt(), r.int())
        assertEquals(5L, r.long())
        assertEquals(MTPupload_GetFile.CONSTRUCTOR, r.int())
        assertEquals(0, r.int()) // flags
        assertEquals(MTPDinputTakeoutFileLocation.CONSTRUCTOR, r.int())
        assertEquals(1048576L, r.long())
        assertEquals(1048576, r.int())
    }
}
