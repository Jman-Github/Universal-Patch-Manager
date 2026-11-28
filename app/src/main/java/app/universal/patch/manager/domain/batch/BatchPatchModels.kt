/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.universal.patch.manager.domain.batch

import app.universal.patch.manager.data.room.profile.PatchProfilePayload
import app.universal.patch.manager.domain.manager.SignatureMetadataWorkflowProgress
import app.universal.patch.manager.patcher.PatcherSessionInfo
import app.universal.patch.manager.patcher.ProgressEvent
import app.universal.patch.manager.patcher.worker.PatcherMemoryUsage
import app.universal.patch.manager.ui.model.PatcherProgressSnapshot
import app.universal.patch.manager.ui.model.SelectedApp
import app.universal.patch.manager.util.Options
import app.universal.patch.manager.util.PatchSelection
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File

// Code adapted from Morphe, see third-party/NOTICE for more information
// https://github.com/MorpheApp/morphe-manager/pull/795
enum class BatchItemState {
    READY, NEEDS_APK, VERSION_MISMATCH, NO_PATCHES, EXCLUDED,
    RUNNING, SUCCEEDED, FAILED, CANCELLED;

    val isRunnable get() = this == READY
    val needsAttention get() =
        this == NEEDS_APK || this == VERSION_MISMATCH || this == NO_PATCHES
    val isTerminal get() = this == SUCCEEDED || this == FAILED || this == CANCELLED
}

enum class BatchInstallOutcome { INSTALLED, FAILED }

@Serializable
data class BatchBundleRef(
    val uid: Int,
    val name: String,
    val version: String?,
    val patchNames: Set<String>
)

data class BatchPatchItem(
    val packageName: String,
    val appName: String,
    val version: String?,
    val versionCode: Long?,
    val input: SelectedApp?,
    val selection: PatchSelection,
    val options: Options,
    val selectionPayload: PatchProfilePayload? = null,
    val bundles: List<BatchBundleRef>,
    val state: BatchItemState,
    val patcherEngine: String? = null,
    val patcherSessionInfo: PatcherSessionInfo = PatcherSessionInfo(),
    val message: String? = null,
    val forceVersionMismatch: Boolean = false,
    val restoreState: BatchItemState? = null,
    val patchedFile: File? = null,
    val installOutcome: BatchInstallOutcome? = null,
    val installMessage: String? = null,
    val installedPackageName: String? = null,
    val installing: Boolean = false,
    val saving: Boolean = false,
    val savedForLater: Boolean = false,
    val repatchSourcePath: String? = null,
    val sourceEntryKey: String? = null,
    val profileInstallerToken: String? = null,
    val signatureWorkflow: PatchProfilePayload.SignatureWorkflow =
        PatchProfilePayload.SignatureWorkflow(),
    val useMount: Boolean = false,
    val hadPatchFailures: Boolean = false,
    val progressEvents: List<ProgressEvent> = emptyList(),
    val patcherProgress: PatcherProgressSnapshot? = null,
    val memoryUsageSamples: List<PatcherMemoryUsage> = emptyList(),
    val logLines: List<String> = emptyList(),
    val signatureInjection: SignatureMetadataWorkflowProgress = SignatureMetadataWorkflowProgress(),
    // Identifies this queue item. Planned batches have one item per app, so the package name
    // serves; a manual queue can hold the same app more than once (one per patch bundle).
    val key: String = packageName
) {
    val patchCount get() = selection.values.sumOf { it.size }
    val hasAvailablePatchedFile get() =
        state == BatchItemState.SUCCEEDED && patchedFile?.isFile == true
    val needsSaveBeforeLeaving get() =
        hasAvailablePatchedFile &&
            installOutcome != BatchInstallOutcome.INSTALLED &&
            !savedForLater
    val hasProgressDetails get() =
        state == BatchItemState.RUNNING ||
            progressEvents.isNotEmpty() ||
            (state.isTerminal && (
                selectionPayload != null ||
                    logLines.isNotEmpty() ||
                    patcherEngine != null ||
                    patcherSessionInfo != PatcherSessionInfo()
                ))
}

/** True when another item in the batch was resolved from the same source entry as [item]. */
internal fun sharesBatchSourceEntry(items: List<BatchPatchItem>, item: BatchPatchItem): Boolean {
    val source = item.sourceEntryKey ?: return false
    return items.any { it.key != item.key && it.sourceEntryKey == source }
}

/** Names of the bundles this item patches with, which tell apart entries for the same app. */
internal fun BatchPatchItem.selectedBundleNames(): String = bundles
    .filter { it.uid in selection.keys }
    .joinToString(", ") { it.name }

enum class BatchInstallPolicy { SAVE_ONLY, INSTALL_AFTER }

enum class BatchPhase { PLANNING, PREFLIGHT, RUNNING, INSTALLING, CANCELLING, FINISHED }

enum class BatchResultStore { USER, AUTOMATIC }

