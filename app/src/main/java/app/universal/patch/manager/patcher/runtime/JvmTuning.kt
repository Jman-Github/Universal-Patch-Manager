/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */
package app.universal.patch.manager.patcher.runtime

// Code adapted from Morphe, see third-party/NOTICE.txt for more information.
// https://github.com/MorpheApp/morphe-manager/pull/1246
// https://github.com/MorpheApp/morphe-manager/pull/1302

// https://github.com/MorpheApp/morphe-manager/pull/1474

/** Liberica 25 server options on both 64-bit and 32-bit ARM. */
internal object JvmTuning {
    fun options(is64Bit: Boolean, aot: List<String> = emptyList()): List<String> {
        val tuning = if (is64Bit) {
            listOfNotNull("-XX:TieredStopAtLevel=1".takeIf { aot.isEmpty() }, "-XX:+UseCompactObjectHeaders")
        } else {
            listOf("-XX:+UseSerialGC", "-XX:ReservedCodeCacheSize=64m")
        }
        return listOf("-Djava.awt.headless=true") + tuning + aot
    }
}
