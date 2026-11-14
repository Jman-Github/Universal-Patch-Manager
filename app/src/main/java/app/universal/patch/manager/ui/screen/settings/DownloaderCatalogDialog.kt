package app.universal.patch.manager.ui.screen.settings

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universal.patch.manager.R
import app.universal.patch.manager.network.downloader.DownloaderCatalogItem
import app.universal.patch.manager.network.downloader.DownloaderPluginSourceState
import app.universal.patch.manager.ui.component.AppScaffold
import app.universal.patch.manager.ui.component.AppTopBar
import app.universal.patch.manager.ui.component.DASHBOARD_SCROLLBAR_THUMB_LENGTH
import app.universal.patch.manager.ui.component.GroupHeader
import app.universal.patch.manager.ui.component.LazyColumnWithScrollbar
import app.universal.patch.manager.ui.component.RuntimePluginIcon
import app.universal.patch.manager.ui.component.downloaderCatalogPreviewIcon
import app.universal.patch.manager.ui.component.SettingsSectionIcons
import app.universal.patch.manager.ui.viewmodel.DownloaderCatalogViewModel
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloaderCatalogDialog(
    onDismiss: () -> Unit,
    onImportUrl: () -> Unit,
    viewModel: DownloaderCatalogViewModel = koinViewModel()
) {
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val selected = viewModel.catalog.filter { it.id in viewModel.selectedIds }
    val pending = selected.filterNot { viewModel.isDownloaded(it, sources) }
    val ready = selected.isNotEmpty() && pending.isEmpty()
    val sizeKnown = pending.all { it.size != null }
    val size = pending.sumOf { it.size ?: 0L }
    val busy = viewModel.currentDownload != null
    var showMenu by remember { mutableStateOf(false) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let(viewModel::importFile)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface {
            BackHandler { onDismiss() }
            AppScaffold(
                topBar = { scrollBehavior ->
                    AppTopBar(
                        title = stringResource(R.string.downloader_catalog_title),
                        onBackClick = onDismiss,
                        scrollBehavior = scrollBehavior,
                        actions = {
                            IconButton(enabled = !busy && !viewModel.loading, onClick = { viewModel.refresh() }) {
                                Icon(Icons.Default.Refresh, stringResource(R.string.runtime_catalog_refresh))
                            }
                            Box {
                                IconButton(enabled = !busy, onClick = { showMenu = true }) {
                                    Icon(Icons.Default.MoreVert, stringResource(R.string.runtime_catalog_more))
                                }
                                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.downloader_catalog_import_url)) },
                                        enabled = !busy,
                                        onClick = {
                                            showMenu = false
                                            onImportUrl()
                                        }
                                    )
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
                                        progress = viewModel.progress,
                                        fallbackName = viewModel.currentDownload.orEmpty(),
                                        step = if (viewModel.stepCount > 0) viewModel.step else -1,
                                        stepCount = viewModel.stepCount,
                                        stageLabel = viewModel.progress?.stage?.let { downloaderStageLabel(it) }
                                    )
                                } else {
                                    CatalogSelectionSummary(
                                        count = selected.size,
                                        summary = when {
                                            ready -> stringResource(R.string.runtime_catalog_ready)
                                            sizeKnown && size > 0 -> stringResource(R.string.runtime_catalog_download_size, formatSize(size))
                                            selected.isNotEmpty() -> stringResource(R.string.downloader_catalog_pending)
                                            else -> stringResource(R.string.runtime_catalog_select_hint)
                                        }
                                    )
                                }
                                Button(
                                    enabled = selected.isNotEmpty() && !busy && (ready || !viewModel.loading),
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                    onClick = { if (ready) onDismiss() else viewModel.downloadSelected() }
                                ) {
                                    if (!busy) {
                                        Icon(if (ready) Icons.Default.Check else Icons.Outlined.Download, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(stringResource(when {
                                        busy -> R.string.runtime_catalog_in_progress
                                        ready -> R.string.done
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
                            title = stringResource(R.string.downloader_catalog_choose),
                            description = stringResource(R.string.downloader_catalog_description)
                        )
                    }
                    if (viewModel.loading) item {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp))
                    }
                    viewModel.warning?.let { warning ->
                        item { CatalogMessage(warning, error = true) }
                    }
                    viewModel.importError?.let { error ->
                        item { CatalogMessage(error, error = true) }
                    }
                    item { GroupHeader(stringResource(R.string.downloader_catalog_available), SettingsSectionIcons.DownloaderPlugins) }
                    if (!viewModel.loading && viewModel.warning == null && viewModel.catalog.isEmpty()) item {
                        CatalogMessage(stringResource(R.string.downloader_catalog_empty), error = false)
                    }
                    items(viewModel.catalog, key = { it.id }) { item ->
                        DownloaderChoiceCard(
                            item = item,
                            source = viewModel.sourceFor(item, sources),
                            selected = item.id in viewModel.selectedIds,
                            busy = busy,
                            viewModel = viewModel
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloaderChoiceCard(
    item: DownloaderCatalogItem,
    source: DownloaderPluginSourceState?,
    selected: Boolean,
    busy: Boolean,
    viewModel: DownloaderCatalogViewModel
) {
    val progress = viewModel.progress?.takeIf { it.catalogId == item.id }
    val imported = source != null &&
        source.state !is DownloaderPluginSourceState.State.Missing &&
        source.state !is DownloaderPluginSourceState.State.Failed
    val apk by produceState<java.io.File?>(null, source?.entry, source?.version, source?.state) {
        value = viewModel.apkFor(source)
    }
    CatalogChoiceCard(
        name = item.name,
        description = item.repoUrl.removePrefix("https://github.com/"),
        version = if (imported) stringResource(R.string.downloader_catalog_imported_version, source?.version ?: item.version)
            else item.size?.let { stringResource(R.string.downloader_catalog_version_size, item.version, formatSize(it)) }
                ?: item.version,
        status = when {
            progress != null -> downloaderStageLabel(progress.stage)
            imported -> stringResource(R.string.downloader_catalog_imported)
            else -> stringResource(R.string.runtime_catalog_available)
        },
        complete = imported && progress == null,
        selected = selected,
        busy = busy,
        onCheckedChange = { viewModel.select(item.id, it) },
        icon = {
            RuntimePluginIcon(
                apk = apk, pm = viewModel.pm, previewUrl = null, modifier = Modifier.size(44.dp),
                cacheKey = source?.let { "${it.entry.id}:${it.entry.versionKey}:${it.version}" },
                previewResource = downloaderCatalogPreviewIcon(item.repoUrl, item.assetSelector)
            )
        }
    )
}

@Composable
private fun downloaderStageLabel(stage: app.universal.patch.manager.network.runtime.RuntimeDownloadStage): String =
    if (stage == app.universal.patch.manager.network.runtime.RuntimeDownloadStage.VERIFYING) {
        stringResource(R.string.downloader_catalog_verifying)
    } else {
        stage.catalogLabel()
    }
