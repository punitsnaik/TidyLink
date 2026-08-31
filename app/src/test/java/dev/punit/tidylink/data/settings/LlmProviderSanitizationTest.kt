package dev.punit.tidylink.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class LlmProviderSanitizationTest {

    @Test
    fun `uppercase scheme is normalized instead of duplicated`() {
        val provider = LlmProvider(
            name = "Test",
            baseUrl = "HTTPS://api.example.com/v1",
            model = " model ",
            apiKey = " key ",
        ).sanitized()

        assertEquals("https://api.example.com/v1/", provider.baseUrl)
        assertEquals("model", provider.model)
        assertEquals("key", provider.apiKey)
    }

    @Test
    fun `uppercase http scheme stays http`() {
        val provider = LlmProvider(
            name = "Test",
            baseUrl = "HTTP://localhost:11434/v1",
            model = "model",
            apiKey = "",
        ).sanitized()

        assertEquals("http://localhost:11434/v1/", provider.baseUrl)
    }
}
