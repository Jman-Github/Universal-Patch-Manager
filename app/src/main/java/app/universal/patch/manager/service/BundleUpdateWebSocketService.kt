package app.universal.patch.manager.service

import app.universal.patch.manager.util.applyManagerNotificationIcon
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import app.universal.patch.manager.R
import app.universal.patch.manager.MainActivity
import app.universal.patch.manager.domain.manager.AutoClearCacheInterval
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.domain.manager.SearchForUpdatesBackgroundInterval
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.android.inject

class BundleUpdateWebSocketService : Service() {
    private val prefs: PreferencesManager by inject()

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val listenTargets = resolveListenTargets(intent)
        if (!listenTargets.any) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(
            NOTIFICATION_ID,
            buildNotification(listenTargets)
        )
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(listenTargets: ListenTargets): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val targetLabels = buildList {
            if (listenTargets.bundleUpdates) add(getString(R.string.bundle_update_websocket_target_bundle))
            if (listenTargets.managerUpdates) add(getString(R.string.bundle_update_websocket_target_manager))
            if (listenTargets.announcements) add(getString(R.string.bundle_update_websocket_target_announcements))
            if (listenTargets.cacheCleanup) add(getString(R.string.bundle_update_websocket_target_cache_cleanup))
        }
        val body = when (targetLabels.size) {
            0 -> ""
            1 -> getString(R.string.bundle_update_websocket_notification_description_single, targetLabels[0])
            2 -> getString(
                R.string.bundle_update_websocket_notification_description_pair,
                targetLabels[0],
                targetLabels[1]
            )
            3 -> getString(
                R.string.bundle_update_websocket_notification_description_triple,
                targetLabels[0],
                targetLabels[1],
                targetLabels[2]
            )
            else -> getString(
                R.string.bundle_update_websocket_notification_description_quad,
                targetLabels[0],
                targetLabels[1],
                targetLabels[2],
                targetLabels[3]
            )
        }

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.bundle_update_websocket_notification_title))
            .setContentText(body)
            .applyManagerNotificationIcon(this)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
    }

    private fun resolveListenTargets(intent: Intent?): ListenTargets {
        val bundleFromIntent = intent?.getBooleanExtra(EXTRA_LISTEN_BUNDLE_UPDATES, false)
        val managerFromIntent = intent?.getBooleanExtra(EXTRA_LISTEN_MANAGER_UPDATES, false)
        val announcementsFromIntent = intent?.getBooleanExtra(EXTRA_LISTEN_ANNOUNCEMENTS, false)
        val cacheCleanupFromIntent = intent?.getBooleanExtra(EXTRA_KEEP_ALIVE_CACHE_CLEANUP, false)
        val hasBundleExtra = intent?.hasExtra(EXTRA_LISTEN_BUNDLE_UPDATES) == true
        val hasManagerExtra = intent?.hasExtra(EXTRA_LISTEN_MANAGER_UPDATES) == true
        val hasAnnouncementsExtra = intent?.hasExtra(EXTRA_LISTEN_ANNOUNCEMENTS) == true
        val hasCacheCleanupExtra = intent?.hasExtra(EXTRA_KEEP_ALIVE_CACHE_CLEANUP) == true

        if (hasBundleExtra || hasManagerExtra || hasAnnouncementsExtra || hasCacheCleanupExtra) {
            return ListenTargets(
                bundleUpdates = bundleFromIntent == true,
                managerUpdates = managerFromIntent == true,
                announcements = announcementsFromIntent == true,
                cacheCleanup = cacheCleanupFromIntent == true
            )
        }

        val listenBundle = runBlocking {
            prefs.searchForUpdatesBackgroundInterval.get() != SearchForUpdatesBackgroundInterval.NEVER
        }
        val listenManager = runBlocking {
            prefs.searchForManagerUpdatesBackgroundInterval.get() != SearchForUpdatesBackgroundInterval.NEVER
        }
        val listenAnnouncements = runBlocking {
            prefs.announcementSystemEnabled.get() &&
                prefs.announcementPushNotificationInterval.get() != SearchForUpdatesBackgroundInterval.NEVER
        }
        val keepAliveForCacheCleanup = runBlocking {
            prefs.autoClearCacheInterval.get() != AutoClearCacheInterval.NEVER
        }
        return ListenTargets(
            bundleUpdates = listenBundle,
            managerUpdates = listenManager,
            announcements = listenAnnouncements,
            cacheCleanup = keepAliveForCacheCleanup
        )
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_bundle_websocket_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_bundle_websocket_description)
        }
        manager.createNotificationChannel(channel)
    }

    private data class ListenTargets(
        val bundleUpdates: Boolean,
        val managerUpdates: Boolean,
        val announcements: Boolean,
        val cacheCleanup: Boolean
    ) {
        val any: Boolean
            get() = bundleUpdates || managerUpdates || announcements || cacheCleanup
    }

    companion object {
        const val CHANNEL_ID = "bundle-update-websocket-channel"
        const val EXTRA_LISTEN_BUNDLE_UPDATES = "listen_bundle_updates"
        const val EXTRA_LISTEN_MANAGER_UPDATES = "listen_manager_updates"
        const val EXTRA_LISTEN_ANNOUNCEMENTS = "listen_announcements"
        const val EXTRA_KEEP_ALIVE_CACHE_CLEANUP = "keep_alive_cache_cleanup"
        private const val NOTIFICATION_ID = 9002
    }
}
