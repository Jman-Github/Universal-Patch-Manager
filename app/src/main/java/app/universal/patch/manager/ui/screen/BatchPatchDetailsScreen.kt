/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.universal.patch.manager.ui.screen

import android.content.Context
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.PostAdd
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.universal.patch.manager.R
import app.universal.patch.manager.domain.batch.BatchInstallOutcome
import app.universal.patch.manager.domain.batch.canInstallBatchItem
import app.universal.patch.manager.domain.batch.BatchItemState
import app.universal.patch.manager.domain.batch.BatchPhase
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.patcher.ProgressEvent
import app.universal.patch.manager.patcher.StepId
import app.universal.patch.manager.ui.component.AppScaffold
import app.universal.patch.manager.ui.component.AppTopBar
import app.universal.patch.manager.ui.component.TransparentLoadingDialog
import app.universal.patch.manager.ui.component.haptics.HapticExtendedFloatingActionButton
import app.universal.patch.manager.ui.component.patcher.PatcherInformation
import app.universal.patch.manager.ui.component.patcher.PatcherInformationCard
import app.universal.patch.manager.ui.component.patcher.PatcherResourceUsageCards
import app.universal.patch.manager.ui.component.patcher.ResourceUsageProgressLayout
import app.universal.patch.manager.ui.component.patcher.rememberResourceGraphState
import app.universal.patch.manager.ui.component.patcher.Steps
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.snapshots.SnapshotStateList
import app.universal.patch.manager.patcher.patch.PatchBundleType
import app.universal.patch.manager.ui.model.PatcherProgressTracker
import app.universal.patch.manager.ui.model.PatcherProgressSnapshot
import app.universal.patch.manager.ui.model.SelectedApp
import app.universal.patch.manager.patcher.parsePatcherSessionInfo
import app.universal.patch.manager.patcher.withFallback
import app.universal.patch.manager.ui.model.State
import app.universal.patch.manager.ui.model.Step
import app.universal.patch.manager.ui.model.StepCategory
import app.universal.patch.manager.ui.model.StepDetail
import app.universal.patch.manager.ui.model.signatureMetadataPatcherProgress
import app.universal.patch.manager.domain.manager.SignatureMetadataWorkflowProgress
import app.universal.patch.manager.ui.model.withState
import app.universal.patch.manager.ui.viewmodel.BatchPatcherViewModel
import app.universal.patch.manager.ui.viewmodel.PatcherViewModel
import app.universal.patch.manager.util.PatchSelection
import app.universal.patch.manager.util.mutableStateSetOf
import app.universal.patch.manager.util.saver.snapshotStateSetSaver
import app.universal.patch.manager.patcher.split.SplitApkPreparer
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

