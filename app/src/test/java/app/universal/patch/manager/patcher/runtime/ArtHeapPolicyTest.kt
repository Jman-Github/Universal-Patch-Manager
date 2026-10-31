package app.universal.patch.manager.patcher.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArtHeapPolicyTest {
    @Test
    fun fastReservationFailuresTryLargerHeapsBeforeOneGigabyte() {
        val firstRetry = nextArtHeapMb(2048, 400)
        assertEquals(1920, firstRetry)
        assertEquals(1792, nextArtHeapMb(firstRetry!!, 400))
    }

    @Test
    fun slowStartupStillBacksOffQuickly() {
        assertEquals(1024, nextArtHeapMb(2048, 30_000))
        assertEquals(512, nextArtHeapMb(1024, 30_000))
    }

    @Test
    fun retriesStopAtMinimumHeap() {
        assertEquals(512, nextArtHeapMb(640, 400))
        assertNull(nextArtHeapMb(512, 400))
    }
}
