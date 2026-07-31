package dev.punit.tidylink.data.local

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trash stores a whole [LinkEntity] as JSON rather than as mirrored
 * columns. That buys "trash never needs its own migration" - but only if
 * the round trip actually holds, so it is worth asserting rather than
 * assuming.
 *
 * Mirrors LinkRepository's Json configuration. If that config ever changes,
 * this test is the thing that should fail.
 */
class TrashSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val link = LinkEntity(
        id = "abc",
        url = "https://example.com/a",
        title = "A title",
        description = "A description",
        imageUrl = "https://example.com/img.png",
        category = "Dev",
        tags = listOf("kotlin", "android"),
        aiSummary = "A summary",
        timestamp = 1_620_000_000_000L,
        dedupeKey = "example.com/a",
        pinned = true,
        scrapeAttempts = 2,
        isRead = true,
        note = "why I saved this",
    )

    @Test
    fun `a trashed link survives the round trip with every field intact`() {
        val restored = json.decodeFromString<LinkEntity>(json.encodeToString(link))
        assertEquals(link, restored)
    }

    /**
     * The actual claim being made: a row trashed by an older build, before
     * some column existed, must still restore. Otherwise every future
     * column silently makes existing trash unrecoverable - which is the
     * failure this design exists to avoid.
     */
    @Test
    fun `a row trashed before isRead and note existed still restores`() {
        val old = """
            {
              "id": "old",
              "url": "https://example.com/old",
              "title": "Old",
              "description": "",
              "imageUrl": null,
              "category": "Uncategorized",
              "tags": [],
              "aiSummary": "",
              "timestamp": 100
            }
        """.trimIndent()

        val restored = json.decodeFromString<LinkEntity>(old)

        assertEquals("old", restored.id)
        assertFalse("isRead must decode at its default", restored.isRead)
        assertEquals("note must decode at its default", "", restored.note)
        assertEquals("", restored.dedupeKey)
        assertFalse(restored.pinned)
        assertEquals(0, restored.scrapeAttempts)
    }

    /**
     * The other direction: a row trashed by a NEWER build must not break an
     * older one. ignoreUnknownKeys is what makes a downgrade survivable.
     */
    @Test
    fun `unknown future fields are ignored rather than throwing`() {
        val future = """
            {
              "id": "future",
              "url": "https://example.com/f",
              "title": "F",
              "description": "",
              "imageUrl": null,
              "category": "Dev",
              "tags": [],
              "aiSummary": "",
              "timestamp": 100,
              "somethingAddedLater": "surprise"
            }
        """.trimIndent()

        assertEquals("future", json.decodeFromString<LinkEntity>(future).id)
    }

    @Test
    fun `tags with awkward characters survive the round trip`() {
        val awkward = link.copy(
            tags = listOf("c++", "a,b", "quote\"inside", "50%"),
            note = "line one\nline two \"quoted\"",
        )
        val restored = json.decodeFromString<LinkEntity>(json.encodeToString(awkward))
        assertEquals(awkward.tags, restored.tags)
        assertEquals(awkward.note, restored.note)
    }

    @Test
    fun `a corrupt row fails as null rather than taking the restore down`() {
        // Mirrors the repository's runCatching-per-row: one unreadable
        // entry must not make the rest of the trash unrecoverable.
        val decoded = runCatching {
            json.decodeFromString<LinkEntity>("{ not json at all")
        }.getOrNull()
        assertTrue(decoded == null)
    }
}
