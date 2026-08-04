package dev.punit.tidylink.ui.dashboard

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/**
 * Frosted surface: real backdrop blur of the content behind it on API 31+,
 * Haze's translucent fallback tint below. Must be a SIBLING of the
 * hazeSource node, never a descendant - a descendant would capture its own
 * pixels into the blur.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
internal fun GlassSurface(
    hazeState: HazeState,
    shape: Shape,
    modifier: Modifier = Modifier,
    elevation: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .shadow(elevation, shape, clip = false)
            .clip(shape)
            .hazeEffect(
                state = hazeState,
                style = HazeMaterials.thin(MaterialTheme.colorScheme.surface),
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                shape = shape,
            ),
    ) {
        content()
    }
}
