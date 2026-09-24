package dev.punit.tidylink.ui.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.punit.tidylink.R
import dev.punit.tidylink.data.local.LinkEntity
import dev.punit.tidylink.data.scraper.availableRelatedLinks
import dev.punit.tidylink.data.settings.LibraryViewMode
import dev.punit.tidylink.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

private const val TITLE_MAX_LINES = 2

// Swipe actions: width of the revealed button, and how far (fraction of card
// width) a drag must travel to run the action without tapping the button.
private val SWIPE_REVEAL_WIDTH = 104.dp
private val SWIPE_PANEL_GAP = 8.dp
private const val FULL_SWIPE_FRACTION = 0.6f

// Keep summaries concise even when portrait media makes a card taller.
private const val SUMMARY_MAX_LINES = 3

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun LinkCard(
    link: LinkEntity,
    selected: Boolean,
    index: Int,
    animateEntrance: Boolean,
    showActions: Boolean,
    isRefreshing: Boolean,
    viewMode: LibraryViewMode,
    cardRefreshSwipe: Boolean,
    cardDeleteSwipe: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
    onImageFailed: () -> Unit,
    modifier: Modifier = Modifier,
    isOpen: Boolean = false,
    onOpenChange: (Boolean) -> Unit = {},
) {
    // Press feedback: card gently scales down while held.
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "pressScale",
    )

    // Staggered entrance on initial load only. The decision is captured on
    // first composition so a mid-animation flag flip can't cancel it.
    //
    // The Animatable is allocated ONLY for cards that will actually animate
    // (the first screenful on a cold start). Every card composed while
    // scrolling used to allocate one and immediately hold it at 1f forever.
    val shouldAnimateEntrance = remember { animateEntrance }
    val entrance = if (shouldAnimateEntrance) remember { Animatable(0f) } else null
    if (entrance != null) {
        LaunchedEffect(Unit) {
            delay(index * 45L)
            entrance.animateTo(1f, tween(Motion.DURATION_MEDIUM, easing = Motion.EnterEasing))
        }
    }

    // Selection feedback: border and container tint animate together.
    val borderColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        },
        label = "borderColor",
    )
    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        label = "containerColor",
    )

    // Amazon-cart style swipe. A partial drag snaps open to reveal a
    // Refresh (right) or Delete (left) button that must be tapped; a long
    // drag past FULL_SWIPE_FRACTION of the card runs the action directly.
    // Fling velocity is deliberately ignored - a quick flick only reveals.
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val revealPx = with(density) { SWIPE_REVEAL_WIDTH.toPx() }
    var cardWidth by remember { mutableIntStateOf(0) }
    val offset = remember { Animatable(0f) }
    val canRefresh = showActions && cardRefreshSwipe
    val canDelete = showActions && cardDeleteSwipe
    val fullSwipePx = cardWidth * FULL_SWIPE_FRACTION
    val pastFull = cardWidth > 0 && kotlin.math.abs(offset.value) > fullSwipePx
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(pastFull) {
        if (pastFull) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    // Another card opened (or the list scrolled): close this one.
    LaunchedEffect(isOpen) {
        if (!isOpen && offset.value != 0f) offset.animateTo(0f)
    }
    LaunchedEffect(showActions) {
        if (!showActions) offset.snapTo(0f)
    }
    fun close() {
        onOpenChange(false)
        scope.launch { offset.animateTo(0f) }
    }
    val dragState = rememberDraggableState { delta ->
        val min = if (canDelete) -cardWidth.toFloat() else 0f
        val max = if (canRefresh) cardWidth.toFloat() else 0f
        scope.launch { offset.snapTo((offset.value + delta).coerceIn(min, max)) }
    }

    // TalkBack / switch-access parity for the swipe gestures.
    val refreshActionLabel = stringResource(R.string.action_refresh_link)
    val deleteActionLabel = stringResource(R.string.action_delete_link)
    val selectedStateLabel = stringResource(R.string.cd_selected)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { cardWidth = it.width },
    ) {
        // Action button: fixed width on both sides, anchored to the outer
        // edge. The card slides off it to uncover it; dragging further only
        // widens the gap, never the button.
        if (offset.value != 0f) {
            val isDelete = offset.value < 0f
            val panelWidth = SWIPE_REVEAL_WIDTH - SWIPE_PANEL_GAP
            val panelColor = if (isDelete) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            }
            val onPanel = if (isDelete) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onPrimaryContainer
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .matchParentSize()
                    .wrapContentWidth(if (isDelete) Alignment.End else Alignment.Start)
                    .width(panelWidth)
                    .clip(RoundedCornerShape(20.dp))
                    .background(panelColor)
                    .clickable(onClickLabel = if (isDelete) deleteActionLabel else refreshActionLabel) {
                        close()
                        if (isDelete) onDelete() else onRefresh()
                    },
            ) {
                Icon(
                    if (isDelete) Icons.Default.Delete else Icons.Default.Refresh,
                    contentDescription = null,
                    tint = onPanel,
                )
                Text(
                    text = stringResource(if (isDelete) R.string.action_delete else R.string.action_refresh),
                    style = MaterialTheme.typography.labelLarge,
                    color = onPanel,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = containerColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor),
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    enabled = canRefresh || canDelete,
                    onDragStopped = {
                        val x = offset.value
                        when {
                            cardWidth > 0 && kotlin.math.abs(x) > fullSwipePx -> {
                                close()
                                if (x < 0f) onDelete() else onRefresh()
                            }
                            kotlin.math.abs(x) > revealPx / 2 -> {
                                onOpenChange(true)
                                offset.animateTo(if (x < 0f) -revealPx else revealPx)
                            }
                            else -> close()
                        }
                    },
                )
                .graphicsLayer {
                    val progress = entrance?.value ?: 1f
                    alpha = progress
                    translationY = (1f - progress) * 24.dp.toPx()
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = { if (offset.value != 0f) close() else onClick() },
                    onLongClick = onLongClick,
                )
                .semantics {
                    if (selected) {
                        stateDescription = selectedStateLabel
                    }
                    customActions = listOf(
                        CustomAccessibilityAction(refreshActionLabel) { onRefresh(); true },
                        CustomAccessibilityAction(deleteActionLabel) { onDelete(); true },
                    )
                },
        ) {
            // Selection check / refresh spinner / pinned star are library-
            // grid-only concerns, so they're drawn here via the overlay
            // slot rather than living inside the shared body - trash has
            // none of the three.
            val overlay: @Composable BoxScope.() -> Unit = {
                androidx.compose.animation.AnimatedVisibility(
                        visible = selected,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp),
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = stringResource(R.string.cd_selected),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.background(
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(50),
                            ),
                        )
                    }
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(24.dp),
                        )
                    }
                    if (link.pinned) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = stringResource(R.string.cd_pinned),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(50),
                                )
                                .padding(3.dp)
                                .size(14.dp),
                        )
                }
            }
            if (viewMode == LibraryViewMode.ADAPTIVE) {
                AdaptiveLinkCardBody(link, onImageFailed = onImageFailed, thumbnailOverlay = overlay)
            } else {
                LinkCardBody(link, onImageFailed = onImageFailed, thumbnailOverlay = overlay)
            }
        }
    }
}

