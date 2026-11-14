package app.universal.patch.manager.ui.component.patches

/*
 * Portions adapted from Morphe. Copyright 2026 Morphe.
 * Code adapted from Morphe, see third-party/NOTICE for more information.
 * https://github.com/MorpheApp/morphe-manager/blob/0b39b6479b07cfdae5b976cd922480fba406d155/app/src/main/java/app/morphe/manager/ui/screen/shared/FilePicker.kt
 */
import app.universal.patch.manager.ui.component.persistentControls
import android.text.format.DateFormat
import android.text.format.Formatter
import android.util.LruCache
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.text.SimpleDateFormat
import java.util.Date
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import app.universal.patch.manager.R
import app.universal.patch.manager.data.platform.Filesystem
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.domain.manager.base.Preference
import app.universal.patch.manager.patcher.split.SplitArchiveDisplayResolver
import app.universal.patch.manager.patcher.split.SplitApkPreparer
import app.universal.patch.manager.ui.component.AppTopBar
import app.universal.patch.manager.ui.component.AppIcon
import app.universal.patch.manager.ui.component.ConfirmDialog
import app.universal.patch.manager.ui.component.FullscreenDialog
import app.universal.patch.manager.ui.component.GroupHeader
import app.universal.patch.manager.ui.component.InterceptBackHandler
import app.universal.patch.manager.ui.component.LazyColumnWithScrollbar
import app.universal.patch.manager.ui.component.ShimmerBox
import app.universal.patch.manager.util.toast
import app.universal.patch.manager.util.APK_FILE_EXTENSIONS
import app.universal.patch.manager.util.PM
import app.universal.patch.manager.util.saver.PathSaver
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import java.util.UUID
import org.koin.compose.koinInject
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import kotlin.io.path.absolutePathString
import kotlin.io.path.isDirectory
import kotlin.io.path.isReadable
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import app.universal.patch.manager.ui.component.CenteredDialogTitle

