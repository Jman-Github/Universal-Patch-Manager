package app.universal.patch.manager.ui.screen

import app.universal.patch.manager.ui.component.minimalActionButtons
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.LayersClear
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.PlaylistAddCheck
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.net.Uri
import android.os.Build
import java.util.Locale
import kotlin.math.max
import app.universal.patch.manager.R
import app.universal.patch.manager.patcher.patch.Option
import app.universal.patch.manager.patcher.patch.PatchBundleInfo
import app.universal.patch.manager.patcher.patch.PatchInfo
import app.universal.patch.manager.patcher.patch.PatchLockState
import app.universal.patch.manager.domain.repository.PatchProfile
import app.universal.patch.manager.domain.repository.resolvePatchProfileAppVersion
import app.universal.patch.manager.ui.component.AppTopBar
import app.universal.patch.manager.ui.component.appVersionLabel
import app.universal.patch.manager.ui.component.suggestedVersionLabel
import app.universal.patch.manager.ui.component.CheckedFilterChip
import app.universal.patch.manager.ui.component.ExperimentalVersionBadge
import app.universal.patch.manager.ui.component.ExpandableText
import app.universal.patch.manager.ui.component.FullscreenDialog
import app.universal.patch.manager.ui.component.InterceptBackHandler
import app.universal.patch.manager.ui.component.LazyColumnWithScrollbar
import app.universal.patch.manager.ui.component.SearchBar
import app.universal.patch.manager.ui.component.SearchView
import app.universal.patch.manager.ui.component.AlertDialogExtended
import app.universal.patch.manager.ui.component.haptics.HapticCheckbox
import app.universal.patch.manager.ui.component.haptics.HapticExtendedFloatingActionButton
import app.universal.patch.manager.ui.component.haptics.HapticTab
import app.universal.patch.manager.ui.component.patches.OptionItem
import app.universal.patch.manager.ui.component.patches.SelectionWarningDialog
import app.universal.patch.manager.ui.model.PatchSelectionActionKey
import app.universal.patch.manager.ui.model.SupportedVersionInfo
import app.universal.patch.manager.ui.viewmodel.BundleSourceType
import app.universal.patch.manager.ui.viewmodel.PatchesSelectorViewModel
import app.universal.patch.manager.ui.viewmodel.PatchesSelectorViewModel.Companion.SHOW_INCOMPATIBLE
import app.universal.patch.manager.ui.viewmodel.PatchesSelectorViewModel.Companion.SHOW_NON_UNIVERSAL
import app.universal.patch.manager.ui.viewmodel.PatchesSelectorViewModel.Companion.SHOW_TYPE_FILTER
import app.universal.patch.manager.ui.viewmodel.PatchesSelectorViewModel.Companion.SHOW_UNIVERSAL
import app.universal.patch.manager.util.Options
import app.universal.patch.manager.util.consumeHorizontalScroll
import app.universal.patch.manager.util.PatchSelection
import app.universal.patch.manager.util.isScrollingUp
import app.universal.patch.manager.util.openUrl
import app.universal.patch.manager.util.toast
import kotlinx.coroutines.flow.collectLatest
import app.universal.patch.manager.util.transparentListItemColors
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import app.universal.patch.manager.ui.component.CenteredDialogTitle

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PatchesSelectorScreen(
    onSave: (PatchSelection?, Options, Set<String>) -> Unit,
    onBackClick: () -> Unit,
    viewModel: PatchesSelectorViewModel
) {
    val appInfo by viewModel.selectedAppInfo.collectAsStateWithLifecycle()
    val bundles by viewModel.bundlesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val bundleDisplayNames by viewModel.bundleDisplayNames.collectAsStateWithLifecycle(initialValue = emptyMap())
    val bundleTypes by viewModel.bundleTypes.collectAsStateWithLifecycle(initialValue = emptyMap<Int, BundleSourceType>())
    val profiles by viewModel.profiles.collectAsStateWithLifecycle(initialValue = emptyList<PatchProfile>())
    val suggestedVersionsByBundle by viewModel.suggestedVersionsByBundle.collectAsStateWithLifecycle(
        initialValue = emptyMap()
    )
    val newPatchNamesByBundle by viewModel.newPatchNamesByBundle.collectAsStateWithLifecycle(
        initialValue = emptyMap()
    )
    val pagerState = rememberPagerState(
        initialPage = 0,
        initialPageOffsetFraction = 0f
    ) {
        bundles.size
    }
    val settledPageIndex by remember(bundles.size) {
        derivedStateOf {
            pagerState.settledPage.coerceIn(0, bundles.lastIndex.coerceAtLeast(0))
        }
    }
    val swipeSyncedTabIndex by remember(bundles.size) {
        derivedStateOf {
            if (bundles.isEmpty()) return@derivedStateOf 0
            val pageIndex = if (pagerState.isScrollInProgress) {
                pagerState.targetPage
            } else {
                pagerState.currentPage
            }
            pageIndex.coerceIn(0, bundles.lastIndex)
        }
    }
    suspend fun scrollToBundlePage(targetIndex: Int, animated: Boolean = true) {
        if (targetIndex !in bundles.indices) return
        if (
            (pagerState.currentPage == targetIndex && !pagerState.isScrollInProgress) ||
            (pagerState.isScrollInProgress && pagerState.targetPage == targetIndex)
        ) return
        val canAnimateDirectly = abs(pagerState.currentPage - targetIndex) <= 1
        if (animated && canAnimateDirectly) {
            pagerState.animateScrollToPage(targetIndex)
        } else {
            pagerState.scrollToPage(targetIndex)
        }
    }
    val composableScope = rememberCoroutineScope()
    val textFieldState = rememberTextFieldState()
    val searchBarState = rememberSearchBarState()
    val query by remember {
        derivedStateOf { textFieldState.text.toString() }
    }
    val searchExpanded by remember {
        derivedStateOf { searchBarState.currentValue == SearchBarValue.Expanded }
    }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    val useFallbackSearch = remember {
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1
    }
    val updateQuery: (String) -> Unit = { value ->
        textFieldState.edit {
            replace(0, length, value)
        }
    }
    fun List<PatchInfo>.searched(searchQuery: String): List<PatchInfo> =
        if (searchQuery.isBlank()) this else filter { it.name.contains(searchQuery, true) }
    var showBottomSheet by rememberSaveable { mutableStateOf(false) }
    val actionOrderPref by viewModel.prefs.patchSelectionActionOrder.getAsState()
    val hiddenActionsPref by viewModel.prefs.patchSelectionHiddenActions.getAsState()
    val sortAlphabeticallyPref by viewModel.prefs.patchSelectionSortAlphabetical.getAsState()
    val sortDescendingPref by viewModel.prefs.patchSelectionSortDescending.getAsState()
    val sortSettingsModePref by viewModel.prefs.patchSelectionSortSettingsMode.getAsState()
    val sortSelectionModePref by viewModel.prefs.patchSelectionSortSelectionMode.getAsState()
    val searchEngineHost by viewModel.prefs.searchEngineHost.getAsState()
    val showVersionTags by viewModel.prefs.patchSelectionShowVersionTags.getAsState()
    val showOptionPreviews by viewModel.prefs.patchSelectionShowOptionPreviews.getAsState()
    var patchVersionsDialogState by remember { mutableStateOf<PatchVersionsDialogState?>(null) }
    val disablePatchSelectionTabSwipe by viewModel.prefs.disablePatchSelectionTabSwipe.getAsState()
    val preventAccidentalTouching by viewModel.prefs.preventAccidentalTouching.getAsState()
    val showPatchProfilesTab by viewModel.prefs.showPatchProfilesTab.getAsState()
    val collapseActionsOnSelection by viewModel.prefs.collapsePatchActionsOnSelection.getAsState()
    val orderedActionKeys = remember(actionOrderPref) {
        val parsed = actionOrderPref
            .split(',')
            .mapNotNull { PatchSelectionActionKey.fromStorageId(it.trim()) }
        PatchSelectionActionKey.ensureComplete(parsed)
    }
    val forcedHiddenActionIds = remember(showPatchProfilesTab) {
        if (showPatchProfilesTab) emptySet() else setOf(PatchSelectionActionKey.SAVE_PROFILE.storageId)
    }
    val visibleActionKeys = remember(orderedActionKeys, hiddenActionsPref, forcedHiddenActionIds) {
        orderedActionKeys.filterNot { it.storageId in hiddenActionsPref || it.storageId in forcedHiddenActionIds }
    }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val selectedBundleUids = remember { mutableStateListOf<Int>() }
    var showBundleDialog by rememberSaveable { mutableStateOf(false) }
    var showProfileNameDialog by rememberSaveable { mutableStateOf(false) }
    var pendingProfileName by rememberSaveable { mutableStateOf("") }
    var selectedProfileId by rememberSaveable { mutableStateOf<Int?>(null) }
    var bundleSelectionCustomized by rememberSaveable { mutableStateOf(false) }
    var isSavingProfile by remember { mutableStateOf(false) }
    data class ProfileVersionConflict(
        val profileId: Int,
        val profileName: String,
        val existingVersion: String?,
        val newVersion: String?
    )
    var versionConflict by remember { mutableStateOf<ProfileVersionConflict?>(null) }
    suspend fun saveProfileAndClose(
        name: String,
        bundles: Set<Int>,
        existingProfileId: Int?,
        appVersionOverride: String?,
        keepExistingVersion: Boolean = false,
        existingProfileVersion: String? = null
    ) {
        isSavingProfile = true
        val success = try {
            viewModel.savePatchProfile(
                name,
                bundles,
                existingProfileId,
                appVersionOverride,
                keepExistingProfileVersion = keepExistingVersion,
                existingProfileVersion = existingProfileVersion
            )
        } finally {
            isSavingProfile = false
        }
        if (success) {
            showProfileNameDialog = false
            showBundleDialog = false
            pendingProfileName = ""
            selectedBundleUids.clear()
            selectedProfileId = null
            bundleSelectionCustomized = false
        }
    }
    fun String.asVersionLabel(): String =
        if (startsWith("v", ignoreCase = true)) this else "v$this"

    val defaultPatchSelectionCount by viewModel.defaultSelectionCount
        .collectAsStateWithLifecycle(initialValue = 0)

    val selectedPatchCount by remember {
        derivedStateOf {
            viewModel.customPatchSelection?.values?.sumOf { it.size } ?: defaultPatchSelectionCount
        }
    }
    val hasAnySelection by remember {
        derivedStateOf {
            viewModel.customPatchSelection?.values?.any { it.isNotEmpty() }
                ?: (defaultPatchSelectionCount > 0)
        }
    }
    val typeFilterMask = SHOW_UNIVERSAL or SHOW_NON_UNIVERSAL
    val typeFilterActive by remember {
        derivedStateOf {
            viewModel.filter and SHOW_TYPE_FILTER != 0 || viewModel.filter and typeFilterMask != 0
        }
    }
    val nonUniversalSelected by remember {
        derivedStateOf {
            typeFilterActive && viewModel.filter and SHOW_NON_UNIVERSAL != 0
        }
    }
    val currentBundleHasSelection by remember {
        derivedStateOf {
            val bundle = bundles.getOrNull(settledPageIndex)
            bundle != null && viewModel.bundleHasSelection(bundle.uid)
        }
    }
    val showSaveButton by remember {
        derivedStateOf { hasAnySelection }
    }

    val patchLazyListStates = remember(bundles) { List(bundles.size) { LazyListState() } }
    val dialogsOpen = showBundleDialog || showProfileNameDialog
    var actionsExpanded by rememberSaveable { mutableStateOf(false) }
    var showResetConfirmation by rememberSaveable { mutableStateOf(false) }
    var sortAlphabetically by rememberSaveable { mutableStateOf(sortAlphabeticallyPref) }
    var sortDescending by rememberSaveable { mutableStateOf(sortDescendingPref) }
    var sortSettingsMode by rememberSaveable { mutableStateOf(sortSettingsModePref) }
    var sortSelectionMode by rememberSaveable { mutableStateOf(sortSelectionModePref) }
    val resolvedSortSettingsMode = remember(sortSettingsMode) {
        PatchSortSettingsMode.values().firstOrNull { it.name == sortSettingsMode } ?: PatchSortSettingsMode.None
    }
    val resolvedSortSelectionMode = remember(sortSelectionMode) {
        PatchSortSelectionMode.values().firstOrNull { it.name == sortSelectionMode } ?: PatchSortSelectionMode.None
    }
    LaunchedEffect(sortAlphabeticallyPref) {
        if (sortAlphabetically != sortAlphabeticallyPref) {
            sortAlphabetically = sortAlphabeticallyPref
        }
    }
    LaunchedEffect(sortDescendingPref) {
        if (sortDescending != sortDescendingPref) {
            sortDescending = sortDescendingPref
        }
    }
    LaunchedEffect(sortSettingsModePref) {
        if (sortSettingsMode != sortSettingsModePref) {
            sortSettingsMode = sortSettingsModePref
        }
    }
    LaunchedEffect(sortSelectionModePref) {
        if (sortSelectionMode != sortSelectionModePref) {
            sortSelectionMode = sortSelectionModePref
        }
    }
    LaunchedEffect(sortAlphabetically, sortAlphabeticallyPref) {
        if (sortAlphabetically != sortAlphabeticallyPref) {
            viewModel.prefs.patchSelectionSortAlphabetical.update(sortAlphabetically)
        }
    }
    LaunchedEffect(sortDescending, sortDescendingPref) {
        if (sortDescending != sortDescendingPref) {
            viewModel.prefs.patchSelectionSortDescending.update(sortDescending)
        }
    }
    LaunchedEffect(sortSettingsMode, sortSettingsModePref) {
        if (sortSettingsMode != sortSettingsModePref) {
            viewModel.prefs.patchSelectionSortSettingsMode.update(sortSettingsMode)
        }
    }
    LaunchedEffect(sortSelectionMode, sortSelectionModePref) {
        if (sortSelectionMode != sortSelectionModePref) {
            viewModel.prefs.patchSelectionSortSelectionMode.update(sortSelectionMode)
        }
    }
    LaunchedEffect(patchLazyListStates, collapseActionsOnSelection) {
        snapshotFlow { patchLazyListStates.any { it.isScrollInProgress } }
            .collectLatest { scrolling ->
                if (collapseActionsOnSelection && scrolling && actionsExpanded) {
                    actionsExpanded = false
                }
            }
    }
    LaunchedEffect(pagerState, collapseActionsOnSelection) {
        snapshotFlow { pagerState.settledPage }
            .collectLatest {
                if (collapseActionsOnSelection) {
                    actionsExpanded = false
                }
            }
    }
    fun handlePatchSelectorBack() {
        when {
            showBottomSheet -> {
                showBottomSheet = false
            }
            actionsExpanded -> {
                actionsExpanded = false
            }
            useFallbackSearch && searchActive -> {
                searchActive = false
            }
            !useFallbackSearch && searchExpanded -> {
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
                composableScope.launch {
                    searchBarState.animateToCollapsed()
                }
            }
            query.isNotBlank() -> updateQuery("")
            else -> onBackClick()
        }
    }
    InterceptBackHandler(enabled = !dialogsOpen) {
        handlePatchSelectorBack()
    }
    LaunchedEffect(searchActive, useFallbackSearch) {
        if (useFallbackSearch && searchActive) {
            actionsExpanded = false
        }
    }
    LaunchedEffect(searchExpanded, useFallbackSearch) {
        if (!useFallbackSearch && searchExpanded) {
            actionsExpanded = false
        }
    }
    LaunchedEffect(dialogsOpen, useFallbackSearch) {
        if (!dialogsOpen) return@LaunchedEffect
        if (useFallbackSearch) {
            searchActive = false
        } else {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            searchBarState.animateToCollapsed()
        }
    }

    fun selectDefaultProfileBundle() {
        selectedBundleUids.clear()
        val defaultBundleUid =
            bundles.getOrNull(settledPageIndex)?.uid ?: bundles.firstOrNull()?.uid
        defaultBundleUid?.let { selectedBundleUids.add(it) }
    }

    fun openProfileSaveDialog() {
        if (bundles.isEmpty() || isSavingProfile) return
        selectDefaultProfileBundle()
        bundleSelectionCustomized = false
        pendingProfileName = ""
        selectedProfileId = null
        if (useFallbackSearch && searchActive) {
            searchActive = false
        } else if (!useFallbackSearch && searchExpanded) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            composableScope.launch {
                searchBarState.animateToCollapsed()
            }
        }
        showBottomSheet = false
        showBundleDialog = true
    }

    if (showBottomSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val filterChipColors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
        )

        ModalBottomSheet(
            onDismissRequest = {
                showBottomSheet = false
            },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp)
            ) {
                Text(
                    text = stringResource(R.string.patch_selector_sheet_filter_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Text(
                    text = stringResource(R.string.patch_selector_sheet_filter_compat_title),
                    style = MaterialTheme.typography.titleMedium
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CheckedFilterChip(
                        selected = viewModel.filter and SHOW_INCOMPATIBLE == 0,
                        onClick = { viewModel.toggleFlag(SHOW_INCOMPATIBLE) },
                        colors = filterChipColors,
                        label = { Text(stringResource(R.string.this_version)) }
                    )
                }

                Spacer(modifier = Modifier.size(0.dp, 10.dp))

                Text(
                    text = stringResource(R.string.patch_selector_sheet_filter_type_title),
                    style = MaterialTheme.typography.titleMedium
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CheckedFilterChip(
                        selected = viewModel.showNewPatchesSection,
                        onClick = viewModel::toggleNewPatchesSection,
                        colors = filterChipColors,
                        label = { Text(stringResource(R.string.patch_selector_sheet_filter_new_patches)) }
                    )
                    CheckedFilterChip(
                        selected = typeFilterActive && viewModel.filter and SHOW_NON_UNIVERSAL != 0,
                        onClick = { viewModel.toggleTypeFlag(SHOW_NON_UNIVERSAL) },
                        colors = filterChipColors,
                        label = { Text(stringResource(R.string.non_universal)) }
                    )
                    if (viewModel.allowUniversalPatches) {
                        CheckedFilterChip(
                            selected = typeFilterActive && viewModel.filter and SHOW_UNIVERSAL != 0,
                            onClick = { viewModel.toggleTypeFlag(SHOW_UNIVERSAL) },
                            colors = filterChipColors,
                            label = { Text(stringResource(R.string.universal)) },
                        )
                    }
                }

                Spacer(modifier = Modifier.size(0.dp, 10.dp))

                Text(
                    text = stringResource(R.string.patch_selector_sheet_filter_sort_title),
                    style = MaterialTheme.typography.titleMedium,
                )

                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CheckedFilterChip(
                        selected = sortAlphabetically && !sortDescending,
                        onClick = {
                            if (sortAlphabetically && !sortDescending) {
                                sortAlphabetically = false
                            } else {
                                sortAlphabetically = true
                                sortDescending = false
                            }
                        },
                        colors = filterChipColors,
                        label = { Text(stringResource(R.string.patch_selector_sheet_filter_sort_alphabetical)) }
                    )

                    CheckedFilterChip(
                        selected = sortAlphabetically && sortDescending,
                        onClick = {
                            if (sortAlphabetically && sortDescending) {
                                sortAlphabetically = false
                                sortDescending = false
                            } else {
                                sortAlphabetically = true
                                sortDescending = true
                            }
                        },
                        colors = filterChipColors,
                        label = { Text(stringResource(R.string.patch_selector_sheet_filter_sort_za)) }
                    )

                    CheckedFilterChip(
                        selected = resolvedSortSelectionMode == PatchSortSelectionMode.EnabledFirst,
                        onClick = {
                            sortSelectionMode =
                                if (resolvedSortSelectionMode == PatchSortSelectionMode.EnabledFirst) {
                                    PatchSortSelectionMode.None.name
                                } else {
                                    PatchSortSelectionMode.EnabledFirst.name
                                }
                        },
                        colors = filterChipColors,
                        label = { Text(stringResource(R.string.patch_selector_sheet_filter_sort_enabled_first)) }
                    )

                    CheckedFilterChip(
                        selected = resolvedSortSelectionMode == PatchSortSelectionMode.DisabledFirst,
                        onClick = {
                            sortSelectionMode =
                                if (resolvedSortSelectionMode == PatchSortSelectionMode.DisabledFirst) {
                                    PatchSortSelectionMode.None.name
                                } else {
                                    PatchSortSelectionMode.DisabledFirst.name
                                }
                        },
                        colors = filterChipColors,
                        label = { Text(stringResource(R.string.patch_selector_sheet_filter_sort_disabled_first)) }
                    )

                    CheckedFilterChip(
                        selected = resolvedSortSettingsMode == PatchSortSettingsMode.HasSettings,
                        onClick = {
                            sortSettingsMode = if (resolvedSortSettingsMode == PatchSortSettingsMode.HasSettings) {
                                PatchSortSettingsMode.None.name
                            } else {
                                PatchSortSettingsMode.HasSettings.name
                            }
                        },
                        colors = filterChipColors,
                        label = { Text(stringResource(R.string.patch_selector_sheet_filter_sort_has_settings)) }
                    )

                    CheckedFilterChip(
                        selected = resolvedSortSettingsMode == PatchSortSettingsMode.NoSettings,
                        onClick = {
                            sortSettingsMode = if (resolvedSortSettingsMode == PatchSortSettingsMode.NoSettings) {
                                PatchSortSettingsMode.None.name
                            } else {
                                PatchSortSettingsMode.NoSettings.name
                            }
                        },
                        colors = filterChipColors,
                        label = { Text(stringResource(R.string.patch_selector_sheet_filter_sort_no_settings)) }
                    )
                }
            }
        }
    }

    val currentAppVersionLabel = viewModel.currentAppVersion?.let { version ->
        appVersionLabel(versionName = version, appInfo = appInfo)
    } ?: stringResource(R.string.any_version)
    if (viewModel.compatibleVersions.isNotEmpty())
        IncompatiblePatchDialog(
            appVersion = currentAppVersionLabel,
            compatibleVersions = viewModel.compatibleVersions,
            onDismissRequest = viewModel::dismissDialogs
        )
    var showIncompatiblePatchesDialog by rememberSaveable {
        mutableStateOf(false)
    }
    if (showIncompatiblePatchesDialog)
        IncompatiblePatchesDialog(
            appVersion = currentAppVersionLabel,
            onDismissRequest = { showIncompatiblePatchesDialog = false }
        )

    viewModel.optionsDialog?.let { (bundle, patch) ->
        OptionsDialog(
            onDismissRequest = viewModel::dismissDialogs,
            patch = patch,
            values = viewModel.getOptions(bundle, patch),
            reset = { viewModel.resetOptions(bundle, patch) },
            set = { key, value -> viewModel.setOption(bundle, patch, key, value) },
            selectionWarningEnabled = viewModel.selectionWarningEnabled
        )
    }

    if (showBundleDialog) {
        PatchProfileBundleDialog(
            bundles = bundles,
            bundleDisplayNames = bundleDisplayNames,
            bundleTypes = bundleTypes,
            selectedBundleUids = selectedBundleUids,
            onSelectionChanged = { bundleSelectionCustomized = true },
            onDismiss = {
                showBundleDialog = false
                selectedBundleUids.clear()
                pendingProfileName = ""
                selectedProfileId = null
                bundleSelectionCustomized = false
            },
            onConfirm = {
                if (selectedBundleUids.isNotEmpty()) {
                    showBundleDialog = false
                    showProfileNameDialog = true
                }
            }
        )
    }

    if (showProfileNameDialog) {
        PatchProfileNameDialog(
            name = pendingProfileName,
            onNameChange = { pendingProfileName = it },
            isSaving = isSavingProfile,
            profiles = profiles,
            selectedProfileId = selectedProfileId,
            onProfileSelected = { profile ->
                if (profile == null) {
                    selectedProfileId = null
                    if (!bundleSelectionCustomized) {
                        selectDefaultProfileBundle()
                    }
                } else {
                    selectedProfileId = profile.uid
                    pendingProfileName = profile.name
                    if (!bundleSelectionCustomized) {
                        val availableBundleUids = bundles.mapTo(mutableSetOf()) { it.uid }
                        val savedBundleUids = profile.payload.bundles
                            .map { it.bundleUid }
                            .filter { it in availableBundleUids }
                            .distinct()
                        if (savedBundleUids.isNotEmpty()) {
                            selectedBundleUids.clear()
                            selectedBundleUids.addAll(savedBundleUids)
                        }
                    }
                }
            },
            onDismiss = {
                if (isSavingProfile) return@PatchProfileNameDialog
                showProfileNameDialog = false
                selectedBundleUids.clear()
                pendingProfileName = ""
                selectedProfileId = null
                bundleSelectionCustomized = false
            },
            onConfirm = {
                if (pendingProfileName.isBlank() || isSavingProfile) return@PatchProfileNameDialog
                composableScope.launch {
                    val selectedId = selectedProfileId
                    val targetProfile = selectedId?.let { id -> profiles.firstOrNull { it.uid == id } }
                    if (selectedId != null && targetProfile != null) {
                        val resolvedVersion = viewModel.previewResolvedAppVersion(selectedBundleUids.toSet())
                        val existingVersion = resolvePatchProfileAppVersion(
                            appVersion = targetProfile.appVersion,
                            apkPath = targetProfile.apkPath,
                            apkVersion = targetProfile.apkVersion,
                            useSelectedApkVersion = targetProfile.useSelectedApkVersion
                        )
                        if (resolvedVersion != existingVersion) {
                            versionConflict = ProfileVersionConflict(
                                profileId = selectedId,
                                profileName = targetProfile.name,
                                existingVersion = existingVersion,
                                newVersion = resolvedVersion
                            )
                            return@launch
                        }
                    }
                    saveProfileAndClose(
                        name = pendingProfileName.trim(),
                        bundles = selectedBundleUids.toSet(),
                        existingProfileId = selectedId,
                        appVersionOverride = null,
                        keepExistingVersion = selectedId != null,
                        existingProfileVersion = targetProfile?.let { profile ->
                            resolvePatchProfileAppVersion(
                                appVersion = profile.appVersion,
                                apkPath = profile.apkPath,
                                apkVersion = profile.apkVersion,
                                useSelectedApkVersion = profile.useSelectedApkVersion
                            )
                        }
                    )
                }
            }
        )
    }

    versionConflict?.let { conflict ->
        val existingLabel = conflict.existingVersion?.asVersionLabel()
            ?: stringResource(R.string.bundle_version_all_versions)
        val newLabel = conflict.newVersion?.asVersionLabel()
            ?: stringResource(R.string.bundle_version_all_versions)
        AlertDialog(
            onDismissRequest = { if (!isSavingProfile) versionConflict = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (isSavingProfile) return@TextButton
                        composableScope.launch {
                            saveProfileAndClose(
                                name = pendingProfileName.trim(),
                                bundles = selectedBundleUids.toSet(),
                                existingProfileId = conflict.profileId,
                                appVersionOverride = conflict.newVersion,
                                keepExistingVersion = false,
                                existingProfileVersion = conflict.existingVersion
                            )
                            versionConflict = null
                        }
                    }
                ) {
                    Text(stringResource(R.string.patch_profile_version_conflict_use_new))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (isSavingProfile) return@TextButton
                        composableScope.launch {
                            saveProfileAndClose(
                                name = pendingProfileName.trim(),
                                bundles = selectedBundleUids.toSet(),
                                existingProfileId = conflict.profileId,
                                appVersionOverride = conflict.existingVersion,
                                keepExistingVersion = true,
                                existingProfileVersion = conflict.existingVersion
                            )
                            versionConflict = null
                        }
                    }
                ) {
                    Text(stringResource(R.string.patch_profile_version_conflict_keep_existing))
                }
            },
            title = { CenteredDialogTitle(stringResource(R.string.patch_profile_version_conflict_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.patch_profile_version_conflict_message,
                        existingLabel,
                        newLabel
                    )
                )
            }
        )
    }

    var showSelectionWarning by rememberSaveable { mutableStateOf(false) }
    val missingPatchNames = viewModel.missingPatchNames
    var showMissingPatchReminder by rememberSaveable(missingPatchNames) {
        mutableStateOf(!missingPatchNames.isNullOrEmpty())
    }
    var pendingSelectionConfirmation by remember { mutableStateOf<SelectionConfirmation?>(null) }

    if (showSelectionWarning)
        SelectionWarningDialog(onDismiss = { showSelectionWarning = false })

    if (showMissingPatchReminder && !missingPatchNames.isNullOrEmpty()) {
        val reminderList = missingPatchNames.joinToString(separator = "\nâ€¢ ", prefix = "â€¢ ")
        AlertDialog(
            onDismissRequest = { showMissingPatchReminder = false },
            title = { CenteredDialogTitle(stringResource(R.string.patch_selector_missing_patch_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.patch_selector_missing_patch_message,
                        reminderList
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { showMissingPatchReminder = false }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }
    if (viewModel.showMixedPatchBundlesDialog) {
        AlertDialogExtended(
            onDismissRequest = viewModel::dismissMixedPatchBundlesDialog,
            confirmButton = {
                TextButton(onClick = viewModel::dismissMixedPatchBundlesDialog) {
                    Text(stringResource(R.string.ok))
                }
            },
            title = {
                Text(
                    text = stringResource(R.string.mixed_patch_bundles_title),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Start
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.mixed_patch_bundles_description),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Start
                )
            }
        )
    }
    if (viewModel.showMixedRevancedPatcherVersionsDialog) {
        AlertDialogExtended(
            onDismissRequest = viewModel::dismissMixedRevancedPatcherVersionsDialog,
            confirmButton = {
                TextButton(onClick = viewModel::dismissMixedRevancedPatcherVersionsDialog) {
                    Text(stringResource(R.string.ok))
                }
            },
            title = {
                Text(
                    text = stringResource(R.string.mixed_revanced_patcher_versions_title),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Start
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.mixed_revanced_patcher_versions_description),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Start
                )
            }
        )
    }
    val disableActionConfirmations by viewModel.prefs.disablePatchSelectionConfirmations.getAsState()
    val actionPopupProperties = remember(collapseActionsOnSelection) {
        PopupProperties(
            focusable = collapseActionsOnSelection,
            dismissOnBackPress = collapseActionsOnSelection,
            dismissOnClickOutside = collapseActionsOnSelection
        )
    }

    fun requestConfirmation(
        @StringRes title: Int,
        message: String,
        icon: ImageVector? = null,
        onConfirm: () -> Unit
    ) {
        if (disableActionConfirmations) {
            onConfirm()
        } else {
            pendingSelectionConfirmation = SelectionConfirmation(title, message, icon, onConfirm)
        }
    }

    if (showResetConfirmation && disableActionConfirmations) {
        showResetConfirmation = false
    }

    if (showResetConfirmation) {
        val profileNote = stringResource(R.string.patch_selection_reset_all_dialog_description)
            .substringAfter("\n\n", "")
            .trim()
        val resetMessage = buildString {
            append(stringResource(R.string.patch_selection_reset_dialog_message))
            if (profileNote.isNotEmpty()) {
                appendLine()
                appendLine()
                append(profileNote)
            }
        }

        AlertDialogExtended(
            onDismissRequest = { showResetConfirmation = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetConfirmation = false
                        viewModel.reset()
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            icon = { Icon(Icons.Outlined.Restore, null) },
            title = { CenteredDialogTitle(stringResource(R.string.patch_selection_reset_dialog_title)) },
            text = { CenteredDialogText(resetMessage) }
        )
    }

    if (!disableActionConfirmations) {
        pendingSelectionConfirmation?.let { confirmation ->
            AlertDialogExtended(
                onDismissRequest = { pendingSelectionConfirmation = null },
                confirmButton = {
                    TextButton(onClick = {
                        confirmation.onConfirm()
                        pendingSelectionConfirmation = null
                    }) {
                        Text(stringResource(R.string.confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingSelectionConfirmation = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
                icon = confirmation.icon?.let { icon ->
                    { Icon(icon, null) }
                },
                title = { CenteredDialogText(stringResource(confirmation.title)) },
                text = { CenteredDialogText(confirmation.message) }
            )
        }
    }

    fun LazyListScope.patchList(
        uid: Int,
        patches: List<PatchInfo>,
        visible: Boolean,
        compatible: Boolean,
        suggestedVersion: String?,
        header: (@Composable () -> Unit)? = null
    ) {
        if (patches.isNotEmpty() && visible) {
            fun PatchInfo.sortNameKey(): String = name.lowercase(Locale.ROOT)
            fun PatchInfo.hasSettings(): Boolean = options?.isNotEmpty() == true

            val filteredPatches = when (resolvedSortSettingsMode) {
                PatchSortSettingsMode.HasSettings -> patches.filter { it.hasSettings() }
                PatchSortSettingsMode.NoSettings -> patches.filterNot { it.hasSettings() }
                PatchSortSettingsMode.None -> patches
            }

            fun PatchInfo.selectionSortRank(): Int = when (resolvedSortSelectionMode) {
                PatchSortSelectionMode.EnabledFirst -> if (viewModel.isSelected(uid, this)) 0 else 1
                PatchSortSelectionMode.DisabledFirst -> if (viewModel.isSelected(uid, this)) 1 else 0
                PatchSortSelectionMode.None -> 0
            }

            val sortedPatches = filteredPatches
                .withIndex()
                .sortedWith { left, right ->
                    val selectionComparison =
                        left.value.selectionSortRank().compareTo(right.value.selectionSortRank())
                    if (selectionComparison != 0) return@sortedWith selectionComparison

                    if (sortAlphabetically) {
                        val nameComparison = if (sortDescending) {
                            right.value.sortNameKey().compareTo(left.value.sortNameKey())
                        } else {
                            left.value.sortNameKey().compareTo(right.value.sortNameKey())
                        }
                        if (nameComparison != 0) return@sortedWith nameComparison
                    }

                    left.index.compareTo(right.index)
                }
                .map { it.value }

            header?.let {
                item(contentType = 0) {
                    it()
                }
            }

            items(
                items = sortedPatches,
                key = { patchSelectionIdentity(uid, it.name) },
                contentType = { 1 }
            ) { patch ->
                PatchItem(
                    identity = patchSelectionIdentity(uid, patch.name),
                    patch = patch,
                    onOptionsDialog = { viewModel.optionsDialog = uid to patch },
                    onShowVersionsDialog = { patchVersionsDialogState = it },
                    selected = compatible && viewModel.isSelected(
                        uid,
                        patch
                    ),
                    lockState = viewModel.lockState(uid, patch),
                    searchEngineHost = searchEngineHost,
                    showVersionTags = showVersionTags,
                    showOptionPreviews = showOptionPreviews,
                    onToggle = {
                        when {
                            // Open incompatible dialog if the patch is not supported
                            !compatible -> viewModel.openIncompatibleDialog(patch)

                            // Show selection warning if enabled
                            viewModel.selectionWarningEnabled -> showSelectionWarning = true

                            // Toggle the patch otherwise
                            else -> {
                                viewModel.togglePatch(uid, patch)
                                if (collapseActionsOnSelection) {
                                    actionsExpanded = false
                                }
                            }
                        }
                    },
                    compatible = compatible,
                    packageName = viewModel.appPackageName,
                    optionValues = viewModel.getOptions(uid, patch),
                    suggestedVersion = suggestedVersion
                )
            }
        }
    }

    fun LazyListScope.patchSections(
        uid: Int,
        regularPatches: List<PatchInfo>,
        incompatiblePatches: List<PatchInfo>,
        universalPatches: List<PatchInfo>,
        suggestedVersion: String?
    ) {
        val newPatchNames = if (viewModel.showNewPatchesSection) {
            newPatchNamesByBundle[uid].orEmpty()
        } else {
            emptySet()
        }
        val showNewSection = newPatchNames.isNotEmpty()
        val newCompatiblePatches = if (showNewSection) {
            (regularPatches + universalPatches).filter { it.name in newPatchNames }
        } else {
            emptyList()
        }
        val newIncompatiblePatches = if (
            showNewSection &&
            viewModel.filter and SHOW_INCOMPATIBLE != 0
        ) {
            incompatiblePatches.filter { it.name in newPatchNames }
        } else {
            emptyList()
        }
        var newHeaderShown = false
        fun newPatchHeader(): (@Composable () -> Unit)? {
            if (newHeaderShown) return null
            newHeaderShown = true
            return {
                ListHeader(title = stringResource(R.string.patch_selector_sheet_filter_new_patches))
            }
        }

        patchList(
            uid = uid,
            patches = newCompatiblePatches,
            visible = showNewSection,
            compatible = true,
            suggestedVersion = suggestedVersion,
            header = if (newCompatiblePatches.isNotEmpty()) newPatchHeader() else null
        )
        patchList(
            uid = uid,
            patches = newIncompatiblePatches,
            visible = showNewSection,
            compatible = viewModel.allowIncompatiblePatches,
            suggestedVersion = suggestedVersion,
            header = if (newIncompatiblePatches.isNotEmpty()) newPatchHeader() else null
        )

        val regularVisible = if (showNewSection) {
            regularPatches.filterNot { it.name in newPatchNames }
        } else {
            regularPatches
        }
        val incompatibleVisible = if (showNewSection) {
            incompatiblePatches.filterNot { it.name in newPatchNames }
        } else {
            incompatiblePatches
        }
        val universalVisible = if (showNewSection) {
            universalPatches.filterNot { it.name in newPatchNames }
        } else {
            universalPatches
        }

        if (nonUniversalSelected) {
            if (regularVisible.isNotEmpty()) {
                item(contentType = 0) {
                    ListHeader(title = stringResource(R.string.regular_patches))
                }
            }
            patchList(
                uid = uid,
                patches = regularVisible,
                visible = true,
                compatible = true,
                suggestedVersion = suggestedVersion
            )
            patchList(
                uid = uid,
                patches = incompatibleVisible,
                visible = viewModel.filter and SHOW_INCOMPATIBLE != 0,
                compatible = viewModel.allowIncompatiblePatches,
                suggestedVersion = suggestedVersion
            ) {
                ListHeader(
                    title = stringResource(R.string.incompatible_patches),
                    onHelpClick = { showIncompatiblePatchesDialog = true }
                )
            }
            patchList(
                uid = uid,
                patches = universalVisible,
                visible = true,
                compatible = true,
                suggestedVersion = suggestedVersion
            ) {
                ListHeader(title = stringResource(R.string.universal_patches))
            }
        } else {
            patchList(
                uid = uid,
                patches = universalVisible,
                visible = true,
                compatible = true,
                suggestedVersion = suggestedVersion
            ) {
                ListHeader(title = stringResource(R.string.universal_patches))
            }
            if (regularVisible.isNotEmpty()) {
                item(contentType = 0) {
                    ListHeader(title = stringResource(R.string.regular_patches))
                }
            }
            patchList(
                uid = uid,
                patches = regularVisible,
                visible = true,
                compatible = true,
                suggestedVersion = suggestedVersion
            )
            patchList(
                uid = uid,
                patches = incompatibleVisible,
                visible = viewModel.filter and SHOW_INCOMPATIBLE != 0,
                compatible = viewModel.allowIncompatiblePatches,
                suggestedVersion = suggestedVersion
            ) {
                ListHeader(
                    title = stringResource(R.string.incompatible_patches),
                    onHelpClick = { showIncompatiblePatchesDialog = true }
                )
            }
        }
    }

    val currentBundle = bundles.getOrNull(settledPageIndex)
    val currentBundleDisplayName = currentBundle?.let { bundleDisplayNames[it.uid] ?: it.name }
    val warningEnabled = viewModel.selectionWarningEnabled

    val actionSpecs = visibleActionKeys.mapNotNull { key ->
        when (key) {
            PatchSelectionActionKey.UNDO -> PatchActionSpec(
                key = key,
                icon = Icons.AutoMirrored.Outlined.Undo,
                contentDescription = R.string.patch_selection_button_label_undo_action,
                label = R.string.patch_selection_button_label_undo_action,
                enabled = viewModel.canUndo,
                onClick = viewModel::undoAction
            )

            PatchSelectionActionKey.REDO -> PatchActionSpec(
                key = key,
                icon = Icons.AutoMirrored.Outlined.Redo,
                contentDescription = R.string.patch_selection_button_label_redo_action,
                label = R.string.patch_selection_button_label_redo_action,
                enabled = viewModel.canRedo,
                onClick = viewModel::redoAction
            )

            PatchSelectionActionKey.SELECT_BUNDLE -> PatchActionSpec(
                key = key,
                icon = Icons.AutoMirrored.Outlined.PlaylistAddCheck,
                contentDescription = R.string.patch_selection_button_label_select_bundle,
                label = R.string.patch_selection_button_label_select_bundle,
                enabled = currentBundle != null
            ) spec@{
                if (warningEnabled) {
                    showSelectionWarning = true
                    return@spec
                }
                val bundle = currentBundle ?: return@spec
                val bundleName = currentBundleDisplayName ?: bundle.name
                requestConfirmation(
                    title = R.string.patch_selection_confirm_select_bundle_title,
                    message = context.getString(
                        R.string.patch_selection_confirm_select_bundle_message,
                        bundleName
                    )
                ) {
                    viewModel.selectBundle(bundle.uid, bundleName)
                }
            }

            PatchSelectionActionKey.SELECT_ALL -> PatchActionSpec(
                key = key,
                icon = Icons.Outlined.DoneAll,
                contentDescription = R.string.patch_selection_button_label_select_all,
                label = R.string.patch_selection_button_label_select_all,
                enabled = bundles.isNotEmpty()
            ) {
                if (warningEnabled) {
                    showSelectionWarning = true
                } else {
                    requestConfirmation(
                        title = R.string.patch_selection_confirm_select_all_title,
                        message = context.getString(R.string.patch_selection_confirm_select_all_message),
                        onConfirm = viewModel::selectAll
                    )
                }
            }

            PatchSelectionActionKey.DESELECT_BUNDLE -> PatchActionSpec(
                key = key,
                icon = Icons.Outlined.LayersClear,
                contentDescription = R.string.deselect_bundle,
                label = R.string.patch_selection_button_label_bundle,
                enabled = currentBundle != null && currentBundleHasSelection
            ) spec@{
                if (warningEnabled) {
                    showSelectionWarning = true
                    return@spec
                }
                val bundle = currentBundle ?: return@spec
                val bundleName = currentBundleDisplayName ?: bundle.name
                requestConfirmation(
                    title = R.string.patch_selection_confirm_deselect_bundle_title,
                    message = context.getString(
                        R.string.patch_selection_confirm_deselect_bundle_message,
                        bundleName
                    )
                ) {
                    viewModel.deselectBundle(bundle.uid, bundleName)
                }
            }

            PatchSelectionActionKey.DESELECT_ALL -> PatchActionSpec(
                key = key,
                icon = Icons.Outlined.ClearAll,
                contentDescription = R.string.deselect_all,
                label = R.string.patch_selection_button_label_all,
                enabled = hasAnySelection
            ) {
                if (warningEnabled) {
                    showSelectionWarning = true
                } else {
                    requestConfirmation(
                        title = R.string.patch_selection_confirm_deselect_all_title,
                        message = context.getString(R.string.patch_selection_confirm_deselect_all_message),
                        onConfirm = viewModel::deselectAll
                    )
                }
            }

            PatchSelectionActionKey.BUNDLE_DEFAULTS -> PatchActionSpec(
                key = key,
                icon = Icons.Outlined.SettingsBackupRestore,
                contentDescription = R.string.patch_selection_button_label_reset_bundle,
                label = R.string.patch_selection_button_label_reset_bundle,
                enabled = currentBundle != null
            ) spec@{
                if (warningEnabled) {
                    showSelectionWarning = true
                    return@spec
                }
                val bundle = currentBundle ?: return@spec
                val bundleName = currentBundleDisplayName ?: bundle.name
                requestConfirmation(
                    title = R.string.patch_selection_confirm_bundle_defaults_title,
                    message = context.getString(
                        R.string.patch_selection_confirm_bundle_defaults_message,
                        bundleName
                    ),
                    icon = Icons.Outlined.Restore
                ) {
                    viewModel.resetBundleToDefaults(bundle.uid, bundleName)
                }
            }

            PatchSelectionActionKey.ALL_DEFAULTS -> PatchActionSpec(
                key = key,
                icon = Icons.Outlined.Restore,
                contentDescription = R.string.patch_selection_button_label_defaults,
                label = R.string.patch_selection_button_label_defaults,
                enabled = true
            ) {
                if (disableActionConfirmations) {
                    viewModel.reset()
                } else {
                    showResetConfirmation = true
                }
            }

            PatchSelectionActionKey.SAVE_PROFILE -> PatchActionSpec(
                key = key,
                icon = Icons.AutoMirrored.Outlined.PlaylistAdd,
                contentDescription = R.string.patch_profile_save_action,
                label = R.string.patch_profile_save_label,
                enabled = !isSavingProfile
            ) {
                if (!isSavingProfile) openProfileSaveDialog()
            }
        }
    }

    if (useFallbackSearch && searchActive) {
        SearchView(
            query = query,
            onQueryChange = updateQuery,
            onActiveChange = { searchActive = it },
            placeholder = { Text(stringResource(R.string.search_patches)) }
        ) {
            val bundle = bundles[settledPageIndex]
            val suggestedVersion = suggestedVersionsByBundle[bundle.uid]?.get(viewModel.appPackageName)
            val searchQuery = query
            val regularPatches = bundle.compatible.searched(searchQuery)
            val incompatiblePatches = bundle.incompatible.searched(searchQuery)
            val universalPatches = bundle.universal.searched(searchQuery)

            LazyColumnWithScrollbar(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                patchSections(
                    uid = bundle.uid,
                    regularPatches = regularPatches,
                    incompatiblePatches = incompatiblePatches,
                    universalPatches = universalPatches,
                    suggestedVersion = suggestedVersion
                )
            }
        }
    }

    Scaffold(
        topBar = {
            if (useFallbackSearch) {
                AppTopBar(
                    title = stringResource(R.string.patch_selector_item),
                    onBackClick = ::handlePatchSelectorBack,
                    actions = {
                        IconButton(onClick = { searchActive = true }) {
                            Icon(
                                imageVector = Icons.Outlined.Search,
                                contentDescription = stringResource(R.string.search)
                            )
                        }
                        Box {
                            val toggleLabel = if (actionsExpanded) {
                                R.string.patch_selection_toggle_collapse
                            } else {
                                R.string.patch_selection_toggle_expand
                            }
                            IconButton(onClick = {
                                if (visibleActionKeys.isEmpty()) {
                                    actionsExpanded = false
                                    context.toast(
                                        context.getString(R.string.patch_selection_all_actions_hidden_toast)
                                    )
                                    return@IconButton
                                }
                                actionsExpanded = !actionsExpanded
                            }) {
                                Icon(
                                    imageVector = Icons.Outlined.MoreHoriz,
                                    contentDescription = stringResource(toggleLabel)
                                )
                            }
                            if (actionsExpanded) {
                                val density = LocalDensity.current
                                val marginPx = remember(density) { with(density) { 8.dp.roundToPx() } }
                                val glowRadiusPx = remember(density) { with(density) { 220.dp.toPx() } }

                                Popup(
                                    popupPositionProvider = remember(marginPx) {
                                        PatchSelectionActionsPopupPositionProvider(marginPx = marginPx)
                                    },
                                    onDismissRequest = { actionsExpanded = false },
                                    properties = actionPopupProperties
                                ) {
                                    PatchSelectionActionsPopup(
                                        actionSpecs = actionSpecs,
                                        glowRadiusPx = glowRadiusPx,
                                        onActionClick = { spec ->
                                            spec.onClick()
                                            if (collapseActionsOnSelection) {
                                                actionsExpanded = false
                                            }
                                        }
                                    )
                                }
                            }
                        }
                        IconButton(onClick = {
                            actionsExpanded = false
                            showBottomSheet = true
                        }) {
                            Icon(
                                imageVector = Icons.Outlined.FilterList,
                                contentDescription = stringResource(R.string.more)
                            )
                        }
                    }
                )
            } else {
                SearchBar(
                    textFieldState = textFieldState,
                    searchBarState = searchBarState,
                    onSearch = {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                        composableScope.launch {
                            searchBarState.animateToCollapsed()
                        }
                    },
                    placeholder = {
                        Text(stringResource(R.string.search_patches))
                    },
                    leadingIcon = {
                        val rotation by animateFloatAsState(
                            targetValue = if (searchExpanded) 360f else 0f,
                            animationSpec = tween(durationMillis = 400, easing = EaseInOut),
                            label = stringResource(R.string.search_bar_back_button_label)
                        )
                        IconButton(
                            onClick = {
                                when {
                                    searchExpanded -> {
                                        focusManager.clearFocus(force = true)
                                        keyboardController?.hide()
                                        composableScope.launch {
                                            searchBarState.animateToCollapsed()
                                        }
                                    }
                                    query.isNotBlank() -> updateQuery("")
                                    else -> handlePatchSelectorBack()
                                }
                            }
                        ) {
                            Icon(
                                modifier = Modifier.rotate(rotation),
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back)
                            )
                        }
                    },
                    trailingIcon = {
                        AnimatedContent(
                            targetState = searchExpanded,
                            label = stringResource(R.string.patch_selector_filter_clear_label),
                            transitionSpec = { fadeIn() togetherWith fadeOut() }
                        ) { expanded ->
                            if (expanded) {
                                IconButton(
                                    onClick = { updateQuery("") },
                                    enabled = query.isNotEmpty()
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.clear)
                                    )
                                }
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box {
                                        val toggleLabel = if (actionsExpanded) {
                                            R.string.patch_selection_toggle_collapse
                                        } else {
                                            R.string.patch_selection_toggle_expand
                                        }
                                        IconButton(onClick = {
                                            if (visibleActionKeys.isEmpty()) {
                                                actionsExpanded = false
                                                context.toast(
                                                    context.getString(R.string.patch_selection_all_actions_hidden_toast)
                                                )
                                                return@IconButton
                                            }
                                            actionsExpanded = !actionsExpanded
                                        }) {
                                            Icon(
                                                imageVector = Icons.Outlined.MoreHoriz,
                                                contentDescription = stringResource(toggleLabel)
                                            )
                                        }
                                        if (actionsExpanded) {
                                            val density = LocalDensity.current
                                            val marginPx = remember(density) { with(density) { 8.dp.roundToPx() } }
                                            val glowRadiusPx = remember(density) { with(density) { 220.dp.toPx() } }

                                            Popup(
                                                popupPositionProvider = remember(marginPx) {
                                                    PatchSelectionActionsPopupPositionProvider(marginPx = marginPx)
                                                },
                                                onDismissRequest = { actionsExpanded = false },
                                                properties = actionPopupProperties
                                            ) {
                                                PatchSelectionActionsPopup(
                                                    actionSpecs = actionSpecs,
                                                    glowRadiusPx = glowRadiusPx,
                                                    onActionClick = { spec ->
                                                        spec.onClick()
                                                        if (collapseActionsOnSelection) {
                                                            actionsExpanded = false
                                                        }
                                                    }
                                                )
                                            }
                                        }
                                    }
                                    IconButton(onClick = {
                                        actionsExpanded = false
                                        showBottomSheet = true
                                    }) {
                                        Icon(
                                            imageVector = Icons.Outlined.FilterList,
                                            contentDescription = stringResource(R.string.more)
                                        )
                                    }
                                }
                            }
                        }
                    }
                ) {
                    val bundle = bundles[settledPageIndex]
                    val suggestedVersion = suggestedVersionsByBundle[bundle.uid]?.get(viewModel.appPackageName)
                    val searchQuery = query
                    val regularPatches = bundle.compatible.searched(searchQuery)
                    val incompatiblePatches = bundle.incompatible.searched(searchQuery)
                    val universalPatches = bundle.universal.searched(searchQuery)

                    LazyColumnWithScrollbar(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        patchSections(
                            uid = bundle.uid,
                            regularPatches = regularPatches,
                            incompatiblePatches = incompatiblePatches,
                            universalPatches = universalPatches,
                            suggestedVersion = suggestedVersion
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (useFallbackSearch) {
                if (searchActive) return@Scaffold
            } else {
                if (searchExpanded) return@Scaffold
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.TopEnd
            ) {
                Column(
                    modifier = Modifier
                        .wrapContentWidth(Alignment.End)
                        .padding(horizontal = 4.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    val saveButtonExpanded =
                        patchLazyListStates.getOrNull(settledPageIndex)?.isScrollingUp ?: true
                    val saveButtonText = stringResource(
                        R.string.save_with_count,
                        selectedPatchCount
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HapticExtendedFloatingActionButton(
                            text = { Text(saveButtonText) },
                            icon = {
                                SaveFabIcon(
                                    expanded = saveButtonExpanded,
                                    count = selectedPatchCount,
                                    contentDescription = saveButtonText
                                )
                            },
                            expanded = saveButtonExpanded,
                            enabled = showSaveButton,
                            onClick = {
                                viewModel.dismissDialogs()
                                onSave(
                                    viewModel.getCustomSelection(),
                                    viewModel.getOptions(),
                                    viewModel.transferPendingOptionInputs()
                                )
                            }
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(top = 16.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (bundles.isNotEmpty()) {
                        if (bundles.size == 1) {
                            TabRow(
                                selectedTabIndex = swipeSyncedTabIndex,
                                containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.0.dp)
                            ) {
                                bundles.forEachIndexed { index, bundle ->
                                    HapticTab(
                                        selected = swipeSyncedTabIndex == index,
                                        onClick = {
                                            composableScope.launch {
                                                scrollToBundlePage(index)
                                            }
                                        },
                                        text = {
                                            BundleTabContent(
                                                displayName = bundleDisplayNames[bundle.uid] ?: bundle.name,
                                                version = bundle.version.orEmpty(),
                                                sourceType = bundleTypes[bundle.uid]
                                            )
                                        },
                                        selectedContentColor = MaterialTheme.colorScheme.primary,
                                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            ScrollableTabRow(
                                selectedTabIndex = swipeSyncedTabIndex,
                                containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.0.dp)
                            ) {
                                bundles.forEachIndexed { index, bundle ->
                                    HapticTab(
                                        selected = swipeSyncedTabIndex == index,
                                        onClick = {
                                            composableScope.launch {
                                                scrollToBundlePage(index)
                                            }
                                        },
                                        text = {
                                            val selectionProgress =
                                                viewModel.bundleSelectionProgress(bundle)
                                            BundleTabContent(
                                                displayName = bundleDisplayNames[bundle.uid] ?: bundle.name,
                                                version = bundle.version.orEmpty(),
                                                sourceType = bundleTypes[bundle.uid],
                                                selectedCount = selectionProgress.selectedCount,
                                                totalCount = selectionProgress.totalCount
                                            )
                                        },
                                        selectedContentColor = MaterialTheme.colorScheme.primary,
                                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    val pagerFlingBehavior = if (preventAccidentalTouching) {
                        PagerDefaults.flingBehavior(state = pagerState)
                    } else {
                        PagerDefaults.flingBehavior(
                            state = pagerState,
                            pagerSnapDistance = PagerSnapDistance.atMost(1),
                            snapPositionalThreshold = 0.2f
                        )
                    }

                    HorizontalPager(
                        state = pagerState,
                        flingBehavior = pagerFlingBehavior,
                        userScrollEnabled = !disablePatchSelectionTabSwipe,
                        pageContent = { index ->
                            // Avoid crashing if the lists have not been fully initialized yet.
                            if (index > bundles.lastIndex || bundles.size != patchLazyListStates.size) return@HorizontalPager
                        val bundle = bundles[index]
                        val suggestedVersion = suggestedVersionsByBundle[bundle.uid]?.get(viewModel.appPackageName)
                        val searchQuery = query
                        val regularPatches = bundle.compatible.searched(searchQuery)
                        val incompatiblePatches = bundle.incompatible.searched(searchQuery)
                        val universalPatches = bundle.universal.searched(searchQuery)

                            LazyColumnWithScrollbar(
                                modifier = Modifier.fillMaxSize(),
                                state = patchLazyListStates[index],
                                contentPadding = PaddingValues(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = 12.dp,
                                    bottom = 12.dp
                                ),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                            patchSections(
                                uid = bundle.uid,
                                regularPatches = regularPatches,
                                incompatiblePatches = incompatiblePatches,
                                universalPatches = universalPatches,
                                suggestedVersion = suggestedVersion
                            )
                            }
                        }
                    )
                }
            }
        }
    }

    patchVersionsDialogState?.let { state ->
        PatchVersionsDialog(
            patchName = state.patchName,
            packageName = viewModel.appPackageName,
            versions = state.versions,
            suggestedVersion = state.suggestedVersion,
            searchEngineHost = searchEngineHost,
            onDismiss = { patchVersionsDialogState = null }
        )
    }
}

// Exact names are unique within a bundle; include the bundle across sections and pages.
internal fun patchSelectionIdentity(bundleUid: Int, patchName: String): String =
    "$bundleUid:$patchName"

@Composable
private fun PatchItem(
    identity: String,
    patch: PatchInfo,
    onOptionsDialog: () -> Unit,
    onShowVersionsDialog: (PatchVersionsDialogState) -> Unit,
    selected: Boolean,
    lockState: PatchLockState,
    onToggle: () -> Unit,
    compatible: Boolean = true,
    packageName: String,
    optionValues: Map<String, Any?>?,
    suggestedVersion: String?,
    searchEngineHost: String,
    showVersionTags: Boolean,
    showOptionPreviews: Boolean
): Unit {
    val context = LocalContext.current
    val lockedMessage = when (lockState) {
        PatchLockState.LOCKED_ON ->
            stringResource(R.string.patch_required_by_install_target)
        PatchLockState.LOCKED_OFF ->
            stringResource(R.string.patch_unavailable_for_install_target)
        PatchLockState.NONE -> null
    }
    val onItemClick = if (lockedMessage == null) {
        onToggle
    } else {
        { context.toast(lockedMessage) }
    }
    val supportedPackage = patch.compatiblePackages?.firstOrNull { it.packageName == packageName }
    val supportsAllVersions = patch.compatiblePackages == null || supportedPackage?.versions == null
    val rawVersions = supportedPackage?.versions?.toList()?.sorted().orEmpty()
    val experimentalVersions = supportedPackage?.experimentalVersions.orEmpty()
    val bundleSuggestedVersion = suggestedVersion?.takeUnless { it.isBlank() }
    val effectiveSuggestedVersion = if (!supportsAllVersions) {
        when {
            bundleSuggestedVersion != null && rawVersions.contains(bundleSuggestedVersion) -> bundleSuggestedVersion
            rawVersions.size == 1 -> rawVersions.first()
            else -> null
        }
    } else null
    val suggestedVersionInfo = effectiveSuggestedVersion?.let { version ->
        PatchVersionChipInfo(
            label = stringResource(
                R.string.bundle_version_suggested_label,
                suggestedVersionLabel(
                    versionName = version,
                    versionCodes = supportedPackage?.versionCodes?.get(version).orEmpty(),
                    displayVersion = formatPatchVersionLabel(version)
                )
            ),
            version = version,
            versionCodes = supportedPackage?.versionCodes?.get(version).orEmpty(),
            highlighted = true,
            experimental = version in experimentalVersions
        )
    }
    val showAllVersionsChip = supportsAllVersions && suggestedVersionInfo == null
    val availableVersions = if (supportsAllVersions) {
        emptyList()
    } else {
        rawVersions.filterNot { it == effectiveSuggestedVersion }
    }
    val hasMoreVersions = availableVersions.isNotEmpty()
    val visibleVersions = if (showAllVersionsChip) {
        listOf(
            PatchVersionChipInfo(
                label = stringResource(R.string.bundle_version_all_versions),
                version = null,
                outlined = true
            )
        )
    } else {
        emptyList()
    }
    val hasChips = suggestedVersionInfo != null || showAllVersionsChip || hasMoreVersions
    var showOptionPreview by rememberSaveable(identity) { mutableStateOf(false) }
    var showOptionPreviewDialog by rememberSaveable(identity) { mutableStateOf(false) }
    val optionValueEnabled = stringResource(R.string.option_value_enabled)
    val optionValueDisabled = stringResource(R.string.option_value_disabled)
    val optionValueUnset = stringResource(R.string.field_not_set)
    val optionSummaries = remember(
        patch.options,
        optionValues,
        optionValueEnabled,
        optionValueDisabled,
        optionValueUnset
    ) {
        patch.options.orEmpty().map { option ->
            val resolvedValue = if (optionValues?.contains(option.key) == true) {
                optionValues[option.key]
            } else {
                option.default
            }
            val presetLabel = option.presets
                ?.entries
                ?.firstOrNull { it.value == resolvedValue }
                ?.key
            val displayValue = when {
                presetLabel != null -> presetLabel
                resolvedValue == null -> optionValueUnset
                resolvedValue is Boolean -> if (resolvedValue) optionValueEnabled else optionValueDisabled
                resolvedValue is List<*> -> resolvedValue.joinToString(", ") { it?.toString().orEmpty() }
                else -> resolvedValue.toString()
            }
            option.title to displayValue
        }
    }
    val hasExpandableOptionPreview = optionSummaries.size > 1
    val showExpandedOptionPreview = hasExpandableOptionPreview && showOptionPreview
    val dialogVersions = when {
        supportsAllVersions -> listOf(
            PatchVersionChipInfo(
                label = stringResource(R.string.bundle_version_all_versions),
                version = null,
                outlined = true
            )
        )
        else -> availableVersions.map { version ->
            PatchVersionChipInfo(
                label = suggestedVersionLabel(
                    versionName = version,
                    versionCodes = supportedPackage?.versionCodes?.get(version).orEmpty(),
                    displayVersion = formatPatchVersionLabel(version)
                ),
                version = version,
                versionCodes = supportedPackage?.versionCodes?.get(version).orEmpty(),
                outlined = true,
                experimental = version in experimentalVersions
            )
        }
    }

    if (showOptionPreviewDialog) {
        AlertDialog(
            onDismissRequest = { showOptionPreviewDialog = false },
            confirmButton = {
                TextButton(onClick = { showOptionPreviewDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            },
            title = { CenteredDialogTitle(stringResource(R.string.patch_option_preview_title, patch.displayName)) },
            text = {
                val optionPreviewScrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(optionPreviewScrollState),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    optionSummaries.forEach { (title, value) ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = value,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        )
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (!compatible) it.alpha(0.6f) else it },
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 2.dp,
        onClick = onItemClick
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HapticCheckbox(
                    checked = selected,
                    onCheckedChange = { onItemClick() },
                    enabled = compatible && lockState == PatchLockState.NONE
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = patch.displayName,
                        style = MaterialTheme.typography.titleMedium
                    )
                    patch.description?.let { description ->
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    lockedMessage?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (patch.options?.isNotEmpty() == true) {
                    IconButton(
                        onClick = onOptionsDialog,
                        enabled = compatible && lockState != PatchLockState.LOCKED_OFF
                    ) {
                        Icon(Icons.Outlined.Settings, null)
                    }
                }
            }
            if (showVersionTags && hasChips) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    suggestedVersionInfo?.let { info ->
                        PatchVersionSearchChip(
                            label = info.label,
                            packageName = packageName,
                            version = info.version,
                            versionCodes = info.versionCodes,
                            searchEngineHost = searchEngineHost,
                            highlighted = true,
                            experimental = info.experimental
                        )
                    }
                    visibleVersions.forEach { version ->
                        PatchVersionSearchChip(
                            label = version.label,
                            packageName = packageName,
                            version = version.version,
                            versionCodes = version.versionCodes,
                            searchEngineHost = searchEngineHost,
                            outlined = true,
                            experimental = version.experimental
                        )
                    }
                    if (hasMoreVersions) {
                        PatchVersionChip(
                            label = stringResource(R.string.more),
                            icon = Icons.Outlined.UnfoldMore,
                            outlined = true,
                            onClick = {
                                onShowVersionsDialog(
                                    PatchVersionsDialogState(
                                        patchName = patch.displayName,
                                        versions = dialogVersions,
                                        suggestedVersion = suggestedVersionInfo
                                    )
                                )
                            }
                        )
                    }
                }
            }
            if (
                showOptionPreviews &&
                patch.options?.isNotEmpty() == true &&
                optionSummaries.isNotEmpty()
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.options),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                IconButton(
                                    onClick = { showOptionPreviewDialog = true },
                                    modifier = Modifier.size(26.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.MoreHoriz,
                                        contentDescription = stringResource(R.string.patch_option_preview_action),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                if (hasExpandableOptionPreview) {
                                    IconButton(
                                        onClick = { showOptionPreview = !showOptionPreview },
                                        modifier = Modifier.size(26.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (showExpandedOptionPreview) {
                                                Icons.Outlined.UnfoldLess
                                            } else {
                                                Icons.Outlined.UnfoldMore
                                            },
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                        if (!showExpandedOptionPreview) {
                            val first = optionSummaries.first()
                            val moreCount = optionSummaries.size - 1
                            Text(
                                text = if (moreCount > 0) {
                                    "${first.first}: ${first.second}  +$moreCount ${stringResource(R.string.more)}"
                                } else {
                                    "${first.first}: ${first.second}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                optionSummaries.forEach { (title, value) ->
                                    Text(
                                        text = "$title: $value",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 4,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class PatchVersionChipInfo(
    val label: String,
    val version: String?,
    val versionCodes: Set<Long> = emptySet(),
    val highlighted: Boolean = false,
    val outlined: Boolean = false,
    val experimental: Boolean = false
)

private data class PatchVersionsDialogState(
    val patchName: String,
    val versions: List<PatchVersionChipInfo>,
    val suggestedVersion: PatchVersionChipInfo?
)

@Composable
private fun PatchVersionSearchChip(
    label: String,
    packageName: String,
    version: String?,
    versionCodes: Set<Long> = emptySet(),
    searchEngineHost: String,
    highlighted: Boolean = false,
    outlined: Boolean = false,
    experimental: Boolean = false,
    showIcon: Boolean = true,
    fullWidth: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    PatchVersionChip(
        label = label,
        icon = Icons.Outlined.Search.takeIf { showIcon },
        highlighted = highlighted,
        outlined = outlined,
        experimental = experimental,
        fullWidth = fullWidth,
        modifier = modifier,
        onClick = {
            context.openUrl(buildSearchUrl(packageName, version, versionCodes, searchEngineHost))
        }
    )
}

@Composable
private fun PatchVersionChipWithSearch(
    label: String,
    packageName: String,
    version: String?,
    versionCodes: Set<Long> = emptySet(),
    searchEngineHost: String,
    highlighted: Boolean = false,
    outlined: Boolean = false,
    experimental: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PatchVersionChip(
            label = label,
            highlighted = highlighted,
            outlined = outlined,
            experimental = experimental
        )
        PatchVersionSearchButton(
            packageName = packageName,
            version = version,
            versionCodes = versionCodes,
            searchEngineHost = searchEngineHost
        )
    }
}

@Composable
private fun PatchVersionChip(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    highlighted: Boolean = false,
    outlined: Boolean = false,
    experimental: Boolean = false,
    fullWidth: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val background = when {
        highlighted -> MaterialTheme.colorScheme.primaryContainer
        outlined -> MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
        else -> MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
    }
    val contentColor = when {
        highlighted -> MaterialTheme.colorScheme.onPrimaryContainer
        outlined -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        onClick = onClick ?: {},
        enabled = onClick != null,
        color = background,
        contentColor = contentColor,
        shape = if (fullWidth) RoundedCornerShape(6.dp) else RoundedCornerShape(999.dp),
        border = if (outlined) BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)) else null,
        modifier = if (fullWidth) {
            modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
        } else {
            modifier.widthIn(max = if (experimental) 240.dp else 220.dp)
        }
    ) {
        val contentModifier = if (fullWidth) {
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(horizontal = 10.dp, vertical = 4.dp)
        } else {
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        }
        Column(
            modifier = contentModifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = if (experimental) {
                Arrangement.spacedBy(3.dp, Alignment.CenterVertically)
            } else {
                Arrangement.Center
            }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (icon == null) 6.dp else 4.dp)
            ) {
                ExpandableText(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = if (fullWidth && icon == null) Modifier.fillMaxWidth() else Modifier
                )
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            if (experimental) {
                ExperimentalVersionBadge()
            }
        }
    }
}

@Composable
private fun PatchVersionsDialog(
    patchName: String,
    packageName: String,
    versions: List<PatchVersionChipInfo>,
    suggestedVersion: PatchVersionChipInfo?,
    searchEngineHost: String,
    onDismiss: () -> Unit
) {
    val scrollState = rememberScrollState()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ok))
            }
        },
        title = {
            CenteredDialogTitle(stringResource(R.string.patch_versions_dialog_title, patchName))
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(scrollState)
            ) {
                suggestedVersion?.let { info ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        PatchVersionSearchChip(
                            label = info.label,
                            packageName = packageName,
                            version = info.version,
                            versionCodes = info.versionCodes,
                            searchEngineHost = searchEngineHost,
                            highlighted = true,
                            experimental = info.experimental
                        )
                    }
                }
                if (versions.isEmpty()) {
                    Text(
                        text = stringResource(R.string.other_supported_versions_empty),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        versions.chunked(2).forEach { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                row.forEach { info ->
                                    PatchVersionSearchChip(
                                        label = info.label,
                                        packageName = packageName,
                                        version = info.version,
                                        versionCodes = info.versionCodes,
                                        searchEngineHost = searchEngineHost,
                                        outlined = true,
                                        experimental = info.experimental,
                                        fullWidth = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (row.size == 1) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
    )
}

private fun formatPatchVersionLabel(version: String): String =
    if (version.startsWith("v", ignoreCase = true)) version else "v$version"

@Composable
private fun PatchVersionSearchButton(
    packageName: String,
    version: String?,
    versionCodes: Set<Long> = emptySet(),
    searchEngineHost: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    IconButton(
        onClick = {
            context.openUrl(buildSearchUrl(packageName, version, versionCodes, searchEngineHost))
        },
        modifier = modifier.size(24.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = stringResource(R.string.search),
            modifier = Modifier.size(14.dp)
        )
    }
}

private fun buildSearchUrl(
    packageName: String,
    version: String?,
    versionCodes: Set<Long>,
    searchEngineHost: String
): String {
    val encodedPackage = Uri.encode(packageName)
    val encodedVersion = version?.takeIf { it.isNotBlank() }?.let {
        Uri.encode(formatPatchVersionLabel(it))
    }
    val encodedVersionCodes = versionCodes.sorted().map { Uri.encode(it.toString()) }
    val encodedArch = Build.SUPPORTED_ABIS.firstOrNull()
        ?.takeIf { it.isNotBlank() }
        ?.let(Uri::encode)
    val query = buildList {
        add(encodedPackage)
        encodedVersion?.let(::add)
        addAll(encodedVersionCodes)
        encodedArch?.let(::add)
    }.joinToString("+")
    val host = normalizeSearchHost(searchEngineHost)
    return "https://$host/search?q=$query"
}

private fun normalizeSearchHost(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return "google.com"
    val noScheme = trimmed.removePrefix("https://").removePrefix("http://")
    val noPath = noScheme.substringBefore('/').substringBefore('?').substringBefore('#')
    return noPath.trim().trimEnd('/').ifBlank { "google.com" }
}

@Composable
private fun SaveFabIcon(
    expanded: Boolean,
    count: Int,
    contentDescription: String
) {
    if (expanded) {
        Icon(
            imageVector = Icons.Outlined.Save,
            contentDescription = contentDescription
        )
    } else {
        BadgedBox(
            badge = {
                Badge {
                    Text(
                        text = formatPatchCountForBadge(count),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                }
            }
        ) {
            Icon(
                imageVector = Icons.Outlined.Save,
                contentDescription = contentDescription
            )
        }
    }
}

private fun bundleTypeLabelRes(type: BundleSourceType?): Int = when (type) {

    BundleSourceType.Remote -> R.string.bundle_type_remote
    else -> R.string.bundle_type_local
}

@Composable
private fun BundleTabContent(
    displayName: String,
    version: String,
    sourceType: BundleSourceType?,
    selectedCount: Int? = null,
    totalCount: Int? = null
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = displayName,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = version,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(bundleTypeLabelRes(sourceType)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
        if (selectedCount != null && totalCount != null) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Text(
                    text = stringResource(
                        R.string.patch_selector_bundle_selection_progress,
                        selectedCount,
                        totalCount
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun formatPatchCountForBadge(count: Int): String =
    if (count > 999) stringResource(R.string.patch_count_overflow) else count.toString()

@Composable
private fun PatchProfileBundleDialog(
    bundles: List<PatchBundleInfo.Scoped>,
    bundleDisplayNames: Map<Int, String>,
    bundleTypes: Map<Int, BundleSourceType>,
    selectedBundleUids: MutableList<Int>,
    onSelectionChanged: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val confirmEnabled = bundles.isNotEmpty() && selectedBundleUids.isNotEmpty()

    AlertDialogExtended(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                Text(stringResource(R.string.next))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        title = { CenteredDialogTitle(stringResource(R.string.patch_profile_select_bundles_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.patch_profile_select_bundles_description))
                if (bundles.isEmpty()) {
                    Text(
                        text = stringResource(R.string.patch_profile_select_bundles_empty),
                        color = MaterialTheme.colorScheme.outline
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(bundles, key = { it.uid }) { bundle ->
                            val selected = bundle.uid in selectedBundleUids
                            val toggle: () -> Unit = {
                                onSelectionChanged()
                                if (bundle.uid in selectedBundleUids) {
                                    selectedBundleUids.remove(bundle.uid)
                                } else {
                                    selectedBundleUids.add(bundle.uid)
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(onClick = toggle),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                HapticCheckbox(
                                    checked = selected,
                                    onCheckedChange = { toggle() }
                                )

                                Column {
                                    Text(
                                        text = bundleDisplayNames[bundle.uid] ?: bundle.name,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    bundle.version?.let { version ->
                                        Text(
                                            text = version,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        text = stringResource(bundleTypeLabelRes(bundleTypes[bundle.uid])),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun PatchProfileNameDialog(
    name: String,
    onNameChange: (String) -> Unit,
    isSaving: Boolean,
    profiles: List<PatchProfile>,
    selectedProfileId: Int?,
    onProfileSelected: (PatchProfile?) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var nameFieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(name, selection = TextRange(name.length)))
    }

    LaunchedEffect(name) {
        if (name != nameFieldValue.text) {
            nameFieldValue = TextFieldValue(name, selection = TextRange(name.length))
        }
    }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    AlertDialogExtended(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = name.isNotBlank() && !isSaving
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) {
                Text(stringResource(R.string.cancel))
            }
        },
        title = { CenteredDialogTitle(stringResource(R.string.patch_profile_name_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.patch_profile_name_description))
                TextField(
                    value = nameFieldValue,
                    onValueChange = {
                        nameFieldValue = it
                        onNameChange(it.text)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    singleLine = true,
                    enabled = !isSaving,
                    placeholder = { Text(stringResource(R.string.patch_profile_name_hint)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (nameFieldValue.text.isNotBlank() && !isSaving) onConfirm()
                        }
                    )
                )

                if (profiles.isNotEmpty()) {
                    Text(stringResource(R.string.patch_profile_update_existing_title))
                    Text(
                        text = stringResource(R.string.patch_profile_update_existing_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    LazyColumnWithScrollbar(
                        thumbLength = app.universal.patch.manager.ui.component.DASHBOARD_SCROLLBAR_THUMB_LENGTH,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp),
                        reserveScrollbarSpace = true
                    ) {
                        items(
                            items = profiles,
                            key = { it.uid }
                        ) { profile ->
                            val selected = selectedProfileId == profile.uid
                            ListItem(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (selected)
                                            MaterialTheme.colorScheme.secondaryContainer
                                        else
                                            Color.Transparent
                                    )
                                    .clickable(enabled = !isSaving) {
                                        onProfileSelected(if (selected) null else profile)
                                    }
                                    .padding(horizontal = 4.dp),
                                headlineContent = { Text(profile.name) },
                                supportingContent = resolvePatchProfileAppVersion(
                                    appVersion = profile.appVersion,
                                    apkPath = profile.apkPath,
                                    apkVersion = profile.apkVersion,
                                    useSelectedApkVersion = profile.useSelectedApkVersion
                                )?.let { version ->
                                    {
                                        Text(
                                            text = version.ifBlank { stringResource(R.string.any_version) },
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                },
                                trailingContent = {
                                    if (selected) {
                                        Icon(Icons.Filled.Check, null)
                                    }
                                },
                                colors = transparentListItemColors
                            )
                        }
                    }
                }
            }
        }
    )
}

private data class PatchActionSpec(
    val key: PatchSelectionActionKey?,
    val icon: ImageVector,
    @StringRes val contentDescription: Int,
    @StringRes val label: Int,
    val enabled: Boolean,
    val onClick: () -> Unit
)

@Composable
private fun PatchSelectionActionChip(
    spec: PatchActionSpec,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val contentAlpha = if (spec.enabled) 1f else 0.4f
    Surface(
        onClick = { if (spec.enabled) onClick() },
        enabled = spec.enabled,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        tonalElevation = 4.dp,
        shadowElevation = 1.dp,
        shape = RoundedCornerShape(999.dp),
        modifier = modifier.alpha(contentAlpha)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = spec.icon,
                contentDescription = stringResource(spec.contentDescription),
                modifier = Modifier.size(16.dp)
            )
            if (!minimalActionButtons("selection")) Text(
                text = stringResource(spec.label),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1
            )
        }
    }
}

private class PatchSelectionActionsPopupPositionProvider(
    private val marginPx: Int
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val x = ((windowSize.width - popupContentSize.width) / 2)
            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))

        val yBelow = anchorBounds.bottom + marginPx
        val yAbove = anchorBounds.top - popupContentSize.height - marginPx
        val y = when {
            yBelow + popupContentSize.height <= windowSize.height -> yBelow
            yAbove >= 0 -> yAbove
            else -> (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        }

        return IntOffset(x, y)
    }
}

@Composable
private fun PatchSelectionActionsPopup(
    actionSpecs: List<PatchActionSpec>,
    glowRadiusPx: Float,
    onActionClick: (PatchActionSpec) -> Unit,
    modifier: Modifier = Modifier
) {
    val splitIndex = (actionSpecs.size + 1) / 2
    val firstRow = remember(actionSpecs) { actionSpecs.take(splitIndex) }
    val secondRow = remember(actionSpecs) { actionSpecs.drop(splitIndex) }
    var firstRowWidthPx by remember { mutableStateOf(0) }
    var secondRowWidthPx by remember { mutableStateOf(0) }
    BoxWithConstraints(modifier = modifier) {
        val popupMaxWidth = (maxWidth - 32.dp)
            .coerceAtLeast(0.dp)
            .coerceAtMost(720.dp)
        val maxRowWidthPx = max(firstRowWidthPx, secondRowWidthPx)
        val measuredWidth = if (maxRowWidthPx > 0) {
            with(LocalDensity.current) { maxRowWidthPx.toDp() + 32.dp }
        } else {
            popupMaxWidth
        }
        val popupWidth = measuredWidth.coerceAtMost(popupMaxWidth)

        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
            tonalElevation = 0.dp,
            shadowElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)),
            modifier = Modifier
                .width(popupWidth)
        ) {
            Box {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .blur(26.dp)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                                    Color.Transparent
                                ),
                                radius = glowRadiusPx
                            )
                        )
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.22f))
                )

                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    ActionChipRow(
                        specs = firstRow,
                        onActionClick = onActionClick,
                        onContentWidth = { width ->
                            if (width != firstRowWidthPx) {
                                firstRowWidthPx = width
                            }
                        }
                    )
                    if (secondRow.isNotEmpty()) {
                        ActionChipRow(
                            specs = secondRow,
                            onActionClick = onActionClick,
                            onContentWidth = { width ->
                                if (width != secondRowWidthPx) {
                                    secondRowWidthPx = width
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionChipRow(
    specs: List<PatchActionSpec>,
    onActionClick: (PatchActionSpec) -> Unit,
    onContentWidth: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = 6.dp
    val scrollState = rememberScrollState()

    SubcomposeLayout(modifier = modifier.fillMaxWidth()) { constraints ->
        val contentPlaceable = subcompose("content") {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                specs.forEach { spec ->
                    PatchSelectionActionChip(
                        spec = spec,
                        onClick = { onActionClick(spec) }
                    )
                }
            }
        }.first().measure(Constraints())

        onContentWidth(contentPlaceable.width)
        val layoutWidth = constraints.maxWidth
        val layoutHeight = contentPlaceable.height
        val scrollNeeded = contentPlaceable.width > layoutWidth

        val rowPlaceable = subcompose("row") {
            val rowModifier = if (scrollNeeded) {
                Modifier.consumeHorizontalScroll(scrollState)
            } else {
                Modifier
            }
            val arrangement = if (scrollNeeded) {
                Arrangement.spacedBy(spacing, Alignment.End)
            } else {
                Arrangement.spacedBy(spacing, Alignment.CenterHorizontally)
            }
            Row(
                modifier = rowModifier.fillMaxWidth(),
                horizontalArrangement = arrangement
            ) {
                specs.forEach { spec ->
                    PatchSelectionActionChip(
                        spec = spec,
                        onClick = { onActionClick(spec) }
                    )
                }
            }
        }.first().measure(
            Constraints(
                minWidth = layoutWidth,
                maxWidth = layoutWidth,
                minHeight = 0,
                maxHeight = constraints.maxHeight
            )
        )

        layout(layoutWidth, layoutHeight) {
            rowPlaceable.place(0, 0)
        }
    }
}

@Composable
private fun SelectionActionButton(
    icon: ImageVector,
    @StringRes contentDescription: Int,
    @StringRes label: Int,
    containerColor: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
    contentColor: Color = MaterialTheme.colorScheme.onTertiaryContainer,
    modifier: Modifier = Modifier
) {
    val contentAlpha = if (enabled) 1f else 0.4f
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Surface(
            onClick = { if (enabled) onClick() },
            enabled = enabled,
            color = containerColor,
            contentColor = contentColor,
            tonalElevation = 6.dp,
            shadowElevation = 2.dp,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .size(52.dp)
                .alpha(contentAlpha)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    stringResource(contentDescription),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp).copy(alpha = 0.85f),
            shape = RoundedCornerShape(999.dp),
            tonalElevation = 1.dp,
            modifier = Modifier
                .fillMaxWidth()
                .alpha(contentAlpha)
        ) {
            Text(
                text = stringResource(label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 2.dp),
                maxLines = 1
            )
        }
    }
}

private data class SelectionConfirmation(
    @StringRes val title: Int,
    val message: String,
    val icon: ImageVector?,
    val onConfirm: () -> Unit
)

@Composable
private fun CenteredDialogText(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center
    )
}

private enum class PatchSortSettingsMode {
    None,
    HasSettings,
    NoSettings
}

private enum class PatchSortSelectionMode {
    None,
    EnabledFirst,
    DisabledFirst
}

@Composable
fun ListHeader(
    title: String,
    onHelpClick: (() -> Unit)? = null
) {
    ListItem(
        headlineContent = {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge
            )
        },
        trailingContent = onHelpClick?.let {
            {
                IconButton(onClick = it) {
                    Icon(
                        Icons.AutoMirrored.Outlined.HelpOutline,
                        stringResource(R.string.help)
                    )
                }
            }
        },
        colors = transparentListItemColors
    )
}

@Composable
private fun IncompatiblePatchesDialog(
    appVersion: String,
    onDismissRequest: () -> Unit
) = AlertDialog(
    icon = {
        Icon(Icons.Outlined.WarningAmber, null)
    },
    onDismissRequest = onDismissRequest,
    confirmButton = {
        TextButton(onClick = onDismissRequest) {
            Text(stringResource(R.string.ok))
        }
    },
    title = { CenteredDialogTitle(stringResource(R.string.incompatible_patches)) },
    text = {
        CenteredDialogText(
            stringResource(
                R.string.incompatible_patches_dialog,
                appVersion
            )
        )
    }
)

@Composable
private fun IncompatiblePatchDialog(
    appVersion: String,
    compatibleVersions: List<SupportedVersionInfo>,
    onDismissRequest: () -> Unit
) {
    val compatibleVersionLabels = compatibleVersions
        .sortedBy { it.version }
        .map { info ->
            suggestedVersionLabel(
                versionName = info.version,
                versionCodes = info.versionCodes
            )
        }

    AlertDialog(
        icon = {
            Icon(Icons.Outlined.WarningAmber, null)
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.ok))
            }
        },
        title = { CenteredDialogTitle(stringResource(R.string.incompatible_patch)) },
        text = {
            Text(
                stringResource(
                    R.string.app_version_not_compatible,
                    appVersion,
                    compatibleVersionLabels.joinToString(", ")
                )
            )
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionsDialog(
    patch: PatchInfo,
    values: Map<String, Any?>?,
    reset: () -> Unit,
    set: (String, Any?) -> Unit,
    onDismissRequest: () -> Unit,
    selectionWarningEnabled: Boolean
) = FullscreenDialog(onDismissRequest = onDismissRequest) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppTopBar(
                title = patch.displayName,
                onBackClick = onDismissRequest,
                applyContainerColor = true,
                actions = {
                    IconButton(onClick = reset) {
                        Icon(Icons.Outlined.Restore, stringResource(R.string.reset))
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumnWithScrollbar(
            modifier = Modifier.padding(paddingValues),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (patch.options == null) return@LazyColumnWithScrollbar

            item(key = "options_header") {
                ListHeader(
                    title = stringResource(R.string.options)
                )
            }
            item(key = "options_summary") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    tonalElevation = 2.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = patch.displayName,
                            style = MaterialTheme.typography.titleMedium
                        )
                        patch.description?.let { description ->
                            Text(
                                text = description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            items(patch.options, key = { it.key }) { option ->
                val key = option.key
                val value =
                    if (values == null || !values.contains(key)) option.default else values[key]

                @Suppress("UNCHECKED_CAST")
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    tonalElevation = 2.dp
                ) {
                    OptionItem(
                        option = option as Option<Any>,
                        value = value,
                        setValue = {
                            set(key, it)
                        },
                        selectionWarningEnabled = selectionWarningEnabled
                    )
                }
            }
        }
    }
}


