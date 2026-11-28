package app.universal.patch.manager.ui.component.bundle

import app.universal.patch.manager.ui.theme.withClassicCorner
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.universal.patch.manager.data.platform.NetworkInfo
import app.universal.patch.manager.domain.bundles.BundleReleasePageRepository
import app.universal.patch.manager.domain.bundles.PatchBundleSource
import app.universal.patch.manager.util.simpleMessage
import app.universal.patch.manager.util.toast
import app.universal.patch.manager.R
import kotlinx.coroutines.CancellationException

@Composable
fun LinkOptionRow(
    icon: @Composable () -> Unit,
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val shape = MaterialTheme.shapes.medium.withClassicCorner(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (enabled) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surface
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val iconTint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
        CompositionLocalProvider(LocalContentColor provides iconTint) {
            icon()
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
            modifier = Modifier.weight(1f)
        )
    }
}

suspend fun openBundleReleasePage(
    src: PatchBundleSource,
    pages: BundleReleasePageRepository,
    networkInfo: NetworkInfo,
    context: android.content.Context,
    uriHandler: androidx.compose.ui.platform.UriHandler,
) {
    try {
        val url = pages.resolve(src, networkInfo.isConnected())
        if (url == null) context.toast(context.getString(R.string.bundle_release_page_unavailable))
        else uriHandler.openUri(url)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        context.toast(context.getString(R.string.bundle_release_page_error, error.simpleMessage().orEmpty()))
    }
}
