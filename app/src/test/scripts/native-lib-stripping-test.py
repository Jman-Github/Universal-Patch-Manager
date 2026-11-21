"""Run production native-library stripping and signing regression tests without Gradle."""
import os
from pathlib import Path
import subprocess
import tempfile
import tomllib

ROOT = Path(__file__).resolve().parents[4]
JAVA = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-17")) / "bin/java.exe"
VERSIONS = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text(encoding="utf-8"))["versions"]


# Reuse the existing runner's cache lookup across repository and user Gradle homes.
import importlib.util
import sys
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("compat", Path(__file__).with_name("urv-import-compat-test.py"))
compat = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compat)
artifact = compat.artifact


def cp(paths):
    return os.pathsep.join(map(str, paths))


compiler = [artifact("org.jetbrains.kotlin", name, VERSIONS["kotlin"]) for name in
            ("kotlin-compiler-embeddable", "kotlin-stdlib", "kotlin-reflect", "kotlin-script-runtime")]
compiler += [artifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm"), artifact("org.jetbrains", "annotations")]
deps = [artifact("org.jetbrains.kotlin", name, VERSIONS["kotlin"]) for name in
        ("kotlin-stdlib", "kotlin-test", "kotlin-test-junit")]
deps += [artifact("junit", "junit", "4.13.2"), artifact("org.hamcrest", "hamcrest-core"),
         artifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm"),
         artifact("com.android.tools.build", "apksig"), ROOT / "libs/APKEditor-1.4.7.jar"]
deps += [artifact("org.bouncycastle", name + "-jdk15to18", VERSIONS["bouncycastle"])
         for name in ("bcprov", "bcpkix", "bcutil")]
base = ROOT / "app/src/main/java/app/universal/patch/manager"
# Optional path exercises the identical copies shipped by the separate runtime plugins.
raw_base = Path(sys.argv[1]) if len(sys.argv) > 1 else base / "patcher/util"
(ROOT / "build").mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix="native-strip-", dir=ROOT / "build") as directory:
    work = Path(directory)
    stub = work / "RuntimePlatform.kt"
    stub.write_text("""package app.universal.patch.manager.patcher.runtime
object RuntimeDevice { val supportedAbis = listOf("arm64-v8a"); const val sdk = 36 }
object RuntimeLog { fun i(tag: String, message: String) {} }
""", encoding="utf-8")
    sources = [stub, raw_base / "NativeLibStripper.kt", raw_base / "NativeLibRawCopy.kt",
               base / "domain/manager/InPlaceApkSigner.kt", base / "domain/manager/SigningKeyAlgorithm.kt",
               ROOT / "app/src/test/java/app/universal/patch/manager/patcher/util/NativeLibStripperTest.kt"]
    classes = work / "classes"
    subprocess.run([str(JAVA), "-cp", cp(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                    "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", cp(deps),
                    "-d", str(classes), *map(str, sources)], check=True, timeout=120)
    subprocess.run([str(JAVA), "-cp", cp([classes] + deps), "org.junit.runner.JUnitCore",
                    "app.universal.patch.manager.patcher.util.NativeLibStripperTest"], check=True, timeout=120)
