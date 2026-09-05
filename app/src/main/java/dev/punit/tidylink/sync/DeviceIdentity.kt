package dev.punit.tidylink.sync

import android.content.Context
import android.os.Build
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * This device's stable sync identity: a UUID, a human-readable name, and a
 * long-lived X25519 keypair. Same shape as desktop/shared's
 * `identity/DeviceIdentity.kt`, adapted for Android storage and for
 * [PairingCrypto]'s Bouncy-Castle-backed [PairingCrypto.DeviceKeyPair]
 * (Android has no platform XDH provider below API 33 - see that file).
 *
 * ponytail: key file lives in app-private storage (already sandboxed by the
 * OS - no other app can read `context.getDir(...)`), not Android Keystore.
 * Keystore's `KeyPairGenerator` only gained XDH/X25519 support on newer API
 * levels than this app's minSdk 29 supports uniformly, so it isn't usable
 * here without a version-gated fallback - the same gap PairingCrypto works
 * around with Bouncy Castle. Upgrade path if this ever matters: wrap the raw
 * key bytes with a Keystore-backed AES key instead of storing them in the
 * clear (Keystore AES has been available since API 23).
 */
class DeviceIdentity(val deviceId: String, val name: String, val keyPair: PairingCrypto.DeviceKeyPair) {

    companion object {
        private const val DIR = "sync-identity"

        fun loadOrCreate(context: Context): DeviceIdentity {
            val dir = context.getDir(DIR, Context.MODE_PRIVATE)
            val idFile = java.io.File(dir, "identity.json")
            val keyFile = java.io.File(dir, "key.p8")

            if (idFile.exists() && keyFile.exists()) {
                val meta = Json.decodeFromString<IdentityMeta>(idFile.readText())
                return DeviceIdentity(meta.deviceId, meta.name, PairingCrypto.keyPairFromPkcs8(keyFile.readBytes()))
            }

            val keyPair = PairingCrypto.generateKeyPair()
            val deviceId = UUID.randomUUID().toString()
            val name = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"

            keyFile.writeBytes(keyPair.pkcs8)
            idFile.writeText(Json.encodeToString(IdentityMeta.serializer(), IdentityMeta(deviceId, name)))
            return DeviceIdentity(deviceId, name, keyPair)
        }
    }
}

@Serializable
private data class IdentityMeta(val deviceId: String, val name: String)
