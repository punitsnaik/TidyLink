package dev.punit.tidylink.data.work

import android.content.ContentResolver
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SdkSuppress
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID

@SdkSuppress(minSdkVersion = 29)
class BackupStorageTest {
    private lateinit var provider: TestDocuments
    private lateinit var resolver: ContentResolver
    private val tree = DocumentsContract.buildTreeDocumentUri("tidylink.backup.test", "root")

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        provider = TestDocuments(File(context.cacheDir, "backup-test-${UUID.randomUUID()}").apply { mkdirs() })
        provider.attachInfo(context, ProviderInfo().apply {
            authority = "tidylink.backup.test"
            exported = true
            grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"
            writePermission = "android.permission.MANAGE_DOCUMENTS"
        })
        resolver = ContentResolver.wrap(provider)
        (1..3).forEach { provider.seed("tidylink-backup-2026-01-0$it.json", "[]") }
    }

    @After fun cleanup() { provider.directory.deleteRecursively() }

    @Test fun failed_writes_never_become_rotation_candidates_even_if_cleanup_fails() = runBlocking {
        provider.failDelete = true
        repeat(2) {
            try {
                writeBackupDocument(resolver, tree) { it.write("[".toByteArray()); throw IOException("disk full") }
                fail("Expected failed write")
            } catch (_: IOException) { }
        }
        (1..3).forEach { assertTrue(provider.names().contains("tidylink-backup-2026-01-0$it.json")) }
        provider.failDelete = false
        writeBackupDocument(resolver, tree) { it.write("[]".toByteArray()) }
        assertFalse(provider.names().contains("tidylink-backup-2026-01-01.json"))
        assertTrue(provider.names().contains("tidylink-backup-2026-01-02.json"))
        assertTrue(provider.names().contains("tidylink-backup-2026-01-03.json"))
    }

    @Test fun invalid_legacy_backups_do_not_evict_good_history() = runBlocking {
        provider.seed("tidylink-backup-2099-01-01.json", "[")
        provider.seed("tidylink-backup-2099-01-02.json", "")
        writeBackupDocument(resolver, tree) { it.write("[]".toByteArray()) }
        assertTrue(provider.names().contains("tidylink-backup-2026-01-03.json"))
        assertTrue(provider.names().contains("tidylink-backup-2026-01-02.json"))
    }

    @Test fun corrupt_output_does_not_rotate_good_backups() = runBlocking {
        try {
            writeBackupDocument(resolver, tree) { it.write("[".toByteArray()) }
            fail("Expected failed verification")
        } catch (_: Exception) { }
        assertEquals(3, provider.names().count(::isOwnedBackupName))
    }

    @Test fun one_off_and_periodic_writers_cannot_overlap() = runBlocking {
        val releaseFirst = CompletableDeferred<Unit>()
        var secondStarted = false
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            writeBackupDocument(resolver, tree) {
                releaseFirst.await()
                it.write("[]".toByteArray())
            }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            writeBackupDocument(resolver, tree) {
                secondStarted = true
                it.write("[]".toByteArray())
            }
        }
        try {
            assertFalse(secondStarted)
        } finally {
            releaseFirst.complete(Unit)
        }
        first.await()
        second.await()
        assertTrue(secondStarted)
        assertEquals(3, provider.names().count(::isOwnedBackupName))
    }

    /** Real file descriptors behind the Android SAF boundary; failures are injected only here. */
    private class TestDocuments(val directory: File) : DocumentsProvider() {
        private val names = linkedMapOf<String, String>()
        var failDelete = false
        fun names() = names.values.toList()
        fun seed(name: String, text: String) {
            val id = createDocument("root", "application/json", name)
            File(directory, id).writeText(text)
        }
        override fun onCreate() = true
        override fun isChildDocument(parentDocumentId: String?, documentId: String?) = true
        override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
            val id = UUID.randomUUID().toString()
            names[id] = displayName
            File(directory, id).createNewFile()
            return id
        }
        override fun deleteDocument(documentId: String) {
            if (failDelete) throw java.io.FileNotFoundException("delete unavailable")
            names.remove(documentId)
            File(directory, documentId).delete()
        }
        override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
            ParcelFileDescriptor.open(File(directory, documentId), ParcelFileDescriptor.parseMode(mode))
        override fun queryRoots(projection: Array<out String>?) = MatrixCursor(arrayOf("root_id"))
        override fun queryDocument(documentId: String, projection: Array<out String>?) = cursor(listOf(documentId), projection)
        override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?) =
            cursor(names.keys.toList(), projection)
        private fun cursor(ids: List<String>, projection: Array<out String>?): MatrixCursor {
            val columns = projection ?: arrayOf("document_id", "_display_name")
            return MatrixCursor(columns).apply {
                ids.forEach { id -> addRow(columns.map { column -> when (column) {
                    "document_id" -> id
                    "_display_name" -> names[id]
                    "mime_type" -> "application/json"
                    "flags" -> 0
                    else -> null
                } }) }
            }
        }
    }
}
