package app.universal.patch.manager.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import app.universal.patch.manager.ui.component.LazyColumnWithScrollbar
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MergeType
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universal.patch.manager.R
import app.universal.patch.manager.data.platform.Filesystem
import app.universal.patch.manager.data.room.apps.downloaded.DownloadedApp
import app.universal.patch.manager.domain.repository.DownloadedAppRepository
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.domain.manager.SignatureMetadataInjectorStage
import app.universal.patch.manager.domain.manager.SignatureMetadataInjectionMode
import app.universal.patch.manager.domain.manager.SignatureMetadataSigningMode
import app.universal.patch.manager.domain.manager.SignatureMetadataOutputType
import app.universal.patch.manager.domain.manager.SignatureMetadataSplitOutputMode
import app.universal.patch.manager.domain.manager.SignatureMetadataSourceInfo
import app.universal.patch.manager.domain.manager.SignatureMetadataSourceType
import app.universal.patch.manager.domain.manager.SignatureMetadataTargetType
import app.universal.patch.manager.domain.storage.CacheCleanupGuard
import app.universal.patch.manager.ui.component.AlertDialogExtended
import app.universal.patch.manager.ui.component.AppTopBar
import app.universal.patch.manager.ui.component.persistentControls
import app.universal.patch.manager.ui.component.CenteredDialogTitle
import app.universal.patch.manager.ui.component.ConfirmDialog
import app.universal.patch.manager.ui.component.FullscreenDialog
import app.universal.patch.manager.ui.component.ExportSavedApkFileNameDialog
import app.universal.patch.manager.ui.component.RememberedCreateDocument
import app.universal.patch.manager.ui.component.RememberedGetContent
import app.universal.patch.manager.ui.component.TransparentLoadingDialog
import app.universal.patch.manager.ui.component.patcher.InstallerPickerDialog
import app.universal.patch.manager.ui.component.patches.PathSelectorDialog
import app.universal.patch.manager.ui.component.toPickerDirectoryUri
import app.universal.patch.manager.ui.viewmodel.SignatureMetadataInputRole
import app.universal.patch.manager.ui.viewmodel.SignatureMetadataInjectorViewModel
import app.universal.patch.manager.ui.viewmodel.SignatureMetadataSelectionState
import app.universal.patch.manager.util.APK_MIMETYPE
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.Color
import app.universal.patch.manager.ui.component.AppIcon
import app.universal.patch.manager.ui.component.AppLabel
import app.universal.patch.manager.domain.repository.DownloaderPluginRepository
import app.universal.patch.manager.util.AppInfo
import app.universal.patch.manager.util.PM
import app.universal.patch.manager.util.FilenameUtils
import app.universal.patch.manager.util.toast
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun SignatureMetadataInjectorSection(
    viewModel: SignatureMetadataInjectorViewModel = koinViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs: PreferencesManager = koinInject()
    val fs: Filesystem = koinInject()
    val downloadedAppRepository: DownloadedAppRepository = koinInject()
    val pm: PM = koinInject()
    val pluginsRepository: DownloaderPluginRepository = koinInject()
    val downloaderPlugins by pluginsRepository.loadedPluginsFlow
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val downloadedApps by downloadedAppRepository.getAll()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val installedApps by pm.installedAppList
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val state by viewModel.state.collectAsStateWithLifecycle()
    val useCustomFilePicker by prefs.useCustomFilePicker.getAsState()
    val chooseInstallerPerInstall by prefs.chooseInstallerPerInstall.getAsState()
    val splitMergeSortMode by prefs.splitMergeModuleSortMode.getAsState()
    val signatureSourceDirectory by
        prefs.signatureMetadataSourceInputLastDirectory.getAsState()
    val targetApkDirectory by
        prefs.signatureMetadataApkInputLastDirectory.getAsState()
    val exportDirectory by
        prefs.signatureMetadataExportLastDirectory.getAsState()
    val logExportDirectory by
        prefs.signatureMetadataLogExportLastDirectory.getAsState()
    val roots = remember { fs.storageRoots() }
    val (permissionContract, permissionName) = remember { fs.permissionContract() }

    var sourceDialogRole by remember { mutableStateOf<SignatureMetadataInputRole?>(null) }
    var appPicker by remember { mutableStateOf<SignatureMetadataAppPicker?>(null) }
    var pluginRole by remember { mutableStateOf<SignatureMetadataInputRole?>(null) }
    var pluginPackageName by rememberSaveable { mutableStateOf("") }
    var pluginVersion by rememberSaveable { mutableStateOf("") }
    val pluginActivityLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
        viewModel::handlePluginActivityResult
    )
    LaunchedEffect(viewModel) {
        viewModel.pluginActivityFlow.collect { pluginActivityLauncher.launch(it) }
    }
    var customInputRole by rememberSaveable {
        mutableStateOf<SignatureMetadataInputRole?>(null)
    }
    var showOutputPicker by rememberSaveable { mutableStateOf(false) }
    var showLogExportPicker by rememberSaveable { mutableStateOf(false) }
    var pendingLogExportFileName by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingPermission by rememberSaveable {
        mutableStateOf<SignatureMetadataPermissionRequest?>(null)
    }
    var outputDialogState by remember {
        mutableStateOf<SignatureMetadataSaveDialogState?>(null)
    }
    var pendingOutputOverwrite by remember {
        mutableStateOf<SignatureMetadataSaveDialogState?>(null)
    }
    var logExportDialogState by remember {
        mutableStateOf<SignatureMetadataLogExportDialogState?>(null)
    }
    var pendingLogOverwrite by remember {
        mutableStateOf<SignatureMetadataLogExportDialogState?>(null)
    }
    var saveError by rememberSaveable { mutableStateOf<String?>(null) }
    var showInstallerPicker by rememberSaveable { mutableStateOf(false) }
    val logScrollState = rememberScrollState()

    fun defaultOutputName(): String {
        val baseName = state.targetApk.displayName
            ?.substringBeforeLast('.')
            ?.ifBlank { "target" }
            ?: "target"
        val splitContainer = state.result?.outputType ==
            SignatureMetadataOutputType.SPLIT_APK_CONTAINER ||
            (state.hasSplitTarget && !state.mergeSplitTarget)
        val splitExtension = state.targetApk.displayName
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase(Locale.ROOT)
            ?.takeIf(SIGNATURE_METADATA_SPLIT_EXTENSIONS::contains)
            ?: "apks"
        val extension = if (splitContainer) ".$splitExtension" else ".apk"
        return sanitizeSignatureMetadataFileName(baseName) +
            "-metadata-injected$extension"
    }

    fun acceptInput(
        role: SignatureMetadataInputRole,
        uri: Uri,
        fallbackName: String?
    ) {
        val displayName = querySignatureMetadataDisplayName(context, uri)
            ?: fallbackName
            ?: uri.lastPathSegment
            ?: "input"
        val mimeType = context.contentResolver.getType(uri)
        val supported = when (role) {
            SignatureMetadataInputRole.SIGNATURE_SOURCE ->
                isSignatureMetadataSourceInput(displayName, mimeType)
            SignatureMetadataInputRole.TARGET_APK ->
                isSignatureMetadataTargetInput(displayName, mimeType)
        }
        if (!supported) {
            val message = when (role) {
                SignatureMetadataInputRole.SIGNATURE_SOURCE ->
                    R.string.tools_signature_metadata_injector_invalid_zip
                SignatureMetadataInputRole.TARGET_APK ->
                    R.string.tools_signature_metadata_injector_invalid_target
            }
            context.toast(context.getString(message))
            return
        }
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        viewModel.select(role, uri, displayName)
    }

    val permissionLauncher = rememberLauncherForActivityResult(permissionContract) { granted ->
        val request = pendingPermission
        pendingPermission = null
        if (!granted) {
            if (request == SignatureMetadataPermissionRequest.LOG_EXPORT) {
                pendingLogExportFileName = null
            }
            return@rememberLauncherForActivityResult
        }
        when (request) {
            SignatureMetadataPermissionRequest.SOURCE ->
                customInputRole = SignatureMetadataInputRole.SIGNATURE_SOURCE
            SignatureMetadataPermissionRequest.APK ->
                customInputRole = SignatureMetadataInputRole.TARGET_APK
            SignatureMetadataPermissionRequest.OUTPUT -> showOutputPicker = true
            SignatureMetadataPermissionRequest.LOG_EXPORT -> showLogExportPicker = true
            null -> Unit
        }
    }

    val signatureSourceDocumentLauncher = rememberLauncherForActivityResult(
        RememberedGetContent {
            signatureSourceDirectory.takeIf(String::isNotBlank)?.let(Uri::parse)
        }
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            prefs.signatureMetadataSourceInputLastDirectory.update(
                uri.toPickerDirectoryUri().toString()
            )
        }
        acceptInput(SignatureMetadataInputRole.SIGNATURE_SOURCE, uri, null)
    }

    val targetApkDocumentLauncher = rememberLauncherForActivityResult(
        RememberedGetContent {
            targetApkDirectory.takeIf(String::isNotBlank)?.let(Uri::parse)
        }
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            prefs.signatureMetadataApkInputLastDirectory.update(
                uri.toPickerDirectoryUri().toString()
            )
        }
        acceptInput(SignatureMetadataInputRole.TARGET_APK, uri, null)
    }

    val saveDocumentLauncher = rememberLauncherForActivityResult(
        RememberedCreateDocument("application/octet-stream") {
            exportDirectory.takeIf(String::isNotBlank)?.let(Uri::parse)
        }
    ) { uri ->
        val output = state.result?.outputFile ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            prefs.signatureMetadataExportLastDirectory.update(
                uri.toPickerDirectoryUri().toString()
            )
            runCatching {
                CacheCleanupGuard.withCacheInUse {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { destination ->
                            output.inputStream().use { source -> source.copyTo(destination) }
                        } ?: throw IOException("Unable to open export destination.")
                    }
                }
            }.onSuccess {
                saveError = null
                context.toast(
                    context.getString(R.string.tools_signature_metadata_injector_saved)
                )
            }.onFailure { error ->
                saveError = error.message
                    ?: context.getString(
                        R.string.tools_signature_metadata_injector_save_failed
                    )
            }
        }
    }

    val logExportDocumentLauncher = rememberLauncherForActivityResult(
        RememberedCreateDocument("text/plain") {
            logExportDirectory.takeIf(String::isNotBlank)?.let(Uri::parse)
        }
    ) { uri ->
        if (uri != null) {
            scope.launch {
                prefs.signatureMetadataLogExportLastDirectory.update(
                    uri.toPickerDirectoryUri().toString()
                )
            }
            viewModel.exportLogsToUri(uri)
        }
        pendingLogExportFileName = null
    }

    fun requestInput(role: SignatureMetadataInputRole) {
        if (state.working) return
        if (useCustomFilePicker) {
            if (fs.hasStoragePermission()) {
                customInputRole = role
            } else {
                pendingPermission = when (role) {
                    SignatureMetadataInputRole.SIGNATURE_SOURCE ->
                        SignatureMetadataPermissionRequest.SOURCE
                    SignatureMetadataInputRole.TARGET_APK ->
                        SignatureMetadataPermissionRequest.APK
                }
                permissionLauncher.launch(permissionName)
            }
        } else {
            when (role) {
                SignatureMetadataInputRole.SIGNATURE_SOURCE ->
                    signatureSourceDocumentLauncher.launch("application/*")
                SignatureMetadataInputRole.TARGET_APK ->
                    targetApkDocumentLauncher.launch("application/*")
            }
        }
    }

    fun requestSave() {
        if (state.result == null) return
        saveError = null
        if (useCustomFilePicker) {
            if (fs.hasStoragePermission()) {
                showOutputPicker = true
            } else {
                pendingPermission = SignatureMetadataPermissionRequest.OUTPUT
                permissionLauncher.launch(permissionName)
            }
        } else {
            saveDocumentLauncher.launch(defaultOutputName())
        }
    }

    fun saveOutput(directory: Path, fileName: String) {
        val output = state.result?.outputFile ?: return
        outputDialogState = null
        pendingOutputOverwrite = null
        showOutputPicker = false
        scope.launch {
            runCatching {
                CacheCleanupGuard.withCacheInUse {
                    withContext(Dispatchers.IO) {
                        Files.copy(
                            output.toPath(),
                            directory.resolve(fileName),
                            StandardCopyOption.REPLACE_EXISTING
                        )
                    }
                }
            }.onSuccess {
                saveError = null
                prefs.signatureMetadataExportLastDirectory.update(directory.toString())
                context.toast(
                    context.getString(R.string.tools_signature_metadata_injector_saved)
                )
            }.onFailure { error ->
                saveError = error.message
                    ?: context.getString(
                        R.string.tools_signature_metadata_injector_save_failed
                    )
            }
        }
    }

    fun copyLog() {
        if (state.logEntries.isEmpty()) return
        scope.launch {
            val content = viewModel.getLogContent()
            val clipboard = context.getSystemService(ClipboardManager::class.java)
                ?: return@launch
            clipboard.setPrimaryClip(
                ClipData.newPlainText(
                    "Signature metadata injector log",
                    content
                )
            )
            context.toast(
                context.getString(
                    R.string.tools_signature_metadata_injector_log_copy_success
                )
            )
        }
    }

    fun requestLogExport() {
        if (state.logEntries.isEmpty()) return
        val fileName = FilenameUtils.timestampedLogFileName(
            "signature-metadata-injector"
        )
        pendingLogExportFileName = fileName
        if (useCustomFilePicker) {
            if (fs.hasStoragePermission()) {
                showLogExportPicker = true
            } else {
                pendingPermission = SignatureMetadataPermissionRequest.LOG_EXPORT
                permissionLauncher.launch(permissionName)
            }
        } else {
            logExportDocumentLauncher.launch(fileName)
        }
    }

    fun exportLog(directory: Path, fileName: String) {
        logExportDialogState = null
        pendingLogOverwrite = null
        pendingLogExportFileName = null
        viewModel.exportLogsToPath(directory.resolve(fileName)) { success ->
            if (success) {
                showLogExportPicker = false
                scope.launch {
                    prefs.signatureMetadataLogExportLastDirectory.update(
                        directory.toString()
                    )
                }
            }
        }
    }

    fun requestInstall() {
        if (state.result == null || state.working) return
        if (chooseInstallerPerInstall) {
            showInstallerPicker = true
        } else {
            viewModel.install()
        }
    }

    customInputRole?.let { role ->
        if (useCustomFilePicker) {
            PathSelectorDialog(
                roots = roots,
                onSelect = { path ->
                    if (path == null) {
                        customInputRole = null
                        return@PathSelectorDialog
                    }
                    val supported = !Files.isDirectory(path) && when (role) {
                        SignatureMetadataInputRole.SIGNATURE_SOURCE ->
                            isSignatureMetadataSourceFile(path)
                        SignatureMetadataInputRole.TARGET_APK ->
                            isSignatureMetadataTargetFile(path)
                    }
                    if (!supported) return@PathSelectorDialog
                    customInputRole = null
                    acceptInput(
                        role,
                        Uri.fromFile(path.toFile()),
                        path.fileName?.toString()
                    )
                },
                fileFilter = { path ->
                    when (role) {
                        SignatureMetadataInputRole.SIGNATURE_SOURCE ->
                            isSignatureMetadataSourceFile(path)
                        SignatureMetadataInputRole.TARGET_APK ->
                            isSignatureMetadataTargetFile(path)
                    }
                },
                allowDirectorySelection = false,
                fileTypeLabel = when (role) {
                    SignatureMetadataInputRole.SIGNATURE_SOURCE ->
                        ".apk, .apks, .xapk, .apkm, .zip"
                    SignatureMetadataInputRole.TARGET_APK ->
                        ".apk, .apks, .xapk, .apkm, .zip"
                },
                lastDirectoryPreference = when (role) {
                    SignatureMetadataInputRole.SIGNATURE_SOURCE ->
                        prefs.signatureMetadataSourceInputLastDirectory
                    SignatureMetadataInputRole.TARGET_APK ->
                        prefs.signatureMetadataApkInputLastDirectory
                }
            )
        }
    }

    if (showOutputPicker && useCustomFilePicker) {
        PathSelectorDialog(
            roots = roots,
            onSelect = { path -> if (path == null) showOutputPicker = false },
            fileFilter = { false },
            allowDirectorySelection = true,
            confirmButtonText = stringResource(R.string.save),
            onConfirm = { selection ->
                val directory = if (Files.isDirectory(selection)) {
                    selection
                } else {
                    selection.parent ?: selection
                }
                outputDialogState = SignatureMetadataSaveDialogState(
                    directory,
                    defaultOutputName()
                )
            },
            lastDirectoryPreference = prefs.signatureMetadataExportLastDirectory
        )
    }

    outputDialogState?.let { dialogState ->
        ExportSavedApkFileNameDialog(
            initialName = dialogState.fileName,
            onDismiss = { outputDialogState = null },
            onConfirm = { enteredName ->
                val finalName = normalizeSignatureMetadataFileName(
                    value = enteredName.trim().ifBlank { dialogState.fileName },
                    splitContainer = state.result?.outputType ==
                        SignatureMetadataOutputType.SPLIT_APK_CONTAINER,
                    splitExtension = state.result?.outputFile?.extension
                        ?.lowercase(Locale.ROOT)
                        ?.takeIf(SIGNATURE_METADATA_SPLIT_EXTENSIONS::contains)
                        ?: "apks"
                )
                outputDialogState = null
                if (Files.exists(dialogState.directory.resolve(finalName))) {
                    pendingOutputOverwrite = SignatureMetadataSaveDialogState(
                        directory = dialogState.directory,
                        fileName = finalName
                    )
                } else {
                    saveOutput(dialogState.directory, finalName)
                }
            }
        )
    }

    pendingOutputOverwrite?.let { overwrite ->
        ConfirmDialog(
            onDismiss = {
                pendingOutputOverwrite = null
                outputDialogState = overwrite
            },
            onConfirm = {
                saveOutput(overwrite.directory, overwrite.fileName)
            },
            title = stringResource(R.string.export_overwrite_title),
            description = stringResource(
                R.string.export_overwrite_description,
                overwrite.fileName
            ),
            icon = Icons.Outlined.WarningAmber
        )
    }

    if (showLogExportPicker && useCustomFilePicker) {
        PathSelectorDialog(
            roots = roots,
            onSelect = { selection ->
                if (selection == null) {
                    showLogExportPicker = false
                    pendingLogExportFileName = null
                }
            },
            fileFilter = { false },
            allowDirectorySelection = true,
            fileTypeLabel = ".txt",
            confirmButtonText = stringResource(R.string.save),
            onConfirm = { selection ->
                val directory = if (Files.isDirectory(selection)) {
                    selection
                } else {
                    selection.parent ?: selection
                }
                logExportDialogState = SignatureMetadataLogExportDialogState(
                    directory = directory,
                    fileName = pendingLogExportFileName
                        ?: FilenameUtils.timestampedLogFileName(
                            "signature-metadata-injector"
                        )
                )
            },
            lastDirectoryPreference =
                prefs.signatureMetadataLogExportLastDirectory
        )
    }

    logExportDialogState?.let { dialogState ->
        ExportSavedApkFileNameDialog(
            initialName = dialogState.fileName,
            onDismiss = {
                logExportDialogState = null
                pendingLogExportFileName = null
            },
            onConfirm = { enteredName ->
                val finalName = normalizeSignatureMetadataLogFileName(
                    enteredName.trim().ifBlank { dialogState.fileName }
                )
                logExportDialogState = null
                pendingLogExportFileName = null
                if (Files.exists(dialogState.directory.resolve(finalName))) {
                    pendingLogOverwrite = SignatureMetadataLogExportDialogState(
                        directory = dialogState.directory,
                        fileName = finalName
                    )
                } else {
                    exportLog(dialogState.directory, finalName)
                }
            }
        )
    }

    pendingLogOverwrite?.let { overwrite ->
        ConfirmDialog(
            onDismiss = {
                pendingLogOverwrite = null
                logExportDialogState = overwrite
            },
            onConfirm = {
                exportLog(overwrite.directory, overwrite.fileName)
            },
            title = stringResource(R.string.export_overwrite_title),
            description = stringResource(
                R.string.export_overwrite_description,
                overwrite.fileName
            ),
            icon = Icons.Outlined.WarningAmber
        )
    }

    if (showInstallerPicker) {
        InstallerPickerDialog(
            title = stringResource(R.string.installer_choose_for_this_install_title),
            options = viewModel.installerOptions(),
            onDismiss = { showInstallerPicker = false },
            onConfirm = viewModel::install,
            onOpenShizuku = viewModel::openShizuku
        )
    }

    if (state.rootDowngradeConfirmationPending) {
        AlertDialog(
            onDismissRequest = viewModel::dismissRootDowngradeConfirmation,
            confirmButton = {
                TextButton(onClick = viewModel::confirmRootDowngrade) {
                    Text(stringResource(R.string.continue_))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissRootDowngradeConfirmation) {
                    Text(stringResource(R.string.cancel))
                }
            },
            title = { Text(stringResource(R.string.root_mount_downgrade_title)) },
            text = { Text(stringResource(R.string.root_mount_downgrade_confirmation)) }
        )
    }

    LaunchedEffect(useCustomFilePicker) {
        if (!useCustomFilePicker) {
            customInputRole = null
            showOutputPicker = false
            showLogExportPicker = false
            outputDialogState = null
            pendingOutputOverwrite = null
            logExportDialogState = null
            pendingLogOverwrite = null
            pendingLogExportFileName = null
            pendingPermission = null
        }
    }
    LaunchedEffect(state.result?.outputFile) {
        saveError = null
    }

    if (state.preparingSplitSelection) {
        TransparentLoadingDialog(
            message = stringResource(R.string.patcher_preparing_split_selection),
            cancelButtonText = stringResource(R.string.cancel),
            onCancel = viewModel::cancelSplitSelectionPreparation
        )
    }

    state.splitSelection?.let { selection ->
        val allModules = selection.modules.mapTo(linkedSetOf()) { it.name }
        SplitMergeSelectionDialog(
            selection = selection,
            initialModules = state.includedSplitModules ?: allModules,
            initialStripNativeLibs = false,
            showStripNativeLibsOption = false,
            initialPresetKey = if (state.includedSplitModules == null) "all" else "custom",
            initialSortMode = SplitMergeModuleSortMode.fromStorage(splitMergeSortMode),
            confirmTextRes = R.string.continue_,
            onDismissRequest = viewModel::dismissSplitSelection,
            onFilterSelectionChanged = { _, _, _, _ -> },
            onSortModeChanged = { mode ->
                scope.launch {
                    prefs.splitMergeModuleSortMode.update(mode.storageValue)
                }
            },
            onConfirm = { modules, _ ->
                viewModel.confirmSplitSelection(modules)
            }
        )
    }

    state.splitSelectionError?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissSplitSelectionError,
            title = {
                Text(
                    stringResource(
                        R.string.tools_signature_metadata_injector_split_selection_failed
                    )
                )
            },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissSplitSelectionError) {
                    Text(stringResource(R.string.confirm))
                }
            }
        )
    }

    sourceDialogRole?.let { role ->
        SignatureMetadataInputSourceDialog(
            title = stringResource(
                if (role == SignatureMetadataInputRole.SIGNATURE_SOURCE)
                    R.string.tools_signature_metadata_injector_select_zip
                else R.string.tools_signature_metadata_injector_select_apk
            ),
            onPlugin = {
                sourceDialogRole = null
                pluginRole = role
            },
            onDismiss = { sourceDialogRole = null },
            onStorage = {
                sourceDialogRole = null
                requestInput(role)
            },
            onDownloaded = {
                sourceDialogRole = null
                appPicker = SignatureMetadataAppPicker(role, installed = false)
            },
            onInstalled = {
                sourceDialogRole = null
                appPicker = SignatureMetadataAppPicker(role, installed = true)
            }
        )
    }
    pluginRole?.let { role ->
        DownloaderPluginInputDialog(
            plugins = downloaderPlugins,
            activePluginId = null,
            packageName = pluginPackageName,
            version = pluginVersion,
            onPackageNameChange = { pluginPackageName = it },
            onVersionChange = { pluginVersion = it },
            onDismissRequest = { pluginRole = null },
            onSelectPlugin = { plugin ->
                pluginRole = null
                viewModel.selectPlugin(role, plugin, pluginPackageName, pluginVersion)
            }
        )
    }
    if (state.signatureSource.analyzing || state.targetApk.analyzing) {
        TransparentLoadingDialog(
            message = stringResource(R.string.tools_signature_input_preparing),
            cancelButtonText = stringResource(R.string.cancel),
            onCancel = viewModel::cancelInputSelection
        )
    }
    appPicker?.let { picker ->
        SignatureMetadataAppPickerDialog(
            picker = picker,
            downloadedApps = downloadedApps,
            installedApps = installedApps,
            pm = pm,
            onDismiss = { appPicker = null },
            onDownloadedSelected = { downloaded ->
                val source = runCatching {
                    downloadedAppRepository.getApkFileForApp(downloaded)
                }.getOrNull()
                if (source?.isFile != true) {
                    context.toast(context.getString(R.string.tools_signature_input_unavailable))
                } else {
                    appPicker = null
                    acceptInput(
                        picker.role,
                        Uri.fromFile(source),
                        "${downloaded.packageName}-${downloaded.version}.${source.extension}"
                    )
                }
            },
            onInstalledSelected = { selected ->
                appPicker = null
                viewModel.selectInstalled(
                    picker.role,
                    selected.packageName,
                    selected.packageInfo?.let(pm::hasSplitApks) == true
                )
            }
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.SwapVert,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Text(
                stringResource(R.string.tools_signature_metadata_injector_title),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge
            )
        }

        SignatureMetadataMessageCard(
            text = stringResource(R.string.tools_signature_metadata_injector_info),
            icon = Icons.Outlined.Info
        )

        Button(
            onClick = { sourceDialogRole = SignatureMetadataInputRole.SIGNATURE_SOURCE },
            enabled = !state.working,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Outlined.FolderOpen, null)
            Text(
                stringResource(R.string.tools_signature_metadata_injector_select_zip),
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        if (state.signatureSource.displayName != null) {
            SignatureMetadataSelectionCard(
                label = stringResource(
                    R.string.tools_signature_metadata_injector_zip_label
                ),
                selection = state.signatureSource,
                icon = Icons.Outlined.Description,
                details = state.signatureSource.sourceInfo
                    ?.let { info -> signatureMetadataSourceDetails(context, info) }
                    .orEmpty()
            )
        }

        Button(
            onClick = { sourceDialogRole = SignatureMetadataInputRole.TARGET_APK },
            enabled = !state.working,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Outlined.FolderOpen, null)
            Text(
                stringResource(R.string.tools_signature_metadata_injector_select_apk),
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        if (state.targetApk.displayName != null) {
            SignatureMetadataSelectionCard(
                label = stringResource(
                    R.string.tools_signature_metadata_injector_apk_label
                ),
                selection = state.targetApk,
                icon = Icons.Outlined.Android,
                details = state.targetApk.targetInfo?.let { target ->
                    buildList {
                        val info = target.apkInfo
                        add(info.packageName)
                        add(
                            context.getString(
                                R.string.tools_signature_metadata_injector_version,
                                info.versionName ?: "—",
                                info.versionCode
                            )
                        )
                        if (
                            target.targetType ==
                            SignatureMetadataTargetType.SPLIT_APK_CONTAINER
                        ) {
                            add(
                                context.resources.getQuantityString(
                                    R.plurals.tools_signature_metadata_injector_target_apk_count,
                                    target.apkEntryCount,
                                    target.apkEntryCount
                                )
                            )
                        }
                    }
                }.orEmpty()
            )
        }

        SignatureMetadataSplitOutputOption(
            hasSplitTarget = state.hasSplitTarget,
            mergeSplits = state.hasSplitTarget && state.mergeSplitTarget,
            enabled = !state.working && state.hasSplitTarget,
            selectedSplitCount = state.includedSplitModules?.size,
            totalSplitCount = state.targetApk.targetInfo?.apkEntryCount ?: 0,
            onMergeSplitsChanged = { mergeSplits ->
                viewModel.selectSplitOutputMode(
                    if (mergeSplits) {
                        SignatureMetadataSplitOutputMode.MERGED_APK
                    } else {
                        SignatureMetadataSplitOutputMode.SPLIT_APK_CONTAINER
                    }
                )
            },
            onChooseSplits = viewModel::prepareSplitSelection
        )

        Text(
            text = stringResource(R.string.tools_signature_metadata_injector_mode_title),
            style = MaterialTheme.typography.titleSmall
        )
        SignatureMetadataModeOption(
            selected = state.injectionMode ==
                SignatureMetadataInjectionMode.REPLACE_EXISTING,
            title = stringResource(
                R.string.tools_signature_metadata_injector_mode_replace_title
            ),
            description = stringResource(
                R.string.tools_signature_metadata_injector_mode_replace_description
            ),
            enabled = !state.working,
            signingMode = if (state.signingSelectionEnabled) {
                state.signingModes[SignatureMetadataInjectionMode.REPLACE_EXISTING]
            } else {
                state.selectedSigningMode
            },
            signingEnabled = !state.working &&
                state.signingSelectionEnabled &&
                state.injectionMode == SignatureMetadataInjectionMode.REPLACE_EXISTING,
            automaticSignatureCloning =
                state.signatureSource.sourceInfo
                    ?.sourceType
                    ?.usesAutomaticSignatureCloning == true,
            onClick = {
                viewModel.selectInjectionMode(
                    SignatureMetadataInjectionMode.REPLACE_EXISTING
                )
            },
            onSigningModeSelected = { signingMode ->
                viewModel.selectSigningMode(
                    SignatureMetadataInjectionMode.REPLACE_EXISTING,
                    signingMode
                )
            }
        )
        SignatureMetadataModeOption(
            selected = state.injectionMode ==
                SignatureMetadataInjectionMode.ADD_ALONGSIDE,
            title = stringResource(
                R.string.tools_signature_metadata_injector_mode_add_title
            ),
            description = stringResource(
                R.string.tools_signature_metadata_injector_mode_add_description
            ),
            enabled = !state.working,
            signingMode = if (state.signingSelectionEnabled) {
                state.signingModes[SignatureMetadataInjectionMode.ADD_ALONGSIDE]
            } else {
                state.selectedSigningMode
            },
            signingEnabled = !state.working &&
                state.signingSelectionEnabled &&
                state.injectionMode == SignatureMetadataInjectionMode.ADD_ALONGSIDE,
            automaticSignatureCloning =
                state.signatureSource.sourceInfo
                    ?.sourceType
                    ?.usesAutomaticSignatureCloning == true,
            onClick = {
                viewModel.selectInjectionMode(
                    SignatureMetadataInjectionMode.ADD_ALONGSIDE
                )
            },
            onSigningModeSelected = { signingMode ->
                viewModel.selectSigningMode(
                    SignatureMetadataInjectionMode.ADD_ALONGSIDE,
                    signingMode
                )
            }
        )

        Button(
            onClick = { viewModel.inject(defaultOutputName()) },
            enabled = state.canInject,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            if (state.injecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
                Text(
                    signatureMetadataStageText(state.progress?.stage),
                    modifier = Modifier.padding(start = 8.dp)
                )
            } else {
                Icon(Icons.Outlined.SwapVert, null)
                Text(
                    stringResource(R.string.tools_signature_metadata_injector_action),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }

        if (state.injecting) {
            OutlinedButton(
                onClick = viewModel::cancelInjection,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.cancel))
            }
        }

        if (state.logEntries.isNotEmpty()) {
            SignatureMetadataRawLog(
                entries = state.logEntries,
                logRevision = state.logRevision,
                logSessionId = state.logSessionId,
                scrollState = logScrollState,
                showActions = state.result != null || state.error != null,
                onCopy = ::copyLog,
                onExport = ::requestLogExport
            )
        }

        state.result?.let { result ->
            SignatureMetadataResultCard(
                fileName = result.outputFile.name,
                packageName = result.packageName,
                injectedEntries = result.injectedEntries,
                skippedEntries = result.skippedEntries,
                signingMode = result.signingMode,
                suppliedSigningBlockApplied = result.suppliedSigningBlockApplied,
                installing = state.installing,
                installStatus = state.installStatus,
                onInstall = ::requestInstall,
                onSave = ::requestSave
            )
        }

        (state.error ?: saveError)?.let { error ->
            SignatureMetadataMessageCard(
                text = error,
                icon = Icons.Outlined.WarningAmber,
                error = true
            )
        }
    }
}

@Composable
private fun SignatureMetadataSplitOutputOption(
    hasSplitTarget: Boolean,
    mergeSplits: Boolean,
    enabled: Boolean,
    selectedSplitCount: Int?,
    totalSplitCount: Int,
    onMergeSplitsChanged: (Boolean) -> Unit,
    onChooseSplits: () -> Unit
) {
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    val description = when {
        !hasSplitTarget ->
            stringResource(
                R.string.tools_signature_metadata_injector_split_output_unavailable
            )
        mergeSplits ->
            stringResource(
                R.string.tools_signature_metadata_injector_split_output_merge_description
            )
        else ->
            stringResource(
                R.string.tools_signature_metadata_injector_split_output_preserve_description
            )
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.MergeType,
                    contentDescription = null,
                    tint = contentColor
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = stringResource(
                            R.string.tools_signature_metadata_injector_split_output_title
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        color = contentColor
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(
                            alpha = if (enabled) 0.75f else contentColor.alpha
                        )
                    )
                }
                Switch(
                    checked = mergeSplits,
                    onCheckedChange = onMergeSplitsChanged,
                    enabled = enabled
                )
            }
            if (hasSplitTarget && mergeSplits) {
                val selectionSummary = if (selectedSplitCount == null) {
                    stringResource(
                        R.string.tools_signature_metadata_injector_split_output_all,
                        totalSplitCount
                    )
                } else {
                    stringResource(
                        R.string.tools_signature_metadata_injector_split_output_selected,
                        selectedSplitCount,
                        totalSplitCount
                    )
                }
                OutlinedButton(
                    onClick = onChooseSplits,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(
                            R.string.tools_signature_metadata_injector_split_output_choose
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = selectionSummary,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

private data class SignatureMetadataAppPicker(
    val role: SignatureMetadataInputRole,
    val installed: Boolean
)

@Composable
private fun SignatureMetadataInputSourceDialog(
    title: String,
    onPlugin: () -> Unit,
    onDismiss: () -> Unit,
    onStorage: () -> Unit,
    onDownloaded: () -> Unit,
    onInstalled: () -> Unit
) {
    AlertDialogExtended(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        title = {
            CenteredDialogTitle(title)
        },
        textHorizontalPadding = PaddingValues(horizontal = 0.dp),
        text = {
            Column {
                SignatureMetadataInputSourceOption(
                    stringResource(R.string.tools_merge_split_source_plugin_title),
                    stringResource(R.string.tools_signature_input_plugin_description),
                    onPlugin
                )
                SignatureMetadataInputSourceOption(
                    stringResource(R.string.downloaded_apps),
                    stringResource(R.string.tools_signature_input_downloaded_description),
                    onDownloaded
                )
                SignatureMetadataInputSourceOption(
                    stringResource(R.string.tools_signature_input_installed),
                    stringResource(R.string.tools_signature_input_installed_description),
                    onInstalled
                )
                SignatureMetadataInputSourceOption(
                    stringResource(R.string.tools_signature_input_storage),
                    stringResource(R.string.tools_signature_input_storage_description),
                    onStorage
                )
            }
        }
    )
}

@Composable
private fun SignatureMetadataInputSourceOption(
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = Color.Transparent
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignatureMetadataAppPickerDialog(
    picker: SignatureMetadataAppPicker,
    downloadedApps: List<DownloadedApp>,
    installedApps: List<AppInfo>,
    pm: PM,
    onDismiss: () -> Unit,
    onDownloadedSelected: (DownloadedApp) -> Unit,
    onInstalledSelected: (AppInfo) -> Unit
) {
    var filterText by remember(picker) { mutableStateOf("") }
    val roleTitle = stringResource(
        if (picker.role == SignatureMetadataInputRole.SIGNATURE_SOURCE) {
            R.string.tools_signature_metadata_injector_select_zip
        } else {
            R.string.tools_signature_metadata_injector_select_apk
        }
    )
    val sourceTitle = stringResource(
        if (picker.installed) R.string.tools_signature_input_installed
        else R.string.downloaded_apps
    )
    val filteredDownloads = remember(downloadedApps, filterText) {
        downloadedApps.filter {
            filterText.isBlank() ||
                it.packageName.contains(filterText, ignoreCase = true) ||
                it.version.contains(filterText, ignoreCase = true)
        }.sortedWith(compareBy<DownloadedApp> { it.packageName }.thenByDescending { it.version })
    }
    val filteredInstalled = remember(installedApps, filterText) {
        installedApps.filter { app ->
            val label = app.packageInfo?.let { pm.run { it.label() } }.orEmpty()
            filterText.isBlank() ||
                app.packageName.contains(filterText, ignoreCase = true) ||
                label.contains(filterText, ignoreCase = true)
        }
    }

    FullscreenDialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Scaffold(
            topBar = {
                AppTopBar(
                    title = "$roleTitle · $sourceTitle",
                    onBackClick = onDismiss
                )
            }
        ) { padding ->
            LazyColumnWithScrollbar(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                persistentControls(key = "signature-app-search") {
                    TextField(
                        value = filterText,
                        onValueChange = { filterText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.search_apps)) },
                        singleLine = true
                    )
                }
                if (picker.installed) {
                    if (filteredInstalled.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.tools_signature_input_no_apps),
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                    items(filteredInstalled, key = { it.packageName }) { app ->
                        val packageInfo = app.packageInfo ?: return@items
                        Surface(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { onInstalledSelected(app) },
                            shape = RoundedCornerShape(20.dp),
                            tonalElevation = 2.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
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
                                        defaultText = app.packageName,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        app.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        packageInfo.versionName.orEmpty() +
                                            if (pm.hasSplitApks(packageInfo)) " · " + stringResource(R.string.tools_signature_input_split_apks) else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                } else {
                    if (filteredDownloads.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.tools_signature_input_no_apps),
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                    items(
                        filteredDownloads,
                        key = { "${it.packageName}:${it.version}" }
                    ) { app ->
                        Surface(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { onDownloadedSelected(app) },
                            shape = RoundedCornerShape(20.dp),
                            tonalElevation = 2.dp
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    app.packageName,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    app.version,
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
}

@Composable
private fun SignatureMetadataModeOption(
    selected: Boolean,
    title: String,
    description: String,
    enabled: Boolean,
    signingMode: SignatureMetadataSigningMode?,
    signingEnabled: Boolean,
    automaticSignatureCloning: Boolean,
    onClick: () -> Unit,
    onSigningModeSelected: (SignatureMetadataSigningMode) -> Unit
) {
    var signingMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(signingEnabled) {
        if (!signingEnabled) signingMenuExpanded = false
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                RadioButton(
                    selected = selected,
                    onClick = null,
                    enabled = enabled
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                stringResource(
                    if (automaticSignatureCloning) {
                        R.string
                            .tools_signature_metadata_injector_signing_automatic_label
                    } else {
                        R.string.tools_signature_metadata_injector_signing_label
                    }
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { signingMenuExpanded = true },
                    enabled = signingEnabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (automaticSignatureCloning) {
                            stringResource(
                                R.string
                                    .tools_signature_metadata_injector_signing_clone_automatic
                            )
                        } else {
                            signatureMetadataSigningModeLabel(signingMode)
                        },
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(Icons.Outlined.KeyboardArrowDown, null)
                }
                DropdownMenu(
                    expanded = signingMenuExpanded,
                    onDismissRequest = { signingMenuExpanded = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SignatureMetadataSigningMode.entries.forEach { option ->
                        DropdownMenuItem(
                            text = {
                                Text(signatureMetadataSigningModeLabel(option))
                            },
                            onClick = {
                                signingMenuExpanded = false
                                onSigningModeSelected(option)
                            }
                        )
                    }
                }
            }
            if (automaticSignatureCloning) {
                Text(
                    stringResource(
                        R.string
                            .tools_signature_metadata_injector_signing_clone_automatic_description
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                signingMode?.let { selectedSigningMode ->
                    Text(
                        signatureMetadataSigningModeDescription(selectedSigningMode),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun signatureMetadataSigningModeLabel(
    signingMode: SignatureMetadataSigningMode?
): String = when (signingMode) {
    SignatureMetadataSigningMode.DONT_SIGN ->
        stringResource(R.string.tools_signature_metadata_injector_signing_dont_sign)
    SignatureMetadataSigningMode.SIGN ->
        stringResource(R.string.tools_signature_metadata_injector_signing_sign)
    SignatureMetadataSigningMode.APPLY_SUPPLIED_SIGNATURE ->
        stringResource(R.string.tools_signature_metadata_injector_signing_apply_supplied)
    null -> stringResource(R.string.tools_signature_metadata_injector_signing_select)
}

@Composable
private fun signatureMetadataSigningModeDescription(
    signingMode: SignatureMetadataSigningMode
): String = when (signingMode) {
    SignatureMetadataSigningMode.DONT_SIGN ->
        stringResource(
            R.string.tools_signature_metadata_injector_signing_dont_sign_description
        )
    SignatureMetadataSigningMode.SIGN ->
        stringResource(
            R.string.tools_signature_metadata_injector_signing_sign_description
        )
    SignatureMetadataSigningMode.APPLY_SUPPLIED_SIGNATURE ->
        stringResource(
            R.string.tools_signature_metadata_injector_signing_apply_supplied_description
        )
}

@Composable
internal fun SignatureMetadataRawLog(
    entries: List<String>,
    logRevision: Long,
    logSessionId: Long,
    scrollState: ScrollState,
    showActions: Boolean,
    onCopy: () -> Unit,
    onExport: () -> Unit
) {
    var followLatest by remember(logSessionId) { mutableStateOf(true) }
    var logExpanded by rememberSaveable(logSessionId) { mutableStateOf(true) }
    val latestLogRevision by rememberUpdatedState(logRevision)
    val hasLogEntries by rememberUpdatedState(entries.isNotEmpty())
    val isLogDragged by scrollState.interactionSource.collectIsDraggedAsState()
    val showJumpToLatest by remember(scrollState, logSessionId) {
        derivedStateOf { !followLatest && scrollState.canScrollForward }
    }

    LaunchedEffect(scrollState, logSessionId) {
        snapshotFlow {
            isLogDragged to !scrollState.canScrollForward
        }.collect { (isDragged, isAtLatest) ->
            when {
                isDragged -> followLatest = false
                isAtLatest -> followLatest = true
            }
        }
    }

    LaunchedEffect(scrollState, logSessionId) {
        snapshotFlow { Triple(latestLogRevision, followLatest, logExpanded) }
            .collect { (_, shouldFollowLatest, expanded) ->
                if (shouldFollowLatest && expanded && hasLogEntries) {
                    withFrameNanos { }
                    scrollState.scrollTo(scrollState.maxValue)
                }
            }
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { logExpanded = !logExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.tools_signature_metadata_injector_log_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Icon(
                    imageVector = if (logExpanded) {
                        Icons.Outlined.ExpandLess
                    } else {
                        Icons.Outlined.ExpandMore
                    },
                    contentDescription = stringResource(
                        if (logExpanded) R.string.collapse_content else R.string.expand_content
                    )
                )
            }
            AnimatedVisibility(visible = logExpanded) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    SelectionContainer {
                        Text(
                            text = entries.joinToString("\n"),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 96.dp, max = 220.dp)
                                .verticalScroll(scrollState),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 14.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    if (showJumpToLatest) {
                        SmallFloatingActionButton(
                            onClick = { followLatest = true },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp),
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.KeyboardArrowDown,
                                contentDescription = stringResource(
                                    R.string.tools_signature_metadata_injector_log_jump_latest
                                ),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
            if (showActions) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onCopy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = stringResource(
                                R.string.tools_signature_metadata_injector_log_copy
                            ),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                    OutlinedButton(
                        onClick = onExport,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Description,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = stringResource(
                                R.string.tools_signature_metadata_injector_log_export
                            ),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SignatureMetadataSelectionCard(
    label: String,
    selection: SignatureMetadataSelectionState,
    icon: ImageVector,
    details: List<String>
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (selection.analyzing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(19.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(icon, null, modifier = Modifier.size(20.dp))
                    }
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    selection.displayName.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                details.forEach { detail ->
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                selection.error?.let { error ->
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

private fun signatureMetadataSourceDetails(
    context: Context,
    info: SignatureMetadataSourceInfo
): List<String> = buildList {
    add(
        context.getString(
            when (info.sourceType) {
                SignatureMetadataSourceType.METADATA_ZIP ->
                    R.string.tools_signature_metadata_injector_source_type_metadata_zip
                SignatureMetadataSourceType.APK ->
                    R.string.tools_signature_metadata_injector_source_type_apk
                SignatureMetadataSourceType.SPLIT_APK_CONTAINER ->
                    R.string.tools_signature_metadata_injector_source_type_split
            }
        )
    )
    if (info.sourceType == SignatureMetadataSourceType.SPLIT_APK_CONTAINER) {
        add(
            context.resources.getQuantityString(
                R.plurals.tools_signature_metadata_injector_source_apk_count,
                info.apkEntryCount,
                info.apkEntryCount
            )
        )
    }
    info.donorApkInfo?.let { donor ->
        add(donor.packageName)
        add(
            context.getString(
                R.string.tools_signature_metadata_injector_version,
                donor.versionName ?: "—",
                donor.versionCode
            )
        )
    }
    if (info.entryNames.isNotEmpty()) {
        add(
            context.resources.getQuantityString(
                R.plurals.tools_signature_metadata_injector_entry_count,
                info.entryNames.size,
                info.entryNames.size
            )
        )
    }
    if (info.signingBlockEntryCount > 0) {
        add(
            context.resources.getQuantityString(
                R.plurals.tools_signature_metadata_injector_signing_block_count,
                info.signingBlockEntryCount,
                info.signingBlockEntryCount
            )
        )
    }
    if (
        info.sourceType == SignatureMetadataSourceType.METADATA_ZIP &&
        info.entryNames.isNotEmpty()
    ) {
        add(signatureMetadataEntryPreview(context, info.entryNames))
    }
}

private fun signatureMetadataEntryPreview(
    context: Context,
    entries: List<String>
): String {
    val visibleEntries = entries.take(SIGNATURE_METADATA_ENTRY_PREVIEW_LIMIT)
    val remainingCount = entries.size - visibleEntries.size
    return buildString {
        append(visibleEntries.joinToString())
        if (remainingCount > 0) {
            if (isNotEmpty()) append(" • ")
            append(
                context.getString(
                    R.string.tools_signature_metadata_injector_more_entries,
                    remainingCount
                )
            )
        }
    }
}

@Composable
private fun SignatureMetadataResultCard(
    fileName: String,
    packageName: String,
    injectedEntries: List<String>,
    skippedEntries: List<String>,
    signingMode: SignatureMetadataSigningMode,
    suppliedSigningBlockApplied: Boolean,
    installing: Boolean,
    installStatus: String?,
    onInstall: () -> Unit,
    onSave: () -> Unit
) {
    val context = LocalContext.current
    val injectedEntriesPreview = signatureMetadataEntryPreview(context, injectedEntries)
    val signingNotice = when (signingMode) {
        SignatureMetadataSigningMode.DONT_SIGN ->
            stringResource(R.string.tools_signature_metadata_injector_result_unsigned)
        SignatureMetadataSigningMode.SIGN ->
            stringResource(R.string.tools_signature_metadata_injector_result_signed)
        SignatureMetadataSigningMode.APPLY_SUPPLIED_SIGNATURE ->
            stringResource(
                if (suppliedSigningBlockApplied) {
                    R.string.tools_signature_metadata_injector_result_supplied_applied
                } else {
                    R.string.tools_signature_metadata_injector_result_supplied_legacy_only
                }
            )
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Outlined.CheckCircle, null)
                Text(
                    stringResource(R.string.tools_signature_metadata_injector_ready),
                    style = MaterialTheme.typography.titleSmall
                )
            }
            Text(fileName, style = MaterialTheme.typography.bodyMedium)
            Text(packageName, style = MaterialTheme.typography.bodySmall)
            Text(
                signingNotice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (injectedEntriesPreview.isNotBlank()) {
                Text(
                    injectedEntriesPreview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (skippedEntries.isNotEmpty()) {
                Text(
                    stringResource(
                        R.string.tools_signature_metadata_injector_skipped,
                        skippedEntries.size
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            installStatus?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Button(
                onClick = onInstall,
                enabled = !installing,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (installing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Text(
                        stringResource(
                            R.string.tools_signature_metadata_injector_installing
                        ),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                } else {
                    Icon(Icons.Outlined.Android, null)
                    Text(
                        stringResource(R.string.install_app),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
            OutlinedButton(
                onClick = onSave,
                enabled = !installing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.Save, null)
                Text(
                    stringResource(R.string.save),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun SignatureMetadataMessageCard(
    text: String,
    icon: ImageVector,
    error: Boolean = false
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (error) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(icon, null, modifier = Modifier.size(18.dp))
            Text(
                text,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 12.sp,
                    lineHeight = 15.sp
                )
            )
        }
    }
}

@Composable
internal fun signatureMetadataStageText(stage: SignatureMetadataInjectorStage?): String {
    return when (stage) {
        SignatureMetadataInjectorStage.ANALYZING ->
            stringResource(R.string.tools_signature_metadata_injector_stage_analyzing)
        SignatureMetadataInjectorStage.PREPARING_TARGET ->
            stringResource(R.string.tools_signature_metadata_injector_stage_preparing_target)
        SignatureMetadataInjectorStage.LOADING ->
            stringResource(R.string.tools_signature_metadata_injector_stage_loading)
        SignatureMetadataInjectorStage.INJECTING ->
            stringResource(R.string.tools_signature_metadata_injector_stage_injecting)
        SignatureMetadataInjectorStage.WRITING ->
            stringResource(R.string.tools_signature_metadata_injector_stage_writing)
        SignatureMetadataInjectorStage.SIGNING ->
            stringResource(R.string.tools_signature_metadata_injector_stage_signing)
        SignatureMetadataInjectorStage.VALIDATING ->
            stringResource(R.string.tools_signature_metadata_injector_stage_validating)
        SignatureMetadataInjectorStage.COMPLETE ->
            stringResource(R.string.tools_signature_metadata_injector_stage_complete)
        null -> stringResource(R.string.tools_signature_metadata_injector_action)
    }
}

private data class SignatureMetadataSaveDialogState(
    val directory: Path,
    val fileName: String
)

private data class SignatureMetadataLogExportDialogState(
    val directory: Path,
    val fileName: String
)

private enum class SignatureMetadataPermissionRequest {
    SOURCE,
    APK,
    OUTPUT,
    LOG_EXPORT
}

private const val SIGNATURE_METADATA_ENTRY_PREVIEW_LIMIT = 5
private val SIGNATURE_METADATA_SOURCE_EXTENSIONS =
    setOf("apk", "apks", "xapk", "apkm", "zip")
private val SIGNATURE_METADATA_SPLIT_EXTENSIONS =
    setOf("apks", "xapk", "apkm", "zip")
private val SIGNATURE_METADATA_ARCHIVE_MIME_TYPES =
    setOf("application/zip", "application/x-zip-compressed")

private fun querySignatureMetadataDisplayName(context: Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(
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
}

private fun isSignatureMetadataSourceFile(path: Path): Boolean =
    path.fileName
        ?.toString()
        ?.substringAfterLast('.', missingDelimiterValue = "")
        ?.lowercase(Locale.ROOT) in SIGNATURE_METADATA_SOURCE_EXTENSIONS

private fun isSignatureMetadataTargetFile(path: Path): Boolean =
    isSignatureMetadataSourceFile(path)

private fun isSignatureMetadataSourceInput(
    displayName: String?,
    mimeType: String?
): Boolean {
    val extension = displayName
        ?.substringAfterLast('.', missingDelimiterValue = "")
        ?.lowercase(Locale.ROOT)
    val normalizedMimeType = mimeType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.ROOT)
    return extension in SIGNATURE_METADATA_SOURCE_EXTENSIONS ||
        normalizedMimeType == APK_MIMETYPE ||
        normalizedMimeType in SIGNATURE_METADATA_ARCHIVE_MIME_TYPES
}

private fun isSignatureMetadataTargetInput(
    displayName: String?,
    mimeType: String?
): Boolean = isSignatureMetadataSourceInput(displayName, mimeType)

private fun normalizeSignatureMetadataFileName(
    value: String,
    splitContainer: Boolean,
    splitExtension: String
): String {
    val sanitized = sanitizeSignatureMetadataFileName(value)
    val extension = if (splitContainer) ".$splitExtension" else ".apk"
    val knownExtension = sanitized
        .substringAfterLast('.', missingDelimiterValue = "")
        .lowercase(Locale.ROOT)
    val withoutKnownExtension = if (
        knownExtension in SIGNATURE_METADATA_SOURCE_EXTENSIONS
    ) {
        sanitized.substringBeforeLast('.')
    } else {
        sanitized
    }
    return "$withoutKnownExtension$extension"
}

private fun normalizeSignatureMetadataLogFileName(value: String): String {
    val sanitized = sanitizeSignatureMetadataFileName(value)
    return if (sanitized.endsWith(".txt", ignoreCase = true)) {
        sanitized
    } else {
        "$sanitized.txt"
    }
}

private fun sanitizeSignatureMetadataFileName(value: String): String {
    return value
        .trim()
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .ifBlank { "metadata-injected" }
}
