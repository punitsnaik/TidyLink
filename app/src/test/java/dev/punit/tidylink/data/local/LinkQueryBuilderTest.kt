package dev.punit.tidylink.data.local

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkQueryBuilderTest {

    /**
     * `links_fts` shares the column names title/description/category/tags/
     * aiSummary with `links`. Any unqualified reference to one of those in
     * the search query is an "ambiguous column name" SQLite error at
     * runtime, which Paging surfaces as an empty list.
     */
    @Test
    fun `sort columns are table-qualified so the fts join stays unambiguous`() {
        val shared = listOf("title", "description", "category", "tags", "aiSummary")
        for (sort in SortOrder.entries) {
            for (column in shared) {
                assertFalse(
                    "${sort.name} references bare `$column`: ${sort.orderBy}",
                    Regex("(?<!\\.)\\b$column\\b").containsMatchIn(sort.orderBy),
                )
            }
        }
    }

    @Test
    fun `blank search skips the fts join entirely`() {
        val query = LinkQueryBuilder.build("   ", category = null, sort = SortOrder.NEWEST)
        assertFalse(query.sql.contains("links_fts"))
        assertEquals(0, query.argCount)
    }

    @Test
    fun `search joins fts and binds the sanitized query`() {
        val query = LinkQueryBuilder.build("kotl", category = null, sort = SortOrder.TITLE_AZ)
        assertTrue(query.sql.contains("links_fts MATCH ?"))
        assertEquals(1, query.argCount)
    }

    @Test
    fun `search and category filter bind both args in order`() {
        val query = LinkQueryBuilder.build("kotl", category = "Dev", sort = SortOrder.NEWEST)
        assertTrue(query.sql.contains("links_fts MATCH ?"))
        assertTrue(query.sql.contains("links.category = ?"))
        assertEquals(2, query.argCount)
    }

    /**
     * `tags` is a JSON array in one column, so the filter is a LIKE over
     * its stored text. Without the JSON quotes around the needle, `"and"`
     * would match inside `["android"]` and the chip would return links
     * that don't carry the tag at all.
     */
    @Test
    fun `tag filter binds the needle wrapped in its json quotes`() {
        val query = LinkQueryBuilder.build("", category = null, sort = SortOrder.NEWEST, tag = "and")
        assertTrue(query.sql.contains("links.tags LIKE ?"))
        assertEquals(1, query.argCount)
        assertEquals("%\"and\"%", boundArgs(query).single())
    }

    @Test
    fun `tag needle cannot match a longer tag that starts with it`() {
        val needle = boundArgs(
            LinkQueryBuilder.build("", null, SortOrder.NEWEST, tag = "and")
        ).single() as String
        // Mirrors SQLite LIKE semantics for a needle with no wildcards left
        // in it: % anchors either end, the rest is a literal substring.
        val literal = needle.removePrefix("%").removeSuffix("%")
        assertTrue("""["and","kotlin"]""".contains(literal))
        assertFalse("""["android"]""".contains(literal))
    }

    @Test
    fun `like wildcards inside a tag are escaped so they match literally`() {
        val needle = boundArgs(
            LinkQueryBuilder.build("", null, SortOrder.NEWEST, tag = "c_100%")
        ).single() as String
        assertEquals("%\"c\\_100\\%\"%", needle)
        assertTrue(
            "escaped needle must declare its escape character",
            LinkQueryBuilder.build("", null, SortOrder.NEWEST, tag = "c_100%")
                .sql.contains("ESCAPE"),
        )
    }

    @Test
    fun `search category and tag all compose with correct binding order`() {
        val query = LinkQueryBuilder.build("kotl", category = "Dev", sort = SortOrder.NEWEST, tag = "jvm")
        assertTrue(query.sql.contains("links_fts MATCH ?"))
        assertTrue(query.sql.contains("links.category = ?"))
        assertTrue(query.sql.contains("links.tags LIKE ?"))
        assertEquals(3, query.argCount)
        // Order matters: SimpleSQLiteQuery binds positionally, so a
        // mismatch here silently filters by the wrong value.
        val args = boundArgs(query)
        assertEquals("Dev", args[1])
        assertEquals("%\"jvm\"%", args[2])
    }

    @Test
    fun `no tag filter leaves the tags column out of the query entirely`() {
        val query = LinkQueryBuilder.build("", category = "Dev", sort = SortOrder.NEWEST)
        assertFalse(query.sql.contains("links.tags"))
        assertEquals(1, query.argCount)
    }

    @Test
    fun `pinned links float to the top in every sort order`() {
        for (sort in SortOrder.entries) {
            assertTrue(
                sort.name,
                LinkQueryBuilder.build("", null, sort).sql.contains("ORDER BY links.pinned DESC"),
            )
        }
    }

    /**
     * [SimpleSQLiteQuery] only hands its arguments to a program at bind
     * time, so recording them is the only way to assert what actually
     * reaches SQLite - `argCount` alone would not catch a wrong value or a
     * swapped binding order.
     */
    private fun boundArgs(query: SimpleSQLiteQuery): List<Any?> =
        ArgRecorder().also(query::bindTo).ordered()

    private class ArgRecorder : SupportSQLiteProgram {
        // Bind indexes are 1-based; sorted so ordered() reflects SQL order.
        private val bound = sortedMapOf<Int, Any?>()

        fun ordered(): List<Any?> = bound.values.toList()

        override fun bindNull(index: Int) { bound[index] = null }
        override fun bindLong(index: Int, value: Long) { bound[index] = value }
        override fun bindDouble(index: Int, value: Double) { bound[index] = value }
        override fun bindString(index: Int, value: String) { bound[index] = value }
        override fun bindBlob(index: Int, value: ByteArray) { bound[index] = value }
        override fun clearBindings() { bound.clear() }
        override fun close() = Unit
    }
}
