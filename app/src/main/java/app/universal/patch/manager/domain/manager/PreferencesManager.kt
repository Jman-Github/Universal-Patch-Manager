package app.universal.patch.manager.domain.manager

import android.content.ComponentName
import android.content.Context
import android.util.Log
import app.universal.patch.manager.R
import app.universal.patch.manager.domain.manager.base.BasePreferencesManager
import app.universal.patch.manager.domain.manager.base.EditorContext
import app.universal.patch.manager.patcher.logger.PatcherLogMode
import app.universal.patch.manager.patcher.runtime.morphe.MorpheBytecodeMode
import app.universal.patch.manager.ui.theme.Theme
import app.universal.patch.manager.util.ExportNameFormatter
import app.universal.patch.manager.util.isDebuggable
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.nio.file.Paths
import kotlin.io.path.isDirectory
import kotlin.io.path.isReadable

import app.universal.patch.manager.ui.model.PatchSelectionActionKey
import app.universal.patch.manager.ui.model.PatchBundleActionKey
import app.universal.patch.manager.ui.model.BatchResultActionKey
import app.universal.patch.manager.ui.model.SavedAppActionKey
import app.universal.patch.manager.ui.model.PatchProfileActionKey
import app.universal.patch.manager.ui.model.LsposedModuleActionKey

enum class SearchForUpdatesBackgroundInterval(val displayName: Int, val value: Long) {
    NEVER(R.string.never, 0),
    MIN15(R.string.minutes_15, 15),
    HOUR(R.string.hourly, 60),
    DAY(R.string.daily, 60 * 24)
}

internal fun normalizedImportedAutoPatchInterval(
    interval: SearchForUpdatesBackgroundInterval
): SearchForUpdatesBackgroundInterval =
    if (interval == SearchForUpdatesBackgroundInterval.NEVER) {
        SearchForUpdatesBackgroundInterval.DAY
    } else {
        interval
    }

enum class AutoClearCacheInterval(val displayName: Int, val value: Long) {
    NEVER(R.string.never, 0),
    HOUR(R.string.hourly, 60),
    DAY(R.string.daily, 60 * 24),
    WEEK(R.string.weekly, 60 * 24 * 7)
}

enum class BundleUpdateDeliveryMode(val displayName: Int) {
    AUTO(R.string.bundle_update_delivery_mode_auto),
    WEBSOCKET_PREFERRED(R.string.bundle_update_delivery_mode_websocket_preferred),
    POLLING_ONLY(R.string.bundle_update_delivery_mode_polling_only)
}

