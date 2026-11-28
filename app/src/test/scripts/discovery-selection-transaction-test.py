"""Compile the production Discovery selection transaction and verify failure/cancellation recovery."""
from pathlib import Path
import subprocess
import tempfile
ROOT = Path(__file__).resolve().parents[4]
namespace = {"__file__": str(Path(__file__).with_name("bundle-ui-issues-test.py"))}
exec(Path(namespace["__file__"]).read_text().split('(ROOT / "build").mkdir')[0], namespace)
compiler, deps, JAVA, cp = (namespace[key] for key in ("compiler", "deps", "JAVA", "cp"))
deps += [namespace["artifact"]("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.10.2")]
source = (ROOT / "app/src/main/java/app/universal/patch/manager/domain/repository/PatchBundleRepository.kt").read_text(encoding="utf-8")
method = source[source.index("    private suspend fun updateDiscoveryReleaseSelection("):source.index("    suspend fun updateTimestamps(")]
harness = """
import kotlinx.coroutines.*
typealias PatchBundleDownloadProgress = (Long, Long?) -> Unit
data class RepositoryBundleSettings(val latest: Boolean)
data class RemotePatchBundle(val uid: Int, val endpoint: String)
object SourceInfo { fun from(endpoint: String) = endpoint }
data class PatchBundleEntity(val uid: Int = 1, val name: String = "Bundle", val displayName: String? = null,
    val versionHash: String? = "v1", val source: String = "old", val autoUpdate: Boolean = true,
    val searchUpdate: Boolean = true, val lastNotifiedVersion: String? = null, val enabled: Boolean = true,
    val sortOrder: Int = 0, val createdAt: Long? = null, val updatedAt: Long? = null)
class Dao { var props: PatchBundleEntity? = PatchBundleEntity(); suspend fun getProps(uid: Int) = props }
object ExternalBundleMetadataStore {
    var metadata: String? = "old"
    fun read(directory: Int) = metadata
    fun write(directory: Int, value: String) { metadata = value }
    fun clear(directory: Int) { metadata = null }
}
class Preference { suspend fun get() = false }
class Preferences { val allowMeteredUpdates = Preference() }
data class Result(val updated: Boolean)
class Repository {
    val dao = Dao()
    val prefs = Preferences()
    var success = false
    var cancel = false
    val started = CompletableDeferred<Unit>()
    val finish = CompletableDeferred<Unit>()
    fun directoryOf(uid: Int) = uid
    fun PatchBundleEntity.load(): Any = RemotePatchBundle(uid, source)
    suspend fun notifyQueuedBundleSetting() {}
    suspend fun <T> withRemoteUpdateReservation(onQueued: suspend () -> Unit, block: suspend () -> T) = block()
    suspend fun performRemoteUpdate(force: Boolean, showToast: Boolean, allowUnsafeNetwork: Boolean,
        showProgress: Boolean, onPerBundleProgress: ((RemotePatchBundle, Long, Long?) -> Unit)?,
        onBundleUpdated: Any?, predicate: (RemotePatchBundle) -> Boolean, refreshMetadata: Boolean,
        selectionOverrides: Map<Int, RemotePatchBundle>, discoverySelections: Map<Int, RepositoryBundleSettings>): Result {
        check(force && showProgress && refreshMetadata && showToast)
        val candidate = selectionOverrides.getValue(1)
        check(predicate(candidate) && candidate.endpoint == "new" && discoverySelections.getValue(1).latest)
        check(dao.props!!.source == "old") { "Changed endpoint before downloading" }
        ExternalBundleMetadataStore.metadata = "candidate"
        started.complete(Unit)
        finish.await()
        if (cancel) throw CancellationException("interrupted")
        if (success) dao.props = dao.props!!.copy(source = candidate.endpoint)
        return Result(success)
    }
    suspend fun change() = updateDiscoveryReleaseSelection(1, "new", RepositoryBundleSettings(true), null)
    // PRODUCTION
}
fun main() = runBlocking {
    for (success in listOf(false, true)) {
        ExternalBundleMetadataStore.metadata = "old"
        val repo = Repository().apply { this.success = success }
        val change = async { repo.change() }
        repo.started.await()
        check(repo.dao.props!!.source == "old")
        repo.finish.complete(Unit)
        check(change.await() == success)
        check(repo.dao.props!!.source == if (success) "new" else "old")
        check(ExternalBundleMetadataStore.metadata == if (success) "candidate" else "old")
    }
    for (original in listOf<String?>("old", null)) {
        ExternalBundleMetadataStore.metadata = original
        val repo = Repository().apply { cancel = true; finish.complete(Unit) }
        check(runCatching { repo.change() }.exceptionOrNull() is CancellationException)
        check(repo.dao.props!!.source == "old" && ExternalBundleMetadataStore.metadata == original)
    }
    println("PASS: Discovery endpoints remain unchanged while pending/failed, commit after success, restore metadata on cancellation")
}
""".replace("    // PRODUCTION", method)
with tempfile.TemporaryDirectory(prefix="discovery-selection-", dir=ROOT / "build") as directory:
    main = Path(directory) / "Main.kt"
    main.write_text(harness, encoding="utf-8")
    classes = Path(directory) / "classes"
    subprocess.run(list(map(str, [JAVA, "-cp", cp(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", cp(deps),
        "-d", classes, main])), check=True, timeout=90)
    subprocess.run(list(map(str, [JAVA, "-cp", cp([classes] + deps), "MainKt"])), check=True, timeout=30)
