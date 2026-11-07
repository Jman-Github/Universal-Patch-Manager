package app.universal.patch.manager.ui.screen.settings

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.documentfile.provider.DocumentFile
import app.universal.patch.manager.R
import app.universal.patch.manager.data.platform.Filesystem
import app.universal.patch.manager.domain.installer.InstallerManager
import app.universal.patch.manager.domain.manager.KeystoreManager
import app.universal.patch.manager.ui.component.AppTopBar
import app.universal.patch.manager.ui.component.ColumnWithScrollbar
import app.universal.patch.manager.ui.component.ConfirmDialog
import app.universal.patch.manager.ui.component.DownloadProgressBanner
import app.universal.patch.manager.ui.component.GroupHeader
import app.universal.patch.manager.ui.component.InterceptBackHandler
import app.universal.patch.manager.ui.component.ShimmerBox
import app.universal.patch.manager.ui.component.PasswordField
import app.universal.patch.manager.ui.component.SettingsSectionIcons
import app.universal.patch.manager.ui.component.bundle.BundleSelector
import app.universal.patch.manager.ui.component.patches.PathSelectorDialog
import app.universal.patch.manager.ui.component.RememberedCreateDocument
import app.universal.patch.manager.ui.component.RememberedGetContent
import app.universal.patch.manager.ui.component.toPickerDirectoryUri
import app.universal.patch.manager.ui.component.settings.ExpandableSettingListItem
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsCard
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsDivider
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsItem
import app.universal.patch.manager.ui.component.settings.SettingsSearchHighlight
import app.universal.patch.manager.ui.viewmodel.ImportExportViewModel
import app.universal.patch.manager.ui.viewmodel.ResetDialogState
import app.universal.patch.manager.ui.model.navigation.Settings
import app.universal.patch.manager.ui.screen.settings.SettingsSearchState
import app.universal.patch.manager.util.FilenameUtils
import app.universal.patch.manager.util.toast
import app.universal.patch.manager.util.uiSafe
import app.universal.patch.manager.util.permission.hasNotificationPermission
import app.universal.patch.manager.domain.repository.PatchBundleRepository.BundleImportPhase
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.domain.manager.base.Preference
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import rikka.shizuku.Shizuku
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportExportSettingsScreen(
    onBackClick: () -> Unit,
    vm: ImportExportViewModel = koinViewModel()
) {
    val context = LocalContext.current
    val prefs: PreferencesManager = koinInject()
    val installerManager: InstallerManager = koinInject()
    val shizukuPermissionListener = remember {
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != IMPORTED_SHIZUKU_PERMISSION_REQUEST_CODE) {
                return@OnRequestPermissionResultListener
            }
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            vm.onImportedShizukuPermissionResult(granted)
            if (!granted) {
                context.toast(
                    context.getString(R.string.auto_patch_shizuku_permission_denied)
                )
            }
        }
    }
    DisposableEffect(shizukuPermissionListener) {
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        onDispose {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        }
    }
    val useCustomFilePicker by prefs.useCustomFilePicker.getAsState()
    val keystoreImportDirectory by prefs.keystoreImportLastDirectory.getAsState()
    val patchBundlesImportDirectory by prefs.patchBundlesImportLastDirectory.getAsState()
    val patchProfilesImportDirectory by prefs.patchProfilesImportLastDirectory.getAsState()
    val managerSettingsImportDirectory by prefs.managerSettingsImportLastDirectory.getAsState()
    val everythingImportDirectory by prefs.everythingImportLastDirectory.getAsState()
    val patchSelectionImportDirectory by prefs.patchSelectionImportLastDirectory.getAsState()
    val currentKeystoreExportDirectory by prefs.currentKeystoreExportLastDirectory.getAsState()
    val patchBundlesExportDirectory by prefs.patchBundlesExportLastDirectory.getAsState()
    val patchProfilesExportDirectory by prefs.patchProfilesExportLastDirectory.getAsState()
    val settingsBackupDirectory by prefs.settingsBackupLastDirectory.getAsState()
    val everythingExportDirectory by prefs.everythingExportLastDirectory.getAsState()
    val patchSelectionExportDirectory by prefs.patchSelectionExportLastDirectory.getAsState()
    val searchTarget by SettingsSearchState.target.collectAsStateWithLifecycle()
    var highlightTarget by rememberSaveable { mutableStateOf<Int?>(null) }
    val coroutineScope = rememberCoroutineScope()
    var selectorDialog by rememberSaveable { mutableStateOf<(@Composable () -> Unit)?>(null) }
    val fs: Filesystem = koinInject()
    val storageRoots = remember { fs.storageRoots() }
    val (permissionContract, permissionName) = remember { fs.permissionContract() }
    var pendingImportPicker by rememberSaveable { mutableStateOf<ImportPicker?>(null) }
    var activeImportPicker by rememberSaveable { mutableStateOf<ImportPicker?>(null) }
    var pendingExportPicker by rememberSaveable { mutableStateOf<ExportPicker?>(null) }
    var activeExportPicker by rememberSaveable { mutableStateOf<ExportPicker?>(null) }
    var pendingDocumentImportPicker by rememberSaveable { mutableStateOf<ImportPicker?>(null) }
    var pendingDocumentExportPicker by rememberSaveable { mutableStateOf<ExportPicker?>(null) }
    var exportFileDialogState by remember { mutableStateOf<ExportFileDialogState?>(null) }
    var pendingExportConfirmation by remember { mutableStateOf<PendingExportConfirmation?>(null) }
    var exportInProgress by rememberSaveable { mutableStateOf(false) }
    var exportPreparing by remember { mutableStateOf(false) }

    fun importDirectoryPreference(picker: ImportPicker): Preference<String> = when (picker) {
        ImportPicker.Keystore -> prefs.keystoreImportLastDirectory
        ImportPicker.PatchBundles -> prefs.patchBundlesImportLastDirectory
        ImportPicker.PatchProfiles -> prefs.patchProfilesImportLastDirectory
        ImportPicker.ManagerSettings -> prefs.managerSettingsImportLastDirectory
        ImportPicker.Everything -> prefs.everythingImportLastDirectory
        ImportPicker.PatchSelection -> prefs.patchSelectionImportLastDirectory
    }

    fun importDirectory(picker: ImportPicker): String = when (picker) {
        ImportPicker.Keystore -> keystoreImportDirectory
        ImportPicker.PatchBundles -> patchBundlesImportDirectory
        ImportPicker.PatchProfiles -> patchProfilesImportDirectory
        ImportPicker.ManagerSettings -> managerSettingsImportDirectory
        ImportPicker.Everything -> everythingImportDirectory
        ImportPicker.PatchSelection -> patchSelectionImportDirectory
    }

    fun exportDirectoryPreference(picker: ExportPicker): Preference<String> = when (picker) {
        ExportPicker.Keystore -> prefs.currentKeystoreExportLastDirectory
        ExportPicker.PatchBundles -> prefs.patchBundlesExportLastDirectory
        ExportPicker.PatchProfiles -> prefs.patchProfilesExportLastDirectory
        ExportPicker.ManagerSettings -> prefs.settingsBackupLastDirectory
        ExportPicker.Everything -> prefs.everythingExportLastDirectory
        ExportPicker.PatchSelection -> prefs.patchSelectionExportLastDirectory
    }

    fun exportDirectory(picker: ExportPicker): String = when (picker) {
        ExportPicker.Keystore -> currentKeystoreExportDirectory
        ExportPicker.PatchBundles -> patchBundlesExportDirectory
        ExportPicker.PatchProfiles -> patchProfilesExportDirectory
        ExportPicker.ManagerSettings -> settingsBackupDirectory
        ExportPicker.Everything -> everythingExportDirectory
        ExportPicker.PatchSelection -> patchSelectionExportDirectory
    }

    fun filePickerBusy(): Boolean =
        vm.settingsImportPending || exportPreparing || exportInProgress ||
            activeExportPicker != null || pendingExportPicker != null ||
            pendingDocumentExportPicker != null ||
            activeImportPicker != null || pendingImportPicker != null ||
            pendingDocumentImportPicker != null

    fun startSelectionAction(action: ImportExportViewModel.SelectionAction) {
        if (filePickerBusy()) return
        when (action) {
            ImportExportViewModel.SelectionAction.ImportBundle -> vm.importSelectionForBundle()
            ImportExportViewModel.SelectionAction.ImportAllBundles -> vm.importSelectionAllBundles()
            ImportExportViewModel.SelectionAction.ExportBundle -> vm.exportSelectionForBundle()
            ImportExportViewModel.SelectionAction.ExportAllBundles -> vm.exportSelectionAllBundles()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(permissionContract) { granted ->
        val pendingImport = pendingImportPicker
        val pendingExport = pendingExportPicker
        if (granted) {
            activeImportPicker = pendingImport
            activeExportPicker = pendingExport
        } else {
            if (pendingExport != null) vm.clearPreparedExport()
            if (pendingImport == ImportPicker.PatchSelection) {
                vm.clearSelectionAction()
            }
            if (pendingExport == ExportPicker.PatchSelection) {
                vm.clearSelectionAction()
            }
        }
        pendingImportPicker = null
        pendingExportPicker = null
    }
    val importedStoragePermissionLauncher = rememberLauncherForActivityResult(permissionContract) {
        vm.onImportedStoragePermissionResult(it)
    }
    val importedNotificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        vm.onImportedNotificationPermissionResult(it)
    }
    val importDocumentLauncher = rememberLauncherForActivityResult(
        contract = RememberedGetContent {
            pendingDocumentImportPicker
                ?.let(::importDirectory)
                ?.takeIf(String::isNotBlank)
                ?.let(Uri::parse)
        }
    ) { uri ->
        val picker = pendingDocumentImportPicker
        pendingDocumentImportPicker = null
        if (picker == null) return@rememberLauncherForActivityResult
        if (uri == null) {
            if (picker == ImportPicker.PatchSelection) vm.clearSelectionAction()
            return@rememberLauncherForActivityResult
        }
        if (!isValidImportUri(context, uri, picker)) {
            if (picker == ImportPicker.PatchSelection) vm.clearSelectionAction()
            return@rememberLauncherForActivityResult
        }
        coroutineScope.launch {
            importDirectoryPreference(picker).update(uri.toPickerDirectoryUri().toString())
        }
        when (picker) {
            ImportPicker.Keystore -> vm.startKeystoreImport(uri)
            ImportPicker.PatchBundles -> vm.importPatchBundles(uri)
            ImportPicker.PatchProfiles -> vm.importPatchProfiles(uri)
            ImportPicker.ManagerSettings -> vm.importManagerSettings(uri)
            ImportPicker.Everything -> vm.importEverything(uri)
            ImportPicker.PatchSelection -> when (vm.selectionAction) {
                ImportExportViewModel.SelectionAction.ImportAllBundles ->
                    vm.executeSelectionImportAllBundles(uri)
                else -> vm.executeSelectionImport(uri)
            }
        }
    }
    val runDocumentExport: (ExportPicker, Uri) -> Unit = { picker, uri ->
        exportInProgress = true
        val job = when (picker) {
            ExportPicker.Keystore -> vm.exportKeystore(uri)
            ExportPicker.PatchBundles -> vm.exportPatchBundles(uri)
            ExportPicker.PatchProfiles -> vm.exportPatchProfiles(uri)
            ExportPicker.ManagerSettings -> vm.exportManagerSettings(uri)
            ExportPicker.Everything -> vm.exportEverything(uri)
            ExportPicker.PatchSelection -> when (vm.selectionAction) {
                ImportExportViewModel.SelectionAction.ExportAllBundles ->
                    vm.executeSelectionExportAllBundles(uri)
                else -> vm.executeSelectionExport(uri)
            }
        }
        coroutineScope.launch {
            job.join()
            exportInProgress = false
            activeExportPicker = null
        }
    }
    val exportDocumentLauncher = rememberLauncherForActivityResult(
        contract = RememberedCreateDocument("application/json") {
            pendingDocumentExportPicker
                ?.let(::exportDirectory)
                ?.takeIf(String::isNotBlank)
                ?.let(Uri::parse)
        }
    ) { uri ->
        val picker = pendingDocumentExportPicker
        pendingDocumentExportPicker = null
        if (picker == null) return@rememberLauncherForActivityResult
        if (uri == null) {
            vm.clearPreparedExport()
            if (picker == ExportPicker.PatchSelection) vm.clearSelectionAction()
            return@rememberLauncherForActivityResult
        }
        coroutineScope.launch {
            exportDirectoryPreference(picker).update(uri.toPickerDirectoryUri().toString())
        }
        runDocumentExport(picker, uri)
    }
    val exportKeystoreDocumentLauncher = rememberLauncherForActivityResult(
        contract = RememberedCreateDocument("application/octet-stream") {
            currentKeystoreExportDirectory.takeIf(String::isNotBlank)?.let(Uri::parse)
        }
    ) { uri ->
        val picker = pendingDocumentExportPicker
        pendingDocumentExportPicker = null
        if (picker == null) return@rememberLauncherForActivityResult
        if (uri == null) {
            vm.clearPreparedExport()
            if (picker == ExportPicker.PatchSelection) vm.clearSelectionAction()
            return@rememberLauncherForActivityResult
        }
        coroutineScope.launch {
            exportDirectoryPreference(picker).update(uri.toPickerDirectoryUri().toString())
        }
        runDocumentExport(picker, uri)
    }
    val openImportPicker: (ImportPicker) -> Unit = open@{ target ->
        if (filePickerBusy()) return@open
        if (target != ImportPicker.PatchSelection) vm.clearSelectionAction()

        if (useCustomFilePicker) {
            if (fs.hasStoragePermission()) {
                activeImportPicker = target
            } else {
                pendingImportPicker = target
                permissionLauncher.launch(permissionName)
            }
        } else {
            pendingDocumentImportPicker = target
            importDocumentLauncher.launch(documentMimeForImportPicker(target))
        }
    }
    val resolveExportFileName = { target: ExportPicker ->
        defaultExportFileName(
            picker = target,
            selectionAction = vm.selectionAction,
            selectedBundleDisplayTitle = vm.selectedBundle?.displayTitle
        )
    }
    val openExportPicker: (ExportPicker) -> Unit = open@{ target ->
        if (filePickerBusy()) return@open
        if (target != ExportPicker.PatchSelection) vm.clearSelectionAction()

        exportPreparing = true
        coroutineScope.launch {
            try {
                val type = when (target) {
                    ExportPicker.PatchBundles -> ImportExportViewModel.ExportType.PatchBundles
                    ExportPicker.PatchProfiles -> ImportExportViewModel.ExportType.PatchProfiles
                    ExportPicker.PatchSelection -> when (vm.selectionAction) {
                        ImportExportViewModel.SelectionAction.ExportAllBundles ->
                            ImportExportViewModel.ExportType.SelectionAllBundles
                        else -> ImportExportViewModel.ExportType.SelectionBundle
                    }
                    else -> null
                }
                if (type != null && !vm.prepareExport(type)) {
                    if (target == ExportPicker.PatchSelection) vm.clearSelectionAction()
                    return@launch
                }

                val exportName = resolveExportFileName(target)
                if (useCustomFilePicker) {
                    if (fs.hasStoragePermission()) {
                        activeExportPicker = target
                    } else {
                        pendingExportPicker = target
                        permissionLauncher.launch(permissionName)
                    }
                } else {
                    pendingDocumentExportPicker = target
                    if (target == ExportPicker.Keystore) {
                        exportKeystoreDocumentLauncher.launch(exportName)
                    } else {
                        exportDocumentLauncher.launch(exportName)
                    }
                }
            } finally {
                exportPreparing = false
            }
        }
    }
    val runExport = { picker: ExportPicker, target: Path ->
        exportInProgress = true
        val job = when (picker) {
            ExportPicker.Keystore -> vm.exportKeystore(target)
            ExportPicker.PatchBundles -> vm.exportPatchBundles(target)
            ExportPicker.PatchProfiles -> vm.exportPatchProfiles(target)
            ExportPicker.ManagerSettings -> vm.exportManagerSettings(target)
            ExportPicker.Everything -> vm.exportEverything(target)
            ExportPicker.PatchSelection -> when (vm.selectionAction) {
                ImportExportViewModel.SelectionAction.ExportAllBundles ->
                    vm.executeSelectionExportAllBundles(target)
                else -> vm.executeSelectionExport(target)
            }
        }
        coroutineScope.launch {
            job.join()
            exportInProgress = false
            activeExportPicker = null
        }
    }

    val patchBundles by vm.patchBundles.collectAsStateWithLifecycle(initialValue = emptyList())
    val packagesWithSelections by vm.packagesWithSelection.collectAsStateWithLifecycle(initialValue = emptySet())
    val packagesWithOptions by vm.packagesWithOptions.collectAsStateWithLifecycle(initialValue = emptySet())
    val importProgress by vm.bundleImportProgress.collectAsStateWithLifecycle(initialValue = null)
    val keystoreDiagnostics = vm.keystoreDiagnostics
    val importedPermissionRequest = vm.importedPermissionRequest

    LaunchedEffect(Unit) {
        vm.refreshKeystoreDiagnostics()
    }

    LaunchedEffect(searchTarget) {
        val target = searchTarget
        if (target?.destination == Settings.ImportExport) {
            highlightTarget = target.targetId
            SettingsSearchState.clear()
        }
    }
    LaunchedEffect(useCustomFilePicker) {
        if (!useCustomFilePicker) {
            pendingImportPicker = null
            activeImportPicker = null
            pendingExportPicker = null
            activeExportPicker = null
            exportFileDialogState = null
            pendingExportConfirmation = null
        } else {
            pendingDocumentImportPicker = null
            pendingDocumentExportPicker = null
        }
    }
    LaunchedEffect(vm.importedSettingsRestartLanguage) {
        val language = vm.importedSettingsRestartLanguage ?: return@LaunchedEffect
        val activity = context as? android.app.Activity ?: return@LaunchedEffect
        vm.consumeImportedSettingsRestart()
        app.universal.patch.manager.util.applyAppLanguage(language)
        activity.recreate()
    }
    LaunchedEffect(importedPermissionRequest) {
        val request = importedPermissionRequest ?: return@LaunchedEffect
        if (request.needsStoragePermission) {
            if (!fs.hasStoragePermission()) {
                importedStoragePermissionLauncher.launch(permissionName)
                return@LaunchedEffect
            }
            vm.onImportedStoragePermissionResult(granted = true)
            return@LaunchedEffect
        }

        if (request.needsNotificationPermission) {
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !context.hasNotificationPermission()
            ) {
                importedNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return@LaunchedEffect
            }
            vm.onImportedNotificationPermissionResult(granted = true)
            return@LaunchedEffect
        }

        if (request.needsShizukuPermission) {
            val status = installerManager.shizukuStatus(
                InstallerManager.InstallTarget.PATCHER
            )
            when {
                status.permissionGranted ->
                    vm.onImportedShizukuPermissionResult(granted = true)
                status.installed && status.supported && status.running -> {
                    runCatching {
                        Shizuku.requestPermission(
                            IMPORTED_SHIZUKU_PERMISSION_REQUEST_CODE
                        )
                    }.onFailure {
                        vm.onImportedShizukuPermissionResult(granted = false)
                        context.toast(
                            context.getString(
                                R.string.auto_patch_shizuku_permission_request_failed
                            )
                        )
                    }
                }
                else -> {
                    vm.onImportedShizukuPermissionResult(granted = false)
                    status.availability.reason?.let { reason ->
                        context.toast(context.getString(reason))
                    }
                    installerManager.openShizukuApp()
                }
            }
        }
    }

    vm.selectionAction?.let { action ->
        when (action) {
            ImportExportViewModel.SelectionAction.ExportAllBundles -> {
                LaunchedEffect(action) {
                    openExportPicker(ExportPicker.PatchSelection)
                }
            }
            ImportExportViewModel.SelectionAction.ImportAllBundles -> {
                if (activeImportPicker == null && pendingDocumentImportPicker == null) {
                    openImportPicker(ImportPicker.PatchSelection)
                }
            }
            else -> {
                if (vm.selectedBundle == null) {
                    BundleSelector(patchBundles) {
                        if (it == null) {
                            vm.clearSelectionAction()
                        } else {
                            vm.selectBundle(it)
                            when (action) {
                                ImportExportViewModel.SelectionAction.ImportBundle -> {
                                    openImportPicker(ImportPicker.PatchSelection)
                                }
                                ImportExportViewModel.SelectionAction.ExportBundle -> {
                                    openExportPicker(ExportPicker.PatchSelection)
                                }
                                else -> {}
                            }
                        }
                    }
                }
            }
        }
    }

    if (vm.showCredentialsDialog) {
        KeystoreCredentialsDialog(
            onDismissRequest = vm::cancelKeystoreImport,
            onSubmit = { alias, storePass, keyPass ->
                vm.viewModelScope.launch {
                    uiSafe(context, R.string.failed_to_import_keystore, "Failed to import keystore") {
                        val result = vm.tryKeystoreImport(alias, storePass, keyPass)
                        if (!result) context.toast(context.getString(R.string.import_keystore_wrong_credentials))
                    }
                }
            }
        )
    }

    vm.resetDialogState?.let { state ->
        with(state) {
            ConfirmDialog(
                onDismiss = { vm.resetDialogState = null },
                onConfirm = {
                    vm.resetDialogState = null
                    state.onConfirm()
                },
                title = stringResource(titleResId),
                description = dialogOptionName?.let {
                    stringResource(descriptionResId, it)
                } ?: stringResource(descriptionResId),
                icon = Icons.Outlined.WarningAmber
            )
        }
    }
    pendingExportConfirmation?.let { state ->
        ConfirmDialog(
            onDismiss = {
                pendingExportConfirmation = null
                exportFileDialogState = ExportFileDialogState(state.picker, state.directory, state.fileName)
            },
            onConfirm = {
                pendingExportConfirmation = null
                runExport(state.picker, state.directory.resolve(state.fileName))
            },
            title = stringResource(R.string.export_overwrite_title),
            description = stringResource(R.string.export_overwrite_description, state.fileName),
            icon = Icons.Outlined.WarningAmber
        )
    }
    InterceptBackHandler(enabled = vm.settingsImportPending) {}

    if (vm.settingsImportPending || exportPreparing || exportInProgress) {
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
                    stringResource(
                        if (vm.settingsImportPending) R.string.import_ else R.string.export
                    ),
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
                        stringResource(
                            when {
                                vm.settingsImportPending -> R.string.import_settings_in_progress
                                exportPreparing -> R.string.patcher_step_group_preparing
                                else -> R.string.patcher_step_group_saving
                            }
                        ),
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

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.import_export),
                scrollBehavior = scrollBehavior,
                onBackClick = { if (!vm.settingsImportPending) onBackClick() }
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
            selectorDialog?.invoke()
            if (useCustomFilePicker) activeImportPicker?.let { picker ->
                val fileFilter = when (picker) {
                    ImportPicker.Keystore -> ::isKeystoreFile
                    ImportPicker.PatchBundles,
                    ImportPicker.PatchProfiles,
                    ImportPicker.ManagerSettings,
                    ImportPicker.Everything,
                    ImportPicker.PatchSelection -> ::isJsonFile
                }
                PathSelectorDialog(
                    roots = storageRoots,
                    onSelect = { path ->
                        activeImportPicker = null
                        if (path == null) {
                            if (picker == ImportPicker.PatchSelection) {
                                vm.clearSelectionAction()
                            }
                            return@PathSelectorDialog
                        }
                        when (picker) {
                            ImportPicker.Keystore -> vm.startKeystoreImport(path)
                            ImportPicker.PatchBundles -> vm.importPatchBundles(path)
                            ImportPicker.PatchProfiles -> vm.importPatchProfiles(path)
                            ImportPicker.ManagerSettings -> vm.importManagerSettings(path)
                            ImportPicker.Everything -> vm.importEverything(path)
                            ImportPicker.PatchSelection -> when (vm.selectionAction) {
                                ImportExportViewModel.SelectionAction.ImportAllBundles ->
                                    vm.executeSelectionImportAllBundles(path)
                                else -> vm.executeSelectionImport(path)
                            }
                        }
                    },
                    fileFilter = fileFilter,
                    allowDirectorySelection = false,
                    lastDirectoryPreference = importDirectoryPreference(picker)
                )
            }
            if (useCustomFilePicker) activeExportPicker?.let { picker ->
                val fileFilter = when (picker) {
                    ExportPicker.Keystore -> ::isKeystoreFile
                    ExportPicker.PatchBundles,
                    ExportPicker.PatchProfiles,
                    ExportPicker.ManagerSettings,
                    ExportPicker.Everything,
                    ExportPicker.PatchSelection -> ::isJsonFile
                }
                val fileTypeLabel = when (picker) {
                    ExportPicker.Keystore -> ".keystore"
                    ExportPicker.PatchBundles,
                    ExportPicker.PatchProfiles,
                    ExportPicker.ManagerSettings,
                    ExportPicker.Everything,
                    ExportPicker.PatchSelection -> ".json"
                }
                PathSelectorDialog(
                    roots = storageRoots,
                    onSelect = { path ->
                        if (path == null) {
                            activeExportPicker = null
                            vm.clearPreparedExport()
                            if (picker == ExportPicker.PatchSelection) {
                                vm.clearSelectionAction()
                            }
                            return@PathSelectorDialog
                        }
                    },
                    fileFilter = fileFilter,
                    allowDirectorySelection = false,
                    fileTypeLabel = fileTypeLabel,
                    confirmButtonText = stringResource(R.string.save),
                    onConfirm = { directory ->
                        exportFileDialogState = ExportFileDialogState(
                            picker = picker,
                            directory = directory,
                            fileName = resolveExportFileName(picker)
                        )
                    },
                    lastDirectoryPreference = exportDirectoryPreference(picker)
                )
            }
            if (useCustomFilePicker) exportFileDialogState?.let { state ->
                ExportFileNameDialog(
                    initialName = state.fileName,
                    onDismiss = {
                        exportFileDialogState = null
                        activeExportPicker = null
                        vm.clearPreparedExport()
                        if (state.picker == ExportPicker.PatchSelection) {
                            vm.clearSelectionAction()
                        }
                    },
                    onConfirm = { fileName ->
                        val trimmedName = fileName.trim()
                        if (trimmedName.isBlank()) return@ExportFileNameDialog
                        exportFileDialogState = null
                        val target = state.directory.resolve(trimmedName)
                        if (Files.exists(target)) {
                            pendingExportConfirmation = PendingExportConfirmation(
                                picker = state.picker,
                                directory = state.directory,
                                fileName = trimmedName
                            )
                        } else {
                            runExport(state.picker, target)
                        }
                    }
                )
            }

            AnimatedVisibility(
                visible = importProgress != null,
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
                importProgress?.let { progress ->
                    val total = progress.total.coerceAtLeast(1)
                    val bundleCount = progress.bundleCount.coerceAtLeast(1)
                    val subtitleParts = buildList {
                        val stepLabel = if (progress.isStepBased) {
                            val step = (progress.processed + 1).coerceAtMost(total)
                            stringResource(R.string.import_patch_bundles_banner_steps, step, total)
                        } else {
                            pluralStringResource(R.plurals.bundle_update_progress_quantity, total, progress.processed, total)
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            SettingsSearchHighlight(
                targetKey = R.string.keystore_section,
                activeKey = highlightTarget,
                onHighlightComplete = { highlightTarget = null }
            ) { highlightModifier ->
                GroupHeader(
                    stringResource(R.string.keystore_section),
                    icon = SettingsSectionIcons.Keystore,
                    modifier = highlightModifier
                )
            }
            ExpressiveSettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                SettingsSearchHighlight(
                    targetKey = R.string.export_keystore,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    GroupItem(
                        modifier = highlightModifier,
                        onClick = {
                            openExportPicker(ExportPicker.Keystore)
                        },
                        headline = R.string.export_keystore,
                        description = R.string.export_keystore_description
                    )
                }
                ExpressiveSettingsDivider()

                SettingsSearchHighlight(
                    targetKey = R.string.import_keystore,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    GroupItem(
                        modifier = highlightModifier,
                        onClick = {
                            openImportPicker(ImportPicker.Keystore)
                        },
                        headline = R.string.import_keystore,
                        description = R.string.import_keystore_description
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.keystore_diagnostics,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpandableSettingListItem(
                        headlineContent = stringResource(R.string.keystore_diagnostics),
                        supportingContent = stringResource(R.string.keystore_diagnostics_description),
                        modifier = highlightModifier,
                        expandableContent = {
                            KeystoreDiagnosticsPanel(
                                diagnostics = keystoreDiagnostics,
                                onRefresh = vm::refreshKeystoreDiagnostics
                            )
                        }
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.regenerate_keystore,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    GroupItem(
                        modifier = highlightModifier,
                        onClick = {
                            vm.resetDialogState = ResetDialogState.Keystore {
                                vm.regenerateKeystore()
                            }
                        },
                        headline = R.string.regenerate_keystore,
                        description = R.string.regenerate_keystore_description
                    )
                }
            }

            SettingsSearchHighlight(
                targetKey = R.string.settings_selections_bundles_section,
                activeKey = highlightTarget,
                onHighlightComplete = { highlightTarget = null }
            ) { highlightModifier ->
                GroupHeader(
                    stringResource(R.string.settings_selections_bundles_section),
                    icon = SettingsSectionIcons.SettingsSelectionsBundles,
                    modifier = highlightModifier
                )
            }
            ExpressiveSettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                SettingsSearchHighlight(
                    targetKey = R.string.export_everything,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    GroupItem(
                        modifier = highlightModifier,
                        onClick = {
                            openExportPicker(ExportPicker.Everything)
                        },
                        headline = R.string.export_everything,
                        description = R.string.export_everything_description
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.export_patch_selection,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpandableSettingListItem(
                        headlineContent = stringResource(R.string.export_patch_selection),
                        supportingContent = stringResource(R.string.export_patch_selection_description),
                        modifier = highlightModifier,
                        expandableContent = {
                            GroupItem(
                                onClick = {
                                    startSelectionAction(ImportExportViewModel.SelectionAction.ExportBundle)
                                },
                                headline = R.string.export_patch_selection_bundle,
                                description = R.string.export_patch_selection_bundle_description
                            )

                            GroupItem(
                                onClick = {
                                    startSelectionAction(ImportExportViewModel.SelectionAction.ExportAllBundles)
                                },
                                headline = R.string.export_patch_selection_all,
                                description = R.string.export_patch_selection_all_description
                            )
                        }
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.export_patch_bundles,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    GroupItem(
                        modifier = highlightModifier,
                        onClick = {
                            openExportPicker(ExportPicker.PatchBundles)
                        },
                        headline = R.string.export_patch_bundles,
                        description = R.string.export_patch_bundles_description
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.export_patch_profiles,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    GroupItem(
                        modifier = highlightModifier,
                        onClick = {
                            openExportPicker(ExportPicker.PatchProfiles)
                        },
                        headline = R.string.export_patch_profiles,
                        description = R.string.export_patch_profiles_description
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.export_manager_settings,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    GroupItem(
                        modifier = highlightModifier,
                        onClick = {
                            openExportPicker(ExportPicker.ManagerSettings)
                        },
                        headline = R.string.export_manager_settings,
                        description = R.string.export_manager_settings_description
                    )
                }
            }
            ExpressiveSettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                SettingsSearchHighlight(
                    targetKey = R.string.import_everything,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    GroupItem(
                        modifier = highlightModifier,
                        onClick = {
                            openImportPicker(ImportPicker.Everything)
                        },
                        headline = R.string.import_everything,
                        description = R.string.import_everything_description
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.import_patch_selection,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpandableSettingListItem(
                        headlineContent = stringResource(R.string.import_patch_selection),
                        supportingContent = stringResource(R.string.import_patch_selection_description),
                        modifier = highlightModifier,
                        expandableContent = {
                            GroupItem(
                                onClick = {
                                    startSelectionAction(ImportExportViewModel.SelectionAction.ImportBundle)
                                },
                                headline = R.string.import_patch_selection_bundle,
                                description = R.string.import_patch_selection_bundle_description
                            )
                            GroupItem(
                                onClick = {
                                    startSelectionAction(ImportExportViewModel.SelectionAction.ImportAllBundles)
                                },
                                headline = R.string.import_patch_selection_all,
                                description = R.string.import_patch_selection_all_description
                            )
                        }
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.import_patch_bundles,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    GroupItem(
                        modifier = highlightModifier,
                        onClick = {
                            openImportPicker(ImportPicker.PatchBundles)
                        },
                        headline = R.string.import_patch_bundles,
                        description = R.string.import_patch_bundles_description
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.import_patch_profiles,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    GroupItem(
                        modifier = highlightModifier,
                        onClick = {
                            openImportPicker(ImportPicker.PatchProfiles)
                        },
                        headline = R.string.import_patch_profiles,
                        description = R.string.import_patch_profiles_description
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.import_manager_settings,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    GroupItem(
                        modifier = highlightModifier,
                        onClick = {
                            openImportPicker(ImportPicker.ManagerSettings)
                        },
                        headline = R.string.import_manager_settings,
                        description = R.string.import_manager_settings_description
                    )
                }
            }

            SettingsSearchHighlight(
                targetKey = R.string.reset,
                activeKey = highlightTarget,
                onHighlightComplete = { highlightTarget = null }
            ) { highlightModifier ->
                GroupHeader(
                    stringResource(R.string.reset),
                    icon = SettingsSectionIcons.Reset,
                    modifier = highlightModifier
                )
            }
            ExpressiveSettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                SettingsSearchHighlight(
                    targetKey = R.string.reset_patch_selection,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpandableSettingListItem(
                        headlineContent = stringResource(R.string.reset_patch_selection),
                        supportingContent = stringResource(R.string.reset_patch_selection_description),
                        modifier = highlightModifier,
                        expandableContent = {
                            GroupItem(
                                onClick = {
                                    vm.resetDialogState = ResetDialogState.PatchSelectionAll {
                                        vm.resetSelection()
                                    }
                                },
                                headline = R.string.patch_selection_reset_all,
                                description = R.string.patch_selection_reset_all_description
                            )

                            GroupItem(
                                onClick = {
                                    selectorDialog = {
                                        PackageSelector(packages = packagesWithSelections) { packageName ->
                                            packageName?.also {
                                                vm.resetDialogState =
                                                    ResetDialogState.PatchSelectionPackage(packageName) {
                                                        vm.resetSelectionForPackage(packageName)
                                                    }
                                            }
                                            selectorDialog = null
                                        }
                                    }
                                },
                                headline = R.string.patch_selection_reset_package,
                                description = R.string.patch_selection_reset_package_description
                            )

                            if (patchBundles.isNotEmpty()) {
                                GroupItem(
                                    onClick = {
                                        selectorDialog = {
                                            BundleSelector(sources = patchBundles) { src ->
                                                src?.also {
                                                    coroutineScope.launch {
                                                        vm.resetDialogState =
                                                            ResetDialogState.PatchSelectionBundle(it.displayTitle) {
                                                                vm.resetSelectionForPatchBundle(it)
                                                            }
                                                    }
                                                }
                                                selectorDialog = null
                                            }
                                        }
                                    },
                                    headline = R.string.patch_selection_reset_patches,
                                    description = R.string.patch_selection_reset_patches_description
                                )
                            }
                        }
                    )
                }

                ExpressiveSettingsDivider()

                SettingsSearchHighlight(
                    targetKey = R.string.reset_patch_options,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpandableSettingListItem(
                        headlineContent = stringResource(R.string.reset_patch_options),
                        supportingContent = stringResource(R.string.reset_patch_options_description),
                        modifier = highlightModifier,
                        expandableContent = {
                            GroupItem(
                                onClick = {
                                    vm.resetDialogState = ResetDialogState.PatchOptionsAll {
                                        vm.resetOptions()
                                    }
                                }, // TODO: patch options import/export.
                                headline = R.string.patch_options_reset_all,
                                description = R.string.patch_options_reset_all_description,
                            )

                            GroupItem(
                                onClick = {
                                    selectorDialog = {
                                        PackageSelector(packages = packagesWithOptions) { packageName ->
                                            packageName?.also {
                                                vm.resetDialogState =
                                                    ResetDialogState.PatchOptionPackage(packageName) {
                                                        vm.resetOptionsForPackage(packageName)
                                                    }
                                            }
                                            selectorDialog = null
                                        }
                                    }
                                },
                                headline = R.string.patch_options_reset_package,
                                description = R.string.patch_options_reset_package_description
                            )

                            if (patchBundles.isNotEmpty()) {
                                GroupItem(
                                    onClick = {
                                        selectorDialog = {
                                            BundleSelector(sources = patchBundles) { src ->
                                                src?.also {
                                                    coroutineScope.launch {
                                                        vm.resetDialogState =
                                                            ResetDialogState.PatchOptionBundle(src.displayTitle) {
                                                                vm.resetOptionsForBundle(src)
                                                            }
                                                    }
                                                }
                                                selectorDialog = null
                                            }
                                        }
                                    },
                                    headline = R.string.patch_options_reset_patches,
                                    description = R.string.patch_options_reset_patches_description,
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PackageSelector(packages: Set<String>, onFinish: (String?) -> Unit) {
    val context = LocalContext.current

    val noPackages = packages.isEmpty()

    LaunchedEffect(noPackages) {
        if (noPackages) {
            context.toast(context.getString(R.string.no_packages_available))
            onFinish(null)
        }
    }

    if (noPackages) return

    ModalBottomSheet(
        onDismissRequest = { onFinish(null) }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .height(48.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.select_package),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            packages.forEach {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .height(48.dp)
                        .fillMaxWidth()
                        .clickable {
                            onFinish(it)
                        }
                ) {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupItem(
    onClick: () -> Unit,
    @StringRes headline: Int,
    @StringRes description: Int? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    ExpressiveSettingsItem(
        modifier = modifier,
        headlineContent = stringResource(headline),
        supportingContent = description?.let { stringResource(it) },
        supportingContentSlot = supportingContent,
        onClick = onClick
    )
}

private enum class StatusTone { Positive, Negative, Neutral }

@Composable
private fun KeystoreDiagnosticsPanel(
    diagnostics: KeystoreManager.KeystoreDiagnostics?,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    val scope = rememberCoroutineScope()
    val refreshedMessage = stringResource(R.string.keystore_diagnostics_refreshed)
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var isRefreshing by rememberSaveable { mutableStateOf(false) }

    fun copy(label: String, value: String) {
        if (value.isBlank()) return
        clipboard?.setPrimaryClip(ClipData.newPlainText(label, value))
        context.toast(context.getString(R.string.toast_copied_to_clipboard))
    }

    fun triggerRefresh() {
        if (isRefreshing) return
        scope.launch {
            isRefreshing = true
            onRefresh()
            delay(850)
            isRefreshing = false
            context.toast(refreshedMessage)
        }
    }

    val showShimmer = isRefreshing

    val fieldColors = OutlinedTextFieldDefaults.colors(
        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
        focusedContainerColor = MaterialTheme.colorScheme.surface,
        disabledContainerColor = MaterialTheme.colorScheme.surface,
        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        disabledBorderColor = MaterialTheme.colorScheme.outlineVariant,
        disabledTextColor = MaterialTheme.colorScheme.onSurface
    )
    val fieldShape = MaterialTheme.shapes.medium

    @Composable
    fun ShimmerField() {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            color = MaterialTheme.colorScheme.surface,
            shape = fieldShape,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            ShimmerBox(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                shape = fieldShape
            )
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (diagnostics == null) {
                Text(
                    text = stringResource(R.string.keystore_diagnostics_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }

            Text(
                text = stringResource(R.string.keystore_diagnostics_credentials_section),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.keystore_diagnostics_alias),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.outline
            )
            if (showShimmer) {
                ShimmerField()
            } else {
                OutlinedTextField(
                    value = diagnostics.alias,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    colors = fieldColors,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { copy("Keystore alias", diagnostics.alias) },
                    trailingIcon = {
                        IconButton(
                            onClick = { copy("Keystore alias", diagnostics.alias) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                contentDescription = stringResource(R.string.copy_to_clipboard)
                            )
                        }
                    }
                )
            }

            Text(
                text = stringResource(R.string.keystore_diagnostics_password),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.outline
            )
            if (showShimmer) {
                ShimmerField()
            } else {
                val passwordValue = diagnostics.storePass
                val passwordEmpty = passwordValue.isBlank()
                val passwordDisplay = if (passwordEmpty) {
                    stringResource(R.string.keystore_diagnostics_password_empty)
                } else {
                    passwordValue
                }
                OutlinedTextField(
                    value = passwordDisplay,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    colors = fieldColors,
                    shape = MaterialTheme.shapes.medium,
                    visualTransformation = if (passwordVisible || passwordEmpty) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            IconButton(
                                onClick = { passwordVisible = !passwordVisible },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = stringResource(
                                        if (passwordVisible) R.string.hide_password_field else R.string.show_password_field
                                    )
                                )
                            }
                            IconButton(
                                onClick = { copy("Keystore password", passwordValue) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.ContentCopy,
                                    contentDescription = stringResource(R.string.copy_to_clipboard)
                                )
                            }
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.keystore_diagnostics_details_section),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            val typeLabel = diagnostics.type?.takeIf { it.isNotBlank() } ?: "-"
            val fingerprintLabel = diagnostics.fingerprint?.takeIf { it.isNotBlank() } ?: "-"
            Text(
                text = stringResource(R.string.keystore_diagnostics_type_label),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.outline
            )
            if (showShimmer) {
                ShimmerField()
            } else {
                OutlinedTextField(
                    value = typeLabel,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    colors = fieldColors,
                    shape = fieldShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { copy("Keystore type", typeLabel) },
                    trailingIcon = {
                        IconButton(
                            onClick = { copy("Keystore type", typeLabel) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                contentDescription = stringResource(R.string.copy_to_clipboard)
                            )
                        }
                    }
                )
            }

            Text(
                text = stringResource(R.string.keystore_diagnostics_fingerprint_label),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.outline
            )
            if (showShimmer) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(76.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shape = fieldShape,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    ShimmerBox(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp),
                        shape = fieldShape
                    )
                }
            } else {
                OutlinedTextField(
                    value = fingerprintLabel,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = false,
                    minLines = 2,
                    maxLines = 3,
                    colors = fieldColors,
                    shape = fieldShape,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { copy("Keystore fingerprint", fingerprintLabel) },
                    trailingIcon = {
                        IconButton(
                            onClick = { copy("Keystore fingerprint", fingerprintLabel) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                contentDescription = stringResource(R.string.copy_to_clipboard)
                            )
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.keystore_diagnostics_files_section),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            val keystoreStatus = if (diagnostics.keystoreSize > 0L) {
                stringResource(
                    R.string.keystore_diagnostics_present_size,
                    Formatter.formatShortFileSize(context, diagnostics.keystoreSize)
                )
            } else {
                stringResource(R.string.keystore_diagnostics_missing)
            }
            val backupStatus = if ((diagnostics.backupSize ?: 0L) > 0L) {
                stringResource(
                    R.string.keystore_diagnostics_present_size,
                    Formatter.formatShortFileSize(context, diagnostics.backupSize ?: 0L)
                )
            } else {
                stringResource(R.string.keystore_diagnostics_missing)
            }
            val legacyPresent = diagnostics.legacySize > 0L
            val legacyStatus = if (legacyPresent) {
                stringResource(
                    R.string.keystore_diagnostics_present_size,
                    Formatter.formatShortFileSize(context, diagnostics.legacySize)
                )
            } else {
                stringResource(R.string.not_applicable_short)
            }

            @Composable
            fun StatusChip(text: String, tone: StatusTone) {
                val (container, content) = when (tone) {
                    StatusTone.Positive -> MaterialTheme.colorScheme.primaryContainer to
                        MaterialTheme.colorScheme.onPrimaryContainer
                    StatusTone.Negative -> MaterialTheme.colorScheme.errorContainer to
                        MaterialTheme.colorScheme.onErrorContainer
                    StatusTone.Neutral -> MaterialTheme.colorScheme.surfaceVariant to
                        MaterialTheme.colorScheme.onSurfaceVariant
                }
                Surface(
                    color = container,
                    contentColor = content,
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    @Composable
                    fun ShimmerRow() {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ShimmerBox(
                                Modifier.width(140.dp).height(12.dp),
                                shape = RoundedCornerShape(999.dp)
                            )
                            ShimmerBox(
                                Modifier.width(90.dp).height(20.dp),
                                shape = RoundedCornerShape(999.dp)
                            )
                        }
                    }

                    if (showShimmer) {
                        ShimmerRow()
                        ShimmerRow()
                        ShimmerRow()
                        ShimmerRow()
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.keystore_diagnostics_keystore_file_label),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            StatusChip(
                                keystoreStatus,
                                if (diagnostics.keystoreSize > 0L) StatusTone.Positive else StatusTone.Negative
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.keystore_diagnostics_credentials_file_label),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            StatusChip(
                                if (diagnostics.credentialsExists) {
                                    stringResource(R.string.keystore_diagnostics_present)
                                } else {
                                    stringResource(R.string.keystore_diagnostics_missing)
                                },
                                if (diagnostics.credentialsExists) StatusTone.Positive else StatusTone.Negative
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.keystore_diagnostics_backup_file_label),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            StatusChip(
                                backupStatus,
                                if ((diagnostics.backupSize ?: 0L) > 0L) StatusTone.Positive else StatusTone.Negative
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.keystore_diagnostics_legacy_file_label),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            StatusChip(
                                legacyStatus,
                                if (legacyPresent) StatusTone.Positive else StatusTone.Neutral
                            )
                        }
                    }
                }
            }

            TextButton(
                onClick = { triggerRefresh() },
                enabled = !isRefreshing,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(stringResource(R.string.keystore_diagnostics_refresh))
            }
        }
    }
}

@Composable
fun KeystoreCredentialsDialog(
    onDismissRequest: () -> Unit,
    onSubmit: (String, String, String) -> Unit
) {
    var alias by rememberSaveable { mutableStateOf("") }
    var storePass by rememberSaveable { mutableStateOf("") }
    var keyPass by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                onClick = {
                    onSubmit(alias, storePass, keyPass)
                }
            ) {
                Text(stringResource(R.string.import_keystore_dialog_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel))
            }
        },
        icon = {
            Icon(Icons.Outlined.Key, null)
        },
        title = {
            Text(
                text = stringResource(R.string.import_keystore_dialog_title),
                style = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center),
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.import_keystore_dialog_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text(stringResource(R.string.import_keystore_dialog_alias_field)) }
                )
                PasswordField(
                    value = storePass,
                    onValueChange = { storePass = it },
                    label = { Text(stringResource(R.string.import_keystore_dialog_password_field)) }
                )
                PasswordField(
                    value = keyPass,
                    onValueChange = { keyPass = it },
                    label = { Text(stringResource(R.string.import_keystore_dialog_key_password_field)) }
                )
            }
        }
    )
}

private data class ExportFileDialogState(
    val picker: ExportPicker,
    val directory: Path,
    val fileName: String
)

private data class PendingExportConfirmation(
    val picker: ExportPicker,
    val directory: Path,
    val fileName: String
)

private enum class ExportPicker(val defaultName: String) {
    Keystore("upm_keystore.keystore"),
    Everything("upm_backup_all.json"),
    PatchBundles("upm_patch_bundles.json"),
    PatchProfiles("upm_patch_profiles.json"),
    ManagerSettings("upm_settings.json"),
    PatchSelection("upm_patch_selection_all.json")
}

private enum class ImportPicker {
    Keystore,
    Everything,
    PatchBundles,
    PatchProfiles,
    ManagerSettings,
    PatchSelection
}

private fun documentMimeForImportPicker(picker: ImportPicker): String =
    when (picker) {
        ImportPicker.Keystore -> "application/octet-stream"
        ImportPicker.PatchBundles,
        ImportPicker.PatchProfiles,
        ImportPicker.ManagerSettings,
        ImportPicker.Everything,
        ImportPicker.PatchSelection -> "application/json"
    }

private fun defaultExportFileName(
    picker: ExportPicker,
    selectionAction: ImportExportViewModel.SelectionAction?,
    selectedBundleDisplayTitle: String?
): String =
    when (picker) {
        ExportPicker.PatchSelection -> when (selectionAction) {
            ImportExportViewModel.SelectionAction.ExportAllBundles -> "upm_patch_selection_all.json"
            ImportExportViewModel.SelectionAction.ExportBundle -> {
                val sanitized = FilenameUtils.sanitize(selectedBundleDisplayTitle.orEmpty().trim())
                if (sanitized.isBlank()) {
                    "upm_patch_selection.json"
                } else {
                    "upm_patch_selection_${sanitized}.json"
                }
            }
            else -> picker.defaultName
        }
        else -> picker.defaultName
    }

private fun isValidImportUri(context: android.content.Context, uri: Uri, picker: ImportPicker): Boolean {
    val valid = when (picker) {
        ImportPicker.Keystore -> uriHasAnyExtension(context, uri, "jks", "keystore", "p12", "pfx", "bks")
        ImportPicker.PatchBundles,
        ImportPicker.PatchProfiles,
        ImportPicker.ManagerSettings,
        ImportPicker.Everything,
        ImportPicker.PatchSelection -> uriHasAnyExtension(context, uri, "json")
    }
    if (!valid) {
        val message = when (picker) {
            ImportPicker.Keystore -> context.getString(R.string.selected_file_not_supported_keystore)
            ImportPicker.PatchBundles,
            ImportPicker.PatchProfiles,
            ImportPicker.ManagerSettings,
            ImportPicker.Everything,
            ImportPicker.PatchSelection -> context.getString(R.string.selected_file_not_supported_json)
        }
        context.toast(message)
    }
    return valid
}

private fun isJsonFile(path: Path): Boolean =
    hasExtension(path, "json")

private fun isKeystoreFile(path: Path): Boolean =
    hasExtension(path, "jks", "keystore", "p12", "pfx", "bks")

private fun hasExtension(path: Path, vararg extensions: String): Boolean {
    val name = path.fileName?.toString()?.lowercase().orEmpty()
    return extensions.any { name.endsWith(".${it.lowercase()}") }
}

private fun uriHasAnyExtension(context: android.content.Context, uri: Uri, vararg extensions: String): Boolean {
    val normalized = extensions.map { it.lowercase() }.toSet()
    val name = DocumentFile.fromSingleUri(context, uri)?.name
        ?: uri.lastPathSegment
        ?: return false
    val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return extension in normalized
}

@Composable
private fun ExportFileNameDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var fileName by rememberSaveable(initialName) { mutableStateOf(initialName) }
    val trimmedName = fileName.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.export),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        icon = {
            Icon(
                Icons.Outlined.Save,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trimmedName) },
                enabled = trimmedName.isNotEmpty()
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.file_name),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    placeholder = { Text(stringResource(R.string.dialog_input_placeholder)) },
                    singleLine = true
                )
            }
        }
    )
}

private const val IMPORTED_SHIZUKU_PERMISSION_REQUEST_CODE = 0x4953









