package dev.punit.tidylink.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CategoryNamesTest {

    @Test
    fun `case, punctuation, and plural variants share one key`() {
        val expected = CategoryNames.key("AI Tools")
        assertEquals(expected, CategoryNames.key("ai-tool"))
        assertEquals(expected, CategoryNames.key("Ai tools"))
        assertEquals(expected, CategoryNames.key("AI_TOOL"))
    }

    @Test
    fun `distinct categories keep distinct keys`() {
        assertNotEquals(CategoryNames.key("Recipes"), CategoryNames.key("Tech News"))
    }

    @Test
    fun `title case capitalizes each word`() {
        assertEquals("Tech News", CategoryNames.titleCase("tech news"))
        assertEquals("Recipes", CategoryNames.titleCase("recipes"))
    }

    @Test
    fun `title case leaves already-capitalized words alone`() {
        assertEquals("AI Tools", CategoryNames.titleCase("AI Tools"))
    }
}
