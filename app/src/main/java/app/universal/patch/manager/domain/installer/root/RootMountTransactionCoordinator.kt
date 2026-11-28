package app.universal.patch.manager.domain.installer.root

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private data class CorruptCommittedRecoveryDecision(
    val resultToReturn: RootMountResult? = null,
    val verifiedOutcome: String? = null
)

private class RootTargetAppBusyException(message: String) : IllegalStateException(message)

private data class RootMountPreparation(
    val initialMountedState: RootPackageState,
    val patchedArtifact: RootArtifactState?,
    val stockArtifacts: List<RootArtifactState>,
    val preflightMounts: List<MountInfoEntry>,
    val legacyPayload: RootBackupArtifact?,
    val journalPatchedArtifact: RootArtifactState?,
    val reusableModuleState: RootCommittedState?
) {
    val legacyMigration: Boolean get() = legacyPayload != null
}

private data class InterruptedJournalResolution(
    val result: RootMountResult? = null,
    val previousCommitted: RootCommittedState?,
    val recoveredModuleState: RootCommittedState?,
    val corruptCommitted: Boolean
)

private data class DurableCompletion(
    val committedState: RootCommittedState?
)

private data class DurableCompletionCleanup(
    val snapshotCleaned: Boolean,
    val failure: Throwable?
)

internal data class RootMountStateIdentity(
    val transactionId: String,
    val packageName: String,
    val userId: Int,
    val stockSha256: String,
    val patchedSha256: String,
    val committedAtEpochMs: Long
)

internal data class RootRestoreDowngradeTarget(
    val savedTransactionId: String,
    val savedStockSha256: String,
    val savedPatchedSha256: String,
    val installedStock: RootPackageState
)

internal data class RootPayloadRestoreAttempt(
    val result: RootMountResult,
    val downgradeTarget: RootRestoreDowngradeTarget? = null
)

internal data class ConditionalRootMountExecution(
    val matched: Boolean,
    val stateIdentity: RootMountStateIdentity?
)

