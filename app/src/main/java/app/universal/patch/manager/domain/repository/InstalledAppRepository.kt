package app.universal.patch.manager.domain.repository

import android.util.Log
import app.universal.patch.manager.data.platform.Filesystem
import app.universal.patch.manager.data.platform.RetainedOriginalReference
import app.universal.patch.manager.data.room.AppDatabase
import app.universal.patch.manager.data.room.apps.installed.AppliedPatch
import app.universal.patch.manager.data.room.apps.installed.InstallType
import app.universal.patch.manager.data.room.apps.installed.InstalledApp
import app.universal.patch.manager.data.room.profile.PatchProfilePayload
import app.universal.patch.manager.domain.installer.persistedInstallerPackageName
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.util.PM
import app.universal.patch.manager.util.PatchSelection
import app.universal.patch.manager.util.savedAppBasePackage
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private const val TAG = "InstalledAppRepository"
private const val INSTALLED_RECORD_TIMESTAMP_TOLERANCE_MS = 60_000L

internal fun installedRecordMatchesCurrentPackage(
    record: InstalledApp,
    installedVersion: String?,
    installedLastUpdateTime: Long,
    managedPatchedFileAvailable: Boolean,
    managedPatchedFileMatchesRecord: Boolean
): Boolean {
    if (record.installType == InstallType.SAVED) return false
    if (
        installedVersion != null &&
        !record.version.equals(installedVersion, ignoreCase = true)
    ) return false
    if (managedPatchedFileMatchesRecord) return true
    if (managedPatchedFileAvailable) return false
    if (record.createdAt <= 0L || installedLastUpdateTime <= 0L) return true
    return installedLastUpdateTime <=
        record.createdAt + INSTALLED_RECORD_TIMESTAMP_TOLERANCE_MS
}

