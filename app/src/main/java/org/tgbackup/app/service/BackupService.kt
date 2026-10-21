package org.tgbackup.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.tgbackup.app.App
import org.tgbackup.app.R
import org.tgbackup.app.ui.MainActivity

/**
 * Foreground service that keeps the process (and the TDLib clients inside it) alive
 * while jobs run, with the screen off or the app in the background.
 */
class BackupService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALL) {
            App.instance.jobs.stopAll()
        }
        goForeground("מתחיל…")
        if (!started) {
            started = true
            acquireWakeLock()
            // After the system restarts a killed service, bring the interrupted jobs back.
            App.instance.jobs.restore()
            observe()
        }
        return START_STICKY
    }

    private fun observe() {
        val jobs = App.instance.jobs
        scope.launch {
            combine(jobs.statuses, App.instance.store.config) { statuses, cfg ->
                val active = statuses.filterValues { it.running }
                if (active.isEmpty()) return@combine null
                val copied = active.values.sumOf { it.copied }
                val names = active.keys.mapNotNull { id -> cfg.jobs.firstOrNull { it.id == id }?.name?.ifBlank { null } }
                val head = "${active.size} משימות פעילות · הועתקו $copied"
                val detail = active.values.firstOrNull()?.phase.orEmpty()
                head to (if (names.isNotEmpty()) names.joinToString() + " — " else "") + detail
            }.collect { info ->
                if (info == null) {
                    // Give a restarting job a moment before deciding nothing runs anymore.
                    delay(3_000)
                    if (!jobs.anyRunning.value) stopEverything()
                } else {
                    notifyStatus(info.first, info.second)
                }
            }
        }
    }

    private fun stopEverything() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun goForeground(text: String) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification("TG Backup פועל", text), type)
    }

    private fun notifyStatus(title: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun buildNotification(title: String, text: String): Notification {
        ensureChannel(this)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BackupService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "עצור הכל", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TGBackup:run").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "backup"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP_ALL = "org.tgbackup.STOP_ALL"

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "גיבוי פעיל", NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
    }
}
