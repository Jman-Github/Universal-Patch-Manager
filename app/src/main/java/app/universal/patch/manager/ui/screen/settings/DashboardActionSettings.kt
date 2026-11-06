package app.universal.patch.manager.ui.screen.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Public
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsSwitch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import app.universal.patch.manager.R
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsItem
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsDivider
import app.universal.patch.manager.ui.component.settings.SettingsSearchHighlight
import app.universal.patch.manager.ui.model.dashboardActionOrder
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DashboardActionSettings(
    prefs: PreferencesManager,
    bundles: Boolean,
    highlightTarget: Int?,
    onHighlightComplete: () -> Unit
) {
    val orderPref = if (bundles) prefs.dashboardBundlesActionOrder else prefs.dashboardAppsActionOrder
    val stored by orderPref.getAsState()
    val hidden by prefs.dashboardHiddenActions.getAsState()
    val group = if (bundles) "bundles" else "apps"
    val defaults = if (bundles) listOf("discovery", "add") else listOf("storage", "add", "batch")
    val order = dashboardActionOrder(stored, defaults, emptySet(), group)
    var expanded by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val workingOrder = remember(stored, group) { order.toMutableStateList() }
    fun saveOrder() {
        val updated = workingOrder.joinToString(",")
        scope.launch { orderPref.update(updated) }
    }
    fun moveAction(id: String, offset: Int): Boolean {
        val from = workingOrder.indexOf(id)
        val to = from + offset
        if (from < 0 || to !in workingOrder.indices) return false
        workingOrder.add(to, workingOrder.removeAt(from))
        saveOrder()
        return true
    }
    fun label(id: String) = when (id) {
        "storage" -> R.string.select_from_storage
        "batch" -> R.string.batch_queue_create
        "discovery" -> R.string.patch_bundle_discovery_title
        else -> R.string.add
    }
    val titleRes = if (bundles) R.string.dashboard_bundles_actions else R.string.dashboard_apps_actions
    val visibilityRes = if (bundles) R.string.dashboard_bundles_visibility else R.string.dashboard_apps_visibility
    val moveUpLabel = stringResource(R.string.move_action_up)
    val moveDownLabel = stringResource(R.string.move_action_down)
    LaunchedEffect(highlightTarget) {
        if (highlightTarget == titleRes || highlightTarget == visibilityRes) expanded = true
    }
    SettingsSearchHighlight(
        targetKey = titleRes,
        activeKey = highlightTarget,
        onHighlightComplete = onHighlightComplete
    ) { highlightModifier ->
        ExpressiveSettingsItem(
            modifier = highlightModifier,
            headlineContent = stringResource(titleRes),
            supportingContent = stringResource(R.string.dashboard_actions_description),
            onClick = { expanded = !expanded },
            trailingContent = {
                Icon(
                    if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                    null
                )
            }
        )
    }
    if (expanded) {
        ExpressiveSettingsDivider()
        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            val rowState = rememberLazyListState()
            val reorderableState = rememberReorderableLazyListState(rowState) { from, to ->
                workingOrder.add(to.index, workingOrder.removeAt(from.index))
            }
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                shadowElevation = 6.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)),
                modifier = Modifier.widthIn(max = 520.dp)
            ) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp).height(48.dp),
                    state = rowState,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    items(workingOrder, key = { it }) { id ->
                        ReorderableItem(reorderableState, key = id) { isDragging ->
                            Box(Modifier.fillParentMaxHeight(), contentAlignment = Alignment.Center) {
                                SelectionActionPreviewChip(
                                    icon = when (id) {
                                        "storage" -> Icons.Default.Storage
                                        "batch" -> Icons.AutoMirrored.Outlined.PlaylistPlay
                                        "discovery" -> Icons.Outlined.Public
                                        else -> Icons.Default.Add
                                    },
                                    label = stringResource(label(id)),
                                    hidden = "$group:$id" in hidden,
                                    dragging = isDragging,
                                    group = group,
                                    modifier = Modifier
                                        .semantics(mergeDescendants = true) {
                                            customActions = buildList {
                                                if (workingOrder.indexOf(id) > 0) add(
                                                    CustomAccessibilityAction(moveUpLabel) { moveAction(id, -1) }
                                                )
                                                if (workingOrder.indexOf(id) < workingOrder.lastIndex) add(
                                                    CustomAccessibilityAction(moveDownLabel) { moveAction(id, 1) }
                                                )
                                            }
                                        }
                                        .longPressDraggableHandle(onDragStopped = { saveOrder() })
                                )
                            }
                        }
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsSearchHighlight(
                targetKey = visibilityRes,
                activeKey = highlightTarget,
                onHighlightComplete = onHighlightComplete
            ) { highlightModifier ->
                Text(
                    stringResource(visibilityRes),
                    modifier = highlightModifier,
                    style = MaterialTheme.typography.titleSmall
                )
            }
            Text(stringResource(R.string.dashboard_visibility_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        defaults.forEach { id ->
            val title = when (id) {
                "storage" -> R.string.select_from_storage
                "batch" -> R.string.batch_queue_create
                "discovery" -> R.string.patch_bundle_discovery_title
                else -> R.string.add
            }
            ExpressiveSettingsItem(
                headlineContent = stringResource(title),
                trailingContent = {
                    Row {
                        ExpressiveSettingsSwitch(checked = "$group:$id" !in hidden, onCheckedChange = { shown ->
                            scope.launch {
                                prefs.edit {
                                    val current = prefs.dashboardHiddenActions.value
                                    prefs.dashboardHiddenActions.value =
                                        if (shown) current - "$group:$id" else current + "$group:$id"
                                }
                            }
                        })
                    }
                }
            )
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
        ) {
            TextButton(onClick = {
                scope.launch {
                    prefs.edit {
                        prefs.dashboardHiddenActions.value = prefs.dashboardHiddenActions.value
                            .filterNot { it.startsWith("$group:") }.toSet()
                    }
                }
            }) {
                Text(stringResource(R.string.patch_selection_action_visibility_reset))
            }
            TextButton(onClick = {
                workingOrder.clear()
                workingOrder.addAll(defaults)
                saveOrder()
            }) {
                Text(stringResource(R.string.patch_selection_action_order_reset))
            }
        }
    }
    ExpressiveSettingsDivider()
}
