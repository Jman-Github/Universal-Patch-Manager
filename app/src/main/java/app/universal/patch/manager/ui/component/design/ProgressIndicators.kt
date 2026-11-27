@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.universal.patch.manager.ui.component.design

import androidx.compose.material3.LoadingIndicatorDefaults
import androidx.compose.material3.CircularProgressIndicator as MaterialCircularProgress
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.LinearProgressIndicator as MaterialLinearProgress
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.universal.patch.manager.ui.theme.LocalExpressiveDesign

@Composable
internal fun CircularProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    strokeWidth: Dp = ProgressIndicatorDefaults.CircularStrokeWidth,
    trackColor: Color = Color.Unspecified,
    strokeCap: StrokeCap = ProgressIndicatorDefaults.CircularIndeterminateStrokeCap
) {
    if (LocalExpressiveDesign.current) {
        // Inline indicators inherit a foreground chosen for their host button or badge.
        val container = trackColor.takeOrElse {
            if (color == Color.Unspecified) LoadingIndicatorDefaults.containedContainerColor else Color.Transparent
        }
        ContainedLoadingIndicator(
            modifier = modifier,
            indicatorColor = color.takeOrElse { LoadingIndicatorDefaults.containedIndicatorColor },
            containerColor = container
        )
    } else {
        MaterialCircularProgress(
            modifier, color.takeOrElse { ProgressIndicatorDefaults.circularColor }, strokeWidth,
            trackColor.takeOrElse { ProgressIndicatorDefaults.circularIndeterminateTrackColor }, strokeCap
        )
    }
}

@Composable
internal fun CircularProgressIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = ProgressIndicatorDefaults.circularColor,
    strokeWidth: Dp = ProgressIndicatorDefaults.CircularStrokeWidth,
    trackColor: Color = ProgressIndicatorDefaults.circularDeterminateTrackColor,
    strokeCap: StrokeCap = ProgressIndicatorDefaults.CircularDeterminateStrokeCap
) {
    if (LocalExpressiveDesign.current) {
        val stroke = with(LocalDensity.current) { Stroke(width = strokeWidth.toPx(), cap = strokeCap) }
        CircularWavyProgressIndicator(
            progress = progress, modifier = modifier, color = color,
            trackColor = trackColor, stroke = stroke, trackStroke = stroke
        )
    } else {
        MaterialCircularProgress(progress, modifier, color, strokeWidth, trackColor, strokeCap)
    }
}

@Composable
internal fun LinearProgressIndicator(modifier: Modifier = Modifier) {
    if (LocalExpressiveDesign.current) LinearWavyProgressIndicator(modifier)
    else MaterialLinearProgress(modifier)
}

@Composable
internal fun LinearProgressIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    showStopIndicator: Boolean = true
) {
    if (LocalExpressiveDesign.current) {
        LinearWavyProgressIndicator(
            progress = progress, modifier = modifier,
            stopSize = if (showStopIndicator) WavyProgressIndicatorDefaults.LinearTrackStopIndicatorSize else 0.dp
        )
    } else if (showStopIndicator) {
        MaterialLinearProgress(progress, modifier)
    } else {
        MaterialLinearProgress(progress, modifier, drawStopIndicator = {})
    }
}
