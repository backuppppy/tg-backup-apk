package org.tgbackup.mtproto

/**
 * Spots a storm of bad_msg_notification: [threshold] notifications with the same error code inside
 * [windowMs]. A storm fires once per code and re-arms after [quietMs] without notifications.
 * Observation only; it never changes how the session reacts.
 */
internal class BadMsgStormDetector(
    private val threshold: Int = 20,
    private val windowMs: Long = 10_000,
    private val quietMs: Long = 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed interface Verdict {
        /** Not (yet) a storm: log the line as usual. */
        data object Log : Verdict

        /** A storm is running and was already reported: skip the per-message line. */
        data object Suppress : Verdict

        /** The storm just started: [count] notifications in [spanMs]. */
        data class Storm(val count: Int, val spanMs: Long) : Verdict
    }

    private class State {
        val times = ArrayDeque<Long>()
        var last = 0L
        var storming = false
    }

    private val states = HashMap<Int, State>()

    fun record(code: Int): Verdict = synchronized(states) {
        val now = clock()
        val s = states.getOrPut(code) { State() }
        if (s.storming && now - s.last >= quietMs) {
            s.storming = false
            s.times.clear()
        }
        s.last = now
        if (s.storming) return Verdict.Suppress
        while (s.times.isNotEmpty() && now - s.times.first() > windowMs) s.times.removeFirst()
        s.times.addLast(now)
        if (s.times.size >= threshold) {
            s.storming = true
            val v = Verdict.Storm(s.times.size, now - s.times.first())
            s.times.clear()
            return v
        }
        Verdict.Log
    }
}

/** What the session knew when a bad_msg_notification storm began. */
data class BadMsgStorm(
    val dcId: Int,
    val code: Int,
    val count: Int,
    val windowMs: Long,
    val offsetBeforeMs: Long,
    val offsetAfterMs: Long,
    val pendingCount: Int,
    val oldestRequestAgeMs: Long,
    val maxResends: Int,
)
