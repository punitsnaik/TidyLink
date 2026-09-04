package dev.punit.tidylink.data.repository

import dev.punit.tidylink.data.UrlCanonicalizer
import dev.punit.tidylink.data.local.LinkEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [mergeDuplicateGroup] decides which rows survive a merge and which get
 * deleted, so its rules are worth pinning down directly rather than only
 * observing them against a real library.
 */
class MergeDuplicateGroupTest {

    private fun link(
        id: String,
        url: String = "https://example.com/a",
        title: String = "T",
        description: String = "",
        imageUrl: String? = null,
        category: String = LinkRepository.FALLBACK_CATEGORY,
        aiSummary: String = "",
        timestamp: Long = 1_000L,
        pinned: Boolean = false,
        dedupeKey: String? = null,
        resolvedUrl: String = "",
        relatedLinksJson: String = "[]",
        note: String = "",
    ) = LinkEntity(
        id = id,
        url = url,
        title = title,
        description = description,
        imageUrl = imageUrl,
        category = category,
        aiSummary = aiSummary,
        timestamp = timestamp,
        dedupeKey = dedupeKey ?: UrlCanonicalizer.dedupeKey(resolvedUrl.ifBlank { url }),
        pinned = pinned,
        resolvedUrl = resolvedUrl,
        relatedLinksJson = relatedLinksJson,
        note = note,
    )

    @Test
    fun `a group with nothing to merge yields null`() {
        assertNull(mergeDuplicateGroup(emptyList()))
        assertNull(mergeDuplicateGroup(listOf(link("only"))))
    }

    @Test
    fun `a categorized row beats an uncategorized one`() {
        val merged = mergeDuplicateGroup(
            listOf(
                link("fallback"),
                link("real", category = "Dev"),
            )
        )!!
        assertEquals("real", merged.id)
        assertEquals("Dev", merged.category)
    }

    /**
     * The whole reason this is a merge and not "keep one, drop the rest":
     * the surviving row must not lose a field that only a deleted copy had.
     */
    @Test
    fun `gaps in the winner are filled from the rows being deleted`() {
        val merged = mergeDuplicateGroup(
            listOf(
                link("winner", category = "Dev"),
                link("hasImage", imageUrl = "https://img/1.png"),
                link("hasSummary", aiSummary = "a summary"),
                link("hasDescription", description = "a description"),
            )
        )!!
        assertEquals("winner", merged.id)
        assertEquals("https://img/1.png", merged.imageUrl)
        assertEquals("a summary", merged.aiSummary)
        assertEquals("a description", merged.description)
    }

    /** A pin is an explicit user action - it must survive on any copy. */
    @Test
    fun `pin on any copy survives onto the merged row`() {
        val merged = mergeDuplicateGroup(
            listOf(
                link("winner", category = "Dev", pinned = false),
                link("pinnedCopy", pinned = true),
            )
        )!!
        assertEquals("winner", merged.id)
        assertTrue("a pin on a deleted copy must not be lost", merged.pinned)
    }

    @Test
    fun `merged row keeps the earliest save time, not the winner's`() {
        val merged = mergeDuplicateGroup(
            listOf(
                link("winner", category = "Dev", timestamp = 5_000L),
                link("older", timestamp = 1_000L),
            )
        )!!
        assertEquals(1_000L, merged.timestamp)
    }

    @Test
    fun `a blank dedupe key is filled in so the merged row stays deduplicable`() {
        val merged = mergeDuplicateGroup(
            listOf(
                link("a", url = "https://example.com/page", dedupeKey = ""),
                link("b", url = "https://example.com/page", dedupeKey = ""),
            )
        )!!
        assertTrue("merged row must carry a dedupe key", merged.dedupeKey.isNotBlank())
    }

    @Test
    fun `richest wins over merely newer`() {
        val merged = mergeDuplicateGroup(
            listOf(
                link("rich", category = "Dev", imageUrl = "https://img/1.png", timestamp = 1_000L),
                link("newerButBare", timestamp = 9_000L),
            )
        )!!
        assertEquals("rich", merged.id)
    }

    /**
     * Every row that isn't the merged one gets deleted by the caller, so a
     * group of N must always collapse to exactly one surviving id.
     */
    @Test
    fun `merged row is always one of the input rows`() {
        val group = listOf(
            link("a", timestamp = 3_000L),
            link("b", category = "Dev", timestamp = 2_000L),
            link("c", imageUrl = "https://img/1.png", timestamp = 1_000L),
        )
        val merged = mergeDuplicateGroup(group)!!
        assertTrue(
            "merged id ${merged.id} is not in the group",
            group.any { it.id == merged.id },
        )
    }

    @Test
    fun `merges resolvedUrl and note and updates dedupeKey to canonical`() {
        val group = listOf(
            link("short", url = "https://reddit.com/r/sub/s/123", resolvedUrl = "https://reddit.com/r/sub/comments/abc", note = "my note"),
            link("full", url = "https://reddit.com/r/sub/comments/abc", resolvedUrl = "", category = "Tech"),
        )
        val merged = mergeDuplicateGroup(group)!!
        assertEquals("Tech", merged.category)
        assertEquals("my note", merged.note)
        assertEquals("https://reddit.com/r/sub/comments/abc", merged.resolvedUrl)
        assertEquals("reddit.com/r/sub/comments/abc", merged.dedupeKey)
    }

    @Test
    fun `findDuplicateGroups groups short link and target link by resolvedUrl`() {
        val short = link("1", url = "https://reddit.com/r/HowToMen/s/zGy6FijNnU", resolvedUrl = "https://reddit.com/r/HowToMen/comments/1w5tvxg/app/")
        val canonical = link("2", url = "https://reddit.com/r/HowToMen/comments/1w5tvxg/app/", resolvedUrl = "")
        val other = link("3", url = "https://example.com")

        val groups = findDuplicateGroups(listOf(short, canonical, other))
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].size)
        val groupIds = groups[0].map { it.id }.toSet()
        assertEquals(setOf("1", "2"), groupIds)
    }

    @Test
    fun `findDuplicateGroups groups multiple share links resolving to same post`() {
        val share1 = link("1", url = "https://reddit.com/r/HowToMen/s/token1", resolvedUrl = "https://reddit.com/r/HowToMen/comments/1w5tvxg/app/")
        val share2 = link("2", url = "https://reddit.com/r/HowToMen/s/token2", resolvedUrl = "https://reddit.com/r/HowToMen/comments/1w5tvxg/app/")

        val groups = findDuplicateGroups(listOf(share1, share2))
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].size)
    }
}
