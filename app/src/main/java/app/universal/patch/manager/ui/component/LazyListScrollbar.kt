package app.universal.patch.manager.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import app.universal.patch.manager.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

@Composable
internal fun LazyListScrollbar(
    state: LazyListState,
    modifier: Modifier,
    prominent: Boolean,
    thumbLength: Dp? = DASHBOARD_SCROLLBAR_THUMB_LENGTH
) {
    val measurements = remember(state) { LazyScrollbarMeasurements() }
    val geometry by remember(state) {
        derivedStateOf {
            val layout = state.layoutInfo
            val viewport = (layout.viewportEndOffset - layout.viewportStartOffset).coerceAtLeast(1)
            lazyScrollbarGeometry(
                measurements.sizes(layout.totalItemsCount, layout.visibleItemsInfo.map {
                    LazyScrollbarSample(it.index, it.key, it.size)
                }, viewport / 3),
                state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset,
                layout.mainAxisItemSpacing, layout.beforeContentPadding, layout.afterContentPadding,
                viewport, state.canScrollBackward, state.canScrollForward, layout.reverseLayout)
        }
    }
    val currentGeometry by rememberUpdatedState(geometry)
    val targets = remember(state) { Channel<Pair<Int, Int>>(Channel.CONFLATED) }
    LaunchedEffect(state) {
        for ((index, offset) in targets) {
            val count = state.layoutInfo.totalItemsCount
            if (count > 0) try {
                state.scrollToItem(index.coerceIn(0, count - 1), offset)
            } catch (cancel: CancellationException) {
                // A competing list gesture may interrupt a scroll without disposing
                // this scrollbar. Keep consuming later drag targets in that case.
                currentCoroutineContext().ensureActive()
            }
        }
    }
    var dragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    var dragGeometry by remember { mutableStateOf<LazyScrollbarGeometry?>(null) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(state.isScrollInProgress, dragging) {
        if (state.isScrollInProgress || dragging) visible = true
        else { delay(900); visible = false }
    }
    val alpha by animateFloatAsState(
        if (geometry.scrollable && (visible || prominent)) if (prominent) 0.6f else 0.35f else 0f,
        label = "scrollbar_visibility")
    val minimum = with(LocalDensity.current) { 24.dp.toPx() }
    val fixedThumb = with(LocalDensity.current) { thumbLength?.toPx() }
    val thickness = with(LocalDensity.current) { (if (prominent) 6.dp else 4.dp).toPx() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val label = stringResource(R.string.scroll_position)
    val color = MaterialTheme.colorScheme.onSurface

    // Invisible, non-scrollable tracks must not intercept controls underneath them.
    if (!geometry.scrollable) return

    Canvas(modifier.width(LAZY_SCROLLBAR_TOUCH_WIDTH).fillMaxHeight()
        .semantics {
            if (geometry.scrollable) {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(geometry.progress, 0f..1f)
                setProgress { fraction -> targets.trySend(geometry.target(fraction)).isSuccess }
            }
        }
        .pointerInput(state, minimum, fixedThumb) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val initial = currentGeometry
                if (!initial.scrollable) return@awaitEachGesture
                val track = size.height.toFloat()
                val thumb = initial.thumbSize(track, minimum, fixedThumb)
                val travel = track - thumb
                if (travel <= 0f) return@awaitEachGesture
                val top = initial.progress * travel
                val grab = if (down.position.y in top..top + thumb) down.position.y - top else thumb / 2
                dragging = true
                dragGeometry = initial
                dragProgress = ((down.position.y - grab) / travel).coerceIn(0f, 1f)
                down.consume()
                targets.trySend(initial.target(dragProgress))
                try {
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        dragProgress = ((change.position.y - grab) / travel).coerceIn(0f, 1f)
                        change.consume()
                        targets.trySend(initial.target(dragProgress))
                    }
                } finally {
                    dragging = false
                    dragGeometry = null
                }
            }
        }) {
        val model = dragGeometry ?: geometry
        if (!model.scrollable) return@Canvas
        val thumb = model.thumbSize(size.height, minimum, fixedThumb)
        val progress = if (dragging) dragProgress else model.progress
        drawRoundRect(
            color = color,
            topLeft = Offset(if (rtl) 0f else size.width - thickness, progress * (size.height - thumb)),
            size = Size(thickness, thumb), cornerRadius = CornerRadius(thickness / 2),
            alpha = alpha)
    }
}
