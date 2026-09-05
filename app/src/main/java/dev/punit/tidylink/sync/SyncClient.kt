package dev.punit.tidylink.sync

import android.content.Context
import dev.punit.tidylink.data.local.AppDatabase
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Wire shape of the QR the Mac shows - field names are a wire contract, see desktop/shared's `SyncServer.PairingInfo.qrJson`. */
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

/** Result of a pairing or sync attempt, for the UI to show. */
sealed interface SyncOutcome {
    data class Success(val peerName: String) : SyncOutcome
    data class Failure(val message: String) : SyncOutcome
}

/**
 * Everything Android needs to talk to a paired Mac: v1 is CLIENT ONLY (see
 * `SyncSession`'s file header) - this device always dials out, either
 * straight to the address in a freshly-scanned QR, or by finding the peer
 * again via NSD for a later manual sync.
 */
class SyncClient(private val db: AppDatabase, private val identity: DeviceIdentity) {

    private val session = SyncSession(db, identity)

    /** Decode a scanned QR and pair + do the first sync in one shot. */
    suspend fun pairFromQr(qrText: String): SyncOutcome = withContext(Dispatchers.IO) {
        val payload = try {
            Json { ignoreUnknownKeys = true }.decodeFromString(QrPayload.serializer(), qrText)
        } catch (e: Exception) {
            return@withContext SyncOutcome.Failure("Not a TidyLink pairing code")
        }
        val pairing = PairingClient(
            token = Base64.getDecoder().decode(payload.token),
            expectedDeviceId = payload.deviceId,
            expectedPublicKey = Base64.getDecoder().decode(payload.pub),
        )
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(payload.host, payload.port), CONNECT_TIMEOUT_MS)
                val peer = session.connect(socket, expectedPeer = null, pairing = pairing)
                SyncOutcome.Success(peer.name)
            }
        } catch (e: Exception) {
            SyncOutcome.Failure(e.message ?: "Couldn't reach that device")
        }
    }

    /**
     * Re-sync with every already-paired peer: find each one again via NSD
     * (bounded wait - the Mac may not be advertising right now, which is the
     * normal case, not an error) and run the same handshake as pairing minus
     * the token exchange.
     */
    suspend fun syncAll(context: Context, scope: CoroutineScope): List<SyncOutcome> = withContext(Dispatchers.IO) {
        db.syncDao().getPeers().map { peer ->
            val address = findOnLan(context, scope, peer.deviceId)
                ?: return@map SyncOutcome.Failure("${peer.name} not found on this network")
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(address.first, address.second), CONNECT_TIMEOUT_MS)
                    session.connect(socket, expectedPeer = peer)
                }
                SyncOutcome.Success(peer.name)
            } catch (e: Exception) {
                SyncOutcome.Failure("${peer.name}: ${e.message ?: "sync failed"}")
            }
        }
    }

    /** Bounded NSD browse for one specific peer's current host:port. */
    private suspend fun findOnLan(context: Context, scope: CoroutineScope, deviceId: String): Pair<String, Int>? {
        val found = CompletableDeferred<Pair<String, Int>>()
        val handle = NsdDiscovery.watch(context, scope) { seenId, host, port ->
            if (seenId == deviceId) found.complete(host to port)
        }
        return try {
            withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) { found.await() }
        } finally {
            handle.close()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val DISCOVERY_TIMEOUT_MS = 6_000L
    }
}