internal data class BatchPlanRequestKey(
    val packageNames: List<String>,
    val manualQueue: Boolean,
    val showExistingResult: Boolean,
    val scheduled: Boolean,
    val requestId: String?
)

internal fun batchPlanRequestKey(
    packageNames: List<String>,
    manualQueue: Boolean = false,
    showExistingResult: Boolean = false,
    scheduled: Boolean = false,
    requestId: String? = null
) = BatchPlanRequestKey(
    packageNames = packageNames.distinct(),
    manualQueue = manualQueue,
    showExistingResult = showExistingResult,
    scheduled = scheduled,
    requestId = requestId
)

internal val BatchPhase.blocksBatchReplacement: Boolean
    get() = this != BatchPhase.FINISHED

internal fun canOpenBatchPlan(
    currentPhase: BatchPhase?,
    currentRequestKey: BatchPlanRequestKey?,
    currentPackageNames: List<String>,
    currentScheduled: Boolean,
    requestedKey: BatchPlanRequestKey
): Boolean {
    if (currentPhase?.blocksBatchReplacement != true) return true
    return currentRequestKey?.matchesBatchPlanRequest(requestedKey) == true ||
        (
            currentRequestKey == null &&
                currentScheduled == requestedKey.scheduled &&
                currentPackageNames == requestedKey.packageNames
            )
}

internal fun BatchPlanRequestKey.matchesBatchPlanRequest(
    requested: BatchPlanRequestKey
): Boolean = packageNames == requested.packageNames &&
    manualQueue == requested.manualQueue &&
    showExistingResult == requested.showExistingResult &&
    scheduled == requested.scheduled &&
    (requested.requestId == null || requestId == requested.requestId)

internal fun batchResultStore(scheduled: Boolean): BatchResultStore =
    if (scheduled) BatchResultStore.AUTOMATIC else BatchResultStore.USER

internal fun allowsInteractiveBatchActivity(scheduled: Boolean): Boolean = !scheduled

internal fun shouldPersistBatchOutputImmediately(scheduled: Boolean): Boolean = scheduled

data class BatchRunState(
    val items: List<BatchPatchItem>,
    val phase: BatchPhase,
    val policy: BatchInstallPolicy,
    val scheduled: Boolean = false,
    val requestId: String? = null,
    val activeIndex: Int? = null,
    val progress: Float = 0f,
    val detail: String? = null,
    val restored: Boolean = false,
    val activeInstallKey: String? = null
) {
    val activeItem get() = activeIndex?.let(items::getOrNull)
    val runnable get() = items.filter { it.state.isRunnable }
    val succeeded get() = items.count { it.state == BatchItemState.SUCCEEDED }
    val failed get() = items.count { it.state == BatchItemState.FAILED }
    val skipped get() = items.count {
        it.state.needsAttention || it.state == BatchItemState.EXCLUDED
    }
    val processed get() = items.count { it.state.isTerminal }
    val total get() = items.count {
        it.state.isRunnable || it.state.isTerminal || it.state == BatchItemState.RUNNING
    }
    val patchedItems get() = items.filter(BatchPatchItem::hasAvailablePatchedFile)
    val unsavedPatchedItems get() = items.filter(BatchPatchItem::needsSaveBeforeLeaving)
}

internal fun BatchRunState.canInstallBatchItem(item: BatchPatchItem): Boolean =
    (phase == BatchPhase.RUNNING || phase == BatchPhase.FINISHED) &&
        item.hasAvailablePatchedFile &&
        !item.saving &&
        item.installOutcome != BatchInstallOutcome.INSTALLED &&
        items.none { it.installing } &&
        !(phase == BatchPhase.RUNNING && items.any { other ->
            // Installing now would replace the installed app another entry still patches from.
            other.key != item.key &&
                other.packageName == item.packageName &&
                other.input is SelectedApp.Installed &&
                (other.state == BatchItemState.READY || other.state == BatchItemState.RUNNING)
        })

internal fun interruptBatchItemInstall(
    state: BatchRunState,
    key: String,
    message: String
): BatchRunState = state.copy(
    activeInstallKey = null,
    items = state.items.map { item ->
        if (item.key == key && item.installing) {
            item.copy(
                installOutcome = BatchInstallOutcome.FAILED,
                installMessage = message,
                installing = false
            )
        } else item
    }
)

internal fun BatchRunState.canStartBatchPatch(): Boolean =
    phase == BatchPhase.PREFLIGHT &&
        if (scheduled) runnable.isNotEmpty() else runnable.size >= 2

internal fun reorderBatchItems(
    items: List<BatchPatchItem>,
    keys: List<String>
): List<BatchPatchItem> {
    val order = keys.distinct().withIndex().associate { it.value to it.index }
    return items.withIndex()
        .sortedWith(
            compareBy<IndexedValue<BatchPatchItem>> {
                order[it.value.key] ?: Int.MAX_VALUE
            }.thenBy { it.index }
        )
        .map { it.value }
}

