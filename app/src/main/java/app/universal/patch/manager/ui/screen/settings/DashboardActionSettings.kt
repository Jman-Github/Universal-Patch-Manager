package app.universal.patch.manager.ui.screen.settings

import androidx.compose.foundation.layout.*
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
    fun moveAction(id: String, offset: Int) {
        scope.launch {
            prefs.edit {
                val current = dashboardActionOrder(orderPref.value, defaults, emptySet(), group).toMutableList()
                val from = current.indexOf(id)
                if (from >= 0) {
                    val to = (from + offset).coerceIn(0, current.lastIndex)
                    current.add(to, current.removeAt(from))
                    orderPref.value = current.joinToString(",")
                }
            }
        }
    }
    val titleRes = if (bundles) R.string.dashboard_bundles_actions else R.string.dashboard_apps_actions
    LaunchedEffect(highlightTarget) {
        if (highlightTarget == titleRes) expanded = true
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
        order.forEachIndexed { index, id ->
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
                        IconButton(enabled = index > 0, onClick = { moveAction(id, -1) }) {
                            Icon(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.move_action_up))
                        }
                        IconButton(enabled = index < order.lastIndex, onClick = { moveAction(id, 1) }) {
                            Icon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.move_action_down))
                        }
                        Switch(checked = "$group:$id" !in hidden, onCheckedChange = { shown ->
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
    }
    ExpressiveSettingsDivider()
}
