package dev.punit.tidylink.ui

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dev.punit.tidylink.R
import dev.punit.tidylink.TidyLinkApplication
import dev.punit.tidylink.data.UrlCanonicalizer
import dev.punit.tidylink.data.ai.AiCategorizationService
import dev.punit.tidylink.data.local.CategoryCount
import dev.punit.tidylink.data.local.LinkEntity
import dev.punit.tidylink.data.local.SortOrder
import dev.punit.tidylink.data.repository.LinkRepository
import dev.punit.tidylink.data.settings.LlmProvider
import dev.punit.tidylink.data.settings.LlmProviderStore
import dev.punit.tidylink.data.settings.OnboardingStore
import dev.punit.tidylink.data.settings.ProviderHealth
import dev.punit.tidylink.data.update.UpdateChecker
import dev.punit.tidylink.data.update.UpdateInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * One-shot snackbar message. The ViewModel never touches string resources
 * directly (it has no Context); the UI resolves these to localized text.
 */
sealed interface UiMessage {
    // @param: pins today's behaviour (annotate the constructor parameter only).
    // Without it, Kotlin warns that a future release will also apply these to
    // the backing field - see KT-73255.
    data class Text(
        @param:StringRes val res: Int,
        val args: List<Any> = emptyList(),
    ) : UiMessage

    data class Plural(
        @param:PluralsRes val res: Int,
        val quantity: Int,
        val args: List<Any> = emptyList(),
    ) : UiMessage
}

/** Lifecycle of the in-app update flow, driving the Settings row. */
sealed interface UpdateState {
    /** Nothing checked yet this session. */
    data object Idle : UpdateState
    data object Checking : UpdateState
    /** Manual check found nothing newer. */
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val percent: Int) : UpdateState
    data class ReadyToInstall(val file: File, val version: String) : UpdateState
    /** Check or download failed - tap retries. */
    data object Failed : UpdateState
}

