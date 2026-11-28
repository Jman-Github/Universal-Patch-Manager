package app.universal.patch.manager.ui.component

import app.universal.patch.manager.ui.component.design.IconButton
import app.universal.patch.manager.ui.theme.LocalExpressiveDesign
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import app.universal.patch.manager.R

/**
 * Every bar is a single row with the title beside the back button, so it stays pinned and only
 * picks up its scrolled container tone once content moves under it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberAppBarScrollBehavior(): TopAppBarScrollBehavior =
    TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    topBar: (@Composable (TopAppBarScrollBehavior) -> Unit)? = null,
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit
) {
    val scrollBehavior = rememberAppBarScrollBehavior()

    Scaffold(
        modifier = if (topBar == null) modifier else modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { topBar?.invoke(scrollBehavior) },
        bottomBar = bottomBar,
        floatingActionButton = floatingActionButton,
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    onBackClick: (() -> Unit)? = null,
    onBackLongClick: (() -> Unit)? = null,
    backIcon: @Composable (() -> Unit) = @Composable {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(
                R.string.back
            )
        )
    },
    actions: @Composable (RowScope.() -> Unit) = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    applyContainerColor: Boolean = false,
    onHelpClick: (() -> Unit)? = null // From PR #37: https://github.com/Jman-Github/Universal-Patch-Manager/pull/37
) {
    AppTopBarContent(
        title = { AppBarTitle(title) }, onBackClick = onBackClick,
        onBackLongClick = onBackLongClick, backIcon = backIcon, actions = actions,
        scrollBehavior = scrollBehavior, applyContainerColor = applyContainerColor, onHelpClick = onHelpClick
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: @Composable () -> Unit,
    onBackClick: (() -> Unit)? = null,
    onBackLongClick: (() -> Unit)? = null,
    backIcon: @Composable (() -> Unit) = @Composable {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(
                R.string.back
            )
        )
    },
    actions: @Composable (RowScope.() -> Unit) = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    applyContainerColor: Boolean = false,
    onHelpClick: (() -> Unit)? = null // From PR #37: https://github.com/Jman-Github/Universal-Patch-Manager/pull/37
) {
    AppTopBarContent(
        title = title, onBackClick = onBackClick,
        onBackLongClick = onBackLongClick, backIcon = backIcon, actions = actions,
        scrollBehavior = scrollBehavior, applyContainerColor = applyContainerColor, onHelpClick = onHelpClick
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTopBarContent(
    title: @Composable () -> Unit,
    onBackClick: (() -> Unit)?,
    onBackLongClick: (() -> Unit)?,
    backIcon: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    scrollBehavior: TopAppBarScrollBehavior?,
    applyContainerColor: Boolean,
    onHelpClick: (() -> Unit)?
) {
    val expressive = LocalExpressiveDesign.current
    // Expressive bars sit on the page color and only pick up a container tone once content scrolls under them.
    val colors = when {
        expressive -> TopAppBarDefaults.topAppBarColors()
        applyContainerColor -> TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
        )
        else -> TopAppBarDefaults.topAppBarColors(containerColor = Color.Unspecified)
    }
    val navigation: @Composable () -> Unit = {
        if (onBackClick != null) TopBarBackButton(onBackClick, onBackLongClick, backIcon)
    }
    val barActions: @Composable RowScope.() -> Unit = {
        if (onHelpClick != null) {
            IconButton(onClick = onHelpClick) {
                Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = stringResource(R.string.help))
            }
        }
        actions()
    }
    TopAppBar(
        title = title, navigationIcon = navigation, actions = barActions,
        colors = colors, scrollBehavior = scrollBehavior
    )
}

/** Long titles stay on the back button's row as one ellipsized line. */
@Composable
private fun AppBarTitle(title: String) {
    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun TopBarBackButton(
    onBackClick: () -> Unit,
    onBackLongClick: (() -> Unit)?,
    backIcon: @Composable () -> Unit
) {
    if (onBackLongClick == null) {
        IconButton(onClick = onBackClick) {
            backIcon()
        }
        return
    }

    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onBackClick,
                onLongClick = onBackLongClick
            ),
        contentAlignment = Alignment.Center
    ) {
        backIcon()
    }
}
