package app.universal.patch.manager.ui.viewmodel

import android.app.Application
import android.content.pm.PackageInfo
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.universal.patch.manager.data.platform.Filesystem
import app.universal.patch.manager.data.platform.SharedApkFiles
import app.universal.patch.manager.domain.storage.CacheCleanupGuard
import app.universal.patch.manager.data.room.profile.PatchProfilePayload
import app.universal.patch.manager.data.room.options.Option.SerializedValue as StoredOptionSerializedValue
import app.universal.patch.manager.domain.bundles.PatchBundleSource
import app.universal.patch.manager.domain.bundles.PatchBundleSource.Extensions.asRemoteOrNull
import app.universal.patch.manager.domain.bundles.RemotePatchBundle
import app.universal.patch.manager.domain.repository.DuplicatePatchProfileNameException
import app.universal.patch.manager.domain.repository.PatchBundleRepository
import app.universal.patch.manager.domain.repository.PatchProfile
import app.universal.patch.manager.domain.repository.PatchProfileRepository
import app.universal.patch.manager.domain.repository.resolvePatchProfileAppVersion
import app.universal.patch.manager.domain.repository.remapLocalBundles
import app.universal.patch.manager.domain.repository.toConfiguration
import app.universal.patch.manager.patcher.split.SplitApkInspector
import app.universal.patch.manager.patcher.split.SplitApkPreparer
import app.universal.patch.manager.util.APK_FILE_EXTENSIONS
import app.universal.patch.manager.util.PM
import app.universal.patch.manager.util.mutableStateSetOf
import app.universal.patch.manager.util.tag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

data class PatchProfileListItem(
    val id: Int,
    val name: String,
    val packageName: String,
    val appVersion: String?,
    val apkPath: String?,
    val apkSourcePath: String?,
    val apkVersion: String?,
    val useSelectedApkVersion: Boolean,
    val autoPatch: Boolean,
    val installerToken: String?,
    val autoInstall: Boolean,
    val signatureWorkflow: PatchProfilePayload.SignatureWorkflow,
    val bundleCount: Int,
    val bundleNames: List<String>,
    val createdAt: Long,
    val bundleDetails: List<BundleDetail>
)

enum class BundleSourceType {
    Remote,
    Local
}

data class BundleDetail(
    val uid: Int,
    val displayName: String?,
    val patchCount: Int,
    val patches: List<String>,
    val options: Map<String, List<BundleOptionDisplay>>,
    val isAvailable: Boolean,
    val type: BundleSourceType
)

data class BundleOptionDisplay(
    val key: String,
    val label: String,
    val value: String,
    val displayValue: String
)

data class RemoteBundleOption(
    val uid: Int,
    val displayName: String,
    val version: String?,
    val patchCount: Int,
    val patchNamesLowercase: Set<String>
)

private fun Any?.toDisplayString(): String = when (this) {
    null -> ""
    is Boolean, is Number -> toString()
    is String -> this
    is List<*> -> joinToString(", ", prefix = "[", postfix = "]")
    else -> toString()
}

private fun Map<Int, Map<String, Map<String, Any?>>>.toStringMap(): Map<Int, Map<String, Map<String, String>>> =
    mapValues { (_, patchMap) ->
        patchMap.mapValues { (_, optionMap) ->
            optionMap.mapValues { (_, value) -> value.toDisplayString() }
        }
    }

private fun Map<String, Map<String, app.universal.patch.manager.data.room.options.Option.SerializedValue>>.toSerializedStringMap(): Map<String, Map<String, String>> =
    mapValues { (_, options) -> options.mapValues { (_, value) -> value.toJsonString() } }

private fun <T> Map<String, T>.getIgnoreCase(key: String): T? =
    this[key]
        ?: this[key.trim()]
        ?: entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value

