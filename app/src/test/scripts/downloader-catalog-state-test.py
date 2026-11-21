"""Exercise the production catalog ViewModel through refresh, retry and background imports."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[4]
BASE = ROOT / "app/src/main/java/app/universal/patch/manager"
helper = ROOT / "app/src/test/scripts/bundle-ui-issues-test.py"
env = {"__file__": str(helper)}
exec(helper.read_text(encoding="utf-8").split('(ROOT / "build").mkdir', 1)[0], env)
files = {
    "State.kt": """
package androidx.compose.runtime
import kotlin.reflect.KProperty
class State<T>(var value:T)
fun <T> mutableStateOf(value:T)=State(value)
operator fun <T> State<T>.getValue(receiver:Any?,property:KProperty<*>)=value
operator fun <T> State<T>.setValue(receiver:Any?,property:KProperty<*>,next:T) { value=next }
""",
    "Lifecycle.kt": """
package androidx.lifecycle
import kotlinx.coroutines.*
open class ViewModel { val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined) }
val ViewModel.viewModelScope:CoroutineScope get()=scope
""",
    "Platform.kt": """
package android.net
data class Uri(val value:String)
""",
    "PM.kt": """
package app.universal.patch.manager.util
class App { fun getString(id:Int)="error-$id" }
class PM { val application=App() }
""",
    "R.kt": """
package app.universal.patch.manager
object R { object string {
 const val downloader_catalog_load_failed=1
 const val runtime_progress_preparing=2
 const val downloader_catalog_import_failed=3
 const val downloader_catalog_importing_file=4
 const val downloader_file_import_failed=5
 const val downloader_catalog_partial=6
} }
""",
    "Source.kt": """
package app.universal.patch.manager.network.downloader
data class Entry(val versionKey:String?)
data class DownloaderPluginSourceState(val entry:Entry,val state:State) {
 sealed interface State {
  data object Missing:State
  data object Failed:State
  data object Untrusted:State
 }
}
""",
    "Repository.kt": """
package app.universal.patch.manager.domain.repository
import android.net.Uri
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import app.universal.patch.manager.network.downloader.*
import app.universal.patch.manager.network.runtime.*
class DownloaderPluginRepository {
 val sourceStates=MutableStateFlow(emptyMap<String,DownloaderPluginSourceState>())
 var catalog=emptyList<DownloaderCatalogItem>()
 var failRefresh=false
 val unavailableProviders=mutableSetOf<String>()
 val failures=mutableSetOf<String>()
 val attempts=mutableListOf<String>()
 val repairs=mutableListOf<String>()
 var gate:CompletableDeferred<Unit>?=null
 suspend fun reload() {}
 suspend fun catalogAssets(onProviderFailure:((String)->Unit)?=null):List<DownloaderCatalogItem> {
  if(failRefresh) error("offline")
  unavailableProviders.forEach { onProviderFailure?.invoke(it) }
  return catalog.filterNot { it.repoUrl in unavailableProviders }
 }
 fun catalogSource(item:DownloaderCatalogItem,sources:Map<String,DownloaderPluginSourceState>)=sources[item.id]
 suspend fun getManagedSourceApk(id:String):File?=null
 suspend fun importSourcesFromUrl(url:String,onProgress:(RuntimeDownloadProgress)->Unit) {
  attempts.add(url)
  gate?.await()
  if(url in failures) error("download failed")
  val item=catalog.single { it.downloadUrl==url }
  onProgress(RuntimeDownloadProgress(item.id,item.name,RuntimeDownloadStage.DOWNLOADING,50,100))
  sourceStates.value=sourceStates.value+(item.id to
   DownloaderPluginSourceState(Entry(item.versionKey,item.id),DownloaderPluginSourceState.State.Untrusted))
 }
 suspend fun updateSource(id:String,onProgress:((RuntimeDownloadProgress)->Unit)?=null) {
  attempts.add("url-"+id)
  repairs.add(id)
  gate?.await()
  val source=sourceStates.value.getValue(id)
  sourceStates.value=sourceStates.value+(id to source.copy(state=DownloaderPluginSourceState.State.Untrusted))
 }
 suspend fun importSourceFile(uri:Uri) { gate?.await() }
}
""",
    "Test.kt": """
