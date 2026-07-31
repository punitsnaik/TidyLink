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
 * filter, optional tag filter, chosen sort - all executed in SQLite
 * instead of in memory. Pinned links float above the rest in every sort
 * order.
 *
 * Conditions are accumulated into a list rather than branched inline:
 * with three independent optional filters the WHERE/AND bookkeeping is
 * where the bugs live, and appending the SQL fragment and its argument
 * together keeps binding order correct by construction.
 */
object LinkQueryBuilder {

    fun build(
        searchQuery: String,
        category: String?,
        sort: SortOrder,
        tag: String? = null,
        unreadOnly: Boolean = false,
    ): SimpleSQLiteQuery {
        val fts = sanitizeFtsQuery(searchQuery)
        val args = mutableListOf<Any>()
        val conditions = mutableListOf<String>()

        val from = if (fts.isEmpty()) {
            "SELECT * FROM links"
        } else {
            conditions += "links_fts MATCH ?"
            args += fts
            "SELECT links.* FROM links JOIN links_fts ON links.rowid = links_fts.rowid"
        }

        if (category != null) {
            conditions += "links.category = ?"
            args += category
        }

        if (tag != null) {
            // `tags` is a JSON array string (see Converters), so the stored
            // form of ["and","android"] is literally `["and","android"]`.
            // Wrapping the needle in its JSON quotes makes this an exact
            // element match: `"and"` cannot match inside `"android"`.
            conditions += "links.tags LIKE ? ESCAPE '\\'"
            args += "%\"${escapeLike(tag)}\"%"
        }

        // No argument: a constant predicate binds nothing, and adding one
        // would shift every later argument's position.
        if (unreadOnly) conditions += "links.isRead = 0"

        val sql = buildString {
            append(from)
            if (conditions.isNotEmpty()) {
                append(" WHERE ").append(conditions.joinToString(" AND "))
            }
            append(" ORDER BY links.pinned DESC, ").append(sort.orderBy)
        }
        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }

    /**
     * Neutralises LIKE's own wildcards so a tag containing `%` or `_`
     * filters to itself instead of to everything. Paired with the
     * `ESCAPE '\'` clause above.
     *
     * ponytail: SQLite's LIKE is case-insensitive for ASCII, so this
     * matches "Kotlin" for "kotlin" - desirable here, and the tag row only
     * ever offers tags that actually exist. A case-sensitive match would
     * need GLOB and a second escaping scheme.
     */
    private fun escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
