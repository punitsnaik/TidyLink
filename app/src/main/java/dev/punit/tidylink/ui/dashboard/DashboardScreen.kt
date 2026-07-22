package dev.punit.tidylink.ui.dashboard

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import dev.punit.tidylink.R
import dev.punit.tidylink.data.local.CategoryCount
import dev.punit.tidylink.data.local.SortOrder
import dev.punit.tidylink.ui.LinkViewModel
import dev.punit.tidylink.ui.UiMessage
import dev.punit.tidylink.ui.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Destinations reachable from the bottom navigation bar. */
private enum class DashboardTab { Links, Settings }

/** Cap on .txt bulk import - ~100k URLs, far past any real bookmark export. */
private const val MAX_IMPORT_BYTES = 5L * 1024 * 1024

/**
 * File size for a content:// URI, or null when the provider doesn't report one
 * (some don't). Null means "unknown", not "empty" - callers must not treat an
 * unknown size as a reason to block.
 */
private fun android.content.ContentResolver.fileSize(uri: android.net.Uri): Long? =
    query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
        if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
            cursor.getLong(index)
        } else {
            null
        }
    }

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
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    // LocalResources (not context.resources): stays correct across locale /
    // configuration changes, and satisfies the Compose lint check.
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var currentTab by rememberSaveable { mutableStateOf(DashboardTab.Links) }
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var showSortSheet by rememberSaveable { mutableStateOf(false) }
    var showAiProviders by rememberSaveable { mutableStateOf(false) }
    var showMoveDialog by rememberSaveable { mutableStateOf(false) }
    var showTidyConfirm by rememberSaveable { mutableStateOf(false) }
    var providerBannerDismissed by rememberSaveable { mutableStateOf(false) }
    // Ids (not entities) survive rotation/process death; the live entity is
    // observed from the DB below.
    var selectedLinkId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingLinkId by rememberSaveable { mutableStateOf<String?>(null) }

    // Grid state lives here (not inside the tab branch) so the scroll
    // position survives switching tabs.
    val gridState = rememberLazyGridState()

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

    // Import URLs from a plain-text file: every http(s) link found gets
    // queued through the normal scrape -> classify -> save pipeline.
    val importTxtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val text = try {
                // Size-check BEFORE reading. readText() allocates the whole file
                // as one String, and blowing the heap raises OutOfMemoryError -
                // an Error, not an Exception, so the catch below cannot save us;
                // the process just dies. Cheaper to refuse a silly file (a video
                // renamed to .txt) than to crash on it. 5MB is ~100k URLs.
                val tooBig = withContext(Dispatchers.IO) {
                    context.contentResolver.fileSize(uri)?.let { it > MAX_IMPORT_BYTES } == true
                }
                if (tooBig) {
                    snackbarHostState.showSnackbar(
                        resources.getString(R.string.msg_import_too_large)
                    )
                    return@launch
                }
                val fileText = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }
                }.orEmpty()
                viewModel.importUrlsFromText(fileText)
                // The ViewModel reports every outcome (none found, import
                // summary) through UiMessage - a second snackbar here would
                // duplicate it.
                null
            } catch (e: Exception) {
                resources.getString(R.string.msg_import_failed)
            }
            if (text != null) snackbarHostState.showSnackbar(text)
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
            if (uiState.isSelectionMode && currentTab == DashboardTab.Links) {
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
            } else {
                TopAppBar(
                    title = {
                        Text(
                            when (currentTab) {
                                DashboardTab.Links -> stringResource(R.string.app_name)
                                DashboardTab.Settings -> stringResource(R.string.title_settings)
                            }
                        )
                    },
                    // Sort and Refresh are ACTIONS, so they live in the app
                    // bar - not in the bottom navigation bar, which is for
                    // destinations only.
                    actions = {
                        if (currentTab == DashboardTab.Links) {
                            IconButton(onClick = { showSortSheet = true }) {
                                Icon(
                                    SortIcon,
                                    contentDescription = stringResource(R.string.action_sort),
                                )
                            }
                            IconButton(
                                onClick = viewModel::refreshAll,
                                enabled = !uiState.isRefreshing,
                            ) {
                                if (uiState.isRefreshing) {
                                    CircularProgressIndicator(
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(24.dp),
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = stringResource(R.string.action_refresh),
                                    )
                                }
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentTab == DashboardTab.Links,
                    onClick = { currentTab = DashboardTab.Links },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_links)) },
                )
                NavigationBarItem(
                    selected = currentTab == DashboardTab.Settings,
                    onClick = { currentTab = DashboardTab.Settings },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_settings)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (currentTab == DashboardTab.Links) {
                FloatingActionButton(
                    onClick = { showAddDialog = true },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.action_add_link))
                }
            }
        },
    ) { innerPadding ->
        when (currentTab) {
            DashboardTab.Links -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                // First-run guidance: links exist but AI categorization is
                // off because no provider key was ever added.
                AnimatedVisibility(
                    visible = providers.isEmpty() &&
                        !providerBannerDismissed &&
                        lazyLinks.itemCount > 0,
                ) {
                    AddProviderBanner(
                        onAdd = { showAiProviders = true },
                        onDismiss = { providerBannerDismissed = true },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }

                SearchBar(
                    query = query,
                    onQueryChange = viewModel::search,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )

                // A failed query must not read as "0 results" - that's how a
                // broken search query hid in plain sight.
                if (lazyLinks.loadState.refresh is LoadState.Error) {
                    Text(
                        text = stringResource(R.string.msg_load_failed),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                } else if (query.isNotBlank() && lazyLinks.loadState.refresh !is LoadState.Loading) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.search_results,
                            lazyLinks.itemCount,
                            lazyLinks.itemCount,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }

                AnimatedVisibility(visible = uiState.categories.isNotEmpty()) {
                    CategoryChips(
                        categories = uiState.categories,
                        selected = uiState.selectedCategory,
                        onSelect = viewModel::selectCategory,
                    )
                }

                // One bar covers both foreground work (save/import) and
                // background enrichment - the two used to be separate blocks,
                // which stacked into a double bar whenever a save overlapped a
                // bulk-import sweep. Animated in/out so the list doesn't jump.
                AnimatedVisibility(
                    visible = uiState.isProcessing || uiState.pendingEnrichment > 0,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        // Caption only when we know the remaining count.
                        if (uiState.pendingEnrichment > 0) {
                            Text(
                                text = pluralStringResource(
                                    R.plurals.banner_fetching_details,
                                    uiState.pendingEnrichment,
                                    uiState.pendingEnrichment,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp, start = 4.dp),
                            )
                        }
                    }
                }

                val listIsEmpty = lazyLinks.itemCount == 0 &&
                    lazyLinks.loadState.refresh !is LoadState.Loading
                if (listIsEmpty) {
                    EmptyState(
                        isFiltering = query.isNotBlank() || uiState.selectedCategory != null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                } else {
                    // Entrance stagger only applies to the first screenful on
                    // launch; cards composed later (while scrolling) must not
                    // re-animate or scrolling feels janky.
                    var animateInitialEntrance by remember { mutableStateOf(true) }
                    LaunchedEffect(Unit) {
                        delay(700)
                        animateInitialEntrance = false
                    }
                    // Adaptive grid: one column on phones, two-plus on
                    // tablets/landscape, without stretching cards too wide.
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(minSize = 340.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                    ) {
                        items(
                            count = lazyLinks.itemCount,
                            key = lazyLinks.itemKey { it.id },
                        ) { index ->
                            val link = lazyLinks[index] ?: return@items
                            LinkCard(
                                link = link,
                                selected = link.id in uiState.selectedIds,
                                index = index,
                                animateEntrance = animateInitialEntrance && index < 8,
                                showActions = !uiState.isSelectionMode,
                                isRefreshing = link.id in uiState.refreshingIds,
                                onRefresh = { viewModel.refreshLink(link) },
                                onDelete = { viewModel.deleteLink(link) },
                                onClick = {
                                    if (uiState.isSelectionMode) {
                                        viewModel.toggleSelection(link.id)
                                    } else {
                                        selectedLinkId = link.id
                                    }
                                },
                                onLongClick = { viewModel.toggleSelection(link.id) },
                                modifier = Modifier.animateItem(
                                    fadeInSpec = tween(220),
                                    fadeOutSpec = tween(180),
                                    placementSpec = spring(
                                        dampingRatio = Spring.DampingRatioLowBouncy,
                                        stiffness = Spring.StiffnessMediumLow,
                                    ),
                                ),
                            )
                        }
                    }
                }
            }

            DashboardTab.Settings -> SettingsTab(
                onAiProviders = { showAiProviders = true },
                onTidyCategories = { showTidyConfirm = true },
                onFetchMissingDetails = viewModel::refreshAll,
                isFetchingMissingDetails = uiState.isRefreshing,
                onExport = { exportLauncher.launch("tidylink-backup.json") },
                onImportJson = {
                    importLauncher.launch(
                        arrayOf("application/json", "application/octet-stream")
                    )
                },
                onImportTxt = {
                    importTxtLauncher.launch(
                        arrayOf("text/plain", "text/*", "application/octet-stream")
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
            )
        }
    }
}

/** Resolves a ViewModel [UiMessage] to localized text. */
@Composable
private fun resolveUiMessage(message: UiMessage): String = when (message) {
    is UiMessage.Text ->
        stringResource(message.res, *message.args.toTypedArray())
    is UiMessage.Plural ->
        pluralStringResource(message.res, message.quantity, *message.args.toTypedArray())
}

/** Dismissible banner nudging first-time users to enable AI categorization. */
@Composable
private fun AddProviderBanner(
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
            Text(
                text = stringResource(R.string.banner_add_provider_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.banner_add_provider_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_dismiss))
                }
                TextButton(onClick = onAdd) {
                    Text(stringResource(R.string.banner_add_provider_action))
                }
            }
        }
    }
}

