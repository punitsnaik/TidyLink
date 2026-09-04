package dev.punit.tidylink.ui.dashboard

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.browser.customtabs.CustomTabsIntent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.punit.tidylink.R
import dev.punit.tidylink.data.local.LinkEntity
import dev.punit.tidylink.ui.theme.Motion
import kotlinx.serialization.decodeFromString
import dev.punit.tidylink.data.scraper.availableRelatedLinks
import java.text.DateFormat
import java.util.Date

/**
 * How far a finger has to travel before the sheet changes link. Below this
 * the drag is ignored outright - no rubber-band, no partial follow, because
 * the sheet is not a pager and a half-committed card would just look broken.
 */
private val SWIPE_THRESHOLD = 80.dp

/** Full-screen link page shown when a card is tapped. */
@Composable
internal fun LinkDetailSheet(
    link: LinkEntity,
    isBusy: Boolean,
    hasPrev: Boolean,
    hasNext: Boolean,
    onNavigate: (Int) -> Unit,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onTogglePin: () -> Unit,
    pageSwipeNavigation: Boolean,
    onOpenRelated: (String) -> Unit,
    onSaveRelated: (String) -> Unit,
    // Takes the link whose image failed rather than closing over [link]:
    // mid-swipe the sheet is rendering a neighbour, and recovering the
    // wrong row would be a silent no-op that looks like a working fix.
    onImageFailed: (LinkEntity) -> Unit,
    savedRelatedUrls: Set<String> = emptySet(),
    savingRelatedUrls: Set<String> = emptySet(),
    feedback: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val source = linkSourceOf(link.url)
    val swipeThresholdPx = with(LocalDensity.current) { SWIPE_THRESHOLD.toPx() }
    // Which way the last swipe went, so the incoming card enters from the
    // side the finger came from.
    var navDirection by remember { mutableIntStateOf(1) }
    var dragTotal by remember { mutableFloatStateOf(0f) }
    // pointerInput's block is keyed on Unit so an in-flight drag is never
    // cancelled by a key change - which means the block captures its lambdas
    // ONCE. onNavigate closes over the caller's list index, so reading the
    // captured copy navigated from a frozen position and the sheet appeared
    // to stop swiping after a card or two. These read the current values on
    // every gesture instead.
    val currentOnNavigate by rememberUpdatedState(onNavigate)
    val currentHasPrev by rememberUpdatedState(hasPrev)
    val currentHasNext by rememberUpdatedState(hasNext)
    val previousLabel = stringResource(R.string.cd_previous_link)
    val nextLabel = stringResource(R.string.cd_next_link)
    BackHandler(onBack = onDismiss)

    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxSize(),
    ) {
      Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        feedback()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
            }
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        // Details scroll; the action bar below stays pinned so the CTA is
        // always reachable without scrolling.
        //
        // AnimatedContent swaps ONLY this body, keyed on the link id - the
        // ModalBottomSheet around it never leaves composition, so a swipe
        // never replays its slide-up enter animation. contentKey (not
        // targetState equality) is what stops an ordinary field update - a
        // refresh landing, a background classification - from sliding the
        // whole card sideways.
        AnimatedContent(
            targetState = link,
            contentKey = { it.id },
            transitionSpec = {
                (
                    slideInHorizontally(Motion.spatialSpring()) { width -> navDirection * width } +
                        fadeIn(tween(Motion.FADE_IN_MS, easing = Motion.EnterEasing))
                    ).togetherWith(
                    slideOutHorizontally(Motion.spatialSpring()) { width -> -navDirection * width } +
                        fadeOut(tween(Motion.FADE_OUT_MS, easing = Motion.ExitEasing))
                ) using SizeTransform(clip = false)
            },
            label = "detailSwipe",
            modifier = Modifier
                .weight(1f)
                .then(if (pageSwipeNavigation) Modifier.pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragTotal = 0f },
                        onDragEnd = {
                            val direction = when {
                                dragTotal <= -swipeThresholdPx && currentHasNext -> 1
                                dragTotal >= swipeThresholdPx && currentHasPrev -> -1
                                else -> 0
                            }
                            if (direction != 0) {
                                navDirection = direction
                                currentOnNavigate(direction)
                            }
                            dragTotal = 0f
                        },
                        onDragCancel = { dragTotal = 0f },
                    ) { change, dragAmount ->
                        // Consume it, or the same drag also reaches the ModalBottomSheet's
                        // drag-to-dismiss and a swipe can close the sheet instead of moving
                        // to the next link.
                        change.consume()
                        dragTotal += dragAmount
                    }
                } else Modifier),
        ) { shown ->
            // Load failure is tracked keyed on the image URL itself
            // (remember(shown.imageUrl), not a plain boolean) so a
            // re-scrape that hands the link a different URL starts fresh
            // instead of the sheet staying pinned to the favicon fallback
            // forever - and so navigating to a different link (a new
            // `shown`) doesn't inherit the previous link's failure state.
            var imageLoadFailed by remember(shown.imageUrl) { mutableStateOf(false) }
            val hasImage = !shown.imageUrl.isNullOrBlank() && !imageLoadFailed
            // verticalScroll stays on THIS Column, not hoisted up next to
            // .weight(...) on AnimatedContent above. rememberScrollState()
            // is per-composable-instance, so scoping it here means each
            // swipe gets a fresh scroll position; hoisting it would carry
            // the previous link's scroll offset into the next one.
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .animateContentSize(),
            ) {
                if (hasImage) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(shown.imageUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = shown.title,
                        contentScale = ContentScale.Fit,
                        // Same one-shot recovery as the grid card: a URL
                        // that won't load is invisible to the sweep, whose
                        // retry predicate is `imageUrl IS NULL`.
                        onError = {
                            imageLoadFailed = true
                            onImageFailed(shown)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                } else {
                    // No thumbnail: compact favicon banner instead of an empty
                    // 16:9 grey box.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                            )
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    ) {
                        AsyncImage(
                            model = faviconUrl(shown.url),
                            contentDescription = null,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = domainOf(shown.url),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }

                AnimatedVisibility(
                    visible = isBusy,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CategoryBadge(category = shown.category)
                    Text(
                        text = stringResource(
                            R.string.saved_on,
                            DateFormat.getDateInstance().format(Date(shown.timestamp)),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(8.dp))

                Text(
                    text = displayTitle(shown.title, shown.url),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )

                // The user's own words go ABOVE the machine's, in a tinted card
                // with a heading. Everything else on this sheet was written by
                // the page or the LLM, so the note has to be unmistakably
                // theirs - dropping it into the same run of grey body text
                // would bury the one part they wrote.
                if (shown.note.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = stringResource(R.string.detail_note_heading),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = shown.note,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }

                if (shown.aiSummary.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = shown.aiSummary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                if (shown.description.isNotBlank() && shown.description != shown.aiSummary) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = shown.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = shown.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                if (shown.resolvedUrl.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.redirected_to, domainOf(shown.resolvedUrl)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                val related = remember(shown.relatedLinksJson, shown.description, shown.url, shown.resolvedUrl) {
                    availableRelatedLinks(shown.relatedLinksJson, shown.description, shown.url, shown.resolvedUrl)
                }
                if (related.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    Text(
                        stringResource(R.string.related_links_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    related.forEach { item ->
                        Surface(
                            onClick = { onOpenRelated(item.url) },
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(end = 12.dp).size(40.dp)
                                        .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(10.dp)),
                                ) {
                                    Text(item.role.take(1).uppercase(), style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                                }
                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(
                                        item.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        "${item.role} · ${domainOf(item.url)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                val saved = item.url in savedRelatedUrls
                                val saving = item.url in savingRelatedUrls
                                IconButton(
                                    onClick = { onSaveRelated(item.url) },
                                    enabled = !saved && !saving,
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    if (saving) {
                                        val savingLabel = stringResource(R.string.related_link_saving)
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp).semantics { contentDescription = savingLabel },
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        Icon(
                                            if (saved) Icons.Default.Check else RelatedBookmarkIcon,
                                            stringResource(if (saved) R.string.related_link_saved else R.string.related_link_save_named, item.title),
                                        )
                                    }
                                }
                                Icon(Icons.Default.KeyboardArrowRight, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }

        // Pinned action area (outside the scrollable content).
        // navigationBarsPadding keeps it above the system nav bar.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp),
        ) {
            // customActions lives on the Button, not the Column above: the
            // Column merges no descendants and TalkBack only offers the
            // action list of the node it actually focuses, which is this
            // Button (it merges its own descendants and is first in the bar).
            Button(
                onClick = onOpen,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        customActions = buildList {
                            if (hasPrev) {
                                add(CustomAccessibilityAction(previousLabel) {
                                    navDirection = -1
                                    onNavigate(-1)
                                    true
                                })
                            }
                            if (hasNext) {
                                add(CustomAccessibilityAction(nextLabel) {
                                    navDirection = 1
                                    onNavigate(1)
                                    true
                                })
                            }
                        }
                    },
            ) {
                if (source.isPlayable) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    if (source.isGeneric) {
                        stringResource(R.string.cta_open_link)
                    } else {
                        stringResource(R.string.cta_open, source.name)
                    }
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth(),
            ) {
                IconButton(onClick = onTogglePin) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = stringResource(
                            if (link.pinned) R.string.action_unpin else R.string.action_pin
                        ),
                        tint = if (link.pinned) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.action_edit))
                }
                IconButton(onClick = { shareLink(context, link.url) }) {
                    Icon(Icons.Default.Share, contentDescription = stringResource(R.string.action_share))
                }
                IconButton(onClick = { copyLink(context, link.url) }) {
                    Icon(CopyIcon, contentDescription = stringResource(R.string.action_copy))
                }
                IconButton(onClick = onRefresh) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.action_refresh_link),
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.action_delete_link),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
      }
    }
}

private val RelatedBookmarkIcon = ImageVector.Builder("SaveLink", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(6f, 3f); horizontalLineTo(18f); verticalLineTo(22f); lineTo(12f, 18f)
        lineTo(6f, 22f); close()
        moveTo(8f, 5f); verticalLineTo(18f); lineTo(12f, 15.5f); lineTo(16f, 18f)
        verticalLineTo(5f); close()
    }
}.build()

/**
 * Material "content copy" glyph, hand-built because material-icons-core
 * doesn't include it (and the extended artifact isn't worth the dependency).
 */
internal val CopyIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "ContentCopy",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            // Back sheet outline.
            moveTo(16f, 1f)
            horizontalLineTo(4f)
            curveTo(2.9f, 1f, 2f, 1.9f, 2f, 3f)
            verticalLineTo(17f)
            horizontalLineToRelative(2f)
            verticalLineTo(3f)
            horizontalLineToRelative(12f)
            verticalLineTo(1f)
            close()
            // Front sheet (hollow).
            moveTo(19f, 5f)
            horizontalLineTo(8f)
            curveTo(6.9f, 5f, 6f, 5.9f, 6f, 7f)
            verticalLineTo(21f)
            curveTo(6f, 22.1f, 6.9f, 23f, 8f, 23f)
            horizontalLineToRelative(11f)
            curveTo(20.1f, 23f, 21f, 22.1f, 21f, 21f)
            verticalLineTo(7f)
            curveTo(21f, 5.9f, 20.1f, 5f, 19f, 5f)
            close()
            moveTo(19f, 21f)
            horizontalLineTo(8f)
            verticalLineTo(7f)
            horizontalLineToRelative(11f)
            verticalLineTo(21f)
            close()
        }
    }.build()
}

