package dev.punit.tidylink

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import dev.punit.tidylink.data.UrlCanonicalizer
import dev.punit.tidylink.data.work.SaveUrlWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Invisible trampoline for the system share sheet. Captures the shared URL,
 * hands the save to the application scope, and finishes immediately - the
 * sharing app stays in the foreground and TidyLink's UI never opens.
 *
 * Uses Theme.NoDisplay, which requires finish() before onResume() completes.
 * The repository persists a placeholder row synchronously-fast and enqueues a
 * WorkManager safety net for enrichment, so finishing early can't lose the save.
 */
class ShareReceiverActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShare()
        finish()
    }

    private fun handleShare() {
        // Some apps share URLs under text/* subtypes other than text/plain.
        if (intent?.action != Intent.ACTION_SEND ||
            intent.type?.startsWith("text/") != true
        ) {
            return
        }
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return

        // Shared text often includes extra words around the link
        // (e.g. YouTube shares "Check this out! https://youtu.be/…").
        val url = extractUrl(sharedText)
        if (url == null) {
            Toast.makeText(
                applicationContext,
                getString(R.string.share_no_link_found),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        Toast.makeText(
            applicationContext,
            getString(R.string.share_saving),
            Toast.LENGTH_SHORT,
        ).show()

        // Committed to WorkManager's DB before this activity finishes: even
        // if the process dies right now, the save still happens. The inline
        // path below usually wins; both are idempotent.
        SaveUrlWorker.enqueue(applicationContext, url)

        val app = application as TidyLinkApplication
        app.applicationScope.launch {
            val result = runCatching { app.container.linkRepository.processAndSaveUrl(url) }.getOrNull()
            if (result?.alreadyExisted == true) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        app,
                        app.getString(R.string.share_already_saved),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    /**
     * First http(s) URL in the shared text, falling back to a bare domain
     * typed without a scheme ("example.com") - cleanUrl adds https:// later.
     *
     * Extraction is [UrlCanonicalizer]'s, not a local regex: this used to
     * carry its own `https?://\S+`, which swallows punctuation that clings to
     * a URL in prose - sharing "Check this (https://example.com/page)." saved
     * the trailing ")." as part of the address. The shared helper trims those
     * and is unit-tested; keeping a second copy here only kept the bug.
     */
    private fun extractUrl(text: String): String? =
        UrlCanonicalizer.extractUrls(text).firstOrNull()
            ?: text.trim().takeIf { it.contains('.') && !it.contains(' ') }
}
