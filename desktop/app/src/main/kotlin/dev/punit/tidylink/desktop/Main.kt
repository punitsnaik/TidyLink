package dev.punit.tidylink.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.punit.tidylink.desktop.ui.EditDialog
import dev.punit.tidylink.desktop.ui.LinkList
import dev.punit.tidylink.desktop.ui.PairingDialog
import dev.punit.tidylink.desktop.ui.StatusBar
import dev.punit.tidylink.shared.db.Link
import kotlinx.coroutines.launch

fun main() = application {
    val appState = remember { AppState() }
    Window(
        onCloseRequest = {
            appState.shutdown()
            exitApplication()
        },
        title = "TidyLink",
        state = rememberWindowState(width = 960.dp, height = 700.dp),
    ) {
        App(appState)
    }
}

@Composable
private fun App(appState: AppState) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
    ) {
        val links by appState.links.collectAsState(initial = emptyList())
        val peers by appState.peers.collectAsState(initial = emptyList())
        val status by appState.status.collectAsState()
        val query by appState.searchQuery.collectAsState()
        val scope = appState.scope

        var editTarget by remember { mutableStateOf<Link?>(null) }
        var showEdit by remember { mutableStateOf(false) }
        var showPairing by remember { mutableStateOf(false) }

        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { appState.searchQuery.value = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("Search") },
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { editTarget = null; showEdit = true }) { Text("+ Add") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { showPairing = true }) { Text("Pair device") }
                }
                LinkList(
                    links = links,
                    searchActive = query.isNotBlank(),
                    onOpen = appState::openInBrowser,
                    onTogglePin = { scope.launch { appState.togglePin(it) } },
                    onEdit = { editTarget = it; showEdit = true },
                    onDelete = { scope.launch { appState.trashLink(it) } },
                    modifier = Modifier.weight(1f),
                )
                StatusBar(status = status, onSyncNow = { appState.syncNow() })
            }
        }

        if (showEdit) {
            val target = editTarget
            EditDialog(
                existing = target,
                onSave = { url, title, category, note ->
                    scope.launch { appState.saveLink(target, url, title, category, note) }
                    showEdit = false
                },
                onDismiss = { showEdit = false },
            )
        }
        if (showPairing) {
            PairingDialog(
                server = appState.server,
                peers = peers,
                onRemove = { scope.launch { appState.removePeer(it) } },
                onDismiss = { showPairing = false },
            )
        }
    }
}
