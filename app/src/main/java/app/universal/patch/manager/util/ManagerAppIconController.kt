package app.universal.patch.manager.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import app.universal.patch.manager.ManagerApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal val Context.managerAppIcons: ManagerAppIconController
    get() = (applicationContext as ManagerApplication).appIcons

internal fun ManagerAppIcon.launcherComponent(context: Context) =
    ComponentName(context.packageName, launcherClass)

internal class ManagerAppIconController(private val context: Context) {
    private val pm = context.packageManager
    private val current = MutableStateFlow(installedIcon())
    val selected = current.asStateFlow()

    // The package manager persists this across launches, including in the separate patcher process.
    fun installedIcon(): ManagerAppIcon = activeIcon(enabledIcons())

    private fun activeIcon(enabled: List<ManagerAppIcon>): ManagerAppIcon =
        enabled.firstOrNull { it != ManagerAppIcon.DEFAULT } ?: ManagerAppIcon.DEFAULT

    @Synchronized
    fun select(icon: ManagerAppIcon): Boolean = updateSelection(icon)

    @Synchronized
    fun reconcileLauncherIcons() {
        // Recover an interrupted switch on Android versions that update one component at a time.
        updateSelection(requestedIcon = null)
    }

    private fun updateSelection(requestedIcon: ManagerAppIcon?): Boolean {
        return try {
            val enabled = enabledIcons()
            val icon = requestedIcon ?: activeIcon(enabled)
            val changes = buildList {
                if (icon !in enabled) {
                    val enabledState = if (icon == ManagerAppIcon.DEFAULT) {
                        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                    } else {
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    }
                    add(icon.launcherComponent(context) to enabledState)
                }
                (enabled - icon).forEach {
                    add(it.launcherComponent(context) to PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
                }
            }
            if (changes.isNotEmpty()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.setComponentEnabledSettings(changes.map { (component, state) ->
                        PackageManager.ComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
                    })
                } else {
                    applyLegacyChanges(changes)
                }
            }
            current.value = icon
            true
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Could not change launcher icon", failure)
            current.value = runCatching(::installedIcon).getOrDefault(current.value)
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun enabledIcons(): List<ManagerAppIcon> {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName)
        val enabledClasses = pm.queryIntentActivities(intent, 0).mapTo(mutableSetOf()) {
            it.activityInfo.name
        }
        return ManagerAppIcon.entries.filter { it.launcherClass in enabledClasses }
    }

    private fun applyLegacyChanges(changes: List<Pair<ComponentName, Int>>) {
        val previous = changes.associate { (component, _) ->
            component to pm.getComponentEnabledSetting(component)
        }
        val applied = mutableListOf<ComponentName>()
        try {
            // Enable the new alias before disabling the current one.
            changes.forEach { (component, state) ->
                pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
                applied.add(component)
            }
        } catch (failure: RuntimeException) {
            applied.asReversed().forEach { component ->
                runCatching {
                    pm.setComponentEnabledSetting(component, previous.getValue(component), PackageManager.DONT_KILL_APP)
                }.onFailure(failure::addSuppressed)
            }
            throw failure
        }
    }

    private companion object {
        const val TAG = "ManagerAppIcons"
    }
}
