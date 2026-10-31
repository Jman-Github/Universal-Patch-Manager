package app.universal.patch.manager.ui.viewmodel

import app.universal.patch.manager.util.selectSuggestedPatchVersion

import app.universal.patch.manager.patcher.runtime.PatcherJvm
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Parcelable
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.SavedStateHandleSaveableApi
import androidx.lifecycle.viewmodel.compose.saveable
import app.universal.patch.manager.R
import app.universal.patch.manager.patcher.patch.mostCommonCompatibleVersions as revancedMostCommonCompatibleVersions
import app.universal.patch.manager.data.platform.Filesystem
import app.universal.patch.manager.data.room.apps.downloaded.DownloadedApp
import app.universal.patch.manager.data.room.apps.installed.InstallType
import app.universal.patch.manager.data.room.apps.installed.InstalledApp
import app.universal.patch.manager.data.room.profile.PatchProfilePayload
import app.universal.patch.manager.domain.installer.RootInstaller
import app.universal.patch.manager.domain.installer.InstallerManager
import app.universal.patch.manager.domain.installer.installerTokenMatchesPatchMode
import app.universal.patch.manager.domain.installer.shouldApplyProfileInstallerPreference
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.domain.repository.DownloadedAppRepository
import app.universal.patch.manager.domain.repository.DownloaderPluginRepository
import app.universal.patch.manager.domain.repository.InstalledAppRepository
import app.universal.patch.manager.domain.repository.PatchBundleRepository
import app.universal.patch.manager.domain.repository.PatchOptionsRepository
import app.universal.patch.manager.domain.repository.PatchOptionInputManager
import app.universal.patch.manager.domain.repository.PatchProfile
import app.universal.patch.manager.domain.repository.PatchProfileRepository
import app.universal.patch.manager.domain.repository.PatchSelectionRepository
import app.universal.patch.manager.domain.repository.resolvePatchProfileAppVersion
import app.universal.patch.manager.domain.repository.PatchOptionsRepository.ResetEvent as OptionsResetEvent
import app.universal.patch.manager.domain.repository.PatchSelectionRepository.ResetEvent as SelectionResetEvent
import app.universal.patch.manager.domain.repository.remapLocalBundles
import app.universal.patch.manager.domain.repository.toConfiguration
import app.universal.patch.manager.patcher.patch.PatchBundleInfo
import app.universal.patch.manager.patcher.patch.PatchBundleInfo.Extensions.toPatchSelection
import app.universal.patch.manager.patcher.patch.PatchBundleType
import app.universal.patch.manager.patcher.patch.PatchInfo
import app.universal.patch.manager.patcher.patch.applyAvailability
import app.universal.patch.manager.patcher.patch.installerTypeFor
import app.universal.patch.manager.patcher.patch.removeGmsCoreSupport
import app.universal.patch.manager.patcher.split.SplitArchiveDisplayResolver
import app.universal.patch.manager.patcher.split.SplitApkPreparer
import app.universal.patch.manager.network.downloader.ApkDownloadHelperContract
import app.universal.patch.manager.network.downloader.LoadedDownloaderPlugin
import app.universal.patch.manager.network.downloader.ParceledDownloaderData
import app.universal.patch.manager.patcher.patch.PatchBundleInfo.Extensions.requiredOptionsSet
import app.universal.patch.manager.plugin.downloader.DownloadUrl
import app.universal.patch.manager.plugin.downloader.GetScope
import app.universal.patch.manager.plugin.downloader.PluginHostApi
import app.universal.patch.manager.plugin.downloader.Package as DownloaderPackage
import app.universal.patch.manager.plugin.downloader.UserInteractionException
import app.universal.patch.manager.ui.model.SelectedApp
import app.universal.patch.manager.ui.model.SupportedVersionInfo
import app.universal.patch.manager.ui.model.navigation.Patcher
import app.universal.patch.manager.ui.model.navigation.SelectedApplicationInfo
import app.universal.patch.manager.util.Options
import app.universal.patch.manager.util.APK_FILE_EXTENSIONS
import app.universal.patch.manager.util.PM
import app.universal.patch.manager.util.PatchSelection
import app.universal.patch.manager.util.resolveSupportedApkExtension
import app.universal.patch.manager.util.simpleMessage
import app.universal.patch.manager.util.toast
import androidx.documentfile.provider.DocumentFile
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

