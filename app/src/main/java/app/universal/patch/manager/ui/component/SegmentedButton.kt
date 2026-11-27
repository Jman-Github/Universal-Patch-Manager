package app.universal.patch.manager.ui.component

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import app.universal.patch.manager.ui.theme.LocalExpressiveDesign
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.universal.patch.manager.util.consumeHorizontalScroll

/**
 * Credits to [Vendetta](https://github.com/vendetta-mod)
 */
@Composable
fun RowScope.SegmentedButton(
    icon: Any,
    text: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    iconDescription: String? = null,
    enabled: Boolean = true
) {
    val expressive = LocalExpressiveDesign.current
    val colors = MaterialTheme.colorScheme
    val contentColor = when {
        !enabled -> colors.onSurface.copy(alpha = 0.38f)
        expressive -> colors.onSecondaryContainer
        else -> colors.primary
    }
    val containerColor = when {
        !enabled -> colors.onSurface.copy(alpha = 0.12f)
        expressive -> colors.secondaryContainer
        else -> colors.surfaceColorAtElevation(2.dp)
    }

    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            modifier = Modifier
                // Connected button group: tight inner corners, the parent clip supplies the outer ones.
                .then(if (expressive) Modifier.clip(MaterialTheme.shapes.extraSmall) else Modifier)
                .combinedClickable(
                    role = Role.Button,
                    enabled = enabled,
                    onClick = onClick,
                    onLongClick = onLongClick
                )
                .background(containerColor)
                .weight(1f)
                .padding(vertical = 20.dp)
        ) {
            when (icon) {
                is ImageVector -> {
                    Icon(
                        imageVector = icon,
                        contentDescription = iconDescription
                    )
                }

                is Painter -> {
                    Icon(
                        painter = icon,
                        contentDescription = iconDescription
                    )
                }
            }

            val labelScrollState = rememberScrollState()
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                modifier = Modifier
                    .consumeHorizontalScroll(labelScrollState)
            )
        }
    }
}
