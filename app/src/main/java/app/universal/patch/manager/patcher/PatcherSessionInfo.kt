package app.universal.patch.manager.patcher

import kotlinx.serialization.Serializable

@Serializable
data class PatcherSessionInfo(
    val apkSizeBytes: Long? = null,
    val splitApk: Boolean? = null,
    val splitCount: Int? = null,
    val includedSplits: String? = null,
    val excludedSplits: String? = null,
    val patchCount: Int? = null,
    val selectedPatchLines: List<String>? = null,
    val runtimeProcess: Boolean? = null,
    val runtimeName: String? = null,
    val memoryLimitMb: Int? = null,
    val nativeLibsStripped: Boolean? = null,
    val skipUnusedSplits: Boolean? = null,
    val appPackageName: String? = null,
    val appVersionName: String? = null,
    val appVersionCode: Long? = null,
    val appVersionCodeReported: Boolean? = null,
    val bundleType: String? = null,
    val patcherEngine: String? = null,
    val morpheBytecodeMode: String? = null,
    val memoryOverride: String? = null,
    val aapt2: String? = null,
    val aapt2Fallback: Boolean? = null,
    val batteryOptimization: String? = null,
    val environment: String? = null,
    val startedAtElapsedRealtimeMs: Long? = null,
    val elapsedMs: Long? = null
)

fun PatcherSessionInfo.withFallback(fallback: PatcherSessionInfo): PatcherSessionInfo =
    PatcherSessionInfo(
        apkSizeBytes = apkSizeBytes ?: fallback.apkSizeBytes,
        splitApk = splitApk ?: fallback.splitApk,
        splitCount = splitCount ?: fallback.splitCount,
        includedSplits = includedSplits ?: fallback.includedSplits,
        excludedSplits = excludedSplits ?: fallback.excludedSplits,
        patchCount = patchCount ?: fallback.patchCount,
        selectedPatchLines = selectedPatchLines ?: fallback.selectedPatchLines,
        runtimeProcess = runtimeProcess ?: fallback.runtimeProcess,
        runtimeName = runtimeName ?: fallback.runtimeName,
        memoryLimitMb = memoryLimitMb ?: fallback.memoryLimitMb,
        nativeLibsStripped = nativeLibsStripped ?: fallback.nativeLibsStripped,
        skipUnusedSplits = skipUnusedSplits ?: fallback.skipUnusedSplits,
        appPackageName = appPackageName ?: fallback.appPackageName,
        appVersionName = appVersionName ?: fallback.appVersionName,
        appVersionCode = if (appVersionCodeReported == true) {
            appVersionCode
        } else {
            appVersionCode ?: fallback.appVersionCode
        },
        appVersionCodeReported = appVersionCodeReported ?: fallback.appVersionCodeReported,
        bundleType = bundleType ?: fallback.bundleType,
        patcherEngine = patcherEngine ?: fallback.patcherEngine,
        morpheBytecodeMode = morpheBytecodeMode ?: fallback.morpheBytecodeMode,
        memoryOverride = memoryOverride ?: fallback.memoryOverride,
        aapt2 = aapt2 ?: fallback.aapt2,
        aapt2Fallback = aapt2Fallback ?: fallback.aapt2Fallback,
        batteryOptimization = batteryOptimization ?: fallback.batteryOptimization,
        environment = environment ?: fallback.environment,
        startedAtElapsedRealtimeMs =
            startedAtElapsedRealtimeMs ?: fallback.startedAtElapsedRealtimeMs,
        elapsedMs = elapsedMs ?: fallback.elapsedMs
    )

fun parsePatcherSessionInfo(messages: Iterable<String>): PatcherSessionInfo =
    messages.fold(PatcherSessionInfo()) { info, message ->
        info.updatedFromLog(message)
    }

