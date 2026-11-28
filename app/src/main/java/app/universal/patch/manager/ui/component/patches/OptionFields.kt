package app.universal.patch.manager.ui.component.patches

import app.universal.patch.manager.util.withHapticFeedback
import android.view.HapticFeedbackConstants
import app.universal.patch.manager.ui.component.design.ListItem
import app.universal.patch.manager.ui.component.design.ListItemRadioButton
import app.universal.patch.manager.ui.component.design.CircularProgressIndicator
import app.universal.patch.manager.ui.component.design.IconButton
import app.universal.patch.manager.ui.component.design.TextButton
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Parcelable
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisallowComposableCalls
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import app.universal.patch.manager.R
import app.universal.patch.manager.data.platform.Filesystem
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.patcher.patch.ExplicitOptionKind
import app.universal.patch.manager.patcher.patch.Option
import app.universal.patch.manager.ui.component.AlertDialogExtended
import app.universal.patch.manager.ui.component.AppTopBar
import app.universal.patch.manager.ui.component.CenteredDialogTitle
import app.universal.patch.manager.ui.component.FloatInputDialog
import app.universal.patch.manager.ui.component.DoubleInputDialog
import app.universal.patch.manager.ui.component.FullscreenDialog
import app.universal.patch.manager.ui.component.IntInputDialog
import app.universal.patch.manager.ui.component.LongInputDialog
import app.universal.patch.manager.ui.component.RememberedOpenDocument
import app.universal.patch.manager.ui.component.RememberedOpenDocumentTree
import app.universal.patch.manager.ui.component.toPickerDirectoryUri
import app.universal.patch.manager.ui.component.haptics.HapticExtendedFloatingActionButton
import app.universal.patch.manager.ui.component.haptics.HapticSwitch
import app.universal.patch.manager.util.IMAGE_MIMETYPE
import app.universal.patch.manager.util.WILDCARD_MIMETYPE
import app.universal.patch.manager.util.isScrollingUp
import app.universal.patch.manager.util.mutableStateSetOf
import app.universal.patch.manager.util.saver.snapshotStateListSaver
import app.universal.patch.manager.util.saver.snapshotStateSetSaver
import app.universal.patch.manager.util.toast
import app.universal.patch.manager.util.transparentListItemColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import org.koin.compose.koinInject
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.Serializable
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.reflect.typeOf

private class CompletedImportTracker(val path: AtomicReference<String?>) :
    AbstractCoroutineContextElement(CompletedImportTracker) {
    companion object Key : CoroutineContext.Key<CompletedImportTracker>
}

private class OptionEditorScope<T : Any>(
    private val editor: OptionEditor<T>,
    val option: Option<T>,
    val openDialog: () -> Unit,
    val dismissDialog: () -> Unit,
    val selectionWarningEnabled: Boolean,
    val showSelectionWarning: () -> Unit,
    val value: T?,
    val setValue: (T?) -> Unit
) {
    fun submitDialog(value: T?) {
        setValue(value)
        dismissDialog()
    }

    fun checkSafeguard(block: () -> Unit) {
        if (!option.required && selectionWarningEnabled)
            showSelectionWarning()
        else
            block()
    }

    fun clickAction() {
        checkSafeguard {
            editor.clickAction(this)
        }
    }

    @Composable
    fun ListItemTrailingContent() = editor.ListItemTrailingContent(this)

    @Composable
    fun Dialog() = editor.Dialog(this)
}

private interface OptionEditor<T : Any> {
    fun clickAction(scope: OptionEditorScope<T>) = scope.openDialog()

    @Composable
    fun ListItemTrailingContent(scope: OptionEditorScope<T>) {
        IconButton(onClick = { scope.checkSafeguard { clickAction(scope) } }) {
            Icon(Icons.Outlined.Edit, stringResource(R.string.edit))
        }
    }

    @Composable
    fun Dialog(scope: OptionEditorScope<T>)
}

private inline fun <reified T : Serializable> OptionEditor<T>.toMapEditorElements() = arrayOf(
    typeOf<T>() to this,
    typeOf<List<T>>() to ListOptionEditor(this)
)

private val optionEditors = mapOf(
    *BooleanOptionEditor.toMapEditorElements(),
    *StringOptionEditor.toMapEditorElements(),
    *IntOptionEditor.toMapEditorElements(),
    *LongOptionEditor.toMapEditorElements(),
    *FloatOptionEditor.toMapEditorElements(),
    *DoubleOptionEditor.toMapEditorElements()
)

@Composable
private inline fun <T : Any> WithOptionEditor(
    editor: OptionEditor<T>,
    option: Option<T>,
    value: T?,
    noinline setValue: (T?) -> Unit,
    selectionWarningEnabled: Boolean,
    crossinline onDismissDialog: @DisallowComposableCalls () -> Unit = {},
    block: OptionEditorScope<T>.() -> Unit
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var showSelectionWarningDialog by rememberSaveable { mutableStateOf(false) }

    val scope = remember(editor, option, value, setValue, selectionWarningEnabled) {
        OptionEditorScope(
            editor,
            option,
            openDialog = { showDialog = true },
            dismissDialog = {
                showDialog = false
                onDismissDialog()
            },
            selectionWarningEnabled,
            showSelectionWarning = { showSelectionWarningDialog = true },
            value,
            setValue
        )
    }

    if (showSelectionWarningDialog)
        SelectionWarningDialog(
            onDismiss = { showSelectionWarningDialog = false }
        )

    if (showDialog) scope.Dialog()

    scope.block()
}

