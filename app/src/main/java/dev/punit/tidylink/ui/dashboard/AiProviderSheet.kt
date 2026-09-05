package dev.punit.tidylink.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.punit.tidylink.R
import dev.punit.tidylink.data.settings.LlmProvider
import dev.punit.tidylink.data.settings.ProviderHealth

/**
 * Prefills for the supported OpenAI-compatible endpoints.
 *
 * Model IDs are pinned to currently-shipping releases and must be revisited
 * when a provider retires one - a stale ID surfaces as an HTTP 404 on every
 * call. [baseUrl] == null marks the "Custom" option, which reveals the URL
 * field instead of prefilling it.
 */
private data class ProviderPreset(
    val name: String,
    val baseUrl: String?,
    val model: String,
)

private val PRESETS = listOf(
    ProviderPreset(
        name = "Gemini",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai/",
        model = "gemini-3.1-flash-lite",
    ),
    ProviderPreset(
        name = "xAI Grok",
        baseUrl = "https://api.x.ai/v1/",
        model = "grok-4.5",
    ),
    ProviderPreset(name = "Custom", baseUrl = null, model = ""),
)

private val CUSTOM_PRESET = PRESETS.last()

/** Outcome of a "Test" press, rendered inline under the add form. */
private sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data object Success : TestState
    data class Failure(val reason: String) : TestState
}

/**
 * Bottom sheet for managing the LLM providers/API keys used for
 * categorization. Keys are stored encrypted and live only on the device.
 *
 * List order is fallback order: the first provider is tried first, and the
 * next takes over when it is rate-limited - hence the reorder controls.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AiProviderSheet(
    providers: List<LlmProvider>,
    health: Map<String, ProviderHealth>,
    onAdd: (name: String, baseUrl: String, model: String, apiKey: String) -> Unit,
    onRemove: (String) -> Unit,
    onMove: (id: String, up: Boolean) -> Unit,
    onTest: (
        name: String,
        baseUrl: String,
        model: String,
        apiKey: String,
        onResult: (String?) -> Unit,
    ) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedPreset by rememberSaveable { mutableStateOf(PRESETS.first().name) }
    var name by rememberSaveable { mutableStateOf(PRESETS.first().name) }
    var baseUrl by rememberSaveable { mutableStateOf(PRESETS.first().baseUrl.orEmpty()) }
    var model by rememberSaveable { mutableStateOf(PRESETS.first().model) }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var showKey by rememberSaveable { mutableStateOf(false) }
    var testState by remember { mutableStateOf<TestState>(TestState.Idle) }
    // Provider pending removal confirmation; null = no dialog.
    var confirmRemove by remember { mutableStateOf<LlmProvider?>(null) }

    val isCustom = selectedPreset == CUSTOM_PRESET.name
    val canSubmit = baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = glassSheetColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.provider_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.provider_sheet_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))

            if (providers.isEmpty()) {
                ProviderEmptyState()
            } else {
                providers.forEachIndexed { index, provider ->
                    ProviderCard(
                        provider = provider,
                        health = health[provider.id],
                        index = index,
                        total = providers.size,
                        onMoveUp = { onMove(provider.id, true) },
                        onMoveDown = { onMove(provider.id, false) },
                        onRemove = { confirmRemove = provider },
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (providers.size > 1) {
                    Text(
                        text = stringResource(R.string.provider_order_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text(
                text = stringResource(R.string.provider_add_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PRESETS.forEach { preset ->
                    FilterChip(
                        selected = selectedPreset == preset.name,
                        onClick = {
                            selectedPreset = preset.name
                            testState = TestState.Idle
                            name = if (preset.baseUrl == null) "" else preset.name
                            baseUrl = preset.baseUrl.orEmpty()
                            model = preset.model
                        },
                        label = {
                            Text(
                                if (preset.baseUrl == null) {
                                    stringResource(R.string.preset_custom)
                                } else {
                                    preset.name
                                }
                            )
                        },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // The URL is fixed for a known preset, so only Custom needs it -
            // hiding it keeps the most error-prone field off the common path.
            if (isCustom) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.field_name_optional)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = {
                        baseUrl = it
                        testState = TestState.Idle
                    },
                    label = { Text(stringResource(R.string.field_base_url)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
            }

            OutlinedTextField(
                value = model,
                onValueChange = {
                    model = it
                    testState = TestState.Idle
                },
                label = { Text(stringResource(R.string.field_model)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    testState = TestState.Idle
                },
                label = { Text(stringResource(R.string.field_api_key)) },
                singleLine = true,
                // Masked like a password: keys shouldn't sit readable in
                // screenshots or over-the-shoulder looks.
                visualTransformation = if (showKey) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password,
                ),
                trailingIcon = {
                    TextButton(onClick = { showKey = !showKey }) {
                        Text(
                            stringResource(
                                if (showKey) R.string.action_hide else R.string.action_show
                            )
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            TestResultLine(testState)

            Spacer(Modifier.height(12.dp))

            // Test sits next to Add so a bad key is caught at paste time,
            // rather than silently surfacing as "Failing" on a later sweep.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        testState = TestState.Running
                        onTest(name, baseUrl, model, apiKey) { reason ->
                            testState = if (reason == null) {
                                TestState.Success
                            } else {
                                TestState.Failure(reason)
                            }
                        }
                    },
                    enabled = canSubmit && testState != TestState.Running,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.action_test))
                }
                Button(
                    onClick = {
                        onAdd(name, baseUrl, model, apiKey)
                        // Reset only the key: the preset's name/url/model stay
                        // put so adding a second key for the same provider
                        // (to stack free-tier quota) is a paste-and-go.
                        apiKey = ""
                        showKey = false
                        testState = TestState.Idle
                    },
                    enabled = canSubmit,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.action_add_provider))
                }
            }
        }
    }

    confirmRemove?.let { provider ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(stringResource(R.string.provider_remove_confirm_title, provider.name)) },
            text = { Text(stringResource(R.string.provider_remove_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemove(provider.id)
                        confirmRemove = null
                    },
                ) {
                    Text(
                        stringResource(R.string.action_remove),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun ProviderEmptyState() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.provider_empty_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.provider_empty_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One provider: identity, fallback rank, health (with reason), controls. */
