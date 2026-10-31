/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.universal.patch.manager.domain.batch

import app.universal.patch.manager.data.platform.SharedApkFiles
import androidx.core.content.pm.PackageInfoCompat
import app.universal.patch.manager.data.platform.Filesystem
import app.universal.patch.manager.data.room.apps.installed.InstallType
import app.universal.patch.manager.data.room.apps.installed.InstalledApp
import app.universal.patch.manager.data.room.profile.PatchProfilePayload
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.domain.installer.InstallerManager
import app.universal.patch.manager.domain.installer.RootInstaller
import app.universal.patch.manager.domain.installer.installerTokenMatchesPatchMode
import app.universal.patch.manager.domain.installer.shouldApplyProfileInstallerPreference
import app.universal.patch.manager.domain.repository.InstalledAppRepository
import app.universal.patch.manager.domain.repository.PatchBundleRepository
import app.universal.patch.manager.domain.repository.PatchOptionsRepository
import app.universal.patch.manager.domain.repository.PatchProfileRepository
import app.universal.patch.manager.domain.repository.PatchSelectionRepository
import app.universal.patch.manager.domain.repository.remapLocalBundles
import app.universal.patch.manager.domain.repository.toConfiguration
import app.universal.patch.manager.domain.repository.toSignatureMap
import app.universal.patch.manager.patcher.patch.PatchBundleInfo
import app.universal.patch.manager.patcher.patch.PatchBundleType
import app.universal.patch.manager.patcher.patch.PatchInfo
import app.universal.patch.manager.patcher.patch.applyAvailability
import app.universal.patch.manager.patcher.patch.installerTypeFor
import app.universal.patch.manager.patcher.patch.removeGmsCoreSupport
import app.universal.patch.manager.patcher.patch.patcherEngineDisplayName
import app.universal.patch.manager.patcher.split.SplitApkInspector
import app.universal.patch.manager.patcher.split.SplitApkPreparer
import app.universal.patch.manager.ui.model.SelectedApp
import app.universal.patch.manager.util.Options
import app.universal.patch.manager.util.PM
import app.universal.patch.manager.util.savedAppBasePackage
import app.universal.patch.manager.util.PatchSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

