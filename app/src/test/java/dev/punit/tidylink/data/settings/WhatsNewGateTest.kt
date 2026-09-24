package dev.punit.tidylink.data.settings

import dev.punit.tidylink.data.settings.OnboardingStore.Companion.WHATS_NEW_VERSION
import dev.punit.tidylink.data.settings.OnboardingStore.Companion.shouldShowWhatsNew
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsNewGateTest {
    @Test fun freshInstallAndOlderVersionsGetTheSheet() {
        assertTrue(shouldShowWhatsNew(seenVersion = 0))
        assertTrue(shouldShowWhatsNew(seenVersion = WHATS_NEW_VERSION - 1))
    }

    @Test fun dismissedForThisVersionStaysDismissed() {
        assertFalse(shouldShowWhatsNew(seenVersion = WHATS_NEW_VERSION))
    }
}
