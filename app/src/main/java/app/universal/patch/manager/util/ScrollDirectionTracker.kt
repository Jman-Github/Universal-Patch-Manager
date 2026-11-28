package app.universal.patch.manager.util

/** Accumulates small movements without changing direction while the list is stationary. */
internal class ScrollDirectionTracker(
    private val sensitivity: Int = 10,
    initialIndex: Int = 0,
    initialOffset: Int = 0
) {
    private var anchorIndex = initialIndex
    private var anchorOffset = initialOffset
    var scrollingUp = true
        private set

    fun update(index: Int, offset: Int): Boolean {
        if (index == 0 && offset == 0) {
            scrollingUp = true
        } else if (index != anchorIndex) {
            scrollingUp = index < anchorIndex
        } else {
            val delta = offset.toLong() - anchorOffset.toLong()
            if (delta > sensitivity) scrollingUp = false
            else if (delta < -sensitivity.toLong()) scrollingUp = true
            // Keep the furthest point in the current direction as the reversal anchor.
            if ((scrollingUp && delta >= 0) || (!scrollingUp && delta <= 0)) {
                return scrollingUp
            }
        }
        anchorIndex = index
        anchorOffset = offset
        return scrollingUp
    }
}
