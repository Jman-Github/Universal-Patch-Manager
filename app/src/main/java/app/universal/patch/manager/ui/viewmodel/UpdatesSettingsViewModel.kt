package app.universal.patch.manager.ui.viewmodel

import android.app.Application
import android.app.NotificationManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.universal.patch.manager.R
import app.universal.patch.manager.data.platform.NetworkInfo
import app.universal.patch.manager.domain.manager.BundleUpdateDeliveryMode
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.domain.manager.SearchForUpdatesBackgroundInterval
import app.universal.patch.manager.domain.worker.WorkerRepository
import app.universal.patch.manager.network.api.UpmAPI
import app.universal.patch.manager.patcher.worker.AnnouncementNotificationWorker
import app.universal.patch.manager.util.toast
import app.universal.patch.manager.util.uiSafe
import kotlinx.coroutines.launch

class UpdatesSettingsViewModel(
    prefs: PreferencesManager,
    private val app: Application,
    private val reVancedAPI: UpmAPI,
    private val network: NetworkInfo,
    private val workerRepository: WorkerRepository,
) : ViewModel() {
    val managerAutoUpdates = prefs.managerAutoUpdates
    val showManagerUpdateDialogOnLaunch = prefs.showManagerUpdateDialogOnLaunch
    val showManagerUpdateChangelog = prefs.showManagerUpdateChangelog
    val announcementSystemEnabled = prefs.announcementSystemEnabled
    val announcementPushNotificationInterval = prefs.announcementPushNotificationInterval
    val useManagerPrereleases = prefs.useManagerPrereleases
    val allowMeteredUpdates = prefs.allowMeteredUpdates
    val backgroundManagerUpdateInterval = prefs.searchForManagerUpdatesBackgroundInterval
    val backgroundBundleUpdateInterval = prefs.searchForUpdatesBackgroundInterval
    val bundleUpdateDeliveryMode = prefs.bundleUpdateDeliveryMode
    val bundleChangelogFetchLimit = prefs.bundleChangelogFetchLimit
    val bundleChangelogStorageLimit = prefs.bundleChangelogStorageLimit

    val isConnected: Boolean
        get() = network.isConnected()

    fun updateBackgroundBundleUpdateTime(interval: SearchForUpdatesBackgroundInterval) {
        viewModelScope.launch {
            uiSafe(app, R.string.failed_to_check_updates, "Failed to update background update schedule") {
                backgroundBundleUpdateInterval.update(interval)
                workerRepository.scheduleBundleUpdateNotificationWork(interval)
            }
        }
    }

    fun updateBackgroundManagerUpdateTime(interval: SearchForUpdatesBackgroundInterval) {
        viewModelScope.launch {
            uiSafe(app, R.string.failed_to_check_updates, "Failed to update manager background schedule") {
                backgroundManagerUpdateInterval.update(interval)
                workerRepository.scheduleManagerUpdateNotificationWork(interval)
            }
        }
    }

    fun updateBundleUpdateDeliveryMode(mode: BundleUpdateDeliveryMode) {
        viewModelScope.launch {
            uiSafe(app, R.string.failed_to_check_updates, "Failed to update background delivery mode") {
                bundleUpdateDeliveryMode.update(mode)
            }
        }
    }

    fun updateAnnouncementPushNotificationTime(interval: SearchForUpdatesBackgroundInterval) {
        viewModelScope.launch {
            uiSafe(app, R.string.failed_to_check_updates, "Failed to update announcement notifications") {
                announcementPushNotificationInterval.update(interval)
                workerRepository.scheduleAnnouncementNotificationWork(
                    if (announcementSystemEnabled.get()) interval else SearchForUpdatesBackgroundInterval.NEVER
                )
            }
        }
    }

    fun updateAnnouncementSystemEnabled(enabled: Boolean) {
        viewModelScope.launch {
            uiSafe(app, R.string.failed_to_check_updates, "Failed to update announcement settings") {
                announcementSystemEnabled.update(enabled)

                workerRepository.scheduleAnnouncementNotificationWork(
                    if (enabled) {
                        announcementPushNotificationInterval.get()
                    } else {
                        SearchForUpdatesBackgroundInterval.NEVER
                    }
                )
                if (!enabled) {
                    app.getSystemService(NotificationManager::class.java)
                        ?.cancel(AnnouncementNotificationWorker.ANNOUNCEMENT_NOTIFICATION_ID)
                }
            }
        }
    }

    suspend fun checkForUpdates(): Boolean {
        uiSafe(app, R.string.failed_to_check_updates, "Failed to check for updates") {
            app.toast(app.getString(R.string.update_check))

            if (reVancedAPI.getAppUpdate() == null)
                app.toast(app.getString(R.string.no_update_available))
            else
                return true
        }

        return false
    }
}
