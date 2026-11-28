package app.universal.patch.manager.ui.component.bundle

import app.universal.patch.manager.ui.component.AppTopBar
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.universal.patch.manager.util.consumeHorizontalScroll

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BundleTopBar(
    title: String,
    onBackClick: (() -> Unit)? = null,
    actions: @Composable (RowScope.() -> Unit) = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    backIcon: @Composable () -> Unit,
) {
    val scrollState = rememberScrollState()
    AppTopBar(
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                modifier = Modifier
                    .consumeHorizontalScroll(scrollState)
            )
        },
        scrollBehavior = scrollBehavior,
        onBackClick = onBackClick,
        backIcon = backIcon,
        actions = actions,
        applyContainerColor = true
    )
}
