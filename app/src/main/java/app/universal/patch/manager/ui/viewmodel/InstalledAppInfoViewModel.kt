package app.universal.patch.manager.ui.viewmodel

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.universal.patch.manager.BuildConfig
import app.universal.patch.manager.R
import app.universal.patch.manager.data.platform.Filesystem
import app.universal.patch.manager.data.room.apps.installed.InstallType
import app.universal.patch.manager.data.room.apps.installed.InstalledApp
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.domain.installer.InstallCancelledException
import app.universal.patch.manager.domain.installer.InstallResult as PackageInstallResult
import app.universal.patch.manager.domain.installer.InstallerManager
import app.universal.patch.manager.domain.installer.RootInstaller
import app.universal.patch.manager.domain.installer.SessionDeadException
import app.universal.patch.manager.domain.installer.SessionInstaller
import app.universal.patch.manager.domain.installer.ShizukuInstaller
import app.universal.patch.manager.domain.installer.usesPersistedPlayStoreMountMode
import app.universal.patch.manager.domain.installer.root.RootMountOperation
import app.universal.patch.manager.domain.installer.root.RootMountRequest
import app.universal.patch.manager.domain.installer.root.RootMountResult
import app.universal.patch.manager.domain.installer.root.RootMountSuspension
import app.universal.patch.manager.domain.installer.root.RootMountTransactionCoordinator
import app.universal.patch.manager.domain.installer.root.installAsPlayStoreWithMountRollback
import app.universal.patch.manager.domain.installer.root.launchExternalInstallerWithMountFinalization
import app.universal.patch.manager.domain.installer.root.reinstallMountedStockAsPlayStore
import app.universal.patch.manager.domain.installer.root.restore
import app.universal.patch.manager.domain.installer.root.retire
import app.universal.patch.manager.domain.installer.root.requireSuccess
import app.universal.patch.manager.domain.installer.root.suspendRootMountForPackageInstall
import app.universal.patch.manager.domain.repository.InstalledAppRepository
import app.universal.patch.manager.domain.repository.PendingHistoricalSavedEntry
import app.universal.patch.manager.domain.repository.PatchBundleRepository
import app.universal.patch.manager.domain.repository.mergeWithRemappedSelection
import app.universal.patch.manager.domain.repository.remapAndExtractSelection
import app.universal.patch.manager.domain.repository.remapLocalBundles
import app.universal.patch.manager.domain.repository.toPayload
import app.universal.patch.manager.domain.repository.toSignatureMap
import app.universal.patch.manager.domain.bundles.PatchBundleSource.Extensions.asRemoteOrNull
import app.universal.patch.manager.domain.batch.batchOriginalPackageName
import app.universal.patch.manager.domain.batch.hasBatchShortcutTarget
import app.universal.patch.manager.util.PM
import app.universal.patch.manager.util.PLAY_STORE_INSTALLER_PACKAGE
import app.universal.patch.manager.util.PatchSelection
import app.universal.patch.manager.util.buildSavedAppEntryKey
import app.universal.patch.manager.util.buildSavedAppVariantIdentity
import app.universal.patch.manager.util.isSavedAppEntryForPackage
import app.universal.patch.manager.util.savedAppBasePackage
import app.universal.patch.manager.util.savedApkAbiLabel
import app.universal.patch.manager.util.savedAppLauncherShortcutCapacity
import app.universal.patch.manager.util.supportsRootMount
import app.universal.patch.manager.util.simpleMessage
import app.universal.patch.manager.util.tag
import app.universal.patch.manager.util.awaitUserConfirmation
import app.universal.patch.manager.util.toast
import app.universal.patch.manager.util.toastHandle
import app.universal.patch.manager.util.versionCodeFor
import app.universal.patch.manager.util.withRepeatingToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import app.universal.patch.manager.patcher.split.SplitApkPreparer
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import ru.solrudev.ackpine.session.Session
import ru.solrudev.ackpine.session.await
import ru.solrudev.ackpine.session.parameters.Confirmation
import ru.solrudev.ackpine.uninstaller.PackageUninstaller
import ru.solrudev.ackpine.uninstaller.UninstallFailure
import ru.solrudev.ackpine.uninstaller.createSession

