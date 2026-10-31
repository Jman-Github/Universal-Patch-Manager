package app.universal.patch.manager.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import app.universal.patch.manager.domain.manager.PreferencesManager
import org.koin.compose.koinInject

@Composable
fun minimalActionButtons(group: String): Boolean {
    val prefs: PreferencesManager = koinInject()
    val groups by prefs.minimalActionButtonGroups.getAsState()
    return group in groups
}
