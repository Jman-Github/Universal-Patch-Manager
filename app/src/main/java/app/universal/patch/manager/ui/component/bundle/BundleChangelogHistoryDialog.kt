package app.universal.patch.manager.ui.component.bundle

import app.universal.patch.manager.ui.component.design.CircularProgressIndicator
import app.universal.patch.manager.ui.component.design.Button
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.universal.patch.manager.domain.bundles.PatchBundleChangelogEntry
import app.universal.patch.manager.ui.component.FullscreenDialog
import app.universal.patch.manager.ui.component.settings.Changelog
import app.universal.patch.manager.util.relativeTime
import app.universal.patch.manager.util.simpleMessage
import app.universal.patch.manager.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BundleChangelogHistoryDialog(
    entries: List<PatchBundleChangelogEntry>,
    isRefreshing: Boolean,
    error: Throwable?,
    onRetry: (() -> Unit)? = null,
    onDismissRequest: () -> Unit,
) {
    FullscreenDialog(
        onDismissRequest = onDismissRequest,
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                BundleTopBar(
                    title = stringResource(R.string.bundle_previous_changelogs),
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
            when {
                entries.isNotEmpty() -> BundleChangelogHistoryContent(
                    paddingValues = paddingValues,
                    entries = entries,
                    isRefreshing = isRefreshing,
                    error = error,
                    onRetry = onRetry
                )
                isRefreshing -> BundleChangelogHistoryLoading(paddingValues)
                error != null -> BundleChangelogHistoryError(
                    paddingValues = paddingValues,
                    error = error,
                    onRetry = onRetry
                )
                else -> BundleChangelogHistoryEmpty(paddingValues)
            }
        }
    }
}

@Composable
private fun BundleChangelogHistoryLoading(paddingValues: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
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

@Composable
private fun BundleChangelogHistoryError(
    paddingValues: PaddingValues,
    error: Throwable,
    onRetry: (() -> Unit)?
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(
                R.string.bundle_changelog_error,
                error.simpleMessage().orEmpty()
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (onRetry != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onRetry) {
                Text(stringResource(R.string.bundle_changelog_retry))
            }
        }
    }
}

@Composable
private fun BundleChangelogHistoryEmpty(paddingValues: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.bundle_previous_changelogs_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
private fun BundleChangelogHistoryContent(
    paddingValues: PaddingValues,
    entries: List<PatchBundleChangelogEntry>,
    isRefreshing: Boolean,
    error: Throwable?,
    onRetry: (() -> Unit)?
) {
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        if (isRefreshing) {
            item { CircularProgressIndicator() }
        }
        if (error != null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.bundle_changelog_error, error.simpleMessage().orEmpty()),
                        color = MaterialTheme.colorScheme.error
                    )
                    if (onRetry != null) {
                        Button(onClick = onRetry) {
                            Text(stringResource(R.string.bundle_changelog_retry))
                        }
                    }
                }
            }
        }
        items(entries) { entry ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val publishDate = remember(entry.publishedAtMillis) {
                    entry.publishedAtMillis?.relativeTime(context)
                        ?: context.getString(R.string.invalid_date)
                }
                val markdown = remember(entry.description) {
                    entry.description
                        .replace("\r\n", "\n")
                        .sanitizePatchChangelogMarkdown()
                }

                Changelog(
                    markdown = if (markdown.isBlank()) {
                        stringResource(R.string.bundle_changelog_empty)
                    } else markdown,
                    version = entry.version,
                    publishDate = publishDate
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}
