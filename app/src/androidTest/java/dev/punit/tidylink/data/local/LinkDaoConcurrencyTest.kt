package dev.punit.tidylink.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LinkDaoConcurrencyTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: LinkDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.linkDao()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun enrichment_only_replaces_an_existing_unchanged_row() = runBlocking {
        val original = LinkEntity(
            id = "link",
            url = "https://example.com",
            title = "Original",
            description = "",
            imageUrl = null,
            category = "Other",
            aiSummary = "",
            timestamp = 1L,
        )
        dao.upsert(original)

        val enriched = original.copy(title = "Scraped", timestamp = 2L)
        assertTrue(dao.replaceIfUnchanged(original, enriched))
        assertEquals("Scraped", dao.getById(original.id)?.title)

        val userEdited = enriched.copy(title = "User title", pinned = true, timestamp = 3L)
        dao.upsert(userEdited)
        assertFalse(dao.replaceIfUnchanged(enriched, enriched.copy(title = "Stale")))
        assertEquals(userEdited, dao.getById(original.id))

        dao.delete(original.id)
        assertFalse(dao.replaceIfUnchanged(userEdited, userEdited.copy(title = "Resurrected")))
        assertNull(dao.getById(original.id))
    }
}
