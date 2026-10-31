package app.universal.patch.manager.data.platform

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.util.Base64
import android.util.Log
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import app.universal.patch.manager.util.FilenameUtils
import app.universal.patch.manager.util.RequestManageStorageContract
import app.universal.patch.manager.util.SAVED_APP_ENTRY_DELIMITER
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

internal data class RetainedOriginalReference(
    val packageName: String,
    val version: String,
    val versionCode: Long?
)

class Filesystem(private val app: Application) {
    data class StorageRoot(val path: Path, val label: String, val isRemovable: Boolean)

    val contentResolver = app.contentResolver // TODO: move Content Resolver operations to here.
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val patchOptionInputLock = Any()
    private val patchOptionInputLeasePreferences =
        app.getSharedPreferences("patch_option_input_leases", Context.MODE_PRIVATE)
    private val leasedPatchOptionInputs = patchOptionInputLeasePreferences
        .getStringSet(PATCH_OPTION_INPUT_LEASES_KEY, emptySet())
        .orEmpty()
        .toMutableSet()
    private val patchOptionInputLeaseTimestamps = leasedPatchOptionInputs.associateWith { path ->
        patchOptionInputLeasePreferences.getLong(
            patchOptionInputLeaseTimestampKey(path),
            System.currentTimeMillis()
        )
    }.toMutableMap()
    private val restoredPatchOptionInputs = leasedPatchOptionInputs.toMutableSet()
    private val refreshedPatchOptionInputsThisProcess = mutableSetOf<String>()

    /**
     * A directory that gets cleared when the app restarts.
     * Do not store paths to this directory in a parcel.
     */
    val tempDir: File = app.getDir("ephemeral", Context.MODE_PRIVATE).apply {
        deleteRecursively()
        mkdirs()
    }

    /**
     * A directory for storing temporary files related to UI.
     * This is the same as [tempDir], but does not get cleared on system-initiated process death.
     * Paths to this directory can be safely stored in parcels.
     */
    val uiTempDir: File = app.getDir("ui_ephemeral", Context.MODE_PRIVATE)
    private val batchPatchOutputsDir: File =
        app.getDir("batch-patch-outputs", Context.MODE_PRIVATE).apply { mkdirs() }
    private val patchWorkspaceCleanup = PatchWorkspaceCleanup(tempDir, batchPatchOutputsDir)

    fun pruneTemporaryPatchFiles(): Int = patchWorkspaceCleanup.pruneScratchFiles()

    fun detachTemporaryPatchFiles(): List<File> = patchWorkspaceCleanup.detachScratchFiles()

    fun pruneRestoredBatchPatchOutputFiles(retainedPaths: Collection<String>): Int =
        patchWorkspaceCleanup.pruneRestoredBatchOutputs(retainedPaths)

    private val patchedAppsDir: File = app.getDir("patched-apps", Context.MODE_PRIVATE).apply { mkdirs() }

    // Code adapted from Morphe, see third-party/NOTICE for more information
    // https://github.com/MorpheApp/morphe-manager/pull/795
    private val originalAppsDir: File = app.getDir("original-apps", Context.MODE_PRIVATE).apply { mkdirs() }

    /**
     * Durable local copies selected through Android document providers.
     * Patch options persist across app restarts, so their backing files must persist as well.
     * Pending-path leases are persisted separately so eager cleanup cannot invalidate restored
     * navigation state before its ViewModels reclaim ownership.
     */
    private val patchOptionInputsDir =
        app.getDir("patch-option-inputs", Context.MODE_PRIVATE).apply { mkdirs() }
    private val patchOptionInputStagingDir =
        patchOptionInputsDir.resolve("$PATCH_OPTION_INPUT_STAGING_DIR_PREFIX${UUID.randomUUID()}")
    private val patchProfileInputsDir: File = app.getDir("patch-profile-inputs", Context.MODE_PRIVATE).apply { mkdirs() }
    private val repatchInputsDir: File = app.getDir("repatch-inputs", Context.MODE_PRIVATE).apply { mkdirs() }
    private val repatchInputStagingDir: File =
        app.getDir("repatch-input-staging", Context.MODE_PRIVATE).apply { mkdirs() }
    private val repatchInputStagingCleanup = RepatchInputStagingCleanup(repatchInputStagingDir)

    init {
        deleteLeakedBundleDex()
        val staleStagingInputs = patchOptionInputsDir.listFiles()
            .orEmpty()
            .filter { it.isPatchOptionInputStagingEntry() }
        check(patchOptionInputStagingDir.mkdirs() || patchOptionInputStagingDir.isDirectory) {
            "Could not create patch option input staging directory"
        }
        staleStagingInputs.forEach { staleInput ->
            cleanupScope.launch {
                runCatching { staleInput.deleteRecursively() }
            }
        }
        val nowMillis = System.currentTimeMillis()
        val existingInputs = patchOptionInputsDir.listFiles()
            .orEmpty()
            .filterNot { it.isPatchOptionInputStagingEntry() }
            .mapTo(mutableSetOf()) { it.safeCanonicalPath() }
        synchronized(patchOptionInputLock) {
            val leasesWithoutTimestamp = leasedPatchOptionInputs.filterTo(mutableSetOf()) { path ->
                !patchOptionInputLeasePreferences.contains(patchOptionInputLeaseTimestampKey(path))
            }
            leasesWithoutTimestamp.forEach { path ->
                patchOptionInputLeaseTimestamps[path] = nowMillis
            }
            val expiredLeases = leasedPatchOptionInputs.filterTo(mutableSetOf()) { path ->
                isPatchOptionInputLeaseExpired(
                    leasedAtMillis = patchOptionInputLeaseTimestamps.getValue(path),
                    nowMillis = nowMillis,
                    maxAgeMillis = PATCH_OPTION_INPUT_RESTORED_LEASE_MAX_AGE_MILLIS
                )
            }
            val changed = leasedPatchOptionInputs.retainAll(existingInputs - expiredLeases)
            patchOptionInputLeaseTimestamps.keys.retainAll(leasedPatchOptionInputs)
            restoredPatchOptionInputs.retainAll(leasedPatchOptionInputs)
            if (changed || leasesWithoutTimestamp.isNotEmpty()) {
                persistPatchOptionInputLeases()
            }
        }
    }

