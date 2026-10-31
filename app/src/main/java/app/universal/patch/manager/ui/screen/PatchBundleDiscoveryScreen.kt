package app.universal.patch.manager.ui.screen

import app.universal.patch.manager.ui.component.persistentControls
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.rotate
import androidx.compose.material.icons.outlined.FilterAltOff
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.ui.platform.LocalFocusManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.Source
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universal.patch.manager.R
import app.universal.patch.manager.domain.bundles.RemotePatchBundle
import app.universal.patch.manager.data.platform.Filesystem
import app.universal.patch.manager.domain.repository.PatchBundleRepository
import app.universal.patch.manager.network.dto.ExternalBundleSnapshot
import app.universal.patch.manager.ui.component.AppTopBar
import app.universal.patch.manager.ui.component.CheckedFilterChip
import app.universal.patch.manager.ui.component.ConfirmDialog
import app.universal.patch.manager.ui.component.LazyColumnWithScrollbar
import app.universal.patch.manager.ui.component.ShimmerBox
import app.universal.patch.manager.ui.component.bundle.LinkOptionRow
import app.universal.patch.manager.ui.component.patches.PathSelectorDialog
import app.universal.patch.manager.ui.component.RememberedCreateDocument
import app.universal.patch.manager.ui.component.toPickerDirectoryUri
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsCard
import app.universal.patch.manager.network.api.CatalogFilters
import app.universal.patch.manager.ui.component.bundle.CatalogIcon
import app.universal.patch.manager.ui.component.bundle.CatalogAppItem
import app.universal.patch.manager.ui.viewmodel.BundleDiscoveryViewModel
import app.universal.patch.manager.util.isAllowedPatchBundleFile
import app.universal.patch.manager.util.openUrl
import app.universal.patch.manager.util.relativeTime
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.util.toast
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Brands
import compose.icons.fontawesomeicons.brands.Github
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import coil3.compose.AsyncImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PatchBundleDiscoveryScreen(
    onBackClick: () -> Unit,
    onViewPatches: (ExternalBundleSnapshot) -> Unit,
    viewModel: BundleDiscoveryViewModel = koinViewModel(),
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val browsingState by viewModel.browsingState.collectAsStateWithLifecycle()
    val appsTab = browsingState.appsTab
    val filtersExpanded = browsingState.filtersExpanded
    var historyBundle by remember { mutableStateOf<ExternalBundleSnapshot?>(null) }
    var historicalPatchesBundle by remember { mutableStateOf<ExternalBundleSnapshot?>(null) }
    historyBundle?.let { selected ->
        BundleHistorySheet(selected, viewModel, onDismiss = { historyBundle = null },
            onViewPatches = { historicalPatchesBundle = it })
    }
    historicalPatchesBundle?.let { selected ->
        app.universal.patch.manager.ui.component.FullscreenDialog(
            onDismissRequest = { historicalPatchesBundle = null }) {
            PatchBundleDiscoveryPatchesScreen(
                bundleId = selected.bundleId, apiHost = selected.apiHost,
                sourceUrl = selected.sourceUrl, version = selected.version,
                fileHash = selected.fileHash, isPrerelease = selected.isPrerelease,
                patchCount = selected.patchCount, ownerName = selected.ownerName,
                repoName = selected.repoName, bundleType = selected.bundleType,
                downloadUrl = selected.downloadUrl, historicalGeneration = selected.historicalGeneration,
                onBackClick = { historicalPatchesBundle = null }, viewModel = viewModel)
        }
    }
    val prefs: PreferencesManager = koinInject()
    val patchBundleRepository: PatchBundleRepository = koinInject()
    val filesystem: Filesystem = koinInject()
    val storageRoots = remember { filesystem.storageRoots() }
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    val sources by patchBundleRepository.sources.collectAsStateWithLifecycle(emptyList())
    val bundleInfos by patchBundleRepository.allBundlesInfoFlow.collectAsStateWithLifecycle(emptyMap())
    val existingEndpoints = remember(sources) {
        sources.filterIsInstance<RemotePatchBundle>().map { it.endpoint }.toSet()
    }
    val localPatchSources = remember(sources, bundleInfos) {
        sources.mapNotNull { source ->
            val remote = source as? RemotePatchBundle ?: return@mapNotNull null
            val patchCount = bundleInfos[source.uid]?.patches?.size ?: 0
            if (patchCount <= 0) null else remote to patchCount
        }
    }
    val bundles = viewModel.bundles
    val isLoading = viewModel.isLoading
    val errorMessage = viewModel.errorMessage
    val isSearchingMore = viewModel.isSearchingMore
    val bundlesListState = rememberSaveable(browsingState.bundlesFilters, saver = LazyListState.Saver) {
        LazyListState()
    }
    val appsListState = rememberSaveable(browsingState.appsFilters, saver = LazyListState.Saver) {
        LazyListState()
    }
    val listState = if (appsTab) appsListState else bundlesListState
    val useCustomFilePicker by prefs.useCustomFilePicker.getAsState()
    val patchBundleDiscoveryExportDirectory by
        prefs.patchBundleDiscoveryExportLastDirectory.getAsState()
    val pickerScope = rememberCoroutineScope()
    val currentFilters = browsingState.filtersFor(appsTab)
    val defaultFilters = CatalogFilters(release = if (appsTab) "all" else "latest")
    val query = currentFilters.query
    val packageQuery = currentFilters.packageName
    val selectedSource = currentFilters.sourceUrl
    val selectedType = currentFilters.bundleType
    val selectedVersion = currentFilters.version
    val releaseSelection = currentFilters.release
    val toggleRotation by animateFloatAsState(if (filtersExpanded) 180f else 0f,
        animationSpec = tween(280, easing = FastOutSlowInEasing), label = "filter_toggle")
    val showRelease = releaseSelection == "release" || releaseSelection == "all"
    val showPrerelease = releaseSelection == "prerelease" || releaseSelection == "all"
    val latestSelected = releaseSelection == "latest"
    val discoverySortMode = BundleDiscoverySortMode.values().firstOrNull {
        it.storageValue == currentFilters.sort
    } ?: BundleDiscoverySortMode.UPDATED_DESC
    var showSortMenu by remember { mutableStateOf(false) }
    val filterChipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
    )
    val requestedFilters = currentFilters.copy(query = query.trim(), packageName = packageQuery.trim())
    LaunchedEffect(requestedFilters, appsTab) {
        viewModel.configure(requestedFilters, appsTab)
    }
    LaunchedEffect(selectedSource) { viewModel.clearVersions() }
    val groupedBundles by remember(
        bundles,
        query,
        showRelease,
        showPrerelease,
        latestSelected,
        discoverySortMode
    ) {
        derivedStateOf {
            if (bundles == null) return@derivedStateOf null
            val grouped = LinkedHashMap<String, BundleGroup>()
            val order = mutableListOf<String>()
            bundles.forEach { bundle ->
                val sourceKey = bundle.sourceUrl.ifBlank { "${bundle.ownerName}/${bundle.repoName}" }
                val key = if (latestSelected) sourceKey else "$sourceKey|${bundle.bundleId}"
                val entry = grouped.getOrPut(key) {
                    order.add(key)
                    BundleGroup(key = key, release = null, prerelease = null)
                }
                grouped[key] = if (bundle.isPrerelease) {
                    entry.copy(prerelease = entry.prerelease ?: bundle)
                } else {
                    entry.copy(release = entry.release ?: bundle)
                }
            }
            val filtered = order.mapNotNull { grouped[it] }
            when (discoverySortMode) {
                BundleDiscoverySortMode.BUNDLE_NAME_ASC -> {
                    filtered.sortedBy { groupDisplayNameSortKey(it) }
                }
                BundleDiscoverySortMode.BUNDLE_NAME_DESC -> {
                    filtered.sortedByDescending { groupDisplayNameSortKey(it) }
                }
                BundleDiscoverySortMode.UPDATED_ASC -> {
                    filtered.sortedBy { groupUpdatedSortKey(it) }
                }
                BundleDiscoverySortMode.UPDATED_DESC -> {
                    filtered.sortedByDescending { groupUpdatedSortKey(it) }
                }
            }
        }
    }

    val listReady = viewModel.isAppsTab == appsTab && viewModel.filters == requestedFilters &&
        (if (appsTab) viewModel.apps != null else groupedBundles != null)
    LaunchedEffect(listState, currentFilters, appsTab, listReady) {
        if (!listReady) return@LaunchedEffect
        // Wait for this query's results, so loading placeholders cannot erase the saved position.
        val saved = browsingState.scrollFor(appsTab)
        listState.scrollToItem(saved.index, saved.offset)
        snapshotFlow {
            Triple(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, listState.isScrollInProgress)
        }.collect { (index, offset, scrolling) ->
            if (!scrolling) viewModel.saveDiscoveryScroll(appsTab, currentFilters, index, offset)
        }
    }
    DisposableEffect(listState, currentFilters, appsTab, listReady) {
        onDispose {
            if (listReady) viewModel.saveDiscoveryScroll(appsTab, currentFilters,
                listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }
    }

    fun mergeLatest(latest: ExternalBundleSnapshot?, fallback: ExternalBundleSnapshot?): ExternalBundleSnapshot? {
        if (latest == null) return fallback
        if (fallback == null) return latest
        return latest.copy(
            ownerName = latest.ownerName.ifBlank { fallback.ownerName },
            ownerAvatarUrl = latest.ownerAvatarUrl ?: fallback.ownerAvatarUrl,
            iconUrl = latest.iconUrl ?: fallback.iconUrl,
            repoName = latest.repoName.ifBlank { fallback.repoName },
            repoDescription = latest.repoDescription ?: fallback.repoDescription,
            sourceUrl = latest.sourceUrl.ifBlank { fallback.sourceUrl },
            repoStars = if (latest.repoStars == 0) fallback.repoStars else latest.repoStars,
            repoPushedAt = latest.repoPushedAt ?: fallback.repoPushedAt,
            lastRefreshedAt = latest.lastRefreshedAt ?: fallback.lastRefreshedAt,
            bundleType = latest.bundleType.ifBlank { fallback.bundleType },
            createdAt = latest.createdAt.ifBlank { fallback.createdAt },
            description = latest.description ?: fallback.description,
            version = latest.version.ifBlank { fallback.version },
            downloadUrl = latest.downloadUrl ?: fallback.downloadUrl,
            signatureDownloadUrl = latest.signatureDownloadUrl ?: fallback.signatureDownloadUrl,
            patchCount = latest.patchCount,
            patches = latest.patches
        )
    }

    LaunchedEffect(listState, groupedBundles, viewModel.apps, appsTab, viewModel.canLoadMore, viewModel.isLoadingMore) {
        snapshotFlow {
            val total = listState.layoutInfo.totalItemsCount
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible to total
        }.collect { (lastVisible, totalItems) ->
            if (if (appsTab) viewModel.apps.isNullOrEmpty() else groupedBundles.isNullOrEmpty()) return@collect
            if (totalItems > 0 && lastVisible >= totalItems - 3) {
                viewModel.loadMore()
            }
        }
    }
    val variantOverrides = remember { mutableStateMapOf<String, Boolean>() }
    val latestOverrides = remember { mutableStateMapOf<String, Boolean>() }
    val latestSnapshots = remember { mutableStateMapOf<String, ExternalBundleSnapshot?>() }
    fun latestKey(groupKey: String, prerelease: Boolean): String =
        "$groupKey|${if (prerelease) "prerelease" else "release"}"
    var activeBundleMenu by remember { mutableStateOf<BundleMenuState?>(null) }
    var activeExportBundle by remember { mutableStateOf<ExternalBundleSnapshot?>(null) }
    var pendingDocumentExportBundle by remember { mutableStateOf<ExternalBundleSnapshot?>(null) }
    var exportFileDialogState by remember { mutableStateOf<BundleExportFileDialogState?>(null) }
    var pendingExportConfirmation by remember { mutableStateOf<PendingBundleExportConfirmation?>(null) }
    val bundleExportDocumentLauncher = rememberLauncherForActivityResult(
        contract = RememberedCreateDocument("*/*") {
            patchBundleDiscoveryExportDirectory.takeIf(String::isNotBlank)?.let(Uri::parse)
        }
    ) { uri ->
        val bundle = pendingDocumentExportBundle
        pendingDocumentExportBundle = null
        if (bundle != null && uri != null) {
            pickerScope.launch {
                prefs.patchBundleDiscoveryExportLastDirectory.update(
                    uri.toPickerDirectoryUri().toString()
                )
            }
            viewModel.exportBundle(bundle, uri)
        }
    }

    LaunchedEffect(activeExportBundle?.bundleId, useCustomFilePicker) {
        val bundle = activeExportBundle
        if (bundle != null && !useCustomFilePicker) {
            pendingDocumentExportBundle = bundle
            activeExportBundle = null
            exportFileDialogState = null
            pendingExportConfirmation = null
            bundleExportDocumentLauncher.launch(defaultBundleExportName(bundle))
        }
    }

    activeExportBundle?.let { bundle ->
        if (!useCustomFilePicker) return@let
        PathSelectorDialog(
            roots = storageRoots,
            onSelect = { path ->
                if (path == null) activeExportBundle = null
            },
            fileFilter = { isAllowedPatchBundleFile(it) },
            allowDirectorySelection = false,
            fileTypeLabel = bundleExportExtension(bundle),
            confirmButtonText = stringResource(R.string.save),
            onConfirm = { directory ->
                exportFileDialogState = BundleExportFileDialogState(
                    bundle = bundle,
                    directory = directory,
                    fileName = defaultBundleExportName(bundle)
                )
            },
            lastDirectoryPreference = prefs.patchBundleDiscoveryExportLastDirectory
        )
    }

    exportFileDialogState?.let { state ->
        ExportBundleFileNameDialog(
            initialName = state.fileName,
            onDismiss = {
                exportFileDialogState = null
            },
            onConfirm = { fileName ->
                val trimmedName = fileName.trim()
                if (trimmedName.isBlank()) return@ExportBundleFileNameDialog
                exportFileDialogState = null
                val resolvedName = ensureBundleExportExtension(trimmedName, state.bundle)
                val target = state.directory.resolve(resolvedName)
                if (Files.exists(target)) {
                    pendingExportConfirmation = PendingBundleExportConfirmation(
                        bundle = state.bundle,
                        directory = state.directory,
                        fileName = resolvedName
                    )
                } else {
                    viewModel.exportBundle(state.bundle, target)
                    activeExportBundle = null
                }
            }
        )
    }

    pendingExportConfirmation?.let { state ->
        ConfirmDialog(
            onDismiss = {
                pendingExportConfirmation = null
                exportFileDialogState = BundleExportFileDialogState(
                    bundle = state.bundle,
                    directory = state.directory,
                    fileName = state.fileName
                )
            },
            onConfirm = {
                pendingExportConfirmation = null
                viewModel.exportBundle(state.bundle, state.directory.resolve(state.fileName))
                activeExportBundle = null
            },
            title = stringResource(R.string.export_overwrite_title),
            description = stringResource(R.string.export_overwrite_description, state.fileName),
            icon = Icons.Outlined.FileDownload
        )
    }
    activeBundleMenu?.let { menu ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val coroutineScope = rememberCoroutineScope()
        val toggleLabel = stringResource(
            if (menu.showPrerelease) {
                R.string.patch_bundle_discovery_show_release
            } else {
                R.string.patch_bundle_discovery_show_prerelease
            }
        )
        val latestLabel = stringResource(
            if (menu.useLatest) {
                R.string.patch_bundle_discovery_use_listed
            } else {
                R.string.patch_bundle_discovery_use_latest
            }
        )
        ModalBottomSheet(
            onDismissRequest = { activeBundleMenu = null },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = menu.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                LinkOptionRow(
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    text = stringResource(R.string.patch_bundle_copy_remote_url)
                ) {
                    coroutineScope.launch {
                        sheetState.hide()
                        activeBundleMenu = null
                        val url = viewModel.remoteBundleUrl(menu.bundle)
                        if (url.isNullOrBlank()) {
                            context.toast(context.getString(R.string.patch_bundle_discovery_error))
                        } else {
                            clipboard?.setPrimaryClip(
                                ClipData.newPlainText(
                                    context.getString(R.string.patch_bundle_remote_url_label),
                                    url
                                )
                            )
                            context.toast(context.getString(R.string.toast_copied_to_clipboard))
                        }
                    }
                }
                LinkOptionRow(
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.FileDownload,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    text = stringResource(R.string.patch_bundle_export)
                ) {
                    coroutineScope.launch {
                        sheetState.hide()
                        activeBundleMenu = null
                        activeExportBundle = menu.bundle
                    }
                }
                if (menu.bundle.sourceUrl.isNotBlank()) {
                    LinkOptionRow(
                        icon = { Icon(Icons.Outlined.NewReleases, contentDescription = null,
                            modifier = Modifier.size(20.dp)) },
                        text = stringResource(R.string.catalog_release_history)
                    ) {
                        coroutineScope.launch {
                            sheetState.hide()
                            activeBundleMenu = null
                            historyBundle = menu.bundle
                        }
                    }
                }
                if (menu.latestVisible) {
                    LinkOptionRow(
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.NewReleases,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        text = latestLabel,
                        enabled = !menu.latestLocked
                    ) {
                        val newValue = !menu.useLatest
                        latestOverrides[menu.groupKey] = newValue
                        activeBundleMenu = menu.copy(useLatest = newValue)
                        if (newValue) {
                            if (menu.bundle.sourceUrl.isNotBlank() ||
                                (menu.bundle.ownerName.isNotBlank() && menu.bundle.repoName.isNotBlank())) {
                                coroutineScope.launch {
                                    latestSnapshots[latestKey(menu.groupKey, false)] =
                                        viewModel.fetchLatestBundle(menu.bundle, prerelease = false)
                                    latestSnapshots[latestKey(menu.groupKey, true)] =
                                        viewModel.fetchLatestBundle(menu.bundle, prerelease = true)
                                }
                            }
                        }
                    }
                }
                if (menu.toggleVisible) {
                    LinkOptionRow(
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        text = toggleLabel,
                        enabled = menu.toggleEnabled
                    ) {
                        val newValue = !menu.showPrerelease
                        variantOverrides[menu.groupKey] = newValue
                        activeBundleMenu = menu.copy(showPrerelease = newValue)
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.patch_bundle_discovery_title),
                scrollBehavior = scrollBehavior,
                onBackClick = onBackClick,
                actions = {
                    IconButton(onClick = { viewModel.refresh(force = true) }) {
                        Icon(Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.catalog_refresh))
                    }
                    if (!appsTab) {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(
                                imageVector = Icons.Outlined.Sort,
                                modifier = Modifier.size(24.dp),
                                contentDescription = stringResource(R.string.patch_bundle_discovery_sort_title)
                            )
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            BundleDiscoverySortMode.values().forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(mode.labelRes)) },
                                    onClick = {
                                        viewModel.updateDiscoveryFilters(appsTab) { filters -> filters.copy(sort = mode.storageValue) }
                                        showSortMenu = false
                                    },
                                    leadingIcon = {
                                        if (discoverySortMode == mode) {
                                            Icon(
                                                imageVector = Icons.Outlined.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }
                    IconButton(
                        onClick = { context.openUrl(viewModel.discoverySiteUrl()) }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Link,
                            modifier = Modifier.size(24.dp),
                            contentDescription = stringResource(R.string.patch_bundle_discovery_open_site)
                        )
                    }
                    IconButton(
                        onClick = { context.openUrl("https://github.com/Jman-Github/Patch-Bundle-Registry") }
                    ) {
                        Icon(
                            imageVector = FontAwesomeIcons.Brands.Github,
                            modifier = Modifier.size(24.dp),
                            contentDescription = stringResource(R.string.patch_bundle_discovery_open_github)
                        )
                    }
                }
            )
        },
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { paddingValues ->
        val visibleBundles = groupedBundles
        LazyColumnWithScrollbar(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                ExpressiveSettingsCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Public,
                                    contentDescription = null,
                                    modifier = Modifier.padding(8.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.patch_bundle_discovery_title),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = stringResource(R.string.patch_bundle_discovery_description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            persistentControls(key = "discovery-controls") {
                ExpressiveSettingsCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.weight(1f)) {
                                BoxWithConstraints(Modifier.padding(4.dp).selectableGroup()) {
                                    val tabWidth = maxWidth / 2
                                    val indicatorOffset by animateDpAsState(
                                        targetValue = if (appsTab) tabWidth else 0.dp,
                                        animationSpec = tween(280, easing = FastOutSlowInEasing),
                                        label = "discovery_tab_position"
                                    )
                                    // Match the row's measured height, including larger font sizes.
                                    Box(Modifier.matchParentSize()) {
                                        Surface(
                                            modifier = Modifier.offset(x = indicatorOffset)
                                                .width(tabWidth).fillMaxHeight(),
                                            shape = RoundedCornerShape(16.dp),
                                            color = MaterialTheme.colorScheme.secondaryContainer,
                                            content = {}
                                        )
                                    }
                                    Row {
                                        listOf(false to R.string.catalog_bundles, true to R.string.catalog_apps)
                                            .forEach { (isApps, label) ->
                                                val selected = appsTab == isApps
                                                val labelColor by animateColorAsState(
                                                    targetValue = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    animationSpec = tween(200),
                                                    label = "discovery_tab_label"
                                                )
                                                Surface(
                                                    selected = selected,
                                                    onClick = { viewModel.setDiscoveryTab(isApps) },
                                                    shape = RoundedCornerShape(16.dp),
                                                    color = Color.Transparent,
                                                    contentColor = labelColor,
                                                    modifier = Modifier.weight(1f).semantics { role = Role.Tab },
                                                ) {
                                                    Box(Modifier.heightIn(min = 40.dp).padding(horizontal = 8.dp),
                                                        contentAlignment = Alignment.Center) {
                                                        Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
                                                    }
                                                }
                                            }
                                    }
                                }
                            }
                            IconButton(onClick = {
                                viewModel.setDiscoveryFilters(appsTab, defaultFilters)
                                focusManager.clearFocus()
                            }, enabled = currentFilters != defaultFilters) {
                                Icon(Icons.Outlined.FilterAltOff,
                                    contentDescription = stringResource(R.string.catalog_clear_filters))
                            }
                            IconButton(onClick = {
                                viewModel.toggleDiscoveryFiltersExpanded()
                                if (filtersExpanded) focusManager.clearFocus()
                            }) {
                                Icon(Icons.Outlined.ExpandMore, modifier = Modifier.rotate(toggleRotation),
                                    contentDescription = stringResource(if (filtersExpanded) R.string.catalog_hide_filters
                                        else R.string.catalog_show_filters))
                            }
                        }
                        AnimatedVisibility(visible = filtersExpanded,
                            enter = expandVertically(animationSpec = tween(300, easing = FastOutSlowInEasing),
                                expandFrom = Alignment.Top) + fadeIn(tween(220, delayMillis = 60)),
                            exit = shrinkVertically(animationSpec = tween(280, easing = FastOutSlowInEasing),
                                shrinkTowards = Alignment.Top) + fadeOut(tween(140))) {
                            Column(Modifier.padding(top = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CatalogFilterMenu(
                                        label = stringResource(R.string.catalog_source),
                                        selected = selectedSource,
                                        options = viewModel.sourceOptions.map { source ->
                                            source.url.orEmpty() to listOfNotNull(
                                                source.sourceMetadata?.ownerName,
                                                source.sourceMetadata?.repoName
                                            ).joinToString("/").ifBlank { source.url.orEmpty() }
                                        },
                                        onOpen = { viewModel.loadSources() },
                                        loading = viewModel.isSourcesLoading,
                                        error = viewModel.sourcesError,
                                        onRetry = { viewModel.loadSources(force = true) },
                                        onSelect = { viewModel.updateDiscoveryFilters(appsTab) { filters -> filters.copy(sourceUrl = it, version = "") } }
                                    )
                                    CatalogFilterMenu(
                                        label = stringResource(R.string.catalog_bundle_type),
                                        selected = selectedType,
                                        options = listOf("Morphe:V1" to "Morphe", "ReVanced:v21" to "ReVanced v21",
                                            "ReVanced:v22" to "ReVanced v22", "Reseam" to "Reseam"),
                                        onSelect = { viewModel.updateDiscoveryFilters(appsTab) { filters -> filters.copy(bundleType = it) } }
                                    )
                                    CatalogFilterMenu(
                                        label = stringResource(R.string.catalog_bundle_version),
                                        selected = selectedVersion,
                                        options = viewModel.versions.map { it to it },
                                        enabled = selectedSource.isNotBlank(),
                                        onOpen = { viewModel.loadVersions(selectedSource) },
                                        onSelect = { viewModel.updateDiscoveryFilters(appsTab) { filters -> filters.copy(version = it,
                                            release = if (it.isNotBlank()) "all" else filters.release) } }
                                    )
                                }
                                OutlinedTextField(
                                    value = packageQuery,
                                    onValueChange = {
                                        viewModel.updateDiscoveryFilters(appsTab) { filters -> filters.copy(packageName = it, exactPackage = false) }
                                    },
                                    label = { Text(stringResource(R.string.patch_bundle_discovery_package_label)) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.Search,
                                            contentDescription = null
                                        )
                                    },
                                    trailingIcon = {
                                        if (packageQuery.isNotBlank()) {
                                            IconButton(onClick = {
                                                viewModel.updateDiscoveryFilters(appsTab) { filters -> filters.copy(packageName = "", exactPackage = false) }
                                            }) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Close,
                                                    contentDescription = null
                                                )
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = query,
                                    onValueChange = { viewModel.updateDiscoveryFilters(appsTab) { filters -> filters.copy(query = it) } },
                                    label = { Text(stringResource(if (appsTab) R.string.catalog_search_apps
                                        else R.string.patch_bundle_discovery_search_label)) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.Search,
                                            contentDescription = null
                                        )
                                    },
                                    trailingIcon = {
                                        if (query.isNotBlank()) {
                                            IconButton(onClick = { viewModel.updateDiscoveryFilters(appsTab) { filters -> filters.copy(query = "") } }) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Close,
                                                    contentDescription = null
                                                )
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf("all" to R.string.catalog_all,
                                        "release" to R.string.patch_bundle_discovery_release,
                                        "prerelease" to R.string.patch_bundle_discovery_prerelease,
                                        "latest" to R.string.patch_bundle_discovery_latest).forEach { (value, label) ->
                                        CheckedFilterChip(
                                            selected = releaseSelection == value,
                                            onClick = { viewModel.updateDiscoveryFilters(appsTab) { filters -> filters.copy(release = value) } },
                                            colors = filterChipColors,
                                            label = { Text(stringResource(label)) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (!errorMessage.isNullOrBlank()) {
                item(key = "catalog_error") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(errorMessage.orEmpty(), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { viewModel.refresh(force = true) }) {
                            Text(stringResource(R.string.catalog_refresh))
                        }
                    }
                }
            }
            when {
                appsTab -> {
                    val applications = viewModel.apps
                    if (isLoading && applications == null) {
                        items(3) { BundleDiscoveryPlaceholderItem() }
                    } else if (applications.isNullOrEmpty()) {
                        item { Text(stringResource(R.string.catalog_apps_empty)) }
                    } else {
                        items(applications, key = { "app|${it.name}" }) { application ->
                            CatalogAppItem(application) {
                                viewModel.showBundlesForPackage(application.name.orEmpty())
                            }
                        }
                        if (viewModel.isLoadingMore) item { BundleDiscoveryPlaceholderItem() }
                    }
                }
                isLoading && visibleBundles == null -> {
                    items(3) {
                        BundleDiscoveryPlaceholderItem()
                    }
                }

                visibleBundles.isNullOrEmpty() -> {
                    item {
                        if (isSearchingMore && viewModel.canLoadMore && (query.isNotBlank() || packageQuery.isNotBlank())) {
                            ExpressiveSettingsCard(
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
                            ) {
                                val pillShape = RoundedCornerShape(24.dp)
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(36.dp)
                                ) {
                                    ShimmerBox(
                                        modifier = Modifier.fillMaxSize(),
                                        shape = pillShape
                                    )
                                    Row(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = stringResource(R.string.patch_bundle_discovery_searching),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillParentMaxHeight(0.5f),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.patch_bundle_discovery_empty),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }

                else -> {
                    items(visibleBundles, key = { it.key }) { group ->
                        val useLatest = latestSelected || latestOverrides[group.key] == true
                        val latestRelease = if (useLatest) {
                            latestSnapshots[latestKey(group.key, false)]
                        } else {
                            null
                        }
                        val latestPrerelease = if (useLatest) {
                            latestSnapshots[latestKey(group.key, true)]
                        } else {
                            null
                        }
                        val effectiveRelease = if (useLatest) {
                            mergeLatest(latestRelease, group.release)
                        } else {
                            group.release
                        }
                        val effectivePrerelease = if (useLatest) {
                            mergeLatest(latestPrerelease, group.prerelease)
                        } else {
                            group.prerelease
                        }
                        BundleDiscoveryItem(
                            groupKey = group.key,
                            releaseBundle = effectiveRelease,
                            prereleaseBundle = effectivePrerelease,
                            allowRelease = showRelease || latestSelected,
                            allowPrerelease = showPrerelease || latestSelected,
                            showPrereleaseOverride = variantOverrides[group.key],
                            useLatest = useLatest,
                            latestLocked = latestSelected,
                            isImported = { bundle ->
                                viewModel.bundleEndpoints(bundle).any { it in existingEndpoints }
                            },
                            localPatchCount = { bundle ->
                                localPatchSources.firstOrNull { (source, _) ->
                                    viewModel.matchesLocalPatchSource(bundle, source)
                                }?.second
                            },
                            onImport = { bundle ->
                                viewModel.importBundle(
                                    bundle,
                                    autoUpdate = true,
                                    searchUpdate = true,
                                    preferLatestAcrossChannels = useLatest
                                )
                            },
                            onViewPatches = onViewPatches,
                            onMenuRequest = { activeBundleMenu = it },
                            exportProgressFor = viewModel::getExportProgress,
                            importProgressFor = { bundle, isImported ->
                                viewModel.getImportProgress(bundle, isImported)
                            }
                        )
                    }
                    if (viewModel.isLoadingMore) {
                        item(key = "bundle_loading_more") {
                            ExpressiveSettingsCard(
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
                            ) {
                                val pillShape = RoundedCornerShape(24.dp)
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(36.dp)
                                ) {
                                    ShimmerBox(
                                        modifier = Modifier.fillMaxSize(),
                                        shape = pillShape
                                    )
                                    Row(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = stringResource(R.string.patch_bundle_discovery_loading_more),
                                            style = MaterialTheme.typography.bodyMedium,
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
    }
}

@Composable
private fun BundleDiscoveryPlaceholderItem() {
    ExpressiveSettingsCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ShimmerBox(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ShimmerBox(
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .height(16.dp)
                    )
                    ShimmerBox(
                        modifier = Modifier
                            .fillMaxWidth(0.4f)
                            .height(12.dp)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ShimmerBox(modifier = Modifier.size(28.dp))
                    ShimmerBox(modifier = Modifier.size(28.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShimmerBox(modifier = Modifier.size(width = 70.dp, height = 22.dp))
                ShimmerBox(modifier = Modifier.size(width = 90.dp, height = 22.dp))
                ShimmerBox(modifier = Modifier.size(width = 62.dp, height = 22.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BundleDiscoveryItem(
    groupKey: String,
    releaseBundle: ExternalBundleSnapshot?,
    prereleaseBundle: ExternalBundleSnapshot?,
    allowRelease: Boolean,
    allowPrerelease: Boolean,
    showPrereleaseOverride: Boolean?,
    useLatest: Boolean,
    latestLocked: Boolean,
    isImported: (ExternalBundleSnapshot) -> Boolean,
    localPatchCount: (ExternalBundleSnapshot) -> Int?,
    onImport: (ExternalBundleSnapshot) -> Unit,
    onViewPatches: (ExternalBundleSnapshot) -> Unit,
    onMenuRequest: (BundleMenuState) -> Unit,
    exportProgressFor: (Int) -> BundleDiscoveryViewModel.BundleExportProgress?,
    importProgressFor: (
        ExternalBundleSnapshot,
        Boolean
    ) -> PatchBundleRepository.DiscoveryImportProgress?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val hasRelease = releaseBundle != null
    val hasPrerelease = prereleaseBundle != null
    val releaseAllowed = allowRelease && hasRelease
    val prereleaseAllowed = allowPrerelease && hasPrerelease
    val toggleVisible = allowRelease && allowPrerelease
    val toggleEnabled = toggleVisible && hasRelease && hasPrerelease && !latestLocked
    val effectiveShowPrerelease = when {
        releaseAllowed && !prereleaseAllowed -> false
        prereleaseAllowed && !releaseAllowed -> true
        else -> showPrereleaseOverride ?: (!hasRelease && hasPrerelease)
    }
    val bundle = if (useLatest) {
        pickLatestBundle(releaseBundle, prereleaseBundle) ?: return
    } else if (effectiveShowPrerelease && prereleaseBundle != null) {
        prereleaseBundle
    } else {
        releaseBundle ?: prereleaseBundle ?: return
    }
    val displayName = remember(bundle.ownerName, bundle.repoName, bundle.sourceUrl) {
        listOf(bundle.ownerName, bundle.repoName)
            .filter { it.isNotBlank() }
            .joinToString("/")
            .ifBlank { bundle.sourceUrl }
    }
    val description = bundle.repoDescription?.takeIf { it.isNotBlank() }
    val remotePatchCount = if (bundle.patches.isNotEmpty()) bundle.patches.size else bundle.patchCount
    val patchCount = localPatchCount(bundle) ?: remotePatchCount
    val isSupported = !bundle.isBundleV3
    val lastUpdatedLabel = remember(bundle.repoPushedAt) {
        formatRepoUpdatedLabel(context, bundle.repoPushedAt)
    }
    val lastRefreshedLabel = remember(bundle.lastRefreshedAt) {
        formatBundleRefreshedLabel(context, bundle.lastRefreshedAt)
    }
    val releaseImported = releaseBundle?.let(isImported) ?: false
    val prereleaseImported = prereleaseBundle?.let(isImported) ?: false
    val releaseProgress = releaseBundle?.let { importProgressFor(it, releaseImported) }
    val prereleaseProgress = prereleaseBundle?.let { importProgressFor(it, prereleaseImported) }
    val groupProgress = listOfNotNull(releaseProgress, prereleaseProgress)
        .firstOrNull { it.status == PatchBundleRepository.DiscoveryImportStatus.Importing }
        ?: listOfNotNull(releaseProgress, prereleaseProgress)
            .firstOrNull { it.status == PatchBundleRepository.DiscoveryImportStatus.Queued }
    val isImportQueuedForGroup =
        groupProgress?.status == PatchBundleRepository.DiscoveryImportStatus.Queued
    val isImportingForGroup =
        groupProgress?.status == PatchBundleRepository.DiscoveryImportStatus.Importing
    val viewPatchesEnabled = patchCount > 0
    val importEnabled = isSupported && !bundle.downloadUrl.isNullOrBlank() &&
        !isImported(bundle) &&
        !isImportQueuedForGroup &&
        !isImportingForGroup
    val importLabel = when {
        isImported(bundle) -> stringResource(R.string.patch_bundle_discovery_imported)
        isImportQueuedForGroup -> stringResource(R.string.patch_bundle_import_queued_label)
        isImportingForGroup -> stringResource(R.string.patch_bundle_importing)
        else -> stringResource(R.string.import_)
    }
    val exportProgress = exportProgressFor(bundle.bundleId)
    val menuState = BundleMenuState(
        groupKey = groupKey,
        bundle = bundle,
        release = releaseBundle,
        prerelease = prereleaseBundle,
        showPrerelease = effectiveShowPrerelease,
        toggleVisible = toggleVisible,
        toggleEnabled = toggleEnabled,
        latestVisible = bundle.ownerName.isNotBlank() && bundle.repoName.isNotBlank(),
        useLatest = useLatest,
        latestLocked = latestLocked,
        displayName = displayName
    )

    ExpressiveSettingsCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val official = app.universal.patch.manager.domain.bundles.bundleRepositoryUrl(bundle.sourceUrl)
                    .equals(app.universal.patch.manager.domain.bundles.OFFICIAL_PATCH_REPOSITORY, true)
                CatalogIcon(if (official) app.universal.patch.manager.domain.bundles.BundleIconRepository.OFFICIAL_ICON_URL
                    else bundle.iconUrl, bundle.ownerAvatarUrl, displayName, isOfficial = official)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (description != null) {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (bundle.sourceUrl.isNotBlank()) {
                        Text(
                            text = bundle.sourceUrl,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { context.openUrl(bundle.sourceUrl) }
                        )
                    }
                }
                IconButton(onClick = { onMenuRequest(menuState) }) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.more_options)
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (bundle.bundleType.isNotBlank()) {
                    val typeLabel = remember(bundle.bundleType) {
                        formatBundleTypeLabel(bundle.bundleType)
                    }
                    BundleTag(
                        text = typeLabel,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                BundleTag(
                    text = stringResource(
                        if (bundle.isPrerelease) {
                            R.string.patch_bundle_discovery_prerelease
                        } else {
                            R.string.patch_bundle_discovery_release
                        }
                    ),
                    containerColor = if (bundle.isPrerelease) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    contentColor = if (bundle.isPrerelease) {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    }
                )
                if (bundle.version.isNotBlank()) {
                    BundleTag(
                        text = bundle.version,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                BundleTag(
                    text = pluralStringResource(R.plurals.patch_count, patchCount, patchCount),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
                if (!lastUpdatedLabel.isNullOrBlank()) {
                    BundleTag(
                        text = lastUpdatedLabel,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!lastRefreshedLabel.isNullOrBlank()) {
                    BundleTag(
                        text = lastRefreshedLabel,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (bundle.isRepoArchived) {
                    BundleTag(
                        text = stringResource(R.string.patch_bundle_discovery_archived_badge),
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                if (isImported(bundle)) {
                    BundleTag(
                        text = stringResource(R.string.patch_bundle_discovery_imported),
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            app.universal.patch.manager.ui.component.bundle.BundleRuntimeNotice(bundle.bundleType)
            if (bundle.isRepoArchived) {
                Text(
                    text = stringResource(R.string.patch_bundle_discovery_archived_notice),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    enabled = viewPatchesEnabled,
                    onClick = { onViewPatches(bundle) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = stringResource(R.string.patch_bundle_discovery_view_patches),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip
                    )
                }
                FilledTonalButton(
                    enabled = importEnabled,
                    onClick = { onImport(bundle) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = importLabel,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip
                    )
                }
            }
            if (exportProgress != null) {
                val totalBytes = exportProgress.bytesTotal?.takeIf { it > 0L }
                val fraction = exportProgress.bytesTotal?.takeIf { it > 0L }?.let { total ->
                    exportProgress.bytesRead.toFloat() / total.toFloat()
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.patch_bundle_exporting),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val progressLabel = if (totalBytes != null) {
                        val percent = (exportProgress.bytesRead.toDouble() / totalBytes.toDouble()) * 100
                        context.getString(
                            R.string.patch_bundle_export_progress,
                            Formatter.formatShortFileSize(context, exportProgress.bytesRead),
                            Formatter.formatShortFileSize(context, totalBytes),
                            percent
                        )
                    } else {
                        context.getString(
                            R.string.patch_bundle_export_progress_indeterminate,
                            Formatter.formatShortFileSize(context, exportProgress.bytesRead)
                        )
                    }
                    Text(
                        text = progressLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LinearProgressIndicator(
                        progress = { fraction ?: 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            if (groupProgress != null) {
                val totalBytes = groupProgress.bytesTotal?.takeIf { it > 0L }
                val fraction = groupProgress.bytesTotal?.takeIf { it > 0L }?.let { total ->
                    groupProgress.bytesRead.toFloat() / total.toFloat()
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.patch_bundle_importing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val progressLabel = if (isImportQueuedForGroup) {
                        stringResource(R.string.patch_bundle_import_queued_label)
                    } else if (totalBytes != null) {
                        val percent = (groupProgress.bytesRead.toDouble() / totalBytes.toDouble()) * 100
                        context.getString(
                            R.string.patch_bundle_import_progress,
                            Formatter.formatShortFileSize(context, groupProgress.bytesRead),
                            Formatter.formatShortFileSize(context, totalBytes),
                            percent
                        )
                    } else {
                        context.getString(
                            R.string.patch_bundle_import_progress_indeterminate,
                            Formatter.formatShortFileSize(context, groupProgress.bytesRead)
                        )
                    }
                    Text(
                        text = progressLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (fraction == null || isImportQueuedForGroup) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth(),
                            drawStopIndicator = {}
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BundleTag(
    text: String,
    containerColor: Color,
    contentColor: Color
) {
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun ExportBundleFileNameDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by rememberSaveable(initialName) { mutableStateOf(initialName) }
    val trimmedName = value.trim()
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
                    value = value,
                    onValueChange = { value = it },
                    placeholder = { Text(stringResource(R.string.dialog_input_placeholder)) },
                    singleLine = true
                )
            }
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
        }
    )
}

private fun defaultBundleExportName(bundle: ExternalBundleSnapshot): String {
    val base = listOf(bundle.ownerName, bundle.repoName)
        .filter { it.isNotBlank() }
        .joinToString("-")
        .ifBlank { "patch-bundle" }
    val version = bundle.version.takeIf { it.isNotBlank() }?.let { "-$it" }.orEmpty()
    val rawName = sanitizeFileName("$base$version")
    return ensureBundleExportExtension(rawName, bundle)
}

private fun ensureBundleExportExtension(name: String, bundle: ExternalBundleSnapshot): String {
    val trimmed = name.trim()
    val ext = bundleExportExtension(bundle)
    return app.universal.patch.manager.util.withBundleExportExtension(trimmed, ext)
}

private fun bundleExportExtension(bundle: ExternalBundleSnapshot): String {
    return app.universal.patch.manager.util.discoveryBundleExportExtension(
        bundle.bundleType, bundle.downloadUrl
    )
}

private fun sanitizeFileName(value: String): String =
    value.replace(Regex("[\\\\/:*?\"<>|]"), "_")

private fun formatBundleTypeLabel(rawType: String): String {
    val trimmed = rawType.trim()
    if (trimmed.startsWith("Morphe", ignoreCase = true)) {
        return "Morphe"
    }
    if (trimmed.startsWith("Ample", ignoreCase = true)) {
        return "Ample"
    }
    if (trimmed.startsWith("ReVanced", ignoreCase = true)) {
        val version = trimmed.substringAfter(':', "").ifBlank {
            trimmed.substringAfter(' ', "")
        }.trim()
        return if (version.isBlank()) {
            "ReVanced"
        } else {
            "ReVanced $version"
        }
    }
    return trimmed.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

private fun formatRepoUpdatedLabel(context: Context, raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val relative = runCatching {
        Instant.parse(trimmed).toLocalDateTime(TimeZone.UTC).relativeTime(context)
    }.getOrNull() ?: return null
    return context.getString(R.string.bundle_updated_at, relative)
}

private fun formatBundleRefreshedLabel(context: Context, raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val relative = runCatching {
        Instant.parse(trimmed).toLocalDateTime(TimeZone.UTC).relativeTime(context)
    }.getOrNull() ?: return null
    return context.getString(R.string.bundle_refreshed_at, relative)
}

private fun parseBundleInstant(bundle: ExternalBundleSnapshot?): Instant? {
    if (bundle == null) return null
    val candidate = listOf(bundle.createdAt, bundle.repoPushedAt, bundle.lastRefreshedAt)
        .firstOrNull { !it.isNullOrBlank() }
        ?.trim()
        ?: return null
    return runCatching { Instant.parse(candidate) }.getOrNull()
}

private fun pickLatestBundle(
    release: ExternalBundleSnapshot?,
    prerelease: ExternalBundleSnapshot?
): ExternalBundleSnapshot? {
    if (release == null) return prerelease
    if (prerelease == null) return release
    val releaseInstant = parseBundleInstant(release)
    val prereleaseInstant = parseBundleInstant(prerelease)
    if (releaseInstant == null && prereleaseInstant == null) return prerelease
    if (releaseInstant == null) return prerelease
    if (prereleaseInstant == null) return release
    return if (prereleaseInstant > releaseInstant) prerelease else release
}

private fun groupDisplayNameSortKey(group: BundleGroup): String {
    val bundle = group.release ?: group.prerelease
    if (bundle == null) return group.key.lowercase(Locale.ROOT)
    val owner = bundle.ownerName.trim()
    val repo = bundle.repoName.trim()
    return if (owner.isNotBlank() || repo.isNotBlank()) {
        listOf(owner, repo)
            .filter { it.isNotBlank() }
            .joinToString("/")
            .lowercase(Locale.ROOT)
    } else {
        group.key.lowercase(Locale.ROOT)
    }
}

private fun groupUpdatedSortKey(group: BundleGroup): Long {
    val releaseEpoch = parseBundleUpdatedInstant(group.release)?.toEpochMilliseconds() ?: Long.MIN_VALUE
    val prereleaseEpoch = parseBundleUpdatedInstant(group.prerelease)?.toEpochMilliseconds() ?: Long.MIN_VALUE
    return maxOf(releaseEpoch, prereleaseEpoch)
}

private fun parseBundleUpdatedInstant(bundle: ExternalBundleSnapshot?): Instant? {
    if (bundle == null) return null
    val candidate = listOf(bundle.createdAt, bundle.repoPushedAt, bundle.lastRefreshedAt)
        .firstOrNull { !it.isNullOrBlank() }
        ?.trim()
        ?: return null
    return runCatching { Instant.parse(candidate) }.getOrNull()
}

private enum class BundleDiscoverySortMode(
    val storageValue: String,
    val labelRes: Int
) {
    BUNDLE_NAME_ASC("BUNDLE_NAME_ASC", R.string.patch_bundle_discovery_sort_bundle_name_asc),
    BUNDLE_NAME_DESC("BUNDLE_NAME_DESC", R.string.patch_bundle_discovery_sort_bundle_name_desc),
    UPDATED_DESC("UPDATED_DESC", R.string.patch_bundle_discovery_sort_updated_desc),
    UPDATED_ASC("UPDATED_ASC", R.string.patch_bundle_discovery_sort_updated_asc);

    companion object {
        fun fromStorage(value: String?): BundleDiscoverySortMode =
            values().firstOrNull { it.storageValue == value } ?: UPDATED_DESC
    }
}

private data class BundleGroup(
    val key: String,
    val release: ExternalBundleSnapshot?,
    val prerelease: ExternalBundleSnapshot?
)

private data class BundleExportFileDialogState(
    val bundle: ExternalBundleSnapshot,
    val directory: Path,
    val fileName: String
)

private data class PendingBundleExportConfirmation(
    val bundle: ExternalBundleSnapshot,
    val directory: Path,
    val fileName: String
)

private data class BundleMenuState(
    val groupKey: String,
    val bundle: ExternalBundleSnapshot,
    val release: ExternalBundleSnapshot?,
    val prerelease: ExternalBundleSnapshot?,
    val showPrerelease: Boolean,
    val toggleVisible: Boolean,
    val toggleEnabled: Boolean,
    val latestVisible: Boolean,
    val useLatest: Boolean,
    val latestLocked: Boolean,
    val displayName: String
)


















