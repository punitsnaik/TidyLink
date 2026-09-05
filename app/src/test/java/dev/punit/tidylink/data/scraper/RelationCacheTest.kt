package dev.punit.tidylink.data.scraper

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RelationCacheTest {
    private val link = RelatedLink("https://example.com/manual", "Manual", "Documentation", "Product manual", true)
    private val page = ScrapedData("https://product.test", "Product", "Description", null, relatedLinks = listOf(link), fetched = true)

    @Test fun failureFallsBackOnceAndExplicitRefreshRetries() = runBlocking {
        var calls = 0
        val select: suspend (ScrapedData) -> List<RelatedLink>? = { calls++; null }
        val first = resolveRelationCache("[]", page, true, select = select)
        assertEquals(listOf(link.url), decodeRelationCache(first)!!.links.map { it.url })
        assertEquals(first, resolveRelationCache(first, page, true, select = select))
        assertEquals(1, calls)
        resolveRelationCache(first, page, true, force = true, select = select)
        assertEquals(2, calls)
    }

    @Test fun addingProviderKeepsContentVerifiedFallbackWhenSelectionIsEmpty() = runBlocking {
        var calls = 0
        val select: suspend (ScrapedData) -> List<RelatedLink>? = { calls++; emptyList() }
        val fallback = resolveRelationCache("[]", page, false, select = select)
        assertEquals(0, calls)
        val ai = resolveRelationCache(fallback, page, true, select = select)
        assertEquals(1, calls)
        assertEquals(listOf(link.url), decodeRelationCache(ai)!!.links.map { it.url })
        assertEquals(ai, resolveRelationCache(ai, page, true, select = select))
        assertEquals(1, calls)
    }

    @Test fun failedFetchPreservesVerifiedLinksAndChangedContentReevaluates() = runBlocking {
        var calls = 0
        val select: suspend (ScrapedData) -> List<RelatedLink>? = { calls++; it.relatedLinks }
        val first = resolveRelationCache("[]", page, true, select = select)
        val offline = resolveRelationCache(first, page.copy(fetched = false, relatedLinks = emptyList()), true, select = select)
        assertEquals(decodeRelationCache(first), decodeRelationCache(offline))
        assertEquals(1, calls)
        resolveRelationCache(first, page.copy(description = "New description"), true, select = select)
        assertEquals(2, calls)
    }
}
