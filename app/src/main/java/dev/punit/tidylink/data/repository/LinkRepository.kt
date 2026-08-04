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
import dev.punit.tidylink.data.importer.BookmarkHtmlParser
import dev.punit.tidylink.data.local.CategoryCount
import dev.punit.tidylink.data.local.LinkDao
import dev.punit.tidylink.data.local.LinkEntity
import dev.punit.tidylink.data.local.LinkQueryBuilder
import dev.punit.tidylink.data.local.SortOrder
import dev.punit.tidylink.data.local.TrashedLinkEntity
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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

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

/** Result of importing a browser bookmarks export. */
data class BookmarkImportSummary(val imported: Int, val skipped: Int)

/** A trashed link, decoded back into an entity, with when it was deleted. */
data class TrashedLink(val link: LinkEntity, val deletedAt: Long)

/** The chosen file was too large to read safely - see MAX_IMPORT_BYTES. */
class ImportTooLargeException : Exception()

/**
 * Collapses one group of duplicate rows into the single row that should
 * survive, or null when the group holds nothing to merge.
 *
 * Deliberately a merge and not a "keep one, drop the rest": the duplicates
 * are the same page saved at different times, so one copy may have the
 * image, another the AI summary, and a third the pin. Picking a winner
 * outright would silently throw away whichever field the loser held.
 *
 * Pure and file-level rather than a method so the rule can be tested
 * directly - it decides which rows get deleted, which is not something to
 * verify only by running it against a real library.
 */
internal fun mergeDuplicateGroup(group: List<LinkEntity>): LinkEntity? {
    if (group.size <= 1) return null
    // Richest row wins: a real category beats the fallback, an image beats
    // none, a summary beats none. Ties go to the earliest save.
    val best = group.maxWithOrNull(
        compareBy(
            { it.category != LinkRepository.FALLBACK_CATEGORY },
            { it.imageUrl != null },
            { it.aiSummary.isNotBlank() },
            { -it.timestamp },
        )
    ) ?: return null
    return best.copy(
        imageUrl = best.imageUrl ?: group.firstNotNullOfOrNull { it.imageUrl },
        description = best.description.ifBlank {
            group.firstOrNull { it.description.isNotBlank() }?.description.orEmpty()
        },
        aiSummary = best.aiSummary.ifBlank {
            group.firstOrNull { it.aiSummary.isNotBlank() }?.aiSummary.orEmpty()
        },
        // The link has been in the library since its earliest save, and a
        // pin on any copy is an explicit user action - neither survives
        // "keep the newest".
        timestamp = group.minOf { it.timestamp },
        pinned = group.any { it.pinned },
    ).let { if (it.dedupeKey.isNotBlank()) it else it.copy(dedupeKey = UrlCanonicalizer.dedupeKey(it.url)) }
}

/**
 * What to write back after a thumbnail-recovery scrape, or null to leave
 * the row untouched. Pure so the never-downgrade rule is unit-testable
 * without a fake DAO - same reasoning as [mergeDuplicateGroup].
 *
 * Null [scraped] is the offline case and MUST leave [existing] alone.
 * That is the whole safety argument for recovering thumbnails from a
 * failed image load: a tunnel or airplane mode fires the load-failure
 * callback for every visible card at once, and if this ever returned
 * null-as-a-value it would erase a screenful of good URLs.
 */
