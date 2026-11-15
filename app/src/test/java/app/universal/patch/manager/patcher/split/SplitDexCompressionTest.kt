package app.universal.patch.manager.patcher.split

import com.reandroid.apk.APKLogger
import com.reandroid.apk.ApkModule
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.value.DataType
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SplitDexCompressionTest {
    @Test
    fun `base and renamed split DEX are compressed without changing their bytes`() =
        assertMergedDex(ZipEntry.DEFLATED)

    @Test
    fun `explicit false embedded DEX flag permits compression`() =
        assertMergedDex(ZipEntry.DEFLATED) {
            it.createAttribute("useEmbeddedDex", android.R.attr.useEmbeddedDex)
                .setValueAsBoolean(false)
        }

    @Test
    fun `embedded DEX stays stored even when input was compressed`() =
        assertMergedDex(ZipEntry.STORED) {
            it.createAttribute("useEmbeddedDex", android.R.attr.useEmbeddedDex)
                .setValueAsBoolean(true)
        }

    @Test
    fun `name-only embedded DEX flag keeps DEX stored`() =
        assertMergedDex(ZipEntry.STORED) {
            it.createAttribute("useEmbeddedDex", 0).setValueAsBoolean(true)
        }

    @Test
    fun `resource ID embedded DEX flag keeps DEX stored`() =
        assertMergedDex(ZipEntry.STORED) {
            it.createAttribute("obfuscated", android.R.attr.useEmbeddedDex)
                .setValueAsBoolean(true)
        }

    @Test
    fun `unresolved embedded DEX flag keeps DEX stored`() =
        assertMergedDex(ZipEntry.STORED) {
            it.createAttribute("useEmbeddedDex", android.R.attr.useEmbeddedDex)
                .setTypeAndData(DataType.REFERENCE, 0x7f010000)
        }

    private fun assertMergedDex(
        expectedMethod: Int,
        configureApplication: (ResXmlElement) -> Unit = {}
    ) {
        val workspace = createTempDirectory("split-dex-compression").toFile()
        val payloads = List(3) { index -> ByteArray(8192) { (it % (31 + index)).toByte() } }
        try {
            val inputDir = workspace.resolve("splits").apply { mkdirs() }
            val manifest = manifest().apply {
                configureApplication(applicationElement)
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
                writeEntry(zip, "classes.dex", payloads[0], ZipEntry.STORED)
                writeEntry(zip, "classes2.dex", payloads[1], ZipEntry.DEFLATED)
                writeEntry(zip, "assets/classes.dex", payloads[0], ZipEntry.STORED)
            }
            val splitManifest = manifest().apply {
                manifestElement.createAttribute("split", 0).setDataAsString("feature")
                refreshFull()
            }
            ZipOutputStream(inputDir.resolve("split_feature.apk").outputStream()).use { zip ->
                writeEntry(zip, AndroidManifestBlock.FILE_NAME, splitManifest.bytes, ZipEntry.DEFLATED)
                writeEntry(zip, "classes.dex", payloads[2], ZipEntry.STORED)
            }
            val output = workspace.resolve("merged.apk")
            ApkEditorMergeProcess.merge(inputDir, output, emptySet(), false, quietLogger)
            ZipFile(output).use { zip ->
                for (index in payloads.indices) {
                    val name = if (index == 0) "classes.dex" else "classes${index + 1}.dex"
                    val entry = zip.getEntry(name)
                    assertEquals(expectedMethod, entry.method, name)
                    assertContentEquals(payloads[index], zip.getInputStream(entry).use { it.readBytes() })
                    if (expectedMethod == ZipEntry.DEFLATED) {
                        assertTrue(entry.compressedSize < entry.size, name)
                    }
                }
                val asset = zip.getEntry("assets/classes.dex")
                assertEquals(ZipEntry.STORED, asset.method)
                assertContentEquals(payloads[0], zip.getInputStream(asset).use { it.readBytes() })
            }
            ApkModule.loadApkFile(output).use { module ->
                assertEquals("app.upm.test", module.packageName)
                assertEquals(1, module.versionCode)
            }
        } finally {
            workspace.deleteRecursively()
        }
    }

    private fun manifest() = AndroidManifestBlock().apply {
        newElement("manifest")
        packageName = "app.upm.test"
        versionCode = 1
        getOrCreateApplicationElement()
        refreshFull()
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
        val quietLogger = object : APKLogger {
            override fun logMessage(msg: String) = Unit
            override fun logError(msg: String, tr: Throwable?) = Unit
            override fun logVerbose(msg: String) = Unit
        }
    }
}
