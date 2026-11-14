package app.universal.patch.manager.ui.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import app.universal.patch.manager.patcher.ProgressEvent
import app.universal.patch.manager.patcher.RemoteError
import app.universal.patch.manager.patcher.StepId

data class PatcherProgressSnapshot(
    val steps: List<Step>,
    val subStepsById: Map<StepId, List<StepDetail>>,
    val progress: Float
)

/**
 * Visual substep state shared by the regular patcher and batch progress.
 * Worker lifecycle, failure logging, and signature injection remain with their callers.
 */
internal class PatcherProgressTracker(
    val steps: SnapshotStateList<Step>,
    val stepSubSteps: MutableMap<StepId, SnapshotStateList<StepDetail>>,
    private val isMorpheSelection: () -> Boolean,
    private val morpheBytecodeMode: () -> String?,
    private val dispatch: ((() -> Unit) -> Unit) = { it() },
    private val onFailure: (ProgressEvent.Failed, Boolean) -> Unit = { _, _ -> },
    private val onRetry: () -> Unit = {},
    private val onSplitStepRequired: () -> Unit = {},
    initialFailure: RemoteError? = null,
    initialFailureStep: String? = null
) {
    private var lastPatchFailure: RemoteError? = initialFailure
    private var lastPatchFailureStep: String? = initialFailureStep
    private var deferLoadPatchesUntilSplitComplete = false
    private val deferredLoadPatchesEvents = mutableListOf<ProgressEvent>()
    private var deferredLoadPatchesStepSnapshot: Step? = null
    var dexSubStepsReady = false
    val pendingDexCompileLines = mutableListOf<String>()
    var writeApkStepStarted = false
    private var acceptsLogProgress = true
    private var logProgressGeneration = 0L
    var progress by mutableFloatStateOf(0f)
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

    private fun isExpandableStep(stepId: StepId) = when (stepId) {
        StepId.PrepareSplitApk,
        StepId.WriteAPK -> true
        else -> false
    }

    private fun prepareSubSteps(stepId: StepId, titles: List<String>) {
        val normalized = titles.filter { it.isNotBlank() }.map { it.trim() }
        val existing = stepSubSteps[stepId]
        val effectiveTitles = if (stepId == StepId.WriteAPK) {
            mergeWriteApkSubStepTitles(normalized, existing)
        } else {
            normalized
        }
        val list = buildSubStepList(effectiveTitles, existing, stepId)
        if (stepId == StepId.WriteAPK) {
            collapsePreparedWriteApkDexChildren(list)
            reconcilePreparedWriteApkSubSteps(list)
        }
        stepSubSteps[stepId] = list
        if (stepId == StepId.WriteAPK) {
            dexSubStepsReady = list.isNotEmpty()
            flushPendingDexCompileLines(force = true)
        }
    }

    private fun buildSubStepList(
        titles: List<String>,
        existing: List<StepDetail>?,
        stepId: StepId
    ): SnapshotStateList<StepDetail> {
        val list = mutableStateListOf<StepDetail>()
        titles.forEach { rawTitle ->
            val (title, skipped) = parseSubStepTitle(rawTitle)
            val previous = existing?.firstOrNull { it.title.equals(title, ignoreCase = true) }
            val effectiveSkipped = skipped || previous?.skipped == true
            val state = when {
                effectiveSkipped -> if (previous?.state == State.FAILED) State.FAILED else State.COMPLETED
                previous != null -> previous.state
                else -> State.WAITING
            }
            val expandable = when {
                previous != null -> previous.expandable
                stepId == StepId.WriteAPK && isDexCompileGroupTitle(title) -> true
                else -> false
            }
            list.add(
                previous?.copy(
                    title = title,
                    state = state,
                    skipped = effectiveSkipped,
                    expandable = expandable
                ) ?: StepDetail(
                    title = title,
                    state = state,
                    skipped = effectiveSkipped,
                    expandable = expandable
                )
            )
        }
        if (stepId == StepId.PrepareSplitApk && list.isNotEmpty()) {
            val extraction = list.filter {
                it.title.equals("Extracting split APKs", ignoreCase = true)
            }
            val remaining = list.filterNot {
                it.title.equals("Extracting split APKs", ignoreCase = true)
            }
            val ordered = extraction + remaining.filter { it.skipped } + remaining.filter { !it.skipped }
            list.clear()
            list.addAll(ordered)
        }
        return list
    }

    private fun mergeWriteApkSubStepTitles(
        incomingTitles: List<String>,
        existing: List<StepDetail>?
    ): List<String> {
        val incoming = incomingTitles
            .map { normalizeWriteApkTitle(StepId.WriteAPK, it) }
            .filter { it.isNotBlank() }
        val incomingDexTitles = incoming
            .filter(::isWriteApkDexChildTitle)
            .distinctBy { it.lowercase() }

        val existingDexTitles = existing.orEmpty()
            .map { it.title }
            .filter(::isWriteApkDexChildTitle)
            .distinctBy { it.lowercase() }
        val merged = incoming.toMutableList()
        if ((incomingDexTitles.isNotEmpty() || existingDexTitles.isNotEmpty()) &&
            merged.none(::isDexCompileGroupTitle)
        ) {
            merged.add(writeApkDexInsertIndex(merged), currentWriteApkDexGroupTitle())
        }
        return merged.distinctBy { it.lowercase() }
    }

    private fun writeApkDexInsertIndex(titles: List<String>): Int {
        return titles.indexOfFirst(::isResourceCompileTitle).takeIf { it != -1 }
            ?: titles.indexOfFirst { it.equals("Writing output APK", ignoreCase = true) }
                .takeIf { it != -1 }
            ?: titles.indexOfFirst { it.equals("Finalizing output", ignoreCase = true) }
                .takeIf { it != -1 }
            ?: titles.size
    }

    private fun updateSubStep(
        stepId: StepId,
        message: String?,
        progress: Pair<Long, Long?>?
    ) {
        val list = stepSubSteps.getOrPut(stepId) { mutableStateListOf() }
        if (message.isNullOrBlank()) {
            if (progress != null && list.isNotEmpty()) {
                val runningIndex = list.indexOfFirst { it.state == State.RUNNING }
                val targetIndex = if (runningIndex != -1) runningIndex else list.lastIndex
                val target = list[targetIndex]
                list[targetIndex] = target.copy(progress = progress)
            }
            return
        }

        val title = message.trim()
        val splitNormalized = if (stepId == StepId.PrepareSplitApk) {
            normalizeSplitApkTitle(title)
        } else {
            title
        }
        val normalized = normalizeWriteApkTitle(stepId, splitNormalized)
        if (stepId == StepId.WriteAPK) {
            when {
                normalized.equals("Writing patched files...", ignoreCase = true) -> {
                    activateWriteApkFromWritingPatchedFiles(list)
                    return
                }

                isWriteApkDexChildTitle(normalized) -> {
                    updateWriteApkDexChildSubStep(list, normalized)
                    return
                }

                isDexCompilePhaseTitle(normalized) || isDexCompileGroupTitle(normalized) -> {
                    activateWriteApkDexGroup(list)
                    return
                }

                isResourceCompileTitle(normalized) -> {
                    activateResourceCompileStep(list, progress)
                    return
                }

                normalized.equals("Writing output APK", ignoreCase = true) ||
                    normalized.equals("Finalizing output", ignoreCase = true) ||
                    normalized.equals("Stripping native libraries", ignoreCase = true) -> {
                    activateKnownWriteApkSubStep(list, normalized, progress)
                    return
                }
            }
        }
        var existingIndex = list.indexOfFirst { it.title == normalized }
        val runningIndex = list.indexOfFirst { !it.skipped && it.state == State.RUNNING }
        if (stepId == StepId.PrepareSplitApk && list.isNotEmpty()) {
            if (normalized.startsWith("Merging ", ignoreCase = true)) {
                if (existingIndex == -1) {
                    existingIndex = findBestSubStepIndex(list, normalized)
                    if (existingIndex == -1) {
                        return
                    }
                }
                if (runningIndex != -1 && existingIndex < runningIndex) {
                    val stale = list[existingIndex]
                    if (!stale.skipped && stale.state != State.COMPLETED) {
                        list[existingIndex] = stale.copy(state = State.COMPLETED, progress = null)
                    }
                    return
                }
                completePrepareSplitApkPriorSteps(list, existingIndex)
            }
        }
        if (stepId == StepId.PrepareSplitApk &&
            (normalized.equals("Writing merged APK", ignoreCase = true)
                || normalized.equals("Finalizing merged APK", ignoreCase = true)
                || normalized.equals("Stripping native libraries", ignoreCase = true))
        ) {
            val limit = if (existingIndex != -1) existingIndex else list.size
            for (index in 0 until limit) {
                val detail = list[index]
                if (detail.skipped || detail.state == State.COMPLETED) continue
                list[index] = detail.copy(state = State.COMPLETED, progress = null)
            }
        }
        if (existingIndex == -1 && list.isNotEmpty()) {
            existingIndex = findBestSubStepIndex(list, normalized)
        }
        if (existingIndex != -1) {
            if (list[existingIndex].skipped) return
            if (stepId == StepId.WriteAPK && existingIndex > 0 && (runningIndex == -1 || existingIndex >= runningIndex)) {
                completeWriteApkPriorSteps(list, existingIndex)
            }
            if (stepId == StepId.PrepareSplitApk && runningIndex != -1 && existingIndex < runningIndex) {
                val existing = list[existingIndex]
                if (existing.state != State.COMPLETED) {
                    list[existingIndex] = existing.copy(state = State.COMPLETED, progress = null)
                }
                return
            }
            if (runningIndex != -1 && existingIndex < runningIndex) {
                return
            }
            if (runningIndex != -1 && runningIndex != existingIndex) {
                val running = list[runningIndex]
                list[runningIndex] = running.copy(state = State.COMPLETED, progress = null)
            }
            val existing = list[existingIndex]
            list[existingIndex] = existing.copy(
                state = if (existing.state == State.COMPLETED) State.COMPLETED else State.RUNNING,
                progress = progress
            )
            return
        }

        if (list.isNotEmpty()) {
            return
        }

        if (runningIndex != -1) {
            val running = list[runningIndex]
            list[runningIndex] = running.copy(state = State.COMPLETED, progress = null)
        }

        list.add(StepDetail(title = normalized, state = State.RUNNING, progress = progress))
    }

    private fun normalizeWriteApkTitle(stepId: StepId, title: String): String {
        if (stepId != StepId.WriteAPK) return title
        if (isMorpheSelection()) {
            when {
                title.equals("Copying base APK", ignoreCase = true) -> return "Copy base APK"
                title.equals("Compiling patched dex files", ignoreCase = true) ->
                    return currentWriteApkDexGroupTitle()
                dexCompilePattern.containsMatchIn(title) || dexWritePattern.containsMatchIn(title) ->
                    return ""
                morpheProcessingClassesPattern.containsMatchIn(title) ->
                    return "Processing ${morpheProcessingClassesPattern.find(title)?.groupValues?.get(1)} classes"
                morpheWroteDexFilesPattern.containsMatchIn(title) ->
                    return "Wrote ${morpheWroteDexFilesPattern.find(title)?.groupValues?.get(1)} dex files"
                morpheStrippedDexPattern.containsMatchIn(title) -> {
                    val dexName = morpheStrippedDexPattern.find(title)?.groupValues?.get(1) ?: return title
                    return "Modified $dexName"
                }
            }
        }
        if (title.equals("Compiling patched dex files", ignoreCase = true)) {
            return currentWriteApkDexGroupTitle()
        }
        if (title.equals("Compiling patched resources", ignoreCase = true) ||
            title.equals("Compiled patched resources", ignoreCase = true)
        ) {
            return "Compiling modified resources"
        }
        return if (title.startsWith("Compiled ", ignoreCase = true)) {
            "Compiling " + title.removePrefix("Compiled ").trim()
        } else {
            title
        }
    }

    private fun normalizeSplitApkTitle(title: String): String {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return trimmed
        val prefix = when {
            trimmed.startsWith("Merging:", ignoreCase = true) -> "Merging:"
            trimmed.startsWith("Merging ", ignoreCase = true) -> "Merging "
            else -> return trimmed
        }
        val raw = trimmed.substringAfter(prefix).trim()
        if (raw.isEmpty()) return trimmed
        val name = if (raw.endsWith(".apk", ignoreCase = true)) raw else "$raw.apk"
        return "Merging $name"
    }

    private fun isDexCompileTitle(title: String): Boolean {
        if (!title.startsWith("Compiling ", ignoreCase = true)) return false
        val suffix = title.removePrefix("Compiling ").trim()
        return suffix.startsWith("classes") && suffix.endsWith(".dex")
    }

    private fun isMorpheWriteApkDexChildTitle(title: String): Boolean {
        return title.startsWith("Processing ", ignoreCase = true) &&
            title.endsWith(" classes", ignoreCase = true) ||
            title.startsWith("Wrote ", ignoreCase = true) &&
            title.contains(" dex files", ignoreCase = true) ||
            title.startsWith("Modified classes", ignoreCase = true) &&
            title.endsWith(".dex", ignoreCase = true)
    }

    private fun isWriteApkDexChildTitle(title: String): Boolean =
        if (isMorpheSelection()) {
            isMorpheWriteApkDexChildTitle(title)
        } else {
            isDexCompileTitle(title)
        }

    private fun writeApkDexSortKey(name: String): Int {
        val base = name.removeSuffix(".dex")
        if (base.equals("classes", ignoreCase = true)) return 1
        val suffix = base.removePrefix("classes")
        return suffix.toIntOrNull() ?: Int.MAX_VALUE
    }

    private fun isDexCompilePhaseTitle(title: String): Boolean =
        title.equals("Compiling patched dex files", ignoreCase = true) ||
            isDexCompileGroupTitle(title)

    private fun isDexCompileGroupTitle(title: String): Boolean =
        title.equals(WRITE_APK_DEX_GROUP_TITLE, ignoreCase = true) ||
            title.startsWith("$WRITE_APK_DEX_GROUP_TITLE:", ignoreCase = true)

    private fun isResourceCompileTitle(title: String): Boolean =
        title.equals("Compiling modified resources", ignoreCase = true) ||
            title.equals("Compiling patched resources", ignoreCase = true)


    private fun currentWriteApkDexGroupTitle(): String {
        if (!isMorpheSelection()) return WRITE_APK_DEX_GROUP_TITLE
        val bytecodeMode = morpheBytecodeMode()
        return if (bytecodeMode.equals("FULL", ignoreCase = true)) {
            "Compiling DEX files: FULL"
        } else {
            "Compiling DEX files: FAST"
        }
    }

    fun resetDexCompileState() {
        dexSubStepsReady = false
        pendingDexCompileLines.clear()
        writeApkStepStarted = false
    }

    fun reconcileProgressStateAfterSuccess() {
        stopLogProgress()
        clearFailure()
        resetDexCompileState()
        steps.forEachIndexed { index, step ->
            if (step.state == State.FAILED) return@forEachIndexed
            steps[index] = step.withState(
                state = State.COMPLETED,
                message = null,
                progress = null
            )
        }
        stepSubSteps.forEach { (_, list) ->
            list.forEachIndexed { index, detail ->
                if (detail.state == State.FAILED) return@forEachIndexed
                list[index] = detail.withRecursiveState(
                    state = State.COMPLETED,
                    message = null,
                    progress = null
                )
            }
        }
        progress = 1f
    }

    fun resetVisualProgress() {
        logProgressGeneration++
        acceptsLogProgress = true
        progress = 0f
    }

    private fun stopLogProgress() {
        logProgressGeneration++
        acceptsLogProgress = false
    }

    private fun dispatchLogProgress(action: () -> Unit) {
        val generation = logProgressGeneration
        dispatch {
            // A callback queued by the previous attempt must not update a retry.
            if (acceptsLogProgress && generation == logProgressGeneration) action()
        }
    }

    private fun refreshVisualProgress() {
        val totalSteps = steps.size
        if (totalSteps <= 0) {
            progress = 0f
            return
        }

        val completedSteps = steps.sumOf(::calculateProgressFraction)
        val candidate = (completedSteps / totalSteps.toDouble()).toFloat().coerceIn(0f, 1f)
        if (candidate > progress) {
            progress = candidate
        }
    }

    private fun calculateProgressFraction(step: Step): Double {
        if (step.state == State.COMPLETED) return 1.0

        return expandableStepProgressFraction(step.id, stepSubSteps[step.id].orEmpty())
            ?: step.state.progressFraction(step.progress)
    }

    private fun updateExpandableStepProgress(stepId: StepId?, previousProgress: Pair<Long, Long?>?) {
        if (stepId == null || !isExpandableStep(stepId)) return
        val index = steps.indexOfFirst { it.id == stepId }
        if (index == -1 || steps[index].state != State.RUNNING) return
        val step = steps[index]
        val fraction = expandableStepProgressFraction(stepId, stepSubSteps[stepId].orEmpty()) ?: return
        val previous = previousProgress?.takeIf { it.second == 1000L }?.first ?: 0L
        // Late child events and newly discovered work must not restart the parent indicator.
        val current = maxOf(previous, (fraction * 1000).toLong()).coerceIn(0L, 999L)
        steps[index] = step.copy(progress = current to 1000L)
    }

    private fun State.progressFraction(progress: Pair<Long, Long?>?): Double = when (this) {
        State.COMPLETED -> 1.0
        State.RUNNING -> {
            val current = progress?.first
            val total = progress?.second?.takeIf { it > 0L }
            if (current != null && total != null) {
                (current.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
        }
        else -> 0.0
    }

    private fun flushPendingDexCompileLines(force: Boolean = false) {
        if (pendingDexCompileLines.isEmpty()) return
        val list = stepSubSteps[StepId.WriteAPK] ?: return
        val iterator = pendingDexCompileLines.iterator()
        while (iterator.hasNext()) {
            val title = iterator.next()
            val hasEntry = list.any { it.title.equals(title, ignoreCase = true) }
            if (force || hasEntry) {
                updateSubStep(StepId.WriteAPK, title, null)
                iterator.remove()
            }
        }
    }

    private fun completeWriteApkApplyChanges(list: SnapshotStateList<StepDetail>) {
        val index = list.indexOfFirst {
            it.title.equals("Applying patched changes", ignoreCase = true)
        }
        if (index == -1) return
        val detail = list[index]
        if (detail.state == State.COMPLETED) return
        list[index] = detail.copy(state = State.COMPLETED, progress = null)
    }

    private fun completeResourceCompileIfPending(list: SnapshotStateList<StepDetail>) {
        val index = list.indexOfFirst {
            isResourceCompileTitle(it.title)
        }
        if (index == -1) return
        val detail = list[index]
        if (detail.skipped || detail.state == State.COMPLETED) return
        list[index] = detail.copy(state = State.COMPLETED, progress = null)
    }

    private fun completeWriteApkPriorSteps(
        list: SnapshotStateList<StepDetail>,
        untilExclusive: Int
    ) {
        if (untilExclusive <= 0) return
        val limit = untilExclusive.coerceAtMost(list.size)
        for (index in 0 until limit) {
            val detail = list[index]
            if (detail.skipped || detail.state == State.COMPLETED) continue
            list[index] = detail.copy(state = State.COMPLETED, progress = null)
        }
    }

    private fun reconcilePreparedWriteApkSubSteps(list: SnapshotStateList<StepDetail>) {
        val activeIndex = list.indexOfLast { detail ->
            !detail.skipped && (
                detail.state == State.RUNNING ||
                    detail.state == State.COMPLETED ||
                    detail.children.any { child ->
                        !child.skipped &&
                            (child.state == State.RUNNING || child.state == State.COMPLETED)
                    }
                )
        }
        if (activeIndex == -1) return

        val activeDetail = list[activeIndex]
        if (activeDetail.state == State.WAITING && activeDetail.children.isNotEmpty()) {
            list[activeIndex] = activeDetail.copy(
                state = State.RUNNING,
                progress = null,
                expandable = true
            )
        }
        completeWriteApkPriorSteps(list, activeIndex)
        if (isMorpheSelection()) {
            promoteNextWriteApkSubStep(list, activeIndex)
        }
    }

    private fun collapsePreparedWriteApkDexChildren(list: SnapshotStateList<StepDetail>) {
        val flatDexEntries = list
            .filter(::isPreparedWriteApkDexChild)
        if (flatDexEntries.isEmpty()) return

        val groupIndex = ensureWriteApkDexGroupIndex(list)
        val group = list[groupIndex]
        val mergedChildren = (group.children + flatDexEntries)
            .distinctBy { it.title.lowercase() }
            .let { children ->
                if (isMorpheSelection()) {
                    children
                } else {
                    children.sortedBy { writeApkDexSortKey(it.title.removePrefix("Compiling ").trim()) }
                }
            }

        for (index in list.lastIndex downTo 0) {
            if (index == groupIndex) continue
            if (isPreparedWriteApkDexChild(list[index])) {
                list.removeAt(index)
            }
        }

        val updatedGroupIndex = list.indexOfFirst(::matchesWriteApkDexGroup)
        if (updatedGroupIndex == -1) return
        val updatedGroup = list[updatedGroupIndex]
        list[updatedGroupIndex] = updatedGroup.copy(
            expandable = true,
            children = mergedChildren
        )
    }

    private fun isPreparedWriteApkDexChild(detail: StepDetail): Boolean =
        isWriteApkDexChildTitle(detail.title)

    private fun completePrepareSplitApkPriorSteps(
        list: SnapshotStateList<StepDetail>,
        untilExclusive: Int
    ) {
        if (untilExclusive <= 0) return
        val limit = untilExclusive.coerceAtMost(list.size)
        for (index in 0 until limit) {
            val detail = list[index]
            if (detail.skipped || detail.state == State.COMPLETED) continue
            list[index] = detail.copy(state = State.COMPLETED, progress = null)
        }
    }

    private fun promoteNextWriteApkSubStep(
        list: SnapshotStateList<StepDetail>,
        completedIndex: Int
    ) {
        val runningIndex = list.indexOfFirst { !it.skipped && it.state == State.RUNNING }
        if (runningIndex != -1) return

        val nextIndex = ((completedIndex + 1) until list.size)
            .firstOrNull { index ->
                val detail = list[index]
                !detail.skipped && detail.state == State.WAITING
            }
            ?: return

        val next = list[nextIndex]
        list[nextIndex] = next.copy(state = State.RUNNING, progress = null)
    }

    private fun ensureWriteApkDexGroupIndex(list: SnapshotStateList<StepDetail>): Int {
        val existingIndex = list.indexOfFirst(::matchesWriteApkDexGroup)
        if (existingIndex != -1) return existingIndex

        val insertIndex = writeApkDexInsertIndex(list.map { it.title })
        list.add(
            insertIndex,
            StepDetail(
                title = currentWriteApkDexGroupTitle(),
                state = State.WAITING,
                expandable = true
            )
        )
        return insertIndex
    }

    private fun matchesWriteApkDexGroup(detail: StepDetail): Boolean =
        isDexCompileGroupTitle(detail.title)

    private fun hasLaterWriteApkPhaseStarted(list: List<StepDetail>, phaseIndex: Int): Boolean =
        list.drop(phaseIndex + 1).any { !it.skipped && it.state != State.WAITING }

    private fun activateWriteApkDexGroup(list: SnapshotStateList<StepDetail>) {
        val groupIndex = ensureWriteApkDexGroupIndex(list)
        if (hasLaterWriteApkPhaseStarted(list, groupIndex)) return
        completeWriteApkApplyChanges(list)
        completeWriteApkPriorSteps(list, groupIndex)

        val runningIndex = list.indexOfFirst { !it.skipped && it.state == State.RUNNING }
        if (runningIndex != -1 && runningIndex != groupIndex) {
            val running = list[runningIndex]
            list[runningIndex] = running.copy(state = State.COMPLETED, progress = null)
        }

        val group = list[groupIndex]
        if (group.state != State.COMPLETED) {
            list[groupIndex] = group.copy(state = State.RUNNING, progress = null, expandable = true)
        }
    }

    private fun activateWriteApkFromWritingPatchedFiles(list: SnapshotStateList<StepDetail>) {
        val applyIndex = list.indexOfFirst {
            it.title.equals("Applying patched changes", ignoreCase = true)
        }
        if (applyIndex != -1) {
            completeWriteApkPriorSteps(list, applyIndex + 1)
            val apply = list[applyIndex]
            if (apply.state != State.COMPLETED) {
                list[applyIndex] = apply.copy(state = State.COMPLETED, progress = null)
            }
            if (isMorpheSelection()) {
                promoteNextWriteApkSubStep(list, applyIndex)
            }
            return
        }

        val copyIndex = list.indexOfFirst {
            it.title.equals("Copy base APK", ignoreCase = true)
        }
        if (copyIndex != -1) {
            completeWriteApkPriorSteps(list, copyIndex + 1)
            if (isMorpheSelection()) {
                promoteNextWriteApkSubStep(list, copyIndex)
            }
        }
    }

    private fun updateWriteApkDexChildSubStep(
        list: SnapshotStateList<StepDetail>,
        normalizedTitle: String
    ) {
        val groupIndex = ensureWriteApkDexGroupIndex(list)
        if (hasLaterWriteApkPhaseStarted(list, groupIndex)) {
            // A delayed compile report belongs to the finished DEX phase, not the active output chip.
            val group = list[groupIndex]
            val children = group.children.toMutableList()
            val index = children.indexOfFirst { it.title.equals(normalizedTitle, ignoreCase = true) }
            if (index == -1) {
                children.add(StepDetail(title = normalizedTitle, state = State.COMPLETED))
            }
            list[groupIndex] = group.copy(children = children)
            return
        }
        if (isMorpheSelection() && isMorpheWriteApkDexChildTitle(normalizedTitle)) {
            updateMorpheWriteApkDexChildSubStep(list, normalizedTitle)
            return
        }

        val group = list[groupIndex]
        val initialChildren = group.children
        val initialRunningChildIndex = initialChildren.indexOfFirst { !it.skipped && it.state == State.RUNNING }
        val initialExistingIndex = initialChildren.indexOfFirst { it.title.equals(normalizedTitle, ignoreCase = true) }

        if (initialExistingIndex != -1 && initialChildren[initialExistingIndex].state == State.COMPLETED) {
            reconcilePreparedWriteApkSubSteps(list)
            return
        }

        if (initialRunningChildIndex != -1 &&
            initialChildren[initialRunningChildIndex].title.equals(normalizedTitle, ignoreCase = true)
        ) {
            reconcilePreparedWriteApkSubSteps(list)
            return
        }

        activateWriteApkDexGroup(list)
        val activatedGroup = list[groupIndex]
        val children = activatedGroup.children.toMutableList()
        val runningChildIndex = children.indexOfFirst { !it.skipped && it.state == State.RUNNING }
        val existingIndex = children.indexOfFirst { it.title.equals(normalizedTitle, ignoreCase = true) }

        if (runningChildIndex != -1) {
            val runningChild = children[runningChildIndex]
            children[runningChildIndex] = runningChild.copy(state = State.COMPLETED, progress = null)
        }

        val targetIndex = if (existingIndex != -1) existingIndex else children.size
        for (index in 0 until targetIndex) {
            val child = children[index]
            if (!child.skipped && child.state != State.COMPLETED) {
                children[index] = child.copy(state = State.COMPLETED, progress = null)
            }
        }

        if (existingIndex != -1) {
            val existingChild = children[existingIndex]
            children[existingIndex] = existingChild.copy(state = State.RUNNING, progress = null)
        } else {
            children.add(StepDetail(title = normalizedTitle, state = State.RUNNING))
        }

        list[groupIndex] = list[groupIndex].copy(
            state = State.RUNNING,
            progress = null,
            expandable = true,
            children = children
        )
    }

    private fun updateMorpheWriteApkDexChildSubStep(
        list: SnapshotStateList<StepDetail>,
        normalizedTitle: String
    ) {
        val groupIndex = ensureWriteApkDexGroupIndex(list)
        val group = list[groupIndex]
        val initialChildren = group.children
        val initialRunningChildIndex = initialChildren.indexOfFirst { !it.skipped && it.state == State.RUNNING }
        val initialExistingIndex = initialChildren.indexOfFirst { it.title.equals(normalizedTitle, ignoreCase = true) }

        if (initialExistingIndex != -1 && initialChildren[initialExistingIndex].state == State.COMPLETED) {
            reconcilePreparedWriteApkSubSteps(list)
            return
        }

        if (initialRunningChildIndex != -1 &&
            initialChildren[initialRunningChildIndex].title.equals(normalizedTitle, ignoreCase = true)
        ) {
            reconcilePreparedWriteApkSubSteps(list)
            return
        }

        activateWriteApkDexGroup(list)
        val activatedGroup = list[groupIndex]
        val children = activatedGroup.children.toMutableList()
        val runningChildIndex = children.indexOfFirst { !it.skipped && it.state == State.RUNNING }
        val existingIndex = children.indexOfFirst { it.title.equals(normalizedTitle, ignoreCase = true) }

        if (runningChildIndex != -1) {
            val runningChild = children[runningChildIndex]
            children[runningChildIndex] = runningChild.copy(state = State.COMPLETED, progress = null)
        }

        if (existingIndex != -1) {
            val existingChild = children[existingIndex]
            children[existingIndex] = existingChild.copy(state = State.RUNNING, progress = null)
        } else {
            children.add(StepDetail(title = normalizedTitle, state = State.RUNNING))
        }

        list[groupIndex] = list[groupIndex].copy(
            state = State.RUNNING,
            progress = null,
            expandable = true,
            children = children
        )
    }

    private fun completeWriteApkDexGroup(list: SnapshotStateList<StepDetail>) {
        val groupIndex = list.indexOfFirst(::matchesWriteApkDexGroup)
        if (groupIndex == -1) return

        val group = list[groupIndex]
        val children = group.children.toMutableList()
        for (index in children.indices) {
            val child = children[index]
            if (!child.skipped && child.state != State.COMPLETED) {
                children[index] = child.copy(state = State.COMPLETED, progress = null)
            }
        }

        val finalState = if (group.state == State.WAITING && children.isEmpty()) State.WAITING else State.COMPLETED
        list[groupIndex] = group.copy(
            state = finalState,
            progress = null,
            expandable = true,
            children = children
        )
        if (isMorpheSelection()) {
            promoteNextWriteApkSubStep(list, groupIndex)
        }
    }

    private fun activateResourceCompileStep(
        list: SnapshotStateList<StepDetail>,
        progress: Pair<Long, Long?>?
    ) {
        val resourceIndex = list.indexOfFirst {
            isResourceCompileTitle(it.title)
        }.takeIf { it != -1 } ?: run {
            val insertIndex = list.indexOfFirst {
                it.title.equals("Writing output APK", ignoreCase = true)
            }.takeIf { it != -1 } ?: list.size
            list.add(insertIndex, StepDetail(title = "Compiling modified resources", state = State.WAITING))
            insertIndex
        }
        if (hasLaterWriteApkPhaseStarted(list, resourceIndex)) return
        completeWriteApkPriorSteps(list, resourceIndex)
        completeWriteApkDexGroup(list)

        val runningIndex = list.indexOfFirst { it.state == State.RUNNING }
        if (runningIndex != -1 && runningIndex != resourceIndex) {
            val running = list[runningIndex]
            list[runningIndex] = running.copy(state = State.COMPLETED, progress = null)
        }

        val resourceStep = list[resourceIndex]
        if (resourceStep.state == State.COMPLETED) return
        list[resourceIndex] = resourceStep.copy(state = State.RUNNING, progress = progress)
    }

    private fun activateKnownWriteApkSubStep(
        list: SnapshotStateList<StepDetail>,
        normalizedTitle: String,
        progress: Pair<Long, Long?>?
    ) {
        val existingIndex = list.indexOfFirst { it.title.equals(normalizedTitle, ignoreCase = true) }
        if (existingIndex == -1) return
        if (hasLaterWriteApkPhaseStarted(list, existingIndex)) return

        completeWriteApkDexGroup(list)
        completeWriteApkPriorSteps(list, existingIndex)

        val runningIndex = list.indexOfFirst { !it.skipped && it.state == State.RUNNING }
        if (runningIndex != -1 && runningIndex != existingIndex) {
            val running = list[runningIndex]
            list[runningIndex] = running.copy(state = State.COMPLETED, progress = null)
        }

        val existing = list[existingIndex]
        list[existingIndex] = existing.copy(
            state = if (existing.state == State.COMPLETED) State.COMPLETED else State.RUNNING,
            progress = progress
        )
    }

    fun handleDexCompileLine(rawLine: String) {
        if (!acceptsLogProgress) return
        val line = rawLine.trim()
        if (line.isEmpty()) return
        if (line.startsWith("[STDIO]:", ignoreCase = true)) return
        if (!writeApkStepStarted && shouldStartWriteApkFromLog(line)) {
            startWriteApkFromLogFallback()
        }
        if (!writeApkStepStarted) return
        if (line.contains("Writing patched files", ignoreCase = true)) {
            dispatchLogProgress {
                if (shouldBufferWriteApkLogProgress()) {
                    pendingDexCompileLines += "Writing patched files..."
                    return@dispatchLogProgress
                }
                updateSubStep(StepId.WriteAPK, "Writing patched files...", null)
            }
            return
        }
        if (line.contains("Compiling modified resources", ignoreCase = true) ||
            line.contains("Compiling patched resources", ignoreCase = true)
        ) {
            dispatchLogProgress {
                if (shouldBufferWriteApkLogProgress()) {
                    pendingDexCompileLines += "Compiling modified resources"
                    return@dispatchLogProgress
                }
                if (!hasWriteApkApplyPhaseStarted()) return@dispatchLogProgress
                updateSubStep(StepId.WriteAPK, line, null)
            }
            return
        }
        if (isDexCompilePhaseTitle(line)) {
            dispatchLogProgress {
                if (shouldBufferWriteApkLogProgress()) {
                    pendingDexCompileLines += line
                    return@dispatchLogProgress
                }
                if (!hasWriteApkApplyPhaseStarted()) return@dispatchLogProgress
                updateSubStep(StepId.WriteAPK, line, null)
            }
            return
        }
        morpheProcessingClassesPattern.find(line)?.let { match ->
            val title = "Processing ${match.groupValues[1]} classes"
            dispatchLogProgress {
                if (shouldBufferWriteApkLogProgress()) {
                    pendingDexCompileLines += title
                    return@dispatchLogProgress
                }
                if (!hasWriteApkApplyPhaseStarted()) return@dispatchLogProgress
                updateSubStep(StepId.WriteAPK, title, null)
            }
            return
        }
        morpheWroteDexFilesPattern.find(line)?.let { match ->
            val title = "Wrote ${match.groupValues[1]} dex files"
            dispatchLogProgress {
                if (shouldBufferWriteApkLogProgress()) {
                    pendingDexCompileLines += title
                    return@dispatchLogProgress
                }
                if (!hasWriteApkApplyPhaseStarted()) return@dispatchLogProgress
                updateSubStep(StepId.WriteAPK, title, null)
            }
            return
        }
        morpheStrippedDexPattern.find(line)?.let { match ->
            val title = "Modified ${match.groupValues[1]}"
            dispatchLogProgress {
                if (shouldBufferWriteApkLogProgress()) {
                    pendingDexCompileLines += title
                    return@dispatchLogProgress
                }
                if (!hasWriteApkApplyPhaseStarted()) return@dispatchLogProgress
                updateSubStep(StepId.WriteAPK, title, null)
            }
            return
        }
        if (isMorpheSelection()) return
        val match = dexCompilePattern.find(line) ?: dexWritePattern.find(line) ?: return
        val completionKeyword = match.groupValues.getOrNull(1)
        val dexName = match.groupValues.lastOrNull()?.takeIf { it.endsWith(".dex") } ?: return
        dispatchLogProgress {
            val isCompletion = completionKeyword.equals("Compiled", ignoreCase = true)
            val title = if (isCompletion) "Compiled $dexName" else "Compiling $dexName"
            if (shouldBufferWriteApkLogProgress()) {
                pendingDexCompileLines += title
                return@dispatchLogProgress
            }
            if (!hasWriteApkApplyPhaseStarted()) return@dispatchLogProgress
            updateSubStep(StepId.WriteAPK, title, null)
        }
    }

    private fun shouldBufferWriteApkLogProgress(): Boolean {
        val list = stepSubSteps[StepId.WriteAPK] ?: return true
        return list.isEmpty()
    }

    private fun hasWriteApkApplyPhaseStarted(): Boolean {
        val list = stepSubSteps[StepId.WriteAPK] ?: return false
        val applyStep = list.firstOrNull {
            it.title.equals("Applying patched changes", ignoreCase = true)
        } ?: return false
        return applyStep.state == State.RUNNING || applyStep.state == State.COMPLETED
    }

    private fun shouldStartWriteApkFromLog(line: String): Boolean {
        if (line.contains("Writing patched files", ignoreCase = true)) return true
        if (line.contains("Compiling patched dex files", ignoreCase = true)) return true
        if (line.contains("Applying patched changes", ignoreCase = true)) return true
        if (line.contains("Compiled modified resources", ignoreCase = true)) return true
        if (line.contains("Compiled patched resources", ignoreCase = true)) return true
        if (line.contains("Compiling modified resources", ignoreCase = true)) return true
        if (line.contains("Writing output APK", ignoreCase = true)) return true
        if (line.contains("Finalizing output", ignoreCase = true)) return true
        if (line.contains("Patched apk saved to", ignoreCase = true)) return true
        if (morpheProcessingClassesPattern.containsMatchIn(line)) return true
        if (morpheWroteDexFilesPattern.containsMatchIn(line)) return true
        if (morpheStrippedDexPattern.containsMatchIn(line)) return true
        if (dexCompilePattern.containsMatchIn(line)) return true
        if (dexWritePattern.containsMatchIn(line)) return true
        return false
    }

    private fun startWriteApkFromLogFallback() {
        writeApkStepStarted = true
        val writeIndex = steps.indexOfFirst { it.id == StepId.WriteAPK }
        if (writeIndex == -1) return

        val runningIndex = steps.indexOfFirst { it.state == State.RUNNING }
        if (runningIndex != -1 && runningIndex != writeIndex && runningIndex < writeIndex) {
            val running = steps[runningIndex]
            steps[runningIndex] = running.withState(State.COMPLETED, progress = null)
        }

        val writeStep = steps[writeIndex]
        if (writeStep.state == State.WAITING) {
            steps[writeIndex] = writeStep.withState(State.RUNNING)
        }
    }

    private fun promoteNextStepIfNeeded(completedIndex: Int) {
        val completedStep = steps.getOrNull(completedIndex) ?: return
        if (completedStep.hide) return
        // Preparation can include worker startup and input inspection between events.
        // Keep its next row active during that handoff, just like section transitions.
        val preparing = completedStep.category == StepCategory.PREPARING
        if (!preparing && !isLastVisibleStepInSection(completedIndex)) return

        val nextVisibleIndex = ((completedIndex + 1) until steps.size)
            .firstOrNull { !steps[it].hide }
            ?: return
        val nextStep = steps[nextVisibleIndex]
        if (!preparing && nextStep.category == completedStep.category) return
        if (nextStep.state != State.WAITING) return

        val anotherVisibleRunning = steps.indices.any { index ->
            index != nextVisibleIndex && !steps[index].hide && steps[index].state == State.RUNNING
        }
        if (anotherVisibleRunning) return

        steps[nextVisibleIndex] = nextStep.withState(
            state = State.RUNNING,
            message = null,
            progress = null
        )
    }

    private fun promoteImmediateSignStepIfNeeded(completedIndex: Int) {
        val completedStep = steps.getOrNull(completedIndex) ?: return
        if (completedStep.id != StepId.WriteAPK || completedStep.hide) return

        val signIndex = ((completedIndex + 1) until steps.size)
            .firstOrNull { index ->
                val step = steps[index]
                !step.hide && step.id == StepId.SignAPK
            }
            ?: return

        val signStep = steps[signIndex]
        if (signStep.state != State.WAITING) return

        val anotherVisibleRunning = steps.indices.any { index ->
            index != signIndex && !steps[index].hide && steps[index].state == State.RUNNING
        }
        if (anotherVisibleRunning) return

        steps[signIndex] = signStep.withState(
            state = State.RUNNING,
            message = null,
            progress = null
        )
    }

    private fun isLastVisibleStepInSection(stepIndex: Int): Boolean {
        val step = steps.getOrNull(stepIndex) ?: return false
        return ((stepIndex + 1) until steps.size).none { index ->
            !steps[index].hide && steps[index].category == step.category
        }
    }

    private fun findBestSubStepIndex(
        list: List<StepDetail>,
        title: String
    ): Int {
        val needle = title.lowercase()
        val prefixIndex = list.indexOfFirst { needle.startsWith(it.title.lowercase()) }
        if (prefixIndex != -1) return prefixIndex
        val reversePrefix = list.indexOfFirst { it.title.lowercase().startsWith(needle) }
        if (reversePrefix != -1) return reversePrefix
        val containsIndex = list.indexOfFirst { needle.contains(it.title.lowercase()) }
        return containsIndex
    }

    private fun finalizeSubSteps(
        stepId: StepId,
        failed: Boolean = false,
        errorMessage: String? = null
    ) {
        val list = stepSubSteps[stepId] ?: return
        if (list.isEmpty()) return
        if (!failed) {
            list.forEachIndexed { index, detail ->
                list[index] = detail.withRecursiveState(state = State.COMPLETED, progress = null)
            }
            return
        }

        val runningIndex = list.indexOfFirst { !it.skipped && it.state == State.RUNNING }
        val failedIndex = when {
            runningIndex != -1 -> runningIndex
            else -> list.indexOfFirst { !it.skipped && it.state != State.COMPLETED }.takeIf { it != -1 }
        } ?: return

        list.forEachIndexed { index, detail ->
            if (detail.skipped) {
                list[index] = detail.stopRunning(State.WAITING)
                return@forEachIndexed
            }
            val updated = when {
                index == failedIndex -> detail.stopRunning(State.FAILED, errorMessage).copy(
                    state = State.FAILED,
                    message = errorMessage,
                    progress = null
                )
                else -> detail.stopRunning(State.WAITING)
            }
            list[index] = updated
        }
    }

    private fun StepDetail.stopRunning(state: State, message: String? = null): StepDetail = copy(
        state = if (this.state == State.RUNNING) state else this.state,
        message = if (this.state == State.RUNNING) message ?: this.message else this.message,
        progress = null,
        children = children.map { it.stopRunning(state, message) }
    )

    private fun StepDetail.withRecursiveState(
        state: State = this.state,
        message: String? = this.message,
        progress: Pair<Long, Long?>? = this.progress
    ): StepDetail = copy(
        state = state,
        message = message,
        progress = progress,
        children = children.map { child ->
            child.withRecursiveState(
                state = state,
                message = if (state == State.FAILED) message else child.message,
                progress = null
            )
        }
    )

    private fun parseSubStepTitle(rawTitle: String): Pair<String, Boolean> {
        val trimmed = rawTitle.trim()
        return if (trimmed.startsWith(SKIPPED_SUBSTEP_PREFIX)) {
            trimmed.removePrefix(SKIPPED_SUBSTEP_PREFIX).trim() to true
        } else {
            trimmed to false
        }
    }

    /** Reorder rows by runtime execution while retaining stable IDs and failure state. */
    fun applyPatchExecutionOrder(order: List<Int>) {
        if (order.isEmpty()) return
        val positions = steps.indices.filter { steps[it].id is StepId.ExecutePatch }
        val byIndex = positions.associate { position ->
            (steps[position].id as StepId.ExecutePatch).index to steps[position]
        }
        val indexes = order.filter { it in byIndex }.distinct()
        val waiting = byIndex.filterKeys { it !in indexes }.toSortedMap().values.map { step ->
            // Preparation may have speculatively activated the alphabetically first row.
            if (step.state == State.RUNNING) step.withState(State.WAITING) else step
        }
        val ordered = indexes.map { byIndex.getValue(it) } + waiting
        positions.forEachIndexed { index, position -> steps[position] = ordered[index] }
    }

    fun processProgressEventLocked(event: ProgressEvent) {
        val previousProgress = steps.firstOrNull { it.id == event.stepId }?.progress
        prepareSplitStepForIncomingEvent(event)
        if (bufferLoadPatchesEventDuringSplitPreparation(event)) return
        applyProgressEvent(event)

        if (event.stepId == StepId.PrepareSplitApk) {
            when (event) {
                is ProgressEvent.Completed -> replayDeferredLoadPatchesEvents()
                is ProgressEvent.Failed -> clearDeferredLoadPatchesEvents()
                else -> Unit
            }
        }
        enforceSplitPreparationVisualPriority()
        updateExpandableStepProgress(event.stepId, previousProgress)
        refreshVisualProgress()
    }

    private fun applyProgressEvent(event: ProgressEvent) {
        val eventStepId = event.stepId
        if (shouldResetProgressStateForAutomaticRetry(event)) {
            resetProgressStateForAutomaticRetry()
        }
        val isDuplicateFailureWrapper = event is ProgressEvent.Failed &&
            lastPatchFailure?.matchesUnderlyingFailure(event.error) == true &&
            (
                eventStepId == null ||
                    (
                        eventStepId == StepId.ExecutePatches &&
                            lastPatchFailureStep == StepId.ExecutePatch::class.java.simpleName
                    )
            )
        val stepIndex = steps.indexOfFirst { step ->
            eventStepId?.let { id -> id == step.id }
                ?: (step.state == State.RUNNING || step.state == State.WAITING)
        }

        if (eventStepId != null && isExpandableStep(eventStepId)) {
            when (event) {
                is ProgressEvent.Started -> {
                    if (eventStepId == StepId.WriteAPK) {
                        resetDexCompileState()
                        writeApkStepStarted = true
                        if (!event.subSteps.isNullOrEmpty()) {
                            prepareSubSteps(eventStepId, event.subSteps)
                        } else {
                            stepSubSteps.remove(eventStepId)
                        }
                    } else {
                        if (!event.subSteps.isNullOrEmpty()) {
                            prepareSubSteps(eventStepId, event.subSteps)
                        } else {
                            stepSubSteps.remove(eventStepId)
                        }
                    }
                }
                is ProgressEvent.Progress -> {
                    val progress = event.current?.let { current -> current to event.total }
                    event.subSteps?.let { prepareSubSteps(eventStepId, it) }
                    if (!event.message.isNullOrBlank() || progress != null) {
                        updateSubStep(eventStepId, event.message, progress)
                    }
                }
                is ProgressEvent.Completed -> {
                    if (eventStepId == StepId.WriteAPK) {
                        writeApkStepStarted = false
                    }
                    finalizeSubSteps(eventStepId)
                }
                is ProgressEvent.Failed -> {
                    if (eventStepId == StepId.WriteAPK) {
                        writeApkStepStarted = false
                    }
                    finalizeSubSteps(
                        eventStepId,
                        failed = true,
                        errorMessage = event.error.message ?: event.error.type
                    )
                }
            }
        }

        if (stepIndex != -1) {
            val step = steps[stepIndex]
            val updatedStep = when (event) {
                is ProgressEvent.Started -> {
                    if (eventStepId is StepId.ExecutePatch) {
                        // Reseam emits a fresh start when rollback requires a rerun.
                        step.withState(State.RUNNING, message = null, progress = null)
                    } else if (step.state == State.COMPLETED || step.state == State.FAILED) {
                        null
                    } else {
                        step.withState(State.RUNNING)
                    }
                }

                is ProgressEvent.Progress -> {
                    if (step.state == State.COMPLETED || step.state == State.FAILED) {
                        null
                    } else {
                        val nextState = if (step.state == State.WAITING) State.RUNNING else step.state
                        val nextMessage = if (eventStepId == StepId.LoadPatches) {
                            null
                        } else {
                            event.message ?: step.message
                        }
                        step.withState(
                            state = nextState,
                            message = nextMessage,
                            progress = event.current?.let { event.current to event.total } ?: step.progress
                        )
                    }
                }

                is ProgressEvent.Completed -> {
                    val recoveredPatch = step.state == State.FAILED && eventStepId is StepId.ExecutePatch
                    if (step.state == State.FAILED && !recoveredPatch) {
                        null
                    } else {
                        step.withState(
                            State.COMPLETED,
                            message = if (recoveredPatch) null else step.message,
                            progress = null
                        )
                    }
                }

                is ProgressEvent.Failed -> {
                    if (isDuplicateFailureWrapper && steps.any { it.state == State.FAILED }) {
                        reconcileFailureState(event.error.message)
                        return
                    }
                    step.withState(
                        State.FAILED,
                        message = formatFailure(event.error),
                        progress = null
                    )
                }
            }

            if (updatedStep != null) {
                steps[stepIndex] = updatedStep
                if (event is ProgressEvent.Completed && updatedStep.state == State.COMPLETED) {
                    promoteImmediateSignStepIfNeeded(stepIndex)
                    promoteNextStepIfNeeded(stepIndex)
                }
            }
        }

        if (event is ProgressEvent.Failed) {
            if (!isDuplicateFailureWrapper) {
                lastPatchFailure = event.error
                lastPatchFailureStep = event.stepId?.let { it::class.java.simpleName } ?: "Unknown"
            }
            onFailure(event, isDuplicateFailureWrapper)
        }
    }

    private fun bufferLoadPatchesEventDuringSplitPreparation(event: ProgressEvent): Boolean {
        if (event.stepId != StepId.LoadPatches) return false
        if (!shouldDeferLoadPatchesEventUntilSplitComplete()) return false

        deferLoadPatchesUntilSplitComplete = true
        deferredLoadPatchesEvents += event
        pauseLoadPatchesVisualProgress()
        return true
    }

    private fun shouldDeferLoadPatchesEventUntilSplitComplete(): Boolean {
        val splitStep = steps.firstOrNull { it.id == StepId.PrepareSplitApk } ?: return false
        return splitStep.state != State.COMPLETED && splitStep.state != State.FAILED
    }

    private fun prepareSplitStepForIncomingEvent(event: ProgressEvent) {
        if (event.stepId != StepId.PrepareSplitApk) return
        if (steps.none { it.id == StepId.PrepareSplitApk }) {
            onSplitStepRequired()
        }
        if (event is ProgressEvent.Started && event.subSteps?.contains("Inspecting split APKs") == true) {
            val index = steps.indexOfFirst { it.id == StepId.PrepareSplitApk }
            if (index != -1 && (steps[index].state == State.COMPLETED || steps[index].state == State.FAILED)) {
                // A fresh Reseam attempt prepares its own components again; merged runtimes
                // keep their already prepared input when the patcher retries.
                steps[index] = steps[index].copy(state = State.WAITING, message = null, progress = null)
                stepSubSteps.remove(StepId.PrepareSplitApk)
            }
        }
        when (event) {
            is ProgressEvent.Started,
            is ProgressEvent.Progress -> pauseLoadPatchesForSplitPreparation()
            else -> Unit
        }
    }

    fun pauseLoadPatchesForSplitPreparation() {
        val loadIndex = steps.indexOfFirst { it.id == StepId.LoadPatches }
        if (loadIndex == -1) return

        val loadStep = steps[loadIndex]
        if (loadStep.state == State.WAITING || loadStep.state == State.FAILED) return

        if (deferredLoadPatchesStepSnapshot == null) {
            deferredLoadPatchesStepSnapshot = loadStep
        }
        deferLoadPatchesUntilSplitComplete = true
        pauseLoadPatchesVisualProgress()
    }

    private fun pauseLoadPatchesVisualProgress() {
        val loadIndex = steps.indexOfFirst { it.id == StepId.LoadPatches }
        if (loadIndex != -1) {
            val loadStep = steps[loadIndex]
            if (loadStep.state != State.FAILED) {
                steps[loadIndex] = loadStep.withState(
                    state = State.WAITING,
                    message = null,
                    progress = null
                )
            }
        }

        val splitIndex = steps.indexOfFirst { it.id == StepId.PrepareSplitApk }
        if (splitIndex == -1) return

        val splitStep = steps[splitIndex]
        if (splitStep.state == State.WAITING) {
            steps[splitIndex] = splitStep.withState(
                state = State.RUNNING,
                message = null,
                progress = null
            )
        }
    }

    private fun replayDeferredLoadPatchesEvents() {
        if (!deferLoadPatchesUntilSplitComplete) return
        val pending = deferredLoadPatchesEvents.toList()
        val snapshot = deferredLoadPatchesStepSnapshot
        deferredLoadPatchesEvents.clear()
        deferredLoadPatchesStepSnapshot = null
        deferLoadPatchesUntilSplitComplete = false
        if (pending.isEmpty() && snapshot != null) {
            val loadIndex = steps.indexOfFirst { it.id == StepId.LoadPatches }
            if (loadIndex != -1 && steps[loadIndex].state != State.FAILED) {
                steps[loadIndex] = snapshot
            }
            return
        }
        pending.forEach(::applyProgressEvent)
    }

    fun clearDeferredLoadPatchesEvents() {
        deferredLoadPatchesEvents.clear()
        deferredLoadPatchesStepSnapshot = null
        deferLoadPatchesUntilSplitComplete = false
    }

    private fun enforceSplitPreparationVisualPriority() {
        if (!shouldDeferLoadPatchesEventUntilSplitComplete()) return
        pauseLoadPatchesVisualProgress()
    }


    private fun shouldResetProgressStateForAutomaticRetry(event: ProgressEvent): Boolean {
        if (event !is ProgressEvent.Started || event.stepId != StepId.LoadPatches) return false

        val loadIndex = steps.indexOfFirst { it.id == StepId.LoadPatches }
        if (loadIndex == -1) return false

        val loadStep = steps[loadIndex]
        if (loadStep.state == State.COMPLETED || loadStep.state == State.FAILED) {
            return true
        }

        val preservedIds = setOf<StepId>(StepId.PrepareSplitApk)
        val resettableIds = steps.drop(loadIndex).map { it.id }.toSet() - preservedIds
        return steps.drop(loadIndex + 1).any { it.id !in preservedIds && it.state != State.WAITING } ||
            stepSubSteps.keys.any { it in resettableIds } ||
            writeApkStepStarted ||
            dexSubStepsReady ||
            pendingDexCompileLines.isNotEmpty()
    }

    private fun resetProgressStateForAutomaticRetry() {
        clearDeferredLoadPatchesEvents()
        val loadIndex = steps.indexOfFirst { it.id == StepId.LoadPatches }
        if (loadIndex == -1) return

        val preservedIds = setOf<StepId>(StepId.PrepareSplitApk)
        val resettableIds = steps.drop(loadIndex).map { it.id }.toSet() - preservedIds
        resetDexCompileState()
        lastPatchFailure = null
        lastPatchFailureStep = null
        onRetry()
        resetVisualProgress()

        steps.forEachIndexed { index, step ->
            if (index < loadIndex) {
                steps[index] = step.withState(
                    state = if (step.state == State.FAILED) State.COMPLETED else step.state,
                    progress = null
                )
                return@forEachIndexed
            }

            if (step.id in preservedIds) {
                steps[index] = step.withState(
                    state = if (step.state == State.FAILED) State.COMPLETED else step.state,
                    progress = null
                )
                return@forEachIndexed
            }

            steps[index] = step.withState(
                state = State.WAITING,
                message = null,
                progress = null
            )
        }

        stepSubSteps.keys
            .filter { it in resettableIds }
            .toList()
            .forEach(stepSubSteps::remove)
    }


    private fun RemoteError.matchesUnderlyingFailure(other: RemoteError): Boolean =
        this == other || (stackTrace.isNotBlank() && stackTrace == other.stackTrace)

    private fun formatFailure(error: RemoteError): String =
        if (error.type.contains("UserInteractionException")) {
            error.message ?: "Downloader search cancelled by user."
        } else error.stackTrace

    fun reconcileFailureState(failureMessage: String?) {
        stopLogProgress()
        val message = failureMessage?.takeIf { it.isNotBlank() }
        clearDeferredLoadPatchesEvents()
        resetDexCompileState()

        // A patch failure may already be visible while its parent or another active
        // row is still running. Always settle those rows when the worker stops.
        val hasFailure = steps.any { it.state == State.FAILED }
        val fallbackIndex = if (hasFailure) -1 else {
            steps.indexOfFirst { it.state == State.RUNNING }.takeIf { it != -1 }
                ?: steps.indexOfFirst { it.state == State.WAITING }
        }
        steps.forEachIndexed { index, step ->
            if (step.state == State.RUNNING || index == fallbackIndex) {
                steps[index] = step.withState(State.FAILED, message = message, progress = null)
            }
        }
        stepSubSteps.forEach { (id, details) ->
            if (steps.any { it.id == id && it.state == State.FAILED } &&
                details.none { it.state == State.FAILED }
            ) {
                finalizeSubSteps(id, failed = true, errorMessage = message)
            }
            details.forEachIndexed { index, detail ->
                details[index] = detail.stopRunning(State.FAILED, message)
            }
        }
    }

    fun snapshot() = PatcherProgressSnapshot(
        steps = steps.toList(),
        subStepsById = stepSubSteps.mapValues { it.value.toList() },
        progress = progress
    )

    fun clearFailure() {
        lastPatchFailure = null
        lastPatchFailureStep = null
    }

    fun markInitialStepRunning() {
        val index = steps.indexOfFirst { !it.hide && it.state == State.WAITING }
        if (index >= 0) {
            val step = steps[index]
            stepSubSteps.remove(step.id)
            steps[index] = step.withState(
                state = State.RUNNING,
                message = null,
                progress = null
            )
        }
    }

    private companion object {
        const val SKIPPED_SUBSTEP_PREFIX = "[skipped]"
        const val WRITE_APK_DEX_GROUP_TITLE = "Compiling DEX files"
    }
}