/**
 * Opens the link in its native app when one is installed (YouTube,
 * Instagram, Amazon, …), otherwise in a Chrome Custom Tab, finally the
 * default browser.
 */
internal fun openLink(context: Context, url: String) {
    val uri = url.toUri()
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") {
        return
    }

    if (openInNativeApp(context, uri)) return
    try {
        CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
            .launchUrl(context, uri)
    } catch (e: Exception) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }
}

/**
 * Tries to hand [uri] to a non-browser app. Returns false if only browsers
 * (or nothing) can handle it, which is the caller's cue to use a Custom Tab.
 *
 * Two implementations, because [Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER] is
 * API 30+. It's an int constant, so it compiles and inlines happily at
 * minSdk 29 and then does *nothing* on Android 10: the flag is ignored,
 * startActivity succeeds into a browser, no ActivityNotFoundException is
 * thrown, and the Custom Tab below is never reached. Silent, and only on
 * Android 10 - hence the explicit version split rather than one clever call.
 */
private fun openInNativeApp(context: Context, uri: Uri): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val nativeIntent = Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER)
        return try {
            context.startActivity(nativeIntent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    // API 29: ask the package manager which handlers are browsers, and take
    // the first that isn't. Package-visibility filtering doesn't apply here -
    // it's enforced from API 30, which this branch never runs on - so no
    // <queries> entry is needed.
    val pm = context.packageManager
    val browserProbe = Intent(Intent.ACTION_VIEW, Uri.fromParts("http", "", null))
        .addCategory(Intent.CATEGORY_BROWSABLE)
    val browsers = pm.queryIntentActivities(browserProbe, 0)
        .mapTo(mutableSetOf()) { it.activityInfo.packageName }
    val target = pm.queryIntentActivities(
        Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE), 0,
    ).map { it.activityInfo.packageName }
        .firstOrNull { it !in browsers && it != context.packageName }
        ?: return false
    return runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(target))
        true
    }.getOrDefault(false)
}

/** Shares a saved link back out through the system share sheet. */
private fun shareLink(context: Context, url: String) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
    }
    runCatching {
        context.startActivity(
            Intent.createChooser(sendIntent, context.getString(R.string.share_chooser_title))
        )
    }
}

/** Copies the URL; Android 13+ shows the system's own clipboard confirmation. */
private fun copyLink(context: Context, url: String) {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("link", url))
}

/**
 * Index the sheet should move to for a swipe, or null when there is nowhere
 * to go (either end of the list, or the open link is no longer in the list
 * because a filter changed underneath it - [currentIndex] is -1 then).
 *
 * [direction] is -1 for previous, +1 for next.
 */
internal fun detailNeighborIndex(currentIndex: Int, direction: Int, itemCount: Int): Int? {
    if (currentIndex < 0) return null
    val target = currentIndex + direction
    return if (target in 0 until itemCount) target else null
}
