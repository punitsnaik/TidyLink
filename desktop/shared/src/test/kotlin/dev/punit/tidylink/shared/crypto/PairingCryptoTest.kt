package dev.punit.tidylink.shared.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PairingCryptoTest {

    @Test
    fun shared_secret_is_equal_in_both_directions() {
        val a = PairingCrypto.generateKeyPair()
        val b = PairingCrypto.generateKeyPair()
        val ab = PairingCrypto.sharedSecret(a.private, PairingCrypto.publicBytes(b))
        val ba = PairingCrypto.sharedSecret(b.private, PairingCrypto.publicBytes(a))
        assertContentEquals(ab, ba)
        assertEquals(32, ab.size)
    }

    @Test
    fun different_peers_produce_different_secrets() {
        val a = PairingCrypto.generateKeyPair()
        val b = PairingCrypto.generateKeyPair()
        val c = PairingCrypto.generateKeyPair()
        val ab = PairingCrypto.sharedSecret(a.private, PairingCrypto.publicBytes(b))
        val ac = PairingCrypto.sharedSecret(a.private, PairingCrypto.publicBytes(c))
        assertFalse(ab.contentEquals(ac))
    }

    @Test
    fun hkdf_is_deterministic_and_length_correct() {
        val secret = ByteArray(32) { it.toByte() }
        val salt = byteArrayOf(1, 2, 3)
        val info = "test".toByteArray()
        for (length in listOf(1, 16, 32, 33, 64, 100)) {
            val one = PairingCrypto.hkdf(secret, salt, info, length)
            val two = PairingCrypto.hkdf(secret, salt, info, length)
            assertEquals(length, one.size)
            assertContentEquals(one, two)
        }
    }

    @Test
    fun hkdf_matches_rfc5869_test_case_1() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { it.toByte() }
        val info = ByteArray(10) { (0xf0 + it).toByte() }
        val okm = PairingCrypto.hkdf(ikm, salt, info, 42)
        val expected = (
            "3cb25f25faacd57a90434f64d0362f2a" +
                "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865"
            ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertContentEquals(expected, okm)
    }

    @Test
    fun session_keys_are_deterministic_direction_distinct_and_32_bytes() {
        val secret = ByteArray(32) { (it * 7).toByte() }
        val nonceA = ByteArray(16) { 1 }
        val nonceB = ByteArray(16) { 2 }
        val (a2b, b2a) = PairingCrypto.sessionKeys(secret, nonceA, nonceB)
        val (a2b2, b2a2) = PairingCrypto.sessionKeys(secret, nonceA, nonceB)
        assertEquals(32, a2b.size)
        assertEquals(32, b2a.size)
        assertFalse(a2b.contentEquals(b2a))
        assertContentEquals(a2b, a2b2)
        assertContentEquals(b2a, b2a2)
        // different nonces = different keys
        val (a2bOther, _) = PairingCrypto.sessionKeys(secret, nonceB, nonceA)
        assertFalse(a2b.contentEquals(a2bOther))
    }

    @Test
    fun hmac_is_deterministic_sha256_sized() {
        val key = ByteArray(32) { 5 }
        val mac = PairingCrypto.hmac(key, "hello".toByteArray())
        assertEquals(32, mac.size)
        assertContentEquals(mac, PairingCrypto.hmac(key, "hello".toByteArray()))
        assertFalse(mac.contentEquals(PairingCrypto.hmac(key, "hellp".toByteArray())))
    }
}
