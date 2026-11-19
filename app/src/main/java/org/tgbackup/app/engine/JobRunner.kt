package org.tgbackup.app.engine

import dev.g000sha256.tdl.dto.InputMessageText
import dev.g000sha256.tdl.dto.FormattedText
import dev.g000sha256.tdl.dto.Chat
import dev.g000sha256.tdl.dto.Message
import dev.g000sha256.tdl.dto.MessageText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tgbackup.app.data.Job
import org.tgbackup.app.data.JobMode
import org.tgbackup.app.data.Progress
import org.tgbackup.app.data.Store
import org.tgbackup.app.tg.AccountClient
import org.tgbackup.app.tg.SendOutcome
import org.tgbackup.app.tg.getOrNull
import org.tgbackup.app.tg.serverId
import org.tgbackup.copy.containsArabic
import kotlin.random.Random
import io.sentry.Sentry
import io.sentry.SentryLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class RateLimiter(private val perMinute: Int) {
    private val stamps = ArrayDeque<Long>()
    private val mutex = Mutex()

    suspend fun acquire(onWait: (Long) -> Unit) {
        if (perMinute <= 0) return
        while (true) {
            val wait = mutex.withLock {
                val now = System.currentTimeMillis()
                while (stamps.isNotEmpty() && now - stamps.first() >= 60_000) stamps.removeFirst()
                if (stamps.size < perMinute) {
                    stamps.addLast(now)
                    0L
                } else {
                    60_000 - (now - stamps.first())
                }
            }
            if (wait <= 0) return
            onWait(wait)
            delay(wait)
        }
    }
}

/** How one message's delivery ended (see JobRunner.deliver). */
sealed interface Delivery {
    data object Sent : Delivery

    /** Not sent on purpose: nothing to send, or a kind a protected source cannot give. */
    data object Skipped : Delivery

    /** A FloodWait handed back to the caller (hand-off mode), instead of waiting it out here. */
    data class FloodWait(val seconds: Int) : Delivery

    /** Every retry on this account was refused. */
    data object Failed : Delivery
}

/** Callbacks from a runner to the UI-facing job status. */
interface JobReporter {
    fun phase(text: String)
    fun position(lastId: Long)
    fun copied()

    /** The source's newest message is [id] (server id), as read by latestServerId. */
    fun latest(id: Long)

    /** A message was sent to [chatId]; [serverId] is its id as shown in t.me links. */
    fun delivered(chatId: Long, serverId: Long)
    fun failed()
    fun sourceInfo(chatId: Long, title: String, isProtected: Boolean)
}

/**
 * Runs one job until cancelled. Progress is persisted after every message, so a
 * restart (crash, reboot, manual stop) resumes exactly where it left off.
 */
