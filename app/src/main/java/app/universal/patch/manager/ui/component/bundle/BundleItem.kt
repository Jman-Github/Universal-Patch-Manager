package app.universal.patch.manager.ui.component.bundle

import app.universal.patch.manager.ui.component.minimalActionButtons
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.LaunchedEffect
import app.universal.patch.manager.domain.bundles.BundleIcon
import app.universal.patch.manager.domain.bundles.BundleIconRepository
import app.universal.patch.manager.domain.bundles.RemotePatchBundle
import org.koin.compose.koinInject
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.universal.patch.manager.R
import app.universal.patch.manager.domain.bundles.PatchBundleSource
import app.universal.patch.manager.domain.bundles.PatchBundleChangelogEntry
import app.universal.patch.manager.domain.bundles.PatchBundleSource.Extensions.asRemoteOrNull
import app.universal.patch.manager.data.platform.NetworkInfo
import app.universal.patch.manager.domain.repository.PatchBundleRepository
import app.universal.patch.manager.domain.repository.PatchBundleRepository.DisplayNameUpdateResult
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.ui.component.ConfirmDialog
import app.universal.patch.manager.ui.component.TextInputDialog
import app.universal.patch.manager.domain.bundles.BundleReleasePageRepository
import app.universal.patch.manager.ui.component.bundle.openBundleReleasePage
import app.universal.patch.manager.ui.component.haptics.HapticCheckbox
import app.universal.patch.manager.ui.component.ShimmerBox
import app.universal.patch.manager.ui.model.PatchBundleActionKey
import app.universal.patch.manager.util.consumeHorizontalScroll
import app.universal.patch.manager.util.relativeTime
import app.universal.patch.manager.util.toast
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Brands
import compose.icons.fontawesomeicons.brands.Github
import kotlinx.coroutines.launch

