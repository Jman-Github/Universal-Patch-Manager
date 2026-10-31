"""Exercise production action-setting callbacks against delayed, serialized storage.

Runs before assembleRelease. The callbacks are extracted from the Compose UI;
rendered state stays stale to reproduce clicks made before the next recomposition.
This checks persistence transitions, not device UI rendering.
"""
from pathlib import Path
import importlib.util
import os
import subprocess
import sys
import tempfile
import tomllib

ROOT = Path(__file__).resolve().parents[4]
BASE = ROOT / "app/src/main/java/app/universal/patch/manager"
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("compat", Path(__file__).with_name("urv-import-compat-test.py"))
compat = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compat)


def block(text, marker):
    start = text.index(marker)
    opening = text.index("{", start)
    end, depth = opening + 1, 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    return text[opening + 1:end - 1]


minimal = (BASE / "ui/screen/settings/MinimalActionSettings.kt").read_text(encoding="utf-8")
dashboard = (BASE / "ui/screen/settings/DashboardActionSettings.kt").read_text(encoding="utf-8")
minimal_callback = block(minimal, "onValueChange = { enabled ->").split("->", 1)[1]
visibility_callback = block(dashboard, "onCheckedChange = { shown ->").split("->", 1)[1]
move_callback = block(dashboard, "fun moveAction(")
harness = """
package regression
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*
import app.universal.patch.manager.ui.model.dashboardActionOrder

class Preference<T>(var value: T) {
    suspend fun get(): T { delay(5); return value }
    suspend fun update(value: T) { delay(5); this.value = value }
}
class Prefs {
    val minimalActionButtonGroups = Preference(emptySet<String>())
    val dashboardHiddenActions = Preference(emptySet<String>())
    val mutex = Mutex()
    suspend fun edit(block: Prefs.() -> Unit) {
        delay(5)
        mutex.withLock { block() }
    }
}
class Actions {
    val prefs = Prefs()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val group = "apps"
    val defaults = listOf("storage", "add", "batch")
    val order = defaults // Last rendered state, deliberately not refreshed between clicks.
    val hidden = emptySet<String>()
    val groups = emptySet<String>()
    val orderPref = Preference("storage,add,batch")
    fun minimal(group: String, enabled: Boolean) = %s
    fun visible(id: String, shown: Boolean) = %s
    fun moveAction(id: String, offset: Int) { %s }
    suspend fun awaitWrites() { scope.coroutineContext[Job]!!.children.toList().joinAll() }
}
fun main() = runBlocking {
    val actions = Actions()
    try {
        listOf("selection", "bundles", "saved", "batch", "profiles", "lsposed")
            .map { actions.minimal(it, true) }.joinAll()
        check(actions.prefs.minimalActionButtonGroups.value ==
            setOf("selection", "bundles", "saved", "batch", "profiles", "lsposed"))
        listOf(actions.minimal("selection", false), actions.minimal("saved", false)).joinAll()
        check(actions.prefs.minimalActionButtonGroups.value == setOf("bundles", "batch", "profiles", "lsposed"))

        listOf("storage", "add", "batch").map { actions.visible(it, false) }.joinAll()
        check(actions.prefs.dashboardHiddenActions.value == setOf("apps:storage", "apps:add", "apps:batch"))
        listOf(actions.visible("add", true), actions.visible("batch", true)).joinAll()
        check(actions.prefs.dashboardHiddenActions.value == setOf("apps:storage"))

        actions.moveAction("batch", -1)
        actions.moveAction("batch", -1)
        actions.awaitWrites()
        check(actions.orderPref.value == "batch,storage,add")
        repeat(4) { actions.moveAction("batch", -1) }
        actions.awaitWrites()
        check(actions.orderPref.value == "batch,storage,add")
        println("Concurrent minimal/visibility changes and repeated reorder callbacks passed.")
    } finally { actions.scope.cancel() }
}
""" % (minimal_callback, visibility_callback, move_callback)

discovery = (BASE / "ui/screen/PatchBundleDiscoveryPatchesScreen.kt").read_text(encoding="utf-8")
local_patch_call = discovery.split("                        PatchItem(", 1)[1].split("\n                        )", 1)[0]
assert "allowAppMetadataLookup = false" in local_patch_call
patch_item = (BASE / "ui/component/bundle/BundlePatchesDialog.kt").read_text(encoding="utf-8")
assert "if (allowAppMetadataLookup && !packageIsAny && packageName !in appMetadata)" in patch_item
print("Discovery API-only metadata guard passed.", flush=True)

# Check the live chip renderer rather than an unused legacy button helper.
selector = (BASE / "ui/screen/PatchesSelectorScreen.kt").read_text(encoding="utf-8")
chip = block(selector, "private fun PatchSelectionActionChip(")
assert 'if (!minimalActionButtons("selection")) Text(' in chip
assert "contentDescription = stringResource(spec.contentDescription)" in chip
row = block(selector, "private fun ActionChipRow(")
assert "PatchSelectionActionChip(" in row
print("Live patch-selection minimal-button and accessibility guards passed.", flush=True)

kotlin = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text(encoding="utf-8"))["versions"]["kotlin"]
artifact = compat.artifact
compiler = [artifact("org.jetbrains.kotlin", name, kotlin) for name in
            ("kotlin-compiler-embeddable", "kotlin-stdlib", "kotlin-reflect", "kotlin-script-runtime")]
deps = [artifact("org.jetbrains.kotlin", "kotlin-stdlib", kotlin), artifact("org.jetbrains", "annotations"),
        artifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm")]
compiler += deps
java = Path(r"C:\Program Files\Java\jdk-17\bin\java.exe")
cp = lambda paths: os.pathsep.join(map(str, paths))
with tempfile.TemporaryDirectory(prefix="easy-actions-", dir=ROOT / "build") as directory:
    work = Path(directory)
    source = work / "Actions.kt"
    source.write_text(harness, encoding="utf-8")
    classes = work / "classes"
    subprocess.run(list(map(str, [java, "-cp", cp(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", cp(deps), "-d", classes,
        source, BASE / "ui/model/DashboardActionOrder.kt"])), check=True, timeout=120)
    subprocess.run(list(map(str, [java, "-cp", cp([classes] + deps), "regression.ActionsKt"])),
                   check=True, timeout=60)
