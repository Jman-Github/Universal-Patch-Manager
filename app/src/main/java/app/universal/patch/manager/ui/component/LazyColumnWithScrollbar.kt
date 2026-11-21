package app.universal.patch.manager.ui.component

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp

internal val DASHBOARD_SCROLLBAR_THUMB_LENGTH = 64.dp
internal val LAZY_SCROLLBAR_TOUCH_WIDTH = 24.dp

@Composable
fun LazyColumnWithScrollbar(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    reverseLayout: Boolean = false,
    verticalArrangement: Arrangement.Vertical =
        if (!reverseLayout) Arrangement.Top else Arrangement.Bottom,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    flingBehavior: FlingBehavior = ScrollableDefaults.flingBehavior(),
    userScrollEnabled: Boolean = true,
    thumbLength: Dp? = DASHBOARD_SCROLLBAR_THUMB_LENGTH,
    reserveScrollbarSpace: Boolean = false,
    content: LazyListScope.() -> Unit
) {
    Box(modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth()
                .padding(end = if (reserveScrollbarSpace) LAZY_SCROLLBAR_TOUCH_WIDTH else 0.dp),
            state = state,
            contentPadding = contentPadding,
            reverseLayout = reverseLayout,
            verticalArrangement = verticalArrangement,
            horizontalAlignment = horizontalAlignment,
            flingBehavior = flingBehavior,
            userScrollEnabled = userScrollEnabled,
            content = content
        )
        // The overlay must follow the measured list, rather than force it to
        // fill all available height when this list is inside a wrapping dialog.
        Box(Modifier.matchParentSize()) {
            Scrollbar(
                state,
                Modifier.align(Alignment.CenterEnd),
                thumbLength = thumbLength
            )
        }
    }
}
