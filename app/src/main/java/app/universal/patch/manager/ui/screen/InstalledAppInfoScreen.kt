package app.universal.patch.manager.ui.screen

import app.universal.patch.manager.domain.bundles.toLegacyAwareBundleIndex

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.util.Log
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universal.patch.manager.R
import app.universal.patch.manager.data.platform.Filesystem
import app.universal.patch.manager.data.room.apps.installed.InstallType
import app.universal.patch.manager.domain.installer.InstallerManager
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.domain.batch.batchOriginalPackageName
import app.universal.patch.manager.domain.bundles.PatchBundleSource.Extensions.asRemoteOrNull
import app.universal.patch.manager.domain.repository.PatchBundleRepository
import app.universal.patch.manager.domain.repository.remapLocalBundles
import app.universal.patch.manager.domain.repository.toSignatureMap
import app.universal.patch.manager.data.room.profile.PatchProfilePayload
import app.universal.patch.manager.ui.component.AppInfo
import app.universal.patch.manager.ui.component.AppliedPatchBundleUi
import app.universal.patch.manager.ui.component.AppliedPatchesDialog
import app.universal.patch.manager.ui.component.AppTopBar
import app.universal.patch.manager.ui.component.AppVersion
import app.universal.patch.manager.ui.component.ArrowButton
import app.universal.patch.manager.ui.component.ColumnWithScrollbar
import app.universal.patch.manager.ui.component.InterceptBackHandler
import app.universal.patch.manager.ui.component.SegmentedButton
import app.universal.patch.manager.ui.component.TransparentLoadingDialog
import app.universal.patch.manager.ui.component.ConfirmDialog
import app.universal.patch.manager.ui.component.ExportSavedApkFileNameDialog
import app.universal.patch.manager.ui.component.appVersionLabel
import app.universal.patch.manager.ui.component.patches.PathSelectorDialog
import app.universal.patch.manager.ui.component.RememberedCreateDocument
import app.universal.patch.manager.ui.component.toPickerDirectoryUri
import app.universal.patch.manager.ui.component.patcher.InstallerPickerDialog
import app.universal.patch.manager.ui.component.patcher.SavedAppMountPromptDialog
import app.universal.patch.manager.ui.component.patcher.SavedAppMountPromptMode
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsSwitch
import app.universal.patch.manager.ui.component.settings.SettingsListItem
import app.universal.patch.manager.ui.model.InstalledAppAction
import app.universal.patch.manager.ui.viewmodel.InstalledAppInfoViewModel
import app.universal.patch.manager.ui.viewmodel.InstalledAppInfoViewModel.ReplaceSavedBundleResult
import app.universal.patch.manager.ui.viewmodel.isBundleUpdateAvailable
import app.universal.patch.manager.ui.viewmodel.InstallResult
import app.universal.patch.manager.ui.viewmodel.MountWarningAction
import app.universal.patch.manager.ui.viewmodel.MountWarningReason
import app.universal.patch.manager.util.EventEffect
import app.universal.patch.manager.util.ExportNameFormatter
import app.universal.patch.manager.util.FilenameUtils
import app.universal.patch.manager.util.PatchBundleExportData
import app.universal.patch.manager.util.PatchedAppExportData
import app.universal.patch.manager.util.PatchSelection
import app.universal.patch.manager.util.isAllowedApkFile
import app.universal.patch.manager.util.longPressOnly
import app.universal.patch.manager.util.savedAppBasePackage
import app.universal.patch.manager.util.savedAppLauncherShortcutCapacity
import app.universal.patch.manager.util.tag
import app.universal.patch.manager.util.toast
import app.universal.patch.manager.util.withHapticFeedback
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.util.Locale
import java.io.File
import androidx.compose.runtime.rememberCoroutineScope
import java.nio.file.Files
import java.nio.file.Path
import app.universal.patch.manager.ui.component.CenteredDialogTitle

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class
)
@Composable
fun InstalledAppInfoScreen(
    onPatchClick: (
        packageName: String,
        sourceEntryKey: String?,
        selection: PatchSelection?,
        selectionPayload: PatchProfilePayload?,
        persistConfiguration: Boolean
    ) -> Unit,
    onBackClick: () -> Unit,
    viewModel: InstalledAppInfoViewModel,
    initialAction: InstalledAppAction? = null
) {
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    val copiedToClipboardMessage = stringResource(R.string.toast_copied_to_clipboard)
    val scope = rememberCoroutineScope()
    val patchBundleRepository: PatchBundleRepository = koinInject()
    val prefs: PreferencesManager = koinInject()
    val fs: Filesystem = koinInject()
    val bundleInfo by patchBundleRepository.allBundlesInfoFlow.collectAsStateWithLifecycle(emptyMap())
    val bundleSources by patchBundleRepository.sources.collectAsStateWithLifecycle(emptyList())
    val allowUniversalPatches by prefs.disableUniversalPatchCheck.getAsState()
    val allowBundleOverride by prefs.allowPatchProfileBundleOverride.getAsState()
    val savedAppsEnabled by prefs.enableSavedApps.getAsState()
    val autoPatchEnabled by prefs.autoPatchEnabled.getAsState()
    val autoPatchEnabledPackages by prefs.autoPatchEnabledPackages.getAsState()
    val savedAppLauncherShortcutPackages by
        prefs.savedAppLauncherShortcutPackages.getAsState()
    val exportFormat by prefs.patchedAppExportFormat.getAsState()
    val useCustomFilePicker by prefs.useCustomFilePicker.getAsState()
    val savedAppExportDirectory by prefs.savedAppExportLastDirectory.getAsState()
    val rootMountDiagnosticsExportDirectory by
        prefs.rootMountDiagnosticsExportLastDirectory.getAsState()
    val chooseInstallerPerInstall by prefs.chooseInstallerPerInstall.getAsState()
    val rootMountToolsCollapsed by prefs.rootMountToolsCollapsed.getAsState()
    val installerManager: InstallerManager = koinInject()
    var showAppliedPatchesDialog by rememberSaveable { mutableStateOf(false) }
    var showUniversalBlockedDialog by rememberSaveable { mutableStateOf(false) }
    var showMixedBundleDialog by rememberSaveable { mutableStateOf(false) }
    var showMixedRevancedPatcherDialog by rememberSaveable { mutableStateOf(false) }
    var showLeaveInstallDialog by rememberSaveable { mutableStateOf(false) }
    var showExportPicker by rememberSaveable { mutableStateOf(false) }
    var exportFileDialogState by remember { mutableStateOf<ExportSavedApkDialogState?>(null) }
    var pendingExportConfirmation by remember { mutableStateOf<PendingSavedExportConfirmation?>(null) }
    var exportInProgress by rememberSaveable { mutableStateOf(false) }
    var showRootDiagnosticsActionsDialog by rememberSaveable { mutableStateOf(false) }
    var showRootDiagnosticsExportPicker by rememberSaveable { mutableStateOf(false) }
    var rootDiagnosticsExportFileDialogState by remember {
        mutableStateOf<RootDiagnosticsExportDialogState?>(null)
    }
    var pendingRootDiagnosticsExportConfirmation by remember {
        mutableStateOf<PendingRootDiagnosticsExportConfirmation?>(null)
    }
    var rootDiagnosticsExportInProgress by rememberSaveable { mutableStateOf(false) }
    var pendingRootDiagnosticsFileName by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingAction by rememberSaveable { mutableStateOf(initialAction) }
    var pendingRepatchSourceWarningTarget by remember { mutableStateOf<String?>(null) }
    var showSavedEntryDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var showSavedAppDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var showSavedUninstallDialog by rememberSaveable { mutableStateOf(false) }
    var showSavedAppInstallerPicker by rememberSaveable { mutableStateOf(false) }
    var savedAppMountPromptMode by rememberSaveable { mutableStateOf<SavedAppMountPromptMode?>(null) }
    val appliedSelection = viewModel.appliedPatches
    val isInstalledOnDevice = viewModel.isInstalledOnDevice
    val installedAppState = viewModel.installedApp
    val hasRoot = viewModel.rootInstaller.hasRootAccess()
    val storageRoots = remember { fs.storageRoots() }
    val (permissionContract, permissionName) = remember { fs.permissionContract() }
    val permissionLauncher =
        rememberLauncherForActivityResult(permissionContract) { granted ->
            if (granted) {
                showExportPicker = true
            }
        }
    val rootDiagnosticsPermissionLauncher =
        rememberLauncherForActivityResult(permissionContract) { granted ->
            if (granted) {
                showRootDiagnosticsExportPicker = true
            }
        }
    val exportDocumentLauncher = rememberLauncherForActivityResult(
        contract = RememberedCreateDocument(if (viewModel.savedFileExtension == "apks") "application/zip"
            else "application/vnd.android.package-archive") {
            savedAppExportDirectory.takeIf(String::isNotBlank)?.let(Uri::parse)
        }
    ) { uri ->
        uri?.let {
            scope.launch {
                prefs.savedAppExportLastDirectory.update(it.toPickerDirectoryUri().toString())
            }
        }
        viewModel.exportSavedApp(uri)
        showExportPicker = false
    }
    val rootDiagnosticsDocumentLauncher = rememberLauncherForActivityResult(
        contract = RememberedCreateDocument("text/plain") {
            rootMountDiagnosticsExportDirectory.takeIf(String::isNotBlank)?.let(Uri::parse)
        }
    ) { uri ->
        if (uri != null) {
            scope.launch {
                prefs.rootMountDiagnosticsExportLastDirectory.update(
                    uri.toPickerDirectoryUri().toString()
                )
            }
            rootDiagnosticsExportInProgress = true
            viewModel.exportRootMountDiagnosticsToUri(uri) { success ->
                rootDiagnosticsExportInProgress = false
                if (success) showRootDiagnosticsExportPicker = false
            }
        }
        pendingRootDiagnosticsFileName = null
    }
    fun openExportPicker() {
        if (useCustomFilePicker) {
            if (fs.hasStoragePermission()) {
                showExportPicker = true
            } else {
                permissionLauncher.launch(permissionName)
            }
        } else {
            showExportPicker = true
        }
    }
    fun openRootDiagnosticsExportPicker() {
        val packageName = installedAppState?.currentPackageName ?: "diagnostics"
        val fileName = FilenameUtils.timestampedLogFileName("root-mount-$packageName")
        pendingRootDiagnosticsFileName = fileName
        if (useCustomFilePicker) {
            if (fs.hasStoragePermission()) {
                showRootDiagnosticsExportPicker = true
            } else {
                rootDiagnosticsPermissionLauncher.launch(permissionName)
            }
        } else {
            rootDiagnosticsDocumentLauncher.launch(fileName)
        }
    }
    val selectionPayload = installedAppState?.selectionPayload
    val remapSignatures = remember(bundleSources, bundleInfo) {
        bundleInfo.toSignatureMap().toMutableMap().apply {
            bundleSources
                .filter { it.asRemoteOrNull == null }
                .forEach { source -> putIfAbsent(source.uid, emptySet()) }
        }
    }
    val remappedSelectionPayload = remember(selectionPayload, bundleSources, bundleInfo) {
        selectionPayload?.remapLocalBundles(
            sources = bundleSources,
            signatures = remapSignatures
        )
    }
    val savedBundlesByUid = remember(remappedSelectionPayload, bundleSources) {
        val sourcesByUid = bundleSources.associateBy { it.uid }
        val sourcesByEndpoint = bundleSources.mapNotNull { source ->
            source.asRemoteOrNull?.endpoint
                ?.normalizedBundleEndpoint()
                ?.let { it to source }
        }.toLegacyAwareBundleIndex()
        val savedBundles = remappedSelectionPayload?.bundles.orEmpty()
        buildMap {
            savedBundles.forEach { bundle ->
                if (sourcesByUid.containsKey(bundle.bundleUid)) {
                    put(bundle.bundleUid, bundle)
                }
            }
            savedBundles.forEach { bundle ->
                if (sourcesByUid.containsKey(bundle.bundleUid)) return@forEach
                val resolvedUid = bundle.sourceEndpoint
                    ?.normalizedBundleEndpoint()
                    ?.let(sourcesByEndpoint::get)
                    ?.uid
                    ?: bundle.bundleUid
                putIfAbsent(resolvedUid, bundle)
            }
        }
    }
    data class SavedBundleTarget(
        val bundleUid: Int,
        val bundleName: String,
        val requiredPatchesLowercase: Set<String>
    )
    data class SavedBundleOption(
        val uid: Int,
        val displayName: String,
        val version: String?,
        val patchCount: Int,
        val patchNamesLowercase: Set<String>
    )
    data class SavedBundleOverrideTarget(
        val bundleUid: Int,
        val targetUid: Int,
        val displayName: String,
        val requiredPatchesLowercase: Set<String>
    )
    var missingBundleTarget by remember { mutableStateOf<SavedBundleTarget?>(null) }
    var missingBundleSelectionUid by rememberSaveable { mutableStateOf<Int?>(null) }
    var missingBundleSaving by remember { mutableStateOf(false) }
    var missingBundleIncompatibleTarget by remember { mutableStateOf<SavedBundleOverrideTarget?>(null) }

    val appliedBundles = remember(appliedSelection, bundleInfo, bundleSources, context, savedBundlesByUid) {
        if (appliedSelection.isNullOrEmpty()) return@remember emptyList<AppliedPatchBundleUi>()

        runCatching {
            val sourceByEndpoint = bundleSources.mapNotNull { source ->
                source.asRemoteOrNull?.endpoint
                    ?.normalizedBundleEndpoint()
                    ?.let { it to source }
            }.toLegacyAwareBundleIndex()
            appliedSelection.entries.mapNotNull { (bundleUid, patches) ->
                if (patches.isEmpty()) return@mapNotNull null
                val patchNames = patches.toList().sorted()
                val savedBundle = savedBundlesByUid[bundleUid]
                val savedEndpoint = savedBundle?.sourceEndpoint?.normalizedBundleEndpoint()
                val source = bundleSources.firstOrNull { it.uid == bundleUid }
                    ?: savedEndpoint?.let(sourceByEndpoint::get)
                val info = bundleInfo[source?.uid ?: bundleUid] ?: bundleInfo[bundleUid]
                val fallbackName = if (bundleUid == 0)
                    context.getString(R.string.patches_name_default)
                else
                    context.getString(R.string.patches_name_fallback)

                val title = source?.displayTitle
                    ?: savedBundle?.displayName?.takeIf { it.isNotBlank() }
                    ?: savedBundle?.sourceName?.takeIf { it.isNotBlank() }
                    ?: info?.name
                    ?: "$fallbackName (#$bundleUid)"

                val patchInfos = info?.patches
                    ?.filter { it.name in patches }
                    ?.distinctBy { it.name }
                    ?.sortedBy { it.name }
                    ?: emptyList()

                val missingNames = patchNames.filterNot { patchName ->
                    patchInfos.any { it.name == patchName }
                }.distinct()
                val savedVersion = savedBundle?.version?.takeUnless { it.isBlank() }
                val currentVersion = info?.version?.takeUnless { it.isBlank() }
                    ?: source?.version?.takeUnless { it.isBlank() }

                AppliedPatchBundleUi(
                    uid = bundleUid,
                    title = title,
                    version = savedVersion ?: currentVersion,
                    patchInfos = patchInfos,
                    fallbackNames = missingNames,
                    bundleAvailable = info != null,
                    hasUpdate = savedVersion != null &&
                        currentVersion != null &&
                        isBundleUpdateAvailable(currentVersion, savedVersion)
                )
            }.sortedBy { it.title }
        }.getOrElse { error ->
            Log.e(tag, "Failed to build applied bundle summary", error)
            emptyList()
        }
    }
    val bundleOptions = remember(bundleInfo, bundleSources) {
        bundleInfo.mapNotNull { (uid, info) ->
            val source = bundleSources.firstOrNull { it.uid == uid }
            val displayName = source?.displayTitle ?: info.name
            val patchNamesLowercase = info.patches
                .mapTo(mutableSetOf()) { it.name.trim().lowercase(Locale.ROOT) }
            SavedBundleOption(
                uid = uid,
                displayName = displayName,
                version = info.version,
                patchCount = info.patches.size,
                patchNamesLowercase = patchNamesLowercase
            )
        }.sortedBy { it.displayName.lowercase(Locale.ROOT) }
    }

    val universalPatchNamesByUid = remember(bundleInfo) {
        bundleInfo.mapValues { (_, info) ->
            info.patches
                .asSequence()
                .filter { it.compatiblePackages == null }
                .mapTo(mutableSetOf()) { it.name.trim().lowercase(Locale.ROOT) }
        }
    }

    val appliedBundlesContainUniversal = remember(appliedBundles, universalPatchNamesByUid) {
        appliedBundles.any { bundle ->
            val universalNames = universalPatchNamesByUid[bundle.uid].orEmpty()
            val hasByMetadata = bundle.patchInfos.any { it.compatiblePackages == null }
            val fallbackMatch = bundle.fallbackNames.any { name ->
                universalNames.contains(name.trim().lowercase(Locale.ROOT))
            }
            hasByMetadata || fallbackMatch
        }
    }

    val appliedSelectionContainsUniversal = remember(appliedSelection, universalPatchNamesByUid) {
        appliedSelection?.any { (bundleUid, patches) ->
            val universalNames = universalPatchNamesByUid[bundleUid].orEmpty()
            patches.any { universalNames.contains(it.trim().lowercase(Locale.ROOT)) }
        } ?: false
    }

    fun continueRepatch(targetPackageName: String) {
        val selection = appliedSelection ?: return
        val persistConfiguration = viewModel.installedApp?.installType != InstallType.SAVED
        onPatchClick(
            targetPackageName,
            installedAppState?.currentPackageName,
            selection,
            selectionPayload,
            persistConfiguration
        )
    }

    fun handleRepatchClick(targetPackageName: String) {
        if (!allowUniversalPatches && (appliedBundlesContainUniversal || appliedSelectionContainsUniversal)) {
            showUniversalBlockedDialog = true
            return
        }

        val selection = appliedSelection ?: return
        scope.launch {
            if (patchBundleRepository.selectionHasMixedBundleTypes(selection)) {
                showMixedBundleDialog = true
                return@launch
            }
            if (patchBundleRepository.selectionHasMixedRevancedPatcherVersions(selection)) {
                showMixedRevancedPatcherDialog = true
                return@launch
            }
            val sourcePath = installedAppState?.repatchSourcePath
            val sourceAvailable = sourcePath
                ?.takeIf(String::isNotBlank)
                ?.let(::File)
                ?.isFile == true
            if (!sourceAvailable) {
                pendingRepatchSourceWarningTarget = targetPackageName
                return@launch
            }
            continueRepatch(targetPackageName)
        }
    }

    val activityLauncher = rememberLauncherForActivityResult(
        contract = StartActivityForResult(),
        onResult = viewModel::handleActivityResult
    )
    EventEffect(flow = viewModel.launchActivityFlow) { intent ->
        activityLauncher.launch(intent)
    }

    if (showSavedAppInstallerPicker) {
        InstallerPickerDialog(
            title = stringResource(R.string.installer_choose_for_this_install_title),
            options = installerManager.listEntries(
                target = InstallerManager.InstallTarget.SAVED_APP,
                includeNone = false
            ).filterNot { entry ->
                entry.token == InstallerManager.Token.AutoSaved && !viewModel.supportsRootMount
            },
            onDismiss = { showSavedAppInstallerPicker = false },
            onConfirm = viewModel::installSavedApp,
            onOpenShizuku = installerManager::openShizukuApp
        )
    }

    savedAppMountPromptMode?.let { mode ->
        SavedAppMountPromptDialog(
            mode = mode,
            canMount = hasRoot,
            onDismiss = { savedAppMountPromptMode = null },
            onMount = {
                savedAppMountPromptMode = null
                viewModel.mountOrUnmount()
            },
            onChooseDifferentInstaller = {
                savedAppMountPromptMode = null
                showSavedAppInstallerPicker = true
            },
            onRemount = {
                savedAppMountPromptMode = null
                viewModel.remountSavedInstallation()
            }
        )
    }

    LaunchedEffect(initialAction) {
        if (initialAction != null) {
            pendingAction = initialAction
        }
    }

    SideEffect {
        viewModel.onBackClick = onBackClick
    }

    var showUninstallDialog by rememberSaveable { mutableStateOf(false) }

    if (showUninstallDialog)
        UninstallDialog(
            onDismiss = { showUninstallDialog = false },
            onConfirm = { viewModel.uninstall() }
        )

    if (showAppliedPatchesDialog && appliedSelection != null) {
        AppliedPatchesDialog(
            bundles = appliedBundles,
            onDismissRequest = { showAppliedPatchesDialog = false }
        )
    }

    if (showUniversalBlockedDialog) {
        AlertDialog(
            onDismissRequest = { showUniversalBlockedDialog = false },
            confirmButton = {
                TextButton(onClick = { showUniversalBlockedDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            },
            title = { CenteredDialogTitle(stringResource(R.string.universal_patches_profile_blocked_title)) },
            text = {
                Text(
                    text = stringResource(
                        R.string.universal_patches_app_blocked_description,
                        stringResource(R.string.universal_patches_safeguard)
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        )
    }

    pendingRepatchSourceWarningTarget?.let { targetPackageName ->
        val sourcePath = installedAppState?.repatchSourcePath
        ConfirmDialog(
            onDismiss = { pendingRepatchSourceWarningTarget = null },
            onConfirm = {
                pendingRepatchSourceWarningTarget = null
                continueRepatch(targetPackageName)
            },
            title = stringResource(R.string.repatch_source_unavailable_title),
            description = if (sourcePath.isNullOrBlank()) {
                stringResource(R.string.repatch_source_not_remembered_description)
            } else {
                stringResource(R.string.repatch_source_missing_description)
            },
            icon = Icons.Outlined.WarningAmber,
            confirmLabelRes = R.string.continue_
        )
    }

    if (showMixedBundleDialog) {
        AlertDialog(
            onDismissRequest = { showMixedBundleDialog = false },
            confirmButton = {
                TextButton(onClick = { showMixedBundleDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            },
            title = { CenteredDialogTitle(stringResource(R.string.mixed_patch_bundles_title)) },
            text = { Text(stringResource(R.string.mixed_patch_bundles_description)) }
        )
    }

    if (showMixedRevancedPatcherDialog) {
        AlertDialog(
            onDismissRequest = { showMixedRevancedPatcherDialog = false },
            confirmButton = {
                TextButton(onClick = { showMixedRevancedPatcherDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            },
            title = { CenteredDialogTitle(stringResource(R.string.mixed_revanced_patcher_versions_title)) },
            text = { Text(stringResource(R.string.mixed_revanced_patcher_versions_description)) }
        )
    }

    missingBundleTarget?.let { target ->
        val options = bundleOptions
        AlertDialog(
            onDismissRequest = {
                if (missingBundleSaving) return@AlertDialog
                missingBundleTarget = null
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val selectedId = missingBundleSelectionUid ?: return@TextButton
                        val selected = options.firstOrNull { it.uid == selectedId } ?: return@TextButton
                        if (missingBundleSaving) return@TextButton
                        val isCompatible = target.requiredPatchesLowercase.all {
                            it in selected.patchNamesLowercase
                        }
                        if (!isCompatible) {
                            missingBundleIncompatibleTarget = SavedBundleOverrideTarget(
                                bundleUid = target.bundleUid,
                                targetUid = selected.uid,
                                displayName = selected.displayName,
                                requiredPatchesLowercase = target.requiredPatchesLowercase
                            )
                            return@TextButton
                        }
                        missingBundleSaving = true
                        scope.launch {
                            try {
                                when (
                                    viewModel.replaceSavedBundle(
                                        target.bundleUid,
                                        selected.uid,
                                        target.requiredPatchesLowercase,
                                        false
                                    )
                                ) {
                                    ReplaceSavedBundleResult.SUCCESS -> context.toast(
                                        context.getString(
                                            R.string.saved_app_bundle_select_success,
                                            selected.displayName
                                        )
                                    )
                                    ReplaceSavedBundleResult.INCOMPATIBLE -> {
                                        missingBundleIncompatibleTarget = SavedBundleOverrideTarget(
                                            bundleUid = target.bundleUid,
                                            targetUid = selected.uid,
                                            displayName = selected.displayName,
                                            requiredPatchesLowercase = target.requiredPatchesLowercase
                                        )
                                    }
                                    ReplaceSavedBundleResult.APP_NOT_FOUND,
                                    ReplaceSavedBundleResult.TARGET_NOT_FOUND,
                                    ReplaceSavedBundleResult.FAILED -> context.toast(
                                        context.getString(R.string.saved_app_bundle_select_error)
                                    )
                                }
                            } finally {
                                missingBundleSaving = false
                                missingBundleTarget = null
                            }
                        }
                    },
                    enabled = !missingBundleSaving && missingBundleSelectionUid != null && options.isNotEmpty()
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (missingBundleSaving) return@TextButton
                        missingBundleTarget = null
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
            title = {
                Text(
                    stringResource(
                        R.string.saved_app_bundle_select_title,
                        target.bundleName
                    )
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = stringResource(R.string.saved_app_bundle_select_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (options.isEmpty()) {
                        Text(
                            text = stringResource(R.string.saved_app_bundle_select_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        options.forEach { option ->
                            val patchCountText = pluralStringResource(
                                R.plurals.patch_profile_bundle_patch_count,
                                option.patchCount,
                                option.patchCount
                            )
                            val versionLabel = option.version?.let { version ->
                                if (version.startsWith("v", ignoreCase = true)) version else "v$version"
                            }
                            val subtitle = if (versionLabel != null) {
                                "$versionLabel - $patchCountText"
                            } else {
                                patchCountText
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { missingBundleSelectionUid = option.uid }
                                    .padding(vertical = 6.dp)
                            ) {
                                RadioButton(
                                    selected = missingBundleSelectionUid == option.uid,
                                    onClick = { missingBundleSelectionUid = option.uid }
                                )
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = option.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        )
    }

    missingBundleIncompatibleTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { missingBundleIncompatibleTarget = null },
            confirmButton = {
                if (allowBundleOverride) {
                    TextButton(
                        onClick = {
                            if (missingBundleSaving) return@TextButton
                            missingBundleSaving = true
                            scope.launch {
                                try {
                                    when (
                                        viewModel.replaceSavedBundle(
                                            target.bundleUid,
                                            target.targetUid,
                                            target.requiredPatchesLowercase,
                                            true
                                        )
                                    ) {
                                        ReplaceSavedBundleResult.SUCCESS -> context.toast(
                                            context.getString(
                                                R.string.saved_app_bundle_select_success,
                                                target.displayName
                                            )
                                        )
                                        ReplaceSavedBundleResult.INCOMPATIBLE,
                                        ReplaceSavedBundleResult.TARGET_NOT_FOUND,
                                        ReplaceSavedBundleResult.APP_NOT_FOUND,
                                        ReplaceSavedBundleResult.FAILED -> context.toast(
                                            context.getString(R.string.saved_app_bundle_select_error)
                                        )
                                    }
                                } finally {
                                    missingBundleSaving = false
                                    missingBundleIncompatibleTarget = null
                                    missingBundleTarget = null
                                }
                            }
                        }
                    ) {
                        Text(stringResource(R.string.saved_app_bundle_select_override))
                    }
                } else {
                    TextButton(onClick = { missingBundleIncompatibleTarget = null }) {
                        Text(stringResource(R.string.ok))
                    }
                }
            },
            dismissButton = {
                if (allowBundleOverride) {
                    TextButton(onClick = { missingBundleIncompatibleTarget = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            },
            title = { CenteredDialogTitle(stringResource(R.string.saved_app_bundle_select_incompatible_title)) },
            text = {
                Text(
                    text = stringResource(
                        R.string.saved_app_bundle_select_incompatible_message,
                        target.displayName
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        )
    }

    val installResult = viewModel.installResult
    if (installResult != null) {
        val (titleRes, message) = when (installResult) {
            is InstallResult.Success -> R.string.install_app_success to installResult.message
            is InstallResult.Failure -> R.string.install_app_fail_title to installResult.message
            is InstallResult.UninstallError -> R.string.uninstall_app_fail_title to installResult.message
        }
        AlertDialog(
            onDismissRequest = viewModel::clearInstallResult,
            confirmButton = {
                TextButton(onClick = viewModel::clearInstallResult) {
                    Text(stringResource(R.string.ok))
                }
            },
            title = { CenteredDialogTitle(stringResource(titleRes)) },
            text = { Text(message) }
        )
    }

    viewModel.signatureMismatchPackage?.let {
        AlertDialog(
            onDismissRequest = viewModel::dismissSignatureMismatchPrompt,
            confirmButton = {
                TextButton(onClick = viewModel::confirmSignatureMismatchInstall) {
                    Text(stringResource(R.string.installation_signature_mismatch_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissSignatureMismatchPrompt) {
                    Text(stringResource(R.string.cancel))
                }
            },
            title = { CenteredDialogTitle(stringResource(R.string.installation_signature_mismatch_dialog_title)) },
            text = {
                Text(
                    text = stringResource(R.string.installation_signature_mismatch_description),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        )
    }

    viewModel.mountVersionMismatchMessage?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissMountVersionMismatch,
            confirmButton = {
                TextButton(onClick = viewModel::dismissMountVersionMismatch) {
                    Text(stringResource(R.string.ok))
                }
            },
            title = { CenteredDialogTitle(stringResource(R.string.mount_version_mismatch_title)) },
            text = { Text(message) }
        )
    }

    val mountWarning = viewModel.mountWarning
    if (mountWarning != null) {
        val (descriptionRes, titleRes) = when (mountWarning.reason) {
            MountWarningReason.PRIMARY_IS_MOUNT_FOR_NON_MOUNT_APP ->
                when (mountWarning.action) {
                    MountWarningAction.INSTALL -> R.string.installer_mount_warning_install
                    MountWarningAction.UPDATE -> R.string.installer_mount_warning_update
                    MountWarningAction.UNINSTALL -> R.string.installer_mount_warning_uninstall
                } to R.string.installer_mount_warning_title

            MountWarningReason.PRIMARY_NOT_MOUNT_FOR_MOUNT_APP ->
                when (mountWarning.action) {
                    MountWarningAction.INSTALL -> R.string.installer_mount_mismatch_install
                    MountWarningAction.UPDATE -> R.string.installer_mount_mismatch_update
                    MountWarningAction.UNINSTALL -> R.string.installer_mount_mismatch_uninstall
                } to R.string.installer_mount_mismatch_title
        }

        AlertDialog(
            onDismissRequest = viewModel::clearMountWarning,
            confirmButton = {
                TextButton(onClick = viewModel::clearMountWarning) {
                    Text(stringResource(R.string.ok))
                }
            },
            title = { CenteredDialogTitle(stringResource(titleRes)) },
            text = {
                Text(
                    text = stringResource(descriptionRes),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        )
    }

    if (showRootDiagnosticsActionsDialog) {
        RootMountDiagnosticsActionsDialog(
            onDismiss = { showRootDiagnosticsActionsDialog = false },
            onCopy = {
                showRootDiagnosticsActionsDialog = false
                scope.launch {
                    val content = viewModel.readRootMountDiagnostics() ?: return@launch
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    if (clipboard == null) {
                        context.toast(context.getString(R.string.root_mount_diagnostics_copy_failed))
                        return@launch
                    }
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText("Root mount diagnostics", content)
                    )
                    context.toast(context.getString(R.string.root_mount_diagnostics_copy_success))
                }
            },
            onExport = {
                showRootDiagnosticsActionsDialog = false
                openRootDiagnosticsExportPicker()
            }
        )
    }

    if (showRootDiagnosticsExportPicker && useCustomFilePicker) {
        PathSelectorDialog(
            roots = storageRoots,
            onSelect = { path ->
                if (path == null) {
                    showRootDiagnosticsExportPicker = false
                    pendingRootDiagnosticsFileName = null
                }
            },
            fileFilter = { false },
            allowDirectorySelection = true,
            fileTypeLabel = ".txt",
            confirmButtonText = stringResource(R.string.save),
            onConfirm = { directory ->
                rootDiagnosticsExportFileDialogState = RootDiagnosticsExportDialogState(
                    directory = directory,
                    fileName = pendingRootDiagnosticsFileName
                        ?: FilenameUtils.timestampedLogFileName("root-mount")
                )
            },
            lastDirectoryPreference = prefs.rootMountDiagnosticsExportLastDirectory
        )
    }

    rootDiagnosticsExportFileDialogState?.let { state ->
        ExportRootDiagnosticsFileNameDialog(
            initialName = state.fileName,
            onDismiss = {
                rootDiagnosticsExportFileDialogState = null
                pendingRootDiagnosticsFileName = null
            },
            onConfirm = { fileName ->
                val trimmedName = fileName.trim()
                if (trimmedName.isBlank()) return@ExportRootDiagnosticsFileNameDialog
                val finalName = if (trimmedName.endsWith(".txt", ignoreCase = true)) {
                    trimmedName
                } else {
                    "$trimmedName.txt"
                }
                rootDiagnosticsExportFileDialogState = null
                pendingRootDiagnosticsFileName = null
                val target = state.directory.resolve(finalName)
                if (Files.exists(target)) {
                    pendingRootDiagnosticsExportConfirmation =
                        PendingRootDiagnosticsExportConfirmation(state.directory, finalName)
                } else {
                    rootDiagnosticsExportInProgress = true
                    viewModel.exportRootMountDiagnosticsToPath(target) { success ->
                        rootDiagnosticsExportInProgress = false
                        if (success) showRootDiagnosticsExportPicker = false
                    }
                }
            }
        )
    }

    pendingRootDiagnosticsExportConfirmation?.let { state ->
        ConfirmDialog(
            onDismiss = {
                pendingRootDiagnosticsExportConfirmation = null
                rootDiagnosticsExportFileDialogState =
                    RootDiagnosticsExportDialogState(state.directory, state.fileName)
            },
            onConfirm = {
                pendingRootDiagnosticsExportConfirmation = null
                rootDiagnosticsExportInProgress = true
                viewModel.exportRootMountDiagnosticsToPath(
                    state.directory.resolve(state.fileName)
                ) { success ->
                    rootDiagnosticsExportInProgress = false
                    if (success) showRootDiagnosticsExportPicker = false
                }
            },
            title = stringResource(R.string.export_overwrite_title),
            description = stringResource(R.string.export_overwrite_description, state.fileName),
            icon = Icons.Outlined.WarningAmber
        )
    }

    if (rootDiagnosticsExportInProgress) {
        AlertDialog(
            onDismissRequest = {},
            icon = {
                Icon(
                    Icons.Outlined.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(
                    stringResource(R.string.root_mount_diagnostics_save_title),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        stringResource(R.string.root_mount_diagnostics_exporting),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(999.dp))
                    )
                }
            },
            confirmButton = {},
            dismissButton = {},
            shape = RoundedCornerShape(28.dp)
        )
    }

    if (viewModel.isDeletingSavedRootApp) {
        TransparentLoadingDialog(
            message = stringResource(R.string.delete_root_mount_saved_app_progress)
        )
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.app_info),
                scrollBehavior = scrollBehavior,
                onBackClick = {
                    if (viewModel.isInstalling) showLeaveInstallDialog = true else onBackClick()
                }
            )
        },
        modifier = Modifier
            .blur(if (viewModel.isDeletingSavedRootApp) 16.dp else 0.dp)
            .nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { paddingValues ->
        InterceptBackHandler(enabled = viewModel.isInstalling) {
            showLeaveInstallDialog = true
        }
        ColumnWithScrollbar(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            val installedApp = installedAppState ?: return@ColumnWithScrollbar
            val supportsRootMount = viewModel.supportsRootMount
            val displayInstallType = if (
                installedApp.installType == InstallType.MOUNT && !supportsRootMount
            ) {
                InstallType.SAVED
            } else {
                installedApp.installType
            }
            val installerLabelType = if (
                displayInstallType == InstallType.MOUNT &&
                viewModel.isPlayStoreInstallerSource
            ) {
                InstallType.ROOT_PLAY_STORE
            } else {
                displayInstallType
            }
            val displayPackageName = if (displayInstallType == InstallType.SAVED) {
                installedApp.originalPackageName.takeIf { it.isNotBlank() }
                    ?: savedAppBasePackage(installedApp.currentPackageName)
            } else {
                installedApp.currentPackageName
            }

            if (displayInstallType == InstallType.MOUNT) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                            modifier = Modifier.size(28.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.WarningAmber,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.root_mount_update_guidance_title),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = stringResource(R.string.root_mount_safe_update_notice),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            AppInfo(
                appInfo = viewModel.appInfo,
                labelOverride = viewModel.appLabel,
                placeholderLabel = displayPackageName,
                isLoading = !viewModel.hasLoadedAppInfo
            ) {
                val versionLabel = appVersionLabel(
                    versionName = installedApp.version,
                    appInfo = viewModel.appInfo,
                    versionCodeOverride = viewModel.displayedVersionCode
                ).toPrefixedVersionLabel()
                val copyAppVersion = {
                    clipboard?.setPrimaryClip(
                        ClipData.newPlainText(
                            "App version",
                            versionLabel
                        )
                    )
                    if (clipboard != null) {
                        context.toast(copiedToClipboardMessage)
                    }
                }.withHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                AppVersion(
                    appInfo = viewModel.appInfo,
                    versionName = installedApp.version,
                    versionCodeOverride = viewModel.displayedVersionCode,
                    prefixVersion = true,
                    modifier = Modifier.longPressOnly(
                        label = stringResource(R.string.copy_to_clipboard),
                        onLongPress = copyAppVersion
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                viewModel.savedApkAbiLabel?.let { abiLabel ->
                    Text(
                        abiLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (displayInstallType == InstallType.MOUNT) {
                    val mountStatusText = when (viewModel.mountOperation) {
                        InstalledAppInfoViewModel.MountOperation.UNMOUNTING -> stringResource(R.string.unmounting)
                        InstalledAppInfoViewModel.MountOperation.MOUNTING -> stringResource(R.string.mounting_ellipsis)
                        InstalledAppInfoViewModel.MountOperation.REPAIRING -> stringResource(R.string.root_mount_repair_progress)
                        null -> if (viewModel.isMounted) {
                            stringResource(R.string.mounted)
                        } else {
                            stringResource(R.string.not_mounted)
                        }
                    }
                    Text(
                        text = mountStatusText,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

    val exportMetadata = remember(
        installedApp.currentPackageName,
        installedApp.version,
        appliedBundles,
        viewModel.appInfo,
        viewModel.appLabel,
        viewModel.savedFileExtension
    ) {
        val label = viewModel.appLabel ?: displayPackageName
        val patchBundles = appliedBundles.mapNotNull { bundle ->
            val name = bundle.title.takeIf(String::isNotBlank)
            val version = bundle.version?.takeIf(String::isNotBlank)
            if (name == null && version == null) {
                null
            } else {
                PatchBundleExportData(name = name, version = version)
            }
        }
        PatchedAppExportData(
            appName = label,
            packageName = viewModel.appInfo?.packageName ?: displayPackageName,
            appVersion = installedApp.version,
            patchBundles = patchBundles,
            fileExtension = viewModel.savedFileExtension
        )
    }
    val exportFileName = remember(exportMetadata, exportFormat) {
        ExportNameFormatter.format(exportFormat, exportMetadata)
    }
    if (showExportPicker && useCustomFilePicker) {
        PathSelectorDialog(
            roots = storageRoots,
            onSelect = { path ->
                if (path == null) {
                    showExportPicker = false
                }
            },
            fileFilter = ::isAllowedApkFile,
            allowDirectorySelection = false,
            fileTypeLabel = ".apk",
            confirmButtonText = stringResource(R.string.save),
            onConfirm = { directory ->
                exportFileDialogState = ExportSavedApkDialogState(directory, exportFileName)
            },
            lastDirectoryPreference = prefs.savedAppExportLastDirectory
        )
    }
    LaunchedEffect(showExportPicker, useCustomFilePicker, exportFileName) {
        if (showExportPicker && !useCustomFilePicker) {
            exportDocumentLauncher.launch(exportFileName)
        }
    }
    LaunchedEffect(useCustomFilePicker) {
        if (!useCustomFilePicker) {
            exportFileDialogState = null
            pendingExportConfirmation = null
            rootDiagnosticsExportFileDialogState = null
            pendingRootDiagnosticsExportConfirmation = null
            showRootDiagnosticsExportPicker = false
        }
    }
    exportFileDialogState?.let { state ->
        ExportSavedApkFileNameDialog(
            initialName = state.fileName,
            onDismiss = { exportFileDialogState = null },
            onConfirm = { fileName ->
                val trimmedName = fileName.trim()
                if (trimmedName.isBlank()) return@ExportSavedApkFileNameDialog
                exportFileDialogState = null
                val target = state.directory.resolve(trimmedName)
                if (Files.exists(target)) {
                    pendingExportConfirmation = PendingSavedExportConfirmation(
                        directory = state.directory,
                        fileName = trimmedName
                    )
                } else {
                    exportInProgress = true
                    viewModel.exportSavedAppToPath(target) { success ->
                        exportInProgress = false
                        if (success) {
                            showExportPicker = false
                        }
                    }
                }
            }
        )
    }
    pendingExportConfirmation?.let { state ->
        ConfirmDialog(
            onDismiss = {
                pendingExportConfirmation = null
                exportFileDialogState = ExportSavedApkDialogState(state.directory, state.fileName)
            },
            onConfirm = {
                pendingExportConfirmation = null
                exportInProgress = true
                viewModel.exportSavedAppToPath(state.directory.resolve(state.fileName)) { success ->
                    exportInProgress = false
                    if (success) {
                        showExportPicker = false
                    }
                }
            },
            title = stringResource(R.string.export_overwrite_title),
            description = stringResource(R.string.export_overwrite_description, state.fileName),
            icon = Icons.Outlined.WarningAmber
        )
    }
    if (exportInProgress) {
        AlertDialog(
            onDismissRequest = {},
            icon = {
                Icon(
                    Icons.Outlined.Save,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(
                    stringResource(R.string.export),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        stringResource(R.string.patcher_step_group_saving),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(999.dp))
                    )
                }
            },
            confirmButton = {},
            dismissButton = {},
            shape = RoundedCornerShape(28.dp)
        )
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(24.dp))
    ) {
        val installType = remember(
            installedApp.installType,
            viewModel.primaryInstallerIsMount,
            chooseInstallerPerInstall,
            hasRoot,
            supportsRootMount
        ) {
            when {
                installedApp.installType == InstallType.MOUNT && !supportsRootMount ->
                    InstallType.SAVED
                !chooseInstallerPerInstall &&
                    installedApp.installType == InstallType.SAVED &&
                    viewModel.primaryInstallerIsMount &&
                    hasRoot &&
                    supportsRootMount -> InstallType.MOUNT
                else -> installedApp.installType
            }
        }
        val rootRequiredText = stringResource(R.string.installer_status_requires_root)
        val primaryInstallerIsMount = viewModel.primaryInstallerIsMount
        val isMounted = viewModel.isMounted

        fun triggerSavedAppInstall() {
            if (chooseInstallerPerInstall) {
                showSavedAppInstallerPicker = true
            } else if (
                primaryInstallerIsMount &&
                supportsRootMount &&
                installType != InstallType.MOUNT
            ) {
                val action = if (isInstalledOnDevice) MountWarningAction.UPDATE else MountWarningAction.INSTALL
                viewModel.showMountWarning(action, MountWarningReason.PRIMARY_IS_MOUNT_FOR_NON_MOUNT_APP)
            } else {
                viewModel.installSavedApp()
            }
        }

        fun handleInstallOrUpdate() {
            when (installType) {
                InstallType.MOUNT -> {
                    if (chooseInstallerPerInstall) {
                        if (viewModel.isMounted) {
                            if (!hasRoot) {
                                Toast
                                    .makeText(context, rootRequiredText, Toast.LENGTH_SHORT)
                                    .show()
                            } else {
                                savedAppMountPromptMode = SavedAppMountPromptMode.REMOUNT
                            }
                        } else {
                            savedAppMountPromptMode = SavedAppMountPromptMode.MOUNT_OR_INSTALL
                        }
                    } else if (!primaryInstallerIsMount) {
                        val action = if (isMounted) MountWarningAction.UPDATE else MountWarningAction.INSTALL
                        viewModel.showMountWarning(action, MountWarningReason.PRIMARY_NOT_MOUNT_FOR_MOUNT_APP)
                    } else {
                        if (isMounted) viewModel.remountSavedInstallation() else viewModel.mountOrUnmount()
                    }
                }
                else -> triggerSavedAppInstall()
            }
        }

        fun handleUninstall() {
            when (installType) {
                InstallType.MOUNT -> {
                    if (isMounted) viewModel.unmountSavedInstallation()
                }
                InstallType.SAVED -> {
                    if (isInstalledOnDevice) {
                        if (primaryInstallerIsMount && supportsRootMount) {
                            viewModel.showMountWarning(
                                MountWarningAction.UNINSTALL,
                                MountWarningReason.PRIMARY_IS_MOUNT_FOR_NON_MOUNT_APP
                            )
                        } else {
                            showSavedUninstallDialog = true
                        }
                    }
                }
                else -> {
                    if (isInstalledOnDevice) showUninstallDialog = true
                }
            }
        }

        fun handleDeleteSavedAction() {
            when (installType) {
                InstallType.SAVED -> showSavedAppDeleteDialog = true
                else -> if (viewModel.hasSavedCopy) showSavedEntryDeleteDialog = true
            }
        }

        LaunchedEffect(pendingAction, installedApp.currentPackageName, appliedSelection) {
            val action = pendingAction ?: return@LaunchedEffect
            when (action) {
                InstalledAppAction.OPEN -> {
                    if (isInstalledOnDevice) viewModel.launch()
                    pendingAction = null
                }
                InstalledAppAction.EXPORT -> {
                    openExportPicker()
                    pendingAction = null
                }
                InstalledAppAction.INSTALL_OR_UPDATE -> {
                    handleInstallOrUpdate()
                    pendingAction = null
                }
                InstalledAppAction.REPAIR_ROOT_MOUNT -> {
                    viewModel.repairRootMount()
                    pendingAction = null
                }
                InstalledAppAction.EXPORT_ROOT_MOUNT_DIAGNOSTICS -> {
                    showRootDiagnosticsActionsDialog = true
                    pendingAction = null
                }
                InstalledAppAction.UNINSTALL -> {
                    handleUninstall()
                    pendingAction = null
                }
                InstalledAppAction.DELETE -> {
                    handleDeleteSavedAction()
                    pendingAction = null
                }
                InstalledAppAction.REPATCH -> {
                    val selection = appliedSelection
                    if (selection == null) return@LaunchedEffect
                    if (selection.isEmpty()) {
                        context.toast(context.getString(R.string.no_patches_selected))
                        pendingAction = null
                        return@LaunchedEffect
                    }
                    handleRepatchClick(installedApp.originalPackageName)
                    pendingAction = null
                }
            }
        }

        if (viewModel.appInfo != null && installType != InstallType.MOUNT) {
            key("open") {
                SegmentedButton(
                    icon = Icons.AutoMirrored.Outlined.OpenInNew,
                    text = stringResource(R.string.open_app),
                    onClick = viewModel::launch,
                    enabled = isInstalledOnDevice
                )
            }
        }

        when (installType) {
            InstallType.DEFAULT,
            InstallType.PLAY_STORE,
            InstallType.ROOT_PLAY_STORE,
            InstallType.CUSTOM,
            InstallType.SHIZUKU,
            InstallType.SHIZUKU_PLAY_STORE -> {
                if (viewModel.hasSavedCopy) {
                    val installAction: () -> Unit = ::triggerSavedAppInstall

                    key("export") {
                        SegmentedButton(
                            icon = Icons.Outlined.Save,
                            text = stringResource(R.string.export),
                            onClick = { openExportPicker() }
                        )
                    }
                    key("update_or_install") {
                        SegmentedButton(
                            icon = Icons.Outlined.InstallMobile,
                            text = if (isInstalledOnDevice) stringResource(R.string.update) else stringResource(R.string.install_saved_app),
                            onClick = installAction,
                            onLongClick = if (isInstalledOnDevice) viewModel::uninstall else null
                        )
                    }

                    if (showSavedEntryDeleteDialog) {
                        ConfirmDialog(
                            onDismiss = { showSavedEntryDeleteDialog = false },
                            onConfirm = {
                                showSavedEntryDeleteDialog = false
                                viewModel.deleteSavedEntry()
                            },
                            title = stringResource(R.string.delete_saved_entry_title),
                            description = stringResource(R.string.delete_saved_entry_description),
                            icon = Icons.Outlined.Delete
                        )
                    }
                    key("delete_entry") {
                        SegmentedButton(
                            icon = Icons.Outlined.Delete,
                            text = stringResource(R.string.delete),
                            onClick = { showSavedEntryDeleteDialog = true }
                        )
                    }
                } else if (isInstalledOnDevice) {
                    SegmentedButton(
                        icon = Icons.Outlined.Delete,
                        text = stringResource(R.string.uninstall),
                        onClick = viewModel::uninstall
                    )
                }

                key("repatch") {
                    SegmentedButton(
                        icon = Icons.Outlined.Update,
                        text = stringResource(R.string.repatch),
                        onClick = { handleRepatchClick(installedApp.originalPackageName) }
                    )
                }
            }

            InstallType.MOUNT -> {
                if (showSavedEntryDeleteDialog) {
                    ConfirmDialog(
                        onDismiss = { showSavedEntryDeleteDialog = false },
                        onConfirm = {
                            showSavedEntryDeleteDialog = false
                            viewModel.deleteSavedEntry()
                        },
                        title = stringResource(R.string.delete_root_mount_saved_app_title),
                        description = stringResource(R.string.delete_root_mount_saved_app_description),
                        icon = Icons.Outlined.Delete
                    )
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        SegmentedButton(
                            icon = Icons.AutoMirrored.Outlined.OpenInNew,
                            text = stringResource(R.string.open_app),
                            onClick = viewModel::launch,
                            enabled = viewModel.appInfo != null && isInstalledOnDevice && !viewModel.isRootMountBusy
                        )
                        SegmentedButton(
                            icon = Icons.Outlined.Delete,
                            text = stringResource(R.string.delete),
                            onClick = { showSavedEntryDeleteDialog = true },
                            enabled = !viewModel.isRootMountBusy
                        )
                        SegmentedButton(
                            icon = Icons.Outlined.Save,
                            text = stringResource(R.string.export),
                            onClick = { openExportPicker() }
                        )
                        SegmentedButton(
                            icon = Icons.Outlined.Update,
                            text = stringResource(R.string.repatch),
                            onClick = { handleRepatchClick(installedApp.originalPackageName) },
                            enabled = !viewModel.isRootMountBusy
                        )
                    }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        color = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        prefs.rootMountToolsCollapsed.update(!rootMountToolsCollapsed)
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.root_mount_tools),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f)
                            )
                            ArrowButton(
                                modifier = Modifier.size(22.dp),
                                expanded = !rootMountToolsCollapsed,
                                onClick = null
                            )
                        }
                    }

                    AnimatedVisibility(visible = !rootMountToolsCollapsed) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                SegmentedButton(
                                    icon = Icons.Outlined.Circle,
                                    text = stringResource(
                                        if (viewModel.isMounted) {
                                            R.string.root_mount_use_stock
                                        } else {
                                            R.string.mount
                                        }
                                    ),
                                    onClick = {
                                        if (viewModel.isMounted) {
                                            viewModel.unmountSavedInstallation()
                                        } else {
                                            viewModel.mountOrUnmount()
                                        }
                                    },
                                    enabled = !viewModel.isRootMountBusy
                                )
                                SegmentedButton(
                                    icon = Icons.Outlined.SettingsBackupRestore,
                                    text = stringResource(R.string.root_mount_repair),
                                    onClick = viewModel::repairRootMount,
                                    enabled = !viewModel.isRootMountBusy
                                )
                            }

                            Row(modifier = Modifier.fillMaxWidth()) {
                                SegmentedButton(
                                    icon = Icons.Outlined.Save,
                                    text = stringResource(R.string.root_mount_export_diagnostics),
                                    onClick = { showRootDiagnosticsActionsDialog = true }
                                )
                            }
                        }
                    }
                }
            }

            InstallType.SAVED -> {
                key("export") {
                    SegmentedButton(
                        icon = Icons.Outlined.Save,
                        text = stringResource(R.string.export),
                        onClick = { openExportPicker() }
                    )
                }

                if (showSavedUninstallDialog) {
                    val confirmTitle = stringResource(R.string.saved_app_uninstall_title)
                    val confirmDescription = stringResource(R.string.saved_app_uninstall_description)
                    ConfirmDialog(
                        onDismiss = { showSavedUninstallDialog = false },
                        onConfirm = {
                            showSavedUninstallDialog = false
                            viewModel.uninstallSavedInstallation()
                        },
                        title = confirmTitle,
                        description = confirmDescription,
                        icon = Icons.Outlined.Delete
                    )
                }

                val installText = if (isInstalledOnDevice) {
                    stringResource(R.string.update_saved_app)
                } else {
                    stringResource(R.string.install_saved_app)
                }
                key("update_or_install") {
                    SegmentedButton(
                        icon = Icons.Outlined.InstallMobile,
                        text = installText,
                        onClick = ::triggerSavedAppInstall,
                        onLongClick = if (isInstalledOnDevice) {
                            {
                                if (viewModel.primaryInstallerIsMount && supportsRootMount) {
                                    viewModel.showMountWarning(MountWarningAction.UNINSTALL, MountWarningReason.PRIMARY_IS_MOUNT_FOR_NON_MOUNT_APP)
                                } else {
                                    showSavedUninstallDialog = true
                                }
                            }
                        } else null
                    )
                }

                val deleteAction = viewModel::removeSavedApp
                val deleteTitle = stringResource(R.string.delete_saved_app_title)
                val deleteDescription = stringResource(R.string.delete_saved_app_description)
                val deleteLabel = stringResource(R.string.delete)
                if (showSavedAppDeleteDialog) {
                    ConfirmDialog(
                        onDismiss = { showSavedAppDeleteDialog = false },
                        onConfirm = {
                            showSavedAppDeleteDialog = false
                            deleteAction()
                        },
                        title = deleteTitle,
                        description = deleteDescription,
                        icon = Icons.Outlined.Delete
                    )
                }
                key("delete_entry") {
                    SegmentedButton(
                        icon = Icons.Outlined.Delete,
                        text = deleteLabel,
                        onClick = { showSavedAppDeleteDialog = true }
                    )
                }

                key("repatch") {
                    SegmentedButton(
                        icon = Icons.Outlined.Update,
                        text = stringResource(R.string.repatch),
                        onClick = {
                            handleRepatchClick(installedApp.originalPackageName)
                        }
                    )
                }
            }
        }
    }

            Column(
                modifier = Modifier.padding(vertical = 16.dp)
            ) {
                SettingsListItem(
                    modifier = Modifier.clickable(
                        enabled = appliedSelection != null
                    ) { showAppliedPatchesDialog = true },
                    headlineContent = stringResource(R.string.applied_patches),
                    supportingContent = when (val selection = appliedSelection) {
                        null -> stringResource(R.string.loading)
                        else -> {
                            val count = selection.values.sumOf { it.size }
                            pluralStringResource(
                                id = R.plurals.patch_count,
                                count,
                                count
                            )
                        }
                    },
                    trailingContent = {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowRight,
                            contentDescription = stringResource(R.string.view_applied_patches)
                        )
                    }
                )

                SettingsListItem(
                    headlineContent = stringResource(R.string.package_name),
                    supportingContent = displayPackageName
                )

                if (installedApp.originalPackageName != displayPackageName) {
                    SettingsListItem(
                        headlineContent = stringResource(R.string.original_package_name),
                        supportingContent = installedApp.originalPackageName
                    )
                }

                SettingsListItem(
                    headlineContent = stringResource(R.string.install_type),
                    supportingContent = if (installerLabelType == InstallType.CUSTOM) {
                        viewModel.customInstallerLabel
                            ?: stringResource(installerLabelType.stringResource)
                    } else {
                        stringResource(installerLabelType.stringResource)
                    }
                )

                if (viewModel.hasSavedCopy) {
                    val shortcutPackageName = batchOriginalPackageName(installedApp)
                    val appLauncherShortcutEnabled =
                        shortcutPackageName in savedAppLauncherShortcutPackages
                    val launcherShortcutCapacity = savedAppLauncherShortcutCapacity(context)
                    val canChangeLauncherShortcut = appLauncherShortcutEnabled ||
                        savedAppLauncherShortcutPackages.size < launcherShortcutCapacity
                    SettingsListItem(
                        modifier = Modifier
                            .alpha(if (canChangeLauncherShortcut) 1f else 0.38f)
                            .clickable(enabled = canChangeLauncherShortcut) {
                                viewModel.setLauncherShortcutEnabledForApp(
                                    !appLauncherShortcutEnabled
                                )
                            },
                        headlineContent = stringResource(R.string.saved_app_launcher_shortcut),
                        supportingContent = if (canChangeLauncherShortcut) {
                            stringResource(R.string.saved_app_launcher_shortcut_description)
                        } else {
                            stringResource(
                                R.string.saved_app_launcher_shortcut_limit_reached,
                                launcherShortcutCapacity
                            )
                        },
                        trailingContent = {
                            ExpressiveSettingsSwitch(
                                checked = appLauncherShortcutEnabled,
                                enabled = canChangeLauncherShortcut,
                                onCheckedChange = viewModel::setLauncherShortcutEnabledForApp
                            )
                        }
                    )
                }

                val signatureWorkflow = installedApp.selectionPayload?.signatureWorkflow
                if (signatureWorkflow?.remembered == true &&
                    installedApp.repatchSourcePath?.let { File(it).isFile } == true
                ) {
                    SettingsListItem(
                        modifier = Modifier.clickable {
                            viewModel.setSignatureInjectionEnabled(!signatureWorkflow.enabled)
                        },
                        headlineContent = stringResource(R.string.patch_profile_signature_workflow_enable),
                        supportingContent = stringResource(
                            if (signatureWorkflow.injected) R.string.saved_app_signature_workflow_applied
                            else R.string.saved_app_signature_workflow_not_applied
                        ) + "\n" + stringResource(R.string.saved_app_signature_workflow_description),
                        trailingContent = {
                            ExpressiveSettingsSwitch(
                                checked = signatureWorkflow.enabled,
                                onCheckedChange = viewModel::setSignatureInjectionEnabled
                            )
                        }
                    )
                }

                if (autoPatchEnabled && viewModel.hasSavedCopy) {
                    val appAutoPatchEnabled =
                        installedApp.currentPackageName in autoPatchEnabledPackages
                    SettingsListItem(
                        modifier = Modifier.clickable {
                            viewModel.setAutoPatchEnabledForApp(!appAutoPatchEnabled)
                        },
                        headlineContent = stringResource(R.string.auto_patch_app_enabled),
                        supportingContent = stringResource(
                            R.string.auto_patch_app_enabled_description
                        ),
                        trailingContent = {
                            ExpressiveSettingsSwitch(
                                checked = appAutoPatchEnabled,
                                onCheckedChange = viewModel::setAutoPatchEnabledForApp
                            )
                        }
                    )
                }

                val bundleSummaryText = when {
                    appliedSelection == null -> stringResource(R.string.loading)
                    appliedBundles.isEmpty() -> stringResource(R.string.no_patch_bundles_tracked)
                    else -> null
                }
                SettingsListItem(
                    headlineContent = stringResource(R.string.patch_bundles_used),
                    supportingContent = bundleSummaryText,
                    supportingContentSlot = if (bundleSummaryText == null) {
                        {
                            AppliedBundleSummary(
                                bundles = appliedBundles,
                                clipboard = clipboard,
                                onCopied = {
                                    context.toast(copiedToClipboardMessage)
                                }
                            )
                        }
                    } else {
                        null
                    }
                )

                val missingBundles = appliedBundles.filterNot { it.bundleAvailable }
                if (installedApp.installType == InstallType.SAVED && missingBundles.isNotEmpty()) {
                    missingBundles.forEach { bundle ->
                        SettingsListItem(
                            headlineContent = stringResource(R.string.saved_app_bundle_missing_title),
                            supportingContent = stringResource(
                                R.string.patch_profile_bundle_unavailable_suffix,
                                bundle.title
                            ),
                            trailingContent = {
                                TextButton(
                                    onClick = {
                                        val requiredPatches = buildSet {
                                            appliedSelection?.get(bundle.uid).orEmpty().forEach { add(it) }
                                            bundle.patchInfos.forEach { add(it.name) }
                                            bundle.fallbackNames.forEach { add(it) }
                                        }.map { it.trim() }
                                            .filter { it.isNotBlank() }
                                            .map { it.lowercase(Locale.ROOT) }
                                            .toSet()
                                        missingBundleSelectionUid = null
                                        missingBundleTarget = SavedBundleTarget(
                                            bundleUid = bundle.uid,
                                            bundleName = bundle.title,
                                            requiredPatchesLowercase = requiredPatches
                                        )
                                    }
                                ) {
                                    Text(stringResource(R.string.saved_app_bundle_select_action))
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showLeaveInstallDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveInstallDialog = false },
            title = { CenteredDialogTitle(stringResource(R.string.patcher_install_in_progress_title)) },
            text = {
                Text(
                    stringResource(R.string.patcher_install_in_progress),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLeaveInstallDialog = false
                        viewModel.cancelOngoingInstall()
                        onBackClick()
                    }
                ) {
                    Text(stringResource(R.string.patcher_install_in_progress_leave))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveInstallDialog = false }) {
                    Text(stringResource(R.string.patcher_install_in_progress_stay))
                }
            }
        )
    }
}

@Composable
fun UninstallDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { CenteredDialogTitle(stringResource(R.string.unpatch_app)) },
    text = { Text(stringResource(R.string.unpatch_description)) },
    confirmButton = {
        TextButton(
            onClick = {
                onConfirm()
                onDismiss()
            }
        ) {
            Text(stringResource(R.string.ok))
        }
    },
    dismissButton = {
        TextButton(
            onClick = onDismiss
        ) {
            Text(stringResource(R.string.cancel))
        }
    }
)

@Composable
internal fun RootMountDiagnosticsActionsDialog(
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onExport: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Description, contentDescription = null) },
        title = {
            CenteredDialogTitle(stringResource(R.string.root_mount_diagnostics_dialog_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.root_mount_diagnostics_dialog_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onCopy)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(R.string.root_mount_diagnostics_dialog_copy),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = stringResource(
                                    R.string.root_mount_diagnostics_dialog_copy_description
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onExport)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(R.string.root_mount_diagnostics_dialog_export),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = stringResource(
                                    R.string.root_mount_diagnostics_dialog_export_description
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        dismissButton = {}
    )
}

@Composable
internal fun ExportRootDiagnosticsFileNameDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var fileName by rememberSaveable(initialName) { mutableStateOf(initialName) }
    val trimmedName = fileName.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.root_mount_diagnostics_save_title),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        icon = {
            Icon(
                Icons.Outlined.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trimmedName) },
                enabled = trimmedName.isNotEmpty()
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.file_name),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    placeholder = { Text(stringResource(R.string.dialog_input_placeholder)) },
                    singleLine = true
                )
            }
        }
    )
}

private data class RootDiagnosticsExportDialogState(
    val directory: Path,
    val fileName: String
)

private data class PendingRootDiagnosticsExportConfirmation(
    val directory: Path,
    val fileName: String
)

private data class ExportSavedApkDialogState(
    val directory: Path,
    val fileName: String
)

private data class PendingSavedExportConfirmation(
    val directory: Path,
    val fileName: String
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppliedBundleSummary(
    bundles: List<AppliedPatchBundleUi>,
    clipboard: ClipboardManager?,
    onCopied: () -> Unit
) {
    val updateCount = bundles.count { it.hasUpdate }
    val showIndividualUpdates = bundles.size > 1 && updateCount in 1 until bundles.size
    val copyToClipboardLabel = stringResource(R.string.copy_to_clipboard)

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        bundles.forEach { bundle ->
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = bundle.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline
                )
                bundle.version
                    ?.takeIf(String::isNotBlank)
                    ?.toPrefixedVersionLabel()
                    ?.let { versionLabel ->
                        val copyBundleVersion = {
                            clipboard?.setPrimaryClip(
                                ClipData.newPlainText(
                                    "${bundle.title} version",
                                    versionLabel
                                )
                            )
                            if (clipboard != null) {
                                onCopied()
                            }
                        }.withHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        Text(
                            text = versionLabel,
                            modifier = Modifier.longPressOnly(
                                label = copyToClipboardLabel,
                                onLongPress = copyBundleVersion
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                if (showIndividualUpdates && bundle.hasUpdate) {
                    Text(
                        text = "(${stringResource(R.string.update)})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

private fun String.toPrefixedVersionLabel(): String {
    val normalized = trim().removePrefix("v").removePrefix("V")
    return "v$normalized"
}

private fun String.normalizedBundleEndpoint(): String? =
    app.universal.patch.manager.domain.bundles.bundleEndpointIdentity(this).trimEnd('/').takeIf(String::isNotBlank)
