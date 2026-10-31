package app.universal.patch.manager.patcher.worker

import app.universal.patch.manager.data.platform.SharedApkFiles
import app.universal.patch.manager.patcher.runtime.usage.PatcherResourceMonitor

import app.universal.patch.manager.patcher.runtime.RevancedJvmRuntime
import android.app.Activity
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Parcelable
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.universal.patch.manager.MainActivity
import app.universal.patch.manager.R
import app.universal.patch.manager.data.platform.Filesystem
import app.universal.patch.manager.data.room.apps.installed.InstallType
import app.universal.patch.manager.domain.installer.root.RootMountOperation
import app.universal.patch.manager.domain.installer.root.RootMountRequest
import app.universal.patch.manager.domain.installer.root.RootMountTransactionCoordinator
import app.universal.patch.manager.domain.installer.root.requireSuccess
import app.universal.patch.manager.domain.manager.KeystoreManager
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.domain.manager.SignatureMetadataInjectorManager
import app.universal.patch.manager.domain.repository.DownloadResult
import app.universal.patch.manager.domain.repository.DownloadedAppRepository
import app.universal.patch.manager.domain.repository.DownloaderPluginRepository
import app.universal.patch.manager.domain.repository.InstalledAppRepository
import app.universal.patch.manager.domain.repository.PatchBundleRepository
import app.universal.patch.manager.domain.storage.PatcherWorkspaceGate
import app.universal.patch.manager.domain.worker.Worker
import app.universal.patch.manager.domain.worker.WorkerRepository
import app.universal.patch.manager.network.downloader.LoadedDownloaderPlugin
import app.universal.patch.manager.patcher.ProgressEvent
import app.universal.patch.manager.patcher.RemoteError
import app.universal.patch.manager.patcher.StepId
import app.universal.patch.manager.patcher.toSafeRemoteError
import app.universal.patch.manager.patcher.logger.Logger
import app.universal.patch.manager.patcher.logger.LogLevel
import app.universal.patch.manager.patcher.logger.allows
import app.universal.patch.manager.patcher.split.SplitArchiveDisplayResolver
import app.universal.patch.manager.patcher.split.SplitApkPreparer
import app.universal.patch.manager.patcher.split.SplitMergeRuntime
import app.universal.patch.manager.patcher.morphe.MorpheBridgeFailureException
import app.universal.patch.manager.patcher.revanced.Revanced21BridgeFailureException
import app.universal.patch.manager.patcher.revanced.Revanced22BridgeFailureException
import app.universal.patch.manager.patcher.runtime.morphe.MorpheJvmRuntime
import app.universal.patch.manager.patcher.runtime.morphe.MorpheRuntimeAssets
import app.universal.patch.manager.patcher.runtime.revanced.Revanced21RuntimeAssets
import app.universal.patch.manager.patcher.runCancellableBlockingIo
import app.universal.patch.manager.patcher.runStep
import app.universal.patch.manager.patcher.toRemoteError
import app.universal.patch.manager.patcher.patch.PatchBundleType
import app.universal.patch.manager.patcher.patch.patcherEngineDisplayName
import app.universal.patch.manager.plugin.downloader.GetScope
import app.universal.patch.manager.plugin.downloader.PluginHostApi
import app.universal.patch.manager.plugin.downloader.UserInteractionException
import app.universal.patch.manager.ui.model.SelectedApp
import app.universal.patch.manager.util.AppForeground
import app.universal.patch.manager.util.applyProgressNotification
import app.universal.patch.manager.util.Options
import app.universal.patch.manager.util.PM
import app.universal.patch.manager.util.PatchSelection
import app.universal.patch.manager.util.tag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.measureTime