@Composable
internal fun AdaptiveLinkCardBody(
    link: LinkEntity,
    onImageFailed: () -> Unit,
    thumbnailOverlay: @Composable BoxScope.() -> Unit,
) {
    // Lazy-grid saveable state preserves the decision when this card re-enters view.
    var imageRatio by rememberSaveable(link.imageUrl) { mutableStateOf(1f) }
    var landscapeUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var failedUrl by rememberSaveable { mutableStateOf<String?>(null) }
    val hasImage = !link.imageUrl.isNullOrBlank() && failedUrl != link.imageUrl
    val context = LocalContext.current
    val imageRequest = remember(context, link.imageUrl, link.url, hasImage) {
        ImageRequest.Builder(context)
            .data(if (hasImage) link.imageUrl else faviconUrl(link.url))
            .size(1024, 1024)
            .crossfade(true)
            .build()
    }

    // One painter stays alive across both layouts: no second request on promotion.
    val painter = rememberAsyncImagePainter(
        model = imageRequest,
        contentScale = ContentScale.Fit,
        onSuccess = { success ->
            imageRatio = thumbnailAspectRatio(success.result.image.width, success.result.image.height)
            if (hasImage) landscapeUrl = link.imageUrl.takeIf {
                isLandscapeThumbnail(success.result.image.width, success.result.image.height)
            }
        },
        onError = {
            if (hasImage) {
                landscapeUrl = null
                failedUrl = link.imageUrl
                onImageFailed()
            }
        },
    )
    val image: @Composable (Modifier) -> Unit = { modifier ->
        Image(
            painter = painter,
            contentDescription = link.title,
            contentScale = if (hasImage) ContentScale.FillWidth else ContentScale.Fit,
            modifier = modifier.then(if (hasImage) Modifier else Modifier.padding(32.dp)),
        )
    }
    if (hasImage && landscapeUrl == link.imageUrl) {
        VisualLinkCardBody(link, imageRatio, image, thumbnailOverlay)
    } else {
        LinkCardBody(link, thumbnailOverlay = thumbnailOverlay, adaptiveImage = image, adaptiveImageRatio = imageRatio)
    }
}

internal fun isLandscapeThumbnail(width: Int, height: Int): Boolean = width > height && height > 0

internal fun thumbnailAspectRatio(width: Int, height: Int): Float =
    if (width > 0 && height > 0) width.toFloat() / height else 1f

