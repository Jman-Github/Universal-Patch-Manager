package app.universal.patch.manager.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universal.patch.manager.data.platform.Filesystem
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.ui.component.AppScaffold
import app.universal.patch.manager.ui.component.AppTopBar
import app.universal.patch.manager.ui.component.ConfirmDialog
import app.universal.patch.manager.ui.component.ExportSavedApkFileNameDialog
import app.universal.patch.manager.ui.component.InterceptBackHandler
import app.universal.patch.manager.ui.component.ShimmerBox
import app.universal.patch.manager.ui.component.patches.PathSelectorDialog
import app.universal.patch.manager.ui.component.RememberedCreateDocument
import app.universal.patch.manager.ui.component.RememberedOpenDocument
import app.universal.patch.manager.ui.component.toPickerDirectoryUri
import app.universal.patch.manager.ui.viewmodel.SplitApkInstallerViewModel
import app.universal.patch.manager.ui.viewmodel.SplitInstallMode
import app.universal.patch.manager.util.SPLIT_ARCHIVE_MIME_TYPES
import app.universal.patch.manager.util.FilenameUtils
import app.universal.patch.manager.util.SplitArchiveIntent
import app.universal.patch.manager.util.isAllowedSplitArchiveFile
import app.universal.patch.manager.util.toast
import app.universal.patch.manager.R
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import kotlinx.coroutines.launch
import app.universal.patch.manager.ui.component.CenteredDialogTitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitApkInstallerScreen(
    onBackClick: () -> Unit,
    pendingExternalInput: SplitArchiveIntent? = null,
    onExternalInputConsumed: () -> Unit = {},
    vm: SplitApkInstallerViewModel = koinViewModel()
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val fs: Filesystem = koinInject()
    val prefs: PreferencesManager = koinInject()
    val useCustomFilePicker by prefs.useCustomFilePicker.getAsState()
    val splitInstallerInputDirectory by prefs.splitInstallerInputLastDirectory.getAsState()
    val splitInstallerLogExportDirectory by prefs.splitInstallerLogExportLastDirectory.getAsState()
    val pickerScope = rememberCoroutineScope()
    val storageRoots = remember { fs.storageRoots() }
    val (permissionContract, permissionName) = remember { fs.permissionContract() }

    var pendingMode by rememberSaveable { mutableStateOf<SplitInstallMode?>(null) }
    var showInputPicker by rememberSaveable { mutableStateOf(false) }
    var showDismissConfirmationDialog by rememberSaveable { mutableStateOf(false) }
    var showLogExportPicker by rememberSaveable { mutableStateOf(false) }
    var logExportInProgress by rememberSaveable { mutableStateOf(false) }
    var logExportFileDialogState by remember { mutableStateOf<SplitInstallerLogExportDialogState?>(null) }
    var pendingLogExportFileName by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingPermissionRequest by rememberSaveable {
        mutableStateOf<SplitInstallerPermissionRequest?>(null)
    }

    val permissionLauncher = rememberLauncherForActivityResult(permissionContract) { granted ->
        if (!granted) {
            if (pendingPermissionRequest == SplitInstallerPermissionRequest.LOG_EXPORT) {
                pendingLogExportFileName = null
            }
            pendingPermissionRequest = null
            return@rememberLauncherForActivityResult
        }
        when (pendingPermissionRequest) {
            SplitInstallerPermissionRequest.INPUT -> showInputPicker = true
            SplitInstallerPermissionRequest.LOG_EXPORT -> showLogExportPicker = true
            null -> Unit
        }
        pendingPermissionRequest = null
    }

    val splitArchiveLauncher = rememberLauncherForActivityResult(
        contract = RememberedOpenDocument {
            splitInstallerInputDirectory.takeIf(String::isNotBlank)?.let(Uri::parse)
        }
    ) { uri: Uri? ->
        val mode = pendingMode
        pendingMode = null
        if (mode == null || uri == null) return@rememberLauncherForActivityResult
        pickerScope.launch {
            prefs.splitInstallerInputLastDirectory.update(uri.toPickerDirectoryUri().toString())
        }

        val displayName = resolveDisplayName(context.contentResolver, uri)

        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        vm.installFromUri(
            uri = uri,
            inputDisplayName = displayName,
            mode = mode
        )
    }
    val logExportDocumentLauncher = rememberLauncherForActivityResult(
        contract = RememberedCreateDocument("text/plain") {
            splitInstallerLogExportDirectory.takeIf(String::isNotBlank)?.let(Uri::parse)
        }
    ) { uri ->
        uri?.let {
            pickerScope.launch {
                prefs.splitInstallerLogExportLastDirectory.update(it.toPickerDirectoryUri().toString())
            }
        }
        vm.exportLogsToUri(context, uri)
        showLogExportPicker = false
        pendingLogExportFileName = null
    }

    fun launchInstall(mode: SplitInstallMode) {
        if (state.inProgress) return
        pendingExternalInput?.let { externalInput ->
            vm.installFromUri(
                uri = externalInput.uri,
                inputDisplayName = externalInput.displayName,
                mode = mode
            )
            onExternalInputConsumed()
            return
        }
        pendingMode = mode
        if (useCustomFilePicker) {
            if (fs.hasStoragePermission()) {
                showInputPicker = true
            } else {
                pendingPermissionRequest = SplitInstallerPermissionRequest.INPUT
                permissionLauncher.launch(permissionName)
            }
        } else {
            splitArchiveLauncher.launch(SPLIT_ARCHIVE_MIME_TYPES)
        }
    }

    fun onPageBack() {
        if (state.inProgress) {
            showDismissConfirmationDialog = true
        } else {
            onExternalInputConsumed()
            onBackClick()
        }
    }

    fun copyLog() {
        if (state.logEntries.isEmpty()) return
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(
            ClipData.newPlainText(
                "Raw installer log",
                vm.getLogContent(context)
            )
        )
        context.toast(context.getString(R.string.split_installer_log_copy_success))
    }

    fun openLogExportPicker() {
        if (state.logEntries.isEmpty()) return
        val logFileName = FilenameUtils.timestampedLogFileName("installer")
        pendingLogExportFileName = logFileName
        if (useCustomFilePicker) {
            if (fs.hasStoragePermission()) {
                showLogExportPicker = true
            } else {
                pendingPermissionRequest = SplitInstallerPermissionRequest.LOG_EXPORT
                permissionLauncher.launch(permissionName)
            }
        } else {
            logExportDocumentLauncher.launch(logFileName)
        }
    }

    InterceptBackHandler(onBack = ::onPageBack)

    if (showDismissConfirmationDialog) {
        ConfirmDialog(
            onDismiss = { showDismissConfirmationDialog = false },
            onConfirm = {
                showDismissConfirmationDialog = false
                vm.cancelInstall()
                onBackClick()
            },
            title = stringResource(R.string.split_installer_stop_confirm_title),
            description = stringResource(R.string.split_installer_stop_confirm_description),
            icon = Icons.Outlined.Cancel
        )
    }

    if (showInputPicker && useCustomFilePicker) {
        PathSelectorDialog(
            roots = storageRoots,
            onSelect = { selection ->
                if (selection == null) {
                    showInputPicker = false
                    pendingMode = null
                    return@PathSelectorDialog
                }

                if (!selection.isDirectory()) {
                    val mode = pendingMode
                    pendingMode = null
                    showInputPicker = false
                    if (mode != null) {
                        vm.installFromPath(selection.toString(), mode)
                    }
                }
            },
            fileFilter = ::isAllowedSplitArchiveFile,
            allowDirectorySelection = false,
            lastDirectoryPreference = prefs.splitInstallerInputLastDirectory
        )
    }
    if (showLogExportPicker && useCustomFilePicker) {
        PathSelectorDialog(
            roots = storageRoots,
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
                val exportDirectory = if (Files.isDirectory(selection)) {
                    selection
                } else {
                    selection.parent ?: selection
                }
                logExportFileDialogState = SplitInstallerLogExportDialogState(
                    exportDirectory,
                    pendingLogExportFileName ?: FilenameUtils.timestampedLogFileName("installer")
                )
            },
            lastDirectoryPreference = prefs.splitInstallerLogExportLastDirectory
        )
    }
    LaunchedEffect(showLogExportPicker, useCustomFilePicker, pendingLogExportFileName) {
        if (showLogExportPicker && !useCustomFilePicker) {
            val logFileName = pendingLogExportFileName
                ?: FilenameUtils.timestampedLogFileName("installer")
            logExportDocumentLauncher.launch(logFileName)
        }
    }
    logExportFileDialogState?.let { state ->
        ExportSavedApkFileNameDialog(
            initialName = state.fileName,
            onDismiss = {
                logExportFileDialogState = null
                pendingLogExportFileName = null
            },
            onConfirm = { fileName ->
                val trimmedName = fileName.trim()
                if (trimmedName.isBlank()) return@ExportSavedApkFileNameDialog
                logExportFileDialogState = null
                pendingLogExportFileName = null
                logExportInProgress = true
                val target = state.directory.resolve(trimmedName)
                vm.exportLogsToPath(context, target) { success ->
                    logExportInProgress = false
                    if (success) {
                        showLogExportPicker = false
                    }
                }
            }
        )
    }
    if (logExportInProgress) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { CenteredDialogTitle(stringResource(R.string.split_installer_log_exporting_title)) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        stringResource(R.string.split_installer_log_exporting),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth(0.75f)
                            .height(4.dp)
                    )
                }
            }
        )
    }

    val privilegedAvailable = state.shizukuAvailable || state.rootAvailable
    val availabilityLabelAvailable = stringResource(R.string.split_installer_status_available)
    val availabilityLabelUnavailable = stringResource(R.string.split_installer_status_unavailable)
    val contentScrollState = rememberScrollState()
    val logScrollState = rememberScrollState()

    AppScaffold(
        topBar = { scrollBehavior ->
            AppTopBar(
                title = stringResource(R.string.tools_split_installer_title),
                scrollBehavior = scrollBehavior,
                onBackClick = ::onPageBack,
                actions = {
                    IconButton(onClick = { vm.refreshAvailability(userInitiated = true) }) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.refresh)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        app.universal.patch.manager.ui.component.ColumnWithScrollbar(
            state = contentScrollState,
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        modifier = Modifier.size(22.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.split_installer_info),
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 14.sp)
                    )
                }
            }

            SplitInstallerAvailabilityCard(
                checkingAvailability = state.checkingAvailability,
                shizukuAvailable = state.shizukuAvailable,
                rootAvailable = state.rootAvailable,
                availabilityLabelAvailable = availabilityLabelAvailable,
                availabilityLabelUnavailable = availabilityLabelUnavailable
            )

            SplitInstallerModeCard(
                title = stringResource(R.string.split_installer_mode_normal_title),
                description = stringResource(R.string.split_installer_mode_normal_description),
                icon = Icons.Filled.Storage,
                enabled = !state.inProgress,
                onClick = { launchInstall(SplitInstallMode.NORMAL) }
            )

            SplitInstallerModeCard(
                title = stringResource(R.string.split_installer_mode_privileged_title),
                description = stringResource(R.string.split_installer_mode_privileged_description),
                icon = Icons.Outlined.Build,
                enabled = !state.inProgress && privilegedAvailable,
                disabledHint = if (!state.checkingAvailability && !privilegedAvailable) {
                    stringResource(R.string.split_installer_no_privileged_access)
                } else {
                    null
                },
                onClick = { launchInstall(SplitInstallMode.PRIVILEGED) }
            )

            if (state.inProgress) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            SplitInstallerRawLog(
                entries = state.logEntries,
                logRevision = state.logRevision,
                logSessionId = state.logSessionId,
                scrollState = logScrollState,
                showActions = state.logComplete &&
                    (state.successMessage != null || state.errorMessage != null),
                onCopy = ::copyLog,
                onExport = ::openLogExportPicker
            )

            if (!state.inProgress && !state.installedPackageName.isNullOrBlank()) {
                Button(
                    onClick = {
                        if (!vm.openInstalledApp()) {
                            context.toast(context.getString(R.string.split_installer_open_failed_toast))
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = null
                    )
                    Text(
                        text = stringResource(R.string.open_app),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SplitInstallerRawLog(
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
        derivedStateOf {
            logExpanded &&
                hasLogEntries &&
                !followLatest &&
                scrollState.canScrollForward
        }
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
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = hasLogEntries) {
                        logExpanded = !logExpanded
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.split_installer_log_title),
                    style = MaterialTheme.typography.titleSmall
                )
                if (hasLogEntries) {
                    Icon(
                        imageVector = if (logExpanded) {
                            Icons.Outlined.ExpandLess
                        } else {
                            Icons.Outlined.ExpandMore
                        },
                        contentDescription = stringResource(
                            if (logExpanded) {
                                R.string.collapse_content
                            } else {
                                R.string.expand_content
                            }
                        )
                    )
                }
            }

            AnimatedVisibility(visible = !hasLogEntries || logExpanded) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    SelectionContainer {
                        Text(
                            text = if (hasLogEntries) {
                                entries.joinToString(separator = "\n\n")
                            } else {
                                stringResource(R.string.split_installer_log_empty)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 96.dp, max = 220.dp)
                                .verticalScroll(scrollState),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 14.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                    R.string.split_installer_log_jump_latest
                                ),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            if (showActions && hasLogEntries) {
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
                            text = stringResource(R.string.split_installer_log_copy),
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
                            text = stringResource(R.string.split_installer_log_export),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SplitInstallerAvailabilityCard(
    checkingAvailability: Boolean,
    shizukuAvailable: Boolean,
    rootAvailable: Boolean,
    availabilityLabelAvailable: String,
    availabilityLabelUnavailable: String
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.32f)
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (checkingAvailability) {
                    ShimmerBox(
                        modifier = Modifier.size(22.dp),
                        shape = MaterialTheme.shapes.small
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        ShimmerBox(
                            modifier = Modifier
                                .fillMaxWidth(0.48f)
                                .height(14.dp)
                        )
                        ShimmerBox(
                            modifier = Modifier
                                .fillMaxWidth(0.74f)
                                .height(14.dp)
                        )
                        ShimmerBox(
                            modifier = Modifier
                                .fillMaxWidth(0.66f)
                                .height(14.dp)
                        )
                    }
                } else {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f),
                        modifier = Modifier.size(22.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.Tune,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = stringResource(R.string.split_installer_availability_title),
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            text = stringResource(
                                R.string.split_installer_availability_entry,
                                stringResource(R.string.installer_shizuku_name),
                                if (shizukuAvailable) availabilityLabelAvailable else availabilityLabelUnavailable
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(
                                R.string.split_installer_availability_entry,
                                stringResource(R.string.split_installer_root_label),
                                if (rootAvailable) availabilityLabelAvailable else availabilityLabelUnavailable
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
@Composable
private fun SplitInstallerModeCard(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    disabledHint: String? = null,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 2.dp,
        shadowElevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
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
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (enabled) 0.5f else 0.3f),
                modifier = Modifier.size(52.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
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
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!enabled && !disabledHint.isNullOrBlank()) {
                    Text(
                        text = disabledHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

private enum class SplitInstallerPermissionRequest {
    INPUT,
    LOG_EXPORT
}

private data class SplitInstallerLogExportDialogState(
    val directory: Path,
    val fileName: String
)

private fun resolveDisplayName(contentResolver: ContentResolver, uri: Uri): String? =
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
        }
        ?: uri.lastPathSegment

