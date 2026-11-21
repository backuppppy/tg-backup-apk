package org.tgbackup.app.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
import org.tgbackup.app.data.Store
import org.tgbackup.app.tg.serverId
import org.tgbackup.app.export.ExportManager
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
    /** Last message delivered to the target in this run (server id). */
    val targetChatId: Long = 0,
    val lastTargetId: Long = 0,
    /** Messages copied over all runs (Progress.copiedTotal, plus this run's copies as they happen). */
    val copiedTotal: Long = 0,
    /** Saved run time; while running, the time since [runningSince] comes on top of it. */
    val runMillis: Long = 0,
    /** Epoch ms since which the job runs without the time being in [runMillis]; 0 when stopped or paused. */
    val runningSince: Long = 0,
    /** Server id of the source's newest message; 0 = not known yet. */
    val latestId: Long = 0,
) {
    /** The last handled message in the source. */
    val lastLink: String? get() = messageLink(sourceChatId, lastId)

    /** The newest message in the source. */
    val latestLink: String? get() = messageLink(sourceChatId, latestId)

    /** The last message copied into the target. */
    val lastTargetLink: String? get() = messageLink(targetChatId, lastTargetId)

    private fun messageLink(chatId: Long, messageId: Long): String? {
        if (messageId <= 0 || chatId == 0L) return null
        val internal = if (chatId <= -1_000_000_000_000L) -chatId - 1_000_000_000_000L else return null
        return "https://t.me/c/$internal/$messageId"
    }
}

