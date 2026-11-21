"""Exercise the production selection orchestration with delayed, failed and successful downloads."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[4]
namespace = {"__file__": str(Path(__file__).with_name("bundle-ui-issues-test.py"))}
exec(Path(namespace["__file__"]).read_text().split('(ROOT / "build").mkdir')[0], namespace)
compiler, deps, JAVA, cp = (namespace[key] for key in ("compiler", "deps", "JAVA", "cp"))
deps = deps + [namespace["artifact"]("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.10.2")]
source = (ROOT / "app/src/main/java/app/universal/patch/manager/domain/repository/PatchBundleRepository.kt").read_text(encoding="utf-8")
method = source[source.index("    suspend fun JsonPatchBundle.updateRepositorySelection("):source.index("    suspend fun JsonPatchBundle.setRepositoryReleaseChannel(")]
harness = """
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
data class RepositoryBundleSettings(val latest: Boolean = false, val useSpecificVersion: Boolean = false, val selectedVersion: String? = null)
data class JsonPatchBundle(val uid: Int = 1, val repositorySettings: RepositoryBundleSettings = RepositoryBundleSettings(), val denyAccess: Boolean = false, val autoUpdate: Boolean = true) {
    fun withRepositorySettings(settings: RepositoryBundleSettings) = copy(repositorySettings = settings)
    suspend fun fetchAvailableVersions(): List<String> {
        Lookup.duringFetch?.invoke()
        if (denyAccess) error("rate limited")
        return emptyList() // An unavailable latest channel must not prevent an empty pin.
    }
}
object Lookup { var duringFetch: (() -> Unit)? = null }
data class State(val sources: Map<Int, JsonPatchBundle>)
class Box<T>(var value: T)
fun <K,V> Map<K,V>.put(key: K, value: V) = this + (key to value)
fun Throwable.simpleMessage() = message
object R { object string { const val patches_download_fail = 1 } }
object RepositoryBundleSettingsStore {
    var writes = 0
    fun write(dir: Int, settings: RepositoryBundleSettings) { writes++ }
}
class Repository {
    val store = objectStore
    val lock = Mutex()
    val started = CompletableDeferred<Unit>()
    val finish = CompletableDeferred<Unit>()
    var success = false
    var candidate: JsonPatchBundle? = null
    var errors = 0
    fun directoryOf(uid: Int) = uid
    fun toast(id: Int, error: String?) { errors++ }
    suspend fun notifyQueuedBundleSetting() {}
    suspend fun <T> withRemoteUpdateReservation(onQueued: suspend () -> Unit, block: suspend () -> T): T = lock.withLock { block() }
    suspend fun dispatchAction(name: String, action: (State) -> State) { store.state.value = action(store.state.value) }
    suspend fun performRemoteUpdate(force: Boolean, showToast: Boolean, allowUnsafeNetwork: Boolean,
        showProgress: Boolean, onPerBundleProgress: Any?, onBundleUpdated: Any?,
        predicate: (JsonPatchBundle) -> Boolean, refreshMetadata: Boolean,
        selectionOverrides: Map<Int, JsonPatchBundle>) {
        check(force && showProgress && showToast && refreshMetadata && !allowUnsafeNetwork)
        candidate = selectionOverrides.getValue(1)
        check(predicate(candidate!!))
        started.complete(Unit)
        finish.await()
        if (success) {
            RepositoryBundleSettingsStore.write(1, candidate!!.repositorySettings)
            store.state.value = State(mapOf(1 to candidate!!))
        }
    }
    // PRODUCTION
}
object objectStore { val state = Box(State(mapOf(1 to JsonPatchBundle()))) }
fun main() = runBlocking {
    for (old in listOf(false, true)) for (succeed in listOf(false, true)) {
        val initial = JsonPatchBundle(repositorySettings = RepositoryBundleSettings(latest = old))
        objectStore.state.value = State(mapOf(1 to initial))
        RepositoryBundleSettingsStore.writes = 0
        val repo = Repository()
        repo.success = succeed
        val change = async { with(repo) { initial.updateRepositorySelection { it.repositorySettings.copy(latest = !old) } } }
        repo.started.await()
        check(repo.store.state.value.sources[1] == initial) { "Published a toggle before download completed" }
        check(RepositoryBundleSettingsStore.writes == 0)
        check(repo.candidate!!.repositorySettings.latest == !old)
        repo.finish.complete(Unit)
        check(change.await() == succeed)
        check(repo.store.state.value.sources[1]!!.repositorySettings.latest == if (succeed) !old else old)
        check(RepositoryBundleSettingsStore.writes == if (succeed) 1 else 0)
    }
    for (limited in listOf(false, true)) {
        val initial = JsonPatchBundle(denyAccess = limited)
        objectStore.state.value = State(mapOf(1 to initial))
        val repo = Repository()
        val accepted = with(repo) { initial.updateRepositorySelection { it.repositorySettings.copy(useSpecificVersion = true) } }
        check(accepted == !limited)
        check(repo.store.state.value.sources[1]!!.repositorySettings.useSpecificVersion == !limited)
        check(repo.candidate == null) { "Empty pin should not download a bundle" }
    }
    val initial = JsonPatchBundle()
    objectStore.state.value = State(mapOf(1 to initial))
    Lookup.duringFetch = {
        objectStore.state.value = State(mapOf(1 to initial.copy(autoUpdate = false)))
    }
    val repo = Repository()
    check(with(repo) { initial.updateRepositorySelection { it.repositorySettings.copy(useSpecificVersion = true) } })
    check(!repo.store.state.value.sources[1]!!.autoUpdate) { "Pinning overwrote a concurrent auto-update change" }
    Lookup.duringFetch = null
    println("PASS: pending/successful/failed toggles in both directions, empty pin, and rate-limited pin")
}
""".replace("    // PRODUCTION", method)
with tempfile.TemporaryDirectory(prefix="bundle-selection-", dir=ROOT / "build") as directory:
    work = Path(directory)
    main = work / "Main.kt"
    main.write_text(harness, encoding="utf-8")
    classes = work / "classes"
    subprocess.run(list(map(str, [JAVA, "-cp", cp(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", cp(deps),
        "-d", classes, main])), check=True, timeout=90)
    subprocess.run(list(map(str, [JAVA, "-cp", cp([classes] + deps), "MainKt"])), check=True, timeout=30)
