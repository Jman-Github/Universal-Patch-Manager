package app.universal.patch.manager.ui.component.patcher

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Keeps pinned graphs separate from the steps without crowding out progress on small screens. */
@Composable
fun ResourceUsageProgressLayout(
    pinGraphs: Boolean,
    graphContent: @Composable () -> Unit,
    progressContent: @Composable (Modifier) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val graphMaxHeight = maxHeight * 0.45f
        Column(Modifier.fillMaxSize()) {
            if (pinGraphs) {
                Column(
                    Modifier.fillMaxWidth()
                        .heightIn(max = graphMaxHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    graphContent()
                }
            }
            progressContent(Modifier.fillMaxWidth().weight(1f))
        }
    }
}
