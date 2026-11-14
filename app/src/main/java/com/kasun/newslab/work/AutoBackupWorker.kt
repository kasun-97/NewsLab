package com.kasun.newslab.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kasun.newslab.data.AutoBackup
import com.kasun.newslab.data.ImportResult
import com.kasun.newslab.data.Prefs
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** Writes the weekly automatic backup; see [AutoBackup]. */
class AutoBackupWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // The setting decides, not the schedule: a reset wipes the setting but leaves
        // this job queued, and it must then do nothing.
        if (Prefs.get(applicationContext).autoBackupFolder.first().isBlank()) return Result.success()
        return when (AutoBackup.run(applicationContext)) {
            is ImportResult.Success -> Result.success()
            // A full disk or a busy provider may clear; a revoked folder won't, so stop
            // after a few tries and leave the reason on show in Settings until next week.
            is ImportResult.Failure -> if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "newslab-auto-backup"
        private const val MAX_RETRIES = 3

        /**
         * Starts the weekly schedule afresh. A periodic job's first run is immediate, so
         * switching the backup on (or picking another folder) writes one straight away,
         * which also shows at once whether the folder can be written to.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(AutoBackup.INTERVAL_DAYS, TimeUnit.DAYS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
