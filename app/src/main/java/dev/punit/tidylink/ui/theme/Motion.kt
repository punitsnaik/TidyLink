package dev.punit.tidylink.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

/**
 * Single source of truth for motion specs. The aesthetic is "blend":
 * Material structure with Apple-level restraint - calm, precise, never
 * bouncy. New animations reference these instead of ad-hoc tween(300)s.
 */
internal object Motion {
    /** M3 emphasized-decelerate: incoming content lands softly. */
    val EnterEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** M3 emphasized-accelerate: outgoing content leaves quickly. */
    val ExitEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** Fade-through pair (M3 top-level transition pattern). */
    const val FADE_OUT_MS = 90
    const val FADE_IN_MS = 210

    const val DURATION_MEDIUM = 300

    /** Spatial moves (placement, size). Damping 0.9 settles with no overshoot. */
    fun <T> spatialSpring(): SpringSpec<T> =
        spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMedium)
}
