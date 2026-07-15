package dev.punit.tidylink.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.punit.tidylink.R

/** Settings tab: configuration, data management, about. */
@Composable
internal fun SettingsTab(
    onAiProviders: () -> Unit,
    onTidyCategories: () -> Unit,
    onFetchMissingDetails: () -> Unit,
    isFetchingMissingDetails: Boolean,
    onExport: () -> Unit,
    onImportJson: () -> Unit,
    onImportTxt: () -> Unit,
    onShowIntro: () -> Unit,
    onOpenRepo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        SettingsSectionHeader(stringResource(R.string.settings_section_ai))
        SettingsItem(
            title = stringResource(R.string.settings_ai_providers_title),
            subtitle = stringResource(R.string.settings_ai_providers_subtitle),
            onClick = onAiProviders,
        )
        SettingsItem(
            title = stringResource(R.string.settings_tidy_title),
            subtitle = stringResource(R.string.settings_tidy_subtitle),
            onClick = onTidyCategories,
        )

        SettingsSectionHeader(stringResource(R.string.settings_section_data))
        // Same action as the app-bar Refresh icon, kept here under its
        // explicit name: the icon alone doesn't say what it refreshes.
        SettingsItem(
            title = stringResource(R.string.settings_fetch_missing_title),
            subtitle = if (isFetchingMissingDetails) {
                stringResource(R.string.settings_fetch_missing_busy)
            } else {
                stringResource(R.string.settings_fetch_missing_subtitle)
            },
            onClick = onFetchMissingDetails,
            enabled = !isFetchingMissingDetails,
        )
        SettingsItem(
            title = stringResource(R.string.settings_export_title),
            subtitle = stringResource(R.string.settings_export_subtitle),
            onClick = onExport,
        )
        SettingsItem(
            title = stringResource(R.string.settings_import_json_title),
            subtitle = stringResource(R.string.settings_import_json_subtitle),
            onClick = onImportJson,
        )
        SettingsItem(
            title = stringResource(R.string.settings_import_txt_title),
            subtitle = stringResource(R.string.settings_import_txt_subtitle),
            onClick = onImportTxt,
        )

        SettingsSectionHeader(stringResource(R.string.settings_section_about))
        SettingsItem(
            title = stringResource(R.string.settings_show_intro_title),
            subtitle = stringResource(R.string.settings_show_intro_subtitle),
            onClick = onShowIntro,
        )
        SettingsItem(
            title = stringResource(R.string.settings_github_title),
            subtitle = stringResource(R.string.settings_github_subtitle),
            onClick = onOpenRepo,
        )
        SettingsInfoItem(
            title = stringResource(R.string.settings_privacy_title),
            subtitle = stringResource(R.string.settings_privacy_subtitle),
        )
        SettingsInfoItem(
            title = stringResource(R.string.app_name),
            subtitle = stringResource(R.string.settings_version, versionName),
        )

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp, start = 4.dp),
    )
}

@Composable
private fun SettingsItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Non-interactive settings row (about text, version). */
@Composable
private fun SettingsInfoItem(
    title: String,
    subtitle: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
