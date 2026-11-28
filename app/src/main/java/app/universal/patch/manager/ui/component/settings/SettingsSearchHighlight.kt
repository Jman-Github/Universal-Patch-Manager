package app.universal.patch.manager.ui.component.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun SettingsSearchHighlight(
    targetKey: Int,
    activeKey: Int?,
    extraKeys: Set<Int> = emptySet(),
    onHighlightComplete: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(vertical = 2.dp),
    content: @Composable (Modifier) -> Unit
) {
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val highlight = remember { Animatable(0f) }
    val highlightColor = MaterialTheme.colorScheme.primary
    val isActive = activeKey == targetKey || (activeKey != null && extraKeys.contains(activeKey))

    LaunchedEffect(isActive) {
        if (!isActive) {
            highlight.snapTo(0f)
            return@LaunchedEffect
        }
        delay(120)
        bringIntoViewRequester.bringIntoView()
        highlight.snapTo(0.18f)
        highlight.animateTo(0f, animationSpec = tween(durationMillis = 1400))
        onHighlightComplete()
    }

    content(
        Modifier
            .padding(contentPadding)
            .bringIntoViewRequester(bringIntoViewRequester)
            .drawWithContent {
                drawContent()
                val alpha = highlight.value
                if (alpha > 0f) {
                    drawRoundRect(
                        color = highlightColor,
                        cornerRadius = CornerRadius(14.dp.toPx()),
                        alpha = alpha
                    )
                }
            }
    )
}
