package dev.punit.tidylink.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CategoryPaletteTest {
    @Test
    fun `visible categories get stable distinct hues despite hash collisions`() {
        val categories = listOf("AI & Tech", "Entertainment", "Web Tools", "Technology")
        val first = categoryHueMap(categories)
        val reordered = categoryHueMap(categories.reversed())

        assertEquals(first, reordered)
        assertEquals(categories.size, first.values.toSet().size)
        assertNotEquals(first.getValue("AI & Tech"), first.getValue("Technology"))
    }
}
