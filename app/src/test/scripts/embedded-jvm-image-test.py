"""Smoke-test the linked Liberica image on an existing Android x86_64 emulator.

Uses the manager and support build intermediates. No apps are installed.
This checks the packaged runtime and headless image support, not the bound worker.
"""
import lzma
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[4]
RUNTIMES = Path(os.environ.get(
    "UPM_RUNTIMES", ROOT.parent.parent / "UP-Manager-Runtimes" / "main-worktree"
))
JDK = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-17"))
ADB = Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb.exe"
DEVICE = [ADB, "-s", os.environ.get("ANDROID_SERIAL", "emulator-5554")]
ABI = "x86_64"
runtime_output = RUNTIMES / "shared-support/build/intermediates/embeddedJvm"
loader = ROOT / f"app/build/generated/glibcLoader/{ABI}/{ABI}/libglibc_loader.so"
host = RUNTIMES / "revanced21-patcher-core/build/shrunk-host/revanced21-patcher-core.jar"


def run(*args, **kwargs):
    return subprocess.run(list(map(str, args)), check=True, **kwargs)


with tempfile.TemporaryDirectory(prefix="liberica-image-", dir=ROOT / "build") as directory:
    work = Path(directory)
    runtime = work / "jre"
    with lzma.open(runtime_output / f"{ABI}.zip.xz") as compressed, zipfile.ZipFile(compressed) as archive:
        archive.extractall(runtime)
    shutil.copyfile(runtime_output / f"{ABI}.modules", runtime / "lib/modules")
    run(JDK / "bin/javac.exe", "--release", "17", "-d", work,
        ROOT / "app/src/test/fixtures/jvm-image/ImageProbe.java")

    remote = "/data/local/tmp/upm-liberica-" + uuid.uuid4().hex
    try:
        run(*DEVICE, "shell", "mkdir", "-p", remote)
        run(*DEVICE, "push", runtime, remote + "/jre")
        run(*DEVICE, "push", work / "ImageProbe.class", remote + "/ImageProbe.class")
        run(*DEVICE, "push", host, remote + "/host.jar")
        run(*DEVICE, "push", loader, remote + "/loader")
        java = remote + "/jre/bin/java"
        run(*DEVICE, "shell", "chmod", "700", remote + "/loader", java)
        libraries = ":".join(remote + "/jre/" + name for name in ("glibc", "lib", "lib/server"))
        command = [*DEVICE, "shell", remote + "/loader", "--library-path", libraries, java]
        # Check the version and image support in one startup. The probe needs only a
        # small heap and baseline JIT, which limits contention on a busy emulator.
        run(*command, "-showversion", "-Xms64m", "-Xmx512m", "-XX:+UseSerialGC",
            "-XX:TieredStopAtLevel=1", "-Djava.awt.headless=true", "-cp",
            remote + ":" + remote + "/host.jar", "ImageProbe", timeout=180)
    finally:
        resolved = subprocess.check_output(list(map(str, [*DEVICE, "shell", "readlink", "-f", remote])),
                                          text=True).strip()
        if resolved == remote and remote.startswith("/data/local/tmp/upm-liberica-"):
            run(*DEVICE, "shell", "rm", "-rf", remote)
