package dev.punit.tidylink.data.ai

/**
 * Defensive extraction of JSON from LLM chat output. Free models don't all
 * support forced-JSON responses, so markdown fences and chatter around the
 * payload must be tolerated. Pure functions - unit-tested on the JVM.
 */
internal object LlmTextParsing {

    /** Cuts the first {...} object out of [raw], stripping code fences. */
    fun extractJson(raw: String): String {
        val unfenced = unfence(raw)
        val start = unfenced.indexOf('{')
        val end = unfenced.lastIndexOf('}')
        return if (start in 0 until end) unfenced.substring(start, end + 1) else unfenced
    }

    /** Like [extractJson] but for the [...] array a batch response returns. */
    fun extractJsonArray(raw: String): String {
        val unfenced = unfence(raw)
        val start = unfenced.indexOf('[')
        val end = unfenced.lastIndexOf(']')
        return if (start in 0 until end) unfenced.substring(start, end + 1) else unfenced
    }

    private fun unfence(raw: String): String = raw.trim()
        .removePrefix("```json").removePrefix("```")
        .removeSuffix("```")
        .trim()
}
