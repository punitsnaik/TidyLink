package dev.punit.tidylink.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import dev.chrisbanes.haze.HazeState
import dev.punit.tidylink.R
import dev.punit.tidylink.data.local.LinkEntity
import dev.punit.tidylink.data.settings.LibraryViewMode
import dev.punit.tidylink.ui.LinkUiState
import dev.punit.tidylink.ui.LinkViewModel
import dev.punit.tidylink.ui.theme.Motion
import kotlinx.coroutines.launch

/** Gutter shared by the grid's contentPadding and the empty-state header. */
private val HEADER_GUTTER = 16.dp

/** Height reserved for the floating glass search bar overlaying the grid. */
internal val SEARCH_OVERLAY_HEIGHT = 72.dp

/**
 * The Links tab: a Box where the link grid fills the space and scrolls
 * underneath a floating glass search bar owned by DashboardScreen. The grid
 * clears the bar with a measured top content padding (the bar's real height,
 * not an asserted constant), and its FIRST ITEM is the header (title row,
 * provider banner, search, result
 * count, category tiles). The progress bar is a separate overlay, pinned
 * just below the search bar on an opaque surface so it does not blend into
 * whatever the grid is scrolling underneath it.
 *
 * The header is a grid item on purpose, not a collapsing block above the
 * grid. It used to be an AnimatedVisibility driven by nested-scroll deltas,
 * which animated the header's HEIGHT - so it snapped away on its own clock
 * instead of following the finger, and forced the whole grid to re-measure
 * on every frame of the collapse. As an item it scrolls 1:1 with the
 * content, with no animation to stutter. Cost of the trade: the header
 * returns when you scroll back to the top, not on any small upward flick.
 */
