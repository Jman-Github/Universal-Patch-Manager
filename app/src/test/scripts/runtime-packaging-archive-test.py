"""Validate assembled runtime boundaries without installing or executing APKs."""
from pathlib import Path
import argparse
import io
import lzma
import struct
import time
import zipfile

ROOT = Path(__file__).resolve().parents[4]

def dex_classes(data):
    def u32(offset):
        return struct.unpack_from("<I", data, offset)[0]
    strings = u32(60)
    types = u32(68)
    count, definitions = u32(96), u32(100)
    names = set()
    for index in range(count):
        string_index = u32(types + 4 * u32(definitions + index * 32))
        offset = u32(strings + 4 * string_index)
        while data[offset] & 0x80:
            offset += 1
        offset += 1
        end = data.index(0, offset)
        names.add(data[offset:end].decode("utf-8"))
    return names

def apk_classes(archive):
    result = set()
    for name in archive.namelist():
        if name.startswith("classes") and name.endswith(".dex"):
            result.update(dex_classes(archive.read(name)))
    return result

def validate():
    app = max((ROOT / "app/build/outputs/apk/release").glob("*universal.apk"),
              key=lambda path: path.stat().st_mtime_ns)
    with zipfile.ZipFile(app) as archive:
        entries = set(archive.namelist())
        assert "assets/runtime-catalog.json" in entries
        assert not any(name.startswith("assets/jvm/") or name in
            {"assets/reseam-runtime.apk", "assets/morphe-runtime.apk"} for name in entries)
        classes = apk_classes(archive)
        assert not any(name.startswith(("Lapp/revanced/patcher/", "Lapp/morphe/patcher/", "Lapp/reseam/sdk/")) for name in classes)
        for name in ("revanced", "morphe", "reseam"):
            assert not any(name + "/runtime/" in value and value.endswith("RuntimeEntry;") for value in classes)
    print("PASS: manager contains its bootstrap catalog and no engine APKs, JVM distributions or engine implementations")

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--wait", type=int, default=0, help="Wait for an in-progress assembly to publish valid APKs")
args = parser.parse_args()
deadline = time.monotonic() + args.wait
while True:
    try:
        validate()
        break
    except (AssertionError, FileNotFoundError, zipfile.BadZipFile):
        if time.monotonic() >= deadline:
            raise
        time.sleep(1)
