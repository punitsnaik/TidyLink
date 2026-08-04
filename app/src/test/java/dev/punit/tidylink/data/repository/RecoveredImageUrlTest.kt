package dev.punit.tidylink.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [recoveredImageUrl] is the guard that makes recovering a thumbnail from a
 * failed image load safe. The dangerous case is offline: losing
 * connectivity fires the load-failure callback for every visible card at
 * once, so anything other than "leave the row alone" would erase a
 * screenful of good URLs and force a re-scrape of each.
 */
class RecoveredImageUrlTest {

    @Test
    fun `a fresh url replaces a broken one`() {
        assertEquals(
            "https://example.com/new.jpg",
            recoveredImageUrl("https://example.com/expired.jpg", "https://example.com/new.jpg"),
        )
    }

    @Test
    fun `a scrape that found nothing leaves the row alone`() {
        assertNull(recoveredImageUrl("https://example.com/expired.jpg", null))
    }

    @Test
    fun `a blank scrape result leaves the row alone`() {
        assertNull(recoveredImageUrl("https://example.com/expired.jpg", ""))
    }

    @Test
    fun `the same url again is not a write`() {
        assertNull(
            recoveredImageUrl("https://example.com/same.jpg", "https://example.com/same.jpg"),
        )
    }

    @Test
    fun `a link that never had an image can still gain one`() {
        assertEquals("https://example.com/found.jpg", recoveredImageUrl(null, "https://example.com/found.jpg"))
    }

    @Test
    fun `no existing url and no scrape result is still no write`() {
        assertNull(recoveredImageUrl(null, null))
    }
}
