"""Regression checks for settings issues #754, #759-764, #771, #788, #798-804.
Run before assembleRelease. UI contracts are static checks, not device interaction tests.
Preference transitions execute the actual production methods with in-memory storage.
"""
from pathlib import Path
import importlib.util
import os
import re
import subprocess
import tempfile
import sys
import tomllib
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[4]
BASE = ROOT / "app/src/main/java/app/universal/patch/manager"
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("compat", Path(__file__).with_name("urv-import-compat-test.py"))
compat = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compat)

def source(name):
    return (BASE / name).read_text(encoding="utf-8")

def method(text, signature):
    start = text.index(signature)
    opening = text.index("{", start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    return text[start:end]

# Every displayed settings subsection must have a searchable entry and a highlight target.
search = source("ui/screen/SettingsScreen.kt")
for path in (BASE / "ui/screen/settings").rglob("*.kt"):
    text = path.read_text(encoding="utf-8")
    for title in re.findall(r"GroupHeader\(\s*(?:title = )?stringResource\(R.string.(\w+)\)", text):
        assert re.search(r"SearchEntry\(\s*R.string." + title + r"\s*,", search), (path.name, title)
        assert ("targetKey = R.string." + title in text or
                "R.string." + title in re.sub(r"GroupHeader\([^)]*\)", "", text)), title
    for call in re.finditer(r"(?<!fun )(?:ColumnWithScrollbar|LazyColumnWithScrollbar)\(", text):
        assert "DASHBOARD_SCROLLBAR_THUMB_LENGTH" in text[call.start():call.start()+280], path.name
picker = source("ui/component/patches/PathSelectorDialog.kt")
assert "prefs.pathSelectorSearchQuery" not in picker
assert 'var searchQuery by rememberSaveable { mutableStateOf("") }' in picker
expandable = source("ui/component/settings/BooleanItem.kt")
assert "enabled = enabled && value" in expandable
assert "if (!enabled || !value) expanded = false" in expandable
assert "expandableContent(enabled && value && contentEnabled)" in expandable
discovery = source("ui/component/bundle/DiscoveryBundleReleaseSettings.kt")
assert "useLatest = !settings.useLatest, usePrereleases = false" not in discovery
assert "registry.prerelease ?: stored.usePrereleases" in discovery
filters = source("ui/screen/PatchBundleDiscoveryScreen.kt").split('listOf("all" to R.string.catalog_all,', 1)[1].split(".forEach", 1)[0]
assert filters.index('"release"') < filters.index('"prerelease"') < filters.index('"latest"')
for filename, dialog in [("BundleListScreen.kt","BundleOrderDialog"),("InstalledAppsScreen.kt","AppsOrderDialog"),
                         ("PatchProfilesScreen.kt","PatchProfilesOrderDialog"),("PatchesSelectorScreen.kt","PatchProfileNameDialog")]:
    text = source("ui/screen/"+filename).split("private fun "+dialog, 1)[1]
    assert "DASHBOARD_SCROLLBAR_THUMB_LENGTH" in text.split("LazyColumnWithScrollbar(",1)[1][:180]
for locale in ("values", "values-en-rUS"):
    strings = {e.attrib["name"]: e.text for e in ET.parse(ROOT/"app/src/main/res"/locale/"strings.xml").getroot() if e.tag=="string"}
    for key, expected in [("downloader_source_prerelease","Use pre-releases"),("downloader_source_latest","Use latest"),
                          ("patcher_runtime_type_label","Plugin type:"),("patcher_auto_expand_steps","Auto-expand running patcher steps")]:
        assert strings[key] == expected, (locale, key)
print("Settings search, scrollbar, picker, expansion, channel and English resource contracts passed.")

prefs = method(source("domain/manager/PreferencesManager.kt"), "suspend fun setMinimalPatchSelectionView")
provider_classes = ""
for name in ("DownloaderPluginRepository", "PatcherRuntimePluginRepository"):
    text = source("domain/repository/"+name+".kt")
    methods = "\n".join(method(text, "suspend fun "+m) for m in ("setSourceLatest", "setSourcePrerelease"))
    provider_classes += """
class %s {
    val sourceMutex = Mutex()
    var entries = listOf(SourceEntry("target", true, false), SourceEntry("other", false, false))
    fun readSourceEntries() = entries
    fun writeSourceEntries(value: List<SourceEntry>) { entries = value }
    fun reload() {}
    %s
}
""" % (name, methods)
# Execute the actual persisted-settings projection used when rebuilding a remote source.
# Storage-only tests cannot catch fields discarded by PatchBundleEntity.load().
load_call = source("domain/repository/PatchBundleRepository.kt").split(
    "private fun PatchBundleEntity.load()", 1)[1].split("JsonPatchBundle(", 1)[1].split(
    ").let { jsonBundle", 1)[0]
load_flags = "\n".join(load_call.strip().splitlines()[-4:])
assert load_flags.count("repositorySettings.") == 4
loader = """
fun reloadBundleSettings(directory: java.io.File): app.universal.patch.manager.domain.bundles.RepositoryBundleSettings {
    val repositorySettings = app.universal.patch.manager.domain.bundles.RepositoryBundleSettingsStore.read(directory)
    return app.universal.patch.manager.domain.bundles.RepositoryBundleSettings(
        %s
    )
}
""" % load_flags

harness = """
package regression
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*
import org.junit.Assert.*
import org.junit.Test
import app.universal.patch.manager.network.downloader.toDownloaderMainName
class Preference<T>(var value: T)
class PreferencesHarness {
    val patchSelectionShowVersionTags = Preference(true)
    val patchSelectionShowOptionPreviews = Preference(true)
    val patchSelectionDisplayRestoreFlags = Preference(0)
    suspend fun edit(block: PreferencesHarness.() -> Unit) { block() }
    %s
}
data class SourceEntry(val id: String, val prerelease: Boolean, val latest: Boolean)
%s
%s
class SettingsTransitionsTest {
    @Test fun sourceReloadRetainsAllSavedChannelAndPinFlags() {
        val directory = java.nio.file.Files.createTempDirectory("upm-source-reload-").toFile()
        try {
            for (prerelease in listOf(false, true)) for (latest in listOf(false, true)) {
                for (pinned in listOf(false, true)) {
                    val saved = app.universal.patch.manager.domain.bundles.RepositoryBundleSettings(
                        usePrereleases = prerelease, useLatest = latest, useSpecificVersion = pinned)
                    app.universal.patch.manager.domain.bundles.RepositoryBundleSettingsStore.write(directory, saved)
                    assertEquals(saved, reloadBundleSettings(directory))
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }
    @Test fun minimalViewRestoresEveryPriorCombination() = runBlocking {
        for (tags in listOf(false, true)) for (previews in listOf(false, true)) {
            val prefs = PreferencesHarness()
            prefs.patchSelectionShowVersionTags.value = tags
            prefs.patchSelectionShowOptionPreviews.value = previews
            if (tags || previews) prefs.setMinimalPatchSelectionView(true)
            assertFalse(prefs.patchSelectionShowVersionTags.value)
            assertFalse(prefs.patchSelectionShowOptionPreviews.value)
            prefs.setMinimalPatchSelectionView(false)
            assertEquals(tags || !previews, prefs.patchSelectionShowVersionTags.value)
            assertEquals(previews || !tags, prefs.patchSelectionShowOptionPreviews.value)
            assertEquals(0, prefs.patchSelectionDisplayRestoreFlags.value)
        }
    }
    @Test fun enablingMinimalTwiceRetainsTheOriginalChoice() = runBlocking {
        val prefs = PreferencesHarness()
        prefs.patchSelectionShowVersionTags.value = false
        prefs.setMinimalPatchSelectionView(true)
        prefs.setMinimalPatchSelectionView(true)
        prefs.setMinimalPatchSelectionView(false)
        assertFalse(prefs.patchSelectionShowVersionTags.value)
        assertTrue(prefs.patchSelectionShowOptionPreviews.value)
    }
    @Test fun downloaderNamesExcludeManagerPrefixesAndUseProviderCapitalization() {
        for ((label, expected) in mapOf(
            "ReVanced Manager: APKCombo" to "APKCombo",
            "ReVanced Manager: APKMirror" to "APKMirror",
            "ReVanced Manager: APKPure" to "APKPure",
            "ReVanced Manager: Play Store" to "Play Store",
            "UP Manager: Apkmirror Downloader" to "APKMirror"
        )) assertEquals(expected, label.toDownloaderMainName())
    }
    @Test fun automaticallyReachedMinimalViewDoesNotRestoreStaleChoices() = runBlocking {
        val prefs = PreferencesHarness()
        prefs.patchSelectionShowVersionTags.value = false
        prefs.setMinimalPatchSelectionView(true)
        prefs.setMinimalPatchSelectionView(false)
        prefs.patchSelectionShowOptionPreviews.value = false
        prefs.setMinimalPatchSelectionView(false)
        assertTrue(prefs.patchSelectionShowVersionTags.value)
        assertTrue(prefs.patchSelectionShowOptionPreviews.value)
    }
    @Test fun downloaderRestoresPreReleaseAfterLatestIsDisabled() = runBlocking {
        val repo = DownloaderPluginRepository()
        repo.setSourceLatest("target", true)
        assertTrue(repo.entries[0].prerelease)
        assertTrue(repo.entries[0].latest)
        repo.setSourceLatest("target", false)
        assertTrue(repo.entries[0].prerelease)
        assertFalse(repo.entries[0].latest)
        assertEquals(SourceEntry("other", false, false), repo.entries[1])
        repo.setSourcePrerelease("target", false)
        repo.setSourceLatest("target", true)
        repo.setSourceLatest("target", false)
        assertFalse(repo.entries[0].prerelease)
    }
    @Test fun runtimeRestoresPreReleaseAfterLatestIsDisabled() = runBlocking {
        val repo = PatcherRuntimePluginRepository()
        repo.setSourceLatest("target", true)
        assertTrue(repo.entries[0].prerelease)
        repo.setSourceLatest("target", false)
        assertTrue(repo.entries[0].prerelease)
        assertFalse(repo.entries[0].latest)
        assertEquals(SourceEntry("other", false, false), repo.entries[1])
        repo.setSourcePrerelease("target", false)
        repo.setSourceLatest("target", true)
        repo.setSourceLatest("target", false)
        assertFalse(repo.entries[0].prerelease)
    }
}
""" % (prefs, provider_classes, loader)

kotlin = tomllib.loads((ROOT/"gradle/libs.versions.toml").read_text(encoding="utf-8"))["versions"]["kotlin"]
artifact = compat.artifact
compiler = [artifact("org.jetbrains.kotlin", name, kotlin) for name in
            ("kotlin-compiler-embeddable","kotlin-stdlib","kotlin-reflect","kotlin-script-runtime")]
compiler += [artifact("org.jetbrains.kotlinx","kotlinx-coroutines-core-jvm"), artifact("org.jetbrains","annotations")]
deps = [artifact("org.jetbrains.kotlin","kotlin-stdlib",kotlin), artifact("org.jetbrains","annotations"),
        artifact("org.jetbrains.kotlinx","kotlinx-coroutines-core-jvm"),artifact("org.jetbrains.kotlinx","kotlinx-datetime-jvm"),
        artifact("org.jetbrains.kotlinx","kotlinx-serialization-core-jvm"),artifact("org.jetbrains.kotlinx","kotlinx-serialization-json-jvm"),
        artifact("junit","junit","4.13.2"),artifact("org.hamcrest","hamcrest-core")]
plugin = artifact("org.jetbrains.kotlin","kotlin-serialization-compiler-plugin-embeddable",kotlin)
java = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-17"))/"bin/java.exe"
cp = lambda paths: os.pathsep.join(map(str, paths))
with tempfile.TemporaryDirectory(prefix="settings-issues-",dir=ROOT/"build") as directory:
    work=Path(directory)
    test=work/"SettingsTransitionsTest.kt"
    test.write_text(harness,encoding="utf-8")
    classes=work/"classes"
    names=work/"DownloaderNames.kt"
    names.write_text("package app.universal.patch.manager.network.downloader\n" +
        method(source("network/downloader/LoadedDownloaderPlugin.kt"), "fun String.toDownloaderMainName"),
        encoding="utf-8")
    sources=[test, names, BASE/"domain/bundles/RepositoryBundleSettings.kt", BASE/"network/dto/ReVancedAsset.kt",
             ROOT/"app/src/test/java/app/universal/patch/manager/domain/bundles/RepositoryBundleSettingsTest.kt"]
    subprocess.run(list(map(str,[java,"-cp",cp(compiler),"org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib","-no-reflect","-jvm-target","17","-classpath",cp(deps),"-Xplugin="+str(plugin),
        "-d",classes,*sources])),check=True,timeout=120)
    subprocess.run(list(map(str,[java,"-cp",cp([classes]+deps),"org.junit.runner.JUnitCore",
        "regression.SettingsTransitionsTest","app.universal.patch.manager.domain.bundles.RepositoryBundleSettingsTest"])),
        check=True,timeout=90)
