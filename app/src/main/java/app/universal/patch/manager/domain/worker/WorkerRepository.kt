package app.universal.patch.manager.domain.worker

import app.universal.patch.manager.util.applyManagerNotificationIcon
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.universal.patch.manager.R
import app.universal.patch.manager.domain.manager.AutoClearCacheInterval
import app.universal.patch.manager.domain.manager.SearchForUpdatesBackgroundInterval
import app.universal.patch.manager.patcher.worker.AnnouncementNotificationWorker
import app.universal.patch.manager.patcher.worker.AutoClearCacheWorker
import app.universal.patch.manager.patcher.worker.BundleUpdateNotificationWorker
import app.universal.patch.manager.patcher.worker.ManagerUpdateNotificationWorker
import app.universal.patch.manager.patcher.worker.PatcherWorkerProgressSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class UniqueWorkAlreadyRunningException(
    val workName: String
) : IllegalStateException("Another patching task is already running")

class WorkerRepository(app: Application) {
    val workManager = WorkManager.getInstance(app)

    private companion object {
        private const val BUNDLE_UPDATE_WORK_ID = "BundleUpdateNotificationWork"
        private const val MANAGER_UPDATE_WORK_ID = "ManagerUpdateNotificationWork"
        private const val ANNOUNCEMENT_WORK_ID = "AnnouncementNotificationWork"
        private const val AUTO_CLEAR_CACHE_WORK_ID = "AutoClearCacheWork"
        private const val BUNDLE_UPDATE_IMMEDIATE_WORK_ID = "BundleUpdateNotificationWorkImmediate"
        private const val MANAGER_UPDATE_IMMEDIATE_WORK_ID = "ManagerUpdateNotificationWorkImmediate"
        private const val ANNOUNCEMENT_IMMEDIATE_WORK_ID = "AnnouncementNotificationWorkImmediate"
        private const val AUTO_CLEAR_CACHE_IMMEDIATE_WORK_ID = "AutoClearCacheWorkImmediate"
    }

    /**
     * The standard WorkManager communication APIs use [androidx.work.Data], which has too many limitations.
     * We can get around those limits by passing inputs using global variables instead.
     */
    val workerInputs = mutableMapOf<UUID, Any>()
    @PublishedApi
    internal val activeUniqueWorkIds = ConcurrentHashMap<String, UUID>()
    @PublishedApi
    internal val activeWorkerProgressSnapshots = ConcurrentHashMap<UUID, PatcherWorkerProgressSnapshot>()

    @Suppress("UNCHECKED_CAST")
    fun <A : Any, W : Worker<A>> claimInput(worker: W): A {
        val data = workerInputs[worker.id] ?: throw IllegalStateException("Worker was not launched via WorkerRepository")
        workerInputs.remove(worker.id)

        return data as A
    }

