package dev.punit.tidylink.data.repository

import android.content.Context
import androidx.paging.PagingSource
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.punit.tidylink.data.UrlCanonicalizer
import dev.punit.tidylink.data.ai.AiCategorizationService
import dev.punit.tidylink.data.local.CategoryCount
import dev.punit.tidylink.data.local.LinkDao
import dev.punit.tidylink.data.local.LinkEntity
import dev.punit.tidylink.data.local.LinkQueryBuilder
import dev.punit.tidylink.data.local.SortOrder
import dev.punit.tidylink.data.scraper.LinkScraperService
import dev.punit.tidylink.data.scraper.ScrapedData
import dev.punit.tidylink.data.work.ClassificationRetryWorker
import dev.punit.tidylink.data.work.EnrichmentSweepWorker
import dev.punit.tidylink.data.work.LinkEnrichmentWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/** Result of a bulk URL import (enrichment continues in the background). */
data class ImportSummary(val imported: Int, val duplicates: Int)

/** Result of saving a single URL. */
data class SaveResult(val link: LinkEntity, val alreadyExisted: Boolean)

/**
 * Result of a refresh pass. [unclassified] links failed AI categorization;
 * [aiUnavailable] distinguishes "no provider configured" (nothing will retry,
 * the user must add a key) from a transient rate limit.
 */
data class RefreshSummary(
    val refreshed: Int,
    val unclassified: Int,
    val aiUnavailable: Boolean = false,
)

/** Result of a category tidy-up pass. */
data class TidySummary(val merged: Int, val aiUnavailable: Boolean)

