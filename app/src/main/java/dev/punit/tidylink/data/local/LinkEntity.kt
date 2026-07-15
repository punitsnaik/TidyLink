package dev.punit.tidylink.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable // for JSON export/import
@Entity(
    tableName = "links",
    indices = [Index(value = ["dedupeKey"])],
)
data class LinkEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val url: String,
    val title: String,
    val description: String,
    val imageUrl: String?,
    val category: String,
    val tags: List<String>,
    val aiSummary: String,
    val timestamp: Long = System.currentTimeMillis(),
    /**
     * Canonical duplicate-detection key (scheme/www/tracking-param
     * insensitive). Indexed so duplicate checks don't scan the table.
     * Blank only on legacy rows; backfilled at app start.
     */
    @ColumnInfo(defaultValue = "") val dedupeKey: String = "",
    /** Pinned links float to the top of every sort order. */
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
    /**
     * How many scrape attempts this link has had. Caps re-scraping of pages
     * that simply have no Open Graph image, so Refresh doesn't hit them
     * forever.
     */
    @ColumnInfo(defaultValue = "0") val scrapeAttempts: Int = 0,
)
