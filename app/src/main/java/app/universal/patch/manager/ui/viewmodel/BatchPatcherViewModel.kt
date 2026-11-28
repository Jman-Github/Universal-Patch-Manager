/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.universal.patch.manager.ui.viewmodel

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.universal.patch.manager.BuildConfig
import app.universal.patch.manager.R
import app.universal.patch.manager.data.platform.Filesystem
import app.universal.patch.manager.data.room.apps.downloaded.DownloadedApp
import app.universal.patch.manager.domain.batch.BatchActivityRequest
import app.universal.patch.manager.domain.batch.BatchFallbackInstallRequest
import app.universal.patch.manager.domain.batch.BatchInstallPolicy
import app.universal.patch.manager.domain.batch.BatchItemState
import app.universal.patch.manager.domain.batch.BatchPatchCoordinator
import app.universal.patch.manager.domain.batch.BatchPatchItem
import app.universal.patch.manager.domain.batch.BatchPlanRequestKey
import app.universal.patch.manager.domain.batch.BatchRootDowngradeRequest
import app.universal.patch.manager.domain.batch.BatchPhase
import app.universal.patch.manager.domain.batch.ManualBatchPatchQueue
import app.universal.patch.manager.domain.batch.PendingRequestRegistry
import app.universal.patch.manager.domain.batch.batchPlanRequestKey
import app.universal.patch.manager.domain.batch.blocksBatchReplacement
import app.universal.patch.manager.domain.batch.canOpenBatchPlan
import app.universal.patch.manager.domain.batch.isUserBatchSelectionAllowed
import app.universal.patch.manager.domain.batch.matchesBatchPlanRequest
import app.universal.patch.manager.domain.installer.InstallerManager
import app.universal.patch.manager.domain.repository.DownloadedAppRepository
import app.universal.patch.manager.domain.repository.DownloaderPluginRepository
import app.universal.patch.manager.network.downloader.LoadedDownloaderPlugin
import app.universal.patch.manager.network.downloader.ParceledDownloaderData
import app.universal.patch.manager.patcher.logger.isVerbosePatcherExportLog
import app.universal.patch.manager.patcher.split.SplitApkInspector
import app.universal.patch.manager.patcher.split.SplitApkPreparer
import app.universal.patch.manager.plugin.downloader.DownloadUrl
import app.universal.patch.manager.plugin.downloader.GetScope
import app.universal.patch.manager.plugin.downloader.PluginHostApi
import app.universal.patch.manager.plugin.downloader.Package as DownloaderPackage
import app.universal.patch.manager.plugin.downloader.UserInteractionException
import app.universal.patch.manager.ui.BatchActivityProxyActivity
import app.universal.patch.manager.ui.model.SelectedApp
import app.universal.patch.manager.util.Options
import app.universal.patch.manager.util.PM
import app.universal.patch.manager.util.PatchSelection
import app.universal.patch.manager.util.simpleMessage
import app.universal.patch.manager.util.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

internal data class BatchDownloaderMetadata(
    val versionName: String?,
    val versionCode: Long?
)

internal fun handleBatchPlanRequestResult(
    accepted: Boolean,
    onRejected: () -> Unit
): Boolean {
    if (!accepted) onRejected()
    return accepted
}

internal fun isCurrentBatchPluginAction(
    currentToken: Any?,
    completedToken: Any
): Boolean = currentToken === completedToken

private data class ActiveBatchPluginAction(
    val plugin: LoadedDownloaderPlugin,
    val token: Any,
    val job: Job
)

internal fun resolveBatchDownloaderMetadata(
    packageVersion: String?,
    downloadUrl: String?,
    reportedVersion: String?,
    requestedVersion: String?
): BatchDownloaderMetadata {
    val explicitVersions = listOfNotNull(
        packageVersion,
        reportedVersion,
        requestedVersion
    ).map(String::trim).filter(String::isNotBlank)
    val versionName = explicitVersions.firstNotNullOfOrNull(::extractBatchDisplayVersion)
        ?: explicitVersions.firstNotNullOfOrNull { raw ->
            raw.removePrefix("v").takeIf { normalized ->
                normalized.firstOrNull()?.isDigit() == true &&
                    !looksLikeBatchVersionCode(normalized)
            }
        }
        ?: downloadUrl?.let(::extractBatchDisplayVersion)
    val versionCode = (explicitVersions + listOfNotNull(downloadUrl))
        .firstNotNullOfOrNull(::extractBatchVersionCode)
    return BatchDownloaderMetadata(versionName, versionCode)
}