/** Bottom sheet listing the sort options, current one check-marked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortSheet(
    current: SortOrder,
    onSelect: (SortOrder) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.sheet_sort_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            SortOrder.entries.forEach { order ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelect(order) }
                        .padding(horizontal = 4.dp, vertical = 14.dp),
                ) {
                    Text(
                        text = sortLabel(order),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (order == current) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                    )
                    if (order == current) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = stringResource(R.string.cd_selected),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/** UI label for a sort order (the enum itself is a data-layer type). */
@Composable
private fun sortLabel(order: SortOrder): String = stringResource(
    when (order) {
        SortOrder.NEWEST -> R.string.sort_newest
        SortOrder.OLDEST -> R.string.sort_oldest
        SortOrder.TITLE_AZ -> R.string.sort_title_az
        SortOrder.TITLE_ZA -> R.string.sort_title_za
        SortOrder.CATEGORY -> R.string.sort_category
    }
)

/**
 * Material "sort" glyph, built by hand because material-icons-core doesn't
 * ship one (and the extended artifact isn't worth the dependency).
 */
private val SortIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Sort",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            // Three left-aligned bars of decreasing width.
            moveTo(3f, 18f)
            horizontalLineToRelative(6f)
            verticalLineToRelative(-2f)
            horizontalLineTo(3f)
            close()
            moveTo(3f, 6f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(18f)
            verticalLineTo(6f)
            close()
            moveTo(3f, 13f)
            horizontalLineToRelative(12f)
            verticalLineToRelative(-2f)
            horizontalLineTo(3f)
            close()
        }
    }.build()
}

