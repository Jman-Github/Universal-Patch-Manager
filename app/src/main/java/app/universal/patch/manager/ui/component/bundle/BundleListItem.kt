package app.universal.patch.manager.ui.component.bundle

import app.universal.patch.manager.ui.component.design.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.universal.patch.manager.ui.theme.LocalExpressiveDesign

@Composable
fun BundleListItem(
    modifier: Modifier = Modifier,
    headlineText: String,
    supportingText: String = "",
    trailingContent: @Composable (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = {
            Text(
                text = headlineText,
                // Expressive rows share the settings rows' title size.
                style = if (LocalExpressiveDesign.current) MaterialTheme.typography.titleMedium
                    else MaterialTheme.typography.titleLarge
            )
        },
        supportingContent = {
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (LocalExpressiveDesign.current) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.outline
            )
        },
        trailingContent = trailingContent,
        modifier = modifier
    )
}