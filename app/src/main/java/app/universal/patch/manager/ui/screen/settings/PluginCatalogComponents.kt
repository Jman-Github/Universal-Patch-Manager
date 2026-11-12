package app.universal.patch.manager.ui.screen.settings

import android.text.format.Formatter
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.universal.patch.manager.R
import app.universal.patch.manager.network.runtime.RuntimeDownloadProgress
import app.universal.patch.manager.network.runtime.RuntimeDownloadStage
import app.universal.patch.manager.ui.component.haptics.HapticCheckbox
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsCard
import app.universal.patch.manager.util.withHapticFeedback
import java.text.NumberFormat

@Composable
internal fun CatalogTransferProgress(
    progress: RuntimeDownloadProgress?,
    fallbackName: String,
    step: Int,
    stepCount: Int,
    stageLabel: String? = null
) {
    val context = LocalContext.current
    val fraction = progress?.fraction
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(progress?.name ?: fallbackName, style = MaterialTheme.typography.titleSmall)
                progress?.let {
                    Text(stageLabel ?: it.stage.catalogLabel(), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (step >= 0) Text(
                stringResource(R.string.runtime_progress_step, step + 1, stepCount),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary
            )
        }
        if (fraction == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            val animated by animateFloatAsState(
                targetValue = fraction, animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
                label = "runtimeDownloadProgress"
            )
            LinearProgressIndicator(progress = { animated }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
        }
        if (progress?.stage == RuntimeDownloadStage.DOWNLOADING &&
            (progress.bytesDownloaded > 0 || progress.totalBytes != null)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    progress.totalBytes?.takeIf { it > 0 }?.let { total ->
                        stringResource(R.string.runtime_progress_bytes,
                            Formatter.formatShortFileSize(context, progress.bytesDownloaded),
                            Formatter.formatShortFileSize(context, total))
                    } ?: Formatter.formatShortFileSize(context, progress.bytesDownloaded),
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                fraction?.let {
                    Text(
                        stringResource(R.string.runtime_progress_percent, NumberFormat.getPercentInstance().format(it)),
                        Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End
                    )
                }
            }
        }
    }
}

@Composable
internal fun CatalogMessage(message: String, error: Boolean) {
    ExpressiveSettingsCard(
        modifier = Modifier.padding(horizontal = 16.dp),
        containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
        contentPadding = PaddingValues(16.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Outlined.WarningAmber, contentDescription = null, modifier = Modifier.size(20.dp),
                tint = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.primary
            )
            Text(
                message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun RuntimeDownloadStage.catalogLabel(): String = stringResource(when (this) {
    RuntimeDownloadStage.PREPARING -> R.string.runtime_progress_preparing
    RuntimeDownloadStage.DOWNLOADING -> R.string.runtime_progress_downloading
    RuntimeDownloadStage.VERIFYING -> R.string.runtime_progress_verifying
    RuntimeDownloadStage.FINISHING -> R.string.runtime_progress_finishing
})

@Composable
internal fun formatSize(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)


/** Catalog entries share one layout, touch target, selection feedback and status badge. */
@Composable
internal fun CatalogChoiceCard(
    name: String,
    description: String,
    version: String,
    status: String?,
    complete: Boolean,
    selected: Boolean,
    busy: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: @Composable () -> Unit,
    extraContent: @Composable () -> Unit = {}
) {
    val shape = MaterialTheme.shapes.extraLarge
    ExpressiveSettingsCard(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(shape)
            .border(1.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
            .toggleable(
                value = selected, enabled = !busy, role = Role.Checkbox,
                onValueChange = onCheckedChange.withHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            ).semantics(mergeDescendants = true) {},
        containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
        contentPadding = PaddingValues(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            icon()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HapticCheckbox(checked = selected, onCheckedChange = null, enabled = !busy)
        }
        Spacer(Modifier.height(12.dp))
        Text(version, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        status?.let {
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = MaterialTheme.shapes.large,
                color = if (selected) MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                    else MaterialTheme.colorScheme.secondaryContainer
            ) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(if (complete) Icons.Default.Check else Icons.Outlined.Download,
                        contentDescription = null, modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Text(it, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        extraContent()
    }
}

@Composable
internal fun CatalogIntroduction(title: String, description: String) {
    ExpressiveSettingsCard(
        modifier = Modifier.padding(horizontal = 16.dp),
        contentPadding = PaddingValues(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(Icons.Outlined.Extension, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(14.dp).size(24.dp))
            }
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Text(description, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun CatalogSelectionSummary(count: Int, summary: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.runtime_catalog_selected_count, count),
            Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        Text(summary, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}
