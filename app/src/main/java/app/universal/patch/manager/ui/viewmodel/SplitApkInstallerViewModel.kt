package app.universal.patch.manager.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.universal.patch.manager.domain.storage.CacheCleanupGuard
import app.universal.patch.manager.domain.installer.InstallerManager
import app.universal.patch.manager.domain.installer.RootInstaller
import app.universal.patch.manager.domain.installer.ShizukuInstaller
import app.universal.patch.manager.patcher.split.SplitApkPreparer
import app.universal.patch.manager.util.InstalledPackageSnapshot
import app.universal.patch.manager.util.PM
import app.universal.patch.manager.util.installedPackageSnapshot
import app.universal.patch.manager.util.toast
import app.universal.patch.manager.R
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class SplitApkInstallerViewModel(
    private val app: Application,
    private val rootInstaller: RootInstaller,
    private val shizukuInstaller: ShizukuInstaller,
    private val pm: PM
) : ViewModel() {
    private val stateFlow = MutableStateFlow(SplitApkInstallerState())
    val state = stateFlow.asStateFlow()
    private val cleanupLock = Any()
    private val cancelCleanupActions = mutableListOf<() -> Unit>()
    private var availabilityRefreshJob: Job? = null
    private var installJob: Job? = null

    init {
        refreshAvailability()
    }

    fun refreshAvailability(userInitiated: Boolean = false) {
        availabilityRefreshJob?.cancel()
        availabilityRefreshJob = viewModelScope.launch {
            val startedAt = if (userInitiated) SystemClock.elapsedRealtime() else 0L
            stateFlow.update { it.copy(checkingAvailability = true) }
            val shizukuAvailable = runCatching {
                withContext(Dispatchers.IO) {
                    shizukuInstaller.availability(InstallerManager.InstallTarget.PATCHER).available
                }
            }.getOrDefault(false)
            val rootAvailable = runCatching {
                withContext(Dispatchers.IO) {
                    rootInstaller.hasRootAccess()
                }
            }.getOrDefault(false)

            if (userInitiated) {
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                val remaining = MIN_MANUAL_REFRESH_SHIMMER_MS - elapsed
                if (remaining > 0) {
                    delay(remaining)
                }
            }

            stateFlow.update {
                it.copy(
                    checkingAvailability = false,
                    shizukuAvailable = shizukuAvailable,
                    rootAvailable = rootAvailable
                )
            }
        }
    }

    fun installFromPath(path: String, mode: SplitInstallMode) {
        startInstall(mode, inputDisplayName = File(path).name, deleteInputAfterUse = false) {
            File(path)
        }
    }

    fun installFromUri(
        uri: Uri,
        inputDisplayName: String?,
        mode: SplitInstallMode
    ) {
        startInstall(mode, inputDisplayName = inputDisplayName, deleteInputAfterUse = true) {
            copyUriToTempFile(uri, inputDisplayName)
        }
    }

    private fun startInstall(
        mode: SplitInstallMode,
        inputDisplayName: String?,
        deleteInputAfterUse: Boolean,
        resolveInput: suspend () -> File
    ) {
        if (installJob?.isActive == true || stateFlow.value.inProgress) return

        clearCancelCleanupActions()
        val job = viewModelScope.launch {
            val cacheUseToken = CacheCleanupGuard.begin()
            startLogSession()
            appendLog("Started split install (${mode.name.lowercase(Locale.ROOT)})")
            inputDisplayName?.takeIf { it.isNotBlank() }?.let { appendLog("Input: $it") }
            val preparingMessage = app.getString(R.string.split_installer_preparing)
            stateFlow.update {
                it.copy(
                    inProgress = true,
                    activeMode = mode,
                    inputName = inputDisplayName,
                    installedPackageName = null,
                    statusMessage = preparingMessage,
                    errorMessage = null,
                    successMessage = null
                )
            }
            appendLog(preparingMessage)

            val workspace = File(app.cacheDir, "split-installer-${System.currentTimeMillis()}").apply { mkdirs() }
            appendLog("Workspace: ${workspace.absolutePath}")
            registerCancelCleanupAction { runCatching { workspace.deleteRecursively() } }
            var inputFile: File? = null
            var completedSuccessfully = false

            try {
                inputFile = resolveInput()
                appendLog("Resolved input file: ${inputFile.absolutePath} (${inputFile.length()} bytes)")
                if (deleteInputAfterUse) {
                    registerCancelCleanupAction { runCatching { inputFile?.delete() } }
                }
                val apkFiles = withContext(Dispatchers.IO) {
                    prepareSplitApkFiles(inputFile, workspace)
                }
                val expectedPackageName = resolveExpectedPackageName(apkFiles)
                val openablePackageName = expectedPackageName
                    ?: inferPackageNameFromInputName(inputDisplayName)
                appendLog(
                    if (expectedPackageName.isNullOrBlank()) {
                        "Could not resolve package name from split APK entries"
                    } else {
                        "Resolved package name: $expectedPackageName"
                    }
                )
                if (expectedPackageName == null && openablePackageName != null) {
                    appendLog("Using inferred package name from input: $openablePackageName")
                }

                when (mode) {
                    SplitInstallMode.NORMAL -> {
                        val status = app.getString(R.string.split_installer_installing_normal)
                        stateFlow.update {
                            it.copy(statusMessage = status)
                        }
                        appendLog(status)
                        installWithPackageInstaller(apkFiles, expectedPackageName)
                    }

                    SplitInstallMode.PRIVILEGED -> {
                        installWithPrivilegedBackend(apkFiles, expectedPackageName)
                    }
                }

                stateFlow.update {
                    it.copy(
                        inProgress = false,
                        activeMode = null,
                        installedPackageName = openablePackageName,
                        statusMessage = null,
                        errorMessage = null,
                        successMessage = app.getString(R.string.split_installer_success)
                    )
                }
                appendLog(app.getString(R.string.split_installer_success))
                app.toast(app.getString(R.string.split_installer_success_toast))
                completedSuccessfully = true
            } catch (_: CancellationException) {
                val cancelledMessage = app.getString(R.string.split_installer_cancelled)
                stateFlow.update {
                    it.copy(
                        inProgress = false,
                        activeMode = null,
                        installedPackageName = null,
                        statusMessage = null,
                        successMessage = null,
                        errorMessage = cancelledMessage
                    )
                }
                appendLog(cancelledMessage)
            } catch (error: Throwable) {
                val resolvedMessage = error.message?.takeIf { it.isNotBlank() }
                    ?: app.getString(R.string.split_installer_failed)
                stateFlow.update {
                    it.copy(
                        inProgress = false,
                        activeMode = null,
                        installedPackageName = null,
                        statusMessage = null,
                        successMessage = null,
                        errorMessage = resolvedMessage
                    )
                }
                appendLog("Error: $resolvedMessage")
                appendLog(error.stackTraceToString())
                app.toast(app.getString(R.string.split_installer_error_toast))
            } finally {
                if (!completedSuccessfully) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        runCancelCleanupActions()
                    }
                } else {
                    clearCancelCleanupActions()
                }
                if (deleteInputAfterUse) {
                    inputFile?.let { runCatching { it.delete() } }
                }
                runCatching { workspace.deleteRecursively() }
                refreshAvailability()
                runCatching { cacheUseToken.close() }
                stateFlow.update { it.copy(logComplete = true) }
            }
        }
        installJob = job
        job.invokeOnCompletion {
            if (installJob === job) {
                installJob = null
            }
        }
    }

    fun cancelInstall() {
        val activeJob = installJob ?: return
        if (!activeJob.isActive) return
        appendLog("Cancellation requested by user")
        stateFlow.update {
            it.copy(
                inProgress = false,
                activeMode = null,
                statusMessage = null
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCancelCleanupActions()
        }
        activeJob.cancel(CancellationException("Split install cancelled by user"))
    }

    private suspend fun installWithPrivilegedBackend(
        apkFiles: List<File>,
        expectedPackage: String?
    ) {
        var shizukuFailure: Throwable? = null
        val shizukuAvailable = withContext(Dispatchers.IO) {
            shizukuInstaller.availability(InstallerManager.InstallTarget.PATCHER).available
        }
        if (shizukuAvailable) {
            val status = app.getString(R.string.split_installer_installing_privileged_shizuku)
            stateFlow.update {
                it.copy(statusMessage = status)
            }
            appendLog(status)
            appendLog(
                if (expectedPackage.isNullOrBlank()) {
                    "No expected package resolved from split APKs"
                } else {
                    "Expected package: $expectedPackage"
                }
            )
            runCatching {
                val result = shizukuInstaller.installMultiple(apkFiles, expectedPackage)
                appendLog("Shizuku installer returned status=${result.status}, message=${result.message ?: "n/a"}")
                if (result.status != PackageInstaller.STATUS_SUCCESS) {
                    throw IOException(result.message ?: app.getString(R.string.split_installer_failed))
                }
            }.onSuccess {
                return
            }.onFailure { error ->
                shizukuFailure = error
                appendLog(
                    "Shizuku install failed; attempting root fallback: " +
                        (error.message ?: error::class.java.simpleName)
                )
            }
        }

        val status = app.getString(R.string.split_installer_installing_privileged_root)
        stateFlow.update {
            it.copy(statusMessage = status)
        }
        appendLog(status)
        try {
            installWithRootPackageManager(apkFiles)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            val message = error.message ?: ""
            if (message.contains(app.getString(R.string.split_installer_no_privileged_access))) {
                throw IllegalStateException(
                    buildString {
                        append(app.getString(R.string.split_installer_no_privileged_access))
                        shizukuFailure?.message?.takeIf { it.isNotBlank() }?.let { failureMessage ->
                            append(" (")
                            append(failureMessage)
                            append(")")
                        }
                    }
                )
            }
            throw error
        }
    }

    private suspend fun installWithPackageInstaller(
        apkFiles: List<File>,
        expectedPackage: String?
    ) = withContext(Dispatchers.IO) {
        val beforeInstall = expectedPackage?.let { packageName ->
            pm.installedPackageSnapshot(packageName, includeHashes = false)
        }
        val packageInstaller = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setRequestUpdateOwnership(true)
            }
        }

        val sessionId = packageInstaller.createSession(params)
        registerCancelCleanupAction { runCatching { packageInstaller.abandonSession(sessionId) } }
        val session = packageInstaller.openSession(sessionId)
        appendLog("Created Package Installer session $sessionId")
        var completed = false
        var callbackRegistered = false
        var callback: PackageInstaller.SessionCallback? = null

        try {
            for ((index, file) in apkFiles.withIndex()) {
                coroutineContext.ensureActive()
                file.inputStream().use { input ->
                    session.openWrite("$index.apk", 0, file.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
            }

            val resultDeferred = CompletableDeferred<InstallOutcome>()
            var pendingUserConfirmation = false
            var installProcessingLogged = false
            var lastLoggedProgressBucket = -1
            val completionCallback = object : PackageInstaller.SessionCallback() {
                override fun onCreated(sessionId: Int) = Unit
                override fun onBadgingChanged(sessionId: Int) = Unit
                override fun onActiveChanged(changedSessionId: Int, active: Boolean) {
                    if (changedSessionId != sessionId) return
                    if (active && pendingUserConfirmation && !installProcessingLogged) {
                        installProcessingLogged = true
                        val status = app.getString(R.string.split_installer_installing_normal)
                        stateFlow.update { it.copy(statusMessage = status) }
                        appendLog("User confirmation accepted; Package Installer is now processing installation")
                    }
                }
                override fun onProgressChanged(changedSessionId: Int, progress: Float) {
                    if (changedSessionId != sessionId) return
                    val clampedProgress = progress.coerceIn(0f, 1f)
                    val progressBucket = (clampedProgress * 100).toInt() / 10
                    if (progressBucket <= lastLoggedProgressBucket) return
                    lastLoggedProgressBucket = progressBucket
                    appendLog("Package Installer session progress: ${(clampedProgress * 100).toInt()}%")
                    if (pendingUserConfirmation && clampedProgress > 0f && !installProcessingLogged) {
                        installProcessingLogged = true
                        val status = app.getString(R.string.split_installer_installing_normal)
                        stateFlow.update { it.copy(statusMessage = status) }
                        appendLog("Installation has started after confirmation")
                    }
                }

                override fun onFinished(finishedSessionId: Int, success: Boolean) {
                    if (finishedSessionId != sessionId || resultDeferred.isCompleted) return
                    appendLog("Package Installer session $finishedSessionId finished (success=$success)")
                    resultDeferred.complete(
                        InstallOutcome(
                            status = if (success) {
                                PackageInstaller.STATUS_SUCCESS
                            } else {
                                PackageInstaller.STATUS_FAILURE
                            },
                            message = if (success) null else app.getString(R.string.split_installer_failed)
                        )
                    )
                }
            }
            callback = completionCallback
            packageInstaller.registerSessionCallback(
                completionCallback,
                Handler(Looper.getMainLooper())
            )
            callbackRegistered = true
            val intentSender = IntentSenderCompat.create { resultIntent ->
                val status = resultIntent.getIntExtra(
                    PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE
                )
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    val confirmationIntent = resultIntent.readConfirmationIntent()
                    if (confirmationIntent != null) {
                        pendingUserConfirmation = true
                        appendLog("Waiting for user confirmation in Package Installer")
                        confirmationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        viewModelScope.launch(Dispatchers.Main.immediate) {
                            runCatching { app.startActivity(confirmationIntent) }
                                .onFailure { launchError ->
                                    appendLog(
                                        "Failed to launch Package Installer confirmation UI: " +
                                            (launchError.message ?: launchError::class.java.simpleName)
                                    )
                                    if (!resultDeferred.isCompleted) {
                                        resultDeferred.complete(
                                            InstallOutcome(
                                                status = PackageInstaller.STATUS_FAILURE,
                                                message = launchError.message
                                                    ?: app.getString(R.string.split_installer_failed)
                                            )
                                        )
                                    }
                                }
                        }
                        val waitingMessage = app.getString(R.string.split_installer_pending_user_action)
                        stateFlow.update {
                            it.copy(statusMessage = waitingMessage)
                        }
                        appendLog(waitingMessage)
                    } else if (!resultDeferred.isCompleted) {
                        appendLog("Package Installer returned pending user action without confirmation intent")
                        resultDeferred.complete(
                            InstallOutcome(
                                status = PackageInstaller.STATUS_FAILURE,
                                message = app.getString(R.string.split_installer_failed)
                            )
                        )
                    }
                    return@create
                }

                if (!resultDeferred.isCompleted) {
                    val statusMessage = resultIntent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    appendLog(
                        buildString {
                            append("Package Installer result status=$status")
                            if (!statusMessage.isNullOrBlank()) {
                                append(", message=")
                                append(statusMessage)
                            }
                        }
                    )
                    resultDeferred.complete(
                        InstallOutcome(
                            status = status,
                            message = statusMessage
                        )
                    )
                }
            }

            appendLog("Committing Package Installer session $sessionId")
            session.commit(intentSender)

            val outcome = withTimeout(INSTALL_TIMEOUT_MS) {
                resultDeferred.await()
            }

            val installedDespiteFailure =
                outcome.status != PackageInstaller.STATUS_SUCCESS &&
                    expectedPackage != null &&
                    confirmInstallCompleted(apkFiles, expectedPackage, beforeInstall)
            if (outcome.status != PackageInstaller.STATUS_SUCCESS && !installedDespiteFailure) {
                throw IOException(outcome.message ?: app.getString(R.string.split_installer_failed))
            }
            if (installedDespiteFailure) {
                // Code adapted from Morphe, see third-party/NOTICE for more information
                // https://github.com/MorpheApp/morphe-manager/pull/598
                appendLog(
                    "Package Installer reported failure but APK verification succeeded for " +
                        expectedPackage
                )
            }
            completed = true
        } finally {
            if (callbackRegistered) {
                callback?.let { registeredCallback ->
                    runCatching { packageInstaller.unregisterSessionCallback(registeredCallback) }
                }
            }
            runCatching { session.close() }
            if (!completed) {
                runCatching { packageInstaller.abandonSession(sessionId) }
            }
        }
    }

    // Code adapted from Morphe, see third-party/NOTICE for more information
    // https://github.com/MorpheApp/morphe-manager/pull/598
    private fun confirmInstallCompleted(
        apkFiles: List<File>,
        expectedPackage: String,
        beforeInstall: InstalledPackageSnapshot?
    ): Boolean {
        val afterInstall = pm.installedPackageSnapshot(expectedPackage) ?: return false
        return afterInstall.changedSince(beforeInstall) && afterInstall.matches(apkFiles)
    }

    private suspend fun installWithRootPackageManager(apkFiles: List<File>) {
        rootInstaller.installPackageFiles(
            apkFiles = apkFiles,
            onLog = ::appendLog,
            registerCancelCleanup = ::registerCancelCleanupAction
        )
    }

    private suspend fun prepareSplitApkFiles(
        source: File,
        workspace: File
    ): List<File> = withContext(Dispatchers.IO) {
        if (!source.exists()) {
            throw FileNotFoundException(app.getString(R.string.merge_split_apk_input_missing))
        }
        if (!SplitApkPreparer.isSplitArchive(source)) {
            throw IOException(app.getString(R.string.merge_split_apk_input_invalid))
        }
        appendLog("Preparing split archive: ${source.absolutePath}")

        stateFlow.update {
            it.copy(statusMessage = app.getString(R.string.split_installer_extracting))
        }
        appendLog(app.getString(R.string.split_installer_extracting))

        val extractedDir = workspace.resolve("apks").apply { mkdirs() }
        val extractedApks = extractSplitEntries(source, extractedDir)
        if (extractedApks.isEmpty()) {
            throw IOException(app.getString(R.string.split_installer_no_apk_entries))
        }
        appendLog("Extracted ${extractedApks.size} APK entries from archive")

        extractedApks.sortedWith(
            compareBy<ExtractedSplitApk> { !it.originalName.equals("base.apk", ignoreCase = true) }
                .thenBy { it.originalName.lowercase(Locale.ROOT) }
        ).map { split ->
            appendLog("Prepared split entry: ${split.originalName} -> ${split.file.name} (${split.file.length()} bytes)")
            split.file
        }
    }

    private fun extractSplitEntries(source: File, outputDir: File): List<ExtractedSplitApk> {
        val results = mutableListOf<ExtractedSplitApk>()
        val selectedEntryNames = SplitApkPreparer.splitApkEntryNames(source)
        ZipFile(source).use { zip ->
            val entries = zip.entries()
            var index = 0
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || entry.name !in selectedEntryNames) continue

                val rawName = entry.name.substringAfterLast('/').ifBlank { "split-$index.apk" }
                val fileName = sanitizeFileName(rawName).ifBlank { "split-$index.apk" }
                val destination = outputDir.resolve("${index.toString().padStart(3, '0')}-$fileName")
                zip.getInputStream(entry).use { input ->
                    destination.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                results += ExtractedSplitApk(
                    originalName = rawName,
                    file = destination
                )
                index += 1
            }
        }
        return results
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private suspend fun copyUriToTempFile(uri: Uri, displayName: String?): File = withContext(Dispatchers.IO) {
        val fileName = displayName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.takeIf { it.isNotBlank() }
            ?: "split-installer-input.apks"
        val sanitizedName = sanitizeFileName(fileName)
        val destination = File(app.cacheDir, "split-installer-input-$sanitizedName")
        app.contentResolver.openInputStream(uri)?.use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: throw FileNotFoundException("Unable to open selected file")
        destination
    }

    @Suppress("DEPRECATION")
    private fun readPackageArchiveInfo(path: String) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.packageManager.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(0L))
        } else {
            app.packageManager.getPackageArchiveInfo(path, 0)
        }

    private fun resolveExpectedPackageName(apkFiles: List<File>): String? =
        apkFiles.asSequence()
            .mapNotNull { file -> readPackageArchiveInfo(file.absolutePath)?.packageName }
            .firstOrNull()

    private fun inferPackageNameFromInputName(inputName: String?): String? {
        val raw = inputName?.trim().orEmpty()
        if (raw.isBlank()) return null
        val withoutPath = raw.substringAfterLast('/').substringAfterLast('\\')
        val candidate = withoutPath
            .substringBefore('_')
            .substringBefore(".apkm", missingDelimiterValue = withoutPath)
            .substringBefore(".xapk", missingDelimiterValue = withoutPath)
            .substringBefore(".apks", missingDelimiterValue = withoutPath)
            .substringBefore(".zip", missingDelimiterValue = withoutPath)
            .trim()
        return candidate.takeIf { PACKAGE_NAME_PATTERN.matches(it) }
    }

    private fun registerCancelCleanupAction(action: () -> Unit) {
        synchronized(cleanupLock) {
            cancelCleanupActions += action
        }
    }

    private fun runCancelCleanupActions() {
        val actions = synchronized(cleanupLock) {
            val snapshot = cancelCleanupActions.toList()
            cancelCleanupActions.clear()
            snapshot
        }
        if (actions.isNotEmpty()) {
            appendLog("Running ${actions.size} cleanup action(s)")
        }
        actions.forEach { action ->
            runCatching { action() }
        }
    }

    private fun clearCancelCleanupActions() {
        synchronized(cleanupLock) {
            cancelCleanupActions.clear()
        }
    }

    private fun appendLog(message: String) {
        val trimmed = message.trim()
        if (trimmed.isEmpty()) return
        val timestamp = "%1\$tH:%1\$tM:%1\$tS".format(Date())
        stateFlow.update { current ->
            current.copy(
                logEntries = current.logEntries + "[$timestamp] $trimmed"
            )
        }
    }

    private fun startLogSession() {
        stateFlow.update { current ->
            current.copy(
                inProgress = true,
                installedPackageName = null,
                errorMessage = null,
                successMessage = null,
                logEntries = emptyList(),
                logComplete = false,
                logSessionId = current.logSessionId + 1
            )
        }
    }

    private fun buildLogContent(context: Context): String = buildString {
        appendLine(context.getString(R.string.tools_split_installer_title))
        appendLine("Generated at: ${Date()}")
        appendLine("Input: ${stateFlow.value.inputName ?: "n/a"}")
        appendLine()
        appendLine("------------")
        appendLine("Raw installer log:")
        appendLine("------------")
        if (stateFlow.value.logEntries.isEmpty()) {
            appendLine("No log messages recorded.")
        } else {
            stateFlow.value.logEntries.forEach { appendLine(it) }
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
            app.toast(app.getString(R.string.split_installer_log_export_failed))
            onResult(false)
            return@launch
        }

        app.toast(app.getString(R.string.split_installer_log_export_success))
        onResult(true)
    }

    fun openInstalledApp(): Boolean {
        val packageName = stateFlow.value.installedPackageName ?: return false
        val launchIntent = app.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            app.startActivity(launchIntent)
            appendLog("Opened installed app: $packageName")
            true
        }.getOrDefault(false)
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
                    ?: throw IOException("Could not open output stream for split installer log export")
            }
        }.isSuccess

        if (!exportSucceeded) {
            app.toast(app.getString(R.string.split_installer_log_export_failed))
            onResult(false)
            return@launch
        }

        app.toast(app.getString(R.string.split_installer_log_export_success))
        onResult(true)
    }

    companion object {
        private const val INSTALL_TIMEOUT_MS = 10 * 60 * 1000L
        private const val MIN_MANUAL_REFRESH_SHIMMER_MS = 3_000L
        private val PACKAGE_NAME_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    }
}

