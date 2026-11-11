package app.universal.patch.manager.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.universal.patch.manager.R
import app.universal.patch.manager.ui.component.AppIcon
import app.universal.patch.manager.ui.component.LazyColumnWithScrollbar
import app.universal.patch.manager.ui.component.haptics.HapticCheckbox

internal data class AppEntryChoice(
    val name: String,
    val packageName: String,
    val version: String,
    val missing: Boolean,
    val enabled: Boolean = true
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AppEntryArchiveSelection(
    title: String,
    description: String,
    action: String,
    entries: List<AppEntryChoice>,
    onDismiss: () -> Unit,
    onConfirm: (Set<Int>) -> Unit
) {
    var selected by rememberSaveable(entries) { mutableStateOf(arrayListOf<Int>()) }
    val selectable = entries.indices.filterTo(linkedSetOf()) { entries[it].enabled }
    val selection = selected.filterTo(mutableSetOf()) { it in selectable }
    val all = selectable.isNotEmpty() && selection.size == selectable.size
    val selectionState = when {
        all -> ToggleableState.On
        selection.isEmpty() -> ToggleableState.Off
        else -> ToggleableState.Indeterminate
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                pluralStringResource(R.plurals.app_entries_selected, selection.size, selection.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Row(
                modifier = Modifier
                    .triStateToggleable(
                        state = selectionState,
                        enabled = selectable.isNotEmpty(),
                        role = Role.Checkbox,
                        onClick = { selected = ArrayList(if (all) emptyList() else selectable) }
                    )
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(stringResource(R.string.app_entries_all))
                TriStateCheckbox(
                    state = selectionState,
                    onClick = null,
                    enabled = selectable.isNotEmpty()
                )
            }
        }
        HorizontalDivider()
        if (entries.isEmpty()) {
            Text(
                stringResource(R.string.app_entries_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(32.dp)
            )
        } else {
            LazyColumnWithScrollbar(
                thumbLength = app.universal.patch.manager.ui.component.DASHBOARD_SCROLLBAR_THUMB_LENGTH,
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                itemsIndexed(entries) { index, entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = index in selection,
                                enabled = entry.enabled,
                                role = Role.Checkbox
                            ) { checked ->
                                selected = ArrayList(if (checked) selection + index else selection - index)
                            }
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        AppIcon(
                            packageInfo = null,
                            packageName = entry.packageName,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp)
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(entry.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                entry.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (entry.version.isNotBlank()) {
                                Text(
                                    entry.version,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (entry.missing) {
                                Text(
                                    stringResource(R.string.app_entries_missing),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            if (!entry.enabled) {
                                Text(
                                    stringResource(R.string.app_entries_no_apk),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        HapticCheckbox(
                            checked = index in selection,
                            onCheckedChange = null,
                            enabled = entry.enabled
                        )
                    }
                    if (index != entries.lastIndex) {
                        HorizontalDivider(Modifier.padding(horizontal = 24.dp))
                    }
                }
            }
        }
        HorizontalDivider()
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            Button(
                onClick = { onConfirm(selection) },
                enabled = selection.isNotEmpty()
            ) { Text(action) }
        }
    }
}
