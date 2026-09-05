package dev.punit.tidylink.shared.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncDao {

    // Trash (tombstones)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrash(trashed: TrashedLink)

    @Query("SELECT * FROM trashed_links WHERE id = :id")
    suspend fun getTrash(id: String): TrashedLink?

    @Query("DELETE FROM trashed_links WHERE id = :id")
    suspend fun deleteTrash(id: String)

    /** Tombstones created STRICTLY after [t]. */
    @Query("SELECT * FROM trashed_links WHERE deletedAt > :t")
    suspend fun trashedSince(t: Long): List<TrashedLink>

    /** The 90-day trash purge - called once per app launch. */
    @Query("DELETE FROM trashed_links WHERE deletedAt < :cutoff")
    suspend fun purgeTrashBefore(cutoff: Long)

    @Query("DELETE FROM links WHERE id = :id")
    suspend fun deleteLink(id: String)

    /**
     * Tombstone + row delete as ONE transaction - a crash between the two
     * statements would leave the id in both tables, and a later sync could
     * re-trash a restored edit. This is the local-delete path's atomic pair.
     */
    @Transaction
    suspend fun trashAndDeleteLink(trashed: TrashedLink) {
        insertTrash(trashed)
        deleteLink(trashed.id)
    }

    // Peers

    @Upsert
    suspend fun upsertPeer(peer: Peer)

    @Query("SELECT * FROM peers WHERE deviceId = :deviceId")
    suspend fun getPeer(deviceId: String): Peer?

    @Query("DELETE FROM peers WHERE deviceId = :deviceId")
    suspend fun deletePeer(deviceId: String)

    @Query("SELECT * FROM peers")
    fun observePeers(): Flow<List<Peer>>

    // Watermarks

    @Query("SELECT lastSyncAt FROM sync_state WHERE peerId = :peerId")
    suspend fun watermarkOrNull(peerId: String): Long?

    /** Never-synced peers start at 0, so a first sync requests everything. */
    suspend fun getWatermark(peerId: String): Long = watermarkOrNull(peerId) ?: 0L

    @Upsert
    suspend fun setWatermark(state: SyncState)
}
