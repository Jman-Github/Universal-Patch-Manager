package app.universal.patch.manager.ui.component.settings

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.universal.patch.manager.R
import app.universal.patch.manager.domain.manager.base.Preference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun BooleanItem(
    modifier: Modifier = Modifier,
    preference: Preference<Boolean>,
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
    @StringRes headline: Int,
    @StringRes description: Int,
    enabled: Boolean = true
) {
    val value by preference.getAsState()

    BooleanItem(
        modifier = modifier,
        value = value,
        onValueChange = { coroutineScope.launch { preference.update(it) } },
        headline = headline,
        description = description,
        enabled = enabled
    )
}

@Composable
fun BooleanItem(
    modifier: Modifier = Modifier,
    value: Boolean,
    onValueChange: (Boolean) -> Unit,
    @StringRes headline: Int,
    @StringRes description: Int,
    enabled: Boolean = true
) = ExpressiveSettingsItem(
    modifier = Modifier
        .clickable(enabled = enabled) { onValueChange(!value) }
        .then(modifier),
    headlineContent = stringResource(headline),
    supportingContent = stringResource(description),
    enabled = enabled,
    trailingContent = {
        ExpressiveSettingsSwitch(
            checked = enabled && value,
            onCheckedChange = onValueChange,
            enabled = enabled
        )
    }
)


@Composable
fun ExpandableBooleanItem(
    modifier: Modifier = Modifier,
    preference: Preference<Boolean>,
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
    @StringRes headline: Int,
    @StringRes description: Int,
    enabled: Boolean = true,
    contentEnabled: Boolean = true,
    expandForSearch: Boolean = false,
    expandableContent: @Composable (Boolean) -> Unit
) {
    val value by preference.getAsState()
    var expanded by rememberSaveable { mutableStateOf(expandForSearch) }
    LaunchedEffect(enabled, value, expandForSearch) {
        if (!enabled || !value) expanded = false
        else if (expandForSearch) expanded = true
    }

    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        ExpressiveSettingsItem(
            headlineContent = stringResource(headline),
            supportingContent = stringResource(description),
            enabled = enabled,
            onClick = { coroutineScope.launch { preference.update(!value) } },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { expanded = !expanded },
                        enabled = enabled && value
                    ) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = stringResource(
                                if (expanded) R.string.collapse_content else R.string.expand_content
                            )
                        )
                    }
                    ExpressiveSettingsSwitch(
                        checked = enabled && value,
                        onCheckedChange = { coroutineScope.launch { preference.update(it) } },
                        enabled = enabled
                    )
                }
            }
        )

        AnimatedVisibility(visible = expanded && enabled && value) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            ) {
                expandableContent(enabled && value && contentEnabled)
            }
        }
    }
}
