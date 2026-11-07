package app.universal.patch.manager.ui.screen

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import app.universal.patch.manager.ui.component.LazyColumnWithScrollbar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.universal.patch.manager.data.platform.Filesystem
import app.universal.patch.manager.domain.manager.KeystoreManager
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.ui.component.AppScaffold
import app.universal.patch.manager.ui.component.AppTopBar
import app.universal.patch.manager.ui.component.ExportSavedApkFileNameDialog
import app.universal.patch.manager.ui.component.PasswordField
import app.universal.patch.manager.ui.component.haptics.HapticExtendedFloatingActionButton
import app.universal.patch.manager.ui.component.patches.PathSelectorDialog
import app.universal.patch.manager.ui.component.RememberedCreateDocument
import app.universal.patch.manager.ui.component.RememberedGetContent
import app.universal.patch.manager.ui.component.toPickerDirectoryUri
import app.universal.patch.manager.util.toast
import app.universal.patch.manager.R
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeystoreConverterScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keystoreManager: KeystoreManager = koinInject()
    val prefs: PreferencesManager = koinInject()
    val fs: Filesystem = koinInject()
    val useCustomFilePicker by prefs.useCustomFilePicker.getAsState()
    val keystoreConverterInputDirectory by prefs.keystoreConverterInputLastDirectory.getAsState()
    val convertedKeystoreExportDirectory by prefs.convertedKeystoreExportLastDirectory.getAsState()
    val roots = remember { fs.storageRoots() }
    val (permissionContract, permissionName) = remember { fs.permissionContract() }

    var inputSource by rememberSaveable { mutableStateOf<String?>(null) }
    var inputDisplayName by rememberSaveable { mutableStateOf<String?>(null) }
    var alias by rememberSaveable { mutableStateOf("") }
    var storePassword by rememberSaveable { mutableStateOf("") }
    var keyPassword by rememberSaveable { mutableStateOf("") }
    var format by rememberSaveable { mutableStateOf(KeystoreManager.KeystoreFormat.PKCS12) }

    var converting by rememberSaveable { mutableStateOf(false) }
    var convertedKeystore by remember { mutableStateOf<KeystoreManager.KeystoreBinary?>(null) }
    var errorText by rememberSaveable { mutableStateOf<String?>(null) }

    var showInputPicker by rememberSaveable { mutableStateOf(false) }
    var showOutputPicker by rememberSaveable { mutableStateOf(false) }
    var outputDialogState by remember { mutableStateOf<ConverterSaveDialogState?>(null) }
    var pendingPermission by rememberSaveable { mutableStateOf<KeystorePermissionRequest?>(null) }

    fun defaultOutputName(): String {
        val sourceName = inputDisplayName
            ?.substringBeforeLast('.')
            ?.ifBlank { "converted-keystore" }
            ?: "converted-keystore"
        val sanitized = sourceName.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "converted-keystore" }
        return "$sanitized.${format.extension}"
    }

    fun setInput(uri: Uri, fallbackName: String?) {
        val displayName = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()
        inputSource = uri.toString()
        inputDisplayName = displayName ?: fallbackName ?: uri.lastPathSegment ?: uri.toString()
        convertedKeystore = null
        errorText = null
    }

    val permissionLauncher = rememberLauncherForActivityResult(permissionContract) { granted ->
        val request = pendingPermission
        pendingPermission = null
        if (!granted) return@rememberLauncherForActivityResult
        when (request) {
            KeystorePermissionRequest.INPUT -> showInputPicker = true
            KeystorePermissionRequest.OUTPUT -> showOutputPicker = true
            null -> Unit
        }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        RememberedGetContent {
            keystoreConverterInputDirectory.takeIf(String::isNotBlank)?.let(Uri::parse)
        }
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            prefs.keystoreConverterInputLastDirectory.update(uri.toPickerDirectoryUri().toString())
        }
        val displayName = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
        if (!isKeystoreFileName(displayName)) {
            errorText = context.getString(R.string.selected_file_not_supported_keystore_info)
            return@rememberLauncherForActivityResult
        }
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        setInput(uri, displayName)
    }

    val saveDocumentLauncher = rememberLauncherForActivityResult(
        RememberedCreateDocument("application/octet-stream") {
            convertedKeystoreExportDirectory.takeIf(String::isNotBlank)?.let(Uri::parse)
        }
    ) { uri ->
        val keystore = convertedKeystore ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            prefs.convertedKeystoreExportLastDirectory.update(uri.toPickerDirectoryUri().toString())
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(keystore.bytes)
                    } ?: throw IOException("Unable to open destination.")
                }
            }.onSuccess {
                context.toast(context.getString(R.string.tools_keystore_converter_saved))
            }.onFailure {
                errorText = it.message ?: context.getString(R.string.tools_keystore_converter_save_failed)
            }
        }
    }

    fun requestInputPicker() {
        if (useCustomFilePicker) {
            if (fs.hasStoragePermission()) {
                showInputPicker = true
            } else {
                pendingPermission = KeystorePermissionRequest.INPUT
                permissionLauncher.launch(permissionName)
            }
        } else {
            openDocumentLauncher.launch("application/octet-stream")
        }
    }

    fun requestSave() {
        if (convertedKeystore == null) return
        if (useCustomFilePicker) {
            if (fs.hasStoragePermission()) {
                showOutputPicker = true
            } else {
                pendingPermission = KeystorePermissionRequest.OUTPUT
                permissionLauncher.launch(permissionName)
            }
        } else {
            saveDocumentLauncher.launch(defaultOutputName())
        }
    }

    if (showInputPicker && useCustomFilePicker) {
        PathSelectorDialog(
            roots = roots,
            onSelect = { path ->
                if (path == null) {
                    showInputPicker = false
                    return@PathSelectorDialog
                }
                if (Files.isDirectory(path) || !isKeystoreFile(path)) return@PathSelectorDialog
                showInputPicker = false
                setInput(Uri.fromFile(path.toFile()), path.fileName?.toString())
            },
            fileFilter = ::isKeystoreFile,
            allowDirectorySelection = false,
            fileTypeLabel = ".jks .keystore .p12 .pfx .bks",
            lastDirectoryPreference = prefs.keystoreConverterInputLastDirectory
        )
    }

    if (showOutputPicker && useCustomFilePicker) {
        PathSelectorDialog(
            roots = roots,
            onSelect = { path ->
                if (path == null) showOutputPicker = false
            },
            fileFilter = { false },
            allowDirectorySelection = true,
            confirmButtonText = stringResource(R.string.save),
            onConfirm = { selection ->
                val directory = if (Files.isDirectory(selection)) selection else (selection.parent ?: selection)
                outputDialogState = ConverterSaveDialogState(
                    directory = directory,
                    fileName = defaultOutputName()
                )
            },
            lastDirectoryPreference = prefs.convertedKeystoreExportLastDirectory
        )
    }

    outputDialogState?.let { state ->
        ExportSavedApkFileNameDialog(
            initialName = state.fileName,
            onDismiss = { outputDialogState = null },
            onConfirm = { enteredName ->
                val bytes = convertedKeystore?.bytes ?: return@ExportSavedApkFileNameDialog
                val finalName = enteredName.trim().ifBlank { state.fileName }
                outputDialogState = null
                showOutputPicker = false
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            Files.write(state.directory.resolve(finalName), bytes)
                        }
                    }.onSuccess {
                        context.toast(context.getString(R.string.tools_keystore_converter_saved))
                    }.onFailure {
                        errorText = it.message ?: context.getString(R.string.tools_keystore_converter_save_failed)
                    }
                }
            }
        )
    }

    AppScaffold(
        topBar = { scrollBehavior ->
            AppTopBar(
                title = stringResource(R.string.tools_keystore_converter_title),
                scrollBehavior = scrollBehavior,
                onBackClick = onBackClick
            )
        },
        floatingActionButton = {
            AnimatedVisibility(visible = convertedKeystore != null && !converting) {
                HapticExtendedFloatingActionButton(
                    text = { Text(stringResource(R.string.save)) },
                    icon = { Icon(Icons.Outlined.Save, null) },
                    onClick = ::requestSave
                )
            }
        }
    ) { paddingValues ->
        LazyColumnWithScrollbar(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                            modifier = Modifier.size(22.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.tools_keystore_converter_info),
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 14.sp)
                        )
                    }
                }
            }
            item {
                Button(
                    onClick = ::requestInputPicker,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.FolderOpen, null)
                    Text(
                        text = stringResource(R.string.tools_keystore_converter_select_input),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
            inputDisplayName?.let { displayName ->
                item {
                    Text(
                        text = stringResource(R.string.tools_keystore_converter_selected_input, displayName),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text(stringResource(R.string.import_keystore_dialog_alias_field)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
            item {
                PasswordField(
                    modifier = Modifier.fillMaxWidth(),
                    value = storePassword,
                    onValueChange = { storePassword = it },
                    label = { Text(stringResource(R.string.import_keystore_dialog_password_field)) }
                )
            }
            item {
                PasswordField(
                    modifier = Modifier.fillMaxWidth(),
                    value = keyPassword,
                    onValueChange = { keyPassword = it },
                    label = { Text(stringResource(R.string.import_keystore_dialog_key_password_field)) }
                )
            }
            item {
                Text(
                    text = stringResource(R.string.tools_keystore_converter_target_type),
                    style = MaterialTheme.typography.titleSmall
                )
            }
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    KeystoreManager.KeystoreFormat.entries.forEach { candidate ->
                        FilterChip(
                            onClick = { format = candidate },
                            selected = format == candidate,
                            label = { Text(candidate.label) }
                        )
                    }
                }
            }
            item {
                Button(
                    onClick = {
                        val source = inputSource ?: return@Button
                        converting = true
                        errorText = null
                        convertedKeystore = null
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    openSourceInputStream(context, source).use { input ->
                                        keystoreManager.convertKeystore(
                                            keystore = input,
                                            alias = alias,
                                            storePass = storePassword,
                                            keyPass = keyPassword,
                                            format = format
                                        )
                                    }
                                }
                            }.onSuccess {
                                convertedKeystore = it
                                context.toast(context.getString(R.string.tools_keystore_converter_converted))
                            }.onFailure {
                                errorText = it.message ?: context.getString(R.string.tools_keystore_converter_failed)
                            }
                            converting = false
                        }
                    },
                    enabled = !converting && inputSource != null && storePassword.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (converting) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(vertical = 2.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(stringResource(R.string.tools_keystore_converter_action))
                    }
                }
            }
            convertedKeystore?.let { keystore ->
                item {
                    Text(
                        text = stringResource(
                            R.string.tools_keystore_converter_ready,
                            keystore.format.label.uppercase(),
                            keystore.alias
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            errorText?.let { error ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f))) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Outlined.Info, null)
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    lineBreak = LineBreak.Simple
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class ConverterSaveDialogState(
    val directory: Path,
    val fileName: String
)

private enum class KeystorePermissionRequest {
    INPUT,
    OUTPUT
}

private fun isKeystoreFile(path: Path): Boolean {
    return isKeystoreFileName(path.fileName?.toString())
}

private fun isKeystoreFileName(name: String?): Boolean {
    val normalized = name?.lowercase().orEmpty()
    return normalized.endsWith(".jks") ||
        normalized.endsWith(".keystore") ||
        normalized.endsWith(".p12") ||
        normalized.endsWith(".pfx") ||
        normalized.endsWith(".bks")
}

private fun openSourceInputStream(context: android.content.Context, source: String): InputStream {
    val uri = Uri.parse(source)
    if (uri.scheme.equals("file", ignoreCase = true)) {
        val file = uri.path?.let(::File) ?: throw IOException("Invalid file path.")
        return file.inputStream()
    }
    return context.contentResolver.openInputStream(uri) ?: throw IOException("Unable to open source keystore.")
}
