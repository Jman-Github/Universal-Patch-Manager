package app.universal.patch.manager.data.platform

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Owners retain references; immutable content is collected only after every owner releases it. */
internal object SharedApkFiles {
    private const val MAGIC = "UPM_SHARED_APK_V1\n"
    private const val POOL = "app_shared-apks"

    fun resolve(file: File): File {
        if (!file.isFile || file.length() > 2048) return file
        val text = file.readText()
        if (!text.startsWith(MAGIC)) return file
        val blob = File(text.removePrefix(MAGIC)).canonicalFile
        val pool = requireNotNull(blob.parentFile)
        val dataRoot = requireNotNull(pool.parentFile)
        require(pool.name == POOL &&
            blob.name.matches(Regex("[a-f0-9]{64}\\.[a-z0-9]{1,10}")) &&
            file.canonicalPath.startsWith(dataRoot.canonicalPath + File.separator)) {
            "Invalid shared APK reference"
        }
        return blob
    }

    fun identical(a: File, b: File): Boolean {
        val first = resolve(a)
        val second = resolve(b)
        if (!first.isFile || !second.isFile || first.length() != second.length()) return false
        if (first.canonicalPath == second.canonicalPath) return true
        return first.inputStream().buffered().use { input ->
            second.inputStream().buffered().use secondUse@ { other ->
                val x = ByteArray(65536)
                val y = ByteArray(65536)
                while (true) {
                    val n = input.read(x)
                    if (n != other.read(y)) return@secondUse false
                    if (n < 0) return@secondUse true
                    for (i in 0 until n) if (x[i] != y[i]) return@secondUse false
                }
                @Suppress("UNREACHABLE_CODE")
                false
            }
        }
    }

    private fun writeReference(target: File, blob: File) {
        val staging = File.createTempFile(".apk-ref-", ".tmp", target.parentFile)
        try {
            java.io.FileOutputStream(staging).use { output ->
                output.write((MAGIC + blob.canonicalPath).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(staging.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { staging.delete() }
    }

    @Synchronized
    fun copy(source: File, target: File, roots: List<File>) {
        require(roots.isNotEmpty())
        val input = resolve(source)
        check(input.isFile) { "Shared APK source is unavailable" }
        val pool = File(roots.first().parentFile, POOL).apply { mkdirs() }
        if (input.parentFile?.canonicalPath == pool.canonicalPath) {
            writeReference(target, input)
            return
        }
        // Content inspection distinguishes standalone APKs from split archives.
        val extension = "apk"
        val hash = MessageDigest.getInstance("SHA-256")
        input.inputStream().buffered().use { stream ->
            val bytes = ByteArray(65536)
            while (true) {
                val n = stream.read(bytes)
                if (n < 0) break
                hash.update(bytes, 0, n)
            }
        }
        val key = hash.digest().joinToString("") { "%02x".format(it) }
        val blob = pool.resolve("$key.$extension")
        if (!blob.isFile || !identical(input, blob)) {
            val staging = File.createTempFile(".apk-", ".tmp", pool)
            try {
                java.io.FileOutputStream(staging).use { output ->
                    input.inputStream().use { it.copyTo(output) }
                    output.fd.sync()
                }
                check(identical(input, staging)) { "Shared APK verification failed" }
                val verifiedHash = MessageDigest.getInstance("SHA-256")
                staging.inputStream().buffered().use { stream ->
                    val bytes = ByteArray(65536)
                    while (true) {
                        val n = stream.read(bytes)
                        if (n < 0) break
                        verifiedHash.update(bytes, 0, n)
                    }
                }
                check(verifiedHash.digest().joinToString("") { "%02x".format(it) } == key) {
                    "APK source changed while it was being retained"
                }
                Files.move(staging.toPath(), blob.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { staging.delete() }
        }
        writeReference(target, blob)
        // Never replace a source that a running patch job may still have open.
        // Existing full copies are migrated by deduplicate() under the idle guard.
    }

    /** Prepare bytes off-lock; publish references and collect blobs only while the app stays idle. */
    @Synchronized
    fun deduplicate(
        owners: List<File>,
        downloads: File,
        commitIfIdle: (() -> Unit) -> Boolean = { block -> block(); true }
    ) {
        val roots = listOf(downloads) + owners
        roots.forEach { root ->
            root.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }.filter {
                it.isFile && it.name.startsWith(".apk-ref-") && it.name.endsWith(".tmp")
            }.forEach { it.delete() }
        }
        roots.forEach { root ->
            root.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }.filter {
                it.isFile && !Files.isSymbolicLink(it.toPath()) &&
                    it.extension.lowercase() in setOf("apk", "apks", "xapk", "apkm")
            }.forEach { file ->
                if (resolve(file).canonicalPath == file.canonicalPath) {
                    val size = file.length()
                    val modified = file.lastModified()
                    val staging = File.createTempFile(".apk-ref-", ".tmp", file.parentFile)
                    try {
                        // Large reads must not hold CacheCleanupGuard's lock: begin() can run
                        // on the UI thread. The original path remains usable during preparation.
                        copy(file, staging, roots)
                        if (!commitIfIdle {
                            // Do not recreate an owner removed by a concurrent pruning pass.
                            if (file.isFile && file.length() == size && file.lastModified() == modified) {
                                Files.move(staging.toPath(), file.toPath(),
                                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                            }
                        }) return
                    } finally { staging.delete() }
                }
            }
        }
        commitIfIdle {
            val referenced = roots.asSequence().flatMap {
                it.walkTopDown().onEnter { dir -> !Files.isSymbolicLink(dir.toPath()) }.asSequence()
            }.filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }.mapNotNull { file ->
                val blob = resolve(file)
                blob.canonicalPath.takeIf { it != file.canonicalPath }
            }.toSet()
            File(downloads.parentFile, POOL).listFiles().orEmpty().forEach {
                if (it.isFile && it.canonicalPath !in referenced) it.delete()
            }
        }
    }
}
