package dev.punit.tidylink.data.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.punit.tidylink.TidyLinkApplication

/**
 * Retries AI classification for links stuck in "Uncategorized" (e.g. saved
 * while offline or when the LLM was rate-limited). Enqueued with a
 * network-connected constraint and exponential backoff.
 */
class ClassificationRetryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repository =
            (applicationContext as TidyLinkApplication).container.linkRepository

        val stillFailing = try {
            repository.retryPendingClassifications()
        } catch (e: Exception) {
            return retryOrGiveUp()
        }

        return if (stillFailing == 0) Result.success() else retryOrGiveUp()
    }

    private fun retryOrGiveUp(): Result =
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()

    private companion object {
        const val MAX_ATTEMPTS = 5
    }
}
