"""Exercise the production activation method with uninstall and storage fixtures."""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[4]
CACHE = Path(os.environ.get("GRADLE_USER_HOME", ROOT / ".gradle-user-home")) / "caches/modules-2/files-2.1"
JAVA = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-17")) / "bin/java.exe"

def artifact(group, name, version):
    return next((CACHE / group / name / version).glob("*/*.jar"))

compiler = [artifact("org.jetbrains.kotlin", name, "2.4.10") for name in
    ("kotlin-compiler-embeddable", "kotlin-stdlib", "kotlin-reflect", "kotlin-script-runtime")]
stdlib = artifact("org.jetbrains.kotlin", "kotlin-stdlib", "2.4.10")
coroutines = artifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.10.2")
compiler += [coroutines, artifact("org.jetbrains", "annotations", "13.0")]
dependencies = [stdlib, coroutines]
source = (ROOT / "app/src/main/java/app/universal/patch/manager/domain/repository/PatcherRuntimePluginRepository.kt").read_text(encoding="utf-8")
method = source[source.index("    private suspend fun activateCatalogReplacement("):source.index("\n    suspend fun importCatalogFile(")]
method = method.replace("private suspend fun", "suspend fun").replace(
    "app.universal.patch.manager.network.runtime.RuntimeCatalogItem", "Item")
harness = """
import java.io.File
import kotlinx.coroutines.runBlocking

data class Item(val id: String, val packageName: String, val kind: String, val name: String)
data class PatcherRuntimePluginSourceEntry(
    val id: String, val catalogId: String?, val expectedPackageName: String,
    val expectedKind: String, val legacyProtocol: Boolean = false, val archiveName: String = "new.apk"
)
class PatcherRuntimePluginSourceState(val state: State) {
    sealed interface State { data object Loaded : State }
}
class Value<T>(var value: T)
object Session { enum class State { Succeeded, Aborted } }
object R { object string { const val runtime_catalog_remove_old_failed = 1 } }
class App { fun getString(id: Int, name: String) = "Allow uninstall of $name" }
class PM(val events: MutableList<String>) {
    var installed = true
    var aborted = false
    fun getPackageInfo(name: String, flags: Int): Any? = if (installed) Any() else null
    suspend fun uninstallPackage(name: String): Session.State {
        events += "uninstall"
        if (aborted) return Session.State.Aborted
        installed = false
        return Session.State.Succeeded
    }
}
class Repository(val root: File) {
    val events = mutableListOf<String>()
    val pm = PM(events)
    val app = App()
    val archiveCleanupRequests = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    val signingPackageInfoFlags = 0
    var valid = true
    var entries = emptyList<PatcherRuntimePluginSourceEntry>()
    val _sourceStates = Value(emptyMap<String, PatcherRuntimePluginSourceState>())
    fun sourceFile(entry: PatcherRuntimePluginSourceEntry) = managedSourceDirectory(entry.id).resolve(entry.archiveName)
    fun managedSourceDirectory(id: String) = root.resolve(id)
    fun readArchivePackageInfo(file: File) = file
    fun validateManagedArchive(entry: PatcherRuntimePluginSourceEntry, archive: File, file: File) {
        check(valid && file.isFile) { "Invalid replacement" }
        events += "verified"
    }
    fun resolvePlugin(archive: File, sourceId: String) { events += "resolved" }
    suspend fun removePlugin(name: String) { events += "removed" }
    fun readSourceEntries() = entries
    fun writeSourceEntries(updated: List<PatcherRuntimePluginSourceEntry>) {
        check(!pm.installed) { "New plugin activated before the old app was uninstalled" }
        events += "activated"
        entries = updated
    }
    suspend fun reload() {
        events += "reloaded"
        _sourceStates.value = entries.associate { it.id to PatcherRuntimePluginSourceState(PatcherRuntimePluginSourceState.State.Loaded) }
    }
""" + method + """
}
fun main(args: Array<String>) = runBlocking {
    val base = File(args[0]).apply { mkdirs() }
    val item = Item("revanced-v21", "official.runtime", "revanced-v21", "ReVanced v21")
    val replacement = PatcherRuntimePluginSourceEntry("current", item.id, item.packageName, item.kind)
    fun fixture(name: String): Repository {
        val repo = Repository(base.resolve(name).apply { mkdirs() })
        repo.entries = listOf(replacement.copy(legacyProtocol = true, archiveName = "old.apk"))
        repo.managedSourceDirectory("current").mkdirs()
        repo.sourceFile(replacement).writeText("verified staged APK")
        repo.sourceFile(repo.entries.single()).writeText("old managed APK")
        return repo
    }
    val success = fixture("success")
    val duplicate = replacement.copy(id = "duplicate", archiveName = "old.apk")
    val unrelated = replacement.copy(id = "unrelated", catalogId = "other")
    success.entries += listOf(duplicate, unrelated)
    success.managedSourceDirectory("duplicate").mkdirs()
    success.sourceFile(duplicate).writeText("duplicate")
    success.managedSourceDirectory("unrelated").mkdirs()
    success.sourceFile(unrelated).writeText("unrelated")
    success.activateCatalogReplacement(item, replacement)
    check(success.events == listOf("verified", "resolved", "uninstall", "removed", "activated", "reloaded"))
    check(!success.pm.installed && success.sourceFile(replacement).isFile)
    check(success.sourceFile(replacement.copy(archiveName = "old.apk")).exists())
    check(success.archiveCleanupRequests.tryReceive().isSuccess)
    check(success.managedSourceDirectory("duplicate").exists()) // Deferred cleanup protects runtime users.
    check(success.sourceFile(unrelated).isFile)

    val cancelled = fixture("cancelled")
    val oldEntries = cancelled.entries
    cancelled.pm.aborted = true
    check(runCatching { cancelled.activateCatalogReplacement(item, replacement) }.isFailure)
    check(cancelled.pm.installed && cancelled.entries == oldEntries)
    check("activated" !in cancelled.events)
    check(cancelled.sourceFile(replacement).isFile && cancelled.sourceFile(oldEntries.single()).isFile)
    cancelled.pm.aborted = false
    cancelled.events.clear()
    cancelled.activateCatalogReplacement(item, replacement)
    check(!cancelled.pm.installed && "activated" in cancelled.events)

    val invalid = fixture("invalid")
    invalid.valid = false
    check(runCatching { invalid.activateCatalogReplacement(item, replacement) }.isFailure)
    check(invalid.events.isEmpty() && invalid.pm.installed)
    val absent = fixture("absent")
    absent.pm.installed = false
    absent.activateCatalogReplacement(item, replacement)
    check("uninstall" !in absent.events && "activated" in absent.events)
    println("PASS: verify before uninstall; uninstall before activation; cancellation, retry, invalid APK and cleanup")
}
"""
(ROOT / "build").mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix="runtime-replacement-", dir=ROOT / "build") as temporary:
    work = Path(temporary)
    main = work / "Main.kt"
    main.write_text(harness, encoding="utf-8")
    classes = work / "classes"
    subprocess.run([str(JAVA), "-cp", os.pathsep.join(map(str, compiler)),
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "17",
        "-classpath", os.pathsep.join(map(str, dependencies)), "-d", str(classes), str(main)], check=True)
    subprocess.run([str(JAVA), "-cp", os.pathsep.join(map(str, [classes] + dependencies)),
        "MainKt", str(work / "fixtures")], check=True)
