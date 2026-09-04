package dev.punit.tidylink.data.scraper

import dev.punit.tidylink.data.UrlCanonicalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

data class ScrapedData(
    val url: String,
    val title: String,
    val description: String,
    val imageUrl: String?,
    val resolvedUrl: String = "",
    val relatedLinks: List<RelatedLink> = emptyList(),
    val fetched: Boolean = false,
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
     * crawlers - so if the first attempt comes back thin, we retry with the
     * facebookexternalhit user agent.
     *
     * Never throws - on total failure it returns a minimal [ScrapedData] built
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
        UrlCanonicalizer.hostMatches(url, "youtube.com", "youtu.be")

    /** Blank, "undefined", or URL-shaped - i.e. not a real page title. */
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

        val imageUrl = extractImageUrl(document)

        ScrapedData(
            url = url,
            title = title.trim(),
            description = description.trim(),
            imageUrl = imageUrl,
            resolvedUrl = document.location().takeUnless { it == url }.orEmpty(),
            relatedLinks = extractRelatedLinks(document, url),
            fetched = true,
        )
    } catch (e: Exception) {
        // Network error, non-HTML content, HTTP 4xx/5xx, malformed markup, etc.
        null
    }

    private companion object {
        const val TIMEOUT_MS = 10_000
        const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

        /** Social preview crawler UA - many walled sites still serve OG tags to it. */
        const val CRAWLER_UA = "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)"
    }
}

/**
 * Where a thumbnail might be published, in fallback order. og:image wins
 * when present; the rest are secondary tags real sites use when they skip
 * Open Graph entirely. `link[rel=image_src]` is the only one whose URL
 * lives in `href` rather than `content`, hence pairing a selector with its
 * attribute instead of assuming `content` everywhere.
 *
 * Deliberately NOT extended with the page's first `<img>` - see
 * PRD-thumbnail-recovery.md: that heuristic picks up spacers, tracking
 * pixels, avatars and ad slots, and a wrong thumbnail is worse than none.
 */
private val IMAGE_SOURCES: List<Pair<String, String>> = listOf(
    "meta[property=og:image]" to "content",
    "meta[name=twitter:image]" to "content",
    "meta[name=twitter:image:src]" to "content",
    "meta[itemprop=image]" to "content",
    "meta[name=msapplication-TileImage]" to "content",
    "link[rel=image_src]" to "href",
)

/**
 * Extracted out of [LinkScraperService]'s private `fetch` so it's reachable
 * from a JVM unit test (jsoup parses a String with no network) without
 * dragging network I/O into the test.
 *
 * `absUrl` resolves a relative path against the document's base URI; the
 * `.ifBlank { attr(...) }` fallback covers documents with no base URI,
 * where `absUrl` itself returns blank.
 *
 * The raw-attribute check has to come FIRST, and that is not a style
 * preference. `absUrl` resolves an EMPTY value against the base URI the
 * same way it resolves a relative one, so `content=""` on a page at
 * https://example.com comes back as "https://example.com" - a non-blank
 * string that passes every later emptiness check and gets stored as the
 * thumbnail. The card then tries to load a web page as an image and shows
 * an empty box. Guarding on absUrl's output alone cannot catch this,
 * because by then the base URI has already been substituted in.
 */
internal fun extractImageUrl(document: Document): String? =
    IMAGE_SOURCES.firstNotNullOfOrNull { (selector, attribute) ->
        document.selectFirst(selector)
            ?.takeIf { el -> el.attr(attribute).isNotBlank() }
            ?.let { el -> el.absUrl(attribute).ifBlank { el.attr(attribute) } }
            ?.takeIf { it.isNotBlank() }
    }
