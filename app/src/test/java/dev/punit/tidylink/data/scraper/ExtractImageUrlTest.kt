package dev.punit.tidylink.data.scraper

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [extractImageUrl] never touches the network - jsoup parses a plain
 * String - so the fallback order (PRD-thumbnail-recovery.md, "Broaden the
 * scraper's image sources") is fully coverable without a live fetch.
 */
class ExtractImageUrlTest {

    private fun doc(html: String, baseUri: String = "https://example.com/page") =
        Jsoup.parse(html, baseUri)

    @Test
    fun `og-image wins when present alongside every other source`() {
        val html = """
            <html><head>
            <meta property="og:image" content="https://cdn.example.com/og.jpg">
            <meta name="twitter:image" content="https://cdn.example.com/twitter.jpg">
            <meta name="twitter:image:src" content="https://cdn.example.com/twitter-src.jpg">
            <meta itemprop="image" content="https://cdn.example.com/itemprop.jpg">
            <meta name="msapplication-TileImage" content="https://cdn.example.com/tile.jpg">
            <link rel="image_src" href="https://cdn.example.com/image-src.jpg">
            </head></html>
        """.trimIndent()
        assertEquals("https://cdn.example.com/og.jpg", extractImageUrl(doc(html)))
    }

    @Test
    fun `twitter-image is used when og-image is absent`() {
        val html = """
            <meta name="twitter:image" content="https://cdn.example.com/twitter.jpg">
            <meta name="twitter:image:src" content="https://cdn.example.com/twitter-src.jpg">
        """.trimIndent()
        assertEquals("https://cdn.example.com/twitter.jpg", extractImageUrl(doc(html)))
    }

    @Test
    fun `twitter-image-src is used when og-image and twitter-image are absent`() {
        val html = """
            <meta name="twitter:image:src" content="https://cdn.example.com/twitter-src.jpg">
            <meta itemprop="image" content="https://cdn.example.com/itemprop.jpg">
        """.trimIndent()
        assertEquals("https://cdn.example.com/twitter-src.jpg", extractImageUrl(doc(html)))
    }

    @Test
    fun `itemprop-image is used when every source above it is absent`() {
        val html = """
            <meta itemprop="image" content="https://cdn.example.com/itemprop.jpg">
            <meta name="msapplication-TileImage" content="https://cdn.example.com/tile.jpg">
        """.trimIndent()
        assertEquals("https://cdn.example.com/itemprop.jpg", extractImageUrl(doc(html)))
    }

    @Test
    fun `msapplication-TileImage is used when every source above it is absent`() {
        val html = """
            <meta name="msapplication-TileImage" content="https://cdn.example.com/tile.jpg">
            <link rel="image_src" href="https://cdn.example.com/image-src.jpg">
        """.trimIndent()
        assertEquals("https://cdn.example.com/tile.jpg", extractImageUrl(doc(html)))
    }

    @Test
    fun `link rel=image_src is the last resort and reads href, not content`() {
        // A stray content attribute on the link element must be ignored -
        // proves the selector is paired with the right attribute, not
        // defaulting to "content" for every source.
        val html = """
            <link rel="image_src" href="https://cdn.example.com/image-src.jpg" content="https://cdn.example.com/wrong.jpg">
        """.trimIndent()
        assertEquals("https://cdn.example.com/image-src.jpg", extractImageUrl(doc(html)))
    }

    @Test
    fun `relative paths resolve against the document base URI`() {
        val html = """<meta property="og:image" content="/img/og.jpg">"""
        assertEquals("https://example.com/img/og.jpg", extractImageUrl(doc(html)))
    }

    @Test
    fun `a document with none of the six sources returns null`() {
        val html = """
            <html><head><title>No image here</title></head><body></body></html>
        """.trimIndent()
        assertNull(extractImageUrl(doc(html)))
    }

    @Test
    fun `a blank content attribute is skipped rather than returned as empty`() {
        val html = """
            <meta property="og:image" content="">
            <meta name="twitter:image" content="https://cdn.example.com/twitter.jpg">
        """.trimIndent()
        assertEquals("https://cdn.example.com/twitter.jpg", extractImageUrl(doc(html)))
    }

    @Test
    fun `blank content on every source returns null, not an empty string`() {
        val html = """<meta property="og:image" content="">"""
        assertNull(extractImageUrl(doc(html)))
    }
}
