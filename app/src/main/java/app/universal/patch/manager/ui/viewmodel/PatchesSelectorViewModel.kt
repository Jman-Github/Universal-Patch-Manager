package app.universal.patch.manager.ui.viewmodel

import android.app.Application
import android.content.pm.PackageInfo
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.SavedStateHandleSaveableApi
import androidx.lifecycle.viewmodel.compose.saveable
import app.universal.patch.manager.R
import app.universal.patch.manager.data.platform.Filesystem
import app.universal.patch.manager.data.room.profile.PatchProfilePayload
import app.universal.patch.manager.data.room.options.Option as StoredOption
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.domain.installer.InstallerManager
import app.universal.patch.manager.domain.bundles.PatchBundleSource.Extensions.asRemoteOrNull
import app.universal.patch.manager.domain.bundles.RemotePatchBundle
import app.universal.patch.manager.domain.repository.PatchBundleRepository
import app.universal.patch.manager.domain.repository.PatchSelectionRepository
import app.universal.patch.manager.domain.repository.DuplicatePatchProfileNameException
import app.universal.patch.manager.domain.repository.PatchProfileRepository
import app.universal.patch.manager.domain.repository.PatchOptionInputManager
import app.universal.patch.manager.domain.repository.remapLocalBundles
import app.universal.patch.manager.domain.repository.DownloadedAppRepository
import app.universal.patch.manager.patcher.patch.Option
import app.universal.patch.manager.patcher.patch.PatchBundleType
import app.universal.patch.manager.patcher.patch.PatchBundleInfo
import app.universal.patch.manager.patcher.patch.PatchBundleInfo.Extensions.toPatchSelection
import app.universal.patch.manager.patcher.patch.PatchInfo
import app.universal.patch.manager.patcher.patch.PatchLockState
import app.universal.patch.manager.patcher.patch.GMSCORE_SUPPORT_PATCH_NAME
import app.universal.patch.manager.patcher.patch.applyAvailability
import app.universal.patch.manager.patcher.patch.installerTypeFor
import app.universal.patch.manager.patcher.patch.removeGmsCoreSupport
import app.universal.patch.manager.patcher.patch.hasRequiredValue
import app.universal.patch.manager.patcher.split.SplitApkInspector
import app.universal.patch.manager.patcher.split.SplitApkPreparer
import app.universal.patch.manager.ui.model.SelectedApp
import app.universal.patch.manager.ui.model.SupportedVersionInfo
import app.universal.patch.manager.ui.model.navigation.SelectedApplicationInfo
import app.universal.patch.manager.util.Options
import app.universal.patch.manager.util.PM
import app.universal.patch.manager.util.PatchSelection
import app.universal.patch.manager.util.tag
import app.universal.patch.manager.util.saver.Nullable
import app.universal.patch.manager.util.saver.persistentMapSaver
import app.universal.patch.manager.util.saver.snapshotStateMapSaver
import app.universal.patch.manager.util.toast
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.collections.ArrayDeque

@OptIn(SavedStateHandleSaveableApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PatchesSelectorViewModel(input: SelectedApplicationInfo.PatchesSelector.ViewModelParams) :
    ViewModel(), KoinComponent {
    private val app: Application = get()
    private val filesystem: Filesystem = get()
    private val pm: PM = get()
    private val savedStateHandle: SavedStateHandle = get()
    val prefs: PreferencesManager = get()
    private val installerManager: InstallerManager = get()
    private val patchBundleRepository: PatchBundleRepository = get()
    private val patchSelectionRepository: PatchSelectionRepository = get()
    private val patchOptionInputManager: PatchOptionInputManager = get()
    val missingPatchNames: List<String>? = input.missingPatchNames
    private val patchProfileRepository: PatchProfileRepository = get()

    val selectedApp = input.app
    private val packageName = selectedApp.packageName
    val selectedAppInfo = flow {
        emit(resolveSelectedAppInfo())
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = null
    )
    private val downloadedAppRepository: DownloadedAppRepository = get()
    private val preferredBundleUid = input.preferredBundleUid
    private val preferredBundleOverride = input.preferredBundleOverride?.takeUnless { it.isNullOrBlank() }
    private val preferredBundleTargetsAllVersions = input.preferredBundleTargetsAllVersions
    private val preferredBundleVersion = input.preferredBundleVersion?.takeUnless { it.isNullOrBlank() }
    private val preferredAppVersionHint = input.preferredAppVersion?.takeUnless { it.isBlank() }
    private var appVersion: String? = null
    private val appVersionState = MutableStateFlow<String?>(null)
    val appPackageName: String
        get() = packageName
    val currentAppVersion: String?
        get() = appVersion
    private var currentBundles: List<PatchBundleInfo.Scoped> = emptyList()
    private val installerType = installerTypeFor(input.useMount)
    private val patchAvailabilityEnabled = prefs.patchAvailabilityEnabled.getBlocking()
    private val removeGmsCoreForMount = input.useMount &&
        installerManager.baseInstallerToken(installerManager.getPrimaryToken()) ==
            InstallerManager.Token.AutoSaved &&
        prefs.removeGmsCoreForPrimaryMount.getBlocking()

    var selectionWarningEnabled by mutableStateOf(true)
        private set
    var allowUniversalPatches by mutableStateOf(true)
        private set

    var allowIncompatiblePatches by mutableStateOf(prefs.disablePatchVersionCompatCheck.getBlocking())
        private set
    private val suggestedVersionSafeguardEnabled = prefs.suggestedVersionSafeguard.getBlocking()
    private val allowUniversalPatchesFlow = prefs.disableUniversalPatchCheck.flow
    val bundlesFlow =
        combine(appVersionState, selectedAppInfo) { version, info ->
            val resolvedVersionCode =
                info?.takeIf { it.versionName == version }?.let(pm::getVersionCode)
            val knownVersionCode =
                selectedApp.versionCode.takeIf { selectedApp.version == version }
            version to (resolvedVersionCode ?: knownVersionCode)
        }.flatMapLatest { (version, versionCode) ->
            patchBundleRepository.scopedBundleInfoFlow(packageName, version, versionCode)
        }.combine(allowUniversalPatchesFlow) { bundles, allowUniversal ->
            if (allowUniversal) bundles else bundles.map(PatchBundleInfo.Scoped::withoutUniversalPatches)
        }
    val bundleDisplayNames =
        patchBundleRepository.sources.map { sources ->
            sources.associate { source ->
                val title = source.displayTitle
                source.uid to title
            }
        }
    val bundleEndpoints =
        patchBundleRepository.sources.map { sources ->
            sources.associate { source ->
                source.uid to (source as? RemotePatchBundle)?.endpoint
            }
        }
    val bundleIdentifiers =
        patchBundleRepository.sources.map { sources ->
            sources.associate { source ->
                val identifier = source.patchBundle?.manifestAttributes?.name?.takeUnless { it.isNullOrBlank() }
                    ?: source.name
                source.uid to identifier
            }
        }
    val bundleTypes =
        patchBundleRepository.sources.map { sources ->
            sources.associate { source ->
                val type = when {

                    source.asRemoteOrNull != null -> BundleSourceType.Remote
                    else -> BundleSourceType.Local
                }
                source.uid to type
            }
        }

    private val defaultPatchSelection = bundlesFlow
        .map { bundles ->
            bundles.toPatchSelection(allowIncompatiblePatches) { _, patch ->
                isDefaultSelected(patch)
            }
                .toPersistentPatchSelection()
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = persistentMapOf()
        )

    private var currentDefaultSelection: PersistentPatchSelection by mutableStateOf(persistentMapOf())

    val defaultSelectionCount = defaultPatchSelection.map { selection ->
        selection.values.sumOf { it.size }
    }

    private val filterState = mutableStateOf(resolveInitialFilter(savedStateHandle, prefs))
    var filter: Int
        get() = filterState.value
        private set(value) {
            filterState.value = value
            savedStateHandle["filter"] = value
            viewModelScope.launch {
                prefs.patchSelectionFilterFlags.update(value)
            }
        }
    var showNewPatchesSection by mutableStateOf(prefs.patchSelectionShowNewPatches.getBlocking())
        private set
    private val _newPatchNamesByBundle = MutableStateFlow<Map<Int, Set<String>>>(emptyMap())
    val newPatchNamesByBundle = _newPatchNamesByBundle.asStateFlow()
    val suggestedVersionsByBundle = patchBundleRepository.suggestedVersionsByBundle

    init {
        if (prefs.patchSelectionFilterFlags.getBlocking() < 0) {
            viewModelScope.launch {
                prefs.patchSelectionFilterFlags.update(filterState.value)
            }
        }
        val initialVersion = when {
            preferredBundleUid != null && preferredBundleTargetsAllVersions -> null
            preferredBundleUid != null -> preferredBundleOverride
                ?: preferredBundleVersion
                ?: preferredAppVersionHint
                ?: input.app.version
            else -> input.app.version ?: preferredAppVersionHint
        }
        setAppVersion(initialVersion)
        viewModelScope.launch {
            prefs.disablePatchVersionCompatCheck.flow
                .distinctUntilChanged()
                .collect { allow ->
                    allowIncompatiblePatches = allow
                    filter = if (allow) {
                        filter or SHOW_INCOMPATIBLE
                    } else {
                        filter and SHOW_INCOMPATIBLE.inv()
                    }
                }
        }

        allowUniversalPatches = prefs.disableUniversalPatchCheck.getBlocking()

        viewModelScope.launch {
            allowUniversalPatchesFlow
                .distinctUntilChanged()
                .collect { allowUniversal ->
                    allowUniversalPatches = allowUniversal
                    if (allowUniversal) {
                        filter = filter or SHOW_UNIVERSAL
                    } else {
                        filter = filter and SHOW_UNIVERSAL.inv()
                        pruneSelectionsAndOptions()
                    }
                }
        }

        viewModelScope.launch {
            bundlesFlow.collect { bundles ->
                currentBundles = bundles
                _newPatchNamesByBundle.value = loadNewPatchNames(bundles)
                if (!allowUniversalPatches) pruneSelectionsAndOptions()
            }
        }

        viewModelScope.launch {
            if (prefs.disableSelectionWarning.get()) {
                selectionWarningEnabled = false
                return@launch
            }

            fun PatchBundleInfo.Scoped.hasDefaultPatches() =
                patchSequence(allowIncompatiblePatches).any {
                    isDefaultSelected(it)
                }

            // Don't show the warning if there are no default patches.
            selectionWarningEnabled = bundlesFlow.first().any(PatchBundleInfo.Scoped::hasDefaultPatches)
        }

        viewModelScope.launch {
            currentDefaultSelection = defaultPatchSelection.first()
        }

        viewModelScope.launch {
            defaultPatchSelection.collect { currentDefaultSelection = it }
        }
    }

    private var hasModifiedSelection = false
    private val customPatchSelectionKey = "selection_${packageName}"
    private var customPatchSelectionState by mutableStateOf(
        restoreInitialCustomPatchSelection(input.currentSelection)
    )
    var customPatchSelection: PersistentPatchSelection?
        get() = customPatchSelectionState
        set(value) {
            customPatchSelectionState = value
            if (value == null) {
                savedStateHandle.remove<Any?>(customPatchSelectionKey)
                return
            }
            savedStateHandle[customPatchSelectionKey] = Nullable(value.toPatchSelection())
        }

    private val patchOptions: PersistentOptions by savedStateHandle.saveable(
        saver = optionsSaver,
    ) {
        // Convert Options to PersistentOptions
        input.options.mapValuesTo(mutableStateMapOf()) { (_, allPatches) ->
            allPatches.mapValues { (_, options) -> options.toPersistentMap() }.toPersistentMap()
        }
    }
    private val inheritedPendingOptionInputs = patchOptionInputManager.pendingInputsIn(input.options)
    private val pendingOptionInputOwnership = patchOptionInputManager.pendingInputOwnership(
        inherited = inheritedPendingOptionInputs,
        initiallyReferenced = patchOptionInputManager.pendingInputsIn(patchOptions)
    )

    /**
     * Show the patch options dialog for this patch.
     */
    var optionsDialog by mutableStateOf<Pair<Int, PatchInfo>?>(null)
    var showMixedPatchBundlesDialog by mutableStateOf(false)
        private set
    var showMixedRevancedPatcherVersionsDialog by mutableStateOf(false)
        private set

    val compatibleVersions = mutableStateListOf<SupportedVersionInfo>()

    // This is for the required options screen.
    private val requiredOptsPatchesDeferred = viewModelScope.async(start = CoroutineStart.LAZY) {
        bundlesFlow.first().map { bundle ->
            bundle to bundle.patchSequence(allowIncompatiblePatches).filter { patch ->
                val opts by lazy {
                    getOptions(bundle.uid, patch).orEmpty()
                }
                isSelected(
                    bundle.uid,
                    patch
                ) && patch.options?.any { option -> !option.hasRequiredValue(opts) } ?: false
            }.toList()
        }.filter { (_, patches) -> patches.isNotEmpty() }
    }
    val requiredOptsPatches = flow { emit(requiredOptsPatchesDeferred.await()) }

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    private data class SelectionSnapshot(
        val selection: PersistentPatchSelection?,
        val options: Options
    )

    private data class HistoryEntry(
        val snapshot: SelectionSnapshot,
        val description: String
    )

    private val history = ArrayDeque<HistoryEntry>()
    private val future = ArrayDeque<HistoryEntry>()

    fun selectionIsValid(bundles: List<PatchBundleInfo.Scoped>) = bundles.any { bundle ->
        bundle.patchSequence(allowIncompatiblePatches).any { patch ->
            isSelected(bundle.uid, patch)
        }
    }

    fun bundleHasSelection(bundleUid: Int): Boolean {
        customPatchSelection?.get(bundleUid)?.let { return it.isNotEmpty() }
        return customPatchSelection == null && (currentDefaultSelection[bundleUid]?.isNotEmpty() == true)
    }

    data class BundleSelectionProgress(
        val selectedCount: Int,
        val totalCount: Int
    )

    fun bundleSelectionProgress(bundle: PatchBundleInfo.Scoped): BundleSelectionProgress {
        var selectedCount = 0
        var totalCount = 0
        bundle.patchSequence(allowIncompatiblePatches).forEach { patch ->
            if (effectiveLockState(bundle.uid, patch) == PatchLockState.LOCKED_OFF) return@forEach
            totalCount++
            if (isSelected(bundle.uid, patch)) selectedCount++
        }
        return BundleSelectionProgress(selectedCount, totalCount)
    }

    fun isSelected(bundle: Int, patch: PatchInfo): Boolean {
        when (effectiveLockState(bundle, patch)) {
            PatchLockState.LOCKED_ON -> return true
            PatchLockState.LOCKED_OFF -> return false
            PatchLockState.NONE -> Unit
        }
        customPatchSelection?.let { selection ->
            return selection[bundle]?.contains(patch.name) == true
        }
        return currentDefaultSelection[bundle]?.contains(patch.name)
            ?: isDefaultSelected(patch)
    }

    fun lockState(bundle: Int, patch: PatchInfo): PatchLockState = effectiveLockState(bundle, patch)

    fun togglePatch(bundle: Int, patch: PatchInfo) = viewModelScope.launch {
        if (effectiveLockState(bundle, patch) != PatchLockState.NONE) return@launch
        hasModifiedSelection = true

        // Materialize required patches before toggling so removing the last optional patch
        // cannot also remove patches that the UI shows as locked on.
        // Start from exactly the states displayed by isSelected, including defaults for
        // bundles whose default selection has not loaded yet. Only this identity changes.
        val displayedSelection = customPatchSelection ?: currentBundles.associate { scoped ->
            scoped.uid to scoped.patchSequence(allowIncompatiblePatches)
                .filter { isSelected(scoped.uid, it) }
                .map(PatchInfo::name)
                .toPersistentSet()
        }.toPersistentMap()
        val baseSelection = displayedSelection
            .toPatchSelection()
            .applyCurrentAvailability()
            .toPersistentPatchSelection()
        val currentPatches = baseSelection[bundle] ?: persistentSetOf()
        val isSelected = patch.name in currentPatches
        if (!isSelected) {
            val targetType = currentBundles.firstOrNull { it.uid == bundle }?.bundleType
            if (!canMixBundleType(baseSelection, targetType)) {
                notifyMixedPatchBundles()
                return@launch
            }
        }

        val newPatches = if (isSelected) {
            currentPatches.remove(patch.name)
        } else {
            currentPatches.add(patch.name)
        }

        val nextSelection = if (newPatches.isEmpty()) {
            baseSelection.remove(bundle)
        } else {
            baseSelection.put(bundle, newPatches)
        }

        if (!isSelected && hasMixedRevancedPatcherVersions(nextSelection)) {
            notifyMixedRevancedPatcherVersions()
            return@launch
        }

        customPatchSelection = nextSelection
            .toPatchSelection()
            .applyCurrentAvailability()
            .toPersistentPatchSelection()
    }

    fun reset() {
        recordSnapshot(actionLabel(R.string.patch_selection_action_all_defaults))
        patchOptions.clear()
        reconcilePendingOptionInputs()
        customPatchSelection = null
        hasModifiedSelection = false
        app.toast(app.getString(R.string.patch_selection_reset_toast))
    }

    fun deselectAll() {
        recordSnapshot(actionLabel(R.string.patch_selection_action_deselect_all))
        hasModifiedSelection = true
        customPatchSelection = requiredSelection(currentBundles)
        patchOptions.clear()
        reconcilePendingOptionInputs()
        app.toast(app.getString(R.string.patch_selection_deselected_all_toast))
    }

    fun selectAll() = viewModelScope.launch {
        if (currentBundles.isEmpty()) return@launch

        val baseSelection = customPatchSelection ?: currentDefaultSelection
        val currentTypes = selectedBundleTypes(baseSelection)
        if (currentTypes.size > 1) {
            notifyMixedPatchBundles()
            return@launch
        }

        val preferredType = currentTypes.firstOrNull()
        val eligibleBundles = if (preferredType == null) {
            currentBundles
        } else {
            currentBundles.filter { it.bundleType == preferredType }
        }
        if (preferredType == null && currentBundles.map { it.bundleType }.distinct().size > 1) {
            notifyMixedPatchBundles()
            return@launch
        }

        val selections = eligibleBundles
            .associate { bundle ->
                bundle.uid to bundle.patchSequence(allowIncompatiblePatches)
                    .filter { effectiveLockState(bundle.uid, it) != PatchLockState.LOCKED_OFF }
                    .map(PatchInfo::name)
                    .toPersistentSet()
            }
            .filterValues { it.isNotEmpty() }

        if (selections.isEmpty()) {
            app.toast(app.getString(R.string.patch_selection_select_all_empty_toast))
            return@launch
        }
        if (hasMixedRevancedPatcherVersions(selections.toPersistentMap())) {
            notifyMixedRevancedPatcherVersions()
            return@launch
        }

        recordSnapshot(actionLabel(R.string.patch_selection_action_select_all))
        hasModifiedSelection = true
        customPatchSelection = selections.toPersistentMap()
        app.toast(app.getString(R.string.patch_selection_selected_all_toast))
    }

    fun selectBundle(bundleUid: Int, bundleName: String) = viewModelScope.launch {
        val bundle = currentBundles.firstOrNull { it.uid == bundleUid }
        if (bundle == null) {
            app.toast(app.getString(R.string.patch_selection_select_bundle_empty_toast, bundleName))
            return@launch
        }

        val baseSelection = customPatchSelection ?: run {
            if (currentDefaultSelection.isNotEmpty()) currentDefaultSelection
            else defaultPatchSelection.value ?: defaultPatchSelection.first()
        }
        if (!canMixBundleType(baseSelection, bundle.bundleType)) {
            notifyMixedPatchBundles()
            return@launch
        }

        val patches = bundle.patchSequence(allowIncompatiblePatches)
            .filter { effectiveLockState(bundleUid, it) != PatchLockState.LOCKED_OFF }
            .map(PatchInfo::name)
            .toPersistentSet()

        if (patches.isEmpty()) {
            app.toast(app.getString(R.string.patch_selection_select_bundle_empty_toast, bundleName))
            return@launch
        }
        if (hasMixedRevancedPatcherVersions(baseSelection.put(bundleUid, patches))) {
            notifyMixedRevancedPatcherVersions()
            return@launch
        }

        recordSnapshot(actionLabel(R.string.patch_selection_action_select_bundle, bundleName))
        hasModifiedSelection = true
        customPatchSelection = baseSelection.put(bundleUid, patches)
        app.toast(
            app.getString(
                R.string.patch_selection_selected_bundle_toast,
                bundleName
            )
        )
    }

    fun deselectBundle(bundleUid: Int, bundleName: String) = viewModelScope.launch {
        val bundle = currentBundles.firstOrNull { it.uid == bundleUid }
        val baseSelection = customPatchSelection ?: run {
            if (currentDefaultSelection.isNotEmpty()) currentDefaultSelection
            else defaultPatchSelection.value ?: defaultPatchSelection.first()
        }

        val selectedPatches = baseSelection[bundleUid] ?: persistentSetOf()
        if (selectedPatches.isEmpty()) {
        app.toast(
            app.getString(
                R.string.patch_selection_no_selected_bundle_toast,
                bundleName
            )
        )
            return@launch
        }

        recordSnapshot(actionLabel(R.string.patch_selection_action_deselect_bundle, bundleName))
        hasModifiedSelection = true
        val required = bundle?.patchSequence(allowIncompatiblePatches)
            .orEmpty()
            .filter { effectiveLockState(bundleUid, it) == PatchLockState.LOCKED_ON }
            .map(PatchInfo::name)
            .toPersistentSet()
        customPatchSelection = if (required.isEmpty()) {
            baseSelection.remove(bundleUid)
        } else {
            baseSelection.put(bundleUid, required)
        }
        patchOptions.remove(bundleUid)
        reconcilePendingOptionInputs()
        app.toast(
            app.getString(
                R.string.patch_selection_deselected_bundle_toast,
                bundleName
            )
        )
    }

    private fun selectedBundleTypes(selection: PersistentPatchSelection): Set<PatchBundleType> =
        selection.mapNotNull { (uid, patches) ->
            if (patches.isEmpty()) return@mapNotNull null
            currentBundles.firstOrNull { it.uid == uid }?.bundleType
        }.toSet()

    private fun canMixBundleType(
        selection: PersistentPatchSelection,
        targetType: PatchBundleType?,
    ): Boolean {
        if (targetType == null) return true
        val types = selectedBundleTypes(selection)
        return types.isEmpty() || types.size == 1 && types.first() == targetType
    }

    private suspend fun hasMixedRevancedPatcherVersions(selection: PersistentPatchSelection): Boolean =
        patchBundleRepository.selectionHasMixedRevancedPatcherVersions(selection.toPatchSelection())

    private fun notifyMixedPatchBundles() {
        showMixedPatchBundlesDialog = true
    }

    private fun notifyMixedRevancedPatcherVersions() {
        showMixedRevancedPatcherVersionsDialog = true
    }

    fun resetBundleToDefaults(bundleUid: Int, bundleName: String) = viewModelScope.launch {
        val defaultSelection = currentDefaultSelection[bundleUid] ?: persistentSetOf()
        val baseSelection = customPatchSelection ?: run {
            if (currentDefaultSelection.isNotEmpty()) currentDefaultSelection
            else defaultPatchSelection.value ?: defaultPatchSelection.first()
        }

        recordSnapshot(actionLabel(R.string.patch_selection_action_bundle_defaults, bundleName))
        hasModifiedSelection = true
        customPatchSelection = if (defaultSelection.isEmpty()) {
            baseSelection.remove(bundleUid)
        } else {
            baseSelection.put(bundleUid, defaultSelection)
        }
        patchOptions.remove(bundleUid)
        reconcilePendingOptionInputs()
        app.toast(
            app.getString(
                R.string.patch_selection_reset_bundle_toast,
                bundleName
            )
        )
    }

    fun getCustomSelection(): PatchSelection? {
        // Convert persistent collections to standard hash collections because persistent collections are not parcelable.

        return customPatchSelection
            ?.toPatchSelection()
            ?.applyCurrentAvailability()
    }

    fun getOptions(): Options {
        // Convert the collection for the same reasons as in getCustomSelection()

        return patchOptions.mapValues { (_, allPatches) -> allPatches.mapValues { (_, options) -> options.toMap() } }
    }

    fun getOptions(bundle: Int, patch: PatchInfo) = patchOptions[bundle]?.get(patch.name)

    fun setOption(bundle: Int, patch: PatchInfo, key: String, value: Any?) {
        // All patches
        val patchesToOpts = patchOptions.getOrElse(bundle, ::persistentMapOf)
        // The key-value options of an individual patch
        val patchToOpts = patchesToOpts
            .getOrElse(patch.name, ::persistentMapOf)
            .put(key, value)

        patchOptions[bundle] = patchesToOpts.put(patch.name, patchToOpts)
        reconcilePendingOptionInputs()
    }

    fun resetOptions(bundle: Int, patch: PatchInfo) {
        app.toast(app.getString(R.string.patch_options_reset_toast))
        val bundleOptions = patchOptions[bundle] ?: return
        patchOptions[bundle] = bundleOptions.remove(patch.name)
        reconcilePendingOptionInputs()
    }

    private fun pruneSelectionsAndOptions() {
        if (currentBundles.isEmpty()) return

        val availablePatches = currentBundles.associate { bundle ->
            bundle.uid to bundle.patches.map(PatchInfo::name).toSet()
        }

        customPatchSelection?.let { current ->
            val pruned = current.pruneTo(availablePatches)
            if (pruned !== current) {
                customPatchSelection = pruned
                hasModifiedSelection = true
            }
        }

        patchOptions.keys.toList().forEach { bundleUid ->
            val bundleOptions = patchOptions[bundleUid] ?: return@forEach
            val allowed = availablePatches[bundleUid] ?: emptySet()
            val filtered = bundleOptions
                .filterKeys { it in allowed }
                .toPersistentMap()

            when {
                filtered.isEmpty() -> patchOptions.remove(bundleUid)
                filtered.size != bundleOptions.size -> patchOptions[bundleUid] = filtered
            }
        }
        reconcilePendingOptionInputs()
    }

    private fun PatchSelection.applyCurrentAvailability(): PatchSelection = applyAvailability(
        installerType,
        currentBundles.associate { bundle ->
            bundle.uid to bundle.patchSequence(allowIncompatiblePatches)
                .associateBy(PatchInfo::name)
        },
        patchAvailabilityEnabled
    ).removeGmsCoreSupport(removeGmsCoreForMount)

    private fun isDefaultSelected(patch: PatchInfo): Boolean =
        !(removeGmsCoreForMount && patch.name == GMSCORE_SUPPORT_PATCH_NAME) &&
            patch.defaultSelected(installerType, patchAvailabilityEnabled)

    private fun effectiveLockState(bundleUid: Int, patch: PatchInfo): PatchLockState {
        if (removeGmsCoreForMount && patch.name == GMSCORE_SUPPORT_PATCH_NAME) {
            return PatchLockState.LOCKED_OFF
        }
        val lockState = patch.lockState(installerType, patchAvailabilityEnabled)
        // Required patches apply only after their bundle is part of the custom selection.
        if (lockState == PatchLockState.LOCKED_ON &&
            customPatchSelection?.containsKey(bundleUid) == false
        ) {
            return PatchLockState.NONE
        }
        return lockState
    }

    private fun requiredSelection(
        bundles: List<PatchBundleInfo.Scoped>
    ): PersistentPatchSelection = bundles.mapNotNull { bundle ->
        val required = bundle.patchSequence(allowIncompatiblePatches)
            .filter { effectiveLockState(bundle.uid, it) == PatchLockState.LOCKED_ON }
            .map(PatchInfo::name)
            .toPersistentSet()
        bundle.uid.takeIf { required.isNotEmpty() }?.let { it to required }
    }.toMap().toPersistentMap()

    val profiles = patchProfileRepository.profilesForPackageFlow(packageName).flatMapLatest { profiles ->
        flow {
            // Saved profiles must remain selectable while bundle metadata loads
            // or legacy local-bundle remapping performs runtime inspection.
            emit(profiles)
            if (profiles.isEmpty()) return@flow
            emitAll(combine(
                patchBundleRepository.bundleInfoFlow,
                patchBundleRepository.sources
            ) { bundleInfoSnapshot, sources ->
                withContext(Dispatchers.Default) {
                    val signatureMap = bundleInfoSnapshot.mapValues { (_, info) ->
                        info.patches.map { it.name.trim().lowercase() }.toSet()
                    }

                    profiles.map { profile ->
                        val remappedPayload = profile.payload.remapLocalBundles(sources, signatureMap)
                        if (remappedPayload !== profile.payload) {
                            viewModelScope.launch(Dispatchers.Default) {
                                patchProfileRepository.updateRemappedPayload(
                                    uid = profile.uid,
                                    expectedPayload = profile.payload,
                                    payload = remappedPayload
                                )
                            }
                            profile.copy(payload = remappedPayload)
                        } else profile
                    }
                }
            })
        }
    }

    private suspend fun resolveAppVersion(
        selectedBundles: Set<Int>,
        keepExistingVersion: Boolean = false,
        existingProfileVersion: String? = null
    ): String? {
        if (keepExistingVersion) {
            // Honor the profile's stored version, including "all versions" (null).
            return existingProfileVersion
        }

        val existing = appVersion?.takeUnless { it.isBlank() }
        if (existing != null) return existing

        downloadedAppRepository.getLatest(packageName)?.version?.takeUnless { it.isNullOrBlank() }?.let {
            setAppVersion(it)
            return it
        }

        if (preferredBundleUid != null && preferredBundleUid in selectedBundles) {
            if (preferredBundleTargetsAllVersions) return null
            preferredBundleOverride?.let { override ->
                appVersion = override
                return override
            }
            preferredBundleVersion?.let { version ->
                appVersion = version
                return version
            }
            val suggestedForPreferred =
                patchBundleRepository.suggestedVersionsByBundle.first()[preferredBundleUid]?.get(packageName)
            if (!suggestedForPreferred.isNullOrBlank()) {
                setAppVersion(suggestedForPreferred)
                return suggestedForPreferred
            }
        }

        val preferred = preferredAppVersionHint?.takeUnless { it.isBlank() }
        if (preferred != null) return preferred

        val suggestedByBundle = patchBundleRepository.suggestedVersionsByBundle.first()
        val suggested = selectedBundles.firstNotNullOfOrNull { bundleUid ->
            suggestedByBundle[bundleUid]?.get(packageName)
        } ?: preferredBundleVersion ?: patchBundleRepository.suggestedVersions.first()[packageName]

        return suggested?.takeUnless { it.isBlank() }?.also { resolved ->
            setAppVersion(resolved)
        }
    }

    suspend fun savePatchProfile(
        name: String,
        selectedBundles: Set<Int>,
        existingProfileId: Int?,
        overrideAppVersion: String? = null,
        keepExistingProfileVersion: Boolean = false,
        existingProfileVersion: String? = null
    ): Boolean = withContext(Dispatchers.Default) {
        if (selectedBundles.isEmpty()) return@withContext false
        val resolvedAppVersion = overrideAppVersion
            ?: resolveAppVersion(
                selectedBundles,
                keepExistingVersion = keepExistingProfileVersion,
                existingProfileVersion = existingProfileVersion
            )
        val snapshot = snapshotPatchProfileState(selectedBundles)
        val displayNames = bundleDisplayNames.first()
        val endpoints = bundleEndpoints.first()
        val identifiers = bundleIdentifiers.first()

        val bundles = snapshot.bundleOrder.map { bundleUid ->
            val patches = snapshot.selection[bundleUid]?.toList().orEmpty()
            val serializedOptions = serializeOptions(bundleUid, patches.toSet(), snapshot.options)
            PatchProfilePayload.Bundle(
                bundleUid = bundleUid,
                patches = patches,
                options = serializedOptions.values,
                displayName = displayNames[bundleUid],
                sourceEndpoint = endpoints[bundleUid],
                sourceName = identifiers[bundleUid],
                optionDisplayInfo = serializedOptions.displayInfo
            )
        }

        val payload = PatchProfilePayload(bundles)
        try {
            withContext(Dispatchers.IO) {
                if (existingProfileId != null) {
                    val updated = patchProfileRepository.updateProfile(
                        uid = existingProfileId,
                        packageName = packageName,
                        appVersion = resolvedAppVersion,
                        name = name,
                        payload = payload
                    )
                    if (updated != null) {
                        withContext(Dispatchers.Main) {
                            app.toast(app.getString(R.string.patch_profile_updated_toast, name))
                        }
                    } else {
                        patchProfileRepository.createProfile(
                            packageName = packageName,
                            appVersion = resolvedAppVersion,
                            name = name,
                            payload = payload
                        )
                        withContext(Dispatchers.Main) {
                            app.toast(app.getString(R.string.patch_profile_saved_toast, name))
                        }
                    }
                } else {
                    patchProfileRepository.createProfile(
                        packageName = packageName,
                        appVersion = resolvedAppVersion,
                        name = name,
                        payload = payload
                    )
                    withContext(Dispatchers.Main) {
                        app.toast(app.getString(R.string.patch_profile_saved_toast, name))
                    }
                }
            }
            true
        } catch (duplicate: DuplicatePatchProfileNameException) {
            withContext(Dispatchers.Main) {
                app.toast(app.getString(R.string.patch_profile_duplicate_toast, duplicate.profileName))
            }
            false
        } catch (t: Exception) {
            Log.e(tag, "Failed to save patch profile", t)
            withContext(Dispatchers.Main) {
                app.toast(app.getString(R.string.patch_profile_save_failed_toast))
            }
            false
        }
    }

    suspend fun previewResolvedAppVersion(selectedBundles: Set<Int>): String? =
        resolveAppVersion(selectedBundles)

    private data class PatchProfileStateSnapshot(
        val bundleOrder: List<Int>,
        val selection: PatchSelection,
        val options: Options
    )

    private fun snapshotPatchProfileState(selectedBundles: Set<Int>): PatchProfileStateSnapshot {
        val liveSelection = (customPatchSelection ?: currentDefaultSelection)
            .toPatchSelection()
            .applyCurrentAvailability()
        val knownBundleOrder = currentBundles.map(PatchBundleInfo.Scoped::uid).filter { it in selectedBundles }
        val remainingBundles = selectedBundles.filterNot { it in knownBundleOrder }
        val bundleOrder = knownBundleOrder + remainingBundles
        val selection = bundleOrder.associateWith { bundleUid -> liveSelection[bundleUid].orEmpty() }
        val liveOptions = getOptions()
        val options = bundleOrder.mapNotNull { bundleUid ->
            val selectedPatches = selection[bundleUid].orEmpty()
            val filteredBundleOptions = liveOptions[bundleUid]
                ?.let { bundleOptions ->
                    if (selectedPatches.isEmpty()) bundleOptions
                    else bundleOptions.filterKeys { it in selectedPatches }
                }
                ?.takeUnless { it.isEmpty() }
                ?: return@mapNotNull null
            bundleUid to filteredBundleOptions
        }.toMap()

        return PatchProfileStateSnapshot(
            bundleOrder = bundleOrder,
            selection = selection,
            options = options
        )
    }

    private data class SerializedOptions(
        val values: Map<String, Map<String, StoredOption.SerializedValue>>,
        val displayInfo: Map<String, Map<String, PatchProfilePayload.OptionDisplayInfo>>
    )

    private fun serializeOptions(
        bundleUid: Int,
        selectedPatches: Set<String>,
        options: Options
    ): SerializedOptions {
        val bundleOptions = options[bundleUid] ?: return SerializedOptions(emptyMap(), emptyMap())
        val serializedOptions = mutableMapOf<String, MutableMap<String, StoredOption.SerializedValue>>()
        val displayInfo = mutableMapOf<String, MutableMap<String, PatchProfilePayload.OptionDisplayInfo>>()
        val bundleMetadata = currentBundles.firstOrNull { it.uid == bundleUid }
        val optionMetadataByPatch = bundleMetadata?.patches?.associateBy { it.name } ?: emptyMap()

        bundleOptions.forEach { (patchName, optionValues) ->
            if (selectedPatches.isNotEmpty() && patchName !in selectedPatches) return@forEach
            val serializedForPatch = mutableMapOf<String, StoredOption.SerializedValue>()
            val displayInfoForPatch = mutableMapOf<String, PatchProfilePayload.OptionDisplayInfo>()
            val optionMetadata = optionMetadataByPatch[patchName]?.options?.associateBy { it.key } ?: emptyMap()

            optionValues.forEach { (key, value) ->
                try {
                    serializedForPatch[key] = StoredOption.SerializedValue.fromValue(value)
                    val label = optionMetadata[key]?.title ?: key
                    val displayValue = formatDisplayValue(value)
                    displayInfoForPatch[key] = PatchProfilePayload.OptionDisplayInfo(label, displayValue)
                } catch (e: StoredOption.SerializationException) {
                    Log.w(
                        tag,
                        "Failed to serialize option $patchName:$key for bundle $bundleUid",
                        e
                    )
                }
            }

            if (serializedForPatch.isNotEmpty()) {
                serializedOptions[patchName] = serializedForPatch
                if (displayInfoForPatch.isNotEmpty()) {
                    displayInfo[patchName] = displayInfoForPatch
                }
            }
        }

        return SerializedOptions(
            values = serializedOptions.mapValues { entry -> entry.value.toMap() },
            displayInfo = displayInfo.mapValues { entry -> entry.value.toMap() }
        )
    }

    private fun formatDisplayValue(value: Any?): String = when (value) {
        null -> ""
        is Boolean, is Number -> value.toString()
        is String -> value
        is List<*> -> value.joinToString(", ", prefix = "[", postfix = "]")
        else -> value.toString()
    }

    private fun setAppVersion(value: String?) {
        val normalized = value?.takeUnless { it.isBlank() }
        if (normalized == appVersion) return
        appVersion = normalized
        appVersionState.value = normalized
    }

    fun dismissDialogs() {
        optionsDialog = null
        compatibleVersions.clear()
    }

    fun dismissMixedPatchBundlesDialog() {
        showMixedPatchBundlesDialog = false
    }

    fun dismissMixedRevancedPatcherVersionsDialog() {
        showMixedRevancedPatcherVersionsDialog = false
    }

    fun openIncompatibleDialog(incompatiblePatch: PatchInfo) {
        val compatiblePackage = incompatiblePatch.compatiblePackages
            ?.find { it.packageName == packageName }
            ?: return
        compatibleVersions.clear()
        compatibleVersions.addAll(
            compatiblePackage.versions.orEmpty().map { version ->
                SupportedVersionInfo(
                    version = version,
                    experimental = compatiblePackage.experimentalVersions?.contains(version) == true,
                    versionCodes = compatiblePackage.versionCodes?.get(version).orEmpty()
                )
            }
        )
    }

    fun toggleFlag(flag: Int) {
        filter = filter xor flag
    }

    fun toggleNewPatchesSection() {
        showNewPatchesSection = !showNewPatchesSection
        viewModelScope.launch {
            prefs.patchSelectionShowNewPatches.update(showNewPatchesSection)
        }
    }

    private suspend fun loadNewPatchNames(
        bundles: List<PatchBundleInfo.Scoped>
    ): Map<Int, Set<String>> = withContext(Dispatchers.Default) {
        bundles.mapNotNull { bundle ->
            val seenPatchNames = patchSelectionRepository.getSeenPatches(packageName, bundle.uid)
                ?: return@mapNotNull null
            val currentPatchNames = bundle.patches.map(PatchInfo::name).toSet()
            val newPatchNames = currentPatchNames - seenPatchNames
            if (newPatchNames.isEmpty()) null else bundle.uid to newPatchNames
        }.toMap()
    }

    fun toggleTypeFlag(flag: Int) {
        val typeMask = SHOW_UNIVERSAL or SHOW_NON_UNIVERSAL
        val typeActive = filter and SHOW_TYPE_FILTER != 0
        val next = if (!typeActive) {
            (filter and typeMask.inv()) or SHOW_TYPE_FILTER or flag
        } else {
            val toggled = filter xor flag
            if (toggled and typeMask == 0) {
                toggled and SHOW_TYPE_FILTER.inv()
            } else {
                toggled
            }
        }
        filter = next
    }

    private fun resolveInitialFilter(handle: SavedStateHandle, prefs: PreferencesManager): Int {
        val prefValue = prefs.patchSelectionFilterFlags.getBlocking()
        val stored = handle.get<Any?>("filter")
        val resolved = if (prefValue >= 0) {
            prefValue
        } else {
            when (stored) {
                is Int -> stored
                is MutableState<*> -> defaultFilterFlags()
                else -> defaultFilterFlags()
            }
        }
        val normalized = normalizeTypeFilter(resolved)
        handle["filter"] = normalized
        return normalized
    }

    fun undoAction() {
        val entry = history.removeLastOrNull() ?: return
        future.addLast(HistoryEntry(currentSnapshot(), entry.description))
        applySnapshot(entry.snapshot)
        updateHistoryState()
        app.toast(app.getString(R.string.patch_selection_history_undo, entry.description))
    }

    fun redoAction() {
        val entry = future.removeLastOrNull() ?: return
        history.addLast(HistoryEntry(currentSnapshot(), entry.description))
        applySnapshot(entry.snapshot)
        updateHistoryState()
        app.toast(app.getString(R.string.patch_selection_history_redo, entry.description))
    }

    private fun recordSnapshot(actionDescription: String) {
        history.addLast(HistoryEntry(currentSnapshot(), actionDescription))
        future.clear()
        reconcilePendingOptionInputs()
        updateHistoryState()
    }

    private fun currentSnapshot() = SelectionSnapshot(
        customPatchSelection,
        patchOptions.toOptions()
    )

    private fun applySnapshot(snapshot: SelectionSnapshot) {
        customPatchSelection = snapshot.selection
        patchOptions.clear()
        snapshot.options.forEach { (bundleUid, patchMap) ->
            val persistent = patchMap.mapValues { (_, options) ->
                options.toPersistentMap()
            }.toPersistentMap()
            patchOptions[bundleUid] = persistent
        }
        reconcilePendingOptionInputs()
        hasModifiedSelection = true
    }

    private fun reconcilePendingOptionInputs() {
        val currentPendingInputs = patchOptionInputManager.pendingInputsIn(
            listOf(
                patchOptions,
                history.map { it.snapshot.options },
                future.map { it.snapshot.options }
            )
        )
        pendingOptionInputOwnership.reconcile(currentPendingInputs)
    }

    fun transferPendingOptionInputs(): Set<String> {
        val transfer = pendingOptionInputOwnership.transfer(
            patchOptionInputManager.pendingInputsIn(patchOptions)
        )
        return transfer.transferred
    }

    override fun onCleared() {
        super.onCleared()
        pendingOptionInputOwnership.releaseAll()
    }

    private fun updateHistoryState() {
        canUndo = history.isNotEmpty()
        canRedo = future.isNotEmpty()
    }

    private fun PersistentOptions.toOptions(): Options =
        mapValues { (_, bundleOptions) ->
            bundleOptions.mapValues { (_, options) -> options.toMap() }.toMap()
        }.toMap()

    private fun actionLabel(@StringRes labelRes: Int, vararg args: Any?): String =
        app.getString(labelRes, *args)

    private suspend fun resolveSelectedAppInfo(): PackageInfo? = runCatching {
        withContext(Dispatchers.IO) {
            when (val source = selectedApp) {
                is SelectedApp.Local -> {
                    if (SplitApkPreparer.isSplitArchive(source.file)) {
                        val extracted = SplitApkInspector.extractRepresentativeApk(
                            source.file,
                            filesystem.tempDir
                        ) ?: return@withContext null
                        try {
                            pm.getPackageInfo(extracted.file)
                        } finally {
                            extracted.cleanup()
                        }
                    } else {
                        pm.getPackageInfo(source.file)
                    }
                }

                is SelectedApp.Installed -> pm.getPackageInfo(source.packageName)
                is SelectedApp.Download, is SelectedApp.Search -> null
            }
        }
    }.onFailure { error ->
        Log.w(tag, "Failed to resolve selected app package info", error)
    }.getOrNull()

    private fun restoreInitialCustomPatchSelection(
        initialSelection: PatchSelection?
    ): PersistentPatchSelection? {
        if (initialSelection != null) {
            return initialSelection.toPersistentPatchSelection()
        }

        val stored = try {
            savedStateHandle.get<Any?>(customPatchSelectionKey)
        } catch (exception: Exception) {
            Log.w(tag, "Failed to restore custom patch selection; clearing corrupt saved state", exception)
            savedStateHandle.remove<Any?>(customPatchSelectionKey)
            return null
        }

        val restored = stored.toPersistentPatchSelectionOrNull()
        if (stored != null && restored == null) {
            // Self-heal invalid saved state to prevent repeat crashes on next launch.
            savedStateHandle.remove<Any?>(customPatchSelectionKey)
        }
        return restored
    }

    private fun defaultFilterFlags(): Int =
        if (allowIncompatiblePatches || !suggestedVersionSafeguardEnabled)
            SHOW_INCOMPATIBLE
        else
            0

    private fun normalizeTypeFilter(flags: Int): Int {
        val typeMask = SHOW_UNIVERSAL or SHOW_NON_UNIVERSAL
        return if (flags and typeMask != 0) {
            flags or SHOW_TYPE_FILTER
        } else {
            flags
        }
    }

    companion object {
        const val SHOW_INCOMPATIBLE = 1 // 2^0
        const val SHOW_UNIVERSAL = 2 // 2^1
        const val SHOW_NON_UNIVERSAL = 4 // 2^2
        const val SHOW_TYPE_FILTER = 8 // 2^3

        private val optionsSaver: Saver<PersistentOptions, Options> = snapshotStateMapSaver(
            // Patch name -> Options
            valueSaver = persistentMapSaver(
                // Option key -> Option value
                valueSaver = persistentMapSaver()
            )
        )
    }
}

// Versions of other types, but utilizing persistent/observable collection types.
private typealias PersistentOptions = SnapshotStateMap<Int, PersistentMap<String, PersistentMap<String, Any?>>>
private typealias PersistentPatchSelection = PersistentMap<Int, PersistentSet<String>>

private fun PatchSelection.toPersistentPatchSelection(): PersistentPatchSelection =
    mapValues { (_, v) -> v.toPersistentSet() }.toPersistentMap()

private fun PersistentPatchSelection.toPatchSelection(): PatchSelection =
    mapValues { (_, v) -> v.toSet() }

private fun Any?.toPersistentPatchSelectionOrNull(): PersistentPatchSelection? = when (this) {
    null -> null
    is Nullable<*> -> when (val value = inner) {
        null -> null
        is Map<*, *> -> value.toPatchSelectionOrNull()?.toPersistentPatchSelection()
        else -> null
    }
    is Map<*, *> -> toPatchSelectionOrNull()?.toPersistentPatchSelection()
    else -> null
}

private fun Map<*, *>.toPatchSelectionOrNull(): PatchSelection? {
    if (isEmpty()) return emptyMap()

    val parsed = mutableMapOf<Int, Set<String>>()
    var validEntries = 0

    for (entry in entries) {
        val rawBundleUid: Any? = entry.key
        val rawPatchNames: Any? = entry.value

        val bundleUid = when (rawBundleUid) {
            is Int -> rawBundleUid
            is Number -> rawBundleUid.toInt()
            is String -> rawBundleUid.toIntOrNull()
            else -> null
        } ?: continue

        val patchNames = when (rawPatchNames) {
            is Set<*> -> rawPatchNames.mapNotNull { it as? String }.toSet()
            is Collection<*> -> rawPatchNames.mapNotNull { it as? String }.toSet()
            is Array<*> -> {
                val parsedNames = mutableSetOf<String>()
                for (patchName in rawPatchNames) {
                    if (patchName is String) parsedNames += patchName
                }
                parsedNames
            }
            else -> null
        } ?: continue

        parsed[bundleUid] = patchNames
        validEntries++
    }

    return if (validEntries > 0) parsed else null
}

private fun PatchBundleInfo.Scoped.withoutUniversalPatches(): PatchBundleInfo.Scoped {
    if (universal.isEmpty()) return this

    val filteredPatches = patches.filter { it.compatiblePackages != null }
    return copy(
        patches = filteredPatches,
        universal = emptyList()
    )
}

private fun PersistentPatchSelection.pruneTo(
    available: Map<Int, Set<String>>
): PersistentPatchSelection {
    var changed = false
    val filtered = buildMap<Int, PersistentSet<String>> {
        this@pruneTo.forEach { (bundleUid, patches) ->
            val allowed = available[bundleUid] ?: run {
                if (patches.isNotEmpty()) changed = true
                return@forEach
            }
            val kept = patches.filter { it in allowed }.toPersistentSet()
            if (kept.size != patches.size) changed = true
            if (kept.isNotEmpty()) put(bundleUid, kept)
        }
    }

    if (!changed) return this
    if (filtered.isEmpty()) return persistentMapOf()
    return filtered.toPersistentMap()
}
