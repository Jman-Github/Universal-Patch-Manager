package app.universal.patch.manager.network.runtime

import app.universal.patch.manager.patcher.patch.PatchBundleType

enum class PatcherRuntimeKind(
    val id: String,
    val displayName: String
) {
    REVANCED_V21("revanced-v21", "ReVanced v21"),
    REVANCED_V22("revanced-v22", "ReVanced v22"),
    MORPHE("morphe", "Morphe"),
    RESEAM("reseam", "ReSeam"),
    SHARED_SUPPORT("shared-support", "UPM runtime support");

    companion object {
        fun fromId(value: String?): PatcherRuntimeKind? =
            entries.firstOrNull { it.id.equals(value?.trim(), ignoreCase = true) }
    }
}

fun PatchBundleType.requiredRuntimeKinds(useRevancedPatcher22: Boolean): List<PatcherRuntimeKind> =
    when (this) {
        PatchBundleType.REVANCED -> if (!useRevancedPatcher22) {
            listOf(PatcherRuntimeKind.REVANCED_V21)
        } else {
            listOf(PatcherRuntimeKind.REVANCED_V22)
        }
        PatchBundleType.RESEAM -> listOf(PatcherRuntimeKind.RESEAM)
        PatchBundleType.MORPHE -> listOf(PatcherRuntimeKind.MORPHE)
        PatchBundleType.AMPLE -> emptyList()
    }
