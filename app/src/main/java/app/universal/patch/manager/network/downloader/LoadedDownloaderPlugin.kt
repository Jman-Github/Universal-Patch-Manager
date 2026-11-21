package app.universal.patch.manager.network.downloader

import android.os.Parcelable
import app.universal.patch.manager.plugin.downloader.OutputDownloadScope
import app.universal.patch.manager.plugin.downloader.GetScope
import java.io.OutputStream

class LoadedDownloaderPlugin(
    val packageName: String,
    val className: String,
    val name: String,
    val version: String,
    val sourceId: String? = null,
    private val getImpl: suspend GetScope.(packageName: String, version: String?) -> Pair<Parcelable, String?>?,
    private val downloadImpl: suspend OutputDownloadScope.(data: Parcelable, outputStream: OutputStream) -> Unit,
    val classLoader: ClassLoader
) {
    val id = sourceId?.let { "$it:$className" } ?: "$packageName:$className"
    val shortDisplayName: String
        get() = name.toDownloaderMainName()

    suspend fun get(scope: GetScope, packageName: String, version: String?) =
        getImpl(scope.asDualGetScope(), packageName, version)

    suspend fun download(scope: OutputDownloadScope, data: Parcelable, outputStream: OutputStream) =
        downloadImpl(scope.asDualOutputScope(), data, outputStream)
}

fun String.toDownloaderMainName(): String {
    val withoutPrefix = substringAfterLast(':', this).trim()
    return withoutPrefix
        .removeSuffix(" downloader")
        .removeSuffix(" Downloader")
        .trim()
        .replace("Apkmirror", "APKMirror", ignoreCase = true)
        .replace("Apkcombo", "APKCombo", ignoreCase = true)
        .replace("Apkpure", "APKPure", ignoreCase = true)
        .let { if (it.equals("APK Download Helper", ignoreCase = true)) "Morphe Helper" else it }
        .ifBlank { this }
}
