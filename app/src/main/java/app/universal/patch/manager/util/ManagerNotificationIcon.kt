package app.universal.patch.manager.util

import android.app.Notification
import android.content.Context
import android.graphics.drawable.Icon
import app.universal.patch.manager.R

/** Keep the minimal mark in both the status bar and the notification's artwork slot. */
fun Notification.Builder.applyManagerNotificationIcon(context: Context): Notification.Builder =
    setSmallIcon(Icon.createWithResource(context, R.drawable.ic_notification_status))
        .setLargeIcon(
            Icon.createWithResource(context, R.drawable.ic_notification_artwork)
                .setTint(context.getColor(R.color.notification_icon_tint))
        )
