package dev.punit.tidylink.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Defensive extraction of JSON from LLM chat output. Free models don't all
 * support forced-JSON responses, so markdown fences and chatter around the
 * payload must be tolerated. Pure functions - unit-tested on the JVM.
 */
internal object LlmTextParsing {

    /** Cuts the outermost {...} object out of [raw], stripping code fences. */
    fun extractJson(raw: String): String = extractBetween(raw, '{', '}')

    /** Like [extractJson] but for the [...] array a batch response returns. */
    fun extractJsonArray(raw: String): String = extractBetween(raw, '[', ']')

    /**
     * First [open] to last [close] of the unfenced text, or the unfenced text
     * itself when there is no such span - a caller decoding it then fails and
     * falls back, which is the same outcome as returning junk.
     */
    private fun extractBetween(raw: String, open: Char, close: Char): String {
        val unfenced = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        var lastValid: String? = null
        var start = unfenced.indexOf(open)
        while (start >= 0) {
            val candidate = balancedValueAt(unfenced, start, open, close)
            if (candidate == null) {
                start = unfenced.indexOf(open, start + 1)
                continue
            }
            val parsed = runCatching { Json.parseToJsonElement(candidate) }.getOrNull()
            if (parsed?.isStrictJson() == true &&
                ((parsed is JsonObject && open == '{') || (parsed is JsonArray && open == '['))
            ) {
                lastValid = candidate
                start = unfenced.indexOf(open, start + candidate.length)
            } else {
                start = unfenced.indexOf(open, start + 1)
            }
        }
        return lastValid ?: unfenced
    }

    private fun JsonElement.isStrictJson(): Boolean = when (this) {
        is JsonObject -> values.all { it.isStrictJson() }
        is JsonArray -> all { it.isStrictJson() }
        JsonNull -> true
        is JsonPrimitive -> isString || content == "true" || content == "false" || content.toBigDecimalOrNull() != null
    }

    private fun balancedValueAt(text: String, start: Int, open: Char, close: Char): String? {
        var depth = 0
        var quoted = false
        var escaped = false
        for (i in start until text.length) {
            val char = text[i]
            if (quoted) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> quoted = false
                }
                continue
            }
            when (char) {
                '"' -> quoted = true
                open -> depth++
                close -> if (--depth == 0) return text.substring(start, i + 1)
            }
        }
        return null
    }
}
