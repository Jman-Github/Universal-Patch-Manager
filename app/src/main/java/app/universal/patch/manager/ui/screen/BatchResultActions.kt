package app.universal.patch.manager.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.universal.patch.manager.R
import app.universal.patch.manager.data.platform.Filesystem
import app.universal.patch.manager.domain.batch.BatchInstallOutcome
import app.universal.patch.manager.domain.batch.canInstallBatchItem
import app.universal.patch.manager.domain.batch.BatchItemState
import app.universal.patch.manager.domain.batch.BatchPatchItem
import app.universal.patch.manager.domain.installer.InstallerManager
import app.universal.patch.manager.domain.installer.installerTokenSelectableForPatchedOutput
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.ui.component.ConfirmDialog
import app.universal.patch.manager.ui.component.RememberedCreateDocument
import app.universal.patch.manager.ui.component.TransparentLoadingDialog
import app.universal.patch.manager.ui.component.patcher.InstallerPickerDialog
import app.universal.patch.manager.ui.component.patches.PathSelectorDialog
import app.universal.patch.manager.ui.component.toPickerDirectoryUri
import app.universal.patch.manager.ui.viewmodel.BatchPatcherViewModel
import app.universal.patch.manager.util.ExportNameFormatter
import app.universal.patch.manager.util.FilenameUtils
import app.universal.patch.manager.patcher.split.SplitApkPreparer
import app.universal.patch.manager.util.PatchedAppExportData
import app.universal.patch.manager.util.isAllowedApkFile
import app.universal.patch.manager.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.nio.file.Files

internal data class BatchResultActionCallbacks(
    val exportApk: () -> Unit,
    val showLogs: () -> Unit,
    val installOrOpen: () -> Unit
)

