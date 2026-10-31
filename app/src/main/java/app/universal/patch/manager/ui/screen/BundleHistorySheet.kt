package app.universal.patch.manager.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.universal.patch.manager.R
import app.universal.patch.manager.network.dto.ExternalBundleSnapshot
import app.universal.patch.manager.network.dto.HistoricalRelease
import app.universal.patch.manager.ui.component.LazyColumnWithScrollbar
import app.universal.patch.manager.ui.component.Markdown
import app.universal.patch.manager.ui.viewmodel.BundleDiscoveryViewModel
import app.universal.patch.manager.util.openUrl
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BundleHistorySheet(
    bundle: ExternalBundleSnapshot,
    viewModel: BundleDiscoveryViewModel,
    onDismiss: () -> Unit,
    onViewPatches: (ExternalBundleSnapshot) -> Unit,
    selectionMode: Boolean = false,
) {
    val listState = rememberLazyListState()
    val context = LocalContext.current
    var releases by remember(bundle.sourceUrl) { mutableStateOf(emptyList<HistoricalRelease>()) }
    var generation by remember(bundle.sourceUrl) { mutableStateOf<String?>(null) }
    var nextCursor by remember(bundle.sourceUrl) { mutableStateOf<String?>(null) }
    var append by remember { mutableStateOf(false) }
    var force by remember { mutableStateOf(false) }
    var request by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(listState, bundle.sourceUrl) {
        snapshotFlow {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            nextCursor?.takeIf {
                !loading && error == null && releases.isNotEmpty() &&
                    lastVisible >= (releases.size - 3).coerceAtLeast(0)
            }
        }.collect { cursor ->
            if (cursor != null) {
                append = true
                force = false
                loading = true
                request++
            }
        }
    }

    LaunchedEffect(bundle.sourceUrl, request) {
        if (!append) listState.scrollToItem(0)
        val requestedCursor = if (append) nextCursor else null
        loading = true
        error = null
        try {
            var page = try {
                viewModel.releaseHistory(bundle.sourceUrl, requestedCursor, force)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (!append || !failure.message.orEmpty().contains("Catalog changed", true)) throw failure
                // Cursors pin a generation. Discard old pages before restarting.
                releases = emptyList()
                generation = null
                append = false
                viewModel.releaseHistory(bundle.sourceUrl, null, true)
            }
            if (append && generation != page.generation) {
                releases = emptyList()
                append = false
                page = viewModel.releaseHistory(bundle.sourceUrl, null, true)
            }
            releases = (if (append) releases + page.data else page.data).distinctBy { it.id }
            generation = page.generation
            // Do not loop indefinitely if the server repeats the cursor we just used.
            nextCursor = page.nextCursor.takeUnless { append && it == requestedCursor }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            error = failure.message ?: context.getString(R.string.patch_bundle_discovery_error)
        } finally {
            loading = false
        }
    }

    // Size the sheet outside its offset-dependent content insets so scrolling cannot
    // change its measured height and move the expanded anchor.
    ModalBottomSheet(onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxHeight(0.9f),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.catalog_release_history),
                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(enabled = !loading, onClick = {
                    append = false
                    force = true
                    releases = emptyList()
                    generation = null
                    nextCursor = null
                    loading = true
                    request++
                }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.catalog_refresh))
                }
            }
            Text(bundle.sourceUrl.removePrefix("https://github.com/").trimEnd('/'),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            LazyColumnWithScrollbar(modifier = Modifier.weight(1f), state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(releases, key = { it.id }) { release ->
                    generation?.let { currentGeneration ->
                        key(currentGeneration, release.id) {
                            HistoricalReleaseCard(release, currentGeneration, viewModel, onViewPatches,
                                if (selectionMode) bundle.bundleType else null)
                        }
                    }
                }
                item(key = "history-status") {
                    // Keep the list's end stable when the paging spinner appears or disappears.
                    Box(Modifier.fillMaxWidth().heightIn(min = 60.dp), contentAlignment = Alignment.Center) {
                        if (loading) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                        } else if (error != null) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = { force = true; loading = true; request++ }) {
                                    Text(stringResource(R.string.bundle_changelog_retry))
                                }
                            }
                        } else if (releases.isEmpty()) {
                            Text(stringResource(R.string.catalog_history_empty))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoricalReleaseCard(
    release: HistoricalRelease,
    generation: String,
    viewModel: BundleDiscoveryViewModel,
    onViewPatches: (ExternalBundleSnapshot) -> Unit,
    requiredBundleType: String? = null,
) {
    val context = LocalContext.current
    var showNotes by remember { mutableStateOf(false) }
    var showBundles by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf<HistoricalRelease?>(null) }
    var bundles by remember { mutableStateOf<List<ExternalBundleSnapshot>?>(null) }
    var notesLoading by remember { mutableStateOf(false) }
    var bundlesLoading by remember { mutableStateOf(false) }
    var notesError by remember { mutableStateOf<String?>(null) }
    var bundlesError by remember { mutableStateOf<String?>(null) }
    var notesRequest by remember { mutableIntStateOf(0) }
    var bundlesRequest by remember { mutableIntStateOf(0) }

    LaunchedEffect(showNotes, notesRequest) {
        if (!showNotes || notes != null) return@LaunchedEffect
        notesLoading = true
        notesError = null
        try {
            notes = viewModel.historicalRelease(release.id, generation)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            notesError = failure.message
        } finally {
            notesLoading = false
        }
    }
    fun matchesRequiredBundle(historical: ExternalBundleSnapshot): Boolean =
        requiredBundleType == null ||
            (historical.bundleType.equals(requiredBundleType, true) &&
                !historical.isBundleV3 && !historical.downloadUrl.isNullOrBlank() &&
                historical.version.isNotBlank())

    LaunchedEffect(showBundles, bundlesRequest) {
        if (!showBundles || (requiredBundleType == null && bundles != null)) return@LaunchedEffect
        bundlesLoading = true
        bundlesError = null
        try {
            val loaded = bundles ?: viewModel.historicalBundles(release, generation).also { bundles = it }
            if (requiredBundleType != null) {
                loaded.firstOrNull(::matchesRequiredBundle)?.let(onViewPatches)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            bundlesError = failure.message ?: context.getString(R.string.patch_bundle_discovery_error)
        } finally {
            bundlesLoading = false
        }
    }

    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(release.version ?: release.tag ?: release.name ?: release.id,
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                release.releaseUrl?.let { url ->
                    IconButton(onClick = { context.openUrl(url) }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Outlined.OpenInNew,
                            contentDescription = stringResource(R.string.catalog_history_open_release),
                            modifier = Modifier.size(20.dp))
                    }
                }
            }
            Text(listOfNotNull(stringResource(if (release.isPrerelease)
                R.string.patch_bundle_discovery_prerelease else R.string.patch_bundle_discovery_release),
                release.publishedAt?.substringBefore('T')).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (release.availability != null && release.availability != "available") {
                Text(stringResource(R.string.catalog_history_unavailable),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            release.bundleTypes.distinct().forEach { type ->
                app.universal.patch.manager.ui.component.bundle.BundleRuntimeNotice(type)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (release.bundleIds.isNotEmpty()) {
                    FilledTonalButton(enabled = !bundlesLoading, onClick = {
                        if (requiredBundleType != null) {
                            showBundles = true
                            bundlesRequest++
                        } else {
                            showBundles = !showBundles
                        }
                    }) {
                        Text(stringResource(if (requiredBundleType != null) R.string.bundle_select_version
                            else R.string.patch_bundle_discovery_view_patches))
                    }
                }
                TextButton(onClick = { showNotes = !showNotes }) {
                    Text(stringResource(if (showNotes) R.string.catalog_hide_changelog
                        else R.string.bundle_changelog))
                }
            }
            if (showNotes) {
                if (notesLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                notesError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { notesRequest++ }) {
                        Text(stringResource(R.string.bundle_changelog_retry))
                    }
                }
                notes?.let {
                    val text = it.changelog?.takeIf(String::isNotBlank) ?: it.releaseNotes.orEmpty()
                    if (text.isBlank()) Text(stringResource(R.string.catalog_history_no_changelog))
                    else Markdown(text, compactHeadings = true)
                }
            }
            if (release.bundleIds.isEmpty()) Text(stringResource(R.string.catalog_history_no_bundle))
            if (showBundles) {
                if (bundlesLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                bundlesError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { bundlesRequest++ }) {
                        Text(stringResource(R.string.bundle_changelog_retry))
                    }
                }
                val matchingBundles = bundles?.filter(::matchesRequiredBundle)
                matchingBundles?.takeIf { requiredBundleType == null }?.forEach { historical ->
                    FilledTonalButton(onClick = { onViewPatches(historical) }) {
                        Text(listOf(historical.bundleType, historical.version).filter(String::isNotBlank)
                            .joinToString(" · "))
                    }
                }
                if (matchingBundles?.isEmpty() == true) Text(stringResource(R.string.catalog_history_no_bundle))
            }
        }
    }
}
