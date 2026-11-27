package app.universal.patch.manager.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import app.universal.patch.manager.ui.theme.LocalExpressiveDesign
import app.universal.patch.manager.ui.theme.contrastingForeground

private val properties = DialogProperties(
    usePlatformDefaultWidth = false,
    dismissOnBackPress = true,
    decorFitsSystemWindows = false,
)

@Composable
fun FullscreenDialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = properties
    ) {
        val view = LocalView.current
        val contentBackgroundColor = MaterialTheme.colorScheme.surface
        val topBarColor = if (LocalExpressiveDesign.current) {
            contentBackgroundColor
        } else {
            MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
        }
        val useDarkStatusBarIcons = topBarColor.contrastingForeground() == Color.Black
        val useDarkNavigationBarIcons = contentBackgroundColor.contrastingForeground() == Color.Black
        LaunchedEffect(
            topBarColor,
            contentBackgroundColor,
            useDarkStatusBarIcons,
            useDarkNavigationBarIcons
        ) {
            val window = (view.parent as DialogWindowProvider).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = contentBackgroundColor.toArgb()

            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = useDarkStatusBarIcons
            insetsController.isAppearanceLightNavigationBars = useDarkNavigationBarIcons
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(contentBackgroundColor)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                    )
                )
        ) {
            content()
        }
    }
}
