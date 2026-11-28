"""Run production backup reconciliation and cache reuse against disk/network fixtures."""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[4]
CACHE = Path(os.environ.get("GRADLE_USER_HOME", ROOT / ".gradle-user-home")) / "caches/modules-2/files-2.1"
JAVA = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-17")) / "bin/java.exe"
def artifact(group, name, version=None):
    base = CACHE/group/name
    return next((base/version).glob("*/*.jar")) if version else max(base.glob("*/*/*.jar"), key=lambda p: p.stat().st_mtime)
compiler = [artifact("org.jetbrains.kotlin", n, "2.4.10") for n in
    ("kotlin-compiler-embeddable", "kotlin-stdlib", "kotlin-reflect", "kotlin-script-runtime")]
coroutines = artifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.10.2")
compiler += [coroutines, artifact("org.jetbrains", "annotations", "13.0")]
dependencies = [artifact("org.jetbrains.kotlin", "kotlin-stdlib", "2.4.10"), coroutines,
                artifact("org.jetbrains.kotlinx", "kotlinx-serialization-core-jvm")]
source = (ROOT/"app/src/main/java/app/universal/patch/manager/domain/repository/PatcherRuntimePluginRepository.kt").read_text(encoding="utf-8")
restore = source[source.index("    suspend fun restoreImportedRuntimeSources()"):source.index("\n    suspend fun importCatalogRuntimes(")]
reuse = source[source.index("    private fun reuseCachedCatalogArchive("):source.index("\n    private fun validateManagedArchive(")]
harness = """
import java.io.File
import java.util.UUID
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.universal.patch.manager.network.runtime.*

class Setting<T>(var value: T) { suspend fun get() = value; suspend fun update(v: T) { value = v } }
class Prefs { val runtimeRestorePending = Setting(true); val allowMeteredUpdates = Setting(false) }
class Network { var connected = true; var unmetered = true
    fun isConnected() = connected; fun isUnmetered() = unmetered }
object Log { fun e(tag: String, message: String, error: Exception) {}; fun w(tag: String, message: String, error: Exception) {} }
class RuntimeCatalogRepository { companion object { const val REPOSITORY_URL = "https://github.com/example/runtimes" } }
data class Asset(val name: String)
data class Release(val assets: List<Asset> = listOf(Asset("plugin.apk")))
fun Release.successOrThrow(context: String) = this
class Api {
    val requests = mutableListOf<Pair<String, String>>()
    fun getRepositoryReleaseByTag(repo: String, tag: String): Release {
        requests += repo to tag
        return Release()
    }
}
data class Manifest(val sha256: String)
data class Resolved(val item: RuntimeCatalogItem, val manifest: Manifest)
class Catalog {
    val items = listOf(
        RuntimeCatalogItem("morphe", "morphe", "Morphe", "", "app.universal.patch.manager.morphe.runtime",
            RuntimeCatalogRepository.REPOSITORY_URL, "morphe-plugin.apk", listOf("shared-support")),
        RuntimeCatalogItem("shared-support", "shared-support", "support", "", INTERNAL_RUNTIME_SUPPORT_PACKAGE,
            RuntimeCatalogRepository.REPOSITORY_URL, "shared-support-plugin.apk")
    )
    val requests = mutableListOf<Pair<String, String?>>()
    var failEngine = false
    var cancelEngine = false
    fun cached() = RuntimeCatalog(1, items)
    fun refresh() = cached()
    fun resolve(item: RuntimeCatalogItem, prerelease: Boolean?, tag: String?): Resolved {
        requests += item.id to tag
        if (item.id == "morphe" && cancelEngine) throw CancellationException()
        if (item.id == "morphe" && failEngine) error("Offline release endpoint")
        val hash = if (item.id == "shared-support") "f".repeat(64) else if (tag != null) "a".repeat(64) else "c".repeat(64)
        return Resolved(item, Manifest(hash))
    }
}
class Saved {
    val prefs = Prefs()
    var entries = emptyList<PatcherRuntimePluginSourceEntry>()
}
class Repository(val managedSourceRoot: File, val saved: Saved) {
    val prefs = saved.prefs
    val networkInfo = Network()
    val catalog = Catalog()
    val api = Api()
    val sourceMutex = Mutex()
    val tag = "restore-test"
    var downloads = 0
    var failLoad = false
    fun readSourceEntries() = saved.entries
    fun writeSourceEntries(entries: List<PatcherRuntimePluginSourceEntry>) { saved.entries = entries }
    fun sourceFile(entry: PatcherRuntimePluginSourceEntry) =
        managedSourceRoot.resolve(entry.id).resolve(entry.archiveName ?: "patcher-runtime.apk")
    fun readArchivePackageInfo(file: File) = file
    fun validateManagedArchive(entry: PatcherRuntimePluginSourceEntry, pkg: File, file: File) {
        check(file.readText() == (if (entry.catalogId == null) "download" else entry.expectedSha256)) { "Bad archive" }
    }
    fun ensureManagedSourceIsReadOnly(file: File) {}
    fun canonicalAssetSelector(selector: String) = selector
    fun Asset.matchesAssetSelector(selector: String) = true
    val _sourceStates = Setting(emptyMap<String, PatcherRuntimePluginSourceState>())
    suspend fun reload() { _sourceStates.value = saved.entries.associate { it.id to loadManagedSource(it) } }
    fun loadManagedSource(entry: PatcherRuntimePluginSourceEntry): PatcherRuntimePluginSourceState {
        val file = sourceFile(entry)
        val state = if (failLoad) PatcherRuntimePluginSourceState.State.Failed(Exception("Loader failed"))
            else if (file.isFile && runCatching { validateManagedArchive(entry, file, file) }.isSuccess)
            PatcherRuntimePluginSourceState.State.Loaded(LoadedPatcherRuntimePlugin(
                entry.expectedPackageName ?: "runtime", "runtime", "1.0.0",
                PatcherRuntimeKind.fromId(entry.expectedKind) ?: PatcherRuntimeKind.MORPHE, entry.id, file))
            else PatcherRuntimePluginSourceState.State.Missing
        return PatcherRuntimePluginSourceState(entry, "runtime", null, entry.repoUrl, state)
    }
    fun downloadAssetToEntry(entry: PatcherRuntimePluginSourceEntry, asset: Asset) {
        downloads++
        sourceFile(entry).apply { parentFile.mkdirs(); writeText(entry.expectedSha256 ?: "download") }
    }
    fun installCatalogEntry(old: PatcherRuntimePluginSourceEntry, resolved: Resolved): PatcherRuntimePluginSourceEntry {
        val entry = old.copy(expectedSha256 = resolved.manifest.sha256, archiveName = resolved.manifest.sha256 + ".apk",
            expectedKind = resolved.item.kind, expectedPackageName = resolved.item.packageName,
            legacyProtocol = false, versionKey = resolved.item.id + "-v1.0.0:" + resolved.manifest.sha256)
        if (!reuseCachedCatalogArchive(entry)) downloadAssetToEntry(entry, Asset("plugin.apk"))
        return entry
    }
    fun updateEntry(entry: PatcherRuntimePluginSourceEntry, force: Boolean): PatcherRuntimePluginSourceEntry = error("Unexpected legacy download")
""" + restore + reuse + """
}
fun main(args: Array<String>) = runBlocking {
    val base = File(args[0])
    fun fixture(name: String, enginePresent: Boolean = true): Repository {
        val saved = Saved()
        val repo = Repository(base.resolve(name).apply { mkdirs() }, saved)
        val engine = PatcherRuntimePluginSourceEntry("engine", RuntimeCatalogRepository.REPOSITORY_URL,
            "morphe-plugin", autoUpdate = false, versionKey = "morphe-v1.0.0:hash", trustedSignatureHex = "trusted",
            catalogId = "morphe", expectedKind = "morphe", expectedSha256 = "a".repeat(64),
            archiveName = "a".repeat(64) + ".apk")
        val support = engine.copy(id = "support", assetSelector = "shared-support-plugin", catalogId = "shared-support",
            expectedKind = "shared-support", autoUpdate = true, expectedSha256 = "f".repeat(64),
            archiveName = "f".repeat(64) + ".apk")
        saved.entries = listOf(engine, support)
        if (enginePresent) repo.sourceFile(engine).apply { parentFile.mkdirs(); writeText(engine.expectedSha256!!) }
        repo.sourceFile(support).apply { parentFile.mkdirs(); writeText(support.expectedSha256!!) }
        return repo
    }
    val legacyPinned = fixture("legacy-pinned")
    val local = legacyPinned.saved.entries.first().copy(
        catalogId = null, repoUrl = "https://github.com/thirdparty/runtimes",
        versionKey = "v2:plugin.apk", expectedSha256 = null, archiveName = null)
    legacyPinned.sourceFile(local).apply { parentFile.mkdirs(); writeText("download") }
    val backup = local.copy(versionKey = "v1:plugin.apk")
    val rebound = rebindImportedRuntimeSources(listOf(backup), listOf(local)).single()
    legacyPinned.saved.entries = listOf(rebound, legacyPinned.saved.entries.last())
    check(!legacyPinned.sourceFile(rebound).exists())
    check(legacyPinned.restoreImportedRuntimeSources())
    check(legacyPinned.api.requests.contains(local.repoUrl to "v1") && legacyPinned.downloads == 1)
    check(legacyPinned.sourceFile(local).exists())

    val pinned = fixture("pinned")
    check(pinned.restoreImportedRuntimeSources())
    check(pinned.downloads == 0 && pinned.catalog.requests.none { it.first == "morphe" })
    check(!pinned.prefs.runtimeRestorePending.value)

    val latest = fixture("latest")
    latest.saved.entries = latest.saved.entries.map { if (it.catalogId == "morphe") it.copy(latest = true) else it }
    latest.managedSourceRoot.resolve("another-source/" + "c".repeat(64) + ".apk")
        .apply { parentFile.mkdirs(); writeText("c".repeat(64)) }
    check(latest.restoreImportedRuntimeSources())
    check(latest.downloads == 0 && latest.catalog.requests.contains("morphe" to null))
    check(latest.sourceFile(latest.saved.entries.first()).readText() == "c".repeat(64))

    val missing = fixture("missing", false)
    check(missing.restoreImportedRuntimeSources())
    check(missing.downloads == 1 && missing.catalog.requests.contains("morphe" to "morphe-v1.0.0"))
    check(missing.saved.entries.first().expectedSha256 == "a".repeat(64))

    val retry = fixture("retry", false)
    retry.catalog.failEngine = true
    check(!retry.restoreImportedRuntimeSources() && retry.prefs.runtimeRestorePending.value)
    val resumed = Repository(retry.managedSourceRoot, retry.saved)
    check(resumed.restoreImportedRuntimeSources() && resumed.downloads == 1)
    check(!resumed.prefs.runtimeRestorePending.value)

    val offline = fixture("offline", false)
    offline.networkInfo.connected = false
    check(!offline.restoreImportedRuntimeSources() && offline.catalog.requests.isEmpty())
    offline.networkInfo.connected = true
    offline.networkInfo.unmetered = false
    check(!offline.restoreImportedRuntimeSources() && offline.downloads == 0)
    offline.prefs.allowMeteredUpdates.value = true
    check(offline.restoreImportedRuntimeSources() && offline.downloads == 1)

    val untrusted = fixture("untrusted", false)
    untrusted.saved.entries = untrusted.saved.entries.map { if (it.catalogId == "morphe") it.copy(trustedSignatureHex = null) else it }
    check(untrusted.restoreImportedRuntimeSources())
    check(untrusted.downloads == 0 && untrusted.saved.entries.first().trustedSignatureHex == null)

    val cancelled = fixture("cancelled", false)
    cancelled.catalog.cancelEngine = true
    check(runCatching { cancelled.restoreImportedRuntimeSources() }.exceptionOrNull() is CancellationException)
    check(cancelled.prefs.runtimeRestorePending.value)
    val invalidCache = fixture("invalid-cache")
    invalidCache.saved.entries = invalidCache.saved.entries.map { if (it.catalogId == "morphe") it.copy(latest = true) else it }
    invalidCache.managedSourceRoot.resolve("another-source/" + "c".repeat(64) + ".apk")
        .apply { parentFile.mkdirs(); writeText("invalid") }
    val previousApk = invalidCache.sourceFile(invalidCache.saved.entries.first())
    check(invalidCache.restoreImportedRuntimeSources() && invalidCache.downloads == 1)
    check(previousApk.readText() == "a".repeat(64))

    val collision = fixture("dependency-collision")
    val unrelated = collision.saved.entries.last().copy(id = "catalog-shared-support",
        catalogId = null, expectedKind = "morphe", assetSelector = "other", trustedSignatureHex = null)
    collision.saved.entries = listOf(collision.saved.entries.first(), unrelated)
    check(collision.restoreImportedRuntimeSources())
    check(collision.saved.entries.any { it == unrelated })
    check(collision.saved.entries.map { it.id }.distinct().size == 3)

    val retired = fixture("retired-catalog-entry", false)
    val removed = retired.saved.entries.first().copy(
        id = "removed", catalogId = "retired-engine", assetSelector = "retired-plugin", autoUpdate = true)
    retired.saved.entries += removed
    check(!retired.restoreImportedRuntimeSources())
    check(retired.sourceFile(retired.saved.entries.first()).isFile) {
        "A retired catalog source blocked restoration of a supported runtime"
    }
    check(retired.downloads == 1 && retired.prefs.runtimeRestorePending.value)
    check(retired.saved.entries.any { it == removed })

    val loadFailure = fixture("load-failure", false)
    loadFailure.failLoad = true
    check(!loadFailure.restoreImportedRuntimeSources() && loadFailure.prefs.runtimeRestorePending.value)
    println("PASS: cached/pinned restore; restart/retry; metered/offline; trust/cancellation; invalid cache; dependency IDs; load failures")
}
"""
sources = [
    "RuntimeCatalog.kt", "RuntimeRestorePlan.kt", "InternalRuntimeSupport.kt",
    "PatcherRuntimePluginState.kt", "LoadedPatcherRuntimePlugin.kt", "PatcherRuntimeKind.kt"]
production = [ROOT/"app/src/main/java/app/universal/patch/manager/network/runtime"/name for name in sources]
production += [ROOT/"app/src/main/java/app/universal/patch/manager/patcher/patch/PatchBundleType.kt"]
(ROOT/"build").mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix="runtime-backup-", dir=ROOT/"build") as temporary:
    folder = Path(temporary)
    harness_file = folder/"RuntimeBackupRestoreHarness.kt"
    harness_file.write_text(harness, encoding="utf-8")
    subprocess.run([str(JAVA), "-cp", os.pathsep.join(map(str, compiler)),
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "17",
        "-classpath", os.pathsep.join(map(str, dependencies)), "-d", str(folder),
        str(harness_file), *map(str, production)], check=True)
    subprocess.run([str(JAVA), "-cp", os.pathsep.join([str(folder), *map(str, dependencies)]),
        "RuntimeBackupRestoreHarnessKt", str(folder/"fixtures")], check=True)
