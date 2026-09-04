package dev.punit.tidylink.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A paired device (schema v9, sync). [publicKey] is the peer's static X25519
 * public key (X.509 SubjectPublicKeyInfo bytes). Mirrors desktop/shared's
 * `Peer` table exactly - the two must stay wire-compatible.
 */
@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val deviceId: String,
    val name: String,
    val publicKey: ByteArray,
    val addedAt: Long,
) {
    // ByteArray compares by reference in a data class; content equality is
    // what callers (and tests) expect.
    override fun equals(other: Any?): Boolean =
        other is PeerEntity &&
            deviceId == other.deviceId &&
            name == other.name &&
            publicKey.contentEquals(other.publicKey) &&
            addedAt == other.addedAt

    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + addedAt.hashCode()
        return result
    }
}
