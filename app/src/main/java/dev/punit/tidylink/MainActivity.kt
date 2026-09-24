package dev.punit.tidylink

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.punit.tidylink.data.settings.ThemeMode
import dev.punit.tidylink.ui.LinkViewModel
import dev.punit.tidylink.ui.dashboard.DashboardScreen
import dev.punit.tidylink.ui.onboarding.OnboardingScreen
import dev.punit.tidylink.ui.onboarding.WhatsNewSheet
import dev.punit.tidylink.ui.theme.TidyLinkTheme

// Shared URLs are handled by ShareReceiverActivity, not here.
class MainActivity : ComponentActivity() {

    private val viewModel: LinkViewModel by viewModels { LinkViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.AMOLED -> true
            }
            // Bar icons follow the APP theme, not the OS one: otherwise a
            // Dark choice on a light-mode phone gets dark icons on dark.
            // Scrims match enableEdgeToEdge's defaults.
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(
                        Color.argb(0xe6, 0xFF, 0xFF, 0xFF),
                        Color.argb(0x80, 0x1b, 0x1b, 0x1b),
                    ) { darkTheme },
                )
                onDispose {}
            }
            val dynamicColor by viewModel.dynamicColor.collectAsStateWithLifecycle()
            TidyLinkTheme(
                darkTheme = darkTheme,
                amoled = themeMode == ThemeMode.AMOLED,
                dynamicColor = dynamicColor,
            ) {
                // Gated here rather than inside DashboardScreen so the intro
                // is a sibling of the dashboard, not a layer on top of it.
                // The flag is loaded synchronously from prefs (OnboardingStore),
                // so the correct branch is taken on the very first frame.
                val hasSeenIntro by viewModel.hasSeenIntro.collectAsStateWithLifecycle()
                if (hasSeenIntro) {
                    DashboardScreen(viewModel = viewModel)
                    val whatsNewOpen by viewModel.whatsNewOpen.collectAsStateWithLifecycle()
                    if (whatsNewOpen) WhatsNewSheet(onDismiss = viewModel::dismissWhatsNew)
                } else {
                    OnboardingScreen(viewModel = viewModel)
                }
            }
        }
    }
}
