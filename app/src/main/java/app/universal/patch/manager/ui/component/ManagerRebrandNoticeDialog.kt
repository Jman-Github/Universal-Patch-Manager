package app.universal.patch.manager.ui.component

import app.universal.patch.manager.ui.component.design.TextButton
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import app.universal.patch.manager.R

@Composable
fun ManagerRebrandNoticeDialog(
    secondsRemaining: Int,
    onContinue: () -> Unit,
    onCancel: () -> Unit
) {
    val ready = secondsRemaining == 0
    AlertDialog(
        onDismissRequest = { if (ready) onCancel() },
        properties = DialogProperties(
            dismissOnBackPress = ready,
            dismissOnClickOutside = false
        ),
        icon = { Icon(Icons.Outlined.WarningAmber, contentDescription = null) },
        title = { CenteredDialogTitle(stringResource(R.string.manager_rebrand_notice_title)) },
        text = {
            ColumnWithScrollbar(Modifier.heightIn(max = 360.dp)) {
                Text(stringResource(
                    R.string.manager_rebrand_notice_description,
                    stringResource(R.string.settings),
                    stringResource(R.string.import_export),
                    stringResource(R.string.export_everything),
                    stringResource(R.string.import_everything),
                    stringResource(R.string.export_keystore),
                    stringResource(R.string.import_keystore)
                ))
            }
        },
        confirmButton = {
            TextButton(onClick = onContinue, enabled = ready) {
                Text(if (ready) stringResource(R.string.continue_) else
                    stringResource(R.string.manager_rebrand_continue_countdown, secondsRemaining))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = ready) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
