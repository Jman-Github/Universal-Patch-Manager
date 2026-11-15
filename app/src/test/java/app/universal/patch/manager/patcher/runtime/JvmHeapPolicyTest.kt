package app.universal.patch.manager.patcher.runtime

import kotlin.test.Test
import kotlin.test.assertEquals

class JvmHeapPolicyTest {
    @Test fun gives64BitRunsOneThirdOfRamWithinTheUpstreamBounds() {
        assertEquals(2048, JvmHeapPolicy.limitMb(2048, true))
        assertEquals(2048, JvmHeapPolicy.limitMb(6144, true))
        assertEquals(3072, JvmHeapPolicy.limitMb(16384, true))
        assertEquals(1024, JvmHeapPolicy.limitMb(2048, false))
        assertEquals(1024, JvmHeapPolicy.limitMb(16384, false))
    }

    @Test fun leavesNativeHeadroomAndCapsThe32BitHeap() {
        assertEquals(1024, JvmHeapPolicy.fit32Bit(3072, 1152))
        assertEquals(768, JvmHeapPolicy.fit32Bit(1024, 896))
        assertEquals(512, JvmHeapPolicy.fit32Bit(1024, 640))
        assertEquals(448, JvmHeapPolicy.fit32Bit(1024, 576))
        assertEquals(0, JvmHeapPolicy.fit32Bit(1024, 64))
        assertEquals(0, JvmHeapPolicy.fit32Bit(1024, 0))
    }
}
