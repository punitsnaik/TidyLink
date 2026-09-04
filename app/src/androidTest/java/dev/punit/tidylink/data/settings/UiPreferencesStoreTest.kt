package dev.punit.tidylink.data.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiPreferencesStoreTest {
    @Test
    fun swipeDirectionsPersistIndependentlyAndRespectLegacySetting() {
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("test_swipe_preferences", mode)
        }
        val prefs = context.getSharedPreferences("ui_preferences", Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean("card_swipe_actions", false).commit()
        try {
            val store = UiPreferencesStore(context)
            assertFalse(store.cardRefreshSwipe.value)
            assertFalse(store.cardDeleteSwipe.value)
            store.setCardRefreshSwipe(true)
            assertTrue(UiPreferencesStore(context).cardRefreshSwipe.value)
            assertFalse(UiPreferencesStore(context).cardDeleteSwipe.value)
            store.setCardRefreshSwipe(false)
            store.setCardDeleteSwipe(true)
            assertFalse(UiPreferencesStore(context).cardRefreshSwipe.value)
            assertTrue(UiPreferencesStore(context).cardDeleteSwipe.value)
            assertTrue(UiPreferencesStore(context).pageSwipeNavigation.value)
        } finally {
            prefs.edit().clear().commit()
        }
    }
}
