package dev.punit.tidylink.shared.sync

import dev.punit.tidylink.shared.crypto.PairingCrypto
import dev.punit.tidylink.shared.db.Peer
import dev.punit.tidylink.shared.db.TidyLinkDb
import dev.punit.tidylink.shared.identity.DeviceIdentity
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Collections
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncServerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dbA: TidyLinkDb
    private lateinit var idA: DeviceIdentity
    private var server: SyncServer? = null
    private val extraDbs = mutableListOf<TidyLinkDb>()

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dbA = TidyLinkDb.inMemory()
        idA = DeviceIdentity.loadOrCreate(tmp.newFolder("a").toPath())
    }

    @After
    fun tearDown() {
        server?.stop()
        scope.cancel()
        dbA.close()
        extraDbs.forEach { it.close() }
    }

    private fun newServer(): SyncServer = SyncServer(dbA, idA, scope).also { server = it }

    private fun newNode(name: String): Pair<TidyLinkDb, DeviceIdentity> {
        val db = TidyLinkDb.inMemory().also(extraDbs::add)
        return db to DeviceIdentity.loadOrCreate(tmp.newFolder(name).toPath())
    }

    // (1) qrJson round-trip - this exact JSON is what the Android scanner parses.

    @Test
    fun qr_json_carries_exactly_the_seven_contract_fields_and_decodes() {
        val srv = newServer()
        val port = srv.start()
        val info = srv.beginPairing()
        val obj = Json.parseToJsonElement(info.qrJson()).jsonObject

        assertEquals(setOf("v", "deviceId", "name", "pub", "host", "port", "token"), obj.keys)
        assertEquals(1, obj.getValue("v").jsonPrimitive.int)
        assertEquals(idA.deviceId, obj.getValue("deviceId").jsonPrimitive.content)
        assertEquals(idA.name, obj.getValue("name").jsonPrimitive.content)
        assertEquals(port, obj.getValue("port").jsonPrimitive.int)
        assertTrue(obj.getValue("host").jsonPrimitive.content.isNotBlank())

        val token = Base64.getDecoder().decode(obj.getValue("token").jsonPrimitive.content)
        assertEquals(16, token.size)
        val pub = Base64.getDecoder().decode(obj.getValue("pub").jsonPrimitive.content)
        assertTrue(pub.contentEquals(PairingCrypto.publicBytes(idA.keyPair)))
        // Must be a valid X.509-encoded XDH public key:
        assertNotNull(KeyFactory.getInstance("XDH").generatePublic(X509EncodedKeySpec(pub)))
    }

    // (2) pairing end-to-end THROUGH the server accept loop.

    @Test
    fun pairing_through_the_accept_loop_stores_both_peers_and_consumes_the_token() {
        val srv = newServer()
        val port = srv.start()
        val info = srv.beginPairing()
        val token = Base64.getDecoder().decode(info.tokenB64)
        val pub = Base64.getDecoder().decode(info.publicKeyB64)

        val (dbB, idB) = newNode("b")
        val peer = runBlocking {
            withTimeout(10_000) {
                Socket("127.0.0.1", port).use {
                    SyncSession(dbB, idB).connect(it, null, PairingClient(token, info.deviceId, pub))
                }
            }
        }
        assertEquals(idA.deviceId, peer.deviceId)
        runBlocking {
            assertNotNull(dbB.syncDao().getPeer(idA.deviceId), "client must store the server peer")
            assertNotNull(dbA.syncDao().getPeer(idB.deviceId), "server must store the client peer")
            // lastSync is published only AFTER the serve coroutine consumed the
            // token, so waiting on it makes the consumption check deterministic.
            withTimeout(10_000) { srv.status.first { it.lastSync != null } }
            assertEquals(idB.name, srv.status.value.lastSync?.peerName)
        }

        val (dbC, idC) = newNode("c")
        val second = runCatching {
            runBlocking {
                withTimeout(10_000) {
                    Socket("127.0.0.1", port).use {
                        SyncSession(dbC, idC).connect(it, null, PairingClient(token, info.deviceId, pub))
                    }
                }
            }
        }
        assertTrue(second.isFailure, "the one-time token must be consumed by the first pairing")
        runBlocking { assertNull(dbA.syncDao().getPeer(idC.deviceId)) }
    }

    // (3) single-flight: two syncNow calls against one held-open fake peer = one connection.

    @Test
    fun concurrent_sync_now_calls_open_only_one_connection_per_peer() {
        val fakePeer = ServerSocket(0)
        val accepted = Collections.synchronizedList(mutableListOf<Socket>())
        val acceptor = thread {
            try {
                while (true) accepted.add(fakePeer.accept())
            } catch (_: IOException) {
                // fakePeer closed - done
            }
        }
        val srv = newServer()
        val kp = PairingCrypto.generateKeyPair()
        runBlocking {
            dbA.syncDao().upsertPeer(Peer("peer-1", "Fake", PairingCrypto.publicBytes(kp), 1L))
        }
        srv.noteSeen("peer-1", "127.0.0.1", fakePeer.localPort)

        val first = scope.launch { srv.syncNow() }
        try {
            // The fake peer accepts and then never answers, so the first
            // syncNow holds the per-peer lock while blocked on the handshake.
            val deadline = System.currentTimeMillis() + 10_000
            while (accepted.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertEquals(1, accepted.size, "first syncNow should have connected")

            // Second call while the first is still in flight: must return
            // promptly WITHOUT opening a second connection.
            runBlocking { withTimeout(10_000) { srv.syncNow() } }
            assertEquals(1, accepted.size, "second syncNow must not open another connection")
        } finally {
            accepted.forEach { runCatching { it.close() } }
            runCatching { fakePeer.close() }
            acceptor.join(2_000)
        }
        // Closing the sockets makes the first attempt fail (silently, per the
        // PRD - peer absent is the normal case), so the job must finish.
        runBlocking { withTimeout(15_000) { first.join() } }
        assertNull(srv.status.value.syncingWith)
    }

    // (4) status flow transitions.

    @Test
    fun status_reports_listening_port_after_start_and_clears_on_stop() {
        val srv = newServer()
        val port = srv.start()
        assertTrue(port > 0)
        assertEquals(port, srv.status.value.listeningPort)
        assertEquals(port, srv.start(), "second start() is a no-op returning the same port")
        srv.stop()
        assertNull(srv.status.value.listeningPort)
    }

    // (5) Discovery must degrade to a no-op where multicast is unavailable
    // (this sandbox) instead of throwing. Real mDNS is a device-pass check.

    @Test
    fun discovery_watch_and_advertise_never_throw_without_multicast() {
        Discovery.watch(scope) { _, _, _ -> }.close()
        Discovery.advertise("test-device", 12_345).close()
    }
}
