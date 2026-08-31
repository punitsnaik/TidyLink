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
}
