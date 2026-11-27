package app.universal.patch.manager.ui.component.design

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Fades content out as it scrolls under a pinned header instead of cutting it off on a hard line.
 * The fade grows with the scroll offset, so nothing changes while the list rests at the top.
 */
internal fun Modifier.topScrollFade(scrolledPx: () -> Int, length: Dp = 24.dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = minOf(scrolledPx().toFloat(), length.toPx())
        if (fade > 0f) {
            drawRect(
                brush = Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, endY = fade),
                blendMode = BlendMode.DstIn
            )
        }
    }

/**
 * Fades the edges of a horizontally scrolling row that has more to show, so a row clipped by its
 * card reads as scrollable instead of cut off. Apply it outside the scroll modifier.
 */
internal fun Modifier.horizontalScrollFade(state: ScrollState, length: Dp = 24.dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = length.toPx()
        val before = minOf(state.value.toFloat(), fade)
        val after = minOf((state.maxValue - state.value).toFloat(), fade)
        // Scroll value counts from the start edge, which is the right edge in RTL layouts.
        val (left, right) = if (layoutDirection == LayoutDirection.Rtl) after to before else before to after
        if (left > 0f) {
            drawRect(
                brush = Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black, endX = left),
                blendMode = BlendMode.DstIn
            )
        }
        if (right > 0f) {
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Black, 1f to Color.Transparent,
                    startX = size.width - right, endX = size.width
                ),
                blendMode = BlendMode.DstIn
            )
        }
    }

/**
 * Fades both ends of a vertically scrolling area that has more content past them, so rows are
 * never cut off on a hard line. Apply it outside the scroll modifier.
 */
internal fun Modifier.verticalScrollFade(state: ScrollState, length: Dp = 24.dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = length.toPx()
        val top = minOf(state.value.toFloat(), fade)
        val bottom = minOf((state.maxValue - state.value).toFloat(), fade)
        if (top > 0f) {
            drawRect(
                brush = Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, endY = top),
                blendMode = BlendMode.DstIn
            )
        }
        if (bottom > 0f) {
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Black, 1f to Color.Transparent,
                    startY = size.height - bottom, endY = size.height
                ),
                blendMode = BlendMode.DstIn
            )
        }
    }
