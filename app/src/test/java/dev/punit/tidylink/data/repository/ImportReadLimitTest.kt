package dev.punit.tidylink.data.repository

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImportReadLimitTest {

    @Test
    fun `capped reader accepts the limit and rejects the next byte`() {
        assertEquals("1234", ByteArrayInputStream("1234".toByteArray()).readTextCapped(4))
        assertNull(ByteArrayInputStream("12345".toByteArray()).readTextCapped(4))
    }
}
