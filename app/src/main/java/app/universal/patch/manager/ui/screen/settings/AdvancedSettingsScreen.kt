package app.universal.patch.manager.ui.screen.settings

import app.universal.patch.manager.ui.component.minimalActionButtons
import android.Manifest
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.net.Uri
import android.view.HapticFeedbackConstants
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.PlaylistAddCheck
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Api
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.LayersClear
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Redo
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.consumeAllChanges
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import androidx.core.content.getSystemService
import androidx.lifecycle.viewModelScope
import app.universal.patch.manager.BuildConfig
import app.universal.patch.manager.R
import app.universal.patch.manager.ui.component.AppTopBar
import app.universal.patch.manager.ui.component.ColumnWithScrollbar
import app.universal.patch.manager.ui.component.CenteredDialogTitle
import app.universal.patch.manager.ui.component.GroupHeader
import app.universal.patch.manager.ui.component.SettingsSectionIcons
import app.universal.patch.manager.ui.component.RememberedCreateDocument
import app.universal.patch.manager.ui.component.toPickerDirectoryUri
import app.universal.patch.manager.ui.component.splitTrailingPunctuation
import app.universal.patch.manager.ui.component.settings.BooleanItem
import app.universal.patch.manager.ui.component.settings.ExpandableBooleanItem
import app.universal.patch.manager.ui.component.settings.SafeguardBooleanItem
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsCard
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsConfigurableItem
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsDivider
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsItem
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsSwitch
import app.universal.patch.manager.ui.component.settings.SettingsSearchHighlight
import app.universal.patch.manager.ui.component.patcher.InstallerPickerDialog
import app.universal.patch.manager.ui.component.patcher.ShizukuConfigurationDialog
import app.universal.patch.manager.domain.installer.InstallerManager
import app.universal.patch.manager.domain.manager.SearchForUpdatesBackgroundInterval
import app.universal.patch.manager.patcher.logger.displayName
import app.universal.patch.manager.patcher.logger.PatcherLogMode
import app.universal.patch.manager.patcher.runtime.morphe.MorpheBytecodeMode
import app.universal.patch.manager.ui.viewmodel.AdvancedSettingsViewModel
import app.universal.patch.manager.util.AppLanguageOption
import app.universal.patch.manager.util.ExportNameFormatter
import app.universal.patch.manager.util.appLanguageOptions
import app.universal.patch.manager.util.applyAppLanguage
import app.universal.patch.manager.util.isAppLanguageApplied
import app.universal.patch.manager.util.consumeHorizontalScroll
import app.universal.patch.manager.util.openUrl
import app.universal.patch.manager.util.permission.hasNotificationPermission
import app.universal.patch.manager.util.toast
import app.universal.patch.manager.util.transparentListItemColors
import app.universal.patch.manager.util.withHapticFeedback
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universal.patch.manager.ui.model.PatchSelectionActionKey
import app.universal.patch.manager.ui.model.PatchBundleActionKey
import app.universal.patch.manager.ui.model.BatchResultActionKey
import app.universal.patch.manager.ui.model.SavedAppActionKey
import app.universal.patch.manager.ui.model.PatchProfileActionKey
import app.universal.patch.manager.ui.model.LsposedModuleActionKey
import app.universal.patch.manager.ui.model.navigation.Settings as NavigationSettings
import app.universal.patch.manager.ui.screen.settings.SettingsSearchState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.math.ceil
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, FlowPreview::class)
@Composable
fun AdvancedSettingsScreen(
    onBackClick: () -> Unit,
    mode: AdvancedSettingsMode = AdvancedSettingsMode.APP_MANAGER,
    viewModel: AdvancedSettingsViewModel = koinViewModel()
) {
    val context = LocalContext.current
    val installerManager: InstallerManager = koinInject()
    var installerDialogTarget by rememberSaveable { mutableStateOf<InstallerDialogTarget?>(null) }
    var showCustomInstallerDialog by rememberSaveable { mutableStateOf(false) }
    var showShizukuConfigurationDialog by rememberSaveable { mutableStateOf(false) }
    var showAutoPatchIntervalDialog by rememberSaveable { mutableStateOf(false) }
    var pendingAutoPatchShizukuPermission by rememberSaveable { mutableStateOf(false) }
    val shizukuPermissionListener = remember {
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (
                requestCode != AUTO_PATCH_SHIZUKU_PERMISSION_REQUEST_CODE ||
                !pendingAutoPatchShizukuPermission
            ) {
                return@OnRequestPermissionResultListener
            }
            pendingAutoPatchShizukuPermission = false
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                viewModel.setAutoPatchInstallWithShizuku(true)
            } else {
                context.toast(
                    context.getString(R.string.auto_patch_shizuku_permission_denied)
                )
            }
        }
    }
    DisposableEffect(shizukuPermissionListener) {
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        onDispose {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        }
    }
    val autoPatchNotificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.setAutoPatchEnabled(true)
        }
    }
    val setAutoPatchEnabled: (Boolean) -> Unit = { enabled ->
        if (!enabled) {
            pendingAutoPatchShizukuPermission = false
            viewModel.setAutoPatchEnabled(false)
        } else if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !context.hasNotificationPermission()
        ) {
            autoPatchNotificationPermissionLauncher.launch(
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            viewModel.setAutoPatchEnabled(true)
        }
    }
    val setAutoPatchInstallWithShizuku: (Boolean) -> Unit = { enabled ->
        if (!enabled) {
            pendingAutoPatchShizukuPermission = false
            viewModel.setAutoPatchInstallWithShizuku(false)
        } else {
            val status = installerManager.shizukuStatus(
                InstallerManager.InstallTarget.PATCHER
            )
            when {
                status.permissionGranted ->
                    viewModel.setAutoPatchInstallWithShizuku(true)
                status.installed && status.supported && status.running -> {
                    pendingAutoPatchShizukuPermission = true
                    runCatching {
                        Shizuku.requestPermission(
                            AUTO_PATCH_SHIZUKU_PERMISSION_REQUEST_CODE
                        )
                    }.onFailure {
                        pendingAutoPatchShizukuPermission = false
                        context.toast(
                            context.getString(
                                R.string.auto_patch_shizuku_permission_request_failed
                            )
                        )
                    }
                }
                else -> {
                    status.availability.reason?.let { reason ->
                        context.toast(context.getString(reason))
                    }
                    installerManager.openShizukuApp()
                }
            }
        }
    }
    val searchTarget by SettingsSearchState.target.collectAsStateWithLifecycle()
    var highlightTarget by rememberSaveable { mutableStateOf<Int?>(null) }
    var autoPatchShizukuConflictOptionsExpanded by rememberSaveable {
        mutableStateOf(false)
    }
    val appLanguage by viewModel.prefs.appLanguage.getAsState()
    var showLanguageDialog by rememberSaveable { mutableStateOf(false) }
    var pendingLanguageRestart by rememberSaveable { mutableStateOf<String?>(null) }
    val languageOptions = appLanguageOptions
    val memoryLimit = remember {
        val activityManager = context.getSystemService<ActivityManager>()!!
        context.getString(
            R.string.device_memory_limit_format,
            activityManager.memoryClass,
            activityManager.largeMemoryClass
        )
    }
    val exportFormat by viewModel.prefs.patchedAppExportFormat.getAsState()
    val mergedApkExportFormat by viewModel.prefs.mergedApkExportFormat.getAsState()
    var showExportFormatDialog by rememberSaveable { mutableStateOf(false) }
    var showMergedApkExportFormatDialog by rememberSaveable { mutableStateOf(false) }
    if (showExportFormatDialog) {
        ExportNameFormatDialog(
            currentValue = exportFormat,
            onDismiss = { showExportFormatDialog = false },
            onSave = {
                viewModel.setPatchedAppExportFormat(it)
                showExportFormatDialog = false
            }
        )
    }
    if (showMergedApkExportFormatDialog) {
        ExportNameFormatDialog(
            currentValue = mergedApkExportFormat,
            onDismiss = { showMergedApkExportFormatDialog = false },
            onSave = {
                viewModel.setMergedApkExportFormat(it)
                showMergedApkExportFormatDialog = false
            },
            titleRes = R.string.merged_apk_name_format_dialog_title,
            supportingRes = R.string.merged_apk_name_format_dialog_supporting,
            fieldLabelRes = R.string.merged_apk_name_format,
            resetLabelRes = R.string.merged_apk_name_format_reset,
            defaultFormatTemplate = ExportNameFormatter.DEFAULT_MERGED_APK_TEMPLATE,
            formatPreview = {
                ExportNameFormatter.preview(
                    it,
                    ExportNameFormatter.mergedApkPreviewData(),
                    ExportNameFormatter.DEFAULT_MERGED_APK_TEMPLATE
                )
            },
            availableVariables = ExportNameFormatter.availableMergedApkVariables()
        )
    }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    LaunchedEffect(searchTarget, mode) {
        val target = searchTarget
        if (target?.destination == mode.destination) {
            if (
                target.targetId == R.string.installer_shizuku_configure_title ||
                target.targetId == R.string.installer_play_store_mode ||
                target.targetId == R.string.settings_auto_install_with_shizuku ||
                target.targetId == R.string.settings_auto_uninstall_with_shizuku
            ) {
                showShizukuConfigurationDialog = true
            } else {
                highlightTarget = target.targetId
            }
            SettingsSearchState.clear()
        }
    }
    val commitLanguageChange: (String, Boolean) -> Unit = { code, applyNow ->
        pendingLanguageRestart = null
        viewModel.viewModelScope.launch {
            viewModel.prefs.appLanguage.update(code)
            if (applyNow) {
                // AppCompat recreates the activity when the locale changes.
                applyAppLanguage(code)
            }
        }
    }
    if (showLanguageDialog) {
        LanguageDialog(
            options = languageOptions,
            selectedCode = appLanguage,
            onSelect = { code ->
                if (code == appLanguage && isAppLanguageApplied(code)) {
                    showLanguageDialog = false
                } else {
                    pendingLanguageRestart = code
                    showLanguageDialog = false
                }
            },
            onDismiss = { showLanguageDialog = false }
        )
    }
    pendingLanguageRestart?.let { languageCode ->
        AlertDialog(
            onDismissRequest = { commitLanguageChange(languageCode, false) },
            confirmButton = {
                TextButton(onClick = { commitLanguageChange(languageCode, true) }) {
                    Text(stringResource(R.string.language_restart_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { commitLanguageChange(languageCode, false) }) {
                    Text(stringResource(R.string.language_restart_later))
                }
            },
            title = { CenteredDialogTitle(stringResource(R.string.language_restart_title)) },
            text = {
                Text(
                    text = stringResource(R.string.language_restart_message),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        )
    }
    val canResetLanguage = appLanguage != viewModel.prefs.appLanguage.default ||
        !isAppLanguageApplied(viewModel.prefs.appLanguage.default)
    val selectedLanguageLabel = when (appLanguage) {
        "system" -> R.string.language_option_system
        else -> languageOptions.firstOrNull { it.code == appLanguage }?.labelRes
            ?: R.string.language_option_english
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(mode.titleRes),
                scrollBehavior = scrollBehavior,
                onBackClick = onBackClick
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { paddingValues ->
        ColumnWithScrollbar(
            thumbLength = app.universal.patch.manager.ui.component.DASHBOARD_SCROLLBAR_THUMB_LENGTH,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (
                mode == AdvancedSettingsMode.APP_MANAGER ||
                mode == AdvancedSettingsMode.ADVANCED_SYSTEM
            ) {
            val searchEngineHost by viewModel.prefs.searchEngineHost.getAsState()
            val announcementSystemEnabled by viewModel.prefs.announcementSystemEnabled.getAsState()
            var showSearchEngineDialog by rememberSaveable { mutableStateOf(false) }
            SettingsSearchHighlight(
                targetKey = R.string.app_behavior_section,
                activeKey = highlightTarget,
                onHighlightComplete = { highlightTarget = null }
            ) { highlightModifier ->
                GroupHeader(
                    stringResource(R.string.app_behavior_section),
                    icon = SettingsSectionIcons.AppBehavior,
                    modifier = highlightModifier
                )
            }
            ExpressiveSettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) {
                SettingsSearchHighlight(
                    targetKey = R.string.app_language,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpressiveSettingsConfigurableItem(
                        modifier = highlightModifier,
                        headlineContent = stringResource(R.string.app_language),
                        supportingContent = stringResource(selectedLanguageLabel),
                        secondaryActionLabel = stringResource(R.string.reset),
                        onSecondaryAction = {
                            if (canResetLanguage) {
                                pendingLanguageRestart = viewModel.prefs.appLanguage.default
                            }
                        },
                        secondaryActionEnabled = canResetLanguage,
                        primaryActionLabel = stringResource(R.string.edit),
                        onPrimaryAction = { showLanguageDialog = true }
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.use_custom_file_picker_title,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    BooleanItem(
                        modifier = highlightModifier,
                        preference = viewModel.prefs.useCustomFilePicker,
                        coroutineScope = viewModel.viewModelScope,
                        headline = R.string.use_custom_file_picker_title,
                        description = R.string.use_custom_file_picker_description,
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.announcement_system_enabled,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    BooleanItem(
                        modifier = highlightModifier,
                        value = announcementSystemEnabled,
                        onValueChange = viewModel::updateAnnouncementSystemEnabled,
                        headline = R.string.announcement_system_enabled,
                        description = R.string.announcement_system_enabled_description
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.search_engine_host_title,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpressiveSettingsConfigurableItem(
                        modifier = highlightModifier,
                        headlineContent = stringResource(R.string.search_engine_host_title),
                        supportingContent = stringResource(
                            R.string.search_engine_host_description,
                            searchEngineHost
                        ),
                        secondaryActionLabel = stringResource(R.string.reset),
                        onSecondaryAction = {
                            viewModel.setSearchEngineHost(viewModel.prefs.searchEngineHost.default)
                        },
                        secondaryActionEnabled = searchEngineHost != viewModel.prefs.searchEngineHost.default,
                        primaryActionLabel = stringResource(R.string.edit),
                        onPrimaryAction = { showSearchEngineDialog = true }
                    )
                }
            }

            SettingsSearchHighlight(
                targetKey = R.string.network_integrations_section,
                activeKey = highlightTarget,
                onHighlightComplete = { highlightTarget = null }
            ) { highlightModifier ->
                GroupHeader(
                    stringResource(R.string.network_integrations_section),
                    icon = SettingsSectionIcons.NetworkIntegrations,
                    modifier = highlightModifier
                )
            }

            val apiUrl by viewModel.prefs.api.getAsState()
            val gitHubPat by viewModel.prefs.gitHubPat.getAsState()
            var showApiUrlDialog by rememberSaveable { mutableStateOf(false) }
            var showGitHubPatDialog by rememberSaveable { mutableStateOf(false) }

            if (showApiUrlDialog) {
                APIUrlDialog(
                    currentUrl = apiUrl,
                    defaultUrl = viewModel.prefs.api.default,
                    onSubmit = {
                        showApiUrlDialog = false
                        it?.let(viewModel::setApiUrl)
                    }
                )
            }
            if (showGitHubPatDialog) {
                GitHubPatDialog(
                    currentPat = gitHubPat,
                    onSubmit = { pat ->
                        showGitHubPatDialog = false
                        viewModel.setGitHubPat(pat)
                    },
                    onDismiss = { showGitHubPatDialog = false }
                )
            }
            if (showSearchEngineDialog) {
                SearchEngineHostDialog(
                    currentHost = searchEngineHost,
                    defaultHost = viewModel.prefs.searchEngineHost.default,
                    onSubmit = {
                        showSearchEngineDialog = false
                        it?.let(viewModel::setSearchEngineHost)
                    },
                    onDismiss = { showSearchEngineDialog = false }
                )
            }
            ExpressiveSettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) {
                SettingsSearchHighlight(
                    targetKey = R.string.api_url,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpressiveSettingsConfigurableItem(
                        modifier = highlightModifier,
                        headlineContent = stringResource(R.string.api_url),
                        supportingContent = stringResource(R.string.api_url_description),
                        secondaryActionLabel = stringResource(R.string.reset),
                        onSecondaryAction = { viewModel.setApiUrl(viewModel.prefs.api.default) },
                        secondaryActionEnabled = apiUrl != viewModel.prefs.api.default,
                        primaryActionLabel = stringResource(R.string.edit),
                        onPrimaryAction = { showApiUrlDialog = true }
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.github_pat,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpressiveSettingsConfigurableItem(
                        modifier = highlightModifier,
                        headlineContent = stringResource(R.string.github_pat),
                        supportingContent = stringResource(R.string.github_pat_description),
                        secondaryActionLabel = stringResource(R.string.reset),
                        onSecondaryAction = { viewModel.setGitHubPat(viewModel.prefs.gitHubPat.default) },
                        secondaryActionEnabled = gitHubPat != viewModel.prefs.gitHubPat.default,
                        primaryActionLabel = stringResource(R.string.edit),
                        onPrimaryAction = { showGitHubPatDialog = true }
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.include_github_pat_in_exports_label,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    BooleanItem(
                        modifier = highlightModifier,
                        preference = viewModel.prefs.includeGitHubPatInExports,
                        coroutineScope = viewModel.viewModelScope,
                        headline = R.string.include_github_pat_in_exports_label,
                        description = R.string.include_github_pat_in_exports_supporting
                    )
                }
            }

            SettingsSearchHighlight(
                targetKey = R.string.installer_section,
                activeKey = highlightTarget,
                onHighlightComplete = { highlightTarget = null }
            ) { highlightModifier ->
                GroupHeader(
                    stringResource(R.string.installer_section),
                    icon = SettingsSectionIcons.Installer,
                    modifier = highlightModifier
                )
            }
            val installTarget = InstallerManager.InstallTarget.PATCHER
            val primaryPreference by viewModel.prefs.installerPrimary.getAsState()
            val fallbackPreference by viewModel.prefs.installerFallback.getAsState()
            val chooseInstallerPerInstall by viewModel.prefs.chooseInstallerPerInstall.getAsState()
            val shizukuInstallAsPlayStore by viewModel.prefs.shizukuInstallAsPlayStore.getAsState()
            val autoInstallWithShizuku by viewModel.prefs.autoInstallWithShizuku.getAsState()
            val autoUninstallWithShizuku by viewModel.prefs.autoUninstallWithShizuku.getAsState()
            val autoPatchEnabled by viewModel.prefs.autoPatchEnabled.getAsState()
            val autoPatchInstallWithShizuku by
                viewModel.prefs.autoPatchInstallWithShizuku.getAsState()
            val autoPatchUninstallOnConflictWithShizuku by
                viewModel.prefs.autoPatchUninstallOnConflictWithShizuku.getAsState()
            LaunchedEffect(autoPatchEnabled, autoPatchInstallWithShizuku, highlightTarget) {
                if (!autoPatchEnabled || !autoPatchInstallWithShizuku) {
                    autoPatchShizukuConflictOptionsExpanded = false
                } else if (highlightTarget == R.string.auto_patch_shizuku_uninstall_on_conflict) {
                    autoPatchShizukuConflictOptionsExpanded = true
                }
            }
            val autoPatchRequiresCharging by viewModel.prefs.autoPatchRequiresCharging.getAsState()
            val autoPatchInterval by viewModel.prefs.autoPatchInterval.getAsState()
            val allowExternalBatchActions by viewModel.prefs.allowExternalBatchActions.getAsState()
            val primaryToken = remember(primaryPreference) { installerManager.parseToken(primaryPreference) }
            val fallbackToken = remember(fallbackPreference) { installerManager.parseToken(fallbackPreference) }
            fun ensureSelection(
                entries: List<InstallerManager.Entry>,
                token: InstallerManager.Token,
                includeNone: Boolean,
                blockedToken: InstallerManager.Token? = null
            ): List<InstallerManager.Entry> {
                val normalized = buildList {
                    val seen = mutableSetOf<Any>()
                    entries.forEach { entry ->
                        val key = when (val entryToken = entry.token) {
                            is InstallerManager.Token.Component -> entryToken.componentName
                            else -> entryToken
                        }
                        if (seen.add(key)) add(entry)
                    }
                }
                val ensured = if (
                    token == InstallerManager.Token.Internal ||
                    token == InstallerManager.Token.PlayStore ||
                    token == InstallerManager.Token.RootPlayStore ||
                    token == InstallerManager.Token.AutoSaved ||
                    (token == InstallerManager.Token.None && includeNone) ||
                    normalized.any { tokensEqual(it.token, token) }
                ) {
                    normalized
                } else {
                    val described = installerManager.describeEntry(token, installTarget) ?: return normalized
                    normalized + described
                }

                if (blockedToken == null) return ensured

                return ensured.map { entry ->
                    if (!tokensEqual(entry.token, token) && tokensEqual(entry.token, blockedToken)) {
                        entry.copy(availability = entry.availability.copy(available = false))
                    } else entry
                }
            }

            var primaryEntries by remember(primaryToken, fallbackToken) {
                mutableStateOf(
                    ensureSelection(
                        installerManager.listEntries(installTarget, includeNone = false),
                        primaryToken,
                        includeNone = false,
                        blockedToken = fallbackToken.takeUnless { tokensEqual(it, InstallerManager.Token.None) }
                    )
                )
            }
            var fallbackEntries by remember(primaryToken, fallbackToken) {
                mutableStateOf(
                    ensureSelection(
                        installerManager.listEntries(installTarget, includeNone = true),
                        fallbackToken,
                        includeNone = true,
                        blockedToken = primaryToken
                    )
                )
            }

            LaunchedEffect(installTarget, primaryToken, fallbackToken) {
                while (isActive) {
                    val updatedPrimary = ensureSelection(
                        installerManager.listEntries(installTarget, includeNone = false),
                        primaryToken,
                        includeNone = false,
                        blockedToken = fallbackToken.takeUnless { tokensEqual(it, InstallerManager.Token.None) }
                    )
                    val updatedFallback = ensureSelection(
                        installerManager.listEntries(installTarget, includeNone = true),
                        fallbackToken,
                        includeNone = true,
                        blockedToken = primaryToken
                    )

                    primaryEntries = updatedPrimary
                    fallbackEntries = updatedFallback
                    delay(1_500)
                }
            }

            val primaryEntry = primaryEntries.find { it.token == primaryToken }
                ?: installerManager.describeEntry(primaryToken, installTarget)
                ?: primaryEntries.first()
            val fallbackEntry = fallbackEntries.find { it.token == fallbackToken }
                ?: installerManager.describeEntry(fallbackToken, installTarget)
                ?: fallbackEntries.first()

            @Composable
            fun entrySupporting(entry: InstallerManager.Entry): String? {
                val lines = buildList {
                    entry.description?.takeIf { it.isNotBlank() }?.let { add(it) }
                    entry.availability.reason?.let { add(stringResource(it)) }
                }
                return if (lines.isEmpty()) null else lines.joinToString("\n")
            }

            val primarySupporting = entrySupporting(primaryEntry)
            val fallbackSupporting = entrySupporting(fallbackEntry)
            fun installerLeadingContent(
                entry: InstallerManager.Entry,
                selected: Boolean
            ): (@Composable () -> Unit)? = when (entry.token) {
                InstallerManager.Token.Internal,
                InstallerManager.Token.None,
                InstallerManager.Token.AutoSaved -> null
                InstallerManager.Token.PlayStore,
                InstallerManager.Token.RootPlayStore,
                InstallerManager.Token.Shizuku,
                InstallerManager.Token.ShizukuGooglePlay,
                is InstallerManager.Token.Component -> entry.icon?.let { drawable ->
                    {
                        InstallerIcon(
                            drawable = drawable,
                            selected = selected,
                            enabled = entry.availability.available || selected
                        )
                    }
                }
            }

            val primaryLeadingContent = installerLeadingContent(primaryEntry, primaryEntry.token == primaryToken)
            val fallbackLeadingContent = installerLeadingContent(fallbackEntry, fallbackEntry.token == fallbackToken)

            ExpressiveSettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) {
                SettingsSearchHighlight(
                    targetKey = R.string.disable_apk_signature_checks,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    SafeguardBooleanItem(
                        modifier = highlightModifier,
                        preference = viewModel.prefs.disableApkSignatureChecks,
                        coroutineScope = viewModel.viewModelScope,
                        headline = R.string.disable_apk_signature_checks,
                        description = R.string.disable_apk_signature_checks_description,
                        confirmationText = R.string.disable_apk_signature_checks_confirmation
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.installer_choose_per_install_title,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpressiveSettingsItem(
                        modifier = highlightModifier,
                        headlineContent = stringResource(R.string.installer_choose_per_install_title),
                        supportingContent = stringResource(R.string.installer_choose_per_install_description),
                        trailingContent = {
                            ExpressiveSettingsSwitch(
                                checked = chooseInstallerPerInstall,
                                onCheckedChange = viewModel::setChooseInstallerPerInstall
                            )
                        },
                        onClick = {
                            viewModel.setChooseInstallerPerInstall(!chooseInstallerPerInstall)
                        }
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.installer_primary_title,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpressiveSettingsConfigurableItem(
                        modifier = highlightModifier,
                        headlineContent = stringResource(R.string.installer_primary_title),
                        supportingContent = primarySupporting,
                        leadingContent = primaryLeadingContent,
                        enabled = !chooseInstallerPerInstall,
                        secondaryActionLabel = stringResource(R.string.reset),
                        onSecondaryAction = {
                            viewModel.setPrimaryInstaller(
                                installerManager.parseToken(viewModel.prefs.installerPrimary.default)
                            )
                        },
                        secondaryActionEnabled = !chooseInstallerPerInstall &&
                            primaryPreference != viewModel.prefs.installerPrimary.default,
                        primaryActionLabel = stringResource(R.string.settings),
                        onPrimaryAction = { installerDialogTarget = InstallerDialogTarget.Primary },
                        primaryActionEnabled = !chooseInstallerPerInstall
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.installer_fallback_title,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpressiveSettingsConfigurableItem(
                        modifier = highlightModifier,
                        headlineContent = stringResource(R.string.installer_fallback_title),
                        supportingContent = fallbackSupporting,
                        leadingContent = fallbackLeadingContent,
                        enabled = !chooseInstallerPerInstall,
                        secondaryActionLabel = stringResource(R.string.reset),
                        onSecondaryAction = {
                            viewModel.setFallbackInstaller(
                                installerManager.parseToken(viewModel.prefs.installerFallback.default)
                            )
                        },
                        secondaryActionEnabled = !chooseInstallerPerInstall &&
                            fallbackPreference != viewModel.prefs.installerFallback.default,
                        primaryActionLabel = stringResource(R.string.settings),
                        onPrimaryAction = { installerDialogTarget = InstallerDialogTarget.Fallback },
                        primaryActionEnabled = !chooseInstallerPerInstall
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.installer_custom_manage_title,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpressiveSettingsItem(
                        modifier = highlightModifier,
                        headlineContent = stringResource(R.string.installer_custom_manage_title),
                        supportingContent = stringResource(R.string.installer_custom_manage_description),
                        onClick = { showCustomInstallerDialog = true }
                    )
                }
            }

            if (showShizukuConfigurationDialog) {
                ShizukuConfigurationDialog(
                    installAsPlayStore = shizukuInstallAsPlayStore,
                    autoInstall = autoInstallWithShizuku,
                    autoUninstallOnConflict = autoUninstallWithShizuku,
                    onInstallAsPlayStoreChange =
                        viewModel::setShizukuInstallAsPlayStore,
                    onAutoInstallChange = viewModel::setAutoInstallWithShizuku,
                    onAutoUninstallOnConflictChange =
                        viewModel::setAutoUninstallWithShizuku,
                    onDismiss = { showShizukuConfigurationDialog = false }
                )
            }

            if (showCustomInstallerDialog) {
                CustomInstallerManagerDialog(
                    installerManager = installerManager,
                    viewModel = viewModel,
                    installTarget = installTarget,
                    onDismiss = { showCustomInstallerDialog = false }
                )
            }

            installerDialogTarget?.let { target ->
                val isPrimary = target == InstallerDialogTarget.Primary
                val options = if (isPrimary) primaryEntries else fallbackEntries
                InstallerPickerDialog(
                    title = stringResource(
                        if (isPrimary) R.string.installer_primary_title else R.string.installer_fallback_title
                    ),
                    options = options,
                    initialSelection = if (isPrimary) primaryToken else fallbackToken,
                    confirmLabel = R.string.save,
                    onDismiss = { installerDialogTarget = null },
                    onConfirm = { selection ->
                        if (isPrimary) {
                            viewModel.setPrimaryInstaller(selection)
                        } else {
                            viewModel.setFallbackInstaller(selection)
                        }
                        installerDialogTarget = null
                    },
                    onOpenShizuku = installerManager::openShizukuApp
                )
            }

            // Code adapted from Morphe, see third-party/NOTICE for more information
            // https://github.com/MorpheApp/morphe-manager/pull/795
            SettingsSearchHighlight(
                targetKey = R.string.auto_patch_section,
                activeKey = highlightTarget,
                onHighlightComplete = { highlightTarget = null }
            ) { highlightModifier ->
                GroupHeader(
                    stringResource(R.string.auto_patch_section),
                    icon = SettingsSectionIcons.Installer,
                    modifier = highlightModifier
                )
            }
            ExpressiveSettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) {
                SettingsSearchHighlight(
                    targetKey = R.string.auto_patch_enabled,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpressiveSettingsItem(
                        modifier = highlightModifier,
                        headlineContent = stringResource(R.string.auto_patch_enabled),
                        supportingContent = stringResource(
                            R.string.auto_patch_enabled_description
                        ),
                        trailingContent = {
                            ExpressiveSettingsSwitch(
                                checked = autoPatchEnabled,
                                onCheckedChange = setAutoPatchEnabled
                            )
                        },
                        onClick = { setAutoPatchEnabled(!autoPatchEnabled) }
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.auto_patch_install_with_shizuku,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    val controlEnabled = !pendingAutoPatchShizukuPermission &&
                        autoPatchEnabled
                    ExpressiveSettingsItem(
                        modifier = highlightModifier,
                        headlineContent = stringResource(
                            R.string.auto_patch_install_with_shizuku
                        ),
                        supportingContent = stringResource(
                            R.string.auto_patch_install_with_shizuku_description
                        ),
                        enabled = controlEnabled,
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    enabled = controlEnabled && autoPatchInstallWithShizuku,
                                    onClick = {
                                        autoPatchShizukuConflictOptionsExpanded =
                                            !autoPatchShizukuConflictOptionsExpanded
                                    }
                                ) {
                                    Icon(
                                        imageVector = if (autoPatchShizukuConflictOptionsExpanded) {
                                            Icons.Outlined.KeyboardArrowUp
                                        } else {
                                            Icons.Outlined.KeyboardArrowDown
                                        },
                                        contentDescription = null
                                    )
                                }
                                ExpressiveSettingsSwitch(
                                    checked = autoPatchInstallWithShizuku,
                                    enabled = controlEnabled,
                                    onCheckedChange = setAutoPatchInstallWithShizuku
                                )
                            }
                        },
                        onClick = {
                            setAutoPatchInstallWithShizuku(
                                !autoPatchInstallWithShizuku
                            )
                        }
                    )
                }
                if (autoPatchShizukuConflictOptionsExpanded && autoPatchEnabled && autoPatchInstallWithShizuku) {
                    ExpressiveSettingsDivider()
                    SettingsSearchHighlight(
                        targetKey = R.string.auto_patch_shizuku_uninstall_on_conflict,
                        activeKey = highlightTarget,
                        onHighlightComplete = { highlightTarget = null }
                    ) { highlightModifier ->
                        ExpressiveSettingsItem(
                            modifier = highlightModifier.padding(start = 24.dp),
                            headlineContent = stringResource(
                                R.string.auto_patch_shizuku_uninstall_on_conflict
                            ),
                            supportingContent = stringResource(
                                R.string.auto_patch_shizuku_uninstall_on_conflict_description
                            ),
                            enabled = autoPatchInstallWithShizuku,
                            trailingContent = {
                                ExpressiveSettingsSwitch(
                                    checked = autoPatchInstallWithShizuku &&
                                        autoPatchUninstallOnConflictWithShizuku,
                                    enabled = autoPatchInstallWithShizuku,
                                    onCheckedChange =
                                        viewModel::setAutoPatchUninstallOnConflictWithShizuku
                                )
                            },
                            onClick = {
                                if (autoPatchInstallWithShizuku) {
                                    viewModel.setAutoPatchUninstallOnConflictWithShizuku(
                                        !autoPatchUninstallOnConflictWithShizuku
                                    )
                                }
                            }
                        )
                    }
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.auto_patch_requires_charging,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpressiveSettingsItem(
                        modifier = highlightModifier,
                        headlineContent = stringResource(
                            R.string.auto_patch_requires_charging
                        ),
                        supportingContent = stringResource(
                            R.string.auto_patch_requires_charging_description
                        ),
                        enabled = autoPatchEnabled,
                        trailingContent = {
                            ExpressiveSettingsSwitch(
                                checked = autoPatchEnabled && autoPatchRequiresCharging,
                                enabled = autoPatchEnabled,
                                onCheckedChange = viewModel::setAutoPatchRequiresCharging
                            )
                        },
                        onClick = {
                            viewModel.setAutoPatchRequiresCharging(
                                !autoPatchRequiresCharging
                            )
                        }
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.auto_patch_interval,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpressiveSettingsConfigurableItem(
                        modifier = highlightModifier,
                        headlineContent = stringResource(R.string.auto_patch_interval),
                        supportingContent = stringResource(autoPatchInterval.displayName),
                        enabled = autoPatchEnabled,
                        secondaryActionLabel = stringResource(R.string.reset),
                        onSecondaryAction = {
                            viewModel.setAutoPatchInterval(
                                viewModel.prefs.autoPatchInterval.default
                            )
                        },
                        secondaryActionEnabled = autoPatchEnabled &&
                            autoPatchInterval != viewModel.prefs.autoPatchInterval.default,
                        primaryActionLabel = stringResource(R.string.settings),
                        onPrimaryAction = { showAutoPatchIntervalDialog = true },
                        primaryActionEnabled = autoPatchEnabled
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.batch_patch_external_actions,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpressiveSettingsItem(
                        modifier = highlightModifier,
                        headlineContent = stringResource(
                            R.string.batch_patch_external_actions
                        ),
                        supportingContent = stringResource(
                            R.string.batch_patch_external_actions_description
                        ),
                        trailingContent = {
                            ExpressiveSettingsSwitch(
                                checked = allowExternalBatchActions,
                                onCheckedChange =
                                    viewModel::setAllowExternalBatchActions
                            )
                        },
                        onClick = {
                            viewModel.setAllowExternalBatchActions(
                                !allowExternalBatchActions
                            )
                        }
                    )
                }
            }

            if (showAutoPatchIntervalDialog) {
                AlertDialog(
                    onDismissRequest = { showAutoPatchIntervalDialog = false },
                    title = {
                        CenteredDialogTitle(stringResource(R.string.auto_patch_interval))
                    },
                    confirmButton = {
                        TextButton(onClick = { showAutoPatchIntervalDialog = false }) {
                            Text(stringResource(R.string.close))
                        }
                    },
                    text = {
                        Column {
                            SearchForUpdatesBackgroundInterval.entries
                                .filterNot { it == SearchForUpdatesBackgroundInterval.NEVER }
                                .forEach { interval ->
                                    ListItem(
                                        colors = transparentListItemColors,
                                        leadingContent = {
                                            RadioButton(
                                                selected = interval == autoPatchInterval,
                                                onClick = null
                                            )
                                        },
                                        headlineContent = {
                                            Text(stringResource(interval.displayName))
                                        },
                                        modifier = Modifier.clickable {
                                            viewModel.setAutoPatchInterval(interval)
                                            showAutoPatchIntervalDialog = false
                                        }
                                    )
                                }
                        }
                    }
                )
            }
            }

            if (mode == AdvancedSettingsMode.ADVANCED_SYSTEM) {
            SettingsSearchHighlight(
                targetKey = R.string.safeguards_compatibility_section,
                activeKey = highlightTarget,
                onHighlightComplete = { highlightTarget = null }
            ) { highlightModifier ->
                GroupHeader(
                    stringResource(R.string.safeguards_compatibility_section),
                    icon = SettingsSectionIcons.SafeguardsCompatibility,
                    modifier = highlightModifier
                )
            }
            ExpressiveSettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) {
                SettingsSearchHighlight(
                    targetKey = R.string.patch_compat_check,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    SafeguardBooleanItem(
                        modifier = highlightModifier,
                        preference = viewModel.prefs.disablePatchVersionCompatCheck,
                        coroutineScope = viewModel.viewModelScope,
                        headline = R.string.patch_compat_check,
                        description = R.string.patch_compat_check_description,
                        confirmationText = R.string.patch_compat_check_confirmation
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.suggested_version_safeguard,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    SafeguardBooleanItem(
                        modifier = highlightModifier,
                        preference = viewModel.prefs.suggestedVersionSafeguard,
                        coroutineScope = viewModel.viewModelScope,
                        headline = R.string.suggested_version_safeguard,
                        description = R.string.suggested_version_safeguard_description,
                        confirmationText = R.string.suggested_version_safeguard_confirmation
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.patch_selection_safeguard,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    SafeguardBooleanItem(
                        modifier = highlightModifier,
                        preference = viewModel.prefs.disableSelectionWarning,
                        coroutineScope = viewModel.viewModelScope,
                        headline = R.string.patch_selection_safeguard,
                        description = R.string.patch_selection_safeguard_description,
                        confirmationText = R.string.patch_selection_safeguard_confirmation
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.disable_patch_selection_confirmations,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    SafeguardBooleanItem(
                        modifier = highlightModifier,
                        preference = viewModel.prefs.disablePatchSelectionConfirmations,
                        coroutineScope = viewModel.viewModelScope,
                        headline = R.string.disable_patch_selection_confirmations,
                        description = R.string.disable_patch_selection_confirmations_description,
                        confirmationText = R.string.disable_patch_selection_confirmations_warning
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.universal_patches_safeguard,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    BooleanItem(
                        modifier = highlightModifier,
                        preference = viewModel.prefs.disableUniversalPatchCheck,
                        coroutineScope = viewModel.viewModelScope,
                        headline = R.string.universal_patches_safeguard,
                        description = R.string.universal_patches_safeguard_description,
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.patch_availability_enabled,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    BooleanItem(
                        modifier = highlightModifier,
                        preference = viewModel.prefs.patchAvailabilityEnabled,
                        coroutineScope = viewModel.viewModelScope,
                        headline = R.string.patch_availability_enabled,
                        description = R.string.patch_availability_enabled_description,
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.remove_gmscore_for_primary_mount,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    BooleanItem(
                        modifier = highlightModifier,
                        preference = viewModel.prefs.removeGmsCoreForPrimaryMount,
                        coroutineScope = viewModel.viewModelScope,
                        headline = R.string.remove_gmscore_for_primary_mount,
                        description = R.string.remove_gmscore_for_primary_mount_description,
                    )
                }
            }
            }

            if (mode == AdvancedSettingsMode.PATCHER) {
            var showMorpheBytecodeModeDialog by rememberSaveable { mutableStateOf(false) }
            var showPatcherLogModeDialog by rememberSaveable { mutableStateOf(false) }
            val morpheBytecodeMode by viewModel.prefs.morpheBytecodeMode.getAsState()
            val patcherLogMode by viewModel.prefs.patcherLogMode.getAsState()
            val useJvmRuntime by viewModel.prefs.useJvmRuntime.getAsState()
            val artMemoryLimit by viewModel.prefs.artMemoryLimit.getAsState()
            var showArtMemoryDialog by remember { mutableStateOf(false) }
            if (showArtMemoryDialog) {
                app.universal.patch.manager.ui.component.settings.ProcessMemoryLimitDialog(
                    currentLimit = artMemoryLimit,
                    onDismiss = { showArtMemoryDialog = false },
                    onLimitChange = { limit -> viewModel.viewModelScope.launch {
                        viewModel.prefs.artMemoryLimit.update(limit)
                    } }
                )
            }

            if (showMorpheBytecodeModeDialog) {
                MorpheBytecodeModeDialog(
                    current = morpheBytecodeMode,
                    onDismiss = { showMorpheBytecodeModeDialog = false },
                    onSelect = { modeValue ->
                        viewModel.setMorpheBytecodeMode(modeValue)
                        showMorpheBytecodeModeDialog = false
                    }
                )
            }
            if (showPatcherLogModeDialog) {
                PatcherLogModeDialog(
                    current = patcherLogMode,
                    onDismiss = { showPatcherLogModeDialog = false },
                    onSelect = { modeValue ->
                        viewModel.setPatcherLogMode(modeValue)
                        showPatcherLogModeDialog = false
                    }
                )
            }
            SettingsSearchHighlight(
                targetKey = R.string.patching_engine_section,
                activeKey = highlightTarget,
                onHighlightComplete = { highlightTarget = null }
            ) { highlightModifier ->
                GroupHeader(
                    stringResource(R.string.patching_engine_section),
                    icon = SettingsSectionIcons.PatchingEngine,
                    modifier = highlightModifier
                )
            }
            ExpressiveSettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) {
                SettingsSearchHighlight(
                    targetKey = R.string.process_memory_limit,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpressiveSettingsItem(
                        modifier = highlightModifier,
                        headlineContent = stringResource(R.string.process_memory_limit),
                        supportingContent = stringResource(R.string.art_memory_description),
                        trailingContent = {
                            Text(stringResource(R.string.process_memory_limit_value, artMemoryLimit))
                        },
                        onClick = { showArtMemoryDialog = true }
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.patcher_jvm_enable,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    BooleanItem(
                        modifier = highlightModifier,
                        value = useJvmRuntime && viewModel.jvmRuntimeAvailable,
                        onValueChange = { viewModel.setJvmRuntime(it) },
                        headline = R.string.patcher_jvm_enable,
                        description = if (viewModel.jvmRuntimeAvailable) R.string.patcher_jvm_description
                            else R.string.patcher_jvm_unavailable,
                        enabled = viewModel.jvmRuntimeAvailable
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.strip_unused_libs,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    BooleanItem(
                        modifier = highlightModifier,
                        preference = viewModel.prefs.stripUnusedNativeLibs,
                        coroutineScope = viewModel.viewModelScope,
                        headline = R.string.strip_unused_libs,
                        description = R.string.strip_unused_libs_description,
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.skip_unneeded_split_apks,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    BooleanItem(
                        modifier = highlightModifier,
                        preference = viewModel.prefs.skipUnneededSplitApks,
                        coroutineScope = viewModel.viewModelScope,
                        headline = R.string.skip_unneeded_split_apks,
                        description = R.string.skip_unneeded_split_apks_description,
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.choose_split_apks_before_patching,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    BooleanItem(
                        modifier = highlightModifier,
                        preference = viewModel.prefs.chooseSplitApksBeforePatching,
                        coroutineScope = viewModel.viewModelScope,
                        headline = R.string.choose_split_apks_before_patching,
                        description = R.string.choose_split_apks_before_patching_description,
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.continue_on_patch_error,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    BooleanItem(
                        modifier = highlightModifier,
                        preference = viewModel.prefs.continueOnPatchError,
                        coroutineScope = viewModel.viewModelScope,
                        headline = R.string.continue_on_patch_error,
                        description = R.string.continue_on_patch_error_description,
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.skip_apk_signing,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    BooleanItem(
                        modifier = highlightModifier,
                        preference = viewModel.prefs.skipApkSigning,
                        coroutineScope = viewModel.viewModelScope,
                        headline = R.string.skip_apk_signing,
                        description = R.string.skip_apk_signing_description,
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.inject_signature_metadata_after_patching,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    BooleanItem(
                        modifier = highlightModifier,
                        preference = viewModel.prefs.injectSignatureMetadataAfterPatching,
                        coroutineScope = viewModel.viewModelScope,
                        headline = R.string.inject_signature_metadata_after_patching,
                        description = R.string.inject_signature_metadata_after_patching_description,
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.patcher_log_mode,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpressiveSettingsConfigurableItem(
                        modifier = highlightModifier,
                        headlineContent = stringResource(R.string.patcher_log_mode),
                        supportingContentSlot = {
                            Text(
                                text = stringResource(R.string.patcher_log_mode_description),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        secondaryActionLabel = stringResource(R.string.reset_to_default),
                        onSecondaryAction = {
                            viewModel.setPatcherLogMode(viewModel.prefs.patcherLogMode.default)
                        },
                        secondaryActionEnabled = patcherLogMode != viewModel.prefs.patcherLogMode.default,
                        primaryActionLabel = stringResource(R.string.edit),
                        onPrimaryAction = { showPatcherLogModeDialog = true }
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.morphe_bytecode_mode,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpressiveSettingsConfigurableItem(
                        modifier = highlightModifier,
                        headlineContent = stringResource(R.string.morphe_bytecode_mode),
                        supportingContentSlot = {
                            Text(
                                text = stringResource(R.string.morphe_bytecode_mode_description),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        secondaryActionLabel = stringResource(R.string.reset),
                        onSecondaryAction = {
                            viewModel.setMorpheBytecodeMode(viewModel.prefs.morpheBytecodeMode.default)
                        },
                        secondaryActionEnabled = morpheBytecodeMode != viewModel.prefs.morpheBytecodeMode.default,
                        primaryActionLabel = stringResource(R.string.edit),
                        onPrimaryAction = { showMorpheBytecodeModeDialog = true }
                    )
                }
            }

            val autoExpandRunningStepsEnabled by viewModel.prefs.autoExpandRunningSteps.getAsState()
            val autoExpandRunningStepsExclusive by
                viewModel.prefs.autoExpandRunningStepsExclusive.getAsState()

            SettingsSearchHighlight(
                targetKey = R.string.patching_flow_section,
                activeKey = highlightTarget,
                onHighlightComplete = { highlightTarget = null }
            ) { highlightModifier ->
                GroupHeader(
                    stringResource(R.string.patching_flow_section),
                    icon = SettingsSectionIcons.PatchingFlow,
                    modifier = highlightModifier
                )
            }
            ExpressiveSettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) {
                val patcherGraphsEnabled by viewModel.prefs.showPatcherMemoryUsageGraph.getAsState()
                val patcherGraphsCompact by viewModel.prefs.compactPatcherResourceGraphs.getAsState()
                SettingsSearchHighlight(
                    targetKey = R.string.patcher_memory_usage_graph_title,
                    activeKey = highlightTarget,
                    extraKeys = setOf(R.string.patcher_resource_graph_options_title),
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpandableBooleanItem(
                        modifier = highlightModifier,
                        preference = viewModel.prefs.showPatcherMemoryUsageGraph,
                        coroutineScope = viewModel.viewModelScope,
                        headline = R.string.patcher_memory_usage_graph_title,
                        description = R.string.patcher_memory_usage_graph_description,
                        contentEnabled = patcherGraphsEnabled && !patcherGraphsCompact,
                        expandForSearch = highlightTarget == R.string.patcher_resource_graph_options_title
                    ) { childEnabled ->
                        BooleanItem(
                            preference = viewModel.prefs.showPatcherResourceGraphExtraInfo,
                            coroutineScope = viewModel.viewModelScope,
                            headline = R.string.resource_graph_extra_info_title,
                            description = R.string.resource_graph_extra_info_description,
                            enabled = childEnabled
                        )
                    }
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.patcher_resource_graphs_compact_title,
                    activeKey = highlightTarget,
                    extraKeys = setOf(R.string.patcher_compact_resource_graph_options_title),
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpandableBooleanItem(
                        modifier = highlightModifier,
                        preference = viewModel.prefs.compactPatcherResourceGraphs,
                        coroutineScope = viewModel.viewModelScope,
                        headline = R.string.patcher_resource_graphs_compact_title,
                        description = R.string.patcher_resource_graphs_compact_description,
                        enabled = patcherGraphsEnabled,
                        contentEnabled = patcherGraphsCompact,
                        expandForSearch = highlightTarget == R.string.patcher_compact_resource_graph_options_title
                    ) { childEnabled ->
                        BooleanItem(
                            preference = viewModel.prefs.showCompactPatcherResourceGraphExtraInfo,
                            coroutineScope = viewModel.viewModelScope,
                            headline = R.string.resource_graph_extra_info_title,
                            description = R.string.resource_graph_extra_info_description,
                            enabled = childEnabled
                        )
                    }
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(

                    targetKey = R.string.patcher_auto_collapse_steps,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    BooleanItem(
                        modifier = highlightModifier,
                        preference = viewModel.prefs.autoCollapsePatcherSteps,
                        coroutineScope = viewModel.viewModelScope,
                        headline = R.string.patcher_auto_collapse_steps,
                        description = R.string.patcher_auto_collapse_steps_description,
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.patcher_auto_expand_steps,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    BooleanItem(
                        modifier = highlightModifier,
                        preference = viewModel.prefs.autoExpandRunningSteps,
                        coroutineScope = viewModel.viewModelScope,
                        headline = R.string.patcher_auto_expand_steps,
                        description = R.string.patcher_auto_expand_steps_description,
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.patcher_auto_expand_running_steps_exclusive,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    BooleanItem(
                        modifier = highlightModifier,
                        value = autoExpandRunningStepsEnabled && autoExpandRunningStepsExclusive,
                        onValueChange = { value ->
                            viewModel.viewModelScope.launch {
                                viewModel.prefs.autoExpandRunningStepsExclusive.update(value)
                            }
                        },
                        headline = R.string.patcher_auto_expand_running_steps_exclusive,
                        description = R.string.patcher_auto_expand_running_steps_exclusive_description,
                        enabled = autoExpandRunningStepsEnabled
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.show_patch_selection_summary,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    BooleanItem(
                        modifier = highlightModifier,
                        preference = viewModel.prefs.showPatchSelectionSummary,
                        coroutineScope = viewModel.viewModelScope,
                        headline = R.string.show_patch_selection_summary,
                        description = R.string.show_patch_selection_summary_description,
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.patch_selection_collapse_on_toggle,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    BooleanItem(
                        modifier = highlightModifier,
                        preference = viewModel.prefs.collapsePatchActionsOnSelection,
                        coroutineScope = viewModel.viewModelScope,
                        headline = R.string.patch_selection_collapse_on_toggle,
                        description = R.string.patch_selection_collapse_on_toggle_description,
                    )
                }
            }

            SettingsSearchHighlight(
                targetKey = R.string.saved_apps_section,
                activeKey = highlightTarget,
                onHighlightComplete = { highlightTarget = null }
            ) { highlightModifier ->
                GroupHeader(
                    stringResource(R.string.saved_apps_section),
                    icon = SettingsSectionIcons.SavedApps,
                    modifier = highlightModifier
                )
            }
            val savedAppsEnabled by viewModel.prefs.enableSavedApps.getAsState()
            val disableSavedAppOverwrite by viewModel.prefs.disableSavedAppOverwrite.getAsState()
            ExpressiveSettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) {
                SettingsSearchHighlight(
                    targetKey = R.string.patcher_saved_apps_title,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    BooleanItem(
                        modifier = highlightModifier,
                        value = savedAppsEnabled,
                        onValueChange = viewModel::setSavedAppsEnabled,
                        headline = R.string.patcher_saved_apps_title,
                        description = R.string.patcher_saved_apps_description,
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.saved_apps_disable_overwrite_title,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    BooleanItem(
                        modifier = highlightModifier,
                        value = savedAppsEnabled && disableSavedAppOverwrite,
                        onValueChange = { value ->
                            viewModel.viewModelScope.launch {
                                viewModel.prefs.disableSavedAppOverwrite.update(value)
                            }
                        },
                        headline = R.string.saved_apps_disable_overwrite_title,
                        description = R.string.saved_apps_disable_overwrite_description,
                        enabled = savedAppsEnabled
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.saved_apps_show_bundle_update_badges_title,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    val showSavedAppBundleUpdateBadges by viewModel.prefs.showSavedAppBundleUpdateBadges.getAsState()
                    BooleanItem(
                        modifier = highlightModifier,
                        value = savedAppsEnabled && showSavedAppBundleUpdateBadges,
                        onValueChange = { value ->
                            viewModel.viewModelScope.launch {
                                viewModel.prefs.showSavedAppBundleUpdateBadges.update(value)
                            }
                        },
                        headline = R.string.saved_apps_show_bundle_update_badges_title,
                        description = R.string.saved_apps_show_bundle_update_badges_description,
                        enabled = savedAppsEnabled
                    )
                }
            }
            val showPatchSelectionVersionTags by
                viewModel.prefs.patchSelectionShowVersionTags.getAsState()
            val showPatchSelectionOptionPreviews by
                viewModel.prefs.patchSelectionShowOptionPreviews.getAsState()
            val minimalPatchSelectionView =
                !showPatchSelectionVersionTags && !showPatchSelectionOptionPreviews

            SettingsSearchHighlight(
                targetKey = R.string.patch_list_display_section,
                activeKey = highlightTarget,
                onHighlightComplete = { highlightTarget = null }
            ) { highlightModifier ->
                GroupHeader(
                    stringResource(R.string.patch_list_display_section),
                    icon = SettingsSectionIcons.ActionButtonsPatchList,
                    modifier = highlightModifier
                )
            }
            ExpressiveSettingsCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) {
                SettingsSearchHighlight(
                    targetKey = R.string.minimal_patch_selection_view_title,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    BooleanItem(
                        modifier = highlightModifier,
                        value = minimalPatchSelectionView,
                        onValueChange = { enabled ->
                            viewModel.viewModelScope.launch {
                                viewModel.prefs.setMinimalPatchSelectionView(enabled)
                            }
                        },
                        headline = R.string.minimal_patch_selection_view_title,
                        description = R.string.minimal_patch_selection_view_description
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.patch_selection_version_tags_title,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    BooleanItem(
                        modifier = highlightModifier,
                        value = showPatchSelectionVersionTags,
                        onValueChange = { value ->
                            viewModel.viewModelScope.launch {
                                viewModel.prefs.patchSelectionShowVersionTags.update(value)
                            }
                        },
                        headline = R.string.patch_selection_version_tags_title,
                        description = R.string.patch_selection_version_tags_description,
                        enabled = !minimalPatchSelectionView
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.patch_selection_option_previews_title,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    BooleanItem(
                        modifier = highlightModifier,
                        value = showPatchSelectionOptionPreviews,
                        onValueChange = { value ->
                            viewModel.viewModelScope.launch {
                                viewModel.prefs.patchSelectionShowOptionPreviews.update(value)
                            }
                        },
                        headline = R.string.patch_selection_option_previews_title,
                        description = R.string.patch_selection_option_previews_description,
                        enabled = !minimalPatchSelectionView
                    )
                }
            }
            val splitMergeAutoExpandRunningStepsEnabled by
                viewModel.prefs.splitMergeAutoExpandRunningSteps.getAsState()
            val splitMergeAutoExpandRunningStepsExclusive by
                viewModel.prefs.splitMergeAutoExpandRunningStepsExclusive.getAsState()

            SettingsSearchHighlight(
                targetKey = R.string.merge_split_flow_section,
                activeKey = highlightTarget,
                onHighlightComplete = { highlightTarget = null }
            ) { highlightModifier ->
                GroupHeader(
                    stringResource(R.string.merge_split_flow_section),
                    icon = SettingsSectionIcons.PatchingFlow,
                    modifier = highlightModifier
                )
            }
            ExpressiveSettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) {
                SettingsSearchHighlight(
                    targetKey = R.string.skip_split_merge_signing,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    BooleanItem(
                        modifier = highlightModifier,
                        preference = viewModel.prefs.skipSplitMergeSigning,
                        coroutineScope = viewModel.viewModelScope,
                        headline = R.string.skip_split_merge_signing,
                        description = R.string.skip_split_merge_signing_description,
                    )
                }
                ExpressiveSettingsDivider()
                val mergerGraphsEnabled by viewModel.prefs.showSplitMergeMemoryUsageGraph.getAsState()
                val mergerGraphsCompact by viewModel.prefs.compactSplitMergeResourceGraphs.getAsState()
                SettingsSearchHighlight(
                    targetKey = R.string.merge_split_memory_usage_graph_title,
                    activeKey = highlightTarget,
                    extraKeys = setOf(R.string.merger_resource_graph_options_title),
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpandableBooleanItem(
                        modifier = highlightModifier,
                        preference = viewModel.prefs.showSplitMergeMemoryUsageGraph,
                        coroutineScope = viewModel.viewModelScope,
                        headline = R.string.merge_split_memory_usage_graph_title,
                        description = R.string.merge_split_memory_usage_graph_description,
                        contentEnabled = mergerGraphsEnabled && !mergerGraphsCompact,
                        expandForSearch = highlightTarget == R.string.merger_resource_graph_options_title
                    ) { childEnabled ->
                        BooleanItem(
                            preference = viewModel.prefs.showSplitMergeResourceGraphExtraInfo,
                            coroutineScope = viewModel.viewModelScope,
                            headline = R.string.resource_graph_extra_info_title,
                            description = R.string.resource_graph_extra_info_description,
                            enabled = childEnabled
                        )
                    }
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.merger_resource_graphs_compact_title,
                    activeKey = highlightTarget,
                    extraKeys = setOf(R.string.merger_compact_resource_graph_options_title),
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpandableBooleanItem(
                        modifier = highlightModifier,
                        preference = viewModel.prefs.compactSplitMergeResourceGraphs,
                        coroutineScope = viewModel.viewModelScope,
                        headline = R.string.merger_resource_graphs_compact_title,
                        description = R.string.merger_resource_graphs_compact_description,
                        enabled = mergerGraphsEnabled,
                        contentEnabled = mergerGraphsCompact,
                        expandForSearch = highlightTarget == R.string.merger_compact_resource_graph_options_title
                    ) { childEnabled ->
                        BooleanItem(
                            preference = viewModel.prefs.showCompactSplitMergeResourceGraphExtraInfo,
                            coroutineScope = viewModel.viewModelScope,
                            headline = R.string.resource_graph_extra_info_title,
                            description = R.string.resource_graph_extra_info_description,
                            enabled = childEnabled
                        )
                    }
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.merge_split_auto_collapse_steps,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    BooleanItem(
                        modifier = highlightModifier,
                        preference = viewModel.prefs.splitMergeAutoCollapseSteps,
                        coroutineScope = viewModel.viewModelScope,
                        headline = R.string.merge_split_auto_collapse_steps,
                        description = R.string.merge_split_auto_collapse_steps_description,
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.merge_split_auto_expand_steps,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    BooleanItem(
                        modifier = highlightModifier,
                        preference = viewModel.prefs.splitMergeAutoExpandRunningSteps,
                        coroutineScope = viewModel.viewModelScope,
                        headline = R.string.merge_split_auto_expand_steps,
                        description = R.string.merge_split_auto_expand_steps_description,
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.merge_split_auto_expand_running_steps_exclusive,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    BooleanItem(
                        modifier = highlightModifier,
                        value = splitMergeAutoExpandRunningStepsEnabled &&
                            splitMergeAutoExpandRunningStepsExclusive,
                        onValueChange = { value ->
                            viewModel.viewModelScope.launch {
                                viewModel.prefs.splitMergeAutoExpandRunningStepsExclusive.update(value)
                            }
                        },
                        headline = R.string.merge_split_auto_expand_running_steps_exclusive,
                        description = R.string.merge_split_auto_expand_running_steps_exclusive_description,
                        enabled = splitMergeAutoExpandRunningStepsEnabled
                    )
                }
            }
        }

        if (mode == AdvancedSettingsMode.ADVANCED_SYSTEM) {
        SettingsSearchHighlight(
            targetKey = R.string.diagnostics_output_section,
            activeKey = highlightTarget,
            onHighlightComplete = { highlightTarget = null }
        ) { highlightModifier ->
            GroupHeader(
                stringResource(R.string.diagnostics_output_section),
                icon = SettingsSectionIcons.DiagnosticsOutput,
                modifier = highlightModifier
            )
        }
        ExpressiveSettingsCard(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
        ) {
                SettingsSearchHighlight(
                    targetKey = R.string.export_name_format,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpressiveSettingsConfigurableItem(
                        modifier = highlightModifier,
                        headlineContent = stringResource(R.string.export_name_format),
                        supportingContentSlot = {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = stringResource(R.string.export_name_format_description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.export_name_format_current, exportFormat),
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        },
                        secondaryActionLabel = stringResource(R.string.reset),
                        onSecondaryAction = { viewModel.resetPatchedAppExportFormat() },
                        secondaryActionEnabled = exportFormat != viewModel.prefs.patchedAppExportFormat.default,
                        primaryActionLabel = stringResource(R.string.edit),
                        onPrimaryAction = { showExportFormatDialog = true }
                    )
                }
                ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.merged_apk_name_format,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpressiveSettingsConfigurableItem(
                        modifier = highlightModifier,
                        headlineContent = stringResource(R.string.merged_apk_name_format),
                        supportingContentSlot = {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = stringResource(R.string.merged_apk_name_format_description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.export_name_format_current, mergedApkExportFormat),
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        },
                        secondaryActionLabel = stringResource(R.string.reset),
                        onSecondaryAction = { viewModel.resetMergedApkExportFormat() },
                        secondaryActionEnabled = mergedApkExportFormat != viewModel.prefs.mergedApkExportFormat.default,
                        primaryActionLabel = stringResource(R.string.edit),
                        onPrimaryAction = { showMergedApkExportFormatDialog = true }
                    )
                }
            }

            val advancedLogExportDirectory by
                viewModel.prefs.advancedLogExportLastDirectory.getAsState()
            val exportDebugLogsLauncher = rememberLauncherForActivityResult(
                RememberedCreateDocument("text/plain") {
                    advancedLogExportDirectory.takeIf(String::isNotBlank)?.let(Uri::parse)
                }
            ) { uri ->
                uri?.let {
                    viewModel.viewModelScope.launch {
                        viewModel.prefs.advancedLogExportLastDirectory.update(
                            it.toPickerDirectoryUri().toString()
                        )
                    }
                    viewModel.exportDebugLogs(it)
                }
            }
            ExpressiveSettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) {
                SettingsSearchHighlight(
                    targetKey = R.string.debug_logs_export,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpressiveSettingsItem(
                        modifier = highlightModifier,
                        headlineContent = stringResource(R.string.debug_logs_export),
                        onClick = { exportDebugLogsLauncher.launch(viewModel.debugLogFileName) }
                    )
                }
                ExpressiveSettingsDivider()
                val clipboard = remember { context.getSystemService<ClipboardManager>()!! }
                val deviceContent = """
                    Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})
                    Build type: ${BuildConfig.BUILD_TYPE}
                    Model: ${Build.MODEL}
                    Android version: ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})
                    Supported Archs: ${Build.SUPPORTED_ABIS.joinToString(", ")}
                    Memory limit: $memoryLimit
                """.trimIndent()
                SettingsSearchHighlight(
                    targetKey = R.string.about_device,
                    activeKey = highlightTarget,
                    onHighlightComplete = { highlightTarget = null }
                ) { highlightModifier ->
                    ExpressiveSettingsItem(
                        modifier = highlightModifier.combinedClickable(
                            onClick = { },
                            onLongClickLabel = stringResource(R.string.copy_to_clipboard),
                            onLongClick = {
                                clipboard.setPrimaryClip(
                                    ClipData.newPlainText("Device Information", deviceContent)
                                )

                                context.toast(context.getString(R.string.toast_copied_to_clipboard))
                            }.withHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        ),
                        headlineContent = stringResource(R.string.about_device),
                        supportingContent = deviceContent
                    )
                }
            }
        }
        }
    }

}

enum class AdvancedSettingsMode(
    @StringRes val titleRes: Int,
    val destination: NavigationSettings.Destination
) {
    APP_MANAGER(R.string.advanced, NavigationSettings.Advanced),
    PATCHER(R.string.patcher_category, NavigationSettings.Patcher),
    ADVANCED_SYSTEM(R.string.advanced_system, NavigationSettings.AdvancedSystem)
}

private enum class InstallerDialogTarget {
    Primary,
    Fallback
}

@Composable
private fun LanguageDialog(
    options: List<AppLanguageOption>,
    selectedCode: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val sortedOptions = remember(options, configuration) {
        app.universal.patch.manager.util.sortLanguageOptions(
            options, configuration.locales[0],
            code = { it.code },
            label = { context.getString(it.labelRes) }
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        title = {
            CenteredDialogTitle(stringResource(R.string.language_dialog_title))
        },
        text = {
            val scrollState = rememberScrollState()
            ColumnWithScrollbar(
                thumbLength = app.universal.patch.manager.ui.component.DASHBOARD_SCROLLBAR_THUMB_LENGTH,
                state = scrollState,
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                sortedOptions.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelect(option.code) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = option.code == selectedCode,
                            onClick = { onSelect(option.code) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(option.labelRes),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    )
}

@OptIn(FlowPreview::class)
@Composable
internal fun ActionButtonSettings(
    viewModel: AdvancedSettingsViewModel,
    highlightTarget: Int?,
    onHighlightComplete: () -> Unit
) {
    val actionOrderPref by viewModel.prefs.patchSelectionActionOrder.getAsState()
    val hiddenActionsPref by viewModel.prefs.patchSelectionHiddenActions.getAsState()
    val showPatchProfilesTab by viewModel.prefs.showPatchProfilesTab.getAsState()
    val bundleActionOrderPref by viewModel.prefs.patchBundleActionOrder.getAsState()
    val bundleHiddenActionsPref by viewModel.prefs.patchBundleHiddenActions.getAsState()
    val batchResultActionOrderPref by viewModel.prefs.batchResultActionOrder.getAsState()
    val batchResultHiddenActionsPref by viewModel.prefs.batchResultHiddenActions.getAsState()
    val savedActionOrderPref by viewModel.prefs.savedAppActionOrder.getAsState()
    val savedHiddenActionsPref by viewModel.prefs.savedAppHiddenActions.getAsState()
    val profileActionOrderPref by viewModel.prefs.patchProfileActionOrder.getAsState()
    val profileHiddenActionsPref by viewModel.prefs.patchProfileHiddenActions.getAsState()
    val lsposedActionOrderPref by viewModel.prefs.lsposedModuleActionOrder.getAsState()
    val lsposedHiddenActionsPref by viewModel.prefs.lsposedModuleHiddenActions.getAsState()
            val actionOrderList = remember(actionOrderPref) {
                val parsed = actionOrderPref
                    .split(',')
                    .mapNotNull { PatchSelectionActionKey.fromStorageId(it.trim()) }
                PatchSelectionActionKey.ensureComplete(parsed)
            }
            val workingOrder = remember(actionOrderList) { actionOrderList.toMutableStateList() }
            LaunchedEffect(actionOrderList) {
                workingOrder.clear()
                workingOrder.addAll(actionOrderList)
            }
            var actionsExpanded by rememberSaveable { mutableStateOf(false) }
            val boundsMap = remember { mutableStateMapOf<PatchSelectionActionKey, Rect>() }
            var draggingKey by remember { mutableStateOf<PatchSelectionActionKey?>(null) }
            var hoverTarget by remember { mutableStateOf<PatchSelectionActionKey?>(null) }
            var dragPointerOffset by remember { mutableStateOf<Offset?>(null) }
            var dragStartRect by remember { mutableStateOf<Rect?>(null) }
            var lastDragPosition by remember { mutableStateOf<Offset?>(null) }
            val dragAnimatable = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
            var dragDesiredTopLeft by remember { mutableStateOf<Offset?>(null) }
            var isReturningToStart by remember { mutableStateOf(false) }
            var previewOrigin by remember { mutableStateOf(Offset.Zero) }
            val coroutineScope = rememberCoroutineScope()

            val bundleActionOrderList = remember(bundleActionOrderPref) {
                val parsed = bundleActionOrderPref
                    .split(',')
                    .mapNotNull { PatchBundleActionKey.fromStorageId(it.trim()) }
                PatchBundleActionKey.ensureComplete(parsed)
            }
            val bundleWorkingOrder = remember(bundleActionOrderList) { bundleActionOrderList.toMutableStateList() }
            LaunchedEffect(bundleActionOrderList) {
                bundleWorkingOrder.clear()
                bundleWorkingOrder.addAll(bundleActionOrderList)
            }
            var bundleActionsExpanded by rememberSaveable { mutableStateOf(false) }

            val batchResultActionOrderList = remember(batchResultActionOrderPref) {
                val parsed = batchResultActionOrderPref
                    .split(',')
                    .mapNotNull { BatchResultActionKey.fromStorageId(it.trim()) }
                BatchResultActionKey.ensureComplete(parsed)
            }
            val batchResultWorkingOrder = remember(batchResultActionOrderList) {
                batchResultActionOrderList.toMutableStateList()
            }
            LaunchedEffect(batchResultActionOrderList) {
                batchResultWorkingOrder.clear()
                batchResultWorkingOrder.addAll(batchResultActionOrderList)
            }
            var batchResultActionsExpanded by rememberSaveable { mutableStateOf(false) }

            val savedActionOrderList = remember(savedActionOrderPref) {
                val parsed = savedActionOrderPref
                    .split(',')
                    .mapNotNull { SavedAppActionKey.fromStorageId(it.trim()) }
                SavedAppActionKey.ensureComplete(parsed)
            }
            val savedWorkingOrder = remember(savedActionOrderList) { savedActionOrderList.toMutableStateList() }
            LaunchedEffect(savedActionOrderList) {
                savedWorkingOrder.clear()
                savedWorkingOrder.addAll(savedActionOrderList)
            }
            var savedActionsExpanded by rememberSaveable { mutableStateOf(false) }

            val profileActionOrderList = remember(profileActionOrderPref) {
                val parsed = profileActionOrderPref
                    .split(',')
                    .mapNotNull { PatchProfileActionKey.fromStorageId(it.trim()) }
                PatchProfileActionKey.ensureComplete(parsed)
            }
            val profileWorkingOrder = remember(profileActionOrderList) { profileActionOrderList.toMutableStateList() }
            LaunchedEffect(profileActionOrderList) {
                profileWorkingOrder.clear()
                profileWorkingOrder.addAll(profileActionOrderList)
            }
            var profileActionsExpanded by rememberSaveable { mutableStateOf(false) }

            val lsposedActionOrderList = remember(lsposedActionOrderPref) {
                val parsed = lsposedActionOrderPref
                    .split(',')
                    .mapNotNull { LsposedModuleActionKey.fromStorageId(it.trim()) }
                LsposedModuleActionKey.ensureComplete(parsed)
            }
            val lsposedWorkingOrder = remember(lsposedActionOrderList) {
                lsposedActionOrderList.toMutableStateList()
            }
            LaunchedEffect(lsposedActionOrderList) {
                lsposedWorkingOrder.clear()
                lsposedWorkingOrder.addAll(lsposedActionOrderList)
            }
            var lsposedActionsExpanded by rememberSaveable { mutableStateOf(false) }

            fun moveAction(action: PatchSelectionActionKey, target: PatchSelectionActionKey) {
                if (action == target) return
                val fromIndex = workingOrder.indexOf(action)
                val toIndex = workingOrder.indexOf(target)
                if (fromIndex == -1 || toIndex == -1) return
                val removed = workingOrder.removeAt(fromIndex)
                val updatedTargetIndex = workingOrder.indexOf(target).takeIf { it >= 0 } ?: run {
                    workingOrder.add(fromIndex.coerceIn(0, workingOrder.size), removed)
                    return
                }

                val insertionIndex = if (fromIndex < toIndex) {
                    // Moving "forward": place after the target.
                    updatedTargetIndex + 1
                } else {
                    // Moving "backward": place before the target.
                    updatedTargetIndex
                }

                workingOrder.add(insertionIndex.coerceIn(0, workingOrder.size), removed)
            }

            LaunchedEffect(Unit) {
                snapshotFlow { dragDesiredTopLeft }
                    .filterNotNull()
                    .collectLatest { desired ->
                        if (draggingKey == null || isReturningToStart) return@collectLatest
                        dragAnimatable.snapTo(desired)
                    }
            }

            LaunchedEffect(bundleActionOrderList) {
                snapshotFlow { bundleWorkingOrder.toList() }
                    .distinctUntilChanged()
                    .debounce(200)
                    .collectLatest { order ->
                        if (order == bundleActionOrderList) return@collectLatest
                        viewModel.setPatchBundleActionOrder(order)
                    }
            }

            LaunchedEffect(savedActionOrderList) {
                snapshotFlow { savedWorkingOrder.toList() }
                    .distinctUntilChanged()
                    .debounce(200)
                    .collectLatest { order ->
                        if (order == savedActionOrderList) return@collectLatest
                        viewModel.setSavedAppActionOrder(order)
                    }
            }

            LaunchedEffect(batchResultActionOrderList) {
                snapshotFlow { batchResultWorkingOrder.toList() }
                    .distinctUntilChanged()
                    .debounce(200)
                    .collectLatest { order ->
                        if (order == batchResultActionOrderList) return@collectLatest
                        viewModel.setBatchResultActionOrder(order)
                    }
            }

            LaunchedEffect(profileActionOrderList) {
                snapshotFlow { profileWorkingOrder.toList() }
                    .distinctUntilChanged()
                    .debounce(200)
                    .collectLatest { order ->
                        if (order == profileActionOrderList) return@collectLatest
                        viewModel.setPatchProfileActionOrder(order)
                    }
            }

            LaunchedEffect(lsposedActionOrderList) {
                snapshotFlow { lsposedWorkingOrder.toList() }
                    .distinctUntilChanged()
                    .debounce(200)
                    .collectLatest { order ->
                        if (order == lsposedActionOrderList) return@collectLatest
                        viewModel.setLsposedModuleActionOrder(order)
                    }
            }


    LaunchedEffect(highlightTarget) {
        when (highlightTarget) {
            R.string.patch_selection_action_visibility_title,
            R.string.minimal_action_buttons_title -> actionsExpanded = true
            R.string.patch_bundle_action_visibility_title -> bundleActionsExpanded = true
            R.string.batch_result_action_visibility_title -> batchResultActionsExpanded = true
            R.string.saved_app_action_visibility_title -> savedActionsExpanded = true
            R.string.patch_profile_action_visibility_title -> profileActionsExpanded = true
            R.string.lsposed_module_action_visibility_title -> lsposedActionsExpanded = true
        }
    }
    Column {
        DashboardActionSettings(
            viewModel.prefs,
            bundles = false,
            highlightTarget = highlightTarget,
            onHighlightComplete = onHighlightComplete
        )
        DashboardActionSettings(
            viewModel.prefs,
            bundles = true,
            highlightTarget = highlightTarget,
            onHighlightComplete = onHighlightComplete
        )
        SettingsSearchHighlight(
                targetKey = R.string.patch_selection_action_order_title,
                extraKeys = setOf(R.string.minimal_action_buttons_title),
                activeKey = highlightTarget,
                onHighlightComplete = onHighlightComplete
            ) { highlightModifier ->
                ExpressiveSettingsItem(
                    modifier = highlightModifier,
                    headlineContent = stringResource(R.string.patch_selection_action_order_title),
                    supportingContent = stringResource(R.string.patch_selection_action_order_description),
                    trailingContent = {
                        Icon(
                            imageVector = if (actionsExpanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                            contentDescription = null
                        )
                    },
                    onClick = { actionsExpanded = !actionsExpanded }
                )
            }

            if (actionsExpanded) {
                MinimalActionSettings(viewModel.prefs, "selection")
                ExpressiveSettingsDivider()
                Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .onGloballyPositioned { previewOrigin = it.positionInRoot() },
                        contentAlignment = Alignment.Center
                    ) {
                            val firstRowState = rememberLazyListState()
                            val secondRowState = rememberLazyListState()
                            var popupBounds by remember { mutableStateOf<Rect?>(null) }

                            fun findSlot(
                                position: Offset,
                                excludeKey: PatchSelectionActionKey? = null
                            ): PatchSelectionActionKey? {
                                val rects = workingOrder.mapNotNull { key ->
                                    if (key == excludeKey) return@mapNotNull null
                                    boundsMap[key]?.let { rect -> key to rect }
                                }
                                if (rects.isEmpty()) return null

                                rects.firstOrNull { (_, rect) ->
                                    position.x >= rect.left &&
                                        position.x <= rect.right &&
                                        position.y >= rect.top &&
                                        position.y <= rect.bottom
                                }?.let { (key, _) -> return key }

                                fun distanceSqToRect(rect: Rect): Float {
                                    val dx = when {
                                        position.x < rect.left -> rect.left - position.x
                                        position.x > rect.right -> position.x - rect.right
                                        else -> 0f
                                    }
                                    val dy = when {
                                        position.y < rect.top -> rect.top - position.y
                                        position.y > rect.bottom -> position.y - rect.bottom
                                        else -> 0f
                                    }
                                    return dx * dx + dy * dy
                                }

                                return rects.minBy { (_, rect) -> distanceSqToRect(rect) }.first
                            }

                            val density = LocalDensity.current
                            val edgeThresholdPx = remember(density) { with(density) { 28.dp.toPx() } }
                            val maxScrollPx = remember(density) { with(density) { 16.dp.toPx() } }

                            fun finishDrag(releasePosition: Offset? = null) {
                                val currentKey = draggingKey
                                val startRect = dragStartRect
                                val pointerOffset = dragPointerOffset
                                val target = releasePosition?.let { findSlot(it, excludeKey = currentKey) }?.takeIf { it != currentKey }
                                    ?: hoverTarget?.takeIf { it != currentKey }
                                    ?: lastDragPosition?.let { findSlot(it, excludeKey = currentKey) }?.takeIf { it != currentKey }
                                if (currentKey == null || startRect == null || pointerOffset == null) {
                                    draggingKey = null
                                    hoverTarget = null
                                    dragDesiredTopLeft = null
                                    isReturningToStart = false
                                    dragPointerOffset = null
                                    dragStartRect = null
                                    lastDragPosition = null
                                    return
                                }
                                if (target != null) {
                                    moveAction(currentKey, target)
                                    draggingKey = null
                                    hoverTarget = null
                                    dragDesiredTopLeft = null
                                    isReturningToStart = false
                                    dragPointerOffset = null
                                    dragStartRect = null
                                    lastDragPosition = null
                                    viewModel.setPatchSelectionActionOrder(workingOrder.toList())
                                } else {
                                    isReturningToStart = true
                                    dragDesiredTopLeft = null
                                    coroutineScope.launch {
                                        dragAnimatable.animateTo(
                                            startRect.topLeft,
                                            tween(durationMillis = 220)
                                        )
                                        draggingKey = null
                                        hoverTarget = null
                                        dragPointerOffset = null
                                        dragStartRect = null
                                        lastDragPosition = null
                                        isReturningToStart = false
                                    }
                                }
                            }

                            LaunchedEffect(draggingKey) {
                                while (true) {
                                    val activeKey = draggingKey ?: break
                                    val pos = lastDragPosition
                                    val bounds = popupBounds

                                    val rawDx = if (pos != null && bounds != null && pos.y >= bounds.top && pos.y <= bounds.bottom) {
                                        when {
                                            pos.x < bounds.left + edgeThresholdPx -> {
                                                val t = ((pos.x - bounds.left) / edgeThresholdPx).coerceIn(0f, 1f)
                                                -maxScrollPx * (1f - t)
                                            }
                                            pos.x > bounds.right - edgeThresholdPx -> {
                                                val t = ((bounds.right - pos.x) / edgeThresholdPx).coerceIn(0f, 1f)
                                                maxScrollPx * (1f - t)
                                            }
                                            else -> 0f
                                        }
                                    } else {
                                        0f
                                    }

                                    val canScrollLeft = firstRowState.canScrollBackward || secondRowState.canScrollBackward
                                    val canScrollRight = firstRowState.canScrollForward || secondRowState.canScrollForward
                                    val dx = when {
                                        rawDx < 0f && !canScrollLeft -> 0f
                                        rawDx > 0f && !canScrollRight -> 0f
                                        else -> rawDx
                                    }

                                    if (dx != 0f) {
                                        firstRowState.scrollBy(dx)
                                        secondRowState.scrollBy(dx)
                                        if (pos != null) {
                                            hoverTarget = findSlot(pos, excludeKey = activeKey).takeIf { it != activeKey }
                                        }
                                    }

                                    kotlinx.coroutines.delay(16)
                                }
                            }

                            val dragGestureModifier = Modifier.pointerInput(actionsExpanded, workingOrder.size) {
                                if (!actionsExpanded) return@pointerInput
                                awaitEachGesture {
                                    if (draggingKey != null) return@awaitEachGesture

                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val globalDown = previewOrigin + down.position
                                    val key = boundsMap.entries.firstOrNull { (_, rect) ->
                                        globalDown.x >= rect.left &&
                                            globalDown.x <= rect.right &&
                                            globalDown.y >= rect.top &&
                                            globalDown.y <= rect.bottom
                                    }?.key ?: return@awaitEachGesture

                                    val rect = boundsMap[key] ?: return@awaitEachGesture
                                    val pointerOffset = globalDown - rect.topLeft

                                    awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture

                                    draggingKey = key
                                    hoverTarget = null
                                    isReturningToStart = false
                                    dragPointerOffset = pointerOffset
                                    dragStartRect = rect
                                    lastDragPosition = globalDown
                                    dragDesiredTopLeft = rect.topLeft

                                    var didFinish = false
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Main)
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break

                                        if (!change.pressed) {
                                            val upPosition = previewOrigin + change.position
                                            lastDragPosition = upPosition
                                            finishDrag(releasePosition = upPosition)
                                            didFinish = true
                                            break
                                        }

                                        val global = previewOrigin + change.position
                                        lastDragPosition = global
                                        dragDesiredTopLeft = global - pointerOffset
                                        hoverTarget = findSlot(global, excludeKey = key).takeIf { it != key }
                                        change.consume()
                                    }

                                    if (!didFinish && draggingKey == key) {
                                        finishDrag()
                                    }
                                }
                            }
                            PatchSelectionActionPreview(
                                order = workingOrder,
                                hiddenActions = hiddenActionsPref,
                                modifier = dragGestureModifier,
                                centerContent = true,
                                draggingKey = draggingKey,
                                highlightKey = hoverTarget,
                                firstRowState = firstRowState,
                                secondRowState = secondRowState,
                                userScrollEnabled = draggingKey == null,
                                onPopupBoundsChanged = { popupBounds = it },
                                onBoundsChanged = { key, rect -> boundsMap[key] = rect },
                                onBoundsDisposed = { key -> boundsMap.remove(key) },
                            )
                            val activeKey = draggingKey
                            val pointerOffset = dragPointerOffset
                            if (activeKey != null && pointerOffset != null) {
                                val overlayOffset = dragAnimatable.value - previewOrigin
                                val bounds = popupBounds
                                val startRect = dragStartRect
                                val constrainedOffset = if (bounds != null && startRect != null) {
                                    val minX = bounds.left - previewOrigin.x
                                    val maxX = (bounds.right - previewOrigin.x - startRect.width).coerceAtLeast(minX)
                                    val minY = bounds.top - previewOrigin.y
                                    val maxY = (bounds.bottom - previewOrigin.y - startRect.height).coerceAtLeast(minY)
                                    Offset(
                                        x = overlayOffset.x.coerceIn(minX, maxX),
                                        y = overlayOffset.y.coerceIn(minY, maxY)
                                    )
                                } else {
                                    overlayOffset
                                }
                                SelectionActionPreviewChip(
                                    icon = previewIconForAction(activeKey),
                                    label = stringResource(activeKey.labelRes),
                                    hidden = activeKey.storageId in hiddenActionsPref,
                                    floating = true,
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .offset {
                                            IntOffset(
                                                constrainedOffset.x.roundToInt(),
                                                constrainedOffset.y.roundToInt()
                                            )
                                        }
                                )
                            }

                        }
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                        ) {
                            SettingsSearchHighlight(
                                targetKey = R.string.patch_selection_action_visibility_title,
                                activeKey = highlightTarget,
                                onHighlightComplete = onHighlightComplete
                            ) { highlightModifier ->
                                Text(
                                    text = stringResource(R.string.patch_selection_action_visibility_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = highlightModifier
                                )
                            }
                            Text(
                                text = stringResource(R.string.patch_selection_action_visibility_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (!showPatchProfilesTab) {
                                Text(
                                    text = stringResource(R.string.patch_selection_action_visibility_forced_note),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                PatchSelectionActionKey.values()
                                    .forEach { key ->
                                        val forcedHidden =
                                            !showPatchProfilesTab && key == PatchSelectionActionKey.SAVE_PROFILE
                                        val visible = !forcedHidden && key.storageId !in hiddenActionsPref
                                        val setVisible: (Boolean) -> Unit = { isVisible ->
                                            if (!forcedHidden) {
                                                val updated = hiddenActionsPref.toMutableSet()
                                                if (isVisible) updated.remove(key.storageId) else updated.add(key.storageId)
                                                viewModel.setPatchSelectionHiddenActions(updated)
                                            }
                                        }

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .clickable(enabled = !forcedHidden) { setVisible(!visible) }
                                                .alpha(if (forcedHidden) 0.7f else 1f)
                                                .padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = stringResource(key.labelRes),
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                                if (forcedHidden) {
                                                    Text(
                                                        text = stringResource(
                                                            R.string.patch_selection_action_forced_hidden_profiles_disabled
                                                        ),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                            ExpressiveSettingsSwitch(
                                                checked = visible,
                                                onCheckedChange = setVisible,
                                                enabled = !forcedHidden
                                            )
                                        }
                                    }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = { viewModel.setPatchSelectionHiddenActions(emptySet()) }
                                ) {
                                    Text(stringResource(R.string.patch_selection_action_visibility_reset))
                                }
                                TextButton(
                                    onClick = {
                                        workingOrder.clear()
                                        workingOrder.addAll(PatchSelectionActionKey.DefaultOrder)
                                        viewModel.setPatchSelectionActionOrder(PatchSelectionActionKey.DefaultOrder)
                                    }
                                ) {
                                    Text(stringResource(R.string.patch_selection_action_order_reset))
                                }
                            }
                    }
                }
            ExpressiveSettingsDivider()
                SettingsSearchHighlight(
                    targetKey = R.string.patch_bundle_action_order_title,
                    activeKey = highlightTarget,
                    onHighlightComplete = onHighlightComplete
                ) { highlightModifier ->
                    ExpressiveSettingsItem(
                        modifier = highlightModifier,
                        headlineContent = stringResource(R.string.patch_bundle_action_order_title),
                        supportingContent = stringResource(R.string.patch_bundle_action_order_description),
                        trailingContent = {
                            Icon(
                                imageVector = if (bundleActionsExpanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                                contentDescription = null
                            )
                        },
                        onClick = { bundleActionsExpanded = !bundleActionsExpanded }
                    )
                }

                if (bundleActionsExpanded) {
                MinimalActionSettings(viewModel.prefs, "bundles")
                    ExpressiveSettingsDivider()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val rowState = rememberLazyListState()
                    val reorderableState = rememberReorderableLazyListState(rowState) { from, to ->
                        bundleWorkingOrder.add(to.index, bundleWorkingOrder.removeAt(from.index))
                    }

                    PatchBundleActionPreview(
                        order = bundleWorkingOrder,
                        hiddenActions = bundleHiddenActionsPref,
                        rowState = rowState,
                        reorderableState = reorderableState
                    )
                }

                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        SettingsSearchHighlight(
                            targetKey = R.string.patch_bundle_action_visibility_title,
                            activeKey = highlightTarget,
                            onHighlightComplete = onHighlightComplete
                        ) { highlightModifier ->
                            Text(
                                text = stringResource(R.string.patch_bundle_action_visibility_title),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = highlightModifier
                            )
                        }
                        Text(
                            text = stringResource(R.string.patch_bundle_action_visibility_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            PatchBundleActionKey.values()
                                .forEach { key ->
                                    val visible = key.storageId !in bundleHiddenActionsPref
                                    val setVisible: (Boolean) -> Unit = { isVisible ->
                                        val updated = bundleHiddenActionsPref.toMutableSet()
                                        if (isVisible) updated.remove(key.storageId) else updated.add(key.storageId)
                                        viewModel.setPatchBundleHiddenActions(updated)
                                    }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable { setVisible(!visible) }
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = stringResource(key.labelRes),
                                            modifier = Modifier.weight(1f),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        ExpressiveSettingsSwitch(
                                            checked = visible,
                                            onCheckedChange = setVisible
                                        )
                                    }
                                }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = { viewModel.setPatchBundleHiddenActions(emptySet()) }
                            ) {
                                Text(stringResource(R.string.patch_bundle_action_visibility_reset))
                            }
                            TextButton(
                                onClick = {
                                    bundleWorkingOrder.clear()
                                    bundleWorkingOrder.addAll(PatchBundleActionKey.DefaultOrder)
                                    viewModel.setPatchBundleActionOrder(PatchBundleActionKey.DefaultOrder)
                                }
                            ) {
                                Text(stringResource(R.string.patch_bundle_action_order_reset))
                            }
                        }
                    }
                }

            ExpressiveSettingsDivider()
            SettingsSearchHighlight(
                targetKey = R.string.batch_result_action_order_title,
                activeKey = highlightTarget,
                onHighlightComplete = onHighlightComplete
            ) { highlightModifier ->
                ExpressiveSettingsItem(
                    modifier = highlightModifier,
                    headlineContent = stringResource(R.string.batch_result_action_order_title),
                    supportingContent = stringResource(R.string.batch_result_action_order_description),
                    trailingContent = {
                        Icon(
                            imageVector = if (batchResultActionsExpanded) {
                                Icons.Outlined.KeyboardArrowUp
                            } else {
                                Icons.Outlined.KeyboardArrowDown
                            },
                            contentDescription = null
                        )
                    },
                    onClick = { batchResultActionsExpanded = !batchResultActionsExpanded }
                )
            }

            if (batchResultActionsExpanded) {
                MinimalActionSettings(viewModel.prefs, "batch")
                ExpressiveSettingsDivider()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val rowState = rememberLazyListState()
                    val reorderableState = rememberReorderableLazyListState(rowState) { from, to ->
                        batchResultWorkingOrder.add(
                            to.index,
                            batchResultWorkingOrder.removeAt(from.index)
                        )
                    }

                    BatchResultActionPreview(
                        order = batchResultWorkingOrder,
                        hiddenActions = batchResultHiddenActionsPref,
                        rowState = rowState,
                        reorderableState = reorderableState
                    )
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    SettingsSearchHighlight(
                        targetKey = R.string.batch_result_action_visibility_title,
                        activeKey = highlightTarget,
                        onHighlightComplete = onHighlightComplete
                    ) { highlightModifier ->
                        Text(
                            text = stringResource(R.string.batch_result_action_visibility_title),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = highlightModifier
                        )
                    }
                    Text(
                        text = stringResource(R.string.batch_result_action_visibility_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        BatchResultActionKey.values().forEach { key ->
                            val visible = key.storageId !in batchResultHiddenActionsPref
                            val setVisible: (Boolean) -> Unit = { isVisible ->
                                val updated = batchResultHiddenActionsPref.toMutableSet()
                                if (isVisible) {
                                    updated.remove(key.storageId)
                                } else {
                                    updated.add(key.storageId)
                                }
                                viewModel.setBatchResultHiddenActions(updated)
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { setVisible(!visible) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(key.labelRes),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                ExpressiveSettingsSwitch(
                                    checked = visible,
                                    onCheckedChange = setVisible
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { viewModel.setBatchResultHiddenActions(emptySet()) }
                        ) {
                            Text(stringResource(R.string.batch_result_action_visibility_reset))
                        }
                        TextButton(
                            onClick = {
                                batchResultWorkingOrder.clear()
                                batchResultWorkingOrder.addAll(BatchResultActionKey.DefaultOrder)
                                viewModel.setBatchResultActionOrder(BatchResultActionKey.DefaultOrder)
                            }
                        ) {
                            Text(stringResource(R.string.batch_result_action_order_reset))
                        }
                    }
                }
            }

            ExpressiveSettingsDivider()
            SettingsSearchHighlight(
                targetKey = R.string.saved_app_action_order_title,
                activeKey = highlightTarget,
                onHighlightComplete = onHighlightComplete
            ) { highlightModifier ->
                ExpressiveSettingsItem(
                    modifier = highlightModifier,
                    headlineContent = stringResource(R.string.saved_app_action_order_title),
                    supportingContent = stringResource(R.string.saved_app_action_order_description),
                    trailingContent = {
                        Icon(
                            imageVector = if (savedActionsExpanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                            contentDescription = null
                        )
                    },
                    onClick = { savedActionsExpanded = !savedActionsExpanded }
                )
            }

            if (savedActionsExpanded) {
                MinimalActionSettings(viewModel.prefs, "saved")
                ExpressiveSettingsDivider()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val rowState = rememberLazyListState()
                    val reorderableState = rememberReorderableLazyListState(rowState) { from, to ->
                        savedWorkingOrder.add(to.index, savedWorkingOrder.removeAt(from.index))
                    }

                    SavedAppActionPreview(
                        order = savedWorkingOrder,
                        hiddenActions = savedHiddenActionsPref,
                        rowState = rowState,
                        reorderableState = reorderableState
                    )
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    SettingsSearchHighlight(
                        targetKey = R.string.saved_app_action_visibility_title,
                        activeKey = highlightTarget,
                        onHighlightComplete = onHighlightComplete
                    ) { highlightModifier ->
                        Text(
                            text = stringResource(R.string.saved_app_action_visibility_title),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = highlightModifier
                        )
                    }
                    Text(
                        text = stringResource(R.string.saved_app_action_visibility_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SavedAppActionKey.values()
                            .forEach { key ->
                                val visible = key.storageId !in savedHiddenActionsPref
                                val setVisible: (Boolean) -> Unit = { isVisible ->
                                    val updated = savedHiddenActionsPref.toMutableSet()
                                    if (isVisible) updated.remove(key.storageId) else updated.add(key.storageId)
                                    viewModel.setSavedAppHiddenActions(updated)
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { setVisible(!visible) }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(key.labelRes),
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    ExpressiveSettingsSwitch(
                                        checked = visible,
                                        onCheckedChange = setVisible
                                    )
                                }
                            }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { viewModel.setSavedAppHiddenActions(emptySet()) }
                        ) {
                            Text(stringResource(R.string.saved_app_action_visibility_reset))
                        }
                        TextButton(
                            onClick = {
                                savedWorkingOrder.clear()
                                savedWorkingOrder.addAll(SavedAppActionKey.DefaultOrder)
                                viewModel.setSavedAppActionOrder(SavedAppActionKey.DefaultOrder)
                            }
                        ) {
                            Text(stringResource(R.string.saved_app_action_order_reset))
                        }
                    }
                }
            }
            ExpressiveSettingsDivider()
            SettingsSearchHighlight(
                targetKey = R.string.patch_profile_action_order_title,
                activeKey = highlightTarget,
                onHighlightComplete = onHighlightComplete
            ) { highlightModifier ->
                ExpressiveSettingsItem(
                    modifier = highlightModifier,
                    headlineContent = stringResource(R.string.patch_profile_action_order_title),
                    supportingContent = stringResource(R.string.patch_profile_action_order_description),
                    trailingContent = {
                        Icon(
                            imageVector = if (profileActionsExpanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                            contentDescription = null
                        )
                    },
                    onClick = { profileActionsExpanded = !profileActionsExpanded }
                )
            }

            if (profileActionsExpanded) {
                MinimalActionSettings(viewModel.prefs, "profiles")
                ExpressiveSettingsDivider()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val rowState = rememberLazyListState()
                    val reorderableState = rememberReorderableLazyListState(rowState) { from, to ->
                        profileWorkingOrder.add(to.index, profileWorkingOrder.removeAt(from.index))
                    }

                    PatchProfileActionPreview(
                        order = profileWorkingOrder,
                        hiddenActions = profileHiddenActionsPref,
                        rowState = rowState,
                        reorderableState = reorderableState
                    )
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    SettingsSearchHighlight(
                        targetKey = R.string.patch_profile_action_visibility_title,
                        activeKey = highlightTarget,
                        onHighlightComplete = onHighlightComplete
                    ) { highlightModifier ->
                        Text(
                            text = stringResource(R.string.patch_profile_action_visibility_title),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = highlightModifier
                        )
                    }
                    Text(
                        text = stringResource(R.string.patch_profile_action_visibility_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PatchProfileActionKey.values()
                            .forEach { key ->
                                val visible = key.storageId !in profileHiddenActionsPref
                                val setVisible: (Boolean) -> Unit = { isVisible ->
                                    val updated = profileHiddenActionsPref.toMutableSet()
                                    if (isVisible) updated.remove(key.storageId) else updated.add(key.storageId)
                                    viewModel.setPatchProfileHiddenActions(updated)
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { setVisible(!visible) }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(key.labelRes),
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    ExpressiveSettingsSwitch(
                                        checked = visible,
                                        onCheckedChange = setVisible
                                    )
                                }
                            }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { viewModel.setPatchProfileHiddenActions(emptySet()) }
                        ) {
                            Text(stringResource(R.string.patch_profile_action_visibility_reset))
                        }
                        TextButton(
                            onClick = {
                                profileWorkingOrder.clear()
                                profileWorkingOrder.addAll(PatchProfileActionKey.DefaultOrder)
                                viewModel.setPatchProfileActionOrder(PatchProfileActionKey.DefaultOrder)
                            }
                        ) {
                            Text(stringResource(R.string.patch_profile_action_order_reset))
                        }
                    }
                }
            }

            ExpressiveSettingsDivider()
            SettingsSearchHighlight(
                targetKey = R.string.lsposed_module_action_order_title,
                activeKey = highlightTarget,
                onHighlightComplete = onHighlightComplete
            ) { highlightModifier ->
                ExpressiveSettingsItem(
                    modifier = highlightModifier,
                    headlineContent = stringResource(R.string.lsposed_module_action_order_title),
                    supportingContent = stringResource(R.string.lsposed_module_action_order_description),
                    trailingContent = {
                        Icon(
                            imageVector = if (lsposedActionsExpanded) {
                                Icons.Outlined.KeyboardArrowUp
                            } else {
                                Icons.Outlined.KeyboardArrowDown
                            },
                            contentDescription = null
                        )
                    },
                    onClick = { lsposedActionsExpanded = !lsposedActionsExpanded }
                )
            }

            if (lsposedActionsExpanded) {
                MinimalActionSettings(viewModel.prefs, "lsposed")
                ExpressiveSettingsDivider()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val rowState = rememberLazyListState()
                    val reorderableState = rememberReorderableLazyListState(rowState) { from, to ->
                        lsposedWorkingOrder.add(
                            to.index,
                            lsposedWorkingOrder.removeAt(from.index)
                        )
                    }

                    LsposedModuleActionPreview(
                        order = lsposedWorkingOrder,
                        hiddenActions = lsposedHiddenActionsPref,
                        rowState = rowState,
                        reorderableState = reorderableState
                    )
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    SettingsSearchHighlight(
                        targetKey = R.string.lsposed_module_action_visibility_title,
                        activeKey = highlightTarget,
                        onHighlightComplete = onHighlightComplete
                    ) { highlightModifier ->
                        Text(
                            text = stringResource(R.string.lsposed_module_action_visibility_title),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = highlightModifier
                        )
                    }
                    Text(
                        text = stringResource(R.string.lsposed_module_action_visibility_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LsposedModuleActionKey.values().forEach { key ->
                            val visible = key.storageId !in lsposedHiddenActionsPref
                            val setVisible: (Boolean) -> Unit = { isVisible ->
                                val updated = lsposedHiddenActionsPref.toMutableSet()
                                if (isVisible) updated.remove(key.storageId) else updated.add(key.storageId)
                                viewModel.setLsposedModuleHiddenActions(updated)
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { setVisible(!visible) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(key.labelRes),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                ExpressiveSettingsSwitch(
                                    checked = visible,
                                    onCheckedChange = setVisible
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { viewModel.setLsposedModuleHiddenActions(emptySet()) }
                        ) {
                            Text(stringResource(R.string.lsposed_module_action_visibility_reset))
                        }
                        TextButton(
                            onClick = {
                                lsposedWorkingOrder.clear()
                                lsposedWorkingOrder.addAll(LsposedModuleActionKey.DefaultOrder)
                                viewModel.setLsposedModuleActionOrder(
                                    LsposedModuleActionKey.DefaultOrder
                                )
                            }
                        ) {
                            Text(stringResource(R.string.lsposed_module_action_order_reset))
                        }
                    }
                }
            }

    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PatchSelectionActionPreview(
    order: List<PatchSelectionActionKey>,
    hiddenActions: Set<String> = emptySet(),
    modifier: Modifier = Modifier,
    centerContent: Boolean = false,
    draggingKey: PatchSelectionActionKey? = null,
    highlightKey: PatchSelectionActionKey? = null,
    firstRowState: LazyListState = rememberLazyListState(),
    secondRowState: LazyListState = rememberLazyListState(),
    userScrollEnabled: Boolean = true,
    onPopupBoundsChanged: ((Rect) -> Unit)? = null,
    onBoundsDisposed: ((PatchSelectionActionKey) -> Unit)? = null,
    onBoundsChanged: ((PatchSelectionActionKey, Rect) -> Unit)? = null
) {
    val splitIndex = (order.size + 1) / 2
    val firstRow = remember(order) { order.take(splitIndex) }
    val secondRow = remember(order) { order.drop(splitIndex) }
    val density = LocalDensity.current
    val glowRadiusPx = remember(density) { with(density) { 220.dp.toPx() } }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = if (centerContent || minimalActionButtons("selection")) Alignment.Center else Alignment.CenterEnd
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                tonalElevation = 0.dp,
                shadowElevation = 6.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)),
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .onGloballyPositioned { coords ->
                        onPopupBoundsChanged?.invoke(coords.boundsInRoot())
                    }
            ) {
                Box {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .blur(26.dp)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                        Color.Transparent
                                    ),
                                    radius = glowRadiusPx
                                )
                            )
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.16f))
                    )

                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        SelectionActionPreviewRow(
                            keys = firstRow,
                            hiddenActions = hiddenActions,
                            draggingKey = draggingKey,
                            highlightKey = highlightKey,
                            state = firstRowState,
                            userScrollEnabled = userScrollEnabled,
                            onBoundsDisposed = onBoundsDisposed,
                            onBoundsChanged = onBoundsChanged
                        )
                        if (secondRow.isNotEmpty()) {
                            SelectionActionPreviewRow(
                                keys = secondRow,
                                hiddenActions = hiddenActions,
                                draggingKey = draggingKey,
                                highlightKey = highlightKey,
                                state = secondRowState,
                                userScrollEnabled = userScrollEnabled,
                                onBoundsDisposed = onBoundsDisposed,
                                onBoundsChanged = onBoundsChanged
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionActionPreviewRow(
    keys: List<PatchSelectionActionKey>,
    hiddenActions: Set<String>,
    draggingKey: PatchSelectionActionKey?,
    highlightKey: PatchSelectionActionKey?,
    state: LazyListState,
    userScrollEnabled: Boolean,
    onBoundsDisposed: ((PatchSelectionActionKey) -> Unit)?,
    onBoundsChanged: ((PatchSelectionActionKey, Rect) -> Unit)?
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        state = state,
        userScrollEnabled = userScrollEnabled,
        horizontalArrangement = Arrangement.spacedBy(
            6.dp,
            if (minimalActionButtons("selection")) Alignment.CenterHorizontally else Alignment.End
        )
    ) {
        items(
            items = keys,
            key = { key -> key.storageId }
        ) { key ->
            DisposableEffect(key) {
                onDispose { onBoundsDisposed?.invoke(key) }
            }
            Box(
                modifier = Modifier.fillParentMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                SelectionActionPreviewChip(
                    icon = previewIconForAction(key),
                    label = stringResource(key.labelRes),
                    hidden = key.storageId in hiddenActions,
                    dragging = draggingKey == key,
                    highlighted = highlightKey == key,
                    ghost = draggingKey == key,
                    onBoundsChanged = { rect -> onBoundsChanged?.invoke(key, rect) }
                )
            }
        }
    }
}

@Composable
private fun SelectionActionPreviewChip(
    icon: ImageVector,
    label: String,
    hidden: Boolean = false,
    modifier: Modifier = Modifier,
    dragging: Boolean = false,
    highlighted: Boolean = false,
    ghost: Boolean = false,
    floating: Boolean = false,
    group: String = "selection",
    onBoundsChanged: ((Rect) -> Unit)? = null
) {
    var layoutCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    val typography = MaterialTheme.typography
    val labelStyle = remember(typography) {
        typography.labelMedium.copy(
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeight = typography.labelMedium.fontSize,
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.Both
            )
        )
    }

    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        tonalElevation = if (dragging) 8.dp else 4.dp,
        shadowElevation = if (dragging) 4.dp else 1.dp,
        border = if (highlighted && !floating) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = modifier
            .onGloballyPositioned { coords ->
                layoutCoordinates = coords
                onBoundsChanged?.invoke(coords.boundsInRoot())
            }
            .graphicsLayer {
                when {
                    floating -> {
                        alpha = 0.9f
                        scaleX = 1.03f
                        scaleY = 1.03f
                        shadowElevation = 24f
                    }
                    ghost -> alpha = 0.12f
                    hidden -> alpha = 0.55f
                }
            }
    ) {
        val iconOffset = 2.dp
        Row(
            modifier = Modifier
                .height(28.dp)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier
                    .size(16.dp)
                    .offset(y = iconOffset)
            )
            if (!minimalActionButtons(group)) Text(
                text = label,
                style = labelStyle,
                maxLines = 1
            )
            if (hidden) {
                Spacer(modifier = Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.Outlined.VisibilityOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                    modifier = Modifier
                        .size(14.dp)
                        .offset(y = iconOffset)
                )
            }
        }
    }
}

private fun previewIconForAction(key: PatchSelectionActionKey): ImageVector =
    when (key) {
        PatchSelectionActionKey.UNDO -> Icons.AutoMirrored.Outlined.Undo
        PatchSelectionActionKey.REDO -> Icons.AutoMirrored.Outlined.Redo
        PatchSelectionActionKey.SELECT_BUNDLE -> Icons.AutoMirrored.Outlined.PlaylistAddCheck
        PatchSelectionActionKey.SELECT_ALL -> Icons.Outlined.DoneAll
        PatchSelectionActionKey.DESELECT_BUNDLE -> Icons.Outlined.LayersClear
        PatchSelectionActionKey.DESELECT_ALL -> Icons.Outlined.ClearAll
        PatchSelectionActionKey.BUNDLE_DEFAULTS -> Icons.Outlined.SettingsBackupRestore
        PatchSelectionActionKey.ALL_DEFAULTS -> Icons.Outlined.Restore
        PatchSelectionActionKey.SAVE_PROFILE -> Icons.AutoMirrored.Outlined.PlaylistAdd
    }

@Composable
private fun PatchBundleActionPreview(
    order: List<PatchBundleActionKey>,
    hiddenActions: Set<String> = emptySet(),
    modifier: Modifier = Modifier,
    rowState: LazyListState = rememberLazyListState(),
    reorderableState: sh.calvin.reorderable.ReorderableLazyListState
) {
    val density = LocalDensity.current
    val glowRadiusPx = remember(density) { with(density) { 200.dp.toPx() } }

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
            tonalElevation = 0.dp,
            shadowElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)),
            modifier = Modifier
                .widthIn(max = 520.dp)
        ) {
            Box {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .blur(26.dp)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    Color.Transparent
                                ),
                                radius = glowRadiusPx
                            )
                        )
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.16f))
                )

                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    PatchBundleActionPreviewRow(
                        keys = order,
                        hiddenActions = hiddenActions,
                        state = rowState,
                        reorderableState = reorderableState
                    )
                }
            }
        }
    }
}

@Composable
private fun PatchBundleActionPreviewRow(
    keys: List<PatchBundleActionKey>,
    hiddenActions: Set<String>,
    state: LazyListState,
    reorderableState: sh.calvin.reorderable.ReorderableLazyListState
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        state = state,
        horizontalArrangement = if (minimalActionButtons("bundles")) {
            Arrangement.spacedBy(0.dp, Alignment.CenterHorizontally)
        } else {
            Arrangement.Start
        },
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
        items(
            items = keys,
            key = { key -> key.storageId }
        ) { key ->
            ReorderableItem(reorderableState, key = key.storageId) { isDragging ->
                Box(
                    modifier = Modifier
                        .fillParentMaxHeight()
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    SelectionActionPreviewChip(
                        group = "bundles",
                        icon = previewIconForBundleAction(key),
                        label = stringResource(key.labelRes),
                        dragging = isDragging,
                        ghost = isDragging,
                        hidden = key.storageId in hiddenActions,
                        modifier = Modifier.longPressDraggableHandle()
                    )
                }
            }
        }
    }
}

@Composable
private fun BatchResultActionPreview(
    order: List<BatchResultActionKey>,
    hiddenActions: Set<String> = emptySet(),
    modifier: Modifier = Modifier,
    rowState: LazyListState = rememberLazyListState(),
    reorderableState: sh.calvin.reorderable.ReorderableLazyListState
) {
    val density = LocalDensity.current
    val glowRadiusPx = remember(density) { with(density) { 200.dp.toPx() } }

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
            tonalElevation = 0.dp,
            shadowElevation = 6.dp,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)
            ),
            modifier = Modifier.widthIn(max = 520.dp)
        ) {
            Box {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .blur(26.dp)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    Color.Transparent
                                ),
                                radius = glowRadiusPx
                            )
                        )
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.16f))
                )

                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    BatchResultActionPreviewRow(
                        keys = order,
                        hiddenActions = hiddenActions,
                        state = rowState,
                        reorderableState = reorderableState
                    )
                }
            }
        }
    }
}

@Composable
private fun BatchResultActionPreviewRow(
    keys: List<BatchResultActionKey>,
    hiddenActions: Set<String>,
    state: LazyListState,
    reorderableState: sh.calvin.reorderable.ReorderableLazyListState
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        state = state,
        horizontalArrangement = if (minimalActionButtons("batch")) {
            Arrangement.spacedBy(0.dp, Alignment.CenterHorizontally)
        } else {
            Arrangement.Start
        },
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
        items(
            items = keys,
            key = { key -> key.storageId }
        ) { key ->
            ReorderableItem(reorderableState, key = key.storageId) { isDragging ->
                Box(
                    modifier = Modifier
                        .fillParentMaxHeight()
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    SelectionActionPreviewChip(
                        group = "batch",
                        icon = previewIconForBatchResultAction(key),
                        label = stringResource(key.labelRes),
                        dragging = isDragging,
                        ghost = isDragging,
                        hidden = key.storageId in hiddenActions,
                        modifier = Modifier.longPressDraggableHandle()
                    )
                }
            }
        }
    }
}

@Composable
private fun SavedAppActionPreview(
    order: List<SavedAppActionKey>,
    hiddenActions: Set<String> = emptySet(),
    modifier: Modifier = Modifier,
    rowState: LazyListState = rememberLazyListState(),
    reorderableState: sh.calvin.reorderable.ReorderableLazyListState
) {
    val density = LocalDensity.current
    val glowRadiusPx = remember(density) { with(density) { 200.dp.toPx() } }

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
            tonalElevation = 0.dp,
            shadowElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)),
            modifier = Modifier
                .widthIn(max = 520.dp)
        ) {
            Box {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .blur(26.dp)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    Color.Transparent
                                ),
                                radius = glowRadiusPx
                            )
                        )
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.16f))
                )

                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    SavedAppActionPreviewRow(
                        keys = order,
                        hiddenActions = hiddenActions,
                        state = rowState,
                        reorderableState = reorderableState
                    )
                }
            }
        }
    }
}

