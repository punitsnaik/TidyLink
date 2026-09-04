package dev.punit.tidylink.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-peer sync watermark (schema v9): the `sentAt` of the last fully
 * applied batch from that peer. Mirrors desktop/shared's `SyncState` table.
 */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val peerId: String,
    val lastSyncAt: Long,
)
