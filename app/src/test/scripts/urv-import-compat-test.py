"""Check URV exports against UPM's wire models before assembleRelease.

The host runner extracts the production serialization declarations, omitting Android-only
methods and resource-only enum constructors. Frozen fixtures are emitted by the same
declarations from the last URV commit, not by UPM. No Android runtime is simulated.
"""
import argparse
import os
from pathlib import Path
import re
import subprocess
import tempfile
import tomllib

ROOT = Path(__file__).resolve().parents[4]
BASELINE = "88c51f70717d14a8d54755c3cf2fdaf9d0a9e7c5"
JAVA = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-17")) / "bin/java.exe"
CACHES = [ROOT / ".gradle-user-home/caches/modules-2/files-2.1",
          Path(os.environ.get("GRADLE_RO_DEP_CACHE", r"E:\Android\caches")) / "modules-2/files-2.1"]
FIXTURES = ROOT / "app/src/test/fixtures/urv-import"

def artifact(group, name, version="*"):
    found = [p for cache in CACHES for p in (cache / group / name).glob(version + "/*/*.jar")]
    if not found:
        raise RuntimeError(f"Missing cached {group}:{name}:{version}")
    return max(found, key=lambda p: tuple(map(int, re.findall(r"\d+", p.parent.parent.name))))

def classpath(paths):
    return os.pathsep.join(map(str, paths))

def source(relative, legacy):
    prefix = "app/urv/manager" if legacy else "app/universal/patch/manager"
    path = f"app/src/main/java/{prefix}/{relative}.kt"
    if legacy:
        return subprocess.check_output(["git", "show", f"{BASELINE}:{path}"], cwd=ROOT).decode("utf-8")
    return (ROOT / path).read_text(encoding="utf-8")

def constructor(text, name):
    start = re.search(r"\bdata class " + name + r"\s*\(", text)
    if start is None:
        raise AssertionError(f"Missing production data class {name}")
    opening = text.index("(", start.start())
    depth = 0
    for end in range(opening, len(text)):
        if text[end] == "(":
            depth += 1
        elif text[end] == ")":
            depth -= 1
            if depth == 0:
                return "@Serializable\n" + text[start.start():end + 1]
    raise AssertionError(f"Unclosed constructor {name}")

def enum(text, name):
    declaration = re.search(r"enum class " + name + r"\b[\s\S]*?\{([\s\S]*?)\}", text)
    assert declaration, name
    # Resource IDs and logging levels do not participate in the serialized enum name.
    values = re.findall(r"^\s*([A-Z][A-Z0-9_]*)\s*(?:\(|[,;])", declaration[1], re.M)
    assert values, name
    return "enum class " + name + " { " + ", ".join(values) + " }\n"

