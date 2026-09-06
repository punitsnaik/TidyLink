package dev.punit.tidylink.ui.dashboard

import androidx.paging.ItemSnapshotList
import dev.punit.tidylink.data.reader.ReaderArticle

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.punit.tidylink.R
import dev.punit.tidylink.data.local.LinkEntity

import dev.punit.tidylink.data.settings.LibraryViewMode
import dev.punit.tidylink.ui.LinkViewModel
import dev.punit.tidylink.ui.UpdateState
import dev.punit.tidylink.ui.theme.Motion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Public repository - linked from Settings → About. */
private const val REPO_URL = "https://github.com/punitsnaik/TidyLink"

/** Backdrop blur radius while any modal is open. See its use site. */
private val BACKDROP_BLUR = 40.dp

/**
 * An action waiting on confirmation: what to say, and what to run if the
 * user goes ahead. One holder for every confirm-then-act entry point (the
 * four deletes, tidy-up, empty trash, merge duplicates) so they can't drift
 * apart.
 */
private data class PendingConfirm(
    /** String resource. */
    val title: Int,
    /** String resource when [count] is null, plurals resource otherwise. */
    val body: Int,
    /** String resource for the confirm button. */
    val action: Int,
    /** Quantity for [body]; also the single plurals argument. */
    val count: Int? = null,
    /** Tints the confirm button with the error colour. */
    val destructive: Boolean = false,
    val confirm: () -> Unit,
)

/** Paging indices include leading placeholders, unlike the loaded item slice. */
internal fun detailLinkIndex(snapshot: ItemSnapshotList<LinkEntity>, id: String): Int {
    val loadedIndex = snapshot.items.indexOfFirst { it.id == id }
    return if (loadedIndex < 0) -1 else snapshot.placeholdersBefore + loadedIndex
}

