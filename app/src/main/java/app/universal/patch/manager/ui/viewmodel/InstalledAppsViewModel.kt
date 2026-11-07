package app.universal.patch.manager.ui.viewmodel

import app.universal.patch.manager.domain.bundles.toLegacyAwareBundleIndex

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import app.universal.patch.manager.R
import app.universal.patch.manager.data.platform.Filesystem
import app.universal.patch.manager.data.room.apps.installed.InstallType
import app.universal.patch.manager.data.room.apps.installed.InstalledApp
import app.universal.patch.manager.data.room.profile.PatchProfilePayload
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.domain.bundles.PatchBundleSource
import app.universal.patch.manager.domain.bundles.compareBundleVersions
import app.universal.patch.manager.domain.bundles.PatchBundleSource.Extensions.asRemoteOrNull
import app.universal.patch.manager.domain.installer.RootInstaller
import app.universal.patch.manager.domain.installer.RootServiceException
import app.universal.patch.manager.domain.installer.usesPersistedPlayStoreMountMode
import app.universal.patch.manager.domain.installer.root.RootMountOperation
import app.universal.patch.manager.domain.installer.root.RootMountRequest
import app.universal.patch.manager.domain.installer.root.RootMountTransactionCoordinator
import app.universal.patch.manager.domain.installer.root.requireSuccess
import app.universal.patch.manager.domain.repository.InstalledAppRepository
import app.universal.patch.manager.domain.repository.PatchBundleRepository
import app.universal.patch.manager.domain.repository.mergeWithRemappedSelection
import app.universal.patch.manager.domain.repository.remapAndExtractSelection
import app.universal.patch.manager.domain.repository.remapLocalBundles
import app.universal.patch.manager.domain.repository.toSignatureMap
import app.universal.patch.manager.util.PM
import app.universal.patch.manager.util.PatchSelection
import app.universal.patch.manager.util.mutableStateSetOf
import app.universal.patch.manager.util.PatchBundleExportData
import app.universal.patch.manager.util.PatchedAppExportData
import app.universal.patch.manager.util.ExportNameFormatter
import app.universal.patch.manager.util.buildSavedAppVariantIdentity
import app.universal.patch.manager.util.savedAppBasePackage
import app.universal.patch.manager.util.savedApkAbiLabel
import app.universal.patch.manager.util.simpleMessage
import app.universal.patch.manager.util.supportsRootMount
import app.universal.patch.manager.util.toast
import app.universal.patch.manager.util.versionCodeFor
import app.universal.patch.manager.patcher.patch.PatchBundleInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.universal.patch.manager.patcher.split.SplitApkPreparer
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale

