package org.tgbackup.copy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TakeoutDiagnosticsTest {
    @Test fun slicesSplitWaveAcrossWorkers() {
        val s = TakeoutDiagnostics.slices(100, 174, 3, 25)
        assertEquals(listOf(100L to 124L, 125L to 149L, 150L to 174L), s.map { it.start to it.end })
        assertEquals(listOf(0, 1, 2), s.map { it.index })
    }
    @Test fun slicesStopAtWaveEnd() {
        val s = TakeoutDiagnostics.slices(100, 130, 3, 25)
        assertEquals(listOf(100L to 124L, 125L to 130L), s.map { it.start to it.end })
    }
    @Test fun slicesEmptyWhenNoWorkers() = assertTrue(TakeoutDiagnostics.slices(1, 50, 0, 25).isEmpty())

    @Test fun waveJumpOnlyWhenGap() {
        assertTrue(TakeoutDiagnostics.waveJump(0, 34446))
        assertFalse(TakeoutDiagnostics.waveJump(99, 100))
        assertFalse(TakeoutDiagnostics.waveJump(99, 99))
    }
    @Test fun sliceSkippedWhenNothingProcessed() {
        assertTrue(TakeoutDiagnostics.sliceSkipped(last = 99, rs = 100))
        assertFalse(TakeoutDiagnostics.sliceSkipped(last = 100, rs = 100))
    }
    @Test fun staleProgress() {
        assertTrue(TakeoutDiagnostics.staleProgress(lastId = 5, waveEnd = 0, startFromId = 0))
        assertTrue(TakeoutDiagnostics.staleProgress(lastId = 0, waveEnd = 9, startFromId = 0))
        assertFalse(TakeoutDiagnostics.staleProgress(lastId = 0, waveEnd = 0, startFromId = 0))
        assertFalse(TakeoutDiagnostics.staleProgress(lastId = 5, waveEnd = 9, startFromId = 3))
    }
    @Test fun sourceChangedKeepsLegacyZero() {
        assertFalse(TakeoutDiagnostics.sourceChanged(0, 77))
        assertFalse(TakeoutDiagnostics.sourceChanged(77, 77))
        assertTrue(TakeoutDiagnostics.sourceChanged(76, 77))
    }
    @Test fun firstFar() {
        assertFalse(TakeoutDiagnostics.firstFar(firstId = 125, rs = 100, chunk = 25))
        assertTrue(TakeoutDiagnostics.firstFar(firstId = 126, rs = 100, chunk = 25))
    }
    @Test fun outsideSlice() {
        assertFalse(TakeoutDiagnostics.outsideSlice(100, 100, 124))
        assertFalse(TakeoutDiagnostics.outsideSlice(124, 100, 124))
        assertTrue(TakeoutDiagnostics.outsideSlice(99, 100, 124))
        assertTrue(TakeoutDiagnostics.outsideSlice(125, 100, 124))
    }
    @Test fun waveGap() {
        assertFalse(TakeoutDiagnostics.waveGap(prevWaveEnd = 0, ws = 500))
        assertFalse(TakeoutDiagnostics.waveGap(prevWaveEnd = 99, ws = 100))
        assertTrue(TakeoutDiagnostics.waveGap(prevWaveEnd = 99, ws = 200))
    }
    @Test fun incompleteSlice() {
        assertTrue(TakeoutDiagnostics.incompleteSlice(workerLast = 110, re = 124, endedNormally = false))
        assertFalse(TakeoutDiagnostics.incompleteSlice(workerLast = 110, re = 124, endedNormally = true))
        assertFalse(TakeoutDiagnostics.incompleteSlice(workerLast = 124, re = 124, endedNormally = true))
    }
    @Test fun endedNormally() {
        assertTrue(TakeoutDiagnostics.endedNormally(last = 124, re = 124, batchEmpty = false))
        assertTrue(TakeoutDiagnostics.endedNormally(last = 110, re = 124, batchEmpty = false))  // batch past re: deleted tail
        assertFalse(TakeoutDiagnostics.endedNormally(last = 110, re = 124, batchEmpty = true))
    }
    @Test fun accountsChangedOnlyWhenMoreSlicesThanWorkers() {
        assertFalse(TakeoutDiagnostics.accountsChanged(100, 174, 25, 3))
        assertTrue(TakeoutDiagnostics.accountsChanged(100, 174, 25, 2))
        assertFalse(TakeoutDiagnostics.accountsChanged(100, 130, 25, 3))
        assertFalse(TakeoutDiagnostics.accountsChanged(100, 149, 25, 3))
    }
    @Test fun largeJump() {
        assertTrue(TakeoutDiagnostics.largeJump(0, 3, 25))
        assertTrue(TakeoutDiagnostics.largeJump(100, 126, 25))
        assertFalse(TakeoutDiagnostics.largeJump(100, 125, 25))
    }
    @Test fun staleReason() {
        assertEquals("source-changed", TakeoutDiagnostics.staleReason(true, true, 5))
        assertEquals("mode-reset", TakeoutDiagnostics.staleReason(false, false, 5))
        assertEquals("resume", TakeoutDiagnostics.staleReason(false, true, 5))
        assertEquals("resume", TakeoutDiagnostics.staleReason(false, false, 0))
    }
}
