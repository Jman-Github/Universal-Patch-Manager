package app.universal.patch.manager.ui.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageInfo
import android.graphics.drawable.Drawable
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import app.universal.patch.manager.R
import app.universal.patch.manager.domain.repository.appNamePackagePattern
import app.universal.patch.manager.util.longPressOnly
import app.universal.patch.manager.util.toast
import app.universal.patch.manager.util.versionCodeFor
import app.universal.patch.manager.util.withHapticFeedback

@Composable
fun AppInfo(
    appInfo: PackageInfo?,
    labelOverride: String? = null,
    iconOverride: Drawable? = null,
    placeholderLabel: String? = null,
    placeholderMetaLines: Int = 1,
    showExtraContentWhenLoading: Boolean = false,
    isLoading: Boolean = appInfo == null,
    packageName: String? = appInfo?.packageName ?: placeholderLabel?.takeIf(appNamePackagePattern::matches),
    extraContent: @Composable () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (appInfo == null && !isLoading) {
            if (packageName != null || iconOverride != null) {
                AppIcon(null, null, Modifier.size(100.dp).padding(bottom = 5.dp),
                    iconOverride = iconOverride, packageName = packageName)
                if (packageName != null) {
                    AppLabel(null, labelOverride = labelOverride, packageName = packageName,
                        defaultText = placeholderLabel ?: packageName,
                        modifier = Modifier.padding(top = 16.dp),
                        style = MaterialTheme.typography.titleLarge, centered = true)
                }
            } else Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.app_info_unavailable),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(100.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
                    .padding(20.dp)
            )
            extraContent()
        } else if (appInfo == null) {
            if (packageName != null || iconOverride != null) {
                AppIcon(null, null, Modifier.size(100.dp).padding(bottom = 5.dp),
                    iconOverride = iconOverride, packageName = packageName)
            } else {
                ShimmerBox(Modifier.size(100.dp).padding(bottom = 5.dp), shape = CircleShape)
            }
            if (packageName != null) {
                AppLabel(null, labelOverride = labelOverride, packageName = packageName,
                    defaultText = placeholderLabel ?: packageName,
                    modifier = Modifier.padding(top = 16.dp),
                    style = MaterialTheme.typography.titleLarge, centered = true)
            } else {
                ShimmerBox(
                    modifier = Modifier.padding(top = 16.dp).width(200.dp).height(22.dp)
                )
            }
            val placeholderLineCount = if (showExtraContentWhenLoading) {
                placeholderMetaLines.coerceAtLeast(0)
            } else {
                placeholderMetaLines.coerceAtLeast(1)
            }
            repeat(placeholderLineCount) { index ->
                val lineWidth = when (index) {
                    0 -> 150.dp
                    1 -> 130.dp
                    else -> 110.dp
                }
                ShimmerBox(
                    modifier = Modifier
                        .padding(top = if (index == 0) 8.dp else 6.dp)
                        .width(lineWidth)
                        .height(14.dp)
                )
            }
            if (showExtraContentWhenLoading) {
                extraContent()
            }
        } else {
            AppIcon(
                appInfo,
                iconOverride = iconOverride,
                contentDescription = null,
                modifier = Modifier
                    .size(100.dp)
                    .padding(bottom = 5.dp)
            )

            AppLabel(
                appInfo,
                labelOverride = labelOverride,
                modifier = Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.titleLarge,
                centered = true,
                defaultText = placeholderLabel
            )

            extraContent()
        }
    }
}

@Composable
fun appVersionLabel(
    versionName: String,
    appInfo: PackageInfo?,
    displayVersion: String = versionName,
    versionCodeOverride: Long? = null
): String {
    versionCodeOverride?.let { versionCode ->
        return stringResource(
            R.string.app_version_with_code,
            displayVersion,
            versionCode
        )
    }
    val versionCode = appInfo?.versionCodeFor(versionName) ?: return displayVersion

    return stringResource(
        R.string.app_version_with_code,
        displayVersion,
        versionCode
    )
}

@Composable
fun suggestedVersionLabel(
    versionName: String,
    versionCodes: Set<Long> = emptySet(),
    displayVersion: String = versionName
): String {
    if (versionCodes.isEmpty()) return displayVersion

    return stringResource(
        R.string.app_version_with_code,
        displayVersion,
        versionCodes.sorted().joinToString(", ")
    )
}

@Composable
fun AppVersion(
    appInfo: PackageInfo?,
    versionName: String? = appInfo?.versionName,
    modifier: Modifier = Modifier,
    style: TextStyle? = null,
    prefixVersion: Boolean = false,
    versionCodeOverride: Long? = null,
    copyVersionOnLongPress: Boolean = false
) {
    val rawVersion = versionName?.takeIf { it.isNotBlank() } ?: return
    val displayVersion = if (prefixVersion) "v${rawVersion.removePrefix("v").removePrefix("V")}" else rawVersion

    val clipboardModifier = if (copyVersionOnLongPress) {
        val context = LocalContext.current
        val copyLabel = stringResource(R.string.copy_to_clipboard)
        val copiedMessage = stringResource(R.string.toast_copied_to_clipboard)
        Modifier.longPressOnly(label = copyLabel, onLongPress = {
            context.getSystemService(ClipboardManager::class.java)?.let { clipboard ->
                clipboard.setPrimaryClip(ClipData.newPlainText(copyLabel, displayVersion))
                context.toast(copiedMessage)
            }
            Unit
        }.withHapticFeedback(HapticFeedbackConstants.LONG_PRESS))
    } else {
        Modifier
    }
    ExpandableText(
        text = appVersionLabel(
            versionName = rawVersion,
            appInfo = appInfo,
            displayVersion = displayVersion,
            versionCodeOverride = versionCodeOverride
        ),
        modifier = modifier.then(clipboardModifier),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = style ?: MaterialTheme.typography.bodySmall
    )
}
