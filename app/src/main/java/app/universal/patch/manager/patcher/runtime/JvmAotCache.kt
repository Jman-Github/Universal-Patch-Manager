/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */
package app.universal.patch.manager.patcher.runtime

// Adapted for UPM's per-plugin, disposable workers from:
// https://github.com/MorpheApp/morphe-manager/commit/ded5f5bcbdb7c47d3dc551a882b4546dcf803b8c
import java.io.File
import java.security.MessageDigest

/** Rebuildable, bounded cache, separate from the installed runtime and host. */
internal class JvmAotCache(directory: File, home: File, host: File) {
    private val key = MessageDigest.getInstance("SHA-256")
        .digest((home.absolutePath + "\n" + host.absolutePath).toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    val directory = directory.resolve(key)
    val cache = this.directory.resolve("host.aot")
    val configuration = this.directory.resolve("host.aotconf")
    val staging = this.directory.resolve("host.aot.staging")
    private val failures = this.directory.resolve("failures")
    fun prepare() {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create AOT cache directory" }
        directory.setLastModified(System.currentTimeMillis())
        // Runs are serialized and an AOT recording is leased until creation ends.
        // Never remove this installation; bound stale installations to eight.
        directory.parentFile?.listFiles()?.filter { it.isDirectory && it != directory }
            ?.sortedByDescending { it.lastModified() }?.drop(7)?.forEach { it.deleteRecursively() }
    }
    fun canRecord() = (failures.takeIf { it.isFile }?.useLines { it.take(3).count() } ?: 0) < 3
    fun failed(error: Throwable) {
        cache.delete()
        if (directory.isDirectory) failures.appendText(error.toString().replace('\n', ' ') + "\n")
    }
    fun discardRecording() { configuration.delete(); staging.delete() }
    fun recordOptions() = listOf("-XX:AOTMode=record",
        "-XX:AOTConfiguration=$configuration", "-Xlog:aot*=error")
    fun useOptions() = listOf("-XX:AOTCache=$cache")
    fun createOptions() = listOf("-XX:AOTMode=create",
        "-XX:AOTConfiguration=$configuration", "-XX:AOTCache=$staging", "-Xlog:aot*=error")
}
