package app.universal.patch.manager

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.appcompat.app.AppCompatActivity
import app.universal.patch.manager.data.room.profile.PatchProfilePayload
import app.universal.patch.manager.domain.batch.ManualBatchPatchQueue
import app.universal.patch.manager.domain.repository.resolvePatchProfileAppVersion
import app.universal.patch.manager.util.LocalPreventAccidentalTouching
import app.universal.patch.manager.ui.model.navigation.Announcement
import app.universal.patch.manager.ui.model.navigation.Announcements
import app.universal.patch.manager.ui.model.navigation.AppSelector
import app.universal.patch.manager.ui.model.navigation.ApkSigner
import app.universal.patch.manager.ui.model.navigation.BatchPatchDetails
import app.universal.patch.manager.ui.model.navigation.BatchPatcher
import app.universal.patch.manager.ui.model.navigation.BatchPatchesSelector
import app.universal.patch.manager.ui.model.navigation.ComplexParameter
import app.universal.patch.manager.ui.model.navigation.CreateYoutubeAssets
import app.universal.patch.manager.ui.model.navigation.Dashboard
import app.universal.patch.manager.ui.model.navigation.KeystoreConverter
import app.universal.patch.manager.ui.model.navigation.KeystoreCreator
import app.universal.patch.manager.ui.model.navigation.InstalledApplicationInfo
import app.universal.patch.manager.ui.model.navigation.MergeSplitApk
import app.universal.patch.manager.ui.model.navigation.Patcher
import app.universal.patch.manager.ui.model.navigation.PatchBundleDiscovery
import app.universal.patch.manager.ui.model.navigation.PatchBundleDiscoveryPatches
import app.universal.patch.manager.ui.model.navigation.SelectedApplicationInfo
import app.universal.patch.manager.ui.model.navigation.Settings
import app.universal.patch.manager.ui.model.navigation.SplitApkInstaller
import app.universal.patch.manager.ui.model.navigation.Update
import app.universal.patch.manager.ui.model.SelectedApp
import app.universal.patch.manager.ui.screen.AnnouncementScreen
import app.universal.patch.manager.ui.screen.AnnouncementsScreen
import app.universal.patch.manager.ui.screen.AppSelectorScreen
import app.universal.patch.manager.ui.screen.ApkSignerScreen
import app.universal.patch.manager.ui.screen.BatchPatchDetailsScreen
import app.universal.patch.manager.ui.screen.BatchPatcherHostEffects
import app.universal.patch.manager.ui.screen.BatchPatcherScreen
import app.universal.patch.manager.ui.screen.CreateYoutubeAssetsScreen
import app.universal.patch.manager.ui.screen.DashboardScreen
import app.universal.patch.manager.ui.screen.InstalledAppInfoScreen
import app.universal.patch.manager.ui.screen.KeystoreConverterScreen
import app.universal.patch.manager.ui.screen.KeystoreCreatorScreen
import app.universal.patch.manager.ui.screen.MergeSplitApkScreen
import app.universal.patch.manager.ui.screen.PatcherScreen
import app.universal.patch.manager.ui.screen.PatchBundleDiscoveryScreen
import app.universal.patch.manager.ui.screen.PatchBundleDiscoveryPatchesScreen
import app.universal.patch.manager.ui.screen.PatchesSelectorScreen
import app.universal.patch.manager.ui.screen.RequiredOptionsScreen
import app.universal.patch.manager.ui.screen.SelectedAppInfoScreen
import app.universal.patch.manager.ui.screen.SettingsScreen
import app.universal.patch.manager.ui.screen.SplitApkInstallerScreen
import app.universal.patch.manager.ui.screen.UpdateScreen
import app.universal.patch.manager.ui.screen.settings.AboutSettingsScreen
import app.universal.patch.manager.ui.screen.settings.AdvancedSettingsScreen
import app.universal.patch.manager.ui.screen.settings.AdvancedSettingsMode
import app.universal.patch.manager.ui.screen.settings.ContributorSettingsScreen
import app.universal.patch.manager.ui.screen.settings.DeveloperSettingsScreen
import app.universal.patch.manager.ui.screen.settings.DownloadsSettingsScreen
import app.universal.patch.manager.ui.screen.settings.GeneralSettingsScreen
import app.universal.patch.manager.ui.screen.settings.ImportExportSettingsScreen
import app.universal.patch.manager.ui.screen.settings.PatcherRuntimePluginsSettingsScreen
import app.universal.patch.manager.ui.screen.settings.StorageSettingsScreen
import app.universal.patch.manager.ui.screen.settings.update.ChangelogsSettingsScreen
import app.universal.patch.manager.ui.screen.settings.update.UpdatesSettingsScreen
import app.universal.patch.manager.ui.theme.UpmTheme
import app.universal.patch.manager.ui.theme.Theme
import app.universal.patch.manager.ui.viewmodel.BatchPatcherViewModel
import app.universal.patch.manager.ui.viewmodel.DashboardViewModel
import app.universal.patch.manager.ui.viewmodel.MainViewModel
import app.universal.patch.manager.ui.viewmodel.SelectedAppInfoViewModel
import app.universal.patch.manager.ui.viewmodel.ThemePreset
import app.universal.patch.manager.util.EventEffect
import app.universal.patch.manager.util.AppForeground
import app.universal.patch.manager.util.PatchBundleFileIntent
import app.universal.patch.manager.R
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel
import org.koin.androidx.compose.navigation.koinNavViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import org.koin.androidx.viewmodel.ext.android.getViewModel as getActivityViewModel
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {
    @ExperimentalAnimationApi
    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply the normal app theme before AppCompat initializes or restores the activity.
        installSplashScreen()
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()

        val vm: MainViewModel = getActivityViewModel()
        vm.handleInitialIntent(intent)

        setContent {
            val launcher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
                onResult = vm::applyLegacySettings
            )
            val theme by vm.prefs.theme.getAsState()
            val dynamicColor by vm.prefs.dynamicColor.getAsState()
            val pureBlackTheme by vm.prefs.pureBlackTheme.getAsState()
            val materialYouPureBlackTheme by vm.prefs.materialYouPureBlackTheme.getAsState()
            val pureBlackOnSystemDark by vm.prefs.pureBlackOnSystemDark.getAsState()
            val themePresetSelectionEnabled by vm.prefs.themePresetSelectionEnabled.getAsState()
            val selectedThemePresetName by vm.prefs.themePresetSelectionName.getAsState()
            val customAccentColor by vm.prefs.customAccentColor.getAsState()
            val customThemeColor by vm.prefs.customThemeColor.getAsState()
            val customBackgroundImageUri by vm.prefs.customBackgroundImageUri.getAsState()
            val customBackgroundImageOpacity by vm.prefs.customBackgroundImageOpacity.getAsState()
            val preventAccidentalTouching by vm.prefs.preventAccidentalTouching.getAsState()
            val systemDark = isSystemInDarkTheme()
            val supportsDynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            val materialYouPureBlackSelected = supportsDynamicColor &&
                themePresetSelectionEnabled &&
                selectedThemePresetName == ThemePreset.DYNAMIC.name &&
                materialYouPureBlackTheme
            val pureBlackPresetSelected = themePresetSelectionEnabled &&
                selectedThemePresetName == ThemePreset.PURE_BLACK.name &&
                pureBlackTheme
            val monochromePresetSelected = themePresetSelectionEnabled &&
                selectedThemePresetName == ThemePreset.MONOCHROME.name
            val darkThemeEnabled = materialYouPureBlackSelected ||
                theme == Theme.SYSTEM && systemDark ||
                theme == Theme.DARK
            val systemDarkPureBlackPresetSelected = themePresetSelectionEnabled &&
                (
                    selectedThemePresetName == ThemePreset.DEFAULT.name ||
                        selectedThemePresetName == ThemePreset.MONOCHROME.name ||
                        !supportsDynamicColor &&
                        selectedThemePresetName == ThemePreset.DYNAMIC.name
                )
            val pureBlackEnabled = materialYouPureBlackSelected ||
                pureBlackPresetSelected ||
                pureBlackOnSystemDark &&
                systemDarkPureBlackPresetSelected &&
                theme == Theme.SYSTEM &&
                systemDark
            val resolvedAccentColor = if (monochromePresetSelected) {
                if (darkThemeEnabled) "#FFFFFF" else "#000000"
            } else {
                customAccentColor.takeUnless { it.isBlank() }
            }
            val resolvedThemeColor = if (monochromePresetSelected) {
                null
            } else {
                customThemeColor.takeUnless { it.isBlank() }
            }

            EventEffect(vm.legacyImportActivityFlow) {
                try {
                    launcher.launch(it)
                } catch (_: ActivityNotFoundException) {
                }
            }

            CompositionLocalProvider(
                LocalPreventAccidentalTouching provides preventAccidentalTouching
            ) {
                UpmTheme(
                    darkTheme = darkThemeEnabled,
                    dynamicColor = dynamicColor,
                    pureBlackTheme = pureBlackEnabled,
                    accentColorHex = resolvedAccentColor,
                    themeColorHex = resolvedThemeColor,
                    hasCustomBackground = !customBackgroundImageUri.isNullOrBlank()
                ) {
                    ReVancedManagerBackground(
                        customBackgroundImageUri = customBackgroundImageUri.takeUnless { it.isBlank() },
                        imageOverlayAlpha = customBackgroundImageOpacity
                    ) {
                        ReVancedManager(
                            vm = vm,
                            disableScreenSlideTransitions = !customBackgroundImageUri.isNullOrBlank()
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val vm: MainViewModel = getActivityViewModel()
        vm.handleIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        AppForeground.onWindowFocusChanged(hasFocus)
    }
}

@Composable
private fun ReVancedManagerBackground(
    customBackgroundImageUri: String?,
    imageOverlayAlpha: Float,
    content: @Composable () -> Unit
) {
    Box(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
        if (!customBackgroundImageUri.isNullOrBlank()) {
            // Keep a single shared base/tint layer outside screen transitions.
            Box(
                modifier = androidx.compose.ui.Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
            )
            CustomBackgroundImage(
                customBackgroundImageUri = customBackgroundImageUri,
                modifier = androidx.compose.ui.Modifier.fillMaxSize()
            )
            Box(
                modifier = androidx.compose.ui.Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = imageOverlayAlpha.coerceIn(0f, 1f)))
            )
        }
        content()
    }
}

@Composable
private fun CustomBackgroundImage(
    customBackgroundImageUri: String,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier
) {
    val context = LocalContext.current
    val uri = remember(customBackgroundImageUri) { Uri.parse(customBackgroundImageUri) }
    val isFileUri = remember(uri) { uri.scheme.equals("file", ignoreCase = true) }
    val fileUriPath = remember(uri, isFileUri) { uri.path?.takeIf { isFileUri && it.isNotBlank() } }
    val asyncImageModel = remember(uri, fileUriPath) {
        fileUriPath?.let(::File) ?: uri
    }
    val mimeType = remember(uri) {
        runCatching { context.contentResolver.getType(uri) }
            .getOrNull()
            .orEmpty()
            .lowercase(Locale.ROOT)
    }
    val pathSegment = remember(uri) { uri.lastPathSegment.orEmpty().lowercase(Locale.ROOT) }
    val isTiff = mimeType == "image/tiff" || pathSegment.endsWith(".tif") || pathSegment.endsWith(".tiff")

    val tiffBitmap by produceState<ImageBitmap?>(initialValue = null, key1 = uri, key2 = isTiff) {
        if (!isTiff) {
            value = null
            return@produceState
        }

        value = withContext(Dispatchers.IO) {
            runCatching {
                val bitmap = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    fileUriPath?.let { ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(it))) }
                        ?: ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri))
                } else {
                    fileUriPath?.let(BitmapFactory::decodeFile)
                        ?: context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
                }
                bitmap?.asImageBitmap()
            }.getOrNull()
        }
    }

    if (isTiff && tiffBitmap != null) {
        Image(
            bitmap = tiffBitmap!!,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        AsyncImage(
            model = asyncImageModel,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    }
}

@Composable
private fun ReVancedManager(
    vm: MainViewModel,
    disableScreenSlideTransitions: Boolean
) {
    val navController = rememberNavController()
    val dashboardVm: DashboardViewModel = koinViewModel()
    val batchPatcherVm: BatchPatcherViewModel = koinViewModel()
    val manualBatchQueue: ManualBatchPatchQueue = koinInject()
    var pendingBundleDeepLink by remember { mutableStateOf<app.universal.patch.manager.util.BundleDeepLink?>(null) }
    var pendingSplitArchiveIntent by remember { mutableStateOf<app.universal.patch.manager.util.SplitArchiveIntent?>(null) }
    var pendingPatchBundleFileIntent by remember { mutableStateOf<PatchBundleFileIntent?>(null) }
    val context = LocalContext.current
    val returnToManualBatchSelector = {
        navController.navigate(AppSelector(batchQueue = true)) {
            popUpTo<AppSelector> { inclusive = false }
            launchSingleTop = true
        }
    }

    BatchPatcherHostEffects(batchPatcherVm)

    EventEffect(vm.appSelectFlow) { params ->
        navController.navigateComplex(
            SelectedApplicationInfo,
            params
        ) {
            if (params.returnToDashboard) {
                popUpTo<Dashboard> { inclusive = false }
            } else {
                popUpTo<SelectedApplicationInfo.Main> { inclusive = true }
            }
            launchSingleTop = true
        }
    }

    EventEffect(vm.bundleDeepLinkFlow) { deepLink ->
        pendingBundleDeepLink = deepLink
        navController.navigate(Dashboard) {
            launchSingleTop = true
            popUpTo(Dashboard) { inclusive = false }
        }
    }

    EventEffect(vm.managerUpdateDeepLinkFlow) {
        navController.navigate(Update()) {
            launchSingleTop = true
        }
    }

    EventEffect(vm.announcementDeepLinkFlow) { announcement ->
        dashboardVm.markAnnouncementRead(announcement.id)
        navController.navigateComplex(
            Announcement,
            announcement
        ) {
            launchSingleTop = true
        }
    }

    // Code adapted from Morphe, see third-party/NOTICE for more information
    // https://github.com/MorpheApp/morphe-manager/blob/6688aa17ea35b5ab398a3c1922be13626290cbf1/app/src/main/java/app/morphe/manager/MainActivity.kt#L222-L228
    EventEffect(vm.patchBundleFileIntentFlow) { patchBundleFileIntent ->
        pendingPatchBundleFileIntent = patchBundleFileIntent
        navController.navigate(Dashboard) {
            launchSingleTop = true
            popUpTo(Dashboard) { inclusive = false }
        }
    }

    EventEffect(vm.splitArchiveIntentFlow) { splitArchiveIntent ->
        pendingSplitArchiveIntent = splitArchiveIntent
        navController.navigate(SplitApkInstaller) {
            launchSingleTop = true
            popUpTo(Dashboard) { inclusive = false }
        }
    }

    EventEffect(vm.batchPatchRequestFlow) { request ->
        val requestId = batchPatcherVm.requestIdForPlan(
            packageNames = request.packageNames,
            scheduled = request.scheduled,
            showExistingResult = request.showExistingResult
        )
        if (!batchPatcherVm.canOpenPlan(
                packageNames = request.packageNames,
                scheduled = request.scheduled,
                showExistingResult = request.showExistingResult,
                requestId = requestId
            )
        ) return@EventEffect

        navController.navigate(
            BatchPatcher(
                packageNames = request.packageNames,
                startImmediately = request.startImmediately,
                showExistingResult = request.showExistingResult,
                scheduled = request.scheduled,
                requestId = requestId
            )
        ) {
            launchSingleTop = true
        }
    }

    EventEffect(vm.dashboardRequestFlow) {
        navController.navigate(Dashboard) {
            launchSingleTop = true
            popUpTo(Dashboard) { inclusive = false }
        }
    }

    EventEffect(vm.patchedAppsRequestFlow) { packages ->
        // Recreate Dashboard so a previously selected tab cannot hide the Apps tab.
        navController.navigate(Dashboard) {
            popUpTo(Dashboard) { inclusive = true }
        }
        packages.singleOrNull()?.let { packageName ->
            navController.navigate(InstalledApplicationInfo(packageName)) {
                launchSingleTop = true
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = Dashboard,
        enterTransition = {
            if (disableScreenSlideTransitions) EnterTransition.None
            else slideInHorizontally(initialOffsetX = { it })
        },
        exitTransition = {
            if (disableScreenSlideTransitions) ExitTransition.None
            else slideOutHorizontally(targetOffsetX = { -it / 3 })
        },
        popEnterTransition = {
            EnterTransition.None
        },
        popExitTransition = {
            if (disableScreenSlideTransitions) ExitTransition.None
            else slideOutHorizontally(targetOffsetX = { it })
        },
    ) {
        composable<Dashboard> {
            DashboardScreen(
                vm = dashboardVm,
                mainVm = vm,
                onSettingsClick = { navController.navigate(Settings) },
                onAppSelectorClick = {
                    navController.navigate(AppSelector())
                },
                onBatchQueueClick = {
                    manualBatchQueue.clear()
                    navController.navigate(AppSelector(batchQueue = true))
                },
                onStorageSelect = { saved -> vm.selectApp(saved) },
                onUpdateClick = {
                    navController.navigate(Update())
                },
                onAnnouncementsClick = {
                    navController.navigate(Announcements) {
                        launchSingleTop = true
                    }
                },
                onDownloaderPluginClick = {
                    navController.navigate(Settings.Downloads)
                },
                onPatcherRuntimePluginClick = {
                    navController.navigate(Settings.PatcherRuntimes)
                },
                onBundleDiscoveryClick = {
                    navController.navigate(PatchBundleDiscovery)
                },
                onMergeSplitClick = {
                    navController.navigate(MergeSplitApk)
                },
                onOpenSplitInstallerClick = {
                    navController.navigate(SplitApkInstaller) {
                        launchSingleTop = true
                    }
                },
                onCreateYoutubeAssetsClick = {
                    navController.navigate(CreateYoutubeAssets)
                },
                onOpenApkSignerClick = {
                    navController.navigate(ApkSigner)
                },
                onOpenKeystoreCreatorClick = {
                    navController.navigate(KeystoreCreator)
                },
                onOpenKeystoreConverterClick = {
                    navController.navigate(KeystoreConverter)
                },
                onAppClick = { packageName, action ->
                    navController.navigate(InstalledApplicationInfo(packageName, action))
                },
                onBatchPatch = { packageNames ->
                    val requestId = batchPatcherVm.requestIdForPlan(packageNames)
                    if (
                        batchPatcherVm.canOpenPlan(
                            packageNames = packageNames,
                            requestId = requestId
                        )
                    ) {
                        navController.navigate(
                            BatchPatcher(
                                packageNames = packageNames,
                                requestId = requestId
                            )
                        )
                    }
                },
                onAnnouncementClick = { announcement ->
                    navController.navigateComplex(
                        Announcement,
                        announcement
                    ) {
                        launchSingleTop = true
                    }
                },
                onProfileLaunch = { launchData ->
                    val apkFile = launchData.profile.apkPath
                        ?.let(::File)
                        ?.takeIf { it.exists() }
                    val resolvedVersion = resolvePatchProfileAppVersion(
                        appVersion = launchData.profile.appVersion,
                        apkPath = launchData.profile.apkPath,
                        apkVersion = launchData.profile.apkVersion,
                        useSelectedApkVersion = launchData.profile.useSelectedApkVersion
                    ) ?: context.getString(R.string.app_version_unspecified)
                    val selectedApp = if (apkFile != null) {
                        SelectedApp.Local(
                            packageName = launchData.profile.packageName,
                            version = resolvedVersion,
                            file = apkFile,
                            temporary = false,
                            resolved = true
                        )
                    } else {
                        SelectedApp.Search(
                            launchData.profile.packageName,
                            resolvePatchProfileAppVersion(
                                appVersion = launchData.profile.appVersion,
                                apkPath = launchData.profile.apkPath,
                                apkVersion = launchData.profile.apkVersion,
                                useSelectedApkVersion = launchData.profile.useSelectedApkVersion
                            )
                        )
                    }
                    navController.navigateComplex(
                        SelectedApplicationInfo,
                        SelectedApplicationInfo.ViewModelParams(
                            app = selectedApp,
                            patches = null,
                            persistConfiguration = false,
                            profileId = launchData.profile.uid,
                            requiresSourceSelection = apkFile == null
                        )
                    )
                },
                bundleDeepLink = pendingBundleDeepLink,
                onBundleDeepLinkConsumed = { pendingBundleDeepLink = null },
                patchBundleFileIntent = pendingPatchBundleFileIntent,
                onPatchBundleFileIntentConsumed = { pendingPatchBundleFileIntent = null }
            )
        }

        composable<BatchPatcher> {
            val data = it.toRoute<BatchPatcher>()
            BatchPatcherScreen(
                packageNames = data.packageNames,
                startImmediately = data.startImmediately,
                showExistingResult = data.showExistingResult,
                manualQueue = data.manualQueue,
                scheduled = data.scheduled,
                requestId = data.requestId,
                onBackClick = navController::navigateUp,
                onOpenProgress = { packageName ->
                    navController.navigate(BatchPatchDetails(packageName))
                },
                onEditPatches = { params ->
                    navController.navigateComplex(BatchPatchesSelector, params)
                },
                viewModel = batchPatcherVm
            )
        }

        composable<BatchPatchDetails> {
            val data = it.toRoute<BatchPatchDetails>()
            BatchPatchDetailsScreen(
                packageName = data.packageName,
                onBackClick = navController::popBackStack,
                viewModel = batchPatcherVm
            )
        }

        composable<BatchPatchesSelector> {
            val data =
                it.getComplexArg<SelectedApplicationInfo.PatchesSelector.ViewModelParams>()
            PatchesSelectorScreen(
                onBackClick = navController::popBackStack,
                onSave = { patches, options, _ ->
                    batchPatcherVm.updateConfiguration(
                        packageName = data.app.packageName,
                        patches = patches,
                        options = options
                    )
                    navController.popBackStack()
                },
                viewModel = koinViewModel { parametersOf(data) }
            )
        }

        composable<InstalledApplicationInfo> {
            val data = it.toRoute<InstalledApplicationInfo>()

            InstalledAppInfoScreen(
                onPatchClick = {
                    packageName,
                    sourceEntryKey,
                    selection,
                    selectionPayload,
                    persistConfiguration ->
                    vm.selectApp(
                        packageName = packageName,
                        patches = selection,
                        selectionPayload = selectionPayload,
                        persistConfiguration = persistConfiguration,
                        sourceEntryKey = sourceEntryKey
                    )
                },
                onBackClick = navController::popBackStack,
                viewModel = koinViewModel { parametersOf(data.packageName) },
                initialAction = data.action
            )
        }

        composable<AppSelector> {
            val args = it.toRoute<AppSelector>()
            val manualEntries by manualBatchQueue.entries.collectAsStateWithLifecycle()
            AppSelectorScreen(
                onSelect = { packageName ->
                    vm.selectApp(packageName, batchQueue = args.batchQueue)
                },
                onStorageSelect = { app ->
                    vm.selectApp(app, batchQueue = args.batchQueue)
                },
                onBackClick = {
                    if (args.batchQueue) manualBatchQueue.clear()
                    navController.popBackStack()
                },
                autoOpenStorage = args.autoStorage,
                returnToDashboardOnStorage = args.autoStorageReturn,
                batchQueueMode = args.batchQueue,
                batchQueueSize = manualEntries.size,
                onReviewQueue = {
                    val packageNames = manualEntries.map { entry ->
                        entry.input.packageName
                    }
                    val requestId = batchPatcherVm.requestIdForPlan(
                        packageNames = packageNames,
                        manualQueue = true
                    )
                    if (
                        manualEntries.size >= 2 &&
                        batchPatcherVm.canOpenPlan(
                            packageNames = packageNames,
                            manualQueue = true,
                            requestId = requestId
                        )
                    ) {
                        navController.navigate(
                            BatchPatcher(
                                packageNames = packageNames,
                                manualQueue = true,
                                requestId = requestId
                            )
                        )
                    }
                }
            )
        }

        composable<Patcher> {
            val params = it.getComplexArg<Patcher.ViewModelParams>()
            val previousEntry = remember(it) { requireNotNull(navController.previousBackStackEntry) }
            val selectedAppInfoEntry = navController.navGraphEntry(previousEntry)
            val selectedAppInfoArgs =
                selectedAppInfoEntry.getComplexArg<SelectedApplicationInfo.ViewModelParams>()
            val selectedAppInfoVm = koinNavViewModel<SelectedAppInfoViewModel>(
                viewModelStoreOwner = selectedAppInfoEntry
            ) {
                parametersOf(selectedAppInfoArgs)
            }
            PatcherScreen(
                onBackClick = { app ->
                    selectedAppInfoVm.updateSelectedApp(app)
                    navController.popBackStack()
                },
                onBackToDashboard = {
                    navController.navigate(Dashboard) {
                        popUpTo<Dashboard> { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onReviewSelection = { app, selection, options, missing ->
                    val appWithVersion = when (app) {
                        is SelectedApp.Search -> if (app.version == null) {
                            app.copy(version = params.selectedApp.version, versionCode = null)
                        } else {
                            app
                        }
                        is SelectedApp.Download -> if (app.version.isNullOrBlank()) {
                            app.copy(version = params.selectedApp.version, versionCode = null)
                        } else {
                            app
                        }
                        else -> app
                    }
                    navController.navigateComplex(
                        SelectedApplicationInfo.PatchesSelector,
                        SelectedApplicationInfo.PatchesSelector.ViewModelParams(
                            app = appWithVersion,
                            currentSelection = selection,
                            options = options,
                            missingPatchNames = missing,
                            preferredAppVersion = app.version,
                            preferredBundleVersion = null,
                            preferredBundleUid = selection.keys.firstOrNull(),
                            preferredBundleOverride = null,
                            preferredBundleTargetsAllVersions = false,
                            useMount = params.useMount,
                        )
                    )
                },
                viewModel = koinViewModel { parametersOf(params) }
            )
        }

        composable<Update> {
            val data = it.toRoute<Update>()

            UpdateScreen(
                onBackClick = navController::popBackStack,
                vm = koinViewModel { parametersOf(data.downloadOnScreenEntry) }
            )
        }

        composable<Announcements> {
            AnnouncementsScreen(
                onBackClick = navController::popBackStack,
                onAnnouncementClick = { announcement ->
                    navController.navigateComplex(
                        Announcement,
                        announcement
                    ) {
                        launchSingleTop = true
                    }
                }
            )
        }

        composable<Announcement> {
            AnnouncementScreen(
                onBackClick = navController::popBackStack,
                announcement = it.getComplexArg()
            )
        }

        composable<PatchBundleDiscovery> {
            PatchBundleDiscoveryScreen(
                onBackClick = navController::popBackStack,
                onViewPatches = { bundle ->
                    navController.navigate(
                        PatchBundleDiscoveryPatches(
                            bundleId = bundle.bundleId,
                            apiHost = bundle.apiHost,
                            sourceUrl = bundle.sourceUrl,
                            version = bundle.version,
                            fileHash = bundle.fileHash,
                            isPrerelease = bundle.isPrerelease,
                            patchCount = bundle.patchCount,
                            ownerName = bundle.ownerName,
                            repoName = bundle.repoName,
                            bundleType = bundle.bundleType,
                            downloadUrl = bundle.downloadUrl
                        )
                    )
                }
            )
        }

        composable<PatchBundleDiscoveryPatches> {
            val data = it.toRoute<PatchBundleDiscoveryPatches>()
            PatchBundleDiscoveryPatchesScreen(
                bundleId = data.bundleId,
                apiHost = data.apiHost,
                sourceUrl = data.sourceUrl,
                version = data.version,
                fileHash = data.fileHash,
                isPrerelease = data.isPrerelease,
                patchCount = data.patchCount,
                ownerName = data.ownerName,
                repoName = data.repoName,
                bundleType = data.bundleType,
                downloadUrl = data.downloadUrl,
                onBackClick = navController::popBackStack
            )
        }

        composable<MergeSplitApk> {
            MergeSplitApkScreen(
                onBackClick = navController::popBackStack,
                vm = dashboardVm
            )
        }

        composable<SplitApkInstaller> {
            SplitApkInstallerScreen(
                onBackClick = navController::popBackStack,
                pendingExternalInput = pendingSplitArchiveIntent,
                onExternalInputConsumed = { pendingSplitArchiveIntent = null }
            )
        }

        composable<CreateYoutubeAssets> {
            CreateYoutubeAssetsScreen(
                onBackClick = navController::popBackStack
            )
        }

        composable<ApkSigner> {
            ApkSignerScreen(
                onBackClick = navController::popBackStack
            )
        }

        composable<KeystoreCreator> {
            KeystoreCreatorScreen(
                onBackClick = navController::popBackStack
            )
        }

        composable<KeystoreConverter> {
            KeystoreConverterScreen(
                onBackClick = navController::popBackStack
            )
        }

        navigation<SelectedApplicationInfo>(startDestination = SelectedApplicationInfo.Main) {
            composable<SelectedApplicationInfo.Main> {
                val parentBackStackEntry = navController.navGraphEntry(it)
                val data =
                    parentBackStackEntry.getComplexArg<SelectedApplicationInfo.ViewModelParams>()
                val viewModel =
                    koinNavViewModel<SelectedAppInfoViewModel>(viewModelStoreOwner = parentBackStackEntry) {
                        parametersOf(data)
                    }

                SelectedAppInfoScreen(
                    onBackClick = navController::popBackStack,
                    onPatchClick = {
                        it.lifecycleScope.launch {
                            val patcherParams = viewModel.getPatcherParams()
                            if (data.batchQueue) {
                                manualBatchQueue.upsert(
                                    input = patcherParams.selectedApp,
                                    selection = patcherParams.selectedPatches,
                                    options = patcherParams.options,
                                    useMount = patcherParams.useMount,
                                    signatureWorkflow = PatchProfilePayload.SignatureWorkflow(
                                        enabled = patcherParams.injectSignatureMetadata,
                                        remembered = patcherParams.rememberSignatureWorkflow
                                    ),
                                    sourceEntryKey = patcherParams.sourceEntryKey,
                                )
                                returnToManualBatchSelector()
                            } else {
                                navController.navigateComplex(Patcher, patcherParams)
                            }
                        }
                    },
                    onPatchSelectorClick = { app, patches, options ->
                        val versionHint = viewModel.selectedAppInfo?.versionName?.takeUnless { it.isNullOrBlank() }
                            ?: app.version?.takeUnless { it.isNullOrBlank() }
                            ?: viewModel.preferredBundleVersion?.takeUnless { it.isNullOrBlank() }
                            ?: viewModel.desiredVersion
                        val appWithVersion = when (app) {
                            is SelectedApp.Search -> app.copy(
                                version = versionHint,
                                versionCode = app.versionCode.takeIf { app.version == versionHint }
                            )
                            is SelectedApp.Download -> if (app.version.isNullOrBlank()) {
                                app.copy(version = versionHint, versionCode = null)
                            } else {
                                app
                            }
                            else -> app
                        }
                        navController.navigateComplex(
                            SelectedApplicationInfo.PatchesSelector,
                            SelectedApplicationInfo.PatchesSelector.ViewModelParams(
                                appWithVersion,
                                patches,
                                options,
                                preferredAppVersion = versionHint,
                                preferredBundleVersion = viewModel.preferredBundleVersion,
                                preferredBundleUid = viewModel.selectedBundleUidFlow.value,
                                preferredBundleOverride = viewModel.selectedBundleVersionOverrideFlow.value,
                                preferredBundleTargetsAllVersions = viewModel.preferredBundleTargetsAllVersionsFlow.value,
                                useMount = viewModel.usingMountInstall,
                            )
                        )
                    },
                    onRequiredOptions = { app, patches, options ->
                        val versionHint = viewModel.selectedAppInfo?.versionName?.takeUnless { it.isNullOrBlank() }
                            ?: app.version?.takeUnless { it.isNullOrBlank() }
                            ?: viewModel.preferredBundleVersion?.takeUnless { it.isNullOrBlank() }
                            ?: viewModel.desiredVersion
                        val appWithVersion = when (app) {
                            is SelectedApp.Search -> app.copy(
                                version = versionHint,
                                versionCode = app.versionCode.takeIf { app.version == versionHint }
                            )
                            is SelectedApp.Download -> if (app.version.isNullOrBlank()) {
                                app.copy(version = versionHint, versionCode = null)
                            } else {
                                app
                            }
                            else -> app
                        }
                        navController.navigateComplex(
                            SelectedApplicationInfo.RequiredOptions,
                            SelectedApplicationInfo.PatchesSelector.ViewModelParams(
                                appWithVersion,
                                patches,
                                options,
                                preferredAppVersion = versionHint,
                                preferredBundleVersion = viewModel.preferredBundleVersion,
                                preferredBundleUid = viewModel.selectedBundleUidFlow.value,
                                preferredBundleOverride = viewModel.selectedBundleVersionOverrideFlow.value,
                                preferredBundleTargetsAllVersions = viewModel.preferredBundleTargetsAllVersionsFlow.value,
                                useMount = viewModel.usingMountInstall,
                            )
                        )
                    },
                    batchQueueMode = data.batchQueue,
                    vm = viewModel
                )
            }

            composable<SelectedApplicationInfo.PatchesSelector> {
                val data =
                    it.getComplexArg<SelectedApplicationInfo.PatchesSelector.ViewModelParams>()
                val parentEntry = navController.navGraphEntry(it)
                val parentArgs =
                    parentEntry.getComplexArg<SelectedApplicationInfo.ViewModelParams>()
                val selectedAppInfoVm = koinNavViewModel<SelectedAppInfoViewModel>(
                    viewModelStoreOwner = parentEntry
                ) {
                    parametersOf(parentArgs)
                }

                PatchesSelectorScreen(
                    onBackClick = navController::popBackStack,
                    onSave = { patches, options, pendingInputs ->
                        selectedAppInfoVm.updateConfiguration(
                            patches,
                            options,
                            pendingInputs = pendingInputs
                        )
                        navController.popBackStack()
                    },
                    viewModel = koinViewModel { parametersOf(data) }
                )
            }

            composable<SelectedApplicationInfo.RequiredOptions> {
                val data =
                    it.getComplexArg<SelectedApplicationInfo.PatchesSelector.ViewModelParams>()
                val parentEntry = navController.navGraphEntry(it)
                val parentArgs =
                    parentEntry.getComplexArg<SelectedApplicationInfo.ViewModelParams>()
                val selectedAppInfoVm = koinNavViewModel<SelectedAppInfoViewModel>(
                    viewModelStoreOwner = parentEntry
                ) {
                    parametersOf(parentArgs)
                }

                RequiredOptionsScreen(
                    onBackClick = navController::popBackStack,
                    onContinue = { patches, options, pendingInputs ->
                        selectedAppInfoVm.updateConfiguration(
                            patches,
                            options,
                            pendingInputs = pendingInputs
                        )
                        it.lifecycleScope.launch {
                            val patcherParams = selectedAppInfoVm.getPatcherParams()
                            if (parentArgs.batchQueue) {
                                manualBatchQueue.upsert(
                                    input = patcherParams.selectedApp,
                                    selection = patcherParams.selectedPatches,
                                    options = patcherParams.options,
                                    useMount = patcherParams.useMount,
                                    signatureWorkflow = PatchProfilePayload.SignatureWorkflow(
                                        enabled = patcherParams.injectSignatureMetadata,
                                        remembered = patcherParams.rememberSignatureWorkflow
                                    ),
                                    sourceEntryKey = patcherParams.sourceEntryKey,
                                )
                                returnToManualBatchSelector()
                            } else {
                                navController.navigateComplex(Patcher, patcherParams)
                            }
                        }
                    },
                    vm = koinViewModel { parametersOf(data) }
                )
            }
        }

        navigation<Settings>(startDestination = Settings.Main) {
            composable<Settings.Main> {
                SettingsScreen(
                    onBackClick = navController::popBackStack,
                    navigate = navController::navigate
                )
            }

            composable<Settings.General> {
                GeneralSettingsScreen(onBackClick = navController::popBackStack)
            }

            composable<Settings.Advanced> {
                AdvancedSettingsScreen(
                    onBackClick = navController::popBackStack,
                    mode = AdvancedSettingsMode.ADVANCED_SYSTEM
                )
            }

            composable<Settings.Patcher> {
                AdvancedSettingsScreen(
                    onBackClick = navController::popBackStack,
                    mode = AdvancedSettingsMode.PATCHER
                )
            }

            composable<Settings.AdvancedSystem> {
                AdvancedSettingsScreen(
                    onBackClick = navController::popBackStack,
                    mode = AdvancedSettingsMode.ADVANCED_SYSTEM
                )
            }

            composable<Settings.Developer> {
                DeveloperSettingsScreen(onBackClick = navController::popBackStack)
            }

            composable<Settings.Updates> {
                UpdatesSettingsScreen(
                    onBackClick = navController::popBackStack,
                    onChangelogClick = { navController.navigate(Settings.Changelogs) },
                    onUpdateClick = { navController.navigate(Update()) }
                )
            }

            composable<Settings.Downloads> {
                DownloadsSettingsScreen(onBackClick = navController::popBackStack)
            }

            composable<Settings.PatcherRuntimes> {
                PatcherRuntimePluginsSettingsScreen(onBackClick = navController::popBackStack)
            }

            composable<Settings.Storage> {
                StorageSettingsScreen(onBackClick = navController::popBackStack)
            }

            composable<Settings.ImportExport> {
                ImportExportSettingsScreen(onBackClick = navController::popBackStack)
            }

            composable<Settings.About> {
                AboutSettingsScreen(
                    onBackClick = navController::popBackStack,
                    navigate = navController::navigate
                )
            }

            composable<Settings.Changelogs> {
                ChangelogsSettingsScreen(onBackClick = navController::popBackStack)
            }

            composable<Settings.Contributors> {
                ContributorSettingsScreen(onBackClick = navController::popBackStack)
            }


        }
    }
}

@Composable
private fun NavController.navGraphEntry(entry: NavBackStackEntry) =
    remember(entry) { getBackStackEntry(entry.destination.parent!!.id) }

// Androidx Navigation does not support storing complex types in route objects, so we have to store them inside the saved state handle of the back stack entry instead.
private fun <T : Parcelable, R : ComplexParameter<T>> NavController.navigateComplex(
    route: R,
    data: T,
    options: NavOptionsBuilder.() -> Unit = {}
) {
    navigate(route, options)
    getBackStackEntry(route).savedStateHandle["args"] = data
}

private fun <T : Parcelable> NavBackStackEntry.getComplexArg() = savedStateHandle.get<T>("args")!!
