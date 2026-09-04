package dev.punit.tidylink.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.punit.tidylink.shared.db.Link

/**
 * Add/edit dialog. The url field is disabled when editing an existing link -
 * the URL is identity-ish; changing it is a delete + add.
 */
@Composable
internal fun EditDialog(
    existing: Link?,
    onSave: (url: String, title: String, category: String, note: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf(existing?.url ?: "") }
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var category by remember { mutableStateOf(existing?.category ?: "") }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    val valid = url.trim().let { it.startsWith("http://") || it.startsWith("https://") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add link" else "Edit link") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL") },
                    singleLine = true,
                    enabled = existing == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Category") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(url.trim(), title.trim(), category.trim(), note.trim()) },
                enabled = valid,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
