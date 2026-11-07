package app.universal.patch.manager.domain.repository

import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import android.os.Parcelable
import android.util.Log
import app.universal.patch.manager.data.room.AppDatabase
import app.universal.patch.manager.data.room.AppDatabase.Companion.generateUid
import app.universal.patch.manager.data.room.apps.downloaded.DownloadedApp
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.domain.storage.CacheCleanupGuard
import app.universal.patch.manager.network.downloader.LoadedDownloaderPlugin
import app.universal.patch.manager.plugin.downloader.OutputDownloadScope
import app.universal.patch.manager.util.PM
import app.universal.patch.manager.patcher.split.SplitApkInspector
import app.universal.patch.manager.patcher.split.SplitApkPreparer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.outputStream

data class DownloadResult(
    val file: File,
    val needsSplit: Boolean,
    val merged: Boolean = false,
    val cleanup: (() -> Unit)? = null,
    val packageInfo: PackageInfo? = null
)

class DownloadedAppRepository(
    private val app: Application,
    db: AppDatabase,
    private val pm: PM,
    private val prefs: PreferencesManager
) {
    private val dir = app.getDir("downloaded-apps", Context.MODE_PRIVATE)
    private val tempDir = app.cacheDir.resolve("temp-downloaded-apps").apply { mkdirs() }
    private val dao = db.downloadedAppDao()

    fun getAll() = dao.getAllApps().distinctUntilChanged()

    fun getApkFileForApp(app: DownloadedApp): File =
        getApkFileForDir(dir.resolve(app.directory))

    suspend fun exportApps(
        apps: List<DownloadedApp>,
        output: OutputStream,
        asArchive: Boolean
    ) = CacheCleanupGuard.withCacheInUse {
        withContext(Dispatchers.IO) {
            DownloadedAppFiles.export(
                files = apps.map { "${it.packageName}_${it.version}" to getApkFileForApp(it) },
                output = output,
                asArchive = asArchive
            )
        }
    }

    private fun getApkFileForDir(directory: File) = DownloadedAppFiles.source(directory)

    suspend fun download(
        plugin: LoadedDownloaderPlugin,
        data: Parcelable,
        expectedPackageName: String,
        expectedVersion: String?,
        appCompatibilityCheck: Boolean,
        patchesCompatibilityCheck: Boolean,
        onDownload: suspend (downloadProgress: Pair<Long, Long?>) -> Unit,
        persistDownload: Boolean = true,
    ): DownloadResult = CacheCleanupGuard.withCacheInUse {
        // Converted integers cannot contain / or .. unlike the package name or version, so they are safer to use here.
        val relativePath = File(generateUid().toString())
        val parentDir = if (persistDownload) dir else tempDir
        val saveDir = parentDir.resolve(relativePath).also { it.mkdirs() }
        val targetFile = saveDir.resolve("base.apk").toPath()

        try {
            val downloadSize = AtomicLong(0)
            val downloadedBytes = AtomicLong(0)

            channelFlow {
                val scope = object : OutputDownloadScope {
                    override val pluginPackageName = plugin.packageName
                    override val hostPackageName = app.packageName
                    override suspend fun reportSize(size: Long) {
                        require(size > 0) { "Size must be greater than zero" }
                        require(
                            downloadSize.compareAndSet(
                                0,
                                size
                            )
                        ) { "Download size has already been set" }
                        send(downloadedBytes.get() to size)
                    }
                }

                fun emitProgress(bytes: Long) {
                    val newValue = downloadedBytes.addAndGet(bytes)
                    val totalSize = downloadSize.get()
                    trySend(
                        newValue to totalSize.takeIf { it > 0L }
                    ).getOrThrow()
                }

                targetFile.outputStream(StandardOpenOption.CREATE_NEW).buffered().use {
                    val stream = object : FilterOutputStream(it) {
                        override fun write(b: Int) = out.write(b).also { emitProgress(1) }

                        override fun write(b: ByteArray?, off: Int, len: Int) =
                            out.write(b, off, len).also {
                                emitProgress(
                                    len.toLong()
                                )
                            }
                    }
                    plugin.download(scope, data, stream)
                }
            }
                .conflate()
                .flowOn(Dispatchers.IO)
                .collect { (downloaded, size) -> onDownload(downloaded to size) }

            if (downloadedBytes.get() < 1) error("Downloader did not download anything.")

            val workingFile = targetFile.toFile()
            val pkgInfo = resolvePackageInfoForValidation(workingFile, saveDir) ?: run {
                Log.e(
                    TAG,
                    "Downloaded APK file is invalid: " +
                        "plugin=${plugin.packageName}, file=${workingFile.absolutePath}, " +
                        "size=${workingFile.length()}, split=${SplitApkPreparer.isSplitArchive(workingFile)}"
                )
                error("Downloaded APK file is invalid")
            }
            if (pkgInfo.packageName != expectedPackageName) error("Downloaded APK has the wrong package name. Expected: $expectedPackageName, Actual: ${pkgInfo.packageName}")
            expectedVersion?.let {
                if (
                    pkgInfo.versionName != expectedVersion &&
                    (appCompatibilityCheck || patchesCompatibilityCheck)
                ) error("The selected app version ($pkgInfo.versionName) doesn't match the suggested version. Please use the suggested version ($expectedVersion), or adjust your settings by disabling \"Require suggested app version\" and enabling \"Disable version compatibility check\".")
            }

            val needsSplit = SplitApkPreparer.isSplitArchive(workingFile)
            val storedFile = DownloadedAppFiles.finishDownload(workingFile, needsSplit)

            if (persistDownload) {
                val versionName = pkgInfo.versionName!!
                val shouldKeepLatestOnly = prefs.autoSaveDownloaderLatestOnly.get()
                val downloadsToReplace = dao.getByPackageName(pkgInfo.packageName).filter {
                    shouldKeepLatestOnly || it.version == versionName
                }

                if (downloadsToReplace.isNotEmpty()) {
                    deleteStoredApps(downloadsToReplace, requireSuccess = true)
                    dao.delete(downloadsToReplace)
                }

                dao.upsert(
                    DownloadedApp(
                        packageName = pkgInfo.packageName,
                        version = versionName,
                        directory = relativePath,
                    )
                )
            }

            // Keep split archives intact so the selected runtime can prepare their components.
            val cleanupAction = if (persistDownload) null else {
                {
                    saveDir.deleteRecursively()
                    Unit
                }
            }

            DownloadResult(
                file = storedFile,
                needsSplit = needsSplit,
                merged = false,
                cleanup = cleanupAction,
                packageInfo = pkgInfo
            )
        } catch (e: Exception) {
            saveDir.deleteRecursively()
            throw e
        }
    }

    suspend fun get(packageName: String, version: String, markUsed: Boolean = false) =
        dao.get(packageName, version)?.also {
            if (markUsed) dao.markUsed(packageName, version)
        }

    suspend fun getLatest(packageName: String, markUsed: Boolean = false) =
        dao.getLatest(packageName)?.also {
            if (markUsed) dao.markUsed(it.packageName, it.version)
        }

    suspend fun delete(downloadedApps: Collection<DownloadedApp>) = CacheCleanupGuard.withCacheInUse {
        deleteStoredApps(downloadedApps)
        dao.delete(downloadedApps)
    }

    private fun deleteStoredApps(downloadedApps: Collection<DownloadedApp>, requireSuccess: Boolean = false) {
        downloadedApps.forEach {
            val targetDir = dir.resolve(it.directory)
            val deleted = targetDir.deleteRecursively()
            if (requireSuccess && !deleted) {
                throw IllegalStateException("Failed to delete existing directory: ${targetDir.absolutePath}")
            }
        }
    }

    private suspend fun resolvePackageInfoForValidation(file: File, workspace: File): PackageInfo? {
        if (!file.exists()) return null
        if (!SplitApkPreparer.isSplitArchive(file)) return pm.getPackageInfo(file)

        val extracted = SplitApkInspector.extractRepresentativeApk(file, workspace) ?: return null
        return try {
            pm.getPackageInfo(extracted.file)
        } finally {
            extracted.cleanup()
        }
    }

    companion object {
        private const val TAG = "DownloadedAppRepo"
    }
}
