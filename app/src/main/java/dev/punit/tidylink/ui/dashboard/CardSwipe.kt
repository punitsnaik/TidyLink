package dev.punit.tidylink.ui.dashboard

import androidx.compose.foundation.gestures.DraggableAnchors

/**
 * Rest positions of a swipeable link card. A card can only ever settle on
 * one of these: closed, a revealed action button, or committed (the action
 * runs and the card springs back to [Closed]).
 */
internal enum class CardSwipe {
    Closed,
    RefreshRevealed,
    DeleteRevealed,
    RefreshCommitted,
    DeleteCommitted;

    val isRevealed: Boolean get() = this == RefreshRevealed || this == DeleteRevealed
    val isCommitted: Boolean get() = this == RefreshCommitted || this == DeleteCommitted
}

/**
 * Card offsets (px) for each rest position. Swipe right (positive) is
 * refresh, swipe left (negative) is delete; a disabled side gets no anchors,
 * so the card can't move that way. Commit sits at the full card width, so
 * with a half-way positional threshold the action fires once the drag passes
 * roughly the middle of the card.
 */
internal fun cardSwipeAnchors(
    cardWidthPx: Float,
    revealPx: Float,
    canRefresh: Boolean,
    canDelete: Boolean,
): DraggableAnchors<CardSwipe> = DraggableAnchors {
    CardSwipe.Closed at 0f
    // Before layout (width 0) or on a card too narrow to reveal, stay closed.
    if (cardWidthPx > revealPx) {
        if (canRefresh) {
            CardSwipe.RefreshRevealed at revealPx
            CardSwipe.RefreshCommitted at cardWidthPx
        }
        if (canDelete) {
            CardSwipe.DeleteRevealed at -revealPx
            CardSwipe.DeleteCommitted at -cardWidthPx
        }
    }
}
