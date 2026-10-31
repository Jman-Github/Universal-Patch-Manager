/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.universal.patch.manager.patcher.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.universal.patch.manager.R
import app.universal.patch.manager.MainActivity
import app.universal.patch.manager.domain.batch.BatchInstallOutcome
import app.universal.patch.manager.domain.batch.BatchItemState
import app.universal.patch.manager.domain.batch.BatchInstallPolicy
import app.universal.patch.manager.domain.batch.BatchPatchCoordinator
import app.universal.patch.manager.domain.batch.BatchPhase
import app.universal.patch.manager.domain.batch.BatchRunState
import app.universal.patch.manager.domain.batch.BatchPlanResolver
import app.universal.patch.manager.domain.installer.InstallerManager
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.domain.manager.SearchForUpdatesBackgroundInterval
import app.universal.patch.manager.domain.repository.PatchBundleRepository
import app.universal.patch.manager.domain.worker.WorkerRepository
import app.universal.patch.manager.util.BatchPatchIntents
import app.universal.patch.manager.util.permission.hasNotificationPermission
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

internal suspend fun reconcileAutoPatchNotificationPermission(
    context: Context,
    prefs: PreferencesManager
): Boolean {
    if (context.hasNotificationPermission()) return true
    if (prefs.autoPatchEnabled.get()) {
        prefs.updateAutoPatchEnabled(false)
    }
    AutoPatchWorker.cancel(context)
    return false
}

// Code adapted from Morphe, see third-party/NOTICE for more information
// https://github.com/MorpheApp/morphe-manager/pull/795
class AutoPatchWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {
    private val prefs: PreferencesManager by inject()
    private val resolver: BatchPlanResolver by inject()
    private val patchBundleRepository: PatchBundleRepository by inject()
    private val coordinator: BatchPatchCoordinator by inject()
    private val installerManager: InstallerManager by inject()
    private val workerRepository: WorkerRepository by inject()

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(notification(running = true, count = 0))

    private fun foregroundInfo(notification: Notification) = ForegroundInfo(
        NOTIFICATION_ID_RUNNING,
        notification,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
    )

    override suspend fun doWork(): Result {
        if (!prefs.autoPatchEnabled.get()) return Result.success()
        if (!reconcileAutoPatchNotificationPermission(applicationContext, prefs)) {
            return Result.success()
        }

        if (workerRepository.hasActiveUniqueWork(PatcherWorker.UNIQUE_WORK_NAME)) {
            return Result.retry()
        }

        val powerManager = applicationContext.getSystemService(PowerManager::class.java)
        if (powerManager?.isIgnoringBatteryOptimizations(applicationContext.packageName) != true) {
            showBlockedNotification(R.string.auto_patch_blocked_battery)
            return Result.success()
        }

        try {
            patchBundleRepository.updateCheck()
        } catch (error: Exception) {
            Log.w("UPM AutoPatch", "Unable to refresh patch bundles", error)
            return Result.retry()
        }

        val packages = resolver.findOutdatedPackages(onlyAutoPatchEnabled = true)
        if (packages.isEmpty()) return Result.success()

        try {
            setForeground(
                foregroundInfo(
                    notification(
                        running = true,
                        count = packages.size,
                        packageNames = packages
                    )
                )
            )
        } catch (error: Exception) {
            Log.w("UPM AutoPatch", "Unable to promote automatic patching to foreground", error)
            showBlockedNotification(R.string.auto_patch_blocked_foreground)
            return Result.success()
        }
        var ownsCoordinator = false
        var completed = false
        try {
            ownsCoordinator = coordinator.plan(
                packageNames = packages,
                policy = BatchInstallPolicy.SAVE_ONLY,
                scheduled = true
            )
            if (!ownsCoordinator) return Result.retry()

            val planned = coordinator.state.first {
                it?.phase == BatchPhase.PREFLIGHT || it?.phase == BatchPhase.FINISHED
            } ?: return Result.retry()
            if (planned.phase == BatchPhase.FINISHED) {
                showResultNotification(
                    state = planned,
                    textOverride = applicationContext.resources.getQuantityString(
                        R.plurals.auto_patch_preparation_failed,
                        planned.failed.coerceAtLeast(1),
                        planned.failed.coerceAtLeast(1)
                    )
                )
                completed = true
                return Result.success()
            }
            if (planned.runnable.isEmpty()) {
                val finished = coordinator.finishPreflight() ?: planned
                showResultNotification(
                    state = finished,
                    textOverride = applicationContext.resources.getQuantityString(
                        R.plurals.auto_patch_needs_attention,
                        finished.skipped.coerceAtLeast(1),
                        finished.skipped.coerceAtLeast(1)
                    )
                )
                completed = true
                return Result.success()
            }

            coordinator.start()
            var finished = coordinator.state.first {
                it?.phase == BatchPhase.FINISHED
            } ?: return Result.retry()

            val allSaved = coordinator.saveAllForLater()
            finished = coordinator.state.value ?: finished

            var installBlockedByShizuku = false
            if (
                allSaved && prefs.autoPatchInstallWithShizuku.get() &&
                finished.patchedItems.isNotEmpty()
            ) {
                val shizukuStatus = installerManager.shizukuStatus(
                    InstallerManager.InstallTarget.PATCHER
                )
                if (shizukuStatus.availability.available) {
                    coordinator.installAll(forceShizuku = true)
                    finished = coordinator.state.first {
                        it?.phase == BatchPhase.FINISHED &&
                            it.patchedItems.all { item -> item.installOutcome != null }
                    } ?: finished
                } else {
                    installBlockedByShizuku = true
                }
            }

            showResultNotification(
                state = finished,
                saveFailed = !allSaved,
                textOverride = if (!allSaved) {
                    applicationContext.getString(R.string.auto_patch_save_failed)
                } else if (installBlockedByShizuku) {
                    applicationContext.getString(
                        R.string.auto_patch_install_skipped_shizuku
                    )
                } else {
                    null
                }
            )
            completed = true
            return Result.success()
        } finally {
            withContext(NonCancellable) {
                if (ownsCoordinator && !completed) {
                    coordinator.cancel()
                }
                coordinator.shutdown()
            }
        }
    }

