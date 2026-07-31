package dev.punit.tidylink.ui.dashboard

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import dev.punit.tidylink.R
import dev.punit.tidylink.data.settings.ThemeMode
import dev.punit.tidylink.ui.UpdateState
import java.io.File

/**
 * Settings: things you CONFIGURE, in four groups - Appearance, AI, Backup, About.
 *
 * Deliberately holds no library maintenance actions. "Fetch missing details"
 * and "Tidy up categories" moved to [ToolsSheet], one tap from the list,
 * because they are operations performed ON the library rather than settings;
 * fetch-missing was also a duplicate of the header's old Refresh icon.
 *
 * Bulk .txt URL import was removed with them. JSON export/import remain as
 * the single Backup pair.
 */
@Composable
internal fun SettingsTab(
    themeMode: ThemeMode,
    onThemeClick: () -> Unit,
    onAiProviders: () -> Unit,
    onExport: () -> Unit,
    onImportJson: () -> Unit,
    onImportBookmarks: () -> Unit,
    onShowIntro: () -> Unit,
    onOpenRepo: () -> Unit,
    updateState: UpdateState,
    onUpdateClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }
    var showPrivacy by rememberSaveable { mutableStateOf(false) }

    if (showPrivacy) {
        PrivacySheet(onDismiss = { showPrivacy = false })
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        SettingsSectionHeader(stringResource(R.string.settings_section_appearance))
        SettingsGroup {
            SettingsItem(
                title = stringResource(R.string.settings_theme_title),
                subtitle = themeLabel(themeMode),
                onClick = onThemeClick,
            )
        }

        SettingsSectionHeader(stringResource(R.string.settings_section_ai))
        SettingsGroup {
            SettingsItem(
                title = stringResource(R.string.settings_ai_providers_title),
                subtitle = stringResource(R.string.settings_ai_providers_subtitle),
                onClick = onAiProviders,
            )
        }

        SettingsSectionHeader(stringResource(R.string.settings_section_backup))
        SettingsGroup {
            SettingsItem(
                title = stringResource(R.string.settings_export_title),
                subtitle = stringResource(R.string.settings_export_subtitle),
                onClick = onExport,
            )
            SettingsDivider()
            SettingsItem(
                title = stringResource(R.string.settings_import_json_title),
                subtitle = stringResource(R.string.settings_import_json_subtitle),
                onClick = onImportJson,
            )
            SettingsDivider()
            SettingsItem(
                title = stringResource(R.string.settings_import_bookmarks_title),
                subtitle = stringResource(R.string.settings_import_bookmarks_subtitle),
                onClick = onImportBookmarks,
            )
        }

        SettingsSectionHeader(stringResource(R.string.settings_section_help))
        SettingsGroup {
            SettingsItem(
                title = stringResource(R.string.settings_show_intro_title),
                subtitle = stringResource(R.string.settings_show_intro_subtitle),
                onClick = onShowIntro,
            )
            SettingsDivider()
            SettingsItem(
                title = stringResource(R.string.settings_github_title),
                subtitle = stringResource(R.string.settings_github_subtitle),
                onClick = onOpenRepo,
            )
        }

        SettingsSectionHeader(stringResource(R.string.settings_section_about))
        SettingsGroup {
            // One row runs the whole update flow; the subtitle is the state
            // machine made visible: idle -> checking -> available ->
            // downloading -> ready to install (or up to date / failed).
            SettingsItem(
                title = stringResource(R.string.settings_updates_title),
                subtitle = when (updateState) {
                    UpdateState.Idle -> stringResource(R.string.settings_updates_idle)
                    UpdateState.Checking -> stringResource(R.string.settings_updates_checking)
                    UpdateState.UpToDate -> stringResource(R.string.settings_updates_up_to_date)
                    is UpdateState.Available -> stringResource(
                        R.string.settings_updates_available,
                        updateState.info.version,
                    )
                    is UpdateState.Downloading -> stringResource(
                        R.string.settings_updates_downloading,
                        updateState.percent,
                    )
                    is UpdateState.ReadyToInstall -> stringResource(
                        R.string.settings_updates_ready,
                        updateState.version,
                    )
                    UpdateState.Failed -> stringResource(R.string.settings_updates_failed)
                },
                onClick = onUpdateClick,
                enabled = updateState != UpdateState.Checking &&
                    updateState !is UpdateState.Downloading,
            )
            SettingsDivider()
            // Summary row, full text in a sheet: the privacy paragraph is
            // five lines and used to dominate this card, pushing the version
            // number off the bottom of most screens.
            SettingsItem(
                title = stringResource(R.string.settings_privacy_title),
                subtitle = stringResource(R.string.settings_privacy_summary),
                onClick = { showPrivacy = true },
            )
            SettingsDivider()
            SettingsInfoItem(
                title = stringResource(R.string.app_name),
                subtitle = stringResource(R.string.settings_version, versionName),
            )
        }

        // Clears the floating pill nav so the last row stays tappable.
        Spacer(Modifier.height(120.dp))
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp, start = 8.dp),
    )
}

/**
 * Rounded surface holding one section's rows, so the grouping is visible
 * rather than merely implied by the heading above it. Matches the pill nav
 * and category tiles; uses theme tokens only, no literal colours.
 */
@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column { content() }
    }
}

/** Full privacy text, kept out of the About card so it stays readable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrivacySheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_privacy_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.settings_privacy_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Hairline between rows inside a group, inset past the text start. */
@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp),
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
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
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

/**
 * Hands the downloaded update APK to the system installer. If the user
 * hasn't yet allowed this app to install unknown apps, opens that settings
 * screen instead - they grant once, come back, and tap the row again.
 *
 * No signature check here on purpose: Android refuses to install an APK
 * whose signing cert differs from the installed app's, which is exactly the
 * verification needed and can't be skipped.
 */
internal fun installApk(context: Context, file: File) {
    if (!context.packageManager.canRequestPackageInstalls()) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    "package:${context.packageName}".toUri(),
                )
            )
        }.onFailure { installFailedToast(context) }
        return
    }
    runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }.onFailure { installFailedToast(context) }
}

/** Both failure paths above are fire-and-forget Intents; a toast is the only
 *  surface left once the Settings row has already said "tap to install". */
private fun installFailedToast(context: Context) {
    Toast.makeText(context, R.string.msg_install_failed, Toast.LENGTH_SHORT).show()
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
            .padding(horizontal = 16.dp, vertical = 14.dp),
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
