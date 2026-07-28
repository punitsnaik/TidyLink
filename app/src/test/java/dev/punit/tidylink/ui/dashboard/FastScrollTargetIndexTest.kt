package dev.punit.tidylink.ui.dashboard

import org.junit.Assert.assertEquals
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
}
