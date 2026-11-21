package app.universal.patch.manager.worker

import app.universal.patch.manager.util.applyManagerNotificationIcon
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.universal.patch.manager.R
import app.universal.patch.manager.MainActivity
import app.universal.patch.manager.domain.installer.root.RootMountFeedback
import app.universal.patch.manager.domain.installer.root.RootMountResult
import app.universal.patch.manager.domain.installer.root.RootMountTransactionCoordinator
import app.universal.patch.manager.util.permission.hasNotificationPermission

class RootMountReconcileWorker(
    context: Context,
    parameters: WorkerParameters,
    private val coordinator: RootMountTransactionCoordinator
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val userId = inputData.getInt(KEY_USER_ID, 0).coerceAtLeast(0)
        val packageName = inputData.getString(KEY_PACKAGE)
        val results = coordinator.reconcileCommittedTransactions(userId, packageName)
        results.forEach { (committedPackage, result) ->
            RootMountFeedback.automaticResult(applicationContext, committedPackage, result)
            handleResult(committedPackage, result)
        }
        return when {
            results.values.any { it is RootMountResult.Busy } -> Result.retry()
            results.values.any {
                it is RootMountResult.Failure || it is RootMountResult.RequiresDowngradeConfirmation
            } -> Result.failure()
            else -> Result.success()
        }
    }

    private fun handleResult(packageName: String, result: RootMountResult): Result =
        when (result) {
            is RootMountResult.Success -> {
                clearAttentionNotification(applicationContext, packageName)
                Result.success()
            }

            is RootMountResult.RecoveredToPreviousMount -> {
                notifyAttentionRequired(
                    applicationContext,
                    packageName,
                    applicationContext.getString(R.string.root_mount_recovered_title),
                    applicationContext.getString(
                        R.string.root_mount_recovered_previous_message,
                        result.diagnosticId
                    )
                )
                Result.success()
            }

            is RootMountResult.RecoveredToStock -> {
                notifyAttentionRequired(
                    applicationContext,
                    packageName,
                    applicationContext.getString(R.string.root_mount_recovered_title),
                    applicationContext.getString(
                        R.string.root_mount_recovered_stock_message,
                        result.diagnosticId
                    )
                )
                Result.success()
            }

            is RootMountResult.RequiresRepatch -> {
                notifyAttentionRequired(
                    applicationContext,
                    packageName,
                    applicationContext.getString(R.string.root_mount_repatch_title),
                    result.reason
                )
                Result.success()
            }

            is RootMountResult.Busy -> Result.retry()
            is RootMountResult.Failure -> {
                notifyAttentionRequired(
                    applicationContext,
                    packageName,
                    applicationContext.getString(R.string.root_mount_repair_notification_title),
                    applicationContext.getString(
                        R.string.root_mount_repair_notification_message,
                        result.message,
                        result.diagnosticId
                    )
                )
                Result.failure()
            }
            is RootMountResult.RequiresDowngradeConfirmation -> Result.failure()
        }

    companion object {
        const val KEY_PACKAGE = "package"
        const val KEY_ACTION = "action"
        const val KEY_USER_ID = "user_id"
        private const val CHANNEL_ID = "root-mount-reconciliation"
        private const val NOTIFICATION_BASE = 9200

        fun clearAttentionNotification(context: Context, packageName: String) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(notificationId(packageName))
        }

        fun notifyAttentionRequired(context: Context, packageName: String, title: String, reason: String) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.root_mount_notification_channel),
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
            if (!context.hasNotificationPermission()) return
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                packageName.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            manager.notify(
                notificationId(packageName),
                Notification.Builder(context, CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(reason)
                    .setStyle(Notification.BigTextStyle().bigText(reason))
                    .applyManagerNotificationIcon(context)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .build()
            )
        }

        private fun notificationId(packageName: String): Int =
            NOTIFICATION_BASE + (packageName.hashCode() and 0x0fff)
    }
}
