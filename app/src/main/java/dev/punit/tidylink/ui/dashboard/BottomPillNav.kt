package dev.punit.tidylink.ui.dashboard

import androidx.compose.animation.animateContentSize
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
import dev.punit.tidylink.R

/** Destinations reachable from the floating pill navigation bar. */
internal enum class DashboardTab { Links, Pinned, Settings }

/**
 * Floating pill navigation: a dark rounded bar where the selected tab is a
 * lighter inner pill with icon + label and unselected tabs are label-only,
 * plus a separate round "+" button. Overlaid on content - the list scrolls
 * underneath. Colors come from the theme's inverse surface so the bar reads
 * "dark pill" in light theme and "light pill" in dark theme, and follows
 * dynamic color.
 */
@Composable
internal fun BottomPillNav(
    currentTab: DashboardTab,
    onSelect: (DashboardTab) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.inverseSurface,
            shadowElevation = 6.dp,
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
        Surface(
            onClick = onAdd,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 6.dp,
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.action_add_link),
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
            MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.22f)
        } else {
            Color.Transparent
        },
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
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
