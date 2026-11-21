package app.universal.patch.manager.patcher.host

import app.universal.patch.manager.domain.manager.*
import app.universal.patch.manager.patcher.logger.LogLevel
import app.universal.patch.manager.patcher.logger.Logger
import app.universal.patch.manager.patcher.split.SplitApkPreparer
import java.io.File
import kotlinx.coroutines.*
import app.universal.patch.manager.patcher.runCancellableBlockingIo

interface ToolRuntimeCallback {
    fun log(level: String, message: String)
    fun event(event: Map<String, Any?>)
    fun isCancelled(): Boolean
}

object ToolRuntimeEntry {
    @JvmStatic
    fun runPatcher(params: Map<String, Any?>, callback: ToolRuntimeCallback): String? = runBlocking {
        fun file(key: String) = File(params.getValue(key) as String)
        fun checkCancelled() {
            if (callback.isCancelled()) throw CancellationException("Tool run cancelled")
        }
        checkCancelled()
        val runJob = currentCoroutineContext()[Job]!!
        val cancellation = launch(Dispatchers.Default) {
            while (isActive) {
                if (callback.isCancelled()) { runJob.cancel(); break }
                delay(50)
            }
        }
        try {
            when (params["action"]) {
                "merge" -> {
                    val preparation = SplitApkPreparer.prepareIfNeeded(
                        source = file("inputFile"), workspace = file("workspace"),
                        logger = object : Logger() {
                            override fun log(level: LogLevel, message: String) {
                                checkCancelled()
                                callback.log(level.name, message)
                            }
                        },
                        stripNativeLibs = params["stripNativeLibs"] as Boolean,
                        skipUnneededSplits = params["skipUnneededSplits"] as Boolean,
                        includedModules = (params["includedModules"] as? List<*>)?.map { it as String }?.toSet(),
                        compressNativeLibraries = params["compressNativeLibraries"] as Boolean,
                        onStripProgress = { percent ->
                            checkCancelled()
                            callback.event(mapOf(
                                "message" to "Stripping native libraries",
                                "current" to percent.toLong(), "total" to 100L
                            ))
                        },
                        onProgress = { callback.event(mapOf("message" to it)) },
                        onSubSteps = { callback.event(mapOf("subSteps" to it)) },
                        onLog = { checkCancelled(); callback.log("INFO", it) }
                    )
                    try {
                        runCancellableBlockingIo(::checkCancelled) {
                            preparation.file.copyTo(file("outputFile"), overwrite = true)
                        }
                    }
                    finally { preparation.cleanup() }
                }
                "signature" -> {
                    val result = runCancellableBlockingIo(::checkCancelled) {
                        SignatureMetadataInjectorArscEngine.execute(
                            metadataArchive = file("metadataArchive"), targetApk = file("inputFile"),
                            outputApk = file("outputFile"),
                            mode = SignatureMetadataInjectionMode.valueOf(params["mode"] as String),
                            onProgress = { checkCancelled(); callback.event(mapOf("stage" to it.stage.name)) },
                            checkCancelled = ::checkCancelled,
                            onLog = { checkCancelled(); callback.log("INFO", it) }
                        )
                    }
                    checkCancelled()
                    callback.event(mapOf("result" to mapOf("injectedEntries" to result.injectedEntries,
                        "skippedEntries" to result.skippedEntries,
                        "removedSignatureEntryCount" to result.removedSignatureEntryCount)))
                }
                else -> error("Unknown tool action")
            }
            null
        } finally { cancellation.cancel() }
    }
}