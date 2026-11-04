package app.universal.patch.manager.network.runtime

import kotlinx.serialization.Serializable

sealed interface PatcherRuntimePluginState {
    data object Untrusted : PatcherRuntimePluginState

    data class Loaded(
        val plugin: LoadedPatcherRuntimePlugin
    ) : PatcherRuntimePluginState

    data class Failed(val throwable: Throwable) : PatcherRuntimePluginState
}

@Serializable
data class PatcherRuntimePluginSourceEntry(
    val id: String,
    val repoUrl: String,
    val assetSelector: String,
    val autoUpdate: Boolean = true,
    // Keep the serialized fallback for older backups; new imports explicitly opt into Latest.
    val latest: Boolean = false,
    val prerelease: Boolean = false,
    val versionKey: String? = null,
    val trustedSignatureHex: String? = null,
    val catalogId: String? = null,
    val expectedPackageName: String? = null,
    val expectedKind: String? = null,
    val expectedSha256: String? = null,
    val expectedVersionCode: Long? = null,
    val expectedSize: Long? = null,
    val archiveName: String? = null,
    val legacyProtocol: Boolean = false
) {
    /** null asks GitHub for both stable and prerelease versions. */
    val releasePrereleaseFilter: Boolean? get() = if (latest) null else prerelease
}

data class PatcherRuntimePluginSourceState(
    val entry: PatcherRuntimePluginSourceEntry,
    val name: String,
    val version: String?,
    val repoUrl: String,
    val state: State
) {
    sealed interface State {
        data class Loaded(
            val plugin: LoadedPatcherRuntimePlugin
        ) : State

        data class Untrusted(
            val packageName: String,
            val signature: String
        ) : State

        data object Missing : State

        data class Failed(
            val throwable: Throwable
        ) : State
    }
}

/** Only verified modular copies count as ready; installed APKs still need removal. */
fun catalogRuntimePlugins(
    sources: Collection<PatcherRuntimePluginSourceState>,
    installedPackages: Set<String>
): Map<PatcherRuntimeKind, LoadedPatcherRuntimePlugin> = sources.mapNotNull { source ->
    val entry = source.entry
    val plugin = (source.state as? PatcherRuntimePluginSourceState.State.Loaded)?.plugin
    plugin?.takeIf {
        entry.catalogId != null && !entry.legacyProtocol &&
            entry.expectedPackageName == it.packageName && entry.expectedKind == it.kind.id &&
            it.packageName !in installedPackages
    }
}.associateBy { it.kind }
