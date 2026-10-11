package dev.punit.tidylink.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkSourceTest {

    @Test
    fun `service detection uses the host instead of query text`() {
        assertTrue(linkSourceOf("https://example.com/?next=youtube.com/watch").isGeneric)
        assertEquals("YouTube", linkSourceOf("https://m.youtube.com/watch?v=1").name)
    }

    @Test
    fun `regional services and map short links use host boundaries`() {
        assertEquals("Amazon", linkSourceOf("https://www.amazon.in/item").name)
        assertEquals("Pinterest", linkSourceOf("https://www.pinterest.co.uk/pin/1").name)
        assertEquals("Maps", linkSourceOf("https://maps.google.co.in/place/example").name)
        assertEquals("Maps", linkSourceOf("https://goo.gl/maps/example").name)
        assertTrue(linkSourceOf("https://example.com/?next=amazon.in").isGeneric)
    }

    @Test
    fun `site name prefers known services then the main domain label`() {
        assertEquals("YouTube", siteNameOf("https://m.youtube.com/watch?v=1"))
        assertEquals("Medium", siteNameOf("https://blog.medium.com/post"))
        assertEquals("Autodraft", siteNameOf("https://www.autodraft.in/"))
        assertEquals("Bbc", siteNameOf("https://www.bbc.co.uk/news"))
    }
}
