package app.universal.patch.manager.patcher.runtime

/** Keep as much heap as possible after a fast reservation failure, without prolonging timeouts. */
internal fun nextArtHeapMb(currentMb: Int, startupElapsedMs: Long): Int? {
    if (currentMb <= 512) return null
    return if (currentMb > 1024 && startupElapsedMs < 5_000L) {
        currentMb - 128
    } else {
        (currentMb / 2 / 128 * 128).coerceAtLeast(512)
    }
}
