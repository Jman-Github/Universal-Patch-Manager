package app.universal.patch.manager.ui.screen

import app.universal.patch.manager.ui.component.minimalActionButtons
import app.universal.patch.manager.util.managerStorageContext

import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universal.patch.manager.ui.component.AppIcon
import app.universal.patch.manager.ui.component.InterceptBackHandler
import app.universal.patch.manager.ui.component.LazyColumnWithScrollbar
import app.universal.patch.manager.ui.component.DASHBOARD_SCROLLBAR_THUMB_LENGTH
import app.universal.patch.manager.ui.component.LoadingIndicator
import app.universal.patch.manager.ui.component.Scrollbar
import app.universal.patch.manager.ui.component.TextInputDialog
import app.universal.patch.manager.ui.component.haptics.HapticTab
import app.universal.patch.manager.ui.component.haptics.HapticCheckbox
import app.universal.patch.manager.ui.component.patches.PathSelectorDialog
import app.universal.patch.manager.ui.component.patcher.InstallerPickerDialog
import app.universal.patch.manager.ui.component.RememberedGetContent
import app.universal.patch.manager.ui.component.toPickerDirectoryUri
import app.universal.patch.manager.data.platform.Filesystem
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.domain.installer.InstallerManager
import app.universal.patch.manager.domain.repository.DownloadedAppRepository
import app.universal.patch.manager.domain.repository.resolvePatchProfileAppVersion
import app.universal.patch.manager.patcher.split.SplitArchiveDisplayResolver
import app.universal.patch.manager.patcher.split.SplitApkPreparer
import app.universal.patch.manager.ui.viewmodel.BundleSourceType
import app.universal.patch.manager.ui.viewmodel.BundleOptionDisplay
import app.universal.patch.manager.ui.viewmodel.PatchProfileLaunchData
import app.universal.patch.manager.ui.viewmodel.PatchProfileListItem
import app.universal.patch.manager.ui.viewmodel.PatchProfilesViewModel
import app.universal.patch.manager.ui.viewmodel.PatchProfilesViewModel.RenameResult
import app.universal.patch.manager.ui.model.PatchProfileActionKey
import app.universal.patch.manager.util.APK_FILE_EXTENSIONS
import app.universal.patch.manager.util.PM
import app.universal.patch.manager.util.consumeHorizontalScroll
import app.universal.patch.manager.util.resolveSupportedApkExtension
import app.universal.patch.manager.util.relativeTime
import app.universal.patch.manager.util.toast
import app.universal.patch.manager.util.isAllowedApkFile
import app.universal.patch.manager.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.io.File
import java.util.Locale
import app.universal.patch.manager.ui.component.CenteredDialogTitle

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun PatchProfilesScreen(
    onProfileClick: (PatchProfileLaunchData) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PatchProfilesViewModel,
    showOrderDialog: Boolean = false,
    onDismissOrderDialog: () -> Unit = {},
    searchQuery: String = ""
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val remoteBundleOptions by viewModel.remoteBundleOptions.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs = koinInject<PreferencesManager>()
    val installerManager = koinInject<InstallerManager>()
    val useCustomFilePicker by prefs.useCustomFilePicker.flow.collectAsStateWithLifecycle(
        initialValue = prefs.useCustomFilePicker.default
    )
    val patchProfileApkInputDirectory by
        prefs.patchProfileApkInputLastDirectory.flow.collectAsStateWithLifecycle(
            initialValue = prefs.patchProfileApkInputLastDirectory.default
        )
    val allowUniversal by prefs.disableUniversalPatchCheck.flow.collectAsStateWithLifecycle(
        initialValue = prefs.disableUniversalPatchCheck.default
    )
    val allowBundleOverride by prefs.allowPatchProfileBundleOverride.flow.collectAsStateWithLifecycle(
        initialValue = prefs.allowPatchProfileBundleOverride.default
    )
    val profileActionOrderPref by prefs.patchProfileActionOrder.flow.collectAsStateWithLifecycle(
        initialValue = prefs.patchProfileActionOrder.default
    )
    val profileHiddenActionsPref by prefs.patchProfileHiddenActions.flow.collectAsStateWithLifecycle(
        initialValue = prefs.patchProfileHiddenActions.default
    )
    val filesystem = koinInject<Filesystem>()
    val downloadedAppRepository = koinInject<DownloadedAppRepository>()
    val downloadedApps by downloadedAppRepository.getAll().collectAsStateWithLifecycle(emptyList())
    val pm = koinInject<PM>()
    val storageRoots = remember { filesystem.storageRoots() }
    var loadingProfileId by remember { mutableStateOf<Int?>(null) }
    var blockedProfile by remember { mutableStateOf<PatchProfileLaunchData?>(null) }
    var renameProfileId by rememberSaveable { mutableStateOf<Int?>(null) }
    var renameProfileName by rememberSaveable { mutableStateOf("") }
    var versionDialogProfile by remember { mutableStateOf<PatchProfileListItem?>(null) }
    var versionDialogValue by rememberSaveable { mutableStateOf("") }
    var versionDialogAllVersions by rememberSaveable { mutableStateOf(false) }
    var versionDialogUseSelectedApkVersion by remember { mutableStateOf(false) }
    var versionDialogSaving by remember { mutableStateOf(false) }
    var settingsDialogProfile by remember { mutableStateOf<PatchProfileListItem?>(null) }
    var installerPickerProfile by remember { mutableStateOf<PatchProfileListItem?>(null) }
    var apkPickerProfile by remember { mutableStateOf<PatchProfileListItem?>(null) }
    var downloadedApkPickerProfile by remember { mutableStateOf<PatchProfileListItem?>(null) }
    var pendingDocumentApkPickerProfile by remember { mutableStateOf<PatchProfileListItem?>(null) }
    var apkPickerBusy by remember { mutableStateOf(false) }
    data class ChangeUidTarget(val profileId: Int, val bundleUid: Int, val bundleName: String?)
    var changeUidTarget by remember { mutableStateOf<ChangeUidTarget?>(null) }
    data class RemoteBundleTarget(
        val profileId: Int,
        val bundleUid: Int,
        val bundleName: String?,
        val requiredPatchesLowercase: Set<String>
    )
    var remoteBundleTarget by remember { mutableStateOf<RemoteBundleTarget?>(null) }
    var remoteBundleSelectionUid by rememberSaveable { mutableStateOf<Int?>(null) }
    var remoteBundleSaving by remember { mutableStateOf(false) }
    data class RemoteBundleOverrideTarget(
        val profileId: Int,
        val bundleUid: Int,
        val targetUid: Int,
        val displayName: String
    )
    var remoteBundleIncompatibleTarget by remember { mutableStateOf<RemoteBundleOverrideTarget?>(null) }
    val expandedProfiles = remember { mutableStateMapOf<Int, Boolean>() }
    val selectedBundleTabs = remember { mutableStateMapOf<Int, Int>() }
    val selectionActive = viewModel.selectedProfiles.isNotEmpty()
    data class OptionDialogData(val patchName: String, val entries: List<BundleOptionDisplay>)
    var optionDialogData by remember { mutableStateOf<OptionDialogData?>(null) }
    val profileActionOrder = remember(profileActionOrderPref) {
        val parsed = profileActionOrderPref
            .split(',')
            .mapNotNull { PatchProfileActionKey.fromStorageId(it.trim()) }
        PatchProfileActionKey.ensureComplete(parsed)
    }
    val visibleProfileActionKeys = remember(profileActionOrder, profileHiddenActionsPref) {
        profileActionOrder.filter { key -> key.storageId !in profileHiddenActionsPref }
    }
    val normalizedQuery = searchQuery.trim().lowercase()
    val filteredProfiles = if (normalizedQuery.isBlank()) {
        profiles
    } else {
        profiles.filter { profile ->
            val searchText = buildString {
                append(profile.name)
                append(' ')
                append(profile.packageName)
                profile.appVersion?.let { version ->
                    append(' ')
                    append(version)
                }
                profile.apkVersion?.let { version ->
                    append(' ')
                    append(version)
                }
                profile.bundleNames.forEach { name ->
                    append(' ')
                    append(name)
                }
            }.lowercase()
            searchText.contains(normalizedQuery)
        }
    }

    InterceptBackHandler(enabled = selectionActive) {
        viewModel.handleEvent(PatchProfilesViewModel.Event.CANCEL)
    }

    fun handleApkSelectionResult(profileName: String, result: PatchProfilesViewModel.ApkSelectionResult) {
        when (result) {
            PatchProfilesViewModel.ApkSelectionResult.SUCCESS -> context.toast(
                context.getString(R.string.patch_profile_apk_saved_toast, profileName)
            )
            PatchProfilesViewModel.ApkSelectionResult.INVALID_FILE -> context.toast(
                context.getString(R.string.patch_profile_apk_invalid_toast)
            )
            PatchProfilesViewModel.ApkSelectionResult.PACKAGE_MISMATCH -> context.toast(
                context.getString(R.string.patch_profile_apk_mismatch_toast)
            )
            PatchProfilesViewModel.ApkSelectionResult.PROFILE_NOT_FOUND,
            PatchProfilesViewModel.ApkSelectionResult.FAILED,
            PatchProfilesViewModel.ApkSelectionResult.CLEARED -> context.toast(
                context.getString(R.string.patch_profile_apk_failed_toast)
            )
        }
    }
    val apkDocumentLauncher = rememberLauncherForActivityResult(
        contract = RememberedGetContent {
            patchProfileApkInputDirectory.takeIf(String::isNotBlank)?.let(Uri::parse)
        }
    ) { uri ->
        val profile = pendingDocumentApkPickerProfile
        pendingDocumentApkPickerProfile = null
        apkPickerProfile = null
        if (profile == null || uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            prefs.patchProfileApkInputLastDirectory.update(uri.toPickerDirectoryUri().toString())
        }
        if (!isAllowedApkUri(context, uri)) {
            handleApkSelectionResult(profile.name, PatchProfilesViewModel.ApkSelectionResult.INVALID_FILE)
            return@rememberLauncherForActivityResult
        }
        apkPickerBusy = true
        scope.launch {
            val tempFile = withContext(Dispatchers.IO) {
                val displayName = resolveApkUriDisplayName(context, uri)
                val extension = resolveSupportedApkExtension(
                    displayName = displayName,
                    mimeType = context.contentResolver.getType(uri)
                ) ?: "apk"
                val file = File.createTempFile("patch-profile-apk", ".${extension}", context.managerStorageContext.cacheDir)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                file
            }
            try {
                val result = viewModel.updateProfileApk(profile.id, tempFile)
                handleApkSelectionResult(profile.name, result)
            } finally {
                withContext(Dispatchers.IO) { tempFile.delete() }
                apkPickerBusy = false
            }
        }
    }

    apkPickerProfile?.let { profile ->
        if (!useCustomFilePicker) return@let
        PathSelectorDialog(
            roots = storageRoots,
            onSelect = { path ->
                apkPickerProfile = null
                if (path == null) return@PathSelectorDialog
                apkPickerBusy = true
                scope.launch {
                    try {
                        val result = viewModel.updateProfileApk(profile.id, File(path.toString()))
                        handleApkSelectionResult(profile.name, result)
                    } finally {
                        apkPickerBusy = false
                    }
                }
            },
            fileFilter = ::isAllowedApkFile,
            allowDirectorySelection = false,
            lastDirectoryPreference = prefs.patchProfileApkInputLastDirectory,
            fileTypeLabel = stringResource(R.string.apk_file_type)
        )
    }
    LaunchedEffect(apkPickerProfile?.id, useCustomFilePicker) {
        val profile = apkPickerProfile
        if (profile != null && !useCustomFilePicker) {
            pendingDocumentApkPickerProfile = profile
            apkDocumentLauncher.launch("application/*")
        }
    }
    downloadedApkPickerProfile?.let { profile ->
        val matchingDownloadedApps = remember(downloadedApps, profile.packageName) {
            downloadedApps
                .asSequence()
                .filter { it.packageName.equals(profile.packageName, ignoreCase = true) }
                .sortedByDescending { it.lastUsed }
                .toList()
        }
        AlertDialog(
            onDismissRequest = {
                if (apkPickerBusy) return@AlertDialog
                downloadedApkPickerProfile = null
            },
            confirmButton = {
                TextButton(
                    onClick = { downloadedApkPickerProfile = null },
                    enabled = !apkPickerBusy
                ) { Text(stringResource(R.string.close)) }
            },
            title = { CenteredDialogTitle(stringResource(R.string.downloaded_apps)) },
            text = {
                if (matchingDownloadedApps.isEmpty()) {
                    Text(
                        text = stringResource(R.string.patch_profile_downloaded_apps_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.heightIn(max = 320.dp)
                    ) {
                        items(
                            items = matchingDownloadedApps,
                            key = { "${it.packageName}:${it.version}" }
                        ) { downloadedApp ->
                            TextButton(
                                onClick = {
                                    if (apkPickerBusy) return@TextButton
                                    apkPickerBusy = true
                                    scope.launch {
                                        try {
                                            val sourceFile = withContext(Dispatchers.IO) {
                                                downloadedAppRepository.getApkFileForApp(downloadedApp)
                                            }
                                            val result = viewModel.updateProfileApk(profile.id, sourceFile)
                                            handleApkSelectionResult(profile.name, result)
                                            if (result == PatchProfilesViewModel.ApkSelectionResult.SUCCESS) {
                                                downloadedApkPickerProfile = null
                                            }
                                        } finally {
                                            apkPickerBusy = false
                                        }
                                    }
                                },
                                enabled = !apkPickerBusy,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                    horizontalAlignment = Alignment.Start,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = downloadedApp.version,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = downloadedApp.packageName,
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

    renameProfileId?.let { targetId ->
        TextInputDialog(
            initial = renameProfileName,
            title = stringResource(R.string.patch_profile_rename_title),
            onDismissRequest = { renameProfileId = null },
            onConfirm = { newName ->
                val trimmed = newName.trim()
                if (trimmed.isEmpty()) return@TextInputDialog
                scope.launch {
                    when (viewModel.renameProfile(targetId, trimmed)) {
                        RenameResult.SUCCESS -> {
                            context.toast(context.getString(R.string.patch_profile_updated_toast, trimmed))
                            renameProfileId = null
                        }
                        RenameResult.DUPLICATE_NAME -> {
                            context.toast(context.getString(R.string.patch_profile_duplicate_toast, trimmed))
                            renameProfileName = trimmed
                        }
                        RenameResult.FAILED -> {
                            context.toast(context.getString(R.string.patch_profile_save_failed_toast))
                            renameProfileId = null
                        }
                    }
                }
            },
            validator = { it.isNotBlank() }
        )
    }

    changeUidTarget?.let { target ->
        TextInputDialog(
            initial = target.bundleUid.toString(),
            title = stringResource(
                R.string.patch_profile_bundle_change_uid_title,
                target.bundleName ?: target.bundleUid.toString()
            ),
            onDismissRequest = { changeUidTarget = null },
            onConfirm = { newValue ->
                val trimmed = newValue.trim()
                val newUid = trimmed.toIntOrNull()
                if (newUid == null) {
                    context.toast(context.getString(R.string.patch_profile_bundle_change_uid_invalid))
                    return@TextInputDialog
                }
                scope.launch {
                    val result = viewModel.changeLocalBundleUid(target.profileId, target.bundleUid, newUid)
                    when (result) {
                        PatchProfilesViewModel.ChangeUidResult.SUCCESS -> context.toast(
                            context.getString(R.string.patch_profile_bundle_change_uid_success, newUid)
                        )

                        PatchProfilesViewModel.ChangeUidResult.PROFILE_OR_BUNDLE_NOT_FOUND,
                        PatchProfilesViewModel.ChangeUidResult.TARGET_NOT_FOUND -> context.toast(
                            context.getString(R.string.patch_profile_bundle_change_uid_not_found, newUid)
                        )
                    }
                    changeUidTarget = null
                }
            },
            validator = { it.trim().toIntOrNull() != null }
        )
    }

    if (profiles.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.patch_profile_empty_state),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
            )
        }
        return
    }

    if (filteredProfiles.isEmpty() && normalizedQuery.isNotBlank()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.profile_search_no_results),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    LazyColumnWithScrollbar(
        thumbLength = DASHBOARD_SCROLLBAR_THUMB_LENGTH,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(filteredProfiles, key = { it.id }) { profile ->
            val patchCount = profile.bundleDetails.sumOf { it.patchCount }
            val patchCountText = pluralStringResource(R.plurals.patch_count, patchCount, patchCount)
            val bundleCountText = pluralStringResource(
                R.plurals.patch_profile_bundle_count,
                profile.bundleCount,
                profile.bundleCount
            )
            val storedApkPath = profile.apkPath?.takeIf { it.isNotBlank() }
            val hasAvailableApk = remember(storedApkPath) {
                storedApkPath?.let { File(it).exists() } == true
            }
            val hasMissingApk = storedApkPath != null && !hasAvailableApk
            val apkPath = storedApkPath?.takeIf { hasAvailableApk }
            val displayedVersion = resolvePatchProfileAppVersion(
                appVersion = profile.appVersion,
                apkPath = storedApkPath,
                apkVersion = profile.apkVersion,
                useSelectedApkVersion = profile.useSelectedApkVersion
            )
            val versionLabel = formatProfileVersionLabel(
                version = displayedVersion,
                allVersionsLabel = stringResource(R.string.bundle_version_all_versions)
            )
            val creationText = profile.createdAt.takeIf { it > 0 }?.relativeTime(context)?.let {
                stringResource(R.string.patch_profile_created_at, it)
            }
            val expanded = expandedProfiles[profile.id] == true
            val isSelected = profile.id in viewModel.selectedProfiles
            val cardShape = RoundedCornerShape(16.dp)
            val cardColor = if (isSelected) {
                MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp)
            } else {
                MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(cardShape)
                    .combinedClickable(
                        enabled = loadingProfileId == null,
                        onClick = {
                            if (selectionActive) {
                                viewModel.toggleSelection(profile.id)
                                return@combinedClickable
                            }
                            if (loadingProfileId != null) return@combinedClickable
                            loadingProfileId = profile.id
                            scope.launch {
                                try {
                                    val launchData = viewModel.resolveProfile(profile.id)
                                    if (launchData != null) {
                                        if (launchData.availableBundleCount == 0) {
                                            context.toast(
                                                context.getString(R.string.patch_profile_no_available_bundles_toast)
                                            )
                                            return@launch
                                        }
                                        if (launchData.missingBundles.isNotEmpty()) {
                                            context.toast(
                                                context.getString(R.string.patch_profile_missing_bundles_toast)
                                            )
                                        }
                                        if (launchData.changedBundles.isNotEmpty()) {
                                            context.toast(
                                                context.getString(R.string.patch_profile_changed_patches_toast)
                                            )
                                        }
                                        if (!allowUniversal && launchData.containsUniversalPatches) {
                                            blockedProfile = launchData
                                            return@launch
                                        }
                                        onProfileClick(launchData)
                                    } else {
                                        context.toast(
                                            context.getString(R.string.patch_profile_launch_error)
                                        )
                                    }
                                } finally {
                                    loadingProfileId = null
                                }
                            }
                        },
                        onLongClick = { viewModel.toggleSelection(profile.id) }
                    ),
                shape = cardShape,
                tonalElevation = if (isSelected) 4.dp else 2.dp,
                color = cardColor
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (selectionActive) {
                                HapticCheckbox(
                                    checked = isSelected,
                                    onCheckedChange = { viewModel.setSelection(profile.id, it) }
                                )
                            }
                            if (apkPath != null) {
                                PatchProfileApkIcon(
                                    apkPath = apkPath,
                                    pm = pm,
                                    filesystem = filesystem,
                                    modifier = Modifier.size(44.dp)
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = profile.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = profile.packageName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Divider(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                )
                            }
                            if (loadingProfileId == profile.id) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.consumeHorizontalScroll(rememberScrollState())
                        ) {
                            ProfileMetaPill(text = patchCountText)
                            ProfileMetaPill(text = bundleCountText)
                            ProfileMetaPill(text = versionLabel)
                            if (hasMissingApk) {
                                ProfileMetaPill(text = stringResource(R.string.patch_profile_apk_missing))
                            }
                        }
                        creationText?.let { created ->
                            Text(
                                text = created,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!selectionActive) {
                            val actionScrollState = rememberScrollState()
                            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(
                                        8.dp,
                                        Alignment.CenterHorizontally
                                    ),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .widthIn(min = maxWidth)
                                        .consumeHorizontalScroll(actionScrollState)
                                ) {
                                    visibleProfileActionKeys.forEach { actionKey ->
                                        when (actionKey) {
                                            PatchProfileActionKey.RENAME -> ProfileActionPill(
                                                text = stringResource(R.string.patch_profile_rename),
                                                icon = Icons.Outlined.Edit
                                            ) {
                                                renameProfileId = profile.id
                                                renameProfileName = profile.name
                                            }
                                            PatchProfileActionKey.VIEW_PATCHES -> ProfileActionPill(
                                                text = stringResource(
                                                    if (expanded) R.string.patch_profile_show_less
                                                    else R.string.patch_profile_show_more
                                                ),
                                                icon = Icons.Outlined.Extension
                                            ) {
                                                expandedProfiles[profile.id] = !expanded
                                            }
                                            PatchProfileActionKey.SETTINGS -> ProfileActionPill(
                                                text = stringResource(R.string.settings),
                                                icon = Icons.Outlined.Settings
                                            ) {
                                                settingsDialogProfile = profile
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    AnimatedVisibility(
                        visible = expanded,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        val bundleDetails = profile.bundleDetails
                        if (bundleDetails.isNotEmpty()) {
                            val selectedBundleIndex = (selectedBundleTabs[profile.id] ?: 0)
                                .coerceIn(0, bundleDetails.lastIndex)
                            val detail = bundleDetails[selectedBundleIndex]
                            val selectedBundleBaseName = detail.displayName
                                ?: stringResource(R.string.patches_name_fallback)
                            val selectedBundleDisplayName = if (detail.isAvailable) {
                                selectedBundleBaseName
                            } else {
                                stringResource(
                                    R.string.patch_profile_bundle_unavailable_suffix,
                                    selectedBundleBaseName
                                )
                            }
                            val selectedBundleTypeLabel = stringResource(
                                when (detail.type) {

                                    BundleSourceType.Remote -> R.string.bundle_type_remote
                                    else -> R.string.bundle_type_local
                                }
                            )
                            val showRemoteReplacementAction =
                                !detail.isAvailable && detail.type == BundleSourceType.Remote && !selectionActive
                            val showLocalUidRow = detail.type == BundleSourceType.Local
                            Column(
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (bundleDetails.size > 1) {
                                    ScrollableTabRow(
                                        selectedTabIndex = selectedBundleIndex,
                                        containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                                        edgePadding = 0.dp,
                                        divider = {}
                                    ) {
                                        bundleDetails.forEachIndexed { index, bundleDetail ->
                                            val tabTitle = bundleDetail.displayName
                                                ?: context.getString(R.string.patches_name_fallback)
                                            val tabTypeLabel = context.getString(
                                                when (bundleDetail.type) {

                                                    BundleSourceType.Remote -> R.string.bundle_type_remote
                                                    else -> R.string.bundle_type_local
                                                }
                                            )
                                            HapticTab(
                                                selected = index == selectedBundleIndex,
                                                onClick = { selectedBundleTabs[profile.id] = index },
                                                text = {
                                                    Column(
                                                        horizontalAlignment = Alignment.CenterHorizontally,
                                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                                    ) {
                                                        Text(
                                                            text = tabTitle,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        Text(
                                                            text = tabTypeLabel,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                            style = MaterialTheme.typography.labelSmall
                                                        )
                                                    }
                                                },
                                                selectedContentColor = MaterialTheme.colorScheme.primary,
                                                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                } else {
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            text = selectedBundleDisplayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = selectedBundleTypeLabel,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                }
                                if (showRemoteReplacementAction || showLocalUidRow) {
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        if (showRemoteReplacementAction) {
                                            ProfileActionText(
                                                text = stringResource(R.string.patch_profile_bundle_select_remote)
                                            ) {
                                                remoteBundleTarget = RemoteBundleTarget(
                                                    profileId = profile.id,
                                                    bundleUid = detail.uid,
                                                    bundleName = detail.displayName ?: detail.uid.toString(),
                                                    requiredPatchesLowercase = detail.patches
                                                        .mapTo(mutableSetOf()) { it.trim().lowercase() }
                                                )
                                                remoteBundleSelectionUid = null
                                            }
                                        }
                                        if (showLocalUidRow) {
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = stringResource(
                                                        R.string.patch_profile_bundle_uid_label,
                                                        detail.uid
                                                    ),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                if (!selectionActive) {
                                                    ProfileActionText(
                                                        text = stringResource(R.string.patch_profile_bundle_change_uid)
                                                    ) {
                                                        changeUidTarget = ChangeUidTarget(
                                                            profileId = profile.id,
                                                            bundleUid = detail.uid,
                                                            bundleName = detail.displayName
                                                                ?: detail.uid.toString()
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Divider(color = MaterialTheme.colorScheme.outlineVariant)
                                    }
                                }
                                val patchCountText = pluralStringResource(
                                    R.plurals.patch_profile_bundle_patch_count,
                                    detail.patches.size,
                                    detail.patches.size
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    ProfileMetaPill(text = patchCountText)
                                }
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    tonalElevation = 1.dp,
                                    color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
                                ) {
                                    val patchScrollState = rememberScrollState()
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp)
                                            .heightIn(max = 220.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(end = 8.dp)
                                                .verticalScroll(patchScrollState),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            detail.patches.forEachIndexed { index, patchName ->
                                                val optionList = detail.options[patchName]
                                                    ?: detail.options[patchName.trim()]
                                                    ?: detail.options.entries.firstOrNull {
                                                        it.key.equals(patchName, ignoreCase = true)
                                                    }?.value
                                                    ?: emptyList()
                                                val hasOptions = optionList.isNotEmpty()
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Text(
                                                        text = patchName,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    if (hasOptions) {
                                                        IconButton(
                                                            onClick = {
                                                                optionDialogData = OptionDialogData(
                                                                    patchName = patchName,
                                                                    entries = optionList
                                                                )
                                                            },
                                                            modifier = Modifier.size(24.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Outlined.MoreVert,
                                                                contentDescription = stringResource(R.string.patch_profile_view_options),
                                                                tint = MaterialTheme.colorScheme.primary,
                                                                modifier = Modifier.size(18.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                                if (index != detail.patches.lastIndex) {
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                }
                                            }
                                        }
                                        Scrollbar(
                                            scrollState = patchScrollState,
                                            modifier = Modifier.align(Alignment.CenterEnd),
                                            prominent = true
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

    if (showOrderDialog) {
        PatchProfilesOrderDialog(
            profiles = profiles,
            onDismissRequest = onDismissOrderDialog,
            onConfirm = { ordered ->
                viewModel.reorderProfiles(ordered.map { it.id })
                onDismissOrderDialog()
            }
        )
    }

    optionDialogData?.let { data ->
        AlertDialog(
            onDismissRequest = { optionDialogData = null },
            confirmButton = {
                TextButton(onClick = { optionDialogData = null }) {
                    Text(stringResource(R.string.close))
                }
            },
            title = { CenteredDialogTitle(stringResource(R.string.patch_profile_patch_options_title, data.patchName)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    data.entries.forEach { entry ->
                        Text(
                            text = "${entry.label}: ${entry.displayValue}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        )
    }

    remoteBundleTarget?.let { target ->
        val options = remoteBundleOptions
        AlertDialog(
            onDismissRequest = {
                if (remoteBundleSaving) return@AlertDialog
                remoteBundleTarget = null
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val selectedId = remoteBundleSelectionUid ?: return@TextButton
                        val selected = options.firstOrNull { it.uid == selectedId } ?: return@TextButton
                        if (remoteBundleSaving) return@TextButton
                        val isCompatible = target.requiredPatchesLowercase.all {
                            it in selected.patchNamesLowercase
                        }
                        if (!isCompatible) {
                            remoteBundleIncompatibleTarget = RemoteBundleOverrideTarget(
                                profileId = target.profileId,
                                bundleUid = target.bundleUid,
                                targetUid = selected.uid,
                                displayName = selected.displayName
                            )
                            return@TextButton
                        }
                        remoteBundleSaving = true
                        scope.launch {
                            try {
                                when (
                                    viewModel.replaceRemoteBundle(
                                        target.profileId,
                                        target.bundleUid,
                                        selected.uid,
                                        target.requiredPatchesLowercase,
                                        false
                                    )
                                ) {
                                    PatchProfilesViewModel.ReplaceRemoteBundleResult.SUCCESS -> context.toast(
                                        context.getString(
                                            R.string.patch_profile_bundle_select_remote_success,
                                            selected.displayName
                                        )
                                    )
                                    PatchProfilesViewModel.ReplaceRemoteBundleResult.INCOMPATIBLE -> {
                                        remoteBundleIncompatibleTarget = RemoteBundleOverrideTarget(
                                            profileId = target.profileId,
                                            bundleUid = target.bundleUid,
                                            targetUid = selected.uid,
                                            displayName = selected.displayName
                                        )
                                    }
                                    PatchProfilesViewModel.ReplaceRemoteBundleResult.TARGET_NOT_FOUND,
                                    PatchProfilesViewModel.ReplaceRemoteBundleResult.PROFILE_OR_BUNDLE_NOT_FOUND,
                                    PatchProfilesViewModel.ReplaceRemoteBundleResult.FAILED -> context.toast(
                                        context.getString(R.string.patch_profile_bundle_select_remote_error)
                                    )
                                }
                            } finally {
                                remoteBundleSaving = false
                                remoteBundleTarget = null
                            }
                        }
                    },
                    enabled = !remoteBundleSaving && remoteBundleSelectionUid != null && options.isNotEmpty()
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (remoteBundleSaving) return@TextButton
                        remoteBundleTarget = null
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
            title = {
                Text(
                    stringResource(
                        R.string.patch_profile_bundle_select_remote_title,
                        target.bundleName ?: target.bundleUid.toString()
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
                        text = stringResource(R.string.patch_profile_bundle_select_remote_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (options.isEmpty()) {
                        Text(
                            text = stringResource(R.string.patch_profile_bundle_select_remote_empty),
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
                                    .clickable {
                                        remoteBundleSelectionUid = option.uid
                                    }
                                    .padding(vertical = 6.dp)
                            ) {
                                RadioButton(
                                    selected = remoteBundleSelectionUid == option.uid,
                                    onClick = { remoteBundleSelectionUid = option.uid }
                                )
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = option.displayName,
                                        style = MaterialTheme.typography.bodyMedium
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

    installerPickerProfile?.let { profile ->
        InstallerPickerDialog(
            title = stringResource(R.string.patch_profile_installer_choose_title),
            options = installerManager.listEntries(
                target = InstallerManager.InstallTarget.PATCHER,
                includeNone = false
            ),
            initialSelection = profile.installerToken?.let(installerManager::parseToken),
            confirmLabel = R.string.save,
            onDismiss = { installerPickerProfile = null },
            onConfirm = { token ->
                scope.launch {
                    val saved = viewModel.updateProfileInstaller(
                        profile.id,
                        installerManager.tokenToPreference(token),
                        profile.autoInstall
                    )
                    if (!saved) context.toast(context.getString(R.string.patch_profile_save_failed_toast))
                }
            },
            onOpenShizuku = installerManager::openShizukuApp
        )
    }

    settingsDialogProfile?.let { profile ->
        val settingsProfile = profiles.firstOrNull { it.id == profile.id } ?: profile
        val storedApkPath = settingsProfile.apkPath?.takeIf { it.isNotBlank() }
        val hasAvailableApk = remember(storedApkPath) {
            storedApkPath?.let { File(it).exists() } == true
        }
        val hasMissingApk = storedApkPath != null && !hasAvailableApk
        val apkPath = storedApkPath?.takeIf { hasAvailableApk }
        val apkDisplayPath = settingsProfile.apkSourcePath?.takeIf { it.isNotBlank() } ?: storedApkPath
        val effectiveVersion = resolvePatchProfileAppVersion(
            appVersion = settingsProfile.appVersion,
            apkPath = storedApkPath,
            apkVersion = settingsProfile.apkVersion,
            useSelectedApkVersion = settingsProfile.useSelectedApkVersion
        )
        val versionSummary = formatProfileVersionLabel(
            version = effectiveVersion,
            allVersionsLabel = stringResource(R.string.bundle_version_all_versions)
        )
        var autoPatchUpdating by remember(settingsProfile.id) { mutableStateOf(false) }
        var installerUpdating by remember(settingsProfile.id) { mutableStateOf(false) }
        var signatureWorkflowUpdating by remember(settingsProfile.id) { mutableStateOf(false) }
        fun saveSignatureWorkflow(workflow: app.universal.patch.manager.data.room.profile.PatchProfilePayload.SignatureWorkflow) {
            if (signatureWorkflowUpdating) return
            signatureWorkflowUpdating = true
            scope.launch {
                val updated = viewModel.updateProfileSignatureWorkflow(settingsProfile.id, workflow)
                if (!updated) {
                    context.toast(context.getString(R.string.patch_profile_save_failed_toast))
                }
                signatureWorkflowUpdating = false
            }
        }
        val selectedInstaller = settingsProfile.installerToken
            ?.let(installerManager::parseToken)
            ?.let { installerManager.describeEntry(it, InstallerManager.InstallTarget.PATCHER) }
        AlertDialog(
            onDismissRequest = {
                if (apkPickerBusy) return@AlertDialog
                settingsDialogProfile = null
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (apkPickerBusy) return@TextButton
                        settingsDialogProfile = null
                    }
                ) {
                    Text(stringResource(R.string.close))
                }
            },
            title = { CenteredDialogTitle(stringResource(R.string.patch_profile_settings_title, settingsProfile.name)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringResource(R.string.patch_profile_apk_section_title),
                            style = MaterialTheme.typography.titleSmall
                        )
                        val apkScrollState = rememberScrollState()
                        Text(
                            text = apkDisplayPath ?: stringResource(R.string.patch_profile_apk_not_set),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Visible,
                            modifier = Modifier
                                .fillMaxWidth()
                                .consumeHorizontalScroll(apkScrollState)
                        )
                        if (hasMissingApk) {
                            Text(
                                text = stringResource(R.string.patch_profile_apk_missing),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            TextButton(
                                onClick = {
                                    if (apkPickerBusy) return@TextButton
                                    apkPickerProfile = settingsProfile
                                }
                            ) {
                                Text(
                                    text = stringResource(R.string.patch_profile_apk_select),
                                    maxLines = 1
                                )
                            }
                            TextButton(
                                onClick = {
                                    if (apkPickerBusy) return@TextButton
                                    downloadedApkPickerProfile = settingsProfile
                                }
                            ) {
                                Text(
                                    text = stringResource(R.string.downloaded_apps),
                                    maxLines = 1
                                )
                            }
                            if (apkPath != null) {
                                TextButton(
                                    onClick = {
                                        if (apkPickerBusy) return@TextButton
                                        apkPickerBusy = true
                                        scope.launch {
                                            try {
                                                when (viewModel.updateProfileApk(settingsProfile.id, null)) {
                                                    PatchProfilesViewModel.ApkSelectionResult.CLEARED -> context.toast(
                                                        context.getString(
                                                            R.string.patch_profile_apk_cleared_toast,
                                                            settingsProfile.name
                                                        )
                                                    )
                                                    PatchProfilesViewModel.ApkSelectionResult.PROFILE_NOT_FOUND,
                                                    PatchProfilesViewModel.ApkSelectionResult.FAILED,
                                                    PatchProfilesViewModel.ApkSelectionResult.SUCCESS,
                                                    PatchProfilesViewModel.ApkSelectionResult.INVALID_FILE,
                                                    PatchProfilesViewModel.ApkSelectionResult.PACKAGE_MISMATCH -> context.toast(
                                                        context.getString(R.string.patch_profile_apk_failed_toast)
                                                    )
                                                }
                                            } finally {
                                                apkPickerBusy = false
                                            }
                                        }
                                    }
                                ) {
                                    Text(
                                        text = stringResource(R.string.patch_profile_apk_clear),
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                    Divider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.patch_profile_version_override_action),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = versionSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = {
                                if (apkPickerBusy) return@TextButton
                                versionDialogProfile = settingsProfile
                                versionDialogValue = effectiveVersion.orEmpty()
                                versionDialogUseSelectedApkVersion =
                                    settingsProfile.useSelectedApkVersion && hasAvailableApk
                                versionDialogAllVersions =
                                    !versionDialogUseSelectedApkVersion && effectiveVersion.isNullOrBlank()
                                settingsDialogProfile = null
                            }
                        ) {
                            Text(stringResource(R.string.edit))
                        }
                    }
                    Divider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.patch_profile_installer_title),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = selectedInstaller?.label
                                ?: stringResource(R.string.patch_profile_installer_default),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    installerPickerProfile = settingsProfile
                                    settingsDialogProfile = null
                                },
                                enabled = !installerUpdating
                            ) {
                                Text(stringResource(R.string.patch_profile_installer_choose))
                            }
                            if (settingsProfile.installerToken != null) {
                                TextButton(
                                    onClick = {
                                        if (installerUpdating) return@TextButton
                                        installerUpdating = true
                                        scope.launch {
                                            val saved = viewModel.updateProfileInstaller(
                                                settingsProfile.id,
                                                null,
                                                false
                                            )
                                            if (!saved) {
                                                context.toast(context.getString(R.string.patch_profile_save_failed_toast))
                                            }
                                            installerUpdating = false
                                        }
                                    }
                                ) {
                                    Text(stringResource(R.string.clear))
                                }
                            }
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        HapticCheckbox(
                            checked = settingsProfile.autoInstall,
                            enabled = settingsProfile.installerToken != null && !installerUpdating,
                            onCheckedChange = { enabled ->
                                if (installerUpdating) return@HapticCheckbox
                                installerUpdating = true
                                scope.launch {
                                    val updated = viewModel.updateProfileInstaller(
                                        settingsProfile.id,
                                        settingsProfile.installerToken,
                                        enabled
                                    )
                                    if (!updated) {
                                        context.toast(context.getString(R.string.patch_profile_save_failed_toast))
                                    }
                                    installerUpdating = false
                                }
                            }
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(R.string.patch_profile_auto_install_label),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = stringResource(R.string.patch_profile_auto_install_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Divider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        stringResource(R.string.patch_profile_signature_workflow_title),
                        style = MaterialTheme.typography.titleSmall
                    )
                    PatchProfileSignatureWorkflowOption(
                        checked = settingsProfile.signatureWorkflow.enabled,
                        enabled = !signatureWorkflowUpdating,
                        title = stringResource(R.string.patch_profile_signature_workflow_enable),
                        description = stringResource(R.string.patch_profile_signature_workflow_enable_description),
                        onCheckedChange = { enabled ->
                            saveSignatureWorkflow(settingsProfile.signatureWorkflow.copy(enabled = enabled))
                        }
                    )
                    Divider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        HapticCheckbox(
                            checked = settingsProfile.autoPatch,
                            onCheckedChange = { enabled ->
                                if (autoPatchUpdating) return@HapticCheckbox
                                autoPatchUpdating = true
                                scope.launch {
                                    val updated = viewModel.updateProfileAutoPatch(settingsProfile.id, enabled)
                                    if (!updated) {
                                        context.toast(context.getString(R.string.patch_profile_save_failed_toast))
                                    }
                                    autoPatchUpdating = false
                                }
                            }
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(R.string.patch_profile_auto_patch_label),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = stringResource(R.string.patch_profile_auto_patch_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        )
    }

    versionDialogProfile?.let { profile ->
        val versionDialogHasSelectedApk = remember(profile.apkPath, profile.apkVersion) {
            profile.apkVersion?.isNotBlank() == true &&
                profile.apkPath?.let(::File)?.exists() == true
        }
        val currentApkVersion = profile.apkVersion?.takeIf { version -> version.isNotBlank() }
        AlertDialog(
            onDismissRequest = {
                if (versionDialogSaving) return@AlertDialog
                versionDialogProfile = null
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (versionDialogSaving) return@TextButton
                        versionDialogSaving = true
                        scope.launch {
                            val versionToSave =
                                if (versionDialogAllVersions) null else versionDialogValue.trim().takeUnless { it.isBlank() }
                            if (versionToSave == null && !versionDialogAllVersions) {
                                val quoted = "\"${context.getString(R.string.bundle_version_all_versions)}\""
                                context.toast(context.getString(R.string.patch_profile_version_override_set_to_all, quoted))
                            }
                            try {
                                when (
                                    viewModel.updateProfileVersion(
                                        profile.id,
                                        versionToSave,
                                        useSelectedApkVersion = versionDialogUseSelectedApkVersion && !versionDialogAllVersions
                                    )
                                ) {
                                    PatchProfilesViewModel.VersionUpdateResult.SUCCESS -> context.toast(
                                        context.getString(R.string.patch_profile_version_override_saved_toast)
                                    )

                                    PatchProfilesViewModel.VersionUpdateResult.PROFILE_NOT_FOUND -> context.toast(
                                        context.getString(R.string.patch_profile_launch_error)
                                    )

                                    PatchProfilesViewModel.VersionUpdateResult.FAILED -> context.toast(
                                        context.getString(R.string.patch_profile_version_override_failed_toast)
                                    )
                                }
                            } finally {
                                versionDialogSaving = false
                                versionDialogProfile = null
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (versionDialogSaving) return@TextButton
                        versionDialogProfile = null
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
            title = { CenteredDialogTitle(stringResource(R.string.patch_profile_version_override_title, profile.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.patch_profile_version_override_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextField(
                        value = versionDialogValue,
                        onValueChange = {
                            versionDialogValue = it
                            if (versionDialogAllVersions && it.isNotBlank()) {
                                versionDialogAllVersions = false
                            }
                            if (
                                versionDialogUseSelectedApkVersion &&
                                currentApkVersion != null &&
                                it != currentApkVersion
                            ) {
                                versionDialogUseSelectedApkVersion = false
                            }
                        },
                        label = { Text(stringResource(R.string.patch_profile_version_override_label)) },
                        placeholder = { Text(stringResource(R.string.patch_profile_version_override_hint)) },
                        singleLine = true,
                        enabled = !versionDialogAllVersions
                    )
                    if (versionDialogHasSelectedApk && currentApkVersion != null) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            HapticCheckbox(
                                checked = versionDialogUseSelectedApkVersion,
                                onCheckedChange = {
                                    versionDialogUseSelectedApkVersion = it
                                    if (it) {
                                        versionDialogAllVersions = false
                                        versionDialogValue = currentApkVersion
                                    }
                                }
                            )
                            Text(
                                text = stringResource(
                                    R.string.patch_profile_version_override_use_selected_apk,
                                    currentApkVersion
                                )
                            )
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HapticCheckbox(
                            checked = versionDialogAllVersions,
                            onCheckedChange = {
                                versionDialogAllVersions = it
                                if (it) {
                                    versionDialogUseSelectedApkVersion = false
                                }
                            }
                        )
                        Text(text = stringResource(R.string.patch_profile_version_override_all_versions))
                    }
                }
            }
        )
    }

    remoteBundleIncompatibleTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { remoteBundleIncompatibleTarget = null },
            confirmButton = {
                if (allowBundleOverride) {
                    TextButton(
                        onClick = {
                            if (remoteBundleSaving) return@TextButton
                            remoteBundleSaving = true
                            scope.launch {
                                try {
                                    when (
                                        viewModel.replaceRemoteBundle(
                                            target.profileId,
                                            target.bundleUid,
                                            target.targetUid,
                                            emptySet(),
                                            true
                                        )
                                    ) {
                                        PatchProfilesViewModel.ReplaceRemoteBundleResult.SUCCESS -> context.toast(
                                            context.getString(
                                                R.string.patch_profile_bundle_select_remote_success,
                                                target.displayName
                                            )
                                        )
                                        PatchProfilesViewModel.ReplaceRemoteBundleResult.FAILED,
                                        PatchProfilesViewModel.ReplaceRemoteBundleResult.PROFILE_OR_BUNDLE_NOT_FOUND,
                                        PatchProfilesViewModel.ReplaceRemoteBundleResult.TARGET_NOT_FOUND,
                                        PatchProfilesViewModel.ReplaceRemoteBundleResult.INCOMPATIBLE -> context.toast(
                                            context.getString(R.string.patch_profile_bundle_select_remote_error)
                                        )
                                    }
                                } finally {
                                    remoteBundleSaving = false
                                    remoteBundleIncompatibleTarget = null
                                    remoteBundleTarget = null
                                }
                            }
                        }
                    ) {
                        Text(stringResource(R.string.patch_profile_bundle_select_remote_override))
                    }
                } else {
                    TextButton(onClick = { remoteBundleIncompatibleTarget = null }) {
                        Text(stringResource(R.string.ok))
                    }
                }
            },
            dismissButton = {
                if (allowBundleOverride) {
                    TextButton(onClick = { remoteBundleIncompatibleTarget = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            },
            title = { CenteredDialogTitle(stringResource(R.string.patch_profile_bundle_select_remote_incompatible_title)) },
            text = {
                Text(
                    text = stringResource(
                        R.string.patch_profile_bundle_select_remote_incompatible_message,
                        target.displayName
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        )
    }

    blockedProfile?.let {
        AlertDialog(
            onDismissRequest = { blockedProfile = null },
            confirmButton = {
                TextButton(onClick = { blockedProfile = null }) {
                    Text(stringResource(R.string.close))
                }
            },
            title = { CenteredDialogTitle(stringResource(R.string.universal_patches_profile_blocked_title)) },
            text = {
                Text(
                    text = stringResource(
                        R.string.universal_patches_profile_blocked_description,
                        stringResource(R.string.universal_patches_safeguard)
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        )
    }

    if (apkPickerBusy) {
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            )
        ) {
            val view = LocalView.current
            SideEffect {
                val window = (view.parent as DialogWindowProvider).window
                window.setDimAmount(0f)
                window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                LoadingIndicator(
                    modifier = Modifier.size(56.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
                    strokeWidth = 4.dp
                )
            }
        }
    }
}

@Composable
private fun PatchProfilesOrderDialog(
    profiles: List<PatchProfileListItem>,
    onDismissRequest: () -> Unit,
    onConfirm: (List<PatchProfileListItem>) -> Unit
) {
    val workingOrder = remember(profiles) { profiles.toMutableStateList() }
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
        title = { CenteredDialogTitle(text = stringResource(R.string.patch_profiles_reorder_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.patch_profiles_reorder_description),
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
                    itemsIndexed(workingOrder, key = { _, profile -> profile.id }) { index, profile ->
                        val interactionSource = remember { MutableInteractionSource() }
                        ReorderableItem(reorderableState, key = profile.id) { _ ->
                            PatchProfileOrderRow(
                                index = index,
                                profile = profile,
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
private fun ReorderableCollectionItemScope.PatchProfileOrderRow(
    index: Int,
    profile: PatchProfileListItem,
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
                text = profile.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = profile.packageName,
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


@Composable
private fun ProfileActionText(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
private fun ProfileActionPill(
    text: String,
    icon: ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val background = MaterialTheme.colorScheme.surface.copy(alpha = if (enabled) 0.9f else 0.5f)
    val contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.6f)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(background)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
                onLongClick = null
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = text,
                tint = contentColor,
                modifier = Modifier.size(14.dp)
            )
            if (!minimalActionButtons("profiles")) Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ProfileMetaPill(
    text: String,
    modifier: Modifier = Modifier
) {
    val background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
    val contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        tonalElevation = 0.dp,
        color = background
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun PatchProfileApkIcon(
    apkPath: String,
    pm: PM,
    filesystem: Filesystem,
    modifier: Modifier = Modifier
) {
    val containerShape = RoundedCornerShape(12.dp)
    val containerColor = MaterialTheme.colorScheme.surfaceVariant
    val containerBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    val apkFile = remember(apkPath) { File(apkPath) }
    var iconInfo by remember(apkPath) { mutableStateOf<PatchProfileApkIconInfo?>(null) }
    LaunchedEffect(apkPath) {
        iconInfo?.cleanup?.invoke()
        iconInfo = loadPatchProfileApkIconInfo(apkFile, pm, filesystem)
    }
    DisposableEffect(apkPath) {
        onDispose { iconInfo?.cleanup?.invoke() }
    }
    val packageInfo = iconInfo?.packageInfo
    Surface(
        modifier = modifier,
        shape = containerShape,
        tonalElevation = 0.dp,
        color = containerColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, containerBorder)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp),
            contentAlignment = Alignment.Center
        ) {
            if (packageInfo != null) {
                AppIcon(
                    packageInfo = packageInfo,
                    iconOverride = iconInfo?.iconOverride,
                    contentDescription = null,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Android,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PatchProfileSignatureWorkflowOption(
    checked: Boolean,
    enabled: Boolean,
    title: String,
    description: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        HapticCheckbox(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun resolveApkUriDisplayName(context: android.content.Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index != -1 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

private fun isAllowedApkUri(context: android.content.Context, uri: Uri): Boolean {
    return resolveSupportedApkExtension(
        displayName = resolveApkUriDisplayName(context, uri),
        mimeType = context.contentResolver.getType(uri)
    ) != null
}

private fun formatProfileVersionLabel(version: String?, allVersionsLabel: String): String =
    version
        ?.takeIf { it.isNotBlank() }
        ?.let { if (it.startsWith("v", ignoreCase = true)) it else "v$it" }
        ?: allVersionsLabel

private data class PatchProfileApkIconInfo(
    val packageInfo: android.content.pm.PackageInfo?,
    val iconOverride: android.graphics.drawable.Drawable? = null,
    val cleanup: (() -> Unit)?
)

private suspend fun loadPatchProfileApkIconInfo(
    file: File,
    pm: PM,
    filesystem: Filesystem
): PatchProfileApkIconInfo? = withContext(Dispatchers.IO) {
    if (!file.exists()) return@withContext null
    val extension = file.extension.lowercase()
    val isSplitArchive = SplitApkPreparer.isSplitArchive(file)
    if (extension !in APK_FILE_EXTENSIONS && !isSplitArchive) return@withContext null

    if (isSplitArchive) {
        val resolved = runCatching {
            SplitArchiveDisplayResolver.resolve(
                source = file,
                workspace = filesystem.tempDir,
                app = pm.application,
                pm = pm
            )
        }.getOrNull()
        if (resolved != null) {
            return@withContext PatchProfileApkIconInfo(
                packageInfo = resolved.packageInfo,
                iconOverride = resolved.icon,
                cleanup = null
            )
        }
    }

    PatchProfileApkIconInfo(
        packageInfo = pm.getPackageInfo(file),
        iconOverride = null,
        cleanup = null
    )
}

