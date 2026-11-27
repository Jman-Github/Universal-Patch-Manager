package app.universal.patch.manager.ui.component

import app.universal.patch.manager.ui.component.design.TextButton
import android.graphics.drawable.ColorDrawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

@Composable
fun TransparentLoadingDialog(
    message: String? = null,
    cancelButtonText: String? = null,
    onCancel: (() -> Unit)? = null,
    cancelEnabled: Boolean = true,
    logTitle: String? = null,
    logLines: List<String> = emptyList(),
    emptyLogMessage: String? = null,
    progress: Float? = null,
    logContent: (@Composable () -> Unit)? = null
) {
    Dialog(
        onDismissRequest = {
            if (cancelEnabled) onCancel?.invoke()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = onCancel != null && cancelEnabled,
            dismissOnClickOutside = false
        )
    ) {
        val view = LocalView.current
        SideEffect {
            val window = (view.parent as DialogWindowProvider).window
            window.setDimAmount(0f)
            window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LoadingIndicator(
                    modifier = Modifier.size(56.dp),
                    progress = { progress },
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
                    strokeWidth = 4.dp
                )
                message?.takeIf { it.isNotBlank() }?.let {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                }
                if (logContent != null) {
                    Spacer(modifier = Modifier.height(20.dp))
                    logContent()
                } else logTitle?.takeIf { it.isNotBlank() }?.let { title ->
                    Spacer(modifier = Modifier.height(20.dp))
                    ExpandableLoadingLog(
                        title = title,
                        lines = logLines,
                        emptyMessage = emptyLogMessage
                    )
                }
                onCancel?.let { cancel ->
                    cancelButtonText?.takeIf { it.isNotBlank() }?.let { label ->
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(
                            onClick = cancel,
                            enabled = cancelEnabled
                        ) {
                            Text(label)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpandableLoadingLog(
    title: String,
    lines: List<String>,
    emptyMessage: String?
) {
    var expanded by remember { mutableStateOf(true) }
    val scrollState = rememberScrollState()
    val output = lines.joinToString(separator = "\n")
        .ifBlank { emptyMessage.orEmpty() }

    LaunchedEffect(lines.size, expanded) {
        if (expanded) scrollState.animateScrollTo(scrollState.maxValue)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 720.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
        tonalElevation = 6.dp
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AnimatedVisibility(visible = expanded) {
                SelectionContainer {
                    Text(
                        text = output,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .verticalScroll(scrollState)
                            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
