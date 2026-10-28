package org.tgbackup.app.engine

import io.sentry.IScope
import io.sentry.Sentry
import io.sentry.SentryLevel
import org.tgbackup.app.BuildConfig
import java.io.File

/**
 * Sends each distinct problem (by [key]) to Sentry only once per app version, across restarts,
 * so a job that fails every 15 seconds does not flood Sentry. A new version reports again.
 */
object SentryOnce {
    private val sent = HashSet<String>()
    private var file: File? = null

    fun init(dir: File) {
        val name = "sentry_sent_${BuildConfig.VERSION_NAME}.txt"
        dir.listFiles { f -> f.name.startsWith("sentry_sent_") && f.name != name }?.forEach { it.delete() }
        val f = File(dir, name)
        file = f
        synchronized(sent) { if (f.exists()) sent += f.readLines() }
    }

    fun message(key: String, text: String, level: SentryLevel, scope: (IScope) -> Unit = {}) {
        if (claim(key)) Sentry.captureMessage(text, level) { s -> scope(s) }
    }

    fun exception(key: String, e: Throwable, scope: (IScope) -> Unit = {}) {
        if (claim(key)) Sentry.captureException(e) { s -> scope(s) }
    }

    private fun claim(key: String): Boolean = synchronized(sent) {
        val k = key.replace('\n', ' ')
        if (!sent.add(k)) return false
        runCatching { file?.appendText(k + "\n") }
        true
    }
}