// Code adapted from Morphe, see third-party/NOTICE for more information
// https://github.com/MorpheApp/morphe-manager/pull/795
class BatchPlanResolver(
    private val patchBundleRepository: PatchBundleRepository,
    private val installedAppRepository: InstalledAppRepository,
    private val patchSelectionRepository: PatchSelectionRepository,
    private val patchOptionsRepository: PatchOptionsRepository,
    private val patchProfileRepository: PatchProfileRepository,
    private val installerManager: InstallerManager,
    private val rootInstaller: RootInstaller,
    private val prefs: PreferencesManager,
    private val fs: Filesystem,
    private val pm: PM
) {
    suspend fun resolve(
        packageNames: List<String>,
        forcedUseMount: Boolean? = null
    ): List<BatchPatchItem> = coroutineScope {
        packageNames.distinct().map { packageName ->
            async { resolve(packageName, forcedUseMount = forcedUseMount) }
        }.awaitAll()
    }

    suspend fun resolveManual(
        entries: List<ManualBatchPatchEntry>
    ): List<BatchPatchItem> = coroutineScope {
        entries.distinctBy { it.input.packageName }.map { entry ->
            async {
                val resolved = resolve(
                    targetIdentifier = entry.sourceEntryKey ?: entry.input.packageName,
                    attachedInput = entry.input,
                    forcedUseMount = entry.useMount,
                )
                val (sanitizedSelection, sanitizedOptions) = sanitizeBatchConfiguration(
                    selection = entry.selection,
                    options = entry.options,
                    bundles = resolved.bundles
                )
                val selection = applyBatchAvailability(sanitizedSelection, resolved)
                val options = optionsForSelection(sanitizedOptions, selection)
                val patcherEngine = resolvePatcherEngine(selection)
                val state = resolveManualBatchItemState(
                    resolvedState = resolved.state,
                    hasInput = resolved.input != null,
                    hasBundles = resolved.bundles.isNotEmpty(),
                    hasSelection = selection.isNotEmpty()
                )
                resolved.copy(
                    input = entry.input,
                    version = entry.input.version,
                    versionCode = entry.input.versionCode,
                    selection = selection,
                    options = options,
                    selectionPayload = null,
                    signatureWorkflow = entry.signatureWorkflow ?: resolved.signatureWorkflow,
                    sourceEntryKey = entry.sourceEntryKey ?: resolved.sourceEntryKey,
                    patcherEngine = patcherEngine,
                    state = state,
                    message = null
                )
            }
        }.awaitAll()
    }

    suspend fun resolve(
        targetIdentifier: String,
        attachedFile: File? = null,
        attachedInput: SelectedApp? = null,
        forcedUseMount: Boolean? = null
    ): BatchPatchItem = withContext(Dispatchers.IO) {
        patchBundleRepository.awaitReady()
        val installedApps = installedAppRepository.getAll().first()
        val exactRecord = installedApps.firstOrNull { app ->
            app.currentPackageName == targetIdentifier
        }
        val resolvedPackageName = resolveBatchPackageName(
            exactRecord = exactRecord,
            targetIdentifier = targetIdentifier
        )
        val installedRecord = exactRecord
            ?: preferredBatchRecord(installedApps, resolvedPackageName)
        val sourceEntryKey = installedRecord?.currentPackageName
        val installedInfo = pm.getPackageInfo(resolvedPackageName)
        val trackedInstalledPackageName = installedRecord
            ?.takeIf { it.installType != InstallType.SAVED }
            ?.currentPackageName
            ?.takeIf(String::isNotBlank)
            ?: resolvedPackageName
        val trackedInstalledInfo = if (trackedInstalledPackageName == resolvedPackageName) {
            installedInfo
        } else {
            pm.getPackageInfo(trackedInstalledPackageName)
        }
        val deviceInstalledRecord = trackedInstalledInfo?.let { packageInfo ->
            installedAppRepository.getCurrentInstalledRecord(
                packageName = trackedInstalledPackageName,
                installedVersion = packageInfo.versionName,
                installedLastUpdateTime = packageInfo.lastUpdateTime,
                installedApk = packageInfo.applicationInfo?.sourceDir?.let(::File)
            )
        }
        val targetVersion = resolveBatchTargetVersion(
            selectedRecord = installedRecord,
            currentInstalledRecord = deviceInstalledRecord,
            installedVersion = trackedInstalledInfo?.versionName
        )
        val selectedSavedInfo = installedRecord
            ?.takeIf { it.installType == InstallType.SAVED }
            ?.let { record ->
                fs.getPatchedAppFile(
                    record.currentPackageName,
                    record.version
                ).takeIf(File::isFile)
                    ?.let(pm::getPackageInfo)
            }
        val targetVersionCode = when {
            targetVersion.isNullOrBlank() -> null
            selectedSavedInfo?.versionName?.equals(targetVersion, ignoreCase = true) == true ->
                PackageInfoCompat.getLongVersionCode(selectedSavedInfo)
            installedRecord?.installType != InstallType.SAVED &&
                trackedInstalledInfo?.versionName?.equals(targetVersion, ignoreCase = true) == true ->
                PackageInfoCompat.getLongVersionCode(trackedInstalledInfo)
            else -> null
        }
        val rememberedSourceFile = installedRecord
            ?.repatchSourcePath
            ?.takeIf(String::isNotBlank)
            ?.let { SharedApkFiles.resolve(File(it)) }
            ?.takeIf(File::isFile)
        val rememberedSourceInfo = rememberedSourceFile?.let { file ->
            if (SplitApkPreparer.isSplitArchive(file)) {
                runCatching {
                    val extracted = SplitApkInspector.extractRepresentativeApk(
                        source = file,
                        workspace = fs.tempDir
                    )
                    try {
                        extracted?.file?.let(pm::getPackageInfo)
                    } finally {
                        extracted?.cleanup?.invoke()
                    }
                }.getOrNull()
            } else {
                pm.getPackageInfo(file)
            }
        }
        val validRememberedSource = rememberedSourceFile?.takeIf {
            val sourceInfo = rememberedSourceInfo ?: return@takeIf false
            val sourceVersionCode = PackageInfoCompat.getLongVersionCode(sourceInfo)
            sourceInfo.packageName == resolvedPackageName &&
                if (
                    installedRecord.installType == InstallType.SAVED ||
                    deviceInstalledRecord?.currentPackageName == installedRecord.currentPackageName
                ) {
                    true
                } else {
                    (targetVersion == null ||
                        sourceInfo.versionName.equals(targetVersion, ignoreCase = true)) &&
                        (targetVersionCode == null || sourceVersionCode == targetVersionCode)
                }
        }
        val profiles = patchProfileRepository.profilesForPackageFlow(resolvedPackageName)
            .first()
            .filter { it.apkPath?.let(::File)?.isFile == true }
        val profile = profiles.firstOrNull { candidate ->
            targetVersion == null ||
                candidate.apkVersion?.takeIf(String::isNotBlank)
                    ?.equals(targetVersion, ignoreCase = true) == true ||
                candidate.appVersion?.takeIf(String::isNotBlank)
                    ?.equals(targetVersion, ignoreCase = true) == true
        }
        val globalSignatureWorkflow = prefs.injectSignatureMetadataAfterPatching.get()
        val signatureWorkflow = installedRecord?.selectionPayload?.signatureWorkflow
            ?.takeIf { it.remembered }
            ?: PatchProfilePayload.SignatureWorkflow(
                enabled = profile?.payload?.signatureWorkflow?.enabled ?: globalSignatureWorkflow,
                remembered = globalSignatureWorkflow &&
                    (profile?.payload?.signatureWorkflow?.enabled != false)
            )
        val retainedOriginalFile = when {
            targetVersion != null && targetVersionCode != null ->
                fs.findOriginalAppFile(
                    packageName = resolvedPackageName,
                    version = targetVersion,
                    versionCode = targetVersionCode
                )
            targetVersion == null && installedRecord == null ->
                fs.findOriginalAppFile(resolvedPackageName)
            else -> null
        }
        val retainedOriginalInfo = retainedOriginalFile?.let { file ->
            if (SplitApkPreparer.isSplitArchive(file)) {
                runCatching {
                    val extracted = SplitApkInspector.extractRepresentativeApk(
                        source = file,
                        workspace = fs.tempDir
                    )
                    try {
                        extracted?.file?.let(pm::getPackageInfo)
                    } finally {
                        extracted?.cleanup?.invoke()
                    }
                }.getOrNull()
            } else {
                pm.getPackageInfo(file)
            }
        }
        val validRetainedOriginal = retainedOriginalFile?.takeIf {
            retainedOriginalInfo?.packageName == resolvedPackageName &&
                (
                    targetVersion == null ||
                        retainedOriginalInfo.versionName.equals(targetVersion, ignoreCase = true)
                ) &&
                (
                    targetVersionCode == null ||
                        PackageInfoCompat.getLongVersionCode(retainedOriginalInfo) == targetVersionCode
                )
        }

        val input = when {
            attachedInput != null -> {
                if (attachedInput.packageName != resolvedPackageName) {
                    return@withContext blocked(
                        packageName = resolvedPackageName,
                        appName = appName(installedInfo, resolvedPackageName),
                        state = BatchItemState.NEEDS_APK,
                        message = attachedInput.packageName,
                        sourceEntryKey = sourceEntryKey,
                        signatureWorkflow = signatureWorkflow
                    )
                }
                attachedInput
            }
            attachedFile != null -> {
                val info = pm.getPackageInfo(attachedFile)
                if (info == null || info.packageName != resolvedPackageName) {
                    return@withContext blocked(
                        packageName = resolvedPackageName,
                        appName = appName(installedInfo, resolvedPackageName),
                        state = BatchItemState.NEEDS_APK,
                        message = info?.packageName,
                        sourceEntryKey = sourceEntryKey,
                        signatureWorkflow = signatureWorkflow
                    )
                }
                SelectedApp.Local(
                    packageName = resolvedPackageName,
                    version = info.versionName.orEmpty(),
                    versionCode = PackageInfoCompat.getLongVersionCode(info),
                    file = attachedFile,
                    temporary = false
                )
            }
            validRememberedSource != null -> SelectedApp.Local(
                packageName = resolvedPackageName,
                version = rememberedSourceInfo?.versionName ?: targetVersion.orEmpty(),
                versionCode = rememberedSourceInfo?.let {
                    PackageInfoCompat.getLongVersionCode(it)
                },
                file = validRememberedSource,
                temporary = false
            )
            validRetainedOriginal != null -> SelectedApp.Local(
                packageName = resolvedPackageName,
                version = retainedOriginalInfo?.versionName ?: targetVersion.orEmpty(),
                versionCode = retainedOriginalInfo?.let {
                    PackageInfoCompat.getLongVersionCode(it)
                },
                file = validRetainedOriginal,
                temporary = false
            )
            profile != null -> {
                val profileFile = File(requireNotNull(profile.apkPath))
                val profileInfo = pm.getPackageInfo(profileFile)
                val profileVersionCode = profileInfo?.let(PackageInfoCompat::getLongVersionCode)
                if (
                    profileInfo == null ||
                    profileInfo.packageName != resolvedPackageName ||
                    !automaticBatchSourceMatchesTarget(
                        selectedRecord = installedRecord,
                        targetVersion = targetVersion,
                        targetVersionCode = targetVersionCode,
                        sourceVersion = profileInfo.versionName,
                        sourceVersionCode = profileVersionCode
                    )
                ) {
                    null
                } else {
                    SelectedApp.Local(
                        packageName = resolvedPackageName,
                        version = profileInfo.versionName.orEmpty(),
                        versionCode = profileVersionCode,
                        file = profileFile,
                        temporary = false
                    )
                }
            }
            installedInfo != null &&
                deviceInstalledRecord == null &&
                trackedInstalledPackageName == resolvedPackageName &&
                automaticBatchSourceMatchesTarget(
                    selectedRecord = installedRecord,
                    targetVersion = targetVersion,
                    targetVersionCode = targetVersionCode,
                    sourceVersion = installedInfo.versionName,
                    sourceVersionCode = PackageInfoCompat.getLongVersionCode(installedInfo)
                ) -> SelectedApp.Installed(
                    packageName = resolvedPackageName,
                    version = installedInfo.versionName.orEmpty(),
                    versionCode = PackageInfoCompat.getLongVersionCode(installedInfo)
                )
            else -> null
        }

        if (input == null) {
            return@withContext blocked(
                packageName = resolvedPackageName,
                appName = appName(installedInfo, resolvedPackageName),
                state = BatchItemState.NEEDS_APK,
                sourceEntryKey = sourceEntryKey,
                signatureWorkflow = signatureWorkflow
            )
        }

        val bundles = patchBundleRepository.scopedBundleInfoFlow(
            resolvedPackageName,
            input.version,
            input.versionCode
        ).first().filter { it.enabled && it.patches.isNotEmpty() }

        if (bundles.isEmpty()) {
            return@withContext blocked(
                packageName = resolvedPackageName,
                appName = appName(installedInfo, resolvedPackageName),
                state = BatchItemState.NO_PATCHES,
                input = input,
                sourceEntryKey = sourceEntryKey,
                signatureWorkflow = signatureWorkflow
            )
        }

        val allowIncompatible = prefs.disablePatchVersionCompatCheck.get()
        val hasCompatible = bundles.any {
            it.compatible.isNotEmpty() || it.universal.isNotEmpty()
        }
        val hasIncompatible = bundles.any { it.incompatible.isNotEmpty() }
        val mismatch = !allowIncompatible && !hasCompatible && hasIncompatible
        val selectableByBundle = bundles.associate { bundle ->
            bundle.uid to bundle.patchSequence(allowIncompatible || mismatch)
                .mapTo(mutableSetOf()) { it.name }
        }
        val bundleMap = bundles.associateBy { it.uid }
        val sources = patchBundleRepository.sources.first()
        val sourceMap = sources.associateBy { it.uid }
        val profileConfiguration = profile?.toConfiguration(bundleMap, sourceMap)
        val installedPayloadConfiguration = installedRecord?.selectionPayload
            ?.toConfiguration(bundleMap, sourceMap)
        val installedSelection = installedRecord?.let {
            installedAppRepository.getAppliedPatches(it.currentPackageName)
        }.orEmpty()
        val persistedSelection = patchSelectionRepository.getSelection(resolvedPackageName)

        val configurationCandidates = sequenceOf<Triple<PatchSelection, Options?, String?>?>(
            installedPayloadConfiguration?.let {
                Triple(it.selection, it.options, null)
            },
            installedSelection.takeIf { it.isNotEmpty() }?.let {
                Triple(it, null, null)
            },
            profileConfiguration?.let {
                Triple(it.selection, it.options, profile.installerToken)
            },
            persistedSelection.takeIf { it.isNotEmpty() }?.let {
                Triple(it, null, null)
            }
        ).filterNotNull()
        val validSavedConfiguration = configurationCandidates.mapNotNull {
            (candidate, candidateOptions, candidateInstallerToken) ->
            candidate.mapNotNull { (uid, patches) ->
                val valid = patches intersect selectableByBundle[uid].orEmpty()
                uid.takeIf { valid.isNotEmpty() }?.let { it to valid }
            }.toMap().takeIf { it.isNotEmpty() }?.let {
                Triple(it, candidateOptions, candidateInstallerToken)
            }
        }.firstOrNull()
        val savedProfileInstallerToken = validSavedConfiguration?.third
        val chooseInstallerPerInstall = prefs.chooseInstallerPerInstall.get()
        val mountRequested = mountRequestedFor(
            forcedUseMount = forcedUseMount,
            rememberedUseMount = installedRecord?.useMount,
            installerToken = savedProfileInstallerToken,
            chooseInstallerPerInstall = chooseInstallerPerInstall,
        )
        val useMount = mountRequested && rootInstaller.hasRootAccess()
        val profileInstallerToken = savedProfileInstallerToken?.takeIf { token ->
            shouldApplyProfileInstallerPreference(
                chooseInstallerPerInstall = chooseInstallerPerInstall,
                installerMatchesPatchMode = installerTokenMatchesPatchMode(
                    installerManager.parseToken(token),
                    useMount
                )
            )
        }
        val installerType = installerTypeFor(useMount)
        val patchesByBundle = bundles.associate { bundle ->
            bundle.uid to bundle.patchSequence(allowIncompatible || mismatch)
                .associateBy(PatchInfo::name)
        }
        val availabilityEnabled = prefs.patchAvailabilityEnabled.get()
        val removeGmsCore = useMount &&
            installerManager.baseInstallerToken(installerManager.getPrimaryToken()) ==
                InstallerManager.Token.AutoSaved &&
            prefs.removeGmsCoreForPrimaryMount.get()
        val selection = (validSavedConfiguration?.first
            ?: bundles.associate { bundle ->
                bundle.uid to bundle.patchSequence(allowIncompatible || mismatch)
                    .filter { it.defaultSelected(installerType, availabilityEnabled) }
                    .mapTo(mutableSetOf()) { it.name }
            }.filterValues { it.isNotEmpty() })
            .applyAvailability(installerType, patchesByBundle, availabilityEnabled)
            .removeGmsCoreSupport(removeGmsCore)
        val savedOptions = validSavedConfiguration?.second
            ?: patchOptionsRepository.getOptions(
                resolvedPackageName,
                bundles.associate { bundle ->
                    bundle.uid to bundle.patches.associateBy { it.name }
                }
            )
        val options = savedOptions.mapNotNull { (uid, patchOptions) ->
            val selectedPatches = selection[uid].orEmpty()
            val selectedOptions = patchOptions.filterKeys(selectedPatches::contains)
            uid.takeIf { selectedOptions.isNotEmpty() }?.let { it to selectedOptions }
        }.toMap()

        val refs = bundles.map { bundle ->
            BatchBundleRef(
                uid = bundle.uid,
                name = bundle.name,
                version = bundle.version,
                patchNames = selectableByBundle[bundle.uid].orEmpty()
            )
        }
        val patcherEngine = resolvePatcherEngine(selection)
        if (!mismatch && selection.values.sumOf { it.size } == 0) {
            return@withContext blocked(
                packageName = resolvedPackageName,
                appName = appName(installedInfo, resolvedPackageName),
                state = BatchItemState.NO_PATCHES,
                input = input,
                bundles = refs,
                sourceEntryKey = sourceEntryKey,
                signatureWorkflow = signatureWorkflow
            )
        }

        BatchPatchItem(
            packageName = resolvedPackageName,
            appName = appName(installedInfo, resolvedPackageName),
            version = input.version,
            versionCode = input.versionCode,
            input = input,
            selection = selection,
            options = options,
            bundles = refs,
            patcherEngine = patcherEngine,
            state = if (mismatch) BatchItemState.VERSION_MISMATCH else BatchItemState.READY,
            sourceEntryKey = sourceEntryKey,
            profileInstallerToken = profileInstallerToken,
            signatureWorkflow = signatureWorkflow,
            useMount = useMount,
        )
    }

    internal suspend fun resolvePatcherEngine(selection: PatchSelection): String? {
        val selectedBundleType = patchBundleRepository.selectionBundleType(selection)
        if (
            selectedBundleType == PatchBundleType.REVANCED &&
            patchBundleRepository.selectionHasMixedRevancedPatcherVersions(selection)
        ) return null
        val usesRevancedPatcher22 = selectedBundleType == PatchBundleType.REVANCED &&
            patchBundleRepository.selectionUsesRevancedPatcher22(selection)
        return patcherEngineDisplayName(selectedBundleType, usesRevancedPatcher22)
    }

    suspend fun findOutdatedPackages(
        onlyAutoPatchEnabled: Boolean = false
    ): List<String> = withContext(Dispatchers.IO) {
        patchBundleRepository.awaitReady()
        val enabledBundleInfo = patchBundleRepository.enabledBundlesInfoFlow.first()
        val currentVersions = enabledBundleInfo.mapValues { it.value.version }
        val bundleSources = patchBundleRepository.sources.first()
        val bundleSignatures = patchBundleRepository.allBundlesInfoFlow.first().toSignatureMap()
        val installedApps = installedAppRepository.getAll().first()
        val targets = if (onlyAutoPatchEnabled) {
            val enabledTargets = prefs.autoPatchEnabledPackages.get()
            resolveAutoPatchRecords(installedApps, enabledTargets).also { resolved ->
                val normalizedTargets = resolved.mapTo(mutableSetOf()) {
                    it.currentPackageName
                }
                if (normalizedTargets != enabledTargets) {
                    prefs.autoPatchEnabledPackages.update(normalizedTargets)
                }
            }
        } else {
            installedApps
                .groupBy(::batchOriginalPackageName)
                .mapNotNull { (packageName, variants) ->
                    preferredBatchRecord(variants, packageName)
                }
        }

        targets.mapNotNull { target ->
            val storedSelection = installedAppRepository.getAppliedPatches(
                target.currentPackageName
            )
            val remappedPayload = target.selectionPayload?.remapLocalBundles(
                bundleSources,
                bundleSignatures
            )
            val outdated = isAutoPatchTargetOutdated(
                payload = remappedPayload,
                storedSelection = storedSelection,
                currentVersions = currentVersions
            )
            if (!outdated) return@mapNotNull null
            if (onlyAutoPatchEnabled) {
                target.currentPackageName
            } else {
                batchOriginalPackageName(target)
            }
        }
    }

    suspend fun reattach(
        item: BatchPatchItem,
        file: File
    ): BatchPatchItem =
        preserveReattachedConfiguration(
            item,
            resolve(
                item.sourceEntryKey ?: item.packageName,
                attachedFile = file,
                forcedUseMount = item.useMount
            )
        )

    suspend fun reattach(item: BatchPatchItem, input: SelectedApp): BatchPatchItem =
        preserveReattachedConfiguration(
            item,
            resolve(
                item.sourceEntryKey ?: item.packageName,
                attachedInput = input,
                forcedUseMount = item.useMount
            )
        )

    private suspend fun preserveReattachedConfiguration(
        item: BatchPatchItem,
        resolved: BatchPatchItem
    ): BatchPatchItem {
        val (preservedSelection, preservedOptions) = sanitizeBatchConfiguration(
            selection = item.selection,
            options = item.options,
            bundles = resolved.bundles
        )
        val usePreservedConfiguration = preservedSelection.isNotEmpty()
        val selection = applyBatchAvailability(if (usePreservedConfiguration) {
            preservedSelection
        } else {
            resolved.selection
        }, resolved)
        val candidateOptions = if (usePreservedConfiguration) {
            preservedOptions
        } else {
            resolved.options
        }
        val options = optionsForSelection(candidateOptions, selection)
        val patcherEngine = resolvePatcherEngine(selection)
        val forcedMismatch = item.forceVersionMismatch &&
            resolved.state == BatchItemState.VERSION_MISMATCH
        val state = when {
            resolved.input == null -> resolved.state
            resolved.bundles.isEmpty() -> BatchItemState.NO_PATCHES
            selection.isEmpty() -> BatchItemState.NO_PATCHES
            resolved.state == BatchItemState.VERSION_MISMATCH && !forcedMismatch ->
                BatchItemState.VERSION_MISMATCH
            else -> BatchItemState.READY
        }
        return resolved.copy(
            selection = selection,
            options = options,
            selectionPayload = null,
            patcherEngine = patcherEngine,
            state = state,
            message = null,
            forceVersionMismatch = forcedMismatch,
            signatureWorkflow = item.signatureWorkflow
        )
    }

    // Code adapted from Morphe, see third-party/NOTICE for more information.
    // https://github.com/MorpheApp/morphe-manager/pull/747
    private fun mountRequestedFor(
        forcedUseMount: Boolean?,
        rememberedUseMount: Boolean?,
        installerToken: String?,
        chooseInstallerPerInstall: Boolean,
    ): Boolean {
        forcedUseMount?.let { return it }
        rememberedUseMount?.let { return it }
        if (chooseInstallerPerInstall) return false
        installerToken?.let {
            return installerManager.baseInstallerToken(installerManager.parseToken(it)) ==
                InstallerManager.Token.AutoSaved
        }
        return installerManager.baseInstallerToken(installerManager.getPrimaryToken()) ==
            InstallerManager.Token.AutoSaved
    }

    private suspend fun applyBatchAvailability(
        selection: PatchSelection,
        item: BatchPatchItem,
    ): PatchSelection {
        val input = item.input ?: return selection
        val bundles = patchBundleRepository.scopedBundleInfoFlow(
            item.packageName,
            input.version,
            input.versionCode
        ).first()
        val selectableByBundle = item.bundles.associate { it.uid to it.patchNames }
        val availabilityEnabled = prefs.patchAvailabilityEnabled.get()
        val removeGmsCore = item.useMount &&
            installerManager.baseInstallerToken(installerManager.getPrimaryToken()) ==
                InstallerManager.Token.AutoSaved &&
            prefs.removeGmsCoreForPrimaryMount.get()
        return selection.applyAvailability(
            installerTypeFor(item.useMount),
            bundles.associate { bundle ->
                val selectable = selectableByBundle[bundle.uid].orEmpty()
                bundle.uid to bundle.patches
                    .filter { it.name in selectable }
                    .associateBy(PatchInfo::name)
            },
            availabilityEnabled
        ).removeGmsCoreSupport(removeGmsCore)
    }

    private fun optionsForSelection(
        options: Options,
        selection: PatchSelection,
    ): Options = options.mapNotNull { (bundleUid, patchOptions) ->
        val selected = selection[bundleUid].orEmpty()
        patchOptions.filterKeys(selected::contains)
            .takeIf { it.isNotEmpty() }
            ?.let { bundleUid to it }
    }.toMap()

    private fun appName(
        info: android.content.pm.PackageInfo?,
        packageName: String
    ): String = info?.let { with(pm) { it.label() } }?.takeIf(String::isNotBlank)
        ?: packageName

    private fun blocked(
        packageName: String,
        appName: String,
        state: BatchItemState,
        message: String? = null,
        input: SelectedApp? = null,
        bundles: List<BatchBundleRef> = emptyList(),
        sourceEntryKey: String? = null,
        signatureWorkflow: PatchProfilePayload.SignatureWorkflow
    ) = BatchPatchItem(
        packageName = packageName,
        appName = appName,
        version = input?.version,
        versionCode = input?.versionCode,
        input = input,
        selection = emptyMap(),
        options = emptyMap(),
        bundles = bundles,
        state = state,
        message = message,
        sourceEntryKey = sourceEntryKey,
        signatureWorkflow = signatureWorkflow
    )
}

