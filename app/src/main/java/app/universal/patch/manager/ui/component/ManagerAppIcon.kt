package app.universal.patch.manager.ui.component

import android.graphics.drawable.Drawable
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universal.patch.manager.util.ManagerAppIcon
import app.universal.patch.manager.util.managerAppIcons
import com.google.accompanist.drawablepainter.rememberDrawablePainter

@Composable
internal fun rememberSelectedManagerIcon(): ManagerAppIcon {
    val icon by LocalContext.current.managerAppIcons.selected.collectAsStateWithLifecycle()
    return icon
}

@Composable
internal fun ManagerIconImage(
    contentDescription: String?,
    modifier: Modifier = Modifier,
    icon: ManagerAppIcon = rememberSelectedManagerIcon()
) {
    val context = LocalContext.current
    val drawable: Drawable? = remember(context, icon) {
        AppCompatResources.getDrawable(context, icon.icon)
    }
    Image(
        painter = rememberDrawablePainter(drawable),
        contentDescription = contentDescription,
        modifier = modifier
    )
}
