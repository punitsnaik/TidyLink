package dev.punit.tidylink.shared.db

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SyncDaoTest {

    private lateinit var db: TidyLinkDb
    private lateinit var dao: SyncDao

    @Before
    fun setUp() {
        db = TidyLinkDb.inMemory()
        dao = db.syncDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun trash_round_trips() = runTest {
        val t = TrashedLink(id = "a", json = """{"id":"a"}""", deletedAt = 42)
        dao.insertTrash(t)
        assertEquals(t, dao.getTrash("a"))
        dao.deleteTrash("a")
        assertNull(dao.getTrash("a"))
    }

    @Test
    fun trashedSince_is_a_strict_boundary() = runTest {
        dao.insertTrash(TrashedLink("before", "{}", deletedAt = 99))
        dao.insertTrash(TrashedLink("at", "{}", deletedAt = 100))
        dao.insertTrash(TrashedLink("after", "{}", deletedAt = 101))
        assertEquals(listOf("after"), dao.trashedSince(100).map { it.id })
    }

    @Test
    fun purgeTrashBefore_removes_old_rows_and_keeps_recent_ones() = runTest {
        dao.insertTrash(TrashedLink("old", "{}", deletedAt = 10))
        dao.insertTrash(TrashedLink("recent", "{}", deletedAt = 200))
        dao.purgeTrashBefore(100)
        assertNull(dao.getTrash("old"))
        assertEquals(200L, dao.getTrash("recent")?.deletedAt)
    }

    @Test
    fun watermark_defaults_to_zero_and_set_overwrites() = runTest {
        assertEquals(0L, dao.getWatermark("phone"))
        dao.setWatermark(SyncState(peerId = "phone", lastSyncAt = 123))
        assertEquals(123L, dao.getWatermark("phone"))
        dao.setWatermark(SyncState(peerId = "phone", lastSyncAt = 456))
        assertEquals(456L, dao.getWatermark("phone"))
    }

    @Test
    fun peer_crud_and_observePeers() = runTest {
        val p = Peer(deviceId = "d1", name = "Pixel", publicKey = byteArrayOf(1, 2, 3), addedAt = 7)
        dao.upsertPeer(p)
        assertEquals(p, dao.getPeer("d1"))
        assertEquals(listOf(p), dao.observePeers().first())
        dao.deletePeer("d1")
        assertNull(dao.getPeer("d1"))
        assertEquals(emptyList(), dao.observePeers().first())
    }

    @Test
    fun peer_equality_is_by_publicKey_content_not_reference() {
        val a = Peer("d1", "Pixel", byteArrayOf(1, 2, 3), 7)
        val b = Peer("d1", "Pixel", byteArrayOf(1, 2, 3), 7)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