internal fun resolveManualBatchItemState(
    resolvedState: BatchItemState,
    hasInput: Boolean,
    hasBundles: Boolean,
    hasSelection: Boolean
): BatchItemState = when {
    !hasInput -> resolvedState
    !hasBundles -> BatchItemState.NO_PATCHES
    !hasSelection -> BatchItemState.NO_PATCHES
    resolvedState == BatchItemState.VERSION_MISMATCH -> BatchItemState.VERSION_MISMATCH
    else -> BatchItemState.READY
}

internal fun sanitizeBatchConfiguration(
    selection: PatchSelection,
    options: Options,
    bundles: List<BatchBundleRef>
): Pair<PatchSelection, Options> {
    val availablePatches = bundles.associate { bundle ->
        bundle.uid to bundle.patchNames
    }
    val sanitizedSelection = selection.mapNotNull { (uid, patches) ->
        val valid = patches intersect availablePatches[uid].orEmpty()
        uid.takeIf { valid.isNotEmpty() }?.let { it to valid }
    }.toMap()
    val sanitizedOptions = options.mapNotNull { (uid, patchOptions) ->
        val selected = sanitizedSelection[uid].orEmpty()
        val valid = patchOptions.filterKeys(selected::contains)
        uid.takeIf { valid.isNotEmpty() }?.let { it to valid }
    }.toMap()
    return sanitizedSelection to sanitizedOptions
}

