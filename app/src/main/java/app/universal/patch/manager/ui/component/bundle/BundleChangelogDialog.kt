package app.universal.patch.manager.ui.component.bundle

import app.universal.patch.manager.ui.component.design.CircularProgressIndicator
import app.universal.patch.manager.ui.component.design.Button
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.universal.patch.manager.domain.bundles.RemotePatchBundle
import app.universal.patch.manager.domain.repository.PatchBundleRepository
import app.universal.patch.manager.network.dto.ReVancedAsset
import app.universal.patch.manager.ui.component.ColumnWithScrollbar
import app.universal.patch.manager.ui.component.FullscreenDialog
import app.universal.patch.manager.ui.component.settings.Changelog
import app.universal.patch.manager.util.relativeTime
import app.universal.patch.manager.util.simpleMessage
import app.universal.patch.manager.R
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BundleChangelogDialog(
    src: RemotePatchBundle,
    onDismissRequest: () -> Unit,
) {
    val bundleRepo = koinInject<PatchBundleRepository>()
    var refreshKey by remember { mutableStateOf(0) }
    var state: BundleChangelogState by remember { mutableStateOf(BundleChangelogState.Loading) }

    LaunchedEffect(src, refreshKey) {
        state = BundleChangelogState.Loading
        state = try {
            val result = src.fetchLatestChangelog()
            try {
                bundleRepo.recordChangelog(
                    src, result.asset, hasReleaseBody = result.hasReleaseBody
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A history write failure must not prevent reading downloaded notes.
            }
            BundleChangelogState.Success(result.asset)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (t: Exception) {
            BundleChangelogState.Error(t)
        }
    }

    FullscreenDialog(
        onDismissRequest = onDismissRequest,
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                BundleTopBar(
                    title = stringResource(R.string.bundle_changelog),
                    onBackClick = onDismissRequest,
                    backIcon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                )
            }
        ) { paddingValues ->
            when (val current = state) {
                BundleChangelogState.Loading -> BundleChangelogLoading(paddingValues)
                is BundleChangelogState.Error -> BundleChangelogError(
                    paddingValues = paddingValues,
                    error = current.throwable,
                    onRetry = { refreshKey++ }
                )
                is BundleChangelogState.Success -> BundleChangelogContent(
                    paddingValues = paddingValues,
                    asset = current.asset,
                )
            }
        }
    }
}

@Composable
private fun BundleChangelogLoading(paddingValues: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.changelog_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun BundleChangelogError(
    paddingValues: PaddingValues,
    error: Throwable,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(
                    R.string.bundle_changelog_error,
                    error.simpleMessage().orEmpty()
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Button(onClick = onRetry) {
                Text(stringResource(R.string.bundle_changelog_retry))
            }
        }
    }
}

@Composable
private fun BundleChangelogContent(
    paddingValues: PaddingValues,
    asset: ReVancedAsset
) {
    val context = LocalContext.current
    val publishDate = remember(asset.createdAt) {
        asset.createdAt.relativeTime(context)
    }
    val markdown = remember(asset.description) {
        asset.description
            .replace("\r\n", "\n")
            .sanitizePatchChangelogMarkdown()
    }

    ColumnWithScrollbar(
        modifier = Modifier
            .fillMaxWidth()
            .padding(paddingValues)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Changelog(
                markdown = if (markdown.isBlank()) {
                    stringResource(R.string.bundle_changelog_empty)
                } else markdown,
                version = asset.version,
                publishDate = publishDate
            )
        }
    }
}

private sealed interface BundleChangelogState {
    data object Loading : BundleChangelogState
    data class Success(val asset: ReVancedAsset) : BundleChangelogState
    data class Error(val throwable: Throwable) : BundleChangelogState
}
