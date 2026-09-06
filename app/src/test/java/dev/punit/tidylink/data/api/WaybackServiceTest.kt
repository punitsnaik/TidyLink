package dev.punit.tidylink.data.api

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WaybackServiceTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `deserializes wayback response when snapshot exists`() {
        val body = """
            {
                "url": "http://example.com",
                "archived_snapshots": {
                    "closest": {
                        "status": "200",
                        "available": true,
                        "url": "http://web.archive.org/web/20200101000000/http://example.com",
                        "timestamp": "20200101000000"
                    }
                }
            }
        """.trimIndent()

        val parsed = json.decodeFromString<WaybackResponse>(body)
        val closest = parsed.archived_snapshots.closest
        assertNotNull(closest)
        assertTrue(closest!!.available)
        assertEquals("http://web.archive.org/web/20200101000000/http://example.com", closest.url)
        assertEquals("20200101000000", closest.timestamp)
    }

    @Test
    fun `deserializes empty wayback response when no snapshot exists`() {
        val body = """
            {
                "url": "http://nonexistent-site-12345.org",
                "archived_snapshots": {}
            }
        """.trimIndent()

        val parsed = json.decodeFromString<WaybackResponse>(body)
        assertEquals(null, parsed.archived_snapshots.closest)
    }
}
