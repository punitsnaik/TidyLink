package dev.punit.tidylink.ui.onboarding

import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.punit.tidylink.R
import dev.punit.tidylink.data.settings.LibraryViewMode
import dev.punit.tidylink.data.settings.ThemeMode
import dev.punit.tidylink.ui.LinkViewModel
import dev.punit.tidylink.ui.dashboard.AiProviderSheet
import dev.punit.tidylink.ui.theme.TidyLinkTheme
import kotlinx.coroutines.launch

/** Pages that carry a choice, not just text. */
private enum class PageKind { Info, Theme, Layout, Import, AiSetup }

/** One intro page. [kind] decides what sits under the text. */
private data class IntroPage(
    val icon: ImageVector,
    val titleRes: Int,
    val bodyRes: Int,
    val kind: PageKind = PageKind.Info,
)

private val PAGES = listOf(
    IntroPage(
        icon = Icons.Filled.Home,
        titleRes = R.string.intro_welcome_title,
        bodyRes = R.string.intro_welcome_body,
    ),
    IntroPage(
        icon = Icons.Filled.Settings,
        titleRes = R.string.intro_theme_title,
        bodyRes = R.string.intro_theme_body,
        kind = PageKind.Theme,
    ),
    IntroPage(
        icon = Icons.Filled.Share,
        titleRes = R.string.intro_share_title,
        bodyRes = R.string.intro_share_body,
    ),
    IntroPage(
        icon = Icons.Filled.Search,
        titleRes = R.string.intro_organize_title,
        bodyRes = R.string.intro_organize_body,
    ),
    IntroPage(
        icon = Icons.AutoMirrored.Filled.List,
        titleRes = R.string.intro_layout_title,
        bodyRes = R.string.intro_layout_body,
        kind = PageKind.Layout,
    ),
    IntroPage(
        icon = Icons.Filled.Add,
        titleRes = R.string.intro_import_title,
        bodyRes = R.string.intro_import_body,
        kind = PageKind.Import,
    ),
    IntroPage(
        icon = Icons.Filled.Star,
        titleRes = R.string.intro_ai_title,
        bodyRes = R.string.intro_ai_body,
        kind = PageKind.AiSetup,
    ),
)

/**
 * First-run intro: what TidyLink is, how links get in, how to find them
 * again, and an optional prompt to add an AI key.
 *
 * The AI step is deliberately skippable - categorization is an optional
 * feature that needs the user's own key, and a first-run wall demanding one
 * would misrepresent the app. Whoever skips still meets [AddProviderBanner]
 * on the dashboard, which shows while no provider is configured.
 *
 * Reached from [dev.punit.tidylink.MainActivity] while
 * [LinkViewModel.hasSeenIntro] is false. Sharing a URL into the app does NOT
 * come through here - see ShareReceiverActivity.
 */
