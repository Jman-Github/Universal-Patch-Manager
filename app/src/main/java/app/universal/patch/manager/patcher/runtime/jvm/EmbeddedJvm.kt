/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */
package app.universal.patch.manager.patcher.runtime.jvm

// Code adapted from Morphe, see third-party/NOTICE for more information.
// https://github.com/MorpheApp/morphe-manager/pull/1223

/** One HotSpot VM lives alongside ART until the app process ends. */
internal object EmbeddedJvm {
    init { System.loadLibrary("jvm_launcher") }

    /**
     * @return 0 on startup, a negative JNI error, 1 for a library that failed to load,
     * 2 for a missing main method, or 3 if memory tag checks could not be disabled.
     * Adapted from https://github.com/MorpheApp/morphe-manager/commit/ad2f4644edfe848a3d09260b2f53c441ebfd6161
     */
    fun start(launch: JvmLaunch, args: List<String>) = startJvm(
        launch.libjvm, launch.preload.toTypedArray(), launch.options.toTypedArray(),
        launch.mainClass, args.toTypedArray()
    )

    external fun largestReservationMb(limitMb: Int): Int

    private external fun startJvm(
        libjvm: String, preload: Array<String>, options: Array<String>,
        mainClass: String, args: Array<String>
    ): Int
}
