package dev.punit.tidylink.ui.dashboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.punit.tidylink.R
import dev.punit.tidylink.data.repository.LinkRepository
import dev.punit.tidylink.data.repository.TrashedLink
import java.util.concurrent.TimeUnit

/**
 * Deleted links, recoverable for 90 days. A full screen, not a sheet.
 *
 * It was a `ModalBottomSheet` whose list was capped at `heightIn(max =
 * 400.dp)` - roughly three cards - inside a sheet that itself only covered
 * part of the screen. Trash holds up to 90 days of deletions, and the one
 * thing people come here to do is find a specific link they regret, so the
 * cap was fighting the feature.
 *
 * Rendered as an overlay by `DashboardScreen` rather than as a navigation
 * destination: the app has no navigation library, and adding one to reach a
 * single screen would be a dependency for a `Boolean`.
 *
 * It is NOT a modal window, so it deliberately does not blur the backdrop
 * the way the sheets do - a full-screen page covers what it would be
 * blurring, so the blur would be a per-frame cost with nothing to show for
 * it. The delete-forever and empty-trash confirmations raised from here are
 * still dialogs and still blur.
 *
 * Shows days remaining rather than the deletion date: "deleted 12 March"
 * needs mental arithmetic against a retention period the user doesn't
 * know, whereas "3 days left" is the thing they actually need to act on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrashScreen(
    trashed: List<TrashedLink>,
    onRestore: (List<String>) -> Unit,
    onDeleteForever: (List<String>) -> Unit,
    onEmptyTrash: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onClose)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.sheet_trash_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.sheet_trash_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                actions = {
                    if (trashed.isNotEmpty()) {
                        TextButton(onClick = onEmptyTrash) {
                            Text(
                                text = stringResource(R.string.action_empty_trash),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        if (trashed.isEmpty()) {
            EmptyState(
                text = stringResource(R.string.trash_empty),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
            return@Scaffold
        }

        // Unbounded, unlike the sheet it replaces: the whole point of the
        // page is that a long trash is scrollable.
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding(),
        ) {
            items(trashed, key = { it.link.id }, contentType = { "trash" }) { item ->
                TrashRow(
                    item = item,
                    onRestore = { onRestore(listOf(item.link.id)) },
                    onDeleteForever = { onDeleteForever(listOf(item.link.id)) },
                )
            }
        }
    }
}

/**
 * Same card as the library grid ([LinkCardBody]), plus what trash needs on
 * top: the days-left line and the restore / delete-forever buttons. No
 * selection, no refresh spinner, no pin, no swipe - trash has none of
 * those, so [LinkCardBody] is called with no thumbnail overlay.
 */
@Composable
private fun TrashRow(
    item: TrashedLink,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        LinkCardBody(link = item.link)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, bottom = 6.dp),
        ) {
            Text(
                text = pluralStringResource(
                    R.plurals.trash_days_left,
                    daysLeft(item.deletedAt),
                    daysLeft(item.deletedAt),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRestore) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.action_restore),
                )
            }
            IconButton(onClick = onDeleteForever) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.action_delete_forever),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * Never returns less than 1: the purge runs at app start, so anything
 * still visible here has at least the rest of today. Showing "0 days left"
 * next to a link that is plainly still listed would just look broken.
 */
private fun daysLeft(deletedAt: Long): Int {
    val elapsed = System.currentTimeMillis() - deletedAt
    val remaining = LinkRepository.TRASH_RETENTION_DAYS - TimeUnit.MILLISECONDS.toDays(elapsed)
    return remaining.coerceAtLeast(1L).toInt()
}
