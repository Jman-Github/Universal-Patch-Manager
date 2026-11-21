"""Regressions for #846/#847. Flow checks execute production code; UI checks are structural."""
from pathlib import Path
import subprocess
import re
import sqlite3
import tempfile

ROOT = Path(__file__).resolve().parents[4]
BASE = ROOT / "app/src/main/java/app/universal/patch/manager"
def source(path):
    return (BASE / path).read_text(encoding="utf-8")

dialog = source("ui/component/AlertDialogExtended.kt").split("fun CollectionDialog(", 1)[1]
assert ".heightIn(max = 560.dp)" in dialog and "fillText = true" not in dialog
for file, name in (("BundleListScreen.kt", "BundleOrderDialog"),
                   ("InstalledAppsScreen.kt", "AppsOrderDialog"),
                   ("PatchProfilesScreen.kt", "PatchProfilesOrderDialog"),
                   ("PatchesSelectorScreen.kt", "PatchProfileBundleDialog")):
    body = source("ui/screen/" + file).split("private fun " + name, 1)[1]
    body = body.split("CollectionDialog(", 1)[1]
    assert "fillMaxSize()" not in body.split("LazyColumnWithScrollbar(", 1)[0]
    assert ".weight(1f, fill = false)" in body
name_dialog = source("ui/screen/PatchesSelectorScreen.kt").split("private fun PatchProfileNameDialog(", 1)[1]
assert name_dialog.index("TextField(") < name_dialog.index("LazyColumnWithScrollbar(")
assert "items = profiles" in name_dialog and "onProfileSelected(if (selected) null else profile)" in name_dialog
assert "Box(Modifier.matchParentSize())" in source("ui/component/LazyColumnWithScrollbar.kt")
buttons = source("ui/screen/settings/DashboardActionSettings.kt")
assert "FlowRow(" not in buttons
assert "verticalAlignment = Alignment.CenterVertically" in buttons.split("defaults.forEach", 1)[1]

