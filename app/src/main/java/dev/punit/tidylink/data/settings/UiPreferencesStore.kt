package dev.punit.tidylink.data.settings

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LibraryViewMode { ADAPTIVE, COMPACT }

internal fun libraryViewModeFromPreference(value: String?): LibraryViewMode =
    if (value == "COMPACT") LibraryViewMode.COMPACT else LibraryViewMode.ADAPTIVE

class UiPreferencesStore(context: Context) {
    private val prefs = context.getSharedPreferences("ui_preferences", Context.MODE_PRIVATE)

    private val _libraryViewMode = MutableStateFlow(
        libraryViewModeFromPreference(prefs.getString("library_view_mode", null))
    )
    val libraryViewMode: StateFlow<LibraryViewMode> = _libraryViewMode.asStateFlow()

    private val _cardRefreshSwipe = MutableStateFlow(prefs.getBoolean("card_refresh_swipe", prefs.getBoolean("card_swipe_actions", true)))
    val cardRefreshSwipe: StateFlow<Boolean> = _cardRefreshSwipe.asStateFlow()

    private val _cardDeleteSwipe = MutableStateFlow(prefs.getBoolean("card_delete_swipe", prefs.getBoolean("card_swipe_actions", true)))
    val cardDeleteSwipe: StateFlow<Boolean> = _cardDeleteSwipe.asStateFlow()

    private val _pageSwipeNavigation = MutableStateFlow(prefs.getBoolean("page_swipe_navigation", true))
    val pageSwipeNavigation: StateFlow<Boolean> = _pageSwipeNavigation.asStateFlow()

    fun setLibraryViewMode(mode: LibraryViewMode) {
        _libraryViewMode.value = mode
        prefs.edit { putString("library_view_mode", mode.name) }
    }

    fun setCardRefreshSwipe(enabled: Boolean) {
        _cardRefreshSwipe.value = enabled
        prefs.edit { putBoolean("card_refresh_swipe", enabled) }
    }

    fun setCardDeleteSwipe(enabled: Boolean) {
        _cardDeleteSwipe.value = enabled
        prefs.edit { putBoolean("card_delete_swipe", enabled) }
    }

    fun setPageSwipeNavigation(enabled: Boolean) {
        _pageSwipeNavigation.value = enabled
        prefs.edit { putBoolean("page_swipe_navigation", enabled) }
    }
}
