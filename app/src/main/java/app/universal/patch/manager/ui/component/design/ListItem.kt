@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.universal.patch.manager.ui.component.design

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem as MaterialListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.universal.patch.manager.ui.theme.LocalExpressiveDesign

@Composable
internal fun ListItem(
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    overlineContent: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    colors: ListItemColors = ListItemDefaults.colors(),
    tonalElevation: Dp = ListItemDefaults.Elevation,
    shadowElevation: Dp = ListItemDefaults.Elevation,
    enabled: Boolean = true,
    role: Role? = null,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    interactionSource: MutableInteractionSource? = null,
    onClick: (() -> Unit)? = null
) {
    if (!LocalExpressiveDesign.current) {
        val rowModifier = when {
            onClick == null -> modifier
            onLongClick == null -> modifier.clickable(
                interactionSource = interactionSource, indication = LocalIndication.current,
                enabled = enabled, role = role, onClick = onClick
            )
            else -> modifier.combinedClickable(
                interactionSource = interactionSource, indication = LocalIndication.current,
                enabled = enabled, role = role, onLongClick = onLongClick,
                onLongClickLabel = onLongClickLabel, onClick = onClick
            )
        }
        MaterialListItem(
            headlineContent = headlineContent, modifier = rowModifier,
            overlineContent = overlineContent, supportingContent = supportingContent,
            leadingContent = leadingContent, trailingContent = trailingContent,
            colors = colors, tonalElevation = tonalElevation, shadowElevation = shadowElevation
        )
    } else if (onClick == null) {
        MaterialListItem(
            content = headlineContent, modifier = modifier, enabled = enabled,
            overlineContent = overlineContent, supportingContent = supportingContent,
            leadingContent = leadingContent, trailingContent = trailingContent,
            colors = colors.inheritingContainer(), elevation = ListItemDefaults.elevation(shadowElevation)
        )
    } else {
        MaterialListItem(
            content = headlineContent, onClick = onClick, enabled = enabled,
            onLongClick = onLongClick, onLongClickLabel = onLongClickLabel, interactionSource = interactionSource,
            modifier = if (role == null) modifier else modifier.semantics { this.role = role },
            overlineContent = overlineContent, supportingContent = supportingContent,
            leadingContent = leadingContent, trailingContent = trailingContent,
            colors = colors.inheritingContainer(), elevation = ListItemDefaults.elevation(shadowElevation)
        )
    }
}

@Composable
internal fun ListItemRadioButton(selected: Boolean, enabled: Boolean = true) {
    val colors = if (LocalExpressiveDesign.current) {
        val foreground = LocalContentColor.current
        RadioButtonDefaults.colors(
            selectedColor = foreground, unselectedColor = foreground,
            disabledSelectedColor = foreground, disabledUnselectedColor = foreground
        )
    } else {
        RadioButtonDefaults.colors()
    }
    RadioButton(selected = selected, enabled = enabled, onClick = null, colors = colors)
}

@Composable
internal fun ListItem(
    selected: Boolean,
    onClick: () -> Unit,
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    leadingContent: (@Composable () -> Unit)? = null,
    overlineContent: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    colors: ListItemColors = ListItemDefaults.colors()
) {
    if (LocalExpressiveDesign.current) {
        MaterialListItem(
            selected = selected, onClick = onClick, enabled = enabled,
            // The selection pill keeps clear of dialog edges; row text then meets the 24dp text inset.
            modifier = modifier.padding(horizontal = 8.dp),
            colors = colors.inheritingContainer(),
            content = headlineContent, leadingContent = leadingContent, overlineContent = overlineContent,
            supportingContent = supportingContent, trailingContent = trailingContent
        )
    } else {
        MaterialListItem(
            modifier = modifier.selectable(
                selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick
            ),
            headlineContent = headlineContent, leadingContent = leadingContent, overlineContent = overlineContent,
            supportingContent = supportingContent, trailingContent = trailingContent, colors = colors
        )
    }
}

@Composable
internal fun ListItem(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    headlineContent: @Composable () -> Unit,
    leadingContent: @Composable () -> Unit,
    supportingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    colors: ListItemColors = ListItemDefaults.colors()
) {
    if (LocalExpressiveDesign.current) {
        MaterialListItem(
            checked = checked, onCheckedChange = onCheckedChange, colors = colors.inheritingContainer(),
            content = headlineContent, leadingContent = leadingContent,
            supportingContent = supportingContent, trailingContent = trailingContent
        )
    } else {
        MaterialListItem(
            modifier = Modifier.toggleable(checked, role = Role.Checkbox, onValueChange = onCheckedChange),
            headlineContent = headlineContent, leadingContent = leadingContent,
            supportingContent = supportingContent, trailingContent = trailingContent, colors = colors
        )
    }
}

/**
 * The classic list item tinted its surface container with the parent's tonal elevation. The
 * expressive one paints the raw color, so a default container would cut a hole in its card.
 */
@Composable
private fun ListItemColors.inheritingContainer(): ListItemColors = when (containerColor) {
    MaterialTheme.colorScheme.surface ->
        copy(containerColor = Color.Transparent, disabledContainerColor = Color.Transparent)
    // A see-through row stays see-through when disabled instead of filling its bounds with a band.
    Color.Transparent -> copy(disabledContainerColor = Color.Transparent)
    else -> this
}
