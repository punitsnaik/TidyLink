package dev.punit.tidylink.shared.crypto

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.spec.NamedParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * X25519 key agreement + HKDF session-key derivation for device pairing.
 * Pure JDK crypto - no dependencies. Never log or embed key material in
 * exception messages.
 */
object PairingCrypto {

    private const val HASH_LEN = 32 // SHA-256 output

    fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("XDH")
            .apply { initialize(NamedParameterSpec("X25519")) }
            .generateKeyPair()

    /** X.509 SubjectPublicKeyInfo encoding, the JDK default for public keys. */
    fun publicBytes(kp: KeyPair): ByteArray = kp.public.encoded

    fun sharedSecret(priv: PrivateKey, peerPublic: ByteArray): ByteArray {
        val peer = KeyFactory.getInstance("XDH")
            .generatePublic(X509EncodedKeySpec(peerPublic))
        return KeyAgreement.getInstance("XDH")
            .apply { init(priv); doPhase(peer, true) }
            .generateSecret()
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
