package dev.punit.tidylink.data.work

import java.security.MessageDigest
import java.util.Base64

internal fun shouldRetry(runAttemptCount: Int, maxAttempts: Int): Boolean =
    runAttemptCount + 1 < maxAttempts

internal fun saveWorkName(url: String): String = "save_url_" + Base64.getUrlEncoder()
    .withoutPadding()
    .encodeToString(MessageDigest.getInstance("SHA-256").digest(url.toByteArray()))
