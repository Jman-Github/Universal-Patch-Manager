package app.universal.patch.manager.patcher.reseam

import app.universal.patch.manager.util.managerStorageContext

import android.content.Context
import app.universal.patch.manager.patcher.ProgressEvent
import app.universal.patch.manager.patcher.RemoteError
import app.universal.patch.manager.patcher.StepId
import app.universal.patch.manager.patcher.patch.PatchInfo
import app.universal.patch.manager.patcher.logger.LogLevel
import app.universal.patch.manager.patcher.logger.Logger
import app.universal.patch.manager.patcher.runtime.reseam.ReseamRuntimeAssets
import dalvik.system.DexClassLoader
import java.io.File
import java.io.IOException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException

object ReseamRuntimeBridge {
    private const val ENTRY_CLASS_NAME = "app.universal.patch.manager.reseam.runtime.ReseamRuntimeEntry"
    private const val CALLBACK_CLASS_NAME = "app.universal.patch.manager.reseam.runtime.ReseamRuntimeCallback"
    private const val LOAD_METADATA_METHOD = "loadMetadata"
    private const val LOAD_METADATA_FOR_BUNDLE_METHOD = "loadMetadataForBundle"
    private const val RUN_PATCHER_METHOD = "runPatcher"
    private const val CANCELLATION_SENTINEL = "__PATCHING_CANCELLED__"

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var runtimeClassPathOverride: String? = null
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
    private val nativeLoaders = ReseamRuntimeLoaderCache<DexClassLoader>()

    private val lock = Any()

    fun initialize(context: Context, runtimeClassPath: String? = null) {
        appContext = context.applicationContext ?: context
        val overridePath = runtimeClassPath?.takeIf { it.isNotBlank() }
        if (overridePath != null && runtimeClassPathOverride != overridePath) {
            synchronized(lock) {
                if (runtimeClassPathOverride != overridePath) {
                    runtimeClassPathOverride = overridePath
                    this.runtimeClassPath = null
                    classLoader = null
                    entryClass = null
                    loadMetadataMethod = null
                    loadMetadataForBundleMethod = null
                }
            }
        }
    }

    fun engineVersion(): String? = reseamRuntimeCall {
        val entry = ensureEntryClass()
        // Earlier plugin builds do not expose their SDK version.
        val method = try {
            entry.getMethod("engineVersion")
        } catch (_: NoSuchMethodException) {
            return@reseamRuntimeCall null
        }
        (method.invokeReseamRuntime() as? String)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun loadBundleName(bundlePath: String): String? = reseamRuntimeCall {
        (ensureEntryClass().getMethod("loadBundleName", String::class.java)
            .invokeReseamRuntime(bundlePath) as? String)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun loadMetadata(bundlePath: String): List<PatchInfo> = reseamRuntimeCall {
        val method = ensureLoadMetadataForBundleMethod()
        val raw = method.invokeReseamRuntime(bundlePath) as? List<*> ?: emptyList<Any?>()
        raw.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            PatchInfo.fromMorpheMetadata(map as Map<String, Any?>, preserveDouble = true)
        }
    }

    fun loadMetadata(bundlePaths: List<String>): Map<String, List<PatchInfo>> = reseamRuntimeCall {
        val method = ensureLoadMetadataMethod()
        val raw = method.invokeReseamRuntime(bundlePaths) as? Map<*, *> ?: emptyMap<String, List<PatchInfo>>()
        raw.mapNotNull { (key, value) ->
            val path = key as? String ?: return@mapNotNull null
            val metadata = value as? List<*> ?: return@mapNotNull path to emptyList()
            val patches = metadata.mapNotNull { entry ->
                val map = entry as? Map<*, *> ?: return@mapNotNull null
                PatchInfo.fromMorpheMetadata(map as Map<String, Any?>, preserveDouble = true)
            }
            path to patches
        }.toMap()
    }

    fun runPatcher(
        params: Map<String, Any?>,
        logger: Logger,
        onEvent: (ProgressEvent) -> Unit,
        isCancelled: () -> Boolean = { false }
    ): String? = reseamRuntimeCall {
        val entry = ensureEntryClass()
        // Pin every reflected type to this invocation's loader, even if the active plugin changes.
        val loader = requireNotNull(entry.classLoader) { "Reseam runtime has no plugin class loader" }
        val callback = loader.loadClass(CALLBACK_CLASS_NAME)
        val proxy = Proxy.newProxyInstance(
            loader,
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
        val result = method.invokeReseamRuntime(params, proxy) as? String
        if (result == CANCELLATION_SENTINEL) {
            throw CancellationException("Patching cancelled")
        }
        result
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

    private fun ensureEntryClass(): Class<*> = reseamRuntimeCall {
        synchronized(lock) {
            // Recheck authorization even when returning classes from a retained JNI loader.
            val loader = ensureClassLoader()
            entryClass ?: loader.loadClass(ENTRY_CLASS_NAME).also { entryClass = it }
        }
    }

    private fun ensureClassLoader(): DexClassLoader = synchronized(lock) {
        val context = appContext ?: error("ReseamRuntimeBridge is not initialized.")
        val overrideFile = runtimeClassPathOverride
            ?.takeIf { it.isNotBlank() }
            ?.let { path ->
                File(path).takeIf { it.exists() }
                    ?: throw IOException("Reseam runtime classpath does not exist: $path")
            }
        val runtime = if (overrideFile != null) ReseamRuntimeAssets.prepareRuntime(context, overrideFile)
            else ReseamRuntimeAssets.prepareSelectedRuntime(context)
        val cacheKey = runtime.nativeLibraryDirectory.canonicalPath
        val existing = classLoader
        if (existing != null && runtimeClassPath == cacheKey) return@synchronized existing

        if (runtimeClassPathOverride == null) {
            val metadataScratch = File(context.managerStorageContext.cacheDir, "reseam-metadata")
            check(metadataScratch.mkdirs() || metadataScratch.isDirectory)
            android.system.Os.setenv("TMPDIR", metadataScratch.absolutePath, true)
        }
        val optimizedDir = File(context.managerStorageContext.codeCacheDir, "reseam-runtime-dex").apply { mkdirs() }
        val parent = context.classLoader.parent ?: context.classLoader
        val loader = nativeLoaders.getOrCreate(runtime.nativeLibraryDirectory) {
            DexClassLoader(runtime.apkFile.absolutePath, optimizedDir.absolutePath,
                runtime.nativeLibraryDirectory.absolutePath, parent)
        }
        classLoader = loader
        runtimeClassPath = cacheKey
        entryClass = null
        loadMetadataMethod = null
        loadMetadataForBundleMethod = null
        loader
    }

    private fun mapToProgressEvent(map: Map<*, *>): ProgressEvent {
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

class ReseamBridgeFailureException(val originalStackTrace: String) :
    Exception("Reseam external runtime failed")
