package app.universal.patch.manager.ui.component.patcher

import app.universal.patch.manager.ui.component.design.TextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.universal.patch.manager.R
import app.universal.patch.manager.ui.component.CenteredDialogTitle

enum class SavedAppMountPromptMode {
    MOUNT_OR_INSTALL,
    REMOUNT
}

@Composable
fun SavedAppMountPromptDialog(
    mode: SavedAppMountPromptMode,
    canMount: Boolean,
    onDismiss: () -> Unit,
    onMount: () -> Unit,
    onChooseDifferentInstaller: () -> Unit,
    onRemount: () -> Unit
) {
    val titleRes = when (mode) {
        SavedAppMountPromptMode.MOUNT_OR_INSTALL -> R.string.mount
        SavedAppMountPromptMode.REMOUNT -> R.string.remount_saved_app
    }
    val descriptionRes = when (mode) {
        SavedAppMountPromptMode.MOUNT_OR_INSTALL -> R.string.saved_app_mount_choice_description
        SavedAppMountPromptMode.REMOUNT -> R.string.saved_app_remount_choice_description
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { CenteredDialogTitle(stringResource(titleRes)) },
        text = { Text(stringResource(descriptionRes)) },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.End
            ) {
                when (mode) {
                    SavedAppMountPromptMode.MOUNT_OR_INSTALL -> {
                        TextButton(onClick = onChooseDifferentInstaller) {
                            Text(stringResource(R.string.install_with_different_installer))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onMount, enabled = canMount) {
                                Text(stringResource(R.string.mount))
                            }
                            TextButton(onClick = onDismiss) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    }

                    SavedAppMountPromptMode.REMOUNT -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onRemount, enabled = canMount) {
                                Text(stringResource(R.string.continue_))
                            }
                            TextButton(onClick = onDismiss) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    }
                }
            }
        }
    )
}