internal fun isUserBatchSelectionAllowed(count: Int): Boolean = count >= 2

internal fun isAutoPatchTargetOutdated(
    payload: PatchProfilePayload?,
    storedSelection: PatchSelection,
    currentVersions: Map<Int, String?>
): Boolean {
    val trackedBundles = payload?.bundles.orEmpty()
    val trackedUids = trackedBundles.mapTo(mutableSetOf()) { it.bundleUid }
    if (trackedBundles.any { bundle ->
            val currentVersion = currentVersions[bundle.bundleUid] ?: return@any false
            bundle.version == null || bundle.version != currentVersion
        }
    ) {
        return true
    }
    return storedSelection.keys.any { bundleUid ->
        bundleUid !in trackedUids && currentVersions[bundleUid] != null
    }
}

internal fun resolveAutoPatchRecords(
    records: List<InstalledApp>,
    enabledTargets: Set<String>
): List<InstalledApp> {
    if (records.isEmpty() || enabledTargets.isEmpty()) return emptyList()
    val recordsByKey = records.associateBy(InstalledApp::currentPackageName)
    return enabledTargets
        .mapNotNull { target ->
            recordsByKey[target] ?: preferredBatchRecord(records, target)
        }
        .distinctBy(InstalledApp::currentPackageName)
        .groupBy(::batchOriginalPackageName)
        .toSortedMap()
        .values
        .mapNotNull { selectedVariants ->
            selectedVariants.maxWithOrNull(
                compareBy<InstalledApp> { it.createdAt }
                    .thenBy { it.currentPackageName }
            )
        }
}

