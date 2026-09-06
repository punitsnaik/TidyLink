package dev.punit.tidylink.data.scraper

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

internal const val MAX_RELATED_CANDIDATES = 32
internal const val MAX_USEFUL_LINKS = 8
internal const val RELATION_CACHE_VERSION = 2
// Canonical prefixes are shared with the DAO: SQLite JSON extensions aren't required.
internal const val CURRENT_RELATION_CACHE_PREFIX = "{\"version\":2,%"
internal const val NO_AI_RELATION_CACHE_PREFIX = "{\"version\":2,\"aiAttempted\":false,%"

@Serializable
internal data class RelationCache(
    val version: Int = RELATION_CACHE_VERSION,
    val aiAttempted: Boolean = false,
    val fingerprint: String = "",
    val links: List<RelatedLink> = emptyList(),
)

private val cacheJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

internal fun decodeRelationCache(raw: String): RelationCache? = runCatching {
    cacheJson.decodeFromString<RelationCache>(raw).takeIf { it.version == RELATION_CACHE_VERSION }
}.getOrNull()

internal fun encodeRelationCache(cache: RelationCache): String = cacheJson.encodeToString(cache)

internal fun mergeRelationCaches(raw: List<String>): String {
    val caches = raw.mapNotNull(::decodeRelationCache)
    if (caches.isEmpty()) return raw.firstOrNull { it != "[]" } ?: "[]"
    return encodeRelationCache(RelationCache(
        aiAttempted = caches.any { it.aiAttempted },
        links = caches.flatMap { it.links }.distinctBy { it.dedupeKey }.take(MAX_USEFUL_LINKS),
    ))
}

/** A failed attempt is cached too; explicit refresh retries, list rendering never spends quota. */
internal suspend fun resolveRelationCache(
    previous: String,
    data: ScrapedData,
    configured: Boolean,
    force: Boolean = false,
    select: suspend (ScrapedData) -> List<RelatedLink>?,
): String {
    val cached = decodeRelationCache(previous)
    if (!data.fetched && cached != null) {
        return encodeRelationCache(cached.copy(aiAttempted = configured || cached.aiAttempted))
    }
    val fingerprint = relationFingerprint(data)
    if (!force && cached?.fingerprint == fingerprint && (!configured || cached.aiAttempted)) return previous
    val fallback = conservativeRelatedLinks(data)
    val candidates = (data.relatedLinks + fallback).distinctBy { it.dedupeKey }.take(MAX_RELATED_CANDIDATES)
    val selected = if (configured && candidates.isNotEmpty()) select(data.copy(relatedLinks = candidates)) else null
    return encodeRelationCache(RelationCache(
        aiAttempted = configured,
        fingerprint = fingerprint,
        links = (selected?.ifEmpty { fallback } ?: fallback).map { it.copy(context = "") },
    ))
}

internal fun relationFingerprint(data: ScrapedData): String {
    val input = listOf(data.url, data.resolvedUrl, data.title, data.description,
        cacheJson.encodeToString(data.relatedLinks)).joinToString("\n")
    return MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