    private fun showResultNotification(
        state: BatchRunState,
        textOverride: String? = null,
        saveFailed: Boolean = false
    ) {
        val failed = saveFailed || state.items.any {
            it.state != BatchItemState.SUCCEEDED || it.hadPatchFailures ||
                it.installOutcome == BatchInstallOutcome.FAILED
        }
        applicationContext.getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID_FINISHED,
            notification(
                running = false,
                count = state.succeeded,
                installed = state.patchedItems.count {
                    it.installOutcome == BatchInstallOutcome.INSTALLED
                },
                textOverride = textOverride,
                openBatchResult = failed,
                packageNames = state.items.map { item ->
                    // Batch results use input packages; app details use persisted entry keys.
                    if (failed) item.packageName
                    else item.sourceEntryKey ?: item.installedPackageName ?: item.packageName
                },
                appNames = state.items.map { it.appName.ifBlank { it.packageName } },
                failed = failed
            )
        )
    }

    private fun showBlockedNotification(message: Int) {
        applicationContext.getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID_FINISHED,
            notification(
                running = false,
                count = 0,
                textOverride = applicationContext.getString(message),
                failed = true
            )
        )
    }

    private fun notification(
        running: Boolean,
        count: Int,
        installed: Int = 0,
        textOverride: String? = null,
        packageNames: List<String> = emptyList(),
        appNames: List<String> = emptyList(),
        failed: Boolean = false,
        openBatchResult: Boolean = false
    ): Notification {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.batch_patch_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val intent = BatchPatchIntents.markInternal(
            applicationContext,
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (!running) {
                    action = if (openBatchResult || failed) BatchPatchIntents.ACTION_SHOW_RESULT
                        else BatchPatchIntents.ACTION_SHOW_APPS
                    putExtra(BatchPatchIntents.EXTRA_SCHEDULED, true)
                    if (packageNames.isNotEmpty()) {
                        putExtra(BatchPatchIntents.EXTRA_PACKAGES, packageNames.toTypedArray())
                    }
                }
            }
        )
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            795,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = textOverride ?: if (running) {
            applicationContext.resources.getQuantityString(
                R.plurals.auto_patch_notification_running,
                count,
                count
            )
        } else if (failed) {
            applicationContext.getString(R.string.auto_patch_notification_failed)
        } else if (installed > 0) {
            applicationContext.resources.getQuantityString(
                R.plurals.auto_patch_notification_finished_installed,
                count,
                count,
                installed
            )
        } else {
            applicationContext.resources.getQuantityString(
                R.plurals.auto_patch_notification_finished,
                count,
                count
            )
        }
        return Notification.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(
                Icon.createWithResource(
                    applicationContext,
                    R.drawable.ic_notification_status
                )
            )
            .setContentTitle(applicationContext.getString(
                if (running) R.string.auto_patch_notification_title
                else if (failed) R.string.auto_patch_notification_failed_title
                else R.string.auto_patch_notification_finished_title
            ))
            .setContentText(text)
            .setStyle(Notification.InboxStyle().addLine(text).apply {
                appNames.forEach { addLine("- $it") }
            })
            .apply { if (!running) setContentIntent(pendingIntent) }
            .setOngoing(running)
            .setAutoCancel(!running)
            .build()
    }

    companion object {
        const val WORK_NAME = "upm_auto_patch"
        private const val CHANNEL_ID = "upm-batch-patching"
        private const val NOTIFICATION_ID_RUNNING = 795
        private const val NOTIFICATION_ID_FINISHED = 796

        suspend fun schedule(
            context: Context,
            prefs: PreferencesManager,
            interval: SearchForUpdatesBackgroundInterval,
            requiresCharging: Boolean
        ) {
            if (interval == SearchForUpdatesBackgroundInterval.NEVER) {
                if (prefs.autoPatchEnabled.get()) {
                    prefs.updateAutoPatchEnabled(false)
                }
                cancel(context)
                return
            }
            if (!reconcileAutoPatchNotificationPermission(context, prefs)) {
                cancel(context)
                return
            }
            val request = PeriodicWorkRequestBuilder<AutoPatchWorker>(
                interval.value,
                TimeUnit.MINUTES
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .setRequiresStorageNotLow(true)
                        .setRequiresCharging(requiresCharging)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
