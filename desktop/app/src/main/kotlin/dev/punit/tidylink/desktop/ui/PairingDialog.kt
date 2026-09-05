package dev.punit.tidylink.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.punit.tidylink.desktop.QrImage
import dev.punit.tidylink.shared.db.Peer
import dev.punit.tidylink.shared.sync.SyncServer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Shows the pairing QR (one token per dialog open) and the live paired-device
 * list. A peer whose addedAt lands after the dialog opened is the one this QR
 * just paired - celebrate it inline.
 */
@Composable
internal fun PairingDialog(
    server: SyncServer,
    peers: List<Peer>,
    onRemove: (deviceId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val info = remember { server.beginPairing() }
    // An unused QR dies with its dialog - whatever path closed it.
    DisposableEffect(Unit) { onDispose { server.cancelPairing() } }
    val qrJson = remember { info.qrJson() }
    val openedAt = remember { System.currentTimeMillis() }
    val newlyPaired = peers.filter { it.addedAt >= openedAt }.maxByOrNull { it.addedAt }
    val dateFormat = remember { DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.systemDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pair device") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QrImage(qrJson)
                // Debug aid for the Android implementer - where this QR points.
                Text(
                    "${info.host}:${info.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Scan with TidyLink on your phone (Settings -> Pair device)")
                if (newlyPaired != null) {
                    Text("Paired with ${newlyPaired.name}!", color = Color(0xFF2E7D32))
                }
                if (peers.isNotEmpty()) {
                    HorizontalDivider()
                    peers.forEach { peer ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(peer.name)
                                Text(
                                    "Added ${dateFormat.format(Instant.ofEpochMilli(peer.addedAt))}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { onRemove(peer.deviceId) }) { Text("✕") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
