package dev.punit.tidylink.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import dev.punit.tidylink.R
import dev.punit.tidylink.data.local.LinkEntity
import dev.punit.tidylink.ui.LinkUiState
import dev.punit.tidylink.ui.LinkViewModel

/** Gutter shared by the grid's contentPadding and the empty-state header. */
private val HEADER_GUTTER = 16.dp

/**
 * The Links tab: a pinned progress bar, then the link grid whose FIRST ITEM
 * is the header (title row, provider banner, search, result count, category
 * tiles).
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
    onShowSortSheet: () -> Unit,
    onShowToolsSheet: () -> Unit,
    onShowAiProviders: () -> Unit,
    onOpenDetail: (String) -> Unit,
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

    Column(modifier = modifier) {
        // One bar covers both foreground work (save/import) and background
        // enrichment - the two used to be separate blocks, which stacked
        // into a double bar whenever a save overlapped a bulk-import sweep.
        // Pinned above the scrolling header so activity stays visible no
        // matter where the list is. Animated in/out so the list doesn't jump.
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
            // No grid to host the header, but search and the category
            // filter must stay reachable - that's the only way back from a
            // filter that matches nothing. Supplies its own gutter, which
            // the grid otherwise contributes via contentPadding.
            header?.let {
                Column(modifier = Modifier.padding(horizontal = HEADER_GUTTER)) { it() }
            }
            EmptyState(
                text = stringResource(
                    if (query.isNotBlank() ||
                        uiState.selectedCategory != null ||
                        uiState.selectedTag != null ||
                        uiState.unreadOnly
                    ) {
                        R.string.empty_filtered
                    } else {
                        R.string.empty_no_links
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        } else {
            LinksGrid(
                lazyLinks = lazyLinks,
                gridState = gridState,
                uiState = uiState,
                viewModel = viewModel,
                onOpenDetail = onOpenDetail,
                header = header,
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
            )
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

        SearchBar(
            query = query,
            onQueryChange = viewModel::search,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        )

        // A failed query must not read as "0 results" - that's how a broken
        // search query hid in plain sight.
        if (lazyLinks.loadState.refresh is LoadState.Error) {
            Text(
                text = stringResource(R.string.msg_load_failed),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 4.dp),
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

        // Hidden only on a genuinely empty library - once links exist the
        // unread chip is worth showing even before anything has tags. Held
        // open while a filter is active, or the row that hides the grid's
        // contents would vanish along with them.
        val hasFilterableContent = lazyLinks.itemCount > 0 ||
            uiState.tags.isNotEmpty() ||
            uiState.unreadOnly ||
            uiState.selectedTag != null
        if (hasFilterableContent) {
            FilterRow(
                tags = uiState.tags,
                selected = uiState.selectedTag,
                unreadOnly = uiState.unreadOnly,
                onSelect = viewModel::selectTag,
                onUnreadOnlyChange = viewModel::setUnreadOnly,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
    }
}