class PreferencesManager(
    context: Context
) : BasePreferencesManager(context) {
    companion object {
        private val MANAGER_PRERELEASE_VERSION_REGEX = Regex("""\d+\.\d+\.\d+-.+""")
        private fun isManagerPrereleaseVersion(versionName: String): Boolean =
            MANAGER_PRERELEASE_VERSION_REGEX.matches(
                versionName.removePrefix("v").substringBefore('+')
            )
        private val PATCH_ACTION_ORDER_DEFAULT =
            PatchSelectionActionKey.DefaultOrder.joinToString(",") { it.storageId }
        private val PATCH_BUNDLE_ACTION_ORDER_DEFAULT =
            PatchBundleActionKey.DefaultOrder.joinToString(",") { it.storageId }
        private val BATCH_RESULT_ACTION_ORDER_DEFAULT =
            BatchResultActionKey.DefaultOrder.joinToString(",") { it.storageId }
        private val SAVED_APP_ACTION_ORDER_DEFAULT =
            SavedAppActionKey.DefaultOrder.joinToString(",") { it.storageId }
        private val PATCH_PROFILE_ACTION_ORDER_DEFAULT =
            PatchProfileActionKey.DefaultOrder.joinToString(",") { it.storageId }
        private val LSPOSED_MODULE_ACTION_ORDER_DEFAULT =
            LsposedModuleActionKey.DefaultOrder.joinToString(",") { it.storageId }
        val DEFAULT_ANNOUNCEMENT_TAGS: Set<String> = emptySet()
        const val MIN_BUNDLE_CHANGELOG_HISTORY_LIMIT = 1
        const val DEFAULT_BUNDLE_CHANGELOG_FETCH_LIMIT = 20
        const val DEFAULT_BUNDLE_CHANGELOG_STORAGE_LIMIT = 40
    }
    val dynamicColor = booleanPreference("dynamic_color", false)
    val pureBlackTheme = booleanPreference("pure_black_theme", false)
    val materialYouPureBlackTheme = booleanPreference("material_you_pure_black_theme", false)
    val pureBlackOnSystemDark = booleanPreference("pure_black_on_system_dark", false)
    val themePresetSelectionEnabled = booleanPreference("theme_preset_selection_enabled", true)
    val themePresetSelectionName = stringPreference("theme_preset_selection_name", "DEFAULT")
    val customAccentColor = stringPreference("custom_accent_color", "")
    val customThemeColor = stringPreference("custom_theme_color", "")
    val customBackgroundImageUri = stringPreference("custom_background_image_uri", "")
    val customBackgroundImageOpacity = floatPreference("custom_background_image_opacity", 0.65f)
    val hideMainTabLabels = booleanPreference("hide_main_tab_labels", false)
    val showAppsTabUpdateCount = booleanPreference("show_apps_tab_update_count", true)
    val showBundlesTabUpdateCount = booleanPreference("show_bundles_tab_update_count", true)
    val disableMainTabSwipe = booleanPreference("disable_main_tab_swipe", false)
    val disablePatchSelectionTabSwipe = booleanPreference("disable_patch_selection_tab_swipe", false)
    val preventAccidentalTouching = booleanPreference("prevent_accidental_touching", true)
    val showPatchProfilesTab = booleanPreference("show_patch_profiles_tab", true)
    val showToolsTab = booleanPreference("show_tools_tab", true)
    val showLsposedTab = booleanPreference("show_lsposed_tab", false)
    val appSelectorSortMode = stringPreference("app_selector_sort_mode", "NAME_ASC")
    val batchQueueAppSelectorSortMode = stringPreference("batch_queue_app_selector_sort_mode", "NAME_ASC")
    val theme = enumPreference("theme", Theme.SYSTEM)
    val appLanguage = stringPreference("app_language", "system")

    val api = stringPreference("api_url", "https://api.revanced.app")
    // PR #35: https://github.com/Jman-Github/Universal-Patch-Manager/pull/35
    val gitHubPat = stringPreference("github_pat", "")
    val includeGitHubPatInExports = booleanPreference("include_github_pat_in_exports", false)

    val stripUnusedNativeLibs = booleanPreference("strip_unused_native_libs", false)
    val skipUnneededSplitApks = booleanPreference("skip_unneeded_split_apks", false)
    val chooseSplitApksBeforePatching = booleanPreference("choose_split_apks_before_patching", false)
    val continueOnPatchError = booleanPreference("continue_on_patch_error", false)
    val skipApkSigning = booleanPreference("skip_apk_signing", false)
    val skipSplitMergeSigning = booleanPreference("skip_split_merge_signing", false)
    val disableApkSignatureChecks = booleanPreference("disable_apk_signature_checks", false)
    val injectSignatureMetadataAfterPatching = booleanPreference("inject_signature_metadata_after_patching", false)
    val artMemoryLimit = intPreference("patcher_art_memory_mb", 1024)
    val useJvmRuntime = booleanPreference("patcher_jvm", true)
    val morpheBytecodeMode = enumPreference("morphe_bytecode_mode", MorpheBytecodeMode.FAST)
    val patcherLogMode = enumPreference("patcher_log_mode", PatcherLogMode.DEFAULT)
    val patchAvailabilityEnabled = booleanPreference("patch_availability_enabled", true)
    val removeGmsCoreForPrimaryMount =
        booleanPreference("remove_gmscore_for_primary_mount", true)

    // Code adapted from Morphe, see third-party/NOTICE for more information
    // https://github.com/MorpheApp/morphe-manager/blob/a2c3d31bd7ab42e6bc4b9dd528ed856fc72fb948/app/src/main/java/app/morphe/manager/domain/manager/PreferencesManager.kt
    val patchedAppExportFormat = stringPreference(
        "patched_app_export_format",
        ExportNameFormatter.DEFAULT_TEMPLATE
    )
    val mergedApkExportFormat = stringPreference(
        "merged_apk_export_format",
        ExportNameFormatter.DEFAULT_MERGED_APK_TEMPLATE
    )
    val patchBundleCacheVersionCode = intPreference("patch_bundle_cache_version_code", -1)
    val patchBundleImportAutoUpdate = booleanPreference("patch_bundle_import_auto_update", true)
    val patchBundleImportSearchUpdate = booleanPreference("patch_bundle_import_search_update", true)
    val dashboardBundlesFabCollapsed = booleanPreference("dashboard_bundles_fab_collapsed", false)
    val dashboardAppsFabCollapsed = booleanPreference("dashboard_apps_fab_collapsed", false)
    val dashboardLsposedFabCollapsed = booleanPreference("dashboard_lsposed_fab_collapsed", false)
    val rootMountToolsCollapsed = booleanPreference("root_mount_tools_collapsed", false)
    private val dashboardProgressBannerCollapsed =
        booleanPreference("dashboard_progress_banner_collapsed", false)
    private val dashboardBundleBannerStateMigrated =
        booleanPreference("dashboard_bundle_banner_state_migrated", false)
    val dashboardBundleImportBannerCollapsed =
        booleanPreference("dashboard_bundle_import_banner_collapsed", false)
    val dashboardBundleUpdateBannerCollapsed =
        booleanPreference("dashboard_bundle_update_banner_collapsed", false)
    val autoCollapsePatcherSteps = booleanPreference("auto_collapse_patcher_steps", false)
    val showPatcherMemoryUsageGraph = booleanPreference("show_patcher_memory_usage_graph", true)
    val compactPatcherResourceGraphs = booleanPreference("compact_patcher_resource_graphs", false)
    val showPatcherResourceGraphExtraInfo = booleanPreference("show_patcher_resource_graph_extra_info", false)
    val showCompactPatcherResourceGraphExtraInfo = booleanPreference("show_compact_patcher_resource_graph_extra_info", false)
    val patcherInformationExpanded = booleanPreference("patcher_information_expanded", true)
    val autoExpandRunningSteps = booleanPreference("auto_expand_running_steps", true)
    val autoExpandRunningStepsExclusive = booleanPreference("auto_expand_running_steps_exclusive", false)
    val enableSavedApps = booleanPreference("enable_saved_apps", true)
    val disableSavedAppOverwrite = booleanPreference("disable_saved_app_overwrite", false)
    val showSavedAppBundleUpdateBadges =
        booleanPreference("show_saved_app_bundle_update_badges", true)

    val allowMeteredUpdates = booleanPreference("allow_metered_updates", true)
    val chooseInstallerPerInstall = booleanPreference("choose_installer_per_install", false)
    val installerPrimary = stringPreference("installer_primary", InstallerPreferenceTokens.INTERNAL)
    val installerFallback = stringPreference("installer_fallback", InstallerPreferenceTokens.NONE)
    val installerCustomComponents = stringSetPreference("installer_custom_components", emptySet())
    val installerHiddenComponents = stringSetPreference("installer_hidden_components", emptySet())
    val shizukuInstallAsPlayStore =
        booleanPreference("shizuku_install_as_play_store", false)
    val autoInstallWithShizuku = booleanPreference("auto_patch_install", false)
    val autoUninstallWithShizuku = booleanPreference("auto_uninstall_with_shizuku", false)
    // Code adapted from Morphe, see third-party/NOTICE for more information
    // https://github.com/MorpheApp/morphe-manager/pull/795
    val autoPatchEnabled = booleanPreference("auto_patch_enabled", false)
    val autoPatchInstallWithShizuku =
        booleanPreference("auto_patch_install_with_shizuku", false)
    val autoPatchUninstallOnConflictWithShizuku =
        booleanPreference("auto_patch_uninstall_on_conflict_with_shizuku", false)
    private val autoPatchShizukuSettingsRestorePending =
        booleanPreference("auto_patch_shizuku_settings_restore_pending", false)
    private val autoPatchInstallWithShizukuBeforeDisable =
        booleanPreference("auto_patch_install_with_shizuku_before_disable", false)
    private val autoPatchUninstallOnConflictWithShizukuBeforeDisable =
        booleanPreference("auto_patch_uninstall_on_conflict_with_shizuku_before_disable", false)
    val autoPatchRequiresCharging = booleanPreference("auto_patch_requires_charging", true)
    val autoPatchInterval = enumPreference("auto_patch_interval", SearchForUpdatesBackgroundInterval.DAY)
    val autoPatchEnabledPackages = stringSetPreference(
        "auto_patch_enabled_packages",
        emptySet()
    )
    val savedAppLauncherShortcutPackages = stringSetPreference(
        "saved_app_launcher_shortcut_packages",
        emptySet()
    )
    val allowExternalBatchActions = booleanPreference("allow_external_batch_actions", false)
    val lastBatchPatchResult = stringPreference("last_batch_patch_result", "")
    val lastAutoPatchResult = stringPreference("last_auto_patch_result", "")

    val keystoreAlias = stringPreference("keystore_alias", KeystoreManager.DEFAULT_ALIAS)
    val keystorePass = stringPreference("keystore_pass", KeystoreManager.DEFAULT_PASSWORD)
    val keystoreKeyPass = stringPreference("keystore_key_pass", KeystoreManager.DEFAULT_KEY_PASSWORD)

    suspend fun updateAutoPatchEnabled(enabled: Boolean) {
        edit {
            applyAutoPatchEnabledState(enabled)
        }
    }

    suspend fun setSavedAppLauncherShortcutEnabled(
        packageName: String,
        enabled: Boolean,
        capacity: Int
    ) {
        edit {
            val selectedPackages = savedAppLauncherShortcutPackages.value
            savedAppLauncherShortcutPackages.value = when {
                enabled && packageName in selectedPackages -> selectedPackages
                enabled && selectedPackages.size < capacity -> selectedPackages + packageName
                enabled -> selectedPackages
                else -> selectedPackages - packageName
            }
        }
    }

    suspend fun restoreAutoPatchShizukuSettings(
        installWithShizuku: Boolean,
        uninstallOnConflictWithShizuku: Boolean
    ) {
        edit {
            if (autoPatchEnabled.value) {
                autoPatchInstallWithShizuku.value = installWithShizuku
                autoPatchUninstallOnConflictWithShizuku.value =
                    uninstallOnConflictWithShizuku
            } else {
                autoPatchInstallWithShizukuBeforeDisable.value = installWithShizuku
                autoPatchUninstallOnConflictWithShizukuBeforeDisable.value =
                    uninstallOnConflictWithShizuku
                autoPatchShizukuSettingsRestorePending.value = true
                autoPatchInstallWithShizuku.value = false
                autoPatchUninstallOnConflictWithShizuku.value = false
            }
        }
    }

    private fun EditorContext.applyAutoPatchEnabledState(enabled: Boolean) {
        if (enabled) {
            autoPatchEnabled.value = true
            if (!autoPatchShizukuSettingsRestorePending.value) return

            autoPatchInstallWithShizuku.value =
                autoPatchInstallWithShizukuBeforeDisable.value
            autoPatchUninstallOnConflictWithShizuku.value =
                autoPatchUninstallOnConflictWithShizukuBeforeDisable.value
            autoPatchShizukuSettingsRestorePending.value = false
            return
        }

        if (!autoPatchShizukuSettingsRestorePending.value) {
            autoPatchInstallWithShizukuBeforeDisable.value =
                autoPatchInstallWithShizuku.value
            autoPatchUninstallOnConflictWithShizukuBeforeDisable.value =
                autoPatchUninstallOnConflictWithShizuku.value
            autoPatchShizukuSettingsRestorePending.value = true
        }
        autoPatchInstallWithShizuku.value = false
        autoPatchUninstallOnConflictWithShizuku.value = false
        autoPatchEnabled.value = false
    }

    suspend fun migrateDashboardBundleBannerState() {
        if (dashboardBundleBannerStateMigrated.get()) return
        edit {
            val legacyState = dashboardProgressBannerCollapsed.value
            dashboardBundleImportBannerCollapsed.value = legacyState
            dashboardBundleUpdateBannerCollapsed.value = legacyState
            dashboardBundleBannerStateMigrated.value = true
        }
    }

    suspend fun migrateSplitModuleSortModeSeparation() {
        if (splitModuleSortModeSeparationMigrated.get()) return
        edit {
            patcherSplitModuleSortMode.value = splitMergeModuleSortMode.value
            splitModuleSortModeSeparationMigrated.value = true
        }
    }

    suspend fun migrateLegacyShizukuPlayStoreMode() {
        val primary = installerPrimary.get()
        val fallback = installerFallback.get()
        if (
            primary != InstallerPreferenceTokens.SHIZUKU_GOOGLE_PLAY &&
            fallback != InstallerPreferenceTokens.SHIZUKU_GOOGLE_PLAY
        ) return

        edit {
            if (installerPrimary.value == InstallerPreferenceTokens.SHIZUKU_GOOGLE_PLAY) {
                installerPrimary.value = InstallerPreferenceTokens.SHIZUKU
            }
            if (installerFallback.value == InstallerPreferenceTokens.SHIZUKU_GOOGLE_PLAY) {
                installerFallback.value = InstallerPreferenceTokens.SHIZUKU
            }
            shizukuInstallAsPlayStore.value = true
        }
    }

    val firstLaunch = booleanPreference("first_launch", true)
    val managerAutoUpdates = booleanPreference("manager_auto_updates", false)
    val showManagerUpdateDialogOnLaunch = booleanPreference("show_manager_update_dialog_on_launch", true)
    val showManagerUpdateChangelog = booleanPreference("show_manager_update_changelog", true)
    val announcementSystemEnabled = booleanPreference("announcement_system_enabled", false)
    private val announcementPushNotificationsLegacy =
        booleanPreference("announcement_push_notifications", false)
    private val announcementPushNotificationIntervalMigrated =
        booleanPreference("announcement_push_notifications_interval_migrated", false)
    val announcementPushNotificationInterval = enumPreference(
        "announcement_push_notifications_interval",
        SearchForUpdatesBackgroundInterval.NEVER
    )
    val autoClearCacheInterval = enumPreference(
        "auto_clear_cache_interval",
        AutoClearCacheInterval.NEVER
    )
    val viewedManagerUpdateVersion = stringPreference("viewed_manager_update_version", "")
    val readAnnouncements = stringSetPreference("read_announcements", emptySet())
    val notifiedAnnouncements = stringSetPreference("notified_announcements", emptySet())
    val selectedAnnouncementTags =
        stringSetPreference("selected_announcement_tags", DEFAULT_ANNOUNCEMENT_TAGS)
    val useManagerPrereleases = booleanPreference("manager_prereleases", false)
    private val managerPrereleaseAutoEnabledVersion =
        stringPreference("manager_prerelease_auto_enabled_version", "")
    // Read only while migrating a previously installed API source.
    internal val legacyOfficialBundleRemoved = booleanPreference("official_bundle_removed", false)
    internal val legacyPatchesPrereleases = booleanPreference("patches_prereleases", false)
    val showBatteryOptimizationBanner = booleanPreference("show_battery_optimization_banner", true)
    val allowPatchProfileBundleOverride = booleanPreference(
        "allow_patch_profile_bundle_override",
        false
    )
    val searchForUpdatesBackgroundInterval = enumPreference(
        "background_bundle_update_time",
        SearchForUpdatesBackgroundInterval.NEVER
    )
    val searchForManagerUpdatesBackgroundInterval = enumPreference(
        "background_manager_update_time",
        SearchForUpdatesBackgroundInterval.NEVER
    )
    val bundleUpdateDeliveryMode = enumPreference(
        "bundle_update_delivery_mode",
        BundleUpdateDeliveryMode.AUTO
    )
    val bundleChangelogFetchLimit = intPreference(
        "bundle_changelog_fetch_limit",
        DEFAULT_BUNDLE_CHANGELOG_FETCH_LIMIT
    )
    val bundleChangelogStorageLimit = intPreference(
        "bundle_changelog_storage_limit",
        DEFAULT_BUNDLE_CHANGELOG_STORAGE_LIMIT
    )
    val pendingManagerUpdateVersionCode = intPreference("pending_manager_update_version_code", -1)

    val disablePatchVersionCompatCheck = booleanPreference("disable_patch_version_compatibility_check", false)
    val disableSelectionWarning = booleanPreference("disable_selection_warning", false)
    val disableUniversalPatchCheck = booleanPreference("disable_patch_universal_check", true)
    val suggestedVersionSafeguard = booleanPreference("suggested_version_safeguard", true)
    val disablePatchSelectionConfirmations = booleanPreference("disable_patch_selection_confirmations", false)
    val showPatchSelectionSummary = booleanPreference("show_patch_selection_summary", true)
    val collapsePatchActionsOnSelection = booleanPreference("collapse_patch_actions_on_selection", true)
    val patchSelectionFilterFlags = intPreference("patch_selection_filter_flags", -1)
    val patchSelectionShowNewPatches = booleanPreference("patch_selection_show_new_patches", true)
    val patchSelectionSortAlphabetical = booleanPreference("patch_selection_sort_alphabetical", false)
    val patchSelectionSortDescending = booleanPreference("patch_selection_sort_descending", false)
    val patchSelectionSortSettingsMode = stringPreference("patch_selection_sort_settings_mode", "None")
    val patchSelectionSortSelectionMode = stringPreference("patch_selection_sort_selection_mode", "None")
    val patchSelectionActionOrder =
        stringPreference("patch_selection_action_order", PATCH_ACTION_ORDER_DEFAULT)
    val patchBundleActionOrder =
        stringPreference("patch_bundle_action_order", PATCH_BUNDLE_ACTION_ORDER_DEFAULT)
    val patchBundleHiddenActions =
        stringSetPreference("patch_bundle_hidden_actions", emptySet())
    val batchResultActionOrder =
        stringPreference("batch_result_action_order", BATCH_RESULT_ACTION_ORDER_DEFAULT)
    val batchResultHiddenActions =
        stringSetPreference("batch_result_hidden_actions", emptySet())
    val savedAppActionOrder =
        stringPreference("saved_app_action_order", SAVED_APP_ACTION_ORDER_DEFAULT)
    val savedAppHiddenActions =
        stringSetPreference("saved_app_hidden_actions", emptySet())
    val patchProfileActionOrder =
        stringPreference("patch_profile_action_order", PATCH_PROFILE_ACTION_ORDER_DEFAULT)
    val patchProfileHiddenActions =
        stringSetPreference("patch_profile_hidden_actions", emptySet())
    val lsposedModuleActionOrder =
        stringPreference("lsposed_module_action_order", LSPOSED_MODULE_ACTION_ORDER_DEFAULT)
    val lsposedModuleHiddenActions =
        stringSetPreference("lsposed_module_hidden_actions", emptySet())
    val patchSelectionHiddenActions =
        stringSetPreference("patch_selection_hidden_actions", emptySet())
    val patchSelectionShowVersionTags = booleanPreference("patch_selection_show_version_tags", true)
    val patchSelectionShowOptionPreviews =
        booleanPreference("patch_selection_show_option_previews", true)

    private val patchSelectionDisplayRestoreFlags = intPreference("patch_selection_display_restore_flags", 0)

    suspend fun setMinimalPatchSelectionView(enabled: Boolean) = edit {
        if (enabled) {
            val flags = (if (patchSelectionShowVersionTags.value) 1 else 0) or
                (if (patchSelectionShowOptionPreviews.value) 2 else 0)
            if (flags != 0) patchSelectionDisplayRestoreFlags.value = flags
            patchSelectionShowVersionTags.value = false
            patchSelectionShowOptionPreviews.value = false
        } else {
            // Zero means minimal view was reached by disabling both details separately.
            val flags = patchSelectionDisplayRestoreFlags.value.takeIf { it in 1..3 } ?: 3
            patchSelectionShowVersionTags.value = flags and 1 != 0
            patchSelectionShowOptionPreviews.value = flags and 2 != 0
            patchSelectionDisplayRestoreFlags.value = 0
        }
    }

    val pathSelectorFavorites = stringSetPreference("path_selector_favorites", emptySet())
    val pathSelectorLastDirectory = stringPreference("path_selector_last_directory", "")
    val apkInputLastDirectory = stringPreference("file_picker_apk_input_directory", "")
    val selectedAppApkInputLastDirectory =
        stringPreference("file_picker_selected_app_apk_input_directory", "")
    val patchProfileApkInputLastDirectory =
        stringPreference("file_picker_patch_profile_apk_input_directory", "")
    val dashboardApkInputLastDirectory =
        stringPreference("file_picker_dashboard_apk_input_directory", "")
    val patchedApkExportLastDirectory = stringPreference("file_picker_patched_apk_export_directory", "")
    val savedAppExportLastDirectory =
        stringPreference("file_picker_saved_app_export_directory", "")
    val dashboardQuickExportLastDirectory =
        stringPreference("file_picker_dashboard_quick_export_directory", "")
    val dashboardBundleInputLastDirectory =
        stringPreference("file_picker_dashboard_bundle_input_directory", "")
    val dashboardSplitInputLastDirectory =
        stringPreference("file_picker_dashboard_split_input_directory", "")
    val dashboardSavedAppsExportLastDirectory =
        stringPreference("file_picker_dashboard_saved_apps_export_directory", "")
    val settingsBackupLastDirectory = stringPreference("file_picker_settings_backup_directory", "")
    val keystoreImportLastDirectory = stringPreference("file_picker_keystore_import_directory", "")
    val patchBundlesImportLastDirectory =
        stringPreference("file_picker_patch_bundles_import_directory", "")
    val patchProfilesImportLastDirectory =
        stringPreference("file_picker_patch_profiles_import_directory", "")
    val managerSettingsImportLastDirectory =
        stringPreference("file_picker_manager_settings_import_directory", "")
    val everythingImportLastDirectory =
        stringPreference("file_picker_everything_import_directory", "")
    val patchSelectionImportLastDirectory =
        stringPreference("file_picker_patch_selection_import_directory", "")
    val patchBundlesExportLastDirectory =
        stringPreference("file_picker_patch_bundles_export_directory", "")
    val patchProfilesExportLastDirectory =
        stringPreference("file_picker_patch_profiles_export_directory", "")
    val everythingExportLastDirectory =
        stringPreference("file_picker_everything_export_directory", "")
    val patchSelectionExportLastDirectory =
        stringPreference("file_picker_patch_selection_export_directory", "")
    val currentKeystoreExportLastDirectory = stringPreference("file_picker_current_keystore_export_directory", "")
    val youtubeAssetsExportLastDirectory = stringPreference("file_picker_youtube_assets_export_directory", "")
    val mergedApkExportLastDirectory = stringPreference("file_picker_merged_apk_export_directory", "")
    val signedApkExportLastDirectory = stringPreference("file_picker_signed_apk_export_directory", "")
    val signatureMetadataExportLastDirectory =
        stringPreference("file_picker_signature_metadata_export_directory", "")
    val signatureMetadataLogExportLastDirectory =
        stringPreference("file_picker_signature_metadata_log_export_directory", "")
    val createdKeystoreExportLastDirectory = stringPreference("file_picker_created_keystore_export_directory", "")
    val convertedKeystoreExportLastDirectory = stringPreference("file_picker_converted_keystore_export_directory", "")
    val apkSignerInputLastDirectory = stringPreference("file_picker_apk_signer_input_directory", "")
    val signatureMetadataSourceInputLastDirectory =
        stringPreference("file_picker_signature_metadata_archive_input_directory", "")
    val signatureMetadataApkInputLastDirectory =
        stringPreference("file_picker_signature_metadata_apk_input_directory", "")
    val lsposedModuleInputLastDirectory =
        stringPreference("file_picker_lsposed_module_input_directory", "")
    val youtubeImageInputLastDirectory = stringPreference("file_picker_youtube_image_input_directory", "")
    val keystoreConverterInputLastDirectory =
        stringPreference("file_picker_keystore_converter_input_directory", "")
    val patchOptionFileInputLastDirectory =
        stringPreference("file_picker_patch_option_file_input_directory", "")
    val mergeLogExportLastDirectory = stringPreference("file_picker_merge_log_export_directory", "")
    val patcherLogExportLastDirectory = stringPreference("file_picker_patcher_log_export_directory", "")
    val rootMountDiagnosticsExportLastDirectory =
        stringPreference("file_picker_root_mount_diagnostics_export_directory", "")
    val patchBundleDiscoveryExportLastDirectory =
        stringPreference("file_picker_patch_bundle_discovery_export_directory", "")
    val splitInstallerInputLastDirectory =
        stringPreference("file_picker_split_installer_input_directory", "")
    val splitInstallerLogExportLastDirectory =
        stringPreference("file_picker_split_installer_log_export_directory", "")
    val advancedLogExportLastDirectory =
        stringPreference("file_picker_advanced_log_export_directory", "")
    val downloadsExportLastDirectory = stringPreference("file_picker_downloads_export_directory", "")
    val backgroundImageInputLastDirectory =
        stringPreference("file_picker_background_image_input_directory", "")
    val contentSelectorLastDirectory = stringPreference("file_picker_content_selector_directory", "")
    // Code adapted from Morphe, see third-party/NOTICE for more information
    // https://github.com/MorpheApp/morphe-manager/commit/46e37e2915dc92b6a655127155935e63c0b04efc
    // UPM also remembers a separate folder for each import/export picker.
    private val filePickerDirectoryPreferences by lazy {
        mapOf(
            "apkInputLastDirectory" to apkInputLastDirectory,
            "selectedAppApkInputLastDirectory" to selectedAppApkInputLastDirectory,
            "patchProfileApkInputLastDirectory" to patchProfileApkInputLastDirectory,
            "dashboardApkInputLastDirectory" to dashboardApkInputLastDirectory,
            "patchedApkExportLastDirectory" to patchedApkExportLastDirectory,
            "savedAppExportLastDirectory" to savedAppExportLastDirectory,
            "dashboardQuickExportLastDirectory" to dashboardQuickExportLastDirectory,
            "dashboardBundleInputLastDirectory" to dashboardBundleInputLastDirectory,
            "dashboardSplitInputLastDirectory" to dashboardSplitInputLastDirectory,
            "dashboardSavedAppsExportLastDirectory" to dashboardSavedAppsExportLastDirectory,
            "settingsBackupLastDirectory" to settingsBackupLastDirectory,
            "keystoreImportLastDirectory" to keystoreImportLastDirectory,
            "patchBundlesImportLastDirectory" to patchBundlesImportLastDirectory,
            "patchProfilesImportLastDirectory" to patchProfilesImportLastDirectory,
            "managerSettingsImportLastDirectory" to managerSettingsImportLastDirectory,
            "everythingImportLastDirectory" to everythingImportLastDirectory,
            "patchSelectionImportLastDirectory" to patchSelectionImportLastDirectory,
            "patchBundlesExportLastDirectory" to patchBundlesExportLastDirectory,
            "patchProfilesExportLastDirectory" to patchProfilesExportLastDirectory,
            "everythingExportLastDirectory" to everythingExportLastDirectory,
            "patchSelectionExportLastDirectory" to patchSelectionExportLastDirectory,
            "currentKeystoreExportLastDirectory" to currentKeystoreExportLastDirectory,
            "youtubeAssetsExportLastDirectory" to youtubeAssetsExportLastDirectory,
            "mergedApkExportLastDirectory" to mergedApkExportLastDirectory,
            "signedApkExportLastDirectory" to signedApkExportLastDirectory,
            "signatureMetadataExportLastDirectory" to signatureMetadataExportLastDirectory,
            "signatureMetadataLogExportLastDirectory" to signatureMetadataLogExportLastDirectory,
            "createdKeystoreExportLastDirectory" to createdKeystoreExportLastDirectory,
            "convertedKeystoreExportLastDirectory" to convertedKeystoreExportLastDirectory,
            "apkSignerInputLastDirectory" to apkSignerInputLastDirectory,
            "signatureMetadataSourceInputLastDirectory" to signatureMetadataSourceInputLastDirectory,
            "signatureMetadataApkInputLastDirectory" to signatureMetadataApkInputLastDirectory,
            "lsposedModuleInputLastDirectory" to lsposedModuleInputLastDirectory,
            "youtubeImageInputLastDirectory" to youtubeImageInputLastDirectory,
            "keystoreConverterInputLastDirectory" to keystoreConverterInputLastDirectory,
            "patchOptionFileInputLastDirectory" to patchOptionFileInputLastDirectory,
            "mergeLogExportLastDirectory" to mergeLogExportLastDirectory,
            "patcherLogExportLastDirectory" to patcherLogExportLastDirectory,
            "rootMountDiagnosticsExportLastDirectory" to rootMountDiagnosticsExportLastDirectory,
            "patchBundleDiscoveryExportLastDirectory" to patchBundleDiscoveryExportLastDirectory,
            "splitInstallerInputLastDirectory" to splitInstallerInputLastDirectory,
            "splitInstallerLogExportLastDirectory" to splitInstallerLogExportLastDirectory,
            "advancedLogExportLastDirectory" to advancedLogExportLastDirectory,
            "downloadsExportLastDirectory" to downloadsExportLastDirectory,
            "backgroundImageInputLastDirectory" to backgroundImageInputLastDirectory,
            "contentSelectorLastDirectory" to contentSelectorLastDirectory,
        )
    }

    val pathSelectorSortMode = stringPreference("path_selector_sort_mode", "MODIFIED_DESC")
    val pathSelectorSearchQuery = stringPreference("path_selector_search_query", "")
    val minimalActionButtonGroups = stringSetPreference("minimal_action_button_groups", emptySet())
    val dashboardAppsActionOrder = stringPreference("dashboard_apps_action_order", "storage,add,batch")
    val dashboardBundlesActionOrder = stringPreference("dashboard_bundles_action_order", "discovery,add")
    val dashboardHiddenActions = stringSetPreference("dashboard_hidden_actions", emptySet())
    // Code adapted from Morphe, see third-party/NOTICE for more information
    // https://github.com/MorpheApp/morphe-manager/commit/205956fa9c9bdf6b4ab13cc2d1880077936bc436
    val pathSelectorShowHiddenFiles = booleanPreference("path_selector_show_hidden_files", false)
    val appSelectorFilterInstalledOnly = booleanPreference("app_selector_filter_installed_only", false)
    val appSelectorFilterPatchesAvailable = booleanPreference("app_selector_filter_patches_available", false)
    val splitMergeSelectionPreset = stringPreference("split_merge_selection_preset", "all")
    val splitMergeExcludeUnusedLanguages = booleanPreference("split_merge_exclude_unused_languages", false)
    val splitMergeExcludeExtraDensities = booleanPreference("split_merge_exclude_extra_densities", false)
    val splitMergeExcludeExtraNativeLibs = booleanPreference("split_merge_exclude_extra_native_libs", false)
    val patcherSplitModuleSortMode = stringPreference("patcher_split_module_sort_mode", "DEFAULT")
    val splitMergeModuleSortMode = stringPreference("split_merge_module_sort_mode", "DEFAULT")
    private val splitModuleSortModeSeparationMigrated =
        booleanPreference("split_module_sort_mode_separation_migrated", false)
    val splitMergeInstalledFilterUserApps = booleanPreference("split_merge_installed_filter_user_apps", false)
    val splitMergeInstalledFilterSystemApps = booleanPreference("split_merge_installed_filter_system_apps", false)
    val splitMergeInstalledFilterSplitApks = booleanPreference("split_merge_installed_filter_split_apks", false)
    val splitMergeInstalledFilterSingleApks = booleanPreference("split_merge_installed_filter_single_apks", false)
    val splitMergeAutoCollapseSteps = booleanPreference("split_merge_auto_collapse_steps", false)
    val showSplitMergeMemoryUsageGraph = booleanPreference("show_split_merge_memory_usage_graph", true)
    val compactSplitMergeResourceGraphs = booleanPreference("compact_split_merge_resource_graphs", false)
    val showSplitMergeResourceGraphExtraInfo = booleanPreference("show_split_merge_resource_graph_extra_info", false)
    val showCompactSplitMergeResourceGraphExtraInfo = booleanPreference("show_compact_split_merge_resource_graph_extra_info", false)
    val splitMergeInformationExpanded = booleanPreference("split_merge_information_expanded", true)
    val splitMergeAutoExpandRunningSteps =
        booleanPreference("split_merge_auto_expand_running_steps", true)
    val splitMergeAutoExpandRunningStepsExclusive =
        booleanPreference("split_merge_auto_expand_running_steps_exclusive", false)
    val useCustomFilePicker = booleanPreference("use_custom_file_picker", true)
    val youtubeAssetsSyncHeaderTransforms = booleanPreference("youtube_assets_sync_header_transforms", false)
    val patchBundleDiscoveryState = stringPreference("patch_bundle_discovery_state", "")
    val patchBundleDiscoveryShowRelease = booleanPreference("patch_bundle_discovery_show_release", true)
    val patchBundleDiscoveryShowPrerelease = booleanPreference("patch_bundle_discovery_show_prerelease", true)
    val patchBundleDiscoveryLatest = booleanPreference("patch_bundle_discovery_latest", false)
    val patchBundleDiscoverySortMode = stringPreference("patch_bundle_discovery_sort_mode", "UPDATED_DESC")

    val acknowledgedDownloaderPlugins = stringSetPreference("acknowledged_downloader_plugins", emptySet())
    val downloaderPluginSourcesJson = stringPreference("downloader_plugin_sources_json", "")
    val trustedApkDownloadHelpersJson = stringPreference("trusted_apk_download_helpers_json", "")
    val acknowledgedPatcherRuntimePlugins =
        stringSetPreference("acknowledged_patcher_runtime_plugins", emptySet())
    val trustedPatcherRuntimePluginsJson =
        stringPreference("trusted_patcher_runtime_plugins_json", "")
    val patcherRuntimePluginSourcesJson =
        stringPreference("patcher_runtime_plugin_sources_json", "")
    val autoSaveDownloaderApks = booleanPreference("auto_save_downloader_apks", true)
    val autoSaveDownloaderLatestOnly =
        booleanPreference("auto_save_downloader_latest_only", false)
    val searchEngineHost = stringPreference("search_engine_host", "google.com")

    // Code adapted from Morphe, see third-party/NOTICE for more information
    // https://github.com/MorpheApp/morphe-manager/blob/a2c3d31bd7ab42e6bc4b9dd528ed856fc72fb948/app/src/main/java/app/morphe/manager/domain/manager/PreferencesManager.kt
    init {
        runBlocking {
            // Code adapted from Morphe, see third-party/NOTICE for more information
            // https://github.com/MorpheApp/morphe-manager/commit/3a07ae5ca114667e8f085802ff76c4addb7825db
            // The marker stays on this device. Restored data (and the first upgrade to this
            // policy) starts without a token, before repositories can issue network requests.
            val credentialMarker = context.noBackupFilesDir.resolve(".credentials_initialized")
            if (!credentialMarker.isFile) {
                gitHubPat.update("")
                runCatching { credentialMarker.writeText("1") }
                    .onFailure { Log.w("PreferencesManager", "Could not mark credential initialization", it) }
            }

        }
    }

    @Serializable
    data class SettingsSnapshot(
        val dynamicColor: Boolean? = null,
        val pureBlackTheme: Boolean? = null,
        val materialYouPureBlackTheme: Boolean? = null,
        val pureBlackOnSystemDark: Boolean? = null,
        val customAccentColor: String? = null,
        val customThemeColor: String? = null,
        val customBackgroundImageUri: String? = null,
        val customBackgroundImageOpacity: Float? = null,
        val hideMainTabLabels: Boolean? = null,
        val showAppsTabUpdateCount: Boolean? = null,
        val showBundlesTabUpdateCount: Boolean? = null,
        val disableMainTabSwipe: Boolean? = null,
        val disablePatchSelectionTabSwipe: Boolean? = null,
        val preventAccidentalTouching: Boolean? = null,
        val showPatchProfilesTab: Boolean? = null,
        val showToolsTab: Boolean? = null,
        val showLsposedTab: Boolean? = null,
        val appSelectorSortMode: String? = null,
        val batchQueueAppSelectorSortMode: String? = null,
        val themePresetSelectionName: String? = null,
        val themePresetSelectionEnabled: Boolean? = null,
        val stripUnusedNativeLibs: Boolean? = null,
        val skipUnneededSplitApks: Boolean? = null,
        val chooseSplitApksBeforePatching: Boolean? = null,
        val continueOnPatchError: Boolean? = null,
        val skipApkSigning: Boolean? = null,
        val skipSplitMergeSigning: Boolean? = null,
        val disableApkSignatureChecks: Boolean? = null,
        val injectSignatureMetadataAfterPatching: Boolean? = null,
        val artMemoryLimit: Int? = null,
        val useJvmRuntime: Boolean? = null,
        val morpheBytecodeMode: String? = null,
        val patcherLogMode: PatcherLogMode? = null,
        val patchAvailabilityEnabled: Boolean? = null,
        val removeGmsCoreForPrimaryMount: Boolean? = null,
        val theme: Theme? = null,
        val appLanguage: String? = null,
        val api: String? = null,
        val gitHubPat: String? = null,
        val includeGitHubPatInExports: Boolean? = null,
        val autoCollapsePatcherSteps: Boolean? = null,
        val showPatcherMemoryUsageGraph: Boolean? = null,
        val compactPatcherResourceGraphs: Boolean? = null,
        val showPatcherResourceGraphExtraInfo: Boolean? = null,
        val showCompactPatcherResourceGraphExtraInfo: Boolean? = null,
        val patcherInformationExpanded: Boolean? = null,
        val autoExpandRunningSteps: Boolean? = null,
        val autoExpandRunningStepsExclusive: Boolean? = null,
        val enableSavedApps: Boolean? = null,
        val disableSavedAppOverwrite: Boolean? = null,
        val showSavedAppBundleUpdateBadges: Boolean? = null,
        val rootMountToolsCollapsed: Boolean? = null,
        val patchedAppExportFormat: String? = null,
        val mergedApkExportFormat: String? = null,
        val officialBundleRemoved: Boolean? = null,
        val officialBundleSortOrder: Int? = null,
        val officialBundleCustomDisplayName: String? = null,
        val dashboardBundlesFabCollapsed: Boolean? = null,
        val dashboardAppsFabCollapsed: Boolean? = null,
        val dashboardLsposedFabCollapsed: Boolean? = null,
        val dashboardProgressBannerCollapsed: Boolean? = null,
        val dashboardBundleImportBannerCollapsed: Boolean? = null,
        val dashboardBundleUpdateBannerCollapsed: Boolean? = null,
        val allowMeteredUpdates: Boolean? = null,
        val chooseInstallerPerInstall: Boolean? = null,
        val installerPrimary: String? = null,
        val installerFallback: String? = null,
        val installerCustomComponents: Set<String>? = null,
        val installerHiddenComponents: Set<String>? = null,
        val shizukuInstallAsPlayStore: Boolean? = null,
        val autoInstallWithShizuku: Boolean? = null,
        val autoPatchInstall: Boolean? = null,
        val autoUninstallWithShizuku: Boolean? = null,
        val autoPatchEnabled: Boolean? = null,
        val autoPatchInstallWithShizuku: Boolean? = null,
        val autoPatchUninstallOnConflictWithShizuku: Boolean? = null,
        val autoPatchRequiresCharging: Boolean? = null,
        val autoPatchInterval: SearchForUpdatesBackgroundInterval? = null,
        val autoPatchEnabledPackages: Set<String>? = null,
        val savedAppLauncherShortcutPackages: Set<String>? = null,
        val allowExternalBatchActions: Boolean? = null,
        val keystoreAlias: String? = null,
        val keystorePass: String? = null,
        val keystoreKeyPass: String? = null,
        val firstLaunch: Boolean? = null,
        val managerAutoUpdates: Boolean? = null,
        val showManagerUpdateDialogOnLaunch: Boolean? = null,
        val showManagerUpdateChangelog: Boolean? = null,
        val announcementSystemEnabled: Boolean? = null,
        val selectedAnnouncementTags: Set<String>? = null,
        val announcementPushNotifications: Boolean? = null,
        val announcementPushNotificationInterval: SearchForUpdatesBackgroundInterval? = null,
        val autoClearCacheInterval: AutoClearCacheInterval? = null,
        val useManagerPrereleases: Boolean? = null,
        val usePatchesPrereleases: Boolean? = null,
        val showBatteryOptimizationBanner: Boolean? = null,
        val allowPatchProfileBundleOverride: Boolean? = null,
        val searchForUpdatesBackgroundInterval: SearchForUpdatesBackgroundInterval? = null,
        val searchForManagerUpdatesBackgroundInterval: SearchForUpdatesBackgroundInterval? = null,
        val bundleUpdateDeliveryMode: BundleUpdateDeliveryMode? = null,
        val bundleChangelogFetchLimit: Int? = null,
        val bundleChangelogStorageLimit: Int? = null,
        val disablePatchVersionCompatCheck: Boolean? = null,
        val disableSelectionWarning: Boolean? = null,
        val disableUniversalPatchCheck: Boolean? = null,
        val suggestedVersionSafeguard: Boolean? = null,
        val disablePatchSelectionConfirmations: Boolean? = null,
        val showPatchSelectionSummary: Boolean? = null,
        val collapsePatchActionsOnSelection: Boolean? = null,
        val patchSelectionFilterFlags: Int? = null,
        val patchSelectionShowNewPatches: Boolean? = null,
        val patchSelectionSortAlphabetical: Boolean? = null,
        val patchSelectionSortDescending: Boolean? = null,
        val patchSelectionSortSettingsMode: String? = null,
        val patchSelectionSortSelectionMode: String? = null,
        val patchSelectionActionOrder: String? = null,
        val patchSelectionHiddenActions: Set<String>? = null,
        val patchSelectionShowVersionTags: Boolean? = null,
        val patchSelectionShowOptionPreviews: Boolean? = null,
        val patchSelectionDisplayRestoreFlags: Int? = null,
        val patchBundleActionOrder: String? = null,
        val patchBundleHiddenActions: Set<String>? = null,
        val batchResultActionOrder: String? = null,
        val batchResultHiddenActions: Set<String>? = null,
        val savedAppActionOrder: String? = null,
        val savedAppHiddenActions: Set<String>? = null,
        val patchProfileActionOrder: String? = null,
        val patchProfileHiddenActions: Set<String>? = null,
        val lsposedModuleActionOrder: String? = null,
        val lsposedModuleHiddenActions: Set<String>? = null,
        val acknowledgedDownloaderPlugins: Set<String>? = null,
        val downloaderPluginSourcesJson: String? = null,
        val trustedApkDownloadHelpersJson: String? = null,
        val acknowledgedPatcherRuntimePlugins: Set<String>? = null,
        val trustedPatcherRuntimePluginsJson: String? = null,
        val patcherRuntimePluginSourcesJson: String? = null,
        val autoSaveDownloaderApks: Boolean? = null,
        val autoSaveDownloaderLatestOnly: Boolean? = null,
        val pathSelectorFavorites: Set<String>? = null,
        val pathSelectorLastDirectory: String? = null,
        // Code adapted from Morphe, see third-party/NOTICE for more information
        // https://github.com/MorpheApp/morphe-manager/commit/46e37e2915dc92b6a655127155935e63c0b04efc
        val pathSelectorSortMode: String? = null,
        val pathSelectorSearchQuery: String? = null,
        val minimalActionButtonGroups: Set<String>? = null,
        val dashboardAppsActionOrder: String? = null,
        val dashboardBundlesActionOrder: String? = null,
        val dashboardHiddenActions: Set<String>? = null,
        val pathSelectorShowHiddenFiles: Boolean? = null,
        val filePickerLastDirectories: Map<String, String>? = null,
        val appSelectorFilterInstalledOnly: Boolean? = null,
        val appSelectorFilterPatchesAvailable: Boolean? = null,
        val splitMergeSelectionPreset: String? = null,
        val splitMergeExcludeUnusedLanguages: Boolean? = null,
        val splitMergeExcludeExtraDensities: Boolean? = null,
        val splitMergeExcludeExtraNativeLibs: Boolean? = null,
        val patcherSplitModuleSortMode: String? = null,
        val splitMergeModuleSortMode: String? = null,
        val splitMergeInstalledFilterUserApps: Boolean? = null,
        val splitMergeInstalledFilterSystemApps: Boolean? = null,
        val splitMergeInstalledFilterSplitApks: Boolean? = null,
        val splitMergeInstalledFilterSingleApks: Boolean? = null,
        val splitMergeAutoCollapseSteps: Boolean? = null,
        val showSplitMergeMemoryUsageGraph: Boolean? = null,
        val compactSplitMergeResourceGraphs: Boolean? = null,
        val showSplitMergeResourceGraphExtraInfo: Boolean? = null,
        val showCompactSplitMergeResourceGraphExtraInfo: Boolean? = null,
        val splitMergeInformationExpanded: Boolean? = null,
        val splitMergeAutoExpandRunningSteps: Boolean? = null,
        val splitMergeAutoExpandRunningStepsExclusive: Boolean? = null,
        val useCustomFilePicker: Boolean? = null,
        val youtubeAssetsSyncHeaderTransforms: Boolean? = null,
        val patchBundleDiscoveryShowRelease: Boolean? = null,
        val patchBundleDiscoveryShowPrerelease: Boolean? = null,
        val patchBundleDiscoveryLatest: Boolean? = null,
        val patchBundleDiscoverySortMode: String? = null,
        val searchEngineHost: String? = null,
    )

    suspend fun exportSettings(): SettingsSnapshot = readSnapshot {
        var snapshot = SettingsSnapshot()
        snapshot = exportAppearanceSettings(snapshot)
        snapshot = exportCoreUpdateSettings(snapshot)
        snapshot = exportRuntimeAndInstallerSettings(snapshot)
        snapshot = exportPatchingSettings(snapshot)
        snapshot = exportDiscoverySettings(snapshot)
        snapshot
    }

    suspend fun importSettings(snapshot: SettingsSnapshot) = edit {
        val imported = normalizeImportedManagerSettings(snapshot)
        importAppearanceSettings(imported)
        importCoreUpdateSettings(imported)
        importRuntimeAndInstallerSettings(imported)
        importPatchingSettings(imported)
        importDiscoverySettings(imported)
    }

    suspend fun migrateAnnouncementPushNotificationInterval() = edit {
        if (announcementPushNotificationIntervalMigrated.value) return@edit

        val legacyAnnouncementsEnabled = announcementPushNotificationsLegacy.value
        announcementPushNotificationInterval.value =
            if (legacyAnnouncementsEnabled) {
                SearchForUpdatesBackgroundInterval.MIN15
            } else {
                SearchForUpdatesBackgroundInterval.NEVER
            }
        if (legacyAnnouncementsEnabled) {
            announcementSystemEnabled.value = true
        }
        announcementPushNotificationsLegacy.value = false
        announcementPushNotificationIntervalMigrated.value = true
    }

    suspend fun enableManagerPrereleasesForVersion(versionName: String) {
        val normalizedVersion = versionName.removePrefix("v").substringBefore('+')
        if (!isManagerPrereleaseVersion(normalizedVersion)) {
            if (managerPrereleaseAutoEnabledVersion.get().isNotEmpty()) {
                managerPrereleaseAutoEnabledVersion.update("")
            }
            return
        }

        edit {
            if (managerPrereleaseAutoEnabledVersion.value == normalizedVersion) return@edit
            useManagerPrereleases.value = true
            managerPrereleaseAutoEnabledVersion.value = normalizedVersion
        }
    }

    suspend fun useManagerPrereleasesForVersion(versionName: String): Boolean {
        enableManagerPrereleasesForVersion(versionName)
        return useManagerPrereleases.get()
    }

    private fun EditorContext.exportAppearanceSettings(snapshot: SettingsSnapshot): SettingsSnapshot {
        return snapshot.copy(
            dynamicColor = dynamicColor.value,
            pureBlackTheme = pureBlackTheme.value,
            materialYouPureBlackTheme = materialYouPureBlackTheme.value,
            pureBlackOnSystemDark = pureBlackOnSystemDark.value,
            customAccentColor = customAccentColor.value,
            customThemeColor = customThemeColor.value,
            customBackgroundImageUri = customBackgroundImageUri.value,
            customBackgroundImageOpacity = customBackgroundImageOpacity.value,
            hideMainTabLabels = hideMainTabLabels.value,
            showAppsTabUpdateCount = showAppsTabUpdateCount.value,
            showBundlesTabUpdateCount = showBundlesTabUpdateCount.value,
            disableMainTabSwipe = disableMainTabSwipe.value,
            disablePatchSelectionTabSwipe = disablePatchSelectionTabSwipe.value,
            preventAccidentalTouching = preventAccidentalTouching.value,
            showPatchProfilesTab = showPatchProfilesTab.value,
            showToolsTab = showToolsTab.value,
            showLsposedTab = showLsposedTab.value,
            appSelectorSortMode = appSelectorSortMode.value,
            batchQueueAppSelectorSortMode = batchQueueAppSelectorSortMode.value,
            themePresetSelectionName = themePresetSelectionName.value,
            themePresetSelectionEnabled = themePresetSelectionEnabled.value,
            theme = theme.value,
            appLanguage = appLanguage.value
        )
    }

    private fun EditorContext.exportCoreUpdateSettings(snapshot: SettingsSnapshot): SettingsSnapshot {
        val exportPat = includeGitHubPatInExports.value
        return snapshot.copy(
            api = api.value,
            gitHubPat = gitHubPat.value.takeIf { exportPat },
            includeGitHubPatInExports = exportPat,
            firstLaunch = firstLaunch.value,
            managerAutoUpdates = managerAutoUpdates.value,
            showManagerUpdateDialogOnLaunch = showManagerUpdateDialogOnLaunch.value,
            showManagerUpdateChangelog = showManagerUpdateChangelog.value,
            announcementSystemEnabled = announcementSystemEnabled.value,
            selectedAnnouncementTags = selectedAnnouncementTags.value,
            announcementPushNotifications =
                announcementPushNotificationInterval.value != SearchForUpdatesBackgroundInterval.NEVER,
            announcementPushNotificationInterval = announcementPushNotificationInterval.value,
            autoClearCacheInterval = autoClearCacheInterval.value,
            useManagerPrereleases = useManagerPrereleases.value,
            showBatteryOptimizationBanner = showBatteryOptimizationBanner.value,
            allowPatchProfileBundleOverride = allowPatchProfileBundleOverride.value,
            searchForUpdatesBackgroundInterval = searchForUpdatesBackgroundInterval.value,
            searchForManagerUpdatesBackgroundInterval = searchForManagerUpdatesBackgroundInterval.value,
            bundleUpdateDeliveryMode = bundleUpdateDeliveryMode.value,
            bundleChangelogFetchLimit = bundleChangelogFetchLimit.value,
            bundleChangelogStorageLimit = bundleChangelogStorageLimit.value,
            allowMeteredUpdates = allowMeteredUpdates.value
        )
    }

    private fun EditorContext.exportRuntimeAndInstallerSettings(snapshot: SettingsSnapshot): SettingsSnapshot {
        val autoPatchEnabledValue = autoPatchEnabled.value
        val restoreShizukuSettings =
            !autoPatchEnabledValue && autoPatchShizukuSettingsRestorePending.value
        val exportedAutoPatchInstallWithShizuku = if (restoreShizukuSettings) {
            autoPatchInstallWithShizukuBeforeDisable.value
        } else {
            autoPatchInstallWithShizuku.value
        }
        val exportedAutoPatchUninstallOnConflictWithShizuku = if (restoreShizukuSettings) {
            autoPatchUninstallOnConflictWithShizukuBeforeDisable.value
        } else {
            autoPatchUninstallOnConflictWithShizuku.value
        }

        return snapshot.copy(
            stripUnusedNativeLibs = stripUnusedNativeLibs.value,
            skipUnneededSplitApks = skipUnneededSplitApks.value,
            chooseSplitApksBeforePatching = chooseSplitApksBeforePatching.value,
            continueOnPatchError = continueOnPatchError.value,
            skipApkSigning = skipApkSigning.value,
            skipSplitMergeSigning = skipSplitMergeSigning.value,
            disableApkSignatureChecks = disableApkSignatureChecks.value,
            injectSignatureMetadataAfterPatching = injectSignatureMetadataAfterPatching.value,
            artMemoryLimit = artMemoryLimit.value,
            useJvmRuntime = useJvmRuntime.value,
            morpheBytecodeMode = morpheBytecodeMode.value.runtimeValue,
            patcherLogMode = patcherLogMode.value,
            patchAvailabilityEnabled = patchAvailabilityEnabled.value,
            removeGmsCoreForPrimaryMount = removeGmsCoreForPrimaryMount.value,
            autoCollapsePatcherSteps = autoCollapsePatcherSteps.value,
            showPatcherMemoryUsageGraph = showPatcherMemoryUsageGraph.value,
            compactPatcherResourceGraphs = compactPatcherResourceGraphs.value,
            showPatcherResourceGraphExtraInfo = showPatcherResourceGraphExtraInfo.value,
            showCompactPatcherResourceGraphExtraInfo = showCompactPatcherResourceGraphExtraInfo.value,
            patcherInformationExpanded = patcherInformationExpanded.value,
            autoExpandRunningSteps = autoExpandRunningSteps.value,
            autoExpandRunningStepsExclusive = autoExpandRunningStepsExclusive.value,
            enableSavedApps = enableSavedApps.value,
            disableSavedAppOverwrite = disableSavedAppOverwrite.value,
            showSavedAppBundleUpdateBadges = showSavedAppBundleUpdateBadges.value,
            rootMountToolsCollapsed = rootMountToolsCollapsed.value,
            patchedAppExportFormat = patchedAppExportFormat.value,
            mergedApkExportFormat = mergedApkExportFormat.value,
            chooseInstallerPerInstall = chooseInstallerPerInstall.value,
            installerPrimary = installerPrimary.value,
            installerFallback = installerFallback.value,
            installerCustomComponents = installerCustomComponents.value,
            installerHiddenComponents = installerHiddenComponents.value,
            shizukuInstallAsPlayStore = shizukuInstallAsPlayStore.value,
            autoInstallWithShizuku = autoInstallWithShizuku.value,
            autoUninstallWithShizuku = autoUninstallWithShizuku.value,
            autoPatchEnabled = autoPatchEnabledValue,
            autoPatchInstallWithShizuku = exportedAutoPatchInstallWithShizuku,
            autoPatchUninstallOnConflictWithShizuku =
                exportedAutoPatchUninstallOnConflictWithShizuku,
            autoPatchRequiresCharging = autoPatchRequiresCharging.value,
            autoPatchInterval = autoPatchInterval.value,
            autoPatchEnabledPackages = autoPatchEnabledPackages.value,
            savedAppLauncherShortcutPackages = savedAppLauncherShortcutPackages.value,
            allowExternalBatchActions = allowExternalBatchActions.value,
            keystoreAlias = keystoreAlias.value,
            keystorePass = keystorePass.value,
            keystoreKeyPass = keystoreKeyPass.value,
            dashboardBundlesFabCollapsed = dashboardBundlesFabCollapsed.value,
            dashboardAppsFabCollapsed = dashboardAppsFabCollapsed.value,
            dashboardLsposedFabCollapsed = dashboardLsposedFabCollapsed.value,
            dashboardProgressBannerCollapsed =
                dashboardBundleImportBannerCollapsed.value && dashboardBundleUpdateBannerCollapsed.value,
            dashboardBundleImportBannerCollapsed = dashboardBundleImportBannerCollapsed.value,
            dashboardBundleUpdateBannerCollapsed = dashboardBundleUpdateBannerCollapsed.value
        )
    }

    private fun EditorContext.exportPatchingSettings(snapshot: SettingsSnapshot): SettingsSnapshot {
        return snapshot.copy(
            disablePatchVersionCompatCheck = disablePatchVersionCompatCheck.value,
            disableSelectionWarning = disableSelectionWarning.value,
            disableUniversalPatchCheck = disableUniversalPatchCheck.value,
            suggestedVersionSafeguard = suggestedVersionSafeguard.value,
            disablePatchSelectionConfirmations = disablePatchSelectionConfirmations.value,
            showPatchSelectionSummary = showPatchSelectionSummary.value,
            collapsePatchActionsOnSelection = collapsePatchActionsOnSelection.value,
            patchSelectionFilterFlags = patchSelectionFilterFlags.value,
            patchSelectionShowNewPatches = patchSelectionShowNewPatches.value,
            patchSelectionSortAlphabetical = patchSelectionSortAlphabetical.value,
            patchSelectionSortDescending = patchSelectionSortDescending.value,
            patchSelectionSortSettingsMode = patchSelectionSortSettingsMode.value,
            patchSelectionSortSelectionMode = patchSelectionSortSelectionMode.value,
            patchSelectionActionOrder = patchSelectionActionOrder.value,
            patchSelectionHiddenActions = patchSelectionHiddenActions.value,
            patchSelectionShowVersionTags = patchSelectionShowVersionTags.value,
            patchSelectionShowOptionPreviews = patchSelectionShowOptionPreviews.value,
            patchSelectionDisplayRestoreFlags = patchSelectionDisplayRestoreFlags.value,
            patchBundleActionOrder = patchBundleActionOrder.value,
            patchBundleHiddenActions = patchBundleHiddenActions.value,
            batchResultActionOrder = batchResultActionOrder.value,
            batchResultHiddenActions = batchResultHiddenActions.value,
            savedAppActionOrder = savedAppActionOrder.value,
            savedAppHiddenActions = savedAppHiddenActions.value,
            patchProfileActionOrder = patchProfileActionOrder.value,
            patchProfileHiddenActions = patchProfileHiddenActions.value,
            lsposedModuleActionOrder = lsposedModuleActionOrder.value,
            lsposedModuleHiddenActions = lsposedModuleHiddenActions.value
        )
    }

    private fun EditorContext.exportDiscoverySettings(snapshot: SettingsSnapshot): SettingsSnapshot {
        return snapshot.copy(
            acknowledgedDownloaderPlugins = acknowledgedDownloaderPlugins.value,
            downloaderPluginSourcesJson = downloaderPluginSourcesJson.value,
            trustedApkDownloadHelpersJson =
                trustedApkDownloadHelpersJson.value,
            acknowledgedPatcherRuntimePlugins = acknowledgedPatcherRuntimePlugins.value,
            trustedPatcherRuntimePluginsJson =
                trustedPatcherRuntimePluginsJson.value,
            patcherRuntimePluginSourcesJson =
                patcherRuntimePluginSourcesJson.value,
            autoSaveDownloaderApks = autoSaveDownloaderApks.value,
            autoSaveDownloaderLatestOnly = autoSaveDownloaderLatestOnly.value,
            pathSelectorFavorites = pathSelectorFavorites.value,
            pathSelectorLastDirectory = pathSelectorLastDirectory.value,
            // Code adapted from Morphe, see third-party/NOTICE for more information
            // https://github.com/MorpheApp/morphe-manager/commit/46e37e2915dc92b6a655127155935e63c0b04efc
            pathSelectorSortMode = pathSelectorSortMode.value,
            pathSelectorSearchQuery = pathSelectorSearchQuery.value,
            minimalActionButtonGroups = minimalActionButtonGroups.value,
            dashboardAppsActionOrder = dashboardAppsActionOrder.value,
            dashboardBundlesActionOrder = dashboardBundlesActionOrder.value,
            dashboardHiddenActions = dashboardHiddenActions.value,
            pathSelectorShowHiddenFiles = pathSelectorShowHiddenFiles.value,
            filePickerLastDirectories = filePickerDirectoryPreferences.mapValues { (_, preference) ->
                preference.value
            },
            appSelectorFilterInstalledOnly = appSelectorFilterInstalledOnly.value,
            appSelectorFilterPatchesAvailable = appSelectorFilterPatchesAvailable.value,
            splitMergeSelectionPreset = splitMergeSelectionPreset.value,
            splitMergeExcludeUnusedLanguages = splitMergeExcludeUnusedLanguages.value,
            splitMergeExcludeExtraDensities = splitMergeExcludeExtraDensities.value,
            splitMergeExcludeExtraNativeLibs = splitMergeExcludeExtraNativeLibs.value,
            patcherSplitModuleSortMode = patcherSplitModuleSortMode.value,
            splitMergeModuleSortMode = splitMergeModuleSortMode.value,
            splitMergeInstalledFilterUserApps = splitMergeInstalledFilterUserApps.value,
            splitMergeInstalledFilterSystemApps = splitMergeInstalledFilterSystemApps.value,
            splitMergeInstalledFilterSplitApks = splitMergeInstalledFilterSplitApks.value,
            splitMergeInstalledFilterSingleApks = splitMergeInstalledFilterSingleApks.value,
            splitMergeAutoCollapseSteps = splitMergeAutoCollapseSteps.value,
            showSplitMergeMemoryUsageGraph = showSplitMergeMemoryUsageGraph.value,
            compactSplitMergeResourceGraphs = compactSplitMergeResourceGraphs.value,
            showSplitMergeResourceGraphExtraInfo = showSplitMergeResourceGraphExtraInfo.value,
            showCompactSplitMergeResourceGraphExtraInfo = showCompactSplitMergeResourceGraphExtraInfo.value,
            splitMergeInformationExpanded = splitMergeInformationExpanded.value,
            splitMergeAutoExpandRunningSteps = splitMergeAutoExpandRunningSteps.value,
            splitMergeAutoExpandRunningStepsExclusive = splitMergeAutoExpandRunningStepsExclusive.value,
            useCustomFilePicker = useCustomFilePicker.value,
            youtubeAssetsSyncHeaderTransforms = youtubeAssetsSyncHeaderTransforms.value,
            patchBundleDiscoveryShowRelease = patchBundleDiscoveryShowRelease.value,
            patchBundleDiscoveryShowPrerelease = patchBundleDiscoveryShowPrerelease.value,
            patchBundleDiscoveryLatest = patchBundleDiscoveryLatest.value,
            patchBundleDiscoverySortMode = patchBundleDiscoverySortMode.value,
            searchEngineHost = searchEngineHost.value
        )
    }

    private fun sanitizeImportedPickerDirectory(directory: String): String? {
        if (directory.isEmpty()) return ""
        if (directory.isBlank()) return null
        return runCatching {
            val resolved = Paths.get(directory)
            if (!resolved.isAbsolute) return@runCatching null
            val target = when {
                resolved.isDirectory() -> resolved
                resolved.parent?.isDirectory() == true -> resolved.parent
                else -> null
            }
            target?.takeIf { it.isReadable() }?.toString()
        }.getOrNull()
    }

    private fun EditorContext.importAppearanceSettings(snapshot: SettingsSnapshot) {
        snapshot.dynamicColor?.let { dynamicColor.value = it }
        snapshot.pureBlackTheme?.let { pureBlackTheme.value = it }
        snapshot.materialYouPureBlackTheme?.let { materialYouPureBlackTheme.value = it }
        snapshot.pureBlackOnSystemDark?.let { pureBlackOnSystemDark.value = it }
        snapshot.customAccentColor?.let { customAccentColor.value = it }
        snapshot.customThemeColor?.let { customThemeColor.value = it }
        snapshot.customBackgroundImageUri?.let { customBackgroundImageUri.value = it }
        snapshot.customBackgroundImageOpacity?.let { customBackgroundImageOpacity.value = it.coerceIn(0f, 1f) }
        snapshot.hideMainTabLabels?.let { hideMainTabLabels.value = it }
        snapshot.showAppsTabUpdateCount?.let { showAppsTabUpdateCount.value = it }
        snapshot.showBundlesTabUpdateCount?.let { showBundlesTabUpdateCount.value = it }
        snapshot.disableMainTabSwipe?.let { disableMainTabSwipe.value = it }
        snapshot.disablePatchSelectionTabSwipe?.let { disablePatchSelectionTabSwipe.value = it }
        snapshot.preventAccidentalTouching?.let { preventAccidentalTouching.value = it }
        snapshot.showPatchProfilesTab?.let { showPatchProfilesTab.value = it }
        snapshot.showToolsTab?.let { showToolsTab.value = it }
        snapshot.showLsposedTab?.let { showLsposedTab.value = it }
        snapshot.appSelectorSortMode?.takeIf { it.isNotBlank() }?.let { appSelectorSortMode.value = it }
        snapshot.batchQueueAppSelectorSortMode?.takeIf { it.isNotBlank() }?.let {
            batchQueueAppSelectorSortMode.value = it
        }
        snapshot.themePresetSelectionName?.let { themePresetSelectionName.value = it }
        snapshot.themePresetSelectionEnabled?.let { themePresetSelectionEnabled.value = it }
        snapshot.theme?.let { theme.value = it }
        snapshot.appLanguage?.let { appLanguage.value = it }
    }

    private fun EditorContext.importCoreUpdateSettings(snapshot: SettingsSnapshot) {
        snapshot.selectedAnnouncementTags?.let { selectedAnnouncementTags.value = it }
        snapshot.api?.let { api.value = it }
        snapshot.gitHubPat?.let { gitHubPat.value = it }
        snapshot.includeGitHubPatInExports?.let { includeGitHubPatInExports.value = it }
        snapshot.firstLaunch?.let { firstLaunch.value = it }
        snapshot.managerAutoUpdates?.let { managerAutoUpdates.value = it }
        snapshot.showManagerUpdateDialogOnLaunch?.let {
            showManagerUpdateDialogOnLaunch.value = it
        }
        snapshot.showManagerUpdateChangelog?.let { showManagerUpdateChangelog.value = it }
        snapshot.announcementSystemEnabled?.let { announcementSystemEnabled.value = it }
        snapshot.announcementPushNotificationInterval?.let {
            announcementPushNotificationInterval.value = it
        } ?: snapshot.announcementPushNotifications?.let {
            announcementPushNotificationInterval.value = if (it) {
                SearchForUpdatesBackgroundInterval.MIN15
            } else {
                SearchForUpdatesBackgroundInterval.NEVER
            }
            if (snapshot.announcementSystemEnabled == null && it) {
                announcementSystemEnabled.value = true
            }
        }
        if (snapshot.announcementPushNotificationInterval != null ||
            snapshot.announcementPushNotifications != null
        ) {
            announcementPushNotificationsLegacy.value = false
            announcementPushNotificationIntervalMigrated.value = true
        }
        snapshot.autoClearCacheInterval?.let { autoClearCacheInterval.value = it }
        snapshot.useManagerPrereleases?.let { useManagerPrereleases.value = it }
        snapshot.showBatteryOptimizationBanner?.let { showBatteryOptimizationBanner.value = it }
        snapshot.allowPatchProfileBundleOverride?.let { allowPatchProfileBundleOverride.value = it }
        snapshot.searchForUpdatesBackgroundInterval?.let {
            searchForUpdatesBackgroundInterval.value = it
        }
        snapshot.searchForManagerUpdatesBackgroundInterval?.let {
            searchForManagerUpdatesBackgroundInterval.value = it
        }
        snapshot.bundleUpdateDeliveryMode?.let {
            bundleUpdateDeliveryMode.value = it
        }
        snapshot.bundleChangelogFetchLimit?.let {
            bundleChangelogFetchLimit.value = it.coerceAtLeast(MIN_BUNDLE_CHANGELOG_HISTORY_LIMIT)
        }
        snapshot.bundleChangelogStorageLimit?.let {
            bundleChangelogStorageLimit.value = it.coerceAtLeast(MIN_BUNDLE_CHANGELOG_HISTORY_LIMIT)
        }
        snapshot.allowMeteredUpdates?.let { allowMeteredUpdates.value = it }
    }

    private fun EditorContext.importRuntimeAndInstallerSettings(snapshot: SettingsSnapshot) {
        snapshot.dashboardLsposedFabCollapsed?.let { dashboardLsposedFabCollapsed.value = it }
        snapshot.stripUnusedNativeLibs?.let { stripUnusedNativeLibs.value = it }
        snapshot.skipUnneededSplitApks?.let { skipUnneededSplitApks.value = it }
        snapshot.chooseSplitApksBeforePatching?.let { chooseSplitApksBeforePatching.value = it }
        snapshot.continueOnPatchError?.let { continueOnPatchError.value = it }
        snapshot.skipApkSigning?.let { skipApkSigning.value = it }
        snapshot.skipSplitMergeSigning?.let { skipSplitMergeSigning.value = it }
        snapshot.disableApkSignatureChecks?.let { disableApkSignatureChecks.value = it }
        snapshot.injectSignatureMetadataAfterPatching?.let { injectSignatureMetadataAfterPatching.value = it }
        snapshot.artMemoryLimit?.let { artMemoryLimit.value = it.coerceIn(512, 2048) }
        snapshot.useJvmRuntime?.let { useJvmRuntime.value = it }
        snapshot.morpheBytecodeMode?.let {
            morpheBytecodeMode.value = MorpheBytecodeMode.fromRuntimeValue(it)
        }
        snapshot.patcherLogMode?.let { patcherLogMode.value = it }
        snapshot.patchAvailabilityEnabled?.let { patchAvailabilityEnabled.value = it }
        snapshot.removeGmsCoreForPrimaryMount?.let { removeGmsCoreForPrimaryMount.value = it }
        snapshot.autoCollapsePatcherSteps?.let { autoCollapsePatcherSteps.value = it }
        snapshot.showPatcherMemoryUsageGraph?.let { showPatcherMemoryUsageGraph.value = it }
        snapshot.compactPatcherResourceGraphs?.let { compactPatcherResourceGraphs.value = it }
        snapshot.showPatcherResourceGraphExtraInfo?.let { showPatcherResourceGraphExtraInfo.value = it }
        snapshot.showCompactPatcherResourceGraphExtraInfo?.let { showCompactPatcherResourceGraphExtraInfo.value = it }
        snapshot.patcherInformationExpanded?.let { patcherInformationExpanded.value = it }
        snapshot.autoExpandRunningSteps?.let { autoExpandRunningSteps.value = it }
        snapshot.autoExpandRunningStepsExclusive?.let { autoExpandRunningStepsExclusive.value = it }
        snapshot.enableSavedApps?.let { enableSavedApps.value = it }
        snapshot.disableSavedAppOverwrite?.let { disableSavedAppOverwrite.value = it }
        snapshot.showSavedAppBundleUpdateBadges?.let { showSavedAppBundleUpdateBadges.value = it }
        snapshot.rootMountToolsCollapsed?.let { rootMountToolsCollapsed.value = it }
        snapshot.patchedAppExportFormat?.let { patchedAppExportFormat.value = it }
        snapshot.mergedApkExportFormat?.let { mergedApkExportFormat.value = it }
        snapshot.chooseInstallerPerInstall?.let { chooseInstallerPerInstall.value = it }
        val importedPrimary = snapshot.installerPrimary
        val importedFallback = snapshot.installerFallback
        importedPrimary?.let {
            installerPrimary.value = if (it == InstallerPreferenceTokens.SHIZUKU_GOOGLE_PLAY) {
                InstallerPreferenceTokens.SHIZUKU
            } else it
        }
        importedFallback?.let {
            installerFallback.value = if (it == InstallerPreferenceTokens.SHIZUKU_GOOGLE_PLAY) {
                InstallerPreferenceTokens.SHIZUKU
            } else it
        }
        snapshot.installerCustomComponents?.let { installerCustomComponents.value = it }
        snapshot.installerHiddenComponents?.let { installerHiddenComponents.value = it }
        val importedLegacyPlayStoreMode =
            importedPrimary == InstallerPreferenceTokens.SHIZUKU_GOOGLE_PLAY ||
                importedFallback == InstallerPreferenceTokens.SHIZUKU_GOOGLE_PLAY
        val importedPlayStoreMode = snapshot.shizukuInstallAsPlayStore
            ?: if (importedLegacyPlayStoreMode) true else null
        importedPlayStoreMode?.let {
            shizukuInstallAsPlayStore.value = it
        }
        (snapshot.autoInstallWithShizuku ?: snapshot.autoPatchInstall)?.let {
            autoInstallWithShizuku.value = it
        }
        snapshot.autoUninstallWithShizuku?.let { autoUninstallWithShizuku.value = it }
        snapshot.autoPatchInstallWithShizuku?.let {
            autoPatchInstallWithShizuku.value = it
        }
        snapshot.autoPatchUninstallOnConflictWithShizuku?.let {
            autoPatchUninstallOnConflictWithShizuku.value = it
        }
        snapshot.autoPatchEnabled?.let { enabled ->
            if (enabled) {
                autoPatchEnabled.value = true
                autoPatchShizukuSettingsRestorePending.value = false
            } else {
                autoPatchShizukuSettingsRestorePending.value = false
                applyAutoPatchEnabledState(false)
            }
        }
        snapshot.autoPatchRequiresCharging?.let { autoPatchRequiresCharging.value = it }
        autoPatchInterval.value = normalizedImportedAutoPatchInterval(
            snapshot.autoPatchInterval ?: autoPatchInterval.value
        )
        snapshot.autoPatchEnabledPackages?.let { autoPatchEnabledPackages.value = it }
        snapshot.savedAppLauncherShortcutPackages?.let {
            savedAppLauncherShortcutPackages.value = it
        }
        snapshot.allowExternalBatchActions?.let { allowExternalBatchActions.value = it }
        snapshot.keystoreAlias?.let { keystoreAlias.value = it }
        snapshot.keystorePass?.let { keystorePass.value = it }
        snapshot.keystoreKeyPass?.let { keystoreKeyPass.value = it }
        snapshot.dashboardBundlesFabCollapsed?.let { dashboardBundlesFabCollapsed.value = it }
        snapshot.dashboardAppsFabCollapsed?.let { dashboardAppsFabCollapsed.value = it }
        val legacyBannerState = snapshot.dashboardProgressBannerCollapsed
        (snapshot.dashboardBundleImportBannerCollapsed ?: legacyBannerState)?.let {
            dashboardBundleImportBannerCollapsed.value = it
        }
        (snapshot.dashboardBundleUpdateBannerCollapsed ?: legacyBannerState)?.let {
            dashboardBundleUpdateBannerCollapsed.value = it
        }
        dashboardBundleBannerStateMigrated.value = true
    }

    private fun EditorContext.importPatchingSettings(snapshot: SettingsSnapshot) {
        snapshot.disablePatchVersionCompatCheck?.let { disablePatchVersionCompatCheck.value = it }
        snapshot.disableSelectionWarning?.let { disableSelectionWarning.value = it }
        snapshot.disableUniversalPatchCheck?.let { disableUniversalPatchCheck.value = it }
        snapshot.suggestedVersionSafeguard?.let { suggestedVersionSafeguard.value = it }
        snapshot.disablePatchSelectionConfirmations?.let { disablePatchSelectionConfirmations.value = it }
        snapshot.showPatchSelectionSummary?.let { showPatchSelectionSummary.value = it }
        snapshot.collapsePatchActionsOnSelection?.let { collapsePatchActionsOnSelection.value = it }
        snapshot.patchSelectionFilterFlags?.let { patchSelectionFilterFlags.value = it }
        snapshot.patchSelectionShowNewPatches?.let { patchSelectionShowNewPatches.value = it }
        snapshot.patchSelectionSortAlphabetical?.let { patchSelectionSortAlphabetical.value = it }
        snapshot.patchSelectionSortDescending?.let { patchSelectionSortDescending.value = it }
        snapshot.patchSelectionSortSettingsMode?.let { patchSelectionSortSettingsMode.value = it }
        snapshot.patchSelectionSortSelectionMode?.let { patchSelectionSortSelectionMode.value = it }
        snapshot.patchSelectionActionOrder?.let { patchSelectionActionOrder.value = it }
        snapshot.patchSelectionHiddenActions?.let { patchSelectionHiddenActions.value = it }
        if (snapshot.patchSelectionShowVersionTags != null || snapshot.patchSelectionShowOptionPreviews != null) {
            patchSelectionDisplayRestoreFlags.value =
                snapshot.patchSelectionDisplayRestoreFlags?.takeIf { it in 0..3 } ?: 0
        }
        snapshot.patchSelectionShowVersionTags?.let { patchSelectionShowVersionTags.value = it }
        snapshot.patchSelectionShowOptionPreviews?.let {
            patchSelectionShowOptionPreviews.value = it
        }
        snapshot.patchBundleActionOrder?.let { patchBundleActionOrder.value = it }
        snapshot.patchBundleHiddenActions?.let { patchBundleHiddenActions.value = it }
        snapshot.batchResultActionOrder?.let { batchResultActionOrder.value = it }
        snapshot.batchResultHiddenActions?.let { batchResultHiddenActions.value = it }
        snapshot.savedAppActionOrder?.let { savedAppActionOrder.value = it }
        snapshot.savedAppHiddenActions?.let { savedAppHiddenActions.value = it }
        snapshot.patchProfileActionOrder?.let { patchProfileActionOrder.value = it }
        snapshot.patchProfileHiddenActions?.let { patchProfileHiddenActions.value = it }
        snapshot.lsposedModuleActionOrder?.let { lsposedModuleActionOrder.value = it }
        snapshot.lsposedModuleHiddenActions?.let { lsposedModuleHiddenActions.value = it }
    }

    private fun EditorContext.importDiscoverySettings(snapshot: SettingsSnapshot) {
        snapshot.patchBundleDiscoverySortMode?.let { patchBundleDiscoverySortMode.value = it }
        snapshot.youtubeAssetsSyncHeaderTransforms?.let { youtubeAssetsSyncHeaderTransforms.value = it }
        snapshot.acknowledgedDownloaderPlugins?.let { acknowledgedDownloaderPlugins.value = it }
        snapshot.downloaderPluginSourcesJson?.let { downloaderPluginSourcesJson.value = it }
        snapshot.trustedApkDownloadHelpersJson?.let { trustedApkDownloadHelpersJson.value = it }
        snapshot.acknowledgedPatcherRuntimePlugins?.let {
            acknowledgedPatcherRuntimePlugins.value = it
        }
        snapshot.trustedPatcherRuntimePluginsJson?.let {
            trustedPatcherRuntimePluginsJson.value = it
        }
        snapshot.patcherRuntimePluginSourcesJson?.let {
            patcherRuntimePluginSourcesJson.value = it
        }
        snapshot.autoSaveDownloaderApks?.let { autoSaveDownloaderApks.value = it }
        snapshot.autoSaveDownloaderLatestOnly?.let { autoSaveDownloaderLatestOnly.value = it }
        snapshot.pathSelectorFavorites?.let { favorites ->
            val sanitized = favorites.filter { path ->
                runCatching { Paths.get(path).isReadable() }.getOrDefault(false)
            }.toSet()
            pathSelectorFavorites.value = sanitized
        }
        // Code adapted from Morphe, see third-party/NOTICE for more information
        // https://github.com/MorpheApp/morphe-manager/commit/46e37e2915dc92b6a655127155935e63c0b04efc
        snapshot.pathSelectorLastDirectory?.let { directory ->
            sanitizeImportedPickerDirectory(directory)?.let { pathSelectorLastDirectory.value = it }
        }
        snapshot.pathSelectorSortMode?.takeIf {
            it in setOf(
                "NAME_ASC", "NAME_DESC", "MODIFIED_DESC", "MODIFIED_ASC",
                "TYPE_ASC", "TYPE_DESC", "SIZE_DESC", "SIZE_ASC"
            )
        }?.let { pathSelectorSortMode.value = it }
        snapshot.pathSelectorSearchQuery?.let { pathSelectorSearchQuery.value = it }
        snapshot.filePickerLastDirectories?.forEach { (name, directory) ->
            val preference = filePickerDirectoryPreferences[name] ?: return@forEach
            sanitizeImportedPickerDirectory(directory)?.let { preference.value = it }
        }
        snapshot.minimalActionButtonGroups?.let { minimalActionButtonGroups.value = it }
        snapshot.dashboardAppsActionOrder?.let { dashboardAppsActionOrder.value = it }
        snapshot.dashboardBundlesActionOrder?.let { dashboardBundlesActionOrder.value = it }
        snapshot.dashboardHiddenActions?.let { dashboardHiddenActions.value = it }
        snapshot.pathSelectorShowHiddenFiles?.let { pathSelectorShowHiddenFiles.value = it }
        snapshot.appSelectorFilterInstalledOnly?.let { appSelectorFilterInstalledOnly.value = it }
        snapshot.appSelectorFilterPatchesAvailable?.let { appSelectorFilterPatchesAvailable.value = it }
        snapshot.splitMergeSelectionPreset?.takeIf { it.isNotBlank() }?.let {
            splitMergeSelectionPreset.value = it
        }
        snapshot.splitMergeExcludeUnusedLanguages?.let { splitMergeExcludeUnusedLanguages.value = it }
        snapshot.splitMergeExcludeExtraDensities?.let { splitMergeExcludeExtraDensities.value = it }
        snapshot.splitMergeExcludeExtraNativeLibs?.let { splitMergeExcludeExtraNativeLibs.value = it }
        val importedSplitMergeSortMode =
            snapshot.splitMergeModuleSortMode?.takeIf { it.isNotBlank() }
        (snapshot.patcherSplitModuleSortMode?.takeIf { it.isNotBlank() }
            ?: importedSplitMergeSortMode)?.let {
            patcherSplitModuleSortMode.value = it
        }
        importedSplitMergeSortMode?.let {
            splitMergeModuleSortMode.value = it
        }
        if (
            snapshot.patcherSplitModuleSortMode?.isNotBlank() == true ||
            importedSplitMergeSortMode != null
        ) {
            splitModuleSortModeSeparationMigrated.value = true
        }
        snapshot.splitMergeInstalledFilterUserApps?.let { splitMergeInstalledFilterUserApps.value = it }
        snapshot.splitMergeInstalledFilterSystemApps?.let { splitMergeInstalledFilterSystemApps.value = it }
        snapshot.splitMergeInstalledFilterSplitApks?.let { splitMergeInstalledFilterSplitApks.value = it }
        snapshot.splitMergeInstalledFilterSingleApks?.let { splitMergeInstalledFilterSingleApks.value = it }
        snapshot.splitMergeAutoCollapseSteps?.let { splitMergeAutoCollapseSteps.value = it }
        snapshot.showSplitMergeMemoryUsageGraph?.let { showSplitMergeMemoryUsageGraph.value = it }
        snapshot.compactSplitMergeResourceGraphs?.let { compactSplitMergeResourceGraphs.value = it }
        snapshot.showSplitMergeResourceGraphExtraInfo?.let { showSplitMergeResourceGraphExtraInfo.value = it }
        snapshot.showCompactSplitMergeResourceGraphExtraInfo?.let { showCompactSplitMergeResourceGraphExtraInfo.value = it }
        snapshot.splitMergeInformationExpanded?.let { splitMergeInformationExpanded.value = it }
        snapshot.splitMergeAutoExpandRunningSteps?.let { splitMergeAutoExpandRunningSteps.value = it }
        snapshot.splitMergeAutoExpandRunningStepsExclusive?.let {
            splitMergeAutoExpandRunningStepsExclusive.value = it
        }
        snapshot.useCustomFilePicker?.let { useCustomFilePicker.value = it }
        snapshot.patchBundleDiscoveryShowRelease?.let { patchBundleDiscoveryShowRelease.value = it }
        snapshot.patchBundleDiscoveryShowPrerelease?.let { patchBundleDiscoveryShowPrerelease.value = it }
        snapshot.patchBundleDiscoveryLatest?.let { patchBundleDiscoveryLatest.value = it }
        snapshot.searchEngineHost?.let { searchEngineHost.value = it }
    }

}

object InstallerPreferenceTokens {
    const val INTERNAL = ":internal:"
    const val SYSTEM = ":system:"
    const val ROOT = ":root:" // Legacy value, mapped to AUTO_SAVED.
    const val AUTO_SAVED = ":auto_saved:"
    // Code adapted from Morphe, see third-party/NOTICE for more information
    // https://github.com/MorpheApp/morphe-manager/commit/7e24461c1454b712da4df21440db6f417c94ce58
    const val PLAY_STORE = ":play_store:"
    const val ROOT_PLAY_STORE = ":root_play_store:"
    const val SHIZUKU = ":shizuku:"
    const val SHIZUKU_PLAY_STORE = ":shizuku_play_store:"
    const val SHIZUKU_GOOGLE_PLAY = ":shizuku_google_play:"
    const val NONE = ":none:"
}

suspend fun PreferencesManager.hideInstallerComponent(component: ComponentName) = edit {
    val flattened = component.flattenToString()
    installerHiddenComponents.value = installerHiddenComponents.value + flattened
}

suspend fun PreferencesManager.showInstallerComponent(component: ComponentName) = edit {
    val flattened = component.flattenToString()
    installerHiddenComponents.value = installerHiddenComponents.value - flattened
}





