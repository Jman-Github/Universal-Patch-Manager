package app.universal.patch.manager.ui.component

import app.universal.patch.manager.ui.component.design.IconButton
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.universal.patch.manager.R

@Composable
fun ExpandableText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    textAlign: TextAlign? = null,
    collapsedMaxLines: Int = 1,
    collapsedSoftWrap: Boolean = true
) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    var overflowed by remember(text) { mutableStateOf(false) }
    val expandable = overflowed || expanded

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            modifier = Modifier.weight(1f, fill = false),
            color = color,
            style = style,
            textAlign = textAlign,
            maxLines = if (expanded) Int.MAX_VALUE else collapsedMaxLines,
            overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
            softWrap = expanded || collapsedSoftWrap,
            onTextLayout = { result ->
                if (!expanded) overflowed = result.hasVisualOverflow
            }
        )
        if (expandable) {
            IconButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Outlined.UnfoldLess else Icons.Outlined.UnfoldMore,
                    contentDescription = stringResource(if (expanded) R.string.less else R.string.more),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