import kotlinx.coroutines.*
import app.universal.patch.manager.domain.repository.*
import app.universal.patch.manager.network.downloader.*
import app.universal.patch.manager.ui.viewmodel.*
import app.universal.patch.manager.util.*
fun main()=runBlocking {
 val repo=DownloaderPluginRepository()
 fun item(id:String,version:String="v1")=DownloaderCatalogItem(
  id,id,version,"repo-"+id,id,"url-"+id,version,100L)
 repo.catalog=listOf(item("a"),item("b"))
 val vm=DownloaderCatalogViewModel(repo,PM())
 vm.select("a",true); vm.select("b",true)
 repo.failures.add("url-b")
 vm.downloadSelected().join()
 check(vm.selectedIds==setOf("a","b")) { "Successful downloads must stay selected" }
 check(vm.isDownloaded(repo.catalog[0],repo.sourceStates.value))
 check(!vm.isDownloaded(repo.catalog[1],repo.sourceStates.value))
 check(vm.importError!=null && vm.currentDownload==null && vm.progress==null)
 repo.failures.clear()
 vm.downloadSelected().join()
 check(repo.attempts==listOf("url-a","url-b","url-b")) { "Retry must skip successful imports" }
 check(vm.importError==null && repo.catalog.all { vm.isDownloaded(it,repo.sourceStates.value) })
 println("PASS: selections survive success/failure; retry imports only unfinished items")

 repo.unavailableProviders.add("repo-b")
 vm.refresh().join()
 check(vm.warning!=null && vm.catalog.map { it.id }==listOf("a","b"))
 check(vm.selectedIds==setOf("a","b")) { "Partial outages must preserve selections" }
 repo.unavailableProviders.clear()
 vm.refresh().join()
 check(vm.warning==null)
 println("PASS: partial provider outages retain entries, order and selections")

 repo.failRefresh=true
 vm.refresh().join()
 check(vm.warning!=null && vm.catalog.size==2 && vm.selectedIds==setOf("a","b"))
 repo.failRefresh=false
 repo.catalog=listOf(item("a","v2"),item("b"))
 vm.refresh().join()
 check(vm.selectedIds==setOf("a","b") && vm.warning==null)
 check(vm.isDownloaded(repo.catalog[0],repo.sourceStates.value))
 println("PASS: failed refresh retains catalog; stable selection survives release changes")

 val attemptsBeforeExisting=repo.attempts.size
 repo.sourceStates.value=repo.sourceStates.value+("a" to
  DownloaderPluginSourceState(Entry("v3-dev.1","a"),DownloaderPluginSourceState.State.Untrusted))
 vm.downloadSelected().join()
 check(repo.attempts.size==attemptsBeforeExisting) {
  "Catalog import must not replace an existing prerelease with a stable release"
 }
 check(repo.sourceStates.value["a"]!!.entry.versionKey=="v3-dev.1")
 println("PASS: existing prereleases are preserved instead of silently replaced")

 repo.sourceStates.value=repo.sourceStates.value+("a" to
  DownloaderPluginSourceState(Entry("v3-dev.1","a"),DownloaderPluginSourceState.State.Missing))
 check(!vm.isDownloaded(repo.catalog[0],repo.sourceStates.value))
 repo.gate=CompletableDeferred()
 val job=vm.downloadSelected()
 check(vm.currentDownload!=null)
 vm.select("a",false)
 check("a" in vm.selectedIds)
 vm.downloadSelected().join()
 val count=repo.attempts.size
 repo.gate!!.complete(Unit)
 job.join()
 check(vm.currentDownload==null && vm.isDownloaded(repo.catalog[0],repo.sourceStates.value))
 check(repo.repairs==listOf("a") && repo.sourceStates.value["a"]!!.entry.versionKey=="v3-dev.1") {
  "Missing sources must be repaired through their existing release preferences"
 }
 check(repo.attempts.size==count) { "A second tap must not start another download" }
 println("PASS: ongoing imports retain their ViewModel state and reject duplicate actions")
 repo.catalog=listOf(item("a","v2"))
 vm.refresh().join()
 check(vm.catalog.map { it.id }==listOf("a") && vm.selectedIds==setOf("a"))
 println("PASS: successful refresh removes entries no longer provided")
 vm.scope.cancel()
}
"""
}
files["DownloaderCatalogViewModel.kt"] = (BASE / "ui/viewmodel/DownloaderCatalogViewModel.kt").read_text(encoding="utf-8")
files["DownloaderCatalogItem.kt"] = (BASE / "network/downloader/DownloaderCatalogItem.kt").read_text(encoding="utf-8")
files["RuntimeDownloadProgress.kt"] = (BASE / "network/runtime/RuntimeDownloadProgress.kt").read_text(encoding="utf-8")
# The platform source fixture only needs identity, while the production VM exposes the source id for APK lookup.
files["Source.kt"] = files["Source.kt"].replace("data class Entry(val versionKey:String?)",
    'data class Entry(val versionKey:String?,val id:String="source")')
with tempfile.TemporaryDirectory(prefix="downloader-catalog-", dir=ROOT / "build") as directory:
    work = Path(directory)
    sources = []
    for name, content in files.items():
        source = work / name
        source.write_text(content, encoding="utf-8")
        sources.append(source)
    deps = env["deps"] + [env["artifact"]("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm")]
    cp = env["cp"]
    classes = work / "classes"
    subprocess.run(list(map(str, [env["JAVA"], "-cp", cp(env["compiler"]),
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "17",
        "-classpath", cp(deps), "-d", classes] + sources)), check=True, timeout=120)
    subprocess.run(list(map(str, [env["JAVA"], "-cp", cp([classes] + deps), "TestKt"])),
        check=True, timeout=45)
