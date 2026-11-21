package app.universal.patch.manager.patcher.patch

import app.universal.patch.manager.network.runtime.PatcherRuntimeKind
import app.universal.patch.manager.patcher.runtime.PatcherRuntimePluginRegistry

fun patcherEngineDisplayName(
    bundleType: PatchBundleType?,
    usesRevancedPatcher22: Boolean = false
): String? {
    val kind = when (bundleType) {
        PatchBundleType.MORPHE -> PatcherRuntimeKind.MORPHE
        PatchBundleType.RESEAM -> PatcherRuntimeKind.RESEAM
        PatchBundleType.REVANCED -> if (usesRevancedPatcher22) PatcherRuntimeKind.REVANCED_V22 else PatcherRuntimeKind.REVANCED_V21
        PatchBundleType.AMPLE -> return "Ample"
        null -> return null
    }
    val runtime = PatcherRuntimePluginRegistry.runtimeFor(kind)
    return runtime?.upstreamVersion?.let { "${kind.displayName} $it" } ?: kind.displayName
}
