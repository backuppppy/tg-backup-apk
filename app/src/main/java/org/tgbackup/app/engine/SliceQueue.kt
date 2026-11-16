package org.tgbackup.app.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The slices of one fast-mode wave and who works them. At the start slice `i` is held by account `i`. A stuck account
 * [release]s the rest of its slice, and the next free account takes it with [next]. A slice is held by one account at
 * a time, so no message is sent twice; the wave is over when every slice is [done].
 */
class SliceQueue(private val count: Int) {
    private val mutex = Mutex()
    private val queued = ArrayDeque<Int>()
    private val finished = BooleanArray(count)
    @Volatile private var doneCount = 0

    /** Bumped on every change, so [next] wakes up. */
    private val version = MutableStateFlow(0)

    val allDone: Boolean
        get() = doneCount == count

    /** The holder stopped working [slice]: it waits for the next free account. */
    suspend fun release(slice: Int) {
        mutex.withLock {
            if (!finished[slice] && slice !in queued) queued.addLast(slice)
        }
        version.update { it + 1 }
    }

    suspend fun done(slice: Int) {
        mutex.withLock {
            if (!finished[slice]) {
                finished[slice] = true
                doneCount++
            }
            queued.remove(slice)
        }
        version.update { it + 1 }
    }

    /** A queued slice, now held by the caller; waits while others still hold slices; null when all are done. */
    suspend fun next(): Int? {
        while (true) {
            val seen = version.value
            mutex.withLock {
                queued.removeFirstOrNull()?.let { return it }
                if (doneCount == count) return null
            }
            version.first { it != seen }
        }
    }
}
