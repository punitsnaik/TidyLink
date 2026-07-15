package dev.punit.tidylink.data.local

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

    @Test
    fun `pinned links float to the top in every sort order`() {
        for (sort in SortOrder.entries) {
            assertTrue(
                sort.name,
                LinkQueryBuilder.build("", null, sort).sql.contains("ORDER BY links.pinned DESC"),
            )
        }
    }
}
