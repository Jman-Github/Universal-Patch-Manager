package app.universal.patch.manager.ui.component.haptics

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.disabled
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.FloatingActionButtonElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import app.universal.patch.manager.util.withHapticFeedback

@Composable
fun HapticExtendedFloatingActionButton (
    text: @Composable () -> Unit,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = true,
    enabled: Boolean = true,
    shape: Shape = FloatingActionButtonDefaults.extendedFabShape,
    containerColor: Color = FloatingActionButtonDefaults.containerColor,
    contentColor: Color = contentColorFor(containerColor),
    elevation: FloatingActionButtonElevation = FloatingActionButtonDefaults.elevation(),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    // A disabled FAB is an opaque muted button; fading the whole FAB let the content scrolling
    // underneath show through it.
    val mutedDisabled = !enabled
    // Material FABs have no disabled state, so report it to accessibility services here.
    val displayModifier = if (enabled) modifier else modifier.semantics { disabled() }
    val clickHandler: () -> Unit = if (enabled) {
        onClick.withHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    } else {
        {}
    }

    ExtendedFloatingActionButton(
        text = text,
        icon = icon,
        onClick = clickHandler,
        modifier = displayModifier,
        expanded = expanded,
        shape = shape,
        containerColor = if (mutedDisabled) MaterialTheme.colorScheme.surfaceContainerHighest else containerColor,
        contentColor = if (mutedDisabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else contentColor,
        elevation = elevation,
        interactionSource = interactionSource
    )
}
