"""Exercise the actual settings search registry and matcher before assembleRelease.
Checks query results and navigation targets on the JVM, not device UI rendering.
"""
from pathlib import Path
import importlib.util
import json
import os
import re
import subprocess
import sys
import tempfile
import tomllib
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[4]
BASE = ROOT / "app/src/main/java/app/universal/patch/manager"
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("compat", Path(__file__).with_name("urv-import-compat-test.py"))
compat = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compat)
source = (BASE / "ui/screen/SettingsScreen.kt").read_text(encoding="utf-8")
registry = source.split("val settingsEntries = remember {", 1)[1].split("var searchQuery", 1)[0]
registry = registry[:registry.rfind("}")].strip()
model = source.split("private data class SearchEntry(", 1)[1].split("\n)", 1)[0]
model = "private data class SearchEntry(" + model.replace("@StringRes ", "") + "\n)"
assert "matchesSettingsSearch(searchText, normalizedQuery)" in source
assert "append(stringResource(entry.category))" in source
assert "SettingsSearchTarget(entry.destination, entry.targetId)" in source
names = sorted(set(re.findall(r"R\.string\.(\w+)", registry)))
resources = "object R { object string {\n" + "\n".join(
    f"const val {name} = {i}" for i, name in enumerate(names)
) + "\n} }\n"
advanced = (BASE / "ui/screen/settings/AdvancedSettingsScreen.kt").read_text(encoding="utf-8")
dashboard = (BASE / "ui/screen/settings/DashboardActionSettings.kt").read_text(encoding="utf-8")
for kind in ("patcher", "merger"):
    assert f"targetKey = R.string.{kind}_resource_graphs_pinned_title" in advanced
assert "highlightTarget == titleRes || highlightTarget == visibilityRes" in dashboard
common = '''
package regression
import app.universal.patch.manager.ui.model.matchesSettingsSearch
object Settings {
    enum class Destination { General, Patcher, AdvancedSystem, Updates,
        Downloads, PatcherRuntimes, Storage, ImportExport, About, Developer }
    val General = Destination.General
    val Patcher = Destination.Patcher
    val AdvancedSystem = Destination.AdvancedSystem
    val Updates = Destination.Updates
    val Downloads = Destination.Downloads
    val PatcherRuntimes = Destination.PatcherRuntimes
    val Storage = Destination.Storage
    val ImportExport = Destination.ImportExport
    val About = Destination.About
    val Developer = Destination.Developer
}
'''
common += resources + model + "\nprivate val entries = " + registry + "\n"
tests = '''
private fun results(query: String) = entries.filter { entry ->
    val text = (listOfNotNull(entry.title, entry.category, entry.description) + entry.keywords)
        .joinToString(" ") { strings.getValue(it) }
    matchesSettingsSearch(text, query)
}
fun main() {
    for (query in listOf("pin patcher resource graphs", "patcher pin graphs", "fixed patcher graphs", "pinning patcher", "PATCHER  PINNED")) {
        check(results(query).any { it.title == R.string.patcher_resource_graphs_pinned_title &&
            it.targetId == it.title && it.destination == Settings.Patcher }) { query }
    }
    for (query in listOf("pin merger resource graphs", "merger pin graphs", "fixed merger graphs", "sticky merger graphs")) {
        check(results(query).any { it.title == R.string.merger_resource_graphs_pinned_title &&
            it.targetId == it.title && it.destination == Settings.Patcher }) { query }
    }
    for ((query, category, target, title) in listOf(
        listOf("apps show all action buttons", R.string.dashboard_apps_actions, R.string.dashboard_apps_visibility, R.string.patch_selection_action_visibility_reset),
        listOf("apps reset default order", R.string.dashboard_apps_actions, R.string.dashboard_apps_actions, R.string.patch_selection_action_order_reset),
        listOf("patch bundles show all action buttons", R.string.dashboard_bundles_actions, R.string.dashboard_bundles_visibility, R.string.patch_selection_action_visibility_reset),
        listOf("patch bundles reset default order", R.string.dashboard_bundles_actions, R.string.dashboard_bundles_actions, R.string.patch_selection_action_order_reset)
    )) {
        check(results(query as String).any { it.category == category && it.targetId == target &&
            it.title == title && it.destination == Settings.General }) { query }
    }
    check(results("").isEmpty())
    check(results("  ").isEmpty())
    check(results("unmatched-feature-xyz").isEmpty())
    check(results("merger pin graphs").none { it.title == R.string.patcher_resource_graphs_pinned_title })
    check(matchesSettingsSearch("Pin patcher resource graphs", " graphs\\tpatcher\\npin "))
    check(!matchesSettingsSearch("Pin patcher resource graphs", "pin absent"))
    println("PASS: pinning and dashboard reset searches, word order, whitespace, and safe navigation")
}
'''
a = compat.artifact
kotlin = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text(encoding="utf-8"))["versions"]["kotlin"]
compiler = [a("org.jetbrains.kotlin", n, kotlin) for n in
            ("kotlin-compiler-embeddable", "kotlin-stdlib", "kotlin-reflect", "kotlin-script-runtime")]
deps = [a("org.jetbrains.kotlin", "kotlin-stdlib", kotlin), a("org.jetbrains", "annotations")]
compiler += deps + [a("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm")]
java = Path(r"C:\Program Files\Java\jdk-17\bin\java.exe")
cp = lambda paths: os.pathsep.join(map(str, paths))
def xml_strings(locale):
    return {e.attrib["name"]: e.text or "" for e in
            ET.parse(ROOT / "app/src/main/res" / locale / "strings.xml").getroot() if e.tag == "string"}
base_strings = xml_strings("values")
with tempfile.TemporaryDirectory(prefix="settings-search-", dir=ROOT / "build") as directory:
    work = Path(directory)
    for locale in ("values", "values-en-rUS"):
        strings = base_strings | xml_strings(locale)
        values = "private val strings = mapOf(\n" + ",\n".join(
            f"R.string.{name} to " + json.dumps(strings[name], ensure_ascii=False).replace("$", "\\$") for name in names
        ) + "\n)\n"
        file = work / "SearchRegression.kt"
        file.write_text(common + values + tests, encoding="utf-8")
        classes = work / locale
        subprocess.run(list(map(str, [java, "-cp", cp(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", cp(deps), "-d", classes,
            file, BASE / "ui/model/SettingsSearchQuery.kt"])), check=True, timeout=120)
        subprocess.run(list(map(str, [java, "-cp", cp([classes] + deps), "regression.SearchRegressionKt"])),
                       check=True, timeout=60)
        print("Validated search registry with " + locale, flush=True)
