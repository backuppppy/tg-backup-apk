package org.tgbackup.mtproto

import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MsgIdsTest {
    @Test
    fun msgIdAndSeqNoGoUpTogetherEvenFromManyThreads() {
        val ids = MsgIds { System.currentTimeMillis() }
        val all = java.util.concurrent.ConcurrentLinkedQueue<Pair<MsgIds.Ids, Boolean>>()
        (0 until 8).map { k ->
            thread { repeat(2_000) { i -> val c = (i + k) % 3 != 0; all.add(ids.next(c) to c) } }
        }.forEach { it.join() }
        val sorted = all.sortedBy { it.first.msgId }
        // What the server checks: a higher msg_id never has a lower seq_no, and never an equal odd one.
        sorted.zipWithNext().forEach { (a, b) ->
            assertTrue(b.first.seq >= a.first.seq, "msg_id ${b.first.msgId} has seq ${b.first.seq} < ${a.first.seq}")
            if (b.first.seq == a.first.seq) assertEquals(0, b.first.seq % 2)
        }
        // Content messages count: odd, one apart; acks take the even number in between.
        val content = sorted.filter { it.second }.map { it.first.seq }
        assertEquals((0 until content.size).map { it * 2 + 1 }, content)
        assertEquals(sorted.size, sorted.map { it.first.msgId }.distinct().size)
    }

    @Test
    fun aNewSessionStartsSeqNoOverAndKeepsMsgIdGrowing() {
        val ids = MsgIds { 1_000_000L }
        val a = ids.next(true)
        ids.next(true)
        val old = ids.sessionId
        ids.newSession()
        val b = ids.next(true)
        assertNotEquals(old, b.sessionId)
        assertEquals(1, b.seq)
        assertTrue(b.msgId > a.msgId)
        assertEquals(0L, b.msgId % 4)
    }

    @Test
    fun answersOfTheLastFewSessionsAreStillTaken() {
        val ids = MsgIds { 1_000_000L }
        val first = ids.sessionId
        ids.newSession()
        assertTrue(ids.isOurs(first))
        assertTrue(ids.isOurs(ids.sessionId))
        repeat(4) { ids.newSession() }
        kotlin.test.assertFalse(ids.isOurs(first))
        kotlin.test.assertFalse(ids.isOurs(12345L))
    }
}
