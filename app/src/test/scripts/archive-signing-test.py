"""Exercise the production archive reader against Android's certificate-collection rules."""
import os
from pathlib import Path
import subprocess
import tempfile
import tomllib

ROOT = Path(__file__).resolve().parents[4]
JAVA = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-17")) / "bin/java.exe"
CACHE = ROOT / ".gradle-user-home/caches/modules-2/files-2.1"
KOTLIN = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text())["versions"]["kotlin"]

def artifact(group, name, version="*"):
    return next((CACHE / group / name).glob(version + "/*/*.jar"))

def cp(paths):
    return os.pathsep.join(map(str, paths))

compiler = [artifact("org.jetbrains.kotlin", name, KOTLIN) for name in
            ("kotlin-compiler-embeddable", "kotlin-stdlib", "kotlin-reflect", "kotlin-script-runtime")]
compiler += [artifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm"),
             artifact("org.jetbrains", "annotations")]
deps = [artifact("org.jetbrains.kotlin", "kotlin-stdlib", KOTLIN)]
base = ROOT / "app/src/main/java/app/universal/patch/manager"
pm = (base / "util/PM.kt").read_text()
reader = pm[pm.index("    fun getPackageInfo(file: File,"):pm.index("\n    /**", pm.index("    fun getPackageInfo(file: File,"))]
signature = pm[pm.index("    fun getSignature(packageInfo: PackageInfo)"):pm.index("\n    fun hasSplitApks")]
repository = (base / "domain/repository/PatcherRuntimePluginRepository.kt").read_text()
signers = repository[repository.index("    private fun archiveSigners("):repository.index("\n    private fun verifyArchiveTrust(")]

