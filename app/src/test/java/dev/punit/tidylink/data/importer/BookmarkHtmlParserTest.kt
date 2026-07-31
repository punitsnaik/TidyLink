package dev.punit.tidylink.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Samples below are shaped like real `export bookmarks` output, including
 * the parts that make the format awkward: unclosed `<DT>` and `<P>`,
 * Firefox's `<DD>` folder descriptions, entity-escaped titles, and the
 * non-page entries (`javascript:`, `place:`, `chrome://`) both browsers
 * happily export.
 */
class BookmarkHtmlParserTest {

    private val chrome = """
        <!DOCTYPE NETSCAPE-Bookmark-file-1>
        <META HTTP-EQUIV="Content-Type" CONTENT="text/html; charset=UTF-8">
        <TITLE>Bookmarks</TITLE>
        <H1>Bookmarks</H1>
        <DL><p>
            <DT><H3 ADD_DATE="1600000000" PERSONAL_TOOLBAR_FOLDER="true">Bookmarks bar</H3>
            <DL><p>
                <DT><A HREF="https://kotlinlang.org/" ADD_DATE="1610000000">Kotlin &amp; Friends</A>
                <DT><H3 ADD_DATE="1600000001">Work</H3>
                <DL><p>
                    <DT><A HREF="https://example.com/a" ADD_DATE="1620000000">Example A</A>
                    <DT><H3>Reading</H3>
                    <DL><p>
                        <DT><A HREF="https://example.com/deep">Deep&nbsp;One</A>
                    </DL><p>
                    <DT><A HREF="https://example.com/b">Example B</A>
                </DL><p>
                <DT><A HREF="javascript:void(0)">A bookmarklet</A>
                <DT><A HREF="chrome://bookmarks/">Internals</A>
            </DL><p>
            <DT><H3>Other bookmarks</H3>
            <DL><p>
                <DT><A HREF="https://example.org/loose" ADD_DATE="0">Loose</A>
            </DL><p>
        </DL><p>
    """.trimIndent()

    private val firefox = """
        <!DOCTYPE NETSCAPE-Bookmark-file-1>
        <TITLE>Bookmarks</TITLE>
        <H1>Bookmarks Menu</H1>
        <DL><p>
            <DT><A HREF="place:type=6&amp;sort=14" ADD_DATE="1600000000">Recent Tags</A>
            <DT><H3 ADD_DATE="1600000000" PERSONAL_TOOLBAR_FOLDER="true">Bookmarks Toolbar</H3>
            <DD>Add bookmarks to this folder to see them in the toolbar
            <DL><p>
                <DT><H3>Dev</H3>
                <DL><p>
                    <DT><A HREF="https://developer.mozilla.org/" ADD_DATE="1630000000">MDN</A>
                </DL><p>
            </DL><p>
        </DL><p>
    """.trimIndent()

    @Test
    fun `chrome export yields every real page and nothing else`() {
        assertEquals(
            listOf(
                "https://kotlinlang.org/",
                "https://example.com/a",
                "https://example.com/deep",
                "https://example.com/b",
                "https://example.org/loose",
            ),
            BookmarkHtmlParser.parse(chrome).map { it.url },
        )
    }

    /**
     * The reason folder depth is counted from the DL stream rather than
     * recovered from a parsed DOM: a link after a nested `</DL>` belongs to
     * the OUTER folder again, and getting that wrong silently files links
     * under the wrong category for the whole import.
     */
    @Test
    fun `folder depth follows DL nesting, including after a nested folder closes`() {
        val byUrl = BookmarkHtmlParser.parse(chrome).associateBy { it.url }
        assertEquals("Work", byUrl.getValue("https://example.com/a").folder)
        assertEquals("Reading", byUrl.getValue("https://example.com/deep").folder)
        assertEquals(
            "back to the outer folder after </DL>",
            "Work",
            byUrl.getValue("https://example.com/b").folder,
        )
    }

