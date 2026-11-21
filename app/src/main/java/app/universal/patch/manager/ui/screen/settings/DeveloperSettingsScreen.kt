package app.universal.patch.manager.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universal.patch.manager.R
import app.universal.patch.manager.ui.component.AlertDialogExtended
import app.universal.patch.manager.ui.component.AppTopBar
import app.universal.patch.manager.ui.component.GroupHeader
import app.universal.patch.manager.ui.component.SettingsSectionIcons
import app.universal.patch.manager.ui.component.settings.BooleanItem
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsCard
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsDivider
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsItem
import app.universal.patch.manager.ui.component.settings.SettingsSearchHighlight
import app.universal.patch.manager.ui.model.navigation.Settings
import app.universal.patch.manager.ui.screen.settings.SettingsSearchState
import app.universal.patch.manager.ui.viewmodel.DeveloperOptionsViewModel
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import app.universal.patch.manager.ui.component.CenteredDialogTitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperSettingsScreen(
    onBackClick: () -> Unit,
    vm: DeveloperOptionsViewModel = koinViewModel()
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val coroutineScope = rememberCoroutineScope()
    val searchTarget by SettingsSearchState.target.collectAsStateWithLifecycle()
    var highlightTarget by rememberSaveable { mutableStateOf<Int?>(null) }
    val showBatteryOptimizationBanner by vm.prefs.showBatteryOptimizationBanner.getAsState()
    val allowPatchProfileBundleOverride by vm.prefs.allowPatchProfileBundleOverride.getAsState()
    var showBatteryOptimizationDialog by rememberSaveable { mutableStateOf(false) }
    var showResetBundlesDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(searchTarget) {
        val target = searchTarget
        if (target?.destination == Settings.Developer) {
            highlightTarget = target.targetId
            SettingsSearchState.clear()
        }
    }

    if (showBatteryOptimizationDialog) {
        AlertDialogExtended(
            onDismissRequest = { showBatteryOptimizationDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            vm.prefs.showBatteryOptimizationBanner.update(false)
                        }
                        showBatteryOptimizationDialog = false
                    }
                ) {
                    Text(stringResource(R.string.battery_optimization_banner_disable_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatteryOptimizationDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            icon = { androidx.compose.material3.Icon(Icons.Default.BatteryAlert, null) },
            title = { CenteredDialogTitle(stringResource(R.string.battery_optimization_banner_disable_title)) },
            text = { Text(stringResource(R.string.battery_optimization_banner_disable_description)) }
        )
    }

    if (showResetBundlesDialog) {
        AlertDialogExtended(
            onDismissRequest = { showResetBundlesDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.resetBundles()
                        showResetBundlesDialog = false
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetBundlesDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            icon = { Icon(Icons.Outlined.Restore, null) },
            title = {
                Text(
                    text = stringResource(R.string.reset_patch_bundles_dialog_title),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.reset_patch_bundles_dialog_description),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        )
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.developer_options),
                scrollBehavior = scrollBehavior,
                onBackClick = onBackClick
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { paddingValues ->
        app.universal.patch.manager.ui.component.ColumnWithScrollbar(
            modifier = Modifier.padding(paddingValues),
            thumbLength = app.universal.patch.manager.ui.component.DASHBOARD_SCROLLBAR_THUMB_LENGTH
        ) {
            SettingsSearchHighlight(
                targetKey = R.string.stability_update_controls_section,
                activeKey = highlightTarget,
                onHighlightComplete = { highlightTarget = null }
            ) { highlightModifier ->
                GroupHeader(
                    stringResource(R.string.stability_update_controls_section),
                    icon = SettingsSectionIcons.StabilityUpdateControls,
                    modifier = highlightModifier
                )
            }
            ExpressiveSettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                SettingsSearchHighlight(
                    targetKey = R.string.battery_optimization_banner_title,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    BooleanItem(
                        modifier = highlightModifier,
                        value = showBatteryOptimizationBanner,
                        onValueChange = { enabled ->
                            if (enabled) {
                                coroutineScope.launch {
                                    vm.prefs.showBatteryOptimizationBanner.update(true)
                                }
                            } else {
                                showBatteryOptimizationDialog = true
                            }
                        },
                        headline = R.string.battery_optimization_banner_title,
                        description = R.string.battery_optimization_banner_description
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.patches_force_download,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpressiveSettingsItem(
                        headlineContent = stringResource(R.string.patches_force_download),
                        modifier = highlightModifier.clickable(onClick = vm::redownloadBundles)
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.reset_patch_bundles,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpressiveSettingsItem(
                        modifier = highlightModifier,
                        headlineContent = stringResource(R.string.reset_patch_bundles),
                        supportingContent = stringResource(R.string.reset_patch_bundles_description),
                        onClick = { showResetBundlesDialog = true }
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.patch_profile_bundle_override_title,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    BooleanItem(
                        modifier = highlightModifier,
                        value = allowPatchProfileBundleOverride,
                        onValueChange = { enabled ->
                            coroutineScope.launch {
                                vm.prefs.allowPatchProfileBundleOverride.update(enabled)
                            }
                        },
                        headline = R.string.patch_profile_bundle_override_title,
                        description = R.string.patch_profile_bundle_override_description
                    )
                }
            }
        }
    }
}
