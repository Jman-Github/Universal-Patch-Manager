package app.universal.patch.manager.network.runtime

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test

class RuntimePluginPayloadTest {
    private fun archive(vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                entries.forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content)
                    zip.closeEntry()
                }
            }
        }.toByteArray()

    private fun withApk(bytes: ByteArray, block: (File) -> Unit) {
        val apk = Files.createTempFile("runtime-payload-", ".apk").toFile()
        try { apk.writeBytes(bytes); block(apk) } finally { apk.delete() }
    }

    @Test fun v21AcceptsItsNestedLegacyHostWithoutRequiringADuplicateAtTheApkRoot() {
        val legacy = archive("classes.dex" to byteArrayOf(1),
            "assets/jvm/revanced-legacy-patcher-core.jar" to byteArrayOf(2))
        withApk(archive("classes.dex" to byteArrayOf(1),
            "assets/jvm/revanced21-patcher-core.jar" to byteArrayOf(2),
            "assets/runtimes/revanced-legacy-runtime.jar" to legacy)) {
            validateRuntimePluginPayload(PatcherRuntimeKind.REVANCED_V21, it, listOf("x86_64"))
        }
    }

    @Test fun legacyArchiveMustContainANonemptyNestedJvmHost() {
        val hostPath = "assets/jvm/revanced-legacy-patcher-core.jar"
        val invalidArchives = listOf(
            archive("classes.dex" to byteArrayOf(1)),
            archive("classes.dex" to byteArrayOf(1), hostPath to byteArrayOf()),
            byteArrayOf(1, 2, 3)
        )
        invalidArchives.forEach { legacy ->
            withApk(archive("classes.dex" to byteArrayOf(1),
                "assets/jvm/revanced21-patcher-core.jar" to byteArrayOf(2),
                "assets/runtimes/revanced-legacy-runtime.jar" to legacy)) {
                val error = assertThrows(IllegalArgumentException::class.java) {
                    validateRuntimePluginPayload(PatcherRuntimeKind.REVANCED_V21, it, listOf("x86_64"))
                }
                assertTrue(error.message!!.contains(hostPath))
            }
        }
    }

    @Test fun missingLegacyArchiveReportsTheRuntimeAndMissingEntry() {
        withApk(archive("classes.dex" to byteArrayOf(1),
            "assets/jvm/revanced21-patcher-core.jar" to byteArrayOf(2))) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                validateRuntimePluginPayload(PatcherRuntimeKind.REVANCED_V21, it, listOf("x86_64"))
            }
            assertTrue(error.message!!.contains("ReVanced v21"))
            assertTrue(error.message!!.contains("assets/runtimes/revanced-legacy-runtime.jar"))
        }
    }

    @Test fun emptyHostAndWrongNativeAbiAreRejected() {
        withApk(archive("classes.dex" to byteArrayOf(1),
            "assets/jvm/upm-patcher-core.jar" to byteArrayOf())) {
            assertThrows(IllegalArgumentException::class.java) {
                validateRuntimePluginPayload(PatcherRuntimeKind.MORPHE, it, emptyList())
            }
        }
        withApk(archive("classes.dex" to byteArrayOf(1),
            "lib/arm64-v8a/libreseam-sdk-native.so" to byteArrayOf(2))) {
            assertThrows(IllegalArgumentException::class.java) {
                validateRuntimePluginPayload(PatcherRuntimeKind.RESEAM, it, listOf("x86_64"))
            }
        }
    }

    @Test fun actualBuiltPluginsPassTheSameChecksAsImports() {
        val root = System.getenv("RUNTIME_APK_ROOT")
        assumeNotNull(root)
        val modules = mapOf(
            PatcherRuntimeKind.MORPHE to "morphe-runtime/morphe-plugin.apk",
            PatcherRuntimeKind.RESEAM to "reseam-runtime/reseam-runtime.apk",
            PatcherRuntimeKind.REVANCED_V21 to "revanced.v21-runtime-plugin/revanced.v21-plugin.apk",
            PatcherRuntimeKind.REVANCED_V22 to "revanced.v22-runtime-plugin/revanced.v22-plugin.apk",
            PatcherRuntimeKind.SHARED_SUPPORT to "shared-support/shared-support-plugin.apk"
        )
        modules.forEach { (kind, path) ->
            val apk = File(root!!, path.substringBefore('/') + "/build/outputs/apk/release/" + path.substringAfter('/'))
            assertTrue("Missing fixture APK: $apk", apk.isFile)
            validateRuntimePluginPayload(kind, apk, listOf("x86_64", "arm64-v8a", "armeabi-v7a"))
        }
        System.getenv("RUNTIME_V21_RELEASE_APK")?.let {
            validateRuntimePluginPayload(PatcherRuntimeKind.REVANCED_V21, File(it), listOf("x86_64"))
        }
    }
}