@Composable
private fun SavedAppActionPreviewRow(
    keys: List<SavedAppActionKey>,
    hiddenActions: Set<String>,
    state: LazyListState,
    reorderableState: sh.calvin.reorderable.ReorderableLazyListState
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        state = state,
        horizontalArrangement = if (minimalActionButtons("saved")) {
            Arrangement.spacedBy(0.dp, Alignment.CenterHorizontally)
        } else {
            Arrangement.Start
        },
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
        items(
            items = keys,
            key = { key -> key.storageId }
        ) { key ->
            ReorderableItem(reorderableState, key = key.storageId) { isDragging ->
                Box(
                    modifier = Modifier
                        .fillParentMaxHeight()
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    SelectionActionPreviewChip(
                        group = "saved",
                        icon = previewIconForSavedAppAction(key),
                        label = stringResource(key.labelRes),
                        dragging = isDragging,
                        ghost = isDragging,
                        hidden = key.storageId in hiddenActions,
                        modifier = Modifier.longPressDraggableHandle()
                    )
                }
            }
        }
    }
}

@Composable
private fun PatchProfileActionPreview(
    order: List<PatchProfileActionKey>,
    hiddenActions: Set<String> = emptySet(),
    modifier: Modifier = Modifier,
    rowState: LazyListState = rememberLazyListState(),
    reorderableState: sh.calvin.reorderable.ReorderableLazyListState
) {
    val density = LocalDensity.current
    val glowRadiusPx = remember(density) { with(density) { 200.dp.toPx() } }

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
            tonalElevation = 0.dp,
            shadowElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)),
            modifier = Modifier.widthIn(max = 520.dp)
        ) {
            Box {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .blur(26.dp)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    Color.Transparent
                                ),
                                radius = glowRadiusPx
                            )
                        )
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.16f))
                )

                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    PatchProfileActionPreviewRow(
                        keys = order,
                        hiddenActions = hiddenActions,
                        state = rowState,
                        reorderableState = reorderableState
                    )
                }
            }
        }
    }
}

