package app.universal.patch.manager.patcher.runtime.morphe

import android.content.Context
import app.universal.patch.manager.network.runtime.PatcherRuntimeKind
import app.universal.patch.manager.patcher.runtime.PatcherRuntimePluginRegistry
import java.io.File
import java.io.IOException

object MorpheRuntimeAssets {
    fun isAvailable(context: Context): Boolean =
        PatcherRuntimePluginRegistry.runtimeFor(PatcherRuntimeKind.MORPHE) != null
    fun ensureRuntimeApk(context: Context): File =
        PatcherRuntimePluginRegistry.runtimeFor(PatcherRuntimeKind.MORPHE)?.apkFile
            ?: throw IOException("Morphe runtime plugin is not installed or trusted.")
    fun ensureRuntimeClassPath(context: Context): File = ensureRuntimeApk(context)
}