class LinkRepository(
    private val linkDao: LinkDao,
    private val scraper: LinkScraperService,
    private val aiService: AiCategorizationService,
    private val appContext: Context,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    /**
     * Serializes duplicate-check + placeholder insert, so two near-simultaneous
     * saves of the same URL (e.g. double-share) can't both pass the check and
     * create two rows.
     */
    private val saveMutex = Mutex()

    /**
     * Serializes enrichment sweeps: the manual Refresh and the background
     * sweep worker share the same pipeline and must not double-scrape.
     */
    private val sweepMutex = Mutex()

    /** Paged, SQL-side filtered and sorted view of the library. */
    fun pagingSource(
        searchQuery: String,
        category: String?,
        sort: SortOrder,
    ): PagingSource<Int, LinkEntity> =
        linkDao.pagingSource(LinkQueryBuilder.build(searchQuery, category, sort))

    fun getCategories(): Flow<List<CategoryCount>> = linkDao.getCategories()

    /** Links still awaiting their first scrape — drives the progress banner. */
    fun pendingEnrichmentCount(): Flow<Int> = linkDao.countNeverScraped()

    /**
     * True when links are still waiting for their first scrape — checked at
     * app start so an interrupted sweep (process killed mid-import, or rows
     * upgraded from a pre-v3 schema) is resumed instead of leaving the
     * "fetching details" banner up with no worker behind it.
     */
    suspend fun hasPendingEnrichment(): Boolean = linkDao.countNeverScrapedOnce() > 0

    /** Live view of a single link — keeps the detail sheet current. */
    fun observeLink(id: String): Flow<LinkEntity?> = linkDao.observeById(id)

    suspend fun getLinksByIds(ids: List<String>): List<LinkEntity> = linkDao.getByIds(ids)

    suspend fun deleteLink(id: String) = linkDao.delete(id)

    suspend fun deleteLinks(ids: List<String>) = linkDao.deleteByIds(ids)

    /** Used by undo-delete: puts previously deleted entities back. */
    suspend fun restoreLinks(links: List<LinkEntity>) =
        linkDao.upsertAll(links.map { it.withDedupeKey() })

    /** Manual edit from the UI: title / category / tags. */
    suspend fun updateLinkDetails(
        link: LinkEntity,
        title: String,
        category: String,
        tags: List<String>,
    ): LinkEntity {
        val updated = link.copy(
            title = title.trim().ifBlank { link.title },
            category = category.trim()
                .ifBlank { FALLBACK_CATEGORY }
                .let { resolveCategory(it, allCategoryNames()) },
            tags = tags.map { it.trim().removePrefix("#").lowercase() }
                .filter { it.isNotBlank() }
                .distinct(),
        )
        linkDao.upsert(updated)
        return updated
    }

    /** Bulk recategorization from multi-select mode. */
    suspend fun moveToCategory(ids: List<String>, category: String) {
        val target = category.trim()
        if (ids.isEmpty() || target.isBlank()) return
        linkDao.moveToCategory(ids, resolveCategory(target, allCategoryNames()))
    }

    suspend fun setPinned(id: String, pinned: Boolean) = linkDao.setPinned(id, pinned)

    /**
     * One-time upgrade helper (run at app start): fills the indexed
     * dedupeKey for rows saved before the column existed.
     */
    suspend fun backfillDedupeKeys() {
        val missing = linkDao.getMissingDedupeKeys()
        if (missing.isEmpty()) return
        linkDao.upsertAll(missing.map { it.copy(dedupeKey = UrlCanonicalizer.dedupeKey(it.url)) })
    }

    private fun LinkEntity.withDedupeKey(): LinkEntity =
        if (dedupeKey.isNotBlank()) this else copy(dedupeKey = UrlCanonicalizer.dedupeKey(url))

    /**
     * Full pipeline: Check DB -> Save placeholder -> Scrape -> AI Classify
     * -> Update. The placeholder row is persisted BEFORE any network work,
     * so the link survives even if the app is killed mid-pipeline; a
     * WorkManager job is enqueued as a safety net and cancelled once the
     * inline enrichment succeeds. Idempotent: saving an existing URL (in any
     * tracking-param/www/scheme variant) returns the existing row.
     */
    suspend fun processAndSaveUrl(rawUrl: String): SaveResult {
        val url = UrlCanonicalizer.cleanUrl(rawUrl)

        // 1. Check-and-insert under a mutex so concurrent saves can't race.
        val placeholder = saveMutex.withLock {
            findExistingByUrl(url)?.let { return SaveResult(it, alreadyExisted = true) }
            LinkEntity(
                url = url,
                title = UrlCanonicalizer.placeholderTitle(url),
                description = "",
                imageUrl = null,
                category = FALLBACK_CATEGORY,
                tags = emptyList(),
                aiSummary = "",
                dedupeKey = UrlCanonicalizer.dedupeKey(url),
            ).also { linkDao.upsert(it) }
        }

        // 2. Safety net: completes scrape/classify if we die mid-way.
        scheduleEnrichment(placeholder.id)

        // 3. Enrich inline; on failure the worker finishes the job later.
        val enriched = try {
            enrich(placeholder)
        } catch (e: Exception) {
            return SaveResult(placeholder, alreadyExisted = false)
        }
        WorkManager.getInstance(appContext)
            .cancelUniqueWork(enrichWorkName(placeholder.id))
        return SaveResult(enriched, alreadyExisted = false)
    }

    /** Scrape + classify + update for a placeholder row. Never downgrades. */
    private suspend fun enrich(entity: LinkEntity): LinkEntity {
        val scraped = scraper.scrapeMetadata(entity.url)
        val existing = allCategoryNames()
        val classification =
            aiService.classify(scraped, existing.take(MAX_PROMPT_CATEGORIES))
        if (classification == null) scheduleClassificationRetry()

        val updated = entity.copy(
            title = if (scraped.isRich) scraped.title else entity.title,
            description = scraped.description.ifBlank { entity.description },
            imageUrl = scraped.imageUrl ?: entity.imageUrl,
            category = classification?.category?.takeIf { it.isNotBlank() }
                ?.let { resolveCategory(it, existing) } ?: entity.category,
            tags = classification?.tags?.takeIf { it.isNotEmpty() } ?: entity.tags,
            // A blank summary would leave the row matching getClassifyCandidates
            // (aiSummary = '') forever, re-classifying it on every sweep.
            aiSummary = classification?.aiSummary?.takeIf { it.isNotBlank() }
                ?: scraped.description.ifBlank { entity.aiSummary },
            scrapeAttempts = entity.scrapeAttempts + 1,
        )
        linkDao.upsert(updated)
        return updated
    }

    /**
     * Called by [LinkEnrichmentWorker]: finishes the pipeline for a link
     * whose inline enrichment was interrupted. No-op when the link was
     * already enriched (or deleted in the meantime).
     */
    suspend fun completePendingEnrichment(id: String) {
        val link = linkDao.getById(id) ?: return
        val incomplete = link.scrapeAttempts == 0 ||
            link.category == FALLBACK_CATEGORY ||
            link.aiSummary.isBlank()
        if (incomplete) enrich(link)
    }

    private fun scheduleEnrichment(linkId: String) {
        val request = OneTimeWorkRequestBuilder<LinkEnrichmentWorker>()
            .setInputData(workDataOf(LinkEnrichmentWorker.KEY_LINK_ID to linkId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            // Give the inline pipeline time to win before the net fires.
            .setInitialDelay(20, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            enrichWorkName(linkId),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private fun enrichWorkName(id: String) = "enrich_link_$id"

    /**
     * Re-runs scrape + classification for an existing link and updates it in
     * place (same id, same saved-at timestamp). Never downgrades: keeps the
     * old value wherever the new attempt came back empty.
     */
    suspend fun refreshLink(existing: LinkEntity): LinkEntity {
        val scraped = scraper.scrapeMetadata(existing.url)
        val categories = allCategoryNames()
        val classification = aiService.classify(
            if (scraped.isRich) scraped else scrapedFromEntity(existing),
            categories.take(MAX_PROMPT_CATEGORIES),
        )
        if (classification == null) scheduleClassificationRetry()

        val updated = existing.copy(
            title = if (scraped.isRich) scraped.title else existing.title,
            description = scraped.description.ifBlank { existing.description },
            imageUrl = scraped.imageUrl ?: existing.imageUrl,
            category = classification?.category?.takeIf { it.isNotBlank() }
                ?.let { resolveCategory(it, categories) } ?: existing.category,
            // takeIf: an empty tag list / summary is the model saying nothing,
            // not saying "remove what you had" — never downgrade on it.
            tags = classification?.tags?.takeIf { it.isNotEmpty() } ?: existing.tags,
            aiSummary = classification?.aiSummary?.takeIf { it.isNotBlank() }
                ?: existing.aiSummary,
            scrapeAttempts = existing.scrapeAttempts + 1,
        )
        linkDao.upsert(updated)
        return updated
    }

    /**
     * Bulk import: canonicalizes and de-duplicates the URLs, inserts all new
     * ones as placeholders in a single write, then hands scraping and
     * classification to [EnrichmentSweepWorker] — so a 1,000-link import
     * survives the user leaving the app, Doze, and process death.
     */
    suspend fun importUrls(rawUrls: List<String>): ImportSummary {
        val cleaned = rawUrls.map { UrlCanonicalizer.cleanUrl(it) }
            .distinctBy { UrlCanonicalizer.dedupeKey(it) }
        val existingKeys = linkDao.getAllDedupeKeys().toHashSet()
        // Legacy rows may not be indexed yet (backfill still running).
        if (linkDao.countMissingDedupeKeys() > 0) {
            existingKeys += linkDao.getAllOnce().map { UrlCanonicalizer.dedupeKey(it.url) }
        }
        val newUrls = cleaned.filterNot { UrlCanonicalizer.dedupeKey(it) in existingKeys }
        val duplicates = cleaned.size - newUrls.size
        if (newUrls.isEmpty()) return ImportSummary(0, duplicates)

        linkDao.upsertAll(
            newUrls.map { url ->
                LinkEntity(
                    url = url,
                    title = UrlCanonicalizer.placeholderTitle(url),
                    description = "",
                    imageUrl = null,
                    category = FALLBACK_CATEGORY,
                    tags = emptyList(),
                    aiSummary = "",
                    dedupeKey = UrlCanonicalizer.dedupeKey(url),
                )
            }
        )
        EnrichmentSweepWorker.enqueue(appContext)
        return ImportSummary(newUrls.size, duplicates)
    }

    /**
     * Enrichment sweep, used by both the manual Refresh and
     * [EnrichmentSweepWorker]: merges duplicate rows, scrapes every link
     * that still needs it (never scraped, or image-less below the attempt
     * cap), then classifies everything scraped-but-uncategorized. Writes are
     * batched so the UI isn't invalidated once per link.
     */
    suspend fun refreshUnfetched(): RefreshSummary = sweepMutex.withLock {
        mergeDuplicates()

        val toScrape = linkDao.getScrapeCandidates(MAX_SCRAPE_ATTEMPTS)
        coroutineScope {
            val semaphore = Semaphore(SCRAPE_CONCURRENCY)
            toScrape.chunked(WRITE_BATCH_SIZE).forEach { chunk ->
                val scrapedChunk = chunk.map { existing ->
                    async {
                        semaphore.withPermit { existing to scraper.scrapeMetadata(existing.url) }
                    }
                }.awaitAll()
                linkDao.upsertAll(
                    scrapedChunk.map { (existing, data) ->
                        existing.copy(
                            title = if (data.isRich) data.title else existing.title,
                            description = data.description.ifBlank { existing.description },
                            imageUrl = data.imageUrl ?: existing.imageUrl,
                            scrapeAttempts = existing.scrapeAttempts + 1,
                        )
                    }
                )
            }
        }

        val toClassify = linkDao.getClassifyCandidates(FALLBACK_CATEGORY)
        val unclassified = classifyInBatches(toClassify)
        if (unclassified > 0) scheduleClassificationRetry()

        val touched = (toScrape.map { it.id } + toClassify.map { it.id }).toSet().size
        RefreshSummary(
            refreshed = touched,
            unclassified = unclassified,
            aiUnavailable = unclassified > 0 && !aiService.isConfigured(),
        )
    }

    /**
     * Classifies [links] in batches of [CLASSIFY_BATCH_SIZE] per LLM call,
     * with one batched DB write and one category lookup per chunk. Returns
     * how many links could not be classified.
     */
    private suspend fun classifyInBatches(links: List<LinkEntity>): Int {
        var failed = 0
        links.chunked(CLASSIFY_BATCH_SIZE).forEach { chunk ->
            val categories = allCategoryNames()
            val results = aiService.classifyBatch(
                chunk.map { scrapedFromEntity(it) },
                categories.take(MAX_PROMPT_CATEGORIES),
            )
            val updated = mutableListOf<LinkEntity>()
            chunk.zip(results).forEach { (link, classification) ->
                if (classification != null) {
                    updated += link.copy(
                        // Same never-downgrade rule as enrich/refreshLink: an
                        // empty field means "no answer", not "clear it".
                        category = classification.category.takeIf { it.isNotBlank() }
                            ?.let { resolveCategory(it, categories) } ?: link.category,
                        tags = classification.tags.takeIf { it.isNotEmpty() } ?: link.tags,
                        aiSummary = classification.aiSummary.takeIf { it.isNotBlank() }
                            ?: link.aiSummary,
                    )
                } else {
                    failed++
                }
            }
            if (updated.isNotEmpty()) linkDao.upsertAll(updated)
        }
        return failed
    }

    /**
     * Merges rows that are the same page saved under URL variants (tracking
     * params, www/no-www, http/https). Keeps the richest row, fills any gaps
     * from the others, and deletes the rest.
     */
    private suspend fun mergeDuplicates() {
        linkDao.getAllOnce().groupBy { UrlCanonicalizer.dedupeKey(it.url) }.values.forEach { group ->
            if (group.size <= 1) return@forEach
            val best = group.maxWithOrNull(
                compareBy(
                    { it.category != FALLBACK_CATEGORY },
                    { it.imageUrl != null },
                    { it.aiSummary.isNotBlank() },
                    { -it.timestamp }, // tie-break: prefer the earliest save
                )
            ) ?: return@forEach
            val merged = best.copy(
                imageUrl = best.imageUrl ?: group.firstNotNullOfOrNull { it.imageUrl },
                description = best.description.ifBlank {
                    group.firstOrNull { it.description.isNotBlank() }?.description.orEmpty()
                },
                aiSummary = best.aiSummary.ifBlank {
                    group.firstOrNull { it.aiSummary.isNotBlank() }?.aiSummary.orEmpty()
                },
                timestamp = group.minOf { it.timestamp },
                pinned = group.any { it.pinned },
            ).withDedupeKey()
            linkDao.upsert(merged)
            linkDao.deleteByIds(group.filter { it.id != best.id }.map { it.id })
        }
    }

    /**
     * One-shot cleanup for a category list that has grown messy, in two
     * passes:
     *  1. OFFLINE: merges spelling variants (case/punctuation/plural) —
     *     always works, no network or quota needed.
     *  2. AI: when more than [MAX_CATEGORIES] remain, asks the LLM for a
     *     semantic merge mapping ("Movies"/"Film") and renames in bulk.
     */
    suspend fun consolidateCategories(): TidySummary {
        // Pass 1: offline variant merge.
        var merged = mergeCategoryVariants()

        // Pass 2: semantic merge via the LLM, only if still sprawling.
        val counts = linkDao.getCategoriesOnce().filter { it.category != FALLBACK_CATEGORY }
        if (counts.size <= MAX_CATEGORIES) return TidySummary(merged, aiUnavailable = false)

        val mapping = aiService.consolidateCategories(
            categories = counts.map { it.category to it.count },
            maxCategories = MAX_CATEGORIES,
        ) ?: return TidySummary(merged, aiUnavailable = true)

        val validOld = counts.map { it.category }.toSet()
        mapping.forEach { (old, new) ->
            val target = new.trim()
            if (old in validOld && target.isNotBlank() &&
                target != old && old != FALLBACK_CATEGORY
            ) {
                linkDao.renameCategory(old, target)
                merged++
            }
        }
        return TidySummary(merged, aiUnavailable = false)
    }

    /**
     * Merges categories that are the same words in disguise — "Ai tool",
     * "AI Tools", "ai-tools" — into whichever variant has the most links.
     * Pure string matching; needs no AI quota.
     */
    private suspend fun mergeCategoryVariants(): Int {
        val counts = linkDao.getCategoriesOnce().filter { it.category != FALLBACK_CATEGORY }
        var merged = 0
        counts.groupBy { CategoryNames.key(it.category) }.values.forEach { group ->
            if (group.size <= 1) return@forEach
            val canonical = group.maxByOrNull { it.count }?.category ?: return@forEach
            group.forEach { variant ->
                if (variant.category != canonical) {
                    linkDao.renameCategory(variant.category, canonical)
                    merged++
                }
            }
        }
        return merged
    }

    /**
     * Called by [ClassificationRetryWorker]: re-classifies every link stuck in
     * the fallback category using its stored metadata (no re-scrape needed).
     * Returns how many links still failed, so the worker can decide to retry.
     */
    suspend fun retryPendingClassifications(): Int {
        val pending = linkDao.getByCategory(FALLBACK_CATEGORY)
        if (pending.isEmpty()) return 0
        return classifyInBatches(pending)
    }

    // --- Export / import ---------------------------------------------------

    /**
     * Streams the whole library as JSON to [stream] — avoids building a
     * second full-library String in memory on top of the entity list.
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun exportLinks(stream: OutputStream) {
        val links = linkDao.getAllOnce()
        withContext(Dispatchers.IO) {
            json.encodeToStream(links, stream)
        }
    }

    /** Returns the number of imported links, or -1 if the JSON was invalid. */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun importLinks(stream: InputStream): Int = try {
        val links = withContext(Dispatchers.IO) {
            json.decodeFromStream<List<LinkEntity>>(stream)
        }
        // Older backups predate the dedupeKey column — compute it on the way in.
        linkDao.upsertAll(links.map { it.withDedupeKey() })
        links.size
    } catch (e: Exception) {
        -1
    }

    // ------------------------------------------------------------------------

    /**
     * Queues a retry for links the LLM couldn't categorize.
     *
     * No-op when no provider is configured: classification then fails not
     * because of a transient rate limit but because there is nothing to call,
     * so a retry chain (5 attempts, backing off to 8h, each waking the device
     * on a network constraint) could only ever burn battery. Running without
     * an API key is a supported mode — the UI offers to add one via a banner.
     */
    private fun scheduleClassificationRetry() {
        if (!aiService.isConfigured()) return
        val request = OneTimeWorkRequestBuilder<ClassificationRetryWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            // Free-tier quotas are often per-day, so back off in large steps:
            // 30m, 1h, 2h, 4h, 8h — enough to reach the next quota reset.
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            RETRY_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private fun scrapedFromEntity(link: LinkEntity) = ScrapedData(
        url = link.url,
        title = link.title,
        description = link.description,
        imageUrl = link.imageUrl,
    )

    /** Duplicate check that also catches tracking-param / www / scheme variants. */
    private suspend fun findExistingByUrl(cleanedUrl: String): LinkEntity? {
        linkDao.getByUrl(cleanedUrl)?.let { return it }
        val key = UrlCanonicalizer.dedupeKey(cleanedUrl)
        linkDao.getByDedupeKey(key)?.let { return it }
        // Legacy rows may not be indexed yet (backfill still running):
        // fall back to the old table scan only while any remain.
        if (linkDao.countMissingDedupeKeys() > 0) {
            return linkDao.getAllOnce().firstOrNull { UrlCanonicalizer.dedupeKey(it.url) == key }
        }
        return null
    }

    /** Current taxonomy (busiest first), excluding the fallback bucket. */
    private suspend fun allCategoryNames(): List<String> =
        linkDao.getCategoriesOnce()
            .map { it.category }
            .filter { it != FALLBACK_CATEGORY }

    /**
     * Snaps an LLM-returned category onto an existing one when they differ
     * only by case, punctuation, spacing, or a trailing plural — the main
     * source of near-duplicate categories. [existingCategories] is passed in
     * (rather than queried here) so batch callers pay for one lookup per
     * chunk instead of one per link.
     */
    private fun resolveCategory(raw: String, existingCategories: List<String>): String {
        val cleaned = raw.trim()
        if (cleaned.isBlank()) return FALLBACK_CATEGORY
        val key = CategoryNames.key(cleaned)
        val existing = existingCategories.firstOrNull { CategoryNames.key(it) == key }
        return existing ?: CategoryNames.titleCase(cleaned)
    }

    companion object {
        const val FALLBACK_CATEGORY = "Uncategorized"
        private const val RETRY_WORK_NAME = "classification_retry"
        private const val SCRAPE_CONCURRENCY = 4
        private const val CLASSIFY_BATCH_SIZE = 8

        /** One DB write (and one list invalidation) per this many scrapes. */
        private const val WRITE_BATCH_SIZE = 8

        /** Re-scrape ceiling for links that never get a thumbnail. */
        private const val MAX_SCRAPE_ATTEMPTS = 3

        /** Target ceiling for the taxonomy when consolidating. */
        private const val MAX_CATEGORIES = 12

        /** How many existing categories to include in classification prompts. */
        private const val MAX_PROMPT_CATEGORIES = 30
    }
}
