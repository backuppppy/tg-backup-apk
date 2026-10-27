package org.tgbackup.app

import android.app.Application
import android.content.Intent
import android.os.Build
import io.sentry.android.core.SentryAndroid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.tgbackup.app.data.Store
import org.tgbackup.app.engine.JobManager
import org.tgbackup.app.engine.LogBus
import org.tgbackup.app.export.ExportManager
import org.tgbackup.app.service.BackupService
import org.tgbackup.app.tg.AccountManager

class App : Application() {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var store: Store
        private set
    lateinit var log: LogBus
        private set
    lateinit var accounts: AccountManager
        private set
    lateinit var jobs: JobManager
        private set
    lateinit var exports: ExportManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        SentryAndroid.init(this) { options ->
            options.dsn = SENTRY_DSN
            options.release = "tgbackup-export@${BuildConfig.VERSION_NAME}"
            options.tracesSampleRate = 0.0
            options.isAttachThreads = true
        }
        log = LogBus(filesDir)
        store = Store(filesDir)
        accounts = AccountManager(filesDir, log)
        jobs = JobManager(store, accounts, log, scope) { exports }
        exports = ExportManager(filesDir, accounts, log)
        if (store.migratedFromLegacy) {
            log.info("ההגדרות יובאו מהגרסה הקודמת. יש להתחבר מחדש לכל חשבון (פעם אחת).")
        }

        // Keep one TDLib client per configured account.
        scope.launch {
            store.config.map { it.accounts }.distinctUntilChanged().collect { accounts.sync(it) }
        }
        // A foreground service holds the process alive whenever any job runs.
        scope.launch {
            jobs.anyRunning.collect { running -> if (running) startBackupService() }
        }
    }

    fun startBackupService() {
        val intent = Intent(this, BackupService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        }.onFailure { log.warn("לא ניתן להפעיל שירות רקע: ${it.message}") }
    }

    companion object {
        // The takeout-export branch reports to its own Sentry project, apart from
        // the regular TG Backup builds.
        const val SENTRY_DSN = "https://e02d804de729ebc80b61b875138bc50e@o4510215210598400.ingest.de.sentry.io/4512308552859728"

        lateinit var instance: App
            private set
    }
}
