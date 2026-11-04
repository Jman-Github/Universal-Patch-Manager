"""Exercise production startup submission and Back cleanup with controlled coroutine races."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[4]
# Reuse only the artifact helpers, without running that script's tests.
namespace = {"__file__": str(Path(__file__).with_name("bundle-ui-issues-test.py"))}
exec(Path(namespace["__file__"]).read_text().split('(ROOT / "build").mkdir')[0], namespace)
compiler, deps, JAVA = namespace["compiler"], namespace["deps"], namespace["JAVA"]
deps = deps + [namespace["artifact"]("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.10.2")]
cp = namespace["cp"]
source = (ROOT / "app/src/main/java/app/universal/patch/manager/ui/viewmodel/PatcherViewModel.kt").read_text(encoding="utf-8")
submission = source[source.index("    private suspend fun launchWorker("):source.index("\n    private suspend fun handleDownloaderActivityRequest(")]
back = source[source.index("    fun onBack(cleanupLocalInput: Boolean)"):source.index("\n    fun isDeviceRooted()")]
# Android Main is replaced with the fixture's single-thread event loop.
back = back.replace("Dispatchers.Main.immediate", "dispatcher")
fixture = """
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object PatcherWorkspaceGate {
    val mutex = Mutex()
    suspend fun <T> withWorkspace(block: suspend () -> T): T = mutex.withLock { block() }
}
typealias PatchSelection = Unit
typealias Options = Unit
class ParcelUuid(val uuid: UUID)
class PatcherWorker { class Args; companion object { const val UNIQUE_WORK_NAME = "patching" } }
class Repository {
    val requested = CompletableDeferred<Unit>()
    val committed = CompletableDeferred<Unit>()
    val id = UUID.randomUUID()
    suspend inline fun <reified W, A> launchExpedited(name: String, input: A, onEnqueueRequested: (UUID) -> Unit): UUID {
        requested.complete(Unit)
        committed.await()
        onEnqueueRequested(id)
        return id
    }
}
class WorkManager {
    val cancelled = CompletableDeferred<UUID>()
    fun cancelWorkById(id: UUID) { cancelled.complete(id) }
}
class Filesystem { fun deleteRepatchInputStagingFile(path: String?) {} }
class Fixture(val dispatcher: CoroutineDispatcher, val root: File) {
    val workerRepository = Repository()
    val workManager = WorkManager()
    val workFinished = CompletableDeferred<Unit>()
    val cleaned = CompletableDeferred<Unit>()
    val argsStarted = CompletableDeferred<Unit>()
    val argsReady = CompletableDeferred<Unit>()
    var prePatchPreparationJob: Job? = null
    var workerLaunchJob: Job? = null
    var signatureWorkflowJob: Job? = null
    var leavingScreen = false
    var patcherWorkerId: ParcelUuid? = null
    var preparedInput: Any? = Any()
    var preparedInputIncludesDownload = true
    var selectedSplitConfiguration: Any? = Any()
    var patchedRepatchSourcePath: String? = null
    val fs = Filesystem()
    val tempDir = root.resolve(UUID.randomUUID().toString()).apply { mkdirs() }
    suspend fun buildWorkerArgs(selection: PatchSelection, options: Options): PatcherWorker.Args {
        argsStarted.complete(Unit)
        argsReady.await()
        return PatcherWorker.Args()
    }
    fun clearPendingActivityInteractions() {}
    fun clearPatchingNotification() {}
    fun stopPatchingTaskMonitor() {}
    fun cleanupPreparedInput() {
        check(workerLaunchJob?.isCompleted != false) { "Startup still owns the files" }
        check(prePatchPreparationJob?.isCompleted != false) { "Preparation still owns the files" }
        check(patcherWorkerId == null || workFinished.isCompleted) { "Worker still owns the files" }
        preparedInput = null
        cleaned.complete(Unit)
    }
    fun cleanupTemporaryLocalInput() {}
    suspend fun awaitWorkToFinish(id: UUID) { workFinished.await() }
    suspend fun submit() = launchWorker(Unit, Unit)
    // PRODUCTION_METHODS
}
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
fun main(args: Array<String>) = runBlocking {
    newSingleThreadContext("Main").use { main ->
        withContext(main) {
            val before = Fixture(main, File(args[0]))
            before.workerLaunchJob = launch(start = CoroutineStart.UNDISPATCHED) { before.submit() }
            before.argsStarted.await()
            before.onBack(true)
            withTimeout(3000) { before.cleaned.await() }
            check(!before.workerRepository.requested.isCompleted)
            check(before.preparedInput == null)

            val during = Fixture(main, File(args[0]))
            during.argsReady.complete(Unit)
            during.workerLaunchJob = launch(start = CoroutineStart.UNDISPATCHED) {
                during.submit()
                currentCoroutineContext().ensureActive()
                error("Cancelled startup resumed after submission")
            }
            during.workerRepository.requested.await()
            during.onBack(true)
            yield()
            check(!during.cleaned.isCompleted && during.preparedInput != null)
            during.workerRepository.committed.complete(Unit)
            check(withTimeout(3000) { during.workManager.cancelled.await() } == during.workerRepository.id)
            check(during.patcherWorkerId?.uuid == during.workerRepository.id)
            check(during.preparedInput == null)
            check(!during.cleaned.isCompleted)
            PatcherWorkspaceGate.mutex.lock()
            during.workFinished.complete(Unit)
            yield()
            check(!during.cleaned.isCompleted) { "Terminal WorkInfo was mistaken for released files" }
            PatcherWorkspaceGate.mutex.unlock()
            withTimeout(3000) { during.cleaned.await() }
            // Back must be idempotent.
            during.onBack(true)

            val preparing = Fixture(main, File(args[0]))
            val releasePreparation = CompletableDeferred<Unit>()
            preparing.prePatchPreparationJob = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { releasePreparation.await() }
                }
            }
            preparing.onBack(true)
            yield()
            check(!preparing.cleaned.isCompleted)
            releasePreparation.complete(Unit)
            withTimeout(3000) { preparing.cleaned.await() }
        }
    }
    println("PASS: Back before enqueue, during submission, after worker ownership, and during preparation")
}
""".replace("    // PRODUCTION_METHODS", submission + "\n" + back)
(ROOT / "build").mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix="patcher-startup-cancel-", dir=ROOT / "build") as temporary:
    work = Path(temporary)
    harness = work / "Main.kt"
    harness.write_text(fixture, encoding="utf-8")
    classes = work / "classes"
    subprocess.run(list(map(str, [JAVA, "-cp", cp(compiler),
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
        "-jvm-target", "17", "-classpath", cp(deps), "-d", classes, harness])), check=True, timeout=90)
    subprocess.run(list(map(str, [JAVA, "-cp", cp([classes] + deps), "MainKt", work / "files"])),
        check=True, timeout=20)
