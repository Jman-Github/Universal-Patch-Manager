/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.universal.patch.manager.patcher.runtime.jvm

// Code adapted from Morphe, see third-party/NOTICE for more information.
// https://github.com/MorpheApp/morphe-manager/pull/1202
// https://github.com/MorpheApp/morphe-manager/pull/1212
// https://github.com/MorpheApp/morphe-manager/pull/1215

import app.universal.patch.manager.util.managerStorageContext
import android.app.Application
import android.os.Build
import android.os.Process
import android.util.Log
import app.universal.patch.manager.BuildConfig
import app.universal.patch.manager.network.runtime.PatcherRuntimeKind
import app.universal.patch.manager.patcher.runtime.PatcherRuntimePluginRegistry
import java.util.zip.ZipFile
import java.io.InputStream
import java.io.FilterInputStream
import app.universal.patch.manager.patcher.runtime.JvmUnavailableException
import app.universal.patch.manager.util.tag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.ZipInputStream

/**
 * The JVM distributed in the separately signed runtime support plugin for patching, unpacked to storage the first time a run
 * needs it, since the JVM can only be loaded from files.
 *
 * Runtime images are unpacked once per support plugin version. Each patcher host comes from
 * its matching signed plugin and is cached by plugin content and manager build identity.
 * The shared runtime images retain the compressed layout used by earlier manager builds.
 */
class JvmInstallation(private val app: Application) {
    /**
     * The ABI of the runtime this device gets, null where the app ships none for it. Matched to
     * the process rather than the device, as a 64 bit device can run the app as a 32 bit one.
     */
    private val supportPack: File
        get() = PatcherRuntimePluginRegistry.runtimeFor(PatcherRuntimeKind.SHARED_SUPPORT)?.apkFile
            ?: throw JvmUnavailableException("UPM runtime support plugin is missing")
    val abi: String?
        get() = runCatching {
            ZipFile(supportPack).use { zip ->
                (if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS)
                    .firstOrNull { zip.getEntry("assets/jvm/$it.zip.xz") != null }
            }
        }.getOrNull()
    val isAvailable get() = abi != null
    val feature get() = if (Process.is64Bit()) 25 else 17

    private fun contentHash(file: File): String = java.security.MessageDigest.getInstance("SHA-256").let { digest ->
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun openAsset(pack: File, name: String): InputStream {
        val zip = ZipFile(pack)
        try {
            val entry = zip.getEntry("assets/$name") ?: throw IOException("Missing runtime support asset: $name")
            return object : FilterInputStream(zip.getInputStream(entry)) {
                override fun close() { try { super.close() } finally { zip.close() } }
            }
        } catch (error: Throwable) { zip.close(); throw error }
    }

    private val root = app.managerStorageContext.noBackupFilesDir.resolve(ROOT_DIR)
    private val lock = Mutex()

    /** Warm the runtime image without unpacking an unrelated engine's host JAR. */
    suspend fun prepareRuntime() = lock.withLock {
        withContext(Dispatchers.IO) { runtimeHome(supportPack) }
    }

    private fun runtimeHome(pack: File): File {
        val abi = abi ?: throw JvmUnavailableException("No patcher JVM for ${Build.SUPPORTED_ABIS.joinToString()}")
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Could not create $root")
        val home = root.resolve("runtime-$abi-${contentHash(pack)}")
        if (!home.isDirectory) unpackRuntime(pack, abi, home)
        return home
    }

    /** The runtime and host ready to start, unpacked first if they are not yet. */
    suspend fun install(host: String, plugin: File? = null): Installed = lock.withLock {
        withContext(Dispatchers.IO) {
            val abi = abi ?: throw JvmUnavailableException(
                "No patcher JVM for ${Build.SUPPORTED_ABIS.joinToString()}"
            )

            try {
                val pack = supportPack
                val home = runtimeHome(pack)

                // UPM PR APKs can share a version code, so refresh the host on every app update.
                val updatedAt = app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime
                val source = plugin ?: pack
                val pluginId = source.let { file ->
                    java.security.MessageDigest.getInstance("SHA-256").let { digest ->
                        file.inputStream().use { input ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                digest.update(buffer, 0, count)
                            }
                        }
                        digest.digest().joinToString("") { "%02x".format(it) }
                    }
                }.orEmpty()
                val hostJar = root.resolve("$host-$pluginId-${BuildConfig.VERSION_CODE}-${BuildConfig.BUILD_ID}-$updatedAt.jar")
                if (!hostJar.isFile) unpackHost(host, hostJar, source)

                // Previous images and hosts remain valid for jobs using an older plugin.
                Installed(home, hostJar)
            } catch (e: IOException) {
                throw JvmUnavailableException("Could not unpack the patcher JVM", e)
            }
        }
    }

