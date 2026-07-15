package dev.punit.tidylink.data.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.punit.tidylink.TidyLinkApplication

/**
 * Safety net for newly saved links: if the app is killed before the inline
 * scrape + classification finish, this worker completes them later. It is
 * enqueued as soon as the placeholder row is saved and cancelled when the
 * inline pipeline succeeds, so it only ever runs after an interrupted save.
 */
class LinkEnrichmentWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val linkId = inputData.getString(KEY_LINK_ID) ?: return Result.failure()
        val repository =
            (applicationContext as TidyLinkApplication).container.linkRepository

        return try {
            repository.completePendingEnrichment(linkId)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_LINK_ID = "link_id"
        private const val MAX_ATTEMPTS = 3
    }
}
