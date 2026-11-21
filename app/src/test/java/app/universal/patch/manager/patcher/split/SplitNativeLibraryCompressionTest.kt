package app.universal.patch.manager.patcher.split

import com.reandroid.apk.APKLogger
import com.reandroid.apk.ApkModule
import com.reandroid.app.AndroidManifest
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SplitNativeLibraryCompressionTest {
    @Test
    fun `false extraction flag does not inflate compressed libraries`() =
        assertMergedNativeLibraries(false)

    @Test
    fun `stored libraries are compressed with extraction allowed`() =
        assertMergedNativeLibraries(true)

    @Test
    fun `missing extraction flag permits native library compression`() =
        assertMergedNativeLibraries(null)

    @Test
    fun `name-only extraction flags are removed from both elements`() =
        assertMergedNativeLibraries(null, nameOnlyFlags = true)

    @Test
    fun `patcher default keeps direct-loading libraries uncompressed`() =
        assertMergedNativeLibraries(false, compressNativeLibraries = false)

    @Test
    fun `patcher default keeps explicit extraction enabled`() =
        assertMergedNativeLibraries(true, compressNativeLibraries = false)

    @Test
    fun `patcher default keeps a missing extraction flag absent`() =
        assertMergedNativeLibraries(null, compressNativeLibraries = false)

    private fun assertMergedNativeLibraries(
        value: Boolean?,
        nameOnlyFlags: Boolean = false,
        compressNativeLibraries: Boolean = true
    ) {
        val workspace = createTempDirectory("split-native-compression").toFile()
        val payload = ByteArray(8192) { (it % 251).toByte() }
        try {
            val inputDir = workspace.resolve("splits").apply { mkdirs() }
            val manifest = AndroidManifestBlock().apply {
                newElement("manifest")
                packageName = "app.upm.test"
                versionCode = 1
                getOrCreateApplicationElement()
                setExtractNativeLibs(value)
                if (nameOnlyFlags) {
                    arrayOf(applicationElement, manifestElement).forEach {
                        it.createAttribute(AndroidManifest.NAME_extractNativeLibs, 0)
                            .setValueAsBoolean(false)
                    }
                }
                refreshFull()
            }
            val table = TableBlock().apply {
                packageArray.createNext().apply {
                    id = 0x7f
                    name = "app.upm.test"
                }
                refresh()
            }
            ZipOutputStream(inputDir.resolve("base.apk").outputStream()).use { zip ->
                writeEntry(zip, TableBlock.FILE_NAME, table.bytes, ZipEntry.STORED)
                writeEntry(zip, AndroidManifestBlock.FILE_NAME, manifest.bytes, ZipEntry.DEFLATED)
                writeEntry(zip, COMPRESSED_LIBRARY, payload, ZipEntry.DEFLATED)
            }
            val splitManifest = AndroidManifestBlock().apply {
                newElement("manifest")
                packageName = "app.upm.test"
                versionCode = 1
                getOrCreateApplicationElement()
                manifestElement.createAttribute("split", 0).setDataAsString("config.arm64_v8a")
                refreshFull()
            }
            ZipOutputStream(inputDir.resolve("split_config.arm64_v8a.apk").outputStream()).use { zip ->
                writeEntry(zip, AndroidManifestBlock.FILE_NAME, splitManifest.bytes, ZipEntry.DEFLATED)
                writeEntry(zip, STORED_LIBRARY, payload, ZipEntry.STORED)
            }

            val output = workspace.resolve("merged.apk")
            if (compressNativeLibraries) {
                ApkEditorMergeProcess.merge(inputDir, output, emptySet(), false, quietLogger, null, true)
            } else {
                ApkEditorMergeProcess.merge(inputDir, output, emptySet(), false, quietLogger)
            }

            ApkModule.loadApkFile(output).use { module ->
                val merged = module.androidManifest
                assertEquals(if (compressNativeLibraries) null else value, merged.isExtractNativeLibs)
                if (compressNativeLibraries) {
                    arrayOf(merged.applicationElement, merged.manifestElement).forEach { element ->
                        assertTrue(element.getAttributes {
                            it.nameId == AndroidManifest.ID_extractNativeLibs ||
                                it.name == AndroidManifest.NAME_extractNativeLibs
                        }.asSequence().none())
                    }
                }
                assertEquals("app.upm.test", module.packageName)
                assertEquals(1, module.versionCode)
            }
            ZipFile(output).use { zip ->
                val compressed = zip.getEntry(COMPRESSED_LIBRARY)
                val stored = zip.getEntry(STORED_LIBRARY)
                val expectedMethod = if (!compressNativeLibraries && value == false) {
                    ZipEntry.STORED
                } else {
                    ZipEntry.DEFLATED
                }
                assertEquals(expectedMethod, compressed.method)
                assertEquals(expectedMethod, stored.method)
                if (compressNativeLibraries) {
                    assertTrue(compressed.compressedSize < compressed.size)
                    assertTrue(stored.compressedSize < stored.size)
                }
                for (entry in listOf(compressed, stored)) {
                    assertContentEquals(payload, zip.getInputStream(entry).use { it.readBytes() })
                }
            }
        } finally {
            workspace.deleteRecursively()
        }
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, bytes: ByteArray, method: Int) {
        val entry = ZipEntry(name).apply {
            this.method = method
            if (method == ZipEntry.STORED) {
                size = bytes.size.toLong()
                compressedSize = size
                crc = CRC32().apply { update(bytes) }.value
            }
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private companion object {
        const val COMPRESSED_LIBRARY = "lib/arm64-v8a/libcompressed.so"
        const val STORED_LIBRARY = "lib/arm64-v8a/libstored.so"
        val quietLogger = object : APKLogger {
            override fun logMessage(msg: String) = Unit
            override fun logError(msg: String, tr: Throwable?) = Unit
            override fun logVerbose(msg: String) = Unit
        }
    }
}
