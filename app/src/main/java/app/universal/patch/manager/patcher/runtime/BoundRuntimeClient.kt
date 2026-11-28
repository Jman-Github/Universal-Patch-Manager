/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */
package app.universal.patch.manager.patcher.runtime

// Code adapted from Morphe, see third-party/NOTICE for more information.
// https://github.com/MorpheApp/morphe-manager/pull/1202
// https://github.com/MorpheApp/morphe-manager/pull/1212
// https://github.com/MorpheApp/morphe-manager/pull/1223
// https://github.com/MorpheApp/morphe-manager/pull/1246

import android.content.Context
import android.os.ParcelFileDescriptor
import app.universal.patch.manager.patcher.host.RuntimeProtocol
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class BoundRuntimeClient(private val context: Context) {
    private val artProcess = AtomicReference<Process?>()
    private val cancelled = AtomicBoolean()
    private val active = AtomicReference<BoundArtRuntimes.ArtRuntime?>()
    private val cancelJvm = AtomicReference<(() -> Unit)?>()
    fun cancel() {
        cancelled.set(true)
        artProcess.getAndSet(null)?.destroy()
        runCatching { cancelJvm.get()?.invoke() }
        active.getAndSet(null)?.close()
    }
    private fun checkCancelled() {
        if (cancelled.get()) throw CancellationException("Runtime cancelled")
    }

    suspend fun execute(engine: RuntimeEngine, params: Map<String, Any?>,
        bundles: List<String> = emptyList(), runtimeClassPath: String? = null,
        nativeLibraryDir: String? = null,
        onMessage: (String, Map<String, Any?>) -> Unit) {
        checkCancelled()
        // Keep ownership outside the cancellable dispatcher switch so a canceled write cannot
        // discard the only reference to its temporary file.
        val request = File.createTempFile("runtime-request-", ".json", context.cacheDir)
        try {
            withContext(Dispatchers.IO) {
                // Tools jobs (split merging/signature operations) do not supply a patcher cache.
                // Standalone ART needs one before loading any runtime, including built-in tools.
                val requestParams = params + mapOf(
                    "cacheDir" to ((params["cacheDir"] as? String) ?: context.cacheDir.path),
                    "device" to deviceFacts()
                )
                request.writeText(RuntimeProtocol.encode(requestParams).toString())
            }
            if (engine.host != null && PatcherJvm.get(context).isEnabled()) {
                try {
                    PatcherJvm.get(context).run(engine, runtimeClassPath, request, cancelled, cancelJvm, onMessage)
                    return
                } catch (error: JvmUnavailableException) {
                    checkCancelled()
                    onMessage("log", mapOf("level" to "WARN", "message" to
                        ("JVM unavailable; using ART: " + error.message +
                            " (cause=" + (error.cause?.javaClass?.simpleName ?: error.javaClass.simpleName) + ")")))
                }
            }
            checkCancelled()
            // Older Android releases do not support our startup heap override.
            // Preserve their Android-managed large-heap service and its lifecycle.
            if (android.os.Build.VERSION.SDK_INT < 30) {
                val runtime = BoundArtRuntimes.get(context).take(engine, runtimeClassPath, nativeLibraryDir)
                active.set(runtime)
                runtime.use {
                    checkCancelled()
                    onMessage("log", mapOf("level" to "INFO", "message" to
                        "Runtime mode: bound ART service; memory budget: Android managed"))
                    onMessage("log", mapOf("level" to "INFO", "message" to
                        ("Patcher process: pid=${runtime.pid} vm=ART engine=${engine.name} heap=Android-managed " +
                            "bits=${if (android.os.Process.is64Bit()) 64 else 32} " +
                            "abis=${android.os.Build.SUPPORTED_ABIS.joinToString(",")} age=0s")))
                    onMessage("log", mapOf("level" to "INFO", "message" to "Memory override: disabled"))
                    val (events, sink) = ParcelFileDescriptor.createPipe()
                    try {
                        sink.use { runtime.run(request.path, it) }
                        relay(ParcelFileDescriptor.AutoCloseInputStream(events).bufferedReader(), onMessage)
                    } finally { events.close() }
                }
                return
            }
            val prefs = org.koin.java.KoinJavaComponent.get<app.universal.patch.manager.domain.manager.PreferencesManager>(
                app.universal.patch.manager.domain.manager.PreferencesManager::class.java)
            val limit = prefs.artMemoryLimit.get()
            val requestedHeap = MemoryLimitConfig.clampConfiguredMemoryLimitMb(limit).coerceAtMost(
                if (android.os.Process.is64Bit()) 2048 else 1024)
            var heap = requestedHeap
            val binary = if (android.os.Process.is64Bit()) "/system/bin/app_process64" else "/system/bin/app_process32"
            val builder = ProcessBuilder(binary, "-Djava.io.tmpdir=${context.cacheDir}", "/",
                ArtProcessMain::class.java.name, request.path, engine.entry,
                runtimeClassPath.orEmpty(), nativeLibraryDir.orEmpty())
            builder.environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir +
                builder.environment()["LD_LIBRARY_PATH"]?.let { ":$it" }.orEmpty()
            builder.environment()["CLASSPATH"] = context.applicationInfo.sourceDir
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val library = File(context.applicationInfo.nativeLibraryDir, "libprop_override.so")
                check(library.isFile) { "ART heap override library is missing" }
                builder.environment()["LD_PRELOAD"] = library.path
                builder.environment()["PROP_dalvik.vm.heapsize"] = "${heap}M"
                builder.environment()["PROP_dalvik.vm.heapgrowthlimit"] = "${heap}M"
            }
            // Android 16 x86_64 ART JIT crashes with high heap references in Reseam,
            // Morphe and ReVanced. Keep all ART workers on the verified interpreter
            // path on this platform; JVM workers never enter this launcher.
            val artJitWorkaround =
                android.os.Build.VERSION.SDK_INT == 36 &&
                android.os.Build.SUPPORTED_ABIS.firstOrNull() == "x86_64"
            if (artJitWorkaround) {
                builder.environment()["PROP_dalvik.vm.usejit"] = "false"
                builder.environment()["PROP_dalvik.vm.usejitprofiles"] = "false"
            }
            val stderr = File.createTempFile("art-stderr-", ".log", context.cacheDir)
            try {
                builder.redirectError(stderr)
                while (true) {
                    checkCancelled()
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        builder.environment()["PROP_dalvik.vm.heapsize"] = "${heap}M"
                        builder.environment()["PROP_dalvik.vm.heapgrowthlimit"] = "${heap}M"
                    }
                    val startupAt = android.os.SystemClock.elapsedRealtime()
                    val process = withContext(NonCancellable + Dispatchers.IO) { builder.start().also { artProcess.set(it) } }
                    try {
                        checkCancelled()
                        onMessage("log", mapOf("level" to "INFO", "message" to
                            "Runtime mode: standalone ART; requested heap=${heap}MB; override=${android.os.Build.VERSION.SDK_INT >= 30}"))
                        onMessage("log", mapOf("level" to "INFO", "message" to "Memory override: enabled (${heap}MB)"))
                        if (artJitWorkaround) onMessage("log", mapOf("level" to "INFO", "message" to
                            "ART JIT: disabled on Android 16 x86_64 (large-heap crash workaround)"))
                        relay(process.inputStream.bufferedReader(), onMessage)
                        break
                    } catch (startup: JvmUnavailableException) {
                        checkCancelled()
                        heap = nextArtHeapMb(heap, android.os.SystemClock.elapsedRealtime() - startupAt)
                            ?: throw startup
                        onMessage("log", mapOf("level" to "WARN", "message" to
                            "ART could not start; retrying this run with ${heap}MB heap. The configured ${limit}MB limit is unchanged."))
                    } catch (failure: BoundRuntimeFailureException) {
                        if (failure.originalStackTrace != null) throw failure
                        val exitCode = withContext(Dispatchers.IO) {
                            if (process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) process.exitValue() else null
                        }
                        if (exitCode == null) throw failure
                        val reason = when (exitCode) {
                            139 -> "native crash (SIGSEGV)"
                            134 -> "runtime abort (SIGABRT)"
                            137 -> "process killed (SIGKILL)"
                            else -> "unexpected worker termination"
                        }
                        throw BoundRuntimeFailureException("ART worker exited with code $exitCode: $reason. ${failure.message}")
                    } finally {
                        artProcess.compareAndSet(process, null)
                        process.destroy()
                        withContext(NonCancellable + Dispatchers.IO) {
                            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
                        }
                    }
                }
            } finally {
                try {
                    if (stderr.length() > 0) onMessage("log", mapOf("level" to "WARN", "message" to readDiagnosticTail(stderr)))
                } finally { stderr.delete() }
            }
        } finally {
            active.getAndSet(null)?.close()
            withContext(NonCancellable + Dispatchers.IO) {
                // Cancellation can win the dispatcher handoff after builder.start() but
                // before its result enters the per-attempt try/finally. That worker was
                // published in artProcess and still belongs to this execution.
                try {
                    artProcess.getAndSet(null)?.let { orphan ->
                        orphan.destroy()
                        if (!orphan.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) orphan.destroyForcibly()
                    }
                } finally { request.delete() }
            }
        }
    }

    private suspend fun relay(events: java.io.BufferedReader, onMessage: (String, Map<String, Any?>) -> Unit) {
        val channel = Channel<Map<String, Any?>>(64)
        thread(isDaemon = true, name = "RuntimeEvents") {
            try {
                events.useLines { lines ->
                    lines.forEach {
                        @Suppress("UNCHECKED_CAST")
                        val value = RuntimeProtocol.decode(Json.parseToJsonElement(it)) as Map<String, Any?>
                        runBlocking { channel.send(value) }
                    }
                }
                channel.close()
            } catch (error: Exception) { channel.close(error) }
        }
        var started = false
        var memoryReported = false
        try {
            val first = withTimeoutOrNull(30_000L) { channel.receiveCatching().getOrNull() }
            checkCancelled()
            var event = first
                ?: throw JvmUnavailableException("The ART worker did not start reporting within 30 seconds")
            while (true) {
                checkCancelled()
                val type = event.getValue("type") as String
                if (type == "started") started = true
                if (type == "finished") {
                    (event["error"] as? String)?.let { throw BoundRuntimeFailureException(it, originalStackTrace = it) }
                    return
                }
                if (type == "memory" && !memoryReported) {
                    memoryReported = true
                    onMessage("log", mapOf("level" to "INFO", "message" to "Memory limit: ${event["max"]}MB"))
                }
                onMessage(type, event)
                event = channel.receiveCatching().getOrNull() ?: break
            }
        } finally {
            channel.cancel()
            // readLine holds the reader lock while waiting for the worker. Closing the
            // reader here can deadlock before the caller gets to terminate that worker.
            // The producer's useLines closes it after worker shutdown ends the read.
        }
        checkCancelled()
        if (!started) throw JvmUnavailableException("The runtime ended before the job started")
        throw BoundRuntimeFailureException("The runtime ended during the job. Android may have stopped its process.")
    }

    private fun readDiagnosticTail(file: File): String = runCatching {
        java.io.RandomAccessFile(file, "r").use { input ->
            val count = minOf(input.length(), 8192L).toInt()
            input.seek(input.length() - count)
            val bytes = ByteArray(count)
            input.readFully(bytes)
            bytes.toString(Charsets.UTF_8)
        }
    }.getOrElse { "Unable to read ART diagnostics: ${it.message}" }

    private fun deviceFacts(): Map<String, Any?> = mapOf(
        "sdk" to RuntimeDevice.sdk, "fingerprint" to RuntimeDevice.fingerprint,
        "abis" to RuntimeDevice.supportedAbis, "locales" to RuntimeDevice.locales.map { it.toLanguageTag() },
        "density" to RuntimeDevice.densityDpi)
}
