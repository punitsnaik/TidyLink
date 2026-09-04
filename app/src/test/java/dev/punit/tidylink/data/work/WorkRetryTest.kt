package dev.punit.tidylink.data.work

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkRetryTest {

    @Test
    fun `three maximum attempts means two retries after the first run`() {
        assertTrue(shouldRetry(runAttemptCount = 0, maxAttempts = 3))
        assertTrue(shouldRetry(runAttemptCount = 1, maxAttempts = 3))
        assertFalse(shouldRetry(runAttemptCount = 2, maxAttempts = 3))
    }

    @Test
    fun `save work names do not collide for Java hash collisions`() {
        val first = "https://example.com/Aa"
        val second = "https://example.com/BB"
        assertTrue(first.hashCode() == second.hashCode())
        assertNotEquals(saveWorkName(first), saveWorkName(second))
    }

    @Test
    fun `backup names are unique and rotation only owns exact backup names`() {
        assertNotEquals(backupFileName(1_000L), backupFileName(1_001L))
        assertNotEquals(backupFileName(1_000L), backupFileName(1_000L))
        assertTrue(isOwnedBackupName(backupFileName(1_000L)))
        assertFalse(isOwnedBackupName("tidylink-pending-123.json"))
        assertTrue(isOwnedBackupName("tidylink-backup-2026-09-03.json"))
        assertTrue(isOwnedBackupName("tidylink-backup-2026-09-03T102030123.json"))
        assertFalse(isOwnedBackupName("tidylink-backup-keep-forever.json"))
        assertFalse(isOwnedBackupName("tidylink-backup-2026-09-03-copy.json"))
    }
}
