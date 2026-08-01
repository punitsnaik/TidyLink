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
    /**
     * Set when the link is opened, so the library can resolve instead of
     * only growing. Also togglable by hand from the detail sheet.
     */
    @ColumnInfo(defaultValue = "0") val isRead: Boolean = false,
    /**
     * The user's own words about why this was saved. Every other text field
     * here belongs to the page (title, description) or to the LLM
     * (aiSummary); this one is the only place the user's intent lives, so
     * it is indexed for search too.
     */
    @ColumnInfo(defaultValue = "") val note: String = "",
)
