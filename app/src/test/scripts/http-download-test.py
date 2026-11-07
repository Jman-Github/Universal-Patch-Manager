"""Exercise the production HTTP downloader against a local server, without Gradle."""
import os
from pathlib import Path
import re
import subprocess
import tempfile
import tomllib
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[4]
JAVA = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-17")) / "bin/java.exe"
CACHES = [ROOT / ".gradle-user-home/caches/modules-2/files-2.1",
          Path(os.environ.get("GRADLE_RO_DEP_CACHE", r"E:\Android\caches")) / "modules-2/files-2.1"]

def artifact(group, name, version="*"):
    found = [p for cache in CACHES for p in (cache / group / name).glob(version + "/*/*.jar")]
    if not found:
        raise RuntimeError(f"Missing cached {group}:{name}:{version}")
    return max(found, key=lambda p: tuple(map(int, re.findall(r"\d+", p.parent.parent.name))))

def cp(paths):
    return os.pathsep.join(map(str, paths))

versions = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text())["versions"]
compiler = [artifact("org.jetbrains.kotlin", n, versions["kotlin"]) for n in
            ("kotlin-compiler-embeddable", "kotlin-stdlib", "kotlin-reflect", "kotlin-script-runtime")]
compiler += [artifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm"), artifact("org.jetbrains", "annotations")]
deps = [artifact("org.jetbrains.kotlin", "kotlin-stdlib", versions["kotlin"]),
        artifact("org.jetbrains", "annotations"), artifact("junit", "junit", "4.13.2"),
        artifact("org.hamcrest", "hamcrest-core"), artifact("org.slf4j", "slf4j-api"),
        artifact("com.squareup.okio", "okio-jvm")]
deps += [artifact("org.jetbrains.kotlinx", n) for n in
         ("kotlinx-coroutines-core-jvm", "kotlinx-serialization-core-jvm",
          "kotlinx-serialization-json-jvm", "kotlinx-io-core-jvm", "kotlinx-io-bytestring-jvm")]
deps += [artifact("io.ktor", n, versions["ktor"]) for n in
         ("ktor-client-core-jvm", "ktor-client-okhttp-jvm", "ktor-http-jvm",
          "ktor-io-jvm", "ktor-utils-jvm", "ktor-events-jvm", "ktor-websockets-jvm", "ktor-sse-jvm")]

with tempfile.TemporaryDirectory(prefix="http-download-", dir=ROOT / "build") as directory:
    work = Path(directory)
    # The Android artifact has Android-only platform classes. Use its matching JVM release.
    versions_okhttp = [p.name for cache in CACHES for p in
                       (cache / "com.squareup.okhttp3/okhttp-android").glob("*") if p.is_dir()]
    version_okhttp = max(versions_okhttp, key=lambda v: tuple(map(int, re.findall(r"\d+", v))))
    try:
        okhttp = artifact("com.squareup.okhttp3", "okhttp-jvm", version_okhttp)
    except RuntimeError:
        okhttp = work / "okhttp-jvm.jar"
        url = ("https://repo.maven.apache.org/maven2/com/squareup/okhttp3/okhttp-jvm/"
               f"{version_okhttp}/okhttp-jvm-{version_okhttp}.jar")
        with urlopen(url, timeout=30) as response:
            okhttp.write_bytes(response.read())
    deps.append(okhttp)
    (work / "Log.kt").write_text(
        'package android.util\nobject Log {\n'
        'fun e(tag: String, message: String, error: Throwable? = null): Int = error("Android logging is unavailable in JVM tests")\n'
        'fun w(tag: String, message: String, error: Throwable? = null): Int = error("Android logging is unavailable in JVM tests")\n}\n')
    (work / "Tag.kt").write_text('package app.universal.patch.manager.util\nval Any.tag: String get() = "test"\n')
    sources = [work / "Log.kt", work / "Tag.kt",
               ROOT / "app/src/main/java/app/universal/patch/manager/network/utils/APIResponse.kt",
               ROOT / "app/src/main/java/app/universal/patch/manager/network/service/HttpService.kt",
               ROOT / "app/src/test/java/app/universal/patch/manager/network/service/HttpServiceDownloadTest.kt"]
    classes = work / "classes"
    subprocess.run(list(map(str, [JAVA, "-cp", cp(compiler),
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
        "-jvm-target", "17", "-classpath", cp(deps), "-d", classes, *sources])), check=True, timeout=90)
    subprocess.run(list(map(str, [JAVA, "-cp", cp([classes] + deps), "org.junit.runner.JUnitCore",
        "app.universal.patch.manager.network.service.HttpServiceDownloadTest"])), check=True, timeout=90)
