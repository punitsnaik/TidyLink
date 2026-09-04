package dev.punit.tidylink.data.repository

import dev.punit.tidylink.data.local.LinkEntity
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class BackupRoundTripTest {
    private val link = LinkEntity(url = "https://example.com", title = "Title",
        description = "", imageUrl = null, category = "Research", aiSummary = "")

    @Test fun large_export_can_be_read_back() {
        val links = (0 until 9000).map { link.copy(id = "$it", note = "n".repeat(1024)) }
        val out = ByteArrayOutputStream()
        writeBackupLinks(out, links)
        assertTrue(out.size() > 8 * 1024 * 1024)
        assertEquals(links, readBackupLinks(out.toByteArray().inputStream()).toList())
    }

    @Test fun missing_output_is_a_failure() {
        assertThrows(Exception::class.java) { writeBackupLinks(null, listOf(link)) }
    }

    @Test fun malformed_tail_is_not_silently_accepted() {
        val out = ByteArrayOutputStream()
        writeBackupLinks(out, listOf(link))
        for (payload in listOf(out.toString("UTF-8").dropLast(1), out.toString("UTF-8") + "garbage")) {
            assertThrows(Exception::class.java) { readBackupLinks(payload.byteInputStream()).toList() }
        }
    }

    @Test fun background_ai_preserves_an_existing_category() {
        assertEquals("My Research", backgroundCategory("My Research", "Technology"))
        assertEquals("Technology", backgroundCategory(LinkRepository.FALLBACK_CATEGORY, "Technology"))
        assertEquals("My Research", backgroundCategory("My Research", ""))
    }
}
