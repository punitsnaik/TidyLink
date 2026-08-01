package dev.punit.tidylink.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fast scroller's thumb-to-index mapping. The empty-list cases are the
 * point: a naive coerceIn(0, itemCount - 1) throws when itemCount is 0, and
 * that crash shipped once (AnimatedVisibility composes during the exit fade,
 * so the thumb can be laid out against a list that just emptied).
 */
class FastScrollTargetIndexTest {

    @Test
    fun empty_list_returns_zero_and_does_not_throw() {
        assertEquals(0, fastScrollTargetIndex(0f, 0))
        assertEquals(0, fastScrollTargetIndex(0.5f, 0))
        assertEquals(0, fastScrollTargetIndex(1f, 0))
    }

    @Test
    fun single_item_list_always_lands_on_it() {
        assertEquals(0, fastScrollTargetIndex(0f, 1))
        assertEquals(0, fastScrollTargetIndex(1f, 1))
    }

    @Test
    fun maps_ends_and_middle_of_the_track() {
        assertEquals(0, fastScrollTargetIndex(0f, 101))
        assertEquals(50, fastScrollTargetIndex(0.5f, 101))
        assertEquals(100, fastScrollTargetIndex(1f, 101))
    }

    @Test
    fun out_of_range_fractions_clamp_into_the_list() {
        assertEquals(0, fastScrollTargetIndex(-0.5f, 10))
        assertEquals(9, fastScrollTargetIndex(1.5f, 10))
    }

    // --- fastScrollFraction: the list driving the thumb ---------------------

    @Test
    fun thumb_is_at_the_top_when_nothing_has_scrolled() {
        assertEquals(0f, fraction(row = 0, offset = 0), 0f)
    }

    @Test
    fun thumb_reaches_the_bottom_at_the_last_scrollable_row() {
        // 50 rows of 400px in a 2000px viewport: 5 rows fit, so row 45 is
        // as far as the list goes.
        assertEquals(1f, fraction(row = 45, offset = 0), 0.001f)
        assertEquals("past the end still clamps", 1f, fraction(row = 60, offset = 0), 0f)
    }

    /**
     * The actual bug this replaced. Using the whole-row index alone, the
     * thumb only moves when a row passes - a staircase that reads as
     * shivering with uneven card heights. Half a row scrolled must put the
     * thumb half a step along, not leave it where it was.
     */
    @Test
    fun scrolling_within_a_row_moves_the_thumb_proportionally() {
        val atRow = fraction(row = 10, offset = 0)
        val halfPast = fraction(row = 10, offset = 200)
        val nextRow = fraction(row = 11, offset = 0)

        assertTrue("half a row must move the thumb", halfPast > atRow)
        assertTrue("and not overshoot the next row", halfPast < nextRow)
        assertEquals("exactly halfway between", (atRow + nextRow) / 2, halfPast, 0.0001f)
    }

    /**
     * The other half of the bug: the old denominator counted how many items
     * were visible right now, which flips by one as a row half-enters the
     * viewport. Here the viewport is fixed, so the thumb can only ever move
     * forwards as the list does.
     */
    @Test
    fun thumb_never_moves_backwards_while_the_list_scrolls_forwards() {
        var previous = -1f
        for (px in 0..(45 * 400) step 37) {
            val current = fraction(row = px / 400, offset = px % 400)
            assertTrue("went backwards at ${px}px: $current < $previous", current >= previous)
            previous = current
        }
    }

    @Test
    fun degenerate_layouts_return_zero_instead_of_dividing_by_zero() {
        // Before the first measure pass, and for a list that fits on screen.
        assertEquals(0f, fastScrollFraction(0, 0, rowHeightPx = 0, totalRows = 10, viewportHeightPx = 2000), 0f)
        assertEquals(0f, fastScrollFraction(0, 0, rowHeightPx = 400, totalRows = 0, viewportHeightPx = 2000), 0f)
        assertEquals(0f, fastScrollFraction(0, 0, rowHeightPx = 400, totalRows = 3, viewportHeightPx = 2000), 0f)
    }

    /** 50 rows of 400px, 2000px viewport - 45 rows of travel. */
    private fun fraction(row: Int, offset: Int): Float = fastScrollFraction(
        firstVisibleRow = row,
        rowScrollOffsetPx = offset,
        rowHeightPx = 400,
        totalRows = 50,
        viewportHeightPx = 2000,
    )
}