@Composable
fun OnboardingScreen(
    viewModel: LinkViewModel,
    modifier: Modifier = Modifier,
) {
    val providers by viewModel.llmProviders.collectAsStateWithLifecycle()
    val health by viewModel.providerHealth.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val dynamicColor by viewModel.dynamicColor.collectAsStateWithLifecycle()
    val viewMode by viewModel.libraryViewMode.collectAsStateWithLifecycle()
    val refreshSwipe by viewModel.cardRefreshSwipe.collectAsStateWithLifecycle()
    val deleteSwipe by viewModel.cardDeleteSwipe.collectAsStateWithLifecycle()
    val backup by viewModel.backupState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Same contracts and mime types as Settings; results surface as the
    // dashboard snackbar once the intro is done.
    // Disabled once a file is picked, so a double tap can't import twice.
    var jsonImported by rememberSaveable { mutableStateOf(false) }
    var bookmarksImported by rememberSaveable { mutableStateOf(false) }
    val importJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        jsonImported = true
        viewModel.importLinks(uri)
    }
    val importBookmarks = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        bookmarksImported = true
        viewModel.importBookmarks(uri, useFoldersAsCategories = true)
    }
    // Mirrors DashboardScreen: no persistable grant, no backup - otherwise
    // the worker fails silently after the next reboot.
    val backupFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val persisted = runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.isSuccess
        if (persisted) viewModel.enableBackup(uri.toString())
    }
    val pagerState = rememberPagerState(pageCount = { PAGES.size })
    val scope = rememberCoroutineScope()
    var showAiProviders by rememberSaveable { mutableStateOf(false) }

    val isLastPage = pagerState.currentPage == PAGES.lastIndex

    // Back steps through the intro rather than dropping straight to the
    // dashboard: leaving via Back would otherwise skip it permanently
    // without the user ever choosing to.
    BackHandler(enabled = pagerState.currentPage > 0) {
        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
    }

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            // innerPadding already carries the system-bar insets (Scaffold's
            // default contentWindowInsets); adding statusBarsPadding() here
            // too would inset twice under enableEdgeToEdge.
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // Skip is always available; the intro is informational, never a gate.
            // Fixed height: Skip fades out on the last page, and without a
            // reserved row the pages below would jump up as it goes.
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = 8.dp),
            ) {
                AnimatedVisibility(visible = !isLastPage, enter = fadeIn(), exit = fadeOut()) {
                    TextButton(onClick = viewModel::markIntroSeen) {
                        Text(stringResource(R.string.intro_action_skip))
                    }
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) { page ->
                IntroPageContent(page = PAGES[page]) {
                    when (PAGES[page].kind) {
                        PageKind.Info -> Unit
                        PageKind.Theme -> {
                            ThemePicker(selected = themeMode, onSelect = viewModel::setThemeMode)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && themeMode != ThemeMode.AMOLED) {
                                ToggleRow(
                                    label = stringResource(R.string.settings_dynamic_color_title),
                                    checked = dynamicColor,
                                    onChange = viewModel::setDynamicColor,
                                )
                            }
                        }
                        PageKind.Layout -> {
                            LayoutPicker(selected = viewMode, onSelect = viewModel::setLibraryViewMode)
                            ToggleRow(
                                label = stringResource(R.string.settings_card_refresh_swipe_title),
                                checked = refreshSwipe,
                                onChange = viewModel::setCardRefreshSwipe,
                            )
                            ToggleRow(
                                label = stringResource(R.string.settings_card_delete_swipe_title),
                                checked = deleteSwipe,
                                onChange = viewModel::setCardDeleteSwipe,
                            )
                        }
                        PageKind.Import -> {
                            OutlinedButton(enabled = !bookmarksImported, onClick = { importBookmarks.launch(arrayOf("text/html", "text/plain", "*/*")) }) {
                                Text(stringResource(R.string.settings_import_bookmarks_title))
                            }
                            OutlinedButton(enabled = !jsonImported, onClick = { importJson.launch(arrayOf("application/json", "application/octet-stream")) }) {
                                Text(stringResource(R.string.settings_import_json_title))
                            }
                            if (backup.enabled) {
                                ConfirmText(stringResource(R.string.intro_backup_on))
                            } else {
                                OutlinedButton(onClick = { backupFolder.launch(null) }) {
                                    Text(stringResource(R.string.settings_auto_backup_title))
                                }
                            }
                        }
                        PageKind.AiSetup -> if (providers.isNotEmpty()) {
                            // Confirms the key landed - the sheet dismisses back to this
                            // page, which would otherwise look like nothing happened.
                            ConfirmText(stringResource(R.string.intro_ai_configured))
                        } else {
                            OutlinedButton(onClick = { showAiProviders = true }) {
                                Text(stringResource(R.string.intro_ai_add_key))
                            }
                        }
                    }
                }
            }

            PageIndicator(
                pageCount = PAGES.size,
                currentPage = pagerState.currentPage,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            )

            Button(
                onClick = {
                    if (isLastPage) {
                        viewModel.markIntroSeen()
                    } else {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp)
                    .height(52.dp),
            ) {
                Text(
                    stringResource(
                        if (isLastPage) R.string.intro_action_done else R.string.intro_action_next
                    )
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showAiProviders) {
        AiProviderSheet(
            providers = providers,
            health = health,
            onAdd = viewModel::addLlmProvider,
            onRemove = viewModel::removeLlmProvider,
            onMove = viewModel::moveLlmProvider,
            onTest = viewModel::testLlmProvider,
            onDismiss = { showAiProviders = false },
        )
    }
}

/** Icon, title, body - plus the page's choices in [extra]. */
@Composable
private fun IntroPageContent(
    page: IntroPage,
    modifier: Modifier = Modifier,
    extra: @Composable ColumnScope.() -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp),
    ) {
        // Choice pages drop the big icon: their previews are the visual.
        if (page.kind == PageKind.Info || page.kind == PageKind.AiSetup) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
            ) {
                Icon(
                    imageVector = page.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(44.dp),
                )
            }
            Spacer(Modifier.height(32.dp))
        }

        Text(
            text = stringResource(page.titleRes),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(page.bodyRes),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (page.kind != PageKind.Info) {
            Spacer(Modifier.height(24.dp))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = extra,
            )
        }
    }
}

