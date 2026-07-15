package dev.punit.tidylink.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class FtsQuerySanitizerTest {

    /**
     * The crux: FTS4's prefix operator only binds to a bare token. Quoting
     * ("kotl"*) turns it into an exact phrase match and silently drops the
     * `*`, which is why prefix search returned nothing.
     */
    @Test
    fun `tokens are bare and prefix-starred, never quoted`() {
        assertEquals("kotl* compo*", sanitizeFtsQuery("kotl compo"))
    }

    @Test
    fun `quotes are separators, not literals`() {
        assertEquals("quoted*", sanitizeFtsQuery("\"quoted\""))
    }

    @Test
    fun `fts operators are neutralized by lowercasing and stripping`() {
        // FTS only treats these as keywords in uppercase.
        assertEquals("and*", sanitizeFtsQuery("AND"))
        assertEquals("near* x*", sanitizeFtsQuery("NEAR x"))
        // Column filter, prefix/exclusion operators, grouping.
        assertEquals("title* foo*", sanitizeFtsQuery("title:foo"))
        assertEquals("kot*", sanitizeFtsQuery("^kot"))
        assertEquals("kot*", sanitizeFtsQuery("(kot)"))
        assertEquals("kot*", sanitizeFtsQuery("-kot"))
        assertEquals("kot*", sanitizeFtsQuery("kot*"))
    }

    @Test
    fun `punctuation inside a term splits it into tokens`() {
        assertEquals("jetpack* compose*", sanitizeFtsQuery("jetpack-compose"))
        assertEquals("example* com*", sanitizeFtsQuery("example.com"))
    }

    @Test
    fun `digits and unicode letters survive`() {
        assertEquals("android* 15*", sanitizeFtsQuery("Android 15"))
        assertEquals("café*", sanitizeFtsQuery("Café"))
    }

    @Test
    fun `blank and operator-only input produce an empty query`() {
        assertEquals("", sanitizeFtsQuery("   "))
        assertEquals("", sanitizeFtsQuery("\""))
        assertEquals("", sanitizeFtsQuery("\" \""))
        assertEquals("", sanitizeFtsQuery("*"))
        assertEquals("", sanitizeFtsQuery("-"))
    }

    @Test
    fun `extra whitespace between tokens is collapsed`() {
        assertEquals("a* b*", sanitizeFtsQuery("  a   b  "))
    }
}