@Composable
private fun PatchProfileActionPreviewRow(
    keys: List<PatchProfileActionKey>,
    hiddenActions: Set<String>,
    state: LazyListState,
    reorderableState: sh.calvin.reorderable.ReorderableLazyListState
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        state = state,
        horizontalArrangement = if (minimalActionButtons("profiles")) {
            Arrangement.spacedBy(0.dp, Alignment.CenterHorizontally)
        } else {
            Arrangement.Start
        },
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
        items(
            items = keys,
            key = { key -> key.storageId }
        ) { key ->
            ReorderableItem(reorderableState, key = key.storageId) { isDragging ->
                Box(
                    modifier = Modifier
                        .fillParentMaxHeight()
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    SelectionActionPreviewChip(
                        group = "profiles",
                        icon = previewIconForProfileAction(key),
                        label = stringResource(key.labelRes),
                        dragging = isDragging,
                        ghost = isDragging,
                        hidden = key.storageId in hiddenActions,
                        modifier = Modifier.longPressDraggableHandle()
                    )
                }
            }
        }
    }
}

@Composable
private fun LsposedModuleActionPreview(
    order: List<LsposedModuleActionKey>,
    hiddenActions: Set<String> = emptySet(),
    modifier: Modifier = Modifier,
    rowState: LazyListState = rememberLazyListState(),
    reorderableState: sh.calvin.reorderable.ReorderableLazyListState
) {
    val density = LocalDensity.current
    val glowRadiusPx = remember(density) { with(density) { 200.dp.toPx() } }

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
            tonalElevation = 0.dp,
            shadowElevation = 6.dp,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)
            ),
            modifier = Modifier.widthIn(max = 520.dp)
        ) {
            Box {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .blur(26.dp)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    Color.Transparent
                                ),
                                radius = glowRadiusPx
                            )
                        )
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.16f)
                        )
                )

                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    LsposedModuleActionPreviewRow(
                        keys = order,
                        hiddenActions = hiddenActions,
                        state = rowState,
                        reorderableState = reorderableState
                    )
                }
            }
        }
    }
}

