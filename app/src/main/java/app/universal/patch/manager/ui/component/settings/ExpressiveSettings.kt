package app.universal.patch.manager.ui.component.settings

import androidx.compose.ui.semantics.Role
import app.universal.patch.manager.ui.component.design.ListItem
import app.universal.patch.manager.ui.component.design.FilledTonalButton
import app.universal.patch.manager.ui.component.design.OutlinedButton
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.onSizeChanged
import kotlin.math.abs
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.zIndex
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import app.universal.patch.manager.ui.theme.LocalExpressiveDesign
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.universal.patch.manager.ui.component.haptics.HapticSwitch
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.surfaceColorAtElevation

@Composable
fun ExpressiveSettingsCard(
    modifier: Modifier = Modifier,
    containerColor: Color = if (LocalExpressiveDesign.current) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
    },
    shadowElevation: Dp = 0.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable () -> Unit
) {
    val cardWidth = remember { mutableIntStateOf(0) }
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = containerColor,
        tonalElevation = 0.dp,
        shadowElevation = shadowElevation,
        modifier = modifier
    ) {
        CompositionLocalProvider(LocalSegmentCardWidth provides cardWidth) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { cardWidth.intValue = it.width }
                    .padding(contentPadding)
            ) {
                content()
            }
        }
    }
}

/** Width of the enclosing settings card, so a divider can tell whether it spans the whole group. */
private val LocalSegmentCardWidth = compositionLocalOf<MutableIntState?> { null }

@Composable
fun ExpressiveSettingsDivider(
    modifier: Modifier = Modifier
) {
    if (LocalExpressiveDesign.current) {
        val background = MaterialTheme.colorScheme.background
        val gapColor = if (background.alpha < 1f) MaterialTheme.colorScheme.surface else background
        val lineColor = MaterialTheme.colorScheme.outlineVariant
        val cardWidth = LocalSegmentCardWidth.current
        Spacer(
            modifier
                .fillMaxWidth()
                .height(ExpressiveSegmentGap)
                // Draw after both neighbours so the inner corners land on top of them.
                .zIndex(1f)
                .drawBehind {
                    // Only a divider that spans its whole card splits it into segments; an inset
                    // one (inside padded content, or outside any card) stays a hairline.
                    val spansCard = cardWidth != null && abs(size.width - cardWidth.intValue) <= 1f
                    if (spansCard) {
                        drawSegmentGap(gapColor, ExpressiveSegmentCorner.toPx())
                    } else {
                        val y = size.height / 2
                        drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                    }
                }
        )
    } else {
        HorizontalDivider(
            modifier = modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    }
}

private val ExpressiveSegmentGap = 3.dp
private val ExpressiveSegmentCorner = 8.dp

private fun DrawScope.drawSegmentGap(color: Color, radius: Float) {
    drawRect(color)
    val right = size.width - radius
    val bottom = size.height
    // Square minus quarter circle: a concave corner on the segment above and below the gap.
    for ((left, top, centerX, centerY) in listOf(
        Corner(0f, -radius, radius, -radius),
        Corner(right, -radius, right, -radius),
        Corner(0f, bottom, radius, bottom + radius),
        Corner(right, bottom, right, bottom + radius)
    )) {
        val square = Path().apply { addRect(Rect(left, top, left + radius, top + radius)) }
        val circle = Path().apply { addOval(Rect(Offset(centerX, centerY), radius)) }
        drawPath(Path.combine(PathOperation.Difference, square, circle), color)
    }
}

private data class Corner(val left: Float, val top: Float, val centerX: Float, val centerY: Float)

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
    val slotAlpha = if (enabled || LocalExpressiveDesign.current) 1f else 0.38f
    ListItem(
        headlineContent = {
            Box(Modifier.alpha(slotAlpha)) { headlineContent() }
        },
        supportingContent = when {
            supportingContentSlot != null -> {
                { Box(Modifier.alpha(slotAlpha)) { supportingContentSlot() } }
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
        role = Role.Button,
        colors = if (LocalExpressiveDesign.current) {
            ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        } else {
            ListItemDefaults.colors(containerColor = Color.Transparent)
        },
        modifier = modifier,
        enabled = enabled,
        onClick = onClick
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
    val segment = if (LocalExpressiveDesign.current) {
        Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)
    } else Modifier
    Column(
        modifier = modifier.fillMaxWidth().then(segment)
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
        colors = if (LocalExpressiveDesign.current) SwitchDefaults.colors() else SwitchDefaults.colors(
            checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
            checkedThumbColor = MaterialTheme.colorScheme.primary,
            checkedIconColor = MaterialTheme.colorScheme.onPrimary,
            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            uncheckedThumbColor = MaterialTheme.colorScheme.outline,
            uncheckedIconColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}