data class SplitApkInstallerState(
    val checkingAvailability: Boolean = true,
    val shizukuAvailable: Boolean = false,
    val rootAvailable: Boolean = false,
    val inProgress: Boolean = false,
    val activeMode: SplitInstallMode? = null,
    val inputName: String? = null,
    val installedPackageName: String? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val logEntries: List<String> = emptyList(),
    val logComplete: Boolean = false,
    val logSessionId: Long = 0L
)

enum class SplitInstallMode {
    NORMAL,
    PRIVILEGED
}

internal data class InstallOutcome(
    val status: Int,
    val message: String?
)

private data class ExtractedSplitApk(
    val originalName: String,
    val file: File
)

internal fun Intent.readConfirmationIntent(): Intent? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(Intent.EXTRA_INTENT)
    }
}

internal object IntentSenderCompat {
    fun create(callback: (Intent) -> Unit): IntentSender {
        val binder = object : android.content.IIntentSender.Stub() {
            override fun send(
                code: Int,
                intent: Intent?,
                resolvedType: String?,
                finishedReceiver: android.content.IIntentReceiver?,
                requiredPermission: String?,
                options: Bundle?
            ): Int {
                intent?.let(callback)
                return 0
            }

            override fun send(
                code: Int,
                intent: Intent?,
                resolvedType: String?,
                whitelistToken: IBinder?,
                finishedReceiver: android.content.IIntentReceiver?,
                requiredPermission: String?,
                options: Bundle?
            ) {
                intent?.let(callback)
            }
        }

        val constructor = IntentSender::class.java.getDeclaredConstructor(android.content.IIntentSender::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(binder)
    }
}
