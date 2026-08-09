package dev.punit.tidylink.shared.sync

import dev.punit.tidylink.shared.db.Link
import dev.punit.tidylink.shared.db.TidyLinkDb
import dev.punit.tidylink.shared.db.TrashedLink
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncMergerTest {

    private lateinit var db: TidyLinkDb

    @Before
    fun setUp() {
        db = TidyLinkDb.inMemory()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun link(
        id: String,
        title: String = "",
        timestamp: Long = 0L,
        modifiedAt: Long,
    ) = Link(
        id = id, url = "https://example.com/$id", title = title,
        timestamp = timestamp, modifiedAt = modifiedAt,
    )

    private fun batch(
        from: String,
        links: List<LinkPayload> = emptyList(),
        trashed: List<TrashPayload> = emptyList(),
    ) = SyncBatch(fromDevice = from, sentAt = 999L, links = links, trashed = trashed)

    private fun trashJson(l: Link): String = Json.encodeToString(Link.serializer(), l)

    // Rule 1: incoming link vs local link, LWW on modifiedAt

    @Test
    fun newer_remote_link_wins() = runTest {
        val merger = SyncMerger(db, "mac")
        db.linkDao().upsert(link("a", title = "old", modifiedAt = 100))
        val incoming = link("a", title = "new", modifiedAt = 200).toPayload()
        val result = merger.apply(batch("phone", links = listOf(incoming)), lastSyncAt = 0)
        assertEquals(ApplyResult(upserted = 1, trashed = 0, restored = 0, ignored = 0), result)
        assertEquals("new", db.linkDao().getById("a")?.title)
    }

    @Test
    fun older_remote_link_is_ignored() = runTest {
        val merger = SyncMerger(db, "mac")
        db.linkDao().upsert(link("a", title = "local", modifiedAt = 200))
        val incoming = link("a", title = "stale", modifiedAt = 100).toPayload()
        val result = merger.apply(batch("phone", links = listOf(incoming)), lastSyncAt = 0)
        assertEquals(ApplyResult(0, 0, 0, 1), result)
        assertEquals("local", db.linkDao().getById("a")?.title)
    }

    @Test
    fun tie_break_incoming_wins_when_fromDevice_is_lexicographically_greater() = runTest {
        val merger = SyncMerger(db, "mac")
        db.linkDao().upsert(link("a", title = "local", modifiedAt = 100))
        val incoming = link("a", title = "remote", modifiedAt = 100).toPayload()
        val result = merger.apply(batch("phone", links = listOf(incoming)), lastSyncAt = 0)
        assertEquals(ApplyResult(1, 0, 0, 0), result)
        assertEquals("remote", db.linkDao().getById("a")?.title)
    }

    @Test
    fun tie_break_local_wins_when_fromDevice_is_lexicographically_smaller() = runTest {
        val merger = SyncMerger(db, "phone")
        db.linkDao().upsert(link("a", title = "local", modifiedAt = 100))
        val incoming = link("a", title = "remote", modifiedAt = 100).toPayload()
        val result = merger.apply(batch("mac", links = listOf(incoming)), lastSyncAt = 0)
        assertEquals(ApplyResult(0, 0, 0, 1), result)
        assertEquals("local", db.linkDao().getById("a")?.title)
    }

    @Test
    fun incoming_link_with_nothing_local_is_a_plain_upsert() = runTest {
        val merger = SyncMerger(db, "mac")
        val incoming = link("a", title = "fresh", modifiedAt = 100).toPayload()
        val result = merger.apply(batch("phone", links = listOf(incoming)), lastSyncAt = 0)
        assertEquals(ApplyResult(1, 0, 0, 0), result)
        assertEquals("fresh", db.linkDao().getById("a")?.title)
    }

    // Rule 2: incoming link vs local trash

    @Test
    fun concurrent_edit_and_delete_incoming_link_vs_local_trash_restores_the_edit() = runTest {
        val merger = SyncMerger(db, "mac")
        val old = link("a", title = "old", modifiedAt = 90)
        // Local trashed at 150, peer edited at 120: both after lastSyncAt=100,
        // so CONCURRENT - the edit wins even though the delete is newer.
        db.syncDao().insertTrash(TrashedLink("a", trashJson(old), deletedAt = 150))
        val incoming = link("a", title = "edited", modifiedAt = 120).toPayload()
        val result = merger.apply(batch("phone", links = listOf(incoming)), lastSyncAt = 100)
        assertEquals(ApplyResult(upserted = 0, trashed = 0, restored = 1, ignored = 0), result)
        assertEquals("edited", db.linkDao().getById("a")?.title)
        assertNull(db.syncDao().getTrash("a"))
    }

    @Test
    fun non_concurrent_edit_after_delete_restores_by_lww() = runTest {
        val merger = SyncMerger(db, "mac")
        val old = link("a", title = "old", modifiedAt = 40)
        // Trashed at 50, before lastSyncAt=100 - not concurrent. Edit at 150 is newer: restore.
        db.syncDao().insertTrash(TrashedLink("a", trashJson(old), deletedAt = 50))
        val incoming = link("a", title = "edited", modifiedAt = 150).toPayload()
        val result = merger.apply(batch("phone", links = listOf(incoming)), lastSyncAt = 100)
        assertEquals(ApplyResult(0, 0, 1, 0), result)
        assertEquals("edited", db.linkDao().getById("a")?.title)
        assertNull(db.syncDao().getTrash("a"))
    }

    @Test
    fun incoming_link_older_than_local_trash_not_concurrent_is_ignored() = runTest {
        val merger = SyncMerger(db, "mac")
        val old = link("a", title = "old", modifiedAt = 40)
        // Edit at 50 predates lastSyncAt=100, delete at 150: not concurrent, delete is newer.
        db.syncDao().insertTrash(TrashedLink("a", trashJson(old), deletedAt = 150))
        val incoming = link("a", title = "stale edit", modifiedAt = 50).toPayload()
        val result = merger.apply(batch("phone", links = listOf(incoming)), lastSyncAt = 100)
        assertEquals(ApplyResult(0, 0, 0, 1), result)
        assertNull(db.linkDao().getById("a"))
        assertEquals(150, db.syncDao().getTrash("a")?.deletedAt)
    }

    // Rule 3: incoming trash vs local link

    @Test
    fun concurrent_edit_and_delete_incoming_trash_vs_local_link_keeps_the_edit() = runTest {
        val merger = SyncMerger(db, "mac")
        // Local edit at 120, peer delete at 150: both after lastSyncAt=100 - the edit wins.
        db.linkDao().upsert(link("a", title = "edited", modifiedAt = 120))
        val incoming = TrashPayload("a", "{}", deletedAt = 150)
        val result = merger.apply(batch("phone", trashed = listOf(incoming)), lastSyncAt = 100)
        assertEquals(ApplyResult(0, 0, 0, 1), result)
        assertEquals("edited", db.linkDao().getById("a")?.title)
        assertNull(db.syncDao().getTrash("a"))
    }

    @Test
    fun non_concurrent_delete_after_edit_trashes_the_local_link() = runTest {
        val merger = SyncMerger(db, "mac")
        val local = link("a", title = "edited", modifiedAt = 50)
        // Edit at 50 predates lastSyncAt=100, delete at 150: not concurrent, delete wins.
        db.linkDao().upsert(local)
        val incoming = TrashPayload("a", "{}", deletedAt = 150)
        val result = merger.apply(batch("phone", trashed = listOf(incoming)), lastSyncAt = 100)
        assertEquals(ApplyResult(upserted = 0, trashed = 1, restored = 0, ignored = 0), result)
        assertNull(db.linkDao().getById("a"))
        val tomb = db.syncDao().getTrash("a")
        assertNotNull(tomb)
        assertEquals(150, tomb.deletedAt)
        // The tombstone carries the LOCAL row's serialization, so a restore recovers the edit.
        assertEquals(local, Json.decodeFromString(Link.serializer(), tomb.json))
    }

    @Test
    fun incoming_trash_older_than_local_link_not_concurrent_is_ignored() = runTest {
        val merger = SyncMerger(db, "mac")
        db.linkDao().upsert(link("a", title = "kept", modifiedAt = 80))
        // Delete at 50 predates the edit at 80; neither after lastSyncAt=100.
        val incoming = TrashPayload("a", "{}", deletedAt = 50)
        val result = merger.apply(batch("phone", trashed = listOf(incoming)), lastSyncAt = 100)
        assertEquals(ApplyResult(0, 0, 0, 1), result)
        assertEquals("kept", db.linkDao().getById("a")?.title)
        assertNull(db.syncDao().getTrash("a"))
    }

    // Rule 4: incoming trash vs local trash or nothing

    @Test
    fun incoming_trash_newer_than_local_trash_replaces_it() = runTest {
        val merger = SyncMerger(db, "mac")
        db.syncDao().insertTrash(TrashedLink("a", "{\"local\":true}", deletedAt = 50))
        val incoming = TrashPayload("a", "{\"remote\":true}", deletedAt = 150)
        val result = merger.apply(batch("phone", trashed = listOf(incoming)), lastSyncAt = 0)
        assertEquals(ApplyResult(0, 1, 0, 0), result)
        assertEquals(150, db.syncDao().getTrash("a")?.deletedAt)
        assertEquals("{\"remote\":true}", db.syncDao().getTrash("a")?.json)
    }

    @Test
    fun incoming_trash_older_than_local_trash_is_ignored() = runTest {
        val merger = SyncMerger(db, "mac")
        db.syncDao().insertTrash(TrashedLink("a", "{\"local\":true}", deletedAt = 150))
        val incoming = TrashPayload("a", "{\"remote\":true}", deletedAt = 50)
        val result = merger.apply(batch("phone", trashed = listOf(incoming)), lastSyncAt = 0)
        assertEquals(ApplyResult(0, 0, 0, 1), result)
        assertEquals(150, db.syncDao().getTrash("a")?.deletedAt)
    }

    @Test
    fun incoming_trash_with_nothing_local_inserts_the_tombstone() = runTest {
        val merger = SyncMerger(db, "mac")
        val incoming = TrashPayload("a", "{\"remote\":true}", deletedAt = 150)
        val result = merger.apply(batch("phone", trashed = listOf(incoming)), lastSyncAt = 0)
        assertEquals(ApplyResult(0, 1, 0, 0), result)
        assertEquals("{\"remote\":true}", db.syncDao().getTrash("a")?.json)
    }

    // Idempotency

    @Test
    fun applying_the_same_batch_twice_makes_the_second_pass_all_ignored() = runTest {
        val merger = SyncMerger(db, "mac")
        val b = batch(
            "phone",
            links = listOf(link("a", title = "x", modifiedAt = 100).toPayload()),
            trashed = listOf(TrashPayload("b", "{}", deletedAt = 100)),
        )
        val first = merger.apply(b, lastSyncAt = 0)
        assertEquals(ApplyResult(upserted = 1, trashed = 1, restored = 0, ignored = 0), first)
        val second = merger.apply(b, lastSyncAt = 0)
        assertEquals(ApplyResult(0, 0, 0, 2), second)
    }

    // Same id in both lists of one batch: peer edited at t1 then trashed at t2 > t1

    @Test
    fun edited_then_trashed_on_the_peer_in_one_batch_ends_trashed_locally() = runTest {
        val merger = SyncMerger(db, "mac")
        val edited = link("a", title = "final edit", modifiedAt = 110)
        val b = batch(
            "phone",
            links = listOf(edited.toPayload()),
            trashed = listOf(TrashPayload("a", trashJson(edited), deletedAt = 120)),
        )
        val result = merger.apply(b, lastSyncAt = 100)
        assertEquals(1, result.upserted)
        assertEquals(1, result.trashed)
        assertNull(db.linkDao().getById("a"))
        val tomb = db.syncDao().getTrash("a")
        assertNotNull(tomb)
        assertEquals(120, tomb.deletedAt)
        assertEquals(edited, Json.decodeFromString(Link.serializer(), tomb.json))
    }

    // changesSince

    @Test
    fun changesSince_excludes_rows_at_or_before_the_watermark() = runTest {
        val merger = SyncMerger(db, "mac")
        db.linkDao().upsert(link("before", modifiedAt = 99))
        db.linkDao().upsert(link("at", modifiedAt = 100))
        db.linkDao().upsert(link("after", modifiedAt = 101))
        db.syncDao().insertTrash(TrashedLink("t-at", "{}", deletedAt = 100))
        db.syncDao().insertTrash(TrashedLink("t-after", "{}", deletedAt = 101))
        val b = merger.changesSince(100)
        assertEquals("mac", b.fromDevice)
        assertTrue(b.sentAt > 0)
        assertEquals(listOf("after"), b.links.map { it.id })
        assertEquals(listOf("t-after"), b.trashed.map { it.id })
    }
}
