package app.universal.patch.manager.network.runtime

import kotlinx.serialization.Serializable
import java.net.URI

const val RUNTIME_HOST_PROTOCOL = 1
const val RUNTIME_SETUP_VERSION = 2

@Serializable
data class RuntimeCatalog(val schemaVersion: Int, val plugins: List<RuntimeCatalogItem>) {
    fun validated(): RuntimeCatalog {
        require(schemaVersion == 1) { "Unsupported runtime catalog format" }
        require(plugins.map { it.id }.distinct().size == plugins.size) { "Duplicate runtime ID" }
        plugins.forEach { item ->
            require(item.id.matches(Regex("[a-z0-9-]+"))) { "Invalid runtime ID" }
            require(PatcherRuntimeKind.fromId(item.kind) != null) { "Unsupported runtime kind" }
            require(item.packageName.startsWith("app.universal.patch.manager.")) { "Invalid runtime package" }
            require(item.assetName.endsWith(".apk") && '/' !in item.assetName) { "Invalid runtime asset" }
            val uri = URI(item.repoUrl)
            require(uri.scheme == "https" && uri.host == "github.com" &&
                uri.rawQuery == null && uri.rawFragment == null &&
                uri.path.trim('/').split('/').size == 2) { "Invalid runtime repository" }
        }
        installationOrder(plugins.map { it.id }.toSet())
        val engines = plugins.filter { it.isEngine }
        require(engines.isNotEmpty()) { "Runtime catalog contains no engines" }
        engines.forEach { engine ->
            require(installationOrder(setOf(engine.id)).any { it.runtimeKind == PatcherRuntimeKind.SHARED_SUPPORT }) {
                "Runtime ${engine.id} is missing its shared support dependency"
            }
        }
        return this
    }

    /** Catalogs can retire engines while a saved selection still contains their IDs. */
    fun engineSelection(selected: Set<String>): Set<String> =
        plugins.filter { it.isEngine && it.id in selected }.map { it.id }.toSet()

    /** Dependencies precede consumers; reject missing dependencies and cycles. */
    fun installationOrder(selected: Set<String>): List<RuntimeCatalogItem> {
        val byId = plugins.associateBy { it.id }
        val visiting = mutableSetOf<String>()
        val done = linkedSetOf<String>()
        fun visit(id: String) {
            if (id in done) return
            require(visiting.add(id)) { "Circular runtime dependency: $id" }
            val item = requireNotNull(byId[id]) { "Unknown runtime: $id" }
            item.dependencies.forEach(::visit)
            visiting.remove(id)
            done.add(id)
        }
        selected.sorted().forEach(::visit)
        return done.map { byId.getValue(it) }
    }
}

@Serializable
data class RuntimeCatalogItem(
    val id: String,
    val kind: String,
    val name: String,
    val description: String,
    val packageName: String,
    val repoUrl: String,
    val assetName: String,
    val dependencies: List<String> = emptyList()
) {
    val runtimeKind: PatcherRuntimeKind get() = requireNotNull(PatcherRuntimeKind.fromId(kind))
    val isEngine: Boolean get() = runtimeKind != PatcherRuntimeKind.SHARED_SUPPORT
}

@Serializable
data class RuntimeReleaseManifest(
    val schemaVersion: Int,
    val id: String,
    val kind: String,
    val packageName: String,
    val assetName: String,
    val version: String,
    val versionCode: Long,
    val upstreamVersion: String,
    val sha256: String,
    val size: Long,
    val minHostProtocol: Int,
    val maxHostProtocol: Int,
    val minSdk: Int = 26,
    val abis: List<String> = emptyList(),
    val executionModes: List<String> = emptyList()
) {
    fun isCompatible(item: RuntimeCatalogItem, sdk: Int, processAbis: List<String>): Boolean =
        schemaVersion == 1 && id == item.id && kind == item.kind &&
            packageName == item.packageName && assetName == item.assetName &&
            versionCode > 0 && size > 0 && sha256.matches(Regex("[a-fA-F0-9]{64}")) &&
            RUNTIME_HOST_PROTOCOL in minHostProtocol..maxHostProtocol &&
            sdk >= minSdk && (abis.isEmpty() || abis.any { it in processAbis })
}

/** Null means the initial runtime scan has not finished; neither destination is ready. */
fun runtimeSetupRequired(
    version: Int,
    loaded: Boolean,
    available: Set<PatcherRuntimeKind>,
    restoredFromBackup: Boolean = false
): Boolean? = if (!loaded) null else !restoredFromBackup &&
    (version < RUNTIME_SETUP_VERSION ||
        available.none { it != PatcherRuntimeKind.SHARED_SUPPORT } || PatcherRuntimeKind.SHARED_SUPPORT !in available)

fun canCompleteRuntimeSetup(catalog: RuntimeCatalog, selected: Set<String>, available: Set<PatcherRuntimeKind>): Boolean =
    PatcherRuntimeKind.SHARED_SUPPORT in available && selected.isNotEmpty() &&
        selected.all { id -> catalog.plugins.any { it.id == id && it.isEngine } } &&
        catalog.installationOrder(selected).all { it.runtimeKind in available }
