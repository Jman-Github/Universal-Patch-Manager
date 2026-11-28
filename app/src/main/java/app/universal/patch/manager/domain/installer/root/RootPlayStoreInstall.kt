package app.universal.patch.manager.domain.installer.root

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.universal.patch.manager.domain.installer.InstallerActivityProxyActivity
import app.universal.patch.manager.domain.installer.RootInstaller
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal class RootMountSuspension(
    private val rootMountCoordinator: RootMountTransactionCoordinator,
    val packageName: String,
    val userId: Int,
    stateIdentity: RootMountStateIdentity,
    private var recoveryStore: RootExternalInstallRecoveryStore? = null,
    private var recoveryRequestId: String? = null
) {
    private val finalizationMutex = Mutex()
    private var stateIdentity: RootMountStateIdentity? = stateIdentity

    suspend fun restoreMount() = finalizationMutex.withLock {
        val expected = stateIdentity ?: return@withLock
        val execution = rootMountCoordinator.executeIfStateMatches(
            expected,
            RootMountRequest(
                packageName = packageName,
                userId = userId,
                operation = RootMountOperation.MOUNT_ONLY
            )
        )
        stateIdentity = if (execution.matched) execution.stateIdentity else null
        clearProcessDeathRecovery()
    }

    suspend fun retireMount() = finalizationMutex.withLock {
        val expected = stateIdentity ?: return@withLock
        rootMountCoordinator.executeIfStateMatches(
            expected,
            RootMountRequest(
                packageName = packageName,
                userId = userId,
                operation = RootMountOperation.UNMOUNT,
                removeModuleAfterUnmount = true
            )
        )
        stateIdentity = null
        clearProcessDeathRecovery()
    }

    fun enableProcessDeathRecovery(
        context: Context,
        baselineVersionCode: Long?,
        baselineLastUpdateTime: Long?,
        cleanupFile: File? = null,
        grantedUri: Uri? = null,
        restoreMountAfterPackageChange: Boolean = false
    ): String {
        val expected = stateIdentity ?: error("Root mount suspension is already finalized")
        val store = recoveryStore ?: RootExternalInstallRecoveryStore(context).also {
            recoveryStore = it
        }
        val requestId = recoveryRequestId ?: newRootExternalInstallRecoveryId().also {
            recoveryRequestId = it
        }
        store.write(
            PendingRootExternalInstall(
                requestId = requestId,
                transactionId = expected.transactionId,
                packageName = expected.packageName,
                userId = expected.userId,
                stockSha256 = expected.stockSha256,
                patchedSha256 = expected.patchedSha256,
                committedAtEpochMs = expected.committedAtEpochMs,
                baselineVersionCode = baselineVersionCode,
                baselineLastUpdateTime = baselineLastUpdateTime,
                cleanupFilePath = cleanupFile?.absolutePath,
                grantedUri = grantedUri?.toString(),
                restoreMountAfterPackageChange = restoreMountAfterPackageChange
            )
        )
        return requestId
    }

    fun processDeathRecoveryId(): String? = recoveryRequestId

    private fun clearProcessDeathRecovery() {
        val requestId = recoveryRequestId ?: return
        recoveryStore?.remove(requestId)
        recoveryRequestId = null
    }
}

internal suspend fun suspendRootMountForPackageInstall(
    rootInstaller: RootInstaller,
    rootMountCoordinator: RootMountTransactionCoordinator,
    packageName: String,
    userId: Int,
    recoveryContext: Context? = null
): RootMountSuspension? {
    if (!rootInstaller.hasRootAccess()) return null
    val shouldRestore = rootInstaller.isAppMounted(packageName) ||
        rootMountCoordinator.hasActiveMountState(packageName)
    if (!shouldRestore) return null

    currentCoroutineContext().ensureActive()
    // Keep the committed unmount result even if cancellation arrives while
    // the coordinator is returning from its I/O dispatcher.
    val suspension = withContext(NonCancellable) {
        val stateIdentity = rootMountCoordinator.suspendForExternalInstall(
            RootMountRequest(packageName, userId, RootMountOperation.UNMOUNT)
        )
        RootMountSuspension(rootMountCoordinator, packageName, userId, stateIdentity)
    }
    return try {
        if (recoveryContext != null) suspension.enableProcessDeathRecovery(recoveryContext)
        currentCoroutineContext().ensureActive()
        suspension
    } catch (error: Throwable) {
        val restoreError = withContext(NonCancellable) {
            runCatching { suspension.restore() }.exceptionOrNull()
        }
        restoreError?.let(error::addSuppressed)
        throw error
    }
}

internal suspend fun RootMountSuspension.restore() = restoreMount()

internal suspend fun RootMountSuspension.retire() = retireMount()

