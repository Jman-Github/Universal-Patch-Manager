package app.universal.patch.manager.domain.installer.root

import java.io.File

data class RootBackupArtifact(val path: String, val sha256: String)

interface RootTransactionStorage {
    suspend fun initialize()
    suspend fun writeActive(journal: RootMountJournal)
    suspend fun readActive(packageName: String): RootMountJournal?
    suspend fun activeExists(packageName: String): Boolean
    suspend fun clearActive(packageName: String)
    suspend fun clearCommitted(packageName: String)
    suspend fun writeCommitted(state: RootCommittedState)
    suspend fun readCommitted(packageName: String): RootCommittedState?
    suspend fun committedExists(packageName: String): Boolean
    suspend fun complete(journal: RootMountJournal, committed: RootCommittedState?)
    suspend fun appendDiagnostic(packageName: String, diagnosticId: String, message: String)
    suspend fun markRepatchRequired(packageName: String, reason: String): RootCommittedState?
    suspend fun listIncompletePackages(): List<String>
    suspend fun listCommittedPackages(): List<String>
    suspend fun exportDiagnostics(packageName: String): String
}

interface RootModuleStorage {
    suspend fun ensureRollbackSpace(packageName: String, stockPaths: List<String>, incomingBytes: Long)
    suspend fun snapshot(packageName: String, preferredPayload: RootBackupArtifact? = null): String?
    suspend fun snapshotInactive(state: RootCommittedState): String? = snapshot(state.packageName)
    suspend fun readLegacyPayload(packageName: String): RootBackupArtifact?
    suspend fun copyCommittedPayloads(
        state: RootCommittedState,
        directory: File,
        installedStockPaths: List<String>
    ): List<File> = emptyList()
    suspend fun readCommittedState(packageName: String): RootCommittedState?
    suspend fun snapshotStock(packageName: String, paths: List<String>): List<RootBackupArtifact>
    suspend fun commitSnapshot(packageName: String)
    suspend fun cleanupCommittedSnapshot(packageName: String)
    suspend fun stageAndActivate(
        transactionId: String,
        packageName: String,
        label: String,
        patchedApk: File,
        compatible: RootPackageState,
        patchedHash: String
    ): String
    suspend fun updateState(state: RootCommittedState)
    suspend fun restorePrevious(packageName: String): Boolean
    suspend fun enable(packageName: String, repairPayloads: Boolean = true)
    suspend fun disableCompetingBootHooks(packageName: String) {}
    suspend fun disable(packageName: String)
    suspend fun removeActive(packageName: String)
    suspend fun purgeBackups(packageName: String)
}

sealed interface RootPackageReplaceResult {
    data object Success : RootPackageReplaceResult
    data class DowngradeRejected(val cause: Throwable) : RootPackageReplaceResult
    data class Failure(val cause: Throwable) : RootPackageReplaceResult

    fun getOrThrow() {
        when (this) {
            Success -> Unit
            is DowngradeRejected -> throw cause
            is Failure -> throw cause
        }
    }
}

interface RootPackageInstallation {
    suspend fun replace(
        apks: List<File>,
        userId: Int,
        allowDowngrade: Boolean = false
    ): RootPackageReplaceResult
    suspend fun uninstallKeepData(packageName: String, userId: Int)
    suspend fun restoreSystemRegistration(packageName: String, userId: Int): Boolean
    suspend fun replaceRootBackup(path: String, expectedSha256: String, userId: Int): Result<Unit>
    suspend fun replaceRootBackups(apks: List<RootBackupArtifact>, userId: Int): Result<Unit> {
        val apk = apks.singleOrNull()
            ?: return Result.failure(IllegalStateException("APK-set rollback is not supported by this installer"))
        return replaceRootBackup(apk.path, apk.sha256, userId)
    }
}

interface RootReconciliationScheduling {
    fun ensureScheduled(userId: Int, packageName: String)
    fun stopScheduled(userId: Int, packageName: String)
    fun trackedPackages(userId: Int): Set<String>
}

interface RootMountVerification {
    suspend fun mountEverywhere(expected: RootCommittedState)
    suspend fun findUrvMounts(packageName: String, extraTargets: Set<String> = emptySet()): List<MountInfoEntry>
    suspend fun verifyTargetsClear(targets: Set<String>)
    suspend fun removeAllUrvMounts(
        packageName: String,
        extraTargets: Set<String>,
        allowLazyRecovery: Boolean,
        includeCompeting: Boolean = false
    ): List<String>
    suspend fun verifyRootMounted(expected: RootCommittedState): RootPackageState
    suspend fun verifyMounted(expected: RootCommittedState): RootPackageState
    suspend fun verifyProcessMounted(expected: RootCommittedState, pids: List<Int>)
    suspend fun verifyProcessStock(packageName: String, userId: Int, stockPath: String, pids: List<Int>)
}

data class RootLockHandle(
    val packageName: String,
    val lockPath: String,
    val ownerPath: String,
    val ownerPid: Int,
    val ownerStart: String,
    val transactionId: String
)

interface RootPackageLocking {
    suspend fun acquire(packageName: String, transactionId: String): RootLockHandle?
    suspend fun release(handle: RootLockHandle)
}
