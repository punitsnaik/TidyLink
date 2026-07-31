package dev.punit.tidylink.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.punit.tidylink.R
import dev.punit.tidylink.data.UrlCanonicalizer
import dev.punit.tidylink.data.local.CategoryCount
import dev.punit.tidylink.data.local.LinkEntity

/** Manual edit of a link's title, category, tags, and personal note. */
@Composable
internal fun EditLinkDialog(
    link: LinkEntity,
    onConfirm: (title: String, category: String, tags: String, note: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by rememberSaveable(link.id) { mutableStateOf(link.title) }
    var category by rememberSaveable(link.id) { mutableStateOf(link.category) }
    var tags by rememberSaveable(link.id) { mutableStateOf(link.tags.joinToString(", ")) }
    var note by rememberSaveable(link.id) { mutableStateOf(link.note) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_edit_title)) },
        text = {
            // Scrollable: four fields (one of them multi-line) don't fit an
            // AlertDialog on a short screen or with a keyboard up.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.field_title)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text(stringResource(R.string.field_category)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text(stringResource(R.string.field_tags)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.field_note)) },
                    placeholder = { Text(stringResource(R.string.field_note_hint)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title, category, tags, note) },
                enabled = title.isNotBlank(),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Picks a target category for the current multi-selection. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MoveCategoryDialog(
    categories: List<CategoryCount>,
    count: Int,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var category by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.dialog_move_title, count, count)) },
        text = {
            Column {
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text(stringResource(R.string.field_category)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (categories.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        categories.take(12).forEach { cat ->
                            FilterChip(
                                selected = category == cat.category,
                                onClick = { category = cat.category },
                                label = { Text(cat.category) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(category) },
                enabled = category.isNotBlank(),
            ) { Text(stringResource(R.string.action_move)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * Dialog for adding a link manually. Pre-fills from the clipboard when it
 * contains something URL-shaped; validates before enabling Save so free
 * text can't become a permanently broken row.
 */
@Composable
internal fun AddLinkDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboard.current
    var url by rememberSaveable { mutableStateOf("") }
    val isValid = UrlCanonicalizer.isValidHttpUrl(url)
    val showError = url.isNotBlank() && !isValid

    // Pre-fill from the clipboard when it holds a URL (suspend API).
    LaunchedEffect(Unit) {
        if (url.isNotEmpty()) return@LaunchedEffect
        val clip = clipboard.getClipEntry()?.clipData
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.text?.toString()?.trim()
            .orEmpty()
        if (clip.startsWith("http")) url = clip
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_add_title)) },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                placeholder = { Text(stringResource(R.string.add_url_placeholder)) },
                singleLine = true,
                isError = showError,
                supportingText = if (showError) {
                    { Text(stringResource(R.string.add_url_invalid)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = { if (isValid) onConfirm(url.trim()) },
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(url.trim()) },
                enabled = isValid,
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
