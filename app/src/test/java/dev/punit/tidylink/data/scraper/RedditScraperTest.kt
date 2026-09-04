package dev.punit.tidylink.data.scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedditScraperTest {

    private val scraper = LinkScraperService()

    @Test
    fun `parses reddit json with selftext and links`() {
        val json = """
        [
          {
            "kind": "Listing",
            "data": {
              "children": [
                {
                  "kind": "t3",
                  "data": {
                    "title": "TidyLink: an open-source URL cleaner and link organizer",
                    "selftext": "Hey everyone, check out the project on GitHub: https://github.com/punitsnaik/TidyLink and let me know your thoughts!",
                    "selftext_html": "&lt;!-- SC_OFF --&gt;&lt;div class=\"md\"&gt;&lt;p&gt;Hey everyone, check out the project on GitHub: &lt;a href=\"https://github.com/punitsnaik/TidyLink\"&gt;https://github.com/punitsnaik/TidyLink&lt;/a&gt; and let me know your thoughts!&lt;/p&gt;&lt;/div&gt;&lt;!-- SC_ON --&gt;",
                    "url": "https://www.reddit.com/r/HowToMen/comments/1izwoc8/tidylink_an_opensource_url_cleaner_and_link/",
                    "is_self": true,
                    "preview": {
                      "images": [
                        {
                          "source": {
                            "url": "https://preview.redd.it/test.png?width=640&amp;crop=smart&amp;auto=webp"
                          }
                        }
                      ]
                    }
                  }
                }
              ]
            }
          }
        ]
        """.trimIndent()

        val sourceUrl = "https://www.reddit.com/r/HowToMen/s/zGy6FijNnU"
        val cleanTarget = "https://www.reddit.com/r/HowToMen/comments/1izwoc8/tidylink_an_opensource_url_cleaner_and_link"
        val fallback = ScrapedData(
            url = sourceUrl,
            title = "Reddit fallback",
            description = "Short desc",
            imageUrl = null,
            resolvedUrl = cleanTarget,
            relatedLinks = emptyList(),
            fetched = true,
        )

        val result = scraper.parseRedditJson(json, sourceUrl, cleanTarget, fallback)
        assertNotNull(result)
        assertEquals("TidyLink: an open-source URL cleaner and link organizer", result!!.title)
        assertTrue(result.description.startsWith("Hey everyone, check out the project on GitHub"))
        assertEquals("https://preview.redd.it/test.png?width=640&crop=smart&auto=webp", result.imageUrl)
        assertEquals(cleanTarget, result.resolvedUrl)

        // Sub-links should contain the GitHub link and infer "Source code"
        assertEquals(1, result.relatedLinks.size)
        val subLink = result.relatedLinks.first()
        assertEquals("https://github.com/punitsnaik/TidyLink", subLink.url)
        assertEquals("Source code", subLink.role)
    }

    @Test
    fun `excludes source url and post url from sub-links`() {
        val json = """
        [
          {
            "kind": "Listing",
            "data": {
              "children": [
                {
                  "kind": "t3",
                  "data": {
                    "title": "Post with self-reference",
                    "selftext": "Check https://www.reddit.com/r/HowToMen/s/zGy6FijNnU and https://example.com/useful",
                    "selftext_html": "",
                    "url": "https://www.reddit.com/r/HowToMen/comments/1izwoc8/post/",
                    "is_self": true
                  }
                }
              ]
            }
          }
        ]
        """.trimIndent()

        val sourceUrl = "https://www.reddit.com/r/HowToMen/s/zGy6FijNnU"
        val cleanTarget = "https://www.reddit.com/r/HowToMen/comments/1izwoc8/post"
        val fallback = ScrapedData(sourceUrl, "Title", "", null, cleanTarget, emptyList(), true)

        val result = scraper.parseRedditJson(json, sourceUrl, cleanTarget, fallback)
        assertNotNull(result)
        assertEquals(listOf("https://example.com/useful"), result!!.relatedLinks.map { it.url })
    }
}
