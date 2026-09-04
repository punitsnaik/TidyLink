package dev.punit.tidylink.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.punit.tidylink.data.scraper.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RelationBackfillTest {
    @Test fun legacyRowsAreBatchedAndNoAiRowsGetOnePassAfterProviderAdded() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            val dao = database.linkDao()
            val base = LinkEntity(url = "https://example.com", title = "Title", description = "", imageUrl = "image", category = "Test", aiSummary = "Summary", scrapeAttempts = 1, relatedLinksScannedAt = 1L)
            dao.upsertAll(listOf(
                base.copy(id = "legacy", relatedLinksJson = "[]", timestamp = 3L),
                base.copy(id = "fallback", relatedLinksJson = encodeRelationCache(RelationCache()), timestamp = 2L),
                base.copy(id = "attempted", relatedLinksJson = encodeRelationCache(RelationCache(aiAttempted = true)), timestamp = 1L),
            ))
            assertEquals(1, dao.countRelationCandidates(CURRENT_RELATION_CACHE_PREFIX, NO_AI_RELATION_CACHE_PREFIX, false))
            assertEquals(2, dao.countRelationCandidates(CURRENT_RELATION_CACHE_PREFIX, NO_AI_RELATION_CACHE_PREFIX, true))
            assertEquals(listOf("legacy"), dao.getRelationCandidates(CURRENT_RELATION_CACHE_PREFIX, NO_AI_RELATION_CACHE_PREFIX, true, 1).map { it.id })
            val pending = dao.getRelationCandidates(CURRENT_RELATION_CACHE_PREFIX, NO_AI_RELATION_CACHE_PREFIX, true, 24)
            pending.forEach { dao.replaceIfUnchanged(it, it.copy(relatedLinksJson = encodeRelationCache(RelationCache(aiAttempted = true)))) }
            assertEquals(0, dao.countRelationCandidates(CURRENT_RELATION_CACHE_PREFIX, NO_AI_RELATION_CACHE_PREFIX, true))
        } finally {
            database.close()
        }
    }
}