@Composable
private fun VisualLinkCardBody(
    link: LinkEntity,
    imageRatio: Float,
    image: @Composable (Modifier) -> Unit,
    thumbnailOverlay: @Composable BoxScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(imageRatio)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            image(Modifier.fillMaxSize())
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp),
            ) {
                Text(
                    domainOf(link.url),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            thumbnailOverlay()
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryBadge(link.category)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.saved_on, DateFormat.getDateInstance().format(Date(link.timestamp))),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                displayTitle(link.title, link.url),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (link.aiSummary.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    link.aiSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val relationCount = remember(link.relatedLinksJson, link.description, link.url, link.resolvedUrl) {
                availableRelatedLinks(link.relatedLinksJson, link.description, link.url, link.resolvedUrl).size
            }
            if (relationCount > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.related_links_found, relationCount),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * Thumbnail + category badge + title + summary - the part of a link's card
 * that is identical between the library grid and trash (`TrashScreen`).
 * Everything that differs between those two contexts - selection, refresh
 * spinner, pinned star, swipe-to-dismiss, the outer `Card`, entrance/press
 * animation - stays with the caller. [thumbnailOverlay]
 * is where the library grid draws its selection/refresh/pin badges on top
 * of the thumbnail; trash passes none.
 */
@Composable
internal fun LinkCardBody(
    link: LinkEntity,
    modifier: Modifier = Modifier,
    thumbnailOverlay: @Composable BoxScope.() -> Unit = {},
    // Defaults to nothing so trash cards stay inert: re-scraping a link the
    // user has deleted would be work nobody asked for.
    onImageFailed: () -> Unit = {},
    adaptiveImage: (@Composable (Modifier) -> Unit)? = null,
    adaptiveImageRatio: Float = 1f,
) {
    // The row fits both its text and the thumbnail at its natural aspect ratio.
    //
    // No animateContentSize here, deliberately. Room invalidates this
    // screen's PagingSource on every write, and the background enrichment
    // sweep writes constantly - so a height animation on each card meant the
    // grid re-laying out under the user's finger mid-scroll, to smooth a
    // change most cards make exactly once. Text now appears without the
    // card resizing on an animation clock.
    Row(modifier = modifier.height(IntrinsicSize.Min)) {
        // Thumbnail (or favicon placeholder) with selection / refresh
        // overlays. Fills the card height; 5dp gap on all sides.
        // Load failure is tracked keyed on the image URL itself
        // (remember(link.imageUrl), not a plain boolean) so a
        // re-scrape that hands the link a different URL starts
        // fresh instead of the card staying pinned to the favicon
        // fallback forever.
        var imageLoadFailed by remember(link.imageUrl) { mutableStateOf(false) }
        var imageRatio by remember(link.imageUrl) { mutableStateOf(1f) }
        val hasImage = !link.imageUrl.isNullOrBlank() && !imageLoadFailed

        // Remembered, not rebuilt every recomposition: a new ImageRequest per
        // frame is an allocation per visible card per frame while scrolling.
        val context = LocalContext.current
        val imageRequest = remember(context, link.imageUrl, link.url, hasImage) {
            ImageRequest.Builder(context)
                .data(if (hasImage) link.imageUrl else faviconUrl(link.url))
                .crossfade(true)
                .build()
        }

        Box(
            modifier = Modifier
                .padding(5.dp)
                .width(116.dp)
                .defaultMinSize(minHeight = if (hasImage) {
                    maxOf(104f, 116f / if (adaptiveImage != null) adaptiveImageRatio else imageRatio).dp
                } else 104.dp)
                .fillMaxHeight(),
        ) {
            if (adaptiveImage != null) {
                adaptiveImage(Modifier.matchParentSize().clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant))
            } else AsyncImage(
                model = imageRequest,
                contentDescription = link.title,
                contentScale = if (hasImage) ContentScale.FillWidth else ContentScale.Fit,
                onSuccess = { imageRatio = thumbnailAspectRatio(it.result.image.width, it.result.image.height) },
                // A stored URL that won't load is the one broken-thumbnail
                // case the background sweep can't detect (a dead URL is
                // still a non-null URL), so the failure is reported up for
                // a one-shot re-scrape as well as swapped for the favicon.
                onError = {
                    if (hasImage) {
                        imageLoadFailed = true
                        onImageFailed()
                    }
                },
                modifier = Modifier
                    // The image box supplies the decoded aspect ratio to row measurement.
                    .matchParentSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (hasImage) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                        }
                    )
                    .then(if (hasImage) Modifier else Modifier.padding(32.dp)),
            )
            thumbnailOverlay()
        }

        // Details
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 7.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        ) {
            CategoryBadge(category = link.category)

            Spacer(Modifier.height(6.dp))

            Text(
                text = displayTitle(link.title, link.url),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = TITLE_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
            )

            if (link.aiSummary.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = link.aiSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = SUMMARY_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val relationCount = remember(link.relatedLinksJson, link.description, link.url, link.resolvedUrl) {
                availableRelatedLinks(link.relatedLinksJson, link.description, link.url, link.resolvedUrl).size
            }
            if (relationCount > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.related_links_found, relationCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
internal fun CategoryBadge(category: String, modifier: Modifier = Modifier) {
    val colors = categoryColors(category)
    Text(
        text = category,
        style = MaterialTheme.typography.labelSmall,
        color = colors.content,
        modifier = modifier
            .background(
                color = colors.container,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
