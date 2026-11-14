package app.universal.patch.manager.ui.screen

import app.universal.patch.manager.ui.component.persistentControls
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.format.Formatter
import androidx.appcompat.content.res.AppCompatResources
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DeviceHub
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.Source
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.universal.patch.manager.R
import app.universal.patch.manager.data.platform.Filesystem
import app.universal.patch.manager.domain.installer.InstallerManager
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.domain.batch.selectedBatchTargetIdentifiers
import app.universal.patch.manager.patcher.aapt.Aapt
import app.universal.patch.manager.ui.model.navigation.Announcement
import app.universal.patch.manager.ui.component.AlertDialogExtended
import app.universal.patch.manager.ui.component.AppIcon
import app.universal.patch.manager.ui.component.AppLabel
import app.universal.patch.manager.ui.component.AppTopBar
import app.universal.patch.manager.ui.component.AppVersion
import app.universal.patch.manager.ui.component.AutoUpdatesDialog
import app.universal.patch.manager.ui.component.AvailableUpdateDialog
import app.universal.patch.manager.ui.component.CheckedFilterChip
import app.universal.patch.manager.ui.component.DownloadProgressBanner
import app.universal.patch.manager.ui.component.FullscreenDialog
import app.universal.patch.manager.ui.component.InterceptBackHandler
import app.universal.patch.manager.ui.component.LoadingIndicator
import app.universal.patch.manager.ui.component.NotificationCard
import app.universal.patch.manager.ui.component.ConfirmDialog
import app.universal.patch.manager.ui.component.ExportSavedApkFileNameDialog
import app.universal.patch.manager.ui.component.NonSuggestedVersionDialog
import app.universal.patch.manager.ui.component.TransparentLoadingDialog
import app.universal.patch.manager.ui.component.UniversalFallbackVersionDialog
import app.universal.patch.manager.ui.component.bundle.BundleTopBar
import app.universal.patch.manager.ui.component.bundle.ImportPatchBundleDialog
import app.universal.patch.manager.ui.component.bundle.PatchBundleFileImportDialog
import app.universal.patch.manager.ui.component.haptics.HapticFloatingActionButton
import app.universal.patch.manager.ui.component.haptics.HapticTab
import app.universal.patch.manager.ui.component.patches.PathSelectorDialog
import app.universal.patch.manager.ui.component.RememberedCreateDocument
import app.universal.patch.manager.ui.component.RememberedGetContent
import app.universal.patch.manager.ui.component.RememberedOpenDocumentTree
import app.universal.patch.manager.ui.component.toPickerDirectoryUri
import app.universal.patch.manager.ui.component.patcher.InstallerPickerDialog
import app.universal.patch.manager.ui.component.patcher.SavedAppMountPromptDialog
import app.universal.patch.manager.ui.component.patcher.SavedAppMountPromptMode
import app.universal.patch.manager.ui.viewmodel.DashboardViewModel
import app.universal.patch.manager.ui.viewmodel.InstalledAppInfoViewModel
import app.universal.patch.manager.ui.viewmodel.MainViewModel
import app.universal.patch.manager.ui.viewmodel.LsposedViewModel
import app.universal.patch.manager.ui.model.SelectedApp
import app.universal.patch.manager.ui.viewmodel.PatchProfileLaunchData
import app.universal.patch.manager.ui.viewmodel.PatchProfilesViewModel
import app.universal.patch.manager.domain.repository.PatchBundleRepository
import app.universal.patch.manager.domain.repository.PatchBundleRepository.BundleUpdatePhase
import app.universal.patch.manager.domain.repository.PatchBundleRepository.BundleImportPhase
import app.universal.patch.manager.ui.viewmodel.InstalledAppsViewModel
import app.universal.patch.manager.data.room.apps.installed.InstalledApp
import app.universal.patch.manager.network.downloader.LoadedDownloaderPlugin
import app.universal.patch.manager.ui.viewmodel.AppSelectorViewModel
import app.universal.patch.manager.ui.viewmodel.BundleListViewModel
import app.universal.patch.manager.ui.model.InstalledAppAction
import app.universal.patch.manager.ui.viewmodel.InstallResult
import app.universal.patch.manager.ui.viewmodel.MountWarningAction
import app.universal.patch.manager.ui.viewmodel.MountWarningReason
import app.universal.patch.manager.ui.viewmodel.NewPluginNotification
import app.universal.patch.manager.ui.viewmodel.SplitMergeState
import app.universal.patch.manager.ui.viewmodel.SplitMergeStepStatus
import app.universal.patch.manager.util.RequestInstallAppsContract
import app.universal.patch.manager.util.AppInfo
import app.universal.patch.manager.util.BundleDeepLink
import app.universal.patch.manager.util.PatchBundleFileIntent
import app.universal.patch.manager.util.EventEffect
import app.universal.patch.manager.util.ExportNameFormatter
import app.universal.patch.manager.util.FilenameUtils
import app.universal.patch.manager.util.PatchBundleExportData
import app.universal.patch.manager.util.PatchedAppExportData
import app.universal.patch.manager.util.isAllowedApkFile
import app.universal.patch.manager.util.isAllowedPatchBundleFile
import app.universal.patch.manager.util.isAllowedSplitArchiveFile
import app.universal.patch.manager.util.PM
import app.universal.patch.manager.util.savedAppBasePackage
import app.universal.patch.manager.util.toast
import app.universal.patch.manager.data.room.apps.installed.InstallType
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import kotlin.math.abs
import app.universal.patch.manager.ui.component.CenteredDialogTitle
import app.universal.patch.manager.ui.screen.dashboard.LsposedTabScreen

enum class DashboardPage(
    val titleResId: Int,
    val icon: ImageVector
) {
    DASHBOARD(R.string.tab_apps, Icons.Outlined.Apps),
    BUNDLES(R.string.tab_patches, Icons.Outlined.Source),
    PROFILES(R.string.tab_profiles, Icons.Outlined.Bookmarks),
    LSPOSED(R.string.tab_lsposed, Icons.Outlined.DeviceHub),
    TOOLS(R.string.tab_tools, Icons.Outlined.Build),
}

private enum class BundleProgressBannerType {
    IMPORT,
    UPDATE,
}

