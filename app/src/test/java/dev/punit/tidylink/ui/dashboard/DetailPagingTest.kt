package dev.punit.tidylink.ui.dashboard

import androidx.paging.ItemSnapshotList
import dev.punit.tidylink.data.local.LinkEntity
import org.junit.Assert.*
import org.junit.Test

class DetailPagingTest {
    @Test fun detail_navigation_uses_absolute_paging_indices() {
        val rows = (240 until 420).map { LinkEntity(id = "$it", url = "https://example.com/$it",
            title = "$it", description = "", imageUrl = null, category = "Reading", aiSummary = "") }
        val snapshot = ItemSnapshotList(240, 580, rows)
        val index = detailLinkIndex(snapshot, "250")
        assertEquals(250, index)
        assertEquals("251", snapshot[detailNeighborIndex(index, 1, snapshot.size)!!]?.id)
        assertEquals(-1, detailLinkIndex(snapshot, "missing"))
    }
}
