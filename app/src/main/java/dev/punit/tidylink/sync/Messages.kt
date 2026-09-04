package dev.punit.tidylink.sync

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.Base64
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * Ported from desktop/shared's `sync/Messages.kt` per that file's own
 * instruction to the Android implementer. Field names, @SerialName values
 * and frame shape are a wire contract - do not rename anything here without
 * changing both sides at once.
 */

/** ByteArray as Base64 text in JSON - keys, nonces and MACs on the wire. */
internal object Base64Bytes : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Base64Bytes", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ByteArray) =
        encoder.encodeString(Base64.getEncoder().encodeToString(value))

    override fun deserialize(decoder: Decoder): ByteArray =
        Base64.getDecoder().decode(decoder.decodeString())
}

internal typealias B64 = @Serializable(with = Base64Bytes::class) ByteArray

@Serializable
internal sealed interface Msg {

    // Pairing (first contact, token from the QR):

    /** mac = HMAC(token, deviceId(utf8) || publicKey || nonce). */
    @Serializable
    @SerialName("PairHello")
    data class PairHello(
        val deviceId: String,
        val name: String,
        val publicKey: B64,
        val nonce: B64,
        val mac: B64,
    ) : Msg

    /** mac = HMAC(token, deviceId(utf8) || publicKey || nonce || clientNonce). */
    @Serializable
    @SerialName("PairOk")
    data class PairOk(
        val deviceId: String,
        val name: String,
        val publicKey: B64,
        val nonce: B64,
        val mac: B64,
    ) : Msg

    // Reconnect:

    @Serializable
    @SerialName("SessionHello")
    data class SessionHello(val deviceId: String, val nonce: B64) : Msg

    @Serializable
    @SerialName("SessionOk")
    data class SessionOk(val nonce: B64) : Msg

    // After the channel is encrypted:

    @Serializable
    @SerialName("SyncRequest")
    data class SyncRequest(val watermark: Long) : Msg

    @Serializable
    @SerialName("Batch")
    data class Batch(val batch: SyncBatch) : Msg

    @Serializable
    @SerialName("Ack")
    data class Ack(val newWatermark: Long) : Msg
}

private val wireJson = Json { ignoreUnknownKeys = true }
private val msgSerializer = serializer<Msg>()

internal fun encodeMsg(msg: Msg): ByteArray =
    wireJson.encodeToString(msgSerializer, msg).encodeToByteArray()

internal fun decodeMsg(bytes: ByteArray): Msg =
    wireJson.decodeFromString(msgSerializer, bytes.decodeToString())

/** Handshake messages are tiny; anything bigger is a broken or hostile peer. */
private const val MAX_PLAIN_FRAME = 64 * 1024

/**
 * PRE-encryption framing: Int32 BE length + UTF-8 JSON - the same length
 * prefix SecureChannel uses, minus the nonce and encryption. Exactly one
 * flush per frame.
 */
internal fun DataOutputStream.writeMsgFrame(msg: Msg) {
    val bytes = encodeMsg(msg)
    writeInt(bytes.size)
    write(bytes)
    flush()
}

internal fun DataInputStream.readMsgFrame(): Msg {
    val length = readInt()
    if (length < 0 || length > MAX_PLAIN_FRAME) throw IOException("invalid frame length: $length")
    return decodeMsg(ByteArray(length).also(::readFully))
}