// Limit APK/split extraction even when its helpers switch to their own I/O dispatcher.
private val pathPreviewPermits = Semaphore(2)
private val pathDetailsDispatcher = Dispatchers.IO.limitedParallelism(2)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PathSelectorDialog(
    roots: List<Filesystem.StorageRoot>,
    onSelect: (Path?) -> Unit,
    fileFilter: (Path) -> Boolean = { true },
    allowDirectorySelection: Boolean = true,
    fileTypeLabel: String? = null,
    confirmButtonText: String? = null,
    onConfirm: ((Path) -> Unit)? = null,
    lastDirectoryPreference: Preference<String>? = null
) {
    val context = LocalContext.current
    val prefs = koinInject<PreferencesManager>()
    val pm = koinInject<PM>()
    val filesystem = koinInject<Filesystem>()
    val scope = rememberCoroutineScope()
    val (permissionContract, permissionName) = remember { filesystem.permissionContract() }
    var permissionGranted by remember { mutableStateOf(filesystem.hasStoragePermission()) }
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher =
        rememberLauncherForActivityResult(permissionContract) { granted ->
            permissionGranted = granted || filesystem.hasStoragePermission()
        }
    val availableRoots = remember(roots) {
        roots.filter { runCatching { it.path.isReadable() }.getOrDefault(true) }.ifEmpty { roots }
    }
    val defaultRoot = availableRoots.firstOrNull() ?: return
    val directoryPreference = lastDirectoryPreference ?: prefs.pathSelectorLastDirectory
    val lastDirectoryValue by directoryPreference.getAsState()
    val (initialRootPath, initialDirectory) = remember(availableRoots, defaultRoot, lastDirectoryValue) {
        val lastPath = lastDirectoryValue.takeIf { it.isNotBlank() }
            ?.let { runCatching { Paths.get(it) }.getOrNull() }
        val resolved = lastPath
            ?.let { if (it.isDirectory()) it else it.parent }
            ?.takeIf { it.isReadable() }
        val rootForResolved = resolved?.let { dir ->
            availableRoots.firstOrNull { dir.startsWith(it.path) }
        }
        val root = rootForResolved ?: defaultRoot
        val directory = if (rootForResolved != null) resolved ?: root.path else root.path
        root.path to directory
    }
    var currentRootPath by rememberSaveable(initialRootPath, stateSaver = PathSaver) {
        mutableStateOf(initialRootPath)
    }
    val currentRoot = remember(currentRootPath, availableRoots) {
        availableRoots.firstOrNull { it.path == currentRootPath } ?: defaultRoot
    }
    var currentDirectory by rememberSaveable(initialRootPath, stateSaver = PathSaver) {
        mutableStateOf(initialDirectory)
    }
    val notAtRootDir = remember(currentDirectory, currentRoot) {
        currentDirectory != currentRoot.path
    }
    LaunchedEffect(permissionGranted) {
        if (!permissionGranted && !permissionRequested) {
            permissionRequested = true
            permissionLauncher.launch(permissionName)
        }
    }
    var refreshNonce by remember { mutableStateOf(0) }
    val detailsCache = remember(refreshNonce) { LruCache<Path, PathEntryDetails>(200) }
    val previewCache = remember(refreshNonce) { PathPreviewCache() }
    val configuration = LocalConfiguration.current
    val is24Hour = DateFormat.is24HourFormat(context)
    val modificationFormat = remember(configuration, is24Hour) {
        val locale = configuration.locales[0] ?: Locale.getDefault()
        val clock = if (is24Hour) "Hm" else "hm"
        SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "yMMMd" + clock), locale)
    }
    // Code adapted from Morphe, see third-party/NOTICE for more information
    // https://github.com/MorpheApp/morphe-manager/commit/e181417019239408b23a9a075b42e9399323eb23
    val directoryResult by key(currentDirectory, permissionGranted, refreshNonce) {
        produceState<Result<List<Path>>?>(initialValue = null) {
            val result: Result<List<Path>> = if (!permissionGranted) {
                Result.failure(SecurityException("Storage permission required"))
            } else {
                var contents = withContext(Dispatchers.IO) {
                    listDirectoryEntriesSafe(currentDirectory)
                }
                if (contents == null) {
                    delay(300)
                    contents = withContext(Dispatchers.IO) {
                        listDirectoryEntriesSafe(currentDirectory)
                    }
                }
                contents?.let { Result.success(it) }
                    ?: Result.failure(SecurityException("Cannot read directory"))
            }
            value = result
        }
    }
    val refreshInProgress = directoryResult == null
    val entries = directoryResult?.getOrNull().orEmpty()

    fun refreshDirectory() {
        permissionGranted = filesystem.hasStoragePermission()
        if (!permissionGranted) {
            permissionRequested = true
            permissionLauncher.launch(permissionName)
        }
        refreshNonce += 1
    }
    // Code adapted from Morphe, see third-party/NOTICE for more information
    // https://github.com/MorpheApp/morphe-manager/commit/205956fa9c9bdf6b4ab13cc2d1880077936bc436
    val showHiddenFiles by prefs.pathSelectorShowHiddenFiles.getAsState()
    val visibleEntries = remember(entries, showHiddenFiles) {
        if (showHiddenFiles) entries else entries.filterNot { it.name.startsWith(".") }
    }
    val persistedSortMode by prefs.pathSelectorSortMode.getAsState()
    var sortMode by rememberSaveable(persistedSortMode) {
        mutableStateOf(PathSortMode.fromStorage(persistedSortMode))
    }
    var showSortMenu by rememberSaveable { mutableStateOf(false) }
    val sortKeys = remember(entries) {
        entries.associateWith(::buildPathSortKey)
    }
    val sortComparator = remember(sortMode, sortKeys) {
        sortMode.comparator(sortKeys)
    }
    val directories = remember(visibleEntries, sortComparator) {
        visibleEntries.filter(Path::isDirectory).sortedWith(sortComparator)
    }
    val files = remember(visibleEntries, fileFilter, sortComparator) {
        visibleEntries.filterNot(Path::isDirectory).filter(fileFilter).sortedWith(sortComparator)
    }
    // Search belongs to this picker session, never the next picker that is opened.
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val normalizedQuery = searchQuery.trim()
    val filteredDirectories = remember(directories, normalizedQuery) {
        if (normalizedQuery.isBlank()) {
            directories
        } else {
            directories.filter { it.name.contains(normalizedQuery, ignoreCase = true) }
        }
    }
    val filteredFiles = remember(files, normalizedQuery) {
        if (normalizedQuery.isBlank()) {
            files
        } else {
            files.filter { it.name.contains(normalizedQuery, ignoreCase = true) }
        }
    }
    val duplicateDirectoryNames = remember(directories) {
        directories
            .groupingBy { it.name.lowercase(Locale.ROOT) }
            .eachCount()
            .filterValues { it > 1 }
            .keys
    }
    val favoriteSet: Set<String> by prefs.pathSelectorFavorites.getAsState()
    val favorites: List<Path> = remember(favoriteSet, fileFilter) {
        favoriteSet.mapNotNull { runCatching { Paths.get(it) }.getOrNull() }
            .filter { isReadableSafe(it) }
            .sortedBy { it.absolutePathString() }
            .filter { it.isDirectory() || fileFilter(it) }
    }
    var favoritesExpanded by rememberSaveable { mutableStateOf(false) }
    var pendingRemoveFavorite by remember { mutableStateOf<Path?>(null) }
    var pendingAddFavorite by remember { mutableStateOf<Path?>(null) }
    var previewImagePath by remember { mutableStateOf<Path?>(null) }

    fun addFavorite(path: Path) {
        val key = path.absolutePathString()
        scope.launch {
            prefs.pathSelectorFavorites.update(favoriteSet + key)
            context.toast(context.getString(R.string.path_selector_favorite_added))
        }
    }

    fun removeFavorite(path: Path) {
        val key = path.absolutePathString()
        scope.launch {
            prefs.pathSelectorFavorites.update(favoriteSet - key)
            context.toast(context.getString(R.string.path_selector_favorite_removed))
        }
    }

    fun handleFavoritePress(path: Path) {
        if (favoriteSet.contains(path.absolutePathString())) {
            pendingRemoveFavorite = path
        } else {
            pendingAddFavorite = path
        }
    }

    LaunchedEffect(currentDirectory, lastDirectoryValue, directoryResult) {
        if (directoryResult?.isSuccess != true) return@LaunchedEffect
        val nextValue = currentDirectory.absolutePathString()
        if (nextValue != lastDirectoryValue) {
            directoryPreference.update(nextValue)
        }
    }

    LaunchedEffect(sortMode, persistedSortMode) {
        val nextValue = sortMode.name
        if (nextValue != persistedSortMode) {
            prefs.pathSelectorSortMode.update(nextValue)
        }
    }


    FullscreenDialog(
        onDismissRequest = { onSelect(null) },
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                Column {
                    AppTopBar(
                        title = stringResource(R.string.path_selector),
                        onBackClick = { onSelect(null) },
                        backIcon = {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close))
                        },
                        actions = {
                            IconButton(onClick = ::refreshDirectory) {
                                Icon(
                                    Icons.Outlined.Refresh,
                                    contentDescription = stringResource(R.string.refresh)
                                )
                            }
                            Box {
                                IconButton(onClick = { showSortMenu = true }) {
                                    Icon(
                                        Icons.Outlined.Sort,
                                        contentDescription = stringResource(R.string.path_selector_sort_title)
                                    )
                                }
                                DropdownMenu(
                                    expanded = showSortMenu,
                                    onDismissRequest = { showSortMenu = false },
                                    modifier = Modifier.heightIn(max = 420.dp)
                                ) {
                                    PathSortMode.entries.forEach { mode ->
                                        val selected = mode == sortMode
                                        DropdownMenuItem(
                                            text = { Text(text = stringResource(mode.labelRes)) },
                                            leadingIcon = {
                                                if (selected) {
                                                    Icon(
                                                        imageVector = Icons.Outlined.Check,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary
                                                    )
                                                } else {
                                                    Spacer(modifier = Modifier.size(24.dp))
                                                }
                                            },
                                            onClick = {
                                                sortMode = mode
                                                showSortMenu = false
                                            }
                                        )
                                    }
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.path_selector_show_hidden_files)) },
                                        trailingIcon = {
                                            Checkbox(checked = showHiddenFiles, onCheckedChange = null)
                                        },
                                        modifier = Modifier.semantics {
                                            role = Role.Checkbox
                                            toggleableState = ToggleableState(showHiddenFiles)
                                        },
                                        onClick = {
                                            scope.launch {
                                                prefs.pathSelectorShowHiddenFiles.update(!showHiddenFiles)
                                            }
                                        }
                                    )
                                }
                            }
                            if (confirmButtonText != null && onConfirm != null) {
                                TextButton(onClick = { onConfirm(currentDirectory) }) {
                                    Text(confirmButtonText)
                                }
                            }
                        }
                    )
                    PathSelectorFolderTrail(
                        root = currentRoot,
                        directory = currentDirectory,
                        onOpen = { currentDirectory = it }
                    )
                }
            },
        ) { paddingValues ->
            InterceptBackHandler(enabled = notAtRootDir) {
                currentDirectory = currentDirectory.parent
            }

            LazyColumnWithScrollbar(
                modifier = Modifier.padding(paddingValues)
            ) {
                if (refreshInProgress) {
                    item(key = "refresh_current") {
                        PathSelectorRowShimmer(headlineWidth = 0.7f)
                    }
                    item(key = "refresh_search") {
                        PathSelectorSearchShimmer()
                    }
                    item(key = "refresh_favorites_header") {
                        PathSelectorHeaderShimmer()
                    }
                    items(2, key = { "refresh_favorite_$it" }) {
                        PathSelectorRowShimmer(headlineWidth = 0.5f)
                    }
                    item(key = "refresh_storage_header") {
                        PathSelectorGroupHeaderShimmer()
                    }
                    items(2, key = { "refresh_storage_$it" }) {
                        PathSelectorRowShimmer(headlineWidth = 0.45f)
                    }
                    item(key = "refresh_dirs_header") {
                        PathSelectorGroupHeaderShimmer()
                    }
                    items(6, key = { "refresh_dir_$it" }) {
                        PathSelectorRowShimmer(headlineWidth = 0.6f)
                    }
                    item(key = "refresh_files_header") {
                        PathSelectorGroupHeaderShimmer()
                    }
                    items(4, key = { "refresh_file_$it" }) {
                        PathSelectorRowShimmer(headlineWidth = 0.55f)
                    }
                } else {
                    item(key = "current") {
                        PathItem(
                            onClick = { onSelect(currentDirectory) },
                            onLongClick = { handleFavoritePress(currentDirectory) },
                            icon = Icons.Outlined.Folder,
                            name = currentDirectory.toString(),
                            enabled = allowDirectorySelection
                        )
                    }

                    persistentControls(key = "search") {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            label = { Text(stringResource(R.string.path_selector_search)) },
                            singleLine = true,
                            trailingIcon = {
                                if (searchQuery.isNotBlank()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = stringResource(R.string.clear)
                                        )
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 8.dp)
                        )
                    }

                    item(key = "favorites_header") {
                        val headerColor = MaterialTheme.colorScheme.primary
                        val icon = if (favoritesExpanded) {
                            Icons.Outlined.ExpandLess
                        } else {
                            Icons.Outlined.ChevronRight
                        }
                        val description = if (favoritesExpanded) {
                            stringResource(R.string.collapse_content)
                        } else {
                            stringResource(R.string.expand_content)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp)
                                .semantics { heading() }
                                .clickable { favoritesExpanded = !favoritesExpanded },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.path_selector_favorites),
                                color = headerColor,
                                style = MaterialTheme.typography.titleSmall
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Icon(icon, contentDescription = description, tint = headerColor)
                        }
                    }
                    if (favoritesExpanded && favorites.isNotEmpty()) {
                        items(favorites, key = { "fav_${it.absolutePathString()}" }) { favorite ->
                            val isDir = favorite.isDirectory()
                            PathItem(
                                onClick = {
                                    if (isDir) {
                                        val matchingRoot = availableRoots.firstOrNull {
                                            favorite.startsWith(it.path)
                                        }
                                        matchingRoot?.let { currentRootPath = it.path }
                                        currentDirectory = favorite
                                    } else {
                                        onSelect(favorite)
                                    }
                                },
                                onLongClick = { handleFavoritePress(favorite) },
                                supportingContent = {
                                    PathEntrySupportingContent(
                                        path = favorite, isDirectory = isDir,
                                        cache = detailsCache, modificationFormat = modificationFormat,
                                        extraText = favorite.absolutePathString()
                                    )
                                },
                                icon = if (isDir) Icons.Outlined.Folder else fileIconForPath(favorite),
                                leadingContent = if (isDir) null else {
                                    {
                                        FileLeadingIcon(
                                            path = favorite,
                                            previewCache = previewCache,
                                            pm = pm,
                                            filesystem = filesystem,
                                            onImagePreviewClick = if (isImagePreviewablePath(favorite)) {
                                                { previewImagePath = favorite }
                                            } else {
                                                null
                                            }
                                        )
                                    }
                                },
                                name = favorite.name.ifBlank { favorite.absolutePathString() },
                                supportingText = favorite.absolutePathString()
                            )
                        }
                    }

                    if (availableRoots.size > 1) {
                        item(key = "roots_header") {
                            GroupHeader(title = stringResource(R.string.storage))
                        }
                        items(availableRoots, key = { it.path.toString() }) { root ->
                            val icon = if (root.isRemovable) Icons.Outlined.SdCard else Icons.Outlined.Storage
                            PathItem(
                                onClick = {
                                    currentRootPath = root.path
                                    currentDirectory = root.path
                                },
                                onLongClick = { handleFavoritePress(root.path) },
                                icon = icon,
                                name = root.label
                            )
                        }
                    }

                    if (notAtRootDir) {
                        item(key = "parent") {
                            PathItem(
                                onClick = { currentDirectory = currentDirectory.parent },
                                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                                name = stringResource(R.string.path_selector_parent_dir)
                            )
                        }
                    }

                    if (directoryResult?.isFailure == true) {
                        item(key = "read_error") {
                            ListItem(
                                leadingContent = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                                headlineContent = { Text(stringResource(R.string.path_selector_read_error)) },
                                trailingContent = {
                                    TextButton(onClick = ::refreshDirectory) {
                                        Text(stringResource(R.string.retry))
                                    }
                                }
                            )
                        }
                    } else if (filteredDirectories.isEmpty() && filteredFiles.isEmpty()) {
                        item(key = "empty") {
                            ListItem(
                                headlineContent = {
                                    Text(stringResource(
                                        if (normalizedQuery.isBlank() && entries.isEmpty()) {
                                            R.string.path_selector_no_files
                                        } else {
                                            R.string.path_selector_no_matches
                                        }
                                    ))
                                }
                            )
                        }
                    }

                    if (filteredDirectories.isNotEmpty()) {
                        item(key = "dirs_header") {
                            GroupHeader(title = stringResource(R.string.path_selector_dirs))
                        }
                    }
                    items(filteredDirectories, key = { it.absolutePathString() }) {
                        val nameKey = it.name.lowercase(Locale.ROOT)
                        PathItem(
                            onClick = { currentDirectory = it },
                            onLongClick = { handleFavoritePress(it) },
                            icon = Icons.Outlined.Folder,
                            name = it.name,
                            supportingContent = {
                                PathEntrySupportingContent(
                                    path = it, isDirectory = true,
                                    cache = detailsCache, modificationFormat = modificationFormat,
                                    extraText = it.absolutePathString().takeIf { nameKey in duplicateDirectoryNames }
                                )
                            }
                        )
                    }

                    if (filteredFiles.isNotEmpty()) {
                        item(key = "files_header") {
                            val header = if (!fileTypeLabel.isNullOrBlank()) {
                                "${stringResource(R.string.path_selector_files)} ($fileTypeLabel)"
                            } else {
                                stringResource(R.string.path_selector_files)
                            }
                            GroupHeader(title = header)
                        }
                    }
                    items(filteredFiles, key = { it.absolutePathString() }) {
                        PathItem(
                            onClick = { onSelect(it) },
                            onLongClick = { handleFavoritePress(it) },
                            icon = fileIconForPath(it),
                            leadingContent = {
                                FileLeadingIcon(
                                    path = it,
                                    previewCache = previewCache,
                                    pm = pm,
                                    filesystem = filesystem,
                                    onImagePreviewClick = if (isImagePreviewablePath(it)) {
                                        { previewImagePath = it }
                                    } else {
                                        null
                                    }
                                )
                            },
                            name = it.name,
                            supportingContent = {
                                PathEntrySupportingContent(
                                    path = it, isDirectory = false,
                                    cache = detailsCache, modificationFormat = modificationFormat
                                )
                            }
                        )
                    }
                }
            }
        }

        pendingRemoveFavorite?.let { target ->
            val displayName = target.name.ifBlank { target.absolutePathString() }
            ConfirmDialog(
                onDismiss = { pendingRemoveFavorite = null },
                onConfirm = {
                    pendingRemoveFavorite = null
                    removeFavorite(target)
                },
                title = stringResource(R.string.path_selector_favorite_remove_title),
                description = stringResource(R.string.path_selector_favorite_remove_description, displayName),
                icon = Icons.Outlined.Star
            )
        }

        pendingAddFavorite?.let { target ->
            val displayName = target.name.ifBlank { target.absolutePathString() }
            ConfirmDialog(
                onDismiss = { pendingAddFavorite = null },
                onConfirm = {
                    pendingAddFavorite = null
                    addFavorite(target)
                },
                title = stringResource(R.string.path_selector_favorite_add_title),
                description = stringResource(R.string.path_selector_favorite_add_description, displayName),
                icon = Icons.Outlined.Star
            )
        }

        previewImagePath?.let { target ->
            val displayName = target.name.ifBlank { target.absolutePathString() }
            AlertDialog(
                onDismissRequest = { previewImagePath = null },
                confirmButton = {
                    TextButton(onClick = { previewImagePath = null }) {
                        Text(stringResource(R.string.close))
                    }
                },
                title = { CenteredDialogTitle(stringResource(R.string.path_selector_image_preview_title, displayName)) },
                text = {
                    AsyncImage(
                        model = rememberPathPreviewRequest(target, previewCache),
                        contentDescription = stringResource(R.string.path_selector_image_preview_content_description, displayName),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp, max = 420.dp)
                            .clip(MaterialTheme.shapes.medium)
                    )
                }
            )
        }

    }
}