class RootMountTransactionCoordinator(
    private val shell: RootShellGateway,
    private val packageStateReader: PackageStateReader,
    private val mountVerifier: RootMountVerification,
    private val transactionStore: RootTransactionStorage,
    private val moduleStore: RootModuleStorage,
    private val packageInstaller: RootPackageInstallation,
    private val packageLock: RootPackageLocking,
    private val reconciliationScheduler: RootReconciliationScheduling
) {
    suspend fun hasActiveMountState(packageName: String): Boolean =
        withContext(Dispatchers.IO) {
            mutexFor(packageName).withLock {
                transactionStore.initialize()
                val committed = transactionStore.readCommitted(packageName)
                val moduleState = moduleStore.readCommittedState(packageName)
                val interruptedPrevious = transactionStore.readActive(packageName)
                    ?.previousCommitted
                listOfNotNull(committed, moduleState, interruptedPrevious).any { state ->
                    isValidCommittedState(packageName, state) && state.active
                }
            }
        }

    suspend fun execute(
        request: RootMountRequest,
        onPhase: (RootMountPhase) -> Unit = {}
    ): RootMountResult = withContext(Dispatchers.IO) {
        mutexFor(request.packageName).withLock {
            executeWithLockHeld(request, onPhase)
        }
    }

    internal suspend fun restoreCommittedPayload(
        packageName: String,
        userId: Int,
        cacheDirectory: File,
        confirmation: RootRestoreDowngradeTarget? = null
    ): RootPayloadRestoreAttempt? = withContext(Dispatchers.IO) {
        mutexFor(packageName).withLock {
            transactionStore.initialize()
            val state = transactionStore.readCommitted(packageName)?.takeIf {
                it.userId == userId && !it.active && isValidCommittedState(packageName, it)
            }
            if (state == null) {
                check(confirmation == null) { "The saved root mount changed. Start repair again." }
                return@withLock null
            }
            val current = packageStateReader.read(packageName, userId)
            val target = RootRestoreDowngradeTarget(
                state.transactionId, state.stockSha256, state.patchedSha256, current.downgradeIdentity()
            )
            check(confirmation == null || confirmation == target) {
                "The saved root mount or installed stock APK changed. Start repair again."
            }
            val installedStockMatches = matchesCommittedStock(state, current)
            if (state.status != "REPAIR_REQUIRED" && installedStockMatches &&
                moduleStore.readCommittedState(packageName)?.let { matchesReusableModule(state, it) } == true
            ) {
                return@withLock RootPayloadRestoreAttempt(
                    executeWithLockHeld(RootMountRequest(packageName, userId, RootMountOperation.MOUNT_ONLY), {})
                )
            }
            if (current.installed && current.signerSha256 != state.signerSha256) return@withLock null
            val directory = kotlin.io.path.createTempDirectory(cacheDirectory.toPath(), "root-restore-").toFile()
            try {
                // Older modules archived the stock base without its splits. Only
                // reuse installed files when the entire stock identity still matches.
                val installedStockPaths = if (installedStockMatches) {
                    listOf(requireNotNull(current.basePath)) + current.splitPaths.sorted()
                } else {
                    emptyList()
                }
                val files = moduleStore.copyCommittedPayloads(state, directory, installedStockPaths)
                if (files.size < 2) {
                    check(confirmation == null) { "The saved root mount APKs are unavailable. Start repair again." }
                    return@withLock null
                }
                val result = executeWithLockHeld(
                    RootMountRequest(
                        packageName = packageName,
                        userId = userId,
                        operation = RootMountOperation.REPLACE_STOCK_AND_MOUNT,
                        patchedApk = files.first(),
                        stockApks = files.drop(1),
                        expectedVersionName = state.versionName,
                        expectedStockVersionCode = state.versionCode,
                        downgradeFallbackConfirmed = confirmation != null,
                        downgradeFallbackStock = confirmation?.installedStock
                    ),
                    {}
                )
                RootPayloadRestoreAttempt(
                    result,
                    target.takeIf { result is RootMountResult.RequiresDowngradeConfirmation }
                )
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    private fun RootPackageState.downgradeIdentity(): RootPackageState =
        copy(launcherResolvable = false, splitPaths = splitPaths.sorted())

    private fun verifyDowngradeFallbackStock(request: RootMountRequest, current: RootPackageState) {
        val expected = request.downgradeFallbackStock ?: return
        check(current.downgradeIdentity() == expected) {
            "The installed stock APK changed after downgrade confirmation. Start repair again."
        }
    }

    internal suspend fun suspendForExternalInstall(
        request: RootMountRequest
    ): RootMountStateIdentity = withContext(Dispatchers.IO) {
        mutexFor(request.packageName).withLock {
            check(request.operation == RootMountOperation.UNMOUNT) {
                "External install suspension must unmount the package"
            }
            executeWithLockHeld(request, {}).requireSuccess()
            val committed = transactionStore.readCommitted(request.packageName)
                ?: error("Root mount suspension state was not committed")
            check(isValidCommittedState(request.packageName, committed) && !committed.active) {
                "Root mount suspension state is invalid"
            }
            committed.identity()
        }
    }

    internal suspend fun executeIfStateMatches(
        expected: RootMountStateIdentity,
        request: RootMountRequest
    ): ConditionalRootMountExecution = withContext(Dispatchers.IO) {
        mutexFor(request.packageName).withLock {
            transactionStore.initialize()
            val current = transactionStore.readCommitted(request.packageName)
            if (current?.identity() != expected) {
                return@withLock ConditionalRootMountExecution(false, null)
            }

            executeWithLockHeld(request, {}).requireSuccess()
            ConditionalRootMountExecution(
                matched = true,
                stateIdentity = transactionStore.readCommitted(request.packageName)?.identity()
            )
        }
    }

    private suspend fun executeWithLockHeld(
        request: RootMountRequest,
        onPhase: (RootMountPhase) -> Unit
    ): RootMountResult {
        val first = executeLocked(request, onPhase)
        return when (first) {
                is RootMountResult.RecoveredToPreviousMount ->
                    if (request.operation in RESUMABLE_AFTER_RECOVERY_OPERATIONS) {
                        executeAfterRecovery(
                            request,
                            onPhase,
                            RootRecoveryState.PREVIOUS_MOUNT
                        )
                    } else {
                        first
                    }

                is RootMountResult.RecoveredToStock -> when {
                    request.operation == RootMountOperation.UNMOUNT &&
                        !request.removeModuleAfterUnmount -> RootMountResult.Success(first.transactionId)

                    request.operation in MOUNTING_OPERATIONS -> {
                        val committedStatus = runCatchingPreservingCancellation {
                            transactionStore.readCommitted(request.packageName)?.status
                        }
                        when {
                            committedStatus.isFailure -> first
                            committedStatus.getOrNull() == "REPATCH_REQUIRED" &&
                                !runCatchingPreservingCancellation {
                                    requestMatchesRecoveredStock(request)
                                }.getOrDefault(false) ->
                                changedStockRequiresRepatch(first.diagnosticId)
                            else -> executeAfterRecovery(
                                request,
                                onPhase,
                                RootRecoveryState.STOCK
                            )
                        }
                    }

                    request.operation in RESUMABLE_AFTER_RECOVERY_OPERATIONS ->
                        executeAfterRecovery(request, onPhase, RootRecoveryState.STOCK)

                    else -> first
                }

                else -> first
            }
    }

    private fun RootCommittedState.identity() = RootMountStateIdentity(
        transactionId = transactionId,
        packageName = packageName,
        userId = userId,
        stockSha256 = stockSha256,
        patchedSha256 = patchedSha256,
        committedAtEpochMs = committedAtEpochMs
    )

    private suspend fun requestMatchesRecoveredStock(request: RootMountRequest): Boolean {
        if (request.operation != RootMountOperation.SWITCH_PATCHED_BUILD &&
            request.operation != RootMountOperation.REPLACE_STOCK_AND_MOUNT
        ) {
            return false
        }
        val expectedVersionName = request.expectedVersionName?.takeIf(String::isNotBlank)
            ?: return false
        val expectedVersionCode = request.expectedStockVersionCode ?: return false
        val current = packageStateReader.read(request.packageName, request.userId)
        return isStructurallyVerifiedStock(current) &&
            current.packageName == request.packageName &&
            current.userId == request.userId &&
            current.versionName == expectedVersionName &&
            current.versionCode == expectedVersionCode
    }

    private suspend fun executeAfterRecovery(
        request: RootMountRequest,
        onPhase: (RootMountPhase) -> Unit,
        recoveredState: RootRecoveryState
    ): RootMountResult {
        val resumed = executeLocked(request, onPhase)
        return if (
            resumed is RootMountResult.Failure &&
            resumed.phase == RootMountPhase.PREPARING &&
            resumed.recoveryState == RootRecoveryState.NONE
        ) {
            resumed.copy(recoveryState = recoveredState)
        } else {
            resumed
        }
    }

    private fun RootMountResult.preparingRecoveryOutcome(): String? = when (this) {
        is RootMountResult.RecoveredToStock -> RootRecoveryState.STOCK.describeRecovery()
        is RootMountResult.Success ->
            "Automatic recovery removed stale root mount state and left the app uninstalled."
        else -> null
    }

    private suspend fun handleCorruptCommittedState(
        request: RootMountRequest,
        transactionId: String,
        stateUnreadable: Boolean
    ): CorruptCommittedRecoveryDecision {
        val recovery = recoverCorruptCommittedState(
            request.packageName,
            request.userId,
            transactionId,
            if (stateUnreadable) {
                "Committed root mount state is unreadable"
            } else {
                "Committed root mount identity is invalid"
            }
        )
        val canRebuildSavedPayload =
            request.operation == RootMountOperation.MOUNT_ONLY &&
                request.patchedApk != null &&
                request.stockApks.size == 1
        return if (!canRebuildSavedPayload ||
            recovery !is RootMountResult.RecoveredToStock &&
            recovery !is RootMountResult.Success
        ) {
            CorruptCommittedRecoveryDecision(resultToReturn = recovery)
        } else {
            CorruptCommittedRecoveryDecision(
                verifiedOutcome = recovery.preparingRecoveryOutcome()
            )
        }
    }

    private suspend fun mountOnlyCommittedStateBlock(
        request: RootMountRequest,
        previousCommitted: RootCommittedState?
    ): RootMountResult? {
        if (request.operation != RootMountOperation.MOUNT_ONLY) return null
        return when (previousCommitted?.status) {
            "REPATCH_REQUIRED" -> {
                // A boot-time query can fail even though the installed APK set is unchanged.
                // A manual retry still has to prove every recorded stock identity field.
                val current = packageStateReader.read(request.packageName, request.userId)
                if (runCatching { checkCommittedIdentity(requireNotNull(previousCommitted), current) }.isSuccess) {
                    null
                } else {
                    changedStockRequiresRepatch()
                }
            }
            "REPAIR_REQUIRED" -> error("Root mount repair is required before mounting")
            else -> null
        }
    }

    private suspend fun prepareMountExecution(
        request: RootMountRequest,
        previousCommitted: RootCommittedState?,
        recoveredModuleState: RootCommittedState?
    ): RootMountPreparation {
        val initialMountedState = packageStateReader.read(request.packageName, request.userId)
        if (request.operation in MOUNTING_OPERATIONS) {
            requireExclusivePackageUser(request.packageName, request.userId)
        }
        val patchedArtifact = request.patchedApk?.let(packageStateReader::inspect)
        val stockArtifacts = verifiedStockSet(request.stockApks.map { stockApk ->
            inspectRequestedStock(request, initialMountedState, previousCommitted, stockApk)
        })
        val preflightMounts = mountVerifier.findUrvMounts(
            request.packageName,
            setOfNotNull(initialMountedState.basePath, previousCommitted?.stockPath)
        )
        val shouldInspectLegacyPayload = previousCommitted == null && when (request.operation) {
            RootMountOperation.UNMOUNT -> preflightMounts.isNotEmpty()
            RootMountOperation.SWITCH_PATCHED_BUILD ->
                stockArtifacts.isEmpty() && patchedArtifact != null
            else -> false
        }
        val legacyPayload = if (shouldInspectLegacyPayload) {
            moduleStore.readLegacyPayload(request.packageName)
                ?.takeIf { it.sha256 == initialMountedState.baseSha256 }
        } else {
            null
        }
        val journalPatchedArtifact = patchedArtifact ?: legacyPayload?.let { legacy ->
            RootArtifactState(
                path = legacy.path,
                packageName = request.packageName,
                versionName = initialMountedState.versionName,
                versionCode = requireNotNull(initialMountedState.versionCode) {
                    "Legacy mounted payload version code is unavailable"
                },
                signerSha256 = initialMountedState.signerSha256,
                sha256 = legacy.sha256,
                topology = initialMountedState.topology
            )
        }
        val reusableModuleState = if (
            request.operation == RootMountOperation.MOUNT_ONLY &&
            previousCommitted != null &&
            !previousCommitted.active
        ) {
            (recoveredModuleState ?: moduleStore.readCommittedState(request.packageName))
                ?.takeIf { moduleState ->
                    isValidCommittedState(request.packageName, moduleState) &&
                        matchesReusableModule(previousCommitted, moduleState)
                }
        } else {
            null
        }
        val preparation = RootMountPreparation(
            initialMountedState = initialMountedState,
            patchedArtifact = patchedArtifact,
            stockArtifacts = stockArtifacts,
            preflightMounts = preflightMounts,
            legacyPayload = legacyPayload,
            journalPatchedArtifact = journalPatchedArtifact,
            reusableModuleState = reusableModuleState
        )
        preflight(
            request,
            initialMountedState,
            patchedArtifact,
            stockArtifacts,
            previousCommitted,
            preparation.legacyMigration
        )
        if (request.operation in MOUNTING_OPERATIONS && reusableModuleState == null) {
            val rollbackPaths = if (initialMountedState.installed) {
                listOfNotNull(initialMountedState.basePath) + initialMountedState.splitPaths
            } else {
                emptyList()
            }
            val stockInputBytes = if (
                RootMountPolicy.classifyStockTransition(
                    initialMountedState,
                    stockArtifacts.singleOrNull()
                ) != RootMountPolicy.StockTransition.NONE
            ) {
                request.stockApks.sumOf { it.length() }
            } else {
                0L
            }
            val incomingBytes = stockInputBytes +
                (request.patchedApk?.length() ?: 0L)
            moduleStore.ensureRollbackSpace(request.packageName, rollbackPaths, incomingBytes)
        }
        return preparation
    }

    private fun inspectRequestedStock(
        request: RootMountRequest,
        installed: RootPackageState,
        committed: RootCommittedState?,
        stockApk: File
    ): RootArtifactState {
        val installedBasePath = installed.basePath
        val isRegisteredInstalledBase =
            request.operation in setOf(
                RootMountOperation.SWITCH_PATCHED_BUILD,
                RootMountOperation.MOUNT_ONLY
            ) &&
                installed.installed &&
                installedBasePath != null &&
                stockApk.absolutePath == File(installedBasePath).absolutePath
        if (!isRegisteredInstalledBase) return packageStateReader.inspect(stockApk)

        val committedStock = committed?.takeIf { state ->
            state.active &&
                state.packageName == request.packageName &&
                state.userId == request.userId &&
                state.stockPath == installedBasePath
        }
        // Both the root shell and this app process can still see the patched bind at the
        // registered base path. While that committed mount is active, its saved identity is
        // the trustworthy description of the raw stock file exposed after unmounting.
        return RootArtifactState(
            path = requireNotNull(installedBasePath),
            packageName = committedStock?.packageName ?: installed.packageName,
            versionName = committedStock?.versionName ?: installed.versionName,
            versionCode = committedStock?.versionCode ?: requireNotNull(installed.versionCode) {
                "Installed version code is unavailable"
            },
            signerSha256 = committedStock?.signerSha256 ?: installed.signerSha256,
            sha256 = committedStock?.stockSha256 ?: requireNotNull(installed.baseSha256) {
                "Installed APK hash is unavailable"
            },
            topology = committedStock?.topology ?: installed.topology
        )
    }

    suspend fun recoverIncompleteTransactions(userId: Int): Map<String, RootMountResult> {
        require(userId >= 0) { "Invalid Android user" }
        transactionStore.initialize()
        val results = linkedMapOf<String, RootMountResult>()
        for (packageName in transactionStore.listIncompletePackages()) {
            try {
                val interrupted = transactionStore.readActive(packageName)
                val committed = transactionStore.readCommitted(packageName)
                val interruptedUserId = interrupted
                    ?.takeIf { it.packageName == packageName && it.userId >= 0 }
                    ?.userId
                val committedUserId = committed
                    ?.takeIf { isValidCommittedState(packageName, it) }
                    ?.userId
                val recoveryUserId = interruptedUserId ?: committedUserId
                if (recoveryUserId != null && recoveryUserId != userId) {
                    continue
                }
                if (recoveryUserId == null) {
                    val diagnosticId = "unknown-user-${UUID.randomUUID().toString().take(8)}"
                    transactionStore.appendDiagnostic(
                        packageName,
                        diagnosticId,
                        "Incomplete transaction has no trustworthy Android user; automatic recovery was refused"
                    )
                    results[packageName] = RootMountResult.Failure(
                        RootMountPhase.PREPARING,
                        RootRecoveryState.NONE,
                        diagnosticId,
                        "Android user identity is unavailable; use Repair root mount from the affected user"
                    )
                    continue
                }
                val request = RootMountRequest(
                    packageName = packageName,
                    userId = userId,
                    operation = RootMountOperation.RECOVER
                )
                results[packageName] = execute(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                val diagnosticId = "recovery-${UUID.randomUUID().toString().take(8)}"
                runCatching {
                    transactionStore.appendDiagnostic(
                        packageName,
                        diagnosticId,
                        "Incomplete transaction scan failed: ${failure.stackTraceToString()}"
                    )
                }
                results[packageName] = RootMountResult.Failure(
                    RootMountPhase.PREPARING,
                    RootRecoveryState.NONE,
                    diagnosticId,
                    failure.message ?: failure.javaClass.simpleName
                )
            }
        }
        return results
    }
    suspend fun reconcileCommittedTransactions(
        userId: Int,
        requestedPackageName: String? = null
    ): Map<String, RootMountResult> {
        require(userId >= 0) { "Invalid Android user" }
        transactionStore.initialize()
        val results = linkedMapOf<String, RootMountResult>()
        val packages = requestedPackageName?.let { setOf(it) } ?: buildSet {
            addAll(transactionStore.listCommittedPackages())
            addAll(reconciliationScheduler.trackedPackages(userId))
        }
        for (packageName in packages) {
            try {
                val (handled, quickResult) = mutexFor(packageName).withLock {
                    val scanId = "startup-${UUID.randomUUID().toString().take(8)}"
                    val activePhase = runCatching {
                        transactionStore.readActive(packageName)?.phase
                    }.getOrNull()
                    val rootLock = try {
                        packageLock.acquire(packageName, scanId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        return@withLock true to RootMountResult.Busy(
                            activePhase,
                            "Root package lock is temporarily unavailable: " +
                                (failure.message ?: failure.javaClass.simpleName)
                        )
                    } ?: return@withLock true to RootMountResult.Busy(activePhase)
                    try {
                        if (transactionStore.activeExists(packageName)) {
                            val active = transactionStore.readActive(packageName)
                            val activeUserId = active
                                ?.takeIf { it.packageName == packageName && it.userId >= 0 }
                                ?.userId
                            val committedUserId = if (activeUserId == null) {
                                transactionStore.readCommitted(packageName)
                                    ?.takeIf { isValidCommittedState(packageName, it) }
                                    ?.userId
                            } else {
                                null
                            }
                            val recoveryUserId = activeUserId ?: committedUserId
                            if (recoveryUserId == null) {
                                val diagnosticId = "unknown-user-${scanId.take(8)}"
                                transactionStore.appendDiagnostic(
                                    packageName,
                                    diagnosticId,
                                    "Unreadable active transaction has no trustworthy Android user; " +
                                        "committed reconciliation was refused"
                                )
                                return@withLock true to RootMountResult.Failure(
                                    RootMountPhase.PREPARING,
                                    RootRecoveryState.NONE,
                                    diagnosticId,
                                    "Android user identity is unavailable; use Repair root mount from the affected user"
                                )
                            }
                            if (recoveryUserId != userId) {
                                return@withLock true to null
                            }
                            return@withLock false to null
                        }
                        val committedExists = transactionStore.committedExists(packageName)
                        val committed = transactionStore.readCommitted(packageName)
                        if (committed == null) {
                            return@withLock true to if (committedExists) {
                                recoverCorruptCommittedState(
                                    packageName,
                                    userId,
                                    scanId,
                                    "Committed root mount state is unreadable"
                                )
                            } else {
                                stopReconciliation(packageName, userId, scanId)
                                RootMountResult.Success(scanId)
                            }
                        }
                        if (!isValidCommittedState(packageName, committed)) {
                            return@withLock true to recoverCorruptCommittedState(
                                packageName,
                                userId,
                                scanId,
                                "Committed root mount identity is invalid"
                            )
                        }
                        if (!committed.active || committed.userId != userId) {
                            if (!committed.active && committed.userId == userId) {
                                stopReconciliation(packageName, userId, scanId)
                            }
                            return@withLock true to null
                        }
                        runCatching { reconciliationScheduler.ensureScheduled(userId, packageName) }
                        val installedOnlyForRequestedUser = runCatching {
                            packageStateReader.installedUserIds(packageName)
                        }.getOrNull() == setOf(userId)
                        if (!installedOnlyForRequestedUser) {
                            false to null
                        } else if (committed.status == MOUNTED_PENDING_APP_STOP_STATUS) {
                            val pendingCompletion = runCatchingPreservingCancellation {
                                requireExclusivePackageUser(packageName, userId)
                                mountVerifier.verifyMounted(committed)
                                finalizeMountedProcessState(committed)
                            }
                            if (pendingCompletion.isSuccess) {
                                transactionStore.writeCommitted(committed.copy(status = "MOUNTED"))
                                moduleStore.enable(packageName, repairPayloads = false)
                                cleanupCommittedModuleSnapshot(
                                    packageName,
                                    committed.transactionId,
                                    preserveCancellation = true
                                )
                                true to RootMountResult.Success(
                                    committed.transactionId,
                                    automaticallyRemounted = true
                                )
                            } else {
                                transactionStore.appendDiagnostic(
                                    packageName,
                                    "repair-${scanId.take(8)}",
                                    "Could not verify the pending mount in the restarted target process; " +
                                        "falling back to full reconciliation: " +
                                        (pendingCompletion.exceptionOrNull()?.message ?: "unknown failure")
                                )
                                false to null
                            }
                        } else {
                            val initialVerification = runCatchingPreservingCancellation {
                                mountVerifier.verifyMounted(committed)
                                verifyStableRunningProcessState(packageName) { pids ->
                                    mountVerifier.verifyProcessMounted(committed, pids)
                                }
                            }
                            if (initialVerification.isSuccess) {
                                moduleStore.enable(packageName, repairPayloads = false)
                                cleanupCommittedModuleSnapshot(
                                    packageName,
                                    committed.transactionId,
                                    preserveCancellation = true
                                )
                                true to RootMountResult.Success(committed.transactionId)
                            } else {
                                // Namespace repair needs an existing root mount. After reboot, restore
                                // absent mounts through reconciliation without first stopping the app.
                                val rootVerification = runCatchingPreservingCancellation {
                                    mountVerifier.verifyRootMounted(committed)
                                }
                                if (rootVerification.isFailure) {
                                    val mounts = mountVerifier.findUrvMounts(packageName, setOf(committed.stockPath))
                                    val message = if (mounts.none { it.mountPoint == committed.stockPath }) {
                                        "Committed mount is absent; restoring it through full reconciliation"
                                    } else {
                                        "Committed root mount verification failed; using full reconciliation: " +
                                            rootVerification.exceptionOrNull()?.message
                                    }
                                    transactionStore.appendDiagnostic(packageName, scanId, message)
                                    return@withLock false to null
                                }
                                // A package or Zygote namespace can lose a propagated bind while
                                // the root mount itself remains correct. Repair the committed mount
                                // in place first so background reconciliation does not tear down a
                                // working patched app just because one namespace drifted.
                                val nonDestructiveRepair = runCatchingPreservingCancellation {
                                    try {
                                        stopAndWait(packageName, userId)
                                    } catch (_: RootTargetAppBusyException) {
                                        // Continue after proving the existing root mount below. The
                                        // target is force-stopped again after Zygote is repaired.
                                    }
                                    requireExclusivePackageUser(packageName, userId)
                                    // Recheck after stopping the app, before changing namespaces.
                                    mountVerifier.verifyRootMounted(committed)
                                    mountVerifier.mountEverywhere(committed)
                                    mountVerifier.verifyMounted(committed)
                                    finalizeMountedProcessState(committed)
                                }
                                if (nonDestructiveRepair.isSuccess) {
                                    moduleStore.enable(packageName)
                                    transactionStore.appendDiagnostic(
                                        packageName,
                                        "repair-${scanId.take(8)}",
                                        "Repaired committed mount namespaces in place after verification failed: " +
                                            (initialVerification.exceptionOrNull()?.message ?: "unknown verification failure")
                                    )
                                    cleanupCommittedModuleSnapshot(
                                        packageName,
                                        committed.transactionId,
                                        preserveCancellation = true
                                    )
                                    true to RootMountResult.Success(
                                        committed.transactionId,
                                        automaticallyRemounted = true
                                    )
                                } else {
                                    transactionStore.appendDiagnostic(
                                        packageName,
                                        "repair-${scanId.take(8)}",
                                        "Non-destructive committed mount repair was not possible; " +
                                            "falling back to full reconciliation. Initial verification: " +
                                            (initialVerification.exceptionOrNull()?.message ?: "unknown failure") +
                                            "; repair: " +
                                            (nonDestructiveRepair.exceptionOrNull()?.message ?: "unknown failure")
                                    )
                                    false to null
                                }
                            }
                        }
                    } finally {
                        withContext(NonCancellable) {
                            runCatching { packageLock.release(rootLock) }.onFailure { releaseFailure ->
                                runCatching {
                                    transactionStore.appendDiagnostic(
                                        packageName,
                                        "lock-${scanId.take(8)}",
                                        "Failed to release package lock cleanly: ${releaseFailure.message}"
                                    )
                                }
                            }
                        }
                    }
                }
                when {
                    quickResult != null -> results[packageName] = quickResult
                    !handled -> results[packageName] = execute(
                        RootMountRequest(packageName, userId, RootMountOperation.RECONCILE)
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                val diagnosticId = "startup-${UUID.randomUUID().toString().take(8)}"
                runCatching {
                    transactionStore.appendDiagnostic(
                        packageName,
                        diagnosticId,
                        "Startup reconciliation failed: ${failure.stackTraceToString()}"
                    )
                }
                results[packageName] = RootMountResult.Failure(
                    RootMountPhase.PREPARING,
                    RootRecoveryState.NONE,
                    diagnosticId,
                    failure.message ?: failure.javaClass.simpleName
                )
            }
        }
        return results
    }
    suspend fun exportDiagnostics(packageName: String): String =
        withContext(Dispatchers.IO) {
            transactionStore.exportDiagnostics(packageName)
        }

    private suspend fun executeLocked(
        request: RootMountRequest,
        onPhase: (RootMountPhase) -> Unit
    ): RootMountResult {
        reportProgress(onPhase, RootMountPhase.PREPARING)
        transactionStore.initialize()
        val transactionId = UUID.randomUUID().toString()
        val activePhase = runCatching {
            transactionStore.readActive(request.packageName)?.phase
        }.getOrNull()
        val rootLock = try {
            packageLock.acquire(request.packageName, transactionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            return RootMountResult.Busy(
                activePhase,
                "Root package lock is temporarily unavailable: " +
                    (failure.message ?: failure.javaClass.simpleName)
            )
        } ?: return RootMountResult.Busy(activePhase)
        var journal: RootMountJournal? = null
        var currentPhase = RootMountPhase.PREPARING
        var stockChanged = false
        var moduleChanged = false
        var transactionCompleted = false
        var stockBackup: List<RootBackupArtifact>? = null
        var preparingRecoveryOutcome: String? = null
        fun progress(phase: RootMountPhase) {
            currentPhase = phase
            reportProgress(onPhase, phase)
        }
        suspend fun persist(transform: (RootMountJournal) -> RootMountJournal = { it }) {
            val updated = transform(requireNotNull(journal)).copy(phase = currentPhase)
            transactionStore.writeActive(updated)
            journal = updated
        }

        try {
            val storedCommitted = transactionStore.readCommitted(request.packageName)
            val committedFileExists = transactionStore.committedExists(request.packageName)
            val validStoredCommitted = storedCommitted?.takeIf {
                isValidCommittedState(request.packageName, it)
            }
            val interrupted = transactionStore.readActive(request.packageName)
            val activeExists = transactionStore.activeExists(request.packageName)
            var recoveredModuleState = if (
                validStoredCommitted == null &&
                !committedFileExists &&
                !activeExists &&
                request.operation in MANUAL_STATE_RECOVERY_OPERATIONS
            ) {
                moduleStore.readCommittedState(request.packageName)?.takeIf {
                    isValidCommittedState(request.packageName, it)
                }
            } else {
                null
            }
            if (recoveredModuleState != null) {
                transactionStore.writeCommitted(recoveredModuleState)
                transactionStore.appendDiagnostic(
                    request.packageName,
                    "module-state-${transactionId.take(8)}",
                    "Recovered missing transaction state from the verified root module state"
                )
            }
            var previousCommitted = validStoredCommitted ?: recoveredModuleState
            var corruptCommitted = committedFileExists && previousCommitted == null
            if (previousCommitted != null && previousCommitted.userId != request.userId) {
                val diagnosticId = "user-${transactionId.take(8)}"
                transactionStore.appendDiagnostic(
                    request.packageName,
                    diagnosticId,
                    "Committed root mount belongs to Android user ${previousCommitted.userId}; " +
                        "request for user ${request.userId} was refused"
                )
                return RootMountResult.Failure(
                    RootMountPhase.PREPARING,
                    RootRecoveryState.NONE,
                    diagnosticId,
                    "This root mount is committed for a different Android user"
                )
            }
            if (activeExists && interrupted == null) {
                return recoverCorruptJournal(request, previousCommitted, transactionId)
            }
            if (interrupted != null) {
                val resolution = resolveInterruptedJournal(
                    request,
                    interrupted,
                    transactionId,
                    storedCommitted,
                    validStoredCommitted,
                    committedFileExists,
                    previousCommitted,
                    recoveredModuleState,
                    corruptCommitted,
                    onPhase
                )
                resolution.result?.let { return it }
                previousCommitted = resolution.previousCommitted
                recoveredModuleState = resolution.recoveredModuleState
                corruptCommitted = resolution.corruptCommitted
            }
            if (corruptCommitted) {
                val recoveryDecision = handleCorruptCommittedState(
                    request,
                    transactionId,
                    storedCommitted == null
                )
                val resultToReturn = recoveryDecision.resultToReturn
                if (resultToReturn != null) {
                    return resultToReturn
                }
                preparingRecoveryOutcome = recoveryDecision.verifiedOutcome
            }
            val mountOnlyBlock = mountOnlyCommittedStateBlock(request, previousCommitted)
            if (mountOnlyBlock != null) return mountOnlyBlock
            if (request.operation == RootMountOperation.RECOVER) {
                if (previousCommitted?.active != true) {
                    stopReconciliation(request.packageName, request.userId, transactionId)
                }
                return RootMountResult.Success(transactionId)
            }
            if (request.operation == RootMountOperation.RECONCILE && previousCommitted == null) {
                stopReconciliation(request.packageName, request.userId, transactionId)
                return RootMountResult.Success(transactionId)
            }

            val preparation = prepareMountExecution(
                request,
                previousCommitted,
                recoveredModuleState
            )
            val initialMountedState = preparation.initialMountedState
            val patchedArtifact = preparation.patchedArtifact
            val stockArtifacts = preparation.stockArtifacts
            val preflightMounts = preparation.preflightMounts
            val legacyPayload = preparation.legacyPayload
            val legacyMigration = preparation.legacyMigration
            val journalPatchedArtifact = preparation.journalPatchedArtifact
            val reusableModuleState = preparation.reusableModuleState
            reconciliationScheduler.ensureScheduled(request.userId, request.packageName)

            journal = createJournal(
                transactionId,
                request,
                currentPhase,
                initialMountedState,
                journalPatchedArtifact,
                stockArtifacts.singleOrNull(),
                previousCommitted,
                preflightMounts.map { it.mountPoint }.distinct(),
                legacyMigration,
                reusableModuleState != null
            )
            persist()

            progress(RootMountPhase.STOPPING_APP)
            persist()
            val restartTolerantMount = request.operation in RESTART_TOLERANT_MOUNT_OPERATIONS
            stopForMountMutation(
                request,
                restartTolerantMount,
                transactionId,
                "before mount cleanup"
            )

            progress(RootMountPhase.REMOVING_OLD_MOUNTS)
            persist { it.copy(mountMutationStarted = true) }
            val knownTargets = setOfNotNull(
                initialMountedState.basePath,
                previousCommitted?.stockPath
            ) + requireNotNull(journal).candidateMountTargets
            removeOldMountsForTransaction(
                request.packageName, knownTargets, transactionId,
                includeCompeting = request.operation in MOUNTING_OPERATIONS
            )

            if (request.operation == RootMountOperation.UNMOUNT) {
                val rawStock = packageStateReader.read(request.packageName, request.userId)
                val externalStockUpdate = previousCommitted?.let {
                    isVerifiedExternalStockUpdate(it, rawStock)
                } == true
                if (rawStock.installed) {
                    check(rawStock.packageName == request.packageName && rawStock.userId == request.userId) {
                        "Unmounted package identity mismatch"
                    }
                    previousCommitted?.let {
                        // Permanent removal leaves the current installed APK untouched. It
                        // does not need compatibility with a payload that is being deleted.
                        if (request.removeModuleAfterUnmount || externalStockUpdate) {
                            check(isStructurallyVerifiedExternalStock(rawStock)) {
                                "Current stock package state could not be verified after unmount"
                            }
                        } else {
                            checkCommittedIdentity(it, rawStock)
                        }
                        check(rawStock.basePath?.isSafeAbsoluteApkPath() == true) {
                            "Unmounted stock base path is unsafe"
                        }
                    } ?: check(isStructurallyVerifiedExternalStock(rawStock)) {
                        "Raw stock package state could not be verified after unmount"
                    }
                } else {
                    check(request.removeModuleAfterUnmount) {
                        "Package is not installed"
                    }
                    check(rawStock.packageName == request.packageName) { "Package name mismatch" }
                    check(rawStock.userId == request.userId) { "Android user mismatch" }
                }
                check(packageStateReader.waitUntilStopped(request.packageName, 1_000)) {
                    "Target app restarted while verifying the unmounted stock package"
                }
                var migratedLegacyState: RootCommittedState? = null
                if (!request.removeModuleAfterUnmount && legacyPayload != null) {
                    check(rawStock.installed) { "Legacy mount migration requires an installed stock package" }
                    val legacyArtifact = requireNotNull(journalPatchedArtifact)
                    check(rawStock.versionName == legacyArtifact.versionName) {
                        "Legacy mounted payload and stock version names differ"
                    }
                    check(rawStock.versionCode == legacyArtifact.versionCode) {
                        "Legacy mounted payload and stock version codes differ"
                    }
                    check(rawStock.signerSha256 == legacyArtifact.signerSha256) {
                        "Legacy mounted payload and stock signing identities differ"
                    }
                    check(rawStock.baseSha256 != legacyPayload.sha256) {
                        "Legacy mounted payload is byte-identical to raw stock"
                    }

                    progress(RootMountPhase.SNAPSHOTTING)
                    persist { it.copy(expectedPackageState = rawStock, status = "LEGACY_UNMOUNT_SNAPSHOT_PENDING") }
                    val moduleBackupHash = moduleStore.snapshot(request.packageName, legacyPayload)
                    check(moduleBackupHash == legacyPayload.sha256) {
                        "Legacy root module could not be snapshotted and verified"
                    }

                    progress(RootMountPhase.STAGING_PATCHED_PAYLOAD)
                    persist {
                        it.copy(
                            status = "LEGACY_UNMOUNT_STAGE_PENDING",
                            moduleMutationStarted = true,
                            moduleRestoreRequired = true
                        )
                    }
                    moduleChanged = true
                    val patchedPath = moduleStore.stageAndActivate(
                        transactionId = transactionId,
                        packageName = request.packageName,
                        label = request.label.ifBlank { request.packageName },
                        patchedApk = File(legacyPayload.path),
                        compatible = rawStock,
                        patchedHash = legacyPayload.sha256
                    )
                    migratedLegacyState = RootCommittedState(
                        transactionId = transactionId,
                        packageName = request.packageName,
                        userId = request.userId,
                        versionName = requireNotNull(rawStock.versionName),
                        versionCode = requireNotNull(rawStock.versionCode),
                        signerSha256 = rawStock.signerSha256,
                        stockPath = requireNotNull(rawStock.basePath),
                        stockSha256 = requireNotNull(rawStock.baseSha256),
                        patchedPath = patchedPath,
                        patchedSha256 = legacyPayload.sha256,
                        stockShadowPath = RootPaths.moduleStockApk(request.packageName),
                        stockShadowSha256 = requireNotNull(rawStock.baseSha256),
                        preserveStockAcrossBoot = true,
                        topology = rawStock.topology,
                        stockSplits = rawStock.verifiedSplits(),
                        enabled = rawStock.enabled,
                        launcherResolvable = rawStock.launcherResolvable,
                        active = false,
                        status = "STOCK",
                        committedAtEpochMs = System.currentTimeMillis()
                    )
                } else if (request.removeModuleAfterUnmount) {
                    progress(RootMountPhase.SNAPSHOTTING)
                    persist()
                    val moduleBackupHash = moduleStore.snapshot(request.packageName)
                    previousCommitted?.let { committed ->
                        check(moduleBackupHash == committed.patchedSha256) {
                            "Root module removal backup could not be verified"
                        }
                    }
                    persist {
                        it.copy(
                            moduleMutationStarted = true,
                            moduleRestoreRequired = true
                        )
                    }
                    moduleChanged = true
                }
                progress(RootMountPhase.COMMITTING)
                persist {
                    it.copy(
                        status = when {
                            request.removeModuleAfterUnmount -> "MODULE_REMOVAL_PENDING"
                            migratedLegacyState != null -> "LEGACY_UNMOUNT_COMMIT_PENDING"
                            else -> "MODULE_DISABLE_PENDING"
                        }
                    )
                }
                moduleStore.disable(request.packageName)
                if (request.removeModuleAfterUnmount) {
                    moduleStore.removeActive(request.packageName)
                    persist { it.copy(status = "MODULE_REMOVED") }
                    moduleStore.commitSnapshot(request.packageName)
                } else if (migratedLegacyState != null) {
                    moduleStore.commitSnapshot(request.packageName)
                }
                val inactive = if (request.removeModuleAfterUnmount) {
                    null
                } else {
                    migratedLegacyState ?: previousCommitted?.copy(
                        active = false,
                        status = if (externalStockUpdate) "REPATCH_REQUIRED" else "STOCK",
                        committedAtEpochMs = System.currentTimeMillis()
                    )
                }
                withContext(NonCancellable) {
                    if (inactive == null) transactionStore.clearCommitted(request.packageName)
                    completeTransaction(requireNotNull(journal), inactive)
                    transactionCompleted = true
                    if (request.removeModuleAfterUnmount) {
                        val cleanupFailure = runCatching {
                            moduleStore.purgeBackups(request.packageName)
                        }.exceptionOrNull()
                        if (cleanupFailure != null) {
                            runCatching {
                                transactionStore.appendDiagnostic(
                                    request.packageName,
                                    "cleanup-${transactionId.take(8)}",
                                    "Permanent module removal committed, but backup cleanup failed: " +
                                        (cleanupFailure.message ?: cleanupFailure::class.java.simpleName)
                                )
                            }
                        }
                    }
                    stopReconciliation(request.packageName, request.userId, transactionId)
                }
                return RootMountResult.Success(transactionId)
            }

            val initial = packageStateReader.read(request.packageName, request.userId)
            check(initial.matchesSplits(initialMountedState.verifiedSplits())) {
                "Installed split APKs changed after preflight; retry with the current package"
            }
            if (request.operation == RootMountOperation.RECONCILE) {
                return reconcile(
                    request,
                    requireNotNull(previousCommitted),
                    initial,
                    transactionId,
                    onPhase,
                    requireNotNull(journal)
                ) {
                    transactionCompleted = true
                }
            }

            progress(RootMountPhase.SNAPSHOTTING)
            persist { it.copy(initialPackageState = initial) }
            val snapshot = snapshotPreviousModule(request, preparation, initial, previousCommitted, transactionId)
            val rebuildMountOnlyModule = snapshot.rebuildMountOnlyModule
            if (snapshot.useStockForRollback) {
                persist { it.copy(previousCommitted = null) }
                previousCommitted = null
            }
            val useRequestedPayload =
                request.operation != RootMountOperation.MOUNT_ONLY || rebuildMountOnlyModule
            val stock = stockArtifacts.singleOrNull().takeIf { useRequestedPayload }
            val effectivePatchedArtifact = patchedArtifact.takeIf { useRequestedPayload }
            val stockTransition = verifyStockTransition(
                request, initial, stock, patchedArtifact, previousCommitted, legacyMigration
            )
            val stockNeedsChange = stockTransition != RootMountPolicy.StockTransition.NONE

            // A raw stock backup is only needed when PackageInstaller will mutate the
            // installed package. Copying and syncing it for an ordinary remount or bundle
            // switch can add hundreds of megabytes of unnecessary root I/O.
            if (stockNeedsChange && initial.installed) {
                stockBackup = snapshotStockForRollback(request.packageName, initial)
            }

            var stableStock = initial
            if (stockNeedsChange) {
                val requiredStock = requireNotNull(stock) {
                    "A complete stock APK is required for this package transition"
                }
                val stockFiles = request.stockApks.also {
                    require(it.isNotEmpty()) { "A complete stock APK set is required for this package transition" }
                }
                progress(RootMountPhase.INSTALLING_STOCK)
                persist { it.copy(stockMutationStarted = true) }
                val downgrade = stockTransition == RootMountPolicy.StockTransition.DOWNGRADE
                val replacement = packageInstaller.replace(
                    stockFiles,
                    userId = request.userId,
                    allowDowngrade = downgrade
                )
                var replacementProven = replacement is RootPackageReplaceResult.Success
                if (!replacementProven) {
                    val afterRejectedReplacement = packageStateReader.read(request.packageName, request.userId)
                    stockChanged = packageRegistrationChanged(initial, afterRejectedReplacement)
                    if (matchesRequestedStock(afterRejectedReplacement, requiredStock)) {
                        val expectedAfterAmbiguousCommit = afterRejectedReplacement.copy(
                            installed = true,
                            versionName = requiredStock.versionName,
                            versionCode = requiredStock.versionCode,
                            signerSha256 = requiredStock.signerSha256,
                            baseSha256 = requiredStock.sha256
                        )
                        val stableAfterAmbiguousCommit = runCatching {
                            packageStateReader.waitForStable(expectedAfterAmbiguousCommit)
                        }.getOrNull()
                        replacementProven = stableAfterAmbiguousCommit?.let {
                            matchesRequestedStock(it, requiredStock)
                        } == true
                    }
                    if (!replacementProven && replacement is RootPackageReplaceResult.DowngradeRejected) {
                        verifyDowngradeFallbackStock(request, afterRejectedReplacement)
                    }
                }
                if (!replacementProven && downgrade) {
                    if (replacement !is RootPackageReplaceResult.DowngradeRejected) {
                        replacement.getOrThrow()
                        error("Package replacement failed without an error")
                    }
                    check(!initial.systemApp) {
                        "Automatic per-user removal is disabled for system-app downgrade recovery"
                    }
                    if (!request.downgradeFallbackConfirmed) {
                        val diagnosticId = "downgrade-${transactionId.take(8)}"
                        transactionStore.appendDiagnostic(
                            request.packageName,
                            diagnosticId,
                            replacement.cause.message ?: "In-place downgrade rejected"
                        )
                        val recovery = rollback(
                            requireNotNull(journal),
                            stockChanged = stockChanged,
                            moduleChanged = false,
                            stockBackup = stockBackup,
                            diagnosticId = diagnosticId,
                            reason = "In-place downgrade rejected before registration changed"
                        )
                        if (recovery is RootMountResult.Failure) return recovery
                        return RootMountResult.RequiresDowngradeConfirmation(
                            "Android rejected the in-place downgrade. Confirm keep-data uninstall/reinstall to continue."
                        )
                    }
                    persist { it.copy(registrationGap = true) }
                    stockChanged = true
                    packageInstaller.uninstallKeepData(request.packageName, request.userId)
                    packageInstaller.replace(
                        stockFiles,
                        userId = request.userId,
                        allowDowngrade = false
                    ).getOrThrow()
                } else {
                    if (!replacementProven) replacement.getOrThrow()
                    stockChanged = stockChanged || replacement is RootPackageReplaceResult.Success
                }

                progress(RootMountPhase.WAITING_FOR_PACKAGE_MANAGER)
                persist()
                waitForPackageManagerIdle()
                val observed = packageStateReader.read(request.packageName, request.userId)
                check(observed.matchesRequestedSplits(requiredStock)) {
                    "Installed split APKs do not match the verified stock input"
                }
                val expected = observed.copy(
                    installed = true,
                    versionName = requiredStock.versionName,
                    versionCode = requiredStock.versionCode,
                    signerSha256 = requiredStock.signerSha256,
                    baseSha256 = requiredStock.sha256,
                    enabled = if (initial.installed) initial.enabled else observed.enabled,
                    launcherResolvable = if (initial.installed) {
                        initial.launcherResolvable
                    } else {
                        observed.launcherResolvable
                    }
                )
                stableStock = packageStateReader.waitForStable(expected)
                check(stableStock.baseSha256 == requiredStock.sha256) {
                    "Installed stock APK hash does not match the verified input"
                }
            }

            verifyStableStock(request, stableStock, effectivePatchedArtifact)
            val compatible = stableStock.copy(baseSha256 = stableStock.baseSha256)
            val mountOnlyCommitted = if (
                request.operation == RootMountOperation.MOUNT_ONLY &&
                !rebuildMountOnlyModule
            ) {
                val previous = requireNotNull(previousCommitted) { "No committed payload is available to mount" }
                checkCommittedIdentity(previous, compatible)
                val currentStockPath = requireNotNull(compatible.basePath) {
                    "Installed stock base path is unavailable"
                }
                check(currentStockPath.isSafeAbsoluteApkPath()) { "Installed stock base path is unsafe" }
                val pathChanged = currentStockPath != previous.stockPath
                val retargeted = previous.copy(
                    transactionId = if (pathChanged) transactionId else previous.transactionId,
                    stockPath = currentStockPath,
                    active = true,
                    status = "MOUNTED",
                    committedAtEpochMs = System.currentTimeMillis()
                )
                if (pathChanged) {
                    persist {
                        it.copy(
                            expectedPackageState = compatible,
                            moduleMutationStarted = true,
                            moduleRestoreRequired = false,
                            status = "MODULE_STATE_RETARGET_PENDING"
                        )
                    }
                    moduleStore.updateState(retargeted)
                }
                moduleStore.enable(request.packageName)
                retargeted
            } else {
                null
            }
            val activePatchedPath = mountOnlyCommitted?.patchedPath ?: run {
                progress(RootMountPhase.STAGING_PATCHED_PAYLOAD)
                persist {
                    it.copy(
                        expectedPackageState = compatible,
                        moduleMutationStarted = true,
                        moduleRestoreRequired = true
                    )
                }
                moduleChanged = true
                moduleStore.stageAndActivate(
                    transactionId = transactionId,
                    packageName = request.packageName,
                    label = request.label,
                    patchedApk = requireNotNull(request.patchedApk),
                    compatible = compatible,
                    patchedHash = requireNotNull(patchedArtifact).sha256
                )
            }

            val patchedHash = effectivePatchedArtifact?.sha256
                ?: requireNotNull(previousCommitted).patchedSha256
            val committed = mountOnlyCommitted ?: createCommittedState(
                transactionId,
                request,
                compatible,
                activePatchedPath,
                patchedHash
            )

            progress(RootMountPhase.MOUNTING)
            persist {
                it.copy(
                    expectedPackageState = compatible,
                    mountMutationStarted = true
                )
            }
            applyMountBeforeVerification(
                request,
                committed,
                restartTolerantMount,
                transactionId
            )

            progress(RootMountPhase.VERIFYING)
            persist()
            verifyMountedTargetAfterApplication(committed, transactionId)

            progress(RootMountPhase.COMMITTING)
            persist()
            withContext(NonCancellable) {
                completeTransaction(
                    requireNotNull(journal),
                    committed.copy(status = "MOUNTED"),
                    finalizeMount = true
                )
                transactionCompleted = true
            }
            progress(RootMountPhase.COMPLETED)
            return RootMountResult.Success(transactionId)
        } catch (cancelled: CancellationException) {
            if (transactionCompleted) throw cancelled
            val localJournal = journal
            val activeJournal = withContext(NonCancellable) {
                runCatching { transactionStore.readActive(request.packageName) }.getOrNull() ?: localJournal
            }
            val cancelledPhase = activeJournal?.phase ?: currentPhase
            if (
                activeJournal != null &&
                preserveDurableCompletionOnCancellation(request, activeJournal, transactionId)
            ) {
                throw cancelled
            }
            val mutationStarted = activeJournal?.let { active ->
                stockChanged ||
                    moduleChanged ||
                    RootMountPolicy.interruptedJournalMayHaveChangedStock(active) ||
                    RootMountPolicy.interruptedJournalMayHaveChangedModule(active) ||
                    RootMountPolicy.interruptedJournalMayHaveChangedMounts(active) ||
                    active.moduleMutationStarted == true
            } ?: false
            if (activeJournal != null && !mutationStarted) {
                withContext(NonCancellable) {
                    runCatching { transactionStore.clearActive(request.packageName) }
                        .onFailure { cleanupFailure ->
                            runCatching {
                                transactionStore.appendDiagnostic(
                                    request.packageName,
                                    "cancel-${transactionId.take(8)}",
                                    "Cancellation occurred before any package, module, or mount mutation, " +
                                        "but the untouched journal could not be cleared; existing state was left unchanged: " +
                                        (cleanupFailure.message ?: cleanupFailure.javaClass.simpleName)
                                )
                            }
                        }
                }
                throw cancelled
            }
            if (activeJournal != null) {
                currentPhase = RootMountPhase.ROLLING_BACK
                reportMandatoryRecoveryProgress(onPhase)
            }
            val cancellationRecovery = withContext(NonCancellable) {
                activeJournal?.let {
                    rollback(
                        it.copy(
                            phase = RootMountPhase.ROLLING_BACK,
                            rollbackFromPhase = it.rollbackFromPhase ?: cancelledPhase
                        ),
                        stockChanged || RootMountPolicy.interruptedJournalMayHaveChangedStock(it),
                        moduleChanged || RootMountPolicy.interruptedJournalMayHaveChangedModule(it),
                        stockBackup,
                        "cancel-${transactionId.take(8)}",
                        "Operation cancelled"
                    )
                }
            }
            if (cancellationRecovery is RootMountResult.Failure) return cancellationRecovery
            throw cancelled
        } catch (failure: Throwable) {
            val diagnosticId = "root-${transactionId.take(8)}"
            val activeJournal = runCatchingPreservingCancellation {
                transactionStore.readActive(request.packageName)
            }.getOrNull() ?: journal
            val failedPhase = activeJournal?.phase ?: currentPhase
            val failureMessage = failure.message ?: failure.javaClass.simpleName
            durableCompletionFailureResult(
                request,
                activeJournal,
                diagnosticId,
                failureMessage
            )?.let { return it }
            val mutationStarted = activeJournal?.let { active ->
                stockChanged ||
                    moduleChanged ||
                    RootMountPolicy.interruptedJournalMayHaveChangedStock(active) ||
                    RootMountPolicy.interruptedJournalMayHaveChangedModule(active) ||
                    RootMountPolicy.interruptedJournalMayHaveChangedMounts(active) ||
                    active.moduleMutationStarted == true
            } ?: false
            if (activeJournal != null && !mutationStarted) {
                val journalCleanupFailure = runCatchingPreservingCancellation {
                    transactionStore.clearActive(request.packageName)
                }.exceptionOrNull()
                val cleanupNote = journalCleanupFailure?.let {
                    " The untouched journal could not be cleared and was left for a later retry: " +
                        (it.message ?: it.javaClass.simpleName)
                }.orEmpty()
                if (failure is RootTargetAppBusyException) {
                    runCatchingPreservingCancellation {
                        transactionStore.appendDiagnostic(
                            request.packageName,
                            "busy-${transactionId.take(8)}",
                            "Deferred root mount operation before changing mounts or any package/module state " +
                                "because the target app did not remain stopped: $failureMessage$cleanupNote"
                        )
                    }
                    return RootMountResult.Busy(failedPhase, failureMessage)
                }
                runCatchingPreservingCancellation {
                    transactionStore.appendDiagnostic(
                        request.packageName,
                        diagnosticId,
                        "Root mount operation failed before changing any package, module, or mount state: " +
                            failure.stackTraceToString() + cleanupNote
                    )
                }
                return RootMountResult.Failure(
                    failedPhase,
                    RootRecoveryState.NONE,
                    diagnosticId,
                    failureMessage,
                    preparingRecoveryOutcome
                )
            }
            runCatching {
                transactionStore.appendDiagnostic(request.packageName, diagnosticId, failure.stackTraceToString())
            }
            if (activeJournal == null) {
                return RootMountResult.Failure(
                    failedPhase,
                    RootRecoveryState.NONE,
                    diagnosticId,
                    failureMessage,
                    preparingRecoveryOutcome
                )
            }
            currentPhase = RootMountPhase.ROLLING_BACK
            reportMandatoryRecoveryProgress(onPhase)
            val recovery = withContext(NonCancellable) {
                rollback(
                    activeJournal.copy(
                        phase = RootMountPhase.ROLLING_BACK,
                        rollbackFromPhase = activeJournal.rollbackFromPhase ?: failedPhase
                    ),
                    stockChanged || RootMountPolicy.interruptedJournalMayHaveChangedStock(activeJournal),
                    moduleChanged || RootMountPolicy.interruptedJournalMayHaveChangedModule(activeJournal),
                    stockBackup,
                    diagnosticId,
                    failureMessage
                )
            }
            return when (recovery) {
                is RootMountResult.RecoveredToPreviousMount -> RootMountResult.Failure(
                    failedPhase,
                    RootRecoveryState.PREVIOUS_MOUNT,
                    recovery.diagnosticId,
                    failureMessage
                )
                is RootMountResult.RecoveredToStock -> RootMountResult.Failure(
                    failedPhase,
                    RootRecoveryState.STOCK,
                    recovery.diagnosticId,
                    failureMessage
                )
                else -> recovery
            }
        } finally {
            withContext(NonCancellable) {
                runCatching { packageLock.release(rootLock) }.onFailure { releaseFailure ->
                    runCatching {
                        transactionStore.appendDiagnostic(
                            request.packageName,
                            "lock-${transactionId.take(8)}",
                            "Failed to release package lock cleanly: ${releaseFailure.message}"
                        )
                    }
                }
            }
        }
    }

    private suspend fun stopForMountMutation(
        request: RootMountRequest,
        restartTolerant: Boolean,
        transactionId: String,
        context: String
    ) {
        try {
            stopAndWait(request.packageName, request.userId)
        } catch (busy: RootTargetAppBusyException) {
            if (!restartTolerant) throw busy
            transactionStore.appendDiagnostic(
                request.packageName,
                "busy-${transactionId.take(8)}",
                "Target app restarted $context; continuing with restart-tolerant mounting: " +
                    (busy.message ?: "target app is active")
            )
        }
    }

    private fun verifyStockTransition(
        request: RootMountRequest,
        initial: RootPackageState,
        stock: RootArtifactState?,
        patchedArtifact: RootArtifactState?,
        previousCommitted: RootCommittedState?,
        legacyMigration: Boolean
    ): RootMountPolicy.StockTransition {
        verifyDowngradeFallbackStock(request, initial)
        val stockTransition = RootMountPolicy.classifyStockTransition(initial, stock)
        if (stockTransition == RootMountPolicy.StockTransition.NONE && stock != null) {
            check(initial.baseSha256 == stock.sha256) {
                "Same-version stock input does not match the unmounted installed stock APK"
            }
            if (stock.splitHashes.isNotEmpty()) check(initial.matchesRequestedSplits(stock)) {
                "Same-version stock split set differs from the installed package"
            }
        }
        if (request.operation == RootMountOperation.SWITCH_PATCHED_BUILD && stock == null) {
            if (legacyMigration) {
                check(initial.versionName == patchedArtifact?.versionName &&
                    initial.versionCode == patchedArtifact?.versionCode
                ) { "Legacy mount and raw stock versions differ" }
            } else {
                checkCommittedIdentity(
                    requireNotNull(previousCommitted) {
                        "No committed stock identity is available for bundle switching"
                    },
                    initial
                )
            }
        }
        return stockTransition
    }

    private data class MountModuleSnapshot(
        val rebuildMountOnlyModule: Boolean,
        val useStockForRollback: Boolean
    )

    private suspend fun snapshotPreviousModule(
        request: RootMountRequest,
        preparation: RootMountPreparation,
        initial: RootPackageState,
        previous: RootCommittedState?,
        transactionId: String
    ): MountModuleSnapshot {
        val hash = preparation.reusableModuleState?.patchedSha256 ?: if (previous?.active == false) {
            moduleStore.snapshotInactive(previous)
        } else {
            moduleStore.snapshot(request.packageName, preparation.legacyPayload)
        }
        val hasPatchedInput = preparation.patchedArtifact != null
        val rebuildMountOnlyModule = request.operation == RootMountOperation.MOUNT_ONLY &&
            hasPatchedInput && (previous == null || hash != previous.patchedSha256)
        if (rebuildMountOnlyModule && previous != null) {
            preflight(
                request,
                initial,
                preparation.patchedArtifact,
                preparation.stockArtifacts,
                committed = null,
                legacyMigration = false
            )
        }
        preparation.legacyPayload?.let { legacy ->
            check(hash == legacy.sha256) { "Legacy root module could not be snapshotted and verified" }
        }
        if (previous == null || hash == previous.patchedSha256) {
            return MountModuleSnapshot(rebuildMountOnlyModule, useStockForRollback = false)
        }

        // An active mount whose module disappeared can only be rebuilt from a
        // complete saved input against the exact stock exposed after unmounting.
        val hasStockInput = preparation.stockArtifacts.singleOrNull() != null
        val verifiedBaseline = when {
            previous.active -> rebuildMountOnlyModule && matchesCommittedStock(previous, initial)
            initial.installed -> matchesCommittedStock(previous, initial) ||
                isVerifiedExternalStockUpdate(previous, initial)
            else -> true
        }
        check(hasPatchedInput && hasStockInput && verifiedBaseline) {
            "Previous committed module could not be snapshotted and verified"
        }
        transactionStore.appendDiagnostic(
            request.packageName,
            "snapshot-${transactionId.take(8)}",
            "Replacing a module with an unusable backup; verified stock is the rollback baseline"
        )
        return MountModuleSnapshot(rebuildMountOnlyModule, useStockForRollback = true)
    }

    private suspend fun removeOldMountsForTransaction(
        packageName: String,
        targets: Set<String>,
        transactionId: String,
        includeCompeting: Boolean
    ) {
        // The target was force-stopped first, but restart-tolerant operations may continue if
        // Android immediately relaunches it. Ownership verification keeps lazy detach bounded to
        // UPM layers even while process or mount-namespace activity is still settling.
        val lazyUnmounts = mountVerifier.removeAllUrvMounts(
            packageName,
            targets,
            allowLazyRecovery = true,
            includeCompeting = includeCompeting
        )
        if (lazyUnmounts.isNotEmpty()) {
            transactionStore.appendDiagnostic(
                packageName,
                "unmount-${transactionId.take(8)}",
                "Unmount used ownership-verified lazy detach: ${lazyUnmounts.joinToString()}"
            )
        }
        mountVerifier.verifyTargetsClear(targets)
    }

    private suspend fun applyMountBeforeVerification(
        request: RootMountRequest,
        committed: RootCommittedState,
        restartTolerant: Boolean,
        transactionId: String
    ) {
        stopForMountMutation(
            request,
            restartTolerant,
            transactionId,
            "before applying the new mount"
        )
        requireExclusivePackageUser(request.packageName, request.userId)
        mountVerifier.verifyTargetsClear(setOf(committed.stockPath))
        mountVerifier.mountEverywhere(committed)
    }

    private suspend fun verifyMountedTargetAfterApplication(
        committed: RootCommittedState,
        transactionId: String
    ) {
        mountVerifier.verifyMounted(committed)
        if (finalizeMountedProcessState(committed)) {
            transactionStore.appendDiagnostic(
                committed.packageName,
                "restart-${transactionId.take(8)}",
                "Target app restarted after the root/Zygote mount was verified; its running process namespaces also see the patched APK"
            )
        }
    }

    private suspend fun snapshotStockForRollback(
        packageName: String,
        initial: RootPackageState
    ): List<RootBackupArtifact> {
        val rawPaths = listOfNotNull(initial.basePath) + initial.splitPaths.sorted()
        val backup = moduleStore.snapshotStock(packageName, rawPaths)
        val hashes = listOf(requireNotNull(initial.baseSha256)) + initial.splitPaths.sorted().map {
            requireNotNull(initial.splitSha256[it]) { "Previous split APK hash is unavailable" }
        }
        check(backup.map { it.sha256 } == hashes) {
            "Raw stock rollback snapshot hash mismatch"
        }
        return backup
    }

    private suspend fun resolveInterruptedJournal(
        request: RootMountRequest,
        interrupted: RootMountJournal,
        transactionId: String,
        storedCommitted: RootCommittedState?,
        validStoredCommitted: RootCommittedState?,
        committedFileExists: Boolean,
        previousCommitted: RootCommittedState?,
        recoveredModuleState: RootCommittedState?,
        corruptCommitted: Boolean,
        onPhase: (RootMountPhase) -> Unit
    ): InterruptedJournalResolution {
        if (interrupted.packageName != request.packageName || interrupted.userId < 0) {
            return InterruptedJournalResolution(
                result = recoverCorruptJournal(request, previousCommitted, transactionId),
                previousCommitted = previousCommitted,
                recoveredModuleState = recoveredModuleState,
                corruptCommitted = corruptCommitted
            )
        }
        if (interrupted.userId != request.userId) {
            val diagnosticId = "user-${transactionId.take(8)}"
            transactionStore.appendDiagnostic(
                request.packageName,
                diagnosticId,
                "Interrupted root mount belongs to Android user ${interrupted.userId}; " +
                    "request for user ${request.userId} cannot recover it"
            )
            return InterruptedJournalResolution(
                result = RootMountResult.Failure(
                    interrupted.phase,
                    RootRecoveryState.NONE,
                    diagnosticId,
                    "The unfinished root mount belongs to Android user ${interrupted.userId}, " +
                        "not the current user ${request.userId}"
                ),
                previousCommitted = previousCommitted,
                recoveredModuleState = recoveredModuleState,
                corruptCommitted = corruptCommitted
            )
        }

        val durableCompletion = recordedCompletion(
            interrupted,
            storedCommitted,
            validStoredCommitted,
            committedFileExists
        )
        if (durableCompletion != null) {
            val recordedCompletionState = durableCompletion.committedState
            val completionCleanup = cleanupDurableCompletion(
                request.packageName,
                transactionId,
                recordedCompletionState,
                finalizeMount = interrupted.finalizeMountOnCompletion && recordedCompletionState?.active == true
            )
            val cleanupFailure = completionCleanup.failure
            if (cleanupFailure != null) {
                val diagnosticId = "complete-${transactionId.take(8)}"
                runCatchingPreservingCancellation {
                    transactionStore.appendDiagnostic(
                        request.packageName,
                        diagnosticId,
                        "The completed root mount metadata is already durable, but its stale transaction " +
                            "journal could not be cleared; the recorded completed result was preserved and " +
                            "no rollback was attempted: " +
                            (cleanupFailure.message ?: cleanupFailure.javaClass.simpleName)
                    )
                }
                return InterruptedJournalResolution(
                    result = RootMountResult.Failure(
                        RootMountPhase.COMPLETED,
                        RootRecoveryState.NONE,
                        diagnosticId,
                        "Completed root transaction journal cleanup failed",
                        "The completed transaction result was preserved; only stale journal cleanup failed."
                    ),
                    previousCommitted = recordedCompletionState,
                    recoveredModuleState = if (recordedCompletionState == null) null else recoveredModuleState,
                    corruptCommitted = false
                )
            }
            if (!completionCleanup.snapshotCleaned) {
                return InterruptedJournalResolution(
                    result = RootMountResult.Failure(
                        RootMountPhase.COMPLETED,
                        RootRecoveryState.NONE,
                        "cleanup-${transactionId.take(8)}",
                        "Completed root transaction snapshot cleanup is pending",
                        "The completed transaction result was preserved and cleanup will be retried."
                    ),
                    previousCommitted = recordedCompletionState,
                    recoveredModuleState = if (recordedCompletionState == null) null else recoveredModuleState,
                    corruptCommitted = false
                )
            }
            runCatchingPreservingCancellation {
                transactionStore.appendDiagnostic(
                    request.packageName,
                    "complete-${transactionId.take(8)}",
                    "Cleared a stale completed transaction journal after proving its committed metadata was already durable"
                )
            }
            return InterruptedJournalResolution(
                previousCommitted = recordedCompletionState,
                recoveredModuleState = if (recordedCompletionState == null) null else recoveredModuleState,
                corruptCommitted = false
            )
        }

        val mutationStarted =
            RootMountPolicy.interruptedJournalMayHaveChangedStock(interrupted) ||
                RootMountPolicy.interruptedJournalMayHaveChangedModule(interrupted) ||
                RootMountPolicy.interruptedJournalMayHaveChangedMounts(interrupted) ||
                interrupted.moduleMutationStarted == true
        if (!mutationStarted) {
            transactionStore.clearActive(request.packageName)
            return InterruptedJournalResolution(
                previousCommitted = previousCommitted,
                recoveredModuleState = recoveredModuleState,
                corruptCommitted = corruptCommitted
            )
        }

        val recoveringJournal = interrupted.copy(
            transactionId = transactionId,
            phase = RootMountPhase.ROLLING_BACK,
            rollbackFromPhase = interrupted.rollbackFromPhase
                ?: interrupted.phase.takeUnless { it == RootMountPhase.ROLLING_BACK }
        )
        reportMandatoryRecoveryProgress(onPhase)
        val recovery = withContext(NonCancellable) {
            transactionStore.writeActive(recoveringJournal)
            rollback(
                journal = recoveringJournal,
                stockChanged = RootMountPolicy.interruptedJournalMayHaveChangedStock(interrupted),
                moduleChanged = RootMountPolicy.interruptedJournalMayHaveChangedModule(interrupted),
                stockBackup = null,
                diagnosticId = "recovery-${transactionId.take(8)}",
                reason = "Recovered interrupted transaction"
            )
        }
        return InterruptedJournalResolution(
            result = recovery,
            previousCommitted = previousCommitted,
            recoveredModuleState = recoveredModuleState,
            corruptCommitted = corruptCommitted
        )
    }

    private fun createJournal(
        transactionId: String,
        request: RootMountRequest,
        phase: RootMountPhase,
        initialPackageState: RootPackageState,
        patchedArtifact: RootArtifactState?,
        stockArtifact: RootArtifactState?,
        previousCommitted: RootCommittedState?,
        candidateMountTargets: List<String>,
        legacyMigration: Boolean,
        reusableCommittedModule: Boolean
    ) = RootMountJournal(
        transactionId = transactionId,
        packageName = request.packageName,
        userId = request.userId,
        operation = request.operation,
        phase = phase,
        startedAtEpochMs = System.currentTimeMillis(),
        initialPackageState = initialPackageState,
        patchedArtifact = patchedArtifact,
        stockArtifact = stockArtifact,
        previousCommitted = previousCommitted,
        candidateMountTargets = candidateMountTargets,
        reusableCommittedModule = reusableCommittedModule,
        moduleMutationStarted = false,
        moduleRestoreRequired = false,
        mountMutationStarted = false,
        status = if (legacyMigration) "LEGACY_MIGRATION" else null
    )

    private fun createCommittedState(
        transactionId: String,
        request: RootMountRequest,
        compatible: RootPackageState,
        patchedPath: String,
        patchedSha256: String
    ) = RootCommittedState(
        transactionId = transactionId,
        packageName = request.packageName,
        userId = request.userId,
        versionName = requireNotNull(compatible.versionName),
        versionCode = requireNotNull(compatible.versionCode),
        signerSha256 = compatible.signerSha256,
        stockPath = requireNotNull(compatible.basePath),
        stockSha256 = requireNotNull(compatible.baseSha256),
        patchedPath = patchedPath,
        patchedSha256 = patchedSha256,
        stockShadowPath = RootPaths.moduleStockApk(request.packageName),
        stockShadowSha256 = requireNotNull(compatible.baseSha256),
        preserveStockAcrossBoot = true,
        topology = compatible.topology,
        stockSplits = compatible.verifiedSplits(),
        enabled = compatible.enabled,
        launcherResolvable = compatible.launcherResolvable,
        committedAtEpochMs = System.currentTimeMillis()
    )

    private fun verifyStableStock(
        request: RootMountRequest,
        stableStock: RootPackageState,
        patchedArtifact: RootArtifactState?
    ) {
        check(stableStock.installed) { "Stock package is not installed for Android user ${request.userId}" }
        stableStock.verifiedSplits()
        check(stableStock.sharedUserId == null) { "Shared-UID process ownership cannot be isolated safely" }
        check(!stableStock.signerSha256.isNullOrBlank()) { "Installed stock signer is unavailable" }
        check(!stableStock.basePath.isNullOrBlank()) { "Installed stock base path is unavailable" }
        check(!stableStock.baseSha256.isNullOrBlank()) { "Installed stock hash is unavailable" }
        patchedArtifact?.let { artifact ->
            check(artifact.topology != "SPLIT" || stableStock.topology == "SPLIT") {
                "Split-dependent patched base has no installed split APKs"
            }
            check(stableStock.versionName == artifact.versionName) { "Patched and stock version names differ" }
            val expectedStockVersionCode = request.expectedStockVersionCode ?: artifact.versionCode
            check(stableStock.versionCode == expectedStockVersionCode) {
                "Installed stock version code does not match the patched APK source"
            }
            check(stableStock.baseSha256 != artifact.sha256) {
                "Patched payload is byte-identical to raw stock"
            }
        }
    }

    private fun preflight(
        request: RootMountRequest,
        initial: RootPackageState,
        patched: RootArtifactState?,
        stock: List<RootArtifactState>,
        committed: RootCommittedState?,
        legacyMigration: Boolean
    ) {
        if (request.operation in MOUNTING_OPERATIONS) {
            val useCommittedMount =
                request.operation == RootMountOperation.MOUNT_ONLY && committed != null
            val requestedPatched = patched.takeUnless { useCommittedMount }
            val requestedStock = stock.takeUnless { useCommittedMount }.orEmpty()
            if (request.operation == RootMountOperation.REPLACE_STOCK_AND_MOUNT) {
                require(requestedStock.size == 1) { "A complete stock APK is required for stock replacement" }
            }
            if (request.operation == RootMountOperation.SWITCH_PATCHED_BUILD) {
                require(initial.installed) { "A separately installed stock package is required for bundle switching" }
                require(requestedStock.size == 1 || committed != null || legacyMigration) {
                    "Exact stock compatibility cannot be proven without a verified stock APK or committed mount"
                }
                val switchPayload = requireNotNull(requestedPatched) {
                    "Patched APK is required for bundle switching"
                }
                val expectedStockVersionCode = request.expectedStockVersionCode
                    ?: switchPayload.versionCode
                require(
                    initial.versionName == switchPayload.versionName &&
                        initial.versionCode == expectedStockVersionCode,
                ) {
                    "Bundle switching cannot change the installed stock version"
                }
                requestedStock.singleOrNull()?.let { artifact ->
                    require(initial.versionName == artifact.versionName && initial.versionCode == artifact.versionCode) {
                        "Bundle switching cannot change the installed stock version"
                    }
                }
            }
            if (request.operation != RootMountOperation.MOUNT_ONLY || requestedPatched != null) {
                requireNotNull(requestedPatched) { "Patched APK is required" }
                require(requestedPatched.packageName == request.packageName) { "Patched APK package mismatch" }
                request.expectedVersionName?.let {
                    require(requestedPatched.versionName == it) { "Patched APK version name mismatch" }
                }
                request.expectedVersionCode?.let {
                    require(requestedPatched.versionCode == it) { "Patched APK version code mismatch" }
                }
            }
            if (request.operation == RootMountOperation.MOUNT_ONLY) {
                require(
                    committed != null || requestedPatched != null && requestedStock.size == 1
                ) {
                    "No committed root mount or complete saved payload is available"
                }
            }
            RootMountPolicy.validateSafeMount(
                request.packageName,
                initial,
                requestedPatched,
                requestedStock,
                request.expectedStockVersionCode ?: requestedPatched?.versionCode
            )
            requestedStock.singleOrNull()?.let { artifact ->
                (request.expectedStockVersionCode ?: request.expectedVersionCode)?.let {
                    require(artifact.versionCode == it) {
                        "Stock APK version code does not match the patched APK source"
                    }
                }
                request.expectedVersionName?.let { require(artifact.versionName == it) }
            }
        }
    }

    private suspend fun requireExclusivePackageUser(packageName: String, userId: Int) {
        val installedUserIds = packageStateReader.installedUserIds(packageName)
        val otherUserIds = installedUserIds - userId
        require(otherUserIds.isEmpty()) {
            "Safe root mounting requires the package to be installed only for Android user " +
                "$userId; it is also installed for ${otherUserIds.sorted().joinToString()}"
        }
    }

    private suspend fun forceStopPackageUsers(packageName: String, userId: Int) {
        val installedUserIds = packageStateReader.installedUserIds(packageName)
        if (packageStateReader.read(packageName, userId).installed) {
            check(userId in installedUserIds) {
                "Target package user ownership could not be verified"
            }
        }
        installedUserIds.sorted().forEach { installedUserId ->
            shell.runIsolatedBounded(
                "am force-stop --user $installedUserId ${shellQuote(packageName)}",
                FORCE_STOP_TIMEOUT_SECONDS,
                "root mount force-stop"
            ).requireSuccess("Stop target app for Android user $installedUserId")
        }
    }

    private suspend fun stopAndWait(packageName: String, userId: Int) {
        forceStopPackageUsers(packageName, userId)
        if (!packageStateReader.waitUntilStopped(packageName)) {
            throw RootTargetAppBusyException(
                "Target package processes did not exit; shared-UID ownership may be unsafe"
            )
        }
    }

    private suspend fun finalizeMountedProcessState(expected: RootCommittedState): Boolean {
        forceStopPackageUsers(expected.packageName, expected.userId)
        if (packageStateReader.waitUntilStopped(expected.packageName, POST_MOUNT_STOP_GRACE_MS)) {
            return false
        }
        return verifyStableRunningProcessState(expected.packageName) { pids ->
            mountVerifier.verifyProcessMounted(expected, pids)
        }
    }

    private suspend fun finalizeStockProcessState(stock: RootPackageState) {
        if (!stock.installed) return
        val stockPath = requireNotNull(stock.basePath) { "Recovered stock base path is unavailable" }
        check(stockPath.isSafeAbsoluteApkPath()) { "Recovered stock base path is unsafe" }
        forceStopPackageUsers(stock.packageName, stock.userId)
        if (packageStateReader.waitUntilStopped(stock.packageName, POST_MOUNT_STOP_GRACE_MS)) return
        verifyStableRunningProcessState(stock.packageName) { pids ->
            mountVerifier.verifyProcessStock(stock.packageName, stock.userId, stockPath, pids)
        }
    }

    private suspend fun verifyStableRunningProcessState(
        packageName: String,
        verify: suspend (List<Int>) -> Unit
    ): Boolean {
        var sawRunningProcess = false
        repeat(PROCESS_NAMESPACE_STABILITY_ATTEMPTS) {
            val before = packageStateReader.runningPids(packageName).distinct().sorted()
            if (before.isEmpty()) return sawRunningProcess
            sawRunningProcess = true
            verify(before)
            val after = packageStateReader.runningPids(packageName).distinct().sorted()
            if (after.isEmpty()) return true
            if (before == after) {
                // Re-check the stable PID set once more, then confirm the process list still
                // matches after that second namespace probe. Without the final read, a process
                // could be replaced during the second probe and the replacement would be unseen.
                verify(after)
                val confirmed = packageStateReader.runningPids(packageName).distinct().sorted()
                if (confirmed.isEmpty()) return true
                if (confirmed == after) return true
            }
            delay(PROCESS_NAMESPACE_STABILITY_DELAY_MS)
        }
        throw RootTargetAppBusyException(
            "Target package process set did not stabilize during namespace verification"
        )
    }

    private suspend fun waitForPackageManagerIdle() {
        shell.runIsolatedBounded(
            """
                set -eu
                package_help="${'$'}(cmd package help 2>/dev/null || true)"
                handler_supported=0
                background_supported=0
                if printf '%s\n' "${'$'}package_help" | grep -q 'wait-for-handler --timeout'; then
                  handler_supported=1
                  cmd package wait-for-handler --timeout 20000
                fi
                if printf '%s\n' "${'$'}package_help" | grep -q 'wait-for-background-handler --timeout'; then
                  background_supported=1
                  cmd package wait-for-background-handler --timeout 20000
                fi
                if [ "${'$'}handler_supported" = 1 ]; then
                  cmd package wait-for-handler --timeout 20000
                elif [ "${'$'}background_supported" = 0 ]; then
                  sleep 1
                fi
            """.trimIndent(),
            PACKAGE_MANAGER_IDLE_TIMEOUT_SECONDS,
            "post-install PackageManager idle barrier"
        ).requireSuccess("Wait for PackageManager post-install work")
    }

    private suspend fun completeTransaction(
        journal: RootMountJournal,
        committed: RootCommittedState?,
        finalizeMount: Boolean = false
    ) = withContext(NonCancellable) {
        transactionStore.complete(journal.copy(finalizeMountOnCompletion = finalizeMount), committed)
        if (
            cleanupCommittedModuleSnapshot(
                journal.packageName,
                journal.transactionId,
                preserveCancellation = false,
                finalizeMount = finalizeMount
            )
        ) {
            clearCompletedJournal(
                journal.packageName,
                journal.transactionId,
                preserveCancellation = false
            )
        }
    }

    private suspend fun cleanupCommittedModuleSnapshot(
        packageName: String,
        transactionId: String,
        preserveCancellation: Boolean,
        finalizeMount: Boolean = false
    ): Boolean {
        val cleanupFailure = runCleanupCatching(preserveCancellation) {
            if (finalizeMount) {
                moduleStore.commitSnapshot(packageName)
                runCleanupCatching(preserveCancellation) {
                    moduleStore.disableCompetingBootHooks(packageName)
                }.onFailure { failure ->
                    runCleanupCatching(preserveCancellation) {
                        transactionStore.appendDiagnostic(
                            packageName,
                            "cleanup-${transactionId.take(8)}",
                            "Mount committed, but competing ReVanced boot hooks could not be disabled: ${failure.message}"
                        )
                    }
                }
            }
            moduleStore.cleanupCommittedSnapshot(packageName)
        }.exceptionOrNull()
        if (cleanupFailure != null) {
            runCleanupCatching(preserveCancellation) {
                transactionStore.appendDiagnostic(
                    packageName,
                    "cleanup-${transactionId.take(8)}",
                    "Root transaction committed, but transient module snapshot cleanup failed: " +
                        (cleanupFailure.message ?: cleanupFailure.javaClass.simpleName)
                )
            }
            return false
        }
        return true
    }

    private suspend fun clearCompletedJournal(
        packageName: String,
        transactionId: String,
        preserveCancellation: Boolean
    ): Throwable? {
        val cleanupFailure = runCleanupCatching(preserveCancellation) {
            transactionStore.clearActive(packageName)
        }.exceptionOrNull()
        if (cleanupFailure != null) {
            runCleanupCatching(preserveCancellation) {
                transactionStore.appendDiagnostic(
                    packageName,
                    "complete-${transactionId.take(8)}",
                    "Root transaction committed, but its completed cleanup marker could not be cleared: " +
                        (cleanupFailure.message ?: cleanupFailure.javaClass.simpleName)
                )
            }
        }
        return cleanupFailure
    }

    private suspend fun cleanupDurableCompletion(
        packageName: String,
        transactionId: String,
        committedState: RootCommittedState?,
        finalizeMount: Boolean = false
    ): DurableCompletionCleanup {
        val committedStateCleanupFailure = runCatchingPreservingCancellation {
            if (committedState == null) transactionStore.clearCommitted(packageName)
        }.exceptionOrNull()
        val snapshotCleaned = cleanupCommittedModuleSnapshot(
            packageName,
            transactionId,
            preserveCancellation = true,
            finalizeMount = finalizeMount
        )
        val journalCleanupFailure = if (committedStateCleanupFailure == null && snapshotCleaned) {
            clearCompletedJournal(packageName, transactionId, preserveCancellation = true)
        } else {
            null
        }
        return DurableCompletionCleanup(
            snapshotCleaned = snapshotCleaned,
            failure = committedStateCleanupFailure ?: journalCleanupFailure
        )
    }

    private suspend fun <T> runCleanupCatching(
        preserveCancellation: Boolean,
        block: suspend () -> T
    ): Result<T> = if (preserveCancellation) {
        runCatchingPreservingCancellation(block)
    } else {
        runCatching { block() }
    }

    private suspend fun reconcile(
        request: RootMountRequest,
        committed: RootCommittedState,
        current: RootPackageState,
        transactionId: String,
        onPhase: (RootMountPhase) -> Unit,
        journal: RootMountJournal,
        onTransactionCompleted: () -> Unit
    ): RootMountResult {
        suspend fun completeReconciliation(
            completionJournal: RootMountJournal,
            state: RootCommittedState?
        ) {
            withContext(NonCancellable) {
                completeTransaction(completionJournal, state)
                onTransactionCompleted()
            }
        }

        val otherUserIds = packageStateReader.installedUserIds(request.packageName) - request.userId
        if (otherUserIds.isNotEmpty()) {
            val reason = "Root mounting is unsafe because the package is also installed for Android " +
                "users ${otherUserIds.sorted().joinToString()}"
            moduleStore.disable(request.packageName)
            transactionStore.markRepatchRequired(request.packageName, reason)
            completeReconciliation(
                journal.copy(phase = RootMountPhase.COMPLETED),
                committed.copy(active = false, status = "REPATCH_REQUIRED")
            )
            stopReconciliation(request.packageName, request.userId, transactionId)
            return RootMountResult.RequiresRepatch(reason)
        }
        when (RootMountPolicy.reconcile(committed, current)) {
            RootMountPolicy.ReconcileDecision.INACTIVE -> {
                moduleStore.disable(request.packageName)
                val inactive = committed.copy(active = false, status = "INACTIVE")
                completeReconciliation(journal.copy(phase = RootMountPhase.COMPLETED), inactive)
                stopReconciliation(request.packageName, request.userId, transactionId)
                return RootMountResult.Success(transactionId)
            }
            RootMountPolicy.ReconcileDecision.REPATCH_REQUIRED -> {
                moduleStore.disable(request.packageName)
                transactionStore.markRepatchRequired(
                    request.packageName,
                    "External package change is incompatible: " +
                        "stockPath=${current.basePath}, version=${current.versionName}/${current.versionCode}, " +
                        "stockSha256=${current.baseSha256} (expected ${committed.stockSha256}), " +
                        "signerSha256=${current.signerSha256} (expected ${committed.signerSha256}), " +
                        "topology=${current.topology}, enabled=${current.enabled}"
                )
                completeReconciliation(
                    journal.copy(phase = RootMountPhase.COMPLETED),
                    committed.copy(active = false, status = "REPATCH_REQUIRED")
                )
                stopReconciliation(request.packageName, request.userId, transactionId)
                return RootMountResult.RequiresRepatch(
                    "Installed stock no longer exactly matches the committed patched build"
                )
            }
            RootMountPolicy.ReconcileDecision.REPAIR_REQUIRED -> {
                moduleStore.disable(request.packageName)
                val diagnosticId = "repair-${transactionId.take(8)}"
                transactionStore.appendDiagnostic(
                    request.packageName,
                    diagnosticId,
                    "External reconciliation left an existing repair-required transaction unchanged"
                )
                completeReconciliation(journal.copy(phase = RootMountPhase.COMPLETED), committed)
                stopReconciliation(request.packageName, request.userId, transactionId)
                return RootMountResult.Failure(
                    RootMountPhase.ROLLING_BACK,
                    RootRecoveryState.NONE,
                    diagnosticId,
                    "Root mount repair is required before reconciliation"
                )
            }
            RootMountPolicy.ReconcileDecision.REMOUNT -> Unit
        }
        val currentStockPath = requireNotNull(current.basePath) {
            "Reconciled stock base path is unavailable"
        }
        check(currentStockPath.isSafeAbsoluteApkPath()) { "Reconciled stock base path is unsafe" }
        val stockPathChanged = currentStockPath != committed.stockPath
        val reconciledState = if (stockPathChanged) {
            committed.copy(
                transactionId = transactionId,
                stockPath = currentStockPath,
                active = true,
                status = "MOUNTED",
                committedAtEpochMs = System.currentTimeMillis()
            )
        } else {
            committed
        }
        reportProgress(onPhase, RootMountPhase.MOUNTING)
        val mountingJournal = journal.copy(
            phase = RootMountPhase.MOUNTING,
            expectedPackageState = current,
            moduleMutationStarted = journal.moduleMutationStarted == true || stockPathChanged,
            moduleRestoreRequired = false,
            mountMutationStarted = true,
            status = if (stockPathChanged) "MODULE_STATE_RETARGET_PENDING" else journal.status
        )
        transactionStore.writeActive(mountingJournal)
        if (stockPathChanged) moduleStore.updateState(reconciledState)
        try {
            stopAndWait(request.packageName, request.userId)
        } catch (_: RootTargetAppBusyException) {
            // The old mount has already been removed, so rolling back solely because the app
            // relaunched would turn temporary process activity into repair-required state.
            // Rebuild root/Zygote first, then force-stop once more and verify any restarted
            // target process against the patched mount before committing.
        }
        requireExclusivePackageUser(request.packageName, request.userId)
        moduleStore.enable(request.packageName)
        mountVerifier.verifyTargetsClear(setOf(reconciledState.stockPath))
        mountVerifier.mountEverywhere(reconciledState)
        reportProgress(onPhase, RootMountPhase.VERIFYING)
        transactionStore.writeActive(
            mountingJournal.copy(phase = RootMountPhase.VERIFYING)
        )
        mountVerifier.verifyMounted(reconciledState)
        val restartedAfterMount = finalizeMountedProcessState(reconciledState)
        if (restartedAfterMount) {
            transactionStore.appendDiagnostic(
                request.packageName,
                "restart-${transactionId.take(8)}",
                "Reconciliation verified the patched APK in the restarted target process namespaces"
            )
        }
        completeReconciliation(
            mountingJournal.copy(phase = RootMountPhase.COMPLETED),
            reconciledState.copy(active = true, status = "MOUNTED")
        )
        return RootMountResult.Success(transactionId, automaticallyRemounted = true)
    }

    private fun checkCommittedIdentity(committed: RootCommittedState, current: RootPackageState) {
        check(current.installed) { "Package is not installed" }
        check(current.packageName == committed.packageName) { "Package name mismatch" }
        check(current.userId == committed.userId) { "Android user mismatch" }
        check(current.versionName == committed.versionName) { "Version name mismatch" }
        check(current.versionCode == committed.versionCode) { "Version code mismatch" }
        check(current.signerSha256 == committed.signerSha256) {
            "Signing certificate mismatch: expected ${committed.signerSha256}, found ${current.signerSha256}"
        }
        check(current.baseSha256 == committed.stockSha256) {
            "Stock APK hash mismatch at ${current.basePath}: expected ${committed.stockSha256}, found ${current.baseSha256}"
        }
        check(current.topology == committed.topology) { "APK topology mismatch" }
        check(current.matchesSplits(committed.stockSplits)) { "Installed split APKs changed" }
        check(current.enabled == committed.enabled) { "Enabled state mismatch" }
    }

    private suspend fun rollback(
        journal: RootMountJournal,
        stockChanged: Boolean,
        moduleChanged: Boolean,
        stockBackup: List<RootBackupArtifact>?,
        diagnosticId: String,
        reason: String
    ): RootMountResult {
        val packageName = journal.packageName
        val rollbackFromPhase = journal.rollbackFromPhase
            ?: journal.phase.takeUnless { it == RootMountPhase.ROLLING_BACK }
        return try {
            transactionStore.writeActive(
                journal.copy(
                    phase = RootMountPhase.ROLLING_BACK,
                    rollbackFromPhase = rollbackFromPhase,
                    mountMutationStarted = true,
                    diagnosticId = diagnosticId
                )
            )
            transactionStore.appendDiagnostic(
                packageName,
                diagnosticId,
                "Rollback context: fromPhase=${rollbackFromPhase ?: "unknown"}, " +
                    "stockMutationStarted=${journal.stockMutationStarted}, " +
                    "registrationGap=${journal.registrationGap}, " +
                    "moduleMutationStarted=${journal.moduleMutationStarted ?: "legacy-unknown"}, " +
                    "moduleRestoreRequired=${journal.moduleRestoreRequired ?: "legacy-unknown"}, " +
                    "mountMutationStarted=${journal.mountMutationStarted ?: "legacy-unknown"}"
            )
            try {
                stopAndWait(packageName, journal.userId)
            } catch (_: RootTargetAppBusyException) {
                transactionStore.appendDiagnostic(
                    packageName,
                    diagnosticId,
                    "Target app restarted during rollback; continuing with ownership-verified mount cleanup"
                )
            }
            val targets = setOfNotNull(
                journal.initialPackageState?.basePath,
                journal.expectedPackageState?.basePath,
                journal.previousCommitted?.stockPath
            ) + journal.candidateMountTargets
            val lazyUnmounts = mountVerifier.removeAllUrvMounts(
                packageName,
                targets,
                allowLazyRecovery = true
            )
            if (lazyUnmounts.isNotEmpty()) {
                transactionStore.appendDiagnostic(
                    packageName,
                    diagnosticId,
                    "Recovery used ownership-verified lazy unmount: ${lazyUnmounts.joinToString()}"
                )
            }
            mountVerifier.verifyTargetsClear(targets)

            val initial = journal.initialPackageState
            var retainedExternalUpdate = false
            if (stockChanged && initial?.installed == true) {
                val beforeRestore = packageStateReader.read(packageName, journal.userId)
                retainedExternalUpdate = isVerifiedExternalStockUpdate(journal, beforeRestore)
                if (!retainedExternalUpdate) {
                    if (initial.systemApp && !beforeRestore.installed) {
                        check(packageInstaller.restoreSystemRegistration(packageName, journal.userId)) {
                            "Failed to restore system package registration"
                        }
                    }
                    val hashes = listOf(requireNotNull(initial.baseSha256)) + initial.splitPaths.sorted().map {
                        requireNotNull(initial.splitSha256[it]) { "Previous split APK hash is unavailable" }
                    }
                    val backup = stockBackup ?: hashes.mapIndexed { index, hash ->
                        RootBackupArtifact("${RootPaths.backup(packageName)}/package/$index.apk", hash)
                    }
                    check(backup.map { it.sha256 } == hashes) { "Rollback APK set does not match the journal" }
                    packageInstaller.replaceRootBackups(backup, journal.userId).getOrThrow()
                    // PackageInstaller may allocate a new directory for the restored APK set.
                    val restored = packageStateReader.read(packageName, journal.userId)
                    check(restored.matchesSplits(initial.verifiedSplits())) { "Restored split APK set mismatch" }
                    packageStateReader.waitForStable(initial.copy(
                        installed = true,
                        splitPaths = restored.splitPaths,
                        splitSha256 = restored.splitSha256
                    ))
                } else {
                    transactionStore.appendDiagnostic(
                        packageName,
                        diagnosticId,
                        "A newer same-signer stock update was detected during recovery; " +
                            "the update was retained and the saved patch was marked for repatching"
                    )
                }
            }

            val restoredState = packageStateReader.read(packageName, journal.userId)
            val previous = journal.previousCommitted
            if (moduleChanged) {
                val restoredModule = moduleStore.restorePrevious(packageName)
                if (previous != null) check(restoredModule) { "Previous root module could not be restored" }
            }
            val previousMountUserSafe = runCatchingPreservingCancellation {
                packageStateReader.installedUserIds(packageName) == setOf(journal.userId)
            }.getOrDefault(false)
            if (previous != null && previous.active && previousMountUserSafe &&
                runCatching { checkCommittedIdentity(previous, restoredState) }.isSuccess &&
                restoredState.baseSha256 == previous.stockSha256 &&
                restoredState.basePath?.isSafeAbsoluteApkPath() == true
            ) {
                val restoredPrevious = previous.copy(
                    transactionId = if (restoredState.basePath != previous.stockPath) {
                        journal.transactionId
                    } else {
                        previous.transactionId
                    },
                    stockPath = requireNotNull(restoredState.basePath),
                    committedAtEpochMs = System.currentTimeMillis()
                )
                if (restoredPrevious.stockPath != previous.stockPath) {
                    moduleStore.updateState(restoredPrevious)
                }
                try {
                    stopAndWait(packageName, journal.userId)
                } catch (_: RootTargetAppBusyException) {
                    // The previous payload will be re-established in root/Zygote first, then
                    // any restarted target process is verified after one more force-stop.
                }
                requireExclusivePackageUser(packageName, journal.userId)
                moduleStore.enable(packageName)
                mountVerifier.verifyTargetsClear(setOf(restoredPrevious.stockPath))
                mountVerifier.mountEverywhere(restoredPrevious)
                mountVerifier.verifyMounted(restoredPrevious)
                finalizeMountedProcessState(restoredPrevious)
                completeTransaction(journal, restoredPrevious.copy(status = "MOUNTED"))
                RootMountResult.RecoveredToPreviousMount(journal.transactionId, diagnosticId, reason)
            } else {
                moduleStore.disable(packageName)
                val externalStockUpdate = retainedExternalUpdate ||
                    isVerifiedExternalStockUpdate(journal, restoredState)
                if (isVerifiedStockRecovery(journal, restoredState) || externalStockUpdate) {
                    finalizeStockProcessState(restoredState)
                    val stockState = inactiveCommittedForVerifiedStock(
                        previous,
                        restoredState,
                        repatchRequired = externalStockUpdate
                    )
                    if (stockState == null) transactionStore.clearCommitted(packageName)
                    completeTransaction(journal, stockState)
                    stopReconciliation(packageName, journal.userId, diagnosticId)
                    RootMountResult.RecoveredToStock(journal.transactionId, diagnosticId, reason)
                } else {
                    transactionStore.writeActive(
                        journal.copy(
                            phase = RootMountPhase.ROLLING_BACK,
                            rollbackFromPhase = rollbackFromPhase,
                            diagnosticId = diagnosticId,
                            status = "REPAIR_REQUIRED"
                        )
                    )
                    previous?.let {
                        transactionStore.writeCommitted(it.copy(active = false, status = "REPAIR_REQUIRED"))
                    }
                    RootMountResult.Failure(
                        RootMountPhase.ROLLING_BACK,
                        RootRecoveryState.NONE,
                        diagnosticId,
                        "$reason; stock recovery could not be proven"
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rollbackFailure: Throwable) {
            val durableCompletedRecovery = runCatchingPreservingCancellation {
                transactionStore.readActive(packageName)?.let { active ->
                    readDurableCompletion(active)
                }
            }.getOrNull()
            if (durableCompletedRecovery != null) {
                cleanupDurableCompletion(
                    packageName,
                    journal.transactionId,
                    durableCompletedRecovery.committedState
                ).failure?.let { cleanupFailure ->
                    runCatchingPreservingCancellation {
                        transactionStore.appendDiagnostic(
                            packageName,
                            diagnosticId,
                            "Recovery result was already durable, so no fallback was attempted; stale completed " +
                                "journal cleanup failed: " +
                                (cleanupFailure.message ?: cleanupFailure.javaClass.simpleName)
                        )
                    }
                }
                return if (durableCompletedRecovery.committedState?.active == true) {
                    RootMountResult.RecoveredToPreviousMount(
                        journal.transactionId,
                        diagnosticId,
                        "$reason; previous mount recovery was already committed"
                    )
                } else {
                    RootMountResult.RecoveredToStock(
                        journal.transactionId,
                        diagnosticId,
                        "$reason; stock recovery was already committed"
                    )
                }
            }
            runCatchingPreservingCancellation {
                transactionStore.appendDiagnostic(
                    packageName,
                    diagnosticId,
                    "Rollback failed: ${rollbackFailure.stackTraceToString()}"
                )
            }
            var stockFallback = runCatchingPreservingCancellation {
                try {
                    stopAndWait(packageName, journal.userId)
                } catch (_: RootTargetAppBusyException) {
                    transactionStore.appendDiagnostic(
                        packageName,
                        diagnosticId,
                        "Target app restarted during stock fallback; continuing with ownership-verified cleanup"
                    )
                }
                val targets = setOfNotNull(
                    journal.initialPackageState?.basePath,
                    journal.expectedPackageState?.basePath,
                    journal.previousCommitted?.stockPath
                ) + journal.candidateMountTargets
                val lazyUnmounts = mountVerifier.removeAllUrvMounts(
                    packageName,
                    targets,
                    allowLazyRecovery = true
                )
                if (lazyUnmounts.isNotEmpty()) {
                    transactionStore.appendDiagnostic(
                        packageName,
                        diagnosticId,
                        "Stock fallback used ownership-verified lazy unmount: ${lazyUnmounts.joinToString()}"
                    )
                }
                mountVerifier.verifyTargetsClear(targets)
                moduleStore.disable(packageName)
                val stock = packageStateReader.read(packageName, journal.userId)
                val externalStockUpdate = isVerifiedExternalStockUpdate(journal, stock)
                check(isVerifiedStockRecovery(journal, stock) || externalStockUpdate) {
                    "Stock fallback state could not be verified"
                }
                finalizeStockProcessState(stock)
                val inactive = inactiveCommittedForVerifiedStock(
                    journal.previousCommitted,
                    stock,
                    repatchRequired = externalStockUpdate
                )
                if (inactive == null) transactionStore.clearCommitted(packageName)
                completeTransaction(journal, inactive)
                stopReconciliation(packageName, journal.userId, diagnosticId)
            }.isSuccess
            if (!stockFallback) {
                val durableStockFallback = runCatchingPreservingCancellation {
                    transactionStore.readActive(packageName)?.let { active ->
                        readDurableCompletion(active)?.takeIf {
                            it.committedState?.active != true
                        }
                    }
                }.getOrNull()
                if (durableStockFallback != null) {
                    cleanupDurableCompletion(
                        packageName,
                        journal.transactionId,
                        durableStockFallback.committedState
                    ).failure?.let { cleanupFailure ->
                        runCatchingPreservingCancellation {
                            transactionStore.appendDiagnostic(
                                packageName,
                                diagnosticId,
                                "Verified stock fallback was already durable; stale completed journal cleanup failed: " +
                                    (cleanupFailure.message ?: cleanupFailure.javaClass.simpleName)
                            )
                        }
                    }
                    stockFallback = true
                }
            }
            if (stockFallback) {
                RootMountResult.RecoveredToStock(
                    journal.transactionId,
                    diagnosticId,
                    "$reason; previous mount restoration failed and verified stock was retained"
                )
            } else {
                runCatchingPreservingCancellation { moduleStore.disable(packageName) }
                journal.previousCommitted?.let { previous ->
                    runCatchingPreservingCancellation {
                        transactionStore.writeCommitted(previous.copy(active = false, status = "REPAIR_REQUIRED"))
                    }
                }
                RootMountResult.Failure(
                    RootMountPhase.ROLLING_BACK,
                    RootRecoveryState.NONE,
                    diagnosticId,
                    "$reason; automatic recovery failed: ${rollbackFailure.message}"
                )
            }
        }
    }

    private suspend fun recoverCorruptJournal(
        request: RootMountRequest,
        previousCommitted: RootCommittedState?,
        transactionId: String
    ): RootMountResult = withContext(NonCancellable) {
        recoverCorruptJournalMandatory(request, previousCommitted, transactionId)
    }

    private suspend fun recoverCorruptJournalMandatory(
        request: RootMountRequest,
        previousCommitted: RootCommittedState?,
        transactionId: String
    ): RootMountResult {
        val diagnosticId = "corrupt-${transactionId.take(8)}"
        val current = packageStateReader.read(request.packageName, request.userId)
        val stopped = runCatching {
            stopAndWait(request.packageName, request.userId)
            true
        }.getOrDefault(false)
        val mountsRemoved = stopped && runCatching {
            val targets = setOfNotNull(current.basePath, previousCommitted?.stockPath)
            val lazyUnmounts = mountVerifier.removeAllUrvMounts(
                request.packageName,
                targets,
                allowLazyRecovery = true
            )
            if (lazyUnmounts.isNotEmpty()) {
                transactionStore.appendDiagnostic(
                    request.packageName,
                    diagnosticId,
                    "Corrupt-journal recovery used lazy unmount: ${lazyUnmounts.joinToString()}"
                )
            }
            mountVerifier.verifyTargetsClear(targets)
        }.isSuccess
        val moduleDisabled = runCatching { moduleStore.disable(request.packageName) }.isSuccess

        var restored = packageStateReader.read(request.packageName, request.userId)
        var externalStockUpdate = previousCommitted?.let {
            isVerifiedExternalStockUpdate(it, restored)
        } == true
        if (stopped && mountsRemoved && previousCommitted != null &&
            !matchesCommittedStock(previousCommitted, restored) && !externalStockUpdate
        ) {
            val backupHashes = listOf(previousCommitted.stockSha256) +
                previousCommitted.stockSplits.toSortedMap().values
            val restoredBackup = packageInstaller.replaceRootBackups(
                backupHashes.mapIndexed { index, hash ->
                    RootBackupArtifact("${RootPaths.backup(request.packageName)}/package/$index.apk", hash)
                },
                request.userId
            )
            if (restoredBackup.isSuccess) {
                val observedAfterRestore = packageStateReader.read(request.packageName, request.userId)
                check(observedAfterRestore.matchesSplits(previousCommitted.stockSplits)) {
                    "Restored split APK set does not match the committed stock package"
                }
                restored = packageStateReader.waitForStable(
                    observedAfterRestore.copy(
                        installed = true,
                        versionName = previousCommitted.versionName,
                        versionCode = previousCommitted.versionCode,
                        signerSha256 = previousCommitted.signerSha256,
                        baseSha256 = previousCommitted.stockSha256
                    )
                )
            }
        }
        externalStockUpdate = previousCommitted?.let {
            isVerifiedExternalStockUpdate(it, restored)
        } == true
        val stockProven = previousCommitted?.let {
            matchesCommittedStock(it, restored) || externalStockUpdate
        }
            ?: isStructurallyVerifiedStock(restored)
        val processStateProven = if (stopped && mountsRemoved && moduleDisabled && stockProven) {
            runCatchingPreservingCancellation {
                finalizeStockProcessState(restored)
            }.isSuccess
        } else {
            false
        }
        val recoveryProven =
            stopped && mountsRemoved && moduleDisabled && stockProven && processStateProven
        val inactive = previousCommitted?.copy(
            active = false,
            status = when {
                !recoveryProven -> "REPAIR_REQUIRED"
                externalStockUpdate -> "REPATCH_REQUIRED"
                else -> "STOCK"
            }
        )
        if (inactive != null) transactionStore.writeCommitted(inactive)
        else transactionStore.clearCommitted(request.packageName)
        transactionStore.appendDiagnostic(
            request.packageName,
            diagnosticId,
            "The active journal was unreadable. Mounts were removed, the module was disabled, and backups were preserved."
        )
        if (recoveryProven) transactionStore.clearActive(request.packageName)
        if (recoveryProven) {
            stopReconciliation(request.packageName, request.userId, diagnosticId)
        }
        return if (recoveryProven) {
            RootMountResult.RecoveredToStock(
                transactionId,
                diagnosticId,
                "Unreadable transaction journal was recovered conservatively"
            )
        } else {
            RootMountResult.Failure(
                RootMountPhase.ROLLING_BACK,
                RootRecoveryState.NONE,
                diagnosticId,
                "Unreadable journal recovery could not prove an installed stock package; use Repair root mount"
            )
        }
    }

    private suspend fun recoverCorruptCommittedState(
        packageName: String,
        userId: Int,
        transactionId: String,
        reason: String
    ): RootMountResult = withContext(NonCancellable) {
        recoverCorruptCommittedStateMandatory(packageName, userId, transactionId, reason)
    }

    private suspend fun recoverCorruptCommittedStateMandatory(
        packageName: String,
        userId: Int,
        transactionId: String,
        reason: String
    ): RootMountResult {
        val diagnosticId = "committed-${transactionId.take(8)}"
        val initial = packageStateReader.read(packageName, userId)
        val stopped = runCatching {
            stopAndWait(packageName, userId)
            true
        }.getOrDefault(false)
        val mountsRemoved = stopped && runCatching {
            val discovered = mountVerifier.findUrvMounts(
                packageName,
                setOfNotNull(initial.basePath)
            )
            val targets = setOfNotNull(initial.basePath) + discovered.map(MountInfoEntry::mountPoint)
            val lazyUnmounts = mountVerifier.removeAllUrvMounts(
                packageName,
                targets,
                allowLazyRecovery = true
            )
            if (lazyUnmounts.isNotEmpty()) {
                transactionStore.appendDiagnostic(
                    packageName,
                    diagnosticId,
                    "Corrupt committed-state recovery used lazy unmount: ${lazyUnmounts.joinToString()}"
                )
            }
            mountVerifier.verifyTargetsClear(targets)
        }.isSuccess
        val moduleDisabled = runCatching { moduleStore.disable(packageName) }.isSuccess
        val restored = packageStateReader.read(packageName, userId)
        val stockProven = !restored.installed || isStructurallyVerifiedStock(restored)
        val processStateProven = if (stopped && mountsRemoved && moduleDisabled && stockProven) {
            runCatchingPreservingCancellation {
                finalizeStockProcessState(restored)
            }.isSuccess
        } else {
            false
        }
        val recoveryProven =
            stopped && mountsRemoved && moduleDisabled && stockProven && processStateProven
        transactionStore.appendDiagnostic(
            packageName,
            diagnosticId,
            if (recoveryProven) {
                "$reason; UPM mounts were removed and the module was disabled"
            } else {
                "$reason; automatic stock recovery could not be proven"
            }
        )
        if (!recoveryProven) {
            return RootMountResult.Failure(
                RootMountPhase.ROLLING_BACK,
                RootRecoveryState.NONE,
                diagnosticId,
                "$reason; use Repair root mount"
            )
        }
        transactionStore.clearCommitted(packageName)
        stopReconciliation(packageName, userId, diagnosticId)
        return if (restored.installed) {
            RootMountResult.RecoveredToStock(
                transactionId,
                diagnosticId,
                "$reason; verified stock was retained"
            )
        } else {
            RootMountResult.Success(transactionId)
        }
    }

    private fun packageRegistrationChanged(before: RootPackageState, after: RootPackageState): Boolean =
        before.installed != after.installed ||
            before.versionName != after.versionName ||
            before.versionCode != after.versionCode ||
            before.signerSha256 != after.signerSha256 ||
            before.basePath != after.basePath ||
            before.splitPaths != after.splitPaths ||
            before.splitSha256 != after.splitSha256 ||
            before.baseSha256 != after.baseSha256 ||
            before.enabled != after.enabled

    private fun matchesRequestedStock(state: RootPackageState, artifact: RootArtifactState): Boolean =
        state.installed &&
            state.packageName == artifact.packageName &&
            state.versionName == artifact.versionName &&
            state.versionCode == artifact.versionCode &&
            state.signerSha256 == artifact.signerSha256 &&
            state.matchesRequestedSplits(artifact) &&
            !state.basePath.isNullOrBlank() &&
            state.baseSha256 == artifact.sha256

    private fun isVerifiedStockRecovery(journal: RootMountJournal, restored: RootPackageState): Boolean {
        if (restored.packageName != journal.packageName || restored.userId != journal.userId) {
            return false
        }
        val initial = journal.initialPackageState
        if (initial?.installed == false && !restored.installed) {
            return initial.packageName == journal.packageName && initial.userId == journal.userId
        }
        if (!restored.installed) {
            return false
        }
        if (initial?.installed == true) {
            if (journal.status == "LEGACY_MIGRATION") {
                return restored.versionName == initial.versionName &&
                    restored.versionCode == initial.versionCode &&
                    restored.signerSha256 == initial.signerSha256 &&
                    restored.basePath == initial.basePath &&
                    restored.topology == initial.topology &&
                    restored.matchesSplits(initial.verifiedSplits()) &&
                    restored.enabled == initial.enabled &&
                    journal.patchedArtifact?.let { patched ->
                        restored.versionName == patched.versionName &&
                            restored.versionCode == patched.versionCode
                    } == true
            }
            val matchesInitial = restored.versionName == initial.versionName &&
                restored.versionCode == initial.versionCode &&
                restored.signerSha256 == initial.signerSha256 &&
                restored.baseSha256 == initial.baseSha256 &&
                restored.topology == initial.topology &&
                restored.matchesSplits(initial.verifiedSplits()) &&
                restored.enabled == initial.enabled
            if (matchesInitial) return true
            return journal.previousCommitted?.let { matchesCommittedStock(it, restored) } == true
        }
        val requested = journal.stockArtifact ?: return false
        return restored.versionName == requested.versionName &&
            restored.versionCode == requested.versionCode &&
            restored.signerSha256 == requested.signerSha256 &&
            restored.baseSha256 == requested.sha256 &&
            restored.topology == requested.topology
    }

    private fun matchesCommittedStock(committed: RootCommittedState, state: RootPackageState): Boolean =
        state.installed &&
            state.packageName == committed.packageName &&
            state.userId == committed.userId &&
            state.versionName == committed.versionName &&
            state.versionCode == committed.versionCode &&
            state.signerSha256 == committed.signerSha256 &&
            state.baseSha256 == committed.stockSha256 &&
            state.topology == committed.topology &&
            state.matchesSplits(committed.stockSplits) &&
            state.enabled == committed.enabled

    private fun matchesReusableModule(
        committed: RootCommittedState,
        module: RootCommittedState
    ): Boolean =
        module.packageName == committed.packageName &&
            module.userId == committed.userId &&
            module.versionName == committed.versionName &&
            module.versionCode == committed.versionCode &&
            module.signerSha256 == committed.signerSha256 &&
            module.stockPath == committed.stockPath &&
            module.stockSha256 == committed.stockSha256 &&
            module.active == committed.active &&
            module.patchedPath == committed.patchedPath &&
            module.patchedSha256 == committed.patchedSha256 &&
            module.stockShadowPath == committed.stockShadowPath &&
            module.stockShadowSha256 == committed.stockShadowSha256 &&
            module.preserveStockAcrossBoot == committed.preserveStockAcrossBoot &&
            module.topology == committed.topology &&
            module.stockSplits == committed.stockSplits &&
            module.enabled == committed.enabled &&
            module.launcherResolvable == committed.launcherResolvable

    private fun isVerifiedExternalStockUpdate(
        journal: RootMountJournal,
        state: RootPackageState
    ): Boolean {
        val requested = journal.stockArtifact
        if (requested != null && matchesRequestedStock(state, requested)) return false
        val currentVersionCode = state.versionCode
        if (journal.stockMutationStarted && requested != null &&
            (currentVersionCode == null || currentVersionCode == requested.versionCode)
        ) {
            // If this transaction already started replacing stock, a different APK at
            // the same requested version is ambiguous rather than an external update.
            return false
        }
        if (journal.stockMutationStarted && requested == null) return false
        val initial = journal.initialPackageState?.takeIf { it.installed }
        if (initial != null && isVerifiedExternalStockUpdate(initial, state)) return true
        return journal.previousCommitted?.let { isVerifiedExternalStockUpdate(it, state) } == true
    }

    private fun isVerifiedExternalStockUpdate(
        baseline: RootPackageState,
        state: RootPackageState
    ): Boolean =
        isStructurallyVerifiedExternalStock(state) &&
            state.packageName == baseline.packageName &&
            state.userId == baseline.userId &&
            state.signerSha256 == baseline.signerSha256 &&
            baseline.versionCode != null &&
            requireNotNull(state.versionCode) > baseline.versionCode

    private fun isVerifiedExternalStockUpdate(
        baseline: RootCommittedState,
        state: RootPackageState
    ): Boolean =
        isStructurallyVerifiedExternalStock(state) &&
            state.packageName == baseline.packageName &&
            state.userId == baseline.userId &&
            state.signerSha256 == baseline.signerSha256 &&
            requireNotNull(state.versionCode) > baseline.versionCode

    private suspend fun inactiveCommittedForVerifiedStock(
        previous: RootCommittedState?,
        restored: RootPackageState,
        repatchRequired: Boolean = false
    ): RootCommittedState? {
        previous ?: return null
        val inactive = if (repatchRequired) {
            previous.copy(
                active = false,
                status = "REPATCH_REQUIRED",
                committedAtEpochMs = System.currentTimeMillis()
            )
        } else if (matchesCommittedStock(previous, restored)) {
            previous.copy(
                stockPath = requireNotNull(restored.basePath) {
                    "Verified recovered stock base path is unavailable"
                },
                active = false,
                status = "STOCK",
                committedAtEpochMs = System.currentTimeMillis()
            )
        } else {
            previous.copy(
                active = false,
                status = "STOCK",
                committedAtEpochMs = System.currentTimeMillis()
            )
        }
        if (!repatchRequired && inactive.stockPath != previous.stockPath) {
            moduleStore.updateState(inactive)
        }
        return inactive
    }

    private fun isStructurallyVerifiedStock(state: RootPackageState): Boolean =
        state.installed &&
            runCatching { state.verifiedSplits() }.isSuccess &&
            state.sharedUserId == null &&
            !state.versionName.isNullOrBlank() &&
            state.versionCode != null &&
            !state.signerSha256.isNullOrBlank() &&
            state.basePath?.isSafeAbsoluteApkPath() == true &&
            !state.baseSha256.isNullOrBlank()

    private fun isStructurallyVerifiedExternalStock(state: RootPackageState): Boolean =
        state.installed &&
            state.sharedUserId == null &&
            !state.versionName.isNullOrBlank() &&
            state.versionCode != null &&
            !state.signerSha256.isNullOrBlank() &&
            state.basePath?.isSafeAbsoluteApkPath() == true &&
            state.splitPaths.all { it.isSafeAbsoluteApkPath() } &&
            runCatching { state.verifiedSplits() }.isSuccess &&
            !state.baseSha256.isNullOrBlank()

    private fun changedStockRequiresRepatch(diagnosticId: String? = null) =
        RootMountResult.RequiresRepatch(
            reason = "The stock app changed after this root mount was created. " +
                "Patch the currently installed APK set before mounting again.",
            diagnosticId = diagnosticId
        )

    private fun recordedCompletion(
        journal: RootMountJournal,
        storedCommitted: RootCommittedState?,
        validStoredCommitted: RootCommittedState?,
        committedFileExists: Boolean
    ): DurableCompletion? {
        if (journal.phase != RootMountPhase.COMPLETED) return null
        if (journal.completionStateRecorded == true) {
            return when (val recorded = journal.completionCommittedState) {
                null -> DurableCompletion(null).takeIf {
                    storedCommitted == null && !committedFileExists
                }
                else -> DurableCompletion(recorded).takeIf {
                    validStoredCommitted == recorded
                }
            }
        }
        if (journal.completionStateRecorded != null) return null

        // Journals written before completionStateRecorded existed persisted COMPLETED before
        // writing the final committed state. Prove the effective final state from durable metadata
        // instead of assuming a missing legacy field means the committed result was null.
        if (validStoredCommitted?.transactionId == journal.transactionId) {
            return DurableCompletion(validStoredCommitted)
        }
        val previous = journal.previousCommitted
        if (
            previous?.active == true &&
            validStoredCommitted != null &&
            !validStoredCommitted.active &&
            validStoredCommitted.status in setOf("STOCK", "INACTIVE", "REPATCH_REQUIRED") &&
            sameCommittedPayloadIdentity(previous, validStoredCommitted)
        ) {
            return DurableCompletion(validStoredCommitted)
        }
        if (
            previous?.active == false &&
            validStoredCommitted?.active == true &&
            validStoredCommitted.status == "MOUNTED" &&
            sameCommittedPayloadIdentity(previous, validStoredCommitted)
        ) {
            return DurableCompletion(validStoredCommitted)
        }
        if (
            journal.operation == RootMountOperation.MOUNT_ONLY &&
            previous?.active == true &&
            validStoredCommitted?.active == true &&
            validStoredCommitted.stockPath == previous.stockPath &&
            sameCommittedPayloadIdentity(previous, validStoredCommitted)
        ) {
            return DurableCompletion(validStoredCommitted)
        }
        if (
            journal.operation == RootMountOperation.UNMOUNT &&
            journal.status == "MODULE_REMOVED" &&
            storedCommitted == null &&
            !committedFileExists
        ) {
            return DurableCompletion(null)
        }
        return null
    }

    private fun sameCommittedPayloadIdentity(
        expected: RootCommittedState,
        actual: RootCommittedState
    ): Boolean =
        actual.transactionId == expected.transactionId &&
            actual.packageName == expected.packageName &&
            actual.userId == expected.userId &&
            actual.versionName == expected.versionName &&
            actual.versionCode == expected.versionCode &&
            actual.signerSha256 == expected.signerSha256 &&
            actual.stockSha256 == expected.stockSha256 &&
            actual.patchedPath == expected.patchedPath &&
            actual.patchedSha256 == expected.patchedSha256 &&
            actual.stockShadowPath == expected.stockShadowPath &&
            actual.stockShadowSha256 == expected.stockShadowSha256 &&
            actual.preserveStockAcrossBoot == expected.preserveStockAcrossBoot &&
            actual.topology == expected.topology &&
            actual.stockSplits == expected.stockSplits &&
            actual.enabled == expected.enabled &&
            actual.launcherResolvable == expected.launcherResolvable

    private suspend fun readDurableCompletion(journal: RootMountJournal): DurableCompletion? {
        val storedCommitted = transactionStore.readCommitted(journal.packageName)
        val committedFileExists = transactionStore.committedExists(journal.packageName)
        val validStoredCommitted = storedCommitted?.takeIf {
            isValidCommittedState(journal.packageName, it)
        }
        return recordedCompletion(
            journal,
            storedCommitted,
            validStoredCommitted,
            committedFileExists
        )
    }

    private suspend fun preserveDurableCompletionOnCancellation(
        request: RootMountRequest,
        activeJournal: RootMountJournal,
        transactionId: String
    ): Boolean = withContext(NonCancellable) {
        if (runCatching { readDurableCompletion(activeJournal) }.getOrNull() == null) {
            return@withContext false
        }
        runCatching {
            transactionStore.appendDiagnostic(
                request.packageName,
                "cancel-${transactionId.take(8)}",
                "Cancellation occurred after the completed transaction result was already durable; " +
                    "no rollback was attempted. The completed journal was retained so cleanup can be retried."
            )
        }
        true
    }

    private suspend fun durableCompletionFailureResult(
        request: RootMountRequest,
        activeJournal: RootMountJournal?,
        diagnosticId: String,
        failureMessage: String
    ): RootMountResult.Failure? {
        activeJournal ?: return null
        if (runCatchingPreservingCancellation {
            readDurableCompletion(activeJournal)
        }.getOrNull() == null) return null
        runCatchingPreservingCancellation {
            transactionStore.appendDiagnostic(
                request.packageName,
                diagnosticId,
                "The transaction result was already durably completed before this failure, so no rollback " +
                    "was attempted. The completed journal was retained so cleanup can be retried. " +
                    "Original failure: $failureMessage"
            )
        }
        return RootMountResult.Failure(
            RootMountPhase.COMPLETED,
            RootRecoveryState.NONE,
            diagnosticId,
            failureMessage,
            "The completed transaction result was preserved; no rollback was performed."
        )
    }

    private fun isValidCommittedState(packageName: String, state: RootCommittedState): Boolean {
        val safeStockPath = state.stockPath.isSafeAbsoluteApkPath()
        val expectedPatchedPath = RootPaths.moduleApk(packageName)
        val validStockShadow = !state.preserveStockAcrossBoot ||
            (state.stockShadowPath == RootPaths.moduleStockApk(packageName) &&
                state.stockShadowSha256 == state.stockSha256)
        val versionName = state.versionName
        val validStatus = if (state.active) {
            state.status in ACTIVE_COMMITTED_STATUSES
        } else {
            state.status in INACTIVE_COMMITTED_STATUSES
        }
        return state.packageName == packageName &&
            state.userId >= 0 &&
            !state.transactionId.isBlank() &&
            versionName != null &&
            versionName.isNotBlank() &&
            versionName.none { it.isISOControl() } &&
            state.versionCode >= 0 &&
            state.signerSha256?.matches(SHA256) == true &&
            safeStockPath &&
            state.stockPath != expectedPatchedPath &&
            state.stockSha256.matches(SHA256) &&
            state.patchedPath == expectedPatchedPath &&
            state.patchedSha256.matches(SHA256) &&
            validStockShadow &&
            validSplitIdentity(state.topology, state.stockSplits) &&
            validStatus
    }

    private fun String.isSafeAbsoluteApkPath(): Boolean =
        startsWith('/') &&
            endsWith(".apk") &&
            this != "/.apk" &&
            none { it.isISOControl() } &&
            !contains("/../") &&
            !contains("/./")

    private suspend fun stopReconciliation(packageName: String, userId: Int, diagnosticId: String) {
        runCatchingPreservingCancellation {
            reconciliationScheduler.stopScheduled(userId, packageName)
        }.onFailure { failure ->
            runCatchingPreservingCancellation {
                transactionStore.appendDiagnostic(
                    packageName,
                    "schedule-${diagnosticId.take(24)}",
                    "Failed to stop root mount reconciliation: ${failure.message}"
                )
            }
        }
    }

    private fun reportProgress(onPhase: (RootMountPhase) -> Unit, phase: RootMountPhase) {
        try {
            onPhase(phase)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Progress observers are not part of the transaction's correctness boundary.
        }
    }

    private fun reportMandatoryRecoveryProgress(onPhase: (RootMountPhase) -> Unit) {
        try {
            onPhase(RootMountPhase.ROLLING_BACK)
        } catch (_: Throwable) {
            // Once state may have changed, rollback must not be interrupted by a UI observer.
        }
    }

    private suspend fun <T> runCatchingPreservingCancellation(
        block: suspend () -> T
    ): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        Result.failure(failure)
    }

    private companion object {
        val mutexes = ConcurrentHashMap<String, Mutex>()
        val MOUNTING_OPERATIONS = setOf(
            RootMountOperation.MOUNT_ONLY,
            RootMountOperation.SWITCH_PATCHED_BUILD,
            RootMountOperation.REPLACE_STOCK_AND_MOUNT
        )
        val RESTART_TOLERANT_MOUNT_OPERATIONS = setOf(
            RootMountOperation.MOUNT_ONLY,
            RootMountOperation.SWITCH_PATCHED_BUILD
        )
        val MANUAL_STATE_RECOVERY_OPERATIONS = setOf(
            RootMountOperation.MOUNT_ONLY,
            RootMountOperation.UNMOUNT,
            RootMountOperation.RECOVER
        )
        val RESUMABLE_AFTER_RECOVERY_OPERATIONS = MOUNTING_OPERATIONS + RootMountOperation.UNMOUNT
        const val FORCE_STOP_TIMEOUT_SECONDS = 15L
        const val POST_MOUNT_STOP_GRACE_MS = 1_000L
        const val PROCESS_NAMESPACE_STABILITY_ATTEMPTS = 5
        const val PROCESS_NAMESPACE_STABILITY_DELAY_MS = 100L
        const val PACKAGE_MANAGER_IDLE_TIMEOUT_SECONDS = 70L
        val SHA256 = Regex("[0-9a-f]{64}")
        val INACTIVE_COMMITTED_STATUSES = setOf(
            "STOCK",
            "INACTIVE",
            "REPATCH_REQUIRED",
            "REPAIR_REQUIRED"
        )
        const val MOUNTED_PENDING_APP_STOP_STATUS = "MOUNTED_PENDING_APP_STOP"
        val ACTIVE_COMMITTED_STATUSES = setOf("MOUNTED", MOUNTED_PENDING_APP_STOP_STATUS)
        fun mutexFor(packageName: String): Mutex = mutexes.getOrPut(packageName) { Mutex() }
    }
}
