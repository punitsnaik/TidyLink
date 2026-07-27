package dev.punit.tidylink.ui.dashboard

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import dev.punit.tidylink.data.local.LinkEntity
import dev.punit.tidylink.ui.LinkUiState
import dev.punit.tidylink.ui.LinkViewModel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The paged link grid, shared by the Links and Pinned tabs. */
@Composable
internal fun LinksGrid(
    lazyLinks: LazyPagingItems<LinkEntity>,
    gridState: LazyGridState,
    uiState: LinkUiState,
    viewModel: LinkViewModel,
    onOpenDetail: (String) -> Unit,
    modifier: Modifier = Modifier,
    animateEntrance: Boolean = true,
) {
    // Entrance stagger only applies to the first screenful on launch; cards
    // composed later (while scrolling) must not re-animate or scrolling
    // feels janky.
    var animateInitialEntrance by remember { mutableStateOf(animateEntrance) }
    LaunchedEffect(Unit) {
        delay(700)
        animateInitialEntrance = false
    }
    // Adaptive grid: one column on phones, two-plus on tablets/landscape,
    // without stretching cards too wide. The extra bottom padding keeps the
    // floating pill nav from covering the last row.
    Box(modifier = modifier) {
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = 340.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
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
                        onOpenDetail(link.id)
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

        // Bottom padding keeps the thumb clear of the floating "+" button.
        val monthFormat = remember { SimpleDateFormat("MMM yyyy", Locale.getDefault()) }
        FastScroller(
            gridState = gridState,
            itemCount = lazyLinks.itemCount,
            bubbleTextForIndex = { index ->
                lazyLinks.peek(index)?.timestamp?.let { monthFormat.format(Date(it)) }
            },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(top = 4.dp, bottom = 96.dp),
        )
    }
}
