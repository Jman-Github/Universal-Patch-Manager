package app.universal.patch.manager.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Api
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

object SettingsSectionIcons {
    val NavigationTabs: ImageVector = Icons.Outlined.Apps
    val Theme: ImageVector = Icons.Outlined.Palette
    val AppIcons: ImageVector = Icons.Outlined.Widgets
    val Background: ImageVector = Icons.Outlined.Image
    val AppBehavior: ImageVector = Icons.Outlined.Tune
    val NetworkIntegrations: ImageVector = Icons.Outlined.Api
    val Installer: ImageVector = Icons.Outlined.InstallMobile
    val SeparateProcesses: ImageVector = Icons.Outlined.Memory
    val SafeguardsCompatibility: ImageVector = Icons.Outlined.Warning
    val PatchingEngine: ImageVector = Icons.Outlined.Build
    val PatchingFlow: ImageVector = Icons.Outlined.SwapHoriz
    val SavedApps: ImageVector = Icons.Outlined.Save
    val ActionButtonsPatchList: ImageVector = Icons.Outlined.MoreHoriz
    val DiagnosticsOutput: ImageVector = Icons.Outlined.Description
    val DownloadBehavior: ImageVector = Icons.Outlined.Download
    val DownloaderPlugins: ImageVector = Icons.Outlined.Extension
    val DownloadExport: ImageVector = Icons.Outlined.Save
    val StabilityUpdateControls: ImageVector = Icons.Outlined.Update
    val Keystore: ImageVector = Icons.Outlined.VpnKey
    val SettingsSelectionsBundles: ImageVector = Icons.Outlined.SettingsBackupRestore
    val Reset: ImageVector = Icons.Outlined.Restore
    val NetworkDelivery: ImageVector = Icons.Outlined.Api
    val UpdateChecks: ImageVector = Icons.Outlined.Update
    val BundleChangelogHistory: ImageVector = Icons.Outlined.History
    val BackgroundUpdates: ImageVector = Icons.Outlined.Update
    val Announcements: ImageVector = Icons.Outlined.NotificationsActive
}

@Composable
fun GroupHeader(
    title: String,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp)
            .semantics { heading() }
            .then(modifier),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                modifier = Modifier.size(18.dp)
            )
        }
        Text(
            text = title,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f)
        )
    }
}
