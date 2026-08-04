package dev.punit.tidylink.ui.dashboard

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.punit.tidylink.R

/** Destinations reachable from the floating pill navigation bar. */
internal enum class DashboardTab { Links, Pinned, Settings }

/**
 * Floating pill navigation: frosted glass over the scrolling content behind
 * it (real backdrop blur on API 31+, Haze's translucent fallback tint on
 * 10/11 - see GlassSurface). The selected tab is a tonal inner pill with
 * icon + label and unselected tabs are label-only, plus a separate round
 * "+" button. Overlaid on content - the list scrolls underneath. The
 * selected pill uses the highest surface container tone (NOT
 * inverseSurface, which flips polarity and turned the bar light in dark
 * theme): dark gray on a dark theme, light gray on a light one - the
 * Google Photos look - and it follows dynamic color.
 */
@Composable
internal fun BottomPillNav(
    currentTab: DashboardTab,
    onSelect: (DashboardTab) -> Unit,
    onAdd: () -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        GlassSurface(
            hazeState = hazeState,
            shape = RoundedCornerShape(50),
            elevation = 6.dp,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(6.dp),
            ) {
                DashboardTab.entries.forEach { tab ->
                    PillTab(
                        tab = tab,
                        selected = tab == currentTab,
                        onClick = { onSelect(tab) },
                    )
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        GlassSurface(
            hazeState = hazeState,
            shape = CircleShape,
            elevation = 6.dp,
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(56.dp)
                    .clickable(onClick = onAdd),
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.action_add_link),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun PillTab(
    tab: DashboardTab,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val label = stringResource(
        when (tab) {
            DashboardTab.Links -> R.string.nav_links
            DashboardTab.Pinned -> R.string.nav_pinned
            DashboardTab.Settings -> R.string.nav_settings
        }
    )
    // Star matches the pin marker used on cards and in the detail sheet.
    val icon = when (tab) {
        DashboardTab.Links -> Icons.Default.Home
        DashboardTab.Pinned -> Icons.Default.Star
        DashboardTab.Settings -> Icons.Default.Settings
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            Color.Transparent
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            // animateContentSize eases the width change when the icon
            // appears on the newly selected tab.
            modifier = Modifier
                .animateContentSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (selected) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            )
        }
    }
}
