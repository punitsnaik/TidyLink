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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.delay

/**
 * How far a read link recedes. Low enough to read as "handled" at a
 * glance, high enough that the title stays legible - these links are still
 * in the library and still searchable, they just aren't the queue any more.
 */
private const val READ_ALPHA = 0.55f

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
            entrance.animateTo(1f, tween(durationMillis = 300))
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
            // IntrinsicSize.Min lets the thumbnail stretch to exactly the
            // card's content height (edge to edge minus a small aesthetic
            // gap) instead of floating as a small square in empty space.
            Row(
                modifier = Modifier
                    .height(IntrinsicSize.Min)
                    // animateContentSize: text appearing after a scrape/
                    // classification grows the card smoothly instead of snapping.
                    .animateContentSize(),
            ) {
                // Thumbnail (or favicon placeholder) with selection / refresh
                // overlays. Fills the card height; 5dp gap on all sides.
                val hasImage = !link.imageUrl.isNullOrBlank()
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
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )

                    if (link.aiSummary.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = link.aiSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    if (link.tags.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        // Plain Row, not LazyRow: lazy layouts can't be
                        // measured inside IntrinsicSize.Min and would crash.
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        ) {
                            link.tags.forEach { tag -> TagChip(tag) }
                        }
                    }
                }
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

@Composable
internal fun TagChip(tag: String, modifier: Modifier = Modifier) {
    Text(
        text = "#$tag",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
