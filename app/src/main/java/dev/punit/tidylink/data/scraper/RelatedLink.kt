package dev.punit.tidylink.data.scraper

import dev.punit.tidylink.data.UrlCanonicalizer
import kotlinx.serialization.Serializable
import org.jsoup.nodes.Document
import java.net.URI

@Serializable
data class RelatedLink(
    val url: String,
    val title: String,
    val role: String,
    val context: String = "",
    val contentEvidence: Boolean = false,
) {
    val dedupeKey: String get() = UrlCanonicalizer.dedupeKey(url)
}

private val NOISE = Regex(
    "(?:^|[/#?&_.-])(login|signin|signup|register|privacy|terms|cookie|advert|account|logout|sitemap|cart|site-directory)(?:$|[/#?&_.=-])",
    RegexOption.IGNORE_CASE,
)

internal fun extractRelatedLinks(document: Document, sourceUrl: String): List<RelatedLink> {
    // Work on a copy: image/title extraction still needs the original document.
    val content = document.clone()
    content.select("nav, footer, header, aside, [role=navigation], [role=contentinfo], script, style, " +
        ".recommendations, [data-testid=recommendations], .related-posts, .sponsored, #nav-belt, #nav-main").remove()
    val bodies = content.select("article, [itemprop=articleBody], .markdown-body, [data-testid=post-text], .usertext-body, " +
        "#productDescription, #feature-bullets, #technicalSpecifications_section_1")
    val roots = bodies.ifEmpty { content.select("main, [role=main]") }
    val anchors = roots.flatMap { root ->
        root.select("a[href]").map {
            RelatedLink(it.absUrl("href"), it.text(), "Related", it.parent()?.text().orEmpty().take(300), bodies.isNotEmpty())
        } + textLinks(root.text(), bodies.isNotEmpty())
    }
    val text = content.select("meta[property=og:description], meta[name=description], meta[name=twitter:description]")
        .joinToString("\n") { it.attr("content") }
    return filterRelatedLinks(anchors + textLinks(text), sourceUrl, document.location())
}

/** Old arrays lack provenance: only explicit description URLs are safe until background re-scan. */
internal fun availableRelatedLinks(json: String, description: String, sourceUrl: String, resolvedUrl: String): List<RelatedLink> {
    val cache = decodeRelationCache(json)
    // Empty cached AI selections must stay empty; don't add rejected description URLs back.
    return filterRelatedLinks(cache?.links ?: textLinks(description), sourceUrl, resolvedUrl).take(MAX_USEFUL_LINKS)
}

internal fun conservativeRelatedLinks(data: ScrapedData): List<RelatedLink> = filterRelatedLinks(
    data.relatedLinks.filter { it.contentEvidence } + textLinks(data.description), data.url, data.resolvedUrl,
).take(MAX_USEFUL_LINKS)

private fun textLinks(text: String, evidence: Boolean = true): List<RelatedLink> = UrlCanonicalizer.extractUrls(text).map {
    RelatedLink(it, it.substringAfter("://"), "Related", text.take(300), evidence)
}

internal fun filterRelatedLinks(candidates: List<RelatedLink>, sourceUrl: String, resolvedUrl: String): List<RelatedLink> {
    val excluded = setOf(sourceUrl, resolvedUrl).filter { it.isNotBlank() }.map(UrlCanonicalizer::dedupeKey)
    return candidates.asSequence().mapNotNull { candidate ->
        val url = candidate.url.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) return@mapNotNull null
        if (!UrlCanonicalizer.isValidHttpUrl(url)) return@mapNotNull null
        val uri = runCatching { URI(url) }.getOrNull() ?: return@mapNotNull null
        if (NOISE.containsMatchIn(uri.rawPath.orEmpty())) return@mapNotNull null
        if (UrlCanonicalizer.dedupeKey(url) in excluded) return@mapNotNull null
        val title = candidate.title.trim().ifBlank { url.substringAfter("://") }.take(120)
        candidate.copy(url = UrlCanonicalizer.cleanUrl(url), title = title, role = inferRole(url, title))
    }
        .distinctBy { it.dedupeKey }
        .sortedByDescending(::relationScore)
        .take(MAX_RELATED_CANDIDATES)
        .toList()
}

private fun inferRole(url: String, title: String): String {
    val value = "$url $title".lowercase()
    return when {
        UrlCanonicalizer.hostMatches(url, "play.google.com") && "/store/apps" in url -> "Play Store"
        UrlCanonicalizer.hostMatches(url, "f-droid.org") -> "F-Droid"
        "/releases" in value -> "Releases"
        value.endsWith(".apk") || " download" in value || "apk " in value -> "Download"
        "/issues" in value || "support" in value -> "Issues"
        "docs." in value || "/docs" in value || "documentation" in value -> "Documentation"
        UrlCanonicalizer.hostMatches(url, "github.com", "gitlab.com") || "source code" in value -> "Source code"
        "official" in value || "website" in value || "homepage" in value -> "Website"
        else -> "Related"
    }
}

private fun relationScore(link: RelatedLink): Int = when (link.role) {
    "Download", "Releases" -> 90
    "Play Store", "F-Droid" -> 80
    "Source code" -> 70
    "Issues", "Documentation" -> 60
    "Website" -> 50
    else -> 10
}
