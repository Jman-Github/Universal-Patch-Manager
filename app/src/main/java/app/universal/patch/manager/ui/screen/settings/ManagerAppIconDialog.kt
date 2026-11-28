package app.universal.patch.manager.ui.screen.settings

import app.universal.patch.manager.ui.component.design.TextButton
import androidx.annotation.StringRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.universal.patch.manager.R
import app.universal.patch.manager.ui.component.ConfirmDialog
import app.universal.patch.manager.ui.component.ManagerIconImage
import app.universal.patch.manager.ui.component.SettingsSectionIcons
import app.universal.patch.manager.ui.component.rememberSelectedManagerIcon
import app.universal.patch.manager.util.ManagerAppIcon
import app.universal.patch.manager.util.ManagerIconBackground
import app.universal.patch.manager.util.ManagerIconForeground
import app.universal.patch.manager.util.managerAppIcons
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class CommunityIcon(val icon: ManagerAppIcon, val artist: String)

private val communityIcons = listOf(
    CommunityIcon(ManagerAppIcon.LIGHT, "@xASAHIx"),
    CommunityIcon(ManagerAppIcon.DARK, "@xASAHIx"),
    CommunityIcon(ManagerAppIcon.GRADIENT, "@sr-shishir")
)

@get:StringRes
internal val ManagerAppIcon.titleRes: Int
    get() = when (this) {
        ManagerAppIcon.DEFAULT -> R.string.manager_icon_default
        ManagerAppIcon.LIGHT -> R.string.manager_icon_light
        ManagerAppIcon.DARK -> R.string.manager_icon_dark
        ManagerAppIcon.GRADIENT -> R.string.manager_icon_gradient
        else -> R.string.manager_icon_custom
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ManagerAppIconDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val controller = context.managerAppIcons
    val current = rememberSelectedManagerIcon()
    var useCustomColors by rememberSaveable { mutableStateOf(current.background != null) }
    var preset by rememberSaveable {
        mutableStateOf(if (current.background == null) current else ManagerAppIcon.DEFAULT)
    }
    var background by rememberSaveable {
        mutableStateOf(current.background ?: ManagerIconBackground.NAVY)
    }
    var foreground by rememberSaveable {
        mutableStateOf(current.foreground ?: ManagerIconForeground.ORIGINAL)
    }
    var showApplyConfirmation by rememberSaveable { mutableStateOf(false) }
    var applying by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val customIcons = remember(background) { ManagerAppIcon.entries.filter { it.background == background } }
    val selected = if (useCustomColors) {
        customIcons.firstOrNull { it.foreground == foreground } ?: customIcons.first()
    } else {
        preset
    }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    if (showApplyConfirmation) {
        ConfirmDialog(
            title = stringResource(R.string.manager_icon_confirm_title),
            description = stringResource(R.string.manager_icon_confirm_description),
            icon = SettingsSectionIcons.AppIcons,
            confirmLabelRes = R.string.apply,
            onDismiss = { showApplyConfirmation = false },
            onConfirm = {
                showApplyConfirmation = false
                applying = true
                failed = false
                scope.launch {
                    val applied = withContext(Dispatchers.IO) { controller.select(selected) }
                    applying = false
                    if (applied) onDismiss() else failed = true
                }
            }
        )
        return
    }

    AlertDialog(
        onDismissRequest = { if (!applying) onDismiss() },
        title = { Text(stringResource(R.string.manager_app_icon)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ManagerIconImage(
                    contentDescription = stringResource(R.string.manager_icon_preview),
                    icon = selected,
                    modifier = Modifier.size(80.dp).align(Alignment.CenterHorizontally)
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth().selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                ) {
                    FilterChip(
                        selected = !useCustomColors,
                        onClick = { useCustomColors = false },
                        label = { Text(stringResource(R.string.manager_icon_presets)) },
                        enabled = !applying
                    )
                    FilterChip(
                        selected = useCustomColors,
                        onClick = { useCustomColors = true },
                        label = { Text(stringResource(R.string.manager_icon_custom)) },
                        enabled = !applying
                    )
                }

                if (useCustomColors) {
                    Text(
                        stringResource(R.string.manager_icon_background),
                        style = MaterialTheme.typography.titleSmall
                    )
                    FlowRow(
                        modifier = Modifier.selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ManagerIconBackground.entries.forEach { color ->
                            FilterChip(
                                selected = color == background,
                                enabled = !applying,
                                onClick = { background = color },
                                label = { Text(stringResource(color.label)) },
                                leadingIcon = {
                                    val swatch = remember(context, color) {
                                        AppCompatResources.getDrawable(context, color.swatch)
                                    }
                                    Image(
                                        painter = rememberDrawablePainter(swatch),
                                        contentDescription = null,
                                        contentScale = ContentScale.FillBounds,
                                        modifier = Modifier.size(16.dp)
                                            .clip(CircleShape)
                                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                    )
                                }
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.manager_icon_foreground),
                        style = MaterialTheme.typography.titleSmall
                    )
                    FlowRow(
                        modifier = Modifier.selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ManagerIconForeground.entries.forEach { color ->
                            FilterChip(
                                selected = selected.foreground == color,
                                enabled = !applying && customIcons.any { it.foreground == color },
                                onClick = { foreground = color },
                                label = { Text(stringResource(color.label)) }
                            )
                        }
                    }
                } else {
                    Column(Modifier.selectableGroup()) {
                        IconPresetRow(ManagerAppIcon.DEFAULT, preset, !applying) {
                            preset = ManagerAppIcon.DEFAULT
                        }
                        Text(
                            text = stringResource(R.string.manager_icon_community),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                        )
                        communityIcons.forEach { entry ->
                            IconPresetRow(entry.icon, preset, !applying, entry.artist) {
                                preset = entry.icon
                            }
                        }
                    }
                }
                if (failed) {
                    Text(
                        text = stringResource(R.string.manager_icon_apply_failed),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected != current && !applying,
                onClick = { showApplyConfirmation = true }
            ) { Text(stringResource(R.string.apply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !applying) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun IconPresetRow(
    icon: ManagerAppIcon,
    selected: ManagerAppIcon,
    enabled: Boolean,
    artist: String? = null,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = icon == selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ManagerIconImage(null, Modifier.size(38.dp), icon)
        Column(Modifier.weight(1f)) {
            Text(stringResource(icon.titleRes), style = MaterialTheme.typography.bodyLarge)
            if (artist != null) {
                Text(
                    text = stringResource(R.string.manager_icon_artist, artist),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        RadioButton(selected = icon == selected, enabled = enabled, onClick = null)
    }
}