@Composable
fun BundleItem(
    modifier: Modifier = Modifier,
    src: PatchBundleSource,
    patchCount: Int,
    manualUpdateInfo: PatchBundleRepository.ManualBundleUpdateInfo?,
    selectable: Boolean,
    isBundleSelected: Boolean,
    toggleSelection: (Boolean) -> Unit,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onUpdate: () -> Unit,
    onForceUpdate: () -> Unit,
    onDisable: () -> Unit,
) {
    var viewBundleDialogPage by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirmationDialog by rememberSaveable { mutableStateOf(false) }
    var showDisableConfirmationDialog by rememberSaveable { mutableStateOf(false) }
    var showEnableConfirmationDialog by rememberSaveable { mutableStateOf(false) }
    var showForceUpdateDialog by rememberSaveable { mutableStateOf(false) }
    var autoOpenReleaseRequest by rememberSaveable { mutableStateOf<Int?>(null) }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val networkInfo = koinInject<NetworkInfo>()
    val bundleRepo = koinInject<PatchBundleRepository>()
    val prefs = koinInject<PreferencesManager>()
    val coroutineScope = rememberCoroutineScope()
    val releasePages: BundleReleasePageRepository = koinInject()
    var releasePageLoading by remember { mutableStateOf(false) }
    var showRenameDialog by rememberSaveable { mutableStateOf(false) }
    var showBundleChangelog by rememberSaveable { mutableStateOf(false) }
    var showBundleChangelogHistory by rememberSaveable { mutableStateOf(false) }
    var changelogHistoryRefreshKey by remember { mutableStateOf(0) }
    var changelogHistory by remember(src.uid, src.asRemoteOrNull?.endpoint) {
        mutableStateOf<List<PatchBundleChangelogEntry>>(emptyList())
    }
    var changelogHistoryLoading by remember { mutableStateOf(false) }
    var changelogHistoryError by remember { mutableStateOf<Throwable?>(null) }
    val remoteSource = src.asRemoteOrNull
    val historicalChangelogSource = remoteSource?.takeIf { it.supportsHistoricalChangelog }
    val supportsHistoricalChangelog = historicalChangelogSource != null

    if (viewBundleDialogPage) {
        BundleInformationDialog(
            src = src,
            patchCount = patchCount,
            onDismissRequest = {
                viewBundleDialogPage = false
                autoOpenReleaseRequest = null
            },
            onDeleteRequest = { showDeleteConfirmationDialog = true },
            onDisableRequest = {
                if (src.enabled) {
                    showDisableConfirmationDialog = true
                } else {
                    showEnableConfirmationDialog = true
                }
            },
            onUpdate = onUpdate,
            onForceUpdate = onForceUpdate,
            autoOpenReleaseRequest = autoOpenReleaseRequest,
        )
    }

    if (showBundleChangelog) {
        val remote = src.asRemoteOrNull
        if (remote != null) {
            BundleChangelogDialog(
                src = remote,
                onDismissRequest = { showBundleChangelog = false }
            )
        } else {
            showBundleChangelog = false
        }
    }

    LaunchedEffect(supportsHistoricalChangelog) {
        if (!supportsHistoricalChangelog) {
            showBundleChangelogHistory = false
        }
    }

    if (showBundleChangelogHistory && supportsHistoricalChangelog) {
        BundleChangelogHistoryDialog(
            entries = changelogHistory,
            isRefreshing = changelogHistoryLoading,
            error = changelogHistoryError,
            onRetry = { changelogHistoryRefreshKey++ },
            onDismissRequest = { showBundleChangelogHistory = false }
        )
    }

    val bundleTitle = src.displayTitle

    if (showRenameDialog) {
        TextInputDialog(
            initial = src.displayName.orEmpty(),
            title = stringResource(R.string.patches_display_name),
            onDismissRequest = { showRenameDialog = false },
            onConfirm = { value ->
                coroutineScope.launch {
                    val result = bundleRepo.setDisplayName(src.uid, value.trim().ifEmpty { null })
                    when (result) {
                        DisplayNameUpdateResult.SUCCESS, DisplayNameUpdateResult.NO_CHANGE -> {
                            showRenameDialog = false
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

    if (showDeleteConfirmationDialog) {
        ConfirmDialog(
            onDismiss = { showDeleteConfirmationDialog = false },
            onConfirm = {
                showDeleteConfirmationDialog = false
                onDelete()
                viewBundleDialogPage = false
            },
            title = stringResource(R.string.delete),
            description = stringResource(
                R.string.patches_delete_single_dialog_description,
                bundleTitle
            ),
            icon = Icons.Outlined.Delete
        )
    }

    if (showDisableConfirmationDialog) {
        ConfirmDialog(
            onDismiss = { showDisableConfirmationDialog = false },
            onConfirm = {
                showDisableConfirmationDialog = false
                onDisable()
                viewBundleDialogPage = false
            },
            title = stringResource(R.string.disable),
            description = stringResource(
                R.string.patches_disable_single_dialog_description,
                bundleTitle
            ),
            icon = Icons.Outlined.Block
        )
    }

    if (showEnableConfirmationDialog) {
        ConfirmDialog(
            onDismiss = { showEnableConfirmationDialog = false },
            onConfirm = {
                showEnableConfirmationDialog = false
                onDisable()
                viewBundleDialogPage = false
            },
            title = stringResource(R.string.enable),
            description = stringResource(
                R.string.patches_enable_single_dialog_description,
                bundleTitle
            ),
            icon = Icons.Outlined.CheckCircle
        )
    }

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

    val displayVersion = src.version
    val installedSignature = remoteSource?.installedVersionSignature
    val manualUpdateBadge = manualUpdateInfo?.takeIf { info ->
        val latest = info.latestVersion
        val baseline = installedSignature ?: displayVersion
        !latest.isNullOrBlank() && baseline != null && latest != baseline
    }

    LaunchedEffect(
        showBundleChangelogHistory, historicalChangelogSource,
        src.uid, src.updatedAt, changelogHistoryRefreshKey
    ) {
        if (showBundleChangelogHistory && historicalChangelogSource != null) {
            changelogHistoryLoading = true
            changelogHistoryError = null
            try {
                changelogHistory = bundleRepo.getChangelogHistory(src)
                changelogHistory = bundleRepo.synchronizeChangelogHistory(historicalChangelogSource)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                changelogHistoryError = error
            } finally {
                changelogHistoryLoading = false
            }
        }
    }

    val disabledAlpha = 0.38f
    val primaryTextColor = if (src.enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = disabledAlpha)
    }
    val secondaryTextColor = if (src.enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = disabledAlpha)
    }
    val cardShape = RoundedCornerShape(18.dp)
    val cardBase = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
    val headerBase = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp)
    val cardBackground = cardBase.copy(alpha = if (src.enabled) 1f else 0.6f)
    val headerBackground = headerBase.copy(alpha = if (src.enabled) 1f else 0.7f)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(cardShape)
            .combinedClickable(
                onClick = { viewBundleDialogPage = true },
                onLongClick = onSelect,
            ),
        shape = cardShape,
        tonalElevation = 2.dp,
        color = cardBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            val statusIcon = remember(src.state) {
                when (src.state) {
                    is PatchBundleSource.State.Failed -> Icons.Outlined.ErrorOutline to R.string.patches_error
                    is PatchBundleSource.State.Missing -> Icons.Outlined.Warning to R.string.patches_missing
                    is PatchBundleSource.State.Available -> null
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(headerBackground)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (selectable) {
                        HapticCheckbox(
                            checked = isBundleSelected,
                            onCheckedChange = toggleSelection,
                        )
                    }
                    val iconRepository: BundleIconRepository = koinInject()
                    var bundleIcon by remember(src.uid, (src as? RemotePatchBundle)?.endpoint) {
                        mutableStateOf(BundleIcon())
                    }
                    // Update checks can hydrate metadata without changing the endpoint or artifact.
                    LaunchedEffect(src) {
                        bundleIcon = iconRepository.resolve(src)
                    }
                    if (src is RemotePatchBundle) {
                        CatalogIcon(bundleIcon.url, bundleIcon.avatar, src.displayTitle,
                            isOfficial = bundleIcon.url == BundleIconRepository.OFFICIAL_ICON_URL)
                    }
                    val titleScrollState = rememberScrollState()
                    Text(
                        text = src.displayTitle,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        color = primaryTextColor,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .consumeHorizontalScroll(titleScrollState)
                    )
                    statusIcon?.let { (icon, description) ->
                        Icon(
                            icon,
                            contentDescription = stringResource(description),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                val sourceTypeLabel = when {

                    src.asRemoteOrNull != null -> R.string.bundle_type_remote
                    else -> R.string.bundle_type_local
                }
                BundleMetaPill(
                    text = stringResource(sourceTypeLabel),
                    enabled = src.enabled,
                    modifier = Modifier.align(Alignment.TopEnd)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val versionText = src.version?.let {
                    if (it.startsWith("v", ignoreCase = true)) it else "v$it"
                }
                val titleLine = listOfNotNull(src.name.takeIf { it.isNotBlank() }, versionText)
                    .joinToString(" • ")
                if (titleLine.isNotBlank()) {
                    Text(
                        text = titleLine,
                        style = MaterialTheme.typography.bodyMedium,
                        color = primaryTextColor
                    )
                }

                val patchCountText =
                    if (src.state is PatchBundleSource.State.Available) {
                        pluralStringResource(R.plurals.patch_count, patchCount, patchCount)
                    } else null
                patchCountText?.let { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryTextColor
                    )
                }

                val timestampLine = listOfNotNull(
                    src.createdAt?.takeIf { it > 0 }?.relativeTime(context)?.let {
                        stringResource(R.string.bundle_created_at, it)
                    },
                    src.updatedAt?.takeIf { it > 0 }?.relativeTime(context)?.let {
                        stringResource(R.string.bundle_updated_at, it)
                    }
                ).joinToString(" • ")
                if (timestampLine.isNotEmpty()) {
                    Text(
                        text = timestampLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryTextColor
                    )
                }

                manualUpdateBadge?.let { info ->
                    val label = info.latestVersion?.takeUnless { it.isBlank() }?.let { version ->
                        stringResource(R.string.bundle_update_manual_available_with_version, version)
                    } ?: stringResource(R.string.bundle_update_manual_available)
                    BundleMetaPill(text = label, enabled = src.enabled, isAccent = true)
                }

    val showUpdate = manualUpdateBadge != null || src.asRemoteOrNull != null
    val actionScrollState = rememberScrollState()
    val actionOrderPref by prefs.patchBundleActionOrder.getAsState()
    val hiddenActionsPref by prefs.patchBundleHiddenActions.getAsState()
    val orderedActionKeys = remember(actionOrderPref) {
        val parsed = actionOrderPref
            .split(',')
            .mapNotNull { PatchBundleActionKey.fromStorageId(it.trim()) }
        PatchBundleActionKey.ensureComplete(parsed)
    }
    val visibleActionKeys = remember(orderedActionKeys, hiddenActionsPref) {
        orderedActionKeys.filterNot { it.storageId in hiddenActionsPref }
    }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .widthIn(min = maxWidth)
                .consumeHorizontalScroll(actionScrollState),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            visibleActionKeys.forEach { key ->
                when (key) {
                    PatchBundleActionKey.EDIT -> BundleActionPill(
                        text = stringResource(R.string.edit),
                        icon = Icons.Outlined.Edit,
                        enabled = src.enabled,
                        onClick = { showRenameDialog = true }
                    )
                    PatchBundleActionKey.REFRESH -> if (showUpdate) {
                        BundleActionPill(
                            text = stringResource(R.string.refresh),
                            icon = Icons.Outlined.Update,
                            enabled = src.enabled,
                            onClick = onUpdate,
                            onLongClick = { showForceUpdateDialog = true }
                        )
                    }
                    PatchBundleActionKey.RELEASE_PAGE -> BundleActionPill(
                        text = stringResource(R.string.bundle_release_page),
                        icon = FontAwesomeIcons.Brands.Github,
                        enabled = src.enabled && !releasePageLoading,
                        onClick = {
                            coroutineScope.launch {
                                if (releasePageLoading) return@launch
                                releasePageLoading = true
                                try {
                                    openBundleReleasePage(src, releasePages, networkInfo, context, uriHandler)
                                } finally {
                                    releasePageLoading = false
                                }
                            }
                        }
                    )
                    PatchBundleActionKey.CHANGELOG_LATEST -> if (remoteSource != null) {
                        BundleActionPill(
                            text = stringResource(R.string.bundle_latest_changelog),
                            icon = Icons.Outlined.Description,
                            enabled = src.enabled,
                            onClick = { showBundleChangelog = true }
                        )
                    }
                    PatchBundleActionKey.CHANGELOG_HISTORY -> if (supportsHistoricalChangelog) {
                        BundleActionPill(
                            text = stringResource(R.string.bundle_previous_changelogs),
                            icon = Icons.Outlined.History,
                            enabled = src.enabled,
                            onClick = { showBundleChangelogHistory = true }
                        )
                    }
                    PatchBundleActionKey.TOGGLE -> {
                        val toggleIcon = if (src.enabled) Icons.Outlined.Block else Icons.Outlined.CheckCircle
                        val toggleLabel = if (src.enabled) R.string.disable else R.string.enable
                        BundleActionPill(
                            text = stringResource(toggleLabel),
                            icon = toggleIcon,
                            enabled = true,
                            onClick = {
                                if (src.enabled) {
                                    showDisableConfirmationDialog = true
                                } else {
                                    showEnableConfirmationDialog = true
                                }
                            }
                        )
                    }
                    PatchBundleActionKey.DELETE -> BundleActionPill(
                        text = stringResource(R.string.delete),
                        icon = Icons.Outlined.Delete,
                        enabled = true,
                        onClick = { showDeleteConfirmationDialog = true }
                    )
                }
            }
        }
    }
            }
        }
    }
}

@Composable
private fun BundleActionPill(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    val interactionSource = remember { MutableInteractionSource() }
    val background = MaterialTheme.colorScheme.surface.copy(alpha = if (enabled) 0.9f else 0.5f)
    val contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.6f)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(background)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = text,
                tint = contentColor,
                modifier = Modifier.size(14.dp)
            )
            if (!minimalActionButtons("bundles")) Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                maxLines = 1
            )
        }
    }
}

@Composable
fun BundleItemPlaceholder(
    modifier: Modifier = Modifier
) {
    val cardShape = RoundedCornerShape(18.dp)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(cardShape),
        shape = cardShape,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                ShimmerBox(modifier = Modifier.width(180.dp).height(18.dp))
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ShimmerBox(modifier = Modifier.width(140.dp).height(14.dp))
                ShimmerBox(modifier = Modifier.width(90.dp).height(12.dp))
                ShimmerBox(modifier = Modifier.width(200.dp).height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShimmerBox(modifier = Modifier.width(64.dp).height(22.dp))
                    ShimmerBox(modifier = Modifier.width(64.dp).height(22.dp))
                    ShimmerBox(modifier = Modifier.width(64.dp).height(22.dp))
                }
            }
        }
    }
}

@Composable
private fun BundleMetaPill(
    text: String,
    enabled: Boolean,
    isAccent: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val background = if (isAccent) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (isAccent) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val adjustedBackground = if (enabled) background else background.copy(alpha = 0.6f)
    val adjustedContent = if (enabled) content else content.copy(alpha = 0.6f)

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = adjustedBackground,
        contentColor = adjustedContent
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            maxLines = 1
        )
    }
}

