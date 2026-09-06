package dev.punit.tidylink.ui.dashboard

import androidx.core.net.toUri
import dev.punit.tidylink.data.UrlCanonicalizer

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
    val host = UrlCanonicalizer.hostOf(url)
    fun domain(vararg names: String) = names.any { host == it || host.endsWith(".$it") }
    fun label(name: String) = host == name || host.startsWith("$name.") || host.contains(".$name.")
    return when {
        domain("music.youtube.com") -> LinkSource("YouTube Music", true)
        domain("youtube.com", "youtu.be") -> LinkSource("YouTube", true)
        domain("instagram.com") -> LinkSource("Instagram", u.contains("/reel"))
        domain("tiktok.com") -> LinkSource("TikTok", true)
        domain("vimeo.com") -> LinkSource("Vimeo", true)
        domain("open.spotify.com", "spotify.link") -> LinkSource("Spotify", true)
        domain("twitter.com", "x.com") -> LinkSource("X", false)
        label("amazon") || label("amzn") -> LinkSource("Amazon", false)
        domain("flipkart.com") || label("fkrt") -> LinkSource("Flipkart", false)
        domain("reddit.com", "redd.it") -> LinkSource("Reddit", false)
        domain("linkedin.com", "lnkd.in") -> LinkSource("LinkedIn", false)
        domain("facebook.com", "fb.watch", "fb.com") -> LinkSource("Facebook", false)
        label("pinterest") || domain("pin.it") -> LinkSource("Pinterest", false)
        domain("github.com") -> LinkSource("GitHub", false)
        domain("play.google.com") -> LinkSource("Play Store", false)
        label("google") && host.startsWith("maps.") ||
            domain("maps.app.goo.gl") || domain("goo.gl") && u.contains("/maps") -> LinkSource("Maps", false)
        else -> LinkSource("", false, isGeneric = true)
    }
}

/** Bare domain ("autodraft.in") - favicon lookups and title fallbacks. */
internal fun domainOf(url: String): String =
    runCatching { url.toUri().host.orEmpty() }
        .getOrDefault("")
        .removePrefix("www.")
        .ifBlank { url.take(40) }

/** Favicon for links without a scraped thumbnail. */
internal fun faviconUrl(url: String): String =
    "https://www.google.com/s2/favicons?domain=${domainOf(url)}&sz=128"

/** High-resolution brand logo via Clearbit Logo API. */
internal fun brandLogoUrl(url: String): String =
    "https://logo.clearbit.com/${domainOf(url)}"

/** Human-friendly title, guarding against blank / "undefined" scrapes. */
internal fun displayTitle(title: String, url: String): String {
    val t = title.trim()
    val junk = t.isBlank() ||
        t.equals("undefined", ignoreCase = true) ||
        t.equals("null", ignoreCase = true) ||
        t.startsWith("http://") || t.startsWith("https://")
    return if (junk) domainOf(url) else t
}
