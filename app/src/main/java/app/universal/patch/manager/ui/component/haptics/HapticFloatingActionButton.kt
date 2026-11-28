package app.universal.patch.manager.ui.component.haptics

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.disabled
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.FloatingActionButtonElevation
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import app.universal.patch.manager.util.withHapticFeedback

@Composable
fun HapticFloatingActionButton (
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = FloatingActionButtonDefaults.shape,
    containerColor: Color = FloatingActionButtonDefaults.containerColor,
    contentColor: Color = contentColorFor(containerColor),
    elevation: FloatingActionButtonElevation = FloatingActionButtonDefaults.elevation(),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable () -> Unit,
) {
    val clickHandler: () -> Unit = if (enabled) {
        onClick.withHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    } else {
        {}
    }

    FloatingActionButton(
        onClick = clickHandler,
        // Material FABs have no disabled state, so report it to accessibility services here.
        modifier = if (enabled) modifier else modifier.semantics { disabled() },
        shape = shape,
        containerColor = containerColor,
        contentColor = contentColor,
        elevation = elevation,
        interactionSource = interactionSource,
        content = content
    )
}
