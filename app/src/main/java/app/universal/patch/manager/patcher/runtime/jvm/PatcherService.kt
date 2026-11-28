/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */
package app.universal.patch.manager.patcher.runtime.jvm

// Code adapted from Morphe, see third-party/NOTICE for more information.
// https://github.com/MorpheApp/morphe-manager/commit/76b5477eca7a97b393a95cc5880d90d1ab47354a
// https://github.com/MorpheApp/morphe-manager/pull/1474

import android.app.Service
import android.util.Log
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.Process
import app.universal.patch.manager.patcher.host.RuntimeProtocol
import java.util.concurrent.CompletableFuture
import kotlin.system.exitProcess
import kotlin.concurrent.thread

/** Disposable worker: one ART or embedded JVM run, isolated from the manager. */
open class PatcherService : Service() {
    private var running = false
    private var launch: JvmLaunch? = null
    private val binder = object : IPatcherService.Stub() {
        @Synchronized
        override fun prepare(launch: JvmLaunch, bundles: Array<String>): Int {
            check(this@PatcherService.launch == null) { "The worker runs once per process" }
            this@PatcherService.launch = launch
            return Process.myPid()
        }

        @Synchronized
        override fun run(requestFile: String, events: ParcelFileDescriptor): Int {
            val launch = checkNotNull(this@PatcherService.launch) { "The runtime was not prepared" }
            check(!running) { "The worker runs once per process" }
            running = true
            if (launch.command.isNotEmpty()) {
                return startJvm(launch, requestFile, events)
            }
            thread(name = "BoundPatcherRuntime") {
                try {
                    RuntimeProtocol.run(requestFile, "/proc/self/fd/" + events.fd) { params, send ->
                        android.system.Os.setenv("TMPDIR", params["cacheDir"] as? String ?: cacheDir.path, true)
                        val loader = launch.runtimeClassPath?.let { path ->
                            dalvik.system.DexClassLoader(path, codeCacheDir.path, launch.nativeLibraryDir,
                                classLoader.parent)
                        } ?: classLoader
                        val device = try {
                            loader.loadClass("app.universal.patch.manager.patcher.runtime.RuntimeDevice")
                        } catch (_: ClassNotFoundException) { null }
                        device?.methods?.firstOrNull { it.name == "configure" && it.parameterCount == 1 }
                            ?.invoke(device.getField("INSTANCE").get(null), params["device"])
                        RuntimeProtocol.invoke(loader.loadClass(launch.entryClass), params, send)
                    }
                } finally { Process.killProcess(Process.myPid()) }
            }
            return Process.myPid()
        }
    }

    // Adapted from Morphe PR #1474 for UPM's file-based requests and JSON event pipe.
    private fun startJvm(launch: JvmLaunch, requestFile: String, events: ParcelFileDescriptor): Int {
        val (output, outputSink) = try {
            ParcelFileDescriptor.createPipe()
        } catch (error: Exception) {
            events.close()
            throw error
        }
        val tail = ArrayDeque<String>()
        val reader = thread(isDaemon = true, name = "PatcherJvmOutput") {
            runCatching {
                ParcelFileDescriptor.AutoCloseInputStream(output).bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        Log.i(JVM_TAG, line.takeLast(MAX_OUTPUT_CHARS))
                        synchronized(tail) {
                            if (tail.size == MAX_OUTPUT_LINES) tail.removeFirst()
                            tail.addLast(line.takeLast(MAX_OUTPUT_CHARS))
                        }
                    }
                }
            }
        }
        val started = CompletableFuture<Int>()
        thread(name = "PatcherJvm") {
            val pid = try {
                outputSink.use { sink ->
                    val command = launch.command + launch.options +
                        listOf(launch.mainClass, requestFile, "/proc/self/fd/${events.fd}", launch.entryClass)
                    EmbeddedJvm.spawn(command, events.fd, sink.fd)
                }
            } catch (error: Throwable) {
                writeJvmEvent(events, "startup_failed", mapOf("error" to (error.message ?: error.javaClass.simpleName)))
                started.complete(0)
                return@thread
            }
            if (pid <= 0) {
                writeJvmEvent(events, "startup_failed", mapOf("error" to "Could not fork the JVM (errno ${-pid})"))
                started.complete(0)
                return@thread
            }
            started.complete(pid)

            // The child dies with this thread, including when cancellation unbinds the service.
            val status = EmbeddedJvm.waitProcess(pid)
            try {
                reader.join(1000)
                val diagnostics = synchronized(tail) { tail.toList() }
                writeJvmEvent(events, "jvm_ended", mapOf("status" to status, "output" to diagnostics))
            } finally {
                exitProcess(status)
            }
        }
        return started.get()
    }

    private fun writeJvmEvent(events: ParcelFileDescriptor, type: String, values: Map<String, Any?>) {
        // The manager can close its end after cancellation or after reading "finished".
        runCatching {
            ParcelFileDescriptor.AutoCloseOutputStream(events).bufferedWriter().use {
                it.write(RuntimeProtocol.encode(values + ("type" to type)).toString())
                it.newLine()
            }
        }
    }

    private companion object {
        const val JVM_TAG = "EmbeddedJvm"
        const val MAX_OUTPUT_LINES = 30
        const val MAX_OUTPUT_CHARS = 4096
    }

    override fun onBind(intent: Intent) = binder
    override fun onUnbind(intent: Intent): Boolean {
        Process.killProcess(Process.myPid())
        return false
    }
}
class PatcherService1 : PatcherService()
class PatcherService2 : PatcherService()
class PatcherService3 : PatcherService()
