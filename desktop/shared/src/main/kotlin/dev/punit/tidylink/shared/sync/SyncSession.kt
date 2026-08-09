package dev.punit.tidylink.shared.sync

import dev.punit.tidylink.shared.crypto.PairingCrypto
import dev.punit.tidylink.shared.crypto.SecureChannel
import dev.punit.tidylink.shared.db.Peer
import dev.punit.tidylink.shared.db.SyncState
import dev.punit.tidylink.shared.db.TidyLinkDb
import dev.punit.tidylink.shared.identity.DeviceIdentity
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Wire script - FIXED TURN ORDER, deadlock-free by construction. The Android
 * implementation MUST follow this exact script.
 *
 * Plain length-prefixed JSON frames (Int32 BE length + UTF-8 JSON):
 *   1. client -> server: PairHello (first contact) or SessionHello (reconnect)
 *   2. server -> client: PairOk or SessionOk
 *      (a PairHello MAC that fails HMAC verification = abort, nothing stored)
 *
 * Both sides then derive directional AES-GCM keys from the static-static
 * X25519 secret via PairingCrypto.sessionKeys(secret, clientNonce,
 * serverNonce). The CLIENT is "a": keyAtoB = client->server. Switch to
 * SecureChannel. Encrypted frames, in order:
 *   3. client -> server: "ok"   (literal bytes - mutual key-possession proof;
 *   4. server -> client: "ok"    a GCM tag failure here = wrong keys, abort)
 *   5. client -> server: SyncRequest(client's stored watermark for server)
 *   6. server -> client: Batch(changesSince(client's watermark))
 *   7. server -> client: SyncRequest(server's stored watermark for client)
 *   8. client -> server: Batch(changesSince(server's watermark))
 *      Each side applies the batch it RECEIVED with
 *      lastSyncAt = its OWN stored watermark for the peer.
 *   9. client -> server: Ack(server batch's sentAt)
 *  10. server -> client: Ack(client batch's sentAt)
 *
 * Only after seeing the peer's Ack does either side persist
 * setWatermark(peer, receivedBatch.sentAt). Any exception before that leaves
 * the watermark untouched - resuming just re-sends, and SyncMerger.apply is
 * idempotent, so that is safe.
 */

/**
 * Client-side pairing credentials, decoded from the server's QR: the
 * one-time [token] plus the server identity the QR promised, which the
 * PairOk reply must match.
 */
data class PairingClient(
    val token: ByteArray,
    val expectedDeviceId: String,
    val expectedPublicKey: ByteArray,
)

/** One sync conversation over one already-connected socket. */
class SyncSession(val db: TidyLinkDb, val identity: DeviceIdentity) {

    private val random = SecureRandom()

    /**
     * Server side of a socket: pairing (if [pairingToken] != null and the
     * client sends PairHello) or reconnect, then the full exchange.
     * Returns the peer synced with; throws on any failure, in which case the
     * watermark was not advanced.
     */
    suspend fun serve(socket: Socket, pairingToken: ByteArray?): Peer = withContext(Dispatchers.IO) {
        socket.soTimeout = TIMEOUT_MS
        val dataIn = DataInputStream(socket.getInputStream())
        val dataOut = DataOutputStream(socket.getOutputStream())
        val ownNonce = ByteArray(NONCE_BYTES).also(random::nextBytes)

        val peer: Peer
        val clientNonce: ByteArray
        when (val hello = dataIn.readMsgFrame()) {
            is Msg.PairHello -> {
                val token = pairingToken ?: throw IOException("pairing not enabled")
                val expected = PairingCrypto.hmac(
                    token, hello.deviceId.encodeToByteArray() + hello.publicKey + hello.nonce
                )
                if (!MessageDigest.isEqual(expected, hello.mac)) {
                    throw IOException("pairing token mismatch")
                }
                val ownPub = PairingCrypto.publicBytes(identity.keyPair)
                val mac = PairingCrypto.hmac(
                    token, identity.deviceId.encodeToByteArray() + ownPub + ownNonce + hello.nonce
                )
                dataOut.writeMsgFrame(Msg.PairOk(identity.deviceId, identity.name, ownPub, ownNonce, mac))
                peer = Peer(hello.deviceId, hello.name, hello.publicKey, System.currentTimeMillis())
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

        val secret = PairingCrypto.sharedSecret(identity.keyPair.private, peer.publicKey)
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
        expectedPeer: Peer?,
        pairing: PairingClient? = null,
    ): Peer = withContext(Dispatchers.IO) {
        socket.soTimeout = TIMEOUT_MS
        val dataIn = DataInputStream(socket.getInputStream())
        val dataOut = DataOutputStream(socket.getOutputStream())
        val ownNonce = ByteArray(NONCE_BYTES).also(random::nextBytes)

        val peer: Peer
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
            peer = Peer(ok.deviceId, ok.name, ok.publicKey, System.currentTimeMillis())
            db.syncDao().upsertPeer(peer)
            serverNonce = ok.nonce
        } else {
            peer = expectedPeer ?: throw IllegalArgumentException("expectedPeer required without pairing")
            dataOut.writeMsgFrame(Msg.SessionHello(identity.deviceId, ownNonce))
            val ok = dataIn.readMsgFrame() as? Msg.SessionOk ?: throw IOException("expected SessionOk")
            serverNonce = ok.nonce
        }

        val secret = PairingCrypto.sharedSecret(identity.keyPair.private, peer.publicKey)
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
    private suspend fun exchange(channel: SecureChannel, peer: Peer, clientTurn: Boolean) {
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
            channel.send(encodeMsg(Msg.Ack(theirBatch.sentAt)))
            expect<Msg.Ack>(channel.receive())
        } else {
            val theirReq = expect<Msg.SyncRequest>(channel.receive())
            channel.send(encodeMsg(Msg.Batch(merger.changesSince(theirReq.watermark))))
            channel.send(encodeMsg(Msg.SyncRequest(myWatermark)))
            theirBatch = expect<Msg.Batch>(channel.receive()).batch
            merger.apply(theirBatch, lastSyncAt = myWatermark)
            expect<Msg.Ack>(channel.receive())
            channel.send(encodeMsg(Msg.Ack(theirBatch.sentAt)))
        }
        // Both acks seen - only now does the watermark advance.
        sync.setWatermark(SyncState(peer.deviceId, theirBatch.sentAt))
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
