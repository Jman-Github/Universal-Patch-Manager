package app.universal.patch.manager.network.runtime

import app.universal.patch.manager.patcher.patch.PatchBundleType
import org.junit.Assert.*
import org.junit.Test

class RuntimePackagingPolicyTest {
    @Test fun everySupportedEngineRequiresItsPlugin() {
        assertEquals(listOf(PatcherRuntimeKind.RESEAM), PatchBundleType.RESEAM.requiredRuntimeKinds(false))
        assertEquals(listOf(PatcherRuntimeKind.MORPHE), PatchBundleType.MORPHE.requiredRuntimeKinds(false))
        assertEquals(listOf(PatcherRuntimeKind.REVANCED_V21), PatchBundleType.REVANCED.requiredRuntimeKinds(false))
        assertEquals(listOf(PatcherRuntimeKind.REVANCED_V22), PatchBundleType.REVANCED.requiredRuntimeKinds(true))
        assertEquals(PatcherRuntimeKind.RESEAM, PatcherRuntimeKind.fromId("reseam"))
    }
}
