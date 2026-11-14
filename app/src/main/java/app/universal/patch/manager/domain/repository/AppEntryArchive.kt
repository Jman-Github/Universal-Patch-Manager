package app.universal.patch.manager.domain.repository

import android.app.Application
import android.net.Uri
import app.universal.patch.manager.data.platform.Filesystem
import app.universal.patch.manager.data.platform.SharedApkFiles
import app.universal.patch.manager.data.room.apps.installed.InstallType
import app.universal.patch.manager.data.room.apps.installed.InstalledApp
import app.universal.patch.manager.data.room.options.Option
import app.universal.patch.manager.data.room.profile.PatchProfilePayload
import app.universal.patch.manager.util.PM
import app.universal.patch.manager.util.buildSavedAppEntryKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal class AppEntryArchive(
    private val app: Application,
    private val fs: Filesystem,
    private val pm: PM,
    private val apps: InstalledAppRepository,
    private val bundles: PatchBundleRepository
) {
    @Serializable
    data class Entry(
        val key: String,
        val packageName: String,
        val originalPackageName: String,
        val version: String,
        val versionCode: Long?,
        val label: String,
        val missing: Boolean,
        val installType: String,
        val createdAt: Long,
        val useMount: Boolean?,
        val payload: PatchProfilePayload?,
        val repatchExtension: String? = null,
        val inputs: List<Input> = emptyList(),
        val customInstallerPackageName: String? = null
    )

    @Serializable
    data class Input(val extension: String, val files: List<String>? = null)

    @Serializable
    private data class Manifest(val format: String, val version: Int, val entries: List<Entry>)

    data class ExportEntry(val record: InstalledApp, val label: String, val missing: Boolean, val hasApk: Boolean)
    data class ImportPlan(val file: File, val entries: List<Entry>)
    data class ImportResult(val imported: Int, val updated: Int, val skipped: Int)

    enum class Stage { Preparing, Reading, Checking, Exporting, Saving, Importing }
    data class Progress(
        val stage: Stage,
        val completed: Int = 0,
        val total: Int = 0,
        val currentApp: String? = null
    )

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun exportEntries() = withContext(Dispatchers.IO) {
        apps.getAll().first().map { record ->
            val archive = retainedApk(record)
            val installed = record.takeUnless { it.installType == InstallType.SAVED }
                ?.let { pm.getPackageInfo(it.currentPackageName) }
            val current = installed != null &&
                apps.getCurrentInstalledRecord(
                    record.currentPackageName, installed.versionName, installed.lastUpdateTime,
                    installed.applicationInfo?.sourceDir?.let(::File)
                ) != null
            ExportEntry(
                record, archive?.let { runCatching { pm.getArchiveLabel(it) }.getOrNull() }
                    ?: record.originalPackageName,
                missing = record.installType != InstallType.SAVED && !current,
                hasApk = archive != null
            )
        }
    }

    suspend fun export(
        target: Uri,
        selected: List<ExportEntry>,
        onProgress: suspend (Progress) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        require(selected.isNotEmpty() && selected.size <= MAX_APPS)
        val sources = bundles.sources.first()
        val bundleInfo = bundles.allBundlesInfoFlow.first()
        // Complete the ZIP before replacing the user's destination.
        val archive = File.createTempFile("app-entries-", ".zip", app.cacheDir)
        try {
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                val entries = selected.mapIndexed { index, entry ->
                    onProgress(Progress(Stage.Exporting, index, selected.size, entry.label))
                    val record = requireNotNull(apps.get(entry.record.currentPackageName))
                    check(record == entry.record) { "App entry changed while preparing export" }
                    val apk = requireNotNull(retainedApk(record)) { "Patched APK is unavailable" }
                    val packageInfo = requireNotNull(pm.getPackageInfo(apk)) { "Cannot read patched APK" }
                    val retainedInput = record.repatchSourcePath?.let(::File)?.let(SharedApkFiles::resolve)
                        ?.takeIf(File::isFile)
                    val repatchExtension = retainedInput?.extension?.let(::safeExtension)
                    val savedPayload = record.selectionPayload
                        ?: apps.getAppliedPatches(record.currentPackageName).toPayload(sources, bundleInfo)
                    val payload = savedPayload?.copy(bundles = savedPayload.bundles.map { bundle ->
                        val details = bundleInfo[bundle.bundleUid]?.patches.orEmpty()
                            .filter { it.name in bundle.patches }
                            .associate { it.name to PatchProfilePayload.PatchDisplayInfo(it.displayName, it.description) }
                        bundle.copy(patchDisplayInfo = (details + bundle.patchDisplayInfo.orEmpty())
                            .takeIf { it.isNotEmpty() })
                    })
                    require(payload?.signatureWorkflow?.remembered != true || retainedInput != null) {
                        "The original signed input required by this app entry is unavailable"
                    }
                    val inputPaths = linkedMapOf<String, Int>()
                    val archivedPayload = payload?.rewriteOptions { value ->
                        val file = File(value)
                        if (file.isAbsolute && file.exists() && fs.isManagedPatchOptionInputPath(value)) {
                            INPUT_PREFIX + inputPaths.getOrPut(file.canonicalPath) { inputPaths.size }
                        } else value
                    }
                    zip.writeFile(apkPath(index), apk)
                    retainedInput?.let { zip.writeFile(repatchPath(index), it) }
                    val inputs = inputPaths.keys.mapIndexed { inputIndex, path ->
                        val file = File(path)
                        if (file.isDirectory) {
                            val files = file.walkTopDown()
                                .onEnter { !Files.isSymbolicLink(it.toPath()) }
                                .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
                                .take(MAX_INPUT_FILES + 1)
                                .toList()
                            require(files.size <= MAX_INPUT_FILES)
                            Input("", files.mapIndexed { fileIndex, child ->
                                zip.writeFile(inputPath(index, inputIndex, fileIndex), child)
                                child.relativeTo(file).invariantSeparatorsPath
                            })
                        } else {
                            zip.writeFile(inputPath(index, inputIndex, 0), file)
                            Input(safeExtension(file.extension))
                        }
                    }
                    Entry(
                        record.currentPackageName, packageInfo.packageName, record.originalPackageName,
                        record.version, record.versionCode ?: androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(packageInfo),
                        pm.getArchiveLabel(apk, packageInfo) ?: entry.label, entry.missing,
                        record.installType.name, record.createdAt, record.useMount,
                        archivedPayload, repatchExtension, inputs, record.customInstallerPackageName
                    ).also {
                        onProgress(Progress(Stage.Exporting, index + 1, selected.size, entry.label))
                    }
                }
                val manifest = json.encodeToString(Manifest(FORMAT, 1, entries)).toByteArray()
                require(manifest.size <= MAX_MANIFEST_BYTES)
                zip.putNextEntry(ZipEntry(MANIFEST_NAME))
                zip.write(manifest)
                zip.closeEntry()
            }
            onProgress(Progress(Stage.Saving))
            ZipFile(archive).use { readManifest(it) }
            val output = requireNotNull(app.contentResolver.openOutputStream(target, "wt"))
            output.use { archive.inputStream().use { input -> copy(input, it) } }
            selected.size
        } finally {
            archive.delete()
        }
    }

    suspend fun prepareImport(
        source: Uri,
        onProgress: suspend (Progress) -> Unit = {}
    ): ImportPlan {
        var staged: File? = null
        return try {
            withContext(Dispatchers.IO) {
                val file = File.createTempFile("app-entries-", ".zip", app.cacheDir)
                staged = file
                requireNotNull(app.contentResolver.openInputStream(source)).use { input ->
                    file.outputStream().use { copy(input, it) }
                }
                onProgress(Progress(Stage.Checking))
                ZipFile(file).use { zip -> ImportPlan(file, readManifest(zip).entries) }
            }
        } catch (failure: Throwable) {
            // withContext can be cancelled after IO finishes but before the UI receives the plan.
            // Keep cleanup outside that boundary so the staged APK archive is still released.
            withContext(NonCancellable + Dispatchers.IO) { staged?.delete() }
            throw failure
        }
    }

    suspend fun restore(
        plan: ImportPlan,
        selected: Set<Int>,
        onProgress: suspend (Progress) -> Unit = {}
    ): ImportResult = withContext(Dispatchers.IO) {
        val sources = bundles.sources.first()
        val signatures = bundles.allBundlesInfoFlow.first().toSignatureMap()
        var imported = 0
        var updated = 0
        var skipped = 0
        ZipFile(plan.file).use { zip ->
            val entries = readManifest(zip).entries
            require(entries == plan.entries && selected.all { it in entries.indices })
            selected.sorted().forEachIndexed { processed, index ->
                currentCoroutineContext().ensureActive()
                val entry = entries[index]
                onProgress(Progress(Stage.Importing, processed, selected.size, entry.label))
                val apk = File.createTempFile("archived-app-", ".apk", app.cacheDir)
                var repatch: File? = null
                val inputs = mutableListOf<File>()
                var committed = false
                try {
                    zip.extract(apkPath(index), apk)
                    require(SharedApkFiles.resolve(apk) == apk)
                    require(pm.getPackageInfo(apk)?.packageName == entry.packageName)
                    if (entry.repatchExtension != null) {
                        val input = File.createTempFile("archived-repatch-", "." + safeExtension(entry.repatchExtension), app.cacheDir)
                        repatch = input
                        zip.extract(repatchPath(index), input)
                        require(SharedApkFiles.resolve(input) == input &&
                            pm.getPackageInfo(input)?.packageName in setOf(entry.originalPackageName, entry.packageName))
                    }
                    require(entry.payload?.signatureWorkflow?.remembered != true || repatch != null)
                    entry.inputs.forEachIndexed { inputIndex, input ->
                        val staging = if (input.files == null) fs.createPatchOptionInputFile(safeExtension(input.extension))
                            else fs.createPatchOptionInputDirectory()
                        inputs.add(staging)
                        if (input.files == null) {
                            zip.extract(inputPath(index, inputIndex, 0), staging)
                        } else {
                            input.files.forEachIndexed { fileIndex, relative ->
                                val target = staging.resolve(relative).canonicalFile
                                require(target.path.startsWith(staging.canonicalPath + File.separator))
                                check(target.parentFile?.let { it.mkdirs() || it.isDirectory } == true)
                                zip.extract(inputPath(index, inputIndex, fileIndex), target)
                            }
                        }
                        inputs[inputIndex] = fs.completePatchOptionInput(staging)
                    }
                    // Foreign database IDs cannot identify bundles on this device.
                    val used = sources.mapTo(mutableSetOf()) { it.uid }
                    var nextUid = Int.MIN_VALUE
                    val portable = entry.payload?.rewriteOptions { value ->
                        if (value.startsWith(INPUT_PREFIX)) {
                            inputs[value.removePrefix(INPUT_PREFIX).toInt()].absolutePath
                        } else value
                    }?.let { payload ->
                        payload.copy(bundles = payload.bundles.map { bundle ->
                            while (nextUid in used) nextUid++
                            bundle.copy(bundleUid = nextUid++)
                        })
                    }
                    val (payload, selection) = portable?.remapAndExtractSelection(sources, signatures)
                        ?: (null to emptyMap())
                    val record = InstalledApp(
                        entry.restoreKey(), entry.originalPackageName, entry.version, InstallType.SAVED, 0,
                        selectionPayload = payload, createdAt = entry.createdAt, useMount = entry.useMount,
                        versionCode = entry.versionCode
                    )
                    val wasUpdated = apps.restoreArchivedApp(record, apk, repatch, selection.filterKeys { it in used })
                    committed = true
                    if (wasUpdated) updated++ else imported++
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    android.util.Log.w("AppEntryArchive", "Could not restore app entry", error)
                    skipped++
                } finally {
                    apk.delete()
                    repatch?.delete()
                    // A completed input stays leased until the row referencing it has committed.
                    if (committed) fs.releasePendingPatchOptionInputs(inputs.map { it.absolutePath })
                    else inputs.forEach { fs.deletePatchOptionInput(it.absolutePath) }
                }
                onProgress(Progress(Stage.Importing, processed + 1, selected.size, entry.label))
            }
        }
        ImportResult(imported, updated, skipped)
    }

    private fun retainedApk(app: InstalledApp): File? =
        SharedApkFiles.resolve(fs.getPatchedAppFile(app.currentPackageName, app.version)).takeIf(File::isFile)

    private fun Entry.restoreKey(): String =
        if (installType == InstallType.SAVED.name &&
            key.matches(Regex(Regex.escape(packageName) + "__bundle_[0-9a-f]{12}"))
        ) key else buildSavedAppEntryKey(packageName, "archive:$key:$version")

    private suspend fun readManifest(zip: ZipFile): Manifest {
        val entry = requireNotNull(zip.getEntry(MANIFEST_NAME))
        require(entry.size in 1..MAX_MANIFEST_BYTES.toLong())
        val buffer = java.io.ByteArrayOutputStream(entry.size.toInt())
        zip.copyVerified(entry, buffer)
        val manifest = json.decodeFromString<Manifest>(buffer.toString(Charsets.UTF_8.name()))
        require(manifest.format == FORMAT && manifest.version == 1)
        require(manifest.entries.size in 1..MAX_APPS)
        require(manifest.entries.map { it.key to it.version }.toSet().size == manifest.entries.size)
        val expected = mutableSetOf(MANIFEST_NAME)
        manifest.entries.forEachIndexed { i, item ->
            require(item.packageName.matches(PACKAGE) && item.originalPackageName.matches(PACKAGE))
            require(item.key.length in 1..300 && item.version.length <= 128)
            expected.add(apkPath(i))
            if (item.repatchExtension != null) expected.add(repatchPath(i))
            require(item.inputs.size <= MAX_INPUT_FILES)
            item.inputs.forEachIndexed { j, input ->
                val paths = input.files
                if (paths == null) expected.add(inputPath(i, j, 0))
                else {
                    require(paths.size <= MAX_INPUT_FILES && paths.toSet().size == paths.size)
                    paths.forEachIndexed { k, path ->
                        require(path.length <= 1024 && path.split('/').all {
                            it.isNotBlank() && it != "." && it != ".." && !it.contains('\\') && !it.contains(':')
                        })
                        expected.add(inputPath(i, j, k))
                    }
                }
            }
            require(expected.size <= MAX_ARCHIVE_FILES)
        }
        require(manifest.entries.map { it.restoreKey() }.toSet().size == manifest.entries.size)
        require(zip.size() == expected.size)
        var totalSize = 0L
        zip.entries().asSequence().forEach { file ->
            require(expected.remove(file.name) && !file.isDirectory && file.size in 0..MAX_FILE_BYTES)
            totalSize += file.size
            require(totalSize <= MAX_ARCHIVE_BYTES)
        }
        require(expected.isEmpty())
        return manifest
    }

    private suspend fun ZipFile.extract(name: String, target: File) {
        val entry = requireNotNull(getEntry(name))
        target.outputStream().buffered().use { copyVerified(entry, it) }
    }

    private suspend fun ZipFile.copyVerified(entry: ZipEntry, output: OutputStream) {
        require(!entry.isDirectory && entry.size in 0..MAX_FILE_BYTES && entry.crc >= 0)
        val crc = CRC32()
        getInputStream(entry).use { copy(it, output, entry.size, crc) }
        require(crc.value == entry.crc)
    }

    private suspend fun ZipOutputStream.writeFile(name: String, file: File) {
        require(file.length() <= MAX_FILE_BYTES)
        putNextEntry(ZipEntry(name))
        file.inputStream().buffered().use { copy(it, this, file.length()) }
        closeEntry()
    }

    private suspend fun copy(input: InputStream, output: OutputStream, size: Long? = null, crc: CRC32? = null) {
        val bytes = ByteArray(128 * 1024)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(bytes)
            if (count < 0) break
            total += count
            require(total <= (size ?: MAX_ARCHIVE_BYTES))
            crc?.update(bytes, 0, count)
            output.write(bytes, 0, count)
        }
        require(size == null || size == total)
    }

    private fun PatchProfilePayload.rewriteOptions(transform: (String) -> String): PatchProfilePayload {
        fun rewrite(value: JsonElement): JsonElement = when {
            value is JsonPrimitive && value.isString -> JsonPrimitive(transform(value.content))
            value is JsonArray -> JsonArray(value.map(::rewrite))
            else -> value
        }
        return copy(bundles = bundles.map { bundle ->
            bundle.copy(options = bundle.options.mapValues { (_, options) ->
                options.mapValues { (_, value) -> Option.SerializedValue(rewrite(value.raw)) }
            })
        })
    }

    private fun safeExtension(extension: String) =
        extension.takeIf { it.matches(Regex("[a-zA-Z0-9]{1,10}")) } ?: "apk"

    private companion object {
        const val FORMAT = "upm-app-entries"
        const val MANIFEST_NAME = "manifest.json"
        const val INPUT_PREFIX = "upm-archive-input:"
        const val MAX_APPS = 2_000
        const val MAX_MANIFEST_BYTES = 16 * 1024 * 1024
        const val MAX_INPUT_FILES = 4_096
        const val MAX_ARCHIVE_FILES = 20_000
        const val MAX_FILE_BYTES = 8L * 1024 * 1024 * 1024
        const val MAX_ARCHIVE_BYTES = 64L * 1024 * 1024 * 1024
        val PACKAGE = Regex("[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)*")
        fun apkPath(index: Int) = "apps/$index/patched.apk"
        fun repatchPath(index: Int) = "apps/$index/repatch"
        fun inputPath(index: Int, input: Int, file: Int) = "apps/$index/inputs/$input/$file"
    }
}
