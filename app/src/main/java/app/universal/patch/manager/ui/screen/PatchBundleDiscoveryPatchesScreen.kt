package app.universal.patch.manager.ui.screen

import app.universal.patch.manager.ui.component.persistentControls
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import app.universal.patch.manager.ui.component.bundle.CatalogIcon
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universal.patch.manager.R
import app.universal.patch.manager.domain.bundles.PatchBundleSource.Extensions.asRemoteOrNull
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.domain.repository.PatchBundleRepository
import app.universal.patch.manager.network.dto.ExternalBundlePatch
import app.universal.patch.manager.network.dto.ExternalBundleSnapshot
import app.universal.patch.manager.patcher.patch.PatchInfo
import app.universal.patch.manager.ui.component.AppTopBar
import app.universal.patch.manager.ui.component.LazyColumnWithScrollbar
import app.universal.patch.manager.ui.component.ShimmerBox
import app.universal.patch.manager.ui.component.bundle.PACKAGE_ICON
import app.universal.patch.manager.ui.component.bundle.PatchInfoChip
import app.universal.patch.manager.ui.component.bundle.PatchItem
import app.universal.patch.manager.ui.component.bundle.VERSION_ICON
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsCard
import app.universal.patch.manager.ui.viewmodel.BundleDiscoveryViewModel
import app.universal.patch.manager.util.openUrl
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatchBundleDiscoveryPatchesScreen(
    bundleId: Int,
    apiHost: String,
    sourceUrl: String,
    version: String,
    fileHash: String?,
    isPrerelease: Boolean,
    patchCount: Int,
    ownerName: String,
    repoName: String,
    bundleType: String,
    downloadUrl: String?,
    onBackClick: () -> Unit,
    historicalGeneration: String? = null,
    viewModel: BundleDiscoveryViewModel = koinViewModel(),
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val prefs: PreferencesManager = koinInject()
    val patchBundleRepository: PatchBundleRepository = koinInject()
    val searchEngineHost by prefs.searchEngineHost.getAsState()
    var query by rememberSaveable(bundleId, "patches_query") { mutableStateOf("") }
    val sources by patchBundleRepository.sources.collectAsStateWithLifecycle(emptyList())
    val bundleInfos by patchBundleRepository.allBundlesInfoFlow.collectAsStateWithLifecycle(emptyMap())
    val bundles = viewModel.bundles
    val isLoading = viewModel.isLoading
    val errorMessage = viewModel.errorMessage
    val routedBundle = remember(
        bundleId,
        apiHost,
        sourceUrl,
        version,
        fileHash,
        isPrerelease,
        patchCount,
        ownerName,
        repoName,
        bundleType,
        downloadUrl,
        historicalGeneration
    ) {
        ExternalBundleSnapshot(
            apiHost = apiHost,
            bundleId = bundleId,
            sourceUrl = sourceUrl,
            version = version,
            fileHash = fileHash,
            isPrerelease = isPrerelease,
            patchCount = patchCount,
            ownerName = ownerName,
            repoName = repoName,
            bundleType = bundleType,
            downloadUrl = downloadUrl,
            historicalGeneration = historicalGeneration
        )
    }
    val bundle = remember(bundles, routedBundle) {
        if (historicalGeneration != null) return@remember routedBundle
        val routedSourceUrl = routedBundle.sourceUrl.trim().removeSuffix("/")
        val routedFileHash = routedBundle.fileHash?.takeUnless(String::isBlank)
        bundles?.firstOrNull {
            it.bundleId == routedBundle.bundleId &&
                it.apiHost.equals(routedBundle.apiHost, ignoreCase = true) &&
                it.sourceUrl.trim().removeSuffix("/") == routedSourceUrl &&
                it.version == routedBundle.version &&
                it.isPrerelease == routedBundle.isPrerelease &&
                (routedFileHash == null || it.fileHash == routedFileHash)
        } ?: routedBundle
    }
    val patches = viewModel.getPatches(bundle)
    val patchesLoading = viewModel.isPatchesLoading(bundle)
    val patchesError = viewModel.getPatchesError(bundle)

    val importedUid = remember(bundle, sources, bundleInfos) {
        val matchingSources = sources.filter { src ->
            src.asRemoteOrNull?.let { remote ->
                viewModel.matchesLocalPatchSource(bundle, remote)
            } == true
        }
        matchingSources.firstOrNull { source ->
            !bundleInfos[source.uid]?.patches.isNullOrEmpty()
        }?.uid ?: matchingSources.firstOrNull()?.uid
    }
    val localPatches = importedUid?.let { bundleInfos[it]?.patches }
    val useLocalPatches = !localPatches.isNullOrEmpty()
    LaunchedEffect(localPatches, patches) {
        val names = if (useLocalPatches) localPatches.orEmpty().flatMap { patch ->
            patch.compatiblePackages.orEmpty().map { it.packageName }
        } else patches.orEmpty().flatMap { patch ->
            patch.compatiblePackages.filter { it.appName.isNullOrBlank() || it.iconUrl.isNullOrBlank() }
                .map { it.name }
        }
        viewModel.loadAppMetadata(names)
    }
    val filteredLocalPatches = remember(localPatches, query) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            localPatches
        } else {
            localPatches?.filter { it.matchesQuery(trimmed) }
        }
    }
    val filteredExternalPatches = remember(patches, query) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            patches
        } else {
            patches?.filter { it.matchesQuery(trimmed) }
        }
    }

    LaunchedEffect(bundle, useLocalPatches, patches, patchesLoading, patchesError) {
        if (
            !useLocalPatches &&
            patches == null &&
            !patchesLoading &&
            patchesError == null
        ) {
            viewModel.loadPatches(bundle)
        }
    }

    val baseTitle = bundle?.let {
        stringResource(R.string.patch_bundle_discovery_patches_title, it.ownerName, it.repoName)
    } ?: stringResource(R.string.patch_bundle_discovery_patches_title_fallback)
    val title = if (historicalGeneration != null) "$baseTitle · $version" else baseTitle

    Scaffold(
        topBar = {
            AppTopBar(
                title = title,
                scrollBehavior = scrollBehavior,
                onBackClick = onBackClick
            )
        },
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { paddingValues ->
        LazyColumnWithScrollbar(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (app.universal.patch.manager.ui.component.bundle.catalogRuntimeNotice(bundle.bundleType) != null) {
                item(key = "runtime_notice") {
                    app.universal.patch.manager.ui.component.bundle.BundleRuntimeNotice(bundle.bundleType)
                }
            }
            persistentControls(key = "patches_search") {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.search_patches)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = stringResource(R.string.search)
                        )
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = stringResource(R.string.clear)
                                )
                            }
                        }
                    }
                )
            }
            when {
                (isLoading && bundle == null) || (patchesLoading && !useLocalPatches) -> {
                    items(4) {
                        PatchBundlePatchPlaceholderItem()
                    }
                }

                !useLocalPatches && patchesError != null -> {
                    item {
                        Column {
                            PatchBundlePatchesEmptyState(
                                message = patchesError ?: errorMessage ?: stringResource(
                                    R.string.patch_bundle_discovery_patches_empty
                                )
                            )
                            androidx.compose.material3.TextButton(onClick = {
                                viewModel.loadPatches(bundle)
                            }) { Text(stringResource(R.string.bundle_changelog_retry)) }
                        }
                    }
                }

                !useLocalPatches && patches == null -> {
                    items(4) {
                        PatchBundlePatchPlaceholderItem()
                    }
                }

                query.isNotBlank() && useLocalPatches && filteredLocalPatches.isNullOrEmpty() -> {
                    item {
                        PatchBundlePatchesEmptyState(
                            message = stringResource(R.string.search_no_results)
                        )
                    }
                }

                query.isNotBlank() && !useLocalPatches && filteredExternalPatches.isNullOrEmpty() -> {
                    item {
                        PatchBundlePatchesEmptyState(
                            message = stringResource(R.string.search_no_results)
                        )
                    }
                }

                !useLocalPatches && filteredExternalPatches.isNullOrEmpty() -> {
                    item {
                        PatchBundlePatchesEmptyState(
                            message = stringResource(R.string.patch_bundle_discovery_patches_empty)
                        )
                    }
                }

                useLocalPatches -> {
                    itemsIndexed(
                        items = filteredLocalPatches.orEmpty(),
                        key = { index, patch -> "${importedUid ?: "bundle"}-${patch.name}-$index" }
                    ) { _, patch ->
                        var expandVersions by rememberSaveable(importedUid, patch.name, "versions") {
                            mutableStateOf(false)
                        }
                        var expandOptions by rememberSaveable(importedUid, patch.name, "options") {
                            mutableStateOf(false)
                        }

                        PatchItem(
                            patch,
                            expandVersions,
                            onExpandVersions = { expandVersions = !expandVersions },
                            expandOptions,
                            onExpandOptions = { expandOptions = !expandOptions },
                            searchEngineHost = searchEngineHost,
                            appMetadata = viewModel.appMetadata,
                            allowAppMetadataLookup = false
                        )
                    }
                }

                // Currently used for discovery bundles that aren't imported (API has no options)
                else -> {
                    itemsIndexed(
                        items = filteredExternalPatches.orEmpty(),
                        key = { index, patch -> "${bundleId}-${patch.name ?: "patch"}-$index" }
                    ) { index, patch ->
                        PatchBundlePatchItem(
                            patch = patch,
                            searchEngineHost = searchEngineHost,
                            stateKey = "${bundleId}-${patch.name ?: "patch"}-$index",
                            appMetadata = viewModel.appMetadata
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PatchBundlePatchPlaceholderItem() {
    ExpressiveSettingsCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ShimmerBox(modifier = Modifier.fillMaxWidth(0.7f).height(18.dp))
            ShimmerBox(modifier = Modifier.fillMaxWidth(0.9f).height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ShimmerBox(modifier = Modifier.size(width = 120.dp, height = 28.dp))
                ShimmerBox(modifier = Modifier.size(width = 100.dp, height = 28.dp))
                ShimmerBox(modifier = Modifier.size(width = 140.dp, height = 28.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PatchBundlePatchItem(
    patch: ExternalBundlePatch,
    searchEngineHost: String,
    stateKey: String,
    appMetadata: Map<String, app.universal.patch.manager.network.dto.PackageNode>,
) {
    val context = LocalContext.current
    val anyPackageLabel = stringResource(R.string.patches_view_any_package)
    val anyVersionLabel = stringResource(R.string.patches_view_any_version)
    var expandVersions by rememberSaveable(stateKey) { mutableStateOf(false) }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = patch.name ?: stringResource(R.string.patch_bundle_discovery_patch_unnamed),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            patch.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (patch.compatiblePackages.isEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        PatchInfoChip(
                            text = "$PACKAGE_ICON $anyPackageLabel"
                        )
                        PatchInfoChip(
                            text = "$VERSION_ICON $anyVersionLabel"
                        )
                    }
                } else {
                    patch.compatiblePackages.forEach { compatiblePackage ->
                        val packageName = compatiblePackage.name
                        val versions = compatiblePackage.versions
                            .filterNotNull()
                            .filter { it.isNotBlank() }
                            .reversed()
                        val experimentalVersions = compatiblePackage.experimentalVersions
                            .filterNotNull()
                            .filter { it.isNotBlank() }
                            .toSet()
                        val packageIsAny = isAnyPackageTag(packageName, anyPackageLabel)

                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val application = appMetadata[packageName]
                            val appName = compatiblePackage.appName?.takeIf { it.isNotBlank() }
                                ?: application?.appName?.takeIf { it.isNotBlank() } ?: packageName
                            CatalogIcon(compatiblePackage.iconUrl?.takeIf { it.isNotBlank() }
                                ?: application?.iconUrl, description = appName,
                                modifier = Modifier.size(24.dp))
                            Text(appName, style = MaterialTheme.typography.labelMedium)
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            PatchInfoChip(
                                modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically),
                                text = "$PACKAGE_ICON $packageName",
                                onClick = if (packageIsAny) {
                                    null
                                } else {
                                    {
                                        context.openUrl(buildSearchUrl(packageName, null, searchEngineHost))
                                    }
                                }
                            )

                            if (versions.isNotEmpty()) {
                                if (expandVersions) {
                                    versions.forEach { version ->
                                        val versionIsAny = isAnyVersionTag(version, anyVersionLabel)
                                        val versionIsExperimental = version in experimentalVersions
                                        val searchable = !(packageIsAny && versionIsAny)
                                        PatchInfoChip(
                                            modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically),
                                            text = "$VERSION_ICON $version",
                                            experimental = versionIsExperimental,
                                            onClick = if (!searchable) {
                                                null
                                            } else {
                                                {
                                                    val queryVersion = if (versionIsAny) null else version
                                                    val queryPackage = if (packageIsAny) "android.app" else packageName
                                                    context.openUrl(
                                                        buildSearchUrl(queryPackage, queryVersion, searchEngineHost)
                                                    )
                                                }
                                            }
                                        )
                                    }
                                } else {
                                    val displayedVersion = versions.first()
                                    val versionIsAny = isAnyVersionTag(displayedVersion, anyVersionLabel)
                                    val versionIsExperimental = displayedVersion in experimentalVersions
                                    val searchable = !(packageIsAny && versionIsAny)
                                    PatchInfoChip(
                                        modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically),
                                        text = "$VERSION_ICON $displayedVersion",
                                        experimental = versionIsExperimental,
                                        onClick = if (!searchable) {
                                            null
                                        } else {
                                            {
                                                val queryVersion = if (versionIsAny) null else displayedVersion
                                                val queryPackage = if (packageIsAny) "android.app" else packageName
                                                context.openUrl(
                                                    buildSearchUrl(queryPackage, queryVersion, searchEngineHost)
                                                )
                                            }
                                        }
                                    )
                                }
                                if (versions.size > 1) {
                                    PatchInfoChip(
                                        onClick = { expandVersions = !expandVersions },
                                        text = if (expandVersions) stringResource(R.string.less) else "+${versions.size - 1}"
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

@Composable
private fun PatchBundlePatchesEmptyState(message: String) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PatchBundlePatchPlaceholderItem()
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun buildSearchUrl(packageName: String, version: String?, searchEngineHost: String): String {
    val encodedPackage = Uri.encode(packageName)
    val encodedVersion = version?.takeIf { it.isNotBlank() }?.let {
        val formatted = if (it.startsWith("v", ignoreCase = true)) it else "v$it"
        Uri.encode(formatted)
    }
    val encodedArch = Build.SUPPORTED_ABIS.firstOrNull()
        ?.takeIf { it.isNotBlank() }
        ?.let(Uri::encode)
    val query = listOfNotNull(encodedPackage, encodedVersion, encodedArch).joinToString("+")
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

private fun isAnyPackageTag(value: String?, anyPackageLabel: String): Boolean {
    val normalized = value.orEmpty().trim().lowercase()
    if (normalized.isBlank()) return true
    val normalizedLabel = anyPackageLabel.trim().lowercase()
    return normalized == normalizedLabel ||
        normalized == "any package" ||
        normalized == "*"
}

private fun isAnyVersionTag(value: String?, anyVersionLabel: String): Boolean {
    val normalized = value.orEmpty().trim().lowercase()
    if (normalized.isBlank()) return true
    val normalizedLabel = anyVersionLabel.trim().lowercase()
    return normalized == normalizedLabel ||
        normalized == "any version" ||
        normalized == "*"
}

private fun PatchInfo.matchesQuery(query: String): Boolean {
    val normalized = query.lowercase()
    if (name.contains(normalized, ignoreCase = true)) return true
    if (description?.contains(normalized, ignoreCase = true) == true) return true
    if (compatiblePackages?.any { pkg ->
            pkg.packageName.contains(normalized, ignoreCase = true) ||
                (pkg.versions?.any { it.contains(normalized, ignoreCase = true) } == true) ||
                (pkg.versionCodes?.values?.flatten()?.any { it.toString().contains(normalized) } == true)
        } == true
    ) {
        return true
    }
    if (options?.any { option ->
            option.title.contains(normalized, ignoreCase = true) ||
                option.key.contains(normalized, ignoreCase = true) ||
                option.description.contains(normalized, ignoreCase = true)
        } == true
    ) {
        return true
    }
    return false
}

private fun ExternalBundlePatch.matchesQuery(query: String): Boolean {
    val normalized = query.lowercase()
    if (name?.contains(normalized, ignoreCase = true) == true) return true
    if (description?.contains(normalized, ignoreCase = true) == true) return true
    if (compatiblePackages.any { pkg ->
            pkg.name.contains(normalized, ignoreCase = true) ||
                pkg.versions.any { it?.contains(normalized, ignoreCase = true) == true }
        }
    ) {
        return true
    }
    return false
}
