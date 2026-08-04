package dev.punit.tidylink.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which link a swipe lands on. The currentIndex = -1 case is the point: the
 * grid is a paging list behind a live filter, so the open link can stop
 * being in it while the sheet is up. Navigation must stop there rather than
 * jump to whatever happens to sit at index 0.
 */
class DetailNeighborIndexTest {

    @Test
    fun moves_one_step_in_each_direction_from_the_middle() {
        assertEquals(6, detailNeighborIndex(currentIndex = 5, direction = 1, itemCount = 10))
        assertEquals(4, detailNeighborIndex(currentIndex = 5, direction = -1, itemCount = 10))
    }

    @Test
    fun there_is_nothing_before_the_first_item() {
        assertNull(detailNeighborIndex(currentIndex = 0, direction = -1, itemCount = 10))
        assertEquals(1, detailNeighborIndex(currentIndex = 0, direction = 1, itemCount = 10))
    }

    @Test
    fun there_is_nothing_after_the_last_item() {
        assertNull(detailNeighborIndex(currentIndex = 9, direction = 1, itemCount = 10))
        assertEquals(8, detailNeighborIndex(currentIndex = 9, direction = -1, itemCount = 10))
    }

    @Test
    fun a_single_item_list_goes_nowhere_in_either_direction() {
        assertNull(detailNeighborIndex(currentIndex = 0, direction = 1, itemCount = 1))
        assertNull(detailNeighborIndex(currentIndex = 0, direction = -1, itemCount = 1))
    }

    /** The filter changed underneath the open sheet. */
    @Test
    fun a_link_that_is_no_longer_in_the_list_navigates_nowhere() {
        assertNull(detailNeighborIndex(currentIndex = -1, direction = 1, itemCount = 10))
        assertNull(detailNeighborIndex(currentIndex = -1, direction = -1, itemCount = 10))
    }

    @Test
    fun an_empty_list_navigates_nowhere_and_does_not_throw() {
        assertNull(detailNeighborIndex(currentIndex = 0, direction = 1, itemCount = 0))
        assertNull(detailNeighborIndex(currentIndex = 0, direction = -1, itemCount = 0))
    }
}
