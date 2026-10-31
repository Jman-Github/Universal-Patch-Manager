package app.universal.patch.manager.ui.screen.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.view.HapticFeedbackConstants
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universal.patch.manager.domain.installer.InstallerManager
import app.universal.patch.manager.domain.manager.PreferencesManager
import kotlinx.coroutines.flow.first
import app.universal.patch.manager.R
import app.universal.patch.manager.data.room.apps.downloaded.DownloadedApp
import app.universal.patch.manager.data.platform.Filesystem
import app.universal.patch.manager.network.downloader.LoadedDownloaderPlugin
import app.universal.patch.manager.network.downloader.DownloaderPluginSourceState
import app.universal.patch.manager.network.downloader.DownloaderPluginState
import app.universal.patch.manager.network.downloader.toDownloaderMainName
import app.universal.patch.manager.ui.component.AppTopBar
import app.universal.patch.manager.ui.component.AppIcon
import app.universal.patch.manager.ui.component.AppLabel
import app.universal.patch.manager.ui.component.ExceptionViewerDialog
import app.universal.patch.manager.ui.component.GroupHeader
import app.universal.patch.manager.ui.component.LazyColumnWithScrollbar
import app.universal.patch.manager.ui.component.SettingsSectionIcons
import app.universal.patch.manager.ui.component.ConfirmDialog
import app.universal.patch.manager.ui.component.TransparentLoadingDialog
import app.universal.patch.manager.ui.component.patches.PathSelectorDialog
import app.universal.patch.manager.ui.component.patcher.InstallerPickerDialog
import app.universal.patch.manager.ui.component.haptics.HapticCheckbox
import app.universal.patch.manager.ui.component.settings.BooleanItem
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsCard
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsDivider
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsItem
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsSwitch
import app.universal.patch.manager.ui.component.settings.SettingsSearchHighlight
import app.universal.patch.manager.ui.model.navigation.Settings
import app.universal.patch.manager.ui.screen.settings.SettingsSearchState
import app.universal.patch.manager.ui.viewmodel.DownloadsViewModel
import app.universal.patch.manager.ui.component.RememberedCreateDocument
import app.universal.patch.manager.ui.component.toPickerDirectoryUri
import app.universal.patch.manager.ui.component.AnnotatedLinkText // From PR #37: https://github.com/Jman-Github/Universal-Patch-Manager/pull/37
import app.universal.patch.manager.util.isAllowedApkFile
import app.universal.patch.manager.util.consumeHorizontalScroll
import app.universal.patch.manager.util.longPressOnly
import app.universal.patch.manager.util.toast
import app.universal.patch.manager.util.withHapticFeedback
import org.koin.compose.koinInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import java.security.MessageDigest
import kotlin.text.HexFormat
import java.nio.file.Files
import java.nio.file.Path
import app.universal.patch.manager.ui.component.CenteredDialogTitle

