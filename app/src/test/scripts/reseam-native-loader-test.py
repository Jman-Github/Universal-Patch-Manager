"""Run focused JVM tests and an optional real-Reseam JNI regression on an Android emulator.

No Gradle tasks are invoked. Run before the final assembleRelease validation.
Use --emulator SERIAL with an existing reseam-runtime.apk; only a unique /data/local/tmp
harness directory is created and removed. Installed apps and their data are untouched.
"""
import argparse
import os
import re
from pathlib import Path
import shutil
import subprocess
import tempfile
import tomllib
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[4]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--emulator")
parser.add_argument("--runtime", "--plugin", dest="plugin", type=Path, default=ROOT / "reseam-runtime/build/outputs/apk/release/reseam-runtime.apk")
parser.add_argument("--metadata-bundle", type=Path)
parser.add_argument("--manager-apk", type=Path)
args = parser.parse_args()
if args.metadata_bundle and not (args.emulator and args.manager_apk):
    parser.error("--metadata-bundle requires --emulator and --manager-apk")
java_home = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-17"))
java = java_home / "bin" / ("java.exe" if os.name == "nt" else "java")
caches = [ROOT / ".gradle-user-home/caches/modules-2/files-2.1"]
if os.environ.get("GRADLE_USER_HOME"):
    caches.append(Path(os.environ["GRADLE_USER_HOME"]) / "caches/modules-2/files-2.1")
if os.environ.get("GRADLE_RO_DEP_CACHE"):
    caches.append(Path(os.environ["GRADLE_RO_DEP_CACHE"]) / "modules-2/files-2.1")


def artifact(group, name, version="*"):
    matches = [path for cache in caches for path in (cache / group / name).glob(version + "/*/*.jar")]
    if not matches:
        raise RuntimeError(f"Missing cached dependency {group}:{name}:{version}")
    return max(matches, key=lambda path: tuple(map(int, re.findall(r"\d+", path.parent.parent.name))))


def cp(paths):
    return os.pathsep.join(map(str, paths))


def run(command, **kwargs):
    return subprocess.run(list(map(str, command)), check=True, **kwargs)


kotlin = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text())["versions"]["kotlin"]
stdlib = artifact("org.jetbrains.kotlin", "kotlin-stdlib", kotlin)
compiler = [artifact("org.jetbrains.kotlin", name, kotlin) for name in
            ("kotlin-compiler-embeddable", "kotlin-stdlib", "kotlin-reflect", "kotlin-script-runtime", "kotlin-daemon-embeddable")]
coroutines = artifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm")
compiler += [coroutines, artifact("org.jetbrains", "annotations")]
tests = [stdlib, coroutines, artifact("junit", "junit", "4.13.2"), artifact("org.hamcrest", "hamcrest-core")]
main = ROOT / "app/src/main/java/app/universal/patch/manager/patcher"
unit = ROOT / "app/src/test/java/app/universal/patch/manager/patcher"
pure_sources = [main / "reseam/ReseamRuntimeLoaderCache.kt", main / "reseam/ReseamRuntimeReflection.kt",
                main / "runtime/reseam/ReseamRuntimeFiles.kt"]
unit_sources = [unit / "reseam/ReseamRuntimeLoaderCacheTest.kt", unit / "reseam/ReseamRuntimeReflectionTest.kt",
                unit / "runtime/reseam/ReseamRuntimeFilesTest.kt"]