@OptIn(SavedStateHandleSaveableApi::class, PluginHostApi::class, ExperimentalCoroutinesApi::class)
class SelectedAppInfoViewModel(
    input: SelectedApplicationInfo.ViewModelParams
) : ViewModel(), KoinComponent {
    private val app: Application = get()
    private val bundleRepository: PatchBundleRepository = get()
    private val selectionRepository: PatchSelectionRepository = get()
    private val optionsRepository: PatchOptionsRepository = get()
    private val patchOptionInputManager: PatchOptionInputManager = get()
    private val pluginsRepository: DownloaderPluginRepository = get()
    private val downloadedAppRepository: DownloadedAppRepository = get()
    private val patchProfileRepository: PatchProfileRepository = get()
    private val installedAppRepository: InstalledAppRepository = get()
    private val rootInstaller: RootInstaller = get()
    private val installerManager: InstallerManager = get()
    private val json: Json = get()
    private val pm: PM = get()
    private val filesystem: Filesystem = get()
    private val savedStateHandle: SavedStateHandle = get()
    val prefs: PreferencesManager = get()
    private val patchAvailabilityEnabled = prefs.patchAvailabilityEnabled.getBlocking()
    private val removeGmsCoreForPrimaryMountEnabled =
        prefs.removeGmsCoreForPrimaryMount.getBlocking()
    private var selectionLoadJob: Job? = null
    private var optionsLoadJob: Job? = null
    private var configurationUpdateJob: Job? = null
    // Recommendation mode scopes the active selection to one bundle, so retain each bundle's custom choices here.
    private val rememberedBundleSelections = mutableMapOf<Int, Set<String>>()
    val plugins = pluginsRepository.loadedPluginsFlow
    val apkDownloadHelpers = pluginsRepository.trustedApkDownloadHelpers
    val desiredVersion = input.app.version
    val packageName = input.app.packageName
    private val profileId = input.profileId
    private val sourceEntryKey = input.sourceEntryKey
    private val requiresSourceSelection = input.requiresSourceSelection
    private val inputSelectionPayload = input.selectionPayloadJson?.let { encoded ->
        runCatching { json.decodeFromString<PatchProfilePayload>(encoded) }
            .onFailure { error ->
                if (error !is SerializationException) return@onFailure
                Log.e(TAG, "Failed to decode selection payload", error)
            }
            .getOrNull()
    }
    val sourceSelectionRequired get() = requiresSourceSelection
    private val persistConfiguration = input.persistConfiguration
    private val storageInputDir = filesystem.uiTempDir
    private val splitWorkspace = filesystem.tempDir
    private var preparedApkCleanup: (() -> Unit)? = null
    private val temporaryLocalCleanupPaths = linkedSetOf<String>()
    private val storageSelectionChannel = Channel<Unit>(Channel.CONFLATED)
    val requestStorageSelection = storageSelectionChannel.receiveAsFlow()
    private val _profileLaunchState = MutableStateFlow<ProfileLaunchState?>(null)
    val profileLaunchState = _profileLaunchState.asStateFlow()
    private var autoLaunchProfilePatcher = profileId != null
    private var autoPatchProfile = false
    private val allowUniversalFlow = prefs.disableUniversalPatchCheck.flow
    private var allowUniversalPatches = true
    private var _selectedApp by savedStateHandle.saveable {
        mutableStateOf(input.app)
    }
    private val selectedAppState = MutableStateFlow(_selectedApp)
    private val bundleInfoFlowInternal =
        MutableStateFlow<List<PatchBundleInfo.Scoped>>(emptyList())
    val bundleInfoFlow = bundleInfoFlowInternal.asStateFlow()
    private val preferredBundleOverrideFlow =
        savedStateHandle.getStateFlow("preferred_bundle_override", null as String?)
    val suggestedVersionsByBundle = selectedAppState
        .flatMapLatest { selected ->
            combine(
                bundleRepository.suggestedVersionsByBundle,
                bundleInfoFlow
            ) { bundleVersions, bundles ->
                val relevantBundles = bundles.map { it.uid }.toSet()
                buildMap {
                    bundleVersions.forEach { (bundleUid, versions) ->
                        if (bundleUid in relevantBundles) {
                            put(bundleUid, versions[selected.packageName])
                        }
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    private val preferredBundleUidFlow =
        savedStateHandle.getStateFlow("preferred_bundle_uid", null as Int?)
    private val preferredBundleAllVersionsFlow =
        savedStateHandle.getStateFlow("preferred_bundle_all_versions", false)
    val selectedBundleUidFlow = preferredBundleUidFlow
    val selectedBundleVersionOverrideFlow = preferredBundleOverrideFlow
    val preferredBundleTargetsAllVersionsFlow = preferredBundleAllVersionsFlow
    private val selectedPatchNamesByBundleFlow = combine(
        bundleInfoFlow,
        snapshotFlow { selectionState to usingMountInstall },
        prefs.disablePatchVersionCompatCheck.flow
    ) { bundles, selection, allowIncompatible ->
        val (state, useMount) = selection
        state.patches(
            bundles,
            allowIncompatible,
            installerTypeFor(useMount),
            patchAvailabilityEnabled,
            shouldRemoveGmsCore(useMount)
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    val selectionRecommendedVersionFlow = combine(
        bundleInfoFlow,
        selectedPatchNamesByBundleFlow,
        selectedAppState.map { it.packageName }
    ) { scopedBundles, selectedPatchNames, activePackageName ->
        val selectedBundles = scopedBundles.filter { scoped ->
            selectedPatchNames[scoped.uid]?.isNotEmpty() == true
        }
        val bundleType = selectedBundles.firstOrNull()?.bundleType
        val patches = selectedBundles.flatMap { scoped ->
            val selected = selectedPatchNames[scoped.uid].orEmpty()
            scoped.patches.filter { it.name in selected }
        }
        when (bundleType) {
            PatchBundleType.REVANCED -> {
                val versionCounts = patches
                    .asSequence()
                    .map { it.toPatcherPatch() }
                    .toSet()
                    .revancedMostCommonCompatibleVersions(countUnusedPatches = true)[activePackageName]
                selectSuggestedPatchVersion(versionCounts.orEmpty())
            }
            PatchBundleType.MORPHE,
            PatchBundleType.RESEAM,
            PatchBundleType.AMPLE -> suggestedVersionForMorphe(patches, activePackageName)
            else -> null
        }
    }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val recommendedVersionsByBundleFlow = combine(
        bundleInfoFlow,
        selectedPatchNamesByBundleFlow,
        selectedAppState.map { it.packageName }
    ) { scopedBundles, selectedPatchNames, activePackageName ->
        scopedBundles.associate { scoped ->
            val selected = selectedPatchNames[scoped.uid]?.takeIf { it.isNotEmpty() }
            scoped.uid to scoped.recommendedVersionForSelection(activePackageName, selected)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    val preferredBundleVersionFlow = combine(
        preferredBundleUidFlow,
        preferredBundleOverrideFlow,
        recommendedVersionsByBundleFlow
    ) { uid, override, versions ->
        if (uid == null) null else override ?: versions[uid]
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val preferredBundleVersion get() = preferredBundleVersionFlow.value
    val effectiveDesiredVersion get() = preferredBundleVersion ?: selectionRecommendedVersionFlow.value ?: desiredVersion
    val bundleRecommendationDetailsFlow = combine(
        bundleInfoFlow,
        selectedPatchNamesByBundleFlow,
        selectedAppState.map { it.packageName },
        bundleRepository.sources
    ) { scopedBundles, selectedPatchNames, activePackageName, sources ->
        val displayNames = sources.associate { source ->
            source.uid to source.displayTitle
        }

        scopedBundles.mapNotNull { scoped ->
            val selected = selectedPatchNames[scoped.uid]?.takeIf { it.isNotEmpty() }
            val support = scoped.collectBundleSupport(activePackageName, selected)
            if (!support.hasSupport) return@mapNotNull null
            val recommended = scoped.recommendedVersionForSelection(activePackageName, selected)
            if (
                recommended == null &&
                support.versions.isEmpty() &&
                !support.supportsAllVersions
            ) return@mapNotNull null

            val recommendedExperimental = recommended != null &&
                recommended in support.experimentalVersions &&
                recommended !in support.stableVersions
            val otherVersions = support.versions
                .filterNot { recommended.equals(it, ignoreCase = true) }
                .sorted()
                .map { version ->
                    SupportedVersionInfo(
                        version = version,
                        experimental = version in support.experimentalVersions &&
                            version !in support.stableVersions,
                        versionCodes = support.versionCodes[version].orEmpty()
                    )
                }

            BundleRecommendationDetail(
                bundleUid = scoped.uid,
                name = displayNames[scoped.uid]
                    ?.takeIf { it.isNotBlank() }
                    ?: scoped.name,
                recommendedVersion = recommended,
                recommendedVersionCodes = recommended?.let(support.versionCodes::get).orEmpty(),
                recommendedVersionExperimental = recommendedExperimental,
                otherSupportedVersions = otherVersions,
                supportsAllVersions = support.supportsAllVersions
            )
        }.sortedBy { it.name.lowercase(Locale.ROOT) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val hasRoot = rootInstaller.hasRootAccess()
    var usingMountInstall by savedStateHandle.saveable {
        val configuredMount = !prefs.chooseInstallerPerInstall.getBlocking() &&
            installerManager.baseInstallerToken(installerManager.getPrimaryToken()) ==
                InstallerManager.Token.AutoSaved
        mutableStateOf(hasRoot && (input.useMount ?: configuredMount))
    }
        private set

    init {
        if (!hasRoot) usingMountInstall = false
        // Code adapted from Morphe, see third-party/NOTICE for more information.
        // https://github.com/MorpheApp/morphe-manager/pull/1212
        PatcherJvm.get(app).startAhead()
    }

    fun selectPatchMode(useMount: Boolean) {
        usingMountInstall = useMount && hasRoot
    }
    var installedAppData: Pair<SelectedApp.Installed, InstalledApp?>? by mutableStateOf(null)
        private set
    val downloadedApps =
        downloadedAppRepository.getAll().map { apps ->
            apps.filter { it.packageName == packageName }
                .sortedByDescending { it.lastUsed }
        }

    var selectedAppInfo: PackageInfo? by mutableStateOf(null)
        private set
    var selectedAppInfoLabelOverride: String? by mutableStateOf(null)
        private set
    var selectedAppInfoIconOverride: Drawable? by mutableStateOf(null)
        private set

    var selectedApp
        get() = _selectedApp
        set(value) {
            _selectedApp = value
            selectedAppState.value = value
            trackTemporaryLocalCleanup(value)
            invalidateSelectedAppInfo()
        }

    var options: Options by savedStateHandle.saveable {
        mutableStateOf<Options>(emptyMap())
    }
        private set
    private val pendingOptionInputOwnership = patchOptionInputManager.pendingInputOwnership(
        initiallyReferenced = patchOptionInputManager.pendingInputsIn(options)
    )

    private fun replaceOptions(value: Options) {
        options = value
        val currentPendingInputs = patchOptionInputManager.pendingInputsIn(value)
        pendingOptionInputOwnership.reconcile(currentPendingInputs)
    }

    data class RemovedPatchesNotice(val patchNames: List<String>)

    var removedPatchesNotice by mutableStateOf<RemovedPatchesNotice?>(null)
        private set

    fun dismissRemovedPatchesNotice() {
        removedPatchesNotice = null
    }

    private fun loadOptionsFromRepository() {
        if (optionsLoadJob?.isActive == true) return
        optionsLoadJob = viewModelScope.launch {
            val bundlePatches = withContext(Dispatchers.Default) {
                bundleRepository
                    .scopedBundleInfoFlow(packageName, desiredVersion)
                    .first()
                    .associate { scoped -> scoped.uid to scoped.patches.associateBy { it.name } }
            }

            replaceOptions(
                withContext(Dispatchers.Default) {
                    optionsRepository.getOptions(packageName, bundlePatches)
                }
            )
        }
    }

    private var selectionState: SelectionState by mutableStateOf(
        if (input.patches != null) SelectionState.Customized(input.patches) else SelectionState.Default
    )
    private val shouldLoadPersistedSelection =
        persistConfiguration &&
            input.profileId == null &&
            input.patches == null &&
            input.selectionPayloadJson == null

    init {
        if (shouldLoadPersistedSelection) {
            selectionLoadJob = viewModelScope.launch {
                if (!prefs.disableSelectionWarning.get()) return@launch
                bundleRepository.reloadInProgress.first { loading -> !loading }
                val previous = selectionRepository.getSelection(packageName)
                if (previous.values.sumOf { it.size } == 0) return@launch

                selectionState = SelectionState.Customized(previous)
                removeDeletedPatchesFromSavedSelection()
                pruneSelectionForAvailability(
                    bundleInfoFlowInternal.value,
                    prefs.disablePatchVersionCompatCheck.get()
                )
            }
            loadOptionsFromRepository()
        }
    }

    init {
        invalidateSelectedAppInfo()
        profileId?.let(::loadProfileConfiguration)
        viewModelScope.launch(Dispatchers.Main) {
            val packageInfo = async(Dispatchers.IO) { pm.getPackageInfo(packageName) }
            val installedAppDeferred =
                async(Dispatchers.IO) { installedAppRepository.get(packageName) }
            val installedApp = installedAppDeferred.await()

            installedAppData =
                packageInfo.await()?.let {
                    SelectedApp.Installed(
                        packageName,
                        it.versionName!!,
                        pm.getVersionCode(it)
                    ) to installedApp
                }
            if (profileId == null && input.patches != null) {
                val payload = inputSelectionPayload
                    ?: installedApp?.takeIf { it.installType == InstallType.SAVED }?.selectionPayload
                if (payload != null) {
                    applySavedSelectionPayload(payload)
                } else {
                    loadOptionsFromRepository()
                }
            }
        }

        allowUniversalPatches = prefs.disableUniversalPatchCheck.getBlocking()

        viewModelScope.launch {
            // Cached app-list previews can open this screen before execution metadata is ready.
            // Do not prune a remembered selection against the initial empty bundle state.
            bundleRepository.awaitReady()
            selectedAppState
                .flatMapLatest { app ->
                    bundleRepository.scopedBundleInfoFlow(app.packageName, app.version, app.versionCode)
                }
                .combine(allowUniversalFlow.distinctUntilChanged()) { bundles, allowUniversal ->
                    allowUniversal to bundles
                }
                .combine(prefs.disablePatchVersionCompatCheck.flow) { (allowUniversal, bundles), allowIncompatible ->
                    Triple(allowUniversal, bundles, allowIncompatible)
                }
                .collect { (allowUniversal, bundles, allowIncompatible) ->
                    allowUniversalPatches = allowUniversal
                    val visibleBundles = if (allowUniversal) {
                        bundles
                    } else {
                        bundles.map(PatchBundleInfo.Scoped::withoutUniversalPatches)
                    }
                    bundleInfoFlowInternal.value = visibleBundles
                    removeDeletedPatchesFromSavedSelection()
                    pruneSelectionForAvailability(visibleBundles, allowIncompatible)
                }
        }

        viewModelScope.launch {
            combine(
                preferredBundleVersionFlow,
                selectionRecommendedVersionFlow,
                preferredBundleAllVersionsFlow
            ) { preferred, selectionRecommended, targetsAll ->
                if (targetsAll) null else preferred ?: selectionRecommended ?: desiredVersion
            }.distinctUntilChanged().collect { target ->
                val current = selectedApp
                when (current) {
                    is SelectedApp.Search -> if (current.version != target) {
                        selectedApp = current.copy(version = target, versionCode = null)
                    }

                    is SelectedApp.Download -> if (
                        current.version.isNullOrBlank() ||
                        (target != null && current.version != target)
                    ) {
                        val version = target ?: current.version
                        selectedApp = current.copy(
                            version = version,
                            versionCode = current.versionCode.takeIf { current.version == version }
                        )
                    }

                    else -> Unit
                }
            }
        }

        viewModelScope.launch {
            combine(
                prefs.disablePatchVersionCompatCheck.flow,
                prefs.suggestedVersionSafeguard.flow,
                selectedBundleUidFlow,
                selectedBundleVersionOverrideFlow
            ) { allowIncompatible, requireSuggested, selectedUid, override ->
                val overridesAllowed = allowIncompatible && !requireSuggested
                val hasSelection = selectedUid != null || override != null
                overridesAllowed to hasSelection
            }.collect { (overridesAllowed, hasSelection) ->
                if (!overridesAllowed && hasSelection) {
                    selectBundleRecommendation(null, null)
                }
            }
        }

        if (shouldLoadPersistedSelection) {
            viewModelScope.launch {
                prefs.disableSelectionWarning.flow.distinctUntilChanged().collect { allowCustomSelection ->
                    if (allowCustomSelection) {
                        if (selectionState is SelectionState.Customized) return@collect
                        val previous = selectionRepository.getSelection(packageName)
                        if (previous.values.sumOf { it.size } == 0) return@collect
                        selectionState = SelectionState.Customized(previous)
                        pruneSelectionForAvailability(
                            bundleInfoFlowInternal.value,
                            prefs.disablePatchVersionCompatCheck.get()
                        )
                    } else {
                        clearSelectionState()
                    }
                }
            }
        }

        if (persistConfiguration) {
            viewModelScope.launch {
                selectionRepository.resetEvents.collect(::handleSelectionResetEvent)
            }
            viewModelScope.launch {
                optionsRepository.resetEvents.collect(::handleOptionsResetEvent)
            }
        }
    }

    private fun handleSelectionResetEvent(event: SelectionResetEvent) {
        if (!persistConfiguration) return
        when (event) {
            SelectionResetEvent.All -> clearSelectionState()
            is SelectionResetEvent.Package -> if (event.packageName == packageName) {
                clearSelectionState()
            }

            is SelectionResetEvent.Bundle -> clearSelectionForBundle(event.bundleUid)
        }
    }

    private fun clearSelectionState() {
        rememberedBundleSelections.clear()
        if (selectionState is SelectionState.Customized) {
            selectionState = SelectionState.Default
        }
    }

    private fun clearSelectionForBundle(bundleUid: Int) {
        rememberedBundleSelections.remove(bundleUid)
        val current = selectionState
        if (current !is SelectionState.Customized) return
        if (bundleUid !in current.patchSelection) return
        val updated = current.patchSelection
            .filterKeys { it != bundleUid }
        selectionState = if (updated.isEmpty()) SelectionState.Default else SelectionState.Customized(updated)
    }

    private fun handleOptionsResetEvent(event: OptionsResetEvent) {
        if (!persistConfiguration) return
        when (event) {
            OptionsResetEvent.All -> if (options.isNotEmpty()) {
                replaceOptions(emptyMap())
            }

            is OptionsResetEvent.Package -> if (event.packageName == packageName && options.isNotEmpty()) {
                replaceOptions(emptyMap())
            }

            is OptionsResetEvent.Bundle -> {
                if (event.bundleUid in options) {
                    replaceOptions(options.filterKeys { it != event.bundleUid })
                }
            }
        }
    }

    private fun loadProfileConfiguration(id: Int) {
        viewModelScope.launch(Dispatchers.Default) {
            bundleRepository.awaitReady()
            val profile = patchProfileRepository.getProfile(id) ?: run {
                withContext(Dispatchers.Main) {
                    app.toast(app.getString(R.string.patch_profile_launch_error))
                }
                autoLaunchProfilePatcher = false
                autoPatchProfile = false
                return@launch
            }
            autoPatchProfile = profile.autoPatch && !profile.apkPath.isNullOrBlank()
            profile.installerToken?.let { storedToken ->
                withContext(Dispatchers.Main) {
                    selectPatchMode(
                        installerTokenMatchesPatchMode(
                            installerManager.parseToken(storedToken),
                            useMount = true
                        )
                    )
                }
            }

            val sourcesList = bundleRepository.sources.first()
            val bundleInfoSnapshot = bundleRepository.bundleInfoFlow.first()
            val signatureMap = bundleInfoSnapshot.mapValues { (_, info) ->
                info.patches.map { it.name.trim().lowercase() }.toSet()
            }
            val remappedPayload = profile.payload.remapLocalBundles(sourcesList, signatureMap)
            val workingProfile = if (remappedPayload === profile.payload) profile else profile.copy(payload = remappedPayload)

            val scopedBundles = bundleRepository
                .scopedBundleInfoFlow(
                    profile.packageName,
                    resolvePatchProfileAppVersion(
                        appVersion = profile.appVersion,
                        apkPath = profile.apkPath,
                        apkVersion = profile.apkVersion,
                        useSelectedApkVersion = profile.useSelectedApkVersion
                    )
                )
                .first()
                .associateBy { it.uid }
            val allowUniversal = prefs.disableUniversalPatchCheck.get()
            if (!allowUniversal) {
                val universalPatchNamesByUid = bundleInfoSnapshot.mapValues { (_, info) ->
                    info.patches
                        .asSequence()
                        .filter { it.compatiblePackages == null }
                        .mapTo(mutableSetOf()) { it.name.trim().lowercase() }
                }
                val containsUniversal = workingProfile.payload.bundles.any { bundle ->
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
                if (containsUniversal) {
                    autoLaunchProfilePatcher = false
                    withContext(Dispatchers.Main) {
                        app.toast(
                            app.getString(
                                R.string.universal_patches_profile_blocked_description,
                                app.getString(R.string.universal_patches_safeguard)
                            )
                        )
                    }
                    return@launch
                }
            }

            val sources = sourcesList.associateBy { it.uid }
            val configuration = workingProfile.toConfiguration(scopedBundles, sources)
            val selection = configuration.selection.takeUnless { it.isEmpty() }

            updateConfiguration(
                selection,
                configuration.options,
                persistState = false,
                filterOptions = false
            ).join()

            _profileLaunchState.value = ProfileLaunchState(
                profile = workingProfile,
                selection = selection,
                options = configuration.options,
                missingBundles = configuration.missingBundles,
                changedBundles = configuration.changedBundles
            )

            if (configuration.missingBundles.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    app.toast(app.getString(R.string.patch_profile_missing_bundles_toast))
                }
            }
            if (configuration.changedBundles.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    app.toast(app.getString(R.string.patch_profile_changed_patches_toast))
                }
            }
        }
    }

    private fun applySavedSelectionPayload(payload: PatchProfilePayload) {
        val hasPayloadOptions = payload.bundles.any { it.options.isNotEmpty() }
        viewModelScope.launch(Dispatchers.Default) {
            bundleRepository.awaitReady()
            val sourcesList = bundleRepository.sources.first()
            val bundleInfoSnapshot = bundleRepository.bundleInfoFlow.first()
            val signatureMap = bundleInfoSnapshot.mapValues { (_, info) ->
                info.patches.map { it.name.trim().lowercase() }.toSet()
            }
            val remappedPayload = payload.remapLocalBundles(sourcesList, signatureMap)
            val scopedBundles = bundleRepository
                .scopedBundleInfoFlow(packageName, desiredVersion)
                .first()
                .associateBy { it.uid }
            val sources = sourcesList.associateBy { it.uid }
            val config = PatchProfile(
                uid = 0,
                name = "",
                packageName = packageName,
                appVersion = desiredVersion,
                apkPath = null,
                apkSourcePath = null,
                apkVersion = null,
                useSelectedApkVersion = false,
                autoPatch = false,
                installerToken = null,
                autoInstall = false,
                createdAt = 0L,
                payload = remappedPayload
            ).toConfiguration(scopedBundles, sources)
            val selection = config.selection.takeUnless { it.isEmpty() }
            val resolvedOptions = if (hasPayloadOptions) config.options else emptyMap()
            updateConfiguration(
                selection,
                resolvedOptions,
                persistState = false,
                filterOptions = false
            ).join()
        }
    }

    private suspend fun removeDeletedPatchesFromSavedSelection() {
        if (!shouldLoadPersistedSelection) return
        val currentState = selectionState as? SelectionState.Customized ?: return
        val availablePatches = bundleRepository.allBundlesInfoFlow.first()
            .mapValues { (_, bundle) ->
                bundle.patches.mapTo(mutableSetOf()) { it.name }
            }
        val removedPatchesByBundle = currentState.patchSelection.mapValues { (bundleUid, selectedPatches) ->
            val available = availablePatches[bundleUid].orEmpty()
            selectedPatches.filterNotTo(mutableSetOf()) { it in available }
        }.filterValues { it.isNotEmpty() }
        val removedPatches = removedPatchesByBundle.values
            .flatten()
            .sorted()
        if (removedPatches.isEmpty()) return

        val cleanedSelection = currentState.patchSelection.mapNotNull { (bundleUid, selectedPatches) ->
            val available = availablePatches[bundleUid].orEmpty()
            selectedPatches.filterTo(mutableSetOf()) { it in available }
                .takeIf { it.isNotEmpty() }
                ?.let { bundleUid to it }
        }.toMap()

        selectionState = SelectionState.Customized(cleanedSelection)
        replaceOptions(
            options.mapNotNull { (bundleUid, patchOptions) ->
                val removedForBundle = removedPatchesByBundle[bundleUid].orEmpty()
                patchOptions.filterKeys { it !in removedForBundle }
                    .takeIf { it.isNotEmpty() }
                    ?.let { bundleUid to it }
            }.toMap()
        )
        selectionRepository.updateSelection(packageName, cleanedSelection)
        optionsRepository.removeOptionsForPatches(packageName, removedPatchesByBundle)
        val pendingNames = removedPatchesNotice?.patchNames.orEmpty()
        removedPatchesNotice = RemovedPatchesNotice((pendingNames + removedPatches).sorted())
    }

    private fun pruneSelectionForAvailability(
        bundles: List<PatchBundleInfo.Scoped>,
        allowIncompatible: Boolean
    ) {
        if (bundles.isEmpty()) return

        val currentState = selectionState
        if (currentState is SelectionState.Customized) {
            val available = bundles.associate { bundle ->
                bundle.uid to bundle.patchSequence(allowIncompatible).map { it.name }.toSet()
            }
            val filteredSelection = buildMap<Int, Set<String>> {
                currentState.patchSelection.forEach { (bundleUid, patches) ->
                    val allowed = available[bundleUid] ?: return@forEach
                    val kept = patches.filter { it in allowed }.toSet()
                    if (kept.isNotEmpty()) put(bundleUid, kept)
                }
            }
            if (filteredSelection != currentState.patchSelection) {
                val hadSelectedBefore = currentState.patchSelection.values.any { it.isNotEmpty() }
                selectionState = if (filteredSelection.isEmpty() && hadSelectedBefore) {
                    SelectionState.Default
                } else {
                    SelectionState.Customized(filteredSelection)
                }
            }
        }

        val autoRecommendationMode =
            preferredBundleUidFlow.value == null &&
                preferredBundleOverrideFlow.value.isNullOrBlank()
        val currentSelectionState = selectionState
        if (autoRecommendationMode && currentSelectionState is SelectionState.Customized) {
            val hasAnySelectablePatch = currentSelectionState
                .patches(
                    bundles,
                    allowIncompatible,
                    installerTypeFor(usingMountInstall),
                    patchAvailabilityEnabled,
                    shouldRemoveGmsCore(usingMountInstall)
                )
                .values
                .any { it.isNotEmpty() }
            if (!hasAnySelectablePatch) {
                selectionState = SelectionState.Default
            }
        }

        val filteredOptions = options.filtered(bundles)
        if (filteredOptions != options) {
            replaceOptions(filteredOptions)
        }
    }

    val requiredVersion = combine(
        prefs.suggestedVersionSafeguard.flow,
        bundleRepository.suggestedVersions,
        selectionRecommendedVersionFlow,
        preferredBundleVersionFlow
    ) { suggestedVersionSafeguard, suggestedVersions, selectionRecommended, preferred ->
        if (!suggestedVersionSafeguard) return@combine null

        preferred ?: selectionRecommended ?: suggestedVersions[input.app.packageName]
    }

    var nonSuggestedVersionDialogSubject by mutableStateOf<SelectedApp.Local?>(null)
        private set
    var nonSuggestedVersionDialogSuggestedVersion by mutableStateOf<String?>(null)
        private set
    var nonSuggestedVersionDialogSuggestedVersionCodes by mutableStateOf<Set<Long>>(emptySet())
        private set
    var nonSuggestedVersionDialogRequiresUniversalEnabled by mutableStateOf(false)
        private set
    var universalFallbackDialogSubject by mutableStateOf<SelectedApp.Local?>(null)
        private set
    var universalFallbackDialogSuggestedVersion by mutableStateOf<String?>(null)
        private set

    fun dismissNonSuggestedVersionDialog() {
        nonSuggestedVersionDialogSubject = null
        nonSuggestedVersionDialogSuggestedVersion = null
        nonSuggestedVersionDialogSuggestedVersionCodes = emptySet()
        nonSuggestedVersionDialogRequiresUniversalEnabled = false
    }

    fun dismissUniversalFallbackDialog() {
        universalFallbackDialogSubject = null
        universalFallbackDialogSuggestedVersion = null
    }

    fun continueWithUniversalFallbackSelection() {
        val local = universalFallbackDialogSubject ?: return
        dismissUniversalFallbackDialog()
        dismissNonSuggestedVersionDialog()
        selectBundleRecommendation(null, null)
        selectedApp = local
        dismissSourceSelector()
    }

    suspend fun awaitOptions(): Options {
        optionsLoadJob?.join()
        return options
    }

    var showSourceSelector by mutableStateOf(requiresSourceSelection)
        private set
    private var pluginAction: Pair<LoadedDownloaderPlugin, Job>? by mutableStateOf(null)
    private var helperAction: Pair<ApkDownloadHelperContract.Helper, Job>? by mutableStateOf(null)
    val activePluginAction get() = pluginAction?.first?.id ?: helperAction?.first?.id
    private var launchedActivity by mutableStateOf<CompletableDeferred<ActivityResult>?>(null)
    private val launchActivityChannel = Channel<Intent>()
    val launchActivityFlow = launchActivityChannel.receiveAsFlow()

    private val downloaderEmptyStateFlow = combine(
        pluginsRepository.pluginStates,
        pluginsRepository.sourceStates,
        pluginsRepository.apkDownloadHelpers,
        pluginsRepository.hasLoadedInitialState
    ) { pluginStates, sourceStates, helpers, hasLoadedDownloaderState ->
        hasLoadedDownloaderState to
            (pluginStates.isNotEmpty() || sourceStates.isNotEmpty() || helpers.isNotEmpty())
    }

    val errorFlow = combine(
        plugins,
        apkDownloadHelpers,
        downloaderEmptyStateFlow,
        downloadedApps,
        snapshotFlow { selectedApp }
    ) { pluginsList, helpers, downloaderEmptyState, downloadedApps, app ->
        val (hasLoadedDownloaderState, hasAnyDownloaderPluginState) = downloaderEmptyState
        when {
            app is SelectedApp.Search &&
                downloadedApps.isEmpty() &&
                hasLoadedDownloaderState -> when {
                    pluginsList.isNotEmpty() -> null
                    helpers.isNotEmpty() -> Error.SourceSelectionRequired
                    hasAnyDownloaderPluginState -> Error.NoUsablePlugins
                    else -> Error.NoPluginsInstalled
                }
            else -> null
        }
    }

    fun showSourceSelector() {
        dismissSourceSelector()
        showSourceSelector = true
    }

    fun requestLocalSelection() {
        storageSelectionChannel.trySend(Unit)
    }

    private fun cancelPluginAction() {
        pluginAction?.second?.cancel()
        helperAction?.second?.cancel()
        pluginAction = null
        helperAction = null
    }

    fun dismissSourceSelector() {
        cancelPluginAction()
        showSourceSelector = false
    }

    fun handleStorageResult(uri: Uri?) {
        if (uri == null) {
            if (requiresSourceSelection && selectedApp is SelectedApp.Search) {
                showSourceSelector = true
            }
            return
        }

        viewModelScope.launch {
            when (val loadResult = withContext(Dispatchers.IO) { loadLocalApk(uri) }) {
                is LocalApkLoadResult.Success -> handleSelectedStorageApk(loadResult.app)
                LocalApkLoadResult.InvalidType -> {
                    app.toast(app.getString(R.string.selected_file_not_supported_apk))
                    if (requiresSourceSelection && selectedApp is SelectedApp.Search) {
                        showSourceSelector = true
                    }
                }

                LocalApkLoadResult.Failed -> {
                    app.toast(app.getString(R.string.failed_to_load_apk))
                    if (requiresSourceSelection && selectedApp is SelectedApp.Search) {
                        showSourceSelector = true
                    }
                }
            }
        }
    }

    fun handleStorageFile(file: File?) {
        if (file == null) {
            if (requiresSourceSelection && selectedApp is SelectedApp.Search) {
                showSourceSelector = true
            }
            return
        }

        viewModelScope.launch {
            when (val loadResult = withContext(Dispatchers.IO) { loadLocalApk(file) }) {
                is LocalApkLoadResult.Success -> handleSelectedStorageApk(loadResult.app)
                LocalApkLoadResult.InvalidType -> {
                    app.toast(app.getString(R.string.selected_file_not_supported_apk))
                    if (requiresSourceSelection && selectedApp is SelectedApp.Search) {
                        showSourceSelector = true
                    }
                }

                LocalApkLoadResult.Failed -> {
                    app.toast(app.getString(R.string.failed_to_load_apk))
                    if (requiresSourceSelection && selectedApp is SelectedApp.Search) {
                        showSourceSelector = true
                    }
                }
            }
        }
    }

    private suspend fun handleSelectedStorageApk(local: SelectedApp.Local) {
        val assessment = bundleRepository.assessVersionSelection(
            local.packageName,
            local.version,
            local.versionCode
        )
        if (!assessment.isAllowed) {
            if (assessment.canContinueWithUniversalFallback) {
                universalFallbackDialogSubject = local
                universalFallbackDialogSuggestedVersion = assessment.suggestedVersion
                dismissNonSuggestedVersionDialog()
            } else {
                nonSuggestedVersionDialogSubject = local
                nonSuggestedVersionDialogSuggestedVersion = assessment.suggestedVersion
                nonSuggestedVersionDialogSuggestedVersionCodes = assessment.suggestedVersionCodes
                nonSuggestedVersionDialogRequiresUniversalEnabled =
                    assessment.requiresUniversalPatchesEnabled
                dismissUniversalFallbackDialog()
            }

            dismissSourceSelector()
            return
        }

        dismissUniversalFallbackDialog()
        dismissNonSuggestedVersionDialog()
        selectBundleRecommendation(null, null)
        selectedApp = local
        dismissSourceSelector()
    }

    private suspend fun loadLocalApk(uri: Uri): LocalApkLoadResult =
        app.contentResolver.openInputStream(uri)?.use { stream ->
            storageInputDir.listFiles()
                ?.filter { it.name.startsWith("profile_input.") }
                ?.forEach(File::delete)
            val extension = resolveExtension(uri)
            if (extension.isBlank()) return@use LocalApkLoadResult.InvalidType
            val sanitized = extension.lowercase(Locale.ROOT).takeIf { it.matches(Regex("^[a-z0-9]{1,10}$")) }
                ?: "apk"
            val storageInputFile = File(storageInputDir, "profile_input.$sanitized").apply { delete() }
            Files.copy(stream, storageInputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            resolvePackageInfo(storageInputFile)?.let { packageInfo ->
                SelectedApp.Local(
                    packageName = packageInfo.packageName,
                    version = packageInfo.versionName ?: "",
                    file = storageInputFile,
                    temporary = true,
                    versionCode = pm.getVersionCode(packageInfo)
                )
            }?.let(LocalApkLoadResult::Success) ?: LocalApkLoadResult.Failed
        } ?: LocalApkLoadResult.Failed

    private suspend fun loadLocalApk(file: File): LocalApkLoadResult {
        if (!file.exists()) return LocalApkLoadResult.Failed
        storageInputDir.listFiles()
            ?.filter { it.name.startsWith("profile_input.") }
            ?.forEach(File::delete)
        val extension = file.extension.lowercase(Locale.ROOT)
            .takeIf { it in APK_FILE_EXTENSIONS }
            ?: "apk"
        val sanitized = extension.lowercase(Locale.ROOT).takeIf { it.matches(Regex("^[a-z0-9]{1,10}$")) }
            ?: "apk"
        val storageInputFile = File(storageInputDir, "profile_input.$sanitized").apply { delete() }
        Files.copy(file.toPath(), storageInputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return resolvePackageInfo(storageInputFile)?.let { packageInfo ->
            SelectedApp.Local(
                packageName = packageInfo.packageName,
                version = packageInfo.versionName ?: "",
                file = storageInputFile,
                temporary = true,
                versionCode = pm.getVersionCode(packageInfo)
            )
        }?.let(LocalApkLoadResult::Success) ?: LocalApkLoadResult.Failed
    }

    private fun resolveExtension(uri: Uri): String {
        val document = DocumentFile.fromSingleUri(app, uri)
        val mime = app.contentResolver.getType(uri)
        return resolveSupportedApkExtension(document?.name, mime).orEmpty()
    }

    private suspend fun resolvePackageInfo(file: File): PackageInfo? =
        resolveLocalPackageInfo(file).packageInfo

    private suspend fun resolveLocalPackageInfo(file: File): PackageInfoResolution {
        if (!file.exists()) return PackageInfoResolution(null)
        val hasApkExtension = file.extension.equals("apk", ignoreCase = true)
        if (hasApkExtension) {
            pm.getPackageInfo(file)?.let { return PackageInfoResolution(it) }
        }
        if (!SplitApkPreparer.isSplitArchive(file)) {
            return PackageInfoResolution(
                if (hasApkExtension) null else pm.getPackageInfo(file)
            )
        }

        val resolved = runCatching {
            SplitArchiveDisplayResolver.resolve(file, splitWorkspace, app, pm)
        }.getOrNull()
        if (resolved != null) {
            return PackageInfoResolution(
                packageInfo = resolved.packageInfo,
                labelOverride = resolved.label.takeUnless { it.isBlank() },
                iconOverride = resolved.icon
            )
        }

        return PackageInfoResolution(pm.getPackageInfo(file))
    }

    private data class PackageInfoResolution(
        val packageInfo: PackageInfo?,
        val labelOverride: String? = null,
        val iconOverride: Drawable? = null
    )

    private sealed interface LocalApkLoadResult {
        data class Success(val app: SelectedApp.Local) : LocalApkLoadResult
        data object InvalidType : LocalApkLoadResult
        data object Failed : LocalApkLoadResult
    }

    fun selectDownloadedApp(downloadedApp: DownloadedApp) {
        cancelPluginAction()
        viewModelScope.launch {
            val result = runCatching {
                val apkFile = downloadedAppRepository.getApkFileForApp(downloadedApp)
                withContext(Dispatchers.IO) {
                    downloadedAppRepository.get(
                        downloadedApp.packageName,
                        downloadedApp.version,
                        markUsed = true
                    )
                }
                SelectedApp.Local(
                    packageName = downloadedApp.packageName,
                    version = downloadedApp.version,
                    file = apkFile,
                    temporary = false
                )
            }

            result.onSuccess { local ->
                selectedApp = local
                dismissSourceSelector()
            }.onFailure { throwable ->
                Log.e(TAG, "Failed to select downloaded app", throwable)
                app.toast(app.getString(R.string.failed_to_load_apk))
            }
        }
    }

    fun selectBundleRecommendation(
        bundleUid: Int?,
        versionOverride: String?,
        targetsAllVersions: Boolean = false
    ) {
        val customSelectionEmpty =
            (selectionState as? SelectionState.Customized)
                ?.patchSelection
                ?.values
                ?.all { it.isEmpty() } == true

        if (bundleUid == null) {
            savedStateHandle["preferred_bundle_uid"] = null
            savedStateHandle["preferred_bundle_override"] = null
            savedStateHandle["preferred_bundle_all_versions"] = false
            if (customSelectionEmpty) {
                selectionState = SelectionState.Default
            }
        } else {
            savedStateHandle["preferred_bundle_uid"] = bundleUid
            savedStateHandle["preferred_bundle_override"] = versionOverride
            savedStateHandle["preferred_bundle_all_versions"] = targetsAllVersions
        }

        // Keep the selected app version in sync when no APK has been chosen yet (Search/download flows).
        val bundleRecommended = bundleUid?.let { recommendedVersionsByBundleFlow.value[it] }
        val targetVersion = if (targetsAllVersions) {
            null
        } else {
            versionOverride?.takeUnless { it.isBlank() }
                ?: bundleRecommended
                ?: selectionRecommendedVersionFlow.value
                ?: desiredVersion
        }
        when (val current = selectedApp) {
            is SelectedApp.Search -> {
                if (current.version != targetVersion) {
                    selectedApp = current.copy(version = targetVersion, versionCode = null)
                }
            }

            is SelectedApp.Download -> {
                if (current.version.isNullOrBlank() || (targetVersion != null && current.version != targetVersion)) {
                    val version = targetVersion ?: current.version
                    selectedApp = current.copy(
                        version = version,
                        versionCode = current.versionCode.takeIf { current.version == version }
                    )
                }
            }

            else -> Unit
        }

        if (bundleUid != null) {
            applyBundleRecommendationSelection(bundleUid)
        }
    }

    private fun applyBundleRecommendationSelection(bundleUid: Int) = viewModelScope.launch {
        selectionLoadJob?.join()
        (selectionState as? SelectionState.Customized)
            ?.patchSelection
            ?.let(::rememberBundleSelections)

        val bundles = bundleInfoFlow.first()
        val bundle = bundles.firstOrNull { it.uid == bundleUid } ?: return@launch
        val allowIncompatible = prefs.disablePatchVersionCompatCheck.get()
        val availablePatches = bundle.patchSequence(allowIncompatible).toList()
        val availablePatchNames = availablePatches.mapTo(mutableSetOf()) { it.name }
        val customizedPatches = rememberedBundleSelections[bundleUid]
            ?.filterTo(mutableSetOf()) { it in availablePatchNames }
            ?.takeIf { it.isNotEmpty() }
        val selectedPatches = customizedPatches
            ?: availablePatches
                .filter {
                    it.defaultSelected(
                        installerTypeFor(usingMountInstall),
                        patchAvailabilityEnabled
                    )
                }
                .map { it.name }
                .toSet()
                .ifEmpty {
                    bundle.patchSequence(false)
                        .filter {
                            it.defaultSelected(
                                installerTypeFor(usingMountInstall),
                                patchAvailabilityEnabled
                            )
                        }
                        .map { it.name }
                        .toSet()
                }
                .ifEmpty {
                    bundle.patchSequence(false)
                        .map { it.name }
                        .toSet()
                }

        if (preferredBundleUidFlow.value != bundleUid) return@launch

        if (selectedPatches.isEmpty()) {
            selectionState = SelectionState.Default
            return@launch
        }

        val filteredSelection = mapOf(bundleUid to selectedPatches)
            .removeGmsCoreSupport(shouldRemoveGmsCore(usingMountInstall))
        selectionState = if (filteredSelection.isEmpty()) {
            SelectionState.Default
        } else {
            SelectionState.Customized(filteredSelection)
        }
    }

    private fun rememberBundleSelections(selection: PatchSelection) {
        selection.forEach { (bundleUid, patches) ->
            if (patches.isEmpty()) {
                rememberedBundleSelections.remove(bundleUid)
            } else {
                rememberedBundleSelections[bundleUid] = patches.toSet()
            }
        }
    }

    private suspend fun awaitDownloaderActivityResult(intent: Intent): ActivityResult =
        withContext(Dispatchers.Main) {
            if (launchedActivity != null) error("Previous activity has not finished")
            try {
                val result = with(CompletableDeferred<ActivityResult>()) {
                    launchedActivity = this
                    launchActivityChannel.send(intent)
                    await()
                }
                when (result.resultCode) {
                    Activity.RESULT_OK -> result
                    Activity.RESULT_CANCELED -> throw UserInteractionException.Activity.Cancelled()
                    else -> throw UserInteractionException.Activity.NotCompleted(result.resultCode, result.data)
                }
            } finally {
                launchedActivity = null
            }
        }

    private suspend fun handleApkDownloadHelperInstalledResult(resultIntent: Intent) {
        val resultPackageName = ApkDownloadHelperContract.resultPackageName(resultIntent)
            ?: error("APK download helper did not report the installed package name")
        check(resultPackageName == packageName) {
            "APK download helper returned installed package $resultPackageName; expected $packageName"
        }

        val packageInfo = withContext(Dispatchers.IO) { pm.getPackageInfo(packageName) }
            ?: error("APK download helper requested an installed app that is no longer installed")
        val installedVersion = packageInfo.versionName
            ?.takeUnless { it.isBlank() }
            ?: error("Installed app does not report a version")
        val resultVersion = ApkDownloadHelperContract.resultVersionName(resultIntent)
            ?.takeUnless { it.isBlank() }
        check(resultVersion == null || resultVersion == installedVersion) {
            "APK download helper reported installed version $resultVersion; actual version is $installedVersion"
        }

        val installedRecord = withContext(Dispatchers.IO) {
            installedAppRepository.get(packageName)
        }
        when {
            installedRecord?.installType == InstallType.MOUNT && !hasRoot ->
                error(app.getString(R.string.app_source_dialog_option_installed_no_root))
            installedRecord?.installType == InstallType.DEFAULT ||
                installedRecord?.installType == InstallType.PLAY_STORE ||
                installedRecord?.installType == InstallType.ROOT_PLAY_STORE ||
                installedRecord?.installType == InstallType.CUSTOM ||
                installedRecord?.installType == InstallType.SHIZUKU ||
                installedRecord?.installType == InstallType.SHIZUKU_PLAY_STORE ->
                error(app.getString(R.string.already_patched))
        }

        val required = requiredVersion.first()
        if (required != null && installedVersion != required) {
            error(
                app.getString(
                    R.string.app_source_dialog_option_installed_version_not_suggested,
                    installedVersion
                )
            )
        }

        selectedApp = SelectedApp.Installed(
            packageName = packageName,
            version = installedVersion,
            versionCode = pm.getVersionCode(packageInfo)
        )
    }

    // Code adapted from Morphe, see third-party/NOTICE for more information
    // https://github.com/MorpheApp/morphe-manager/pull/797
    fun searchUsingApkDownloadHelper(helper: ApkDownloadHelperContract.Helper) {
        cancelPluginAction()
        helperAction = helper to viewModelScope.launch {
            try {
                val targetsAllVersions = preferredBundleAllVersionsFlow.value
                val selectionRecommended = selectionRecommendedVersionFlow.value
                val override = preferredBundleOverrideFlow.value?.takeUnless { it.isBlank() }
                val targetVersion = if (targetsAllVersions) {
                    null
                } else {
                    override ?: preferredBundleVersion ?: selectionRecommended ?: desiredVersion
                }
                val compatiblePackages = bundleInfoFlow.value
                    .asSequence()
                    .flatMap { it.patches.asSequence() }
                    .flatMap { it.compatiblePackages.orEmpty().asSequence() }
                    .filter { it.packageName == packageName }
                    .toList()
                val compatibleVersionNames = compatiblePackages
                    .flatMap { it.versions.orEmpty() }
                    .distinct()
                val versionCodes = targetVersion
                    ?.let { version ->
                        compatiblePackages.flatMap { it.versionCodes?.get(version).orEmpty() }
                    }
                    ?.distinct()
                    ?.toLongArray()
                    ?: longArrayOf()
                val appName = selectedAppInfoLabelOverride
                    ?: selectedAppInfo?.applicationInfo?.loadLabel(app.packageManager)?.toString()
                    ?: packageName
                val searchEngineHost = prefs.searchEngineHost.get()
                    .trim()
                    .removePrefix("https://")
                    .removePrefix("http://")
                    .substringBefore('/')
                    .substringBefore('?')
                    .substringBefore('#')
                    .trim()
                    .trimEnd('/')
                    .ifBlank { "google.com" }
                val fallbackWebUrl = "https://$searchEngineHost/search?q=" +
                    Uri.encode(listOfNotNull(packageName, targetVersion, "APK").joinToString(" "))
                val intent = ApkDownloadHelperContract.createRequestIntent(
                    helper = helper,
                    callerPackage = app.packageName,
                    packageName = packageName,
                    appName = appName,
                    versionName = targetVersion,
                    versionCodes = versionCodes,
                    compatibleVersionNames = compatibleVersionNames,
                    supportedAbis = Build.SUPPORTED_ABIS,
                    fileType = null,
                    allowSplitArchive = true,
                    stockInstallRequired = false,
                    fallbackWebUrl = fallbackWebUrl
                )
                val result = awaitDownloaderActivityResult(intent)
                val resultIntent = result.data
                if (ApkDownloadHelperContract.usesInstalledApp(resultIntent)) {
                    handleApkDownloadHelperInstalledResult(
                        resultIntent
                            ?: error("APK download helper returned an empty installed-app result")
                    )
                    return@launch
                }

                val uri = ApkDownloadHelperContract.resultUri(resultIntent)
                    ?: error("APK download helper did not return a content URI")
                check(ApkDownloadHelperContract.grantsReadAccess(resultIntent)) {
                    "APK download helper did not grant read access to the returned file"
                }
                when (val loadResult = withContext(Dispatchers.IO) { loadLocalApk(uri) }) {
                    is LocalApkLoadResult.Success -> {
                        if (loadResult.app.packageName != packageName) {
                            loadResult.app.file.delete()
                            error(
                                "APK download helper returned ${loadResult.app.packageName}; " +
                                    "expected $packageName"
                            )
                        }
                        handleSelectedStorageApk(loadResult.app)
                    }
                    LocalApkLoadResult.InvalidType -> error(app.getString(R.string.selected_file_not_supported_apk))
                    LocalApkLoadResult.Failed -> error(app.getString(R.string.failed_to_load_apk))
                }
            } catch (_: UserInteractionException.Activity.Cancelled) {
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                app.toast(app.getString(R.string.downloader_error, e.simpleMessage()))
                Log.e(TAG, "APK download helper failed", e)
            } finally {
                helperAction = null
                dismissSourceSelector()
            }
        }
    }

    fun searchUsingPlugin(plugin: LoadedDownloaderPlugin) {
        cancelPluginAction()
        pluginAction = plugin to viewModelScope.launch {
            try {
                val scope = object : GetScope {
                    override val hostPackageName = app.packageName
                    override val pluginPackageName = plugin.packageName
                    override suspend fun requestStartActivity(intent: Intent) =
                        withContext(Dispatchers.Main) {
                            if (launchedActivity != null) error("Previous activity has not finished")
                            try {
                                val result = with(CompletableDeferred<ActivityResult>()) {
                                    launchedActivity = this
                                    launchActivityChannel.send(intent)
                                    await()
                                }
                                when (result.resultCode) {
                                    Activity.RESULT_OK -> result.data
                                    Activity.RESULT_CANCELED -> throw UserInteractionException.Activity.Cancelled()
                                    else -> throw UserInteractionException.Activity.NotCompleted(
                                        result.resultCode,
                                        result.data
                                    )
                                }
                            } finally {
                                launchedActivity = null
                            }
                        }
                }

                val targetsAllVersions = preferredBundleAllVersionsFlow.value
                val selectionRecommended = selectionRecommendedVersionFlow.value
                val override = preferredBundleOverrideFlow.value?.takeUnless { it.isBlank() }
                val targetVersion =
                    if (targetsAllVersions) null
                    else override ?: preferredBundleVersion ?: selectionRecommended ?: desiredVersion
                val result = withContext(Dispatchers.IO) {
                    plugin.get(scope, packageName, targetVersion)
                }
                if (result == null) {
                    app.toast(app.getString(R.string.downloader_app_not_found))
                    return@launch
                }

                val (data, reportedVersion) = result

                val derivedVersion = resolveVersionFromDownloaderData(plugin, data, targetVersion)
                val resolvedVersion = when {
                    targetVersion != null && !looksLikeVersionCode(targetVersion) -> targetVersion
                    derivedVersion != null && !looksLikeVersionCode(derivedVersion) -> derivedVersion
                    else -> override ?: preferredBundleVersion ?: selectionRecommended ?: desiredVersion
                }
                if (targetVersion != null && resolvedVersion != targetVersion) {
                    Log.d(TAG, "Downloader provided $targetVersion, resolved version=$resolvedVersion")
                }
                selectedApp = SelectedApp.Download(
                    packageName,
                    resolvedVersion,
                    ParceledDownloaderData(plugin, data)
                )
            } catch (e: UserInteractionException.Activity) {
                app.toast(e.message!!)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                app.toast(app.getString(R.string.downloader_error, e.simpleMessage()))
                Log.e(TAG, "Downloader.get threw an exception", e)
            } finally {
                pluginAction = null
                dismissSourceSelector()
            }
        }
    }

    fun handlePluginActivityResult(result: ActivityResult) {
        launchedActivity?.complete(result)
    }

    fun handlePluginActivityLaunchFailure(error: Throwable) {
        launchedActivity?.completeExceptionally(error)
    }

    private fun invalidateSelectedAppInfo() = viewModelScope.launch {
        // Defer cleanup of the previous prepared APK until after new metadata is resolved,
        // so existing UI can keep reading the old label/icon without races.
        val previousCleanup = preparedApkCleanup
        preparedApkCleanup = null

        val resolution = when (val app = selectedApp) {
            is SelectedApp.Local -> withContext(Dispatchers.IO) { resolveLocalPackageInfo(app.file) }
            is SelectedApp.Installed -> withContext(Dispatchers.IO) {
                PackageInfoResolution(pm.getPackageInfo(app.packageName))
            }
            is SelectedApp.Download, is SelectedApp.Search -> withContext(Dispatchers.IO) {
                val version = app.version
                val downloaded = when {
                    version != null -> downloadedAppRepository.get(app.packageName, version)
                        ?: downloadedAppRepository.getLatest(app.packageName)
                    else -> downloadedAppRepository.getLatest(app.packageName)
                }
                downloaded?.let {
                    resolveLocalPackageInfo(downloadedAppRepository.getApkFileForApp(it))
                } ?: PackageInfoResolution(pm.getPackageInfo(app.packageName))
            }
            else -> PackageInfoResolution(null)
        }

        val unresolvedLocal = selectedApp as? SelectedApp.Local
        if (
            unresolvedLocal?.resolved == false &&
            resolution.packageInfo?.packageName != unresolvedLocal.packageName
        ) {
            Log.w(
                TAG,
                "Remembered repatch source does not match ${unresolvedLocal.packageName}: " +
                    (resolution.packageInfo?.packageName ?: "unreadable")
            )
            previousCleanup?.invoke()
            val suggestedVersion = runCatching {
                bundleRepository.awaitReady()
                bundleRepository.suggestedVersions.first()[unresolvedLocal.packageName]
            }.getOrNull()
            app.toast(app.getString(R.string.repatch_source_missing_description))
            selectedApp = SelectedApp.Search(unresolvedLocal.packageName, suggestedVersion)
            return@launch
        }

        selectedAppInfo = resolution.packageInfo
        selectedAppInfoLabelOverride = resolution.labelOverride
        selectedAppInfoIconOverride = resolution.iconOverride
        // Now that UI is updated to new info, we can safely clean up the old prepared APK.
        previousCleanup?.invoke()

        val current = selectedApp
        val resolvedVersion = resolution.packageInfo?.versionName?.takeUnless(String::isNullOrBlank)
        val resolvedVersionCode = resolution.packageInfo?.let(pm::getVersionCode)
        if (resolution.packageInfo != null) {
            when (current) {
                is SelectedApp.Local -> if (
                    !current.resolved ||
                    current.versionCode != resolvedVersionCode ||
                    (
                        current.packageName == current.file.nameWithoutExtension &&
                            resolution.packageInfo.packageName != current.packageName
                    )
                ) {
                    selectedApp = current.copy(
                        packageName = resolution.packageInfo.packageName,
                        version = resolvedVersion ?: current.version,
                        resolved = true,
                        versionCode = resolvedVersionCode
                    )
                }

                is SelectedApp.Download -> {
                    val adoptResolvedVersion =
                        current.version.isNullOrBlank() || current.packageName == current.version
                    val resolvedVersionMatches =
                        resolvedVersion != null && resolvedVersion == current.version
                    val version = if (adoptResolvedVersion) {
                        resolvedVersion ?: resolution.packageInfo.versionName
                    } else {
                        current.version
                    }
                    if (
                        (adoptResolvedVersion || resolvedVersionMatches) &&
                        (
                            current.packageName != resolution.packageInfo.packageName ||
                            current.version != version ||
                            current.versionCode != resolvedVersionCode
                        )
                    ) {
                        selectedApp = current.copy(
                            packageName = resolution.packageInfo.packageName,
                            version = version,
                            versionCode = resolvedVersionCode
                        )
                    }
                }

                is SelectedApp.Search -> {
                    val adoptResolvedVersion = current.version.isNullOrBlank()
                    val resolvedVersionMatches =
                        resolvedVersion != null && resolvedVersion == current.version
                    val version = if (adoptResolvedVersion) {
                        resolvedVersion ?: resolution.packageInfo.versionName
                    } else {
                        current.version
                    }
                    if (
                        (adoptResolvedVersion || resolvedVersionMatches) &&
                        (
                            current.packageName != resolution.packageInfo.packageName ||
                            current.version != version ||
                            current.versionCode != resolvedVersionCode
                        )
                    ) {
                        selectedApp = current.copy(
                            packageName = resolution.packageInfo.packageName,
                            version = version,
                            versionCode = resolvedVersionCode
                        )
                    }
                }

                is SelectedApp.Installed -> if (current.versionCode != resolvedVersionCode) {
                    selectedApp = current.copy(
                        version = resolvedVersion ?: current.version,
                        versionCode = resolvedVersionCode
                    )
                }

                else -> Unit
            }
        }
    }

    fun getOptionsFiltered(bundles: List<PatchBundleInfo.Scoped>) = options.filtered(bundles)
    suspend fun hasSetRequiredOptions(patchSelection: PatchSelection) = bundleInfoFlow
        .first()
        .requiredOptionsSet(
            allowIncompatible = prefs.disablePatchVersionCompatCheck.get(),
            isSelected = { bundle, patch -> patch.name in patchSelection[bundle.uid].orEmpty() },
            optionsForPatch = { bundle, patch -> options[bundle.uid]?.get(patch.name) },
        )

    init {
        trackTemporaryLocalCleanup(_selectedApp)
    }

    private fun trackTemporaryLocalCleanup(app: SelectedApp) {
        val local = app as? SelectedApp.Local ?: return
        if (!local.temporary) return
        temporaryLocalCleanupPaths += local.file.absolutePath
    }

    override fun onCleared() {
        super.onCleared()
        pendingOptionInputOwnership.releaseAll()
        preparedApkCleanup?.invoke()
        preparedApkCleanup = null
        temporaryLocalCleanupPaths
            .asSequence()
            .map(::File)
            .forEach { file ->
                runCatching {
                    if (file.exists()) file.delete()
                }
            }
        temporaryLocalCleanupPaths.clear()
    }

    suspend fun getPatcherParams(): Patcher.ViewModelParams {
        configurationUpdateJob?.join()
        selectionLoadJob?.join()
        optionsLoadJob?.join()
        val allowIncompatible = prefs.disablePatchVersionCompatCheck.get()
        val bundles = bundleInfoFlow.first()
        val profile = profileId?.let { patchProfileRepository.getProfile(it) }
        val chooseInstallerPerInstall = prefs.chooseInstallerPerInstall.get()
        val profileInstallerToken = profile?.installerToken?.takeIf { storedToken ->
            shouldApplyProfileInstallerPreference(
                chooseInstallerPerInstall = chooseInstallerPerInstall,
                autoInstall = profile.autoInstall,
                installerMatchesPatchMode = installerTokenMatchesPatchMode(
                    installerManager.parseToken(storedToken),
                    usingMountInstall
                )
            )
        }
        val globalSignatureWorkflow = prefs.injectSignatureMetadataAfterPatching.get()
        val rememberedWorkflow = inputSelectionPayload?.signatureWorkflow?.takeIf { it.remembered }
        val injectSignature = rememberedWorkflow?.enabled
            ?: profile?.payload?.signatureWorkflow?.enabled
            ?: globalSignatureWorkflow
        return Patcher.ViewModelParams(
            selectedApp = selectedApp,
            selectedPatches = getPatches(bundles, allowIncompatible),
            options = getOptionsFiltered(bundles),
            profileId = profile?.uid,
            profileInstallerToken = profileInstallerToken,
            autoInstall = profile?.autoInstall == true && profileInstallerToken != null,
            injectSignatureMetadata = injectSignature,
            rememberSignatureWorkflow = rememberedWorkflow != null ||
                (globalSignatureWorkflow && injectSignature),
            sourceEntryKey = sourceEntryKey,
            useMount = usingMountInstall,
        )
    }

    fun getPatches(bundles: List<PatchBundleInfo.Scoped>, allowIncompatible: Boolean) =
        selectionState.patches(
            bundles,
            allowIncompatible,
            installerTypeFor(usingMountInstall),
            patchAvailabilityEnabled,
            shouldRemoveGmsCore(usingMountInstall)
        )

    fun getCustomPatches(
        bundles: List<PatchBundleInfo.Scoped>,
        allowIncompatible: Boolean
    ): PatchSelection? =
        (selectionState as? SelectionState.Customized)?.patches(
            bundles,
            allowIncompatible,
            installerTypeFor(usingMountInstall),
            patchAvailabilityEnabled,
            shouldRemoveGmsCore(usingMountInstall)
        )

    private fun shouldRemoveGmsCore(useMount: Boolean): Boolean =
        useMount && removeGmsCoreForPrimaryMountEnabled &&
            installerManager.baseInstallerToken(installerManager.getPrimaryToken()) ==
                InstallerManager.Token.AutoSaved


    fun updateConfiguration(
        selection: PatchSelection?,
        options: Options,
        persistState: Boolean = persistConfiguration,
        filterOptions: Boolean = true,
        pendingInputs: Set<String> = emptySet()
    ): Job {
        pendingOptionInputOwnership.accept(patchOptionInputManager.pendingInputsIn(pendingInputs))
        configurationUpdateJob?.cancel()
        return viewModelScope.launch {
            optionsLoadJob?.cancel()
            optionsLoadJob = null

            rememberedBundleSelections.clear()
            selection?.let(::rememberBundleSelections)
            selectionState = selection?.let(SelectionState::Customized) ?: SelectionState.Default

            val filteredOptions = withContext(Dispatchers.Default) {
                if (filterOptions) {
                    options.filtered(bundleInfoFlow.first())
                } else {
                    options
                }
            }
            withContext(Dispatchers.Main) {
                replaceOptions(filteredOptions)
            }

            if (!persistConfiguration || !persistState) return@launch
            withContext(Dispatchers.Default) {
                val seenPatchesByBundle = bundleInfoFlow.first().associate { bundle ->
                    bundle.uid to bundle.patches.map(PatchInfo::name).toSet()
                }
                selection?.let {
                    selectionRepository.updateSelectionWithSeenPatches(
                        packageName,
                        it,
                        seenPatchesByBundle
                    )
                } ?: selectionRepository.resetSelectionForPackage(packageName)

                optionsRepository.saveOptions(packageName, filteredOptions)
            }
        }.also { configurationUpdateJob = it }
    }

    fun updateSelectedApp(app: SelectedApp) {
        if (selectedApp == app) return
        selectedApp = app
    }

    fun shouldAutoLaunchProfile() = autoLaunchProfilePatcher

    fun markProfileAutoLaunchConsumed() {
        autoLaunchProfilePatcher = false
        autoPatchProfile = false
    }

    fun shouldAutoPatchProfile() = autoPatchProfile

    data class ProfileLaunchState(
        val profile: PatchProfile,
        val selection: PatchSelection?,
        val options: Options,
        val missingBundles: Set<Int>,
        val changedBundles: Set<Int>
    )

    enum class Error(@param:StringRes val resourceId: Int) {
        NoPluginsInstalled(R.string.downloader_no_plugins_installed),
        NoUsablePlugins(R.string.downloader_no_plugins_available),
        SourceSelectionRequired(R.string.downloader_helper_source_required)
    }

    private fun PatchBundleInfo.Scoped.collectBundleSupport(
        packageName: String,
        selectedPatches: Set<String>?
    ): BundleSupport {
        var supportsAllVersions = false
        val versions = mutableSetOf<String>()
        val stableVersions = mutableSetOf<String>()
        val experimentalVersions = mutableSetOf<String>()
        val versionCodes = mutableMapOf<String, MutableSet<Long>>()
        var hasSupport = false

        patches.asSequence()
            .filter { selectedPatches == null || it.name in selectedPatches }
            .forEach { patch ->
            if (patch.compatiblePackages == null) {
                hasSupport = true
                supportsAllVersions = true
                return@forEach
            }
            patch.compatiblePackages
                ?.filter { it.packageName.equals(packageName, ignoreCase = true) }
                ?.forEach { compatible ->
                    hasSupport = true
                    if (bundleType == PatchBundleType.MORPHE) {
                        compatible.versionCodes.orEmpty().forEach { (version, codes) ->
                            versionCodes.getOrPut(version) { mutableSetOf() } += codes
                        }
                    }
                    val supportedVersions = compatible.versions
                    if (supportedVersions.isNullOrEmpty()) {
                        supportsAllVersions = true
                    } else {
                        val supportedExperimentalVersions = compatible.experimentalVersions.orEmpty().toSet()
                        versions += supportedVersions
                        experimentalVersions += supportedExperimentalVersions
                        stableVersions += supportedVersions.filterNot { it in supportedExperimentalVersions }
                    }
                }
        }

        return BundleSupport(
            hasSupport = hasSupport,
            supportsAllVersions = supportsAllVersions,
            versions = versions,
            stableVersions = stableVersions,
            experimentalVersions = experimentalVersions,
            versionCodes = versionCodes
        )
    }

    private data class BundleSupport(
        val hasSupport: Boolean,
        val supportsAllVersions: Boolean,
        val versions: Set<String>,
        val stableVersions: Set<String>,
        val experimentalVersions: Set<String>,
        val versionCodes: Map<String, Set<Long>>
    )

    private fun PatchBundleInfo.Scoped.recommendedVersionForSelection(
        packageName: String,
        selectedPatches: Set<String>?
    ): String? {
        val patches = patches.filter { selectedPatches == null || it.name in selectedPatches }
        if (patches.isEmpty()) return null

        return when (bundleType) {
            PatchBundleType.REVANCED -> {
                val versionCounts = patches
                    .asSequence()
                    .map { it.toPatcherPatch() }
                    .toSet()
                    .revancedMostCommonCompatibleVersions(countUnusedPatches = true)[packageName]
                    ?: return null
                selectSuggestedPatchVersion(versionCounts)
            }

            PatchBundleType.MORPHE,
            PatchBundleType.RESEAM,
            PatchBundleType.AMPLE -> suggestedVersionForMorphe(patches, packageName)
        }
    }

    private fun suggestedVersionForMorphe(
        patches: Iterable<PatchInfo>,
        packageName: String
    ): String? {
        val versions = linkedMapOf<String, MorpheVersionStats>()

        patches.forEach { patch ->
            patch.compatiblePackages
                ?.filter { it.packageName.equals(packageName, ignoreCase = true) }
                ?.forEach { compatible ->
                    val supportedVersions = compatible.versions ?: return@forEach
                    supportedVersions.sorted().forEach { version ->
                        val stats = versions.getOrPut(version) { MorpheVersionStats() }
                        if (compatible.experimentalVersions?.contains(version) == true) {
                            stats.experimentalPatchCount += 1
                        } else {
                            stats.stablePatchCount += 1
                        }
                    }
                }
        }

        val stableVersions = versions.mapValues { (_, stats) -> stats.stablePatchCount }
            .filterValues { it > 0 }
        val selectedVersions = stableVersions.ifEmpty {
            versions.mapValues { (_, stats) -> stats.totalPatchCount }
        }

        return selectSuggestedPatchVersion(selectedVersions)
    }

    private data class MorpheVersionStats(
        var stablePatchCount: Int = 0,
        var experimentalPatchCount: Int = 0
    ) {
        val totalPatchCount: Int
            get() = stablePatchCount + experimentalPatchCount
    }

    private suspend fun resolveVersionFromDownloaderData(
        plugin: LoadedDownloaderPlugin,
        data: Parcelable,
        targetVersion: String?
    ): String? = when (data) {
        is DownloaderPackage -> sanitizeVersionString(data.version)
        is DownloadUrl -> resolveVersionFromDownloadUrl(data, sanitizeVersionString(targetVersion) ?: targetVersion)
        else -> {
            Log.d(TAG, "Unhandled downloader data type from ${plugin.packageName}: ${data::class.java.simpleName}")
            null
        }
    }

    private suspend fun resolveVersionFromDownloadUrl(
        downloadUrl: DownloadUrl,
        targetVersion: String?
    ): String? = withContext(Dispatchers.IO) {
        val probe = runCatching { probeDownloadUrl(downloadUrl) }.getOrNull()
        val candidates = buildList {
            probe?.filename?.let(::add)
            probe?.finalUrl?.let(::add)
            add(downloadUrl.url)
            sanitizeVersionString(targetVersion)?.let(::add)
        }

        candidates.firstNotNullOfOrNull { candidate ->
            candidate?.let(::extractVersionCandidate)
        }
    }

    private data class ProbeResult(val finalUrl: String, val filename: String?)

    private fun probeDownloadUrl(downloadUrl: DownloadUrl): ProbeResult {
        var currentUrl = downloadUrl.url
        var filename: String? = null

        repeat(4) {
            val connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "HEAD"
                connectTimeout = 10_000
                readTimeout = 10_000
                useCaches = false
                doInput = true
                downloadUrl.headers.forEach(::setRequestProperty)
            }

            connection.connect()
            connection.getHeaderField("Content-Disposition")?.let { disposition ->
                parseFilename(disposition)?.let { resolved -> filename = resolved }
            }

            val code = runCatching { connection.responseCode }.getOrDefault(-1)
            val location = connection.getHeaderField("Location")

            if (code in 300..399 && !location.isNullOrBlank()) {
                currentUrl = URL(URL(currentUrl), location).toString()
                return@repeat
            }

            if (filename.isNullOrBlank()) {
                filename = connection.url.path.substringAfterLast('/').takeIf { it.isNotBlank() }
            }

            return ProbeResult(currentUrl, filename)
        }

        return ProbeResult(currentUrl, filename)
    }

    private fun parseFilename(contentDisposition: String): String? {
        contentDisposition.split(';').forEach { part ->
            val trimmed = part.trim()
            if (trimmed.startsWith("filename", ignoreCase = true)) {
                val value = trimmed.substringAfter('=').trim().trim('"')
                if (value.isNotBlank()) return value
            }
        }
        return null
    }

    private fun extractVersionCandidate(raw: String): String? {
        val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        // Strip build metadata / query markers that occasionally leak into filenames or URLs.
        val cleaned = decoded
            .substringBefore('$')
            .trimEnd('-', '_', '.', '+')
        if (cleaned.isBlank()) return null
        val qualifiedPattern = Regex(
            """\d+(?:[._-]\d+){1,5}(?:[._-](?:release|beta|alpha|rc|build)[A-Za-z0-9.]*)?""",
            RegexOption.IGNORE_CASE
        )
        val numericPattern = Regex("""\d+(?:[._-]\d+){1,5}""")
        val matches = (qualifiedPattern.findAll(cleaned) + numericPattern.findAll(cleaned))
            .map { it.value }
            .distinct()
            .toList()
        if (matches.isEmpty()) return null

        val best = matches
            .map { it to scoreVersionCandidate(it) }
            .maxWithOrNull(
                compareBy<Pair<String, Int>> { it.second }
                    .thenBy { it.first.length } // prefer shorter strings
                    .thenBy { leadingNumber(it.first) } // prefer smaller leading number
            )?.first ?: return null
        var normalized = best
            .trim('.', '-', '_')
            .replace("_", ".")
            .replace(Regex("(?<=\\d)-(\\d)")) { ".$1" }
        normalized = trimBuildMetadata(normalized)
        if (normalized.isBlank()) return null
        if (!normalized.first().isDigit()) return null
        if (!normalized.contains('.')) return null
        val firstNumeric = leadingNumber(normalized)
        val hasQualifier = hasQualifier(normalized)
        val dotCount = normalized.count { it == '.' }
        if (firstNumeric > 200000 && !hasQualifier && dotCount <= 1) return null
        return normalized
    }

    private fun sanitizeVersionString(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isBlank()) return null
        extractVersionCandidate(trimmed)?.let { return it }
        val withoutPrefix = trimmed.removePrefix("v").trim()
        return withoutPrefix.takeIf { it.isNotBlank() && withoutPrefix.first().isDigit() }
    }

    private fun scoreVersionCandidate(candidate: String): Int {
        var score = 0
        val hasQualifier = hasQualifier(candidate)
        val parts = candidate.split('.', '-', '_').filter { it.isNotBlank() }
        val firstPart = parts.firstOrNull() ?: ""
        val firstNumeric = firstPart.toIntOrNull() ?: 0

        if (hasQualifier) score += 10
        if (parts.size >= 3) score += 6
        if (parts.size >= 4) score += 2
        if (firstPart.length <= 4) score += 4
        if (firstNumeric in 1..9999) score += 2
        if (candidate.length <= 20) score += 2

        // Penalize likely version codes (very large leading number with few segments and no qualifier)
        if (firstPart.length >= 6 && !hasQualifier) score -= 8
        if (firstNumeric > 200000 && !hasQualifier) score -= 6
        if (parts.size <= 2 && !hasQualifier) score -= 3

        return score
    }

    private fun trimBuildMetadata(value: String): String {
        return value.substringBefore('$').trim('.', '-', '_')
    }

    private fun leadingNumber(candidate: String): Int {
        val first = candidate.split('.', '-', '_').firstOrNull()?.trim() ?: return Int.MAX_VALUE
        return first.toIntOrNull() ?: Int.MAX_VALUE
    }

    private fun hasQualifier(candidate: String): Boolean =
        candidate.contains(Regex("(release|beta|alpha|rc|build)", RegexOption.IGNORE_CASE))

    private fun looksLikeVersionCode(value: String): Boolean {
        val numericOnly = value.all { it.isDigit() || it == '.' }
        val first = value.split('.', '-', '_').firstOrNull()?.trim().orEmpty()
        val firstNum = first.toLongOrNull() ?: return false
        val dotCount = value.count { it == '.' }
        return numericOnly && firstNum > 200000 && dotCount <= 1
    }

    private companion object {
        private val TAG = SelectedAppInfoViewModel::class.java.simpleName ?: "SelectedAppInfoViewModel"
        /**
         * Returns a copy with all nonexistent options removed.
         */
        private fun Options.filtered(bundles: List<PatchBundleInfo.Scoped>): Options {
            if (isEmpty()) return this
            if (bundles.isEmpty()) return this

            return buildMap options@{
                bundles.forEach bundles@{ bundle ->
                    val bundleOptions = this@filtered[bundle.uid] ?: return@bundles

                    val patches = bundle.patches.associateBy { it.name }

                    this@options[bundle.uid] = buildMap bundleOptions@{
                        bundleOptions.forEach patch@{ (patchName, values) ->
                            // Get all valid option keys for the patch.
                            val validOptionKeys =
                                patches[patchName]?.options?.map { it.key }?.toSet() ?: return@patch

                            this@bundleOptions[patchName] = values.filterKeys { key ->
                                key in validOptionKeys
                            }
                        }
                    }
                }
            }
        }
    }
}

private sealed interface SelectionState : Parcelable {
    fun patches(
        bundles: List<PatchBundleInfo.Scoped>,
        allowIncompatible: Boolean,
        installerType: app.universal.patch.manager.patcher.patch.PatchInstallerType,
        availabilityEnabled: Boolean,
        removeGmsCore: Boolean,
    ): PatchSelection

    @Parcelize
    data class Customized(val patchSelection: PatchSelection) : SelectionState {
        override fun patches(
            bundles: List<PatchBundleInfo.Scoped>,
            allowIncompatible: Boolean,
            installerType: app.universal.patch.manager.patcher.patch.PatchInstallerType,
            availabilityEnabled: Boolean,
            removeGmsCore: Boolean,
        ) = bundles.filter { it.uid in patchSelection }
            .toPatchSelection(allowIncompatible) { uid, patch ->
                patchSelection[uid]?.contains(patch.name) ?: false
            }
            .applyAvailability(
                installerType,
                bundles.associate { bundle ->
                    bundle.uid to bundle.patchSequence(allowIncompatible)
                        .associateBy(PatchInfo::name)
                },
                availabilityEnabled
            )
            .removeGmsCoreSupport(removeGmsCore)
    }

    @Parcelize
    data object Default : SelectionState {
        override fun patches(
            bundles: List<PatchBundleInfo.Scoped>,
            allowIncompatible: Boolean,
            installerType: app.universal.patch.manager.patcher.patch.PatchInstallerType,
            availabilityEnabled: Boolean,
            removeGmsCore: Boolean,
        ) = bundles.toPatchSelection(allowIncompatible) { _, patch ->
            patch.defaultSelected(installerType, availabilityEnabled)
        }.removeGmsCoreSupport(removeGmsCore)
    }
}

data class BundleRecommendationDetail(
    val bundleUid: Int,
    val name: String,
    val recommendedVersion: String?,
    val recommendedVersionCodes: Set<Long>,
    val recommendedVersionExperimental: Boolean,
    val otherSupportedVersions: List<SupportedVersionInfo>,
    val supportsAllVersions: Boolean
)

private fun PatchBundleInfo.Scoped.withoutUniversalPatches(): PatchBundleInfo.Scoped {
    if (universal.isEmpty()) return this

    val filteredPatches = patches.filter { it.compatiblePackages != null }
    return copy(
        patches = filteredPatches,
        universal = emptyList()
    )
}