@Composable
private fun ConfirmText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** Whole row toggles, so the tap target isn't just the switch. */
@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** Dots showing position in the pager. */
@Composable
private fun PageIndicator(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        repeat(pageCount) { index ->
            val selected = index == currentPage
            Box(
                modifier = Modifier
                    .height(8.dp)
                    // The active dot stretches instead of just recolouring, so
                    // position stays readable without relying on colour alone.
                    .width(if (selected) 24.dp else 8.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    ),
            )
        }
    }
}

private val THEME_OPTIONS = listOf(
    ThemeMode.SYSTEM to R.string.theme_system,
    ThemeMode.LIGHT to R.string.theme_light,
    ThemeMode.DARK to R.string.theme_dark,
    ThemeMode.AMOLED to R.string.theme_amoled,
)

/**
 * 2x2 grid of mini dashboards, each drawn in its own theme. Picking one
 * applies it app-wide at once (MainActivity re-themes from [ThemeStore]), so
 * the whole intro becomes the full-size preview.
 */
@Composable
private fun ThemePicker(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.selectableGroup(),
    ) {
        THEME_OPTIONS.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (mode, labelRes) ->
                    ThemeOption(
                        mode = mode,
                        label = stringResource(labelRes),
                        selected = mode == selected,
                        onClick = { onSelect(mode) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeOption(
    mode: ThemeMode,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }
    PreviewOption(label, selected, onClick, modifier) {
        TidyLinkTheme(darkTheme = dark, amoled = mode == ThemeMode.AMOLED) {
            MiniDashboard()
        }
    }
}

/** Two side-by-side sketches of the library: cards with thumbnails vs. dense rows. */
@Composable
private fun LayoutPicker(
    selected: LibraryViewMode,
    onSelect: (LibraryViewMode) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.selectableGroup(),
    ) {
        PreviewOption(
            label = stringResource(R.string.intro_layout_adaptive),
            selected = selected == LibraryViewMode.ADAPTIVE,
            onClick = { onSelect(LibraryViewMode.ADAPTIVE) },
            modifier = Modifier.weight(1f),
        ) { MiniDashboard(rows = 1, thumbnailSize = 40.dp) }
        PreviewOption(
            label = stringResource(R.string.intro_layout_compact),
            selected = selected == LibraryViewMode.COMPACT,
            onClick = { onSelect(LibraryViewMode.COMPACT) },
            modifier = Modifier.weight(1f),
        ) { MiniDashboard(rows = 4, thumbnailSize = 0.dp) }
    }
}

/** A bordered, radio-style tile: [preview] on top, [label] below. */
@Composable
private fun PreviewOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    preview: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(shape)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = shape,
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(8.dp),
    ) {
        preview()
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/** Fake link cards - enough to show background, surface, and accent. */
@Composable
private fun MiniDashboard(rows: Int = 2, thumbnailSize: Dp = 22.dp) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.background,
        shape = RoundedCornerShape(10.dp),
        // Outline so the Light preview doesn't melt into a light page.
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.fillMaxWidth().height(96.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(8.dp),
        ) {
            repeat(rows) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.surfaceContainerHigh)
                        .padding(6.dp),
                ) {
                    if (thumbnailSize > 0.dp) {
                        Box(
                            Modifier
                                .size(thumbnailSize)
                                .clip(RoundedCornerShape(4.dp))
                                .background(colors.primaryContainer)
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.fillMaxWidth(0.8f).height(5.dp).clip(CircleShape).background(colors.onSurface))
                        Box(Modifier.fillMaxWidth(0.5f).height(5.dp).clip(CircleShape).background(colors.primary))
                    }
                }
            }
        }
    }
}
