"""Run persistent app-preview and shared-APK regressions on the JVM and Android ART."""
from pathlib import Path
import importlib.util, os, subprocess, tempfile, sys, tomllib
sys.dont_write_bytecode=True
ROOT=Path(__file__).resolve().parents[4]
BASE=ROOT/"app/src/main/java/app/universal/patch/manager"
spec=importlib.util.spec_from_file_location("compat",Path(__file__).with_name("urv-import-compat-test.py"))
compat=importlib.util.module_from_spec(spec);spec.loader.exec_module(compat)
a=compat.artifact
k=tomllib.loads((ROOT/"gradle/libs.versions.toml").read_text(encoding="utf-8"))["versions"]["kotlin"]
java=Path(r"C:\Program Files\Java\jdk-17\bin\java.exe")
compiler=[a("org.jetbrains.kotlin",n,k) for n in ("kotlin-compiler-embeddable","kotlin-stdlib","kotlin-reflect","kotlin-script-runtime")]
compiler += [a("org.jetbrains.kotlinx","kotlinx-coroutines-core-jvm"),a("org.jetbrains","annotations")]
deps=[a("org.jetbrains.kotlin","kotlin-stdlib",k),a("org.jetbrains","annotations"),a("org.jetbrains.kotlinx","kotlinx-serialization-core-jvm"),a("org.jetbrains.kotlinx","kotlinx-serialization-json-jvm"),a("org.jetbrains.kotlinx","kotlinx-collections-immutable-jvm")]
sdk=Path(os.environ.get("ANDROID_HOME",r"E:\Android-Stuff\AndroidSDK"))
android=next((sdk/"platforms").glob("android-37*/android.jar"))
deps += [android,a("org.jetbrains.kotlinx","kotlinx-coroutines-core-jvm")]
cp=lambda paths:os.pathsep.join(map(str,paths))
def run(*args):subprocess.run(list(map(str,args)),check=True,timeout=120)
shared=r'''
package regression
import app.universal.patch.manager.data.platform.SharedApkFiles
import java.io.File
import java.nio.file.Files
object SharedProbe {
 @JvmStatic fun main(args: Array<String>) {
  val root=Files.createTempDirectory(File(args[0]).toPath(),"upm-sharing-").toFile()
  try {
   val downloads=root.resolve("downloads").apply { mkdirs() }
   val owners=root.resolve("repatch").apply { mkdirs() }
   val original=downloads.resolve("base.apks").apply { writeBytes(ByteArray(200000) { (it % 251).toByte() }) }
   val expected=original.readBytes()
   val saved=owners.resolve("input.apks")
   SharedApkFiles.copy(original,saved,listOf(downloads,owners))
   check(original.readBytes().contentEquals(expected)) // a running worker may still use this raw path
   SharedApkFiles.deduplicate(listOf(owners),downloads)
   check(SharedApkFiles.resolve(original)==SharedApkFiles.resolve(saved))
   check(original.delete())
   check(SharedApkFiles.resolve(saved).readBytes().contentEquals(expected)) // deleting download preserves repatching
   val replacement=downloads.resolve("base.apks").apply { writeText("newer download") }
   check(SharedApkFiles.resolve(saved).readBytes().contentEquals(expected)) // replacement never changes old version
   val another=owners.resolve("second.apks")
   SharedApkFiles.copy(saved,another,listOf(downloads,owners))
   check(SharedApkFiles.resolve(saved)==SharedApkFiles.resolve(another))
   check(saved.delete())
   check(SharedApkFiles.resolve(another).readBytes().contentEquals(expected))
   val duplicate=owners.resolve("duplicate.apk").apply { writeText("newer download") }
   val different=owners.resolve("different.apk").apply { writeText("other content!") }
   SharedApkFiles.deduplicate(listOf(owners),downloads)
   check(SharedApkFiles.resolve(duplicate)==SharedApkFiles.resolve(replacement))
   check(SharedApkFiles.resolve(different).readText()=="other content!")
   val outside=root.resolve("external.apk").apply { writeText("external bytes") }
   val copy=owners.resolve("copied.apk")
   SharedApkFiles.copy(outside,copy,listOf(downloads,owners)) // external files stay independent
   outside.writeText("changed")
   check(SharedApkFiles.resolve(copy).readText()=="external bytes")
   check(duplicate.delete())
   check(SharedApkFiles.resolve(replacement).readText()=="newer download") // deleting repatch owner preserves download
   val pool=root.resolve("app_shared-apks")
   val archive=downloads.resolve("split.apks")
   java.util.zip.ZipOutputStream(archive.outputStream()).use { zip ->
    zip.putNextEntry(java.util.zip.ZipEntry("base.apk"))
    zip.write("base APK".toByteArray())
    zip.closeEntry()
   }
   val pending=owners.resolve("pending.apks")
   SharedApkFiles.copy(archive,pending,listOf(downloads,owners))
   val originalArchive=SharedApkFiles.resolve(pending)
   val profile=root.resolve("profile.apks")
   SharedApkFiles.resolve(pending).copyTo(profile) // independent profile bytes, outside shared-owner roots
   archive.delete()
   SharedApkFiles.deduplicate(listOf(owners),downloads)
   java.util.zip.ZipFile(originalArchive).use { zip ->
    check(zip.getInputStream(zip.getEntry("base.apk")).readBytes().decodeToString()=="base APK")
   }
   check(SharedApkFiles.resolve(pending)==originalArchive) // restart has no in-memory reference table
   pending.delete()
   SharedApkFiles.deduplicate(listOf(owners),downloads)
   check(!originalArchive.exists())
   java.util.zip.ZipFile(profile).use { zip ->
    check(zip.getInputStream(zip.getEntry("base.apk")).readBytes().decodeToString()=="base APK")
   }
   val interrupted=downloads.resolve("interrupted.apk").apply { writeText("unchanged APK") }
   var attempted=false
   SharedApkFiles.deduplicate(listOf(owners),downloads) { attempted=true; false }
   check(attempted && interrupted.readText()=="unchanged APK") // refused commit cannot replace the active input
   SharedApkFiles.deduplicate(listOf(owners),downloads) { commit ->
    interrupted.delete()
    commit()
    true
   }
   check(!interrupted.exists()) // pruning during preparation must not resurrect a removed owner
   check(owners.listFiles().orEmpty().none { it.name.startsWith(".apk-ref-") })
   downloads.deleteRecursively()
   owners.deleteRecursively()
   downloads.mkdirs(); owners.mkdirs()
   SharedApkFiles.deduplicate(listOf(owners),downloads)
   check(pool.listFiles().isNullOrEmpty())
   println("Shared APK ownership, deletion, replacement, legacy duplicates, archive and garbage-collection tests passed")
  } finally { root.deleteRecursively() }
 }
}
'''
models=r'''
package app.universal.patch.manager.patcher.patch
import kotlinx.collections.immutable.*
enum class PatchBundleType { REVANCED, MORPHE, RESEAM, AMPLE }
data class CompatiblePackage(val packageName:String,val versions:ImmutableSet<String>?,
 val experimentalVersions:ImmutableSet<String>?=null,val versionCodes:ImmutableMap<String,ImmutableSet<Long>>?=null)
data class PatchInfo(val name:String,val description:String?,val include:Boolean,
 val compatiblePackages:ImmutableList<CompatiblePackage>?,val options:Any?)
object PatchBundleInfo {
 data class Global(val name:String,val version:String?,val uid:Int,val bundleType:PatchBundleType,
 val enabled:Boolean,val patches:List<PatchInfo>)
}
'''
cache=r'''
package regression
import app.universal.patch.manager.domain.repository.PatchableAppCache
import app.universal.patch.manager.patcher.patch.*
import kotlinx.collections.immutable.*
import java.nio.file.Files
object CacheProbe {
 @JvmStatic fun main(args:Array<String>) {
  val dir=Files.createTempDirectory("upm-preview-").toFile()
  try {
   check(PatchableAppCache.read(dir,1,"Bundle","v1",true)==null)
   for (type in PatchBundleType.entries) {
    val pkg=CompatiblePackage("example.app",persistentSetOf("v1","v2"),persistentSetOf("v2"),
       persistentMapOf("v1" to persistentSetOf(123L)))
    val info=PatchBundleInfo.Global("Bundle","v1",1,type,true,
       listOf(PatchInfo("Patch",null,false,persistentListOf(pkg),null),PatchInfo("Universal",null,false,null,null)))
    PatchableAppCache.write(dir,info)
    val loaded=checkNotNull(PatchableAppCache.read(dir,1,"Renamed","v2",false))
    check(loaded.patches==info.patches)
    check(loaded.name=="Renamed" && loaded.version=="v2" && !loaded.enabled)
    check(loaded.bundleType==type)
    PatchableAppCache.write(dir,info.copy(patches=emptyList()))
    check(PatchableAppCache.read(dir,1,"Bundle","v3",true)?.patches?.isEmpty()==true)
   }
   val file=dir.resolve("patchable-apps.json")
   file.writeText("corrupt")
   check(PatchableAppCache.read(dir,1,"Bundle",null,true)==null)
   file.writeText("""{"schema":999,"type":"RESEAM","patches":[]}""")
   check(PatchableAppCache.read(dir,1,"Bundle",null,true)==null)
   file.writeBytes(ByteArray(8*1024*1024+1))
   check(PatchableAppCache.read(dir,1,"Bundle",null,true)==null)
   println("App-preview restart, refresh, schema, corruption and size-bound regressions passed")
  } finally { dir.deleteRecursively() }
 }
}
'''
guard=r'''
package regression
import app.universal.patch.manager.data.platform.SharedApkFiles
import app.universal.patch.manager.domain.storage.CacheCleanupGuard
import java.nio.file.Files
object GuardProbe {
 @JvmStatic fun main(args:Array<String>) {
  val start=CacheCleanupGuard.idleGeneration.value
  val first=CacheCleanupGuard.begin()
  val second=CacheCleanupGuard.begin()
  check(CacheCleanupGuard.runIfIdle { true }==null)
  first.close();first.close()
  check(CacheCleanupGuard.isCacheInUse && CacheCleanupGuard.idleGeneration.value==start)
  second.close()
  check(!CacheCleanupGuard.isCacheInUse && CacheCleanupGuard.idleGeneration.value==start+1)
  val root=Files.createTempDirectory("upm-idle-").toFile()
  try {
   val downloads=root.resolve("downloads").apply { mkdirs() }
   val owners=root.resolve("owners").apply { mkdirs() }
   val original=downloads.resolve("base.apk").apply { writeText("old APK bytes") }
   val generation=CacheCleanupGuard.idleGeneration.value
   SharedApkFiles.deduplicate(listOf(owners),downloads) { commit ->
    // An operation starts and finishes after the old bytes were prepared.
    val user=Thread {
     CacheCleanupGuard.begin().use { original.writeText("replacement APK bytes") }
    }
    user.start();user.join(5000)
    check(!user.isAlive)
    CacheCleanupGuard.runIfIdle {
     if (CacheCleanupGuard.idleGeneration.value!=generation) false else { commit();true }
    }==true
   }
   check(original.readText()=="replacement APK bytes")
   SharedApkFiles.deduplicate(listOf(owners),downloads)
   check(SharedApkFiles.resolve(original).readText()=="replacement APK bytes")
   println("Idle migration, nested cache ownership and replacement-race regressions passed")
  } finally { root.deleteRecursively() }
 }
}
'''
with tempfile.TemporaryDirectory(prefix="storage-issues-",dir=ROOT/"build") as workdir:
 work=Path(workdir);classes=work/"classes"
 for name,code in [("SharedProbe.kt",shared),("CacheProbe.kt",cache),("Models.kt",models),("GuardProbe.kt",guard)]:
  (work/name).write_text(code,encoding="utf-8")
 instrumentation=work/"ProbeInstrumentation.kt"
 instrumentation.write_text("""
package regression
class ProbeInstrumentation : android.app.Instrumentation() {
 override fun onCreate(arguments: android.os.Bundle?) { super.onCreate(arguments); start() }
 override fun onStart() {
  val result=android.os.Bundle()
  try {
   SharedProbe.main(arrayOf(targetContext.filesDir.absolutePath))
   result.putString("stream","Android private-storage sharing tests passed")
   finish(-1,result)
  } catch (failure: Throwable) {
   result.putString("stream",failure.stackTraceToString())
   finish(0,result)
  }
 }
}
""",encoding="utf-8")
 sources=[instrumentation,work/"SharedProbe.kt",work/"CacheProbe.kt",work/"Models.kt",BASE/"data/platform/SharedApkFiles.kt",BASE/"domain/repository/PatchableAppCache.kt",BASE/"domain/storage/CacheCleanupGuard.kt",work/"GuardProbe.kt"]
 run(java,"-cp",cp(compiler),"org.jetbrains.kotlin.cli.jvm.K2JVMCompiler","-no-stdlib","-no-reflect","-jvm-target","17","-classpath",cp(deps),"-Xplugin="+str(a("org.jetbrains.kotlin","kotlin-serialization-compiler-plugin-embeddable",k)),"-d",classes,*sources)
 run(java,"-cp",cp([classes]+deps),"regression.SharedProbe",work)
 run(java,"-cp",cp([classes]+deps),"regression.CacheProbe")
 run(java,"-cp",cp([classes]+deps),"regression.GuardProbe")
 # Run the real reference storage and garbage collection implementation on Android.
 import zipfile,uuid
 probe=work/"probe.jar"
 with zipfile.ZipFile(probe,"w") as archive:
  for file in classes.rglob("*.class"):
   rel=file.relative_to(classes).as_posix()
   if "Shared" in rel or "ProbeInstrumentation" in rel:archive.write(file,rel)
 sdk=Path(os.environ.get("ANDROID_HOME",r"E:\Android-Stuff\AndroidSDK"))
 android=next((sdk/"platforms").glob("android-37*/android.jar"))
 dex=work/"tests.zip"
 run(java,"-cp",a("com.android.tools","r8"),"com.android.tools.r8.D8","--min-api","26","--lib",android,"--output",dex,probe,deps[0])
 adb=Path(r"D:\Android-Stuff\Android ADB\platform-tools\adb.exe")
 buildtools=sorted((sdk/"build-tools").iterdir())[-1]
 manifest=work/"AndroidManifest.xml"
 manifest.write_text("""<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="app.universal.patch.manager.storageprobe">
 <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36"/>
 <application android:label="UPM storage test" android:debuggable="true"/>
 <instrumentation android:name="regression.ProbeInstrumentation" android:targetPackage="app.universal.patch.manager.storageprobe"/>
 </manifest>""",encoding="utf-8")
 apk=work/"probe.apk"
 run(buildtools/"aapt2.exe","link","-I",android,"--manifest",manifest,"-o",apk)
 with zipfile.ZipFile(apk,"a") as out,zipfile.ZipFile(dex) as compiled:
  for name in compiled.namelist():out.writestr(name,compiled.read(name))
 key=work/"probe.jks"
 run(java.with_name("keytool.exe"),"-genkeypair","-keystore",key,"-storepass","testpass",
     "-keypass","testpass","-alias","probe","-dname","CN=UPM Storage Test","-keyalg","RSA","-validity","1")
 run(buildtools/"apksigner.bat","sign","--ks",key,"--ks-pass","pass:testpass","--key-pass","pass:testpass",apk)
 package="app.universal.patch.manager.storageprobe"
 try:
  run(adb,"-s","emulator-5554","install","-r",apk)
  result=subprocess.check_output(list(map(str,[adb,"-s","emulator-5554","shell","am","instrument","-w",package+"/regression.ProbeInstrumentation"])),text=True,timeout=120)
  print(result)
  assert "Android private-storage sharing tests passed" in result
 finally:run(adb,"-s","emulator-5554","uninstall",package)
repo=(BASE/"domain/repository/PatchBundleRepository.kt").read_text(encoding="utf-8")
assert "(if (state.metadataLoaded) state.info else cached)" in repo
assert "Store(scope, State(metadataLoaded = false))" in repo
assert "val allBundlesInfoFlow = store.state.map { it.info }" in repo
print("Preview cache is isolated from execution metadata; removed/disabled bundles use live state.")

profile=(BASE/"ui/viewmodel/PatchProfilesViewModel.kt").read_text(encoding="utf-8")
assert "val source = SharedApkFiles.resolve(file)" in profile
assert "Files.copy(source.toPath(), destination.toPath()" in profile
assert "CacheCleanupGuard.withCacheInUse" in profile
worker=(BASE/"patcher/worker/PatcherWorker.kt").read_text(encoding="utf-8")
assert "val inputFile = SharedApkFiles.resolve(downloadResult.file)" in worker
print("Profile copies and prepared patch inputs resolve shared references.")
