package app.universal.patch.manager.domain.installer

import app.universal.patch.manager.patcher.split.SplitApkFiles
import app.universal.patch.manager.patcher.split.SplitApkPreparer
import app.universal.patch.manager.patcher.split.SplitManifestInspector
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** Keep extracted APKs alive until the installer has completed or been cancelled. */
internal suspend fun <T> withInstallApkFiles(
    source: File,
    cacheDirectory: File,
    install: suspend (List<File>) -> T
): T = withContext(Dispatchers.IO) {
    if (!SplitApkPreparer.isSplitArchive(source)) return@withContext install(listOf(source))
    val directory = Files.createTempDirectory(cacheDirectory.toPath(), "split-install-").toFile()
    try {
        val checkCancelled = { coroutineContext.ensureActive() }
        val files = runInterruptible {
            val entries = SplitApkPreparer.splitApkEntryNames(source, checkCancelled = checkCancelled)
            SplitApkFiles.extract(source, directory, entries)
            SplitManifestInspector.directoryOrder(directory, checkCancelled).map(directory::resolve)
        }
        install(files)
    } finally {
        directory.deleteRecursively()
    }
}
