package dev.punit.tidylink.sync

import androidx.room.withTransaction
import dev.punit.tidylink.data.local.AppDatabase
import dev.punit.tidylink.data.local.LinkEntity
import kotlinx.serialization.json.Json

/** What one [SyncMerger.apply] call did, by row. */
data class ApplyResult(
    val upserted: Int,
    val trashed: Int,
    val restored: Int,
    val ignored: Int,
)

/**
 * The sync brain: builds outgoing batches and merges incoming ones. Ported
 * from desktop/shared's `sync/SyncMerger.kt` - same merge rules, same
 * behaviour, adapted from Room's KMP `useWriterConnection`/
 * `immediateTransaction` (not available on this Room version) to the
 * standard `RoomDatabase.withTransaction` extension.
 *
 * Merge rules: row-level LWW on `modifiedAt`, ties broken lexicographically
 * by device id, deletes are tombstones, and a CONCURRENT edit + delete
 * (both after [apply]'s `lastSyncAt`) resolves in favour of the edit -
 * losing a delete is annoying, losing an edit is data loss.
 */
class SyncMerger(private val db: AppDatabase, private val selfDeviceId: String) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Everything this device changed strictly after [watermark]. */
    suspend fun changesSince(watermark: Long): SyncBatch = SyncBatch(
        fromDevice = selfDeviceId,
        sentAt = System.currentTimeMillis(),
        links = db.linkDao().changedSince(watermark).map { it.toPayload() },
        trashed = db.syncDao().trashedSince(watermark).map { it.toPayload() },
    )

    // The whole batch is ONE transaction: restore (upsert + delete-trash) and
    // trash (insert-trash + delete) are two-statement row operations, and a
    // crash between the statements would leave the id in both tables - the
    // peer's re-send could then re-trash a restored edit.
    suspend fun apply(batch: SyncBatch, lastSyncAt: Long): ApplyResult =
        db.withTransaction { applyInTransaction(batch, lastSyncAt) }

    private suspend fun applyInTransaction(batch: SyncBatch, lastSyncAt: Long): ApplyResult {
        val links = db.linkDao()
        val sync = db.syncDao()
        var upserted = 0
        var trashed = 0
        var restored = 0
        var ignored = 0

        // Links first, then tombstones: a row edited then trashed on the peer
        // arrives in BOTH lists, and its trash entry must resolve against the
        // just-upserted edit. Rows written by this very batch are the peer's
        // own earlier state, not a concurrent local edit - track them so the
        // trash pass falls back to plain LWW for those ids.
        val writtenByThisBatch = mutableSetOf<String>()

        for (p in batch.links) {
            val local = links.getById(p.id)
            if (local != null) {
                // Rule 1: link vs link, LWW; tie = incoming wins iff its
                // device id sorts after ours. An identical row (same
                // modifiedAt, same content) is already applied - ignore it,
                // which is what makes re-applying a batch idempotent.
                val incoming = p.toEntity(local)
                val wins = p.modifiedAt > local.modifiedAt ||
                    (p.modifiedAt == local.modifiedAt && incoming != local &&
                        batch.fromDevice > selfDeviceId)
                if (wins) {
                    links.upsert(incoming)
                    writtenByThisBatch += p.id
                    upserted++
                } else {
                    ignored++
                }
                continue
            }
            val tomb = sync.getTrashById(p.id)
            if (tomb != null) {
                // Rule 2: link vs local trash. Concurrent = edit wins
                // regardless of timestamps; otherwise LWW with the edit
                // winning an exact modifiedAt == deletedAt tie (>=). Rule 3's
                // strict > keeps the link on the same tie, so both devices
                // converge on the link.
                val concurrent = p.modifiedAt > lastSyncAt && tomb.deletedAt > lastSyncAt
                if (concurrent || p.modifiedAt >= tomb.deletedAt) {
                    val localDerived = runCatching {
                        json.decodeFromString(LinkEntity.serializer(), tomb.json)
                    }.getOrNull()
                    links.upsert(p.toEntity(localDerived))
                    sync.deleteTrashById(p.id)
                    writtenByThisBatch += p.id
                    restored++
                } else {
                    ignored++
                }
            } else {
                // Nothing local at all: plain new row.
                links.upsert(p.toEntity())
                writtenByThisBatch += p.id
                upserted++
            }
        }

        for (t in batch.trashed) {
            val local = links.getById(t.id)
            if (local != null) {
                // Rule 3: trash vs local link - mirror of rule 2. Concurrent
                // means the LOCAL edit wins; but a link this batch just wrote
                // is sequential peer history, so only LWW applies to it.
                val concurrent = t.id !in writtenByThisBatch &&
                    local.modifiedAt > lastSyncAt && t.deletedAt > lastSyncAt
                if (!concurrent && t.deletedAt > local.modifiedAt) {
                    // Serialize the LOCAL row into the tombstone so a later
                    // restore recovers what this device actually had.
                    sync.upsertTrash(t.id, json.encodeToString(LinkEntity.serializer(), local), t.deletedAt)
                    links.delete(t.id)
                    trashed++
                } else {
                    ignored++
                }
                continue
            }
            // Rule 4: trash vs local trash or vs nothing - keep the newer.
            val tomb = sync.getTrashById(t.id)
            if (tomb == null || t.deletedAt > tomb.deletedAt) {
                sync.upsertTrash(t.id, t.json, t.deletedAt)
                trashed++
            } else {
                ignored++
            }
        }
        // Rule 5 (no purge messages) is a deliberate absence: each device ages
        // out its own trash >90 days.
        // ponytail: purge propagation skipped - identical end state without it.

        return ApplyResult(upserted, trashed, restored, ignored)
    }
}