private fun buildBundleOptionDisplays(
    optionValues: Map<String, String>,
    metadataOptions: List<app.universal.patch.manager.patcher.patch.Option<*>>,
    fallbackDisplayEntries: Map<String, PatchProfilePayload.OptionDisplayInfo>
): List<BundleOptionDisplay> {
    val metadataByKey = metadataOptions.associateBy { it.key }
    val orderedKeys = buildList {
        addAll(metadataOptions.map { it.key })
        addAll(optionValues.keys)
        addAll(fallbackDisplayEntries.keys)
    }.distinct()

    return orderedKeys.map { key ->
        val option = metadataByKey[key]
        val fallbackEntry = fallbackDisplayEntries[key]
        val serializedValue = optionValues[key]
        val parsedResult = if (serializedValue != null && option != null) {
            runCatching {
                StoredOptionSerializedValue.fromJsonString(serializedValue).deserializeFor(option)
            }
        } else {
            null
        }
        val parsedValue = parsedResult?.getOrNull()
        val effectiveValue = when {
            parsedResult?.isSuccess == true -> parsedValue
            serializedValue == null -> option?.default
            else -> null
        }
        val presetLabel = if (option != null && effectiveValue != null) {
            option.presets?.entries?.firstOrNull { (_, presetValue) -> presetValue == effectiveValue }?.key
        } else {
            null
        }
        val label = option?.title ?: fallbackEntry?.label ?: key
        val displayValue = presetLabel
            ?: effectiveValue?.toDisplayString()
            ?: fallbackEntry?.displayValue
            ?: serializedValue
            ?: ""
        BundleOptionDisplay(
            key = key,
            label = label,
            value = serializedValue ?: effectiveValue?.toDisplayString().orEmpty(),
            displayValue = displayValue
        )
    }
}

private fun PatchProfile.effectiveAppVersion(): String? =
    resolvePatchProfileAppVersion(appVersion, apkPath, apkVersion, useSelectedApkVersion)

