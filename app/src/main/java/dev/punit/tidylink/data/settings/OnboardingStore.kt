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

    private val _whatsNewOpen = MutableStateFlow(
        shouldShowWhatsNew(prefs.getInt(KEY_WHATS_NEW_SEEN, 0))
    )

    /** True while the "What's new" sheet should be on screen. */
    val whatsNewOpen: StateFlow<Boolean> = _whatsNewOpen.asStateFlow()

    private val _seenTips = MutableStateFlow(prefs.getStringSet(KEY_SEEN_TIPS, emptySet()).orEmpty())

    /** One-time hints ([TIP_SWIPE], [TIP_LINK_TOOLS]) the user has already dismissed. */
    val seenTips: StateFlow<Set<String>> = _seenTips.asStateFlow()

    /** Called when the intro is completed OR skipped - both mean "don't show again". */
    fun markIntroSeen() = setSeen(true)

    fun openWhatsNew() {
        _whatsNewOpen.value = true
    }

    fun dismissWhatsNew() {
        _whatsNewOpen.value = false
        prefs.edit { putInt(KEY_WHATS_NEW_SEEN, WHATS_NEW_VERSION) }
    }

    fun markTipSeen(tip: String) {
        val tips = _seenTips.value + tip
        _seenTips.value = tips
        prefs.edit { putStringSet(KEY_SEEN_TIPS, tips) }
    }

    /** Replays the intro from Settings → About. */
    fun replayIntro() = setSeen(false)

    private fun setSeen(seen: Boolean) {
        _hasSeenIntro.value = seen
        prefs.edit { putBoolean(KEY_SEEN_INTRO, seen) }
    }

    companion object {
        /**
         * The versionCode whose features WhatsNewSheet describes. Bump it (and
         * rewrite the whats_new_* strings) only in a release that has news, so
         * a bug-fix release never pops an empty or stale sheet.
         */
        const val WHATS_NEW_VERSION = 17

        const val TIP_SWIPE = "swipe"
        const val TIP_LINK_TOOLS = "link_tools"

        private const val PREFS_NAME = "onboarding"
        private const val KEY_SEEN_INTRO = "has_seen_intro"
        private const val KEY_WHATS_NEW_SEEN = "whats_new_seen_version"
        private const val KEY_SEEN_TIPS = "seen_tips"

        /**
         * Everyone - upgraders and fresh installs - sees the sheet once per
         * [WHATS_NEW_VERSION]. MainActivity only shows it on the dashboard, so a
         * fresh install gets it right after the intro, never on top of it.
         */
        internal fun shouldShowWhatsNew(seenVersion: Int): Boolean =
            seenVersion < WHATS_NEW_VERSION
    }
}