internal fun recoveredImageUrl(existing: String?, scraped: String?): String? =
    scraped?.takeIf { it.isNotBlank() && it != existing }

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

    /**
     * Caps concurrent thumbnail recoveries. These are triggered by cards
     * rendering, so flinging past a screenful of broken images would
     * otherwise fire a scrape per card at once.
     */
    private val recoverySemaphore = Semaphore(RECOVERY_CONCURRENCY)

    /** Paged, SQL-side filtered and sorted view of the library. */
    fun pagingSource(
        searchQuery: String,
        category: String?,
        sort: SortOrder,
    ): PagingSource<Int, LinkEntity> =
        linkDao.pagingSource(LinkQueryBuilder.build(searchQuery, category, sort))

    fun getCategories(): Flow<List<CategoryCount>> = linkDao.getCategories()

    /** Paged view of pinned links only - drives the Pinned tab. */
    fun pinnedPagingSource(): PagingSource<Int, LinkEntity> = linkDao.pinnedPagingSource()

    /** Links still awaiting their first scrape - drives the progress banner. */
    fun pendingEnrichmentCount(): Flow<Int> = linkDao.countNeverScraped()

    /** Redundant copies in the library - drives the Tools sheet subtitle. */
    fun duplicateCount(): Flow<Int> = linkDao.countDuplicates()

    /**
     * True when there is anything worth (re-)scraping - checked at app start
     * so an interrupted sweep (process killed mid-import, or rows upgraded
     * from a pre-v3 schema) is resumed instead of leaving the "fetching
     * details" banner up with no worker behind it.
     *
     * Deliberately mirrors [LinkDao.getScrapeCandidates] via
     * [LinkDao.countScrapeCandidates], not "never scraped" alone - a link
     * scraped once that came back with no image has scrapeAttempts = 1 and
     * would never re-open this gate, even though the sweep's own candidate
     * query would still retry it.
     */
    suspend fun hasPendingEnrichment(): Boolean =
        linkDao.countScrapeCandidates(MAX_SCRAPE_ATTEMPTS) > 0

    /** Live view of a single link - keeps the detail sheet current. */
    fun observeLink(id: String): Flow<LinkEntity?> = linkDao.observeById(id)

    // --- Trash ---------------------------------------------------------------

    /**
     * Soft-deletes: rows leave `links` and land in `trashed_links` as
     * serialized JSON, in one transaction.
     *
     * Everything that reads the library therefore excludes trash without
     * asking - search, category tiles, export, backup and the
     * enrichment sweep all query `links` and simply cannot see these rows.
     */
    suspend fun moveToTrash(ids: List<String>) {
        if (ids.isEmpty()) return
        val links = linkDao.getByIds(ids)
        if (links.isEmpty()) return
        val now = System.currentTimeMillis()
        linkDao.moveToTrash(
            rows = links.map {
                TrashedLinkEntity(id = it.id, json = json.encodeToString(it), deletedAt = now)
            },
            ids = links.map { it.id },
        )
    }

    /**
     * The ONE restore path - the undo snackbar and the trash sheet both
     * come through here, so there is no second version to drift out of step.
     *
     * A row that fails to decode is dropped rather than aborting the whole
     * restore: one corrupt entry must not make the other forty-nine
     * unrecoverable.
     */
    suspend fun restoreFromTrash(ids: List<String>): Int {
        if (ids.isEmpty()) return 0
        val trashed = linkDao.getTrashedByIds(ids)
        val links = trashed.mapNotNull { row ->
            runCatching { json.decodeFromString<LinkEntity>(row.json) }.getOrNull()
        }
        if (links.isEmpty()) return 0
        linkDao.restoreFromTrash(links.map { it.withDedupeKey() }, trashed.map { it.id })
        return links.size
    }

    /**
     * Trash contents, already decoded. Decoding happens off the main thread
     * off the main thread on purpose: a `.map` downstream of
     * Room's flow runs in the COLLECTOR's context, and JSON-parsing a few
     * hundred rows there would land on the UI thread.
     */
    fun observeTrash(): Flow<List<TrashedLink>> = linkDao.observeTrash()
        .map { rows ->
            rows.mapNotNull { row ->
                decodeTrashed(row)?.let { TrashedLink(it, row.deletedAt) }
            }
        }
        .flowOn(Dispatchers.Default)

    fun trashCount(): Flow<Int> = linkDao.countTrashed()

    suspend fun deleteFromTrashForever(ids: List<String>) = linkDao.deleteTrashed(ids)

    suspend fun emptyTrash() = linkDao.emptyTrash()

    /** Decodes a trashed row for display; null if it can't be read. */
    fun decodeTrashed(row: TrashedLinkEntity): LinkEntity? =
        runCatching { json.decodeFromString<LinkEntity>(row.json) }.getOrNull()

    /**
     * Called once at app start, next to [backfillDedupeKeys]. A worker
     * would be more machinery than a single DELETE justifies, and trash
     * purged a few hours late harms nobody.
     */
    suspend fun purgeExpiredTrash() =
        linkDao.purgeTrashOlderThan(System.currentTimeMillis() - TRASH_RETENTION_MS)

    /**
     * Deleting from the library means trashing it. Note this is NOT what
     * duplicate merging does - see [mergeDuplicates], which still hard
     * deletes on purpose.
     */
    suspend fun deleteLink(id: String) = moveToTrash(listOf(id))

    suspend fun deleteLinks(ids: List<String>) = moveToTrash(ids)

    /** Manual edit from the UI: title / category / note. */
    suspend fun updateLinkDetails(
        link: LinkEntity,
        title: String,
        category: String,
        note: String = link.note,
    ): LinkEntity {
        val updated = link.copy(
            title = title.trim().ifBlank { link.title },
            category = category.trim()
                .ifBlank { FALLBACK_CATEGORY }
                .let { resolveCategory(it, allCategoryNames()) },
            // Only trimmed, never blank-guarded: clearing a note is a
            // legitimate edit, unlike clearing the title.
            note = note.trim(),
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
            // takeIf: an empty summary is the model saying nothing, not
            // saying "remove what you had" - never downgrade on it.
            aiSummary = classification?.aiSummary?.takeIf { it.isNotBlank() }
                ?: existing.aiSummary,
            scrapeAttempts = existing.scrapeAttempts + 1,
        )
        linkDao.upsert(updated)
        return updated
    }

    /**
     * Re-scrapes one link because its stored thumbnail failed to LOAD.
     *
     * This is the one broken-image case the sweep structurally cannot see.
     * [LinkDao.getScrapeCandidates] retries on `imageUrl IS NULL`, and a
     * dead, expired or hotlink-blocked URL is still a non-null URL - so
     * the row looks finished forever and "Fetch missing details" reports
     * "all up to date" while the card sits blank. Only the image loader
     * knows the URL is bad, so the recovery has to start there.
     *
     * Scrape only, deliberately no classification: this fires from a card
     * appearing on screen, and an LLM call per blank card would be slow
     * and would spend the user's API quota on work already done.
     *
     * Writes nothing unless the scrape produced a DIFFERENT usable URL
     * (see [recoveredImageUrl]), which is what makes it safe offline.
     *
     * `scrapeAttempts` is left alone on purpose: that counter exists to
     * cap the sweep's retries of image-LESS rows, and these rows are not
     * image-less. The caller bounds this instead - one attempt per link
     * per app session.
     */
    suspend fun recoverThumbnail(existing: LinkEntity) {
        val scraped = recoverySemaphore.withPermit { scraper.scrapeMetadata(existing.url) }
        // Re-read BEFORE deciding, not just before writing. The sweep or a
        // manual refresh may have rewritten this row while the scrape was
        // in flight, and comparing against the stale `existing.imageUrl`
        // would judge our result "different" from a URL nobody holds any
        // more - overwriting their fresh one with ours. Comparing against
        // the re-read row closes that: if they already fixed the image, our
        // scrape either agrees (and writes nothing) or loses to the newer
        // value on the next failure, which is the safe direction.
        val current = linkDao.getById(existing.id) ?: return
        val image = recoveredImageUrl(current.imageUrl, scraped.imageUrl) ?: return
        linkDao.upsert(current.copy(imageUrl = image))
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
     *
     * Returns how many rows were removed, so the Tools sheet can report the
     * result of an explicit "merge duplicates" tap. Callers that run this as
     * part of a wider sweep ignore it.
     */
    internal suspend fun mergeDuplicates(): Int {
        var removed = 0
        linkDao.getAllOnce().groupBy { UrlCanonicalizer.dedupeKey(it.url) }.values.forEach { group ->
            val merged = mergeDuplicateGroup(group) ?: return@forEach
            linkDao.upsert(merged)
            val doomed = group.filter { it.id != merged.id }.map { it.id }
            linkDao.deleteByIds(doomed)
            removed += doomed.size
        }
        return removed
    }

    /**
     * One-shot cleanup for a category list that has grown messy, in two
     * passes:
     *  1. OFFLINE: merges spelling variants (case/punctuation/plural) -
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
     * Merges categories that are the same words in disguise - "Ai tool",
     * "AI Tools", "ai-tools" - into whichever variant has the most links.
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
     * Streams the whole library as JSON to [stream] - avoids building a
     * second full-library String in memory on top of the entity list.
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun exportLinks(stream: OutputStream) {
        val links = linkDao.getAllOnce()
        withContext(Dispatchers.IO) {
            json.encodeToStream(links, stream)
        }
    }

    /**
     * Imports a Netscape bookmarks export (Chrome/Firefox/Safari "export
     * bookmarks"). Rows land raw and immediately - browser title and URL
     * only - and the existing enrichment sweep scrapes and classifies them
     * in the background, exactly as it does for links saved by hand. A few
     * hundred rows appearing instantly is the point; waiting on a few
     * hundred network round trips before showing anything is not.
     *
     * [useFoldersAsCategories] maps the deepest enclosing bookmark folder
     * onto the category. Those rows skip AI classification entirely, which
     * is both cheaper and more faithful - it keeps organisation the user
     * already did by hand.
     */
    suspend fun importBookmarks(
        stream: InputStream,
        useFoldersAsCategories: Boolean,
    ): BookmarkImportSummary {
        val html = withContext(Dispatchers.IO) { stream.readTextCapped(MAX_IMPORT_BYTES) }
            ?: throw ImportTooLargeException()

        val parsed = BookmarkHtmlParser.parse(html)
        if (parsed.isEmpty()) return BookmarkImportSummary(imported = 0, skipped = 0)

        // One query rather than one per bookmark. Also seeds the set that
        // dedupes the file against ITSELF - browser exports routinely list
        // the same page in two folders.
        val seenKeys = linkDao.getAllDedupeKeys().toMutableSet()
        val knownCategories = allCategoryNames().toMutableList()
        val now = System.currentTimeMillis()
        var skipped = 0

        val rows = parsed.mapNotNull { bookmark ->
            val url = UrlCanonicalizer.cleanUrl(bookmark.url)
            val key = UrlCanonicalizer.dedupeKey(url)
            if (!seenKeys.add(key)) {
                skipped++
                return@mapNotNull null
            }
            val category = bookmark.folder
                ?.takeIf { useFoldersAsCategories && it.isNotBlank() }
                ?.let { folder ->
                    resolveCategory(folder, knownCategories).also { resolved ->
                        // Fold later folders onto categories this same
                        // import already created, not just onto pre-existing
                        // ones - otherwise "Dev" and "dev" both survive.
                        if (resolved !in knownCategories) knownCategories += resolved
                    }
                }
                ?: FALLBACK_CATEGORY
            LinkEntity(
                url = url,
                title = bookmark.title.ifBlank { UrlCanonicalizer.placeholderTitle(url) },
                description = "",
                imageUrl = null,
                category = category,
                aiSummary = "",
                // Keep the bookmark's real age when the export carries one,
                // so an import doesn't dump a decade of links onto today and
                // bury everything the user actually saved recently.
                timestamp = bookmark.addedAtMillis ?: now,
                dedupeKey = key,
            )
        }

        if (rows.isNotEmpty()) {
            linkDao.upsertAll(rows)
            // Survives the user leaving the app, Doze and process death -
            // which a ViewModel-scoped loop over 400 links would not.
            EnrichmentSweepWorker.enqueue(appContext)
        }
        return BookmarkImportSummary(imported = rows.size, skipped = skipped)
    }

    /**
     * Reads the whole stream as UTF-8, or null if it exceeds [maxBytes].
     *
     * The cap is not only about memory. [BookmarkHtmlParser] matches
     * `<A ...>...</A>` with a lazy group, which degrades to O(n^2) on a file
     * full of unclosed anchors, so bounding the input also bounds the worst
     * case parse time on a hostile or corrupt file.
     */
    private fun InputStream.readTextCapped(maxBytes: Int): String? {
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = read(chunk)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            out.write(chunk, 0, read)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** Returns the number of imported links, or -1 if the JSON was invalid. */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun importLinks(stream: InputStream): Int = try {
        val links = withContext(Dispatchers.IO) {
            json.decodeFromStream<List<LinkEntity>>(stream)
        }
        // Older backups predate the dedupeKey column - compute it on the way in.
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
     * an API key is a supported mode - the UI offers to add one via a banner.
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
            // 30m, 1h, 2h, 4h, 8h - enough to reach the next quota reset.
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
            return linkDao.getMissingDedupeKeys().firstOrNull { UrlCanonicalizer.dedupeKey(it.url) == key }
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
     * only by case, punctuation, spacing, or a trailing plural - the main
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

        /**
         * Lower than [SCRAPE_CONCURRENCY]: recoveries are triggered by
         * scrolling, so they compete with the UI rather than running in a
         * worker the user is waiting on.
         */
        private const val RECOVERY_CONCURRENCY = 2
        private const val CLASSIFY_BATCH_SIZE = 8

        /** One DB write (and one list invalidation) per this many scrapes. */
        private const val WRITE_BATCH_SIZE = 8

        /**
         * Re-scrape ceiling for links that never get a thumbnail. Raised
         * from 3 to 6 - since there is no separate reset mechanism, raising
         * the cap doubles as the reset for rows already stuck at the old
         * ceiling: a row at scrapeAttempts = 3 is under the new cap, so it
         * becomes a scrape candidate again with no migration, no one-time
         * flag, and no bookkeeping.
         */
        private const val MAX_SCRAPE_ATTEMPTS = 6

        /** Target ceiling for the taxonomy when consolidating. */
        private const val MAX_CATEGORIES = 12

        /** How many existing categories to include in classification prompts. */
        private const val MAX_PROMPT_CATEGORIES = 30

        /**
         * Ceiling on an imported bookmarks file. A real export of several
         * thousand bookmarks is well under a megabyte; 8 MB is generous
         * enough to never reject a genuine file while still refusing
         * something that would exhaust memory or stall the parser.
         */
        private const val MAX_IMPORT_BYTES = 8 * 1024 * 1024

        /**
         * How long a deleted link stays recoverable. Internal, not private,
         * so the trash sheet counts down against this exact value instead
         * of keeping its own copy to drift out of step.
         */
        internal const val TRASH_RETENTION_DAYS = 90L
        private const val TRASH_RETENTION_MS = TRASH_RETENTION_DAYS * 24 * 60 * 60 * 1000
    }
}
