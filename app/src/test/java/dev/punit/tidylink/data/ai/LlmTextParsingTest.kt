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

    @Test
    fun `object extraction stops at its matching brace`() {
        val raw = "Answer: {\"text\": \"a } inside a string\"} trailing {noise}"
        assertEquals("{\"text\": \"a } inside a string\"}", LlmTextParsing.extractJson(raw))
    }

    @Test
    fun `array extraction ignores brackets inside strings`() {
        val raw = "Answer: [{\"text\": \"a ] inside a string\"}] trailing [noise]"
        assertEquals("[{\"text\": \"a ] inside a string\"}]", LlmTextParsing.extractJsonArray(raw))
    }

    @Test
    fun `escaped quotes do not expose delimiters inside strings`() {
        val raw = "Answer: {\"text\":\"quoted \\\"} still text\"} trailing {noise}"
        assertEquals("{\"text\":\"quoted \\\"} still text\"}", LlmTextParsing.extractJson(raw))
    }

    @Test
    fun `invalid brace chatter before the payload is skipped`() {
        val raw = "Use {category: text}. Result: {\"category\":\"Recipes\"}"
        assertEquals("{\"category\":\"Recipes\"}", LlmTextParsing.extractJson(raw))
    }

    @Test
    fun `unbalanced bracket chatter before the payload is skipped`() {
        val raw = "Items [below. Result: [{\"index\":0}]"
        assertEquals("[{\"index\":0}]", LlmTextParsing.extractJsonArray(raw))
    }

    @Test
    fun `last valid object wins when a model echoes an example first`() {
        val raw = "Example: {\"category\":\"Example\"}. Answer: {\"category\":\"Recipes\"}"
        assertEquals("{\"category\":\"Recipes\"}", LlmTextParsing.extractJson(raw))
    }

    @Test
    fun `last valid array wins when a model echoes an example first`() {
        assertEquals("[2]", LlmTextParsing.extractJsonArray("Example: [1]. Answer: [2]"))
    }

    @Test
    fun `non json primitives are skipped`() {
        assertEquals("[1]", LlmTextParsing.extractJsonArray("Invalid: [NaN]. Answer: [1]"))
    }
}
