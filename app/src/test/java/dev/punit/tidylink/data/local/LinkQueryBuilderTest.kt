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
     * Order matters: SimpleSQLiteQuery binds positionally, so a mismatch
     * here silently filters by the wrong value.
     */
    @Test
    fun `search and category bind in the order they appear in the sql`() {
        val query = LinkQueryBuilder.build("kotl", category = "Dev", sort = SortOrder.NEWEST)
        assertEquals(listOf("kotl*", "Dev"), boundArgs(query))
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
