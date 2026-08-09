package dev.punit.tidylink.shared.identity

import dev.punit.tidylink.shared.crypto.PairingCrypto
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * This device's stable sync identity: a UUID, a human-readable name, and a
 * long-lived X25519 keypair, persisted in [loadOrCreate]'s directory as
 * identity.json + key.p8 (raw PKCS8, POSIX 600) + pub.x509.
 *
 * ponytail: key file not macOS Keychain - Keychain when packaging polish happens.
 */
class DeviceIdentity(val deviceId: String, val name: String, val keyPair: KeyPair) {

    companion object {
        fun loadOrCreate(dir: Path): DeviceIdentity {
            Files.createDirectories(dir)
            val idFile = dir.resolve("identity.json")
            val keyFile = dir.resolve("key.p8")
            val pubFile = dir.resolve("pub.x509")

            if (Files.exists(idFile) && Files.exists(keyFile) && Files.exists(pubFile)) {
                val meta = Json.decodeFromString<IdentityMeta>(Files.readString(idFile))
                val factory = KeyFactory.getInstance("XDH")
                val priv = factory.generatePrivate(PKCS8EncodedKeySpec(Files.readAllBytes(keyFile)))
                val pub = factory.generatePublic(X509EncodedKeySpec(Files.readAllBytes(pubFile)))
                return DeviceIdentity(meta.deviceId, meta.name, KeyPair(pub, priv))
            }

            val keyPair = PairingCrypto.generateKeyPair()
            val deviceId = UUID.randomUUID().toString()
            val name = runCatching { InetAddress.getLocalHost().hostName }
                .getOrNull()?.takeIf { it.isNotBlank() } ?: "Mac"

            Files.write(keyFile, keyPair.private.encoded)
            try {
                Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"))
            } catch (_: UnsupportedOperationException) {
                // non-POSIX filesystem - nothing to tighten
            }
            Files.write(pubFile, keyPair.public.encoded)
            Files.writeString(idFile, Json.encodeToString(IdentityMeta(deviceId, name)))
            return DeviceIdentity(deviceId, name, keyPair)
        }
    }
}

@Serializable
private data class IdentityMeta(val deviceId: String, val name: String)
