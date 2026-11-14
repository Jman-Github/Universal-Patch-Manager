"""Host regressions for archive picker state and cancelled import staging.
Runs the production ViewModel with small Android/lifecycle fixtures and extracts the
production prepareImport method. This does not simulate the Android document picker.
Run before assembleRelease.
"""
import os
from pathlib import Path
import re
import subprocess
import tempfile
import tomllib

ROOT = Path(__file__).resolve().parents[4]
BASE = ROOT / "app/src/main/java/app/universal/patch/manager"
JAVA = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-17")) / "bin/java.exe"
CACHES = [ROOT / ".gradle-user-home/caches/modules-2/files-2.1",
          Path(os.environ.get("GRADLE_RO_DEP_CACHE", r"E:\Android\caches")) / "modules-2/files-2.1"]

def artifact(group, name, version="*"):
    found = [p for cache in CACHES for p in (cache / group / name).glob(version + "/*/*.jar")]
    if not found:
        raise RuntimeError(f"Missing cached {group}:{name}:{version}")
    return max(found, key=lambda p: tuple(map(int, re.findall(r"\d+", p.parent.parent.name))))

def cp(paths):
    return os.pathsep.join(map(str, paths))

version = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text())["versions"]["kotlin"]
compiler = [artifact("org.jetbrains.kotlin", name, version) for name in
            ("kotlin-compiler-embeddable", "kotlin-stdlib", "kotlin-reflect", "kotlin-script-runtime")]
coroutines = artifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm")
annotations = artifact("org.jetbrains", "annotations")
compiler += [coroutines, annotations]
deps = [artifact("org.jetbrains.kotlin", "kotlin-stdlib", version), coroutines, annotations]
vm_source = BASE / "ui/viewmodel/AppEntryArchiveViewModel.kt"
archive_source = (BASE / "domain/repository/AppEntryArchive.kt").read_text()
prepare = archive_source.split("    suspend fun prepareImport(", 1)[1].split("    suspend fun restore(", 1)[0]
prepare = "    suspend fun prepareImport(" + prepare
resource_ids = re.findall(r"R\.(string|plurals)\.(\w+)", vm_source.read_text())
r_source = "package app.universal.patch.manager\nobject R {\n"
for group in ("string", "plurals"):
    names = sorted({name for kind, name in resource_ids if kind == group})
    r_source += "object " + group + " { " + "; ".join(
        f"const val {name} = {index}" for index, name in enumerate(names, 1)) + " }\n"
