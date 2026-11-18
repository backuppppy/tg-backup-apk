package org.tgbackup.app.ui

import org.tgbackup.app.engine.JobStatus

/** A count with a comma between thousands: 12,340 (as the desktop's job card). */
fun formatCount(n: Long): String = "%,d".format(java.util.Locale.US, n)

/** A run time as H:MM:SS; the hours go on past a day (25:01:01). */
fun formatRunTime(ms: Long): String {
    val secs = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d:%02d".format(secs / 3600, secs / 60 % 60, secs % 60)
}

/** The engine writes the run time every 10 s and counts at most two of those per segment (the rest is sleep). */
private const val MAX_LIVE_MILLIS = 20_000L

/** The job's run time at [now]: the saved time, plus the time since runningSince while the clock runs. */
fun runMillisAt(status: JobStatus, now: Long): Long {
    val live = if (status.runningSince > 0) (now - status.runningSince).coerceAtLeast(0) else 0
    return status.runMillis + minOf(live, MAX_LIVE_MILLIS)
}

/** The run time line shows once there is a saved time or the clock runs. */
fun runTimeShown(status: JobStatus): Boolean = status.runMillis > 0 || status.runningSince > 0

/** The phase as shown: the message a copy or upload phase names is in the target ("מעתיק הודעה ביעד 9384"). */
fun phaseText(phase: String): String = phase.replace(Regex("^(מעתיק|מעלה) הודעה(?= )"), "$1 הודעה ביעד")