@OptIn(PluginHostApi::class)
class PatcherWorker(
    context: Context,
    parameters: WorkerParameters
) : Worker<PatcherWorker.Args>(context, parameters), KoinComponent {
    private val workerRepository: WorkerRepository by inject()
    private val prefs: PreferencesManager by inject()
    private val keystoreManager: KeystoreManager by inject()
    private val downloaderPluginRepository: DownloaderPluginRepository by inject()
    private val downloadedAppRepository: DownloadedAppRepository by inject()
    private val pm: PM by inject()
    private val fs: Filesystem by inject()
    private val signatureMetadataInjector: SignatureMetadataInjectorManager by inject()
    private val installedAppRepository: InstalledAppRepository by inject()
    private val rootMountCoordinator: RootMountTransactionCoordinator by inject()
    private val patchBundleRepository: PatchBundleRepository by inject()
    private var activeRuntime: app.universal.patch.manager.patcher.runtime.Runtime? = null
    private var activeMorpheRuntime: app.universal.patch.manager.patcher.runtime.morphe.MorpheRuntime? = null
    private var activeSplitMergeRuntime: SplitMergeRuntime? = null
    @Volatile
    private var patchNotificationSteps: List<String> = emptyList()
    @Volatile
    private var patchExecutionOrder: List<Int> = emptyList()
    @Volatile
    private var foregroundStarted: Boolean = false
    @Volatile
    private var lastNotificationProgressCurrent: Int = 0
    @Volatile
    private var notificationSplitPreparationSeen: Boolean = false
    @Volatile
    private var lastWriteApkNotificationPhaseIndex: Int = -1
    @Volatile
    private var lastWriteApkNotificationDetail: String? = null
    @Volatile
    private var lastForegroundNotificationSequence: Long = Long.MIN_VALUE
    private val notificationStateLock = Any()
    private val workerProgressScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val workerProgressMutex = Mutex()
    private val progressSequence = AtomicLong(0L)
    private val progressGeneration = SystemClock.elapsedRealtimeNanos()
    private var progressPersistenceClosed: Boolean = false
    private var lastPersistedProgressSequence: Long = Long.MIN_VALUE
    private val cachedExpandableSubSteps = ConcurrentHashMap<StepId, List<String>>()
    private val notificationExpandableSubSteps = ConcurrentHashMap<StepId, List<String>>()
    private val failedPatchIndexes = ConcurrentHashMap.newKeySet<Int>()
    @Volatile
    private var activePatchBundleType: PatchBundleType? = null
    @Volatile
    private var activeMorpheDexGroupTitle: String? = null
    @Volatile
    private var activeMorpheDexChildTitle: String? = null
    @Volatile
    private var lastPatcherMemoryUsage: PatcherMemoryUsage? = null
    @Volatile
    private var requestedPatcherMemoryLimitMb: Long? = null
    @Volatile
    private var batchQueueLabel: String? = null
    @Volatile
    private var batchAppLabel: String? = null
    @Volatile
    private var allowBackgroundExecution: Boolean = true
    private val dexCompilePattern =
        Regex("(Compiling|Compiled)\\s+(classes\\d*\\.dex)", RegexOption.IGNORE_CASE)
    private val dexWritePattern =
        Regex("Write\\s+\\[[^\\]]+\\]\\s+(classes\\d*\\.dex)", RegexOption.IGNORE_CASE)
    private val morpheProcessingClassesPattern =
        Regex(
            "Processing\\s+(\\d+)\\s+classes(?:\\s+in\\s+parallel|\\s+\\(single threaded mode\\))",
            RegexOption.IGNORE_CASE
        )
    private val morpheWroteDexFilesPattern =
        Regex("Wrote\\s+(\\d+)\\s+dex\\s+files\\b", RegexOption.IGNORE_CASE)
    private val morpheStrippedDexPattern =
        Regex(
            "Stripped\\s+\\d+\\s+class_def\\s+entries\\s+from\\s+(classes\\d*\\.dex)",
            RegexOption.IGNORE_CASE
        )
    private val notificationManager by lazy {
        applicationContext.getSystemService(NotificationManager::class.java)
    }
    private val patchingServiceType by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
    }

    class Args(
        val input: SelectedApp,
        val output: String,
        val selectedPatches: PatchSelection,
        val options: Options,
        val skipApkSigning: Boolean,
        val logger: Logger,
        val preparedInput: DownloadResult?,
        val splitSelection: SplitSelection?,
        val handleStartActivityRequest: suspend (LoadedDownloaderPlugin, Intent) -> ActivityResult,
        val setInputFile: suspend (File, Boolean, Boolean) -> Unit,
        val onEvent: (PatcherWorkerProgressUpdate) -> Unit,
        val setInputMetadata: suspend (String?, Long?) -> Unit = { _, _ -> },
        val queuePosition: Int? = null,
        val queueSize: Int? = null,
        val appName: String? = null,
        val allowBackgroundExecution: Boolean = false,
        val signatureMetadataOutput: String? = null
    ) {
        val packageName get() = input.packageName
    }

    data class SplitSelection(
        val includedModules: Set<String>,
        val stripNativeLibs: Boolean
    )

    override suspend fun getForegroundInfo() = createForegroundInfo(event = null, totalPatchCount = 0)

    private fun createForegroundInfo(
        event: ProgressEvent?,
        totalPatchCount: Int
    ): ForegroundInfo = createForegroundInfo(createNotification(event, totalPatchCount))

    private fun createForegroundInfo(notification: Notification): ForegroundInfo = ForegroundInfo(
        NOTIFICATION_ID,
        notification,
        patchingServiceType
    )

    private fun createNotification(
        event: ProgressEvent?,
        totalPatchCount: Int
    ): Notification {
        val progress = normalizeNotificationProgress(notificationProgress(event, totalPatchCount))
        val contentText = notificationContentText(event, totalPatchCount)
        return createNotificationBuilder(
            context = applicationContext,
            title = notificationTitle()
        )
            .setContentText(contentText)
            .apply {
                if (progress != null) {
                    applyProgressNotification(
                        max = progress.max,
                        current = progress.current,
                        indeterminate = progress.indeterminate
                    )
                } else {
                    setProgress(0, 0, false)
                }
            }
            .build()
    }

    private fun notificationContentText(
        event: ProgressEvent?,
        totalPatchCount: Int
    ): CharSequence {
        val stepText = event?.stepId?.let { step -> notificationStepTitle(step, totalPatchCount) }

        val detail = when (event) {
            is ProgressEvent.Progress -> normalizeNotificationDetail(event.stepId, event.message)
            else -> null
        }

        val base = when {
            stepText != null && detail != null -> "$stepText • $detail"
            stepText != null -> stepText
            else -> applicationContext.getText(R.string.patcher_notification_text)
        }
        return batchQueueLabel?.let { queue -> "$queue • $base" } ?: base
    }

    private fun notificationTitle(): CharSequence = batchAppLabel
        ?.let { appName ->
            applicationContext.getString(R.string.batch_patch_patching_app, appName)
        }
        ?: applicationContext.getText(R.string.patcher_notification_title)

    private fun normalizeNotificationDetail(stepId: StepId?, message: String?): String? {
        val detail = message?.takeIf { it.isNotBlank() } ?: return null
        if (stepId != StepId.WriteAPK) return detail
        return normalizeWriteApkNotificationDisplayDetail(detail.trim())
    }

    private fun normalizeWriteApkNotificationDisplayDetail(detail: String): String? {
        val trimmed = detail.trim()
        if (!isActiveMorpheWriteApkUi()) return trimmed

        val normalized = when {
            trimmed.equals("Copying base APK", ignoreCase = true) -> "Copy base APK"
            trimmed.equals("Copy base APK", ignoreCase = true) -> "Copy base APK"
            trimmed.equals("Applying patched changes", ignoreCase = true) -> "Applying patched changes"
            trimmed.contains("Writing patched files", ignoreCase = true) -> currentWriteApkNotificationDexDisplayDetail()
            trimmed.equals("Compiling patched dex files", ignoreCase = true) ||
                trimmed.startsWith("Compiling patched dex files (mode:", ignoreCase = true) ->
                currentWriteApkNotificationDexDisplayDetail()
            trimmed.equals("Compiling modified resources", ignoreCase = true) ||
                trimmed.equals("Compiled modified resources", ignoreCase = true) ||
                trimmed.equals("Compiling patched resources", ignoreCase = true) ||
                trimmed.equals("Compiled patched resources", ignoreCase = true) ->
                "Compiling modified resources"
            trimmed.equals("Writing output APK", ignoreCase = true) ||
                trimmed.contains("Patched apk saved to", ignoreCase = true) ->
                "Writing output APK"
            trimmed.equals("Finalizing output", ignoreCase = true) -> "Finalizing output"
            trimmed.equals("Stripping native libraries", ignoreCase = true) -> "Stripping native libraries"
            isWriteApkDexNotificationTitle(trimmed) ->
                currentWriteApkNotificationDexDisplayDetail(trimmed)
            morpheProcessingClassesPattern.containsMatchIn(trimmed) ->
                currentWriteApkNotificationDexDisplayDetail(
                    "Processing ${morpheProcessingClassesPattern.find(trimmed)?.groupValues?.get(1)} classes"
                )
            morpheWroteDexFilesPattern.containsMatchIn(trimmed) ->
                currentWriteApkNotificationDexDisplayDetail(
                    "Wrote ${morpheWroteDexFilesPattern.find(trimmed)?.groupValues?.get(1)} dex files"
                )
            morpheStrippedDexPattern.containsMatchIn(trimmed) ->
                currentWriteApkNotificationDexDisplayDetail(
                    "Modified ${morpheStrippedDexPattern.find(trimmed)?.groupValues?.get(1)}"
                )
            dexCompilePattern.containsMatchIn(trimmed) || dexWritePattern.containsMatchIn(trimmed) -> null
            else -> trimmed
        }

        val lastDetail = lastWriteApkNotificationDetail ?: return normalized
        val normalizedPhase = normalized
            ?.let(::normalizeWriteApkNotificationProgressDetail)
            ?.let(::writeApkNotificationPhaseIndex)
            ?: -1
        return if (normalizedPhase != -1 && normalizedPhase < lastWriteApkNotificationPhaseIndex) {
            lastDetail
        } else {
            normalized
        }
    }

    private fun notificationStepTitle(step: StepId, totalPatchCount: Int): String = when (step) {
        StepId.DownloadAPK -> applicationContext.getString(R.string.download_apk)
        StepId.LoadPatches -> applicationContext.getString(R.string.patcher_step_load_patches)
        StepId.PrepareSplitApk -> applicationContext.getString(
            if (activePatchBundleType == PatchBundleType.RESEAM) R.string.patcher_step_prepare_separate_apks
            else R.string.patcher_step_prepare_split_apk
        )
        StepId.ReadAPK -> applicationContext.getString(R.string.patcher_step_unpack)
        StepId.ExecutePatches -> applicationContext.getString(R.string.execute_patches)
        is StepId.ExecutePatch -> {
            patchNotificationSteps.getOrNull(step.index)?.takeIf { it.isNotBlank() } ?: run {
                if (totalPatchCount > 0) {
                    val current = (step.index + 1).coerceIn(1, totalPatchCount)
                    "${applicationContext.getString(R.string.execute_patches)} ($current/$totalPatchCount)"
                } else {
                    applicationContext.getString(R.string.execute_patches)
                }
            }
        }
        StepId.WriteAPK -> applicationContext.getString(R.string.patcher_step_write_patched)
        StepId.SignAPK -> applicationContext.getString(R.string.patcher_step_sign_apk)
    }

    private data class NotificationProgress(
        val max: Int,
        val current: Int,
        val indeterminate: Boolean
    )


    private var reseamSplitSubSteps: List<String> = emptyList()

    private fun notificationProgress(
        event: ProgressEvent?,
        totalPatchCount: Int
    ): NotificationProgress? {
        if (event == null) return NotificationProgress(max = 0, current = 0, indeterminate = true)
        if (event.stepId == StepId.PrepareSplitApk) {
            when (event) {
                is ProgressEvent.Started -> reseamSplitSubSteps = event.subSteps.orEmpty()
                is ProgressEvent.Progress -> event.subSteps?.let { reseamSplitSubSteps = it }
                else -> Unit
            }
        }
        return when (event) {
            is ProgressEvent.Started -> if (event.stepId == StepId.DownloadAPK) {
                NotificationProgress(max = 0, current = 0, indeterminate = true)
            } else {
                notificationStageProgress(event.stepId, totalPatchCount, 0f)
            }
            is ProgressEvent.Progress -> {
                val total = event.total?.takeIf { it > 0L }
                val current = event.current
                if (event.stepId == StepId.DownloadAPK) {
                    if (total != null && current != null) {
                        val maxInt = min(total, Int.MAX_VALUE.toLong()).toInt()
                        val curInt = min(current, maxInt.toLong()).toInt()
                        NotificationProgress(max = maxInt, current = curInt, indeterminate = false)
                    } else {
                        NotificationProgress(max = 0, current = 0, indeterminate = true)
                    }
                } else {
                    notificationStageProgress(
                        stepId = event.stepId,
                        totalPatchCount = totalPatchCount,
                        fraction = if (activePatchBundleType == PatchBundleType.RESEAM) {
                            reseamStageFraction(event, reseamSplitSubSteps)
                                ?: if (total != null && current != null) {
                                    (current.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                                } else 0f
                        } else when {
                            total != null && current != null ->
                                (current.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                            event.stepId == StepId.WriteAPK -> notificationWriteApkFraction(event)
                            else -> 0.5f
                        }
                    )
                }
            }
            is ProgressEvent.Completed -> if (event.stepId == StepId.DownloadAPK) {
                NotificationProgress(max = 0, current = 0, indeterminate = true)
            } else {
                notificationStageProgress(event.stepId, totalPatchCount, 1f)
            }
            is ProgressEvent.Failed -> null
        }
    }

    private fun normalizeNotificationProgress(progress: NotificationProgress?): NotificationProgress? {
        if (progress == null) return null
        if (progress.indeterminate || progress.max <= 0) return progress

        val current = progress.current
            .coerceAtLeast(lastNotificationProgressCurrent)
            .coerceAtMost(progress.max)
        lastNotificationProgressCurrent = current
        return if (current == progress.current) progress else progress.copy(current = current)
    }

    private fun notificationStageProgress(
        stepId: StepId,
        totalPatchCount: Int,
        fraction: Float
    ): NotificationProgress {
        val normalized = fraction.coerceIn(0f, 1f)
        val current = when (stepId) {
            StepId.DownloadAPK -> 0
            StepId.LoadPatches -> {
                val (start, end) = loadPatchesNotificationRange()
                progressInRange(start, end, normalized)
            }
            StepId.PrepareSplitApk -> {
                val (start, end) = prepareSplitNotificationRange()
                progressInRange(start, end, normalized)
            }
            StepId.ReadAPK -> progressInRange(READ_APK_START, READ_APK_END, normalized)
            StepId.ExecutePatches -> progressInRange(
                EXECUTE_PATCHES_START,
                EXECUTE_PATCHES_END,
                normalized
            )
            is StepId.ExecutePatch -> notificationExecutePatchProgress(stepId, totalPatchCount, normalized)
            StepId.WriteAPK -> progressInRange(WRITE_APK_START, WRITE_APK_END, normalized)
            StepId.SignAPK -> progressInRange(SIGN_APK_START, SIGN_APK_END, normalized)
        }
        return NotificationProgress(
            max = NOTIFICATION_PROGRESS_MAX,
            current = current,
            indeterminate = false
        )
    }

    private fun loadPatchesNotificationRange(): Pair<Int, Int> {
        return if (notificationSplitPreparationSeen) {
            LOAD_PATCHES_WITH_SPLIT_START to LOAD_PATCHES_WITH_SPLIT_END
        } else {
            LOAD_PATCHES_START to LOAD_PATCHES_END
        }
    }

    private fun prepareSplitNotificationRange(): Pair<Int, Int> {
        return if (notificationSplitPreparationSeen) {
            PREPARE_SPLIT_WITH_SPLIT_START to PREPARE_SPLIT_WITH_SPLIT_END
        } else {
            PREPARE_SPLIT_START to PREPARE_SPLIT_END
        }
    }

    private fun notificationExecutePatchProgress(
        stepId: StepId.ExecutePatch,
        totalPatchCount: Int,
        fraction: Float
    ): Int {
        if (totalPatchCount <= 0) {
            return progressInRange(EXECUTE_PATCHES_START, EXECUTE_PATCHES_END, fraction)
        }
        val executionIndex = if (activePatchBundleType == PatchBundleType.RESEAM) {
            patchExecutionOrder.indexOf(stepId.index).coerceAtLeast(0)
        } else stepId.index
        val currentPatch = executionIndex.coerceIn(0, totalPatchCount)
        val overallFraction =
            ((currentPatch.toFloat() + fraction.coerceIn(0f, 1f)) / totalPatchCount.toFloat())
                .coerceIn(0f, 1f)
        return progressInRange(EXECUTE_PATCHES_START, EXECUTE_PATCHES_END, overallFraction)
    }

    private fun notificationWriteApkFraction(event: ProgressEvent.Progress): Float {
        val detail = normalizeNotificationDetail(event.stepId, event.message)?.trim()
        return when {
            detail == null -> 0.5f
            detail.equals("Validating resources", ignoreCase = true) -> 0.02f
            detail.equals("Preparing output APK", ignoreCase = true) -> 0.05f
            detail.equals("Copying base APK", ignoreCase = true) -> 0.15f
            detail.equals("Copy base APK", ignoreCase = true) -> 0.15f
            detail.equals("Applying patched changes", ignoreCase = true) -> 0.28f
            detail.equals("Compiling patched dex files", ignoreCase = true) -> 0.4f
            detail.equals(currentWriteApkNotificationDexGroupTitle(), ignoreCase = true) -> 0.4f
            detail.startsWith("${currentWriteApkNotificationDexGroupTitle()}:", ignoreCase = true) -> 0.4f
            detail.equals("Compiling modified resources", ignoreCase = true) -> 0.65f
            detail.equals("Writing patched APK", ignoreCase = true) -> 0.75f
            isWriteApkDexNotificationTitle(detail) -> 0.4f
            detail.startsWith("Compiling ", ignoreCase = true) -> 0.4f
            detail.startsWith("Compiled ", ignoreCase = true) -> 0.4f
            detail.equals("Writing output APK", ignoreCase = true) -> 0.82f
            detail.equals("Finalizing output", ignoreCase = true) -> 0.92f
            detail.equals("Stripping native libraries", ignoreCase = true) -> 0.97f
            !event.subSteps.isNullOrEmpty() -> 0.25f
            else -> 0.5f
        }
    }

    private fun progressInRange(start: Int, end: Int, fraction: Float): Int {
        val normalized = fraction.coerceIn(0f, 1f)
        return (start + ((end - start) * normalized)).roundToInt()
            .coerceIn(start, end)
    }

    private fun cancelActiveRuntimes() {
        activeRuntime?.cancel()
        activeMorpheRuntime?.cancel()
        activeSplitMergeRuntime?.cancelActiveExecution()
    }

    private fun isAppTaskPresent(): Boolean {
        val activityManager = applicationContext.getSystemService(ActivityManager::class.java) ?: return true
        return runCatching {
            activityManager.appTasks.isNotEmpty()
        }.onFailure { error ->
            Log.d(tag, "Failed to inspect app task state", error)
        }.getOrDefault(true)
    }

    private fun cancelForAppClosed(reason: String) {
        if (!workerRepository.isActiveUniqueWork(UNIQUE_WORK_NAME, id)) return

        Log.d(tag, "$reason; cancelling active patching work")
        clearForegroundNotificationIfOwned()
        workerRepository.cancelUniqueWork(UNIQUE_WORK_NAME)
        cancelActiveRuntimes()
    }

    private fun stopForegroundUpdates() {
        cancelActiveRuntimes()
        synchronized(notificationStateLock) {
            patchNotificationSteps = emptyList()
            resetNotificationProgressTrackingLocked()
            foregroundStarted = false
        }
        clearForegroundNotificationIfOwned()
    }

    private fun shouldSkipForegroundUpdates(): Boolean {
        if (!isStopped) return false
        stopForegroundUpdates()
        return true
    }

    private fun updateForegroundNotification(
        event: ProgressEvent?,
        totalPatchCount: Int,
        sequence: Long? = null
    ) {
        if (shouldSkipForegroundUpdates()) return
        synchronized(notificationStateLock) {
            if (sequence != null && sequence < lastForegroundNotificationSequence) {
                return
            }
            if (sequence != null) {
                lastForegroundNotificationSequence = sequence
            }
            val notificationEvent = normalizeNotificationEvent(event)
            val notification = createNotification(notificationEvent, totalPatchCount)
            try {
                if (!foregroundStarted) {
                    runBlocking {
                        setForeground(createForegroundInfo(notification))
                    }
                    foregroundStarted = true
                }
            } catch (e: Exception) {
                Log.d(tag, "Failed to set foreground notification:", e)
            }

            try {
                notificationManager.notify(NOTIFICATION_ID, notification)
            } catch (e: Exception) {
                Log.d(tag, "Failed to refresh foreground notification:", e)
            }
        }
    }

    private fun normalizeNotificationEvent(event: ProgressEvent?): ProgressEvent? {
        when (event?.stepId) {
            StepId.PrepareSplitApk -> notificationSplitPreparationSeen = true
            null,
            StepId.DownloadAPK,
            StepId.LoadPatches,
            StepId.ReadAPK,
            StepId.ExecutePatches,
            StepId.WriteAPK,
            StepId.SignAPK -> Unit
            is StepId.ExecutePatch -> Unit
        }
        return when (event) {
            is ProgressEvent.Started -> {
                if (event.stepId == StepId.DownloadAPK || event.stepId == StepId.LoadPatches) {
                    lastNotificationProgressCurrent = 0
                }
                if (event.stepId == StepId.LoadPatches) {
                    resetWriteApkNotificationPhase()
                }
                if (event.stepId == StepId.WriteAPK || event.stepId == StepId.SignAPK) {
                    resetWriteApkNotificationPhase()
                }
                event
            }
            is ProgressEvent.Progress -> normalizeWriteApkNotificationPhase(event)
            is ProgressEvent.Completed -> {
                if (event.stepId == StepId.DownloadAPK) {
                    lastNotificationProgressCurrent = 0
                }
                if (event.stepId == StepId.WriteAPK || event.stepId == StepId.SignAPK) {
                    resetWriteApkNotificationPhase()
                }
                event
            }
            is ProgressEvent.Failed -> {
                if (event.stepId == StepId.WriteAPK || event.stepId == StepId.SignAPK) {
                    resetWriteApkNotificationPhase()
                }
                event
            }
            null -> null
        }
    }

    private fun normalizeWriteApkNotificationPhase(
        event: ProgressEvent.Progress
    ): ProgressEvent.Progress {
        if (event.stepId != StepId.WriteAPK) return event
        syncMorpheDexNotificationChild(event.message)
        val detail = normalizeNotificationDetail(event.stepId, event.message)
            ?.trim()
            ?.let(::normalizeWriteApkNotificationProgressDetail)
            ?: return lastWriteApkNotificationDetail?.let { detail ->
                event.copy(message = detail)
            } ?: event
        val phaseIndex = writeApkNotificationPhaseIndex(detail)
        if (phaseIndex == -1) {
            return lastWriteApkNotificationDetail?.let { lastDetail ->
                event.copy(message = lastDetail)
            } ?: event
        }
        if (phaseIndex < lastWriteApkNotificationPhaseIndex) {
            return event.copy(message = lastWriteApkNotificationDetail)
        }
        if (shouldRetainWriteApkNotificationDetail(detail, phaseIndex)) {
            return event.copy(message = lastWriteApkNotificationDetail)
        }
        lastWriteApkNotificationPhaseIndex = phaseIndex
        lastWriteApkNotificationDetail = detail
        return event.copy(message = detail)
    }

    private fun resetWriteApkNotificationPhase() {
        lastWriteApkNotificationPhaseIndex = -1
        lastWriteApkNotificationDetail = null
        activeMorpheDexChildTitle = null
    }

    private fun resetNotificationProgressTracking() {
        synchronized(notificationStateLock) {
            resetNotificationProgressTrackingLocked()
        }
    }

    private fun resetNotificationProgressTrackingLocked() {
        lastNotificationProgressCurrent = 0
        reseamSplitSubSteps = emptyList()
        notificationSplitPreparationSeen = false
        lastForegroundNotificationSequence = Long.MIN_VALUE
        resetWriteApkNotificationPhase()
    }

    private fun primeNotificationSplitFlow(input: SelectedApp) {
        if (input !is SelectedApp.Local) return
        if (!SplitApkPreparer.isSplitArchive(input.file)) return
        synchronized(notificationStateLock) {
            notificationSplitPreparationSeen = true
        }
    }

    private fun writeApkNotificationPhaseIndex(detail: String): Int = when {
        activePatchBundleType == PatchBundleType.RESEAM && detail == "Writing patched APKs" -> 0
        activePatchBundleType == PatchBundleType.RESEAM && detail == "Preparing unsigned APKs" -> 1
        detail.equals("Validating resources", ignoreCase = true) -> 0
        detail.equals("Preparing output APK", ignoreCase = true) -> 0
        detail.equals("Copying base APK", ignoreCase = true) ||
            detail.equals("Copy base APK", ignoreCase = true) -> 1
        detail.equals("Applying patched changes", ignoreCase = true) -> 2
        detail.equals("Compiling patched dex files", ignoreCase = true) ||
            detail.equals(currentWriteApkNotificationDexGroupTitle(), ignoreCase = true) ||
            detail.startsWith("${currentWriteApkNotificationDexGroupTitle()}:", ignoreCase = true) -> 3
        detail.equals("Compiling modified resources", ignoreCase = true) ||
            detail.equals("Writing patched APK", ignoreCase = true) -> 4
        isWriteApkDexNotificationTitle(detail) -> 3
        detail.startsWith("Compiling ", ignoreCase = true) -> 3
        detail.startsWith("Compiled ", ignoreCase = true) -> 3
        detail.equals("Writing output APK", ignoreCase = true) -> 5
        detail.equals("Finalizing output", ignoreCase = true) -> 6
        detail.equals("Stripping native libraries", ignoreCase = true) -> 7
        else -> -1
    }

    private fun normalizeWriteApkNotificationProgressDetail(detail: String): String {
        val trimmed = detail.trim()
        return if (trimmed.startsWith("Compiled ", ignoreCase = true)) {
            "Compiling ${trimmed.removePrefix("Compiled ").trim()}"
        } else {
            trimmed
        }
    }

    private fun shouldRetainWriteApkNotificationDetail(detail: String, phaseIndex: Int): Boolean {
        val lastDetail = lastWriteApkNotificationDetail ?: return false
        if (phaseIndex != lastWriteApkNotificationPhaseIndex) return false
        if (phaseIndex == 4 && lastDetail.equals("Writing patched APK", ignoreCase = true)) {
            return detail.equals("Compiling modified resources", ignoreCase = true)
        }
        if (phaseIndex != 3) return false

        val lastIsDexChild = isWriteApkDexNotificationTitle(lastDetail)
        val nextIsDexChild = isWriteApkDexNotificationTitle(detail)
        if (lastIsDexChild && !nextIsDexChild) return true
        if (!lastIsDexChild || !nextIsDexChild) return false

        val lastDexSortKey = notificationDexDetailSortKey(lastDetail) ?: return false
        val nextDexSortKey = notificationDexDetailSortKey(detail) ?: return false
        return nextDexSortKey < lastDexSortKey
    }

    private fun clearForegroundNotification() {
        try {
            notificationManager.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.d(tag, "Failed to clear foreground notification:", e)
        }
    }

    private fun clearForegroundNotificationIfOwned() {
        if (!ownsCurrentPatchingNotification()) return
        clearForegroundNotification()
        workerRepository.clearActiveUniqueWork(UNIQUE_WORK_NAME, id)
    }

    private fun ownsCurrentPatchingNotification(): Boolean {
        val activeWorkId = workerRepository.activeUniqueWorkId(UNIQUE_WORK_NAME)
        if (activeWorkId == id) return true
        if (activeWorkId != null) return false

        return try {
            runBlocking {
                workerRepository.workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME).first()
            }.none { workInfo ->
                workInfo.id != id && !workInfo.state.isFinished
            }
        } catch (e: Exception) {
            Log.d(tag, "Failed to resolve current patching work ownership:", e)
            true
        }
    }

    private suspend fun updateWorkerProgressState(
        active: Boolean,
        snapshot: PatcherWorkerProgressSnapshot? = null
    ): Boolean {
        return runCatching {
            setProgress(PatcherWorkerProgressState.toWorkData(active, snapshot))
            true
        }.onFailure { error ->
            Log.d(tag, "Failed to update active patching state", error)
        }.getOrDefault(false)
    }

    private suspend fun persistWorkerProgressSnapshot(snapshot: PatcherWorkerProgressSnapshot) {
        workerProgressMutex.withLock {
            if (progressPersistenceClosed) return
            if (snapshot.sequence <= lastPersistedProgressSequence) return
            if (updateWorkerProgressState(active = true, snapshot = snapshot)) {
                lastPersistedProgressSequence = snapshot.sequence
            }
        }
    }

    private suspend fun deactivateWorkerProgressState() {
        workerProgressMutex.withLock {
            progressPersistenceClosed = true
            workerRepository.clearActiveProgressSnapshot(id)
            updateWorkerProgressState(active = false)
        }
    }

    private fun persistProgressSnapshot(
        sequence: Long,
        event: ProgressEvent,
        totalPatchCount: Int
    ) {
        if (event is ProgressEvent.Failed) {
            val patchStepId = event.stepId as? StepId.ExecutePatch ?: return
            failedPatchIndexes += patchStepId.index
        } else if (event is ProgressEvent.Completed || event is ProgressEvent.Started) {
            (event.stepId as? StepId.ExecutePatch)?.let { patchStepId ->
                failedPatchIndexes -= patchStepId.index
            }
        }

        cacheExpandableSubSteps(event)
        val snapshotEvent = when (event) {
            is ProgressEvent.Progress -> {
                val cachedSubSteps = if (isExpandableStep(event.stepId)) {
                    event.subSteps ?: cachedExpandableSubSteps[event.stepId]
                } else {
                    null
                }
                event.copy(subSteps = cachedSubSteps)
            }
            else -> event
        }
        val persistedNotificationProgress = persistedNotificationProgress(event, totalPatchCount)
        val snapshot = PatcherWorkerProgressSnapshot(
            generation = progressGeneration,
            sequence = sequence,
            event = snapshotEvent,
            notificationProgressCurrent = persistedNotificationProgress?.current,
            notificationProgressMax = persistedNotificationProgress?.max,
            memoryUsage = lastPatcherMemoryUsage,
            failedPatchIndexes = currentFailedPatchIndexes(),
            patchExecutionOrder = patchExecutionOrder
        )

        workerRepository.updateActiveProgressSnapshot(id, snapshot)
        workerProgressScope.launch {
            persistWorkerProgressSnapshot(snapshot)
        }
    }

    private fun currentFailedPatchIndexes(): Set<Int> =
        failedPatchIndexes.toSet()

    private fun currentFailedPatchIndexArray(): IntArray =
        failedPatchIndexes.toList().sorted().toIntArray()

    private fun cacheExpandableSubSteps(event: ProgressEvent) {
        when (event) {
            is ProgressEvent.Started -> {
                if (isExpandableStep(event.stepId)) {
                    cachedExpandableSubSteps.remove(event.stepId)
                    notificationExpandableSubSteps.remove(event.stepId)
                    if (!event.subSteps.isNullOrEmpty()) {
                        cachedExpandableSubSteps[event.stepId] = event.subSteps
                        notificationExpandableSubSteps[event.stepId] =
                            normalizeNotificationExpandableSubSteps(event.stepId, event.subSteps)
                    }
                }
            }
            is ProgressEvent.Progress -> {
                if (isExpandableStep(event.stepId) && !event.subSteps.isNullOrEmpty()) {
                    cachedExpandableSubSteps[event.stepId] = event.subSteps
                    notificationExpandableSubSteps[event.stepId] =
                        normalizeNotificationExpandableSubSteps(event.stepId, event.subSteps)
                }
            }
            is ProgressEvent.Completed,
            is ProgressEvent.Failed -> {
                event.stepId?.takeIf(::isExpandableStep)?.let {
                    cachedExpandableSubSteps.remove(it)
                    notificationExpandableSubSteps.remove(it)
                }
            }
        }
    }

    private fun isRepeatableSplitPreparationLog(message: String): Boolean =
        message.startsWith("Preparing split APK bundle from ") ||
            (message.startsWith("Found ") && " split modules:" in message) ||
            message.startsWith("Module sizes:") ||
            message.startsWith("Included splits:") ||
            message.startsWith("Excluded splits:")

    private fun handleWorkerLogProgress(
        message: String,
        totalPatchCount: Int,
        onEvent: (PatcherWorkerProgressUpdate) -> Unit
    ) {
        if (shouldSkipForegroundUpdates()) return
        val event = buildWriteApkLogEvent(message) ?: return
        val liveSequence = progressSequence.incrementAndGet()
        forwardWriteApkLogProgressForUi(liveSequence, event, totalPatchCount, onEvent)
        val notificationEvent = notificationDisplayEventForWriteApkLog(event)
        updateForegroundNotification(notificationEvent, totalPatchCount, sequence = liveSequence)
        val snapshotSequence = progressSequence.incrementAndGet()
        persistWriteApkLogProgressSnapshot(snapshotSequence, event, totalPatchCount)
    }

    private fun notificationDisplayEventForWriteApkLog(
        event: ProgressEvent.Progress
    ): ProgressEvent.Progress {
        if (event.stepId != StepId.WriteAPK || !isActiveMorpheWriteApkUi()) {
            return normalizeWriteApkNotificationEvent(event)
        }

        syncMorpheDexNotificationChild(event.message)
        val normalizedDetail = normalizeNotificationDetail(event.stepId, event.message)
        val normalizedPhaseIndex = normalizedDetail
            ?.trim()
            ?.let(::normalizeWriteApkNotificationProgressDetail)
            ?.let(::writeApkNotificationPhaseIndex)
            ?: -1

        val displayDetail = when {
            normalizedPhaseIndex >= 4 ->
                normalizedDetail ?: lastWriteApkNotificationDetail ?: "Compiling modified resources"
            lastWriteApkNotificationPhaseIndex >= 5 ->
                lastWriteApkNotificationDetail ?: "Writing output APK"
            lastWriteApkNotificationPhaseIndex == 4 ->
                lastWriteApkNotificationDetail ?: "Compiling modified resources"
            else ->
                currentWriteApkNotificationDexDisplayDetail()
        }

        return event.copy(
            message = displayDetail,
            subSteps = null
        )
    }

    private fun normalizeWriteApkNotificationEvent(
        event: ProgressEvent.Progress
    ): ProgressEvent.Progress {
        if (event.stepId != StepId.WriteAPK) return event
        return event.copy(subSteps = null)
    }

    private fun forwardWriteApkLogProgressForUi(
        sequence: Long,
        event: ProgressEvent.Progress,
        totalPatchCount: Int,
        onEvent: (PatcherWorkerProgressUpdate) -> Unit
    ) {
        if (event.stepId != StepId.WriteAPK) return

        val uiEvent = normalizeEarlyWriteApkUiEvent(event)
        runCatching {
            onEvent(
                buildWorkerProgressUpdate(
                    sequence = sequence,
                    event = uiEvent,
                    notificationProgress = persistedNotificationProgress(event, totalPatchCount)
                )
            )
        }.onFailure { error ->
            Log.d(tag, "Failed to forward write APK log progress to UI", error)
        }
    }

    private fun normalizeNotificationExpandableSubSteps(
        stepId: StepId,
        subSteps: List<String>
    ): List<String> {
        if (stepId != StepId.WriteAPK || !isActiveMorpheWriteApkUi()) return subSteps
        return subSteps
            .mapNotNull { normalizeWriteApkNotificationCacheTitle(it).takeIf(String::isNotBlank) }
            .distinctBy { it.lowercase() }
    }

    private fun normalizeEarlyWriteApkUiEvent(event: ProgressEvent.Progress): ProgressEvent.Progress {
        if (event.stepId != StepId.WriteAPK) return event
        if (event.message.equals("Applying patched changes", ignoreCase = true)) {
            return event.copy(message = null, subSteps = null)
        }
        return event.copy(subSteps = null)
    }

    private fun persistedNotificationProgress(
        event: ProgressEvent,
        totalPatchCount: Int
    ): NotificationProgress? {
        if (event.stepId == StepId.DownloadAPK) return null
        val progress = notificationProgress(event, totalPatchCount) ?: return null
        if (progress.indeterminate || progress.max <= 0) return null
        return normalizeNotificationProgress(progress)
    }

    private fun persistWriteApkLogProgressSnapshot(
        sequence: Long,
        event: ProgressEvent.Progress,
        totalPatchCount: Int
    ) {
        val snapshotEvent = normalizeEarlyWriteApkUiEvent(event).copy(
            subSteps = event.subSteps
                ?: notificationExpandableSubSteps[StepId.WriteAPK]
                ?: cachedExpandableSubSteps[StepId.WriteAPK]
        )
        val persistedNotificationProgress = persistedNotificationProgress(event, totalPatchCount)
        val snapshot = PatcherWorkerProgressSnapshot(
            generation = progressGeneration,
            sequence = sequence,
            event = snapshotEvent,
            notificationProgressCurrent = persistedNotificationProgress?.current,
            notificationProgressMax = persistedNotificationProgress?.max,
            memoryUsage = lastPatcherMemoryUsage,
            failedPatchIndexes = currentFailedPatchIndexes(),
            patchExecutionOrder = patchExecutionOrder
        )

        workerRepository.updateActiveProgressSnapshot(id, snapshot)
        workerProgressScope.launch {
            persistWorkerProgressSnapshot(snapshot)
        }
    }

    private fun buildWorkerProgressUpdate(
        sequence: Long,
        event: ProgressEvent,
        notificationProgress: NotificationProgress?
    ) = PatcherWorkerProgressUpdate(
        generation = progressGeneration,
        sequence = sequence,
        event = event,
        notificationProgressCurrent = notificationProgress?.current,
        notificationProgressMax = notificationProgress?.max,
        memoryUsage = lastPatcherMemoryUsage,
        patchExecutionOrder = patchExecutionOrder
    )

    private fun buildWriteApkLogEvent(rawMessage: String): ProgressEvent.Progress? {
        val message = rawMessage.trim()
        val rawDetail = if (isActiveMorpheWriteApkUi()) {
            when {
                message.contains("Writing patched files", ignoreCase = true) ->
                    "Writing patched files..."
                message.contains("Compiling modified resources", ignoreCase = true) ||
                    message.contains("Compiling patched resources", ignoreCase = true) ||
                    message.contains("Compiled modified resources", ignoreCase = true) ||
                    message.contains("Compiled patched resources", ignoreCase = true) ->
                    "Compiling modified resources"
                message.contains("Writing output APK", ignoreCase = true) ->
                    "Writing output APK"
                message.contains("Finalizing output", ignoreCase = true) ->
                    "Finalizing output"
                message.contains("Patched apk saved to", ignoreCase = true) ->
                    "Writing output APK"
                morpheProcessingClassesPattern.containsMatchIn(message) ->
                    "Processing ${morpheProcessingClassesPattern.find(message)?.groupValues?.get(1)} classes"
                morpheWroteDexFilesPattern.containsMatchIn(message) ->
                    "Wrote ${morpheWroteDexFilesPattern.find(message)?.groupValues?.get(1)} dex files"
                morpheStrippedDexPattern.containsMatchIn(message) -> {
                    val dexName = morpheStrippedDexPattern.find(message)?.groupValues?.get(1) ?: return null
                    "Modified $dexName"
                }
                else -> return null
            }
        } else {
            when {
                message.contains("Writing patched files", ignoreCase = true) ->
                    "Applying patched changes"
                message.contains("Compiling modified resources", ignoreCase = true) ||
                    message.contains("Compiling patched resources", ignoreCase = true) ||
                    message.contains("Compiled modified resources", ignoreCase = true) ||
                    message.contains("Compiled patched resources", ignoreCase = true) ->
                    "Compiling modified resources"
                message.contains("Writing output APK", ignoreCase = true) ->
                    "Writing output APK"
                message.contains("Finalizing output", ignoreCase = true) ->
                    "Finalizing output"
                message.contains("Patched apk saved to", ignoreCase = true) ->
                    "Writing output APK"
                isDexCompilePhaseTitle(message) -> message
                else -> {
                    val match = dexCompilePattern.find(message) ?: dexWritePattern.find(message)
                    val dexName =
                        match?.groupValues?.lastOrNull()?.takeIf { it.endsWith(".dex", ignoreCase = true) }
                            ?: return null
                    val completionKeyword = match.groupValues.getOrNull(1)
                    if (completionKeyword.equals("Compiled", ignoreCase = true)) {
                        "Compiled $dexName"
                    } else {
                        "Compiling $dexName"
                    }
                }
            }
        }

        updateCachedWriteApkNotificationSubSteps(rawDetail)
        val detail = rawDetail

        return ProgressEvent.Progress(
            stepId = StepId.WriteAPK,
            message = detail,
            subSteps = notificationExpandableSubSteps[StepId.WriteAPK]
        )
    }

    private fun updateCachedWriteApkNotificationSubSteps(detail: String) {
        val normalized = normalizeWriteApkNotificationCacheTitle(detail)
        if (normalized.isBlank()) return

        val existing = notificationExpandableSubSteps[StepId.WriteAPK].orEmpty()
        val updated = existing.toMutableList()
        if (updated.isEmpty()) {
            updated += defaultWriteApkNotificationSubSteps()
        }

        if (isWriteApkDexNotificationTitle(normalized)) {
            ensureWriteApkDexNotificationTitle(updated, normalized)
        } else {
            ensureWriteApkPhaseNotificationTitle(updated, normalized)
        }

        if (updated != existing) {
            notificationExpandableSubSteps[StepId.WriteAPK] = updated
        }
    }

    private fun normalizeWriteApkNotificationCacheTitle(detail: String): String {
        val trimmed = detail.trim()
        if (isActiveMorpheWriteApkUi() && trimmed.equals("Writing patched files...", ignoreCase = true)) {
            return ""
        }
        if (isActiveMorpheWriteApkUi() &&
            trimmed.startsWith("Compiling patched dex files (mode:", ignoreCase = true)
        ) {
            return currentWriteApkNotificationDexGroupTitle()
        }
        if (isActiveMorpheWriteApkUi() && trimmed.equals("Copying base APK", ignoreCase = true)) {
            return "Copy base APK"
        }
        if (isActiveMorpheWriteApkUi() &&
            (dexCompilePattern.containsMatchIn(trimmed) || dexWritePattern.containsMatchIn(trimmed))
        ) {
            return ""
        }
        if (trimmed.startsWith("Compiled ", ignoreCase = true)) {
            return "Compiling ${trimmed.removePrefix("Compiled ").trim()}"
        }
        return trimmed
    }

    private fun syncMorpheDexNotificationChild(detail: String?) {
        if (!isActiveMorpheWriteApkUi()) return
        val trimmed = detail?.trim().orEmpty()
        when {
            trimmed.isEmpty() -> Unit
            trimmed.equals("Compiling modified resources", ignoreCase = true) ||
                trimmed.equals("Compiled modified resources", ignoreCase = true) ||
                trimmed.equals("Compiling patched resources", ignoreCase = true) ||
                trimmed.equals("Compiled patched resources", ignoreCase = true) ||
                trimmed.equals("Writing output APK", ignoreCase = true) ||
                trimmed.contains("Patched apk saved to", ignoreCase = true) ||
                trimmed.equals("Finalizing output", ignoreCase = true) ||
                trimmed.equals("Stripping native libraries", ignoreCase = true) -> {
                activeMorpheDexChildTitle = null
            }
            isWriteApkDexNotificationTitle(trimmed) -> {
                activeMorpheDexChildTitle = trimmed
            }
        }
    }

    private fun currentWriteApkNotificationDexDisplayDetail(activeChild: String? = activeMorpheDexChildTitle): String {
        return activeChild?.trim().takeUnless { it.isNullOrBlank() }
            ?: currentWriteApkNotificationDexGroupTitle()
    }

    private fun isWriteApkDexNotificationTitle(title: String): Boolean {
        if (isActiveMorpheWriteApkUi()) {
            return title.startsWith("Processing ", ignoreCase = true) &&
                title.endsWith(" classes", ignoreCase = true) ||
                title.startsWith("Wrote ", ignoreCase = true) &&
                title.contains(" dex files", ignoreCase = true) ||
                title.startsWith("Modified classes", ignoreCase = true) &&
                title.endsWith(".dex", ignoreCase = true)
        }
        if (!title.startsWith("Compiling ", ignoreCase = true)) return false
        return title.removePrefix("Compiling ").trim().endsWith(".dex", ignoreCase = true)
    }

    private fun ensureWriteApkDexNotificationTitle(
        subSteps: MutableList<String>,
        title: String
    ) {
        if (subSteps.any { it.equals(title, ignoreCase = true) }) return

        if (isActiveMorpheWriteApkUi()) {
            val dexGroupTitle = currentWriteApkNotificationDexGroupTitle()
            if (subSteps.none { it.equals(dexGroupTitle, ignoreCase = true) }) {
                ensureWriteApkPhaseNotificationTitle(subSteps, dexGroupTitle)
            }
            val resourceIndex = subSteps.indexOfFirst {
                it.equals("Compiling modified resources", ignoreCase = true)
            }.takeIf { it != -1 }
                ?: subSteps.indexOfFirst {
                    it.equals("Writing output APK", ignoreCase = true)
                }.takeIf { it != -1 }
                ?: subSteps.size
            subSteps.add(resourceIndex, title)
            return
        }

        val resourceIndex = subSteps.indexOfFirst {
            it.equals("Compiling modified resources", ignoreCase = true)
        }.takeIf { it != -1 }
            ?: subSteps.indexOfFirst {
                it.equals("Writing output APK", ignoreCase = true)
            }.takeIf { it != -1 }
            ?: subSteps.size

        val targetDexName = title.removePrefix("Compiling ").trim()
        val targetSortKey = writeApkDexSortKey(targetDexName)
        val insertIndex = (0 until resourceIndex)
            .firstOrNull { index ->
                val existing = subSteps[index]
                isWriteApkDexNotificationTitle(existing) &&
                    writeApkDexSortKey(existing.removePrefix("Compiling ").trim()) > targetSortKey
            }
            ?: resourceIndex
        subSteps.add(insertIndex, title)
    }

    private fun ensureWriteApkPhaseNotificationTitle(
        subSteps: MutableList<String>,
        title: String
    ) {
        if (subSteps.any { it.equals(title, ignoreCase = true) }) return

        val phaseOrder = if (isActiveMorpheWriteApkUi()) {
            listOf(
                "Copy base APK",
                "Applying patched changes",
                currentWriteApkNotificationDexGroupTitle(),
                "Compiling modified resources",
                "Writing output APK",
                "Finalizing output",
                "Stripping native libraries"
            )
        } else {
            listOf(
                "Validating resources",
                "Copying base APK",
                "Applying patched changes",
                "Compiling patched dex files",
                "Compiling modified resources",
                "Writing patched APK",
                "Writing output APK",
                "Finalizing output",
                "Stripping native libraries"
            )
        }
        val targetOrder = phaseOrder.indexOfFirst { it.equals(title, ignoreCase = true) }
        if (targetOrder == -1) {
            subSteps += title
            return
        }

        val insertIndex = subSteps.indexOfFirst { existing ->
            val existingOrder = phaseOrder.indexOfFirst { it.equals(existing, ignoreCase = true) }
            existingOrder != -1 && existingOrder > targetOrder
        }.takeIf { it != -1 } ?: subSteps.size
        subSteps.add(insertIndex, title)
    }

    private fun defaultWriteApkNotificationSubSteps(): List<String> {
        return if (isActiveMorpheWriteApkUi()) {
            listOf(
                "Copy base APK",
                "Applying patched changes",
                currentWriteApkNotificationDexGroupTitle(),
                "Compiling modified resources",
                "Writing output APK",
                "Finalizing output"
            )
        } else {
            listOf(
                "Copying base APK",
                "Applying patched changes",
                "Compiling modified resources",
                "Writing output APK",
                "Finalizing output"
            )
        }
    }

    private fun isActiveMorpheWriteApkUi(): Boolean = activePatchBundleType == PatchBundleType.MORPHE

    private fun currentWriteApkNotificationDexGroupTitle(): String =
        if (isActiveMorpheWriteApkUi()) {
            activeMorpheDexGroupTitle ?: "Compiling DEX files: FAST"
        } else {
            "Compiling patched dex files"
        }

    private fun writeApkDexSortKey(name: String): Int {
        val base = name.removeSuffix(".dex")
        if (base == "classes") return 1
        val suffix = base.removePrefix("classes")
        return suffix.toIntOrNull() ?: Int.MAX_VALUE
    }

    private fun notificationDexDetailSortKey(detail: String): Int? {
        val trimmed = detail.trim()
        if (isActiveMorpheWriteApkUi()) {
            return when {
                trimmed.startsWith("Processing ", ignoreCase = true) &&
                    trimmed.endsWith(" classes", ignoreCase = true) -> 0
                morpheWroteDexFilesPattern.matches(trimmed) -> 1
                morpheStrippedDexPattern.matches(trimmed) -> {
                    val dexName = morpheStrippedDexPattern.find(trimmed)?.groupValues?.get(1) ?: return null
                    100 + writeApkDexSortKey(dexName)
                }
                trimmed.startsWith("Modified classes", ignoreCase = true) &&
                    trimmed.endsWith(".dex", ignoreCase = true) -> {
                    val dexName = trimmed.removePrefix("Modified ").trim()
                    100 + writeApkDexSortKey(dexName)
                }
                else -> null
            }
        }

        if (!trimmed.startsWith("Compiling ", ignoreCase = true)) return null
        val dexName = trimmed.removePrefix("Compiling ").trim()
        return writeApkDexSortKey(dexName)
    }

    private fun isDexCompilePhaseTitle(message: String): Boolean {
        return message.equals("Compiling patched dex files", ignoreCase = true) ||
            message.equals("Applying patched changes", ignoreCase = true)
    }

    private fun isExpandableStep(stepId: StepId) = when (stepId) {
        StepId.PrepareSplitApk,
        StepId.WriteAPK -> true
        else -> false
    }

    override suspend fun doWork(): Result = PatcherWorkspaceGate.withWorkspace {
        if (runAttemptCount > 0) {
            Log.d(tag, "Android requested retrying but retrying is disabled.".logFmt())
            return@withWorkspace Result.failure()
        }

        resetNotificationProgressTracking()
        val workerFinished = AtomicBoolean(false)
        val stopMonitor = CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
            var missingTaskSamples = 0
            while (!workerFinished.get()) {
                if (isStopped) {
                    cancelActiveRuntimes()
                } else if (!allowBackgroundExecution && AppForeground.isMainTaskClosed) {
                    cancelForAppClosed("Main activity destroyed")
                } else if (allowBackgroundExecution || isAppTaskPresent()) {
                    missingTaskSamples = 0
                } else {
                    missingTaskSamples++
                    if (missingTaskSamples >= 4) {
                        cancelForAppClosed("App task removed")
                    }
                }
                delay(250)
            }
        }
        val wakeLock: PowerManager.WakeLock =
            (applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$tag::Patcher")
                .apply {
                    acquire(10 * 60 * 1000L)
                    Log.d(tag, "Acquired wakelock.")
                }

        try {
            val initialForegroundInfo = createForegroundInfo(event = null, totalPatchCount = 0)
            setForeground(initialForegroundInfo)
            foregroundStarted = true
        } catch (e: Exception) {
            Log.d(tag, "Failed to set initial foreground info:", e)
        }

        try {
            val args = workerRepository.claimInput(this)
            allowBackgroundExecution = args.allowBackgroundExecution
            backgroundExecutionActive = allowBackgroundExecution
            clearBackgroundExecutionRequested()
            batchQueueLabel = args.queuePosition?.let { position ->
                args.queueSize?.let { size ->
                    applicationContext.getString(
                        R.string.batch_patch_notification_queue_progress,
                        position,
                        size
                    )
                }
            }
            batchAppLabel = args.appName?.trim()?.takeIf(String::isNotBlank)
            val totalPatchCount = args.selectedPatches.values.sumOf { it.size }
            primeNotificationSplitFlow(args.input)

            try {
                updateForegroundNotification(event = null, totalPatchCount = totalPatchCount)
            } catch (e: Exception) {
                Log.d(tag, "Failed to publish initial patching notification:", e)
            }

            updateWorkerProgressState(active = true)
            val result = runPatcher(args, totalPatchCount)

            result
        } finally {
            workerFinished.set(true)
            backgroundExecutionActive = false
            clearBackgroundExecutionRequested()
            stopMonitor.cancel()
            withContext(NonCancellable) {
                deactivateWorkerProgressState()
            }
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
            stopForegroundUpdates()
            workerProgressScope.cancel()
        }
    }

    private suspend fun runPatcher(args: Args, totalPatchCount: Int): Result {
        patchExecutionOrder = emptyList()
        cleanupTemporarySplitArtifacts()
        lastPatcherMemoryUsage = null
        requestedPatcherMemoryLimitMb = null
        val patchedApk = fs.tempDir.resolve("patched.apk")
        var downloadCleanup: (() -> Unit)? = null
        val signatureMetadataOutput = args.signatureMetadataOutput?.let(::File)
        var retainSignatureMetadata = false
        val notificationBundleInfo = patchBundleRepository.allBundlesInfoFlow.first()
        patchNotificationSteps = args.selectedPatches.flatMap { (uid, selected) ->
            val titles = notificationBundleInfo[uid]?.patches?.associate { it.name to it.displayName }.orEmpty()
            selected.map { it to (titles[it] ?: it) }
        }.sortedBy { it.first }.map { it.second }
        val patcherLogMode = prefs.patcherLogMode.get()
        val reportedSplitPreparationLogs = ConcurrentHashMap.newKeySet<String>()
        val workerLogger = object : Logger() {
            override fun log(level: LogLevel, message: String) {
                // Code adapted from Morphe, see third-party/NOTICE for more information.
                // https://github.com/MorpheApp/morphe-manager/blob/03fe5f5a3cb89d87bb9898933d39430e4134f154/app/src/main/java/app/morphe/manager/ui/model/PatchRunProgress.kt
                if (message.startsWith(PatcherResourceMonitor.LOG_PREFIX)) {
                    parsePatcherResourceUsage(message)?.let { sample ->
                        publishPatcherMemoryUsage(sample, args.onEvent)
                    }
                    return
                }
                if (!patcherLogMode.allows(level)) return
                if (isRepeatableSplitPreparationLog(message)) {
                    val key = "${level.name}\u0000$message"
                    reportedSplitPreparationLogs.add(key)
                }
                args.logger.log(level, message)
                handleWorkerLogProgress(message, totalPatchCount, args.onEvent)
            }
        }
        val eventDispatcher: (ProgressEvent) -> Unit = eventDispatcher@{ event ->
            if (shouldSkipForegroundUpdates()) return@eventDispatcher
            if (activePatchBundleType == PatchBundleType.RESEAM) {
                if (event is ProgressEvent.Started && event.stepId == StepId.LoadPatches) {
                    patchExecutionOrder = emptyList()
                }
                val patch = event.stepId as? StepId.ExecutePatch
                if (patch != null && patch.index !in patchExecutionOrder) {
                    patchExecutionOrder = patchExecutionOrder + patch.index
                }
            }
            val sequence = progressSequence.incrementAndGet()
            args.onEvent(
                buildWorkerProgressUpdate(
                    sequence = sequence,
                    event = event,
                    notificationProgress = persistedNotificationProgress(event, totalPatchCount)
                )
            )
            updateForegroundNotification(event, totalPatchCount, sequence = sequence)
            persistProgressSnapshot(sequence, event, totalPatchCount)
        }

        val startTime = SystemClock.elapsedRealtime()
        return try {
            signatureMetadataOutput?.delete()
            workerLogger.info("Patching session started elapsedRealtime=${startTime}ms")
            val autoSaveDownloads = prefs.autoSaveDownloaderApks.get()

            if (args.input is SelectedApp.Installed) {
                installedAppRepository.get(args.packageName)?.let {
                    if (it.installType == InstallType.MOUNT) {
                        rootMountCoordinator.execute(
                            RootMountRequest(
                                args.packageName,
                                userId = android.os.Process.myUid() / 100_000,
                                operation = RootMountOperation.UNMOUNT
                            )
                        ).requireSuccess()
                    }
                }
            }

            suspend fun download(plugin: LoadedDownloaderPlugin, data: Parcelable) =
                downloadedAppRepository.download(
                    plugin,
                    data,
                    args.packageName,
                    args.input.version,
                    prefs.suggestedVersionSafeguard.get(),
                    !prefs.disablePatchVersionCompatCheck.get(),
                    onDownload = run {
                        var lastProgressAt = 0L
                        var lastProgressBytes = 0L
                        progressHandler@{ progress ->
                            val current = progress.first
                            val total = progress.second
                            val now = System.currentTimeMillis()
                            val isFinal = total != null && total > 0L && current >= total
                            val shouldDispatch =
                                isFinal ||
                                    lastProgressAt == 0L ||
                                    (now - lastProgressAt) >= DOWNLOAD_PROGRESS_MIN_INTERVAL_MS ||
                                    (current - lastProgressBytes) >= DOWNLOAD_PROGRESS_MIN_BYTES

                            if (!shouldDispatch) return@progressHandler

                            lastProgressAt = now
                            lastProgressBytes = current
                            eventDispatcher(
                                ProgressEvent.Progress(
                                    stepId = StepId.DownloadAPK,
                                    current = current,
                                    total = total
                                )
                            )
                        }
                    },
                    persistDownload = autoSaveDownloads
                ).also { result ->
                    args.setInputFile(result.file, result.needsSplit, result.merged)
                }

            val downloadResult = args.preparedInput ?: when (val selectedApp = args.input) {
                is SelectedApp.Download -> runStep(StepId.DownloadAPK, eventDispatcher) {
                    val (plugin, data) = downloaderPluginRepository.unwrapParceledData(selectedApp.data)
                    download(plugin, data)
                }

                is SelectedApp.Search -> runStep(StepId.DownloadAPK, eventDispatcher) {
                    var lastUserInteractionFailure: UserInteractionException? = null
                    for (plugin in downloaderPluginRepository.loadedPluginsFlow.first()) {
                        val interactionFailure = AtomicReference<UserInteractionException?>(null)
                        try {
                            val getScope = object : GetScope {
                                override val pluginPackageName = plugin.packageName
                                override val hostPackageName = applicationContext.packageName
                                override suspend fun requestStartActivity(intent: Intent): Intent? {
                                    interactionFailure.get()?.let { error -> throw error }
                                    val result = try {
                                        args.handleStartActivityRequest(plugin, intent)
                                    } catch (e: UserInteractionException) {
                                        interactionFailure.compareAndSet(null, e)
                                        throw e
                                    }
                                    interactionFailure.get()?.let { error -> throw error }
                                    return when (result.resultCode) {
                                        Activity.RESULT_OK -> result.data
                                        Activity.RESULT_CANCELED -> {
                                            val error = UserInteractionException.Activity.Cancelled()
                                            interactionFailure.compareAndSet(null, error)
                                            throw error
                                        }
                                        else -> {
                                            val error = UserInteractionException.Activity.NotCompleted(
                                                result.resultCode,
                                                result.data
                                            )
                                            interactionFailure.compareAndSet(null, error)
                                            throw error
                                        }
                                    }
                                }
                            }
                            val result = runInterruptiblePluginGet(interactionFailure) {
                                plugin.get(
                                    getScope,
                                    selectedApp.packageName,
                                    selectedApp.version
                                )
                            }?.takeIf { (_, version) ->
                                selectedApp.version == null ||
                                    version == null ||
                                    version == selectedApp.version
                            }
                            if (result != null) {
                                val (data, _) = result
                                return@runStep download(plugin, data)
                            }
                        } catch (e: UserInteractionException.Activity.NotCompleted) {
                            throw e
                        } catch (e: UserInteractionException) {
                            lastUserInteractionFailure = e
                        }
                    }
                    throw (lastUserInteractionFailure ?: Exception("App is not available."))
                }

                is SelectedApp.Local -> {
                    val file = SharedApkFiles.resolve(selectedApp.file)
                    val needsSplit = SplitApkPreparer.isSplitArchive(file)
                    args.setInputFile(file, needsSplit, false)
                    DownloadResult(file, needsSplit)
                }

                is SelectedApp.Installed -> {
                    val input = prepareInstalledInput(selectedApp.packageName)
                    args.setInputFile(input.file, input.needsSplit, false)
                    input
                }
            }
            downloadCleanup = downloadResult.cleanup
            // A prepared selection can outlive the idle migration of its original path.
            val inputFile = SharedApkFiles.resolve(downloadResult.file)
            val patchingContext = currentCoroutineContext()
            val checkCancelled: () -> Unit = { patchingContext.ensureActive() }
            val inputIsSplitArchive = SplitApkPreparer.isSplitArchive(
                file = inputFile,
                checkCancelled = checkCancelled
            )
            if (signatureMetadataOutput != null) {
                workerLogger.info("Caching original signature metadata before split preparation")
                try {
                    signatureMetadataInjector.cacheSignatureMetadata(inputFile, signatureMetadataOutput)
                } catch (error: IllegalArgumentException) {
                    throw IOException(
                        applicationContext.getString(R.string.patcher_signature_workflow_unsigned_source),
                        error
                    )
                }
            }
            val sourceInfo = try {
                // The download already validated this exact file; avoid extracting its base again.
                downloadResult.packageInfo ?: SplitArchiveDisplayResolver.resolvePackageInfo(
                    source = inputFile,
                    workspace = fs.tempDir.resolve("patch-input-metadata"),
                    pm = pm,
                    checkCancelled = checkCancelled
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(
                    tag,
                    "Failed to resolve patch input metadata for ${args.packageName}",
                    error
                )
                null
            }
            val resolvedInputPackageName = sourceInfo?.packageName
                ?.takeIf(String::isNotBlank)
            val resolvedInputVersionName = sourceInfo?.versionName
                ?.takeIf(String::isNotBlank)
            val resolvedInputVersionCode = sourceInfo?.let(pm::getVersionCode)
            args.setInputMetadata(resolvedInputVersionName, resolvedInputVersionCode)
            val inputPackageName = resolvedInputPackageName ?: args.packageName
            val inputVersionName = resolvedInputVersionName
                ?: args.input.version?.takeIf(String::isNotBlank)
            val inputVersionCode = resolvedInputVersionCode ?: args.input.versionCode
            workerLogger.info("App package: $inputPackageName")
            workerLogger.info("App version: ${inputVersionName ?: "unspecified"}")
            workerLogger.info("App version code: ${inputVersionCode ?: "unspecified"}")

            // Code adapted from Morphe, see third-party/NOTICE for more information
            // https://github.com/MorpheApp/morphe-manager/pull/795
            if (shouldRetainOriginalInput(args.input, inputFile)) {
                runCatching {
                    val sourceVersion = resolvedInputVersionName
                        ?: args.input.version.orEmpty()
                    val sourceVersionCode = resolvedInputVersionCode ?: args.input.versionCode
                    fs.saveOriginalAppFile(
                        packageName = args.packageName,
                        version = sourceVersion,
                        versionCode = sourceVersionCode,
                        source = inputFile
                    )
                }.onFailure { error ->
                    Log.w(
                        tag,
                        "Failed to retain original patch input for ${args.packageName}",
                        error
                    )
                }
            }

            val bundleType = patchBundleRepository.selectionBundleType(args.selectedPatches)
                ?: throw IllegalStateException("Cannot patch with mixed ReVanced or Morphe bundles.")
            activePatchBundleType = bundleType
            if (
                bundleType == PatchBundleType.REVANCED &&
                patchBundleRepository.selectionHasMixedRevancedPatcherVersions(args.selectedPatches)
            ) {
                throw IllegalStateException(
                    "Cannot patch with mixed ReVanced patcher versions. " +
                        "Select either ReVanced v21 or v22 patches."
                )
            }
            val useRevancedPatcher22 =
                bundleType == PatchBundleType.REVANCED &&
                    patchBundleRepository.selectionUsesRevancedPatcher22(args.selectedPatches)
            val patcherEngine = patcherEngineDisplayName(bundleType, useRevancedPatcher22)
            val morpheBytecodeMode = if (bundleType == PatchBundleType.MORPHE) {
                prefs.morpheBytecodeMode.get().runtimeValue
            } else {
                null
            }
            activeMorpheDexGroupTitle = when {
                morpheBytecodeMode.equals("FULL", ignoreCase = true) -> "Compiling DEX files: FULL"
                morpheBytecodeMode != null -> "Compiling DEX files: FAST"
                else -> null
            }
            val stripNativeLibs = prefs.stripUnusedNativeLibs.get()
            val skipUnneededSplits = prefs.skipUnneededSplitApks.get()
            requestedPatcherMemoryLimitMb = null
            // Code adapted from Morphe, see third-party/NOTICE for more information.
            // https://github.com/MorpheApp/morphe-manager/pull/1088
            if (!args.skipApkSigning) keystoreManager.preloadSigner()
            val selectedCount = totalPatchCount
            val manualSplitSelectionRequested = inputIsSplitArchive && args.splitSelection != null
            val inputSplitCount = if (inputIsSplitArchive) {
                try {
                    SplitApkPreparer.splitApkEntryNames(
                        file = inputFile,
                        checkCancelled = checkCancelled
                    ).size
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
            val nativeLibsStripped = if (inputIsSplitArchive) {
                args.splitSelection?.stripNativeLibs ?: stripNativeLibs
            } else {
                stripNativeLibs
            }
            val skipUnusedSplitsForRun =
                inputIsSplitArchive && skipUnneededSplits && !manualSplitSelectionRequested
            val splitCountLog = inputSplitCount?.let { " splits=$it" }.orEmpty()
            workerLogger.info(
                "Patching started at ${System.currentTimeMillis()} " +
                    "pkg=$inputPackageName version=${inputVersionName ?: "unspecified"} " +
                    "input=${inputFile.absolutePath} size=${inputFile.length()} " +
                    "split=$inputIsSplitArchive$splitCountLog patches=$selectedCount " +
                    "nativeLibsStripped=$nativeLibsStripped"
            )
            workerLogger.info(
                "Patcher runtime: bundle=$bundleType; automatic memory budget"
            )
            patcherEngine?.let { workerLogger.info("Patcher engine: $it") }
            morpheBytecodeMode?.let { workerLogger.info("Morphe bytecode mode: $it") }
            workerLogger.info("Strip native libs: ${if (nativeLibsStripped) "on" else "off"}")
            workerLogger.info("Skip unused splits: ${if (skipUnusedSplitsForRun) "on" else "off"}")

            var runtimeInputFile = inputFile
            var manualSplitSelectionApplied = false
            if (inputIsSplitArchive && args.splitSelection != null && bundleType != PatchBundleType.RESEAM) {
                val selection = args.splitSelection
                val mergeWorkspace = fs.tempDir.resolve("split-patcher-selection-${id}").apply {
                    deleteRecursively()
                    mkdirs()
                }
                val mergeRuntime = SplitMergeRuntime(applicationContext)
                activeSplitMergeRuntime = mergeRuntime
                eventDispatcher(
                    ProgressEvent.Started(
                        StepId.PrepareSplitApk,
                        subSteps = emptyList()
                    )
                )
                runtimeInputFile = try {
                    mergeRuntime.execute(
                        inputFile = inputFile,
                        workspace = mergeWorkspace,
                        stripNativeLibs = selection.stripNativeLibs,
                        skipUnneededSplits = false,
                        includedModules = selection.includedModules,

                        onProgress = { message ->
                            eventDispatcher(
                                ProgressEvent.Progress(
                                    stepId = StepId.PrepareSplitApk,
                                    message = message
                                )
                            )
                        },
                        onSubSteps = { subSteps ->
                            eventDispatcher(
                                ProgressEvent.Progress(
                                    stepId = StepId.PrepareSplitApk,
                                    subSteps = subSteps
                                )
                            )
                        },
                        onLog = workerLogger::info,
                        onMemoryUsage = { sample ->
                            publishPatcherMemoryUsage(sample, args.onEvent)
                        }
                    )
                } finally {
                    activeSplitMergeRuntime = null
                }
                manualSplitSelectionApplied = true
                workerLogger.info(
                    "Selected ${selection.includedModules.size} split modules before patching"
                )
                eventDispatcher(ProgressEvent.Completed(StepId.PrepareSplitApk))
            }
            val effectiveStripNativeLibs = when {
                manualSplitSelectionApplied -> false
                bundleType == PatchBundleType.RESEAM -> args.splitSelection?.stripNativeLibs ?: stripNativeLibs
                else -> stripNativeLibs
            }
            // Manual selection owns the split set, including Reseam's unmerged inputs.
            val effectiveSkipUnneededSplits =
                if (manualSplitSelectionRequested) false else skipUnneededSplits
            // Code adapted from Morphe, see third-party/NOTICE for more information
            // https://github.com/MorpheApp/morphe-manager/blob/a2c3d31bd7ab42e6bc4b9dd528ed856fc72fb948/app/src/main/java/app/morphe/manager/patcher/worker/PatcherWorker.kt
            val runtimeMemoryUsageDispatcher: (Long, Long) -> Unit = { usedMb, maxMb ->
                publishPatcherMemoryUsage(
                    PatcherMemoryUsage(
                        usedMb = usedMb.coerceIn(0L, maxMb.coerceAtLeast(1L)),
                        maxMb = maxMb.coerceAtLeast(1L)
                    ),
                    args.onEvent
                )
            }
            suspend fun executeSelectedRuntime(
                attemptLogger: Logger
            ) {
                when (bundleType) {
                PatchBundleType.MORPHE -> {
                    check(MorpheRuntimeAssets.isAvailable(applicationContext)) {
                        "Morphe runtime is not included in this build."
                    }
                    // Code adapted from Morphe, see third-party/NOTICE for more information.
                    // https://github.com/MorpheApp/morphe-manager/pull/1202
                    val runtime = MorpheJvmRuntime(applicationContext)
                    activeMorpheRuntime = runtime
                    runtime.execute(
                        runtimeInputFile.absolutePath,
                        patchedApk.absolutePath,
                        args.packageName,
                        args.selectedPatches,
                        args.options,
                        attemptLogger,
                        eventDispatcher,
                        runtimeMemoryUsageDispatcher,
                        effectiveStripNativeLibs,
                        effectiveSkipUnneededSplits
                    )
                }
                PatchBundleType.RESEAM -> {
                    val runtime = app.universal.patch.manager.patcher.runtime.ReseamBoundRuntime(
                        applicationContext, args.splitSelection?.includedModules)
                    activeRuntime = runtime
                    runtime.execute(
                        runtimeInputFile.absolutePath, patchedApk.absolutePath,
                        args.packageName, args.selectedPatches, args.options,
                        attemptLogger, eventDispatcher, runtimeMemoryUsageDispatcher,
                        effectiveStripNativeLibs, effectiveSkipUnneededSplits
                    )
                }
                PatchBundleType.AMPLE -> throw IllegalStateException("Ample runtime is no longer supported.")
                PatchBundleType.REVANCED -> {
                    if (!useRevancedPatcher22) {
                        check(Revanced21RuntimeAssets.isAvailable(applicationContext)) {
                            "ReVanced v21 runtime plugin is not installed or trusted."
                        }
                    }
                    val runtime = RevancedJvmRuntime(applicationContext, useRevancedPatcher22)
                    activeRuntime = runtime
                    runtime.execute(
                        runtimeInputFile.absolutePath,
                        patchedApk.absolutePath,
                        args.packageName,
                        args.selectedPatches,
                        args.options,
                        attemptLogger,
                        eventDispatcher,
                        runtimeMemoryUsageDispatcher,
                        effectiveStripNativeLibs,
                        effectiveSkipUnneededSplits
                    )
                }
            }

            }

            // Code adapted from Morphe, see third-party/NOTICE for more information
            // https://github.com/MorpheApp/morphe-manager/blob/a2c3d31bd7ab42e6bc4b9dd528ed856fc72fb948/app/src/main/java/app/morphe/manager/patcher/runtime/ProcessRuntime.kt
            executeSelectedRuntime(workerLogger)

            if (args.skipApkSigning) {
                workerLogger.warn("APK signing skipped; saving unsigned output")
                patchedApk.copyTo(File(args.output), overwrite = true)
            } else {
                // Code adapted from Morphe, see third-party/NOTICE for more information.
                // https://github.com/MorpheApp/morphe-manager/pull/1088
                runStep(StepId.SignAPK, eventDispatcher) {
                    val signTime = measureTime {
                        keystoreManager.signPatchedApk(patchedApk)
                        runCancellableBlockingIo(checkCancelled) {
                            Files.move(
                                patchedApk.toPath(),
                                File(args.output).toPath(),
                                StandardCopyOption.REPLACE_EXISTING
                            )
                        }
                    }
                    workerLogger.info("Signed apk in ${signTime.inWholeMilliseconds}ms")
                }
            }

            val elapsed = (SystemClock.elapsedRealtime() - startTime).coerceAtLeast(0L)
            val rt = Runtime.getRuntime()
            val usedMem = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
            val totalMem = rt.totalMemory() / (1024 * 1024)

            workerLogger.info(
                "Patching succeeded: output=${args.output} size=${File(args.output).length()} " +
                        "elapsed=${elapsed}ms memory=${usedMem}MB/${totalMem}MB"
            )

            Log.i(tag, "Patching succeeded".logFmt())
            val repatchSourcePath = runCatching {
                fs.stageRepatchInputFile(inputFile).absolutePath
            }.onFailure { error ->
                Log.w(tag, "Failed to retain Repatch input for ${args.packageName}", error)
            }.getOrNull()
            val resultData = Data.Builder()
                .putIntArray(FAILED_PATCH_INDEXES_KEY, currentFailedPatchIndexArray())
                .apply {
                    resolvedInputVersionName?.let {
                        putString(INPUT_VERSION_NAME_KEY, it)
                    }
                    resolvedInputVersionCode?.let {
                        putLong(INPUT_VERSION_CODE_KEY, it)
                    }
                    repatchSourcePath?.let {
                        putString(REPATCH_SOURCE_PATH_KEY, it)
                    }
                }
                .build()
            // The caller owns the cache after success and removes it after injection.
            val result = Result.success(resultData)
            currentCoroutineContext().ensureActive()
            retainSignatureMetadata = true
            result
        } catch (e: CancellationException) {
            Log.i(tag, "Patching cancelled".logFmt())
            throw e
        } catch (e: app.universal.patch.manager.patcher.runtime.BoundRuntimeFailureException) {
            Log.e(tag, "An exception occurred in the patcher runtime".logFmt(), e)
            // Keep the engine's trace identical to its earlier Failed event, so the progress
            // tracker recognizes the terminal wrapper and preserves the actual failed stage.
            val error = e.toRemoteError()
            eventDispatcher(ProgressEvent.Failed(null, error))
            Result.failure(
                workDataOf(PROCESS_FAILURE_MESSAGE_KEY to trimForWorkData(error.stackTrace))
            )
        } catch (e: MorpheBridgeFailureException) {
            Log.e(
                tag,
                "An exception occurred in the Morphe bridge runtime while patching. ${e.originalStackTrace}".logFmt()
            )
            eventDispatcher(
                ProgressEvent.Failed(
                    null,
                    RemoteError(
                        type = e::class.java.name,
                        message = e.message,
                        stackTrace = e.originalStackTrace
                    )
                )
            )
            Result.failure(
                workDataOf(PROCESS_FAILURE_MESSAGE_KEY to trimForWorkData(e.originalStackTrace))
            )
        } catch (e: Revanced22BridgeFailureException) {
            Log.e(
                tag,
                "An exception occurred in the ReVanced v22 bridge runtime while patching. ${e.originalStackTrace}".logFmt()
            )
            eventDispatcher(
                ProgressEvent.Failed(
                    null,
                    RemoteError(
                        type = e::class.java.name,
                        message = e.message,
                        stackTrace = e.originalStackTrace
                    )
                )
            )
            Result.failure(
                workDataOf(PROCESS_FAILURE_MESSAGE_KEY to trimForWorkData(e.originalStackTrace))
            )
        } catch (e: Revanced21BridgeFailureException) {
            Log.e(
                tag,
                "An exception occurred in the ReVanced v21 bridge runtime while patching. ${e.originalStackTrace}".logFmt()
            )
            eventDispatcher(
                ProgressEvent.Failed(
                    null,
                    RemoteError(
                        type = e::class.java.name,
                        message = e.message,
                        stackTrace = e.originalStackTrace
                    )
                )
            )
            Result.failure(
                workDataOf(PROCESS_FAILURE_MESSAGE_KEY to trimForWorkData(e.originalStackTrace))
            )
        } catch (e: UserInteractionException) {
            Log.i(tag, "User cancelled downloader interaction".logFmt(), e)
            eventDispatcher(ProgressEvent.Failed(null, e.toRemoteError()))
            Result.failure(
                workDataOf(
                    PROCESS_FAILURE_MESSAGE_KEY to trimForWorkData(
                        e.message ?: "Downloader interaction cancelled by user"
                    )
                )
            )
        } catch (e: OutOfMemoryError) {
            Log.e(tag, "Patching ran out of memory".logFmt(), e)
            val safeError = e.toSafeRemoteError()
            eventDispatcher(ProgressEvent.Failed(null, safeError))
            Result.failure(
                workDataOf(
                    PROCESS_FAILURE_MESSAGE_KEY to trimForWorkData(safeError.stackTrace)
                )
            )
        } catch (e: Exception) {
            Log.e(tag, "An exception occurred while patching".logFmt(), e)
            eventDispatcher(ProgressEvent.Failed(null, e.toRemoteError()))
            Result.failure(
                workDataOf(PROCESS_FAILURE_MESSAGE_KEY to trimForWorkData(e.stackTraceToString()))
            )
        } finally {
            val sessionElapsed =
                (SystemClock.elapsedRealtime() - startTime).coerceAtLeast(0L)
            runCatching {
                workerLogger.info("Patching session finished elapsed=${sessionElapsed}ms")
            }.onFailure { error ->
                Log.d(tag, "Failed to publish patching session duration", error)
            }
            activeRuntime = null
            activeMorpheRuntime = null
            activeSplitMergeRuntime = null
            patchNotificationSteps = emptyList()
            foregroundStarted = false
            patchedApk.delete()
            if (!retainSignatureMetadata) signatureMetadataOutput?.delete()
            try {
                downloadCleanup?.invoke()
            } finally {
                cleanupTemporarySplitArtifacts()
                runCatching { fs.pruneTemporaryPatchFiles() }.onFailure { error ->
                    Log.w(tag, "Failed to clean the temporary patch workspace", error)
                }
            }
        }
    }

    // Code adapted from Morphe, see third-party/NOTICE for more information
    // https://github.com/MorpheApp/morphe-manager/blob/a2c3d31bd7ab42e6bc4b9dd528ed856fc72fb948/app/src/main/java/app/morphe/manager/patcher/runtime/ProcessRuntime.kt

    private fun publishPatcherMemoryUsage(
        memoryUsage: PatcherMemoryUsage,
        onEvent: (PatcherWorkerProgressUpdate) -> Unit
    ) {
        val sample = memoryUsage.copy(
            requestedMaxMb = requestedPatcherMemoryLimitMb ?: memoryUsage.maxMb
        )
        lastPatcherMemoryUsage = sample
        val sequence = progressSequence.incrementAndGet()
        runCatching {
            onEvent(
                PatcherWorkerProgressUpdate(
                    generation = progressGeneration,
                    sequence = sequence,
                    memoryUsage = sample,
                    isMemorySample = true
                )
            )
        }.onFailure { error ->
            Log.d(tag, "Failed to publish patcher memory usage", error)
        }
    }

    companion object {
        @Volatile
        var backgroundExecutionActive: Boolean = false
            private set
        @Volatile
        private var backgroundExecutionRequested: Boolean = false

        val backgroundExecutionAllowed: Boolean
            get() = backgroundExecutionActive || backgroundExecutionRequested

        fun markBackgroundExecutionRequested() {
            backgroundExecutionRequested = true
        }

        fun clearBackgroundExecutionRequested() {
            backgroundExecutionRequested = false
        }

        private const val LOG_PREFIX = "[Worker]"
        private fun String.logFmt() = "$LOG_PREFIX $this"
        const val UNIQUE_WORK_NAME = "patching"
        internal const val PATCHING_NOTIFICATION_CHANNEL_ID = "revanced-patcher-patching"
        internal const val NOTIFICATION_ID = 1
        const val PROCESS_EXIT_CODE_KEY = "process_exit_code"

        const val PROCESS_FAILURE_MESSAGE_KEY = "process_failure_message"
        const val PATCHING_ACTIVE_KEY = "patching_active"
        const val FAILED_PATCH_INDEXES_KEY = "failed_patch_indexes"
        const val INPUT_VERSION_NAME_KEY = "input_version_name"
        const val INPUT_VERSION_CODE_KEY = "input_version_code"
        const val REPATCH_SOURCE_PATH_KEY = "repatch_source_path"
        private const val WORK_DATA_MAX_BYTES = 9000
        private const val DOWNLOAD_PROGRESS_MIN_INTERVAL_MS = 150L
        private const val DOWNLOAD_PROGRESS_MIN_BYTES = 256 * 1024L
        private const val NOTIFICATION_PROGRESS_MAX = 1000
        private const val LOAD_PATCHES_START = 0
        private const val LOAD_PATCHES_END = 220
        private const val PREPARE_SPLIT_START = LOAD_PATCHES_START
        private const val PREPARE_SPLIT_END = LOAD_PATCHES_END
        private const val PREPARE_SPLIT_WITH_SPLIT_START = 0
        private const val PREPARE_SPLIT_WITH_SPLIT_END = 120
        private const val LOAD_PATCHES_WITH_SPLIT_START = PREPARE_SPLIT_WITH_SPLIT_END
        private const val LOAD_PATCHES_WITH_SPLIT_END = 220
        private const val READ_APK_START = PREPARE_SPLIT_END
        private const val READ_APK_END = 320
        private const val EXECUTE_PATCHES_START = READ_APK_END
        private const val EXECUTE_PATCHES_END = 820
        private const val WRITE_APK_START = EXECUTE_PATCHES_END
        private const val WRITE_APK_END = 970
        private const val SIGN_APK_START = WRITE_APK_END
        private const val SIGN_APK_END = NOTIFICATION_PROGRESS_MAX

        private fun ensureNotificationChannel(context: Context): String {
            val manager = context.getSystemService(NotificationManager::class.java)
                ?: error("NotificationManager unavailable")
            val channel = NotificationChannel(
                PATCHING_NOTIFICATION_CHANNEL_ID,
                context.getString(R.string.notification_channel_patching_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notification_channel_patching_description)
            }
            manager.createNotificationChannel(channel)
            return channel.id
        }

        private fun createNotificationBuilder(
            context: Context,
            title: CharSequence = context.getText(R.string.patcher_notification_title)
        ): Notification.Builder {
            val notificationIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                notificationIntent,
                PendingIntent.FLAG_IMMUTABLE
            )
            return Notification.Builder(context, ensureNotificationChannel(context))
                .setContentTitle(title)
                .setLargeIcon(Icon.createWithResource(context, R.drawable.ic_notification))
                .setSmallIcon(Icon.createWithResource(context, R.drawable.ic_notification_status))
                .setContentIntent(pendingIntent)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
        }

        fun showInitialNotification(
            context: Context,
            appName: String? = null,
            queuePosition: Int? = null,
            queueSize: Int? = null
        ) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            runCatching {
                val title = appName
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?.let { context.getString(R.string.batch_patch_patching_app, it) }
                    ?: context.getText(R.string.patcher_notification_title)
                val queueLabel = queuePosition?.let { position ->
                    queueSize?.let { size ->
                        context.getString(
                            R.string.batch_patch_notification_queue_progress,
                            position,
                            size
                        )
                    }
                }
                val baseText = context.getText(R.string.patcher_notification_text)
                val contentText = queueLabel?.let { "$it • $baseText" } ?: baseText
                val notification = createNotificationBuilder(context, title)
                    .setContentText(contentText)
                    .applyProgressNotification(
                        max = 0,
                        current = 0,
                        indeterminate = true
                    )
                    .build()
                manager.notify(NOTIFICATION_ID, notification)
            }.onFailure { error ->
                Log.d("PatcherWorker", "Failed to publish initial patching notification", error)
            }
        }

        fun clearNotification(context: Context) {
            runCatching {
                context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
            }.onFailure { error ->
                Log.d("PatcherWorker", "Failed to clear patching notification", error)
            }
        }
    }

    private fun trimForWorkData(message: String?): String? {
        if (message.isNullOrEmpty()) return message
        val utf8 = Charsets.UTF_8
        if (message.toByteArray(utf8).size <= WORK_DATA_MAX_BYTES) return message
        var end = message.length
        while (end > 0) {
            val candidate = message.substring(0, end)
            if (candidate.toByteArray(utf8).size <= WORK_DATA_MAX_BYTES) {
                return candidate + "\n[truncated]"
            }
            end -= 1
        }
        return message.take(512) + "\n[truncated]"
    }

    private suspend fun <T> runInterruptiblePluginGet(
        interactionFailure: AtomicReference<UserInteractionException?>,
        block: suspend () -> T
    ): T = runCancellableBlockingIo(
        checkCancelled = {
            interactionFailure.get()?.let { error -> throw error }
        }
    ) {
        runBlocking { block() }
    }.also {
        interactionFailure.get()?.let { error -> throw error }
    }

    private fun cleanupTemporarySplitArtifacts() {
        fs.tempDir.listFiles()?.forEach { child ->
            val shouldDelete = child.name == "patched.apk" ||
                child.name.startsWith("split-") ||
                child.name.startsWith("installed-splits-") ||
                child.name.startsWith("reseam-")
            if (!shouldDelete) return@forEach
            runCatching {
                if (child.isDirectory) {
                    child.deleteRecursively()
                } else {
                    child.delete()
                }
            }
        }
    }

    private suspend fun shouldRetainOriginalInput(
        input: SelectedApp,
        inputFile: File
    ): Boolean {
        if (fs.isManagedPatchedAppFile(inputFile)) return false
        if (input !is SelectedApp.Installed) return true

        val installedInfo = pm.getPackageInfo(input.packageName) ?: return true
        val installedApk = installedInfo.applicationInfo?.sourceDir
            ?.let(::File)
            ?: inputFile
        return installedAppRepository.getCurrentInstalledRecord(
            packageName = input.packageName,
            installedVersion = installedInfo.versionName,
            installedLastUpdateTime = installedInfo.lastUpdateTime,
            installedApk = installedApk
        ) == null
    }

    private suspend fun prepareInstalledInput(packageName: String): DownloadResult = withContext(Dispatchers.IO) {
        val packageInfo = pm.getPackageInfo(packageName)
            ?: throw IllegalStateException("Installed package not found: $packageName")
        val appInfo = packageInfo.applicationInfo
            ?: throw IllegalStateException("ApplicationInfo missing for package: $packageName")
        val basePath = appInfo.sourceDir
            ?: throw IllegalStateException("sourceDir missing for package: $packageName")

        val baseApk = File(basePath)
        if (!baseApk.exists()) {
            throw IllegalStateException("Base APK not found for package: $packageName")
        }
        val splitApks = appInfo.splitSourceDirs
            ?.map(::File)
            ?.filter(File::exists)
            ?.sortedBy { it.name }
            .orEmpty()

        if (splitApks.isEmpty()) {
            return@withContext DownloadResult(baseApk, needsSplit = false)
        }

        val archiveDir = fs.tempDir.resolve("installed-splits-${System.currentTimeMillis()}").apply { mkdirs() }
        val archiveFile = archiveDir.resolve("${packageName.replace('.', '_')}.apks")

        buildInstalledSplitArchive(
            apkFiles = listOf(baseApk) + splitApks,
            output = archiveFile
        )

        DownloadResult(
            file = archiveFile,
            needsSplit = true,
            cleanup = { archiveDir.deleteRecursively() }
        )
    }

    private fun buildInstalledSplitArchive(apkFiles: List<File>, output: File) {
        output.parentFile?.mkdirs()
        val usedNames = LinkedHashSet<String>()
        var writtenEntries = 0
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            apkFiles.forEachIndexed { index, apk ->
                if (!apk.exists()) return@forEachIndexed
                val entryName = uniqueSplitEntryName(apk.name, index, usedNames)
                zip.putNextEntry(ZipEntry(entryName).apply { time = apk.lastModified() })
                apk.inputStream().buffered().use { input -> input.copyTo(zip) }
                zip.closeEntry()
                writtenEntries++
            }
        }
        if (writtenEntries == 0) {
            throw IllegalStateException("Failed to build installed split archive: no APK entries written.")
        }
    }

    private fun uniqueSplitEntryName(originalName: String, index: Int, usedNames: MutableSet<String>): String {
        val normalized = if (originalName.endsWith(".apk", ignoreCase = true)) originalName else "$originalName.apk"
        if (usedNames.add(normalized)) return normalized

        val dot = normalized.lastIndexOf('.')
        val base = if (dot >= 0) normalized.substring(0, dot) else normalized
        val ext = if (dot >= 0) normalized.substring(dot) else ".apk"
        var counter = 1
        while (true) {
            val candidate = "${base}_${index}_$counter$ext"
            if (usedNames.add(candidate)) return candidate
            counter++
        }
    }
}
