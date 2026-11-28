package app.universal.patch.manager.ui.component

import androidx.compose.ui.graphics.takeOrElse
import app.universal.patch.manager.ui.component.design.CircularProgressIndicator
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import app.universal.patch.manager.ui.theme.LocalExpressiveDesign

@Composable
fun LoadingIndicator(
    modifier: Modifier = Modifier,
    progress: () -> Float? = { null },
    color: Color = Color.Unspecified,
    strokeWidth: Dp = ProgressIndicatorDefaults.CircularStrokeWidth,
    trackColor: Color = Color.Unspecified,
    strokeCap: StrokeCap = ProgressIndicatorDefaults.CircularDeterminateStrokeCap
) {
    val value = progress()
    if (value != null) {
        CircularProgressIndicator(
            progress = { value },
            modifier = modifier,
            color = color.takeOrElse { ProgressIndicatorDefaults.circularColor },
            strokeWidth = strokeWidth,
            // Classic progress rings have always drawn without a track; the wavy expressive one shows it.
            trackColor = trackColor.takeOrElse {
                if (LocalExpressiveDesign.current) ProgressIndicatorDefaults.circularDeterminateTrackColor
                else Color.Transparent
            },
            strokeCap = strokeCap
        )
    } else {
        CircularProgressIndicator(
            modifier = modifier,
            color = color,
            strokeWidth = strokeWidth,
            trackColor = trackColor,
            strokeCap = strokeCap
        )
    }
}
