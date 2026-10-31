package app.universal.patch.manager.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universal.patch.manager.ui.component.LazyColumnWithScrollbar
import app.universal.patch.manager.ui.component.DASHBOARD_SCROLLBAR_THUMB_LENGTH
import app.universal.patch.manager.ui.component.bundle.BundleItem
import app.universal.patch.manager.ui.component.bundle.BundleItemPlaceholder
import app.universal.patch.manager.ui.component.settings.SettingsSearchHighlight
import app.universal.patch.manager.ui.viewmodel.BundleListViewModel
import app.universal.patch.manager.util.EventEffect
import app.universal.patch.manager.R
import app.universal.patch.manager.domain.bundles.PatchBundleSource
import app.universal.patch.manager.domain.bundles.PatchBundleSource.Extensions.asRemoteOrNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.Flow
import org.koin.androidx.compose.koinViewModel
import androidx.compose.foundation.interaction.MutableInteractionSource
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import app.universal.patch.manager.ui.component.CenteredDialogTitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BundleListScreen(
    viewModel: BundleListViewModel = koinViewModel(),
    eventsFlow: Flow<BundleListViewModel.Event>,
    setSelectedSourceCount: (Int) -> Unit,
    setSelectedSourceHasEnabled: (Boolean) -> Unit,
    searchQuery: String = "",
    showOrderDialog: Boolean = false,
    onDismissOrderDialog: () -> Unit = {},
    onScrollStateChange: (Boolean) -> Unit = {},
    highlightBundleUid: Int? = null,
    onHighlightConsumed: () -> Unit = {}
) {
    val patchCounts by viewModel.patchCounts.collectAsStateWithLifecycle(emptyMap())
    val sources by viewModel.sources.collectAsStateWithLifecycle<List<PatchBundleSource>?>(initialValue = null)
    val manualUpdateInfo by viewModel.manualUpdateInfo.collectAsStateWithLifecycle(emptyMap())
    val listState = rememberLazyListState()
    val normalizedQuery = searchQuery.trim().lowercase()
    val filteredSources = sources?.let { currentSources ->
        if (normalizedQuery.isBlank()) {
            currentSources
        } else {
            currentSources.filter { source ->
                val searchText = buildString {
                    append(source.displayTitle)
                    source.displayName?.let { displayName ->
                        append(' ')
                        append(displayName)
                    }
                    source.name.let { name ->
                        append(' ')
                        append(name)
                    }
                    source.version?.let { version ->
                        append(' ')
                        append(version)
                    }
                }.lowercase()
                searchText.contains(normalizedQuery)
            }
        }
    }

    EventEffect(eventsFlow) {
        viewModel.handleEvent(it)
    }
    val selectedUids = remember { derivedStateOf { viewModel.selectedSources.toSet() } }

    LaunchedEffect(selectedUids.value, sources) {
        setSelectedSourceCount(selectedUids.value.size)
        val selectedSources = sources.orEmpty().filter { it.uid in selectedUids.value }
        setSelectedSourceHasEnabled(selectedSources.any { it.enabled })
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { onScrollStateChange(it) }
    }

    LaunchedEffect(highlightBundleUid, filteredSources) {
        val uid = highlightBundleUid ?: return@LaunchedEffect
        val currentSources = filteredSources ?: return@LaunchedEffect
        val index = currentSources.indexOfFirst { it.uid == uid }
        if (index >= 0) {
            listState.animateScrollToItem(index)
        } else if (currentSources.isNotEmpty()) {
            onHighlightConsumed()
        }
    }

    PullToRefreshBox(
        onRefresh = viewModel::refresh,
        isRefreshing = viewModel.isRefreshing
    ) {
        if (sources == null) {
            LazyColumnWithScrollbar(
                thumbLength = DASHBOARD_SCROLLBAR_THUMB_LENGTH,
                modifier = Modifier.fillMaxSize(),
                state = listState,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top,
            ) {
                items(4) {
                    BundleItemPlaceholder()
                }
            }
        } else if (sources!!.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.patch_bundle_empty_state),
                    style = MaterialTheme.typography.titleLarge
                )
            }
        } else if (filteredSources.isNullOrEmpty() && normalizedQuery.isNotBlank()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.bundle_search_no_results),
                    style = MaterialTheme.typography.titleLarge
                )
            }
        } else {
            val currentSources = filteredSources.orEmpty()
            LazyColumnWithScrollbar(
                thumbLength = DASHBOARD_SCROLLBAR_THUMB_LENGTH,
                modifier = Modifier.fillMaxSize(),
                state = listState,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top,
            ) {
                items(
                    currentSources,
                    key = { it.uid }
                ) { source ->
                    val content: @Composable (Modifier) -> Unit = { modifier ->
                        BundleItem(
                            src = source,
                            patchCount = patchCounts[source.uid] ?: 0,
                            manualUpdateInfo = manualUpdateInfo[source.uid],
                            onDelete = {
                                viewModel.delete(source)
                            },
                            onDisable = {
                                viewModel.disable(source)
                            },
                            onUpdate = {
                                viewModel.update(source)
                            },
                            onForceUpdate = {
                                viewModel.forceUpdate(source)
                            },
                            selectable = viewModel.selectedSources.size > 0,
                            onSelect = {
                                viewModel.selectedSources.add(source.uid)
                            },
                            isBundleSelected = source.uid in viewModel.selectedSources,
                            toggleSelection = { bundleIsNotSelected ->
                                if (bundleIsNotSelected) {
                                    viewModel.selectedSources.add(source.uid)
                                } else {
                                    viewModel.selectedSources.remove(source.uid)
                                }
                            },
                            modifier = modifier
                        )
                    }

                    SettingsSearchHighlight(
                        targetKey = source.uid,
                        activeKey = highlightBundleUid,
                        onHighlightComplete = onHighlightConsumed,
                        contentPadding = PaddingValues(
                            vertical = if (highlightBundleUid != null) 2.dp else 0.dp
                        )
                    ) { modifier ->
                        content(modifier)
                    }
                }
            }
        }
    }

    if (showOrderDialog) {
        BundleOrderDialog(
            bundles = sources.orEmpty(),
            onDismissRequest = onDismissOrderDialog,
            onConfirm = { ordered ->
                viewModel.reorder(ordered.map(PatchBundleSource::uid))
                onDismissOrderDialog()
            }
        )
    }
}

