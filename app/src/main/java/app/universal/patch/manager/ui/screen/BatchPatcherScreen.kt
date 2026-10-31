/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.universal.patch.manager.ui.screen

import app.universal.patch.manager.ui.component.minimalActionButtons
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.PostAdd
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.universal.patch.manager.R
import app.universal.patch.manager.data.platform.Filesystem
import app.universal.patch.manager.domain.batch.BatchInstallOutcome
import app.universal.patch.manager.domain.batch.BatchInstallPolicy
import app.universal.patch.manager.domain.batch.canInstallBatchItem
import app.universal.patch.manager.domain.batch.BatchItemState
import app.universal.patch.manager.domain.batch.BatchPatchItem
import app.universal.patch.manager.domain.batch.BatchPhase
import app.universal.patch.manager.domain.batch.BatchRunState
import app.universal.patch.manager.domain.batch.canStartBatchPatch
import app.universal.patch.manager.domain.installer.shouldUseConfiguredInstallerWithoutPrompt
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.ui.component.KeepScreenOn
import app.universal.patch.manager.ui.component.AppIcon
import app.universal.patch.manager.ui.component.AppLabel
import app.universal.patch.manager.ui.component.AppScaffold
import app.universal.patch.manager.ui.component.AppTopBar
import app.universal.patch.manager.ui.component.CenteredDialogTitle
import app.universal.patch.manager.ui.component.LazyColumnWithScrollbar
import app.universal.patch.manager.ui.component.appVersionLabel
import app.universal.patch.manager.ui.component.ConfirmDialog
import app.universal.patch.manager.ui.component.InterceptBackHandler
import app.universal.patch.manager.ui.component.RememberedGetContent
import app.universal.patch.manager.ui.component.TransparentLoadingDialog
import app.universal.patch.manager.ui.component.patches.PathSelectorDialog
import app.universal.patch.manager.ui.component.toPickerDirectoryUri
import app.universal.patch.manager.ui.model.BatchResultActionKey
import app.universal.patch.manager.ui.model.SelectedApp
import app.universal.patch.manager.ui.model.navigation.SelectedApplicationInfo
import app.universal.patch.manager.ui.viewmodel.BatchPatcherViewModel
import app.universal.patch.manager.util.EventEffect
import app.universal.patch.manager.util.consumeHorizontalScroll
import app.universal.patch.manager.util.isAllowedApkFile
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.io.File

