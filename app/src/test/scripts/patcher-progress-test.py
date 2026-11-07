"""Run manager progress tracking regressions without a runtime repository checkout."""
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[4]
CACHE = Path(os.environ.get("GRADLE_USER_HOME", ROOT / ".gradle-user-home")) / "caches/modules-2/files-2.1"
JAVA = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-17")) / "bin/java.exe"

def artifact(group, name, version="*", extension="jar"):
    matches = list((CACHE / group / name).glob(version + "/*/*." + extension))
    return max(matches, key=lambda p: p.stat().st_mtime)

def cp(files):
    return os.pathsep.join(map(str, files))

compiler = [artifact("org.jetbrains.kotlin", name, "2.4.10") for name in
    ("kotlin-compiler-embeddable", "kotlin-stdlib", "kotlin-reflect", "kotlin-script-runtime")]
compiler += [artifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.10.2"),
             artifact("org.jetbrains", "annotations", "13.0")]
(ROOT / "build").mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix="patcher-progress-", dir=ROOT / "build") as temporary:
    work = Path(temporary)
    compose = work / "compose.jar"
    with zipfile.ZipFile(artifact("androidx.compose.runtime", "runtime-android", extension="aar")) as archive:
        compose.write_bytes(archive.read("classes.jar"))
    android = next((Path(os.environ["ANDROID_HOME"]) / "platforms").glob("android-37*/android.jar"))
    dependencies = [compose, android, artifact("androidx.collection", "collection-jvm"),
                    artifact("org.jetbrains.kotlin", "kotlin-stdlib", "2.4.10"),
                    artifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.10.2"),
                    artifact("junit", "junit", "4.13.2"), artifact("org.hamcrest", "hamcrest-core")]
    sources = []
    # The JVM check uses the production models, omitting only Android parcel/resource annotations.
    for relative in ("patcher/PatcherProgress.kt", "ui/model/PatcherStep.kt"):
        source = ROOT / "app/src/main/java/app/universal/patch/manager" / relative
        models = source.read_text()
        models = re.sub(r"import (android.os.Parcelable|androidx.annotation.StringRes|app.universal.patch.manager.R|kotlinx.parcelize.Parcelize)\n", "", models)
        models = re.sub(r"enum class StepCategory\([^}]+}", "enum class StepCategory { PREPARING, PATCHING, SAVING }", models)
        models = models.replace("@Parcelize\n", "").replace(" : Parcelable", "")
        target = work / source.name
        target.write_text(models)
        sources.append(target)
    sources += [ROOT / "app/src/main/java/app/universal/patch/manager/ui/model" / name
                for name in ("PatcherProgressTracker.kt", "ExpandableStepProgress.kt")]
    sources += [ROOT / "app/src/test/java/app/universal/patch/manager/ui/model/PatcherProgressTrackerTest.kt"]
    classes = work / "classes"
    subprocess.run([str(JAVA), "-cp", cp(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", cp(dependencies),
        "-d", str(classes), *map(str, sources)], check=True, timeout=120)
    subprocess.run([str(JAVA), "-cp", cp([classes] + dependencies), "org.junit.runner.JUnitCore",
        "app.universal.patch.manager.ui.model.PatcherProgressTrackerTest"], check=True, timeout=90)
