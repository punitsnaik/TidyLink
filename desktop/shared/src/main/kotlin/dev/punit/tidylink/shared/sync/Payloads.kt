package dev.punit.tidylink.shared.sync

import dev.punit.tidylink.shared.db.Link
import dev.punit.tidylink.shared.db.TrashedLink
import kotlinx.serialization.Serializable

/** Wire form of a [Link] - every field, same names, so the two convert 1:1. */
@Serializable
data class LinkPayload(
    val id: String,
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

fun Link.toPayload() = LinkPayload(
    id = id, url = url, title = title, description = description,
    imageUrl = imageUrl, category = category, aiSummary = aiSummary,
    timestamp = timestamp, dedupeKey = dedupeKey, pinned = pinned,
    scrapeAttempts = scrapeAttempts, note = note, modifiedAt = modifiedAt,
)

fun LinkPayload.toLink() = Link(
    id = id, url = url, title = title, description = description,
    imageUrl = imageUrl, category = category, aiSummary = aiSummary,
    timestamp = timestamp, dedupeKey = dedupeKey, pinned = pinned,
    scrapeAttempts = scrapeAttempts, note = note, modifiedAt = modifiedAt,
)

/** Wire form of a [TrashedLink] tombstone. */
@Serializable
data class TrashPayload(val id: String, val json: String, val deletedAt: Long)

fun TrashedLink.toPayload() = TrashPayload(id = id, json = json, deletedAt = deletedAt)

/** Everything one device has to say since the peer's watermark. */
@Serializable
data class SyncBatch(
    val fromDevice: String,
    val sentAt: Long,
    val links: List<LinkPayload>,
    val trashed: List<TrashPayload>,
)
