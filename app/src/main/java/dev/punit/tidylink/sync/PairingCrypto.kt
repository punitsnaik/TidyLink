package dev.punit.tidylink.sync

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters

/**
 * X25519 key agreement + HKDF session-key derivation for device pairing.
 *
 * Desktop's copy of this file uses `java.security`'s "XDH" provider directly
 * (plain JDK, no dependency needed there). Android's own JCE provider only
 * gained XDH/X25519 support at API 33 - minSdk here is 29 - so this port
 * uses Bouncy Castle's lightweight crypto API for the actual scalar
 * multiplication instead. It is NOT a different protocol: [publicBytes] and
 * [DeviceKeyPair.pkcs8] still produce the exact RFC 8410 X.509/PKCS8 DER
 * bytes `java.security`'s XDH `KeyFactory` produces on the desktop side, via
 * the fixed prefixes below, so a public key generated here decodes
 * correctly on a Mac and vice versa. Never log or embed key material in
 * exceptions.
 */
object PairingCrypto {

    private const val HASH_LEN = 32 // SHA-256 output
    private const val RAW_KEY_LEN = 32 // X25519 scalar/point length

    // RFC 8410 encodes an X25519 key as a fixed 12-byte (public) or 16-byte
    // (private) DER prefix followed by the raw 32 bytes - no other field
    // varies, so this is a constant, not a template needing an ASN.1 lib.
    private val X509_PREFIX = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03, 0x21, 0x00,
    )
    private val PKCS8_PREFIX = byteArrayOf(
        0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x04, 0x22, 0x04, 0x20,
    )

    /** A device's X25519 keypair. [x509]/[pkcs8] are the wire/storage encodings. */
    class DeviceKeyPair(private val params: X25519PrivateKeyParameters) {
        val rawPrivate: ByteArray = params.encoded
        val rawPublic: ByteArray = params.generatePublicKey().encoded
        val x509: ByteArray get() = X509_PREFIX + rawPublic
        val pkcs8: ByteArray get() = PKCS8_PREFIX + rawPrivate
    }

    fun generateKeyPair(): DeviceKeyPair =
        DeviceKeyPair(X25519PrivateKeyParameters(SecureRandom()))

    /** Reconstructs a [DeviceKeyPair] from the PKCS8 bytes [loadOrCreate][dev.punit.tidylink.sync.DeviceIdentity] persisted. */
    fun keyPairFromPkcs8(pkcs8: ByteArray): DeviceKeyPair {
        require(pkcs8.size == PKCS8_PREFIX.size + RAW_KEY_LEN) { "malformed X25519 PKCS8 key" }
        val raw = pkcs8.copyOfRange(PKCS8_PREFIX.size, pkcs8.size)
        return DeviceKeyPair(X25519PrivateKeyParameters(raw, 0))
    }

    /** X.509 SubjectPublicKeyInfo encoding - the wire/storage form. */
    fun publicBytes(kp: DeviceKeyPair): ByteArray = kp.x509

    /**
     * [peerPublicX509] is the X.509-encoded public key as received on the
     * wire or read from a [dev.punit.tidylink.data.local.PeerEntity].
     */
    fun sharedSecret(ownPrivate: DeviceKeyPair, peerPublicX509: ByteArray): ByteArray {
        require(peerPublicX509.size == X509_PREFIX.size + RAW_KEY_LEN) { "malformed X25519 X.509 public key" }
        val rawPeerPublic = peerPublicX509.copyOfRange(X509_PREFIX.size, peerPublicX509.size)
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(ownPrivate.rawPrivate, 0))
        val out = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(rawPeerPublic, 0), out, 0)
        return out
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256")
            .apply { init(SecretKeySpec(key, "HmacSHA256")) }
            .doFinal(data)

    /** RFC 5869 HKDF (extract + expand) with HMAC-SHA256. */
    fun hkdf(secret: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * HASH_LEN) { "invalid HKDF output length" }
        val prk = hmac(if (salt.isEmpty()) ByteArray(HASH_LEN) else salt, secret)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1 // RFC 5869: counter byte starts at 0x01
        while (pos < length) {
            t = hmac(prk, t + info + byteArrayOf(counter.toByte()))
            val n = minOf(t.size, length - pos)
            t.copyInto(out, pos, 0, n)
            pos += n
            counter++
        }
        return out
    }

    /**
     * Derives the two directional 32-byte session keys from the X25519 shared
     * secret and both handshake nonces. Returns (keyAtoB, keyBtoA).
     */
    fun sessionKeys(secret: ByteArray, nonceA: ByteArray, nonceB: ByteArray): Pair<ByteArray, ByteArray> {
        val salt = nonceA + nonceB
        return hkdf(secret, salt, "tidylink-v1-a2b".toByteArray(), HASH_LEN) to
            hkdf(secret, salt, "tidylink-v1-b2a".toByteArray(), HASH_LEN)
    }
}