@Composable
private fun LsposedModuleActionPreviewRow(
    keys: List<LsposedModuleActionKey>,
    hiddenActions: Set<String>,
    state: LazyListState,
    reorderableState: sh.calvin.reorderable.ReorderableLazyListState
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        state = state,
        horizontalArrangement = if (minimalActionButtons("lsposed")) {
            Arrangement.spacedBy(0.dp, Alignment.CenterHorizontally)
        } else {
            Arrangement.Start
        },
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
        items(
            items = keys,
            key = { key -> key.storageId }
        ) { key ->
            ReorderableItem(reorderableState, key = key.storageId) { isDragging ->
                Box(
                    modifier = Modifier
                        .fillParentMaxHeight()
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    SelectionActionPreviewChip(
                        group = "lsposed",
                        icon = previewIconForLsposedModuleAction(key),
                        label = stringResource(key.labelRes),
                        dragging = isDragging,
                        ghost = isDragging,
                        hidden = key.storageId in hiddenActions,
                        modifier = Modifier.longPressDraggableHandle()
                    )
                }
            }
        }
    }
}

private fun previewIconForBundleAction(key: PatchBundleActionKey): ImageVector =
    when (key) {
        PatchBundleActionKey.EDIT -> Icons.Outlined.Edit
        PatchBundleActionKey.REFRESH -> Icons.Outlined.Update
        PatchBundleActionKey.RELEASE_PAGE -> Icons.Outlined.Link
        PatchBundleActionKey.CHANGELOG_LATEST -> Icons.Outlined.Description
        PatchBundleActionKey.CHANGELOG_HISTORY -> Icons.Outlined.History
        PatchBundleActionKey.TOGGLE -> Icons.Outlined.Block
        PatchBundleActionKey.DELETE -> Icons.Outlined.Delete
    }