    // Code adapted from Morphe, see third-party/NOTICE for more information
    // https://github.com/MorpheApp/morphe-manager/commit/6b92f02ca70c34dc14c0acea20af5e1faec59c24
    // Runs before patch bundles can load; only old patcher extraction directories are removed.
    private fun deleteLeakedBundleDex() {
        val leaked = app.cacheDir.listFiles { file ->
            file.isDirectory && file.name.startsWith("morphe-extracted-patches")
        }.orEmpty()
        val deleted = leaked.count { runCatching { it.deleteRecursively() }.getOrDefault(false) }
        if (deleted > 0) Log.i("Filesystem", "Deleted $deleted leaked bundle DEX directories")
    }

    val patchBundlesDir: File = app.getDir("patch_bundles", Context.MODE_PRIVATE)

    fun externalFilesDir(): Path = Environment.getExternalStorageDirectory().toPath()

    fun storageRoots(): List<StorageRoot> {
        val roots = LinkedHashMap<String, StorageRoot>()
        val storageManager = app.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            storageManager.storageVolumes.forEach { volume ->
                val directory = volume.directory ?: return@forEach
                addStorageRoot(
                    roots = roots,
                    directory = directory,
                    labelCandidate = volume.getDescription(app),
                    isRemovable = volume.isRemovable
                )
            }
        } else {
            app.getExternalFilesDirs(null).forEach { appSpecific ->
                val root = appSpecific?.let(::resolveLegacyStorageRoot) ?: return@forEach
                val volume = runCatching { storageManager.getStorageVolume(root) }.getOrNull()
                addStorageRoot(
                    roots = roots,
                    directory = root,
                    labelCandidate = volume?.getDescription(app),
                    isRemovable = volume?.isRemovable
                        ?: !Environment.isExternalStorageEmulated(root)
                )
            }
        }

        val primaryDir = Environment.getExternalStorageDirectory()
        val primaryVolume = runCatching { storageManager.getStorageVolume(primaryDir) }.getOrNull()
        addStorageRoot(
            roots = roots,
            directory = primaryDir,
            labelCandidate = primaryVolume?.getDescription(app),
            isRemovable = primaryVolume?.isRemovable ?: false
        )

