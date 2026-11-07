package app.universal.patch.manager.ui.component

import android.content.pm.PackageInfo
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

/** Read downloaded app icons from their APK; release previews come from that same resource. */
@Composable
fun RuntimePluginIcon(apk: File?, pm: PM, previewUrl: String?, modifier: Modifier = Modifier) {
    val packageInfo by key(apk?.path) {
        produceState<PackageInfo?>(null, apk?.path) {
            value = withContext(Dispatchers.IO) { apk?.let { runCatching { pm.getPackageInfo(it) }.getOrNull() } }
        }
    }
    if (packageInfo != null) {
        AppIcon(packageInfo, null, modifier, cacheKey = apk?.path, packageName = null)
    } else {
        val fallback = rememberVectorPainter(Icons.Default.Android)
        AsyncImage(model = previewUrl, contentDescription = null, modifier = modifier,
            placeholder = fallback, error = fallback, fallback = fallback)
    }
}