// Code adapted from Morphe's FolderTrail and FileEntryRow; see third-party/NOTICE.
// https://github.com/MorpheApp/morphe-manager/blob/0b39b6479b07cfdae5b976cd922480fba406d155/app/src/main/java/app/morphe/manager/ui/screen/shared/FilePicker.kt
@Composable
private fun PathSelectorFolderTrail(
    root: Filesystem.StorageRoot,
    directory: Path,
    onOpen: (Path) -> Unit
) {
    val breadcrumbs = remember(root.path, directory) { pathSelectorBreadcrumbs(root.path, directory) }
    val scroll = rememberScrollState()
    // maxValue changes after layout, so a new, longer trail reveals its current folder too.
    LaunchedEffect(directory, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(scroll)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        breadcrumbs.forEachIndexed { index, path ->
            if (index > 0) {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
            FilterChip(
                selected = path == directory,
                onClick = { if (path != directory) onOpen(path) },
                label = { Text(if (path == root.path) root.label else path.name.ifBlank { path.toString() }) },
                leadingIcon = {
                    Icon(
                        if (path == root.path && root.isRemovable) Icons.Outlined.SdCard
                        else if (path == root.path) Icons.Outlined.Storage else Icons.Outlined.Folder,
                        contentDescription = null, modifier = Modifier.size(18.dp)
                    )
                }
            )
        }
    }
}

@Composable
private fun PathEntrySupportingContent(
    path: Path,
    isDirectory: Boolean,
    cache: LruCache<Path, PathEntryDetails>,
    modificationFormat: SimpleDateFormat,
    extraText: String? = null
) {
    val context = LocalContext.current
    val details by key(path, cache, isDirectory) {
        produceState<PathEntryDetails?>(initialValue = cache.get(path)) {
            value = cache.get(path) ?: withContext(pathDetailsDispatcher) {
                readPathEntryDetails(path, isDirectory)?.also { cache.put(path, it) }
            }
        }
    }
    val detail = if (isDirectory) {
        details?.itemCount?.let { pluralStringResource(R.plurals.path_selector_item_count, it, it) }
    } else {
        val size = details?.size?.let { Formatter.formatFileSize(context, it) }
        val date = details?.modified?.takeIf { it > 0 }?.let { modificationFormat.format(Date(it)) }
        listOfNotNull(size, date).takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }
    val text = listOfNotNull(extraText, detail).joinToString("\n")
    if (text.isNotBlank()) Text(text)
}

private val KEYSTORE_EXTENSIONS = setOf("jks", "keystore", "bks", "p12", "pfx")
private val AUDIO_EXTENSIONS = setOf("mp3", "wav", "ogg", "flac", "aac", "m4a", "opus", "wma", "mid", "midi")
private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "ts", "flv")

