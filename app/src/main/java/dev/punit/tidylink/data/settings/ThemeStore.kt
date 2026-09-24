package dev.punit.tidylink.data.settings

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Settings → Appearance choice. [SYSTEM] follows the OS light/dark setting. */
enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

/**
 * Persists the user's theme choice.
 *
 * SharedPreferences, not DataStore, for the same reason as [OnboardingStore]:
 * MainActivity reads this on the very first composition, and prefs load
 * synchronously in the constructor, so the correct theme is already applied
 * before the first frame - no light-then-dark flash.
 */
class ThemeStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(
        prefs.getString(KEY_THEME_MODE, null)?.let {
            runCatching { ThemeMode.valueOf(it) }.getOrNull()
        } ?: ThemeMode.SYSTEM
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _dynamicColor = MutableStateFlow(prefs.getBoolean(KEY_DYNAMIC_COLOR, false))

    /** Material You wallpaper colors. Ignored below API 31 and in AMOLED. */
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()

    fun setDynamicColor(enabled: Boolean) {
        _dynamicColor.value = enabled
        prefs.edit { putBoolean(KEY_DYNAMIC_COLOR, enabled) }
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit { putString(KEY_THEME_MODE, mode.name) }
    }

    private companion object {
        const val PREFS_NAME = "theme"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_DYNAMIC_COLOR = "dynamic_color"
    }
}
