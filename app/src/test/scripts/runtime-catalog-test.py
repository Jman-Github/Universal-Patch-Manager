"""Compile and run pure runtime catalog/setup JUnit tests before assembleRelease."""
import os
from pathlib import Path
import subprocess
import tempfile
ROOT = Path(__file__).resolve().parents[4]
CACHE = Path(os.environ.get("GRADLE_USER_HOME", ROOT / ".gradle-user-home")) / "caches/modules-2/files-2.1"
JAVA = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-17")) / "bin" / ("java.exe" if os.name == "nt" else "java")
def artifact(group, name, version=None):
    base = CACHE/group/name
    if version:
        return next((base/version).glob("*/*.jar"))
    return max(base.glob("*/*/*.jar"), key=lambda p: p.stat().st_mtime)
compiler = [artifact("org.jetbrains.kotlin", name, "2.4.10") for name in
    ("kotlin-compiler-embeddable", "kotlin-stdlib", "kotlin-reflect", "kotlin-script-runtime")]
compiler += [artifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.10.2"), artifact("org.jetbrains", "annotations", "13.0")]
dependencies = [artifact("org.jetbrains.kotlin", "kotlin-stdlib", "2.4.10"),
    artifact("org.jetbrains.kotlinx", "kotlinx-serialization-core-jvm"),
    artifact("junit", "junit", "4.13.2"), artifact("org.hamcrest", "hamcrest-core", "1.3")]
sources = [
    "app/src/main/java/app/universal/patch/manager/network/runtime/RuntimeCatalog.kt",
    "app/src/main/java/app/universal/patch/manager/network/runtime/RuntimeDownloadProgress.kt",
    "app/src/test/java/app/universal/patch/manager/network/runtime/RuntimeDownloadProgressTest.kt",
    "app/src/main/java/app/universal/patch/manager/network/runtime/InternalRuntimeSupport.kt",
    "app/src/main/java/app/universal/patch/manager/network/runtime/RuntimeRestorePlan.kt",
    "app/src/test/java/app/universal/patch/manager/network/runtime/RuntimeRestorePlanTest.kt",
    "app/src/test/java/app/universal/patch/manager/network/runtime/InternalRuntimeSupportTest.kt",
    "app/src/main/java/app/universal/patch/manager/network/runtime/RuntimePluginPayload.kt",
    "app/src/test/java/app/universal/patch/manager/network/runtime/RuntimePluginPayloadTest.kt",
    "app/src/main/java/app/universal/patch/manager/network/runtime/LoadedPatcherRuntimePlugin.kt",
    "app/src/main/java/app/universal/patch/manager/network/runtime/PatcherRuntimePluginState.kt",
    "app/src/main/java/app/universal/patch/manager/network/runtime/RuntimeCatalogCache.kt",
    "app/src/test/java/app/universal/patch/manager/network/runtime/RuntimeCatalogCacheTest.kt",
    "app/src/main/java/app/universal/patch/manager/network/runtime/PatcherRuntimeKind.kt",
    "app/src/main/java/app/universal/patch/manager/patcher/patch/PatchBundleType.kt",
    "app/src/test/java/app/universal/patch/manager/network/runtime/RuntimeCatalogTest.kt",
    "app/src/test/java/app/universal/patch/manager/network/runtime/RuntimePackagingPolicyTest.kt"]
(ROOT/"build").mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix="runtime-catalog-", dir=ROOT/"build") as temporary:
    subprocess.run([str(JAVA), "-cp", os.pathsep.join(map(str,compiler)),
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
        "-jvm-target", "17", "-classpath", os.pathsep.join(map(str,dependencies)),
        "-d", temporary, *[str(ROOT/s) for s in sources]], check=True)
    subprocess.run([str(JAVA), "-cp", os.pathsep.join([temporary,*map(str,dependencies)]),
        "org.junit.runner.JUnitCore",
        "app.universal.patch.manager.network.runtime.RuntimeCatalogTest",
        "app.universal.patch.manager.network.runtime.RuntimeDownloadProgressTest",
        "app.universal.patch.manager.network.runtime.InternalRuntimeSupportTest",
        "app.universal.patch.manager.network.runtime.RuntimeRestorePlanTest",
        "app.universal.patch.manager.network.runtime.RuntimePluginPayloadTest",
        "app.universal.patch.manager.network.runtime.RuntimeCatalogCacheTest",
        "app.universal.patch.manager.network.runtime.RuntimePackagingPolicyTest"], check=True)
