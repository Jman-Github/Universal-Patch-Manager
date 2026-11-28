package app.universal.patch.manager.ui.component.settings

import app.universal.patch.manager.ui.theme.LocalExpressiveDesign
import app.universal.patch.manager.ui.component.design.ListItem
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Composable
fun SettingsListItem(
    headlineContent: String,
    modifier: Modifier = Modifier,
    overlineContent: @Composable (() -> Unit)? = null,
    supportingContent: String? = null,
    supportingContentSlot: (@Composable (() -> Unit))? = null,
    leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    colors: ListItemColors = ListItemDefaults.colors(),
    tonalElevation: Dp = ListItemDefaults.Elevation,
    shadowElevation: Dp = ListItemDefaults.Elevation,
) = SettingsListItem(
    headlineContent = {
        Text(
            text = headlineContent,
            // Expressive rows sit in cards and share the settings rows' title size.
            style = if (LocalExpressiveDesign.current) MaterialTheme.typography.titleMedium
                else MaterialTheme.typography.titleLarge
        )
    },
    modifier = modifier,
    overlineContent = overlineContent,
    supportingContent = supportingContent,
    supportingContentSlot = supportingContentSlot,
    leadingContent = leadingContent,
    trailingContent = trailingContent,
    colors = colors,
    tonalElevation = tonalElevation,
    shadowElevation = shadowElevation
)

@Composable
fun SettingsListItem(
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    overlineContent: @Composable (() -> Unit)? = null,
    supportingContent: String? = null,
    supportingContentSlot: (@Composable (() -> Unit))? = null,
    leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    colors: ListItemColors = ListItemDefaults.colors(),
    tonalElevation: Dp = ListItemDefaults.Elevation,
    shadowElevation: Dp = ListItemDefaults.Elevation,
) = ListItem(
    headlineContent = headlineContent,
    modifier = if (LocalExpressiveDesign.current) modifier else modifier.then(Modifier.padding(horizontal = 8.dp)),
    overlineContent = overlineContent,
    supportingContent = {
        when {
            supportingContentSlot != null -> supportingContentSlot()
            supportingContent != null -> Text(
                text = supportingContent,
                style = MaterialTheme.typography.bodyMedium,
                color = if (LocalExpressiveDesign.current) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.outline
            )
        }
    },
    leadingContent = leadingContent,
    trailingContent = trailingContent,
    colors = colors,
    tonalElevation = tonalElevation,
    shadowElevation = shadowElevation
)

@Composable
fun ExpandableSettingListItem(
    headlineContent: String,
    supportingContent: String,
    expandableContent: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(modifier)
    ) {
        ExpressiveSettingsItem(
            modifier = Modifier.clickable { expanded = !expanded },
            headlineContent = headlineContent,
            supportingContent = supportingContent,
            trailingContent = {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
            }
        )

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
            ) + fadeIn(animationSpec = tween(durationMillis = 160)),
            exit = shrinkVertically(
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
            ) + fadeOut(animationSpec = tween(durationMillis = 160))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp, start = 16.dp, end = 16.dp)
            ) {
                expandableContent()
            }
        }
    }
}