data class LinkUiState(
    val categories: List<CategoryCount> = emptyList(),
    val searchQuery: String = "",
    val selectedCategory: String? = null,
    val sortOrder: SortOrder = SortOrder.NEWEST,
    val isProcessing: Boolean = false,
    val isRefreshing: Boolean = false,
    val message: UiMessage? = null,
    val pendingUndo: List<LinkEntity> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    /** Links currently being refreshed individually (card / detail sheet). */
    val refreshingIds: Set<String> = emptySet(),
    /** Links still awaiting their first scrape (background enrichment). */
    val pendingEnrichment: Int = 0,
) {
    val isSelectionMode: Boolean get() = selectedIds.isNotEmpty()
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class LinkViewModel(
    private val repository: LinkRepository,
    private val providerStore: LlmProviderStore,
    private val onboardingStore: OnboardingStore,
    private val aiService: AiCategorizationService,
    private val updateChecker: UpdateChecker,
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")
    private val selectedCategory = MutableStateFlow<String?>(null)
    private val sortOrder = MutableStateFlow(SortOrder.NEWEST)
    private val isProcessing = MutableStateFlow(false)
    private val isRefreshing = MutableStateFlow(false)
    private val message = MutableStateFlow<UiMessage?>(null)
    private val pendingUndo = MutableStateFlow<List<LinkEntity>>(emptyList())
    private val selectedIds = MutableStateFlow<Set<String>>(emptySet())
    private val refreshingIds = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Paged library: search (debounced), category filter, and sort all run
     * in SQLite; Compose only ever holds the visible pages in memory, so a
     * 10k-link library scrolls the same as a 100-link one.
     */
    val links: Flow<PagingData<LinkEntity>> = combine(
        searchQuery.debounce(250),
        selectedCategory,
        sortOrder,
        ::Triple,
    ).flatMapLatest { (query, category, sort) ->
        Pager(
            config = PagingConfig(
                pageSize = 60,
                prefetchDistance = 90,
                enablePlaceholders = false,
            ),
            pagingSourceFactory = { repository.pagingSource(query, category, sort) },
        ).flow
    }.cachedIn(viewModelScope)

    private data class TransientState(
        val isProcessing: Boolean,
        val isRefreshing: Boolean,
        val message: UiMessage?,
        val pendingUndo: List<LinkEntity>,
        val selectedIds: Set<String>,
        val refreshingIds: Set<String> = emptySet(),
        val sortOrder: SortOrder = SortOrder.NEWEST,
        val pendingEnrichment: Int = 0,
    )

    /** Bundle secondary state so each combine stays within 5 flows. */
    private val transientState = combine(
        isProcessing, isRefreshing, message, pendingUndo, selectedIds,
    ) { processing, refreshing, msg, undo, selected ->
        TransientState(processing, refreshing, msg, undo, selected)
    }.combine(refreshingIds) { transient, ids -> transient.copy(refreshingIds = ids) }
        .combine(sortOrder) { transient, sort -> transient.copy(sortOrder = sort) }
        .combine(repository.pendingEnrichmentCount()) { transient, pending ->
            transient.copy(pendingEnrichment = pending)
        }

    val uiState: StateFlow<LinkUiState> = combine(
        repository.getCategories(),
        searchQuery,
        selectedCategory,
        transientState,
    ) { categories, query, category, transient ->
        LinkUiState(
            categories = categories,
            searchQuery = query,
            selectedCategory = category,
            sortOrder = transient.sortOrder,
            isProcessing = transient.isProcessing,
            isRefreshing = transient.isRefreshing,
            message = transient.message,
            pendingUndo = transient.pendingUndo,
            selectedIds = transient.selectedIds,
            refreshingIds = transient.refreshingIds,
            pendingEnrichment = transient.pendingEnrichment,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LinkUiState(),
    )

    /** Immediately reflected in the UI; the actual FTS query is debounced. */
    val searchQueryInput: StateFlow<String> = searchQuery.asStateFlow()

    /** Live view of a single link - keeps the detail sheet current. */
    fun observeLink(id: String): Flow<LinkEntity?> = repository.observeLink(id)

    fun search(query: String) {
        searchQuery.value = query
    }

    fun selectCategory(category: String?) {
        selectedCategory.value = category
    }

    fun setSortOrder(order: SortOrder) {
        sortOrder.value = order
    }

    fun processAndSaveUrl(url: String) {
        if (!UrlCanonicalizer.isValidHttpUrl(url)) {
            message.value = UiMessage.Text(R.string.add_url_invalid)
            return
        }
        viewModelScope.launch {
            isProcessing.value = true
            message.value = null
            try {
                val result = repository.processAndSaveUrl(url)
                if (result.alreadyExisted) {
                    message.value = UiMessage.Text(R.string.msg_already_saved)
                }
            } catch (e: Exception) {
                message.value = UiMessage.Text(R.string.msg_save_failed)
            } finally {
                isProcessing.value = false
            }
        }
    }

    /** Manual edit of a link's title / category / tags (comma-separated). */
    fun editLink(link: LinkEntity, title: String, category: String, tagsText: String) {
        viewModelScope.launch {
            try {
                val tags = tagsText.split(',').map { it.trim() }.filter { it.isNotBlank() }
                repository.updateLinkDetails(link, title, category, tags)
            } catch (e: Exception) {
                message.value = UiMessage.Text(R.string.msg_edit_failed)
            }
        }
    }

    /** Bulk recategorization of the current selection. */
    fun moveSelectedToCategory(category: String) {
        val ids = selectedIds.value.toList()
        if (ids.isEmpty() || category.isBlank()) return
        selectedIds.value = emptySet()
        viewModelScope.launch {
            try {
                repository.moveToCategory(ids, category)
                message.value = UiMessage.Plural(
                    R.plurals.msg_moved,
                    ids.size,
                    listOf(ids.size, category),
                )
            } catch (e: Exception) {
                message.value = UiMessage.Text(R.string.msg_move_failed)
            }
        }
    }

    fun togglePin(link: LinkEntity) {
        viewModelScope.launch {
            try {
                repository.setPinned(link.id, !link.pinned)
            } catch (e: Exception) {
                message.value = UiMessage.Text(R.string.msg_pin_failed)
            }
        }
    }

    /**
     * Refreshes a single link. Tracked per-id (not via the global
     * [isProcessing] flag) so only the affected card / sheet shows progress
     * instead of the app-wide bar.
     */
    fun refreshLink(link: LinkEntity) {
        viewModelScope.launch {
            refreshingIds.value += link.id
            try {
                repository.refreshLink(link)
            } catch (e: Exception) {
                message.value = UiMessage.Text(R.string.msg_refresh_link_failed)
            } finally {
                refreshingIds.value -= link.id
            }
        }
    }

    /**
     * Manual refresh: merges duplicates and re-fetches details for every
     * link that still needs them (never scraped, image-less under the
     * attempt cap, or unclassified).
     */
    fun refreshAll() {
        if (isRefreshing.value) return
        viewModelScope.launch {
            isRefreshing.value = true
            try {
                val result = repository.refreshUnfetched()
                message.value = when {
                    result.refreshed == 0 -> UiMessage.Text(R.string.msg_all_up_to_date)
                    // No provider configured: nothing will retry, so don't
                    // promise it - point at the fix instead.
                    result.aiUnavailable -> UiMessage.Text(
                        R.string.msg_refreshed_no_provider,
                        listOf(result.refreshed, result.unclassified),
                    )
                    result.unclassified > 0 -> UiMessage.Text(
                        R.string.msg_refreshed_with_pending,
                        listOf(result.refreshed, result.unclassified),
                    )
                    else -> UiMessage.Plural(
                        R.plurals.msg_refreshed,
                        result.refreshed,
                        listOf(result.refreshed),
                    )
                }
            } catch (e: Exception) {
                message.value = UiMessage.Text(R.string.msg_refresh_failed)
            } finally {
                isRefreshing.value = false
            }
        }
    }

    // --- Selection ------------------------------------------------------------

    fun toggleSelection(id: String) {
        selectedIds.value = selectedIds.value.let { if (id in it) it - id else it + id }
    }

    fun clearSelection() {
        selectedIds.value = emptySet()
    }

    // --- Delete with undo ---------------------------------------------------

    fun deleteLink(link: LinkEntity) {
        viewModelScope.launch {
            repository.deleteLink(link.id)
            pendingUndo.value = listOf(link)
        }
    }

    /** Bulk-deletes everything currently selected, keeping copies for undo. */
    fun deleteSelected() {
        val ids = selectedIds.value.toList()
        if (ids.isEmpty()) return
        selectedIds.value = emptySet()
        viewModelScope.launch {
            val toDelete = repository.getLinksByIds(ids)
            repository.deleteLinks(ids)
            pendingUndo.value = toDelete
        }
    }

    fun undoDelete() {
        val links = pendingUndo.value
        if (links.isEmpty()) return
        pendingUndo.value = emptyList()
        viewModelScope.launch { repository.restoreLinks(links) }
    }

    fun clearUndo() {
        pendingUndo.value = emptyList()
    }

    // --- Export / import ----------------------------------------------------

    /** Streams the whole library as JSON into [stream]. */
    suspend fun exportLinks(stream: OutputStream) = repository.exportLinks(stream)

    /** Returns number of imported links, or -1 on invalid JSON. */
    suspend fun importLinks(stream: InputStream): Int = repository.importLinks(stream)

    /**
     * Extracts every http(s) URL from arbitrary text (e.g. a .txt file of
     * links) and bulk-imports them: new links appear immediately as
     * placeholders, then a WorkManager sweep scrapes and classifies them in
     * the background (survives leaving the app). Duplicates - including
     * tracking-param variants - are skipped. All outcome messages (none
     * found, import summary) surface through [message], so callers show
     * nothing themselves.
     */
    fun importUrlsFromText(text: String) {
        val urls = UrlCanonicalizer.extractUrls(text)
        if (urls.isEmpty()) {
            message.value = UiMessage.Text(R.string.msg_no_links_found_file)
            return
        }

        viewModelScope.launch {
            isProcessing.value = true
            try {
                val summary = repository.importUrls(urls)
                message.value = when {
                    summary.imported == 0 -> UiMessage.Text(
                        R.string.msg_import_none_new,
                        listOf(summary.duplicates),
                    )
                    summary.duplicates > 0 -> UiMessage.Text(
                        R.string.msg_imported_with_dups,
                        listOf(summary.imported, summary.duplicates),
                    )
                    else -> UiMessage.Plural(
                        R.plurals.msg_imported,
                        summary.imported,
                        listOf(summary.imported),
                    )
                }
            } catch (e: Exception) {
                message.value = UiMessage.Text(R.string.msg_import_failed)
            } finally {
                isProcessing.value = false
            }
        }
        return urls.size
    }

    /**
     * Merges the sprawling category list into a small set of broad ones
     * (single LLM call + bulk renames). The UI asks for confirmation first -
     * renames are not undoable.
     */
    fun tidyCategories() {
        viewModelScope.launch {
            isProcessing.value = true
            try {
                val result = repository.consolidateCategories()
                // The selected category may have been renamed away.
                if (result.merged > 0) selectedCategory.value = null
                message.value = when {
                    result.merged > 0 && result.aiUnavailable -> UiMessage.Text(
                        R.string.msg_tidy_merged_ai_unavailable,
                        listOf(result.merged),
                    )
                    result.merged > 0 -> UiMessage.Plural(
                        R.plurals.msg_tidy_merged,
                        result.merged,
                        listOf(result.merged),
                    )
                    result.aiUnavailable -> UiMessage.Text(R.string.msg_tidy_ai_unavailable)
                    else -> UiMessage.Text(R.string.msg_tidy_already)
                }
            } catch (e: Exception) {
                message.value = UiMessage.Text(R.string.msg_tidy_failed)
            } finally {
                isProcessing.value = false
            }
        }
    }

    // --- AI providers (in-app API keys) --------------------------------------

    /** Providers tried in order; extra ones act as rate-limit fallbacks. */
    val llmProviders: StateFlow<List<LlmProvider>> = providerStore.providers

    /** Provider id -> last success/failure, shown in the provider sheet. */
    val providerHealth: StateFlow<Map<String, ProviderHealth>> = providerStore.health

    fun addLlmProvider(name: String, baseUrl: String, model: String, apiKey: String) {
        if (baseUrl.isBlank() || model.isBlank() || apiKey.isBlank()) return
        providerStore.add(
            LlmProvider(name = name, baseUrl = baseUrl, model = model, apiKey = apiKey)
        )
    }

    fun removeLlmProvider(id: String) {
        providerStore.remove(id)
    }

    /** Moves a provider up/down the fallback order. */
    fun moveLlmProvider(id: String, up: Boolean) {
        providerStore.move(id, up)
    }

    /**
     * Live-checks an unsaved provider. [onResult] gets null on success, or a
     * short human-readable reason - so a bad key is caught at paste time
     * rather than surfacing as a silent "Failing" much later.
     */
    fun testLlmProvider(
        name: String,
        baseUrl: String,
        model: String,
        apiKey: String,
        onResult: (String?) -> Unit,
    ) {
        if (baseUrl.isBlank() || model.isBlank() || apiKey.isBlank()) {
            onResult("Fill in the URL, model and key first")
            return
        }
        viewModelScope.launch {
            onResult(
                aiService.testProvider(
                    providerStore.normalize(
                        LlmProvider(
                            name = name,
                            baseUrl = baseUrl,
                            model = model,
                            apiKey = apiKey,
                        )
                    )
                )
            )
        }
    }

    fun dismissMessage() {
        message.value = null
    }

    // --- In-app updates -------------------------------------------------------

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)

    /** Drives the "Check for updates" row in Settings > About. */
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    init {
        // Weekly automatic check; failures stay silent - the user didn't ask.
        if (updateChecker.shouldAutoCheck()) checkForUpdates(auto = true)
    }

    /** Manual (Settings row) or weekly automatic check. */
    fun checkForUpdates(auto: Boolean = false) {
        if (_updateState.value is UpdateState.Checking ||
            _updateState.value is UpdateState.Downloading
        ) {
            return
        }
        viewModelScope.launch {
            if (!auto) _updateState.value = UpdateState.Checking
            try {
                val info = updateChecker.fetchLatest()
                if (info != null) {
                    _updateState.value = UpdateState.Available(info)
                    // The weekly check runs on the dashboard, where the
                    // Settings row is invisible - surface it as a snackbar.
                    if (auto) {
                        message.value = UiMessage.Text(
                            R.string.msg_update_available,
                            listOf(info.version),
                        )
                    }
                } else if (!auto) {
                    _updateState.value = UpdateState.UpToDate
                }
            } catch (e: Exception) {
                if (!auto) _updateState.value = UpdateState.Failed
            }
        }
    }

    /** Downloads the APK; the UI offers install when [UpdateState.ReadyToInstall]. */
    fun downloadUpdate() {
        val available = _updateState.value as? UpdateState.Available ?: return
        viewModelScope.launch {
            _updateState.value = UpdateState.Downloading(0)
            try {
                val file = updateChecker.downloadApk(available.info) { percent ->
                    _updateState.value = UpdateState.Downloading(percent)
                }
                _updateState.value =
                    UpdateState.ReadyToInstall(file, available.info.version)
            } catch (e: Exception) {
                _updateState.value = UpdateState.Failed
            }
        }
    }

    // --- First-run intro -----------------------------------------------------

    /** False until the intro is finished or skipped; gates the dashboard. */
    val hasSeenIntro: StateFlow<Boolean> = onboardingStore.hasSeenIntro

    /** Finishing and skipping are the same commitment: don't show it again. */
    fun markIntroSeen() {
        onboardingStore.markIntroSeen()
    }

    /** Settings → About → "Show intro again". */
    fun replayIntro() {
        onboardingStore.replayIntro()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                        as TidyLinkApplication
                LinkViewModel(
                    repository = app.container.linkRepository,
                    providerStore = app.container.llmProviderStore,
                    onboardingStore = app.container.onboardingStore,
                    aiService = app.container.aiService,
                    updateChecker = app.container.updateChecker,
                )
            }
        }
    }
}