@Composable
private fun ProviderCard(
    provider: LlmProvider,
    health: ProviderHealth?,
    index: Int,
    total: Int,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, top = 10.dp, end = 4.dp, bottom = 10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = provider.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (total > 1) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (index == 0) {
                                stringResource(R.string.provider_priority_primary)
                            } else {
                                stringResource(R.string.provider_priority_fallback, index)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = stringResource(
                        R.string.provider_model_and_key,
                        provider.model,
                        provider.maskedKey,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                ProviderHealthLine(health)
            }

            if (total > 1) {
                Column {
                    IconButton(
                        onClick = onMoveUp,
                        enabled = index > 0,
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowUp,
                            contentDescription = stringResource(R.string.provider_move_up),
                        )
                    }
                    IconButton(
                        onClick = onMoveDown,
                        enabled = index < total - 1,
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.provider_move_down),
                        )
                    }
                }
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.provider_remove),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * Status dot + "Working · 2h ago" / "Failing · 5m ago", plus the concrete
 * failure reason underneath when there is one. The reason is the whole point:
 * "Failing" alone can't distinguish a revoked key from a stripped serializer.
 */
@Composable
private fun ProviderHealthLine(health: ProviderHealth?) {
    val (text, color) = when (health?.isHealthy) {
        true -> stringResource(R.string.provider_health_working, relativeTime(health.lastOkAt)) to
            MaterialTheme.colorScheme.primary
        false -> stringResource(R.string.provider_health_failing, relativeTime(health.lastFailAt)) to
            MaterialTheme.colorScheme.error
        null -> stringResource(R.string.provider_health_unused) to
            MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color)
            Spacer(Modifier.width(6.dp))
            Text(text = text, style = MaterialTheme.typography.labelSmall, color = color)
        }
        val error = health?.lastError
        if (health?.isHealthy == false && !error.isNullOrBlank()) {
            Text(
                text = error,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 14.dp, top = 2.dp),
            )
        }
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color),
    )
}

/** Inline feedback for the Test button. */
@Composable
private fun TestResultLine(state: TestState) {
    when (state) {
        TestState.Idle -> Unit
        TestState.Running -> {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.provider_test_testing),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TestState.Success -> {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.provider_test_ok),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        is TestState.Failure -> {
            Spacer(Modifier.height(8.dp))
            Text(
                text = state.reason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun relativeTime(timestamp: Long): String {
    val elapsed = System.currentTimeMillis() - timestamp
    val minutes = elapsed / 60_000
    return when {
        minutes < 1 -> stringResource(R.string.time_just_now)
        minutes < 60 -> stringResource(R.string.time_minutes_ago, minutes)
        minutes < 60 * 24 -> stringResource(R.string.time_hours_ago, minutes / 60)
        else -> stringResource(R.string.time_days_ago, minutes / (60 * 24))
    }
}
