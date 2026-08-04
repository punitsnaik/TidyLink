package dev.punit.tidylink.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * System font, tuned scale: SemiBold titles, slightly negative tracking
 * on large text (the Google/Apple look). Built by copying the M3
 * defaults so every slot keeps the platform lineHeightStyle metrics -
 * bare TextStyle() constructions lose them and misalign leading against
 * untouched slots.
 */
private val Defaults = Typography()

val Typography = Typography(
    headlineSmall = Defaults.headlineSmall.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = Defaults.titleLarge.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = Defaults.titleMedium.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.1.sp,
    ),
    titleSmall = Defaults.titleSmall.copy(
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = Defaults.bodyLarge.copy(
        letterSpacing = 0.3.sp,
    ),
    bodyMedium = Defaults.bodyMedium.copy(
        letterSpacing = 0.2.sp,
    ),
    labelLarge = Defaults.labelLarge.copy(
        fontWeight = FontWeight.Medium,
    ),
)
