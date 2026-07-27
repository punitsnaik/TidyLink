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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val THUMB_HEIGHT = 48.dp

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
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var trackHeightPx by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    // Where the list actually is, as a 0..1 fraction of scrollable indexes.
    val layoutFraction by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val scrollable = info.totalItemsCount - info.visibleItemsInfo.size
            if (scrollable <= 0) {
                0f
            } else {
                (gridState.firstVisibleItemIndex.toFloat() / scrollable).coerceIn(0f, 1f)
            }
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
            val targetIndex = (fraction * (itemCount - 1)).roundToInt()
                .coerceIn(0, itemCount - 1)

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
                    .pointerInput(itemCount, trackHeightPx) {
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
                                val target = (dragFraction * (itemCount - 1))
                                    .roundToInt()
                                    .coerceIn(0, itemCount - 1)
                                scope.launch { gridState.scrollToItem(target) }
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