/**
 * Category filter that scales: only the busiest categories get a chip
 * (sorted by link count), and an "All categories" chip opens a bottom
 * sheet with the full list - no more endless horizontal swiping.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CategoryChips(
    categories: List<CategoryCount>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAllCategories by rememberSaveable { mutableStateOf(false) }
    val topCategories = categories.take(MAX_VISIBLE_CATEGORY_CHIPS)
    val hasOverflow = categories.size > topCategories.size

    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        item {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text(stringResource(R.string.chip_all)) },
            )
        }
        // Keep the active filter visible even when it isn't a top category.
        if (selected != null && topCategories.none { it.category == selected }) {
            item {
                FilterChip(
                    selected = true,
                    onClick = { onSelect(null) },
                    label = { Text(selected) },
                )
            }
        }
        items(topCategories, key = { it.category }) { cat ->
            FilterChip(
                selected = selected == cat.category,
                onClick = { onSelect(if (selected == cat.category) null else cat.category) },
                label = { Text(cat.category) },
            )
        }
        if (hasOverflow) {
            item {
                FilterChip(
                    selected = false,
                    onClick = { showAllCategories = true },
                    label = { Text(stringResource(R.string.chip_all_categories, categories.size)) },
                )
            }
        }
    }

    if (showAllCategories) {
        ModalBottomSheet(onDismissRequest = { showAllCategories = false }) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            ) {
                Text(
                    text = stringResource(R.string.sheet_filter_by_category),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    categories.forEach { cat ->
                        FilterChip(
                            selected = selected == cat.category,
                            onClick = {
                                onSelect(if (selected == cat.category) null else cat.category)
                                showAllCategories = false
                            },
                            label = {
                                Text(
                                    stringResource(
                                        R.string.chip_category_with_count,
                                        cat.category,
                                        cat.count,
                                    )
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

private const val MAX_VISIBLE_CATEGORY_CHIPS = 8

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        placeholder = { Text(stringResource(R.string.search_placeholder)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Default.Clear,
                        contentDescription = stringResource(R.string.action_clear_search),
                    )
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}

@Composable
private fun EmptyState(isFiltering: Boolean, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(
                if (isFiltering) R.string.empty_filtered else R.string.empty_no_links
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