private fun fileIconForPath(path: Path): ImageVector = when (extensionOf(path)) {
    in APK_FILE_EXTENSIONS -> Icons.Outlined.Android
    in KEYSTORE_EXTENSIONS -> Icons.Outlined.Key
    in IMAGE_PREVIEW_EXTENSIONS -> Icons.Outlined.Image
    in AUDIO_EXTENSIONS -> Icons.Outlined.MusicNote
    in VIDEO_EXTENSIONS -> Icons.Outlined.VideoFile
    "json" -> Icons.Outlined.DataObject
    "mpp", "rvp", "jar", "reseam" -> Icons.Outlined.Extension
    else -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

private data class PathSortKey(
    val nameLower: String,
    val extensionLower: String,
    val modified: Long,
    val size: Long
)

private enum class PathSortMode(val labelRes: Int) {
    NAME_ASC(R.string.path_selector_sort_name_asc),
    NAME_DESC(R.string.path_selector_sort_name_desc),
    MODIFIED_DESC(R.string.path_selector_sort_modified_desc),
    MODIFIED_ASC(R.string.path_selector_sort_modified_asc),
    TYPE_ASC(R.string.path_selector_sort_type_asc),
    TYPE_DESC(R.string.path_selector_sort_type_desc),
    SIZE_DESC(R.string.path_selector_sort_size_desc),
    SIZE_ASC(R.string.path_selector_sort_size_asc);

    companion object {
        fun fromStorage(value: String): PathSortMode =
            entries.firstOrNull { it.name == value } ?: MODIFIED_DESC
    }

    fun comparator(keys: Map<Path, PathSortKey>): Comparator<Path> {
        val nameComparator = compareBy<Path>(
            { keys[it]?.nameLower ?: it.name.lowercase(Locale.ROOT) },
            { it.name }
        )
        val typeComparator = compareBy<Path>(
            { keys[it]?.extensionLower ?: extensionOf(it) },
            { keys[it]?.nameLower ?: it.name.lowercase(Locale.ROOT) },
            { it.name }
        )
        val modifiedComparator = compareBy<Path>(
            { keys[it]?.modified ?: 0L },
            { keys[it]?.nameLower ?: it.name.lowercase(Locale.ROOT) },
            { it.name }
        )
        val sizeComparator = compareBy<Path>(
            { keys[it]?.size ?: 0L },
            { keys[it]?.nameLower ?: it.name.lowercase(Locale.ROOT) },
            { it.name }
        )

        return when (this) {
            NAME_ASC -> nameComparator
            NAME_DESC -> nameComparator.reversed()
            MODIFIED_DESC -> modifiedComparator.reversed()
            MODIFIED_ASC -> modifiedComparator
            TYPE_ASC -> typeComparator
            TYPE_DESC -> typeComparator.reversed()
            SIZE_DESC -> sizeComparator.reversed()
            SIZE_ASC -> sizeComparator
        }
    }
}

private fun buildPathSortKey(path: Path): PathSortKey {
    val file = path.toFile()
    return PathSortKey(
        nameLower = path.name.lowercase(Locale.ROOT),
        extensionLower = extensionOf(path),
        modified = runCatching { file.lastModified() }.getOrDefault(0L),
        size = runCatching {
            if (file.isDirectory) 0L else file.length()
        }.getOrDefault(0L)
    )
}

private fun extensionOf(path: Path): String =
    path.name.substringAfterLast('.', "").lowercase(Locale.ROOT)

// Code adapted from Morphe, see third-party/NOTICE for more information
// https://github.com/MorpheApp/morphe-manager/commit/e181417019239408b23a9a075b42e9399323eb23
private fun listDirectoryEntriesSafe(path: Path): List<Path>? {
    val rawEntries = runCatching { path.listDirectoryEntries() }.getOrNull()
        ?: runCatching { path.toFile().listFiles()?.map { it.toPath() } }.getOrNull()
        ?: return null
    if (rawEntries.isEmpty()) return emptyList()
    val readable = rawEntries.filter { isReadableSafe(it) }
    return if (readable.isEmpty()) rawEntries else readable
}

private fun isReadableSafe(path: Path): Boolean =
    runCatching { path.isReadable() }.getOrDefault(true)

@Composable
private fun PathSelectorRowShimmer(
    headlineWidth: Float = 0.55f
) {
    ListItem(
        leadingContent = {
            ShimmerBox(modifier = Modifier.size(24.dp))
        },
        headlineContent = {
            ShimmerBox(modifier = Modifier.fillMaxWidth(headlineWidth).height(16.dp))
        }
    )
}

@Composable
private fun PathSelectorSearchShimmer() {
    ShimmerBox(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .height(56.dp)
    )
}

@Composable
private fun PathSelectorHeaderShimmer() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ShimmerBox(modifier = Modifier.width(88.dp).height(14.dp))
        Spacer(modifier = Modifier.weight(1f))
        ShimmerBox(modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun PathSelectorGroupHeaderShimmer() {
    ShimmerBox(
        modifier = Modifier
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp)
            .width(96.dp)
            .height(14.dp)
    )
}

@Composable
private fun rememberPathPreviewRequest(path: Path, previewCache: PathPreviewCache): ImageRequest {
    val context = LocalContext.current
    return remember(path, previewCache, context) {
        ImageRequest.Builder(context)
            .data(path.toFile())
            .memoryCacheKey(pathPreviewCacheKey(previewCache.generation, path))
            .build()
    }
}

@Composable
private fun FileLeadingIcon(
    path: Path,
    previewCache: PathPreviewCache,
    pm: PM,
    filesystem: Filesystem,
    onImagePreviewClick: (() -> Unit)? = null
) {
    if (isImagePreviewablePath(path)) {
        Box(
            modifier = Modifier.then(
                if (onImagePreviewClick != null) Modifier.clickable(onClick = onImagePreviewClick) else Modifier
            )
        ) {
            val imageRequest = rememberPathPreviewRequest(path, previewCache)
            AsyncImage(
                model = imageRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(24.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
            )
        }
        return
    }

    val fileName = path.fileName?.toString()?.lowercase().orEmpty()
    val extension = fileName.substringAfterLast('.', "")
    if (extension !in APK_FILE_EXTENSIONS) {
        Icon(fileIconForPath(path), contentDescription = null)
        return
    }

    var iconInfo by remember(path, previewCache) { mutableStateOf<ApkIconInfo?>(previewCache.get(path)) }
    LaunchedEffect(path, previewCache) {
        iconInfo = previewCache.get(path) ?: pathPreviewPermits.withPermit {
            previewCache.get(path) ?: loadApkIconInfo(path, pm, filesystem)?.also {
                previewCache.put(path, it)
            }
        }
    }
    DisposableEffect(path, previewCache) {
        onDispose {
            iconInfo?.cleanup?.invoke()
        }
    }

    val packageInfo = iconInfo?.packageInfo
    if (packageInfo != null) {
        AppIcon(
            packageInfo = packageInfo,
            iconOverride = iconInfo?.iconOverride,
            cacheKey = pathPreviewCacheKey(previewCache.generation, path),
            contentDescription = null,
            modifier = Modifier.size(24.dp)
        )
    } else {
        Icon(fileIconForPath(path), contentDescription = null)
    }
}

private val IMAGE_PREVIEW_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "tif", "tiff",
    "avif", "heif", "heic", "jfif", "ico", "apng"
)

private fun isImagePreviewablePath(path: Path): Boolean {
    val extension = path.fileName?.toString()
        ?.substringAfterLast('.', "")
        ?.lowercase(Locale.ROOT)
        .orEmpty()
    return extension in IMAGE_PREVIEW_EXTENSIONS
}

// A refresh invalidates row details and previews, including files replaced at the same path.
private class PathPreviewCache : LruCache<Path, ApkIconInfo>(100) {
    val generation: String = UUID.randomUUID().toString()
}

private data class ApkIconInfo(
    val packageInfo: android.content.pm.PackageInfo?,
    val iconOverride: android.graphics.drawable.Drawable? = null,
    val cleanup: (() -> Unit)?
)

private suspend fun loadApkIconInfo(
    path: Path,
    pm: PM,
    filesystem: Filesystem
): ApkIconInfo? = withContext(Dispatchers.IO) {
    try {
        val file = path.toFile()
        if (!file.exists()) return@withContext null
        val extension = file.extension.lowercase(Locale.ROOT)
        val isSplitArchive = extension != "apk" && SplitApkPreparer.isSplitArchive(file)
        if (extension != "apk" && !isSplitArchive) return@withContext null

        if (isSplitArchive) {
            val resolved = SplitArchiveDisplayResolver.resolve(
                source = file,
                workspace = filesystem.tempDir,
                app = pm.application,
                pm = pm
            ) ?: return@withContext null
            ApkIconInfo(
                packageInfo = resolved.packageInfo,
                iconOverride = resolved.icon,
                cleanup = null
            )
        } else {
            ApkIconInfo(
                packageInfo = pm.getPackageInfo(file),
                iconOverride = null,
                cleanup = null
            )
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // Previews are optional: unreadable or damaged archives keep the generic file icon.
        null
    }
}

@Composable
private fun PathItem(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    icon: ImageVector,
    leadingContent: (@Composable () -> Unit)? = null,
    name: String,
    enabled: Boolean = true,
    supportingText: String? = null,
    supportingContent: (@Composable () -> Unit)? = null
) {
    val hasLongClick = onLongClick != null
    val clickEnabled = enabled || hasLongClick
    ListItem(
        modifier = if (clickEnabled) {
            Modifier.combinedClickable(
                enabled = clickEnabled,
                onClick = { if (enabled) onClick() },
                onLongClick = onLongClick
            )
        } else {
            Modifier
        },
        headlineContent = { Text(name) },
        supportingContent = supportingContent ?: supportingText?.let { { Text(it) } },
        leadingContent = {
            leadingContent?.invoke() ?: Icon(icon, contentDescription = null)
        }
    )
}