        return roots.values.toList()
    }

    private fun addStorageRoot(
        roots: MutableMap<String, StorageRoot>,
        directory: File,
        labelCandidate: String?,
        isRemovable: Boolean
    ) {
        val canonical = runCatching { directory.canonicalFile }.getOrElse { directory.absoluteFile }
        if (!canonical.exists() || !canonical.isDirectory) return

        val path = canonical.toPath()
        val label = labelCandidate?.takeIf { it.isNotBlank() } ?: path.toString()
        roots.putIfAbsent(
            path.toString(),
            StorageRoot(path = path, label = label, isRemovable = isRemovable)
        )
    }

    private fun resolveLegacyStorageRoot(appSpecificDir: File): File? {
        val canonical = runCatching { appSpecificDir.canonicalFile }.getOrElse { appSpecificDir.absoluteFile }

        // Expected app-specific path form: <root>/Android/data/<package>/files
        val filesDir = canonical.name == "files"
        val packageDir = canonical.parentFile?.name == app.packageName
        val dataDir = canonical.parentFile?.parentFile?.name == "data"
        val androidDir = canonical.parentFile?.parentFile?.parentFile?.name == "Android"
        if (filesDir && packageDir && dataDir && androidDir) {
            return canonical.parentFile?.parentFile?.parentFile?.parentFile
        }

        // Fallback for vendor-modified paths.
        val marker = "${File.separator}Android${File.separator}data${File.separator}"
        val absolutePath = canonical.absolutePath
        val markerIndex = absolutePath.indexOf(marker)
        if (markerIndex > 0) {
            return File(absolutePath.substring(0, markerIndex))
        }

        return null
    }

    private fun usesManagePermission() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    private val storagePermissionName =
        if (usesManagePermission()) Manifest.permission.MANAGE_EXTERNAL_STORAGE else Manifest.permission.WRITE_EXTERNAL_STORAGE

    fun permissionContract(): Pair<ActivityResultContract<String, Boolean>, String> {
        val contract =
            if (usesManagePermission()) RequestManageStorageContract() else ActivityResultContracts.RequestPermission()
        return contract to storagePermissionName
    }

    fun hasStoragePermission() =
        if (usesManagePermission()) Environment.isExternalStorageManager() else app.checkSelfPermission(
            storagePermissionName
        ) == PackageManager.PERMISSION_GRANTED

    fun getPatchedAppFile(packageName: String, version: String): File {
        val safePackage = FilenameUtils.sanitize(packageName)
        val safeVersion = FilenameUtils.sanitize(version.ifBlank { "unspecified" })
        return patchedAppsDir.resolve("${safePackage}_${safeVersion}.apk")
    }

    fun createBatchPatchOutputFile(packageName: String): File {
        check(batchPatchOutputsDir.mkdirs() || batchPatchOutputsDir.isDirectory) {
            "Unable to create the batch patch staging directory"
        }
        val safePackage = FilenameUtils.sanitize(packageName)
            .ifBlank { "app" }
            .take(80)
        return batchPatchOutputsDir.resolve("batch_${safePackage}_${UUID.randomUUID()}.apk")
    }

    fun pruneBatchPatchOutputFiles(
        retainedPaths: Collection<String>,
        olderThanTimestampMillis: Long? = null
    ): Int {
        val retainedCanonicalPaths = retainedPaths
            .asSequence()
            .filter(String::isNotBlank)
            .map(::File)
            .mapTo(mutableSetOf()) { it.safeCanonicalPath() }
        return batchPatchOutputsDir.listFiles { file ->
            file.isFile &&
                file.name.startsWith("batch_") &&
                file.name.endsWith(".apk", ignoreCase = true)
        }.orEmpty().count { file ->
            val oldEnough = olderThanTimestampMillis == null ||
                file.lastModified() < olderThanTimestampMillis
            oldEnough &&
                file.safeCanonicalPath() !in retainedCanonicalPaths &&
                file.delete()
        }
    }

    // Code adapted from Morphe, see third-party/NOTICE for more information
    // https://github.com/MorpheApp/morphe-manager/pull/795
    fun saveOriginalAppFile(
        packageName: String,
        version: String,
        versionCode: Long?,
        source: File
    ): File {
        val extension = source.extension.takeIf(String::isNotBlank) ?: "apk"
        val packageDir = originalAppPackageDir(packageName)
        check(packageDir.mkdirs() || packageDir.isDirectory) {
            "Unable to create the retained original app directory"
        }
        val target = packageDir.resolve(
            "${retainedOriginalFileStem(version, versionCode)}.$extension"
        )
        if (source.safeCanonicalPath() == target.safeCanonicalPath()) return target

        val staging = packageDir.resolve(".${target.name}.${UUID.randomUUID()}.tmp")
        val backup = packageDir.resolve(".${target.name}.${UUID.randomUUID()}.bak")
        var replacementStarted = false
        var keepBackup = false
        try {
            SharedApkFiles.copy(source, staging, sharedApkRoots())
            check(staging.isFile && SharedApkFiles.identical(staging, source)) {
                "Failed to verify the retained original app staging copy"
            }
            if (target.isFile) {
                target.copyTo(backup, overwrite = true)
                check(backup.isFile && backup.length() == target.length()) {
                    "Failed to verify the retained original app backup"
                }
            }

            replacementStarted = true
            if (!staging.renameTo(target)) {
                check(!target.exists() || target.delete())
                staging.copyTo(target, overwrite = false)
            }
            check(target.isFile && SharedApkFiles.identical(target, source)) {
                "Failed to verify the retained original app"
            }
        } catch (error: Throwable) {
            if (replacementStarted) {
                val restoreError = runCatching {
                    if (backup.isFile) {
                        check(!target.exists() || target.delete())
                        backup.copyTo(target, overwrite = false)
                        check(target.isFile && SharedApkFiles.identical(target, backup)) {
                            "Failed to verify the restored retained original app"
                        }
                    } else {
                        check(target.delete() || !target.exists()) {
                            "Failed to remove the incomplete retained original app"
                        }
                    }
                }.exceptionOrNull()
                if (restoreError != null) {
                    keepBackup = backup.isFile
                    error.addSuppressed(restoreError)
                }
            }
            throw error
        } finally {
            staging.delete()
            if (!keepBackup) backup.delete()
        }
        return target
    }

    fun findOriginalAppFiles(
        packageName: String,
        version: String? = null,
        versionCode: Long? = null
    ): List<File> {
        val candidates = originalAppPackageDir(packageName).listFiles { file ->
            file.isFile &&
                (version == null || retainedOriginalFileMatches(file.name, version, versionCode))
        }.orEmpty()
        if (candidates.isEmpty()) return emptyList()

        val exactStem = if (version != null && versionCode != null) {
            retainedOriginalFileStem(version, versionCode)
        } else {
            null
        }
        return candidates.sortedWith(
            compareByDescending<File> { candidate ->
                if (exactStem != null && candidate.name.startsWith("$exactStem.")) 1 else 0
            }.thenByDescending { candidate -> candidate.lastModified() }
        ).map(SharedApkFiles::resolve)
    }

    fun findOriginalAppFile(
        packageName: String,
        version: String? = null,
        versionCode: Long? = null
    ): File? = findOriginalAppFiles(packageName, version, versionCode).firstOrNull()

    fun isManagedPatchedAppFile(
        file: File,
        packageName: String,
        version: String
    ): Boolean = filesMatch(
        file,
        getPatchedAppFile(packageName, version)
    )

    fun isManagedPatchedAppFile(file: File): Boolean {
        if (!file.isFile) return false
        val rootPath = patchedAppsDir.safeCanonicalPath()
        val filePath = file.safeCanonicalPath()
        if (filePath.startsWith("$rootPath${File.separator}")) return true

        val candidates = patchedAppsDir.listFiles { candidate ->
            candidate.isFile && candidate.length() == file.length()
        }.orEmpty()
        return candidates.any { candidate -> filesMatch(file, candidate) }
    }

    private fun filesMatch(first: File, second: File): Boolean =
        SharedApkFiles.identical(first, second)

    private fun filesHaveSameContent(first: File, second: File): Boolean {
        var identical = false
        try {
            first.inputStream().buffered().use { firstInput ->
                second.inputStream().buffered().use { secondInput ->
                    val firstBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    val secondBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val firstRead = firstInput.read(firstBuffer)
                        val secondRead = secondInput.read(secondBuffer)
                        if (firstRead != secondRead) break
                        if (firstRead < 0) {
                            identical = true
                            break
                        }
                        var chunkMatches = true
                        for (index in 0 until firstRead) {
                            if (firstBuffer[index] != secondBuffer[index]) {
                                chunkMatches = false
                                break
                            }
                        }
                        if (!chunkMatches) break
                    }
                }
            }
        } catch (_: Exception) {
            return false
        }
        return identical
    }

    fun deleteOriginalAppFiles(packageName: String): Int =
        deleteDirectoryAndCountFiles(originalAppPackageDir(packageName))

    internal fun pruneOriginalAppFiles(retainedReferences: Collection<RetainedOriginalReference>): Int {
        val referencesByDirectory = retainedReferences.groupBy { reference ->
            originalAppPackageDir(reference.packageName).name
        }
        return originalAppsDir.listFiles().orEmpty().sumOf { entry ->
            when {
                entry.isDirectory -> {
                    val references = referencesByDirectory[entry.name].orEmpty()
                    if (references.isEmpty()) {
                        deleteDirectoryAndCountFiles(entry)
                    } else {
                        val removed = entry.listFiles().orEmpty().count { file ->
                            file.isFile &&
                                references.none { reference ->
                                    retainedOriginalFileMatches(
                                        fileName = file.name,
                                        version = reference.version,
                                        versionCode = reference.versionCode
                                    )
                                } &&
                                file.delete()
                        }
                        if (entry.listFiles().isNullOrEmpty()) entry.delete()
                        removed
                    }
                }
                // The old flat layout cannot be mapped safely to a package. Keep it while any
                // retained original is still referenced and remove it once nothing is retained.
                entry.isFile && retainedReferences.isEmpty() && entry.delete() -> 1
                else -> 0
            }
        }
    }

    private fun originalAppPackageDir(packageName: String): File =
        originalAppsDir.resolve(FilenameUtils.sanitize(packageName).ifBlank { "package" })

    private fun deleteDirectoryAndCountFiles(directory: File): Int {
        if (!directory.exists()) return 0
        val fileCount = directory.walkTopDown().count(File::isFile)
        return if (directory.deleteRecursively()) fileCount else 0
    }

    fun findPatchedAppFile(packageName: String): File? {
        val safePackage = FilenameUtils.sanitize(packageName)
        return patchedAppsDir
            .listFiles { file -> patchedAppFileMatchesPackage(file, safePackage) }
            ?.maxByOrNull { it.lastModified() }
    }

    fun deletePatchedAppFiles(packageName: String): Int {
        val safePackage = FilenameUtils.sanitize(packageName)
        val matches = patchedAppsDir.listFiles { file ->
            patchedAppFileMatchesPackage(file, safePackage)
        } ?: return 0

        var removed = 0
        matches.forEach { file ->
            if (file.delete()) {
                removed++
            }
        }
        return removed
    }

    private fun patchedAppFileMatchesPackage(file: File, safePackage: String): Boolean =
        file.isFile && patchedAppFileNameMatchesPackage(file.name, safePackage)

    fun prunePatchedAppFiles(retainedFiles: Collection<File>): Int {
        val retainedPaths = retainedFiles.mapTo(mutableSetOf()) { it.safeCanonicalPath() }
        val staleFiles = patchedAppsDir.listFiles { file ->
            file.isFile && file.name.endsWith(".apk", ignoreCase = true)
        }?.filterNot { file ->
            file.safeCanonicalPath() in retainedPaths
        } ?: return 0

        var removed = 0
        staleFiles.forEach { file ->
            if (file.delete()) {
                removed++
            }
        }
        return removed
    }

    private fun File.safeCanonicalPath(): String =
        runCatching { canonicalFile.absolutePath }.getOrElse { absoluteFile.absolutePath }

    private fun File.isWithinDirectory(directory: File): Boolean {
        val rootPath = directory.safeCanonicalPath()
        val filePath = safeCanonicalPath()
        return filePath != rootPath && filePath.startsWith("$rootPath${File.separator}")
    }

    fun getPatchProfileInputFile(profileId: Int, extension: String): File {
        val sanitized = extension.lowercase(Locale.ROOT).takeIf { it.matches(Regex("^[a-z0-9]{1,10}$")) }
            ?: "apk"
        return patchProfileInputsDir.resolve("profile_${profileId}.$sanitized")
    }

    private fun sharedApkRoots() = listOf(
        app.getDir("downloaded-apps", Context.MODE_PRIVATE), originalAppsDir, repatchInputsDir,
        repatchInputStagingDir
    )

    fun deduplicateRetainedApks(commitIfIdle: (() -> Unit) -> Boolean) = SharedApkFiles.deduplicate(
        listOf(originalAppsDir, repatchInputsDir, repatchInputStagingDir),
        app.getDir("downloaded-apps", Context.MODE_PRIVATE),
        commitIfIdle
    )

    fun stageRepatchInputFile(source: File): File {
        check(source.isFile) { "Repatch input source is unavailable" }
        val extension = source.extension.lowercase(Locale.ROOT)
            .takeIf { it.matches(Regex("^[a-z0-9]{1,10}$")) }
            ?: "apk"
        check(repatchInputStagingDir.mkdirs() || repatchInputStagingDir.isDirectory) {
            "Unable to create the Repatch input staging directory"
        }
        val target = repatchInputStagingDir.resolve("input_${UUID.randomUUID()}.$extension")
        return try {
            SharedApkFiles.copy(source, target, sharedApkRoots())
            check(target.isFile && SharedApkFiles.identical(target, source)) {
                "Failed to verify the retained Repatch input"
            }
            target
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    data class PersistedRepatchInput(val file: File, val created: Boolean)

    fun persistRepatchInputFile(
        entryKey: String,
        sourcePath: String,
        retainedPath: String? = null
    ): PersistedRepatchInput {
        val source = File(sourcePath)
        check(source.isFile) { "Repatch input source is unavailable" }
        val extension = source.extension.lowercase(Locale.ROOT)
            .takeIf { it.matches(Regex("^[a-z0-9]{1,10}$")) }
            ?: "apk"
        val entryDir = repatchInputEntryDir(entryKey)
        check(entryDir.mkdirs() || entryDir.isDirectory) {
            "Unable to create the retained Repatch input directory"
        }
        val retainedFile = retainedPath
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?.takeIf { it.isWithinDirectory(entryDir) }
        val sourceInEntryDir = source.takeIf { it.isWithinDirectory(entryDir) }
        val reusableFile = sequenceOf(sourceInEntryDir, retainedFile)
            .filterNotNull()
            .distinctBy { it.safeCanonicalPath() }
            .firstOrNull { candidate ->
                candidate.isFile &&
                    candidate.extension.equals(extension, ignoreCase = true) &&
                    filesMatch(source, candidate)
            }
        val protectedPaths = buildSet {
            retainedFile?.let { add(it.safeCanonicalPath()) }
            sourceInEntryDir?.let { add(it.safeCanonicalPath()) }
            reusableFile?.let { add(it.safeCanonicalPath()) }
        }
        entryDir.listFiles().orEmpty().forEach { candidate ->
            if (
                candidate.isFile &&
                candidate.safeCanonicalPath() !in protectedPaths
            ) {
                candidate.delete()
            }
        }
        reusableFile?.let { return PersistedRepatchInput(it, created = false) }
        val target = entryDir.resolve("input_${UUID.randomUUID()}.$extension")
        return try {
            SharedApkFiles.copy(source, target, sharedApkRoots())
            check(target.isFile && SharedApkFiles.identical(target, source)) {
                "Failed to verify the persisted Repatch input"
            }
            PersistedRepatchInput(target, created = true)
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    fun deleteRepatchInputStagingFile(path: String?): Boolean {
        val file = path?.takeIf(String::isNotBlank)?.let(::File) ?: return false
        val stagingRoot = repatchInputStagingDir.safeCanonicalPath()
        val filePath = file.safeCanonicalPath()
        if (!filePath.startsWith("$stagingRoot${File.separator}")) return false
        return !file.exists() || file.delete()
    }

    fun pruneRepatchInputStagingFiles(retainedPaths: Collection<String>): Int =
        repatchInputStagingCleanup.prune(retainedPaths)

    fun deleteRepatchInputFile(path: String?): Boolean {
        val file = path?.takeIf(String::isNotBlank)?.let(::File) ?: return false
        val root = repatchInputsDir.safeCanonicalPath()
        val filePath = file.safeCanonicalPath()
        if (!filePath.startsWith("$root${File.separator}")) return false
        return !file.exists() || file.delete()
    }

    fun pruneRepatchInputFiles(retainedPaths: Collection<String?>): Int {
        val retainedCanonicalPaths = retainedPaths.mapNotNullTo(mutableSetOf()) { path ->
            path?.takeIf(String::isNotBlank)
                ?.let(::File)
                ?.takeIf(File::isFile)
                ?.safeCanonicalPath()
        }
        var removed = 0
        repatchInputsDir.listFiles().orEmpty().forEach { entry ->
            if (entry.isDirectory) {
                entry.listFiles().orEmpty().forEach { candidate ->
                    if (
                        candidate.isFile &&
                        candidate.safeCanonicalPath() !in retainedCanonicalPaths &&
                        candidate.delete()
                    ) {
                        removed++
                    }
                }
                if (entry.listFiles().isNullOrEmpty()) {
                    entry.delete()
                }
            } else if (
                entry.isFile &&
                entry.safeCanonicalPath() !in retainedCanonicalPaths &&
                entry.delete()
            ) {
                removed++
            }
        }
        return removed
    }

    fun deleteRepatchInputsForEntry(entryKey: String): Int =
        deleteDirectoryAndCountFiles(repatchInputEntryDir(entryKey)) +
            deleteDirectoryAndCountFiles(legacyRepatchInputEntryDir(entryKey))

    private fun repatchInputEntryDir(entryKey: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(entryKey.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte ->
                (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
            }
        return repatchInputsDir.resolve(digest)
    }

    private fun legacyRepatchInputEntryDir(entryKey: String): File {
        val encoded = Base64.encodeToString(
            entryKey.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        ).ifBlank { "entry" }
        return repatchInputsDir.resolve(encoded)
    }

    fun createPatchOptionInputFile(extension: String): File {
        check(patchOptionInputsDir.mkdirs() || patchOptionInputsDir.isDirectory) {
            "Unable to create the patch option input directory"
        }
        val sanitized = normalizeManagedPatchOptionInputExtension(extension)
        check(patchOptionInputStagingDir.mkdirs() || patchOptionInputStagingDir.isDirectory) {
            "Unable to create the patch option input staging directory"
        }
        return patchOptionInputStagingDir
            .resolve("input_${UUID.randomUUID()}.$sanitized")
            .also(::leasePatchOptionInput)
    }

    fun createPatchOptionInputDirectory(): File {
        check(patchOptionInputsDir.mkdirs() || patchOptionInputsDir.isDirectory) {
            "Unable to create the patch option input directory"
        }
        check(patchOptionInputStagingDir.mkdirs() || patchOptionInputStagingDir.isDirectory) {
            "Unable to create the patch option input staging directory"
        }
        val directory = patchOptionInputStagingDir
            .resolve("folder_${UUID.randomUUID()}")
        leasePatchOptionInput(directory)
        return directory.apply {
            try {
                check(mkdirs()) { "Unable to create a managed patch option directory" }
            } catch (error: Throwable) {
                releasePatchOptionInput(this)
                throw error
            }
        }
    }

    fun completePatchOptionInput(input: File): File {
        val inputPath = input.safeCanonicalPath()
        check(isManagedPatchOptionInputPath(inputPath)) {
            "Patch option input is outside the managed directory"
        }
        check(input.parentFile?.safeCanonicalPath() == patchOptionInputStagingDir.safeCanonicalPath()) {
            "Patch option input is not an incomplete import"
        }

        val completed = patchOptionInputsDir.resolve(input.name)
        val completedPath = completed.safeCanonicalPath()
        synchronized(patchOptionInputLock) {
            check(inputPath in leasedPatchOptionInputs) {
                "Patch option input is not pending"
            }
            check(!completed.exists()) {
                "Completed patch option input already exists"
            }

            leasedPatchOptionInputs.add(completedPath)
            patchOptionInputLeaseTimestamps[completedPath] = System.currentTimeMillis()
            refreshedPatchOptionInputsThisProcess.add(completedPath)
            persistPatchOptionInputLeases()
            if (!input.renameTo(completed)) {
                leasedPatchOptionInputs.remove(completedPath)
                patchOptionInputLeaseTimestamps.remove(completedPath)
                refreshedPatchOptionInputsThisProcess.remove(completedPath)
                persistPatchOptionInputLeases()
                error("Unable to finalize patch option input")
            }
            leasedPatchOptionInputs.remove(inputPath)
            patchOptionInputLeaseTimestamps.remove(inputPath)
            restoredPatchOptionInputs.remove(inputPath)
            refreshedPatchOptionInputsThisProcess.remove(inputPath)
            persistPatchOptionInputLeases()
        }
        return completed
    }

    fun deletePatchOptionInput(path: String?): Boolean {
        val candidate = path?.takeIf(String::isNotBlank)?.let(::File) ?: return false
        val managedRoot = patchOptionInputsDir.safeCanonicalPath()
        val managedPath = candidate.safeCanonicalPath()
        val isManagedInput = managedPath != managedRoot &&
            managedPath.startsWith("$managedRoot${File.separator}")
        if (isManagedInput) {
            synchronized(patchOptionInputLock) {
                if (leasedPatchOptionInputs.remove(managedPath)) {
                    patchOptionInputLeaseTimestamps.remove(managedPath)
                    restoredPatchOptionInputs.remove(managedPath)
                    refreshedPatchOptionInputsThisProcess.remove(managedPath)
                    persistPatchOptionInputLeases()
                }
            }
        }
        return isManagedInput && runCatching { candidate.deleteRecursively() }.getOrDefault(false)
    }

    fun deletePatchOptionInputAsync(path: String?) {
        cleanupScope.launch {
            deletePatchOptionInput(path)
        }
    }

    fun prunePatchOptionInputs(retainedPaths: Collection<String>): Int {
        val retainedManagedPaths = retainedPaths
            .asSequence()
            .filter(String::isNotBlank)
            .map(::File)
            .map { it.safeCanonicalPath() }
            .filter(::isManagedPatchOptionInputPath)
            .toSet()

        val candidates = synchronized(patchOptionInputLock) {
            val protectedPaths = retainedManagedPaths + leasedPatchOptionInputs
            patchOptionInputsDir.listFiles()
                .orEmpty()
                .filterNot { it.isPatchOptionInputStagingEntry() }
                .filter { candidate ->
                    val candidatePath = candidate.safeCanonicalPath()
                    val isRetained = protectedPaths.any { retainedPath ->
                        retainedPath == candidatePath ||
                            retainedPath.startsWith("$candidatePath${File.separator}")
                    }
                    !isRetained
                }
        }

        return candidates.count { candidate ->
            synchronized(patchOptionInputLock) {
                val candidatePath = candidate.safeCanonicalPath()
                val protectedPaths = retainedManagedPaths + leasedPatchOptionInputs
                val isNowRetained = protectedPaths.any { retainedPath ->
                    retainedPath == candidatePath ||
                        retainedPath.startsWith("$candidatePath${File.separator}")
                }
                !isNowRetained &&
                    runCatching { candidate.deleteRecursively() }.getOrDefault(false)
            }
        }
    }

    fun patchOptionInputPaths(): Set<String> =
        patchOptionInputsDir.listFiles()
            .orEmpty()
            .filterNot { it.isPatchOptionInputStagingEntry() }
            .mapTo(mutableSetOf()) { it.safeCanonicalPath() }

    fun claimPatchOptionInput(path: String): Boolean =
        claimPatchOptionInputs(listOf(path)).isNotEmpty()

    fun claimPatchOptionInputs(paths: Collection<String>): Set<String> {
        if (paths.isEmpty()) return emptySet()
        val managedPaths = paths.mapTo(mutableSetOf()) { File(it).safeCanonicalPath() }
        return synchronized(patchOptionInputLock) {
            val claimedPaths = managedPaths.filterTo(mutableSetOf()) { managedPath ->
                isManagedPatchOptionInputPath(managedPath) && File(managedPath).exists()
            }
            val nowMillis = System.currentTimeMillis()
            val changed = claimedPaths.fold(false) { changed, managedPath ->
                val added = leasedPatchOptionInputs.add(managedPath)
                val firstClaimThisProcess = refreshedPatchOptionInputsThisProcess.add(managedPath)
                restoredPatchOptionInputs.remove(managedPath)
                if (added || firstClaimThisProcess) {
                    patchOptionInputLeaseTimestamps[managedPath] = nowMillis
                }
                changed || added || firstClaimThisProcess
            }
            if (changed) {
                persistPatchOptionInputLeases(commitSynchronously = false)
            }
            claimedPaths
        }
    }

    fun releasePendingPatchOptionInputs(paths: Collection<String>) {
        if (paths.isEmpty()) return
        val managedPaths = paths.mapTo(mutableSetOf()) { File(it).safeCanonicalPath() }
        synchronized(patchOptionInputLock) {
            if (leasedPatchOptionInputs.removeAll(managedPaths)) {
                patchOptionInputLeaseTimestamps.keys.removeAll(managedPaths)
                restoredPatchOptionInputs.removeAll(managedPaths)
                refreshedPatchOptionInputsThisProcess.removeAll(managedPaths)
                persistPatchOptionInputLeases()
            }
        }
    }

    fun millisUntilNextRestoredPatchOptionInputExpiry(
        nowMillis: Long = System.currentTimeMillis()
    ): Long? = synchronized(patchOptionInputLock) {
        restoredPatchOptionInputs.minOfOrNull { path ->
            val leasedAtMillis = patchOptionInputLeaseTimestamps.getValue(path)
            if (nowMillis < leasedAtMillis) {
                PATCH_OPTION_INPUT_RESTORED_LEASE_MAX_AGE_MILLIS
            } else {
                (PATCH_OPTION_INPUT_RESTORED_LEASE_MAX_AGE_MILLIS - (nowMillis - leasedAtMillis))
                    .coerceAtLeast(0L)
            }
        }
    }

    fun releaseExpiredRestoredPatchOptionInputs(
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean = synchronized(patchOptionInputLock) {
        val expiredPaths = restoredPatchOptionInputs.filterTo(mutableSetOf()) { path ->
            isPatchOptionInputLeaseExpired(
                leasedAtMillis = patchOptionInputLeaseTimestamps.getValue(path),
                nowMillis = nowMillis,
                maxAgeMillis = PATCH_OPTION_INPUT_RESTORED_LEASE_MAX_AGE_MILLIS
            )
        }
        if (expiredPaths.isEmpty()) {
            return@synchronized false
        }

        restoredPatchOptionInputs.removeAll(expiredPaths)
        refreshedPatchOptionInputsThisProcess.removeAll(expiredPaths)
        leasedPatchOptionInputs.removeAll(expiredPaths)
        patchOptionInputLeaseTimestamps.keys.removeAll(expiredPaths)
        persistPatchOptionInputLeases()
        true
    }

    private fun isManagedPatchOptionInputPath(path: String): Boolean {
        val managedRoot = patchOptionInputsDir.safeCanonicalPath()
        return path != managedRoot && path.startsWith("$managedRoot${File.separator}")
    }

    private fun leasePatchOptionInput(file: File) {
        synchronized(patchOptionInputLock) {
            val path = file.safeCanonicalPath()
            if (leasedPatchOptionInputs.add(path)) {
                patchOptionInputLeaseTimestamps[path] = System.currentTimeMillis()
                refreshedPatchOptionInputsThisProcess.add(path)
                persistPatchOptionInputLeases()
            }
        }
    }

    private fun releasePatchOptionInput(file: File) {
        synchronized(patchOptionInputLock) {
            val path = file.safeCanonicalPath()
            if (leasedPatchOptionInputs.remove(path)) {
                patchOptionInputLeaseTimestamps.remove(path)
                restoredPatchOptionInputs.remove(path)
                refreshedPatchOptionInputsThisProcess.remove(path)
                persistPatchOptionInputLeases()
            }
        }
    }

    private fun persistPatchOptionInputLeases(commitSynchronously: Boolean = true) {
        val nowMillis = System.currentTimeMillis()
        leasedPatchOptionInputs.forEach { path ->
            patchOptionInputLeaseTimestamps.putIfAbsent(path, nowMillis)
        }
        patchOptionInputLeaseTimestamps.keys.retainAll(leasedPatchOptionInputs)

        val timestampKeys = leasedPatchOptionInputs.associateWith(::patchOptionInputLeaseTimestampKey)
        val editor = patchOptionInputLeasePreferences.edit()
            .putStringSet(PATCH_OPTION_INPUT_LEASES_KEY, leasedPatchOptionInputs.toSet())
        patchOptionInputLeasePreferences.all.keys
            .filter { key ->
                key.startsWith(PATCH_OPTION_INPUT_LEASE_TIMESTAMP_PREFIX) &&
                    key !in timestampKeys.values
            }
            .forEach { key -> editor.remove(key) }
        timestampKeys.forEach { (path, key) ->
            editor.putLong(key, patchOptionInputLeaseTimestamps.getValue(path))
        }
        if (commitSynchronously) {
            editor.commit()
        } else {
            editor.apply()
        }
    }

    private fun patchOptionInputLeaseTimestampKey(path: String): String {
        val encodedPath = Base64.encodeToString(
            path.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return "$PATCH_OPTION_INPUT_LEASE_TIMESTAMP_PREFIX$encodedPath"
    }

    private fun File.isPatchOptionInputStagingEntry(): Boolean =
        name == LEGACY_PATCH_OPTION_INPUT_STAGING_DIR_NAME ||
            name.startsWith(PATCH_OPTION_INPUT_STAGING_DIR_PREFIX)

    private companion object {
        const val PATCH_OPTION_INPUT_LEASES_KEY = "paths"
        const val PATCH_OPTION_INPUT_LEASE_TIMESTAMP_PREFIX = "leased_at_"
        const val LEGACY_PATCH_OPTION_INPUT_STAGING_DIR_NAME = ".staging"
        const val PATCH_OPTION_INPUT_STAGING_DIR_PREFIX = ".staging_"
        const val PATCH_OPTION_INPUT_RESTORED_LEASE_MAX_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000
    }
}

internal fun patchedAppFileNameMatchesPackage(
    fileName: String,
    safePackage: String
): Boolean {
    if (!fileName.endsWith(".apk", ignoreCase = true)) return false
    if (!fileName.startsWith("${safePackage}_")) return false
    if (
        SAVED_APP_ENTRY_DELIMITER !in safePackage &&
        fileName.startsWith("$safePackage$SAVED_APP_ENTRY_DELIMITER")
    ) return false
    return true
}

internal fun retainedOriginalFileStem(version: String, versionCode: Long?): String {
    val safeVersion = FilenameUtils.sanitize(version.ifBlank { "unspecified" })
    return if (versionCode == null) {
        "${safeVersion}_original"
    } else {
        "${safeVersion}_${versionCode}_original"
    }
}

internal fun retainedOriginalFileMatches(
    fileName: String,
    version: String,
    versionCode: Long?
): Boolean {
    val legacyStem = retainedOriginalFileStem(version, null)
    if (fileName.startsWith("$legacyStem.")) return true
    if (versionCode != null) {
        return fileName.startsWith("${retainedOriginalFileStem(version, versionCode)}.")
    }

    val safeVersion = FilenameUtils.sanitize(version.ifBlank { "unspecified" })
    val prefix = "${safeVersion}_"
    val suffix = "_original."
    if (!fileName.startsWith(prefix)) return false
    val remainder = fileName.removePrefix(prefix)
    val suffixIndex = remainder.indexOf(suffix)
    return suffixIndex > 0 && remainder.substring(0, suffixIndex).toLongOrNull() != null
}

internal fun isPatchOptionInputLeaseExpired(
    leasedAtMillis: Long,
    nowMillis: Long,
    maxAgeMillis: Long
): Boolean {
    if (nowMillis < leasedAtMillis) return false
    val elapsedMillis = nowMillis - leasedAtMillis
    return elapsedMillis < 0L || elapsedMillis >= maxAgeMillis
}

internal fun normalizeManagedPatchOptionInputExtension(extension: String): String {
    val normalized = extension.trim().trimStart('.').lowercase(Locale.ROOT)
    val isSafe = normalized.isNotEmpty() &&
        normalized.toByteArray(Charsets.UTF_8).size <= MAX_PATCH_OPTION_INPUT_EXTENSION_UTF8_BYTES &&
        normalized.none { character ->
            character == '\u0000' ||
                character == '/' ||
                character == '\\' ||
                Character.isISOControl(character)
        }
    return normalized.takeIf { isSafe } ?: "dat"
}

// Leaves room for "input_", a UUID, and the separator within the common
// 255-byte filesystem component limit.
private const val MAX_PATCH_OPTION_INPUT_EXTENSION_UTF8_BYTES = 200
