package dev.punit.tidylink.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The fast scroller's two mappings: list -> thumb ([fastScrollFraction]) and
 * thumb -> list ([fastScrollTargetIndex]). They must be exact inverses of
 * each other and both must weight rows by their real heights, because
 * LinkCard is content-sized and a library mixes short text-only cards with
 * tall thumbnail ones.
 *
 * The empty-list cases are load-bearing: a naive coerceIn(0, itemCount - 1)
 * throws when itemCount is 0, and that crash shipped once (AnimatedVisibility
 * composes during the exit fade, so the thumb can be laid out against a list
 * that just emptied).
 */
class FastScrollTargetIndexTest {

    /** A deliberately lumpy library: 4x the tallest row against the shortest. */
    private val unevenRows = listOf(900, 320, 640, 320, 1100, 400, 380, 950)

    // --- fastScrollTargetIndex: the thumb driving the list -----------------

    @Test
    fun empty_list_returns_zero_and_does_not_throw() {
        assertEquals(0, index(0f, itemCount = 0))
        assertEquals(0, index(0.5f, itemCount = 0))
        assertEquals(0, index(1f, itemCount = 0))
    }

    @Test
    fun single_item_list_always_lands_on_it() {
        assertEquals(0, index(0f, itemCount = 1, totalRows = 1))
        assertEquals(0, index(1f, itemCount = 1, totalRows = 1))
    }

    /**
     * The bottom of the track is the last SCROLL POSITION, not the last
     * item: 51 rows of 400px in a 2000px viewport leaves row 46 at the top
     * when the list is scrolled all the way down. Targeting item 50 instead
     * would ask for a scroll the grid clamps anyway, and would make the
     * drag bubble name a link that is not the one at the top.
     */
    @Test
    fun maps_both_ends_of_the_track_into_the_list() {
        assertEquals(0, index(0f))
        assertEquals(46, index(1f))
        val middle = index(0.5f)
        assertTrue("middle must sit between the ends: $middle", middle in 1..45)
    }

    @Test
    fun out_of_range_fractions_clamp_into_the_track() {
        // 10 rows of 400px, 2000px viewport - 2000px of travel, so the
        // bottom of the track is row 5.
        assertEquals(0, index(-0.5f, itemCount = 10, totalRows = 10))
        assertEquals(5, index(1.5f, itemCount = 10, totalRows = 10))
    }

    @Test
    fun a_list_that_fits_on_screen_has_nowhere_to_scroll() {
        assertEquals(0, index(1f, itemCount = 3, totalRows = 3, viewportHeightPx = 2000))
    }

    @Test
    fun multi_column_grids_land_on_the_first_item_of_the_target_row() {
        // 2 columns, 400px rows, 50 rows = 100 items, 2000px viewport.
        val at = index(1f, itemCount = 100, columns = 2, totalRows = 50)
        assertEquals("last scrollable row is 45, first item of it is 90", 90, at)
    }

    // --- fastScrollFraction: the list driving the thumb --------------------

    @Test
    fun thumb_is_at_the_top_when_nothing_has_scrolled() {
        assertEquals(0f, frac(row = 0, offset = 0), 0f)
    }

    @Test
    fun thumb_reaches_the_bottom_at_the_last_scrollable_row() {
        // 50 rows of 400px in a 2000px viewport: 5 rows fit, so row 45 is
        // as far as the list goes.
        assertEquals(1f, frac(row = 45, offset = 0), 0.001f)
        assertEquals("past the end still clamps", 1f, frac(row = 60, offset = 0), 0f)
    }

    /**
     * The original bug this replaced. Using the whole-row index alone, the
     * thumb only moves when a row passes - a staircase that reads as
     * shivering with uneven card heights. Half a row scrolled must put the
     * thumb half a step along, not leave it where it was.
     */
    @Test
    fun scrolling_within_a_row_moves_the_thumb_proportionally() {
        val atRow = frac(row = 10, offset = 0)
        val halfPast = frac(row = 10, offset = 200)
        val nextRow = frac(row = 11, offset = 0)

        assertTrue("half a row must move the thumb", halfPast > atRow)
        assertTrue("and not overshoot the next row", halfPast < nextRow)
        assertEquals("exactly halfway between", (atRow + nextRow) / 2, halfPast, 0.0001f)
    }

    @Test
    fun thumb_never_moves_backwards_while_the_list_scrolls_forwards() {
        var previous = -1f
        for (px in 0..(45 * 400) step 37) {
            val current = frac(row = px / 400, offset = px % 400)
            assertTrue("went backwards at ${px}px: $current < $previous", current >= previous)
            previous = current
        }
    }

    @Test
    fun degenerate_layouts_return_zero_instead_of_dividing_by_zero() {
        // Before the first measure pass, and for a list that fits on screen.
        assertEquals(0f, frac(0, 0, rows = { 0 }), 0f)
        assertEquals(0f, frac(0, 0, totalRows = 0), 0f)
        assertEquals(0f, frac(0, 0, totalRows = 3), 0f)
    }

