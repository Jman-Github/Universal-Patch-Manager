/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */
package app.universal.patch.manager.patcher.runtime

// Code adapted from Morphe, see third-party/NOTICE for more information.
// https://github.com/MorpheApp/morphe-manager/commit/77af30c2e2711d6b745ffa435a5a7fa534277449
// https://github.com/MorpheApp/morphe-manager/commit/76b5477eca7a97b393a95cc5880d90d1ab47354a

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.Process
import app.universal.patch.manager.BuildConfig
import app.universal.patch.manager.data.platform.Filesystem
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.patcher.host.RuntimeProtocol
import app.universal.patch.manager.patcher.runtime.jvm.JvmInstallation
import app.universal.patch.manager.patcher.runtime.jvm.JvmLaunch
import app.universal.patch.manager.util.managerStorageContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File
import java.util.jar.JarFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** A disposable JVM worker per run, so exhausting its heap cannot take the manager with it. */
class PatcherJvm private constructor(
    private val app: Application,
    private val fs: Filesystem,
    private val prefs: PreferencesManager
) {
    private val installation = JvmInstallation(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runLock = Mutex()
    private val errorFile = app.managerStorageContext.filesDir.resolve("patcher-jvm-error.log")

    val isAvailable get() = installation.isAvailable
    suspend fun isEnabled() = isAvailable && prefs.useJvmRuntime.get()

    /** Warm the installation without keeping an unused worker or SDK heap alive. */
    fun startAhead() = scope.launch {
        if (isEnabled()) runCatching { installation.prepareRuntime() }
    }

    suspend fun run(engine: RuntimeEngine, runtimeClassPath: String?, request: File,
        cancelled: AtomicBoolean, cancelAction: AtomicReference<(() -> Unit)?>,
        onMessage: (String, Map<String, Any?>) -> Unit) = withContext(Dispatchers.IO) {
        runLock.withLock {
            fun checkCancelled() {
                if (cancelled.get()) throw CancellationException("Runtime cancelled")
            }
            checkCancelled()
            val installed = installation.install(requireNotNull(engine.host),
                if (engine != RuntimeEngine.TOOLS)
                    runtimeClassPath?.let(::File) else null)
            if (JarFile(installed.hostJar).use {
                    it.getJarEntry("app/universal/patch/manager/patcher/host/GcOverheadWatch.class") == null
                }) {
                throw JvmUnavailableException("This runtime plugin needs an update for disposable JVM workers")
            }
            val requestedHeapMb = heapLimitMb(app)
            val debug = if (BuildConfig.DEBUG) app.managerStorageContext.filesDir.resolve("jvm-options.txt")
                .takeIf { it.isFile }?.readLines()?.map(String::trim)
                ?.filter { it.isNotEmpty() && !it.startsWith("#") }.orEmpty() else emptyList()
            val tuning = JvmTuning.options(Process.is64Bit()) + debug
            val options = listOf(
                "-Djava.class.path=" + installed.hostJar, "-Djava.io.tmpdir=" + fs.tempDir,
                "-Djdk.lang.Process.launchMechanism=FORK", "-Xmx" + requestedHeapMb + "m", "-Xrs",
                "-XX:-UsePerfData", "-XX:ErrorFile=" + errorFile
            ) + tuning
            val launch = JvmLaunch(installed.libjvm.path, installed.preload, options,
                "app/universal/patch/manager/patcher/host/HostMainKt", engine.entry)
            val runtime = try {
                BoundArtRuntimes.get(app).takeJvm(launch)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                throw JvmUnavailableException("Could not prepare the embedded JVM", error)
            }
            val cancel: () -> Unit = { runtime.close() }
            cancelAction.set(cancel)
            try {
                runtime.use {
                    checkCancelled()
                    onMessage("log", mapOf("level" to "INFO", "message" to
                        "Runtime mode: bound JVM worker (OpenJDK ${installation.feature}); requested memory budget: ${requestedHeapMb}MB"))
                    onMessage("log", mapOf("level" to "INFO", "message" to
                        "Patcher process: pid=${runtime.pid} vm=JVM engine=${engine.name} heap=requested-${requestedHeapMb}MB bits=${if (Process.is64Bit()) 64 else 32} age=0s"))
                    onMessage("log", mapOf("level" to "INFO", "message" to
                        "Patcher JVM: libjvm=${installed.libjvm.name} options=${tuning.joinToString(" ")}"))
                    if (errorFile.isFile) {
                        onMessage("log", mapOf("level" to "ERROR", "message" to errorFile.reader().use {
                            val buffer = CharArray(262144)
                            val count = it.read(buffer)
                            if (count > 0) String(buffer, 0, count) else ""
                        }))
                        errorFile.delete()
                    }
                    val (events, sink) = ParcelFileDescriptor.createPipe()
                    val channel = try {
                        readEvents(events)
                    } catch (error: Throwable) {
                        events.close()
                        sink.close()
                        throw error
                    }
                    try {
                        sink.use { runtime.run(request.path, it) }
                        // No patch runs before "started". Only startup failures may fall back to ART.
                        val first = withTimeoutOrNull(30_000L) { channel.receiveCatching().getOrNull() }
                        checkCancelled()
                        if (first == null) throw JvmUnavailableException("The embedded JVM did not start reporting")
                        if (first["type"] == "startup_failed") {
                            throw JvmUnavailableException(first["error"] as? String ?: "The embedded JVM could not start")
                        }
                        check(first["type"] == "started") { "Unexpected JVM startup event" }
                        onMessage("started", first)
                        var memoryReported = false
                        for (event in channel) {
                            checkCancelled()
                            val type = event.getValue("type") as String
                            if (type == "finished") {
                                (event["error"] as? String)?.let {
                                    throw BoundRuntimeFailureException(it, originalStackTrace = it)
                                }
                                return@withLock
                            }
                            if (type == "memory" && !memoryReported) {
                                memoryReported = true
                                onMessage("log", mapOf("level" to "INFO", "message" to "Memory limit: ${event["max"]}MB"))
                            }
                            onMessage(type, event)
                        }
                        checkCancelled()
                        throw BoundRuntimeFailureException("The JVM worker ended during patching. Android may have stopped its process.")
                    } finally {
                        channel.cancel()
                        events.close()
                    }
                }
            } catch (error: Throwable) {
                checkCancelled()
                throw error
            } finally {
                cancelAction.compareAndSet(cancel, null)
                runtime.close()
            }
        }
    }

    private fun readEvents(events: ParcelFileDescriptor): Channel<Map<String, Any?>> {
        val channel = Channel<Map<String, Any?>>(64)
        thread(isDaemon = true, name = "JvmEvents") {
            try {
                ParcelFileDescriptor.AutoCloseInputStream(events).bufferedReader().useLines { lines ->
                    lines.forEach {
                        @Suppress("UNCHECKED_CAST")
                        val value = RuntimeProtocol.decode(Json.parseToJsonElement(it)) as Map<String, Any?>
                        runBlocking { channel.send(value) }
                    }
                }
                channel.close()
            } catch (error: Exception) { channel.close(error) }
        }
        return channel
    }

    companion object {
        fun heapLimitMb(context: Context): Int {
            val info = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
            return JvmHeapPolicy.limitMb(info.totalMem / 1048576, Process.is64Bit())
        }
        @Volatile private var instance: PatcherJvm? = null
        fun get(context: Context): PatcherJvm = instance ?: synchronized(this) {
            instance ?: run {
                val koin = org.koin.core.context.GlobalContext.get()
                PatcherJvm(context.applicationContext as Application, koin.get(), koin.get())
                    .also { instance = it }
            }
        }
    }
}
