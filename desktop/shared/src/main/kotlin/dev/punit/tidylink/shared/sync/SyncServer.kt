package dev.punit.tidylink.shared.sync

import dev.punit.tidylink.shared.crypto.PairingCrypto
import dev.punit.tidylink.shared.db.Peer
import dev.punit.tidylink.shared.db.TidyLinkDb
import dev.punit.tidylink.shared.identity.DeviceIdentity
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The most recent successful sync, for "last synced with X at T" UI. */
data class LastSync(val at: Long, val peerName: String)

/**
 * One flat status snapshot instead of a sealed hierarchy, so the UI can show
 * "Listening on 5xxxx" and "last synced with Phone at T" at the same time.
 */
data class SyncStatus(
    val listeningPort: Int? = null,
    val syncingWith: String? = null,
    val lastSync: LastSync? = null,
)

/**
 * Everything the phone needs to pair: this device's identity, where to dial,
 * and the one-time token. [qrJson] is the exact QR payload the Android
 * scanner parses - field names and shapes are a wire contract, do not change.
 */
data class PairingInfo(
    val deviceId: String,
    val name: String,
    val publicKeyB64: String,
    val host: String,
    val port: Int,
    val tokenB64: String,
) {
    fun qrJson(): String = Json.encodeToString(
        QrPayload.serializer(),
        QrPayload(1, deviceId, name, publicKeyB64, host, port, tokenB64),
    )
}

/** Wire shape of the QR. v=1; fields exactly: v, deviceId, name, pub, host, port, token. */
@Serializable
private data class QrPayload(
    val v: Int,
    val deviceId: String,
    val name: String,
    val pub: String,
    val host: String,
    val port: Int,
    val token: String,
)

/**
 * The always-on sync endpoint: accept loop for inbound peers, one-time
 * pairing token, mDNS advertise/watch wiring, and outbound [syncNow].
 * Failed syncs are swallowed into [status] - per the PRD, an absent peer is
 * the NORMAL case and never surfaces as an error.
 */
class SyncServer(val db: TidyLinkDb, val identity: DeviceIdentity, val scope: CoroutineScope) {

    private val session = SyncSession(db, identity)
    private val random = SecureRandom()

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val pairingToken = AtomicReference<ByteArray?>(null)

    // ponytail: lastSeen is in-memory only - peers rediscover via mDNS each
    // launch; persisting last-known addresses is the upgrade if mDNS proves flaky.
    private val lastSeen = ConcurrentHashMap<String, Pair<String, Int>>()
    private val peerLocks = ConcurrentHashMap<String, Mutex>()

    private val discoveryHandles = mutableListOf<AutoCloseable>()

    @Volatile
    private var stopped = false

    /** Open the server socket and start accepting; returns the bound port. Idempotent. */
    @Synchronized
    fun start(): Int {
        serverSocket?.let { return it.localPort }
        stopped = false
        val ss = ServerSocket(0)
        serverSocket = ss
        _status.update { it.copy(listeningPort = ss.localPort) }
        acceptJob = scope.launch(Dispatchers.IO) {
            while (true) {
                val socket = try {
                    ss.accept()
                } catch (_: IOException) {
                    break // socket closed by stop()
                }
                launch { serveOne(socket) }
            }
        }
        // JmDNS init can block for seconds (or fail where multicast is
        // unavailable) - keep it off start()'s caller.
        scope.launch(Dispatchers.IO) { wireDiscovery(ss.localPort) }
        return ss.localPort
    }

    /**
     * Generate a fresh one-time pairing token and the QR payload around it.
     * Valid until the first successful pairing consumes it or the next call
     * replaces it. Starts the server if it is not running yet.
     */
    fun beginPairing(): PairingInfo {
        val port = serverSocket?.localPort ?: start()
        val token = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        pairingToken.set(token)
        return PairingInfo(
            deviceId = identity.deviceId,
            name = identity.name,
            publicKeyB64 = Base64.getEncoder().encodeToString(PairingCrypto.publicBytes(identity.keyPair)),
            host = Discovery.bestLocalAddress()?.hostAddress ?: "127.0.0.1",
            port = port,
            tokenB64 = Base64.getEncoder().encodeToString(token),
        )
    }

