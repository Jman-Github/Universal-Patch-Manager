/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.universal.patch.manager.ui.component.patcher

import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import app.universal.patch.manager.R
import app.universal.patch.manager.patcher.worker.PatcherMemoryUsage
import app.universal.patch.manager.patcher.worker.latestResourceUsage
import kotlinx.coroutines.launch
import kotlin.math.ceil

@Composable
fun PatcherMemoryUsageCard(
    samples: List<PatcherMemoryUsage>,
    isActive: Boolean,
    @StringRes titleRes: Int = R.string.patcher_heap_memory_usage,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    showExtraInfo: Boolean = false,
    layout: ResourceGraphLayout? = null,
    pageControls: (@Composable () -> Unit)? = null
) {
    if (samples.isEmpty()) return
    val latest = samples.last()
    // Heap pressure must use the worker's actual heap limit, not process RSS or
    // the requested limit before a startup fallback. Native memory is additional.
    val heapMaxMb = latest.maxMb.coerceAtLeast(1L)
    val processMemory = samples.latestResourceUsage()?.processMemoryMb
    val peakMb = samples.maxOf { it.usedMb }
    val trend = stringResource(memoryTrendRes(samples, heapMaxMb))
    val status = stringResource(if (isActive) R.string.patcher_memory_usage_live else R.string.patcher_memory_usage_final)
    val processDetail = processMemory?.let {
        stringResource(R.string.patcher_heap_process_detail, it)
    }
    val accessibilityText = stringResource(
        R.string.patcher_memory_usage_accessibility,
        latest.usedMb, heapMaxMb, peakMb, trend, status
    ) + (processDetail?.let { " $it" } ?: "")
    PatcherHistoryUsageCard(
        samples = samples,
        isActive = isActive,
        title = stringResource(titleRes),
        headline = stringResource(R.string.patcher_memory_usage_format, latest.usedMb, heapMaxMb),
        detail = processDetail,
        peak = stringResource(R.string.patcher_memory_usage_peak_format, peakMb),
        accessibilityText = accessibilityText,
        graphBars = buildMemoryGraphBars(samples, heapMaxMb),
        modifier = modifier,
        compact = compact,
        showExtraInfo = showExtraInfo,
        layout = layout,
        pageControls = pageControls
    )
}

