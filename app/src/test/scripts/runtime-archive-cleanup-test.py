"""Run actual archive cleanup and process-lifetime runtime retention against temporary files."""
from pathlib import Path
import subprocess
import tempfile
ROOT = Path(__file__).resolve().parents[4]
namespace = {"__file__": str(Path(__file__).with_name("bundle-ui-issues-test.py"))}
exec(Path(namespace["__file__"]).read_text().split('(ROOT / "build").mkdir')[0], namespace)
compiler, deps, JAVA, cp = (namespace[k] for k in ("compiler", "deps", "JAVA", "cp"))
base = ROOT / "app/src/main/java/app/universal/patch/manager"
harness = """
package app.universal.patch.manager.domain.repository
import java.io.File
import app.universal.patch.manager.network.runtime.*
import app.universal.patch.manager.patcher.runtime.PatcherRuntimePluginRegistry

fun main(args: Array<String>) {
    val root = File(args[0]).apply { mkdirs() }
    val directory = root.resolve("catalog-morphe").apply { mkdirs() }
    val oldName = "a".repeat(64)
    val newName = "b".repeat(64)
    val staleName = "c".repeat(64)
    fun apk(name: String) = directory.resolve("$name.apk").apply { writeText(name) }
    val old = apk(oldName)
    val current = apk(newName)
    val stale = apk(staleName)
    val unknown = apk("unrecognized")
    val part = directory.resolve("interrupted.part").apply { writeText("resume") }
    val oat = directory.resolve("oat/arm64").apply { mkdirs() }
    for (name in listOf(oldName, newName, staleName, "d".repeat(64))) {
        for (extension in listOf("odex", "vdex", "art")) oat.resolve("$name.$extension").writeText("compiled")
    }
    val unrelatedArtifact = oat.resolve("unrelated.odex").apply { writeText("unrelated") }
    var selected = LoadedPatcherRuntimePlugin(old)
    PatcherRuntimePluginRegistry.install { mapOf(PatcherRuntimeKind.MORPHE to selected) }
    check(PatcherRuntimePluginRegistry.runtimeFor(PatcherRuntimeKind.MORPHE)!!.apkFile == old)
    selected = LoadedPatcherRuntimePlugin(current)
    PatcherRuntimePluginRegistry.withRetainedFiles { pruneRuntimeArchives(directory, current, it) }
    check(old.exists() && current.exists() && !stale.exists())
    check(unknown.exists() && part.exists() && unrelatedArtifact.exists())
    check(oat.resolve("$oldName.odex").exists() && oat.resolve("$newName.vdex").exists())
    check(!oat.resolve("$staleName.odex").exists() && !oat.resolve("d".repeat(64) + ".vdex").exists())

    // Startup has no retained references from the previous manager process.
    pruneRuntimeArchives(directory, current, emptySet())
    check(!old.exists() && current.exists() && !oat.resolve("$oldName.art").exists())
    check(oat.resolve("$newName.art").exists())

    val recovery = apk(staleName)
    pruneRuntimeArchives(directory, directory.resolve("missing.apk"), emptySet())
    check(recovery.exists()) { "Deleted recovery archive when active archive was missing" }
    pruneRuntimeArchives(directory, root.resolve("outside.apk"), emptySet())
    check(recovery.exists())

    val orphan = root.resolve("retired-source").apply { mkdirs() }
    val pinned = orphan.resolve("patcher-runtime.apk").apply { writeText("running") }
    val abandoned = root.resolve("abandoned-source").apply { mkdirs(); resolve("old.apk").writeText("old") }
    val downloads = root.resolve(".downloads").apply { mkdirs(); resolve("resume.part").writeText("resume") }
    pruneOrphanRuntimeDirectories(root, setOf(directory.name), setOf(pinned))
    check(orphan.exists() && !abandoned.exists() && downloads.exists() && directory.exists())
    pruneOrphanRuntimeDirectories(root, setOf(directory.name), emptySet())
    check(!orphan.exists() && current.exists() && downloads.exists())
    println("PASS: stale APK/ART cleanup, selected runtimes retained, next-start reclamation, recovery protection, orphan folders and resume files")
}
"""
with tempfile.TemporaryDirectory(prefix="runtime-archive-cleanup-", dir=ROOT / "build") as temporary:
    work = Path(temporary)
    (work / "Main.kt").write_text(harness, encoding="utf-8")
    (work / "Models.kt").write_text("package app.universal.patch.manager.network.runtime\nimport java.io.File\nenum class PatcherRuntimeKind { MORPHE }\ndata class LoadedPatcherRuntimePlugin(val apkFile: File)\n")
    classes = work / "classes"
    sources = [work / "Main.kt", work / "Models.kt", base / "domain/repository/RuntimeArchiveCleanup.kt",
               base / "patcher/runtime/PatcherRuntimePluginRegistry.kt"]
    subprocess.run(list(map(str, [JAVA, "-cp", cp(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", cp(deps),
        "-d", classes, *sources])), check=True, timeout=90)
    subprocess.run(list(map(str, [JAVA, "-cp", cp([classes] + deps),
        "app.universal.patch.manager.domain.repository.MainKt", work / "files"])), check=True, timeout=30)
