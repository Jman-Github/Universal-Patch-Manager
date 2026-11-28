package app.universal.patch.manager.ui.screen

import app.universal.patch.manager.ui.component.design.LinearProgressIndicator
import app.universal.patch.manager.ui.component.design.TextButton
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.universal.patch.manager.R
import app.universal.patch.manager.ui.component.LazyColumnWithScrollbar
import app.universal.patch.manager.ui.component.ScrollableBottomSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CatalogFilterMenu(
    label: String,
    selected: String,
    options: List<Pair<String, String>>,
    enabled: Boolean = true,
    loading: Boolean = false,
    error: String? = null,
    onRetry: () -> Unit = {},
    onOpen: () -> Unit = {},
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    FilterChip(selected = selected.isNotBlank(), enabled = enabled,
        onClick = { query = ""; expanded = true; onOpen() },
        label = { Text(options.firstOrNull { it.first == selected }?.second ?: label,
            maxLines = 1) })
    if (expanded) {
        ScrollableBottomSheet(onDismissRequest = { expanded = false }) {
            LazyColumnWithScrollbar(
                contentPadding = PaddingValues(start = 16.dp, bottom = 24.dp),
                reserveScrollbarSpace = true
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(label, style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(R.string.catalog_search)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        if (error != null) {
                            Text(error, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = onRetry) { Text(stringResource(R.string.catalog_retry)) }
                        }
                    }
                }
                item {
                    TextButton(onClick = {
                        onSelect("")
                        expanded = false
                    }) {
                        Text(stringResource(R.string.catalog_all))
                    }
                }
                items(options.filter { it.second.contains(query, true) }, key = { it.first }) { option ->
                    TextButton(onClick = {
                        onSelect(option.first)
                        expanded = false
                    }) {
                        Text(option.second)
                    }
                }
            }
        }
    }
}
