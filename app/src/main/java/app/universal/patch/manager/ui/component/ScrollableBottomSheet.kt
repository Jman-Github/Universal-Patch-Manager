package app.universal.patch.manager.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScrollableBottomSheet(
    onDismissRequest: () -> Unit,
    maxHeightFraction: Float = 0.85f,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    content: @Composable () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        // Let the list handle swipes, including when it reaches either end.
        sheetGesturesEnabled = false,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val safeDrawing = WindowInsets.safeDrawing
            // Keep the sheet below the top inset without measuring its moving offset.
            val safeHeight = (maxHeight - safeDrawing.asPaddingValues().calculateTopPadding())
                .coerceAtLeast(0.dp)
            val heightLimit = minOf(maxHeight * maxHeightFraction, safeHeight)
            // Limit the body inside the sheet so the anchors use the full viewport.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = heightLimit)
                    .windowInsetsPadding(
                        safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                    )
                    .padding(top = 24.dp)
            ) {
                content()
            }
        }
    }
}
