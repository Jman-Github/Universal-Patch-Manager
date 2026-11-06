package app.universal.patch.manager.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.universal.patch.manager.MainActivity
import app.universal.patch.manager.R
import app.universal.patch.manager.util.permission.hasNotificationPermission

internal object RuntimeUpdateNotifications {
    fun show(context: Context, available: List<String>, updated: List<String>, checksComplete: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // Clear stale availability when no sources still need a manual update.
        if (checksComplete && available.isEmpty()) manager.cancel(9017)
        if (!context.hasNotificationPermission()) return
        val channel = "runtime-updates"
        manager.createNotificationChannel(NotificationChannel(
            channel, context.getString(R.string.runtime_updates_channel), NotificationManager.IMPORTANCE_DEFAULT
        ))
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_patcher_runtimes", true)
        }
        val pendingIntent = PendingIntent.getActivity(context, 9017, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun publish(id: Int, titleRes: Int, names: List<String>) {
            if (names.isEmpty()) return
            val title = context.getString(titleRes)
            val style = Notification.InboxStyle().setBigContentTitle(title)
            names.distinct().forEach { style.addLine("• $it") }
            manager.notify(id, Notification.Builder(context, channel)
                .setContentTitle(title)
                .setContentText(names.distinct().joinToString(", "))
                .setStyle(style)
                .applyManagerNotificationIcon(context)
                .setContentIntent(pendingIntent)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .build())
        }
        publish(9017, R.string.runtime_updates_available, available)
        publish(9018, R.string.runtime_updates_completed, updated)
    }
}
