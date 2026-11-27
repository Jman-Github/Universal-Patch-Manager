@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.universal.patch.manager.ui.component.design

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button as MaterialButton
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton as MaterialTonalButton
import androidx.compose.material3.IconButton as MaterialIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedButton as MaterialOutlinedButton
import androidx.compose.material3.TextButton as MaterialTextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import app.universal.patch.manager.ui.theme.LocalExpressiveDesign

@Composable
internal fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    if (LocalExpressiveDesign.current) {
        MaterialButton(
            onClick = onClick, modifier = modifier.heightIn(min = ButtonDefaults.MediumContainerHeight),
            shapes = ButtonDefaults.shapesFor(ButtonDefaults.MediumContainerHeight),
            contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
            enabled = enabled, content = content
        )
    } else {
        MaterialButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
    }
}

@Composable
internal fun FilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    if (LocalExpressiveDesign.current) {
        MaterialTonalButton(
            onClick = onClick, shapes = ButtonDefaults.shapes(), modifier = modifier,
            enabled = enabled, content = content
        )
    } else {
        MaterialTonalButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
    }
}

@Composable
internal fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    if (LocalExpressiveDesign.current) {
        MaterialOutlinedButton(
            onClick = onClick, shapes = ButtonDefaults.shapes(), modifier = modifier,
            enabled = enabled, content = content
        )
    } else {
        MaterialOutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
    }
}

@Composable
internal fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.textShape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    content: @Composable RowScope.() -> Unit
) {
    if (LocalExpressiveDesign.current) {
        MaterialTextButton(
            onClick = onClick, shapes = ButtonDefaults.shapes(shape = shape), modifier = modifier,
            enabled = enabled, colors = colors, contentPadding = contentPadding, content = content
        )
    } else {
        MaterialTextButton(
            onClick = onClick, modifier = modifier, enabled = enabled, shape = shape,
            colors = colors, contentPadding = contentPadding, content = content
        )
    }
}

@Composable
internal fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit
) {
    if (LocalExpressiveDesign.current) {
        // Standard icon buttons keep app bars and rows quiet; the press still morphs the shape.
        MaterialIconButton(
            onClick = onClick, shapes = IconButtonDefaults.shapes(), modifier = modifier,
            enabled = enabled, interactionSource = interactionSource, content = content
        )
    } else {
        MaterialIconButton(
            onClick = onClick, modifier = modifier, enabled = enabled,
            interactionSource = interactionSource, content = content
        )
    }
}
