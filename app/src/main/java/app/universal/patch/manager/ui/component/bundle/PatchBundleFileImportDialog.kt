package app.universal.patch.manager.ui.component.bundle

import app.universal.patch.manager.ui.component.design.TextButton
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.universal.patch.manager.R
import app.universal.patch.manager.ui.component.CenteredDialogTitle
import app.universal.patch.manager.util.PatchBundleFileManifest

// Code adapted from Morphe, see third-party/NOTICE for more information
// https://github.com/MorpheApp/morphe-manager/blob/6688aa17ea35b5ab398a3c1922be13626290cbf1/app/src/main/java/app/morphe/manager/ui/screen/home/HomeDialogs.kt#L1721-L1875
@Composable
fun PatchBundleFileImportDialog(
    manifest: PatchBundleFileManifest?,
    fileName: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val displayName = manifest?.name ?: fileName ?: stringResource(R.string.patch_bundle)
    val versionAndAuthor = listOfNotNull(
        manifest?.version?.let { version ->
            if (version.startsWith("v", ignoreCase = true)) version else "v$version"
        },
        manifest?.author
    ).joinToString(" • ")
    val sourceScrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            CenteredDialogTitle(
                text = stringResource(R.string.patch_bundle_file_import_dialog_title)
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.patch_bundle_file_import_dialog_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                HorizontalDivider()

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    manifest?.description?.let { description ->
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (versionAndAuthor.isNotEmpty()) {
                        Text(
                            text = versionAndAuthor,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    manifest?.source?.let { source ->
                        Text(
                            text = source,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier.horizontalScroll(sourceScrollState)
                        )
                    }

                    if (fileName != null && manifest?.name != null) {
                        Text(
                            text = fileName,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
