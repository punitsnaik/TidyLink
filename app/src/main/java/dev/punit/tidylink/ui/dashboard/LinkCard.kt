package dev.punit.tidylink.ui.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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

/**
 * How far a read link recedes. Low enough to read as "handled" at a
 * glance, high enough that the title stays legible - these links are still
 * in the library and still searchable, they just aren't the queue any more.
 */
private const val READ_ALPHA = 0.55f

// --- Summary line-count derivation (see LinkCardBody / summaryMaxLines) ---

/** Never fewer lines than this, even beside the shortest possible thumbnail. */
private const val MIN_SUMMARY_LINES = 2

/** Sanity ceiling so a very tall portrait thumbnail can't ask for a wall of text. */
private const val MAX_SUMMARY_LINES = 8

/**
 * maxLines on the title [Text] below, and the value the reservation math
 * assumes the title costs. One constant for both so they can't drift apart.
 */
private const val TITLE_MAX_LINES = 2

// The rest of these mirror the literal padding/spacing values used in the
// details Column below. Kept as named constants rather than measured,
// because measuring them would need another SubcomposeLayout-shaped
// workaround (see the "why not BoxWithConstraints" note on
// summaryMaxLines). If those paddings change, update these too.
private val DETAILS_COLUMN_TOP_PADDING = 10.dp
private val DETAILS_COLUMN_BOTTOM_PADDING = 10.dp
private val BADGE_VERTICAL_PADDING = 3.dp
private val BADGE_TO_TITLE_SPACER = 6.dp
private val TITLE_TO_SUMMARY_SPACER = 4.dp

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
    val shouldAnimateEntrance = remember { animateEntrance }
    val entrance = remember { Animatable(if (shouldAnimateEntrance) 0f else 1f) }
    if (shouldAnimateEntrance) {
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
    val readStateLabel = stringResource(R.string.cd_read)

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
                    // Read links recede rather than disappear - folded into
                    // the existing entrance alpha instead of a second
                    // modifier, so there's still one layer per card.
                    alpha = entrance.value * if (link.isRead) READ_ALPHA else 1f
                    translationY = (1f - entrance.value) * 24.dp.toPx()
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
                    // Dimming conveys "read" visually; TalkBack needs it said.
                    if (selected) {
                        stateDescription = selectedStateLabel
                    } else if (link.isRead) {
                        stateDescription = readStateLabel
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
 * that is identical between the library grid and trash (`TrashSheet`).
 * Everything that differs between those two contexts - selection, refresh
 * spinner, pinned star, swipe-to-dismiss, the outer `Card`, entrance/press
 * animation, and read/unread dimming - stays with the caller. [thumbnailOverlay]
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
    Row(
        modifier = modifier
            .height(IntrinsicSize.Min)
            // animateContentSize: text appearing after a scrape/
            // classification grows the card smoothly instead of snapping.
            .animateContentSize(),
    ) {
        // Thumbnail (or favicon placeholder) with selection / refresh
        // overlays. Fills the card height; 5dp gap on all sides.
        // Load failure is tracked keyed on the image URL itself
        // (remember(link.imageUrl), not a plain boolean) so a
        // re-scrape that hands the link a different URL starts
        // fresh instead of the card staying pinned to the favicon
        // fallback forever.
        var imageLoadFailed by remember(link.imageUrl) { mutableStateOf(false) }
        val hasImage = !link.imageUrl.isNullOrBlank() && !imageLoadFailed

        // The thumbnail's rendered height, re-measured (and reset to
        // unmeasured) whenever the image URL changes - same keying as
        // imageLoadFailed above, for the same reason.
        var thumbnailHeightPx by remember(link.imageUrl) { mutableStateOf(0) }

        Box(
            modifier = Modifier
                .padding(5.dp)
                .fillMaxHeight(),
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(if (hasImage) link.imageUrl else faviconUrl(link.url))
                    .crossfade(true)
                    .build(),
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
                    .onSizeChanged { thumbnailHeightPx = it.height }
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
                    maxLines = summaryMaxLines(thumbnailHeightPx),
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * How many lines the summary may use beside a thumbnail measured at
 * [thumbnailHeightPx] pixels tall (0 before the first measurement, which
 * lands on [MIN_SUMMARY_LINES] - the same look the card had before this
 * ever runs).
 *
 * Derived from the THUMBNAIL's own height, never from the details column's
 * height: a column-based derivation would oscillate, because more lines
 * makes the column taller, which permits more lines, which makes it taller
 * again. The thumbnail is measured independently of the text it sits next
 * to, so the summary can only ever fill space the thumbnail already
 * claimed - it can never become the Row's tallest child and grow the card.
 * (A `BoxWithConstraints` here would read simpler, but it's a
 * `SubcomposeLayout`, and those can't answer intrinsic-measurement queries;
 * nesting one inside this Row's `IntrinsicSize.Min` throws.)
 *
 * The reservation below assumes the WORST case for everything above the
 * summary - a full [TITLE_MAX_LINES]-line title even when it's actually
 * one line - on purpose. Under-filling by a line is only cosmetic; reserving
 * too little is not, because that's what lets the summary overflow past
 * where the thumbnail ends and re-inflate the card - the exact bug this
 * whole design exists to avoid. When in doubt, this reserves more.
 */
@Composable
private fun summaryMaxLines(thumbnailHeightPx: Int): Int {
    if (thumbnailHeightPx <= 0) return MIN_SUMMARY_LINES
    val density = LocalDensity.current
    val badgeStyle = MaterialTheme.typography.labelSmall
    val titleStyle = MaterialTheme.typography.titleSmall
    val summaryStyle = MaterialTheme.typography.bodySmall
    return with(density) {
        val reservedAbovePx = DETAILS_COLUMN_TOP_PADDING.toPx() +
            DETAILS_COLUMN_BOTTOM_PADDING.toPx() +
            badgeStyle.lineHeight.toPx() + 2 * BADGE_VERTICAL_PADDING.toPx() +
            BADGE_TO_TITLE_SPACER.toPx() +
            titleStyle.lineHeight.toPx() * TITLE_MAX_LINES +
            TITLE_TO_SUMMARY_SPACER.toPx()
        val summaryLineHeightPx = summaryStyle.lineHeight.toPx()
        val availablePx = thumbnailHeightPx - reservedAbovePx
        (availablePx / summaryLineHeightPx).toInt().coerceIn(MIN_SUMMARY_LINES, MAX_SUMMARY_LINES)
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