internal fun batchOriginalPackageName(app: InstalledApp): String =
    app.originalPackageName.takeIf(String::isNotBlank)
        ?: savedAppBasePackage(app.currentPackageName)

internal fun hasBatchShortcutTarget(
    records: List<InstalledApp>,
    originalPackageName: String,
    hasSavedCopy: (InstalledApp) -> Boolean
): Boolean = records.any { record ->
    batchOriginalPackageName(record) == originalPackageName && hasSavedCopy(record)
}

internal fun selectedBatchTargetIdentifiers(
    records: List<InstalledApp>,
    selectedEntryKeys: Set<String>
): List<String> = records
    .asSequence()
    .filter { it.currentPackageName in selectedEntryKeys }
    .groupBy(::batchOriginalPackageName)
    .values
    .mapNotNull { selectedVariants ->
        selectedVariants.maxWithOrNull(
            compareBy<InstalledApp> { it.createdAt }
                .thenBy { it.currentPackageName }
        )
    }
    .map(InstalledApp::currentPackageName)

internal fun resolveBatchPackageName(
    exactRecord: InstalledApp?,
    targetIdentifier: String
): String = exactRecord?.let(::batchOriginalPackageName) ?: targetIdentifier

internal fun resolveBatchTargetVersion(
    selectedRecord: InstalledApp?,
    currentInstalledRecord: InstalledApp?,
    installedVersion: String?
): String? = if (
    selectedRecord?.installType != InstallType.SAVED &&
    selectedRecord != null &&
    currentInstalledRecord == null &&
    !installedVersion.isNullOrBlank()
) {
    installedVersion
} else {
    selectedRecord?.version
}

internal fun automaticBatchSourceMatchesTarget(
    selectedRecord: InstalledApp?,
    targetVersion: String?,
    targetVersionCode: Long?,
    sourceVersion: String?,
    sourceVersionCode: Long?
): Boolean {
    if (selectedRecord?.installType != InstallType.SAVED) return true
    return targetVersionCode != null &&
        sourceVersion?.equals(targetVersion, ignoreCase = true) == true &&
        sourceVersionCode == targetVersionCode
}

internal fun preferredBatchRecord(
    records: List<InstalledApp>,
    packageName: String
): InstalledApp? = records
    .asSequence()
    .filter {
        it.currentPackageName == packageName ||
            batchOriginalPackageName(it) == packageName
    }
    .maxWithOrNull(
        compareBy<InstalledApp> { it.createdAt }
            .thenBy { it.currentPackageName }
    )