class InstalledAppsViewModel(
    private val installedAppsRepository: InstalledAppRepository,
    private val patchBundleRepository: PatchBundleRepository,
    private val pm: PM,
    private val rootInstaller: RootInstaller,
    private val rootMountCoordinator: RootMountTransactionCoordinator,
    private val filesystem: Filesystem,
    private val prefs: PreferencesManager
) : ViewModel() {
    val apps = combine(
        installedAppsRepository.getAll(),
        prefs.enableSavedApps.flow
    ) { installedApps, savedAppsEnabled ->
        if (savedAppsEnabled) installedApps
        else installedApps.filter { it.installType != InstallType.SAVED }
    }.flowOn(Dispatchers.IO)

    val packageInfoMap = mutableStateMapOf<String, PackageInfo?>()
    val installedOnDeviceMap = mutableStateMapOf<String, Boolean>()
    val mountedOnDeviceMap = mutableStateMapOf<String, Boolean>()
    val playStoreInstallerSourceMap = mutableStateMapOf<String, Boolean>()
    val savedCopyMap = mutableStateMapOf<String, Boolean>()
    val savedApkAbiLabelMap = mutableStateMapOf<String, String>()
    val appLabelMap = mutableStateMapOf<String, String>()
    val customInstallerLabelMap = mutableStateMapOf<String, String>()
    private val devicePackageLookupMap = mutableStateMapOf<String, String>()
    private var normalizingSavedEntries = false
    val selectedApps = mutableStateSetOf<String>()
    val missingPackages = mutableStateSetOf<String>()
    val bundleSummaries = mutableStateMapOf<String, List<AppBundleSummary>>()
    val bundleSummaryLoaded = mutableStateSetOf<String>()

    init {
        viewModelScope.launch {
            apps.collect { installedApps ->
                if (normalizeDuplicateSavedEntries(installedApps)) {
                    return@collect
                }
                val seenPackages = mutableSetOf<String>()
                val newMissing = mutableSetOf<String>()

                installedApps.forEach { installedApp ->
                    val packageName = installedApp.currentPackageName
                    seenPackages += packageName

                    val packageInfo = resolvePackageInfo(installedApp)
                    packageInfoMap[packageName] = packageInfo

                    if (installedApp.installType != InstallType.SAVED && packageInfo == null) {
                        newMissing += packageName
                    }
                }

                val stalePackages = packageInfoMap.keys.toSet() - seenPackages
                stalePackages.forEach { packageName ->
                    packageInfoMap.remove(packageName)
                    installedOnDeviceMap.remove(packageName)
                    mountedOnDeviceMap.remove(packageName)
                    playStoreInstallerSourceMap.remove(packageName)
                    savedCopyMap.remove(packageName)
                    savedApkAbiLabelMap.remove(packageName)
                    appLabelMap.remove(packageName)
                    customInstallerLabelMap.remove(packageName)
                    devicePackageLookupMap.remove(packageName)
                    missingPackages.remove(packageName)
                    selectedApps.remove(packageName)
                }

                val missingToRemove = missingPackages.filterNot { it in newMissing }.toSet()
                missingPackages.removeAll(missingToRemove)
                val missingToAdd = newMissing.filterNot { it in missingPackages }.toSet()
                missingPackages.addAll(missingToAdd)

                val selectablePackages = installedApps
                    .map(InstalledApp::currentPackageName)
                    .toSet()
                selectedApps.retainAll(selectablePackages)
            }
        }

        viewModelScope.launch {
            combine(
                apps,
                patchBundleRepository.allBundlesInfoFlow,
                patchBundleRepository.sources
            ) { installedApps, bundleInfo, sources ->
                Triple(installedApps, bundleInfo, sources)
            }.collect { (installedApps, bundleInfo, sources) ->
                val sourceMap = sources.associateBy { it.uid }
                val signatures = bundleInfo.toSignatureMap().toMutableMap().apply {
                    sources
                        .filter { it.asRemoteOrNull == null }
                        .forEach { source -> putIfAbsent(source.uid, emptySet()) }
                }
                val packageNames = installedApps.map { it.currentPackageName }.toSet()

                installedApps.forEach { app ->
                    val selection = loadAppliedPatches(app.currentPackageName)
                    val remappedPayload = app.selectionPayload?.remapLocalBundles(
                        sources = sources,
                        signatures = signatures
                    )
                    val summaryApp = if (remappedPayload != null && remappedPayload != app.selectionPayload) {
                        app.copy(selectionPayload = remappedPayload)
                    } else {
                        app
                    }
                    val summaries = buildBundleSummaries(summaryApp, selection, bundleInfo, sourceMap)
                    if (summaries.isEmpty()) {
                        bundleSummaries.remove(app.currentPackageName)
                    } else {
                        bundleSummaries[app.currentPackageName] = summaries
                    }
                    bundleSummaryLoaded.add(app.currentPackageName)
                }

                val stale = bundleSummaries.keys - packageNames
                stale.forEach { bundleSummaries.remove(it) }
                val staleLoaded = bundleSummaryLoaded - packageNames
                bundleSummaryLoaded.removeAll(staleLoaded)
            }
        }
    }

    fun refreshDeviceAndMountState() = viewModelScope.launch {
        val installedApps = apps.first()
        val newMissing = mutableSetOf<String>()

        installedApps.forEach { installedApp ->
            val packageName = installedApp.currentPackageName
            val packageInfo = resolvePackageInfo(installedApp)
            packageInfoMap[packageName] = packageInfo

            if (installedApp.installType != InstallType.SAVED && packageInfo == null) {
                newMissing += packageName
            }
        }

        val missingToRemove = missingPackages.filterNot { it in newMissing }.toSet()
        missingPackages.removeAll(missingToRemove)
        val missingToAdd = newMissing.filterNot { it in missingPackages }.toSet()
        missingPackages.addAll(missingToAdd)
    }

    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == Intent.ACTION_PACKAGE_REMOVED &&
                intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            ) {
                return
            }

            val packageName = intent.data?.schemeSpecificPart ?: return
            val matchingEntries = devicePackageLookupMap
                .filterValues { it == packageName }
                .keys
                .toSet()
            val targetEntries = if (matchingEntries.isNotEmpty()) {
                matchingEntries
            } else if (packageName in packageInfoMap || packageName in installedOnDeviceMap) {
                setOf(packageName)
            } else {
                emptySet()
            }
            if (targetEntries.isEmpty()) return

            viewModelScope.launch {
                val installedAppsByPackage = apps.first().associateBy(InstalledApp::currentPackageName)
                val installedInfoAndInstallerLabels = withContext(Dispatchers.IO) {
                    val installedInfo = pm.getPackageInfo(packageName)
                    val installerLabels = targetEntries.associateWith { key ->
                        installedAppsByPackage[key]
                            ?.takeIf { it.installType == InstallType.CUSTOM && installedInfo != null }
                            ?.let { installedApp ->
                                pm.getInstallerLabel(
                                    packageName,
                                    installedApp.customInstallerPackageName
                                )
                            }
                    }
                    installedInfo to installerLabels
                }
                val (installedInfo, installerLabels) = installedInfoAndInstallerLabels
                targetEntries.forEach { key ->
                    val installerLabel = installerLabels[key]
                    installedOnDeviceMap[key] = installedInfo != null
                    if (installedInfo != null) {
                        packageInfoMap[key] = installedInfo
                    }
                    if (
                        installedAppsByPackage[key]?.installType == InstallType.CUSTOM &&
                        !installerLabel.isNullOrBlank()
                    ) {
                        customInstallerLabelMap[key] = installerLabel
                    } else {
                        customInstallerLabelMap.remove(key)
                    }
                }
            }
        }
    }.also {
        ContextCompat.registerReceiver(
            pm.application,
            it,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    data class AppBundleSummary(
        val title: String,
        val version: String?,
        val hasUpdate: Boolean
    )

    data class SavedAppsExportResult(
        val exported: Int,
        val failed: Int,
        val total: Int
    )

    fun removeSavedApp(app: InstalledApp) = viewModelScope.launch {
        if (app.installType != InstallType.SAVED) return@launch
        if (!clearSavedData(app, deleteRecord = true)) return@launch
        savedCopyMap[app.currentPackageName] = false
    }

    fun deleteSavedEntry(app: InstalledApp) = viewModelScope.launch {
        if (!clearSavedData(app, deleteRecord = true)) return@launch
        savedCopyMap[app.currentPackageName] = false
    }

    suspend fun getRepatchSelection(app: InstalledApp): PatchSelection? = withContext(Dispatchers.IO) {
        val storedSelection = loadAppliedPatches(app.currentPackageName)
        val payload = app.selectionPayload ?: return@withContext storedSelection.takeIf { it.isNotEmpty() }
        val sources = patchBundleRepository.sources.first()
        val sourceIds = sources.map { it.uid }.toSet()
        val signatures = patchBundleRepository.allBundlesInfoFlow.first().toSignatureMap()
        val (remappedPayload, remappedSelection) = payload.remapAndExtractSelection(sources, signatures)
        val mergedSelection = storedSelection.mergeWithRemappedSelection(
            originalPayload = payload,
            remappedPayload = remappedPayload,
            remappedSelection = remappedSelection,
            sources = sources
        )
        val persistableSelection = mergedSelection.filterKeys { it in sourceIds }
        if (persistableSelection.isNotEmpty() &&
            (persistableSelection != storedSelection || remappedPayload != payload)
        ) {
            installedAppsRepository.addOrUpdate(
                app.currentPackageName,
                app.originalPackageName,
                app.version,
                app.installType,
                persistableSelection,
                remappedPayload
            )
        }
        mergedSelection.takeIf { it.isNotEmpty() }
    }

    override fun onCleared() {
        super.onCleared()
        pm.application.unregisterReceiver(packageChangeReceiver)
    }

    fun toggleSelection(installedApp: InstalledApp) = viewModelScope.launch {
        val packageName = installedApp.currentPackageName
        val shouldSelect = packageName !in selectedApps
        setSelectionInternal(installedApp, shouldSelect)
    }

    fun setSelection(installedApp: InstalledApp, shouldSelect: Boolean) =
        viewModelScope.launch { setSelectionInternal(installedApp, shouldSelect) }

    fun clearSelection() {
        selectedApps.clear()
    }

    fun reorderApps(orderedPackageNames: List<String>) = viewModelScope.launch(Dispatchers.IO) {
        installedAppsRepository.reorderApps(orderedPackageNames)
    }

    fun deleteSelectedApps() = viewModelScope.launch {
        if (selectedApps.isEmpty()) return@launch

        val snapshot = apps.first()
        val toDelete = snapshot.filter { it.currentPackageName in selectedApps }
        if (toDelete.isEmpty()) {
            selectedApps.clear()
            return@launch
        }

        val removedPackages = mutableSetOf<String>()
        toDelete.forEach { app ->
            if (deleteAppEntry(app)) {
                removedPackages += app.currentPackageName
            }
        }

        selectedApps.removeAll(removedPackages)
        removedPackages.forEach { packageName ->
            packageInfoMap.remove(packageName)
            missingPackages.remove(packageName)
        }
    }

    fun exportSelectedSavedAppsToDirectory(
        context: Context,
        directory: Path,
        exportTemplate: String?,
        onResult: (SavedAppsExportResult) -> Unit = {}
    ) = viewModelScope.launch {
        val snapshot = apps.first()
        val selected = snapshot.filter {
            it.currentPackageName in selectedApps && it.installType == InstallType.SAVED
        }
        if (selected.isEmpty()) {
            onResult(SavedAppsExportResult(0, 0, 0))
            return@launch
        }

        val result = withContext(Dispatchers.IO) {
            exportSelectedSavedAppsInternal(selected, directory, exportTemplate)
        }
        onResult(result)
    }

    fun exportSelectedSavedAppsToTreeUri(
        context: Context,
        treeUri: Uri,
        exportTemplate: String?,
        onResult: (SavedAppsExportResult) -> Unit = {}
    ) = viewModelScope.launch {
        val snapshot = apps.first()
        val selected = snapshot.filter {
            it.currentPackageName in selectedApps && it.installType == InstallType.SAVED
        }
        if (selected.isEmpty()) {
            onResult(SavedAppsExportResult(0, 0, 0))
            return@launch
        }

        val root = DocumentFile.fromTreeUri(context, treeUri)
        if (root == null || !root.isDirectory) {
            onResult(SavedAppsExportResult(0, selected.size, selected.size))
            return@launch
        }

        val result = withContext(Dispatchers.IO) {
            var exported = 0
            var failed = 0

            selected.forEach { app ->
                val source = savedApkFile(app)
                if (source == null || !source.exists()) {
                    failed++
                    return@forEach
                }

                val exportData = buildExportMetadata(app, source)
                val fileName = ExportNameFormatter.format(exportTemplate, exportData)
                val targetName = resolveUniqueDocumentName(root, fileName)
                val target = root.createFile(if (exportData.fileExtension == "apks") "application/zip"
                    else "application/vnd.android.package-archive", targetName)
                if (target == null) {
                    failed++
                    return@forEach
                }

                val success = runCatching {
                    context.contentResolver.openOutputStream(target.uri)?.use { output ->
                        source.inputStream().use { input -> input.copyTo(output) }
                    } ?: error("Could not open output stream")
                }.isSuccess

                if (success) exported++ else failed++
            }

            SavedAppsExportResult(exported = exported, failed = failed, total = selected.size)
        }
        onResult(result)
    }

    private suspend fun setSelectionInternal(installedApp: InstalledApp, shouldSelect: Boolean) {
        val packageName = installedApp.currentPackageName

        if (shouldSelect) {
            selectedApps.add(packageName)
        } else {
            selectedApps.remove(packageName)
        }
    }

    private suspend fun resolvePackageInfo(installedApp: InstalledApp): PackageInfo? =
        withContext(Dispatchers.IO) {
            val packageName = installedApp.currentPackageName
            val savedCopy = savedApkFile(installedApp)
            val savedPackageInfo = savedCopy?.let(pm::getPackageInfo)
            val savedArchiveLabel = savedCopy?.let { pm.getArchiveLabel(it, savedPackageInfo) }
            val supportsRootMount = installedApp.supportsRootMount(savedPackageInfo?.packageName)
            val hasSavedCopy = savedCopy != null
            savedCopyMap[packageName] = hasSavedCopy
            val savedAbiLabel = savedCopy?.savedApkAbiLabel(pm.application)
            if (savedAbiLabel.isNullOrBlank()) {
                savedApkAbiLabelMap.remove(packageName)
            } else {
                savedApkAbiLabelMap[packageName] = savedAbiLabel
            }
            try {
                if (
                    installedApp.installType == InstallType.MOUNT &&
                    supportsRootMount &&
                    !rootInstaller.isAppInstalled(packageName)
                ) {
                    if (clearSavedData(installedApp, deleteRecord = true)) {
                        return@withContext null
                    }
                }
            } catch (_: RootServiceException) {
                // Ignore root service availability issues for mounted apps and fall back to package info lookup.
            }

            val mounted = if (installedApp.installType == InstallType.MOUNT && supportsRootMount) {
                runCatching { rootInstaller.isAppMounted(packageName) }.getOrDefault(false).also {
                    mountedOnDeviceMap[packageName] = it
                }
            } else {
                mountedOnDeviceMap.remove(packageName)
                false
            }

            if (
                installedApp.installType == InstallType.MOUNT &&
                supportsRootMount &&
                pm.getPackageInfo(packageName) != null
            ) {
                playStoreInstallerSourceMap[packageName] =
                    usesPersistedPlayStoreMountMode(
                        installedApp.installType,
                        installedApp.customInstallerPackageName
                    )
            } else {
                playStoreInstallerSourceMap.remove(packageName)
            }

            var resolvedLabel = savedArchiveLabel
            val resolvedPackageInfo = when {
                installedApp.installType == InstallType.SAVED ||
                    (installedApp.installType == InstallType.MOUNT && !supportsRootMount) -> {
                    if (savedCopy == null) return@withContext null
                    val archivePackageInfo = savedPackageInfo
                    val devicePackageName = archivePackageInfo?.packageName
                        ?.takeIf { it.isNotBlank() }
                        ?: installedApp.originalPackageName.takeIf { it.isNotBlank() }
                        ?: savedAppBasePackage(packageName)
                    devicePackageLookupMap[packageName] = devicePackageName

                    val installedInfo = pm.getPackageInfo(devicePackageName)
                    installedOnDeviceMap[packageName] = installedInfo != null
                    if (resolvedLabel.isNullOrBlank()) {
                        resolvedLabel = installedInfo?.let { info ->
                            runCatching { with(pm) { info.label() } }.getOrNull()
                        }
                    }
                    val archiveVersion = archivePackageInfo?.versionName?.takeIf { it.isNotBlank() }
                    val archiveVersionCode = archivePackageInfo?.versionCodeFor(archiveVersion ?: installedApp.version)
                    val resolvedInstallType = if (
                        installedApp.installType == InstallType.MOUNT && !supportsRootMount
                    ) {
                        InstallType.SAVED
                    } else {
                        installedApp.installType
                    }
                    if (
                        resolvedInstallType != installedApp.installType ||
                        (archiveVersion != null && archiveVersion != installedApp.version) ||
                        (archiveVersionCode != null && archiveVersionCode != installedApp.versionCode)
                    ) {
                        val selection = installedAppsRepository.getAppliedPatches(packageName)
                        installedAppsRepository.addOrUpdate(
                            currentPackageName = installedApp.currentPackageName,
                            originalPackageName = installedApp.originalPackageName,
                            version = archiveVersion ?: installedApp.version,
                            installType = resolvedInstallType,
                            patchSelection = selection,
                            selectionPayload = installedApp.selectionPayload,
                            useMount = installedApp.useMount,
                            updateUseMount = true,
                            createdAtOverride = installedApp.createdAt,
                            versionCode = archiveVersionCode,
                            updateVersionCode = archivePackageInfo != null
                        )
                    }
                    archivePackageInfo ?: installedInfo
                }

                else -> {
                    devicePackageLookupMap[packageName] = packageName
                    val installedInfo = pm.getPackageInfo(packageName)
                    installedOnDeviceMap[packageName] = installedInfo != null
                    if (resolvedLabel.isNullOrBlank() && !mounted) {
                        resolvedLabel = installedInfo?.let { info ->
                            runCatching { with(pm) { info.label() } }.getOrNull()
                        }
                    }
                    installedInfo ?: run {
                        val savedFile = filesystem.getPatchedAppFile(packageName, installedApp.version)
                        if (savedFile.exists()) pm.getPackageInfo(savedFile) else null
                    }
                }
            }

            val fallbackLabel = if (
                installedApp.installType == InstallType.SAVED ||
                (installedApp.installType == InstallType.MOUNT && !supportsRootMount)
            ) {
                installedApp.originalPackageName.takeIf { it.isNotBlank() }
                    ?: savedAppBasePackage(packageName)
            } else {
                packageName
            }
            appLabelMap[packageName] = resolvedLabel
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: fallbackLabel
            val customInstallerLabel = if (
                installedApp.installType == InstallType.CUSTOM &&
                installedOnDeviceMap[packageName] == true
            ) {
                pm.getInstallerLabel(
                    resolvedPackageInfo?.packageName ?: packageName,
                    installedApp.customInstallerPackageName
                )
            } else {
                null
            }
            if (customInstallerLabel.isNullOrBlank()) {
                customInstallerLabelMap.remove(packageName)
            } else {
                customInstallerLabelMap[packageName] = customInstallerLabel
            }
            resolvedPackageInfo
        }

    private fun exportSelectedSavedAppsInternal(
        selected: List<InstalledApp>,
        directory: Path,
        exportTemplate: String?
    ): SavedAppsExportResult {
        Files.createDirectories(directory)

        var exported = 0
        var failed = 0
        selected.forEach { app ->
            val source = savedApkFile(app)
            if (source == null || !source.exists()) {
                failed++
                return@forEach
            }

            val exportData = buildExportMetadata(app, source)
            val fileName = ExportNameFormatter.format(exportTemplate, exportData)
            val target = resolveUniqueTarget(directory, fileName)
            val success = runCatching {
                Files.copy(source.toPath(), target, StandardCopyOption.REPLACE_EXISTING)
            }.isSuccess
            if (success) exported++ else failed++
        }

        return SavedAppsExportResult(exported = exported, failed = failed, total = selected.size)
    }

    private fun buildExportMetadata(app: InstalledApp, source: File): PatchedAppExportData {
        val packageInfo = pm.getPackageInfo(source)
        val displayPackageName = if (app.installType == InstallType.SAVED) {
            app.originalPackageName.takeIf { it.isNotBlank() }
                ?: savedAppBasePackage(app.currentPackageName)
        } else {
            app.currentPackageName
        }
        val label = appLabelMap[app.currentPackageName]
            ?: pm.getArchiveLabel(source, packageInfo)
            ?: displayPackageName
        val patchBundles = bundleSummaries[app.currentPackageName]
            .orEmpty()
            .mapNotNull { summary ->
                val name = summary.title.takeIf(String::isNotBlank)
                val version = summary.version?.takeIf(String::isNotBlank)
                if (name == null && version == null) {
                    null
                } else {
                    PatchBundleExportData(name = name, version = version)
                }
            }
        return PatchedAppExportData(
            appName = label,
            packageName = packageInfo?.packageName ?: displayPackageName,
            appVersion = app.version,
            patchBundles = patchBundles,
            fileExtension = if (SplitApkPreparer.isSplitArchive(source)) "apks" else "apk"
        )
    }

    private fun resolveUniqueTarget(directory: Path, fileName: String): Path {
        val lower = fileName.lowercase(Locale.ROOT)
        val ext = if (lower.endsWith(".apk")) ".apk" else ""
        val base = if (ext.isNotEmpty()) fileName.dropLast(ext.length) else fileName
        var candidate = directory.resolve(fileName)
        if (!Files.exists(candidate)) return candidate

        var counter = 2
        while (true) {
            candidate = directory.resolve("${base}_$counter$ext")
            if (!Files.exists(candidate)) return candidate
            counter++
        }
    }

    private fun resolveUniqueDocumentName(directory: DocumentFile, fileName: String): String {
        val lower = fileName.lowercase(Locale.ROOT)
        val ext = if (lower.endsWith(".apk")) ".apk" else ""
        val base = if (ext.isNotEmpty()) fileName.dropLast(ext.length) else fileName
        if (directory.findFile(fileName) == null) return fileName

        var counter = 2
        while (true) {
            val candidate = "${base}_$counter$ext"
            if (directory.findFile(candidate) == null) return candidate
            counter++
        }
    }

    private fun savedApkFile(app: InstalledApp): File? =
        filesystem.getPatchedAppFile(
            app.currentPackageName,
            app.version
        ).takeIf(File::isFile)

    private suspend fun clearSavedData(app: InstalledApp, deleteRecord: Boolean): Boolean {
        if (deleteRecord) return deleteAppEntry(app)

        return try {
            withContext(Dispatchers.IO) {
                savedApkFile(app)?.delete()
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            reportSavedAppDeleteFailure(error)
            false
        }
    }

    private suspend fun deleteAppEntry(app: InstalledApp): Boolean {
        return try {
            if (app.installType == InstallType.MOUNT) {
                removeRootMountModule(app)
            }
            installedAppsRepository.delete(app)
            if (app.installType == InstallType.SAVED || app.installType == InstallType.MOUNT) {
                withContext(Dispatchers.IO) {
                    savedApkFile(app)?.delete()
                }
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            reportSavedAppDeleteFailure(error)
            false
        }
    }

    private suspend fun removeRootMountModule(app: InstalledApp) {
        rootMountCoordinator.execute(
            RootMountRequest(
                packageName = app.currentPackageName,
                userId = android.os.Process.myUid() / 100_000,
                operation = RootMountOperation.UNMOUNT,
                removeModuleAfterUnmount = true
            )
        ).requireSuccess()
    }

    private fun reportSavedAppDeleteFailure(error: Exception) {
        Log.e("InstalledAppsViewModel", "Failed to delete saved app", error)
        pm.application.toast(
            pm.application.getString(R.string.saved_app_delete_failed, error.simpleMessage())
        )
    }

    private suspend fun loadAppliedPatches(packageName: String): PatchSelection =
        withContext(Dispatchers.IO) { installedAppsRepository.getAppliedPatches(packageName) }

    private suspend fun normalizeDuplicateSavedEntries(installedApps: List<InstalledApp>): Boolean {
        // When overwrite protection is active, duplicate saved entries for the
        // currently installed variant are intentional and should remain visible.
        if (prefs.enableSavedApps.get() && prefs.disableSavedAppOverwrite.get()) return false
        if (normalizingSavedEntries || installedApps.isEmpty()) return false
        val duplicateSavedEntries = withContext(Dispatchers.IO) {
            val duplicates = mutableListOf<InstalledApp>()
            installedApps
                .groupBy(::appsBasePackage)
                .values
                .forEach { entries ->
                    val installedEntries = entries.filter { it.installType != InstallType.SAVED }
                    if (installedEntries.isEmpty()) return@forEach
                    val installedIdentities = installedEntries.mapTo(mutableSetOf()) { app ->
                        savedEntryIdentity(app)
                    }
                    entries
                        .filter { it.installType == InstallType.SAVED }
                        .forEach { savedEntry ->
                            if (savedEntryIdentity(savedEntry) in installedIdentities) {
                                duplicates += savedEntry
                            }
                        }
                }
            duplicates
        }
        if (duplicateSavedEntries.isEmpty()) return false

        normalizingSavedEntries = true
        try {
            withContext(Dispatchers.IO) {
                duplicateSavedEntries.forEach { savedEntry ->
                    installedAppsRepository.delete(savedEntry)
                    filesystem.getPatchedAppFile(
                        savedEntry.currentPackageName,
                        savedEntry.version
                    ).takeIf { it.exists() }?.delete()
                }
            }
        } finally {
            normalizingSavedEntries = false
        }
        return true
    }

    private suspend fun savedEntryIdentity(app: InstalledApp): String =
        buildSavedAppVariantIdentity(
            appVersion = app.version,
            selectionPayload = app.selectionPayload,
            patchSelection = loadAppliedPatches(app.currentPackageName),
            useMount = app.useMount
        )

    private fun appsBasePackage(app: InstalledApp): String =
        if (app.installType == InstallType.SAVED) {
            app.originalPackageName.takeIf { it.isNotBlank() }
                ?: savedAppBasePackage(app.currentPackageName)
        } else {
            app.currentPackageName
        }

    private fun buildBundleSummaries(
        app: InstalledApp,
        selection: PatchSelection,
        bundleInfo: Map<Int, PatchBundleInfo.Global>,
        sourceMap: Map<Int, PatchBundleSource>
    ): List<AppBundleSummary> {
        val payloadBundles = app.selectionPayload?.bundles.orEmpty()
        val sourceByEndpoint = sourceMap.values.mapNotNull { source ->
            source.asRemoteOrNull?.endpoint
                ?.normalizedBundleEndpoint()
                ?.let { it to source }
        }.toLegacyAwareBundleIndex()
        val payloadBundlesByUid = buildMap<Int, PatchProfilePayload.Bundle> {
            payloadBundles.forEach { bundle ->
                if (sourceMap.containsKey(bundle.bundleUid)) {
                    put(bundle.bundleUid, bundle)
                }
            }
            payloadBundles.forEach { bundle ->
                if (sourceMap.containsKey(bundle.bundleUid)) return@forEach
                val resolvedUid = bundle.sourceEndpoint
                    ?.normalizedBundleEndpoint()
                    ?.let(sourceByEndpoint::get)
                    ?.uid
                    ?: bundle.bundleUid
                putIfAbsent(resolvedUid, bundle)
            }
        }
        val summaries = mutableListOf<AppBundleSummary>()
        val processed = mutableSetOf<Int>()

        selection.keys.forEach { uid ->
            processed += uid
            buildSummaryEntry(uid, payloadBundlesByUid, bundleInfo, sourceMap, sourceByEndpoint)
                ?.let(summaries::add)
        }

        payloadBundlesByUid.keys.forEach { uid ->
            if (uid in processed) return@forEach
            processed += uid
            buildSummaryEntry(uid, payloadBundlesByUid, bundleInfo, sourceMap, sourceByEndpoint)
                ?.let(summaries::add)
        }

        return summaries
    }

    private fun buildSummaryEntry(
        uid: Int,
        payloadBundlesByUid: Map<Int, PatchProfilePayload.Bundle>,
        bundleInfo: Map<Int, PatchBundleInfo.Global>,
        sourceMap: Map<Int, PatchBundleSource>,
        sourceByEndpoint: Map<String, PatchBundleSource>
    ): AppBundleSummary? {
        val payloadBundle = payloadBundlesByUid[uid]
        val payloadEndpoint = payloadBundle?.sourceEndpoint
            ?.normalizedBundleEndpoint()
        val source = sourceMap[uid] ?: payloadEndpoint?.let(sourceByEndpoint::get)
        val info = bundleInfo[source?.uid ?: uid] ?: bundleInfo[uid]

        val title = source?.displayTitle
            ?: payloadBundle?.displayName
            ?: payloadBundle?.sourceName
            ?: info?.name
            ?: return null

        val payloadVersion = payloadBundle?.version?.takeUnless { it.isBlank() }
        val currentVersion = info?.version?.takeUnless { it.isBlank() }
            ?: source?.version?.takeUnless { it.isBlank() }
        val version = payloadVersion ?: currentVersion
        val hasUpdate = payloadVersion != null &&
            currentVersion != null &&
            isBundleUpdateAvailable(currentVersion, payloadVersion)

        return AppBundleSummary(
            title = title,
            version = version,
            hasUpdate = hasUpdate
        )
    }

}

private fun String.normalizedBundleEndpoint(): String? =
    app.universal.patch.manager.domain.bundles.bundleEndpointIdentity(this).trimEnd('/').takeIf(String::isNotBlank)

internal fun isBundleUpdateAvailable(currentVersion: String, appliedVersion: String): Boolean =
    compareBundleVersionStrings(currentVersion, appliedVersion) > 0

private fun compareBundleVersionStrings(first: String, second: String): Int {
    return compareBundleVersions(first, second)
        ?: compareLooseBundleVersionStrings(first, second)
}

private fun compareLooseBundleVersionStrings(first: String, second: String): Int {
    val aParts = first.split(Regex("[^0-9]+"))
        .filter { it.isNotBlank() }
        .map { it.toIntOrNull() ?: 0 }
    val bParts = second.split(Regex("[^0-9]+"))
        .filter { it.isNotBlank() }
        .map { it.toIntOrNull() ?: 0 }
    val max = maxOf(aParts.size, bParts.size)
    for (index in 0 until max) {
        val a = aParts.getOrElse(index) { 0 }
        val b = bParts.getOrElse(index) { 0 }
        if (a != b) return a.compareTo(b)
    }
    return first.compareTo(second, ignoreCase = true)
}