    // Unpacked beside the destination and renamed into place, so a half unpacked runtime left by
    // a process that died midway is never taken for a complete one
    private fun unpackRuntime(pack: File, abi: String, home: File) {
        val staging = File(home.path + STAGING_SUFFIX).apply { deleteRecursively() }

        ZipInputStream(XZInputStream(openAsset(pack, "$ASSETS_DIR/$abi.zip.xz").buffered())).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                val target = staging.resolve(entry.name)
                if (!target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                    throw IOException("Runtime entry ${entry.name} points outside of it")
                }

                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                }
            }
        }

        unpackImage(pack, abi, staging.resolve("lib/modules"))

        if (!staging.renameTo(home)) throw IOException("Could not move the runtime into $home")
        Log.i(tag, "Unpacked the patcher JVM for $abi")
    }

    // Ship the module image once; rebuild ABI-specific differences before publishing the runtime.
    private fun unpackImage(pack: File, abi: String, image: File) {
        image.parentFile?.mkdirs()
        val delta = "$abi.modules.delta.xz"
        if (ZipFile(pack).use { it.getEntry("assets/$ASSETS_DIR/$delta") } == null) {
            unpackXz(pack, "modules.xz", image)
            return
        }
        val base = File(image.path + ".base")
        try {
            unpackXz(pack, "modules.xz", base)
            RandomAccessFile(base, "r").use { baseImage ->
                XZInputStream(openAsset(pack, "$ASSETS_DIR/$delta").buffered()).use { input ->
                    image.outputStream().buffered().use { JvmImageDelta.apply(baseImage, input, it) }
                }
            }
        } finally {
            base.delete()
        }
    }

    private fun unpackXz(pack: File, asset: String, target: File) {
        XZInputStream(openAsset(pack, "$ASSETS_DIR/$asset").buffered()).use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
    }

    private fun unpackHost(host: String, hostJar: File, plugin: File) {
        val staging = File(hostJar.path + STAGING_SUFFIX)
        try {
            ZipFile(plugin).use { zip ->
                val entry = zip.getEntry("assets/jvm/$host.jar")
                    ?: throw JvmUnavailableException("This plugin has no JVM host; use its ART host")
                zip.getInputStream(entry).use { input ->
                    staging.outputStream().use { input.copyTo(it) }
                }
            }
            if (!staging.setReadOnly()) throw IOException("Cannot protect JVM host")
            if (!staging.renameTo(hostJar)) throw IOException("Could not publish JVM host")
        } finally { staging.delete() }
    }

    class Installed(val home: File, val hostJar: File) {
        /** The VM the runtime ships, the server one where there is one, as the 32-bit ARM one only has the client one. */
        val libjvm
            get() = VMS.map { home.resolve("lib/$it/libjvm.so") }.firstOrNull { it.isFile }
                ?: throw JvmUnavailableException("The patcher JVM ships no VM")

        /** The libraries of the runtime that others of it link against, dependencies first. */
        val preload get() = PRELOAD.map { home.resolve("lib/$it").path }
    }

    private companion object {
        const val ROOT_DIR = "jvm"
        const val ASSETS_DIR = "jvm"
        const val STAGING_SUFFIX = ".staging"

        val VMS = listOf("server", "client")

        // The app's linker namespace does not search the runtime's directory, so a library naming
        // one of these would not find it unless it is already loaded
        val PRELOAD = listOf(
            "libjava.so", "libnet.so", "libnio.so", "libzip.so",
            // Headless AWT libraries name these dependencies without their extracted path.
            "libawt.so", "libawt_headless.so", "libfreetype.so"
        )
    }
}
