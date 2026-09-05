package dev.punit.tidylink.data.scraper

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RelatedLinkExtractionTest {

    @Test
    fun `repository README beats GitHub global marketing`() {
        val doc = Jsoup.parse("""
            <div class="Header"><a href="https://github.com/features/copilot">Copilot</a></div>
            <a href="https://github.com/features/codespaces">Codespaces</a>
            <article class="markdown-body"><p>Run Strix with the <a href="https://docs.strix.example/">official documentation</a>.</p></article>
        """.trimIndent(), "https://github.com/usestrix/strix")
        assertEquals(listOf("https://docs.strix.example"), extractRelatedLinks(doc, doc.location()).map { it.url })
    }

    @Test
    fun `article references survive but platform promotions and recommendations do not`() {
        val doc = Jsoup.parse("""
            <a href="https://play.google.com/store/apps/details?id=com.medium.reader">Open in app</a>
            <main><article><p>Use the <a href="https://github.com/example/heretic">Heretic method</a>
            with <a href="https://docs.valyu.ai/plugin">Valyu LM Studio plugin</a>.</p>
            <aside><a href="https://medium.com/other-story">Recommended</a></aside></article>
            <a href="/sitemap">Sitemap</a></main>
        """.trimIndent(), "https://medium.com/article")
        assertEquals(setOf("https://github.com/example/heretic", "https://docs.valyu.ai/plugin"),
            extractRelatedLinks(doc, doc.location()).map { it.url }.toSet())
    }

    @Test
    fun `shopping and social platform chrome are not content`() {
        for (source in listOf("https://amazon.in/dp/123", "https://instagram.com/reel/123")) {
            val doc = Jsoup.parse("""
                <a href="https://amazon.in/gp/cart/view.html">0</a>
                <a href="https://amazon.in/sp?seller=123">Learn more about the seller</a>
                <a href="https://developers.facebook.com/docs/instagram">API</a>
                <a href="https://amazon.in/gp/site-directory">All categories</a>
            """.trimIndent(), source)
            assertEquals(emptyList<String>(), extractRelatedLinks(doc, source).map { it.url })
        }
    }

    @Test
    fun `stored descriptions repair old results and counts without refetching`() {
        val stale = """[{"url":"https://about.instagram.com/","title":"About","role":"Related"},{"url":"https://pll.harvard.edu/course/ml","title":"ML","role":"Related"}]"""
        val description = "https://pll.harvard.edu/course/ml?utm_source=threads https://pll.harvard.edu/course/cs50."
        assertEquals(listOf("https://pll.harvard.edu/course/ml", "https://pll.harvard.edu/course/cs50"),
            availableRelatedLinks(stale, description, "https://threads.com/post/123", "").map { it.url })
        assertEquals(2, availableRelatedLinks("invalid json", description, "https://threads.com/post/123", "").size)
    }

    @Test
    fun `post metadata URLs are discovered without navigation noise`() {
        val document = Jsoup.parse("""
            <meta property="og:description" content="Courses: https://pll.harvard.edu/course/data-science-machine-learning and https://pll.harvard.edu/course/cs50?delta=0">
            <footer><a href="https://about.meta.com/">Meta</a></footer>
            <a href="https://about.instagram.com/">About</a>
            <a href="https://help.instagram.com/">Help</a>
            <a href="https://meta.ai/">Meta AI</a>
            <a href="https://threads.com/">Threads</a>
        """.trimIndent(), "https://www.threads.com/@aipagedaily/post/123")
        assertEquals(listOf(
            "https://pll.harvard.edu/course/data-science-machine-learning",
            "https://pll.harvard.edu/course/cs50?delta=0",
        ), extractRelatedLinks(document, document.location()).map { it.url })
    }

    @Test
    fun `keeps same-site article links and excludes resolved source and navigation`() {
        val document = Jsoup.parse("""
            <nav><a href="https://other.example/docs">Docs navigation</a></nav>
            <article><a href="/course/ml">Machine learning</a>
            <p>Also https://courses.example/course/cs50.</p>
            <a href="/post">This post</a></article>
        """.trimIndent(), "https://courses.example/post")
        assertEquals(listOf("https://courses.example/course/ml", "https://courses.example/course/cs50"),
            extractRelatedLinks(document, "https://short.example/abc").map { it.url })
    }

    @Test
    fun `extracts useful links, removes noise and infers roles`() {
        val document = Jsoup.parse(
            """
            <article>
            <a href="/login">Log in</a>
            <a href="https://github.com/punitsnaik/TidyLink">Source</a>
            <a href="https://github.com/punitsnaik/TidyLink/releases">APK downloads</a>
            <a href="https://play.google.com/store/apps/details?id=dev.punit.tidylink">Play Store</a>
            <a href="https://f-droid.org/packages/dev.punit.tidylink/">F-Droid</a>
            <a href="https://example.com/privacy">Privacy</a>
            </article>
            """.trimIndent(),
            "https://www.reddit.com/r/test/comments/123/post/",
        )

        val links = extractRelatedLinks(document, document.location())

        assertEquals(
            listOf("Releases", "Play Store", "F-Droid", "Source code"),
            links.map { it.role },
        )
        assertFalse(links.any { it.url.contains("login") || it.url.contains("privacy") })
    }

    @Test
    fun `canonical duplicates collapse and results are capped`() {
        val anchors = buildString {
            append("<article>")
            append("<a href='https://github.com/punitsnaik/TidyLink?utm_source=x'>Repo</a>")
            append("<a href='http://www.github.com/punitsnaik/TidyLink'>Duplicate repo</a>")
            repeat(40) { append("<a href='https://downloads.example.com/app-$it.apk'>APK $it</a>") }
            append("</article>")
        }
        val document = Jsoup.parse(anchors, "https://example.com/post")

        val links = extractRelatedLinks(document, document.location())

        assertEquals(MAX_RELATED_CANDIDATES, links.size)
        assertEquals(links.size, links.map { it.dedupeKey }.distinct().size)
    }

    @Test
    fun `filters query actions without dropping content about login`() {
        val urls = listOf("https://example.com/?action=login", "https://example.com/?cart=true",
            "https://example.com/?action=%6cogin", "https://example.com/?q=login", "https://example.com/?id=42")
        val links = urls.map { RelatedLink(it, "Article", "Related") }
        assertEquals(listOf("https://example.com?q=login", "https://example.com?id=42"),
            filterRelatedLinks(links, "https://example.com/source", "").map { it.url })
    }
}
