package app.universal.patch.manager.ui.component

import app.universal.patch.manager.ui.component.design.ListItem
import app.universal.patch.manager.ui.component.design.TextButton
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.universal.patch.manager.R
import app.universal.patch.manager.ui.component.haptics.HapticCheckbox
import app.universal.patch.manager.util.transparentListItemColors
import app.universal.patch.manager.ui.component.CenteredDialogTitle

@Composable
fun AutoUpdatesDialog(onSubmit: (Boolean) -> Unit) {
    var managerEnabled by rememberSaveable { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = {},
        confirmButton = {
            TextButton(onClick = { onSubmit(managerEnabled) }) {
                Text(stringResource(R.string.save))
            }
        },
        icon = { Icon(Icons.Outlined.Update, null) },
        title = { CenteredDialogTitle(text = stringResource(R.string.auto_updates_dialog_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = stringResource(R.string.auto_updates_dialog_description))

                Column {
                    AutoUpdatesItem(
                        headline = R.string.auto_updates_dialog_manager,
                        icon = Icons.Outlined.Update,
                        checked = managerEnabled,
                        onCheckedChange = { managerEnabled = it }
                    )

                }

                Text(text = stringResource(R.string.auto_updates_dialog_note))
            }
        }
    )
}

@Composable
private fun AutoUpdatesItem(
    @StringRes headline: Int,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) = ListItem(
    checked = checked,
    leadingContent = { Icon(icon, null) },
    headlineContent = { Text(stringResource(headline)) },
    trailingContent = { HapticCheckbox(checked = checked, onCheckedChange = null) },
    onCheckedChange = onCheckedChange,
    colors = transparentListItemColors
)
