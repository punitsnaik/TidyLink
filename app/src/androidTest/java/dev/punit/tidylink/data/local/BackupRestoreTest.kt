package dev.punit.tidylink.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.punit.tidylink.data.repository.readBackupLinks
import dev.punit.tidylink.data.repository.writeBackupLinks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream

class BackupRestoreTest {
    private lateinit var db: AppDatabase
    private val original = LinkEntity(id = "original", url = "https://example.com", title = "Keep me",
        description = "", imageUrl = null, category = "Research", aiSummary = "")

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
    }
    @After fun cleanup() = db.close()

    @Test fun large_restore_streams_all_rows_into_room() = runBlocking {
        val rows = (0 until 9000).map { original.copy(id = "$it", note = "n".repeat(1024)) }
        val out = ByteArrayOutputStream()
        writeBackupLinks(out, rows)
        assertTrue(out.size() > 8 * 1024 * 1024)
        assertEquals(rows.size, db.linkDao().importBackup(readBackupLinks(out.toByteArray().inputStream())))
        assertEquals(rows.last(), db.linkDao().getById("8999"))
    }

    @Test fun malformed_tail_rolls_back_insertions_and_overwrites() = runBlocking {
        db.linkDao().upsert(original)
        val out = ByteArrayOutputStream()
        writeBackupLinks(out, listOf(original.copy(title = "Do not keep")) +
            (0 until 150).map { original.copy(id = "$it") })
        val broken = out.toString("UTF-8").dropLast(1)
        try {
            db.linkDao().importBackup(readBackupLinks(broken.byteInputStream()))
            fail("Expected malformed JSON")
        } catch (_: kotlinx.serialization.SerializationException) { }
        assertEquals(listOf(original), db.linkDao().getAllOnce())
    }

    @Test fun cancellation_rolls_back_earlier_batches() = runBlocking {
        db.linkDao().upsert(original)
        try {
            db.linkDao().importBackup(sequence {
                repeat(150) { yield(original.copy(id = "$it")) }
                throw CancellationException("cancel restore")
            })
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(listOf(original), db.linkDao().getAllOnce())
    }
}