private fun previewIconForBatchResultAction(key: BatchResultActionKey): ImageVector =
    when (key) {
        BatchResultActionKey.VIEW_PROGRESS -> Icons.Outlined.Visibility
        BatchResultActionKey.SAVE_LOGS -> Icons.Outlined.Description
        BatchResultActionKey.SAVE_APK -> Icons.Outlined.Save
        BatchResultActionKey.INSTALL_OR_OPEN -> Icons.Outlined.InstallMobile
    }

private fun previewIconForSavedAppAction(key: SavedAppActionKey): ImageVector =
    when (key) {
        SavedAppActionKey.OPEN -> Icons.AutoMirrored.Outlined.OpenInNew
        SavedAppActionKey.EXPORT -> Icons.Outlined.Save
        SavedAppActionKey.INSTALL_UPDATE -> Icons.Outlined.InstallMobile
        SavedAppActionKey.REPAIR_ROOT_MOUNT -> Icons.Outlined.SettingsBackupRestore
        SavedAppActionKey.EXPORT_ROOT_MOUNT_DIAGNOSTICS -> Icons.Outlined.Save
        SavedAppActionKey.DELETE -> Icons.Outlined.Delete
        SavedAppActionKey.REPATCH -> Icons.Outlined.Update
    }

private fun previewIconForProfileAction(key: PatchProfileActionKey): ImageVector =
    when (key) {
        PatchProfileActionKey.RENAME -> Icons.Outlined.Edit
        PatchProfileActionKey.VIEW_PATCHES -> Icons.Outlined.Extension
        PatchProfileActionKey.SETTINGS -> Icons.Outlined.Settings
    }

