package dev.punit.tidylink.data.importer

import org.jsoup.parser.Parser

/** One `<A HREF>` entry lifted out of a browser bookmarks export. */
data class ParsedBookmark(
    val url: String,
    val title: String,
    /** Deepest enclosing folder, or null at the top level. */
    val folder: String?,
    /** From ADD_DATE when present and plausible, else null. */
    val addedAtMillis: Long?,
)

/**
 * Parser for the Netscape bookmark file format - what every desktop
 * browser produces from "export bookmarks".
 *
 * Structure is read from the tag STREAM rather than from a parsed DOM, and
 * that is the whole design decision here. The format is not valid HTML and
 * never has been: `<DT>` and `<P>` are left unclosed, so the nesting a
 * lenient parser recovers is its own invention, not the file's. Chrome and
 * Firefox disagree on the details, and folder membership is exactly the
 * thing that recovery gets wrong. `<DL>` and `</DL>` are, however, always
 * balanced and explicit - so depth is unambiguous if you just count them.
 *
 * jsoup is still used, for the job it is actually reliable at: decoding
 * HTML entities in titles.
 */
object BookmarkHtmlParser {

    /**
     * Root containers every browser emits. Their names are an artifact of
     * the export, not organisation the user did, so they are not treated as
     * folders - a link sitting directly in the bookmarks bar is
     * uncategorized, and better classified by the AI than filed under
     * "Bookmarks bar".
     */
    private val ROOT_FOLDERS = setOf(
        "bookmarks", "bookmarks bar", "bookmarks toolbar", "bookmarks menu",
        "other bookmarks", "other favorites", "favorites", "favorites bar",
        "mobile bookmarks", "bookmark bar",
    )

    private val TOKEN = Regex(
        """<dl[^>]*>|</dl\s*>|<h3[^>]*>(.*?)</h3\s*>|<a\s([^>]*)>(.*?)</a\s*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val HREF = Regex("""href\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    private val ADD_DATE = Regex("""add_date\s*=\s*["']?(\d+)""", RegexOption.IGNORE_CASE)
    private val TAGS = Regex("<[^>]*>")

    /**
     * Plausibility window for ADD_DATE, in seconds: 1990-01-01 to
     * 2100-01-01. Some exports carry 0, and some carry microsecond or
     * WebKit-epoch values that would land a bookmark in the year 55000 and
     * pin it to the top of the library forever.
     */
    private val PLAUSIBLE_SECONDS = 631_152_000L..4_102_444_800L

    fun parse(html: String): List<ParsedBookmark> {
        val bookmarks = mutableListOf<ParsedBookmark>()
        // Names wait for the <DL> they label: `<H3>Work</H3><DL>...` means
        // everything until the matching </DL> belongs to Work.
        val folders = ArrayDeque<String>()
        var pendingFolder: String? = null

        for (match in TOKEN.findAll(html)) {
            val token = match.value
            when {
                token.startsWith("</", ignoreCase = true) -> {
                    folders.removeLastOrNull()
                }

                token.startsWith("<dl", ignoreCase = true) -> {
                    folders.addLast(pendingFolder.orEmpty())
                    pendingFolder = null
                }

                token.startsWith("<h3", ignoreCase = true) -> {
                    val name = clean(match.groupValues[1])
                    pendingFolder = if (name.lowercase() in ROOT_FOLDERS) "" else name
                }

                else -> {
                    val attrs = match.groupValues[2]
                    val url = HREF.find(attrs)?.groupValues?.get(1)?.let(::clean).orEmpty()
                    if (!isImportable(url)) continue
                    val title = clean(match.groupValues[3])
                    bookmarks += ParsedBookmark(
                        url = url,
                        // May be blank - browsers do export untitled
                        // bookmarks, and the caller substitutes a
                        // URL-derived placeholder.
                        title = title,
                        // Deepest NAMED folder: unnamed levels (the file
                        // root, and the browser's own containers) are
                        // transparent rather than blocking.
                        folder = folders.lastOrNull { it.isNotBlank() },
                        addedAtMillis = ADD_DATE.find(attrs)
                            ?.groupValues?.get(1)
                            ?.toLongOrNull()
                            ?.takeIf { it in PLAUSIBLE_SECONDS }
                            ?.times(1000),
                    )
                }
            }
        }
        return bookmarks
    }

    /**
     * Exports are full of entries that aren't web pages: `javascript:`
     * bookmarklets, Firefox `place:` smart folders, `chrome://` internals,
     * `data:` blobs. Saving those would put rows in the library that can
     * never be scraped and never usefully opened.
     */
    private fun isImportable(url: String): Boolean {
        val lower = url.lowercase()
        return (lower.startsWith("http://") || lower.startsWith("https://")) &&
            url.length > "https://".length
    }

    /**
     * Strips any inline markup, decodes entities, collapses whitespace.
     *
     * The non-breaking-space step is not cosmetic: `&nbsp;` is common in
     * exported titles and decodes to U+00A0, which `\\s+` does NOT match,
     * so without this it survives into the saved title and into the search
     * index. Written as an escape rather than a literal because a literal
     * U+00A0 in source is invisible to the next reader.
     */
    private fun clean(raw: String): String =
        Parser.unescapeEntities(TAGS.replace(raw, ""), false)
            .replace('\u00A0', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
}