    @Test
    fun `browser root containers are not treated as folders`() {
        val byUrl = BookmarkHtmlParser.parse(chrome).associateBy { it.url }
        assertNull(
            "'Bookmarks bar' is an export artifact, not a category",
            byUrl.getValue("https://kotlinlang.org/").folder,
        )
        assertNull(
            "'Other bookmarks' is an export artifact, not a category",
            byUrl.getValue("https://example.org/loose").folder,
        )
    }

    @Test
    fun `entities and non-breaking spaces are decoded out of titles`() {
        val byUrl = BookmarkHtmlParser.parse(chrome).associateBy { it.url }
        assertEquals("Kotlin & Friends", byUrl.getValue("https://kotlinlang.org/").title)
        assertEquals("Deep One", byUrl.getValue("https://example.com/deep").title)
    }

    @Test
    fun `add_date becomes millis when plausible and is dropped when not`() {
        val byUrl = BookmarkHtmlParser.parse(chrome).associateBy { it.url }
        assertEquals(1_610_000_000_000L, byUrl.getValue("https://kotlinlang.org/").addedAtMillis)
        assertNull(
            "ADD_DATE=0 would date the link to 1970 and sink it to the bottom forever",
            byUrl.getValue("https://example.org/loose").addedAtMillis,
        )
    }

    @Test
    fun `firefox export skips place URLs and reads through the DD description`() {
        val parsed = BookmarkHtmlParser.parse(firefox)
        assertEquals(listOf("https://developer.mozilla.org/"), parsed.map { it.url })
        assertEquals("Dev", parsed.single().folder)
    }

    @Test
    fun `non-page schemes are skipped`() {
        val urls = BookmarkHtmlParser.parse(chrome).map { it.url }
        assertTrue(urls.none { it.startsWith("javascript:") })
        assertTrue(urls.none { it.startsWith("chrome://") })
    }

    @Test
    fun `garbage input yields nothing instead of throwing`() {
        assertEquals(emptyList<ParsedBookmark>(), BookmarkHtmlParser.parse(""))
        assertEquals(emptyList<ParsedBookmark>(), BookmarkHtmlParser.parse("not html at all"))
        assertEquals(
            emptyList<ParsedBookmark>(),
            BookmarkHtmlParser.parse("<html><body><p>a real web page</p></body></html>"),
        )
    }

    /** Unbalanced markup must not throw or mis-stack; extra closes are ignored. */
    @Test
    fun `unbalanced DL tags do not break the folder stack`() {
        val parsed = BookmarkHtmlParser.parse(
            """
            </DL><p>
            <DL><p>
                <DT><H3>Solo</H3>
                <DL><p>
                    <DT><A HREF="https://example.com/x">X</A>
            """.trimIndent()
        )
        assertEquals(listOf("https://example.com/x"), parsed.map { it.url })
        assertEquals("Solo", parsed.single().folder)
    }

    @Test
    fun `a bookmark with no title parses with a blank title rather than being dropped`() {
        val parsed = BookmarkHtmlParser.parse(
            """<DL><p><DT><A HREF="https://example.com/untitled"></A></DL><p>"""
        )
        assertEquals(listOf("https://example.com/untitled"), parsed.map { it.url })
        assertEquals("", parsed.single().title)
    }

    /**
     * An unclosed <A> makes the lazy `(.*?)</a>` alternative scan to EOF and
     * fail at every subsequent start position, which is O(n^2). The caller's
     * size cap is what keeps that bounded - this test just pins that a
     * malformed file still terminates quickly at a realistic size.
     */
    @Test(timeout = 5_000)
    fun `a large file full of unclosed anchors still terminates`() {
        val junk = buildString {
            append("<DL><p>")
            repeat(2_000) { append("""<DT><A HREF="https://example.com/$it">t$it""") }
        }
        BookmarkHtmlParser.parse(junk)
    }
}
