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
 * filter, chosen sort - all executed in SQLite instead of in memory.
 * Pinned links float above the rest in every sort order.
 *
 * Conditions are accumulated into a list rather than branched inline:
 * the WHERE/AND bookkeeping is where the bugs live, and appending the SQL
 * fragment and its argument together keeps binding order correct by
 * construction.
 */
object LinkQueryBuilder {

    fun build(
        searchQuery: String,
        category: String?,
        sort: SortOrder,
    ): SimpleSQLiteQuery {
        val fts = sanitizeFtsQuery(searchQuery)
        val args = mutableListOf<Any>()
        val conditions = mutableListOf<String>()

        val from = "SELECT * FROM links"
        if (fts.isNotEmpty()) {
            conditions += "(links.rowid IN (SELECT rowid FROM links_fts WHERE links_fts MATCH ?) " +
                "OR links.relatedLinksJson LIKE ? ESCAPE '\\')"
            args += fts
            args += "%${escapeLike(searchQuery.trim())}%"
        }

        if (category != null) {
            conditions += "links.category = ?"
            args += category
        }

        val sql = buildString {
            append(from)
            if (conditions.isNotEmpty()) {
                append(" WHERE ").append(conditions.joinToString(" AND "))
            }
            append(" ORDER BY links.pinned DESC, ").append(sort.orderBy)
        }
        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }

    private fun escapeLike(value: String): String = value
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")
}
