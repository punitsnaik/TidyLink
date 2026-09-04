package dev.punit.tidylink.data.ai

import dev.punit.tidylink.data.scraper.MAX_USEFUL_LINKS
import dev.punit.tidylink.data.scraper.RelatedLink
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Select existing objects by index only. Never accept AI-generated destinations or labels. */
internal fun parseRelatedLinkSelection(raw: String, candidates: List<RelatedLink>): List<RelatedLink>? = runCatching {
    val values = Json.parseToJsonElement(LlmTextParsing.extractJsonArray(raw)) as? JsonArray
        ?: return null
    val indices = values.map {
        val value = it as? JsonPrimitive ?: return null
        if (value.isString) return null
        val index = value.intOrNull ?: return null
        if (index !in candidates.indices) return null
        index
    }
    indices.distinct().take(MAX_USEFUL_LINKS).map(candidates::get)
}.getOrNull()
