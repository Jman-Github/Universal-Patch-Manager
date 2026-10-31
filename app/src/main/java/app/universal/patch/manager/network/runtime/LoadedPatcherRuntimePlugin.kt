package app.universal.patch.manager.network.runtime

import java.io.File

data class LoadedPatcherRuntimePlugin(
    val packageName: String,
    val name: String,
    val version: String,
    val kind: PatcherRuntimeKind,
    val sourceId: String? = null,
    val apkFile: File,
    // Capture file identity now so replacing a managed APK at the same path/version
    // still emits a runtime change and refreshes bundles loaded by that runtime.
    val apkLastModified: Long = apkFile.lastModified(),
    val apkSize: Long = apkFile.length()
) {
    val id: String = sourceId ?: packageName
}

fun String.toRuntimeMainName(): String {
    val original = trim()
    val withoutPrefix = original.substringAfterLast(':', original).trim()
    val normalized = withoutPrefix
        .replace(Regex("(?i)revanced[ ._-]*(?:runtime[ ._-]*)?v?(21|22)\\b")) {
            "ReVanced v${it.groupValues[1]}"
        }
        .replace(Regex("(?i)\\s+runtime\\b"), "")
        .replace(Regex("(?i)\\s+plugin\\b"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    return normalized.ifBlank { original }
}
