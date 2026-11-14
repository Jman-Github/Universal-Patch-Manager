package app.universal.patch.manager.patcher

import kotlin.test.Test
import kotlin.test.assertEquals

class PatcherSessionInfoTest {
    @Test
    fun standaloneArtReplacesPreviousJvmModeAndUsesActualHeap() {
        val info = parsePatcherSessionInfo(listOf(
            "Runtime mode: in-app JVM (OpenJDK 17); memory budget: 2048MB",
            "Runtime mode: standalone ART; requested heap=2048MB; override=true",
            "Runtime mode: standalone ART; requested heap=1024MB; override=true",
            "Memory limit: 1024MB"
        ))
        assertEquals("ART", info.runtimeName)
        assertEquals(true, info.runtimeProcess)
        assertEquals(1024, info.memoryLimitMb)
    }

    @Test
    fun olderAndroidStillReportsBoundArt() {
        val info = PatcherSessionInfo().updatedFromLog(
            "Runtime mode: bound ART service; memory budget: Android managed"
        )
        assertEquals("ART", info.runtimeName)
        assertEquals(true, info.runtimeProcess)
    }
}
