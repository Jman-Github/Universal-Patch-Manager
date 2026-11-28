package app.universal.patch.manager.patcher.morphe

import app.universal.patch.manager.util.managerStorageContext

import android.content.Context
import app.universal.patch.manager.patcher.patch.PatchInfo
import app.universal.patch.manager.patcher.ProgressEvent
import app.universal.patch.manager.patcher.RemoteError
import app.universal.patch.manager.patcher.StepId
import app.universal.patch.manager.patcher.logger.LogLevel
import app.universal.patch.manager.patcher.logger.Logger
import app.universal.patch.manager.patcher.runtime.morphe.MorpheRuntimeAssets
import dalvik.system.DexClassLoader
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException

object MorpheRuntimeBridge {
    private const val ENTRY_CLASS_NAME = "app.universal.patch.manager.morphe.runtime.MorpheRuntimeEntry"
    private const val CALLBACK_CLASS_NAME = "app.universal.patch.manager.morphe.runtime.MorpheRuntimeCallback"
    private const val LOAD_METADATA_METHOD = "loadMetadata"
    private const val LOAD_METADATA_FOR_BUNDLE_METHOD = "loadMetadataForBundle"
    private const val RUN_PATCHER_METHOD = "runPatcher"
    private const val CANCELLATION_SENTINEL = "__PATCHING_CANCELLED__"

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var runtimeClassPath: String? = null
    @Volatile
    private var classLoader: DexClassLoader? = null
    @Volatile
    private var entryClass: Class<*>? = null
    @Volatile
    private var loadMetadataMethod: Method? = null
    @Volatile
    private var loadMetadataForBundleMethod: Method? = null

    private val lock = Any()

    fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext ?: context
    }

    fun loadMetadata(bundlePath: String): List<PatchInfo> {
        val method = ensureLoadMetadataForBundleMethod()
        val raw = method.invoke(null, bundlePath) as? List<*> ?: emptyList<Any?>()
        return raw.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            PatchInfo.fromMorpheMetadata(map as Map<String, Any?>)
        }
    }

    fun loadMetadata(bundlePaths: List<String>): Map<String, List<PatchInfo>> {
        val method = ensureLoadMetadataMethod()
        val raw = method.invoke(null, bundlePaths) as? Map<*, *> ?: emptyMap<String, List<PatchInfo>>()
        return raw.mapNotNull { (key, value) ->
            val path = key as? String ?: return@mapNotNull null
            val metadata = value as? List<*> ?: return@mapNotNull path to emptyList()
            val patches = metadata.mapNotNull { entry ->
                val map = entry as? Map<*, *> ?: return@mapNotNull null
                PatchInfo.fromMorpheMetadata(map as Map<String, Any?>)
            }
            path to patches
        }.toMap()
    }

    fun runPatcher(
        params: Map<String, Any?>,
        logger: Logger,
        onEvent: (ProgressEvent) -> Unit,
        isCancelled: () -> Boolean = { false }
    ): String? {
        val entry = ensureEntryClass()
        // Keep all reflected types on the loader selected for this invocation.
        val loader = requireNotNull(entry.classLoader)
        val callback = loader.loadClass(CALLBACK_CLASS_NAME)
        val proxy = Proxy.newProxyInstance(
            entry.classLoader,
            arrayOf(callback)
        ) { _, method, args ->
            when (method.name) {
                "log" -> {
                    val level = args?.getOrNull(0) as? String
                    val message = args?.getOrNull(1) as? String
                    if (level != null && message != null) {
                        logger.log(LogLevel.valueOf(level), message)
                    }
                    null
                }
                "event" -> {
                    val raw = args?.getOrNull(0) as? Map<*, *> ?: return@newProxyInstance null
                    onEvent(mapToProgressEvent(raw))
                    null
                }
                "isCancelled" -> isCancelled()
                else -> null
            }
        }
        val method = entry.getMethod(RUN_PATCHER_METHOD, Map::class.java, callback)
        val result = method.invoke(null, params, proxy) as? String
        if (result == CANCELLATION_SENTINEL) {
            throw CancellationException("Patching cancelled")
        }
        return result
    }

    private fun ensureLoadMetadataMethod(): Method = synchronized(lock) {
        val entry = ensureEntryClass()
        loadMetadataMethod ?: run {
            val method = entry.getMethod(LOAD_METADATA_METHOD, List::class.java)
            loadMetadataMethod = method
            method
        }
    }

    private fun ensureLoadMetadataForBundleMethod(): Method = synchronized(lock) {
        val entry = ensureEntryClass()
        loadMetadataForBundleMethod ?: run {
            val method = entry.getMethod(LOAD_METADATA_FOR_BUNDLE_METHOD, String::class.java)
            loadMetadataForBundleMethod = method
            method
        }
    }

    private fun ensureEntryClass(): Class<*> = synchronized(lock) {
        // Refresh cached classes and methods when the selected plugin changes or loses trust.
        val loader = ensureClassLoader()
        entryClass ?: run {
            val loaded = loader.loadClass(ENTRY_CLASS_NAME)
            entryClass = loaded
            loaded
        }
    }

    private fun ensureClassLoader(): DexClassLoader {
        val context = appContext ?: error("MorpheRuntimeBridge is not initialized.")
        val runtimeClassPathFile = MorpheRuntimeAssets.ensureRuntimeClassPath(context)
        val path = runtimeClassPathFile.absolutePath
        val cacheKey = "$path:${runtimeClassPathFile.lastModified()}:${runtimeClassPathFile.length()}"
        val existing = classLoader
        if (existing != null && runtimeClassPath == cacheKey) return existing

        synchronized(lock) {
            val current = classLoader
            if (current != null && runtimeClassPath == cacheKey) return current

            val optimizedDir = File(context.managerStorageContext.codeCacheDir, "morphe-runtime-dex").apply { mkdirs() }
            // Use the boot classloader as parent to avoid app classpath conflicts.
            val parent = context.classLoader.parent ?: context.classLoader
            val loader = DexClassLoader(path, optimizedDir.absolutePath, null, parent)
            classLoader = loader
            runtimeClassPath = cacheKey
            entryClass = null
            loadMetadataMethod = null
            loadMetadataForBundleMethod = null
            return loader
        }
    }

    internal fun mapToProgressEvent(map: Map<*, *>): ProgressEvent {
        val type = map["type"] as? String ?: return ProgressEvent.Progress(StepId.LoadPatches)
        val stepId = mapToStepId(map["stepId"] as? Map<*, *>)
        return when (type) {
            "Started" -> ProgressEvent.Started(
                stepId ?: StepId.LoadPatches,
                (map["subSteps"] as? Iterable<*>)?.mapNotNull { it as? String }
            )
            "Completed" -> ProgressEvent.Completed(stepId ?: StepId.LoadPatches)
            "Progress" -> ProgressEvent.Progress(
                stepId = stepId ?: StepId.LoadPatches,
                current = (map["current"] as? Number)?.toLong(),
                total = (map["total"] as? Number)?.toLong(),
                message = map["message"] as? String,
                subSteps = (map["subSteps"] as? Iterable<*>)?.mapNotNull { it as? String }
            )
            "Failed" -> {
                val error = map["error"] as? Map<*, *> ?: emptyMap<Any, Any?>()
                ProgressEvent.Failed(
                    stepId,
                    RemoteError(
                        type = error["type"] as? String ?: "UnknownError",
                        message = error["message"] as? String,
                        stackTrace = error["stackTrace"] as? String ?: ""
                    )
                )
            }
            else -> ProgressEvent.Progress(stepId ?: StepId.LoadPatches)
        }
    }

    private fun mapToStepId(map: Map<*, *>?): StepId? {
        val kind = map?.get("kind") as? String ?: return null
        return when (kind) {
            "DownloadAPK" -> StepId.DownloadAPK
            "LoadPatches" -> StepId.LoadPatches
            "PrepareSplitApk" -> StepId.PrepareSplitApk
            "ReadAPK" -> StepId.ReadAPK
            "ExecutePatches" -> StepId.ExecutePatches
            "WriteAPK" -> StepId.WriteAPK
            "SignAPK" -> StepId.SignAPK
            "ExecutePatch" -> {
                val index = (map["index"] as? Number)?.toInt() ?: 0
                StepId.ExecutePatch(index)
            }
            else -> null
        }
    }
}

class MorpheBridgeFailureException(val originalStackTrace: String) :
    Exception("Morphe in-process patcher failed")
