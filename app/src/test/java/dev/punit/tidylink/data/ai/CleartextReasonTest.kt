package dev.punit.tidylink.data.ai

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * minSdk 34 blocks cleartext, so an http:// endpoint is unreachable no matter
 * what. Without this check it fails deep inside OkHttp as a generic "Network
 * error", which reads like a connectivity problem rather than a typo.
 */
class CleartextReasonTest {

    @Test
    fun `http urls are rejected with an actionable reason`() {
        assertNotNull(cleartextReason("http://api.example.com/v1/"))
        // Case-insensitive: schemes are, and users paste anything.
        assertNotNull(cleartextReason("HTTP://api.example.com/v1/"))
        // Leading whitespace survives a paste; sanitized() trims only later.
        assertNotNull(cleartextReason("  http://api.example.com/v1/"))
    }

    @Test
    fun `a LAN LLM server is rejected too, deliberately`() {
        // Ollama / LM Studio. Supporting these needs a network security config
        // allowing cleartext to private ranges - a feature, not a fix. Until
        // then this must fail loudly rather than silently never work.
        assertNotNull(cleartextReason("http://192.168.1.5:11434/v1/"))
        assertNotNull(cleartextReason("http://localhost:1234/v1/"))
    }

    @Test
    fun `https urls pass`() {
        assertNull(cleartextReason("https://api.openai.com/v1/"))
        assertNull(cleartextReason("https://generativelanguage.googleapis.com/v1beta/openai/"))
    }

    @Test
    fun `https is not rejected just for containing the substring http`() {
        assertNull(cleartextReason("https://http.example.com/v1/"))
    }
}
