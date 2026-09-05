package dev.punit.tidylink.ui.dashboard

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.punit.tidylink.MainActivity
import dev.punit.tidylink.R
import dev.punit.tidylink.data.local.LinkEntity
import dev.punit.tidylink.data.scraper.RelatedLink
import dev.punit.tidylink.data.scraper.RelationCache
import dev.punit.tidylink.data.scraper.encodeRelationCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Uses native instrumentation and the existing Activity; no additional UI-test dependency. */
@RunWith(AndroidJUnit4::class)
class LayoutRegressionTest {
    @Test
    fun adaptivePromotesLoadedLandscapeAndResetsForReplacementMedia() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val files = listOf(1000 to 500, 500 to 500, 200 to 1000).mapIndexed { index, (width, height) ->
            File(context.cacheDir, "adaptive-$index-${System.nanoTime()}.png").also { file ->
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.BLUE)
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        val current = mutableStateOf(fixture(files.first().toURI().toString()))
        val height = AtomicInteger()
        val failures = AtomicInteger()
        fun heightDp() = height.get() / context.resources.displayMetrics.density
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        MaterialTheme {
                            Box(Modifier.width(360.dp).onGloballyPositioned { height.set(it.size.height) }) {
                                AdaptiveLinkCardBody(current.value, { failures.incrementAndGet() }, {})
                            }
                        }
                    }
                }
                awaitCondition("Landscape never loaded/promoted; height=${heightDp()}") { heightDp() in 250f..320f }
                awaitCondition("Landscape thumbnail does not cover the card width") {
                    val screenshot = instrumentation.uiAutomation.takeScreenshot()
                    val density = context.resources.displayMetrics.density
                    val covered = listOf(8, 352).all { x ->
                        screenshot.getPixel((x * density).toInt(), (90 * density).toInt()) == Color.BLUE
                    }
                    screenshot.recycle()
                    covered
                }
                // Reuse the same composition/card identity; only its thumbnail URL changes.
                files.drop(1).forEachIndexed { index, file ->
                    scenario.onActivity { current.value = fixture(file.toURI().toString()) }
                    awaitCondition("Replacement did not fit its thumbnail") {
                        if (index == 0) heightDp() in 104f..240f else heightDp() in 589f..591f
                    }
                    // Promote again to prove each subsequent replacement resets a known large card.
                    scenario.onActivity { current.value = fixture(files.first().toURI().toString()) }
                    awaitCondition("Landscape did not promote again") { heightDp() in 250f..320f }
                }
                scenario.onActivity {
                    current.value = fixture(File(context.cacheDir, "missing-${System.nanoTime()}.png").toURI().toString())
                }
                awaitCondition("Failed thumbnail did not report failure and fall back") {
                    failures.get() == 1 && heightDp() in 104f..240f
                }
            }
        } finally {
            files.forEach(File::delete)
        }
    }

    @Test
    fun usefulLinkRowOpensAndBookmarkOnlySaves() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val opened = AtomicInteger()
        val saved = AtomicInteger()
        val savedUrls = mutableStateOf(emptySet<String>())
        val related = RelatedLink("https://example.com/resource", "Test resource", "Documentation")
        val link = fixture(null).copy(relatedLinksJson = encodeRelationCache(RelationCache(links = listOf(related))))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme {
                        LinkDetailSheet(
                            link = link, isBusy = false, hasPrev = false, hasNext = false,
                            onNavigate = {}, onDismiss = {}, onOpen = {}, onRefresh = {},
                            onDelete = {}, onEdit = {}, onTogglePin = {}, pageSwipeNavigation = false,
                            onOpenRelated = { assertEquals(related.url, it); opened.incrementAndGet() },
                            onSaveRelated = {
                                assertEquals(related.url, it)
                                saved.incrementAndGet()
                                savedUrls.value = setOf(it)
                            }, onImageFailed = {}, savedRelatedUrls = savedUrls.value,
                        )
                    }
                }
            }
            val saveLabel = instrumentation.targetContext.getString(R.string.related_link_save_named, related.title)
            fun nodes() = descendants(instrumentation.uiAutomation.rootInActiveWindow).toList()
            awaitCondition("Missing accessible useful-link save button") {
                nodes().any { it.contentDescription?.toString() == saveLabel }
            }
            val bookmark = clickableAncestor(nodes().first { it.contentDescription?.toString() == saveLabel })
            assertTrue(bookmark.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            awaitCondition("Bookmark did not save") { saved.get() == 1 }
            assertEquals("Bookmark also opened the URL", 0, opened.get())
            val savedLabel = instrumentation.targetContext.getString(R.string.related_link_saved)
            awaitCondition("Saved bookmark has no completion feedback") {
                nodes().any { it.contentDescription?.toString() == savedLabel }
            }
            val rowText = nodes().first { it.text?.toString()?.contains(related.title) == true }
            val row = clickableAncestor(rowText)
            assertTrue(row.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            awaitCondition("Row did not open") { opened.get() == 1 }
            assertEquals("Row also saved the URL", 1, saved.get())
        }
    }

    @Test
    fun savedUsefulLinkBackReturnsToParentPage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dao = dev.punit.tidylink.data.local.AppDatabase.getInstance(instrumentation.targetContext).linkDao()
        val suffix = System.nanoTime()
        val child = fixture(null).copy(
            url = "https://example.com/child-$suffix", title = "Child $suffix",
            description = "Child page $suffix", timestamp = System.currentTimeMillis(),
        )
        val parent = fixture(null).copy(
            url = "https://example.com/parent-$suffix", title = "Parent $suffix",
            description = "Parent page $suffix", timestamp = child.timestamp + 1000,
            relatedLinksJson = encodeRelationCache(RelationCache(links = listOf(
                RelatedLink(child.url, "Related child $suffix", "Documentation"),
            ))),
        )
        kotlinx.coroutines.runBlocking { dao.upsertAll(listOf(parent, child)) }
        fun nodes() = descendants(instrumentation.uiAutomation.rootInActiveWindow).toList()
        fun clickText(text: String) {
            try {
                awaitCondition("Missing $text") { nodes().any { it.text?.toString() == text } }
            } catch (failure: AssertionError) {
                val stored = kotlinx.coroutines.runBlocking { dao.getById(parent.id) }
                throw AssertionError("$text not visible; parent still has expected title=${stored?.title == parent.title}, " +
                    "description=${stored?.description == parent.description}, scraped=${stored?.scrapeAttempts}", failure)
            }
            assertTrue(clickableAncestor(nodes().first { it.text?.toString() == text })
                .performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val viewModel = androidx.lifecycle.ViewModelProvider(activity,
                        dev.punit.tidylink.ui.LinkViewModel.Factory)[dev.punit.tidylink.ui.LinkViewModel::class.java]
                    activity.setContent { MaterialTheme { DashboardScreen(viewModel) } }
                }
                clickText(parent.title)
                clickText("Related child $suffix")
                awaitCondition("Child page did not open") {
                    nodes().any { it.text?.toString() == child.description }
                }
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                awaitCondition("Back did not return to parent detail") {
                    nodes().any { it.text?.toString() == parent.description }
                }
                clickText("Related child $suffix")
                awaitCondition("Child page did not reopen") {
                    nodes().any { it.text?.toString() == child.description }
                }
                val backLabel = instrumentation.targetContext.getString(R.string.cd_back)
                val back = nodes().first { it.contentDescription?.toString() == backLabel }
                assertTrue(clickableAncestor(back).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                awaitCondition("Toolbar Back did not return to parent detail") {
                    nodes().any { it.text?.toString() == parent.description }
                }
            }
        } finally {
            kotlinx.coroutines.runBlocking { dao.deleteByIds(listOf(parent.id, child.id)) }
        }
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue(message, condition())
    }

    @Test
    fun portraitMediaFitsWholeThumbnailInCompactCard() = checkPortraitHeight(adaptive = false)

    @Test
    fun portraitMediaFitsWholeThumbnailInAdaptiveCard() = checkPortraitHeight(adaptive = true)

    private fun checkPortraitHeight(adaptive: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val image = File(context.cacheDir, "portrait-layout-test.png")
        val bitmap = Bitmap.createBitmap(200, 1000, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val height = AtomicInteger()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        MaterialTheme {
                            Box(Modifier.width(360.dp).onGloballyPositioned { height.set(it.size.height) }) {
                                if (adaptive) {
                                    AdaptiveLinkCardBody(fixture(image.toURI().toString()), {}, {})
                                } else {
                                    LinkCardBody(fixture(image.toURI().toString()))
                                }
                            }
                        }
                    }
                }
                // Let the local Coil request settle; the regression appears after image load.
                Thread.sleep(1500)
                instrumentation.waitForIdleSync()
                val heightDp = height.get() / context.resources.displayMetrics.density
                assertTrue("Portrait thumbnail was clipped at ${heightDp}dp", heightDp in 589f..591f)
            }
        } finally {
            image.delete()
        }
    }

    @Test
    fun shortDetailPageKeepsOpenActionAtBottom() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme {
                        LinkDetailSheet(
                            link = fixture(null), isBusy = false, hasPrev = false, hasNext = false,
                            onNavigate = {}, onDismiss = {}, onOpen = {}, onRefresh = {},
                            onDelete = {}, onEdit = {}, onTogglePin = {}, pageSwipeNavigation = false,
                            onOpenRelated = {}, onSaveRelated = {}, onImageFailed = {},
                        )
                    }
                }
            }
            val label = instrumentation.targetContext.getString(R.string.cta_open_link)
            awaitCondition("Missing $label") {
                descendants(instrumentation.uiAutomation.rootInActiveWindow).any { it.text?.toString() == label }
            }
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val action = descendants(root).first { it.text?.toString() == label }
            val bounds = Rect().also(action::getBoundsInScreen)
            val screen = Rect().also(root::getBoundsInScreen)
            assertTrue("Open action is not bottom anchored: $bounds / $screen", bounds.centerY() > screen.height() * 0.8)
        }
    }

    private fun descendants(node: AccessibilityNodeInfo?): Sequence<AccessibilityNodeInfo> = sequence {
        if (node == null) return@sequence
        yield(node)
        for (index in 0 until node.childCount) node.getChild(index)?.let { yieldAll(descendants(it)) }
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var clickable = node
        while (!clickable.isClickable) clickable = checkNotNull(clickable.parent) { "No clickable ancestor" }
        return clickable
    }

    private fun fixture(imageUrl: String?) = LinkEntity(
        url = "https://example.com/post", title = "A short title", description = "Short description",
        imageUrl = imageUrl, category = "Test", aiSummary = "A short summary",
    )
}
