package dev.punit.tidylink.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = OceanPrimaryDark,
    onPrimary = OceanOnPrimaryDark,
    primaryContainer = OceanPrimaryContainerDark,
    onPrimaryContainer = OceanOnPrimaryContainerDark,
    secondary = CoralSecondaryDark,
    onSecondary = CoralOnSecondaryDark,
    secondaryContainer = CoralSecondaryContainerDark,
    onSecondaryContainer = CoralOnSecondaryContainerDark,
    tertiary = OceanTertiaryDark,
    onTertiary = OceanOnTertiaryDark,
    tertiaryContainer = OceanTertiaryContainerDark,
    onTertiaryContainer = OceanOnTertiaryContainerDark,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
)

private val LightColorScheme = lightColorScheme(
    primary = OceanPrimary,
    onPrimary = OceanOnPrimary,
    primaryContainer = OceanPrimaryContainer,
    onPrimaryContainer = OceanOnPrimaryContainer,
    secondary = CoralSecondary,
    onSecondary = CoralOnSecondary,
    secondaryContainer = CoralSecondaryContainer,
    onSecondaryContainer = CoralOnSecondaryContainer,
    tertiary = OceanTertiary,
    onTertiary = OceanOnTertiary,
    tertiaryContainer = OceanTertiaryContainer,
    onTertiaryContainer = OceanOnTertiaryContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
)

private val AmoledColorScheme = darkColorScheme(
    primary = OceanPrimaryDark,
    onPrimary = OceanOnPrimaryDark,
    primaryContainer = OceanPrimaryContainerDark,
    onPrimaryContainer = OceanOnPrimaryContainerDark,
    secondary = CoralSecondaryDark,
    onSecondary = CoralOnSecondaryDark,
    secondaryContainer = CoralSecondaryContainerDark,
    onSecondaryContainer = CoralOnSecondaryContainerDark,
    tertiary = OceanTertiaryDark,
    onTertiary = OceanOnTertiaryDark,
    tertiaryContainer = OceanTertiaryContainerDark,
    onTertiaryContainer = OceanOnTertiaryContainerDark,
    background = androidx.compose.ui.graphics.Color.Black,
    onBackground = DarkOnBackground,
    surface = androidx.compose.ui.graphics.Color.Black,
    onSurface = DarkOnSurface,
    surfaceDim = androidx.compose.ui.graphics.Color.Black,
    surfaceBright = androidx.compose.ui.graphics.Color(0xFF111111),
    surfaceContainerLowest = androidx.compose.ui.graphics.Color.Black,
    surfaceContainerLow = androidx.compose.ui.graphics.Color.Black,
    surfaceContainer = androidx.compose.ui.graphics.Color.Black,
    surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFF080808),
    surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFF111111),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF111111),
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = androidx.compose.ui.graphics.Color(0xFF242424),
)

@Composable
fun TidyLinkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    amoled: Boolean = false,
    // Material You: derive colors from the user's wallpaper. This is API 31+
    // ONLY - the dynamic*ColorScheme functions read android.R.color.system_*
    // resources that don't exist below S, so calling them on Android 10/11
    // throws at runtime. minSdk is 29, so the check is load-bearing: don't
    // drop it. Below S, and for callers that opt out, the Ocean/Coral brand
    // palettes are used instead.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val supportsDynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme = when {
        amoled -> AmoledColorScheme
        dynamicColor && supportsDynamicColor -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
