"""Exercise real Morphe and ReVanced SDK extension mergers without Gradle tasks.

Confirms each pinned SDK loses incoming members before the compatibility fix.
Checks JAR and Android AAR packaging, fields/methods, overloads, existing values
and repeated merging. Run before the final assembleRelease validation.
"""
import io
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[4]
CACHE = Path(os.environ.get("GRADLE_USER_HOME", ROOT / ".gradle-user-home")) / "caches/modules-2/files-2.1"
JAVA_BIN = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-17")) / "bin"
JAVA = JAVA_BIN / "java.exe"
JAVAC = JAVA_BIN / "javac.exe"


def artifact(group, name, version, extension="jar"):
    if group == "app.morphe" and name == "morphe-patcher" and os.environ.get("MORPHE_PATCHER_JAR"):
        return Path(os.environ["MORPHE_PATCHER_JAR"])
    matches = list((CACHE / group / name / version).glob(f"*/*.{extension}"))
    if not matches:
        raise RuntimeError(f"Missing cached artifact: {group}:{name}:{version}")
    return matches[0]


def check_changed(before, after, expected):
    assert before.namelist() == after.namelist(), "Changed archive entries"
    changed = [entry for entry in before.namelist() if before.read(entry) != after.read(entry)]
    assert changed == [expected], changed


compiler = [artifact("org.jetbrains.kotlin", name, "2.4.10") for name in (
    "kotlin-compiler-embeddable", "kotlin-stdlib", "kotlin-reflect", "kotlin-script-runtime")]
compiler += [artifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.10.2"),
             artifact("org.jetbrains", "annotations", "13.0")]
asm = [artifact("org.ow2.asm", name, "9.9.1") for name in ("asm", "asm-tree")]
classpath = lambda paths: os.pathsep.join(map(str, paths))

with tempfile.TemporaryDirectory(prefix="extension-member-merge-", dir=ROOT / "build") as temp:
    work = Path(temp)
    fix = work / "fix"
    subprocess.run([str(JAVA), "-cp", classpath(compiler),
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
        "-jvm-target", "17", "-classpath", classpath(compiler + asm), "-d", str(fix),
        str(ROOT / "buildSrc/src/main/kotlin/ExtensionClassMergerFix.kt")], check=True)
    runner = work / "ApplyFix.java"
    runner.write_text("""public class ApplyFix {
        public static void main(String[] args) throws Exception {
            var input = new java.io.File(args[0]);
            var output = new java.io.File(args[1]);
            if (input.getName().endsWith(".aar")) ExtensionClassMergerFix.patchAar(input, output);
            else ExtensionClassMergerFix.patchJar(input, output);
        }
    }""")
    fix_cp = classpath([fix] + compiler + asm)
    subprocess.run([str(JAVAC), "--release", "17", "-cp", fix_cp, "-d", str(fix), str(runner)], check=True)
    template = (ROOT / "app/src/test/fixtures/class-merger/ExtensionMembers.java").read_text()
    for module, group, name, version, patcher, extension in (
        ("upm-patcher-core", "app.morphe", "morphe-patcher", "2.1.0", "app.morphe.patcher", "jar"),
        ("revanced21-patcher-core", "app.revanced", "revanced-patcher", "21.1.0-dev.5", "app.revanced.patcher", "jar"),
        ("revanced22-patcher-core", "app.revanced", "patcher-jvm", "22.0.2-dev.1", "app.revanced.patcher", "jar"),
        ("revanced22-patcher-core", "app.revanced", "patcher-android", "22.0.2-dev.1", "app.revanced.patcher", "aar"),
        ("revanced-legacy-patcher-core", "app.revanced", "revanced-patcher", "19.3.1", "app.revanced.patcher", "jar"),
    ):
        directory = work / (module + "-" + name)
        directory.mkdir()
        core = ROOT / module / "build/libs" / (module + ".jar")
        assert core.is_file(), "Missing previously assembled host: " + str(core)
        original = artifact(group, name, version, extension)
        corrected = directory / original.name
        subprocess.run([str(JAVA), "-cp", fix_cp, "ApplyFix", str(original), str(corrected)], check=True)
        twice = directory / ("twice." + extension)
        subprocess.run([str(JAVA), "-cp", fix_cp, "ApplyFix", str(corrected), str(twice)], check=True)
        merger = patcher.replace(".", "/") + "/util/ClassMerger.class"
        with zipfile.ZipFile(original) as before, zipfile.ZipFile(corrected) as after, zipfile.ZipFile(twice) as again:
            expected = "classes.jar" if extension == "aar" else merger
            check_changed(before, after, expected)
            assert all(after.read(entry) == again.read(entry) for entry in after.namelist()), "Fix is not idempotent"
            if extension == "aar":
                original_classes, corrected_classes = before.read("classes.jar"), after.read("classes.jar")
                with zipfile.ZipFile(io.BytesIO(original_classes)) as first, zipfile.ZipFile(io.BytesIO(corrected_classes)) as last:
                    check_changed(first, last, merger)
                original = directory / "original-classes.jar"
                corrected = directory / "corrected-classes.jar"
                original.write_bytes(original_classes)
                corrected.write_bytes(corrected_classes)
        source = directory / "ExtensionMembers.java"
        # These pinned SDKs all use the same public dexlib namespace.
        source.write_text(template.replace("DEX_PACKAGE", "com.android.tools.smali.dexlib2")
            .replace("PATCHER_PACKAGE", patcher))
        cp = classpath([original, core])
        subprocess.run([str(JAVAC), "--release", "17", "-cp", cp, "-d", str(directory), str(source)], check=True)
        for case, expected in (("fields", "Missing incoming static PAGE:I"),
                               ("methods", "Missing incoming isPatchIncluded()Z")):
            result = subprocess.run([str(JAVA), "-cp", classpath([directory, original, core]),
                "ExtensionMembers", case], capture_output=True, text=True)
            assert result.returncode != 0 and expected in result.stderr, result.stdout + result.stderr
        print(f"Reproduced missing field and method: {group}:{name}:{version}", flush=True)
        subprocess.run([str(JAVA), "-cp", classpath([directory, corrected, core]),
            "ExtensionMembers"], check=True)
