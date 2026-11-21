package app.universal.patch.manager.domain.installer

import app.universal.patch.manager.patcher.split.SplitApkFiles
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InstallApkFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun splits(): File = temporary.newFile("output.apk").also { source ->
        val directory = temporary.newFolder()
        val files = listOf(
            writeApk(directory.resolve("config.en.apk"), "config.en"),
            writeApk(directory.resolve("base.apk"))
        )
        SplitApkFiles.pack(files, source)
    }

    private fun writeApk(file: File, splitName: String? = null): File {
        val manifest = AndroidManifestBlock().apply {
            getOrCreateElement("manifest")
            packageName = "test.splits"
            versionCode = 1
            if (splitName != null) setSplit(splitName, true)
            refreshFull()
        }
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(manifest.bytes)
            zip.closeEntry()
        }
        return file
    }

    @Test fun downloadsWithPackageNamedBaseInstallBaseFirst() = runBlocking {
        val directory = temporary.newFolder()
        val config = writeApk(directory.resolve("config.en.apk"), "config.en")
        val base = writeApk(directory.resolve("test.splits.apk"))
        val source = temporary.newFile("download.xapk")
        ZipOutputStream(source.outputStream()).use { zip ->
            for (apk in listOf(config, base)) {
                zip.putNextEntry(ZipEntry(apk.name))
                apk.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }

        withInstallApkFiles(source, temporary.newFolder()) { files ->
            assertEquals(listOf(base.name, config.name), files.map { it.name })
            assertArrayEquals(base.readBytes(), files.first().readBytes())
        }
    }

    @Test fun splitComponentsLiveUntilInstallationCompletesAndAreCleanedAfterwards() = runBlocking {
        val source = splits()
        val cache = temporary.newFolder()
        var extracted = emptyList<File>()
        val result = withInstallApkFiles(source, cache) { files ->
            extracted = files
            assertEquals(listOf("base.apk", "config.en.apk"), files.map { it.name })
            assertTrue(files.all { it.isFile })
            "installed"
        }
        assertEquals("installed", result)
        assertTrue(extracted.none { it.exists() })
        assertTrue(source.isFile)
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test fun cancellationCleansExtractedComponentsWithoutDeletingSource() = runBlocking {
        val source = splits()
        val cache = temporary.newFolder()
        try {
            withInstallApkFiles(source, cache) { throw CancellationException("Cancelled") }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertTrue(cache.listFiles()!!.isEmpty())
            assertTrue(source.isFile)
        }
    }

    @Test fun normalApkUsesTheOriginalFileWithoutExtractingIt() = runBlocking {
        val source = temporary.newFile("single.apk").apply { writeText("single") }
        val cache = temporary.newFolder()
        withInstallApkFiles(source, cache) { files -> assertEquals(listOf(source), files) }
        assertTrue(source.isFile)
        assertTrue(cache.listFiles()!!.isEmpty())
    }
}
