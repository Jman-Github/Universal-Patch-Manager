package app.universal.patch.manager.util

import android.app.Notification
import android.content.Context
import android.graphics.drawable.Icon
import app.universal.patch.manager.ManagerApplication
import app.universal.patch.manager.R

fun Notification.Builder.applyManagerNotificationIcon(context: Context): Notification.Builder {
    val icon = (context.applicationContext as ManagerApplication).notificationAppIcon()
    // Android renders the small notification icon as a monochrome silhouette.
    val statusIcon = if (icon == ManagerAppIcon.DEFAULT || icon.foreground == ManagerIconForeground.ORIGINAL) {
        R.drawable.ic_notification_status
    } else {
        R.drawable.ic_manager_notification_status
    }
    val artwork = if (icon == ManagerAppIcon.DEFAULT) {
        Icon.createWithResource(context, R.drawable.ic_notification_artwork)
            .setTint(context.getColor(R.color.notification_icon_tint))
    } else {
        Icon.createWithResource(context, icon.icon)
    }
    return setSmallIcon(Icon.createWithResource(context, statusIcon))
        .setLargeIcon(artwork)
}
