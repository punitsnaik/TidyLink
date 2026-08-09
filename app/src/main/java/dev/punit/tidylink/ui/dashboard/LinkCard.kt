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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.punit.tidylink.R
import dev.punit.tidylink.data.local.LinkEntity
import dev.punit.tidylink.ui.theme.Motion
import kotlinx.coroutines.delay

private const val TITLE_MAX_LINES = 2

/**
 * Lines the summary may use.
 *
 * This used to be derived per card from the thumbnail's MEASURED height, via
 * an `onSizeChanged` that wrote state during layout - which scheduled a
 * second composition of every card, whose new line count could change the
 * row's height again. A guaranteed extra pass per card, on the hottest path
 * in the app, damped only by a clamp.
 *
 * It was also reading back a number the text had just produced. The
 * thumbnail is a fixed width with `fillMaxHeight()`, so it has no natural
 * height of its own - under `IntrinsicSize.Min` its height IS the text
 * column's height, floored at the 104.dp minimum. The "fill the space beside
 * a tall thumbnail" case the derivation existed for cannot arise.
 *
 * Three lines is what that math returned for a typical card anyway, and the
 * thumbnail still stretches to whatever height the text lands on, so the
 * card fills exactly as before.
 */
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
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
    onImageFailed: () -> Unit,
    modifier: Modifier = Modifier,
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

    // Swipe right = refresh, swipe left = delete. confirmValueChange always
    // returns false so the card springs back; deletion is animated by the
    // list itself (and remains undoable via the snackbar). The action only
    // triggers past HALF the card width, with a haptic tick at the
    // threshold, so accidental part-swipes don't delete anything.
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> onRefresh()
                SwipeToDismissBoxValue.EndToStart -> onDelete()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false
        },
        positionalThreshold = { totalDistance -> totalDistance * 0.5f },
    )
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(dismissState.targetValue) {
        if (dismissState.targetValue != SwipeToDismissBoxValue.Settled) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    // TalkBack / switch-access parity for the swipe gestures.
    val refreshActionLabel = stringResource(R.string.action_refresh_link)
    val deleteActionLabel = stringResource(R.string.action_delete_link)
    val selectedStateLabel = stringResource(R.string.cd_selected)

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = showActions,
        enableDismissFromEndToStart = showActions,
        modifier = modifier.fillMaxWidth(),
        backgroundContent = {
            // Nothing to draw unless a swipe is in progress (the card is
            // semi-transparent during its entrance animation).
            if (dismissState.dismissDirection == SwipeToDismissBoxValue.Settled) return@SwipeToDismissBox
            val isDelete = dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Box(
                contentAlignment = if (isDelete) Alignment.CenterEnd else Alignment.CenterStart,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        if (isDelete) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        }
                    )
                    .padding(horizontal = 28.dp),
            ) {
                Icon(
                    if (isDelete) Icons.Default.Delete else Icons.Default.Refresh,
                    contentDescription = if (isDelete) deleteActionLabel else refreshActionLabel,
                    tint = if (isDelete) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    },
                )
            }
        },
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = containerColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor),
            modifier = Modifier
                .fillMaxWidth()
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
                    onClick = onClick,
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
            LinkCardBody(
                link = link,
                thumbnailOverlay = {
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
                },
                onImageFailed = onImageFailed,
            )
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
) {
    // IntrinsicSize.Min lets the thumbnail stretch to exactly the row's
    // content height (edge to edge minus a small aesthetic gap) instead of
    // floating as a small square in empty space.
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
                .fillMaxHeight(),
        ) {
            AsyncImage(
                model = imageRequest,
                contentDescription = link.title,
                contentScale = if (hasImage) ContentScale.Crop else ContentScale.Fit,
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
                    .fillMaxHeight()
                    .defaultMinSize(minHeight = 104.dp)
                    .width(116.dp)
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
        }
    }
}

@Composable
internal fun CategoryBadge(category: String, modifier: Modifier = Modifier) {
    Text(
        text = category,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
