package app.universal.patch.manager.ui.component

import app.universal.patch.manager.ui.theme.withClassicCorner
import app.universal.patch.manager.ui.component.design.LinearProgressIndicator
import app.universal.patch.manager.ui.component.design.IconButton
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.material3.Icon
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.ui.text.style.TextOverflow

@Composable
fun DownloadProgressBanner(
    title: String,
    subtitle: String? = null,
    progress: Float?,
    collapsedLabel: String? = null,
    collapsed: Boolean = false,
    onToggleCollapsed: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val clampedProgress = progress?.coerceIn(0f, 1f)
    val contentPadding = if (collapsed) {
        PaddingValues(horizontal = 8.dp, vertical = 4.dp)
    } else {
        PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    }
    val headerHeight = 24.dp
    val collapsedHeaderHeight = 24.dp
    Surface(
        modifier = modifier.then(
            if (onToggleCollapsed != null) {
                Modifier.clickable(onClick = onToggleCollapsed)
            } else {
                Modifier
            }
        ),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp),
        tonalElevation = 2.dp,
        shadowElevation = 6.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = MaterialTheme.shapes.medium.withClassicCorner(14.dp)
    ) {
        Column(modifier = Modifier.padding(contentPadding)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (collapsed) collapsedHeaderHeight else headerHeight)
            ) {
                if (collapsed) {
                    if (!collapsedLabel.isNullOrBlank()) {
                        Text(
                            text = collapsedLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .padding(start = 8.dp, end = 36.dp)
                        )
                    }
                    if (onToggleCollapsed != null) {
                        IconButton(
                            onClick = onToggleCollapsed,
                            modifier = Modifier.align(Alignment.CenterEnd)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                } else {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.align(Alignment.CenterStart)
                    )
                    if (onToggleCollapsed != null) {
                        IconButton(
                            onClick = onToggleCollapsed,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .height(headerHeight)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ExpandLess,
                                contentDescription = null
                            )
                        }
                    }
                }
            }
            if (!collapsed) {
                subtitle?.let { sub ->
                    Text(
                        text = sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val progressModifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                if (clampedProgress == null) {
                    LinearProgressIndicator(modifier = progressModifier)
                } else {
                    LinearProgressIndicator(
                        progress = { clampedProgress },
                        modifier = progressModifier,
                        showStopIndicator = false
                    )
                }
            }
        }
    }
}
