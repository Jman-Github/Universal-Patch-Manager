package app.universal.patch.manager.ui.screen.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universal.patch.manager.R
import app.universal.patch.manager.network.runtime.LoadedPatcherRuntimePlugin
import app.universal.patch.manager.network.runtime.RuntimeCatalogItem
import app.universal.patch.manager.network.runtime.RuntimeDownloadProgress
import app.universal.patch.manager.network.runtime.canCompleteRuntimeSetup
import app.universal.patch.manager.ui.component.AppScaffold
import app.universal.patch.manager.ui.component.AppTopBar
import app.universal.patch.manager.ui.component.DASHBOARD_SCROLLBAR_THUMB_LENGTH
import app.universal.patch.manager.ui.component.GroupHeader
import app.universal.patch.manager.ui.component.LazyColumnWithScrollbar
import app.universal.patch.manager.ui.component.RuntimePluginIcon
import app.universal.patch.manager.ui.component.SettingsSectionIcons
import app.universal.patch.manager.ui.viewmodel.PatcherRuntimePluginsViewModel
import org.koin.androidx.compose.koinViewModel

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
                            CatalogTransferProgress(
                                progress = progress,
                                fallbackName = viewModel.currentDownload.orEmpty(),
                                step = step,
                                stepCount = required.size
                            )
                        } else {
                            CatalogSelectionSummary(
                                count = selected.size,
                                summary = if (ready) stringResource(R.string.runtime_catalog_ready)
                                    else if (sizeKnown && size > 0) stringResource(R.string.runtime_catalog_download_size, formatSize(size))
                                    else if (selected.isNotEmpty()) stringResource(R.string.runtime_catalog_pending)
                                    else stringResource(R.string.runtime_catalog_select_hint)
                            )
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
                CatalogIntroduction(
                    title = stringResource(R.string.runtime_catalog_choose),
                    description = stringResource(if (onboarding) R.string.runtime_setup_description else R.string.runtime_catalog_description)
                )
            }
            if (viewModel.catalogLoading) item {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp))
            }
            viewModel.catalogWarning?.let { warning ->
                item { CatalogMessage(stringResource(R.string.runtime_catalog_cached, warning), error = true) }
            }
            viewModel.downloadError?.let { error ->
                item { CatalogMessage(error, error = true) }
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
    val onCheckedChange: (Boolean) -> Unit = { viewModel.selectCatalogRuntime(item.id, it) }
    CatalogChoiceCard(
        name = item.name,
        description = stringResource(when (item.runtimeKind) {
            app.universal.patch.manager.network.runtime.PatcherRuntimeKind.REVANCED_V21 -> R.string.runtime_description_revanced_v21
            app.universal.patch.manager.network.runtime.PatcherRuntimeKind.REVANCED_V22 -> R.string.runtime_description_revanced_v22
            app.universal.patch.manager.network.runtime.PatcherRuntimeKind.MORPHE -> R.string.runtime_description_morphe
            app.universal.patch.manager.network.runtime.PatcherRuntimeKind.RESEAM -> R.string.runtime_description_reseam
            app.universal.patch.manager.network.runtime.PatcherRuntimeKind.SHARED_SUPPORT -> R.string.runtime_description_support
        }),
        version = when {
            installed != null -> stringResource(R.string.runtime_catalog_installed, installed.version)
            release != null -> stringResource(
                R.string.runtime_catalog_version, release.manifest.version, release.manifest.upstreamVersion,
                formatSize(release.manifest.size)
            )
            viewModel.catalogLoading -> stringResource(R.string.runtime_catalog_checking)
            else -> stringResource(R.string.runtime_catalog_unavailable)
        },
        status = if (progress != null || installed != null || existing != null || release != null) {
            when {
                progress != null -> progress.stage.catalogLabel()
                installed != null -> stringResource(R.string.patcher_runtime_available_yes)
                existing != null -> stringResource(R.string.runtime_catalog_replaces_old)
                else -> stringResource(R.string.runtime_catalog_available)
            }
        } else null,
        complete = installed != null && progress == null,
        selected = selected,
        busy = busy,
        onCheckedChange = onCheckedChange,
        icon = {
            RuntimePluginIcon(
                apk = installed?.apkFile ?: existing?.apkFile, pm = viewModel.pm,
                previewUrl = release?.iconUrl, modifier = Modifier.size(44.dp)
            )
        }
    ) {
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
