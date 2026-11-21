/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */
package app.universal.patch.manager.patcher.runtime

// Code adapted from Morphe, see third-party/NOTICE for more information.
// https://github.com/MorpheApp/morphe-manager/commit/77af30c2e2711d6b745ffa435a5a7fa534277449
// https://github.com/MorpheApp/morphe-manager/commit/76b5477eca7a97b393a95cc5880d90d1ab47354a

// https://github.com/MorpheApp/morphe-manager/commit/ded5f5bcbdb7c47d3dc551a882b4546dcf803b8c
// https://github.com/MorpheApp/morphe-manager/pull/1474

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
    private val aotBusy = AtomicBoolean(false)
    private val errorFile = app.managerStorageContext.filesDir.resolve("patcher-jvm-error.log")

    val isAvailable get() = installation.isAvailable
    suspend fun isEnabled() = isAvailable && prefs.useJvmRuntime.get()

    /** Warm the installation without keeping an unused worker or SDK heap alive. */
    fun startAhead() = scope.launch {
        if (isEnabled()) runCatching { installation.prepareRuntime() }
    }

    suspend fun run(engine: RuntimeEngine, runtimeClassPath: String?, request: File,
        cancelled: AtomicBoolean, cancelAction: AtomicReference<(() -> Unit)?>,
        onMessage: (String, Map<String, Any?>) -> Unit) {
        var heap = heapLimitMb(app)
        var allowAot = true
        while (true) {
            try {
                runAttempt(engine, runtimeClassPath, request, cancelled, cancelAction, onMessage, heap, allowAot)
                return
            } catch (failure: StartupFailure) {
                currentCoroutineContext().ensureActive()
                if (cancelled.get()) throw CancellationException("Runtime cancelled")
                val retryHeap = JvmHeapPolicy.minimumMb(Process.is64Bit())
                when {
                    failure.heapNotReserved && heap > retryHeap -> {
                        heap = retryHeap
                        onMessage("log", mapOf("level" to "WARN", "message" to
                            "${failure.message}. Retrying the JVM with ${heap}MB heap"))
                    }
                    !failure.heapNotReserved && allowAot && failure.usedAot -> {
                        allowAot = false
                        onMessage("log", mapOf("level" to "WARN", "message" to
                            "${failure.message}. Retrying the JVM without the AOT cache"))
                    }
                    else -> throw JvmUnavailableException(failure.message.orEmpty(), failure)
                }
            }
        }
    }

    private class StartupFailure(
        message: String,
        cause: Throwable? = null,
        val heapNotReserved: Boolean = false,
        val usedAot: Boolean = false
    ) : Exception(message, cause)

    private suspend fun runAttempt(engine: RuntimeEngine, runtimeClassPath: String?, request: File,
        cancelled: AtomicBoolean, cancelAction: AtomicReference<(() -> Unit)?>,
        onMessage: (String, Map<String, Any?>) -> Unit, requestedHeapMb: Int, allowAot: Boolean) = withContext(Dispatchers.IO) {
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
            val debug = if (BuildConfig.DEBUG) app.managerStorageContext.filesDir.resolve("jvm-options.txt")
                .takeIf { it.isFile }?.readLines()?.map(String::trim)
                ?.filter { it.isNotEmpty() && !it.startsWith("#") }.orEmpty() else emptyList()
            // A debug override can change cache-sensitive flags; do not cache those runs.
            val aot = if (allowAot && debug.isEmpty() && !aotBusy.get()) runCatching {
                JvmAotCache(app.managerStorageContext.cacheDir.resolve("jvm-aot"),
                    installed.home, installed.hostJar).also { it.prepare() }
            }.getOrNull() else null
            val recording = aot != null && !aot.cache.isFile &&
                runCatching { aot.canRecord() }.getOrDefault(false) && aotBusy.compareAndSet(false, true)
            val aotOptions = when {
                recording -> aot!!.recordOptions()
                aot?.cache?.isFile == true -> aot.useOptions()
                else -> emptyList()
            }
            val tuning = JvmTuning.options(Process.is64Bit(), aotOptions) + debug
            val options = listOf(
                "-Djava.class.path=" + installed.hostJar, "-Djava.io.tmpdir=" + fs.tempDir,
                // Bundled JNI libraries load from unnamed modules.
                // https://github.com/MorpheApp/morphe-manager/commit/e785b35cc5159a9f65e28b3d240f345d3c6555d3
                "--enable-native-access=ALL-UNNAMED",
                "-Djdk.lang.Process.launchMechanism=FORK", "-Xmx" + requestedHeapMb + "m",
                // https://github.com/MorpheApp/morphe-manager/commit/42ddd283dc17c44937711dc28a242f5759a6a30b
                "-Xms${minOf(512, requestedHeapMb)}m", "-Xrs",
                "-XX:-UsePerfData", "-XX:ErrorFile=" + errorFile
            ) + tuning
            val launch = JvmLaunch(installed.command, options,
                "app.universal.patch.manager.patcher.host.HostMainKt", engine.entry)
            errorFile.delete()
            val runtime = try {
                BoundArtRuntimes.get(app).takeJvm(launch)
            } catch (error: Throwable) {
                if (recording) aotBusy.set(false)
                if (error is CancellationException) throw error
                if (aotOptions.isNotEmpty()) {
                    runCatching { aot?.failed(error) }
                    aot?.discardRecording()
                    throw StartupFailure("Could not prepare the embedded JVM", error, usedAot = true)
                }
                throw JvmUnavailableException("Could not prepare the embedded JVM", error)
            }
            val cancel: () -> Unit = { runtime.close() }
            cancelAction.set(cancel)
            var succeeded = false
            var started = false
            try {
                checkCancelled()
                val (events, sink) = ParcelFileDescriptor.createPipe()
                val channel = readEvents(events)
                try {
                    val pid = try {
                        sink.use { runtime.run(request.path, it) }
                    } catch (_: android.os.RemoteException) {
                        // A fast JVM failure may close Binder before the reply, with diagnostics
                        // already in the pipe. Read it before deciding whether to fall back.
                        0
                    }
                    val first = withTimeoutOrNull(30_000L) { channel.receiveCatching().getOrNull() }
                    checkCancelled()
                    val earlyExit = first?.let(JvmProcessExit::fromEvent)
                    if (earlyExit != null) {
                        throw StartupFailure(earlyExit.describe("The JVM ended before starting"),
                            heapNotReserved = earlyExit.heapNotReserved, usedAot = aotOptions.isNotEmpty())
                    }
                    if (first?.get("type") != "started") {
                        throw StartupFailure(first?.get("error") as? String ?: "The embedded JVM did not start reporting",
                            usedAot = aotOptions.isNotEmpty())
                    }
                    started = true
                    onMessage("log", mapOf("level" to "INFO", "message" to
                        "Runtime mode: bound JVM worker (Liberica 25); requested memory budget: ${requestedHeapMb}MB"))
                    onMessage("log", mapOf("level" to "INFO", "message" to
                        "Patcher process: pid=$pid servicePid=${runtime.pid} vm=JVM engine=${engine.name} heap=requested-${requestedHeapMb}MB bits=${if (Process.is64Bit()) 64 else 32} age=0s"))
                    onMessage("log", mapOf("level" to "INFO", "message" to
                        "Patcher JVM options: ${tuning.joinToString(" ")}"))
                    onMessage("started", first)
                    var memoryReported = false
                    for (event in channel) {
                        checkCancelled()
                        JvmProcessExit.fromEvent(event)?.let {
                            val diagnostic = readCrashReport()
                            if (diagnostic.isNotBlank()) onMessage("log", mapOf("level" to "ERROR", "message" to diagnostic))
                            throw BoundRuntimeFailureException(it.describe("The JVM ended during patching"))
                        }
                        val type = event.getValue("type") as String
                        if (type == "finished") {
                            (event["error"] as? String)?.let {
                                throw BoundRuntimeFailureException(it, originalStackTrace = it)
                            }
                            succeeded = true
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
                }
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                checkCancelled()
                if (started && error is JvmUnavailableException) {
                    throw BoundRuntimeFailureException(error.message.orEmpty()).apply { initCause(error) }
                }
                val failure = if (!started && (error is java.io.IOException || error is IllegalStateException)) {
                    StartupFailure("Could not start the embedded JVM: ${error.message}", error,
                        usedAot = aotOptions.isNotEmpty())
                } else error
                if (failure is StartupFailure && aotOptions.isNotEmpty() && !failure.heapNotReserved) {
                    runCatching { aot?.failed(failure) }
                }
                throw failure
            } finally {
                cancelAction.compareAndSet(cancel, null)
                if (recording && succeeded) {
                    // Do not unbind while HotSpot writes its recording during System.exit.
                    // Queue for the lock before returning the result. A subsequent run must
                    // not compete with cache creation for another large JVM heap.
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        runLock.withLock {
                            try {
                                withTimeout(120_000L) { runtime.awaitExit() }
                                runtime.close()
                                createAotCache(aot!!, launch)
                            } catch (error: Exception) {
                                runCatching { aot?.failed(error) }
                            } finally {
                                runtime.close()
                                aot?.discardRecording()
                                aotBusy.set(false)
                            }
                        }
                    }
                } else {
                    runtime.close()
                    if (recording) {
                        aot?.discardRecording()
                        aotBusy.set(false)
                    }
                }
            }
        }
    }

    // Adapted from Morphe commit ded5f5bcbdb7c47d3dc551a882b4546dcf803b8c.
    private suspend fun createAotCache(aot: JvmAotCache, launch: JvmLaunch) {
        if (!aot.directory.isDirectory) return // Cache cleared during recording.
        check(aot.configuration.isFile) { "The JVM recorded no AOT configuration" }
        val tuning = JvmTuning.options(Process.is64Bit(), aot.createOptions())
        val createLaunch = launch.copy(options = launch.options.filterNot {
            it.startsWith("-XX:AOT") || it.startsWith("-Xlog:aot") ||
                it.startsWith("-XX:TieredStopAtLevel")
        } + tuning)
        val worker = BoundArtRuntimes.get(app).takeJvm(createLaunch)
        try {
            val (events, sink) = ParcelFileDescriptor.createPipe()
            val channel = readEvents(events)
            try {
                try {
                    sink.use { worker.run("", it) }
                } catch (_: android.os.RemoteException) {
                    // AOTMode=create can exit before a Binder reply arrives.
                }
                withTimeout(120_000L) {
                    var exit: JvmProcessExit? = null
                    for (event in channel) {
                        check(event["type"] != "started") { "The AOT cache process unexpectedly ran the patcher host" }
                        JvmProcessExit.fromEvent(event)?.let { exit = it }
                    }
                    val result = checkNotNull(exit) { "The AOT cache process did not report its exit" }
                    check(result.status == 0) { result.describe("AOT cache creation failed") }
                    worker.awaitExit()
                }
            } finally { channel.cancel() }
            check(aot.staging.isFile && aot.staging.length() > 0) { "The JVM made no AOT cache" }
            check(aot.staging.renameTo(aot.cache)) { "Could not publish AOT cache" }
        } finally { worker.close() }
    }

    private fun readCrashReport(): String = runCatching {
        errorFile.reader().use {
            val buffer = CharArray(65536)
            val count = it.read(buffer)
            if (count > 0) String(buffer, 0, count) else ""
        }
    }.getOrDefault("")

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
