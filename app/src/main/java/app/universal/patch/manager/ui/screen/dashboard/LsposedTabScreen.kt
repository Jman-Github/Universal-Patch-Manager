package app.universal.patch.manager.ui.screen.dashboard

import app.universal.patch.manager.ui.component.minimalActionButtons
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.universal.patch.manager.R
import app.universal.patch.manager.data.platform.Filesystem
import app.universal.patch.manager.data.room.lsposed.LsposedModule
import app.universal.patch.manager.domain.installer.InstallerManager
import app.universal.patch.manager.domain.lsposed.LsposedSourceKind
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.ui.component.AlertDialogExtended
import app.universal.patch.manager.ui.component.ConfirmDialog
import app.universal.patch.manager.ui.component.RememberedGetContent
import app.universal.patch.manager.ui.component.ShimmerBox
import app.universal.patch.manager.ui.component.TransparentLoadingDialog
import app.universal.patch.manager.ui.component.toPickerDirectoryUri
import app.universal.patch.manager.ui.component.haptics.HapticRadioButton
import app.universal.patch.manager.ui.component.patches.PathSelectorDialog
import app.universal.patch.manager.ui.component.patcher.InstallerPickerDialog
import app.universal.patch.manager.ui.model.LsposedModuleActionKey
import app.universal.patch.manager.ui.viewmodel.LsposedViewModel
import app.universal.patch.manager.util.transparentListItemColors
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun LsposedTabScreen(
    modifier: Modifier = Modifier,
    showAddDialog: Boolean = false,
    onAddDialogDismiss: () -> Unit = {},
    viewModel: LsposedViewModel = koinViewModel(),
) {
    val prefs: PreferencesManager = koinInject()
    val filesystem: Filesystem = koinInject()
    val installerManager: InstallerManager = koinInject()
    val chooseInstallerPerInstall by prefs.chooseInstallerPerInstall.getAsState()
    val useCustomFilePicker by prefs.useCustomFilePicker.getAsState()
    val lsposedModuleInputDirectory by prefs.lsposedModuleInputLastDirectory.getAsState()
    val pickerScope = rememberCoroutineScope()
    val storageRoots = remember { filesystem.storageRoots() }
    val modules by viewModel.modules.collectAsState(initial = emptyList())
    val moduleActionOrderPreference by prefs.lsposedModuleActionOrder.getAsState()
    val moduleHiddenActions by prefs.lsposedModuleHiddenActions.getAsState()
    val moduleActionOrder = remember(moduleActionOrderPreference, moduleHiddenActions) {
        val parsed = moduleActionOrderPreference
            .split(',')
            .mapNotNull { LsposedModuleActionKey.fromStorageId(it.trim()) }
        LsposedModuleActionKey.ensureComplete(parsed)
            .filterNot { it.storageId in moduleHiddenActions }
    }
    val framework = viewModel.frameworkState
    var addSource by remember { mutableStateOf(AddModuleSource.GITHUB) }
    var urlDialog by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("") }
    var showInstallerPicker by remember { mutableStateOf(false) }
    var showModuleFilePicker by rememberSaveable { mutableStateOf(false) }
    var pendingModuleFilePicker by rememberSaveable { mutableStateOf(false) }
    var moduleToForget by remember { mutableStateOf<LsposedModule?>(null) }
    val (permissionContract, permissionName) = remember { filesystem.permissionContract() }
    val permissionLauncher = rememberLauncherForActivityResult(permissionContract) { granted ->
        if (granted && pendingModuleFilePicker) {
            showModuleFilePicker = true
        }
        pendingModuleFilePicker = false
    }
    val filePicker = rememberLauncherForActivityResult(
        RememberedGetContent {
            lsposedModuleInputDirectory.takeIf(String::isNotBlank)?.let(Uri::parse)
        }
    ) { uri ->
        uri?.let {
            pickerScope.launch {
                prefs.lsposedModuleInputLastDirectory.update(it.toPickerDirectoryUri().toString())
            }
            viewModel.selectLocal(it)
        }
    }
    val openModuleFilePicker = {
        if (useCustomFilePicker) {
            if (filesystem.hasStoragePermission()) {
                showModuleFilePicker = true
            } else {
                pendingModuleFilePicker = true
                permissionLauncher.launch(permissionName)
            }
        } else {
            filePicker.launch("application/vnd.android.package-archive")
        }
    }
    val externalInstallerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.onExternalInstallerResult()
    }
    val externalInstallRequest = viewModel.externalInstallRequest

    LaunchedEffect(externalInstallRequest) {
        val request = externalInstallRequest ?: return@LaunchedEffect
        viewModel.consumeExternalInstallRequest()
        try {
            externalInstallerLauncher.launch(request)
            viewModel.onExternalInstallerLaunched()
        } catch (error: Exception) {
            viewModel.onExternalInstallerLaunchFailed(error)
        }
    }

    LaunchedEffect(framework.rootAvailable) {
        if (!framework.rootAvailable) {
            onAddDialogDismiss()
            urlDialog = false
            showInstallerPicker = false
            showModuleFilePicker = false
            pendingModuleFilePicker = false
            moduleToForget = null
            viewModel.dismissAssetChoices()
            viewModel.dismissPending()
        }
    }

    LaunchedEffect(useCustomFilePicker) {
        if (!useCustomFilePicker) {
            showModuleFilePicker = false
            pendingModuleFilePicker = false
        }
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item {
                FrameworkCard(
                    rootAvailable = framework.rootAvailable,
                    installed = framework.installed,
                    refreshing = viewModel.frameworkRefreshing,
                    onRefresh = viewModel::refreshFramework,
                    onOpen = viewModel::openManager,
                )
            }
            if (framework.rootAvailable) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        Arrangement.SpaceBetween,
                        Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.lsposed_modules), style = MaterialTheme.typography.titleLarge)
                    }
                }
                if (modules.isEmpty()) item {
                    LsposedStyledCard(
                        title = stringResource(R.string.lsposed_no_modules_added),
                        icon = Icons.Outlined.ViewModule,
                    ) {
                        Text(
                            stringResource(R.string.lsposed_no_modules_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else items(modules, key = LsposedModule::packageName) { module ->
                    ModuleCard(
                        module,
                        moduleActionOrder,
                        viewModel::openManager,
                        { viewModel.openModuleSettings(module) },
                        { viewModel.checkForUpdate(module) },
                        { viewModel.update(module) },
                        { viewModel.reinstall(module) },
                        { viewModel.uninstall(module) },
                        { moduleToForget = module },
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    viewModel.busyMessage?.let { TransparentLoadingDialog(message = it) }

    if (framework.rootAvailable && showModuleFilePicker && useCustomFilePicker) {
        PathSelectorDialog(
            roots = storageRoots,
            onSelect = { path ->
                showModuleFilePicker = false
                path?.let { viewModel.selectLocal(Uri.fromFile(it.toFile())) }
            },
            fileFilter = { path ->
                path.fileName?.toString()?.endsWith(".apk", ignoreCase = true) == true
            },
            allowDirectorySelection = false,
            lastDirectoryPreference = prefs.lsposedModuleInputLastDirectory
        )
    }

    if (showAddDialog && framework.rootAvailable) AlertDialogExtended(
        onDismissRequest = onAddDialogDismiss,
        title = { Text(stringResource(R.string.lsposed_add_select_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Text(
                    stringResource(R.string.lsposed_add_select_description),
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Column {
                    AddModuleSourceRow(
                        selected = addSource == AddModuleSource.GITHUB,
                        overline = stringResource(R.string.lsposed_recommended),
                        title = stringResource(R.string.lsposed_enter_github_url),
                        description = stringResource(R.string.lsposed_github_source_description),
                        onClick = { addSource = AddModuleSource.GITHUB },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    AddModuleSourceRow(
                        selected = addSource == AddModuleSource.FILE,
                        title = stringResource(R.string.lsposed_select_from_storage),
                        description = stringResource(R.string.lsposed_storage_source_description),
                        onClick = { addSource = AddModuleSource.FILE },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onAddDialogDismiss()
                when (addSource) {
                    AddModuleSource.GITHUB -> urlDialog = true
                    AddModuleSource.FILE -> openModuleFilePicker()
                }
            }) { Text(stringResource(R.string.lsposed_next)) }
        },
        dismissButton = { TextButton(onAddDialogDismiss) { Text(stringResource(R.string.lsposed_cancel)) } },
        textHorizontalPadding = PaddingValues(0.dp),
    )

    if (urlDialog && framework.rootAvailable) AlertDialog(
        onDismissRequest = { urlDialog = false },
        title = { Text(stringResource(R.string.lsposed_add_from_github)) },
        text = {
            Column {
                Text(stringResource(R.string.lsposed_github_url_description))
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.lsposed_github_url)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = url.isNotBlank(),
                onClick = {
                    urlDialog = false
                    viewModel.resolveUrl(url)
                },
            ) { Text(stringResource(R.string.lsposed_continue)) }
        },
        dismissButton = { TextButton({ urlDialog = false }) { Text(stringResource(R.string.lsposed_cancel)) } },
    )

    if (framework.rootAvailable && viewModel.assetChoices.isNotEmpty()) AlertDialog(
        onDismissRequest = viewModel::dismissAssetChoices,
        title = { Text(stringResource(R.string.lsposed_choose_apk)) },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(viewModel.assetChoices, key = { it.asset.downloadUrl }) { choice ->
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { viewModel.chooseAsset(choice) },
                    ) { Text(choice.asset.name) }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(viewModel::dismissAssetChoices) { Text(stringResource(R.string.lsposed_cancel)) }
        },
    )

    if (framework.rootAvailable && !showInstallerPicker) viewModel.pendingModule?.let { module ->
        AlertDialog(
            onDismissRequest = viewModel::dismissPending,
            icon = { Icon(Icons.Outlined.WarningAmber, null) },
            title = {
                Text(
                    stringResource(R.string.lsposed_install_trust_title),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(R.string.lsposed_install_review_description),
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LsposedTrustDialogSectionLabel(
                            stringResource(R.string.lsposed_module_label)
                        )
                        Text(
                            module.displayName,
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                        Text(module.packageName, textAlign = TextAlign.Center)
                        Text(stringResource(R.string.lsposed_version_format, module.versionName))
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LsposedTrustDialogSectionLabel(
                            stringResource(R.string.lsposed_signing_certificate)
                        )
                        Text(
                            module.signingFingerprint,
                            modifier = Modifier.fillMaxWidth(),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Text(
                        if (module.checksumPublished)
                            stringResource(R.string.lsposed_checksum_verified)
                        else
                            stringResource(R.string.lsposed_checksum_missing),
                        color = if (module.checksumPublished) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        textAlign = TextAlign.Center,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (chooseInstallerPerInstall) {
                        showInstallerPicker = true
                    } else {
                        viewModel.installPending()
                    }
                }) { Text(stringResource(R.string.lsposed_install)) }
            },
            dismissButton = {
                TextButton(viewModel::dismissPending) { Text(stringResource(R.string.lsposed_cancel)) }
            },
        )
    }

    if (framework.rootAvailable && showInstallerPicker && viewModel.pendingModule != null) {
        InstallerPickerDialog(
            title = stringResource(R.string.installer_choose_for_this_install_title),
            options = installerManager.listEntries(
                target = InstallerManager.InstallTarget.LSPOSED_MODULE,
                includeNone = false,
            ),
            onDismiss = { showInstallerPicker = false },
            onConfirm = { installer ->
                showInstallerPicker = false
                viewModel.installPending(installer)
            },
            onOpenShizuku = installerManager::openShizukuApp,
        )
    }

    if (framework.rootAvailable) moduleToForget?.let { module ->
        ConfirmDialog(
            onDismiss = { moduleToForget = null },
            onConfirm = {
                moduleToForget = null
                viewModel.forget(module)
            },
            title = stringResource(R.string.lsposed_forget_title),
            description = stringResource(R.string.lsposed_forget_description, module.displayName),
            icon = Icons.Outlined.RemoveCircleOutline,
        )
    }

    if (framework.rootAvailable && viewModel.showInstallComplete) AlertDialog(
        onDismissRequest = viewModel::dismissInstallComplete,
        title = { Text(stringResource(R.string.lsposed_module_installed_title)) },
        text = { Text(stringResource(R.string.lsposed_module_installed_description)) },
        confirmButton = {
            Button(onClick = {
                viewModel.dismissInstallComplete()
                viewModel.openManager()
            }) { Text(stringResource(R.string.lsposed_open_manager)) }
        },
        dismissButton = {
            TextButton(viewModel::dismissInstallComplete) { Text(stringResource(R.string.lsposed_done)) }
        },
    )
}

private enum class AddModuleSource {
    GITHUB,
    FILE,
}

@Composable
private fun AddModuleSourceRow(
    selected: Boolean,
    title: String,
    description: String,
    overline: String? = null,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(role = Role.RadioButton, onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        overlineContent = { overline?.let { Text(it) } },
        leadingContent = {
            HapticRadioButton(
                selected = selected,
                onClick = null,
            )
        },
        colors = transparentListItemColors,
    )
}

@Composable
private fun LsposedTrustDialogSectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun FrameworkCard(
    rootAvailable: Boolean,
    installed: Boolean,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onOpen: () -> Unit,
) {
    LsposedStyledCard(
        title = stringResource(R.string.lsposed_framework_title),
        icon = Icons.Outlined.DeviceHub,
    ) {
        Text(
            stringResource(R.string.lsposed_framework_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (refreshing) {
            ShimmerBox(Modifier.fillMaxWidth(0.82f).height(20.dp))
        } else {
            Text(
                when {
                    !rootAvailable ->
                        stringResource(R.string.lsposed_root_unavailable)
                    installed -> stringResource(R.string.lsposed_framework_available)
                    else -> stringResource(R.string.lsposed_framework_missing)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (!rootAvailable) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
        val actions = buildList {
            add(
                LsposedAction(
                    stringResource(R.string.lsposed_refresh),
                    Icons.Outlined.Refresh,
                    enabled = !refreshing,
                    onClick = onRefresh,
                )
            )
            if (installed) {
                add(LsposedAction(stringResource(R.string.lsposed_open_manager_short), Icons.Outlined.OpenInNew, onClick = onOpen))
            }
        }
        LsposedActionRow(actions)
    }
}

@Composable
private fun ModuleCard(
    module: LsposedModule,
    actionOrder: List<LsposedModuleActionKey>,
    onOpenManager: () -> Unit,
    onSettings: () -> Unit,
    onCheck: () -> Unit,
    onUpdate: () -> Unit,
    onReinstall: () -> Unit,
    onUninstall: () -> Unit,
    onForget: () -> Unit,
) {
    LsposedStyledCard(
        title = module.displayName,
        icon = Icons.Outlined.ViewModule,
    ) {
        Text(
            stringResource(R.string.lsposed_module_summary, module.packageName, module.installedVersion),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (module.updateAvailable) {
            Text(
                stringResource(R.string.lsposed_update_available, module.latestVersion.orEmpty()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        val actions = actionOrder.mapNotNull { key ->
            when (key) {
                LsposedModuleActionKey.MANAGER ->
                    LsposedAction(stringResource(R.string.lsposed_manager), Icons.Outlined.OpenInNew, onClick = onOpenManager)
                LsposedModuleActionKey.SETTINGS ->
                    LsposedAction(stringResource(R.string.lsposed_settings), Icons.Outlined.Settings, onClick = onSettings)
                LsposedModuleActionKey.UPDATE -> when {
                    module.sourceKind == LsposedSourceKind.LOCAL_FILE.name -> null
                    module.updateAvailable ->
                        LsposedAction(stringResource(R.string.lsposed_update), Icons.Outlined.Download, onClick = onUpdate)
                    else ->
                        LsposedAction(stringResource(R.string.lsposed_check_update), Icons.Outlined.Update, onClick = onCheck)
                }
                LsposedModuleActionKey.REINSTALL ->
                    if (module.sourceKind == LsposedSourceKind.LOCAL_FILE.name) {
                        LsposedAction(stringResource(R.string.lsposed_reinstall), Icons.Outlined.Refresh, onClick = onReinstall)
                    } else null
                LsposedModuleActionKey.UNINSTALL ->
                    LsposedAction(stringResource(R.string.lsposed_uninstall), Icons.Outlined.Delete, onClick = onUninstall)
                LsposedModuleActionKey.FORGET ->
                    LsposedAction(stringResource(R.string.lsposed_forget), Icons.Outlined.RemoveCircleOutline, onClick = onForget)
            }
        }
        if (actions.isNotEmpty()) LsposedActionRow(actions)
    }
}

private data class LsposedAction(
    val text: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

@Composable
private fun LsposedStyledCard(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(shape),
        shape = shape,
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                icon?.let {
                    Icon(it, null, modifier = Modifier.size(18.dp))
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun LsposedActionRow(actions: List<LsposedAction>) {
    val scrollState = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .widthIn(min = maxWidth)
                .horizontalScroll(scrollState),
            horizontalArrangement = Arrangement.spacedBy(
                6.dp,
                Alignment.CenterHorizontally,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions.forEach { action ->
                LsposedActionPill(
                    text = action.text,
                    icon = action.icon,
                    enabled = action.enabled,
                    onClick = action.onClick,
                )
            }
        }
    }
}

@Composable
private fun LsposedActionPill(
    text: String,
    icon: ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val contentColor = MaterialTheme.colorScheme.onSurface.copy(
        alpha = if (enabled) 1f else 0.6f
    )
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                MaterialTheme.colorScheme.surface.copy(
                    alpha = if (enabled) 0.9f else 0.5f
                )
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = text,
                tint = contentColor,
                modifier = Modifier.size(14.dp),
            )
            if (!minimalActionButtons("lsposed")) Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                maxLines = 1,
            )
        }
    }
}