@Composable
fun <T : Any> OptionItem(
    option: Option<T>,
    value: T?,
    setValue: (T?) -> Unit,
    selectionWarningEnabled: Boolean
) {
    val editor = remember(
        option.type,
        option.presets,
        option.explicitKind,
        option.allowedExtensions,
        option.recommendedSize
    ) {
        @Suppress("UNCHECKED_CAST")
        val baseOptionEditor =
            optionEditors.getOrDefault(option.type, UnknownTypeEditor) as OptionEditor<T>

        if (option.type != typeOf<Boolean>() && option.presets != null) PresetOptionEditor(
            baseOptionEditor
        )
        else baseOptionEditor
    }

    WithOptionEditor(editor, option, value, setValue, selectionWarningEnabled) {
        ListItem(
            onClick = ::clickAction,
            headlineContent = { Text(option.title) },
            supportingContent = {
                Column {
                    Text(option.description)
                    // Code adapted from Morphe, see third-party/NOTICE for more information
                    // https://github.com/MorpheApp/morphe-manager/pull/706
                    option.recommendedSize?.let { size ->
                        Text(
                            "${stringResource(R.string.recommended)}: " +
                                "${size.width}\u00d7${size.height}"
                        )
                    }
                    if (option.required && value == null) Text(
                        stringResource(R.string.option_required),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            trailingContent = { ListItemTrailingContent() }
        )
    }
}

private object StringOptionEditor : OptionEditor<String> {
    @Composable
    override fun ListItemTrailingContent(scope: OptionEditorScope<String>) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (scope.option.isColorOption()) {
                ColorOptionSwatch(scope.value ?: scope.option.default)
            }
            super.ListItemTrailingContent(scope)
        }
    }

    @Composable
    override fun Dialog(scope: OptionEditorScope<String>) {
        var showFileDialog by rememberSaveable { mutableStateOf(false) }
        var showColorPicker by rememberSaveable { mutableStateOf(false) }
        val isColorOption = remember(scope.option) { scope.option.isColorOption() }
        // Code adapted from Morphe, see third-party/NOTICE for more information
        // https://github.com/MorpheApp/morphe-manager/pull/706
        val isFolderOption = scope.option.explicitKind == ExplicitOptionKind.Folder
        val isFilesOption = scope.option.explicitKind == ExplicitOptionKind.Files
        val isFileOption =
            scope.option.explicitKind == ExplicitOptionKind.FilePath || isFilesOption
        val isImageOption = scope.option.explicitKind == ExplicitOptionKind.Image
        val isTypedPathOption = isFolderOption || isFileOption || isImageOption
        val allowedExtensions = remember(scope.option.allowedExtensions) {
            scope.option.allowedExtensions
                ?.map(::normalizeExtension)
                ?.filter(String::isNotEmpty)
                ?.distinct()
                ?.takeIf { it.isNotEmpty() }
        }
        val pickerMimeTypes = remember(allowedExtensions, isImageOption) {
            resolvePickerMimeTypes(allowedExtensions, isImageOption)
        }
        var fieldValue by rememberSaveable(scope.value) {
            mutableStateOf(scope.value.orEmpty())
        }
        var validatorFailed by remember { mutableStateOf(false) }
        val validatorRef by rememberUpdatedState(scope.option.validator)
        LaunchedEffect(fieldValue, isColorOption, scope.option.explicitKind, scope.option.required) {
            val failed = withContext(Dispatchers.Default) {
                val normalizedValue =
                    if (isColorOption) normalizeColorOptionValue(fieldValue) else fieldValue
                val invalidColorValue = isColorOption &&
                    fieldValue.isNotBlank() &&
                    normalizedValue == null
                val invalidRequiredTypedValue = scope.option.explicitKind != null &&
                    scope.option.required &&
                    fieldValue.isBlank()
                invalidColorValue ||
                    invalidRequiredTypedValue ||
                    runCatching { !validatorRef(normalizedValue ?: fieldValue) }.getOrDefault(true)
            }
            validatorFailed = failed
        }

        val fs: Filesystem = koinInject()
        val app: Application = koinInject()
        val prefs: PreferencesManager = koinInject()
        val useCustomFilePicker by prefs.useCustomFilePicker.getAsState()
        val patchOptionFileInputDirectory by prefs.patchOptionFileInputLastDirectory.getAsState()
        val pickerScope = rememberCoroutineScope()
        val storageRoots = remember { fs.storageRoots() }
        var pickerImportInProgress by remember { mutableStateOf(false) }
        var pickerImportJob by remember { mutableStateOf<Job?>(null) }
        var importedPath by rememberSaveable { mutableStateOf<String?>(null) }
        val hostActivity = LocalContext.current.findActivity()

        LaunchedEffect(importedPath) {
            importedPath?.let(fs::claimPatchOptionInput)
        }
        DisposableEffect(hostActivity) {
            onDispose {
                if (shouldDeletePendingDialogInputs(
                        isChangingConfigurations = hostActivity?.isChangingConfigurations == true
                    )
                ) {
                    importedPath?.let(fs::deletePatchOptionInputAsync)
                }
            }
        }

        fun discardImportedPath() {
            importedPath?.let(fs::deletePatchOptionInputAsync)
            importedPath = null
        }

        fun useImportedPath(path: String) {
            if (importedPath != path) discardImportedPath()
            importedPath = path
            fieldValue = path
        }

        fun useExternalPath(path: String) {
            discardImportedPath()
            fieldValue = path
        }

        fun dismissDialog() {
            discardImportedPath()
            scope.dismissDialog()
        }

        fun cancelPickerImport() {
            pickerImportJob?.cancel()
        }

        val (contract, permissionName) = fs.permissionContract()
        val permissionLauncher = rememberLauncherForActivityResult(contract = contract) {
            showFileDialog = it
        }
        val openDocumentLauncher = rememberLauncherForActivityResult(
            contract = RememberedOpenDocument {
                patchOptionFileInputDirectory.takeIf(String::isNotBlank)?.let(Uri::parse)
            }
        ) { uri ->
            uri?.let { selectedUri ->
                pickerImportJob = pickerScope.launch {
                    pickerImportInProgress = true
                    val completedImportPath = AtomicReference<String?>()
                    try {
                        prefs.patchOptionFileInputLastDirectory.update(
                            selectedUri.toPickerDirectoryUri().toString()
                        )
                        val localPath = withContext(Dispatchers.IO + CompletedImportTracker(completedImportPath)) {
                            importDocumentUriToLocalPath(
                                fs = fs,
                                uri = selectedUri,
                                allowedExtensions = allowedExtensions,
                                imageOnly = isImageOption

                            )
                        }
                        localPath?.let { path ->
                            useImportedPath(path)
                            completedImportPath.set(null)
                        }
                            ?: app.toast(app.getString(R.string.failed_to_load_file))
                    } finally {
                        completedImportPath.getAndSet(null)?.let(fs::deletePatchOptionInputAsync)
                        pickerImportInProgress = false
                        pickerImportJob = null
                    }
                }
            }
        }
        val openDocumentTreeLauncher = rememberLauncherForActivityResult(
            contract = RememberedOpenDocumentTree {
                patchOptionFileInputDirectory.takeIf(String::isNotBlank)?.let(Uri::parse)
            }
        ) { uri ->
            uri?.let { selectedUri ->
                pickerImportJob = pickerScope.launch {
                    pickerImportInProgress = true
                    val completedImportPath = AtomicReference<String?>()
                    try {
                        prefs.patchOptionFileInputLastDirectory.update(selectedUri.toString())
                        val localPath = withContext(Dispatchers.IO + CompletedImportTracker(completedImportPath)) {
                            importDocumentTreeUriToLocalPath(
                                app = app,
                                fs = fs,
                                uri = selectedUri,
                            )
                        }
                        localPath?.let { path ->
                            useImportedPath(path)
                            completedImportPath.set(null)
                        }
                            ?: app.toast(app.getString(R.string.failed_to_load_file))
                    } finally {
                        completedImportPath.getAndSet(null)?.let(fs::deletePatchOptionInputAsync)
                        pickerImportInProgress = false
                        pickerImportJob = null
                    }
                }
            }
        }

        if (showFileDialog && useCustomFilePicker) {
            PathSelectorDialog(
                roots = storageRoots,
                fileFilter = { path ->
                    !isFolderOption &&
                        path.matchesAllowedExtensions(allowedExtensions, isImageOption)
                },
                allowDirectorySelection = !isFileOption && !isImageOption,
                fileTypeLabel = allowedExtensions?.joinToString(", ") { ".$it" }
                    ?: IMAGE_MIMETYPE.takeIf { isImageOption },
                onSelect = {
                    showFileDialog = false
                    it?.let { path ->
                        useExternalPath(path.toString())
                    }
                },
                lastDirectoryPreference = prefs.patchOptionFileInputLastDirectory
            )
        }
        LaunchedEffect(useCustomFilePicker) {
            if (!useCustomFilePicker) {
                showFileDialog = false
            }
        }
        if (showColorPicker) {
            PatchOptionColorPickerDialog(
                title = scope.option.title,
                currentColor = fieldValue,
                onColorSelected = {
                    fieldValue = it
                    showColorPicker = false
                },
                onDismiss = { showColorPicker = false }
            )
        }

        AlertDialog(
            onDismissRequest = {
                if (pickerImportInProgress) cancelPickerImport() else dismissDialog()
            },
            title = { CenteredDialogTitle(scope.option.title) },
            text = {
                OutlinedTextField(
                    value = fieldValue,
                    onValueChange = { fieldValue = it },
                    readOnly = isTypedPathOption,
                    placeholder = {
                        Text(
                            stringResource(
                                if (isColorOption) R.string.color_option_input_placeholder
                                else R.string.dialog_input_placeholder
                            )
                        )
                    },
                    isError = validatorFailed,
                    supportingText = {
                        if (validatorFailed) {
                            Text(
                                stringResource(R.string.input_dialog_value_invalid),
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    },
                    trailingIcon = {
                        var showDropdownMenu by rememberSaveable { mutableStateOf(false) }
                        if (pickerImportInProgress) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .padding(12.dp)
                                    .width(24.dp)
                                    .height(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            IconButton(
                                onClick = { showDropdownMenu = true }
                            ) {
                                Icon(
                                    Icons.Outlined.MoreVert,
                                    stringResource(R.string.string_option_menu_description)
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = showDropdownMenu,
                            onDismissRequest = { showDropdownMenu = false }
                        ) {
                            if (isColorOption) {
                                DropdownMenuItem(
                                    leadingIcon = {
                                        ColorOptionSwatch(fieldValue)
                                    },
                                    text = {
                                        Text(stringResource(R.string.color_picker))
                                    },
                                    onClick = {
                                        showDropdownMenu = false
                                        showColorPicker = true
                                    }
                                )
                            }
                            if (scope.option.explicitKind != ExplicitOptionKind.Color) {
                                DropdownMenuItem(
                                    leadingIcon = {
                                        Icon(
                                            when {
                                                isImageOption -> Icons.Outlined.Image
                                                isFileOption ->
                                                    Icons.AutoMirrored.Outlined.InsertDriveFile
                                                else -> Icons.Outlined.Folder
                                            },
                                            null
                                        )
                                    },
                                    text = {
                                        Text(stringResource(R.string.path_selector))
                                    },
                                    onClick = {
                                        showDropdownMenu = false
                                        if (useCustomFilePicker) {
                                            if (fs.hasStoragePermission()) {
                                                showFileDialog = true
                                            } else {
                                                permissionLauncher.launch(permissionName)
                                            }
                                        } else if (isFolderOption) {
                                            openDocumentTreeLauncher.launch(null)
                                        } else {
                                            openDocumentLauncher.launch(pickerMimeTypes)
                                        }
                                    }
                                )
                            }
                            if (!scope.option.required &&
                                isTypedPathOption &&
                                fieldValue.isNotBlank()
                            ) {
                                DropdownMenuItem(
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Delete, null)
                                    },
                                    text = {
                                        Text(stringResource(R.string.clear))
                                    },
                                    onClick = {
                                        showDropdownMenu = false
                                        if (fieldValue == importedPath) discardImportedPath()
                                        fieldValue = ""
                                    }
                                )
                            }
                        }
                    }
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !validatorFailed && !pickerImportInProgress,
                    onClick = {
                        val value = if (isColorOption) {
                            normalizeColorOptionValue(fieldValue) ?: fieldValue.trim()
                        } else {
                            fieldValue
                        }
                        importedPath
                            ?.takeIf { it != value }
                            ?.let(fs::deletePatchOptionInputAsync)
                        importedPath = null
                        scope.submitDialog(value)
                    }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (pickerImportInProgress) cancelPickerImport() else dismissDialog()
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ColorOptionSwatch(value: String?) {
    val color = remember(value) { parseColorOption(value) }
    Box(
        modifier = Modifier
            .width(28.dp)
            .height(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(color ?: MaterialTheme.colorScheme.surfaceVariant)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outline,
                RoundedCornerShape(8.dp)
            )
    )
}

@Composable
private fun PatchOptionColorPickerDialog(
    title: String,
    currentColor: String,
    onColorSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val initialColor = remember(currentColor) { parseColorOption(currentColor) ?: Color.Black }
    val initialRed = remember(initialColor) { (initialColor.red * 255).roundToInt() }
    val initialGreen = remember(initialColor) { (initialColor.green * 255).roundToInt() }
    val initialBlue = remember(initialColor) { (initialColor.blue * 255).roundToInt() }

    var red by rememberSaveable(initialRed) { mutableStateOf(initialRed) }
    var green by rememberSaveable(initialGreen) { mutableStateOf(initialGreen) }
    var blue by rememberSaveable(initialBlue) { mutableStateOf(initialBlue) }
    var hexInput by rememberSaveable(currentColor) {
        mutableStateOf(normalizeColorOptionValue(currentColor) ?: rgbToColorHex(initialRed, initialGreen, initialBlue))
    }
    val hexInputInvalid = remember(hexInput) {
        hexInput.isNotBlank() && normalizeColorOptionValue(hexInput) == null
    }
    val previewColor = remember(red, green, blue) { rgbToColor(red, green, blue) }

    fun updateFromColor(r: Int = red, g: Int = green, b: Int = blue) {
        red = r.coerceIn(0, 255)
        green = g.coerceIn(0, 255)
        blue = b.coerceIn(0, 255)
        hexInput = rgbToColorHex(red, green, blue)
    }

    AlertDialogExtended(
        onDismissRequest = onDismiss,
        title = { CenteredDialogTitle(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.color_option_preview),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(previewColor)
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = rgbToColorHex(red, green, blue),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (previewColor.red + previewColor.green + previewColor.blue > 1.5f) {
                            Color.Black
                        } else {
                            Color.White
                        }
                    )
                }
                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { input ->
                        hexInput = input
                        parseColorOption(input)?.let { color ->
                            red = (color.red * 255).roundToInt()
                            green = (color.green * 255).roundToInt()
                            blue = (color.blue * 255).roundToInt()
                        }
                    },
                    singleLine = true,
                    isError = hexInputInvalid,
                    placeholder = { Text(stringResource(R.string.color_option_input_placeholder)) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Center),
                    supportingText = {
                        if (hexInputInvalid) {
                            Text(
                                stringResource(R.string.input_dialog_value_invalid),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                ColorChannelSlider(
                    label = stringResource(R.string.color_channel_red),
                    value = red,
                    trackColor = Color.Red,
                    onValueChange = { updateFromColor(r = it) }
                )
                ColorChannelSlider(
                    label = stringResource(R.string.color_channel_green),
                    value = green,
                    trackColor = Color.Green,
                    onValueChange = { updateFromColor(g = it) }
                )
                ColorChannelSlider(
                    label = stringResource(R.string.color_channel_blue),
                    value = blue,
                    trackColor = Color.Blue,
                    onValueChange = { updateFromColor(b = it) }
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !hexInputInvalid,
                onClick = {
                    onColorSelected(normalizeColorOptionValue(hexInput) ?: rgbToColorHex(red, green, blue))
                }
            ) {
                Text(stringResource(R.string.apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun ColorChannelSlider(
    label: String,
    value: Int,
    trackColor: Color,
    onValueChange: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(value.toString(), style = MaterialTheme.typography.labelMedium)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = 0f..255f,
            colors = SliderDefaults.colors(
                activeTrackColor = trackColor,
                inactiveTrackColor = trackColor.copy(alpha = 0.3f),
                thumbColor = trackColor
            )
        )
    }
}

// Code adapted from Morphe, see third-party/NOTICE for more information
// https://github.com/MorpheApp/morphe-manager/pull/706
private fun normalizeExtension(extension: String): String =
    extension.trim().trimStart('.').lowercase(Locale.ROOT)

internal fun extensionsToMimeTypes(
    extensions: List<String>?,
    mimeTypeForExtension: (String) -> String? = {
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(it)
    }
): Array<String> {
    if (extensions.isNullOrEmpty()) return emptyArray()
    val mimeTypes = extensions.map { extension ->
        mimeTypeForExtension(normalizeExtension(extension)) ?: return emptyArray()
    }
    return mimeTypes.distinct().toTypedArray()
}

internal fun resolvePickerMimeTypes(
    extensions: List<String>?,
    imageOnly: Boolean,
    mimeTypeForExtension: (String) -> String? = {
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(it)
    }
): Array<String> {
    if (extensions.isNullOrEmpty()) {
        return arrayOf(if (imageOnly) IMAGE_MIMETYPE else WILDCARD_MIMETYPE)
    }
    return extensionsToMimeTypes(extensions, mimeTypeForExtension)
        .ifEmpty { arrayOf(WILDCARD_MIMETYPE) }
}

private fun Path.matchesAllowedExtensions(
    allowedExtensions: List<String>?,
    imageOnly: Boolean
): Boolean {
    val extension = fileName
        ?.toString()
        ?.substringAfterLast('.', "")
        ?.let(::normalizeExtension)
        .orEmpty()
    if (!isDocumentExtensionAllowed(extension, allowedExtensions)) return false
    if (!allowedExtensions.isNullOrEmpty()) return true
    if (!imageOnly) return true
    return MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(extension)
        ?.startsWith("image/")
        ?: false
}

internal fun isDocumentExtensionAllowed(
    extension: String,
    allowedExtensions: List<String>?
): Boolean =
    allowedExtensions.isNullOrEmpty() ||
        normalizeExtension(extension) in allowedExtensions.map(::normalizeExtension)

private fun Option<String>.isColorOption(): Boolean {
    when (explicitKind) {
        ExplicitOptionKind.Color -> return true
        null -> Unit
        else -> return false
    }

    val text = listOf(key, title, description)
        .joinToString(" ")
        .lowercase(Locale.US)
    if (isPathOption(text)) return false
    if ("color" in text || "colour" in text || "hex" in text) return true

    if (default?.trim()?.isColorLikeOptionValue() == true) return true
    return presets?.values?.any { it?.trim()?.isColorLikeOptionValue() == true } == true
}

private fun isPathOption(text: String): Boolean =
    "path" in text ||
        "folder" in text ||
        "directory" in text ||
        Regex("""(^|[^a-z])file([^a-z]|$)|file[_\-\s]?(path|name)""").containsMatchIn(text)

private fun String.isColorLikeOptionValue(): Boolean {
    val trimmed = trim()
    if (trimmed.isEmpty()) return false

    return trimmed.startsWith("@android:color/") ||
        trimmed.startsWith("@color/") ||
        ((trimmed.startsWith("#") || trimmed.startsWith("0x") || trimmed.startsWith("0X")) &&
            normalizeColorOptionValue(trimmed) != null)
}

private fun normalizeColorOptionValue(value: String): String? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return ""
    if (trimmed.startsWith("@")) return trimmed

    val hex = trimmed
        .removePrefix("#")
        .removePrefix("0x")
        .removePrefix("0X")
    if (!hex.matches(Regex("^[0-9a-fA-F]{3,4}$|^[0-9a-fA-F]{6}$|^[0-9a-fA-F]{8}$"))) {
        return null
    }

    val expanded = when (hex.length) {
        3 -> hex.flatMap { listOf(it, it) }.joinToString("")
        4 -> "${hex[3]}${hex[3]}${hex[0]}${hex[0]}${hex[1]}${hex[1]}${hex[2]}${hex[2]}"
        else -> hex
    }.uppercase(Locale.US)

    val normalized = "#$expanded"
    return if (runCatching { AndroidColor.parseColor(normalized) }.isSuccess) normalized else null
}

private fun parseColorOption(value: String?): Color? {
    val normalized = normalizeColorOptionValue(value.orEmpty()) ?: return null
    if (normalized.startsWith("@") || normalized.isBlank()) return null
    return runCatching { Color(AndroidColor.parseColor(normalized)) }.getOrNull()
}

private fun rgbToColor(red: Int, green: Int, blue: Int): Color = Color(
    red = red.coerceIn(0, 255) / 255f,
    green = green.coerceIn(0, 255) / 255f,
    blue = blue.coerceIn(0, 255) / 255f
)

private fun rgbToColorHex(red: Int, green: Int, blue: Int): String =
    "#%02X%02X%02X".format(
        red.coerceIn(0, 255),
        green.coerceIn(0, 255),
        blue.coerceIn(0, 255)
    )

private suspend fun importDocumentUriToLocalPath(
    fs: Filesystem,
    uri: Uri,
    allowedExtensions: List<String>?,
    imageOnly: Boolean
): String? {
    val completedImportPath = currentCoroutineContext()[CompletedImportTracker]?.path
    val resolver = fs.contentResolver
    val displayName = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex < 0) null else cursor.getString(nameIndex)
            }.orEmpty()
    }.getOrDefault("")
    val mimeType = resolver.getType(uri)
    val extension = displayName
        .substringAfterLast('.', "")
        .let(::normalizeExtension)
        .ifBlank {
            mimeType
                ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
                .orEmpty()
                .let(::normalizeExtension)
        }
    if (!isDocumentExtensionAllowed(extension, allowedExtensions)) return null
    if (imageOnly && allowedExtensions.isNullOrEmpty()) {
        val isImage = mimeType?.startsWith("image/") == true ||
            MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(extension)
                ?.startsWith("image/") == true
        if (!isImage) return null
    }

    val outFile = fs.createPatchOptionInputFile(extension)

    return runCatching {
        val input = resolver.openInputStream(uri) ?: error("Unable to open selected document")
        input.use { source ->
            outFile.outputStream().use { output -> copyDocumentStream(source, output) }
        }
        fs.completePatchOptionInput(outFile).absolutePath.also { completedImportPath?.set(it) }
    }.onFailure { error ->
        fs.deletePatchOptionInput(outFile.absolutePath)
        if (error is CancellationException) throw error
    }.getOrNull()
}

private suspend fun importDocumentTreeUriToLocalPath(
    app: Application,
    fs: Filesystem,
    uri: Uri
): String? {
    val completedImportPath = currentCoroutineContext()[CompletedImportTracker]?.path
    val sourceRoot = DocumentFile.fromTreeUri(app, uri)
        ?.takeIf { it.isDirectory }
        ?: return null
    var outDir: File? = null

    return runCatching {
        fs.createPatchOptionInputDirectory().also { outDir = it }.let { destination ->
            copyDocumentTree(fs, sourceRoot.uri, destination, FolderImportBudget(), depth = 0)
            fs.completePatchOptionInput(destination)
                .also { outDir = it }
                .absolutePath
                .also { completedImportPath?.set(it) }
        }
    }.onFailure { error ->
        outDir?.let { fs.deletePatchOptionInput(it.absolutePath) }
        if (error is CancellationException) throw error
    }.getOrNull()
}

private const val MAX_IMPORTED_FOLDER_ENTRIES = 10_000
private const val MAX_IMPORTED_FOLDER_DEPTH = 64
private const val MAX_IMPORTED_FOLDER_BYTES = 512L * 1024L * 1024L

internal class FolderImportBudget(
    private val maxEntries: Int = MAX_IMPORTED_FOLDER_ENTRIES,
    private val maxDepth: Int = MAX_IMPORTED_FOLDER_DEPTH,
    private val maxBytes: Long = MAX_IMPORTED_FOLDER_BYTES
) {
    private var entryCount = 0
    private var byteCount = 0L

    fun checkDepth(depth: Int) {
        check(depth <= maxDepth) {
            "Selected folder is nested too deeply"
        }
    }

    fun recordEntry() {
        check(++entryCount <= maxEntries) {
            "Selected folder contains too many entries"
        }
    }

    fun recordBytes(bytes: Int) {
        byteCount += bytes
        check(byteCount <= maxBytes) {
            "Selected folder is too large"
        }
    }
}

private suspend fun copyDocumentTree(
    fs: Filesystem,
    sourceUri: Uri,
    target: File,
    budget: FolderImportBudget,
    depth: Int
) {
    budget.checkDepth(depth)
    currentCoroutineContext().ensureActive()
    queryDocumentTreeChildren(fs, sourceUri, budget).forEachIndexed { index, child ->
        currentCoroutineContext().ensureActive()
        val childName = safeDocumentName(child.name, index)
        val destination = uniqueDocumentDestination(target, childName)
        if (child.isDirectory) {
            check(destination.mkdirs()) { "Unable to create directory for ${child.uri}" }
            copyDocumentTree(fs, child.uri, destination, budget, depth + 1)
        } else {
            val input = fs.contentResolver.openInputStream(child.uri)
                ?: error("Unable to open ${child.uri}")
            input.use { sourceStream ->
                destination.outputStream().use { output ->
                    copyDocumentStream(sourceStream, output, budget)
                }
            }
        }
    }
}

private data class DocumentTreeEntry(
    val uri: Uri,
    val name: String?,
    val isDirectory: Boolean
)

private suspend fun queryDocumentTreeChildren(
    fs: Filesystem,
    parentUri: Uri,
    budget: FolderImportBudget
): List<DocumentTreeEntry> {
    val parentDocumentId = DocumentsContract.getDocumentId(parentUri)
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
        parentUri,
        parentDocumentId
    )
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE
    )

    return fs.contentResolver.query(childrenUri, projection, null, null, null)
        ?.use { cursor ->
            val documentIdIndex =
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val displayNameIndex =
                cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeTypeIndex =
                cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val children = mutableListOf<DocumentTreeEntry>()
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                budget.recordEntry()
                val documentId = cursor.getString(documentIdIndex)
                val childUri =
                    DocumentsContract.buildDocumentUriUsingTree(parentUri, documentId)
                val displayName = displayNameIndex
                    .takeIf { it >= 0 }
                    ?.let { cursor.getString(it) }
                val mimeType = mimeTypeIndex
                    .takeIf { it >= 0 }
                    ?.let { cursor.getString(it) }
                children.add(
                    DocumentTreeEntry(
                        uri = childUri,
                        name = displayName,
                        isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
                    )
                )
            }
            children
        }
        ?: error("Unable to list $parentUri")
}

private suspend fun copyDocumentStream(
    input: InputStream,
    output: OutputStream,
    folderBudget: FolderImportBudget? = null
) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        currentCoroutineContext().ensureActive()
        val read = input.read(buffer)
        if (read < 0) return
        folderBudget?.recordBytes(read)
        output.write(buffer, 0, read)
    }
}

internal fun safeDocumentName(name: String?, index: Int): String {
    val preserved = name
        ?.replace('\u0000', '_')
        ?.replace('/', '_')
        ?.replace('\\', '_')
        ?.takeUnless { it.isEmpty() || it == "." || it == ".." }
    return preserved ?: "item_$index"
}

private fun uniqueDocumentDestination(parent: File, name: String): File {
    var candidate = parent.resolve(name)
    if (!candidate.exists()) return candidate

    val extension = name.substringAfterLast('.', "").takeIf(String::isNotEmpty)
    val stem = if (extension == null) name else name.removeSuffix(".$extension")
    var suffix = 2
    while (candidate.exists()) {
        val uniqueName = if (extension == null) {
            "${stem}_$suffix"
        } else {
            "${stem}_$suffix.$extension"
        }
        candidate = parent.resolve(uniqueName)
        suffix++
    }
    return candidate
}

private abstract class NumberOptionEditor<T : Number> : OptionEditor<T> {
    @Composable
    abstract fun NumberDialog(
        title: String,
        current: T?,
        validator: (T?) -> Boolean,
        onSubmit: (T?) -> Unit
    )

    @Composable
    override fun Dialog(scope: OptionEditorScope<T>) {
        NumberDialog(scope.option.title, scope.value, scope.option.validator) {
            if (it == null) return@NumberDialog scope.dismissDialog()

            scope.submitDialog(it)
        }
    }
}

private object IntOptionEditor : NumberOptionEditor<Int>() {
    @Composable
    override fun NumberDialog(
        title: String,
        current: Int?,
        validator: (Int?) -> Boolean,
        onSubmit: (Int?) -> Unit
    ) = IntInputDialog(current, title, validator, onSubmit)
}

private object LongOptionEditor : NumberOptionEditor<Long>() {
    @Composable
    override fun NumberDialog(
        title: String,
        current: Long?,
        validator: (Long?) -> Boolean,
        onSubmit: (Long?) -> Unit
    ) = LongInputDialog(current, title, validator, onSubmit)
}

private object FloatOptionEditor : NumberOptionEditor<Float>() {
    @Composable
    override fun NumberDialog(
        title: String,
        current: Float?,
        validator: (Float?) -> Boolean,
        onSubmit: (Float?) -> Unit
    ) = FloatInputDialog(current, title, validator, onSubmit)
}

private object DoubleOptionEditor : NumberOptionEditor<Double>() {
    @Composable
    override fun NumberDialog(
        title: String,
        current: Double?,
        validator: (Double?) -> Boolean,
        onSubmit: (Double?) -> Unit
    ) = DoubleInputDialog(current, title, validator, onSubmit)
}

private object BooleanOptionEditor : OptionEditor<Boolean> {
    override fun clickAction(scope: OptionEditorScope<Boolean>) {
        scope.setValue(!scope.current)
    }

    @Composable
    override fun ListItemTrailingContent(scope: OptionEditorScope<Boolean>) {
        HapticSwitch(
            checked = scope.current,
            onCheckedChange = { value ->
                scope.checkSafeguard {
                    scope.setValue(value)
                }
            }
        )
    }

    @Composable
    override fun Dialog(scope: OptionEditorScope<Boolean>) {
    }

    private val OptionEditorScope<Boolean>.current get() = value ?: false
}

private object UnknownTypeEditor : OptionEditor<Any>, KoinComponent {
    override fun clickAction(scope: OptionEditorScope<Any>) =
        get<Application>().toast("Unknown type: ${scope.option.type}")

    @Composable
    override fun Dialog(scope: OptionEditorScope<Any>) {
    }
}

/**
 * A wrapper for [OptionEditor]s that shows selectable presets.
 *
 * @param innerEditor The [OptionEditor] for [T].
 */
private class PresetOptionEditor<T : Any>(private val innerEditor: OptionEditor<T>) :
    OptionEditor<T> {
    @Composable
    override fun ListItemTrailingContent(scope: OptionEditorScope<T>) {
        innerEditor.ListItemTrailingContent(scope)
    }

    @Composable
    override fun Dialog(scope: OptionEditorScope<T>) {
        var selectedPreset by rememberSaveable(scope.value, scope.option.presets) {
            val presets = scope.option.presets!!

            mutableStateOf(presets.entries.find { it.value == scope.value }?.key)
        }

        WithOptionEditor(
            innerEditor,
            scope.option,
            scope.value,
            scope.setValue,
            scope.selectionWarningEnabled,
            onDismissDialog = scope.dismissDialog
        ) inner@{
            var hidePresetsDialog by rememberSaveable {
                mutableStateOf(false)
            }
            var openCustomDialog by rememberSaveable {
                mutableStateOf(false)
            }
            LaunchedEffect(openCustomDialog) {
                if (openCustomDialog) {
                    openCustomDialog = false
                    this@inner.openDialog()
                }
            }
            if (hidePresetsDialog) return@inner

            // TODO: add a divider for scrollable content
            AlertDialogExtended(
                onDismissRequest = scope.dismissDialog,
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (selectedPreset != null) scope.submitDialog(
                                scope.option.presets?.get(
                                    selectedPreset
                                )
                            )
                            else {
                                // Hide the presets dialog so it doesn't show up in the background.
                                hidePresetsDialog = true
                                // Open the custom dialog on the next frame to avoid flicker.
                                openCustomDialog = true
                            }
                        }
                    ) {
                        Text(stringResource(if (selectedPreset != null) R.string.save else R.string.continue_))
                    }
                },
                dismissButton = {
                    TextButton(onClick = scope.dismissDialog) {
                        Text(stringResource(R.string.cancel))
                    }
                },
                title = { CenteredDialogTitle(scope.option.title) },
                textHorizontalPadding = PaddingValues(horizontal = 0.dp),
                text = {
                    val presets = remember(scope.option.presets) {
                        scope.option.presets?.entries?.toList().orEmpty()
                    }
                    val isColorOption = remember(scope.option) {
                        if (scope.option.type != typeOf<String>()) {
                            false
                        } else {
                            @Suppress("UNCHECKED_CAST")
                            (scope.option as Option<String>).isColorOption()
                        }
                    }

                    LazyColumn {
                        @Composable
                        fun Item(title: String, value: Any?, presetKey: String?) {
                            ListItem(
                                selected = selectedPreset == presetKey,
                                onClick = { selectedPreset = presetKey }.withHapticFeedback(HapticFeedbackConstants.CLOCK_TICK),
                                headlineContent = { Text(title) },
                                supportingContent = value?.toString()?.let { { Text(it) } },
                                leadingContent = {
                                    ListItemRadioButton(selected = selectedPreset == presetKey)
                                },
                                trailingContent = if (isColorOption && value is String) {
                                    { ColorOptionSwatch(value) }
                                } else {
                                    null
                                },
                                colors = transparentListItemColors
                            )
                        }

                        items(presets, key = { it.key }) {
                            Item(it.key, it.value, it.key)
                        }

                        item(key = null) {
                            Item(stringResource(R.string.option_preset_custom_value), null, null)
                        }
                    }
                }
            )
        }
    }
}

private class ListOptionEditor<T : Serializable>(private val elementEditor: OptionEditor<T>) :
    OptionEditor<List<T>> {
    private fun createElementOption(option: Option<List<T>>) = Option<T>(
        title = option.title,
        key = option.key,
        description = option.description,
        required = option.required,
        type = option.type.arguments.first().type!!,
        default = null,
        presets = null,
        validator = { true },
        // Code adapted from Morphe, see third-party/NOTICE for more information
        // https://github.com/MorpheApp/morphe-manager/pull/706
        explicitKind = option.explicitKind?.takeIf { it == ExplicitOptionKind.Files },
        allowedExtensions = option.allowedExtensions,
    )

    @OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
    @Composable
    override fun Dialog(scope: OptionEditorScope<List<T>>) {
        val fs: Filesystem = koinInject()
        val managedInputsAtOpen = remember { fs.patchOptionInputPaths() }
        val managedInputsAcceptedHere = rememberSaveable(saver = snapshotStateSetSaver()) {
            mutableStateSetOf<String>()
        }
        val hostActivity = LocalContext.current.findActivity()
        LaunchedEffect(Unit) {
            if (managedInputsAcceptedHere.isNotEmpty()) {
                fs.claimPatchOptionInputs(managedInputsAcceptedHere)
            }
        }
        DisposableEffect(hostActivity) {
            onDispose {
                if (shouldDeletePendingDialogInputs(
                        isChangingConfigurations = hostActivity?.isChangingConfigurations == true
                    )
                ) {
                    managedInputsAcceptedHere.forEach(fs::deletePatchOptionInputAsync)
                }
            }
        }
        val items =
            rememberSaveable(scope.value, saver = snapshotStateListSaver()) {
                // We need a key for each element in order to support dragging.
                scope.value?.map(::Item)?.toMutableStateList() ?: mutableStateListOf()
            }

        val lazyListState = rememberLazyListState()
        val reorderableLazyColumnState =
            // Update the list
            rememberReorderableLazyListState(lazyListState) { from, to ->
                // Update the list
                items.add(to.index, items.removeAt(from.index))
            }

        var deleteMode by rememberSaveable {
            mutableStateOf(false)
        }
        val deletionTargets = rememberSaveable(saver = snapshotStateSetSaver()) {
            mutableStateSetOf<Int>()
        }

        val back = back@{
            if (deleteMode) {
                deletionTargets.clear()
                deleteMode = false
                return@back
            }

            val nextValues = normalizeListOptionValues(
                values = items.map { it.value },
                explicitKind = scope.option.explicitKind
            )
            val retainedPaths = nextValues.filterIsInstance<String>().toSet()
            managedInputsAcceptedHere
                .filterNot(retainedPaths::contains)
                .forEach(fs::deletePatchOptionInputAsync)

            if (!shouldSubmitListOption(
                    currentValues = scope.value.orEmpty(),
                    nextValues = nextValues,
                    explicitKind = scope.option.explicitKind,
                    required = scope.option.required
                )
            ) {
                scope.dismissDialog()
                return@back
            }

            managedInputsAcceptedHere.clear()
            scope.submitDialog(nextValues)
        }

            FullscreenDialog(
                onDismissRequest = back,
            ) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.surface,
                    topBar = {
                        AppTopBar(
                            title = if (deleteMode) pluralStringResource(
                                R.plurals.selected_apps_count,
                            deletionTargets.size,
                            deletionTargets.size
                            ) else scope.option.title,
                            onBackClick = back,
                            applyContainerColor = true,
                            backIcon = {
                                if (deleteMode) {
                                    return@AppTopBar Icon(
                                    Icons.Filled.Close,
                                    stringResource(R.string.cancel)
                                )
                            }

                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                        },
                        actions = {
                            if (deleteMode) {
                                IconButton(
                                    onClick = {
                                        if (items.size == deletionTargets.size) deletionTargets.clear()
                                        else deletionTargets.addAll(items.map { it.key })
                                    }
                                ) {
                                    Icon(
                                        Icons.Outlined.SelectAll,
                                        stringResource(R.string.select_deselect_all)
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        items.removeIf { it.key in deletionTargets }
                                        deletionTargets.clear()
                                        deleteMode = false
                                    }
                                ) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        stringResource(R.string.delete)
                                    )
                                }
                            } else {
                                IconButton(onClick = items::clear) {
                                    Icon(Icons.Outlined.Restore, stringResource(R.string.reset))
                                }
                            }
                        }
                    )
                },
                floatingActionButton = {
                    if (deleteMode) return@Scaffold

                    HapticExtendedFloatingActionButton(
                        text = { Text(stringResource(R.string.add)) },
                        icon = {
                            Icon(
                                Icons.Outlined.Add,
                                stringResource(R.string.add)
                            )
                        },
                        expanded = lazyListState.isScrollingUp,
                        onClick = { items.add(Item(null)) }
                    )
                }
            ) { paddingValues ->
                val elementOption = remember(scope.option) { createElementOption(scope.option) }

                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(paddingValues),
                ) {
                    itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
                        val interactionSource = remember { MutableInteractionSource() }

                        ReorderableItem(reorderableLazyColumnState, key = item.key) { _ ->
                            val reorderableScope = this
                            WithOptionEditor(
                                elementEditor,
                                elementOption,
                                value = item.value,
                                setValue = { value ->
                                    (value as? String)
                                        ?.takeIf { it !in managedInputsAtOpen }
                                        ?.takeIf { it in fs.patchOptionInputPaths() }
                                        ?.let(managedInputsAcceptedHere::add)
                                    items[index] = item.copy(value = value)
                                },
                                selectionWarningEnabled = scope.selectionWarningEnabled
                            ) {
                                val headline: @Composable () -> Unit = {
                                    val value = item.value
                                    if (value == null) {
                                        Text(stringResource(R.string.empty), fontStyle = FontStyle.Italic)
                                    } else {
                                        Text(value.toString())
                                    }
                                }
                                val dragHandle: @Composable () -> Unit = {
                                    IconButton(
                                        onClick = {},
                                        interactionSource = interactionSource,
                                        modifier = with(reorderableScope) { Modifier.longPressDraggableHandle() }
                                    ) {
                                        Icon(Icons.Filled.DragHandle, stringResource(R.string.drag_handle))
                                    }
                                }
                                if (deleteMode) {
                                    val checked = item.key in deletionTargets
                                    ListItem(
                                        checked = checked,
                                        onCheckedChange = { select ->
                                            if (select) deletionTargets.add(item.key) else deletionTargets.remove(item.key)
                                            deleteMode = deletionTargets.isNotEmpty()
                                        },
                                        headlineContent = headline,
                                        leadingContent = dragHandle,
                                        trailingContent = { Checkbox(checked = checked, onCheckedChange = null) }
                                    )
                                } else {
                                    ListItem(
                                        onClick = ::clickAction,
                                        onLongClickLabel = stringResource(R.string.select),
                                        onLongClick = {
                                            deletionTargets.add(item.key)
                                            deleteMode = true
                                        },
                                        interactionSource = interactionSource,
                                        headlineContent = headline,
                                        leadingContent = dragHandle,
                                        trailingContent = { ListItemTrailingContent() }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Parcelize
    private data class Item<T : Serializable>(val value: T?, val key: Int = Random.nextInt()) :
        Parcelable
}

internal fun <T> normalizeListOptionValues(
    values: Iterable<T?>,
    explicitKind: ExplicitOptionKind?
): List<T> = values.mapNotNull { value ->
    value?.takeUnless {
        explicitKind == ExplicitOptionKind.Files && it is String && it.isBlank()
    }
}

internal fun shouldSubmitListOption(
    currentValues: List<*>,
    nextValues: List<*>,
    explicitKind: ExplicitOptionKind?,
    required: Boolean
): Boolean =
    currentValues != nextValues &&
        !(explicitKind == ExplicitOptionKind.Files && required && nextValues.isEmpty())

internal fun shouldDeletePendingDialogInputs(isChangingConfigurations: Boolean): Boolean =
    !isChangingConfigurations

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
