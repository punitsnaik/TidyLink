package dev.punit.tidylink.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.punit.tidylink.R
import dev.punit.tidylink.data.repository.LinkRepository
import dev.punit.tidylink.data.repository.TrashedLink
import java.util.concurrent.TimeUnit

/**
 * Deleted links, recoverable for 90 days.
 *
 * Shows days remaining rather than the deletion date: "deleted 12 March"
 * needs mental arithmetic against a retention period the user doesn't
 * know, whereas "3 days left" is the thing they actually need to act on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrashSheet(
    trashed: List<TrashedLink>,
    onRestore: (List<String>) -> Unit,
    onDeleteForever: (List<String>) -> Unit,
    onEmptyTrash: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = glassSheetColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.sheet_trash_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.sheet_trash_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (trashed.isNotEmpty()) {
                    TextButton(onClick = onEmptyTrash) {
                        Text(
                            text = stringResource(R.string.action_empty_trash),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            if (trashed.isEmpty()) {
                Text(
                    text = stringResource(R.string.trash_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
                return@Column
            }

            // Bounded: emptying a large trash one row at a time is a real
            // use, and an unbounded column inside a sheet can't scroll.
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.heightIn(max = 400.dp),
            ) {
                items(trashed, key = { it.link.id }) { item ->
                    TrashRow(
                        item = item,
                        onRestore = { onRestore(listOf(item.link.id)) },
                        onDeleteForever = { onDeleteForever(listOf(item.link.id)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TrashRow(
    item: TrashedLink,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayTitle(item.link.title, item.link.url),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = pluralStringResource(
                    R.plurals.trash_days_left,
                    daysLeft(item.deletedAt),
                    daysLeft(item.deletedAt),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
