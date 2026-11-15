package app.universal.patch.manager.patcher.util

import app.universal.patch.manager.patcher.runtime.RuntimeDevice
import app.universal.patch.manager.patcher.runtime.RuntimeLog as Log
import java.io.File
import com.android.apksig.apk.ApkFormatException
import com.android.apksig.zip.ZipFormatException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object NativeLibStripper {
    private const val TAG = "NativeLibStripper"

    suspend fun strip(apkFile: File): Boolean =
        strip(apkFile, RuntimeDevice.supportedAbis.toTypedArray().filter { it.isNotBlank() })

    suspend fun strip(
        apkFile: File,
        checkCancelled: () -> Unit,
    ): Boolean = strip(apkFile, RuntimeDevice.supportedAbis.filter { it.isNotBlank() }, checkCancelled = checkCancelled)

    suspend fun strip(
        apkFile: File,
        onProgress: (Int) -> Unit,
        checkCancelled: () -> Unit = {},
    ): Boolean = strip(apkFile, RuntimeDevice.supportedAbis.filter { it.isNotBlank() }, onProgress, checkCancelled)

    suspend fun strip(
        apkFile: File,
        supportedAbis: List<String>,
        onProgress: (Int) -> Unit = {},
        checkCancelled: () -> Unit = {},
    ): Boolean =
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            checkCancelled()
            var lastProgress = -1
            fun report(percent: Int) {
                if (percent > lastProgress) {
                    lastProgress = percent
                    onProgress(percent)
                }
            }
            fun unchanged(): Boolean {
                report(100)
                return false
            }
            report(0)
            if (supportedAbis.isEmpty()) return@withContext unchanged()

            val abisInApk = abiDirectoriesInApk(apkFile)
            if (abisInApk.size <= 1) return@withContext unchanged()

            val preferredAbi = supportedAbis.firstOrNull { it in abisInApk }
                ?: return@withContext unchanged()
            val allowedAbis = setOf(preferredAbi)

            val context = currentCoroutineContext()
            val checkActive = { context.ensureActive(); checkCancelled() }
            val removedEntries = ZipFile(apkFile).use { zip ->
                zip.entries().asSequence().count {
                    checkActive()
                    !it.isDirectory && !shouldKeepZipEntry(it.name, allowedAbis)
                }
            }
            if (removedEntries == 0) return@withContext unchanged()
            // Reserve completion for the replacement of the original. A compatibility
            // retry must never move the visible progress backwards.
            val reportCopy: (Long, Long) -> Unit = { current, total ->
                if (total > 0L) report((current.toDouble() * 99 / total).toInt().coerceIn(0, 99))
            }
            Log.i(TAG, "Preserving native libraries for ABI $preferredAbi")

            val tempFile = Files.createTempFile(requireNotNull(apkFile.absoluteFile.parentFile).toPath(), "abi-stripped-", ".apk").toFile()
            try {
                try {
                    NativeLibRawCopy.rewrite(apkFile, tempFile, { shouldKeepZipEntry(it, allowedAbis) }, checkActive, reportCopy)
                } catch (_: ApkFormatException) {
                    checkActive()
                    rewriteStreaming(apkFile, tempFile, allowedAbis, checkActive, reportCopy)
                } catch (_: ZipFormatException) {
                    checkActive()
                    rewriteStreaming(apkFile, tempFile, allowedAbis, checkActive, reportCopy)
                }
                checkActive()
                try {
                    Files.move(tempFile.toPath(), apkFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    checkActive()
                    Files.move(tempFile.toPath(), apkFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                report(100)
                Log.i(TAG, "Removed $removedEntries native library entries for unsupported ABIs")
                true
            } finally {
                tempFile.delete()
            }
        }

    /** Compatibility path for archives that apksig cannot copy directly. */
    private fun rewriteStreaming(
        apkFile: File, tempFile: File, allowedAbis: Set<String>, checkCancelled: () -> Unit,
        onProgress: (Long, Long) -> Unit
    ) {
        val totalWork = ZipFile(apkFile).use { zip ->
            zip.entries().asSequence().filter { shouldKeepZipEntry(it.name, allowedAbis) }
                .sumOf { maxOf(1L, it.size) }
        }
        var completedWork = 0L
        ZipInputStream(apkFile.inputStream().buffered()).use { input ->
            ZipOutputStream(tempFile.outputStream().buffered()).use { output ->
                val buffer = ByteArray(64 * 1024)
                var entry = input.nextEntry
                while (entry != null) {
                    checkCancelled()
                    if (shouldKeepZipEntry(entry.name, allowedAbis)) {
                        output.putNextEntry(cloneEntry(entry))
                        var copied = 0L
                        if (!entry.isDirectory) {
                            while (true) {
                                checkCancelled()
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                copied += read
                                onProgress(completedWork + copied, totalWork)
                            }
                        }
                        output.closeEntry()
                        completedWork += maxOf(1L, copied)
                        onProgress(completedWork, totalWork)
                    }
                    input.closeEntry()
                    entry = input.nextEntry
                }
            }
        }
    }

    private fun shouldKeepZipEntry(name: String, allowedAbis: Set<String>): Boolean {
        val abi = extractAbiFromEntry(name) ?: return true
        return abi in allowedAbis
    }

    private fun cloneEntry(entry: ZipEntry): ZipEntry {
        val clone = ZipEntry(entry.name)
        clone.time = entry.time
        clone.comment = entry.comment
        entry.extra?.let { clone.extra = it.copyOf() }
        if (RuntimeDevice.sdk >= 26) {
            try {
                entry.creationTime?.let { clone.creationTime = it }
                entry.lastAccessTime?.let { clone.lastAccessTime = it }
                entry.lastModifiedTime?.let { clone.lastModifiedTime = it }
            } catch (_: Exception) {
                // Ignore metadata failures
            }
        }

        when (entry.method) {
            ZipEntry.STORED -> {
                clone.method = ZipEntry.STORED
                if (entry.size >= 0) clone.size = entry.size
                if (entry.compressedSize >= 0) clone.compressedSize = entry.compressedSize
                clone.crc = entry.crc
            }

            ZipEntry.DEFLATED -> clone.method = ZipEntry.DEFLATED
            else -> if (entry.method != -1) clone.method = entry.method
        }

        return clone
    }

    private fun extractAbiFromEntry(name: String): String? {
        if (!name.startsWith("lib/")) return null
        val secondSlash = name.indexOf('/', startIndex = 4)
        if (secondSlash == -1) return null
        return name.substring(4, secondSlash)
    }

    private fun abiDirectoriesInApk(apkFile: File): Set<String> =
        runCatching {
            ZipFile(apkFile).use { zip ->
                zip.entries().asSequence()
                    .map { it.name }
                    .mapNotNull(::extractAbiFromEntry)
                    .toSet()
            }
        }.getOrDefault(emptySet())
}