@Composable
internal fun LinksTab(
    viewModel: LinkViewModel,
    uiState: LinkUiState,
    query: String,
    lazyLinks: LazyPagingItems<LinkEntity>,
    hasProviders: Boolean,
    providerBannerDismissed: Boolean,
    onDismissProviderBanner: () -> Unit,
    gridState: LazyGridState,
    viewMode: LibraryViewMode,
    cardRefreshSwipe: Boolean,
    cardDeleteSwipe: Boolean,
    onShowSortSheet: () -> Unit,
    onToggleViewMode: () -> Unit,
    onShowAiProviders: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onRequestDelete: (LinkEntity) -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    // Selection mode swaps in the Scaffold's contextual TopAppBar, so the
    // header stands down entirely - null, not an empty item, to keep the
    // grid's index mapping honest for the fast scroller.
    val header: (@Composable () -> Unit)? = if (uiState.isSelectionMode) {
        null
    } else {
        {
            LinksHeader(
                viewModel = viewModel,
                uiState = uiState,
                query = query,
                lazyLinks = lazyLinks,
                hasProviders = hasProviders,
                providerBannerDismissed = providerBannerDismissed,
                onDismissProviderBanner = onDismissProviderBanner,
                onShowSortSheet = onShowSortSheet,
                viewMode = viewMode,
                onToggleViewMode = onToggleViewMode,
                onShowAiProviders = onShowAiProviders,
            )
        }
    }

    Box(modifier = modifier) {
        val density = LocalDensity.current
        var progressBandHeight by remember { mutableStateOf(0.dp) }
        val bandVisible = uiState.isProcessing || uiState.pendingEnrichment > 0
        val bandExtra = if (bandVisible) progressBandHeight else 0.dp
        val contentTop = (if (uiState.isSelectionMode) 12.dp else 8.dp) + bandExtra

        val listIsEmpty = lazyLinks.itemCount == 0 &&
            lazyLinks.loadState.refresh !is LoadState.Loading
        if (listIsEmpty) {
            Column(modifier = Modifier.padding(top = contentTop)) {
                header?.let {
                    Column(modifier = Modifier.padding(horizontal = HEADER_GUTTER)) { it() }
                }
                EmptyState(
                    text = stringResource(
                        if (query.isNotBlank() || uiState.selectedCategory != null) {
                            R.string.empty_filtered
                        } else {
                            R.string.empty_no_links
                        }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        } else {
            LinksGrid(
                lazyLinks = lazyLinks,
                gridState = gridState,
                // Three fields, not the whole state - see LinksGrid's KDoc.
                selectedIds = uiState.selectedIds,
                refreshingIds = uiState.refreshingIds,
                isSelectionMode = uiState.isSelectionMode,
                viewMode = viewMode,
                cardRefreshSwipe = cardRefreshSwipe,
                cardDeleteSwipe = cardDeleteSwipe,
                onToggleSelection = viewModel::toggleSelection,
                onRefreshLink = viewModel::refreshLink,
                onImageFailed = viewModel::recoverThumbnail,
                onOpenDetail = onOpenDetail,
                onRequestDelete = onRequestDelete,
                header = header,
                topPadding = contentTop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Progress overlays the grid just below the search bar - thin, and
        // only present while work is running.
        AnimatedVisibility(
            visible = bandVisible,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
            modifier = Modifier.padding(top = 0.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .onSizeChanged {
                        progressBandHeight = with(density) { it.height.toDp() }
                    }
                    .padding(horizontal = 16.dp, vertical = 2.dp),
            ) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
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

        // Floating search icon: appears when the header (grid index 0)
        // scrolls off-screen so search stays reachable without scrolling
        // back to the top. Uses derivedStateOf so only a boolean is
        // recomposed, not a counter on every scroll pixel.
        val scope = rememberCoroutineScope()
        val headerScrolledAway by remember {
            derivedStateOf { gridState.firstVisibleItemIndex > 0 }
        }
        val showSearchIcon = headerScrolledAway && !uiState.isSelectionMode

        AnimatedVisibility(
            visible = showSearchIcon,
            enter = fadeIn(tween(Motion.FADE_IN_MS, easing = Motion.EnterEasing)) +
                scaleIn(
                    initialScale = 0.6f,
                    animationSpec = tween(Motion.DURATION_MEDIUM, easing = Motion.EnterEasing),
                ),
            exit = fadeOut(tween(Motion.FADE_OUT_MS, easing = Motion.ExitEasing)) +
                scaleOut(
                    targetScale = 0.6f,
                    animationSpec = tween(Motion.FADE_OUT_MS, easing = Motion.ExitEasing),
                ),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 8.dp, end = 16.dp),
        ) {
            GlassSurface(
                hazeState = hazeState,
                shape = CircleShape,
                elevation = 6.dp,
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(role = Role.Button) {
                            scope.launch { gridState.animateScrollToItem(0) }
                        },
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = stringResource(R.string.action_search),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/**
 * Header content. Carries NO horizontal gutter of its own - inside the grid
 * that comes from contentPadding, and the empty-state branch adds it. Two
 * gutters would double-indent the search field.
 */
@Composable
private fun LinksHeader(
    viewModel: LinkViewModel,
    uiState: LinkUiState,
    query: String,
    lazyLinks: LazyPagingItems<LinkEntity>,
    hasProviders: Boolean,
    providerBannerDismissed: Boolean,
    onDismissProviderBanner: () -> Unit,
    onShowSortSheet: () -> Unit,
    viewMode: LibraryViewMode,
    onToggleViewMode: () -> Unit,
    onShowAiProviders: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        // Title row replaces the old pinned TopAppBar so it scrolls away
        // with the rest of the header.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onToggleViewMode) {
                Icon(
                    if (viewMode == LibraryViewMode.ADAPTIVE) AdaptiveViewIcon else Icons.AutoMirrored.Filled.List,
                    contentDescription = stringResource(
                        if (viewMode == LibraryViewMode.ADAPTIVE) {
                            R.string.action_compact_view
                        } else {
                            R.string.action_adaptive_view
                        }
                    ),
                )
            }
            IconButton(onClick = onShowSortSheet) {
                Icon(
                    SortIcon,
                    contentDescription = stringResource(R.string.action_sort),
                )
            }
        }

        SearchBar(
            query = query,
            onQueryChange = viewModel::search,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )

        // First-run guidance: links exist but AI categorization is off
        // because no provider key was ever added.
        if (!hasProviders && !providerBannerDismissed && lazyLinks.itemCount > 0) {
            AddProviderBanner(
                onAdd = onShowAiProviders,
                onDismiss = onDismissProviderBanner,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        Spacer(Modifier.height(4.dp))

        if (uiState.categories.isNotEmpty()) {
            CategoryTiles(
                categories = uiState.categories,
                selected = uiState.selectedCategory,
                onSelect = viewModel::selectCategory,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        ResultsHeader(lazyLinks.loadState.refresh, lazyLinks.itemCount,
            query.isNotBlank() || uiState.selectedCategory != null)
    }
}

@Composable
internal fun ResultsHeader(refresh: LoadState, count: Int, filtered: Boolean) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp)) {

        // A failed query must not read as "0 results" - that's how a broken
        // search query hid in plain sight.
        if (refresh is LoadState.Error) {
            Text(
                text = stringResource(R.string.msg_load_failed),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        } else if (refresh !is LoadState.Loading) {
            Text(
                text = pluralStringResource(
                    if (!filtered) {
                        R.plurals.link_count
                    } else {
                        R.plurals.search_results
                    },
                    count,
                    count,
                ),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { heading() },
            )
        }

    }
}

private val AdaptiveViewIcon = ImageVector.Builder("AdaptiveView", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(3f, 3f); horizontalLineTo(21f); verticalLineTo(12f); horizontalLineTo(3f); close()
        moveTo(3f, 14f); horizontalLineTo(21f); verticalLineTo(16f); horizontalLineTo(3f); close()
        moveTo(3f, 18f); horizontalLineTo(9f); verticalLineTo(22f); horizontalLineTo(3f); close()
        moveTo(11f, 18f); horizontalLineTo(21f); verticalLineTo(20f); horizontalLineTo(11f); close()
    }
}.build()
