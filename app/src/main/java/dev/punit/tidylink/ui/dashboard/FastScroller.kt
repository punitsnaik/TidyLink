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

internal fun fastScrollThumbHeightPx(
    trackHeightPx: Int,
    viewportHeightPx: Int,
    contentHeightPx: Int,
    minimumPx: Int,
): Int {
    if (trackHeightPx <= 0 || viewportHeightPx <= 0 || contentHeightPx <= 0) return minimumPx
    val proportional = trackHeightPx.toLong() * viewportHeightPx / contentHeightPx
    return proportional.coerceIn(minimumPx.toLong(), trackHeightPx.toLong()).toInt()
}

/**
 * Height of every row before [row], in pixels.
 *
 * ponytail: O(rows) and walked twice per frame. At a few thousand rows that
 * is tens of microseconds against a 16ms budget; if a library ever gets big
 * enough to matter, keep a running prefix-sum array instead of re-walking.
 */
private fun heightAbove(row: Int, rowHeightPx: (Int) -> Int): Long {
    var sum = 0L
    for (r in 0 until row) sum += rowHeightPx(r).coerceAtLeast(0)
    return sum
}

/**
 * Thumb position (0..1) to a link index, clamped into the list.
 *
 * The exact inverse of [fastScrollFraction], walking the same per-row
 * heights - so releasing a drag cannot make the thumb jump to a different
 * place than the one the user let go of. A fraction-times-itemCount
 * shortcut is only equivalent when every row is the same height, and these
 * rows are emphatically not.
 *
 * [itemCount] can legitimately be 0 - AnimatedVisibility keeps composing
 * during the exit fade, and paging can invalidate mid-drag - and a naive
 * `coerceIn(0, itemCount - 1)` throws on an empty list. That crash shipped
 * once; this is the one place it can happen, so it lives behind a test.
 */
internal fun fastScrollTargetIndex(
    fraction: Float,
    itemCount: Int,
    columns: Int,
    totalRows: Int,
    viewportHeightPx: Int,
    rowHeightPx: (Int) -> Int,
): Int {
    if (itemCount <= 0 || totalRows <= 0 || columns <= 0) return 0
    val last = itemCount - 1
    val travelPx = heightAbove(totalRows, rowHeightPx) - viewportHeightPx
    if (travelPx <= 0L) return 0
    val targetPx = (fraction.coerceIn(0f, 1f) * travelPx).toLong()
    var accumulated = 0L
    var row = 0
    while (row < totalRows - 1 && accumulated + rowHeightPx(row) <= targetPx) {
        accumulated += rowHeightPx(row)
        row++
    }
    return (row * columns).coerceIn(0, last)
}

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
 * It measures PIXELS, not rows. Mapping the thumb over row indexes makes it
 * travel the same distance for every row regardless of how tall that row
 * is, so a run of short cards races the thumb down the track and a run of
 * tall ones drags it - the "sometimes it moves very fast in the middle"
 * report. Weighting each row by its measured height makes the thumb move at
 * one speed for one speed of scrolling, everywhere in the library.
 *
 * [rowHeightPx] supplies each row's measured height (falling back to the
 * library average for rows not yet laid out). It is the SAME function that
 * sizes the sub-row term, which is what makes the mapping continuous by
 * construction: scrolling to the bottom of row N puts the thumb at exactly
 * the pixel where row N+1 starts, whatever the two rows' heights are.
 */
