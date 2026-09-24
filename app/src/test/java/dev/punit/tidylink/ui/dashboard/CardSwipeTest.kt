package dev.punit.tidylink.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardSwipeTest {
    @Test fun bothSidesEnabledGiveFiveOrderedRestPositions() {
        val anchors = cardSwipeAnchors(cardWidthPx = 1000f, revealPx = 270f, canRefresh = true, canDelete = true)
        assertEquals(5, anchors.size)
        assertEquals(0f, anchors.positionOf(CardSwipe.Closed), 0f)
        assertEquals(270f, anchors.positionOf(CardSwipe.RefreshRevealed), 0f)
        assertEquals(1000f, anchors.positionOf(CardSwipe.RefreshCommitted), 0f)
        assertEquals(-270f, anchors.positionOf(CardSwipe.DeleteRevealed), 0f)
        assertEquals(-1000f, anchors.positionOf(CardSwipe.DeleteCommitted), 0f)
    }

    @Test fun disabledSideCannotBeReachedAtAll() {
        val deleteOnly = cardSwipeAnchors(1000f, 270f, canRefresh = false, canDelete = true)
        assertFalse(deleteOnly.hasPositionFor(CardSwipe.RefreshRevealed))
        assertFalse(deleteOnly.hasPositionFor(CardSwipe.RefreshCommitted))
        assertTrue(deleteOnly.hasPositionFor(CardSwipe.DeleteCommitted))
        assertEquals(CardSwipe.Closed, deleteOnly.closestAnchor(500f))
    }

    @Test fun unmeasuredOrTooNarrowCardStaysClosed() {
        listOf(0f, 200f, 270f).forEach { width ->
            val anchors = cardSwipeAnchors(width, 270f, canRefresh = true, canDelete = true)
            assertEquals(1, anchors.size)
            assertTrue(anchors.hasPositionFor(CardSwipe.Closed))
        }
    }

    @Test fun revealSitsBetweenClosedAndCommit() {
        val anchors = cardSwipeAnchors(1000f, 270f, canRefresh = true, canDelete = true)
        // Just past the button width snaps back to the button, not the commit.
        assertEquals(CardSwipe.RefreshRevealed, anchors.closestAnchor(400f))
        assertEquals(CardSwipe.DeleteRevealed, anchors.closestAnchor(-400f))
        assertEquals(CardSwipe.RefreshCommitted, anchors.closestAnchor(900f))
    }

    @Test fun onlyCommittedStatesRunAnAction() {
        assertEquals(
            setOf(CardSwipe.RefreshCommitted, CardSwipe.DeleteCommitted),
            CardSwipe.entries.filter { it.isCommitted }.toSet(),
        )
        assertEquals(
            setOf(CardSwipe.RefreshRevealed, CardSwipe.DeleteRevealed),
            CardSwipe.entries.filter { it.isRevealed }.toSet(),
        )
    }
}
