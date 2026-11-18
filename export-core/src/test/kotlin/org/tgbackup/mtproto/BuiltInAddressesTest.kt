package org.tgbackup.mtproto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BuiltInAddressesTest {
    @Test
    fun dc2TriesTheBackupAddressAndTheOtherPorts() {
        assertEquals(
            listOf(
                "149.154.167.51:443", "95.161.76.100:443",
                "149.154.167.51:80", "95.161.76.100:80",
                "149.154.167.51:5222", "95.161.76.100:5222",
            ),
            builtInAddresses(2).map { "${it.host}:${it.port}" },
        )
    }

    @Test
    fun theMainAddressComesFirst() {
        for (dc in 1..5) assertEquals(443, builtInAddresses(dc).first().port)
        assertEquals("149.154.167.91", builtInAddresses(4).first().host)
    }

    @Test
    fun unknownDc() {
        assertFailsWith<MtpException> { builtInAddresses(9) }
    }
}
