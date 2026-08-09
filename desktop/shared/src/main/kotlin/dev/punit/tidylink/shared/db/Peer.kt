package dev.punit.tidylink.shared.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A paired device. [publicKey] is the peer's static X25519 public key (X.509 bytes, BLOB). */
@Entity(tableName = "peers")
data class Peer(
    @PrimaryKey val deviceId: String,
    val name: String,
    val publicKey: ByteArray,
    val addedAt: Long,
) {
    // ByteArray compares by reference in a data class; content equality is what callers expect.
    override fun equals(other: Any?): Boolean =
        other is Peer &&
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