@Composable
internal fun PatcherHistoryUsageCard(
    samples: List<PatcherMemoryUsage>,
    isActive: Boolean,
    title: String,
    headline: String,
    peak: String,
    accessibilityText: String,
    graphBars: List<ResourceGraphBar>,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    detail: String? = null,
    scrollableDetailLines: List<String> = emptyList(),
    coreLoads: List<Int> = emptyList(),
    currentAvailable: Boolean = true,
    showExtraInfo: Boolean = false,
    showHistory: Boolean = true,
    layout: ResourceGraphLayout? = null,
    pageControls: (@Composable () -> Unit)? = null
) {
    val status = stringResource(
        when {
            !currentAvailable -> R.string.patcher_resource_unavailable_short
            isActive -> R.string.patcher_memory_usage_live
            else -> R.string.patcher_memory_usage_final
        }
    )
    val visibleAccessibilityText = if (showExtraInfo) accessibilityText else stringResource(
        R.string.resource_graph_current_accessibility, title, headline, status
    )
    val graphScrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val sessionStartTime = samples.first().sampledAtElapsedRealtimeMs
    val latestSampleTime = samples.last().sampledAtElapsedRealtimeMs
    val isGraphDragged by graphScrollState.interactionSource.collectIsDraggedAsState()
    var followLatest by rememberSaveable(sessionStartTime) { mutableStateOf(true) }
    var programmaticScrollCount by remember(sessionStartTime) { mutableIntStateOf(0) }
    val showJumpToLatest = showHistory && !followLatest
    SideEffect { layout?.onLatestControlChanged?.invoke(showJumpToLatest) }
    val historyScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ) = Offset(available.x, 0f)

            override suspend fun onPostFling(consumed: Velocity, available: Velocity) =
                Velocity(available.x, 0f)
        }
    }
    val density = LocalDensity.current
    val normalColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.82f)
    val warningColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.9f)
    val dangerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.9f)
    val trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)

    LaunchedEffect(graphScrollState, sessionStartTime) {
        var userScrollObserved = false
        snapshotFlow {
            Triple(
                isGraphDragged,
                graphScrollState.isScrollInProgress,
                graphScrollState.canScrollForward
            ) to (programmaticScrollCount > 0)
        }.collect { (scroll, isProgrammatic) ->
            val (isDragged, isScrolling, canScrollForward) = scroll
            val isUserScrolling = !isProgrammatic && (isDragged || isScrolling)
            if (isUserScrolling && canScrollForward) {
                userScrollObserved = true
                followLatest = false
            } else if (userScrollObserved && !isScrolling) {
                if (!canScrollForward) {
                    followLatest = true
                }
                userScrollObserved = false
            }
        }
    }
    // Resizing changes the history's end even when a finished session has no new samples.
    LaunchedEffect(graphScrollState, sessionStartTime, latestSampleTime, graphScrollState.maxValue) {
        if (!followLatest) return@LaunchedEffect
        withFrameNanos { }
        if (followLatest && !isGraphDragged) {
            programmaticScrollCount++
            try {
                graphScrollState.scrollTo(graphScrollState.maxValue)
            } finally {
                programmaticScrollCount = (programmaticScrollCount - 1).coerceAtLeast(0)
            }
        }
    }

    val jumpToLatest: () -> Unit = {
        coroutineScope.launch {
            var reachedLatest = false
            programmaticScrollCount++
            try {
                withFrameNanos { }
                graphScrollState.scrollTo(graphScrollState.maxValue)
                reachedLatest = !graphScrollState.canScrollForward
            } finally {
                programmaticScrollCount = (programmaticScrollCount - 1).coerceAtLeast(0)
                followLatest = reachedLatest
            }
        }
    }

    ElevatedCard(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (compact) 8.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp)
        ) {
            ResourceGraphSection(
                minHeight = layout?.headerHeight,
                onMeasured = layout?.onHeaderMeasured,
                onPlaced = layout?.onHeaderPlaced,
                compact = compact
            ) {
                pageControls?.invoke()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        modifier = Modifier.weight(1f),
                        style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (!compact) {
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = status,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isActive && currentAvailable) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
                if (compact) {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isActive && currentAvailable) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = headline,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (scrollableDetailLines.isNotEmpty()) {
                    Column {
                        scrollableDetailLines.forEach { line ->
                            Text(
                                text = line,
                                modifier = Modifier.fillMaxWidth()
                                    .nestedScroll(historyScrollConnection)
                                    .horizontalScroll(rememberScrollState()),
                                softWrap = false,
                                maxLines = 1,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (showHistory) detail?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (showHistory && coreLoads.isNotEmpty()) {
                    CpuCoreLoadBars(loads = coreLoads, historyScrollConnection = historyScrollConnection)
                }
            }
            ResourceGraphSection(
                minHeight = layout?.plotHeight,
                onMeasured = layout?.onPlotMeasured,
                onPlaced = layout?.onPlotPlaced,
                compact = compact,
                contentAlignment = Alignment.BottomStart
            ) {
                if (showHistory) {
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        // Reveal only after the current viewport and initial history position are measured.
                        val viewportWidthPx = with(density) { maxWidth.roundToPx() }
                        val historyPrepared = graphScrollState.viewportSize == viewportWidthPx &&
                            viewportWidthPx > 0 && (!followLatest || !graphScrollState.canScrollForward)
                        SideEffect { layout?.onHistoryReady?.invoke(historyPrepared) }
                        // Keep the same bar pitch in every layout; narrow cards show less history at once.
                        val visibleSlots = ceil(maxWidth / RESOURCE_HISTORY_BAR_SLOT_WIDTH).toInt().coerceAtLeast(1)
                        val slotCount = maxOf(visibleSlots, graphBars.size)
                        val graphWidth = RESOURCE_HISTORY_BAR_SLOT_WIDTH * slotCount
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .nestedScroll(historyScrollConnection)
                                .horizontalScroll(graphScrollState)
                        ) {
                            ResourceHistoryPlot(
                                bars = graphBars,
                                slotCount = slotCount,
                                normalColor = normalColor,
                                warningColor = warningColor,
                                dangerColor = dangerColor,
                                trackColor = trackColor,
                                modifier = Modifier.width(graphWidth).height(64.dp)
                                    .clearAndSetSemantics { contentDescription = visibleAccessibilityText }
                            )
                        }
                    }
                } else {
                    SideEffect { layout?.onHistoryReady?.invoke(true) }
                    detail?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (coreLoads.isNotEmpty()) {
                        CpuCoreLoadBars(loads = coreLoads, historyScrollConnection = historyScrollConnection)
                    }
                }
            }
            if (showExtraInfo) {
                ResourceGraphSection(
                    minHeight = layout?.footerHeight,
                    onMeasured = layout?.onFooterMeasured,
                    onPlaced = layout?.onFooterPlaced,
                    compact = compact
                ) {
                    if (compact) {
                        // The caption reserves its natural height even while Latest fades over it.
                        val historyCaption = stringResource(R.string.patcher_memory_usage_history)
                        Box(Modifier.fillMaxWidth().heightIn(min = 32.dp)) {
                            Text(
                                text = historyCaption,
                                modifier = Modifier.fillMaxWidth().alpha(0f).clearAndSetSemantics { },
                                style = MaterialTheme.typography.bodySmall
                            )
                            Crossfade(
                                targetState = showJumpToLatest,
                                modifier = Modifier.matchParentSize(),
                                animationSpec = tween(180),
                                label = "resource_latest"
                            ) { showLatest ->
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                                    if (showLatest) {
                                        TextButton(
                                            onClick = jumpToLatest,
                                            modifier = Modifier.height(32.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp)
                                        ) {
                                            Text(stringResource(R.string.patcher_resource_latest_compact))
                                        }
                                    } else {
                                        Text(
                                            text = historyCaption,
                                            modifier = Modifier.fillMaxWidth(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                        Text(
                            text = peak,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.patcher_memory_usage_history),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            AnimatedVisibility(
                                visible = showJumpToLatest,
                                enter = expandHorizontally(expandFrom = Alignment.End) +
                                    slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                                exit = shrinkHorizontally(shrinkTowards = Alignment.End) +
                                    slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(
                                        onClick = jumpToLatest,
                                        modifier = Modifier.height(32.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp)
                                    ) {
                                        Text(stringResource(R.string.patcher_memory_usage_latest))
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                            }
                            Text(
                                text = peak,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else if (showJumpToLatest || layout?.showLatestControls == true) {
                ResourceGraphSection(
                    minHeight = layout?.footerHeight,
                    onMeasured = layout?.onFooterMeasured,
                    onPlaced = layout?.onFooterPlaced,
                    compact = compact
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        if (showJumpToLatest) {
                            TextButton(
                                onClick = jumpToLatest,
                                modifier = Modifier.height(32.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Text(
                                    stringResource(
                                        if (compact) R.string.patcher_resource_latest_compact
                                        else R.string.patcher_memory_usage_latest
                                    )
                                )
                            }
                        }
                    }
                }
            } else {
                SideEffect {
                    layout?.onFooterMeasured?.invoke(0)
                    layout?.onFooterPlaced?.invoke(0)
                }
            }
        }
    }
}

@StringRes
private fun memoryTrendRes(
    samples: List<PatcherMemoryUsage>,
    requestedMaxMb: Long
): Int {
    if (samples.size < TREND_SAMPLE_COUNT * 2) {
        return R.string.patcher_memory_usage_trend_steady
    }
    val recent = samples.takeLast(TREND_SAMPLE_COUNT)
    val previous = samples.dropLast(TREND_SAMPLE_COUNT).takeLast(TREND_SAMPLE_COUNT)
    val recentAverage = recent.map { it.usedMb }.average()
    val previousAverage = previous.map { it.usedMb }.average()
    val threshold = requestedMaxMb * TREND_CHANGE_FRACTION
    return when {
        recentAverage - previousAverage > threshold ->
            R.string.patcher_memory_usage_trend_rising
        previousAverage - recentAverage > threshold ->
            R.string.patcher_memory_usage_trend_falling
        else -> R.string.patcher_memory_usage_trend_steady
    }
}

private fun buildMemoryGraphBars(
    samples: List<PatcherMemoryUsage>,
    requestedMaxMb: Long
): List<ResourceGraphBar> {
    val bars = ArrayList<ResourceGraphBar>(samples.size)
    var rollingStartIndex = 0
    var rollingUsedMb = 0.0
    samples.forEachIndexed { index, sample ->
        rollingUsedMb += sample.usedMb.toDouble()
        val rollingStartTime = sample.sampledAtElapsedRealtimeMs - PRESSURE_ROLLING_WINDOW_MS
        while (
            rollingStartIndex < index &&
            samples[rollingStartIndex].sampledAtElapsedRealtimeMs < rollingStartTime
        ) {
            rollingUsedMb -= samples[rollingStartIndex].usedMb.toDouble()
            rollingStartIndex++
        }
        val rollingSampleCount = index - rollingStartIndex + 1
        bars += ResourceGraphBar(
            heightFraction = (
                sample.usedMb.toDouble() / requestedMaxMb.toDouble()
            ).toFloat().coerceIn(0f, 1f),
            pressureFraction = (
                (rollingUsedMb / rollingSampleCount.toDouble()) /
                    sample.maxMb.coerceAtLeast(1L).toDouble()
            ).toFloat().coerceAtLeast(0f)
        )
    }
    return bars
}

internal data class ResourceGraphBar(
    val heightFraction: Float,
    val pressureFraction: Float
)

/** Draw history in the same Compose frame as the card and its measured scroll viewport. */
@Composable
private fun ResourceHistoryPlot(
    bars: List<ResourceGraphBar>,
    slotCount: Int,
    normalColor: Color,
    warningColor: Color,
    dangerColor: Color,
    trackColor: Color,
    modifier: Modifier
) {
    Canvas(modifier) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val count = maxOf(slotCount, bars.size, 1)
        val gap = minOf(3.dp.toPx(), size.width / (count * 3f))
        val barWidth = ((size.width - gap * (count - 1)) / count).coerceAtLeast(0.5f)

        repeat(count) { index ->
            val left = index * (barWidth + gap)
            val width = minOf(size.width - left, barWidth).coerceAtLeast(0f)
            val radius = CornerRadius(barWidth / 2f, barWidth / 2f)
            drawRoundRect(trackColor, Offset(left, 0f), Size(width, size.height), radius)

            val barIndex = index - (count - bars.size)
            val bar = bars.getOrNull(barIndex) ?: return@repeat
            if (bar.heightFraction <= 0f) return@repeat
            val barHeight = size.height * bar.heightFraction.coerceIn(0f, 1f)
            val color = when {
                bar.pressureFraction >= DANGER_PRESSURE_FRACTION -> dangerColor
                bar.pressureFraction >= WARNING_PRESSURE_FRACTION -> warningColor
                else -> normalColor
            }
            drawRoundRect(
                color, Offset(left, size.height - barHeight), Size(width, barHeight), radius
            )
        }
    }
}

private val RESOURCE_HISTORY_BAR_SLOT_WIDTH = 4.dp
private const val PRESSURE_ROLLING_WINDOW_MS = 2_000L
private const val TREND_SAMPLE_COUNT = 3
private const val TREND_CHANGE_FRACTION = 0.03
private const val WARNING_PRESSURE_FRACTION = 0.70f
private const val DANGER_PRESSURE_FRACTION = 0.85f


// Code adapted from Morphe, see third-party/NOTICE for more information.
// https://github.com/MorpheApp/morphe-manager/blob/dcdce54ba920532f90204617fe7710c9b68d1dd9/app/src/main/java/app/morphe/manager/ui/screen/patcher/PatchingUsageGraphs.kt
@Composable
private fun CpuCoreLoadBars(loads: List<Int>, historyScrollConnection: NestedScrollConnection) {
    val normal = MaterialTheme.colorScheme.primary
    val warning = MaterialTheme.colorScheme.error
    val track = MaterialTheme.colorScheme.surfaceVariant
    val coreDescriptions = loads.mapIndexed { core, load ->
        stringResource(R.string.patcher_cpu_core_accessibility, core, load)
    }.joinToString(", ")
    // Preserve core indices, including idle cores, instead of sorting readings by load.
    val fractions = loads.mapIndexed { core, load ->
        key(core) {
            animateFloatAsState(
                targetValue = load.coerceIn(0, 100) / 100f,
                animationSpec = tween(600),
                label = "cpu_core_load"
            ).value
        }
    }
    val scrollState = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val graphWidth = maxOf(maxWidth, 20.dp * loads.size)
        Box(
            Modifier.fillMaxWidth().nestedScroll(historyScrollConnection).horizontalScroll(scrollState)
        ) {
            Canvas(
                modifier = Modifier.width(graphWidth).height(36.dp)
                    .clearAndSetSemantics { contentDescription = coreDescriptions }
            ) {
                if (fractions.isEmpty()) return@Canvas
                val slotWidth = size.width / fractions.size
                val barWidth = 12.dp.toPx()
                fractions.forEachIndexed { core, fraction ->
                    val x = core * slotWidth + (slotWidth - barWidth) / 2f
                    drawRect(track, Offset(x, 0f), Size(barWidth, size.height))
                    val height = size.height * fraction
                    drawRect(
                        lerp(normal, warning, ((fraction - 0.7f) / 0.3f).coerceIn(0f, 1f)),
                        Offset(x, size.height - height),
                        Size(barWidth, height)
                    )
                }
            }
        }
    }
}
