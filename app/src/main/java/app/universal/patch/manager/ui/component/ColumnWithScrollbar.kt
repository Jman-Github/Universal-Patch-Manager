package app.universal.patch.manager.ui.component

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

@Composable
fun ColumnWithScrollbar(
    modifier: Modifier = Modifier,
    state: ScrollState = rememberScrollState(),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    thumbLength: Dp? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(state),
            verticalArrangement = verticalArrangement,
            horizontalAlignment = horizontalAlignment,
            content = content
        )
        Scrollbar(
            state,
            Modifier
                .align(Alignment.CenterEnd),
            thumbLength = thumbLength
        )
    }
}
