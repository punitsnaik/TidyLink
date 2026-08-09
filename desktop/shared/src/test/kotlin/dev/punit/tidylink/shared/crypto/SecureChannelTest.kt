package dev.punit.tidylink.shared.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class SecureChannelTest {

    private val keyAtoB = ByteArray(32) { 1 }
    private val keyBtoA = ByteArray(32) { 2 }

    /** Two channels wired to each other over piped streams, A's send = B's receive. */
    private fun pipedPair(): Pair<SecureChannel, SecureChannel> {
        val aToB = PipedInputStream(1 shl 16)
        val aOut = PipedOutputStream(aToB)
        val bToA = PipedInputStream(1 shl 16)
        val bOut = PipedOutputStream(bToA)
        val a = SecureChannel(bToA, aOut, sendKey = keyAtoB, recvKey = keyBtoA)
        val b = SecureChannel(aToB, bOut, sendKey = keyBtoA, recvKey = keyAtoB)
        return a to b
    }

    @Test
    fun round_trips_in_both_directions() {
        val (a, b) = pipedPair()
        val msg1 = "hello from A".toByteArray()
        val msg2 = "reply from B, with some more bytes éü".toByteArray()
        a.send(msg1)
        assertContentEquals(msg1, b.receive())
        b.send(msg2)
        assertContentEquals(msg2, a.receive())
    }

    @Test
    fun consecutive_messages_arrive_in_order() {
        val (a, b) = pipedPair()
        val messages = listOf(ByteArray(0), byteArrayOf(42), ByteArray(5000) { it.toByte() })
        messages.forEach { a.send(it) }
        messages.forEach { assertContentEquals(it, b.receive()) }
    }

    @Test
    fun tampered_ciphertext_throws() {
        val wire = ByteArrayOutputStream()
        SecureChannel(ByteArrayInputStream(ByteArray(0)), wire, keyAtoB, keyBtoA)
            .send("do not tamper".toByteArray())
        val bytes = wire.toByteArray()
        bytes[4 + 12] = (bytes[4 + 12].toInt() xor 0x01).toByte() // first ciphertext byte
        val receiver = SecureChannel(
            ByteArrayInputStream(bytes), ByteArrayOutputStream(),
            sendKey = keyBtoA, recvKey = keyAtoB,
        )
        assertFailsWith<AEADBadTagException> { receiver.receive() }
    }

    @Test
    fun wrong_key_throws() {
        val wire = ByteArrayOutputStream()
        SecureChannel(ByteArrayInputStream(ByteArray(0)), wire, keyAtoB, keyBtoA)
            .send("secret".toByteArray())
        val receiver = SecureChannel(
            ByteArrayInputStream(wire.toByteArray()), ByteArrayOutputStream(),
            sendKey = keyBtoA, recvKey = ByteArray(32) { 9 },
        )
        assertFailsWith<AEADBadTagException> { receiver.receive() }
    }

    @Test
    fun oversized_frame_is_rejected() {
        val wire = ByteArrayOutputStream()
        DataOutputStream(wire).writeInt(9 * 1024 * 1024)
        val receiver = SecureChannel(
            ByteArrayInputStream(wire.toByteArray()), ByteArrayOutputStream(),
            sendKey = keyAtoB, recvKey = keyBtoA,
        )
        assertFailsWith<IOException> { receiver.receive() }
    }

    @Test
    fun undersized_frame_is_rejected() {
        val wire = ByteArrayOutputStream()
        DataOutputStream(wire).writeInt(4) // smaller than nonce + GCM tag
        val receiver = SecureChannel(
            ByteArrayInputStream(wire.toByteArray()), ByteArrayOutputStream(),
            sendKey = keyAtoB, recvKey = keyBtoA,
        )
        assertFailsWith<IOException> { receiver.receive() }
    }
}
