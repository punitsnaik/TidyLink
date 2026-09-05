package dev.punit.tidylink.ui.dashboard

import dev.punit.tidylink.data.settings.LibraryViewMode
import dev.punit.tidylink.data.settings.libraryViewModeFromPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveViewTest {
    @Test fun onlyPositiveLandscapeDimensionsUseLargeCards() {
        assertTrue(isLandscapeThumbnail(1200, 630))
        listOf(600 to 900, 500 to 500, 0 to 0, 100 to 0, -1 to -2, 0 to 100)
            .forEach { (width, height) -> assertFalse(isLandscapeThumbnail(width, height)) }
    }

    @Test fun thumbnailRatioPreservesWholeImageAtCardWidth() {
        assertEquals(2f, thumbnailAspectRatio(1000, 500), 0f)
        assertEquals(0.5f, thumbnailAspectRatio(500, 1000), 0f)
        listOf(0 to 0, 100 to 0, -1 to 20).forEach { (width, height) ->
            assertEquals(1f, thumbnailAspectRatio(width, height), 0f)
        }
    }

    @Test fun preferencesPreserveCompactAndMigrateVisualToAdaptive() {
        assertEquals(LibraryViewMode.COMPACT, libraryViewModeFromPreference("COMPACT"))
        listOf(null, "VISUAL", "ADAPTIVE", "unknown").forEach {
            assertEquals(LibraryViewMode.ADAPTIVE, libraryViewModeFromPreference(it))
        }
    }
}
