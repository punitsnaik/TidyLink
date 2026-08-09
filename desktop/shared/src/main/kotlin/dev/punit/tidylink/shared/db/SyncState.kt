package dev.punit.tidylink.shared.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Per-peer sync watermark: the `sentAt` of the last fully applied batch from that peer. */
@Entity(tableName = "sync_state")
data class SyncState(
    @PrimaryKey val peerId: String,
    val lastSyncAt: Long,
)
