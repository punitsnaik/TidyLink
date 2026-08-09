package dev.punit.tidylink.shared.identity

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdentityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun loadOrCreate_persists_and_reloads_identical_identity() {
        val dir = tmp.newFolder().toPath()
        val first = DeviceIdentity.loadOrCreate(dir)
        val second = DeviceIdentity.loadOrCreate(dir)
        assertEquals(first.deviceId, second.deviceId)
        assertEquals(first.name, second.name)
        assertContentEquals(first.keyPair.private.encoded, second.keyPair.private.encoded)
        assertContentEquals(first.keyPair.public.encoded, second.keyPair.public.encoded)
        assertTrue(first.deviceId.isNotBlank())
        assertTrue(first.name.isNotBlank())
        assertTrue(Files.exists(dir.resolve("identity.json")))
        assertTrue(Files.exists(dir.resolve("key.p8")))
        assertTrue(Files.exists(dir.resolve("pub.x509")))
    }

    @Test
    fun key_file_is_owner_only_on_posix() {
        val dir = tmp.newFolder().toPath()
        assumeTrue(Files.getFileStore(dir).supportsFileAttributeView("posix"))
        DeviceIdentity.loadOrCreate(dir)
        val perms = Files.getPosixFilePermissions(dir.resolve("key.p8"))
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            perms,
        )
    }

    @Test
    fun creates_missing_directory() {
        val dir = tmp.newFolder().toPath().resolve("nested").resolve("identity")
        val id = DeviceIdentity.loadOrCreate(dir)
        assertEquals(id.deviceId, DeviceIdentity.loadOrCreate(dir).deviceId)
    }
}
