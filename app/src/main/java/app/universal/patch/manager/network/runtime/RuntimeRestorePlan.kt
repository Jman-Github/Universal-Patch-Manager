package app.universal.patch.manager.network.runtime

import java.util.UUID

/** Keep device-local storage identities; a backup only supplies settings and release metadata. */
fun rebindImportedRuntimeSources(
    imported: List<PatcherRuntimePluginSourceEntry>,
    local: List<PatcherRuntimePluginSourceEntry>
): List<PatcherRuntimePluginSourceEntry> {
    require(imported.map { it.id }.distinct().size == imported.size) { "Duplicate imported runtime source" }
    fun String.repositoryKey() = trimEnd('/').removeSuffix(".git").lowercase()
    val rebound = imported.map { desired ->
        val existing = local.firstOrNull { candidate ->
            if (desired.catalogId != null) candidate.catalogId == desired.catalogId
            else candidate.repoUrl.repositoryKey() == desired.repoUrl.repositoryKey() &&
                candidate.assetSelector == desired.assetSelector
        }
        val entry = when {
            existing == null || desired.catalogId != null -> desired.copy(id = existing?.id ?: desired.id)
            !desired.autoUpdate && !desired.latest && desired.versionKey != null &&
                desired.versionKey != existing.versionKey -> {
                // Legacy sources overwrite a fixed filename. Its current APK belongs to the
                // local versionKey, so a different frozen release needs its own empty folder.
                desired.copy(id = UUID.randomUUID().toString())
            }
            desired.autoUpdate || desired.latest || desired.versionKey == null -> {
                // Release checks must compare against the APK actually stored here, rather
                // than the backup's version label. Keep imported channel and trust settings.
                desired.copy(
                    id = existing.id, versionKey = existing.versionKey, archiveName = existing.archiveName,
                    expectedSha256 = existing.expectedSha256, expectedSize = existing.expectedSize,
                    expectedVersionCode = existing.expectedVersionCode,
                    expectedPackageName = existing.expectedPackageName, expectedKind = existing.expectedKind
                )
            }
            else -> desired.copy(id = existing.id)
        }
        entry.withInternalSupportPolicy()
    }
    require(rebound.map { it.id }.distinct().size == rebound.size) { "Duplicate imported runtime source" }
    return rebound
}

/** With automatic updates disabled, a saved release stays pinned unless Latest is selected. */
fun importedRuntimeReleaseTag(entry: PatcherRuntimePluginSourceEntry): String? =
    entry.versionKey?.takeUnless { entry.autoUpdate || entry.latest || it.startsWith("local:") }
        ?.substringBefore(':')?.takeIf { it.isNotBlank() }
