package dev.punit.tidylink.data.local

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/** A category name together with how many links use it. */
data class CategoryCount(val category: String, val count: Int)

@Dao
interface LinkDao {

    @Upsert
    suspend fun upsert(link: LinkEntity)

    @Upsert
    suspend fun upsertAll(links: List<LinkEntity>)

    /**
     * Paged, filtered, sorted view of the library. The query is built by
     * [LinkQueryBuilder] (filter/sort combinations can't be expressed as a
     * single static @Query). [observedEntities] keeps invalidation working.
     */
    @RawQuery(observedEntities = [LinkEntity::class])
    fun pagingSource(query: SupportSQLiteQuery): PagingSource<Int, LinkEntity>

    @Query("SELECT * FROM links ORDER BY timestamp DESC")
    suspend fun getAllOnce(): List<LinkEntity>

    /** Categories sorted by how many links they hold (busiest first). */
    @Query(
        """
        SELECT category, COUNT(*) AS count FROM links
        GROUP BY category
        ORDER BY count DESC, category COLLATE NOCASE
        """
    )
    fun getCategories(): Flow<List<CategoryCount>>

    @Query(
        """
        SELECT category, COUNT(*) AS count FROM links
        GROUP BY category
        ORDER BY count DESC, category COLLATE NOCASE
        """
    )
    suspend fun getCategoriesOnce(): List<CategoryCount>

    @Query("UPDATE links SET category = :newCategory WHERE category = :oldCategory")
    suspend fun renameCategory(oldCategory: String, newCategory: String)

    @Query("SELECT * FROM links WHERE category = :category")
    suspend fun getByCategory(category: String): List<LinkEntity>

    @Query("SELECT * FROM links WHERE url = :url LIMIT 1")
    suspend fun getByUrl(url: String): LinkEntity?

