package dev.punit.tidylink.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.punit.tidylink.R

/**
 * Library maintenance actions, reached from the Tools icon in the Links
 * header rather than from Settings.
 *
 * These are things you DO to the library, not things you configure, so they
 * belong one tap from the list instead of three taps away behind Settings.
 * "Fetch missing details" in particular used to exist twice - as this same
 * [onFetchMissingDetails] action and as the header's own Refresh icon - and
 * the icon alone never said what it refreshed.
 *
 * Subtitles are not decoration: "Tidy up categories" is a bulk, non-undoable
 * rename, so its description has to be readable before the tap. That is why
 * this is a sheet and not a dropdown menu.
 */
@Composable
internal fun ToolsTab(
    isRefreshing: Boolean,
    duplicateCount: Int,
    trashCount: Int,
    onFetchMissingDetails: () -> Unit,
    onTidyCategories: () -> Unit,
    onMergeDuplicates: () -> Unit,
    onOpenTrash: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            ToolRow(
                icon = Icons.Default.Refresh,
                title = stringResource(R.string.tools_fetch_missing_title),
                subtitle = if (isRefreshing) {
                    stringResource(R.string.tools_fetch_missing_busy)
                } else {
                    stringResource(R.string.tools_fetch_missing_subtitle)
                },
                onClick = onFetchMissingDetails,
                enabled = !isRefreshing,
                busy = isRefreshing,
            )
            ToolRow(
                icon = Icons.Default.Build,
                title = stringResource(R.string.tools_tidy_title),
                subtitle = stringResource(R.string.tools_tidy_subtitle),
                onClick = onTidyCategories,
            )
            // Always listed, even at zero: the row's job is to answer
            // "do I have duplicates?", and a row that only appears when the
            // answer is yes can't be checked.
            ToolRow(
                icon = CopyIcon,
                title = stringResource(R.string.tools_duplicates_title),
                subtitle = if (duplicateCount == 0) {
                    stringResource(R.string.tools_duplicates_none)
                } else {
                    pluralStringResource(
                        R.plurals.tools_duplicates_subtitle,
                        duplicateCount,
                        duplicateCount,
                    )
                },
                onClick = onMergeDuplicates,
                enabled = duplicateCount > 0,
            )
            ToolRow(
                icon = Icons.Default.Delete,
                title = stringResource(R.string.tools_trash_title),
                subtitle = if (trashCount == 0) {
                    stringResource(R.string.tools_trash_empty)
                } else {
                    pluralStringResource(R.plurals.tools_trash_subtitle, trashCount, trashCount)
                },
                onClick = onOpenTrash,
                enabled = trashCount > 0,
            )
            }
        }
        Spacer(Modifier.height(120.dp))
    }
}

/**
 * Material "tune" glyph (three sliders), hand-built for the same reason as
 * [SortIcon] and the category FolderIcon - material-icons-core doesn't ship
 * it and the extended artifact isn't worth the dependency.
 *
 * Not Icons.Default.Build: CategoryTiles already maps that wrench to the
 * Technology / Tech / AI / Tools / Programming categories, so a wrench in
 * the header sat directly above a wrench meaning something else entirely.
 */
internal val TuneIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Tune",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            // Three tracks with a handle on each.
            moveTo(3f, 17f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(6f)
            verticalLineToRelative(-2f)
            horizontalLineTo(3f)
            close()
            moveTo(3f, 5f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(10f)
            verticalLineTo(5f)
            horizontalLineTo(3f)
            close()
            moveTo(13f, 21f)
            verticalLineToRelative(-2f)
            horizontalLineToRelative(8f)
            verticalLineToRelative(-2f)
            horizontalLineToRelative(-8f)
            verticalLineToRelative(-2f)
            horizontalLineToRelative(-2f)
            verticalLineToRelative(6f)
            horizontalLineToRelative(2f)
            close()
            moveTo(7f, 9f)
            verticalLineToRelative(2f)
            horizontalLineTo(3f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(4f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(2f)
            verticalLineTo(9f)
            horizontalLineTo(7f)
            close()
            moveTo(21f, 13f)
            verticalLineToRelative(-2f)
            horizontalLineTo(11f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(10f)
            close()
            moveTo(15f, 9f)
            horizontalLineToRelative(2f)
            verticalLineTo(7f)
            horizontalLineToRelative(4f)
            verticalLineTo(5f)
            horizontalLineToRelative(-4f)
            verticalLineTo(3f)
            horizontalLineToRelative(-2f)
            verticalLineToRelative(6f)
            close()
        }
    }.build()
}

@Composable
private fun ToolRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    // A row that can't be tapped has to look like it - otherwise "no
    // duplicates found" reads as a button that silently does nothing.
    val contentAlpha = if (enabled || busy) 1f else 0.38f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 12.dp)
            .alpha(contentAlpha),
    ) {
        if (busy) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(24.dp),
            )
        } else {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.size(16.dp))
        Column(modifier = Modifier.weight(1f)) {
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
}
