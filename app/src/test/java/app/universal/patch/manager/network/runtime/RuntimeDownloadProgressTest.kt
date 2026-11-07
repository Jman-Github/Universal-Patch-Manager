package app.universal.patch.manager.network.runtime

import org.junit.Assert.*
import org.junit.Test

class RuntimeDownloadProgressTest {
    private fun progress(bytes: Long, total: Long?) = RuntimeDownloadProgress(
        "morphe", "Morphe", RuntimeDownloadStage.DOWNLOADING, bytes, total
    )

    @Test fun resumedBytesProduceTheActualDownloadFraction() {
        assertEquals(0.75f, progress(75, 100).fraction!!, 0.0001f)
        assertEquals(1f, progress(Long.MAX_VALUE, Long.MAX_VALUE).fraction!!, 0.0001f)
        assertEquals(0f, progress(-1, 100).fraction!!, 0f)
        assertEquals(1f, progress(150, 100).fraction!!, 0f)
    }

    @Test fun unknownOrEmptyTotalsRemainIndeterminate() {
        assertNull(progress(10, null).fraction)
        assertNull(progress(0, 0).fraction)
        assertNull(progress(1, -1).fraction)
    }

    @Test fun verificationAndActivationNeverPretendTheRuntimeIsReady() {
        for (stage in listOf(RuntimeDownloadStage.PREPARING, RuntimeDownloadStage.VERIFYING, RuntimeDownloadStage.FINISHING)) {
            assertNull(progress(100, 100).copy(stage = stage).fraction)
        }
    }
}
