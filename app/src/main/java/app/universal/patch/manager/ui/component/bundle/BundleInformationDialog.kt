package app.universal.patch.manager.ui.component.bundle

import android.content.ClipData
import android.content.ClipboardManager
import android.webkit.URLUtil.isValidUrl
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowRight
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Commit
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.universal.patch.manager.R
import app.universal.patch.manager.R.string.auto_update
import app.universal.patch.manager.R.string.auto_update_description
import app.universal.patch.manager.R.string.field_not_set
import app.universal.patch.manager.R.string.patches
import app.universal.patch.manager.R.string.patches_url
import app.universal.patch.manager.R.string.view_patches
import app.universal.patch.manager.patcher.patch.PatchBundleType
import app.universal.patch.manager.data.platform.NetworkInfo
import app.universal.patch.manager.domain.bundles.LocalPatchBundle
import app.universal.patch.manager.domain.bundles.JsonPatchBundle
import app.universal.patch.manager.domain.bundles.PatchBundleChangelogEntry
import app.universal.patch.manager.domain.bundles.PatchBundleSource
import app.universal.patch.manager.domain.bundles.PatchBundleSource.Extensions.asRemoteOrNull
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.domain.repository.PatchBundleRepository
import app.universal.patch.manager.domain.repository.PatchBundleRepository.DisplayNameUpdateResult
import app.universal.patch.manager.ui.component.ColumnWithScrollbar
import app.universal.patch.manager.ui.component.ConfirmDialog
import app.universal.patch.manager.ui.component.ExceptionViewerDialog
import app.universal.patch.manager.ui.component.FullscreenDialog
import app.universal.patch.manager.ui.component.TextInputDialog
import app.universal.patch.manager.domain.bundles.BundleReleasePageRepository
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsSwitch
import app.universal.patch.manager.util.simpleMessage
import app.universal.patch.manager.util.toast
import kotlinx.coroutines.launch
import androidx.core.content.getSystemService
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Brands
import compose.icons.fontawesomeicons.brands.Github
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BundleInformationDialog(
    src: PatchBundleSource,
    patchCount: Int,
    onDismissRequest: () -> Unit,
    onDeleteRequest: () -> Unit,
    onDisableRequest: () -> Unit,
    onUpdate: () -> Unit,
    onForceUpdate: () -> Unit,
    autoOpenReleaseRequest: Int? = null,
) {
    val bundleRepo = koinInject<PatchBundleRepository>()
    val networkInfo = koinInject<NetworkInfo>()
    val prefs = koinInject<PreferencesManager>()
    val hasNetwork = remember { networkInfo.isConnected() }
    val composableScope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService<ClipboardManager>() }
    val uriHandler = LocalUriHandler.current
    var viewCurrentBundlePatches by remember { mutableStateOf(false) }
    var viewBundleChangelog by remember { mutableStateOf(false) }
    var viewBundleChangelogHistory by remember { mutableStateOf(false) }
    var showForceUpdateDialog by rememberSaveable { mutableStateOf(false) }
    var showVersionDialog by rememberSaveable(src.uid) { mutableStateOf(false) }
    var changelogHistory by remember { mutableStateOf<List<PatchBundleChangelogEntry>>(emptyList()) }
    var changelogHistoryFetchedOnce by remember { mutableStateOf(false) }
    var changelogHistoryLoading by remember { mutableStateOf(false) }
    var changelogHistoryError by remember { mutableStateOf<Throwable?>(null) }
    val remoteSource = src.asRemoteOrNull
    val historicalChangelogSource = remoteSource?.takeIf { it.supportsHistoricalChangelog }
    val supportsHistoricalChangelog = historicalChangelogSource != null
    val isLocal = src is LocalPatchBundle
    val bundleManifestAttributes = src.patchBundle?.manifestAttributes
    val manifestSource = bundleManifestAttributes?.source
    val bundleInfo by bundleRepo.allBundlesInfoFlow.collectAsStateWithLifecycle(emptyMap())
    val (autoUpdate, endpoint, searchUpdate) = remoteSource?.let {
        Triple(it.autoUpdate, it.endpoint, it.searchUpdate)
    } ?: Triple(null, null, null)
    val bundleTitle = src.displayTitle

    if (showForceUpdateDialog) {
        ConfirmDialog(
            onDismiss = { showForceUpdateDialog = false },
            onConfirm = {
                showForceUpdateDialog = false
                onForceUpdate()
            },
            title = stringResource(R.string.patch_bundle_force_redownload_title),
            description = stringResource(
                R.string.patch_bundle_force_redownload_description,
                bundleTitle
            ),
            icon = Icons.Outlined.Update
        )
    }
    var showDisplayNameDialog by remember { mutableStateOf(false) }
    val releasePages: BundleReleasePageRepository = koinInject()
    var releasePageLoading by remember { mutableStateOf(false) }

    fun onAutoUpdateChange(new: Boolean) = composableScope.launch {
        with(bundleRepo) {
            src.asRemoteOrNull?.setAutoUpdate(new)
        }
    }

    fun onSearchUpdateChange(new: Boolean) = composableScope.launch {
        with(bundleRepo) {
            src.asRemoteOrNull?.setSearchUpdate(new)
        }
    }

    fun onRepositoryPrereleasesChange(new: Boolean) = composableScope.launch {
        val updatedSource = with(bundleRepo) {
            (src as? JsonPatchBundle)?.setUsePrereleases(new)
        } ?: return@launch
        bundleRepo.update(updatedSource, showToast = true)
    }

    fun onRepositoryLatestChange(new: Boolean) = composableScope.launch {
        val updatedSource = with(bundleRepo) {
            (src as? JsonPatchBundle)?.setUseLatest(new)
        } ?: return@launch
        bundleRepo.update(updatedSource, showToast = true)
    }

    fun onVersionSelectionChange(enabled: Boolean) = composableScope.launch {
        val updated = with(bundleRepo) {
            (src as? JsonPatchBundle)?.setVersionSelection(enabled)
        } ?: return@launch
        if (enabled && updated.selectedVersion == null) {
            showVersionDialog = true
        } else {
            bundleRepo.update(updated, showToast = true)
        }
    }

    if (showVersionDialog && src is JsonPatchBundle && src.useSpecificVersion) {
        BundleVersionDialog(
            source = src,
            onDismiss = { showVersionDialog = false },
            onSelect = { asset ->
                showVersionDialog = false
                composableScope.launch {
                    val updated = with(bundleRepo) { src.setVersionSelection(true, asset) }
                    bundleRepo.update(updated, showToast = true)
                }
            }
        )
    }

    fun openReleasePage() = composableScope.launch {
        if (releasePageLoading) return@launch
        releasePageLoading = true
        try {
            openBundleReleasePage(src, releasePages, networkInfo, context, uriHandler)
        } finally {
            releasePageLoading = false
        }
    }

    fun refreshChangelogHistory() {
        changelogHistoryError = null
        composableScope.launch {
            changelogHistoryLoading = true
            changelogHistory = bundleRepo.getChangelogHistory(src)
            try {
                changelogHistory = if (historicalChangelogSource != null) {
                    bundleRepo.synchronizeChangelogHistory(historicalChangelogSource)
                } else {
                    bundleRepo.getChangelogHistory(src)
                }
                changelogHistoryFetchedOnce = supportsHistoricalChangelog
            } catch (t: Throwable) {
                changelogHistoryError = t
            } finally {
                changelogHistoryLoading = false
            }
        }
    }

    LaunchedEffect(autoOpenReleaseRequest) {
        autoOpenReleaseRequest?.let {
            openBundleReleasePage(src, releasePages, networkInfo, context, uriHandler)
            onDismissRequest()
        }
    }

    LaunchedEffect(src.uid, src.updatedAt) {
        changelogHistory = bundleRepo.getChangelogHistory(src)
        changelogHistoryFetchedOnce = changelogHistory.size > 1
    }

    if (viewCurrentBundlePatches) {
        BundlePatchesDialog(
            src = src,
            onDismissRequest = {
                viewCurrentBundlePatches = false
            }
        )
    }
    if (viewBundleChangelog) {
        val remote = remoteSource
        if (remote != null) {
            BundleChangelogDialog(
                src = remote,
                onDismissRequest = {
                    viewBundleChangelog = false
                }
            )
        } else {
            viewBundleChangelog = false
        }
    }

    LaunchedEffect(supportsHistoricalChangelog) {
        if (!supportsHistoricalChangelog) {
            viewBundleChangelogHistory = false
        }
    }

    LaunchedEffect(viewBundleChangelogHistory, supportsHistoricalChangelog, src.uid, src.updatedAt) {
        if (viewBundleChangelogHistory && supportsHistoricalChangelog) {
            refreshChangelogHistory()
        }
    }

    if (viewBundleChangelogHistory && supportsHistoricalChangelog) {
        BundleChangelogHistoryDialog(
            entries = changelogHistory.drop(1),
            isRefreshing = changelogHistoryLoading,
            error = changelogHistoryError,
            onRetry = { refreshChangelogHistory() },
            onDismissRequest = { viewBundleChangelogHistory = false }
        )
    }

    FullscreenDialog(
        onDismissRequest = onDismissRequest,
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                BundleTopBar(
                    title = src.displayTitle,
                    onBackClick = onDismissRequest,
                    backIcon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    },
                    actions = {
                        val releaseAvailable = !isLocal || !manifestSource.isNullOrBlank()
                        val githubButtonEnabled = releaseAvailable && !releasePageLoading
                        IconButton(
                            onClick = { openReleasePage() },
                            enabled = githubButtonEnabled
                        ) {
                            Icon(
                                imageVector = FontAwesomeIcons.Brands.Github,
                                contentDescription = stringResource(R.string.bundle_release_page),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        val toggleIcon = if (src.enabled) Icons.Outlined.Block else Icons.Outlined.CheckCircle
                        val toggleLabel = if (src.enabled) R.string.disable else R.string.enable
                        IconButton(onClick = onDisableRequest) {
                            Icon(
                                toggleIcon,
                                stringResource(toggleLabel)
                            )
                        }
                        IconButton(onClick = onDeleteRequest) {
                            Icon(
                                Icons.Outlined.DeleteOutline,
                                stringResource(R.string.delete)
                            )
                        }
                        if (!isLocal && hasNetwork) {
                            ActionIconButton(
                                onClick = onUpdate,
                                onLongClick = { showForceUpdateDialog = true }
                            ) {
                                Icon(
                                    Icons.Outlined.Update,
                                    stringResource(R.string.refresh)
                                )
                            }
                        }
                    }
                )
            },
        ) { paddingValues ->
            ColumnWithScrollbar(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(paddingValues),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Tag(Icons.Outlined.Sell, src.name)
                    bundleManifestAttributes?.description?.let {
                        Tag(Icons.Outlined.Description, it)
                    }
                    bundleManifestAttributes?.source?.let {
                        Tag(Icons.Outlined.Commit, it)
                    }
                    bundleManifestAttributes?.author?.let {
                        Tag(Icons.Outlined.Person, it)
                    }
                    bundleManifestAttributes?.contact?.let {
                        Tag(Icons.AutoMirrored.Outlined.Send, it)
                    }
                    bundleManifestAttributes?.website?.let {
                        Tag(Icons.Outlined.Language, it, isUrl = true)
                    }
                    bundleManifestAttributes?.license?.let {
                        Tag(Icons.Outlined.Gavel, it)
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                BundleListItem(
                    headlineText = stringResource(R.string.bundle_type_label),
                    supportingText = when (bundleInfo[src.uid]?.bundleType) {
                        PatchBundleType.REVANCED -> stringResource(R.string.bundle_type_revanced)
                        PatchBundleType.MORPHE -> stringResource(R.string.bundle_type_morphe)
                        PatchBundleType.RESEAM -> stringResource(R.string.bundle_type_reseam)
                        PatchBundleType.AMPLE -> stringResource(R.string.bundle_type_ample)
                        null -> stringResource(field_not_set)
                    }
                )

                if (showDisplayNameDialog) {
                    TextInputDialog(
                        initial = src.displayName.orEmpty(),
                        title = stringResource(R.string.patches_display_name),
                        onDismissRequest = { showDisplayNameDialog = false },
                        onConfirm = { value ->
                            composableScope.launch {
                                val result = bundleRepo.setDisplayName(src.uid, value.trim().ifEmpty { null })
                                when (result) {
                                    DisplayNameUpdateResult.SUCCESS, DisplayNameUpdateResult.NO_CHANGE -> {
                                        showDisplayNameDialog = false
                                    }
                                    DisplayNameUpdateResult.DUPLICATE -> {
                                        context.toast(context.getString(R.string.patch_bundle_duplicate_name_error))
                                    }
                                    DisplayNameUpdateResult.NOT_FOUND -> {
                                        context.toast(context.getString(R.string.patch_bundle_missing_error))
                                    }
                                }
                            }
                        },
                        validator = { true }
                    )
                }

                BundleListItem(
                    modifier = Modifier.clickable { showDisplayNameDialog = true },
                    headlineText = stringResource(R.string.patches_display_name),
                    supportingText = src.displayName?.takeUnless { it.isBlank() }
                        ?: stringResource(field_not_set)
                )

                if (isLocal) {
                    val identifierValue = src.uid.toString()
                    BundleListItem(
                        headlineText = stringResource(R.string.bundle_uid),
                        supportingText = identifierValue,
                        trailingContent = {
                            IconButton(
                                onClick = {
                                    clipboard?.setPrimaryClip(
                                        ClipData.newPlainText(
                                            context.getString(R.string.bundle_uid),
                                            identifierValue
                                        )
                                    )
                                    context.toast(context.getString(R.string.toast_copied_to_clipboard))
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.ContentCopy,
                                    contentDescription = stringResource(R.string.copy_to_clipboard)
                                )
                            }
                        }
                    )
                }

                if (autoUpdate != null) {
                    BundleListItem(
                        headlineText = stringResource(auto_update),
                        supportingText = stringResource(auto_update_description),
                        trailingContent = {
                            ExpressiveSettingsSwitch(
                                checked = autoUpdate,
                                onCheckedChange = ::onAutoUpdateChange
                            )
                        },
                        modifier = Modifier.clickable {
                            onAutoUpdateChange(!autoUpdate)
                        }
                    )
                }
                if (searchUpdate != null) {
                    BundleListItem(
                        headlineText = stringResource(R.string.bundle_search_update),
                        supportingText = stringResource(R.string.bundle_search_update_description),
                        trailingContent = {
                            ExpressiveSettingsSwitch(
                                checked = searchUpdate,
                                onCheckedChange = ::onSearchUpdateChange
                            )
                        },
                        modifier = Modifier.clickable {
                            onSearchUpdateChange(!searchUpdate)
                        }
                    )
                }

                remoteSource?.let { DiscoveryBundleReleaseSettings(it) }

                (src as? JsonPatchBundle)?.takeIf { it.supportsPrereleases }?.let { repositoryBundle ->
                    BundleListItem(
                        headlineText = stringResource(R.string.repository_bundle_prereleases),
                        supportingText = stringResource(R.string.repository_bundle_prereleases_description),
                        trailingContent = {
                            ExpressiveSettingsSwitch(
                                checked = repositoryBundle.usePrereleases && !repositoryBundle.useLatest && !repositoryBundle.useSpecificVersion,
                                enabled = !repositoryBundle.useSpecificVersion && !repositoryBundle.useLatest,
                                onCheckedChange = ::onRepositoryPrereleasesChange
                            )
                        },
                        modifier = Modifier
                            .alpha(if (repositoryBundle.useSpecificVersion || repositoryBundle.useLatest) 0.38f else 1f)
                            .clickable(enabled = !repositoryBundle.useSpecificVersion && !repositoryBundle.useLatest) {
                                onRepositoryPrereleasesChange(!repositoryBundle.usePrereleases)
                            }
                    )

                    BundleListItem(
                        headlineText = stringResource(R.string.repository_bundle_latest),
                        supportingText = stringResource(R.string.repository_bundle_latest_description),
                        trailingContent = {
                            ExpressiveSettingsSwitch(
                                checked = repositoryBundle.useLatest && !repositoryBundle.useSpecificVersion,
                                enabled = !repositoryBundle.useSpecificVersion,
                                onCheckedChange = ::onRepositoryLatestChange
                            )
                        },
                        modifier = Modifier
                            .alpha(if (repositoryBundle.useSpecificVersion) 0.38f else 1f)
                            .clickable(enabled = !repositoryBundle.useSpecificVersion) {
                                onRepositoryLatestChange(!repositoryBundle.useLatest)
                            }
                    )

                    BundleListItem(
                        headlineText = stringResource(R.string.bundle_use_specific_version),
                        supportingText = stringResource(R.string.bundle_use_specific_version_description),
                        trailingContent = {
                            ExpressiveSettingsSwitch(
                                checked = repositoryBundle.useSpecificVersion,
                                onCheckedChange = ::onVersionSelectionChange
                            )
                        },
                        modifier = Modifier.clickable {
                            onVersionSelectionChange(!repositoryBundle.useSpecificVersion)
                        }
                    )
                    BundleListItem(
                        headlineText = stringResource(R.string.bundle_select_version),
                        supportingText = repositoryBundle.selectedVersion?.version
                            ?: stringResource(R.string.field_not_set),
                        trailingContent = {
                            Icon(Icons.AutoMirrored.Outlined.ArrowRight, contentDescription = null)
                        },
                        modifier = Modifier
                            .alpha(if (repositoryBundle.useSpecificVersion) 1f else 0.38f)
                            .clickable(enabled = repositoryBundle.useSpecificVersion) {
                                showVersionDialog = true
                            }
                    )
                }

                endpoint?.let { url ->
                    val remote = src.asRemoteOrNull
                    var showUrlInputDialog by rememberSaveable {
                        mutableStateOf(false)
                    }
                    if (showUrlInputDialog) {
                        TextInputDialog(
                            initial = url,
                            title = stringResource(patches_url),
                            onDismissRequest = { showUrlInputDialog = false },
                            singleLine = false,
                            minLines = 2,
                            maxLines = 4,
                            onConfirm = { newUrl ->
                                if (remote == null) {
                                    showUrlInputDialog = false
                                    return@TextInputDialog
                                }
                                val trimmed = newUrl.trim()
                                if (trimmed.isEmpty() || trimmed == url) {
                                    showUrlInputDialog = false
                                    return@TextInputDialog
                                }
                                showUrlInputDialog = false
                                composableScope.launch {
                                    bundleRepo.updateRemoteEndpoint(
                                        src = remote,
                                        newUrl = trimmed
                                    )
                                }
                            },
                            validator = {
                                if (it.isEmpty()) return@TextInputDialog false

                                isValidUrl(it)
                            }
                        )
                    }

                    BundleListItem(
                        headlineText = stringResource(patches_url),
                        supportingText = url.ifEmpty {
                            stringResource(field_not_set)
                        },
                        trailingContent = {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                IconButton(
                                    onClick = {
                                        clipboard?.setPrimaryClip(
                                            ClipData.newPlainText(
                                                context.getString(patches_url),
                                                url
                                            )
                                        )
                                        context.toast(context.getString(R.string.patch_bundle_url_copied))
                                    },
                                    enabled = url.isNotEmpty()
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.ContentCopy,
                                        contentDescription = stringResource(R.string.copy_to_clipboard)
                                    )
                                }
                                IconButton(
                                    onClick = { showUrlInputDialog = true }
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Edit,
                                        contentDescription = stringResource(R.string.edit)
                                    )
                                }
                            }
                        }
                    )
                }

                val patchesClickable = patchCount > 0
                BundleListItem(
                    headlineText = stringResource(patches),
                    supportingText = stringResource(view_patches),
                    modifier = Modifier.clickable(
                        enabled = patchesClickable,
                        onClick = {
                            viewCurrentBundlePatches = true
                        }
                    )
                ) {
                    if (patchesClickable) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowRight,
                            stringResource(patches)
                        )
                    }
                }

                src.asRemoteOrNull?.let {
                    BundleListItem(
                        headlineText = stringResource(R.string.bundle_latest_changelog),
                        supportingText = stringResource(R.string.bundle_view_changelog),
                        modifier = Modifier.clickable {
                            viewBundleChangelog = true
                        }
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowRight,
                            stringResource(R.string.bundle_latest_changelog)
                        )
                    }

                    if (supportsHistoricalChangelog) {
                        val previousEntries = changelogHistory.drop(1)
                        val hasPrevious = previousEntries.isNotEmpty()
                        val previousSupportingText = when {
                            changelogHistoryLoading -> stringResource(R.string.changelog_loading)
                            hasPrevious -> stringResource(R.string.bundle_view_previous_changelogs)
                            changelogHistoryFetchedOnce && changelogHistoryError == null ->
                                stringResource(R.string.bundle_previous_changelogs_empty)
                            else -> stringResource(R.string.bundle_view_previous_changelogs)
                        }
                        BundleListItem(
                            headlineText = stringResource(R.string.bundle_previous_changelogs),
                            supportingText = previousSupportingText,
                            modifier = Modifier.clickable {
                                viewBundleChangelogHistory = true
                            }
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowRight,
                                stringResource(R.string.bundle_previous_changelogs)
                            )
                        }
                    }
                }

                src.error?.let {
                    var showDialog by rememberSaveable {
                        mutableStateOf(false)
                    }
                    if (showDialog) ExceptionViewerDialog(
                        onDismiss = { showDialog = false },
                        text = remember(it) { it.stackTraceToString() }
                    )

                    BundleListItem(
                        headlineText = stringResource(R.string.patches_error),
                        supportingText = stringResource(R.string.patches_error_description),
                        trailingContent = {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowRight,
                                null
                            )
                        },
                        modifier = Modifier.clickable { showDialog = true }
                    )
                }
                if (src.state is PatchBundleSource.State.Missing && !isLocal) {
                    BundleListItem(
                        headlineText = stringResource(R.string.patches_error),
                        supportingText = stringResource(R.string.patches_not_downloaded),
                        modifier = Modifier.clickable(onClick = onUpdate)
                    )
                }
            }
        }
    }
}

@Composable
private fun Tag(
    icon: ImageVector,
    text: String,
    isUrl: Boolean = false
) {
    val uriHandler = LocalUriHandler.current

    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = if (isUrl) {
            Modifier
                .clickable {
                    try {
                        uriHandler.openUri(text)
                    } catch (_: Exception) {
                    }
                }
        } else
            Modifier,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isUrl) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun ActionIconButton(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(RoundedCornerShape(999.dp))
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(6.dp),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