(ROOT / "build").mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix="reseam-native-loader-", dir=ROOT / "build") as temporary:
    work = Path(temporary)
    classes = work / "jvm"
    run([java, "-cp", cp(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
         "-jvm-target", "17", "-classpath", cp(tests), "-d", classes] + pure_sources + unit_sources, timeout=120)
    run([java, "-cp", cp([classes] + tests), "org.junit.runner.JUnitCore",
         "app.universal.patch.manager.patcher.reseam.ReseamRuntimeLoaderCacheTest",
         "app.universal.patch.manager.patcher.reseam.ReseamRuntimeReflectionTest",
         "app.universal.patch.manager.patcher.runtime.reseam.ReseamRuntimeFilesTest"], timeout=90)
    if args.emulator:
        sdk = Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", "")))
        platforms = sorted((sdk / "platforms").glob("android-37*/android.jar"))
        android = platforms[-1] if platforms else sdk / "platforms/android-37/android.jar"
        d8 = artifact("com.android.tools", "r8")
        adb = sdk / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
        assert android.is_file() and d8.is_file() and adb.is_file(), "Android SDK 37/build-tools 36 required"
        assert args.plugin.is_file(), "Pass --runtime with a previously assembled Reseam runtime APK"
        fixture = ROOT / "app/src/test/fixtures/reseam-native-loader"
        sources = pure_sources + [main / "reseam/ReseamRuntimeBridge.kt", main / "runtime/reseam/ReseamRuntimeAssets.kt", main / "runtime/reseam/BundledReseamRuntimeAssets.kt",
            main / "runtime/RuntimePreparationCache.kt", main / "runtime/PatcherRuntimePluginRegistry.kt", ROOT / "shared/runtime-plugin/src/main/java/app/universal/patch/manager/patcher/logger/Logger.kt"]
        sources += sorted(fixture.glob("*.kt"))
        harness = work / "harness.jar"
        run([java, "-cp", cp(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
             "-jvm-target", "17", "-classpath", cp([android, stdlib, coroutines]), "-d", harness] + sources, timeout=120)
        dex = work / "harness.zip"
        run([java, "-cp", d8, "com.android.tools.r8.D8", "--min-api", "26", "--lib", android,
             "--classpath", coroutines, "--output", dex, harness, stdlib], timeout=120)
        device = [adb, "-s", args.emulator]
        remote = "/data/local/tmp/upm-reseam-native-" + uuid.uuid4().hex
        try:
            run(device + ["shell", "mkdir", "-p", remote])
            run(device + ["push", dex, remote + "/harness.zip"])
            for version in ("a", "b"):
                apk = work / f"source-{version}.apk"
                shutil.copyfile(args.plugin, apk)
                with zipfile.ZipFile(apk, "a") as archive:
                    archive.comment = f"reseam-loader-probe-{version}".encode()
                run(device + ["push", apk, remote + "/" + apk.name])
            probe = list(map(str, device + ["shell", "env", "CLASSPATH=" + remote + "/harness.zip", "app_process", "/system/bin",
                                           "app.universal.patch.manager.patcher.reseam.ReseamNativeLoaderProbe", remote]))
            publishers = [subprocess.Popen(probe + ["prepare"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
                          for _ in range(2)]
            for publisher in publishers:
                output, _ = publisher.communicate(timeout=120)
                print(output, end="")
                assert publisher.returncode == 0, output
            run(probe, timeout=180)
            if args.metadata_bundle:
                # The ownership probe deliberately deletes its original APK.
                run(device + ["push", args.plugin, remote + "/source-a.apk"])
                run(device + ["push", args.metadata_bundle, remote + "/metadata.reseam"])
                with zipfile.ZipFile(args.manager_apk) as manager:
                    for name in ("libprop_override.so", "libc++_shared.so"):
                        library = work / name
                        library.write_bytes(manager.read("lib/x86_64/" + name))
                        run(device + ["push", library, remote + "/" + name])
                run(device + ["shell", "env", "LD_LIBRARY_PATH=" + remote,
                    "LD_PRELOAD=" + remote + "/libprop_override.so",
                    "PROP_dalvik.vm.heapsize=1024M", "PROP_dalvik.vm.heapgrowthlimit=1024M",
                    "PROP_dalvik.vm.usejit=false", "PROP_dalvik.vm.usejitprofiles=false",
                    "CLASSPATH=" + remote + "/harness.zip", "app_process64", "/system/bin",
                    "app.universal.patch.manager.patcher.reseam.ReseamNativeLoaderProbe", remote,
                    "metadata", remote + "/metadata.reseam"], timeout=180)

        finally:
            resolved = subprocess.check_output(list(map(str, device + ["shell", "readlink", "-f", remote])), text=True).strip()
            if resolved == remote and remote.startswith("/data/local/tmp/upm-reseam-native-"):
                run(device + ["shell", "rm", "-rf", remote])
