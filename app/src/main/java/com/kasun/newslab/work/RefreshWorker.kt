package com.kasun.newslab.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kasun.newslab.data.Prefs
import com.kasun.newslab.data.Repository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** Periodically fetches all feeds, notifying on new keyword matches. */
class RefreshWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        val prefs = Prefs.get(applicationContext)
        val notify = prefs.notificationsEnabled.first()
        val result = Repository(applicationContext).refresh(notify)

        // Individual dead feeds aren't retryable-with-backoff conditions, but every
        // feed failing at once usually means the network dropped mid-run.
        if (result.attempted > 0 && result.failedFeeds.size == result.attempted) {
            Result.retry()
        } else {
            Result.success()
        }
    } catch (e: CancellationException) {
        // The OS stopped the worker; propagating lets WorkManager reschedule
        // normally instead of counting a backoff retry.
        throw e
    } catch (_: Exception) {
        Result.retry()
    }

    companion object {
        private const val UNIQUE_NAME = "newslab-refresh"

        /** 15 minutes is the minimum periodic interval WorkManager allows. */
        const val MIN_INTERVAL_MINUTES = 15L

        /** (Re)schedules the periodic refresh, updating the interval if it already exists. */
        fun schedule(context: Context, intervalMinutes: Long = MIN_INTERVAL_MINUTES) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(
                intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES), TimeUnit.MINUTES,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
