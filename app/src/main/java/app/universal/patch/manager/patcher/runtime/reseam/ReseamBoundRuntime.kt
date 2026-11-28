package app.universal.patch.manager.patcher.runtime

import android.content.Context
import app.universal.patch.manager.patcher.ProgressEvent
import app.universal.patch.manager.patcher.logger.*
import app.universal.patch.manager.patcher.morphe.MorpheRuntimeBridge
import app.universal.patch.manager.patcher.patch.PatchBundleType
import app.universal.patch.manager.patcher.runtime.reseam.ReseamRuntimeAssets
import app.universal.patch.manager.util.Options
import app.universal.patch.manager.util.PatchSelection

/** The SDK owns native memory; its Android JNI loader runs in a disposable bound ART service. */
class ReseamBoundRuntime(private val context: Context,
    private val includedSplitModules: Set<String>? = null) : Runtime(context) {
    private val client = BoundRuntimeClient(context)
    override fun cancel() = client.cancel()
    override suspend fun execute(inputFile: String, outputFile: String, packageName: String,
        selectedPatches: PatchSelection, options: Options, logger: Logger,
        onEvent: (ProgressEvent) -> Unit, onMemoryUsage: (Long, Long) -> Unit,
        stripNativeLibs: Boolean, skipUnneededSplits: Boolean) {
        val mode = prefs.patcherLogMode.get()
        val runtimeLogger = logger.filtered(mode)
        val selected = selectedPatches.filterValues { it.isNotEmpty() }
        // Enabled dependency catalogs participate even when none of their patches are selected.
        val available = bundles(PatchBundleType.RESEAM, enabledOnly = true)
        require((selected.keys - available.keys).isEmpty()) {
            "Selected Reseam patch sources are unavailable. Re-open patch selection and select patches again."
        }
        val runtime = ReseamRuntimeAssets.prepareSelectedRuntime(context)
        val params = mapOf(
            "cacheDir" to cacheDir, "packageName" to packageName,
            "inputFile" to inputFile, "outputFile" to outputFile,
            "patcherLogMode" to mode.name, "stripNativeLibs" to stripNativeLibs,
            "skipUnneededSplits" to skipUnneededSplits, "includedSplitModules" to includedSplitModules?.toList(),
            "ignorePatchVersions" to prefs.disablePatchVersionCompatCheck.get(),
            "continueOnPatchError" to prefs.continueOnPatchError.get(),
            "configurations" to available.map { (uid, bundle) ->
                mapOf("bundlePath" to bundle.patchesJar, "patches" to selected[uid].orEmpty().toList(),
                    "options" to options[uid].orEmpty())
            }
        )
        client.execute(RuntimeEngine.RESEAM, params, runtimeClassPath = runtime.apkFile.path,
            nativeLibraryDir = runtime.nativeLibraryDirectory.path) { type, event ->
            when (type) {
                "log" -> runtimeLogger.log(LogLevel.valueOf(event["level"] as String), event["message"] as String)
                "event" -> onEvent(MorpheRuntimeBridge.mapToProgressEvent(event["event"] as Map<*, *>))
                "memory" -> onMemoryUsage((event["used"] as Number).toLong(), (event["max"] as Number).toLong())
            }
        }
    }
}
