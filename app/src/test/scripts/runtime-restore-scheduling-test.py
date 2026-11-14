"""Exercise the production restore observer while enqueueing overlaps another import."""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[4]
CACHE = Path(os.environ.get("GRADLE_USER_HOME", ROOT/".gradle-user-home"))/"caches/modules-2/files-2.1"
JAVA = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-17"))/"bin/java.exe"
def artifact(group, name, version):
    return next((CACHE/group/name/version).glob("*/*.jar"))
compiler = [artifact("org.jetbrains.kotlin", n, "2.4.10") for n in
    ("kotlin-compiler-embeddable", "kotlin-stdlib", "kotlin-reflect", "kotlin-script-runtime")]
coroutines = artifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.10.2")
compiler += [coroutines, artifact("org.jetbrains", "annotations", "13.0")]
dependencies = [artifact("org.jetbrains.kotlin", "kotlin-stdlib", "2.4.10"), coroutines]
source = (ROOT/"app/src/main/java/app/universal/patch/manager/domain/repository/PatcherRuntimePluginRepository.kt").read_text(encoding="utf-8")
start = source.index("        scope.launch {", source.index('Failed to register runtime plugin package change receiver'))
observer = source[start:source.index("\n    }\n\n    val pluginStates", start)]
harness = """
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

class Setting<T>(initial: T) {
    val flow = MutableStateFlow(initial)
    suspend fun get() = flow.value
}
class Prefs {
    val runtimeRestorePending = Setting(true)
    val runtimeRestoreRequest = Setting("first-import")
}
object Log { fun e(tag: String, message: String, error: Exception) {} }
object RuntimeRestoreWorker {
    val started = Channel<Unit>(Channel.UNLIMITED)
    val release = CompletableDeferred<Unit>()
    var calls = 0
    suspend fun enqueue(app: Any) {
        calls++
        started.send(Unit)
        if (calls == 1) release.await()
    }
}
class Repository(val prefs: Prefs = Prefs()) {
    val app = Any()
    val tag = "restore-test"
    val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    init {
""" + observer + """
    }
}
fun main() = runBlocking {
    val repo = Repository()
    RuntimeRestoreWorker.started.receive()
    // The preference stream can conflate these writes while its observer is suspended.
    repo.prefs.runtimeRestorePending.flow.value = false
    repo.prefs.runtimeRestoreRequest.flow.value = "second-import"
    repo.prefs.runtimeRestorePending.flow.value = true
    RuntimeRestoreWorker.release.complete(Unit)
    withTimeout(2000) { RuntimeRestoreWorker.started.receive() }
    check(RuntimeRestoreWorker.calls >= 2) { "The second import was never scheduled" }
    repo.prefs.runtimeRestoreRequest.flow.value = "third-import"
    withTimeout(2000) { RuntimeRestoreWorker.started.receive() }
    repo.scope.cancel()

    // Pending work saved before request IDs existed must still resume on startup.
    val legacyPrefs = Prefs().apply { runtimeRestoreRequest.flow.value = "" }
    val resumed = Repository(legacyPrefs)
    withTimeout(2000) { RuntimeRestoreWorker.started.receive() }
    resumed.scope.cancel()

    val idlePrefs = Prefs().apply { runtimeRestorePending.flow.value = false }
    val idle = Repository(idlePrefs)
    idlePrefs.runtimeRestoreRequest.flow.value = "already-completed"
    yield()
    check(RuntimeRestoreWorker.started.tryReceive().isFailure)
    idle.scope.cancel()
    println("PASS: overlapping imports; repeated pending requests; legacy resume; completed restores stay idle")
}
"""
(ROOT/"build").mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix="runtime-scheduling-", dir=ROOT/"build") as temporary:
    folder = Path(temporary)
    fixture = folder/"RuntimeRestoreSchedulingHarness.kt"
    fixture.write_text(harness, encoding="utf-8")
    subprocess.run([str(JAVA), "-cp", os.pathsep.join(map(str, compiler)),
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "17",
        "-classpath", os.pathsep.join(map(str, dependencies)), "-d", str(folder), str(fixture)], check=True)
    subprocess.run([str(JAVA), "-cp", os.pathsep.join([str(folder), *map(str, dependencies)]),
        "RuntimeRestoreSchedulingHarnessKt"], check=True)
