package dev.punit.tidylink.data.ai

import dev.punit.tidylink.data.scraper.*
import org.junit.Assert.*
import org.junit.Test

class RelatedLinkSelectionTest {
    private val links = listOf(
        RelatedLink("https://example.com/docs", "Docs", "Documentation"),
        RelatedLink("https://example.com/download", "Download", "Download"),
    )

    @Test fun selectsOnlySuppliedIndicesAndDeduplicates() {
        assertEquals(listOf(links[1], links[0]), parseRelatedLinkSelection("```json\n[1,1,0]\n```", links))
        assertEquals(emptyList<RelatedLink>(), parseRelatedLinkSelection("[]", links))
        for (raw in listOf("[2]", "[-1]", "[0.5]", "[\"0\"]", "[true]", "[\"https://evil.test\"]", "not json")) {
            assertNull(raw, parseRelatedLinkSelection(raw, links))
        }
    }

    @Test fun cachedEmptyAiDecisionDoesNotRestoreRejectedDescriptionLinks() {
        val cache = encodeRelationCache(RelationCache(aiAttempted = true, links = emptyList()))
        assertEquals(emptyList<RelatedLink>(), availableRelatedLinks(cache, "https://example.com/docs", "https://source.test/post", ""))
        assertTrue(cache.startsWith(CURRENT_RELATION_CACHE_PREFIX.removeSuffix("%")))
        assertFalse(cache.startsWith(NO_AI_RELATION_CACHE_PREFIX.removeSuffix("%")))
        assertTrue(encodeRelationCache(RelationCache()).startsWith(NO_AI_RELATION_CACHE_PREFIX.removeSuffix("%")))
    }

    @Test fun legacyNoiseIsHiddenBeforeBackfillAndFingerprintsTrackInputs() {
        assertEquals(emptyList<RelatedLink>(), availableRelatedLinks("""[{"url":"https://github.com/features/copilot","title":"Copilot","role":"Source code"}]""", "", "https://github.com/project/repo", ""))
        val data = ScrapedData("https://source.test", "Title", "Description", null, relatedLinks = links)
        assertEquals(relationFingerprint(data), relationFingerprint(data.copy()))
        assertNotEquals(relationFingerprint(data), relationFingerprint(data.copy(relatedLinks = links.reversed())))
    }
}
