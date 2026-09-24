package dev.punit.tidylink.data.local

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkQueryBuilderTest {

    /**
     * `links_fts` shares the column names title/description/category/
     * aiSummary with `links`. Any unqualified reference to one of those in
     * the search query is an "ambiguous column name" SQLite error at
     * runtime, which Paging surfaces as an empty list.
     */
    @Test
    fun `sort columns are table-qualified so the fts join stays unambiguous`() {
        val shared = listOf("title", "description", "category", "aiSummary")
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
        assertTrue(query.sql.contains("links.url LIKE ?"))
        assertTrue(query.sql.contains("links.resolvedUrl LIKE ?"))
        assertTrue(query.sql.contains("relatedLinksJson LIKE ?"))
        assertTrue(query.sql.contains("links.title LIKE ?"))
        assertEquals(5, query.argCount)
    }

    @Test
    fun `search and category filter bind both args in order`() {
        val query = LinkQueryBuilder.build("kotl", category = "Dev", sort = SortOrder.NEWEST)
        assertTrue(query.sql.contains("links_fts MATCH ?"))
        assertTrue(query.sql.contains("links.category = ?"))
        assertEquals(6, query.argCount)
    }

    /**
     * Order matters: SimpleSQLiteQuery binds positionally, so a mismatch
     * here silently filters by the wrong value.
     */
    @Test
    fun `search and category bind in the order they appear in the sql`() {
        val query = LinkQueryBuilder.build("kotl", category = "Dev", sort = SortOrder.NEWEST)
        assertEquals(listOf("kotl*", "%kotl%", "%kotl%", "%kotl%", "Dev", "%kotl%"), boundArgs(query))
    }

    @Test
    fun `search with url protocol strips protocol from multi-word fts and matches url`() {
        val query = LinkQueryBuilder.build("https://github.com/torvalds", category = null, sort = SortOrder.NEWEST)
        assertTrue(query.sql.contains("links_fts MATCH ?"))
        assertTrue(query.sql.contains("links.url LIKE ?"))
        assertEquals("github* com* torvalds*", boundArgs(query)[0])
        assertEquals("%https://github.com/torvalds%", boundArgs(query)[1])
    }

    @Test
    fun `search with no alphanumeric tokens falls back to like without dropping query`() {
        val query = LinkQueryBuilder.build("///", category = null, sort = SortOrder.NEWEST)
        assertFalse(query.sql.contains("links_fts"))
        assertTrue(query.sql.contains("links.title LIKE ?"))
        assertTrue(query.sql.contains("links.url LIKE ?"))
        assertTrue(query.sql.contains("links.resolvedUrl LIKE ?"))
        assertTrue(query.sql.contains("links.note LIKE ?"))
    }

    @Test
    fun `search orders by title match relevance before sort order`() {
        val query = LinkQueryBuilder.build("compose", category = null, sort = SortOrder.NEWEST)
        assertTrue(
            query.sql.contains(
                "ORDER BY links.pinned DESC, (CASE WHEN links.title LIKE ? ESCAPE '\\' THEN 0 ELSE 1 END), links.timestamp DESC"
            )
        )
    }

    /**
     * The tags column was dropped in schema v7. A query still naming it
     * would fail at runtime as "no such column", which Paging surfaces as
     * a silently empty list rather than a crash.
     */
    @Test
    fun `no query mentions the dropped tags column`() {
        for (sort in SortOrder.entries) {
            assertFalse(LinkQueryBuilder.build("kotl", "Dev", sort).sql.contains("tags"))
        }
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
