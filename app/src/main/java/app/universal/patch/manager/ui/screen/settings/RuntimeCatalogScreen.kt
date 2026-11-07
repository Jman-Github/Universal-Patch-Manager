package app.universal.patch.manager.ui.screen.settings

import android.text.format.Formatter
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universal.patch.manager.R
import app.universal.patch.manager.network.runtime.LoadedPatcherRuntimePlugin
import app.universal.patch.manager.network.runtime.RuntimeCatalogItem
import app.universal.patch.manager.network.runtime.RuntimeDownloadProgress
import app.universal.patch.manager.network.runtime.RuntimeDownloadStage
import app.universal.patch.manager.network.runtime.canCompleteRuntimeSetup
import app.universal.patch.manager.ui.component.AppScaffold
import app.universal.patch.manager.ui.component.AppTopBar
import app.universal.patch.manager.ui.component.DASHBOARD_SCROLLBAR_THUMB_LENGTH
import app.universal.patch.manager.ui.component.GroupHeader
import app.universal.patch.manager.ui.component.LazyColumnWithScrollbar
import app.universal.patch.manager.ui.component.RuntimePluginIcon
import app.universal.patch.manager.ui.component.SettingsSectionIcons
import app.universal.patch.manager.ui.component.haptics.HapticCheckbox
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsCard
import app.universal.patch.manager.ui.viewmodel.PatcherRuntimePluginsViewModel
import app.universal.patch.manager.util.withHapticFeedback
import org.koin.androidx.compose.koinViewModel
import java.text.NumberFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuntimeCatalogScreen(
    onboarding: Boolean,
    onBackClick: () -> Unit = {},
    viewModel: PatcherRuntimePluginsViewModel = koinViewModel()
) {
    androidx.activity.compose.BackHandler(enabled = !onboarding) { onBackClick() }
    val loaded by viewModel.loadedRuntimes.collectAsStateWithLifecycle(emptyMap())
    val managed by viewModel.catalogRuntimes.collectAsStateWithLifecycle(emptyMap())
    val downloaded by viewModel.downloadedCatalogRuntimes.collectAsStateWithLifecycle(emptyMap())
    val progress by viewModel.downloadProgress.collectAsStateWithLifecycle()
    val selected = remember(viewModel.catalog, viewModel.selectedCatalogIds) {
        viewModel.catalog.engineSelection(viewModel.selectedCatalogIds)
    }
    val required = remember(viewModel.catalog, selected) { viewModel.catalog.installationOrder(selected) }
    val ready = canCompleteRuntimeSetup(viewModel.catalog, selected, managed.keys)
    val busy = viewModel.currentDownload != null || progress != null
    val pending = required.filter { it.runtimeKind !in downloaded }
    val sizeKnown = pending.all { it.id in viewModel.catalogReleases }
    val size = pending.sumOf { viewModel.catalogReleases[it.id]?.manifest?.size ?: 0L }
    val step = required.indexOfFirst { it.id == progress?.catalogId }
    var showMenu by remember { mutableStateOf(false) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let(viewModel::importRuntimeFile)
    }

    AppScaffold(
        topBar = { scrollBehavior ->
            AppTopBar(
                title = stringResource(R.string.runtime_setup_title),
                onBackClick = if (onboarding) null else onBackClick,
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(enabled = !busy && !viewModel.catalogLoading, onClick = { viewModel.refreshCatalog() }) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.runtime_catalog_refresh))
                    }
                    Box {
                        IconButton(enabled = !busy, onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, stringResource(R.string.runtime_catalog_more))
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.runtime_catalog_import_file)) },
                                enabled = !busy,
                                onClick = {
                                    showMenu = false
                                    filePicker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
                                }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Column(
                        Modifier.navigationBarsPadding().fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (busy) {
                            RuntimeTransferProgress(
                                progress = progress,
                                fallbackName = viewModel.currentDownload.orEmpty(),
                                step = step,
                                stepCount = required.size
                            )
                        } else {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    stringResource(R.string.runtime_catalog_selected_count, selected.size),
                                    Modifier.weight(1f), style = MaterialTheme.typography.labelLarge
                                )
                                Text(
                                    if (ready) stringResource(R.string.runtime_catalog_ready)
                                    else if (sizeKnown && size > 0) stringResource(R.string.runtime_catalog_download_size, formatSize(size))
                                    else if (selected.isNotEmpty()) stringResource(R.string.runtime_catalog_pending)
                                    else stringResource(R.string.runtime_catalog_select_hint),
                                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                                )
                            }
                        }
                        Button(
                            enabled = selected.isNotEmpty() && !busy && (ready || !viewModel.catalogLoading),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            onClick = {
                                if (ready) {
                                    if (onboarding) viewModel.completeRuntimeSetup() else onBackClick()
                                } else viewModel.downloadSelected()
                            }
                        ) {
                            if (!busy) {
                                Icon(if (ready) Icons.Default.Check else Icons.Outlined.Download, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(stringResource(when {
                                busy -> R.string.runtime_catalog_in_progress
                                ready && !onboarding -> R.string.done
                                ready -> R.string.runtime_setup_continue
                                selected.isNotEmpty() && pending.isEmpty() -> R.string.runtime_catalog_finish_replacing
                                else -> R.string.runtime_catalog_download
                            }))
                        }
                    }
                }
            }
        }
    ) { padding ->
        LazyColumnWithScrollbar(
            modifier = Modifier.fillMaxSize().padding(padding),
            thumbLength = DASHBOARD_SCROLLBAR_THUMB_LENGTH,
            contentPadding = PaddingValues(top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                ExpressiveSettingsCard(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(20.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                            Icon(
                                Icons.Outlined.Extension, contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(14.dp).size(24.dp)
                            )
                        }
                        Text(
                            stringResource(R.string.runtime_catalog_choose),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(if (onboarding) R.string.runtime_setup_description else R.string.runtime_catalog_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (viewModel.catalogLoading) item {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp))
            }
            viewModel.catalogWarning?.let { warning ->
                item { RuntimeCatalogMessage(stringResource(R.string.runtime_catalog_cached, warning), error = true) }
            }
            viewModel.downloadError?.let { error ->
                item { RuntimeCatalogMessage(error, error = true) }
            }
            item { GroupHeader(stringResource(R.string.patcher_runtime_available), SettingsSectionIcons.PatchingEngine) }
            items(viewModel.catalog.plugins.filter { it.isEngine }, key = { it.id }) { item ->
                RuntimeChoiceCard(
                    item = item, installed = managed[item.runtimeKind], existing = loaded[item.runtimeKind],
                    selected = item.id in selected, busy = busy,
                    progress = progress?.takeIf { it.catalogId == item.id }, viewModel = viewModel
                )
            }
        }
    }
}

@Composable
private fun RuntimeChoiceCard(
    item: RuntimeCatalogItem,
    installed: LoadedPatcherRuntimePlugin?,
    existing: LoadedPatcherRuntimePlugin?,
    selected: Boolean,
    busy: Boolean,
    progress: RuntimeDownloadProgress?,
    viewModel: PatcherRuntimePluginsViewModel
) {
    val release = viewModel.catalogReleases[item.id]
    val shape = MaterialTheme.shapes.extraLarge
    val onCheckedChange: (Boolean) -> Unit = { viewModel.selectCatalogRuntime(item.id, it) }
    ExpressiveSettingsCard(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(shape)
            .border(1.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
            .toggleable(
                value = selected, enabled = !busy, role = Role.Checkbox,
                onValueChange = onCheckedChange.withHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            ).semantics(mergeDescendants = true) {},
        containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
        contentPadding = PaddingValues(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RuntimePluginIcon(
                apk = installed?.apkFile ?: existing?.apkFile, pm = viewModel.pm,
                previewUrl = release?.iconUrl, modifier = Modifier.size(44.dp)
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(when (item.runtimeKind) {
                    app.universal.patch.manager.network.runtime.PatcherRuntimeKind.REVANCED_V21 -> R.string.runtime_description_revanced_v21
                    app.universal.patch.manager.network.runtime.PatcherRuntimeKind.REVANCED_V22 -> R.string.runtime_description_revanced_v22
                    app.universal.patch.manager.network.runtime.PatcherRuntimeKind.MORPHE -> R.string.runtime_description_morphe
                    app.universal.patch.manager.network.runtime.PatcherRuntimeKind.RESEAM -> R.string.runtime_description_reseam
                    app.universal.patch.manager.network.runtime.PatcherRuntimeKind.SHARED_SUPPORT -> R.string.runtime_description_support
                }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HapticCheckbox(checked = selected, onCheckedChange = null, enabled = !busy)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            when {
                installed != null -> stringResource(R.string.runtime_catalog_installed, installed.version)
                release != null -> stringResource(
                    R.string.runtime_catalog_version, release.manifest.version, release.manifest.upstreamVersion,
                    formatSize(release.manifest.size)
                )
                viewModel.catalogLoading -> stringResource(R.string.runtime_catalog_checking)
                else -> stringResource(R.string.runtime_catalog_unavailable)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (progress != null || installed != null || existing != null || release != null) {
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = MaterialTheme.shapes.large,
                color = if (selected) MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                    else MaterialTheme.colorScheme.secondaryContainer
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        if (installed != null && progress == null) Icons.Default.Check else Icons.Outlined.Download,
                        contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        when {
                            progress != null -> progress.stage.label()
                            installed != null -> stringResource(R.string.patcher_runtime_available_yes)
                            existing != null -> stringResource(R.string.runtime_catalog_replaces_old)
                            else -> stringResource(R.string.runtime_catalog_available)
                        },
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        if (release == null && installed == null && !viewModel.catalogLoading) {
            viewModel.catalogErrors[item.id]?.takeIf { it.isNotBlank() }?.let { message ->
                Spacer(Modifier.height(8.dp))
                Text(
                    message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun RuntimeTransferProgress(
    progress: RuntimeDownloadProgress?,
    fallbackName: String,
    step: Int,
    stepCount: Int
) {
    val context = LocalContext.current
    val fraction = progress?.fraction
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(progress?.name ?: fallbackName, style = MaterialTheme.typography.titleSmall)
                progress?.let {
                    Text(it.stage.label(), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (step >= 0) Text(
                stringResource(R.string.runtime_progress_step, step + 1, stepCount),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary
            )
        }
        if (fraction == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            val animated by animateFloatAsState(
                targetValue = fraction, animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
                label = "runtimeDownloadProgress"
            )
            LinearProgressIndicator(progress = { animated }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
        }
        if (progress?.stage == RuntimeDownloadStage.DOWNLOADING &&
            (progress.bytesDownloaded > 0 || progress.totalBytes != null)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    progress.totalBytes?.takeIf { it > 0 }?.let { total ->
                        stringResource(R.string.runtime_progress_bytes,
                            Formatter.formatShortFileSize(context, progress.bytesDownloaded),
                            Formatter.formatShortFileSize(context, total))
                    } ?: Formatter.formatShortFileSize(context, progress.bytesDownloaded),
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                fraction?.let {
                    Text(
                        stringResource(R.string.runtime_progress_percent, NumberFormat.getPercentInstance().format(it)),
                        Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End
                    )
                }
            }
        }
    }
}

@Composable
private fun RuntimeCatalogMessage(message: String, error: Boolean) {
    ExpressiveSettingsCard(
        modifier = Modifier.padding(horizontal = 16.dp),
        containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
        contentPadding = PaddingValues(16.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Outlined.WarningAmber, contentDescription = null, modifier = Modifier.size(20.dp),
                tint = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.primary
            )
            Text(
                message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RuntimeDownloadStage.label(): String = stringResource(when (this) {
    RuntimeDownloadStage.PREPARING -> R.string.runtime_progress_preparing
    RuntimeDownloadStage.DOWNLOADING -> R.string.runtime_progress_downloading
    RuntimeDownloadStage.VERIFYING -> R.string.runtime_progress_verifying
    RuntimeDownloadStage.FINISHING -> R.string.runtime_progress_finishing
})

@Composable
private fun formatSize(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)
