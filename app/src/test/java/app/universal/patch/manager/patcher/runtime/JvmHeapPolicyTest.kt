package app.universal.patch.manager.patcher.runtime

import kotlin.test.Test
import kotlin.test.assertEquals

class JvmHeapPolicyTest {
    @Test fun usesOneThirdOfRamWithin64BitBounds() {
        assertEquals(2048, JvmHeapPolicy.limitMb(2048, true))
        assertEquals(2304, JvmHeapPolicy.limitMb(6912, true))
        assertEquals(3072, JvmHeapPolicy.limitMb(16384, true))
        assertEquals(2048, JvmHeapPolicy.minimumMb(true))
    }

    @Test fun usesHalfOfRamWithin32BitBounds() {
        assertEquals(1024, JvmHeapPolicy.limitMb(1024, false))
        assertEquals(1280, JvmHeapPolicy.limitMb(2560, false))
        assertEquals(1536, JvmHeapPolicy.limitMb(16384, false))
        assertEquals(1024, JvmHeapPolicy.minimumMb(false))
    }
}
