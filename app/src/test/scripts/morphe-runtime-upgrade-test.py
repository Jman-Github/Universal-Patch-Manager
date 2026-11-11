"""Exercise the production Morphe bridge across runtime upgrades without restarting UPM."""
import os
from pathlib import Path
import subprocess
import tempfile
import tomllib

ROOT = Path(__file__).resolve().parents[4]
CACHE = ROOT / ".gradle-user-home/caches/modules-2/files-2.1"
JAVA = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-17")) / "bin/java.exe"
def artifact(group, name, version="*"):
    return next((CACHE / group / name).glob(version + "/*/*.jar"))
def cp(paths):
    return os.pathsep.join(map(str, paths))
version = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text())["versions"]["kotlin"]
compiler = [artifact("org.jetbrains.kotlin", name, version) for name in
            ("kotlin-compiler-embeddable", "kotlin-stdlib", "kotlin-reflect", "kotlin-script-runtime")]
coroutines = artifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm")
compiler += [coroutines, artifact("org.jetbrains", "annotations")]
deps = [artifact("org.jetbrains.kotlin", "kotlin-stdlib", version), coroutines]
def compile_sources(sources, output):
    subprocess.run(list(map(str, [JAVA, "-cp", cp(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", cp(deps),
        "-d", output, *sources])), check=True, timeout=120)

stubs = {
"Context.kt": """
package android.content
import java.io.File
open class Context {
    val applicationContext get() = this
    val codeCacheDir = File(System.getProperty("java.io.tmpdir"))
    val classLoader get() = javaClass.classLoader
}
""",
"Storage.kt": """
package app.universal.patch.manager.util
val android.content.Context.managerStorageContext get() = this
""",
"Dex.kt": """
package dalvik.system
import java.io.File
class DexClassLoader(path: String, optimized: String, native: String?, parent: ClassLoader) :
    java.net.URLClassLoader(arrayOf(File(path).toURI().toURL()), ClassLoader.getSystemClassLoader())
""",
"Assets.kt": """
package app.universal.patch.manager.patcher.runtime.morphe
import java.io.File
object MorpheRuntimeAssets {
    var selected: File? = null
    fun ensureRuntimeClassPath(context: android.content.Context): File =
        selected?.takeIf { it.isFile } ?: error("Runtime removed or untrusted")
}
""",
"Info.kt": """
package app.universal.patch.manager.patcher.patch
data class PatchInfo(val name: String) {
    companion object { fun fromMorpheMetadata(map: Map<String, Any?>) = PatchInfo(map["name"] as String) }
}
""",
"Progress.kt": """
package app.universal.patch.manager.patcher
class ProgressEvent
class RemoteError
class StepId
""",
"Logger.kt": """
package app.universal.patch.manager.patcher.logger
enum class LogLevel { INFO }
class Logger { fun log(level: LogLevel, message: String) {} }
""",
"Main.kt": """
import app.universal.patch.manager.patcher.morphe.MorpheRuntimeBridge as Bridge
import app.universal.patch.manager.patcher.runtime.morphe.MorpheRuntimeAssets as Assets
import app.universal.patch.manager.patcher.logger.Logger
import java.io.File
fun main(args: Array<String>) {
    Bridge.initialize(android.content.Context())
    fun verify(expected: String) {
        check(Bridge.loadMetadata("bundle").single().name == expected)
        check(Bridge.loadMetadata(listOf("bundle")).getValue("bundle").single().name == expected)
        check(Bridge.runPatcher(emptyMap(), Logger(), {}) == expected)
    }
    val first = File(args[0])
    val second = File(args[1])
    Assets.selected = first
    verify("old")
    Assets.selected = second
    verify("new")
    Assets.selected = null
    check(runCatching { Bridge.loadMetadata("bundle") }.isFailure)
    check(runCatching { Bridge.loadMetadata(listOf("bundle")) }.isFailure)
    check(runCatching { Bridge.runPatcher(emptyMap(), Logger(), {}) }.isFailure)
    Assets.selected = first
    verify("old")
    first.setLastModified(first.lastModified() + 10000)
    verify("old")
    println("PASS: Morphe single/batch metadata and patch execution refresh on upgrade, reject removal, and recover")
}
"""
}
runtime = """
package app.universal.patch.manager.morphe.runtime
interface MorpheRuntimeCallback { fun isCancelled(): Boolean }
object MorpheRuntimeEntry {
    @JvmStatic fun loadMetadataForBundle(path: String) = listOf(mapOf("name" to "VERSION"))
    @JvmStatic fun loadMetadata(paths: List<String>) = paths.associateWith { loadMetadataForBundle(it) }
    @JvmStatic fun runPatcher(params: Map<String, Any?>, callback: MorpheRuntimeCallback): String {
        check(!callback.isCancelled())
        return "VERSION"
    }
}
"""
(ROOT / "build").mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix="morphe-upgrade-", dir=ROOT / "build") as temporary:
    work = Path(temporary)
    jars = []
    for label in ("old", "new"):
        source = work / (label + ".kt")
        source.write_text(runtime.replace("VERSION", label))
        jar = work / (label + ".jar")
        compile_sources([source], jar)
        jars.append(jar)
    bridge = (ROOT / "app/src/main/java/app/universal/patch/manager/patcher/morphe/MorpheRuntimeBridge.kt").read_text()
    # Progress conversion is unrelated to loader lifecycle. Stub only that mapping to keep the harness small.
    bridge = bridge[:bridge.index("    internal fun mapToProgressEvent")] + """
    internal fun mapToProgressEvent(map: Map<*, *>): ProgressEvent = ProgressEvent()
}
"""
    stubs["Bridge.kt"] = bridge
    sources = []
    for name, source in stubs.items():
        path = work / name
        path.write_text(source)
        sources.append(path)
    classes = work / "classes"
    compile_sources(sources, classes)
    subprocess.run(list(map(str, [JAVA, "-cp", cp([classes] + deps), "MainKt", *jars])),
                   check=True, timeout=30)
