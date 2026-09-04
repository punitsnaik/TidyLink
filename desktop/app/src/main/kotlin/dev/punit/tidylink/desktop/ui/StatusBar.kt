package dev.punit.tidylink.desktop.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.punit.tidylink.shared.sync.SyncStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Bottom bar: current sync state, last successful sync, and a manual trigger. */
@Composable
internal fun StatusBar(status: SyncStatus, onSyncNow: () -> Unit) {
    val timeFormat = remember { DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()) }
    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val primary = when {
                status.syncingWith != null -> "Syncing with ${status.syncingWith}..."
                status.listeningPort != null -> "Listening on port ${status.listeningPort}"
                else -> "Sync starting..."
            }
            Text(primary, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            status.lastSync?.let {
                Text(
                    "Last sync: ${it.peerName}, ${timeFormat.format(Instant.ofEpochMilli(it.at))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
            }
            TextButton(onClick = onSyncNow) { Text("Sync now") }
        }
    }
}
