package app.universal.patch.manager.network.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private val catalogCacheWriteLock = Any()

/** Readers see a complete catalog even when separate screens refresh concurrently. */
internal fun writeRuntimeCatalogCache(cache: File, raw: String) {
    synchronized(catalogCacheWriteLock) {
        val temporary = File.createTempFile("runtime-catalog-", ".tmp", cache.absoluteFile.parentFile)
        try {
            temporary.writeText(raw)
            Files.move(temporary.toPath(), cache.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temporary.delete()
        }
    }
}