internal fun fastScrollFraction(
    firstVisibleRow: Int,
    rowScrollOffsetPx: Int,
    totalRows: Int,
    viewportHeightPx: Int,
    rowHeightPx: (Int) -> Int,
): Float {
    if (totalRows <= 0) return 0f
    val travelPx = heightAbove(totalRows, rowHeightPx) - viewportHeightPx
    if (travelPx <= 0L) return 0f
    // Clamped to this row's own height: the sub-row term may never carry
    // past where the next row begins, so the sequence stays monotonic even
    // if the cached height is momentarily stale.
    val withinRow = rowScrollOffsetPx.coerceIn(0, rowHeightPx(firstVisibleRow).coerceAtLeast(0))
    val scrolledPx = heightAbove(firstVisibleRow, rowHeightPx) + withinRow
    return (scrolledPx.toFloat() / travelPx).coerceIn(0f, 1f)
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
 * Paging placeholders keep [itemCount] equal to the query's real total, so
 * dragging to the bottom does not move the target as more pages load.
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
    // Measured height per LINK row (header row excluded from the key), written
    // from inside layoutFraction's derivedStateOf below. Recorded once per row
    // rather than re-averaged from the visible window every frame, so a slow
    // drag lingering on one row cannot bias the average toward it. Keyed on
    // indexOffset because the header occupying row 0 shifts every link's raw
    // row number by one; a cache carried across a selection-mode toggle would
    // silently point its keys at the wrong rows.
    val rowHeightByIndex = remember(indexOffset) { mutableMapOf<Int, Int>() }
    // Widest row seen this session. Counting columns from the visible items
    // alone reads 1 when only a partial last row is on screen, which doubles
    // totalRows and halves the thumb's position for that frame. A grid's
    // column count never shrinks mid-session, so the maximum is the truth.
    // Deliberately a plain holder, not a State: it is written from inside
    // derivedStateOf, and writing an observable there would loop.
    val columnsSeen = remember(indexOffset) { intArrayOf(1) }
    // Library-wide average, refreshed whenever the cache above grows. Rows
    // the grid has never laid out have no measured height, so they stand in
    // at the average until they are scrolled into view - which is what keeps
    // the track a sane length before the whole library has been visited.
    val avgRowHeight = remember(indexOffset) { intArrayOf(0) }
    // One definition of "how tall is row r", shared by the thumb's position
    // and by the drag's inverse mapping. If these two ever disagreed, letting
    // go of a drag would snap the thumb somewhere else.
    val rowHeightAt = remember(indexOffset) {
        { row: Int -> rowHeightByIndex[row] ?: avgRowHeight[0] }
    }

    // Where the list actually is, as a 0..1 fraction of the scrollable
    // PIXELS. [indexOffset] discounts non-link items the grid puts ahead of
    // the data (the collapsing header), so the thumb still maps over links.
    val layoutFraction by remember(indexOffset) {
        derivedStateOf {
            val info = gridState.layoutInfo
            // Header excluded: it is full-span and taller than a card, so
            // letting it define the row height would skew every row after it.
            val links = info.visibleItemsInfo.filter { it.index >= indexOffset }
            if (links.isEmpty()) return@derivedStateOf 0f
            // The header takes a full-span grid row of its own ahead of the
            // links, so link rows are numbered one behind grid rows.
            val headerRows = if (indexOffset > 0) 1 else 0
            columnsSeen[0] = maxOf(columnsSeen[0], links.maxOf { it.column } + 1)
            val columns = columnsSeen[0]

            // A grid row is as tall as its TALLEST card, not as tall as
            // whichever of its cards happened to be iterated last. Recorded
            // once per row, so the average converges instead of tracking
            // whichever 2-4 rows are on screen this frame.
            for (item in links) {
                val row = item.row - headerRows
                rowHeightByIndex[row] = maxOf(rowHeightByIndex[row] ?: 0, item.size.height)
            }
            avgRowHeight[0] = rowHeightByIndex.values.average().roundToInt()

            // BOTH the row and the offset within it come from gridState's
            // scroll position - never one from there and the other from
            // layoutInfo. Mixing the two was the actual bug behind four
            // rounds of thumb jitter. firstVisibleItemIndex and
            // layoutInfo.visibleItemsInfo are published by different passes,
            // so around every row boundary they disagree for a frame or two
            // about which row is first; pairing layoutInfo's row with
            // gridState's offset then placed the thumb a whole row away, and
            // the two sources took turns winning frame by frame.
            //
            // Measured, not assumed: pixel-tracking screen-20260809-143922.mp4
            // at its native 60fps shows a square wave of a FLAT 137px on a
            // 2123px track, riding on an otherwise smooth fling - amplitude
            // independent of both position and velocity, and the flips locked
            // to two fixed phases inside each 319px row. On that library's
            // geometry 137px works out to 1.10 rows: one row, exactly. A
            // constant one-row offset is why none of the previous fixes
            // helped - smoothing, EMAs, per-row height caches and freezing
            // the item count all adjust *magnitudes*, and none of them made
            // the two reads agree about *which row* is first.
            val firstLinkIndex =
                (gridState.firstVisibleItemIndex - indexOffset).coerceAtLeast(0)
            val firstVisibleRow = firstLinkIndex / columns
            // Zero while the header is still the first visible item: the
            // offset would be measuring the header, and the thumb belongs at
            // the top anyway.
            val rowScrollOffsetPx = if (gridState.firstVisibleItemIndex >= indexOffset) {
                gridState.firstVisibleItemScrollOffset
            } else {
                0
            }

            // Read live from layoutInfo, NOT from the itemCount parameter:
            // this block is remember(indexOffset), so anything captured from
            // the composition freezes at whatever it was when the block was
            // first built. Same trap that nearly shipped a frozen scroll
            // state here once already.
            val linkCount = (info.totalItemsCount - indexOffset).coerceAtLeast(0)

            fastScrollFraction(
                firstVisibleRow = firstVisibleRow,
                rowScrollOffsetPx = rowScrollOffsetPx,
                totalRows = ceil(linkCount.toFloat() / columns).toInt(),
                viewportHeightPx = info.viewportSize.height,
                rowHeightPx = rowHeightAt,
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
            val info = gridState.layoutInfo
            val cols = columnsSeen[0]
            val rows = ceil((info.totalItemsCount - indexOffset).coerceAtLeast(0).toFloat() / cols).toInt()
            val contentHeightPx = heightAbove(rows, rowHeightAt).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val minimumThumbPx = with(density) { THUMB_HEIGHT.roundToPx() }
            val thumbHeightPx = fastScrollThumbHeightPx(
                trackHeightPx,
                info.viewportSize.height,
                contentHeightPx,
                minimumThumbPx,
            ).toFloat()
            val travelPx = (trackHeightPx - thumbHeightPx).coerceAtLeast(1f)
            val offsetY = (fraction * travelPx).roundToInt()
            // The exact inverse of layoutFraction, fed the SAME inputs read
            // the same way - so the bubble names the link the thumb points
            // at, and letting go of a drag leaves the thumb where the finger
            // left it instead of snapping.
            val indexAtFraction: (Float) -> Int = {
                val info = gridState.layoutInfo
                val links = (info.totalItemsCount - indexOffset).coerceAtLeast(0)
                val cols = columnsSeen[0]
                fastScrollTargetIndex(
                    fraction = it,
                    itemCount = links,
                    columns = cols,
                    totalRows = ceil(links.toFloat() / cols).toInt(),
                    viewportHeightPx = info.viewportSize.height,
                    rowHeightPx = rowHeightAt,
                )
            }
            val targetIndex = indexAtFraction(fraction)

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
                    .height(with(density) { thumbHeightPx.toDp() })
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
                                val target = indexAtFraction(dragFraction)
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
