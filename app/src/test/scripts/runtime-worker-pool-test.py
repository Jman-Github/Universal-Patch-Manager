"""Exercise the production worker pool against Binder death notifications."""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[4]
CACHE = Path(os.environ.get("GRADLE_USER_HOME", ROOT / ".gradle-user-home")) / "caches/modules-2/files-2.1"
JAVA = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-17")) / "bin/java.exe"


def artifact(group, name, version):
    return next((CACHE / group / name / version).glob("*/*.jar"))


compiler = [artifact("org.jetbrains.kotlin", name, "2.4.10") for name in (
    "kotlin-compiler-embeddable", "kotlin-stdlib", "kotlin-reflect", "kotlin-script-runtime")]
compiler += [artifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.10.2"),
             artifact("org.jetbrains", "annotations", "13.0")]
runtime_classpath = os.pathsep.join(map(str, [
    artifact("org.jetbrains.kotlin", "kotlin-stdlib", "2.4.10"),
    artifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.10.2")]))
fixtures = {
    "Binder.kt": """
package android.os
interface IBinder {
    fun interface DeathRecipient { fun binderDied() }
    fun linkToDeath(recipient: DeathRecipient, flags: Int)
    val isBinderAlive: Boolean
}
class ParcelFileDescriptor
class RemoteException : Exception()
""",
    "Context.kt": """
package android.content
import android.os.IBinder
import app.universal.patch.manager.patcher.runtime.jvm.FakeService
class ComponentName
class Intent(context: Context, component: Class<*>)
interface ServiceConnection {
    fun onServiceConnected(name: ComponentName, binder: IBinder)
    fun onServiceDisconnected(name: ComponentName)
    fun onNullBinding(name: ComponentName)
    fun onBindingDied(name: ComponentName)
}
class Context {
    val applicationContext get() = this
    val bindings = mutableMapOf<ServiceConnection, FakeService>()
    var failures = 0
    var connects = true
    var deathsDuringConnect = 0
    fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
        if (failures > 0) { failures--; return false }
        val service = FakeService()
        bindings[connection] = service
        if (connects) connection.onServiceConnected(ComponentName(), service)
        if (deathsDuringConnect > 0) {
            deathsDuringConnect--
            service.die()
        }
        return true
    }
    fun unbindService(connection: ServiceConnection) {
        bindings.remove(connection)?.die()
    }
    companion object { const val BIND_AUTO_CREATE = 1; const val BIND_IMPORTANT = 64 }
}
""",
    "Service.kt": """
package app.universal.patch.manager.patcher.runtime.jvm
import android.os.*
data class JvmLaunch(val libjvm: String, val preload: List<String>, val options: List<String>,
    val mainClass: String, val entryClass: String,
    val runtimeClassPath: String? = null, val nativeLibraryDir: String? = null)
interface IPatcherService {
    fun asBinder(): IBinder
    fun prepare(launch: JvmLaunch, bundles: Array<String>): Int
    fun run(request: String, events: ParcelFileDescriptor)
    object Stub { fun asInterface(binder: IBinder) = binder as IPatcherService }
}
class FakeService : IBinder, IPatcherService {
    private var recipient: IBinder.DeathRecipient? = null
    private var dead = false
    var deathLinks = 0
    // This cached flag never changes without a transaction, as on an unobserved BinderProxy.
    override val isBinderAlive get() = true
    override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
        if (dead) throw RemoteException()
        deathLinks++
        this.recipient = recipient
    }
    fun die() {
        if (dead) return
        dead = true
        recipient?.binderDied()
    }
    override fun asBinder() = this
    override fun prepare(launch: JvmLaunch, bundles: Array<String>): Int {
        if (dead) throw RemoteException()
        return 1234
    }
    override fun run(request: String, events: ParcelFileDescriptor) = Unit
}
open class PatcherService
class PatcherService1 : PatcherService()
class PatcherService2 : PatcherService()
class PatcherService3 : PatcherService()
""",
    "Failure.kt": """
package app.universal.patch.manager.patcher.runtime
class JvmUnavailableException(message: String) : Exception(message)
""",
    "Main.kt": """
import android.content.Context
import app.universal.patch.manager.patcher.runtime.*
import app.universal.patch.manager.patcher.runtime.jvm.JvmLaunch
import kotlinx.coroutines.*
fun main() = runBlocking {
    val context = Context()
    val pool = BoundArtRuntimes.get(context)
    val launch = JvmLaunch("libjvm", emptyList(), emptyList(), "main", "entry")
    withTimeout(3000) {
        repeat(16) { index ->
            val worker = if (index % 2 == 0) pool.take(RuntimeEngine.TOOLS, null, null)
                else pool.takeJvm(launch)
            worker.close()
            worker.close() // A second close must not insert another free slot.
        }
        val crashed = pool.takeJvm(launch)
        val (disconnected, service) = context.bindings.entries.single().let { it.key to it.value }
        service.die()
        disconnected.onServiceDisconnected(android.content.ComponentName())
        check(disconnected !in context.bindings) {
            "A dead disposable worker kept its auto-create binding and could reconnect"
        }
        val late = app.universal.patch.manager.patcher.runtime.jvm.FakeService()
        disconnected.onServiceConnected(android.content.ComponentName(), late)
        check(late.deathLinks == 0) { "A dead connection accepted another worker" }
        crashed.close()
        context.deathsDuringConnect = 6
        repeat(6) {
            try { pool.takeJvm(launch); error("Expected early worker death") }
            catch (_: android.os.RemoteException) {}
        }
        check(context.bindings.isEmpty())
        context.failures = 6
        repeat(6) {
            try { pool.take(RuntimeEngine.TOOLS, null, null); error("Expected bind failure") }
            catch (_: JvmUnavailableException) {}
        }
        context.connects = false
        repeat(6) {
            check(withTimeoutOrNull(20) { pool.take(RuntimeEngine.TOOLS, null, null) } == null)
        }
        context.connects = true
        val held = List(4) { pool.take(RuntimeEngine.TOOLS, null, null) }
        check(withTimeoutOrNull(20) { pool.takeJvm(launch) } == null)
        held.forEach { it.close() }
        repeat(8) { pool.takeJvm(launch).close() }
        val occupied = List(4) { pool.takeJvm(launch) }
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { pool.takeJvm(launch) }
        @Suppress("UNCHECKED_CAST")
        val slots = pool.javaClass.getDeclaredField("slots").apply { isAccessible = true }
            .get(pool) as kotlinx.coroutines.channels.Channel<Int>
        // Deliver a completed worker's slot while the receiver is queued on this event loop,
        // then cancel it before it resumes. Drain the simulated delivery before closing held workers.
        check(slots.trySend(0).isSuccess)
        waiting.cancelAndJoin()
        check(slots.tryReceive().getOrNull() == 0) { "Cancellation discarded a delivered worker slot" }
        occupied.forEach { it.close() }
        val available = List(4) { pool.takeJvm(launch) }
        check(withTimeoutOrNull(20) { pool.takeJvm(launch) } == null)
        available.forEach { it.close() }
    }
    check(context.bindings.isEmpty())
    println("PASS: mixed ART/JVM runs, crash teardown, late reconnect, early death, bind failure and slot-handoff cancellation")
}
"""
}

with tempfile.TemporaryDirectory(prefix="runtime-worker-test-", dir=ROOT / "build") as temporary:
    work = Path(temporary)
    sources = []
    for name, content in fixtures.items():
        source = work / name
        source.write_text(content)
        sources.append(source)
    sources += [ROOT / "app/src/main/java/app/universal/patch/manager/patcher/runtime/BoundArtRuntimes.kt",
                ROOT / "app/src/main/java/app/universal/patch/manager/patcher/runtime/RuntimeEngine.kt"]
    classes = work / "classes"
    subprocess.run([str(JAVA), "-cp", os.pathsep.join(map(str, compiler)),
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
        "-jvm-target", "17", "-classpath", runtime_classpath, "-d", str(classes),
        *map(str, sources)], check=True, timeout=90)
    subprocess.run([str(JAVA), "-cp", os.pathsep.join([str(classes), runtime_classpath]), "MainKt"],
                   check=True, timeout=10)
