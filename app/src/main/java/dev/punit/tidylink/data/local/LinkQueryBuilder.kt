package dev.punit.tidylink.data.local

import androidx.sqlite.db.SimpleSQLiteQuery

/**
 * How the Links list is ordered. Lives in the data layer (labels are
 * resolved to string resources in the UI) so the SQL builder below can
 * depend on it without a UI dependency.
 *
 * Columns are table-qualified: `links_fts` shares the names `title` and
 * `category` with `links`, so a bare `TRIM(title)` in ORDER BY is an
 * "ambiguous column name" error once the search join is present.
 */
enum class SortOrder(internal val orderBy: String) {
    NEWEST("links.timestamp DESC"),
    OLDEST("links.timestamp ASC"),
    TITLE_AZ("TRIM(links.title) COLLATE NOCASE ASC"),
    TITLE_ZA("TRIM(links.title) COLLATE NOCASE DESC"),
    CATEGORY("links.category COLLATE NOCASE ASC, links.timestamp DESC"),
}

/**
 * Builds the paged library query: optional FTS search, optional category
 * filter, chosen sort — all executed in SQLite instead of in memory.
 * Pinned links float above the rest in every sort order.
 */
object LinkQueryBuilder {

    fun build(searchQuery: String, category: String?, sort: SortOrder): SimpleSQLiteQuery {
        val fts = sanitizeFtsQuery(searchQuery)
        val args = mutableListOf<Any>()
        val sql = buildString {
            if (fts.isEmpty()) {
                append("SELECT * FROM links")
                if (category != null) {
                    append(" WHERE category = ?")
                    args += category
                }
            } else {
                append(
                    "SELECT links.* FROM links " +
                        "JOIN links_fts ON links.rowid = links_fts.rowid " +
                        "WHERE links_fts MATCH ?"
                )
                args += fts
                if (category != null) {
                    append(" AND links.category = ?")
                    args += category
                }
            }
            append(" ORDER BY links.pinned DESC, ").append(sort.orderBy)
        }
        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }
}
