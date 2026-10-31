package app.universal.patch.manager.ui.screen

import app.universal.patch.manager.ui.component.persistentControls
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universal.patch.manager.R
import app.universal.patch.manager.data.platform.Filesystem
import app.universal.patch.manager.ui.component.AppIcon
import app.universal.patch.manager.ui.component.AppLabel
import app.universal.patch.manager.ui.component.AppTopBar
import app.universal.patch.manager.ui.component.AppVersion
import app.universal.patch.manager.ui.component.suggestedVersionLabel
import app.universal.patch.manager.ui.component.CheckedFilterChip
import app.universal.patch.manager.ui.component.ExperimentalVersionBadge
import app.universal.patch.manager.ui.component.ExpandableText
import app.universal.patch.manager.ui.component.InterceptBackHandler
import app.universal.patch.manager.ui.component.LazyColumnWithScrollbar
import app.universal.patch.manager.ui.component.haptics.HapticExtendedFloatingActionButton
import app.universal.patch.manager.ui.component.ShimmerBox
import app.universal.patch.manager.ui.component.NonSuggestedVersionDialog
import app.universal.patch.manager.ui.component.TransparentLoadingDialog
import app.universal.patch.manager.ui.component.RememberedGetContent
import app.universal.patch.manager.ui.component.toPickerDirectoryUri
import app.universal.patch.manager.ui.component.UniversalFallbackVersionDialog
import app.universal.patch.manager.ui.component.patches.PathSelectorDialog
import app.universal.patch.manager.ui.component.SafeguardHintCard
import app.universal.patch.manager.ui.component.SearchView
import app.universal.patch.manager.ui.model.SelectedApp
import app.universal.patch.manager.ui.model.SupportedVersionInfo
import app.universal.patch.manager.ui.viewmodel.AppSelectorViewModel
import app.universal.patch.manager.ui.viewmodel.BundleVersionSuggestion
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.util.EventEffect
import app.universal.patch.manager.util.AppInfo
import app.universal.patch.manager.util.isAllowedApkFile
import app.universal.patch.manager.util.consumeHorizontalScroll
import app.universal.patch.manager.util.openUrl
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import app.universal.patch.manager.ui.component.CenteredDialogTitle


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSelectorScreen(
    onSelect: (String) -> Unit,
    onStorageSelect: (SelectedApp.Local) -> Unit,
    onBackClick: () -> Unit,
    autoOpenStorage: Boolean = false,
    returnToDashboardOnStorage: Boolean = false,
    batchQueueMode: Boolean = false,
    batchQueueSize: Int = 0,
    onReviewQueue: () -> Unit = {},
    vm: AppSelectorViewModel = koinViewModel()
) {
    val prefs = koinInject<PreferencesManager>()
    val appNames = koinInject<app.universal.patch.manager.domain.repository.AppNameRepository>()
    val cachedAppNames by appNames.names.collectAsStateWithLifecycle()
    val fs = koinInject<Filesystem>()
    val storageRoots = remember { fs.storageRoots() }
    val allowIncompatiblePatches by prefs.disablePatchVersionCompatCheck.getAsState()
    val suggestedVersionSafeguard by prefs.suggestedVersionSafeguard.getAsState()
    val bundleRecommendationsEnabled = allowIncompatiblePatches && !suggestedVersionSafeguard
    val allowUniversalPatches by prefs.disableUniversalPatchCheck.getAsState()
    val useCustomFilePicker by prefs.useCustomFilePicker.getAsState()
    val searchEngineHost by prefs.searchEngineHost.getAsState()
    val filterInstalledOnly by prefs.appSelectorFilterInstalledOnly.getAsState()
    val filterPatchesAvailable by prefs.appSelectorFilterPatchesAvailable.getAsState()
    val sortModePreference = if (batchQueueMode) {
        prefs.batchQueueAppSelectorSortMode
    } else {
        prefs.appSelectorSortMode
    }
    val persistedSortMode by sortModePreference.getAsState()
    val sortMode = remember(persistedSortMode) {
        AppSelectorSortMode.fromStorage(persistedSortMode)
    }
    val apkInputDirectory by prefs.apkInputLastDirectory.getAsState()
    val coroutineScope = rememberCoroutineScope()

    EventEffect(flow = vm.storageSelectionFlow) {
        vm.consumeStorageSelectionResult()
        onStorageSelect(it)
        if (returnToDashboardOnStorage) {
            onBackClick()
        }
    }

    var showStorageDialog by rememberSaveable { mutableStateOf(false) }
    val (permissionContract, permissionName) = remember { fs.permissionContract() }
    val permissionLauncher =
        rememberLauncherForActivityResult(permissionContract) { granted ->
            if (granted) {
                showStorageDialog = true
            } else if (returnToDashboardOnStorage) {
                onBackClick()
            }
        }
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = RememberedGetContent {
            apkInputDirectory.takeIf(String::isNotBlank)?.let(Uri::parse)
        }
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                prefs.apkInputLastDirectory.update(uri.toPickerDirectoryUri().toString())
                vm.handleStorageResult(uri)
            }
        } else if (returnToDashboardOnStorage) {
            onBackClick()
        }
    }
    val openStoragePicker = {
        if (useCustomFilePicker) {
            if (fs.hasStoragePermission()) {
                showStorageDialog = true
            } else {
                permissionLauncher.launch(permissionName)
            }
        } else {
            openDocumentLauncher.launch("application/*")
        }
    }
    LaunchedEffect(useCustomFilePicker) {
        if (!useCustomFilePicker) {
            showStorageDialog = false
        }
    }
    LaunchedEffect(autoOpenStorage) {
        if (autoOpenStorage) {
            openStoragePicker()
        }
    }

    if (showStorageDialog && useCustomFilePicker) {
        PathSelectorDialog(
            roots = storageRoots,
            onSelect = { path ->
                showStorageDialog = false
                path?.let { vm.handleStorageFile(File(it.toString())) }
                if (path == null && returnToDashboardOnStorage) {
                    onBackClick()
                }
            },
            fileFilter = ::isAllowedApkFile,
            allowDirectorySelection = false,
            lastDirectoryPreference = prefs.apkInputLastDirectory
        )
    }

    val suggestedVersions by vm.suggestedAppVersions.collectAsStateWithLifecycle(emptyMap())
    val bundleSuggestionsByApp by vm.bundleSuggestionsByApp.collectAsStateWithLifecycle(emptyMap())

    var filterText by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf(false) }
    var showSortMenu by rememberSaveable { mutableStateOf(false) }

    InterceptBackHandler(
        enabled = search || filterText.isNotBlank() || batchQueueMode
    ) {
        when {
            search -> search = false
            filterText.isNotBlank() -> filterText = ""
            batchQueueMode -> onBackClick()
        }
    }

    val appList by vm.appList.collectAsStateWithLifecycle(initialValue = emptyList())
    val notInstalledLabel = stringResource(R.string.not_installed)
    // Keep names for this list even after the bounded repository cache evicts them.
    // Replacing the list or leaving the screen releases this screen-owned data.
    var resolvedDisplayLabels by remember(appList) { mutableStateOf<Map<String, String>>(emptyMap()) }
    val appDisplayLabels = remember(resolvedDisplayLabels, cachedAppNames) {
        cachedAppNames + resolvedDisplayLabels.filter { (pkg, label) -> label != pkg }
    }
    LaunchedEffect(appList) {
        // Start remote lookups as each local batch is ready, rather than waiting
        // for PackageManager to read every installed app in a large list first.
        appList.chunked(32).forEach { batch ->
            val localLabels = withContext(Dispatchers.IO) {
                batch.associate { app ->
                    app.packageName to (app.packageInfo?.let(vm::loadLabel) ?: app.packageName)
                }
            }
            resolvedDisplayLabels = resolvedDisplayLabels + localLabels
            batch.filter { localLabels[it.packageName] == it.packageName }.forEach { app ->
                launch {
                    val name = appNames.resolve(app.packageName)
                    resolvedDisplayLabels = resolvedDisplayLabels + (app.packageName to name)
                }
            }
        }
    }
    val filteredAppList = remember(
        appList,
        appDisplayLabels,
        notInstalledLabel,
        filterText,
        filterInstalledOnly,
        filterPatchesAvailable,
        allowUniversalPatches,
        sortMode,
    ) {
        val sortKeys = appList.associate { app ->
            app.packageName to
                appDisplayLabels.getOrElse(app.packageName) { app.packageName }
                    .trim()
                    .lowercase(Locale.ROOT)
        }
        val comparator = compareBy<AppInfo>(
            { app -> sortKeys.getValue(app.packageName) },
            { app -> app.packageName.lowercase(Locale.ROOT) },
            AppInfo::packageName,
        )
        appList
            .asSequence()
            .filter { app ->
                if (filterInstalledOnly && app.packageInfo == null) return@filter false
                if (filterPatchesAvailable && (app.patches ?: 0) <= 0) return@filter false
                if (!allowUniversalPatches && (app.patches ?: 0) <= 0) return@filter false
                true
            }
            .filter { app ->
                if (filterText.isBlank()) return@filter true
                val searchableLabel = appDisplayLabels.getOrElse(app.packageName) { app.packageName }
                searchableLabel.contains(filterText, true) ||
                    (app.packageInfo == null && notInstalledLabel.contains(filterText, true)) ||
                    app.packageName.contains(filterText, true)
            }
            .sortedWith(
                when (sortMode) {
                    AppSelectorSortMode.NAME_ASC -> comparator
                    AppSelectorSortMode.NAME_DESC -> comparator.reversed()
                }
            )
            .toList()
    }

    vm.universalFallbackDialogSubject?.let {
        UniversalFallbackVersionDialog(
            onContinue = vm::continueWithUniversalFallback,
            onDismiss = vm::dismissUniversalFallbackDialog
        )
    }

    vm.nonSuggestedVersionDialogSubject?.let { local ->
        NonSuggestedVersionDialog(
            suggestedVersion = vm.nonSuggestedVersionDialogSuggestedVersion
                ?.takeUnless { it.isBlank() }
                ?: suggestedVersions[local.packageName].orEmpty().ifBlank { local.version },
            suggestedVersionCodes = vm.nonSuggestedVersionDialogSuggestedVersionCodes,
            requiresUniversalPatchesEnabled = vm.nonSuggestedVersionDialogRequiresUniversalEnabled,
            onDismiss = vm::dismissNonSuggestedVersionDialog
        )
    }

    if (search)
        SearchView(
            query = filterText,
            onQueryChange = { filterText = it },
            onActiveChange = { search = it },
            placeholder = { Text(stringResource(R.string.search_apps)) }
        ) {
            if (filteredAppList.isNotEmpty() && filterText.isNotEmpty()) {
                LazyColumnWithScrollbar(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        items = filteredAppList,
                        key = { it.packageName }
                    ) { app ->
                        AppSelectorCard(
                            packageInfo = app.packageInfo,
                            packageName = app.packageName,
                            patchCount = app.patches,
                            onClick = { onSelect(app.packageName) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            SuggestedVersionsDropdown(
                                packageInfo = app.packageInfo,
                                packageName = app.packageName,
                                bundleSuggestions = bundleSuggestionsByApp[app.packageName].orEmpty(),
                                bundleRecommendationsEnabled = bundleRecommendationsEnabled,
                                searchEngineHost = searchEngineHost
                            )
                        }
                    }
                }
            } else if (appList.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    repeat(4) {
                        AppSelectorCardPlaceholder()
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = stringResource(R.string.search),
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = stringResource(R.string.type_anything),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(
                    if (batchQueueMode) R.string.batch_queue_select_apps else R.string.select_app
                ),
                scrollBehavior = scrollBehavior,
                onBackClick = {
                    when {
                        search -> search = false
                        filterText.isNotBlank() -> filterText = ""
                        else -> onBackClick()
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(
                                Icons.AutoMirrored.Outlined.Sort,
                                contentDescription = stringResource(R.string.app_selector_sort_title)
                            )
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            AppSelectorSortMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(mode.labelRes)) },
                                    leadingIcon = {
                                        if (sortMode == mode) {
                                            Icon(
                                                Icons.Filled.Check,
                                                contentDescription = null
                                            )
                                        }
                                    },
                                    onClick = {
                                        showSortMenu = false
                                        coroutineScope.launch {
                                            sortModePreference.update(mode.storageValue)
                                        }
                                    }
                                )
                            }
                        }
                    }
                    IconButton(onClick = { search = true }) {
                        Icon(Icons.Outlined.Search, stringResource(R.string.search))
                    }
                }
            )
        },
        floatingActionButton = {
            if (batchQueueMode && batchQueueSize > 0) {
                HapticExtendedFloatingActionButton(
                    text = {
                        Text(
                            stringResource(
                                R.string.batch_queue_review_count,
                                batchQueueSize
                            )
                        )
                    },
                    icon = {
                        Icon(
                            Icons.AutoMirrored.Outlined.PlaylistPlay,
                            stringResource(R.string.batch_queue_review)
                        )
                    },
                    onClick = onReviewQueue,
                    enabled = batchQueueSize >= 2
                )
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { paddingValues ->
        LazyColumnWithScrollbar(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 12.dp,
                end = 16.dp,
                bottom = if (batchQueueMode && batchQueueSize > 0) 88.dp else 12.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "app-selector-storage") {
                SelectFromStorageCard(onClick = openStoragePicker)
            }
            persistentControls(key = "app-selector-filters") {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val appFilterChipColors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp),
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                        CheckedFilterChip(
                            selected = filterInstalledOnly,
                            onClick = {
                                coroutineScope.launch {
                                    prefs.appSelectorFilterInstalledOnly.update(!filterInstalledOnly)
                                }
                            },
                            colors = appFilterChipColors,
                            label = { Text(stringResource(R.string.app_filter_installed_only)) }
                        )
                        CheckedFilterChip(
                            selected = filterPatchesAvailable,
                            onClick = {
                                coroutineScope.launch {
                                    prefs.appSelectorFilterPatchesAvailable.update(!filterPatchesAvailable)
                                }
                            },
                            colors = appFilterChipColors,
                            label = { Text(stringResource(R.string.app_filter_patches_available)) }
                        )
                    }
                }
            }

            if (appList.isNotEmpty()) {
                items(
                    items = filteredAppList,
                    key = { it.packageName }
                ) { app ->
                    AppSelectorCard(
                        packageInfo = app.packageInfo,
                        packageName = app.packageName,
                        patchCount = app.patches,
                        onClick = { onSelect(app.packageName) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val bundleSuggestions = bundleSuggestionsByApp[app.packageName].orEmpty()
                        var expanded by rememberSaveable(app.packageName) { mutableStateOf(false) }
                        var dialogBundleUid by remember { mutableStateOf<Int?>(null) }

                        LaunchedEffect(bundleRecommendationsEnabled) {
                            if (!bundleRecommendationsEnabled) {
                                expanded = false
                                dialogBundleUid = null
                            }
                        }

                        if (bundleSuggestions.isNotEmpty()) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                val toggleLabel = stringResource(
                                    if (expanded) R.string.hide_suggested_versions
                                    else R.string.show_suggested_versions
                                )
                                TextButton(
                                    onClick = { expanded = !expanded },
                                    modifier = Modifier.align(Alignment.Start),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                ) {
                                    Icon(
                                        imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ChevronRight,
                                        contentDescription = null
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = toggleLabel,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (bundleRecommendationsEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                if (expanded) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(12.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        if (!bundleRecommendationsEnabled) {
                                            SafeguardHintCard(
                                                title = stringResource(R.string.bundle_version_dialog_locked_title),
                                                description = stringResource(R.string.bundle_version_dialog_locked_hint),
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }

                                        bundleSuggestions.forEach { suggestion ->
                                            BundleSuggestionCard(
                                                suggestion = suggestion,
                                                packageInfo = app.packageInfo,
                                                packageName = app.packageName,
                                                searchEngineHost = searchEngineHost,
                                                enabled = bundleRecommendationsEnabled,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .widthIn(max = 560.dp)
                                                    .alpha(if (bundleRecommendationsEnabled) 1f else 0.6f),
                                                onShowOtherVersions = {
                                                    if (bundleRecommendationsEnabled) {
                                                        dialogBundleUid = suggestion.bundleUid
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                            if (dialogBundleUid != null && bundleRecommendationsEnabled) {
                                bundleSuggestions
                                    .firstOrNull { it.bundleUid == dialogBundleUid }
                                    ?.let { suggestion ->
                                        OtherSupportedVersionsInfoDialog(
                                            bundleName = suggestion.bundleName,
                                            packageInfo = app.packageInfo,
                                            packageName = app.packageName,
                                            recommendedVersion = suggestion.recommendedVersion,
                                            recommendedVersionCodes = suggestion.recommendedVersionCodes,
                                            recommendedVersionExperimental = suggestion.recommendedVersionExperimental,
                                            otherVersions = suggestion.otherSupportedVersions,
                                            supportsAllVersions = suggestion.supportsAllVersions,
                                            searchEngineHost = searchEngineHost,
                                            onDismissRequest = { dialogBundleUid = null }
                                        )
                                    }
                            } else if (dialogBundleUid != null) {
                                dialogBundleUid = null
                            }
                        }
                    }
                }
                if (filteredAppList.isEmpty()) {
                    item(key = "app-selector-empty") {
                        Column(
                            modifier = Modifier
                                .fillParentMaxSize()
                                .padding(32.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Search,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = stringResource(R.string.app_filter_no_results),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                item {
                    Column(
                        modifier = Modifier
                            .fillParentMaxSize()
                            .padding(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        repeat(4) {
                            AppSelectorCardPlaceholder()
                        }
                    }
                }
            }
        }
    }

    if (vm.storageSelectionInProgress) {
        TransparentLoadingDialog()
    }
}

private enum class AppSelectorSortMode(
    val storageValue: String,
    val labelRes: Int,
) {
    NAME_ASC("NAME_ASC", R.string.app_selector_sort_name_asc),
    NAME_DESC("NAME_DESC", R.string.app_selector_sort_name_desc);

    companion object {
        fun fromStorage(value: String?): AppSelectorSortMode =
            entries.firstOrNull { it.storageValue == value } ?: NAME_ASC
    }
}

@Composable
private fun AppSelectorCardPlaceholder(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ShimmerBox(
                    modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(12.dp)
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ShimmerBox(modifier = Modifier.fillMaxWidth(0.6f).height(16.dp))
                    ShimmerBox(modifier = Modifier.fillMaxWidth(0.4f).height(12.dp))
                }
                ShimmerBox(modifier = Modifier.size(width = 36.dp, height = 20.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShimmerBox(modifier = Modifier.size(width = 72.dp, height = 22.dp))
                ShimmerBox(modifier = Modifier.size(width = 96.dp, height = 22.dp))
            }
        }
    }
}

@Composable
private fun SelectFromStorageCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                contentColor = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Storage,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.select_from_storage),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(R.string.select_from_storage_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AppSelectorCard(
    packageInfo: PackageInfo?,
    packageName: String,
    patchCount: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: (@Composable () -> Unit)? = null
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIcon(
                    packageInfo = packageInfo,
                    packageName = packageName,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp)
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    AppLabel(
                        packageInfo = packageInfo,
                        defaultText = packageName,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    AppVersion(packageInfo)
                }
                patchCount?.takeIf { it > 0 }?.let { count ->
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text(
                            text = pluralStringResource(R.plurals.patch_count, count, count),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            content?.invoke()
        }
    }
}

@Composable
private fun SuggestedVersionsDropdown(
    packageInfo: PackageInfo?,
    packageName: String,
    bundleSuggestions: List<BundleVersionSuggestion>,
    bundleRecommendationsEnabled: Boolean,
    searchEngineHost: String,
    modifier: Modifier = Modifier
) {
    if (bundleSuggestions.isEmpty()) return

    var expanded by rememberSaveable(packageName) { mutableStateOf(false) }
    var dialogBundleUid by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(bundleRecommendationsEnabled) {
        if (!bundleRecommendationsEnabled) {
            expanded = false
            dialogBundleUid = null
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val toggleLabel = stringResource(
            if (expanded) R.string.hide_suggested_versions
            else R.string.show_suggested_versions
        )
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.align(Alignment.Start),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ChevronRight,
                contentDescription = null
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = toggleLabel,
                style = MaterialTheme.typography.labelLarge,
                color = if (bundleRecommendationsEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (!bundleRecommendationsEnabled) {
                    SafeguardHintCard(
                        title = stringResource(R.string.bundle_version_dialog_locked_title),
                        description = stringResource(R.string.bundle_version_dialog_locked_hint),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                bundleSuggestions.forEach { suggestion ->
                    BundleSuggestionCard(
                        suggestion = suggestion,
                        packageInfo = packageInfo,
                        packageName = packageName,
                        searchEngineHost = searchEngineHost,
                        enabled = bundleRecommendationsEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 560.dp)
                            .alpha(if (bundleRecommendationsEnabled) 1f else 0.6f),
                        onShowOtherVersions = {
                            if (bundleRecommendationsEnabled) {
                                dialogBundleUid = suggestion.bundleUid
                            }
                        }
                    )
                }
            }
        }
    }

    if (dialogBundleUid != null && bundleRecommendationsEnabled) {
        bundleSuggestions
            .firstOrNull { it.bundleUid == dialogBundleUid }
            ?.let { suggestion ->
                OtherSupportedVersionsInfoDialog(
                    bundleName = suggestion.bundleName,
                    packageInfo = packageInfo,
                    packageName = packageName,
                    recommendedVersion = suggestion.recommendedVersion,
                    recommendedVersionCodes = suggestion.recommendedVersionCodes,
                    recommendedVersionExperimental = suggestion.recommendedVersionExperimental,
                    otherVersions = suggestion.otherSupportedVersions,
                    supportsAllVersions = suggestion.supportsAllVersions,
                    searchEngineHost = searchEngineHost,
                    onDismissRequest = { dialogBundleUid = null }
                )
            }
    } else if (dialogBundleUid != null) {
        dialogBundleUid = null
    }
}

@Composable
private fun VersionSearchRow(
    label: String,
    packageName: String,
    version: String?,
    versionCodes: Set<Long> = emptySet(),
    searchEngineHost: String,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    experimental: Boolean = false
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        VersionSearchChip(
            label = label,
            packageName = packageName,
            version = version,
            versionCodes = versionCodes,
            searchEngineHost = searchEngineHost,
            highlighted = highlighted,
            experimental = experimental
        )
    }
}

@Composable
private fun VersionSearchChip(
    label: String,
    packageName: String,
    version: String?,
    versionCodes: Set<Long> = emptySet(),
    searchEngineHost: String,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    experimental: Boolean = false
) {
    val context = LocalContext.current
    val background = if (highlighted) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
    }
    val contentColor = if (highlighted) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val chipModifier = if (highlighted) {
        modifier.widthIn(max = if (experimental) 240.dp else 220.dp)
    } else {
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
    }
    Surface(
        onClick = {
            context.openUrl(buildSearchUrl(packageName, version, versionCodes, searchEngineHost))
        },
        modifier = chipModifier,
        shape = if (highlighted) RoundedCornerShape(999.dp) else RoundedCornerShape(6.dp),
        color = background,
        contentColor = contentColor,
        border = if (highlighted) {
            null
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
        }
    ) {
        val contentModifier = if (highlighted) {
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        } else {
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(horizontal = 10.dp, vertical = 4.dp)
        }
        Column(
            modifier = contentModifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = if (experimental) {
                Arrangement.spacedBy(3.dp, Alignment.CenterVertically)
            } else {
                Arrangement.Center
            }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ExpandableText(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center
                )
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = stringResource(R.string.search),
                    modifier = Modifier.size(14.dp)
                )
            }
            if (experimental) {
                ExperimentalVersionBadge()
            }
        }
    }
}

private fun buildSearchUrl(
    packageName: String,
    version: String?,
    versionCodes: Set<Long>,
    searchEngineHost: String
): String {
    val encodedPackage = Uri.encode(packageName)
    val encodedVersion = version?.takeIf { it.isNotBlank() }?.let {
        val formatted = if (it.startsWith("v", ignoreCase = true)) it else "v$it"
        Uri.encode(formatted)
    }
    val encodedVersionCodes = versionCodes.sorted().map { Uri.encode(it.toString()) }
    val encodedArch = Build.SUPPORTED_ABIS.firstOrNull()
        ?.takeIf { it.isNotBlank() }
        ?.let(Uri::encode)
    val query = buildList {
        add(encodedPackage)
        encodedVersion?.let(::add)
        addAll(encodedVersionCodes)
        encodedArch?.let(::add)
    }.joinToString("+")
    val host = normalizeSearchHost(searchEngineHost)
    return "https://$host/search?q=$query"
}

private fun normalizeSearchHost(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return "google.com"
    val noScheme = trimmed.removePrefix("https://").removePrefix("http://")
    val noPath = noScheme.substringBefore('/').substringBefore('?').substringBefore('#')
    return noPath.trim().trimEnd('/').ifBlank { "google.com" }
}

@Composable
private fun OtherSupportedVersionsInfoDialog(
    bundleName: String,
    packageInfo: PackageInfo?,
    packageName: String,
    recommendedVersion: String?,
    recommendedVersionCodes: Set<Long>,
    recommendedVersionExperimental: Boolean,
    otherVersions: List<SupportedVersionInfo>,
    supportsAllVersions: Boolean,
    searchEngineHost: String,
    onDismissRequest: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.close))
            }
        },
        title = { CenteredDialogTitle(stringResource(R.string.other_supported_versions_title, bundleName)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                recommendedVersion?.let { version ->
                    VersionSearchRow(
                        label = stringResource(
                            R.string.bundle_version_suggested_label,
                            suggestedVersionLabel(
                                versionName = version,
                                versionCodes = recommendedVersionCodes,
                                displayVersion = stringResource(R.string.version_label, version)
                            )
                        ),
                        packageName = packageName,
                        version = version,
                        versionCodes = recommendedVersionCodes,
                        searchEngineHost = searchEngineHost,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        highlighted = true,
                        experimental = recommendedVersionExperimental
                    )
                }
                when {
                    otherVersions.isNotEmpty() -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            otherVersions.chunked(2).forEach { row ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    row.forEach { info ->
                                        VersionSearchRow(
                                            label = suggestedVersionLabel(
                                                versionName = info.version,
                                                versionCodes = info.versionCodes,
                                                displayVersion = stringResource(R.string.version_label, info.version)
                                            ),
                                            packageName = packageName,
                                            version = info.version,
                                            versionCodes = info.versionCodes,
                                            searchEngineHost = searchEngineHost,
                                            modifier = Modifier.weight(1f),
                                            experimental = info.experimental
                                        )
                                    }
                                    if (row.size == 1) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                    supportsAllVersions -> {
                        VersionSearchRow(
                            label = stringResource(R.string.other_supported_versions_all),
                            packageName = packageName,
                            version = null,
                            searchEngineHost = searchEngineHost,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                            highlighted = true
                        )
                    }
                    else -> {
                        Text(
                            stringResource(R.string.other_supported_versions_empty),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BundleSuggestionCard(
    suggestion: BundleVersionSuggestion,
    packageInfo: PackageInfo?,
    packageName: String,
    searchEngineHost: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onShowOtherVersions: () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val nameScrollState = rememberScrollState()
            Text(
                suggestion.bundleName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .consumeHorizontalScroll(nameScrollState)
            )
            val versionLabel = suggestion.recommendedVersion
                ?.let { version ->
                    suggestedVersionLabel(
                        versionName = version,
                        versionCodes = suggestion.recommendedVersionCodes,
                        displayVersion = stringResource(R.string.version_label, version)
                    )
                }
                ?: stringResource(R.string.bundle_version_all_versions)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (suggestion.recommendedVersion != null) {
                    VersionSearchChip(
                        label = versionLabel,
                        packageName = packageName,
                        version = suggestion.recommendedVersion,
                        versionCodes = suggestion.recommendedVersionCodes,
                        searchEngineHost = searchEngineHost,
                        modifier = Modifier,
                        highlighted = true,
                        experimental = suggestion.recommendedVersionExperimental
                    )
                } else if (suggestion.supportsAllVersions) {
                    VersionSearchChip(
                        label = versionLabel,
                        packageName = packageName,
                        version = null,
                        searchEngineHost = searchEngineHost,
                        modifier = Modifier,
                        highlighted = true
                    )
                }
            }
            Text(
                text = if (suggestion.supportsAllVersions) {
                    stringResource(R.string.other_supported_versions_all)
                } else if (suggestion.recommendedVersionExperimental) {
                    stringResource(R.string.bundle_version_dialog_recommended, versionLabel) +
                        " • " + stringResource(R.string.patch_bundle_experimental_version_label)
                } else {
                    stringResource(R.string.bundle_version_dialog_recommended, versionLabel)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val showOtherVersionsButton = !suggestion.supportsAllVersions ||
                suggestion.recommendedVersion != null ||
                suggestion.otherSupportedVersions.isNotEmpty()
            if (showOtherVersionsButton) {
                TextButton(
                    onClick = onShowOtherVersions,
                    enabled = enabled,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = if (enabled) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                        },
                        contentColor = if (enabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.clip(RoundedCornerShape(50))
                ) {
                    Text(
                        text = stringResource(R.string.show_other_versions),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}
