package app.universal.patch.manager.domain.storage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object CacheCleanupGuard {
    private val cleanupLock = Any()
    private val activeCacheUsers = AtomicInteger(0)
    private val mutableIdleGeneration = MutableStateFlow(0L)

    // A generation preserves brief idle transitions even when observers conflate updates.
    internal val idleGeneration = mutableIdleGeneration.asStateFlow()

    val isCacheInUse: Boolean
        get() = activeCacheUsers.get() > 0

    fun begin(): AutoCloseable {
        synchronized(cleanupLock) { activeCacheUsers.incrementAndGet() }
        val closed = AtomicBoolean(false)
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                synchronized(cleanupLock) {
                    if (activeCacheUsers.decrementAndGet() == 0) {
                        mutableIdleGeneration.update { it + 1 }
                    }
                }
            }
        }
    }

    // Keep new cache users out while disposable files are detached from their original paths.
    internal fun <T> runIfIdle(block: () -> T): T? = synchronized(cleanupLock) {
        if (isCacheInUse) null else block()
    }

    suspend fun <T> withCacheInUse(block: suspend () -> T): T {
        val token = begin()
        return try {
            block()
        } finally {
            token.close()
        }
    }
}