@SuppressLint("BatteryLife")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    vm: DashboardViewModel = koinViewModel(),
    mainVm: MainViewModel = koinViewModel(),
    onAppSelectorClick: () -> Unit,
    onBatchQueueClick: () -> Unit,
    onStorageSelect: (SelectedApp.Local) -> Unit,
    onSettingsClick: () -> Unit,
    onUpdateClick: () -> Unit,
    onAnnouncementsClick: () -> Unit,
    onDownloaderPluginClick: () -> Unit,
    onPatcherRuntimePluginClick: () -> Unit,
    onBundleDiscoveryClick: () -> Unit,
    onMergeSplitClick: () -> Unit,
    onOpenSplitInstallerClick: () -> Unit,
    onCreateYoutubeAssetsClick: () -> Unit,
    onOpenApkSignerClick: () -> Unit,
    onOpenKeystoreCreatorClick: () -> Unit,
    onOpenKeystoreConverterClick: () -> Unit,
    onAppClick: (String, InstalledAppAction?) -> Unit,
    onBatchPatch: (List<String>) -> Unit,
    onAnnouncementClick: (Announcement.Payload) -> Unit,
    onProfileLaunch: (PatchProfileLaunchData) -> Unit,
    bundleDeepLink: BundleDeepLink? = null,
    onBundleDeepLinkConsumed: () -> Unit = {},
    patchBundleFileIntent: PatchBundleFileIntent? = null,
    onPatchBundleFileIntentConsumed: () -> Unit = {}
) {
    val installedAppsViewModel: InstalledAppsViewModel = koinViewModel()
    val patchProfilesViewModel: PatchProfilesViewModel = koinViewModel()
    val patchBundleRepository: PatchBundleRepository = koinInject()
    val pm: PM = koinInject()
    val installedApps by installedAppsViewModel.apps.collectAsStateWithLifecycle(initialValue = emptyList())
    val profiles by patchProfilesViewModel.profiles.collectAsStateWithLifecycle(emptyList())
    val bundleSources by patchBundleRepository.sources.collectAsStateWithLifecycle(emptyList())
    var appsSearchActive by rememberSaveable { mutableStateOf(false) }
    var appsSearchQuery by rememberSaveable { mutableStateOf("") }
    var bundlesSearchActive by rememberSaveable { mutableStateOf(false) }
    var bundlesSearchQuery by rememberSaveable { mutableStateOf("") }
    var profilesSearchActive by rememberSaveable { mutableStateOf(false) }
    var profilesSearchQuery by rememberSaveable { mutableStateOf("") }
    var selectedSourceCount by rememberSaveable { mutableIntStateOf(0) }
    var selectedSourcesHasEnabled by rememberSaveable { mutableStateOf(true) }
    val storageVm: AppSelectorViewModel = koinViewModel()
    val bundleListViewModel: BundleListViewModel = koinViewModel()
    val fs = koinInject<Filesystem>()
    val prefs: PreferencesManager = koinInject()
    val savedAppsEnabled by prefs.enableSavedApps.getAsState()
    val viewedManagerUpdateVersion by prefs.viewedManagerUpdateVersion.getAsState()
    val showManagerUpdateChangelog by prefs.showManagerUpdateChangelog.getAsState()
    val useCustomFilePicker by prefs.useCustomFilePicker.getAsState()
    val hideMainTabLabels by prefs.hideMainTabLabels.getAsState()
    val showAppsTabUpdateCount by prefs.showAppsTabUpdateCount.getAsState()
    val showBundlesTabUpdateCount by prefs.showBundlesTabUpdateCount.getAsState()
    val disableMainTabSwipe by prefs.disableMainTabSwipe.getAsState()
    val preventAccidentalTouching by prefs.preventAccidentalTouching.getAsState()
    val showPatchProfilesTab by prefs.showPatchProfilesTab.getAsState()
    val showLsposedTab by prefs.showLsposedTab.getAsState()
    val lsposedViewModel = if (showLsposedTab) {
        koinViewModel<LsposedViewModel>()
    } else {
        null
    }
    val lsposedRootAvailable = lsposedViewModel?.frameworkState?.rootAvailable == true
    val showToolsTab by prefs.showToolsTab.getAsState()
    val announcementSystemEnabled by prefs.announcementSystemEnabled.getAsState()
    val exportFormat by prefs.patchedAppExportFormat.getAsState()
    val dashboardApkInputDirectory by prefs.dashboardApkInputLastDirectory.getAsState()
    val dashboardQuickExportDirectory by prefs.dashboardQuickExportLastDirectory.getAsState()
    val rootMountDiagnosticsExportDirectory by
        prefs.rootMountDiagnosticsExportLastDirectory.getAsState()
    val dashboardBundleInputDirectory by prefs.dashboardBundleInputLastDirectory.getAsState()
    val dashboardSplitInputDirectory by prefs.dashboardSplitInputLastDirectory.getAsState()
    val dashboardSavedAppsExportDirectory by
        prefs.dashboardSavedAppsExportLastDirectory.getAsState()
    val patchBundleImportAutoUpdate by prefs.patchBundleImportAutoUpdate.getAsState()
    val patchBundleImportSearchUpdate by prefs.patchBundleImportSearchUpdate.getAsState()
    val chooseInstallerPerInstall by prefs.chooseInstallerPerInstall.getAsState()
    val bundlesFabCollapsed by prefs.dashboardBundlesFabCollapsed.getAsState()
    val appsFabCollapsed by prefs.dashboardAppsFabCollapsed.getAsState()
    val lsposedFabCollapsed by prefs.dashboardLsposedFabCollapsed.getAsState()
    val bundleImportBannerCollapsed by prefs.dashboardBundleImportBannerCollapsed.getAsState()
    val bundleUpdateBannerCollapsed by prefs.dashboardBundleUpdateBannerCollapsed.getAsState()
    val installerManager: InstallerManager = koinInject()
    val bundlesSelectable by remember { derivedStateOf { selectedSourceCount > 0 } }
    val selectedProfileCount by remember { derivedStateOf { patchProfilesViewModel.selectedProfiles.size } }
    val profilesSelectable = showPatchProfilesTab && selectedProfileCount > 0
    val availablePatches by vm.availablePatches.collectAsStateWithLifecycle(0)
    val patchBundlesLoading by vm.patchBundlesLoading.collectAsStateWithLifecycle()
    val manualBundleUpdates by bundleListViewModel.manualUpdateInfo.collectAsStateWithLifecycle(emptyMap())
    val appsUpdateCount by remember(installedApps) {
        derivedStateOf {
            installedApps.count { app ->
                installedAppsViewModel.bundleSummaries[app.currentPackageName]
                    .orEmpty()
                    .any { it.hasUpdate }
            }
        }
    }
    val splitMergeState by vm.splitMergeState.collectAsStateWithLifecycle()
    val newPluginNotifications by vm.newPluginNotifications.collectAsStateWithLifecycle(emptyList())
    val downloaderPlugins by vm.loadedDownloaderPlugins.collectAsStateWithLifecycle(emptyList())
    val storageRoots = remember { fs.storageRoots() }
    val appInputEnabled = !patchBundlesLoading
    val disabledAppInputFabColor =
        lerp(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.primary, 0.08f)
    EventEffect(flow = storageVm.storageSelectionFlow) { selected ->
        storageVm.consumeStorageSelectionResult()
        onStorageSelect(selected)
    }
    var showStorageDialog by rememberSaveable { mutableStateOf(false) }
    val pickerScope = rememberCoroutineScope()
    val (permissionContract, permissionName) = remember { fs.permissionContract() }
    val permissionLauncher =
        rememberLauncherForActivityResult(permissionContract) { granted ->
            if (granted) {
                showStorageDialog = true
            }
        }
    val openStorageDocumentLauncher = rememberLauncherForActivityResult(
        contract = RememberedGetContent {
            dashboardApkInputDirectory.takeIf(String::isNotBlank)?.let(Uri::parse)
        }
    ) { uri ->
        if (uri != null) {
            pickerScope.launch {
                prefs.dashboardApkInputLastDirectory.update(uri.toPickerDirectoryUri().toString())
                storageVm.handleStorageResult(uri)
            }
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
            openStorageDocumentLauncher.launch("application/*")
        }
    }
    val downloaderPluginLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = vm::handlePluginActivityResult
    )
    EventEffect(flow = vm.launchActivityFlow) { intent ->
        downloaderPluginLauncher.launch(intent)
    }
    EventEffect(flow = vm.openSplitMergeScreenFlow) {
        onMergeSplitClick()
    }
    val bundleUpdateProgress by vm.bundleUpdateProgress.collectAsStateWithLifecycle(null)
    val bundleImportProgress by vm.bundleImportProgress.collectAsStateWithLifecycle(null)
    val storageSuggestedVersions by storageVm.suggestedAppVersions.collectAsStateWithLifecycle(emptyMap())
    val androidContext = LocalContext.current
    val composableScope = rememberCoroutineScope()
    var showBundleOrderDialog by rememberSaveable { mutableStateOf(false) }
    var showAppsOrderDialog by rememberSaveable { mutableStateOf(false) }
    var showProfilesOrderDialog by rememberSaveable { mutableStateOf(false) }
    val visibleTabs = remember(showPatchProfilesTab, showLsposedTab, showToolsTab) {
        DashboardPage.entries.filter { page ->
            when (page) {
                DashboardPage.PROFILES -> showPatchProfilesTab
                DashboardPage.LSPOSED -> showLsposedTab
                DashboardPage.TOOLS -> showToolsTab
                else -> true
            }
        }
    }
    var activeDashboardPage by rememberSaveable { mutableStateOf(DashboardPage.DASHBOARD) }
    val pagerState = rememberPagerState(
        initialPage = 0,
        initialPageOffsetFraction = 0f
    ) { visibleTabs.size }
    val pageIndexByType = remember(visibleTabs) {
        visibleTabs.withIndex().associate { (index, page) -> page to index }
    }
    val currentPage = activeDashboardPage
    val swipeSyncedTabIndex by remember(visibleTabs) {
        derivedStateOf {
            if (visibleTabs.isEmpty()) return@derivedStateOf 0
            val pageIndex = if (pagerState.isScrollInProgress) {
                pagerState.targetPage
            } else {
                pagerState.currentPage
            }
            pageIndex.coerceIn(0, visibleTabs.lastIndex)
        }
    }
    val swipeSyncedPage = visibleTabs.getOrElse(swipeSyncedTabIndex) { DashboardPage.DASHBOARD }
    val uiPage = swipeSyncedPage
    suspend fun scrollToVisiblePage(page: DashboardPage, animated: Boolean) {
        val targetIndex = pageIndexByType[page] ?: return
        if (
            (pagerState.currentPage == targetIndex && !pagerState.isScrollInProgress) ||
            (pagerState.isScrollInProgress && pagerState.targetPage == targetIndex)
        ) return
        val canAnimateDirectly = abs(pagerState.currentPage - targetIndex) <= 1
        if (animated && canAnimateDirectly) {
            pagerState.animateScrollToPage(targetIndex)
        } else {
            pagerState.scrollToPage(targetIndex)
        }
    }

    var highlightBundleUid by rememberSaveable { mutableStateOf<Int?>(null) }
    val appsSelectionActive = installedAppsViewModel.selectedApps.isNotEmpty()
    val selectedAppCount = installedAppsViewModel.selectedApps.size
    var suppressAppsSelectionTopBar by remember { mutableStateOf(false) }
    var suppressBundlesSelectionTopBar by remember { mutableStateOf(false) }
    var suppressProfilesSelectionTopBar by remember { mutableStateOf(false) }
    fun clearSelectionForPage(page: DashboardPage) {
        when (page) {
            DashboardPage.DASHBOARD -> {
                installedAppsViewModel.clearSelection()
                showAppsOrderDialog = false
                suppressAppsSelectionTopBar = false
            }

            DashboardPage.BUNDLES -> {
                bundleListViewModel.handleEvent(BundleListViewModel.Event.CANCEL)
                selectedSourceCount = 0
                selectedSourcesHasEnabled = true
                showBundleOrderDialog = false
                suppressBundlesSelectionTopBar = false
            }

            DashboardPage.PROFILES -> {
                patchProfilesViewModel.handleEvent(PatchProfilesViewModel.Event.CANCEL)
                showProfilesOrderDialog = false
                suppressProfilesSelectionTopBar = false
            }

            DashboardPage.LSPOSED, DashboardPage.TOOLS -> Unit
        }
    }
    fun closeDashboardSearch() {
        appsSearchActive = false
        appsSearchQuery = ""
        bundlesSearchActive = false
        bundlesSearchQuery = ""
        profilesSearchActive = false
        profilesSearchQuery = ""
    }
    var quickActionPackage by remember { mutableStateOf<String?>(null) }
    var pendingQuickAction by remember { mutableStateOf<InstalledAppAction?>(null) }
    var showQuickExportPicker by remember { mutableStateOf(false) }
    var quickExportDialogState by remember { mutableStateOf<QuickExportDialogState?>(null) }
    var pendingQuickExportConfirmation by remember { mutableStateOf<PendingQuickExportConfirmation?>(null) }
    var quickExportInProgress by remember { mutableStateOf(false) }
    var showQuickDeleteDialog by remember { mutableStateOf(false) }
    var quickDeleteIsEntry by remember { mutableStateOf(false) }
    var quickDeleteApp by remember { mutableStateOf<InstalledApp?>(null) }
    var showQuickSavedUninstallDialog by remember { mutableStateOf(false) }
    var showQuickInstallerPicker by remember { mutableStateOf(false) }
    var quickMountPromptMode by remember { mutableStateOf<SavedAppMountPromptMode?>(null) }
    var showQuickRootDiagnosticsActionsDialog by rememberSaveable { mutableStateOf(false) }
    var showQuickRootDiagnosticsExportPicker by rememberSaveable { mutableStateOf(false) }
    var quickRootDiagnosticsExportFileDialogState by remember {
        mutableStateOf<QuickRootDiagnosticsExportDialogState?>(null)
    }
    var pendingQuickRootDiagnosticsExportConfirmation by remember {
        mutableStateOf<PendingQuickRootDiagnosticsExportConfirmation?>(null)
    }
    var quickRootDiagnosticsExportInProgress by rememberSaveable { mutableStateOf(false) }
    var pendingQuickRootDiagnosticsFileName by rememberSaveable { mutableStateOf<String?>(null) }
    var showQuickMixedBundleDialog by remember { mutableStateOf(false) }
    var showQuickMixedRevancedPatcherDialog by remember { mutableStateOf(false) }
    var pendingRepatchSourceWarning by remember {
        mutableStateOf<Pair<String?, () -> Unit>?>(null)
    }

    fun runRepatchOrWarn(app: InstalledApp, action: () -> Unit) {
        val sourcePath = app.repatchSourcePath
        val sourceAvailable = sourcePath
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?.isFile == true
        if (sourceAvailable) {
            action()
        } else {
            pendingRepatchSourceWarning = sourcePath to action
        }
    }

    LaunchedEffect(announcementSystemEnabled) {
        if (announcementSystemEnabled) {
            vm.refreshAnnouncements(forceRefresh = true)
        }
    }

    LaunchedEffect(pagerState.settledPage, visibleTabs) {
        visibleTabs.getOrNull(pagerState.settledPage)?.let { page ->
            if (activeDashboardPage != page) {
                clearSelectionForPage(activeDashboardPage)
                closeDashboardSearch()
                activeDashboardPage = page
            }
        }
    }

    LaunchedEffect(visibleTabs) {
        if (activeDashboardPage !in visibleTabs) {
            clearSelectionForPage(activeDashboardPage)
            closeDashboardSearch()
            activeDashboardPage = DashboardPage.DASHBOARD
            scrollToVisiblePage(DashboardPage.DASHBOARD, animated = false)
        }
    }

    LaunchedEffect(uiPage) {
        if (uiPage != DashboardPage.DASHBOARD && appsSelectionActive) {
            suppressAppsSelectionTopBar = true
        }
        if (uiPage != DashboardPage.BUNDLES && bundlesSelectable) {
            suppressBundlesSelectionTopBar = true
        }
        if (uiPage != DashboardPage.PROFILES && profilesSelectable) {
            suppressProfilesSelectionTopBar = true
        }
    }

    LaunchedEffect(pagerState.isScrollInProgress, currentPage, uiPage) {
        if (!pagerState.isScrollInProgress && currentPage == uiPage) {
            when (currentPage) {
                DashboardPage.DASHBOARD -> suppressAppsSelectionTopBar = false
                DashboardPage.BUNDLES -> suppressBundlesSelectionTopBar = false
                DashboardPage.PROFILES -> suppressProfilesSelectionTopBar = false
                DashboardPage.LSPOSED, DashboardPage.TOOLS -> Unit
            }
        }
    }

    fun previousVisibleTab(page: DashboardPage): DashboardPage {
        val currentIndex = visibleTabs.indexOf(page).takeIf { it >= 0 } ?: 0
        val previousIndex = if (currentIndex == 0) visibleTabs.lastIndex else currentIndex - 1
        return visibleTabs[previousIndex]
    }

    InterceptBackHandler(
        enabled = visibleTabs.size > 1 || appsSelectionActive || bundlesSelectable || profilesSelectable
    ) {
        when (currentPage) {
            DashboardPage.DASHBOARD -> {
                if (appsSelectionActive) {
                    installedAppsViewModel.clearSelection()
                } else {
                    (androidContext as? Activity)?.finish()
                }
            }

            DashboardPage.BUNDLES -> {
                if (bundlesSelectable) {
                    vm.cancelSourceSelection()
                } else {
                    composableScope.launch {
                        scrollToVisiblePage(previousVisibleTab(currentPage), animated = true)
                    }
                }
            }

            DashboardPage.PROFILES -> {
                if (profilesSelectable) {
                    patchProfilesViewModel.handleEvent(PatchProfilesViewModel.Event.CANCEL)
                } else {
                    composableScope.launch {
                        scrollToVisiblePage(previousVisibleTab(currentPage), animated = true)
                    }
                }
            }

            DashboardPage.LSPOSED, DashboardPage.TOOLS -> {
                composableScope.launch {
                    scrollToVisiblePage(previousVisibleTab(currentPage), animated = true)
                }
            }
        }
    }

    val quickActionApp = remember(quickActionPackage, installedApps) {
        quickActionPackage?.let { pkg -> installedApps.firstOrNull { it.currentPackageName == pkg } }
    }
    val quickActionViewModel = quickActionPackage?.let { pkg ->
        koinViewModel<InstalledAppInfoViewModel>(key = "quick-action-$pkg") { parametersOf(pkg) }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(
        lifecycleOwner,
        installedAppsViewModel,
        announcementSystemEnabled,
        vm,
        lsposedViewModel,
    ) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                installedAppsViewModel.refreshDeviceAndMountState()
                lsposedViewModel?.checkRootAccess()
                if (announcementSystemEnabled) {
                    vm.refreshAnnouncements(forceRefresh = true)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    SideEffect {
        quickActionViewModel?.onBackClick = {}
    }

    val quickRootDiagnosticsPermissionLauncher =
        rememberLauncherForActivityResult(permissionContract) { granted ->
            if (granted) {
                showQuickRootDiagnosticsExportPicker = true
            }
        }
    val quickRootDiagnosticsDocumentLauncher = rememberLauncherForActivityResult(
        contract = RememberedCreateDocument("text/plain") {
            rootMountDiagnosticsExportDirectory.takeIf(String::isNotBlank)?.let(Uri::parse)
        }
    ) { uri ->
        val actionViewModel = quickActionViewModel
        if (uri != null && actionViewModel != null) {
            composableScope.launch {
                prefs.rootMountDiagnosticsExportLastDirectory.update(
                    uri.toPickerDirectoryUri().toString()
                )
            }
            quickRootDiagnosticsExportInProgress = true
            actionViewModel.exportRootMountDiagnosticsToUri(uri) { success ->
                quickRootDiagnosticsExportInProgress = false
                if (success) showQuickRootDiagnosticsExportPicker = false
            }
        }
        pendingQuickRootDiagnosticsFileName = null
    }
    fun openQuickRootDiagnosticsExportPicker() {
        val packageName = quickActionViewModel?.installedApp?.currentPackageName
            ?: quickActionPackage
            ?: "diagnostics"
        val fileName = FilenameUtils.timestampedLogFileName("root-mount-$packageName")
        pendingQuickRootDiagnosticsFileName = fileName
        if (useCustomFilePicker) {
            if (fs.hasStoragePermission()) {
                showQuickRootDiagnosticsExportPicker = true
            } else {
                quickRootDiagnosticsPermissionLauncher.launch(permissionName)
            }
        } else {
            quickRootDiagnosticsDocumentLauncher.launch(fileName)
        }
    }

    if (showQuickInstallerPicker && quickActionViewModel != null) {
        InstallerPickerDialog(
            title = stringResource(R.string.installer_choose_for_this_install_title),
            options = installerManager.listEntries(
                target = InstallerManager.InstallTarget.SAVED_APP,
                includeNone = false
            ).filterNot { entry ->
                entry.token == InstallerManager.Token.AutoSaved &&
                    !quickActionViewModel.supportsRootMount
            },
            onDismiss = { showQuickInstallerPicker = false },
            onConfirm = quickActionViewModel::installSavedApp,
            onOpenShizuku = installerManager::openShizukuApp
        )
    }

    quickMountPromptMode?.let { mode ->
        val actionViewModel = quickActionViewModel ?: return@let
        val hasRoot = actionViewModel.rootInstaller.hasRootAccess()
        SavedAppMountPromptDialog(
            mode = mode,
            canMount = hasRoot,
            onDismiss = { quickMountPromptMode = null },
            onMount = {
                quickMountPromptMode = null
                actionViewModel.mountOrUnmount()
            },
            onChooseDifferentInstaller = {
                quickMountPromptMode = null
                showQuickInstallerPicker = true
            },
            onRemount = {
                quickMountPromptMode = null
                actionViewModel.remountSavedInstallation()
            }
        )
    }

    if (showQuickRootDiagnosticsActionsDialog) {
        quickActionViewModel?.let { actionViewModel ->
            RootMountDiagnosticsActionsDialog(
                onDismiss = { showQuickRootDiagnosticsActionsDialog = false },
                onCopy = {
                    showQuickRootDiagnosticsActionsDialog = false
                    composableScope.launch {
                        val content = actionViewModel.readRootMountDiagnostics() ?: return@launch
                        val clipboard = androidContext.getSystemService(ClipboardManager::class.java)
                        if (clipboard == null) {
                            androidContext.toast(
                                androidContext.getString(R.string.root_mount_diagnostics_copy_failed)
                            )
                            return@launch
                        }
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("Root mount diagnostics", content)
                        )
                        androidContext.toast(
                            androidContext.getString(R.string.root_mount_diagnostics_copy_success)
                        )
                    }
                },
                onExport = {
                    showQuickRootDiagnosticsActionsDialog = false
                    openQuickRootDiagnosticsExportPicker()
                }
            )
        }
    }

    if (showQuickRootDiagnosticsExportPicker && useCustomFilePicker) {
        PathSelectorDialog(
            roots = storageRoots,
            onSelect = { path ->
                if (path == null) {
                    showQuickRootDiagnosticsExportPicker = false
                    pendingQuickRootDiagnosticsFileName = null
                }
            },
            fileFilter = { false },
            allowDirectorySelection = true,
            fileTypeLabel = ".txt",
            confirmButtonText = stringResource(R.string.save),
            onConfirm = { directory ->
                quickRootDiagnosticsExportFileDialogState =
                    QuickRootDiagnosticsExportDialogState(
                        directory = directory,
                        fileName = pendingQuickRootDiagnosticsFileName
                            ?: FilenameUtils.timestampedLogFileName("root-mount")
                    )
            },
            lastDirectoryPreference = prefs.rootMountDiagnosticsExportLastDirectory
        )
    }

    quickRootDiagnosticsExportFileDialogState?.let { state ->
        ExportRootDiagnosticsFileNameDialog(
            initialName = state.fileName,
            onDismiss = {
                quickRootDiagnosticsExportFileDialogState = null
                pendingQuickRootDiagnosticsFileName = null
            },
            onConfirm = { fileName ->
                val trimmedName = fileName.trim()
                if (trimmedName.isBlank()) return@ExportRootDiagnosticsFileNameDialog
                val finalName = if (trimmedName.endsWith(".txt", ignoreCase = true)) {
                    trimmedName
                } else {
                    "$trimmedName.txt"
                }
                quickRootDiagnosticsExportFileDialogState = null
                pendingQuickRootDiagnosticsFileName = null
                val target = state.directory.resolve(finalName)
                if (Files.exists(target)) {
                    pendingQuickRootDiagnosticsExportConfirmation =
                        PendingQuickRootDiagnosticsExportConfirmation(
                            state.directory,
                            finalName
                        )
                } else {
                    val actionViewModel = quickActionViewModel
                    if (actionViewModel != null) {
                        quickRootDiagnosticsExportInProgress = true
                        actionViewModel.exportRootMountDiagnosticsToPath(target) { success ->
                            quickRootDiagnosticsExportInProgress = false
                            if (success) showQuickRootDiagnosticsExportPicker = false
                        }
                    }
                }
            }
        )
    }

    pendingQuickRootDiagnosticsExportConfirmation?.let { state ->
        ConfirmDialog(
            onDismiss = {
                pendingQuickRootDiagnosticsExportConfirmation = null
                quickRootDiagnosticsExportFileDialogState =
                    QuickRootDiagnosticsExportDialogState(state.directory, state.fileName)
            },
            onConfirm = {
                pendingQuickRootDiagnosticsExportConfirmation = null
                val actionViewModel = quickActionViewModel
                if (actionViewModel != null) {
                    quickRootDiagnosticsExportInProgress = true
                    actionViewModel.exportRootMountDiagnosticsToPath(
                        state.directory.resolve(state.fileName)
                    ) { success ->
                        quickRootDiagnosticsExportInProgress = false
                        if (success) showQuickRootDiagnosticsExportPicker = false
                    }
                }
            },
            title = stringResource(R.string.export_overwrite_title),
            description = stringResource(
                R.string.export_overwrite_description,
                state.fileName
            ),
            icon = Icons.Outlined.WarningAmber
        )
    }

    if (quickRootDiagnosticsExportInProgress) {
        AlertDialog(
            onDismissRequest = {},
            icon = {
                Icon(
                    Icons.Outlined.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(
                    stringResource(R.string.root_mount_diagnostics_save_title),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        stringResource(R.string.root_mount_diagnostics_exporting),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(999.dp))
                    )
                }
            },
            confirmButton = {},
            dismissButton = {},
            shape = RoundedCornerShape(28.dp)
        )
    }

    LaunchedEffect(
        quickActionViewModel?.installedApp?.currentPackageName,
        quickActionViewModel?.isMounted,
        quickActionViewModel?.supportsRootMount
    ) {
        val app = quickActionViewModel?.installedApp ?: return@LaunchedEffect
        val packageName = app.currentPackageName
        if (app.installType == InstallType.MOUNT && quickActionViewModel.supportsRootMount) {
            installedAppsViewModel.mountedOnDeviceMap[packageName] =
                quickActionViewModel.isMounted
        } else {
            installedAppsViewModel.mountedOnDeviceMap.remove(packageName)
        }
    }

    var showBundleFilePicker by rememberSaveable { mutableStateOf(false) }
    var selectedBundlePath by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedBundleUri by remember { mutableStateOf<Uri?>(null) }
    var showAddBundleDialog by rememberSaveable { mutableStateOf(false) }
    var showAddLsposedDialog by rememberSaveable { mutableStateOf(false) }
    var initialAddBundleRemoteUrl by rememberSaveable { mutableStateOf("") }
    val (bundlePermissionContract, bundlePermissionName) = remember { fs.permissionContract() }
    val bundlePermissionLauncher =
        rememberLauncherForActivityResult(bundlePermissionContract) { granted ->
            if (granted) {
                showBundleFilePicker = true
            }
        }
    val bundleDocumentLauncher = rememberLauncherForActivityResult(
        contract = RememberedGetContent {
            dashboardBundleInputDirectory.takeIf(String::isNotBlank)?.let(Uri::parse)
        }
    ) { uri ->
        if (uri != null) {
            composableScope.launch {
                prefs.dashboardBundleInputLastDirectory.update(uri.toPickerDirectoryUri().toString())
            }
            selectedBundleUri = uri
            val displayName = runCatching {
                androidContext.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1 && cursor.moveToFirst()) cursor.getString(index) else null
                }
            }.getOrNull()
            selectedBundlePath = displayName ?: uri.lastPathSegment ?: uri.toString()
        }
    }
    fun requestBundleFilePicker() {
        if (useCustomFilePicker) {
            if (fs.hasStoragePermission()) {
                showBundleFilePicker = true
            } else {
                bundlePermissionLauncher.launch(bundlePermissionName)
            }
        } else {
            // Bundle extensions can have different MIME types across document providers.
            bundleDocumentLauncher.launch("*/*")
        }
    }

    var showSplitInputPicker by rememberSaveable { mutableStateOf(false) }
    var showSplitSourceDialog by rememberSaveable { mutableStateOf(false) }
    var showSplitPluginDialog by rememberSaveable { mutableStateOf(false) }
    var showSplitInstalledAppsDialog by rememberSaveable { mutableStateOf(false) }
    var pendingSplitInstalledPackage by rememberSaveable { mutableStateOf<String?>(null) }
    val splitInstalledAppsFlow = remember(showSplitInstalledAppsDialog, pm) {
        if (showSplitInstalledAppsDialog) {
            pm.installedAppList.map { apps -> apps as List<AppInfo>? }
        } else {
            flowOf<List<AppInfo>?>(null)
        }
    }
    val splitInstalledApps by splitInstalledAppsFlow.collectAsStateWithLifecycle(initialValue = null)
    val showSplitMergeLoading = pendingSplitInstalledPackage != null ||
        splitMergeState.preparingSelection ||
        (showSplitInstalledAppsDialog && splitInstalledApps == null)
    val splitMergeDownloadLoading = splitMergeState.preparingSelection &&
        splitMergeState.downloadStep.status == SplitMergeStepStatus.RUNNING
    val splitMergeLoadingMessage = splitMergeState.downloadStep.let { step ->
        val current = step.progressCurrent
        val total = step.progressTotal
        when {
            splitMergeDownloadLoading && current != null && total != null && total > 0L ->
                "${(current.coerceIn(0L, total) * 100L) / total}%"
            splitMergeDownloadLoading && current != null && current > 0L ->
                stringResource(
                    R.string.merge_split_apk_downloading_bytes,
                    Formatter.formatShortFileSize(androidContext, current)
                )
            splitMergeDownloadLoading -> stringResource(R.string.merge_split_apk_downloading)
            else -> null
        }
    }
    val canCancelSplitMergeLoading = splitMergeDownloadLoading && !splitMergeState.cancellationInProgress
    var splitPluginPackageName by rememberSaveable { mutableStateOf("") }
    var splitPluginVersion by rememberSaveable { mutableStateOf("") }
    var pendingSplitPermissionRequest by rememberSaveable {
        mutableStateOf<SplitPermissionRequest?>(null)
    }
    val splitPermissionLauncher =
        rememberLauncherForActivityResult(permissionContract) { granted ->
            if (granted) {
                when (pendingSplitPermissionRequest) {
                    SplitPermissionRequest.INPUT -> showSplitInputPicker = true
                    null -> Unit
                }
            }
            pendingSplitPermissionRequest = null
        }
    val splitInputDocumentLauncher = rememberLauncherForActivityResult(
        contract = RememberedGetContent {
            dashboardSplitInputDirectory.takeIf(String::isNotBlank)?.let(Uri::parse)
        }
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        composableScope.launch {
            prefs.dashboardSplitInputLastDirectory.update(uri.toPickerDirectoryUri().toString())
        }
        val displayName = runCatching {
            androidContext.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()
        val resolvedName = displayName ?: uri.lastPathSegment ?: "split.apks"
        if (!isAllowedSplitArchiveName(resolvedName)) {
            androidContext.toast(androidContext.getString(R.string.merge_split_apk_input_invalid))
            return@rememberLauncherForActivityResult
        }
        vm.clearSplitMergeState()
        vm.startSplitMergeFromUri(
            inputUri = uri,
            inputDisplayName = resolvedName
        )
    }

    fun launchSplitMergeFromStorage() {
        vm.clearSplitMergeState()
        if (useCustomFilePicker) {
            if (fs.hasStoragePermission()) {
                showSplitInputPicker = true
            } else {
                pendingSplitPermissionRequest = SplitPermissionRequest.INPUT
                splitPermissionLauncher.launch(permissionName)
            }
        } else {
            splitInputDocumentLauncher.launch("application/*")
        }
    }

    fun launchSplitMerge() {
        showSplitSourceDialog = true
    }

    LaunchedEffect(pendingSplitInstalledPackage) {
        val packageName = pendingSplitInstalledPackage ?: return@LaunchedEffect
        withFrameNanos { }
        vm.clearSplitMergeState()
        vm.startSplitMergeFromInstalledPackage(packageName)
        pendingSplitInstalledPackage = null
    }

    var showSavedAppsExportPicker by rememberSaveable { mutableStateOf(false) }
    var savedAppsExportInProgress by rememberSaveable { mutableStateOf(false) }
    val (exportPermissionContract, exportPermissionName) = remember { fs.permissionContract() }
    val exportPermissionLauncher =
        rememberLauncherForActivityResult(exportPermissionContract) { granted ->
            if (granted) {
                showSavedAppsExportPicker = true
            }
        }
    val savedAppsExportTreeLauncher = rememberLauncherForActivityResult(
        contract = RememberedOpenDocumentTree {
            dashboardSavedAppsExportDirectory.takeIf(String::isNotBlank)?.let(Uri::parse)
        }
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        composableScope.launch {
            prefs.dashboardSavedAppsExportLastDirectory.update(uri.toString())
        }
        savedAppsExportInProgress = true
        installedAppsViewModel.exportSelectedSavedAppsToTreeUri(
            context = androidContext,
            treeUri = uri,
            exportTemplate = exportFormat
        ) { result ->
            savedAppsExportInProgress = false
            when {
                result.total == 0 -> androidContext.toast(
                    androidContext.getString(R.string.saved_apps_export_empty)
                )
                result.exported > 0 -> androidContext.toast(
                    androidContext.getString(
                        R.string.saved_apps_export_success,
                        result.exported
                    )
                )
                else -> androidContext.toast(
                    androidContext.getString(R.string.saved_apps_export_failed)
                )
            }
        }
    }
    fun requestSavedAppsExportPicker() {
        if (useCustomFilePicker) {
            if (fs.hasStoragePermission()) {
                showSavedAppsExportPicker = true
            } else {
                exportPermissionLauncher.launch(exportPermissionName)
            }
        } else {
            savedAppsExportTreeLauncher.launch(null)
        }
    }

    val dashboardSidePadding = 16.dp
    fun resolveQuickExportName(app: InstalledApp): String {
        val displayPackageName = if (app.installType == InstallType.SAVED) {
            app.originalPackageName.takeIf { it.isNotBlank() }
                ?: savedAppBasePackage(app.currentPackageName)
        } else {
            app.currentPackageName
        }
        val label = installedAppsViewModel.packageInfoMap[app.currentPackageName]
            ?.applicationInfo
            ?.loadLabel(androidContext.packageManager)
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: displayPackageName
        val patchBundles = installedAppsViewModel.bundleSummaries[app.currentPackageName]
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
        val exportData = PatchedAppExportData(
            appName = label,
            packageName = installedAppsViewModel.packageInfoMap[app.currentPackageName]?.packageName
                ?: displayPackageName,
            appVersion = app.version,
            patchBundles = patchBundles,
            fileExtension = quickActionViewModel?.savedFileExtension ?: "apk"
        )
        return ExportNameFormatter.format(exportFormat, exportData)
    }
    val quickExportDocumentLauncher = rememberLauncherForActivityResult(
        contract = RememberedCreateDocument(if (quickActionViewModel?.savedFileExtension == "apks") "application/zip"
            else "application/vnd.android.package-archive") {
            dashboardQuickExportDirectory.takeIf(String::isNotBlank)?.let(Uri::parse)
        }
    ) { uri ->
        val viewModel = quickActionViewModel
        if (uri != null && viewModel != null) {
            composableScope.launch {
                prefs.dashboardQuickExportLastDirectory.update(uri.toPickerDirectoryUri().toString())
            }
            viewModel.exportSavedApp(uri)
        }
        showQuickExportPicker = false
    }

    LaunchedEffect(useCustomFilePicker) {
        if (!useCustomFilePicker) {
            showStorageDialog = false
            showBundleFilePicker = false
            showSavedAppsExportPicker = false
            showSplitInputPicker = false
            pendingSplitPermissionRequest = null
            quickExportDialogState = null
            pendingQuickExportConfirmation = null
            showQuickRootDiagnosticsExportPicker = false
            quickRootDiagnosticsExportFileDialogState = null
            pendingQuickRootDiagnosticsExportConfirmation = null
            pendingQuickRootDiagnosticsFileName = null
        }
    }

    @Composable
    fun BundleProgressBanner(modifier: Modifier = Modifier) {
        val bannerSizeSpec = tween<IntSize>(durationMillis = 260, easing = FastOutSlowInEasing)
        val bannerOffsetSpec = tween<IntOffset>(durationMillis = 260, easing = FastOutSlowInEasing)
        val bannerExitAlphaSpec = tween<Float>(durationMillis = 240, easing = FastOutSlowInEasing)
        var lastImportBannerOrder by remember { mutableStateOf(Long.MAX_VALUE) }
        var lastUpdateBannerOrder by remember { mutableStateOf(Long.MAX_VALUE) }
        val importBannerOrder = bundleImportProgress?.startedOrder ?: lastImportBannerOrder
        val updateBannerOrder = bundleUpdateProgress?.startedOrder ?: lastUpdateBannerOrder
        val bannerOrder = remember(importBannerOrder, updateBannerOrder) {
            BundleProgressBannerType.entries.sortedWith(
                compareBy<BundleProgressBannerType> { bannerType ->
                    when (bannerType) {
                        BundleProgressBannerType.IMPORT -> importBannerOrder
                        BundleProgressBannerType.UPDATE -> updateBannerOrder
                    }
                }.thenBy { it.ordinal }
            )
        }
        SideEffect {
            bundleImportProgress?.startedOrder?.let { lastImportBannerOrder = it }
            bundleUpdateProgress?.startedOrder?.let { lastUpdateBannerOrder = it }
        }

        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            bannerOrder.forEach { bannerType ->
                key(bannerType) {
                    when (bannerType) {
                        BundleProgressBannerType.IMPORT -> AnimatedVisibility(
                            visible = bundleImportProgress != null,
                            enter = fadeIn(animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)) +
                                expandVertically(expandFrom = Alignment.Top, animationSpec = bannerSizeSpec) +
                                slideInVertically(
                                    initialOffsetY = { height -> -height / 3 },
                                    animationSpec = bannerOffsetSpec
                                ),
                            exit = fadeOut(animationSpec = bannerExitAlphaSpec) +
                                shrinkVertically(shrinkTowards = Alignment.Top, animationSpec = bannerSizeSpec) +
                                slideOutVertically(
                                    targetOffsetY = { height -> -height / 3 },
                                    animationSpec = bannerOffsetSpec
                                )
                        ) {
                            bundleImportProgress?.let { progress ->
                                val context = LocalContext.current
                                val total = progress.total.coerceAtLeast(1)
                                val bundleCount = progress.bundleCount.coerceAtLeast(1)
                                val collapsedCount = if (progress.isStepBased) {
                                    (progress.processed + 1).coerceIn(1, total)
                                } else {
                                    progress.processed.coerceIn(0, total)
                                }
                                val subtitleParts = buildList {
                                    val stepLabel = if (progress.isStepBased) {
                                        val step = collapsedCount
                                        stringResource(R.string.import_patch_bundles_banner_steps, step, total)
                                    } else {
                                        pluralStringResource(R.plurals.bundle_update_progress_quantity, total, collapsedCount, total)
                                    }
                                    add(stepLabel)
                                    val name = progress.currentBundleName?.takeIf { it.isNotBlank() } ?: return@buildList
                                    val phaseText = if (progress.isStepBased) {
                                        when (progress.phase) {
                                            BundleImportPhase.Downloading ->
                                                stringResource(R.string.bundle_import_phase_copying)
                                            BundleImportPhase.Processing ->
                                                stringResource(R.string.bundle_import_phase_writing)
                                            BundleImportPhase.Finalizing ->
                                                stringResource(R.string.bundle_import_phase_finalizing)
                                        }
                                    } else {
                                        when (progress.phase) {
                                            BundleImportPhase.Processing ->
                                                stringResource(R.string.bundle_import_phase_processing)
                                            BundleImportPhase.Downloading ->
                                                stringResource(R.string.bundle_import_phase_downloading)
                                            BundleImportPhase.Finalizing ->
                                                stringResource(R.string.bundle_import_phase_finalizing_short)
                                        }
                                    }
                                    val detail = buildString {
                                        append(phaseText)
                                        append(": ")
                                        append(name)
                                        if (progress.bytesTotal?.takeIf { it > 0L } != null) {
                                            append(" (")
                                            append(Formatter.formatShortFileSize(context, progress.bytesRead))
                                            progress.bytesTotal?.takeIf { it > 0L }?.let { total ->
                                                append("/")
                                                append(Formatter.formatShortFileSize(context, total))
                                            }
                                            append(")")
                                        }
                                    }
                                    add(detail)
                                }
                                DownloadProgressBanner(
                                    title = pluralStringResource(
                                        R.plurals.import_patch_bundles_banner_title_quantity,
                                        bundleCount
                                    ),
                                    subtitle = subtitleParts.joinToString(" - "),
                                    progress = progress.ratio,
                                    collapsedLabel = pluralStringResource(
                                        R.plurals.import_patch_bundles_banner_collapsed_quantity,
                                        bundleCount,
                                        collapsedCount,
                                        total
                                    ),
                                    collapsed = bundleImportBannerCollapsed,
                                    onToggleCollapsed = {
                                        composableScope.launch {
                                            prefs.dashboardBundleImportBannerCollapsed.update(
                                                !bundleImportBannerCollapsed
                                            )
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = dashboardSidePadding, vertical = 8.dp)
                                )
                            }
                        }

                        BundleProgressBannerType.UPDATE -> AnimatedVisibility(
                            visible = bundleUpdateProgress != null,
                            enter = fadeIn(animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)) +
                                expandVertically(expandFrom = Alignment.Top, animationSpec = bannerSizeSpec) +
                                slideInVertically(
                                    initialOffsetY = { height -> -height / 3 },
                                    animationSpec = bannerOffsetSpec
                                ),
                            exit = fadeOut(animationSpec = bannerExitAlphaSpec) +
                                shrinkVertically(shrinkTowards = Alignment.Top, animationSpec = bannerSizeSpec) +
                                slideOutVertically(
                                    targetOffsetY = { height -> -height / 3 },
                                    animationSpec = bannerOffsetSpec
                                )
                        ) {
                            bundleUpdateProgress?.let { progress ->
                                val context = LocalContext.current
                                val perBundleFraction = progress.bytesTotal
                                    ?.takeIf { it > 0L }
                                    ?.let { total -> (progress.bytesRead.toFloat() / total).coerceIn(0f, 1f) }

                                val progressFraction: Float? = when {
                                    progress.total == 0 -> 0f
                                    progress.phase == BundleUpdatePhase.Downloading && perBundleFraction != null ->
                                        ((progress.completed.toFloat() + perBundleFraction) / progress.total).coerceIn(0f, 1f)
                                    progress.phase == BundleUpdatePhase.Downloading ->
                                        null

                                    else -> (progress.completed.toFloat() / progress.total).coerceIn(0f, 1f)
                                }

                                val subtitleParts = buildList {
                                    add(
                                        pluralStringResource(
                                            R.plurals.bundle_update_progress_quantity,
                                            progress.total,
                                            progress.completed,
                                            progress.total
                                        )
                                    )
                                    val name = progress.currentBundleName?.takeIf { it.isNotBlank() } ?: return@buildList
                                    val phaseText = when (progress.phase) {
                                        BundleUpdatePhase.Checking ->
                                            stringResource(R.string.bundle_update_phase_checking)
                                        BundleUpdatePhase.Downloading ->
                                            stringResource(R.string.bundle_update_phase_downloading)
                                        BundleUpdatePhase.Finalizing ->
                                            stringResource(R.string.bundle_update_phase_finalizing)
                                    }

                                    val detail = buildString {
                                        append(phaseText)
                                        append(": ")
                                        append(name)
                                        if (progress.phase == BundleUpdatePhase.Downloading && progress.bytesRead > 0L) {
                                            append(" (")
                                            append(Formatter.formatShortFileSize(context, progress.bytesRead))
                                            progress.bytesTotal?.takeIf { it > 0L }?.let { total ->
                                                append("/")
                                                append(Formatter.formatShortFileSize(context, total))
                                            }
                                            append(")")
                                        }
                                    }
                                    add(detail)
                                }
                                DownloadProgressBanner(
                                    title = pluralStringResource(
                                        R.plurals.bundle_update_banner_title_quantity,
                                        progress.total,
                                    ),
                                    subtitle = subtitleParts.joinToString(" - "),
                                    progress = progressFraction,
                                    collapsedLabel = pluralStringResource(
                                        R.plurals.bundle_update_banner_collapsed_quantity,
                                        progress.total,
                                        progress.completed.coerceAtMost(progress.total),
                                        progress.total
                                    ),
                                    collapsed = bundleUpdateBannerCollapsed,
                                    onToggleCollapsed = {
                                        composableScope.launch {
                                            prefs.dashboardBundleUpdateBannerCollapsed.update(
                                                !bundleUpdateBannerCollapsed
                                            )
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = dashboardSidePadding, vertical = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(patchBundleFileIntent) {
        val fileIntent = patchBundleFileIntent ?: return@LaunchedEffect
        try {
            val bundleIndex = pageIndexByType[DashboardPage.BUNDLES]
            if (bundleIndex != null && pagerState.currentPage != bundleIndex) {
                runCatching {
                    scrollToVisiblePage(DashboardPage.BUNDLES, animated = true)
                }.onFailure {
                    scrollToVisiblePage(DashboardPage.BUNDLES, animated = false)
                }
            }
            vm.preparePatchBundleFileImport(fileIntent)
        } finally {
            onPatchBundleFileIntentConsumed()
        }
    }

    LaunchedEffect(bundleDeepLink) {
        val deepLink = bundleDeepLink ?: return@LaunchedEffect
        highlightBundleUid = deepLink.bundleUid
        try {
            val bundleIndex = pageIndexByType[DashboardPage.BUNDLES]
            if (bundleIndex != null && pagerState.currentPage != bundleIndex) {
                runCatching {
                    scrollToVisiblePage(DashboardPage.BUNDLES, animated = true)
                }.onFailure {
                    scrollToVisiblePage(DashboardPage.BUNDLES, animated = false)
                }
            }
            deepLink.importUrl?.trim()?.takeIf { it.isNotBlank() }?.let { url ->
                initialAddBundleRemoteUrl = url
                selectedBundlePath = null
                selectedBundleUri = null
                showAddBundleDialog = true
            }
        } finally {
            onBundleDeepLinkConsumed()
        }
    }

    LaunchedEffect(
        pendingQuickAction,
        quickActionViewModel?.installedApp,
        quickActionViewModel?.appliedPatches
    ) {
        val action = pendingQuickAction ?: return@LaunchedEffect
        val actionViewModel = quickActionViewModel ?: return@LaunchedEffect
        val actionApp = actionViewModel.installedApp ?: return@LaunchedEffect

        when (action) {
            InstalledAppAction.OPEN -> {
                actionViewModel.launch()
                pendingQuickAction = null
            }
            InstalledAppAction.EXPORT -> {
                showQuickExportPicker = true
                pendingQuickAction = null
            }
            InstalledAppAction.INSTALL_OR_UPDATE -> {
                if (
                    actionApp.installType == InstallType.MOUNT &&
                    actionViewModel.supportsRootMount
                ) {
                    if (chooseInstallerPerInstall) {
                        if (actionViewModel.isMounted) {
                            if (!actionViewModel.rootInstaller.hasRootAccess()) {
                                androidContext.toast(androidContext.getString(R.string.installer_status_requires_root))
                            } else {
                                quickMountPromptMode = SavedAppMountPromptMode.REMOUNT
                            }
                        } else {
                            quickMountPromptMode = SavedAppMountPromptMode.MOUNT_OR_INSTALL
                        }
                    } else if (!actionViewModel.primaryInstallerIsMount) {
                        val mountAction = if (actionViewModel.isMounted) {
                            MountWarningAction.UPDATE
                        } else {
                            MountWarningAction.INSTALL
                        }
                        actionViewModel.showMountWarning(
                            mountAction,
                            MountWarningReason.PRIMARY_NOT_MOUNT_FOR_MOUNT_APP
                        )
                    } else {
                        if (actionViewModel.isMounted) {
                            actionViewModel.remountSavedInstallation()
                        } else {
                            actionViewModel.mountOrUnmount()
                        }
                    }
                } else if (chooseInstallerPerInstall) {
                    showQuickInstallerPicker = true
                } else if (
                    actionViewModel.primaryInstallerIsMount &&
                    actionViewModel.supportsRootMount
                ) {
                    val mountAction = if (actionViewModel.isInstalledOnDevice) {
                        MountWarningAction.UPDATE
                    } else {
                        MountWarningAction.INSTALL
                    }
                    actionViewModel.showMountWarning(
                        mountAction,
                        MountWarningReason.PRIMARY_IS_MOUNT_FOR_NON_MOUNT_APP
                    )
                } else {
                    actionViewModel.installSavedApp()
                }
                pendingQuickAction = null
            }
            InstalledAppAction.REPAIR_ROOT_MOUNT -> {
                actionViewModel.repairRootMount()
                pendingQuickAction = null
            }
            InstalledAppAction.EXPORT_ROOT_MOUNT_DIAGNOSTICS -> {
                showQuickRootDiagnosticsActionsDialog = true
                pendingQuickAction = null
            }
            InstalledAppAction.UNINSTALL -> {
                if (
                    actionApp.installType == InstallType.MOUNT &&
                    actionViewModel.supportsRootMount
                ) {
                    actionViewModel.unmountSavedInstallation()
                } else if (
                    actionViewModel.primaryInstallerIsMount &&
                    actionViewModel.supportsRootMount
                ) {
                    actionViewModel.showMountWarning(
                        MountWarningAction.UNINSTALL,
                        MountWarningReason.PRIMARY_IS_MOUNT_FOR_NON_MOUNT_APP
                    )
                } else {
                    showQuickSavedUninstallDialog = true
                }
                pendingQuickAction = null
            }
            InstalledAppAction.DELETE -> {
                showQuickDeleteDialog = true
                pendingQuickAction = null
            }
            InstalledAppAction.REPATCH -> {
                val selection = actionViewModel.getRepatchSelection()
                    ?: installedAppsViewModel.getRepatchSelection(actionApp)
                if (selection == null) {
                    val hasPayload = actionApp.selectionPayload != null
                    if (!hasPayload) {
                        androidContext.toast(androidContext.getString(R.string.no_patches_selected))
                        pendingQuickAction = null
                    }
                    return@LaunchedEffect
                }
                if (patchBundleRepository.selectionHasMixedBundleTypes(selection)) {
                    showQuickMixedBundleDialog = true
                    pendingQuickAction = null
                    return@LaunchedEffect
                }
                if (patchBundleRepository.selectionHasMixedRevancedPatcherVersions(selection)) {
                    showQuickMixedRevancedPatcherDialog = true
                    pendingQuickAction = null
                    return@LaunchedEffect
                }
                val payload = actionApp.selectionPayload
                val persistConfiguration = actionApp.installType != InstallType.SAVED
                runRepatchOrWarn(actionApp) {
                    mainVm.selectApp(
                        packageName = actionApp.originalPackageName,
                        patches = selection,
                        selectionPayload = payload,
                        persistConfiguration = persistConfiguration,
                        returnToDashboard = true,
                        sourceEntryKey = actionApp.currentPackageName
                    )
                }
                pendingQuickAction = null
            }
        }
    }

    val firstLaunch by vm.prefs.firstLaunch.getAsState()
    if (firstLaunch) AutoUpdatesDialog(vm::applyAutoUpdatePrefs)

    if (showStorageDialog && useCustomFilePicker) {
        PathSelectorDialog(
            roots = storageRoots,
            onSelect = { path ->
                showStorageDialog = false
                path?.let { storageVm.handleStorageFile(File(it.toString())) }
            },
            fileFilter = ::isAllowedApkFile,
            allowDirectorySelection = false,
            lastDirectoryPreference = prefs.dashboardApkInputLastDirectory
        )
    }
    storageVm.universalFallbackDialogSubject?.let {
        UniversalFallbackVersionDialog(
            onContinue = storageVm::continueWithUniversalFallback,
            onDismiss = storageVm::dismissUniversalFallbackDialog
        )
    }
    storageVm.nonSuggestedVersionDialogSubject?.let { local ->
        NonSuggestedVersionDialog(
            suggestedVersion = storageVm.nonSuggestedVersionDialogSuggestedVersion
                ?.takeUnless { it.isBlank() }
                ?: storageSuggestedVersions[local.packageName].orEmpty().ifBlank { local.version },
            suggestedVersionCodes = storageVm.nonSuggestedVersionDialogSuggestedVersionCodes,
            requiresUniversalPatchesEnabled = storageVm.nonSuggestedVersionDialogRequiresUniversalEnabled,
            onDismiss = storageVm::dismissNonSuggestedVersionDialog
        )
    }
    if (showBundleFilePicker && useCustomFilePicker) {
        PathSelectorDialog(
            roots = storageRoots,
            onSelect = { path ->
                showBundleFilePicker = false
                selectedBundleUri = null
                path?.let { selectedBundlePath = it.toString() }
            },
            fileFilter = ::isAllowedPatchBundleFile,
            allowDirectorySelection = false,
            lastDirectoryPreference = prefs.dashboardBundleInputLastDirectory
        )
    }
    if (showSplitSourceDialog) {
        MergeSplitSourceDialog(
            hasDownloaderPlugins = downloaderPlugins.isNotEmpty(),
            onDismissRequest = { showSplitSourceDialog = false },
            onSelectStorage = {
                showSplitSourceDialog = false
                launchSplitMergeFromStorage()
            },
            onSelectInstalled = {
                showSplitSourceDialog = false
                showSplitInstalledAppsDialog = true
            },
            onSelectDownloader = {
                showSplitSourceDialog = false
                if (downloaderPlugins.isEmpty()) {
                    androidContext.toast(
                        androidContext.getString(R.string.tools_merge_split_source_plugin_unavailable_plugins)
                    )
                    return@MergeSplitSourceDialog
                }
                splitPluginPackageName = ""
                splitPluginVersion = ""
                showSplitPluginDialog = true
            }
        )
    }
    if (showSplitInstalledAppsDialog) {
        splitInstalledApps?.let { apps ->
            MergeSplitInstalledAppsDialog(
                apps = apps,
                pm = pm,
                onDismissRequest = { showSplitInstalledAppsDialog = false },
                onSelectApp = { packageName ->
                    showSplitInstalledAppsDialog = false
                    pendingSplitInstalledPackage = packageName
                }
            )
        }
    }
    if (showSplitPluginDialog) {
        DownloaderPluginInputDialog(
            plugins = downloaderPlugins,
            activePluginId = vm.activeSplitMergePluginId,
            packageName = splitPluginPackageName,
            version = splitPluginVersion,
            onPackageNameChange = { splitPluginPackageName = it },
            onVersionChange = { splitPluginVersion = it },
            onDismissRequest = { showSplitPluginDialog = false },
            onSelectPlugin = { plugin ->
                vm.clearSplitMergeState()
                vm.startSplitMergeFromPlugin(
                    plugin = plugin,
                    packageName = splitPluginPackageName,
                    version = splitPluginVersion.takeIf { it.isNotBlank() }
                )
                showSplitPluginDialog = false
            }
        )
    }
    if (showSplitInputPicker && useCustomFilePicker) {
        PathSelectorDialog(
            roots = storageRoots,
            onSelect = { path ->
                showSplitInputPicker = false
                if (path == null) return@PathSelectorDialog
                vm.clearSplitMergeState()
                vm.startSplitMergeFromPath(path.toString())
            },
            fileFilter = ::isAllowedSplitArchiveFile,
            allowDirectorySelection = false,
            lastDirectoryPreference = prefs.dashboardSplitInputLastDirectory
        )
    }
    if (showSplitMergeLoading) {
        TransparentLoadingDialog(
            message = splitMergeLoadingMessage,
            cancelButtonText = if (canCancelSplitMergeLoading) {
                stringResource(R.string.merge_split_apk_download_cancel)
            } else {
                null
            },
            onCancel = if (canCancelSplitMergeLoading) vm::cancelSplitMerge else null
        )
    }
    if (showSavedAppsExportPicker && useCustomFilePicker) {
        PathSelectorDialog(
            roots = storageRoots,
            onSelect = { path ->
                if (path == null) {
                    showSavedAppsExportPicker = false
                }
            },
            fileFilter = ::isAllowedApkFile,
            allowDirectorySelection = true,
            confirmButtonText = stringResource(R.string.save),
            onConfirm = { selection ->
                val exportDirectory = if (Files.isDirectory(selection)) {
                    selection
                } else {
                    selection.parent ?: selection
                }
                savedAppsExportInProgress = true
                installedAppsViewModel.exportSelectedSavedAppsToDirectory(
                    androidContext,
                    exportDirectory,
                    exportFormat
                ) { result ->
                    savedAppsExportInProgress = false
                    showSavedAppsExportPicker = false
                    when {
                        result.total == 0 -> androidContext.toast(
                            androidContext.getString(R.string.saved_apps_export_empty)
                        )
                        result.exported > 0 -> androidContext.toast(
                            androidContext.getString(
                                R.string.saved_apps_export_success,
                                result.exported
                            )
                        )
                        else -> androidContext.toast(
                            androidContext.getString(R.string.saved_apps_export_failed)
                        )
                    }
                }
            },
            lastDirectoryPreference = prefs.dashboardSavedAppsExportLastDirectory
        )
    }
    if (savedAppsExportInProgress) {
        AlertDialog(
            onDismissRequest = {},
            icon = {
                Icon(
                    Icons.Outlined.Save,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(
                    stringResource(R.string.export),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        stringResource(R.string.patcher_step_group_saving),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(999.dp))
                    )
                }
            },
            confirmButton = {},
            dismissButton = {},
            shape = RoundedCornerShape(28.dp)
        )
    }

    if (showAddBundleDialog) {
        ImportPatchBundleDialog(
            onDismiss = {
                showAddBundleDialog = false
                initialAddBundleRemoteUrl = ""
            },
            onLocalSubmit = { path ->
                showAddBundleDialog = false
                initialAddBundleRemoteUrl = ""
                selectedBundlePath = null
                val selectedUri = selectedBundleUri
                selectedBundleUri = null
                if (selectedUri != null) {
                    vm.createLocalSource(selectedUri)
                } else {
                    vm.createLocalSourceFromFile(path)
                }
            },
            onRemoteSubmit = { url, autoUpdate, searchUpdate ->
                showAddBundleDialog = false
                initialAddBundleRemoteUrl = ""
                vm.createRemoteSource(url, autoUpdate, searchUpdate)
            },
            onLocalPick = {
                requestBundleFilePicker()
            },
            selectedLocalPath = selectedBundlePath,
            initialAutoUpdate = patchBundleImportAutoUpdate,
            initialSearchUpdate = patchBundleImportSearchUpdate,
            onAutoUpdateChange = vm::setPatchBundleImportAutoUpdate,
            onSearchUpdateChange = vm::setPatchBundleImportSearchUpdate,
            initialRemoteUrl = initialAddBundleRemoteUrl
        )
    }

    vm.pendingPatchBundleFileImport?.let { pendingImport ->
        PatchBundleFileImportDialog(
            manifest = pendingImport.manifest,
            fileName = pendingImport.fileIntent.displayName,
            onConfirm = vm::confirmPatchBundleFileImport,
            onDismiss = vm::dismissPatchBundleFileImport
        )
    }

    var showUpdateDialog by rememberSaveable { mutableStateOf(vm.prefs.showManagerUpdateDialogOnLaunch.getBlocking()) }
    val availableUpdate by remember {
        derivedStateOf { vm.updatedManagerRelease.takeIf { showUpdateDialog } }
    }

    availableUpdate?.let { releaseInfo ->
        AvailableUpdateDialog(
            onDismiss = { showUpdateDialog = false },
            setShowManagerUpdateDialogOnLaunch = vm::setShowManagerUpdateDialogOnLaunch,
            onConfirm = onUpdateClick,
            releaseInfo = releaseInfo,
            showFullChangelog = showManagerUpdateChangelog
        )
    }

    var showAndroid11Dialog by rememberSaveable { mutableStateOf(false) }
    var pendingAppInputAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val installAppsPermissionLauncher =
        rememberLauncherForActivityResult(RequestInstallAppsContract) { granted ->
            showAndroid11Dialog = false
            if (granted) {
                (pendingAppInputAction ?: onAppSelectorClick)()
                pendingAppInputAction = null
            }
        }
    if (showAndroid11Dialog) Android11Dialog(
        onDismissRequest = {
            showAndroid11Dialog = false
            pendingAppInputAction = null
        },
        onContinue = {
            installAppsPermissionLauncher.launch(androidContext.packageName)
        }
    )

    fun attemptAppInput(action: () -> Unit) {
        pendingAppInputAction = null
        vm.cancelSourceSelection()
        installedAppsViewModel.clearSelection()
        patchProfilesViewModel.handleEvent(PatchProfilesViewModel.Event.CANCEL)

        if (availablePatches < 1) {
            androidContext.toast(androidContext.getString(R.string.no_patch_found))
            composableScope.launch {
                scrollToVisiblePage(DashboardPage.BUNDLES, animated = true)
            }
            return
        }

        if (vm.android11BugActive) {
            pendingAppInputAction = action
            showAndroid11Dialog = true
            return
        }

        action()
    }

    var showDeleteSavedAppsDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirmationDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteProfilesConfirmationDialog by rememberSaveable { mutableStateOf(false) }
    if (showDeleteSavedAppsDialog) {
        ConfirmDialog(
            onDismiss = { showDeleteSavedAppsDialog = false },
            onConfirm = {
                installedAppsViewModel.deleteSelectedApps()
                showDeleteSavedAppsDialog = false
            },
            title = stringResource(R.string.delete),
            description = pluralStringResource(
                R.plurals.selected_apps_delete_dialog_description_quantity,
                selectedAppCount,
                selectedAppCount
            ),
            icon = Icons.Outlined.Delete
        )
    }
    if (showDeleteConfirmationDialog) {
        ConfirmDialog(
            onDismiss = { showDeleteConfirmationDialog = false },
            onConfirm = {
                vm.deleteSources()
                showDeleteConfirmationDialog = false
            },
            title = stringResource(R.string.delete),
            description = pluralStringResource(
                R.plurals.patches_delete_multiple_dialog_description_quantity,
                selectedSourceCount,
                selectedSourceCount
            ),
            icon = Icons.Outlined.Delete
        )
    }
    if (showDeleteProfilesConfirmationDialog) {
        ConfirmDialog(
            onDismiss = { showDeleteProfilesConfirmationDialog = false },
            onConfirm = {
                patchProfilesViewModel.handleEvent(PatchProfilesViewModel.Event.DELETE_SELECTED)
                showDeleteProfilesConfirmationDialog = false
            },
            title = stringResource(R.string.delete),
            description = pluralStringResource(
                R.plurals.patch_profile_delete_multiple_dialog_description_quantity,
                selectedProfileCount,
                selectedProfileCount
            ),
            icon = Icons.Outlined.Delete
        )
    }

    val quickExportApp = quickActionViewModel?.installedApp
    if (showQuickExportPicker && quickExportApp != null && useCustomFilePicker) {
        PathSelectorDialog(
            roots = storageRoots,
            onSelect = { path ->
                if (path == null) {
                    showQuickExportPicker = false
                }
            },
            fileFilter = ::isAllowedApkFile,
            allowDirectorySelection = false,
            fileTypeLabel = ".apk",
            confirmButtonText = stringResource(R.string.save),
            onConfirm = { directory ->
                val exportName = resolveQuickExportName(quickExportApp)
                quickExportDialogState = QuickExportDialogState(directory, exportName)
            },
            lastDirectoryPreference = prefs.dashboardQuickExportLastDirectory
        )
    }
    LaunchedEffect(showQuickExportPicker, quickExportApp?.currentPackageName, useCustomFilePicker) {
        if (showQuickExportPicker && quickExportApp != null && !useCustomFilePicker) {
            quickExportDocumentLauncher.launch(resolveQuickExportName(quickExportApp))
        }
    }
    quickExportDialogState?.let { state ->
        ExportSavedApkFileNameDialog(
            initialName = state.fileName,
            onDismiss = { quickExportDialogState = null },
            onConfirm = { fileName ->
                val trimmedName = fileName.trim()
                if (trimmedName.isBlank()) return@ExportSavedApkFileNameDialog
                quickExportDialogState = null
                val target = state.directory.resolve(trimmedName)
                if (Files.exists(target)) {
                    pendingQuickExportConfirmation = PendingQuickExportConfirmation(
                        directory = state.directory,
                        fileName = trimmedName
                    )
                } else {
                    quickExportInProgress = true
                    quickActionViewModel?.exportSavedAppToPath(target) { success ->
                        quickExportInProgress = false
                        if (success) {
                            showQuickExportPicker = false
                        }
                    }
                }
            }
        )
    }
    pendingQuickExportConfirmation?.let { state ->
        ConfirmDialog(
            onDismiss = {
                pendingQuickExportConfirmation = null
                quickExportDialogState = QuickExportDialogState(state.directory, state.fileName)
            },
            onConfirm = {
                pendingQuickExportConfirmation = null
                quickExportInProgress = true
                quickActionViewModel?.exportSavedAppToPath(state.directory.resolve(state.fileName)) { success ->
                    quickExportInProgress = false
                    if (success) {
                        showQuickExportPicker = false
                    }
                }
            },
            title = stringResource(R.string.export_overwrite_title),
            description = stringResource(R.string.export_overwrite_description, state.fileName),
            icon = Icons.Outlined.WarningAmber
        )
    }
    if (quickExportInProgress) {
        AlertDialog(
            onDismissRequest = {},
            icon = {
                Icon(
                    Icons.Outlined.Save,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(
                    stringResource(R.string.export),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        stringResource(R.string.patcher_step_group_saving),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                    )
                }
            },
            confirmButton = {},
            dismissButton = {}
        )
    }

    quickActionViewModel?.installResult?.let { result ->
        val (titleRes, message) = when (result) {
            is InstallResult.Success -> R.string.install_app_success to result.message
            is InstallResult.Failure -> R.string.install_app_fail_title to result.message
            is InstallResult.UninstallError -> R.string.uninstall_app_fail_title to result.message
        }
        AlertDialog(
            onDismissRequest = quickActionViewModel::clearInstallResult,
            confirmButton = {
                TextButton(onClick = quickActionViewModel::clearInstallResult) {
                    Text(stringResource(R.string.ok))
                }
            },
            title = { CenteredDialogTitle(stringResource(titleRes)) },
            text = { Text(message) }
        )
    }

    quickActionViewModel?.signatureMismatchPackage?.let {
        AlertDialog(
            onDismissRequest = quickActionViewModel::dismissSignatureMismatchPrompt,
            confirmButton = {
                TextButton(onClick = quickActionViewModel::confirmSignatureMismatchInstall) {
                    Text(stringResource(R.string.installation_signature_mismatch_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = quickActionViewModel::dismissSignatureMismatchPrompt) {
                    Text(stringResource(R.string.cancel))
                }
            },
            title = { CenteredDialogTitle(stringResource(R.string.installation_signature_mismatch_dialog_title)) },
            text = {
                Text(
                    text = stringResource(R.string.installation_signature_mismatch_description),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        )
    }

    quickActionViewModel?.mountVersionMismatchMessage?.let { message ->
        AlertDialog(
            onDismissRequest = quickActionViewModel::dismissMountVersionMismatch,
            confirmButton = {
                TextButton(onClick = quickActionViewModel::dismissMountVersionMismatch) {
                    Text(stringResource(R.string.ok))
                }
            },
            title = { CenteredDialogTitle(stringResource(R.string.mount_version_mismatch_title)) },
            text = { Text(message) }
        )
    }

    quickActionViewModel?.mountWarning?.let { warning ->
        val (descriptionRes, titleRes) = when (warning.reason) {
            MountWarningReason.PRIMARY_IS_MOUNT_FOR_NON_MOUNT_APP ->
                when (warning.action) {
                    MountWarningAction.INSTALL -> R.string.installer_mount_warning_install
                    MountWarningAction.UPDATE -> R.string.installer_mount_warning_update
                    MountWarningAction.UNINSTALL -> R.string.installer_mount_warning_uninstall
                } to R.string.installer_mount_warning_title

            MountWarningReason.PRIMARY_NOT_MOUNT_FOR_MOUNT_APP ->
                when (warning.action) {
                    MountWarningAction.INSTALL -> R.string.installer_mount_mismatch_install
                    MountWarningAction.UPDATE -> R.string.installer_mount_mismatch_update
                    MountWarningAction.UNINSTALL -> R.string.installer_mount_mismatch_uninstall
                } to R.string.installer_mount_mismatch_title
        }

        AlertDialog(
            onDismissRequest = quickActionViewModel::clearMountWarning,
            confirmButton = {
                TextButton(onClick = quickActionViewModel::clearMountWarning) {
                    Text(stringResource(R.string.ok))
                }
            },
            title = { CenteredDialogTitle(stringResource(titleRes)) },
            text = {
                Text(
                    text = stringResource(descriptionRes),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        )
    }



    if (showQuickSavedUninstallDialog) {
        ConfirmDialog(
            onDismiss = { showQuickSavedUninstallDialog = false },
            onConfirm = {
                showQuickSavedUninstallDialog = false
                quickActionViewModel?.uninstallSavedInstallation()
            },
            title = stringResource(R.string.saved_app_uninstall_title),
            description = stringResource(R.string.saved_app_uninstall_description),
            icon = Icons.Outlined.Delete
        )
    }

    if (showQuickDeleteDialog) {
        val deleteEntry = quickDeleteIsEntry
        val deleteApp = quickDeleteApp
        val removesRootMount = deleteApp?.installType == InstallType.MOUNT ||
            (deleteApp == null && quickActionViewModel?.installedApp?.installType == InstallType.MOUNT)
        ConfirmDialog(
            onDismiss = {
                showQuickDeleteDialog = false
                quickDeleteApp = null
            },
            onConfirm = {
                showQuickDeleteDialog = false
                quickDeleteApp = null
                when {
                    deleteApp != null && (deleteEntry || deleteApp.installType != InstallType.SAVED) ->
                        installedAppsViewModel.deleteSavedEntry(deleteApp)
                    deleteApp != null -> installedAppsViewModel.removeSavedApp(deleteApp)
                    deleteEntry -> quickActionViewModel?.deleteSavedEntry()
                    else -> quickActionViewModel?.removeSavedApp()
                }
            },
            title = stringResource(
                when {
                    removesRootMount -> R.string.delete_root_mount_saved_app_title
                    deleteEntry -> R.string.delete_saved_entry_title
                    else -> R.string.delete_saved_app_title
                }
            ),
            description = stringResource(
                when {
                    removesRootMount -> R.string.delete_root_mount_saved_app_description
                    deleteEntry -> R.string.delete_saved_entry_description
                    else -> R.string.delete_saved_app_description
                }
            ),
            icon = Icons.Outlined.Delete
        )
    }

    pendingRepatchSourceWarning?.let { state ->
        ConfirmDialog(
            onDismiss = { pendingRepatchSourceWarning = null },
            onConfirm = {
                val action = state.second
                pendingRepatchSourceWarning = null
                action()
            },
            title = stringResource(R.string.repatch_source_unavailable_title),
            description = if (state.first.isNullOrBlank()) {
                stringResource(R.string.repatch_source_not_remembered_description)
            } else {
                stringResource(R.string.repatch_source_missing_description)
            },
            icon = Icons.Outlined.WarningAmber,
            confirmLabelRes = R.string.continue_
        )
    }

    if (showQuickMixedBundleDialog) {
        AlertDialog(
            onDismissRequest = { showQuickMixedBundleDialog = false },
            confirmButton = {
                TextButton(onClick = { showQuickMixedBundleDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            },
            title = { CenteredDialogTitle(stringResource(R.string.mixed_patch_bundles_title)) },
            text = { Text(stringResource(R.string.mixed_patch_bundles_description)) }
        )
    }

    if (showQuickMixedRevancedPatcherDialog) {
        AlertDialog(
            onDismissRequest = { showQuickMixedRevancedPatcherDialog = false },
            confirmButton = {
                TextButton(onClick = { showQuickMixedRevancedPatcherDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            },
            title = { CenteredDialogTitle(stringResource(R.string.mixed_revanced_patcher_versions_title)) },
            text = { Text(stringResource(R.string.mixed_revanced_patcher_versions_description)) }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.blur(
                if (splitMergeDownloadLoading || lsposedViewModel?.busyMessage != null) {
                    16.dp
                } else {
                    0.dp
                }
            ),
            topBar = {
                when {
                appsSelectionActive &&
                    uiPage == DashboardPage.DASHBOARD &&
                    !suppressAppsSelectionTopBar -> {
                    BundleTopBar(
                        title = pluralStringResource(
                            R.plurals.selected_apps_count,
                            selectedAppCount,
                            selectedAppCount
                        ),
                        onBackClick = installedAppsViewModel::clearSelection,
                        backIcon = {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.back)
                            )
                        },
                        actions = {
                            val selectedPackages = installedAppsViewModel.selectedApps
                            val batchPackages = selectedBatchTargetIdentifiers(
                                records = installedApps,
                                selectedEntryKeys = selectedPackages
                            )
                            if (batchPackages.size > 1) {
                                IconButton(
                                    onClick = {
                                        installedAppsViewModel.clearSelection()
                                        onBatchPatch(batchPackages)
                                    },
                                    enabled = appInputEnabled
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Outlined.PlaylistPlay,
                                        stringResource(R.string.batch_patch_selected)
                                    )
                                }
                            }
                            IconButton(
                                onClick = { requestSavedAppsExportPicker() }
                            ) {
                                Icon(
                                    Icons.Outlined.Save,
                                    stringResource(R.string.export)
                                )
                            }
                            IconButton(
                                onClick = { showDeleteSavedAppsDialog = true }
                            ) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    stringResource(R.string.delete)
                                )
                            }
                        }
                    )
                }

                bundlesSelectable &&
                    uiPage == DashboardPage.BUNDLES &&
                    !suppressBundlesSelectionTopBar -> {
                    BundleTopBar(
                        title = pluralStringResource(
                            R.plurals.patches_selected_quantity,
                            selectedSourceCount,
                            selectedSourceCount
                        ),
                        onBackClick = vm::cancelSourceSelection,
                        backIcon = {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.back)
                            )
                        },
                        actions = {
                            IconButton(
                                onClick = {
                                    showDeleteConfirmationDialog = true
                                }
                            ) {
                                Icon(
                                    Icons.Outlined.DeleteOutline,
                                    stringResource(R.string.delete)
                                )
                            }
                            IconButton(
                                onClick = {
                                    vm.disableSources()
                                    vm.cancelSourceSelection()
                                }
                              ) {
                                  Icon(
                                      if (selectedSourcesHasEnabled) Icons.Outlined.Block else Icons.Outlined.CheckCircle,
                                      stringResource(if (selectedSourcesHasEnabled) R.string.disable else R.string.enable)
                                  )
                              }
                            IconButton(
                                onClick = vm::updateSources
                            ) {
                                Icon(
                                    Icons.Outlined.Refresh,
                                    stringResource(R.string.refresh)
                                )
                            }
                        }
                    )
                }

                profilesSelectable &&
                    uiPage == DashboardPage.PROFILES &&
                    !suppressProfilesSelectionTopBar -> {
                    BundleTopBar(
                        title = pluralStringResource(
                            R.plurals.patch_profiles_selected_quantity,
                            selectedProfileCount,
                            selectedProfileCount
                        ),
                        onBackClick = { patchProfilesViewModel.handleEvent(PatchProfilesViewModel.Event.CANCEL) },
                        backIcon = {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.back)
                            )
                        },
                        actions = {
                            IconButton(
                                onClick = { showDeleteProfilesConfirmationDialog = true }
                            ) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    stringResource(R.string.delete)
                                )
                            }
                        }
                    )
                }

                else -> {
                    val managerUpdateViewed =
                        !vm.updatedManagerVersion.isNullOrEmpty() &&
                            vm.updatedManagerVersion == viewedManagerUpdateVersion
                    val appIcon = remember {
                        AppCompatResources.getDrawable(androidContext, R.mipmap.ic_launcher)
                    }
                    val titleScrollState = rememberScrollState()
                    AppTopBar(
                        title = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AppIcon(
                                    packageInfo = null,
                                    contentDescription = stringResource(R.string.app_name),
                                    modifier = Modifier.size(28.dp),
                                    iconOverride = appIcon
                                )
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .horizontalScroll(titleScrollState)
                                ) {
                                    Text(
                                        text = stringResource(R.string.main_top_title),
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        },
                        actions = {
                            if (!vm.updatedManagerVersion.isNullOrEmpty()) {
                                IconButton(
                                    onClick = onUpdateClick,
                                ) {
                                    if (managerUpdateViewed) {
                                        Icon(Icons.Outlined.Update, stringResource(R.string.update))
                                    } else {
                                        BadgedBox(
                                            badge = {
                                                Badge(modifier = Modifier.size(6.dp))
                                            }
                                        ) {
                                            Icon(
                                                Icons.Outlined.Update,
                                                stringResource(R.string.update)
                                            )
                                        }
                                    }
                                }
                            }
                            if (announcementSystemEnabled) {
                                IconButton(onClick = onAnnouncementsClick) {
                                    BadgedBox(
                                        badge = {
                                            if (vm.unreadAnnouncement != null) {
                                                Badge(modifier = Modifier.size(6.dp))
                                            }
                                        }
                                    ) {
                                        Icon(
                                            Icons.Outlined.Notifications,
                                            stringResource(R.string.announcements)
                                        )
                                    }
                                }
                            }
                            val isAppsTab = uiPage == DashboardPage.DASHBOARD
                            val isBundlesTab = uiPage == DashboardPage.BUNDLES
                            val isProfilesTab = uiPage == DashboardPage.PROFILES && showPatchProfilesTab
                            val searchActive = when {
                                isAppsTab -> appsSearchActive
                                isBundlesTab -> bundlesSearchActive
                                isProfilesTab -> profilesSearchActive
                                else -> false
                            }
                            if (isAppsTab || isBundlesTab || isProfilesTab) {
                                IconButton(
                                    onClick = {
                                        when {
                                            isAppsTab -> {
                                                appsSearchActive = !appsSearchActive
                                                if (!appsSearchActive) appsSearchQuery = ""
                                            }
                                            isBundlesTab -> {
                                                bundlesSearchActive = !bundlesSearchActive
                                                if (!bundlesSearchActive) bundlesSearchQuery = ""
                                            }
                                            isProfilesTab -> {
                                                profilesSearchActive = !profilesSearchActive
                                                if (!profilesSearchActive) profilesSearchQuery = ""
                                            }
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = if (searchActive) Icons.Outlined.Close else Icons.Outlined.Search,
                                        contentDescription = stringResource(if (searchActive) R.string.close else R.string.search)
                                    )
                                }
                            }
                            if (uiPage == DashboardPage.BUNDLES && !bundlesSelectable) {
                                IconButton(
                                    onClick = {
                                        installedAppsViewModel.clearSelection()
                                        patchProfilesViewModel.handleEvent(PatchProfilesViewModel.Event.CANCEL)
                                        if (bundleSources.isEmpty()) {
                                            androidContext.toast(
                                                androidContext.getString(R.string.bundle_reorder_empty_toast)
                                            )
                                            return@IconButton
                                        }
                                        showBundleOrderDialog = true
                                    }
                                ) {
                                    Icon(Icons.Outlined.Sort, stringResource(R.string.bundle_reorder))
                                }
                            }
                            if (uiPage == DashboardPage.DASHBOARD && !appsSelectionActive) {
                                IconButton(
                                    onClick = {
                                        installedAppsViewModel.clearSelection()
                                        if (installedApps.isEmpty()) {
                                            androidContext.toast(
                                                androidContext.getString(R.string.apps_reorder_empty_toast)
                                            )
                                            return@IconButton
                                        }
                                        showAppsOrderDialog = true
                                    }
                                ) {
                                    Icon(Icons.Outlined.Sort, stringResource(R.string.apps_reorder))
                                }
                            }
                            if (uiPage == DashboardPage.PROFILES && showPatchProfilesTab && !profilesSelectable) {
                                IconButton(
                                    onClick = {
                                        patchProfilesViewModel.handleEvent(PatchProfilesViewModel.Event.CANCEL)
                                        if (profiles.isEmpty()) {
                                            androidContext.toast(
                                                androidContext.getString(R.string.patch_profiles_reorder_empty_toast)
                                            )
                                            return@IconButton
                                        }
                                        showProfilesOrderDialog = true
                                    }
                                ) {
                                    Icon(Icons.Outlined.Sort, stringResource(R.string.patch_profiles_reorder))
                                }
                            }
                            IconButton(onClick = onSettingsClick) {
                                Icon(Icons.Outlined.Settings, stringResource(R.string.settings))
                            }
                        },
                        applyContainerColor = true
                    )
                }
            }
        },
        floatingActionButton = {
            when (uiPage) {
                DashboardPage.BUNDLES -> {
                    val storedOrder by prefs.dashboardBundlesActionOrder.getAsState()
                    val hiddenButtons by prefs.dashboardHiddenActions.getAsState()
                    val visibleButtons = app.universal.patch.manager.ui.model.dashboardActionOrder(
                        storedOrder, listOf("discovery", "add"), hiddenButtons, "bundles"
                    )
                    if (visibleButtons.isNotEmpty()) {
                        val enterExitSpec = tween<IntOffset>(durationMillis = 220, easing = FastOutSlowInEasing)
                        val sizeSpec = tween<IntSize>(durationMillis = 220, easing = FastOutSlowInEasing)
                        Row(
                            modifier = Modifier
                                .height(56.dp)
                                .offset(x = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AnimatedVisibility(
                                visible = !bundlesFabCollapsed,
                                enter = fadeIn(animationSpec = tween(180)) +
                                    expandHorizontally(expandFrom = Alignment.End, animationSpec = sizeSpec) +
                                    slideInHorizontally(
                                        initialOffsetX = { it / 2 },
                                        animationSpec = enterExitSpec
                                    ),
                                exit = fadeOut(animationSpec = tween(180)) +
                                    shrinkHorizontally(shrinkTowards = Alignment.End, animationSpec = sizeSpec) +
                                    slideOutHorizontally(
                                        targetOffsetX = { it / 2 },
                                        animationSpec = enterExitSpec
                                    )
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    visibleButtons.forEach { button ->
                                        when (button) {
                                            "discovery" -> HapticFloatingActionButton(
                                                onClick = onBundleDiscoveryClick
                                            ) {
                                                Icon(
                                                    Icons.Outlined.Public,
                                                    stringResource(R.string.patch_bundle_discovery_title)
                                                )
                                            }
                                            "add" -> HapticFloatingActionButton(
                                                onClick = {
                                                    vm.cancelSourceSelection()
                                                    installedAppsViewModel.clearSelection()
                                                    patchProfilesViewModel.handleEvent(PatchProfilesViewModel.Event.CANCEL)
                                                    showAddBundleDialog = true
                                                }
                                            ) { Icon(Icons.Default.Add, stringResource(R.string.add)) }
                                        }
                                    }
                                }
                            }
                            BundleFabHandle(
                                collapsed = bundlesFabCollapsed,
                                onToggle = {
                                    composableScope.launch {
                                        prefs.dashboardBundlesFabCollapsed.update(!bundlesFabCollapsed)
                                    }
                                }
                            )
                        }
                    }
                }

                DashboardPage.DASHBOARD -> {
                    val storedOrder by prefs.dashboardAppsActionOrder.getAsState()
                    val hiddenButtons by prefs.dashboardHiddenActions.getAsState()
                    val visibleButtons = app.universal.patch.manager.ui.model.dashboardActionOrder(
                        storedOrder, listOf("storage", "add", "batch"), hiddenButtons, "apps"
                    )
                    if (visibleButtons.isNotEmpty()) {
                        val enterExitSpec = tween<IntOffset>(durationMillis = 220, easing = FastOutSlowInEasing)
                        val sizeSpec = tween<IntSize>(durationMillis = 220, easing = FastOutSlowInEasing)
                        Row(
                            modifier = Modifier
                                .height(56.dp)
                                .offset(x = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AnimatedVisibility(
                                visible = !appsFabCollapsed,
                                enter = fadeIn(animationSpec = tween(180)) +
                                    expandHorizontally(expandFrom = Alignment.End, animationSpec = sizeSpec) +
                                    slideInHorizontally(
                                        initialOffsetX = { it / 2 },
                                        animationSpec = enterExitSpec
                                    ),
                                exit = fadeOut(animationSpec = tween(180)) +
                                    shrinkHorizontally(shrinkTowards = Alignment.End, animationSpec = sizeSpec) +
                                    slideOutHorizontally(
                                        targetOffsetX = { it / 2 },
                                        animationSpec = enterExitSpec
                                    )
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    visibleButtons.forEach { button ->
                                        when (button) {
                                            "storage" -> HapticFloatingActionButton(
                                                onClick = { attemptAppInput(openStoragePicker) },
                                                enabled = appInputEnabled,
                                                containerColor = if (appInputEnabled) {
                                                    FloatingActionButtonDefaults.containerColor
                                                } else {
                                                    disabledAppInputFabColor
                                                }
                                            ) {
                                                Icon(Icons.Default.Storage, stringResource(R.string.select_from_storage))
                                            }
                                            "add" -> HapticFloatingActionButton(
                                                onClick = { attemptAppInput(onAppSelectorClick) },
                                                enabled = appInputEnabled,
                                                containerColor = if (appInputEnabled) {
                                                    FloatingActionButtonDefaults.containerColor
                                                } else {
                                                    disabledAppInputFabColor
                                                }
                                            ) {
                                                Icon(Icons.Default.Add, stringResource(R.string.add))
                                            }
                                            "batch" -> HapticFloatingActionButton(
                                                onClick = { attemptAppInput(onBatchQueueClick) },
                                                enabled = appInputEnabled,
                                                containerColor = if (appInputEnabled) {
                                                    FloatingActionButtonDefaults.containerColor
                                                } else {
                                                    disabledAppInputFabColor
                                                }
                                            ) {
                                                Icon(
                                                    Icons.AutoMirrored.Outlined.PlaylistPlay,
                                                    stringResource(R.string.batch_queue_create)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            BundleFabHandle(
                                collapsed = appsFabCollapsed,
                                onToggle = {
                                    composableScope.launch {
                                        prefs.dashboardAppsFabCollapsed.update(!appsFabCollapsed)
                                    }
                                }
                            )
                        }
                    }
                }

                DashboardPage.LSPOSED -> {
                    val enterExitSpec = tween<IntOffset>(
                        durationMillis = 220,
                        easing = FastOutSlowInEasing,
                    )
                    val sizeSpec = tween<IntSize>(
                        durationMillis = 220,
                        easing = FastOutSlowInEasing,
                    )
                    Row(
                        modifier = Modifier
                            .height(56.dp)
                            .offset(x = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AnimatedVisibility(
                            visible = !lsposedFabCollapsed,
                            enter = fadeIn(animationSpec = tween(180)) +
                                expandHorizontally(
                                    expandFrom = Alignment.End,
                                    animationSpec = sizeSpec,
                                ) +
                                slideInHorizontally(
                                    initialOffsetX = { it / 2 },
                                    animationSpec = enterExitSpec,
                                ),
                            exit = fadeOut(animationSpec = tween(180)) +
                                shrinkHorizontally(
                                    shrinkTowards = Alignment.End,
                                    animationSpec = sizeSpec,
                                ) +
                                slideOutHorizontally(
                                    targetOffsetX = { it / 2 },
                                    animationSpec = enterExitSpec,
                                ),
                        ) {
                            HapticFloatingActionButton(
                                onClick = { showAddLsposedDialog = true },
                                enabled = lsposedRootAvailable,
                                containerColor = if (lsposedRootAvailable) {
                                    FloatingActionButtonDefaults.containerColor
                                } else {
                                    disabledAppInputFabColor
                                },
                            ) {
                                Icon(Icons.Default.Add, stringResource(R.string.add))
                            }
                        }
                        BundleFabHandle(
                            collapsed = lsposedFabCollapsed,
                            onToggle = {
                                composableScope.launch {
                                    prefs.dashboardLsposedFabCollapsed.update(
                                        !lsposedFabCollapsed
                                    )
                                }
                            },
                        )
                    }
                }

                else -> Unit
            }
        }
    ) { paddingValues ->
        Box(Modifier.padding(paddingValues)) {
            Column {
            TabRow(
                selectedTabIndex = swipeSyncedTabIndex,
                containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.0.dp),
                indicator = {},
                divider = {}
            ) {
                visibleTabs.forEach { page ->
                    val selected = page == swipeSyncedPage
                    val updateCount = when (page) {
                        DashboardPage.DASHBOARD -> appsUpdateCount.takeIf {
                            showAppsTabUpdateCount && it > 0
                        }
                        DashboardPage.BUNDLES -> manualBundleUpdates.size.takeIf {
                            showBundlesTabUpdateCount && it > 0
                        }
                        else -> null
                    }
                    val tabScale by animateFloatAsState(
                        targetValue = if (selected) 1.02f else 1f,
                        animationSpec = spring(
                            stiffness = Spring.StiffnessMediumLow,
                            dampingRatio = Spring.DampingRatioMediumBouncy
                        ),
                        label = "dashboardTabScale"
                    )
                    val tabOffsetY by animateDpAsState(
                        targetValue = if (selected) (-2).dp else 0.dp,
                        animationSpec = spring(
                            stiffness = Spring.StiffnessMediumLow,
                            dampingRatio = Spring.DampingRatioNoBouncy
                        ),
                        label = "dashboardTabOffset"
                    )
                    HapticTab(
                        selected = selected,
                        onClick = {
                            composableScope.launch {
                                if (page != uiPage) {
                                    clearSelectionForPage(uiPage)
                                }
                                scrollToVisiblePage(page, animated = true)
                            }
                        },
                        modifier = Modifier
                            .graphicsLayer {
                                scaleX = tabScale
                                scaleY = tabScale
                            }
                            .offset(y = tabOffsetY),
                        text = if (hideMainTabLabels) null else {
                            {
                                DashboardTabLabel(
                                    text = stringResource(page.titleResId),
                                    selected = selected,
                                    updateCount = updateCount
                                )
                            }
                        },
                        icon = {
                            if (hideMainTabLabels && updateCount != null) {
                                BadgedBox(
                                    badge = { DashboardTabUpdateBadge(updateCount) }
                                ) {
                                    Icon(page.icon, null)
                                }
                            } else {
                                Icon(page.icon, null)
                            }
                        },
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Notifications(
                if (!Aapt.supportsDevice()) {
                    {
                        NotificationCard(
                            isWarning = true,
                            icon = Icons.Outlined.WarningAmber,
                            text = stringResource(R.string.unsupported_architecture_warning),
                            onDismiss = null
                        )
                    }
                } else null,
                if (vm.showBatteryOptimizationsWarning) {
                    {
                        val batteryOptimizationsLauncher =
                            rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                                vm.updateBatteryOptimizationsWarning()
                            }
                        NotificationCard(
                            isWarning = true,
                            icon = Icons.Default.BatteryAlert,
                            text = stringResource(R.string.battery_optimization_notification),
                            onClick = {
                                batteryOptimizationsLauncher.launch(
                                    Intent(
                                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.fromParts("package", androidContext.packageName, null)
                                    )
                                )
                            }
                        )
                    }
                } else null,
                if (newPluginNotifications.isNotEmpty()) {
                    {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            newPluginNotifications.forEach { notification ->
                                when (notification) {
                                    NewPluginNotification.DOWNLOADER -> NotificationCard(
                                        text = stringResource(R.string.new_downloader_plugins_notification),
                                        icon = Icons.Outlined.Download,
                                        modifier = Modifier.clickable(onClick = onDownloaderPluginClick),
                                        actions = {
                                            TextButton(onClick = vm::ignoreNewDownloaderPlugins) {
                                                Text(stringResource(R.string.dismiss))
                                            }
                                        }
                                    )

                                    NewPluginNotification.PATCHER_RUNTIME -> NotificationCard(
                                        text = stringResource(R.string.new_patcher_runtime_plugins_notification),
                                        icon = Icons.Outlined.Build,
                                        modifier = Modifier.clickable(onClick = onPatcherRuntimePluginClick),
                                        actions = {
                                            TextButton(onClick = vm::ignoreNewPatcherRuntimePlugins) {
                                                Text(stringResource(R.string.dismiss))
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                } else null,
                if (announcementSystemEnabled) vm.unreadAnnouncement?.let { announcement ->
                    {
                        NotificationCard(
                            text = stringResource(R.string.new_announcement, announcement.title),
                            icon = Icons.Outlined.Notifications,
                            isWarning = announcement.level > 0,
                            actions = {
                                TextButton(onClick = vm::markUnreadAnnouncementRead) {
                                    Text(stringResource(R.string.dismiss))
                                }
                                TextButton(
                                    onClick = {
                                        vm.markUnreadAnnouncementRead()
                                        onAnnouncementClick(Announcement.Payload.from(announcement))
                                    }
                                ) {
                                    Text(stringResource(R.string.view_announcement))
                                }
                            }
                        )
                    }
                } else null
            )

            val isAppsTab = uiPage == DashboardPage.DASHBOARD
            val isBundlesTab = uiPage == DashboardPage.BUNDLES
            val isProfilesTab = uiPage == DashboardPage.PROFILES && showPatchProfilesTab
            val searchActive = when {
                isAppsTab -> appsSearchActive
                isBundlesTab -> bundlesSearchActive
                isProfilesTab -> profilesSearchActive
                else -> false
            }
            AnimatedVisibility(
                visible = searchActive,
                enter = fadeIn(animationSpec = spring(stiffness = 400f)) +
                    expandVertically(
                        expandFrom = Alignment.Top,
                        animationSpec = spring(stiffness = 400f)
                    ),
                exit = fadeOut(animationSpec = spring(stiffness = 400f)) +
                    shrinkVertically(
                        shrinkTowards = Alignment.Top,
                        animationSpec = spring(stiffness = 400f)
                    )
            ) {
                val (query, onQueryChange, placeholderRes) = when {
                    isAppsTab -> Triple(
                        appsSearchQuery,
                        { value: String -> appsSearchQuery = value },
                        R.string.apps_search_hint
                    )
                    isBundlesTab -> Triple(
                        bundlesSearchQuery,
                        { value: String -> bundlesSearchQuery = value },
                        R.string.bundles_search_hint
                    )
                    else -> Triple(
                        profilesSearchQuery,
                        { value: String -> profilesSearchQuery = value },
                        R.string.profiles_search_hint
                    )
                }
                DashboardSearchField(
                    query = query,
                    onQueryChange = onQueryChange,
                    onClear = {
                        when {
                            isAppsTab -> appsSearchQuery = ""
                            isBundlesTab -> bundlesSearchQuery = ""
                            else -> profilesSearchQuery = ""
                        }
                    },
                    placeholderRes = placeholderRes,
                    modifier = Modifier.padding(
                        start = dashboardSidePadding,
                        end = dashboardSidePadding,
                        top = 12.dp,
                        bottom = 0.dp
                    )
                )
            }

            BundleProgressBanner(
                modifier = Modifier.padding(top = 8.dp)
            )

            val pagerFlingBehavior = if (preventAccidentalTouching) {
                PagerDefaults.flingBehavior(state = pagerState)
            } else {
                PagerDefaults.flingBehavior(
                    state = pagerState,
                    pagerSnapDistance = PagerSnapDistance.atMost(1),
                    snapPositionalThreshold = 0.2f
                )
            }

            HorizontalPager(
                state = pagerState,
                flingBehavior = pagerFlingBehavior,
                userScrollEnabled = !disableMainTabSwipe,
                modifier = Modifier.fillMaxSize(),
                pageContent = { index ->
                    when (visibleTabs[index]) {
                        DashboardPage.DASHBOARD -> {
                            InstalledAppsScreen(
                                onAppClick = {
                                    installedAppsViewModel.clearSelection()
                                    onAppClick(it.currentPackageName, null)
                                },
                                onAppAction = { app, action ->
                                    installedAppsViewModel.clearSelection()
                                    if (action == InstalledAppAction.OPEN) {
                                        val launchPackage = if (app.installType == InstallType.SAVED) {
                                            installedAppsViewModel.packageInfoMap[app.currentPackageName]
                                                ?.packageName
                                                ?: app.originalPackageName.takeIf { it.isNotBlank() }
                                                ?: savedAppBasePackage(app.currentPackageName)
                                        } else {
                                            app.currentPackageName
                                        }
                                        val intent = androidContext.packageManager
                                            .getLaunchIntentForPackage(launchPackage)
                                        if (intent == null) {
                                            androidContext.toast(
                                                androidContext.getString(R.string.saved_app_launch_unavailable)
                                            )
                                        } else {
                                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            androidContext.startActivity(intent)
                                        }
                                        return@InstalledAppsScreen
                                    }
                                    if (action == InstalledAppAction.DELETE) {
                                        quickDeleteApp = app
                                        quickDeleteIsEntry = app.installType != InstallType.SAVED &&
                                            installedAppsViewModel.savedCopyMap[app.currentPackageName] == true
                                    }
                                    if (action == InstalledAppAction.REPATCH) {
                                        composableScope.launch {
                                            val selection = installedAppsViewModel.getRepatchSelection(app)
                                            if (selection.isNullOrEmpty()) {
                                                androidContext.toast(
                                                    androidContext.getString(R.string.no_patches_selected)
                                                )
                                                return@launch
                                            }
                                            if (patchBundleRepository.selectionHasMixedBundleTypes(selection)) {
                                                showQuickMixedBundleDialog = true
                                                return@launch
                                            }
                                            if (patchBundleRepository.selectionHasMixedRevancedPatcherVersions(selection)) {
                                                showQuickMixedRevancedPatcherDialog = true
                                                return@launch
                                            }
                                            val payload = app.selectionPayload
                                            val persistConfiguration = app.installType != InstallType.SAVED
                                            runRepatchOrWarn(app) {
                                                mainVm.selectApp(
                                                    packageName = app.originalPackageName,
                                                    patches = selection,
                                                    selectionPayload = payload,
                                                    persistConfiguration = persistConfiguration,
                                                    returnToDashboard = true,
                                                    sourceEntryKey = app.currentPackageName
                                                )
                                            }
                                        }
                                        return@InstalledAppsScreen
                                    }
                                    quickActionPackage = app.currentPackageName
                                    pendingQuickAction = null
                                    pendingQuickAction = action
                                },
                                searchQuery = appsSearchQuery,
                                showOrderDialog = showAppsOrderDialog,
                                onDismissOrderDialog = { showAppsOrderDialog = false },
                                viewModel = installedAppsViewModel
                            )
                        }

                        DashboardPage.BUNDLES -> {
                            BundleListScreen(
                                viewModel = bundleListViewModel,
                                eventsFlow = vm.bundleListEventsFlow,
                                setSelectedSourceCount = { selectedSourceCount = it },
                                setSelectedSourceHasEnabled = { selectedSourcesHasEnabled = it },
                                searchQuery = bundlesSearchQuery,
                                showOrderDialog = showBundleOrderDialog,
                                onDismissOrderDialog = { showBundleOrderDialog = false },
                                onScrollStateChange = {},
                                highlightBundleUid = highlightBundleUid,
                                onHighlightConsumed = { highlightBundleUid = null }
                            )
                        }

                        DashboardPage.PROFILES -> {
                            PatchProfilesScreen(
                                onProfileClick = onProfileLaunch,
                                modifier = Modifier.fillMaxSize(),
                                searchQuery = profilesSearchQuery,
                                showOrderDialog = showProfilesOrderDialog,
                                onDismissOrderDialog = { showProfilesOrderDialog = false },
                                viewModel = patchProfilesViewModel
                            )
                        }

                        DashboardPage.LSPOSED -> lsposedViewModel?.let { viewModel ->
                            LsposedTabScreen(
                                showAddDialog = showAddLsposedDialog,
                                onAddDialogDismiss = { showAddLsposedDialog = false },
                                viewModel = viewModel,
                            )
                        }

                        DashboardPage.TOOLS -> {
                            ToolsTabScreen(
                                onOpenMergeScreen = ::launchSplitMerge,
                                onOpenSplitInstallerScreen = onOpenSplitInstallerClick,
                                onOpenYoutubeAssetsScreen = onCreateYoutubeAssetsClick,
                                onOpenApkSignerScreen = onOpenApkSignerClick,
                                onOpenKeystoreCreatorScreen = onOpenKeystoreCreatorClick,
                                onOpenKeystoreConverterScreen = onOpenKeystoreConverterClick
                            )
                        }
                    }
                }
            )
            }
        }

        if (storageVm.storageSelectionInProgress) {
            TransparentLoadingDialog()
        }
    }
}
}

private data class QuickExportDialogState(
    val directory: Path,
    val fileName: String
)

private enum class SplitPermissionRequest {
    INPUT
}

@Composable
private fun ToolsTabScreen(
    onOpenMergeScreen: () -> Unit,
    onOpenSplitInstallerScreen: () -> Unit,
    onOpenYoutubeAssetsScreen: () -> Unit,
    onOpenApkSignerScreen: () -> Unit,
    onOpenKeystoreCreatorScreen: () -> Unit,
    onOpenKeystoreConverterScreen: () -> Unit
) {
    app.universal.patch.manager.ui.component.LazyColumnWithScrollbar(
        modifier = Modifier.fillMaxSize(),
        thumbLength = app.universal.patch.manager.ui.component.DASHBOARD_SCROLLBAR_THUMB_LENGTH
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    tonalElevation = 2.dp,
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenYoutubeAssetsScreen)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(52.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Image,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier
                                    .padding(11.dp)
                                    .size(30.dp)
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.tools_youtube_assets_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.tools_youtube_assets_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    tonalElevation = 2.dp,
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenMergeScreen)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(52.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.AccountTree,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier
                                    .padding(11.dp)
                                    .size(30.dp)
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.tools_merge_split_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.tools_merge_split_idle_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    tonalElevation = 2.dp,
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenSplitInstallerScreen)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(52.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Download,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier
                                    .padding(11.dp)
                                    .size(30.dp)
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.tools_split_installer_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.tools_split_installer_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    tonalElevation = 2.dp,
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenApkSignerScreen)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(52.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.VerifiedUser,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier
                                    .padding(11.dp)
                                    .size(30.dp)
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.tools_apk_signature_tools_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.tools_apk_signature_tools_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    tonalElevation = 2.dp,
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenKeystoreCreatorScreen)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(52.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Key,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier
                                    .padding(11.dp)
                                    .size(30.dp)
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.tools_keystore_creator_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.tools_keystore_creator_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    tonalElevation = 2.dp,
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenKeystoreConverterScreen)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(52.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.SwapVert,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier
                                    .padding(11.dp)
                                    .size(30.dp)
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.tools_keystore_converter_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.tools_keystore_converter_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class PendingQuickExportConfirmation(
    val directory: Path,
    val fileName: String
)

private data class QuickRootDiagnosticsExportDialogState(
    val directory: Path,
    val fileName: String
)

private data class PendingQuickRootDiagnosticsExportConfirmation(
    val directory: Path,
    val fileName: String
)

@Composable
private fun MergeSplitSourceDialog(
    hasDownloaderPlugins: Boolean,
    onDismissRequest: () -> Unit,
    onSelectStorage: () -> Unit,
    onSelectInstalled: () -> Unit,
    onSelectDownloader: () -> Unit
) {
    AlertDialogExtended(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel))
            }
        },
        title = { CenteredDialogTitle(stringResource(R.string.tools_merge_split_source_title)) },
        textHorizontalPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
        text = {
            Column {
                MergeSplitSourceOption(
                    title = stringResource(R.string.tools_merge_split_source_storage_title),
                    description = stringResource(R.string.tools_merge_split_source_storage_description),
                    enabled = true,
                    onClick = onSelectStorage
                )
                MergeSplitSourceOption(
                    title = stringResource(R.string.tools_merge_split_source_installed_title),
                    description = stringResource(R.string.tools_merge_split_source_installed_description),
                    enabled = true,
                    onClick = onSelectInstalled
                )
                MergeSplitSourceOption(
                    title = stringResource(R.string.tools_merge_split_source_plugin_title),
                    description = when {
                        hasDownloaderPlugins ->
                            stringResource(R.string.tools_merge_split_source_plugin_description)
                        else ->
                            stringResource(R.string.tools_merge_split_source_plugin_unavailable_plugins)
                    },
                    enabled = hasDownloaderPlugins,
                    onClick = onSelectDownloader
                )
            }
        }
    )
}

@Composable
private fun MergeSplitSourceOption(
    title: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MergeSplitInstalledAppsDialog(
    apps: List<AppInfo>,
    pm: PM,
    onDismissRequest: () -> Unit,
    onSelectApp: (String) -> Unit
) {
    val prefs: PreferencesManager = koinInject()
    val coroutineScope = rememberCoroutineScope()
    var filterText by rememberSaveable { mutableStateOf("") }
    val showUserApps by prefs.splitMergeInstalledFilterUserApps.getAsState()
    val showSystemApps by prefs.splitMergeInstalledFilterSystemApps.getAsState()
    val showSplitApks by prefs.splitMergeInstalledFilterSplitApks.getAsState()
    val showSingleApks by prefs.splitMergeInstalledFilterSingleApks.getAsState()
    val filterScrollState = rememberScrollState()
    val chipColors = FilterChipDefaults.filterChipColors(
        containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp),
        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
    )
    val filteredApps = remember(
        apps,
        filterText,
        showUserApps,
        showSystemApps,
        showSplitApks,
        showSingleApks
    ) {
        apps.filter { app ->
            val packageInfo = app.packageInfo ?: return@filter false
            val isSystemApp = pm.isSystemApp(packageInfo)
            val hasSplitApks = pm.hasSplitApks(packageInfo)
            val typeMatches = when {
                showUserApps && !showSystemApps -> !isSystemApp
                !showUserApps && showSystemApps -> isSystemApp
                else -> true
            }
            val splitTypeMatches = when {
                showSplitApks && !showSingleApks -> hasSplitApks
                !showSplitApks && showSingleApks -> !hasSplitApks
                else -> true
            }
            val label = pm.run { packageInfo.label() }
            typeMatches && splitTypeMatches && (
                filterText.isBlank() ||
                    label.contains(filterText, ignoreCase = true) ||
                    app.packageName.contains(filterText, ignoreCase = true)
            )
        }
    }

    FullscreenDialog(onDismissRequest = onDismissRequest) {
        Scaffold(
            topBar = {
                AppTopBar(
                    title = stringResource(R.string.tools_merge_split_source_installed_title),
                    onBackClick = onDismissRequest
                )
            }
        ) { paddingValues ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                persistentControls(key = "merge-installed-controls") {
                    TextField(
                        value = filterText,
                        onValueChange = { filterText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.search_apps)) },
                        singleLine = true
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(filterScrollState),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CheckedFilterChip(
                            selected = showUserApps,
                            onClick = {
                                coroutineScope.launch {
                                    prefs.splitMergeInstalledFilterUserApps.update(!showUserApps)
                                }
                            },
                            colors = chipColors,
                            label = { Text(stringResource(R.string.merge_split_installed_filter_user_apps)) }
                        )
                        CheckedFilterChip(
                            selected = showSystemApps,
                            onClick = {
                                coroutineScope.launch {
                                    prefs.splitMergeInstalledFilterSystemApps.update(!showSystemApps)
                                }
                            },
                            colors = chipColors,
                            label = { Text(stringResource(R.string.merge_split_installed_filter_system_apps)) }
                        )
                        CheckedFilterChip(
                            selected = showSplitApks,
                            onClick = {
                                coroutineScope.launch {
                                    prefs.splitMergeInstalledFilterSplitApks.update(!showSplitApks)
                                }
                            },
                            colors = chipColors,
                            label = { Text(stringResource(R.string.merge_split_installed_filter_split_apks)) }
                        )
                        CheckedFilterChip(
                            selected = showSingleApks,
                            onClick = {
                                coroutineScope.launch {
                                    prefs.splitMergeInstalledFilterSingleApks.update(!showSingleApks)
                                }
                            },
                            colors = chipColors,
                            label = { Text(stringResource(R.string.merge_split_installed_filter_single_apks)) }
                        )
                    }
                }
                item(key = "merge-installed-description") {
                    Text(
                        text = stringResource(R.string.merge_split_installed_picker_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (filteredApps.isEmpty()) {
                    item(key = "merge-installed-empty") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.merge_split_installed_no_results),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = stringResource(R.string.merge_split_installed_no_results_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    items(
                        items = filteredApps,
                        key = { it.packageName }
                    ) { app ->
                        val packageInfo = app.packageInfo ?: return@items
                        MergeSplitInstalledAppCard(
                            packageInfo = packageInfo,
                            isSystemApp = pm.isSystemApp(packageInfo),
                            hasSplitApks = pm.hasSplitApks(packageInfo),
                            onClick = { onSelectApp(app.packageName) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MergeSplitInstalledAppCard(
    packageInfo: PackageInfo,
    isSystemApp: Boolean,
    hasSplitApks: Boolean,
    onClick: () -> Unit
) {
    val cardAlpha = if (hasSplitApks) 1f else 0.56f
    Surface(
        modifier = Modifier
            .alpha(cardAlpha)
            .then(
                if (hasSplitApks) Modifier.clickable(onClick = onClick)
                else Modifier
            ),
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIcon(
                    packageInfo = packageInfo,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp)
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    AppLabel(
                        packageInfo = packageInfo,
                        defaultText = packageInfo.packageName,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = packageInfo.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    AppVersion(packageInfo)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MergeSplitInstalledStatusChip(
                    label = stringResource(
                        if (hasSplitApks) R.string.merge_split_installed_status_split
                        else R.string.merge_split_installed_status_single
                    )
                )
                if (isSystemApp) {
                    MergeSplitInstalledStatusChip(
                        label = stringResource(R.string.merge_split_installed_status_system)
                    )
                }
            }
        }
    }
}

@Composable
private fun MergeSplitInstalledStatusChip(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
internal fun DownloaderPluginInputDialog(
    plugins: List<LoadedDownloaderPlugin>,
    activePluginId: String?,
    packageName: String,
    version: String,
    onPackageNameChange: (String) -> Unit,
    onVersionChange: (String) -> Unit,
    onDismissRequest: () -> Unit,
    onSelectPlugin: (LoadedDownloaderPlugin) -> Unit
) {
    val canSelect = activePluginId == null
    AlertDialogExtended(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel))
            }
        },
        title = { CenteredDialogTitle(stringResource(R.string.tools_merge_split_source_plugin_title)) },
        textHorizontalPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.tools_merge_split_source_plugin_hint),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextField(
                    value = packageName,
                    onValueChange = onPackageNameChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    label = { Text(stringResource(R.string.tools_merge_split_source_plugin_package_label)) },
                    singleLine = true,
                    enabled = canSelect
                )
                Spacer(modifier = Modifier.height(10.dp))
                TextField(
                    value = version,
                    onValueChange = onVersionChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    label = { Text(stringResource(R.string.tools_merge_split_source_plugin_version_label)) },
                    singleLine = true,
                    enabled = canSelect
                )
                Spacer(modifier = Modifier.height(12.dp))
                if (plugins.isEmpty()) {
                    Text(
                        text = stringResource(R.string.tools_merge_split_source_plugin_unavailable_plugins),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                    ) {
                        items(
                            items = plugins,
                            key = { it.id }
                        ) { plugin ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(
                                        enabled = canSelect
                                    ) { onSelectPlugin(plugin) },
                                color = Color.Transparent
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = plugin.shortDisplayName,
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (activePluginId == plugin.id) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    )
}

@Composable
fun Notifications(
    vararg notifications: (@Composable () -> Unit)?,
) {
    val activeNotifications = notifications.filterNotNull()

    if (activeNotifications.isNotEmpty()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            activeNotifications.forEach { notification ->
                notification()
            }
        }
    }
}

@Composable
private fun DashboardTabLabel(
    text: String,
    selected: Boolean,
    updateCount: Int?
) {
    val compactTabLabelStyle = MaterialTheme.typography.labelSmall.copy(
        letterSpacing = 0.sp,
        fontSize = 10.sp
    )
    val isSingleWord = text.none { it.isWhitespace() }
    val labelContent: @Composable (Modifier, Color) -> Unit = { modifier, color ->
        Row(
            modifier = modifier.widthIn(min = 56.dp, max = 88.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                modifier = Modifier.weight(1f, fill = false),
                style = compactTabLabelStyle,
                color = color,
                maxLines = if (isSingleWord) 1 else 2,
                softWrap = !isSingleWord,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis
            )
            updateCount?.let { DashboardTabUpdateBadge(it) }
        }
    }
    if (selected) {
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        ) {
            labelContent(
                Modifier.padding(
                    horizontal = if (isSingleWord) 3.dp else 6.dp,
                    vertical = 3.dp,
                ),
                MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    } else {
        labelContent(Modifier, MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DashboardTabUpdateBadge(updateCount: Int) {
    Badge(
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary
    ) {
        Text(
            text = updateCount.toString(),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun BundleFabHandle(
    collapsed: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(
        topStart = 22.dp,
        bottomStart = 22.dp,
        topEnd = 0.dp,
        bottomEnd = 0.dp
    )
    val interactionSource = remember { MutableInteractionSource() }
    val container = MaterialTheme.colorScheme.primaryContainer
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    val icon = if (collapsed) {
        Icons.Outlined.ChevronLeft
    } else {
        Icons.Outlined.ChevronRight
    }

    Box(
        modifier = modifier
            .size(width = 22.dp, height = 56.dp)
            .clip(shape)
            .background(container)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onToggle
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(16.dp)
        )
    }
}

private fun isAllowedSplitArchiveName(name: String): Boolean {
    val normalized = name.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
    return normalized == "apks" || normalized == "apkm" || normalized == "xapk" || normalized == "zip"
}

@Composable
private fun DashboardSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    placeholderRes: Int,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var queryFieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(query, selection = TextRange(query.length)))
    }

    LaunchedEffect(query) {
        if (query != queryFieldValue.text) {
            queryFieldValue = TextFieldValue(query, selection = TextRange(query.length))
        }
    }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 3.dp,
        shadowElevation = 10.dp,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        TextField(
            value = queryFieldValue,
            onValueChange = {
                queryFieldValue = it
                onQueryChange(it.text)
            },
            placeholder = { Text(stringResource(placeholderRes)) },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = {
                if (query.isNotBlank()) {
                    IconButton(
                        onClick = {
                            queryFieldValue = TextFieldValue("")
                            onClear()
                        }
                    ) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.clear))
                    }
                }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                shape = RoundedCornerShape(18.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp),
                disabledContainerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp),
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                focusedLeadingIconColor = MaterialTheme.colorScheme.primary,
                unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                focusedTrailingIconColor = MaterialTheme.colorScheme.primary,
                unfocusedTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
    }
}

@Composable
fun Android11Dialog(onDismissRequest: () -> Unit, onContinue: () -> Unit) {
    AlertDialogExtended(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = onContinue) {
                Text(stringResource(R.string.continue_))
            }
        },
        title = {
            Text(stringResource(R.string.android_11_bug_dialog_title))
        },
        icon = {
            Icon(Icons.Outlined.BugReport, null)
        },
        text = {
            Text(stringResource(R.string.android_11_bug_dialog_description))
        }
    )
}

