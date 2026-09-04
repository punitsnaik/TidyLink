package dev.punit.tidylink.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Peers and watermarks (schema v9, device sync). Kept separate from [LinkDao] on purpose - nothing here needs the paging/FTS machinery that dao carries. */
@Dao
interface SyncDao {

    @Upsert
    suspend fun upsertPeer(peer: PeerEntity)

    @Query("SELECT * FROM peers WHERE deviceId = :deviceId")
    suspend fun getPeer(deviceId: String): PeerEntity?

    @Query("SELECT * FROM peers")
    fun observePeers(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers")
    suspend fun getPeers(): List<PeerEntity>

    @Query("DELETE FROM peers WHERE deviceId = :deviceId")
    suspend fun deletePeer(deviceId: String)

    @Query("SELECT lastSyncAt FROM sync_state WHERE peerId = :peerId")
    suspend fun watermarkOrNull(peerId: String): Long?

    /** Never-synced peers start at 0, so a first sync requests everything. */
    suspend fun getWatermark(peerId: String): Long = watermarkOrNull(peerId) ?: 0L

    @Upsert
    suspend fun setWatermark(state: SyncStateEntity)

    /** Single-tombstone lookup - [LinkDao] only has the by-ids batch version. */
    @Query("SELECT * FROM trashed_links WHERE id = :id")
    suspend fun getTrashById(id: String): TrashedLinkEntity?

    /** Tombstones created STRICTLY after [t] - what device sync sends out. */
    @Query("SELECT * FROM trashed_links WHERE deletedAt > :t")
    suspend fun trashedSince(t: Long): List<TrashedLinkEntity>

    @Query("INSERT OR REPLACE INTO trashed_links (id, json, deletedAt) VALUES (:id, :json, :deletedAt)")
    suspend fun upsertTrash(id: String, json: String, deletedAt: Long)

    @Query("DELETE FROM trashed_links WHERE id = :id")
    suspend fun deleteTrashById(id: String)
}
