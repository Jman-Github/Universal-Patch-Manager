package app.universal.patch.manager.patcher.runtime

import android.content.Context

// Code adapted from Morphe, see third-party/NOTICE for more information
// https://github.com/MorpheApp/morphe-manager/blob/a2c3d31bd7ab42e6bc4b9dd528ed856fc72fb948/app/src/main/java/app/morphe/manager/patcher/runtime/ProcessRuntime.kt
object MemoryLimitConfig {
    const val PROCESS_RUNTIME_MEMORY_MINIMUM = 512
    const val PROCESS_RUNTIME_MEMORY_MAX_LIMIT = 2048
    const val PROCESS_RUNTIME_MEMORY_MAX_LIMIT_INITIALIZATION = 1024
    private const val PROCESS_RUNTIME_MEMORY_DEFAULT_MINIMUM = 640
    const val PROCESS_RUNTIME_MEMORY_LOW_WARNING = 640
    const val PROCESS_RUNTIME_MEMORY_HIGH_WARNING = 1280
    const val PROCESS_RUNTIME_MEMORY_STEP = 128
    const val PROCESS_RUNTIME_MEMORY_NOT_SET = -1
    const val PROCESS_RUNTIME_MEMORY_RETRY_MINIMUM = 256

    /**
     * Uses roughly 25% of physical RAM, rounded down to a 128 MB boundary.
     * The result is intentionally independent of Android's per-app memory class because the
     * separate ART processes override the heap properties before the VM starts.
     */
    fun calculateAdaptiveMemoryLimit(context: Context): Int {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE)
            as android.app.ActivityManager
        val memoryInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        val totalRamMb = (memoryInfo.totalMem / (1024 * 1024)).toInt()
        val adaptive =
            ((totalRamMb * 0.25).toInt() / PROCESS_RUNTIME_MEMORY_STEP) *
                PROCESS_RUNTIME_MEMORY_STEP

        return adaptive.coerceIn(
            PROCESS_RUNTIME_MEMORY_DEFAULT_MINIMUM,
            PROCESS_RUNTIME_MEMORY_MAX_LIMIT
        )
    }

    fun initialMemoryLimitMb(context: Context): Int =
        calculateAdaptiveMemoryLimit(context).coerceAtMost(
            PROCESS_RUNTIME_MEMORY_MAX_LIMIT_INITIALIZATION
        )

    fun resolveMemoryLimitMb(context: Context, requestedMb: Int): Int =
        if (requestedMb == PROCESS_RUNTIME_MEMORY_NOT_SET) {
            initialMemoryLimitMb(context)
        } else {
            requestedMb.coerceIn(
                PROCESS_RUNTIME_MEMORY_RETRY_MINIMUM,
                PROCESS_RUNTIME_MEMORY_MAX_LIMIT
            )
        }

    fun clampConfiguredMemoryLimitMb(requestedMb: Int): Int {
        val clamped = requestedMb.coerceIn(
            PROCESS_RUNTIME_MEMORY_MINIMUM,
            PROCESS_RUNTIME_MEMORY_MAX_LIMIT
        )
        val nearestStep = (
            clamped - PROCESS_RUNTIME_MEMORY_MINIMUM + PROCESS_RUNTIME_MEMORY_STEP / 2
        ) / PROCESS_RUNTIME_MEMORY_STEP
        return (
            PROCESS_RUNTIME_MEMORY_MINIMUM + nearestStep * PROCESS_RUNTIME_MEMORY_STEP
        ).coerceAtMost(PROCESS_RUNTIME_MEMORY_MAX_LIMIT)
    }

    // Kept for split-process callers that do not have direct access to preferences.
    fun maxLimitMb(context: Context): Int = initialMemoryLimitMb(context)

    fun clampLimitMb(@Suppress("UNUSED_PARAMETER") context: Context, requestedMb: Int): Int =
        requestedMb.coerceIn(
            PROCESS_RUNTIME_MEMORY_RETRY_MINIMUM,
            PROCESS_RUNTIME_MEMORY_MAX_LIMIT
        )
}
