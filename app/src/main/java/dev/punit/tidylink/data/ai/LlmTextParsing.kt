package dev.punit.tidylink.data.ai

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
        val start = unfenced.indexOf(open)
        val end = unfenced.lastIndexOf(close)
        return if (start in 0 until end) unfenced.substring(start, end + 1) else unfenced
    }
}
