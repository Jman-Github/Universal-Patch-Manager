package app.universal.patch.manager.ui.component

import app.universal.patch.manager.util.withHapticFeedback
import android.view.HapticFeedbackConstants
import app.universal.patch.manager.ui.component.design.ListItem
import app.universal.patch.manager.ui.component.design.TextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.universal.patch.manager.R
import app.universal.patch.manager.network.dto.ReVancedAsset
import app.universal.patch.manager.ui.component.haptics.HapticCheckbox
import app.universal.patch.manager.util.transparentListItemColors

@Composable
fun AvailableUpdateDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    setShowManagerUpdateDialogOnLaunch: (Boolean) -> Unit,
    releaseInfo: ReVancedAsset,
    showFullChangelog: Boolean
) {
    var dontShowAgain by rememberSaveable { mutableStateOf(false) }
    val dismissDialog = {
        setShowManagerUpdateDialogOnLaunch(!dontShowAgain)
        onDismiss()
    }

    AlertDialogExtended(
        onDismissRequest = dismissDialog,
        confirmButton = {
            TextButton(
                onClick = {
                    dismissDialog()
                    onConfirm()
                }
            ) {
                Text(stringResource(R.string.show))
            }
        },
        dismissButton = {
            TextButton(
                onClick = dismissDialog
            ) {
                Text(stringResource(R.string.dismiss))
            }
        },
        icon = {
            Icon(imageVector = Icons.Outlined.Update, contentDescription = null)
        },
        title = {
            Text(
                text = stringResource(R.string.update_available),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier.padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    text = stringResource(R.string.update_available_dialog_description, releaseInfo.version),
                    style = MaterialTheme.typography.bodySmall
                )
                if (showFullChangelog && releaseInfo.description.isNotBlank()) {
                    Surface(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .fillMaxWidth()
                            .heightIn(max = 280.dp),
                        shape = MaterialTheme.shapes.medium,
                        tonalElevation = 1.dp,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Column(
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Markdown(
                                releaseInfo.description.replace("`", ""),
                                textStyle = MaterialTheme.typography.bodySmall,
                                compactHeadings = true
                            )
                        }
                    }
                }
                ListItem(
                    checked = dontShowAgain,
                    onCheckedChange = { value: Boolean -> dontShowAgain = value }.withHapticFeedback(HapticFeedbackConstants.CLOCK_TICK),
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.never_show_again),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    leadingContent = {
                        HapticCheckbox(checked = dontShowAgain, onCheckedChange = null)
                    },
                    colors = transparentListItemColors
                )
            }
        },
        textHorizontalPadding = PaddingValues(0.dp)
    )
}
