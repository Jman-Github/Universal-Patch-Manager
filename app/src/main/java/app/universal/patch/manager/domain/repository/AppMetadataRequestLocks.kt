package app.universal.patch.manager.domain.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Deduplicate an app's name/icon lookup without blocking unrelated packages. */
internal class AppMetadataRequestLocks {
    private class Entry(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val entries = mutableMapOf<String, Entry>()

    suspend fun <T> withLock(packageName: String, block: suspend () -> T): T {
        val entry = synchronized(entries) {
            entries.getOrPut(packageName) { Entry() }.also { it.users++ }
        }
        try {
            return entry.mutex.withLock { block() }
        } finally {
            synchronized(entries) {
                if (--entry.users == 0) entries.remove(packageName)
            }
        }
    }
}
