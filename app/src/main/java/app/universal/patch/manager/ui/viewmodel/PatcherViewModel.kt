package app.universal.patch.manager.ui.viewmodel

import app.universal.patch.manager.data.platform.SharedApkFiles
import app.universal.patch.manager.patcher.runtime.PatcherJvm
import android.app.Activity

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.os.ParcelUuid
import android.os.Parcelable
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.autoSaver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.SavedStateHandleSaveableApi
import androidx.lifecycle.viewmodel.compose.saveable
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.universal.patch.manager.R
import app.universal.patch.manager.data.platform.Filesystem
import app.universal.patch.manager.data.room.apps.installed.InstallType
import app.universal.patch.manager.data.room.apps.installed.InstalledApp
import app.universal.patch.manager.domain.installer.InstallCancelledException
import app.universal.patch.manager.domain.installer.InstallResult
import app.universal.patch.manager.domain.installer.InstallerManager
import app.universal.patch.manager.domain.installer.installerTokenMatchesPatchMode
import app.universal.patch.manager.domain.installer.shouldApplyProfileInstallerPreference
import app.universal.patch.manager.domain.installer.installerTokenSelectableForPatchedOutput
import app.universal.patch.manager.domain.installer.packageInfoIsCompleteSingleApk
import app.universal.patch.manager.domain.installer.patchedOutputSupportsRootMount
import app.universal.patch.manager.domain.installer.rootMountStockIdentityUsable
import app.universal.patch.manager.domain.installer.rootMountStockReplacementRequired
import app.universal.patch.manager.domain.installer.RootInstaller
import app.universal.patch.manager.domain.installer.RootServiceException
import app.universal.patch.manager.domain.installer.SessionDeadException
import app.universal.patch.manager.domain.installer.SessionInstaller
import app.universal.patch.manager.domain.installer.ShizukuInstaller
import app.universal.patch.manager.domain.installer.root.AndroidPackageStateReader
import app.universal.patch.manager.domain.installer.root.LibsuRootShellGateway
import app.universal.patch.manager.domain.installer.root.RootMountOperation
import app.universal.patch.manager.domain.installer.root.RootMountPhase
import app.universal.patch.manager.domain.installer.root.RootMountRequest
import app.universal.patch.manager.domain.installer.root.RootMountResult
import app.universal.patch.manager.domain.installer.root.RootMountSuspension
import app.universal.patch.manager.domain.installer.root.RootMountTransactionCoordinator
import app.universal.patch.manager.domain.installer.root.describeOutcome
import app.universal.patch.manager.domain.installer.root.installAsPlayStoreWithMountRollback
import app.universal.patch.manager.domain.installer.root.launchExternalInstallerWithMountFinalization
import app.universal.patch.manager.domain.installer.root.reinstallMountedStockAsPlayStore
import app.universal.patch.manager.domain.installer.root.restore
import app.universal.patch.manager.domain.installer.root.retire
import app.universal.patch.manager.domain.installer.root.requireSuccess
import app.universal.patch.manager.domain.installer.root.suspendRootMountForPackageInstall
import app.universal.patch.manager.domain.installer.root.verifiedStockSet
import app.universal.patch.manager.data.room.profile.PatchProfilePayload
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.domain.manager.SignatureMetadataInjectionMode
import app.universal.patch.manager.domain.manager.SignatureMetadataInjectorManager
import app.universal.patch.manager.domain.manager.SignatureMetadataWorkflowProgress
import app.universal.patch.manager.domain.manager.SignatureMetadataSigningMode
import app.universal.patch.manager.domain.storage.CacheCleanupGuard
import app.universal.patch.manager.domain.repository.DownloadResult
import app.universal.patch.manager.domain.repository.DownloadedAppRepository
import app.universal.patch.manager.domain.repository.DownloaderPluginRepository
import app.universal.patch.manager.domain.repository.PatchBundleRepository
import app.universal.patch.manager.domain.repository.PatchOptionsRepository
import app.universal.patch.manager.domain.repository.PatchSelectionRepository
import app.universal.patch.manager.domain.repository.InstalledAppRepository
import app.universal.patch.manager.domain.repository.PendingHistoricalSavedEntry
import app.universal.patch.manager.domain.worker.UniqueWorkAlreadyRunningException
import app.universal.patch.manager.domain.worker.WorkerRepository
import app.universal.patch.manager.patcher.ProgressEvent
import app.universal.patch.manager.patcher.RemoteError
import app.universal.patch.manager.patcher.StepId
import app.universal.patch.manager.patcher.logger.LogLevel
import app.universal.patch.manager.patcher.logger.Logger
import app.universal.patch.manager.patcher.logger.isVerbosePatcherExportLog
import app.universal.patch.manager.patcher.runCancellableBlockingIo
import app.universal.patch.manager.patcher.split.SplitApkPreparer
import app.universal.patch.manager.patcher.worker.PatcherWorker
import app.universal.patch.manager.patcher.worker.PatcherMemoryUsage
import app.universal.patch.manager.patcher.worker.PatcherWorkerProgressState
import app.universal.patch.manager.patcher.worker.PatcherWorkerProgressUpdate
import app.universal.patch.manager.network.downloader.LoadedDownloaderPlugin
import app.universal.patch.manager.plugin.downloader.GetScope
import app.universal.patch.manager.plugin.downloader.PluginHostApi
import app.universal.patch.manager.plugin.downloader.UserInteractionException
import app.universal.patch.manager.ui.model.InstallerModel
import app.universal.patch.manager.patcher.PatcherSessionInfo
import app.universal.patch.manager.ui.model.SelectedApp
import app.universal.patch.manager.ui.model.State
import app.universal.patch.manager.ui.model.Step
import app.universal.patch.manager.ui.model.PatcherProgressTracker
import app.universal.patch.manager.ui.model.StepCategory
import app.universal.patch.manager.ui.model.StepDetail
import app.universal.patch.manager.ui.model.withState
import app.universal.patch.manager.patcher.parsePatcherSessionInfo
import app.universal.patch.manager.patcher.updatedFromLog
import app.universal.patch.manager.ui.model.navigation.Patcher
import app.universal.patch.manager.service.PatchingTaskMonitorService
import app.universal.patch.manager.BuildConfig
import app.universal.patch.manager.util.PM
import app.universal.patch.manager.util.PLAY_STORE_INSTALLER_PACKAGE
import app.universal.patch.manager.util.PatchBundleExportData
import app.universal.patch.manager.util.PatchedAppExportData
import app.universal.patch.manager.util.Options
import app.universal.patch.manager.util.PatchSelection
import app.universal.patch.manager.patcher.patch.PatchBundleInfo
import app.universal.patch.manager.patcher.patch.applyAvailability
import app.universal.patch.manager.patcher.patch.installerTypeFor
import app.universal.patch.manager.patcher.patch.isCompatibleWithInstallerRules
import app.universal.patch.manager.patcher.patch.removeGmsCoreSupport
import app.universal.patch.manager.patcher.patch.PatchBundleType
import app.universal.patch.manager.patcher.patch.patcherEngineDisplayName
import app.universal.patch.manager.util.AppForeground
import app.universal.patch.manager.util.buildSavedAppEntryKey
import app.universal.patch.manager.util.buildSavedAppVariantIdentity
import app.universal.patch.manager.util.isSavedAppEntryForPackage
import app.universal.patch.manager.util.saveableVar
import app.universal.patch.manager.util.saver.snapshotStateListSaver
import app.universal.patch.manager.util.simpleMessage
import app.universal.patch.manager.util.tag
import app.universal.patch.manager.util.toast
import app.universal.patch.manager.util.awaitUserConfirmation
import app.universal.patch.manager.util.toastHandle
import app.universal.patch.manager.util.uiSafe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.time.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt
import kotlin.coroutines.resume
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import ru.solrudev.ackpine.session.Session
import ru.solrudev.ackpine.session.await
import ru.solrudev.ackpine.session.parameters.Confirmation
import ru.solrudev.ackpine.uninstaller.PackageUninstaller
import ru.solrudev.ackpine.uninstaller.UninstallFailure
import ru.solrudev.ackpine.uninstaller.createSession
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.LinkedHashSet
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private class PendingPatchedAppReplacement private constructor(
    private val target: File,
    private val staging: File,
    private val backup: File,
    private val targetExisted: Boolean,
    private val previousLastModified: Long?
) {
    private var finished = false

    fun commit() {
        if (finished) return
        finished = true
        staging.delete()
        backup.delete()
    }

    fun rollback(cause: Throwable) {
        if (finished) return
        val restoreError = runCatching {
            if (targetExisted) {
                check(backup.isFile) { "The previous saved APK backup is unavailable" }
                backup.copyTo(target, overwrite = true)
                check(target.isFile && target.length() == backup.length()) {
                    "Failed to verify the restored saved APK"
                }
                previousLastModified?.let(target::setLastModified)
            } else {
                check(target.delete() || !target.exists()) {
                    "Failed to remove the uncommitted saved APK"
                }
            }
        }.exceptionOrNull()
        staging.delete()
        if (restoreError == null) {
            backup.delete()
        } else {
            cause.addSuppressed(restoreError)
        }
        finished = true
    }

    companion object {
        fun prepare(source: File, target: File): PendingPatchedAppReplacement {
            check(source.isFile) { "The patched APK is unavailable" }
            val directory = requireNotNull(target.parentFile)
            check(directory.mkdirs() || directory.isDirectory) {
                "Unable to create the saved APK directory"
            }
            val staging = directory.resolve(".${target.name}.${UUID.randomUUID()}.tmp")
            val backup = directory.resolve(".${target.name}.${UUID.randomUUID()}.bak")
            val targetExisted = target.isFile
            val previousLastModified = target.takeIf(File::isFile)?.lastModified()
            val replacement = PendingPatchedAppReplacement(
                target = target,
                staging = staging,
                backup = backup,
                targetExisted = targetExisted,
                previousLastModified = previousLastModified
            )
            var replacementStarted = false
            try {
                source.copyTo(staging, overwrite = true)
                check(staging.isFile && staging.length() == source.length()) {
                    "Failed to verify the saved APK staging copy"
                }
                if (targetExisted) {
                    target.copyTo(backup, overwrite = true)
                    check(backup.isFile && backup.length() == target.length()) {
                        "Failed to verify the previous saved APK backup"
                    }
                }
                replacementStarted = true
                staging.copyTo(target, overwrite = true)
                check(target.isFile && target.length() == source.length()) {
                    "Failed to verify the saved patched APK"
                }
                return replacement
            } catch (error: Throwable) {
                if (replacementStarted) {
                    replacement.rollback(error)
                } else {
                    staging.delete()
                    backup.delete()
                }
                throw error
            }
        }
    }
}

@Serializable
private data class PatcherRunConfigurationSnapshot(
    val selectedPatches: Map<Int, List<String>>,
    val options: Map<Int, Map<String, Map<String, PatcherRunOptionValue>>>
) {
    fun selection(): PatchSelection = selectedPatches
        .mapValues { (_, patches) -> patches.toSet() }
        .filterValues { patches -> patches.isNotEmpty() }
}

@Serializable
private data class PatcherRunOptionValue(
    val kind: PatcherRunOptionKind,
    val scalar: String? = null,
    val items: List<PatcherRunOptionValue> = emptyList()
) {
    fun toValue(): Any? = when (kind) {
        PatcherRunOptionKind.NULL -> null
        PatcherRunOptionKind.BOOLEAN -> requireNotNull(scalar).toBooleanStrict()
        PatcherRunOptionKind.INT -> requireNotNull(scalar).toInt()
        PatcherRunOptionKind.LONG -> requireNotNull(scalar).toLong()
        PatcherRunOptionKind.FLOAT -> requireNotNull(scalar).toFloat()
        PatcherRunOptionKind.DOUBLE -> requireNotNull(scalar).toDouble()
        PatcherRunOptionKind.STRING -> requireNotNull(scalar)
        PatcherRunOptionKind.LIST -> items.map(PatcherRunOptionValue::toValue)
    }

    companion object {
        fun fromValue(value: Any?): PatcherRunOptionValue = when (value) {
            null -> PatcherRunOptionValue(PatcherRunOptionKind.NULL)
            is Boolean -> PatcherRunOptionValue(PatcherRunOptionKind.BOOLEAN, value.toString())
            is Int -> PatcherRunOptionValue(PatcherRunOptionKind.INT, value.toString())
            is Long -> PatcherRunOptionValue(PatcherRunOptionKind.LONG, value.toString())
            is Float -> PatcherRunOptionValue(PatcherRunOptionKind.FLOAT, value.toString())
            is Double -> PatcherRunOptionValue(PatcherRunOptionKind.DOUBLE, value.toString())
            is String -> PatcherRunOptionValue(PatcherRunOptionKind.STRING, value)
            is List<*> -> PatcherRunOptionValue(
                kind = PatcherRunOptionKind.LIST,
                items = value.map(::fromValue)
            )
            else -> throw IllegalArgumentException(
                "Unsupported patch option value type: ${value::class.qualifiedName}"
            )
        }
    }
}

@Serializable
private enum class PatcherRunOptionKind {
    NULL,
    BOOLEAN,
    INT,
    LONG,
    FLOAT,
    DOUBLE,
    STRING,
    LIST
}

