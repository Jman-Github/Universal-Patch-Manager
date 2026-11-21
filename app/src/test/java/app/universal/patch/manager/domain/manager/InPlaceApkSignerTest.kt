package app.universal.patch.manager.domain.manager

import app.revanced.library.ApkSigner
import com.android.apksig.ApkVerifier
import com.android.apksig.apk.ApkUtils
import com.android.apksig.util.DataSources
import com.android.apksig.zip.ZipFormatException
import com.android.apksig.internal.zip.CentralDirectoryRecord
import com.android.apksig.internal.zip.LocalFileRecord
import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.archive.writer.ZipAligner
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.util.Date
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InPlaceApkSignerTest {
    @get:Rule
    val temporaryDirectory = TemporaryFolder()

    @Test
    fun `unsigned APK verifies with v2 on supported devices and retains ZIP data`() {
        val apk = unsignedApk()
        val contentsBefore = zipContents(apk)
        val entriesEnd = RandomAccessFile(apk, "r").use {
            ApkUtils.findZipSections(DataSources.asDataSource(it)).zipCentralDirectoryOffset.toInt()
        }
        val prefixBefore = apk.readBytes().copyOf(entriesEnd)

        signer(signingKey).sign(apk, 26)

        for (sdk in listOf(26, 28, 30, 36)) verify(apk, sdk, signingKey)
        assertContentEquals(prefixBefore, apk.readBytes().copyOf(entriesEnd))
        val contentsAfter = zipContents(apk)
        assertEquals(contentsBefore.keys, contentsAfter.keys)
        contentsBefore.forEach { (name, bytes) ->
            assertContentEquals(bytes, contentsAfter.getValue(name), name)
        }
    }

    @Test
    fun `re-signing replaces an existing signing block and certificate`() {
        val apk = unsignedApk()
        signer(otherSigningKey).sign(apk, 26)
        verify(apk, 26, otherSigningKey)
        val signedLength = apk.length()

        signer(signingKey).sign(apk, 26)
        signer(signingKey).sign(apk, 26)

        verify(apk, 26, signingKey)
        verify(apk, 36, signingKey)
        assertEquals(signedLength, apk.length())
    }

    @Test
    fun `4 KB aligned native libraries are realigned for 16 KB devices before signing`() {
        val apk = unsignedApk(libraryAlignment = 4096)
        val libraryName = "lib/arm64-v8a/libtest.so"
        val contentsBefore = zipContents(apk)
        val originalOffset = storedEntryOffset(apk, libraryName)
        assertEquals(0L, originalOffset % 4096)
        assertTrue(originalOffset % 16384 != 0L)

        signer(signingKey).sign(apk, 26)

        assertEquals(0L, storedEntryOffset(apk, libraryName) % 16384)
        verify(apk, 26, signingKey)
        verify(apk, 36, signingKey)
        val contentsAfter = zipContents(apk)
        contentsBefore.forEach { (name, bytes) ->
            assertContentEquals(bytes, contentsAfter.getValue(name), name)
        }
    }

    @Test
    fun `16 KB aligned native libraries use the fast path without moving ZIP entries`() {
        val apk = unsignedApk(libraryAlignment = 16384)
        val originalOffset = storedEntryOffset(apk, "lib/arm64-v8a/libtest.so")
        val entriesEnd = RandomAccessFile(apk, "r").use {
            ApkUtils.findZipSections(DataSources.asDataSource(it)).zipCentralDirectoryOffset.toInt()
        }
        val prefixBefore = apk.readBytes().copyOf(entriesEnd)

        signer(signingKey).sign(apk, 26)

        assertEquals(originalOffset, storedEntryOffset(apk, "lib/arm64-v8a/libtest.so"))
        assertContentEquals(prefixBefore, apk.readBytes().copyOf(entriesEnd))
        verify(apk, 26, signingKey)
    }

    @Test
    fun `misaligned stored entries are realigned before signing`() {
        val apk = unsignedApk(libraryAlignment = 1)
        val libraryName = "lib/arm64-v8a/libtest.so"
        val contentsBefore = zipContents(apk)
        assertTrue(storedEntryOffset(apk, libraryName) % 4096 != 0L)

        signer(signingKey).sign(apk, 26)

        assertEquals(0L, storedEntryOffset(apk, libraryName) % 16384)
        val manifestName = "AndroidManifest.xml"
        assertEquals(0L, storedEntryOffset(apk, manifestName) % 4)
        verify(apk, 26, signingKey)
        val contentsAfter = zipContents(apk)
        contentsBefore.forEach { (name, bytes) ->
            assertContentEquals(bytes, contentsAfter.getValue(name), name)
        }
    }

    @Test
    fun `inconsistent compressed entry headers are rejected before writing a signature`() {
        val apk = unsignedApk()
        RandomAccessFile(apk, "rw").use { file ->
            val source = DataSources.asDataSource(file)
            val sections = ApkUtils.findZipSections(source)
            val directory = source.getByteBuffer(
                sections.zipCentralDirectoryOffset, sections.zipCentralDirectorySizeBytes.toInt()
            ).order(ByteOrder.LITTLE_ENDIAN)
            val entry = generateSequence {
                if (directory.hasRemaining()) CentralDirectoryRecord.getRecord(directory) else null
            }.first { it.name == "assets/payload.txt" }
            assertEquals(ZipEntry.DEFLATED, entry.compressionMethod.toInt())
            file.seek(entry.localFileHeaderOffset + 6)
            val flags = file.readUnsignedByte()
            file.seek(entry.localFileHeaderOffset + 6)
            file.writeByte(flags xor 8)
        }
        val before = apk.readBytes()

        assertFailsWith<ZipFormatException> { signer(signingKey).sign(apk, 26) }

        assertContentEquals(before, apk.readBytes())
    }

    @Test
    fun `cached signer can sign multiple APKs`() {
        val cachedSigner = signer(signingKey)
        val first = unsignedApk()
        val second = unsignedApk()
        cachedSigner.sign(first, 26)
        cachedSigner.sign(second, 26)
        verify(first, 26, signingKey)
        verify(second, 26, signingKey)
    }

    @Test
    fun `older device requests are rejected without changing the APK`() {
        val apk = unsignedApk()
        val before = apk.readBytes()
        assertFailsWith<IllegalArgumentException> { signer(signingKey).sign(apk, 23) }
        assertContentEquals(before, apk.readBytes())
    }

    @Test
    fun `cancelled signing does not write a signature`() {
        val apk = unsignedApk()
        val before = apk.readBytes()
        try {
            Thread.currentThread().interrupt()
            assertFailsWith<InterruptedIOException> { signer(signingKey).sign(apk, 26) }
        } finally {
            Thread.interrupted()
        }
        assertContentEquals(before, apk.readBytes())
    }

    @Test
    fun `P256 signs through both signing paths and preserves certificate identity`() {
        val (key, certificate) = generateP256SigningKey("UPM EC", Date(System.currentTimeMillis() + 86_400_000))
        for (alignment in listOf(null, 4096)) {
            val apk = unsignedApk(alignment)
            InPlaceApkSigner("ec", key, listOf(certificate)).sign(apk, 26)
            for (sdk in listOf(26, 28, 36)) {
                val result = ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(sdk)
                    .setMaxCheckedPlatformVersion(sdk).build().verify()
                assertTrue(result.isVerified, result.errors.toString())
                assertEquals(listOf(certificate), result.signerCertificates)
            }
        }
        for (minSdk in listOf(16, 18, 21, 28)) {
            val input = unsignedApk(minSdk = minSdk)
            val payload = zipContents(input)
            val output = temporaryDirectory.newFile("ec-signed-$minSdk.apk")
            val config = com.android.apksig.ApkSigner.SignerConfig.Builder("ec", key, listOf(certificate)).build()
            com.android.apksig.ApkSigner.Builder(listOf(config)).setInputApk(input).setOutputApk(output)
                .supportSigningKey(key, input)
                .setV1SigningEnabled(true).setV2SigningEnabled(true).setV3SigningEnabled(true).build().sign()
            val result = ApkVerifier.Builder(output).setMinCheckedPlatformVersion(maxOf(18, minSdk))
                .setMaxCheckedPlatformVersion(36).build().verify()
            assertTrue(result.isVerified, result.errors.toString())
            if (minSdk < 24) assertTrue(result.isVerifiedUsingV1Scheme)
            if (minSdk < 28) assertTrue(result.isVerifiedUsingV2Scheme)
            assertTrue(result.isVerifiedUsingV3Scheme)
            assertEquals(listOf(certificate), result.signerCertificates)
            val contents = zipContents(output)
            payload.forEach { (name, bytes) -> assertContentEquals(bytes, contents.getValue(name), name) }
        }
    }

    @Test
    fun `RSA keeps legacy platform signing compatibility`() {
        val (key, certificate) = generateSigningKey("RSA, Unicode ?", Date(System.currentTimeMillis() + 86_400_000), SigningKeyAlgorithm.RSA)
        assertEquals(4096, (certificate.publicKey as java.security.interfaces.RSAPublicKey).modulus.bitLength())
        val input = unsignedApk(minSdk = 16)
        val output = temporaryDirectory.newFile("rsa-legacy.apk")
        val config = com.android.apksig.ApkSigner.SignerConfig.Builder(
            "rsa", key, listOf(certificate)).build()
        com.android.apksig.ApkSigner.Builder(listOf(config)).setInputApk(input).setOutputApk(output)
            .supportSigningKey(key, input)
            .setV1SigningEnabled(true).setV2SigningEnabled(true).setV3SigningEnabled(true).build().sign()
        val result = ApkVerifier.Builder(output).setMinCheckedPlatformVersion(16)
            .setMaxCheckedPlatformVersion(36).build().verify()
        assertTrue(result.isVerified, result.errors.toString())
        assertTrue(result.isVerifiedUsingV1Scheme)
        assertEquals(listOf(certificate), result.signerCertificates)
    }

    private fun unsignedApk(libraryAlignment: Int? = null, minSdk: Int = 21): File {
        val apk = temporaryDirectory.newFile("input-${System.nanoTime()}.apk")
        ApkModule().use { module ->
            val manifest = AndroidManifestBlock().apply {
                packageName = "app.upm.signing.test"
                versionCode = 1
                versionName = "1"
                setMinSdkVersion(minSdk)
            }
            module.setManifest(manifest)
            module.add(ByteInputSource("test payload".toByteArray(), "assets/payload.txt"))
            if (libraryAlignment == null) {
                module.writeApk(apk)
            } else {
                module.add(ByteInputSource(ByteArray(100), "lib/arm64-v8a/libtest.so").apply {
                    method = ZipEntry.STORED
                })
                module.uncompressedFiles.addPath("lib/arm64-v8a/libtest.so")
                module.uncompressedFiles.addPath("AndroidManifest.xml")
                module.createApkFileWriter(apk).apply {
                    zipAligner = ZipAligner().apply {
                        setDefaultAlignment(if (libraryAlignment == 1) 1 else 4)
                        setFileAlignment({ it.endsWith(".so") }, libraryAlignment)
                    }
                }.write()
            }
        }
        return apk
    }

    private fun storedEntryOffset(apkFile: File, name: String): Long =
        RandomAccessFile(apkFile, "r").use { file ->
            val apk = DataSources.asDataSource(file)
            val sections = ApkUtils.findZipSections(apk)
            val centralDirectory = apk.getByteBuffer(
                sections.zipCentralDirectoryOffset, sections.zipCentralDirectorySizeBytes.toInt()
            ).order(ByteOrder.LITTLE_ENDIAN)
            while (centralDirectory.hasRemaining()) {
                val record = CentralDirectoryRecord.getRecord(centralDirectory)
                if (record.name != name) continue
                assertEquals(ZipEntry.STORED, record.compressionMethod.toInt())
                val localRecord = LocalFileRecord.getRecord(apk, record, sections.zipCentralDirectoryOffset)
                return@use localRecord.startOffsetInArchive + localRecord.dataStartOffsetInRecord
            }
            error("Missing stored entry: $name")
        }

    private fun zipContents(apk: File) = ZipFile(apk).use { zip ->
        zip.entries().asSequence().associate { entry ->
            entry.name to zip.getInputStream(entry).use { it.readBytes() }
        }
    }

    private fun signer(key: ApkSigner.PrivateKeyCertificatePair) =
        InPlaceApkSigner("test", key.privateKey, listOf(key.certificate))

    private fun verify(apk: File, sdk: Int, key: ApkSigner.PrivateKeyCertificatePair) {
        val result = ApkVerifier.Builder(apk)
            .setMinCheckedPlatformVersion(sdk)
            .setMaxCheckedPlatformVersion(sdk)
            .build().verify()
        assertTrue(result.isVerified, result.errors.toString())
        assertTrue(result.isVerifiedUsingV2Scheme)
        assertFalse(result.isVerifiedUsingV1Scheme)
        assertFalse(result.isVerifiedUsingV3Scheme)
        assertEquals(listOf(key.certificate), result.signerCertificates)
    }

    private companion object {
        val signingKey by lazy {
            ApkSigner.newPrivateKeyCertificatePair("UPM test", Date(System.currentTimeMillis() + 86_400_000))
        }
        val otherSigningKey by lazy {
            ApkSigner.newPrivateKeyCertificatePair("UPM other test", Date(System.currentTimeMillis() + 86_400_000))
        }
    }
}
