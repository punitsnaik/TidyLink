package dev.punit.tidylink.ui.dashboard

import androidx.core.net.toUri

/**
 * Where a saved URL points, used to label the open CTA
 * ("Open YouTube", "Open Amazon", …) and pick a play icon. [isGeneric]
 * marks the unknown-site fallback so the UI can use a localized
 * "Open link" label instead (service names themselves are proper nouns
 * and stay untranslated).
 */
internal data class LinkSource(
    val name: String,
    val isPlayable: Boolean,
    val isGeneric: Boolean = false,
)

internal fun linkSourceOf(url: String): LinkSource {
    val u = url.lowercase()
    fun has(vararg parts: String) = parts.any { u.contains(it) }
    return when {
        has("music.youtube.com") -> LinkSource("YouTube Music", true)
        has("youtube.com", "youtu.be") -> LinkSource("YouTube", true)
        has("instagram.com") -> LinkSource("Instagram", has("/reel"))
        has("tiktok.com") -> LinkSource("TikTok", true)
        has("vimeo.com") -> LinkSource("Vimeo", true)
        has("open.spotify.com", "spotify.link") -> LinkSource("Spotify", true)
        has("twitter.com", "://x.com", "://www.x.com") -> LinkSource("X", false)
        has("amazon.", "amzn.") -> LinkSource("Amazon", false)
        has("flipkart.com", "fkrt.") -> LinkSource("Flipkart", false)
        has("reddit.com", "redd.it") -> LinkSource("Reddit", false)
        has("linkedin.com", "lnkd.in") -> LinkSource("LinkedIn", false)
        has("facebook.com", "fb.watch", "fb.com") -> LinkSource("Facebook", false)
        has("pinterest.", "pin.it") -> LinkSource("Pinterest", false)
        has("github.com") -> LinkSource("GitHub", false)
        has("play.google.com") -> LinkSource("Play Store", false)
        has("maps.google.", "goo.gl/maps", "maps.app.goo.gl") -> LinkSource("Maps", false)
        else -> LinkSource("", false, isGeneric = true)
    }
}

/** Bare domain ("autodraft.in") — favicon lookups and title fallbacks. */
internal fun domainOf(url: String): String =
    runCatching { url.toUri().host.orEmpty() }
        .getOrDefault("")
        .removePrefix("www.")
        .ifBlank { url.take(40) }

/** Favicon for links without a scraped thumbnail. */
internal fun faviconUrl(url: String): String =
    "https://www.google.com/s2/favicons?domain=${domainOf(url)}&sz=128"

/** Human-friendly title, guarding against blank / "undefined" scrapes. */
internal fun displayTitle(title: String, url: String): String {
    val t = title.trim()
    val junk = t.isBlank() ||
        t.equals("undefined", ignoreCase = true) ||
        t.equals("null", ignoreCase = true) ||
        t.startsWith("http://") || t.startsWith("https://")
    return if (junk) domainOf(url) else t
}