fun PatcherSessionInfo.updatedFromLog(message: String): PatcherSessionInfo {
    if (message.startsWith("Included splits:")) {
        return copy(includedSplits = message.logValue("Included splits:") ?: includedSplits)
    }
    if (message.startsWith("Excluded splits:")) {
        return copy(excludedSplits = message.logValue("Excluded splits:") ?: excludedSplits)
    }
    if (message.startsWith("App package:")) {
        return copy(appPackageName = message.logValue("App package:") ?: appPackageName)
    }
    if (message.startsWith("App version:")) {
        return copy(appVersionName = message.logValue("App version:") ?: appVersionName)
    }
    if (message.startsWith("App version code:")) {
        return copy(
            appVersionCode = message.logValue("App version code:")?.toLongOrNull(),
            appVersionCodeReported = true
        )
    }
    if (message.startsWith("Patcher engine:")) {
        return copy(patcherEngine = message.logValue("Patcher engine:") ?: patcherEngine)
    }
    if (message.startsWith("Morphe bytecode mode:")) {
        return copy(
            morpheBytecodeMode = message.logValue("Morphe bytecode mode:") ?: morpheBytecodeMode
        )
    }
    if (message.startsWith("Memory override:")) {
        return copy(memoryOverride = message.logValue("Memory override:") ?: memoryOverride)
    }
    if (message.startsWith("Memory limit:")) {
        return copy(
            memoryLimitMb = message.logValue("Memory limit:")
                ?.toMemoryLimitMb()
                ?: memoryLimitMb
        )
    }
    if (message.startsWith("AAPT2 fallback:")) {
        return copy(
            aapt2Fallback = message.logValue("AAPT2 fallback:")
                ?.substringBefore(' ')
                ?.toBooleanStrictOrNull()
                ?: aapt2Fallback
        )
    }
    if (message.startsWith("AAPT2:")) {
        return copy(aapt2 = message.logValue("AAPT2:") ?: aapt2)
    }
    if (message.startsWith("Strip native libs:")) {
        return copy(
            nativeLibsStripped = message.logValue("Strip native libs:")
                ?.toOnOffBoolean()
                ?: nativeLibsStripped
        )
    }
    if (message.startsWith("Skip unused splits:")) {
        return copy(
            skipUnusedSplits = message.logValue("Skip unused splits:")
                ?.toOnOffBoolean()
                ?: skipUnusedSplits
        )
    }
    if (message.startsWith("Battery optimization:")) {
        return copy(
            batteryOptimization = message.logValue("Battery optimization:")
                ?: batteryOptimization
        )
    }
    if (message.startsWith("Environment:")) {
        return copy(environment = message.logValue("Environment:") ?: environment)
    }
    if (message.startsWith("Patching session started ")) {
        return copy(
            startedAtElapsedRealtimeMs = PATCH_SESSION_START.find(message)
                ?.groupValues
                ?.getOrNull(1)
                ?.toLongOrNull()
                ?: startedAtElapsedRealtimeMs,
            elapsedMs = null
        )
    }
    if (message.startsWith("Patching session finished ")) {
        return copy(
            elapsedMs = PATCH_SESSION_ELAPSED.find(message)
                ?.groupValues
                ?.getOrNull(1)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: elapsedMs
        )
    }
    if (message.startsWith("Patching started at ")) {
        return copy(
            apkSizeBytes = PATCH_START_SIZE.find(message)
                ?.groupValues
                ?.getOrNull(1)
                ?.toLongOrNull()
                ?: apkSizeBytes,
            splitApk = PATCH_START_SPLIT.find(message)
                ?.groupValues
                ?.getOrNull(1)
                ?.toBooleanStrictOrNull()
                ?: splitApk,
            splitCount = PATCH_START_SPLIT_COUNT.find(message)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: splitCount,
            patchCount = PATCH_START_COUNT.find(message)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: patchCount,
            nativeLibsStripped = PATCH_START_NATIVE_LIBS.find(message)
                ?.groupValues
                ?.getOrNull(1)
                ?.toBooleanStrictOrNull()
                ?: nativeLibsStripped
        )
    }
    if (message.startsWith("Patcher runtime:")) {
        return copy(
            bundleType = BUNDLE_TYPE.find(message)
                ?.groupValues
                ?.getOrNull(1)
                ?: bundleType,
            memoryLimitMb = MEMORY_LIMIT.find(message)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: memoryLimitMb
        )
    }
    if (message.startsWith("Runtime mode:")) {
        return copy(
            runtimeName = when {
                message.contains("JVM") -> "JVM"
                message.contains("ART") -> "ART"
                else -> runtimeName
            },
            runtimeProcess = when (message.removePrefix("Runtime mode:").trim()) {
                "process" -> true
                "in-process" -> false
                else -> when {
                    message.removePrefix("Runtime mode:").trim().startsWith("bound ") -> true
                    message.removePrefix("Runtime mode:").trim().startsWith("standalone ART") -> true
                    message.removePrefix("Runtime mode:").trim().startsWith("in-app ") -> false
                    else -> runtimeProcess
                }
            }
        )
    }
    return this
}

private val PATCH_SESSION_START = Regex("\\belapsedRealtime=(\\d+)ms\\b")
private val PATCH_SESSION_ELAPSED = Regex("\\belapsed=(\\d+)ms\\b")
private val PATCH_START_SIZE = Regex("\\bsize=(\\d+)")
private val PATCH_START_SPLIT = Regex("\\bsplit=(true|false)\\b")
private val PATCH_START_SPLIT_COUNT = Regex("\\bsplits=(\\d+)\\b")
private val PATCH_START_COUNT = Regex("\\bpatches=(\\d+)\\b")
private val PATCH_START_NATIVE_LIBS = Regex("\\bnativeLibsStripped=(true|false)\\b")
private val BUNDLE_TYPE = Regex("\\bbundle=([^\\s;]+)")
private val MEMORY_LIMIT = Regex("\\bmemoryLimit=(\\d+)MB\\b")

private fun String.logValue(prefix: String): String? =
    removePrefix(prefix).trim().takeIf(String::isNotBlank)

private fun String.toOnOffBoolean(): Boolean? = when (lowercase()) {
    "on" -> true
    "off" -> false
    else -> null
}

private fun String.toMemoryLimitMb(): Int? =
    Regex("""(\d+)\s*(?:m|mb|mib)?""", RegexOption.IGNORE_CASE)
        .find(trim())
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
