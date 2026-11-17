package org.tgbackup.app.engine

/**
 * The next server ids to read: after [after], at most [max] of them, never past [latest] (the source's newest).
 * Empty when caught up. Jobs read messages by these ids (getMessages), not by browsing history: one request per window.
 */
fun idWindow(after: Long, latest: Long, max: Int): LongRange =
    if (after >= latest) LongRange.EMPTY else (after + 1)..minOf(after + max, latest)

/** TDLib message ids of server ids (TDLib keeps the server id in the high bits). */
fun tdlibIds(serverIds: LongRange): LongArray = LongArray((serverIds.last - serverIds.first + 1).toInt().coerceAtLeast(0)) {
    (serverIds.first + it) shl 20
}

/** Fast mode's slice per account in a wave: the per-minute limit (1..100); 0 (no limit) gives 25. */
fun sliceSize(limit: Int): Int = if (limit <= 0) DEFAULT_SLICE else limit.coerceAtMost(MAX_SLICE)

/** The slice size a saved wave was opened with; 0 = a wave saved before the size was stored, which used 25. */
fun waveSliceSize(waveChunk: Int): Int = if (waveChunk > 0) waveChunk else DEFAULT_SLICE

const val DEFAULT_SLICE = 25
const val MAX_SLICE = 100
