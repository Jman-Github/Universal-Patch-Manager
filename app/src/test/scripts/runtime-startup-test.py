"""Run startup cache, plugin upgrade and hybrid JVM policy tests before assembleRelease."""
from pathlib import Path
import os
import re
import subprocess
import tempfile
import tomllib

ROOT = Path(__file__).resolve().parents[4]
CACHE = ROOT / ".gradle-user-home/caches/modules-2/files-2.1"
JAVA = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-17")) / "bin/java.exe"
def artifact(group, name, version="*"):
    found = list((CACHE / group / name).glob(version + "/*/*.jar"))
    return max(found, key=lambda p: tuple(map(int, re.findall(r"\d+", p.parent.parent.name))))
def cp(paths):
    return os.pathsep.join(map(str, paths))
version = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text())["versions"]["kotlin"]
compiler = [artifact("org.jetbrains.kotlin", name, version) for name in
            ("kotlin-compiler-embeddable", "kotlin-stdlib", "kotlin-reflect", "kotlin-script-runtime")]
compiler += [artifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm"), artifact("org.jetbrains", "annotations")]
deps = [artifact("org.jetbrains.kotlin", name, version) for name in
        ("kotlin-stdlib", "kotlin-test", "kotlin-test-junit")]
deps += [artifact("junit", "junit", "4.13.2"), artifact("org.hamcrest", "hamcrest-core"),
         artifact("org.jetbrains.kotlinx", "kotlinx-serialization-core-jvm")]
names = ["patcher/runtime/JvmTuning", "patcher/runtime/RuntimePreparationCache", "patcher/runtime/ArtHeapPolicy",
         "domain/repository/RuntimePluginCompatibility", "patcher/PatcherSessionInfo"]
with tempfile.TemporaryDirectory(prefix="runtime-startup-", dir=ROOT / "build") as directory:
    main = ROOT / "app/src/main/java/app/universal/patch/manager"
    tests = ROOT / "app/src/test/java/app/universal/patch/manager"
    sources = [main / (name + ".kt") for name in names] + [tests / (name + "Test.kt") for name in names]
    subprocess.run(list(map(str, [JAVA, "-cp", cp(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", cp(deps), "-d", directory, *sources])),
        check=True, timeout=120)
    subprocess.run(list(map(str, [JAVA, "-cp", cp([Path(directory)] + deps), "org.junit.runner.JUnitCore",
        *["app.universal.patch.manager." + name.replace("/", ".") + "Test" for name in names]])),
        check=True, timeout=60)
