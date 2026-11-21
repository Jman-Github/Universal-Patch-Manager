package app.universal.patch.manager.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import app.universal.patch.manager.R
import app.universal.patch.manager.domain.repository.AppEntryArchive

@Composable
internal fun AppEntryArchiveProgressDialog(progress: AppEntryArchive.Progress) {
    val exporting = when (progress.stage) {
        AppEntryArchive.Stage.Preparing, AppEntryArchive.Stage.Exporting, AppEntryArchive.Stage.Saving -> true
        else -> false
    }
    val stageLabel = stringResource(
        when (progress.stage) {
            AppEntryArchive.Stage.Preparing -> R.string.app_entries_preparing
            AppEntryArchive.Stage.Reading -> R.string.app_entries_reading
            AppEntryArchive.Stage.Checking -> R.string.app_entries_checking
            AppEntryArchive.Stage.Exporting -> R.string.app_entries_exporting
            AppEntryArchive.Stage.Saving -> R.string.app_entries_saving
            AppEntryArchive.Stage.Importing -> R.string.app_entries_importing
        }
    )
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        icon = {
            Icon(
                if (exporting) Icons.Outlined.Save else Icons.Outlined.FileDownload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                stringResource(if (exporting) R.string.export_app_entries else R.string.import_app_entries),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    stageLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                progress.currentApp?.let { name ->
                    Text(
                        name,
                        style = MaterialTheme.typography.titleSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (progress.total > 0) {
                    LinearProgressIndicator(
                        progress = { (progress.completed.toFloat() / progress.total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        drawStopIndicator = {}
                    )
                    Text(
                        stringResource(R.string.app_entries_progress_count, progress.completed, progress.total),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {}
    )
}
