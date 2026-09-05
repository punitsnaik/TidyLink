package dev.punit.tidylink.ui.dashboard

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.punit.tidylink.R
import dev.punit.tidylink.sync.SyncOutcome
import kotlinx.coroutines.launch

/**
 * Full page, same convention as [TrashScreen]: opaque, drawn last, not a
 * navigation destination (the app has no navigation library). Not part of
 * `modalOpen` for the same reason - it covers what the backdrop blur would
 * blur, so blurring behind it would be a per-frame cost drawing nothing
 * anyone can see.
 *
 * Not device-tested: zxing-android-embedded's own capture Activity handles
 * the CAMERA permission prompt, but that flow (and the scanned result
 * actually parsing against the desktop app's QR) needs a real device pair to
 * confirm - see `project-docs/PRD-android-sync.md`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PairDeviceScreen(
    onScan: suspend (String) -> SyncOutcome,
    onSyncAll: suspend () -> List<SyncOutcome>,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            when (val outcome = onScan(text)) {
                is SyncOutcome.Success -> status = "Paired with ${outcome.peerName}"
                is SyncOutcome.Failure -> status = outcome.message
            }
            busy = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pair_device_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { padding: PaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.pair_device_scan_instructions),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    scanLauncher.launch(
                        ScanOptions()
                            .setOrientationLocked(false)
                            .setBeepEnabled(false)
                    )
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.pair_device_scan_button))
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    busy = true
                    status = null
                    scope.launch {
                        val results = onSyncAll()
                        status = results.joinToString("\n") {
                            when (it) {
                                is SyncOutcome.Success -> "Synced with ${it.peerName}"
                                is SyncOutcome.Failure -> it.message
                            }
                        }.ifBlank { null }
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.pair_device_sync_now))
            }
            if (busy) {
                Spacer(Modifier.height(16.dp))
                CircularProgressIndicator()
            }
            status?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
