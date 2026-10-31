package app.universal.patch.manager.ui.screen

import androidx.annotation.StringRes
import androidx.appcompat.content.res.AppCompatResources
import app.universal.patch.manager.BuildConfig
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.universal.patch.manager.R
import app.universal.patch.manager.ui.component.AppIcon
import app.universal.patch.manager.ui.component.AppTopBar
import app.universal.patch.manager.ui.component.ColumnWithScrollbar
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsCard
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsDivider
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsItem
import app.universal.patch.manager.ui.model.navigation.Settings
import app.universal.patch.manager.ui.screen.settings.SettingsSearchState
import app.universal.patch.manager.ui.screen.settings.SettingsSearchTarget

private data class Section(
    @StringRes val name: Int,
    @StringRes val description: Int,
    val image: ImageVector,
    val destination: Settings.Destination,
    val keywords: List<Int> = emptyList(),
)

private data class SearchEntry(
    @StringRes val title: Int,
    @StringRes val description: Int?,
    @StringRes val category: Int,
    val destination: Settings.Destination,
    val keywords: List<Int> = emptyList(),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBackClick: () -> Unit, navigate: (Settings.Destination) -> Unit) {
    val context = LocalContext.current
    val appIcon = remember {
        AppCompatResources.getDrawable(context, R.mipmap.ic_launcher)
    }
    val settingsSections = remember {
        listOf(
            Section(
                R.string.general,
                R.string.general_description,
                Icons.Outlined.Settings,
                Settings.General,
                keywords = listOf(
                    R.string.appearance,
                    R.string.dynamic_color,
                    R.string.theme_presets,
                    R.string.theme_color,
                    R.string.accent_color,
                    R.string.custom_background_image,
                    R.string.custom_background_image_transparency,
                    R.string.clear_custom_background_image,
                    R.string.hide_main_tab_labels,
                    R.string.show_apps_tab_update_count,
                    R.string.show_bundles_tab_update_count,
                    R.string.disable_main_tab_swipe,
                    R.string.disable_patch_selection_tab_swipe,
                    R.string.prevent_accidental_touching,
                    R.string.hide_patch_profiles_tab,
                    R.string.hide_tools_tab,
                    R.string.show_lsposed_tab,
                    R.string.patch_selection_action_order_title,
                    R.string.patch_selection_action_visibility_title,
                    R.string.patch_bundle_action_order_title,
                    R.string.patch_bundle_action_visibility_title,
                    R.string.batch_result_action_order_title,
                    R.string.batch_result_action_visibility_title,
                    R.string.saved_app_action_order_title,
                    R.string.saved_app_action_visibility_title,
                    R.string.patch_profile_action_order_title,
                    R.string.patch_profile_action_visibility_title,
                    R.string.lsposed_module_action_order_title,
                    R.string.lsposed_module_action_visibility_title,
                    R.string.theme_preview_title,
                    R.string.theme_reset
                )
            ),
            Section(
                R.string.patcher_category,
                R.string.patcher_category_description,
                Icons.Outlined.Build,
                Settings.Patcher,
                keywords = listOf(
                    R.string.strip_unused_libs,
                    R.string.skip_unneeded_split_apks,
                    R.string.choose_split_apks_before_patching,
                    R.string.continue_on_patch_error,
                    R.string.skip_apk_signing,
                    R.string.inject_signature_metadata_after_patching,
                    R.string.patcher_log_mode,
                    R.string.patcher_jvm_enable,
                    R.string.process_memory_limit,
                    R.string.morphe_bytecode_mode,
                    R.string.patcher_memory_usage_graph_title,
                    R.string.patcher_resource_graphs_compact_title,
                    R.string.patcher_resource_graph_options_title,
                    R.string.patcher_compact_resource_graph_options_title,
                    R.string.patcher_auto_collapse_steps,
                    R.string.patcher_auto_expand_steps,
                    R.string.patcher_auto_expand_running_steps_exclusive,
                    R.string.skip_split_merge_signing,
                    R.string.merge_split_memory_usage_graph_title,
                    R.string.merger_resource_graphs_compact_title,
                    R.string.merger_resource_graph_options_title,
                    R.string.merger_compact_resource_graph_options_title,
                    R.string.merge_split_auto_collapse_steps,
                    R.string.merge_split_auto_expand_steps,
                    R.string.merge_split_auto_expand_running_steps_exclusive,
                    R.string.show_patch_selection_summary,
                    R.string.patch_selection_collapse_on_toggle,
                    R.string.patcher_saved_apps_title,
                    R.string.saved_apps_disable_overwrite_title,
                    R.string.saved_apps_show_bundle_update_badges_title,
                    R.string.minimal_patch_selection_view_title,
                    R.string.patch_selection_version_tags_title,
                    R.string.patch_selection_option_previews_title
                )
            ),
            Section(
                R.string.advanced_system,
                R.string.advanced_system_description,
                Icons.Outlined.AdminPanelSettings,
                Settings.AdvancedSystem,
                keywords = listOf(
                    R.string.app_language,
                    R.string.announcement_system_enabled,
                    R.string.api_url,
                    R.string.github_pat,
                    R.string.include_github_pat_in_exports_label,
                    R.string.search_engine_host_title,
                    R.string.disable_apk_signature_checks,
                    R.string.installer_choose_per_install_title,
                    R.string.installer_primary_title,
                    R.string.installer_fallback_title,
                    R.string.installer_shizuku_configure_title,
                    R.string.installer_play_store_mode,
                    R.string.settings_auto_install_with_shizuku,
                    R.string.settings_auto_uninstall_with_shizuku,
                    R.string.installer_shizuku_google_play_name,
                    R.string.installer_shizuku_google_play_description,
                    R.string.installer_custom_manage_title,
                    R.string.auto_patch_enabled,
                    R.string.auto_patch_install_with_shizuku,
                    R.string.auto_patch_requires_charging,
                    R.string.auto_patch_interval,
                    R.string.batch_patch_external_actions,
                    R.string.use_custom_file_picker_title,
                    R.string.patch_compat_check,
                    R.string.suggested_version_safeguard,
                    R.string.patch_selection_safeguard,
                    R.string.disable_patch_selection_confirmations,
                    R.string.universal_patches_safeguard,
                    R.string.patch_availability_enabled,
                    R.string.remove_gmscore_for_primary_mount,
                    R.string.export_name_format,
                    R.string.merged_apk_name_format,
                    R.string.debug_logs_export,
                    R.string.about_device
                )
            ),
            Section(
                R.string.updates,
                R.string.updates_description,
                Icons.Outlined.Update,
                Settings.Updates,
                keywords = listOf(
                    R.string.update_on_metered_connections,
                    R.string.manual_update_check,
                    R.string.changelog,
                    R.string.update_checking_manager,
                    R.string.show_manager_update_dialog_on_launch,
                    R.string.show_manager_update_changelog,
                    R.string.manager_prereleases,
                    R.string.background_manager_update,
                    R.string.background_bundle_update,
                    R.string.announcement_push_notifications,
                    R.string.background_radio_menu_title,
                    R.string.background_bundle_ask_notification,
                    R.string.bundle_update_delivery_mode,
                    R.string.bundle_update_delivery_mode_dialog_title,
                    R.string.bundle_update_delivery_mode_auto,
                    R.string.bundle_update_delivery_mode_websocket_preferred,
                    R.string.bundle_update_delivery_mode_polling_only,
                    R.string.bundle_changelog_history_section,
                    R.string.bundle_changelog_fetch_limit,
                    R.string.bundle_changelog_storage_limit
                )
            ),
            Section(
                R.string.downloads,
                R.string.downloads_description,
                Icons.Outlined.Download,
                Settings.Downloads,
                keywords = listOf(
                    R.string.download_settings,
                    R.string.downloader_auto_save_title,
                    R.string.downloader_auto_save_latest_only_title,
                    R.string.downloader_plugins,
                    R.string.downloaded_apps,
                    R.string.downloaded_apps_export
                )
            ),
            Section(
                R.string.patcher_runtime_plugins,
                R.string.patcher_runtime_plugins_description,
                Icons.Outlined.Extension,
                Settings.PatcherRuntimes,
                keywords = listOf(
                    R.string.patcher_runtime_import_url,
                    R.string.patcher_runtime_managed_sources,
                    R.string.patcher_runtime_installed_plugins
                )
            ),
            Section(
                R.string.storage_cache_management,
                R.string.storage_cache_management_description,
                Icons.Outlined.Storage,
                Settings.Storage,
                keywords = listOf(
                    R.string.storage_overview_section,
                    R.string.storage_usage_section,
                    R.string.storage_total_app_storage,
                    R.string.storage_internal_cache,
                    R.string.storage_code_cache,
                    R.string.storage_apk_signer_cache,
                    R.string.storage_internal_files,
                    R.string.storage_no_backup_files,
                    R.string.storage_downloaded_apps,
                    R.string.storage_patch_bundles,
                    R.string.storage_signing_files,
                    R.string.storage_downloader_plugins,
                    R.string.storage_patcher_runtime_plugins,
                    R.string.storage_lsposed_modules,
                    R.string.storage_patched_apps,
                    R.string.storage_retained_originals,
                    R.string.storage_patch_profile_inputs,
                    R.string.storage_custom_backgrounds,
                    R.string.storage_temporary_workspace,
                    R.string.storage_ui_temporary_workspace,
                    R.string.storage_other_internal_data,
                    R.string.storage_external_cache,
                    R.string.storage_external_files,
                    R.string.storage_cache_actions_section,
                    R.string.storage_clear_app_cache,
                    R.string.storage_auto_clear_cache,
                    R.string.storage_open_app_storage_settings,
                    R.string.storage_refresh_usage
                )
            ),
            Section(
                R.string.import_export,
                R.string.import_export_description,
                Icons.Outlined.SwapVert,
                Settings.ImportExport,
                keywords = listOf(
                    R.string.import_keystore,
                    R.string.import_everything,
                    R.string.import_patch_selection,
                    R.string.import_patch_bundles,
                    R.string.import_patch_profiles,
                    R.string.import_manager_settings,
                    R.string.export_keystore,
                    R.string.export_everything,
                    R.string.export_patch_selection,
                    R.string.export_patch_bundles,
                    R.string.export_patch_profiles,
                    R.string.export_manager_settings,
                    R.string.reset_patch_selection,
                    R.string.reset_patch_options,
                    R.string.regenerate_keystore
                )
            ),
            Section(
                R.string.about,
                R.string.app_name,
                Icons.Outlined.Info,
                Settings.About
            ),
            Section(
                R.string.developer_options,
                R.string.developer_options_description,
                Icons.Outlined.Code,
                Settings.Developer,
                keywords = listOf(
                    R.string.battery_optimization_banner_title,
                    R.string.patches_force_download,
                    R.string.reset_patch_bundles,
                    R.string.patch_profile_bundle_override_title
                )
            )
        )
    }
    val sectionIconMap = remember(settingsSections) {
        settingsSections.associate { it.destination to it.image }
    }
    val settingsEntries = remember {
        listOf(
            SearchEntry(R.string.app_behavior_section, null, R.string.advanced_system, Settings.AdvancedSystem),
            SearchEntry(R.string.network_integrations_section, null, R.string.advanced_system, Settings.AdvancedSystem),
            SearchEntry(R.string.installer_section, null, R.string.advanced_system, Settings.AdvancedSystem),
            SearchEntry(R.string.auto_patch_section, null, R.string.advanced_system, Settings.AdvancedSystem),
            SearchEntry(R.string.safeguards_compatibility_section, null, R.string.advanced_system, Settings.AdvancedSystem),
            SearchEntry(R.string.patching_engine_section, null, R.string.patcher_category, Settings.Patcher),
            SearchEntry(R.string.patching_flow_section, null, R.string.patcher_category, Settings.Patcher),
            SearchEntry(R.string.merge_split_flow_section, null, R.string.patcher_category, Settings.Patcher),
            SearchEntry(R.string.saved_apps_section, null, R.string.patcher_category, Settings.Patcher),
            SearchEntry(R.string.patch_list_display_section, null, R.string.patcher_category, Settings.Patcher),
            SearchEntry(R.string.diagnostics_output_section, null, R.string.advanced_system, Settings.AdvancedSystem),
            SearchEntry(R.string.stability_update_controls_section, null, R.string.developer_options, Settings.Developer),
            SearchEntry(R.string.download_behavior_section, null, R.string.downloads, Settings.Downloads),
            SearchEntry(R.string.download_export_section, null, R.string.downloads, Settings.Downloads),
            SearchEntry(R.string.navigation_tabs_section, null, R.string.general, Settings.General),
            SearchEntry(R.string.action_buttons_section, null, R.string.general, Settings.General),
            SearchEntry(R.string.theme_section, null, R.string.general, Settings.General),
            SearchEntry(R.string.background_section, null, R.string.general, Settings.General),
            SearchEntry(R.string.keystore_section, null, R.string.import_export, Settings.ImportExport),
            SearchEntry(R.string.settings_selections_bundles_section, null, R.string.import_export, Settings.ImportExport),
            SearchEntry(R.string.reset, null, R.string.import_export, Settings.ImportExport),
            SearchEntry(R.string.patcher_runtime_available, null, R.string.patcher_runtime_plugins, Settings.PatcherRuntimes),
            SearchEntry(R.string.patcher_runtime_plugins_section, null, R.string.patcher_runtime_plugins, Settings.PatcherRuntimes),
            SearchEntry(R.string.network_delivery_section, null, R.string.updates, Settings.Updates),
            SearchEntry(R.string.update_checks_section, null, R.string.updates, Settings.Updates),
            SearchEntry(R.string.bundle_changelog_history_section, null, R.string.updates, Settings.Updates),
            SearchEntry(R.string.background_updates_section, null, R.string.updates, Settings.Updates),
            SearchEntry(R.string.dynamic_color, R.string.dynamic_color_description, R.string.general, Settings.General),
            SearchEntry(R.string.theme_presets, R.string.theme_presets_description, R.string.general, Settings.General),
            SearchEntry(R.string.theme_color, R.string.theme_color_description, R.string.general, Settings.General),
            SearchEntry(R.string.accent_color, R.string.accent_color_description, R.string.general, Settings.General),
            SearchEntry(R.string.accent_color_presets, R.string.accent_color_presets_description, R.string.general, Settings.General),
            SearchEntry(R.string.pure_black_follow_system, R.string.pure_black_follow_system_description, R.string.general, Settings.General),
            SearchEntry(R.string.custom_background_image, R.string.custom_background_image_description, R.string.general, Settings.General),
            SearchEntry(R.string.custom_background_image_transparency, R.string.custom_background_image_transparency_description, R.string.general, Settings.General),
            SearchEntry(R.string.clear_custom_background_image, R.string.clear_custom_background_image_description, R.string.general, Settings.General),
            SearchEntry(R.string.hide_main_tab_labels, R.string.hide_main_tab_labels_description, R.string.general, Settings.General),
            SearchEntry(R.string.show_apps_tab_update_count, R.string.show_apps_tab_update_count_description, R.string.general, Settings.General),
            SearchEntry(R.string.show_bundles_tab_update_count, R.string.show_bundles_tab_update_count_description, R.string.general, Settings.General),
            SearchEntry(R.string.disable_main_tab_swipe, R.string.disable_main_tab_swipe_description, R.string.general, Settings.General),
            SearchEntry(R.string.disable_patch_selection_tab_swipe, R.string.disable_patch_selection_tab_swipe_description, R.string.general, Settings.General),
            SearchEntry(R.string.prevent_accidental_touching, R.string.prevent_accidental_touching_description, R.string.general, Settings.General),
            SearchEntry(R.string.hide_patch_profiles_tab, R.string.hide_patch_profiles_tab_description, R.string.general, Settings.General),
            SearchEntry(R.string.hide_tools_tab, R.string.hide_tools_tab_description, R.string.general, Settings.General),
            SearchEntry(R.string.show_lsposed_tab, R.string.show_lsposed_tab_description, R.string.general, Settings.General),
            SearchEntry(R.string.theme_preview_title, R.string.theme_preview_description, R.string.general, Settings.General),
            SearchEntry(R.string.theme_reset, null, R.string.general, Settings.General),
            SearchEntry(R.string.app_language, R.string.app_language_description, R.string.advanced_system, Settings.AdvancedSystem),
            SearchEntry(R.string.update_on_metered_connections, R.string.update_on_metered_connections_description, R.string.updates, Settings.Updates),
            SearchEntry(R.string.manual_update_check, R.string.manual_update_check_description, R.string.updates, Settings.Updates),
            SearchEntry(R.string.changelog, R.string.changelog_description, R.string.updates, Settings.Updates),
            SearchEntry(R.string.update_checking_manager, R.string.update_checking_manager_description, R.string.updates, Settings.Updates),
            SearchEntry(R.string.show_manager_update_dialog_on_launch, R.string.show_manager_update_dialog_on_launch_description, R.string.updates, Settings.Updates),
            SearchEntry(R.string.show_manager_update_changelog, R.string.show_manager_update_changelog_description, R.string.updates, Settings.Updates),
            SearchEntry(R.string.manager_prereleases, R.string.manager_prereleases_description, R.string.updates, Settings.Updates),
            SearchEntry(R.string.announcement_system_enabled, R.string.announcement_system_enabled_description, R.string.advanced_system, Settings.AdvancedSystem),
            SearchEntry(R.string.background_manager_update, R.string.background_manager_update_description, R.string.updates, Settings.Updates),
            SearchEntry(R.string.background_bundle_update, R.string.background_bundle_update_description, R.string.updates, Settings.Updates),
            SearchEntry(R.string.announcement_push_notifications, R.string.announcement_push_notifications_description, R.string.updates, Settings.Updates),
            SearchEntry(R.string.bundle_update_delivery_mode, R.string.bundle_update_delivery_mode_description, R.string.updates, Settings.Updates),
            SearchEntry(R.string.bundle_changelog_fetch_limit, R.string.bundle_changelog_fetch_limit_description, R.string.updates, Settings.Updates),
            SearchEntry(R.string.bundle_changelog_storage_limit, R.string.bundle_changelog_storage_limit_description, R.string.updates, Settings.Updates),
            SearchEntry(R.string.downloader_auto_save_title, R.string.downloader_auto_save_description, R.string.downloads, Settings.Downloads),
            SearchEntry(R.string.downloader_auto_save_latest_only_title, R.string.downloader_auto_save_latest_only_description, R.string.downloads, Settings.Downloads),
            SearchEntry(R.string.downloader_plugins, null, R.string.downloads, Settings.Downloads),
            SearchEntry(R.string.downloaded_apps, null, R.string.downloads, Settings.Downloads),
            SearchEntry(R.string.downloaded_apps_export, null, R.string.downloads, Settings.Downloads),
            SearchEntry(R.string.patcher_runtime_plugins, R.string.patcher_runtime_plugins_description, R.string.patcher_runtime_plugins, Settings.PatcherRuntimes),
            SearchEntry(R.string.patcher_runtime_import_url, R.string.patcher_runtime_import_url_hint, R.string.patcher_runtime_plugins, Settings.PatcherRuntimes),
            SearchEntry(R.string.patcher_runtime_managed_sources, null, R.string.patcher_runtime_plugins, Settings.PatcherRuntimes),
            SearchEntry(R.string.patcher_runtime_installed_plugins, null, R.string.patcher_runtime_plugins, Settings.PatcherRuntimes),
            SearchEntry(R.string.storage_cache_management, R.string.storage_cache_management_description, R.string.storage_cache_management, Settings.Storage),
            SearchEntry(R.string.storage_overview_section, null, R.string.storage_cache_management, Settings.Storage),
            SearchEntry(R.string.storage_total_app_storage, R.string.storage_total_app_storage_description, R.string.storage_cache_management, Settings.Storage),
            SearchEntry(R.string.storage_refresh_usage, R.string.storage_refresh_usage_description, R.string.storage_cache_management, Settings.Storage),
            SearchEntry(R.string.storage_usage_section, R.string.storage_usage_section_description, R.string.storage_cache_management, Settings.Storage),
            SearchEntry(R.string.storage_internal_cache, R.string.storage_internal_cache_description, R.string.storage_cache_management, Settings.Storage),
            SearchEntry(R.string.storage_code_cache, R.string.storage_code_cache_description, R.string.storage_cache_management, Settings.Storage),
            SearchEntry(R.string.storage_apk_signer_cache, R.string.storage_apk_signer_cache_description, R.string.storage_cache_management, Settings.Storage),
            SearchEntry(R.string.storage_internal_files, R.string.storage_internal_files_description, R.string.storage_cache_management, Settings.Storage),
            SearchEntry(R.string.storage_no_backup_files, R.string.storage_no_backup_files_description, R.string.storage_cache_management, Settings.Storage),
            SearchEntry(R.string.storage_downloaded_apps, R.string.storage_downloaded_apps_description, R.string.storage_cache_management, Settings.Storage),
            SearchEntry(R.string.storage_patch_bundles, R.string.storage_patch_bundles_description, R.string.storage_cache_management, Settings.Storage),
            SearchEntry(R.string.storage_signing_files, R.string.storage_signing_files_description, R.string.storage_cache_management, Settings.Storage),
            SearchEntry(R.string.storage_downloader_plugins, R.string.storage_downloader_plugins_description, R.string.storage_cache_management, Settings.Storage),
            SearchEntry(R.string.storage_patcher_runtime_plugins, R.string.storage_patcher_runtime_plugins_description, R.string.storage_cache_management, Settings.Storage),
            SearchEntry(R.string.storage_lsposed_modules, R.string.storage_lsposed_modules_description, R.string.storage_cache_management, Settings.Storage),
            SearchEntry(R.string.storage_patched_apps, R.string.storage_patched_apps_description, R.string.storage_cache_management, Settings.Storage),
            SearchEntry(R.string.storage_retained_originals, R.string.storage_retained_originals_description, R.string.storage_cache_management, Settings.Storage),
            SearchEntry(R.string.storage_patch_profile_inputs, R.string.storage_patch_profile_inputs_description, R.string.storage_cache_management, Settings.Storage),
            SearchEntry(R.string.storage_custom_backgrounds, R.string.storage_custom_backgrounds_description, R.string.storage_cache_management, Settings.Storage),
            SearchEntry(R.string.storage_temporary_workspace, R.string.storage_temporary_workspace_description, R.string.storage_cache_management, Settings.Storage),
            SearchEntry(R.string.storage_ui_temporary_workspace, R.string.storage_ui_temporary_workspace_description, R.string.storage_cache_management, Settings.Storage),
            SearchEntry(R.string.storage_shared_apks, R.string.storage_shared_apks_description, R.string.storage_cache_management, Settings.Storage),
            SearchEntry(R.string.storage_other_internal_data, R.string.storage_other_internal_data_description, R.string.storage_cache_management, Settings.Storage),
            SearchEntry(R.string.storage_external_cache, R.string.storage_external_cache_description, R.string.storage_cache_management, Settings.Storage),
            SearchEntry(R.string.storage_external_files, R.string.storage_external_files_description, R.string.storage_cache_management, Settings.Storage),
            SearchEntry(R.string.storage_cache_actions_section, null, R.string.storage_cache_management, Settings.Storage),
            SearchEntry(R.string.storage_clear_app_cache, R.string.storage_clear_app_cache_description, R.string.storage_cache_management, Settings.Storage),
            SearchEntry(R.string.storage_auto_clear_cache, R.string.storage_auto_clear_cache_description, R.string.storage_cache_management, Settings.Storage),
            SearchEntry(R.string.storage_open_app_storage_settings, R.string.storage_open_app_storage_settings_description, R.string.storage_cache_management, Settings.Storage),
            SearchEntry(R.string.import_keystore, R.string.import_keystore_description, R.string.import_export, Settings.ImportExport),
            SearchEntry(R.string.import_everything, R.string.import_everything_description, R.string.import_export, Settings.ImportExport),
            SearchEntry(R.string.import_patch_selection, R.string.import_patch_selection_description, R.string.import_export, Settings.ImportExport),
            SearchEntry(R.string.import_patch_bundles, R.string.import_patch_bundles_description, R.string.import_export, Settings.ImportExport),
            SearchEntry(R.string.import_patch_profiles, R.string.import_patch_profiles_description, R.string.import_export, Settings.ImportExport),
            SearchEntry(R.string.import_manager_settings, R.string.import_manager_settings_description, R.string.import_export, Settings.ImportExport),
            SearchEntry(R.string.keystore_diagnostics, R.string.keystore_diagnostics_description, R.string.import_export, Settings.ImportExport),
            SearchEntry(R.string.export_keystore, R.string.export_keystore_description, R.string.import_export, Settings.ImportExport),
            SearchEntry(R.string.export_everything, R.string.export_everything_description, R.string.import_export, Settings.ImportExport),
            SearchEntry(R.string.export_patch_selection, R.string.export_patch_selection_description, R.string.import_export, Settings.ImportExport),
            SearchEntry(R.string.export_patch_bundles, R.string.export_patch_bundles_description, R.string.import_export, Settings.ImportExport),
            SearchEntry(R.string.export_patch_profiles, R.string.export_patch_profiles_description, R.string.import_export, Settings.ImportExport),
            SearchEntry(R.string.export_manager_settings, R.string.export_manager_settings_description, R.string.import_export, Settings.ImportExport),
            SearchEntry(R.string.regenerate_keystore, R.string.regenerate_keystore_description, R.string.import_export, Settings.ImportExport),
            SearchEntry(R.string.reset_patch_selection, R.string.reset_patch_selection_description, R.string.import_export, Settings.ImportExport),
            SearchEntry(R.string.reset_patch_options, R.string.reset_patch_options_description, R.string.import_export, Settings.ImportExport),
            SearchEntry(R.string.api_url, R.string.api_url_description, R.string.advanced_system, Settings.AdvancedSystem),
            SearchEntry(R.string.github_pat, R.string.github_pat_description, R.string.advanced_system, Settings.AdvancedSystem),
            SearchEntry(R.string.include_github_pat_in_exports_label, R.string.include_github_pat_in_exports_supporting, R.string.advanced_system, Settings.AdvancedSystem),
            SearchEntry(R.string.disable_apk_signature_checks, R.string.disable_apk_signature_checks_description, R.string.installer_section, Settings.AdvancedSystem),
            SearchEntry(R.string.installer_choose_per_install_title, R.string.installer_choose_per_install_description, R.string.advanced_system, Settings.AdvancedSystem),
            SearchEntry(
                R.string.installer_primary_title,
                null,
                R.string.advanced_system,
                Settings.AdvancedSystem,
                keywords = listOf(
                    R.string.installer_shizuku_name,
                    R.string.installer_shizuku_description,
                    R.string.installer_shizuku_google_play_name,
                    R.string.installer_shizuku_google_play_description
                )
            ),
            SearchEntry(
                R.string.installer_fallback_title,
                null,
                R.string.advanced_system,
                Settings.AdvancedSystem,
                keywords = listOf(
                    R.string.installer_shizuku_name,
                    R.string.installer_shizuku_description,
                    R.string.installer_shizuku_google_play_name,
                    R.string.installer_shizuku_google_play_description
                )
            ),
            SearchEntry(R.string.installer_shizuku_configure_title, R.string.installer_shizuku_configure_description, R.string.advanced_system, Settings.AdvancedSystem),
            SearchEntry(R.string.installer_play_store_mode, R.string.installer_play_store_mode_description, R.string.advanced_system, Settings.AdvancedSystem),
            SearchEntry(R.string.settings_auto_install_with_shizuku, R.string.settings_auto_install_with_shizuku_description, R.string.advanced_system, Settings.AdvancedSystem),
            SearchEntry(R.string.settings_auto_uninstall_with_shizuku, R.string.settings_auto_uninstall_with_shizuku_description, R.string.advanced_system, Settings.AdvancedSystem),
            SearchEntry(R.string.installer_custom_manage_title, R.string.installer_custom_manage_description, R.string.advanced_system, Settings.AdvancedSystem),
            SearchEntry(R.string.auto_patch_enabled, R.string.auto_patch_enabled_description, R.string.advanced_system, Settings.AdvancedSystem),
            SearchEntry(R.string.auto_patch_install_with_shizuku, R.string.auto_patch_install_with_shizuku_description, R.string.advanced_system, Settings.AdvancedSystem),
            SearchEntry(R.string.auto_patch_shizuku_uninstall_on_conflict, R.string.auto_patch_shizuku_uninstall_on_conflict_description, R.string.advanced_system, Settings.AdvancedSystem),
            SearchEntry(R.string.auto_patch_requires_charging, R.string.auto_patch_requires_charging_description, R.string.advanced_system, Settings.AdvancedSystem),
            SearchEntry(R.string.auto_patch_interval, null, R.string.advanced_system, Settings.AdvancedSystem),
            SearchEntry(R.string.batch_patch_external_actions, R.string.batch_patch_external_actions_description, R.string.advanced_system, Settings.AdvancedSystem),
            SearchEntry(R.string.search_engine_host_title, R.string.search_engine_host_description, R.string.advanced_system, Settings.AdvancedSystem),
            SearchEntry(R.string.patch_compat_check, R.string.patch_compat_check_description, R.string.advanced_system, Settings.AdvancedSystem),
            SearchEntry(R.string.suggested_version_safeguard, R.string.suggested_version_safeguard_description, R.string.advanced_system, Settings.AdvancedSystem),
            SearchEntry(R.string.patch_selection_safeguard, R.string.patch_selection_safeguard_description, R.string.advanced_system, Settings.AdvancedSystem),
            SearchEntry(R.string.disable_patch_selection_confirmations, R.string.disable_patch_selection_confirmations_description, R.string.advanced_system, Settings.AdvancedSystem),
            SearchEntry(R.string.universal_patches_safeguard, R.string.universal_patches_safeguard_description, R.string.advanced_system, Settings.AdvancedSystem),
            SearchEntry(R.string.patch_availability_enabled, R.string.patch_availability_enabled_description, R.string.safeguards_compatibility_section, Settings.AdvancedSystem),
            SearchEntry(R.string.remove_gmscore_for_primary_mount, R.string.remove_gmscore_for_primary_mount_description, R.string.safeguards_compatibility_section, Settings.AdvancedSystem),
            SearchEntry(R.string.strip_unused_libs, R.string.strip_unused_libs_description, R.string.patcher_category, Settings.Patcher),
            SearchEntry(R.string.skip_unneeded_split_apks, R.string.skip_unneeded_split_apks_description, R.string.patcher_category, Settings.Patcher),
            SearchEntry(R.string.choose_split_apks_before_patching, R.string.choose_split_apks_before_patching_description, R.string.patcher_category, Settings.Patcher),
            SearchEntry(R.string.continue_on_patch_error, R.string.continue_on_patch_error_description, R.string.patcher_category, Settings.Patcher),
            SearchEntry(R.string.skip_apk_signing, R.string.skip_apk_signing_description, R.string.patcher_category, Settings.Patcher),
            SearchEntry(R.string.inject_signature_metadata_after_patching, R.string.inject_signature_metadata_after_patching_description, R.string.patcher_category, Settings.Patcher),
            SearchEntry(R.string.patcher_log_mode, R.string.patcher_log_mode_description, R.string.patcher_category, Settings.Patcher),
            SearchEntry(R.string.patcher_jvm_enable, R.string.patcher_jvm_description, R.string.patcher_category, Settings.Patcher),
            SearchEntry(
                R.string.process_memory_limit,
                R.string.art_memory_description,
                R.string.patcher_category,
                Settings.Patcher,
                keywords = listOf(R.string.art_memory_search_keywords)
            ),
            SearchEntry(R.string.morphe_bytecode_mode, R.string.morphe_bytecode_mode_description, R.string.patcher_category, Settings.Patcher),
            SearchEntry(R.string.patcher_memory_usage_graph_title, R.string.patcher_memory_usage_graph_description, R.string.patcher_category, Settings.Patcher),
            SearchEntry(R.string.patcher_resource_graphs_compact_title, R.string.patcher_resource_graphs_compact_description, R.string.patcher_category, Settings.Patcher),
            SearchEntry(
                R.string.patcher_resource_graph_options_title,
                R.string.resource_graph_extra_info_description,
                R.string.patcher_category,
                Settings.Patcher,
                keywords = listOf(R.string.resource_graph_extra_info_title)
            ),
            SearchEntry(
                R.string.patcher_compact_resource_graph_options_title,
                R.string.resource_graph_extra_info_description,
                R.string.patcher_category,
                Settings.Patcher,
                keywords = listOf(R.string.resource_graph_extra_info_title)
            ),
            SearchEntry(R.string.patcher_auto_collapse_steps, R.string.patcher_auto_collapse_steps_description, R.string.patcher_category, Settings.Patcher),
            SearchEntry(R.string.patcher_auto_expand_steps, R.string.patcher_auto_expand_steps_description, R.string.patcher_category, Settings.Patcher),
            SearchEntry(R.string.patcher_auto_expand_running_steps_exclusive, R.string.patcher_auto_expand_running_steps_exclusive_description, R.string.patcher_category, Settings.Patcher),
            SearchEntry(R.string.skip_split_merge_signing, R.string.skip_split_merge_signing_description, R.string.merge_split_flow_section, Settings.Patcher),
            SearchEntry(R.string.merge_split_memory_usage_graph_title, R.string.merge_split_memory_usage_graph_description, R.string.merge_split_flow_section, Settings.Patcher),
            SearchEntry(R.string.merger_resource_graphs_compact_title, R.string.merger_resource_graphs_compact_description, R.string.merge_split_flow_section, Settings.Patcher),
            SearchEntry(
                R.string.merger_resource_graph_options_title,
                R.string.resource_graph_extra_info_description,
                R.string.merge_split_flow_section,
                Settings.Patcher,
                keywords = listOf(R.string.resource_graph_extra_info_title)
            ),
            SearchEntry(
                R.string.merger_compact_resource_graph_options_title,
                R.string.resource_graph_extra_info_description,
                R.string.merge_split_flow_section,
                Settings.Patcher,
                keywords = listOf(R.string.resource_graph_extra_info_title)
            ),
            SearchEntry(R.string.merge_split_auto_collapse_steps, R.string.merge_split_auto_collapse_steps_description, R.string.merge_split_flow_section, Settings.Patcher),
            SearchEntry(R.string.merge_split_auto_expand_steps, R.string.merge_split_auto_expand_steps_description, R.string.merge_split_flow_section, Settings.Patcher),
            SearchEntry(R.string.merge_split_auto_expand_running_steps_exclusive, R.string.merge_split_auto_expand_running_steps_exclusive_description, R.string.merge_split_flow_section, Settings.Patcher),
            SearchEntry(R.string.patcher_saved_apps_title, R.string.patcher_saved_apps_description, R.string.patcher_category, Settings.Patcher),
            SearchEntry(R.string.saved_apps_disable_overwrite_title, R.string.saved_apps_disable_overwrite_description, R.string.patcher_category, Settings.Patcher),
            SearchEntry(R.string.saved_apps_show_bundle_update_badges_title, R.string.saved_apps_show_bundle_update_badges_description, R.string.patcher_category, Settings.Patcher),
            SearchEntry(R.string.use_custom_file_picker_title, R.string.use_custom_file_picker_description, R.string.advanced_system, Settings.AdvancedSystem),
            SearchEntry(R.string.show_patch_selection_summary, R.string.show_patch_selection_summary_description, R.string.patcher_category, Settings.Patcher),
            SearchEntry(R.string.patch_selection_collapse_on_toggle, R.string.patch_selection_collapse_on_toggle_description, R.string.patcher_category, Settings.Patcher),
            SearchEntry(R.string.minimal_action_buttons_title, R.string.minimal_action_buttons_description, R.string.action_buttons_section, Settings.General),
            SearchEntry(R.string.dashboard_apps_actions, R.string.dashboard_actions_description, R.string.action_buttons_section, Settings.General),
            SearchEntry(R.string.dashboard_bundles_actions, R.string.dashboard_actions_description, R.string.action_buttons_section, Settings.General),
            SearchEntry(R.string.patch_selection_action_order_title, R.string.patch_selection_action_order_description, R.string.action_buttons_section, Settings.General),
            SearchEntry(R.string.patch_selection_action_visibility_title, R.string.patch_selection_action_visibility_description, R.string.action_buttons_section, Settings.General),
            SearchEntry(R.string.minimal_patch_selection_view_title, R.string.minimal_patch_selection_view_description, R.string.patch_list_display_section, Settings.Patcher),
            SearchEntry(R.string.patch_selection_version_tags_title, R.string.patch_selection_version_tags_description, R.string.patch_list_display_section, Settings.Patcher),
            SearchEntry(R.string.patch_selection_option_previews_title, R.string.patch_selection_option_previews_description, R.string.patch_list_display_section, Settings.Patcher),
            SearchEntry(R.string.patch_bundle_action_order_title, R.string.patch_bundle_action_order_description, R.string.action_buttons_section, Settings.General),
            SearchEntry(R.string.patch_bundle_action_visibility_title, R.string.patch_bundle_action_visibility_description, R.string.action_buttons_section, Settings.General),
            SearchEntry(R.string.batch_result_action_order_title, R.string.batch_result_action_order_description, R.string.action_buttons_section, Settings.General),
            SearchEntry(R.string.batch_result_action_visibility_title, R.string.batch_result_action_visibility_description, R.string.action_buttons_section, Settings.General),
            SearchEntry(R.string.saved_app_action_order_title, R.string.saved_app_action_order_description, R.string.action_buttons_section, Settings.General),
            SearchEntry(R.string.saved_app_action_visibility_title, R.string.saved_app_action_visibility_description, R.string.action_buttons_section, Settings.General),
            SearchEntry(R.string.patch_profile_action_order_title, R.string.patch_profile_action_order_description, R.string.action_buttons_section, Settings.General),
            SearchEntry(R.string.patch_profile_action_visibility_title, R.string.patch_profile_action_visibility_description, R.string.action_buttons_section, Settings.General),
            SearchEntry(R.string.lsposed_module_action_order_title, R.string.lsposed_module_action_order_description, R.string.action_buttons_section, Settings.General),
            SearchEntry(R.string.lsposed_module_action_visibility_title, R.string.lsposed_module_action_visibility_description, R.string.action_buttons_section, Settings.General),
            SearchEntry(R.string.export_name_format, R.string.export_name_format_description, R.string.advanced_system, Settings.AdvancedSystem),
            SearchEntry(R.string.merged_apk_name_format, R.string.merged_apk_name_format_description, R.string.advanced_system, Settings.AdvancedSystem),
            SearchEntry(R.string.debug_logs_export, null, R.string.advanced_system, Settings.AdvancedSystem),
            SearchEntry(R.string.about_device, null, R.string.advanced_system, Settings.AdvancedSystem),
            SearchEntry(R.string.battery_optimization_banner_title, R.string.battery_optimization_banner_description, R.string.developer_options, Settings.Developer),
            SearchEntry(R.string.patches_force_download, null, R.string.developer_options, Settings.Developer),
            SearchEntry(R.string.reset_patch_bundles, R.string.reset_patch_bundles_description, R.string.developer_options, Settings.Developer),
            SearchEntry(R.string.patch_profile_bundle_override_title, R.string.patch_profile_bundle_override_description, R.string.developer_options, Settings.Developer),
            SearchEntry(R.string.about_revanced_manager, null, R.string.about, Settings.About),
            SearchEntry(R.string.about_donate, R.string.about_donate_description, R.string.about, Settings.About),
            SearchEntry(R.string.github, null, R.string.about, Settings.About),
            SearchEntry(R.string.patch_bundle_urls, null, R.string.about, Settings.About),
            SearchEntry(R.string.credits, null, R.string.about, Settings.About),
            SearchEntry(R.string.revanced_manager_credit, R.string.revanced_manager_credit_subtext, R.string.about, Settings.About),
            SearchEntry(R.string.morphe_manager_credit, R.string.morphe_manager_credit_prefix, R.string.about, Settings.About),
            SearchEntry(R.string.licensing, null, R.string.about, Settings.About),
            SearchEntry(R.string.notice, R.string.notice_description, R.string.about, Settings.About),
            SearchEntry(R.string.open_source_licenses, R.string.open_source_licenses_description, R.string.about, Settings.About),
        )
    }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val normalizedQuery = searchQuery.trim().lowercase()
    val filteredSections = settingsSections
    val filteredEntries = if (normalizedQuery.isBlank()) {
        emptyList()
    } else {
        settingsEntries.filter { entry ->
            val searchText = buildString {
                append(stringResource(entry.title))
                entry.description?.let { description ->
                    append(' ')
                    append(stringResource(description))
                }
                entry.keywords.forEach { keyword ->
                    append(' ')
                    append(stringResource(keyword))
                }
            }.lowercase()
            searchText.contains(normalizedQuery)
        }
    }
    val aboutSection = filteredSections.firstOrNull { it.destination == Settings.About }
    val mainSections = filteredSections.filterNot { it.destination == Settings.About }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.settings),
                onBackClick = onBackClick,
            )
        }
    ) { paddingValues ->
        val searchCard: @Composable () -> Unit = {
            ExpressiveSettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource(R.string.settings_search_hint)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Outlined.Close, null)
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent
                    )
                )
            }
        }
        val mainSectionsCard: @Composable () -> Unit = {
            ExpressiveSettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                mainSections.forEachIndexed { index, (name, description, icon, destination) ->
                    ExpressiveSettingsItem(
                        headlineContent = stringResource(name),
                        supportingContent = stringResource(description),
                        leadingContent = { Icon(icon, null) },
                        onClick = { navigate(destination) }
                    )
                    if (index != mainSections.lastIndex) {
                        ExpressiveSettingsDivider()
                    }
                }
            }
        }
        val aboutCard: (@Composable () -> Unit)? = aboutSection?.let { destination ->
            {
                ExpressiveSettingsCard(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    ExpressiveSettingsItem(
                        headlineContent = stringResource(R.string.about_revanced_manager),
                        supportingContent = "v${BuildConfig.VERSION_NAME.removePrefix("v").removePrefix("V")}",
                        leadingContent = {
                            AppIcon(
                                packageInfo = null,
                                contentDescription = stringResource(R.string.app_name),
                                modifier = Modifier.size(32.dp),
                                iconOverride = appIcon
                            )
                        },
                        onClick = { navigate(destination.destination) }
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            searchCard()
            if (normalizedQuery.isNotBlank()) {
                if (filteredEntries.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.settings_search_no_results),
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                    }
                } else {
                    ColumnWithScrollbar(
                        thumbLength = app.universal.patch.manager.ui.component.DASHBOARD_SCROLLBAR_THUMB_LENGTH,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        val highlightStyle = SpanStyle(
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        ExpressiveSettingsCard(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        ) {
                            filteredEntries.forEachIndexed { index, entry ->
                                val titleText = stringResource(entry.title)
                                val descriptionText = entry.description?.let { stringResource(it) }
                                val categoryText = stringResource(entry.category)
                                ExpressiveSettingsItem(
                                    headlineContent = {
                                        Column {
                                            Text(
                                                text = buildHighlightedText(categoryText, normalizedQuery, highlightStyle),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = buildHighlightedText(titleText, normalizedQuery, highlightStyle),
                                                style = MaterialTheme.typography.titleMedium
                                            )
                                        }
                                    },
                                    supportingContentSlot = {
                                        Column {
                                            descriptionText?.let { description ->
                                                androidx.compose.material3.Text(
                                                    text = buildHighlightedText(description, normalizedQuery, highlightStyle),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    },
                                    leadingContent = {
                                        Icon(
                                            sectionIconMap[entry.destination] ?: Icons.Outlined.Tune,
                                            null
                                        )
                                    },
                                    onClick = {
                                        SettingsSearchState.setTarget(
                                            SettingsSearchTarget(entry.destination, entry.title)
                                        )
                                        navigate(entry.destination)
                                    }
                                )
                                if (index != filteredEntries.lastIndex) {
                                    ExpressiveSettingsDivider()
                                }
                            }
                        }
                    }
                }
            } else {
                SettingsOverviewLayout(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    mainContent = mainSectionsCard,
                    aboutContent = aboutCard
                )
            }
        }
    }
}

private enum class SettingsOverviewSlot {
    Main,
    About,
    Scroll
}

@Composable
private fun SettingsOverviewLayout(
    modifier: Modifier = Modifier,
    mainContent: @Composable () -> Unit,
    aboutContent: (@Composable () -> Unit)?,
    minAboutGap: Dp = 24.dp
) {
    val scrollState = rememberScrollState()
    SubcomposeLayout(modifier = modifier) { constraints ->
        val relaxedConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val mainPlaceable = subcompose(SettingsOverviewSlot.Main, mainContent)
            .single()
            .measure(relaxedConstraints)
        val aboutPlaceable = aboutContent?.let {
            subcompose(SettingsOverviewSlot.About, it)
                .single()
                .measure(relaxedConstraints)
        }
        val requiredHeight = mainPlaceable.height +
            (aboutPlaceable?.let { minAboutGap.roundToPx() + it.height } ?: 0)

        if (requiredHeight > constraints.maxHeight) {
            val scrollPlaceable = subcompose(SettingsOverviewSlot.Scroll) {
                ColumnWithScrollbar(
                    thumbLength = app.universal.patch.manager.ui.component.DASHBOARD_SCROLLBAR_THUMB_LENGTH,
                    modifier = Modifier.fillMaxSize(),
                    state = scrollState
                ) {
                    mainContent()
                    aboutContent?.let {
                        Spacer(modifier = Modifier.height(minAboutGap))
                        it()
                    }
                }
            }.single().measure(constraints)

            layout(constraints.maxWidth, constraints.maxHeight) {
                scrollPlaceable.place(0, 0)
            }
        } else {
            layout(constraints.maxWidth, constraints.maxHeight) {
                mainPlaceable.place(0, 0)
                aboutPlaceable?.place(0, constraints.maxHeight - aboutPlaceable.height)
            }
        }
    }
}

private fun buildHighlightedText(
    text: String,
    query: String,
    highlightStyle: SpanStyle
): AnnotatedString {
    if (query.isBlank()) {
        return AnnotatedString(text)
    }
    val lowerText = text.lowercase()
    val lowerQuery = query.lowercase()
    var searchIndex = 0
    return buildAnnotatedString {
        while (searchIndex < text.length) {
            val matchIndex = lowerText.indexOf(lowerQuery, searchIndex)
            if (matchIndex == -1) {
                append(text.substring(searchIndex))
                break
            }
            if (matchIndex > searchIndex) {
                append(text.substring(searchIndex, matchIndex))
            }
            withStyle(highlightStyle) {
                append(text.substring(matchIndex, matchIndex + lowerQuery.length))
            }
            searchIndex = matchIndex + lowerQuery.length
        }
    }
}
