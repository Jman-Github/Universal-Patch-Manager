package app.universal.patch.manager.domain.installer.root

import kotlinx.serialization.Serializable
import java.io.File

data class RootMountRequest(
    val packageName: String,
    val userId: Int = 0,
    val operation: RootMountOperation,
    val patchedApk: File? = null,
    val stockApks: List<File> = emptyList(),
    val expectedVersionName: String? = null,
    val expectedVersionCode: Long? = null,
    val expectedStockVersionCode: Long? = null,
    val label: String = packageName,
    val downgradeFallbackConfirmed: Boolean = false,
    val removeModuleAfterUnmount: Boolean = false,
    val downgradeFallbackStock: RootPackageState? = null
) {
    init {
        require(PACKAGE_NAME.matches(packageName)) { "Invalid package name" }
        require(userId >= 0) { "Invalid Android user" }
    }

    private companion object {
        val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}

@Serializable
enum class RootMountOperation {
    MOUNT_ONLY,
    SWITCH_PATCHED_BUILD,
    REPLACE_STOCK_AND_MOUNT,
    UNMOUNT,
    RECOVER,
    RECONCILE
}

@Serializable
enum class RootMountPhase {
    PREPARING,
    STOPPING_APP,
    REMOVING_OLD_MOUNTS,
    SNAPSHOTTING,
    INSTALLING_STOCK,
    WAITING_FOR_PACKAGE_MANAGER,
    STAGING_PATCHED_PAYLOAD,
    MOUNTING,
    VERIFYING,
    COMMITTING,
    ROLLING_BACK,
    COMPLETED
}

@Serializable
enum class RootRecoveryState {
    PREVIOUS_MOUNT,
    STOCK,
    NONE
}

fun RootRecoveryState.describeRecovery(): String = when (this) {
    RootRecoveryState.PREVIOUS_MOUNT -> "The previous patched build was restored."
    RootRecoveryState.STOCK -> "The stock app was restored and left unmounted."
    RootRecoveryState.NONE -> "Automatic recovery did not restore a verified state."
}

fun RootMountResult.Failure.describeOutcome(): String =
    verifiedRecoveryOutcome ?: if (
        phase == RootMountPhase.PREPARING && recoveryState == RootRecoveryState.NONE
    ) {
        "No package or mount changes were made."
    } else {
        recoveryState.describeRecovery()
    }

sealed interface RootMountResult {
    data class Success(
        val transactionId: String,
        val automaticallyRemounted: Boolean = false
    ) : RootMountResult
    data class RecoveredToPreviousMount(
        val transactionId: String,
        val diagnosticId: String,
        val reason: String? = null
    ) : RootMountResult
    data class RecoveredToStock(
        val transactionId: String,
        val diagnosticId: String,
        val reason: String? = null
    ) : RootMountResult
    data class RequiresDowngradeConfirmation(val reason: String) : RootMountResult
    data class RequiresRepatch(val reason: String, val diagnosticId: String? = null) : RootMountResult
    data class Busy(
        val phase: RootMountPhase?,
        val reason: String? = null
    ) : RootMountResult
    data class Failure(
        val phase: RootMountPhase,
        val recoveryState: RootRecoveryState,
        val diagnosticId: String,
        val message: String,
        val verifiedRecoveryOutcome: String? = null
    ) : RootMountResult
}

@Serializable
data class RootPackageState(
    val packageName: String,
    val userId: Int,
    val installed: Boolean,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val signerSha256: String? = null,
    val basePath: String? = null,
    val splitPaths: List<String> = emptyList(),
    val baseSha256: String? = null,
    val enabled: Boolean = true,
    val launcherResolvable: Boolean = false,
    val systemApp: Boolean = false,
    val sharedUserId: String? = null,
    val splitSha256: Map<String, String> = emptyMap()
) {
    val topology: String get() = if (splitPaths.isEmpty()) "SINGLE" else "SPLIT"
}

@Serializable
data class RootArtifactState(
    val path: String,
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val signerSha256: String?,
    val sha256: String,
    val topology: String = "SINGLE",
    val splitName: String? = null,
    val splitHashes: List<String> = emptyList()
)

@Serializable
data class RootMountJournal(
    val transactionId: String,
    val packageName: String,
    val userId: Int,
    val operation: RootMountOperation,
    val phase: RootMountPhase,
    val startedAtEpochMs: Long,
    val initialPackageState: RootPackageState? = null,
    val expectedPackageState: RootPackageState? = null,
    val patchedArtifact: RootArtifactState? = null,
    val stockArtifact: RootArtifactState? = null,
    val previousCommitted: RootCommittedState? = null,
    val candidateMountTargets: List<String> = emptyList(),
    val reusableCommittedModule: Boolean = false,
    val stockMutationStarted: Boolean = false,
    val registrationGap: Boolean = false,
    val moduleMutationStarted: Boolean? = null,
    val moduleRestoreRequired: Boolean? = null,
    val mountMutationStarted: Boolean? = null,
    val rollbackFromPhase: RootMountPhase? = null,
    val completionStateRecorded: Boolean? = null,
    val completionCommittedState: RootCommittedState? = null,
    val diagnosticId: String? = null,
    val status: String? = null,
    val finalizeMountOnCompletion: Boolean = false
)

@Serializable
data class RootCommittedState(
    val transactionId: String,
    val packageName: String,
    val userId: Int,
    val versionName: String?,
    val versionCode: Long,
    val signerSha256: String?,
    val stockPath: String,
    val stockSha256: String,
    val patchedPath: String,
    val patchedSha256: String,
    val stockShadowPath: String? = null,
    val stockShadowSha256: String? = null,
    val preserveStockAcrossBoot: Boolean = false,
    val topology: String,
    val enabled: Boolean = true,
    val launcherResolvable: Boolean = false,
    val active: Boolean = true,
    val status: String = "MOUNTED",
    val committedAtEpochMs: Long,
    val stockSplits: Map<String, String> = emptyMap()
)

data class RootCommandResult(
    val status: Int,
    val stdout: List<String>,
    val stderr: List<String>
) {
    val isSuccess: Boolean get() = status == 0
    val output: String get() = (stdout + stderr).joinToString("\n").trim()

    fun requireSuccess(action: String): RootCommandResult {
        if (!isSuccess) throw RootCommandException(action, this)
        return this
    }
}

class RootCommandException(action: String, val result: RootCommandResult) :
    Exception(
        if (result.status == -1 && result.output.isBlank()) {
            "$action failed because the root shell job was not executed"
        } else {
            "$action failed (${result.status}): ${result.output.ifBlank { "no output" }}"
        }
    )

fun RootMountResult.requireSuccess(): String = when (this) {
    is RootMountResult.Success -> transactionId
    is RootMountResult.RecoveredToPreviousMount -> throw IllegalStateException(
        "${reason.orEmpty()} Previous patched build was restored. Diagnostic $diagnosticId."
    )
    is RootMountResult.RecoveredToStock -> throw IllegalStateException(
        "${reason.orEmpty()} Stock app was restored. Diagnostic $diagnosticId."
    )
    is RootMountResult.RequiresDowngradeConfirmation -> throw IllegalStateException(reason)
    is RootMountResult.RequiresRepatch -> throw IllegalStateException(reason)
    is RootMountResult.Busy -> throw IllegalStateException(
        reason ?: "Root mount transaction is busy (${phase ?: "preparing"})"
    )
    is RootMountResult.Failure -> throw IllegalStateException(
        "$message ${describeOutcome()} Diagnostic $diagnosticId."
    )
}

interface RootShellGateway {
    suspend fun run(command: String): RootCommandResult

    suspend fun runBounded(
        command: String,
        timeoutSeconds: Long,
        operation: String
    ): RootCommandResult = run(command)

    suspend fun runIsolatedBounded(
        command: String,
        timeoutSeconds: Long,
        operation: String
    ): RootCommandResult = runBounded(command, timeoutSeconds, operation)
}

interface PackageStateReader {
    suspend fun read(packageName: String, userId: Int): RootPackageState
    suspend fun installedUserIds(packageName: String): Set<Int>
    fun inspect(file: File): RootArtifactState
    suspend fun waitForStable(expected: RootPackageState, consecutiveReads: Int = 3): RootPackageState
    suspend fun runningPids(packageName: String): List<Int>
    suspend fun waitUntilStopped(packageName: String, timeoutMs: Long = 10_000): Boolean
}
