package app.universal.patch.manager.patcher.split

import android.content.Context
import app.universal.patch.manager.patcher.runtime.BoundRuntimeClient
import app.universal.patch.manager.patcher.runtime.RuntimeEngine
import app.universal.patch.manager.patcher.worker.PatcherMemoryUsage
import app.universal.patch.manager.patcher.worker.parsePatcherResourceUsage
import java.io.File

/** All merge jobs use the shared embedded JVM, with ART fallback when it cannot start. */
class SplitMergeRuntime(private val context: Context) {
    @Volatile private var client: BoundRuntimeClient? = null
    fun cancelActiveExecution() { client?.cancel() }
    suspend fun execute(inputFile: File, workspace: File, stripNativeLibs: Boolean,
        skipUnneededSplits: Boolean, includedModules: Set<String>? = null,
        onProgress: (String) -> Unit, onSubSteps: (List<String>) -> Unit,
        onLog: (String) -> Unit = {}, onMemoryUsage: (PatcherMemoryUsage) -> Unit = {},
        compressNativeLibraries: Boolean = false,
        onStripProgress: ((Long, Long) -> Unit)? = null): File {
        workspace.mkdirs()
        val output = workspace.resolve("last-merged-unsigned.apk")
        val current = BoundRuntimeClient(context)
        client = current
        try {
            current.execute(RuntimeEngine.TOOLS, mapOf("action" to "merge", "inputFile" to inputFile.path,
                "workspace" to workspace.path, "outputFile" to output.path,
                "stripNativeLibs" to stripNativeLibs, "skipUnneededSplits" to skipUnneededSplits,
                "includedModules" to includedModules?.toList(),
                "compressNativeLibraries" to compressNativeLibraries)) { type, message ->
                when (type) {
                    "log" -> {
                        val text = message["message"] as String
                        val resourceUsage = parsePatcherResourceUsage(text)
                        if (resourceUsage != null) onMemoryUsage(resourceUsage) else onLog(text)
                    }
                    "memory" -> onMemoryUsage(PatcherMemoryUsage(
                        usedMb = (message["used"] as Number).toLong(),
                        maxMb = (message["max"] as Number).toLong()))
                    "event" -> (message["event"] as Map<*, *>).let { event ->
                        dispatchSplitMergeProgress(event, onProgress, onSubSteps, onStripProgress)
                    }
                }
            }
            check(output.isFile && output.length() > 0) { "The merge host did not produce an APK." }
            return output
        } catch (error: Throwable) { output.delete(); throw error }
        finally { if (client === current) client = null }
    }
}