    /**
     * Rows of UNEVEN height, walked a few pixels at a time across several
     * boundaries. The sub-row term is divided by the height of the row it
     * belongs to, so it lands exactly where the next row starts. Dividing by
     * a library-wide average instead overshoots on tall rows and undershoots
     * on short ones, and the correction shows up as a backward step right at
     * the crossing.
     */
    @Test
    fun uneven_row_heights_never_step_the_thumb_backwards() {
        var previous = -1f
        for (row in unevenRows.indices) {
            for (px in 0 until unevenRows[row] step 7) {
                val current = frac(row, px, totalRows = unevenRows.size, viewportHeightPx = 500, rows = ::unevenRow)
                assertTrue(
                    "went backwards at row $row +${px}px: $current < $previous",
                    current >= previous,
                )
                previous = current
            }
        }
    }

    /**
     * The 2026-08-09 defect: the row and the scroll offset within it were
     * read from two sources that disagreed by one row for a frame or two
     * around each boundary. The end of row N and the start of row N+1 have
     * to be the same point, whatever those two rows' heights are.
     */
    @Test
    fun the_end_of_a_row_meets_the_start_of_the_next() {
        for (row in 0 until unevenRows.size - 1) {
            val endOfRow = frac(row, unevenRows[row], totalRows = unevenRows.size, viewportHeightPx = 500, rows = ::unevenRow)
            val startOfNext = frac(row + 1, 0, totalRows = unevenRows.size, viewportHeightPx = 500, rows = ::unevenRow)
            assertEquals("row $row must hand over to row ${row + 1}", startOfNext, endOfRow, 1e-6f)
        }
    }

    /** A stale cached height cannot push the thumb into the next row. */
    @Test
    fun an_offset_larger_than_the_cached_row_height_clamps_to_one_row() {
        val overshoot = frac(row = 10, offset = 9_999)
        val nextRow = frac(row = 11, offset = 0)
        assertTrue("must not overshoot the next row", overshoot <= nextRow)
    }

    /**
     * The "sometimes it moves very fast in the middle of the list" report.
     * With the thumb mapped over row INDEXES, every row moved it the same
     * distance, so a run of short cards raced it down the track. Mapped over
     * pixels, the distance the thumb covers must be proportional to the
     * pixels actually scrolled - so a 1100px row moves it a bit under 3.5x
     * as far as a 320px one, not the same distance.
     */
    @Test
    fun thumb_speed_follows_pixels_scrolled_not_rows_passed() {
        fun at(row: Int): Float =
            frac(row, 0, totalRows = unevenRows.size, viewportHeightPx = 500, rows = ::unevenRow)

        val shortRow = at(2) - at(1) // crossing the 320px row
        val tallRow = at(5) - at(4) // crossing the 1100px row

        assertTrue("a tall row must move the thumb further", tallRow > shortRow)
        assertEquals(
            "and by the ratio of their heights",
            1100f / 320f,
            tallRow / shortRow,
            0.01f,
        )
    }

    /**
     * The two mappings are inverses: dropping the thumb where the list
     * already is must not move the list. Without this, releasing a drag
     * snapped the thumb somewhere other than where the finger left it.
     */
    @Test
    fun dragging_to_where_the_list_already_is_lands_on_the_same_row() {
        for (row in unevenRows.indices) {
            val f = frac(row, 0, totalRows = unevenRows.size, viewportHeightPx = 500, rows = ::unevenRow)
            val landed = index(
                f,
                itemCount = unevenRows.size,
                totalRows = unevenRows.size,
                viewportHeightPx = 500,
                rows = ::unevenRow,
            )
            // Rows past the bottom of the track all share fraction 1, so only
            // reachable rows can round-trip. One row of float slack: the
            // fraction is a Float and the inverse truncates to whole pixels.
            if (f < 1f) {
                assertTrue("row $row round-tripped to $landed", abs(landed - row) <= 1)
            }
        }
    }

    // --- helpers ----------------------------------------------------------

    private fun unevenRow(row: Int): Int = unevenRows.getOrElse(row) { 400 }

    /** 50 rows of 400px, 2000px viewport - 45 rows of travel. */
    private fun frac(
        row: Int,
        offset: Int,
        totalRows: Int = 50,
        viewportHeightPx: Int = 2000,
        rows: (Int) -> Int = { 400 },
    ): Float = fastScrollFraction(
        firstVisibleRow = row,
        rowScrollOffsetPx = offset,
        totalRows = totalRows,
        viewportHeightPx = viewportHeightPx,
        rowHeightPx = rows,
    )

    private fun index(
        fraction: Float,
        itemCount: Int = 51,
        columns: Int = 1,
        totalRows: Int = 51,
        viewportHeightPx: Int = 2000,
        rows: (Int) -> Int = { 400 },
    ): Int = fastScrollTargetIndex(
        fraction = fraction,
        itemCount = itemCount,
        columns = columns,
        totalRows = totalRows,
        viewportHeightPx = viewportHeightPx,
        rowHeightPx = rows,
    )
}
