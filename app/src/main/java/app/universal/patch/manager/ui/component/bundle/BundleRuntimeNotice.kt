package app.universal.patch.manager.ui.component.bundle

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.universal.patch.manager.R

@StringRes
internal fun catalogRuntimeNotice(bundleType: String): Int? = when {
    bundleType.equals("ReVanced:v21", true) -> R.string.catalog_requires_v21
    bundleType.equals("ReVanced:v22", true) -> R.string.catalog_requires_v22
    bundleType.equals("Morphe", true) -> R.string.catalog_requires_morphe
    bundleType.equals("Reseam", true) -> R.string.catalog_requires_reseam
    else -> null
}

@Composable
internal fun BundleRuntimeNotice(bundleType: String) {
    val notice = catalogRuntimeNotice(bundleType) ?: return
    Surface(shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
        Row(Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(stringResource(notice), style = MaterialTheme.typography.bodySmall)
        }
    }
}
