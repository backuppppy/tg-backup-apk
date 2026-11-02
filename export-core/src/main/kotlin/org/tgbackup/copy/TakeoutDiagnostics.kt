package org.tgbackup.copy

/** Pure decisions behind the takeout FAST diagnostics (what gets logged / reported to Sentry). */
object TakeoutDiagnostics {
    data class Slice(val index: Int, val start: Long, val end: Long)

    /** Splits wave [ws]..[we] into one slice of up to [chunk] ids per worker; trailing workers may get none. */
    fun slices(ws: Long, we: Long, workers: Int, chunk: Int): List<Slice> = (0 until workers).mapNotNull { i ->
        val rs = ws + i.toLong() * chunk
        if (rs > we) null else Slice(i, rs, minOf(rs + chunk - 1, we))
    }

    /** A new wave opens past the id right after the last finished one. */
    fun waveJump(lastId: Long, ws: Long): Boolean = ws > lastId + 1

    /** The worker processed nothing from its slice (last never reached [rs]). */
    fun sliceSkipped(last: Long, rs: Long): Boolean = last < rs

    /** Saved progress exists although the job is not being started from an explicit id. */
    fun staleProgress(lastId: Long, waveEnd: Long, startFromId: Long): Boolean =
        startFromId == 0L && (lastId > 0 || waveEnd > 0)

    /** Saved progress belongs to another source; 0 means unknown (older progress file) and is kept. */
    fun sourceChanged(savedSourceId: Long, sourceId: Long): Boolean = savedSourceId != 0L && savedSourceId != sourceId

    fun firstFar(firstId: Long, rs: Long, chunk: Int): Boolean = firstId > rs + chunk

    fun outsideSlice(id: Long, rs: Long, re: Long): Boolean = id < rs || id > re

    /** A wave does not start right after the previous one ended; [prevWaveEnd] 0 = unknown. */
    fun waveGap(prevWaveEnd: Long, ws: Long): Boolean = prevWaveEnd > 0 && ws != prevWaveEnd + 1

    fun incompleteSlice(workerLast: Long, re: Long, endedNormally: Boolean): Boolean = workerLast < re && !endedNormally

    /**
     * How a slice worker's loop stopped: reaching [re] is normal; so is a non-empty batch that lies past [re]
     * (the slice's tail is empty); an empty batch before [re] is not.
     */
    fun endedNormally(last: Long, re: Long, batchEmpty: Boolean): Boolean = last >= re || !batchEmpty

    /** A resumed wave was cut for more slices than there are workers (fewer is indistinguishable from a wave capped by latest). */
    fun accountsChanged(ws: Long, we: Long, chunk: Int, workers: Int): Boolean =
        (we - ws + chunk) / chunk > workers

    /** A jump too big to be a few deleted ids: worth asking another account and reading the first id directly. */
    fun largeJump(lastId: Long, ws: Long, chunk: Int): Boolean = lastId == 0L || ws - lastId > chunk

    /** Why saved progress is reported on start. */
    fun staleReason(sourceChanged: Boolean, viaTakeout: Boolean, lastId: Long): String = when {
        sourceChanged -> "source-changed"
        !viaTakeout && lastId > 0 -> "mode-reset"
        else -> "resume"
    }
}