platform = r"""
package android.content.pm
import android.os.Build

class Signature(private val value: ByteArray) { fun toByteArray() = value }
class SigningInfo(
    val apkContentsSigners: Array<Signature>,
    val signingCertificateHistory: Array<Signature>?
) { fun hasMultipleSigners() = apkContentsSigners.size > 1 }
class ApplicationInfo { var sourceDir: String? = null; var publicSourceDir: String? = null }
class PackageInfo {
    var packageName = "app.universal.patch.manager.support.runtime"
    var applicationInfo: ApplicationInfo? = ApplicationInfo()
    var signingInfo: SigningInfo? = null
    var signatures: Array<Signature>? = null
}
class PackageManager {
    companion object {
        const val GET_ACTIVITIES = 1
        const val GET_SIGNATURES = 64
        const val GET_META_DATA = 128
        const val GET_SIGNING_CERTIFICATES = 134217728
    }
    class PackageInfoFlags private constructor(val value: Long) {
        companion object { fun of(value: Long) = PackageInfoFlags(value) }
    }
    var lastFlags = 0L
    var usedLongOverload = false
    var validSignature = true
    var current = arrayOf(Signature(byteArrayOf(0xBB.toByte())))
    var history: Array<Signature>? = arrayOf(Signature(byteArrayOf(0xAA.toByte())), current.single())

    fun getPackageArchiveInfo(path: String, flags: Int): PackageInfo? {
        usedLongOverload = false
        return read(flags.toLong())
    }
    fun getPackageArchiveInfo(path: String, flags: PackageInfoFlags): PackageInfo? {
        usedLongOverload = true
        return read(flags.value)
    }
    private fun read(flags: Long): PackageInfo? {
        lastFlags = flags
        // Android 9/10 gate collectCertificates on GET_SIGNATURES alone:
        // https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/core/java/android/content/pm/PackageManager.java
        val legacy = flags and GET_SIGNATURES.toLong() != 0L
        val modern = flags and GET_SIGNING_CERTIFICATES.toLong() != 0L
        val collect = legacy || (Build.VERSION.SDK_INT >= 30 && modern)
        if (collect && !validSignature) return null
        return PackageInfo().apply {
            if (collect && legacy) signatures = history?.take(1)?.toTypedArray() ?: current
            if (collect && modern) signingInfo = SigningInfo(current, history)
        }
    }
}
"""
harness = """
package app.universal.patch.manager.domain.repository
import android.content.pm.*
import android.os.Build
import java.io.File

object SharedApkFiles { fun resolve(file: File) = file }
object SplitApkPreparer {
    fun isSplitArchive(file: File) = false
    fun splitApkEntryNames(file: File) = emptyList<String>()
}
object SplitApkFiles {
    fun baseApk(file: File, cache: File, entries: List<String>) = file
}
object R { object string { const val runtime_signer_unreadable = 1 } }
class App {
    fun getString(id: Int, name: String) = "Failed to read signer for $name"
    val packageManager = PackageManager()
    val cacheDir = File(".")
}
class Reader {
    val app = App()
""" + reader + signature + signers + """
    internal fun checkedSigners(info: PackageInfo) = archiveSigners(info)
}
fun main() {
    val file = File("runtime.apk")
    for (sdk in listOf(26, 27, 28, 29, 30, 32, 33, 36)) {
        Build.VERSION.SDK_INT = sdk
        val reader = Reader()
        val manager = reader.app.packageManager
        val info = checkNotNull(reader.getPackageInfo(file, includeSigning = true))
        val signers = reader.checkedSigners(info)
        if (sdk >= 28) {
            check(signers.identity == "BB") { "API $sdk lost the current signer" }
            check(signers.trusts("AA")) { "API $sdk lost verified signing history" }
            check(reader.getSignature(info)!!.toByteArray().contentEquals(byteArrayOf(0xBB.toByte())))
        } else {
            check(signers.identity == "AA")
        }
        check(!signers.trusts("CC")) { "API $sdk trusted an unrelated signer" }
        check(info.applicationInfo!!.sourceDir == file.absolutePath)
        check(info.applicationInfo!!.publicSourceDir == file.absolutePath)
        check(manager.usedLongOverload == (sdk >= 33))
        val flags = manager.lastFlags
        check(flags and PackageManager.GET_META_DATA.toLong() != 0L)
        check(flags and PackageManager.GET_ACTIVITIES.toLong() != 0L)
        check((flags and PackageManager.GET_SIGNATURES.toLong() != 0L) == (sdk < 30))
        check((flags and PackageManager.GET_SIGNING_CERTIFICATES.toLong() != 0L) == (sdk >= 28))

        // Multi-signer identities must still require every signer.
        if (sdk >= 28) {
            manager.current = arrayOf(Signature(byteArrayOf(0xBB.toByte())), Signature(byteArrayOf(0xCC.toByte())))
            manager.history = null
            val multi = reader.checkedSigners(checkNotNull(reader.getPackageInfo(file, true)))
            check(multi.trusts("CC,BB") && !multi.trusts("BB") && !multi.trusts("AA"))
        }

        manager.validSignature = false
        check(reader.getPackageInfo(file, true) == null) { "API $sdk accepted an invalid signature" }
        val metadataOnly = checkNotNull(reader.getPackageInfo(file))
        check(metadataOnly.signatures == null && metadataOnly.signingInfo == null)
        check(manager.lastFlags and (PackageManager.GET_SIGNATURES or PackageManager.GET_SIGNING_CERTIFICATES).toLong() == 0L)
        check(runCatching { reader.checkedSigners(metadataOnly) }.exceptionOrNull() is SecurityException)
    }
    println("PASS: archive signing on APIs 26-36, current signer, rotation, multi-signers, invalid APKs and metadata-only reads")
}
"""
build = """
package android.os
object Build {
    object VERSION { var SDK_INT = 29 }
    object VERSION_CODES { const val P = 28; const val R = 30; const val TIRAMISU = 33 }
}
"""
(ROOT / "build").mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix="archive-signing-", dir=ROOT / "build") as temporary:
    work = Path(temporary)
    sources = []
    for name, content in (("Main.kt", harness), ("Platform.kt", platform), ("Build.kt", build)):
        target = work / name
        target.write_text(content)
        sources.append(target)
    sources.append(base / "domain/repository/RuntimePluginTrust.kt")
    classes = work / "classes"
    subprocess.run(list(map(str, [JAVA, "-cp", cp(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", cp(deps),
        "-d", classes, *sources])), check=True, timeout=90)
    subprocess.run(list(map(str, [JAVA, "-cp", cp([classes] + deps),
        "app.universal.patch.manager.domain.repository.MainKt"])), check=True, timeout=30)
