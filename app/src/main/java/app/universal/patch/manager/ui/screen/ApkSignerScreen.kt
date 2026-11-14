package app.universal.patch.manager.ui.screen

import app.universal.patch.manager.util.managerStorageContext

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import app.universal.patch.manager.ui.component.LazyColumnWithScrollbar
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universal.patch.manager.R
import app.universal.patch.manager.data.platform.Filesystem
import app.universal.patch.manager.domain.manager.KeystoreManager
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.domain.storage.CacheCleanupGuard
import app.universal.patch.manager.ui.component.AppScaffold
import app.universal.patch.manager.ui.component.AppTopBar
import app.universal.patch.manager.ui.component.ConfirmDialog
import app.universal.patch.manager.ui.component.ExportSavedApkFileNameDialog
import app.universal.patch.manager.ui.component.InterceptBackHandler
import app.universal.patch.manager.ui.component.patches.PathSelectorDialog
import app.universal.patch.manager.ui.component.RememberedCreateDocument
import app.universal.patch.manager.ui.component.RememberedGetContent
import app.universal.patch.manager.ui.component.toPickerDirectoryUri
import app.universal.patch.manager.ui.viewmodel.SignatureMetadataInjectorViewModel
import app.universal.patch.manager.util.APK_MIMETYPE
import app.universal.patch.manager.util.APK_SIGNER_CACHE_DIR
import app.universal.patch.manager.util.toast
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApkSignerScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keystoreManager: KeystoreManager = koinInject()
    val prefs: PreferencesManager = koinInject()
    val fs: Filesystem = koinInject()
    val injectorViewModel: SignatureMetadataInjectorViewModel = koinViewModel()
    val injectorState by injectorViewModel.state.collectAsStateWithLifecycle()
    val useCustomFilePicker by prefs.useCustomFilePicker.getAsState()
    val apkSignerInputDirectory by prefs.apkSignerInputLastDirectory.getAsState()
    val signedApkExportDirectory by prefs.signedApkExportLastDirectory.getAsState()
    val roots = remember { fs.storageRoots() }
    val (permissionContract, permissionName) = remember { fs.permissionContract() }

    var inputSource by rememberSaveable { mutableStateOf<String?>(null) }
    var inputDisplayName by rememberSaveable { mutableStateOf<String?>(null) }
    var signing by rememberSaveable { mutableStateOf(false) }
    var signedApk by remember { mutableStateOf<File?>(null) }
    var errorText by rememberSaveable { mutableStateOf<String?>(null) }

    var showInputPicker by rememberSaveable { mutableStateOf(false) }
    var showOutputPicker by rememberSaveable { mutableStateOf(false) }
    var outputDialogState by remember { mutableStateOf<ApkSignerSaveDialogState?>(null) }
    var pendingOutputOverwrite by remember { mutableStateOf<ApkSignerSaveDialogState?>(null) }
    var pendingPermission by rememberSaveable { mutableStateOf<ApkSignerPermissionRequest?>(null) }
    var showInjectorDismissConfirmationDialog by rememberSaveable {
        mutableStateOf(false)
    }

    fun requestBack() {
        if (injectorState.injecting) {
            showInjectorDismissConfirmationDialog = true
        } else {
            onBackClick()
        }
    }

    fun defaultOutputName(): String {
        val sourceName = inputDisplayName
            ?.substringBeforeLast('.')
            ?.ifBlank { "signed-apk" }
            ?: "signed-apk"
        return "${sanitizeApkFileName(sourceName)}-signed.apk"
    }

    fun setInput(uri: Uri, fallbackName: String?) {
        val displayName = queryDisplayName(context, uri) ?: fallbackName ?: uri.lastPathSegment ?: uri.toString()
        inputSource = uri.toString()
        inputDisplayName = displayName
        signedApk?.delete()
        signedApk = null
        errorText = null
    }

    val permissionLauncher = rememberLauncherForActivityResult(permissionContract) { granted ->
        val request = pendingPermission
        pendingPermission = null
        if (!granted) return@rememberLauncherForActivityResult
        when (request) {
            ApkSignerPermissionRequest.INPUT -> showInputPicker = true
            ApkSignerPermissionRequest.OUTPUT -> showOutputPicker = true
            null -> Unit
        }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        RememberedGetContent {
            apkSignerInputDirectory.takeIf(String::isNotBlank)?.let(Uri::parse)
        }
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            prefs.apkSignerInputLastDirectory.update(uri.toPickerDirectoryUri().toString())
        }
        val displayName = queryDisplayName(context, uri) ?: uri.lastPathSegment
        val mimeType = context.contentResolver.getType(uri)
        if (!isApkInput(displayName, mimeType)) {
            errorText = context.getString(R.string.selected_file_not_supported_apk_file)
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
        RememberedCreateDocument(APK_MIMETYPE) {
            signedApkExportDirectory.takeIf(String::isNotBlank)?.let(Uri::parse)
        }
    ) { uri ->
        val apk = signedApk ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            prefs.signedApkExportLastDirectory.update(uri.toPickerDirectoryUri().toString())
            runCatching {
                CacheCleanupGuard.withCacheInUse {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            apk.inputStream().use { input -> input.copyTo(output) }
                        } ?: throw IOException("Unable to open destination.")
                    }
                }
            }.onSuccess {
                context.toast(context.getString(R.string.tools_apk_signer_saved))
            }.onFailure {
                errorText = it.message ?: context.getString(R.string.tools_apk_signer_save_failed)
            }
        }
    }

    fun requestInputPicker() {
        if (useCustomFilePicker) {
            if (fs.hasStoragePermission()) {
                showInputPicker = true
            } else {
                pendingPermission = ApkSignerPermissionRequest.INPUT
                permissionLauncher.launch(permissionName)
            }
        } else {
            openDocumentLauncher.launch("application/*")
        }
    }

    fun requestSave() {
        if (signedApk == null) return
        if (useCustomFilePicker) {
            if (fs.hasStoragePermission()) {
                showOutputPicker = true
            } else {
                pendingPermission = ApkSignerPermissionRequest.OUTPUT
                permissionLauncher.launch(permissionName)
            }
        } else {
            saveDocumentLauncher.launch(defaultOutputName())
        }
    }

    fun saveSignedApk(directory: Path, fileName: String) {
        val apk = signedApk ?: return
        outputDialogState = null
        pendingOutputOverwrite = null
        showOutputPicker = false
        scope.launch {
            runCatching {
                CacheCleanupGuard.withCacheInUse {
                    withContext(Dispatchers.IO) {
                        Files.copy(
                            apk.toPath(),
                            directory.resolve(fileName),
                            StandardCopyOption.REPLACE_EXISTING
                        )
                    }
                }
            }.onSuccess {
                prefs.signedApkExportLastDirectory.update(directory.toString())
                context.toast(context.getString(R.string.tools_apk_signer_saved))
            }.onFailure {
                errorText = it.message
                    ?: context.getString(R.string.tools_apk_signer_save_failed)
            }
        }
    }

    InterceptBackHandler(onBack = ::requestBack)

    LaunchedEffect(injectorState.injecting) {
        if (!injectorState.injecting) {
            showInjectorDismissConfirmationDialog = false
        }
    }

    if (showInjectorDismissConfirmationDialog) {
        ConfirmDialog(
            onDismiss = { showInjectorDismissConfirmationDialog = false },
            onConfirm = {
                showInjectorDismissConfirmationDialog = false
                injectorViewModel.cancelInjection()
                onBackClick()
            },
            title = stringResource(
                R.string.tools_signature_metadata_injector_stop_confirm_title
            ),
            description = stringResource(
                R.string.tools_signature_metadata_injector_stop_confirm_description
            ),
            icon = Icons.Outlined.Cancel
        )
    }

    if (showInputPicker && useCustomFilePicker) {
        PathSelectorDialog(
            roots = roots,
            onSelect = { path ->
                if (path == null) {
                    showInputPicker = false
                    return@PathSelectorDialog
                }
                if (Files.isDirectory(path) || !isApkFile(path)) return@PathSelectorDialog
                showInputPicker = false
                setInput(Uri.fromFile(path.toFile()), path.fileName?.toString())
            },
            fileFilter = ::isApkFile,
            allowDirectorySelection = false,
            fileTypeLabel = ".apk",
            lastDirectoryPreference = prefs.apkSignerInputLastDirectory
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
                outputDialogState = ApkSignerSaveDialogState(
                    directory = directory,
                    fileName = defaultOutputName()
                )
            },
            lastDirectoryPreference = prefs.signedApkExportLastDirectory
        )
    }

    outputDialogState?.let { state ->
        ExportSavedApkFileNameDialog(
            initialName = state.fileName,
            onDismiss = { outputDialogState = null },
            onConfirm = { enteredName ->
                val finalName = normalizeApkFileName(
                    enteredName.trim().ifBlank { state.fileName }
                )
                outputDialogState = null
                if (Files.exists(state.directory.resolve(finalName))) {
                    pendingOutputOverwrite = ApkSignerSaveDialogState(
                        directory = state.directory,
                        fileName = finalName
                    )
                } else {
                    saveSignedApk(state.directory, finalName)
                }
            }
        )
    }

    pendingOutputOverwrite?.let { overwrite ->
        ConfirmDialog(
            onDismiss = {
                pendingOutputOverwrite = null
                outputDialogState = overwrite
            },
            onConfirm = {
                saveSignedApk(overwrite.directory, overwrite.fileName)
            },
            title = stringResource(R.string.export_overwrite_title),
            description = stringResource(
                R.string.export_overwrite_description,
                overwrite.fileName
            ),
            icon = Icons.Outlined.WarningAmber
        )
    }

    AppScaffold(
        topBar = { scrollBehavior ->
            AppTopBar(
                title = stringResource(R.string.tools_apk_signature_tools_title),
                scrollBehavior = scrollBehavior,
                onBackClick = ::requestBack
            )
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        modifier = Modifier.size(42.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.Draw,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.tools_apk_signer_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }
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
                            text = stringResource(R.string.tools_apk_signer_info),
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
                        text = stringResource(R.string.tools_apk_signer_select_input),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
            inputDisplayName?.let { displayName ->
                item {
                    ApkSignerFileStatusCard(
                        label = stringResource(R.string.tools_apk_signer_input_label),
                        value = displayName,
                        icon = Icons.Outlined.Description
                    )
                }
            }
            item {
                Button(
                    onClick = {
                        val source = inputSource ?: return@Button
                        signing = true
                        errorText = null
                        signedApk?.delete()
                        signedApk = null
                        scope.launch {
                            runCatching {
                                CacheCleanupGuard.withCacheInUse {
                                    val prepared = withContext(Dispatchers.IO) {
                                        prepareSigningFiles(
                                            context = context,
                                            source = source,
                                            outputFileName = defaultOutputName()
                                        )
                                    }
                                    try {
                                        keystoreManager.sign(prepared.input, prepared.output)
                                        prepared.output
                                    } finally {
                                        if (prepared.deleteInput) prepared.input.delete()
                                    }
                                }
                            }.onSuccess {
                                signedApk = it
                                context.toast(context.getString(R.string.tools_apk_signer_signed))
                            }.onFailure {
                                errorText = it.message ?: context.getString(R.string.tools_apk_signer_failed)
                            }
                            signing = false
                        }
                    },
                    enabled = !signing && inputSource != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    if (signing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(stringResource(R.string.tools_apk_signer_action))
                    }
                }
            }
            signedApk?.let { apk ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ApkSignerFileStatusCard(
                            label = stringResource(R.string.tools_apk_signer_ready_label),
                            value = apk.name,
                            icon = Icons.Outlined.CheckCircle,
                            positive = true
                        )
                        OutlinedButton(
                            onClick = ::requestSave,
                            enabled = !signing,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.Save, null)
                            Text(
                                text = stringResource(R.string.tools_apk_signer_save_action),
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
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
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
            item {
                SignatureMetadataInjectorSection(viewModel = injectorViewModel)
            }
        }
    }
}

@Composable
private fun ApkSignerFileStatusCard(
    label: String,
    value: String,
    icon: ImageVector,
    positive: Boolean = false
) {
    val iconColor = if (positive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val iconContainer = if (positive) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = iconContainer,
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private data class ApkSignerSaveDialogState(
    val directory: Path,
    val fileName: String
)

private data class PreparedApkSigningFiles(
    val input: File,
    val output: File,
    val deleteInput: Boolean
)

private enum class ApkSignerPermissionRequest {
    INPUT,
    OUTPUT
}

private fun queryDisplayName(context: Context, uri: Uri): String? {
    return runCatching {
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
}

private fun prepareSigningFiles(
    context: Context,
    source: String,
    outputFileName: String
): PreparedApkSigningFiles {
    val cacheDir = context.managerStorageContext.cacheDir.resolve(APK_SIGNER_CACHE_DIR).apply { mkdirs() }
    cacheDir.listFiles()?.forEach { it.deleteRecursively() }
    val uri = Uri.parse(source)
    val input = if (uri.scheme.equals("file", ignoreCase = true)) {
        uri.path?.let(::File) ?: throw IOException("Invalid file path.")
    } else {
        val tempInput = cacheDir.resolve("input.apk")
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            tempInput.outputStream().use { output -> inputStream.copyTo(output) }
        } ?: throw IOException("Unable to open source APK.")
        tempInput
    }
    val output = cacheDir.resolve(normalizeApkFileName(outputFileName))
    return PreparedApkSigningFiles(
        input = input,
        output = output,
        deleteInput = input.parentFile == cacheDir
    )
}

private fun isApkFile(path: Path): Boolean {
    return isApkFileName(path.fileName?.toString())
}

private fun isApkInput(displayName: String?, mimeType: String?): Boolean {
    if (!displayName.isNullOrBlank()) return isApkFileName(displayName)
    val normalizedMimeType = mimeType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.ROOT)
    return normalizedMimeType == APK_MIMETYPE
}

private fun isApkFileName(name: String?): Boolean {
    return name?.lowercase(Locale.ROOT)?.endsWith(".apk") == true
}

private fun normalizeApkFileName(value: String): String {
    val sanitized = sanitizeApkFileName(value)
    return if (sanitized.endsWith(".apk", ignoreCase = true)) sanitized else "$sanitized.apk"
}

private fun sanitizeApkFileName(value: String): String {
    val sanitized = value.trim().replace(Regex("[^A-Za-z0-9._-]"), "_")
    return sanitized.ifBlank { "signed-apk" }
}