@Composable
private fun BundleOrderDialog(
    bundles: List<PatchBundleSource>,
    onDismissRequest: () -> Unit,
    onConfirm: (List<PatchBundleSource>) -> Unit
) {
    val workingOrder = remember(bundles) { bundles.toMutableStateList() }
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        workingOrder.add(to.index, workingOrder.removeAt(from.index))
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = { onConfirm(workingOrder.toList()) }, enabled = workingOrder.isNotEmpty()) {
                Text(text = stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(R.string.cancel))
            }
        },
        title = { CenteredDialogTitle(text = stringResource(R.string.bundle_reorder_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.bundle_reorder_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyColumnWithScrollbar(
                    thumbLength = app.universal.patch.manager.ui.component.DASHBOARD_SCROLLBAR_THUMB_LENGTH,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    reserveScrollbarSpace = true,
                    state = lazyListState
                ) {
                    itemsIndexed(workingOrder, key = { _, bundle -> bundle.uid }) { index, bundle ->
                        val interactionSource = remember { MutableInteractionSource() }
                        ReorderableItem(reorderableState, key = bundle.uid) { _ ->
                            BundleOrderRow(
                                index = index,
                                bundle = bundle,
                                interactionSource = interactionSource
                            )
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun ReorderableCollectionItemScope.BundleOrderRow(
    index: Int,
    bundle: PatchBundleSource,
    interactionSource: MutableInteractionSource
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = (index + 1).toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = bundle.displayTitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            val typeLabel = stringResource(
                when {

                    bundle.asRemoteOrNull != null -> R.string.bundle_type_remote
                    else -> R.string.bundle_type_local
                }
            )
            Text(
                text = typeLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(
            onClick = {},
            interactionSource = interactionSource,
            modifier = Modifier.draggableHandle()
        ) {
            Icon(
                imageVector = Icons.Filled.DragHandle,
                contentDescription = stringResource(R.string.drag_handle)
            )
        }
    }
}