@OptIn(SavedStateHandleSaveableApi::class, PluginHostApi::class)
class PatcherViewModel(
    private val input: Patcher.ViewModelParams
) : ViewModel(), KoinComponent, InstallerModel {
    private val app: Application by inject()
    private val fs: Filesystem by inject()
    private val pm: PM by inject()
    private val workerRepository: WorkerRepository by inject()
    private val patchBundleRepository: PatchBundleRepository by inject()
    private val patchSelectionRepository: PatchSelectionRepository by inject()
    private val patchOptionsRepository: PatchOptionsRepository by inject()
    private val installedAppRepository: InstalledAppRepository by inject()
    private val downloaderPluginRepository: DownloaderPluginRepository by inject()
    private val downloadedAppRepository: DownloadedAppRepository by inject()
    private val rootInstaller: RootInstaller by inject()
    private val rootMountCoordinator: RootMountTransactionCoordinator by inject()
    private val shizukuInstaller: ShizukuInstaller by inject()
    private val installerManager: InstallerManager by inject()
    private val sessionInstaller: SessionInstaller by inject()
    private val prefs: PreferencesManager by inject()
    private val signatureMetadataInjector: SignatureMetadataInjectorManager by inject()
    private val json: Json by inject()
    private val skipApkSigning = prefs.skipApkSigning.getBlocking()
    private val savedStateHandle: SavedStateHandle = get()
    private val ackpineUninstaller: PackageUninstaller = get()
    private val selectionBundleType by lazy(LazyThreadSafetyMode.NONE) {
        runBlocking { patchBundleRepository.selectionBundleType(appliedSelection) }
    }
    private val selectionMorpheBytecodeMode by lazy(LazyThreadSafetyMode.NONE) {
        if (selectionBundleType == PatchBundleType.MORPHE) {
            runBlocking { prefs.morpheBytecodeMode.get().runtimeValue }
        } else {
            null
        }
    }

    private var pendingExternalInstall: InstallerManager.InstallPlan.External? = null
    private var pendingExternalMountSuspension: RootMountSuspension? = null
    private var pendingExternalMountRestoreJob: Job? = null
    private var pendingExternalMountRestored = false
    private var externalInstallBaseline: Pair<Long?, Long?>? = null
    private var externalInstallStartTime: Long? = null
    private var externalPackageWasPresentAtStart: Boolean = false
    private var externalInstallTimeoutJob: Job? = null
    private var externalInstallPresenceJob: Job? = null
    private var expectedInstallSignature: ByteArray? = null
    private var baselineInstallSignature: ByteArray? = null
    private var internalInstallBaseline: Pair<Long?, Long?>? = null
    private var postTimeoutGraceJob: Job? = null
    private var activeInstallJob: Job? = null
    private var rootMountModeOverrideRefreshJob: Job? = null
    private var installProgressToastJob: Job? = null
    private var installProgressToast: Toast? = null
    private var deferInstallProgressToasts = false
    private var uninstallProgressToastJob: Job? = null
    private var uninstallProgressToast: Toast? = null
    private var deferUninstallProgressToasts = false
    private var pendingSignatureMismatchPlan: InstallerManager.InstallPlan? = null
    private var pendingSignatureMismatchPackage: String? = null
    private var lastInstallToken: InstallerManager.Token? = null
    private var lastInstallTarget: InstallerManager.InstallTarget? = null
    private var lastInstallExpectedPackage: String? = null
    private var lastInstallSourceLabel: String? = null
    private var pendingInstallFailureMessage: String? = null
    var keystoreMissingDialog by mutableStateOf(false)
        private set
    var rootDowngradeConfirmationPending by mutableStateOf(false)
        private set
    private var rootDowngradePlayStoreSourcePending = false
    var rootMountPhase by mutableStateOf<RootMountPhase?>(null)
        private set
    var supportsRootMount by mutableStateOf(true)
        private set
    private var supportsRootMountModeOverride by mutableStateOf(false)
    private var completedPatchHadFailures = false
    private var installedApp: InstalledApp? = null
    private var currentSourceEntryKey: String?
        get() = savedStateHandle.get<String>("current_source_entry_key") ?: input.sourceEntryKey
        set(value) {
            if (value == null) {
                savedStateHandle.remove<String>("current_source_entry_key")
            } else {
                savedStateHandle["current_source_entry_key"] = value
            }
        }
    private suspend fun sourceInstalledApp(): InstalledApp? =
        currentSourceEntryKey
            ?.let { installedAppRepository.get(it) }
            ?: installedAppRepository.get(packageName)

    private val selectedApp = input.selectedApp
    val packageName = selectedApp.packageName
    val version = selectedApp.version
    val versionCode = selectedApp.versionCode
    var informationAppVersion by mutableStateOf(version)
        private set
    var informationAppVersionCode by mutableStateOf(versionCode)
        private set
    val usingMountInstall = input.useMount
    val hasProfileInstallerPreference = input.profileInstallerToken
        ?.let(installerManager::parseToken)
        ?.let { installerTokenMatchesPatchMode(it, usingMountInstall) } == true

    fun isInstallerTokenAllowed(token: InstallerManager.Token): Boolean =
        installerTokenMatchesPatchMode(token, usingMountInstall)

    fun isInstallerTokenSelectable(token: InstallerManager.Token): Boolean =
        installerTokenSelectableForPatchedOutput(
            token = token,
            useMount = usingMountInstall,
            supportsRootMount = supportsRootMountModeOverride
        )

    private suspend fun patchSelectionSupportsRootMountModeOverride(
        sourceVersionName: String,
        sourceVersionCode: Long
    ): Boolean {
        if (completedPatchHadFailures) return false
        val availabilityEnabled = prefs.patchAvailabilityEnabled.get()
        val removeGmsCore = installerManager.baseInstallerToken(
            installerManager.getPrimaryToken()
        ) == InstallerManager.Token.AutoSaved &&
            prefs.removeGmsCoreForPrimaryMount.get()
        if (!availabilityEnabled && !removeGmsCore) return true

        val bundles = patchBundleRepository.scopedBundleInfoFlow(
            packageName,
            sourceVersionName,
            sourceVersionCode
        ).first().associateBy { it.uid }
        if (appliedSelection.isNotEmpty() && bundles.isEmpty()) return false
        val allowIncompatible = prefs.disablePatchVersionCompatCheck.get() ||
            bundles.any { (uid, bundle) ->
                val selected = appliedSelection[uid].orEmpty()
                bundle.incompatible.any { it.name in selected }
            }
        val patchesByBundle = bundles.mapValues { (_, bundle) ->
            bundle.patchSequence(allowIncompatible).associateBy { it.name }
        }
        return appliedSelection.isCompatibleWithInstallerRules(
            installerType = installerTypeFor(useMount = true),
            eligibleBundlePatches = patchesByBundle,
            availabilityEnabled = availabilityEnabled,
            removeGmsCore = removeGmsCore
        )
    }

    private fun stockSourceCandidates(
        sourceVersionName: String,
        sourceVersionCode: Long
    ): List<File> {
        val retainedOriginals = fs.findOriginalAppFiles(
            packageName = packageName,
            version = sourceVersionName,
            versionCode = sourceVersionCode
        )
        val repatchSource = patchedRepatchSourcePath?.let { SharedApkFiles.resolve(File(it)) }
        return (sequenceOf(inputFile, repatchSource).filterNotNull() + retainedOriginals.asSequence())
            .map(SharedApkFiles::resolve)
            .distinctBy { it.absolutePath }
            .toList()
    }

    private fun verifiedStandaloneStockCandidates(
        sourceVersionName: String,
        sourceVersionCode: Long
    ): List<File> = stockSourceCandidates(sourceVersionName, sourceVersionCode)
        .filter { candidate ->
            if (
                !candidate.isFile ||
                fs.isManagedPatchedAppFile(candidate) ||
                SplitApkPreparer.isSplitArchive(candidate)
            ) {
                return@filter false
            }
            val info = pm.getPackageInfo(candidate, includeSigning = true)
                ?: return@filter false
            info.packageName == packageName &&
                info.versionName == sourceVersionName &&
                pm.getVersionCode(info) == sourceVersionCode &&
                info.sharedUserId == null &&
                packageInfoIsCompleteSingleApk(info) &&
                pm.getSignature(info) != null
        }

    private suspend fun verifiedSplitStockSource(
        installedInfo: PackageInfo?,
        sourceVersionName: String,
        sourceVersionCode: Long
    ): Pair<Boolean, File?> {
        val packageStateReader = AndroidPackageStateReader(
            pm = pm,
            shell = LibsuRootShellGateway(rootInstaller)
        )
        var hasVerifiedSource = false
        for (candidate in stockSourceCandidates(sourceVersionName, sourceVersionCode)) {
            if (
                !candidate.isFile ||
                fs.isManagedPatchedAppFile(candidate) ||
                !SplitApkPreparer.isSplitArchive(candidate)
            ) {
                continue
            }

            val workspace = File(app.cacheDir, "root-stock-check-${System.nanoTime()}")
            try {
                val verifiedSet = try {
                    val extracted = SplitApkPreparer.extractEntriesForProcessing(candidate, workspace)
                        .map { it.file }
                    verifiedStockSet(extracted.map(packageStateReader::inspect))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                } ?: continue
                val base = verifiedSet.singleOrNull() ?: continue
                val baseInfo = pm.getPackageInfo(File(base.path), includeSigning = true)
                    ?: continue
                val verified = base.packageName == packageName &&
                    base.versionName == sourceVersionName &&
                    base.versionCode == sourceVersionCode &&
                    baseInfo.sharedUserId == null &&
                    pm.getSignature(baseInfo) != null
                if (!verified) continue
                hasVerifiedSource = true
                if (installedSignerMatchesStockSource(installedInfo, baseInfo)) {
                    return true to candidate
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { workspace.deleteRecursively() }
            }
        }
        return hasVerifiedSource to null
    }

    private fun installedSignerMatchesStockSource(
        installedInfo: PackageInfo?,
        stockSource: File
    ): Boolean {
        val stockArchive = pm.getPackageInfo(stockSource, includeSigning = true)
            ?: return false
        return installedSignerMatchesStockSource(installedInfo, stockArchive)
    }

    private fun installedSignerMatchesStockSource(
        installedInfo: PackageInfo?,
        stockArchive: PackageInfo
    ): Boolean {
        val stockSigner = pm.getSignature(stockArchive)?.toByteArray()
            ?: return false
        if (installedInfo == null) return true
        val installedSigner = runCatching {
            pm.getSignature(installedInfo.packageName).toByteArray()
        }.getOrNull() ?: return false
        return installedSigner.contentEquals(stockSigner)
    }

    private fun installedPackageMatchesSourceVersion(
        installedInfo: PackageInfo?,
        sourceVersionName: String,
        sourceVersionCode: Long
    ): Boolean {
        val installed = installedInfo ?: return false
        return installed.versionName == sourceVersionName &&
            pm.getVersionCode(installed) == sourceVersionCode &&
            !installed.applicationInfo?.sourceDir.isNullOrBlank()
    }

    private suspend fun canSelectRootMountForPatchedOutput(): Boolean {
        val patched = pm.getPackageInfo(outputFile, includeSigning = true) ?: return false
        val patchedArtifact = runCatching {
            AndroidPackageStateReader(pm, LibsuRootShellGateway(rootInstaller)).inspect(outputFile)
        }.getOrNull() ?: return false
        val sourceVersionName = patchedSourceVersionName
            ?: input.selectedApp.version?.takeIf(String::isNotBlank)
            ?: return false
        val sourceVersionCode = patchedSourceVersionCode
            ?: input.selectedApp.versionCode
            ?: return false
        if (!patchSelectionSupportsRootMountModeOverride(sourceVersionName, sourceVersionCode)) {
            return false
        }
        val installedInfo = pm.getPackageInfo(packageName)
        val stockCandidates = verifiedStandaloneStockCandidates(sourceVersionName, sourceVersionCode)
        val stockSource = stockCandidates.firstOrNull {
            installedSignerMatchesStockSource(installedInfo, it)
        }
        val (hasSplitStockSource, compatibleSplitStockSource) = verifiedSplitStockSource(
            installedInfo = installedInfo,
            sourceVersionName = sourceVersionName,
            sourceVersionCode = sourceVersionCode
        )
        val splitStockIdentityCompatible = compatibleSplitStockSource != null
        val installedMatchesSourceVersion = installedPackageMatchesSourceVersion(
            installedInfo,
            sourceVersionName,
            sourceVersionCode
        )
        val installedHasSigningCertificate = installedInfo?.let { installed ->
            runCatching { pm.getSignature(installed.packageName) }.getOrNull() != null
        } == true
        val hasUsableStockIdentity = rootMountStockIdentityUsable(
            installedMatchesSourceVersion = installedMatchesSourceVersion,
            installedHasSigningCertificate = installedHasSigningCertificate,
            hasStockSource = stockCandidates.isNotEmpty() || hasSplitStockSource,
            stockIdentityCompatible = stockSource != null || splitStockIdentityCompatible
        )
        val installedHasSplitApks = installedInfo?.let(pm::hasSplitApks) == true
        val hasUsableSplitStockIdentity =
            installedHasSplitApks && installedMatchesSourceVersion && installedHasSigningCertificate ||
                !installedMatchesSourceVersion && splitStockIdentityCompatible
        return patchedOutputSupportsRootMount(
            patchedPackageName = patched.packageName,
            originalPackageName = packageName,
            patchedIsCompleteSingleApk = patchedArtifact.splitName == null && patchedArtifact.topology == "SINGLE",
            patchedIsSplitDependentBase = patchedArtifact.splitName == null && patchedArtifact.topology == "SPLIT",
            patchedHasSigningCertificate = !patchedArtifact.signerSha256.isNullOrBlank(),
            installedHasSharedUserId = installedInfo?.sharedUserId != null,
            hasUsableStockIdentity = hasUsableStockIdentity,
            hasUsableSplitStockIdentity = hasUsableSplitStockIdentity,
            patchedVersionMatchesSource = patched.versionName == sourceVersionName
        )
    }

    private fun refreshRootMountModeOverrideAsync() {
        rootMountModeOverrideRefreshJob?.cancel()
        rootMountModeOverrideRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            val supported = try {
                canSelectRootMountForPatchedOutput()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Failed to refresh Rooted Mount compatibility", error)
                false
            }
            withContext(Dispatchers.Main) {
                if (_patcherSucceeded.value == true) {
                    supportsRootMountModeOverride = supported
                }
            }
        }
    }

    var basePackageInstalled by mutableStateOf(pm.getPackageInfo(packageName) != null)
        private set

    var installedPackageName by savedStateHandle.saveable(
        key = "installedPackageName",
        // Force Kotlin to select the correct overload.
        stateSaver = autoSaver()
    ) {
        mutableStateOf<String?>(null)
    }
        private set
    private var ongoingPmSession: Boolean by savedStateHandle.saveableVar { false }
    var packageInstallerStatus: Int? by savedStateHandle.saveable(
        key = "packageInstallerStatus",
        stateSaver = autoSaver()
    ) {
        mutableStateOf(null)
    }
        private set

    var isInstalling by mutableStateOf(ongoingPmSession)
        private set
    private var autoInstallTriggered: Boolean by savedStateHandle.saveableVar { false }
    private var signatureWorkflowJob: Job? = null
    private val mutableSignatureWorkflowProgress = MutableStateFlow(SignatureMetadataWorkflowProgress())
    val signatureWorkflowProgress = mutableSignatureWorkflowProgress.asStateFlow()
    var signatureWorkflowRunning by mutableStateOf(false)
        private set
    val signatureInjectionEnabled get() = input.injectSignatureMetadata
    var signatureWorkflowCompleted by mutableStateOf(false)
        private set
    var installStatus by mutableStateOf<InstallCompletionStatus?>(null)
        private set
    var signatureMismatchPackage by mutableStateOf<String?>(null)
        private set
    var activeInstallType by mutableStateOf<InstallType?>(null)
        private set
    var lastInstallType by mutableStateOf<InstallType?>(null)
        private set

    private fun updateInstallingState(value: Boolean) {
        ongoingPmSession = value
        isInstalling = value
        if (!value) {
            externalInstallTimeoutJob?.cancel()
            externalInstallTimeoutJob = null
            externalInstallPresenceJob?.cancel()
            externalInstallPresenceJob = null
            externalInstallBaseline = null
            internalInstallBaseline = null
            stopInstallProgressToasts()
            activeInstallType = null
            suppressFailureAfterSuccess = false
            packageInstallerStatus = null
            expectedInstallSignature = null
            baselineInstallSignature = null
            pendingSignatureMismatchPlan = null
            pendingSignatureMismatchPackage = null
            signatureMismatchPackage = null
            stopUninstallProgressToasts()
            deferInstallProgressToasts = false
        } else {
            postTimeoutGraceJob?.cancel()
            postTimeoutGraceJob = null
            if (!deferInstallProgressToasts) {
                startInstallProgressToasts()
            }
            suppressFailureAfterSuccess = false
        }
    }

    private fun markInstallSuccess(packageName: String?) {
        if (installStatus is InstallCompletionStatus.Success) return
        installStatus = InstallCompletionStatus.Success(packageName)
        app.toast(app.getString(R.string.patched_app_install_success))
    }

    private fun handleUninstallFailure(message: String) {
        pendingSignatureMismatchPlan = null
        pendingSignatureMismatchPackage = null
        signatureMismatchPackage = null
        stopUninstallProgressToasts()
        showInstallFailure(message)
    }

    private var savedPatchedApp by savedStateHandle.saveableVar { false }
    val hasSavedPatchedApp get() = savedPatchedApp

    var exportMetadata by mutableStateOf<PatchedAppExportData?>(null)
        private set
    private val restoredRunConfiguration = restoreRunConfiguration()
    private var appliedSelection: PatchSelection = restoredRunConfiguration
        ?.selection()
        ?: input.selectedPatches.mapValues { (_, patches) -> patches.toSet() }
    private var appliedOptions: Options = restoredRunConfiguration
        ?.let(::restoreRunOptions)
        ?: input.options
    val currentSelectedApp: SelectedApp
        get() = when (val current = selectedApp) {
            // Keep the original selection when it is still usable. A worker's temporary
            // copy must not become the next run's input or trigger another metadata load.
            is SelectedApp.Local -> if (current.file.isFile) current else inputFile
                ?.takeIf { it.isFile }
                ?.let { current.copy(file = it) }
                ?: current
            else -> current
        }

    fun currentSelectionSnapshot(): PatchSelection =
        appliedSelection.mapValues { (_, patches) -> patches.toSet() }

    fun currentOptionsSnapshot(): Options =
        appliedOptions.mapValues { (_, bundleOptions) ->
            bundleOptions.mapValues { (_, patchOptions) -> patchOptions.toMap() }.toMap()
        }.toMap()

    private fun restoreRunConfiguration(): PatcherRunConfigurationSnapshot? {
        val serialized = savedStateHandle.get<String>(PATCHER_RUN_CONFIGURATION_KEY)
            ?.takeIf(String::isNotBlank)
            ?: return null
        return runCatching {
            json.decodeFromString<PatcherRunConfigurationSnapshot>(serialized)
        }.onFailure { error ->
            Log.w(TAG, "Failed to restore patch run configuration", error)
            savedStateHandle.remove<String>(PATCHER_RUN_CONFIGURATION_KEY)
        }.getOrNull()
    }

    private fun restoreRunOptions(snapshot: PatcherRunConfigurationSnapshot): Options =
        buildMap {
            snapshot.options.forEach { (bundleUid, bundleOptions) ->
                val restoredBundleOptions = buildMap {
                    bundleOptions.forEach { (patchName, patchOptions) ->
                        val restoredPatchOptions = buildMap<String, Any?> {
                            patchOptions.forEach { (key, storedValue) ->
                                try {
                                    put(key, storedValue.toValue())
                                } catch (error: Exception) {
                                    Log.w(
                                        TAG,
                                        "Failed to restore patch option $bundleUid:$patchName:$key",
                                        error
                                    )
                                }
                            }
                        }
                        if (restoredPatchOptions.isNotEmpty()) {
                            put(patchName, restoredPatchOptions)
                        }
                    }
                }
                if (restoredBundleOptions.isNotEmpty()) {
                    put(bundleUid, restoredBundleOptions)
                }
            }
        }

    private fun persistRunConfiguration(
        selection: PatchSelection,
        options: Options
    ) {
        runCatching {
            val storedOptions = options.mapValues { (_, bundleOptions) ->
                bundleOptions.mapValues { (_, patchOptions) ->
                    patchOptions.mapValues { (_, value) ->
                        PatcherRunOptionValue.fromValue(value)
                    }
                }
            }
            json.encodeToString(
                PatcherRunConfigurationSnapshot(
                    selectedPatches = selection.mapValues { (_, patches) -> patches.sorted() },
                    options = storedOptions
                )
            )
        }.onSuccess { serialized ->
            savedStateHandle[PATCHER_RUN_CONFIGURATION_KEY] = serialized
        }.onFailure { error ->
            Log.w(TAG, "Failed to persist patch run configuration", error)
            savedStateHandle.remove<String>(PATCHER_RUN_CONFIGURATION_KEY)
        }
    }

    val selectedPatchCount: Int
        get() = appliedSelection.values.sumOf { patches -> patches.size }

    val fallbackInputSizeBytes: Long?
        get() = (inputFile ?: (selectedApp as? SelectedApp.Local)?.file)
            ?.let(SharedApkFiles::resolve)
            ?.takeIf(File::isFile)
            ?.length()

    val fallbackInputIsSplitApk: Boolean?
        get() = when {
            requiresSplitPreparation -> true
            selectedApp is SelectedApp.Local -> false
            inputFile != null -> false
            else -> null
        }

    fun dismissMissingPatchWarning() {
        missingPatchWarning = null
    }

    fun proceedAfterMissingPatchWarning() {
        if (missingPatchWarning == null) return
        missingPatchWarning = null
        beginPrePatchFlow()
    }

    fun removeMissingPatchesAndStart() {
        if (missingPatchWarning == null) return
        launchPrePatchPreparation { chooseSplitApks ->
            val scopedBundles = gatherScopedBundles()
            val sanitizedSelection = applyCurrentPatchRules(
                filterSelectionToAvailablePatches(appliedSelection, scopedBundles),
                scopedBundles
            )
            if (sanitizedSelection.values.none { patches -> patches.isNotEmpty() }) {
                app.toast(app.getString(R.string.no_patches_selected))
                return@launchPrePatchPreparation
            }
            val sanitizedOptions = sanitizeOptions(appliedOptions, scopedBundles)
            appliedSelection = sanitizedSelection
            appliedOptions = sanitizedOptions
            refreshPatcherInformationMetadata(scopedBundles)
            missingPatchWarning = null
            prepareSplitSelectionOrStartWorker(chooseSplitApks)
        }
    }

    data class SplitSelectionDialogState(
        val inspection: SplitApkPreparer.SplitArchiveInspection,
        val initialModules: Set<String>,
        val initialStripNativeLibs: Boolean
    )

    data class PrePatchDownloadProgress(
        val downloadedBytes: Long,
        val totalBytes: Long?
    ) {
        val fraction: Float?
            get() = totalBytes
                ?.takeIf { it > 0L }
                ?.let { total ->
                    (downloadedBytes.toDouble() / total.toDouble())
                        .coerceIn(0.0, 1.0)
                        .toFloat()
                }
    }

    private var pendingSplitSelectionDialog: SplitSelectionDialogState? by mutableStateOf(null)
    val splitSelectionDialog by derivedStateOf { pendingSplitSelectionDialog }

    var isPreparingSplitSelection by mutableStateOf(false)
        private set
    var prePatchDownloadProgress by mutableStateOf<PrePatchDownloadProgress?>(null)
        private set
    var splitSelectionPreparationError by mutableStateOf<String?>(null)
        private set

    private var prePatchPreparationJob: Job? = null
    private var workerLaunchJob: Job? = null
    private var preparedInput: DownloadResult? = null
    private var preparedInputIncludesDownload = false
    private var selectedSplitConfiguration: PatcherWorker.SplitSelection? = null

    fun confirmSplitSelection(includedModules: Set<String>, stripNativeLibs: Boolean) {
        if (pendingSplitSelectionDialog == null) return
        selectedSplitConfiguration = PatcherWorker.SplitSelection(
            includedModules = includedModules,
            stripNativeLibs = stripNativeLibs
        )
        pendingSplitSelectionDialog = null
        startWorker()
    }

    fun cancelSplitSelectionPreparation() {
        prePatchPreparationJob?.cancel()
        prePatchPreparationJob = null
        isPreparingSplitSelection = false
        prePatchDownloadProgress = null
        pendingSplitSelectionDialog = null
        cleanupPreparedInput()
    }

    fun dismissSplitSelectionPreparationError() {
        splitSelectionPreparationError = null
    }

    private fun beginPrePatchFlow() {
        launchPrePatchPreparation(::prepareSplitSelectionOrStartWorker)
    }

    private fun launchPrePatchPreparation(
        block: suspend (chooseSplitApks: Boolean) -> Unit
    ) {
        prePatchPreparationJob?.cancel()
        prePatchPreparationJob = viewModelScope.launch {
            val preparationJob = currentCoroutineContext()[Job]
            splitSelectionPreparationError = null
            var cacheUseToken: AutoCloseable? = null
            try {
                // Idle migration may replace an old APK path with a shared-file reference.
                // Keep the file stable throughout inspection and split preparation.
                withContext(Dispatchers.IO) {
                    currentCoroutineContext().ensureActive()
                    cacheUseToken = CacheCleanupGuard.begin()
                }
                val chooseSplitApks = prefs.chooseSplitApksBeforePatching.get()
                if (chooseSplitApks) {
                    isPreparingSplitSelection = true
                    prePatchDownloadProgress = when (input.selectedApp) {
                        is SelectedApp.Download,
                        is SelectedApp.Search -> PrePatchDownloadProgress(0L, null)
                        else -> null
                    }
                }
                // Let Compose render the preparation state before cached repository flows and
                // patch-selection checks can resume synchronously on the main dispatcher.
                yield()
                block(chooseSplitApks)
            } catch (error: CancellationException) {
                cleanupPreparedInput()
                throw error
            } catch (error: Throwable) {
                cleanupPreparedInput()
                // Interrupted file IO can throw before coroutine cancellation is delivered.
                currentCoroutineContext().ensureActive()
                splitSelectionPreparationError =
                    error.simpleMessage() ?: error.javaClass.simpleName
            } finally {
                cacheUseToken?.close()
                if (prePatchPreparationJob === preparationJob) {
                    isPreparingSplitSelection = false
                    prePatchDownloadProgress = null
                    prePatchPreparationJob = null
                }
            }
        }
    }

    private suspend fun prepareSplitSelectionOrStartWorker(chooseSplitApks: Boolean) {
        if (!chooseSplitApks) {
            startWorker()
            return
        }

        val localInput = (input.selectedApp as? SelectedApp.Local)?.let {
            it.copy(file = SharedApkFiles.resolve(it.file))
        }
        val localSplitEntryNames = localInput?.let { selected ->
            withContext(Dispatchers.IO) {
                SplitApkPreparer.splitApkEntryNames(selected.file)
            }
        }
        if (localSplitEntryNames != null && localSplitEntryNames.size <= 1) {
            startWorker()
            return
        }

        val resolvedInput = localInput?.let { selected ->
            DownloadResult(selected.file, needsSplit = true)
        } ?: resolveInputBeforePatching()
        prePatchDownloadProgress = null
        preparedInput = resolvedInput
        preparedInputIncludesDownload =
            input.selectedApp is SelectedApp.Download || input.selectedApp is SelectedApp.Search
        inputFile = resolvedInput.file
        updateSplitStepRequirement(
            file = resolvedInput.file,
            needsSplitOverride = resolvedInput.needsSplit,
            merged = resolvedInput.merged
        )

        val resolvedSplitEntryNames = if (
            localInput != null &&
            resolvedInput.file.absoluteFile == localInput.file.absoluteFile
        ) {
            localSplitEntryNames.orEmpty()
        } else if (resolvedInput.needsSplit) {
            withContext(Dispatchers.IO) {
                SplitApkPreparer.splitApkEntryNames(resolvedInput.file)
            }
        } else {
            emptySet()
        }
        val hasSelectableSplits =
            resolvedInput.needsSplit && resolvedSplitEntryNames.size > 1
        if (!hasSelectableSplits) {
            startWorker()
            return
        }

        val inspection = withContext(Dispatchers.IO) {
            SplitApkPreparer.inspect(resolvedInput.file)
        }
        val initialStripNativeLibs = prefs.stripUnusedNativeLibs.get()
        val skipUnneededSplitApks = prefs.skipUnneededSplitApks.get()
        val allModules = inspection.modules.mapTo(linkedSetOf()) { it.name }
        var initialModules: Set<String> = allModules
        if (skipUnneededSplitApks) {
            initialModules = initialModules intersect inspection.languageTrimmedModules
            initialModules = initialModules intersect inspection.densityTrimmedModules
        }
        if (initialStripNativeLibs) {
            initialModules = initialModules intersect inspection.abiTrimmedModules
        }

        pendingSplitSelectionDialog = SplitSelectionDialogState(
            inspection = inspection,
            initialModules = initialModules,
            initialStripNativeLibs = initialStripNativeLibs
        )
    }

    private suspend fun resolveInputBeforePatching(): DownloadResult {
        suspend fun download(plugin: LoadedDownloaderPlugin, data: Parcelable): DownloadResult =
            downloadedAppRepository.download(
                plugin = plugin,
                data = data,
                expectedPackageName = packageName,
                expectedVersion = input.selectedApp.version,
                appCompatibilityCheck = prefs.suggestedVersionSafeguard.get(),
                patchesCompatibilityCheck = !prefs.disablePatchVersionCompatCheck.get(),
                onDownload = { (downloadedBytes, totalBytes) ->
                    prePatchDownloadProgress = PrePatchDownloadProgress(
                        downloadedBytes = downloadedBytes,
                        totalBytes = totalBytes
                    )
                },
                persistDownload = prefs.autoSaveDownloaderApks.get()
            )

        return when (val selected = input.selectedApp) {
            is SelectedApp.Download -> {
                val (plugin, data) = downloaderPluginRepository.unwrapParceledData(selected.data)
                download(plugin, data)
            }

            is SelectedApp.Search -> {
                var lastInteractionFailure: UserInteractionException? = null
                for (plugin in downloaderPluginRepository.loadedPluginsFlow.first()) {
                    val interactionFailure = AtomicReference<UserInteractionException?>(null)
                    try {
                        val scope = object : GetScope {
                            override val pluginPackageName = plugin.packageName
                            override val hostPackageName = app.packageName

                            override suspend fun requestStartActivity(intent: Intent): Intent? {
                                interactionFailure.get()?.let { throw it }
                                val result = try {
                                    handleDownloaderActivityRequest(plugin, intent)
                                } catch (error: UserInteractionException) {
                                    interactionFailure.compareAndSet(null, error)
                                    throw error
                                }
                                interactionFailure.get()?.let { throw it }
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
                            plugin.get(scope, selected.packageName, selected.version)
                        }?.takeIf { (_, version) ->
                            selected.version == null || version == null || version == selected.version
                        }
                        if (result != null) {
                            return download(plugin, result.first)
                        }
                    } catch (error: UserInteractionException.Activity.NotCompleted) {
                        throw error
                    } catch (error: UserInteractionException) {
                        lastInteractionFailure = error
                    }
                }
                throw (lastInteractionFailure ?: IllegalStateException("App is not available."))
            }

            is SelectedApp.Local -> {
                val file = SharedApkFiles.resolve(selected.file)
                val needsSplit = SplitApkPreparer.isSplitArchive(file)
                DownloadResult(file, needsSplit = needsSplit)
            }

            is SelectedApp.Installed -> prepareInstalledInputBeforePatching(selected.packageName)
        }
    }

    private suspend fun prepareInstalledInputBeforePatching(
        packageName: String
    ): DownloadResult = withContext(Dispatchers.IO) {
        val packageInfo = pm.getPackageInfo(packageName)
            ?: throw IllegalStateException("Installed package not found: $packageName")
        val appInfo = packageInfo.applicationInfo
            ?: throw IllegalStateException("ApplicationInfo missing for package: $packageName")
        val baseApk = File(
            appInfo.sourceDir
                ?: throw IllegalStateException("sourceDir missing for package: $packageName")
        )
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

        val archiveDir = fs.tempDir
            .resolve("prepatch-installed-splits-${System.currentTimeMillis()}")
            .apply { mkdirs() }
        val archiveFile = archiveDir.resolve("${packageName.replace('.', '_')}.apks")
        try {
            buildInstalledSplitArchive(listOf(baseApk) + splitApks, archiveFile)
            DownloadResult(
                file = archiveFile,
                needsSplit = true,
                cleanup = { archiveDir.deleteRecursively() }
            )
        } catch (error: Throwable) {
            archiveDir.deleteRecursively()
            throw error
        }
    }

    private fun buildInstalledSplitArchive(apkFiles: List<File>, output: File) {
        output.parentFile?.mkdirs()
        val usedNames = LinkedHashSet<String>()
        var writtenEntries = 0
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            apkFiles.forEachIndexed { index, apk ->
                if (!apk.exists()) return@forEachIndexed
                val normalized = apk.name.takeIf { it.endsWith(".apk", ignoreCase = true) }
                    ?: "${apk.name}.apk"
                var entryName = normalized
                var counter = 1
                while (!usedNames.add(entryName)) {
                    entryName = "${normalized.removeSuffix(".apk")}_${index}_${counter++}.apk"
                }
                zip.putNextEntry(ZipEntry(entryName).apply { time = apk.lastModified() })
                apk.inputStream().buffered().use { source -> source.copyTo(zip) }
                zip.closeEntry()
                writtenEntries++
            }
        }
        check(writtenEntries > 0) {
            "Failed to build installed split archive: no APK entries written."
        }
    }

    private fun cleanupPreparedInput() {
        preparedInput?.cleanup?.let { cleanup -> runCatching { cleanup() } }
        preparedInput = null
        preparedInputIncludesDownload = false
        selectedSplitConfiguration = null
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

    data class ActivityPromptDialogState(
        val title: String,
        val requestId: Long
    )

    private data class ActivityPromptRequest(
        val completion: CompletableDeferred<Boolean>,
        val dialogState: ActivityPromptDialogState
    )

    private var nextActivityPromptRequestId = 0L
    private var currentActivityRequest: ActivityPromptRequest? by mutableStateOf(null)
    val activityPromptDialog by derivedStateOf { currentActivityRequest?.dialogState }
    private val activityRequestMutex = Mutex()
    private val progressEventMutex = Mutex()
    private val persistPatchedAppMutex = Mutex()

    private var launchedActivity: CompletableDeferred<ActivityResult>? = null
    private var pendingActivityResumeFallback: Job? = null
    private val launchActivityChannel = Channel<Intent>()
    val launchActivityFlow = launchActivityChannel.receiveAsFlow()

    var installFailureMessage by mutableStateOf<String?>(null)
        private set
    var rootMountRecoveryMessage by mutableStateOf<String?>(null)
        private set
    var rootMountDiagnosticsFlowActive by mutableStateOf(
        savedStateHandle["root_mount_diagnostics_flow_active"]
            ?: (savedStateHandle.get<String>("root_mount_diagnostics_context") != null)
    )
        private set
    var rootMountDiagnosticsExportInProgress by mutableStateOf(false)
        private set
    private var rootMountDiagnosticsContext: String?
        get() = savedStateHandle["root_mount_diagnostics_context"]
        set(value) {
            if (value == null) {
                savedStateHandle.remove<String>("root_mount_diagnostics_context")
                updateRootMountDiagnosticsFlowActive(false)
            } else {
                savedStateHandle["root_mount_diagnostics_context"] = value
                updateRootMountDiagnosticsFlowActive(false)
            }
        }
    val hasRootMountDiagnostics get() = rootMountDiagnosticsContext != null

    private fun updateRootMountDiagnosticsFlowActive(active: Boolean) {
        rootMountDiagnosticsFlowActive = active
        if (active) {
            savedStateHandle["root_mount_diagnostics_flow_active"] = true
        } else {
            savedStateHandle.remove<Boolean>("root_mount_diagnostics_flow_active")
        }
    }
    var fallbackInstallPrompt by mutableStateOf<FallbackInstallPrompt?>(null)
        private set
    private var suppressFailureAfterSuccess = false
    private var lastSuccessInstallType: InstallType? = null
    private var lastSuccessAtMs: Long = 0L

    private fun tokensEqual(a: InstallerManager.Token, b: InstallerManager.Token): Boolean = when {
        a === b -> true
        a is InstallerManager.Token.Component && b is InstallerManager.Token.Component ->
            a.componentName == b.componentName
        else -> false
    }

    private fun recordInstallPlan(
        token: InstallerManager.Token,
        target: InstallerManager.InstallTarget,
        expectedPackage: String?,
        sourceLabel: String?
    ) {
        lastInstallToken = token
        lastInstallTarget = target
        lastInstallExpectedPackage = expectedPackage
        lastInstallSourceLabel = sourceLabel
    }

    private fun recordInstallPlan(
        plan: InstallerManager.InstallPlan,
        expectedPackage: String?,
        sourceLabel: String?
    ) {
        val token = when (plan) {
            is InstallerManager.InstallPlan.Internal -> InstallerManager.Token.Internal
            is InstallerManager.InstallPlan.RootPlayStore -> InstallerManager.Token.RootPlayStore
            is InstallerManager.InstallPlan.Mount -> if (plan.installAsPlayStore) {
                InstallerManager.Token.RootPlayStore
            } else {
                InstallerManager.Token.AutoSaved
            }
            is InstallerManager.InstallPlan.Shizuku -> plan.token
            is InstallerManager.InstallPlan.External -> plan.token
        }
        val target = when (plan) {
            is InstallerManager.InstallPlan.Internal -> plan.target
            is InstallerManager.InstallPlan.RootPlayStore -> plan.target
            is InstallerManager.InstallPlan.Mount -> plan.target
            is InstallerManager.InstallPlan.Shizuku -> plan.target
            is InstallerManager.InstallPlan.External -> plan.target
        }
        val resolvedPackage = expectedPackage
            ?: (plan as? InstallerManager.InstallPlan.External)?.expectedPackage
            ?: lastInstallExpectedPackage
            ?: packageName
        recordInstallPlan(token, target, resolvedPackage, sourceLabel)
    }

    private fun buildFallbackPrompt(message: String): FallbackInstallPrompt? {
        if (prefs.chooseInstallerPerInstall.getBlocking()) return null
        val target = lastInstallTarget ?: return null
        val lastToken = lastInstallToken ?: return null
        val primaryToken = installerManager.getPrimaryToken()
        if (!tokensEqual(primaryToken, lastToken)) return null
        val fallbackToken = installerManager.getFallbackToken()
        if (fallbackToken == InstallerManager.Token.None) return null
        if (!isInstallerTokenAllowed(fallbackToken)) return null
        if (tokensEqual(primaryToken, fallbackToken)) return null
        val fallbackEntry = installerManager.describeEntry(fallbackToken, target) ?: return null
        if (!fallbackEntry.availability.available) return null
        val expectedPackage = lastInstallExpectedPackage ?: packageName
        if (installerManager.baseInstallerToken(fallbackToken) ==
            InstallerManager.Token.AutoSaved && expectedPackage != packageName
        ) {
            return null
        }
        val plan = installerManager.resolvePlanForToken(
            token = fallbackToken,
            target = target,
            sourceFile = outputFile,
            expectedPackage = expectedPackage,
            sourceLabel = lastInstallSourceLabel,
            allowMount = usingMountInstall && expectedPackage == packageName
        ) ?: return null
        if (plan is InstallerManager.InstallPlan.Internal && fallbackToken is InstallerManager.Token.Component) {
            return null
        }
        return FallbackInstallPrompt(
            failureMessage = message,
            fallbackLabel = fallbackEntry.label,
            fallbackToken = fallbackToken,
            target = target
        )
    }

    private fun cleanupFailedInstall() {
        updateInstallingState(false)
        stopInstallProgressToasts()
        pendingExternalInstall?.let(installerManager::cleanup)
        pendingExternalInstall = null
        restorePendingExternalMountAsync()
        externalInstallBaseline = null
        externalInstallStartTime = null
        externalPackageWasPresentAtStart = false
        expectedInstallSignature = null
        baselineInstallSignature = null
        packageInstallerStatus = null
    }

    private suspend fun restorePendingExternalMount() {
        val restoreJob = pendingExternalMountRestoreJob
        if (restoreJob != null) {
            restoreJob.join()
            if (pendingExternalMountRestoreJob === restoreJob) {
                pendingExternalMountRestoreJob = null
            }
        }

        val suspension = pendingExternalMountSuspension ?: return
        if (pendingExternalMountRestored) {
            pendingExternalMountSuspension = null
            pendingExternalMountRestored = false
            return
        }
        suspension.restore()
        if (pendingExternalMountSuspension === suspension) {
            pendingExternalMountSuspension = null
            pendingExternalMountRestored = false
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun restorePendingExternalMountAsync() {
        val suspension = pendingExternalMountSuspension ?: return
        if (pendingExternalMountRestored) return
        if (pendingExternalMountRestoreJob?.isActive == true) return

        pendingExternalMountRestoreJob = GlobalScope.launch(Dispatchers.Main) {
            runCatching { suspension.restore() }
                .onSuccess {
                    if (pendingExternalMountSuspension === suspension) {
                        pendingExternalMountRestored = true
                    }
                }
                .onFailure { error ->
                    Log.e(TAG, "Failed to restore root mount after external install", error)
                }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun retirePendingExternalMount(): Deferred<Throwable?>? {
        val suspension = pendingExternalMountSuspension ?: return null
        val restoreJob = pendingExternalMountRestoreJob
        pendingExternalMountSuspension = null
        pendingExternalMountRestoreJob = null
        pendingExternalMountRestored = false

        return GlobalScope.async(Dispatchers.Main) {
            restoreJob?.join()
            try {
                suspension.retire()
                null
            } catch (error: Throwable) {
                val restoreError = withContext(NonCancellable) {
                    runCatching { suspension.restore() }.exceptionOrNull()
                }
                restoreError?.let(error::addSuppressed)
                if (restoreError != null && pendingExternalMountSuspension == null) {
                    pendingExternalMountSuspension = suspension
                }
                Log.e(TAG, "Failed to retire root mount after external install", error)
                error
            }
        }
    }

    private suspend fun awaitExternalMountRetirement(
        retirement: Deferred<Throwable?>?
    ): Boolean {
        val error = retirement?.await() ?: return true
        suppressFailureAfterSuccess = false
        showInstallFailure(
            app.getString(
                R.string.failed_to_unmount,
                error.simpleMessage() ?: error.javaClass.simpleName.orEmpty()
            ),
            allowFallback = false,
            attemptedInstallType = InstallType.MOUNT
        )
        return false
    }

    private fun applyInstallFailure(message: String) {
        rootMountRecoveryMessage = null
        installFailureMessage = message
        installStatus = InstallCompletionStatus.Failure(message)
        cleanupFailedInstall()
    }

    private fun showRootMountRecovery(message: String) {
        fallbackInstallPrompt = null
        pendingInstallFailureMessage = null
        installFailureMessage = null
        installStatus = null
        rootMountDiagnosticsContext = message
        rootMountRecoveryMessage = message
        cleanupFailedInstall()
    }

    private fun showInstallFailure(
        message: String,
        allowFallback: Boolean = true,
        attemptedInstallType: InstallType? = null
    ) {
        val failureInstallType = attemptedInstallType ?: activeInstallType
        if (attemptedInstallType == null) {
            val now = System.currentTimeMillis()
            if (failureInstallType.isShizukuInstall() && suppressFailureAfterSuccess) return
            if (lastSuccessInstallType.isShizukuInstall() && now - lastSuccessAtMs < SUPPRESS_FAILURE_AFTER_SUCCESS_MS) return
            if (lastSuccessInstallType.isShizukuInstall()) return
            if (installStatus is InstallCompletionStatus.Success || suppressFailureAfterSuccess) return
        }
        val adjusted = if (failureInstallType == InstallType.MOUNT) {
            message
                .replace("Failed to install app:", "Failed to mount app:", ignoreCase = true)
                .replace("for install", "for mount", ignoreCase = true)
        } else message
        if (failureInstallType == InstallType.MOUNT) {
            rootMountDiagnosticsContext = adjusted
        }
        if (failureInstallType != null) {
            lastInstallType = failureInstallType
        }
        val fallbackPrompt = if (allowFallback) buildFallbackPrompt(adjusted) else null
        if (fallbackPrompt != null) {
            pendingInstallFailureMessage = adjusted
            installFailureMessage = null
            installStatus = null
            fallbackInstallPrompt = fallbackPrompt
            cleanupFailedInstall()
            return
        }
        applyInstallFailure(adjusted)
    }

    private fun showSignatureMismatchPrompt(
        packageName: String,
        plan: InstallerManager.InstallPlan
    ) {
        stopInstallProgressToasts()
        if (isInstalling || installStatus != null) {
            updateInstallingState(false)
        } else {
            installStatus = null
            packageInstallerStatus = null
            installFailureMessage = null
        }
        pendingSignatureMismatchPlan = plan
        pendingSignatureMismatchPackage = packageName
        signatureMismatchPackage = packageName
    }

    private fun scheduleInstallTimeout(
        packageName: String,
        durationMs: Long = SYSTEM_INSTALL_TIMEOUT_MS,
        timeoutMessage: (() -> String)? = null
    ) {
        externalInstallTimeoutJob?.cancel()
        externalInstallTimeoutJob = viewModelScope.launch {
            delay(durationMs)
            if (installStatus is InstallCompletionStatus.InProgress) {
                logger.trace("install timeout for $packageName")
                val baselineSnapshot = internalInstallBaseline ?: externalInstallBaseline
                val startTimeSnapshot = externalInstallStartTime
                val expectedSignatureSnapshot = expectedInstallSignature
                val baselineSignatureSnapshot = baselineInstallSignature
                val packageWasPresentAtStartSnapshot = externalPackageWasPresentAtStart
                val installTypeSnapshot = pendingExternalInstall
                    ?.takeIf { it.expectedPackage == packageName }
                    ?.let { plan -> installTypeForExternalToken(plan.token) }
                    ?: activeInstallType
                    ?: InstallType.DEFAULT
                val customInstallerPackageNameSnapshot = pendingExternalInstall
                    ?.takeIf { it.expectedPackage == packageName }
                    ?.token
                    ?.let { it as? InstallerManager.Token.Component }
                    ?.componentName
                    ?.packageName

                packageInstallerStatus = null
                if (!tryMarkInstallIfPresent(packageName)) {
                    val message = timeoutMessage?.invoke() ?: app.getString(R.string.install_timeout_message)
                    showInstallFailure(message)
                    startPostTimeoutGraceWatch(
                        packageName = packageName,
                        installType = installTypeSnapshot,
                        baseline = baselineSnapshot,
                        startTimeMs = startTimeSnapshot,
                        expectedSignature = expectedSignatureSnapshot,
                        baselineSignature = baselineSignatureSnapshot,
                        packageWasPresentAtStart = packageWasPresentAtStartSnapshot,
                        customInstallerPackageName = customInstallerPackageNameSnapshot
                    )
                }
            }
        }
    }

    private fun startPostTimeoutGraceWatch(
        packageName: String,
        installType: InstallType,
        baseline: Pair<Long?, Long?>?,
        startTimeMs: Long?,
        expectedSignature: ByteArray?,
        baselineSignature: ByteArray?,
        packageWasPresentAtStart: Boolean,
        customInstallerPackageName: String?
    ) {
        postTimeoutGraceJob?.cancel()
        postTimeoutGraceJob = viewModelScope.launch {
            val deadline = System.currentTimeMillis() + POST_TIMEOUT_GRACE_MS
            while (isActive && System.currentTimeMillis() < deadline) {
                val info = pm.getPackageInfo(packageName)
                if (info != null) {
                    val updated = isUpdatedSinceBaseline(info, baseline, startTimeMs)
                    val signatureChangedToExpected = if (expectedSignature != null) {
                        val current = readInstalledSignatureBytes(packageName)
                        current != null &&
                            current.contentEquals(expectedSignature) &&
                            (!packageWasPresentAtStart || baselineSignature != null) &&
                            (baselineSignature == null || !baselineSignature.contentEquals(current))
                    } else {
                        false
                    }

                    if (updated || signatureChangedToExpected) {
                        forceMarkInstallSuccess(
                            packageName,
                            installType,
                            customInstallerPackageName
                        )
                        return@launch
                    }
                }
                delay(INSTALL_MONITOR_POLL_MS)
            }
        }
    }

    private fun monitorExternalInstall(plan: InstallerManager.InstallPlan.External) {
        externalInstallTimeoutJob?.cancel()
        externalInstallTimeoutJob = viewModelScope.launch {
            val timeoutAt = System.currentTimeMillis() + EXTERNAL_INSTALL_TIMEOUT_MS
            while (isActive) {
                if (pendingExternalInstall != plan) return@launch

                val currentInfo = pm.getPackageInfo(plan.expectedPackage)
                if (currentInfo != null) {
                    if (tryHandleExternalInstallSuccess(plan, currentInfo)) {
                        return@launch
                    }
                }

                val remaining = timeoutAt - System.currentTimeMillis()
                if (remaining <= 0L) break
                delay(INSTALL_MONITOR_POLL_MS)
            }

            if (pendingExternalInstall == plan && installStatus is InstallCompletionStatus.InProgress) {
                val info = pm.getPackageInfo(plan.expectedPackage)
                if (info != null && tryHandleExternalInstallSuccess(plan, info)) return@launch
                showInstallFailure(app.getString(R.string.installer_external_timeout, plan.installerLabel))
            }
        }
        startExternalPresenceWatch(plan.expectedPackage)
    }

    private fun isUpdatedSinceBaseline(
        info: PackageInfo,
        baseline: Pair<Long?, Long?>?,
        startTime: Long?
    ): Boolean {
        val vc = pm.getVersionCode(info)
        val updated = info.lastUpdateTime
        val baseVc = baseline?.first
        val baseUpdated = baseline?.second
        val versionChanged = baseVc != null && vc != baseVc
        val timestampChanged = baseUpdated != null && updated > baseUpdated
        val started = startTime ?: 0L
        val updatedSinceStart = updated >= started && started > 0L
        return baseline == null || versionChanged || timestampChanged || updatedSinceStart
    }

    private fun forceMarkInstallSuccess(
        packageName: String,
        installType: InstallType = InstallType.DEFAULT,
        customInstallerPackageName: String? = pendingExternalInstall
            ?.takeIf { it.expectedPackage == packageName }
            ?.token
            ?.let { it as? InstallerManager.Token.Component }
            ?.componentName
            ?.packageName
    ) {
        if (installStatus is InstallCompletionStatus.Success) return
        suppressFailureAfterSuccess = true
        postTimeoutGraceJob?.cancel()
        postTimeoutGraceJob = null
        val mountRetirement = retirePendingExternalMount()
        pendingExternalInstall?.let(installerManager::cleanup)
        pendingExternalInstall = null
        externalInstallTimeoutJob?.cancel()
        externalInstallTimeoutJob = null
        externalInstallBaseline = null
        externalInstallStartTime = null
        externalPackageWasPresentAtStart = false
        expectedInstallSignature = null
        baselineInstallSignature = null
        internalInstallBaseline = null
        installFailureMessage = null
        packageInstallerStatus = null
        updateInstallingState(false)
        stopInstallProgressToasts()
        viewModelScope.launch {
            if (!awaitExternalMountRetirement(mountRetirement)) return@launch
            installedPackageName = packageName
            markInstallSuccess(packageName)
            lastSuccessInstallType = installType
            lastSuccessAtMs = System.currentTimeMillis()
            val persisted = persistPatchedApp(
                packageName,
                installType,
                customInstallerPackageName = customInstallerPackageName
            )
            if (!persisted) {
                Log.w(TAG, "Failed to persist installed patched app metadata (detected)")
            }
        }
    }

    private fun handleDetectedInstall(packageName: String): Boolean {
        val info = pm.getPackageInfo(packageName) ?: return false
        val externalPlan = pendingExternalInstall?.takeIf { it.expectedPackage == packageName }
        val updated =
            if (externalPlan != null) {
                isUpdatedSinceExternalBaseline(info, externalInstallBaseline, externalInstallStartTime)
            } else {
                val baseline = internalInstallBaseline ?: externalInstallBaseline
                isUpdatedSinceBaseline(info, baseline, externalInstallStartTime)
            }
        val signatureChangedToExpected =
            if (externalPlan != null) {
                shouldTreatAsInstalledBySignature(packageName, externalPackageWasPresentAtStart)
            } else {
                false
            }
        if (!updated && !signatureChangedToExpected) return false

        val installType = pendingExternalInstall
            ?.takeIf { it.expectedPackage == packageName }
            ?.let { plan -> installTypeForExternalToken(plan.token) }
            ?: activeInstallType
            ?: InstallType.DEFAULT

        val customInstallerPackageName = externalPlan
            ?.token
            ?.let { it as? InstallerManager.Token.Component }
            ?.componentName
            ?.packageName
        forceMarkInstallSuccess(packageName, installType, customInstallerPackageName)
        return true
    }

    private fun startExternalPresenceWatch(packageName: String) {
        externalInstallPresenceJob?.cancel()
        externalInstallPresenceJob = viewModelScope.launch {
            while (isActive) {
                val plan = pendingExternalInstall ?: return@launch
                if (plan.expectedPackage != packageName) return@launch

                val info = pm.getPackageInfo(packageName)
                if (info != null) {
                    if (tryHandleExternalInstallSuccess(plan, info)) {
                        return@launch
                    }
                }
                delay(INSTALL_MONITOR_POLL_MS)
            }
        }
    }

    private fun shouldTreatAsInstalledBySignature(packageName: String, packageWasPresentAtStart: Boolean): Boolean {
        val expected = expectedInstallSignature ?: return false
        val current = readInstalledSignatureBytes(packageName) ?: return false
        if (!current.contentEquals(expected)) return false
        val baseline = baselineInstallSignature
        if (packageWasPresentAtStart && baseline == null) return false
        return baseline == null || !baseline.contentEquals(current)
    }

    private fun readInstalledSignatureBytes(packageName: String): ByteArray? = runCatching {
        pm.getSignature(packageName).toByteArray()
    }.getOrNull()

    private fun readArchiveSignatureBytes(file: File): ByteArray? = runCatching {
        val pkgInfo = pm.getPackageInfo(file, includeSigning = true) ?: return null

        val signature: Signature? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo.signingInfo?.apkContentsSigners?.firstOrNull()
                    ?: pkgInfo.signatures?.firstOrNull()
            } else {
                pkgInfo.signatures?.firstOrNull()
            }

        signature?.toByteArray()
    }.getOrNull()

    private fun hasSignatureMismatch(packageName: String, file: File): Boolean {
        if (!installerManager.apkSignatureChecksEnabled) return false
        val installed = readInstalledSignatureBytes(packageName) ?: return false
        val expected = readArchiveSignatureBytes(file) ?: return false
        return !installed.contentEquals(expected)
    }
    private fun tryMarkInstallIfPresent(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val externalPlan = pendingExternalInstall?.takeIf { it.expectedPackage == packageName }
        val info = if (externalPlan != null) pm.getPackageInfo(packageName) else null
        if (externalPlan != null && info != null) {
            return tryHandleExternalInstallSuccess(externalPlan, info)
        }
        return handleDetectedInstall(packageName)
    }

    private fun isUpdatedSinceExternalBaseline(
        info: PackageInfo,
        baseline: Pair<Long?, Long?>?,
        startTime: Long?
    ): Boolean {
        val vc = pm.getVersionCode(info)
        val updated = info.lastUpdateTime
        val baseVc = baseline?.first
        val baseUpdated = baseline?.second
        val versionChanged = baseVc != null && vc != baseVc
        val timestampChanged = baseUpdated != null && updated > baseUpdated
        val started = startTime ?: 0L
        val updatedSinceStart = updated >= started && started > 0L
        return versionChanged || timestampChanged || updatedSinceStart
    }

    private fun tryHandleExternalInstallSuccess(
        plan: InstallerManager.InstallPlan.External,
        info: PackageInfo
    ): Boolean {
        if (pendingExternalInstall != plan) return false
        val updatedSinceStart = isUpdatedSinceExternalBaseline(info, externalInstallBaseline, externalInstallStartTime)
        val signatureChangedToExpected =
            shouldTreatAsInstalledBySignature(plan.expectedPackage, externalPackageWasPresentAtStart)
        if (updatedSinceStart || signatureChangedToExpected) {
            handleExternalInstallSuccess(plan.expectedPackage)
            return true
        }
        return false
    }

    private fun startInstallProgressToasts() {
        if (installProgressToastJob?.isActive == true) return
        installProgressToastJob = viewModelScope.launch {
            while (isActive) {
                val messageRes =
                    if (activeInstallType == InstallType.MOUNT) R.string.mounting_ellipsis
                    else R.string.installing_patched_app
                installProgressToast?.cancel()
                installProgressToast = app.toastHandle(app.getString(messageRes))
                delay(INSTALL_PROGRESS_TOAST_INTERVAL_MS)
            }
        }
    }

    private fun enableInstallProgressToasts() {
        if (!deferInstallProgressToasts) return
        deferInstallProgressToasts = false
        if (isInstalling) {
            startInstallProgressToasts()
        }
    }

    private fun stopInstallProgressToasts() {
        installProgressToastJob?.cancel()
        installProgressToastJob = null
        installProgressToast?.cancel()
        installProgressToast = null
    }

    private fun startUninstallProgressToasts() {
        if (deferUninstallProgressToasts) return
        if (uninstallProgressToastJob?.isActive == true) return
        uninstallProgressToastJob = viewModelScope.launch {
            while (isActive) {
                uninstallProgressToast?.cancel()
                uninstallProgressToast = app.toastHandle(app.getString(R.string.uninstalling_ellipsis))
                delay(INSTALL_PROGRESS_TOAST_INTERVAL_MS)
            }
        }
    }

    private fun stopUninstallProgressToasts() {
        uninstallProgressToastJob?.cancel()
        uninstallProgressToastJob = null
        uninstallProgressToast?.cancel()
        uninstallProgressToast = null
        deferUninstallProgressToasts = false
    }

    private fun enableUninstallProgressToasts() {
        if (!deferUninstallProgressToasts) return
        deferUninstallProgressToasts = false
        startUninstallProgressToasts()
    }

    private fun launchUninstallConfirmationToast(session: Session<*>): Job =
        viewModelScope.launch {
            if (session.awaitUserConfirmation()) {
                enableUninstallProgressToasts()
            }
        }

    fun suppressInstallProgressToasts() = stopInstallProgressToasts()

    private val tempDir = savedStateHandle.saveable(key = "tempDir") {
        fs.uiTempDir.resolve("installer-${UUID.randomUUID()}").also { it.mkdirs() }
    }

    private var inputFile: File? by savedStateHandle.saveableVar()
    private var requiresSplitPreparation by savedStateHandle.saveableVar {
        false
    }
    private val outputFile = tempDir.resolve("output.apk")
    private val signatureMetadataSource = tempDir.resolve("signature-source.zip")

    private val logs by savedStateHandle.saveable<MutableList<Pair<LogLevel, String>>> { mutableListOf() }

    private fun restoredPatcherSessionInfo(): PatcherSessionInfo {
        savedStateHandle.get<String>(PATCHER_SESSION_INFO_KEY)
            ?.takeIf(String::isNotBlank)
            ?.let { serialized ->
                runCatching {
                    json.decodeFromString<PatcherSessionInfo>(serialized)
                }.getOrNull()?.let { return it }
                savedStateHandle.remove<String>(PATCHER_SESSION_INFO_KEY)
            }
        return parsePatcherSessionInfo(logs.map { (_, message) -> message })
    }

    var patcherSessionInfo by mutableStateOf(restoredPatcherSessionInfo())
        private set

    private fun updatePatcherSessionInfo(value: PatcherSessionInfo) {
        val changed = value != patcherSessionInfo
        if (changed) patcherSessionInfo = value
        if (changed || savedStateHandle.get<String>(PATCHER_SESSION_INFO_KEY).isNullOrBlank()) {
            savedStateHandle[PATCHER_SESSION_INFO_KEY] = json.encodeToString(value)
        }
    }
    var selectedPatchBundleLabels by mutableStateOf<List<String>>(emptyList())
        private set
    var fallbackPatcherEngine by mutableStateOf<String?>(null)
        private set
    private var droppedLogLineCount by savedStateHandle.saveableVar { 0 }
    private var runtimeReportedMemoryLimitMb: Int? by savedStateHandle.saveableVar()
    private var lastPatchFailure: RemoteError? by savedStateHandle.saveableVar()
    private var lastPatchFailureStep: String? by savedStateHandle.saveableVar()
    private fun parseMemoryLimitMb(raw: String?): Int? {
        val value = raw?.trim() ?: return null
        val match = Regex("""(\d+)\s*(?:m|mb|mib)?""", RegexOption.IGNORE_CASE)
            .find(value)
            ?: return null

        return match.groupValues.getOrNull(1)?.toIntOrNull()
    }

    private fun appendBoundedLog(level: LogLevel, message: String) {
        val boundedMessage = if (message.length > PATCHER_LOG_MESSAGE_CHAR_LIMIT) {
            buildString(PATCHER_LOG_MESSAGE_CHAR_LIMIT + 96) {
                append(message.take(PATCHER_LOG_MESSAGE_CHAR_LIMIT))
                append("\n[log message truncated to ")
                append(PATCHER_LOG_MESSAGE_CHAR_LIMIT)
                append(" characters]")
            }
        } else {
            message
        }

        if (logs.size >= PATCHER_LOG_ENTRY_HARD_LIMIT) {
            val trimCount = (logs.size - PATCHER_LOG_ENTRY_SOFT_LIMIT + 1).coerceAtLeast(1)
            val safeTrimCount = trimCount.coerceAtMost(logs.size)
            logs.subList(0, safeTrimCount).clear()
            droppedLogLineCount += safeTrimCount
        }

        logs.add(level to boundedMessage)
    }

    private val logger = object : Logger() {
        override fun log(level: LogLevel, message: String) {
            level.androidLog(message)
            if (level == LogLevel.TRACE) return
            if (message.startsWith("Memory limit:")) {
                parseMemoryLimitMb(
                    message.removePrefix("Memory limit:").trim()
                )?.let { runtimeReportedMemoryLimitMb = it }
            }

            viewModelScope.launch {
                updatePatcherSessionInfo(patcherSessionInfo.updatedFromLog(message))
                if (!isVerbosePatcherExportLog(level, message)) {
                    appendBoundedLog(level, message)
                }
                if (_isPatchingActive.value != true) {
                    progressState.handleDexCompileLine(message)
                }
            }
        }
    }

    data class MissingPatchWarningState(
        val patchNames: List<String>
    )
var missingPatchWarning by mutableStateOf<MissingPatchWarningState?>(null)
    private set

    private suspend fun gatherScopedBundles(): Map<Int, PatchBundleInfo.Scoped> {
        patchBundleRepository.awaitReady()
        return patchBundleRepository.scopedBundleInfoFlow(
            packageName,
            input.selectedApp.version,
            input.selectedApp.versionCode
        ).first().associateBy { it.uid }
    }

    private suspend fun refreshPatcherInformationMetadata(
        scopedBundles: Map<Int, PatchBundleInfo.Scoped>
    ) {
        val activeSelection = appliedSelection.filterValues { patches -> patches.isNotEmpty() }
        selectedPatchBundleLabels = activeSelection.keys.mapNotNull { uid ->
            scopedBundles[uid]?.let { bundle ->
                bundle.version
                    ?.takeIf(String::isNotBlank)
                    ?.let { version -> "${bundle.name} $version" }
                    ?: bundle.name.takeIf(String::isNotBlank)
            }
        }
        val bundleType = patchBundleRepository.selectionBundleType(activeSelection)
        if (
            bundleType == PatchBundleType.REVANCED &&
            patchBundleRepository.selectionHasMixedRevancedPatcherVersions(activeSelection)
        ) {
            fallbackPatcherEngine = null
            return
        }
        val usesRevancedPatcher22 = bundleType == PatchBundleType.REVANCED &&
            patchBundleRepository.selectionUsesRevancedPatcher22(activeSelection)
        fallbackPatcherEngine = patcherEngineDisplayName(
            bundleType,
            usesRevancedPatcher22
        )
    }

    private suspend fun collectSelectedBundleMetadata(): List<PatchBundleExportData> {
        val globalBundles = patchBundleRepository.bundleInfoFlow.first()
        val scopedBundles = gatherScopedBundles()
        val sanitizedSelection = sanitizeSelection(appliedSelection, scopedBundles)
        val displayNames = patchBundleRepository.sources.first().associate { it.uid to it.displayTitle }
        return sanitizedSelection.keys.mapNotNull { uid ->
            val scoped = scopedBundles[uid]
            val global = globalBundles[uid]
            val displayName = displayNames[uid]?.takeIf { it.isNotBlank() }
                ?: scoped?.name?.takeIf { it.isNotBlank() }
                ?: global?.name?.takeIf { it.isNotBlank() }
            val version = global?.version?.takeIf { it.isNotBlank() }
                ?: scoped?.version?.takeIf { it.isNotBlank() }
            if (displayName == null && version == null) {
                null
            } else {
                PatchBundleExportData(name = displayName, version = version)
            }
        }
    }

    private suspend fun collectSelectedPatchDescriptions(
        selection: PatchSelection = appliedSelection
    ): List<String> {
        val globalBundles = patchBundleRepository.bundleInfoFlow.first()
        val displayNames = patchBundleRepository.sources.first().associate { it.uid to it.displayTitle }
        return selection.entries.flatMap { (uid, patchNames) ->
            val bundleName = displayNames[uid]
                ?: globalBundles[uid]?.name
                ?: "Unknown bundle ($uid)"
            val metadata = globalBundles[uid]?.patches?.associateBy { it.name }.orEmpty()
            patchNames.sorted().map { patchName ->
                "${metadata[patchName]?.displayName ?: patchName} - $bundleName"
            }
        }
    }

    private fun resolveDeviceName(): String {
        val marketName = sequenceOf(
            "ro.product.marketname",
            "ro.product.odm.marketname",
            "ro.product.vendor.marketname",
            "ro.config.marketing_name",
            "ro.vendor.product.display"
        ).mapNotNull(::readSystemProperty)
            .firstOrNull()
        return marketName ?: formatDeviceName(Build.MANUFACTURER, Build.MODEL)
    }

    private fun readSystemProperty(key: String): String? = runCatching {
        val systemPropertiesClass = Class.forName("android.os.SystemProperties")
        val getMethod = systemPropertiesClass.getMethod("get", String::class.java)
        (getMethod.invoke(null, key) as? String)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun formatDeviceName(manufacturer: String?, model: String?): String {
        val manufacturerValue = manufacturer?.trim().orEmpty()
        val modelValue = model?.trim().orEmpty()
        if (manufacturerValue.isEmpty() && modelValue.isEmpty()) return "unknown"
        if (manufacturerValue.isEmpty()) return modelValue
        if (modelValue.isEmpty()) return manufacturerValue
        return if (modelValue.startsWith(manufacturerValue, ignoreCase = true)) {
            modelValue
        } else {
            "${manufacturerValue.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }} $modelValue"
        }
    }

    private suspend fun buildExportMetadata(packageInfo: PackageInfo?): PatchedAppExportData? {
        val info = packageInfo ?: pm.getPackageInfo(outputFile) ?: return null
        val patchBundles = collectSelectedBundleMetadata()
        val label = runCatching { with(pm) { info.label() } }.getOrNull()
        val versionName = info.versionName?.takeUnless { it.isBlank() } ?: version ?: "unspecified"
        return PatchedAppExportData(
            appName = label,
            packageName = info.packageName,
            appVersion = versionName,
            patchBundles = patchBundles,
            fileExtension = if (SplitApkPreparer.isSplitArchive(outputFile)) "apks" else "apk"
        )
    }

    private fun refreshExportMetadata() {
        viewModelScope.launch(Dispatchers.IO) {
            val metadata = buildExportMetadata(null)
            withContext(Dispatchers.Main) {
                exportMetadata = metadata
            }
        }
    }

    private suspend fun ensureExportMetadata() {
        if (exportMetadata != null) return
        val metadata = buildExportMetadata(null) ?: return
        withContext(Dispatchers.Main) {
            exportMetadata = metadata
        }
    }
        val steps by savedStateHandle.saveable(saver = snapshotStateListSaver()) {
            generateSteps(
                app,
                input.selectedApp,
                appliedSelection,
                requiresSplitPreparation,
                skipApkSigning,
                input.injectSignatureMetadata,
                preserveSplitApks = isReseamSelection()
            ).toMutableStateList()
        }
    val stepSubSteps = mutableStateMapOf<StepId, SnapshotStateList<StepDetail>>()

    private val progressState by lazy {
        PatcherProgressTracker(
            steps = steps,
            stepSubSteps = stepSubSteps,
            isMorpheSelection = ::isMorpheSelection,
            morpheBytecodeMode = { patcherSessionInfo.morpheBytecodeMode ?: selectionMorpheBytecodeMode },
            dispatch = { action -> viewModelScope.launch { action() } },
            onFailure = ::handleVisualFailure,
            onRetry = { resetFailureLogState(); runtimeReportedMemoryLimitMb = null },
            onSplitStepRequired = { requiresSplitPreparation = true; addSplitStep() },
            initialFailure = lastPatchFailure,
            initialFailureStep = lastPatchFailureStep
        )
    }
    val progress get() = progressState.progress
    val patcherMemoryUsageSamples = mutableStateListOf<PatcherMemoryUsage>()

    // Code adapted from Morphe, see third-party/NOTICE for more information
    // https://github.com/MorpheApp/morphe-manager/blob/a2c3d31bd7ab42e6bc4b9dd528ed856fc72fb948/app/src/main/java/app/morphe/manager/ui/viewmodel/PatcherViewModel.kt


    private val workManager = WorkManager.getInstance(app)
    private val _patcherSucceeded = MediatorLiveData<Boolean?>()
    val patcherSucceeded: LiveData<Boolean?> get() = _patcherSucceeded
    private val _isPatchingActive = MediatorLiveData<Boolean>().apply { value = patcherWorkerId?.uuid != null }
    val isPatchingActive: LiveData<Boolean> get() = _isPatchingActive
    private var currentWorkSource: LiveData<WorkInfo?>? = null
    private val handledFailureIds = mutableSetOf<UUID>()
    private var replayWorkerProgressSnapshots = false
    private var lastAppliedWorkerProgressGeneration = Long.MIN_VALUE
    private var lastAppliedWorkerProgressSequence = Long.MIN_VALUE
    private var patcherMemoryUsageGeneration = -1L
    private var patcherMemoryUsageSequence = Long.MIN_VALUE
    private var patcherMemoryUsageSampleTimeMs = Long.MIN_VALUE
    private var forceKeepLocalInput = false
    private var lastLoggedErrorSignature: String? = null

    private var patchedSourceVersionName: String?
        get() = savedStateHandle.get("patched_source_version_name")
        set(value) {
            if (value == null) {
                savedStateHandle.remove<String>("patched_source_version_name")
            } else {
                savedStateHandle["patched_source_version_name"] = value
            }
        }

    private var patchedSourceVersionCode: Long?
        get() = savedStateHandle.get("patched_source_version_code")
        set(value) {
            if (value == null) {
                savedStateHandle.remove<Long>("patched_source_version_code")
            } else {
                savedStateHandle["patched_source_version_code"] = value
            }
        }

    private var patchedRepatchSourcePath: String?
        get() = savedStateHandle.get("patched_repatch_source_path")
        set(value) {
            if (value == null) {
                savedStateHandle.remove<String>("patched_repatch_source_path")
            } else {
                savedStateHandle["patched_repatch_source_path"] = value
            }
        }

    private var patcherWorkerId: ParcelUuid?
        get() = savedStateHandle.get("patcher_worker_id")
        set(value) {
            if (value == null) {
                savedStateHandle.remove<ParcelUuid>("patcher_worker_id")
            } else {
                savedStateHandle["patcher_worker_id"] = value
            }
        }

    init {
        viewModelScope.launch {
            var observedResumeGeneration = AppForeground.resumeGeneration
            while (true) {
                observedResumeGeneration = AppForeground.awaitNextResume(observedResumeGeneration)
                syncWorkerProgressFromCurrentSnapshot()
            }
        }
        val existingId = patcherWorkerId?.uuid
        if (existingId != null) {
            viewModelScope.launch {
                refreshPatcherInformationMetadata(gatherScopedBundles())
            }
            replayWorkerProgressSnapshots = true
            startPatchingTaskMonitor()
            observeWorker(existingId)
        } else {
            launchPrePatchPreparation(::runPreflightCheck)
        }
    }

    private suspend fun runPreflightCheck(chooseSplitApks: Boolean) {
        requiresSplitPreparation = withContext(Dispatchers.IO) {
            initialSplitRequirement(input.selectedApp)
        }
        val scopedBundles = gatherScopedBundles()
        val currentSelection = appliedSelection
        val sanitizedSelection = filterSelectionToAvailablePatches(currentSelection, scopedBundles)
        val missing = mutableListOf<String>()
        currentSelection.forEach { (uid, patches) ->
            val kept = sanitizedSelection[uid] ?: emptySet()
            patches.filterNot { it in kept }.forEach { missing += it }
        }
        if (missing.isNotEmpty()) {
            // Keep missing patches for "Continue anyway", while still enforcing installer rules.
            appliedSelection = applyCurrentPatchRules(currentSelection, scopedBundles)
            refreshPatcherInformationMetadata(scopedBundles)
            missingPatchWarning = MissingPatchWarningState(
                patchNames = missing.distinct().sorted()
            )
        } else {
            appliedSelection = applyCurrentPatchRules(sanitizedSelection, scopedBundles)
            refreshPatcherInformationMetadata(scopedBundles)
            prepareSplitSelectionOrStartWorker(chooseSplitApks)
        }
    }

    private fun batteryOptimizationState(): String {
        val isIgnoring = app.getSystemService<PowerManager>()
            ?.isIgnoringBatteryOptimizations(app.packageName) == true
        return if (isIgnoring) "disabled" else "enabled"
    }

    private suspend fun environmentState(): String = withContext(Dispatchers.IO) {
        when (rootInstaller.peekRootAccess()) {
            true -> "root"
            false -> "unrooted"
            null -> if (rootInstaller.isDeviceRooted()) "rooted" else "unrooted"
        }
    }

    private fun logBatteryOptimizationStatus(state: String = batteryOptimizationState()) {
        logger.info("Battery optimization: $state")
    }

    private fun logEnvironmentStatus(state: String) {
        logger.info("Environment: $state")
    }

    private fun startWorker() {
        if (workerLaunchJob?.isActive == true || _isPatchingActive.value == true) return
        workerLaunchJob = viewModelScope.launch {
            try {
                startWorkerNow()
            } finally {
                workerLaunchJob = null
            }
        }
    }

    private suspend fun startWorkerNow() {
        signatureWorkflowJob?.cancel()
        signatureWorkflowJob = null
        signatureWorkflowRunning = false
        signatureWorkflowCompleted = false
        mutableSignatureWorkflowProgress.value = SignatureMetadataWorkflowProgress(
            logSessionId = mutableSignatureWorkflowProgress.value.logSessionId + 1L
        )
        progressState.resetDexCompileState()
        resetFailureLogState()
        fs.deleteRepatchInputStagingFile(patchedRepatchSourcePath)
        patchedRepatchSourcePath = null
        patchedSourceVersionName = null
        patchedSourceVersionCode = null
        informationAppVersion = version
        informationAppVersionCode = versionCode
        patcherMemoryUsageGeneration = -1L
        patcherMemoryUsageSequence = Long.MIN_VALUE
        patcherMemoryUsageSampleTimeMs = Long.MIN_VALUE
        patcherMemoryUsageSamples.clear()
        val runSelection = currentSelectionSnapshot()
        val runOptions = currentOptionsSnapshot()
        persistRunConfiguration(runSelection, runOptions)
        val selectedBundleType = patchBundleRepository.selectionBundleType(runSelection)
        val runSteps = generateSteps(
            app,
            input.selectedApp,
            runSelection,
            requiresSplitPreparation,
            skipApkSigning,
            input.injectSignatureMetadata,
            preserveSplitApks = selectedBundleType == PatchBundleType.RESEAM
        )
        steps.clear()
        steps.addAll(runSteps)
        stepSubSteps.clear()
        val hasMixedRevancedPatcherVersions =
            selectedBundleType == PatchBundleType.REVANCED &&
                patchBundleRepository.selectionHasMixedRevancedPatcherVersions(runSelection)
        val usesRevancedPatcher22 = !hasMixedRevancedPatcherVersions &&
            selectedBundleType == PatchBundleType.REVANCED &&
            patchBundleRepository.selectionUsesRevancedPatcher22(runSelection)
        val selectedPatcherEngine = if (hasMixedRevancedPatcherVersions) {
            null
        } else {
            patcherEngineDisplayName(selectedBundleType, usesRevancedPatcher22)
        }
        val configuredMorpheBytecodeMode = if (selectedBundleType == PatchBundleType.MORPHE) {
            prefs.morpheBytecodeMode.get().runtimeValue
        } else {
            null
        }
        val batteryOptimization = batteryOptimizationState()
        val environment = environmentState()
        updatePatcherSessionInfo(
            PatcherSessionInfo(
                patchCount = runSelection.values.sumOf { it.size },
                selectedPatchLines = collectSelectedPatchDescriptions(runSelection),
                runtimeProcess = null,

                nativeLibsStripped = selectedSplitConfiguration?.stripNativeLibs
                    ?: prefs.stripUnusedNativeLibs.get(),
                skipUnusedSplits = prefs.skipUnneededSplitApks.get(),
                bundleType = selectedBundleType?.name,
                patcherEngine = selectedPatcherEngine,
                morpheBytecodeMode = configuredMorpheBytecodeMode,
                memoryOverride = "disabled",
                batteryOptimization = batteryOptimization,
                environment = environment
            )
        )
        runtimeReportedMemoryLimitMb = null
        replayWorkerProgressSnapshots = false
        lastAppliedWorkerProgressGeneration = Long.MIN_VALUE
        lastAppliedWorkerProgressSequence = Long.MIN_VALUE
        progressState.resetVisualProgress()
        if (preparedInputIncludesDownload) {
            val downloadIndex = steps.indexOfFirst { it.id == StepId.DownloadAPK }
            if (downloadIndex >= 0) {
                steps[downloadIndex] = steps[downloadIndex].withState(
                    state = State.COMPLETED,
                    progress = null
                )
            }
        }
        progressState.markInitialStepRunning()
        _isPatchingActive.value = true
        startPatchingTaskMonitor()
        logBatteryOptimizationStatus(batteryOptimization)
        logEnvironmentStatus(environment)
        val workId = try {
            launchWorker(runSelection, runOptions)
        } catch (_: UniqueWorkAlreadyRunningException) {
            stopPatchingTaskMonitor()
            _isPatchingActive.value = false
            progressState.reconcileFailureState(app.getString(R.string.patcher_already_running))
            _patcherSucceeded.value = false
            return
        } catch (error: Exception) {
            stopPatchingTaskMonitor()
            _isPatchingActive.value = false
            progressState.reconcileFailureState(
                error.simpleMessage() ?: app.getString(R.string.patcher_launch_failed)
            )
            _patcherSucceeded.value = false
            return
        }
        patcherWorkerId = ParcelUuid(workId)
        PatcherWorker.showInitialNotification(app)
        observeWorker(workId)
    }

    private fun clearPatchingNotification() {
        PatcherWorker.clearNotification(app)
    }

    private fun startPatchingTaskMonitor() {
        runCatching {
            PatchingTaskMonitorService.start(app)
        }.onFailure { error ->
            Log.d(TAG, "Failed to start patching task monitor", error)
        }
    }

    private fun stopPatchingTaskMonitor() {
        runCatching {
            PatchingTaskMonitorService.stop(app)
        }.onFailure { error ->
            Log.d(TAG, "Failed to stop patching task monitor", error)
        }
    }

    private fun hasTemporaryLocalInput() =
        input.selectedApp is SelectedApp.Local && input.selectedApp.temporary

    private fun originalTemporaryLocalInputFile(): File? =
        (input.selectedApp as? SelectedApp.Local)?.takeIf { it.temporary }?.file

    private fun redundantTemporaryLocalInputSourceFile(): File? {
        val original = originalTemporaryLocalInputFile() ?: return null
        val preserved = inputFile
        return original.takeUnless { preserved?.absolutePath == original.absolutePath }
    }

    private fun clearTemporaryLocalInputState() {
        inputFile = null
    }

    private fun deleteTemporaryLocalInput(file: File?) {
        file?.takeIf { it.exists() }?.delete()
    }

    private fun cleanupTemporaryLocalInput() {
        if (!hasTemporaryLocalInput()) return
        val preservedFileToDelete = inputFile
        val originalFileToDelete = redundantTemporaryLocalInputSourceFile()
        clearTemporaryLocalInputState()
        deleteTemporaryLocalInput(preservedFileToDelete)
        deleteTemporaryLocalInput(originalFileToDelete)
    }

    private suspend fun awaitWorkToFinish(workId: UUID) = suspendCancellableCoroutine<Unit> { continuation ->
        val source = workManager.getWorkInfoByIdLiveData(workId)
        val observer = object : Observer<WorkInfo?> {
            override fun onChanged(workInfo: WorkInfo?) {
                if (workInfo != null && !workInfo.state.isFinished) return
                source.removeObserver(this)
                if (continuation.isActive) {
                    continuation.resume(Unit)
                }
            }
        }
        source.observeForever(observer)
        continuation.invokeOnCancellation { source.removeObserver(observer) }
    }

    private fun cleanupTemporaryLocalInputAfterWorkStops(workId: UUID?) {
        if (!hasTemporaryLocalInput()) return
        val preservedFileToDelete = inputFile
        val originalFileToDelete = redundantTemporaryLocalInputSourceFile()
        if (preservedFileToDelete == null && originalFileToDelete == null) return
        clearTemporaryLocalInputState()
        CoroutineScope(Dispatchers.IO).launch {
            workId?.let { activeWorkId ->
                withContext(Dispatchers.Main.immediate) {
                    awaitWorkToFinish(activeWorkId)
                }
            }
            deleteTemporaryLocalInput(preservedFileToDelete)
            deleteTemporaryLocalInput(originalFileToDelete)
        }
    }

    private suspend fun persistPatchedApp(
        currentPackageName: String?,
        installType: InstallType,
        forceSave: Boolean = false,
        customInstallerPackageName: String? = null
    ): Boolean = persistPatchedAppMutex.withLock {
        val savedAppsEnabled = prefs.enableSavedApps.get()
        val disableSavedAppOverwrite =
            savedAppsEnabled && prefs.disableSavedAppOverwrite.get()
        val latestInstalledApp = sourceInstalledApp()
        if (latestInstalledApp != installedApp) {
            installedApp = latestInstalledApp
        }
        val shouldSaveForLater = savedAppsEnabled || forceSave
        withContext(Dispatchers.IO) {
            val installedPackageInfo = currentPackageName?.let(pm::getPackageInfo)
            val patchedPackageInfo = pm.getPackageInfo(outputFile)
            val packageInfo = installedPackageInfo ?: patchedPackageInfo
            if (packageInfo == null) {
                Log.e(TAG, "Failed to resolve package info for patched APK")
                return@withContext false
            }

            val finalPackageName = packageInfo.packageName
            val finalVersion = packageInfo.versionName?.takeUnless { it.isBlank() } ?: version ?: "unspecified"

            val metadata = buildExportMetadata(patchedPackageInfo ?: packageInfo)
            withContext(Dispatchers.Main) {
                exportMetadata = metadata
            }

            val globalBundlesFinal = patchBundleRepository.allBundlesInfoFlow.first()
            val seenPatchesByBundle = globalBundlesFinal.mapValues { (_, bundle) ->
                bundle.patches.map { it.name }.toSet()
            }
            val sanitizedSelectionFinal = sanitizeSelection(appliedSelection, globalBundlesFinal)
            val sanitizedOptionsFinal = sanitizeOptions(appliedOptions, globalBundlesFinal)
            val sanitizedSelectionOriginal = sanitizeSelection(appliedSelection, globalBundlesFinal)
            val sanitizedOptionsOriginal = sanitizeOptions(appliedOptions, globalBundlesFinal)

            val rememberSignatureWorkflow = input.rememberSignatureWorkflow &&
                (signatureWorkflowCompleted || hasOriginalSignatureMetadata())
            val selectionPayload = patchBundleRepository.snapshotSelection(
                sanitizedSelectionFinal,
                sanitizedOptionsFinal
            )?.copy(
                signatureWorkflow = PatchProfilePayload.SignatureWorkflow(
                    enabled = signatureWorkflowCompleted,
                    remembered = rememberSignatureWorkflow,
                    injected = signatureWorkflowCompleted
                )
            )

            val newVariantIdentity = buildSavedAppVariantIdentity(
                appVersion = finalVersion,
                selectionPayload = selectionPayload,
                patchSelection = sanitizedSelectionFinal,
                useMount = usingMountInstall
            )
            val savedEntriesForPackage = installedAppRepository.getByInstallType(InstallType.SAVED)
                .filter { savedApp ->
                    isSavedAppEntryForPackage(savedApp.currentPackageName, finalPackageName)
                }
            val sourceSavedEntry = installedApp?.takeIf { sourceEntry ->
                sourceEntry.installType == InstallType.SAVED &&
                    (
                        isSavedAppEntryForPackage(
                            sourceEntry.currentPackageName,
                            finalPackageName
                        ) ||
                            sourceEntry.originalPackageName == packageName ||
                            sourceEntry.originalPackageName == finalPackageName
                    )
            }
            val replacementSourceSavedEntry = sourceSavedEntry
                ?.takeUnless { disableSavedAppOverwrite }
            val savedEntryIdentities = mutableMapOf<String, String>()
            savedEntriesForPackage.forEach { savedApp ->
                savedEntryIdentities[savedApp.currentPackageName] = savedEntryIdentity(savedApp)
            }
            val matchingSavedEntries = savedEntriesForPackage.filter { savedApp ->
                savedEntryIdentities[savedApp.currentPackageName] == newVariantIdentity
            }
            val matchingSavedEntry = if (disableSavedAppOverwrite) {
                null
            } else {
                replacementSourceSavedEntry ?: matchingSavedEntries.firstOrNull()
            }
            val persistedInstallType = installType
            val shouldArchiveExistingVisibleEntry = persistedInstallType != InstallType.SAVED
            val existingFinalPackageEntry = installedAppRepository.get(finalPackageName)
            val existingInstalledEntry = existingFinalPackageEntry?.takeIf {
                it.installType != InstallType.SAVED
            }
            val existingSavedEntryAtBaseKey = existingFinalPackageEntry?.takeIf {
                it.installType == InstallType.SAVED
            }
            val effectiveShouldSaveForLater = shouldSaveForLater
            val existingInstalledIdentity = existingInstalledEntry?.let { savedEntryIdentity(it) }
            val existingSavedEntryIdentity = existingSavedEntryAtBaseKey?.let { savedEntryIdentity(it) }
            val savedVariantAlreadyInstalled =
                persistedInstallType == InstallType.SAVED &&
                    !disableSavedAppOverwrite &&
                    existingInstalledEntry != null &&
                    existingInstalledIdentity == newVariantIdentity
            val pendingHistoricalEntry: PendingHistoricalSavedEntry? = when {
                disableSavedAppOverwrite &&
                    effectiveShouldSaveForLater &&
                    persistedInstallType != InstallType.SAVED &&
                    shouldArchiveExistingVisibleEntry &&
                    existingInstalledEntry != null &&
                    existingInstalledIdentity != null &&
                    existingInstalledIdentity != newVariantIdentity &&
                    existingInstalledIdentity !in savedEntryIdentities.values ->
                    installedAppRepository.prepareHistoricalSavedEntry(
                        sourceApp = existingInstalledEntry,
                        targetPackageName = buildSavedAppEntryKey(
                            finalPackageName,
                            existingInstalledIdentity
                        )
                    )
                disableSavedAppOverwrite &&
                    effectiveShouldSaveForLater &&
                    persistedInstallType != InstallType.SAVED &&
                    shouldArchiveExistingVisibleEntry &&
                    existingSavedEntryAtBaseKey != null &&
                    existingSavedEntryIdentity != null &&
                    existingSavedEntryIdentity != newVariantIdentity &&
                    existingSavedEntryIdentity !in savedEntryIdentities
                        .filterKeys { it != existingSavedEntryAtBaseKey.currentPackageName }
                        .values ->
                    installedAppRepository.prepareHistoricalSavedEntry(
                        sourceApp = existingSavedEntryAtBaseKey,
                        targetPackageName = buildSavedAppEntryKey(
                            finalPackageName,
                            existingSavedEntryIdentity
                        )
                    )
                else -> null
            }
            val persistedPackageName = if (persistedInstallType == InstallType.SAVED) {
                if (savedVariantAlreadyInstalled) {
                    finalPackageName
                } else if (matchingSavedEntry != null) {
                    matchingSavedEntry.currentPackageName
                } else if (disableSavedAppOverwrite) {
                    buildUniqueSavedAppEntryKey(finalPackageName, newVariantIdentity)
                } else {
                    val canUseBaseKey = savedEntriesForPackage.isEmpty() &&
                        (existingFinalPackageEntry == null || existingFinalPackageEntry.installType == InstallType.SAVED)
                    if (canUseBaseKey) finalPackageName
                    else buildSavedAppEntryKey(finalPackageName, newVariantIdentity)
                }
            } else {
                finalPackageName
            }

            val savedCopyPackageName = if (persistedInstallType == InstallType.SAVED) {
                persistedPackageName
            } else {
                finalPackageName
            }
            val savedCopy = fs.getPatchedAppFile(savedCopyPackageName, finalVersion)
            var savedCopyReplacement: PendingPatchedAppReplacement? = null
            val savedCopyWritten = if (effectiveShouldSaveForLater) {
                try {
                    savedCopyReplacement = PendingPatchedAppReplacement.prepare(
                        source = outputFile,
                        target = savedCopy
                    )
                    true
                } catch (error: Exception) {
                    pendingHistoricalEntry?.discard()
                    Log.e(
                        TAG,
                        "Failed to prepare saved APK copy for $savedCopyPackageName",
                        error
                    )
                    return@withContext false
                }
            } else {
                false
            }

            val persistReplacement: suspend () -> Unit = {
                when {
                    persistedInstallType != InstallType.SAVED ->
                        installedAppRepository.addOrUpdate(
                            persistedPackageName,
                            packageName,
                            finalVersion,
                            persistedInstallType,
                            sanitizedSelectionFinal,
                            selectionPayload,
                            useMount = usingMountInstall,
                            resetCreatedAt = true,
                            customInstallerPackageName = customInstallerPackageName,
                            repatchSourcePath = patchedRepatchSourcePath,
                            updateRepatchSource = true
                        )
                    effectiveShouldSaveForLater &&
                        savedCopyWritten &&
                        savedVariantAlreadyInstalled -> {
                        val existingInstalled = requireNotNull(existingInstalledEntry)
                        installedAppRepository.addOrUpdate(
                            currentPackageName = persistedPackageName,
                            originalPackageName = existingInstalled.originalPackageName,
                            version = finalVersion,
                            installType = existingInstalled.installType,
                            patchSelection = sanitizedSelectionFinal,
                            selectionPayload = selectionPayload,
                            useMount = usingMountInstall,
                            customInstallerPackageName = existingInstalled.customInstallerPackageName,
                            repatchSourcePath = patchedRepatchSourcePath,
                            updateRepatchSource = true
                        )
                    }
                    effectiveShouldSaveForLater &&
                        savedCopyWritten ->
                        installedAppRepository.addOrUpdate(
                            persistedPackageName,
                            packageName,
                            finalVersion,
                            InstallType.SAVED,
                            sanitizedSelectionFinal,
                            selectionPayload,
                            useMount = usingMountInstall,
                            resetCreatedAt = true,
                            repatchSourcePath = patchedRepatchSourcePath,
                            updateRepatchSource = true
                        )
                }
            }
            try {
                if (pendingHistoricalEntry != null) {
                    pendingHistoricalEntry.commitWith(
                        persistedPackageName,
                        persistReplacement
                    )
                } else {
                    persistReplacement()
                }
            } catch (error: Throwable) {
                savedCopyReplacement?.rollback(error)
                throw error
            }
            savedCopyReplacement?.commit()
            if (pendingHistoricalEntry == null) {
                installedAppRepository.pruneRepatchInputs()
            }

            val persistedEntryHasRetainedRepatchInput = installedAppRepository
                .get(persistedPackageName)
                ?.repatchSourcePath
                ?.takeIf(String::isNotBlank)
                ?.let(::File)
                ?.isFile == true
            val preservedSavedEntry = if (!persistedEntryHasRetainedRepatchInput) {
                sequenceOf(sourceSavedEntry)
                    .plus(matchingSavedEntries.asSequence())
                    .filterNotNull()
                    .distinctBy(InstalledApp::currentPackageName)
                    .firstOrNull { savedEntry ->
                        savedEntry.currentPackageName != persistedPackageName &&
                            savedEntry.repatchSourcePath
                                ?.takeIf(String::isNotBlank)
                                ?.let(::File)
                                ?.isFile == true
                    }
            } else {
                null
            }
            val preservedSavedEntryKey = preservedSavedEntry?.currentPackageName
            if (preservedSavedEntryKey != null) {
                Log.w(
                    TAG,
                    "Keeping saved entry $preservedSavedEntryKey because its Repatch input was not retained for $persistedPackageName"
                )
            }
            val savedEntryCleanupTarget = preservedSavedEntryKey ?: persistedPackageName

            runPostCommitPersistenceStep(
                "Failed to clean up saved app references after persistence"
            ) {
                if (persistedInstallType != InstallType.SAVED) {
                    sourceSavedEntry?.takeIf {
                        it.currentPackageName != persistedPackageName &&
                            it.currentPackageName != preservedSavedEntryKey
                    }?.let { sourceEntry ->
                        installedAppRepository.migrateAutoPatchTarget(
                            sourceEntry.currentPackageName,
                            savedEntryCleanupTarget
                        )
                    }
                    replacementSourceSavedEntry
                        ?.takeUnless { it.currentPackageName == preservedSavedEntryKey }
                        ?.let { sourceEntry ->
                            if (sourceEntry.currentPackageName != persistedPackageName) {
                                installedAppRepository.delete(sourceEntry)
                            }
                            if (
                                sourceEntry.currentPackageName != persistedPackageName ||
                                sourceEntry.version != finalVersion
                            ) {
                                fs.getPatchedAppFile(
                                    sourceEntry.currentPackageName,
                                    sourceEntry.version
                                ).takeIf { oldFile ->
                                    oldFile.exists() &&
                                        !oldFile.absolutePath.equals(
                                            savedCopy.absolutePath,
                                            ignoreCase = true
                                        )
                                }?.delete()
                            }
                        }
                    collapseMatchingSavedEntriesForInstalledVariant(
                        packageName = finalPackageName,
                        installedPackageName = persistedPackageName,
                        variantIdentity = newVariantIdentity,
                        preservedEntryKey = preservedSavedEntryKey
                    )
                }
                if (
                    effectiveShouldSaveForLater &&
                    savedCopyWritten &&
                    persistedInstallType == InstallType.SAVED
                ) {
                    sourceSavedEntry?.takeIf {
                        it.currentPackageName != persistedPackageName &&
                            it.currentPackageName != preservedSavedEntryKey
                    }?.let { sourceEntry ->
                        installedAppRepository.migrateAutoPatchTarget(
                            sourceEntry.currentPackageName,
                            savedEntryCleanupTarget
                        )
                    }
                    replacementSourceSavedEntry
                        ?.takeUnless { it.currentPackageName == preservedSavedEntryKey }
                        ?.let { sourceEntry ->
                            if (
                                sourceEntry.currentPackageName == persistedPackageName &&
                                sourceEntry.version != finalVersion
                            ) {
                                fs.getPatchedAppFile(
                                    sourceEntry.currentPackageName,
                                    sourceEntry.version
                                ).takeIf { oldFile ->
                                    oldFile.exists() &&
                                        !oldFile.absolutePath.equals(
                                            savedCopy.absolutePath,
                                            ignoreCase = true
                                        )
                                }?.delete()
                            }
                        }
                    if (!disableSavedAppOverwrite) {
                        collapseMatchingSavedEntriesForInstalledVariant(
                            packageName = finalPackageName,
                            installedPackageName = persistedPackageName,
                            variantIdentity = newVariantIdentity,
                            preservedEntryKey = preservedSavedEntryKey
                        )
                    }
                }
            }

            runPostCommitPersistenceStep(
                "Failed to update patch configuration after persistence"
            ) {
                if (finalPackageName != packageName) {
                    patchSelectionRepository.updateSelectionWithSeenPatches(
                        finalPackageName,
                        sanitizedSelectionFinal,
                        seenPatchesByBundle
                    )
                    patchOptionsRepository.saveOptions(
                        finalPackageName,
                        sanitizedOptionsFinal
                    )
                }
                patchSelectionRepository.updateSelectionWithSeenPatches(
                    packageName,
                    sanitizedSelectionOriginal,
                    seenPatchesByBundle
                )
                patchOptionsRepository.saveOptions(packageName, sanitizedOptionsOriginal)
                appliedSelection = sanitizedSelectionOriginal
                appliedOptions = sanitizedOptionsOriginal
            }

            runPostCommitPersistenceStep(
                "Failed to prune saved APK files after persistence"
            ) {
                pruneUnreferencedPatchedAppFiles()
            }

            savedPatchedApp = savedPatchedApp ||
                (effectiveShouldSaveForLater && (savedCopyWritten || savedCopy.exists()))
            installedAppRepository.get(persistedPackageName)?.let { persistedApp ->
                currentSourceEntryKey = persistedApp.currentPackageName
                installedApp = persistedApp
            }
            true
        }
    }

    private suspend fun runPostCommitPersistenceStep(
        description: String,
        block: suspend () -> Unit
    ) = withContext(NonCancellable) {
        try {
            block()
        } catch (error: Exception) {
            Log.w(TAG, description, error)
        }
    }

    fun savePatchedAppForLater(
        onResult: (Boolean) -> Unit = {},
        showToast: Boolean = true
    ) {
        if (!outputFile.exists()) {
            app.toast(app.getString(R.string.patched_app_save_failed_toast))
            onResult(false)
            return
        }

        viewModelScope.launch {
            val success = persistPatchedApp(null, InstallType.SAVED, forceSave = true)
            if (success) {
                if (showToast) {
                    app.toast(app.getString(R.string.patched_app_saved_toast))
                }
            } else {
                app.toast(app.getString(R.string.patched_app_save_failed_toast))
            }
            onResult(success)
        }
    }

    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val pkg = intent.data?.schemeSpecificPart ?: return
            if (pkg == packageName) {
                basePackageInstalled = action != Intent.ACTION_PACKAGE_REMOVED
                if (_patcherSucceeded.value == true) {
                    refreshRootMountModeOverrideAsync()
                }
            }
            if (action == Intent.ACTION_PACKAGE_ADDED || action == Intent.ACTION_PACKAGE_REPLACED) {
                handleExternalInstallSuccess(pkg)
            }
        }
    }

    init {
        // TODO: detect system-initiated process death during the patching process.
        ContextCompat.registerReceiver(
            app,
            packageChangeReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        viewModelScope.launch {
            installedApp = sourceInstalledApp()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCleared() {
        super.onCleared()
        app.unregisterReceiver(packageChangeReceiver)
        val rootInstallerStillOpen =
            pendingExternalInstall?.token == InstallerManager.Token.PlayStore &&
                pendingExternalMountSuspension != null
        if (!rootInstallerStillOpen) {
            pendingExternalInstall?.let(installerManager::cleanup)
            pendingExternalInstall = null
            restorePendingExternalMountAsync()
        }
        externalInstallTimeoutJob?.cancel()
        externalInstallTimeoutJob = null
        externalInstallStartTime = null

        if (input.selectedApp is SelectedApp.Installed &&
            installerManager.baseInstallerToken(installerManager.getPrimaryToken()) ==
                InstallerManager.Token.AutoSaved
        ) {
            GlobalScope.launch(Dispatchers.Main) {
                val shouldRemount = withContext(Dispatchers.IO) {
                    installedAppRepository.get(packageName)?.installType == InstallType.MOUNT
                }
                if (!shouldRemount) return@launch
                uiSafe(app, R.string.failed_to_mount, "Failed to mount") {
                    withTimeout(Duration.ofMinutes(1L)) {
                        rootMountCoordinator.execute(
                            RootMountRequest(
                                packageName,
                                userId = android.os.Process.myUid() / 100_000,
                                operation = RootMountOperation.MOUNT_ONLY
                            )
                        ).requireSuccess()
                    }
                }
            }
        }

    }

    fun onBack(cleanupLocalInput: Boolean) {
        cancelSplitSelectionPreparation()
        val injectionJob = signatureWorkflowJob
        injectionJob?.cancel(CancellationException("Patching stopped"))
        // tempDir cannot be deleted inside onCleared because it gets called on system-initiated process death.
        if (_isPatchingActive.value == true) {
            val workId = patcherWorkerId?.uuid
            workId?.let(workManager::cancelWorkById)
            clearPatchingNotification()
            stopPatchingTaskMonitor()
            if (cleanupLocalInput) {
                cleanupTemporaryLocalInputAfterWorkStops(workId)
            }
        } else if (cleanupLocalInput && injectionJob == null) {
            cleanupTemporaryLocalInput()
        }
        val repatchSourcePath = patchedRepatchSourcePath
        patchedRepatchSourcePath = null
        val workId = patcherWorkerId?.uuid
        // Each screen owns its directory, so delayed cleanup cannot delete a retry's output.
        CoroutineScope(Dispatchers.IO).launch {
            injectionJob?.join()
            if (cleanupLocalInput && injectionJob != null) {
                withContext(Dispatchers.Main.immediate) { cleanupTemporaryLocalInput() }
            }
            workId?.let {
                withContext(Dispatchers.Main.immediate) { awaitWorkToFinish(it) }
            }
            fs.deleteRepatchInputStagingFile(repatchSourcePath)
            tempDir.deleteRecursively()
        }
    }

    fun isDeviceRooted() = rootInstaller.isDeviceRooted()

    private fun completeActivityRequest(requestId: Long, accepted: Boolean) {
        currentActivityRequest
            ?.takeIf { it.dialogState.requestId == requestId }
            ?.let { request ->
                request.completion.complete(accepted)
            }
    }

    fun rejectInteraction(requestId: Long) = completeActivityRequest(requestId, accepted = false)

    fun allowInteraction(requestId: Long) = completeActivityRequest(requestId, accepted = true)

    fun handleActivityResult(result: ActivityResult) {
        pendingActivityResumeFallback?.cancel()
        pendingActivityResumeFallback = null
        launchedActivity?.complete(result)
    }

    fun onHostResumed() {
        val pending = launchedActivity ?: return
        if (currentActivityRequest != null || pending.isCompleted) return

        pendingActivityResumeFallback?.cancel()
        pendingActivityResumeFallback = viewModelScope.launch {
            delay(DOWNLOADER_ACTIVITY_RESULT_GRACE_MS)
            if (launchedActivity === pending && currentActivityRequest == null && !pending.isCompleted) {
                pending.complete(ActivityResult(Activity.RESULT_CANCELED, null))
                launchedActivity = null
            }
        }
    }

    private fun clearPendingActivityInteractions() {
        pendingActivityResumeFallback?.cancel()
        pendingActivityResumeFallback = null
        currentActivityRequest?.let { request ->
            if (currentActivityRequest === request) {
                currentActivityRequest = null
            }
            request.completion.complete(false)
        }
        launchedActivity?.complete(ActivityResult(Activity.RESULT_CANCELED, null))
        launchedActivity = null
    }

    fun export(uri: Uri?) = viewModelScope.launch {
        uri?.let { targetUri ->
            ensureExportMetadata()
            val exportSucceeded = runCatching {
                withContext(Dispatchers.IO) {
                    app.contentResolver.openOutputStream(targetUri)
                        ?.use { stream -> Files.copy(outputFile.toPath(), stream) }
                        ?: throw IOException("Could not open output stream for export")
                }
            }.isSuccess

            if (!exportSucceeded) {
                app.toast(app.getString(R.string.saved_app_export_failed))
                return@launch
            }

            finalizeExport()
        }
    }

    fun exportToPath(
        target: Path,
        onResult: (Boolean) -> Unit = {}
    ) = viewModelScope.launch {
        ensureExportMetadata()
        val exportSucceeded = runCatching {
            withContext(Dispatchers.IO) {
                target.parent?.let { Files.createDirectories(it) }
                Files.copy(outputFile.toPath(), target, StandardCopyOption.REPLACE_EXISTING)
            }
        }.isSuccess

        if (!exportSucceeded) {
            app.toast(app.getString(R.string.saved_app_export_failed))
            onResult(false)
            return@launch
        }

        finalizeExport()
        onResult(true)
    }

    private suspend fun finalizeExport() {
        if (prefs.enableSavedApps.get()) {
            val wasAlreadySaved = hasSavedPatchedApp
            val saved = persistPatchedApp(null, InstallType.SAVED)
            if (!saved) {
                app.toast(app.getString(R.string.patched_app_save_failed_toast))
            } else if (!wasAlreadySaved) {
                app.toast(app.getString(R.string.patched_app_saved_toast))
            }
        }

        app.toast(app.getString(R.string.save_apk_success))
    }

    private fun buildLogContent(context: Context): String {
        val logSnapshot = logs.toList()
        val logMessages = logSnapshot.map { it.second }
        fun findLogValue(prefix: String): String? =
            logMessages.lastOrNull { it.startsWith(prefix) }
                ?.removePrefix(prefix)
                ?.trim()

        data class LogFallbackSnapshot(
            val bundleType: PatchBundleType?,
            val morpheBytecodeMode: String?,
            val patcherEngine: String?,
            val stripNativeLibs: Boolean,
            val skipUnusedSplits: Boolean,
            val environment: String,
            val selectedPatchLines: List<String>
        )
        val fallbackSnapshot by lazy(LazyThreadSafetyMode.NONE) {
            runBlocking {
                val fallbackSelection = currentSelectionSnapshot()
                val bundleType = patchBundleRepository.selectionBundleType(fallbackSelection)
                val hasMixedRevancedPatcherVersions =
                    bundleType == PatchBundleType.REVANCED &&
                        patchBundleRepository.selectionHasMixedRevancedPatcherVersions(fallbackSelection)
                val usesRevancedPatcher22 = !hasMixedRevancedPatcherVersions &&
                    bundleType == PatchBundleType.REVANCED &&
                    patchBundleRepository.selectionUsesRevancedPatcher22(fallbackSelection)
                val patcherEngine = if (hasMixedRevancedPatcherVersions) {
                    null
                } else {
                    patcherEngineDisplayName(bundleType, usesRevancedPatcher22)
                }
                val morpheBytecodeMode = if (bundleType == PatchBundleType.MORPHE) {
                    prefs.morpheBytecodeMode.get().runtimeValue
                } else {
                    null
                }
                val environment = environmentState()
                LogFallbackSnapshot(
                    bundleType = bundleType,
                    morpheBytecodeMode = morpheBytecodeMode,
                    patcherEngine = patcherEngine,
                    stripNativeLibs = prefs.stripUnusedNativeLibs.get(),
                    skipUnusedSplits = prefs.skipUnneededSplitApks.get(),
                    environment = environment,
                    selectedPatchLines = collectSelectedPatchDescriptions(fallbackSelection)
                )
            }
        }
        val bundleType = patcherSessionInfo.bundleType
            ?: fallbackSnapshot.bundleType?.name
            ?: "UNKNOWN"
        val morpheBytecodeMode = if (bundleType == PatchBundleType.MORPHE.name) {
            patcherSessionInfo.morpheBytecodeMode
                ?: findLogValue("Morphe bytecode mode:")
                ?: fallbackSnapshot.morpheBytecodeMode
        } else {
            null
        }
        val patcherEngine = patcherSessionInfo.patcherEngine
            ?: findLogValue("Patcher engine:")
            ?: fallbackSnapshot.patcherEngine
        val revancedPatcherVersion = patcherEngine
            ?.takeIf { it.startsWith("ReVanced ") }
            ?.removePrefix("ReVanced ")
        val stripNativeLibs = patcherSessionInfo.nativeLibsStripped
            ?: fallbackSnapshot.stripNativeLibs
        val skipUnusedSplits = patcherSessionInfo.skipUnusedSplits
            ?: fallbackSnapshot.skipUnusedSplits
        val environment = patcherSessionInfo.environment
            ?: findLogValue("Environment:")
            ?: fallbackSnapshot.environment
        val selectedPatchLines = patcherSessionInfo.selectedPatchLines
            ?: fallbackSnapshot.selectedPatchLines

        val runtimeReportedLimit = runtimeReportedMemoryLimitMb
            ?: patcherSessionInfo.memoryLimitMb
            ?: parseMemoryLimitMb(
                logMessages.lastOrNull { it.startsWith("Memory limit:") }
                    ?.removePrefix("Memory limit:")
                    ?.trim()
            )
        val effectiveLimit = runtimeReportedLimit ?: PatcherJvm.heapLimitMb(context)
        val runtimeMode = findLogValue("Runtime mode:")
            ?: patcherSessionInfo.runtimeName
            ?: patcherSessionInfo.runtimeProcess?.let { if (it) "bound ART service" else "in-app" }
            ?: "bound service"
        val memoryOverride = patcherSessionInfo.memoryOverride
            ?: findLogValue("Memory override:")
            ?: "disabled"
        val aapt2 = patcherSessionInfo.aapt2 ?: findLogValue("AAPT2:")
        val aapt2Fallback = patcherSessionInfo.aapt2Fallback
            ?: findLogValue("AAPT2 fallback:")
                ?.substringBefore(' ')
                ?.toBooleanStrictOrNull()
            ?: aapt2?.let { false }
        val appPackage = patcherSessionInfo.appPackageName
            ?: findLogValue("App package:")
            ?: input.selectedApp.packageName
        val appVersion = patcherSessionInfo.appVersionName
            ?: findLogValue("App version:")
            ?: informationAppVersion?.takeIf(String::isNotBlank)
            ?: "unspecified"
        val loggedAppVersionCode = findLogValue("App version code:")
        val appVersionCode = when {
            patcherSessionInfo.appVersionCodeReported == true ->
                patcherSessionInfo.appVersionCode?.toString() ?: "unspecified"
            loggedAppVersionCode != null -> loggedAppVersionCode
            informationAppVersionCode != null -> informationAppVersionCode.toString()
            else -> "unspecified"
        }
        val includedSplits = patcherSessionInfo.includedSplits
            ?: findLogValue("Included splits:")
        val excludedSplits = patcherSessionInfo.excludedSplits
            ?: findLogValue("Excluded splits:")
        val patchFailure = lastPatchFailure
        val patchFailureStep = lastPatchFailureStep
        val failureSummaryLog = patchFailure?.let { error ->
            "Failure in step=${patchFailureStep ?: "Unknown"}: ${error.message ?: error.type}"
        }
        val hasCombinedPatchFailureLog =
            patchFailureStep == StepId.ExecutePatch::class.java.simpleName &&
                patchFailure?.stackTrace
                    ?.lineSequence()
                    ?.firstOrNull(String::isNotBlank)
                    ?.let { firstStackLine ->
                        logSnapshot.any { (_, msg) ->
                            msg.contains(" failed:\n") && msg.contains(firstStackLine)
                        }
                    } == true

        val isIgnoring = context.getSystemService<PowerManager>()
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
        val batteryOptimization = patcherSessionInfo.batteryOptimization
            ?: findLogValue("Battery optimization:")
            ?: if (isIgnoring) "disabled" else "enabled"
        val deviceName = resolveDeviceName()

        val sizeBytes = patcherSessionInfo.apkSizeBytes ?: inputFile?.length() ?: 0L
        val sizeMb = if (sizeBytes > 0L) {
            "${(sizeBytes / 1_000_000.0).roundToInt()}MB"
        } else {
            "unknown"
        }
        val splitCount = patcherSessionInfo.splitCount ?: inputFile
            ?.takeIf { SplitApkPreparer.isSplitArchive(it) }
            ?.let { file -> SplitApkPreparer.splitApkEntryNames(file).size }

        val patchCount = patcherSessionInfo.patchCount ?: selectedPatchLines.size
        val droppedLines = droppedLogLineCount

        val logLines = logSnapshot
            .filterNot { (level, msg) ->
                    msg.startsWith("Battery optimization:") ||
                    msg.startsWith("Environment:") ||
                    msg.startsWith("Patching started at ") ||
                    msg.startsWith("Patcher runtime:") ||
                    msg.startsWith("Patcher engine:") ||
                    msg.startsWith("Morphe bytecode mode:") ||
                    msg.startsWith("Memory limit:") ||
                    msg.startsWith("Runtime mode:") ||
                    msg.startsWith("Memory override:") ||
                    msg.startsWith("Strip native libs:") ||
                    msg.startsWith("Skip unused splits:") ||
                    msg.startsWith("AAPT2:") ||
                    msg.startsWith("AAPT2 fallback:") ||
                    msg.startsWith("App package:") ||
                    msg.startsWith("App version:") ||
                    msg.startsWith("App version code:") ||
                    msg.startsWith("Included splits:") ||
                    msg.startsWith("Excluded splits:") ||
                    failureSummaryLog?.let { matchesBoundedLogMessage(msg, it) } == true ||
                    (hasCombinedPatchFailureLog &&
                        patchFailure?.let { matchesBoundedLogMessage(msg, it.stackTrace) } == true) ||
                    isVerbosePatcherExportLog(level, msg)
            }
            .map { (level, msg) -> "[${level.name}]: $msg" }

        return buildString {
            appendLine("------------")
            appendLine("Information:")
            appendLine("------------")
            appendLine("UPM version: ${BuildConfig.VERSION_NAME}")
            appendLine("Device architecture: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine("Device name: $deviceName")
            appendLine("Device model: ${Build.MODEL}")
            appendLine("Android version: ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})")
            appendLine("Environment: $environment")
            appendLine("Effective memory limit: ${effectiveLimit}MB")
            appendLine("Bundle type: $bundleType")
            morpheBytecodeMode?.let {
                appendLine("Morphe bytecode mode: $it")
            }
            patcherEngine?.let { appendLine("Patcher engine: $it") }
            revancedPatcherVersion?.let {
                appendLine("ReVanced Patcher version: $it")
            }
            appendLine("Runtime mode: $runtimeMode")
            appendLine("Memory override: $memoryOverride")
            aapt2?.let { appendLine("AAPT2: $it") }
            aapt2Fallback?.let { appendLine("AAPT2 fallback: $it") }
            appendLine("Strip native libs: ${if (stripNativeLibs) "on" else "off"}")
            appendLine("Skip unused splits: ${if (skipUnusedSplits) "on" else "off"}")
            appendLine("Battery optimization: $batteryOptimization")
            appendLine("App package: $appPackage")
            appendLine("App version: $appVersion")
            appendLine("App version code: $appVersionCode")
            appendLine("App size: $sizeMb")
            splitCount?.let { appendLine("Split: $it") }
            includedSplits?.let { appendLine("Included splits: $it") }
            excludedSplits?.let { appendLine("Excluded splits: $it") }
            patchFailure?.let { error ->
                appendLine("Patch result: failed")
                appendLine("Failure step: ${patchFailureStep ?: "Unknown"}")
                appendLine("Failure type: ${error.type.substringAfterLast('.')}")
                appendLine("Failure message: ${conciseFailureMessage(error)}")
            }
            appendLine("Patches: $patchCount")
            appendLine("Selected patches:")
            if (selectedPatchLines.isEmpty()) {
                appendLine("None")
            } else {
                selectedPatchLines.forEach { appendLine(it) }
            }
            appendLine()
            appendLine("------------")
            appendLine("Patcher Log:")
            appendLine("------------")
            if (droppedLines > 0) {
                appendLine("[WARN]: Log guard trimmed $droppedLines older line(s) to keep size bounded.")
            }
            if (logLines.isEmpty()) {
                appendLine("No log messages recorded.")
            } else {
                logLines.forEach { appendLine(it) }
            }
        }
    }

    fun getLogContent(context: Context): String = buildLogContent(context)

    fun exportLogsToPath(
        context: Context,
        target: Path,
        onResult: (Boolean) -> Unit = {}
    ) = viewModelScope.launch {
        val exportSucceeded = runCatching {
            withContext(Dispatchers.IO) {
                target.parent?.let { Files.createDirectories(it) }
                Files.newBufferedWriter(
                    target,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
                ).use { writer ->
                    writer.write(buildLogContent(context))
                }
            }
        }.isSuccess

        if (!exportSucceeded) {
            app.toast(app.getString(R.string.patcher_log_export_failed))
            onResult(false)
            return@launch
        }

        app.toast(app.getString(R.string.patcher_log_export_success))
        onResult(true)
    }

    fun exportLogsToUri(
        context: Context,
        target: Uri?,
        onResult: (Boolean) -> Unit = {}
    ) = viewModelScope.launch {
        if (target == null) {
            onResult(false)
            return@launch
        }

        val exportSucceeded = runCatching {
            withContext(Dispatchers.IO) {
                app.contentResolver.openOutputStream(target, "wt")
                    ?.bufferedWriter(StandardCharsets.UTF_8)
                    ?.use { writer ->
                        writer.write(buildLogContent(context))
                    }
                    ?: throw IOException("Could not open output stream for log export")
            }
        }.isSuccess

        if (!exportSucceeded) {
            app.toast(app.getString(R.string.patcher_log_export_failed))
            onResult(false)
            return@launch
        }

        app.toast(app.getString(R.string.patcher_log_export_success))
        onResult(true)
    }

    fun exportLogs(context: Context) {
        val content = buildLogContent(context)

        val sendIntent: Intent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, content)
            type = "text/plain"
        }

        val shareIntent = Intent.createChooser(sendIntent, null)
        context.startActivity(shareIntent)
    }

    suspend fun readRootMountDiagnostics(): String? {
        val report = try {
            rootMountCoordinator.exportDiagnostics(packageName).trim()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e(tag, "Failed to read root mount diagnostics", error)
            buildString {
                appendLine("------------")
                appendLine("Root diagnostic collection failure:")
                appendLine("------------")
                appendLine("Package: $packageName")
                appendLine("Reason: ${error.simpleMessage() ?: error.javaClass.simpleName.orEmpty()}")
            }.trimEnd()
        }

        return buildString {
            appendLine("============================================================")
            appendLine("Universal Patch Manager - Root Mount Diagnostics")
            appendLine("============================================================")
            appendLine("UPM version: ${BuildConfig.VERSION_NAME}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})")
            appendLine("Architecture: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine()
            rootMountDiagnosticsContext?.takeIf(String::isNotBlank)?.let { context ->
                appendLine("------------")
                appendLine("Patcher mount outcome:")
                appendLine("------------")
                appendLine(context)
                appendLine()
            }
            appendLine(report)
        }
    }

    fun exportRootMountDiagnosticsToPath(
        target: Path,
        onResult: (Boolean) -> Unit = {}
    ): Job {
        rootMountDiagnosticsExportInProgress = true
        return viewModelScope.launch {
            try {
                val content = readRootMountDiagnostics() ?: run {
                    onResult(false)
                    return@launch
                }
                val succeeded = try {
                    withContext(Dispatchers.IO) {
                        target.parent?.let { Files.createDirectories(it) }
                        Files.newBufferedWriter(
                            target,
                            StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING
                        ).use { writer -> writer.write(content) }
                    }
                    true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.e(tag, "Failed to export root mount diagnostics to $target", error)
                    false
                }

                if (succeeded) clearRootMountDiagnosticsContext()
                app.toast(
                    app.getString(
                        if (succeeded) {
                            R.string.root_mount_diagnostics_export_success
                        } else {
                            R.string.root_mount_diagnostics_export_failed
                        }
                    )
                )
                onResult(succeeded)
            } finally {
                rootMountDiagnosticsExportInProgress = false
            }
        }
    }

    fun exportRootMountDiagnosticsToUri(
        target: Uri?,
        onResult: (Boolean) -> Unit = {}
    ): Job {
        rootMountDiagnosticsExportInProgress = true
        return viewModelScope.launch {
            try {
                if (target == null) {
                    onResult(false)
                    return@launch
                }
                val content = readRootMountDiagnostics() ?: run {
                    onResult(false)
                    return@launch
                }
                val succeeded = try {
                    withContext(Dispatchers.IO) {
                        app.contentResolver.openOutputStream(target, "wt")
                            ?.bufferedWriter(StandardCharsets.UTF_8)
                            ?.use { writer -> writer.write(content) }
                            ?: throw IOException("Could not open output stream for root mount diagnostics")
                    }
                    true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.e(tag, "Failed to export root mount diagnostics to $target", error)
                    false
                }

                if (succeeded) clearRootMountDiagnosticsContext()
                app.toast(
                    app.getString(
                        if (succeeded) {
                            R.string.root_mount_diagnostics_export_success
                        } else {
                            R.string.root_mount_diagnostics_export_failed
                        }
                    )
                )
                onResult(succeeded)
            } finally {
                rootMountDiagnosticsExportInProgress = false
            }
        }
    }

    fun open() = installedPackageName?.let(pm::launch)

    private suspend fun performInstall(
        installType: InstallType,
        downgradeFallbackConfirmed: Boolean = false,
        installAsPlayStore: Boolean = false
    ) {
        val operationJob = currentCoroutineContext()[Job]
        activeInstallJob = operationJob
        try {
            rootMountRecoveryMessage = null
            if (installType == InstallType.MOUNT) {
                rootMountDiagnosticsContext = null
            }
            activeInstallType = installType
            deferInstallProgressToasts = installType != InstallType.MOUNT
            updateInstallingState(true)
            installStatus = InstallCompletionStatus.InProgress

            Log.d(TAG, "performInstall(type=$installType, outputExists=${outputFile.exists()}, output=${outputFile.absolutePath})")
            val currentPackageInfo = pm.getPackageInfo(outputFile)
                ?: throw Exception("Failed to load application info")

            // If the app is currently installed
            val existingPackageInfo = pm.getPackageInfo(currentPackageInfo.packageName)
            if (existingPackageInfo != null && installType != InstallType.MOUNT) {
                // Check if the app version is less than the installed version
                if (pm.getVersionCode(currentPackageInfo) < pm.getVersionCode(existingPackageInfo)) {
                    val hint = app.getString(R.string.installer_hint_downgrade)
                    showInstallFailure(app.getString(R.string.install_app_fail, hint))
                    return
                }
            }

            when (installType) {
                InstallType.DEFAULT, InstallType.CUSTOM, InstallType.SAVED, InstallType.SHIZUKU -> {
                    if (!pm.requestInstallPackagesPermission()) {
                        val hint = installerManager.formatFailureHint(PackageInstaller.STATUS_FAILURE_BLOCKED, null)
                            ?: app.getString(R.string.installer_hint_blocked)
                        showInstallFailure(app.getString(R.string.install_app_fail, hint))
                        return
                    }
                    // Check if the app is mounted as root
                    // If it is, unmount it first, silently
                    if (rootInstaller.hasRootAccess() && rootInstaller.isAppMounted(packageName)) {
                        rootMountCoordinator.execute(
                            RootMountRequest(
                                packageName,
                                userId = android.os.Process.myUid() / 100_000,
                                operation = RootMountOperation.UNMOUNT
                            )
                        ).requireSuccess()
                    }

                    val result = try {
                        sessionInstaller.install(
                            outputFile,
                            currentPackageInfo.packageName,
                            ::enableInstallProgressToasts
                        )
                    } catch (_: InstallCancelledException) {
                        installStatus = null
                        updateInstallingState(false)
                        return
                    } catch (error: SessionDeadException) {
                        Log.w(TAG, "PackageInstaller session died; using intent fallback", error)
                        val fallbackPlan = installerManager.createSystemFallbackPlan(
                            target = InstallerManager.InstallTarget.PATCHER,
                            sourceFile = outputFile,
                            expectedPackage = currentPackageInfo.packageName,
                            sourceLabel = null
                        )
                        launchExternalInstaller(fallbackPlan)
                        return
                    }

                    when (result) {
                        InstallResult.Success -> {
                            val persisted = persistPatchedApp(currentPackageInfo.packageName, installType)
                            if (!persisted) {
                                Log.w(TAG, "Failed to persist installed patched app metadata")
                            }
                            installedPackageName = currentPackageInfo.packageName
                            packageInstallerStatus = null
                            installFailureMessage = null
                            markInstallSuccess(currentPackageInfo.packageName)
                            lastSuccessInstallType = installType
                            lastSuccessAtMs = System.currentTimeMillis()
                            updateInstallingState(false)
                        }

                        is InstallResult.Conflict -> {
                            val backendReason = result.message
                            if (installerManager.apkSignatureChecksEnabled &&
                                installerManager.isSignatureMismatch(backendReason)
                            ) {
                                val plan = installerManager.resolvePlan(
                                    InstallerManager.InstallTarget.PATCHER,
                                    outputFile,
                                    currentPackageInfo.packageName,
                                    null,
                                    allowMount = usingMountInstall &&
                                        currentPackageInfo.packageName == packageName
                                )
                                showSignatureMismatchPrompt(currentPackageInfo.packageName, plan)
                                return
                            }
                            val message = installerManager.formatFailureHint(
                                PackageInstaller.STATUS_FAILURE_CONFLICT,
                                backendReason
                            ) ?: backendReason ?: app.getString(R.string.installer_hint_conflict_generic)
                            showInstallFailure(app.getString(R.string.install_app_fail, message))
                        }

                        is InstallResult.Failure -> {
                            val backendReason = result.message
                            val message = installerManager.formatFailureHint(
                                result.status,
                                backendReason
                            ) ?: backendReason ?: app.getString(R.string.installer_hint_generic)
                            showInstallFailure(app.getString(R.string.install_app_fail, message))
                        }
                    }
                }

                InstallType.MOUNT -> performRootMount(
                    currentPackageInfo,
                    downgradeFallbackConfirmed,
                    installAsPlayStore
                )
                InstallType.PLAY_STORE,
                InstallType.ROOT_PLAY_STORE,
                InstallType.SHIZUKU_PLAY_STORE -> error(
                    "Play Store source installs use their dedicated installer plan"
                )
            }
        } catch (cancelled: CancellationException) {
            packageInstallerStatus = null
            installStatus = null
            installFailureMessage = null
            updateInstallingState(false)
            throw cancelled
        } catch (e: Exception) {
            Log.e(tag, "Failed to install", e)
            packageInstallerStatus = null
            showInstallFailure(
                app.getString(
                    R.string.install_app_fail,
                    e.simpleMessage() ?: e.javaClass.simpleName.orEmpty()
                ),
                allowFallback = installType != InstallType.MOUNT,
                attemptedInstallType = installType.takeIf { it == InstallType.MOUNT }
            )
        } finally {
            if (activeInstallJob === operationJob) activeInstallJob = null
        }
    }

    private suspend fun performRootMount(
        packageInfo: PackageInfo,
        downgradeFallbackConfirmed: Boolean,
        installAsPlayStore: Boolean
    ) {
        check(packageInfo.packageName == packageName) {
            app.getString(R.string.root_mount_renamed_package_not_supported)
        }
        val originalInput = inputFile?.let(SharedApkFiles::resolve)
        val selectedApp = input.selectedApp
        val sourceVersionNameHint = patchedSourceVersionName
        val sourceVersionCodeHint = patchedSourceVersionCode
        var stockWorkspace: File? = null
        val (installed, request) = try {
            withContext(Dispatchers.IO) {
                if (!rootInstaller.hasRootAccess()) throw RootServiceException()
                val installedBaseInfo = pm.getPackageInfo(packageName)
                val appMounted = installedBaseInfo != null && rootInstaller.isAppMounted(packageName)
                val originalInputIsSplit =
                    originalInput?.let(SplitApkPreparer::isSplitArchive) == true
                val sourcePackageInfo = originalInput
                    ?.takeUnless { originalInputIsSplit }
                    ?.let(pm::getPackageInfo)
                val sourceVersionName = sourceVersionNameHint
                    ?: sourcePackageInfo?.versionName?.takeIf(String::isNotBlank)
                    ?: selectedApp.version?.takeIf(String::isNotBlank)
                    ?: throw IllegalStateException("Patched APK source version name is unavailable")
                val targetVersionCode = sourceVersionCodeHint
                    ?: sourcePackageInfo?.let(pm::getVersionCode)
                    ?: selectedApp.versionCode
                    ?: throw IllegalStateException("Patched APK source version code is unavailable")
                val sourcePackageName = sourcePackageInfo?.packageName ?: selectedApp.packageName
                check(
                    sourcePackageName == packageInfo.packageName &&
                        sourceVersionName == packageInfo.versionName
                ) {
                    "Patched APK does not match its stock source"
                }
                val installedMatchesSourceVersion = installedBaseInfo != null &&
                    pm.getVersionCode(installedBaseInfo) == targetVersionCode &&
                    installedBaseInfo.versionName == sourceVersionName
                val stockNeedsReplacement = rootMountStockReplacementRequired(
                    installedMatchesSourceVersion = installedMatchesSourceVersion
                )
                val splitSource = if (stockNeedsReplacement) {
                    verifiedSplitStockSource(
                        installedInfo = installedBaseInfo,
                        sourceVersionName = sourceVersionName,
                        sourceVersionCode = targetVersionCode
                    ).second
                } else {
                    null
                }
                val stockApks = when {
                    stockNeedsReplacement && splitSource != null -> {
                        val directory = File(app.cacheDir, "root-stock-${System.nanoTime()}")
                        stockWorkspace = directory
                        SplitApkPreparer.extractEntriesForProcessing(splitSource, directory)
                            .map { it.file }
                    }
                    stockNeedsReplacement -> {
                        val stock = verifiedStandaloneStockCandidates(
                            sourceVersionName,
                            targetVersionCode
                        ).firstOrNull { candidate ->
                            installedSignerMatchesStockSource(installedBaseInfo, candidate)
                        } ?: throw IllegalStateException(
                            app.getString(R.string.install_app_fail_missing_stock)
                        )
                        listOf(stock)
                    }
                    appMounted -> {
                        // applicationInfo.sourceDir resolves through the active bind mount. It is
                        // the patched payload, while the committed mount owns the stock identity.
                        emptyList()
                    }
                    else -> {
                        // The installed package already has the stock version we need. Use its
                        // registered base APK instead of the patch input, which can have a
                        // different signing certificate.
                        val installedStock = installedBaseInfo?.applicationInfo?.sourceDir
                            ?.let(::File)
                            ?.takeIf(File::isFile)
                            ?: throw IllegalStateException(
                                app.getString(R.string.install_app_fail_missing_stock)
                            )
                        listOf(installedStock)
                    }
                }
                (installedBaseInfo != null) to RootMountRequest(
                    packageName = packageInfo.packageName,
                    userId = android.os.Process.myUid() / 100_000,
                    operation = if (stockNeedsReplacement) {
                        RootMountOperation.REPLACE_STOCK_AND_MOUNT
                    } else {
                        RootMountOperation.SWITCH_PATCHED_BUILD
                    },
                    patchedApk = outputFile,
                    stockApks = stockApks,
                    expectedVersionName = packageInfo.versionName,
                    expectedVersionCode = pm.getVersionCode(packageInfo),
                    expectedStockVersionCode = targetVersionCode,
                    label = with(pm) { packageInfo.label() },
                    downgradeFallbackConfirmed = downgradeFallbackConfirmed
                )
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { stockWorkspace?.deleteRecursively() }
            throw failure
        }
        basePackageInstalled = installed
        val result = try {
            rootMountCoordinator.execute(
                request
            ) { phase -> rootMountPhase = phase }
        } finally {
            rootMountPhase = null
            withContext(NonCancellable + Dispatchers.IO) { stockWorkspace?.deleteRecursively() }
        }
        when (result) {
            is RootMountResult.Success -> {
                rootDowngradeConfirmationPending = false
                rootDowngradePlayStoreSourcePending = false
                val sourceAttributionError = if (installAsPlayStore) {
                    reinstallMountedStockAsPlayStore(
                        context = app,
                        rootInstaller = rootInstaller,
                        rootMountCoordinator = rootMountCoordinator,
                        packageName = packageInfo.packageName,
                        userId = android.os.Process.myUid() / 100_000
                    )
                } else null
                val displayInstallType = if (
                    installAsPlayStore && sourceAttributionError == null
                ) {
                    InstallType.ROOT_PLAY_STORE
                } else {
                    InstallType.MOUNT
                }
                val persistedInstallerPackageName = PLAY_STORE_INSTALLER_PACKAGE.takeIf {
                    installAsPlayStore && sourceAttributionError == null
                }
                val persisted = persistPatchedApp(
                    packageInfo.packageName,
                    InstallType.MOUNT,
                    customInstallerPackageName = persistedInstallerPackageName
                )
                if (!persisted) {
                    Log.w(TAG, "Failed to persist mounted patched app metadata")
                }
                installedPackageName = packageInfo.packageName
                markInstallSuccess(packageInfo.packageName)
                lastSuccessInstallType = displayInstallType
                lastSuccessAtMs = System.currentTimeMillis()
                sourceAttributionError?.let { error ->
                    Log.w(TAG, "Failed to record Play Store as the installation source", error)
                    app.toast(
                        app.getString(
                            R.string.installer_play_store_attribution_failed,
                            error.simpleMessage() ?: error.javaClass.simpleName.orEmpty()
                        )
                    )
                }
                updateInstallingState(false)
            }

            is RootMountResult.RequiresDowngradeConfirmation -> {
                rootDowngradeConfirmationPending = true
                rootDowngradePlayStoreSourcePending = installAsPlayStore
                installStatus = null
                updateInstallingState(false)
            }

            is RootMountResult.RecoveredToPreviousMount -> {
                showRootMountRecovery(
                    app.getString(R.string.root_mount_recovered_previous_message, result.diagnosticId)
                )
            }
            is RootMountResult.RecoveredToStock -> {
                showRootMountRecovery(
                    app.getString(R.string.root_mount_recovered_stock_message, result.diagnosticId)
                )
            }
            is RootMountResult.RequiresRepatch -> {
                showInstallFailure(
                    result.reason,
                    allowFallback = false,
                    attemptedInstallType = InstallType.MOUNT
                )
            }
            is RootMountResult.Busy -> {
                val detail = result.reason ?: "Persisted phase: " +
                    (result.phase?.name?.lowercase()?.replace('_', ' ') ?: "preparing")
                showInstallFailure(
                    app.getString(R.string.root_mount_recovery_in_progress, detail),
                    allowFallback = false,
                    attemptedInstallType = InstallType.MOUNT
                )
            }
            is RootMountResult.Failure -> throw IllegalStateException(
                "${result.message} ${result.describeOutcome()} " +
                    "Diagnostic ${result.diagnosticId}."
            )
        }
    }

    fun confirmRootDowngrade() {
        val installAsPlayStore = rootDowngradePlayStoreSourcePending
        rootDowngradeConfirmationPending = false
        rootDowngradePlayStoreSourcePending = false
        viewModelScope.launch {
            performInstall(
                InstallType.MOUNT,
                downgradeFallbackConfirmed = true,
                installAsPlayStore = installAsPlayStore
            )
        }
    }

    fun dismissRootDowngradeConfirmation() {
        rootDowngradeConfirmationPending = false
        rootDowngradePlayStoreSourcePending = false
        installStatus = null
        activeInstallType = null
        updateInstallingState(false)
    }

    // Code adapted from Morphe, see third-party/NOTICE for more information
    // https://github.com/MorpheApp/morphe-manager/commit/7e24461c1454b712da4df21440db6f417c94ce58
    private suspend fun performRootPlayStoreInstall(
        retryMountSuspension: RootMountSuspension? = null
    ) {
        val operationJob = currentCoroutineContext()[Job]
        activeInstallJob = operationJob
        activeInstallType = InstallType.ROOT_PLAY_STORE
        updateInstallingState(true)
        installStatus = InstallCompletionStatus.InProgress
        packageInstallerStatus = null
        try {
            val packageInfo = pm.getPackageInfo(outputFile)
                ?: throw Exception("Failed to load application info")
            val targetPackage = packageInfo.packageName
            installAsPlayStoreWithMountRollback(
                rootInstaller = rootInstaller,
                rootMountCoordinator = rootMountCoordinator,
                apkFile = outputFile,
                packageName = targetPackage,
                userId = android.os.Process.myUid() / 100_000
            )
            runCatching { retryMountSuspension?.retire() }
                .onFailure { error ->
                    Log.w(TAG, "Failed to retire the previous root mount after reinstall", error)
                }
            if (!persistPatchedApp(targetPackage, InstallType.ROOT_PLAY_STORE)) {
                Log.w(TAG, "Failed to persist root Play Store install metadata")
            }
            installedPackageName = targetPackage
            markInstallSuccess(targetPackage)
            lastSuccessInstallType = InstallType.ROOT_PLAY_STORE
            lastSuccessAtMs = System.currentTimeMillis()
        } catch (cancelled: CancellationException) {
            installStatus = null
            throw cancelled
        } catch (error: Exception) {
            Log.e(tag, "Failed to install as Play Store with root", error)
            val targetPackage = pm.getPackageInfo(outputFile)?.packageName ?: packageName
            if (installerManager.apkSignatureChecksEnabled &&
                installerManager.isSignatureMismatch(error.message)
            ) {
                showSignatureMismatchPrompt(
                    targetPackage,
                    InstallerManager.InstallPlan.RootPlayStore(
                        InstallerManager.InstallTarget.PATCHER
                    )
                )
                return
            }
            showInstallFailure(
                app.getString(
                    R.string.install_app_fail,
                    error.simpleMessage() ?: error.javaClass.simpleName.orEmpty()
                )
            )
        } finally {
            updateInstallingState(false)
            if (activeInstallJob === operationJob) activeInstallJob = null
        }
    }

    private suspend fun performShizukuInstall(
        installerPackageNameOverride: String? = null,
        allowAutoUninstall: Boolean = false
    ) {
        val operationJob = currentCoroutineContext()[Job]
        activeInstallJob = operationJob
        val shizukuInstallType = if (
            installerPackageNameOverride == ShizukuInstaller.GOOGLE_PLAY_PACKAGE
        ) InstallType.SHIZUKU_PLAY_STORE else InstallType.SHIZUKU
        activeInstallType = shizukuInstallType
        updateInstallingState(true)
        installStatus = InstallCompletionStatus.InProgress
        packageInstallerStatus = null
        try {

            val currentPackageInfo = pm.getPackageInfo(outputFile)
                ?: throw Exception("Failed to load application info")

            val existingPackageInfo = pm.getPackageInfo(currentPackageInfo.packageName)
            if (existingPackageInfo != null) {
                if (pm.getVersionCode(currentPackageInfo) < pm.getVersionCode(existingPackageInfo)) {
                    val hint = app.getString(R.string.installer_hint_downgrade)
                    showInstallFailure(app.getString(R.string.install_app_fail, hint))
                    return
                }
            }

            if (rootInstaller.hasRootAccess() && rootInstaller.isAppMounted(packageName)) {
                rootMountCoordinator.execute(
                    RootMountRequest(
                        packageName,
                        userId = android.os.Process.myUid() / 100_000,
                        operation = RootMountOperation.UNMOUNT
                    )
                ).requireSuccess()
            }

            val result = shizukuInstaller.install(
                outputFile,
                currentPackageInfo.packageName,
                installerPackageNameOverride
            )
            if (result.status != PackageInstaller.STATUS_SUCCESS) {
                throw ShizukuInstaller.InstallerOperationException(result.status, result.message)
            }

            val persisted = persistPatchedApp(currentPackageInfo.packageName, shizukuInstallType)
            if (!persisted) {
                Log.w(TAG, "Failed to persist installed patched app metadata")
            }

            installedPackageName = currentPackageInfo.packageName
            packageInstallerStatus = null
            installFailureMessage = null
            installStatus = InstallCompletionStatus.Success(currentPackageInfo.packageName)
            updateInstallingState(false)
            suppressFailureAfterSuccess = true
            lastSuccessInstallType = shizukuInstallType
            lastSuccessAtMs = System.currentTimeMillis()
        } catch (cancelled: CancellationException) {
            packageInstallerStatus = null
            installStatus = null
            installFailureMessage = null
            throw cancelled
        } catch (error: ShizukuInstaller.InstallerOperationException) {
            Log.e(tag, "Failed to install via Shizuku", error)
            val currentPackage = pm.getPackageInfo(outputFile)?.packageName ?: packageName
            if (
                allowAutoUninstall &&
                installerManager.apkSignatureChecksEnabled &&
                installerManager.isSignatureMismatch(error.message) &&
                tryAutoUninstallSignatureConflict(currentPackage, automatic = true)
            ) {
                performShizukuInstall(
                    installerPackageNameOverride = installerPackageNameOverride,
                    allowAutoUninstall = false
                )
                return
            }
            val backendReason = error.message ?: error.javaClass.simpleName
            val message = installerManager.formatShizukuFailure(error.status, backendReason)
            packageInstallerStatus = null
            showInstallFailure(message)
        } catch (error: Exception) {
            Log.e(tag, "Failed to install via Shizuku", error)
            if (packageInstallerStatus == null) {
                packageInstallerStatus = PackageInstaller.STATUS_FAILURE
            }
            showInstallFailure(
                app.getString(
                    R.string.install_app_fail,
                    error.simpleMessage() ?: error.javaClass.simpleName.orEmpty()
                )
            )
        } finally {
            if (packageInstallerStatus == PackageInstaller.STATUS_SUCCESS && installStatus !is InstallCompletionStatus.Success) {
                markInstallSuccess(installedPackageName ?: packageName)
            }
            updateInstallingState(false)
            if (activeInstallJob === operationJob) activeInstallJob = null
        }
    }

    private suspend fun executeInstallPlan(
        plan: InstallerManager.InstallPlan,
        automatic: Boolean = false
    ) {
        Log.d(TAG, "executeInstallPlan(plan=${plan::class.java.simpleName})")
        restorePendingExternalMount()
        recordInstallPlan(plan, lastInstallExpectedPackage ?: packageName, lastInstallSourceLabel)
        when (plan) {
            is InstallerManager.InstallPlan.Internal -> {
                pendingExternalInstall?.let(installerManager::cleanup)
                pendingExternalInstall = null
                externalInstallTimeoutJob?.cancel()
                externalInstallTimeoutJob = null
                performInstall(installTypeFor(plan.target))
            }

            is InstallerManager.InstallPlan.RootPlayStore -> {
                pendingExternalInstall?.let(installerManager::cleanup)
                pendingExternalInstall = null
                externalInstallTimeoutJob?.cancel()
                externalInstallTimeoutJob = null
                performRootPlayStoreInstall()
            }

            is InstallerManager.InstallPlan.Mount -> {
                pendingExternalInstall?.let(installerManager::cleanup)
                pendingExternalInstall = null
                externalInstallTimeoutJob?.cancel()
                externalInstallTimeoutJob = null
                performInstall(
                    InstallType.MOUNT,
                    installAsPlayStore = plan.installAsPlayStore
                )
            }

            is InstallerManager.InstallPlan.Shizuku -> {
                pendingExternalInstall?.let(installerManager::cleanup)
                pendingExternalInstall = null
                externalInstallTimeoutJob?.cancel()
                externalInstallTimeoutJob = null
                performShizukuInstall(
                    installerPackageNameOverride = plan.installerPackageNameOverride,
                    allowAutoUninstall = automatic
                )
            }

            is InstallerManager.InstallPlan.External -> launchExternalInstaller(plan)
        }
    }

    private fun installTypeFor(target: InstallerManager.InstallTarget): InstallType = when (target) {
        InstallerManager.InstallTarget.PATCHER -> InstallType.DEFAULT
        InstallerManager.InstallTarget.SAVED_APP -> InstallType.DEFAULT
        InstallerManager.InstallTarget.MANAGER_UPDATE,
        InstallerManager.InstallTarget.LSPOSED_MODULE,
        InstallerManager.InstallTarget.DOWNLOADER_HELPER -> InstallType.DEFAULT
    }

    private fun installTypeForExternalToken(token: InstallerManager.Token): InstallType = when (token) {
        InstallerManager.Token.PlayStore -> InstallType.PLAY_STORE
        is InstallerManager.Token.Component -> InstallType.CUSTOM
        else -> InstallType.DEFAULT
    }

    private suspend fun launchExternalInstaller(plan: InstallerManager.InstallPlan.External) {
        restorePendingExternalMount()
        pendingExternalInstall?.let { installerManager.cleanup(it) }
        externalInstallTimeoutJob?.cancel()
        externalInstallTimeoutJob = null

        pendingExternalInstall = plan
        externalInstallStartTime = System.currentTimeMillis()
        val baselineInfo = pm.getPackageInfo(plan.expectedPackage)
        externalPackageWasPresentAtStart = baselineInfo != null
        externalInstallBaseline = baselineInfo?.let { info ->
            pm.getVersionCode(info) to info.lastUpdateTime
        }
        baselineInstallSignature = readInstalledSignatureBytes(plan.expectedPackage)
        expectedInstallSignature = readArchiveSignatureBytes(plan.sharedFile)
        internalInstallBaseline = null
        pendingExternalMountSuspension =
            if (plan.token == InstallerManager.Token.PlayStore) {
                suspendRootMountForPackageInstall(
                    rootInstaller = rootInstaller,
                    rootMountCoordinator = rootMountCoordinator,
                    packageName = plan.expectedPackage,
                    userId = android.os.Process.myUid() / 100_000,
                    recoveryContext = app
                )
            } else {
                null
            }
        pendingExternalMountRestored = false
        activeInstallType = if (plan.token == InstallerManager.Token.PlayStore) {
            InstallType.PLAY_STORE
        } else {
            InstallType.DEFAULT
        }
        updateInstallingState(true)
        installStatus = InstallCompletionStatus.InProgress
        pendingExternalMountSuspension?.let { suspension ->
            val installed = try {
                launchExternalInstallerWithMountFinalization(
                    context = app,
                    targetIntent = plan.intent,
                    suspendedMount = suspension,
                    installChanged = {
                        val info = pm.getPackageInfo(plan.expectedPackage)
                        info != null &&
                            (isUpdatedSinceExternalBaseline(
                                info,
                                externalInstallBaseline,
                                externalInstallStartTime
                            ) || shouldTreatAsInstalledBySignature(
                                plan.expectedPackage,
                                externalPackageWasPresentAtStart
                            ))
                    },
                    activityTimeoutMs = EXTERNAL_INSTALL_TIMEOUT_MS,
                    onRecoveryOwnershipTransferred = {
                        if (pendingExternalInstall === plan) {
                            pendingExternalInstall = null
                        }
                    },
                    cleanupFile = plan.sharedFile,
                    cleanup = {}
                )
            } catch (error: Throwable) {
                pendingExternalMountSuspension = null
                showInstallFailure(
                    app.getString(
                        R.string.install_app_fail,
                        error.simpleMessage() ?: error.javaClass.simpleName.orEmpty()
                    )
                )
                return
            }
            if (pendingExternalInstall != plan) return
            pendingExternalMountSuspension = null
            if (installed) {
                handleExternalInstallSuccess(plan.expectedPackage)
            } else {
                showInstallFailure(
                    app.getString(
                        R.string.install_app_fail,
                        app.getString(
                            R.string.installer_external_finished_no_change,
                            plan.installerLabel
                        )
                    )
                )
            }
            return
        }
        scheduleInstallTimeout(
            packageName = plan.expectedPackage,
            durationMs = EXTERNAL_INSTALL_TIMEOUT_MS,
            timeoutMessage = { app.getString(R.string.installer_external_timeout, plan.installerLabel) }
        )

        if (isInstallerX(plan) && launchedActivity == null) {
            val activityDeferred = CompletableDeferred<ActivityResult>()
            launchedActivity = activityDeferred
            val launchIntent = Intent(plan.intent).apply { removeFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            launchActivityChannel.send(launchIntent)
            monitorExternalInstall(plan)
            viewModelScope.launch {
                try {
                    activityDeferred.await()
                    delay(EXTERNAL_INSTALLER_RESULT_GRACE_MS)
                    if (pendingExternalInstall != plan) return@launch
                    val deadline = System.currentTimeMillis() + EXTERNAL_INSTALLER_POST_CLOSE_TIMEOUT_MS
                    while (pendingExternalInstall == plan && System.currentTimeMillis() < deadline) {
                        if (tryMarkInstallIfPresent(plan.expectedPackage)) return@launch
                        delay(INSTALL_MONITOR_POLL_MS)
                    }
                    if (pendingExternalInstall != plan) return@launch
                    showInstallFailure(
                        app.getString(
                            R.string.install_app_fail,
                            app.getString(R.string.installer_external_finished_no_change, plan.installerLabel)
                        )
                    )
                } finally {
                    if (launchedActivity === activityDeferred) launchedActivity = null
                }
            }
            return
        }

        try {
            ContextCompat.startActivity(app, plan.intent, null)
        } catch (error: ActivityNotFoundException) {
            installerManager.cleanup(plan)
            pendingExternalInstall = null
            updateInstallingState(false)
            externalInstallTimeoutJob = null
            showInstallFailure(
                app.getString(
                    R.string.install_app_fail,
                    error.simpleMessage() ?: error.javaClass.simpleName.orEmpty()
                )
            )
            return
        }

        monitorExternalInstall(plan)
    }

    private fun isInstallerX(plan: InstallerManager.InstallPlan.External): Boolean {
        fun normalize(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }
        val label = normalize(plan.installerLabel)
        val tokenPkg = (plan.token as? InstallerManager.Token.Component)?.componentName?.packageName.orEmpty()
        val componentPkg = plan.intent.component?.packageName.orEmpty()
        val pkg = normalize(if (tokenPkg.isNotBlank()) tokenPkg else componentPkg)
        return "installerx" in label || "installerx" in pkg || pkg.startsWith("comrosaninstaller")
    }

    private fun handleExternalInstallSuccess(packageName: String): Boolean {
        val plan = pendingExternalInstall ?: return false
        if (plan.expectedPackage != packageName) return false

        val mountRetirement = retirePendingExternalMount()
        pendingExternalInstall = null
        externalInstallTimeoutJob?.cancel()
        externalInstallTimeoutJob = null
        externalInstallBaseline = null
        externalInstallStartTime = null
        externalPackageWasPresentAtStart = false
        expectedInstallSignature = null
        baselineInstallSignature = null
        stopInstallProgressToasts()
        val customInstallerPackageName =
            (plan.token as? InstallerManager.Token.Component)
                ?.componentName
                ?.packageName
        suppressFailureAfterSuccess = true

        viewModelScope.launch {
            val finalizationJob = currentCoroutineContext()[Job]
            activeInstallJob = finalizationJob
            try {
                withContext(NonCancellable) {
                    if (!awaitExternalMountRetirement(mountRetirement)) return@withContext
                    val attributionError =
                        installerManager.tryFinalizePlayStoreAttribution(plan)
                    val installType = when (plan.token) {
                        InstallerManager.Token.PlayStore -> if (attributionError == null) {
                            InstallType.PLAY_STORE
                        } else {
                            InstallType.DEFAULT
                        }
                        is InstallerManager.Token.Component -> InstallType.CUSTOM
                        else -> InstallType.DEFAULT
                    }
                    markInstallSuccess(packageName)
                    lastSuccessInstallType = installType
                    lastSuccessAtMs = System.currentTimeMillis()
                    when (plan.target) {
                        InstallerManager.InstallTarget.PATCHER -> {
                            installedPackageName = packageName
                            val persisted = persistPatchedApp(
                                packageName,
                                installType,
                                customInstallerPackageName = customInstallerPackageName
                            )
                            if (!persisted) {
                                Log.w(
                                    TAG,
                                    "Failed to persist installed patched app metadata (external installer)"
                                )
                            }
                        }

                        InstallerManager.InstallTarget.SAVED_APP,
                        InstallerManager.InstallTarget.MANAGER_UPDATE,
                        InstallerManager.InstallTarget.LSPOSED_MODULE,
                        InstallerManager.InstallTarget.DOWNLOADER_HELPER -> Unit
                    }

                    attributionError?.let { error ->
                        Log.w(TAG, "Failed to record Play Store as the installation source", error)
                        app.toast(
                            app.getString(
                                R.string.installer_play_store_attribution_failed,
                                error.simpleMessage() ?: error.javaClass.simpleName.orEmpty()
                            )
                        )
                    }
                }
            } finally {
                installerManager.cleanup(plan)
                updateInstallingState(false)
                if (activeInstallJob === finalizationJob) activeInstallJob = null
            }
        }
        return true
    }

    fun cancelInstall() {
        if (!isInstalling) return
        if (
            pendingExternalInstall?.token == InstallerManager.Token.PlayStore &&
            pendingExternalMountSuspension != null
        ) {
            return
        }

        val cancellation = CancellationException("Installation cancelled")
        val hadActiveJob = activeInstallJob != null

        pendingExternalInstall?.let(installerManager::cleanup)
        pendingExternalInstall = null
        restorePendingExternalMountAsync()
        externalInstallTimeoutJob?.cancel()
        externalInstallTimeoutJob = null
        externalInstallPresenceJob?.cancel()
        externalInstallPresenceJob = null
        postTimeoutGraceJob?.cancel()
        postTimeoutGraceJob = null
        pendingActivityResumeFallback?.cancel()
        pendingActivityResumeFallback = null
        externalInstallStartTime = null
        externalPackageWasPresentAtStart = false
        launchedActivity?.cancel(cancellation)
        launchedActivity = null

        activeInstallJob?.cancel(cancellation)
        if (!hadActiveJob) {
            installStatus = null
            installFailureMessage = null
            updateInstallingState(false)
        }
    }

    override fun install() {
        if (isInstalling) return
        rootMountDiagnosticsContext = null
        if (usingMountInstall) {
            installWithToken(InstallerManager.Token.AutoSaved)
            return
        }
        input.profileInstallerToken?.takeIf {
            shouldApplyProfileInstallerPreference(
                chooseInstallerPerInstall = prefs.chooseInstallerPerInstall.getBlocking(),
                installerMatchesPatchMode = hasProfileInstallerPreference
            )
        }?.let { storedToken ->
            installWithToken(
                installerManager.withPlayStoreSource(
                    installerManager.parseToken(storedToken),
                    prefs.shizukuInstallAsPlayStore.getBlocking()
                )
            )
            return
        }
        viewModelScope.launch {
            runCatching {
                val expectedPackage = pm.getPackageInfo(outputFile)?.packageName ?: packageName
                Log.d(TAG, "install() requested, expected=$expectedPackage, outputExists=${outputFile.exists()}")
                val plan = installerManager.resolvePlan(
                    InstallerManager.InstallTarget.PATCHER,
                    outputFile,
                    expectedPackage,
                    null,
                    allowMount = usingMountInstall && expectedPackage == packageName
                )
                Log.d(TAG, "install() resolved plan=${plan::class.java.simpleName}")
                if (plan !is InstallerManager.InstallPlan.Mount &&
                    hasSignatureMismatch(expectedPackage, outputFile)
                ) {
                    showSignatureMismatchPrompt(expectedPackage, plan)
                    return@runCatching
                }
                recordInstallPlan(plan, expectedPackage, null)
                executeInstallPlan(plan)
            }.onFailure { error ->
                if (error is CancellationException) return@onFailure
                Log.e(TAG, "install() failed to start", error)
                showInstallFailure(
                    app.getString(
                        R.string.install_app_fail,
                        error.simpleMessage() ?: error.javaClass.simpleName.orEmpty()
                    ),
                    allowFallback = !usingMountInstall
                )
            }
        }
    }

    fun maybeAutoInstall() {
        if (autoInstallTriggered) return
        val token = automaticInstallerToken() ?: return
        autoInstallTriggered = true
        installWithToken(token, automatic = true)
    }

    private fun automaticInstallerToken(): InstallerManager.Token? {
        val chooseInstallerPerInstall = prefs.chooseInstallerPerInstall.getBlocking()
        val profileInstallerToken = input.profileInstallerToken
        return when {
            input.autoInstall &&
                profileInstallerToken != null &&
                shouldApplyProfileInstallerPreference(
                    chooseInstallerPerInstall = chooseInstallerPerInstall,
                    autoInstall = input.autoInstall,
                    installerMatchesPatchMode = hasProfileInstallerPreference
                ) ->
                installerManager.withPlayStoreSource(
                    installerManager.parseToken(profileInstallerToken),
                    prefs.shizukuInstallAsPlayStore.getBlocking()
                )
            !usingMountInstall && prefs.autoInstallWithShizuku.getBlocking() -> {
                val primary = installerManager.getPrimaryToken()
                primary.takeIf(installerManager::isShizukuToken)
            }
            else -> null
        }
    }

    private suspend fun completeSignatureWorkflow(): Boolean {
        mutableSignatureWorkflowProgress.update {
            SignatureMetadataWorkflowProgress(running = true, logSessionId = it.logSessionId + 1L)
                .appendLog("Started signature metadata injection")
        }
        signatureWorkflowRunning = true
        try {
            CacheCleanupGuard.withCacheInUse {
                if (!signatureWorkflowCompleted) {
                    injectSourceSignatureIntoPatchedOutput()
                }
            }
            mutableSignatureWorkflowProgress.update { it.copy(completed = true) }
            appendSignatureWorkflowLog("Signature metadata injection complete")
            return true
        } catch (cancelled: CancellationException) {
            appendSignatureWorkflowLog("Signature metadata injection cancelled")
            if (input.injectSignatureMetadata) _patcherSucceeded.value = false
            mutableSignatureWorkflowProgress.update {
                it.copy(error = app.getString(R.string.tools_signature_metadata_injector_cancelled))
            }
            throw cancelled
        } catch (error: Exception) {
            Log.e(TAG, "Signature metadata workflow failed", error)
            appendSignatureWorkflowLog("Signature metadata injection failed: ${error.simpleMessage()}")
            mutableSignatureWorkflowProgress.update {
                it.copy(error = error.simpleMessage()
                    ?: app.getString(R.string.tools_signature_metadata_injector_failed))
            }
            return false
        } finally {
            signatureMetadataSource.delete()
            signatureWorkflowRunning = false
            mutableSignatureWorkflowProgress.update { it.copy(running = false) }
            signatureWorkflowJob = null
        }
    }

    private fun appendSignatureWorkflowLog(message: String) {
        mutableSignatureWorkflowProgress.update { it.appendLog(message) }
        logger.info("Signature metadata: $message")
    }

    private fun originalSignatureSource(): File? = sequenceOf(
        patchedRepatchSourcePath?.let { SharedApkFiles.resolve(File(it)) },
        inputFile,
        (input.selectedApp as? SelectedApp.Local)?.file
    ).filterNotNull().map(SharedApkFiles::resolve).firstOrNull { it.isFile && it.length() > 0L }

    private suspend fun hasOriginalSignatureMetadata(): Boolean {
        val source = originalSignatureSource() ?: return false
        return try {
            signatureMetadataInjector.analyzeSignatureSource(source)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun injectSourceSignatureIntoPatchedOutput() {
        val source = signatureMetadataSource
        check(source.isFile && source.length() > 0L) {
            app.getString(R.string.patcher_signature_workflow_source_missing)
        }
        check(outputFile.isFile && outputFile.length() > 0L) {
            app.getString(R.string.patcher_signature_workflow_output_missing)
        }
        appendSignatureWorkflowLog("Using original signature metadata cached before patching")
        val injected = tempDir.resolve("signature-injected.apk")
        val backup = tempDir.resolve("output-before-signature.apk")
        injected.delete()
        backup.delete()
        try {
            signatureMetadataInjector.inject(
                signatureSource = source,
                targetApk = outputFile,
                outputApk = injected,
                mode = SignatureMetadataInjectionMode.REPLACE_EXISTING,
                signingMode = SignatureMetadataSigningMode.APPLY_SUPPLIED_SIGNATURE,
                onProgress = { progress ->
                    mutableSignatureWorkflowProgress.update { it.copy(stage = progress.stage) }
                },
                onLog = ::appendSignatureWorkflowLog
            )
            currentCoroutineContext().ensureActive()
            // Once replacement starts, keep the file and its completion state consistent.
            withContext(NonCancellable) {
                withContext(Dispatchers.IO) {
                    Files.copy(outputFile.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    try {
                        Files.move(
                            injected.toPath(),
                            outputFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING
                        )
                        backup.delete()
                    } catch (error: Exception) {
                        Files.copy(
                            backup.toPath(),
                            outputFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING
                        )
                        backup.delete()
                        throw error
                    }
                }
                // The saved or installed APK still contains the previous output.
                savedPatchedApp = false
                installedPackageName = null
                installStatus = null
                installFailureMessage = null
                packageInstallerStatus = null
                suppressFailureAfterSuccess = false
                lastSuccessInstallType = null
                lastSuccessAtMs = 0L
                signatureWorkflowCompleted = true
            }
            refreshExportMetadata()
        } finally {
            injected.delete()
        }
    }

    fun installWithToken(token: InstallerManager.Token, automatic: Boolean = false) {
        installWithTokenInternal(
            token = token,
            automatic = automatic,
            allowModeOverride = false
        )
    }

    fun installWithSelectedToken(token: InstallerManager.Token) {
        val crossModeMountRequested =
            installerManager.baseInstallerToken(token) == InstallerManager.Token.AutoSaved &&
                !usingMountInstall
        if (!crossModeMountRequested) {
            installWithTokenInternal(
                token = token,
                automatic = false,
                allowModeOverride = false
            )
            return
        }
        if (isInstalling) return
        viewModelScope.launch {
            supportsRootMountModeOverride = withContext(Dispatchers.IO) {
                try {
                    canSelectRootMountForPatchedOutput()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "Failed to validate Rooted Mount compatibility", error)
                    false
                }
            }
            installWithTokenInternal(
                token = token,
                automatic = false,
                allowModeOverride = true
            )
        }
    }

    private fun installWithTokenInternal(
        token: InstallerManager.Token,
        automatic: Boolean,
        allowModeOverride: Boolean
    ) {
        if (isInstalling) return
        rootMountDiagnosticsContext = null
        val requestedMount =
            installerManager.baseInstallerToken(token) == InstallerManager.Token.AutoSaved
        val attemptedInstallType = if (requestedMount) InstallType.MOUNT else null
        val tokenAllowed = if (allowModeOverride) {
            isInstallerTokenSelectable(token)
        } else {
            isInstallerTokenAllowed(token)
        }
        if (!tokenAllowed) {
            showInstallFailure(
                app.getString(R.string.installer_patch_mode_mismatch),
                allowFallback = false,
                attemptedInstallType = attemptedInstallType
            )
            return
        }
        viewModelScope.launch {
            runCatching {
                val expectedPackage = pm.getPackageInfo(outputFile)?.packageName ?: packageName
                check(!requestedMount || expectedPackage == packageName) {
                    app.getString(R.string.root_mount_renamed_package_not_supported)
                }
                Log.d(TAG, "installWithToken() requested, token=$token, expected=$expectedPackage, outputExists=${outputFile.exists()}")
                val plan = installerManager.resolvePlanForToken(
                    token = token,
                    target = InstallerManager.InstallTarget.PATCHER,
                    sourceFile = outputFile,
                    expectedPackage = expectedPackage,
                    sourceLabel = null,
                    allowMount = requestedMount && expectedPackage == packageName
                ) ?: throw IllegalStateException("Selected installer is unavailable")
                Log.d(TAG, "installWithToken() resolved plan=${plan::class.java.simpleName}")
                if (plan !is InstallerManager.InstallPlan.Mount &&
                    hasSignatureMismatch(expectedPackage, outputFile)
                ) {
                    if (!tryAutoUninstallSignatureConflict(
                            expectedPackage,
                            plan,
                            automatic
                        )
                    ) {
                        showSignatureMismatchPrompt(expectedPackage, plan)
                        return@runCatching
                    }
                }
                recordInstallPlan(plan, expectedPackage, null)
                executeInstallPlan(plan, automatic)
            }.onFailure { error ->
                if (error is CancellationException) return@onFailure
                Log.e(TAG, "installWithToken() failed to start", error)
                showInstallFailure(
                    app.getString(
                        R.string.install_app_fail,
                        error.simpleMessage() ?: error.javaClass.simpleName.orEmpty()
                    ),
                    allowFallback = !requestedMount,
                    attemptedInstallType = attemptedInstallType
                )
            }
        }
    }

    // Code adapted from Morphe, see third-party/NOTICE for more information
    // https://github.com/MorpheApp/morphe-manager/pull/734
    private suspend fun tryAutoUninstallSignatureConflict(
        packageName: String,
        plan: InstallerManager.InstallPlan? = null,
        automatic: Boolean = false
    ): Boolean {
        if (!automatic || !prefs.autoUninstallWithShizuku.get()) return false
        if (plan != null && plan !is InstallerManager.InstallPlan.Shizuku) return false
        if (!installerManager.shizukuStatus(InstallerManager.InstallTarget.PATCHER).availability.available) {
            return false
        }
        val downgradeWouldOccur = withContext(Dispatchers.IO) {
            val installedPackage = pm.getPackageInfo(packageName)
            val patchedPackage = pm.getPackageInfo(outputFile)
            installedPackage != null &&
                patchedPackage != null &&
                pm.getVersionCode(patchedPackage) < pm.getVersionCode(installedPackage)
        }
        if (downgradeWouldOccur) return false

        return runCatching {
            installerManager.uninstallWithShizuku(packageName)
            waitUntilPackageRemoved(packageName)
        }.onFailure {
            Log.w(TAG, "Shizuku auto-uninstall failed for $packageName", it)
        }.getOrDefault(false)
    }

    private suspend fun waitUntilPackageRemoved(packageName: String): Boolean {
        val deadline = System.currentTimeMillis() + SHIZUKU_UNINSTALL_VERIFY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (withContext(Dispatchers.IO) { pm.getPackageInfo(packageName) == null }) {
                return true
            }
            delay(SHIZUKU_UNINSTALL_VERIFY_POLL_MS)
        }
        return withContext(Dispatchers.IO) { pm.getPackageInfo(packageName) == null }
    }

    override fun reinstall() {
        if (isInstalling) return
        viewModelScope.launch {
            val expectedPackage = pm.getPackageInfo(outputFile)?.packageName ?: packageName
            val plan = installerManager.resolvePlan(
                InstallerManager.InstallTarget.PATCHER,
                outputFile,
                expectedPackage,
                null,
                    allowMount = usingMountInstall && expectedPackage == packageName
            )
            recordInstallPlan(plan, expectedPackage, null)
            when (plan) {
                is InstallerManager.InstallPlan.Internal -> {
                    pendingExternalInstall?.let(installerManager::cleanup)
                    pendingExternalInstall = null
                    externalInstallTimeoutJob?.cancel()
                    externalInstallTimeoutJob = null
                    try {
                        val pkg = pm.getPackageInfo(outputFile)?.packageName
                            ?: throw Exception("Failed to load application info")
                        when (val result = pm.uninstallPackage(pkg)) {
                            is Session.State.Failed<UninstallFailure> -> {
                                val message = result.failure.message.orEmpty()
                                handleUninstallFailure(
                                    app.getString(R.string.uninstall_app_fail, message)
                                )
                            }

                            Session.State.Succeeded -> {
                                performInstall(InstallType.DEFAULT)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to reinstall", e)
                        app.toast(app.getString(R.string.reinstall_app_fail, e.simpleMessage()))
                    }
                }
                is InstallerManager.InstallPlan.RootPlayStore -> {
                    pendingExternalInstall?.let(installerManager::cleanup)
                    pendingExternalInstall = null
                    externalInstallTimeoutJob?.cancel()
                    externalInstallTimeoutJob = null
                    performRootPlayStoreInstall()
                }
                is InstallerManager.InstallPlan.Mount -> {
                    pendingExternalInstall?.let(installerManager::cleanup)
                    pendingExternalInstall = null
                    externalInstallTimeoutJob?.cancel()
                    externalInstallTimeoutJob = null
                    performInstall(
                        InstallType.MOUNT,
                        installAsPlayStore = plan.installAsPlayStore
                    )
                }
                is InstallerManager.InstallPlan.Shizuku -> {
                    pendingExternalInstall?.let(installerManager::cleanup)
                    pendingExternalInstall = null
                    externalInstallTimeoutJob?.cancel()
                    externalInstallTimeoutJob = null
                    performShizukuInstall(plan.installerPackageNameOverride)
                }
                is InstallerManager.InstallPlan.External -> launchExternalInstaller(plan)
            }
        }
    }

    fun dismissPackageInstallerDialog() {
        packageInstallerStatus = null
    }

    fun dismissSignatureMismatchPrompt() {
        signatureMismatchPackage = null
        pendingSignatureMismatchPlan = null
        pendingSignatureMismatchPackage = null
    }

    fun confirmSignatureMismatchInstall() {
        val targetPackage = pendingSignatureMismatchPackage ?: return
        val plan = pendingSignatureMismatchPlan ?: return
        signatureMismatchPackage = null
        pendingSignatureMismatchPackage = null
        pendingSignatureMismatchPlan = null
        stopInstallProgressToasts()
        deferUninstallProgressToasts = true
        startUninstallProgressToasts()
        viewModelScope.launch {
            var suspendedMount: RootMountSuspension? = null
            try {
                if (plan is InstallerManager.InstallPlan.RootPlayStore) {
                    suspendedMount = suspendRootMountForPackageInstall(
                        rootInstaller = rootInstaller,
                        rootMountCoordinator = rootMountCoordinator,
                        packageName = targetPackage,
                        userId = android.os.Process.myUid() / 100_000
                    )
                }

                val session = ackpineUninstaller.createSession(targetPackage) {
                    confirmation = Confirmation.IMMEDIATE
                }
                val toastJob = launchUninstallConfirmationToast(session)
                val result = try {
                    withContext(Dispatchers.IO) {
                        session.await()
                    }
                } finally {
                    toastJob.cancel()
                }
                when (result) {
                    is Session.State.Failed<UninstallFailure> -> {
                        val mount = suspendedMount
                        suspendedMount = null
                        if (mount != null) {
                            withContext(NonCancellable) { mount.restore() }
                        }
                        stopUninstallProgressToasts()
                        if (result.failure is UninstallFailure.Aborted) {
                            updateInstallingState(false)
                            return@launch
                        }
                        val message = result.failure.message.orEmpty()
                        handleUninstallFailure(app.getString(R.string.uninstall_app_fail, message))
                    }

                    Session.State.Succeeded -> {
                        val retryMountSuspension = suspendedMount
                        suspendedMount = null
                        stopUninstallProgressToasts()
                        recordInstallPlan(plan, targetPackage, null)
                        if (plan is InstallerManager.InstallPlan.RootPlayStore) {
                            performRootPlayStoreInstall(retryMountSuspension)
                        } else {
                            executeInstallPlan(plan)
                        }
                    }
                }
            } catch (error: Throwable) {
                val mount = suspendedMount
                if (mount != null) {
                    val restoreError = withContext(NonCancellable) {
                        runCatching { mount.restore() }.exceptionOrNull()
                    }
                    restoreError?.let(error::addSuppressed)
                }
                stopUninstallProgressToasts()
                handleUninstallFailure(
                    app.getString(R.string.uninstall_app_fail, error.simpleMessage().orEmpty())
                )
            }
        }
    }

    fun shouldSuppressPackageInstallerDialog(): Boolean {
        if (activeInstallType == InstallType.SHIZUKU ||
            activeInstallType == InstallType.SHIZUKU_PLAY_STORE
        ) return true
        val lastType = lastSuccessInstallType
        if (lastType != InstallType.SHIZUKU &&
            lastType != InstallType.SHIZUKU_PLAY_STORE
        ) return false
        val now = System.currentTimeMillis()
        return now - lastSuccessAtMs < SUPPRESS_FAILURE_AFTER_SUCCESS_MS
    }

    private fun clearInstallFailureUiState() {
        installFailureMessage = null
        packageInstallerStatus = null
        installStatus = null
        pendingInstallFailureMessage = null
    }

    fun dismissInstallFailureMessage() {
        clearInstallFailureUiState()
        rootMountDiagnosticsContext = null
    }

    fun dismissInstallFailureMessageForRootDiagnostics() {
        clearInstallFailureUiState()
        updateRootMountDiagnosticsFlowActive(rootMountDiagnosticsContext != null)
    }

    fun shouldSuppressInstallFailureDialog(): Boolean {
        if (activeInstallType == InstallType.SHIZUKU ||
            activeInstallType == InstallType.SHIZUKU_PLAY_STORE
        ) return true
        val lastType = lastSuccessInstallType
        if (lastType != InstallType.SHIZUKU &&
            lastType != InstallType.SHIZUKU_PLAY_STORE
        ) return false
        val now = System.currentTimeMillis()
        return now - lastSuccessAtMs < SUPPRESS_FAILURE_AFTER_SUCCESS_MS
    }

    fun clearInstallStatus() {
        installStatus = null
    }

    fun clearRootMountRecoveryMessage() {
        rootMountRecoveryMessage = null
        rootMountDiagnosticsContext = null
    }

    fun clearRootMountRecoveryMessageForDiagnostics() {
        rootMountRecoveryMessage = null
        updateRootMountDiagnosticsFlowActive(rootMountDiagnosticsContext != null)
    }

    fun clearRootMountDiagnosticsContext() {
        rootMountDiagnosticsContext = null
    }

    fun confirmFallbackInstallPrompt() {
        val prompt = fallbackInstallPrompt ?: return
        val expectedPackage = lastInstallExpectedPackage ?: packageName
        val plan = installerManager.resolvePlanForToken(
            token = prompt.fallbackToken,
            target = prompt.target,
            sourceFile = outputFile,
            expectedPackage = expectedPackage,
            sourceLabel = lastInstallSourceLabel,
            allowMount = usingMountInstall && expectedPackage == packageName
        )
        fallbackInstallPrompt = null
        pendingInstallFailureMessage = null
        installFailureMessage = null
        installStatus = null
        if (plan == null) {
            val message = app.getString(R.string.installer_hint_generic)
            applyInstallFailure(message)
            return
        }
        recordInstallPlan(plan, expectedPackage, lastInstallSourceLabel)
        viewModelScope.launch {
            executeInstallPlan(plan)
        }
    }

    fun dismissFallbackInstallPrompt() {
        val message = pendingInstallFailureMessage
        fallbackInstallPrompt = null
        pendingInstallFailureMessage = null
        installFailureMessage = null
        installStatus = null
        if (message != null) {
            applyInstallFailure(message)
        }
    }

    data class FallbackInstallPrompt(
        val failureMessage: String,
        val fallbackLabel: String,
        val fallbackToken: InstallerManager.Token,
        val target: InstallerManager.InstallTarget
    )

    sealed class InstallCompletionStatus {
        data object InProgress : InstallCompletionStatus()
        data class Success(val packageName: String?) : InstallCompletionStatus()
        data class Failure(val message: String) : InstallCompletionStatus()
    }

    private suspend fun launchWorker(
        selectedPatches: PatchSelection,
        options: Options
    ): UUID = workerRepository.launchExpedited<PatcherWorker, PatcherWorker.Args>(
        PatcherWorker.UNIQUE_WORK_NAME,
        buildWorkerArgs(selectedPatches, options)
    )

    private suspend fun handleDownloaderActivityRequest(
        plugin: LoadedDownloaderPlugin,
        intent: Intent
    ): ActivityResult = withContext(Dispatchers.Main) {
        activityRequestMutex.withLock {
            val request = ActivityPromptRequest(
                completion = CompletableDeferred(),
                dialogState = ActivityPromptDialogState(
                    title = plugin.shortDisplayName,
                    requestId = nextActivityPromptRequestId++
                )
            )
            try {
                currentActivityRequest = request
                val accepted = try {
                    request.completion.await()
                } finally {
                    if (currentActivityRequest === request) {
                        currentActivityRequest = null
                    }
                }
                delay(DOWNLOADER_DIALOG_SETTLE_MS)
                if (!accepted) throw UserInteractionException.RequestDenied()

                try {
                    with(CompletableDeferred<ActivityResult>()) {
                        launchedActivity = this
                        launchActivityChannel.send(intent)
                        await()
                    }
                } finally {
                    launchedActivity = null
                }
            } finally {
                if (currentActivityRequest === request) {
                    currentActivityRequest = null
                }
            }
        }
    }

    private fun buildWorkerArgs(
        selectedPatches: PatchSelection,
        options: Options
    ): PatcherWorker.Args {
        val selectedForRun = when (val selected = input.selectedApp) {
            is SelectedApp.Local -> {
                val reuseFile = inputFile ?: selected.file
                val temporary = if (forceKeepLocalInput) false else selected.temporary
                selected.copy(file = reuseFile, temporary = temporary)
            }

            else -> selected
        }

        val shouldPreserveInput =
            selectedForRun is SelectedApp.Local && (selectedForRun.temporary || forceKeepLocalInput)
        val resolvedPreparedInput = preparedInput
        val resolvedSplitSelection = selectedSplitConfiguration
        preparedInput = null
        preparedInputIncludesDownload = false
        selectedSplitConfiguration = null

        return PatcherWorker.Args(
            selectedForRun,
            outputFile.path,
            selectedPatches,
            options,
            skipApkSigning,
            logger,
            preparedInput = resolvedPreparedInput,
            splitSelection = resolvedSplitSelection,
            signatureMetadataOutput = signatureMetadataSource.path.takeIf {
                input.injectSignatureMetadata
            },
            setInputFile = { file, needsSplit, merged ->
                val storedFile = if (shouldPreserveInput) {
                    val existing = inputFile
                    if (existing?.exists() == true) {
                        existing
                    } else withContext(Dispatchers.IO) {
                        val destination = File(fs.tempDir, "input-${System.currentTimeMillis()}.apk")
                        file.copyTo(destination, overwrite = true)
                        destination
                    }
                } else file

                withContext(Dispatchers.Main) {
                    inputFile = storedFile
                    updateSplitStepRequirement(storedFile, needsSplit, merged)
                }
            },
            handleStartActivityRequest = ::handleDownloaderActivityRequest,
            onEvent = ::handleProgressEvent,
            setInputMetadata = { resolvedVersion, resolvedVersionCode ->
                withContext(Dispatchers.Main) {
                    informationAppVersion = resolvedVersion ?: version
                    informationAppVersionCode = resolvedVersionCode ?: versionCode
                }
            }
        )
    }

    private fun handleProgressEvent(update: PatcherWorkerProgressUpdate) {
        if (update.isMemorySample) {
            viewModelScope.launch {
                recordPatcherMemoryUsage(
                    generation = update.generation,
                    sequence = update.sequence,
                    memoryUsage = update.memoryUsage
                )
            }
        }
        update.event?.let { event ->
            enqueueWorkerProgressEvent(
                generation = update.generation,
                sequence = update.sequence,
                event = event,
                notificationProgressCurrent = update.notificationProgressCurrent,
                notificationProgressMax = update.notificationProgressMax,
                patchExecutionOrder = update.patchExecutionOrder
            )
        }
    }

    private fun recordPatcherMemoryUsage(
        generation: Long,
        sequence: Long,
        memoryUsage: PatcherMemoryUsage?
    ) {
        memoryUsage ?: return
        if (patcherMemoryUsageGeneration > generation) return
        if (
            patcherMemoryUsageGeneration == generation &&
            sequence <= patcherMemoryUsageSequence
        ) {
            return
        }
        val normalized = memoryUsage.copy(
            usedMb = memoryUsage.usedMb.coerceAtLeast(0L),
            maxMb = memoryUsage.maxMb.coerceAtLeast(1L),
            requestedMaxMb = memoryUsage.requestedMaxMb.coerceAtLeast(1L)
        )
        if (patcherMemoryUsageGeneration != generation) {
            patcherMemoryUsageGeneration = generation
            patcherMemoryUsageSequence = Long.MIN_VALUE
            patcherMemoryUsageSampleTimeMs = Long.MIN_VALUE
            patcherMemoryUsageSamples.clear()
        }
        if (normalized.sampledAtElapsedRealtimeMs <= patcherMemoryUsageSampleTimeMs) return
        patcherMemoryUsageSequence = sequence
        patcherMemoryUsageSampleTimeMs = normalized.sampledAtElapsedRealtimeMs
        patcherMemoryUsageSamples.add(normalized)
    }

    private fun enqueueWorkerProgressEvent(
        generation: Long,
        sequence: Long,
        event: ProgressEvent,
        notificationProgressCurrent: Int?,
        notificationProgressMax: Int?,
        failedPatchIndexes: Set<Int> = emptySet(),
        patchExecutionOrder: List<Int> = emptyList(),
        seedFromWorkerSnapshot: Boolean = false
    ) = viewModelScope.launch {
        progressEventMutex.withLock {
            if (!shouldApplyWorkerProgress(generation, sequence)) return@withLock
            recordAppliedWorkerProgress(generation, sequence)
            progressState.applyPatchExecutionOrder(patchExecutionOrder)
            if (seedFromWorkerSnapshot) {
                seedVisualProgressFromWorkerProgress(
                    current = notificationProgressCurrent,
                    max = notificationProgressMax
                )
                seedProgressStateFromWorkerSnapshot(event, failedPatchIndexes)
            }
            progressState.processProgressEventLocked(event)
        }
    }

    private fun enqueueProgressEvent(
        event: ProgressEvent,
        seedFromWorkerSnapshot: Boolean = false
    ) = viewModelScope.launch {
        progressEventMutex.withLock {
            if (seedFromWorkerSnapshot) {
                seedProgressStateFromWorkerSnapshot(event)
            }
            progressState.processProgressEventLocked(event)
        }
    }

    private fun handleVisualFailure(event: ProgressEvent.Failed, isDuplicateFailureWrapper: Boolean) {
        val stepName = event.stepId?.let { it::class.java.simpleName } ?: "Unknown"
        val shouldRecordFailure = !isDuplicateFailureWrapper
        if (shouldRecordFailure) {
            lastPatchFailure = event.error
            lastPatchFailureStep = stepName
        }
        val shouldLogStandaloneFailure =
            shouldRecordFailure && event.stepId !is StepId.ExecutePatch
        if (shouldLogStandaloneFailure && shouldLogFailure(event.error)) {
            val message = event.error.message ?: event.error.type
            logger.error("Failure in step=$stepName: $message")
            logger.error(event.error.stackTrace)
        }
        handleKeystoreMissing(event.error)
    }

    private fun resetFailureLogState() {
        progressState.clearFailure()
        lastLoggedErrorSignature = null
        lastPatchFailure = null
        lastPatchFailureStep = null
    }

    private fun RemoteError.matchesUnderlyingFailure(other: RemoteError): Boolean =
        this == other || (
            stackTrace.isNotBlank() &&
                stackTrace == other.stackTrace
        )

    private fun conciseFailureMessage(error: RemoteError): String = (
        error.message
            ?.lineSequence()
            ?.firstOrNull(String::isNotBlank)
            ?: error.stackTrace.lineSequence().firstOrNull(String::isNotBlank)
            ?: error.type
        ).trim().take(FAILURE_LOG_SUMMARY_CHAR_LIMIT)

    private fun matchesBoundedLogMessage(loggedMessage: String, sourceMessage: String): Boolean {
        if (loggedMessage == sourceMessage) return true
        if (sourceMessage.length <= PATCHER_LOG_MESSAGE_CHAR_LIMIT) return false
        return loggedMessage.startsWith(sourceMessage.take(PATCHER_LOG_MESSAGE_CHAR_LIMIT)) &&
            loggedMessage.endsWith(
                "[log message truncated to $PATCHER_LOG_MESSAGE_CHAR_LIMIT characters]"
            )
    }

    private fun shouldLogFailure(error: app.universal.patch.manager.patcher.RemoteError): Boolean {
        val signature = listOf(error.type, error.message, error.stackTrace).joinToString("|")
        if (signature == lastLoggedErrorSignature) return false
        lastLoggedErrorSignature = signature
        return true
    }

    private fun formatDisplayedFailure(error: app.universal.patch.manager.patcher.RemoteError): String {
        if (error.type.contains("UserInteractionException")) {
            return error.message ?: "Downloader search cancelled by user."
        }
        return error.stackTrace
    }

    private fun handleKeystoreMissing(error: app.universal.patch.manager.patcher.RemoteError) {
        if (keystoreMissingDialog) return
        val needle = "Keystore missing"
        val messageMatch = error.message?.contains(needle, ignoreCase = true) == true
        val stackMatch = error.stackTrace.contains(needle, ignoreCase = true)
        if (messageMatch || stackMatch) {
            keystoreMissingDialog = true
        }
    }

    private fun isReseamSelection(): Boolean =
        patcherSessionInfo.bundleType?.let { it == PatchBundleType.RESEAM.name }
            ?: (selectionBundleType == PatchBundleType.RESEAM)

    private fun isMorpheSelection(): Boolean =
        patcherSessionInfo.bundleType?.let { it == PatchBundleType.MORPHE.name }
            ?: (selectionBundleType == PatchBundleType.MORPHE)

    private fun observeWorker(id: UUID) {
        val source = workManager.getWorkInfoByIdLiveData(id)
        currentWorkSource?.let {
            _patcherSucceeded.removeSource(it)
            _isPatchingActive.removeSource(it)
        }
        currentWorkSource = source
        _patcherSucceeded.addSource(source) { workInfo ->
            when (workInfo?.state) {
                WorkInfo.State.RUNNING,
                WorkInfo.State.ENQUEUED,
                WorkInfo.State.BLOCKED -> replayWorkerProgressSnapshot(
                    workInfo,
                    enabled = replayWorkerProgressSnapshots
                )
                else -> Unit
            }
            val progressActive =
                workInfo?.progress?.getBoolean(PatcherWorker.PATCHING_ACTIVE_KEY, false) == true
            _isPatchingActive.value = when (workInfo?.state) {
                WorkInfo.State.SUCCEEDED,
                WorkInfo.State.FAILED,
                WorkInfo.State.CANCELLED -> false
                WorkInfo.State.RUNNING,
                WorkInfo.State.ENQUEUED,
                WorkInfo.State.BLOCKED -> true
                else -> progressActive
            }
            when (workInfo?.state) {
                WorkInfo.State.SUCCEEDED -> {
                    if (signatureWorkflowRunning ||
                        (signatureWorkflowCompleted && _patcherSucceeded.value == true)
                    ) return@addSource
                    replayWorkerProgressSnapshots = false
                    workerRepository.clearActiveProgressSnapshot(id)
                    stopPatchingTaskMonitor()
                    clearPendingActivityInteractions()
                    clearPatchingNotification()
                    forceKeepLocalInput = false
                    patchedSourceVersionName = workInfo.outputData
                        .getString(PatcherWorker.INPUT_VERSION_NAME_KEY)
                        ?.takeIf(String::isNotBlank)
                    patchedSourceVersionCode = if (
                        workInfo.outputData.keyValueMap.containsKey(PatcherWorker.INPUT_VERSION_CODE_KEY)
                    ) {
                        workInfo.outputData.getLong(PatcherWorker.INPUT_VERSION_CODE_KEY, 0L)
                    } else {
                        null
                    }
                    informationAppVersion = patchedSourceVersionName ?: version
                    informationAppVersionCode = patchedSourceVersionCode ?: versionCode
                    patchedRepatchSourcePath = workInfo.outputData
                        .getString(PatcherWorker.REPATCH_SOURCE_PATH_KEY)
                        ?.takeIf(String::isNotBlank)
                    patcherWorkerId = null
                    if (requiresSplitPreparation) {
                        updateSplitStepRequirement(
                            file = null,
                            needsSplitOverride = requiresSplitPreparation,
                            merged = true
                        )
                    }
                    val failedPatchIndexes = workInfo.outputData
                        .getIntArray(PatcherWorker.FAILED_PATCH_INDEXES_KEY)
                        ?.toSet()
                        .orEmpty()
                    completedPatchHadFailures = failedPatchIndexes.isNotEmpty()
                    reconcileFailedPatchIndexes(failedPatchIndexes)
                    resetFailureLogState()
                    progressState.reconcileProgressStateAfterSuccess()
                    refreshExportMetadata()
                    // Code adapted from Morphe, see third-party/NOTICE for more information
                    // https://github.com/MorpheApp/morphe-manager/pull/779
                    val patchedPackageInfo = pm.getPackageInfo(outputFile)
                    supportsRootMount = patchedPackageInfo?.packageName == packageName &&
                        !SplitApkPreparer.isSplitArchive(outputFile)
                    supportsRootMountModeOverride = false
                    if (input.injectSignatureMetadata) {
                        _patcherSucceeded.value = null
                        signatureWorkflowRunning = true
                        signatureWorkflowJob = viewModelScope.launch {
                            _patcherSucceeded.value = completeSignatureWorkflow()
                            if (_patcherSucceeded.value == true) {
                                refreshRootMountModeOverrideAsync()
                            }
                        }
                    } else {
                        _patcherSucceeded.value = true
                        refreshRootMountModeOverrideAsync()
                    }
                }

                WorkInfo.State.FAILED -> {
                    replayWorkerProgressSnapshots = false
                    workerRepository.clearActiveProgressSnapshot(id)
                    patcherWorkerId = null
                    stopPatchingTaskMonitor()
                    clearPendingActivityInteractions()
                    clearPatchingNotification()
                    handleWorkerFailure(workInfo)
                    _patcherSucceeded.value = false
                }

                WorkInfo.State.RUNNING,
                WorkInfo.State.ENQUEUED,
                WorkInfo.State.BLOCKED -> _patcherSucceeded.value = null
                WorkInfo.State.CANCELLED -> {
                    replayWorkerProgressSnapshots = false
                    workerRepository.clearActiveProgressSnapshot(id)
                    patcherWorkerId = null
                    stopPatchingTaskMonitor()
                    clearPendingActivityInteractions()
                    clearPatchingNotification()
                    progressState.reconcileFailureState(
                        failureMessage = workInfo.outputData.getString(PatcherWorker.PROCESS_FAILURE_MESSAGE_KEY)
                            ?: "Patching was cancelled."
                    )
                    _patcherSucceeded.value = null
                }
                else -> _patcherSucceeded.value = null
            }
        }
    }

    private suspend fun syncWorkerProgressFromCurrentSnapshot() {
        val workerId = patcherWorkerId?.uuid ?: return
        val workInfo = withContext(Dispatchers.IO) {
            runCatching { workManager.getWorkInfoById(workerId).get() }.getOrNull()
        } ?: currentWorkSource?.value ?: return

        when (workInfo.state) {
            WorkInfo.State.RUNNING,
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.BLOCKED -> replayWorkerProgressSnapshot(workInfo, enabled = true)
            else -> Unit
        }
    }

    private fun replayWorkerProgressSnapshot(workInfo: WorkInfo, enabled: Boolean) {
        if (!enabled) return

        val workerId = patcherWorkerId?.uuid
        val persistedSnapshot = PatcherWorkerProgressState.fromWorkData(workInfo.progress)
        val snapshot = when {
            workerId == null -> persistedSnapshot
            persistedSnapshot == null -> workerRepository.activeProgressSnapshot(workerId)
            else -> {
                val inMemorySnapshot = workerRepository.activeProgressSnapshot(workerId)
                when {
                    inMemorySnapshot == null -> persistedSnapshot
                    isNewerWorkerSnapshot(inMemorySnapshot, persistedSnapshot) -> inMemorySnapshot
                    else -> persistedSnapshot
                }
            }
        } ?: return
        recordPatcherMemoryUsage(
            generation = snapshot.generation,
            sequence = snapshot.sequence,
            memoryUsage = snapshot.memoryUsage
        )
        enqueueWorkerProgressEvent(
            generation = snapshot.generation,
            sequence = snapshot.sequence,
            event = snapshot.event,
            notificationProgressCurrent = snapshot.notificationProgressCurrent,
            notificationProgressMax = snapshot.notificationProgressMax,
            failedPatchIndexes = snapshot.failedPatchIndexes,
            patchExecutionOrder = snapshot.patchExecutionOrder,
            seedFromWorkerSnapshot = true
        )
    }

    private fun shouldApplyWorkerProgress(generation: Long, sequence: Long): Boolean = when {
        generation > lastAppliedWorkerProgressGeneration -> true
        generation < lastAppliedWorkerProgressGeneration -> false
        else -> sequence > lastAppliedWorkerProgressSequence
    }

    private fun recordAppliedWorkerProgress(generation: Long, sequence: Long) {
        lastAppliedWorkerProgressGeneration = generation
        lastAppliedWorkerProgressSequence = sequence
    }

    private fun isNewerWorkerSnapshot(
        candidate: app.universal.patch.manager.patcher.worker.PatcherWorkerProgressSnapshot,
        existing: app.universal.patch.manager.patcher.worker.PatcherWorkerProgressSnapshot
    ): Boolean = when {
        candidate.generation > existing.generation -> true
        candidate.generation < existing.generation -> false
        else -> candidate.sequence > existing.sequence
    }

    private fun seedVisualProgressFromWorkerProgress(current: Int?, max: Int?) {
        val safeCurrent = current ?: return
        val safeMax = max?.takeIf { it > 0 } ?: return
        val candidate = (safeCurrent.toFloat() / safeMax.toFloat()).coerceIn(0f, 1f)
        if (candidate > progress) {
            progressState.progress = candidate
        }
    }

    private fun seedProgressStateFromWorkerSnapshot(
        event: ProgressEvent,
        failedPatchIndexes: Set<Int> = emptySet()
    ) {
        if (event.stepId == StepId.PrepareSplitApk && steps.none { it.id == StepId.PrepareSplitApk }) {
            requiresSplitPreparation = true
            val loadPatchesIndex = steps.indexOfFirst { it.id == StepId.LoadPatches }
                .takeIf { it >= 0 }
                ?: 0
            steps.add(loadPatchesIndex, buildSplitStep(app, preserveSplitApks = isReseamSelection()))
        }

        val stepIndex = event.stepId?.let { stepId ->
            steps.indexOfFirst { it.id == stepId }
        } ?: -1
        if (stepIndex == -1) return

        for (index in 0 until stepIndex) {
            val step = steps[index]
            if (step.state == State.WAITING) {
                steps[index] = step.withState(state = State.COMPLETED, progress = null)
            }
        }
        reconcileFailedPatchIndexes(failedPatchIndexes)
    }

    private fun reconcileFailedPatchIndexes(failedPatchIndexes: Set<Int>) {
        steps.forEachIndexed { index, step ->
            val patchStep = step.id as? StepId.ExecutePatch ?: return@forEachIndexed
            when {
                patchStep.index in failedPatchIndexes && step.state != State.FAILED -> {
                    steps[index] = step.withState(
                        state = State.FAILED,
                        progress = null
                    )
                }
                patchStep.index !in failedPatchIndexes && step.state == State.FAILED -> {
                    steps[index] = step.withState(
                        state = State.COMPLETED,
                        message = null,
                        progress = null
                    )
                }
            }
        }
    }

    private fun handleWorkerFailure(workInfo: WorkInfo) {
        if (!handledFailureIds.add(workInfo.id)) return
        progressState.reconcileFailureState(workInfo.outputData.getString(PatcherWorker.PROCESS_FAILURE_MESSAGE_KEY))
        forceKeepLocalInput = true

        // Missing patch issues are handled during preflight validation.
    }



    fun dismissKeystoreMissingDialog() {
        keystoreMissingDialog = false
    }

    private fun resetStateForRetry() {
        val newSteps = generateSteps(
            app,
            input.selectedApp,
            appliedSelection,
            requiresSplitPreparation,
            skipApkSigning,
            input.injectSignatureMetadata,
            preserveSplitApks = isReseamSelection()
        ).toMutableStateList()
        steps.clear()
        progressState.resetDexCompileState()
        resetFailureLogState()
        progressState.resetVisualProgress()
        steps.addAll(newSteps)
        stepSubSteps.clear()
        _patcherSucceeded.value = null
    }

    private fun initialSplitRequirement(selectedApp: SelectedApp): Boolean =
        when (selectedApp) {
            is SelectedApp.Local -> SplitApkPreparer.isSplitArchive(SharedApkFiles.resolve(selectedApp.file))
            else -> false
        }

    private fun updateSplitStepRequirement(
        file: File?,
        needsSplitOverride: Boolean? = null,
        merged: Boolean = false
    ) {
        val needsSplit = needsSplitOverride
            ?: (merged || file?.let(SplitApkPreparer::isSplitArchive) == true)
        when {
            needsSplit && !requiresSplitPreparation -> {
                requiresSplitPreparation = true
                addSplitStep()
            }

            !needsSplit && requiresSplitPreparation -> {
                requiresSplitPreparation = false
                removeSplitStep()
                return
            }
        }

        if (needsSplit && merged) {
            val index = steps.indexOfFirst { it.id == StepId.PrepareSplitApk }
            if (index >= 0) {
                steps[index] = steps[index].withState(State.COMPLETED)
            }
        }

    }

    private fun addSplitStep() {
        if (steps.any { it.id == StepId.PrepareSplitApk }) return

        val loadIndex = steps.indexOfFirst { it.id == StepId.LoadPatches }
        val insertIndex = when {
            loadIndex >= 0 -> loadIndex
            else -> steps.indexOfFirst { it.id == StepId.ReadAPK }.takeIf { it >= 0 } ?: steps.size
        }
        steps.add(insertIndex, buildSplitStep(app, preserveSplitApks = isReseamSelection()))
        progressState.pauseLoadPatchesForSplitPreparation()
    }

    private fun removeSplitStep() {
        progressState.clearDeferredLoadPatchesEvents()
        val index = steps.indexOfFirst { it.id == StepId.PrepareSplitApk }
        if (index == -1) return
        steps.removeAt(index)
    }

    private fun filterSelectionToAvailablePatches(
        selection: PatchSelection,
        bundles: Map<Int, PatchBundleInfo>
    ): PatchSelection = buildMap {
        selection.forEach { (uid, patches) ->
            val valid = bundles[uid]?.patches?.mapTo(hashSetOf()) { it.name }.orEmpty()
            val kept = patches.filterTo(mutableSetOf()) { it in valid }
            if (kept.isNotEmpty()) put(uid, kept)
        }
    }

    private fun sanitizeSelection(
        selection: PatchSelection,
        bundles: Map<Int, PatchBundleInfo>
    ): PatchSelection = buildMap {
        selection.forEach { (uid, patches) ->
            val bundle = bundles[uid]
            if (bundle == null) {
                // Keep unknown bundles so applied patches stay visible even if the source is missing.
                if (patches.isNotEmpty()) put(uid, patches.toSet())
                return@forEach
            }

            val valid = bundle.patches.map { it.name }.toSet()
            val kept = patches.filter { it in valid }.toSet()
            if (kept.isNotEmpty()) {
                put(uid, kept)
            } else if (patches.isNotEmpty()) {
                // If everything was filtered out by compatibility, still keep the original set so
                // the app info screen can show the applied bundle/patch names.
                put(uid, patches.toSet())
            }
        }
    }

    private suspend fun applyCurrentPatchRules(
        selection: PatchSelection,
        bundles: Map<Int, PatchBundleInfo.Scoped>,
    ): PatchSelection {
        val allowIncompatible = prefs.disablePatchVersionCompatCheck.get() ||
            bundles.any { (uid, bundle) ->
                val selected = selection[uid].orEmpty()
                bundle.incompatible.any { it.name in selected }
            }
        val availabilityEnabled = prefs.patchAvailabilityEnabled.get()
        val removeGmsCore = usingMountInstall &&
            installerManager.baseInstallerToken(installerManager.getPrimaryToken()) ==
                InstallerManager.Token.AutoSaved &&
            prefs.removeGmsCoreForPrimaryMount.get()

        return selection.applyAvailability(
            installerTypeFor(usingMountInstall),
            bundles.mapValues { (_, bundle) ->
                bundle.patchSequence(allowIncompatible).associateBy { it.name }
            },
            availabilityEnabled
        ).removeGmsCoreSupport(removeGmsCore)
    }

    private fun sanitizeOptions(
        options: Options,
        bundles: Map<Int, PatchBundleInfo>
    ): Options = buildMap {
        options.forEach { (uid, patchOptions) ->
            val bundle = bundles[uid] ?: return@forEach
            val patches = bundle.patches.associateBy { it.name }
            val filtered = buildMap<String, Map<String, Any?>> {
                patchOptions.forEach { (patchName, values) ->
                    val patch = patches[patchName] ?: return@forEach
                    val validKeys = patch.options?.map { it.key }?.toSet() ?: emptySet()
                    val kept = if (validKeys.isEmpty()) values else values.filterKeys { it in validKeys }
                    if (kept.isNotEmpty()) put(patchName, kept)
                }
            }
            if (filtered.isNotEmpty()) put(uid, filtered)
        }
    }

    private suspend fun savedEntryIdentity(installedApp: InstalledApp): String {
        val patchSelection = installedAppRepository.getAppliedPatches(installedApp.currentPackageName)
        return buildSavedAppVariantIdentity(
            appVersion = installedApp.version,
            selectionPayload = installedApp.selectionPayload,
            patchSelection = patchSelection,
            useMount = installedApp.useMount
        )
    }

    private suspend fun collapseMatchingSavedEntriesForInstalledVariant(
        packageName: String,
        installedPackageName: String,
        variantIdentity: String,
        preservedEntryKey: String? = null
    ) {
        val cleanupTargetPackageName = preservedEntryKey ?: installedPackageName
        installedAppRepository.getByInstallType(InstallType.SAVED)
            .filter { savedEntry ->
                savedEntry.currentPackageName != installedPackageName &&
                    savedEntry.currentPackageName != preservedEntryKey &&
                    isSavedAppEntryForPackage(savedEntry.currentPackageName, packageName)
            }
            .forEach { savedEntry ->
                if (savedEntryIdentity(savedEntry) != variantIdentity) return@forEach
                installedAppRepository.migrateAutoPatchTarget(
                    savedEntry.currentPackageName,
                    cleanupTargetPackageName
                )
                installedAppRepository.delete(savedEntry)
                fs.getPatchedAppFile(
                    savedEntry.currentPackageName,
                    savedEntry.version
                ).takeIf { it.exists() }?.delete()
            }
    }

    private suspend fun pruneUnreferencedPatchedAppFiles() {
        val retainedFiles = installedAppRepository.getAll().first().map { installedApp ->
            fs.getPatchedAppFile(installedApp.currentPackageName, installedApp.version)
        }
        val removed = fs.prunePatchedAppFiles(retainedFiles)
        if (removed > 0) {
            Log.d(TAG, "Removed $removed stale saved patched APK file(s)")
        }
        installedAppRepository.pruneRetainedOriginals()
    }

    private fun buildUniqueSavedAppEntryKey(packageName: String, variantIdentity: String): String {
        val keyBase = buildSavedAppEntryKey(packageName, variantIdentity)
        val nonce = UUID.randomUUID().toString().replace("-", "").take(8)
        return "${keyBase}__${nonce}"
    }

    internal companion object {
        const val TAG = "ReVanced Patcher"
        const val SKIPPED_SUBSTEP_PREFIX = "[skipped]"
        private const val WRITE_APK_DEX_GROUP_TITLE = "Compiling DEX files"
        private const val DOWNLOADER_DIALOG_SETTLE_MS = 32L
        private const val DOWNLOADER_ACTIVITY_RESULT_GRACE_MS = 750L
        private const val SYSTEM_INSTALL_TIMEOUT_MS = 60_000L
        private const val EXTERNAL_INSTALL_TIMEOUT_MS = 60_000L
        private const val SHIZUKU_UNINSTALL_VERIFY_TIMEOUT_MS = 10_000L
        private const val SHIZUKU_UNINSTALL_VERIFY_POLL_MS = 250L
        private const val POST_TIMEOUT_GRACE_MS = 5_000L
        private const val EXTERNAL_INSTALLER_RESULT_GRACE_MS = 1500L
        private const val EXTERNAL_INSTALLER_POST_CLOSE_TIMEOUT_MS = 30_000L
        private const val INSTALL_MONITOR_POLL_MS = 500L
        private const val INSTALL_PROGRESS_TOAST_INTERVAL_MS = 2500L
        private const val SUPPRESS_FAILURE_AFTER_SUCCESS_MS = 5000L
        private const val PATCHER_LOG_ENTRY_SOFT_LIMIT = 9_000
        private const val PATCHER_LOG_ENTRY_HARD_LIMIT = 12_000
        private const val PATCHER_LOG_MESSAGE_CHAR_LIMIT = 12_000
        private const val PATCHER_SESSION_INFO_KEY = "patcher_session_info"
        private const val PATCHER_RUN_CONFIGURATION_KEY = "patcher_run_configuration"
        private const val FAILURE_LOG_SUMMARY_CHAR_LIMIT = 1_000
        fun LogLevel.androidLog(msg: String) = when (this) {
            LogLevel.TRACE -> Log.v(TAG, msg)
            LogLevel.INFO -> Log.i(TAG, msg)
            LogLevel.WARN -> Log.w(TAG, msg)
            LogLevel.ERROR -> Log.e(TAG, msg)
        }

        fun generateSteps(
            context: Context,
            selectedApp: SelectedApp,
            selectedPatches: PatchSelection,
            splitStepActive: Boolean,
            skipApkSigning: Boolean,
            injectSignatureMetadata: Boolean = false,
            preserveSplitApks: Boolean = false
        ): List<Step> = buildList {
            if (selectedApp is SelectedApp.Download || selectedApp is SelectedApp.Search) {
                add(
                    Step(
                        StepId.DownloadAPK,
                        context.getString(R.string.download_apk),
                        StepCategory.PREPARING
                    )
                )
            }

            if (splitStepActive) {
                add(buildSplitStep(context, preserveSplitApks = preserveSplitApks))
            }

            add(
                Step(
                    StepId.LoadPatches,
                    context.getString(R.string.patcher_step_load_patches),
                    StepCategory.PREPARING
                )
            )

            add(
                Step(
                    StepId.ReadAPK,
                    context.getString(R.string.patcher_step_unpack),
                    StepCategory.PREPARING
                )
            )

            add(
                Step(
                    StepId.ExecutePatches,
                    context.getString(R.string.execute_patches),
                    StepCategory.PATCHING,
                    hide = true
                )
            )

            selectedPatches.values.asSequence().flatten().sorted().forEachIndexed { index, name ->
                add(
                    Step(
                        StepId.ExecutePatch(index),
                        if (preserveSplitApks) app.universal.patch.manager.patcher.patch.ReseamBundleFormat.patchDisplayName(name) else name,
                        StepCategory.PATCHING
                    )
                )
            }

            add(
                Step(
                    StepId.WriteAPK,
                    context.getString(R.string.patcher_step_write_patched),
                    StepCategory.SAVING
                )
            )
            if (!skipApkSigning || injectSignatureMetadata) {
                add(
                    Step(
                        StepId.SignAPK,
                        context.getString(R.string.patcher_step_sign_apk),
                        StepCategory.SAVING
                    )
                )
            }
        }

    }
}

private fun InstallType?.isShizukuInstall(): Boolean =
    this == InstallType.SHIZUKU || this == InstallType.SHIZUKU_PLAY_STORE

private fun buildSplitStep(
    context: Context,
    message: String? = null,
    preserveSplitApks: Boolean = false
) = Step(
    id = StepId.PrepareSplitApk,
    title = context.getString(
        if (preserveSplitApks) R.string.patcher_step_prepare_separate_apks
        else R.string.patcher_step_prepare_split_apk
    ),
    category = StepCategory.PREPARING,
    message = message
)
