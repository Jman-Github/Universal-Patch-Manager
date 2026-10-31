package app.universal.patch.manager.ui.component.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.universal.patch.manager.ui.component.haptics.HapticSwitch
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.surfaceColorAtElevation

@Composable
fun ExpressiveSettingsCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
    shadowElevation: Dp = 0.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = containerColor,
        tonalElevation = 0.dp,
        shadowElevation = shadowElevation,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding)
        ) {
            content()
        }
    }
}

@Composable
fun ExpressiveSettingsDivider(
    modifier: Modifier = Modifier
) {
    HorizontalDivider(
        modifier = modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    )
}

@Composable
fun ExpressiveSettingsItem(
    headlineContent: String,
    modifier: Modifier = Modifier,
    supportingContent: String? = null,
    supportingContentSlot: (@Composable (() -> Unit))? = null,
    leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    ExpressiveSettingsItem(
        headlineContent = {
            androidx.compose.material3.Text(
                text = headlineContent,
                style = MaterialTheme.typography.titleMedium
            )
        },
        modifier = modifier,
        supportingContent = supportingContent,
        supportingContentSlot = supportingContentSlot,
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        enabled = enabled,
        onClick = onClick
    )
}

@Composable
fun ExpressiveSettingsItem(
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    supportingContent: String? = null,
    supportingContentSlot: (@Composable (() -> Unit))? = null,
    leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    val containerColor = Color.Transparent
    val clickableModifier = if (onClick != null) {
        Modifier.clickable(
            enabled = enabled,
            role = Role.Button,
            onClick = onClick
        )
    } else {
        Modifier
    }

    ListItem(
        headlineContent = {
            Box(Modifier.alpha(if (enabled) 1f else 0.38f)) { headlineContent() }
        },
        supportingContent = when {
            supportingContentSlot != null -> {
                { Box(Modifier.alpha(if (enabled) 1f else 0.38f)) { supportingContentSlot() } }
            }
            supportingContent != null -> {
                {
                    androidx.compose.material3.Text(
                        text = supportingContent,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f)
                    )
                }
            }
            else -> null
        },
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        colors = ListItemDefaults.colors(containerColor = containerColor),
        modifier = modifier.then(clickableModifier)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExpressiveSettingsConfigurableItem(
    headlineContent: String,
    modifier: Modifier = Modifier,
    supportingContent: String? = null,
    supportingContentSlot: (@Composable (() -> Unit))? = null,
    leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    secondaryActionLabel: String,
    onSecondaryAction: () -> Unit,
    primaryActionLabel: String,
    onPrimaryAction: () -> Unit,
    secondaryActionEnabled: Boolean = enabled,
    primaryActionEnabled: Boolean = enabled
) {
    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        ExpressiveSettingsItem(
            headlineContent = headlineContent,
            supportingContent = supportingContent,
            supportingContentSlot = supportingContentSlot,
            leadingContent = leadingContent,
            trailingContent = trailingContent,
            enabled = enabled
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onSecondaryAction,
                enabled = secondaryActionEnabled,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = secondaryActionLabel,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .basicMarquee(),
                    textAlign = TextAlign.Center
                )
            }
            FilledTonalButton(
                onClick = onPrimaryAction,
                enabled = primaryActionEnabled,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = primaryActionLabel,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .basicMarquee(),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun ExpressiveSettingsSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val icon = if (checked) Icons.Filled.Check else Icons.Filled.Close
    HapticSwitch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        thumbContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
        },
        colors = SwitchDefaults.colors(
            checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
            checkedThumbColor = MaterialTheme.colorScheme.primary,
            checkedIconColor = MaterialTheme.colorScheme.onPrimary,
            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            uncheckedThumbColor = MaterialTheme.colorScheme.outline,
            uncheckedIconColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}
