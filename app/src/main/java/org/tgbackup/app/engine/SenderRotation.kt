package org.tgbackup.app.engine

/**
 * Whose turn it is to send in ordered mode with several accounts: round robin, [turnSize] messages each (a job
 * setting, 3 by default since 19/11: an account sends that many in a row, then rests). An account that
 * rests (the pause after its send) or waits out a FloodWait is skipped until it is free. After a FloodWait an account
 * is "hot" for a while: it keeps its turns, but rests longer after each send ([restMs]).
 */
class SenderRotation(
    private val size: Int,
    /** Messages an account sends in a row before the turn passes on. */
    private val turnSize: Int = 1,
    private val now: () -> Long = System::currentTimeMillis,
) {
    sealed interface Pick {
        data class Account(val index: Int) : Pick
        data class Wait(val ms: Long) : Pick
        data object None : Pick
    }

    private val busyUntil = LongArray(size)
    private val restUntil = LongArray(size)
    private val hotUntil = LongArray(size)
    private var cursor = 0

    /** The account whose turn it is, and how many messages it sent in it; -1 = no turn going on. */
    private var holder = -1
    private var used = 0
    /** The rest each account is owed when its turn ends (the last one [sent] gave). */
    private val owedRest = LongArray(size)

    /** The next free account in turn; else how long until the first one is free; else [Pick.None]. */
    @Synchronized
    fun pick(usable: (Int) -> Boolean = { true }, exclude: Set<Int> = emptySet()): Pick {
        val t = now()
        // The turn goes on while the account is free and has messages left in it.
        val h = holder
        if (h >= 0 && used in 1 until turnSize && h !in exclude && usable(h) && maxOf(busyUntil[h], restUntil[h]) <= t) {
            return Pick.Account(h)
        }
        var soonest = Long.MAX_VALUE
        for (k in 0 until size) {
            val i = (cursor + k) % size
            if (i in exclude || !usable(i)) continue
            val freeAt = maxOf(busyUntil[i], restUntil[i])
            if (freeAt <= t) {
                cursor = (i + 1) % size
                if (i != holder) {
                    // A turn cut short (its account failed a message) still ends with the account's rest.
                    if (holder >= 0 && used > 0) restUntil[holder] = maxOf(restUntil[holder], t + owedRest[holder])
                    holder = i
                    used = 0
                }
                return Pick.Account(i)
            }
            soonest = minOf(soonest, freeAt - t)
        }
        return if (soonest == Long.MAX_VALUE) Pick.None else Pick.Wait(soonest)
    }

    /** A FloodWait until [untilMs]; afterwards hot for as long as the wait was, between 5 and 30 minutes. */
    @Synchronized
    fun busy(index: Int, untilMs: Long) {
        // A FloodWait ends the turn at once.
        if (holder == index) holder = -1
        busyUntil[index] = untilMs
        val wait = (untilMs - now()).coerceIn(HOT_MIN_MS, HOT_MAX_MS)
        // A short wait never cuts a longer hot window short.
        hotUntil[index] = maxOf(hotUntil[index], untilMs + wait)
    }

    /**
     * [index] sent a message of its turn: when the turn is over it rests [restMs] (see [restMs]) and the next account
     * goes on; inside the turn it sends the next message at once.
     */
    @Synchronized
    fun sent(index: Int, restMs: Long) {
        if (holder != index) {
            holder = index
            used = 0
        }
        used++
        owedRest[index] = restMs
        if (used >= turnSize) {
            restUntil[index] = now() + restMs
            holder = -1
        }
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

/**
 * Why a job's senders cannot run, or null: a bot only helps send (it cannot read a chat's history), so a job needs a
 * regular account too; and a bot cannot open an export session, so "בלי הורדה" takes regular accounts only.
 */
fun botRule(accounts: List<org.tgbackup.app.data.Account>, viaTakeout: Boolean): String? = when {
    accounts.isNotEmpty() && accounts.all { it.isBot } ->
        "משימה עם בוט צריכה גם חשבון רגיל אחד לפחות — הוא זה שקורא את המקור"
    viaTakeout && accounts.any { it.isBot } ->
        "העתקה בלי הורדה לא עובדת עם בוטים (טלגרם לא נותן לבוט סשן ייצוא) — הסר את הבוט או כבה את \"בלי הורדה\""
    else -> null
}

/** Telegram lets a bot send about 20 messages a minute in one chat: a bot's per-minute limit is at most that. */
fun senderLimit(perMinuteLimit: Int, isBot: Boolean): Int =
    if (!isBot) perMinuteLimit else if (perMinuteLimit <= 0) BOT_PER_MINUTE else minOf(perMinuteLimit, BOT_PER_MINUTE)

const val BOT_PER_MINUTE = 20
