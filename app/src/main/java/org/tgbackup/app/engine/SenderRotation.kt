package org.tgbackup.app.engine

/**
 * Whose turn it is to send in ordered mode with several accounts: round robin, one message each. An account that
 * rests (the pause after its send) or waits out a FloodWait is skipped until it is free. After a FloodWait an account
 * is "hot" for a while: it keeps its turns, but rests longer after each send ([restMs]).
 */
class SenderRotation(private val size: Int, private val now: () -> Long = System::currentTimeMillis) {
    sealed interface Pick {
        data class Account(val index: Int) : Pick
        data class Wait(val ms: Long) : Pick
        data object None : Pick
    }

    private val busyUntil = LongArray(size)
    private val restUntil = LongArray(size)
    private val hotUntil = LongArray(size)
    private var cursor = 0

    /** The next free account in turn; else how long until the first one is free; else [Pick.None]. */
    @Synchronized
    fun pick(usable: (Int) -> Boolean = { true }, exclude: Set<Int> = emptySet()): Pick {
        val t = now()
        var soonest = Long.MAX_VALUE
        for (k in 0 until size) {
            val i = (cursor + k) % size
            if (i in exclude || !usable(i)) continue
            val freeAt = maxOf(busyUntil[i], restUntil[i])
            if (freeAt <= t) {
                cursor = (i + 1) % size
                return Pick.Account(i)
            }
            soonest = minOf(soonest, freeAt - t)
        }
        return if (soonest == Long.MAX_VALUE) Pick.None else Pick.Wait(soonest)
    }

    /** A FloodWait until [untilMs]; afterwards hot for as long as the wait was, between 5 and 30 minutes. */
    @Synchronized
    fun busy(index: Int, untilMs: Long) {
        busyUntil[index] = untilMs
        val wait = (untilMs - now()).coerceIn(HOT_MIN_MS, HOT_MAX_MS)
        // A short wait never cuts a longer hot window short.
        hotUntil[index] = maxOf(hotUntil[index], untilMs + wait)
    }

    /** The pause after a send, until [untilMs]. */
    @Synchronized
    fun rest(index: Int, untilMs: Long) {
        restUntil[index] = untilMs
    }

    @Synchronized
    fun isHot(index: Int): Boolean = now() < hotUntil[index]

    /** The pause after a send of [index]: [baseMs], or three times it (at least 5 s) while hot. */
    @Synchronized
    fun restMs(index: Int, baseMs: Long): Long = if (isHot(index)) maxOf(baseMs * HOT_REST_FACTOR, HOT_REST_MIN_MS) else baseMs

    private companion object {
        const val HOT_MIN_MS = 300_000L
        const val HOT_MAX_MS = 1_800_000L
        const val HOT_REST_FACTOR = 3
        const val HOT_REST_MIN_MS = 5_000L
    }
}
