package dev.punit.tidylink.data.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.punit.tidylink.TidyLinkApplication

/**
 * Persistence guarantee for the share sheet: the URL is committed to
 * WorkManager's database at enqueue time, so a share can no longer be lost
 * if the (invisible, immediately-finishing) [dev.punit.tidylink.ShareReceiverActivity]
 * process dies before the inline save completes. The save itself is
 * idempotent - whichever of the inline path or this worker runs second just
 * finds the existing row.
 */
class SaveUrlWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val repository =
            (applicationContext as TidyLinkApplication).container.linkRepository
        return try {
            repository.processAndSaveUrl(url)
            Result.success()
        } catch (e: Exception) {
            if (shouldRetry(runAttemptCount, MAX_ATTEMPTS)) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_URL = "url"
        private const val MAX_ATTEMPTS = 3

        fun enqueue(context: Context, url: String) {
            val request = OneTimeWorkRequestBuilder<SaveUrlWorker>()
                .setInputData(workDataOf(KEY_URL to url))
                // No network constraint: the placeholder insert must happen
                // even offline; enrichment has its own retry safety net.
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                saveWorkName(url),
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