internal class PendingHistoricalSavedEntry(
    private val repository: InstalledAppRepository,
    private val sourceApp: InstalledApp,
    private val sourceSelection: PatchSelection,
    private val targetPackageName: String,
    private val stagingApk: File,
    private val targetApk: File
) {
    private var finished = false

    suspend fun <T> commitWith(
        replacementTargetPackageName: String,
        replacement: suspend () -> T
    ): T {
        check(!finished) { "Historical saved entry transaction is already finished" }
        val previousTarget = repository.get(targetPackageName)
        val previousTargetSelection = previousTarget
            ?.let { repository.getAppliedPatches(targetPackageName) }
            .orEmpty()
        val previousReplacementTarget = repository.get(replacementTargetPackageName)
        val previousReplacementSelection = previousReplacementTarget
            ?.let { repository.getAppliedPatches(replacementTargetPackageName) }
            .orEmpty()
        val targetDirectory = requireNotNull(targetApk.parentFile)
        val backupApk = targetDirectory.resolve(
            ".${targetApk.name}.${UUID.randomUUID()}.bak"
        )
        val targetExisted = targetApk.isFile
        val previousLastModified = targetApk.takeIf(File::isFile)?.lastModified()
        val sourceHasRetainedRepatchInput = sourceApp.repatchSourcePath
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?.isFile == true
        var replacementStarted = false
        var historicalEntryPersisted = false
        var keepBackup = false
        try {
            if (targetExisted) {
                targetApk.copyTo(backupApk, overwrite = true)
                check(backupApk.isFile && backupApk.length() == targetApk.length()) {
                    "Failed to verify the historical saved APK backup"
                }
            }
            replacementStarted = true
            stagingApk.copyTo(targetApk, overwrite = true)
            check(targetApk.isFile && targetApk.length() == stagingApk.length()) {
                "Failed to verify the historical saved APK"
            }
            repository.addOrUpdate(
                currentPackageName = targetPackageName,
                originalPackageName = sourceApp.originalPackageName,
                version = sourceApp.version,
                installType = InstallType.SAVED,
                patchSelection = sourceSelection,
                selectionPayload = sourceApp.selectionPayload,
                useMount = sourceApp.useMount,
                updateUseMount = true,
                createdAtOverride = sourceApp.createdAt,
                repatchSourcePath = sourceApp.repatchSourcePath,
                updateRepatchSource = true,
                versionCode = sourceApp.versionCode,
                updateVersionCode = true
            )
            historicalEntryPersisted = true
            if (sourceHasRetainedRepatchInput) {
                val historicalSourceRetained = repository.get(targetPackageName)
                    ?.repatchSourcePath
                    ?.takeIf(String::isNotBlank)
                    ?.let(::File)
                    ?.isFile == true
                check(historicalSourceRetained) {
                    "Failed to preserve the historical Repatch input"
                }
            }
            val result = replacement()
            repository.pruneRepatchInputs()
            return result
        } catch (error: Throwable) {
            if (replacementStarted) {
                val fileRestoreError = runCatching {
                    if (targetExisted) {
                        check(backupApk.isFile) {
                            "The historical saved APK backup is unavailable"
                        }
                        backupApk.copyTo(targetApk, overwrite = true)
                        check(targetApk.isFile && targetApk.length() == backupApk.length()) {
                            "Failed to verify the restored historical saved APK"
                        }
                        previousLastModified?.let(targetApk::setLastModified)
                    } else {
                        check(targetApk.delete() || !targetApk.exists()) {
                            "Failed to remove the incomplete historical saved APK"
                        }
                    }
                }.exceptionOrNull()
                if (fileRestoreError != null) {
                    keepBackup = backupApk.isFile
                    error.addSuppressed(fileRestoreError)
                }
            }
            runCatching {
                repository.restoreHistoricalTarget(
                    targetPackageName = replacementTargetPackageName,
                    previousApp = previousReplacementTarget,
                    previousSelection = previousReplacementSelection
                )
            }.exceptionOrNull()?.let(error::addSuppressed)
            if (historicalEntryPersisted) {
                runCatching {
                    repository.restoreHistoricalTarget(
                        targetPackageName = targetPackageName,
                        previousApp = previousTarget,
                        previousSelection = previousTargetSelection
                    )
                }.exceptionOrNull()?.let(error::addSuppressed)
            }
            throw error
        } finally {
            stagingApk.delete()
            if (!keepBackup) backupApk.delete()
            finished = true
        }
    }

    fun discard() {
        if (finished) return
        stagingApk.delete()
        finished = true
    }
}

internal fun migratedAutoPatchTargets(
    records: List<InstalledApp>,
    enabledTargets: Set<String>,
    oldKey: String,
    newKey: String
): Set<String> {
    if (oldKey == newKey) return enabledTargets
    val oldRecord = records.firstOrNull { it.currentPackageName == oldKey }
    val originalPackageName = oldRecord?.originalPackageName
        ?.takeIf(String::isNotBlank)
        ?: savedAppBasePackage(oldKey)
    if (oldKey !in enabledTargets && originalPackageName !in enabledTargets) {
        return enabledTargets
    }
    val relatedKeys = records
        .filter { app ->
            val appOriginalPackage = app.originalPackageName.takeIf(String::isNotBlank)
                ?: savedAppBasePackage(app.currentPackageName)
            appOriginalPackage == originalPackageName
        }
        .mapTo(mutableSetOf(), InstalledApp::currentPackageName)
    return enabledTargets.toMutableSet().apply {
        remove(originalPackageName)
        remove(oldKey)
        removeAll(relatedKeys)
        add(newKey)
    }
}

