package app.universal.patch.manager.ui.screen.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.view.HapticFeedbackConstants
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universal.patch.manager.ui.component.settings.SettingsSearchHighlight
import kotlinx.coroutines.flow.first
import app.universal.patch.manager.R
import app.universal.patch.manager.network.runtime.PatcherRuntimeKind
import app.universal.patch.manager.network.runtime.PatcherRuntimePluginSourceState
import app.universal.patch.manager.network.runtime.PatcherRuntimePluginState
import app.universal.patch.manager.network.runtime.toRuntimeMainName
import app.universal.patch.manager.ui.component.AppIcon
import app.universal.patch.manager.ui.component.AppTopBar
import app.universal.patch.manager.ui.component.ConfirmDialog
import app.universal.patch.manager.ui.component.ExceptionViewerDialog
import app.universal.patch.manager.ui.component.GroupHeader
import app.universal.patch.manager.ui.component.LazyColumnWithScrollbar
import app.universal.patch.manager.ui.component.SettingsSectionIcons
import app.universal.patch.manager.ui.component.TransparentLoadingDialog
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsCard
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsDivider
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsItem
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsSwitch
import app.universal.patch.manager.ui.viewmodel.PatcherRuntimePluginsViewModel
import app.universal.patch.manager.util.longPressOnly
import app.universal.patch.manager.util.toast
import app.universal.patch.manager.util.withHapticFeedback
import org.koin.androidx.compose.koinViewModel
import java.security.MessageDigest
import app.universal.patch.manager.ui.component.CenteredDialogTitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatcherRuntimePluginsSettingsScreen(
    onBackClick: () -> Unit,
    viewModel: PatcherRuntimePluginsViewModel = koinViewModel()
) {
    val topAppBarScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val pluginStates by viewModel.runtimePluginStates.collectAsStateWithLifecycle(emptyMap())
    val sourceStates by viewModel.runtimePluginSourceStates.collectAsStateWithLifecycle(emptyMap())
    val loadedRuntimes by viewModel.loadedRuntimes.collectAsStateWithLifecycle(emptyMap())
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val searchTarget by SettingsSearchState.target.collectAsStateWithLifecycle()
    var highlightTarget by rememberSaveable { mutableStateOf<Int?>(null) }
    LaunchedEffect(searchTarget) {
        val target = searchTarget
        if (target?.destination == app.universal.patch.manager.ui.model.navigation.Settings.PatcherRuntimes) {
            val itemCount = androidx.compose.runtime.snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
            listState.scrollToItem((if (target.targetId == R.string.patcher_runtime_available) 0 else 2).coerceAtMost(itemCount - 1))
            highlightTarget = target.targetId
            SettingsSearchState.clear()
        }
    }
    val remoteSourceBusyState = viewModel.remoteSourceBusyState
    var showImportUrlDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.acknowledgeNewPlugins()
        viewModel.refreshPlugins()
    }

    if (showImportUrlDialog) {
        ImportRuntimeSourceDialog(
            onDismiss = { showImportUrlDialog = false },
            onImport = { url ->
                viewModel.importPluginSource(url)
                showImportUrlDialog = false
            }
        )
    }
    if (remoteSourceBusyState != null) {
        TransparentLoadingDialog()
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.patcher_runtime_plugins),
                onBackClick = onBackClick,
                scrollBehavior = topAppBarScrollBehavior
            )
        },
        modifier = Modifier.nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
    ) { padding ->
        PullToRefreshBox(
            onRefresh = viewModel::refreshPlugins,
            isRefreshing = viewModel.isRefreshingPlugins,
            modifier = Modifier.padding(padding)
        ) {
            LazyColumnWithScrollbar(
                thumbLength = app.universal.patch.manager.ui.component.DASHBOARD_SCROLLBAR_THUMB_LENGTH,
                state = listState,
                modifier = Modifier.fillMaxSize()
            ) {
                item {
                    SettingsSearchHighlight(
                        targetKey = R.string.patcher_runtime_available,
                        activeKey = highlightTarget,
                        onHighlightComplete = { highlightTarget = null }
                    ) { highlightModifier ->
                        GroupHeader(
                            stringResource(R.string.patcher_runtime_available),
                            icon = SettingsSectionIcons.PatchingEngine,
                            modifier = highlightModifier
                        )
                    }
                }
                item {
                    RuntimeSummaryCard(
                        loadedRuntimes = loadedRuntimes.keys,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
                item {
                    SettingsSearchHighlight(
                        targetKey = R.string.patcher_runtime_plugins_section,
                        extraKeys = setOf(R.string.patcher_runtime_plugins, R.string.patcher_runtime_import_url,
                            R.string.patcher_runtime_managed_sources, R.string.patcher_runtime_installed_plugins),
                        activeKey = highlightTarget,
                        onHighlightComplete = { highlightTarget = null }
                    ) { highlightModifier ->
                        GroupHeader(
                            stringResource(R.string.patcher_runtime_plugins_section),
                            icon = SettingsSectionIcons.DownloaderPlugins,
                            modifier = highlightModifier
                        )
                    }
                }
                item {
                    RuntimeImportCard(
                        enabled = remoteSourceBusyState == null,
                        onImportClick = { showImportUrlDialog = true },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
                sourceStates.forEach { (sourceId, source) ->
                    item(key = "source:$sourceId") {
                        ManagedRuntimeCard(
                            source = source,
                            viewModel = viewModel,
                            remoteSourceBusyState = remoteSourceBusyState,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }
                }
                pluginStates.forEach { (packageName, state) ->
                    item(key = packageName) {
                        InstalledRuntimeCard(
                            packageName = packageName,
                            state = state,
                            viewModel = viewModel,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }
                }
                if (sourceStates.isEmpty() && pluginStates.isEmpty()) {
                    item {
                        EmptyRuntimeText(stringResource(R.string.patcher_runtime_no_installed_plugins))
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun RuntimeSummaryCard(
    loadedRuntimes: Set<PatcherRuntimeKind>,
    modifier: Modifier = Modifier
) {
    ExpressiveSettingsCard(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
    ) {
        ExpressiveSettingsItem(
            headlineContent = stringResource(R.string.patcher_runtime_available),
            supportingContent = stringResource(R.string.patcher_runtime_plugins_description),
            leadingContent = {
                RuntimePluginLeadingIcon(
                    icon = Icons.Outlined.Security
                )
            }
        )
        ExpressiveSettingsDivider()
        RuntimeAvailabilityRow(name = "Reseam", available = true, builtIn = true)
        RuntimeAvailabilityRow(name = "Morphe", available = true, builtIn = true)
        PatcherRuntimeKind.entries.forEach { kind ->
            RuntimeAvailabilityRow(
                name = kind.displayName,
                available = kind in loadedRuntimes
            )
        }
    }
}

@Composable
private fun RuntimeAvailabilityRow(
    name: String,
    available: Boolean,
    builtIn: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(12.dp))
        RuntimeStatusPill(
            text = if (builtIn) {
                stringResource(R.string.patcher_runtime_built_in)
            } else {
                stringResource(
                    if (available) R.string.patcher_runtime_available_yes
                    else R.string.patcher_runtime_available_no
                )
            },
            icon = when {
                builtIn -> Icons.Outlined.Build
                available -> Icons.Filled.Check
                else -> Icons.Filled.Close
            },
            emphasized = available || builtIn
        )
    }
}

@Composable
private fun RuntimeStatusPill(
    text: String,
    icon: ImageVector,
    emphasized: Boolean
) {
    val containerColor = if (emphasized) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val contentColor = if (emphasized) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(containerColor)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor
        )
    }
}

@Composable
private fun RuntimeImportCard(
    enabled: Boolean,
    onImportClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ExpressiveSettingsCard(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
    ) {
        ExpressiveSettingsItem(
            headlineContent = stringResource(R.string.patcher_runtime_import_url),
            supportingContent = stringResource(R.string.patcher_runtime_import_url_description)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.End
        ) {
            FilledTonalButton(
                onClick = onImportClick,
                enabled = enabled
            ) {
                Text(stringResource(R.string.import_button))
            }
        }
    }
}

@Composable
private fun ManagedRuntimeCard(
    source: PatcherRuntimePluginSourceState,
    viewModel: PatcherRuntimePluginsViewModel,
    remoteSourceBusyState: PatcherRuntimePluginsViewModel.RemoteSourceBusyState?,
    modifier: Modifier = Modifier
) {
    var showTrustDialog by remember { mutableStateOf(false) }
    var showErrorDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRevokeTrustDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    val untrusted = source.state as? PatcherRuntimePluginSourceState.State.Untrusted
    val failed = source.state as? PatcherRuntimePluginSourceState.State.Failed
    val actionsEnabled = remoteSourceBusyState == null
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    val sourceName = remember(source.name) { source.name.toRuntimeMainName() }
    val sourceLabel = remember(sourceName) { sourceName.toRuntimeDisplayLabel() }

    RuntimePluginCard(
        title = sourceName,
        version = source.version,
        status = when (source.state) {
            is PatcherRuntimePluginSourceState.State.Loaded ->
                stringResource(R.string.downloader_source_state_loaded)
            is PatcherRuntimePluginSourceState.State.Untrusted ->
                stringResource(R.string.downloader_plugin_state_untrusted)
            is PatcherRuntimePluginSourceState.State.Failed ->
                stringResource(R.string.downloader_source_state_failed)
            PatcherRuntimePluginSourceState.State.Missing ->
                stringResource(R.string.patcher_runtime_missing)
        },
        type = RuntimePluginType.Remote,
        detail = source.repoUrl.toGitHubRepoDisplayName(),
        modifier = modifier,
        secondaryActionLabel = stringResource(R.string.delete),
        onSecondaryAction = { showDeleteDialog = true },
        middleActionLabel = stringResource(R.string.settings),
        onMiddleAction = { showSettingsDialog = true },
        primaryActionLabel = stringResource(
            if (untrusted != null) R.string.trust else R.string.update
        ),
        primaryActionStyle = if (untrusted != null) {
            RuntimeActionStyle.FilledTonal
        } else {
            RuntimeActionStyle.Outlined
        },
        onPrimaryAction = {
            if (untrusted != null) {
                showTrustDialog = true
            } else {
                viewModel.updatePluginSource(source.entry.id)
            }
        },
        primaryActionEnabled = actionsEnabled,
        middleActionEnabled = actionsEnabled,
        secondaryActionEnabled = actionsEnabled,
        footerActionLabel = stringResource(R.string.downloader_plugin_revoke_trust)
            .takeIf {
                source.state !is PatcherRuntimePluginSourceState.State.Untrusted &&
                    source.state !is PatcherRuntimePluginSourceState.State.Missing
            },
        footerActionStyle = RuntimeActionStyle.FilledTonal,
        onFooterAction = { showRevokeTrustDialog = true },
        footerActionEnabled = actionsEnabled,
        extraSupportingContent = {
            if (failed != null) {
                TextButton(
                    modifier = Modifier.padding(horizontal = 0.dp),
                    onClick = { showErrorDialog = true }
                ) {
                    Text(stringResource(R.string.downloader_plugin_view_error))
                }
            }
        }
    )

    if (showTrustDialog && untrusted != null) {
        TrustRuntimeDialog(
            title = sourceName,
            signature = untrusted.signature,
            secondaryLabel = R.string.delete,
            onDismiss = { showTrustDialog = false },
            onTrust = {
                showTrustDialog = false
                viewModel.trustPluginSource(source.entry.id)
            },
            onSecondary = {
                showTrustDialog = false
                showDeleteDialog = true
            }
        )
    }
    if (showErrorDialog && failed != null) {
        ExceptionViewerDialog(
            text = remember(failed.throwable) {
                failed.throwable.stackTraceToString()
            },
            onDismiss = { showErrorDialog = false }
        )
    }
    if (showDeleteDialog) {
        ConfirmDialog(
            title = stringResource(R.string.patcher_runtime_source_delete_title),
            description = stringResource(R.string.patcher_runtime_source_delete_description, sourceLabel),
            icon = Icons.Outlined.Delete,
            confirmLabelRes = R.string.confirm,
            onDismiss = { showDeleteDialog = false },
            onConfirm = {
                showDeleteDialog = false
                viewModel.removePluginSource(source.entry.id)
            }
        )
    }
    if (
        showRevokeTrustDialog &&
        source.state !is PatcherRuntimePluginSourceState.State.Untrusted &&
        source.state !is PatcherRuntimePluginSourceState.State.Missing
    ) {
        ConfirmDialog(
            title = stringResource(R.string.downloader_plugin_revoke_trust_dialog_title),
            description = stringResource(
                R.string.patcher_runtime_revoke_trust_description,
                sourceLabel
            ),
            icon = Icons.Outlined.WarningAmber,
            onDismiss = { showRevokeTrustDialog = false },
            onConfirm = {
                showRevokeTrustDialog = false
                viewModel.revokePluginSourceTrust(source.entry.id)
            }
        )
    }
    if (showSettingsDialog) {
        RuntimeSourceSettingsDialog(
            source = source,
            onDismiss = { showSettingsDialog = false },
            onAutoUpdateChanged = { viewModel.setPluginSourceAutoUpdate(source.entry.id, it) },
            onLatestChanged = { viewModel.setPluginSourceLatest(source.entry.id, it) },
            onPrereleaseChanged = { viewModel.setPluginSourcePrerelease(source.entry.id, it) },
            onCopyRepoUrl = {
                clipboard?.setPrimaryClip(
                    ClipData.newPlainText(
                        source.repoUrl.toGitHubRepoDisplayName(),
                        source.repoUrl
                    )
                )
                context.toast(context.getString(R.string.runtime_plugin_url_copied))
            }
        )
    }
}

@Composable
private fun InstalledRuntimeCard(
    packageName: String,
    state: PatcherRuntimePluginState,
    viewModel: PatcherRuntimePluginsViewModel,
    modifier: Modifier = Modifier
) {
    var showTrustDialog by remember { mutableStateOf(false) }
    var showErrorDialog by remember { mutableStateOf(false) }
    var showRevokeTrustDialog by remember { mutableStateOf(false) }
    var showUninstallDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val loaded = state as? PatcherRuntimePluginState.Loaded
    val failed = state as? PatcherRuntimePluginState.Failed
    val packageInfo = remember(packageName, state) {
        viewModel.pm.getPackageInfo(packageName)
    }
    val appName = remember(packageName, packageInfo) {
        packageInfo?.applicationInfo?.loadLabel(context.packageManager)?.toString()
            ?: packageName
    }
    val runtimeName = remember(appName) { appName.toRuntimeMainName() }
    val runtimeLabel = remember(runtimeName) { runtimeName.toRuntimeDisplayLabel() }
    val signature = remember(packageName, state) {
        runCatching {
            val androidSignature = viewModel.pm.getSignature(packageName)
            MessageDigest.getInstance("SHA-256")
                .digest(androidSignature.toByteArray())
                .toUpperHexString()
        }.getOrNull()
    }

    RuntimePluginCard(
        title = runtimeName,
        version = packageInfo?.versionName ?: loaded?.plugin?.version,
        status = when (state) {
            is PatcherRuntimePluginState.Loaded -> stringResource(R.string.downloader_source_state_loaded)
            is PatcherRuntimePluginState.Failed -> stringResource(R.string.downloader_source_state_failed)
            PatcherRuntimePluginState.Untrusted -> stringResource(R.string.downloader_plugin_state_untrusted)
        },
        type = RuntimePluginType.Local,
        detail = packageName,
        modifier = modifier,
        secondaryActionLabel = stringResource(R.string.uninstall),
        onSecondaryAction = { showUninstallDialog = true },
        primaryActionLabel = stringResource(
            when (state) {
                is PatcherRuntimePluginState.Loaded ->
                    R.string.downloader_plugin_revoke_trust
                is PatcherRuntimePluginState.Failed ->
                    R.string.downloader_plugin_view_error
                PatcherRuntimePluginState.Untrusted ->
                    R.string.trust
            }
        ),
        onPrimaryAction = {
            when (state) {
                is PatcherRuntimePluginState.Loaded ->
                    showRevokeTrustDialog = true
                is PatcherRuntimePluginState.Failed ->
                    showErrorDialog = true
                PatcherRuntimePluginState.Untrusted ->
                    showTrustDialog = true
            }
        },
        leadingContent = {
            RuntimePluginAppIcon(packageInfo = packageInfo)
        }
    )

    if (showTrustDialog) {
        TrustRuntimeDialog(
            title = runtimeName,
            signature = signature,
            secondaryLabel = R.string.uninstall,
            onDismiss = { showTrustDialog = false },
            onTrust = {
                showTrustDialog = false
                viewModel.trustPlugin(packageName)
            },
            onSecondary = {
                showTrustDialog = false
                showUninstallDialog = true
            }
        )
    }
    if (showErrorDialog && failed != null) {
        ExceptionViewerDialog(
            text = remember(failed.throwable) {
                failed.throwable.stackTraceToString()
            },
            onDismiss = { showErrorDialog = false }
        )
    }
    if (showRevokeTrustDialog && loaded != null) {
        ConfirmDialog(
            title = stringResource(R.string.downloader_plugin_revoke_trust_dialog_title),
            description = stringResource(
                R.string.patcher_runtime_revoke_trust_description,
                runtimeLabel
            ),
            icon = Icons.Outlined.WarningAmber,
            onDismiss = { showRevokeTrustDialog = false },
            onConfirm = {
                showRevokeTrustDialog = false
                viewModel.revokePluginTrust(packageName)
            }
        )
    }
    if (showUninstallDialog) {
        ConfirmDialog(
            title = stringResource(R.string.patcher_runtime_uninstall_title),
            description = stringResource(R.string.patcher_runtime_uninstall_description, runtimeLabel),
            icon = Icons.Outlined.Delete,
            confirmLabelRes = R.string.confirm,
            onDismiss = { showUninstallDialog = false },
            onConfirm = {
                showUninstallDialog = false
                viewModel.uninstallPlugin(packageName)
            }
        )
    }
}

private enum class RuntimePluginType(@StringRes val labelRes: Int) {
    Local(R.string.downloader_plugin_type_legacy),
    Remote(R.string.downloader_plugin_type_modern)
}

private enum class RuntimeActionStyle {
    Outlined,
    FilledTonal
}

private fun String.toRuntimeDisplayLabel(): String =
    if (endsWith(" runtime", ignoreCase = true)) this else "$this runtime"

private fun String?.toRuntimeVersionLabel(): String? {
    val version = this?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return "v${version.removePrefix("v").removePrefix("V")}"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RuntimePluginCard(
    title: String,
    version: String?,
    status: String,
    type: RuntimePluginType,
    detail: String,
    primaryActionLabel: String,
    secondaryActionLabel: String,
    onPrimaryAction: () -> Unit,
    onSecondaryAction: () -> Unit,
    modifier: Modifier = Modifier,
    middleActionLabel: String? = null,
    onMiddleAction: (() -> Unit)? = null,
    footerActionLabel: String? = null,
    onFooterAction: (() -> Unit)? = null,
    primaryActionStyle: RuntimeActionStyle = RuntimeActionStyle.FilledTonal,
    footerActionStyle: RuntimeActionStyle = RuntimeActionStyle.Outlined,
    extraSupportingContent: (@Composable (() -> Unit))? = null,
    primaryActionEnabled: Boolean = true,
    secondaryActionEnabled: Boolean = true,
    middleActionEnabled: Boolean = true,
    footerActionEnabled: Boolean = true,
    leadingContent: (@Composable () -> Unit)? = null
) {
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    val copiedToClipboardMessage = stringResource(R.string.toast_copied_to_clipboard)
    val copyToClipboardLabel = stringResource(R.string.copy_to_clipboard)
    val supportingSlot: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "${stringResource(R.string.patcher_runtime_type_label)} " +
                    stringResource(type.labelRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            RuntimePluginDetailBox(detail = detail)
            extraSupportingContent?.invoke()
        }
    }

    ExpressiveSettingsCard(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
    ) {
        ExpressiveSettingsItem(
            headlineContent = {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            },
            supportingContentSlot = supportingSlot,
            leadingContent = leadingContent ?: {
                RuntimePluginLeadingIcon(
                    icon = when (type) {
                        RuntimePluginType.Local -> Icons.Outlined.Folder
                        RuntimePluginType.Remote -> Icons.Outlined.Link
                    }
                )
            },
            trailingContent = version.toRuntimeVersionLabel()?.let { pluginVersion ->
                {
                    val copyRuntimeVersion = {
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
                            onLongPress = copyRuntimeVersion
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onSecondaryAction,
                enabled = secondaryActionEnabled,
                modifier = Modifier.weight(1f)
            ) {
                RuntimeActionText(secondaryActionLabel)
            }
            if (middleActionLabel != null && onMiddleAction != null) {
                OutlinedButton(
                    onClick = onMiddleAction,
                    enabled = middleActionEnabled,
                    modifier = Modifier.weight(1f)
                ) {
                    RuntimeActionText(middleActionLabel)
                }
            }
            RuntimeActionButton(
                label = primaryActionLabel,
                onClick = onPrimaryAction,
                enabled = primaryActionEnabled,
                modifier = Modifier.weight(1f),
                style = primaryActionStyle
            )
        }
        if (footerActionLabel != null && onFooterAction != null) {
            RuntimeActionButton(
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
private fun RuntimeActionButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    style: RuntimeActionStyle = RuntimeActionStyle.FilledTonal
) {
    when (style) {
        RuntimeActionStyle.Outlined -> OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier
        ) {
            RuntimeActionText(label)
        }
        RuntimeActionStyle.FilledTonal -> FilledTonalButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier
        ) {
            RuntimeActionText(label)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RuntimeActionText(text: String) {
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
private fun RuntimePluginAppIcon(packageInfo: android.content.pm.PackageInfo?) {
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
            AppIcon(
                packageInfo = packageInfo,
                contentDescription = null,
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
        }
    }
}

@Composable
private fun RuntimePluginLeadingIcon(icon: ImageVector) {
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
private fun RuntimePluginDetailBox(detail: String) {
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
private fun RuntimeSourceSettingsDialog(
    source: PatcherRuntimePluginSourceState,
    onDismiss: () -> Unit,
    onAutoUpdateChanged: (Boolean) -> Unit,
    onLatestChanged: (Boolean) -> Unit,
    onPrereleaseChanged: (Boolean) -> Unit,
    onCopyRepoUrl: () -> Unit
) {
    val sourceName = remember(source.name) { source.name.toRuntimeMainName() }
    val sourceLabel = remember(sourceName) { sourceName.toRuntimeDisplayLabel() }
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
                        RuntimeSourceSwitchRow(
                            title = stringResource(R.string.downloader_source_auto_update),
                            description = stringResource(R.string.auto_update_description),
                            checked = source.entry.autoUpdate,
                            onCheckedChange = onAutoUpdateChanged
                        )
                        RuntimeSourceSwitchRow(
                            title = stringResource(R.string.downloader_source_prerelease),
                            description = stringResource(R.string.downloader_source_prerelease_description),
                            checked = source.entry.prerelease && !source.entry.latest,
                            onCheckedChange = onPrereleaseChanged,
                            enabled = !source.entry.latest
                        )
                        RuntimeSourceSwitchRow(
                            title = stringResource(R.string.downloader_source_latest),
                            description = stringResource(R.string.downloader_source_latest_description),
                            checked = source.entry.latest,
                            onCheckedChange = onLatestChanged
                        )
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

@Composable
private fun RuntimeSourceSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                }
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                }
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        ExpressiveSettingsSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
private fun EmptyRuntimeText(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun TrustRuntimeDialog(
    title: String,
    signature: String?,
    @StringRes secondaryLabel: Int? = null,
    onDismiss: () -> Unit,
    onTrust: () -> Unit,
    onSecondary: (() -> Unit)? = null
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
                text = stringResource(R.string.patcher_runtime_trust_dialog_title),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.patcher_runtime_trust_dialog_body),
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
                        text = title,
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
                        text = stringResource(R.string.patcher_runtime_signature_label)
                    )
                    if (signature != null) {
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
                    } else {
                        Text(
                            text = stringResource(
                                R.string.patcher_runtime_signature_after_trust
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
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
                TextButton(onClick = onTrust) {
                    Text(stringResource(R.string.confirm))
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
private fun ImportRuntimeSourceDialog(
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
        title = { CenteredDialogTitle(stringResource(R.string.patcher_runtime_import_url)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.patcher_runtime_import_url_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    value = url,
                    onValueChange = { url = it },
                    placeholder = { Text(stringResource(R.string.patcher_runtime_import_url_hint)) },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onImport(trimmedUrl) },
                enabled = trimmedUrl.isNotEmpty()
            ) {
                Text(stringResource(R.string.import_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
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

private fun ByteArray.toUpperHexString(): String {
    val result = StringBuilder(size * 2)
    for (byte in this) {
        result.append(HEX_CHARS[(byte.toInt() ushr 4) and 0x0F])
        result.append(HEX_CHARS[byte.toInt() and 0x0F])
    }
    return result.toString()
}

private const val HEX_CHARS = "0123456789ABCDEF"
