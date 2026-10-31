package app.universal.patch.manager.ui.screen.settings

import app.universal.patch.manager.util.managerStorageRoot

import app.universal.patch.manager.util.managerStorageContext

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universal.patch.manager.R
import app.universal.patch.manager.data.platform.Filesystem
import app.universal.patch.manager.data.room.apps.installed.InstallType
import app.universal.patch.manager.data.room.apps.installed.InstalledApp
import app.universal.patch.manager.domain.lsposed.LsposedRepository
import app.universal.patch.manager.domain.manager.AutoClearCacheInterval
import app.universal.patch.manager.domain.manager.KeystoreManager
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.domain.repository.DownloadedAppRepository
import app.universal.patch.manager.domain.repository.DownloaderPluginRepository
import app.universal.patch.manager.domain.repository.InstalledAppRepository
import app.universal.patch.manager.domain.repository.PatchBundleRepository
import app.universal.patch.manager.domain.repository.PatchProfileRepository
import app.universal.patch.manager.domain.repository.PatcherRuntimePluginRepository
import app.universal.patch.manager.domain.storage.CacheCleanupGuard
import app.universal.patch.manager.domain.storage.RepatchSourceCleanup
import app.universal.patch.manager.domain.storage.clearManagerCache
import app.universal.patch.manager.domain.worker.WorkerRepository
import app.universal.patch.manager.patcher.worker.PatcherWorker
import app.universal.patch.manager.ui.component.AppTopBar
import app.universal.patch.manager.ui.component.ColumnWithScrollbar
import app.universal.patch.manager.ui.component.GroupHeader
import app.universal.patch.manager.ui.component.ShimmerBox
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsCard
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsConfigurableItem
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsDivider
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsItem
import app.universal.patch.manager.ui.component.settings.SettingsSearchHighlight
import app.universal.patch.manager.ui.model.navigation.Settings
import app.universal.patch.manager.util.APK_SIGNER_CACHE_DIR
import app.universal.patch.manager.util.permission.hasNotificationPermission
import app.universal.patch.manager.util.savedAppBasePackage
import app.universal.patch.manager.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageSettingsScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val prefs: PreferencesManager = koinInject()
    val downloadedAppRepository: DownloadedAppRepository = koinInject()
    val downloaderPluginRepository: DownloaderPluginRepository = koinInject()
    val installedAppRepository: InstalledAppRepository = koinInject()
    val patchBundleRepository: PatchBundleRepository = koinInject()
    val patchProfileRepository: PatchProfileRepository = koinInject()
    val patcherRuntimePluginRepository: PatcherRuntimePluginRepository = koinInject()
    val lsposedRepository: LsposedRepository = koinInject()
    val keystoreManager: KeystoreManager = koinInject()
    val workerRepository: WorkerRepository = koinInject()
    val filesystem: Filesystem = koinInject()
    val repatchSourceCleanup: RepatchSourceCleanup = koinInject()
    val coroutineScope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val searchTarget by SettingsSearchState.target.collectAsStateWithLifecycle()
    val autoClearCacheInterval by prefs.autoClearCacheInterval.getAsState()
    var highlightTarget by rememberSaveable { mutableStateOf<Int?>(null) }
    var snapshot by remember { mutableStateOf<StorageSnapshot?>(null) }
    var isLoading by rememberSaveable { mutableStateOf(false) }
    var showClearCacheDialog by rememberSaveable { mutableStateOf(false) }
    var showAutoClearCacheDialog by rememberSaveable { mutableStateOf(false) }
    var pendingAutoClearCacheInterval by rememberSaveable { mutableStateOf<AutoClearCacheInterval?>(null) }
    var pendingAutoClearCacheRunNow by rememberSaveable { mutableStateOf(false) }
    var pendingClearTarget by rememberSaveable { mutableStateOf<StorageClearTarget?>(null) }
    var pendingHighRiskClearTarget by rememberSaveable { mutableStateOf<StorageClearTarget?>(null) }
    var expandedStorageGroups by rememberSaveable { mutableStateOf(emptyList<String>()) }

    fun refreshStorageUsage() {
        coroutineScope.launch {
            val loadingStartedAt = SystemClock.elapsedRealtime()
            isLoading = true
            try {
                snapshot = loadStorageSnapshot(context, filesystem, installedAppRepository, keystoreManager, repatchSourceCleanup)
                val remainingShimmerTime = STORAGE_REFRESH_SHIMMER_MIN_MS -
                    (SystemClock.elapsedRealtime() - loadingStartedAt)
                if (remainingShimmerTime > 0L) {
                    delay(remainingShimmerTime)
                }
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshStorageUsage()
    }

    suspend fun isPatcherWorkActive(): Boolean {
        if (workerRepository.activeUniqueWorkId(PatcherWorker.UNIQUE_WORK_NAME) != null) return true
        return withContext(Dispatchers.IO) {
            workerRepository.workManager
                .getWorkInfosForUniqueWork(PatcherWorker.UNIQUE_WORK_NAME)
                .get()
                .any { workInfo -> !workInfo.state.isFinished }
        }
    }

    fun clearTarget(target: StorageClearTarget) {
        if (target.blockedWhileStorageInUse && CacheCleanupGuard.isCacheInUse) {
            context.toast(context.getString(R.string.storage_cache_in_use))
            return
        }
        val areaName = target.title(context)
        coroutineScope.launch {
            if (target == StorageClearTarget.RepatchInputs && isPatcherWorkActive()) {
                context.toast(context.getString(R.string.patcher_already_running))
                return@launch
            }
            isLoading = true
            try {
                val clearedBytes = clearStorageTarget(
                    context = context,
                    target = target,
                    prefs = prefs,
                    downloadedAppRepository = downloadedAppRepository,
                    downloaderPluginRepository = downloaderPluginRepository,
                    installedAppRepository = installedAppRepository,
                    patchBundleRepository = patchBundleRepository,
                    patchProfileRepository = patchProfileRepository,
                    patcherRuntimePluginRepository = patcherRuntimePluginRepository,
                    lsposedRepository = lsposedRepository,
                    keystoreManager = keystoreManager
                )
                snapshot = loadStorageSnapshot(context, filesystem, installedAppRepository, keystoreManager, repatchSourceCleanup)
                context.toast(
                    context.getString(
                        R.string.storage_area_cleared,
                        areaName,
                        Formatter.formatShortFileSize(context, clearedBytes)
                    )
                )
            } finally {
                isLoading = false
            }
        }
    }

    fun updateAutoClearCacheInterval(interval: AutoClearCacheInterval) {
        coroutineScope.launch {
            prefs.autoClearCacheInterval.update(interval)
            workerRepository.scheduleAutoClearCacheWork(interval)
            context.toast(
                context.getString(
                    R.string.storage_auto_clear_cache_updated,
                    context.getString(interval.displayName)
                )
            )
        }
    }

    fun runAutoClearCacheNow() {
        val workId = workerRepository.launchAutoClearCacheNow()
        context.toast(context.getString(R.string.storage_auto_clear_cache_queued))
        coroutineScope.launch {
            isLoading = true
            try {
                workerRepository.workManager.getWorkInfoByIdFlow(workId)
                    .first { workInfo -> workInfo?.state?.isFinished == true }
                snapshot = loadStorageSnapshot(context, filesystem, installedAppRepository, keystoreManager, repatchSourceCleanup)
            } finally {
                isLoading = false
            }
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingAutoClearCacheInterval?.let(::updateAutoClearCacheInterval)
            if (pendingAutoClearCacheRunNow) {
                runAutoClearCacheNow()
            }
        }
        pendingAutoClearCacheInterval = null
        pendingAutoClearCacheRunNow = false
    }

    fun requestAutoClearCacheInterval(interval: AutoClearCacheInterval) {
        if (interval == AutoClearCacheInterval.NEVER || context.hasNotificationPermission()) {
            updateAutoClearCacheInterval(interval)
        } else {
            pendingAutoClearCacheInterval = interval
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun requestRunAutoClearCacheNow() {
        if (context.hasNotificationPermission()) {
            runAutoClearCacheNow()
        } else {
            pendingAutoClearCacheRunNow = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(searchTarget) {
        val target = searchTarget
        if (target?.destination == Settings.Storage) {
            highlightTarget = target.targetId
            SettingsSearchState.clear()
        }
    }

    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearCacheDialog = false
                        if (CacheCleanupGuard.isCacheInUse) {
                            context.toast(context.getString(R.string.storage_cache_in_use))
                            return@TextButton
                        }
                        coroutineScope.launch {
                            isLoading = true
                            try {
                                val clearedBytes = clearManagerCache(context)
                                snapshot = loadStorageSnapshot(context, filesystem, installedAppRepository, keystoreManager, repatchSourceCleanup)
                                context.toast(
                                    context.getString(
                                        R.string.storage_cache_cleared,
                                        Formatter.formatShortFileSize(context, clearedBytes)
                                    )
                                )
                            } finally {
                                isLoading = false
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            title = { CenteredDialogText(stringResource(R.string.storage_clear_cache_dialog_title)) },
            text = { CenteredDialogText(stringResource(R.string.storage_clear_cache_dialog_description)) }
        )
    }

    if (showAutoClearCacheDialog) {
        AutoClearCacheIntervalDialog(
            current = autoClearCacheInterval,
            onDismiss = { showAutoClearCacheDialog = false },
            onConfirm = ::requestAutoClearCacheInterval
        )
    }

    pendingClearTarget?.let { target ->
        val areaName = target.title(context)
        AlertDialog(
            onDismissRequest = { pendingClearTarget = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingClearTarget = null
                        if (target.requiresExtraConfirmation) {
                            pendingHighRiskClearTarget = target
                        } else {
                            clearTarget(target)
                        }
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingClearTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            title = { CenteredDialogText(stringResource(R.string.storage_clear_area_dialog_title, areaName)) },
            text = { CenteredDialogText(stringResource(R.string.storage_clear_area_dialog_description, areaName)) }
        )
    }

    pendingHighRiskClearTarget?.let { target ->
        val areaName = target.title(context)
        val warningDescriptionRes = target.warningDescriptionRes
        AlertDialog(
            onDismissRequest = { pendingHighRiskClearTarget = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingHighRiskClearTarget = null
                        clearTarget(target)
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingHighRiskClearTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            title = { CenteredDialogText(stringResource(R.string.storage_clear_area_warning_title, areaName)) },
            text = {
                CenteredDialogText(
                    if (warningDescriptionRes != null) {
                        stringResource(warningDescriptionRes)
                    } else {
                        stringResource(R.string.storage_clear_area_dialog_description, areaName)
                    }
                )
            }
        )
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.storage_cache_management),
                scrollBehavior = scrollBehavior,
                onBackClick = onBackClick
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { paddingValues ->
        ColumnWithScrollbar(
            thumbLength = app.universal.patch.manager.ui.component.DASHBOARD_SCROLLBAR_THUMB_LENGTH,
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            SettingsSearchHighlight(
                targetKey = R.string.storage_overview_section,
                activeKey = highlightTarget,
                onHighlightComplete = { highlightTarget = null }
            ) { highlightModifier ->
                GroupHeader(
                    stringResource(R.string.storage_overview_section),
                    icon = Icons.Outlined.Storage,
                    modifier = highlightModifier
                )
            }
            ExpressiveSettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                SettingsSearchHighlight(
                    targetKey = R.string.storage_total_app_storage,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    StorageOverviewItem(
                        modifier = highlightModifier,
                        sizeText = snapshot?.let { Formatter.formatShortFileSize(context, it.totalBytes) },
                        isLoading = isLoading
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.storage_refresh_usage,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpressiveSettingsItem(
                        headlineContent = stringResource(R.string.storage_refresh_usage),
                        supportingContent = stringResource(R.string.storage_refresh_usage_description),
                        leadingContent = {
                            Icon(Icons.Outlined.Refresh, null)
                        },
                        enabled = !isLoading,
                        onClick = ::refreshStorageUsage,
                        modifier = highlightModifier
                    )
                }
            }

            SettingsSearchHighlight(
                targetKey = R.string.storage_usage_section,
                activeKey = highlightTarget,
                onHighlightComplete = { highlightTarget = null }
            ) { highlightModifier ->
                GroupHeader(
                    stringResource(R.string.storage_usage_section),
                    icon = Icons.Outlined.Folder,
                    modifier = highlightModifier
                )
            }
            ExpressiveSettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                val areas = snapshot?.areas.orEmpty()
                val showUsageShimmer = snapshot == null
                if (showUsageShimmer) {
                    val placeholderCount = snapshot?.areas?.size ?: STORAGE_AREA_PLACEHOLDER_COUNT
                    repeat(placeholderCount) { index ->
                        StorageAreaPlaceholderItem()
                        if (index != placeholderCount - 1) {
                            ExpressiveSettingsDivider()
                        }
                    }
                } else if (areas.isEmpty()) {
                    ExpressiveSettingsItem(
                        headlineContent = stringResource(R.string.storage_calculating),
                        supportingContent = stringResource(R.string.storage_usage_section_description)
                    )
                } else {
                    areas.forEachIndexed { index, area ->
                        val groupKey = area.clearTarget?.name
                        val expandedBySearch = area.children.any { child -> child.targetKey == highlightTarget }
                        val groupExpanded = area.children.isNotEmpty() &&
                                ((groupKey != null && groupKey in expandedStorageGroups) || expandedBySearch)
                        SettingsSearchHighlight(
                            targetKey = area.targetKey,
                            activeKey = highlightTarget,
                            onHighlightComplete = { highlightTarget = null }
                        ) { highlightModifier ->
                            StorageAreaItem(
                                area = area,
                                totalBytes = snapshot?.totalBytes ?: 0L,
                                isLoading = isLoading,
                                formatSize = { Formatter.formatShortFileSize(context, it) },
                                onClear = { clearTarget -> pendingClearTarget = clearTarget },
                                onManage = {
                                    if (!context.openAppStorageSettings()) {
                                        context.toast(context.getString(R.string.storage_open_app_storage_settings_failed))
                                    }
                                },
                                expandable = area.children.isNotEmpty(),
                                expanded = groupExpanded,
                                onToggleExpanded = {
                                    groupKey?.let { key ->
                                        expandedStorageGroups = if (key in expandedStorageGroups) {
                                            expandedStorageGroups - key
                                        } else {
                                            expandedStorageGroups + key
                                        }
                                    }
                                },
                                modifier = highlightModifier
                            )
                        }
                        if (groupExpanded) {
                            area.children.forEach { child ->
                                ExpressiveSettingsDivider()
                                SettingsSearchHighlight(
                                    targetKey = child.targetKey,
                                    activeKey = highlightTarget,
                                    onHighlightComplete = { highlightTarget = null }
                                ) { highlightModifier ->
                                    StorageAreaItem(
                                        area = child,
                                        totalBytes = snapshot?.totalBytes ?: 0L,
                                        isLoading = isLoading,
                                        formatSize = { Formatter.formatShortFileSize(context, it) },
                                        onClear = { clearTarget -> pendingClearTarget = clearTarget },
                                        onManage = {
                                            if (!context.openAppStorageSettings()) {
                                                context.toast(context.getString(R.string.storage_open_app_storage_settings_failed))
                                            }
                                        },
                                        child = true,
                                        modifier = highlightModifier.padding(start = 28.dp)
                                    )
                                }
                            }
                        }
                        if (index != areas.lastIndex) {
                            ExpressiveSettingsDivider()
                        }
                    }
                }
            }

            SettingsSearchHighlight(
                targetKey = R.string.storage_cache_actions_section,
                activeKey = highlightTarget,
                onHighlightComplete = { highlightTarget = null }
            ) { highlightModifier ->
                GroupHeader(
                    stringResource(R.string.storage_cache_actions_section),
                    icon = Icons.Outlined.Delete,
                    modifier = highlightModifier
                )
            }
            ExpressiveSettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                SettingsSearchHighlight(
                    targetKey = R.string.storage_clear_app_cache,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpressiveSettingsItem(
                        headlineContent = stringResource(R.string.storage_clear_app_cache),
                        supportingContent = stringResource(R.string.storage_clear_app_cache_description),
                        leadingContent = {
                            Icon(Icons.Outlined.Delete, null)
                        },
                        enabled = !isLoading,
                        onClick = { showClearCacheDialog = true },
                        modifier = highlightModifier
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.storage_auto_clear_cache,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpressiveSettingsConfigurableItem(
                        headlineContent = stringResource(R.string.storage_auto_clear_cache),
                        supportingContent = stringResource(
                            R.string.storage_auto_clear_cache_description_with_current,
                            stringResource(autoClearCacheInterval.displayName)
                        ),
                        leadingContent = {
                            Icon(Icons.Outlined.Refresh, null)
                        },
                        enabled = !isLoading,
                        secondaryActionLabel = stringResource(R.string.storage_auto_clear_cache_run_now),
                        onSecondaryAction = ::requestRunAutoClearCacheNow,
                        primaryActionLabel = stringResource(R.string.settings),
                        onPrimaryAction = { showAutoClearCacheDialog = true },
                        modifier = highlightModifier
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.storage_open_app_storage_settings,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpressiveSettingsItem(
                        headlineContent = stringResource(R.string.storage_open_app_storage_settings),
                        supportingContent = stringResource(R.string.storage_open_app_storage_settings_description),
                        leadingContent = {
                            Icon(Icons.Outlined.Settings, null)
                        },
                        onClick = {
                            if (!context.openAppStorageSettings()) {
                                context.toast(context.getString(R.string.storage_open_app_storage_settings_failed))
                            }
                        },
                        modifier = highlightModifier
                    )
                }
            }
        }
    }
}

@Composable
private fun StorageOverviewItem(
    sizeText: String?,
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    ExpressiveSettingsItem(
        headlineContent = stringResource(R.string.storage_total_app_storage),
        supportingContent = stringResource(R.string.storage_total_app_storage_description),
        leadingContent = {
            Icon(Icons.Outlined.Storage, null)
        },
        trailingContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isLoading || sizeText == null) {
                    ShimmerBox(
                        modifier = Modifier
                            .width(92.dp)
                            .height(22.dp),
                        shape = RoundedCornerShape(999.dp)
                    )
                } else {
                    Text(
                        text = sizeText,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        },
        modifier = modifier
    )
}

@Composable
private fun StorageAreaItem(
    area: StorageAreaUsage,
    totalBytes: Long,
    isLoading: Boolean,
    formatSize: (Long) -> String,
    onClear: (StorageClearTarget) -> Unit,
    onManage: () -> Unit,
    expandable: Boolean = false,
    expanded: Boolean = false,
    onToggleExpanded: () -> Unit = {},
    child: Boolean = false,
    modifier: Modifier = Modifier
) {
    val percent = if (totalBytes > 0L) {
        (area.stats.bytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    ExpressiveSettingsItem(
        headlineContent = {
            Text(
                text = area.title,
                style = if (child) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium
            )
        },
        supportingContentSlot = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = area.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                StorageUsageBar(progress = percent)
                Text(
                    text = stringResource(
                        R.string.storage_item_count,
                        area.stats.fileCount,
                        area.stats.directoryCount
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (area.clearTarget == null) {
                    TextButton(
                        onClick = onManage,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = null,
                            modifier = Modifier.width(18.dp).height(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.storage_manage_area),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        },
        trailingContent = {
            Column(
                horizontalAlignment = Alignment.End
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (area.stats.bytes > 0L) formatSize(area.stats.bytes) else stringResource(R.string.storage_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.End,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (expandable) {
                        Icon(
                            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ChevronRight,
                            contentDescription = stringResource(
                                if (expanded) R.string.collapse_content else R.string.expand_content
                            ),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f),
                            modifier = Modifier
                                .width(18.dp)
                                .height(18.dp)
                        )
                    }
                }
                area.clearTarget?.let { clearTarget ->
                    StorageAreaActionButton(
                        text = stringResource(R.string.clear),
                        enabled = !isLoading && (area.clearableBytes ?: area.stats.bytes) > 0L
                    ) { onClear(clearTarget) }
                }
            }
        },
        onClick = if (expandable) onToggleExpanded else null,
        modifier = modifier
    )
}

@Composable
private fun StorageAreaPlaceholderItem(
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 16.dp)
    ) {
        ShimmerBox(
            modifier = Modifier
                .fillMaxWidth(0.68f)
                .height(18.dp),
            shape = RoundedCornerShape(999.dp)
        )
    }
}

@Composable
private fun StorageAreaActionButton(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.width(72.dp),
        contentPadding = PaddingValues(0.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun AutoClearCacheIntervalDialog(
    current: AutoClearCacheInterval,
    onDismiss: () -> Unit,
    onConfirm: (AutoClearCacheInterval) -> Unit
) {
    var selected by rememberSaveable(current) { mutableStateOf(current) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { CenteredDialogText(stringResource(R.string.storage_auto_clear_cache_dialog_title)) },
        text = {
            Column {
                AutoClearCacheInterval.entries.forEach { interval ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = interval }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
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
private fun CenteredDialogText(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center
    )
}

@Composable
private fun StorageUsageBar(progress: Float) {
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        if (progress > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0.02f, 1f))
                    .fillMaxHeight()
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.75f))
            )
        }
    }
}

private data class StorageSnapshot(
    val areas: List<StorageAreaUsage>,
    val totalBytes: Long
)

private const val STORAGE_AREA_PLACEHOLDER_COUNT = 17
private const val STORAGE_REFRESH_SHIMMER_MIN_MS = 1200L

private data class StorageAreaUsage(
    val targetKey: Int,
    val title: String,
    val description: String,
    val stats: DirectoryStats,
    val clearTarget: StorageClearTarget?,
    val clearableBytes: Long? = null,
    val children: List<StorageAreaUsage> = emptyList()
)

private enum class StorageClearTarget {
    InternalCache,
    CodeCache,
    ApkSignerCache,
    InternalFiles,
    NoBackupFiles,
    CustomBackgrounds,
    DownloadedApps,
    PatchBundles,
    SigningFiles,
    DownloaderPlugins,
    PatcherRuntimePlugins,
    LsposedModules,
    PatchedApps,
    RetainedOriginals,
    RepatchInputs,
    PatchProfileInputs,
    TemporaryWorkspace,
    UiTemporaryWorkspace,
    ExternalCache,
    ExternalFiles;

    val requiresExtraConfirmation: Boolean
        get() = when (this) {
            InternalFiles,
            NoBackupFiles,
            PatchBundles,
            SigningFiles,
            PatcherRuntimePlugins,
            LsposedModules,
            PatchedApps,
            RetainedOriginals,
            RepatchInputs,
            PatchProfileInputs,
            ExternalFiles -> true
            InternalCache,
            CodeCache,
            ApkSignerCache,
            CustomBackgrounds,
            DownloadedApps,
            DownloaderPlugins,
            TemporaryWorkspace,
            UiTemporaryWorkspace,
            ExternalCache -> false
        }

    val warningDescriptionRes: Int?
        get() = when (this) {
            InternalFiles -> R.string.storage_clear_internal_files_warning_description
            NoBackupFiles -> R.string.storage_clear_no_backup_files_warning_description
            PatchBundles -> R.string.storage_clear_patch_bundles_warning_description
            SigningFiles -> R.string.storage_clear_signing_files_warning_description
            PatcherRuntimePlugins -> R.string.storage_clear_patcher_runtime_plugins_warning_description
            LsposedModules -> R.string.storage_clear_lsposed_modules_warning_description
            PatchedApps -> R.string.storage_clear_patched_apps_warning_description
            RetainedOriginals -> R.string.storage_clear_retained_originals_warning_description
            RepatchInputs -> R.string.storage_clear_repatch_inputs_warning_description
            PatchProfileInputs -> R.string.storage_clear_patch_profile_inputs_warning_description
            ExternalFiles -> R.string.storage_clear_external_files_warning_description
            InternalCache,
            CodeCache,
            ApkSignerCache,
            CustomBackgrounds,
            DownloadedApps,
            DownloaderPlugins,
            TemporaryWorkspace,
            UiTemporaryWorkspace,
            ExternalCache -> null
        }

    val blockedWhileStorageInUse: Boolean
        get() = when (this) {
            InternalCache,
            CodeCache,
            ApkSignerCache,
            InternalFiles,
            NoBackupFiles,
            DownloadedApps,
            PatchBundles,
            SigningFiles,
            DownloaderPlugins,
            PatcherRuntimePlugins,
            LsposedModules,
            PatchedApps,
            RetainedOriginals,
            RepatchInputs,
            PatchProfileInputs,
            TemporaryWorkspace,
            UiTemporaryWorkspace,
            ExternalCache,
            ExternalFiles -> true
            CustomBackgrounds -> false
        }

    fun title(context: Context): String = context.getString(
        when (this) {
            InternalCache -> R.string.storage_internal_cache
            CodeCache -> R.string.storage_code_cache
            ApkSignerCache -> R.string.storage_apk_signer_cache
            InternalFiles -> R.string.storage_internal_files
            NoBackupFiles -> R.string.storage_no_backup_files
            CustomBackgrounds -> R.string.storage_custom_backgrounds
            DownloadedApps -> R.string.storage_downloaded_apps
            PatchBundles -> R.string.storage_patch_bundles
            SigningFiles -> R.string.storage_signing_files
            DownloaderPlugins -> R.string.storage_downloader_plugins
            PatcherRuntimePlugins -> R.string.storage_patcher_runtime_plugins
            LsposedModules -> R.string.storage_lsposed_modules
            PatchedApps -> R.string.storage_patched_apps
            RetainedOriginals -> R.string.storage_retained_originals
            RepatchInputs -> R.string.storage_repatch_inputs
            PatchProfileInputs -> R.string.storage_patch_profile_inputs
            TemporaryWorkspace -> R.string.storage_temporary_workspace
            UiTemporaryWorkspace -> R.string.storage_ui_temporary_workspace
            ExternalCache -> R.string.storage_external_cache
            ExternalFiles -> R.string.storage_external_files
        }
    )
}

private data class DirectoryStats(
    val bytes: Long = 0L,
    val fileCount: Long = 0L,
    val directoryCount: Long = 0L
) {
    operator fun plus(other: DirectoryStats) = DirectoryStats(
        bytes = bytes + other.bytes,
        fileCount = fileCount + other.fileCount,
        directoryCount = directoryCount + other.directoryCount
    )

    operator fun minus(other: DirectoryStats) = DirectoryStats(
        bytes = (bytes - other.bytes).coerceAtLeast(0L),
        fileCount = (fileCount - other.fileCount).coerceAtLeast(0L),
        directoryCount = (directoryCount - other.directoryCount).coerceAtLeast(0L)
    )
}

private suspend fun loadStorageSnapshot(
    context: Context,
    filesystem: Filesystem,
    installedAppRepository: InstalledAppRepository,
    keystoreManager: KeystoreManager,
    repatchSourceCleanup: RepatchSourceCleanup
): StorageSnapshot = withContext(Dispatchers.IO) {
    repatchSourceCleanup.pruneUnusedSources()
    pruneUnreferencedPatchedAppFiles(filesystem, installedAppRepository)

    val dataRoot = context.managerStorageRoot
    val customBackgroundsDir = context.managerStorageContext.filesDir.resolve("custom_background")
    val preferencesDataStoreDir = context.managerStorageContext.filesDir.resolve("datastore")
    val lsposedModulesDir = context.managerStorageContext.filesDir.resolve("lsposed_modules")
    val databasesDir = dataRoot.resolve("databases")
    val downloadedAppsDir = context.privateAppDir("downloaded-apps")
    val patchBundlesDir = context.privateAppDir("patch_bundles")
    val downloaderPluginsDir = context.privateAppDir("managed_downloader_plugins")
    val patcherRuntimePluginsDir = context.privateAppDir("managed_patcher_runtime_plugins")
    val patchedAppsDir = context.privateAppDir("patched-apps")
    val sharedApksStats = context.privateAppDir("shared-apks").directoryStats()
    val retainedOriginalsDir = context.privateAppDir("original-apps")
    val repatchInputsDir = context.privateAppDir("repatch-inputs")
    val repatchInputStagingDir = context.privateAppDir("repatch-input-staging")
    val patchProfileInputsDir = context.privateAppDir("patch-profile-inputs")
    val temporaryWorkspaceDir = context.privateAppDir("ephemeral")
    val uiTemporaryWorkspaceDir = context.privateAppDir("ui_ephemeral")
    val apkSignerCacheDir = context.managerStorageContext.cacheDir.resolve(APK_SIGNER_CACHE_DIR)
    val signingStorageRoots = keystoreManager.signingStorageRoots()
    val externalFilesDirs = context.managerStorageContext.getExternalFilesDirs(null).filterNotNull()
    val internalSigningRoots = signingStorageRoots.filter { root -> root.isWithin(dataRoot) }
    val signingRootsInFilesDir = internalSigningRoots.filter { root -> root.isWithin(context.managerStorageContext.filesDir) }
    val externalSigningRoots = signingStorageRoots.filter { root ->
        externalFilesDirs.any { externalRoot -> root.isWithin(externalRoot) }
    }

    val internalCacheStats = context.managerStorageContext.cacheDir.directoryStats()
    val codeCacheStats = context.managerStorageContext.codeCacheDir.directoryStats()
    val apkSignerCacheStats = apkSignerCacheDir.directoryStats()
    val customBackgroundsStats = customBackgroundsDir.directoryStats()
    val preferencesDataStoreStats = preferencesDataStoreDir.directoryStats()
    val lsposedModulesStats = lsposedModulesDir.directoryStats()
    val databasesStats = databasesDir.directoryStats()
    val internalSigningStats = internalSigningRoots.combinedStats()
    val signingStats = signingStorageRoots.combinedStats()
    val internalFilesStats = context.managerStorageContext.filesDir.directoryStats() -
        customBackgroundsStats -
        preferencesDataStoreStats -
        lsposedModulesStats -
        signingRootsInFilesDir.combinedStats()
    val noBackupStats = context.managerStorageContext.noBackupFilesDir.directoryStats()
    val downloadedAppsStats = downloadedAppsDir.directoryStats()
    val patchBundlesStats = patchBundlesDir.directoryStats()
    val downloaderPluginsStats = downloaderPluginsDir.directoryStats()
    val patcherRuntimePluginsStats = patcherRuntimePluginsDir.directoryStats()
    val patchedAppsStats = patchedAppsDir.directoryStats()
    val retainedOriginalsStats = retainedOriginalsDir.directoryStats()
    val retainedRepatchInputsStats = repatchInputsDir.directoryStats()
    val repatchInputStagingStats = repatchInputStagingDir.directoryStats()
    val repatchInputsStats = retainedRepatchInputsStats + repatchInputStagingStats
    val patchProfileInputsStats = patchProfileInputsDir.directoryStats()
    val temporaryWorkspaceStats = temporaryWorkspaceDir.directoryStats()
    val uiTemporaryWorkspaceStats = uiTemporaryWorkspaceDir.directoryStats()
    val externalCacheStats = context.managerStorageContext.externalCacheDirs.filterNotNull().combinedStats()
    val externalFilesStats = externalFilesDirs.combinedStats() - externalSigningRoots.combinedStats()
    // PR cache and no-backup directories live outside the private profile root.
    // Subtract only directories actually included in dataRoot's total.
    val knownInternalStats = listOf(
        internalCacheStats.takeIf { context.managerStorageContext.cacheDir.isWithin(dataRoot) } ?: DirectoryStats(),
        codeCacheStats.takeIf { context.managerStorageContext.codeCacheDir.isWithin(dataRoot) } ?: DirectoryStats(),
        internalFilesStats,
        preferencesDataStoreStats,
        lsposedModulesStats,
        databasesStats,
        noBackupStats.takeIf { context.managerStorageContext.noBackupFilesDir.isWithin(dataRoot) } ?: DirectoryStats(),
        customBackgroundsStats,
        downloadedAppsStats,
        patchBundlesStats,
        internalSigningStats,
        downloaderPluginsStats,
        patcherRuntimePluginsStats,
        patchedAppsStats,
        retainedOriginalsStats,
        sharedApksStats,
        repatchInputsStats,
        patchProfileInputsStats,
        temporaryWorkspaceStats,
        uiTemporaryWorkspaceStats
    ).fold(DirectoryStats()) { total, stats -> total + stats }
    val otherInternalStats = dataRoot.directoryStats() - knownInternalStats

    val codeCacheArea = StorageAreaUsage(
        targetKey = R.string.storage_code_cache,
        title = context.getString(R.string.storage_code_cache),
        description = context.getString(R.string.storage_code_cache_description),
        stats = codeCacheStats,
        clearTarget = StorageClearTarget.CodeCache,
        clearableBytes = context.managerStorageContext.codeCacheDir.clearableStorageBytes()
    )
    val apkSignerCacheArea = StorageAreaUsage(
        targetKey = R.string.storage_apk_signer_cache,
        title = context.getString(R.string.storage_apk_signer_cache),
        description = context.getString(R.string.storage_apk_signer_cache_description),
        stats = apkSignerCacheStats,
        clearTarget = StorageClearTarget.ApkSignerCache
    )
    val customBackgroundsArea = StorageAreaUsage(
        targetKey = R.string.storage_custom_backgrounds,
        title = context.getString(R.string.storage_custom_backgrounds),
        description = context.getString(R.string.storage_custom_backgrounds_description),
        stats = customBackgroundsStats,
        clearTarget = StorageClearTarget.CustomBackgrounds
    )
    val uiTemporaryWorkspaceArea = StorageAreaUsage(
        targetKey = R.string.storage_ui_temporary_workspace,
        title = context.getString(R.string.storage_ui_temporary_workspace),
        description = context.getString(R.string.storage_ui_temporary_workspace_description),
        stats = uiTemporaryWorkspaceStats,
        clearTarget = StorageClearTarget.UiTemporaryWorkspace
    )

    val areas = listOf(
        StorageAreaUsage(
            targetKey = R.string.storage_shared_apks,
            title = context.getString(R.string.storage_shared_apks),
            description = context.getString(R.string.storage_shared_apks_description),
            stats = sharedApksStats,
            clearTarget = null
        ),
        StorageAreaUsage(
            targetKey = R.string.storage_internal_cache,
            title = context.getString(R.string.storage_internal_cache),
            description = context.getString(R.string.storage_internal_cache_description),
            stats = internalCacheStats + codeCacheStats,
            clearTarget = StorageClearTarget.InternalCache,
            clearableBytes = context.managerStorageContext.cacheDir.clearableStorageBytes() +
                context.managerStorageContext.codeCacheDir.clearableStorageBytes(),
            children = listOf(codeCacheArea, apkSignerCacheArea)
        ),
        StorageAreaUsage(
            targetKey = R.string.storage_internal_files,
            title = context.getString(R.string.storage_internal_files),
            description = context.getString(R.string.storage_internal_files_description),
            stats = internalFilesStats + customBackgroundsStats,
            clearTarget = StorageClearTarget.InternalFiles,
            children = listOf(customBackgroundsArea)
        ),
        StorageAreaUsage(
            targetKey = R.string.storage_no_backup_files,
            title = context.getString(R.string.storage_no_backup_files),
            description = context.getString(R.string.storage_no_backup_files_description),
            stats = noBackupStats,
            clearTarget = StorageClearTarget.NoBackupFiles,
            clearableBytes = context.managerStorageContext.noBackupFilesDir.clearableStorageBytes()
        ),
        StorageAreaUsage(
            targetKey = R.string.storage_downloaded_apps,
            title = context.getString(R.string.storage_downloaded_apps),
            description = context.getString(R.string.storage_downloaded_apps_description),
            stats = downloadedAppsStats,
            clearTarget = StorageClearTarget.DownloadedApps
        ),
        StorageAreaUsage(
            targetKey = R.string.storage_patch_bundles,
            title = context.getString(R.string.storage_patch_bundles),
            description = context.getString(R.string.storage_patch_bundles_description),
            stats = patchBundlesStats,
            clearTarget = StorageClearTarget.PatchBundles
        ),
        StorageAreaUsage(
            targetKey = R.string.storage_signing_files,
            title = context.getString(R.string.storage_signing_files),
            description = context.getString(R.string.storage_signing_files_description),
            stats = signingStats,
            clearTarget = StorageClearTarget.SigningFiles
        ),
        StorageAreaUsage(
            targetKey = R.string.storage_downloader_plugins,
            title = context.getString(R.string.storage_downloader_plugins),
            description = context.getString(R.string.storage_downloader_plugins_description),
            stats = downloaderPluginsStats,
            clearTarget = StorageClearTarget.DownloaderPlugins
        ),
        StorageAreaUsage(
            targetKey = R.string.storage_patcher_runtime_plugins,
            title = context.getString(R.string.storage_patcher_runtime_plugins),
            description = context.getString(R.string.storage_patcher_runtime_plugins_description),
            stats = patcherRuntimePluginsStats,
            clearTarget = StorageClearTarget.PatcherRuntimePlugins
        ),
        StorageAreaUsage(
            targetKey = R.string.storage_lsposed_modules,
            title = context.getString(R.string.storage_lsposed_modules),
            description = context.getString(R.string.storage_lsposed_modules_description),
            stats = lsposedModulesStats,
            clearTarget = StorageClearTarget.LsposedModules
        ),
        StorageAreaUsage(
            targetKey = R.string.storage_patched_apps,
            title = context.getString(R.string.storage_patched_apps),
            description = context.getString(R.string.storage_patched_apps_description),
            stats = patchedAppsStats,
            clearTarget = StorageClearTarget.PatchedApps
        ),
        StorageAreaUsage(
            targetKey = R.string.storage_retained_originals,
            title = context.getString(R.string.storage_retained_originals),
            description = context.getString(R.string.storage_retained_originals_description),
            stats = retainedOriginalsStats,
            clearTarget = StorageClearTarget.RetainedOriginals
        ),
        StorageAreaUsage(
            targetKey = R.string.storage_repatch_inputs,
            title = context.getString(R.string.storage_repatch_inputs),
            description = context.getString(R.string.storage_repatch_inputs_description),
            stats = repatchInputsStats,
            clearTarget = StorageClearTarget.RepatchInputs,
            clearableBytes = retainedRepatchInputsStats.bytes
        ),
        StorageAreaUsage(
            targetKey = R.string.storage_patch_profile_inputs,
            title = context.getString(R.string.storage_patch_profile_inputs),
            description = context.getString(R.string.storage_patch_profile_inputs_description),
            stats = patchProfileInputsStats,
            clearTarget = StorageClearTarget.PatchProfileInputs
        ),
        StorageAreaUsage(
            targetKey = R.string.storage_temporary_workspace,
            title = context.getString(R.string.storage_temporary_workspace),
            description = context.getString(R.string.storage_temporary_workspace_description),
            stats = temporaryWorkspaceStats + uiTemporaryWorkspaceStats,
            clearTarget = StorageClearTarget.TemporaryWorkspace,
            children = listOf(uiTemporaryWorkspaceArea)
        ),
        StorageAreaUsage(
            targetKey = R.string.storage_other_internal_data,
            title = context.getString(R.string.storage_other_internal_data),
            description = context.getString(R.string.storage_other_internal_data_description),
            stats = otherInternalStats,
            // This bucket includes durable private state; only owned orphan files are pruned.
            clearTarget = null
        ),
        StorageAreaUsage(
            targetKey = R.string.storage_external_cache,
            title = context.getString(R.string.storage_external_cache),
            description = context.getString(R.string.storage_external_cache_description),
            stats = externalCacheStats,
            clearTarget = StorageClearTarget.ExternalCache,
            clearableBytes = context.managerStorageContext.externalCacheDirs.filterNotNull().sumOf { it.clearableStorageBytes() }
        ),
        StorageAreaUsage(
            targetKey = R.string.storage_external_files,
            title = context.getString(R.string.storage_external_files),
            description = context.getString(R.string.storage_external_files_description),
            stats = externalFilesStats,
            clearTarget = StorageClearTarget.ExternalFiles,
            clearableBytes = externalFilesDirs.sumOf { it.clearableStorageBytes(signingStorageRoots) }
        )
    )
    StorageSnapshot(
        areas = areas,
        totalBytes = areas.fold(0L) { total, area -> total + area.stats.bytes }
    )
}

private suspend fun clearStorageTarget(
    context: Context,
    target: StorageClearTarget,
    prefs: PreferencesManager,
    downloadedAppRepository: DownloadedAppRepository,
    downloaderPluginRepository: DownloaderPluginRepository,
    installedAppRepository: InstalledAppRepository,
    patchBundleRepository: PatchBundleRepository,
    patchProfileRepository: PatchProfileRepository,
    patcherRuntimePluginRepository: PatcherRuntimePluginRepository,
    lsposedRepository: LsposedRepository,
    keystoreManager: KeystoreManager
): Long = when (target) {
    StorageClearTarget.InternalCache -> clearStorageDirectories(context.managerStorageContext.cacheDir, context.managerStorageContext.codeCacheDir)
    StorageClearTarget.CodeCache -> clearStorageDirectories(context.managerStorageContext.codeCacheDir)
    StorageClearTarget.ApkSignerCache -> clearStorageDirectories(context.managerStorageContext.cacheDir.resolve(APK_SIGNER_CACHE_DIR))
    StorageClearTarget.InternalFiles -> measureClearedStorage(context.managerStorageContext.filesDir) {
        withContext(Dispatchers.IO) {
            val excludedFiles = buildList {
                add(context.managerStorageContext.filesDir.resolve("datastore"))
                add(context.managerStorageContext.filesDir.resolve("lsposed_modules"))
                addAll(
                    keystoreManager.signingStorageRoots()
                        .filter { root -> root.isWithin(context.managerStorageContext.filesDir) }
                )
            }
            context.managerStorageContext.filesDir.deleteContentsExcept(excludedFiles)
        }
        prefs.customBackgroundImageUri.update("")
    }
    StorageClearTarget.NoBackupFiles -> clearStorageDirectories(context.managerStorageContext.noBackupFilesDir)
    StorageClearTarget.CustomBackgrounds -> measureClearedStorage(context.managerStorageContext.filesDir.resolve("custom_background")) {
        withContext(Dispatchers.IO) {
            context.managerStorageContext.filesDir.resolve("custom_background").deleteContentsAndReturnBytes()
        }
        prefs.customBackgroundImageUri.update("")
    }
    StorageClearTarget.DownloadedApps -> measureClearedStorage(context.privateAppDir("downloaded-apps")) {
        val downloadedApps = downloadedAppRepository.getAll().first()
        if (downloadedApps.isNotEmpty()) {
            downloadedAppRepository.delete(downloadedApps)
        }
    }
    StorageClearTarget.PatchBundles -> measureClearedStorage(context.privateAppDir("patch_bundles")) {
        val sources = patchBundleRepository.sources.first()
        if (sources.isNotEmpty()) {
            patchBundleRepository.remove(*sources.toTypedArray())
        }
    }
    StorageClearTarget.SigningFiles -> measureClearedStorage(
        *keystoreManager.signingStorageRoots().toTypedArray()
    ) {
        keystoreManager.clearSigningFiles()
    }
    StorageClearTarget.DownloaderPlugins -> measureClearedStorage(context.privateAppDir("managed_downloader_plugins")) {
        downloaderPluginRepository.reload()
        downloaderPluginRepository.sourceStates.value.keys.toList().forEach { sourceId ->
            downloaderPluginRepository.removeSource(sourceId)
        }
    }
    StorageClearTarget.PatcherRuntimePlugins -> measureClearedStorage(
        context.privateAppDir("managed_patcher_runtime_plugins")
    ) {
        patcherRuntimePluginRepository.clearManagedSources()
    }
    StorageClearTarget.LsposedModules -> measureClearedStorage(context.managerStorageContext.filesDir.resolve("lsposed_modules")) {
        lsposedRepository.clearStoredLocalModules()
    }
    StorageClearTarget.PatchedApps -> measureClearedStorage(context.privateAppDir("patched-apps")) {
        val savedApps = installedAppRepository.getByInstallType(InstallType.SAVED)
        savedApps.forEach { app ->
            installedAppRepository.delete(app)
        }
        prefs.savedAppLauncherShortcutPackages.update(emptySet())
        withContext(Dispatchers.IO) {
            context.privateAppDir("patched-apps").deleteContentsAndReturnBytes()
        }
    }
    StorageClearTarget.RetainedOriginals -> clearStorageDirectories(
        context.privateAppDir("original-apps")
    )
    StorageClearTarget.RepatchInputs -> measureClearedStorage(
        context.privateAppDir("repatch-inputs")
    ) {
        installedAppRepository.clearRepatchInputs()
    }
    StorageClearTarget.PatchProfileInputs -> measureClearedStorage(context.privateAppDir("patch-profile-inputs")) {
        patchProfileRepository.profilesFlow().first()
            .filter { it.apkPath != null }
            .forEach { profile ->
                File(profile.apkPath!!).delete()
                patchProfileRepository.updateProfileApk(
                    uid = profile.uid,
                    apkPath = null,
                    apkVersion = null,
                    apkSourcePath = null,
                    appVersion = if (profile.useSelectedApkVersion) null else profile.appVersion,
                    useSelectedApkVersion = false
                )
            }
        withContext(Dispatchers.IO) {
            context.privateAppDir("patch-profile-inputs").deleteContentsAndReturnBytes()
        }
    }
    StorageClearTarget.TemporaryWorkspace -> clearStorageDirectories(
        context.privateAppDir("ephemeral"),
        context.privateAppDir("ui_ephemeral")
    )
    StorageClearTarget.UiTemporaryWorkspace -> clearStorageDirectories(context.privateAppDir("ui_ephemeral"))
    StorageClearTarget.ExternalCache -> clearStorageDirectories(context.managerStorageContext.externalCacheDirs.filterNotNull())
    StorageClearTarget.ExternalFiles -> clearStorageDirectoriesExcept(
        directories = context.managerStorageContext.getExternalFilesDirs(null).filterNotNull(),
        excludedFiles = keystoreManager.signingStorageRoots()
    )
}

private suspend fun clearStorageDirectories(vararg directories: File): Long =
    clearStorageDirectories(directories.toList())

private suspend fun clearStorageDirectories(directories: List<File>): Long = withContext(Dispatchers.IO) {
    directories.sumOf { it.deleteContentsAndReturnBytes() }
}

private suspend fun clearStorageDirectoriesExcept(
    directories: List<File>,
    excludedFiles: Collection<File>
): Long = withContext(Dispatchers.IO) {
    directories.sumOf { directory ->
        directory.deleteContentsExcept(
            excludedFiles.filter { excluded -> excluded.isWithin(directory) }
        )
    }
}

private suspend fun measureClearedStorage(
    vararg directories: File,
    clear: suspend () -> Unit
): Long {
    val before = withContext(Dispatchers.IO) { directories.toList().combinedStats().bytes }
    clear()
    val after = withContext(Dispatchers.IO) { directories.toList().combinedStats().bytes }
    return (before - after).coerceAtLeast(0L)
}

private fun Context.privateAppDir(name: String): File =
    File(managerStorageRoot, "app_$name")

private suspend fun pruneUnreferencedPatchedAppFiles(
    filesystem: Filesystem,
    installedAppRepository: InstalledAppRepository
) {
    val installedApps = installedAppRepository.getAll().first()
    val retainedFiles = installedApps.map { installedApp ->
        filesystem.getPatchedAppFile(
            installedApp.currentPackageName,
            installedApp.version
        )
    }
    filesystem.prunePatchedAppFiles(retainedFiles)
    installedAppRepository.pruneRetainedOriginals()
}

internal fun retainedOriginalPackageName(installedApp: InstalledApp): String =
    installedApp.originalPackageName.takeIf(String::isNotBlank)
        ?: savedAppBasePackage(installedApp.currentPackageName)

private fun List<File>.combinedStats(): DirectoryStats =
    fold(DirectoryStats()) { total, file -> total + file.directoryStats() }

private fun File?.directoryStats(): DirectoryStats {
    if (this == null || !exists()) return DirectoryStats()
    if (isFile) return DirectoryStats(bytes = length(), fileCount = 1L)

    var bytes = 0L
    var fileCount = 0L
    var directoryCount = 0L
    listFiles().orEmpty().forEach { child ->
        val childStats = child.directoryStats()
        bytes += childStats.bytes
        fileCount += childStats.fileCount
        directoryCount += childStats.directoryCount + if (child.isDirectory) 1L else 0L
    }
    return DirectoryStats(
        bytes = bytes,
        fileCount = fileCount,
        directoryCount = directoryCount
    )
}

private fun File.deleteContentsAndReturnBytes(): Long {
    if (!exists()) return 0L
    if (isFile) {
        val bytes = length()
        val deleted = runCatching { delete() }.getOrDefault(false)
        return if (deleted) bytes else 0L
    }
    if (!isDirectory) return 0L
    return clearableStorageChildren().sumOf { child ->
        val bytes = child.directoryStats().bytes
        val deleted = runCatching { child.deleteRecursively() }.getOrDefault(false)
        if (deleted) bytes else 0L
    }
}

private fun File.deleteContentsExcept(vararg excludedFiles: File): Long =
    deleteContentsExcept(excludedFiles.toList())

private fun File.deleteContentsExcept(excludedFiles: Collection<File>): Long {
    if (!exists() || !isDirectory) return 0L
    return clearableStorageChildren(excludedFiles).sumOf { child ->
        val bytes = child.directoryStats().bytes
        val deleted = runCatching { child.deleteRecursively() }.getOrDefault(false)
        if (deleted) bytes else 0L
    }
}

/** Use the same exclusions for the Clear button and the actual deletion. */
private fun File.clearableStorageBytes(excludedFiles: Collection<File> = emptyList()): Long =
    clearableStorageChildren(excludedFiles).sumOf { it.directoryStats().bytes }

private fun File.clearableStorageChildren(excludedFiles: Collection<File> = emptyList()): List<File> {
    val excludedPaths = excludedFiles.mapTo(mutableSetOf()) { it.canonicalStoragePath() }
    return listFiles().orEmpty().filterNot { child ->
        val childPath = child.canonicalStoragePath()
        child.name == "pr_profile" || child.name == "app_pr_profile" ||
            excludedPaths.any { excludedPath ->
                excludedPath == childPath || excludedPath.startsWith(childPath + File.separator)
            }
    }
}

private fun File.canonicalStoragePath(): String =
    runCatching { canonicalFile }.getOrDefault(absoluteFile).absolutePath

private fun File.isWithin(directory: File): Boolean =
    runCatching {
        val filePath = canonicalFile.path
        val directoryPath = directory.canonicalFile.path
        filePath == directoryPath || filePath.startsWith(directoryPath + File.separator)
    }.getOrDefault(false)

private fun Context.openAppStorageSettings(): Boolean {
    val uri = Uri.fromParts("package", packageName, null)
    val intent = Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)
    return try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