class JobManager(
    private val store: Store,
    private val accounts: AccountManager,
    private val log: LogBus,
    private val scope: CoroutineScope,
    /** How often a running job's run time is written to its progress. */
    private val runTick: Long = 10_000,
    /** How often a running job reads its source's latest id again (refreshLatest). */
    private val latestTick: Long = 3_600_000,
    /** At a run's start, how long to wait before reading the latest id again when the read failed. */
    private val latestRetry: Long = 20_000,
    /** The wall clock of the run time; a test moves it by hand. */
    private val wallClock: () -> Long = System::currentTimeMillis,
    /** The most of one clock segment that counts as run time: anything longer is the PC asleep. */
    private val maxSegment: Long = MAX_TICKS_PER_SEGMENT * runTick,
    private val exports: () -> ExportManager,
) {
    private val _statuses = MutableStateFlow<Map<String, JobStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, JobStatus>> = _statuses.asStateFlow()

    val anyRunning: StateFlow<Boolean> = _statuses
        .map { m -> m.values.any { it.running } }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val running = ConcurrentHashMap<String, CoJob>()

    /** Every run not completed yet, with its job: the current ones and stopped ones still cancelling. */
    private val live = ConcurrentHashMap<CoJob, String>()

    /** What a running job does every [latestTick]; a test replaces it. */
    internal var periodicRefresh: suspend (String) -> Long? = { refreshLatest(it) }
    private val pauseFlags = ConcurrentHashMap<String, MutableStateFlow<Boolean>>()

    /** A run's clock: since when its time is not in Progress.runMillis yet. */
    private class Segment(val run: CoJob, val since: Long)

    /**
     * Per running, unpaused job: its current run's segment. A run touches only its own segment, so a run that
     * ends after a newer one started (stop, then a quick start) cannot stop the newer one's clock.
     */
    private val runSegments = ConcurrentHashMap<String, Segment>()
    /** Guards the run clocks and which run is the current one of a job ([running]). */
    private val clockLock = Any()

    init {
        // Show saved progress for idle jobs as well.
        store.config.value.jobs.forEach { j -> showSavedProgress(j.id) }
    }

    /** Shows the saved position and run totals of [jobId] in its status. */
    fun showSavedProgress(jobId: String) = synchronized(clockLock) {
        // runMillis and runningSince together: a clock flush between them would count its segment twice.
        val p = store.progress(jobId)
        val since = runSegments[jobId]?.since ?: 0
        setStatus(jobId) {
            it.copy(
                lastId = p.lastId, copiedTotal = p.copiedTotal, runMillis = p.runMillis, latestId = p.latestId,
                runningSince = since,
                // After a restart the source's chat is known from the saved progress, so the numbers are links.
                sourceChatId = if (it.sourceChatId == 0L) p.sourceId else it.sourceChatId,
            )
        }
    }

    /**
     * Adds the time since the current run segment began to Progress.runMillis. With [goOn] the clock keeps
     * running from now; otherwise it stops (pause, stop). Does nothing while the clock is stopped, or when
     * [run] is given and the segment is another run's.
     */
    private fun flushRunTime(jobId: String, goOn: Boolean, run: CoJob? = null) = synchronized(clockLock) {
        val seg = runSegments[jobId] ?: return@synchronized
        if (run != null && seg.run !== run) return@synchronized
        val now = wallClock()
        // The clock writes every runTick, so a longer segment means the PC slept (or the program was frozen)
        // in between: that time is not run time. At most two ticks of it count (the part before the sleep is unknown).
        val counted = (now - seg.since).coerceIn(0, maxSegment)
        store.saveProgress(jobId) { it.copy(runMillis = it.runMillis + counted) }
        if (goOn) runSegments[jobId] = Segment(seg.run, now) else runSegments.remove(jobId)
        val total = store.progress(jobId).runMillis
        setStatus(jobId) { it.copy(runMillis = total, runningSince = if (goOn) now else 0) }
    }

    private fun startClock(jobId: String, run: CoJob) = synchronized(clockLock) {
        // A segment left by an older run is added first, so its time is not lost.
        flushRunTime(jobId, goOn = false)
        val now = wallClock()
        runSegments[jobId] = Segment(run, now)
        setStatus(jobId) { it.copy(runningSince = now) }
    }

    /** [run] ended: when it is still [jobId]'s current run, its clock stops and the job shows as stopped. */
    private fun finishRun(jobId: String, run: CoJob) = synchronized(clockLock) {
        if (!running.remove(jobId, run)) return@synchronized
        // A completion handler must not throw; a failed write must not leave the job shown running.
        runCatching { flushRunTime(jobId, goOn = false, run = run) }.onFailure { e ->
            log.error("[$jobId] שמירת זמן הריצה נכשלה: ${e.message}")
            if (runSegments[jobId]?.run === run) runSegments.remove(jobId)
        }
        // A job forgotten while it stopped keeps no status.
        if (jobId in _statuses.value) {
            val phase = stopReasons.remove(jobId) ?: "עצור"
            setStatus(jobId) { it.copy(running = false, paused = false, phase = phase, runningSince = 0) }
            runCatching { showSavedProgress(jobId) }.onFailure { e -> log.error("[$jobId] ${e.message}") }
        }
    }

    /** Why a run stopped by itself (shown on the card instead of "עצור"). */
    private val stopReasons = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun setStatus(id: String, transform: (JobStatus) -> JobStatus) =
        _statuses.update { it + (id to transform(it[id] ?: JobStatus())) }

    /** From the run's registration (before it is started) until a stop is asked; a cancelling run is not running. */
    fun isRunning(id: String) = running[id]?.let { !it.isCancelled && !it.isCompleted } == true

    /** Starts a job. Returns an error message, or null when started. */
    fun start(jobId: String): String? {
        if (isRunning(jobId)) return "המשימה כבר פועלת"
        val cfg = store.config.value
        val job = cfg.jobs.firstOrNull { it.id == jobId } ?: return "המשימה לא נמצאה"
        if (job.source.isBlank() || job.target.isBlank()) return "חסר מקור או יעד"
        if (job.accountIds.isEmpty()) return "לא נבחרו חשבונות למשימה"
        // A job left with bots only (its regular account deleted) is refused, not started to fail every 15 s.
        botRule(cfg.accounts.filter { it.id in job.accountIds }, job.viaTakeout)?.let { return it }
        val busy = cfg.jobs.filter { it.id != jobId && isRunning(it.id) }
            .flatMap { other -> other.accountIds.intersect(job.accountIds.toSet()) }
        if (busy.isNotEmpty()) {
            val names = busy.mapNotNull { id -> cfg.accounts.firstOrNull { it.id == id }?.name }
            return "החשבון ${names.joinToString()} כבר עובד במשימה אחרת"
        }

        store.updateConfig { it.copy(autoResume = it.autoResume + jobId) }
        val paused = pauseFlags.getOrPut(jobId) { MutableStateFlow(false) }
        paused.value = false

        // Started only once it is the job's current run, so its end knows whether it still is.
        val run = scope.launch(start = CoroutineStart.LAZY) { runJob(jobId, paused) }
        synchronized(clockLock) {
            live[run] = jobId
            running[jobId] = run
            setStatus(jobId) { it.copy(running = true, paused = false, phase = "מתחיל…", copied = 0, failed = 0) }
            showSavedProgress(jobId)
            startClock(jobId, run)
        }
        // Also when it is cancelled before it was dispatched, which skips its body and its finally.
        run.invokeOnCompletion {
            try {
                finishRun(jobId, run)
            } finally {
                live.remove(run)
            }
        }
        run.start()
        return null
    }

    private suspend fun CoroutineScope.runJob(jobId: String, paused: MutableStateFlow<Boolean>) {
        val self = coroutineContext[CoJob]!!
        // Time spent in FLOOD_WAIT, retries and waiting for an account counts: the job is running.
        val clock = scope.launch {
            while (isActive) {
                delay(runTick)
                flushRunTime(jobId, goOn = true, run = self)
            }
        }
        // The newest id of the source while the job runs (a caught-up ordered loop reads it as well).
        val latestClock = scope.launch {
            suspend fun read(): Boolean = try {
                periodicRefresh(jobId) != null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.warn("[$jobId] קריאת ID אחרון נכשלה: ${e.message}")
                false
            }
            // Every run reads it at its start: a saved one may be stale (below the position it only follows the
            // position). The accounts may still be connecting, so a failed read is tried again a few times.
            for (attempt in 1..START_LATEST_TRIES) {
                if (running[jobId] !== self || read()) break
                if (attempt < START_LATEST_TRIES) delay(latestRetry)
            }
            while (isActive) {
                delay(latestTick)
                if (running[jobId] !== self) break
                read()
            }
        }
        try {
            while (isActive) {
                val current = store.config.value.jobs.firstOrNull { it.id == jobId } ?: break
                val label = current.name.ifBlank { current.id }
                try {
                    // Right after process start the account clients may still be spinning up.
                    withTimeoutOrNull(10_000) {
                        accounts.clients.first { m -> current.accountIds.any { it in m } }
                    }
                    // Regular accounts first: the first one reads the source, and a bot cannot (see botRule).
                    val clients = current.accountIds.mapNotNull { accounts[it] }.sortedBy { it.account.isBot }
                    if (clients.isEmpty()) error("אף אחד מהחשבונות של המשימה לא מוגדר עם API ID")
                    // A rule a restart cannot fix (bots only, or a bot with בלי הורדה) stops the job with its reason.
                    val rule = botRule(clients.map { it.account }, current.viaTakeout)
                    if (rule != null) {
                        log.error("[$label] נעצרה: $rule")
                        stopReasons[jobId] = "⛔ נעצרה: $rule"
                        store.updateConfig { it.copy(autoResume = it.autoResume - jobId) }
                        break
                    }
                    log.info("[$label] מתחיל…")
                    val clearStartFrom = {
                        store.updateConfig { c ->
                            c.copy(jobs = c.jobs.map { if (it.id == jobId) it.copy(startFromId = 0) else it })
                        }
                    }
                    if (current.viaTakeout) {
                        TakeoutJobRunner(
                            job = current,
                            accounts = clients,
                            exports = exports(),
                            store = store,
                            log = log,
                            paused = paused,
                            report = reporter(jobId),
                            clearStartFrom = clearStartFrom,
                        ).run()
                    } else {
                        JobRunner(
                            job = current,
                            accounts = clients,
                            store = store,
                            log = log,
                            paused = paused,
                            report = reporter(jobId),
                            clearStartFrom = clearStartFrom,
                        ).run()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    SentryOnce.exception("job-crash|$jobId|${e::class.java.name}|${e.message}", e) { s -> s.setTag("job", label) }
                    log.error("[$label] ${e.message ?: e::class.simpleName} — מפעיל מחדש בעוד 15 שנ'")
                    setStatus(jobId) { it.copy(phase = "שגיאה — מפעיל מחדש בעוד 15 שנ'") }
                    delay(15_000)
                }
            }
        } finally {
            clock.cancel()
            latestClock.cancel()
        }
    }

    internal fun reporter(jobId: String) = object : JobReporter {
        override fun phase(text: String) = setStatus(jobId) { it.copy(phase = text) }
        override fun position(lastId: Long) {
            setStatus(jobId) { it.copy(lastId = lastId) }
            // The ordered loops read the latest id only once they caught up: a handled message past it is newer.
            if (lastId > (_statuses.value[jobId]?.latestId ?: 0)) saveLatest(jobId, lastId)
        }
        // The runner adds it to Progress.copiedTotal with the message's position.
        override fun copied() = setStatus(jobId) { it.copy(copied = it.copied + 1, copiedTotal = it.copiedTotal + 1) }
        // A source whose newest messages were deleted reads below the position: the position stays the floor,
        // so latest and position do not swap the value (and write the progress) on every round.
        override fun latest(id: Long) = saveLatest(jobId, maxOf(id, _statuses.value[jobId]?.lastId ?: 0))
        override fun delivered(chatId: Long, serverId: Long) =
            setStatus(jobId) { it.copy(targetChatId = chatId, lastTargetId = serverId) }
        override fun failed() = setStatus(jobId) { it.copy(failed = it.failed + 1) }
        override fun sourceInfo(chatId: Long, title: String, isProtected: Boolean) =
            setStatus(jobId) { it.copy(sourceChatId = chatId, sourceTitle = title, isProtected = isProtected) }
    }

    /**
     * Waits until no run of [id] is left, a stopped one still cancelling included. Returns false when
     * [timeoutMs] passed first (a run that was not stopped never ends by itself).
     */
    suspend fun awaitStopped(id: String, timeoutMs: Long = 10_000): Boolean = withTimeoutOrNull(timeoutMs) {
        while (true) {
            val left = live.filterValues { it == id }.keys
            if (left.isEmpty()) break
            left.forEach { it.join() }
            // Its completion handler drops it from [live] right after it completed.
            if (left.any { live.containsKey(it) }) delay(5)
        }
        true
    } ?: false

    fun stop(jobId: String) {
        store.updateConfig { it.copy(autoResume = it.autoResume - jobId) }
        running[jobId]?.let { run ->
            // The time up to now is the run's; a new run may start before this one's finally runs.
            flushRunTime(jobId, goOn = false, run = run)
            run.cancel()
        }
        log.warn("[$jobId] נעצר")
    }

    fun pause(jobId: String) {
        if (!isRunning(jobId)) return
        pauseFlags[jobId]?.value = true
        setStatus(jobId) { it.copy(paused = true, phase = "מושהה") }
        // Paused time is not run time.
        flushRunTime(jobId, goOn = false)
    }

    fun resume(jobId: String) {
        pauseFlags[jobId]?.value = false
        setStatus(jobId) { it.copy(paused = false, phase = "ממשיך…") }
        synchronized(clockLock) {
            val run = running[jobId]
            if (run != null && run.isActive && !runSegments.containsKey(jobId)) startClock(jobId, run)
        }
    }

    fun startAll(): List<String> = store.config.value.jobs.filter { !isRunning(it.id) }.mapNotNull { j ->
        start(j.id)?.let { "${j.name.ifBlank { j.id }}: $it" }
    }

    fun pauseAll() = store.config.value.jobs.forEach { pause(it.id) }

    fun resumeAll() = store.config.value.jobs.forEach { if (isRunning(it.id)) resume(it.id) }

    fun stopAll() = store.config.value.jobs.forEach { if (isRunning(it.id)) stop(it.id) }

    /** A full reset: the position and the run totals (copied, run time, latest id). */
    fun resetProgress(jobId: String) = synchronized(clockLock) {
        store.clearProgress(jobId)
        // The chats shown belong to the old position (an edited job may have a new source): no links into them.
        setStatus(jobId) { it.copy(sourceChatId = 0, sourceTitle = "", targetChatId = 0, lastTargetId = 0) }
        // A running clock starts over from now.
        runSegments[jobId]?.let { runSegments[jobId] = Segment(it.run, wallClock()) }
        showSavedProgress(jobId)
    }

    /** Forgets a job that was already removed from the config (so nothing saves its progress again). */
    fun forget(jobId: String) {
        // Its clock must not write the progress back after it is cleared.
        synchronized(clockLock) { runSegments.remove(jobId) }
        stop(jobId)
        synchronized(clockLock) {
            store.clearProgress(jobId)
            _statuses.update { it - jobId }
        }
    }

    /** Saves [id] as the source's latest id, only while [jobId] is in the config (a deleted job gets none). */
    private fun saveLatest(jobId: String, id: Long) = synchronized(clockLock) {
        if (id <= 0 || store.config.value.jobs.none { it.id == jobId }) return@synchronized
        if (store.progress(jobId).latestId != id) store.saveProgress(jobId) { it.copy(latestId = id) }
        setStatus(jobId) { it.copy(latestId = id) }
    }

    /**
     * Reads the server id of the newest message in [jobId]'s source through one of its logged-in accounts,
     * without starting the job, and saves it (Progress.latestId). Both runners find the source with
     * TDLib's resolveChat on the job's first account, so any logged-in account of the job can read it.
     * Returns the saved id, or null when no account of the job is logged in, the source cannot be read, or the
     * job was deleted or got another source meanwhile.
     */
    suspend fun refreshLatest(jobId: String): Long? {
        val job = store.config.value.jobs.firstOrNull { it.id == jobId } ?: return null
        // The source as it was when the read began: a result for it is not the latest id of a source set since.
        val source = job.source
        if (source.isBlank()) return null
        val ac = job.accountIds.mapNotNull { accounts[it] }.firstOrNull { it.isReady } ?: return null
        var chatId = 0L
        var chatTitle = ""
        val latest = try {
            withTimeoutOrNull(30_000) {
                val chat = ac.resolveChat(source).getOrNull() ?: return@withTimeoutOrNull null
                chatId = chat.id
                chatTitle = chat.title
                ac.latestServerId(chat.id)?.takeIf { it > 0 } ?: chat.lastMessage?.id?.serverId()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        } ?: return null
        return saveRefreshed(jobId, source, latest, chatId, chatTitle)
    }

    /**
     * Saves [id], read by a refresh for [source], as [jobId]'s latest id: the higher of it and the saved one (a run
     * may have reported a newer one while the refresh read). Nothing is saved when the job was deleted or its
     * source is no longer [source]. Returns the saved latest id, or null when nothing was saved.
     */
    internal fun saveRefreshed(
        jobId: String,
        source: String,
        id: Long,
        /** The source's chat, as the refresh found it: a stopped job's card links its numbers into it. */
        chatId: Long = 0,
        chatTitle: String = "",
    ): Long? = synchronized(clockLock) {
        if (id <= 0) return@synchronized null
        val job = store.config.value.jobs.firstOrNull { it.id == jobId } ?: return@synchronized null
        if (job.source != source) return@synchronized null
        if (chatId != 0L) {
            setStatus(jobId) { it.copy(sourceChatId = chatId, sourceTitle = chatTitle.ifBlank { it.sourceTitle }) }
        }
        val best = maxOf(store.progress(jobId).latestId, id)
        saveLatest(jobId, best)
        best
    }

    /** Restarts jobs that were running before the process died. Safe to call repeatedly. */
    fun restore() {
        store.config.value.autoResume.forEach { id ->
            if (!isRunning(id) && store.config.value.jobs.any { it.id == id }) start(id)
        }
    }
}

/** The most of one clock segment that counts as run time, in run ticks: anything longer is the PC asleep. */
private const val MAX_TICKS_PER_SEGMENT = 2

/** How many times a run tries to read its source's latest id at its start (every latestRetry). */
private const val START_LATEST_TRIES = 6
