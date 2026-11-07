"""Compile the production file-import method against small platform fixtures."""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[4]
CACHE = Path(os.environ.get("GRADLE_USER_HOME", ROOT / ".gradle-user-home")) / "caches/modules-2/files-2.1"
JAVA = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-17")) / "bin/java.exe"

def artifact(group, name, version):
    return next((CACHE / group / name / version).glob("*/*.jar"))

def cp(files):
    return os.pathsep.join(map(str, files))

compiler = [artifact("org.jetbrains.kotlin", name, "2.4.10") for name in
    ("kotlin-compiler-embeddable", "kotlin-stdlib", "kotlin-reflect", "kotlin-script-runtime")]
compiler += [artifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.10.2"),
             artifact("org.jetbrains", "annotations", "13.0")]
dependencies = [artifact("org.jetbrains.kotlin", "kotlin-stdlib", "2.4.10"),
                artifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.10.2")]
source = (ROOT / "app/src/main/java/app/universal/patch/manager/ui/viewmodel/PatcherRuntimePluginsViewModel.kt").read_text()
method = source[source.index("    fun importRuntimeFile("):source.index("    val runtimePluginStates")]
harness = """
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.*

fun Throwable.simpleMessage(): String? = message
class Resolver {
    var fail = false
    fun openInputStream(uri: android.net.Uri): java.io.InputStream {
        if (fail) throw IOException("Cannot read selected document")
        return "plugin".byteInputStream()
    }
}
object R { object string { const val runtime_progress_importing = 1 } }
class App(val cacheDir: File) {
    val contentResolver = Resolver()
    fun getString(id: Int) = "Importing runtime APK"
}
class Repository {
    var calls = 0
    var waitForCancellation = false
    val started = CompletableDeferred<Unit>()
    suspend fun importCatalogFile(file: File): String {
        calls++
        check(file.readText() == "plugin")
        started.complete(Unit)
        if (waitForCancellation) awaitCancellation()
        return "morphe"
    }
}
data class Item(val id: String, val isEngine: Boolean)
class Catalog { val plugins = listOf(Item("morphe", true)) }
class ViewModel(val appContext: App) {
    val uncaught = mutableListOf<Throwable>()
    val viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined +
        CoroutineExceptionHandler { _, error -> uncaught.add(error) })
    val runtimePluginRepository = Repository()
    val catalog = Catalog()
    var currentDownload: String? = null
    var downloadError: String? = null
    val selected = mutableSetOf<String>()
    fun selectCatalogRuntime(id: String, enabled: Boolean) { if (enabled) selected.add(id) }
""" + method + """
}
fun main() = runBlocking {
    val root = Files.createTempDirectory("runtime-file-import-check").toFile()
    try {
        val invalidCache = root.resolve("file-instead-of-cache-dir").apply { writeText("occupied") }
        val failure = ViewModel(App(invalidCache))
        failure.importRuntimeFile(android.net.Uri()).join()
        check(failure.uncaught.isEmpty()) { "Temporary-file creation escaped the import error handler" }
        check(failure.downloadError != null && failure.currentDownload == null)
        check(failure.runtimePluginRepository.calls == 0)
        check(invalidCache.readText() == "occupied")

        val directory = root.resolve("cache").apply { mkdir() }
        val success = ViewModel(App(directory))
        success.importRuntimeFile(android.net.Uri()).join()
        check(success.uncaught.isEmpty() && success.downloadError == null && success.currentDownload == null)
        check(success.runtimePluginRepository.calls == 1 && success.selected == setOf("morphe"))
        check(directory.listFiles()!!.isEmpty())

        val readFailure = ViewModel(App(directory))
        readFailure.appContext.contentResolver.fail = true
        readFailure.importRuntimeFile(android.net.Uri()).join()
        check(readFailure.uncaught.isEmpty() && readFailure.downloadError != null && readFailure.currentDownload == null)
        check(readFailure.runtimePluginRepository.calls == 0 && directory.listFiles()!!.isEmpty())

        val cancelled = ViewModel(App(directory))
        cancelled.runtimePluginRepository.waitForCancellation = true
        val job = cancelled.importRuntimeFile(android.net.Uri())
        cancelled.runtimePluginRepository.started.await()
        job.cancelAndJoin()
        check(job.isCancelled && cancelled.uncaught.isEmpty())
        check(cancelled.downloadError == null && cancelled.currentDownload == null && directory.listFiles()!!.isEmpty())
        listOf(failure, success, readFailure, cancelled).forEach { it.viewModelScope.cancel() }
        println("PASS: temporary-file creation failure, document read failure, successful import and cancellation cleanup")
    } finally { root.deleteRecursively() }
}
"""
(ROOT / "build").mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix="runtime-file-import-", dir=ROOT / "build") as temporary:
    work = Path(temporary)
    main = work / "Main.kt"
    main.write_text(harness)
    uri = work / "Uri.kt"
    uri.write_text("package android.net\nclass Uri\n")
    classes = work / "classes"
    subprocess.run([str(JAVA), "-cp", cp(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", cp(dependencies),
        "-d", str(classes), str(main), str(uri)], check=True, timeout=90)
    subprocess.run([str(JAVA), "-cp", cp([classes] + dependencies), "MainKt"], check=True, timeout=30)
