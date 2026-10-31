package app.universal.patch.manager.domain.repository

import java.io.File

internal const val MAX_METADATA_FILE_BYTES = 16L * 1024

/** Called under the repository's disk mutex; never walks outside this cache directory. */
internal fun pruneAppMetadataCache(directory: File, now: Long) {
    val cutoff = now - 90L * 24 * 60 * 60 * 1000
    var count = 0
    var bytes = 0L
    directory.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }?.forEach { file ->
        if (file.extension == "tmp") {
            file.delete()
        } else if (file.extension == "json") {
            val size = file.length()
            if (file.lastModified() < cutoff || size > MAX_METADATA_FILE_BYTES ||
                count >= 1000 || bytes + size > 8L * 1024 * 1024) {
                file.delete()
            } else {
                count++
                bytes += size
            }
        }
    }
}
