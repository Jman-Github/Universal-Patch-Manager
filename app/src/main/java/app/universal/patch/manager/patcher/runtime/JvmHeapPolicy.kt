package app.universal.patch.manager.patcher.runtime

// Code adapted from Morphe, see third-party/NOTICE for more information.
// https://github.com/MorpheApp/morphe-manager/pull/1223
// https://github.com/MorpheApp/morphe-manager/commit/1b670e196260774aa0029026f01365192c6fa234
internal object JvmHeapPolicy {
    // https://github.com/MorpheApp/morphe-manager/pull/1474
    fun minimumMb(is64Bit: Boolean): Int = if (is64Bit) 2048 else 1024

    fun limitMb(totalRamMb: Long, is64Bit: Boolean): Int {
        val budget = if (is64Bit) totalRamMb / 3 else totalRamMb / 2
        val maximum = if (is64Bit) 3072L else 1536L
        return budget.coerceIn(minimumMb(is64Bit).toLong(), maximum).toInt()
    }
}