class InstalledAppRepository(
    db: AppDatabase,
    private val patchOptionInputManager: PatchOptionInputManager,
    private val fs: Filesystem,
    private val prefs: PreferencesManager,
    private val pm: PM
) {
    private val dao = db.installedAppDao()
    private val archiveRestoreMutex = kotlinx.coroutines.sync.Mutex()

    fun getAll() = dao.getAll().distinctUntilChanged()

    suspend fun get(packageName: String) = dao.get(packageName)

    /** Commit one already validated archive entry, preserving the old APK on a failed write. */
    internal suspend fun restoreArchivedApp(
        record: InstalledApp,
        apk: File,
        repatchInput: File?,
        selection: PatchSelection
    ): Boolean = archiveRestoreMutex.withLock {
        val key = record.currentPackageName
        val existing = dao.get(key)
        require(existing == null || existing.installType == InstallType.SAVED)
        val target = fs.getPatchedAppFile(key, record.version)
        val parent = requireNotNull(target.parentFile)
        val staging = File.createTempFile(".app-entry-", ".tmp", parent)
        val backup = parent.resolve(".app-entry-" + UUID.randomUUID() + ".bak")
        var retainBackup = false
        try {
            apk.copyTo(staging, overwrite = true)
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                val existed = target.isFile
                if (existed) target.copyTo(backup, overwrite = true)
                java.nio.file.Files.move(
                    staging.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
                try {
                    addOrUpdate(
                        currentPackageName = key,
                        originalPackageName = record.originalPackageName,
                        version = record.version,
                        installType = InstallType.SAVED,
                        patchSelection = selection,
                        selectionPayload = record.selectionPayload,
                        useMount = record.useMount,
                        updateUseMount = true,
                        createdAtOverride = record.createdAt,
                        repatchSourcePath = repatchInput?.absolutePath,
                        updateRepatchSource = true,
                        requireRepatchSource = repatchInput != null,
                        versionCode = record.versionCode,
                        updateVersionCode = true
                    )
                } catch (failure: Throwable) {
                    runCatching {
                        if (existed) {
                            java.nio.file.Files.move(
                                backup.toPath(), target.toPath(),
                                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING
                            )
                        } else {
                            java.nio.file.Files.deleteIfExists(target.toPath())
                        }
                    }.onFailure {
                        retainBackup = existed
                        failure.addSuppressed(it)
                    }
                    throw failure
                }
            }
            existing != null
        } finally {
            staging.delete()
            if (!retainBackup) backup.delete()
        }
    }

    suspend fun rememberVersionCode(app: InstalledApp, versionCode: Long) =
        dao.rememberVersionCode(app.currentPackageName, app.version, versionCode)

    suspend fun updateSignatureWorkflow(packageName: String, enabled: Boolean): InstalledApp? {
        val app = dao.get(packageName) ?: return null
        val payload = app.selectionPayload ?: return null
        if (!payload.signatureWorkflow.remembered ||
            app.repatchSourcePath?.let { File(it).isFile } != true
        ) return null
        val updated = app.copy(
            selectionPayload = payload.copy(
                signatureWorkflow = payload.signatureWorkflow.copy(enabled = enabled)
            )
        )
        dao.updateSelectionPayload(packageName, requireNotNull(updated.selectionPayload))
        return updated
    }

    suspend fun getByInstallType(installType: InstallType) =
        dao.getByInstallType(installType)

    suspend fun getCurrentInstalledRecord(
        packageName: String,
        installedVersion: String?,
        installedLastUpdateTime: Long,
        installedApk: File?
    ): InstalledApp? {
        val record = dao.get(packageName) ?: return null
        val managedPatchedFileAvailable = fs.getPatchedAppFile(
            record.currentPackageName,
            record.version
        ).isFile
        val managedPatchedFileMatchesRecord = installedApk
            ?.takeIf(File::isFile)
            ?.let { apk ->
                fs.isManagedPatchedAppFile(
                    file = apk,
                    packageName = record.currentPackageName,
                    version = record.version
                )
            } == true
        return record.takeIf {
            installedRecordMatchesCurrentPackage(
                record = it,
                installedVersion = installedVersion,
                installedLastUpdateTime = installedLastUpdateTime,
                managedPatchedFileAvailable = managedPatchedFileAvailable,
                managedPatchedFileMatchesRecord = managedPatchedFileMatchesRecord
            )
        }
    }

    suspend fun getAppliedPatches(packageName: String): PatchSelection =
        dao.getPatchesSelection(packageName).mapValues { (_, patches) -> patches.toSet() }

    suspend fun addOrUpdate(
        currentPackageName: String,
        originalPackageName: String,
        version: String,
        installType: InstallType,
        patchSelection: PatchSelection,
        selectionPayload: PatchProfilePayload? = null,
        useMount: Boolean? = null,
        updateUseMount: Boolean = useMount != null,
        resetCreatedAt: Boolean = false,
        createdAtOverride: Long? = null,
        sortOrderOverride: Int? = null,
        customInstallerPackageName: String? = null,
        repatchSourcePath: String? = null,
        updateRepatchSource: Boolean = false,
        versionCode: Long? = null,
        updateVersionCode: Boolean = versionCode != null,
        requireRepatchSource: Boolean = false
    ) {
        var createdRepatchSourcePath: String? = null
        try {
            patchOptionInputManager.updateReferences {
                val existingApp = dao.get(currentPackageName)
                val existingSortOrder = dao.getSortOrder(currentPackageName)
                val sortOrder = sortOrderOverride
                    ?: existingSortOrder
                    ?: ((dao.getMaxSortOrder() ?: -1) + 1)
                val createdAt = createdAtOverride ?: when {
                    existingApp == null -> System.currentTimeMillis()
                    resetCreatedAt -> System.currentTimeMillis()
                    else -> existingApp.createdAt
                }
                val persistedCustomInstallerPackageName = persistedInstallerPackageName(
                    installType = installType,
                    selectedInstallerPackageName = customInstallerPackageName,
                    existingInstallType = existingApp?.installType,
                    existingInstallerPackageName = existingApp?.customInstallerPackageName
                )
                val persistedRepatchSourcePath = if (updateRepatchSource) {
                    repatchSourcePath
                        ?.takeIf(String::isNotBlank)
                        ?.let { sourcePath ->
                            runCatching {
                                fs.persistRepatchInputFile(
                                    entryKey = currentPackageName,
                                    sourcePath = sourcePath,
                                    retainedPath = existingApp?.repatchSourcePath
                                ).also { persisted ->
                                    if (persisted.created) {
                                        createdRepatchSourcePath = persisted.file.absolutePath
                                    }
                                }.file.absolutePath
                            }.onFailure { error ->
                                Log.w(
                                    TAG,
                                    "Failed to retain Repatch input for $currentPackageName",
                                    error
                                )
                            }.getOrNull()
                        }
                } else {
                    existingApp?.repatchSourcePath
                }
                check(
                    (!requireRepatchSource && selectionPayload?.signatureWorkflow?.remembered != true) ||
                        persistedRepatchSourcePath?.let { File(it).isFile } == true
                ) { "Unable to retain the Repatch input." }
                dao.upsertApp(
                    InstalledApp(
                        currentPackageName = currentPackageName,
                        originalPackageName = originalPackageName,
                        version = version,
                        versionCode = when {
                            updateVersionCode -> versionCode
                            existingApp != null && existingApp.version == version -> existingApp.versionCode
                            else -> null
                        },
                        installType = installType,
                        sortOrder = sortOrder,
                        selectionPayload = selectionPayload,
                        createdAt = createdAt,
                        customInstallerPackageName = persistedCustomInstallerPackageName,
                        repatchSourcePath = persistedRepatchSourcePath,
                        useMount = if (updateUseMount) useMount else existingApp?.useMount
                    ),
                    patchSelection.flatMap { (uid, patches) ->
                        patches.map { patch ->
                            AppliedPatch(
                                packageName = currentPackageName,
                                bundle = uid,
                                patchName = patch
                            )
                        }
                    }
                )
            }
        } catch (error: Throwable) {
            val committedRepatchSource = createdRepatchSourcePath?.let { createdPath ->
                withContext(NonCancellable) {
                    runCatching {
                        dao.get(currentPackageName)?.repatchSourcePath == createdPath
                    }.getOrDefault(false)
                }
            } == true
            if (!committedRepatchSource) {
                fs.deleteRepatchInputFile(createdRepatchSourcePath)
            }
            throw error
        }
    }

    suspend fun pruneRepatchInputs(additionalRetainedPaths: Collection<String> = emptyList()) {
        withContext(NonCancellable) {
            runCatching {
                patchOptionInputManager.updateReferences {
                    fs.pruneRepatchInputFiles(
                        dao.getAllSnapshot().map(InstalledApp::repatchSourcePath) +
                            additionalRetainedPaths
                    )
                }
            }.onFailure { error ->
                Log.w(TAG, "Failed to prune stale Repatch inputs", error)
            }
        }
    }

    suspend fun clearRepatchInputs() {
        withContext(NonCancellable) {
            patchOptionInputManager.updateReferences {
                dao.clearRepatchSourcePaths()
                fs.pruneRepatchInputFiles(emptyList())
            }
        }
    }

    suspend fun reorderApps(orderedPackageNames: List<String>) {
        orderedPackageNames.forEachIndexed { index, packageName ->
            dao.updateSortOrder(packageName, index)
        }
    }

    internal suspend fun restoreHistoricalTarget(
        targetPackageName: String,
        previousApp: InstalledApp?,
        previousSelection: PatchSelection
    ) {
        if (previousApp == null) {
            patchOptionInputManager.updateReferences {
                dao.get(targetPackageName)?.let { dao.delete(it) }
                fs.deleteRepatchInputsForEntry(targetPackageName)
            }
            return
        }
        addOrUpdate(
            currentPackageName = previousApp.currentPackageName,
            originalPackageName = previousApp.originalPackageName,
            version = previousApp.version,
            installType = previousApp.installType,
            patchSelection = previousSelection,
            selectionPayload = previousApp.selectionPayload,
            useMount = previousApp.useMount,
            updateUseMount = true,
            createdAtOverride = previousApp.createdAt,
            sortOrderOverride = previousApp.sortOrder,
            customInstallerPackageName = previousApp.customInstallerPackageName,
            repatchSourcePath = previousApp.repatchSourcePath,
            updateRepatchSource = true,
            versionCode = previousApp.versionCode,
            updateVersionCode = true
        )
    }

    internal suspend fun prepareHistoricalSavedEntry(
        sourceApp: InstalledApp,
        targetPackageName: String
    ): PendingHistoricalSavedEntry? {
        val sourceApk = fs.getPatchedAppFile(
            sourceApp.currentPackageName,
            sourceApp.version
        ).takeIf(File::isFile) ?: return null
        val targetApk = fs.getPatchedAppFile(targetPackageName, sourceApp.version)
        val targetDirectory = requireNotNull(targetApk.parentFile)
        check(targetDirectory.mkdirs() || targetDirectory.isDirectory) {
            "Unable to create the historical saved APK directory"
        }
        val stagingApk = targetDirectory.resolve(
            ".${targetApk.name}.${UUID.randomUUID()}.history.tmp"
        )
        return try {
            sourceApk.copyTo(stagingApk, overwrite = true)
            check(stagingApk.isFile && stagingApk.length() == sourceApk.length()) {
                "Failed to verify the historical saved APK staging copy"
            }
            PendingHistoricalSavedEntry(
                repository = this,
                sourceApp = sourceApp,
                sourceSelection = getAppliedPatches(sourceApp.currentPackageName),
                targetPackageName = targetPackageName,
                stagingApk = stagingApk,
                targetApk = targetApk
            )
        } catch (error: Throwable) {
            stagingApk.delete()
            throw error
        }
    }

    suspend fun setAutoPatchTarget(installedApp: InstalledApp, enabled: Boolean) {
        val originalPackageName = installedApp.originalPackageName.takeIf(String::isNotBlank)
            ?: savedAppBasePackage(installedApp.currentPackageName)
        val relatedKeys = dao.getAllSnapshot()
            .filter { app ->
                app.originalPackageName == originalPackageName ||
                    savedAppBasePackage(app.currentPackageName) == originalPackageName
            }
            .mapTo(mutableSetOf(), InstalledApp::currentPackageName)
        val enabledTargets = prefs.autoPatchEnabledPackages.get().toMutableSet()
        enabledTargets.remove(originalPackageName)
        enabledTargets.removeAll(relatedKeys)
        if (enabled) enabledTargets.add(installedApp.currentPackageName)
        prefs.autoPatchEnabledPackages.update(enabledTargets)
    }

    suspend fun migrateAutoPatchTarget(oldKey: String, newKey: String) {
        val enabledTargets = prefs.autoPatchEnabledPackages.get()
        val migratedTargets = migratedAutoPatchTargets(
            records = dao.getAllSnapshot(),
            enabledTargets = enabledTargets,
            oldKey = oldKey,
            newKey = newKey
        )
        if (migratedTargets != enabledTargets) {
            prefs.autoPatchEnabledPackages.update(migratedTargets)
        }
    }

    suspend fun delete(installedApp: InstalledApp) {
        patchOptionInputManager.updateReferences {
            dao.delete(installedApp)
            fs.deleteRepatchInputsForEntry(installedApp.currentPackageName)
        }
        cleanupDeletedAutoPatchTargets(listOf(installedApp))
        pruneRetainedOriginals()
    }

    suspend fun deleteByInstallType(installType: InstallType) {
        val deletedApps = patchOptionInputManager.updateReferences {
            val apps = dao.getByInstallType(installType)
            dao.deleteByInstallType(installType)
            apps.forEach { app ->
                fs.deleteRepatchInputsForEntry(app.currentPackageName)
            }
            apps
        }
        cleanupDeletedAutoPatchTargets(deletedApps)
        pruneRetainedOriginals()
    }

    private suspend fun cleanupDeletedAutoPatchTargets(deletedApps: List<InstalledApp>) {
        if (deletedApps.isEmpty()) return
        val remainingApps = dao.getAllSnapshot()
        val remainingOriginalPackages = remainingApps.mapTo(mutableSetOf()) {
            it.originalPackageName.takeIf(String::isNotBlank)
                ?: savedAppBasePackage(it.currentPackageName)
        }
        val enabledTargets = prefs.autoPatchEnabledPackages.get().toMutableSet()
        val changedByEntry = deletedApps.fold(false) { changed, app ->
            enabledTargets.remove(app.currentPackageName) || changed
        }
        val changedByLegacyKey = deletedApps.fold(changedByEntry) { changed, app ->
            val originalPackageName = app.originalPackageName.takeIf(String::isNotBlank)
                ?: savedAppBasePackage(app.currentPackageName)
            if (originalPackageName !in remainingOriginalPackages) {
                enabledTargets.remove(originalPackageName) || changed
            } else {
                changed
            }
        }
        if (changedByLegacyKey) {
            prefs.autoPatchEnabledPackages.update(enabledTargets)
        }
    }

    suspend fun pruneRetainedOriginals() {
        fs.pruneOriginalAppFiles(
            dao.getAllSnapshot().map(::retainedOriginalReference)
        )
    }

    private fun retainedOriginalReference(app: InstalledApp): RetainedOriginalReference {
        val originalPackageName = app.originalPackageName.takeIf(String::isNotBlank)
            ?: savedAppBasePackage(app.currentPackageName)
        val managedVersionCode = fs.getPatchedAppFile(
            app.currentPackageName,
            app.version
        ).takeIf(File::isFile)
            ?.let(pm::getPackageInfo)
            ?.takeIf { info ->
                info.versionName.equals(app.version, ignoreCase = true)
            }
            ?.let(pm::getVersionCode)
        val installedVersionCode = if (
            managedVersionCode == null &&
            app.installType != InstallType.SAVED
        ) {
            pm.getPackageInfo(app.currentPackageName)
                ?.takeIf { info ->
                    info.versionName.equals(app.version, ignoreCase = true)
                }
                ?.let(pm::getVersionCode)
        } else {
            null
        }
        return RetainedOriginalReference(
            packageName = originalPackageName,
            version = app.version,
            versionCode = managedVersionCode ?: installedVersionCode
        )
    }
}
