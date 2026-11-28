package app.universal.patch.manager.ui.screen.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import app.universal.patch.manager.R
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.ui.component.settings.BooleanItem
import kotlinx.coroutines.launch

@Composable
internal fun MinimalActionSettings(
    prefs: PreferencesManager,
    group: String
) {
    val groups by prefs.minimalActionButtonGroups.getAsState()
    val scope = rememberCoroutineScope()
    BooleanItem(
        value = group in groups,
        onValueChange = { enabled ->
            scope.launch {
                prefs.edit {
                    val current = prefs.minimalActionButtonGroups.value
                    prefs.minimalActionButtonGroups.value =
                        if (enabled) current + group else current - group
                }
            }
        },
        headline = R.string.minimal_action_buttons_title,
        description = R.string.minimal_action_buttons_description
    )
}
