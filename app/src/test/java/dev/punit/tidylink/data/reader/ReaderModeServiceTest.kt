package dev.punit.tidylink.data.reader

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderModeServiceTest {

    private val service = ReaderModeService()

    @Test
    fun `parseDocument extracts clean article and removes boilerplate`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Test Article Title - NewsSite</title>
                <meta property="og:title" content="Clean Article Title" />
                <meta name="author" content="Alice Smith" />
            </head>
            <body>
                <header><nav><a href="/">Home</a><a href="/news">News</a></nav></header>
                <div class="ad">Sponsored advertisement</div>
                <article>
                    <h1>Clean Article Title</h1>
                    <p>This is the first paragraph of an insightful article about mobile software architecture and link indexing.</p>
                    <p>This is the second paragraph that elaborates on the topic in detail, explaining offline resilience and database caching.</p>
                </article>
                <div class="sidebar">Sidebar content and ads</div>
                <footer>Copyright 2026 NewsSite</footer>
            </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://newssite.com/article/1")
        val article = service.parseDocument(doc, "https://newssite.com/article/1")

        assertEquals("Clean Article Title", article.title)
        assertEquals("Alice Smith", article.byline)
        assertEquals("newssite.com", article.domain)
        assertTrue(article.textContent.contains("first paragraph"))
        assertTrue(article.textContent.contains("second paragraph"))
        assertFalse(article.textContent.contains("Sponsored advertisement"))
        assertFalse(article.textContent.contains("Sidebar content"))
        assertFalse(article.textContent.contains("Copyright 2026"))
        assertTrue(article.readingTimeMinutes >= 1)
    }
}
