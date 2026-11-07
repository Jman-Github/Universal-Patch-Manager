package app.universal.patch.manager.patcher.runtime

import app.universal.patch.manager.network.runtime.LoadedPatcherRuntimePlugin
import app.universal.patch.manager.network.runtime.PatcherRuntimeKind

object PatcherRuntimePluginRegistry {
    @Volatile
    private var provider: (() -> Map<PatcherRuntimeKind, LoadedPatcherRuntimePlugin>)? = null

    fun install(provider: () -> Map<PatcherRuntimeKind, LoadedPatcherRuntimePlugin>) {
        this.provider = provider
    }

    // Class loaders and queued workers may keep using a selected APK after an update.
    // Retain these paths for this process; the next app process can reclaim old versions.
    private val retainedFiles = mutableSetOf<java.io.File>()

    @Synchronized
    fun runtimeFor(kind: PatcherRuntimeKind): LoadedPatcherRuntimePlugin? =
        provider?.invoke()?.get(kind)?.also { retainedFiles.add(it.apkFile.absoluteFile) }

    /** Selection and deletion share the same lock so cleanup cannot race a new runtime user. */
    @Synchronized
    fun <T> withRetainedFiles(block: (Set<java.io.File>) -> T): T = block(retainedFiles.toSet())
}
