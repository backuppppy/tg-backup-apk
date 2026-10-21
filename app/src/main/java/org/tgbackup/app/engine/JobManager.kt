package org.tgbackup.app.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import io.sentry.Sentry
import org.tgbackup.app.data.Store
import org.tgbackup.app.tg.AccountManager
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job as CoJob

data class JobStatus(
    val running: Boolean = false,
    val paused: Boolean = false,
    val phase: String = "",
    val lastId: Long = 0,
    val sourceChatId: Long = 0,
    val sourceTitle: String = "",
    val isProtected: Boolean = false,
    val copied: Int = 0,
    val failed: Int = 0,
) {
    val lastLink: String?
        get() {
            if (lastId <= 0 || sourceChatId == 0L) return null
            val internal = if (sourceChatId <= -1_000_000_000_000L) -sourceChatId - 1_000_000_000_000L else return null
            return "https://t.me/c/$internal/$lastId"
        }
}

class JobManager(
    private val store: Store,
    private val accounts: AccountManager,
    private val log: LogBus,
    private val scope: CoroutineScope,
) {
    private val _statuses = MutableStateFlow<Map<String, JobStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, JobStatus>> = _statuses.asStateFlow()

    val anyRunning: StateFlow<Boolean> = _statuses
        .map { m -> m.values.any { it.running } }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val running = ConcurrentHashMap<String, CoJob>()
    private val pauseFlags = ConcurrentHashMap<String, MutableStateFlow<Boolean>>()

    init {
        // Show saved progress for idle jobs as well.
        store.config.value.jobs.forEach { j -> setStatus(j.id) { it.copy(lastId = store.progress(j.id).lastId) } }
    }

    private fun setStatus(id: String, transform: (JobStatus) -> JobStatus) =
        _statuses.update { it + (id to transform(it[id] ?: JobStatus())) }

    fun isRunning(id: String) = running[id]?.isActive == true

    /** Starts a job. Returns an error message, or null when started. */
    fun start(jobId: String): String? {
        if (isRunning(jobId)) return "המשימה כבר פועלת"
        val cfg = store.config.value
        val job = cfg.jobs.firstOrNull { it.id == jobId } ?: return "המשימה לא נמצאה"
        if (job.source.isBlank() || job.target.isBlank()) return "חסר מקור או יעד"
        if (job.accountIds.isEmpty()) return "לא נבחרו חשבונות למשימה"
        val busy = cfg.jobs.filter { it.id != jobId && isRunning(it.id) }
            .flatMap { other -> other.accountIds.intersect(job.accountIds.toSet()) }
        if (busy.isNotEmpty()) {
            val names = busy.mapNotNull { id -> cfg.accounts.firstOrNull { it.id == id }?.name }
            return "החשבון ${names.joinToString()} כבר עובד במשימה אחרת"
        }

        store.updateConfig { it.copy(autoResume = it.autoResume + jobId) }
        val paused = pauseFlags.getOrPut(jobId) { MutableStateFlow(false) }
        paused.value = false
        setStatus(jobId) { it.copy(running = true, paused = false, phase = "מתחיל…", copied = 0, failed = 0) }

        running[jobId] = scope.launch {
            try {
                while (isActive) {
                    val current = store.config.value.jobs.firstOrNull { it.id == jobId } ?: break
                    val label = current.name.ifBlank { current.id }
                    try {
                        // Right after process start the account clients may still be spinning up.
                        withTimeoutOrNull(10_000) {
                            accounts.clients.first { m -> current.accountIds.any { it in m } }
                        }
                        val clients = current.accountIds.mapNotNull { accounts[it] }
                        if (clients.isEmpty()) error("אף אחד מהחשבונות של המשימה לא מוגדר עם API ID")
                        log.info("[$label] מתחיל…")
                        JobRunner(
                            job = current,
                            accounts = clients,
                            store = store,
                            log = log,
                            paused = paused,
                            report = reporter(jobId),
                            clearStartFrom = {
                                store.updateConfig { c ->
                                    c.copy(jobs = c.jobs.map { if (it.id == jobId) it.copy(startFromId = 0) else it })
                                }
                            },
                        ).run()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Sentry.captureException(e) { s -> s.setTag("job", label) }
                        log.error("[$label] ${e.message ?: e::class.simpleName} — מפעיל מחדש בעוד 15 שנ'")
                        setStatus(jobId) { it.copy(phase = "שגיאה — מפעיל מחדש בעוד 15 שנ'") }
                        delay(15_000)
                    }
                }
            } finally {
                running.remove(jobId)
                setStatus(jobId) { it.copy(running = false, paused = false, phase = "עצור") }
            }
        }
        return null
    }

    private fun reporter(jobId: String) = object : JobReporter {
        override fun phase(text: String) = setStatus(jobId) { it.copy(phase = text) }
        override fun position(lastId: Long) = setStatus(jobId) { it.copy(lastId = lastId) }
        override fun copied() = setStatus(jobId) { it.copy(copied = it.copied + 1) }
        override fun failed() = setStatus(jobId) { it.copy(failed = it.failed + 1) }
        override fun sourceInfo(chatId: Long, title: String, isProtected: Boolean) =
            setStatus(jobId) { it.copy(sourceChatId = chatId, sourceTitle = title, isProtected = isProtected) }
    }

    fun stop(jobId: String) {
        store.updateConfig { it.copy(autoResume = it.autoResume - jobId) }
        running[jobId]?.cancel()
        log.warn("[$jobId] נעצר")
    }

    fun pause(jobId: String) {
        if (!isRunning(jobId)) return
        pauseFlags[jobId]?.value = true
        setStatus(jobId) { it.copy(paused = true, phase = "מושהה") }
    }

    fun resume(jobId: String) {
        pauseFlags[jobId]?.value = false
        setStatus(jobId) { it.copy(paused = false, phase = "ממשיך…") }
    }

    fun startAll(): List<String> = store.config.value.jobs.filter { !isRunning(it.id) }.mapNotNull { j ->
        start(j.id)?.let { "${j.name.ifBlank { j.id }}: $it" }
    }

    fun pauseAll() = store.config.value.jobs.forEach { pause(it.id) }

    fun resumeAll() = store.config.value.jobs.forEach { if (isRunning(it.id)) resume(it.id) }

    fun stopAll() = store.config.value.jobs.forEach { if (isRunning(it.id)) stop(it.id) }

    fun resetProgress(jobId: String) {
        store.clearProgress(jobId)
        setStatus(jobId) { it.copy(lastId = 0) }
    }

    fun forget(jobId: String) {
        stop(jobId)
        store.clearProgress(jobId)
        _statuses.update { it - jobId }
    }

    /** Restarts jobs that were running before the process died. Safe to call repeatedly. */
    fun restore() {
        store.config.value.autoResume.forEach { id ->
            if (!isRunning(id) && store.config.value.jobs.any { it.id == id }) start(id)
        }
    }
}
