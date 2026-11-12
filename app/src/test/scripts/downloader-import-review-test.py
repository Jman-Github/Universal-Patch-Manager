"""Host checks for downloader import failure handling, catalog resilience and APK replacement."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[4]
BASE = ROOT / "app/src/main/java/app/universal/patch/manager"
helper = ROOT / "app/src/test/scripts/bundle-ui-issues-test.py"
env = {"__file__": str(helper)}
exec(helper.read_text(encoding="utf-8").split('(ROOT / "build").mkdir', 1)[0], env)
repo = (BASE / "domain/repository/DownloaderPluginRepository.kt").read_text(encoding="utf-8")
file_method = "    suspend fun importSourceFile(" + repo.split("    suspend fun importSourceFile(", 1)[1].split("    suspend fun catalogAssets(", 1)[0]
catalog_method = "    suspend fun catalogAssets(" + repo.split("    suspend fun catalogAssets(", 1)[1].split("    fun catalogSource(", 1)[0]
asset_guard = "                val asset = release.assets" + repo.split("                val asset = release.assets", 1)[1].split("                val key = releaseVersionKey", 1)[0]
update_method = "    private suspend fun updateEntry(" + repo.split("    private suspend fun updateEntry(", 1)[1].split("    private suspend fun shouldAutoUpdate(", 1)[0]
vm = (BASE / "ui/viewmodel/DownloadsViewModel.kt").read_text(encoding="utf-8")
import_method = "    suspend fun importPluginSourceResult(" + vm.split("    suspend fun importPluginSourceResult(", 1)[1].split("    fun updatePluginSource", 1)[0]
fixture = r'''
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*
data class Uri(val file:File)
class Resolver { fun openInputStream(uri:Uri)=uri.file.inputStream() }
class App(val cacheDir:File) {
    val contentResolver=Resolver()
    val messages=mutableListOf<String>()
    fun getString(id:Int,vararg args:Any?)="Import failed"
    fun toast(message:String) { messages.add(message) }
}
object R { object string { const val downloader_replace_fail=1 } }
fun Throwable.simpleMessage()=message
data class DownloaderPluginSourceEntry(
    val id:String,val repoUrl:String,val assetSelector:String,val autoUpdate:Boolean,
    val trustedSignatureHex:String?=null,
    val latest:Boolean=false, val prerelease:Boolean=false, val versionKey:String?=null
)
data class DownloaderCatalogItem(
    val id:String,val name:String,val version:String,val repoUrl:String,
    val assetSelector:String,val downloadUrl:String,val versionKey:String,val size:Long?
)
data class Info(val packageName:String)
data class Asset(val name:String,val downloadUrl:String,val size:Long?=null)
data class Release(val assets:List<Asset>,val tagName:String="v1")
typealias GitHubRelease=Release
typealias RuntimeDownloadProgress=Unit
object Log { fun w(tag:String,message:String,error:Throwable) {} }
class Repository(val app:App,val root:File) {
    val tag="fixture"
    val updateCheckMutex=Mutex()
    var entries=emptyList<DownloaderPluginSourceEntry>()
    var cancelAfterInspect:Job?=null
    var latestFetches=0
    var requestedPrerelease:Boolean?=null
    var latest=Release(emptyList())
    var downloaded:Asset?=null
    suspend fun latestReleaseFor(repoUrl:String,prerelease:Boolean?):Release {
        latestFetches++
        requestedPrerelease=prerelease
        return latest
    }
    fun canonicalAssetSelector(value:String)=value
    fun releaseVersionKey(release:Release,asset:Asset)=release.tagName+":"+asset.downloadUrl
    suspend fun downloadAssetToEntry(entry:DownloaderPluginSourceEntry,asset:Asset,onProgress:((RuntimeDownloadProgress)->Unit)?=null) {
        downloaded=asset
    }
    suspend fun updateForTest(entry:DownloaderPluginSourceEntry,selected:Release?=null) =
        updateEntry(entry, force=false, selectedRelease=selected)
    var failWrite=false
    var failure:Throwable?=null
    var releases=mutableMapOf<String,Release>()
    fun readArchivePackageInfo(file:File):Info {
        val info=Info(file.readText().substringBefore(':'))
        cancelAfterInspect?.cancel()
        return info
    }
    fun sourceFile(entry:DownloaderPluginSourceEntry)=root.resolve(entry.id).resolve("plugin.apk")
    suspend fun readSourceEntries()=entries
    suspend fun writeSourceEntries(value:List<DownloaderPluginSourceEntry>) {
        if (failWrite) throw IOException("Persistence failed")
        entries=value
    }
    suspend fun reload() {}
    suspend fun importSourcesFromUrl(url:String) { failure?.let { throw it } }
    suspend fun latestImportReleaseFor(repo:String):Release {
        failure?.let { throw it }
        return releases[repo] ?: throw IOException("Provider unavailable")
    }
    fun isSupportedDownloaderAsset(asset:Asset)=asset.name.endsWith(".apk")
    fun normalizeAssetSelector(value:String)=value
    fun String.toReadableSourceName()=this
    fun String.toDownloaderMainName()=this
''' + file_method + catalog_method + update_method + r'''
    val pending=linkedMapOf<String,String>()
    fun hasAsset(entry:DownloaderPluginSourceEntry,release:Release):Boolean {
        for (entry in listOf(entry)) {
''' + asset_guard + r'''
            return true
        }
        return false
    }

}
class Model(val appContext:App,val downloaderPluginRepository:Repository) {
    enum class RemoteSourceBusyState { Importing }
    var remoteSourceBusyState:RemoteSourceBusyState?=null
''' + import_method + r'''
}
fun main()=runBlocking {
    val root=java.nio.file.Files.createTempDirectory("downloader-review-").toFile()
    try {
        val app=App(root.resolve("cache").apply { mkdirs() })
        val repository=Repository(app,root.resolve("managed"))
        val uri=Uri(root.resolve("source.apk").apply { writeText("plugin.example:old") })
        repository.importSourceFile(uri)
        check(repository.entries.size==1)
        val original=repository.entries.single().copy(trustedSignatureHex="trusted")
        repository.entries=listOf(original)
        val target=repository.sourceFile(original)
        // Windows read-only attributes prevent rename, unlike Android POSIX permissions.
        if (java.nio.file.Files.getFileStore(target.toPath()).supportsFileAttributeView("posix")) {
            check(target.setReadOnly())
        }
        uri.file.writeText("plugin.example:new")
        repository.importSourceFile(uri)
        check(repository.entries==listOf(original)) { "Reimport must preserve identity and trust" }
        check(target.readText()=="plugin.example:new")
        repository.failWrite=true
        uri.file.writeText("plugin.example:rejected")
        check(runCatching { repository.importSourceFile(uri) }.isFailure)
        check(target.readText()=="plugin.example:new") { "Failed persistence must restore the previous APK" }
        check(repository.entries==listOf(original))
        check(app.cacheDir.listFiles().orEmpty().isEmpty()) { "Temporary files must be released" }
        repository.failWrite=false
        val inspectionJob=Job()
        repository.cancelAfterInspect=inspectionJob
        check(runCatching {
            withContext(inspectionJob) { repository.importSourceFile(uri) }
        }.exceptionOrNull() is CancellationException)
        repository.cancelAfterInspect=null
        check(repository.entries==listOf(original) && target.readText()=="plugin.example:new")
        check(app.cacheDir.listFiles().orEmpty().isEmpty()) { "Cancellation must not leak staging or backup files" }
        println("PASS: local APK reimport replaces APKs, preserves identity/trust and rolls back failure")

        repository.releases["https://github.com/rushiranpise/helper-for-morphe"] =
            Release(listOf(Asset("helper.apk","asset-url")))
        val failedProviders=mutableListOf<String>()
        val first=repository.catalogAssets { failedProviders.add(it) }.single()
        check(failedProviders==listOf("https://github.com/brosssh/revanced-manager-downloaders"))
        check(first.downloadUrl=="asset-url" && first.size==null)
        repository.releases["https://github.com/rushiranpise/helper-for-morphe"] =
            Release(listOf(Asset("helper.apk","new-asset-url",2048)), "v2")
        val second=repository.catalogAssets().single()
        check(second.id==first.id && second.version=="v2" && second.size==2048L)
        check(second.versionKey!=first.versionKey)
        repository.releases.clear()
        check(runCatching { repository.catalogAssets() }.isFailure)
        repository.failure=CancellationException("Cancelled")
        check(runCatching { repository.catalogAssets() }.exceptionOrNull() is CancellationException)
        println("PASS: catalog tolerates one unavailable provider, reports total failure and propagates cancellation")

        val selected=Release(listOf(Asset("plugin.apk","checked-asset")), "v1")
        repository.latest=Release(listOf(Asset("plugin.apk","newer-asset")), "v2")
        val updateSource=original.copy(assetSelector="plugin.apk", repoUrl="https://example.test/repo")
        val updated=repository.updateForTest(updateSource, selected)
        check(repository.latestFetches==0 && repository.downloaded?.downloadUrl=="checked-asset")
        check(updated.versionKey=="v1:checked-asset")
        repository.pending[updateSource.id]="old update"
        check(!repository.hasAsset(updateSource, Release(emptyList())))
        check(repository.pending.isEmpty()) { "Removed APKs must clear stale update notices" }
        check(repository.hasAsset(updateSource, selected))
        val manual=repository.updateForTest(updateSource.copy(prerelease=true))
        check(repository.latestFetches==1 && repository.downloaded?.downloadUrl=="newer-asset")
        check(manual.versionKey=="v2:newer-asset")
        check(repository.requestedPrerelease==true && manual.prerelease) {
            "Updating or repairing an existing source must retain its release channel"
        }
        println("PASS: checked updates reuse their release snapshot; manual updates fetch once")

        val model=Model(app,repository)
        repository.failure=IOException("Download failed")
        check(!model.importPluginSourceResult("source"))
        check(model.remoteSourceBusyState==null && app.messages.size==1)
        repository.failure=CancellationException("Cancelled")
        check(runCatching { model.importPluginSourceResult("source") }.exceptionOrNull() is CancellationException)
        check(model.remoteSourceBusyState==null && app.messages.size==1)
        repository.failure=null
        check(model.importPluginSourceResult("source"))
        check(model.remoteSourceBusyState==null)
        println("PASS: import returns success/failure, resets busy state and never swallows cancellation")
    } finally {
        root.walkTopDown().forEach { it.setWritable(true) }
        root.deleteRecursively()
    }
}
'''
with tempfile.TemporaryDirectory(prefix="downloader-review-", dir=ROOT / "build") as directory:
    work = Path(directory)
    source = work / "Test.kt"
    source.write_text(fixture, encoding="utf-8")
    classes = work / "classes"
    deps = env["deps"] + [env["artifact"]("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm")]
    cp = env["cp"]
    subprocess.run(list(map(str, [env["JAVA"], "-cp", cp(env["compiler"]),
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "17",
        "-classpath", cp(deps), "-d", classes, source])), check=True, timeout=120)
    subprocess.run(list(map(str, [env["JAVA"], "-cp", cp([classes] + deps), "TestKt"])),
                   check=True, timeout=45)