class JobRunner(
    private val job: Job,
    private val accounts: List<AccountClient>,
    private val store: Store,
    private val log: LogBus,
    private val paused: StateFlow<Boolean>,
    private val report: JobReporter,
    private val clearStartFrom: () -> Unit,
) {
    private val tag = job.name.ifBlank { job.id }
    private val filter = job.contentFilter()
    /** Fast mode: which accounts are "hot" after a FloodWait (they rest longer after each send); no turns here. */
    private val fastHot = SenderRotation(accounts.size)
    private val protection = SourceProtection(false)

    /** The source as resolved by the first account, for the card's protection line. */
    @Volatile private var sourceId = 0L
    @Volatile private var sourceTitle = ""

    /** Fast mode: until when an account is stuck (a long FloodWait); it skips its slice in the waves until then. */
    private val stuckUntil = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    /** Prefetched files nobody sends: each is awaited (TDLib finishes it anyway) and its file deleted. */
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private companion object {
        /** Fast mode: a FloodWait longer than this hands the rest of the account's slice to a free account. */
        const val FAST_HAND_OFF_SEC = 60

        /** Ordered mode reads this many ids at a time. */
        const val ORDERED_WINDOW = 50

        /** A read that takes longer than this is stuck (a FLOOD_WAIT TDLib waits out without telling us). */
        const val READ_STUCK_MS = 60_000L

        /**
         * A server-side copy still pending after this is stuck the same way: cancelled and handed over. Above TDLib's
         * own silent FLOOD_WAIT limit (about 60 s), so a send TDLib is just retrying is not cut in the middle.
         */
        const val SEND_STUCK_MS = 90_000L

        /** Finding one chat for one account longer than this is logged as slow (and reported once). */
        const val SLOW_RESOLVE_MS = 20_000L

        /** A caption may have this many characters without Premium. */
        const val CAPTION_LIMIT = 1024
    }
    private val limiters = accounts.associate { it.account.id to RateLimiter(senderLimit(job.perMinuteLimit, it.account.isBot)) }

    private class Pair2(val source: Chat, val target: Chat)

    suspend fun run() = coroutineScope {
        for (ac in accounts) {
            if (!ac.isReady) {
                report.phase("ממתין לחיבור החשבון ${ac.tag}")
                log.warn("[$tag] החשבון ${ac.tag} לא מחובר — ממתין לחיבור")
                ac.awaitReady()
            }
        }
        report.phase("מאתר ערוצים")
        val chats = accounts.map { ac ->
            val source = timedResolve(ac, job.source, "מקור")
            val target = timedResolve(ac, job.target, "יעד")
            Pair2(source, target)
        }
        // An opened chat keeps its loaded messages in TDLib memory, which copying relies on.
        accounts.forEachIndexed { i, ac -> ac.client.openChat(chats[i].source.id) }
        val source = chats[0].source
        sourceId = source.id
        sourceTitle = source.title
        // Telegram's notices while the job runs: protection removed → server-side copy, added → download + upload.
        // Subscribed before the flag is read, so a change in between is not missed.
        val watch = launch(start = CoroutineStart.UNDISPATCHED) {
            accounts[0].protectionNotices.collect { n ->
                if (protection.onNotice(n.chatId, source.id, n.isProtected)) {
                    report.sourceInfo(source.id, source.title, n.isProtected)
                    if (n.isProtected) {
                        log.warn("[$tag] ערוץ המקור הפך למוגן — עובר להורדה והעלאה")
                    } else {
                        log.info("[$tag] ערוץ המקור כבר לא מוגן — עובר להעתקה רגילה")
                    }
                }
            }
        }
        protection.set(source.hasProtectedContent)
        report.sourceInfo(source.id, source.title, protection.isProtected)
        if (protection.isProtected) log.warn("[$tag] ערוץ המקור מוגן — מצב הורדה והעלאה מחדש")

        if (job.startFromId > 0) {
            store.saveProgress(job.id) { Progress(lastId = job.startFromId - 1).withTotalsOf(it) }
            clearStartFrom()
            log.info("[$tag] מתחיל מהודעה ${job.startFromId}")
        }

        val fast = job.mode == JobMode.FAST && accounts.size > 1
        log.info(
            "[$tag] פעיל: ${source.title} → ${chats[0].target.title} | ${job.contentLabel()} | " +
                "${accounts.size} חשבונות | ${if (fast) "מהיר" else "מסודר"}",
        )
        try {
            if (fast) runFast(chats) else runOrdered(chats)
        } finally {
            watch.cancel()
        }
    }

    /**
     * Finds [raw] for [ac] and logs how long it took, so a slow start shows where it waits. Over [SLOW_RESOLVE_MS] it
     * is a warning, and Sentry hears of it once per job and account.
     */
    private suspend fun timedResolve(ac: AccountClient, raw: String, role: String): Chat {
        report.phase("מאתר ערוץ $role (${ac.tag})")
        val t0 = System.currentTimeMillis()
        val chat = ac.resolveChat(raw).getOrThrow()
        val ms = System.currentTimeMillis() - t0
        val line = "[$tag · ${ac.tag}] ערוץ ה$role נמצא ב-${"%.1f".format(ms / 1000.0)} שנ': ${chat.title}"
        if (ms > SLOW_RESOLVE_MS) {
            // What the connection to Telegram did meanwhile: a network problem shows as connecting / waiting for network.
            val connection = ac.connection.since(t0)
            log.warn("$line (איטי) — החיבור לטלגרם בזמן האיתור: $connection")
            SentryOnce.message("resolve-slow|${job.id}|${ac.account.id}|$role", "Finding a chat took ${ms / 1000} s", SentryLevel.WARNING) { s ->
                s.setTag("job", tag)
                s.setTag("role", if (role == "מקור") "source" else "target")
                s.setExtra("seconds", (ms / 1000).toString())
                s.setExtra("connection", connection)
            }
        } else {
            log.info(line)
        }
        return chat
    }

    private suspend fun awaitNotPaused() {
        if (paused.value) {
            report.phase("מושהה")
            paused.first { !it }
        }
    }

    // ── Ordered: one sender keeps the source order; with a protected source the
    //    other accounts download ahead of it in parallel. ─────────────────────────

    private suspend fun runOrdered(chats: List<Pair2>) = coroutineScope {
        val sender = accounts[0]
        // Several accounts send in turns, one message at a time (the order stays the source's); one account as before.
        val rotation = if (accounts.size > 1) {
            SenderRotation(accounts.size, turnSize = job.messagesPerTurn.coerceIn(1, MAX_MESSAGES_PER_TURN))
        } else {
            null
        }
        val src = chats[0].source.id
        val dst = chats[0].target.id
        var last = store.progress(job.id).lastId
        report.position(last)
        var latest = last

        while (true) {
            ensureActive()
            awaitNotPaused()
            if (last >= latest) {
                latest = sender.latestServerId(src)?.also { report.latest(it) } ?: latest
                if (last >= latest) {
                    report.phase("מעודכן — ממתין להודעות חדשות")
                    delay(60_000)
                    continue
                }
            }
            // The next ids, read by id in one request (never by browsing history: that floods Telegram).
            val ids = idWindow(last, latest, ORDERED_WINDOW)
            report.phase("קורא הודעות ${ids.first}–${ids.last}")
            val batch = readIds(sender, src, ids) ?: continue
            if (batch.isEmpty()) {
                // A run of deleted message ids.
                last = ids.last
                store.saveProgress(job.id) { it.copy(lastId = last) }
                report.position(last)
                continue
            }

            val wanted = batch.filter { filter.accepts(it.content) && !isArabicSkipped(it) }
            // With turns, every other account reads the same ids once (one request each), so a send by it needs no
            // request of its own per message.
            val copies: Map<Int, Map<Long, Message>> = if (rotation == null) {
                emptyMap()
            } else {
                (1 until accounts.size).filter { accounts[it].isReady }.map { i ->
                    async {
                        i to (withTimeoutOrNull(READ_STUCK_MS) { accounts[i].messagesByIds(src, ids) }?.getOrNull()
                            ?.associateBy { it.id }.orEmpty())
                    }
                }.awaitAll().toMap()
            }
            val prefetch = HashMap<Long, Deferred<Result<Copier.Downloaded>>>()
            val window = if (protection.isProtected) maxOf(2, accounts.size * 2) else 0

            for (msg in batch) {
                ensureActive()
                awaitNotPaused()
                var sent = 0
                val idx = wanted.indexOf(msg)
                if (idx >= 0) {
                    if (window > 0) startPrefetch(this, wanted, idx, window, prefetch)
                    report.phase("מעתיק הודעה ${msg.id.serverId()}")
                    val prefetched = prefetch.remove(msg.id)
                    val ok = if (rotation == null) {
                        deliver(sender, dst, msg, prefetched) == Delivery.Sent
                    } else {
                        deliverInTurns(rotation, chats, msg, prefetched, copies)
                    }
                    if (ok) sent = 1
                } else {
                    log.info(
                        "[$tag] דילוג על ${msg.id.serverId()} " +
                            if (filter.accepts(msg.content)) "(ערבית)" else "(לא תואם לסינון)",
                    )
                }
                last = msg.id.serverId()
                store.saveProgress(job.id) { it.copy(lastId = last, copiedTotal = it.copiedTotal + sent) }
                report.position(last)
            }
            prefetch.values.forEach { discard(it) }
            // Deleted ids at the end of the window are passed too.
            if (last < ids.last) {
                last = ids.last
                store.saveProgress(job.id) { it.copy(lastId = last) }
                report.position(last)
            }
        }
    }

    /**
     * One message in ordered mode with several accounts: sent by the next free account in turn. A FloodWait or a
     * refused send moves it to the next account (never two sends at once, so the order holds). The pause after a
     * copy is the sender's own rest, while the next account already sends. True when sent.
     */
    private suspend fun deliverInTurns(
        rotation: SenderRotation,
        chats: List<Pair2>,
        msg: Message,
        prefetched: Deferred<Result<Copier.Downloaded>>?,
        /** The batch as read by each other account (by index), so its send needs no read of its own. */
        copies: Map<Int, Map<Long, Message>> = emptyMap(),
    ): Boolean {
        val sid = msg.id.serverId()
        val tried = HashSet<Int>()
        var pending = prefetched
        try {
            while (true) {
                awaitNotPaused()
                when (val pick = rotation.pick(usable = { accounts[it].isReady }, exclude = tried)) {
                    is SenderRotation.Pick.Wait -> {
                        report.phase("כל החשבונות בהמתנה — ${(pick.ms + 999) / 1000} שנ'")
                        delay(pick.ms)
                    }
                    SenderRotation.Pick.None -> {
                        report.failed()
                        log.error("[$tag] הודעה $sid לא נשלחה — אף חשבון לא הצליח לשלוח אותה")
                        return false
                    }
                    is SenderRotation.Pick.Account -> {
                        val i = pick.index
                        val ac = accounts[i]
                        // Each TDLib instance sends its own copy of the message.
                        val own = if (i == 0) msg else copies[i]?.get(msg.id) ?: ac.client.getMessage(msg.chatId, msg.id).getOrNull()
                        if (own == null) {
                            log.warn("[$tag · ${ac.tag}] לא רואה את הודעה $sid — עובר לחשבון הבא")
                            tried += i
                            continue
                        }
                        // The prefetch goes to the first try only; deliver owns it from there (uses or discards it).
                        val pre = pending
                        pending = null
                        when (val d = deliver(ac, chats[i].target.id, own, pre, floodHandOffAboveSec = 0, failHandOff = true, pauseAfter = false)) {
                            Delivery.Sent -> {
                                val base = if (protection.isProtected) 0L else Random.nextLong(1_400, 3_800)
                                // The rest comes when the account's turn of [Job.messagesPerTurn] messages is over.
                                rotation.sent(i, rotation.restMs(i, base))
                                return true
                            }
                            Delivery.Skipped -> return false
                            is Delivery.FloodWait -> {
                                rotation.busy(i, System.currentTimeMillis() + (d.seconds + 1) * 1000L)
                                log.warn("[$tag · ${ac.tag}] FloodWait ${d.seconds} שנ' — הודעה $sid עוברת לחשבון הבא")
                            }
                            Delivery.Failed -> {
                                tried += i
                                log.warn("[$tag · ${ac.tag}] שליחת הודעה $sid נכשלה — מנסה בחשבון הבא")
                            }
                        }
                    }
                }
            }
        } finally {
            pending?.let { discard(it) }
        }
    }

    private suspend fun startPrefetch(
        scope: CoroutineScope,
        wanted: List<Message>,
        from: Int,
        window: Int,
        prefetch: HashMap<Long, Deferred<Result<Copier.Downloaded>>>,
    ) {
        for (j in from until minOf(from + window, wanted.size)) {
            val m = wanted[j]
            if (m.id in prefetch || !needsUpload(m)) continue
            val helper = accounts[j % accounts.size]
            prefetch[m.id] = scope.async { downloadWith(helper, accounts[0], m) }
        }
    }

    /**
     * Whether [m]'s file must be downloaded and uploaded again. [m] may have been read while the source was protected,
     * when TDLib marks every message can't-be-saved; TDLib computes that when it builds the message, so it is read
     * again before deciding.
     */
    private suspend fun needsUpload(m: Message, isProtected: Boolean = protection.isProtected): Boolean {
        if (m.content.mainFile() == null) return false
        if (isProtected) return true
        if (m.canBeSaved) return false
        val now = accounts[0].client.getMessage(m.chatId, m.id).getOrNull() ?: m
        return !now.canBeSaved
    }

    /** A copy was refused for protection: the whole job switches only when TDLib says the source is protected now. */
    private suspend fun protectionRefused(who: String, sid: Long) {
        val now = accounts[0].refreshChat(sourceId)?.hasProtectedContent ?: true
        if (!now) {
            log.warn("[$who] הודעה $sid מוגנת — מוריד ומעלה רק אותה")
        } else if (protection.set(true)) {
            report.sourceInfo(sourceId, sourceTitle, true)
            log.warn("[$who] זוהה ערוץ מוגן — עובר למצב הורדה והעלאה")
        }
    }

    /** A prefetch that will not be sent: its file is deleted once TDLib has it. */
    private fun discard(d: Deferred<Result<Copier.Downloaded>>) {
        cleanup.launch {
            runCatching { d.await() }.getOrNull()?.getOrNull()?.let { f -> runCatching { f.owner.deleteLocal(f.fileId) } }
        }
    }

    /**
     * Downloads the main file of [m] (which was read by [owner]) using account [ac].
     * File ids are local to each TDLib instance, so a different account loads its own copy of the message.
     */
    private suspend fun downloadWith(ac: AccountClient, owner: AccountClient, m: Message): Result<Copier.Downloaded> {
        val own = if (ac === owner) m else ac.client.getMessage(m.chatId, m.id).getOrNull() ?: m
        val holder = if (own === m) owner else ac
        val file = own.content.mainFile() ?: return Result.failure(RuntimeException("אין קובץ בהודעה"))
        return holder.download(file).map { Copier.Downloaded(holder, file.id, it) }
    }

    // ── Fast: each account copies its own slice of every wave of messages. ───────

    private suspend fun runFast(chats: List<Pair2>) = coroutineScope {
        val n = accounts.size
        while (true) {
            ensureActive()
            awaitNotPaused()
            var p = store.progress(job.id)
            report.position(p.lastId)
            if (p.waveEnd <= p.lastId) {
                val latest = accounts[0].latestServerId(chats[0].source.id)?.also { report.latest(it) }
                if (latest == null) {
                    report.phase("שגיאת רשת — מנסה שוב")
                    delay(30_000)
                    continue
                }
                if (latest <= p.lastId) {
                    report.phase("מעודכן — ממתין להודעות חדשות")
                    delay(60_000)
                    continue
                }
                val size = sliceSize(job.perMinuteLimit)
                val ws = p.lastId + 1
                val we = minOf(p.lastId + n * size, latest)
                store.saveProgress(job.id) { it.copy(waveStart = ws, waveEnd = we, workerLast = emptyMap(), waveChunk = size) }
                p = store.progress(job.id)
                log.info("[$tag] סבב חדש: הודעות $ws–$we (חלק של $size לחשבון)")
            } else {
                log.info("[$tag] ממשיך סבב קיים: ${p.waveStart}–${p.waveEnd}")
            }
            val wave = p
            // A wave keeps the size it was opened with, even if the limit changed since.
            val chunk = waveSliceSize(wave.waveChunk)
            report.phase("סבב ${wave.waveStart}–${wave.waveEnd}")
            // Slice k starts with account k. A stuck account (long FloodWait, not connected) hands the rest of its
            // slice back, and the first free account takes it over: the wave never waits on one account.
            // From the wave's own range, not the account count: a wave saved with more accounts keeps all its slices.
            val slices = ((wave.waveEnd - wave.waveStart) / chunk + 1).toInt()
            val queue = SliceQueue(slices)
            // Slices beyond the accounts have no first holder: they wait for whoever is free.
            for (extra in n until slices) queue.release(extra)
            // True when the slice is done; false when the account got stuck: it leaves this wave (it never holds it)
            // and rejoins a later one once free (stuckUntil).
            suspend fun work(i: Int, slice: Int): Boolean {
                val rs = wave.waveStart + slice * chunk
                val re = minOf(rs + chunk - 1, wave.waveEnd)
                return when (val end = rangeWorker(i, slice, chats[i], rs, re)) {
                    SliceEnd.Done -> {
                        queue.done(slice)
                        true
                    }
                    is SliceEnd.Released -> {
                        stuckUntil[i] = end.untilMs
                        queue.release(slice)
                        false
                    }
                }
            }
            (0 until n).map { i ->
                async {
                    val ac = accounts[i]
                    val stuck = (stuckUntil[i] ?: 0L) > System.currentTimeMillis()
                    if (i < slices) {
                        if (!ac.isReady || stuck) {
                            val re = minOf(wave.waveStart + i * chunk + chunk - 1, wave.waveEnd)
                            if ((store.progress(job.id).workerLast[i] ?: 0) >= re) {
                                // Its slice was already finished (a resumed wave): nothing to hand over.
                                queue.done(i)
                            } else {
                                log.warn(
                                    "[$tag · ${ac.tag}] " + (if (stuck) "עדיין בהמתנת FloodWait" else "החשבון לא מחובר") +
                                        " — החלק שלו עובר לחשבון פנוי",
                                )
                                queue.release(i)
                            }
                            return@async
                        }
                        if (!work(i, i)) return@async
                    } else if (!ac.isReady || stuck) {
                        return@async
                    }
                    while (true) {
                        val slice = queue.next() ?: break
                        if (!ac.isReady) {
                            queue.release(slice)
                            break
                        }
                        log.info("[$tag · ${ac.tag}] ממשיך חלק של חשבון אחר בסבב")
                        if (!work(i, slice)) break
                    }
                }
            }.awaitAll()
            if (!queue.allDone) {
                // Every account that could take the rest is stuck or offline: the same wave resumes when the first
                // stuck one is free (at most 5 minutes between checks, at least 15 s).
                val now = System.currentTimeMillis()
                val wait = (stuckUntil.values.filter { it > now }.minOrNull()?.minus(now) ?: 15_000L).coerceIn(15_000L, 300_000L)
                report.phase("ממתין לחשבון פנוי להמשך הסבב — ${wait / 1000} שנ'")
                delay(wait)
                continue
            }
            store.saveProgress(job.id) { it.copy(lastId = it.waveEnd, waveStart = 0, waveEnd = 0, workerLast = emptyMap(), waveChunk = 0) }
            report.position(wave.waveEnd)
        }
    }

    private fun isArabicSkipped(msg: Message): Boolean {
        if (!job.skipArabic) return false
        val c = msg.content
        val text = if (c is MessageText) c.text.text else c.caption()?.text
        return text != null && containsArabic(text)
    }

    /**
     * Ordered mode's read of [ids] from [ac]: null after an error or a read stuck for a minute (TDLib waits out a
     * FLOOD_WAIT by itself and never reports it), then the caller reads again.
     */
    private suspend fun readIds(ac: AccountClient, chatId: Long, ids: LongRange): List<Message>? {
        val read = withTimeoutOrNull(READ_STUCK_MS) { ac.messagesByIds(chatId, ids) }
        if (read == null) {
            log.warn("[$tag] קריאת ההודעות ${ids.first}–${ids.last} תקועה יותר מדקה — מנסה שוב")
            report.phase("טלגרם מאט את הקריאה — מנסה שוב")
            return null
        }
        return read.getOrElse { e ->
            log.error("[$tag] קריאת הודעות נכשלה: ${e.message}")
            report.phase("שגיאת רשת — מנסה שוב")
            delay(15_000)
            null
        }
    }

    /** How a slice ended for the account that worked it. */
    private sealed interface SliceEnd {
        data object Done : SliceEnd

        /** The account is stuck until [untilMs]; the rest of the slice goes to another account. */
        data class Released(val untilMs: Long) : SliceEnd
    }

    /** Account [i] works slice [slice] (its messages [rs]..[re]) from where the slice got to, by anyone. */
    private suspend fun rangeWorker(i: Int, slice: Int, chats: Pair2, rs: Long, re: Long): SliceEnd {
        val ac = accounts[i]
        var last = maxOf(rs - 1, store.progress(job.id).workerLast[slice] ?: 0)
        while (last < re) {
            awaitNotPaused()
            // An account that went offline hands the rest over (also between batches and after failed reads).
            if (!ac.isReady) return SliceEnd.Released(System.currentTimeMillis())
            // The rest of the slice, read by id in one request. TDLib waits out a FLOOD_WAIT on reads by itself and
            // never says so: a read stuck for a minute counts as stuck, and the slice goes to a free account.
            val ids = (last + 1)..re
            val read = withTimeoutOrNull(READ_STUCK_MS) { ac.messagesByIds(chats.source.id, ids) }
            if (read == null) {
                log.warn("[$tag · ${ac.tag}] קריאת ההודעות ${ids.first}–${ids.last} תקועה יותר מדקה — שאר החלק עובר לחשבון פנוי")
                return SliceEnd.Released(System.currentTimeMillis() + READ_STUCK_MS)
            }
            val inRange = read.getOrElse { e ->
                log.error("[$tag · ${ac.tag}] קריאת הודעות נכשלה: ${e.message}")
                // A bot that cannot read the source never will: the slice goes to a regular account.
                if (ac.account.isBot) return SliceEnd.Released(System.currentTimeMillis() + READ_STUCK_MS)
                delay(15_000)
                null
            } ?: continue
            // A bot that gets nothing back may not see the source: a regular account reads the slice (and passes
            // deleted messages), so nothing is skipped unseen.
            if (inRange.isEmpty() && ac.account.isBot) {
                log.warn("[$tag · ${ac.tag}] הבוט לא קיבל הודעות ${ids.first}–${ids.last} — החלק עובר לחשבון רגיל")
                return SliceEnd.Released(System.currentTimeMillis() + READ_STUCK_MS)
            }
            for (msg in inRange) {
                awaitNotPaused()
                if (!ac.isReady) return SliceEnd.Released(System.currentTimeMillis())
                var sent = 0
                if (filter.accepts(msg.content)) {
                    if (isArabicSkipped(msg)) {
                        log.info("[$tag · ${ac.tag}] דילוג על ${msg.id.serverId()} (ערבית)")
                    } else {
                        val d = deliver(
                            ac, chats.target.id, msg, null, floodHandOffAboveSec = FAST_HAND_OFF_SEC, pauseAfter = false,
                            onFloodWait = { s -> markHot(i, s) },
                        )
                        when (d) {
                            Delivery.Sent -> {
                                sent = 1
                                // The pause after each copy; three times longer (at least 5 s) while the account is hot.
                                val base = if (protection.isProtected) 0L else Random.nextLong(1_400, 3_800)
                                delay(fastHot.restMs(i, base))
                            }
                            is Delivery.FloodWait -> {
                                // This message is not done: the account that takes the slice starts from it.
                                log.warn("[$tag · ${ac.tag}] FloodWait ${d.seconds} שנ' — שאר החלק עובר לחשבון פנוי")
                                return SliceEnd.Released(System.currentTimeMillis() + (d.seconds + 1) * 1000L)
                            }
                            // A send that failed because the account went offline is not done: hand the rest over.
                            Delivery.Failed -> if (!ac.isReady) return SliceEnd.Released(System.currentTimeMillis())
                            Delivery.Skipped -> Unit
                        }
                    }
                }
                last = msg.id.serverId()
                store.saveProgress(job.id) { it.copy(workerLast = it.workerLast + (slice to last), copiedTotal = it.copiedTotal + sent) }
            }
            // Every id of the slice was read: deleted ones (missing from the result) are passed too.
            last = re
            store.saveProgress(job.id) { it.copy(workerLast = it.workerLast + (slice to re)) }
        }
        return SliceEnd.Done
    }

    /** Fast mode: account [i] got a FloodWait of [seconds] (or a stuck send): it is hot for a while. */
    private fun markHot(i: Int, seconds: Int) {
        val wasHot = fastHot.isHot(i)
        fastHot.busy(i, System.currentTimeMillis() + (seconds + 1) * 1000L)
        if (!wasHot) log.warn("[$tag · ${accounts[i].tag}] החשבון \"חם\" — מנוחה ארוכה יותר אחרי כל הודעה לזמן מה")
    }

    // ── Delivery of one message, with FloodWait handled by retrying the same message. ──

    private suspend fun deliver(
        sender: AccountClient,
        target: Long,
        msg: Message,
        prefetched: Deferred<Result<Copier.Downloaded>>?,
        /** A FloodWait longer than this goes back to the caller as [Delivery.FloodWait]; null: always waited out here. */
        floodHandOffAboveSec: Int? = null,
        /** The caller tries another account after [Delivery.Failed], and counts the failure itself. */
        failHandOff: Boolean = false,
        /** The random pause after a server-side copy is taken here (false: the caller rests this account instead). */
        pauseAfter: Boolean = true,
        /** Told of every FloodWait (seconds) and stuck send, also those waited out here. */
        onFloodWait: (Int) -> Unit = {},
    ): Delivery {
        val sid = msg.id.serverId()
        val who = if (accounts.size > 1) "$tag · ${sender.tag}" else tag
        var attempts = 0
        var downloaded: Copier.Downloaded? = null
        var uploadCopy: File? = null
        // Set when a server-side copy of this message failed: retry it as download + upload.
        var forceUpload = false
        var unusedPrefetch = prefetched
        // A caption over the sender's limit: the media goes with its head, the rest follows as text right after it.
        var captionHead: FormattedText? = null
        var captionRest: FormattedText? = null
        // A replaced caption refused as too long is tried once more as the original one, before it is split.
        var keepOriginal = false
        try {
            while (true) {
                awaitNotPaused()
                // Read once: the watcher may change it between the two uses.
                val isProtected = protection.isProtected
                val upload = needsUpload(msg, isProtected) || (forceUpload && msg.content.mainFile() != null)
                if (isProtected && !upload && msg.content !is MessageText) {
                    log.warn("[$who] הודעה $sid מסוג שלא ניתן להעתיק מערוץ מוגן — דילוג")
                    return Delivery.Skipped
                }
                val content = if (upload) {
                    if (downloaded == null) {
                        report.phase("מוריד הודעה $sid")
                        // A prefetch is used once: after a failed one the next try downloads with the sender.
                        val pre = unusedPrefetch
                        unusedPrefetch = null
                        downloaded = (pre?.await() ?: downloadWith(sender, sender, msg)).getOrElse { e ->
                            log.error("[$who] הורדת הודעה $sid נכשלה: ${e.message}")
                            null
                        }
                    }
                    val d = downloaded
                    if (d == null) {
                        if (++attempts >= 3) {
                            if (!failHandOff) report.failed()
                            return Delivery.Failed
                        }
                        delay(5_000)
                        continue
                    }
                    // Upload from a fresh copy: given TDLib's own cached path, TDLib would reuse the
                    // protected remote file and Telegram rejects it as "can't be forwarded".
                    val copy = uploadCopy ?: freshCopy(sender, sid, d.path).getOrElse { e ->
                        log.error("[$who] הכנת קובץ להעלאה נכשלה: ${e.message}")
                        if (!failHandOff) report.failed()
                        return Delivery.Failed
                    }.also { uploadCopy = it }
                    Copier.uploadContent(msg, copy.absolutePath, job.stripLinks, captionHead)
                } else {
                    Copier.copyContent(msg, job.stripLinks, captionHead, keepOriginalCaption = keepOriginal)
                }
                if (content == null) {
                    log.info("[$who] דילוג על $sid (ריק אחרי ניקוי קישורים)")
                    return Delivery.Skipped
                }

                limiters.getValue(sender.account.id).acquire { ms ->
                    report.phase("תקרת ${job.perMinuteLimit} הודעות לדקה — ממתין ${ms / 1000} שנ'")
                }
                report.phase(if (upload) "מעלה הודעה $sid" else "מעתיק הודעה $sid")
                // In hand-off modes a server-side copy pending for a minute is cancelled and handed over (TDLib waits
                // out a FLOOD_WAIT on sends silently). Uploads are never cut short: a big file may take that long.
                val stuckAfter = if (floodHandOffAboveSec != null && !upload) SEND_STUCK_MS else null
                when (val r = sender.sendAndWait(target, content, stuckAfter)) {
                    is SendOutcome.Sent -> {
                        report.copied()
                        report.delivered(target, r.messageId.serverId())
                        log.success("[$who] הועתקה הודעה $sid")
                        captionRest?.let { rest ->
                            val ok = sendCaptionRest(sender, target, rest) { log.warn("[$who] שליחת המשך הכיתוב של $sid נכשלה: $it") }
                            if (!ok) log.error("[$who] המשך הכיתוב של הודעה $sid לא נשלח")
                        }
                        if (pauseAfter && !protection.isProtected) delay(Random.nextLong(1_400, 3_800))
                        return Delivery.Sent
                    }
                    is SendOutcome.Failed -> {
                        if (r.retryAfterSec > 0) {
                            onFloodWait(r.retryAfterSec)
                            if (floodHandOffAboveSec != null && r.retryAfterSec > floodHandOffAboveSec) {
                                return Delivery.FloodWait(r.retryAfterSec)
                            }
                            log.warn("[$who] FloodWait ${r.retryAfterSec} שנ' — ממתין ומנסה שוב את הודעה $sid")
                            report.phase("FloodWait — ממתין ${r.retryAfterSec} שנ'")
                            delay((r.retryAfterSec + 1) * 1000L)
                            continue
                        }
                        // No answer for 3 hours: it may still go out, but a missing message is worse than a duplicate (the user's
                        // rule), so it is retried like any refused send (and handed to the next account in ordered mode).
                        if (r.code == AccountClient.SEND_TIMEOUT) {
                            log.warn("[$who] אין תשובה מטלגרם על הודעה $sid — שולח שוב (עדיף כפילות על הודעה חסרה)")
                        }
                        if (r.code == AccountClient.SEND_STUCK) {
                            onFloodWait((SEND_STUCK_MS / 1000).toInt())
                            log.warn("[$who] שליחת הודעה $sid תקועה יותר מדקה וחצי (טלגרם מאט) — בוטלה ועוברת לחשבון אחר")
                            return Delivery.FloodWait((SEND_STUCK_MS / 1000).toInt())
                        }
                        if (r.code == AccountClient.SEND_DISCONNECTED) {
                            log.warn("[$who] החשבון התנתק לפני שהודעה $sid נשלחה")
                            if (!failHandOff) report.failed()
                            return Delivery.Failed
                        }
                        if (isCaptionTooLong(r.message)) {
                            val replaced = job.stripLinks && msg.content.caption() != null && !keepOriginal
                            when (captionRetry(upload, replaced, keepOriginal, captionHead != null)) {
                                CaptionStep.KeepOriginal -> {
                                    keepOriginal = true
                                    log.warn("[$who] הכיתוב של הודעה $sid ארוך מדי — מנסה שוב עם הכיתוב המקורי (הקישורים נשארים)")
                                    continue
                                }
                                CaptionStep.Split -> {
                                    val full = msg.content.caption()?.let { cleanText(it, job.stripLinks) }
                                    if (full != null) {
                                        val (head, rest) = splitFormatted(full, CAPTION_LIMIT)
                                        captionHead = head
                                        captionRest = rest
                                        keepOriginal = false
                                        log.warn("[$who] הכיתוב של הודעה $sid ארוך מדי — שולח עם תחילת הכיתוב, והמשך כהודעת טקסט אחריה")
                                        continue
                                    }
                                }
                                CaptionStep.GiveUp -> Unit
                            }
                        }
                        // (A timeout is not a refusal of the copy: it is retried as it was, not as an upload.)
                        if (r.code != AccountClient.SEND_TIMEOUT && !upload && msg.content.mainFile() != null) {
                            forceUpload = true
                            if (Copier.isProtectedError(r.message)) {
                                protectionRefused(who, sid)
                            } else {
                                log.warn("[$who] העתקה ישירה של $sid נכשלה (${r.message}) — מנסה בהורדה והעלאה")
                            }
                            continue
                        }
                        log.error("[$who] שליחת הודעה $sid נכשלה: ${r.code} ${r.message}")
                        if (attempts == 2) {
                            Sentry.captureMessage("Send failed: ${r.code} ${r.message}", SentryLevel.WARNING) { s ->
                                s.setTag("job", tag)
                                s.setExtra("message_id", sid.toString())
                                s.setExtra("upload", upload.toString())
                            }
                        }
                        // Reload the source message in case TDLib dropped it from memory.
                        sender.client.getMessage(msg.chatId, msg.id)
                        if (++attempts >= 3) {
                            if (!failHandOff) report.failed()
                            return Delivery.Failed
                        }
                        delay(3_000L * attempts)
                    }
                }
            }
        } finally {
            downloaded?.let { runCatching { it.owner.deleteLocal(it.fileId) } }
            // Downloaded ahead while the source was protected, then copied server-side: its file is deleted.
            unusedPrefetch?.let { discard(it) }
            uploadCopy?.parentFile?.deleteRecursively()
        }
    }

    private suspend fun freshCopy(sender: AccountClient, sid: Long, path: String): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val src = File(path)
                val dir = File(sender.baseDir, "upload/$sid").apply { mkdirs() }
                File(dir, src.name).also { src.copyTo(it, overwrite = true) }
            }
        }
}
