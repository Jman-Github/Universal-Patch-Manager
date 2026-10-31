package app.universal.patch.manager.ui.screen

import app.universal.patch.manager.ui.component.minimalActionButtons
import android.content.pm.PackageInfo
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universal.patch.manager.data.room.apps.installed.InstallType
import app.universal.patch.manager.data.room.apps.installed.InstalledApp
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.ui.component.AppIcon
import app.universal.patch.manager.ui.component.AppLabel
import app.universal.patch.manager.ui.component.LazyColumnWithScrollbar
import app.universal.patch.manager.ui.component.DASHBOARD_SCROLLBAR_THUMB_LENGTH
import app.universal.patch.manager.ui.component.ShimmerBox
import app.universal.patch.manager.ui.component.haptics.HapticCheckbox
import app.universal.patch.manager.ui.model.InstalledAppAction
import app.universal.patch.manager.ui.model.SavedAppActionKey
import app.universal.patch.manager.ui.viewmodel.InstalledAppsViewModel
import app.universal.patch.manager.ui.viewmodel.InstalledAppsViewModel.AppBundleSummary
import app.universal.patch.manager.util.consumeHorizontalScroll
import app.universal.patch.manager.util.relativeTime
import app.universal.patch.manager.util.savedAppBasePackage
import app.universal.patch.manager.util.supportsRootMount
import app.universal.patch.manager.R
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import app.universal.patch.manager.ui.component.CenteredDialogTitle

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InstalledAppsScreen(
    onAppClick: (InstalledApp) -> Unit,
    onAppAction: (InstalledApp, InstalledAppAction) -> Unit,
    viewModel: InstalledAppsViewModel = koinViewModel(),
    showOrderDialog: Boolean = false,
    onDismissOrderDialog: () -> Unit = {},
    searchQuery: String = ""
) {
    val context = LocalContext.current
    val prefs: PreferencesManager = koinInject()
    val installedApps by viewModel.apps.collectAsStateWithLifecycle(initialValue = null)
    val selectionActive = viewModel.selectedApps.isNotEmpty()
    val savedActionOrderPref by prefs.savedAppActionOrder.getAsState()
    val savedHiddenActionsPref by prefs.savedAppHiddenActions.getAsState()
    val showSavedAppBundleUpdateBadges by prefs.showSavedAppBundleUpdateBadges.getAsState()
    val savedActionOrderList = remember(savedActionOrderPref) {
        val parsed = savedActionOrderPref
            .split(',')
            .mapNotNull { SavedAppActionKey.fromStorageId(it.trim()) }
        SavedAppActionKey.ensureComplete(parsed)
    }
    val visibleSavedActionKeys = remember(savedActionOrderList, savedHiddenActionsPref) {
        savedActionOrderList.filterNot { it.storageId in savedHiddenActionsPref }
    }
    val timeTick by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(60_000)
            value = System.currentTimeMillis()
        }
    }
    val normalizedQuery = searchQuery.trim().lowercase()
    val filteredApps = installedApps?.let { apps ->
        if (normalizedQuery.isBlank()) {
            apps
        } else {
            apps.filter { app ->
                val packageName = if (app.installType == InstallType.SAVED) {
                    app.originalPackageName.takeIf { it.isNotBlank() }
                        ?: savedAppBasePackage(app.currentPackageName)
                } else {
                    app.currentPackageName
                }
                val label = viewModel.appLabelMap[app.currentPackageName].orEmpty()
                val searchText = buildString {
                    append(packageName)
                    if (packageName != app.currentPackageName) {
                        append(' ')
                        append(app.currentPackageName)
                    }
                    if (label.isNotBlank()) {
                        append(' ')
                        append(label)
                    }
                }.lowercase()
                searchText.contains(normalizedQuery)
            }
        }
    }

    when {
        installedApps == null -> {
            LazyColumnWithScrollbar(
                thumbLength = DASHBOARD_SCROLLBAR_THUMB_LENGTH,
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top,
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                items(4) {
                    InstalledAppCardPlaceholder()
                }
            }
        }

        installedApps!!.isEmpty() -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.no_patched_apps_found),
                    style = MaterialTheme.typography.titleLarge
                )
            }
        }

        filteredApps.isNullOrEmpty() && normalizedQuery.isNotBlank() -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.app_filter_no_results),
                    style = MaterialTheme.typography.titleLarge
                )
            }
        }

        else -> {
            LazyColumnWithScrollbar(
                thumbLength = DASHBOARD_SCROLLBAR_THUMB_LENGTH,
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top,
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                items(
                    filteredApps.orEmpty(),
                    key = { it.currentPackageName }
                ) { installedApp ->
                        val packageName = installedApp.currentPackageName
                        val packageInfo = viewModel.packageInfoMap[packageName]
                        val hasPackageInfo = viewModel.packageInfoMap.containsKey(packageName)
                        val supportsRootMount = installedApp.supportsRootMount(packageInfo?.packageName)
                        val isSaved = installedApp.installType == InstallType.SAVED ||
                            (installedApp.installType == InstallType.MOUNT && !supportsRootMount)
                        val isBundleMetaLoaded = !isSaved || packageName in viewModel.bundleSummaryLoaded
                        val showPlaceholder = !hasPackageInfo || !isBundleMetaLoaded
                        val isMissingInstall = packageName in viewModel.missingPackages
                        val isSelectable = true
                        val isSelected = packageName in viewModel.selectedApps
                        val isInstalledOnDevice = viewModel.installedOnDeviceMap[packageName] == true
                        val hasSavedCopy = viewModel.savedCopyMap[packageName] == true
                        val savedApkAbiLabel = viewModel.savedApkAbiLabelMap[packageName]
                        val isMounted = if (
                            installedApp.installType == InstallType.MOUNT && supportsRootMount
                        ) {
                            viewModel.mountedOnDeviceMap[packageName]
                                ?: (viewModel.installedOnDeviceMap[packageName] == true)
                        } else {
                            false
                        }
                        val bundleSummaries = viewModel.bundleSummaries[packageName].orEmpty()

                        if (showPlaceholder) {
                            InstalledAppCardPlaceholder()
                        } else {
                            InstalledAppCard(
                                installedApp = installedApp,
                                packageInfo = packageInfo,
                                appLabel = viewModel.appLabelMap[packageName],
                                customInstallerLabel = viewModel.customInstallerLabelMap[packageName],
                                isSelected = isSelected,
                                selectionActive = selectionActive,
                                isSelectable = isSelectable,
                                isMissingInstall = isMissingInstall,
                                isInstalledOnDevice = isInstalledOnDevice,
                                hasSavedCopy = hasSavedCopy,
                                savedApkAbiLabel = savedApkAbiLabel,
                                isMounted = isMounted,
                                isPlayStoreInstallerSource =
                                    viewModel.playStoreInstallerSourceMap[packageName] == true,
                                supportsRootMount = supportsRootMount,
                                bundleSummaries = bundleSummaries,
                                showBundleUpdateBadges = showSavedAppBundleUpdateBadges,
                                timeTick = timeTick,
                                savedActionKeys = visibleSavedActionKeys,
                                onClick = {
                                    when {
                                        selectionActive -> viewModel.toggleSelection(installedApp)
                                        else -> onAppClick(installedApp)
                                    }
                                },
                                onLongClick = {
                                    viewModel.toggleSelection(installedApp)
                                },
                                onSelectionChange = { checked ->
                                    viewModel.setSelection(installedApp, checked)
                                },
                                onAppAction = onAppAction
                            )
                        }
                }
            }
        }
    }

    if (showOrderDialog && installedApps != null) {
        AppsOrderDialog(
            apps = installedApps.orEmpty(),
            appInfoMap = viewModel.packageInfoMap,
            appLabelMap = viewModel.appLabelMap,
            playStoreInstallerSourceMap = viewModel.playStoreInstallerSourceMap,
            onDismissRequest = onDismissOrderDialog,
            onConfirm = { ordered ->
                viewModel.reorderApps(ordered.map { it.currentPackageName })
                onDismissOrderDialog()
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InstalledAppCard(
    installedApp: InstalledApp,
    packageInfo: PackageInfo?,
    appLabel: String?,
    customInstallerLabel: String?,
    isSelected: Boolean,
    selectionActive: Boolean,
    isSelectable: Boolean,
    isMissingInstall: Boolean,
    isInstalledOnDevice: Boolean,
    hasSavedCopy: Boolean,
    savedApkAbiLabel: String?,
    isMounted: Boolean,
    isPlayStoreInstallerSource: Boolean,
    supportsRootMount: Boolean,
    bundleSummaries: List<InstalledAppsViewModel.AppBundleSummary>,
    showBundleUpdateBadges: Boolean,
    timeTick: Long,
    savedActionKeys: List<SavedAppActionKey>,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSelectionChange: (Boolean) -> Unit,
    onAppAction: (InstalledApp, InstalledAppAction) -> Unit
) {
    val context = LocalContext.current
    val isSaved = installedApp.installType == InstallType.SAVED ||
        (installedApp.installType == InstallType.MOUNT && !supportsRootMount)
    val displayInstallType = when {
        isSaved && installedApp.installType == InstallType.MOUNT -> InstallType.SAVED
        installedApp.installType == InstallType.MOUNT && isPlayStoreInstallerSource ->
            InstallType.ROOT_PLAY_STORE
        else -> installedApp.installType
    }
    val displayPackageName = if (isSaved) {
        installedApp.originalPackageName.takeIf { it.isNotBlank() }
            ?: savedAppBasePackage(installedApp.currentPackageName)
    } else {
        installedApp.currentPackageName
    }
    val cardShape = RoundedCornerShape(18.dp)
    val elevation = if (isSelected) 6.dp else 2.dp
    val cardBase = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
    val headerBase = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp)
    val cardBackground = cardBase
    val headerBackground = headerBase
    val formattedVersion = installedApp.version
        .takeIf { it.isNotBlank() }
        ?.let(::formatVersion)
    val detailLine = listOfNotNull(
        formattedVersion,
        savedApkAbiLabel?.takeIf { hasSavedCopy }
    ).joinToString(" • ")
    val savedAtText = installedApp.createdAt
        .takeIf { it > 0 && (isSaved || hasSavedCopy) }
        ?.let { createdAt ->
            val tick = timeTick
            stringResource(R.string.saved_app_created_at, createdAt.relativeTime(context))
        }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(cardShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = cardShape,
        tonalElevation = elevation,
        color = cardBackground
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(headerBackground)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (selectionActive) {
                    HapticCheckbox(
                        checked = isSelected,
                        onCheckedChange = if (isSelectable) onSelectionChange else null,
                        enabled = isSelectable
                    )
                }
                AppIcon(
                    packageInfo = packageInfo,
                    packageName = displayPackageName,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp)
                )
                val titleScrollState = rememberScrollState()
                AppLabel(
                    packageInfo = packageInfo,
                    labelOverride = appLabel,
                    style = MaterialTheme.typography.titleMedium,
                    defaultText = displayPackageName,
                    modifier = Modifier
                        .weight(1f)
                        .consumeHorizontalScroll(titleScrollState)
                )
                val scrollInstallerLabel = displayInstallType == InstallType.CUSTOM
                AppMetaPill(
                    text = if (displayInstallType == InstallType.CUSTOM) {
                        customInstallerLabel
                            ?: stringResource(displayInstallType.stringResource)
                    } else {
                        stringResource(displayInstallType.stringResource)
                    },
                    modifier = if (scrollInstallerLabel) {
                        Modifier.widthIn(max = if (selectionActive) 112.dp else 160.dp)
                    } else {
                        Modifier
                    },
                    horizontallyScrollable = scrollInstallerLabel
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = displayPackageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (detailLine.isNotBlank()) {
                    Text(
                        text = detailLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!savedAtText.isNullOrBlank()) {
                    Text(
                        text = savedAtText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (bundleSummaries.isNotEmpty()) {
                    val bundleLine = if (bundleSummaries.size == 1) {
                        val summary = bundleSummaries.first()
                        val versionText = summary.version?.let(::formatVersion)
                        listOfNotNull(summary.title, versionText).joinToString(" • ")
                    } else {
                        pluralStringResource(
                            R.plurals.saved_app_patch_bundles_applied_quantity,
                            bundleSummaries.size,
                            bundleSummaries.size
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = bundleLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        if (showBundleUpdateBadges && bundleSummaries.any { it.hasUpdate }) {
                            AppMetaPill(
                                text = pluralStringResource(
                                    R.plurals.saved_app_bundle_update_badge_quantity,
                                    bundleSummaries.count { it.hasUpdate }
                                ),
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
                if (isMissingInstall) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusChip(
                            text = stringResource(R.string.patches_missing),
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
                val hasPatchContext = installedApp.selectionPayload != null || bundleSummaries.isNotEmpty()
                if (hasPatchContext && savedActionKeys.isNotEmpty()) {
                    val actionScrollState = rememberScrollState()
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .widthIn(min = maxWidth)
                                .consumeHorizontalScroll(actionScrollState),
                            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            savedActionKeys.forEach { key ->
                                when (key) {
                                    SavedAppActionKey.OPEN -> AppActionPill(
                                        text = stringResource(R.string.open_app),
                                        icon = Icons.AutoMirrored.Outlined.OpenInNew,
                                        enabled = isInstalledOnDevice,
                                        onClick = { onAppAction(installedApp, InstalledAppAction.OPEN) }
                                    )
                                    SavedAppActionKey.EXPORT -> AppActionPill(
                                        text = stringResource(R.string.export),
                                        icon = Icons.Outlined.Save,
                                        onClick = { onAppAction(installedApp, InstalledAppAction.EXPORT) }
                                    )
                                    SavedAppActionKey.INSTALL_UPDATE -> {
                                        if (
                                            installedApp.installType == InstallType.MOUNT &&
                                            supportsRootMount
                                        ) {
                                            val mountLabel = if (isMounted) {
                                                stringResource(R.string.root_mount_use_stock)
                                            } else {
                                                stringResource(R.string.mount)
                                            }
                                            AppActionPill(
                                                text = mountLabel,
                                                icon = if (isMounted) {
                                                    Icons.Outlined.Circle
                                                } else {
                                                    Icons.Outlined.SettingsBackupRestore
                                                },
                                                onClick = {
                                                    onAppAction(
                                                        installedApp,
                                                        if (isMounted) {
                                                            InstalledAppAction.UNINSTALL
                                                        } else {
                                                            InstalledAppAction.INSTALL_OR_UPDATE
                                                        }
                                                    )
                                                }
                                            )
                                        } else {
                                            val installLabel = if (isInstalledOnDevice) {
                                                stringResource(R.string.update_saved_app)
                                            } else {
                                                stringResource(R.string.install_saved_app)
                                            }
                                            AppActionPill(
                                                text = installLabel,
                                                icon = Icons.Outlined.InstallMobile,
                                                onClick = { onAppAction(installedApp, InstalledAppAction.INSTALL_OR_UPDATE) },
                                                onLongClick = if (isInstalledOnDevice) {
                                                    { onAppAction(installedApp, InstalledAppAction.UNINSTALL) }
                                                } else null
                                            )
                                        }
                                    }
                                    SavedAppActionKey.REPAIR_ROOT_MOUNT -> {
                                        if (
                                            installedApp.installType == InstallType.MOUNT &&
                                            supportsRootMount
                                        ) {
                                            AppActionPill(
                                                text = stringResource(R.string.root_mount_repair),
                                                icon = Icons.Outlined.SettingsBackupRestore,
                                                onClick = {
                                                    onAppAction(
                                                        installedApp,
                                                        InstalledAppAction.REPAIR_ROOT_MOUNT
                                                    )
                                                }
                                            )
                                        }
                                    }
                                    SavedAppActionKey.EXPORT_ROOT_MOUNT_DIAGNOSTICS -> {
                                        if (
                                            installedApp.installType == InstallType.MOUNT &&
                                            supportsRootMount
                                        ) {
                                            AppActionPill(
                                                text = stringResource(
                                                    R.string.root_mount_export_diagnostics
                                                ),
                                                icon = Icons.Outlined.Save,
                                                onClick = {
                                                    onAppAction(
                                                        installedApp,
                                                        InstalledAppAction.EXPORT_ROOT_MOUNT_DIAGNOSTICS
                                                    )
                                                }
                                            )
                                        }
                                    }
                                    SavedAppActionKey.DELETE -> AppActionPill(
                                        text = stringResource(R.string.delete),
                                        icon = Icons.Outlined.Delete,
                                        onClick = { onAppAction(installedApp, InstalledAppAction.DELETE) }
                                    )
                                    SavedAppActionKey.REPATCH -> AppActionPill(
                                        text = stringResource(R.string.repatch),
                                        icon = Icons.Outlined.Update,
                                        onClick = { onAppAction(installedApp, InstalledAppAction.REPATCH) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InstalledAppCardPlaceholder() {
    val cardShape = RoundedCornerShape(16.dp)
    val elevation = 2.dp
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(cardShape),
        shape = cardShape,
        tonalElevation = elevation,
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(elevation)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ShimmerBox(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(12.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ShimmerBox(modifier = Modifier.width(180.dp).height(18.dp))
                ShimmerBox(modifier = Modifier.width(140.dp).height(12.dp))
                ShimmerBox(modifier = Modifier.width(120.dp).height(12.dp))
            }
        }
    }
}

private fun formatVersion(raw: String): String =
    if (raw.startsWith("v", ignoreCase = true)) raw else "v$raw"

@Composable
private fun StatusChip(
    text: String,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = containerColor,
        contentColor = contentColor
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun AppMetaPill(
    text: String,
    modifier: Modifier = Modifier,
    horizontallyScrollable: Boolean = false,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val scrollState = rememberScrollState()
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        tonalElevation = 0.dp,
        color = containerColor
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            modifier = Modifier
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .then(
                    if (horizontallyScrollable) {
                        Modifier.consumeHorizontalScroll(scrollState)
                    } else {
                        Modifier
                    }
                ),
            maxLines = if (horizontallyScrollable) 1 else Int.MAX_VALUE,
            softWrap = !horizontallyScrollable,
            overflow = if (horizontallyScrollable) TextOverflow.Clip else TextOverflow.Visible
        )
    }
}

@Composable
private fun AppActionPill(
    text: String,
    icon: ImageVector,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val background = MaterialTheme.colorScheme.surface.copy(alpha = if (enabled) 0.9f else 0.5f)
    val contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.6f)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(background)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = text,
                tint = contentColor,
                modifier = Modifier.size(14.dp)
            )
            if (!minimalActionButtons("saved")) Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun AppsOrderDialog(
    apps: List<InstalledApp>,
    appInfoMap: Map<String, PackageInfo?>,
    appLabelMap: Map<String, String>,
    playStoreInstallerSourceMap: Map<String, Boolean>,
    onDismissRequest: () -> Unit,
    onConfirm: (List<InstalledApp>) -> Unit
) {
    val workingOrder = remember(apps) { apps.toMutableStateList() }
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        workingOrder.add(to.index, workingOrder.removeAt(from.index))
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = { onConfirm(workingOrder.toList()) }, enabled = workingOrder.isNotEmpty()) {
                Text(text = stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(R.string.cancel))
            }
        },
        title = { CenteredDialogTitle(text = stringResource(R.string.apps_reorder_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.apps_reorder_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyColumnWithScrollbar(
                    thumbLength = app.universal.patch.manager.ui.component.DASHBOARD_SCROLLBAR_THUMB_LENGTH,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    reserveScrollbarSpace = true,
                    state = lazyListState
                ) {
                    itemsIndexed(workingOrder, key = { _, app -> app.currentPackageName }) { index, app ->
                        val interactionSource = remember { MutableInteractionSource() }
                        ReorderableItem(reorderableState, key = app.currentPackageName) { _ ->
                            AppsOrderRow(
                                index = index,
                                app = app,
                                packageInfo = appInfoMap[app.currentPackageName],
                                appLabel = appLabelMap[app.currentPackageName],
                                isPlayStoreInstallerSource =
                                    playStoreInstallerSourceMap[app.currentPackageName] == true,
                                interactionSource = interactionSource
                            )
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun ReorderableCollectionItemScope.AppsOrderRow(
    index: Int,
    app: InstalledApp,
    packageInfo: PackageInfo?,
    appLabel: String?,
    isPlayStoreInstallerSource: Boolean,
    interactionSource: MutableInteractionSource
) {
    val displayPackageName = if (app.installType == InstallType.SAVED) {
        app.originalPackageName.takeIf { it.isNotBlank() }
            ?: savedAppBasePackage(app.currentPackageName)
    } else {
        app.currentPackageName
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = (index + 1).toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Column(modifier = Modifier.weight(1f)) {
            AppLabel(
                packageInfo = packageInfo,
                labelOverride = appLabel,
                style = MaterialTheme.typography.bodyLarge,
                defaultText = displayPackageName
            )
            val displayInstallType = if (
                app.installType == InstallType.MOUNT && isPlayStoreInstallerSource
            ) {
                InstallType.ROOT_PLAY_STORE
            } else {
                app.installType
            }
            val installTypeLabel = stringResource(displayInstallType.stringResource)
            Text(
                text = installTypeLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(
            onClick = {},
            interactionSource = interactionSource,
            modifier = Modifier.draggableHandle()
        ) {
            Icon(
                imageVector = Icons.Filled.DragHandle,
                contentDescription = stringResource(R.string.drag_handle)
            )
        }
    }
}

