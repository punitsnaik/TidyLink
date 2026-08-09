package dev.punit.tidylink.shared.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LinkDao {

    /**
     * Live list filtered by [query] (empty string = everything), pinned rows
     * first, then newest saved first.
     * ponytail: LIKE search, no FTS on desktop v1 - add FTS when a library is
     * big enough to feel it.
     */
    @Query(
        """
        SELECT * FROM links
        WHERE title LIKE '%' || :query || '%'
           OR url LIKE '%' || :query || '%'
           OR note LIKE '%' || :query || '%'
           OR category LIKE '%' || :query || '%'
        ORDER BY pinned DESC, timestamp DESC
        """
    )
    fun observeAll(query: String): Flow<List<Link>>

    @Query("SELECT * FROM links WHERE id = :id")
    suspend fun getById(id: String): Link?

    @Upsert
    suspend fun upsert(link: Link)

    @Query("DELETE FROM links WHERE id = :id")
    suspend fun delete(id: String)

    /** Rows modified STRICTLY after [t] - the boundary row itself was already synced. */
    @Query("SELECT * FROM links WHERE modifiedAt > :t")
    suspend fun changedSince(t: Long): List<Link>
}
