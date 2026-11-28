package app.universal.patch.manager.patcher.split

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** Discover the base from manifests without loading DEX or resource tables. */
internal object SplitManifestInspector {
    private const val MANIFEST = "AndroidManifest.xml"
    private const val MAX_MANIFEST_BYTES = 16 * 1024 * 1024

    fun directoryOrder(directory: File, checkCancelled: () -> Unit = {}): List<String> {
        val apks = directory.listFiles().orEmpty()
            .filter { it.isFile && it.extension.equals("apk", true) }
            .sortedBy { it.name }
        return baseFirst(apks.map { apk ->
            checkCancelled()
            apk.name to ZipFile(apk).use { zip ->
                val entry = requireNotNull(zip.getEntry(MANIFEST)) { "Missing manifest: ${apk.name}" }
                zip.getInputStream(entry).use { isSplit(it, checkCancelled) }
            }
        })
    }

    fun archiveOrder(source: File, entryNames: Set<String>, checkCancelled: () -> Unit = {}): List<String> =
        ZipFile(source).use { archiveOrder(it, entryNames, checkCancelled) }

    fun archiveOrder(archive: ZipFile, entryNames: Set<String>, checkCancelled: () -> Unit): List<String> =
        baseFirst(entryNames.map { name ->
            checkCancelled()
            val entry = requireNotNull(archive.getEntry(name)) { "Missing split APK: $name" }
            name.substringAfterLast('/') to archive.getInputStream(entry).use { input ->
                // Check cancellation even while nextEntry drains an earlier DEX/resource entry.
                val checked = object : FilterInputStream(input) {
                    override fun read(): Int { checkCancelled(); return super.read() }
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        checkCancelled()
                        return super.read(b, off, len)
                    }
                }
                val stream = object : ZipInputStream(checked) {
                    private var scannedBytes = 0L
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        checkCancelled()
                        val read = super.read(b, off, len)
                        if (read > 0) {
                            scannedBytes += read
                            require(scannedBytes <= 2L * 1024 * 1024 * 1024) {
                                "Split APK contents are too large."
                            }
                        }
                        return read
                    }
                }
                stream.use { apk ->
                    var manifest = apk.nextEntry
                    while (manifest != null && (manifest.isDirectory || manifest.name != MANIFEST)) {
                        checkCancelled()
                        manifest = apk.nextEntry
                    }
                    require(manifest != null) { "Missing manifest: $name" }
                    isSplit(apk, checkCancelled)
                }
            }
        })

    private fun isSplit(input: InputStream, checkCancelled: () -> Unit): Boolean {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            checkCancelled()
            val read = input.read(buffer)
            if (read < 0) break
            require(bytes.size() <= MAX_MANIFEST_BYTES - read) { "Split APK manifest is too large." }
            bytes.write(buffer, 0, read)
        }
        val manifest = AndroidManifestBlock.load(ByteArrayInputStream(bytes.toByteArray()))
        require(manifest.manifestElement != null && !manifest.packageName.isNullOrBlank()) {
            "Invalid split APK manifest."
        }
        return manifest.isSplit
    }

    private fun baseFirst(modules: List<Pair<String, Boolean>>): List<String> {
        require(modules.isNotEmpty()) { "Split archive contains no APKs." }
        require(modules.map { it.first.lowercase(Locale.ROOT) }.distinct().size == modules.size) {
            "Split archive contains colliding APK names."
        }
        val base = modules.singleOrNull { !it.second }
        require(base != null) { "Split archive must contain exactly one base APK." }
        return listOf(base.first) + modules.filter { it.second }.map { it.first }
    }
}