r_source += "}\n"
stubs = {
"R.kt": r_source,
"Uri.kt": """
package android.net
data class Uri(val value: String)
""",
"App.kt": """
package android.app
class Resources {
    fun getQuantityString(id: Int, quantity: Int, vararg args: Any?) = "$id:$quantity"
}
class Application(val cacheDir: java.io.File) {
    val resources = Resources()
    val messages = mutableListOf<String>()
    fun getString(id: Int) = "error:$id"
    val contentResolver = Resolver()
}
class Resolver {
    lateinit var input: java.io.File
    fun openInputStream(uri: android.net.Uri) = input.inputStream()
}
""",
"Log.kt": """
package android.util
object Log { fun w(tag: String, message: String, error: Throwable) = 0 }
""",
"Compose.kt": """
package androidx.compose.runtime
import kotlin.reflect.KProperty
class MutableState<T>(var value: T)
fun <T> mutableStateOf(value: T) = MutableState(value)
operator fun <T> MutableState<T>.getValue(owner: Any?, property: KProperty<*>) = value
operator fun <T> MutableState<T>.setValue(owner: Any?, property: KProperty<*>, value: T) {
    this.value = value
}
""",
"Lifecycle.kt": """
package androidx.lifecycle
import kotlinx.coroutines.*
open class ViewModel {
    val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    protected open fun onCleared() {}
}
val ViewModel.viewModelScope get() = testScope
class SavedStateHandle(initial: Map<String, Any?> = emptyMap()) {
    val values = initial.toMutableMap()
    operator fun <T> set(key: String, value: T) { values[key] = value }
    @Suppress("UNCHECKED_CAST") fun <T> remove(key: String): T? = values.remove(key) as T?
}
""",
"MainDispatcher.kt": """
package regression
import kotlinx.coroutines.*
import kotlinx.coroutines.internal.MainDispatcherFactory
import kotlin.coroutines.CoroutineContext
@OptIn(InternalCoroutinesApi::class)
class HostMainFactory : MainDispatcherFactory {
    override val loadPriority = Int.MAX_VALUE
    override fun createDispatcher(allFactories: List<MainDispatcherFactory>) = HostMain
}
object HostMain : MainCoroutineDispatcher() {
    override val immediate get() = this
    override fun isDispatchNeeded(context: CoroutineContext) = false
    override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
}
""",
"Filesystem.kt": """
package app.universal.patch.manager.data.platform
class Filesystem
""",
"Util.kt": """
package app.universal.patch.manager.util
class PM
fun android.app.Application.toast(message: String) { messages.add(message) }
""",
"Archive.kt": """
package app.universal.patch.manager.domain.repository
import android.app.Application
import android.net.Uri
import app.universal.patch.manager.data.platform.Filesystem
import app.universal.patch.manager.util.PM
import kotlinx.coroutines.*
import java.io.*
import java.util.zip.ZipFile
class InstalledAppRepository
class PatchBundleRepository
data class Record(val currentPackageName: String, val version: String)
internal object Fixture {
    var entries = emptyList<AppEntryArchive.ExportEntry>()
    val exports = mutableListOf<List<AppEntryArchive.ExportEntry>>()
    var gate: CompletableDeferred<Unit>? = null
}
internal class AppEntryArchive(
    app: Application, fs: Filesystem, pm: PM,
    apps: InstalledAppRepository, bundles: PatchBundleRepository
) {
    enum class Stage { Preparing, Reading, Checking, Exporting, Saving, Importing }
    data class Progress(val stage: Stage, val completed: Int = 0, val total: Int = 0, val currentApp: String? = null)
    data class ExportEntry(val record: Record, val hasApk: Boolean = true)
    data class ImportPlan(val file: File, val entries: List<Int>)
    data class ImportResult(val imported: Int, val updated: Int, val skipped: Int)
    suspend fun exportEntries() = Fixture.entries.toList()
    suspend fun export(target: Uri, selected: List<ExportEntry>, onProgress: suspend (Progress) -> Unit): Int {
        Fixture.exports.add(selected)
        onProgress(Progress(Stage.Exporting, total = selected.size))
        Fixture.gate?.await()
        return selected.size
    }
    suspend fun prepareImport(uri: Uri, onProgress: suspend (Progress) -> Unit): ImportPlan = error("Not used")
    suspend fun restore(plan: ImportPlan, selected: Set<Int>, onProgress: suspend (Progress) -> Unit) =
        ImportResult(selected.size, 0, 0)
}
internal class ImportStaging(private val app: Application) {
    enum class Stage { Checking }
    data class Progress(val stage: Stage)
    data class ImportPlan(val file: File, val entries: List<Int>)
    data class Manifest(val entries: List<Int>)
    private fun readManifest(zip: ZipFile) = Manifest(listOf(1))
    private fun copy(input: InputStream, output: OutputStream) { input.copyTo(output) }
""" + prepare + "\n}\n",
"Main.kt": """
package regression
import android.app.Application
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import app.universal.patch.manager.data.platform.Filesystem
import app.universal.patch.manager.domain.repository.*
import app.universal.patch.manager.ui.viewmodel.AppEntryArchiveViewModel
import app.universal.patch.manager.util.PM
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.ZipOutputStream
import kotlin.coroutines.CoroutineContext

class QueuedDispatcher : CoroutineDispatcher() {
    val queue = LinkedBlockingQueue<Runnable>()
    override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
    fun next() = checkNotNull(queue.poll(5, TimeUnit.SECONDS)) { "Missing queued continuation" }
}

fun main() = runBlocking {
    val root = Files.createTempDirectory("app-entry-lifecycle-").toFile()
    try {
        val app = Application(root.resolve("cache").apply { mkdir() })
        fun vm(state: SavedStateHandle) = AppEntryArchiveViewModel(
            app, Filesystem(), PM(), InstalledAppRepository(), PatchBundleRepository(), state)
        val first = AppEntryArchive.ExportEntry(Record("saved.first", "1"))
        val second = AppEntryArchive.ExportEntry(Record("saved.second", "2"))
        val unavailable = AppEntryArchive.ExportEntry(Record("saved.missing", "3"), false)

        suspend fun selectedState(): SavedStateHandle {
            Fixture.entries = listOf(first, second, unavailable)
            val state = SavedStateHandle()
            val source = vm(state)
            source.chooseExport()!!.join()
            source.prepareExport(setOf(0, 2))
            return SavedStateHandle(state.values.toMap())
        }

        // The old ViewModel is gone while the picker is open. List order can also change.
        val restoredState = selectedState()
        check((restoredState.values.values.single() as ArrayList<*>).single().toString().length == 64)
        Fixture.entries = listOf(second, unavailable, first)
        vm(restoredState).finishExport(Uri("selected.zip"))!!.join()
        check(Fixture.exports.last() == listOf(first))
        check(restoredState.values.isEmpty())
        check(app.messages.none { it.startsWith("error:") })
        println("PASS: picker selection survives ViewModel recreation and app-list reordering")

        for (entries in listOf(listOf(second), listOf(first.copy(hasApk = false)),
                listOf(first.copy(record = Record("saved.first", "new-version"))))) {
            val state = selectedState()
            Fixture.entries = entries
            val exportsBefore = Fixture.exports.size
            val messagesBefore = app.messages.size
            val restored = vm(state)
            restored.finishExport(Uri("selected.zip"))!!.join()
            check(Fixture.exports.size == exportsBefore)
            check(app.messages.size == messagesBefore + 1 && app.messages.last().startsWith("error:"))
            check(!restored.busy)
        }
        println("PASS: removed, missing and changed selected APKs fail without exporting another app")

        val cancelledState = selectedState()
        vm(cancelledState).finishExport(null)
        check(cancelledState.values.isEmpty())
        val orphan = vm(SavedStateHandle())
        val before = Fixture.exports.size
        orphan.finishExport(Uri("empty-selection.zip"))!!.join()
        check(Fixture.exports.size == before && !orphan.busy)
        println("PASS: cancelling clears saved state; missing selections finish with a handled error")

        val busyState = selectedState()
        val busy = vm(busyState)
        Fixture.gate = CompletableDeferred()
        val job = busy.finishExport(Uri("busy.zip"))!!
        check(busy.busy && busy.finishExport(Uri("duplicate.zip")) == null)
        Fixture.gate!!.complete(Unit)
        job.join()
        Fixture.gate = null
        check(!busy.busy)
        println("PASS: duplicate export callbacks cannot start a second archive operation")

        // Pause the caller's dispatcher after IO has returned a plan, then cancel before delivery.
        // A try/finally only INSIDE withContext(IO) cannot clean up at this boundary.
        val input = root.resolve("source.zip")
        ZipOutputStream(input.outputStream()).use {}
        app.contentResolver.input = input
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var delivered = false
        val cancelled = scope.launch {
            ImportStaging(app).prepareImport(Uri("source.zip"))
            delivered = true
        }
        dispatcher.next().run()
        val queuedReturn = dispatcher.next()
        cancelled.cancel()
        queuedReturn.run()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!cancelled.isCompleted && System.nanoTime() < deadline) {
            dispatcher.queue.poll(100, TimeUnit.MILLISECONDS)?.run()
        }
        check(cancelled.isCompleted && !delivered)
        check(app.cacheDir.listFiles().orEmpty().isEmpty()) { "Cancelled import left a staged ZIP" }
        scope.cancel()
        println("PASS: cancellation at the IO-to-UI boundary removes the staged archive")

        val success = ImportStaging(app).prepareImport(Uri("source.zip"))
        check(success.file.isFile)
        success.file.delete()
        input.writeText("invalid zip")
        check(runCatching { ImportStaging(app).prepareImport(Uri("invalid.zip")) }.isFailure)
        check(app.cacheDir.listFiles().orEmpty().isEmpty())
        println("PASS: successful import transfers ownership; invalid ZIP leaves no temporary file")
    } finally {
        root.deleteRecursively()
    }
}
"""
}
(ROOT / "build").mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix="app-entry-lifecycle-", dir=ROOT / "build") as temporary:
    work = Path(temporary)
    sources = [vm_source]
    for name, content in stubs.items():
        path = work / name
        path.write_text(content, encoding="utf-8")
        sources.append(path)
    classes = work / "classes"
    subprocess.run(list(map(str, [JAVA, "-cp", cp(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", cp(deps),
        "-d", classes, *sources])), check=True, timeout=120)
    service = classes / "META-INF/services/kotlinx.coroutines.internal.MainDispatcherFactory"
    service.parent.mkdir(parents=True, exist_ok=True)
    service.write_text("regression.HostMainFactory\n")
    subprocess.run(list(map(str, [JAVA, "-cp", cp([classes] + deps), "regression.MainKt"])),
                   check=True, timeout=45)