private fun extractBatchDisplayVersion(raw: String): String? {
    val decoded = decodeBatchDownloaderText(raw).substringBefore('?').substringBefore('#')
    val exact = decoded.trim().removePrefix("v")
    if (
        exact.firstOrNull()?.isDigit() == true &&
        exact.any { it == '.' } &&
        !looksLikeBatchVersionCode(exact)
    ) return exact

    val pattern = Regex(
        """\d+(?:[._-]\d+){1,5}(?:[._-](?:release|beta|alpha|rc|build)[A-Za-z0-9.]*)?""",
        RegexOption.IGNORE_CASE
    )
    return pattern.findAll(decoded)
        .map { match ->
            match.value
                .trim('.', '-', '_')
                .replace('_', '.')
                .replace(Regex("(?<=\\d)-(\\d)")) { ".${it.groupValues[1]}" }
        }
        .filterNot(::looksLikeBatchVersionCode)
        .maxByOrNull(String::length)
}

private fun extractBatchVersionCode(raw: String): Long? {
    val trimmed = raw.trim()
    trimmed.toLongOrNull()?.takeIf { it > BATCH_VERSION_CODE_THRESHOLD }?.let { return it }
    return Regex(
        """(?i)(?:version[_-]?code|vc)[^0-9]{0,4}(\d{4,})"""
    ).find(decodeBatchDownloaderText(raw))?.groupValues?.getOrNull(1)?.toLongOrNull()
}

private fun looksLikeBatchVersionCode(value: String): Boolean {
    val normalized = value.trim()
    val numericOnly = normalized.all { it.isDigit() || it == '.' }
    val first = normalized.split('.', '-', '_').firstOrNull().orEmpty()
    val firstNumber = first.toLongOrNull() ?: return false
    return numericOnly &&
        firstNumber > BATCH_VERSION_CODE_THRESHOLD &&
        normalized.count { it == '.' } <= 1
}

private fun decodeBatchDownloaderText(raw: String): String = runCatching {
    URLDecoder.decode(raw, StandardCharsets.UTF_8.name())
}.getOrDefault(raw)

private const val BATCH_VERSION_CODE_THRESHOLD = 200_000L

