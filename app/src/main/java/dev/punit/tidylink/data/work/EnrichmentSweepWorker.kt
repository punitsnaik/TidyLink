package dev.punit.tidylink.data.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.punit.tidylink.TidyLinkApplication
import java.util.concurrent.TimeUnit

/**
 * Scrapes + classifies every link that still needs it (see
 * [dev.punit.tidylink.data.repository.LinkRepository.refreshUnfetched]).
 * Bulk imports enqueue this instead of doing the work in a ViewModel scope,
 * so a large import survives the user leaving the app, Doze, and process
 * death. Idempotent — safe to enqueue repeatedly.
 */
class EnrichmentSweepWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repository =
            (applicationContext as TidyLinkApplication).container.linkRepository
        return try {
            repository.refreshUnfetched()
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "enrichment_sweep"
        private const val MAX_ATTEMPTS = 3

        fun enqueue(context: Context) {
            // Deliberately NOT expedited: APPEND_OR_REPLACE can chain this
            // request behind a running sweep, and expedited work must not be
            // part of a chain. A plain request still starts promptly while
            // the app is in the foreground (the network constraint is met).
            val request = OneTimeWorkRequestBuilder<EnrichmentSweepWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                // A running sweep may already be past the point where it can
                // see freshly inserted rows — append a follow-up run.
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }
    }
}
