/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */
package app.universal.patch.manager.patcher.runtime

// Code adapted from Morphe, see third-party/NOTICE for more information.
// https://github.com/MorpheApp/morphe-manager/pull/1212
// https://github.com/MorpheApp/morphe-manager/pull/1223
// https://github.com/MorpheApp/morphe-manager/pull/1246

import android.content.*
import android.os.IBinder
import android.os.ParcelFileDescriptor
import app.universal.patch.manager.patcher.runtime.jvm.IPatcherService
import app.universal.patch.manager.patcher.runtime.jvm.JvmLaunch
import app.universal.patch.manager.patcher.runtime.jvm.PatcherService
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.Closeable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds

/** Shared pool of disposable workers for JVM runs and Android JNI/ART engines. */
class BoundArtRuntimes private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slots = Channel<Int>(4, onUndeliveredElement = ::recycleSlot)
        .apply { repeat(4) { trySend(it) } }

    private fun recycleSlot(slot: Int) {
        // A slot represents capacity, so cancellation must never discard it.
        slots.trySend(slot)
    }

    suspend fun take(engine: RuntimeEngine, runtimeClassPath: String?, nativeLibraryDir: String?): ArtRuntime =
        take(JvmLaunch(emptyList(), emptyList(), "", engine.entry, runtimeClassPath, nativeLibraryDir))

    suspend fun takeJvm(launch: JvmLaunch): ArtRuntime {
        require(launch.command.isNotEmpty())
        return take(launch)
    }

    private suspend fun take(launch: JvmLaunch): ArtRuntime {
        // Keep ownership outside the timeout: cancellation can win after receive completes
        // but before the timeout block hands its result back to this coroutine.
        var acquiredSlot: Int? = null
        try {
            if (withTimeoutOrNull(CONNECT_TIMEOUT) { acquiredSlot = slots.receive() } == null) {
                throw JvmUnavailableException("No patcher worker became available within $CONNECT_TIMEOUT")
            }
        } catch (error: Throwable) {
            acquiredSlot?.let(::recycleSlot)
            throw error
        }
        val slot = checkNotNull(acquiredSlot)
        val connection = try { PatcherConnection.connect(context, SERVICES[slot]) }
            catch (error: Throwable) { recycleSlot(slot); throw error }
        connection.whenClosed {
            scope.launch {
                // isBinderAlive can stay true until a death notification or transaction arrives.
                // Subscribe before starting the worker, so an idle dead binder cannot leak a slot.
                connection.awaitDeath()
                recycleSlot(slot)
            }
        }
        try {
            val pid = connection.service.prepare(launch, emptyArray())
            return ArtRuntime(connection, pid)
        } catch (error: Throwable) { connection.close(); throw error }
    }

    class ArtRuntime internal constructor(private val connection: PatcherConnection, val pid: Int) : Closeable {
        suspend fun awaitExit() = connection.awaitDeath()
        fun run(requestFile: String, events: ParcelFileDescriptor) = connection.service.run(requestFile, events)
        override fun close() = connection.close()
    }

    /** A binding to [PatcherService] for a single run, which ends the service's process once closed. */
    internal class PatcherConnection private constructor(private val context: Context) : ServiceConnection, Closeable {
        private var continuation: CancellableContinuation<IPatcherService>? = null
        private val death = CompletableDeferred<Unit>()
        private val deathRecipient = IBinder.DeathRecipient { disconnected("worker died") }
        suspend fun awaitDeath() = death.await()
        lateinit var service: IPatcherService
            private set

        @Synchronized
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            // A disposable worker must never accept a reconnect or a late cancelled bind.
            if (continuation?.isActive != true) {
                close()
                return
            }
            try {
                binder.linkToDeath(deathRecipient, 0)
                continuation?.takeIf { it.isActive }?.resume(IPatcherService.Stub.asInterface(binder))
                continuation = null
            } catch (_: android.os.RemoteException) {
                disconnected("died before connecting")
            }
        }

        override fun onServiceDisconnected(name: ComponentName) = disconnected("worker disconnected")

        @Synchronized
        private fun disconnected(reason: String) {
            death.complete(Unit)
            fail(reason)
            // Drop BIND_AUTO_CREATE immediately, before Android can restart this one-shot worker.
            close()
        }

        override fun onNullBinding(name: ComponentName) {
            fail("bound to nothing")
            close()
        }

        override fun onBindingDied(name: ComponentName) {
            fail("binding died")
            close()
        }

        @Synchronized
        private fun fail(reason: String) {
            continuation?.takeIf { it.isActive }?.resumeWithException(JvmUnavailableException("Patcher service $reason"))
            continuation = null
        }

        private var onClosed: (() -> Unit)? = null
        private val bound = java.util.concurrent.atomic.AtomicBoolean(false)

        @Synchronized
        fun whenClosed(action: () -> Unit) {
            check(onClosed == null) { "The close listener is already registered" }
            onClosed = action
            // Death can close the binding after connect resumes but before take owns it.
            if (!bound.get()) action()
        }

        @Synchronized
        override fun close() {
            continuation = null
            if (bound.compareAndSet(true, false)) {
                runCatching { context.unbindService(this) }; onClosed?.invoke()
            }
        }

        companion object {
            suspend fun connect(context: Context, component: Class<out PatcherService>): PatcherConnection {
                val connection = PatcherConnection(context)
                val service = try {
                    withTimeoutOrNull(CONNECT_TIMEOUT) {
                        suspendCancellableCoroutine<IPatcherService> { continuation ->
                            connection.continuation = continuation
                            // Android can register a dispatcher even when bindService returns false.
                            connection.bound.set(true)
                            val bound = context.bindService(
                                Intent(context, component),
                                connection,
                                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
                            )
                            if (!bound) connection.fail("could not be bound")
                        }
                    }
                } catch (e: Throwable) {
                    connection.close()
                    throw e
                }

                if (service == null) {
                    connection.close()
                    throw JvmUnavailableException("Patcher service did not connect within $CONNECT_TIMEOUT")
                }
                return connection.apply { this.service = service }
            }
        }
    }

    companion object {
        private val SERVICES = listOf(PatcherService::class.java,
            app.universal.patch.manager.patcher.runtime.jvm.PatcherService1::class.java,
            app.universal.patch.manager.patcher.runtime.jvm.PatcherService2::class.java,
            app.universal.patch.manager.patcher.runtime.jvm.PatcherService3::class.java)
        private val CONNECT_TIMEOUT = 30.seconds
        @Volatile private var instance: BoundArtRuntimes? = null
        fun get(context: Context): BoundArtRuntimes = instance ?: synchronized(this) {
            instance ?: BoundArtRuntimes(context.applicationContext).also { instance = it }
        }
    }
}
