package dev.punit.tidylink.shared.sync

import dev.punit.tidylink.shared.crypto.PairingCrypto
import dev.punit.tidylink.shared.db.Link
import dev.punit.tidylink.shared.db.Peer
import dev.punit.tidylink.shared.db.TidyLinkDb
import dev.punit.tidylink.shared.db.TrashedLink
import dev.punit.tidylink.shared.identity.DeviceIdentity
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two real nodes over loopback sockets: pairing, bidirectional sync,
 * convergence, idempotency, and mid-sync failure resumability. A is the
 * server (the Mac showing the QR), B the client (the phone that scanned it).
 */
class TwoNodeSyncTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dbA: TidyLinkDb
    private lateinit var dbB: TidyLinkDb
    private lateinit var idA: DeviceIdentity
    private lateinit var idB: DeviceIdentity
    private lateinit var sessionA: SyncSession
    private lateinit var sessionB: SyncSession

    private val token = ByteArray(16) { (it + 1).toByte() }

    @Before
    fun setUp() {
        dbA = TidyLinkDb.inMemory()
        dbB = TidyLinkDb.inMemory()
        idA = DeviceIdentity.loadOrCreate(tmp.newFolder("a").toPath())
        idB = DeviceIdentity.loadOrCreate(tmp.newFolder("b").toPath())
        sessionA = SyncSession(dbA, idA)
        sessionB = SyncSession(dbB, idB)
    }

    @After
    fun tearDown() {
        dbA.close()
        dbB.close()
    }

    private fun link(id: String, title: String, at: Long) = Link(
        id = id, url = "https://example.com/$id", title = title,
        timestamp = at, modifiedAt = at,
    )

    /** One full sync over a fresh loopback socket pair; A serves, B connects. */
    private fun syncOnce(
        serveToken: ByteArray? = null,
        pairing: PairingClient? = null,
        clientPeer: Peer? = null,
        clientSocket: (Int) -> Socket = { Socket("127.0.0.1", it) },
    ): Pair<Result<Peer>, Result<Peer>> = runBlocking {
        ServerSocket(0).use { ss ->
            val server = async(Dispatchers.IO) {
                // serve takes a token PROVIDER now; a plain closure stands in
                // for SyncServer's consume-on-first-use AtomicReference.
                runCatching { ss.accept().use { sessionA.serve(it, serveToken?.let { t -> { t } }) } }
            }
            val client = async(Dispatchers.IO) {
                runCatching { clientSocket(ss.localPort).use { sessionB.connect(it, clientPeer, pairing) } }
            }
            server.await() to client.await()
        }
    }

    /** First-contact sync using the QR token, asserting it succeeded. */
    private fun pairNodes() {
        val (s, c) = syncOnce(
            serveToken = token,
            pairing = PairingClient(token, idA.deviceId, PairingCrypto.publicBytes(idA.keyPair)),
        )
        check(c.isSuccess) { "pairing connect failed: ${c.exceptionOrNull()}" }
        check(s.isSuccess) { "pairing serve failed: ${s.exceptionOrNull()}" }
    }

    /** Reconnect sync: B dials A using the stored Peer row, no token. */
    private fun reconnectSync(
        clientSocket: (Int) -> Socket = { Socket("127.0.0.1", it) },
    ): Pair<Result<Peer>, Result<Peer>> {
        val peerA = runBlocking { dbB.syncDao().getPeer(idA.deviceId) }
            ?: error("B has no stored peer for A - pair first")
        return syncOnce(clientPeer = peerA, clientSocket = clientSocket)
    }

    /**
     * A client socket whose OutputStream throws on its Nth flush. Frames
     * flush exactly once each, so counting flushes counts sent frames:
     * 1 = SessionHello, 2 = "ok", 3 = SyncRequest, 4 = Batch.
     */
    private fun flakySocket(port: Int, failOnFlush: Int): Socket =
        object : Socket() {
            private var wrapped: OutputStream? = null
            private var flushes = 0
            override fun getOutputStream(): OutputStream {
                wrapped?.let { return it }
                val real = super.getOutputStream()
                return object : FilterOutputStream(real) {
                    override fun write(b: ByteArray, off: Int, len: Int) = real.write(b, off, len)
                    override fun flush() {
                        if (++flushes >= failOnFlush) throw IOException("simulated mid-batch socket failure")
                        real.flush()
                    }
                }.also { wrapped = it }
            }
        }.also { it.connect(InetSocketAddress("127.0.0.1", port)) }

    // (a) pairing + first sync

    @Test
    fun pairing_and_first_sync_transfers_links_and_stores_peers_and_watermarks() = runBlocking<Unit> {
        val now = System.currentTimeMillis()
        listOf(link("l1", "one", now), link("l2", "two", now), link("l3", "three", now))
            .forEach { dbA.linkDao().upsert(it) }

        val (s, c) = syncOnce(
            serveToken = token,
            pairing = PairingClient(token, idA.deviceId, PairingCrypto.publicBytes(idA.keyPair)),
        )

        assertTrue(c.isSuccess, "connect failed: ${c.exceptionOrNull()}")
        assertTrue(s.isSuccess, "serve failed: ${s.exceptionOrNull()}")
        assertEquals(idB.deviceId, s.getOrNull()?.deviceId)
        assertEquals(idA.deviceId, c.getOrNull()?.deviceId)

        assertEquals("one", dbB.linkDao().getById("l1")?.title)
        assertEquals("two", dbB.linkDao().getById("l2")?.title)
        assertEquals("three", dbB.linkDao().getById("l3")?.title)

        assertNotNull(dbA.syncDao().getPeer(idB.deviceId))
        assertNotNull(dbB.syncDao().getPeer(idA.deviceId))
        assertTrue(dbA.syncDao().getWatermark(idB.deviceId) > 0)
        assertTrue(dbB.syncDao().getWatermark(idA.deviceId) > 0)
    }

    // (b) concurrent edit + trash of the SAME link converge with the edit restored

    @Test
    fun concurrent_edit_and_trash_of_the_same_link_converge_with_the_edit_restored() = runBlocking<Unit> {
        val t0 = System.currentTimeMillis()
        listOf(link("l1", "one", t0), link("l2", "two", t0), link("l3", "three", t0))
            .forEach { dbA.linkDao().upsert(it) }
        pairNodes()

        Thread.sleep(5) // strictly after the pairing sync's watermark
        val t1 = System.currentTimeMillis()
        dbB.linkDao().upsert(dbB.linkDao().getById("l1")!!.copy(title = "edited", modifiedAt = t1))
        val localL1 = dbA.linkDao().getById("l1")!!
        dbA.syncDao().insertTrash(TrashedLink("l1", Json.encodeToString(Link.serializer(), localL1), t1))
        dbA.linkDao().delete("l1")

        val (s, c) = reconnectSync()

        assertTrue(s.isSuccess, "serve failed: ${s.exceptionOrNull()}")
        assertTrue(c.isSuccess, "connect failed: ${c.exceptionOrNull()}")
        assertEquals("edited", dbA.linkDao().getById("l1")?.title)
        assertEquals("edited", dbB.linkDao().getById("l1")?.title)
        assertNull(dbA.syncDao().getTrash("l1"))
        assertNull(dbB.syncDao().getTrash("l1"))
    }

    // (c) third sync with no changes = empty batches, watermarks still advance

    @Test
    fun sync_with_no_changes_sends_empty_batches_and_still_advances_watermarks() = runBlocking<Unit> {
        dbA.linkDao().upsert(link("l1", "one", System.currentTimeMillis()))
        pairNodes()

        val wmA = dbA.syncDao().getWatermark(idB.deviceId)
        val wmB = dbB.syncDao().getWatermark(idA.deviceId)
        // Both outgoing batches would be empty by construction:
        assertTrue(dbA.linkDao().changedSince(wmB).isEmpty())
        assertTrue(dbB.linkDao().changedSince(wmA).isEmpty())
        val beforeA = dbA.linkDao().changedSince(0).toSet()
        val beforeB = dbB.linkDao().changedSince(0).toSet()

        Thread.sleep(5)
        val (s, c) = reconnectSync()

        assertTrue(s.isSuccess, "serve failed: ${s.exceptionOrNull()}")
        assertTrue(c.isSuccess, "connect failed: ${c.exceptionOrNull()}")
        assertEquals(beforeA, dbA.linkDao().changedSince(0).toSet())
        assertEquals(beforeB, dbB.linkDao().changedSince(0).toSet())
        assertTrue(dbA.syncDao().getWatermark(idB.deviceId) > wmA)
        assertTrue(dbB.syncDao().getWatermark(idA.deviceId) > wmB)
    }

    // (d) mid-batch socket kill: watermarks unchanged, next sync converges

    @Test
    fun mid_batch_socket_failure_leaves_watermarks_unchanged_and_next_sync_converges() = runBlocking<Unit> {
        val t0 = System.currentTimeMillis()
        listOf(link("l1", "one", t0), link("l2", "two", t0), link("l3", "three", t0))
            .forEach { dbA.linkDao().upsert(it) }
        pairNodes()

        Thread.sleep(5)
        dbA.linkDao().upsert(link("l4", "four", System.currentTimeMillis()))
        val wmA = dbA.syncDao().getWatermark(idB.deviceId)
        val wmB = dbB.syncDao().getWatermark(idA.deviceId)

        // Client output dies on frame 4 (its Batch send) - mid-exchange.
        val (s, c) = reconnectSync { port -> flakySocket(port, failOnFlush = 4) }
        assertTrue(c.isFailure, "client should have failed mid-batch")
        assertTrue(s.isFailure, "server should have failed when the client died")
        assertEquals(wmA, dbA.syncDao().getWatermark(idB.deviceId))
        assertEquals(wmB, dbB.syncDao().getWatermark(idA.deviceId))
        // Consistency: nothing half-applied that a rerun could not fix.
        assertEquals(4, dbA.linkDao().changedSince(0).size)

        Thread.sleep(5)
        val (s2, c2) = reconnectSync()
        assertTrue(s2.isSuccess, "recovery serve failed: ${s2.exceptionOrNull()}")
        assertTrue(c2.isSuccess, "recovery connect failed: ${c2.exceptionOrNull()}")
        assertEquals("four", dbB.linkDao().getById("l4")?.title)
        assertTrue(dbA.syncDao().getWatermark(idB.deviceId) > wmA)
        assertTrue(dbB.syncDao().getWatermark(idA.deviceId) > wmB)
    }

    // wrong pairing token is rejected, nothing stored

    @Test
    fun wrong_pairing_token_is_rejected_and_no_peer_is_stored() = runBlocking<Unit> {
        val badToken = ByteArray(16) { 0x7f }
        val (s, c) = syncOnce(
            serveToken = token,
            pairing = PairingClient(badToken, idA.deviceId, PairingCrypto.publicBytes(idA.keyPair)),
        )
        assertTrue(s.isFailure, "serve should reject a bad pairing MAC")
        assertTrue(c.isFailure, "connect should fail when the server hangs up")
        assertNull(dbA.syncDao().getPeer(idB.deviceId))
        assertNull(dbB.syncDao().getPeer(idA.deviceId))
    }
}
