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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
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

/**
 * The Links tab: collapsing header (title row, provider banner, search,
 * result count, category tiles), progress bar, and the link grid.
 *
 * The header hides on scroll down and is revealed by any upward scroll.
 * Direction comes from the grid's nested scroll deltas. A list too short to
 * scroll never hides it, and reaching the top always restores it, so the
 * header can't get stuck off-screen.
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
    onShowAiProviders: () -> Unit,
    onOpenDetail: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var headerVisible by rememberSaveable { mutableStateOf(true) }
    val headerScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (available.y < -4f && gridState.canScrollForward) {
                    headerVisible = false
                } else if (available.y > 4f) {
                    headerVisible = true
                }
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(gridState.canScrollBackward) {
        if (!gridState.canScrollBackward) headerVisible = true
    }

    Column(
        modifier = modifier.nestedScroll(headerScrollConnection),
    ) {
        AnimatedVisibility(
            visible = headerVisible && !uiState.isSelectionMode,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column {
                // Title row replaces the old pinned TopAppBar so it can
                // collapse with the rest of the header.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp),
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

                // First-run guidance: links exist but AI categorization is
                // off because no provider key was ever added.
                AnimatedVisibility(
                    visible = !hasProviders &&
                        !providerBannerDismissed &&
                        lazyLinks.itemCount > 0,
                ) {
                    AddProviderBanner(
                        onAdd = onShowAiProviders,
                        onDismiss = onDismissProviderBanner,
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
                    CategoryTiles(
                        categories = uiState.categories,
                        selected = uiState.selectedCategory,
                        onSelect = viewModel::selectCategory,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
        }

        // One bar covers both foreground work (save/import) and background
        // enrichment - the two used to be separate blocks, which stacked
        // into a double bar whenever a save overlapped a bulk-import sweep.
        // Lives OUTSIDE the collapsing header so activity stays visible
        // while scrolling. Animated in/out so the list doesn't jump.
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
        } else {
            LinksGrid(
                lazyLinks = lazyLinks,
                gridState = gridState,
                uiState = uiState,
                viewModel = viewModel,
                onOpenDetail = onOpenDetail,
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
            )
        }
    }
}
