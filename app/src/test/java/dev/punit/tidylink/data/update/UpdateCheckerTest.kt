package dev.punit.tidylink.data.update

import dev.punit.tidylink.data.update.UpdateChecker.Companion.isRemoteNewer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun newer_major_and_minor_win() {
        assertTrue(isRemoteNewer("2.0", "1.9"))
        assertTrue(isRemoteNewer("1.1", "1.0"))
        assertTrue(isRemoteNewer("1.10", "1.9")) // numeric, not lexicographic
    }

    @Test
    fun equal_and_older_do_not() {
        assertFalse(isRemoteNewer("1.0", "1.0"))
        assertFalse(isRemoteNewer("1.0", "1.1"))
        assertFalse(isRemoteNewer("0.9", "1.0"))
    }

    @Test
    fun different_segment_counts_compare_correctly() {
        assertTrue(isRemoteNewer("1.0.1", "1.0"))
        assertFalse(isRemoteNewer("1.0", "1.0.1"))
        assertFalse(isRemoteNewer("1.0.0", "1.0")) // trailing zeros are equal
    }

    @Test
    fun malformed_remote_never_looks_newer() {
        assertFalse(isRemoteNewer("beta", "1.0"))
        assertFalse(isRemoteNewer("", "1.0"))
        assertFalse(isRemoteNewer("x.y", "1.0"))
    }
}
