package org.tgbackup.app.engine

/**
 * Whether a running job's source has forwarding protection right now: set at the start, then by Telegram's
 * protection notices while the job runs (and by a copy refused for protection). Read by every sender.
 */
class SourceProtection(initial: Boolean) {
    @Volatile
    var isProtected: Boolean = initial
        private set

    /** Sets the flag; true when it changed. */
    @Synchronized
    fun set(value: Boolean): Boolean {
        if (isProtected == value) return false
        isProtected = value
        return true
    }

    /** A notice for [chatId]: applied only when it is the source ([sourceId]); true when the flag changed. */
    fun onNotice(chatId: Long, sourceId: Long, value: Boolean): Boolean = chatId == sourceId && set(value)
}
