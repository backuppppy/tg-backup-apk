package org.tgbackup.mtproto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BadMsgStormDetectorTest {
    private var now = 0L
    private val d = BadMsgStormDetector(clock = { now })

    private fun burst(code: Int, n: Int, stepMs: Long = 50): List<BadMsgStormDetector.Verdict> =
        List(n) { now += stepMs; d.record(code) }

    @Test
    fun belowThresholdNeverFires() {
        val v = burst(16, 19)
        assertTrue(v.all { it is BadMsgStormDetector.Verdict.Log })
    }

    @Test
    fun atThresholdFiresOnce() {
        val v = burst(16, 20)
        val storm = v.last() as BadMsgStormDetector.Verdict.Storm
        assertEquals(20, storm.count)
        assertEquals(1, v.count { it is BadMsgStormDetector.Verdict.Storm })
        assertTrue(v.take(19).all { it is BadMsgStormDetector.Verdict.Log })
    }

    @Test
    fun furtherNotificationsAreSuppressedWithoutRefire() {
        burst(16, 20)
        val v = burst(16, 100)
        assertTrue(v.all { it is BadMsgStormDetector.Verdict.Suppress })
    }

    @Test
    fun slowNotificationsOutsideWindowDoNotFire() {
        val v = burst(16, 100, stepMs = 1_000)
        assertTrue(v.all { it is BadMsgStormDetector.Verdict.Log })
    }

    @Test
    fun reArmsAfterQuietPeriod() {
        burst(16, 20)
        now += 59_000
        assertTrue(d.record(16) is BadMsgStormDetector.Verdict.Suppress)
        now += 60_000
        val v = burst(16, 20)
        assertTrue(v.last() is BadMsgStormDetector.Verdict.Storm)
        assertTrue(v.take(19).all { it is BadMsgStormDetector.Verdict.Log })
    }

    @Test
    fun codesAreIndependent() {
        burst(16, 20)
        assertTrue(d.record(17) is BadMsgStormDetector.Verdict.Log)
        val v = burst(32, 20)
        assertTrue(v.last() is BadMsgStormDetector.Verdict.Storm)
    }
}
