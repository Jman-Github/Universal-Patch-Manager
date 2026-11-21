package app.universal.patch.manager.patcher.util

import com.android.apksig.apk.ApkUtils
import com.android.apksig.internal.zip.CentralDirectoryRecord
import com.android.apksig.internal.zip.LocalFileRecord
import com.android.apksig.util.DataSink
import com.android.apksig.util.DataSources
import com.android.apksig.zip.ZipFormatException
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry

/** Rebuild the ZIP directory while copying retained entry payloads without inflating them. */
internal object NativeLibRawCopy {
    fun rewrite(
        input: File, output: File, keep: (String) -> Boolean, checkCancelled: () -> Unit,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ) {
        RandomAccessFile(input, "r").use { file ->
            val source = DataSources.asDataSource(file)
            val sections = ApkUtils.findZipSections(source)
            if (sections.zipCentralDirectorySizeBytes > Int.MAX_VALUE) {
                throw ZipFormatException("ZIP directory is too large for raw copying")
            }
            // ZipUtils.parseZipCentralDirectory omits directories for signing. Stripping must
            // retain them too, including empty asset directories that apps may inspect.
            val directory = source.getByteBuffer(
                sections.zipCentralDirectoryOffset, sections.zipCentralDirectorySizeBytes.toInt()
            ).order(ByteOrder.LITTLE_ENDIAN)
            val records = ArrayList<CentralDirectoryRecord>(sections.zipCentralDirectoryRecordCount)
            repeat(sections.zipCentralDirectoryRecordCount) {
                checkCancelled()
                records.add(CentralDirectoryRecord.getRecord(directory))
            }
            if (directory.hasRemaining()) throw ZipFormatException("Unexpected ZIP directory data")
            val names = HashSet<String>()
            val retained = records.filter {
                checkCancelled()
                if (!names.add(it.name)) throw ZipFormatException("Duplicate ZIP entry: ${it.name}")
                keep(it.name)
            }
            val totalWork = retained.sumOf { maxOf(1L, it.compressedSize) }
            var completedWork = 0L
            onProgress(0, totalWork)
            val updated = ArrayList<CentralDirectoryRecord>(retained.size)
            output.outputStream().buffered(64 * 1024).use { stream ->
                var position = 0L
                val buffer = ByteArray(64 * 1024)
                var entryStart = 0L
                var payloadOffset = 0L
                var payloadSize: Long? = null
                fun reportProgress() {
                    payloadSize?.let { size ->
                        onProgress(completedWork + (position - entryStart - payloadOffset).coerceIn(0L, size), totalWork)
                    }
                }
                val sink = object : DataSink {
                    override fun consume(bytes: ByteArray, offset: Int, length: Int) {
                        var copied = 0
                        while (copied < length) {
                            checkCancelled()
                            val count = minOf(buffer.size, length - copied)
                            stream.write(bytes, offset + copied, count)
                            position += count
                            copied += count
                            reportProgress()
                        }
                    }

                    override fun consume(bytes: ByteBuffer) {
                        while (bytes.hasRemaining()) {
                            checkCancelled()
                            val count = minOf(buffer.size, bytes.remaining())
                            bytes.get(buffer, 0, count)
                            stream.write(buffer, 0, count)
                            position += count
                            reportProgress()
                        }
                    }
                }
                for (record in retained) {
                    checkCancelled()
                    val local = LocalFileRecord.getRecord(source, record, sections.zipCentralDirectoryOffset)
                    // apksig validates sizes, names and descriptor flags but not the LFH method.
                    val localMethod = source.getByteBuffer(record.localFileHeaderOffset + 8, 2)
                        .order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                    if (localMethod != record.compressionMethod.toInt() ||
                        (localMethod != ZipEntry.STORED && localMethod != ZipEntry.DEFLATED) ||
                        record.gpFlags.toInt() and 1 != 0) {
                        throw ZipFormatException("Unsupported or inconsistent ZIP method: ${record.name}")
                    }
                    val offset = position
                    entryStart = position
                    payloadOffset = local.dataStartOffsetInRecord.toLong()
                    payloadSize = record.compressedSize
                    // Keep stored APK entries aligned without changing their payload or compression.
                    // Native libraries need 16 KB alignment for direct loading on newer devices.
                    val alignment = if (record.name.startsWith("lib/") && record.name.endsWith(".so")) 16384 else 4
                    if (record.compressionMethod.toInt() == ZipEntry.STORED &&
                        (position + local.dataStartOffsetInRecord) % alignment != 0L) {
                        val extra = local.extra.duplicate()
                        val padding = ((alignment - (position + local.dataStartOffsetInRecord + 4) % alignment) % alignment).toInt()
                        if (extra.remaining() + 4 + padding > 65535) {
                            throw ZipFormatException("Native library alignment exceeds ZIP extra field limit")
                        }
                        val alignedExtra = ByteBuffer.allocate(extra.remaining() + 4 + padding)
                            .order(ByteOrder.LITTLE_ENDIAN)
                        // APK aligners can leave 1-3 trailing padding bytes after the last
                        // extra field. Appending a field after those bytes turns them into a
                        // bogus header (and strict readers reject the entire archive).
                        // Put our complete field first, leaving the original extras intact.
                        alignedExtra.putShort(0xffff.toShort()).putShort(padding.toShort())
                        alignedExtra.position(4 + padding)
                        alignedExtra.put(extra)
                        alignedExtra.flip()
                        payloadOffset += 4 + padding
                        local.outputRecordWithModifiedExtra(source, alignedExtra, sink)
                    } else {
                        local.outputRecord(source, sink)
                    }
                    if (offset > 0xffffffffL) throw ZipFormatException("ZIP64 output requires streaming fallback")
                    updated.add(record.createWithModifiedLocalFileHeaderOffset(offset))
                    completedWork += maxOf(1L, record.compressedSize)
                    payloadSize = null
                    onProgress(completedWork, totalWork)
                }
                val directoryOffset = position
                for (record in updated) {
                    checkCancelled()
                    val bytes = ByteBuffer.allocate(record.size)
                    record.copyTo(bytes)
                    bytes.flip()
                    sink.consume(bytes)
                }
                val directorySize = position - directoryOffset
                if (directoryOffset > 0xffffffffL || directorySize > 0xffffffffL || updated.size >= 65535) {
                    throw ZipFormatException("ZIP64 output requires streaming fallback")
                }
                // Preserve the archive comment; only counts, size and offset change.
                val end = sections.zipEndOfCentralDirectory.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                end.putShort(8, updated.size.toShort())
                end.putShort(10, updated.size.toShort())
                end.putInt(12, directorySize.toInt())
                end.putInt(16, directoryOffset.toInt())
                sink.consume(end)
            }
        }
    }
}
