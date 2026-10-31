package app.universal.patch.manager.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.util.Log
import app.universal.patch.manager.R
import app.universal.patch.manager.MainActivity
import app.universal.patch.manager.service.SplitMergeTaskMonitorService
import app.universal.patch.manager.util.permission.hasNotificationPermission

object SplitMergeNotification {
    private const val CHANNEL_ID = "split-merge-progress-channel"
    const val NOTIFICATION_ID = 9006
    private const val TAG = "SplitMergeNotification"

    data class Progress(
        val max: Int,
        val current: Int,
        val indeterminate: Boolean = false
    )

    fun show(
        context: Context,
        contentText: CharSequence = context.getText(R.string.merge_split_notification_text),
        progress: Progress = Progress(max = 0, current = 0, indeterminate = true)
    ) {
        val appContext = context.applicationContext
        if (!appContext.hasNotificationPermission()) return

        runCatching {
            val manager = appContext.getSystemService(NotificationManager::class.java)
                ?: return@runCatching
            val notification = Notification.Builder(appContext, ensureChannel(appContext, manager))
                .setContentTitle(appContext.getText(R.string.merge_split_notification_title))
                .setContentText(contentText)
                .setSmallIcon(Icon.createWithResource(appContext, R.drawable.ic_notification_status))
                .setContentIntent(createPendingIntent(appContext))
                .applyProgressNotification(
                    context = appContext,
                    contentTitle = appContext.getText(R.string.merge_split_notification_title),
                    contentText = contentText,
                    max = progress.max,
                    current = progress.current,
                    indeterminate = progress.indeterminate
                )
                .build()
            if (SplitMergeTaskMonitorService.show(appContext, notification)) {
                manager.notify(NOTIFICATION_ID, notification)
            }
        }.onFailure { error ->
            Log.d(TAG, "Failed to publish split merge notification", error)
        }
    }

    fun clear(context: Context) {
        SplitMergeTaskMonitorService.clear()
        runCatching {
            context.applicationContext
                .getSystemService(NotificationManager::class.java)
                ?.cancel(NOTIFICATION_ID)
        }.onFailure { error ->
            Log.d(TAG, "Failed to clear split merge notification", error)
        }
    }

    private fun ensureChannel(
        context: Context,
        manager: NotificationManager
    ): String {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_split_merge_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notification_channel_split_merge_description)
        }
        manager.createNotificationChannel(channel)
        return channel.id
    }

    private fun createPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
