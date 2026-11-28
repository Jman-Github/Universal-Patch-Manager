package app.universal.patch.manager.ui.component.patcher

import app.universal.patch.manager.ui.theme.withClassicCorner
import app.universal.patch.manager.ui.component.design.ListItem
import app.universal.patch.manager.ui.component.design.ListItemRadioButton
import app.universal.patch.manager.ui.component.design.FilledTonalButton
import app.universal.patch.manager.ui.component.design.OutlinedButton
import app.universal.patch.manager.ui.component.design.TextButton
import android.graphics.drawable.Drawable
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.universal.patch.manager.R
import app.universal.patch.manager.domain.installer.InstallerManager
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.util.toast
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import app.universal.patch.manager.ui.component.CenteredDialogTitle
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
internal fun installerOptionRowColors(enabled: Boolean) = ListItemDefaults.colors(
    containerColor = Color.Transparent,
    contentColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
)

@Composable
fun InstallerPickerDialog(
    title: String,
    options: List<InstallerManager.Entry>,
    initialSelection: InstallerManager.Token? = null,
    @StringRes confirmLabel: Int = R.string.install_app,
    onDismiss: () -> Unit,
    onConfirm: (InstallerManager.Token) -> Unit,
    onOpenShizuku: (() -> Boolean)? = null
) {
    val context = LocalContext.current
    val installerManager: InstallerManager = koinInject()
    val prefs: PreferencesManager = koinInject()
    val scope = rememberCoroutineScope()
    val shizukuInstallAsPlayStore by prefs.shizukuInstallAsPlayStore.getAsState()
    val autoInstallWithShizuku by prefs.autoInstallWithShizuku.getAsState()
    val autoUninstallWithShizuku by prefs.autoUninstallWithShizuku.getAsState()
    // Code adapted from Morphe, see third-party/NOTICE for more information
    // https://github.com/MorpheApp/morphe-manager/commit/7e24461c1454b712da4df21440db6f417c94ce58
    val visibleOptions = remember(options) {
        options.filterNot { option -> installerManager.usesPlayStoreSource(option.token) }
    }
    fun playStoreModeAvailable(token: InstallerManager.Token): Boolean {
        val configuredToken = installerManager.withPlayStoreMode(token, true)
        return options.any { option ->
            option.token == configuredToken && option.availability.available
        }
    }
    var showShizukuConfiguration by remember { mutableStateOf(false) }
    var playStoreConfigurationToken by remember {
        mutableStateOf<InstallerManager.Token?>(null)
    }
    var selectedToken by remember(initialSelection) {
        mutableStateOf(
            initialSelection
                ?.let { token ->
                    val baseToken = installerManager.baseInstallerToken(token)
                    baseToken.takeIf { candidate ->
                        visibleOptions.any { it.token == candidate }
                    } ?: token
                }
                ?: visibleOptions.firstOrNull { it.availability.available }?.token
                ?: visibleOptions.firstOrNull()?.token
                ?: InstallerManager.Token.Internal
        )
    }
    var installAsPlayStore by remember(initialSelection) {
        mutableStateOf(
            initialSelection?.let(::playStoreModeAvailable) == true &&
                installerManager.usesPlayStoreSource(initialSelection)
        )
    }

    LaunchedEffect(visibleOptions, initialSelection) {
        val normalizedInitial = initialSelection?.let { token ->
            val baseToken = installerManager.baseInstallerToken(token)
            baseToken.takeIf { candidate ->
                visibleOptions.any { it.token == candidate }
            } ?: token
        }
        val fallback = normalizedInitial
            ?.takeIf { selection -> visibleOptions.any { it.token == selection } }
            ?: visibleOptions.firstOrNull { it.availability.available }?.token
            ?: visibleOptions.firstOrNull()?.token
            ?: return@LaunchedEffect
        if (visibleOptions.none { it.token == selectedToken }) {
            selectedToken = fallback
        }
    }

    LaunchedEffect(selectedToken) {
        if (!installerManager.supportsPlayStoreMode(selectedToken) ||
            !playStoreModeAvailable(selectedToken)
        ) {
            installAsPlayStore = false
        }
    }

    val effectiveToken = installerManager.withPlayStoreMode(selectedToken, installAsPlayStore)
    val confirmEnabled =
        (options.find { it.token == effectiveToken }
            ?: visibleOptions.find { it.token == selectedToken})
            ?.availability?.available == true
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(effectiveToken)
                    onDismiss()
                },
                enabled = confirmEnabled
            ) {
                Text(stringResource(confirmLabel))
            }
        },
        title = { CenteredDialogTitle(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                visibleOptions.forEach { option ->
                    val enabled = option.availability.available
                    val selected = option.token == selectedToken
                    val isShizukuOption = option.token == InstallerManager.Token.Shizuku ||
                        option.token == InstallerManager.Token.ShizukuGooglePlay
                    val hasPlayStoreConfiguration =
                        (option.token == InstallerManager.Token.Internal ||
                            option.token == InstallerManager.Token.AutoSaved) &&
                            playStoreModeAvailable(option.token)
                    val desc = option.description?.takeIf { it.isNotBlank() }
                    val statusBadges = buildList {
                        option.availability.reason?.let { add(context.getString(it)) }
                    }

                    ListItem(
                        selected = selected,
                        enabled = enabled,
                        onClick = {
                            if (enabled) {
                                selectedToken = option.token
                                installAsPlayStore =
                                    installerManager.usesPlayStoreSource(option.token)
                            }
                        },
                        colors = installerOptionRowColors(enabled),
                        leadingContent = {
                            val iconDrawable = option.icon
                            val useInstallerIcon = iconDrawable != null && when (option.token) {
                                InstallerManager.Token.PlayStore -> true
                                InstallerManager.Token.RootPlayStore -> true
                                InstallerManager.Token.Shizuku -> true
                                InstallerManager.Token.ShizukuGooglePlay -> true
                                is InstallerManager.Token.Component -> true
                                else -> false
                            }
                            if (useInstallerIcon) {
                                InstallerIcon(
                                    drawable = iconDrawable,
                                    selected = selected,
                                    enabled = enabled || selected
                                )
                            } else {
                                ListItemRadioButton(selected = selected, enabled = enabled)
                            }
                        },
                        headlineContent = {
                            Text(option.label)
                        },
                        supportingContent = {
                            if (desc != null || statusBadges.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    desc?.let { line ->
                                        Text(line)
                                    }
                                    statusBadges.forEach { status ->
                                        Surface(
                                            shape = MaterialTheme.shapes.small.withClassicCorner(8.dp),
                                            color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                                            tonalElevation = 0.dp
                                        ) {
                                            Text(
                                                text = status,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    )
                    if (isShizukuOption) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val launched = runCatching {
                                        onOpenShizuku?.invoke() ?: installerManager.openShizukuApp()
                                    }.getOrDefault(false)
                                    if (!launched) {
                                        context.toast(
                                            context.getString(
                                                R.string.installer_shizuku_launch_failed
                                            )
                                        )
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.installer_action_open_shizuku))
                            }
                            FilledTonalButton(
                                onClick = { showShizukuConfiguration = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.configure))
                            }
                        }
                    } else if (hasPlayStoreConfiguration) {
                        FilledTonalButton(
                            onClick = {
                                if (selectedToken != option.token) {
                                    installAsPlayStore = false
                                }
                                selectedToken = option.token
                                playStoreConfigurationToken = option.token
                            },
                            enabled = enabled,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(stringResource(R.string.configure))
                        }
                    }
                }
            }
        }
    )

    if (showShizukuConfiguration) {
        ShizukuConfigurationDialog(
            installAsPlayStore = if (installerManager.isShizukuToken(selectedToken)) {
                installAsPlayStore
            } else {
                shizukuInstallAsPlayStore
            },
            autoInstall = autoInstallWithShizuku,
            autoUninstallOnConflict = autoUninstallWithShizuku,
            onInstallAsPlayStoreChange = { enabled ->
                if (installerManager.isShizukuToken(selectedToken)) {
                    installAsPlayStore = enabled
                }
                scope.launch { installerManager.updateShizukuPlayStoreMode(enabled) }
            },
            onAutoInstallChange = { enabled ->
                scope.launch { prefs.autoInstallWithShizuku.update(enabled) }
            },
            onAutoUninstallOnConflictChange = { enabled ->
                scope.launch { prefs.autoUninstallWithShizuku.update(enabled) }
            },
            onDismiss = { showShizukuConfiguration = false }
        )
    }

    playStoreConfigurationToken?.let { configurationToken ->
        PlayStoreSourceConfigurationDialog(
            installerName = visibleOptions
                .firstOrNull { it.token == configurationToken }
                ?.label
                .orEmpty(),
            installAsPlayStore = selectedToken == configurationToken && installAsPlayStore,
            onInstallAsPlayStoreChange = { enabled ->
                selectedToken = configurationToken
                installAsPlayStore = enabled
            },
            onDismiss = { playStoreConfigurationToken = null }
        )
    }
}

@Composable
private fun InstallerIcon(
    drawable: Drawable?,
    selected: Boolean,
    enabled: Boolean
) {
    val colors = MaterialTheme.colorScheme
    val borderColor = if (selected) colors.primary else colors.outlineVariant
    val background = colors.surfaceVariant.copy(alpha = if (enabled) 1f else 0.6f)
    val contentAlpha = if (enabled) 1f else 0.4f

    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(MaterialTheme.shapes.medium.withClassicCorner(12.dp))
            .background(background)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = MaterialTheme.shapes.medium.withClassicCorner(12.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (drawable != null) {
            Image(
                painter = rememberDrawablePainter(drawable = drawable),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                alpha = contentAlpha
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.Android,
                contentDescription = null,
                tint = colors.onSurface.copy(alpha = contentAlpha)
            )
        }
    }
}
