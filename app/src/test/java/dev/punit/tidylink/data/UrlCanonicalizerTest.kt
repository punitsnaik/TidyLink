package dev.punit.tidylink.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlCanonicalizerTest {

    // --- cleanUrl -----------------------------------------------------------

    @Test
    fun `adds https scheme when missing`() {
        assertEquals("https://example.com/page", UrlCanonicalizer.cleanUrl("example.com/page"))
    }

    @Test
    fun `keeps existing scheme, lowercases scheme and host`() {
        assertEquals(
            "https://example.com/Path",
            UrlCanonicalizer.cleanUrl("HTTPS://EXAMPLE.com/Path"),
        )
    }

    @Test
    fun `strips utm and known tracking params, keeps content params`() {
        assertEquals(
            "https://example.com/page?id=5",
            UrlCanonicalizer.cleanUrl("https://example.com/page?utm_source=x&id=5&fbclid=abc"),
        )
    }

    @Test
    fun `strips youtube si token`() {
        assertEquals(
            "https://youtu.be/dQw4w9WgXcQ",
            UrlCanonicalizer.cleanUrl("https://youtu.be/dQw4w9WgXcQ?si=AbCdEf"),
        )
    }

    @Test
    fun `preserves fragment`() {
        assertEquals(
            "https://example.com/page#section-2",
            UrlCanonicalizer.cleanUrl("https://example.com/page#section-2"),
        )
    }

    @Test
    fun `drops query entirely when only tracking params remain`() {
        assertEquals(
            "https://example.com/page",
            UrlCanonicalizer.cleanUrl("https://example.com/page?utm_source=x&utm_medium=y"),
        )
    }

    @Test
    fun `preserves explicit port`() {
        assertEquals(
            "https://example.com:8443/api",
            UrlCanonicalizer.cleanUrl("https://example.com:8443/api"),
        )
    }

    @Test
    fun `trims trailing slash from path`() {
        assertEquals("https://example.com/a", UrlCanonicalizer.cleanUrl("https://example.com/a/"))
    }

    @Test
    fun `unparseable input falls back to scheme-prefixed raw string`() {
        assertEquals(
            "https://not a url",
            UrlCanonicalizer.cleanUrl("not a url"),
        )
    }

    // --- dedupeKey ----------------------------------------------------------

    @Test
    fun `scheme, www, and tracking variants collide on the same key`() {
        val a = UrlCanonicalizer.dedupeKey("http://www.example.com/a/")
        val b = UrlCanonicalizer.dedupeKey("https://example.com/a")
        val c = UrlCanonicalizer.dedupeKey("https://example.com/a?utm_campaign=x")
        assertEquals("example.com/a", a)
        assertEquals(a, b)
        assertEquals(b, c)
    }

    @Test
    fun `different pages produce different keys`() {
        assertTrue(
            UrlCanonicalizer.dedupeKey("https://example.com/a") !=
                UrlCanonicalizer.dedupeKey("https://example.com/b")
        )
    }

    // --- extractUrls ----------------------------------------------------------

    @Test
    fun `extracts urls and trims trailing punctuation`() {
        val text = "Check https://a.com/x, then (https://b.com/y). Done."
        assertEquals(
            listOf("https://a.com/x", "https://b.com/y"),
            UrlCanonicalizer.extractUrls(text),
        )
    }

    @Test
    fun `deduplicates repeated urls`() {
        val text = "https://a.com/x\nhttps://a.com/x"
        assertEquals(listOf("https://a.com/x"), UrlCanonicalizer.extractUrls(text))
    }

    @Test
    fun `returns empty list when no urls present`() {
        assertTrue(UrlCanonicalizer.extractUrls("no links here").isEmpty())
    }

    // --- placeholderTitle -----------------------------------------------------

    @Test
    fun `placeholder title strips scheme, www, and trailing slash`() {
        assertEquals(
            "example.com/page",
            UrlCanonicalizer.placeholderTitle("https://www.example.com/page/"),
        )
    }

    // --- isValidHttpUrl -------------------------------------------------------

    @Test
    fun `accepts normal urls, with and without scheme`() {
        assertTrue(UrlCanonicalizer.isValidHttpUrl("https://example.com/page"))
        assertTrue(UrlCanonicalizer.isValidHttpUrl("example.com"))
    }

    @Test
    fun `rejects free text, blank, and dotless hosts`() {
        assertFalse(UrlCanonicalizer.isValidHttpUrl("hello"))
        assertFalse(UrlCanonicalizer.isValidHttpUrl(""))
        assertFalse(UrlCanonicalizer.isValidHttpUrl("   "))
        assertFalse(UrlCanonicalizer.isValidHttpUrl("not a url at all"))
    }
}
