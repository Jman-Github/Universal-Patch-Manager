package app.universal.patch.manager.patcher.split

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SplitApkInspector {
    suspend fun extractRepresentativeApk(
        source: File,
        workspace: File,
        maxExtractedBytes: Long = Long.MAX_VALUE,
        maxArchiveEntries: Int = Int.MAX_VALUE,
        validateEntryName: (String) -> Unit = {},
        validateEntry: (ZipEntry) -> Unit = {},
        requireBase: Boolean = false,
        checkCancelled: () -> Unit = {}
    ): ExtractedApk? {
        require(maxExtractedBytes >= 0L) { "Invalid extracted APK size limit." }
        require(maxArchiveEntries >= 0) { "Invalid archive entry limit." }
        if (
            !SplitApkPreparer.isSplitArchive(
                file = source,
                maxArchiveEntries = maxArchiveEntries,
                checkCancelled = checkCancelled
            )
        ) {
            return null
        }

        val temp = File(
            workspace,
            "inspect-${UUID.randomUUID()}.apk"
        )

        return try {
            withContext(Dispatchers.IO) {
                checkCancelled()
                val selectedEntries = SplitApkPreparer.splitApkEntryNames(
                    file = source,
                    maxArchiveEntries = maxArchiveEntries,
                    checkCancelled = checkCancelled
                )
                var verifiedBaseEntry: String? = null
                try {
                    ZipFile(source).use { zip ->
                        validateArchiveEntries(
                            entries = zip.entries().asSequence(),
                            maxArchiveEntries = maxArchiveEntries,
                            validateEntryName = validateEntryName,
                            validateEntry = validateEntry,
                            checkCancelled = checkCancelled
                        )
                        val entry = if (requireBase) {
                            val baseName = SplitManifestInspector.archiveOrder(zip, selectedEntries, checkCancelled).first()
                            val entryName = selectedEntries.single { it.substringAfterLast('/') == baseName }
                            verifiedBaseEntry = entryName
                            zip.getEntry(entryName)
                        } else {
                            selectBestEntry(zip, selectedEntries)
                        } ?: throw IOException("Split archive does not contain any APK entries.")
                        zip.getInputStream(entry).use { input ->
                            Files.newOutputStream(temp.toPath()).use { output ->
                                input.copyToBounded(
                                    output = output,
                                    maxBytes = maxExtractedBytes,
                                    checkCancelled = checkCancelled
                                )
                            }
                        }
                    }
                } catch (error: IOException) {
                    val message = error.message?.lowercase(Locale.ROOT).orEmpty()
                    if ((requireBase && verifiedBaseEntry == null) ||
                        (!message.contains("no such device") && !message.contains("enodev"))
                    ) {
                        throw error
                    }
                    extractWithStream(
                        source = source,
                        temp = temp,
                        selectedEntries = verifiedBaseEntry?.let { setOf(it) } ?: selectedEntries,
                        maxExtractedBytes = maxExtractedBytes,
                        maxArchiveEntries = maxArchiveEntries,
                        validateEntryName = validateEntryName,
                        validateEntry = validateEntry,
                        checkCancelled = checkCancelled
                    )
                }
            }
            checkCancelled()
            ExtractedApk(temp) { temp.delete() }
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
    }

    private fun selectBestEntry(
        zip: ZipFile,
        selectedEntries: Set<String>
    ): ZipEntry? {
        val entries = zip.entries().asSequence()
            .filterNot { it.isDirectory }
            .filter { it.name in selectedEntries }
            .toList()
        if (entries.isEmpty()) return null

        val lowered = entries.associateWith { it.name.lowercase(Locale.ROOT) }
        val baseEntry = lowered.entries.firstOrNull { (_, name) ->
            SplitApkPreparer.isExplicitBaseApkEntryName(name)
        }?.key
        if (baseEntry != null) return baseEntry

        val nonConfig = lowered.entries.filter { (_, name) ->
            !isConfigApkEntryName(name)
        }.map { it.key }
        val largestNonConfig = nonConfig
            .filter { it.size >= 0 }
            .maxByOrNull { it.size }
        if (largestNonConfig != null) return largestNonConfig

        return entries.minWithOrNull(
            compareBy<ZipEntry> { entry ->
                val lower = entry.name.lowercase(Locale.ROOT)
                when {
                    SplitApkPreparer.isExplicitBaseApkEntryName(lower) -> 0
                    isConfigApkEntryName(lower) -> 99
                    else -> 2
                }
            }.thenBy { it.name.length }
        )
    }

    private fun extractWithStream(
        source: File,
        temp: File,
        maxExtractedBytes: Long,
        maxArchiveEntries: Int,
        validateEntryName: (String) -> Unit,
        validateEntry: (ZipEntry) -> Unit,
        checkCancelled: () -> Unit,
        selectedEntries: Set<String>
    ) {
        val entryName = selectBestEntryName(
            checkCancelled = checkCancelled,
            maxArchiveEntries = maxArchiveEntries,
            source = source,
            selectedEntries = selectedEntries,
            validateEntryName = validateEntryName,
            validateEntry = validateEntry
        )
            ?: throw IOException("Split archive does not contain any APK entries.")
        ZipInputStream(FileInputStream(source)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                checkCancelled()
                if (!entry.isDirectory && entry.name == entryName) {

                    Files.newOutputStream(temp.toPath()).use { output ->
                        zip.copyToBounded(
                            output = output,
                            maxBytes = maxExtractedBytes,
                            checkCancelled = checkCancelled
                        )
                    }
                    return
                }
                entry = zip.nextEntry
            }
        }
        throw IOException("Split archive entry not found: $entryName")
    }

    private fun selectBestEntryName(
        maxArchiveEntries: Int,
        validateEntryName: (String) -> Unit,
        validateEntry: (ZipEntry) -> Unit,
        checkCancelled: () -> Unit,
        source: File,
        selectedEntries: Set<String>
    ): String? {
        val exactNames = HashSet<String>()
        val apkNames = HashSet<String>()
        var baseEntry: ZipEntry? = null
        var primaryEntry: ZipEntry? = null
        var largestNonConfig: ZipEntry? = null
        var bestFallback: ZipEntry? = null
        var entryCount = 0

        fun updateFallback(entry: ZipEntry) {
            if (bestFallback == null) {
                bestFallback = entry
                return
            }
            val current = bestFallback ?: return
            val nextName = entry.name.lowercase(Locale.ROOT)
            val currentName = current.name.lowercase(Locale.ROOT)
            val nextScore = when {
                SplitApkPreparer.isExplicitBaseApkEntryName(nextName) -> 0
                isConfigApkEntryName(nextName) -> 99
                else -> 2
            }
            val currentScore = when {
                SplitApkPreparer.isExplicitBaseApkEntryName(currentName) -> 0
                isConfigApkEntryName(currentName) -> 99
                else -> 2
            }
            if (nextScore < currentScore || (nextScore == currentScore && entry.name.length < current.name.length)) {
                bestFallback = entry
            }
        }

        ZipInputStream(FileInputStream(source)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                checkCancelled()
                entryCount++
                require(entryCount <= maxArchiveEntries) {
                    "Archive contains more than $maxArchiveEntries ZIP entries."
                }
                validateEntryName(entry.name)
                validateEntry(entry)
                require(exactNames.add(entry.name)) {
                    "Duplicate ZIP entry: ${entry.name}"
                }
                if (!entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)) {
                    require(apkNames.add(entry.name.substringAfterLast('/').uppercase(Locale.ROOT))) {
                        "Duplicate APK entry: ${entry.name}"
                    }
                }
                if (!entry.isDirectory && entry.name in selectedEntries) {
                    val lower = entry.name.lowercase(Locale.ROOT)
                    if (baseEntry == null && SplitApkPreparer.isExplicitBaseApkEntryName(lower)) {
                        baseEntry = entry
                    }
                    if (primaryEntry == null && ("main" in lower || "master" in lower)) {
                        primaryEntry = entry
                    }
                    if (!isConfigApkEntryName(lower)) {
                        if (entry.size >= 0 && (largestNonConfig == null || entry.size > (largestNonConfig?.size ?: -1))) {
                            largestNonConfig = entry
                        }
                    }
                    updateFallback(entry)
                }
                entry = zip.nextEntry
            }
        }

        return baseEntry?.name
            ?: largestNonConfig?.name
            ?: bestFallback?.name
    }

    private fun isConfigApkEntryName(entryName: String): Boolean {
        val fileName = entryName.replace('\\', '/').substringAfterLast('/')
            .lowercase(Locale.ROOT)
        return fileName.startsWith("config") ||
            fileName.contains("split_config") ||
            fileName.contains("config.")
    }

    private fun validateArchiveEntries(
        entries: Sequence<ZipEntry>,
        maxArchiveEntries: Int,
        validateEntryName: (String) -> Unit,
        validateEntry: (ZipEntry) -> Unit,
        checkCancelled: () -> Unit
    ) {
        val exactNames = HashSet<String>()
        val apkNames = HashSet<String>()
        var entryCount = 0
        entries.forEach { entry ->
            checkCancelled()
            entryCount++
            require(entryCount <= maxArchiveEntries) {
                "Archive contains more than $maxArchiveEntries ZIP entries."
            }
            validateEntryName(entry.name)
            validateEntry(entry)
            require(exactNames.add(entry.name)) {
                "Duplicate ZIP entry: ${entry.name}"
            }
            if (!entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)) {
                require(apkNames.add(entry.name.substringAfterLast('/').uppercase(Locale.ROOT))) {
                    "Duplicate APK entry: ${entry.name}"
                }
            }
        }
    }

    data class ExtractedApk(
        val file: File,
        val cleanup: () -> Unit = {}
    )
}

private fun java.io.InputStream.copyToBounded(
    output: java.io.OutputStream,
    maxBytes: Long,
    checkCancelled: () -> Unit
) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var copied = 0L
    while (true) {
        checkCancelled()
        val count = read(buffer)
        if (count < 0) break
        copied = Math.addExact(copied, count.toLong())
        require(copied <= maxBytes) {
            "Extracted APK exceeds the supported size limit."
        }
        output.write(buffer, 0, count)
    }
}
