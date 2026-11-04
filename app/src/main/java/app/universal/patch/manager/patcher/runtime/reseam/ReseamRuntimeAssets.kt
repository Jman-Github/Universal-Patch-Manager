package app.universal.patch.manager.patcher.runtime.reseam

import android.content.Context
import android.os.Build
import android.os.Process
import app.universal.patch.manager.util.managerStorageContext
import java.io.File
import app.universal.patch.manager.patcher.runtime.RuntimePreparationCache

object ReseamRuntimeAssets {
    private data class Prepared(val files: ReseamRuntimeFiles, val apkSize: Long, val nativeSize: Long)
    private val prepared = RuntimePreparationCache<Prepared>()

    // Prepare an immutable APK/native snapshot from the selected verified plugin.
    internal fun prepareSelectedRuntime(context: Context): ReseamRuntimeFiles {
        val apk = ensureRuntimeClassPath(context)
        val abis = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
        val key = "${context.managerStorageContext.codeCacheDir.canonicalPath}:${apk.canonicalPath}:${apk.length()}:${apk.lastModified()}:${abis.joinToString()}"
        return prepared.get(key, { cached ->
            val library = cached.files.nativeLibraryDirectory.resolve("libreseam-sdk-native.so")
            cached.files.apkFile.isFile && cached.files.apkFile.length() == cached.apkSize &&
                library.isFile && library.length() == cached.nativeSize
        }) {
            val files = prepareRuntime(context, apk)
            Prepared(files, files.apkFile.length(), files.nativeLibraryDirectory.resolve("libreseam-sdk-native.so").length())
        }.files
    }

    fun isAvailable(context: Context): Boolean = app.universal.patch.manager.patcher.runtime.PatcherRuntimePluginRegistry.runtimeFor(app.universal.patch.manager.network.runtime.PatcherRuntimeKind.RESEAM) != null

    fun ensureRuntimeClassPath(context: Context): File = app.universal.patch.manager.patcher.runtime.PatcherRuntimePluginRegistry.runtimeFor(app.universal.patch.manager.network.runtime.PatcherRuntimeKind.RESEAM)?.apkFile
        ?: throw java.io.IOException("ReSeam runtime plugin is not installed or trusted.")

    internal fun prepareRuntime(context: Context, apk: File): ReseamRuntimeFiles {
        val abis = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
        return prepareReseamRuntimeFiles(
            apk, File(context.managerStorageContext.codeCacheDir, "reseam-native"), abis.toList()
        )
    }

    /** Disposable workers use the same immutable snapshot as metadata inspection. */
    fun ensureNativeLibraryDir(context: Context, apk: File): File =
        prepareRuntime(context, apk).nativeLibraryDirectory
}
