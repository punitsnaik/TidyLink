package dev.punit.tidylink.ui.dashboard

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import dev.punit.tidylink.R
import dev.punit.tidylink.ui.LinkViewModel
import dev.punit.tidylink.ui.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Public repository - linked from Settings → About. */
private const val REPO_URL = "https://github.com/punitsnaik/TidyLink"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: LinkViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val query by viewModel.searchQueryInput.collectAsStateWithLifecycle()
    val lazyLinks = viewModel.links.collectAsLazyPagingItems()
    val providers by viewModel.llmProviders.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
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
    var showToolsSheet by rememberSaveable { mutableStateOf(false) }
    var showAiProviders by rememberSaveable { mutableStateOf(false) }
    var showMoveDialog by rememberSaveable { mutableStateOf(false) }
    var showTidyConfirm by rememberSaveable { mutableStateOf(false) }
    var showDuplicatesConfirm by rememberSaveable { mutableStateOf(false) }
    var providerBannerDismissed by rememberSaveable { mutableStateOf(false) }
    // Ids (not entities) survive rotation/process death; the live entity is
    // observed from the DB below.
    var selectedLinkId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingLinkId by rememberSaveable { mutableStateOf<String?>(null) }

    // Grid states live here (not inside the tab branches) so scroll
    // positions survive switching tabs.
    val gridState = rememberLazyGridState()
    val pinnedGridState = rememberLazyGridState()

    // Auto-scroll to the top when a NEW link lands at the head of the list
    // (added manually, shared in, or imported) - but not on deletes.
    val firstLinkId = lazyLinks.itemSnapshotList.items.firstOrNull()?.id
    var lastTopId by remember { mutableStateOf(firstLinkId) }
    var lastCount by remember { mutableIntStateOf(lazyLinks.itemCount) }
    LaunchedEffect(firstLinkId, lazyLinks.itemCount) {
        val grew = lazyLinks.itemCount > lastCount
        val topChanged = firstLinkId != null && firstLinkId != lastTopId
        lastTopId = firstLinkId
        lastCount = lazyLinks.itemCount
        if (grew && topChanged) gridState.animateScrollToItem(0)
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
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
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
        scope.launch {
            val text = try {
                val count = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        viewModel.importLinks(stream)
                    } ?: -1
                }
                if (count >= 0) {
                    resources.getQuantityString(R.plurals.msg_imported_json, count, count)
                } else {
                    resources.getString(R.string.msg_import_invalid)
                }
            } catch (e: Exception) {
                resources.getString(R.string.msg_import_failed)
            }
            snackbarHostState.showSnackbar(text)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            if (uiState.isSelectionMode && currentTab != DashboardTab.Settings) {
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
                        IconButton(onClick = viewModel::deleteSelected) {
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
                                else -> stringResource(R.string.title_settings)
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
            SnackbarHost(
                snackbarHostState,
                modifier = Modifier.padding(bottom = 88.dp),
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
        when (currentTab) {
            DashboardTab.Links -> LinksTab(
                viewModel = viewModel,
                uiState = uiState,
                query = query,
                lazyLinks = lazyLinks,
                hasProviders = providers.isNotEmpty(),
                providerBannerDismissed = providerBannerDismissed,
                onDismissProviderBanner = { providerBannerDismissed = true },
                gridState = gridState,
                onShowSortSheet = { showSortSheet = true },
                onShowToolsSheet = { showToolsSheet = true },
                onShowAiProviders = { showAiProviders = true },
                onOpenDetail = { selectedLinkId = it },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            DashboardTab.Pinned -> {
                val lazyPinned = viewModel.pinnedLinks.collectAsLazyPagingItems()
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
                        uiState = uiState,
                        viewModel = viewModel,
                        onOpenDetail = { selectedLinkId = it },
                        animateEntrance = false,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    )
                }
            }

            DashboardTab.Settings -> SettingsTab(
                themeMode = themeMode,
                onThemeClick = { showThemeSheet = true },
                onAiProviders = { showAiProviders = true },
                onExport = { exportLauncher.launch("tidylink-backup.json") },
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

        BottomPillNav(
            currentTab = currentTab,
            onSelect = { currentTab = it },
            onAdd = { showAddDialog = true },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        )
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

    // Tidy-up is a bulk, non-undoable rename - always confirm first.
    if (showTidyConfirm) {
        AlertDialog(
            onDismissRequest = { showTidyConfirm = false },
            title = { Text(stringResource(R.string.tidy_confirm_title)) },
            text = { Text(stringResource(R.string.tidy_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showTidyConfirm = false
                        viewModel.tidyCategories()
                    },
                ) { Text(stringResource(R.string.tidy_confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = { showTidyConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    // Merging deletes rows. It keeps the richest copy and folds the others
    // into it, but it still can't be undone - state the count first.
    if (showDuplicatesConfirm) {
        AlertDialog(
            onDismissRequest = { showDuplicatesConfirm = false },
            title = { Text(stringResource(R.string.dialog_duplicates_title)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.dialog_duplicates_body,
                        uiState.duplicateCount,
                        uiState.duplicateCount,
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDuplicatesConfirm = false
                        viewModel.mergeDuplicates()
                    },
                ) { Text(stringResource(R.string.dialog_duplicates_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDuplicatesConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    editingLinkId?.let { id ->
        val editingLink by remember(id) { viewModel.observeLink(id) }
            .collectAsStateWithLifecycle(initialValue = null)
        editingLink?.let { link ->
            EditLinkDialog(
                link = link,
                onConfirm = { title, category, tags ->
                    viewModel.editLink(link, title, category, tags)
                    editingLinkId = null
                },
                onDismiss = { editingLinkId = null },
            )
        }
    }

    if (showToolsSheet) {
        ToolsSheet(
            isRefreshing = uiState.isRefreshing,
            duplicateCount = uiState.duplicateCount,
            onFetchMissingDetails = {
                showToolsSheet = false
                viewModel.refreshAll()
            },
            onTidyCategories = {
                showToolsSheet = false
                showTidyConfirm = true
            },
            onMergeDuplicates = {
                showToolsSheet = false
                showDuplicatesConfirm = true
            },
            onDismiss = { showToolsSheet = false },
        )
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
        // Observe the LIVE entity so refreshes / background classification
        // update the open sheet; survives rotation because only the id is
        // saved. If the link is deleted underneath, the sheet closes.
        val observedLink by remember(id) { viewModel.observeLink(id) }
            .collectAsStateWithLifecycle(initialValue = null)
        var everLoaded by remember(id) { mutableStateOf(false) }
        LaunchedEffect(observedLink) {
            if (observedLink != null) {
                everLoaded = true
            } else if (everLoaded) {
                selectedLinkId = null
            }
        }
        observedLink?.let { link ->
            LinkDetailSheet(
                link = link,
                isBusy = link.id in uiState.refreshingIds,
                onDismiss = { selectedLinkId = null },
                onOpen = {
                    selectedLinkId = null
                    openLink(context, link.url)
                },
                // Keep the sheet open: updated details animate in place.
                onRefresh = { viewModel.refreshLink(link) },
                onDelete = {
                    selectedLinkId = null
                    viewModel.deleteLink(link)
                },
                onEdit = { editingLinkId = link.id },
                onTogglePin = { viewModel.togglePin(link) },
                // Dismiss so the filtered library is actually visible -
                // leaving the sheet up would hide the result of the tap.
                // The sheet is reachable from Pinned too, and the tag
                // filter only drives the Links grid, so switch tabs or the
                // tap looks like it did nothing.
                onSelectTag = { tag ->
                    selectedLinkId = null
                    viewModel.selectTag(tag)
                    currentTab = DashboardTab.Links
                },
            )
        }
    }
}