internal fun finishInterruptedInstallState(
    state: BatchRunState,
    message: String
): BatchRunState {
    if (state.phase == BatchPhase.CANCELLING || state.phase == BatchPhase.FINISHED) {
        return state
    }
    val interruptedKey = state.activeInstallKey
        ?: state.activeItem?.key
    return state.copy(
        phase = BatchPhase.FINISHED,
        activeIndex = null,
        activeInstallKey = null,
        detail = null,
        items = state.items.map { item ->
            if (item.key == interruptedKey && item.installing) {
                item.copy(
                    installOutcome = BatchInstallOutcome.FAILED,
                    installMessage = message,
                    installing = false
                )
            } else {
                item.copy(installing = false)
            }
        }
    )
}

@Serializable
data class BatchResultSnapshot(
    val completedAt: Long,
    val policy: String,
    val scheduled: Boolean,
    val items: List<BatchResultItemSnapshot>,
    val requestId: String? = null
)

internal fun matchesBatchResultSession(
    snapshotRequestId: String?,
    requestedRequestId: String?
): Boolean = requestedRequestId == null || snapshotRequestId == requestedRequestId

@Serializable
data class BatchResultItemSnapshot(
    val packageName: String,
    val appName: String,
    val version: String?,
    val versionCode: Long?,
    val selectionPayload: PatchProfilePayload? = null,
    val bundles: List<BatchBundleRef> = emptyList(),
    val state: String,
    val message: String?,
    val patchedFilePath: String?,
    val installOutcome: String?,
    val installMessage: String?,
    val installedPackageName: String?,
    val savedForLater: Boolean = false,
    val repatchSourcePath: String? = null,
    val sourceEntryKey: String? = null,
    val profileInstallerToken: String? = null,
    val useMount: Boolean = false,
    val hadPatchFailures: Boolean = true,
    val patcherEngine: String? = null,
    val patcherSessionInfo: PatcherSessionInfo = PatcherSessionInfo(),
    val logLines: List<String> = emptyList(),
    // Results saved before queue items had their own keys fall back to the package name.
    val key: String? = null
)

internal fun restoreBatchSelection(
    payload: PatchProfilePayload?
): PatchSelection = payload?.bundles
    ?.associate { bundle -> bundle.bundleUid to bundle.patches.toSet() }
    ?.filterValues { it.isNotEmpty() }
    .orEmpty()

internal fun restoreBatchBundleRefs(
    payload: PatchProfilePayload?
): List<BatchBundleRef> = payload?.bundles
    ?.map { bundle ->
        BatchBundleRef(
            uid = bundle.bundleUid,
            name = bundle.displayName
                ?.takeIf(String::isNotBlank)
                ?: bundle.sourceName?.takeIf(String::isNotBlank)
                ?: "Bundle ${bundle.bundleUid}",
            version = bundle.version?.takeIf(String::isNotBlank),
            patchNames = bundle.patches.toSet()
        )
    }
    .orEmpty()

internal fun takeLastWithinCharacterBudget(
    lines: List<String>,
    maxCharacters: Int
): List<String> {
    if (lines.isEmpty() || maxCharacters <= 0) return emptyList()
    val retained = ArrayDeque<String>()
    var usedCharacters = 0
    for (line in lines.asReversed()) {
        val separatorCharacters = if (retained.isEmpty()) 0 else 1
        val availableCharacters = maxCharacters - usedCharacters - separatorCharacters
        if (availableCharacters <= 0) break
        val retainedLine = if (line.length <= availableCharacters) {
            line
        } else {
            line.takeLast(availableCharacters)
        }
        retained.addFirst(retainedLine)
        usedCharacters += retainedLine.length + separatorCharacters
        if (retainedLine.length < line.length) break
    }
    return retained.toList()
}

internal fun retainedBatchOutputPaths(
    json: Json,
    serializedSnapshots: Iterable<String>
): Set<String> = decodedBatchResultItems(json, serializedSnapshots)
    .mapNotNull(BatchResultItemSnapshot::patchedFilePath)
    .filter(String::isNotBlank)
    .toSet()

internal fun retainedBatchRepatchSourcePaths(
    json: Json,
    serializedSnapshots: Iterable<String>
): Set<String> = decodedBatchResultItems(json, serializedSnapshots)
    .mapNotNull(BatchResultItemSnapshot::repatchSourcePath)
    .filter(String::isNotBlank)
    .toSet()

private fun decodedBatchResultItems(
    json: Json,
    serializedSnapshots: Iterable<String>
): Sequence<BatchResultItemSnapshot> = serializedSnapshots
    .asSequence()
    .filter(String::isNotBlank)
    .mapNotNull { serialized ->
        runCatching {
            json.decodeFromString<BatchResultSnapshot>(serialized)
        }.getOrNull()
    }
    .flatMap { snapshot -> snapshot.items.asSequence() }
