package app.universal.patch.manager.util

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import app.universal.patch.manager.R
import app.universal.patch.manager.MainActivity
import app.universal.patch.manager.util.permission.hasNotificationPermission
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class DownloadProgressNotifier(private val app: Application) {
    private val manager =
        app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val nextNotificationId = AtomicInteger(initialNotificationId())

    fun clearStaleNotifications() {
        ensureChannel()
        runCatching {
            manager.activeNotifications
                .filter { it.notification.channelId == CHANNEL_ID }
                .forEach { notification ->
                    if (notification.tag == null) manager.cancel(notification.id)
                    else manager.cancel(notification.tag, notification.id)
                }
        }
    }

    fun begin(title: String): Session {
        ensureChannel()
        return Session(nextNotificationId.getAndIncrement(), title)
    }

    inner class Session internal constructor(
        private val notificationId: Int,
        private var title: String
    ) {
        private val closed = AtomicBoolean(false)
        private val enabled = app.hasNotificationPermission()
        private var lastProgress = -1
        private var lastUpdateAt = -1L
        private var titleChanged = false

        init {
            update(0L, null)
        }

        @Synchronized
        fun update(bytesRead: Long, bytesTotal: Long?) {
            if (!enabled || closed.get()) return
            val total = bytesTotal?.takeIf { it > 0L }
            val progress = total?.let {
                (bytesRead.coerceIn(0L, it).toDouble() / it.toDouble() * PROGRESS_MAX)
                    .toInt()
                    .coerceAtMost(PROGRESS_MAX - 1)
            } ?: -1
            val now = SystemClock.elapsedRealtime()
            if (lastUpdateAt >= 0L && now - lastUpdateAt < MIN_UPDATE_INTERVAL_MS) return
            if (progress == lastProgress && !titleChanged && lastUpdateAt >= 0L) return
            lastProgress = progress
            lastUpdateAt = now
            titleChanged = false
            notify(progress.coerceAtLeast(0), progress < 0)
        }

        @Synchronized
        fun updateTitle(value: String) {
            if (closed.get() || value == title) return
            // The next progress/completion update publishes the resolved title. Avoid
            // two back-to-back notifications being throttled at the end of a download.
            title = value
            titleChanged = true
        }

        @Synchronized
        fun complete() {
            // A final replacement can be rate-limited, leaving the ongoing version
            // visible forever. Removal does not depend on accepting another update.
            cancel()
        }

        fun fail() = cancel()

        @Synchronized
        fun cancel() {
            closed.set(true)
            // A replacement progress notification can be throttled or kept as a live
            // update by the system. Explicitly remove failed/cancelled downloads.
            runCatching { manager.cancel(notificationId) }
        }

        private fun notify(
            progress: Int,
            indeterminate: Boolean,
            ongoing: Boolean = true,
            contentText: CharSequence? = defaultContentText(progress, indeterminate),
            timeoutAfter: Long = ACTIVE_NOTIFICATION_TIMEOUT_MS
        ) {
            runCatching {
                manager.notify(
                    notificationId,
                    createNotification(
                        notificationId = notificationId,
                        title = title,
                        progress = progress,
                        indeterminate = indeterminate,
                        ongoing = ongoing,
                        contentText = contentText,
                        timeoutAfter = timeoutAfter
                    )
                )
            }
        }
    }

    private fun ensureChannel() {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                app.getString(R.string.download_progress_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun createNotification(
        notificationId: Int,
        title: String,
        progress: Int,
        indeterminate: Boolean,
        ongoing: Boolean = true,
        contentText: CharSequence? = defaultContentText(progress, indeterminate),
        timeoutAfter: Long = ACTIVE_NOTIFICATION_TIMEOUT_MS
    ): Notification {
        val intent = Intent(app, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            app,
            notificationId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(app, CHANNEL_ID)
            .setContentTitle(title)
            .apply { contentText?.let(::setContentText) }
            .applyManagerNotificationIcon(app)
            .setContentIntent(pendingIntent)
            .setAutoCancel(!ongoing)
            .setTimeoutAfter(timeoutAfter)
            .applyProgressNotification(
                context = app,
                contentTitle = title,
                contentText = contentText,
                max = PROGRESS_MAX,
                current = progress,
                indeterminate = indeterminate,
                ongoing = ongoing
            )
            .build()
    }

    private fun defaultContentText(progress: Int, indeterminate: Boolean): CharSequence? =
        if (indeterminate) app.getString(R.string.download_progress_indeterminate) else null

    private fun initialNotificationId(): Int = runCatching {
        manager.activeNotifications
            .asSequence()
            .filter { it.notification.channelId == CHANNEL_ID }
            .map { it.id }
            .filter { it >= NOTIFICATION_ID_START }
            .maxOrNull()
            ?.takeIf { it < Int.MAX_VALUE }
            ?.plus(1)
            ?: NOTIFICATION_ID_START
    }.getOrDefault(NOTIFICATION_ID_START)

    private companion object {
        private const val CHANNEL_ID = "download_progress"
        private const val NOTIFICATION_ID_START = 31_000
        private const val PROGRESS_MAX = 1_000
        private const val MIN_UPDATE_INTERVAL_MS = 1_000L
        private const val ACTIVE_NOTIFICATION_TIMEOUT_MS = 30L * 60L * 1_000L
    }
}