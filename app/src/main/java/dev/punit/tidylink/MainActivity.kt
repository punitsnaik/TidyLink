package dev.punit.tidylink

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.punit.tidylink.data.settings.ThemeMode
import dev.punit.tidylink.ui.LinkViewModel
import dev.punit.tidylink.ui.dashboard.DashboardScreen
import dev.punit.tidylink.ui.onboarding.OnboardingScreen
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
            TidyLinkTheme(darkTheme = darkTheme, amoled = themeMode == ThemeMode.AMOLED) {
                // Gated here rather than inside DashboardScreen so the intro
                // is a sibling of the dashboard, not a layer on top of it.
                // The flag is loaded synchronously from prefs (OnboardingStore),
                // so the correct branch is taken on the very first frame.
                val hasSeenIntro by viewModel.hasSeenIntro.collectAsStateWithLifecycle()
                if (hasSeenIntro) {
                    DashboardScreen(viewModel = viewModel)
                } else {
                    OnboardingScreen(viewModel = viewModel)
                }
            }
        }
    }
}