@OptIn(ExperimentalMaterial3Api::class, ExperimentalStdlibApi::class)
@Composable
fun DownloadsSettingsScreen(
    onBackClick: () -> Unit,
    viewModel: DownloadsViewModel = koinViewModel()
) {
    val prefs: PreferencesManager = koinInject()
    val useCustomFilePicker by prefs.useCustomFilePicker.getAsState()
    val downloadsExportDirectory by prefs.downloadsExportLastDirectory.getAsState()
    val pickerScope = rememberCoroutineScope()
    val autoSaveDownloaderApks by prefs.autoSaveDownloaderApks.getAsState()
    val autoSaveDownloaderLatestOnly by prefs.autoSaveDownloaderLatestOnly.getAsState()
    val chooseInstallerPerInstall by prefs.chooseInstallerPerInstall.getAsState()
    val downloadedApps by viewModel.downloadedApps.collectAsStateWithLifecycle(emptyList())
    val installProgress by viewModel.installProgress.collectAsStateWithLifecycle()
    val pluginStates by viewModel.downloaderPluginStates.collectAsStateWithLifecycle(emptyMap())
    val sourceStates by viewModel.downloaderPluginSourceStates.collectAsStateWithLifecycle(emptyMap())
    val apkDownloadHelpers by viewModel.apkDownloadHelpers.collectAsStateWithLifecycle(emptyList())
    val trustedApkDownloadHelpers by viewModel.trustedApkDownloadHelpers.collectAsStateWithLifecycle(emptyList())
    val trustedHelperPackages = remember(trustedApkDownloadHelpers) {
        trustedApkDownloadHelpers.mapTo(mutableSetOf()) { it.packageName }
    }
    val managedHelperPackages = remember(sourceStates) {
        sourceStates.values.mapNotNull { source ->
            when (val state = source.state) {
                is DownloaderPluginSourceState.State.HelperApp -> state.packageName
                is DownloaderPluginSourceState.State.Untrusted ->
                    state.packageName.takeIf { state.helperApp }
                else -> null
            }
        }.toSet()
    }
    val remoteSourceBusyState = viewModel.remoteSourceBusyState
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val searchTarget by SettingsSearchState.target.collectAsStateWithLifecycle()
    var highlightTarget by rememberSaveable { mutableStateOf<Int?>(null) }
    var showHelpDialog by rememberSaveable { mutableStateOf(false) } // From PR #37: https://github.com/Jman-Github/Universal-Patch-Manager/pull/37
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    val fs: Filesystem = koinInject()
    val installerManager: InstallerManager = koinInject()
    val storageRoots = remember { fs.storageRoots() }
    val (permissionContract, permissionName) = remember { fs.permissionContract() }
    var pendingExportState by remember { mutableStateOf<DownloadedAppsExportState?>(null) }
    var activeExportState by remember { mutableStateOf<DownloadedAppsExportState?>(null) }
    var pendingDocumentExportState by remember { mutableStateOf<DownloadedAppsExportState?>(null) }
    var exportFileDialogState by remember { mutableStateOf<DownloadedAppsExportDialogState?>(null) }
    var pendingExportConfirmation by remember { mutableStateOf<PendingDownloadedAppsExportConfirmation?>(null) }
    var exportInProgress by rememberSaveable { mutableStateOf(false) }
    var showDeleteAppsConfirmation by rememberSaveable { mutableStateOf(false) }
    var showImportUrlDialog by rememberSaveable { mutableStateOf(false) }
    var sourceIdPendingDeletion by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceIdPendingTrustRevoke by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceIdInSettings by rememberSaveable { mutableStateOf<String?>(null) }
    var appPendingDeletion by remember { mutableStateOf<DownloadedApp?>(null) }
    var appPendingInstallerChoice by remember { mutableStateOf<DownloadedApp?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.acknowledgeNewPlugins()
    }
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshInstalledHelperState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(permissionContract) { granted ->
            if (granted) {
                activeExportState = pendingExportState
            }
            pendingExportState = null
        }
    val exportDocumentLauncher = rememberLauncherForActivityResult(
        contract = RememberedCreateDocument("*/*") {
            downloadsExportDirectory.takeIf(String::isNotBlank)?.let(Uri::parse)
        }
    ) { uri ->
        val exportState = pendingDocumentExportState
        pendingDocumentExportState = null
        if (uri != null && exportState != null) {
            pickerScope.launch {
                prefs.downloadsExportLastDirectory.update(uri.toPickerDirectoryUri().toString())
            }
            viewModel.exportApps(
                context = context,
                uri = uri,
                apps = exportState.apps,
                asArchive = exportState.asArchive
            )
        }
    }
    fun openExportPicker(apps: Collection<DownloadedApp>) {
        val selection = apps.toList()
        if (selection.isEmpty()) return
        pickerScope.launch {
            try {
                val extension = selection.singleOrNull()?.let {
                    viewModel.exportFileExtensionFor(it)
                } ?: "apk"
                val state = selection.toExportState(extension)
                if (useCustomFilePicker) {
                    if (fs.hasStoragePermission()) {
                        activeExportState = state
                    } else {
                        pendingExportState = state
                        permissionLauncher.launch(permissionName)
                    }
                } else {
                    pendingDocumentExportState = state
                    exportDocumentLauncher.launch(state.defaultFileName)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                android.util.Log.e("DownloadsSettings", "Failed to inspect download for export", error)
                context.toast(context.getString(R.string.downloaded_apps_export_failed))
            }
        }
    }
    LaunchedEffect(useCustomFilePicker) {
        if (!useCustomFilePicker) {
            activeExportState = null
            pendingExportState = null
            exportFileDialogState = null
            pendingExportConfirmation = null
        }
    }

    LaunchedEffect(searchTarget) {
        val target = searchTarget
        if (target?.destination == Settings.Downloads) {
            val itemCount = androidx.compose.runtime.snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
            val index = when (target.targetId) {
                R.string.download_behavior_section, R.string.downloader_auto_save_title,
                R.string.downloader_auto_save_latest_only_title -> 0
                R.string.downloader_plugins -> 2
                else -> 4 + sourceStates.size + pluginStates.size +
                    apkDownloadHelpers.filterNot { it.packageName in managedHelperPackages }
                        .distinctBy { it.packageName }.size +
                    (if (pluginStates.isEmpty() && sourceStates.isEmpty() && apkDownloadHelpers.isEmpty()) 1 else 0)
            }
            listState.scrollToItem(index.coerceIn(0, itemCount - 1))
            highlightTarget = target.targetId
            SettingsSearchState.clear()
        }
    }

    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = { CenteredDialogTitle(stringResource(R.string.plugins_help_title)) },
            text = {
                // From PR #37: https://github.com/Jman-Github/Universal-Patch-Manager/pull/37
                AnnotatedLinkText(
                    text = stringResource(R.string.plugins_help_description),
                    linkLabel = stringResource(R.string.here),
                    url = "https://github.com/Jman-Github/Universal-Patch-Manager?tab=readme-ov-file#-supported-downloader-plugins",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { showHelpDialog = false }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }
    if (showImportUrlDialog) {
        ImportDownloaderSourceDialog(
            onDismiss = { showImportUrlDialog = false },
            onImport = { url ->
                viewModel.importPluginSource(url)
                showImportUrlDialog = false
            }
        )
    }
    appPendingDeletion?.let { app ->
        ConfirmDialog(
            onDismiss = { appPendingDeletion = null },
            onConfirm = {
                viewModel.deleteApp(app)
                appPendingDeletion = null
            },
            title = stringResource(R.string.downloaded_app_delete_title),
            description = stringResource(R.string.downloaded_app_delete_description),
            icon = Icons.Outlined.Delete
        )
    }
    appPendingInstallerChoice?.let { app ->
        InstallerPickerDialog(
            title = stringResource(R.string.installer_choose_for_this_install_title),
            options = installerManager.listEntries(
                target = InstallerManager.InstallTarget.SAVED_APP,
                includeNone = false
            ),
            onDismiss = { appPendingInstallerChoice = null },
            onConfirm = { token -> viewModel.installApp(app, token) },
            onOpenShizuku = installerManager::openShizukuApp
        )
    }
    when {
        installProgress != null -> {
            val progress = requireNotNull(installProgress)
            TransparentLoadingDialog(
                message = progress.status,
                cancelButtonText = stringResource(R.string.cancel),
                onCancel = viewModel::cancelInstall,
                logTitle = if (progress.showMergeLog) {
                    stringResource(R.string.downloaded_app_install_merge_log)
                } else {
                    null
                },
                logLines = progress.logLines,
                emptyLogMessage = stringResource(R.string.downloaded_app_install_merge_log_waiting)
            )
        }
        remoteSourceBusyState != null -> TransparentLoadingDialog()
    }
    sourceIdPendingDeletion
        ?.let(sourceStates::get)
        ?.let { source ->
            val sourceLabel = source.name.toDownloaderDisplayLabel()
            ConfirmDialog(
                onDismiss = { sourceIdPendingDeletion = null },
                onConfirm = {
                    viewModel.removePluginSource(source.entry.id)
                    sourceIdPendingDeletion = null
                },
                title = stringResource(R.string.downloader_source_delete_title),
                description = stringResource(
                    R.string.downloader_source_delete_description,
                    sourceLabel
                ),
                icon = Icons.Outlined.Delete
            )
        }
    sourceIdPendingTrustRevoke
        ?.let(sourceStates::get)
        ?.let { source ->
            val sourceLabel = source.name.toDownloaderDisplayLabel()
            ConfirmDialog(
                onDismiss = { sourceIdPendingTrustRevoke = null },
                onConfirm = {
                    viewModel.revokePluginSourceTrust(source.entry.id)
                    sourceIdPendingTrustRevoke = null
                },
                title = stringResource(R.string.downloader_plugin_revoke_trust_dialog_title),
                description = stringResource(
                    R.string.downloader_source_revoke_trust_description,
                    sourceLabel
                ),
                icon = Icons.Outlined.WarningAmber
            )
        }
    sourceIdInSettings
        ?.let(sourceStates::get)
        ?.let { source ->
            DownloaderSourceSettingsDialog(
                source = source,
                onDismiss = { sourceIdInSettings = null },
                onAutoUpdateChanged = {
                    viewModel.setPluginSourceAutoUpdate(source.entry.id, it)
                },
                onLatestChanged = {
                    viewModel.setPluginSourceLatest(source.entry.id, it)
                },
                onPrereleaseChanged = {
                    viewModel.setPluginSourcePrerelease(source.entry.id, it)
                },
                onCopyRepoUrl = {
                    clipboard?.setPrimaryClip(
                        ClipData.newPlainText(
                            source.repoUrl.toGitHubRepoDisplayName(),
                            source.repoUrl
                        )
                    )
                    context.toast(context.getString(R.string.downloader_plugin_url_copied))
                }
            )
        }
    activeExportState?.let { state ->
        if (!useCustomFilePicker) return@let
        val fileFilter = if (state.asArchive) ::isZipFile else ::isAllowedApkFile
        PathSelectorDialog(
            roots = storageRoots,
            onSelect = { path ->
                if (path == null) {
                    activeExportState = null
                    exportFileDialogState = null
                    pendingExportConfirmation = null
                }
            },
            fileFilter = fileFilter,
            allowDirectorySelection = false,
            fileTypeLabel = state.fileTypeLabel,
            confirmButtonText = stringResource(R.string.save),
            onConfirm = { directory ->
                exportFileDialogState =
                    DownloadedAppsExportDialogState(state, directory, state.defaultFileName)
            },
            lastDirectoryPreference = prefs.downloadsExportLastDirectory
        )
    }
    exportFileDialogState?.let { state ->
        ExportDownloadedAppsFileNameDialog(
            initialName = state.fileName,
            onDismiss = { exportFileDialogState = null },
            onConfirm = { fileName ->
                val trimmedName = fileName.trim()
                if (trimmedName.isBlank()) return@ExportDownloadedAppsFileNameDialog
                exportFileDialogState = null
                val target = state.directory.resolve(trimmedName)
                if (Files.exists(target)) {
                    pendingExportConfirmation = PendingDownloadedAppsExportConfirmation(
                        state.exportState,
                        state.directory,
                        trimmedName
                    )
                } else {
                    exportInProgress = true
                    viewModel.exportAppsToPath(
                        context = context,
                        target = target,
                        apps = state.exportState.apps,
                        asArchive = state.exportState.asArchive
                    ) { success ->
                        exportInProgress = false
                        if (success) {
                            activeExportState = null
                        }
                    }
                }
            }
        )
    }
    pendingExportConfirmation?.let { state ->
        ConfirmDialog(
            onDismiss = {
                pendingExportConfirmation = null
                exportFileDialogState =
                    DownloadedAppsExportDialogState(state.exportState, state.directory, state.fileName)
            },
            onConfirm = {
                pendingExportConfirmation = null
                exportInProgress = true
                viewModel.exportAppsToPath(
                    context,
                    state.directory.resolve(state.fileName),
                    state.exportState.apps,
                    state.exportState.asArchive
                ) { success ->
                    exportInProgress = false
                    if (success) {
                        activeExportState = null
                    }
                }
            },
            title = stringResource(R.string.export_overwrite_title),
            description = stringResource(R.string.export_overwrite_description, state.fileName),
            icon = Icons.Outlined.WarningAmber
        )
    }
    if (showDeleteAppsConfirmation && viewModel.appSelection.isNotEmpty()) {
        ConfirmDialog(
            onDismiss = { showDeleteAppsConfirmation = false },
            onConfirm = {
                viewModel.deleteApps()
                showDeleteAppsConfirmation = false
            },
            title = stringResource(R.string.downloaded_apps_delete_title),
            description = stringResource(R.string.downloaded_apps_delete_description),
            icon = Icons.Outlined.Delete
        )
    }
    if (exportInProgress) {
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
                    stringResource(R.string.downloaded_apps_export),
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

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.downloads),
                scrollBehavior = scrollBehavior,
                onBackClick = onBackClick,
                onHelpClick = { showHelpDialog = true }, // From PR #37: https://github.com/Jman-Github/Universal-Patch-Manager/pull/37
                actions = {
                    if (viewModel.appSelection.isNotEmpty()) {
                        IconButton(onClick = {
                            openExportPicker(viewModel.appSelection)
                        }) {
                            Icon(Icons.Outlined.Save, stringResource(R.string.downloaded_apps_export))
                        }
                        IconButton(onClick = { showDeleteAppsConfirmation = true }) {
                            Icon(Icons.Default.Delete, stringResource(R.string.delete))
                        }
                    }
                }
            )
        },
        modifier = Modifier
            .blur(if (installProgress != null) 12.dp else 0.dp)
            .nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { paddingValues ->
        PullToRefreshBox(
            onRefresh = viewModel::refreshPlugins,
            isRefreshing = viewModel.isRefreshingPlugins,
            modifier = Modifier.padding(paddingValues)
        ) {
            LazyColumnWithScrollbar(
                thumbLength = app.universal.patch.manager.ui.component.DASHBOARD_SCROLLBAR_THUMB_LENGTH,
                state = listState,
                modifier = Modifier.fillMaxSize()
            ) {
                item {
                    SettingsSearchHighlight(
                        targetKey = R.string.download_behavior_section,
                        activeKey = highlightTarget,
                        onHighlightComplete = { highlightTarget = null }
                    ) { highlightModifier ->
                        GroupHeader(
                            stringResource(R.string.download_behavior_section),
                            icon = SettingsSectionIcons.DownloadBehavior,
                            modifier = highlightModifier
                        )
                    }
                }
                item {
                    ExpressiveSettingsCard(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        SettingsSearchHighlight(
                            targetKey = R.string.downloader_auto_save_title,
                            activeKey = highlightTarget,
                            onHighlightComplete = { highlightTarget = null }
                        ) { highlightModifier ->
                            BooleanItem(
                                modifier = highlightModifier,
                                preference = prefs.autoSaveDownloaderApks,
                                headline = R.string.downloader_auto_save_title,
                                description = R.string.downloader_auto_save_description
                            )
                        }
                        ExpressiveSettingsDivider()
                        SettingsSearchHighlight(
                            targetKey = R.string.downloader_auto_save_latest_only_title,
                            activeKey = highlightTarget,
                            onHighlightComplete = { highlightTarget = null }
                        ) { highlightModifier ->
                            BooleanItem(
                                modifier = highlightModifier,
                                value = autoSaveDownloaderApks && autoSaveDownloaderLatestOnly,
                                onValueChange = { value ->
                                    pickerScope.launch {
                                        prefs.autoSaveDownloaderLatestOnly.update(value)
                                    }
                                },
                                headline = R.string.downloader_auto_save_latest_only_title,
                                description = R.string.downloader_auto_save_latest_only_description,
                                enabled = autoSaveDownloaderApks
                            )
                        }
                    }
                }
                item {
                    SettingsSearchHighlight(
                        targetKey = R.string.downloader_plugins,
                        activeKey = highlightTarget,
                        onHighlightComplete = { highlightTarget = null }
                    ) { highlightModifier ->
                        GroupHeader(
                            stringResource(R.string.downloader_plugins),
                            icon = SettingsSectionIcons.DownloaderPlugins,
                            modifier = highlightModifier
                        )
                    }
                }
                item {
                    ExpressiveSettingsCard(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                    ) {
                        ExpressiveSettingsItem(
                            headlineContent = stringResource(R.string.downloader_import_url),
                            supportingContent = stringResource(R.string.downloader_import_url_description)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            FilledTonalButton(
                                onClick = { showImportUrlDialog = true },
                                enabled = remoteSourceBusyState == null
                            ) {
                                Text(stringResource(R.string.import_))
                            }
                        }
                    }
                }
                sourceStates.forEach { (sourceId, source) ->
                    item(key = "source:$sourceId") {
                        var showExceptionViewer by remember { mutableStateOf(false) }
                        var showTrustDialog by remember { mutableStateOf(false) }
                        var showHelperUninstallDialog by remember { mutableStateOf(false) }
                        val helperSource = source.state as? DownloaderPluginSourceState.State.HelperApp
                        val untrustedSource = source.state as? DownloaderPluginSourceState.State.Untrusted
                        val sourceIsHelper = helperSource != null || untrustedSource?.helperApp == true
                        val helperPackageName = helperSource?.packageName
                            ?: untrustedSource?.packageName?.takeIf { untrustedSource.helperApp }
                        val helperInstalled = helperPackageName != null &&
                            apkDownloadHelpers.any { it.packageName == helperPackageName }
                        val installingHelper =
                            remoteSourceBusyState == DownloadsViewModel.RemoteSourceBusyState.InstallingHelper(sourceId)
                        val uninstallingHelper = helperPackageName != null &&
                            remoteSourceBusyState == DownloadsViewModel.RemoteSourceBusyState.UninstallingHelper(helperPackageName)

                        if (showExceptionViewer && source.state is DownloaderPluginSourceState.State.Failed) {
                            ExceptionViewerDialog(
                                text = remember(source.state.throwable) {
                                    source.state.throwable.stackTraceToString()
                                },
                                onDismiss = { showExceptionViewer = false }
                            )
                        }

                        if (showTrustDialog && source.state is DownloaderPluginSourceState.State.Untrusted) {
                            PluginActionDialog(
                                title = R.string.downloader_plugin_trust_dialog_title,
                                body = stringResource(
                                    if (source.state.helperApp) {
                                        R.string.downloader_helper_source_trust_dialog_body
                                    } else {
                                        R.string.downloader_plugin_trust_dialog_body
                                    }
                                ),
                                pluginName = source.name.toDownloaderMainName(),
                                signature = source.state.signature,
                                primaryLabel = R.string.confirm,
                                secondaryLabel = if (source.state.helperApp) null else R.string.delete,
                                onPrimary = {
                                    viewModel.trustPluginSource(sourceId)
                                    showTrustDialog = false
                                },
                                onSecondary = if (source.state.helperApp) null else {
                                    {
                                        showTrustDialog = false
                                        sourceIdPendingDeletion = sourceId
                                    }
                                },
                                onDismiss = { showTrustDialog = false }
                            )
                        }

                        if (showHelperUninstallDialog && helperPackageName != null) {
                            ConfirmDialog(
                                onDismiss = { showHelperUninstallDialog = false },
                                onConfirm = {
                                    viewModel.uninstallApkDownloadHelper(helperPackageName)
                                    showHelperUninstallDialog = false
                                },
                                title = stringResource(R.string.downloader_helper_uninstall_title),
                                description = stringResource(
                                    R.string.downloader_helper_uninstall_description,
                                    source.name.toDownloaderDisplayLabel()
                                ),
                                icon = Icons.Outlined.Delete
                            )
                        }

                        DownloaderPluginCard(
                            title = source.name.toDownloaderMainName(),
                            version = source.version,
                            status = stringResource(
                                when (source.state) {
                                    is DownloaderPluginSourceState.State.Loaded ->
                                        R.string.downloader_source_state_loaded

                                    is DownloaderPluginSourceState.State.HelperApp -> when {
                                        installingHelper -> R.string.downloader_helper_source_installing
                                        !source.state.installed -> R.string.downloader_helper_source_downloaded
                                        !source.state.installedSignerTrusted ->
                                            R.string.downloader_helper_installed_signer_mismatch
                                        source.version != null &&
                                            source.state.installedVersion != null &&
                                            source.version != source.state.installedVersion ->
                                            R.string.downloader_helper_source_update_downloaded
                                        else -> R.string.installed
                                    }

                                    is DownloaderPluginSourceState.State.Missing ->
                                        R.string.downloader_source_state_missing

                                    is DownloaderPluginSourceState.State.Untrusted ->
                                        R.string.downloader_plugin_state_untrusted

                                    is DownloaderPluginSourceState.State.Failed ->
                                        R.string.downloader_source_state_failed
                                }
                            ),
                            type = if (sourceIsHelper) DownloaderPluginType.Helper else DownloaderPluginType.Remote,
                            detail = source.repoUrl.toGitHubRepoDisplayName(),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            secondaryActionLabel = stringResource(
                                if (sourceIsHelper) R.string.settings else R.string.delete
                            ),
                            onSecondaryAction = {
                                if (sourceIsHelper) {
                                    sourceIdInSettings = sourceId
                                } else {
                                    sourceIdPendingDeletion = sourceId
                                }
                            },
                            middleActionLabel = stringResource(
                                if (sourceIsHelper) R.string.update else R.string.settings
                            ),
                            onMiddleAction = {
                                if (sourceIsHelper) {
                                    viewModel.updatePluginSource(sourceId)
                                } else {
                                    sourceIdInSettings = sourceId
                                }
                            },
                            primaryActionLabel = stringResource(
                                when {
                                    sourceIsHelper && untrustedSource == null ->
                                        R.string.downloader_plugin_revoke_trust
                                    source.state is DownloaderPluginSourceState.State.Untrusted ->
                                        R.string.trust
                                    else -> R.string.update
                                }
                            ),
                            primaryActionStyle = when {
                                sourceIsHelper -> DownloaderActionStyle.FilledTonal
                                source.state is DownloaderPluginSourceState.State.Untrusted ->
                                    DownloaderActionStyle.FilledTonal
                                else -> DownloaderActionStyle.Outlined
                            },
                            onPrimaryAction = {
                                when {
                                    sourceIsHelper && untrustedSource == null ->
                                        sourceIdPendingTrustRevoke = sourceId
                                    source.state is DownloaderPluginSourceState.State.Untrusted ->
                                        showTrustDialog = true
                                    else -> viewModel.updatePluginSource(sourceId)
                                }
                            },
                            primaryActionEnabled = remoteSourceBusyState == null,
                            middleActionEnabled = remoteSourceBusyState == null,
                            secondaryActionEnabled = remoteSourceBusyState == null,
                            footerActionLabel = when {
                                sourceIsHelper && helperInstalled -> stringResource(R.string.uninstall)
                                helperSource != null -> stringResource(R.string.downloader_helper_source_install)
                                !sourceIsHelper &&
                                    source.state !is DownloaderPluginSourceState.State.Untrusted &&
                                    source.state !is DownloaderPluginSourceState.State.Missing ->
                                    stringResource(R.string.downloader_plugin_revoke_trust)
                                else -> null
                            },
                            footerActionStyle = DownloaderActionStyle.FilledTonal,
                            onFooterAction = {
                                when {
                                    sourceIsHelper && helperInstalled -> showHelperUninstallDialog = true
                                    helperSource != null -> viewModel.installHelperSource(sourceId)
                                    else -> sourceIdPendingTrustRevoke = sourceId
                                }
                            },
                            footerActionEnabled = remoteSourceBusyState == null &&
                                !installingHelper && !uninstallingHelper,
                            extraSupportingContent = {
                                if (source.state is DownloaderPluginSourceState.State.Failed) {
                                    TextButton(
                                        modifier = Modifier.padding(horizontal = 0.dp),
                                        onClick = { showExceptionViewer = true }
                                    ) {
                                        Text(stringResource(R.string.downloader_plugin_view_error))
                                    }
                                }
                            }
                        )
                    }
                }
                apkDownloadHelpers
                    .filterNot { it.packageName in managedHelperPackages }
                    .distinctBy { it.packageName }
                    .forEach { helper ->
                        item(key = "helper:${helper.packageName}") {
                            var showTrustDialog by remember { mutableStateOf(false) }
                            var showRevokeDialog by remember { mutableStateOf(false) }
                            var showUninstallDialog by remember { mutableStateOf(false) }
                            val trusted = helper.packageName in trustedHelperPackages
                            val signerSnapshot = remember(showTrustDialog, helper.packageName) {
                                if (!showTrustDialog) return@remember null
                                runCatching {
                                    val signerBytes = viewModel.pm.getSignature(helper.packageName).toByteArray()
                                    val signatureHex = signerBytes.toHexString(format = HexFormat.UpperCase)
                                    val fingerprint = MessageDigest.getInstance("SHA-256")
                                        .digest(signerBytes)
                                        .toHexString(format = HexFormat.UpperCase)
                                    signatureHex to fingerprint
                                }.getOrNull()
                            }

                            if (showTrustDialog) {
                                PluginActionDialog(
                                    title = R.string.downloader_plugin_trust_dialog_title,
                                    body = stringResource(R.string.downloader_helper_installed_trust_dialog_body),
                                    pluginName = helper.label.toDownloaderMainName(),
                                    signature = signerSnapshot?.second.orEmpty(),
                                    primaryLabel = R.string.confirm,
                                    secondaryLabel = null,
                                    onPrimary = {
                                        signerSnapshot?.first?.let { expectedSignature ->
                                            viewModel.trustApkDownloadHelper(
                                                helper.packageName,
                                                expectedSignature
                                            )
                                        }
                                        showTrustDialog = false
                                    },
                                    onSecondary = null,
                                    onDismiss = { showTrustDialog = false }
                                )
                            }
                            if (showRevokeDialog) {
                                ConfirmDialog(
                                    title = stringResource(R.string.downloader_plugin_revoke_trust_dialog_title),
                                    description = stringResource(
                                        R.string.downloader_source_revoke_trust_description,
                                        helper.label.toDownloaderDisplayLabel()
                                    ),
                                    icon = Icons.Outlined.WarningAmber,
                                    onDismiss = { showRevokeDialog = false },
                                    onConfirm = {
                                        viewModel.revokeApkDownloadHelperTrust(helper.packageName)
                                        showRevokeDialog = false
                                    }
                                )
                            }
                            if (showUninstallDialog) {
                                ConfirmDialog(
                                    title = stringResource(R.string.downloader_helper_uninstall_title),
                                    description = stringResource(
                                        R.string.downloader_helper_uninstall_description,
                                        helper.label.toDownloaderDisplayLabel()
                                    ),
                                    icon = Icons.Outlined.Delete,
                                    onDismiss = { showUninstallDialog = false },
                                    onConfirm = {
                                        viewModel.uninstallApkDownloadHelper(helper.packageName)
                                        showUninstallDialog = false
                                    }
                                )
                            }

                            DownloaderPluginCard(
                                title = helper.label.toDownloaderMainName(),
                                version = helper.version,
                                status = stringResource(
                                    if (trusted) R.string.installed
                                    else R.string.downloader_plugin_state_untrusted
                                ),
                                type = DownloaderPluginType.Helper,
                                detail = helper.packageName,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                primaryActionLabel = stringResource(
                                    if (trusted) R.string.downloader_plugin_revoke_trust else R.string.trust
                                ),
                                primaryActionStyle = DownloaderActionStyle.FilledTonal,
                                onPrimaryAction = {
                                    if (trusted) showRevokeDialog = true else showTrustDialog = true
                                },
                                primaryActionEnabled = remoteSourceBusyState == null,
                                footerActionLabel = stringResource(R.string.uninstall),
                                footerActionStyle = DownloaderActionStyle.FilledTonal,
                                onFooterAction = { showUninstallDialog = true },
                                footerActionEnabled = remoteSourceBusyState == null
                            )
                        }
                    }
                pluginStates.forEach { (packageName, state) ->
                    item(key = packageName) {
                        var dialogType by remember { mutableStateOf<PluginDialogType?>(null) }
                        var showExceptionViewer by remember { mutableStateOf(false) }

                        val packageInfo =
                            remember(packageName) {
                                viewModel.pm.getPackageInfo(packageName)
                            } ?: return@item

                        val signature = remember(packageName) {
                            runCatching {
                                val androidSignature = viewModel.pm.getSignature(packageName)
                                val hash = MessageDigest.getInstance("SHA-256")
                                    .digest(androidSignature.toByteArray())
                                hash.toHexString(format = HexFormat.UpperCase)
                            }.getOrNull()
                        }
                        val appName = remember(packageName) {
                            packageInfo.applicationInfo?.loadLabel(context.packageManager)
                                ?.toString()
                                ?: packageName
                        }
                        val pluginTitle = remember(appName, state) {
                            when (state) {
                                is DownloaderPluginState.Loaded -> state.plugins
                                    .map(LoadedDownloaderPlugin::shortDisplayName)
                                    .distinct()
                                    .joinToString(", ")
                                    .ifBlank { appName.toDownloaderMainName() }

                                else -> appName.toDownloaderMainName()
                            }
                        }

                        when (dialogType) {
                            PluginDialogType.Trust -> {
                                PluginActionDialog(
                                    title = R.string.downloader_plugin_trust_dialog_title,
                                    body = stringResource(
                                        R.string.downloader_plugin_trust_dialog_body
                                    ),
                                    pluginName = pluginTitle.toDownloaderMainName(),
                                    signature = signature.orEmpty(),
                                    primaryLabel = R.string.confirm,
                                    onPrimary = {
                                        viewModel.trustPlugin(packageName)
                                        dialogType = null
                                    },
                                    onSecondary = {
                                        dialogType = PluginDialogType.Uninstall
                                    },
                                    onDismiss = { dialogType = null }
                                )
                            }

                            PluginDialogType.Revoke -> {
                                ConfirmDialog(
                                    title = stringResource(
                                        R.string.downloader_plugin_revoke_trust_dialog_title
                                    ),
                                    description = stringResource(
                                        R.string.downloader_source_revoke_trust_description,
                                        pluginTitle.toDownloaderDisplayLabel()
                                    ),
                                    icon = Icons.Outlined.WarningAmber,
                                    onDismiss = { dialogType = null },
                                    onConfirm = {
                                        viewModel.revokePluginTrust(packageName)
                                        dialogType = null
                                    }
                                )
                            }

                            PluginDialogType.Failed -> {
                                PluginFailedDialog(
                                    packageName = packageName,
                                    onDismiss = { dialogType = null },
                                    onViewDetails = {
                                        dialogType = null
                                        showExceptionViewer = true
                                    },
                                    onUninstall = { dialogType = PluginDialogType.Uninstall }
                                )
                            }

                            PluginDialogType.Uninstall -> {
                                ConfirmDialog(
                                    onDismiss = { dialogType = null },
                                    onConfirm = {
                                        viewModel.uninstallPlugin(packageName)
                                        dialogType = null
                                    },
                                    title = stringResource(R.string.downloader_plugin_uninstall_title),
                                    description = stringResource(
                                        R.string.downloader_plugin_uninstall_description,
                                        pluginTitle.toDownloaderDisplayLabel()
                                    ),
                                    icon = Icons.Outlined.Delete
                                )
                            }
                            null -> Unit
                        }

                        if (showExceptionViewer && state is DownloaderPluginState.Failed) {
                            ExceptionViewerDialog(
                                text = remember(state.throwable) {
                                    state.throwable.stackTraceToString()
                                },
                                onDismiss = { showExceptionViewer = false }
                            )
                        }

                        DownloaderPluginCard(
                            title = pluginTitle,
                            version = packageInfo.versionName,
                            status = stringResource(
                                when (state) {
                                    is DownloaderPluginState.Loaded ->
                                        R.string.downloader_source_state_loaded

                                    is DownloaderPluginState.Failed ->
                                        R.string.downloader_source_state_failed

                                    is DownloaderPluginState.Untrusted ->
                                        R.string.downloader_plugin_state_untrusted
                                }
                            ),
                            type = DownloaderPluginType.Local,
                            detail = packageName,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            secondaryActionLabel = stringResource(R.string.uninstall),
                            onSecondaryAction = { dialogType = PluginDialogType.Uninstall },
                            primaryActionLabel = stringResource(
                                when (state) {
                                    is DownloaderPluginState.Loaded ->
                                        R.string.downloader_plugin_revoke_trust

                                    is DownloaderPluginState.Failed ->
                                        R.string.downloader_plugin_view_error

                                    is DownloaderPluginState.Untrusted ->
                                        R.string.trust
                                }
                            ),
                            onPrimaryAction = {
                                when (state) {
                                    is DownloaderPluginState.Loaded ->
                                        dialogType = PluginDialogType.Revoke

                                    is DownloaderPluginState.Failed ->
                                        showExceptionViewer = true

                                    is DownloaderPluginState.Untrusted ->
                                        dialogType = PluginDialogType.Trust
                                }
                            }
                        )
                    }
                }
                if (pluginStates.isEmpty() && sourceStates.isEmpty() && apkDownloadHelpers.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.downloader_no_plugins_installed),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                item {
                    SettingsSearchHighlight(
                        targetKey = R.string.downloaded_apps,
                        activeKey = highlightTarget,
                        extraKeys = setOf(R.string.downloaded_apps_export, R.string.download_export_section),
                        onHighlightComplete = { highlightTarget = null }
                    ) { highlightModifier ->
                        GroupHeader(
                            stringResource(R.string.download_export_section),
                            icon = SettingsSectionIcons.SavedApps,
                            modifier = highlightModifier
                        )
                    }
                }
                items(downloadedApps, key = { it.packageName to it.version }) { app ->
                    val selected = app in viewModel.appSelection
                    val isInstalling = viewModel.installingApp == app
                    val displayInfo by produceState(
                        initialValue = viewModel.displayInfoFor(app),
                        key1 = app.packageName,
                        key2 = app.version,
                        key3 = app.directory.path
                    ) {
                        value = viewModel.loadDisplayInfo(app)
                    }

                    DownloadedAppCard(
                        app = app,
                        displayInfo = displayInfo,
                        selected = selected,
                        selectionActive = viewModel.appSelection.isNotEmpty(),
                        isInstalling = isInstalling,
                        installEnabled = viewModel.installingApp == null,
                        onSelectionChange = { viewModel.toggleApp(app) },
                        onExport = {
                            openExportPicker(listOf(app))
                        },
                        onDelete = {
                            appPendingDeletion = app
                        },
                        onInstall = {
                            if (chooseInstallerPerInstall) {
                                appPendingInstallerChoice = app
                            } else {
                                viewModel.installApp(app)
                            }
                        }
                    )
                }
                if (downloadedApps.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.downloader_settings_no_apps),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadedAppCard(
    app: DownloadedApp,
    displayInfo: DownloadsViewModel.DownloadedAppDisplayInfo?,
    selected: Boolean,
    selectionActive: Boolean,
    isInstalling: Boolean,
    installEnabled: Boolean,
    onSelectionChange: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onInstall: () -> Unit
) {
    val cardShape = RoundedCornerShape(18.dp)
    val elevation = if (selected) 6.dp else 2.dp
    val cardBackground = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
    val headerBackground = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(cardShape)
            .clickable(onClick = onSelectionChange),
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
                        checked = selected,
                        onCheckedChange = { onSelectionChange() }
                    )
                }
                AppIcon(
                    packageInfo = displayInfo?.packageInfo,
                    packageName = app.packageName,
                    contentDescription = null,
                    iconOverride = displayInfo?.icon,
                    modifier = Modifier.size(32.dp)
                )
                val titleScrollState = rememberScrollState()
                AppLabel(
                    packageInfo = displayInfo?.packageInfo,
                    labelOverride = displayInfo?.label,
                    style = MaterialTheme.typography.titleMedium,
                    defaultText = app.packageName,
                    modifier = Modifier
                        .weight(1f)
                        .consumeHorizontalScroll(titleScrollState)
                )
                displayInfo?.isSplitApk?.let { isSplitApk ->
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ) {
                        Text(
                            text = stringResource(
                                if (isSplitApk) R.string.downloaded_app_format_split_apk
                                else R.string.downloaded_app_format_apk
                            ),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val packageNameScrollState = rememberScrollState()
                Text(
                    text = app.packageName,
                    modifier = Modifier
                        .fillMaxWidth()
                        .consumeHorizontalScroll(packageNameScrollState),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip
                )
                Text(
                    text = app.version.toPluginVersionLabel() ?: app.version,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DownloadedAppQuickAction(
                            text = stringResource(R.string.export),
                            icon = Icons.Outlined.Save,
                            onClick = onExport
                        )
                        DownloadedAppQuickAction(
                            text = stringResource(
                                if (isInstalling) R.string.loading else R.string.install_app
                            ),
                            icon = Icons.Outlined.InstallMobile,
                            enabled = installEnabled,
                            loading = isInstalling,
                            onClick = onInstall
                        )
                        DownloadedAppQuickAction(
                            text = stringResource(R.string.delete),
                            icon = Icons.Outlined.Delete,
                            enabled = installEnabled,
                            onClick = onDelete
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadedAppQuickAction(
    text: String,
    icon: ImageVector,
    enabled: Boolean = true,
    loading: Boolean = false,
    onClick: () -> Unit
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
                    contentDescription = text,
                    tint = contentColor,
                    modifier = Modifier.size(14.dp)
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                maxLines = 1
            )
        }
    }
}

private enum class PluginDialogType {
    Trust,
    Revoke,
    Failed,
    Uninstall
}

private data class DownloadedAppsExportState(
    val apps: List<DownloadedApp>,
    val asArchive: Boolean,
    val defaultFileName: String,
    val fileTypeLabel: String
)

private fun Collection<DownloadedApp>.toExportState(extension: String): DownloadedAppsExportState {
    val apps = toList()
    require(apps.isNotEmpty())
    return if (apps.size == 1) {
        val app = apps.first()
        DownloadedAppsExportState(
            apps = apps,
            asArchive = false,
            defaultFileName = "${app.packageName}_${app.version}".replace('/', '_') + ".$extension",
            fileTypeLabel = ".$extension"
        )
    } else {
        DownloadedAppsExportState(
            apps = apps,
            asArchive = true,
            defaultFileName = "downloaded-apps-${System.currentTimeMillis()}.zip",
            fileTypeLabel = ".zip"
        )
    }
}

private data class DownloadedAppsExportDialogState(
    val exportState: DownloadedAppsExportState,
    val directory: Path,
    val fileName: String
)

private data class PendingDownloadedAppsExportConfirmation(
    val exportState: DownloadedAppsExportState,
    val directory: Path,
    val fileName: String
)

private enum class DownloaderPluginType(@StringRes val labelRes: Int) {
    Local(R.string.downloader_plugin_type_legacy),
    Remote(R.string.downloader_plugin_type_modern),
    Helper(R.string.downloader_plugin_type_helper)
}

private enum class DownloaderActionStyle {
    Outlined,
    FilledTonal
}

private fun String.toDownloaderDisplayLabel(): String {
    val mainName = toDownloaderMainName()
    return if (mainName.endsWith(" downloader", ignoreCase = true)) {
        mainName
    } else {
        "$mainName downloader"
    }
}

private fun String?.toPluginVersionLabel(): String? {
    val version = this?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return "v${version.removePrefix("v").removePrefix("V")}"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloaderPluginCard(
    title: String,
    version: String?,
    status: String,
    type: DownloaderPluginType,
    detail: String,
    primaryActionLabel: String? = null,
    secondaryActionLabel: String? = null,
    onPrimaryAction: (() -> Unit)? = null,
    onSecondaryAction: (() -> Unit)? = null,
    middleActionLabel: String? = null,
    onMiddleAction: (() -> Unit)? = null,
    footerActionLabel: String? = null,
    onFooterAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    extraSupportingContent: (@Composable (() -> Unit))? = null,
    primaryActionStyle: DownloaderActionStyle = DownloaderActionStyle.FilledTonal,
    footerActionStyle: DownloaderActionStyle = DownloaderActionStyle.Outlined,
    primaryActionEnabled: Boolean = true,
    secondaryActionEnabled: Boolean = true,
    middleActionEnabled: Boolean = true,
    footerActionEnabled: Boolean = true
) {
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    val copiedToClipboardMessage = stringResource(R.string.toast_copied_to_clipboard)
    val copyToClipboardLabel = stringResource(R.string.copy_to_clipboard)
    val supportingSlot: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "${stringResource(R.string.downloader_plugin_type_label)} ${stringResource(type.labelRes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            DownloaderPluginDetailBox(detail = detail)
            extraSupportingContent?.invoke()
        }
    }

    ExpressiveSettingsCard(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
    ) {
        ExpressiveSettingsItem(
            headlineContent = title,
            supportingContentSlot = supportingSlot,
            leadingContent = {
                DownloaderPluginLeadingIcon(type = type)
            },
            trailingContent = version.toPluginVersionLabel()?.let { pluginVersion ->
                {
                    val copyPluginVersion = {
                        clipboard?.setPrimaryClip(
                            ClipData.newPlainText("$title version", pluginVersion)
                        )
                        if (clipboard != null) {
                            context.toast(copiedToClipboardMessage)
                        }
                    }.withHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    Text(
                        text = pluginVersion,
                        modifier = Modifier.longPressOnly(
                            label = copyToClipboardLabel,
                            onLongPress = copyPluginVersion
                        )
                    )
                }
            }
        )
        val hasRowAction =
            (secondaryActionLabel != null && onSecondaryAction != null) ||
                (middleActionLabel != null && onMiddleAction != null) ||
                (primaryActionLabel != null && onPrimaryAction != null)
        if (hasRowAction) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (secondaryActionLabel != null && onSecondaryAction != null) {
                    OutlinedButton(
                        onClick = onSecondaryAction,
                        enabled = secondaryActionEnabled,
                        modifier = Modifier.weight(1f)
                    ) {
                        DownloaderActionText(secondaryActionLabel)
                    }
                }
                if (middleActionLabel != null && onMiddleAction != null) {
                    OutlinedButton(
                        onClick = onMiddleAction,
                        enabled = middleActionEnabled,
                        modifier = Modifier.weight(1f)
                    ) {
                        DownloaderActionText(middleActionLabel)
                    }
                }
                if (primaryActionLabel != null && onPrimaryAction != null) {
                    DownloaderActionButton(
                        label = primaryActionLabel,
                        onClick = onPrimaryAction,
                        enabled = primaryActionEnabled,
                        modifier = Modifier.weight(1f),
                        style = primaryActionStyle
                    )
                }
            }
        }
        if (footerActionLabel != null && onFooterAction != null) {
            DownloaderActionButton(
                label = footerActionLabel,
                onClick = onFooterAction,
                enabled = footerActionEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                style = footerActionStyle
            )
        }
    }
}

@Composable
private fun DownloaderActionButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    style: DownloaderActionStyle = DownloaderActionStyle.FilledTonal
) {
    when (style) {
        DownloaderActionStyle.Outlined -> OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier
        ) {
            DownloaderActionText(label)
        }

        DownloaderActionStyle.FilledTonal -> FilledTonalButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier
        ) {
            DownloaderActionText(label)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloaderActionText(text: String) {
    Text(
        text = text,
        maxLines = 1,
        modifier = Modifier
            .fillMaxWidth()
            .basicMarquee(),
        textAlign = TextAlign.Center
    )
}

@Composable
private fun DownloaderPluginLeadingIcon(
    type: DownloaderPluginType
) {
    val icon = when (type) {
        DownloaderPluginType.Local -> Icons.Outlined.Folder
        DownloaderPluginType.Remote -> Icons.Outlined.Link
        DownloaderPluginType.Helper -> Icons.Outlined.Download
    }
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    ) {
        Box(
            modifier = Modifier.size(44.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun DownloaderPluginDetailBox(
    detail: String
) {
    val scrollState = rememberScrollState()
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .horizontalScroll(scrollState)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun DownloaderSourceSettingsDialog(
    source: DownloaderPluginSourceState,
    onDismiss: () -> Unit,
    onAutoUpdateChanged: (Boolean) -> Unit,
    onLatestChanged: (Boolean) -> Unit,
    onPrereleaseChanged: (Boolean) -> Unit,
    onCopyRepoUrl: () -> Unit
) {
    val sourceLabel = remember(source.name) { source.name.toDownloaderDisplayLabel() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { CenteredDialogTitle(stringResource(R.string.downloader_source_settings_title, sourceLabel)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = source.repoUrl.toGitHubRepoDisplayName(),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = stringResource(R.string.downloader_source_auto_update),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = stringResource(R.string.auto_update_description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            ExpressiveSettingsSwitch(
                                checked = source.entry.autoUpdate,
                                onCheckedChange = onAutoUpdateChanged
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(
                                modifier = Modifier.weight(1f).alpha(if (source.entry.latest) 0.38f else 1f)
                            ) {
                                Text(
                                    text = stringResource(R.string.downloader_source_prerelease),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = stringResource(R.string.downloader_source_prerelease_description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            ExpressiveSettingsSwitch(
                                checked = source.entry.prerelease && !source.entry.latest,
                                onCheckedChange = onPrereleaseChanged,
                                enabled = !source.entry.latest
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = stringResource(R.string.downloader_source_latest),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = stringResource(R.string.downloader_source_latest_description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            ExpressiveSettingsSwitch(
                                checked = source.entry.latest,
                                onCheckedChange = onLatestChanged
                            )
                        }
                    }
                }
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.downloader_source_repo_url),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = source.repoUrl,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            IconButton(onClick = onCopyRepoUrl) {
                                Icon(
                                    imageVector = Icons.Outlined.ContentCopy,
                                    contentDescription = stringResource(R.string.copy_to_clipboard)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

private fun String.toGitHubRepoDisplayName(): String {
    val marker = "github.com/"
    val afterHost = substringAfter(marker, missingDelimiterValue = this)
    val parts = afterHost.trim('/').split('/').filter(String::isNotBlank)
    return if (parts.size >= 2) {
        "${parts[0]}/${parts[1]}"
    } else {
        this
    }
}

@Composable
private fun ImportDownloaderSourceDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit
) {
    var url by rememberSaveable { mutableStateOf("") }
    val trimmedUrl = url.trim()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        withFrameNanos { }
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    AlertDialog(
        modifier = Modifier.imePadding(),
        onDismissRequest = onDismiss,
        title = { CenteredDialogTitle(stringResource(R.string.downloader_import_url)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.downloader_import_url_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    value = url,
                    onValueChange = { url = it },
                    placeholder = { Text(stringResource(R.string.downloader_import_url_hint)) },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onImport(trimmedUrl) },
                enabled = trimmedUrl.isNotEmpty()
            ) {
                Text(stringResource(R.string.import_))
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
private fun ExportDownloadedAppsFileNameDialog(
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
                stringResource(R.string.downloaded_apps_export),
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

private fun isZipFile(path: Path): Boolean {
    val name = path.fileName?.toString()?.lowercase().orEmpty()
    return name.endsWith(".zip")
}

@Composable
private fun PluginActionDialog(
    @StringRes title: Int,
    body: String,
    pluginName: String,
    signature: String,
    @StringRes primaryLabel: Int,
    @StringRes secondaryLabel: Int? = R.string.uninstall,
    onPrimary: () -> Unit,
    onSecondary: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Outlined.WarningAmber,
                contentDescription = null
            )
        },
        title = {
            Text(
                text = stringResource(title),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = body,
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TrustDialogSectionLabel(
                        text = stringResource(R.string.downloader_plugin_trust_dialog_plugin)
                    )
                    Text(
                        text = pluginName,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TrustDialogSectionLabel(
                        text = stringResource(R.string.downloader_plugin_trust_dialog_signature)
                    )
                    Text(
                        text = signature.chunked(2).joinToString(" "),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        textAlign = TextAlign.Center
                    )
                }
            }
        },
        dismissButton = {
            if (secondaryLabel != null && onSecondary != null) {
                TextButton(onClick = onSecondary) {
                    Text(stringResource(secondaryLabel))
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
                TextButton(onClick = onPrimary) {
                    Text(stringResource(primaryLabel))
                }
            }
        }
    )
}

@Composable
private fun TrustDialogSectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun PluginFailedDialog(
    packageName: String,
    onDismiss: () -> Unit,
    onViewDetails: () -> Unit,
    onUninstall: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { CenteredDialogTitle(stringResource(R.string.downloader_plugin_state_failed)) },
        text = {
            Text(
                stringResource(
                    R.string.downloader_plugin_failed_dialog_body,
                    packageName
                )
            )
        },
        dismissButton = {
            TextButton(onClick = onUninstall) {
                Text(stringResource(R.string.uninstall))
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.dismiss))
                }
                TextButton(onClick = onViewDetails) {
                    Text(stringResource(R.string.downloader_plugin_view_error))
                }
            }
        }
    )
}
