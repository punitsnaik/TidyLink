package dev.punit.tidylink.ui.dashboard

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Elevated, outlined surface on the opaque surfaceContainerHighest tone. */
@Composable
internal fun GlassSurface(
    shape: Shape,
    modifier: Modifier = Modifier,
    elevation: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .shadow(
                elevation = elevation,
                shape = shape,
                clip = false,
                ambientColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
                spotColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
            )
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(
                width = 1.25.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                shape = shape,
            ),
    ) {
        content()
    }
}

/**
 * Container color for the dashboard's modal sheets. Sheets render in
 * their own window, so a blur cannot reach through them - instead
 * DashboardScreen blurs the content BEHIND the open sheet, and this
 * translucent container lets that blur read through the sheet like
 * frosted glass. Below API 31 Modifier.blur is a no-op, so the sheet
 * stays opaque: translucency over sharp content would put text on
 * text. The two sheets with local state (privacy, all-categories) do
 * not trigger the blur-behind and keep the default solid container on
 * purpose.
 *
 * The alpha is the whole glass effect and is a judgement call, so it
 * is a named constant rather than a literal. History, because the
 * direction of travel matters more than the number: 0.92 shipped first
 * and read as a flat dark panel - the backdrop blur was there but
 * almost nothing came through. 0.78 was legibly glass but still
 * conservative. 0.68 is the current setting, chosen on device.
 *
 * Below roughly 0.6 the sheet stops being a reading surface: body text
 * sits over visibly moving colour, and it fails first in LIGHT theme on
 * a colourful library, not in dark. Anyone lowering this further must
 * check light theme at a large font scale before keeping it.
 */
private const val SHEET_GLASS_ALPHA = 0.68f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun glassSheetColor(): Color {
    val base = BottomSheetDefaults.ContainerColor
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        base.copy(alpha = SHEET_GLASS_ALPHA)
    } else {
        base
    }
}