    @Query("SELECT * FROM links WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): LinkEntity?

    /** Live view of one link - drives the detail sheet under paging. */
    @Query("SELECT * FROM links WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<LinkEntity?>

    @Query("SELECT * FROM links WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<LinkEntity>

    /** Indexed duplicate lookup - no table scan. */
    @Query("SELECT * FROM links WHERE dedupeKey = :key LIMIT 1")
    suspend fun getByDedupeKey(key: String): LinkEntity?


    /** Legacy rows saved before the dedupeKey column existed. */
    /**
     * How many rows are redundant copies - i.e. how many would disappear if
     * duplicates were merged. Rows saved before the column existed have a
     * blank key and are excluded; `backfillDedupeKeys` fills those at app
     * start, and the merge itself recomputes keys anyway, so it can only
     * ever remove at least this many, never fewer.
     */
    @Query("SELECT COUNT(*) - COUNT(DISTINCT dedupeKey) FROM links WHERE dedupeKey != ''")
    fun countDuplicates(): Flow<Int>

    /**
     * Every dedupe key in the library, for bulk imports. One query beats N
     * `getByDedupeKey` round trips when checking a few hundred bookmarks.
     * (This existed once for the old .txt import and went away with it; a
     * bulk import path makes it worth having again.)
     */
    @Query("SELECT dedupeKey FROM links WHERE dedupeKey != ''")
    suspend fun getAllDedupeKeys(): List<String>

    @Query("SELECT * FROM links WHERE dedupeKey = ''")
    suspend fun getMissingDedupeKeys(): List<LinkEntity>

    @Query("SELECT COUNT(*) FROM links WHERE dedupeKey = ''")
    suspend fun countMissingDedupeKeys(): Int

    /**
     * Links worth (re-)scraping: never scraped, or still without a thumbnail
     * after fewer than [maxAttempts] tries. Pages that simply have no OG
     * image stop being re-fetched once they hit the cap.
     */
    @Query(
        """
        SELECT * FROM links
        WHERE scrapeAttempts = 0 OR (imageUrl IS NULL AND scrapeAttempts < :maxAttempts)
        ORDER BY timestamp DESC
        """
    )
    suspend fun getScrapeCandidates(maxAttempts: Int): List<LinkEntity>

    /**
     * Same question as [getScrapeCandidates], just a count. Deliberately
     * kept a character-for-character mirror of that query's WHERE clause
     * (including :maxAttempts) - this is what drives the startup gate
     * (`LinkRepository.hasPendingEnrichment`), and if the two ever
     * disagree the gate either opens for work the sweep won't do, or
     * stays shut on work it would have done.
     */
    @Query(
        """
        SELECT COUNT(*) FROM links
        WHERE scrapeAttempts = 0 OR (imageUrl IS NULL AND scrapeAttempts < :maxAttempts)
        """
    )
    suspend fun countScrapeCandidates(maxAttempts: Int): Int

    /** Links that have been scraped but never successfully classified. */
    @Query(
        """
        SELECT * FROM links
        WHERE scrapeAttempts > 0 AND (category = :fallbackCategory OR aiSummary = '')
        ORDER BY timestamp DESC
        """
    )
    suspend fun getClassifyCandidates(fallbackCategory: String): List<LinkEntity>

    /** How many links still await their first scrape - drives the UI banner. */
    @Query("SELECT COUNT(*) FROM links WHERE scrapeAttempts = 0")
    fun countNeverScraped(): Flow<Int>

    @Query("UPDATE links SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)


    /** Paged view of pinned links only - drives the Pinned tab. */
    @Query("SELECT * FROM links WHERE pinned = 1 ORDER BY timestamp DESC")
    fun pinnedPagingSource(): PagingSource<Int, LinkEntity>

    @Query("UPDATE links SET category = :category WHERE id IN (:ids)")
    suspend fun moveToCategory(ids: List<String>, category: String)

    @Query("DELETE FROM links WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM links WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    // --- Trash -------------------------------------------------------------

    @Upsert
    suspend fun insertTrashed(rows: List<TrashedLinkEntity>)

    /**
     * Moves rows into the trash as one unit. Without the transaction a kill
     * between the two statements either loses the links entirely (deleted,
     * never trashed) or duplicates them (trashed, never deleted).
     */
    @Transaction
    suspend fun moveToTrash(rows: List<TrashedLinkEntity>, ids: List<String>) {
        insertTrashed(rows)
        deleteByIds(ids)
    }

    /** Restoring is the same two steps in the other order, same reasoning. */
    @Transaction
    suspend fun restoreFromTrash(links: List<LinkEntity>, ids: List<String>) {
        upsertAll(links)
        deleteTrashed(ids)
    }

    @Query("SELECT * FROM trashed_links ORDER BY deletedAt DESC")
    fun observeTrash(): Flow<List<TrashedLinkEntity>>

    @Query("SELECT * FROM trashed_links WHERE id IN (:ids)")
    suspend fun getTrashedByIds(ids: List<String>): List<TrashedLinkEntity>

    @Query("SELECT COUNT(*) FROM trashed_links")
    fun countTrashed(): Flow<Int>

    @Query("DELETE FROM trashed_links WHERE id IN (:ids)")
    suspend fun deleteTrashed(ids: List<String>)

    @Query("DELETE FROM trashed_links")
    suspend fun emptyTrash()

    @Query("DELETE FROM trashed_links WHERE deletedAt < :cutoff")
    suspend fun purgeTrashOlderThan(cutoff: Long)
}

/** Runs of letters/digits/underscore - everything else is a separator. */
private val FTS_WORD = Regex("[\\p{L}\\p{N}_]+")

/**
 * Escapes raw user input into a valid FTS MATCH expression with prefix
 * matching, so "kotl compo" matches "Kotlin Compose". Returns an empty
 * string when nothing searchable remains (callers should then skip the
 * FTS join entirely).
 *
 * Room uses FTS3/FTS4 (not FTS5), where the prefix operator only applies to
 * a *bare* token: `kotl*` is a prefix query, but `"kotl"*` is an exact
 * phrase match and the `*` is silently ignored. So tokens must not be
 * quoted. Instead every non-word character is treated as a separator (which
 * strips the FTS operators `" * ^ - ( ) :` along with punctuation), and
 * tokens are lowercased so the query keywords AND/OR/NOT/NEAR - which FTS
 * only recognizes in uppercase - become ordinary search terms. The unicode61
 * tokenizer folds case anyway, so lowercasing costs no recall.
 */
fun sanitizeFtsQuery(userQuery: String): String =
    FTS_WORD.findAll(userQuery)
        .joinToString(" ") { "${it.value.lowercase()}*" }
