package dev.punit.tidylink.data.reader

import dev.punit.tidylink.data.UrlCanonicalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

data class ReaderArticle(
    val title: String,
    val byline: String?,
    val domain: String,
    val textContent: String,
    val readingTimeMinutes: Int,
    val wordCount: Int,
)

class ReaderModeService {

    suspend fun extractArticle(url: String): ReaderArticle? = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")
                .timeout(12_000)
                .followRedirects(true)
                .get()

            parseDocument(doc, url)
        } catch (e: Exception) {
            null
        }
    }

    internal fun parseDocument(doc: Document, url: String): ReaderArticle {
        val domain = UrlCanonicalizer.hostOf(url).removePrefix("www.")

        val title = doc.selectFirst("meta[property=og:title], meta[name=twitter:title]")
            ?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.title().ifBlank { domain }

        val byline = doc.selectFirst("meta[name=author], meta[property=article:author], [rel=author]")
            ?.let { it.attr("content").ifBlank { it.text() } }
            ?.takeIf { it.isNotBlank() }

        // Remove boilerplate, ads, scripts, nav, footer
        doc.select(
            "script, style, noscript, iframe, svg, form, nav, header, footer, aside, " +
                "[role=navigation], [role=banner], [role=contentinfo], [role=search], " +
                ".ad, .ads, .advertisement, .sidebar, #sidebar, .social-share, .comments"
        ).remove()

        // Locate main article container
        val candidateSelectors = listOf(
            "article",
            "main",
            "[itemprop=articleBody]",
            ".article-body",
            ".post-content",
            ".entry-content",
            ".story-body",
            "#article-body",
            "#content",
        )

        var container: Element? = null
        for (selector in candidateSelectors) {
            val found = doc.selectFirst(selector)
            if (found != null && found.text().length > 200) {
                container = found
                break
            }
        }

        val target = container ?: doc.body()

        // Extract paragraphs and headings
        val blocks = target.select("h1, h2, h3, h4, p, li, blockquote")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && it.length > 20 }

        val content = if (blocks.isNotEmpty()) {
            blocks.joinToString("\n\n")
        } else {
            target.text().trim()
        }

        val words = content.split(Regex("\\s+")).count { it.isNotBlank() }
        val readingMinutes = (words / 200).coerceAtLeast(1)

        return ReaderArticle(
            title = title.trim(),
            byline = byline?.trim(),
            domain = domain,
            textContent = content,
            readingTimeMinutes = readingMinutes,
            wordCount = words,
        )
    }
}
