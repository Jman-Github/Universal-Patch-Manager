package app.universal.patch.manager.ui.component.settings

// Code adapted from Morphe, see third-party/NOTICE for more information
// https://github.com/MorpheApp/morphe-manager/blob/a2c3d31bd7ab42e6bc4b9dd528ed856fc72fb948/app/src/main/java/app/morphe/manager/ui/screen/settings/system/ProcessRuntimeDialog.kt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.universal.patch.manager.R
import app.universal.patch.manager.patcher.runtime.MemoryLimitConfig
import app.universal.patch.manager.ui.component.CenteredDialogTitle
import kotlin.math.roundToInt

@Composable
fun ProcessMemoryLimitDialog(
    currentLimit: Int,
    onDismiss: () -> Unit,
    onLimitChange: (Int) -> Unit
) {
    val maxLimit = MemoryLimitConfig.PROCESS_RUNTIME_MEMORY_MAX_LIMIT
    var sliderValue by remember {
        mutableFloatStateOf(
            MemoryLimitConfig.clampConfiguredMemoryLimitMb(currentLimit).toFloat()
        )
    }
    val selectedLimit = MemoryLimitConfig.clampConfiguredMemoryLimitMb(sliderValue.roundToInt())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { CenteredDialogTitle(stringResource(R.string.process_memory_limit)) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(
                        R.string.process_memory_limit_subtitle
                    ),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = "$selectedLimit MB",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = {
                        onLimitChange(MemoryLimitConfig.clampConfiguredMemoryLimitMb(sliderValue.roundToInt()))
                    },
                    valueRange =
                        MemoryLimitConfig.PROCESS_RUNTIME_MEMORY_MINIMUM.toFloat()..
                            maxLimit.toFloat(),
                    steps = (
                        (maxLimit - MemoryLimitConfig.PROCESS_RUNTIME_MEMORY_MINIMUM) /
                            MemoryLimitConfig.PROCESS_RUNTIME_MEMORY_STEP - 1
                        ).coerceAtLeast(0)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("${MemoryLimitConfig.PROCESS_RUNTIME_MEMORY_MINIMUM} MB")
                    Text("$maxLimit MB")
                }
                Text(
                    text = stringResource(
                        R.string.process_memory_limit_description
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Measure both messages so the dialog and slider stay still while dragging.
                Box(Modifier.fillMaxWidth()) {
                    MemoryLimitWarning(
                        text = stringResource(R.string.process_memory_limit_warning),
                        visible = selectedLimit < MemoryLimitConfig.PROCESS_RUNTIME_MEMORY_LOW_WARNING
                    )
                    MemoryLimitWarning(
                        text = stringResource(R.string.process_memory_limit_high_warning),
                        visible = selectedLimit > MemoryLimitConfig.PROCESS_RUNTIME_MEMORY_HIGH_WARNING
                    )
                }
            }
        }
    )
}

@Composable
private fun MemoryLimitWarning(text: String, visible: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            .alpha(if (visible) 1f else 0f)
            .then(if (visible) Modifier else Modifier.clearAndSetSemantics { }),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error
        )
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}
