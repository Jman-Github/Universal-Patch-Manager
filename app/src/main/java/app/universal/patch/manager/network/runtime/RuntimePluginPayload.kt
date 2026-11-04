package app.universal.patch.manager.network.runtime

import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipException

/** The v21 legacy JVM host is nested in its legacy runtime archive, not the APK root. */
fun validateRuntimePluginPayload(kind: PatcherRuntimeKind, file: File, processAbis: List<String>) {
    ZipFile(file).use { zip ->
        val required = when (kind) {
            PatcherRuntimeKind.MORPHE -> listOf("classes.dex", "assets/jvm/upm-patcher-core.jar")
            PatcherRuntimeKind.REVANCED_V21 -> listOf(
                "classes.dex", "assets/jvm/revanced21-patcher-core.jar",
                "assets/runtimes/revanced-legacy-runtime.jar"
            )
            PatcherRuntimeKind.REVANCED_V22 -> listOf("classes.dex", "assets/jvm/revanced22-patcher-core.jar")
            PatcherRuntimeKind.RESEAM -> listOf("classes.dex")
            PatcherRuntimeKind.SHARED_SUPPORT -> listOf("assets/jvm/tools-patcher-core.jar", "assets/jvm/modules.xz")
        }
        val missing = required.filter { zip.getEntry(it)?.size?.let { size -> size > 0 } != true }
        require(missing.isEmpty()) {
            "${kind.displayName} runtime is missing required payloads: ${missing.joinToString()}"
        }
        if (kind == PatcherRuntimeKind.REVANCED_V21) {
            val legacyArchive = "assets/runtimes/revanced-legacy-runtime.jar"
            val legacyHost = "assets/jvm/revanced-legacy-patcher-core.jar"
            val message = "ReVanced v21 legacy runtime is missing a nonempty $legacyHost inside $legacyArchive"
            val hasHost = try {
                ZipInputStream(zip.getInputStream(zip.getEntry(legacyArchive)).buffered()).use { nested ->
                    generateSequence { nested.nextEntry }.any { entry ->
                        entry.name == legacyHost && !entry.isDirectory && nested.read() != -1
                    }
                }
            } catch (error: ZipException) {
                throw IllegalArgumentException(message, error)
            }
            require(hasHost) { message }
        }
        if (kind == PatcherRuntimeKind.RESEAM) {
            require(processAbis.any { zip.getEntry("lib/$it/libreseam-sdk-native.so")?.size?.let { size -> size > 0 } == true }) {
                "ReSeam has no native library for this process"
            }
        }
    }
}
