package app.universal.patch.manager.patcher.runtime

// Code adapted from Morphe, see third-party/NOTICE for more information.
// https://github.com/MorpheApp/morphe-manager/pull/1223
// https://github.com/MorpheApp/morphe-manager/commit/1b670e196260774aa0029026f01365192c6fa234
internal object JvmHeapPolicy {
    // https://github.com/MorpheApp/morphe-manager/commit/77af30c2e2711d6b745ffa435a5a7fa534277449
    fun limitMb(totalRamMb: Long, is64Bit: Boolean): Int =
        if (is64Bit) (totalRamMb / 3).coerceIn(2048L, 3072L).toInt() else 1024

    /** Leave address space for the 64 MB code cache and metaspace outside the contiguous heap. */
    fun fit32Bit(ramBudgetMb: Int, reservableMb: Int): Int =
        minOf(ramBudgetMb, 1024, (reservableMb - 128).coerceAtLeast(0))
}