flow = source("ui/viewmodel/PatchesSelectorViewModel.kt").split("    val profiles = ", 1)[1]
flow = flow.split("\n    private suspend fun resolveAppVersion", 1)[0]
namespace = {"__file__": str(Path(__file__).with_name("bundle-ui-issues-test.py"))}
exec(Path(namespace["__file__"]).read_text().split('(ROOT / "build").mkdir')[0], namespace)
compiler, deps, JAVA, cp = (namespace[k] for k in ("compiler", "deps", "JAVA", "cp"))
deps = deps + [namespace["artifact"]("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm")]
dao = source("data/room/profile/PatchProfileDao.kt")
guard = dao.split("    suspend fun updateRemappedPayload(", 1)[1].split("\n    @Query", 1)[0]
guard = "suspend fun updateRemappedPayload(" + guard
query = re.search(r'@Query\("([^"]+)"\)\s*suspend fun updatePayload', dao).group(1)
# Exercise the production SQL: background maintenance can only modify payload.
db = sqlite3.connect(":memory:")
db.execute("CREATE TABLE patch_profiles(uid INTEGER PRIMARY KEY, payload TEXT, name TEXT, apk_path TEXT)")
db.execute("INSERT INTO patch_profiles VALUES(1,'old','new user name','retained.apk')")
db.execute(query, dict(uid=1, payload="remapped"))
assert db.execute("SELECT * FROM patch_profiles").fetchone() == (1, "remapped", "new user name", "retained.apk")
db.close()
harness = """
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
data class Payload(val remapped: Boolean = false)
data class Profile(val uid: Int, val packageName: String = "app", val appVersion: String? = null,
                   val name: String = "Saved profile", val payload: Payload = Payload())
data class Patch(val name: String)
data class Info(val patches: List<Patch>)
class ProfileRepository {
    val saved = MutableStateFlow(listOf(Profile(1)))
    val updates = Channel<Payload>(Channel.UNLIMITED)
    fun profilesForPackageFlow(packageName: String) = saved
    suspend fun updateRemappedPayload(uid: Int, expectedPayload: Payload, payload: Payload): Boolean {
        updates.send(payload)
        return true
    }
}
class BundleRepository {
    val bundleInfoFlow = MutableSharedFlow<Map<Int, Info>>(replay = 1)
    val sources = MutableStateFlow(listOf(1))
}
val remappingStarted = java.util.concurrent.CountDownLatch(1)
val releaseRemapping = java.util.concurrent.CountDownLatch(1)
val collectorThread = Thread.currentThread()
fun Payload.remapLocalBundles(sources: List<Int>, signatureMap: Map<Int, Set<String>>): Payload {
    check(Thread.currentThread() !== collectorThread) { "Runtime inspection ran on the collector thread" }
    remappingStarted.countDown()
    check(releaseRemapping.await(5, java.util.concurrent.TimeUnit.SECONDS))
    return copy(remapped = true)
}
@OptIn(ExperimentalCoroutinesApi::class)
class Model(val viewModelScope: CoroutineScope) {
    val patchProfileRepository = ProfileRepository()
    val patchBundleRepository = BundleRepository()
    val packageName = "app"
    val profiles = FLOW
}
typealias PatchProfilePayload = Payload
class GuardDao {
    var row: Profile? = Profile(1)
    suspend fun get(uid: Int) = row?.takeIf { it.uid == uid }
    suspend fun updatePayload(uid: Int, payload: Payload) {
        row = row?.takeIf { it.uid == uid }?.copy(payload = payload)
    }
    GUARD
}
fun main() = runBlocking {
    val dao = GuardDao()
    val inspected = dao.row!!.payload
    dao.row = dao.row!!.copy(name = "Renamed", appVersion = "2")
    check(dao.updateRemappedPayload(1, inspected, Payload(true)))
    check(dao.row!!.name == "Renamed" && dao.row!!.appVersion == "2")
    check(!dao.updateRemappedPayload(1, inspected, Payload(true))) // Changed selection wins.
    dao.row = null
    check(!dao.updateRemappedPayload(1, inspected, Payload(true))) // Do not recreate deleted profiles.
    val model = Model(this)
    val received = Channel<List<Profile>>(Channel.UNLIMITED)
    val job = launch { model.profiles.collect { received.send(it) } }
    // Bundle metadata has not emitted, but the existing profile must already be visible.
    check(withTimeout(2000) { received.receive() }.single().uid == 1)
    model.patchBundleRepository.bundleInfoFlow.emit(emptyMap())
    withContext(Dispatchers.IO) {
        check(remappingStarted.await(2, java.util.concurrent.TimeUnit.SECONDS))
    }
    // The list was emitted before this deliberately blocked inspection can finish.
    releaseRemapping.countDown()
    check(withTimeout(2000) { received.receive() }.single().payload.remapped)
    check(withTimeout(2000) { model.patchProfileRepository.updates.receive() }.remapped)
    model.patchProfileRepository.saved.value = emptyList()
    check(withTimeout(2000) { received.receive() }.isEmpty())
    job.cancelAndJoin()
    println("PASS: saved profiles show before metadata loads, remapping runs off-thread, updates persist, deletion clears the list")
}
""".replace("FLOW", flow).replace("GUARD", guard)
(ROOT / "build").mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix="collection-dialog-", dir=ROOT / "build") as temporary:
    work = Path(temporary)
    main = work / "Main.kt"
    main.write_text(harness, encoding="utf-8")
    classes = work / "classes"
    subprocess.run(list(map(str, [JAVA, "-cp", cp(compiler),
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
        "-jvm-target", "17", "-classpath", cp(deps), "-d", classes, main])),
        check=True, timeout=90)
    subprocess.run(list(map(str, [JAVA, "-cp", cp([classes] + deps), "MainKt"])), check=True, timeout=30)
print("Collection height, input placement, profile selection and side-by-side button contracts passed.")
