package dev.punit.tidylink.data.settings

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks whether the first-run intro has been seen.
 *
 * Deliberately SharedPreferences rather than DataStore: the flag is read on
 * the very first composition, and prefs load synchronously in the constructor,
 * so [hasSeenIntro] already holds the right value before the first frame. A
 * DataStore flow would emit its default first, showing a frame of the empty
 * dashboard before the intro replaced it - the one thing an intro must not do.
 *
 * Kept in its own prefs file (not [LlmProviderStore]'s) because that one is
 * excluded from backup to keep API-key ciphertext on-device. This flag has the
 * opposite requirement: restoring a library to a new device should NOT re-run
 * the intro, so it rides along with the default backup.
 */
class OnboardingStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _hasSeenIntro = MutableStateFlow(prefs.getBoolean(KEY_SEEN_INTRO, false))

    /** False only until the intro is finished or skipped once. */
    val hasSeenIntro: StateFlow<Boolean> = _hasSeenIntro.asStateFlow()

    /** Called when the intro is completed OR skipped - both mean "don't show again". */
    fun markIntroSeen() = setSeen(true)

    /** Replays the intro from Settings → About. */
    fun replayIntro() = setSeen(false)

    private fun setSeen(seen: Boolean) {
        _hasSeenIntro.value = seen
        prefs.edit { putBoolean(KEY_SEEN_INTRO, seen) }
    }

    private companion object {
        const val PREFS_NAME = "onboarding"
        const val KEY_SEEN_INTRO = "has_seen_intro"
    }
}
