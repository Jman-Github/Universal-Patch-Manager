package app.universal.patch.manager.ui.screen

import app.universal.patch.manager.ui.component.rememberAppBarScrollBehavior
import app.universal.patch.manager.ui.component.design.TextButton
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.universal.patch.manager.R
import app.universal.patch.manager.network.dto.ReVancedAsset
import app.universal.patch.manager.ui.component.AppTopBar
import app.universal.patch.manager.ui.component.DownloadProgressBanner
import app.universal.patch.manager.ui.component.Markdown
import app.universal.patch.manager.ui.component.ManagerRebrandNoticeDialog
import app.universal.patch.manager.ui.component.haptics.HapticExtendedFloatingActionButton
import app.universal.patch.manager.ui.viewmodel.UpdateViewModel
import app.universal.patch.manager.ui.viewmodel.UpdateViewModel.State
import app.universal.patch.manager.util.relativeTime
import org.koin.androidx.compose.koinViewModel
import app.universal.patch.manager.ui.component.CenteredDialogTitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Stable
fun UpdateScreen(
    onBackClick: () -> Unit,
    vm: UpdateViewModel = koinViewModel()
) {
    val scrollBehavior = rememberAppBarScrollBehavior()

    if (vm.showRebrandNotice) {
        ManagerRebrandNoticeDialog(
            secondsRemaining = vm.rebrandNoticeSeconds,
            onContinue = vm::confirmRebrandNotice,
            onCancel = vm::dismissRebrandNotice
        )
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(vm.state.title),
                scrollBehavior = scrollBehavior,
                onBackClick = onBackClick
            )
        },
        floatingActionButton = {
            val buttonConfig = when (vm.state) {
                State.CAN_DOWNLOAD -> Triple(
                    { vm.downloadUpdate() },
                    if (vm.canResumeDownload) R.string.resume_download else R.string.download,
                    Icons.Outlined.InstallMobile
                )

                State.DOWNLOADING -> Triple(onBackClick, R.string.cancel, Icons.Outlined.Cancel)
                State.CAN_INSTALL -> Triple(
                    { vm.installUpdate() },
                    R.string.install_app,
                    Icons.Outlined.InstallMobile
                )

                else -> null
            }

            buttonConfig?.let { (onClick, textRes, icon) ->
                HapticExtendedFloatingActionButton(
                    onClick = onClick::invoke,
                    icon = { Icon(icon, null) },
                    text = { Text(stringResource(textRes)) }
                )
            }

        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues),
        ) {
            AnimatedVisibility(
                visible = vm.state == State.DOWNLOADING,
                enter = fadeIn(animationSpec = spring(stiffness = 400f)) +
                    expandVertically(
                        expandFrom = Alignment.Top,
                        animationSpec = spring(stiffness = 400f)
                    ),
                exit = fadeOut(animationSpec = spring(stiffness = 400f)) +
                    shrinkVertically(
                        shrinkTowards = Alignment.Top,
                        animationSpec = spring(stiffness = 400f)
                    )
            ) {
                val progressLabel = stringResource(
                    R.string.manager_update_progress_detail,
                    formatMegabytes(vm.downloadedSize),
                    formatMegabytes(vm.totalSize),
                    (vm.downloadProgress * 100).toInt()
                )
                DownloadProgressBanner(
                    title = stringResource(R.string.manager_update_banner_title),
                    subtitle = progressLabel,
                    progress = vm.downloadProgress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            AnimatedVisibility(visible = vm.showInternetCheckDialog) {
                MeteredDownloadConfirmationDialog(
                    onDismiss = { vm.showInternetCheckDialog = false },
                    onDownloadAnyways = { vm.downloadUpdate(true) }
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(32.dp)
            ) {
                vm.releaseInfo?.let { info ->
                    UpdateInfoSummary(info)
                }
            }
        }
    }
}

@Composable
private fun MeteredDownloadConfirmationDialog(
    onDismiss: () -> Unit,
    onDownloadAnyways: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        dismissButton = {
            TextButton(onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onDownloadAnyways()
                }
            ) {
                Text(stringResource(R.string.download))
            }
        },
        title = { CenteredDialogTitle(stringResource(R.string.download_update_confirmation)) },
        icon = { Icon(Icons.Outlined.Update, null) },
        text = { Text(stringResource(R.string.download_confirmation_metered)) }
    )
}

@Composable
private fun UpdateInfoSummary(
    releaseInfo: ReVancedAsset
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val published = remember(releaseInfo.createdAt) {
        releaseInfo.createdAt.relativeTime(context)
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.version) + " " + releaseInfo.version,
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            text = stringResource(R.string.update_published, published),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (releaseInfo.description.isNotBlank()) {
            Markdown(
                releaseInfo.description.replace("`", ""),
                textStyle = MaterialTheme.typography.bodySmall,
                compactHeadings = true
            )
        }

        releaseInfo.pageUrl?.let { url ->
            TextButton(onClick = { uriHandler.openUri(url) }) {
                Text(stringResource(R.string.changelog_open_github))
            }
        }
    }
}

private fun formatMegabytes(bytes: Long): Float =
    if (bytes <= 0) 0f else bytes / 1_000_000f
