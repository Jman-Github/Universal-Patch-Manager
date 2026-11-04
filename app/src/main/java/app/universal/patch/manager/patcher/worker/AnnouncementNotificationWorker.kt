package app.universal.patch.manager.patcher.worker

import app.universal.patch.manager.util.applyManagerNotificationIcon
import android.app.Notification
import android.app.Notification.BigTextStyle
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import androidx.work.WorkerParameters
import app.universal.patch.manager.MainActivity
import app.universal.patch.manager.data.platform.NetworkInfo
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.domain.manager.SearchForUpdatesBackgroundInterval
import app.universal.patch.manager.domain.repository.AnnouncementRepository
import app.universal.patch.manager.domain.worker.Worker
import app.universal.patch.manager.ui.model.navigation.Announcement
import app.universal.patch.manager.util.AnnouncementDeepLinkIntent
import app.universal.patch.manager.util.permission.hasNotificationPermission
import app.universal.patch.manager.R
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class AnnouncementNotificationWorker(
    context: Context,
    parameters: WorkerParameters
) : Worker<AnnouncementNotificationWorker.Args>(context, parameters), KoinComponent {
    private val announcementRepository: AnnouncementRepository by inject()
    private val networkInfo: NetworkInfo by inject()
    private val prefs: PreferencesManager by inject()

    class Args

    private val announcementNotificationChannel = NotificationChannel(
        CHANNEL_ID,
        applicationContext.getString(R.string.notification_channel_announcements_name),
        NotificationManager.IMPORTANCE_HIGH
    )

    override suspend fun doWork(): Result {
        val wakeLock = runCatching {
            val powerManager =
                applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)?.apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        }.getOrNull()

        return try {
            val notificationManager =
                applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            announcementNotificationChannel.description =
                applicationContext.getString(R.string.notification_channel_announcements_description)
            notificationManager.createNotificationChannel(announcementNotificationChannel)

            if (
                !prefs.announcementSystemEnabled.get() ||
                prefs.announcementPushNotificationInterval.get() == SearchForUpdatesBackgroundInterval.NEVER
            ) {
                notificationManager.cancel(ANNOUNCEMENT_NOTIFICATION_ID)
                return Result.success()
            }

            if (!applicationContext.hasNotificationPermission()) {
                notificationManager.cancel(ANNOUNCEMENT_NOTIFICATION_ID)
                return Result.success()
            }

            if (!networkInfo.isConnected()) {
                return Result.success()
            }

            val announcements = announcementRepository.getAnnouncements(forceRefresh = true).orEmpty()
            if (announcements.isEmpty()) {
                return Result.success()
            }

            val currentIds = announcements.mapTo(linkedSetOf()) { it.id.toString() }
            val storedReadAnnouncements = prefs.readAnnouncements.get()
            val storedNotifiedAnnouncements = prefs.notifiedAnnouncements.get()
            val readAnnouncements = storedReadAnnouncements.intersect(currentIds)
            val notifiedAnnouncements = storedNotifiedAnnouncements.intersect(currentIds)

            if (storedReadAnnouncements.isEmpty()) {
                prefs.edit {
                    prefs.readAnnouncements.value = currentIds
                    prefs.notifiedAnnouncements.value = currentIds
                }
                notificationManager.cancel(ANNOUNCEMENT_NOTIFICATION_ID)
                return Result.success()
            }

            if (readAnnouncements != storedReadAnnouncements || notifiedAnnouncements != storedNotifiedAnnouncements) {
                prefs.edit {
                    prefs.readAnnouncements.value = readAnnouncements
                    prefs.notifiedAnnouncements.value = notifiedAnnouncements
                }
            }

            val latestAnnouncement = announcements.firstOrNull { announcement ->
                val id = announcement.id.toString()
                val notArchived = announcement.archivedAt
                    ?.toEpochMilliseconds()
                    ?.let { it > System.currentTimeMillis() }
                    ?: true
                notArchived && id !in readAnnouncements && id !in notifiedAnnouncements
            } ?: run {
                notificationManager.cancel(ANNOUNCEMENT_NOTIFICATION_ID)
                return Result.success()
            }

            val payload = Announcement.Payload.from(latestAnnouncement)
            val notification = buildNotification(
                title = applicationContext.getString(R.string.announcement_notification_title),
                description = latestAnnouncement.title,
                pendingIntent = buildAnnouncementPendingIntent(payload)
            )
            notificationManager.notify(ANNOUNCEMENT_NOTIFICATION_ID, notification)
            prefs.edit {
                prefs.notifiedAnnouncements += latestAnnouncement.id.toString()
            }
            Result.success()
        } catch (e: Exception) {
            Log.d("AnnouncementWorker", "Error during announcement check: ${e.message}")
            Result.failure()
        } finally {
            runCatching {
                if (wakeLock?.isHeld == true) wakeLock.release()
            }
        }
    }

    private fun buildAnnouncementPendingIntent(announcement: Announcement.Payload): PendingIntent {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            AnnouncementDeepLinkIntent.addOpenAnnouncement(this, announcement)
        }
        return PendingIntent.getActivity(
            applicationContext,
            announcement.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildNotification(
        title: String,
        description: String,
        pendingIntent: PendingIntent
    ): Notification {
        return Notification.Builder(applicationContext, announcementNotificationChannel.id)
            .setContentTitle(title)
            .setContentText(description)
            .setSubText(description)
            .setStyle(BigTextStyle().bigText(description))
            .applyManagerNotificationIcon(applicationContext)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "announcement-notification-channel"
        const val ANNOUNCEMENT_NOTIFICATION_ID = 9004
        private const val WAKE_LOCK_TAG = "upm:announcement_worker"
        private const val WAKE_LOCK_TIMEOUT_MS = 10L * 60L * 1000L
    }
}
