"""Verify production download state is cleared after completion, failure and cancellation."""
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
wrapper = source[source.index("    private suspend fun downloadAssetToEntry("):source.index("\n    private suspend fun performRuntimeDownload(")]
wrapper = wrapper.replace("private suspend fun", "suspend fun")
harness = """
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import app.universal.patch.manager.network.runtime.*

class PatcherRuntimePluginSourceEntry(val catalogId: String = "morphe")
class GitHubAsset
class Repository(val mode: String) {
    val _downloadProgress = MutableStateFlow<RuntimeDownloadProgress?>(null)
    val started = CompletableDeferred<Unit>()
    fun runtimeProgress(entry: PatcherRuntimePluginSourceEntry, stage: RuntimeDownloadStage) =
        RuntimeDownloadProgress(entry.catalogId, "Morphe", stage)
    suspend fun performRuntimeDownload(entry: PatcherRuntimePluginSourceEntry, asset: GitHubAsset) {
        check(_downloadProgress.value?.stage == RuntimeDownloadStage.DOWNLOADING)
        _downloadProgress.value = runtimeProgress(entry, RuntimeDownloadStage.DOWNLOADING)
            .copy(bytesDownloaded = 75, totalBytes = 100)
        check(_downloadProgress.value?.fraction == 0.75f)
        started.complete(Unit)
        if (mode == "failure") error("Network failed")
        if (mode == "cancel") awaitCancellation()
        _downloadProgress.value = runtimeProgress(entry, RuntimeDownloadStage.VERIFYING)
        check(_downloadProgress.value?.fraction == null)
    }
""" + wrapper + """
}
fun main() = runBlocking {
    val entry = PatcherRuntimePluginSourceEntry()
    val asset = GitHubAsset()
    val success = Repository("success")
    success.downloadAssetToEntry(entry, asset)
    check(success._downloadProgress.value == null)

    val failed = Repository("failure")
    check(runCatching { failed.downloadAssetToEntry(entry, asset) }.isFailure)
    check(failed._downloadProgress.value == null)

    val cancelled = Repository("cancel")
    val job = launch { cancelled.downloadAssetToEntry(entry, asset) }
    cancelled.started.await()
    job.cancelAndJoin()
    check(cancelled._downloadProgress.value == null)
    println("PASS: resumed byte progress; verification stage; completion/error/cancellation clear UI state")
}
"""
(ROOT/"build").mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix="runtime-progress-", dir=ROOT/"build") as temporary:
    folder = Path(temporary)
    fixture = folder/"RuntimeDownloadProgressHarness.kt"
    fixture.write_text(harness, encoding="utf-8")
    model = ROOT/"app/src/main/java/app/universal/patch/manager/network/runtime/RuntimeDownloadProgress.kt"
    subprocess.run([str(JAVA), "-cp", os.pathsep.join(map(str, compiler)),
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "17",
        "-classpath", os.pathsep.join(map(str, dependencies)), "-d", str(folder),
        str(fixture), str(model)], check=True)
    subprocess.run([str(JAVA), "-cp", os.pathsep.join([str(folder), *map(str, dependencies)]),
        "RuntimeDownloadProgressHarnessKt"], check=True)
