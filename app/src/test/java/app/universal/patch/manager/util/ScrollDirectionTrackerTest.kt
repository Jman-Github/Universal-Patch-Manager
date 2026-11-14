package app.universal.patch.manager.util

import org.junit.Assert.*
import org.junit.Test

class ScrollDirectionTrackerTest {
    @Test fun slowScrollingAccumulatesWithoutFlickeringAtRest() {
        val tracker = ScrollDirectionTracker()
        for (offset in 1..10) assertTrue(tracker.update(0, offset))
        assertFalse(tracker.update(0, 11))
        for (offset in 12..80) {
            assertFalse(tracker.update(0, offset))
            assertFalse(tracker.update(0, offset))
        }
    }

    @Test fun smallReversalsDoNotExpandTheButton() {
        val tracker = ScrollDirectionTracker(initialOffset = 100)
        assertFalse(tracker.update(0, 120))
        for (offset in listOf(119, 118, 121, 120, 115, 121)) {
            assertFalse(tracker.update(0, offset))
        }
        assertTrue(tracker.update(0, 110))
        assertTrue(tracker.update(0, 110))
        assertTrue(tracker.update(0, 111))
        assertFalse(tracker.update(0, 122))
    }

    @Test fun itemTransitionsAndReturningToTopKeepTheirDirection() {
        val tracker = ScrollDirectionTracker(initialIndex = 2, initialOffset = 500)
        assertFalse(tracker.update(3, 0))
        assertFalse(tracker.update(3, 0))
        assertTrue(tracker.update(2, 900))
        assertTrue(tracker.update(0, 0))
    }

    @Test fun largeOffsetsDoNotOverflow() {
        val tracker = ScrollDirectionTracker()
        assertFalse(tracker.update(0, Int.MAX_VALUE))
        assertTrue(tracker.update(0, 1))
    }
}
