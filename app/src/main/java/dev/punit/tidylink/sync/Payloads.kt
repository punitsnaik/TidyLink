package dev.punit.tidylink.sync

import dev.punit.tidylink.data.local.LinkEntity
import dev.punit.tidylink.data.local.TrashedLinkEntity
import kotlinx.serialization.Serializable

/**
 * Wire form of a [LinkEntity] - every synced field, same names as
 * desktop/shared's `LinkPayload`, so the two decode into each other.
 */
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

fun LinkEntity.toPayload() = LinkPayload(
    id = id, url = url, title = title, description = description,
    imageUrl = imageUrl, category = category, aiSummary = aiSummary,
    timestamp = timestamp, dedupeKey = dedupeKey, pinned = pinned,
    scrapeAttempts = scrapeAttempts, note = note, modifiedAt = modifiedAt,
)

fun LinkPayload.toEntity(local: LinkEntity? = null) = LinkEntity(
    id = id, url = url, title = title, description = description,
    imageUrl = imageUrl, category = category, aiSummary = aiSummary,
    timestamp = timestamp, dedupeKey = dedupeKey, pinned = pinned,
    scrapeAttempts = scrapeAttempts, note = note, modifiedAt = modifiedAt,
    // Relations are derived and Android-only in this release. Preserve them
    // when the portable link fields are replaced by an incoming sync row.
    resolvedUrl = local?.resolvedUrl.orEmpty(),
    relatedLinksJson = local?.relatedLinksJson ?: "[]",
    relatedLinksScannedAt = local?.relatedLinksScannedAt ?: 0L,
)

/** Wire form of a [TrashedLinkEntity] tombstone. */
@Serializable
data class TrashPayload(val id: String, val json: String, val deletedAt: Long)

fun TrashedLinkEntity.toPayload() = TrashPayload(id = id, json = json, deletedAt = deletedAt)

/** Everything one device has to say since the peer's watermark. */
@Serializable
data class SyncBatch(
    val fromDevice: String,
    val sentAt: Long,
    val links: List<LinkPayload>,
    val trashed: List<TrashPayload>,
)
