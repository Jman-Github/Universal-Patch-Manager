package app.universal.patch.manager.ui.screen.settings.update

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.universal.patch.manager.R
import app.universal.patch.manager.domain.manager.BundleUpdateDeliveryMode
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.domain.manager.SearchForUpdatesBackgroundInterval
import app.universal.patch.manager.ui.component.AppTopBar
import app.universal.patch.manager.ui.component.ColumnWithScrollbar
import app.universal.patch.manager.ui.component.GroupHeader
import app.universal.patch.manager.ui.component.SettingsSectionIcons
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsCard
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsConfigurableItem
import app.universal.patch.manager.ui.component.settings.BooleanItem
import app.universal.patch.manager.ui.component.settings.IntegerItem
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsDivider
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsItem
import app.universal.patch.manager.ui.component.settings.SettingsSearchHighlight
import app.universal.patch.manager.ui.viewmodel.UpdatesSettingsViewModel
import app.universal.patch.manager.ui.model.navigation.Settings
import app.universal.patch.manager.ui.screen.settings.SettingsSearchState
import app.universal.patch.manager.util.permission.hasNotificationPermission
import app.universal.patch.manager.util.toast
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universal.patch.manager.ui.component.CenteredDialogTitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatesSettingsScreen(
    onBackClick: () -> Unit,
    onChangelogClick: () -> Unit,
    onUpdateClick: () -> Unit,
    vm: UpdatesSettingsViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val managerInterval by vm.backgroundManagerUpdateInterval.getAsState()
    val backgroundInterval by vm.backgroundBundleUpdateInterval.getAsState()
    val announcementSystemEnabled by vm.announcementSystemEnabled.getAsState()
    val announcementPushNotificationInterval by vm.announcementPushNotificationInterval.getAsState()
    val deliveryMode by vm.bundleUpdateDeliveryMode.getAsState()
    val changelogFetchLimit by vm.bundleChangelogFetchLimit.getAsState()
    val changelogStorageLimit by vm.bundleChangelogStorageLimit.getAsState()
    val searchTarget by SettingsSearchState.target.collectAsStateWithLifecycle()
    var highlightTarget by rememberSaveable { mutableStateOf<Int?>(null) }
    var showBackgroundUpdateDialog by rememberSaveable { mutableStateOf(false) }
    var showBackgroundManagerUpdateDialog by rememberSaveable { mutableStateOf(false) }
    var showAnnouncementNotificationDialog by rememberSaveable { mutableStateOf(false) }
    var showDeliveryModeDialog by rememberSaveable { mutableStateOf(false) }
    var pendingInterval by rememberSaveable {
        mutableStateOf<SearchForUpdatesBackgroundInterval?>(null)
    }
    var pendingManagerInterval by rememberSaveable {
        mutableStateOf<SearchForUpdatesBackgroundInterval?>(null)
    }
    var pendingDeliveryMode by rememberSaveable {
        mutableStateOf<BundleUpdateDeliveryMode?>(null)
    }
    var pendingAnnouncementInterval by rememberSaveable {
        mutableStateOf<SearchForUpdatesBackgroundInterval?>(null)
    }
    var showNotificationPermissionDialog by rememberSaveable { mutableStateOf(false) }
    var showBatteryOptimizationDialog by rememberSaveable { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(searchTarget) {
        val target = searchTarget
        if (target?.destination == Settings.Updates) {
            highlightTarget = target.targetId
            SettingsSearchState.clear()
        }
    }
    val batteryOptimizationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val powerManager = context.getSystemService(PowerManager::class.java)
            val batteryOptimizationDisabled =
                powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
            if (!batteryOptimizationDisabled) {
                if (pendingInterval != null ||
                    pendingManagerInterval != null ||
                    pendingDeliveryMode != null ||
                    pendingAnnouncementInterval != null
                ) {
                    showBatteryOptimizationDialog = true
                }
                return@rememberLauncherForActivityResult
            }

            pendingInterval?.let { interval ->
                if (!context.hasNotificationPermission()) {
                    showNotificationPermissionDialog = true
                } else {
                    vm.updateBackgroundBundleUpdateTime(interval)
                    pendingInterval = null
                }
            }
            pendingManagerInterval?.let { interval ->
                if (!context.hasNotificationPermission()) {
                    showNotificationPermissionDialog = true
                } else {
                    vm.updateBackgroundManagerUpdateTime(interval)
                    pendingManagerInterval = null
                }
            }

            pendingDeliveryMode?.let { mode ->
                if (!context.hasNotificationPermission()) {
                    showNotificationPermissionDialog = true
                } else {
                    vm.updateBundleUpdateDeliveryMode(mode)
                    pendingDeliveryMode = null
                }
            }
            pendingAnnouncementInterval?.let { interval ->
                if (!context.hasNotificationPermission()) {
                    showNotificationPermissionDialog = true
                } else {
                    vm.updateAnnouncementPushNotificationTime(interval)
                    pendingAnnouncementInterval = null
                }
            }
        }

    DisposableEffect(
        lifecycleOwner,
        backgroundInterval,
        managerInterval,
        announcementPushNotificationInterval
    ) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver

            val powerManager = context.getSystemService(PowerManager::class.java)
            val batteryOptimizationDisabled =
                powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
            if ((backgroundInterval != SearchForUpdatesBackgroundInterval.NEVER ||
                    managerInterval != SearchForUpdatesBackgroundInterval.NEVER ||
                    announcementPushNotificationInterval != SearchForUpdatesBackgroundInterval.NEVER) &&
                !batteryOptimizationDisabled
            ) {
                showNotificationPermissionDialog = false
                showBatteryOptimizationDialog = false
                pendingInterval = null
                pendingManagerInterval = null
                pendingDeliveryMode = null
                pendingAnnouncementInterval = null
                if (backgroundInterval != SearchForUpdatesBackgroundInterval.NEVER) {
                    vm.updateBackgroundBundleUpdateTime(SearchForUpdatesBackgroundInterval.NEVER)
                }
                if (managerInterval != SearchForUpdatesBackgroundInterval.NEVER) {
                    vm.updateBackgroundManagerUpdateTime(SearchForUpdatesBackgroundInterval.NEVER)
                }
                if (announcementPushNotificationInterval != SearchForUpdatesBackgroundInterval.NEVER) {
                    vm.updateAnnouncementPushNotificationTime(SearchForUpdatesBackgroundInterval.NEVER)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingInterval?.let { interval ->
                vm.updateBackgroundBundleUpdateTime(interval)
                pendingInterval = null
            }
            pendingManagerInterval?.let { interval ->
                vm.updateBackgroundManagerUpdateTime(interval)
                pendingManagerInterval = null
            }
            pendingDeliveryMode?.let { mode ->
                vm.updateBundleUpdateDeliveryMode(mode)
                pendingDeliveryMode = null
            }
            pendingAnnouncementInterval?.let { interval ->
                vm.updateAnnouncementPushNotificationTime(interval)
                pendingAnnouncementInterval = null
            }
        }
        pendingInterval = null
        pendingManagerInterval = null
        pendingDeliveryMode = null
        pendingAnnouncementInterval = null
    }

    if (showNotificationPermissionDialog) {
        AlertDialog(
            onDismissRequest = {
                showNotificationPermissionDialog = false
                pendingInterval = null
                pendingManagerInterval = null
                pendingDeliveryMode = null
                pendingAnnouncementInterval = null
            },
            title = { CenteredDialogTitle(stringResource(R.string.background_bundle_ask_notification)) },
            text = {
                Text(
                    stringResource(
                        if (pendingAnnouncementInterval != null) {
                            R.string.announcement_push_notifications_permission_description
                        } else {
                            R.string.background_bundle_ask_notification_description
                        }
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showNotificationPermissionDialog = false
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                ) {
                    Text(stringResource(R.string.continue_))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showNotificationPermissionDialog = false
                        pendingInterval = null
                        pendingManagerInterval = null
                        pendingDeliveryMode = null
                        pendingAnnouncementInterval = null
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showBackgroundUpdateDialog) {
        BackgroundBundleUpdateTimeDialog(
            title = stringResource(R.string.background_bundle_update),
            current = backgroundInterval,
            onDismiss = { showBackgroundUpdateDialog = false },
            onConfirm = { interval ->
                if (interval == SearchForUpdatesBackgroundInterval.NEVER) {
                    vm.updateBackgroundBundleUpdateTime(interval)
                    pendingInterval = null
                    return@BackgroundBundleUpdateTimeDialog
                }

                val powerManager = context.getSystemService(PowerManager::class.java)
                val batteryOptimizationDisabled =
                    powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
                if (!batteryOptimizationDisabled) {
                    pendingInterval = interval
                    pendingManagerInterval = null
                    pendingDeliveryMode = null
                    pendingAnnouncementInterval = null
                    showBatteryOptimizationDialog = true
                    return@BackgroundBundleUpdateTimeDialog
                }

                if (!context.hasNotificationPermission()) {
                    pendingInterval = interval
                    pendingManagerInterval = null
                    pendingDeliveryMode = null
                    pendingAnnouncementInterval = null
                    showNotificationPermissionDialog = true
                    return@BackgroundBundleUpdateTimeDialog
                }

                vm.updateBackgroundBundleUpdateTime(interval)
                pendingInterval = null
            }
        )
    }

    if (showBackgroundManagerUpdateDialog) {
        BackgroundBundleUpdateTimeDialog(
            title = stringResource(R.string.background_manager_update),
            current = managerInterval,
            onDismiss = { showBackgroundManagerUpdateDialog = false },
            onConfirm = { interval ->
                if (interval == SearchForUpdatesBackgroundInterval.NEVER) {
                    vm.updateBackgroundManagerUpdateTime(interval)
                    pendingManagerInterval = null
                    return@BackgroundBundleUpdateTimeDialog
                }

                val powerManager = context.getSystemService(PowerManager::class.java)
                val batteryOptimizationDisabled =
                    powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
                if (!batteryOptimizationDisabled) {
                    pendingInterval = null
                    pendingManagerInterval = interval
                    pendingDeliveryMode = null
                    pendingAnnouncementInterval = null
                    showBatteryOptimizationDialog = true
                    return@BackgroundBundleUpdateTimeDialog
                }

                if (!context.hasNotificationPermission()) {
                    pendingInterval = null
                    pendingManagerInterval = interval
                    pendingDeliveryMode = null
                    pendingAnnouncementInterval = null
                    showNotificationPermissionDialog = true
                    return@BackgroundBundleUpdateTimeDialog
                }

                vm.updateBackgroundManagerUpdateTime(interval)
                pendingManagerInterval = null
            }
        )
    }

    if (showDeliveryModeDialog) {
        BundleUpdateDeliveryModeDialog(
            current = deliveryMode,
            onDismiss = { showDeliveryModeDialog = false },
            onConfirm = { mode ->
                if (mode == BundleUpdateDeliveryMode.WEBSOCKET_PREFERRED &&
                    (backgroundInterval != SearchForUpdatesBackgroundInterval.NEVER ||
                        managerInterval != SearchForUpdatesBackgroundInterval.NEVER)
                ) {
                    val powerManager = context.getSystemService(PowerManager::class.java)
                    val batteryOptimizationDisabled =
                        powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
                    if (!batteryOptimizationDisabled) {
                        pendingInterval = null
                        pendingManagerInterval = null
                        pendingDeliveryMode = mode
                        pendingAnnouncementInterval = null
                        showBatteryOptimizationDialog = true
                        return@BundleUpdateDeliveryModeDialog
                    }

                    if (!context.hasNotificationPermission()) {
                        pendingInterval = null
                        pendingManagerInterval = null
                        pendingDeliveryMode = mode
                        pendingAnnouncementInterval = null
                        showNotificationPermissionDialog = true
                        return@BundleUpdateDeliveryModeDialog
                    }
                }

                pendingInterval = null
                pendingManagerInterval = null
                pendingDeliveryMode = null
                pendingAnnouncementInterval = null
                vm.updateBundleUpdateDeliveryMode(mode)
            }
        )
    }

    if (showAnnouncementNotificationDialog) {
        BackgroundBundleUpdateTimeDialog(
            title = stringResource(R.string.announcement_push_notifications),
            current = announcementPushNotificationInterval,
            onDismiss = { showAnnouncementNotificationDialog = false },
            onConfirm = { interval ->
                if (interval == SearchForUpdatesBackgroundInterval.NEVER) {
                    vm.updateAnnouncementPushNotificationTime(interval)
                    pendingAnnouncementInterval = null
                    return@BackgroundBundleUpdateTimeDialog
                }

                val powerManager = context.getSystemService(PowerManager::class.java)
                val batteryOptimizationDisabled =
                    powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
                if (!batteryOptimizationDisabled) {
                    pendingInterval = null
                    pendingManagerInterval = null
                    pendingDeliveryMode = null
                    pendingAnnouncementInterval = interval
                    showBatteryOptimizationDialog = true
                    return@BackgroundBundleUpdateTimeDialog
                }

                if (!context.hasNotificationPermission()) {
                    pendingInterval = null
                    pendingManagerInterval = null
                    pendingDeliveryMode = null
                    pendingAnnouncementInterval = interval
                    showNotificationPermissionDialog = true
                    return@BackgroundBundleUpdateTimeDialog
                }

                pendingAnnouncementInterval = null
                vm.updateAnnouncementPushNotificationTime(interval)
            }
        )
    }

    if (showBatteryOptimizationDialog) {
        AlertDialog(
            onDismissRequest = {
                showBatteryOptimizationDialog = false
                pendingInterval = null
                pendingManagerInterval = null
                pendingDeliveryMode = null
                pendingAnnouncementInterval = null
            },
            title = { CenteredDialogTitle(stringResource(R.string.battery_optimization_dialog_title)) },
            text = { Text(stringResource(R.string.battery_optimization_dialog_description)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBatteryOptimizationDialog = false
                        batteryOptimizationLauncher.launch(
                            Intent(
                                AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.fromParts("package", context.packageName, null)
                            )
                        )
                    }
                ) {
                    Text(stringResource(R.string.disable_battery_optimization))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.updates),
                scrollBehavior = scrollBehavior,
                onBackClick = onBackClick
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { paddingValues ->
        ColumnWithScrollbar(
            thumbLength = app.universal.patch.manager.ui.component.DASHBOARD_SCROLLBAR_THUMB_LENGTH,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            SettingsSearchHighlight(
                targetKey = R.string.network_delivery_section,
                activeKey = highlightTarget,
                onHighlightComplete = { highlightTarget = null }
            ) { highlightModifier ->
                GroupHeader(
                    stringResource(R.string.network_delivery_section),
                    icon = SettingsSectionIcons.NetworkDelivery,
                    modifier = highlightModifier
                )
            }

            ExpressiveSettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                SettingsSearchHighlight(
                    targetKey = R.string.update_on_metered_connections,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    BooleanItem(
                        modifier = highlightModifier,
                        preference = vm.allowMeteredUpdates,
                        headline = R.string.update_on_metered_connections,
                        description = R.string.update_on_metered_connections_description
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.bundle_update_delivery_mode,
                    activeKey = highlightTarget,
                    extraKeys = setOf(
                        R.string.bundle_update_delivery_mode_auto,
                        R.string.bundle_update_delivery_mode_websocket_preferred,
                        R.string.bundle_update_delivery_mode_polling_only,
                    ),
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpressiveSettingsConfigurableItem(
                        modifier = highlightModifier,
                        headlineContent = stringResource(R.string.bundle_update_delivery_mode),
                        supportingContent = stringResource(
                            R.string.bundle_update_delivery_mode_description_with_current,
                            stringResource(deliveryMode.displayName)
                        ),
                        secondaryActionLabel = stringResource(R.string.reset),
                        onSecondaryAction = {
                            vm.updateBundleUpdateDeliveryMode(vm.bundleUpdateDeliveryMode.default)
                        },
                        secondaryActionEnabled = deliveryMode != vm.bundleUpdateDeliveryMode.default,
                        primaryActionLabel = stringResource(R.string.edit),
                        onPrimaryAction = { showDeliveryModeDialog = true }
                    )
                }
            }

            SettingsSearchHighlight(
                targetKey = R.string.update_checks_section,
                activeKey = highlightTarget,
                onHighlightComplete = { highlightTarget = null }
            ) { highlightModifier ->
                GroupHeader(
                    stringResource(R.string.update_checks_section),
                    icon = SettingsSectionIcons.UpdateChecks,
                    modifier = highlightModifier
                )
            }

            ExpressiveSettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                SettingsSearchHighlight(
                    targetKey = R.string.manual_update_check,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpressiveSettingsItem(
                        modifier = highlightModifier,
                        headlineContent = stringResource(R.string.manual_update_check),
                        supportingContent = stringResource(R.string.manual_update_check_description),
                        onClick = {
                            coroutineScope.launch {
                                if (!vm.isConnected) {
                                    context.toast(context.getString(R.string.no_network_toast))
                                    return@launch
                                }
                                if (vm.checkForUpdates()) onUpdateClick()
                            }
                        }
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.update_checking_manager,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    BooleanItem(
                        modifier = highlightModifier,
                        preference = vm.managerAutoUpdates,
                        headline = R.string.update_checking_manager,
                        description = R.string.update_checking_manager_description
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.show_manager_update_dialog_on_launch,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    BooleanItem(
                        modifier = highlightModifier,
                        preference = vm.showManagerUpdateDialogOnLaunch,
                        headline = R.string.show_manager_update_dialog_on_launch,
                        description = R.string.show_manager_update_dialog_on_launch_description
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.show_manager_update_changelog,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    BooleanItem(
                        modifier = highlightModifier,
                        preference = vm.showManagerUpdateChangelog,
                        headline = R.string.show_manager_update_changelog,
                        description = R.string.show_manager_update_changelog_description
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.manager_prereleases,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    BooleanItem(
                        modifier = highlightModifier,
                        preference = vm.useManagerPrereleases,
                        headline = R.string.manager_prereleases,
                        description = R.string.manager_prereleases_description
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.changelog,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpressiveSettingsItem(
                        modifier = highlightModifier,
                        headlineContent = stringResource(R.string.changelog),
                        supportingContent = stringResource(R.string.changelog_description),
                        onClick = {
                            if (!vm.isConnected) {
                                context.toast(context.getString(R.string.no_network_toast))
                                return@ExpressiveSettingsItem
                            }
                            onChangelogClick()
                        }
                    )
                }
            }

            SettingsSearchHighlight(
                targetKey = R.string.bundle_changelog_history_section,
                activeKey = highlightTarget,
                onHighlightComplete = { highlightTarget = null }
            ) { highlightModifier ->
                GroupHeader(
                    stringResource(R.string.bundle_changelog_history_section),
                    icon = SettingsSectionIcons.BundleChangelogHistory,
                    modifier = highlightModifier
                )
            }

            ExpressiveSettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                SettingsSearchHighlight(
                    targetKey = R.string.bundle_changelog_fetch_limit,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    IntegerItem(
                        modifier = highlightModifier,
                        preference = vm.bundleChangelogFetchLimit,
                        coroutineScope = coroutineScope,
                        headline = R.string.bundle_changelog_fetch_limit,
                        description = R.string.bundle_changelog_fetch_limit_description,
                        supportingText = stringResource(
                            R.string.bundle_changelog_fetch_limit_supporting,
                            changelogFetchLimit
                        ),
                        neutralButtonLabel = stringResource(R.string.reset_to_default),
                        neutralValueProvider = {
                            PreferencesManager.DEFAULT_BUNDLE_CHANGELOG_FETCH_LIMIT
                        },
                        validator = {
                            it >= PreferencesManager.MIN_BUNDLE_CHANGELOG_HISTORY_LIMIT
                        }
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.bundle_changelog_storage_limit,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    IntegerItem(
                        modifier = highlightModifier,
                        preference = vm.bundleChangelogStorageLimit,
                        coroutineScope = coroutineScope,
                        headline = R.string.bundle_changelog_storage_limit,
                        description = R.string.bundle_changelog_storage_limit_description,
                        supportingText = stringResource(
                            R.string.bundle_changelog_storage_limit_supporting,
                            changelogStorageLimit
                        ),
                        neutralButtonLabel = stringResource(R.string.reset_to_default),
                        neutralValueProvider = {
                            PreferencesManager.DEFAULT_BUNDLE_CHANGELOG_STORAGE_LIMIT
                        },
                        validator = {
                            it >= PreferencesManager.MIN_BUNDLE_CHANGELOG_HISTORY_LIMIT
                        }
                    )
                }
            }

            SettingsSearchHighlight(
                targetKey = R.string.background_updates_section,
                activeKey = highlightTarget,
                onHighlightComplete = { highlightTarget = null }
            ) { highlightModifier ->
                GroupHeader(
                    stringResource(R.string.background_updates_section),
                    icon = SettingsSectionIcons.BackgroundUpdates,
                    modifier = highlightModifier
                )
            }

            ExpressiveSettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                SettingsSearchHighlight(
                    targetKey = R.string.background_manager_update,
                    activeKey = highlightTarget,
                    extraKeys = setOf(
                        R.string.background_radio_menu_title,
                        R.string.background_bundle_ask_notification
                    ),
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpressiveSettingsConfigurableItem(
                        modifier = highlightModifier,
                        headlineContent = stringResource(R.string.background_manager_update),
                        supportingContent = stringResource(R.string.background_manager_update_description),
                        secondaryActionLabel = stringResource(R.string.reset),
                        onSecondaryAction = {
                            vm.updateBackgroundManagerUpdateTime(
                                vm.backgroundManagerUpdateInterval.default
                            )
                        },
                        secondaryActionEnabled =
                            managerInterval != vm.backgroundManagerUpdateInterval.default,
                        primaryActionLabel = stringResource(R.string.settings),
                        onPrimaryAction = { showBackgroundManagerUpdateDialog = true }
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.background_bundle_update,
                    activeKey = highlightTarget,
                    extraKeys = setOf(
                        R.string.background_radio_menu_title,
                        R.string.background_bundle_ask_notification
                    ),
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpressiveSettingsConfigurableItem(
                        modifier = highlightModifier,
                        headlineContent = stringResource(R.string.background_bundle_update),
                        supportingContent = stringResource(R.string.background_bundle_update_description),
                        secondaryActionLabel = stringResource(R.string.reset),
                        onSecondaryAction = {
                            vm.updateBackgroundBundleUpdateTime(
                                vm.backgroundBundleUpdateInterval.default
                            )
                        },
                        secondaryActionEnabled =
                            backgroundInterval != vm.backgroundBundleUpdateInterval.default,
                        primaryActionLabel = stringResource(R.string.settings),
                        onPrimaryAction = { showBackgroundUpdateDialog = true }
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.announcement_push_notifications,
                    activeKey = highlightTarget,
                    extraKeys = setOf(
                        R.string.announcement_push_notifications_permission_description,
                        R.string.background_radio_menu_title
                    ),
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpressiveSettingsConfigurableItem(
                        modifier = highlightModifier,
                        headlineContent = stringResource(R.string.announcement_push_notifications),
                        supportingContent = stringResource(
                            R.string.announcement_push_notifications_description
                        ),
                        enabled = announcementSystemEnabled,
                        secondaryActionLabel = stringResource(R.string.reset),
                        onSecondaryAction = {
                            vm.updateAnnouncementPushNotificationTime(
                                vm.announcementPushNotificationInterval.default
                            )
                        },
                        secondaryActionEnabled =
                            announcementSystemEnabled &&
                                announcementPushNotificationInterval !=
                                vm.announcementPushNotificationInterval.default,
                        primaryActionLabel = stringResource(R.string.settings),
                        onPrimaryAction = { showAnnouncementNotificationDialog = true },
                        primaryActionEnabled = announcementSystemEnabled
                    )
                }
            }
        }
    }
}

@Composable
private fun BackgroundBundleUpdateTimeDialog(
    title: String,
    current: SearchForUpdatesBackgroundInterval,
    onDismiss: () -> Unit,
    onConfirm: (SearchForUpdatesBackgroundInterval) -> Unit
) {
    var selected by rememberSaveable(current) { mutableStateOf(current) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { CenteredDialogTitle(title) },
        text = {
            Column {
                SearchForUpdatesBackgroundInterval.entries.forEach { interval ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = interval }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = selected == interval,
                            onClick = { selected = interval }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(interval.displayName),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected); onDismiss() }) {
                Text(stringResource(R.string.apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun BundleUpdateDeliveryModeDialog(
    current: BundleUpdateDeliveryMode,
    onDismiss: () -> Unit,
    onConfirm: (BundleUpdateDeliveryMode) -> Unit
) {
    var selected by rememberSaveable(current) { mutableStateOf(current) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { CenteredDialogTitle(stringResource(R.string.bundle_update_delivery_mode_dialog_title)) },
        text = {
            Column {
                BundleUpdateDeliveryMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = mode }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = selected == mode,
                            onClick = { selected = mode }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = stringResource(mode.displayName))
                            Text(
                                text = stringResource(mode.description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected); onDismiss() }) {
                Text(stringResource(R.string.apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

private val BundleUpdateDeliveryMode.description: Int
    get() = when (this) {
        BundleUpdateDeliveryMode.AUTO -> R.string.bundle_update_delivery_mode_auto_description
        BundleUpdateDeliveryMode.WEBSOCKET_PREFERRED -> R.string.bundle_update_delivery_mode_websocket_preferred_description
        BundleUpdateDeliveryMode.POLLING_ONLY -> R.string.bundle_update_delivery_mode_polling_only_description
    }
