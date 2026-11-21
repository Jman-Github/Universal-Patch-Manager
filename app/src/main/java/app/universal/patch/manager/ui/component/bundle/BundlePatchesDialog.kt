package app.universal.patch.manager.ui.component.bundle

import app.universal.patch.manager.ui.component.persistentControls
import android.net.Uri
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universal.patch.manager.R
import app.universal.patch.manager.domain.bundles.PatchBundleSource
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.domain.repository.PatchBundleRepository
import app.universal.patch.manager.patcher.patch.PatchInfo
import app.universal.patch.manager.network.dto.PackageNode
import app.universal.patch.manager.ui.component.ArrowButton
import app.universal.patch.manager.ui.component.ExperimentalVersionBadge
import app.universal.patch.manager.ui.component.ExpandableText
import app.universal.patch.manager.ui.component.FullscreenDialog
import app.universal.patch.manager.ui.component.LazyColumnWithScrollbar
import app.universal.patch.manager.ui.component.suggestedVersionLabel
import app.universal.patch.manager.util.openUrl
import kotlinx.coroutines.flow.mapNotNull
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BundlePatchesDialog(
    onDismissRequest: () -> Unit,
    src: PatchBundleSource,
) {
    val patchBundleRepository: PatchBundleRepository = koinInject()
    val prefs: PreferencesManager = koinInject()
    val searchEngineHost by prefs.searchEngineHost.getAsState()
    var query by rememberSaveable(src.uid, "patches_query") { mutableStateOf("") }
    val patches by remember(src.uid) {
        patchBundleRepository.allBundlesInfoFlow.mapNotNull { it[src.uid]?.patches }
    }.collectAsStateWithLifecycle(emptyList())
    val filteredPatches = remember(patches, query) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            patches
        } else {
            patches.filter { it.matchesQuery(trimmed) }
        }
    }

    FullscreenDialog(
        onDismissRequest = onDismissRequest,
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                BundleTopBar(
                    title = stringResource(R.string.patches),
                    onBackClick = onDismissRequest,
                    backIcon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    },
                )
            },
        ) { paddingValues ->
            LazyColumnWithScrollbar(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(paddingValues),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(16.dp)
            ) {
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
                if (filteredPatches.isEmpty() && query.isNotBlank()) {
                    item(key = "patches_search_empty") {
                        Text(
                            text = stringResource(R.string.search_no_results),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    itemsIndexed(
                        items = filteredPatches,
                        key = { index, patch -> src.uid to (patch.name + "-" + index) }
                    ) { _, patch ->
                        var expandVersions by rememberSaveable(src.uid, patch.name, "versions") { mutableStateOf(false) }
                        var expandOptions by rememberSaveable(src.uid, patch.name, "options") { mutableStateOf(false) }

                        PatchItem(
                            patch,
                            expandVersions,
                            onExpandVersions = { expandVersions = !expandVersions },
                            expandOptions,
                            onExpandOptions = { expandOptions = !expandOptions },
                            searchEngineHost = searchEngineHost
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PatchItem(
    patch: PatchInfo,
    expandVersions: Boolean,
    onExpandVersions: () -> Unit,
    expandOptions: Boolean,
    onExpandOptions: () -> Unit,
    searchEngineHost: String,
    showCompatibilityMeta: Boolean = true,
    showOptionValues: Boolean = false,
    optionValues: Map<String, Any?>? = null,
    appMetadata: Map<String, PackageNode> = emptyMap(),
    allowAppMetadataLookup: Boolean = true
) {
    val context = LocalContext.current
    val anyPackageLabel = stringResource(R.string.patches_view_any_package)
    val anyVersionLabel = stringResource(R.string.patches_view_any_version)
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (patch.options.isNullOrEmpty()) Modifier else Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onExpandOptions),
            )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Absolute.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = patch.displayName,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                if (!patch.options.isNullOrEmpty()) {
                    ArrowButton(expanded = expandOptions, onClick = null)
                }
            }
            patch.description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (showCompatibilityMeta) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (patch.compatiblePackages.isNullOrEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
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
                            val packageName = compatiblePackage.packageName
                            val versions = compatiblePackage.versions.orEmpty().reversed()
                            val packageIsAny = isAnyPackageTag(packageName, anyPackageLabel)

                            if (allowAppMetadataLookup && !packageIsAny && packageName !in appMetadata) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    app.universal.patch.manager.ui.component.AppIcon(
                                        packageInfo = null, contentDescription = null,
                                        packageName = packageName, modifier = Modifier.size(28.dp)
                                    )
                                    app.universal.patch.manager.ui.component.AppLabel(
                                        packageInfo = null, packageName = packageName,
                                        defaultText = packageName, style = MaterialTheme.typography.labelLarge
                                    )
                                }
                            }
                            appMetadata[packageName]?.let { application ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    CatalogIcon(application.iconUrl, modifier = Modifier.size(28.dp))
                                    Text(application.appName?.takeIf { it.isNotBlank() } ?: packageName,
                                        style = MaterialTheme.typography.labelLarge)
                                }
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                PatchInfoChip(
                                    modifier = Modifier.align(Alignment.CenterVertically),
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
                                            val versionIsExperimental =
                                                compatiblePackage.experimentalVersions?.contains(version) == true
                                            val searchable = !(packageIsAny && versionIsAny)
                                            PatchInfoChip(
                                                modifier = Modifier.align(Alignment.CenterVertically),
                                                text = "$VERSION_ICON ${suggestedVersionLabel(
                                                    versionName = version,
                                                    versionCodes = compatiblePackage.versionCodes?.get(version).orEmpty()
                                                )}",
                                                experimental = versionIsExperimental,
                                                onClick = if (!searchable) {
                                                    null
                                                } else {
                                                    {
                                                        val queryVersion = if (versionIsAny) null else version
                                                        val queryPackage = if (packageIsAny) "android.app" else packageName
                                                        context.openUrl(
                                                            buildSearchUrl(
                                                                queryPackage,
                                                                queryVersion,
                                                                searchEngineHost,
                                                                compatiblePackage.versionCodes?.get(version).orEmpty()
                                                            )
                                                        )
                                                    }
                                                }
                                            )
                                        }
                                    } else {
                                        val displayedVersion = versions.first()
                                        val versionIsAny = isAnyVersionTag(displayedVersion, anyVersionLabel)
                                        val versionIsExperimental =
                                            compatiblePackage.experimentalVersions
                                                ?.contains(displayedVersion) == true
                                        val searchable = !(packageIsAny && versionIsAny)
                                        PatchInfoChip(
                                            modifier = Modifier.align(Alignment.CenterVertically),
                                            text = "$VERSION_ICON ${suggestedVersionLabel(
                                                versionName = displayedVersion,
                                                versionCodes = compatiblePackage.versionCodes?.get(displayedVersion).orEmpty()
                                            )}",
                                            experimental = versionIsExperimental,
                                            onClick = if (!searchable) {
                                                null
                                            } else {
                                                {
                                                    val queryVersion = if (versionIsAny) null else displayedVersion
                                                    val queryPackage = if (packageIsAny) "android.app" else packageName
                                                    context.openUrl(
                                                        buildSearchUrl(
                                                            queryPackage,
                                                            queryVersion,
                                                            searchEngineHost,
                                                            compatiblePackage.versionCodes?.get(displayedVersion).orEmpty()
                                                        )
                                                    )
                                                }
                                            }
                                        )
                                    }
                                    if (versions.size > 1) {
                                        PatchInfoChip(
                                            onClick = onExpandVersions,
                                            text = if (expandVersions) stringResource(R.string.less) else "+${versions.size - 1}"
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (!patch.options.isNullOrEmpty()) {
                AnimatedVisibility(visible = expandOptions) {
                    val options = patch.options

                    Column {
                        options.forEachIndexed { i, option ->
                            val resolvedValue = optionValues?.get(option.key) ?: option.default
                            val presetLabel = option.presets
                                ?.entries
                                ?.firstOrNull { it.value == resolvedValue }
                                ?.key
                            OutlinedCard(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardColors(
                                    containerColor = Color.Transparent,
                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                    disabledContainerColor = Color.Transparent,
                                    disabledContentColor = MaterialTheme.colorScheme.onSurface
                                ), shape = when {
                                    options.size == 1 -> RoundedCornerShape(8.dp)
                                    i == 0 -> RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
                                    i == options.lastIndex -> RoundedCornerShape(
                                        bottomStart = 8.dp,
                                        bottomEnd = 8.dp
                                    )

                                    else -> RoundedCornerShape(0.dp)
                                }
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = option.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = option.description,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    if (showOptionValues) {
                                        val displayValue = when {
                                            presetLabel != null -> presetLabel
                                            resolvedValue == null -> stringResource(R.string.app_version_unspecified)
                                            resolvedValue is Boolean -> if (resolvedValue) {
                                                stringResource(R.string.option_value_enabled)
                                            } else {
                                                stringResource(R.string.option_value_disabled)
                                            }
                                            resolvedValue is List<*> -> resolvedValue.joinToString(", ") { it.toString() }
                                            else -> resolvedValue.toString()
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Surface(
                                                color = MaterialTheme.colorScheme.secondaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                                shape = RoundedCornerShape(999.dp)
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.option_value_selected_label),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                )
                                            }
                                            Text(
                                                text = displayValue,
                                                style = MaterialTheme.typography.labelLarge,
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
}

@Composable
fun PatchInfoChip(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    text: String,
    experimental: Boolean = false
) {
    val shape = RoundedCornerShape(8.0.dp)
    val cardModifier = if (onClick != null) {
        Modifier
            .clip(shape)
            .clickable(onClick = onClick)
    } else {
        Modifier
    }

    OutlinedCard(
        modifier = modifier.then(cardModifier),
        colors = CardColors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = MaterialTheme.colorScheme.onSurface
        ),
        shape = shape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.20f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            ExpandableText(
                text = text,
                textAlign = TextAlign.Center,
                collapsedSoftWrap = false,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (experimental) {
                ExperimentalVersionBadge()
            }
        }
    }
}

const val PACKAGE_ICON = "\uD83D\uDCE6"
const val VERSION_ICON = "\uD83C\uDFAF"

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

private fun buildSearchUrl(
    packageName: String,
    version: String?,
    searchEngineHost: String,
    versionCodes: Set<Long> = emptySet()
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
