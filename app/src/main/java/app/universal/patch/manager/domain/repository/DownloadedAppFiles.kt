package app.universal.patch.manager.domain.repository

import app.universal.patch.manager.data.platform.SharedApkFiles
import app.universal.patch.manager.patcher.split.SplitApkPreparer
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Saved downloads retain their original APK components, including when exported. */
internal object DownloadedAppFiles {
    fun source(directory: File): File = SharedApkFiles.resolve(
        listOf(directory.resolve("base.apks"), directory.resolve("base.apk"))
            .firstOrNull(File::isFile)
            ?: directory.listFiles()?.filter(File::isFile)?.singleOrNull()
            ?: error("Downloaded app file is missing or ambiguous: ${directory.absolutePath}"))

    fun extension(file: File): String =
        if (SplitApkPreparer.isSplitArchive(file)) "apks" else "apk"

    fun finishDownload(file: File, isSplitApk: Boolean): File {
        val target = file.resolveSibling(if (isSplitApk) "base.apks" else "base.apk")
        if (target != file) Files.move(file.toPath(), target.toPath())
        return target
    }

    fun export(files: List<Pair<String, File>>, output: OutputStream, asArchive: Boolean) {
        require(files.isNotEmpty())
        if (asArchive) {
            ZipOutputStream(output).use { zip ->
                files.forEach { (baseName, source) ->
                    zip.putNextEntry(ZipEntry("${baseName.replace('/', '_')}.${extension(source)}"))
                    source.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } else {
            require(files.size == 1)
            files.single().second.inputStream().use { it.copyTo(output) }
        }
    }
}
