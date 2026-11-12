package app.universal.patch.manager.ui.component

import android.content.pm.PackageInfo
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.Color
import app.universal.patch.manager.R
import androidx.compose.foundation.Image
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import app.universal.patch.manager.util.PM
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Bundled downloader artwork takes priority; other plugins use APK icons or release previews. */
@Composable
fun RuntimePluginIcon(
    apk: File?,
    pm: PM,
    previewUrl: String?,
    modifier: Modifier = Modifier,
    cacheKey: String? = null,
    @DrawableRes previewResource: Int? = null
) {
    if (previewResource != null) {
        val context = LocalContext.current
        val drawable = remember(context, previewResource) {
            AppCompatResources.getDrawable(context, previewResource)
        }
        // A square with side 70% of the diameter fits entirely inside a circle.
        // Keep the full artwork visible instead of cutting its corners with the mask.
        val background = if (previewResource == R.drawable.ic_downloader_catalog_helper) {
            Color(0xFF0A1610)
        } else Color.White
        Box(modifier.clip(CircleShape).background(background)) {
            Image(
                painter = rememberDrawablePainter(drawable),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(0.70f).align(Alignment.Center),
                contentScale = ContentScale.Fit
            )
        }
        return
    }
    val iconKey = cacheKey ?: apk?.path
    val packageInfo by key(apk?.path, iconKey) {
        produceState<PackageInfo?>(null, apk?.path, iconKey) {
            value = withContext(Dispatchers.IO) { apk?.let { runCatching { pm.getPackageInfo(it) }.getOrNull() } }
        }
    }
    if (packageInfo != null) {
        AppIcon(packageInfo, null, modifier, cacheKey = iconKey, packageName = null)
    } else {
        val fallback = rememberVectorPainter(Icons.Default.Android)
        AsyncImage(model = previewUrl, contentDescription = null, modifier = modifier,
            placeholder = fallback, error = fallback, fallback = fallback)
    }
}