private fun previewIconForLsposedModuleAction(
    key: LsposedModuleActionKey
): ImageVector = when (key) {
    LsposedModuleActionKey.MANAGER -> Icons.AutoMirrored.Outlined.OpenInNew
    LsposedModuleActionKey.SETTINGS -> Icons.Outlined.Settings
    LsposedModuleActionKey.UPDATE -> Icons.Outlined.Update
    LsposedModuleActionKey.REINSTALL -> Icons.Outlined.InstallMobile
    LsposedModuleActionKey.UNINSTALL -> Icons.Outlined.Delete
    LsposedModuleActionKey.FORGET -> Icons.Outlined.Block
}

@Composable
private fun ExportNameFormatDialog(
    currentValue: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    @StringRes titleRes: Int = R.string.export_name_format_dialog_title,
    @StringRes supportingRes: Int = R.string.export_name_format_dialog_supporting,
    @StringRes fieldLabelRes: Int = R.string.export_name_format,
    @StringRes resetLabelRes: Int = R.string.export_name_format_reset,
    defaultFormatTemplate: String = ExportNameFormatter.DEFAULT_TEMPLATE,
    formatPreview: (String) -> String = { ExportNameFormatter.preview(it) },
    availableVariables: List<ExportNameFormatter.Variable> = ExportNameFormatter.availableVariables()
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val textFieldState = rememberTextFieldState(
        initialText = currentValue,
        initialSelection = TextRange(currentValue.length)
    )
    var useAppendInsertionFallback by rememberSaveable(currentValue) { mutableStateOf(true) }
    var showError by rememberSaveable { mutableStateOf(false) }
    val variables = remember(availableVariables) { availableVariables }
    val preview = remember(textFieldState.text) {
        formatPreview(textFieldState.text.toString())
    }

    val helperScrollState = rememberScrollState()
    val formatFieldScrollState = rememberScrollState()

    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }
            .collectLatest { text ->
                if (showError && text.isNotBlank()) {
                    showError = false
                }
            }
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    AlertDialog(
        modifier = Modifier.imePadding(),
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val currentText = textFieldState.text.toString()
                if (currentText.isBlank()) {
                    showError = true
                } else {
                    onSave(currentText.trim())
                }
            }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        title = { CenteredDialogTitle(stringResource(titleRes)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Text(
                    text = stringResource(supportingRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    state = textFieldState,
                    lineLimits = TextFieldLineLimits.SingleLine,
                    scrollState = formatFieldScrollState,
                    label = { Text(stringResource(fieldLabelRes)) },
                    isError = showError && textFieldState.text.isBlank(),
                    supportingText = if (showError && textFieldState.text.isBlank()) {
                        { Text(stringResource(R.string.export_name_format_error_blank)) }
                    } else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )
                Surface(
                    tonalElevation = 2.dp,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.export_name_format_preview_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = preview,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(helperScrollState),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = stringResource(R.string.export_name_format_variables),
                            style = MaterialTheme.typography.titleSmall
                        )
                        variables.forEach { variable ->
                            Surface(
                                tonalElevation = 1.dp,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = stringResource(variable.label),
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        TextButton(onClick = {
                                            val currentText = textFieldState.text.toString()
                                            val currentSelection = if (
                                                useAppendInsertionFallback &&
                                                textFieldState.selection.collapsed &&
                                                textFieldState.selection.start == 0
                                            ) {
                                                TextRange(currentText.length)
                                            } else {
                                                textFieldState.selection
                                            }
                                            val validSelection =
                                                currentSelection.start in 0..currentText.length &&
                                                    currentSelection.end in 0..currentText.length &&
                                                    currentSelection.start <= currentSelection.end

                                            textFieldState.edit {
                                                val insertionStart = if (validSelection) {
                                                    currentSelection.start
                                                } else {
                                                    currentText.length
                                                }
                                                val insertionEnd = if (validSelection) {
                                                    currentSelection.end
                                                } else {
                                                    currentText.length
                                                }
                                                replace(insertionStart, insertionEnd, variable.token)
                                                this.selection = TextRange(insertionStart + variable.token.length)
                                            }
                                            useAppendInsertionFallback = false
                                            if (showError) showError = false
                                        }) {
                                            Text(stringResource(R.string.export_name_format_insert))
                                        }
                                    }
                                    Text(
                                        text = variable.token,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = stringResource(variable.description),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    TextButton(
                        onClick = {
                            textFieldState.edit {
                                replace(0, length, defaultFormatTemplate)
                                selection = TextRange(defaultFormatTemplate.length)
                            }
                            useAppendInsertionFallback = false
                            showError = false
                        },
                        modifier = Modifier.align(Alignment.Start)
                    ) {
                        Text(stringResource(resetLabelRes))
                    }
                }
            }
        }
    )
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomInstallerManagerDialog(
    installerManager: InstallerManager,
    viewModel: AdvancedSettingsViewModel,
    installTarget: InstallerManager.InstallTarget,
    onDismiss: () -> Unit
) {
    val customValues by viewModel.prefs.installerCustomComponents.getAsState()
    val hiddenValues by viewModel.prefs.installerHiddenComponents.getAsState()
    val customComponentNames = remember(customValues) {
        customValues.mapNotNull(ComponentName::unflattenFromString).toSet()
    }
    val hiddenComponentNames = remember(hiddenValues) {
        hiddenValues.mapNotNull(ComponentName::unflattenFromString).toSet()
    }
    val builtinComponents = remember(installTarget, customComponentNames, hiddenComponentNames) {
        val autoComponents = installerManager.listEntries(installTarget, includeNone = true)
            .mapNotNull { (it.token as? InstallerManager.Token.Component)?.componentName }
            .filterNot { it in customComponentNames || it in hiddenComponentNames }
            .toMutableSet()
        val packageInstallerComponent = ComponentName(
            "com.google.android.packageinstaller",
            "com.android.packageinstaller.PackageInstallerActivity"
        )
        autoComponents.removeAll { it.packageName == packageInstallerComponent.packageName }
        autoComponents += packageInstallerComponent
        autoComponents
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        CustomInstallerContent(
            installerManager = installerManager,
            viewModel = viewModel,
            installTarget = installTarget,
            customComponents = customValues,
            builtinComponents = builtinComponents,
            onClose = {
                coroutineScope.launch {
                    sheetState.hide()
                    onDismiss()
                }
            }
        )
    }
}

@Composable
private fun CustomInstallerContent(
    installerManager: InstallerManager,
    viewModel: AdvancedSettingsViewModel,
    installTarget: InstallerManager.InstallTarget,
    customComponents: Set<String>,
    builtinComponents: Set<ComponentName>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val savedComponents = remember(customComponents) { customComponents.toSet() }
    val savedEntries = remember(customComponents, installTarget) {
        customComponents.mapNotNull { flattened ->
            ComponentName.unflattenFromString(flattened)?.let { component ->
                installerManager.describeEntry(InstallerManager.Token.Component(component), installTarget)
                    ?.let { component to it }
            }
        }.sortedBy { (_, entry) -> entry.label.lowercase() }
    }
    var inputValue by rememberSaveable { mutableStateOf("") }
    var lookupResults by remember { mutableStateOf<List<InstallerManager.Entry>>(emptyList()) }
    var lastQuery by remember { mutableStateOf<String?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val trimmedInput = remember(inputValue) { inputValue.trim() }
    var selectedTab by rememberSaveable { mutableStateOf(InstallerTab.Saved) }
    val scrollState = rememberScrollState()
            val autoSavedEntries = remember(builtinComponents, installTarget) {
                buildList {
                    // Built-in system installers discovered automatically.
                    addAll(
                        builtinComponents.mapNotNull { component ->
                            installerManager.describeEntry(InstallerManager.Token.Component(component), installTarget)
                                ?.let { component to it }
                        }
                    )
                    // Add mount installer as an auto-saved option.
                    installerManager.describeEntry(InstallerManager.Token.AutoSaved, installTarget)
                        ?.let { add(null to it) }
                }.sortedBy { (_, entry) -> entry.label.lowercase() }
            }

    fun handleLookup(packageName: String) {
        coroutineScope.launch {
            val normalized = packageName.trim()
            if (normalized.isEmpty()) {
                isSearching = false
                lastQuery = null
                lookupResults = emptyList()
                context.toast(context.getString(R.string.installer_custom_lookup_empty))
                return@launch
            }
            isSearching = true
            val entries = try {
                withContext(Dispatchers.Default) {
                    viewModel.searchInstallerEntries(normalized, installTarget)
                }
            } finally {
                isSearching = false
            }
            lastQuery = normalized
            lookupResults = entries
            if (entries.isEmpty()) {
                context.toast(context.getString(R.string.installer_custom_lookup_none, normalized))
            } else {
                context.toast(context.getString(R.string.installer_custom_lookup_found, entries.size))
            }
        }
    }

    fun handleAdd(component: ComponentName) {
        viewModel.addCustomInstaller(component) { added ->
            val messageRes = if (added) {
                R.string.installer_custom_added
            } else {
                R.string.installer_custom_exists
            }
            context.toast(context.getString(messageRes))
        }
    }

    fun handleRemove(component: ComponentName) {
        viewModel.removeCustomInstaller(component) { removed ->
            val messageRes = if (removed) {
                R.string.installer_custom_removed
            } else {
                R.string.installer_custom_remove_failed
            }
            context.toast(context.getString(messageRes))
        }
    }

    LaunchedEffect(isSearching) {
        if (isSearching) {
            context.toast(context.getString(R.string.installer_custom_searching))
            while (isSearching) {
                delay(2_000)
                if (isSearching) {
                    context.toast(context.getString(R.string.installer_custom_searching))
                }
            }
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(scrollState)
    ) {
        Text(
            text = stringResource(R.string.installer_custom_header),
            style = MaterialTheme.typography.titleLarge
        )
        TabRow(selectedTabIndex = selectedTab.ordinal) {
            InstallerTab.entries.forEachIndexed { index, tab ->
                Tab(
                    selected = selectedTab.ordinal == index,
                    onClick = { selectedTab = tab },
                    text = { Text(stringResource(tab.titleRes)) }
                )
            }
        }

        @Composable
        fun StatusBadge(text: String, modifier: Modifier = Modifier) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                tonalElevation = 0.dp,
                modifier = modifier
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        when (selectedTab) {
            InstallerTab.Saved -> {
                Text(
                    text = stringResource(R.string.installer_custom_saved_header),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.installer_custom_saved_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (savedEntries.isEmpty()) {
                    Text(
                        text = stringResource(R.string.installer_custom_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        savedEntries.forEach { (component, entry) ->
                            val isBuiltinSaved = component in builtinComponents ||
                                component.packageName == "com.google.android.packageinstaller"
                            val badgeText = when {
                                isBuiltinSaved -> stringResource(R.string.installer_custom_builtin_indicator)
                                component.flattenToString() in savedComponents -> stringResource(R.string.installer_custom_saved_indicator)
                                else -> null
                            }
                            val supportingLines = buildList {
                                entry.description?.takeIf { it.isNotBlank() }?.let { add(it) }
                                entry.availability.reason?.let { add(context.getString(it)) }
                                add(component.flattenToString())
                            }
                            ListItem(
                                headlineContent = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            val labelScrollState = rememberScrollState()
                                            Text(
                                                text = entry.label,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier
                                                    .consumeHorizontalScroll(labelScrollState)
                                            )
                                        }
                                        badgeText?.let {
                                            StatusBadge(it)
                                        }
                                    }
                                },
                                supportingContent = {
                                    supportingLines.forEach { line ->
                                        Text(line, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                leadingContent = entry.icon?.let { drawable ->
                                    {
                                        InstallerIcon(
                                            drawable = drawable,
                                            selected = false,
                                            enabled = entry.availability.available
                                        )
                                    }
                                },
                                trailingContent = {
                                    IconButton(onClick = { handleRemove(component) }) {
                                        Icon(
                                            Icons.Outlined.Delete,
                                            contentDescription = stringResource(R.string.installer_custom_action_remove)
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
            InstallerTab.AutoSaved -> {
                Text(
                    text = stringResource(R.string.installer_custom_tab_auto_saved),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.installer_custom_auto_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (autoSavedEntries.isEmpty()) {
                    Text(
                        text = stringResource(R.string.installer_custom_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        autoSavedEntries.forEach { (component, entry) ->
                            val supportingLines = buildList {
                                entry.description?.takeIf { it.isNotBlank() }?.let { add(it) }
                                entry.availability.reason?.let { add(context.getString(it)) }
                                component?.flattenToString()?.let { add(it) }
                            }
                            ListItem(
                                headlineContent = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            val labelScrollState = rememberScrollState()
                                            Text(
                                                text = entry.label,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier
                                                    .consumeHorizontalScroll(labelScrollState)
                                            )
                                        }
                                        StatusBadge(stringResource(R.string.installer_custom_builtin_indicator))
                                    }
                                },
                                supportingContent = {
                                    supportingLines.forEach { line ->
                                        Text(line, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                leadingContent = entry.icon?.let { drawable ->
                                    {
                                        InstallerIcon(
                                            drawable = drawable,
                                            selected = false,
                                            enabled = entry.availability.available
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
            InstallerTab.Discover -> {
                OutlinedTextField(
                    value = inputValue,
                    onValueChange = { inputValue = it },
                    label = { Text(stringResource(R.string.installer_custom_input_label)) },
                    supportingText = { Text(stringResource(R.string.installer_custom_package_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                FilledTonalButton(
                    onClick = { handleLookup(trimmedInput) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.installer_custom_lookup))
                }

                if (lookupResults.isNotEmpty()) {
                    val headerText = stringResource(
                        R.string.installer_custom_candidates_title,
                        lastQuery ?: ""
                    )
                    Text(
                        text = headerText,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        lookupResults.forEach { entry ->
                            val token = entry.token as? InstallerManager.Token.Component ?: return@forEach
                            val flattened = token.componentName.flattenToString()
                            val isSaved = flattened in savedComponents
                            val isBuiltin = token.componentName in builtinComponents ||
                                token.componentName.packageName == "com.google.android.packageinstaller"
                            val badgeText = when {
                                isSaved -> stringResource(R.string.installer_custom_saved_indicator)
                                isBuiltin -> stringResource(R.string.installer_custom_builtin_indicator)
                                else -> null
                            }
                            val supportingLines = buildList {
                                entry.description?.takeIf { it.isNotBlank() }?.let { add(it) }
                                entry.availability.reason?.let { add(context.getString(it)) }
                                add(token.componentName.flattenToString())
                            }
                            ListItem(
                                headlineContent = {
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        badgeText?.let {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(end = 4.dp),
                                                horizontalArrangement = Arrangement.End
                                            ) {
                                                StatusBadge(it)
                                            }
                                        }
                                        val labelScrollState = rememberScrollState()
                                        Text(
                                            text = entry.label,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier
                                                .consumeHorizontalScroll(labelScrollState)
                                        )
                                    }
                                },
                                supportingContent = {
                                    supportingLines.forEach { line ->
                                        Text(line, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                leadingContent = entry.icon?.let { drawable ->
                                    {
                                        InstallerIcon(
                                            drawable = drawable,
                                            selected = false,
                                            enabled = entry.availability.available
                                        )
                                    }
                                },
                                trailingContent = {
                                    if (!isSaved && !isBuiltin) {
                                        IconButton(
                                            onClick = { handleAdd(token.componentName) },
                                            enabled = entry.availability.available
                                        ) {
                                            Icon(
                                                Icons.Outlined.Add,
                                                contentDescription = stringResource(R.string.installer_custom_action_add)
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }
                } else if (lastQuery != null) {
                    Text(
                        text = stringResource(R.string.installer_custom_lookup_none, lastQuery!!),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        TextButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.End)
        ) {
            Text(stringResource(R.string.installer_custom_close))
        }
    }
}

private enum class InstallerTab(val titleRes: Int) {
    Saved(R.string.installer_custom_tab_saved),
    AutoSaved(R.string.installer_custom_tab_auto_saved),
    Discover(R.string.installer_custom_tab_discover)
}

@Composable
private fun InstallerSelectionDialog(
    title: String,
    options: List<InstallerManager.Entry>,
    selected: InstallerManager.Token,
    blockedToken: InstallerManager.Token?,
    blockedStatusLabel: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (InstallerManager.Token) -> Unit,
    onOpenShizuku: (() -> Boolean)? = null,
    stripRootNote: Boolean = false
) {
    val context = LocalContext.current
    val shizukuPromptReasons = remember {
        setOf(
            R.string.installer_status_shizuku_not_running,
            R.string.installer_status_shizuku_permission
        )
    }
    var currentSelection by remember(selected) { mutableStateOf(selected) }

    LaunchedEffect(options, selected, blockedToken) {
        val tokens = options.map { it.token }
        var selection = currentSelection
        if (selection !in tokens) {
            selection = when {
                selected in tokens -> selected
                else -> options.firstOrNull { it.availability.available }?.token
                    ?: tokens.firstOrNull()
                    ?: selected
            }
        }

        if (blockedToken != null && tokensEqual(selection, blockedToken)) {
            selection = options.firstOrNull {
                !tokensEqual(it.token, blockedToken) && it.availability.available
            }?.token ?: options.firstOrNull { !tokensEqual(it.token, blockedToken) }?.token
            ?: selection
        }
        currentSelection = selection
    }
    val confirmEnabled = options.find { it.token == currentSelection }?.availability?.available != false &&
        !(blockedToken != null && tokensEqual(currentSelection, blockedToken))
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onConfirm(currentSelection) },
                enabled = confirmEnabled
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        title = { CenteredDialogTitle(title) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(0.dp),
                modifier = Modifier.verticalScroll(scrollState)
            ) {
                options.forEach { option ->
                    val blocked = blockedToken != null && tokensEqual(option.token, blockedToken)
                    val enabled = option.availability.available && !blocked
                    val selectedOption = currentSelection == option.token
                    val isShizukuOption = option.token == InstallerManager.Token.Shizuku ||
                        option.token == InstallerManager.Token.ShizukuGooglePlay
                    val showShizukuAction = isShizukuOption &&
                        option.availability.reason in shizukuPromptReasons &&
                        onOpenShizuku != null
                    ListItem(
                        modifier = Modifier.clickable(enabled = enabled) {
                            if (enabled) currentSelection = option.token
                        },
                        colors = transparentListItemColors,
                        leadingContent = {
                            val iconDrawable = option.icon
                            val useInstallerIcon = iconDrawable != null && when (option.token) {
                                InstallerManager.Token.Shizuku -> true
                                InstallerManager.Token.ShizukuGooglePlay -> true
                                is InstallerManager.Token.Component -> true
                                else -> false
                            }
                            if (useInstallerIcon) {
                                InstallerIcon(
                                    drawable = iconDrawable,
                                    selected = selectedOption,
                                    enabled = enabled || selectedOption
                                )
                            } else {
                                RadioButton(
                                    selected = selectedOption,
                                    onClick = null,
                                    enabled = enabled
                                )
                            }
                        },
                        headlineContent = {
                            Text(
                                option.label,
                                color = if (enabled) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        },
                        supportingContent = {
                            val desc = option.description?.let { text ->
                                if (stripRootNote && option.token == InstallerManager.Token.AutoSaved) {
                                    val stripped = text.substringBefore(" (root required", text)
                                    stripped.trimEnd('.', ' ')
                                } else text
                            }?.takeIf { it.isNotBlank() }
                            val statusBadges = buildList {
                                option.availability.reason?.let { add(stringResource(it)) }
                                if (blocked) {
                                    blockedStatusLabel?.takeIf { it.isNotBlank() }?.let { add(it) }
                                }
                            }
                            if (desc != null || statusBadges.isNotEmpty() || showShizukuAction) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    desc?.let {
                                        Text(
                                            it,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    statusBadges.forEach { status ->
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                                            tonalElevation = 0.dp
                                        ) {
                                            Text(
                                                text = status,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                    if (showShizukuAction) {
                                        TextButton(onClick = {
                                            val launched = runCatching { onOpenShizuku?.invoke() ?: false }
                                                .getOrDefault(false)
                                            if (!launched) {
                                                context.toast(context.getString(R.string.installer_shizuku_launch_failed))
                                            }
                                        }) {
                                            Text(stringResource(R.string.installer_action_open_shizuku))
                                        }
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    )
}

@Composable
private fun InstallerIcon(
    drawable: Drawable?,
    selected: Boolean,
    enabled: Boolean
) {
    val colors = MaterialTheme.colorScheme
    val borderColor = if (selected) colors.primary else colors.outlineVariant
    val background = colors.surfaceVariant.copy(alpha = if (enabled) 1f else 0.6f)
    val contentAlpha = if (enabled) 1f else 0.4f

    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (drawable != null) {
            Image(
                painter = rememberDrawablePainter(drawable = drawable),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                alpha = contentAlpha
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.Android,
                contentDescription = null,
                tint = colors.onSurface.copy(alpha = contentAlpha)
            )
        }
    }
}

private fun tokensEqual(a: InstallerManager.Token?, b: InstallerManager.Token?): Boolean = when {
    a === b -> true
    a == null || b == null -> false
    a is InstallerManager.Token.Component && b is InstallerManager.Token.Component ->
        a.componentName == b.componentName
    a.baseInstallerVariant() == b.baseInstallerVariant() -> true
    else -> false
}

// Code adapted from Morphe, see third-party/NOTICE for more information
// https://github.com/MorpheApp/morphe-manager/commit/7e24461c1454b712da4df21440db6f417c94ce58
private fun InstallerManager.Token.baseInstallerVariant(): InstallerManager.Token = when (this) {
    InstallerManager.Token.PlayStore -> InstallerManager.Token.Internal
    InstallerManager.Token.RootPlayStore -> InstallerManager.Token.AutoSaved
    InstallerManager.Token.ShizukuGooglePlay -> InstallerManager.Token.Shizuku
    else -> this
}

@Composable
private fun PatcherLogModeDialog(
    current: PatcherLogMode,
    onDismiss: () -> Unit,
    onSelect: (PatcherLogMode) -> Unit
) {
    var selected by rememberSaveable(current) { mutableStateOf(current) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onSelect(selected) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        title = { CenteredDialogTitle(stringResource(R.string.patcher_log_mode)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.patcher_log_mode_dialog_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                PatcherLogMode.entries.forEachIndexed { index, option ->
                    if (index > 0) {
                        ExpressiveSettingsDivider(modifier = Modifier.padding(horizontal = 0.dp))
                    }
                    SelectionDialogOptionRow(
                        title = stringResource(option.displayName),
                        description = stringResource(option.descriptionRes()),
                        selected = selected == option,
                        onClick = { selected = option }
                    )
                }
            }
        }
    )
}

@Composable
private fun MorpheBytecodeModeDialog(
    current: MorpheBytecodeMode,
    onDismiss: () -> Unit,
    onSelect: (MorpheBytecodeMode) -> Unit
) {
    var selected by rememberSaveable(current) { mutableStateOf(current) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onSelect(selected) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        title = { CenteredDialogTitle(stringResource(R.string.morphe_bytecode_mode)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.morphe_bytecode_mode_dialog_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                MorpheBytecodeMode.entries.forEachIndexed { index, option ->
                    if (index > 0) {
                        ExpressiveSettingsDivider(modifier = Modifier.padding(horizontal = 0.dp))
                    }
                    SelectionDialogOptionRow(
                        title = stringResource(option.titleRes()),
                        description = stringResource(option.descriptionRes()),
                        selected = selected == option,
                        onClick = { selected = option }
                    )
                }
            }
        }
    )
}

@Composable
private fun SelectionDialogOptionRow(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            RadioButton(
                selected = selected,
                onClick = null
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 2.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurface
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant
                )
            }
        }
    }
}

@StringRes
private fun MorpheBytecodeMode.titleRes(): Int = when (this) {
    MorpheBytecodeMode.FAST -> R.string.morphe_bytecode_mode_fast
    MorpheBytecodeMode.FULL -> R.string.morphe_bytecode_mode_full
}

@StringRes
private fun MorpheBytecodeMode.descriptionRes(): Int = when (this) {
    MorpheBytecodeMode.FAST -> R.string.morphe_bytecode_mode_fast_description
    MorpheBytecodeMode.FULL -> R.string.morphe_bytecode_mode_full_description
}

@StringRes
private fun PatcherLogMode.descriptionRes(): Int = when (this) {
    PatcherLogMode.DEFAULT -> R.string.patcher_log_mode_default_description
    PatcherLogMode.VERBOSE -> R.string.patcher_log_mode_verbose_description
}

@Composable
private fun APIUrlDialog(currentUrl: String, defaultUrl: String, onSubmit: (String?) -> Unit) {
    var url by rememberSaveable(currentUrl, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(currentUrl, selection = TextRange(currentUrl.length)))
    }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        withFrameNanos { }
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    AlertDialog(
        modifier = Modifier.imePadding(),
        onDismissRequest = { onSubmit(null) },
        confirmButton = {
            TextButton(
                onClick = {
                    onSubmit(url.text)
                }
            ) {
                Text(stringResource(R.string.api_url_dialog_save))
            }
        },
        dismissButton = {
            TextButton(onClick = { onSubmit(null) }) {
                Text(stringResource(R.string.cancel))
            }
        },
        icon = {
            Icon(Icons.Outlined.Api, null)
        },
        title = {
            Text(
                text = stringResource(R.string.api_url_dialog_title),
                style = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center),
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.api_url_dialog_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.api_url_dialog_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.api_url)) },
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                url = TextFieldValue(defaultUrl, selection = TextRange(defaultUrl.length))
                            }
                        ) {
                            Icon(Icons.Outlined.Restore, stringResource(R.string.api_url_dialog_reset))
                        }
                    }
                )
            }
        }
    )
}

// PR #35: https://github.com/Jman-Github/Universal-Patch-Manager/pull/35
@Composable
private fun GitHubPatDialog(
    currentPat: String,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var pat by rememberSaveable(currentPat, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(currentPat, selection = TextRange(currentPat.length)))
    }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val description = stringResource(R.string.set_github_pat_dialog_description)
    val hereLabel = stringResource(R.string.here)
    val generatePatLink = "https://github.com/settings/tokens/new?scopes=public_repo&description=upm-manager-github-integration"
    val linkHighlightColor = MaterialTheme.colorScheme.primary
    val annotatedDescription = remember(description, hereLabel, linkHighlightColor) {
        val (clickableHereLabel, trailingHereLabel) = splitTrailingPunctuation(hereLabel)
        buildAnnotatedString {
            append(description)
            append(" ")
            pushStringAnnotation(tag = "create_pat_link", annotation = generatePatLink)
            withStyle(
                SpanStyle(
                    color = linkHighlightColor,
                    fontWeight = FontWeight.Bold,
                    textDecoration = TextDecoration.Underline
                )
            ) {
                append(clickableHereLabel)
            }
            pop()
            append(trailingHereLabel)
        }
    }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    AlertDialog(
        modifier = Modifier.imePadding(),
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onSubmit(pat.text) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        icon = { Icon(Icons.Outlined.VpnKey, null) },
        title = {
            Text(
                text = stringResource(R.string.set_github_pat_dialog_title),
                style = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center),
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ClickableText(
                    text = annotatedDescription,
                    style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                    onClick = { offset ->
                        annotatedDescription.getStringAnnotations("create_pat_link", offset, offset)
                            .firstOrNull()
                            ?.let { context.openUrl(it.item) }
                    }
                )

                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    value = pat,
                    onValueChange = { pat = it },
                    label = { Text(stringResource(R.string.github_pat)) },
                    trailingIcon = {
                        IconButton(onClick = { pat = TextFieldValue("") }) {
                            Icon(Icons.Outlined.Delete, null)
                        }
                    },
                    visualTransformation = VisualTransformation { original ->
                        val masked = original.text.let { s ->
                            if (s.length <= 5) s else s.take(5) + "\u2022".repeat(s.length - 5)
                        }
                        TransformedText(AnnotatedString(masked), OffsetMapping.Identity)
                    }
                )
            }
        }
    )
}

@Composable
private fun SearchEngineHostDialog(
    currentHost: String,
    defaultHost: String,
    onSubmit: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    var host by rememberSaveable(currentHost, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(currentHost, selection = TextRange(currentHost.length)))
    }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        withFrameNanos { }
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    AlertDialog(
        modifier = Modifier.imePadding(),
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onSubmit(host.text) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        icon = { Icon(Icons.Outlined.Search, null) },
        title = {
            Text(
                text = stringResource(R.string.search_engine_host_dialog_title),
                style = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center),
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = stringResource(R.string.search_engine_host_dialog_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(stringResource(R.string.search_engine_host_label)) },
                    placeholder = { Text(defaultHost) },
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                host = TextFieldValue(defaultHost, selection = TextRange(defaultHost.length))
                            }
                        ) {
                            Icon(Icons.Outlined.Restore, stringResource(R.string.api_url_dialog_reset))
                        }
                    }
                )
            }
        }
    )
}

private const val AUTO_PATCH_SHIZUKU_PERMISSION_REQUEST_CODE = 795
