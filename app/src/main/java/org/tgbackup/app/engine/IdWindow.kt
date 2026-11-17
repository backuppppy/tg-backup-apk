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
