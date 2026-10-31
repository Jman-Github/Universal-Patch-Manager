package app.universal.patch.manager.ui.screen

import app.universal.patch.manager.util.openUrl
import android.content.pm.PackageInfo
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.horizontalScroll
import app.universal.patch.manager.util.consumeHorizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowRight
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universal.patch.manager.R
import app.universal.patch.manager.data.platform.Filesystem
import app.universal.patch.manager.data.platform.NetworkInfo
import app.universal.patch.manager.data.room.apps.downloaded.DownloadedApp
import app.universal.patch.manager.data.room.apps.installed.InstallType
import app.universal.patch.manager.data.room.apps.installed.InstalledApp
import app.universal.patch.manager.domain.repository.PatchBundleRepository
import app.universal.patch.manager.network.downloader.ApkDownloadHelperContract
import app.universal.patch.manager.network.downloader.LoadedDownloaderPlugin
import app.universal.patch.manager.ui.component.AlertDialogExtended
import app.universal.patch.manager.ui.component.AppInfo
import app.universal.patch.manager.ui.component.AppTopBar
import app.universal.patch.manager.ui.component.AppVersion
import app.universal.patch.manager.ui.component.suggestedVersionLabel
import app.universal.patch.manager.ui.component.AppliedPatchBundleUi
import app.universal.patch.manager.ui.component.AppliedPatchesDialog
import app.universal.patch.manager.ui.component.ColumnWithScrollbar
import app.universal.patch.manager.ui.component.ExperimentalVersionBadge
import app.universal.patch.manager.ui.component.ExpandableText
import app.universal.patch.manager.ui.component.LoadingIndicator
import app.universal.patch.manager.ui.component.NonSuggestedVersionDialog
import app.universal.patch.manager.ui.component.UniversalFallbackVersionDialog
import app.universal.patch.manager.ui.component.NotificationCard
import app.universal.patch.manager.ui.component.haptics.HapticExtendedFloatingActionButton
import app.universal.patch.manager.ui.component.SafeguardHintCard
import app.universal.patch.manager.ui.component.patches.PathSelectorDialog
import app.universal.patch.manager.ui.component.RememberedGetContent
import app.universal.patch.manager.ui.component.toPickerDirectoryUri
import app.universal.patch.manager.ui.model.SelectedApp
import app.universal.patch.manager.ui.viewmodel.SelectedAppInfoViewModel
import app.universal.patch.manager.ui.viewmodel.BundleRecommendationDetail
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import app.universal.patch.manager.util.EventEffect
import app.universal.patch.manager.util.Options
import app.universal.patch.manager.util.PatchSelection
import app.universal.patch.manager.util.enabled
import app.universal.patch.manager.util.isAllowedApkFile
import app.universal.patch.manager.util.toast
import app.universal.patch.manager.util.transparentListItemColors
import java.util.Locale
import java.io.File
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import app.universal.patch.manager.ui.component.CenteredDialogTitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectedAppInfoScreen(
    onPatchSelectorClick: (SelectedApp, PatchSelection?, Options) -> Unit,
    onRequiredOptions: (SelectedApp, PatchSelection?, Options) -> Unit,
    onPatchClick: () -> Unit,
    onBackClick: () -> Unit,
    batchQueueMode: Boolean = false,
    vm: SelectedAppInfoViewModel
) {
    val context = LocalContext.current
    val networkInfo = koinInject<NetworkInfo>()
    val patchBundleRepository: PatchBundleRepository = koinInject()
    val networkConnected = remember { networkInfo.isConnected() }
    val networkMetered = remember { !networkInfo.isUnmetered() }

    val packageName = vm.selectedApp.packageName
    val version = vm.selectedApp.version
    val bundles by vm.bundleInfoFlow.collectAsStateWithLifecycle(emptyList())
    val selectedBundleUid by vm.selectedBundleUidFlow.collectAsStateWithLifecycle(null)
    val selectedBundleOverride by vm.selectedBundleVersionOverrideFlow.collectAsStateWithLifecycle(null)
    val bundleTargetsAllVersions by vm.preferredBundleTargetsAllVersionsFlow.collectAsStateWithLifecycle(false)
    val preferredBundleVersion by vm.preferredBundleVersionFlow.collectAsStateWithLifecycle(null)
    val bundleRecommendationDetails by vm.bundleRecommendationDetailsFlow.collectAsStateWithLifecycle(emptyList())
    var showBundleRecommendationDialog by rememberSaveable { mutableStateOf(false) }
    var showMixedBundleDialog by rememberSaveable { mutableStateOf(false) }
    var showMixedRevancedPatcherDialog by rememberSaveable { mutableStateOf(false) }
    var showPatchSummaryDialog by rememberSaveable { mutableStateOf(false) }
    var showPatchModeDialog by rememberSaveable { mutableStateOf(false) }

    vm.removedPatchesNotice?.let { notice ->
        AlertDialog(
            onDismissRequest = vm::dismissRemovedPatchesNotice,
            title = {
                CenteredDialogTitle(
                    pluralStringResource(
                        R.plurals.removed_saved_patches_title,
                        notice.patchNames.size,
                        notice.patchNames.size
                    )
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        pluralStringResource(
                            R.plurals.removed_saved_patches_message_quantity,
                            notice.patchNames.size
                        )
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(notice.patchNames) { patchName ->
                            Text(text = "• $patchName")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = vm::dismissRemovedPatchesNotice) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }

    val allowIncompatiblePatches by vm.prefs.disablePatchVersionCompatCheck.getAsState()
    val suggestedVersionSafeguard by vm.prefs.suggestedVersionSafeguard.getAsState()
    val patchAvailabilityEnabled by vm.prefs.patchAvailabilityEnabled.getAsState()
    val customBackgroundImageUri by vm.prefs.customBackgroundImageUri.getAsState()
    val useCardStylePageItems = customBackgroundImageUri.isNotBlank()
    val bundleRecommendationsEnabled = allowIncompatiblePatches && !suggestedVersionSafeguard
    val patches = vm.getPatches(bundles, allowIncompatiblePatches)
    val selectedPatchCount = patches.values.sumOf { it.size }
    val downloadedApps by vm.downloadedApps.collectAsStateWithLifecycle(emptyList())
    val resolveNavigationVersion: (SelectedApp) -> SelectedApp = remember(
        downloadedApps,
        vm.selectedAppInfo,
        vm.selectedApp,
        selectedBundleOverride,
        preferredBundleVersion,
        bundleTargetsAllVersions,
        selectedBundleUid
    ) {
        { app ->
            val preferredVersion = if (bundleTargetsAllVersions && selectedBundleUid != null) {
                null
            } else {
                selectedBundleOverride?.takeUnless { it.isBlank() }
                    ?: preferredBundleVersion?.takeUnless { it.isBlank() }
            }
            val fileVersion = when (app) {
                is SelectedApp.Local, is SelectedApp.Download -> app.version?.takeUnless { it.isNullOrBlank() }
                else -> null
            }
            val versionOverride = fileVersion
                ?: preferredVersion
                ?: vm.selectedAppInfo?.versionName?.takeUnless { it.isNullOrBlank() }
                ?: app.version?.takeUnless { it.isNullOrBlank() }
                ?: downloadedApps.firstOrNull()?.version?.takeUnless { it.isNullOrBlank() }
            if (versionOverride.isNullOrBlank()) return@remember app
            when (app) {
                is SelectedApp.Download -> app.copy(
                    version = versionOverride,
                    versionCode = app.versionCode.takeIf { app.version == versionOverride }
                )
                is SelectedApp.Search -> app.copy(
                    version = versionOverride,
                    versionCode = app.versionCode.takeIf { app.version == versionOverride }
                )
                is SelectedApp.Local -> app.copy(
                    version = versionOverride,
                    versionCode = app.versionCode.takeIf { app.version == versionOverride }
                )
                is SelectedApp.Installed -> app.copy(
                    version = versionOverride,
                    versionCode = app.versionCode.takeIf { app.version == versionOverride }
                )
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = vm::handlePluginActivityResult
    )
    EventEffect(flow = vm.launchActivityFlow) { intent ->
        try {
            launcher.launch(intent)
        } catch (error: RuntimeException) {
            vm.handlePluginActivityLaunchFailure(error)
        }
    }
    val fs = koinInject<Filesystem>()
    val useCustomFilePicker by vm.prefs.useCustomFilePicker.getAsState()
    val selectedAppApkInputDirectory by vm.prefs.selectedAppApkInputLastDirectory.getAsState()
    val pickerScope = rememberCoroutineScope()
    val storageRoots = remember { fs.storageRoots() }
    var showStorageDialog by rememberSaveable { mutableStateOf(false) }
    val (permissionContract, permissionName) = remember { fs.permissionContract() }
    val permissionLauncher =
        rememberLauncherForActivityResult(permissionContract) { granted ->
            if (granted) {
                showStorageDialog = true
            }
        }
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = RememberedGetContent {
            selectedAppApkInputDirectory.takeIf(String::isNotBlank)?.let(Uri::parse)
        }
    ) { uri ->
        if (uri != null) {
            pickerScope.launch {
                vm.prefs.selectedAppApkInputLastDirectory.update(
                    uri.toPickerDirectoryUri().toString()
                )
            }
            vm.handleStorageResult(uri)
        }
    }
    val openStoragePicker = {
        if (useCustomFilePicker) {
            if (fs.hasStoragePermission()) {
                showStorageDialog = true
            } else {
                permissionLauncher.launch(permissionName)
            }
        } else {
            openDocumentLauncher.launch("application/*")
        }
    }
    LaunchedEffect(useCustomFilePicker) {
        if (!useCustomFilePicker) {
            showStorageDialog = false
        }
    }
    EventEffect(flow = vm.requestStorageSelection) {
        openStoragePicker()
    }
    if (showStorageDialog && useCustomFilePicker) {
        PathSelectorDialog(
            roots = storageRoots,
            onSelect = { path ->
                showStorageDialog = false
                vm.handleStorageFile(path?.let { File(it.toString()) })
            },
            fileFilter = ::isAllowedApkFile,
            allowDirectorySelection = false,
            lastDirectoryPreference = vm.prefs.selectedAppApkInputLastDirectory
        )
    }
    val composableScope = rememberCoroutineScope()
    val selectedPatchSummary = remember(bundles, patches, context) {
        if (patches.isEmpty()) return@remember emptyList<AppliedPatchBundleUi>()
        patches.entries.mapNotNull { (bundleUid, patchNames) ->
            if (patchNames.isEmpty()) return@mapNotNull null
            val bundle = bundles.firstOrNull { it.uid == bundleUid }
            val patchInfos = bundle?.patches
                ?.filter { it.name in patchNames }
                ?.distinctBy { it.name }
                ?.sortedBy { it.name }
                ?: emptyList()
            val missingNames = patchNames
                .filterNot { name -> patchInfos.any { it.name == name } }
                .distinct()
                .sorted()
            val fallbackName = if (bundleUid == 0) {
                context.getString(R.string.patches_name_default)
            } else {
                context.getString(R.string.patches_name_fallback)
            }
            val title = bundle?.name ?: "$fallbackName (#$bundleUid)"

            AppliedPatchBundleUi(
                uid = bundleUid,
                title = title,
                version = bundle?.version,
                patchInfos = patchInfos,
                fallbackNames = missingNames,
                bundleAvailable = bundle != null
            )
        }.sortedBy { it.title.lowercase(Locale.ROOT) }
    }
    val selectedPatchOptions = vm.getOptionsFiltered(bundles)
    val launchPatchFlow: () -> Unit = launch@{
        if (selectedPatchCount == 0) {
            context.toast(context.getString(R.string.no_patches_selected))
            return@launch
        }

        composableScope.launch {
            if (patchBundleRepository.selectionHasMixedBundleTypes(patches)) {
                showMixedBundleDialog = true
                return@launch
            }
            if (patchBundleRepository.selectionHasMixedRevancedPatcherVersions(patches)) {
                showMixedRevancedPatcherDialog = true
                return@launch
            }
            if (!vm.hasSetRequiredOptions(patches)) {
                val optionsSnapshot = vm.awaitOptions()
                onRequiredOptions(
                    vm.selectedApp,
                    vm.getCustomPatches(bundles, allowIncompatiblePatches),
                    optionsSnapshot
                )
                return@launch
            }

            val showPatchSummaryDialogSetting = vm.prefs.showPatchSelectionSummary.get()
            if (showPatchSummaryDialogSetting) {
                showPatchSummaryDialog = true
                return@launch
            }

            onPatchClick()
        }
    }

    val error by vm.errorFlow.collectAsStateWithLifecycle(null)
    val profileLaunchState by vm.profileLaunchState.collectAsStateWithLifecycle(null)

    vm.universalFallbackDialogSubject?.let {
        UniversalFallbackVersionDialog(
            onContinue = vm::continueWithUniversalFallbackSelection,
            onDismiss = vm::dismissUniversalFallbackDialog
        )
    }

    vm.nonSuggestedVersionDialogSubject?.let { local ->
        NonSuggestedVersionDialog(
            suggestedVersion = vm.nonSuggestedVersionDialogSuggestedVersion
                ?.takeUnless { it.isBlank() }
                ?: local.version,
            suggestedVersionCodes = vm.nonSuggestedVersionDialogSuggestedVersionCodes,
            requiresUniversalPatchesEnabled = vm.nonSuggestedVersionDialogRequiresUniversalEnabled,
            onDismiss = vm::dismissNonSuggestedVersionDialog
        )
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    LaunchedEffect(profileLaunchState, vm.selectedApp) {
        val launchState = profileLaunchState ?: return@LaunchedEffect
        if (!vm.shouldAutoLaunchProfile()) return@LaunchedEffect
        val appSource = vm.selectedApp
        if (appSource is SelectedApp.Search) return@LaunchedEffect
        val autoPatch = vm.shouldAutoPatchProfile()
        vm.markProfileAutoLaunchConsumed()
        if (autoPatch) {
            launchPatchFlow()
        } else {
            onPatchSelectorClick(resolveNavigationVersion(appSource), launchState.selection, launchState.options)
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(
                    if (batchQueueMode) R.string.batch_queue_configure_app
                    else R.string.preparing_to_patch
                ),
                scrollBehavior = scrollBehavior,
                onBackClick = onBackClick
            )
        },
        floatingActionButton = {
            if (error != null) return@Scaffold

            val actionLabel = stringResource(
                if (batchQueueMode) R.string.save else R.string.patch
            )
            HapticExtendedFloatingActionButton(
                text = { Text(actionLabel) },
                icon = {
                    Icon(
                        if (batchQueueMode) Icons.Outlined.Save else Icons.Default.AutoFixHigh,
                        actionLabel
                    )
                },
                onClick = { launchPatchFlow() }
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { paddingValues ->
        val plugins by vm.plugins.collectAsStateWithLifecycle(emptyList())
        val apkDownloadHelpers by vm.apkDownloadHelpers.collectAsStateWithLifecycle(emptyList())

        if (vm.showSourceSelector) {
            val requiredVersion by vm.requiredVersion.collectAsStateWithLifecycle(null)
            val selectionRecommendedVersion by vm.selectionRecommendedVersionFlow.collectAsStateWithLifecycle(null)
            val effectiveVersion =
                if (bundleTargetsAllVersions && selectedBundleUid != null) null
                else selectedBundleOverride?.takeUnless { it.isBlank() }
                    ?: preferredBundleVersion
                    ?: selectionRecommendedVersion
                    ?: vm.desiredVersion

            AppSourceSelectorDialog(
                plugins = plugins,
                apkDownloadHelpers = apkDownloadHelpers,
                installedApp = vm.installedAppData,
                searchApp = SelectedApp.Search(
                    vm.packageName,
                    effectiveVersion
                ),
                activeSearchJob = vm.activePluginAction,
                hasRoot = vm.hasRoot,
                downloadedApps = downloadedApps,
                includeAutoOption = !vm.sourceSelectionRequired,
                requiredVersion = requiredVersion,
                onDismissRequest = vm::dismissSourceSelector,
                onSelectPlugin = vm::searchUsingPlugin,
                onSelectApkDownloadHelper = vm::searchUsingApkDownloadHelper,
                onSelectDownloaded = vm::selectDownloadedApp,
                onSelectLocal = vm::requestLocalSelection,
                onSelect = {
                    vm.selectedApp = it
                    vm.dismissSourceSelector()
                }
            )
        }

        ColumnWithScrollbar(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            AppInfo(
                appInfo = vm.selectedAppInfo,
                labelOverride = vm.selectedAppInfoLabelOverride,
                iconOverride = vm.selectedAppInfoIconOverride,
                placeholderLabel = packageName,
                packageName = packageName,
                placeholderMetaLines = 0,
                showExtraContentWhenLoading = true
            ) {
                Text(
                    packageName,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                val versionLabel = when {
                    bundleTargetsAllVersions && selectedBundleUid != null ->
                        stringResource(R.string.bundle_version_all_versions)
                    version != null -> version
                    else -> stringResource(R.string.selected_app_meta_any_version)
                }
                AppVersion(
                    appInfo = vm.selectedAppInfo,
                    versionName = versionLabel,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            PageItem(
                R.string.patch_selector_item,
                stringResource(
                    R.string.patch_selector_item_description,
                    selectedPatchCount
                ),
                useCardStyle = useCardStylePageItems,
                onClick = {
                    composableScope.launch {
                        val optionsSnapshot = vm.awaitOptions()
                        onPatchSelectorClick(
                            resolveNavigationVersion(vm.selectedApp),
                            vm.getCustomPatches(
                                bundles,
                                allowIncompatiblePatches
                            ),
                            optionsSnapshot
                        )
                    }
                }
            )
            if (vm.hasRoot && patchAvailabilityEnabled) {
                PageItem(
                    R.string.patch_mode_title,
                    stringResource(
                        if (vm.usingMountInstall) R.string.patch_mode_mount
                        else R.string.patch_mode_standard
                    ),
                    useCardStyle = useCardStylePageItems,
                    onClick = { showPatchModeDialog = true }
                )
            }
            if (bundleRecommendationDetails.isNotEmpty()) {
                val selectedDetail = bundleRecommendationDetails.firstOrNull { it.bundleUid == selectedBundleUid }
                val overrideVersion = selectedBundleOverride
                val bundleSummary = if (selectedDetail != null) {
                    var versionIsExperimental = false
                    val versionLabel = when {
                        overrideVersion != null ->
                            suggestedVersionLabel(
                                versionName = overrideVersion,
                                versionCodes = selectedDetail.versionCodesFor(overrideVersion),
                                displayVersion = stringResource(R.string.version_label, overrideVersion)
                            ).also {
                                versionIsExperimental = selectedDetail.otherSupportedVersions
                                    .firstOrNull { info -> info.version == overrideVersion }
                                    ?.experimental == true
                            }

                        selectedDetail.recommendedVersion != null ->
                            suggestedVersionLabel(
                                versionName = selectedDetail.recommendedVersion,
                                versionCodes = selectedDetail.recommendedVersionCodes,
                                displayVersion = stringResource(
                                    R.string.version_label,
                                    selectedDetail.recommendedVersion
                                )
                            ).also {
                                versionIsExperimental = selectedDetail.recommendedVersionExperimental
                            }

                        selectedDetail.supportsAllVersions ->
                            stringResource(R.string.bundle_version_all_versions)

                        selectedDetail.otherSupportedVersions.isNotEmpty() -> {
                            val firstVersion = selectedDetail.otherSupportedVersions.first()
                            versionIsExperimental = firstVersion.experimental
                            suggestedVersionLabel(
                                versionName = firstVersion.version,
                                versionCodes = firstVersion.versionCodes,
                                displayVersion = stringResource(
                                    R.string.version_label,
                                    firstVersion.version
                                )
                            )
                        }

                        else -> stringResource(R.string.bundle_version_no_version)
                    }
                    val displayVersionLabel = if (versionIsExperimental) {
                        "$versionLabel (${stringResource(R.string.patch_bundle_experimental_version_label)})"
                    } else {
                        versionLabel
                    }
                    stringResource(
                        R.string.bundle_version_item_description_selected,
                        selectedDetail.name,
                        displayVersionLabel
                    )
                } else {
                    stringResource(R.string.bundle_version_item_description_default)
                }
                PageItem(
                    R.string.bundle_version_item,
                    bundleSummary,
                    useCardStyle = useCardStylePageItems,
                    onClick = { showBundleRecommendationDialog = true }
                )
            }
            PageItem(
                R.string.apk_source_selector_item,
                when (val app = vm.selectedApp) {
                    is SelectedApp.Search -> stringResource(R.string.apk_source_auto)
                    is SelectedApp.Installed -> stringResource(R.string.apk_source_installed)
                    is SelectedApp.Download -> stringResource(
                        R.string.apk_source_downloader,
                        plugins.firstOrNull { it.id == app.data.pluginId }?.name
                            ?: plugins.firstOrNull {
                                it.packageName == app.data.pluginPackageName &&
                                    (app.data.pluginClassName == null || it.className == app.data.pluginClassName)
                            }
                            ?.name
                            ?: app.data.pluginPackageName
                    )

                    is SelectedApp.Local ->
                        if (app.temporary)
                            stringResource(R.string.apk_source_local)
                        else
                            stringResource(R.string.apk_source_downloaded)
                },
                useCardStyle = useCardStylePageItems,
                onClick = {
                    vm.showSourceSelector()
                }
            )
            error?.let {
                Text(
                    stringResource(it.resourceId),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }

            Column(
                modifier = Modifier.padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                val needsInternet =
                    vm.selectedApp.let { it is SelectedApp.Search || it is SelectedApp.Download }

                when {
                    !needsInternet -> {}
                    !networkConnected -> {
                        NotificationCard(
                            isWarning = true,
                            icon = Icons.Outlined.WarningAmber,
                            text = stringResource(R.string.network_unavailable_warning),
                            onDismiss = null
                        )
                    }

                    networkMetered -> {
                        NotificationCard(
                            isWarning = true,
                            icon = Icons.Outlined.WarningAmber,
                            text = stringResource(R.string.network_metered_warning),
                            onDismiss = null
                        )
                    }
                }
            }
        }
    }

    if (showBundleRecommendationDialog && bundleRecommendationDetails.isNotEmpty()) {
        BundleVersionSelectionDialog(
            appInfo = vm.selectedAppInfo,
            details = bundleRecommendationDetails,
            selectedUid = selectedBundleUid,
            selectedOverride = selectedBundleOverride,
            recommendationsEnabled = bundleRecommendationsEnabled,
            onSelect = { uid, override, targetsAllVersions ->
                vm.selectBundleRecommendation(uid, override, targetsAllVersions)
            },
            onDismissRequest = { showBundleRecommendationDialog = false }
        )
    }

    if (showMixedBundleDialog) {
        AlertDialogExtended(
            onDismissRequest = { showMixedBundleDialog = false },
            confirmButton = {
                TextButton(onClick = { showMixedBundleDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            },
            title = { CenteredDialogTitle(stringResource(R.string.mixed_patch_bundles_title)) },
            text = { Text(stringResource(R.string.mixed_patch_bundles_description)) }
        )
    }

    if (showMixedRevancedPatcherDialog) {
        AlertDialogExtended(
            onDismissRequest = { showMixedRevancedPatcherDialog = false },
            confirmButton = {
                TextButton(onClick = { showMixedRevancedPatcherDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            },
            title = { CenteredDialogTitle(stringResource(R.string.mixed_revanced_patcher_versions_title)) },
            text = { Text(stringResource(R.string.mixed_revanced_patcher_versions_description)) }
        )
    }

    if (showPatchSummaryDialog) {
        AppliedPatchesDialog(
            bundles = selectedPatchSummary,
            onDismissRequest = { showPatchSummaryDialog = false },
            titleRes = R.string.patch_confirmation_title,
            sectionHeaderRes = R.string.selected_patches_title,
            sectionSubtitleRes = R.string.patch_confirmation_subtitle,
            optionsByBundle = selectedPatchOptions,
            confirmTextRes = if (batchQueueMode) R.string.save else R.string.continue_,
            onConfirm = {
                showPatchSummaryDialog = false
                onPatchClick()
            }
        )
    }

    if (patchAvailabilityEnabled && showPatchModeDialog) {
        PatchModeDialog(
            useMount = vm.usingMountInstall,
            onSelect = vm::selectPatchMode,
            onDismissRequest = { showPatchModeDialog = false }
        )
    }
}

// UI behavior adapted from Morphe, see third-party/NOTICE for more information.
// https://github.com/MorpheApp/morphe-manager/pull/747
@Composable
private fun PatchModeDialog(
    useMount: Boolean,
    onSelect: (Boolean) -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialogExtended(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.close))
            }
        },
        title = { CenteredDialogTitle(stringResource(R.string.patch_mode_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.patch_mode_dialog_description),
                    style = MaterialTheme.typography.bodyMedium
                )
                listOf(
                    false to R.string.patch_mode_standard,
                    true to R.string.patch_mode_mount
                ).forEach { (mount, label) ->
                    ListItem(
                        headlineContent = { Text(stringResource(label)) },
                        leadingContent = {
                            RadioButton(
                                selected = useMount == mount,
                                onClick = null
                            )
                        },
                        modifier = Modifier.clickable {
                            onSelect(mount)
                            onDismissRequest()
                        }
                    )
                }
            }
        }
    )
}

private fun BundleRecommendationDetail.versionCodesFor(version: String): Set<Long> =
    if (recommendedVersion == version) {
        recommendedVersionCodes
    } else {
        otherSupportedVersions.firstOrNull { it.version == version }?.versionCodes.orEmpty()
    }

@Composable
private fun BundleVersionSelectionDialog(
    appInfo: PackageInfo?,
    details: List<BundleRecommendationDetail>,
    selectedUid: Int?,
    selectedOverride: String?,
    recommendationsEnabled: Boolean,
    onSelect: (Int?, String?, Boolean) -> Unit,
    onDismissRequest: () -> Unit
) {
    var otherVersionsTarget by rememberSaveable { mutableStateOf<BundleRecommendationDetail?>(null) }

    AlertDialogExtended(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.close))
            }
        },
        title = {
            Text(
                text = stringResource(R.string.bundle_version_dialog_title),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        },
        textBottomPadding = 8.dp,
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                item(key = "description") {
                    Text(
                        stringResource(R.string.bundle_version_dialog_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
                item(key = "default") {
                    BundleVersionOptionRow(
                        title = stringResource(R.string.bundle_version_dialog_default),
                        subtitle = stringResource(R.string.bundle_version_dialog_default_subtitle),
                        selected = selectedUid == null,
                        onClick = { onSelect(null, null, false) },
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
                if (!recommendationsEnabled) {
                    item(key = "locked_hint") {
                        SafeguardHintCard(
                            title = stringResource(R.string.bundle_version_dialog_locked_title),
                            description = stringResource(R.string.bundle_version_dialog_locked_hint),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp)
                        )
                    }
                }
                items(
                    items = details,
                    key = { it.bundleUid }
                ) { detail ->
                    val isSelectedBundle = selectedUid == detail.bundleUid
                    BundleRecommendationCard(
                        appInfo = appInfo,
                        detail = detail,
                        isActive = isSelectedBundle,
                        selectedOverride = if (isSelectedBundle) selectedOverride else null,
                        enabled = recommendationsEnabled,
                        onSelectRecommended = { useAllVersions ->
                            onSelect(detail.bundleUid, null, useAllVersions)
                        },
                        onShowOtherVersions = {
                            if (recommendationsEnabled) {
                                otherVersionsTarget = detail
                            }
                        }
                    )
                }
            }
        }
    )

    val targetDetail = if (recommendationsEnabled) otherVersionsTarget else null
    if (targetDetail != null) {
        OtherSupportedVersionsSelectionDialog(
            appInfo = appInfo,
            detail = targetDetail,
            selectedOverride = if (selectedUid == targetDetail.bundleUid) selectedOverride else null,
            onSelect = { version ->
                onSelect(targetDetail.bundleUid, version, false)
                otherVersionsTarget = null
            },
            onDismissRequest = { otherVersionsTarget = null }
        )
    }

}

@Composable
private fun BundleRecommendationCard(
    appInfo: PackageInfo?,
    detail: BundleRecommendationDetail,
    isActive: Boolean,
    selectedOverride: String?,
    enabled: Boolean,
    onSelectRecommended: (Boolean) -> Unit,
    onShowOtherVersions: () -> Unit
) {
    val containerColor = if (isActive) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isActive) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .alpha(if (enabled) 1f else 0.5f),
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
        tonalElevation = if (isActive) 6.dp else 2.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val nameScrollState = rememberScrollState()
                Text(
                    detail.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .consumeHorizontalScroll(nameScrollState)
                )
                if (isActive && selectedOverride == null) {
                    SelectionBadge(
                        text = stringResource(R.string.bundle_version_dialog_selected_indicator),
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                        contentColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
            }

            val recommendedLabel = when {
                detail.recommendedVersion != null ->
                    suggestedVersionLabel(
                        versionName = detail.recommendedVersion,
                        versionCodes = detail.recommendedVersionCodes,
                        displayVersion = stringResource(
                            R.string.version_label,
                            detail.recommendedVersion
                        )
                    )
                detail.supportsAllVersions ->
                    stringResource(R.string.bundle_version_all_versions)
                else -> stringResource(R.string.bundle_version_no_version)
            }

            BundleVersionOptionRow(
                title = stringResource(R.string.bundle_version_dialog_recommended, recommendedLabel),
                subtitle = when {
                    detail.recommendedVersionExperimental ->
                        stringResource(R.string.patch_bundle_experimental_version_label)
                    detail.supportsAllVersions && detail.recommendedVersion == null ->
                        stringResource(R.string.other_supported_versions_all)
                    else -> null
                },
                selected = isActive && selectedOverride == null,
                enabled = enabled,
                onClick = {
                    val useAllVersions =
                        detail.supportsAllVersions && detail.recommendedVersion == null
                    onSelectRecommended(useAllVersions)
                },
                modifier = Modifier.fillMaxWidth()
            )

            if (selectedOverride != null) {
                Column(
                    modifier = Modifier.align(Alignment.Start),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SelectionBadge(
                        text = stringResource(
                            R.string.bundle_version_dialog_selected_override,
                            suggestedVersionLabel(
                                versionName = selectedOverride,
                                versionCodes = detail.versionCodesFor(selectedOverride),
                                displayVersion = stringResource(
                                    R.string.version_label,
                                    selectedOverride
                                )
                            )
                        ),
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    val overrideExperimental = detail.otherSupportedVersions
                        .firstOrNull { it.version == selectedOverride }
                        ?.experimental == true
                    if (overrideExperimental) {
                        SelectionBadge(
                            text = stringResource(R.string.patch_bundle_experimental_version_label),
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }

            when {
                detail.otherSupportedVersions.isNotEmpty() -> {
                    TextButton(
                        onClick = onShowOtherVersions,
                        enabled = enabled,
                        contentPadding = PaddingValues(horizontal = 0.dp),
                        modifier = Modifier.align(Alignment.Start)
                    ) {
                        Text(
                            stringResource(R.string.show_other_versions),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OtherSupportedVersionsSelectionDialog(
    appInfo: PackageInfo?,
    detail: BundleRecommendationDetail,
    selectedOverride: String?,
    onSelect: (String?) -> Unit,
    onDismissRequest: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.close))
            }
        },
        title = { CenteredDialogTitle(stringResource(R.string.other_supported_versions_title, detail.name)) },
        text = {
            when {
                detail.otherSupportedVersions.isNotEmpty() -> {
                    val context = LocalContext.current

                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            stringResource(R.string.bundle_version_dialog_other_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            detail.otherSupportedVersions.chunked(2).forEach { row ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    row.forEach { info ->
                                        val label = suggestedVersionLabel(
                                            versionName = info.version,
                                            versionCodes = info.versionCodes,
                                            displayVersion = context.getString(
                                                R.string.version_label,
                                                info.version
                                            )
                                        )
                                        FilterChip(
                                            selected = selectedOverride == info.version,
                                            onClick = { onSelect(info.version) },
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = 52.dp),
                                            label = {
                                                Column(
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    ExpandableText(
                                                        text = label,
                                                        textAlign = TextAlign.Center
                                                    )
                                                    if (info.experimental) {
                                                        ExperimentalVersionBadge()
                                                    }
                                                }
                                            }
                                        )
                                    }
                                    if (row.size == 1) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
                detail.supportsAllVersions -> {
                    Text(
                        stringResource(R.string.other_supported_versions_all),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                else -> {
                    Text(
                        stringResource(R.string.other_supported_versions_empty),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    )
}

@Composable
private fun BundleVersionOptionRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val primaryContentColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val secondaryContentColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = primaryContentColor
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp)
        ) {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                RadioButton(
                    selected = selected,
                    enabled = enabled,
                    onClick = null,
                    modifier = Modifier.size(24.dp),
                    colors = RadioButtonDefaults.colors(
                        selectedColor = MaterialTheme.colorScheme.primary,
                        unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
            Column(
                modifier = Modifier
                    .padding(start = 12.dp)
                    .weight(1f)
            ) {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = primaryContentColor)
                subtitle?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = secondaryContentColor
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectionBadge(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
    contentColor: Color = MaterialTheme.colorScheme.primary
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        ExpandableText(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            collapsedSoftWrap = false,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun PageItem(
    @StringRes title: Int,
    description: String,
    useCardStyle: Boolean = false,
    onClick: () -> Unit
) {
    if (useCardStyle) {
        Surface(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.68f),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            ListItem(
                modifier = Modifier.clickable(onClick = onClick),
                headlineContent = {
                    Text(
                        stringResource(title),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                supportingContent = {
                    Text(
                        description,
                        color = MaterialTheme.colorScheme.outline,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Outlined.ArrowRight, null)
                },
                colors = transparentListItemColors
            )
        }
    } else {
        ListItem(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(start = 8.dp),
            headlineContent = {
                Text(
                    stringResource(title),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleLarge
                )
            },
            supportingContent = {
                Text(
                    description,
                    color = MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            trailingContent = {
                Icon(Icons.AutoMirrored.Outlined.ArrowRight, null)
            }
        )
    }
}

@Composable
internal fun AppSourceSelectorDialog(
    plugins: List<LoadedDownloaderPlugin>,
    apkDownloadHelpers: List<ApkDownloadHelperContract.Helper> = emptyList(),
    installedApp: Pair<SelectedApp.Installed, InstalledApp?>?,
    searchApp: SelectedApp.Search,
    activeSearchJob: String?,
    hasRoot: Boolean,
    downloadedApps: List<DownloadedApp>,
    includeAutoOption: Boolean = true,
    requiredVersion: String?,
    onDismissRequest: () -> Unit,
    onSelectPlugin: (LoadedDownloaderPlugin) -> Unit,
    onSelectApkDownloadHelper: (ApkDownloadHelperContract.Helper) -> Unit = {},
    onSelectDownloaded: (DownloadedApp) -> Unit,
    onSelectLocal: (() -> Unit)?,
    onSelect: (SelectedApp) -> Unit,
) {
    val browserContext = LocalContext.current
    val browserPrefs: app.universal.patch.manager.domain.manager.PreferencesManager = org.koin.compose.koinInject()
    val browserSearchHost by browserPrefs.searchEngineHost.getAsState()
    val canSelect = activeSearchJob == null
    val hasDownloadedApps = downloadedApps.isNotEmpty()
    val hasPlugins = plugins.isNotEmpty()
    val hasAutoChoice = hasDownloadedApps || hasPlugins
    var showDownloadedApps by remember { mutableStateOf(false) }

    AlertDialogExtended(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel))
            }
        },
        title = { CenteredDialogTitle(stringResource(R.string.app_source_dialog_title)) },
        textHorizontalPadding = PaddingValues(horizontal = 0.dp),
        text = {
            LazyColumn {
                if (downloadedApps.isNotEmpty()) {
                    item(key = "downloaded_header") {
                        val icon = if (showDownloadedApps) Icons.Outlined.UnfoldLess else Icons.Outlined.UnfoldMore
                        ListItem(
                            modifier = Modifier
                                .clickable { showDownloadedApps = !showDownloadedApps }
                                .enabled(downloadedApps.isNotEmpty()),
                            headlineContent = { Text(stringResource(R.string.downloaded_apps)) },
                            trailingContent = { Icon(icon, null) },
                            colors = transparentListItemColors
                        )
                    }
                    if (showDownloadedApps) {
                        items(
                            items = downloadedApps,
                            key = { "downloaded_${it.packageName}_${it.version}" }
                        ) { downloadedApp ->
                            ListItem(
                                modifier = Modifier
                                    .clickable(enabled = canSelect) { onSelectDownloaded(downloadedApp) }
                                    .padding(start = 16.dp),
                                headlineContent = { Text(downloadedApp.version) },
                                supportingContent = { Text(downloadedApp.packageName) },
                                colors = transparentListItemColors
                            )
                        }
                    }
                }

                if (includeAutoOption) {
                    item(key = "auto") {
                        ListItem(
                            modifier = Modifier
                                .clickable(enabled = canSelect && hasAutoChoice) {
                                    onSelect(searchApp)
                                }
                                .enabled(hasAutoChoice),
                            headlineContent = { Text(stringResource(R.string.app_source_dialog_option_auto)) },
                            supportingContent = {
                                Text(
                                    if (hasAutoChoice)
                                        stringResource(R.string.app_source_dialog_option_auto_description)
                                    else
                                        stringResource(R.string.app_source_dialog_option_auto_unavailable)
                                )
                            },
                            colors = transparentListItemColors
                        )
                    }
                }

                installedApp?.let { (app, meta) ->
                    item(key = "installed") {
                        val (usable, text) = when {
                            meta?.installType == InstallType.MOUNT && !hasRoot -> false to stringResource(
                                R.string.app_source_dialog_option_installed_no_root
                            )
                            meta?.installType == InstallType.DEFAULT ||
                                meta?.installType == InstallType.PLAY_STORE ||
                                meta?.installType == InstallType.ROOT_PLAY_STORE ||
                                meta?.installType == InstallType.CUSTOM ||
                                meta?.installType == InstallType.SHIZUKU ||
                                meta?.installType == InstallType.SHIZUKU_PLAY_STORE ->
                                false to stringResource(R.string.already_patched)
                            requiredVersion != null && app.version != requiredVersion -> false to stringResource(
                                R.string.app_source_dialog_option_installed_version_not_suggested,
                                app.version
                            )

                            else -> true to app.version
                        }
                        ListItem(
                            modifier = Modifier
                                .clickable(enabled = canSelect && usable) { onSelect(app) }
                                .enabled(usable),
                            headlineContent = { Text(stringResource(R.string.installed)) },
                            supportingContent = { Text(text) },
                            colors = transparentListItemColors
                        )
                    }
                }

                onSelectLocal?.let { selectLocal ->
                    item(key = "storage") {
                        ListItem(
                            modifier = Modifier.clickable(enabled = canSelect) { selectLocal() },
                            headlineContent = { Text(stringResource(R.string.app_source_dialog_option_storage)) },
                            supportingContent = {
                                Text(stringResource(R.string.app_source_dialog_option_storage_description))
                            },
                            colors = transparentListItemColors
                        )
                    }
                }

                item(key = "browser_search") {
                    ListItem(
                        modifier = Modifier.clickable(enabled = canSelect) {
                            val query = listOfNotNull(
                                searchApp.packageName,
                                (requiredVersion ?: searchApp.version)?.takeIf { it.isNotBlank() }?.let {
                                    if (it.startsWith("v", ignoreCase = true)) it else "v$it"
                                },
                                android.os.Build.SUPPORTED_ABIS.firstOrNull()
                            ).joinToString(" ")
                            val host = browserSearchHost.trim().removePrefix("https://")
                                .removePrefix("http://").substringBefore('/').substringBefore('?')
                                .substringBefore('#').trim().ifBlank { "google.com" }
                            browserContext.openUrl("https://$host/search?q=" + android.net.Uri.encode(query))
                            onDismissRequest()
                        },
                        headlineContent = { Text(stringResource(R.string.source_search_browser)) },
                        supportingContent = { Text(stringResource(R.string.source_search_browser_description)) },
                        colors = transparentListItemColors
                    )
                }

                items(apkDownloadHelpers, key = { it.id }) { helper ->
                    ListItem(
                        modifier = Modifier.clickable(enabled = canSelect) {
                            onSelectApkDownloadHelper(helper)
                        },
                        headlineContent = { Text(helper.label) },
                        supportingContent = { Text(stringResource(R.string.downloader_plugin_type_helper)) },
                        trailingContent = (@Composable { LoadingIndicator() })
                            .takeIf { activeSearchJob == helper.id },
                        colors = transparentListItemColors
                    )
                }

                items(plugins, key = { "plugin_${it.id}" }) { plugin ->
                    ListItem(
                        modifier = Modifier.clickable(enabled = canSelect) { onSelectPlugin(plugin) },
                        headlineContent = { Text(plugin.shortDisplayName) },
                        trailingContent = (@Composable { LoadingIndicator() }).takeIf { activeSearchJob == plugin.id },
                        colors = transparentListItemColors
                    )
                }
            }
        }
    )
}