class PatchProfilesViewModel(
    private val app: Application,
    private val pm: PM,
    private val filesystem: Filesystem,
    private val patchProfileRepository: PatchProfileRepository,
    private val patchBundleRepository: PatchBundleRepository
) : ViewModel() {
    enum class Event {
        DELETE_SELECTED,
        CANCEL
    }

    enum class ChangeUidResult {
        SUCCESS,
        PROFILE_OR_BUNDLE_NOT_FOUND,
        TARGET_NOT_FOUND
    }

    enum class RenameResult {
        SUCCESS,
        DUPLICATE_NAME,
        FAILED
    }

    enum class VersionUpdateResult {
        SUCCESS,
        PROFILE_NOT_FOUND,
        FAILED
    }

    enum class ReplaceRemoteBundleResult {
        SUCCESS,
        INCOMPATIBLE,
        PROFILE_OR_BUNDLE_NOT_FOUND,
        TARGET_NOT_FOUND,
        FAILED
    }

    enum class ApkSelectionResult {
        SUCCESS,
        CLEARED,
        PROFILE_NOT_FOUND,
        INVALID_FILE,
        PACKAGE_MISMATCH,
        FAILED
    }

    val selectedProfiles = mutableStateSetOf<Int>()
    private val splitWorkspace = filesystem.tempDir

    val profiles = combine(
        patchProfileRepository.profilesFlow(),
        patchBundleRepository.bundleInfoFlow,
        patchBundleRepository.sources
    ) { profiles, bundleInfoMap, sources ->
        val sourceMap = sources.associateBy { it.uid }
        val endpointToSource = sources.mapNotNull { source ->
            (source as? RemotePatchBundle)?.endpoint?.let { endpoint -> endpoint to source }
        }.toMap()
        val signatureMap = bundleInfoMap.mapValues { (_, info) ->
            info.patches.map { it.name.trim().lowercase() }.toSet()
        }
        val availableIds = profiles.map { it.uid }.toSet()
        selectedProfiles.retainAll(availableIds)
        profiles.map { profile ->
            val remappedPayload = profile.payload.remapLocalBundles(sources, signatureMap)
            val workingPayload = if (remappedPayload === profile.payload) {
                profile.payload
            } else {
                viewModelScope.launch(Dispatchers.Default) {
                    patchProfileRepository.updateProfile(
                        profile.uid,
                        profile.packageName,
                        profile.appVersion,
                        profile.name,
                        remappedPayload
                    )
                }
                remappedPayload
            }
            val workingProfile = profile.copy(payload = workingPayload)
            val effectiveAppVersion = workingProfile.effectiveAppVersion()
            val scopedBundles = bundleInfoMap.mapValues { (_, info) ->
                info.forPackage(profile.packageName, effectiveAppVersion)
            }
            val configuration = workingProfile.toConfiguration(scopedBundles, sourceMap)
            val optionsByBundle = configuration.options.toStringMap()
            val bundleNames = workingPayload.bundles.map { bundle ->
                val resolvedSource = sourceMap[bundle.bundleUid]
                    ?: bundle.sourceEndpoint?.let { endpointToSource[it] }
                resolvedSource?.displayTitle
                    ?: bundle.displayName
                    ?: bundle.sourceName
                    ?: bundle.bundleUid.toString()
            }
            var payloadNeedsDisplayUpdate = false
            val updatedBundles = workingPayload.bundles.toMutableList()
            val bundleDetails = workingPayload.bundles.mapIndexed { bundleIndex, bundle ->
                val resolvedSource = sourceMap[bundle.bundleUid]
                    ?: bundle.sourceEndpoint?.let { endpointToSource[it] }
                val resolvedName = resolvedSource?.displayTitle ?: bundle.displayName ?: bundle.sourceName
                val type = resolvedSource.determineType(bundle)
                val resolvedUid = resolvedSource?.uid ?: bundle.bundleUid
                val scopedInfo = scopedBundles[resolvedUid]
                val fallbackOptions = bundle.options.toSerializedStringMap()
                val resolvedOptions = optionsByBundle[resolvedUid]
                val fallbackDisplayInfo = bundle.optionDisplayInfo
                val patchMetadataForDisplay = scopedInfo
                val patchMetadataByName = patchMetadataForDisplay?.patches
                    ?.associateBy { it.name.trim().lowercase() }
                    ?: emptyMap()
                val optionPatchNames = buildSet {
                    addAll(
                        bundle.patches.filter { patchName ->
                            patchMetadataByName[patchName.trim().lowercase()]
                                ?.options
                                ?.isNotEmpty() == true
                        }
                    )
                    resolvedOptions?.keys?.let(::addAll)
                    addAll(fallbackOptions.keys)
                    fallbackDisplayInfo?.keys?.let(::addAll)
                }
                val optionDisplays = optionPatchNames.associateWith { patchName ->
                    val metadataOptions = patchMetadataByName[patchName.trim().lowercase()]
                        ?.options
                        ?.toList()
                        .orEmpty()
                    val optionValues = resolvedOptions?.getIgnoreCase(patchName)
                        ?.takeUnless { it.isEmpty() }
                        ?: fallbackOptions.getIgnoreCase(patchName).orEmpty()
                    val fallbackEntriesForPatch = fallbackDisplayInfo
                        ?.getIgnoreCase(patchName)
                        .orEmpty()
                    buildBundleOptionDisplays(
                        optionValues = optionValues,
                        metadataOptions = metadataOptions,
                        fallbackDisplayEntries = fallbackEntriesForPatch
                    )
                }
                val optionDisplayInfoMap = optionDisplays
                    .filterValues { it.isNotEmpty() }
                    .mapValues { (_, entries) ->
                        entries.associate { entry ->
                            entry.key to PatchProfilePayload.OptionDisplayInfo(entry.label, entry.displayValue)
                        }
                    }
                val normalizedDisplayInfo = optionDisplayInfoMap.takeIf { it.isNotEmpty() }
                if (bundle.optionDisplayInfo != normalizedDisplayInfo) {
                    payloadNeedsDisplayUpdate = true
                    updatedBundles[bundleIndex] = bundle.copy(optionDisplayInfo = normalizedDisplayInfo)
                }
                BundleDetail(
                    uid = bundle.bundleUid,
                    displayName = resolvedName,
                    patchCount = bundle.patches.size,
                    patches = bundle.patches,
                    options = optionDisplays,
                    isAvailable = resolvedSource != null,
                    type = type
                )
            }

            if (payloadNeedsDisplayUpdate) {
                val newPayload = workingProfile.payload.copy(bundles = updatedBundles)
                viewModelScope.launch(Dispatchers.Default) {
                    patchProfileRepository.updateProfile(
                        uid = profile.uid,
                        packageName = profile.packageName,
                        appVersion = profile.appVersion,
                        name = profile.name,
                        payload = newPayload
                    )
                }
            }

            PatchProfileListItem(
                id = profile.uid,
                name = profile.name,
                packageName = profile.packageName,
                appVersion = profile.appVersion,
                apkPath = profile.apkPath,
                apkSourcePath = profile.apkSourcePath,
                apkVersion = profile.apkVersion,
                useSelectedApkVersion = profile.useSelectedApkVersion,
                autoPatch = profile.autoPatch,
                installerToken = profile.installerToken,
                autoInstall = profile.autoInstall,
                signatureWorkflow = workingPayload.signatureWorkflow,
                bundleCount = workingPayload.bundles.size,
                bundleNames = bundleNames,
                createdAt = profile.createdAt,
                bundleDetails = bundleDetails
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val remoteBundleOptions = combine(
        patchBundleRepository.sources,
        patchBundleRepository.bundleInfoFlow
    ) { sources, bundleInfoMap ->
        val remoteSources = sources.filterIsInstance<RemotePatchBundle>().associateBy { it.uid }
        bundleInfoMap.mapNotNull { (uid, info) ->
            val source = remoteSources[uid] ?: return@mapNotNull null
            RemoteBundleOption(
                uid = uid,
                displayName = source.displayTitle,
                version = info.version,
                patchCount = info.patches.size,
                patchNamesLowercase = info.patches
                    .mapTo(mutableSetOf()) { it.name.trim().lowercase() }
            )
        }.sortedBy { it.displayName.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun resolveProfile(profileId: Int): PatchProfileLaunchData? {
        val profile = patchProfileRepository.getProfile(profileId) ?: return null
        val sourcesList = patchBundleRepository.sources.first()
        val bundleInfoSnapshot = patchBundleRepository.bundleInfoFlow.first()
        val signatureMap = bundleInfoSnapshot.mapValues { (_, info) ->
            info.patches.map { it.name.trim().lowercase() }.toSet()
        }
        val remappedPayload = profile.payload.remapLocalBundles(sourcesList, signatureMap)
        val workingProfile = if (remappedPayload === profile.payload) profile else profile.copy(payload = remappedPayload)
        val effectiveAppVersion = workingProfile.effectiveAppVersion()
        val scopedBundles = patchBundleRepository
            .scopedBundleInfoFlow(workingProfile.packageName, effectiveAppVersion)
            .first()
            .associateBy { it.uid }
        val sources = sourcesList.associateBy { it.uid }
        val configuration = workingProfile.toConfiguration(scopedBundles, sources)
        val availableBundles = workingProfile.payload.bundles.size - configuration.missingBundles.size
        val universalPatchNamesByUid = bundleInfoSnapshot.mapValues { (_, info) ->
            info.patches
                .asSequence()
                .filter { it.compatiblePackages == null }
                .mapTo(mutableSetOf()) { it.name.trim().lowercase() }
        }
        val containsUniversalPatches = workingProfile.payload.bundles.any { bundle ->
            val info = scopedBundles[bundle.bundleUid]
            val universalNames = universalPatchNamesByUid[bundle.bundleUid].orEmpty()
            bundle.patches.any { patchName ->
                val normalized = patchName.trim().lowercase()
                val matchesScoped = info?.patches?.any {
                    it.name.equals(patchName, true) && it.compatiblePackages == null
                } == true
                matchesScoped || universalNames.contains(normalized)
            }
        }
        return PatchProfileLaunchData(
            profile = workingProfile,
            missingBundles = configuration.missingBundles,
            changedBundles = configuration.changedBundles,
            availableBundleCount = availableBundles,
            containsUniversalPatches = containsUniversalPatches
        )
    }

    suspend fun deleteProfile(profileId: Int) {
        selectedProfiles.remove(profileId)
        val profile = patchProfileRepository.getProfile(profileId)
        profile?.apkPath?.let { File(it).delete() }
        patchProfileRepository.deleteProfile(profileId)
    }

    suspend fun renameProfile(profileId: Int, name: String): RenameResult {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return RenameResult.FAILED
        return withContext(Dispatchers.Default) {
            val profile = patchProfileRepository.getProfile(profileId)
                ?: return@withContext RenameResult.FAILED
            try {
                val updated = patchProfileRepository.updateProfile(
                    uid = profileId,
                    packageName = profile.packageName,
                    appVersion = profile.appVersion,
                    name = trimmed,
                    payload = profile.payload
                )
                if (updated != null) RenameResult.SUCCESS else RenameResult.FAILED
            } catch (duplicate: DuplicatePatchProfileNameException) {
                RenameResult.DUPLICATE_NAME
            } catch (t: Exception) {
                Log.e(tag, "Failed to rename patch profile", t)
                RenameResult.FAILED
            }
        }
    }

    suspend fun updateProfileVersion(
        profileId: Int,
        version: String?,
        useSelectedApkVersion: Boolean = false
    ): VersionUpdateResult =
        withContext(Dispatchers.Default) {
            val profile = patchProfileRepository.getProfile(profileId)
                ?: return@withContext VersionUpdateResult.PROFILE_NOT_FOUND
            val sanitized = version?.trim()?.takeUnless { it.isBlank() }
            return@withContext try {
                val updated = patchProfileRepository.updateProfile(
                    uid = profileId,
                    packageName = profile.packageName,
                    appVersion = sanitized,
                    name = profile.name,
                    payload = profile.payload,
                    useSelectedApkVersion = useSelectedApkVersion
                )
                if (updated != null) VersionUpdateResult.SUCCESS else VersionUpdateResult.FAILED
            } catch (t: Exception) {
                Log.e(tag, "Failed to update patch profile version", t)
                VersionUpdateResult.FAILED
            }
        }

    suspend fun updateProfileApk(profileId: Int, file: File?): ApkSelectionResult =
        withContext(Dispatchers.IO) {
            CacheCleanupGuard.withCacheInUse {
                val profile = patchProfileRepository.getProfile(profileId)
                    ?: return@withCacheInUse ApkSelectionResult.PROFILE_NOT_FOUND
                if (file == null) {
                    profile.apkPath?.let { File(it).delete() }
                    patchProfileRepository.updateProfileApk(
                        profileId,
                        null,
                        null,
                        null,
                        appVersion = if (profile.useSelectedApkVersion) null else profile.appVersion,
                        useSelectedApkVersion = false
                    )
                    return@withCacheInUse ApkSelectionResult.CLEARED
                }
                val source = SharedApkFiles.resolve(file)
                if (!source.isFile) return@withCacheInUse ApkSelectionResult.INVALID_FILE

                val extension = file.extension.lowercase(Locale.ROOT)
                    .takeIf { it in APK_FILE_EXTENSIONS }
                    ?: "apk"

                val destination = filesystem.getPatchProfileInputFile(profileId, extension)
                try {
                    destination.parentFile?.mkdirs()
                    Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } catch (error: Exception) {
                    Log.e(tag, "Failed to copy patch profile APK", error)
                    destination.delete()
                    return@withCacheInUse ApkSelectionResult.FAILED
                }

                val packageInfo = resolvePackageInfo(destination)
                if (packageInfo == null) {
                    destination.delete()
                    return@withCacheInUse ApkSelectionResult.INVALID_FILE
                }
                if (packageInfo.packageName != profile.packageName) {
                    destination.delete()
                    return@withCacheInUse ApkSelectionResult.PACKAGE_MISMATCH
                }

                val version = packageInfo.versionName?.takeIf { it.isNotBlank() } ?: profile.appVersion.orEmpty()
                val updated = patchProfileRepository.updateProfileApk(
                    profileId,
                    destination.absolutePath,
                    version,
                    file.absolutePath,
                    appVersion = profile.appVersion,
                    useSelectedApkVersion = true
                )
                if (updated == null) {
                    destination.delete()
                    return@withCacheInUse ApkSelectionResult.FAILED
                }
                profile.apkPath
                    ?.takeIf { it != destination.absolutePath }
                    ?.let { File(it).delete() }
                ApkSelectionResult.SUCCESS
            }
        }

    suspend fun updateProfileSignatureWorkflow(
        profileId: Int,
        workflow: PatchProfilePayload.SignatureWorkflow
    ): Boolean = withContext(Dispatchers.IO) {
        patchProfileRepository.updateProfileSignatureWorkflow(profileId, workflow) != null
    }

    suspend fun updateProfileAutoPatch(profileId: Int, enabled: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            patchProfileRepository.updateProfileAutoPatch(profileId, enabled) != null
        }

    suspend fun updateProfileInstaller(
        profileId: Int,
        installerToken: String?,
        autoInstall: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        patchProfileRepository.updateProfileInstaller(profileId, installerToken, autoInstall) != null
    }

    suspend fun changeLocalBundleUid(
        profileId: Int,
        currentUid: Int,
        newUid: Int
    ): ChangeUidResult = withContext(Dispatchers.Default) {
        val profile = patchProfileRepository.getProfile(profileId)
            ?: return@withContext ChangeUidResult.PROFILE_OR_BUNDLE_NOT_FOUND
        val sourcesList = patchBundleRepository.sources.first()
        val remappedPayload = profile.payload.remapLocalBundles(sourcesList)
        val bundles = remappedPayload.bundles.toMutableList()
        val bundleIndex = bundles.indexOfFirst { it.bundleUid == currentUid }
        if (bundleIndex == -1) return@withContext ChangeUidResult.PROFILE_OR_BUNDLE_NOT_FOUND

        val targetSource = sourcesList.firstOrNull { it.uid == newUid && it.asRemoteOrNull == null }
            ?: return@withContext ChangeUidResult.TARGET_NOT_FOUND

        val updatedBundle = bundles[bundleIndex].copy(
            bundleUid = targetSource.uid,
            displayName = targetSource.displayTitle,
            sourceName = targetSource.patchBundle?.manifestAttributes?.name ?: targetSource.name,
            sourceEndpoint = null
        )
        bundles[bundleIndex] = updatedBundle
        val updatedPayload = remappedPayload.copy(bundles = bundles.toList())

        patchProfileRepository.updateProfile(
            uid = profileId,
            packageName = profile.packageName,
            appVersion = profile.appVersion,
            name = profile.name,
            payload = updatedPayload
        )
        ChangeUidResult.SUCCESS
    }

    suspend fun replaceRemoteBundle(
        profileId: Int,
        currentUid: Int,
        targetUid: Int,
        requiredPatchesLowercase: Set<String>,
        allowIncompatible: Boolean = false
    ): ReplaceRemoteBundleResult = withContext(Dispatchers.Default) {
        val profile = patchProfileRepository.getProfile(profileId)
            ?: return@withContext ReplaceRemoteBundleResult.PROFILE_OR_BUNDLE_NOT_FOUND
        if (requiredPatchesLowercase.isEmpty() && !allowIncompatible) {
            return@withContext ReplaceRemoteBundleResult.INCOMPATIBLE
        }
        if (!allowIncompatible) {
            val bundleInfoSnapshot = patchBundleRepository.bundleInfoFlow.first()
            val targetInfo = bundleInfoSnapshot[targetUid]
                ?: return@withContext ReplaceRemoteBundleResult.INCOMPATIBLE
            val availablePatches = targetInfo.patches
                .mapTo(mutableSetOf()) { it.name.trim().lowercase() }
            if (!requiredPatchesLowercase.all { it in availablePatches }) {
                return@withContext ReplaceRemoteBundleResult.INCOMPATIBLE
            }
        }
        val sourcesList = patchBundleRepository.sources.first()
        val targetSource = sourcesList.firstOrNull { it.uid == targetUid }?.asRemoteOrNull
            ?: return@withContext ReplaceRemoteBundleResult.TARGET_NOT_FOUND
        val remappedPayload = profile.payload.remapLocalBundles(sourcesList)
        val bundles = remappedPayload.bundles.toMutableList()
        val bundleIndex = bundles.indexOfFirst { it.bundleUid == currentUid }
        if (bundleIndex == -1) {
            return@withContext ReplaceRemoteBundleResult.PROFILE_OR_BUNDLE_NOT_FOUND
        }

        val updatedBundle = bundles[bundleIndex].copy(
            bundleUid = targetSource.uid,
            displayName = targetSource.displayTitle,
            sourceName = targetSource.patchBundle?.manifestAttributes?.name ?: targetSource.name,
            sourceEndpoint = targetSource.endpoint
        )
        bundles[bundleIndex] = updatedBundle
        val updatedPayload = remappedPayload.copy(bundles = bundles.toList())

        val updated = patchProfileRepository.updateProfile(
            uid = profileId,
            packageName = profile.packageName,
            appVersion = profile.appVersion,
            name = profile.name,
            payload = updatedPayload
        )

        if (updated == null) {
            ReplaceRemoteBundleResult.FAILED
        } else {
            ReplaceRemoteBundleResult.SUCCESS
        }
    }

    fun toggleSelection(profileId: Int) {
        setSelection(profileId, profileId !in selectedProfiles)
    }

    fun handleEvent(event: Event) {
        when (event) {
            Event.CANCEL -> selectedProfiles.clear()
            Event.DELETE_SELECTED -> viewModelScope.launch(Dispatchers.Default) {
                val ids = selectedProfiles.toList()
                if (ids.isEmpty()) return@launch
                ids.forEach { id ->
                    patchProfileRepository.getProfile(id)?.apkPath?.let { path ->
                        File(path).delete()
                    }
                }
                patchProfileRepository.deleteProfiles(ids)
                selectedProfiles.clear()
            }
        }
    }

    fun setSelection(profileId: Int, shouldSelect: Boolean) {
        if (shouldSelect) {
            selectedProfiles.add(profileId)
        } else {
            selectedProfiles.remove(profileId)
        }
    }

    fun reorderProfiles(orderedUids: List<Int>) = viewModelScope.launch(Dispatchers.IO) {
        patchProfileRepository.reorderProfiles(orderedUids)
    }

    private suspend fun resolvePackageInfo(file: File): PackageInfo? =
        runCatching {
            if (SplitApkPreparer.isSplitArchive(file)) {
                val extracted = SplitApkInspector.extractRepresentativeApk(file, splitWorkspace)
                    ?: return null
                try {
                    pm.getPackageInfo(extracted.file)
                } finally {
                    extracted.cleanup()
                }
            } else {
                pm.getPackageInfo(file)
            }
        }.onFailure { error ->
            Log.e(tag, "Failed to resolve package info for patch profile APK", error)
        }.getOrNull()
}

private fun PatchBundleSource?.determineType(bundle: PatchProfilePayload.Bundle): BundleSourceType {
    return when {
        this?.asRemoteOrNull != null -> BundleSourceType.Remote
        this != null -> BundleSourceType.Local
        bundle.sourceEndpoint != null -> BundleSourceType.Remote
        else -> BundleSourceType.Local
    }
}

data class PatchProfileLaunchData(
    val profile: PatchProfile,
    val missingBundles: Set<Int>,
    val changedBundles: Set<Int>,
    val availableBundleCount: Int,
    val containsUniversalPatches: Boolean
)
