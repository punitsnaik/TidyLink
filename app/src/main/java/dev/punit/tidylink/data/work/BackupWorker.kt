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
import dev.punit.tidylink.data.repository.readBackupLinks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.io.OutputStream

private const val FILE_PREFIX = "tidylink-backup-"
private val BACKUP_NAME = Regex(
    "^tidylink-backup-\\d{4}-\\d{2}-\\d{2}(?:T\\d{9}(?:-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})?)?\\.json$"
)

internal fun backupFileName(now: Long): String = FILE_PREFIX +
    SimpleDateFormat("yyyy-MM-dd'T'HHmmssSSS", Locale.US).format(Date(now)) + "-${UUID.randomUUID()}.json"

internal fun isOwnedBackupName(name: String): Boolean = BACKUP_NAME.matches(name)

// Periodic and one-off workers share the same folder and retention history.
private val backupMutex = Mutex()

/**
 * Writes a JSON export into a user-chosen folder on a weekly schedule.
 *
 * There is no backend, so the phone is the only copy of the library - this
 * is the one failure mode the architecture can't defend against on its own.
 * Off by default; the user picks the folder.
 *
 * Uses [DocumentsContract] directly rather than pulling in
 * androidx.documentfile: the operations needed here are create, read, list
 * and delete. A tree URI (not the
 * CreateDocument URI the manual export uses) is what survives a reboot,
 * once persisted with takePersistableUriPermission.
 */
class BackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as TidyLinkApplication
        // The container's store, NOT a fresh one. BackupState is a
        // per-instance MutableStateFlow and nothing listens for preference
        // changes, so recordSuccess/recordFailure on a second instance land
        // in a flow no collector is attached to - Settings would keep
        // showing "hasn't run yet" while backups succeed, and show nothing
        // at all while they fail, which is the one thing doWork's catch
        // block below exists to prevent.
        val store = app.container.backupStore
        // The enabled flag, not the folder URI. disable() keeps the folder on
        // purpose, so a URI check alone lets an already-queued run write into
        // the user's folder after they switched backups off.
        if (!store.isEnabled()) return Result.success()
        val treeUriString = store.currentFolderUri() ?: return Result.success()

        return try {
            writeBackupDocument(applicationContext.contentResolver, Uri.parse(treeUriString)) {
                app.container.linkRepository.exportLinks(it)
            }
            store.recordSuccess(System.currentTimeMillis())
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Surfaced in Settings rather than swallowed. A backup that
            // stopped working silently is worse than no backup, because the
            // user believes they have one.
            store.recordFailure()
            if (shouldRetry(runAttemptCount, MAX_ATTEMPTS)) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val UNIQUE_PERIODIC = "scheduled_backup"
        private const val UNIQUE_ONE_OFF = "backup_now"
        private const val MAX_ATTEMPTS = 3

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BackupWorker>(7, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request,
            )
        }

        /** Both names must stop: runNow is separate from the weekly schedule. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).run {
                cancelUniqueWork(UNIQUE_PERIODIC)
                cancelUniqueWork(UNIQUE_ONE_OFF)
            }
        }

        /** Check the chosen folder immediately rather than discovering failure a week later. */
        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_ONE_OFF, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<BackupWorker>().build(),
            )
        }
    }
}

internal suspend fun writeBackupDocument(
    resolver: ContentResolver,
    treeUri: Uri,
    export: suspend (OutputStream) -> Unit,
) = backupMutex.withLock {
    val dirUri = DocumentsContract.buildDocumentUriUsingTree(
        treeUri, DocumentsContract.getTreeDocumentId(treeUri),
    )
    val name = backupFileName(System.currentTimeMillis())
    // Unique names avoid overwriting history. Validation below also handles
    // files left by a killed writer or a provider that refuses cleanup.
    val fileUri = DocumentsContract.createDocument(
        resolver, dirUri, "application/json", name,
    ) ?: error("could not create a backup in the chosen folder")
    try {
        resolver.openOutputStream(fileUri)?.use { export(it) }
            ?: error("could not open backup for writing")
        currentCoroutineContext().ensureActive()
        // Read back the provider's bytes, including the JSON tail, before rotation.
        check(isReadableBackup(resolver, fileUri)) { "backup could not be verified" }
    } catch (e: Exception) {
        // Best effort: even if deletion fails, unreadable files cannot evict history.
        runCatching { DocumentsContract.deleteDocument(resolver, fileUri) }
        throw e
    }

    // Legacy versions left partial files under final names. Don't let those
    // evict readable history either. Unreadable files are left untouched.
    listBackups(resolver, treeUri)
        .filter { (uri, _) -> isReadableBackup(resolver, uri) }
        .sortedByDescending { it.second }
        .drop(3)
        .forEach { (uri, _) ->
            currentCoroutineContext().ensureActive()
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
        }
}

private suspend fun isReadableBackup(resolver: ContentResolver, uri: Uri): Boolean = try {
    val context = currentCoroutineContext()
    resolver.openInputStream(uri)?.use { stream ->
        readBackupLinks(stream).forEach { context.ensureActive() }
        true
    } ?: false
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    false
}

/** (documentUri, displayName) for exact owned names; callers must validate content. */
private fun listBackups(resolver: ContentResolver, treeUri: Uri): List<Pair<Uri, String>> {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
        treeUri, DocumentsContract.getTreeDocumentId(treeUri),
    )
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
    )
    return resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val displayName = cursor.getString(1) ?: continue
                if (!isOwnedBackupName(displayName)) continue
                add(DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0)) to displayName)
            }
        }
    }.orEmpty()
}
