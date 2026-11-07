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
import kotlinx.serialization.Serializable

@Serializable
internal data class PendingRuntimeUpdate(
    val sourceId: String,
    val installedVersionKey: String?,
    val prerelease: Boolean?,
    val name: String,
    val version: String
) {
    val displayLine: String get() = "$name $version"
}

internal object RuntimeUpdateNotifications {
    private const val AVAILABLE_ID = 9017
    private const val UPDATED_ID = 9018

    fun showAvailable(context: Context, updates: List<PendingRuntimeUpdate>, onlyIfActive: Boolean = false) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (updates.isEmpty()) {
            manager.cancel(AVAILABLE_ID)
            return
        }
        if (onlyIfActive && manager.activeNotifications.none { it.id == AVAILABLE_ID && it.tag == null }) return
        val count = updates.size
        val title = context.resources.getQuantityString(R.plurals.runtime_updates_found, count)
        val header = context.resources.getQuantityString(R.plurals.runtime_updates_available_count, count, count)
        publish(context, manager, AVAILABLE_ID, title, header, updates)
    }

    fun showCompleted(context: Context, updates: List<PendingRuntimeUpdate>) {
        if (updates.isEmpty()) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val count = updates.size
        val title = context.resources.getQuantityString(R.plurals.runtime_updates_installed, count)
        val header = context.resources.getQuantityString(R.plurals.runtime_updates_installed_count, count, count)
        publish(context, manager, UPDATED_ID, title, header, updates)
    }

    private fun publish(
        context: Context,
        manager: NotificationManager,
        id: Int,
        title: String,
        header: String,
        updates: List<PendingRuntimeUpdate>
    ) {
        if (!context.hasNotificationPermission()) return
        val channel = "runtime-updates"
        manager.createNotificationChannel(NotificationChannel(
            channel, context.getString(R.string.runtime_updates_channel), NotificationManager.IMPORTANCE_DEFAULT
        ))
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_patcher_runtimes", true)
        }
        val pendingIntent = PendingIntent.getActivity(context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val body = updates.joinToString(separator = "\n", prefix = "$header\n") { "\u2022 ${it.displayLine}" }
        manager.notify(id, Notification.Builder(context, channel)
            .setContentTitle(title)
            .setContentText(header)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .applyManagerNotificationIcon(context)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build())
    }
}