def models(directory, legacy=False):
    ns = "app.urv.manager" if legacy else "app.universal.patch.manager"
    files = []
    def write(name, package, imports, body):
        path = directory / name
        path.write_text(f"package {ns}.{package}\nimport kotlinx.serialization.Serializable\n{imports}\n{body}\n",
                        encoding="utf-8")
        files.append(path)
    preferences = source("domain/manager/PreferencesManager", legacy)
    write("Preferences.kt", "domain.manager",
          f"import {ns}.ui.theme.Theme\nimport {ns}.patcher.logger.PatcherLogMode",
          "\n".join(enum(preferences, n) for n in
                    ["SearchForUpdatesBackgroundInterval", "AutoClearCacheInterval", "BundleUpdateDeliveryMode"])
          + "\nclass PreferencesManager {\n" + constructor(preferences, "SettingsSnapshot") + "\n}")
    write("Theme.kt", "ui.theme", "", enum(source("ui/theme/Theme", legacy), "Theme"))
    write("LogMode.kt", "patcher.logger", "", enum(source("patcher/logger/Logger", legacy), "PatcherLogMode"))
    write("Options.kt", "data.room.options", "import kotlinx.serialization.json.JsonElement",
          "class Option {\n" + constructor(source("data/room/options/Option", legacy), "SerializedValue") + "\n}")
    write("ProfilePayload.kt", "data.room.profile", f"import {ns}.data.room.options.Option",
          "@Serializable" + source("data/room/profile/PatchProfilePayload", legacy).split("@Serializable", 1)[1])
    write("ProfileExport.kt", "domain.repository",
          f"import {ns}.data.room.profile.PatchProfilePayload\nimport {ns}.data.room.options.Option",
          constructor(source("domain/repository/PatchProfileRepository", legacy), "PatchProfileExportEntry")
          + "\ntypealias SerializedSelection = Map<String, List<String>>"
          + "\ntypealias SerializedOptions = Map<String, Map<String, Map<String, Option.SerializedValue>>>")
    dto = source("network/dto/ReVancedAsset", legacy)
    write("Asset.kt", "network.dto",
          "import kotlinx.serialization.SerialName\nimport kotlinx.datetime.LocalDateTime",
          constructor(dto, "ReVancedAsset"))
    write("Changelog.kt", "domain.bundles", "",
          constructor(source("domain/bundles/PatchBundleChangelogEntry", legacy), "PatchBundleChangelogEntry"))
    vm = source("ui/viewmodel/ImportExportViewModel", legacy)
    names = ["PatchBundleExportFile", "PatchBundleSnapshot", "PatchProfileExportFile",
             "ManagerSettingsExportFile", "EverythingExportFile", "PatchSelectionExportFile",
             "PatchSelectionBundleExport"]
    if not legacy:
        # Older baselines predate saved section states.
        names.append("PatchSectionStateExport")
    body = "\n\n".join(constructor(vm, name) for name in names)
    body += "\n@Serializable\n" + re.search(r"enum class OfficialBundleState\s*\{[^}]*\}", vm)[0] + "\n"
    write("BackupModels.kt", "ui.viewmodel",
          f"import {ns}.domain.manager.PreferencesManager\nimport {ns}.domain.bundles.PatchBundleChangelogEntry"
          f"\nimport {ns}.domain.repository.*", body)
    return files

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--generate-fixtures", action="store_true")
    args = parser.parse_args()
    kotlin = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text())["versions"]["kotlin"]
    compiler = [artifact("org.jetbrains.kotlin", name, kotlin) for name in
                ["kotlin-compiler-embeddable", "kotlin-stdlib", "kotlin-reflect", "kotlin-script-runtime"]]
    compiler += [artifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm"),
                 artifact("org.jetbrains", "annotations")]
    deps = [artifact("org.jetbrains.kotlin", name, kotlin) for name in
            ["kotlin-stdlib", "kotlin-test", "kotlin-test-junit"]]
    deps += [artifact("junit", "junit", "4.13.2"), artifact("org.hamcrest", "hamcrest-core")]
    deps += [artifact("org.jetbrains.kotlinx", "kotlinx-serialization-" + name + "-jvm")
             for name in ["core", "json"]]
    deps += [artifact("org.jetbrains.kotlinx", "kotlinx-datetime-jvm"),
             artifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm")]
    plugin = artifact("org.jetbrains.kotlin", "kotlin-serialization-compiler-plugin-embeddable", kotlin)
    (ROOT / "build").mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="urv-import-compat-", dir=ROOT / "build") as directory:
        work = Path(directory)
        classes = work / "classes"
        files = models(work, legacy=args.generate_fixtures)
        if args.generate_fixtures:
            emitter = ROOT / "app/src/test/fixtures/urv-import/EmitUrVFixtures.kt"
            files += [emitter]
        else:
            base = ROOT / "app/src/main/java/app/universal/patch/manager"
            files += [base / "domain/manager/LegacyManagerImport.kt",
                      base / "domain/bundles/LegacyBundleCompatibility.kt",
                      base / "domain/bundles/RepositoryBundleSettings.kt",
                      base / "network/api/ExternalBundlesEndpoints.kt",
                      base / "ui/viewmodel/PatchBundleImportCompatibility.kt",
                      base / "domain/manager/KeystoreRecovery.kt",
                      base / "domain/repository/RuntimePluginInstalledTrust.kt",
                      base / "domain/repository/RuntimePluginTrust.kt"]
            tests = ROOT / "app/src/test/java/app/universal/patch/manager"
            files += [tests / "domain/manager/LegacyManagerImportTest.kt",
                      tests / "domain/manager/KeystoreRecoveryTest.kt",
                      tests / "ui/viewmodel/LegacyUrVBackupTest.kt",
                      tests / "ui/viewmodel/PatchBundleImportCompatibilityTest.kt"]
        subprocess.run(list(map(str, [JAVA, "-cp", classpath(compiler),
            "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "17",
            "-classpath", classpath(deps), "-Xplugin=" + str(plugin), "-d", classes, *files])),
            check=True, timeout=120)
        if args.generate_fixtures:
            subprocess.run(list(map(str, [JAVA, "-cp", classpath([classes] + deps),
                "fixture.EmitUrVFixturesKt", FIXTURES])), check=True, timeout=120)
        else:
            subprocess.run(list(map(str, [JAVA, "-Dupm.legacyImportFixtures=" + str(FIXTURES),
                "-Dupm.resourceDir=" + str(ROOT / "app/src/main/res"),
                "-cp", classpath([classes] + deps), "org.junit.runner.JUnitCore",
                "app.universal.patch.manager.domain.manager.LegacyManagerImportTest",
                "app.universal.patch.manager.domain.manager.KeystoreRecoveryTest",
                "app.universal.patch.manager.ui.viewmodel.LegacyUrVBackupTest",
                "app.universal.patch.manager.ui.viewmodel.PatchBundleImportCompatibilityTest"])), check=True, timeout=120)

if __name__ == "__main__":
    main()
