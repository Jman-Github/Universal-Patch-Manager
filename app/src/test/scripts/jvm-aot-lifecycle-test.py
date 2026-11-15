"""Run the production PatcherJvm lifecycle against disposable-worker test doubles."""
from pathlib import Path
import os
import subprocess
import tempfile
import tomllib

ROOT = Path(__file__).resolve().parents[4]
CACHE = ROOT / ".gradle-user-home/caches/modules-2/files-2.1"
JAVA = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-17")) / "bin/java.exe"
KOTLIN_VERSION = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text(encoding="utf-8"))["versions"]["kotlin"]
def artifact(group, name):
    version = KOTLIN_VERSION if group == "org.jetbrains.kotlin" else "*"
    return next((CACHE / group / name).glob(version + "/*/*.jar"))
compiler = [artifact("org.jetbrains.kotlin", n) for n in
            ("kotlin-compiler-embeddable", "kotlin-stdlib", "kotlin-reflect", "kotlin-script-runtime")]
compiler += [artifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm"), artifact("org.jetbrains", "annotations")]
cp = os.pathsep.join(map(str, compiler))
fixtures = {
"Context.kt": """
package android.content
import java.io.File
open class Context {
 val applicationContext get() = this
 val filesDir get() = File(System.getProperty("test.root"))
 val cacheDir get() = File(filesDir,"cache")
 val packageName = "test"
 fun <T> getSystemService(type: Class<T>): T = type.getDeclaredConstructor().newInstance()
}
""",
"App.kt": """
package android.app
class Application : android.content.Context()
class ActivityManager {
 class MemoryInfo { var totalMem = 6144L * 1048576 }
 fun getMemoryInfo(info: MemoryInfo) {}
}
""",
"Os.kt": r"""
package android.os
import java.io.*
object Process { fun is64Bit() = true }
class RemoteException : Exception()
class ParcelFileDescriptor : Closeable {
 val fd = 1
 override fun close() {}
 class AutoCloseInputStream(fd: ParcelFileDescriptor) :
    ByteArrayInputStream("started\nfinished\n".toByteArray())
 companion object { fun createPipe() = arrayOf(ParcelFileDescriptor(),ParcelFileDescriptor()) }
}
""",
"Storage.kt": """
package app.universal.patch.manager.util
val android.content.Context.managerStorageContext get() = this
""",
"Build.kt": """
package app.universal.patch.manager
object BuildConfig { const val DEBUG = false }
""",
"Fs.kt": """
package app.universal.patch.manager.data.platform
class Filesystem { val tempDir = java.io.File(System.getProperty("test.root")) }
""",
"Prefs.kt": """
package app.universal.patch.manager.domain.manager
class PreferencesManager {
 val useJvmRuntime = Flag()
 class Flag { suspend fun get() = true }
}
""",
"Json.kt": """
package kotlinx.serialization.json
object Json { fun parseToJsonElement(value: String) = value }
""",
"Protocol.kt": """
package app.universal.patch.manager.patcher.host
object RuntimeProtocol { fun decode(value: String): Any = mapOf("type" to value) }
""",
"Install.kt": """
package app.universal.patch.manager.patcher.runtime.jvm
import java.io.File
class JvmInstallation(app: android.app.Application) {
 val isAvailable = true
 val feature = 25
 suspend fun prepareRuntime() {}
 suspend fun install(host: String, plugin: File?) = Installed()
 class Installed {
  val home = File(System.getProperty("test.root"),"runtime")
  val hostJar = File(System.getProperty("test.root"),"host.jar")
  val libjvm = File(home,"libjvm.so")
  val preload = emptyList<String>()
 }
}
data class JvmLaunch(val libjvm: String, val preload: List<String>, val options: List<String>,
 val mainClass: String, val entryClass: String)
""",
"Koin.kt": """
package org.koin.core.context
object GlobalContext { fun get() = Koin() }
class Koin { inline fun <reified T> get(): T = error("Not used") }
""",
"Worker.kt": """
package app.universal.patch.manager.patcher.runtime
import app.universal.patch.manager.patcher.runtime.jvm.JvmLaunch
import kotlinx.coroutines.*
import java.io.File
class JvmUnavailableException(message: String, cause: Throwable? = null): Exception(message,cause)
class BoundRuntimeFailureException(message: String, val originalStackTrace: String? = null): Exception(message)
object BoundArtRuntimes {
 val launches = mutableListOf<JvmLaunch>()
 var release = CompletableDeferred<Unit>()
 var failNext = false
 fun get(app: android.app.Application) = this
 suspend fun takeJvm(launch: JvmLaunch): ArtRuntime {
  launches.add(launch)
  return ArtRuntime(launch)
 }
 class ArtRuntime(val launch: JvmLaunch): java.io.Closeable {
  val pid = 42
  fun run(path: String, fd: android.os.ParcelFileDescriptor) {
   if (failNext) { failNext = false; throw android.os.RemoteException() }
   if (launch.options.contains("-XX:AOTMode=record")) {
    File(launch.options.single { it.startsWith("-XX:AOTConfiguration=") }.substringAfter("=")).writeText("config")
   }
   launch.options.firstOrNull { it.startsWith("-XX:AOTMode=create") }?.let {
    File(launch.options.single { it.startsWith("-XX:AOTCache=") }.substringAfter("=")).writeText("cache")
   }
  }
  suspend fun awaitExit() { release.await() }
  override fun close() {}
 }
}
""",
"Main.kt": """
package app.universal.patch.manager.patcher.runtime
import java.io.File
import java.util.concurrent.atomic.*
import java.util.jar.*
import kotlinx.coroutines.*
import app.universal.patch.manager.data.platform.Filesystem
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.patcher.runtime.jvm.JvmInstallation
fun main(args: Array<String>) = runBlocking {
 System.setProperty("test.root",args.single())
 val app=android.app.Application()
 JarOutputStream(File(app.filesDir,"host.jar").outputStream()).use {
  it.putNextEntry(JarEntry("app/universal/patch/manager/patcher/host/GcOverheadWatch.class"))
  it.closeEntry()
 }
 val ctor=PatcherJvm::class.java.declaredConstructors.single { it.parameterCount == 3 }.apply { isAccessible=true }
 fun manager()=ctor.newInstance(app,Filesystem(),PreferencesManager()) as PatcherJvm
 val request=File(app.filesDir,"request")
 suspend fun run(manager: PatcherJvm, callback: (String,Map<String,Any?>)->Unit = {_,_->}) =
  manager.run(RuntimeEngine.MORPHE,null,request,AtomicBoolean(),AtomicReference(null),callback)
 val m=manager()
 run(m)
 val second=async { run(m) }
 delay(100)
 check(BoundArtRuntimes.launches.size==1) { "Next patch overlapped recording/cache creation" }
 BoundArtRuntimes.release.complete(Unit)
 withTimeout(5000) { second.await() }
 check(BoundArtRuntimes.launches[1].options.contains("-XX:AOTMode=create"))
 check(BoundArtRuntimes.launches[2].options.any { it.startsWith("-XX:AOTCache=") })
 BoundArtRuntimes.launches.clear()
 BoundArtRuntimes.failNext=true
 run(manager())
 check(BoundArtRuntimes.launches.size==2)
 check(BoundArtRuntimes.launches.last().options.none { it.startsWith("-XX:AOT") })
 val installed=JvmInstallation(app).install("host",null)
 val cache=JvmAotCache(app.cacheDir.resolve("jvm-aot"),installed.home,installed.hostJar)
 cache.prepare(); cache.cache.writeText("cache")
 BoundArtRuntimes.launches.clear()
 try {
  run(manager()) { type,_ -> if(type=="started") throw JvmUnavailableException("failure after start") }
  error("Expected callback failure")
 } catch (_: JvmUnavailableException) {}
 check(BoundArtRuntimes.launches.size==1) { "A started patch was retried" }
 println("PASS: AOT serializes workers, retries early Binder failure, never retries a started patch")
}
"""
}
with tempfile.TemporaryDirectory(prefix="aot-lifecycle-", dir=ROOT / "build") as d:
    directory = Path(d)
    sources = []
    for name, code in fixtures.items():
        path = directory / name
        path.write_text(code, encoding="utf-8")
        sources.append(path)
    base = ROOT / "app/src/main/java/app/universal/patch/manager/patcher/runtime"
    sources += [base / (n + ".kt") for n in ("PatcherJvm", "JvmAotCache", "JvmHeapPolicy", "JvmTuning", "RuntimeEngine")]
    subprocess.run(list(map(str, [JAVA,"-cp",cp,"org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib","-no-reflect","-jvm-target","17","-classpath",cp,"-d",directory,*sources])),check=True,timeout=120)
    subprocess.run([str(JAVA),"-cp",str(directory)+os.pathsep+cp,
        "app.universal.patch.manager.patcher.runtime.MainKt",d],check=True,timeout=30)