internal suspend fun launchExternalInstallerWithMountFinalization(
    context: Context,
    targetIntent: Intent,
    suspendedMount: RootMountSuspension,
    installChanged: suspend () -> Boolean,
    activityTimeoutMs: Long,
    onLaunched: () -> Unit = {},
    onRecoveryOwnershipTransferred: () -> Unit = {},
    cleanupFile: File? = null,
    cleanup: () -> Unit
): Boolean {
    var mountFinalized = false
    var finalizationStarted = false
    var installerLaunched = false
    var installerResultReceived = false
    var recoveryOwnsCleanup = false

    suspend fun finalizeMountAfterResult(): Boolean {
        finalizationStarted = true
        return withContext(NonCancellable) {
            val deadline = System.currentTimeMillis() + EXTERNAL_INSTALL_RESULT_GRACE_MS
            var installed: Boolean
            do {
                installed = withContext(Dispatchers.IO) { installChanged() }
                if (!installed) delay(EXTERNAL_INSTALL_POLL_INTERVAL_MS)
            } while (!installed && System.currentTimeMillis() < deadline)

            if (installed) suspendedMount.retire() else suspendedMount.restore()
            mountFinalized = true
            installed
        }
    }

    return try {
        val recoveryRequestId = suspendedMount.enableProcessDeathRecovery(
            context = context,
            cleanupFile = cleanupFile,
            grantedUri = targetIntent.data
        )
        withTimeout(activityTimeoutMs) {
            InstallerActivityProxyActivity.launch(
                context,
                Intent(targetIntent).apply {
                    putExtra(Intent.EXTRA_RETURN_RESULT, true)
                },
                onLaunched = {
                    installerLaunched = true
                    onLaunched()
                },
                onResultReceived = { installerResultReceived = true },
                recoveryRequestId = recoveryRequestId
            )
        }
        finalizeMountAfterResult()
    } catch (error: Throwable) {
        if (installerResultReceived && !finalizationStarted) {
            return finalizeMountAfterResult()
        }
        val orphanedInstallerWillRecover = installerLaunched && !installerResultReceived
        if (orphanedInstallerWillRecover) {
            recoveryOwnsCleanup = true
            onRecoveryOwnershipTransferred()
        }
        if (!mountFinalized && !orphanedInstallerWillRecover) {
            val restoreError = withContext(NonCancellable) {
                runCatching { suspendedMount.restore() }.exceptionOrNull()
            }
            restoreError?.let(error::addSuppressed)
        }
        throw error
    } finally {
        if (!recoveryOwnsCleanup) cleanup()
    }
}

internal suspend fun installAsPlayStoreWithMountRollback(
    rootInstaller: RootInstaller,
    rootMountCoordinator: RootMountTransactionCoordinator,
    apkFile: File,
    packageName: String,
    userId: Int
) {
    val suspendedMount = suspendRootMountForPackageInstall(
        rootInstaller = rootInstaller,
        rootMountCoordinator = rootMountCoordinator,
        packageName = packageName,
        userId = userId
    )

    try {
        rootInstaller.installAsPlayStore(apkFile, userId)
    } catch (error: Throwable) {
        if (suspendedMount != null) {
            val restoreError = withContext(NonCancellable) {
                runCatching { suspendedMount.restore() }.exceptionOrNull()
            }
            restoreError?.let(error::addSuppressed)
        }
        throw error
    }

    if (suspendedMount != null) {
        withContext(NonCancellable) { suspendedMount.retire() }
    }
}

internal suspend fun reinstallMountedStockAsPlayStore(
    context: Context,
    rootInstaller: RootInstaller,
    rootMountCoordinator: RootMountTransactionCoordinator,
    packageName: String,
    userId: Int
): Exception? {
    val suspendedMount = suspendRootMountForPackageInstall(
        rootInstaller = rootInstaller,
        rootMountCoordinator = rootMountCoordinator,
        packageName = packageName,
        userId = userId,
        recoveryContext = context
    ) ?: return IllegalStateException("Active root mount is unavailable")

    var stagedStockApk: File? = null
    var attributionFailure: Throwable? = null
    try {
        val staged = rootInstaller.stageInstalledBaseApk(packageName)
        stagedStockApk = staged
        suspendedMount.enableProcessDeathRecovery(
            context = context,
            cleanupFile = staged,
            restoreMountAfterPackageChange = true
        )
        rootInstaller.installAsPlayStore(staged, userId)
    } catch (error: Throwable) {
        attributionFailure = error
    }

    val restoreFailure = withContext(NonCancellable) {
        runCatching { suspendedMount.restore() }.exceptionOrNull()
    }
    runCatching { stagedStockApk?.delete() }

    val failure = attributionFailure
    if (failure is CancellationException) {
        restoreFailure?.let(failure::addSuppressed)
        throw failure
    }
    if (restoreFailure != null) {
        failure?.let(restoreFailure::addSuppressed)
        throw restoreFailure
    }
    return when (failure) {
        null -> null
        is Exception -> failure
        else -> throw failure
    }
}

private const val EXTERNAL_INSTALL_RESULT_GRACE_MS = 10_000L
private const val EXTERNAL_INSTALL_POLL_INTERVAL_MS = 250L