@Composable
internal fun rememberBatchResultActions(
    item: BatchPatchItem?,
    viewModel: BatchPatcherViewModel
): BatchResultActionCallbacks {
    val context = LocalContext.current
    val currentInstallerPickerItemKey by rememberUpdatedState(
        item?.let { current ->
            Triple(current.key, current.patchedFile?.absolutePath, current.useMount)
        }
    )
    val prefs: PreferencesManager = koinInject()
    val fs: Filesystem = koinInject()
    val installerManager: InstallerManager = koinInject()
    val exportFormat by prefs.patchedAppExportFormat.getAsState()
    val chooseInstallerPerInstall by prefs.chooseInstallerPerInstall.getAsState()
    val useCustomFilePicker by prefs.useCustomFilePicker.getAsState()
    val patchedApkExportDirectory by prefs.patchedApkExportLastDirectory.getAsState()
    val patcherLogExportDirectory by prefs.patcherLogExportLastDirectory.getAsState()
    val pickerScope = rememberCoroutineScope()
    val storageRoots = remember { fs.storageRoots() }
    // Actions address the queue item by key; the same app can be queued more than once.
    val itemKey = item?.key.orEmpty()
    val exportData = remember(item) {
        item?.let {
            PatchedAppExportData(
                appName = it.appName,
                packageName = it.packageName,
                appVersion = it.version ?: "unspecified",
                fileExtension = if (SplitApkPreparer.isSplitArchive(it.patchedFile)) "apks" else "apk"
            )
        }
    }
    val exportFileName = remember(exportFormat, exportData) {
        exportData?.let { ExportNameFormatter.format(exportFormat, it) }
            ?: "patched-app.apk"
    }

    var showExportPicker by rememberSaveable { mutableStateOf(false) }
    var showLogExportPicker by rememberSaveable { mutableStateOf(false) }
    var showLogActionsDialog by rememberSaveable { mutableStateOf(false) }
    var showInstallerPicker by rememberSaveable { mutableStateOf(false) }
    var installerPickerSupportsRootMount by remember { mutableStateOf(false) }
    var installerPickerSupportsRootMountPackage by remember { mutableStateOf(false) }
    var installerPickerEligibilityItemKey by remember {
        mutableStateOf<Triple<String, String?, Boolean>?>(null)
    }
    var pendingPermissionPicker by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingLogExportFileName by rememberSaveable { mutableStateOf<String?>(null) }
    var exportInProgress by rememberSaveable { mutableStateOf(false) }
    var logExportInProgress by rememberSaveable { mutableStateOf(false) }
    var exportFileDialogState by remember { mutableStateOf<ExportApkDialogState?>(null) }
    var pendingExportConfirmation by remember { mutableStateOf<PendingExportConfirmation?>(null) }
    var logExportFileDialogState by remember { mutableStateOf<LogExportDialogState?>(null) }
    var pendingLogExportConfirmation by remember {
        mutableStateOf<PendingLogExportConfirmation?>(null)
    }

    val (permissionContract, permissionName) = remember { fs.permissionContract() }
    val permissionLauncher = rememberLauncherForActivityResult(permissionContract) { granted ->
        if (granted) {
            when (pendingPermissionPicker) {
                PICKER_APK -> showExportPicker = true
                PICKER_LOG -> showLogExportPicker = true
            }
        }
        pendingPermissionPicker = null
    }
    val exportDocumentLauncher = rememberLauncherForActivityResult(
        contract = RememberedCreateDocument(if (exportData?.fileExtension == "apks") "application/zip"
            else "application/vnd.android.package-archive") {
            patchedApkExportDirectory.takeIf(String::isNotBlank)?.let(Uri::parse)
        }
    ) { uri ->
        uri?.let {
            pickerScope.launch {
                prefs.patchedApkExportLastDirectory.update(it.toPickerDirectoryUri().toString())
            }
        }
        viewModel.exportPatchedAppToUri(itemKey, uri)
        showExportPicker = false
    }
    val logExportDocumentLauncher = rememberLauncherForActivityResult(
        contract = RememberedCreateDocument("text/plain") {
            patcherLogExportDirectory.takeIf(String::isNotBlank)?.let(Uri::parse)
        }
    ) { uri ->
        uri?.let {
            pickerScope.launch {
                prefs.patcherLogExportLastDirectory.update(it.toPickerDirectoryUri().toString())
            }
        }
        viewModel.exportLogsToUri(context, itemKey, uri)
        showLogExportPicker = false
        pendingLogExportFileName = null
    }

    fun openExportPicker() {
        if (item?.hasAvailablePatchedFile != true || item.saving || item.installing) return
        if (useCustomFilePicker) {
            if (fs.hasStoragePermission()) {
                showExportPicker = true
            } else {
                pendingPermissionPicker = PICKER_APK
                permissionLauncher.launch(permissionName)
            }
        } else {
            exportDocumentLauncher.launch(exportFileName)
        }
    }

    fun openLogExportPicker() {
        if (item == null) return
        pendingLogExportFileName = FilenameUtils.timestampedLogFileName("batch-${item.packageName}")
        if (useCustomFilePicker) {
            if (fs.hasStoragePermission()) {
                showLogExportPicker = true
            } else {
                pendingPermissionPicker = PICKER_LOG
                permissionLauncher.launch(permissionName)
            }
        } else {
            logExportDocumentLauncher.launch(requireNotNull(pendingLogExportFileName))
        }
    }

    suspend fun refreshInstallerPickerEligibility(requestedItem: BatchPatchItem): Boolean {
        val requestedItemKey = Triple(
            requestedItem.key,
            requestedItem.patchedFile?.absolutePath,
            requestedItem.useMount
        )
        val (supportsRootMount, supportsRootMountPackage) = withContext(Dispatchers.IO) {
            val packageCompatible = viewModel.supportsRootMountPackage(requestedItem.key)
            val modeOverrideCompatible =
                !requestedItem.useMount && viewModel.supportsRootMount(requestedItem.key)
            modeOverrideCompatible to packageCompatible
        }
        if (currentInstallerPickerItemKey != requestedItemKey) return false
        installerPickerSupportsRootMount = supportsRootMount
        installerPickerSupportsRootMountPackage = supportsRootMountPackage
        installerPickerEligibilityItemKey = requestedItemKey
        return true
    }

    LaunchedEffect(showInstallerPicker, currentInstallerPickerItemKey) {
        val currentItem = item ?: return@LaunchedEffect
        if (!showInstallerPicker || installerPickerEligibilityItemKey == currentInstallerPickerItemKey) {
            return@LaunchedEffect
        }
        refreshInstallerPickerEligibility(currentItem)
    }

    LaunchedEffect(useCustomFilePicker) {
        if (!useCustomFilePicker) {
            showExportPicker = false
            showLogExportPicker = false
            exportFileDialogState = null
            pendingExportConfirmation = null
            logExportFileDialogState = null
            pendingLogExportConfirmation = null
            pendingPermissionPicker = null
        }
    }

    if (showInstallerPicker && item != null) {
        InstallerPickerDialog(
            title = stringResource(R.string.installer_choose_for_this_install_title),
            options = installerManager.listEntries(
                target = InstallerManager.InstallTarget.PATCHER,
                includeNone = false
            ).filter { entry ->
                installerTokenSelectableForPatchedOutput(
                    token = entry.token,
                    useMount = item.useMount,
                    supportsRootMount = installerPickerSupportsRootMount
                )
            }.filterNot { entry ->
                entry.token == InstallerManager.Token.AutoSaved &&
                    !installerPickerSupportsRootMountPackage
            },
            onDismiss = { showInstallerPicker = false },
            onConfirm = { token ->
                showInstallerPicker = false
                viewModel.installWithToken(item.key, token)
            },
            onOpenShizuku = installerManager::openShizukuApp
        )
    }

    if (showLogActionsDialog) {
        PatchLogActionsDialog(
            onDismiss = { showLogActionsDialog = false },
            onCopy = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                if (clipboard != null && item != null) {
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText(
                            "Patch log",
                            viewModel.getLogContent(context, item.key)
                        )
                    )
                    context.toast(context.getString(R.string.patcher_log_copy_success))
                }
                showLogActionsDialog = false
            },
            onExport = {
                showLogActionsDialog = false
                openLogExportPicker()
            }
        )
    }

    if (showExportPicker && useCustomFilePicker) {
        PathSelectorDialog(
            roots = storageRoots,
            onSelect = { path ->
                if (path == null) showExportPicker = false
            },
            fileFilter = ::isAllowedApkFile,
            allowDirectorySelection = false,
            fileTypeLabel = ".apk",
            confirmButtonText = stringResource(R.string.save),
            onConfirm = { directory ->
                exportFileDialogState = ExportApkDialogState(directory, exportFileName)
            },
            lastDirectoryPreference = prefs.patchedApkExportLastDirectory
        )
    }

    if (showLogExportPicker && useCustomFilePicker) {
        PathSelectorDialog(
            roots = storageRoots,
            onSelect = { path ->
                if (path == null) {
                    showLogExportPicker = false
                    pendingLogExportFileName = null
                }
            },
            fileFilter = { false },
            allowDirectorySelection = true,
            fileTypeLabel = ".txt",
            confirmButtonText = stringResource(R.string.save),
            onConfirm = { directory ->
                logExportFileDialogState = LogExportDialogState(
                    directory,
                    pendingLogExportFileName
                        ?: FilenameUtils.timestampedLogFileName("batch-patcher")
                )
            },
            lastDirectoryPreference = prefs.patcherLogExportLastDirectory
        )
    }

    exportFileDialogState?.let { state ->
        ExportApkFileNameDialog(
            initialName = state.fileName,
            onDismiss = { exportFileDialogState = null },
            onConfirm = { fileName ->
                val trimmedName = fileName.trim()
                if (trimmedName.isBlank()) return@ExportApkFileNameDialog
                exportFileDialogState = null
                val target = state.directory.resolve(trimmedName)
                if (Files.exists(target)) {
                    pendingExportConfirmation = PendingExportConfirmation(
                        state.directory,
                        trimmedName
                    )
                } else {
                    exportInProgress = true
                    viewModel.exportPatchedAppToPath(itemKey, target) { success ->
                        exportInProgress = false
                        if (success) showExportPicker = false
                    }
                }
            }
        )
    }

    pendingExportConfirmation?.let { state ->
        ConfirmDialog(
            onDismiss = {
                pendingExportConfirmation = null
                exportFileDialogState = ExportApkDialogState(state.directory, state.fileName)
            },
            onConfirm = {
                pendingExportConfirmation = null
                exportInProgress = true
                viewModel.exportPatchedAppToPath(
                    itemKey,
                    state.directory.resolve(state.fileName)
                ) { success ->
                    exportInProgress = false
                    if (success) showExportPicker = false
                }
            },
            title = stringResource(R.string.export_overwrite_title),
            description = stringResource(R.string.export_overwrite_description, state.fileName),
            icon = Icons.Outlined.WarningAmber
        )
    }

    logExportFileDialogState?.let { state ->
        ExportLogFileNameDialog(
            initialName = state.fileName,
            onDismiss = {
                logExportFileDialogState = null
                pendingLogExportFileName = null
            },
            onConfirm = { fileName ->
                val trimmedName = fileName.trim()
                if (trimmedName.isBlank()) return@ExportLogFileNameDialog
                logExportFileDialogState = null
                pendingLogExportFileName = null
                val target = state.directory.resolve(trimmedName)
                if (Files.exists(target)) {
                    pendingLogExportConfirmation = PendingLogExportConfirmation(
                        state.directory,
                        trimmedName
                    )
                } else {
                    logExportInProgress = true
                    viewModel.exportLogsToPath(context, itemKey, target) { success ->
                        logExportInProgress = false
                        if (success) showLogExportPicker = false
                    }
                }
            }
        )
    }

    pendingLogExportConfirmation?.let { state ->
        ConfirmDialog(
            onDismiss = {
                pendingLogExportConfirmation = null
                logExportFileDialogState = LogExportDialogState(state.directory, state.fileName)
            },
            onConfirm = {
                pendingLogExportConfirmation = null
                logExportInProgress = true
                viewModel.exportLogsToPath(
                    context,
                    itemKey,
                    state.directory.resolve(state.fileName)
                ) { success ->
                    logExportInProgress = false
                    if (success) showLogExportPicker = false
                }
            },
            title = stringResource(R.string.export_overwrite_title),
            description = stringResource(R.string.export_overwrite_description, state.fileName),
            icon = Icons.Outlined.WarningAmber
        )
    }

    if (exportInProgress || logExportInProgress) {
        TransparentLoadingDialog()
    }

    return BatchResultActionCallbacks(
        exportApk = ::openExportPicker,
        showLogs = {
            if (
                item?.state == BatchItemState.SUCCEEDED ||
                item?.state == BatchItemState.FAILED
            ) {
                showLogActionsDialog = true
            }
        },
        installOrOpen = {
            when {
                item == null || item.saving -> Unit
                item.installing &&
                    viewModel.state.value?.activeInstallKey == item.key ->
                    viewModel.cancelInstall()
                item.installing -> Unit
                item.installOutcome == BatchInstallOutcome.INSTALLED ->
                    viewModel.open(item.key)
                viewModel.state.value?.canInstallBatchItem(item) != true -> Unit
                item.hasAvailablePatchedFile && chooseInstallerPerInstall -> {
                    pickerScope.launch {
                        if (refreshInstallerPickerEligibility(item)) {
                            showInstallerPicker = true
                        }
                    }
                }
                item.hasAvailablePatchedFile -> viewModel.install(item.key)
            }
        }
    )
}


private const val PICKER_APK = "apk"
private const val PICKER_LOG = "log"
