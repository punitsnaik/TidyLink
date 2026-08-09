package dev.punit.tidylink.shared.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Tombstone: the deleted link serialized as JSON, same shape as the Android trash table. */
@Entity(tableName = "trashed_links")
data class TrashedLink(
    @PrimaryKey val id: String,
    val json: String,
    val deletedAt: Long,
)
