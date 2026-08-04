package dev.punit.tidylink.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The URL join that replaced Retrofit's baseUrl-plus-relative-path
 * resolution. Providers are user-pasted, so this is the piece most likely to
 * break quietly - a wrong join is a 404 that reads like a bad API key.
 */
class ChatEndpointTest {

    @Test
    fun `trailing slash base url joins without doubling`() {
        assertEquals(
            "https://api.x.ai/v1/chat/completions",
            chatEndpoint("https://api.x.ai/v1/"),
        )
    }

    @Test
    fun `missing trailing slash still joins correctly`() {
        // Retrofit rejected these outright; stored providers are sanitized to
        // carry the slash, but nothing should depend on that.
        assertEquals(
            "https://api.x.ai/v1/chat/completions",
            chatEndpoint("https://api.x.ai/v1"),
        )
    }

    @Test
    fun `the gemini openai-compat path survives intact`() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            chatEndpoint("https://generativelanguage.googleapis.com/v1beta/openai/"),
        )
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals(
            "https://api.x.ai/v1/chat/completions",
            chatEndpoint("  https://api.x.ai/v1/  "),
        )
    }

    @Test
    fun `host-only base url gets the path`() {
        assertEquals(
            "https://example.com/chat/completions",
            chatEndpoint("https://example.com"),
        )
    }

    @Test
    fun `an existing port is preserved`() {
        assertEquals(
            "https://example.com:8443/v1/chat/completions",
            chatEndpoint("https://example.com:8443/v1/"),
        )
    }

    @Test
    fun `blank base url is rejected rather than joined`() {
        assertNull(chatEndpoint(""))
        assertNull(chatEndpoint("   "))
    }

    @Test
    fun `a base url with no scheme is rejected`() {
        // LlmProviderStore.sanitized() adds https://, so reaching here means
        // something upstream skipped it - fail loudly rather than build junk.
        assertNull(chatEndpoint("api.x.ai/v1"))
    }

    @Test
    fun `a non-http scheme is rejected`() {
        assertNull(chatEndpoint("ftp://example.com/v1/"))
    }
}
