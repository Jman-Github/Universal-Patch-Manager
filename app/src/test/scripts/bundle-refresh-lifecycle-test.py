"""Run production pull-refresh orchestration against delayed updates and failures."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[4]
namespace = {"__file__": str(Path(__file__).with_name("bundle-ui-issues-test.py"))}
exec(Path(namespace["__file__"]).read_text().split('(ROOT / "build").mkdir')[0], namespace)
compiler, deps, JAVA = namespace["compiler"], namespace["deps"], namespace["JAVA"]
deps = deps + [namespace["artifact"]("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.10.2")]
cp = namespace["cp"]
source = (ROOT / "app/src/main/java/app/universal/patch/manager/domain/repository/PatchBundleRepository.kt").read_text(encoding="utf-8")
method = source[source.index("    suspend fun updateCheck("):source.index("\n    suspend fun fetchUpdatesAndNotify(")]
harness = """
import kotlinx.coroutines.*
object R { object string {
    const val patches_download_fail = 1
    const val bundle_update_manual_available = 2
    const val patches_update_success = 3
    const val patches_update_unavailable = 4
} }
class Bundle(val updatesAutomatically: Boolean = true)
data class Result(val updated: Boolean = false, val error: String? = null)
class State { var value = emptyMap<Int, String>() }
class Repository(val scope: CoroutineScope) {
    val started = CompletableDeferred<Unit>()
    val finishDownload = CompletableDeferred<Unit>()
    var automatic = Result()
    var manualError: String? = null
    val manualUpdateInfoFlow = State()
    val messages = mutableListOf<Pair<Int, String?>>()
    suspend fun awaitReady() {}
    suspend fun updateNowResult(predicate: (Bundle) -> Boolean, refreshMetadata: Boolean): Result {
        check(refreshMetadata && predicate(Bundle()) && !predicate(Bundle(false)))
        started.complete(Unit)
        finishDownload.await()
        return automatic
    }
    suspend fun checkManualUpdates(refreshMetadata: Boolean): String? {
        check(refreshMetadata)
        return manualError
    }
    fun toast(id: Int, error: String? = null) { messages += id to error }
    // PRODUCTION
}
fun main() = runBlocking {
    suspend fun verify(result: Result, manual: String?, available: Boolean, expected: Int) {
        val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = Repository(background)
            repo.automatic = result
            repo.manualError = manual
            if (available) repo.manualUpdateInfoFlow.value = mapOf(1 to "v2")
            val refresh = launch { repo.updateCheck(showToast = true) }
            repo.started.await()
            yield()
            check(!refresh.isCompleted && repo.messages.isEmpty())
            repo.finishDownload.complete(Unit)
            refresh.join()
            check(repo.messages.single().first == expected) { repo.messages.toString() }
        } finally { background.cancel() }
    }
    verify(Result(error = "download failed"), null, false, R.string.patches_download_fail)
    verify(Result(), "metadata failed", true, R.string.patches_download_fail)
    verify(Result(), null, true, R.string.bundle_update_manual_available)
    verify(Result(true), null, false, R.string.patches_update_success)
    verify(Result(), null, false, R.string.patches_update_unavailable)
    val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
        val repo = Repository(background)
        val refresh = launch { repo.updateCheck(true) }
        repo.started.await()
        refresh.cancelAndJoin()
        repo.finishDownload.complete(Unit)
        withTimeout(3000) {
            while (repo.messages.isEmpty()) delay(10)
        }
        check(repo.messages.single().first == R.string.patches_update_unavailable)
    } finally { background.cancel() }
    println("PASS: refresh waits, failure/available/success/no-update feedback, and continuation after screen exit")
}
""".replace("    // PRODUCTION", method)
with tempfile.TemporaryDirectory(prefix="bundle-refresh-", dir=ROOT / "build") as directory:
    work = Path(directory)
    fixture = work / "Main.kt"
    fixture.write_text(harness, encoding="utf-8")
    classes = work / "classes"
    subprocess.run(list(map(str, [JAVA, "-cp", cp(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", cp(deps),
        "-d", classes, fixture])), check=True, timeout=90)
    subprocess.run(list(map(str, [JAVA, "-cp", cp([classes] + deps), "MainKt"])), check=True, timeout=20)
