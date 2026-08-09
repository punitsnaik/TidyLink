package dev.punit.tidylink.shared.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Field set = Android LinkEntity v8 + modifiedAt. Do not add or rename
 * fields - Android schema v9 will mirror this exactly.
 */
@Serializable
@Entity(tableName = "links", indices = [Index("dedupeKey")])
data class Link(
    @PrimaryKey val id: String,
    val url: String,
    val title: String = "",
    val description: String = "",
    val imageUrl: String? = null,
    val category: String = "Unsorted",
    val aiSummary: String = "",
    val timestamp: Long,
    val dedupeKey: String = "",
    val pinned: Boolean = false,
    val scrapeAttempts: Int = 0,
    val note: String = "",
    val modifiedAt: Long,
)
