package dev.punit.tidylink.shared.crypto

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-GCM encrypted message framing over a byte stream.
 *
 * Frame: 4-byte big-endian length (covering everything after it), then a
 * 12-byte random nonce, then the GCM ciphertext (plaintext + 16-byte tag).
 * One key per direction; a fresh random nonce per message.
 *
 * [receive] throws [javax.crypto.AEADBadTagException] on any tampered or
 * wrong-key frame, and [IOException] on a malformed length.
 */
class SecureChannel(
    input: InputStream,
    output: OutputStream,
    sendKey: ByteArray,
    recvKey: ByteArray,
) {
    private val dataIn = DataInputStream(input)
    private val dataOut = DataOutputStream(output)
    private val sendSpec = SecretKeySpec(sendKey, "AES")
    private val recvSpec = SecretKeySpec(recvKey, "AES")
    private val random = SecureRandom()

    fun send(plaintext: ByteArray) {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, sendSpec, GCMParameterSpec(TAG_BITS, nonce))
        val ciphertext = cipher.doFinal(plaintext)
        dataOut.writeInt(NONCE_BYTES + ciphertext.size)
        dataOut.write(nonce)
        dataOut.write(ciphertext)
        dataOut.flush()
    }

    fun receive(): ByteArray {
        val length = dataIn.readInt()
        if (length < NONCE_BYTES + TAG_BITS / 8 || length > MAX_FRAME_BYTES) {
            throw IOException("invalid frame length: $length")
        }
        val nonce = ByteArray(NONCE_BYTES).also(dataIn::readFully)
        val ciphertext = ByteArray(length - NONCE_BYTES).also(dataIn::readFully)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, recvSpec, GCMParameterSpec(TAG_BITS, nonce))
        return cipher.doFinal(ciphertext)
    }

    private companion object {
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        const val MAX_FRAME_BYTES = 8 * 1024 * 1024 // sanity bound
    }
}
