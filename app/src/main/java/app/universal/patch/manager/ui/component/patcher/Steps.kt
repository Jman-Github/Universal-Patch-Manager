package app.universal.patch.manager.ui.component.patcher

import app.universal.patch.manager.ui.theme.LocalExpressiveDesign
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.universal.patch.manager.R
import app.universal.patch.manager.patcher.StepId
import app.universal.patch.manager.ui.component.ArrowButton
import app.universal.patch.manager.ui.component.LoadingIndicator
import app.universal.patch.manager.ui.model.State
import app.universal.patch.manager.ui.model.Step
import app.universal.patch.manager.ui.model.StepCategory
import app.universal.patch.manager.ui.model.StepDetail
import app.universal.patch.manager.ui.model.StepLog
import app.universal.patch.manager.ui.model.displayProgress
import app.universal.patch.manager.ui.model.asDisplayProgress
import kotlin.math.floor

// Credits: https://github.com/Aliucord/AliucordManager/blob/main/app/src/main/kotlin/com/aliucord/manager/ui/component/installer/InstallGroup.kt
@Composable
fun Steps(
    category: StepCategory,
    steps: List<Step>,
    subStepsById: Map<StepId, List<StepDetail>> = emptyMap(),
    isExpanded: Boolean = false,
    autoExpandRunning: Boolean = true,
    autoExpandRunningMainOnly: Boolean = false,
    onExpand: () -> Unit,
    onClick: () -> Unit,
    autoCollapseCompleted: Boolean = false,
    continueOnPatchError: Boolean = false
) {
    var autoCollapsed by rememberSaveable { mutableStateOf(false) }

    val hasFailedStep = remember(steps) {
        steps.any { it.state == State.FAILED }
    }
    val hasFailedSectionStep = remember(steps) {
        steps.any { it.id == StepId.ExecutePatches && it.state == State.FAILED }
    }
    val state = remember(category, steps, hasFailedStep, hasFailedSectionStep, continueOnPatchError) {
        when {
            steps.all { it.state == State.COMPLETED } -> State.COMPLETED
            steps.any { it.state == State.RUNNING } -> State.RUNNING
            hasFailedStep && (category != StepCategory.PATCHING || !continueOnPatchError || hasFailedSectionStep) -> State.FAILED
            else -> State.WAITING
        }
    }

    val filteredSteps = remember(steps) {
        val failedCount = steps.count { it.state == State.FAILED }

        steps.filter { step ->
            !step.hide || (step.state == State.FAILED && failedCount == 1)
        }
    }

    LaunchedEffect(state, hasFailedStep) {
        if (state != State.COMPLETED) {
            autoCollapsed = false
        }
        if (autoExpandRunning && (state == State.RUNNING || state == State.FAILED || hasFailedStep)) {
            onExpand()
        }
    }

    LaunchedEffect(autoCollapseCompleted, state, hasFailedStep) {
        if (autoCollapseCompleted && state == State.COMPLETED && !hasFailedStep && !autoCollapsed) {
            if (isExpanded) onClick()
            // Consume completion even when collapsed so the next tap can reopen the section.
            autoCollapsed = true
        }
    }

    Column(
        modifier = Modifier
            .clip(MaterialTheme.shapes.large)
            .fillMaxWidth()
            // Low sits almost on the page tone; steps use each design's regular card tone.
            .background(
                if (LocalExpressiveDesign.current) MaterialTheme.colorScheme.surfaceContainerHigh
                else MaterialTheme.colorScheme.surfaceContainer
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .clickable(enabled = true, onClick = onClick)
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            StepIcon(
                state = state,
                size = 24.dp,
                partialFailure = category == StepCategory.PATCHING && continueOnPatchError && hasFailedStep && state == State.WAITING
            )

            Text(stringResource(category.displayName))

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "${filteredSteps.count { it.state == State.COMPLETED }}/${filteredSteps.size}",
                style = MaterialTheme.typography.labelSmall
            )

            ArrowButton(modifier = Modifier.size(24.dp), expanded = isExpanded, onClick = null)
        }

        AnimatedVisibility(visible = isExpanded) {
            Column(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.background.copy(0.6f))
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            ) {
                filteredSteps.forEachIndexed { index, step ->
                    val subSteps = subStepsById[step.id].orEmpty()
                    val (progress, progressText) = if (subSteps.isNotEmpty()) {
                        step.progress?.let { (current, total) ->
                            total?.takeIf { it > 0L }?.let {
                                val fraction = (current.toFloat() / it).coerceIn(0f, 1f)
                                fraction to "${(fraction * 100).toInt()}%"
                            }
                        } ?: (null to null)
                    } else step.progress.asDisplayProgress()

                    if (subSteps.isNotEmpty()) {
                        ExpandableSubStep(
                            step = step,
                            subSteps = subSteps,
                            progress = progress,
                            progressText = progressText,
                            autoExpandSubSteps = autoExpandRunning && !autoExpandRunningMainOnly,
                            autoCollapseCompleted = autoCollapseCompleted,
                            isFirst = index == 0,
                            isLast = index == filteredSteps.lastIndex
                        )
                    } else {
                        SubStep(
                            name = step.title,
                            state = step.state,
                            message = step.message,
                            progress = progress,
                            progressText = progressText,
                            isFirst = index == 0,
                            isLast = index == filteredSteps.lastIndex,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpandableSubStep(
    step: Step,
    subSteps: List<StepDetail>,
    progress: Float?,
    progressText: String?,
    autoExpandSubSteps: Boolean,
    autoCollapseCompleted: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
) {
    var expanded by rememberSaveable(step.id.toString()) { mutableStateOf(false) }
    var autoCollapsed by rememberSaveable("${step.id}-auto") { mutableStateOf(false) }
    val completedSubSteps = subSteps.count { it.state == State.COMPLETED || it.skipped }

    LaunchedEffect(step.state) {
        if (step.state != State.COMPLETED) {
            autoCollapsed = false
        }
        if (autoExpandSubSteps && (step.state == State.RUNNING || step.state == State.FAILED)) {
            expanded = true
        }
    }

    LaunchedEffect(autoCollapseCompleted, step.state) {
        if (autoCollapseCompleted && step.state == State.COMPLETED && !autoCollapsed) {
            expanded = false
            autoCollapsed = true
        }
    }

    Column(
        modifier = Modifier
            .clickable { expanded = !expanded }
            .padding(top = if (isFirst) 10.dp else 8.dp, bottom = if (isLast) 20.dp else 8.dp)
            .padding(horizontal = 20.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StepIcon(
                size = 18.dp,
                state = step.state,
                progress = progress,
            )

            Text(
                text = step.title,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, true),
            )

            Text(
                text = "$completedSubSteps/${subSteps.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (progressText != null) {
                Text(
                    progressText,
                    style = MaterialTheme.typography.labelSmall
                )
            }

            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                ArrowButton(
                    modifier = Modifier.size(20.dp),
                    expanded = expanded,
                    onClick = null
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(start = 16.dp)
            ) {
                subSteps.forEachIndexed { index, detail ->
                    if (detail.expandable || detail.children.isNotEmpty()) {
                        ExpandableDetailSubStep(
                            detail = detail,
                            autoExpandSubSteps = autoExpandSubSteps,
                            autoCollapseCompleted = autoCollapseCompleted,
                            isFirst = index == 0,
                            isLast = index == subSteps.lastIndex,
                        )
                    } else {
                        val (subProgress, subProgressText) = detail.displayProgress()

                        SubStep(
                            name = detail.title,
                            state = detail.state,
                            message = detail.message,
                            log = detail.log,
                            progress = subProgress,
                            progressText = subProgressText,
                            skipped = detail.skipped,
                            isFirst = index == 0,
                            isLast = index == subSteps.lastIndex,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpandableDetailSubStep(
    detail: StepDetail,
    autoExpandSubSteps: Boolean,
    autoCollapseCompleted: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
) {
    val hasChildren = detail.children.isNotEmpty()
    var expanded by rememberSaveable(detail.title) { mutableStateOf(false) }
    var autoCollapsed by rememberSaveable("${detail.title}-auto") { mutableStateOf(false) }
    val completedChildren = detail.children.count { it.state == State.COMPLETED || it.skipped }
    val (progress, progressText) = detail.displayProgress()

    LaunchedEffect(detail.state, detail.children.size) {
        if (detail.state != State.COMPLETED) {
            autoCollapsed = false
        }
        if (autoExpandSubSteps &&
            hasChildren &&
            detail.expandable &&
            (detail.state == State.RUNNING || detail.state == State.FAILED || hasChildren)
        ) {
            expanded = true
        }
    }

    LaunchedEffect(autoCollapseCompleted, detail.state) {
        if (autoCollapseCompleted && detail.state == State.COMPLETED && !autoCollapsed) {
            expanded = false
            autoCollapsed = true
        }
    }

    Column(
        modifier = Modifier
            .clickable(enabled = hasChildren) { expanded = !expanded }
            .padding(top = if (isFirst) 10.dp else 8.dp, bottom = if (isLast) 20.dp else 8.dp)
            .padding(horizontal = 20.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StepIcon(
                size = 18.dp,
                state = detail.state,
                progress = progress,
            )

            Text(
                text = detail.title,
                style = MaterialTheme.typography.labelLarge.copy(
                    textDecoration = if (detail.skipped) TextDecoration.LineThrough else null
                ),
                color = if (detail.skipped) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, true),
            )

            if (detail.children.isNotEmpty()) {
                Text(
                    text = "$completedChildren/${detail.children.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (progressText != null) {
                Text(
                    progressText,
                    style = MaterialTheme.typography.labelSmall
                )
            }

            if (hasChildren) {
                Box(
                    modifier = Modifier.size(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    ArrowButton(
                        modifier = Modifier.size(20.dp),
                        expanded = expanded,
                        onClick = null
                    )
                }
            }
        }

        AnimatedVisibility(visible = hasChildren && expanded) {
            Column(
                modifier = Modifier.padding(start = 18.dp, top = 4.dp)
            ) {
                detail.children.forEachIndexed { index, child ->
                    val (childProgress, childProgressText) = child.displayProgress()
                    MiniSubStep(
                        name = child.title,
                        state = child.state,
                        progress = childProgress,
                        progressText = childProgressText,
                        skipped = child.skipped,
                        isFirst = index == 0,
                        isLast = index == detail.children.lastIndex,
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniSubStep(
    name: String,
    state: State,
    progress: Float? = null,
    progressText: String? = null,
    skipped: Boolean = false,
    isFirst: Boolean = false,
    isLast: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .padding(top = if (isFirst) 6.dp else 4.dp, bottom = if (isLast) 10.dp else 4.dp)
            .padding(start = 8.dp, end = 4.dp)
    ) {
        StepIcon(
            size = 14.dp,
            state = state,
            progress = progress,
        )

        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium.copy(
                textDecoration = if (skipped) TextDecoration.LineThrough else null
            ),
            color = if (skipped) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, true),
        )

        if (progressText != null) {
            Text(
                text = progressText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun SubStep(
    name: String,
    state: State,
    message: String? = null,
    progress: Float? = null,
    progressText: String? = null,
    skipped: Boolean = false,
    isFirst: Boolean = false,
    isLast: Boolean = false,
    log: StepLog? = null,
) {
    var messageExpanded by rememberSaveable { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .run {
                if (message != null)
                    clickable { messageExpanded = !messageExpanded }
                else this
            }
            .padding(top = if (isFirst) 10.dp else 8.dp, bottom = if (isLast) 20.dp else 8.dp)
            .padding(horizontal = 20.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StepIcon(
                size = 18.dp,
                state = state,
                progress = progress,
            )

            Text(
                text = name,
                style = MaterialTheme.typography.labelLarge.copy(
                    textDecoration = if (skipped) TextDecoration.LineThrough else null
                ),
                color = if (skipped) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, true),
            )

            when {
                message != null -> Box(
                    modifier = Modifier.size(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    ArrowButton(
                        modifier = Modifier.size(20.dp),
                        expanded = messageExpanded,
                        onClick = null
                    )
                }

                progressText != null -> Text(
                    progressText,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        AnimatedVisibility(visible = messageExpanded && message != null) {
            Text(
                text = message.orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(horizontal = 36.dp, vertical = 8.dp)
            )
        }
        log?.takeIf { it.entries.isNotEmpty() }?.let { PatcherStepLog(it) }
    }
}

@Composable
fun StepIcon(state: State, progress: Float? = null, size: Dp, partialFailure: Boolean = false) {
    val strokeWidth = Dp(floor(size.value / 10) + 1)
    val partialFailureColor = Color(0xFFE0A72E)

    Crossfade(targetState = state to partialFailure, label = "State CrossFade") { (stepState, showPartialFailure) ->
        when {
            showPartialFailure && stepState == State.WAITING -> {
                val description = stringResource(R.string.step_partial_failure)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(size)
                        .semantics {
                            contentDescription = description
                        }
                ) {
                    Icon(
                        Icons.Outlined.Circle,
                        contentDescription = null,
                        tint = partialFailureColor.copy(.86f),
                        modifier = Modifier.size(size)
                    )
                    Box(
                        modifier = Modifier
                            .size(
                                width = Dp(size.value * .52f),
                                height = Dp(size.value * .12f)
                            )
                            .background(partialFailureColor, MaterialTheme.shapes.extraSmall)
                    )
                }
            }

            stepState == State.COMPLETED -> Icon(
                Icons.Filled.CheckCircle,
                contentDescription = stringResource(R.string.step_completed),
                tint = Color(0xFF59B463),
                modifier = Modifier.size(size)
            )

            stepState == State.FAILED -> Icon(
                Icons.Filled.Cancel,
                contentDescription = stringResource(R.string.step_failed),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(size)
            )

            stepState == State.WAITING -> Icon(
                Icons.Outlined.Circle,
                contentDescription = stringResource(R.string.step_waiting),
                tint = MaterialTheme.colorScheme.onSurface.copy(.2f),
                modifier = Modifier.size(size)
            )

            stepState == State.RUNNING -> {
                LoadingIndicator(
                    modifier = stringResource(R.string.step_running).let { description ->
                        Modifier
                            .size(size)
                            .semantics {
                                contentDescription = description
                            }
                    },
                    progress = { progress },
                    strokeWidth = strokeWidth
                )
            }
        }
    }
}
