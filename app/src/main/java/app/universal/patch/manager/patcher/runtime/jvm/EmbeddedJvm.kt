/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */
package app.universal.patch.manager.patcher.runtime.jvm

// Code adapted from Morphe, see third-party/NOTICE.txt for more information.
// https://github.com/MorpheApp/morphe-manager/pull/1474

internal object EmbeddedJvm {
    init { System.loadLibrary("jvm_launcher") }

    /** Start the child on the same thread that will wait for it. */
    fun spawn(command: List<String>, eventsFd: Int, outputFd: Int): Int =
        spawnProcess(command.toTypedArray(), intArrayOf(eventsFd), outputFd)

    /** Returns the exit code, 128 plus the terminating signal, or a negative errno. */
    external fun waitProcess(pid: Int): Int

    private external fun spawnProcess(command: Array<String>, keepFds: IntArray, outputFd: Int): Int
}
