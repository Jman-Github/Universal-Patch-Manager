package app.universal.patch.manager.ui.component

import android.content.Context
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universal.patch.manager.domain.repository.AppNameRepository
import app.universal.patch.manager.domain.repository.appNamePackagePattern
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import org.koin.compose.koinInject
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import io.github.fornewid.placeholder.material3.placeholder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import me.zhanghai.android.appiconloader.AppIconLoader

@Composable
fun AppIcon(
    packageInfo: PackageInfo?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    iconOverride: Drawable? = null,
    cacheKey: String? = null,
    packageName: String? = packageInfo?.packageName
) {
    val iconModifier = modifier.clip(CircleShape)
    if (iconOverride != null) {
        Image(
            painter = rememberDrawablePainter(drawable = iconOverride),
            contentDescription = contentDescription,
            modifier = iconModifier
        )
        return
    }

    if (packageInfo == null) {
        RemoteAppIcon(packageName, contentDescription, iconModifier)
        return
    }

    val context = LocalContext.current
    val appContext = context.applicationContext ?: context
    val iconKey = remember(packageInfo, appContext, cacheKey) {
        cacheKey ?: runCatching { AppIconLoader.getIconKey(packageInfo, appContext) }
            .getOrDefault("${packageInfo.packageName}:${packageInfo.versionName.orEmpty()}")
    }
    val cachedBitmap = remember(iconKey) { AppIconStore.get(iconKey) }
    // A different key owns a different load state; do not retain the previous bitmap.
    val iconState by key(iconKey) {
        produceState(
            initialValue = AppIconState(
                bitmap = cachedBitmap,
                loaded = cachedBitmap != null
            ),
            key1 = iconKey
        ) {
            if (value.bitmap == null) {
                val bitmap = withContext(Dispatchers.IO) {
                    AppIconStore.get(iconKey) ?: runCatching {
                        AppIconStore.load(appContext, iconKey, packageInfo)
                    }.getOrNull()
                }
                value = AppIconState(bitmap = bitmap, loaded = true)
            }
        }
    }

    iconState.bitmap?.let { bitmap ->
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = iconModifier
        )
        return
    }

    if (iconState.loaded) {
        RemoteAppIcon(packageName, contentDescription, iconModifier)
    } else {
        Image(
            painter = rememberVectorPainter(Icons.Default.Android),
            contentDescription = contentDescription,
            modifier = iconModifier.placeholder(visible = true,
                color = MaterialTheme.colorScheme.inverseOnSurface, shape = RoundedCornerShape(100)),
            colorFilter = ColorFilter.tint(LocalContentColor.current)
        )
    }
}

/** Coil's shared loader caches downloaded icon bytes on disk and decoded images in memory. */
@Composable
private fun RemoteAppIcon(packageName: String?, description: String?, modifier: Modifier) {
    val fallback = rememberVectorPainter(Icons.Default.Android)
    if (packageName == null || !appNamePackagePattern.matches(packageName)) {
        Image(fallback, description, modifier, colorFilter = ColorFilter.tint(LocalContentColor.current))
        return
    }
    val repository = koinInject<AppNameRepository>()
    val iconFlow = remember(repository, packageName) {
        repository.icons.map { it[packageName] }.distinctUntilChanged()
    }
    val cachedUrl by key(packageName) {
        iconFlow.collectAsStateWithLifecycle(initialValue = repository.icons.value[packageName])
    }
    // Keep a visible icon when an unrelated lookup evicts its repository cache entry.
    var resolvedUrl by remember(packageName) { mutableStateOf<String?>(null) }
    val url = cachedUrl ?: resolvedUrl
    LaunchedEffect(packageName) { resolvedUrl = repository.resolveIconUrl(packageName) }
    val context = LocalContext.current
    val request = remember(url, context) {
        ImageRequest.Builder(context).data(url)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .build()
    }
    var loaded by remember(packageName, url) { mutableStateOf(false) }
    Box(modifier.defaultMinSize(minWidth = 24.dp, minHeight = 24.dp).semantics {
        if (description != null) contentDescription = description
    }) {
        if (!loaded) Image(fallback, null, Modifier.matchParentSize(),
            colorFilter = ColorFilter.tint(LocalContentColor.current))
        if (url != null) AsyncImage(
            model = request, contentDescription = null, modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Fit,
            onLoading = { loaded = false }, onSuccess = { loaded = true }, onError = { loaded = false }
        )
    }
}

private data class AppIconState(
    val bitmap: Bitmap?,
    val loaded: Boolean
)

private object AppIconStore {
    private const val ICON_SIZE = 512
    private const val CACHE_MEMORY_DIVISOR = 16L
    private const val MIN_CACHE_SIZE_KIB = 8 * 1024L
    private const val MAX_CACHE_SIZE_KIB = 32 * 1024L
    private const val BYTES_PER_KIB = 1024

    private val cacheSizeKib = (Runtime.getRuntime().maxMemory() / BYTES_PER_KIB / CACHE_MEMORY_DIVISOR)
        .coerceIn(MIN_CACHE_SIZE_KIB, MAX_CACHE_SIZE_KIB)
        .toInt()
    private val cache = object : LruCache<String, Bitmap>(cacheSizeKib) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.allocationByteCount / BYTES_PER_KIB).coerceAtLeast(1)
    }

    @Volatile
    private var loader: AppIconLoader? = null

    fun get(key: String): Bitmap? = cache.get(key)

    fun load(context: Context, key: String, packageInfo: PackageInfo): Bitmap? {
        get(key)?.let { return it }
        val applicationInfo = packageInfo.applicationInfo ?: return null
        val bitmap = getLoader(context).loadIcon(applicationInfo)
        cache.put(key, bitmap)
        return bitmap
    }

    private fun getLoader(context: Context): AppIconLoader =
        loader ?: synchronized(this) {
            loader ?: AppIconLoader(ICON_SIZE, true, context.applicationContext ?: context)
                .also { loader = it }
        }
}
