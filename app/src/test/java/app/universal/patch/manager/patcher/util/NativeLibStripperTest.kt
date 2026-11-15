package app.universal.patch.manager.patcher.util

import app.universal.patch.manager.domain.manager.InPlaceApkSigner
import app.universal.patch.manager.domain.manager.generateP256SigningKey
import com.android.apksig.ApkVerifier
import com.android.apksig.apk.ApkUtils
import com.android.apksig.internal.zip.LocalFileRecord
import com.android.apksig.internal.zip.ZipUtils
import com.android.apksig.util.DataSources
import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Date
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class NativeLibStripperTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun rawPayloadsMetadataAndAlignmentArePreserved() = runBlocking {
        val apk = fixture()
        val before = payloads(apk)
        val compressed = rawPayloads(apk)
        assertTrue(NativeLibStripper.strip(apk, listOf("arm64-v8a", "x86")))
        val after = payloads(apk)
        assertEquals(before.keys.filterNot { it.startsWith("lib/x86/") }.toSet(), after.keys)
        val rawAfter = rawPayloads(apk)
        after.forEach { (name, bytes) ->
            assertContentEquals(before.getValue(name), bytes, name)
            assertContentEquals(compressed.getValue(name), rawAfter.getValue(name), name)
        }
        ZipFile(apk).use { zip ->
            assertEquals("archive comment", zip.comment)
            assertEquals("entry comment", zip.getEntry("assets/data").comment)
            assertEquals(ZipEntry.STORED, zip.getEntry("lib/arm64-v8a/libtest.so").method)
            assertEquals(ZipEntry.DEFLATED, zip.getEntry("assets/data").method)
        }
        RandomAccessFile(apk, "r").use { file ->
            val source = DataSources.asDataSource(file)
            val sections = ApkUtils.findZipSections(source)
            for (record in ZipUtils.parseZipCentralDirectory(source, sections)) {
                if (record.compressionMethod.toInt() != ZipEntry.STORED) continue
                val local = LocalFileRecord.getRecord(source, record, sections.zipCentralDirectoryOffset)
                val alignment = if (record.name.endsWith(".so")) 16384 else 4
                assertEquals(0L, (local.startOffsetInArchive + local.dataStartOffsetInRecord) % alignment)
            }
        }
    }

    @Test fun progressAdvancesDuringCopyAndCompletesAfterReplacement() = runBlocking {
        for (fallback in listOf(false, true)) {
            val apk = fixture(large = true, hugeExtra = fallback)
            val values = mutableListOf<Int>()
            assertTrue(NativeLibStripper.strip(apk, listOf("arm64-v8a"), onProgress = { percent ->
                values += percent
                if (percent == 100) {
                    assertFalse(payloads(apk).containsKey("lib/x86/libtest.so"))
                }
            }))
            assertEquals(0, values.first())
            assertEquals(100, values.last())
            assertTrue(values.any { it in 1..98 }, values.toString())
            assertTrue(values.zipWithNext().all { (a, b) -> b > a }, values.toString())
            assertTrue(values.size <= 101)
        }
    }

    @Test fun noOpReportsCompletionWithoutChangingInput() = runBlocking {
        val apk = fixture(onlyOneAbi = true)
        val original = apk.readBytes()
        val values = mutableListOf<Int>()
        assertFalse(NativeLibStripper.strip(apk, listOf("arm64-v8a"), onProgress = values::add))
        assertEquals(listOf(0, 100), values)
        assertContentEquals(original, apk.readBytes())
    }

    @Test fun preferenceOrderIsPreserved() = runBlocking {
        val apk = fixture()
        assertTrue(NativeLibStripper.strip(apk, listOf("x86", "arm64-v8a")))
        assertTrue(payloads(apk).containsKey("lib/x86/libtest.so"))
        assertFalse(payloads(apk).containsKey("lib/arm64-v8a/libtest.so"))
    }

    @Test fun noOpDoesNotRewriteTheArchive() = runBlocking {
        for (abis in listOf(emptyList(), listOf("unknown"))) {
            val apk = fixture()
            val before = apk.readBytes()
            assertFalse(NativeLibStripper.strip(apk, abis))
            assertContentEquals(before, apk.readBytes())
        }
        val apk = fixture(onlyOneAbi = true)
        val before = apk.readBytes()
        assertFalse(NativeLibStripper.strip(apk, listOf("arm64-v8a")))
        assertContentEquals(before, apk.readBytes())
        val dirsOnly = temporary.newFile()
        ZipOutputStream(dirsOnly.outputStream()).use {
            it.putNextEntry(ZipEntry("lib/arm64-v8a/a")); it.write(1); it.closeEntry()
            it.putNextEntry(ZipEntry("lib/x86/")); it.closeEntry()
        }
        val dirsBefore = dirsOnly.readBytes()
        assertFalse(NativeLibStripper.strip(dirsOnly, listOf("arm64-v8a")))
        assertContentEquals(dirsBefore, dirsOnly.readBytes())
    }

    @Test fun cancellationDuringRawCopyLeavesOriginalAndCleansTemporaryFile() = runBlocking {
        val apk = fixture(large = true)
        val original = apk.readBytes()
        val filesBefore = temporary.root.list()!!.toSet()
        var checks = 0
        assertFailsWith<CancellationException> {
            NativeLibStripper.strip(apk, listOf("arm64-v8a")) {
                if (++checks == 40) throw CancellationException("test")
            }
        }
        assertEquals(40, checks)
        assertContentEquals(original, apk.readBytes())
        assertEquals(filesBefore, temporary.root.list()!!.toSet())
    }

    @Test fun malformedRetainedEntryCannotReplaceOriginal() = runBlocking {
        val apk = fixture()
        val raw = apk.readBytes()
        raw[8] = 99 // Break local compression method, leaving the directory intact.
        apk.writeBytes(raw)
        assertFails { NativeLibStripper.strip(apk, listOf("arm64-v8a")) }
        assertContentEquals(raw, apk.readBytes())
        assertFalse(temporary.root.list()!!.any { it.startsWith("abi-stripped-") })
    }

    @Test fun oversizedAlignmentExtraUsesStreamingFallback() = runBlocking {
        val apk = fixture(hugeExtra = true)
        val before = payloads(apk)
        assertTrue(NativeLibStripper.strip(apk, listOf("arm64-v8a")))
        payloads(apk).forEach { (name, bytes) -> assertContentEquals(before.getValue(name), bytes) }
    }

    @Test fun previouslySignedApksCanBeStrippedSignedAndVerified() = runBlocking {
        val apk = temporary.newFile("real.apk")
        ApkModule().use { module ->
            module.setManifest(AndroidManifestBlock().apply {
                packageName = "app.upm.nativecopy.test"; versionCode = 1; versionName = "1"; setMinSdkVersion(26)
            })
            for (abi in listOf("arm64-v8a", "x86")) {
                module.add(ByteInputSource(ByteArray(200) { 42 }, "lib/$abi/libtest.so").apply { method = 0 })
            }
            module.writeApk(apk)
        }
        val (key, certificate) = generateP256SigningKey("test", Date(System.currentTimeMillis() + 86400000))
        val signer = InPlaceApkSigner("test", key, listOf(certificate))
        signer.sign(apk, 26)
        assertTrue(NativeLibStripper.strip(apk, listOf("arm64-v8a")))
        signer.sign(apk, 26)
        for (sdk in listOf(26, 28, 36)) {
            val result = ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(sdk).setMaxCheckedPlatformVersion(sdk).build().verify()
            assertTrue(result.isVerified, result.errors.toString())
            assertEquals(listOf(certificate), result.signerCertificates)
        }
        assertFalse(payloads(apk).containsKey("lib/x86/libtest.so"))
    }

    @Test fun mixedCompressionUnicodeAndDirectoriesRemainReadable() = runBlocking {
        repeat(20) { iteration ->
            val file = temporary.newFile()
            val random = Random(iteration.toLong())
            val names = listOf("lib/arm64-v8a/", "lib/x86/", "assets/empty-folder/", "assets/名前", "lib/arm64-v8a/lib名前.so", "lib/x86/libother.so", "classes.dex", "assets/empty")
            val contents = names.associateWith { name -> if (name.endsWith("/") || name.endsWith("empty")) byteArrayOf() else ByteArray(random.nextInt(4096)+1).also(random::nextBytes) }
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.setComment("試験")
                for (name in names.shuffled(kotlin.random.Random(iteration))) {
                    val bytes = contents.getValue(name)
                    val entry = ZipEntry(name).apply {
                        comment = "名前-$iteration"
                        extra = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN).putShort(0x1234).putShort(5).put(byteArrayOf(1,2,3,4,5)).array()
                        if (random.nextBoolean()) {
                            method = 0; size = bytes.size.toLong(); crc = CRC32().apply { update(bytes) }.value
                        }
                    }
                    zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry()
                }
            }
            assertTrue(NativeLibStripper.strip(file, listOf("arm64-v8a")))
            val expected = contents.filterKeys { !it.startsWith("lib/x86/") }
            ZipFile(file).use { zip ->
                assertEquals("試験", zip.comment)
                assertEquals(expected.keys, zip.entries().asSequence().map { it.name }.toSet())
                for ((name, bytes) in expected) {
                    assertContentEquals(bytes, zip.getInputStream(zip.getEntry(name)).use { it.readBytes() })
                    assertEquals("名前-$iteration", zip.getEntry(name).comment)
                }
            }
            val sequential = linkedMapOf<String, ByteArray>()
            ZipInputStream(file.inputStream()).use { input ->
                var entry = input.nextEntry
                while (entry != null) {
                    sequential[entry.name] = input.readBytes()
                    input.closeEntry()
                    entry = input.nextEntry
                }
            }
            assertEquals(expected.keys, sequential.keys)
            for ((name, bytes) in expected) assertContentEquals(bytes, sequential.getValue(name))
        }
    }

    @Test fun existingZipalignPaddingDoesNotCorruptExtraFields() = runBlocking {
        for (paddingSize in 1..3) {
            val apk = temporary.newFile()
            ZipOutputStream(apk.outputStream()).use { zip ->
                for (name in listOf("assets/message.properties", "lib/x86/lib.so", "lib/arm64-v8a/lib.so")) {
                    val bytes = ByteArray(37) { it.toByte() }
                    val entry = ZipEntry(name).apply {
                        method = ZipEntry.STORED
                        size = bytes.size.toLong()
                        crc = CRC32().apply { update(bytes) }.value
                        extra = ByteArray(paddingSize)
                    }
                    zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry()
                }
            }
            val before = payloads(apk)
            assertTrue(NativeLibStripper.strip(apk, listOf("arm64-v8a")))
            payloads(apk).forEach { (name, bytes) -> assertContentEquals(before.getValue(name), bytes) }
            // Strict ZIP readers parse each complete TLV header; only a short trailing
            // padding fragment may remain. Exercise local fields, not just the directory.
            RandomAccessFile(apk, "r").use { file ->
                val source = DataSources.asDataSource(file)
                val sections = ApkUtils.findZipSections(source)
                for (record in ZipUtils.parseZipCentralDirectory(source, sections)) {
                    val extra = LocalFileRecord.getRecord(source, record, sections.zipCentralDirectoryOffset)
                        .extra.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                    while (extra.remaining() >= 4) {
                        extra.short
                        val size = extra.short.toInt() and 0xffff
                        assertTrue(size <= extra.remaining(), "Malformed extra field for " + record.name)
                        extra.position(extra.position() + size)
                    }
                    assertTrue(extra.remaining() <= 3)
                }
            }
        }
    }

    private fun fixture(onlyOneAbi: Boolean = false, large: Boolean = false, hugeExtra: Boolean = false): File {
        val apk = temporary.newFile()
        ZipOutputStream(apk.outputStream()).use { zip ->
            zip.setComment("archive comment")
            val data = ByteArray(if (large) 8 * 1024 * 1024 else 4096)
            Random(42).nextBytes(data)
            for (name in listOf("assets/data", "lib/arm64-v8a/libtest.so", "lib/x86/libtest.so", "assets/lib/x86/keep")) {
                if (onlyOneAbi && name.startsWith("lib/x86/")) continue
                val entry = ZipEntry(name).apply {
                    comment = "entry comment"
                    if (name.endsWith(".so")) {
                        method = ZipEntry.STORED; size = data.size.toLong()
                        crc = CRC32().apply { update(data) }.value
                    }
                    if (hugeExtra && name == "lib/arm64-v8a/libtest.so") {
                        extra = ByteBuffer.allocate(65520).order(ByteOrder.LITTLE_ENDIAN)
                            .putShort(0x1234).putShort(65516.toShort()).array()
                    }
                }
                zip.putNextEntry(entry); zip.write(data); zip.closeEntry()
            }
        }
        return apk
    }

    private fun payloads(apk: File): Map<String, ByteArray> = ZipFile(apk).use { zip ->
        zip.entries().asSequence().associate { it.name to zip.getInputStream(it).use { input -> input.readBytes() } }
    }

    private fun rawPayloads(apk: File): Map<String, ByteArray> = RandomAccessFile(apk, "r").use { file ->
        val source = DataSources.asDataSource(file)
        val sections = ApkUtils.findZipSections(source)
        ZipUtils.parseZipCentralDirectory(source, sections).associate { record ->
            val local = LocalFileRecord.getRecord(source, record, sections.zipCentralDirectoryOffset)
            val bytes = ByteArray(record.compressedSize.toInt())
            file.seek(local.startOffsetInArchive + local.dataStartOffsetInRecord); file.readFully(bytes)
            record.name to bytes
        }
    }
}
