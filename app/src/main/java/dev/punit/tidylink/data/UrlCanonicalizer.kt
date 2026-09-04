package dev.punit.tidylink.data

import java.net.URI

/**
 * Pure URL helpers shared by the save pipeline, duplicate detection, and
 * bulk import parsing. Deliberately free of Android dependencies so every
 * function can be unit-tested on the JVM.
 */
object UrlCanonicalizer {

    /** Query parameters that only track shares/campaigns, never content. */
    private val TRACKING_PARAMS = setOf(
        "si", "feature", "fbclid", "gclid", "dclid", "msclkid", "twclid",
        "igsh", "igshid", "mc_cid", "mc_eid", "ref_src", "ref_url",
        "mibextid", "share_id", "sfnsn",
    )

    private val URL_REGEX = Regex("""https?://[^\s"'<>\])]+""")

    /**
     * Extracts every distinct http(s) URL from arbitrary text (e.g. a .txt
     * file of links), trimming trailing punctuation that regularly clings to
     * URLs in prose.
     */
    fun extractUrls(text: String): List<String> = URL_REGEX.findAll(text)
        .map { trimExtractedUrl(it.value) }
        .filter(::isValidHttpUrl)
        .distinct()
        .toList()

    /** First shared URL, including a valid bare domain and nothing else. */
    fun extractSharedUrl(text: String): String? = extractUrls(text).firstOrNull()
        ?: text.trim().takeIf(::isValidHttpUrl)

    private fun trimExtractedUrl(raw: String): String {
        val url = raw.trimEnd('.', ',', ';', ')', ']', '>', '"', '\'')
        return if (isValidHttpUrl(url)) url else url.trimEnd('!')
    }

    /** Parsed lowercase host, or an empty string for malformed input. */
    fun hostOf(url: String): String = runCatching {
        URI(cleanUrl(url)).host?.lowercase().orEmpty()
    }.getOrDefault("")

    /** Exact domain or one of its subdomains, never query/path text. */
    fun hostMatches(url: String, vararg domains: String): Boolean {
        val host = hostOf(url)
        return domains.any { domain -> host == domain || host.endsWith(".$domain") }
    }

    /**
     * Cleans a URL for storage: adds a scheme when missing, lowercases the
     * host, and strips known tracking query parameters (utm_*, si, fbclid…).
     * Plain #anchors are dropped, but SPA hash-routes (#/… or #!/…) are
     * preserved - for a hash-routed app the fragment IS the page. Falls back
     * to the raw string when unparseable.
     */
    fun cleanUrl(raw: String): String {
        val trimmed = raw.trim()
        val hasScheme = trimmed.lowercase().let {
            it.startsWith("http://") || it.startsWith("https://")
        }
        val withScheme = if (hasScheme) trimmed else "https://$trimmed"
        return try {
            val uri = URI(withScheme)
            val host = uri.host?.lowercase() ?: return withScheme
            val path = uri.rawPath.orEmpty().trimEnd('/')
            val query = uri.rawQuery
                ?.split('&')
                ?.filter { param ->
                    val key = param.substringBefore('=').lowercase()
                    key.isNotEmpty() && !key.startsWith("utm_") && key !in TRACKING_PARAMS
                }
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString("&")
            buildString {
                append(uri.scheme.lowercase()).append("://").append(host)
                if (uri.port != -1) append(':').append(uri.port)
                append(path)
                if (query != null) append('?').append(query)
                // dedupeKey builds on this output: keeping plain anchors would
                // split one page into many bookmarks, so only fragments that
                // route (SPA style) survive.
                val fragment = uri.rawFragment
                if (fragment != null &&
                    (fragment.startsWith("/") || fragment.startsWith("!/"))
                ) {
                    append('#').append(fragment)
                }
            }
        } catch (e: Exception) {
            withScheme
        }
    }

    /**
     * Key used to decide whether two URLs point at the same page: the cleaned
     * URL minus scheme and "www." - so http/https and www/no-www variants
     * (and tracking-param variants) all collide.
     */
    fun dedupeKey(url: String): String = cleanUrl(url)
        .removePrefix("https://")
        .removePrefix("http://")
        .removePrefix("www.")

    /** Human-readable placeholder title while a link's details are loading. */
    fun placeholderTitle(url: String): String = url
        .removePrefix("https://")
        .removePrefix("http://")
        .removePrefix("www.")
        .trimEnd('/')

    /**
     * Whether [raw] is plausibly a fetchable web URL after cleaning - used to
     * reject free text ("hello") before it becomes a permanently broken row.
     */
    fun isValidHttpUrl(raw: String): Boolean {
        if (raw.isBlank()) return false
        return try {
            val uri = URI(cleanUrl(raw))
            val host = uri.host ?: return false
            host.contains('.') && !host.startsWith('.') && !host.endsWith('.')
        } catch (e: Exception) {
            false
        }
    }
}
