package dev.punit.tidylink.data.scraper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import java.net.URLEncoder

data class ScrapedData(
    val url: String,
    val title: String,
    val description: String,
    val imageUrl: String?,
) {
    /** True when we got real metadata, not just a bare title / login wall. */
    val isRich: Boolean
        get() = imageUrl != null || description.isNotBlank()
}

class LinkScraperService {

    /**
     * Fetches [url] and extracts Open Graph metadata, falling back to standard
     * <title> / <meta name="description"> tags.
     *
     * Some sites (Instagram, Facebook, X/Twitter…) show a login wall to normal
     * browsers-without-cookies but still serve OG tags to social link-preview
     * crawlers — so if the first attempt comes back thin, we retry with the
     * facebookexternalhit user agent.
     *
     * Never throws — on total failure it returns a minimal [ScrapedData] built
     * from the URL itself, so the save pipeline can still proceed.
     */
    suspend fun scrapeMetadata(url: String): ScrapedData = withContext(Dispatchers.IO) {
        val first = fetch(url, BROWSER_UA)
        val best = if (first != null && first.isRich) {
            first
        } else {
            val second = fetch(url, CRAWLER_UA)
            when {
                second != null && second.isRich -> second
                first != null -> first
                second != null -> second
                else -> ScrapedData(
                    url = url,
                    title = url.removePrefix("https://").removePrefix("http://").trimEnd('/'),
                    description = "",
                    imageUrl = null,
                )
            }
        }

        // YouTube / YouTube Music often serve no OG tags (or a junk title
        // like "undefined") to non-browser clients; the official oEmbed
        // endpoint reliably returns the real title + thumbnail.
        if (isYouTubeUrl(url) && (!best.isRich || isJunkTitle(best.title))) {
            youtubeOembed(url)?.let { oembed ->
                return@withContext best.copy(
                    title = if (isJunkTitle(best.title)) oembed.title else best.title,
                    imageUrl = best.imageUrl ?: oembed.imageUrl,
                )
            }
        }
        best
    }

    private fun isYouTubeUrl(url: String): Boolean =
        listOf("youtube.com", "youtu.be").any { url.contains(it, ignoreCase = true) }

    /** Blank, "undefined", or URL-shaped — i.e. not a real page title. */
    private fun isJunkTitle(title: String): Boolean {
        val t = title.trim()
        return t.isBlank() ||
            t.equals("undefined", ignoreCase = true) ||
            t.equals("null", ignoreCase = true) ||
            t.startsWith("http://") || t.startsWith("https://") ||
            !t.contains(' ') && t.contains('/') // bare host/path fallback titles
    }

    /**
     * Title + thumbnail via YouTube's oEmbed API. music.youtube.com links
     * are rewritten to www.youtube.com, which oEmbed accepts.
     */
    private fun youtubeOembed(url: String): ScrapedData? = try {
        val normalized = url.replace("music.youtube.com", "www.youtube.com", ignoreCase = true)
        val endpoint = "https://www.youtube.com/oembed?format=json&url=" +
            URLEncoder.encode(normalized, "UTF-8")
        val body = Jsoup.connect(endpoint)
            .ignoreContentType(true)
            .userAgent(BROWSER_UA)
            .timeout(TIMEOUT_MS)
            .execute()
            .body()
        val obj = oembedJson.parseToJsonElement(body).jsonObject
        val title = obj["title"]?.jsonPrimitive?.content?.trim()
        val thumbnail = obj["thumbnail_url"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        if (title.isNullOrBlank()) {
            null
        } else {
            ScrapedData(url = url, title = title, description = "", imageUrl = thumbnail)
        }
    } catch (e: Exception) {
        null // 4xx (private/deleted video), network, malformed JSON…
    }

    private val oembedJson = Json { ignoreUnknownKeys = true }

    private fun fetch(url: String, userAgent: String): ScrapedData? = try {
        val document = Jsoup.connect(url)
            .userAgent(userAgent)
            .timeout(TIMEOUT_MS)
            .followRedirects(true)
            .get()

        fun meta(vararg selectors: String): String? =
            selectors.firstNotNullOfOrNull { selector ->
                document.selectFirst(selector)?.attr("content")?.takeIf { it.isNotBlank() }
            }

        val title = meta("meta[property=og:title]", "meta[name=twitter:title]")
            ?: document.title().takeIf { it.isNotBlank() }
            ?: url

        val description = meta(
            "meta[property=og:description]",
            "meta[name=description]",
            "meta[name=twitter:description]",
        ).orEmpty()

        // absUrl resolves relative image paths against the document's base URI.
        val imageUrl = listOf("meta[property=og:image]", "meta[name=twitter:image]")
            .firstNotNullOfOrNull { selector ->
                document.selectFirst(selector)
                    ?.let { el -> el.absUrl("content").ifBlank { el.attr("content") } }
                    ?.takeIf { it.isNotBlank() }
            }

        ScrapedData(
            url = url,
            title = title.trim(),
            description = description.trim(),
            imageUrl = imageUrl,
        )
    } catch (e: Exception) {
        // Network error, non-HTML content, HTTP 4xx/5xx, malformed markup, etc.
        null
    }

    private companion object {
        const val TIMEOUT_MS = 10_000
        const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

        /** Social preview crawler UA — many walled sites still serve OG tags to it. */
        const val CRAWLER_UA = "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)"
    }
}