// Code adapted from Morphe, see third-party/NOTICE for more information
// https://github.com/MorpheApp/morphe-manager/pull/795
@OptIn(PluginHostApi::class)
class BatchPatcherViewModel(
    private val app: Application,
    private val fs: Filesystem,
    private val pm: PM,
    private val coordinator: BatchPatchCoordinator,
    private val manualQueue: ManualBatchPatchQueue,
    pluginsRepository: DownloaderPluginRepository,
    private val downloadedAppRepository: DownloadedAppRepository
) : ViewModel() {
    val state = coordinator.state
    val plugins = pluginsRepository.loadedPluginsFlow
    val downloadedApps = downloadedAppRepository.getAll()
    /** Key of the queue item an APK is being attached to. */
    var attachTarget: String? by mutableStateOf(null)
        private set

    /** Key of the queue item whose patches are open for editing. */
    var editingItemKey: String? = null
    private var pluginAction: ActiveBatchPluginAction? by mutableStateOf(null)
    private var pluginActivityRequestId: String? = null
    val activePluginAction get() = pluginAction?.plugin?.id
    private val pendingActivityRequests = PendingRequestRegistry<ActivityResult>()
    internal var rootDowngradeRequest: BatchRootDowngradeRequest? by mutableStateOf(null)
        private set
    internal var fallbackInstallRequest: BatchFallbackInstallRequest? by mutableStateOf(null)
        private set
    private val launchActivityChannel = Channel<Intent>()
    val launchActivityFlow = launchActivityChannel.receiveAsFlow()
    private val storageSelectionChannel = Channel<Unit>(Channel.CONFLATED)
    val requestStorageSelection = storageSelectionChannel.receiveAsFlow()
    private var initialized = false
    private var manualPlan = false
    private var activeRequestKey: BatchPlanRequestKey? = null
    private val ownedWorkspacePaths = mutableSetOf<String>()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        viewModelScope.launch {
            state.collect { snapshot ->
                if (snapshot?.phase == BatchPhase.FINISHED) {
                    snapshot.items
                        .filter { it.state == BatchItemState.SUCCEEDED }
                        .forEach { deleteOwnedInput(it.input) }
                }
            }
        }
        viewModelScope.launch {
            coordinator.activityRequests.collect(::registerActivityRequest)
        }
        viewModelScope.launch {
            coordinator.rootDowngradeRequests.collect { request ->
                rootDowngradeRequest?.completion?.complete(false)
                rootDowngradeRequest = request
                request.completion.invokeOnCompletion {
                    viewModelScope.launch {
                        if (rootDowngradeRequest === request) {
                            rootDowngradeRequest = null
                        }
                    }
                }
            }
        }
        viewModelScope.launch {
            coordinator.fallbackInstallRequests.collect { request ->
                fallbackInstallRequest?.completion?.complete(false)
                fallbackInstallRequest = request
                request.completion.invokeOnCompletion {
                    viewModelScope.launch {
                        if (fallbackInstallRequest === request) {
                            fallbackInstallRequest = null
                        }
                    }
                }
            }
        }
    }

    private suspend fun registerActivityRequest(request: BatchActivityRequest) {
        if (!pendingActivityRequests.register(request.requestId, request.completion)) {
            request.completion.completeExceptionally(
                IllegalStateException("Duplicate activity request")
            )
            return
        }
        request.completion.invokeOnCompletion {
            pendingActivityRequests.remove(request.requestId, request.completion)
        }
        val callerWillFinalize = request.callerWillFinalize
        val callerRegistered = callerWillFinalize != null &&
            BatchActivityProxyActivity.registerCaller(
                request.requestId,
                callerWillFinalize
            )
        if (request.rootRecoveryRequestId != null && !callerRegistered) {
            callerWillFinalize?.complete(false)
            pendingActivityRequests.fail(
                request.requestId,
                IllegalStateException("Duplicate batch activity caller")
            )
            return
        }
        try {
            launchActivityChannel.send(
                BatchActivityProxyActivity.createIntent(
                    app,
                    request.requestId,
                    request.intent,
                    request.rootRecoveryRequestId
                )
            )
        } catch (error: Throwable) {
            if (callerRegistered) {
                BatchActivityProxyActivity.failCaller(request.requestId, error)
            }
            pendingActivityRequests.fail(request.requestId, error)
            return
        }
        if (callerRegistered) {
            viewModelScope.launch {
                try {
                    val result = BatchActivityProxyActivity.awaitCallerResult(request.requestId)
                    if (!pendingActivityRequests.complete(request.requestId, result)) {
                        callerWillFinalize.complete(false)
                    }
                } catch (error: CancellationException) {
                    callerWillFinalize.complete(false)
                    throw error
                } catch (error: Throwable) {
                    callerWillFinalize.complete(false)
                    pendingActivityRequests.fail(request.requestId, error)
                }
            }
        }
    }

    private suspend fun requestActivityResult(intent: Intent): ActivityResult {
        val requestId = UUID.randomUUID().toString()
        val cancelledResult = ActivityResult(Activity.RESULT_CANCELED, null)
        val request = BatchActivityRequest(
            requestId = requestId,
            intent = intent,
            completion = CompletableDeferred()
        )
        pluginActivityRequestId = requestId
        return try {
            withTimeout(ACTIVITY_REQUEST_TIMEOUT_MS) {
                registerActivityRequest(request)
                request.completion.await()
            }
        } catch (_: TimeoutCancellationException) {
            pendingActivityRequests.cancel(requestId, cancelledResult)
            cancelledResult
        } finally {
            if (pluginActivityRequestId == requestId) pluginActivityRequestId = null
            pendingActivityRequests.remove(requestId, request.completion)
        }
    }

    fun canOpenPlan(
        packageNames: List<String>,
        manualQueue: Boolean = false,
        scheduled: Boolean = false,
        showExistingResult: Boolean = false,
        requestId: String? = null
    ): Boolean {
        val requestedKey = batchPlanRequestKey(
            packageNames = packageNames,
            manualQueue = manualQueue,
            showExistingResult = showExistingResult,
            scheduled = scheduled,
            requestId = requestId
        )
        val current = state.value
        val canOpen = current?.items?.none { it.saving } != false && canOpenBatchPlan(
            currentPhase = current?.phase,
            currentRequestKey = activeRequestKey,
            currentPackageNames = current?.items.orEmpty().map { it.packageName },
            currentScheduled = current?.scheduled ?: false,
            requestedKey = requestedKey
        )
        if (!canOpen) {
            app.toast(app.getString(R.string.patcher_already_running))
        }
        return canOpen
    }

    fun requestIdForPlan(
        packageNames: List<String>,
        manualQueue: Boolean = false,
        scheduled: Boolean = false,
        showExistingResult: Boolean = false
    ): String {
        val requestedKey = batchPlanRequestKey(
            packageNames = packageNames,
            manualQueue = manualQueue,
            showExistingResult = showExistingResult,
            scheduled = scheduled
        )
        val current = state.value
        return activeRequestKey
            ?.takeIf {
                current?.phase?.blocksBatchReplacement == true &&
                    it.matchesBatchPlanRequest(requestedKey)
            }
            ?.requestId
            ?: UUID.randomUUID().toString()
    }

    fun ensurePlan(
        packageNames: List<String>,
        startImmediately: Boolean = false,
        scheduled: Boolean = false,
        showExistingResult: Boolean = false,
        requestId: String? = null
    ) {
        val packages = packageNames.distinct()
        val requestKey = batchPlanRequestKey(
            packageNames = packages,
            showExistingResult = showExistingResult,
            scheduled = scheduled,
            requestId = requestId
        )
        if (state.value?.items?.any { it.saving } == true) return
        if (
            initialized &&
            activeRequestKey == requestKey &&
            state.value != null
        ) {
            if (startImmediately && state.value?.phase == BatchPhase.PREFLIGHT) {
                coordinator.start()
            }
            return
        }

        val existingState = state.value
        if (
            requestId != null &&
            existingState?.requestId == requestId &&
            existingState.scheduled == scheduled &&
            existingState.items.map { it.packageName }.toSet() == packages.toSet()
        ) {
            initialized = true
            manualPlan = false
            activeRequestKey = requestKey
            if (startImmediately && existingState.phase == BatchPhase.PREFLIGHT) {
                coordinator.start()
            }
            return
        }

        if (initialized) {
            if (state.value?.phase?.blocksBatchReplacement == true) return
            coordinator.clear()
            resetPlanState()
            cleanupOwnedWorkspaceFiles()
        }
        initialized = true
        manualPlan = false
        activeRequestKey = requestKey

        if (
            !scheduled &&
            !showExistingResult &&
            !isUserBatchSelectionAllowed(packages.size)
        ) {
            coordinator.showUnavailableResult(
                packages,
                app.getString(R.string.batch_patch_minimum_apps)
            )
            return
        }
        val current = state.value
        if (showExistingResult) {
            if (
                current?.phase == BatchPhase.FINISHED &&
                current.scheduled == scheduled &&
                current.items.map { it.packageName }.toSet() == packages.toSet()
            ) return
            coordinator.clear()
            if (!coordinator.restoreLastResult(packages, scheduled = scheduled)) {
                coordinator.showUnavailableResult(
                    packages,
                    app.getString(R.string.batch_patch_result_unavailable)
                )
            }
            return
        }

        if (current?.phase?.blocksBatchReplacement == true) return
        if (
            current?.phase == BatchPhase.PREFLIGHT &&
            current.items.map { it.packageName } == packages
        ) {
            if (startImmediately) coordinator.start()
            return
        }
        if (current != null) coordinator.clear()
        if (packages.isEmpty()) return
        if (
            requestId != null &&
            coordinator.restoreLastResult(
                packageNames = packages,
                scheduled = scheduled,
                requestId = requestId
            )
        ) return

        val accepted = handleBatchPlanRequestResult(
            coordinator.plan(
                packageNames = packages,
                scheduled = scheduled,
                requestId = requestId
            )
        ) {
            resetPlanState()
            app.toast(app.getString(R.string.patcher_already_running))
        }
        if (!accepted) return
        if (startImmediately) {
            viewModelScope.launch {
                val planned = state.first {
                    it != null && it.phase != BatchPhase.PLANNING
                }
                if (planned?.phase == BatchPhase.PREFLIGHT) coordinator.start()
            }
        }
    }

    fun ensureManualPlan(
        packageNames: List<String>,
        startImmediately: Boolean = false,
        requestId: String? = null
    ) {
        val entries = manualQueue.snapshot()
        val packages = entries.map { it.input.packageName }
            .ifEmpty { packageNames }
            .distinct()
        val requestKey = batchPlanRequestKey(
            packageNames = packages,
            manualQueue = true,
            requestId = requestId
        )
        if (state.value?.items?.any { it.saving } == true) return
        if (
            initialized &&
            activeRequestKey == requestKey &&
            state.value != null
        ) {
            if (startImmediately && state.value?.phase == BatchPhase.PREFLIGHT) {
                coordinator.start()
            }
            return
        }

        val existingState = state.value
        if (
            requestId != null &&
            existingState?.requestId == requestId &&
            !existingState.scheduled &&
            existingState.items.map { it.packageName }.toSet() == packages.toSet()
        ) {
            initialized = true
            manualPlan = true
            activeRequestKey = requestKey
            if (startImmediately && existingState.phase == BatchPhase.PREFLIGHT) {
                coordinator.start()
            }
            return
        }

        if (initialized) {
            if (state.value?.phase?.blocksBatchReplacement == true) return
            coordinator.clear()
            resetPlanState()
            cleanupOwnedWorkspaceFiles()
        }
        initialized = true
        manualPlan = true
        activeRequestKey = requestKey

        if (state.value != null) coordinator.clear()
        if (
            requestId != null &&
            coordinator.restoreLastResult(
                packageNames = packages,
                requestId = requestId
            )
        ) return
        if (!isUserBatchSelectionAllowed(entries.size)) {
            coordinator.showUnavailableResult(
                packages,
                app.getString(R.string.batch_patch_minimum_apps)
            )
            return
        }
        val accepted = handleBatchPlanRequestResult(
            coordinator.planManual(entries, requestId = requestId)
        ) {
            resetPlanState()
            app.toast(app.getString(R.string.patcher_already_running))
        }
        if (!accepted) return
        if (startImmediately) {
            viewModelScope.launch {
                val planned = state.first {
                    it != null && it.phase != BatchPhase.PLANNING
                }
                if (planned?.phase == BatchPhase.PREFLIGHT) coordinator.start()
            }
        }
    }

    fun requestAttach(key: String) {
        cancelPluginAction()
        attachTarget = key
    }

    fun dismissAttachSelector() {
        cancelPluginAction()
        attachTarget = null
    }

    fun requestLocalSelection() {
        storageSelectionChannel.trySend(Unit)
    }

    fun onApkPicked(uri: Uri?) {
        val key = attachTarget ?: return
        if (uri == null) return
        viewModelScope.launch {
            val copied = withContext(Dispatchers.IO) { copyToWorkspace(uri) }
            if (copied == null) {
                app.toast(app.getString(R.string.failed_to_load_apk))
                return@launch
            }
            val previousInput = state.value?.items
                ?.firstOrNull { it.key == key }
                ?.input
            val resolved = coordinator.attachApk(key, copied)
            resolved?.let { syncManualQueueEntry(it, copied) }
            deleteOwnedInput(previousInput, except = copied)
            dismissAttachSelector()
        }
    }

    fun onApkFilePicked(file: File?) {
        val key = attachTarget ?: return
        if (file == null) return
        viewModelScope.launch {
            val copied = withContext(Dispatchers.IO) { copyToWorkspace(file) }
            if (copied == null) {
                app.toast(app.getString(R.string.failed_to_load_apk))
                return@launch
            }
            val previousInput = state.value?.items
                ?.firstOrNull { it.key == key }
                ?.input
            val resolved = coordinator.attachApk(key, copied)
            resolved?.let { syncManualQueueEntry(it, copied) }
            deleteOwnedInput(previousInput, except = copied)
            dismissAttachSelector()
        }
    }

    fun selectDownloadedApp(downloadedApp: DownloadedApp) {
        val key = attachTarget ?: return
        val packageName = state.value?.items
            ?.firstOrNull { it.key == key }
            ?.packageName
            ?: return
        if (downloadedApp.packageName != packageName) return
        cancelPluginAction()
        viewModelScope.launch {
            val previousInput = state.value?.items
                ?.firstOrNull { it.key == key }
                ?.input
            runCatching {
                val file = downloadedAppRepository.getApkFileForApp(downloadedApp)
                withContext(Dispatchers.IO) {
                    downloadedAppRepository.get(
                        downloadedApp.packageName,
                        downloadedApp.version,
                        markUsed = true
                    )
                }
                val packageInfo = withContext(Dispatchers.IO) {
                    if (SplitApkPreparer.isSplitArchive(file)) {
                        val extracted = SplitApkInspector.extractRepresentativeApk(
                            source = file,
                            workspace = fs.tempDir
                        )
                        try {
                            extracted?.file?.let(pm::getPackageInfo)
                        } finally {
                            extracted?.cleanup?.invoke()
                        }
                    } else {
                        pm.getPackageInfo(file)
                    }
                }
                val resolved = coordinator.attachSource(
                    key,
                    SelectedApp.Local(
                        packageName = packageName,
                        version = packageInfo?.versionName
                            ?.takeIf(String::isNotBlank)
                            ?: downloadedApp.version,
                        file = file,
                        temporary = false,
                        versionCode = packageInfo?.let(pm::getVersionCode)
                    )
                )
                resolved?.let { syncManualQueueEntry(it) }
                deleteOwnedInput(previousInput)
            }.onSuccess {
                dismissAttachSelector()
            }.onFailure { error ->
                Log.e(TAG, "Failed to select downloaded app", error)
                app.toast(app.getString(R.string.failed_to_load_apk))
            }
        }
    }

    private fun cancelPluginAction() {
        pluginActivityRequestId?.let { requestId ->
            pendingActivityRequests.cancel(
                requestId,
                ActivityResult(Activity.RESULT_CANCELED, null)
            )
        }
        pluginActivityRequestId = null
        pluginAction?.job?.cancel()
        pluginAction = null
    }

    fun searchUsingPlugin(plugin: LoadedDownloaderPlugin) {
        val key = attachTarget ?: return
        val target = state.value?.items?.firstOrNull { it.key == key } ?: return
        val packageName = target.packageName
        val requestedVersion = target.version
        cancelPluginAction()
        val actionToken = Any()
        val actionJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val previousInput = state.value?.items
                ?.firstOrNull { it.key == key }
                ?.input
            try {
                val scope = object : GetScope {
                    override val hostPackageName = app.packageName
                    override val pluginPackageName = plugin.packageName
                    override suspend fun requestStartActivity(intent: Intent): Intent? {
                        val result = requestActivityResult(intent)
                        return when (result.resultCode) {
                            Activity.RESULT_OK -> result.data
                            Activity.RESULT_CANCELED ->
                                throw UserInteractionException.Activity.Cancelled()
                            else -> throw UserInteractionException.Activity.NotCompleted(
                                result.resultCode,
                                result.data
                            )
                        }
                    }
                }
                val result = withContext(Dispatchers.IO) {
                    plugin.get(scope, packageName, requestedVersion)
                }
                if (result == null) {
                    app.toast(app.getString(R.string.downloader_app_not_found))
                    return@launch
                }
                val (data, reportedVersion) = result
                val metadata = resolveBatchDownloaderMetadata(
                    packageVersion = (data as? DownloaderPackage)?.version,
                    downloadUrl = (data as? DownloadUrl)?.url,
                    reportedVersion = reportedVersion,
                    requestedVersion = requestedVersion
                )
                val resolved = coordinator.attachSource(
                    key,
                    SelectedApp.Download(
                        packageName = packageName,
                        version = metadata.versionName,
                        data = ParceledDownloaderData(plugin, data),
                        versionCode = metadata.versionCode
                    )
                )
                resolved?.let { syncManualQueueEntry(it) }
                deleteOwnedInput(previousInput)
            } catch (error: UserInteractionException.Activity) {
                app.toast(error.message!!)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                app.toast(app.getString(R.string.downloader_error, error.simpleMessage()))
                Log.e(TAG, "Downloader.get threw an exception", error)
            } finally {
                if (isCurrentBatchPluginAction(pluginAction?.token, actionToken)) {
                    pluginAction = null
                    attachTarget = null
                }
            }
        }
        pluginAction = ActiveBatchPluginAction(plugin, actionToken, actionJob)
        actionJob.start()
    }

    fun handlePluginActivityResult(result: ActivityResult) {
        val (requestId, activityResult) =
            BatchActivityProxyActivity.decodeResult(result) ?: return
        pendingActivityRequests.complete(requestId, activityResult)
    }

    fun handleActivityLaunchFailure(intent: Intent, error: Throwable) {
        val requestId = BatchActivityProxyActivity.requestId(intent) ?: return
        BatchActivityProxyActivity.failCaller(requestId, error)
        pendingActivityRequests.fail(requestId, error)
    }

    fun confirmRootDowngrade() {
        val request = rootDowngradeRequest ?: return
        rootDowngradeRequest = null
        request.completion.complete(true)
    }

    fun dismissRootDowngrade() {
        val request = rootDowngradeRequest ?: return
        rootDowngradeRequest = null
        request.completion.complete(false)
    }

    fun confirmFallbackInstall() {
        val request = fallbackInstallRequest ?: return
        fallbackInstallRequest = null
        request.completion.complete(true)
    }

    fun dismissFallbackInstall() {
        val request = fallbackInstallRequest ?: return
        fallbackInstallRequest = null
        request.completion.complete(false)
    }

    // Item actions take the queue item's key, which tells apart entries for the same app.
    fun toggleExcluded(key: String) = coordinator.toggleExcluded(key)
    fun forceVersion(key: String) = coordinator.forceVersion(key)
    fun setPolicy(policy: BatchInstallPolicy) = coordinator.setPolicy(policy)
    fun reorder(keys: List<String>) {
        coordinator.reorder(keys)
        if (manualPlan) manualQueue.reorder(keys)
    }
    fun start() = coordinator.start()
    fun install(key: String) = coordinator.install(key)
    fun installWithToken(key: String, token: InstallerManager.Token) =
        coordinator.install(key, token)
    fun installAll() = coordinator.installAll()
    suspend fun supportsRootMount(key: String): Boolean =
        coordinator.supportsRootMountModeOverride(key)
    fun supportsRootMountPackage(key: String): Boolean {
        val item = state.value?.items?.firstOrNull { it.key == key }
            ?: return false
        return item.patchedFile
            ?.takeIf(File::isFile)
            ?.let(pm::getPackageInfo)
            ?.packageName == item.packageName
    }
    fun open(key: String) {
        state.value?.items
            ?.firstOrNull { it.key == key }
            ?.installedPackageName
            ?.let(pm::launch)
    }

    fun savePatchedAppForLater(
        key: String,
        onResult: (Boolean) -> Unit = {}
    ) = viewModelScope.launch {
        val success = coordinator.saveForLater(key)
        app.toast(
            app.getString(
                if (success) R.string.patched_app_saved_toast
                else R.string.patched_app_save_failed_toast
            )
        )
        onResult(success)
    }

    fun saveAllPatchedAppsForLater(
        onResult: (Boolean) -> Unit = {}
    ) = viewModelScope.launch {
        val success = coordinator.saveAllForLater()
        app.toast(
            app.getString(
                if (success) R.string.patched_app_saved_toast
                else R.string.patched_app_save_failed_toast
            )
        )
        onResult(success)
    }

    fun exportPatchedAppToUri(
        key: String,
        target: Uri?,
        onResult: (Boolean) -> Unit = {}
    ) = viewModelScope.launch {
        if (target == null) {
            onResult(false)
            return@launch
        }
        val source = patchedFile(key)
        val success = source != null && runCatching {
            withContext(Dispatchers.IO) {
                app.contentResolver.openOutputStream(target)
                    ?.use { output -> source.inputStream().use { it.copyTo(output) } }
                    ?: throw IOException("Could not open output stream for export")
            }
        }.isSuccess
        app.toast(
            app.getString(
                if (success) R.string.saved_app_export_success
                else R.string.saved_app_export_failed
            )
        )
        onResult(success)
    }

    fun exportPatchedAppToPath(
        key: String,
        target: Path,
        onResult: (Boolean) -> Unit = {}
    ) = viewModelScope.launch {
        val source = patchedFile(key)
        val success = source != null && runCatching {
            withContext(Dispatchers.IO) {
                target.parent?.let { Files.createDirectories(it) }
                Files.copy(
                    source.toPath(),
                    target,
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        }.isSuccess
        app.toast(
            app.getString(
                if (success) R.string.saved_app_export_success
                else R.string.saved_app_export_failed
            )
        )
        onResult(success)
    }

    fun getLogContent(context: Context, key: String): String {
        val item = state.value?.items?.firstOrNull { it.key == key }
            ?: return context.getString(R.string.batch_patch_details_unavailable)
        val selectedPatches = item.selection.values.flatten().sorted()
        val logMessages = item.logLines.map { line -> line.substringAfter("]: ", line) }
        fun findLogValue(prefix: String): String? =
            logMessages.lastOrNull { it.startsWith(prefix) }
                ?.removePrefix(prefix)
                ?.trim()
        val appPackage = item.patcherSessionInfo.appPackageName
            ?: findLogValue("App package:")
            ?: item.packageName
        val appVersion = item.patcherSessionInfo.appVersionName
            ?: findLogValue("App version:")
            ?: item.version
            ?: "unspecified"
        val loggedAppVersionCode = findLogValue("App version code:")
        val appVersionCode = when {
            item.patcherSessionInfo.appVersionCodeReported == true ->
                item.patcherSessionInfo.appVersionCode?.toString() ?: "unspecified"
            loggedAppVersionCode != null -> loggedAppVersionCode
            else -> item.versionCode?.toString()
                ?: item.input?.versionCode?.toString()
                ?: "unspecified"
        }
        val includedSplits = item.patcherSessionInfo.includedSplits
            ?: findLogValue("Included splits:")
        val excludedSplits = item.patcherSessionInfo.excludedSplits
            ?: findLogValue("Excluded splits:")
        val patchCount = item.patcherSessionInfo.patchCount ?: selectedPatches.size
        val patcherLogLines = item.logLines.filterNot { line ->
            val message = line.substringAfter("]: ", line)
            message.startsWith("App package:") ||
                message.startsWith("App version:") ||
                message.startsWith("App version code:") ||
                message.startsWith("Included splits:") ||
                message.startsWith("Excluded splits:") ||
                isVerbosePatcherExportLog(line)
        }
        return buildString {
            appendLine("------------")
            appendLine("Information:")
            appendLine("------------")
            appendLine("UPM version: ${BuildConfig.VERSION_NAME}")
            appendLine("Device architecture: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine("Device model: ${Build.MODEL}")
            appendLine("Android version: ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})")
            appendLine("Batch patch: yes")
            appendLine("App package: $appPackage")
            appendLine("App version: $appVersion")
            appendLine("App version code: $appVersionCode")
            includedSplits?.let { appendLine("Included splits: $it") }
            excludedSplits?.let { appendLine("Excluded splits: $it") }
            appendLine("Patches: $patchCount")
            appendLine("Selected patches:")
            if (selectedPatches.isEmpty()) appendLine("None")
            else selectedPatches.forEach { appendLine(it) }
            appendLine()
            appendLine("------------")
            appendLine("Patcher Log:")
            appendLine("------------")
            if (patcherLogLines.isEmpty()) appendLine("No log messages recorded.")
            else patcherLogLines.forEach { appendLine(it) }
        }
    }

    fun exportLogsToUri(
        context: Context,
        key: String,
        target: Uri?,
        onResult: (Boolean) -> Unit = {}
    ) = viewModelScope.launch {
        if (target == null) {
            onResult(false)
            return@launch
        }
        val content = getLogContent(context, key)
        val success = runCatching {
            withContext(Dispatchers.IO) {
                app.contentResolver.openOutputStream(target, "wt")
                    ?.bufferedWriter(StandardCharsets.UTF_8)
                    ?.use { it.write(content) }
                    ?: throw IOException("Could not open output stream for log export")
            }
        }.isSuccess
        app.toast(
            app.getString(
                if (success) R.string.patcher_log_export_success
                else R.string.patcher_log_export_failed
            )
        )
        onResult(success)
    }

    fun exportLogsToPath(
        context: Context,
        key: String,
        target: Path,
        onResult: (Boolean) -> Unit = {}
    ) = viewModelScope.launch {
        val content = getLogContent(context, key)
        val success = runCatching {
            withContext(Dispatchers.IO) {
                target.parent?.let { Files.createDirectories(it) }
                Files.newBufferedWriter(
                    target,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
                ).use { it.write(content) }
            }
        }.isSuccess
        app.toast(
            app.getString(
                if (success) R.string.patcher_log_export_success
                else R.string.patcher_log_export_failed
            )
        )
        onResult(success)
    }

    private fun patchedFile(key: String): File? =
        state.value?.items
            ?.firstOrNull { it.key == key }
            ?.patchedFile
            ?.takeIf(File::isFile)

    fun updateConfiguration(
        key: String,
        patches: PatchSelection?,
        options: Options
    ) {
        val selection = patches.orEmpty()
        coordinator.updateConfiguration(key, selection, options)
        if (manualPlan) {
            manualQueue.updateConfiguration(key, selection, options)
        }
    }

    fun retryFailed() {
        coordinator.retryFailed(
            manualEntries = manualQueue.snapshot().takeIf { manualPlan }
        )
    }

    fun cancelInstall() = viewModelScope.launch {
        coordinator.cancelInstall()
    }

    fun cancel() = viewModelScope.launch {
        coordinator.cancel()
    }

    fun cancelAndLeave(onCancelled: () -> Unit) {
        viewModelScope.launch {
            coordinator.cancel()
            coordinator.clear()
            resetPlanState()
            cleanupOwnedWorkspaceFiles()
            onCancelled()
        }
    }

    fun leave(onLeave: () -> Unit) {
        cancelPluginAction()
        attachTarget = null
        coordinator.clear()
        resetPlanState()
        cleanupOwnedWorkspaceFiles()
        onLeave()
    }

    fun clear() {
        coordinator.clear()
        resetPlanState()
        cleanupOwnedWorkspaceFiles()
    }

    private fun resetPlanState() {
        initialized = false
        manualPlan = false
        activeRequestKey = null
    }

    private fun copyToWorkspace(uri: Uri): File? {
        var target: File? = null
        return runCatching {
            val displayName = app.contentResolver.query(uri, null, null, null, null)
                ?.use { cursor ->
                    val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && column >= 0) cursor.getString(column)
                    else null
                }
            val extension = displayName?.substringAfterLast('.', "apk") ?: "apk"
            target = fs.uiTempDir.resolve(
                "batch_input_${UUID.randomUUID()}.$extension"
            )
            val copied = app.contentResolver.openInputStream(uri)?.use { input ->
                target!!.outputStream().use(input::copyTo)
            } ?: 0L
            target!!.takeIf { copied > 0L }?.also(::ownWorkspaceFile)
        }.getOrNull().also { result ->
            if (result == null) target?.let { runCatching(it::delete) }
        }
    }

    private fun copyToWorkspace(file: File): File? {
        if (!file.isFile) return null
        var target: File? = null
        return runCatching {
            val extension = file.extension.ifBlank { "apk" }
            target = fs.uiTempDir.resolve(
                "batch_input_${UUID.randomUUID()}.$extension"
            )
            file.copyTo(target!!, overwrite = true)
            target!!.takeIf { it.length() > 0L }?.also(::ownWorkspaceFile)
        }.getOrNull().also { result ->
            if (result == null) target?.let { runCatching(it::delete) }
        }
    }

    private suspend fun syncManualQueueEntry(
        item: BatchPatchItem,
        ownedFile: File? = null
    ) {
        if (!manualPlan) return
        val input = item.input ?: return
        val queueInput = if (
            input is SelectedApp.Local &&
            ownedFile != null &&
            canonicalPath(input.file) == canonicalPath(ownedFile)
        ) {
            input.copy(temporary = true)
        } else {
            input
        }
        // Replace this item's own entry, keeping its signature workflow and saved-app source.
        manualQueue.upsert(
            input = queueInput,
            selection = item.selection,
            options = item.options,
            useMount = item.useMount,
            signatureWorkflow = item.signatureWorkflow,
            sourceEntryKey = item.sourceEntryKey,
            key = item.key,
        )
    }

    private fun ownWorkspaceFile(file: File) {
        synchronized(ownedWorkspacePaths) {
            ownedWorkspacePaths += canonicalPath(file)
        }
    }

    private fun deleteOwnedInput(input: SelectedApp?, except: File? = null) {
        val file = (input as? SelectedApp.Local)?.file ?: return
        val path = canonicalPath(file)
        val exceptPath = except?.let(::canonicalPath)
        if (path == exceptPath) return
        val owned = synchronized(ownedWorkspacePaths) {
            ownedWorkspacePaths.remove(path)
        }
        if (owned) runCatching { file.delete() }
    }

    private fun cleanupOwnedWorkspaceFiles() {
        val paths = synchronized(ownedWorkspacePaths) {
            ownedWorkspacePaths.toList().also { ownedWorkspacePaths.clear() }
        }
        paths.forEach { path -> runCatching { File(path).delete() } }
    }

    private fun canonicalPath(file: File): String =
        runCatching(file::getCanonicalPath).getOrElse { file.absolutePath }

    override fun onCleared() {
        cancelPluginAction()
        rootDowngradeRequest?.completion?.complete(false)
        rootDowngradeRequest = null
        fallbackInstallRequest?.completion?.complete(false)
        fallbackInstallRequest = null
        pendingActivityRequests.cancelAll(
            ActivityResult(Activity.RESULT_CANCELED, null)
        )
        cleanupScope.launch {
            try {
                coordinator.shutdown()
            } finally {
                cleanupOwnedWorkspaceFiles()
            }
        }
        super.onCleared()
    }

    private companion object {
        const val TAG = "BatchPatcherViewModel"
        const val ACTIVITY_REQUEST_TIMEOUT_MS = 5L * 60L * 1_000L
    }
}
