package dev.punit.tidylink.data.work

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.punit.tidylink.TidyLinkApplication
import dev.punit.tidylink.data.settings.BackupStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Writes a JSON export into a user-chosen folder on a weekly schedule.
 *
 * There is no backend, so the phone is the only copy of the library - this
 * is the one failure mode the architecture can't defend against on its own.
 * Off by default; the user picks the folder.
 *
 * Uses [DocumentsContract] directly rather than pulling in
 * androidx.documentfile: the whole of what's needed here is create, list
 * and delete, which is about thirty lines. A tree URI (not the
 * CreateDocument URI the manual export uses) is what survives a reboot,
 * once persisted with takePersistableUriPermission.
 */
class BackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as TidyLinkApplication
        val store = BackupStore(applicationContext)
        // The enabled flag, not the folder URI. disable() keeps the folder on
        // purpose, so a URI check alone lets an already-queued run write into
        // the user's folder after they switched backups off.
        if (!store.isEnabled()) return Result.success()
        val treeUriString = store.currentFolderUri() ?: return Result.success()

        return try {
            writeBackup(Uri.parse(treeUriString), app)
            store.recordSuccess(System.currentTimeMillis())
            Result.success()
        } catch (e: Exception) {
            // Surfaced in Settings rather than swallowed. A backup that
            // stopped working silently is worse than no backup, because the
            // user believes they have one.
            store.recordFailure()
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    private suspend fun writeBackup(treeUri: Uri, app: TidyLinkApplication) {
        val resolver = applicationContext.contentResolver
        val dirUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        // Built per call, never shared: SimpleDateFormat is not thread-safe,
        // and enableBackup() fires schedule() and runNow() back to back under
        // two different unique work names, so two workers can format at once.
        // A garbled date would also corrupt the last-3 rotation, which sorts
        // on this filename.
        val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val name = "$FILE_PREFIX$stamp.json"

        val existing = listBackups(resolver, treeUri)

        // Same-day re-run: SAF would otherwise create "name (1).json", so
        // the day's backup would fork instead of being replaced.
        existing.filter { it.second == name }
            .forEach { (uri, _) -> runCatching { DocumentsContract.deleteDocument(resolver, uri) } }

        val fileUri = DocumentsContract.createDocument(resolver, dirUri, MIME_JSON, name)
            ?: error("could not create $name in the chosen folder")

        resolver.openOutputStream(fileUri)?.use { stream ->
            app.container.linkRepository.exportLinks(stream)
        } ?: error("could not open $name for writing")

        // Rotate AFTER a successful write, never before - pruning first
        // would risk deleting the only good copy to make room for a write
        // that then fails.
        listBackups(resolver, treeUri)
            .sortedByDescending { it.second }
            .drop(KEEP_BACKUPS)
            .forEach { (uri, _) -> runCatching { DocumentsContract.deleteDocument(resolver, uri) } }
    }

    /** (documentUri, displayName) for every file this app has written here. */
    private fun listBackups(
        resolver: ContentResolver,
        treeUri: Uri,
    ): List<Pair<Uri, String>> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
        return resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val displayName = cursor.getString(1) ?: continue
                    // Only ever touch our own files. The user picked a real
                    // folder that may hold anything else they own.
                    if (!displayName.startsWith(FILE_PREFIX)) continue
                    add(
                        DocumentsContract.buildDocumentUriUsingTree(
                            treeUri,
                            cursor.getString(0),
                        ) to displayName
                    )
                }
            }
        }.orEmpty()
    }

    companion object {
        private const val UNIQUE_PERIODIC = "scheduled_backup"
        private const val UNIQUE_ONE_OFF = "backup_now"
        private const val MAX_ATTEMPTS = 3
        private const val MIME_JSON = "application/json"
        /** The last-3 rotation relies on this name sorting lexicographically. */
        private const val FILE_PREFIX = "tidylink-backup-"

        /** Enough history to survive a bad export without filling the folder. */
        private const val KEEP_BACKUPS = 3

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BackupWorker>(7, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /**
         * Cancels BOTH names. [runNow] enqueues a separate one-off, so
         * cancelling only the periodic work leaves an already-queued
         * immediate backup free to fire after the user turned this off.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).run {
                cancelUniqueWork(UNIQUE_PERIODIC)
                cancelUniqueWork(UNIQUE_ONE_OFF)
            }
        }

        /**
         * Runs one backup immediately, used when the user first turns this
         * on. Waiting a week to discover the folder choice didn't work
         * would defeat the point.
         */
        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_ONE_OFF,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<BackupWorker>().build(),
            )
        }
    }
}
