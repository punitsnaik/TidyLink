package dev.punit.tidylink.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt

private val THUMB_HEIGHT = 48.dp

/**
 * Thumb position (0..1) to a link index, clamped into the list.
 *
 * [itemCount] can legitimately be 0 - AnimatedVisibility keeps composing
 * during the exit fade, and paging can invalidate mid-drag - and a naive
 * `coerceIn(0, itemCount - 1)` throws on an empty list. That crash shipped
 * once; this is the one place it can happen, so it lives behind a test.
 */
internal fun fastScrollTargetIndex(fraction: Float, itemCount: Int): Int =
    (fraction * (itemCount - 1))
        .roundToInt()
        .coerceIn(0, (itemCount - 1).coerceAtLeast(0))

/**
 * Where the thumb sits, 0..1, while the LIST is driving it.
 *
 * Row-based and offset-aware on purpose. The obvious version - first
 * visible item index over the item count - only moves the thumb when a
 * whole row passes, so the thumb walks down a staircase instead of
 * sliding; with cards of uneven height those steps land at uneven times,
 * which reads as the thumb shivering rather than tracking the list.
 * [rowScrollOffsetPx] makes the movement continuous within a row.
 *
 * It also takes [viewportHeightPx] rather than "how many items are visible
 * right now". That count flips between n and n+1 as a row half-enters the
 * viewport, and a denominator that changes every frame nudges the thumb
 * BACKWARDS while the list scrolls forwards - visible jitter on a short
 * library, where one item is a large share of the track.
 *
 * ponytail: [rowHeightPx] is the first visible row's height, so mixed card
 * heights make this an approximation. It stays monotonic, which is all a
 * seek bar needs; exact would mean measuring every row in the library.
 */
internal fun fastScrollFraction(
    firstVisibleRow: Int,
    rowScrollOffsetPx: Int,
    rowHeightPx: Int,
    totalRows: Int,
    viewportHeightPx: Int,
): Float {
    if (rowHeightPx <= 0 || totalRows <= 0) return 0f
    // Fractional on purpose: rounding the visible row count to a whole
    // number reintroduces the same off-by-one wobble in the denominator.
    val scrollableRows = totalRows - viewportHeightPx.toFloat() / rowHeightPx
    if (scrollableRows <= 0f) return 0f
    val current = firstVisibleRow + rowScrollOffsetPx.toFloat() / rowHeightPx
    return (current / scrollableRows).coerceIn(0f, 1f)
}

/**
 * Google Photos style fast scroller: a capsule thumb on the right edge that
 * appears while the list scrolls, fades away when idle, and can be dragged
 * to jump through the list. While dragging, a bubble next to the thumb
 * shows [bubbleTextForIndex] for the target position (the saved month of
 * the links being passed).
 *
 * The thumb maps linearly over item INDEXES, not pixels - with variable
 * card heights that's an approximation, but it is monotonic and stable,
 * which is all a seek bar needs.
 *
 * ponytail: with enablePlaceholders=false the paged item count only covers
 * loaded pages, so a drag to the bottom lands on the last loaded item and
 * the track keeps growing as more pages stream in. Fine at real library
 * sizes; switch the pager to placeholders if thumb-jumping ever matters.
 */
@Composable
internal fun FastScroller(
    gridState: LazyGridState,
    itemCount: Int,
    bubbleTextForIndex: (Int) -> String?,
    modifier: Modifier = Modifier,
    indexOffset: Int = 0,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var trackHeightPx by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var scrollJob: Job? by remember { mutableStateOf(null) }

    // Where the list actually is, as a 0..1 fraction of scrollable rows.
    // [indexOffset] discounts non-link items the grid puts ahead of the data
    // (the collapsing header), so the thumb still maps over links only.
    val layoutFraction by remember(indexOffset) {
        derivedStateOf {
            val info = gridState.layoutInfo
            // Header excluded: it is full-span and taller than a card, so
            // letting it define the row height would skew every row after it.
            val links = info.visibleItemsInfo.filter { it.index >= indexOffset }
            val firstLink = links.firstOrNull() ?: return@derivedStateOf 0f
            val columns = (links.maxOf { it.column } + 1).coerceAtLeast(1)
            val linkCount = (info.totalItemsCount - indexOffset).coerceAtLeast(0)
            fastScrollFraction(
                // The header occupies one full-span row of its own, so the
                // grid's row numbers run one ahead of the link rows.
                firstVisibleRow = (firstLink.row - if (indexOffset > 0) 1 else 0)
                    .coerceAtLeast(0),
                // Only meaningful once a link IS the first visible item -
                // while the header is still on screen this offset measures
                // the header, and the thumb belongs at the top anyway.
                rowScrollOffsetPx = if (gridState.firstVisibleItemIndex >= indexOffset) {
                    gridState.firstVisibleItemScrollOffset
                } else {
                    0
                },
                rowHeightPx = firstLink.size.height,
                totalRows = ceil(linkCount.toFloat() / columns).toInt(),
                viewportHeightPx = info.viewportSize.height,
            )
        }
    }
    // While dragging the thumb follows the finger, not the layout - feeding
    // the layout-derived fraction back during a drag makes the thumb jitter.
    val fraction = if (dragging) dragFraction else layoutFraction

    val scrollable = gridState.canScrollForward || gridState.canScrollBackward
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(gridState.isScrollInProgress, dragging) {
        if (gridState.isScrollInProgress || dragging) {
            visible = true
        } else {
            delay(1200)
            visible = false
        }
    }

    AnimatedVisibility(
        visible = visible && scrollable && itemCount > 0,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(160.dp)
                .onSizeChanged { trackHeightPx = it.height },
        ) {
            val thumbHeightPx = with(density) { THUMB_HEIGHT.toPx() }
            val travelPx = (trackHeightPx - thumbHeightPx).coerceAtLeast(1f)
            val offsetY = (fraction * travelPx).roundToInt()
            val targetIndex = fastScrollTargetIndex(fraction, itemCount)

            if (dragging) {
                bubbleTextForIndex(targetIndex)?.let { label ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shadowElevation = 4.dp,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset { IntOffset(0, offsetY) }
                            .padding(end = 36.dp),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            // Wide transparent hit area, slim visible capsule inside it.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, offsetY) }
                    .width(28.dp)
                    .height(THUMB_HEIGHT)
                    .pointerInput(itemCount, trackHeightPx, indexOffset) {
                        detectVerticalDragGestures(
                            onDragStart = {
                                dragging = true
                                dragFraction = layoutFraction
                            },
                            onDragEnd = { dragging = false },
                            onDragCancel = { dragging = false },
                            onVerticalDrag = { change, delta ->
                                change.consume()
                                dragFraction =
                                    (dragFraction + delta / travelPx).coerceIn(0f, 1f)
                                val target = fastScrollTargetIndex(dragFraction, itemCount)
                                // Cancel any scroll still catching up from a prior pointer
                                // move - without this, scrollToItem calls queue up (the grid
                                // serializes them) and the list lags behind the finger,
                                // "catching up" in jerks once the drag pauses.
                                scrollJob?.cancel()
                                scrollJob = scope.launch { gridState.scrollToItem(target + indexOffset) }
                            },
                        )
                    },
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 4.dp)
                        .width(6.dp)
                        .fillMaxHeight(),
                ) {}
            }
        }
    }
}
