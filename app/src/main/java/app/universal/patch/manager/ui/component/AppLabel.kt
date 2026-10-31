package app.universal.patch.manager.ui.component

import android.content.pm.PackageInfo
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import app.universal.patch.manager.R
import app.universal.patch.manager.util.resolveAppDisplayLabel
import app.universal.patch.manager.util.actualAppMetadataLabel
import app.universal.patch.manager.domain.repository.AppNameRepository
import app.universal.patch.manager.domain.repository.appNamePackagePattern
import org.koin.compose.koinInject
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.fornewid.placeholder.material3.placeholder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun AppLabel(
    packageInfo: PackageInfo?,
    labelOverride: String? = null,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    centered: Boolean = false,
    defaultText: String? = stringResource(R.string.not_installed),
    packageName: String? = packageInfo?.packageName
) {
    val context = LocalContext.current
    val appNames = koinInject<AppNameRepository>()
    val lookupPackage = packageName ?: defaultText?.takeIf(appNamePackagePattern::matches)
    val nameFlow = remember(appNames, lookupPackage) {
        appNames.names.map { names -> lookupPackage?.let(names::get) }.distinctUntilChanged()
    }
    val cachedName by key(lookupPackage) {
        nameFlow.collectAsStateWithLifecycle(initialValue = lookupPackage?.let(appNames.names.value::get))
    }
    val explicitLabel = actualAppMetadataLabel(labelOverride, lookupPackage)

    var label: String? by remember(packageInfo, lookupPackage, explicitLabel, defaultText) {
        mutableStateOf(explicitLabel ?: lookupPackage ?: defaultText)
    }

    LaunchedEffect(packageInfo, lookupPackage, explicitLabel, defaultText) {
        if (explicitLabel != null) {
            label = explicitLabel
            return@LaunchedEffect
        }
        val localLabel = withContext(Dispatchers.IO) {
            packageInfo?.let { resolveAppDisplayLabel(context, it, requireMetadataLabel = true) }
        }
        label = localLabel ?: lookupPackage ?: defaultText
        lookupPackage?.let { label = appNames.resolve(it, localLabel) }
    }

    Text(
        explicitLabel ?: label?.takeUnless { it == lookupPackage }
            ?: cachedName ?: label ?: stringResource(R.string.loading),
        modifier = (if (centered) modifier.fillMaxWidth() else modifier)
            .placeholder(
                visible = explicitLabel == null && label == null,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                shape = RoundedCornerShape(100)
            ),
        style = style,
        textAlign = if (centered) TextAlign.Center else TextAlign.Start
    )
}