    /** Drop the one-time pairing token - an unused QR dies with its dialog. */
    fun cancelPairing() {
        pairingToken.set(null)
    }

    /** Close the socket, stop discovery, cancel the accept loop and its serves. */
    @Synchronized
    fun stop() {
        stopped = true
        pairingToken.set(null)
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
        synchronized(discoveryHandles) {
            discoveryHandles.forEach { runCatching { it.close() } }
            discoveryHandles.clear()
        }
        _status.update { it.copy(listeningPort = null, syncingWith = null) }
    }

    /**
     * Dial every paired peer we have a last-known address for. Peers without
     * one, or that fail, are skipped silently - absent is normal (PRD).
     */
    suspend fun syncNow() {
        val peers = db.syncDao().observePeers().first()
        for (peer in peers) {
            val (host, port) = lastSeen[peer.deviceId] ?: continue
            syncWith(peer, host, port)
        }
    }

    /** Record where a peer was last reachable (mDNS callback, or a test). */
    internal fun noteSeen(deviceId: String, host: String, port: Int) {
        lastSeen[deviceId] = host to port
    }

    private suspend fun serveOne(socket: Socket) {
        // The provider consumes the one-time token atomically the moment the
        // pairing branch actually runs - not after the whole exchange - so the
        // token dies at first successful MAC verification and cannot pair a
        // second stranger during a long sync.
        var taken: ByteArray? = null
        val tokenProvider: () -> ByteArray? = { pairingToken.getAndSet(null).also { taken = it } }
        try {
            val peer = session.serve(socket, tokenProvider)
            // Inbound sockets reveal the peer's host but not its listening
            // port - refresh the host of an already-known address only.
            lastSeen[peer.deviceId]?.let { (_, port) ->
                socket.inetAddress?.hostAddress?.let { lastSeen[peer.deviceId] = it to port }
            }
            _status.update { it.copy(lastSync = LastSync(System.currentTimeMillis(), peer.name)) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: PairingMacMismatchException) {
            // A stranger's garbage MAC must not burn the real pairing window:
            // put the consumed token back, unless a new one was issued since.
            taken?.let { pairingToken.compareAndSet(null, it) }
        } catch (_: Exception) {
            // Failed serve: close the socket and keep listening - a bad or
            // absent peer is never an error surface (PRD).
        } finally {
            runCatching { socket.close() }
        }
    }

    /** Outbound sync, single-flight per peer: a second caller returns immediately. */
    private suspend fun syncWith(peer: Peer, host: String, port: Int) {
        val lock = peerLocks.getOrPut(peer.deviceId) { Mutex() }
        if (!lock.tryLock()) return
        try {
            _status.update { it.copy(syncingWith = peer.name) }
            try {
                withContext(Dispatchers.IO) {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                        session.connect(socket, peer)
                    }
                }
                _status.update { it.copy(lastSync = LastSync(System.currentTimeMillis(), peer.name)) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Peer absent or unreachable: the normal case (PRD) - silent.
            } finally {
                _status.update { it.copy(syncingWith = null) }
            }
        } finally {
            lock.unlock()
        }
    }

    private fun wireDiscovery(port: Int) {
        val handles = listOf(
            Discovery.advertise(identity.deviceId, port),
            Discovery.watch(scope) { deviceId, host, peerPort ->
                if (deviceId != identity.deviceId) {
                    scope.launch {
                        // Only PAIRED peers get dialed - a stranger on the LAN
                        // advertising our service type is ignored.
                        val peer = db.syncDao().getPeer(deviceId) ?: return@launch
                        noteSeen(deviceId, host, peerPort)
                        syncWith(peer, host, peerPort)
                    }
                }
            },
        )
        synchronized(discoveryHandles) {
            if (stopped) handles.forEach { runCatching { it.close() } }
            else discoveryHandles += handles
        }
    }

    private companion object {
        const val TOKEN_BYTES = 16
        const val CONNECT_TIMEOUT_MS = 5_000
    }
}