// Code adapted from Morphe, see third-party/NOTICE for more information
// https://github.com/MorpheApp/morphe-manager/pull/795
@Composable
fun BatchPatcherHostEffects(viewModel: BatchPatcherViewModel) {
    val activityLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
        viewModel::handlePluginActivityResult
    )
    EventEffect(flow = viewModel.launchActivityFlow) { intent ->
        runCatching { activityLauncher.launch(intent) }
            .onFailure { error ->
                viewModel.handleActivityLaunchFailure(intent, error)
            }
    }

    viewModel.rootDowngradeRequest?.let { request ->
        ConfirmDialog(
            onDismiss = viewModel::dismissRootDowngrade,
            onConfirm = viewModel::confirmRootDowngrade,
            title = stringResource(R.string.root_mount_downgrade_title),
            description = request.appName + "\n\n" + request.reason,
            icon = Icons.Outlined.Warning
        )
    }

    viewModel.fallbackInstallRequest?.let { request ->
        AlertDialog(
            onDismissRequest = viewModel::dismissFallbackInstall,
            title = {
                CenteredDialogTitle(
                    stringResource(R.string.installer_fallback_prompt_title)
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(
                            R.string.installer_fallback_prompt_failure_label
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = request.failureMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(
                        text = stringResource(
                            R.string.installer_fallback_prompt_fallback_label,
                            request.fallbackLabel
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmFallbackInstall) {
                    Text(stringResource(R.string.installer_use_fallback))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissFallbackInstall) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchPatcherScreen(
    packageNames: List<String>,
    startImmediately: Boolean,
    showExistingResult: Boolean,
    manualQueue: Boolean = false,
    scheduled: Boolean = false,
    requestId: String,
    onBackClick: () -> Unit,
    onOpenProgress: (String) -> Unit,
    onEditPatches: (SelectedApplicationInfo.PatchesSelector.ViewModelParams) -> Unit,
    viewModel: BatchPatcherViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()
    val plugins by viewModel.plugins.collectAsState(initial = emptyList())
    val downloadedApps by viewModel.downloadedApps.collectAsState(initial = emptyList())
    val prefs: PreferencesManager = koinInject()
    val fs: Filesystem = koinInject()
    val useCustomFilePicker by prefs.useCustomFilePicker.getAsState()
    val selectedAppApkInputDirectory by prefs.selectedAppApkInputLastDirectory.getAsState()
    val batchResultActionOrderPref by prefs.batchResultActionOrder.getAsState()
    val batchResultHiddenActions by prefs.batchResultHiddenActions.getAsState()
    val chooseInstallerPerInstall by prefs.chooseInstallerPerInstall.getAsState()
    val batchResultActionOrder = remember(batchResultActionOrderPref) {
        val parsed = batchResultActionOrderPref
            .split(',')
            .mapNotNull { BatchResultActionKey.fromStorageId(it.trim()) }
        BatchResultActionKey.ensureComplete(parsed)
    }
    val storageRoots = remember { fs.storageRoots() }
    val pickerScope = rememberCoroutineScope()
    var showStorageDialog by rememberSaveable { mutableStateOf(false) }
    var showCancelConfirmation by rememberSaveable { mutableStateOf(false) }
    var showReorderDialog by rememberSaveable { mutableStateOf(false) }
    var showSaveBeforeLeaving by rememberSaveable { mutableStateOf(false) }
    var resultActionPackage by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingResultAction by rememberSaveable { mutableStateOf<String?>(null) }

    val (permissionContract, permissionName) = remember { fs.permissionContract() }
    val permissionLauncher = rememberLauncherForActivityResult(permissionContract) { granted ->
        if (granted) showStorageDialog = true
    }
    val attachPicker = rememberLauncherForActivityResult(
        RememberedGetContent {
            selectedAppApkInputDirectory.takeIf(String::isNotBlank)?.let(Uri::parse)
        }
    ) { uri ->
        if (uri != null) {
            pickerScope.launch {
                prefs.selectedAppApkInputLastDirectory.update(
                    uri.toPickerDirectoryUri().toString()
                )
            }
        }
        viewModel.onApkPicked(uri)
    }
    val openStoragePicker = {
        if (useCustomFilePicker) {
            if (fs.hasStoragePermission()) {
                showStorageDialog = true
            } else {
                permissionLauncher.launch(permissionName)
            }
        } else {
            attachPicker.launch("application/*")
        }
    }
    LaunchedEffect(useCustomFilePicker) {
        if (!useCustomFilePicker) showStorageDialog = false
    }
    EventEffect(flow = viewModel.requestStorageSelection) {
        openStoragePicker()
    }

    // Code adapted from Morphe, see third-party/NOTICE for more information
    // https://github.com/MorpheApp/morphe-manager/commit/974e5723cd960526cd618cca79d9f31d3299221d
    KeepScreenOn(state?.phase in setOf(BatchPhase.RUNNING, BatchPhase.INSTALLING, BatchPhase.CANCELLING))

    val phase = state?.phase
    val showLoadingOverlay = state == null ||
        phase == BatchPhase.PLANNING ||
        phase == BatchPhase.CANCELLING
    val isActive = phase == BatchPhase.PLANNING ||
        phase == BatchPhase.RUNNING ||
        phase == BatchPhase.INSTALLING
    val resultActionItem = state?.items?.firstOrNull {
        it.packageName == resultActionPackage
    }
    val resultActions = rememberBatchResultActions(resultActionItem, viewModel)
    LaunchedEffect(resultActionItem?.packageName, pendingResultAction) {
        when (pendingResultAction) {
            RESULT_ACTION_EXPORT -> resultActions.exportApk()
            RESULT_ACTION_LOGS -> resultActions.showLogs()
            RESULT_ACTION_INSTALL -> resultActions.installOrOpen()
        }
        if (resultActionItem != null) pendingResultAction = null
    }
    val requestBack = {
        when {
            phase == BatchPhase.CANCELLING -> Unit
            isActive -> showCancelConfirmation = true
            state?.unsavedPatchedItems?.isNotEmpty() == true -> showSaveBeforeLeaving = true
            else -> viewModel.leave(onBackClick)
        }
    }

    InterceptBackHandler(onBack = requestBack)

    LaunchedEffect(
        packageNames,
        startImmediately,
        showExistingResult,
        manualQueue,
        scheduled,
        requestId
    ) {
        if (manualQueue) {
            viewModel.ensureManualPlan(
                packageNames = packageNames,
                startImmediately = startImmediately,
                requestId = requestId
            )
        } else {
            viewModel.ensurePlan(
                packageNames = packageNames,
                startImmediately = startImmediately,
                scheduled = scheduled,
                showExistingResult = showExistingResult,
                requestId = requestId
            )
        }
    }
    AppScaffold(
        topBar = { scrollBehavior ->
            AppTopBar(
                title = stringResource(
                    if (manualQueue) R.string.batch_queue_title else R.string.batch_patch_title
                ),
                onBackClick = requestBack,
                actions = {
                    IconButton(
                        onClick = { showReorderDialog = true },
                        enabled = state?.phase == BatchPhase.PREFLIGHT &&
                            state?.items.orEmpty().size > 1
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Sort,
                            contentDescription = stringResource(R.string.batch_patch_reorder)
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        val current = state
        if (!showLoadingOverlay && current != null) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = 8.dp,
                    end = 16.dp,
                    bottom = 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    BatchHeader(
                        state = current,
                        allowBulkInstall = shouldUseConfiguredInstallerWithoutPrompt(
                            chooseInstallerPerInstall
                        ),
                        onPolicyChange = viewModel::setPolicy,
                        onStart = viewModel::start,
                        onCancel = { showCancelConfirmation = true },
                        onCancelInstall = viewModel::cancelInstall,
                        onInstallAll = viewModel::installAll,
                        onRetry = viewModel::retryFailed
                    )
                }
                items(current.items, key = { it.packageName }) { item ->
                    val isActiveInstall = item.installing &&
                        current.activeInstallPackageName == item.packageName
                    BatchItemCard(
                        item = item,
                        phase = current.phase,
                        isActiveInstall = isActiveInstall,
                        canInstall = current.canInstallBatchItem(item),
                        resultActionOrder = batchResultActionOrder,
                        hiddenResultActions = batchResultHiddenActions,
                        onToggle = { viewModel.toggleExcluded(item.packageName) },
                        onAttach = {
                            viewModel.requestAttach(item.packageName)
                        },
                        onForce = { viewModel.forceVersion(item.packageName) },
                        onEdit = {
                            item.input?.let { input ->
                                onEditPatches(
                                    SelectedApplicationInfo.PatchesSelector.ViewModelParams(
                                        app = input,
                                        currentSelection = item.selection,
                                        options = item.options,
                                        preferredAppVersion = item.version,
                                        preferredBundleUid = item.selection.keys.firstOrNull(),
                                        useMount = item.useMount,
                                    )
                                )
                            }
                        },
                        onOpenProgress = { onOpenProgress(item.packageName) },
                        onExport = {
                            resultActionPackage = item.packageName
                            pendingResultAction = RESULT_ACTION_EXPORT
                        },
                        onLogs = {
                            resultActionPackage = item.packageName
                            pendingResultAction = RESULT_ACTION_LOGS
                        },
                        onInstallOrOpen = {
                            if (isActiveInstall) {
                                viewModel.cancelInstall()
                            } else {
                                resultActionPackage = item.packageName
                                pendingResultAction = RESULT_ACTION_INSTALL
                            }
                        }
                    )
                }
            }
        }
    }

    if (showLoadingOverlay) {
        TransparentLoadingDialog()
    }

    if (showStorageDialog && useCustomFilePicker) {
        PathSelectorDialog(
            roots = storageRoots,
            onSelect = { path ->
                showStorageDialog = false
                viewModel.onApkFilePicked(path?.let { File(it.toString()) })
            },
            fileFilter = ::isAllowedApkFile,
            allowDirectorySelection = false,
            lastDirectoryPreference = prefs.selectedAppApkInputLastDirectory
        )
    }

    viewModel.attachTarget?.let { packageName ->
        val targetItem = state?.items?.firstOrNull { it.packageName == packageName }
        AppSourceSelectorDialog(
            plugins = plugins,
            installedApp = null,
            searchApp = SelectedApp.Search(packageName, targetItem?.version),
            activeSearchJob = viewModel.activePluginAction,
            hasRoot = false,
            downloadedApps = downloadedApps
                .filter { it.packageName == packageName }
                .sortedByDescending { it.lastUsed },
            includeAutoOption = false,
            requiredVersion = null,
            onDismissRequest = viewModel::dismissAttachSelector,
            onSelectPlugin = viewModel::searchUsingPlugin,
            onSelectDownloaded = viewModel::selectDownloadedApp,
            onSelectLocal = viewModel::requestLocalSelection,
            onSelect = {}
        )
    }

    if (showReorderDialog) {
        state?.takeIf { it.phase == BatchPhase.PREFLIGHT }?.let { current ->
            BatchOrderDialog(
                items = current.items,
                onDismissRequest = { showReorderDialog = false },
                onConfirm = { ordered ->
                    viewModel.reorder(ordered.map { it.packageName })
                    showReorderDialog = false
                }
            )
        }
    }

    if (showSaveBeforeLeaving) {
        SaveBatchPatchedAppsDialog(
            count = state?.unsavedPatchedItems.orEmpty().size,
            saving = state?.items.orEmpty().any { it.saving },
            onDismiss = { showSaveBeforeLeaving = false },
            onLeave = {
                showSaveBeforeLeaving = false
                viewModel.leave(onBackClick)
            },
            onSave = {
                viewModel.saveAllPatchedAppsForLater { success ->
                    if (success) {
                        showSaveBeforeLeaving = false
                        viewModel.leave(onBackClick)
                    }
                }
            }
        )
    }

    if (showCancelConfirmation) {
        ConfirmDialog(
            onDismiss = { showCancelConfirmation = false },
            onConfirm = {
                showCancelConfirmation = false
                viewModel.cancelAndLeave(onBackClick)
            },
            title = stringResource(R.string.batch_patch_cancel_title),
            description = stringResource(R.string.batch_patch_cancel_description),
            icon = Icons.Outlined.Cancel,
            confirmLabelRes = R.string.batch_patch_cancel_confirm
        )
    }
}

@Composable
private fun BatchOrderDialog(
    items: List<BatchPatchItem>,
    onDismissRequest: () -> Unit,
    onConfirm: (List<BatchPatchItem>) -> Unit
) {
    val workingOrder = remember(items) { items.toMutableStateList() }
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        workingOrder.add(to.index, workingOrder.removeAt(from.index))
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = { onConfirm(workingOrder.toList()) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel))
            }
        },
        title = {
            CenteredDialogTitle(text = stringResource(R.string.batch_patch_reorder_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.batch_patch_reorder_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyColumnWithScrollbar(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    state = lazyListState
                ) {
                    itemsIndexed(
                        workingOrder,
                        key = { _, item -> item.packageName }
                    ) { index, item ->
                        val interactionSource = remember { MutableInteractionSource() }
                        ReorderableItem(reorderableState, key = item.packageName) {
                            BatchOrderRow(index, item, interactionSource)
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun ReorderableCollectionItemScope.BatchOrderRow(
    index: Int,
    item: BatchPatchItem,
    interactionSource: MutableInteractionSource
) {
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
            Text(
                text = item.appName,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                softWrap = false
            )
            if (!item.appName.equals(item.packageName, ignoreCase = true)) {
                Text(
                    text = item.packageName,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false
                )
            }
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

@Composable
private fun BatchHeader(
    state: BatchRunState,
    allowBulkInstall: Boolean,
    onPolicyChange: (BatchInstallPolicy) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onCancelInstall: () -> Unit,
    onInstallAll: () -> Unit,
    onRetry: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (state.phase) {
                BatchPhase.PREFLIGHT -> PreflightHeader(
                    state = state,
                    allowBulkInstall = allowBulkInstall,
                    onPolicyChange = onPolicyChange,
                    onStart = onStart
                )

                BatchPhase.RUNNING -> {
                    val activeName = state.activeItem?.appName
                    Text(
                        activeName?.let {
                            stringResource(R.string.batch_patch_patching_app, it)
                        } ?: stringResource(R.string.batch_patch_state_running),
                        style = MaterialTheme.typography.titleMedium
                    )
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                }

                BatchPhase.INSTALLING -> {
                    val activeName = state.activeItem?.appName
                    Text(
                        activeName?.let {
                            stringResource(R.string.batch_patch_installing_app, it)
                        } ?: stringResource(R.string.batch_patch_installing),
                        style = MaterialTheme.typography.titleMedium
                    )
                    OutlinedButton(
                        onClick = onCancelInstall,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                }

                BatchPhase.CANCELLING -> Unit

                BatchPhase.FINISHED -> FinishedHeader(
                    state = state,
                    allowBulkInstall = allowBulkInstall,
                    onInstallAll = onInstallAll,
                    onRetry = onRetry
                )

                BatchPhase.PLANNING -> Unit
            }
        }
    }
}

@Composable
private fun PreflightHeader(
    state: BatchRunState,
    allowBulkInstall: Boolean,
    onPolicyChange: (BatchInstallPolicy) -> Unit,
    onStart: () -> Unit
) {
    Text(
        pluralStringResource(
            R.plurals.batch_patch_ready_summary,
            state.runnable.size,
            state.runnable.size,
            state.items.size
        ),
        style = MaterialTheme.typography.titleMedium
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.batch_patch_install_after))
            Text(
                stringResource(R.string.batch_patch_install_after_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = allowBulkInstall && state.policy == BatchInstallPolicy.INSTALL_AFTER,
            enabled = allowBulkInstall,
            onCheckedChange = {
                onPolicyChange(
                    if (it) BatchInstallPolicy.INSTALL_AFTER
                    else BatchInstallPolicy.SAVE_ONLY
                )
            }
        )
    }
    Button(
        onClick = onStart,
        enabled = state.canStartBatchPatch(),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            pluralStringResource(
                R.plurals.batch_patch_start_count,
                state.runnable.size,
                state.runnable.size
            )
        )
    }
}

@Composable
private fun FinishedHeader(
    state: BatchRunState,
    allowBulkInstall: Boolean,
    onInstallAll: () -> Unit,
    onRetry: () -> Unit
) {
    Text(
        stringResource(
            R.string.batch_patch_finished_summary,
            state.succeeded,
            state.failed,
            state.skipped
        ),
        style = MaterialTheme.typography.titleMedium
    )
    if (
        allowBulkInstall &&
        state.patchedItems.any { it.installOutcome != BatchInstallOutcome.INSTALLED }
    ) {
        Button(onClick = onInstallAll, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.batch_patch_install_all))
        }
    }
    if (state.items.any {
            it.state == BatchItemState.FAILED || it.state == BatchItemState.CANCELLED
        }
    ) {
        OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.retry))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BatchItemCard(
    item: BatchPatchItem,
    phase: BatchPhase,
    isActiveInstall: Boolean,
    canInstall: Boolean,
    resultActionOrder: List<BatchResultActionKey>,
    hiddenResultActions: Set<String>,
    onToggle: () -> Unit,
    onAttach: () -> Unit,
    onForce: () -> Unit,
    onEdit: () -> Unit,
    onOpenProgress: () -> Unit,
    onExport: () -> Unit,
    onLogs: () -> Unit,
    onInstallOrOpen: () -> Unit
) {
    val context = LocalContext.current
    val packageInfo = remember(item.packageName) {
        runCatching { context.packageManager.getPackageInfo(item.packageName, 0) }.getOrNull()
    }
    val canOpenProgress = item.hasProgressDetails
    val canUseLogs = item.state == BatchItemState.SUCCEEDED ||
        item.state == BatchItemState.FAILED
    val canEdit = phase == BatchPhase.PREFLIGHT &&
        item.state != BatchItemState.EXCLUDED &&
        item.input != null &&
        item.bundles.isNotEmpty()
    val showPackageName = !item.appName.equals(item.packageName, ignoreCase = true)
    val showStatus = phase != BatchPhase.PREFLIGHT || item.state != BatchItemState.READY
    val displayVersion = item.version?.takeIf(String::isNotBlank)
        ?: item.versionCode?.toString()
    val hasVersion = displayVersion != null
    val hasPatchCount = item.patchCount > 0

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (phase == BatchPhase.PREFLIGHT) {
                    Checkbox(
                        checked = item.state != BatchItemState.EXCLUDED,
                        onCheckedChange = { onToggle() }
                    )
                }
                AppIcon(
                    packageInfo = packageInfo,
                    packageName = item.packageName,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp)
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    AppLabel(
                        packageInfo = packageInfo,
                        labelOverride = item.appName.takeIf { showPackageName },
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        defaultText = item.packageName,
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (showPackageName) {
                        Text(
                            text = item.packageName,
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    if (hasVersion || hasPatchCount) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            if (hasVersion) {
                                Text(
                                    text = item.version?.takeIf(String::isNotBlank)?.let { version ->
                                        appVersionLabel(
                                            versionName = version,
                                            appInfo = packageInfo,
                                            versionCodeOverride = item.versionCode
                                        )
                                    } ?: requireNotNull(displayVersion),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (hasPatchCount) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (hasVersion) {
                                        Text(
                                            text = "•",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        text = pluralStringResource(
                                            R.plurals.patch_count,
                                            item.patchCount,
                                            item.patchCount
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
                if (showStatus) {
                    BatchStatusPill(item.state)
                }
            }

            item.message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            item.installMessage?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (phase == BatchPhase.PREFLIGHT && item.state != BatchItemState.EXCLUDED) {
                BatchQuickActionRow {
                    if (item.state == BatchItemState.NEEDS_APK) {
                        BatchQuickActionButton(
                            onClick = onAttach,
                            icon = Icons.Outlined.AttachFile,
                            description = stringResource(R.string.batch_patch_attach_apk)
                        )
                    }
                    if (item.state == BatchItemState.VERSION_MISMATCH) {
                        BatchQuickActionButton(
                            onClick = onForce,
                            icon = Icons.Outlined.Warning,
                            description = stringResource(R.string.batch_patch_use_anyway)
                        )
                    }
                    if (canEdit) {
                        BatchQuickActionButton(
                            onClick = onEdit,
                            icon = Icons.Outlined.Tune,
                            description = stringResource(R.string.batch_patch_edit_patches)
                        )
                        BatchQuickActionButton(
                            onClick = onAttach,
                            icon = Icons.Outlined.Edit,
                            description = stringResource(R.string.batch_patch_edit_apk)
                        )
                    }
                }
            }

            val visibleResultActions = resultActionOrder.filter { action ->
                action.storageId !in hiddenResultActions && when (action) {
                    BatchResultActionKey.VIEW_PROGRESS,
                    BatchResultActionKey.SAVE_LOGS -> true
                    BatchResultActionKey.SAVE_APK -> item.hasAvailablePatchedFile
                    BatchResultActionKey.INSTALL_OR_OPEN ->
                        isActiveInstall || item.hasAvailablePatchedFile ||
                            item.installOutcome == BatchInstallOutcome.INSTALLED
                }
            }
            if (phase != BatchPhase.PREFLIGHT && visibleResultActions.isNotEmpty()) {
                BatchQuickActionRow {
                    visibleResultActions.forEach { action ->
                        when (action) {
                            BatchResultActionKey.VIEW_PROGRESS -> BatchQuickActionButton(
                                onClick = onOpenProgress,
                                enabled = canOpenProgress,
                                icon = Icons.Outlined.Visibility,
                                description = stringResource(R.string.batch_patch_view_progress)
                            )
                            BatchResultActionKey.SAVE_LOGS -> BatchQuickActionButton(
                                onClick = onLogs,
                                enabled = canUseLogs,
                                icon = Icons.Outlined.PostAdd,
                                description = stringResource(R.string.save_logs)
                            )
                            BatchResultActionKey.SAVE_APK -> BatchQuickActionButton(
                                onClick = onExport,
                                enabled = !item.saving && !item.installing,
                                icon = Icons.Outlined.Save,
                                description = stringResource(R.string.save_apk)
                            )
                            BatchResultActionKey.INSTALL_OR_OPEN -> BatchQuickActionButton(
                                onClick = onInstallOrOpen,
                                enabled = !item.saving && (isActiveInstall || canInstall ||
                                    item.installOutcome == BatchInstallOutcome.INSTALLED),
                                icon = when {
                                    isActiveInstall -> Icons.Outlined.Cancel
                                    item.installOutcome == BatchInstallOutcome.INSTALLED ->
                                        Icons.AutoMirrored.Outlined.OpenInNew
                                    else -> Icons.Outlined.FileDownload
                                },
                                description = stringResource(
                                    when {
                                        isActiveInstall -> R.string.cancel
                                        item.installOutcome == BatchInstallOutcome.INSTALLED ->
                                            R.string.open_app
                                        else -> R.string.install_app
                                    }
                                ),
                                loading = false
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BatchQuickActionRow(
    content: @Composable RowScope.() -> Unit
) {
    val actionScrollState = rememberScrollState()
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .widthIn(min = maxWidth)
                .consumeHorizontalScroll(actionScrollState),
            horizontalArrangement = Arrangement.spacedBy(
                6.dp,
                Alignment.CenterHorizontally
            ),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

@Composable
private fun BatchQuickActionButton(
    onClick: () -> Unit,
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    loading: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    val background = MaterialTheme.colorScheme.surface.copy(
        alpha = if (enabled) 0.9f else 0.5f
    )
    val contentColor = MaterialTheme.colorScheme.onSurface.copy(
        alpha = if (enabled) 1f else 0.6f
    )
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(background)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = contentColor,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = description,
                    tint = contentColor,
                    modifier = Modifier.size(14.dp)
                )
            }
            if (!minimalActionButtons("batch")) Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SaveBatchPatchedAppsDialog(
    count: Int,
    saving: Boolean,
    onDismiss: () -> Unit,
    onLeave: () -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Save, null) },
        title = {
            CenteredDialogTitle(
                stringResource(R.string.batch_save_patched_apps_dialog_title)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = stringResource(
                        R.string.batch_save_patched_apps_dialog_message,
                        count
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Outlined.Save,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                stringResource(R.string.batch_save_patched_apps_dialog_hint_save),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ExitToApp,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                stringResource(R.string.batch_save_patched_apps_dialog_hint_leave),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onSave,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(stringResource(R.string.batch_save_patched_apps_dialog_save))
                    }
                }
                FilledTonalButton(
                    onClick = onLeave,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.save_patched_app_dialog_leave))
                }
                FilledTonalButton(
                    onClick = onDismiss,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
        dismissButton = {}
    )
}

@Composable
private fun BatchStatusPill(state: BatchItemState) {
    val (containerColor, contentColor) = when (state) {
        BatchItemState.FAILED,
        BatchItemState.VERSION_MISMATCH,
        BatchItemState.NEEDS_APK,
        BatchItemState.NO_PATCHES -> MaterialTheme.colorScheme.errorContainer to
            MaterialTheme.colorScheme.onErrorContainer

        BatchItemState.RUNNING -> MaterialTheme.colorScheme.secondaryContainer to
            MaterialTheme.colorScheme.onSecondaryContainer

        BatchItemState.EXCLUDED,
        BatchItemState.CANCELLED -> MaterialTheme.colorScheme.surfaceVariant to
            MaterialTheme.colorScheme.onSurfaceVariant

        BatchItemState.READY,
        BatchItemState.SUCCEEDED -> MaterialTheme.colorScheme.primaryContainer to
            MaterialTheme.colorScheme.onPrimaryContainer
    }
    Surface(
        shape = RoundedCornerShape(100),
        color = containerColor,
        contentColor = contentColor
    ) {
        Text(
            text = batchStateLabel(state),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
private fun batchStateLabel(state: BatchItemState): String = stringResource(
    when (state) {
        BatchItemState.READY -> R.string.batch_patch_state_ready
        BatchItemState.NEEDS_APK -> R.string.batch_patch_state_needs_apk
        BatchItemState.VERSION_MISMATCH -> R.string.batch_patch_state_version_mismatch
        BatchItemState.NO_PATCHES -> R.string.batch_patch_state_no_patches
        BatchItemState.EXCLUDED -> R.string.batch_patch_state_excluded
        BatchItemState.RUNNING -> R.string.batch_patch_state_running
        BatchItemState.SUCCEEDED -> R.string.batch_patch_state_succeeded
        BatchItemState.FAILED -> R.string.batch_patch_state_failed
        BatchItemState.CANCELLED -> R.string.batch_patch_state_cancelled
    }
)


private const val RESULT_ACTION_EXPORT = "export"
private const val RESULT_ACTION_LOGS = "logs"
private const val RESULT_ACTION_INSTALL = "install"
