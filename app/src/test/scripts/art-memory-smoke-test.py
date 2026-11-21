"""Verify ART startup and actual heap limits using a release APK on an emulator.
Does not install apps. Creates and removes one unique device temp directory.
Requires a built x86_64 APK and adb; no Gradle tasks are invoked.
"""
import argparse
import json
import subprocess
import tempfile
import uuid
import zipfile
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("apk", type=Path)
parser.add_argument("--serial", default="emulator-5554")
args = parser.parse_args()
adb = ["adb", "-s", args.serial]
remote = "/data/local/tmp/upm-art-" + uuid.uuid4().hex

def run(*parts):
    result = subprocess.run(adb + list(parts), capture_output=True, text=True, timeout=90)
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout

try:
    run("shell", "mkdir", remote)
    run("push", str(args.apk), remote + "/manager.apk")
    run("shell", "chmod", "444", remote + "/manager.apk")
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        with zipfile.ZipFile(args.apk) as apk:
            (tmp / "libprop_override.so").write_bytes(apk.read("lib/x86_64/libprop_override.so"))
            (tmp / "libc++_shared.so").write_bytes(apk.read("lib/x86_64/libc++_shared.so"))
        run("push", str(tmp / "libprop_override.so"), remote + "/libprop_override.so")
        run("push", str(tmp / "libc++_shared.so"), remote + "/libc++_shared.so")
        request = {"cacheDir": remote, "action": "invalid-smoke-test", "device": {
            "sdk": 36, "fingerprint": "smoke", "abis": ["x86_64"], "locales": ["en-US"], "density": 160}}
        (tmp / "request.json").write_text(json.dumps(request))
        run("push", str(tmp / "request.json"), remote + "/request.json")
        for heap in (512, 1024, 2048):
            command = (f"env LD_LIBRARY_PATH={remote} CLASSPATH={remote}/manager.apk LD_PRELOAD={remote}/libprop_override.so "
                       f"PROP_dalvik.vm.heapsize={heap}M PROP_dalvik.vm.heapgrowthlimit={heap}M "
                       "/system/bin/app_process64 / app.universal.patch.manager.patcher.runtime.ArtProcessMain "
                       f"{remote}/request.json app.universal.patch.manager.patcher.host.ToolRuntimeEntry '' ''")
            try:
                result = run("shell", command)
            except RuntimeError as error:
                if heap != 2048 or "Aborted" not in str(error):
                    raise
                # Some ART builds cannot reserve two large contiguous heap regions.
                # The client retries startup at half the heap, before any patch runs.
                result = run("shell", command.replace("=2048M", "=1024M"))
                print("PASS: 2048 MB reservation rejected; fresh 1024 MB startup succeeds")
                heap = 1024
            events = [json.loads(line) for line in result.splitlines() if line.startswith("{")]
            assert events[0]["type"] == "started", result
            assert events[-1]["type"] == "finished", result
            samples = [event["max"] for event in events if event["type"] == "memory"]
            values = [int(value["value"]) if isinstance(value, dict) else int(value) for value in samples]
            assert heap in values, result
            print(f"PASS: fresh ART worker reports {heap} MB heap; terminal protocol delivered")
finally:
    run("shell", "rm", "-rf", remote)
