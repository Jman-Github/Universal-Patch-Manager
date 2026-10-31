package app.universal.patch.manager.ui.component.bundle

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Source
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.universal.patch.manager.R
import androidx.compose.ui.res.stringResource
import app.universal.patch.manager.network.dto.PackageNode
import coil3.compose.AsyncImage
import app.universal.patch.manager.ui.component.settings.ExpressiveSettingsCard

/** Coil's shared loader retains successful images in memory and on disk. */
@Composable
fun CatalogIcon(
    iconUrl: String?,
    fallbackUrl: String? = null,
    description: String? = null,
    modifier: Modifier = Modifier.size(40.dp),
    isOfficial: Boolean = false,
) {
    var model by remember(iconUrl, fallbackUrl) {
        mutableStateOf(iconUrl?.takeIf { it.isNotBlank() } ?: fallbackUrl)
    }
    var imageLoaded by remember(iconUrl, fallbackUrl) { mutableStateOf(false) }
    val shape = CircleShape
    Box(modifier.clip(shape)) {
        Surface(shape = shape, color = if (isOfficial) Color(0xFF1B1B1B) else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxSize()) {
            if (!isOfficial && !imageLoaded) Icon(Icons.Outlined.Source, contentDescription = null, modifier = Modifier.padding(8.dp))
        }
        if (!model.isNullOrBlank()) {
            AsyncImage(model = model, contentDescription = description,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                onSuccess = { imageLoaded = true },
                onError = {
                    imageLoaded = false
                    model = fallbackUrl?.takeUnless { it == model }
                })
        }
    }
}

@Composable
fun CatalogAppItem(app: PackageNode, onClick: () -> Unit) {
    ExpressiveSettingsCard(
        modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CatalogIcon(app.iconUrl, description = app.appName ?: app.name)
                Column(modifier = Modifier.weight(1f)) {
                    Text(app.appName?.takeIf { it.isNotBlank() } ?: app.name.orEmpty(),
                        style = MaterialTheme.typography.titleMedium)
                    Text(app.name.orEmpty(), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            FilledTonalButton(onClick = onClick) {
                Text(stringResource(R.string.catalog_view_bundles))
            }
        }
    }
}
