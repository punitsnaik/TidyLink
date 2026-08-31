package dev.punit.tidylink.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import dev.punit.tidylink.R
import dev.punit.tidylink.data.local.LinkEntity
import dev.punit.tidylink.ui.LinkUiState
import dev.punit.tidylink.ui.LinkViewModel

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
    searchBarHeight: Dp,
    onShowSortSheet: () -> Unit,
    onShowToolsSheet: () -> Unit,
    onShowAiProviders: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onRequestDelete: (LinkEntity) -> Unit,
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
                onShowToolsSheet = onShowToolsSheet,
                onShowAiProviders = onShowAiProviders,
            )
        }
    }

    Box(modifier = modifier) {
        val density = LocalDensity.current
        var progressBandHeight by remember { mutableStateOf(0.dp) }
        val bandVisible = uiState.isProcessing || uiState.pendingEnrichment > 0
        val bandExtra = if (bandVisible) progressBandHeight else 0.dp
        val contentTop =
            (if (uiState.isSelectionMode) 12.dp else searchBarHeight + 8.dp) + bandExtra

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
            modifier = Modifier.padding(top = if (uiState.isSelectionMode) 0.dp else searchBarHeight),
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
    onShowToolsSheet: () -> Unit,
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
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onShowSortSheet) {
                Icon(
                    SortIcon,
                    contentDescription = stringResource(R.string.action_sort),
                )
            }
            // Tools, not Refresh: refresh moved inside the sheet alongside
            // tidy-up, because a bare refresh icon never said what it
            // refreshed and the same action was also duplicated in Settings.
            // Still shows a spinner while refreshing - that feedback was the
            // one good thing about the old icon.
            IconButton(onClick = onShowToolsSheet) {
                if (uiState.isRefreshing) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(24.dp),
                    )
                } else {
                    Icon(
                        TuneIcon,
                        contentDescription = stringResource(R.string.action_tools),
                    )
                }
            }
        }

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

        // A failed query must not read as "0 results" - that's how a broken
        // search query hid in plain sight.
        if (lazyLinks.loadState.refresh is LoadState.Error) {
            Text(
                text = stringResource(R.string.msg_load_failed),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        } else if (lazyLinks.loadState.refresh !is LoadState.Loading) {
            Text(
                text = pluralStringResource(
                    if (query.isBlank() && uiState.selectedCategory == null) {
                        R.plurals.link_count
                    } else {
                        R.plurals.search_results
                    },
                    lazyLinks.itemCount,
                    lazyLinks.itemCount,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        if (uiState.categories.isNotEmpty()) {
            CategoryTiles(
                categories = uiState.categories,
                selected = uiState.selectedCategory,
                onSelect = viewModel::selectCategory,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
    }
}
