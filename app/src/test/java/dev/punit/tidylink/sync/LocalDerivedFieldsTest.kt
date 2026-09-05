package dev.punit.tidylink.sync

import dev.punit.tidylink.data.local.LinkEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalDerivedFieldsTest {

    @Test
    fun `incoming payload preserves android local relation fields`() {
        val local = LinkEntity(
            id = "1",
            url = "https://example.com/original",
            title = "Old",
            description = "",
            imageUrl = null,
            category = "Other",
            aiSummary = "",
            resolvedUrl = "https://example.com/final",
            relatedLinksJson = "[{\"url\":\"https://github.com/example/app\"}]",
            relatedLinksScannedAt = 42L,
        )
        val incoming = local.copy(title = "New", modifiedAt = local.modifiedAt + 1).toPayload()

        val merged = incoming.toEntity(local)

        assertEquals("New", merged.title)
        assertEquals(local.resolvedUrl, merged.resolvedUrl)
        assertEquals(local.relatedLinksJson, merged.relatedLinksJson)
        assertEquals(local.relatedLinksScannedAt, merged.relatedLinksScannedAt)
    }
}
