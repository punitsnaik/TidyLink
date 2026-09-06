package dev.punit.tidylink.data.api

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class UrlSafetyServiceTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `deserializes urlhaus response for malicious url`() {
        val body = """
            {
                "query_status": "ok",
                "id": "12345",
                "url_status": "online",
                "threat": "malware_download",
                "tags": ["elf", "mozi"],
                "urlhaus_reference": "https://urlhaus.abuse.ch/url/12345/"
            }
        """.trimIndent()

        val parsed = json.decodeFromString<UrlhausResponse>(body)
        assertEquals("ok", parsed.query_status)
        assertEquals("malware_download", parsed.threat)
        assertEquals(listOf("elf", "mozi"), parsed.tags)
        assertEquals("https://urlhaus.abuse.ch/url/12345/", parsed.urlhaus_reference)
    }

    @Test
    fun `deserializes urlhaus response for clean url`() {
        val body = """
            {
                "query_status": "no_results"
            }
        """.trimIndent()

        val parsed = json.decodeFromString<UrlhausResponse>(body)
        assertEquals("no_results", parsed.query_status)
        assertEquals(null, parsed.threat)
    }
}