/** The delete flavours of [PendingConfirm] - by far the most common. */
private fun deleteConfirm(
    count: Int,
    permanent: Boolean = false,
    confirm: () -> Unit,
) = PendingConfirm(
    title = if (permanent) R.string.dialog_delete_forever_title else R.string.dialog_delete_title,
    body = if (permanent) R.plurals.dialog_delete_forever_body else R.plurals.dialog_delete_body,
    action = if (permanent) R.string.action_delete_forever else R.string.action_delete,
    count = count,
    destructive = true,
    confirm = confirm,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: LinkViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val query by viewModel.searchQueryInput.collectAsStateWithLifecycle()
    val lazyLinks = viewModel.links.collectAsLazyPagingItems()
    // Hoisted out of the Pinned tab branch: the detail sheet is composed at
    // the bottom of this function and needs whichever list the user tapped
    // from to work out the swipe neighbour. Cost is one always-collected
    // paging query; the alternative is threading a callback through two
    // more layers.
    val lazyPinned = viewModel.pinnedLinks.collectAsLazyPagingItems()
    val providers by viewModel.llmProviders.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val libraryViewMode by viewModel.libraryViewMode.collectAsStateWithLifecycle()
    val cardRefreshSwipe by viewModel.cardRefreshSwipe.collectAsStateWithLifecycle()
    val cardDeleteSwipe by viewModel.cardDeleteSwipe.collectAsStateWithLifecycle()
    val pageSwipeNavigation by viewModel.pageSwipeNavigation.collectAsStateWithLifecycle()
    val backupState by viewModel.backupState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    // LocalResources (not context.resources): stays correct across locale /
    // configuration changes, and satisfies the Compose lint check.
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var currentTab by rememberSaveable { mutableStateOf(DashboardTab.Links) }
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var showSortSheet by rememberSaveable { mutableStateOf(false) }
    var showThemeSheet by rememberSaveable { mutableStateOf(false) }
    var showAiProviders by rememberSaveable { mutableStateOf(false) }
    var showMoveDialog by rememberSaveable { mutableStateOf(false) }
    var showBookmarkImport by rememberSaveable { mutableStateOf(false) }
    var showTrash by rememberSaveable { mutableStateOf(false) }
    // Device sync, same full-page convention as Trash - see PairDeviceScreen's KDoc.
    var providerBannerDismissed by rememberSaveable { mutableStateOf(false) }
    // Ids (not entities) survive rotation/process death; the live entity is
    // observed from the DB below.
    var selectedLinkId by rememberSaveable { mutableStateOf<String?>(null) }
    var detailHistory by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var savedRelatedUrls by remember { mutableStateOf(emptySet<String>()) }
    var savingRelatedUrls by remember { mutableStateOf(emptySet<String>()) }
    var detailVisible by rememberSaveable { mutableStateOf(false) }
    var editingLinkId by rememberSaveable { mutableStateOf<String?>(null) }
    var showReaderMode by remember { mutableStateOf(false) }
    var readerArticle by remember { mutableStateOf<ReaderArticle?>(null) }
    var isReaderLoading by remember { mutableStateOf(false) }
    // Every confirm-then-act in the app funnels through one dialog: deletes
    // (swipe, selection toolbar, detail sheet, trash), tidy-up, empty trash
    // and duplicate merging.
    // Deliberately NOT rememberSaveable: it holds the action to run, and a
    // lambda can't go in a Bundle. A rotation mid-dialog cancels the action,
    // which is the safe direction to fail.
    var pendingConfirm by remember { mutableStateOf<PendingConfirm?>(null) }

    // Grid states live here (not inside the tab branches) so scroll
    // positions survive switching tabs.
    val gridState = rememberLazyGridState()
    val pinnedGridState = rememberLazyGridState()

    // One source feeds every glass surface (pill nav, pinned search bar).
    val hazeState = remember { HazeState() }

    // Any modal window open -> the content behind it blurs (API 31+; a
    // no-op below, where the standard scrim still dims). Derived, not
    // stored: it must never go stale against a dismissed sheet.
    //
    // The blur Modifier itself is applied conditionally further down, not
    // unconditionally at radius 0: Modifier.blur keeps an offscreen
    // graphics layer alive even at 0dp, so the layer must exist only while
    // a modal is open or animating closed, not as a permanent steady-state
    // cost on every frame the app draws.
    // Note: pendingConfirm now carries tidy / empty-trash / duplicates, which
    // were separate flags here, AND the delete dialog, which never was. So
    // the delete confirmation now blurs the backdrop like every other modal
    // - a deliberate consequence of the merge, not a stray change.
    // showTrash is deliberately NOT here. Trash is a full-screen page now,
    // not a modal window - it covers the very content the blur would be
    // blurring, so the blur would be a per-frame cost drawing something
    // nobody can see. Confirmations raised from inside it are still dialogs
    // and still set this via pendingConfirm.
    val modalOpen = showAddDialog || showSortSheet || showThemeSheet ||
        showAiProviders || showMoveDialog ||
        showBookmarkImport || pendingConfirm != null ||
        editingLinkId != null
    // Paired with SHEET_GLASS_ALPHA - the two are one effect and should be
    // tuned together. 20.dp left the backdrop only softly out of focus and
    // read as a flat panel; 40.dp is deep enough that what shows through
    // the sheet is unmistakably blur rather than dimmed content, and deep
    // enough that individual cards behind it stop being identifiable, which
    // is what keeps text on the sheet readable at a low alpha.
    // Modifier.blur is a no-op below API 31, so this costs nothing there.
    //
    // NOT animated, deliberately - it used to ramp 0 -> 40.dp over 300ms via
    // animateDpAsState. A blur radius is not a cheap animatable property:
    // every distinct radius rebuilds the RenderEffect and re-renders the
    // WHOLE screen into an offscreen layer, and it did that for 18 frames at
    // precisely the moment the sheet was also animating in. That ramp was
    // the sheet-opening stutter. The sheet's own slide-in and the scrim
    // carry the transition; the blur only ever needed to be there.
    val backdropBlur = if (modalOpen) BACKDROP_BLUR else 0.dp

    // Auto-scroll to the top when a NEW link lands at the head of the list
    // (added manually, shared in, or imported) - but not on deletes.
    //
    // grew/topChanged alone are not reliable signals: Room invalidates this
    // screen's PagingSource on ANY write to `links` (the background
    // enrichment sweep, thumbnail recovery, etc. all write to rows that may
    // be far from the head), and during the resulting reload
    // itemSnapshotList's local index 0 can transiently be a different item
    // than the true first link. That false positive used to fire this
    // effect while scrolled deep in the list, snapping the reader back to
    // the top mid-scroll. Guarded here: only ever auto-scroll if the reader
    // is already at (or very near) the top, which is also the only case
    // where "reveal the new link" is the right behavior in the first place.
    val firstLinkId = lazyLinks.itemSnapshotList.items.firstOrNull()?.id
    var lastTopId by remember { mutableStateOf(firstLinkId) }
    var lastCount by remember { mutableIntStateOf(lazyLinks.itemCount) }
    LaunchedEffect(firstLinkId, lazyLinks.itemCount) {
        val grew = lazyLinks.itemCount > lastCount
        val topChanged = firstLinkId != null && firstLinkId != lastTopId
        val readerAtTop = gridState.firstVisibleItemIndex <= 1
        lastTopId = firstLinkId
        lastCount = lazyLinks.itemCount
        if (grew && topChanged && readerAtTop) gridState.animateScrollToItem(0)
    }

    // Changing the sort order starts the reader back at the top.
    LaunchedEffect(uiState.sortOrder) {
        gridState.scrollToItem(0)
    }

    // One-shot snackbar messages from the ViewModel, resolved to localized
    // text here (the ViewModel has no Context).
    val messageText = uiState.message?.let { resolveUiMessage(it) }
    LaunchedEffect(uiState.message) {
        if (uiState.message != null && messageText != null) {
            snackbarHostState.showSnackbar(messageText)
            viewModel.dismissMessage()
        }
    }

    // Undo-delete snackbar (single or bulk)
    LaunchedEffect(uiState.pendingUndo) {
        val deleted = uiState.pendingUndo
        if (deleted.isEmpty()) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = resources.getQuantityString(
                R.plurals.links_deleted, deleted.size, deleted.size,
            ),
            actionLabel = resources.getString(R.string.action_undo),
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undoDelete()
        } else if (uiState.pendingUndo == deleted) {
            viewModel.clearUndo()
        }
    }

    // Back gesture: exit selection mode first, then return to the Links tab,
    // and only then exit the app.
    BackHandler(enabled = uiState.isSelectionMode) {
        viewModel.clearSelection()
    }
    BackHandler(enabled = !uiState.isSelectionMode && currentTab != DashboardTab.Links) {
        currentTab = DashboardTab.Links
    }

    // Export: user picks where to save the JSON backup (streamed to disk).
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val text = try {
                withContext(Dispatchers.IO) {
                    val output = requireNotNull(context.contentResolver.openOutputStream(uri)) {
                        "Could not open export for writing"
                    }
                    output.use { stream ->
                        viewModel.exportLinks(stream)
                    }
                }
                resources.getString(R.string.msg_export_done)
            } catch (e: Exception) {
                resources.getString(R.string.msg_export_failed)
            }
            snackbarHostState.showSnackbar(text)
        }
    }

    // Import: user picks a previously exported JSON file (streamed).
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        viewModel.importLinks(uri)
    }

    // Bookmark import: the folder choice is made in a dialog BEFORE the
    // picker opens, so this launcher only has to carry the answer.
    var importFoldersAsCategories by rememberSaveable { mutableStateOf(true) }
    val bookmarkImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        viewModel.importBookmarks(uri, importFoldersAsCategories)
    }

    // Backup folder: a TREE uri, not the CreateDocument uri the manual
    // export uses. Only a tree uri can be re-opened later, and only with
    // takePersistableUriPermission does it survive a reboot - without that
    // call the weekly worker would silently start failing after a restart.
    val backupFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        // Guarded, and the guard decides whether backups turn on at all.
        // A provider that hands back a tree URI without a persistable grant
        // throws SecurityException here - unguarded that crashed the app
        // inside this callback. Enabling anyway would be worse than the
        // crash in one way: the weekly worker would run until the next
        // reboot and then fail silently forever, which is exactly the
        // failure this feature exists to rule out. So: no grant, no backup.
        val persisted = runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.isSuccess
        if (persisted) viewModel.enableBackup(uri.toString())
        scope.launch {
            snackbarHostState.showSnackbar(
                resources.getString(
                    if (persisted) {
                        R.string.msg_auto_backup_enabled
                    } else {
                        R.string.msg_auto_backup_folder_unusable
                    }
                )
            )
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .then(if (backdropBlur > 0.dp) Modifier.blur(backdropBlur) else Modifier),
        topBar = {
            if (uiState.isSelectionMode && currentTab in listOf(DashboardTab.Links, DashboardTab.Pinned)) {
                TopAppBar(
                    title = { Text(stringResource(R.string.selected_count, uiState.selectedIds.size)) },
                    navigationIcon = {
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(
                                Icons.Default.Clear,
                                contentDescription = stringResource(R.string.action_exit_selection),
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { showMoveDialog = true }) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = stringResource(R.string.action_move_to_category),
                            )
                        }
                        IconButton(
                            onClick = {
                                pendingConfirm = deleteConfirm(uiState.selectedIds.size) {
                                    viewModel.deleteSelected()
                                }
                            },
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.action_delete_selected),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    },
                )
            } else if (currentTab != DashboardTab.Links) {
                TopAppBar(
                    title = {
                        Text(
                            when (currentTab) {
                                DashboardTab.Pinned -> stringResource(R.string.nav_pinned)
                                DashboardTab.Tools -> stringResource(R.string.nav_tools)
                                DashboardTab.Settings -> stringResource(R.string.title_settings)
                                DashboardTab.Links -> stringResource(R.string.app_name)
                            }
                        )
                    },
                )
            }
            // Links tab: no pinned app bar - the collapsing header (title,
            // sort, refresh, search, category tiles) lives in LinksTab and
            // hides on scroll.
        },
        // The floating pill nav replaces the old NavigationBar + FAB; it is
        // overlaid on the content so the list scrolls underneath it.
        snackbarHost = {
            // Lifted clear of the floating pill nav.
            if (!detailVisible) SnackbarHost(
                snackbarHostState,
                modifier = Modifier.padding(bottom = 88.dp),
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize().hazeSource(hazeState)) {
                AnimatedContent(
                    targetState = currentTab,
                    transitionSpec = {
                        // M3 fade-through: outgoing fades fast, incoming
                        // fades in after it.
                        //
                        // The incoming screen used to also scaleIn(0.94f).
                        // Scaling forces BOTH screens into rasterized
                        // graphics layers for the duration - two full paging
                        // grids at once, on a transition the user fires
                        // constantly. A plain fade-through is the canonical
                        // M3 top-level transition and costs one alpha.
                        fadeIn(
                            tween(Motion.FADE_IN_MS, delayMillis = Motion.FADE_OUT_MS, easing = Motion.EnterEasing)
                        ).togetherWith(fadeOut(tween(Motion.FADE_OUT_MS, easing = Motion.ExitEasing)))
                    },
                    label = "tabTransition",
                ) { tab ->
                    when (tab) {
                        DashboardTab.Links -> LinksTab(
                            viewModel = viewModel,
                            uiState = uiState,
                            query = query,
                            lazyLinks = lazyLinks,
                            hasProviders = providers.isNotEmpty(),
                            providerBannerDismissed = providerBannerDismissed,
                            onDismissProviderBanner = { providerBannerDismissed = true },
                            gridState = gridState,
                            viewMode = libraryViewMode,
                            cardRefreshSwipe = cardRefreshSwipe,
                            cardDeleteSwipe = cardDeleteSwipe,
                            onShowSortSheet = { showSortSheet = true },
                            onToggleViewMode = {
                                viewModel.setLibraryViewMode(
                                    if (libraryViewMode == LibraryViewMode.ADAPTIVE) {
                                        LibraryViewMode.COMPACT
                                    } else {
                                        LibraryViewMode.ADAPTIVE
                                    }
                                )
                            },
                            onShowAiProviders = { showAiProviders = true },
                            onOpenDetail = { detailHistory = arrayListOf(); selectedLinkId = it; detailVisible = true },
                            onRequestDelete = { link ->
                                pendingConfirm = deleteConfirm(1) { viewModel.deleteLink(link) }
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                        )

                        DashboardTab.Pinned -> {
                            val pinnedEmpty = lazyPinned.itemCount == 0 &&
                                lazyPinned.loadState.refresh !is LoadState.Loading
                            if (pinnedEmpty) {
                                EmptyState(
                                    text = stringResource(R.string.empty_no_pinned),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(innerPadding),
                                )
                            } else {
                                LinksGrid(
                                    lazyLinks = lazyPinned,
                                    gridState = pinnedGridState,
                                    selectedIds = uiState.selectedIds,
                                    refreshingIds = uiState.refreshingIds,
                                    isSelectionMode = uiState.isSelectionMode,
                                    viewMode = libraryViewMode,
                                    cardRefreshSwipe = cardRefreshSwipe,
                                    cardDeleteSwipe = cardDeleteSwipe,
                                    onToggleSelection = viewModel::toggleSelection,
                                    onRefreshLink = viewModel::refreshLink,
                                    onImageFailed = viewModel::recoverThumbnail,
                                    onOpenDetail = { detailHistory = arrayListOf(); selectedLinkId = it; detailVisible = true },
                                    onRequestDelete = { link ->
                                        pendingConfirm = deleteConfirm(1) { viewModel.deleteLink(link) }
                                    },
                                    animateEntrance = false,
                                    header = if (uiState.isSelectionMode) null else {
                                        { ResultsHeader(lazyPinned.loadState.refresh, lazyPinned.itemCount, false) }
                                    },
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(innerPadding),
                                )
                            }
                        }

                        DashboardTab.Tools -> ToolsTab(
                            isRefreshing = uiState.isRefreshing,
                            duplicateCount = uiState.duplicateCount,
                            trashCount = uiState.trashCount,
                            deadLinksProgress = uiState.deadLinksScanProgress,
                            safetyScanProgress = uiState.safetyScanProgress,
                            isEnrichingGitHub = uiState.isEnrichingGitHub,
                            onFetchMissingDetails = viewModel::refreshAll,
                            onCheckBrokenLinks = viewModel::scanDeadLinks,
                            onScanSafety = viewModel::scanMaliciousLinks,
                            onEnrichGitHub = viewModel::enrichGitHubRepos,
                            onOpenTrash = { showTrash = true },
                            onTidyCategories = {
                                pendingConfirm = PendingConfirm(
                                    title = R.string.tidy_confirm_title,
                                    body = R.string.tidy_confirm_body,
                                    action = R.string.tidy_confirm_action,
                                ) { viewModel.tidyCategories() }
                            },
                            onMergeDuplicates = {
                                pendingConfirm = PendingConfirm(
                                    title = R.string.dialog_duplicates_title,
                                    body = R.plurals.dialog_duplicates_body,
                                    action = R.string.dialog_duplicates_confirm,
                                    count = uiState.duplicateCount,
                                ) { viewModel.mergeDuplicates() }
                            },
                            onEmptyTrash = {
                                pendingConfirm = PendingConfirm(
                                    title = R.string.dialog_empty_trash_title,
                                    body = R.string.dialog_empty_trash_body,
                                    action = R.string.action_empty_trash,
                                ) { viewModel.emptyTrash() }
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                        )

                        DashboardTab.Settings -> SettingsTab(
                            themeMode = themeMode,
                            cardRefreshSwipe = cardRefreshSwipe,
                            cardDeleteSwipe = cardDeleteSwipe,
                            pageSwipeNavigation = pageSwipeNavigation,
                            onThemeClick = { showThemeSheet = true },
                            onCardRefreshSwipeChange = viewModel::setCardRefreshSwipe,
                            onCardDeleteSwipeChange = viewModel::setCardDeleteSwipe,
                            onPageSwipeNavigationChange = viewModel::setPageSwipeNavigation,
                            onAiProviders = { showAiProviders = true },
                            onExport = { exportLauncher.launch("tidylink-backup.json") },
                            onImportBookmarks = { showBookmarkImport = true },
                            backupState = backupState,
                            // Turning it ON always re-opens the picker: the row is also
                            // the recovery path when the chosen folder has gone away.
                            onToggleAutoBackup = {
                                if (backupState.enabled) {
                                    viewModel.disableBackup()
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            resources.getString(R.string.msg_auto_backup_disabled)
                                        )
                                    }
                                } else {
                                    backupFolderLauncher.launch(null)
                                }
                            },
                            onImportJson = {
                                importLauncher.launch(
                                    arrayOf("application/json", "application/octet-stream")
                                )
                            },
                            onShowIntro = viewModel::replayIntro,
                            onOpenRepo = { openLink(context, REPO_URL) },
                            updateState = updateState,
                            onUpdateClick = {
                                when (val state = updateState) {
                                    is UpdateState.Available -> viewModel.downloadUpdate()
                                    is UpdateState.ReadyToInstall -> installApk(context, state.file)
                                    // Row is disabled in these states; nothing to do.
                                    UpdateState.Checking, is UpdateState.Downloading -> Unit
                                    else -> viewModel.checkForUpdates()
                                }
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                        )
                    }
                }
            }

            BottomPillNav(
                currentTab = currentTab,
                onSelect = { currentTab = it },
                onAdd = { showAddDialog = true },
                hazeState = hazeState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp),
            )

            // Trash is a full page, not a sheet. Drawn last inside this Box
            // so it covers the grid, the glass search bar and the pill nav;
            // it is opaque, so nothing behind it composes visibly and there
            // is no backdrop to blur (see TrashScreen's KDoc). It is
            // deliberately absent from `modalOpen` for that reason.
            AnimatedVisibility(
                visible = showTrash,
                enter = fadeIn(tween(Motion.FADE_IN_MS, easing = Motion.EnterEasing)),
                exit = fadeOut(tween(Motion.FADE_OUT_MS, easing = Motion.ExitEasing)),
                modifier = Modifier.fillMaxSize(),
            ) {
                val trashed by viewModel.trashedLinks.collectAsStateWithLifecycle()
                TrashScreen(
                    trashed = trashed,
                    onRestore = viewModel::restoreFromTrash,
                    onDeleteForever = { ids ->
                        pendingConfirm = deleteConfirm(ids.size, permanent = true) {
                            viewModel.deleteFromTrashForever(ids)
                        }
                    },
                    // Emptying the trash is the one genuinely irreversible
                    // action in the app - everything else here can be undone.
                    // The page stays open behind the dialog, unlike the sheet
                    // it replaces, which had to close to get out of the way.
                    onEmptyTrash = {
                        pendingConfirm = PendingConfirm(
                            title = R.string.dialog_empty_trash_title,
                            body = R.string.dialog_empty_trash_body,
                            action = R.string.action_empty_trash,
                        ) { viewModel.emptyTrash() }
                    },
                    onClose = { showTrash = false },
                )
            }

        }
    }

    if (showAddDialog) {
        AddLinkDialog(
            onConfirm = { url ->
                viewModel.processAndSaveUrl(url)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }

    if (showMoveDialog) {
        MoveCategoryDialog(
            categories = uiState.categories,
            count = uiState.selectedIds.size,
            onConfirm = { category ->
                viewModel.moveSelectedToCategory(category)
                showMoveDialog = false
            },
            onDismiss = { showMoveDialog = false },
        )
    }

    // One dialog for every confirm-then-act. A swipe-delete on a card is the
    // easy-to-trigger one and the reason this exists, but routing the
    // selection toolbar, detail sheet, trash, tidy-up and duplicate merging
    // through the same holder is what keeps them from drifting apart.
    pendingConfirm?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingConfirm = null },
            title = { Text(stringResource(pending.title)) },
            text = {
                Text(
                    if (pending.count != null) {
                        pluralStringResource(pending.body, pending.count, pending.count)
                    } else {
                        stringResource(pending.body)
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingConfirm = null
                        pending.confirm()
                    },
                ) {
                    Text(
                        stringResource(pending.action),
                        color = if (pending.destructive) {
                            MaterialTheme.colorScheme.error
                        } else {
                            Color.Unspecified
                        },
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfirm = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showBookmarkImport) {
        BookmarkImportDialog(
            onConfirm = { useFolders ->
                showBookmarkImport = false
                importFoldersAsCategories = useFolders
                // Some file providers mislabel exported bookmarks as
                // application/octet-stream or text/plain, so the filter
                // can't be text/html alone or the file is greyed out.
                bookmarkImportLauncher.launch(arrayOf("text/html", "text/plain", "*/*"))
            },
            onDismiss = { showBookmarkImport = false },
        )
    }

    editingLinkId?.let { id ->
        val editingLink by remember(id) { viewModel.observeLink(id) }
            .collectAsStateWithLifecycle(initialValue = null)
        editingLink?.let { link ->
            EditLinkDialog(
                link = link,
                onConfirm = { title, category, note ->
                    viewModel.editLink(link, title, category, note)
                    editingLinkId = null
                },
                onDismiss = { editingLinkId = null },
            )
        }
    }

    if (showSortSheet) {
        SortSheet(
            current = uiState.sortOrder,
            onSelect = { order ->
                viewModel.setSortOrder(order)
                showSortSheet = false
            },
            onDismiss = { showSortSheet = false },
        )
    }

    if (showThemeSheet) {
        ThemeSheet(
            current = themeMode,
            onSelect = { mode ->
                viewModel.setThemeMode(mode)
                showThemeSheet = false
            },
            onDismiss = { showThemeSheet = false },
        )
    }

    if (showAiProviders) {
        val providerHealth by viewModel.providerHealth.collectAsStateWithLifecycle()
        AiProviderSheet(
            providers = providers,
            health = providerHealth,
            onAdd = viewModel::addLlmProvider,
            onRemove = viewModel::removeLlmProvider,
            onMove = viewModel::moveLlmProvider,
            onTest = viewModel::testLlmProvider,
            onDismiss = { showAiProviders = false },
        )
    }

    selectedLinkId?.let { id ->
        // Swipe navigates the list the user actually tapped from, so it
        // follows the active search, category filter and sort.
        val activeList = if (currentTab == DashboardTab.Pinned) lazyPinned else lazyLinks
        val currentIndex = detailLinkIndex(activeList.itemSnapshotList, id)
        // Touching the current index lets Paging prefetch around it
        // (prefetchDistance = 90), so a long swipe streak keeps finding
        // neighbours instead of stopping dead at the loaded edge.
        // Return value intentionally unused - the read itself is the point.
        if (currentIndex >= 0) activeList[currentIndex]

        // Observe the LIVE entity so refreshes / background classification
        // update the open sheet; survives rotation because only the id is
        // saved. If the link is deleted underneath, the sheet closes.
        val observedLink by remember(id) { viewModel.observeLink(id) }
            .collectAsStateWithLifecycle(initialValue = null)
        // Hold the last loaded entity. observeLink restarts at null on every
        // id change, and letting LinkDetailSheet leave composition for that
        // one frame replays the ModalBottomSheet slide-up enter animation on
        // every single swipe. Deliberately NOT keyed on id.
        var shownLink by remember { mutableStateOf<LinkEntity?>(null) }
        var everLoaded by remember(id) { mutableStateOf(false) }
        LaunchedEffect(observedLink) {
            val loaded = observedLink
            if (loaded != null) {
                everLoaded = true
                shownLink = loaded
            } else if (everLoaded) {
                selectedLinkId = null
            }
        }
        shownLink?.let { link ->
            LaunchedEffect(link.id, link.relatedLinksJson, link.description, uiState.isProcessing) {
                val urls = dev.punit.tidylink.data.scraper.availableRelatedLinks(
                    link.relatedLinksJson, link.description, link.url, link.resolvedUrl,
                ).map { it.url }.toSet()
                val saved = urls.filter { viewModel.findSavedLink(it) != null }.toSet()
                savedRelatedUrls = (savedRelatedUrls - urls) + saved
            }
            AnimatedVisibility(
                visible = detailVisible,
                enter = slideInHorizontally(Motion.spatialSpring()) { it / 8 } +
                    fadeIn(tween(Motion.FADE_IN_MS, easing = Motion.EnterEasing)),
                exit = slideOutHorizontally(tween(Motion.DURATION_MEDIUM)) { it / 8 } +
                    fadeOut(tween(Motion.FADE_OUT_MS, easing = Motion.ExitEasing)),
                modifier = Modifier.fillMaxSize(),
            ) {
            LinkDetailSheet(
                link = link,
                isBusy = link.id in uiState.refreshingIds,
                pageSwipeNavigation = pageSwipeNavigation,
                hasPrev = detailNeighborIndex(currentIndex, -1, activeList.itemCount) != null,
                hasNext = detailNeighborIndex(currentIndex, 1, activeList.itemCount) != null,
                onNavigate = { direction ->
                    detailNeighborIndex(currentIndex, direction, activeList.itemCount)
                        ?.let { target -> activeList[target]?.let { selectedLinkId = it.id } }
                },
                onDismiss = {
                    if (detailHistory.isNotEmpty()) {
                        selectedLinkId = detailHistory.last()
                        detailHistory = ArrayList(detailHistory.dropLast(1))
                    } else {
                        detailVisible = false
                        scope.launch {
                            delay(Motion.DURATION_MEDIUM.toLong())
                            if (!detailVisible) selectedLinkId = null
                        }
                    }
                },
                onOpen = { openLink(context, link.url) },
                // Keep the sheet open: updated details animate in place.
                onRefresh = { viewModel.refreshLink(link) },
                onDelete = {
                    pendingConfirm = deleteConfirm(1) {
                        detailVisible = false
                        selectedLinkId = null
                        viewModel.deleteLink(link)
                    }
                },
                onEdit = { editingLinkId = link.id },
                onTogglePin = { viewModel.togglePin(link) },
                onOpenRelated = { url ->
                    scope.launch {
                        val saved = viewModel.findSavedLink(url)
                        if (saved != null && saved.id != link.id) {
                            detailHistory = ArrayList(detailHistory + link.id)
                            selectedLinkId = saved.id
                        } else {
                            openLink(context, url)
                        }
                    }
                },
                savedRelatedUrls = savedRelatedUrls,
                savingRelatedUrls = savingRelatedUrls,
                feedback = { SnackbarHost(snackbarHostState) },
                onSaveRelated = { url ->
                    savingRelatedUrls = savingRelatedUrls + url
                    viewModel.processAndSaveUrl(url) { saved ->
                        savingRelatedUrls = savingRelatedUrls - url
                        if (saved) savedRelatedUrls = savedRelatedUrls + url
                    }
                },
                onImageFailed = { failed -> viewModel.recoverThumbnail(failed) },
                onReaderMode = { url ->
                    showReaderMode = true
                    isReaderLoading = true
                    viewModel.extractReaderArticle(url) { article ->
                        readerArticle = article
                        isReaderLoading = false
                    }
                },
                onWayback = { url ->
                    scope.launch {
                        snackbarHostState.showSnackbar("Checking Wayback Machine…")
                        viewModel.checkWaybackForLink(url) { result ->
                            if (result?.isArchived == true && result.snapshotUrl != null) {
                                openLink(context, result.snapshotUrl)
                            } else {
                                openLink(context, "https://web.archive.org/save/$url")
                            }
                        }
                    }
                },
                onCheckSafety = { url ->
                    scope.launch {
                        snackbarHostState.showSnackbar("Scanning URLhaus…")
                        viewModel.checkSafetyForLink(url) { result ->
                            val msg = if (result?.isMalicious == true) {
                                "Threat detected: ${result.threat}"
                            } else {
                                "Safe: not flagged on URLhaus"
                            }
                            scope.launch {
                                snackbarHostState.showSnackbar(msg)
                            }
                        }
                    }
                },
            )
            }
        }
    }

    uiState.deadLinksResults?.let { results ->
        DeadLinksDialog(
            results = results,
            onDismiss = viewModel::dismissDeadLinksDialog,
            onOpenUrl = { openLink(context, it) },
            onReplaceWithSnapshot = { linkId, snapUrl ->
                viewModel.replaceWithWaybackSnapshot(linkId, snapUrl)
            },
            onDeleteLink = { link ->
                viewModel.deleteLink(link)
            },
        )
    }

    uiState.safetyScanResults?.let { results ->
        SafetyScanDialog(
            results = results,
            onDismiss = viewModel::dismissSafetyScanDialog,
            onDeleteLink = { link ->
                viewModel.deleteLink(link)
            },
        )
    }

    if (showReaderMode) {
        ReaderModeSheet(
            article = readerArticle,
            isLoading = isReaderLoading,
            onDismiss = {
                showReaderMode = false
                readerArticle = null
            },
        )
    }
}
