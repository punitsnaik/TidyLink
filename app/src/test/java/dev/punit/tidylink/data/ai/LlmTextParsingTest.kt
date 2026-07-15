package dev.punit.tidylink.data.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class LlmTextParsingTest {

    @Test
    fun `plain json object passes through`() {
        assertEquals("""{"a":1}""", LlmTextParsing.extractJson("""{"a":1}"""))
    }

    @Test
    fun `markdown fences are stripped`() {
        val raw = "```json\n{\"category\": \"Recipes\"}\n```"
        assertEquals("{\"category\": \"Recipes\"}", LlmTextParsing.extractJson(raw))
    }

    @Test
    fun `chatter around the object is cut away`() {
        val raw = "Sure! Here is your JSON:\n{\"a\": 1}\nHope that helps."
        assertEquals("{\"a\": 1}", LlmTextParsing.extractJson(raw))
    }

    @Test
    fun `nested braces keep the outermost object`() {
        val raw = "prefix {\"a\": {\"b\": 2}} suffix"
        assertEquals("{\"a\": {\"b\": 2}}", LlmTextParsing.extractJson(raw))
    }

    @Test
    fun `no braces returns trimmed input unchanged`() {
        assertEquals("no json here", LlmTextParsing.extractJson("  no json here  "))
    }

    @Test
    fun `array extraction strips fences and chatter`() {
        val raw = "```json\n[{\"index\": 0}]\n```"
        assertEquals("[{\"index\": 0}]", LlmTextParsing.extractJsonArray(raw))
    }

    @Test
    fun `array chatter is cut away`() {
        val raw = "Here you go: [1, 2, 3] - done!"
        assertEquals("[1, 2, 3]", LlmTextParsing.extractJsonArray(raw))
    }
}