    suspend inline fun <reified W : Worker<A>, A : Any> launchExpedited(
        name: String,
        input: A,
        onEnqueueRequested: (UUID) -> Unit = {}
    ): UUID {
        val request =
            OneTimeWorkRequest.Builder(W::class.java) // create Worker
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
        val existingId = activeUniqueWorkIds.putIfAbsent(name, request.id)
        if (existingId != null) {
            throw UniqueWorkAlreadyRunningException(name)
        }
        var enqueueRequested = false
        try {
            cancelPersistedUniqueWorkIfPresent(name)
            workerInputs[request.id] = input
            val operation = workManager.enqueueUniqueWork(
                name,
                ExistingWorkPolicy.REPLACE,
                request
            )
            enqueueRequested = true
            onEnqueueRequested(request.id)
            withContext(Dispatchers.IO) {
                operation.result.get()
            }
        } catch (error: Throwable) {
            val cancellationConfirmed = if (enqueueRequested) {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching {
                        workManager.cancelWorkById(request.id).result.get()
                    }.onFailure { cancellationError ->
                        Log.e(
                            "WorkManager",
                            "Failed to cancel partially submitted work $name (${request.id})",
                            cancellationError
                        )
                    }.isSuccess
                }
            } else {
                true
            }
            if (cancellationConfirmed) {
                workerInputs.remove(request.id)
                activeUniqueWorkIds.remove(name, request.id)
            }
            throw error
        }
        return request.id
    }

    fun activeUniqueWorkId(name: String): UUID? = activeUniqueWorkIds[name]

    fun isActiveUniqueWork(name: String, id: UUID) = activeUniqueWorkIds[name] == id

    suspend fun hasActiveUniqueWork(name: String): Boolean {
        if (activeUniqueWorkIds.containsKey(name)) return true
        cancelPersistedUniqueWorkIfPresent(name)
        return false
    }

    @PublishedApi
    internal suspend fun cancelPersistedUniqueWorkIfPresent(name: String): Boolean =
        withContext(Dispatchers.IO) {
            val persistedWorkIsActive = workManager.getWorkInfosForUniqueWork(name)
                .get()
                .any { !it.state.isFinished }
            if (!persistedWorkIsActive) return@withContext false
            workManager.cancelUniqueWork(name).result.get()
            true
        }

    fun clearActiveUniqueWork(name: String, id: UUID) {
        activeUniqueWorkIds.remove(name, id)
        activeWorkerProgressSnapshots.remove(id)
    }

    fun cancelUniqueWork(name: String, expectedId: UUID? = null) {
        val activeId = activeUniqueWorkIds[name]
        if (expectedId != null && activeId != expectedId) return
        activeUniqueWorkIds.remove(name)?.let { id ->
            workerInputs.remove(id)
            activeWorkerProgressSnapshots.remove(id)
        }
        workManager.cancelUniqueWork(name)
    }

    fun updateActiveProgressSnapshot(id: UUID, snapshot: PatcherWorkerProgressSnapshot) {
        activeWorkerProgressSnapshots.compute(id) { _, existing ->
            if (existing == null || isNewerProgressSnapshot(snapshot, existing)) {
                snapshot
            } else {
                existing
            }
        }
    }

    fun activeProgressSnapshot(id: UUID): PatcherWorkerProgressSnapshot? =
        activeWorkerProgressSnapshots[id]

    fun clearActiveProgressSnapshot(id: UUID) {
        activeWorkerProgressSnapshots.remove(id)
    }

    private fun isNewerProgressSnapshot(
        candidate: PatcherWorkerProgressSnapshot,
        existing: PatcherWorkerProgressSnapshot
    ): Boolean = when {
        candidate.generation > existing.generation -> true
        candidate.generation < existing.generation -> false
        else -> candidate.sequence > existing.sequence
    }

    inline fun <reified T> createNotification(
        context: Context,
        notificationChannel: NotificationChannel,
        title: String,
        description: String,
        groupKey: String? = null,
        isGroupSummary: Boolean = false
    ): Pair<Notification, NotificationManager> {
        val notificationIntent = Intent(context, T::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(notificationChannel)
        val builder = Notification.Builder(context, notificationChannel.id)
            .setContentTitle(title)
            .setContentText(description)
            .applyManagerNotificationIcon(context)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
        if (groupKey != null) {
            builder.setGroup(groupKey)
            if (isGroupSummary) {
                builder.setGroupSummary(true)
            }
        }
        return builder.build() to notificationManager
    }

    fun scheduleBundleUpdateNotificationWork(
        bundleUpdateTime: SearchForUpdatesBackgroundInterval
    ) {
        val workId = BUNDLE_UPDATE_WORK_ID
        if (bundleUpdateTime == SearchForUpdatesBackgroundInterval.NEVER) {
            workManager.cancelUniqueWork(workId)
            Log.d("WorkManager", "Cancelled job with workId $workId.")
            return
        }

        val workRequest =
            PeriodicWorkRequestBuilder<BundleUpdateNotificationWorker>(
                bundleUpdateTime.value,
                TimeUnit.MINUTES
            ).build()

        workManager.enqueueUniquePeriodicWork(
            workId,
            ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
            workRequest
        )
        Log.d(
            "WorkManager",
            "Periodic work $workId updated with time ${bundleUpdateTime.value}."
        )
    }

    fun ensureBundleUpdateNotificationWork(
        bundleUpdateTime: SearchForUpdatesBackgroundInterval
    ) {
        val workId = BUNDLE_UPDATE_WORK_ID
        if (bundleUpdateTime == SearchForUpdatesBackgroundInterval.NEVER) {
            workManager.cancelUniqueWork(workId)
            Log.d("WorkManager", "Cancelled job with workId $workId.")
            return
        }

        val workRequest =
            PeriodicWorkRequestBuilder<BundleUpdateNotificationWorker>(
                bundleUpdateTime.value,
                TimeUnit.MINUTES
            ).build()

        workManager.enqueueUniquePeriodicWork(
            workId,
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
        Log.d(
            "WorkManager",
            "Periodic work $workId reconciled with time ${bundleUpdateTime.value}."
        )
    }

    fun scheduleManagerUpdateNotificationWork(
        managerUpdateTime: SearchForUpdatesBackgroundInterval
    ) {
        val workId = MANAGER_UPDATE_WORK_ID
        if (managerUpdateTime == SearchForUpdatesBackgroundInterval.NEVER) {
            workManager.cancelUniqueWork(workId)
            Log.d("WorkManager", "Cancelled job with workId $workId.")
            return
        }

        val workRequest =
            PeriodicWorkRequestBuilder<ManagerUpdateNotificationWorker>(
                managerUpdateTime.value,
                TimeUnit.MINUTES
            ).build()

        workManager.enqueueUniquePeriodicWork(
            workId,
            ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
            workRequest
        )
        Log.d(
            "WorkManager",
            "Periodic work $workId updated with time ${managerUpdateTime.value}."
        )
    }

    fun ensureManagerUpdateNotificationWork(
        managerUpdateTime: SearchForUpdatesBackgroundInterval
    ) {
        val workId = MANAGER_UPDATE_WORK_ID
        if (managerUpdateTime == SearchForUpdatesBackgroundInterval.NEVER) {
            workManager.cancelUniqueWork(workId)
            Log.d("WorkManager", "Cancelled job with workId $workId.")
            return
        }

        val workRequest =
            PeriodicWorkRequestBuilder<ManagerUpdateNotificationWorker>(
                managerUpdateTime.value,
                TimeUnit.MINUTES
            ).build()

        workManager.enqueueUniquePeriodicWork(
            workId,
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
        Log.d(
            "WorkManager",
            "Periodic work $workId reconciled with time ${managerUpdateTime.value}."
        )
    }

    fun scheduleAnnouncementNotificationWork(
        announcementUpdateTime: SearchForUpdatesBackgroundInterval
    ) {
        val workId = ANNOUNCEMENT_WORK_ID
        if (announcementUpdateTime == SearchForUpdatesBackgroundInterval.NEVER) {
            workManager.cancelUniqueWork(workId)
            Log.d("WorkManager", "Cancelled job with workId $workId.")
            return
        }

        val workRequest =
            PeriodicWorkRequestBuilder<AnnouncementNotificationWorker>(
                announcementUpdateTime.value,
                TimeUnit.MINUTES
            ).build()

        workManager.enqueueUniquePeriodicWork(
            workId,
            ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
            workRequest
        )
        Log.d(
            "WorkManager",
            "Periodic work $workId updated with time ${announcementUpdateTime.value}."
        )
    }

    fun ensureAnnouncementNotificationWork(
        announcementUpdateTime: SearchForUpdatesBackgroundInterval
    ) {
        val workId = ANNOUNCEMENT_WORK_ID
        if (announcementUpdateTime == SearchForUpdatesBackgroundInterval.NEVER) {
            workManager.cancelUniqueWork(workId)
            Log.d("WorkManager", "Cancelled job with workId $workId.")
            return
        }

        val workRequest =
            PeriodicWorkRequestBuilder<AnnouncementNotificationWorker>(
                announcementUpdateTime.value,
                TimeUnit.MINUTES
            ).build()

        workManager.enqueueUniquePeriodicWork(
            workId,
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
        Log.d(
            "WorkManager",
            "Periodic work $workId reconciled with time ${announcementUpdateTime.value}."
        )
    }

    fun launchBundleUpdateNotificationNow(): UUID {
        val request = OneTimeWorkRequest.Builder(BundleUpdateNotificationWorker::class.java)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        workManager.enqueueUniqueWork(
            BUNDLE_UPDATE_IMMEDIATE_WORK_ID,
            ExistingWorkPolicy.REPLACE,
            request
        )
        return request.id
    }

    fun launchManagerUpdateNotificationNow(): UUID {
        val request = OneTimeWorkRequest.Builder(ManagerUpdateNotificationWorker::class.java)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        workManager.enqueueUniqueWork(
            MANAGER_UPDATE_IMMEDIATE_WORK_ID,
            ExistingWorkPolicy.REPLACE,
            request
        )
        return request.id
    }

    fun launchAnnouncementNotificationNow(): UUID {
        val request = OneTimeWorkRequest.Builder(AnnouncementNotificationWorker::class.java)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        workManager.enqueueUniqueWork(
            ANNOUNCEMENT_IMMEDIATE_WORK_ID,
            ExistingWorkPolicy.REPLACE,
            request
        )
        return request.id
    }

    fun scheduleAutoClearCacheWork(interval: AutoClearCacheInterval) {
        val workId = AUTO_CLEAR_CACHE_WORK_ID
        if (interval == AutoClearCacheInterval.NEVER) {
            workManager.cancelUniqueWork(workId)
            Log.d("WorkManager", "Cancelled job with workId $workId.")
            return
        }

        val workRequest =
            PeriodicWorkRequestBuilder<AutoClearCacheWorker>(
                interval.value,
                TimeUnit.MINUTES
            ).build()

        workManager.enqueueUniquePeriodicWork(
            workId,
            ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
            workRequest
        )
        Log.d("WorkManager", "Periodic work $workId updated with time ${interval.value}.")
    }

    fun ensureAutoClearCacheWork(interval: AutoClearCacheInterval) {
        val workId = AUTO_CLEAR_CACHE_WORK_ID
        if (interval == AutoClearCacheInterval.NEVER) {
            workManager.cancelUniqueWork(workId)
            Log.d("WorkManager", "Cancelled job with workId $workId.")
            return
        }

        val workRequest =
            PeriodicWorkRequestBuilder<AutoClearCacheWorker>(
                interval.value,
                TimeUnit.MINUTES
            ).build()

        workManager.enqueueUniquePeriodicWork(
            workId,
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
        Log.d("WorkManager", "Periodic work $workId reconciled with time ${interval.value}.")
    }

    fun launchAutoClearCacheNow(): UUID {
        val request = OneTimeWorkRequest.Builder(AutoClearCacheWorker::class.java)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        workManager.enqueueUniqueWork(
            AUTO_CLEAR_CACHE_IMMEDIATE_WORK_ID,
            ExistingWorkPolicy.REPLACE,
            request
        )
        return request.id
    }
}
