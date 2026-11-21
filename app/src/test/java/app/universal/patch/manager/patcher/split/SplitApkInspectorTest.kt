package app.universal.patch.manager.patcher.split

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking

class SplitApkInspectorTest {
    @Test
    fun `base lookup ignores a larger feature apk`() = runBlocking {
        val workspace = createTempDirectory("split-inspector").toFile()
        try {
            val base = makeApk()
            val feature = makeApk("features.large")
            val archive = workspace.resolve("download.xapk")
            ZipOutputStream(archive.outputStream()).use { zip ->
                for ((name, bytes) in listOf("feature-large.apk" to feature, "test.download.apk" to base)) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }

            val extracted = assertNotNull(
                SplitApkInspector.extractRepresentativeApk(archive, workspace, requireBase = true)
            )
            try {
                assertContentEquals(base, extracted.file.readBytes())
            } finally {
                extracted.cleanup()
            }
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `archive validation runs before base manifest inspection`() = runBlocking {
        val workspace = createTempDirectory("split-inspector").toFile()
        try {
            val archive = workspace.resolve("download.xapk")
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("base.apk"))
                zip.write(byteArrayOf(1))
                zip.closeEntry()
            }

            val failure = assertFailsWith<IllegalArgumentException> {
                SplitApkInspector.extractRepresentativeApk(
                    archive,
                    workspace,
                    validateEntry = { throw IllegalArgumentException("Archive entry rejected") },
                    requireBase = true
                )
            }
            assertEquals("Archive entry rejected", failure.message)
        } finally {
            workspace.deleteRecursively()
        }
    }

    private fun makeApk(split: String? = null): ByteArray {
        val manifest = AndroidManifestBlock().apply {
            getOrCreateElement("manifest")
            packageName = "test.download"
            versionCode = 1
            if (split != null) setSplit(split, true)
            refreshFull()
        }
        val apk = ByteArrayOutputStream()
        ZipOutputStream(apk).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(manifest.bytes)
            zip.closeEntry()
            if (split != null) {
                zip.putNextEntry(ZipEntry("classes.dex"))
                zip.write(ByteArray(8192))
                zip.closeEntry()
            }
        }
        return apk.toByteArray()
    }

    @Test
    fun `retained split archive exposes its base apk`() = runBlocking {
        val workspace = createTempDirectory("split-inspector").toFile()
        val archive = workspace.resolve("original.apks")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("base.apk"))
            zip.write("base-content".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("split_config.en.apk"))
            zip.write("config-content".toByteArray())
            zip.closeEntry()
        }

        val extracted = assertNotNull(
            SplitApkInspector.extractRepresentativeApk(archive, workspace)
        )
        try {
            assertEquals("base-content", extracted.file.readText())
        } finally {
            extracted.cleanup()
        }
        assertFalse(extracted.file.exists())
        workspace.deleteRecursively()
        Unit
    }
}
