package dev.punit.tidylink.sync

import dev.punit.tidylink.data.local.AppDatabase
import dev.punit.tidylink.data.local.PeerEntity
import dev.punit.tidylink.data.local.SyncStateEntity
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Wire script - ported from desktop/shared's `sync/SyncSession.kt`, which is
 * the spec (see that file's header comment for the full turn-order rationale).
 * FIXED TURN ORDER, must match exactly - this is a client-only port for v1:
 * Android always dials out ([connect]); it does not run [serve]. The server
 * side is kept here anyway, unused for now, so a future "sync when the Mac
 * is asleep and the phone is the one listening" mode doesn't need a rewrite -
 * ponytail: dead code path, delete if that mode never happens.
 */

/** MAC check failed on a PairHello - the caller may restore the consumed token. */
class PairingMacMismatchException : IOException("pairing token mismatch")

/**
 * Client-side pairing credentials, decoded from the Mac's QR: the one-time
 * [token] plus the server identity the QR promised, which the PairOk reply
 * must match.
 */
data class PairingClient(
    val token: ByteArray,
    val expectedDeviceId: String,
    val expectedPublicKey: ByteArray,
)

/** One sync conversation over one already-connected socket. */
class SyncSession(val db: AppDatabase, val identity: DeviceIdentity) {

    private val random = SecureRandom()

    /** Server side of a socket - unused in v1 (Android never listens). See file header. */
    suspend fun serve(socket: Socket, pairingToken: (() -> ByteArray?)?): PeerEntity = withContext(Dispatchers.IO) {
        socket.soTimeout = TIMEOUT_MS
        val dataIn = DataInputStream(socket.getInputStream())
        val dataOut = DataOutputStream(socket.getOutputStream())
        val ownNonce = ByteArray(NONCE_BYTES).also(random::nextBytes)

        val peer: PeerEntity
        val clientNonce: ByteArray
        when (val hello = dataIn.readMsgFrame()) {
            is Msg.PairHello -> {
                val token = pairingToken?.invoke() ?: throw IOException("pairing not enabled")
                val expected = PairingCrypto.hmac(
                    token, hello.deviceId.encodeToByteArray() + hello.publicKey + hello.nonce
                )
                if (!MessageDigest.isEqual(expected, hello.mac)) {
                    throw PairingMacMismatchException()
                }
                val ownPub = PairingCrypto.publicBytes(identity.keyPair)
                val mac = PairingCrypto.hmac(
                    token, identity.deviceId.encodeToByteArray() + ownPub + ownNonce + hello.nonce
                )
                dataOut.writeMsgFrame(Msg.PairOk(identity.deviceId, identity.name, ownPub, ownNonce, mac))
                peer = PeerEntity(hello.deviceId, hello.name, hello.publicKey, System.currentTimeMillis())
                db.syncDao().upsertPeer(peer)
                clientNonce = hello.nonce
            }
            is Msg.SessionHello -> {
                peer = db.syncDao().getPeer(hello.deviceId) ?: throw IOException("unknown peer")
                dataOut.writeMsgFrame(Msg.SessionOk(ownNonce))
                clientNonce = hello.nonce
            }
            else -> throw IOException("unexpected handshake message")
        }

        val secret = PairingCrypto.sharedSecret(identity.keyPair, peer.publicKey)
        val (clientToServer, serverToClient) = PairingCrypto.sessionKeys(secret, clientNonce, ownNonce)
        val channel = SecureChannel(
            socket.getInputStream(), socket.getOutputStream(),
            sendKey = serverToClient, recvKey = clientToServer,
        )
        if (!channel.receive().contentEquals(OK)) throw IOException("bad hello-confirm")
        channel.send(OK)

        exchange(channel, peer, clientTurn = false)
        peer
    }

    /**
     * Client side (socket already connected by the caller). Reconnect needs
     * [expectedPeer]; first contact needs [pairing] from the scanned QR
     * instead. Returns the peer synced with; throws on any failure, in which
     * case the watermark was not advanced.
     */
    suspend fun connect(
        socket: Socket,
        expectedPeer: PeerEntity?,
        pairing: PairingClient? = null,
    ): PeerEntity = withContext(Dispatchers.IO) {
        socket.soTimeout = TIMEOUT_MS
        val dataIn = DataInputStream(socket.getInputStream())
        val dataOut = DataOutputStream(socket.getOutputStream())
        val ownNonce = ByteArray(NONCE_BYTES).also(random::nextBytes)

        val peer: PeerEntity
        val serverNonce: ByteArray
        if (pairing != null) {
            val ownPub = PairingCrypto.publicBytes(identity.keyPair)
            val mac = PairingCrypto.hmac(
                pairing.token, identity.deviceId.encodeToByteArray() + ownPub + ownNonce
            )
            dataOut.writeMsgFrame(Msg.PairHello(identity.deviceId, identity.name, ownPub, ownNonce, mac))
            val ok = dataIn.readMsgFrame() as? Msg.PairOk ?: throw IOException("expected PairOk")
            val expected = PairingCrypto.hmac(
                pairing.token, ok.deviceId.encodeToByteArray() + ok.publicKey + ok.nonce + ownNonce
            )
            if (!MessageDigest.isEqual(expected, ok.mac)) throw IOException("pairing token mismatch")
            if (ok.deviceId != pairing.expectedDeviceId ||
                !ok.publicKey.contentEquals(pairing.expectedPublicKey)
            ) {
                throw IOException("server identity does not match the QR")
            }
            peer = PeerEntity(ok.deviceId, ok.name, ok.publicKey, System.currentTimeMillis())
            db.syncDao().upsertPeer(peer)
            serverNonce = ok.nonce
        } else {
            peer = expectedPeer ?: throw IllegalArgumentException("expectedPeer required without pairing")
            dataOut.writeMsgFrame(Msg.SessionHello(identity.deviceId, ownNonce))
            val ok = dataIn.readMsgFrame() as? Msg.SessionOk ?: throw IOException("expected SessionOk")
            serverNonce = ok.nonce
        }

        val secret = PairingCrypto.sharedSecret(identity.keyPair, peer.publicKey)
        val (clientToServer, serverToClient) = PairingCrypto.sessionKeys(secret, ownNonce, serverNonce)
        val channel = SecureChannel(
            socket.getInputStream(), socket.getOutputStream(),
            sendKey = clientToServer, recvKey = serverToClient,
        )
        channel.send(OK)
        if (!channel.receive().contentEquals(OK)) throw IOException("bad hello-confirm")

        exchange(channel, peer, clientTurn = true)
        peer
    }

    /** The bidirectional exchange, steps 5-10 of the wire script. */
    private suspend fun exchange(channel: SecureChannel, peer: PeerEntity, clientTurn: Boolean) {
        val merger = SyncMerger(db, identity.deviceId)
        val sync = db.syncDao()
        val myWatermark = sync.getWatermark(peer.deviceId)

        val theirBatch: SyncBatch
        if (clientTurn) {
            channel.send(encodeMsg(Msg.SyncRequest(myWatermark)))
            theirBatch = expect<Msg.Batch>(channel.receive()).batch
            val theirReq = expect<Msg.SyncRequest>(channel.receive())
            channel.send(encodeMsg(Msg.Batch(merger.changesSince(theirReq.watermark))))
            merger.apply(theirBatch, lastSyncAt = myWatermark)
            channel.send(encodeMsg(Msg.Ack(theirBatch.sentAt - 1)))
            expect<Msg.Ack>(channel.receive())
        } else {
            val theirReq = expect<Msg.SyncRequest>(channel.receive())
            channel.send(encodeMsg(Msg.Batch(merger.changesSince(theirReq.watermark))))
            channel.send(encodeMsg(Msg.SyncRequest(myWatermark)))
            theirBatch = expect<Msg.Batch>(channel.receive()).batch
            merger.apply(theirBatch, lastSyncAt = myWatermark)
            expect<Msg.Ack>(channel.receive())
            channel.send(encodeMsg(Msg.Ack(theirBatch.sentAt - 1)))
        }
        // Both acks seen - only now does the watermark advance. sentAt - 1,
        // not sentAt: see the watermark-boundary note in the wire script.
        sync.setWatermark(SyncStateEntity(peer.deviceId, theirBatch.sentAt - 1))
    }

    private inline fun <reified T : Msg> expect(bytes: ByteArray): T =
        decodeMsg(bytes) as? T
            ?: throw IOException("unexpected message, wanted ${T::class.simpleName}")

    private companion object {
        const val TIMEOUT_MS = 15_000
        const val NONCE_BYTES = 16
        val OK = "ok".encodeToByteArray()
    }
}