class InstalledAppInfoViewModel(
    packageName: String
) : ViewModel(), KoinComponent {
    enum class MountOperation { UNMOUNTING, MOUNTING, REPAIRING }

    private val context: Application by inject()
    private val pm: PM by inject()
    private val installedAppRepository: InstalledAppRepository by inject()
    private val patchBundleRepository: PatchBundleRepository by inject()
    private val prefs: PreferencesManager by inject()
    val rootInstaller: RootInstaller by inject()
    private val rootMountCoordinator: RootMountTransactionCoordinator by inject()
    private val installerManager: InstallerManager by inject()
    private val sessionInstaller: SessionInstaller by inject()
    private val ackpineUninstaller: PackageUninstaller = get()
    private val shizukuInstaller: ShizukuInstaller by inject()
    private val filesystem: Filesystem by inject()
    private var launchedActivity: CompletableDeferred<ActivityResult>? = null
    private val launchActivityChannel = Channel<Intent>()
    val launchActivityFlow = launchActivityChannel.receiveAsFlow()
    private var expectedInstallSignature: ByteArray? = null
    private var baselineInstallSignature: ByteArray? = null
    private var pendingExternalInstall: InstallerManager.InstallPlan.External? = null
    private var pendingExternalMountSuspension: RootMountSuspension? = null
    private var pendingExternalMountFinalizationJob: Job? = null
    private var externalInstallFinalizationJob: Job? = null
    private var externalInstallTimeoutJob: Job? = null
    private var internalInstallTimeoutJob: Job? = null
    private var externalInstallBaseline: Pair<Long?, Long?>? = null
    private var externalInstallStartTime: Long? = null
    private var externalPackageWasPresentAtStart: Boolean = false
    private var installProgressToastJob: Job? = null
    private var installProgressToast: Toast? = null
    private var uninstallProgressToastJob: Job? = null
    private var uninstallProgressToast: Toast? = null
    private var deferInstallProgressToasts = false
    private var deferUninstallProgressToasts = false
    private var pendingInstallToken: InstallerManager.Token? = null
    private var pendingSignatureMismatchPackage: String? = null
    var isInstalling by mutableStateOf(false)
        private set
    var isDeletingSavedRootApp by mutableStateOf(false)
        private set

    lateinit var onBackClick: () -> Unit

    var installedApp: InstalledApp? by mutableStateOf(null)
        private set
    var appInfo: PackageInfo? by mutableStateOf(null)
        private set
    var hasLoadedAppInfo by mutableStateOf(false)
        private set
    var appLabel: String? by mutableStateOf(null)
        private set
    var customInstallerLabel: String? by mutableStateOf(null)
        private set
    var appliedPatches: PatchSelection? by mutableStateOf(null)
    var isMounted by mutableStateOf(false)
        private set
    var isPlayStoreInstallerSource by mutableStateOf(false)
        private set
    var isInstalledOnDevice by mutableStateOf(false)
        private set
    var hasSavedCopy by mutableStateOf(false)
        private set
    var savedFileExtension by mutableStateOf("apk")
        private set
    var displayedVersionCode by mutableStateOf<Long?>(null)
        private set
    var savedApkAbiLabel by mutableStateOf<String?>(null)
        private set
    var mountOperation: MountOperation? by mutableStateOf(null)
        private set
    val isRootMountBusy: Boolean
        get() = mountOperation != null || isInstalling || isDeletingSavedRootApp
    var mountWarning: MountWarningState? by mutableStateOf(null)
        private set
    var mountVersionMismatchMessage: String? by mutableStateOf(null)
        private set
    var installResult: InstallResult? by mutableStateOf(null)
        private set
    var signatureMismatchPackage by mutableStateOf<String?>(null)
        private set

    val primaryInstallerIsMount: Boolean
        get() = installerManager.baseInstallerToken(installerManager.getPrimaryToken()) ==
            InstallerManager.Token.AutoSaved
    val supportsRootMount: Boolean
        get() {
            val app = installedApp ?: return false
            return app.supportsRootMount(appInfo?.packageName)
        }
    val primaryInstallerToken: InstallerManager.Token
        get() = installerManager.getPrimaryToken()

    init {
        viewModelScope.launch {
            val app = installedAppRepository.get(packageName)
            installedApp = app
            try {
                if (app != null) {
                    isMounted = rootInstaller.isAppMounted(resolveDevicePackageName(app))
                    refreshAppState(app)
                    appliedPatches = resolveAppliedSelection(app)
                }
            } finally {
                hasLoadedAppInfo = true
            }
        }
    }

    fun showMountWarning(action: MountWarningAction, reason: MountWarningReason) {
        mountWarning = MountWarningState(action, reason)
    }

    fun clearMountWarning() {
        mountWarning = null
    }

    fun cancelOngoingInstall() {
        if (externalInstallFinalizationJob != null) return
        if (
            pendingExternalInstall?.token == InstallerManager.Token.PlayStore &&
            pendingExternalMountSuspension != null
        ) {
            return
        }
        finalizePendingExternalMount(installed = false)
        pendingExternalInstall?.let(installerManager::cleanup)
        pendingExternalInstall = null
        pendingInstallToken = null
        pendingSignatureMismatchPackage = null
        signatureMismatchPackage = null
        externalInstallTimeoutJob?.cancel()
        internalInstallTimeoutJob?.cancel()
        externalInstallBaseline = null
        externalInstallStartTime = null
        stopInstallProgressToasts()
        installResult = null
        isInstalling = false
    }

    fun performMountWarningAction() {
        when (val warning = mountWarning) {
            null -> Unit
            else -> when (warning.reason) {
                MountWarningReason.PRIMARY_IS_MOUNT_FOR_NON_MOUNT_APP -> when (warning.action) {
                    MountWarningAction.INSTALL,
                    MountWarningAction.UPDATE -> installSavedApp()
                    MountWarningAction.UNINSTALL -> {
                        val app = installedApp
                        if (app?.installType == InstallType.MOUNT || isMounted) {
                            mountOrUnmount()
                        } else {
                            uninstallSavedInstallation()
                        }
                    }
                }

                MountWarningReason.PRIMARY_NOT_MOUNT_FOR_MOUNT_APP -> when (warning.action) {
                    MountWarningAction.INSTALL,
                    MountWarningAction.UPDATE -> installSavedApp()
                    MountWarningAction.UNINSTALL -> uninstallSavedInstallation()
                }
            }
        }
        mountWarning = null
    }

    private suspend fun resolveAppliedSelection(app: InstalledApp) = withContext(Dispatchers.IO) {
        val storedSelection = installedAppRepository.getAppliedPatches(app.currentPackageName)
        val payload = app.selectionPayload ?: return@withContext storedSelection
        val sources = patchBundleRepository.sources.first()
        val sourceIds = sources.map { it.uid }.toSet()
        val signatures = patchBundleRepository.allBundlesInfoFlow.first().toSignatureMap()
        val (remappedPayload, remappedSelection) = payload.remapAndExtractSelection(sources, signatures)
        val mergedSelection = storedSelection.mergeWithRemappedSelection(
            originalPayload = payload,
            remappedPayload = remappedPayload,
            remappedSelection = remappedSelection,
            sources = sources
        )
        val persistableSelection = mergedSelection.filterKeys { it in sourceIds }
        if (persistableSelection.isNotEmpty() &&
            (persistableSelection != storedSelection || remappedPayload != payload)
        ) {
            installedAppRepository.addOrUpdate(
                app.currentPackageName,
                app.originalPackageName,
                app.version,
                app.installType,
                persistableSelection,
                remappedPayload
            )
        }
        mergedSelection
    }

    suspend fun getRepatchSelection(): PatchSelection? = withContext(Dispatchers.IO) {
        val app = installedApp ?: return@withContext null
        val selection = appliedPatches ?: resolveAppliedSelection(app)
        if (appliedPatches == null) {
            withContext(Dispatchers.Main) {
                appliedPatches = selection
            }
        }
        selection
    }

    fun setAutoPatchEnabledForApp(enabled: Boolean) =
        viewModelScope.launch(Dispatchers.Default) {
            val app = installedApp ?: return@launch
            installedAppRepository.setAutoPatchTarget(app, enabled)
        }

    fun setLauncherShortcutEnabledForApp(enabled: Boolean) =
        viewModelScope.launch(Dispatchers.Default) {
            val app = installedApp ?: return@launch
            prefs.setSavedAppLauncherShortcutEnabled(
                packageName = batchOriginalPackageName(app),
                enabled = enabled,
                capacity = savedAppLauncherShortcutCapacity(context)
            )
        }

    private suspend fun resolveDevicePackageName(
        app: InstalledApp,
        savedApk: File? = null
    ): String {
        if (app.installType != InstallType.SAVED) return app.currentPackageName
        val resolvedFromApk = (savedApk ?: savedApkFile(app))
            ?.let(pm::getPackageInfo)
            ?.packageName
            ?.takeIf { it.isNotBlank() }
        return resolvedFromApk
            ?: app.originalPackageName.takeIf { it.isNotBlank() }
            ?: savedAppBasePackage(app.currentPackageName)
    }

    private fun resolveDevicePackageNameFromState(app: InstalledApp): String {
        if (app.installType != InstallType.SAVED) return app.currentPackageName
        return appInfo?.packageName
            ?.takeIf { it.isNotBlank() }
            ?: app.originalPackageName.takeIf { it.isNotBlank() }
            ?: savedAppBasePackage(app.currentPackageName)
    }

    fun launch() {
        val app = installedApp ?: return
        if (!isInstalledOnDevice) {
            context.toast(context.getString(R.string.saved_app_launch_unavailable))
        } else {
            pm.launch(resolveDevicePackageNameFromState(app))
        }
    }

    fun dismissMountVersionMismatch() {
        mountVersionMismatchMessage = null
    }

    private fun markInstallSuccess(message: String) {
        stopInstallProgressToasts()
        internalInstallTimeoutJob?.cancel()
        installResult = InstallResult.Success(message)
        isInstalling = false
    }

    private suspend fun persistInstallMetadata(
        installType: InstallType,
        versionName: String? = null,
        packageNameOverride: String? = null,
        customInstallerPackageName: String? = null
    ) {
        val app = installedApp ?: return
        val sourceEntryKey = app.currentPackageName
        val sourceInstallType = app.installType
        val selection = appliedPatches ?: resolveAppliedSelection(app)
        val selectionPayload = app.selectionPayload
        val targetPackage = packageNameOverride ?: resolveDevicePackageName(app)
        val installedInfo = pm.getPackageInfo(targetPackage)
        val resolvedVersion = versionName ?: installedInfo?.versionName ?: app.version
        val resolvedVersionCode = app.versionCode?.takeIf { resolvedVersion == app.version }
            ?: savedApkFile(app)?.let(pm::getPackageInfo)?.versionCodeFor(resolvedVersion)
            ?: installedInfo?.versionCodeFor(resolvedVersion)
        val newVariantIdentity = buildSavedAppVariantIdentity(
            appVersion = resolvedVersion,
            selectionPayload = selectionPayload,
            patchSelection = selection,
            useMount = app.useMount
        )
        val matchingSavedEntries = installedAppRepository
            .getByInstallType(InstallType.SAVED)
            .filter { savedEntry ->
                savedEntry.currentPackageName != targetPackage &&
                    isSavedAppEntryForPackage(savedEntry.currentPackageName, targetPackage) &&
                    savedVariantIdentity(savedEntry) == newVariantIdentity
            }

        val pendingHistoricalEntry = if (sourceInstallType == InstallType.SAVED) {
            prepareReplacedInstalledVariant(
                targetPackage = targetPackage,
                newVariantIdentity = newVariantIdentity
            )
        } else {
            null
        }

        val persistReplacement: suspend () -> Unit = {
            installedAppRepository.addOrUpdate(
                currentPackageName = targetPackage,
                originalPackageName = app.originalPackageName,
                version = resolvedVersion,
                installType = installType,
                patchSelection = selection,
                selectionPayload = selectionPayload,
                useMount = app.useMount,
                updateUseMount = true,
                customInstallerPackageName = customInstallerPackageName,
                repatchSourcePath = app.repatchSourcePath,
                updateRepatchSource = true,
                versionCode = resolvedVersionCode,
                updateVersionCode = true
            )
        }
        if (pendingHistoricalEntry != null) {
            pendingHistoricalEntry.commitWith(targetPackage, persistReplacement)
        } else {
            persistReplacement()
            installedAppRepository.pruneRepatchInputs()
        }

        val targetHasRetainedRepatchInput = installedAppRepository.get(targetPackage)
            ?.repatchSourcePath
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?.isFile == true
        val preservedSavedEntry = if (!targetHasRetainedRepatchInput) {
            sequenceOf(app.takeIf { sourceInstallType == InstallType.SAVED })
                .plus(matchingSavedEntries.asSequence())
                .filterNotNull()
                .distinctBy(InstalledApp::currentPackageName)
                .firstOrNull { savedEntry ->
                    savedEntry.currentPackageName != targetPackage &&
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
                tag,
                "Keeping saved entry $preservedSavedEntryKey because its Repatch input was not retained for $targetPackage"
            )
        }
        val savedEntryCleanupTarget = preservedSavedEntryKey ?: targetPackage

        // Installing from a saved entry can migrate from a synthetic key
        // (for example, package__saved_<bundle-hash>) to the real package name.
        // Remove the old saved row only after its Repatch source is safely transferred.
        if (
            sourceInstallType == InstallType.SAVED &&
            sourceEntryKey != targetPackage &&
            sourceEntryKey != preservedSavedEntryKey
        ) {
            installedAppRepository.migrateAutoPatchTarget(
                sourceEntryKey,
                savedEntryCleanupTarget
            )
            installedAppRepository.delete(app)
        }
        if (installType != InstallType.SAVED) {
            collapseMatchingSavedEntriesForInstalledVariant(
                packageName = targetPackage,
                installedPackageName = targetPackage,
                variantIdentity = newVariantIdentity,
                preservedEntryKey = preservedSavedEntryKey
            )
        }
        try {
            installedAppRepository.pruneRetainedOriginals()
        } catch (error: Exception) {
            Log.w(tag, "Failed to prune retained original APKs", error)
        }

        val updatedApp = installedAppRepository.get(targetPackage) ?: app.copy(
            currentPackageName = targetPackage,
            version = resolvedVersion,
            installType = installType
        )
        installedApp = updatedApp
        refreshAppState(updatedApp)
    }

    private suspend fun prepareReplacedInstalledVariant(
        targetPackage: String,
        newVariantIdentity: String
    ): PendingHistoricalSavedEntry? {
        val savedEntriesForPackage = installedAppRepository.getByInstallType(InstallType.SAVED).filter { savedApp ->
            isSavedAppEntryForPackage(savedApp.currentPackageName, targetPackage)
        }
        val savedEntryIdentities = mutableMapOf<String, String>()
        savedEntriesForPackage.forEach { savedApp ->
            savedEntryIdentities[savedApp.currentPackageName] = savedVariantIdentity(savedApp)
        }

        val existingTargetEntry = installedAppRepository.get(targetPackage) ?: return null
        val existingInstalledEntry = existingTargetEntry.takeIf { it.installType != InstallType.SAVED }
        val existingInstalledIdentity = existingInstalledEntry?.let { savedVariantIdentity(it) }
        if (
            existingInstalledEntry != null &&
            existingInstalledIdentity != null &&
            existingInstalledIdentity != newVariantIdentity &&
            existingInstalledIdentity !in savedEntryIdentities.values
        ) {
            return installedAppRepository.prepareHistoricalSavedEntry(
                sourceApp = existingInstalledEntry,
                targetPackageName = buildSavedAppEntryKey(targetPackage, existingInstalledIdentity)
            )
        }

        val existingSavedEntryAtBaseKey = existingTargetEntry.takeIf { it.installType == InstallType.SAVED }
        val existingSavedEntryIdentity = existingSavedEntryAtBaseKey?.let { savedVariantIdentity(it) }
        return if (
            existingSavedEntryAtBaseKey != null &&
            existingSavedEntryIdentity != null &&
            existingSavedEntryIdentity != newVariantIdentity &&
            existingSavedEntryIdentity !in savedEntryIdentities
                .filterKeys { it != existingSavedEntryAtBaseKey.currentPackageName }
                .values
        ) {
            installedAppRepository.prepareHistoricalSavedEntry(
                sourceApp = existingSavedEntryAtBaseKey,
                targetPackageName = buildSavedAppEntryKey(targetPackage, existingSavedEntryIdentity)
            )
        } else {
            null
        }
    }

    private suspend fun savedVariantIdentity(app: InstalledApp): String =
        buildSavedAppVariantIdentity(
            appVersion = app.version,
            selectionPayload = app.selectionPayload,
            patchSelection = resolveAppliedSelection(app),
            useMount = app.useMount
        )

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
                if (savedVariantIdentity(savedEntry) != variantIdentity) return@forEach
                installedAppRepository.migrateAutoPatchTarget(
                    savedEntry.currentPackageName,
                    cleanupTargetPackageName
                )
                installedAppRepository.delete(savedEntry)
                filesystem.getPatchedAppFile(
                    savedEntry.currentPackageName,
                    savedEntry.version
                ).takeIf { it.exists() }?.delete()
            }
    }

    private fun markInstallFailure(message: String) {
        stopInstallProgressToasts()
        stopUninstallProgressToasts()
        internalInstallTimeoutJob?.cancel()
        installResult = InstallResult.Failure(message)
        isInstalling = false
    }

    private fun markUninstallFailure(message: String) {
        stopInstallProgressToasts()
        stopUninstallProgressToasts()
        internalInstallTimeoutJob?.cancel()
        installResult = InstallResult.UninstallError(message)
        isInstalling = false
    }

    private fun showSignatureMismatchPrompt(packageName: String) {
        stopInstallProgressToasts()
        installResult = null
        isInstalling = false
        pendingSignatureMismatchPackage = packageName
        signatureMismatchPackage = packageName
    }

    private fun startInstallProgressToasts(mounting: Boolean = false) {
        if (deferInstallProgressToasts) return
        if (installProgressToastJob?.isActive == true) return
        isInstalling = true
        val messageRes = if (mounting) R.string.mounting_ellipsis else R.string.installing_ellipsis
        installProgressToastJob = viewModelScope.launch {
            while (isActive) {
                installProgressToast?.cancel()
                installProgressToast = context.toastHandle(context.getString(messageRes))
                delay(INSTALL_PROGRESS_TOAST_INTERVAL_MS)
            }
        }
    }

    private fun stopInstallProgressToasts() {
        installProgressToastJob?.cancel()
        installProgressToastJob = null
        installProgressToast?.cancel()
        installProgressToast = null
        internalInstallTimeoutJob?.cancel()
        deferInstallProgressToasts = false
        if (pendingExternalInstall == null) {
            isInstalling = false
        }
    }

    private fun enableInstallProgressToasts() {
        if (!deferInstallProgressToasts) return
        deferInstallProgressToasts = false
        startInstallProgressToasts()
    }

    private fun startUninstallProgressToasts() {
        if (deferUninstallProgressToasts) return
        if (uninstallProgressToastJob?.isActive == true) return
        uninstallProgressToastJob = viewModelScope.launch {
            while (isActive) {
                uninstallProgressToast?.cancel()
                uninstallProgressToast = context.toastHandle(context.getString(R.string.uninstalling_ellipsis))
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

    private suspend fun runAckpineUninstall(
        packageName: String
    ): Session.State.Completed<UninstallFailure> {
        val session = ackpineUninstaller.createSession(packageName) {
            confirmation = Confirmation.IMMEDIATE
        }
        val toastJob = launchUninstallConfirmationToast(session)
        return try {
            withContext(Dispatchers.IO) {
                session.await()
            }
        } finally {
            toastJob.cancel()
        }
    }

    fun handleActivityResult(result: ActivityResult) {
        launchedActivity?.complete(result)
    }

    fun installSavedApp(token: InstallerManager.Token? = null) = viewModelScope.launch {
        val app = installedApp ?: return@launch

        val apk = savedApkFile(app)
        if (apk == null) {
            markInstallFailure(context.getString(R.string.saved_app_install_missing))
            return@launch
        }
        pendingInstallToken = token

        finalizePendingExternalMount(installed = false)
        awaitPendingExternalMountFinalization()
        pendingExternalInstall?.let(installerManager::cleanup)
        pendingExternalInstall = null
        externalInstallTimeoutJob?.cancel()
        externalInstallBaseline = null
        externalInstallStartTime = null
        val targetPackage = resolveDevicePackageName(app, apk)
        val sourceLabel = appLabel ?: pm.getArchiveLabel(apk)
        val allowMount = app.supportsRootMount(targetPackage)
        val plan = if (token != null) {
            installerManager.resolvePlanForToken(
                token = token,
                target = InstallerManager.InstallTarget.SAVED_APP,
                sourceFile = apk,
                expectedPackage = targetPackage,
                sourceLabel = sourceLabel,
                allowMount = allowMount
            ) ?: run {
                markInstallFailure(context.getString(R.string.install_app_fail, context.getString(R.string.installer_status_not_supported)))
                return@launch
            }
        } else {
            installerManager.resolvePlan(
                InstallerManager.InstallTarget.SAVED_APP,
                apk,
                targetPackage,
                sourceLabel,
                allowMount = allowMount
            )
        }
        if (plan !is InstallerManager.InstallPlan.Mount &&
            isInstalledOnDevice &&
            hasSignatureMismatch(targetPackage, apk)
        ) {
            showSignatureMismatchPrompt(targetPackage)
            return@launch
        }
        isInstalling = true
        deferInstallProgressToasts = plan is InstallerManager.InstallPlan.Internal
        startInstallProgressToasts(mounting = plan is InstallerManager.InstallPlan.Mount)
        if (plan is InstallerManager.InstallPlan.External) {
            runCatching { apk.copyTo(plan.sharedFile, overwrite = true) }
        }
        when (plan) {
            is InstallerManager.InstallPlan.Internal -> {
                if (!pm.requestInstallPackagesPermission()) {
                    val hint = installerManager.formatFailureHint(PackageInstaller.STATUS_FAILURE_BLOCKED, null)
                        ?: context.getString(R.string.installer_hint_blocked)
                    markInstallFailure(context.getString(R.string.install_app_fail, hint))
                    return@launch
                }
                val result = try {
                    sessionInstaller.install(apk, targetPackage, ::enableInstallProgressToasts)
                } catch (_: InstallCancelledException) {
                    stopInstallProgressToasts()
                    isInstalling = false
                    return@launch
                } catch (_: SessionDeadException) {
                    val fallbackPlan = installerManager.createSystemFallbackPlan(
                        target = InstallerManager.InstallTarget.SAVED_APP,
                        sourceFile = apk,
                        expectedPackage = targetPackage,
                        sourceLabel = sourceLabel
                    )
                    launchExternalInstaller(fallbackPlan)
                    return@launch
                }
                when (result) {
                    PackageInstallResult.Success -> {
                        persistInstallMetadata(InstallType.DEFAULT, app.version)
                        isMounted = false
                        markInstallSuccess(context.getString(R.string.saved_app_install_success))
                    }

                    is PackageInstallResult.Conflict -> {
                        if (installerManager.apkSignatureChecksEnabled &&
                            installerManager.isSignatureMismatch(result.message)
                        ) {
                            showSignatureMismatchPrompt(targetPackage)
                        } else {
                            val hint = installerManager.formatFailureHint(
                                PackageInstaller.STATUS_FAILURE_CONFLICT,
                                result.message
                            )
                            val message = hint
                                ?: result.message
                                ?: context.getString(R.string.installer_hint_conflict_generic)
                            markInstallFailure(context.getString(R.string.install_app_fail, message))
                        }
                    }

                    is PackageInstallResult.Failure -> {
                        val hint = installerManager.formatFailureHint(
                            result.status,
                            result.message
                        )
                        val message = hint
                            ?: result.message
                            ?: context.getString(R.string.installer_hint_generic)
                        markInstallFailure(context.getString(R.string.install_app_fail, message))
                    }
                }
            }

            is InstallerManager.InstallPlan.Mount -> {
                try {
                    if (!isInstalledOnDevice) {
                        stopInstallProgressToasts()
                        mountVersionMismatchMessage = context.getString(R.string.install_app_fail_missing_stock)
                        return@launch
                    }
                    val packageInfo = pm.getPackageInfo(apk)
                        ?: throw Exception("Failed to load application info")
                    if (SplitApkPreparer.isSplitArchive(apk) || packageInfo.splitNames?.isNotEmpty() == true) {
                        mountVersionMismatchMessage = context.getString(R.string.mount_split_not_supported)
                        return@launch
                    }
                    val versionName = packageInfo.versionName ?: ""
                    val label = pm.getArchiveLabel(apk, packageInfo)
                        ?: appLabel
                        ?: packageInfo.packageName

                    rootMountCoordinator.execute(
                        RootMountRequest(
                            packageName = packageInfo.packageName,
                            userId = android.os.Process.myUid() / 100_000,
                            operation = RootMountOperation.SWITCH_PATCHED_BUILD,
                            patchedApk = apk,
                            stockApks = stockApksForRootSwitch(packageInfo.packageName),
                            expectedVersionName = versionName,
                            expectedVersionCode = pm.getVersionCode(packageInfo),
                            label = label
                        )
                    ).requireSuccess()
                    val sourceAttributionError = if (plan.installAsPlayStore) {
                        reinstallMountedStockAsPlayStore(
                            context = context,
                            rootInstaller = rootInstaller,
                            rootMountCoordinator = rootMountCoordinator,
                            packageName = packageInfo.packageName,
                            userId = android.os.Process.myUid() / 100_000
                        )
                    } else null
                    val refreshedVersion = packageInfo.versionName ?: app.version
                    val persistedInstallerPackageName =
                        PLAY_STORE_INSTALLER_PACKAGE.takeIf {
                            plan.installAsPlayStore && sourceAttributionError == null
                        }
                    persistInstallMetadata(
                        InstallType.MOUNT,
                        refreshedVersion,
                        packageInfo.packageName,
                        customInstallerPackageName = persistedInstallerPackageName
                    )
                    isMounted = rootInstaller.isAppMounted(packageInfo.packageName)
                    isPlayStoreInstallerSource = usesPersistedPlayStoreMountMode(
                        InstallType.MOUNT,
                        persistedInstallerPackageName
                    )
                    markInstallSuccess(context.getString(R.string.saved_app_install_success))
                    sourceAttributionError?.let { error ->
                        Log.w(tag, "Failed to record Play Store as the installation source", error)
                        context.toast(
                            context.getString(
                                R.string.installer_play_store_attribution_failed,
                                error.simpleMessage() ?: error.javaClass.simpleName.orEmpty()
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Failed to install saved app with root", e)
                    markInstallFailure(context.getString(R.string.saved_app_install_failed))
                } finally {
                    stopInstallProgressToasts()
                }
            }

            // Code adapted from Morphe, see third-party/NOTICE for more information
            // https://github.com/MorpheApp/morphe-manager/commit/7e24461c1454b712da4df21440db6f417c94ce58
            is InstallerManager.InstallPlan.RootPlayStore -> {
                try {
                    installAsPlayStoreWithMountRollback(
                        rootInstaller = rootInstaller,
                        rootMountCoordinator = rootMountCoordinator,
                        apkFile = apk,
                        packageName = targetPackage,
                        userId = android.os.Process.myUid() / 100_000
                    )
                    persistInstallMetadata(InstallType.ROOT_PLAY_STORE, app.version)
                    isMounted = false
                    markInstallSuccess(context.getString(R.string.saved_app_install_success))
                } catch (error: Exception) {
                    Log.e(tag, "Failed to install saved app as Play Store with root", error)
                    markInstallFailure(
                        context.getString(R.string.install_app_fail, error.simpleMessage().orEmpty())
                    )
                }
            }

            is InstallerManager.InstallPlan.Shizuku -> {
                try {
                    shizukuInstaller.install(apk, targetPackage, plan.installerPackageNameOverride)
                    val installType = if (
                        plan.installerPackageNameOverride == ShizukuInstaller.GOOGLE_PLAY_PACKAGE
                    ) InstallType.SHIZUKU_PLAY_STORE else InstallType.SHIZUKU
                    persistInstallMetadata(installType, app.version)
                    isMounted = false
                    markInstallSuccess(context.getString(R.string.saved_app_install_success))
                } catch (error: ShizukuInstaller.InstallerOperationException) {
                    val message = error.message ?: context.getString(R.string.installer_hint_generic)
                    Log.e(tag, "Failed to install saved app with Shizuku", error)
                    markInstallFailure(context.getString(R.string.install_app_fail, message))
                } catch (error: Exception) {
                    Log.e(tag, "Failed to install saved app with Shizuku", error)
                    markInstallFailure(context.getString(R.string.install_app_fail, error.simpleMessage().orEmpty()))
                }
            }

            is InstallerManager.InstallPlan.External -> launchExternalInstaller(plan)
        }
    }

    private suspend fun launchExternalInstaller(plan: InstallerManager.InstallPlan.External) {
        finalizePendingExternalMount(installed = false)
        awaitPendingExternalMountFinalization()
        pendingExternalInstall?.let(installerManager::cleanup)
        externalInstallTimeoutJob?.cancel()
        internalInstallTimeoutJob?.cancel()

        pendingExternalInstall = plan
        externalInstallStartTime = System.currentTimeMillis()
        val baselineInfo = pm.getPackageInfo(plan.expectedPackage)
        externalPackageWasPresentAtStart = baselineInfo != null
        externalInstallBaseline = baselineInfo?.let { info ->
            pm.getVersionCode(info) to info.lastUpdateTime
        }
        baselineInstallSignature = readInstalledSignatureBytes(plan.expectedPackage)
        expectedInstallSignature = readArchiveSignatureBytes(plan.sharedFile)
        // Ensure the staged APK still exists; if not, fail fast.
        if (!plan.sharedFile.exists()) {
            installerManager.cleanup(plan)
            pendingExternalInstall = null
            externalPackageWasPresentAtStart = false
            markInstallFailure(context.getString(R.string.install_app_fail, context.getString(R.string.saved_app_install_missing)))
            return
        }
        pendingExternalMountSuspension =
            if (plan.token == InstallerManager.Token.PlayStore) {
                suspendRootMountForPackageInstall(
                    rootInstaller = rootInstaller,
                    rootMountCoordinator = rootMountCoordinator,
                    packageName = plan.expectedPackage,
                    userId = android.os.Process.myUid() / 100_000,
                    recoveryContext = context
                )
            } else {
                null
            }
        startInstallProgressToasts()
        pendingExternalMountSuspension?.let { suspension ->
            var recoveryOwnsCleanup = false
            val installed = try {
                launchExternalInstallerWithMountFinalization(
                    context = context,
                    targetIntent = plan.intent,
                    suspendedMount = suspension,
                    installChanged = {
                        val info = pm.getPackageInfo(plan.expectedPackage)
                        val updated = info?.let {
                            isUpdatedSinceBaseline(
                                it,
                                externalInstallBaseline,
                                externalInstallStartTime
                            )
                        } ?: false
                        val signatureChanged = shouldTreatAsInstalledBySignature(
                            plan.expectedPackage,
                            externalPackageWasPresentAtStart
                        )
                        info != null && (updated || signatureChanged)
                    },
                    activityTimeoutMs = EXTERNAL_INSTALL_TIMEOUT_MS,
                    onRecoveryOwnershipTransferred = { recoveryOwnsCleanup = true },
                    cleanupFile = plan.sharedFile,
                    cleanup = {}
                )
            } catch (error: Throwable) {
                pendingExternalMountSuspension = null
                finishExternalInstallFailure(
                    plan,
                    context.getString(
                        R.string.install_app_fail,
                        error.simpleMessage().orEmpty()
                    ),
                    cleanupPlan = !recoveryOwnsCleanup
                )
                return
            }
            if (pendingExternalInstall != plan) return
            pendingExternalMountSuspension = null
            if (installed) {
                handleExternalInstallSuccess(plan.expectedPackage)
            } else {
                finishExternalInstallFailure(
                    plan,
                    context.getString(
                        R.string.installer_external_finished_no_change,
                        plan.installerLabel
                    )
                )
            }
            return
        }
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
                        if (tryHandleExternalInstallSuccess(plan)) return@launch
                        delay(INSTALL_MONITOR_POLL_MS)
                    }
                    if (pendingExternalInstall != plan) return@launch
                    finishExternalInstallFailure(
                        plan,
                        context.getString(R.string.installer_external_finished_no_change, plan.installerLabel)
                    )
                } finally {
                    if (launchedActivity === activityDeferred) launchedActivity = null
                }
            }
            return
        }

        try {
            ContextCompat.startActivity(context, plan.intent, null)
        } catch (error: ActivityNotFoundException) {
            finalizePendingExternalMount(installed = false)
            installerManager.cleanup(plan)
            pendingExternalInstall = null
            externalInstallTimeoutJob = null
            externalInstallBaseline = null
            internalInstallTimeoutJob = null
            externalInstallStartTime = null
            externalPackageWasPresentAtStart = false
            expectedInstallSignature = null
            baselineInstallSignature = null
            markInstallFailure(context.getString(R.string.install_app_fail, error.simpleMessage()))
            return
        }

        monitorExternalInstall(plan)
    }

    private fun finishExternalInstallFailure(
        plan: InstallerManager.InstallPlan.External,
        message: String,
        cleanupPlan: Boolean = true
    ) {
        if (pendingExternalInstall != plan) return
        finalizePendingExternalMount(installed = false)
        if (cleanupPlan) installerManager.cleanup(plan)
        pendingExternalInstall = null
        externalInstallTimeoutJob?.cancel()
        externalInstallTimeoutJob = null
        externalInstallBaseline = null
        externalInstallStartTime = null
        externalPackageWasPresentAtStart = false
        expectedInstallSignature = null
        baselineInstallSignature = null
        markInstallFailure(message)
    }

    private fun tryHandleExternalInstallSuccess(plan: InstallerManager.InstallPlan.External): Boolean {
        val info = pm.getPackageInfo(plan.expectedPackage)
        val baseline = externalInstallBaseline
        val updatedSinceStart = info?.let { isUpdatedSinceBaseline(it, baseline, externalInstallStartTime) } ?: false
        val signatureChangedToExpected =
            shouldTreatAsInstalledBySignature(plan.expectedPackage, externalPackageWasPresentAtStart)
        if (info != null && (updatedSinceStart || signatureChangedToExpected)) {
            handleExternalInstallSuccess(plan.expectedPackage)
            return true
        }
        return false
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

    private fun isInstallerX(plan: InstallerManager.InstallPlan.External): Boolean {
        fun normalize(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }
        val label = normalize(plan.installerLabel)
        val tokenPkg = (plan.token as? InstallerManager.Token.Component)?.componentName?.packageName.orEmpty()
        val componentPkg = plan.intent.component?.packageName.orEmpty()
        val pkg = normalize(if (tokenPkg.isNotBlank()) tokenPkg else componentPkg)
        return "installerx" in label || "installerx" in pkg || pkg.startsWith("comrosaninstaller")
    }

    private fun monitorExternalInstall(plan: InstallerManager.InstallPlan.External) {
        externalInstallTimeoutJob?.cancel()
        externalInstallTimeoutJob = viewModelScope.launch {
            val timeoutAt = System.currentTimeMillis() + EXTERNAL_INSTALL_TIMEOUT_MS
            while (isActive) {
                if (pendingExternalInstall != plan) return@launch

                val info = pm.getPackageInfo(plan.expectedPackage)
                if (info != null) {
                    val baseline = externalInstallBaseline
                    val updatedSinceStart = isUpdatedSinceBaseline(
                        info,
                        baseline,
                        externalInstallStartTime
                    )
                    val signatureChangedToExpected =
                        shouldTreatAsInstalledBySignature(plan.expectedPackage, externalPackageWasPresentAtStart)
                    if (updatedSinceStart || signatureChangedToExpected) {
                        handleExternalInstallSuccess(plan.expectedPackage)
                        return@launch
                    }
                }

                val remaining = timeoutAt - System.currentTimeMillis()
                if (remaining <= 0L) break
                delay(INSTALL_MONITOR_POLL_MS)
            }

            if (pendingExternalInstall == plan) {
                val baseline = externalInstallBaseline
                val startTime = externalInstallStartTime
                val info = pm.getPackageInfo(plan.expectedPackage)
                val updatedSinceStart = info?.let {
                    isUpdatedSinceBaseline(it, baseline, startTime)
                } ?: false
                val signatureChangedToExpected =
                    shouldTreatAsInstalledBySignature(plan.expectedPackage, externalPackageWasPresentAtStart)

                if (info != null && (updatedSinceStart || signatureChangedToExpected)) {
                    handleExternalInstallSuccess(plan.expectedPackage)
                } else {
                    finalizePendingExternalMount(installed = false)
                    installerManager.cleanup(plan)
                    pendingExternalInstall = null
                    externalInstallBaseline = null
                    externalInstallStartTime = null
                    internalInstallTimeoutJob = null
                    externalPackageWasPresentAtStart = false
                    expectedInstallSignature = null
                    baselineInstallSignature = null
                    markInstallFailure(context.getString(R.string.installer_external_timeout, plan.installerLabel))
                }
                externalInstallTimeoutJob = null
            }
        }
    }

    private fun handleExternalInstallSuccess(packageName: String) {
        val plan = pendingExternalInstall ?: return
        if (plan.expectedPackage != packageName) return

        val mountRetirement = retirePendingExternalMount()
        pendingExternalInstall = null
        externalInstallTimeoutJob?.cancel()
        internalInstallTimeoutJob?.cancel()
        externalInstallTimeoutJob = null
        externalInstallBaseline = null
        externalInstallStartTime = null
        externalPackageWasPresentAtStart = false
        expectedInstallSignature = null
        baselineInstallSignature = null

        when (plan.target) {
            InstallerManager.InstallTarget.SAVED_APP -> {
                if (installedApp == null) {
                    installerManager.cleanup(plan)
                    isInstalling = false
                    return
                }
                val customInstallerPackageName =
                    (plan.token as? InstallerManager.Token.Component)
                        ?.componentName
                        ?.packageName
                val finalizationJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
                    try {
                        withContext(NonCancellable) {
                            if (!awaitExternalMountRetirement(mountRetirement)) {
                                return@withContext
                            }
                            val attributionError =
                                installerManager.tryFinalizePlayStoreAttribution(plan)
                            val installType = when (plan.token) {
                                InstallerManager.Token.PlayStore -> if (
                                    attributionError == null
                                ) {
                                    InstallType.PLAY_STORE
                                } else {
                                    InstallType.DEFAULT
                                }
                                is InstallerManager.Token.Component -> InstallType.CUSTOM
                                else -> InstallType.DEFAULT
                            }
                            persistInstallMetadata(
                                installType,
                                customInstallerPackageName = customInstallerPackageName
                            )
                            isPlayStoreInstallerSource = withContext(Dispatchers.IO) {
                                installType == InstallType.PLAY_STORE &&
                                    pm.isPlayStoreInstallerSource(packageName)
                            }
                            markInstallSuccess(
                                context.getString(
                                    R.string.installer_external_success,
                                    plan.installerLabel
                                )
                            )
                            attributionError?.let { error ->
                                Log.w(
                                    tag,
                                    "Failed to record Play Store as the installation source",
                                    error
                                )
                                context.toast(
                                    context.getString(
                                        R.string.installer_play_store_attribution_failed,
                                        error.simpleMessage()
                                            ?: error.javaClass.simpleName.orEmpty()
                                    )
                                )
                            }
                        }
                    } finally {
                        installerManager.cleanup(plan)
                        if (externalInstallFinalizationJob === this.coroutineContext[Job]) {
                            externalInstallFinalizationJob = null
                        }
                        isInstalling = false
                    }
                }
                externalInstallFinalizationJob = finalizationJob
                finalizationJob.start()
            }

            else -> {
                installerManager.cleanup(plan)
                isInstalling = false
            }
        }
    }

    private fun retirePendingExternalMount(): Deferred<Throwable?>? {
        val suspension = pendingExternalMountSuspension ?: return null
        val previousFinalization = pendingExternalMountFinalizationJob
        pendingExternalMountSuspension = null

        val retirement = CoroutineScope(Dispatchers.Main).async {
            previousFinalization?.join()
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
                Log.e(tag, "Failed to retire root mount after external install", error)
                error
            }
        }
        pendingExternalMountFinalizationJob = retirement
        return retirement
    }

    private suspend fun awaitExternalMountRetirement(
        retirement: Deferred<Throwable?>?
    ): Boolean {
        val error = retirement?.await()
        if (pendingExternalMountFinalizationJob === retirement) {
            pendingExternalMountFinalizationJob = null
        }
        if (error == null) return true
        markInstallFailure(
            context.getString(
                R.string.failed_to_unmount,
                error.simpleMessage() ?: error.javaClass.simpleName.orEmpty()
            )
        )
        return false
    }

    private suspend fun awaitPendingExternalMountFinalization() {
        val job = pendingExternalMountFinalizationJob ?: return
        job.join()
        if (pendingExternalMountFinalizationJob === job) {
            pendingExternalMountFinalizationJob = null
        }
    }

    private fun finalizePendingExternalMount(installed: Boolean) {
        val suspension = pendingExternalMountSuspension ?: return
        pendingExternalMountSuspension = null
        pendingExternalMountFinalizationJob = CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                withContext(NonCancellable) {
                    if (installed) suspension.retire() else suspension.restore()
                }
            }.onFailure { error ->
                Log.e(tag, "Failed to finalize suspended root mount", error)
            }
        }
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
        return versionChanged || timestampChanged || updatedSinceStart
    }

    fun uninstallSavedInstallation() = viewModelScope.launch {
        val app = installedApp ?: return@launch
        if (!isInstalledOnDevice) return@launch
        val targetPackage = resolveDevicePackageName(app)
        deferUninstallProgressToasts = true
        startUninstallProgressToasts()
        when (val result = runAckpineUninstall(targetPackage)) {
            is Session.State.Failed<UninstallFailure> -> {
                stopUninstallProgressToasts()
                if (result.failure is UninstallFailure.Aborted) return@launch
                val message = result.failure.message.orEmpty()
                context.toast(context.getString(R.string.uninstall_app_fail, message))
            }

            Session.State.Succeeded -> {
                stopUninstallProgressToasts()
                handleUninstallSuccess(app)
            }
        }
    }

    private suspend fun handleUninstallSuccess(currentApp: InstalledApp) {
        if (currentApp.installType == InstallType.SAVED) {
            refreshAppState(currentApp)
            return
        }

        val hasLocalCopy = withContext(Dispatchers.IO) {
            savedApkFile(currentApp) != null
        }

        if (!hasLocalCopy) {
            installedAppRepository.delete(currentApp)
            onBackClick()
            return
        }

        val selection = appliedPatches ?: resolveAppliedSelection(currentApp)

        withContext(Dispatchers.IO) {
            val sourcesSnapshot = patchBundleRepository.sources.first()
            val availableIds = sourcesSnapshot.map { it.uid }.toSet()
            val persistableSelection = selection.filterKeys { it in availableIds }
            val payload = currentApp.selectionPayload
                ?: patchBundleRepository.snapshotSelection(selection)
            installedAppRepository.addOrUpdate(
                currentApp.currentPackageName,
                currentApp.originalPackageName,
                currentApp.version,
                InstallType.SAVED,
                persistableSelection,
                payload,
                useMount = currentApp.useMount,
                updateUseMount = true
            )
        }

        val updatedApp = currentApp.copy(installType = InstallType.SAVED)
        installedApp = updatedApp
        appliedPatches = selection
        isMounted = false
        hasSavedCopy = true
        refreshAppState(updatedApp)
    }

    fun remountSavedInstallation() = viewModelScope.launch {
        if (isRootMountBusy) return@launch
        val app = installedApp ?: return@launch
        val pkgName = resolveDevicePackageName(app)
        // The coordinator removes any old mount and activates the saved payload as one transaction.
        mountOperation = MountOperation.MOUNTING
        isMounted = false
        try {
            val mounted = context.withRepeatingToast(R.string.mounting_ellipsis) {
                mountSavedPayload(pkgName, app)
            }
            if (!mounted) {
                context.toast(context.getString(R.string.saved_app_install_failed))
                return@launch
            }
            isMounted = rootInstaller.isAppMounted(pkgName)
            context.toast(context.getString(R.string.mounted))
        } catch (e: Exception) {
            context.toast(context.getString(R.string.failed_to_mount, e.simpleMessage()))
            Log.e(tag, "Failed to remount", e)
        } finally {
            if (mountOperation == MountOperation.MOUNTING) {
                isMounted = rootInstaller.isAppMounted(pkgName)
            }
            mountOperation = null
        }
    }

    private suspend fun mountSavedPayload(
        packageName: String,
        app: InstalledApp? = installedApp
    ): Boolean = withContext(Dispatchers.IO) {
        if (app?.installType == InstallType.MOUNT && !rootInstaller.isAppMounted(packageName)) {
            check(app.supportsRootMount(packageName)) {
                context.getString(R.string.root_mount_renamed_package_not_supported)
            }
            val installedPackage = pm.getPackageInfo(packageName)
            val installedVersionName = installedPackage?.versionName?.takeIf(String::isNotBlank)
            val installedVersionCode = installedPackage?.let(pm::getVersionCode)
            val fallbackPayload = savedApkFile(app)?.let { apk ->
                pm.getPackageInfo(apk)
                    ?.takeIf { savedPackage ->
                        installedPackage != null &&
                            installedVersionName != null &&
                            savedPackage.packageName == packageName &&
                            savedPackage.versionName == installedVersionName &&
                            pm.getVersionCode(savedPackage) == installedVersionCode
                    }
                    ?.let { apk to it }
            }
            rootMountCoordinator.execute(
                RootMountRequest(
                    packageName,
                    userId = android.os.Process.myUid() / 100_000,
                    operation = RootMountOperation.MOUNT_ONLY,
                    patchedApk = fallbackPayload?.first,
                    stockApks = fallbackPayload?.let {
                        stockApksForRootSwitch(packageName)
                    }.orEmpty(),
                    expectedVersionName = fallbackPayload?.second?.versionName,
                    expectedVersionCode = fallbackPayload?.second?.let(pm::getVersionCode),
                    label = fallbackPayload?.let { (apk, packageInfo) ->
                        pm.getArchiveLabel(apk, packageInfo)
                    } ?: appLabel.orEmpty()
                )
            ).requireSuccess()
            return@withContext true
        }

        val apk = app?.let(::savedApkFile) ?: filesystem.findPatchedAppFile(packageName)
        if (apk == null) {
            check(app?.supportsRootMount(packageName) != false) {
                context.getString(R.string.root_mount_renamed_package_not_supported)
            }
            rootMountCoordinator.execute(
                RootMountRequest(
                    packageName,
                    userId = android.os.Process.myUid() / 100_000,
                    operation = RootMountOperation.MOUNT_ONLY
                )
            ).requireSuccess()
            return@withContext true
        }

        val packageInfo = pm.getPackageInfo(apk) ?: return@withContext false
        if (packageInfo.packageName != packageName) return@withContext false
        check(app?.supportsRootMount(packageInfo.packageName) != false) {
            context.getString(R.string.root_mount_renamed_package_not_supported)
        }

        val versionName = packageInfo.versionName ?: installedApp?.version.orEmpty()
        val label = pm.getArchiveLabel(apk, packageInfo)
            ?: appLabel
            ?: packageInfo.packageName
        rootMountCoordinator.execute(
            RootMountRequest(
                packageName = packageInfo.packageName,
                userId = android.os.Process.myUid() / 100_000,
                operation = RootMountOperation.SWITCH_PATCHED_BUILD,
                patchedApk = apk,
                stockApks = stockApksForRootSwitch(packageInfo.packageName),
                expectedVersionName = versionName,
                expectedVersionCode = pm.getVersionCode(packageInfo),
                label = label
            )
        ).requireSuccess()
        true
    }

    private suspend fun stockApksForRootSwitch(packageName: String): List<File> {
        if (rootInstaller.isAppMounted(packageName)) return emptyList()
        val installed = pm.getPackageInfo(packageName)
            ?: error(context.getString(R.string.root_mount_requires_installed_stock))
        val stockPath = installed.applicationInfo?.sourceDir
            ?: error(context.getString(R.string.install_app_fail_missing_stock))
        val stockApk = File(stockPath)
        check(stockApk.isFile) {
            context.getString(R.string.install_app_fail_missing_stock)
        }
        return listOf(stockApk)
    }

    fun unmountSavedInstallation() = viewModelScope.launch {
        if (isRootMountBusy) return@launch
        val app = installedApp ?: return@launch
        val pkgName = resolveDevicePackageName(app)
        mountOperation = MountOperation.UNMOUNTING
        try {
            context.toast(context.getString(R.string.unmounting))
            rootMountCoordinator.execute(
                RootMountRequest(
                    pkgName,
                    userId = android.os.Process.myUid() / 100_000,
                    operation = RootMountOperation.UNMOUNT
                )
            ).requireSuccess()
            isMounted = false
            context.toast(context.getString(R.string.unmounted))
        } catch (e: Exception) {
            context.toast(context.getString(R.string.failed_to_unmount, e.simpleMessage()))
            Log.e(tag, "Failed to unmount", e)
        } finally {
            mountOperation = null
        }
    }

    fun repairRootMount() = viewModelScope.launch {
        if (isRootMountBusy) return@launch
        val app = installedApp ?: return@launch
        mountOperation = MountOperation.REPAIRING
        try {
            val progressMessage = context.getString(R.string.root_mount_repair_progress)
            var progressToast = context.toastHandle(progressMessage)
            val progressToastJob = launch {
                while (isActive) {
                    delay(INSTALL_PROGRESS_TOAST_INTERVAL_MS)
                    progressToast.cancel()
                    progressToast = context.toastHandle(progressMessage)
                }
            }
            try {
                val packageName = resolveDevicePackageName(app)
                when (val recovery = rootMountCoordinator.execute(
                    RootMountRequest(
                        packageName,
                        userId = android.os.Process.myUid() / 100_000,
                        operation = RootMountOperation.RECOVER
                    )
                )) {
                    is RootMountResult.Success,
                    is RootMountResult.RecoveredToPreviousMount,
                    is RootMountResult.RecoveredToStock -> Unit
                    else -> recovery.requireSuccess()
                }
                check(mountSavedPayload(packageName, app)) {
                    "No compatible saved payload is available"
                }
                isMounted = rootInstaller.isAppMounted(packageName)
                check(isMounted) { "Root mount could not be verified" }
            } finally {
                progressToastJob.cancel()
                progressToast.cancel()
            }
            context.toast(context.getString(R.string.root_mount_repair_success))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            context.toast(context.getString(R.string.root_mount_repair_failed, error.simpleMessage()))
            Log.e(tag, "Failed to repair root mount", error)
        } finally {
            mountOperation = null
        }
    }

    suspend fun readRootMountDiagnostics(): String? {
        val app = installedApp ?: return null
        return runCatching {
            val packageName = resolveDevicePackageName(app)
            val report = rootMountCoordinator.exportDiagnostics(packageName).trim()
            buildString {
                appendLine("============================================================")
                appendLine("Universal Patch Manager - Root Mount Diagnostics")
                appendLine("============================================================")
                appendLine("UPM version: ${BuildConfig.VERSION_NAME}")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Android: ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})")
                appendLine("Architecture: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
                appendLine()
                appendLine(report)
            }
        }.onFailure { error ->
            Log.e(tag, "Failed to read root mount diagnostics", error)
            context.toast(context.getString(R.string.root_mount_diagnostics_read_failed))
        }.getOrNull()
    }

    fun exportRootMountDiagnosticsToPath(
        target: Path,
        onResult: (Boolean) -> Unit = {}
    ) = viewModelScope.launch {
        val content = readRootMountDiagnostics() ?: run {
            onResult(false)
            return@launch
        }
        val succeeded = runCatching {
            withContext(Dispatchers.IO) {
                target.parent?.let { parent -> Files.createDirectories(parent) }
                Files.newBufferedWriter(
                    target,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
                ).use { writer -> writer.write(content) }
            }
        }.onFailure { error ->
            Log.e(tag, "Failed to export root mount diagnostics to $target", error)
        }.isSuccess

        context.toast(
            context.getString(
                if (succeeded) {
                    R.string.root_mount_diagnostics_export_success
                } else {
                    R.string.root_mount_diagnostics_export_failed
                }
            )
        )
        onResult(succeeded)
    }

    fun exportRootMountDiagnosticsToUri(
        target: Uri?,
        onResult: (Boolean) -> Unit = {}
    ) = viewModelScope.launch {
        if (target == null) {
            onResult(false)
            return@launch
        }
        val content = readRootMountDiagnostics() ?: run {
            onResult(false)
            return@launch
        }
        val succeeded = runCatching {
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(target, "wt")
                    ?.bufferedWriter(StandardCharsets.UTF_8)
                    ?.use { writer -> writer.write(content) }
                    ?: throw IOException("Could not open output stream for root mount diagnostics")
            }
        }.onFailure { error ->
            Log.e(tag, "Failed to export root mount diagnostics to $target", error)
        }.isSuccess

        context.toast(
            context.getString(
                if (succeeded) {
                    R.string.root_mount_diagnostics_export_success
                } else {
                    R.string.root_mount_diagnostics_export_failed
                }
            )
        )
        onResult(succeeded)
    }

    fun mountOrUnmount() = viewModelScope.launch {
        if (isRootMountBusy) return@launch
        val app = installedApp ?: return@launch
        val pkgName = resolveDevicePackageName(app)
        try {
            if (isMounted) {
                mountOperation = MountOperation.UNMOUNTING
                context.toast(context.getString(R.string.unmounting))
                rootMountCoordinator.execute(
                    RootMountRequest(
                        pkgName,
                        userId = android.os.Process.myUid() / 100_000,
                        operation = RootMountOperation.UNMOUNT
                    )
                ).requireSuccess()
                isMounted = false
                context.toast(context.getString(R.string.unmounted))
            } else {
                mountOperation = MountOperation.MOUNTING
                val mounted = context.withRepeatingToast(R.string.mounting_ellipsis) {
                    mountSavedPayload(pkgName, app)
                }
                if (!mounted) {
                    context.toast(context.getString(R.string.saved_app_install_failed))
                    return@launch
                }
                isMounted = rootInstaller.isAppMounted(pkgName)
                context.toast(context.getString(R.string.mounted))
            }
        } catch (e: Exception) {
            if (isMounted) {
                context.toast(context.getString(R.string.failed_to_unmount, e.simpleMessage()))
                Log.e(tag, "Failed to unmount", e)
            } else {
                context.toast(context.getString(R.string.failed_to_mount, e.simpleMessage()))
                Log.e(tag, "Failed to mount", e)
            }
        } finally {
            mountOperation = null
        }
    }

    fun uninstall() {
        val app = installedApp ?: return
        when (app.installType) {
            InstallType.DEFAULT,
            InstallType.PLAY_STORE,
            InstallType.ROOT_PLAY_STORE,
            InstallType.CUSTOM,
            InstallType.SHIZUKU,
            InstallType.SHIZUKU_PLAY_STORE -> viewModelScope.launch {
                deferUninstallProgressToasts = true
                startUninstallProgressToasts()
                when (val result = runAckpineUninstall(app.currentPackageName)) {
                    is Session.State.Failed<UninstallFailure> -> {
                        stopUninstallProgressToasts()
                        if (result.failure is UninstallFailure.Aborted) return@launch
                        val message = result.failure.message.orEmpty()
                        context.toast(context.getString(R.string.uninstall_app_fail, message))
                    }

                    Session.State.Succeeded -> {
                        stopUninstallProgressToasts()
                        handleUninstallSuccess(app)
                    }
                }
            }

            InstallType.MOUNT -> viewModelScope.launch {
                try {
                    rootMountCoordinator.execute(
                        RootMountRequest(
                            app.currentPackageName,
                            userId = android.os.Process.myUid() / 100_000,
                            operation = RootMountOperation.UNMOUNT,
                            removeModuleAfterUnmount = true
                        )
                    ).requireSuccess()
                    installedAppRepository.delete(app)
                    onBackClick()
                } catch (error: Exception) {
                    Log.e(tag, "Failed to remove mounted app", error)
                    context.toast(
                        context.getString(
                            R.string.uninstall_app_fail,
                            error.simpleMessage()
                        )
                    )
                }
            }

            InstallType.SAVED -> uninstallSavedInstallation()
        }
    }

    fun exportSavedApp(uri: Uri?) = viewModelScope.launch {
        if (uri == null) return@launch
        val file = savedApkFile()
        if (file == null) {
            context.toast(context.getString(R.string.saved_app_export_failed))
            return@launch
        }

        val success = runCatching {
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)
                    ?.use { output ->
                        file.inputStream().use { input -> input.copyTo(output) }
                    } ?: throw IOException("Could not open output stream for saved app export")
            }
        }.isSuccess

        context.toast(
            context.getString(
                if (success) R.string.saved_app_export_success else R.string.saved_app_export_failed
            )
        )
    }

    fun exportSavedAppToPath(
        target: Path,
        onResult: (Boolean) -> Unit = {}
    ) = viewModelScope.launch {
        val file = savedApkFile()
        if (file == null) {
            context.toast(context.getString(R.string.saved_app_export_failed))
            onResult(false)
            return@launch
        }

        val success = runCatching {
            withContext(Dispatchers.IO) {
                target.parent?.let { Files.createDirectories(it) }
                Files.copy(file.toPath(), target, StandardCopyOption.REPLACE_EXISTING)
            }
        }.isSuccess

        context.toast(
            context.getString(
                if (success) R.string.saved_app_export_success else R.string.saved_app_export_failed
            )
        )
        onResult(success)
    }

    fun removeSavedApp() = viewModelScope.launch {
        val app = installedApp ?: return@launch
        if (app.installType != InstallType.SAVED) return@launch
        if (!clearSavedData(app, deleteRecord = true)) return@launch
        installedApp = null
        appInfo = null
        appliedPatches = null
        isInstalledOnDevice = false
        context.toast(context.getString(R.string.saved_app_removed_toast))
        onBackClick()
    }

    fun deleteSavedEntry() = viewModelScope.launch {
        if (isRootMountBusy) return@launch
        val app = installedApp ?: return@launch
        val deletingSavedRootApp = app.installType == InstallType.MOUNT
        if (deletingSavedRootApp) isDeletingSavedRootApp = true
        if (!clearSavedData(app, deleteRecord = true)) {
            isDeletingSavedRootApp = false
            return@launch
        }
        installedApp = null
        appInfo = null
        appliedPatches = null
        isInstalledOnDevice = false
        context.toast(context.getString(R.string.saved_app_removed_toast))
        onBackClick()
        isDeletingSavedRootApp = false
    }

    fun deleteSavedCopy() = viewModelScope.launch {
        if (isRootMountBusy) return@launch
        val app = installedApp ?: return@launch
        if (!clearSavedData(app, deleteRecord = false)) return@launch
        context.toast(context.getString(R.string.saved_app_copy_removed_toast))
    }

    private suspend fun clearSavedData(app: InstalledApp, deleteRecord: Boolean): Boolean {
        return try {
            if (deleteRecord && app.installType == InstallType.MOUNT) {
                removeRootMountModule(app)
            }
            if (deleteRecord) {
                installedAppRepository.delete(app)
            }
            withContext(Dispatchers.IO) {
                savedApkFile(app)?.delete()
            }
            val shortcutPackageName = batchOriginalPackageName(app)
            val shortcutPackages = prefs.savedAppLauncherShortcutPackages.get()
            val hasRemainingShortcutTarget = withContext(Dispatchers.IO) {
                hasBatchShortcutTarget(
                    records = installedAppRepository.getAll().first(),
                    originalPackageName = shortcutPackageName,
                    hasSavedCopy = { record ->
                        filesystem.getPatchedAppFile(
                            record.currentPackageName,
                            record.version
                        ).isFile
                    }
                )
            }
            if (
                shortcutPackageName in shortcutPackages &&
                !hasRemainingShortcutTarget
            ) {
                prefs.savedAppLauncherShortcutPackages.update(
                    shortcutPackages - shortcutPackageName
                )
            }
            hasSavedCopy = false
            savedApkAbiLabel = null
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e(tag, "Failed to delete saved app", error)
            context.toast(
                context.getString(R.string.saved_app_delete_failed, error.simpleMessage())
            )
            false
        }
    }

    private suspend fun removeRootMountModule(app: InstalledApp) {
        rootMountCoordinator.execute(
            RootMountRequest(
                packageName = resolveDevicePackageName(app),
                userId = android.os.Process.myUid() / 100_000,
                operation = RootMountOperation.UNMOUNT,
                removeModuleAfterUnmount = true
            )
        ).requireSuccess()
    }

    private fun savedApkFile(app: InstalledApp? = this.installedApp): File? {
        val target = app ?: return null
        return filesystem.getPatchedAppFile(
            target.currentPackageName,
            target.version
        ).takeIf(File::isFile)
    }

    private suspend fun refreshAppState(app: InstalledApp) {
        val devicePackageName = resolveDevicePackageName(app)
        val savedFile = withContext(Dispatchers.IO) {
            savedApkFile(app)
        }
        val savedInfo = withContext(Dispatchers.IO) {
            savedFile?.let(pm::getPackageInfo)
        }
        savedFileExtension = withContext(Dispatchers.IO) {
            if (SplitApkPreparer.isSplitArchive(savedFile)) "apks" else "apk"
        }
        val archiveLabel = withContext(Dispatchers.IO) {
            savedFile?.let { pm.getArchiveLabel(it, savedInfo) }
        }
        val displayInfo = if (app.installType == InstallType.SAVED) {
            savedInfo
        } else {
            null
        }
        val installedInfo = withContext(Dispatchers.IO) {
            pm.getPackageInfo(devicePackageName)
        }
        displayedVersionCode = withContext(Dispatchers.IO) {
            app.versionCode
                ?: savedInfo?.versionCodeFor(app.version)
                ?: installedInfo?.versionCodeFor(app.version)
                ?: app.repatchSourcePath?.let(::File)?.takeIf(File::isFile)
                    ?.let(pm::getPackageInfo)
                    ?.takeIf { info ->
                        info.packageName == app.originalPackageName ||
                            info.packageName == devicePackageName
                    }?.versionCodeFor(app.version)
        }
        customInstallerLabel = if (
            app.installType == InstallType.CUSTOM && installedInfo != null
        ) {
            withContext(Dispatchers.IO) {
                pm.getInstallerLabel(
                    devicePackageName,
                    app.customInstallerPackageName
                )
            }
        } else {
            null
        }
        isPlayStoreInstallerSource = usesPersistedPlayStoreMountMode(
            app.installType,
            app.customInstallerPackageName
        )
        val mountedNow = if (app.installType == InstallType.MOUNT) {
            runCatching { rootInstaller.isAppMounted(devicePackageName) }.getOrDefault(isMounted)
        } else {
            false
        }
        isMounted = mountedNow
        hasSavedCopy = savedFile != null
        savedApkAbiLabel = withContext(Dispatchers.IO) {
            savedFile?.savedApkAbiLabel(context)
        }

        val installedLabel = if (!mountedNow) {
            installedInfo?.let { info ->
                runCatching { with(pm) { info.label() } }.getOrNull()
            }
        } else {
            null
        }
        val fallbackPackageName = if (app.installType == InstallType.SAVED) {
            app.originalPackageName.takeIf { it.isNotBlank() }
                ?: savedAppBasePackage(app.currentPackageName)
        } else {
            app.currentPackageName
        }
        appLabel = archiveLabel
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: installedLabel
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            ?: fallbackPackageName

        if (installedInfo != null) {
            isInstalledOnDevice = true
            appInfo = displayInfo ?: installedInfo
        } else {
            isInstalledOnDevice = false
            appInfo = savedInfo
        }
    }

    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_PACKAGE_ADDED,
                Intent.ACTION_PACKAGE_REPLACED -> {
                    val pkg = intent.data?.schemeSpecificPart ?: return
                    val currentApp = installedApp ?: return
                    if (pkg != resolveDevicePackageNameFromState(currentApp)) return

                    if (pendingExternalInstall != null) {
                        handleExternalInstallSuccess(pkg)
                    } else {
                        viewModelScope.launch { refreshAppState(currentApp) }
                    }
                }

                Intent.ACTION_PACKAGE_REMOVED -> {
                    if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
                    val pkg = intent.data?.schemeSpecificPart ?: return
                    val currentApp = installedApp ?: return
                    if (pkg != resolveDevicePackageNameFromState(currentApp)) return
                    viewModelScope.launch {
                        refreshAppState(currentApp)
                        isMounted = false
                    }
                }
            }
        }
    }.also {
        ContextCompat.registerReceiver(
            context,
            it,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onCleared() {
        super.onCleared()
        context.unregisterReceiver(packageChangeReceiver)
        val rootInstallerStillOpen =
            pendingExternalInstall?.token == InstallerManager.Token.PlayStore &&
                pendingExternalMountSuspension != null
        if (!rootInstallerStillOpen) {
            pendingExternalInstall?.let(installerManager::cleanup)
            pendingExternalInstall = null
            finalizePendingExternalMount(installed = false)
        }
        launchedActivity = null
        internalInstallTimeoutJob?.cancel()
        externalInstallTimeoutJob?.cancel()
        externalInstallTimeoutJob = null
        internalInstallTimeoutJob = null
        externalInstallBaseline = null
        externalInstallStartTime = null
        expectedInstallSignature = null
        baselineInstallSignature = null
        stopInstallProgressToasts()
    }

    fun clearInstallResult() {
        installResult = null
    }

    enum class ReplaceSavedBundleResult {
        SUCCESS,
        APP_NOT_FOUND,
        TARGET_NOT_FOUND,
        INCOMPATIBLE,
        FAILED
    }

    suspend fun replaceSavedBundle(
        currentUid: Int,
        targetUid: Int,
        requiredPatchesLowercase: Set<String>,
        allowIncompatible: Boolean = false
    ): ReplaceSavedBundleResult = withContext(Dispatchers.Default) {
        val app = installedApp ?: return@withContext ReplaceSavedBundleResult.APP_NOT_FOUND
        if (app.installType != InstallType.SAVED) {
            return@withContext ReplaceSavedBundleResult.APP_NOT_FOUND
        }
        if (requiredPatchesLowercase.isEmpty() && !allowIncompatible) {
            return@withContext ReplaceSavedBundleResult.INCOMPATIBLE
        }

        if (!allowIncompatible) {
            val bundleInfoSnapshot = patchBundleRepository.bundleInfoFlow.first()
            val targetInfo = bundleInfoSnapshot[targetUid]
                ?: return@withContext ReplaceSavedBundleResult.INCOMPATIBLE
            val availablePatches = targetInfo.patches
                .mapTo(mutableSetOf()) { it.name.trim().lowercase() }
            if (!requiredPatchesLowercase.all { it in availablePatches }) {
                return@withContext ReplaceSavedBundleResult.INCOMPATIBLE
            }
        }

        val sourcesList = patchBundleRepository.sources.first()
        val targetSource = sourcesList.firstOrNull { it.uid == targetUid }
            ?: return@withContext ReplaceSavedBundleResult.TARGET_NOT_FOUND

        val selection = appliedPatches ?: resolveAppliedSelection(app)
        val updatedSelection = selection.toMutableMap()
        val removedPatches = updatedSelection.remove(currentUid).orEmpty()
        if (removedPatches.isEmpty()) {
            return@withContext ReplaceSavedBundleResult.APP_NOT_FOUND
        }
        val merged = updatedSelection[targetUid]?.toMutableSet() ?: mutableSetOf()
        merged.addAll(removedPatches)
        if (merged.isNotEmpty()) {
            updatedSelection[targetUid] = merged
        } else {
            updatedSelection.remove(targetUid)
        }

        val bundleInfoSnapshot = patchBundleRepository.allBundlesInfoFlow.first()
        val updatedPayload = app.selectionPayload?.let { payload ->
            val remapped = payload.remapLocalBundles(sourcesList)
            val bundles = remapped.bundles.toMutableList()
            val bundleIndex = bundles.indexOfFirst { it.bundleUid == currentUid }
            if (bundleIndex == -1) return@let remapped

            val updatedBundle = bundles[bundleIndex].copy(
                bundleUid = targetSource.uid,
                displayName = targetSource.displayTitle,
                sourceName = targetSource.patchBundle?.manifestAttributes?.name ?: targetSource.name,
                sourceEndpoint = targetSource.asRemoteOrNull?.endpoint
            )
            bundles[bundleIndex] = updatedBundle
            remapped.copy(bundles = bundles)
        } ?: updatedSelection.toPayload(sourcesList, bundleInfoSnapshot)

        installedAppRepository.addOrUpdate(
            currentPackageName = app.currentPackageName,
            originalPackageName = app.originalPackageName,
            version = app.version,
            installType = app.installType,
            patchSelection = updatedSelection,
            selectionPayload = updatedPayload
        )

        installedApp = app.copy(selectionPayload = updatedPayload)
        appliedPatches = updatedSelection

        ReplaceSavedBundleResult.SUCCESS
    }

    fun setSignatureInjectionEnabled(enabled: Boolean) {
        val entryKey = installedApp?.currentPackageName ?: return
        viewModelScope.launch {
            val updated = installedAppRepository.updateSignatureWorkflow(entryKey, enabled)
            if (updated != null && installedApp?.currentPackageName == entryKey) {
                installedApp = updated
            }
        }
    }

    fun dismissSignatureMismatchPrompt() {
        signatureMismatchPackage = null
        pendingSignatureMismatchPackage = null
        pendingInstallToken = null
    }

    fun confirmSignatureMismatchInstall() {
        val targetPackage = pendingSignatureMismatchPackage ?: return
        val retryToken = pendingInstallToken
        signatureMismatchPackage = null
        pendingSignatureMismatchPackage = null
        pendingInstallToken = null
        stopInstallProgressToasts()
        deferUninstallProgressToasts = true
        startUninstallProgressToasts()
        viewModelScope.launch {
            when (val result = runAckpineUninstall(targetPackage)) {
                is Session.State.Failed<UninstallFailure> -> {
                    stopUninstallProgressToasts()
                    if (result.failure is UninstallFailure.Aborted) return@launch
                    val failureMessage = context.getString(
                        R.string.uninstall_app_fail,
                        result.failure.message.orEmpty()
                    )
                    context.toast(failureMessage)
                    markUninstallFailure(failureMessage)
                }

                Session.State.Succeeded -> {
                    stopUninstallProgressToasts()
                    installSavedApp(retryToken)
                }
            }
        }
    }

    companion object {
        private const val EXTERNAL_INSTALL_TIMEOUT_MS = 60_000L
        private const val EXTERNAL_INSTALLER_RESULT_GRACE_MS = 1500L
        private const val EXTERNAL_INSTALLER_POST_CLOSE_TIMEOUT_MS = 30_000L
        private const val INSTALL_MONITOR_POLL_MS = 1000L
        private const val INSTALL_PROGRESS_TOAST_INTERVAL_MS = 2500L
    }
}

enum class MountWarningAction {
    INSTALL,
    UPDATE,
    UNINSTALL
}

enum class MountWarningReason {
    PRIMARY_IS_MOUNT_FOR_NON_MOUNT_APP,
    PRIMARY_NOT_MOUNT_FOR_MOUNT_APP
}

data class MountWarningState(
    val action: MountWarningAction,
    val reason: MountWarningReason
)

sealed class InstallResult {
    data class Success(val message: String) : InstallResult()
    data class Failure(val message: String) : InstallResult()
    data class UninstallError(val message: String) : InstallResult()
}
