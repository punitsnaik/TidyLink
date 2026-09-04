package dev.punit.tidylink.data.scraper

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RedditScraperTest {

    @Test
    fun `extracts related links from old reddit post body`() {
        val postUrl = "https://old.reddit.com/r/HowToMen/comments/1izwoc8/tidylink_an_opensource_url_cleaner_and_link/"
        val html = """
            <html>
            <head><title>TidyLink: an open-source URL cleaner and link organizer</title></head>
            <body>
              <div class="usertext-body">
                <div class="md">
                  <p>Hey everyone, check out the project on GitHub: <a href="https://github.com/punitsnaik28/TidyLink">https://github.com/punitsnaik28/TidyLink</a> and let me know your thoughts!</p>
                </div>
              </div>
            </body>
            </html>
        """.trimIndent()
        val doc = Jsoup.parse(html, postUrl)
        val links = extractRelatedLinks(doc, postUrl)
        assertEquals(1, links.size)
        assertEquals("https://github.com/punitsnaik28/TidyLink", links.first().url)
    }

    @Test
    fun `excludes source url and post url from sub-links`() {
        val postUrl = "https://old.reddit.com/r/HowToMen/comments/1izwoc8/post/"
        val html = """
            <html>
            <body>
              <div class="usertext-body">
                <p>Check <a href="$postUrl">self</a> and <a href="https://example.com/useful">https://example.com/useful</a></p>
              </div>
            </body>
            </html>
        """.trimIndent()
        val doc = Jsoup.parse(html, postUrl)
        val links = extractRelatedLinks(doc, postUrl)
        assertEquals(listOf("https://example.com/useful"), links.map { it.url })
    }
}