// Code adapted from Morphe, see third-party/NOTICE for more information
// https://github.com/MorpheApp/morphe-manager/pull/795
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchPatchDetailsScreen(
    packageName: String,
    onBackClick: () -> Unit,
    viewModel: BatchPatcherViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()
    val prefs: PreferencesManager = koinInject()
    val autoCollapsePatcherSteps by prefs.autoCollapsePatcherSteps.getAsState()
    val showPatcherMemoryUsageGraph by prefs.showPatcherMemoryUsageGraph.getAsState()
    val pinResourceGraphs by prefs.pinPatcherResourceGraphs.getAsState()
    val compactResourceGraphs by prefs.compactPatcherResourceGraphs.getAsState()
    val showGraphExtraInfo by prefs.showPatcherResourceGraphExtraInfo.getAsState()
    val showCompactGraphExtraInfo by prefs.showCompactPatcherResourceGraphExtraInfo.getAsState()
    val patcherInformationExpanded by prefs.patcherInformationExpanded.getAsState()
    val autoExpandRunningSteps by prefs.autoExpandRunningSteps.getAsState()
    val autoExpandRunningStepsExclusive by prefs.autoExpandRunningStepsExclusive.getAsState()
    val continueOnPatchError by prefs.continueOnPatchError.getAsState()
    val skipApkSigning by prefs.skipApkSigning.getAsState()
    val coroutineScope = rememberCoroutineScope()
    val useExclusiveAutoExpand =
        autoExpandRunningSteps && autoExpandRunningStepsExclusive
    val item = state?.items?.firstOrNull { it.packageName == packageName }
    val resourceGraphState = rememberResourceGraphState(
        state?.requestId, packageName, item?.patcherSessionInfo?.startedAtElapsedRealtimeMs
    )
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
    val awaitingProgress = item?.let {
        it.state == BatchItemState.RUNNING &&
            (it.input == null ||
                (it.progressEvents.isEmpty() && it.memoryUsageSamples.isEmpty()))
    } == true
    val showLoadingOverlay = state == null ||
        state?.phase == BatchPhase.PLANNING ||
        state?.phase == BatchPhase.CANCELLING ||
        awaitingProgress
    val resultActions = rememberBatchResultActions(item, viewModel)
    val canExport = item?.let {
        it.hasAvailablePatchedFile && !it.saving && !it.installing
    } == true
    val canUseLogs = item?.let {
        it.state == BatchItemState.SUCCEEDED || it.state == BatchItemState.FAILED
    } == true
    val activeInstallPackage = state?.activeInstallPackageName
    val isActiveInstall = item?.installing == true && item.packageName == activeInstallPackage
    val canInstallOrOpen = item?.let {
        !it.saving && (
            isActiveInstall ||
                (!it.installing &&
                    (it.installOutcome == BatchInstallOutcome.INSTALLED || it.hasAvailablePatchedFile))
            )
    } == true
    val installActionEnabled = item?.let {
        !it.saving && (isActiveInstall || it.installOutcome == BatchInstallOutcome.INSTALLED ||
            state?.canInstallBatchItem(it) == true)
    } == true
    AppScaffold(
        topBar = { scrollBehavior ->
            AppTopBar(
                title = item?.appName
                    ?: stringResource(R.string.batch_patch_progress_title),
                onBackClick = onBackClick,
                scrollBehavior = scrollBehavior
            )
        },
        bottomBar = {
            if (item != null) {
                BottomAppBar(
                    actions = {
                        IconButton(
                            onClick = resultActions.exportApk,
                            enabled = canExport
                        ) {
                            Icon(
                                Icons.Outlined.Save,
                                stringResource(R.string.save_apk)
                            )
                        }
                        IconButton(
                            onClick = resultActions.showLogs,
                            enabled = canUseLogs
                        ) {
                            Icon(
                                Icons.Outlined.PostAdd,
                                stringResource(R.string.save_logs)
                            )
                        }
                        IconButton(onClick = onBackClick) {
                            Icon(
                                Icons.Outlined.Check,
                                stringResource(R.string.done)
                            )
                        }
                    },
                    floatingActionButton = {
                        AnimatedVisibility(visible = canInstallOrOpen) {
                            HapticExtendedFloatingActionButton(
                                text = {
                                    Text(
                                        stringResource(
                                            when {
                                                isActiveInstall -> R.string.cancel
                                                item.installOutcome == BatchInstallOutcome.INSTALLED ->
                                                    R.string.open_app
                                                else -> R.string.install_app
                                            }
                                        )
                                    )
                                },
                                icon = {
                                    Icon(
                                        imageVector = when {
                                            isActiveInstall -> Icons.Outlined.Cancel
                                            item.installOutcome == BatchInstallOutcome.INSTALLED ->
                                                Icons.AutoMirrored.Outlined.OpenInNew
                                            else -> Icons.Outlined.FileDownload
                                        },
                                        contentDescription = stringResource(
                                            when {
                                                isActiveInstall -> R.string.cancel
                                                item.installOutcome == BatchInstallOutcome.INSTALLED ->
                                                    R.string.open_app
                                                else -> R.string.install_app
                                            }
                                        )
                                    )
                                },
                                onClick = resultActions.installOrOpen,
                                enabled = installActionEnabled,
                                shape = RoundedCornerShape(16.dp)
                            )
                        }
                    }
                )
            }
        }
    ) { padding ->
        if (showLoadingOverlay) return@AppScaffold

        if (item == null) {
            BatchDetailsMessage(
                text = stringResource(R.string.batch_patch_details_unavailable),
                modifier = Modifier.padding(padding)
            )
            return@AppScaffold
        }

        val input = item.input
        val context = LocalContext.current
        val progressUi = remember(
            input,
            item.selection,
            item.progressEvents,
            item.patcherProgress,
            item.patcherSessionInfo,
            item.patcherEngine,
            item.message,
            item.memoryUsageSamples,
            item.state,
            item.signatureWorkflow,
            item.signatureInjection,
            skipApkSigning
        ) {
            input?.takeIf {
                item.patcherProgress != null || item.progressEvents.isNotEmpty() || item.memoryUsageSamples.isNotEmpty() ||
                    !item.state.isTerminal
            }?.let { selectedApp ->
                buildBatchProgressUiState(
                    context = context,
                    selectedApp = selectedApp,
                    selectedPatches = item.selection,
                    splitStepActive = item.progressEvents.any {
                        it.stepId == StepId.PrepareSplitApk
                    },
                    skipApkSigning = skipApkSigning,
                    events = item.progressEvents,
                    snapshot = item.patcherProgress,
                    succeeded = item.state == BatchItemState.SUCCEEDED,
                    failed = item.state == BatchItemState.FAILED,
                    failureMessage = item.message,
                    isMorphe = item.patcherSessionInfo.bundleType
                        ?.let { it == PatchBundleType.MORPHE.name }
                        ?: item.patcherEngine?.contains("Morphe", ignoreCase = true) == true,
                    preserveSplitApks = item.patcherSessionInfo.bundleType
                        ?.let { it == PatchBundleType.RESEAM.name }
                        ?: item.patcherEngine?.contains("Reseam", ignoreCase = true) == true,
                    morpheBytecodeMode = item.patcherSessionInfo.morpheBytecodeMode,
                    cancelled = item.state == BatchItemState.CANCELLED,
                    cancelledMessage = context.getString(R.string.batch_patch_state_cancelled),
                    signatureInjectionEnabled = item.signatureWorkflow.enabled,
                    signatureInjection = item.signatureInjection.copy(
                        completed = item.signatureInjection.completed ||
                            (item.state == BatchItemState.SUCCEEDED && item.signatureWorkflow.injected)
                    )
                )
            }
        }
        val groupedSteps = remember(progressUi?.steps) {
            progressUi?.steps?.groupBy { it.category }.orEmpty()
        }
        val expandedCategories = rememberSaveable(
            saver = snapshotStateSetSaver()
        ) {
            mutableStateSetOf<StepCategory>()
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LinearProgressIndicator(
                progress = {
                    progressUi?.progress ?: if (item.state == BatchItemState.SUCCEEDED) 1f else 0f
                },
                modifier = Modifier.fillMaxWidth(),
                drawStopIndicator = {}
            )
            val pinGraphs = pinResourceGraphs && showPatcherMemoryUsageGraph && item.memoryUsageSamples.isNotEmpty()
            val resourceGraphs: @Composable () -> Unit = {
                PatcherResourceUsageCards(
                    compact = compactResourceGraphs,
                    showExtraInfo = if (compactResourceGraphs) showCompactGraphExtraInfo else showGraphExtraInfo,
                    samples = item.memoryUsageSamples,
                    isActive = item.state == BatchItemState.RUNNING,
                    graphState = resourceGraphState
                )
            }
            ResourceUsageProgressLayout(
                pinGraphs = pinGraphs,
                graphContent = resourceGraphs
            ) { progressModifier ->
                LazyColumn(
                    modifier = progressModifier,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    item(key = "app-info") {
                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(item.appName, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    item.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                item.message?.let {
                                    Text(it, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }

                    if (
                        !pinGraphs && showPatcherMemoryUsageGraph &&
                        item.memoryUsageSamples.isNotEmpty()
                    ) {
                        item(key = "memory-usage") {
                            resourceGraphs()
                        }
                    }

                    item(key = "patcher-information") {
                        val parsedSessionInfo = remember(item.logLines) {
                            parsePatcherSessionInfo(
                                item.logLines.map { line -> line.substringAfter("]: ", line) }
                            )
                        }
                        val sessionInfo = item.patcherSessionInfo.withFallback(parsedSessionInfo)
                        val activeBundleIds = remember(item.selection) {
                            item.selection.filterValues { patches -> patches.isNotEmpty() }.keys
                        }
                        val patchBundleLabels = remember(item.bundles, activeBundleIds) {
                            item.bundles
                                .filter { bundle -> bundle.uid in activeBundleIds }
                                .map { bundle ->
                                    bundle.version
                                        ?.takeIf(String::isNotBlank)
                                        ?.let { version -> "${bundle.name} $version" }
                                        ?: bundle.name
                                }
                        }
                        val localInput = item.input as? SelectedApp.Local
                        val fallbackSplitApk by produceState<Boolean?>(
                            initialValue = null,
                            key1 = localInput?.file
                        ) {
                            value = localInput?.file?.let { file ->
                                withContext(Dispatchers.IO) {
                                    SplitApkPreparer.isSplitArchive(file)
                                }
                            }
                        }
                        PatcherInformationCard(
                            information = PatcherInformation(
                                appVersion = item.version,
                                appVersionCode = item.versionCode,
                                patchCount = item.patchCount,
                                patchBundles = patchBundleLabels,
                                fallbackApkSizeBytes = localInput?.file
                                    ?.takeIf { it.isFile }
                                    ?.length(),
                                fallbackSplitApk = fallbackSplitApk,
                                fallbackPatcherEngine = item.patcherEngine,
                                session = sessionInfo
                            ),
                            expanded = patcherInformationExpanded,
                            onExpandedChange = { expanded ->
                                coroutineScope.launch {
                                    prefs.patcherInformationExpanded.update(expanded)
                                }
                            }
                        )
                    }

                    items(
                        items = groupedSteps.toList(),
                        key = { (category, _) -> category }
                    ) { (category, steps) ->
                        Steps(
                            category = category,
                            steps = steps,
                            subStepsById = progressUi?.subStepsById.orEmpty(),
                            isExpanded = expandedCategories.contains(category),
                            autoExpandRunning = autoExpandRunningSteps,
                            autoExpandRunningMainOnly = useExclusiveAutoExpand,
                            continueOnPatchError = continueOnPatchError,
                            onExpand = {
                                if (useExclusiveAutoExpand) {
                                    expandedCategories.clear()
                                }
                                expandedCategories.add(category)
                            },
                            onClick = {
                                if (expandedCategories.contains(category)) {
                                    expandedCategories.remove(category)
                                } else {
                                    expandedCategories.add(category)
                                }
                            },
                            autoCollapseCompleted = autoCollapsePatcherSteps
                        )
                    }

                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                        item(key = "legacy-memory-warning") {
                        }
                    }
                }
            }
        }
    }

    if (showLoadingOverlay && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) {
        TransparentLoadingDialog()
    }
}

@Composable
private fun BatchDetailsMessage(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private data class BatchProgressUiState(
    val steps: List<Step>,
    val subStepsById: Map<StepId, List<StepDetail>>,
    val progress: Float
)

private fun buildBatchProgressUiState(
    context: Context,
    selectedApp: SelectedApp,
    selectedPatches: PatchSelection,
    splitStepActive: Boolean,
    skipApkSigning: Boolean,
    events: List<ProgressEvent>,
    snapshot: PatcherProgressSnapshot?,
    succeeded: Boolean,
    failed: Boolean,
    failureMessage: String?,
    isMorphe: Boolean,
    preserveSplitApks: Boolean,
    morpheBytecodeMode: String?,
    cancelled: Boolean,
    cancelledMessage: String,
    signatureInjectionEnabled: Boolean,
    signatureInjection: SignatureMetadataWorkflowProgress
): BatchProgressUiState {
    val steps = (snapshot?.steps ?: PatcherViewModel.generateSteps(
        context = context,
        selectedApp = selectedApp,
        selectedPatches = selectedPatches,
        splitStepActive = splitStepActive,
        skipApkSigning = skipApkSigning,
        injectSignatureMetadata = signatureInjectionEnabled,
        preserveSplitApks = preserveSplitApks
    )).toMutableStateList()
    val subSteps = snapshot?.subStepsById
        ?.mapValues { it.value.toMutableStateList() }
        ?.toMutableMap()
        ?: mutableMapOf<StepId, SnapshotStateList<StepDetail>>()
    val visual = PatcherProgressTracker(
        steps = steps,
        stepSubSteps = subSteps,
        isMorpheSelection = { isMorphe },
        morpheBytecodeMode = { morpheBytecodeMode }
    )
    if (snapshot != null) {
        visual.progress = snapshot.progress
    } else {
        visual.markInitialStepRunning()
        events.forEach(visual::processProgressEventLocked)
    }
    when {
        succeeded -> visual.reconcileProgressStateAfterSuccess()
        cancelled || failed -> visual.reconcileFailureState(
            if (cancelled) cancelledMessage else failureMessage
        )
    }
    val displayed = signatureMetadataPatcherProgress(
        context, steps, subSteps, visual.progress, signatureInjectionEnabled, signatureInjection
    )
    return BatchProgressUiState(
        steps = displayed.steps,
        subStepsById = displayed.subStepsById,
        progress = displayed.progress
    )
}
