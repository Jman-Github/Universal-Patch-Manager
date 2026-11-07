"""Run app name parsing and local-label regressions; requires Android SDK 37."""
from pathlib import Path
import os
import subprocess
import shutil
import tomllib
import uuid

ROOT = Path(__file__).resolve().parents[4]
CACHE = ROOT / ".gradle-user-home/caches/modules-2/files-2.1"
JAVA = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-17")) / "bin/java.exe"
kotlin = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text())["versions"]["kotlin"]


def artifact(group, name, version="*"):
    return next((CACHE / group / name).glob(version + "/*/*.jar"))


def cp(paths):
    return os.pathsep.join(map(str, paths))


compiler = [artifact("org.jetbrains.kotlin", name, kotlin) for name in
            ("kotlin-compiler-embeddable", "kotlin-stdlib", "kotlin-reflect", "kotlin-script-runtime")]
compiler += [artifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm"), artifact("org.jetbrains", "annotations")]
deps = [artifact("org.jetbrains.kotlin", name, kotlin) for name in
        ("kotlin-stdlib", "kotlin-test", "kotlin-test-junit")]
deps += [artifact("junit", "junit", "4.13.2"), artifact("org.hamcrest", "hamcrest-core"),
         artifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm"),
         artifact("org.jetbrains.kotlinx", "kotlinx-serialization-core-jvm"),
         artifact("org.jetbrains.kotlinx", "kotlinx-serialization-json-jvm"),
         next((Path(os.environ["ANDROID_HOME"]) / "platforms").glob("android-37*/android.jar"))]
# mkdir fails promptly on Windows when the workspace is read-only; mkdtemp can
# otherwise retry hundreds of thousands of names before reporting access denied.
work = ROOT / "build" / ("app-name-tests-" + uuid.uuid4().hex)
work.mkdir()
try:
    sources = [ROOT / "app/src/main/java/app/universal/patch/manager/domain/repository/AppNameParser.kt",
               ROOT / "app/src/test/java/app/universal/patch/manager/domain/repository/AppNameParserTest.kt",
               ROOT / "app/src/main/java/app/universal/patch/manager/domain/repository/AppMetadataCache.kt",
               ROOT / "app/src/test/java/app/universal/patch/manager/domain/repository/AppMetadataCacheTest.kt",
               ROOT / "app/src/main/java/app/universal/patch/manager/domain/repository/AppMetadataRequestLocks.kt",
               ROOT / "app/src/test/java/app/universal/patch/manager/domain/repository/AppMetadataRequestLocksTest.kt",
               ROOT / "app/src/main/java/app/universal/patch/manager/util/AppDisplayLabel.kt",
               ROOT / "app/src/test/java/app/universal/patch/manager/util/AppDisplayLabelTest.kt"]
    subprocess.run([str(JAVA), "-cp", cp(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", cp(deps), "-d", work,
        *map(str, sources)], check=True, timeout=120)
    subprocess.run([str(JAVA), "-cp", cp([work] + deps), "org.junit.runner.JUnitCore",
        "app.universal.patch.manager.domain.repository.AppNameParserTest",
        "app.universal.patch.manager.domain.repository.AppMetadataCacheTest",
        "app.universal.patch.manager.domain.repository.AppMetadataRequestLocksTest",
        "app.universal.patch.manager.util.AppDisplayLabelTest"], check=True, timeout=60)
finally:
    if work.resolve().parent == (ROOT / "build").resolve():
        shutil.rmtree(work)
