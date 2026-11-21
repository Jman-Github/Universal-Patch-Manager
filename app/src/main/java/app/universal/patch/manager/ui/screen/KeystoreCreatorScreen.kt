package app.universal.patch.manager.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import app.universal.patch.manager.ui.component.LazyColumnWithScrollbar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.universal.patch.manager.data.platform.Filesystem
import app.universal.patch.manager.domain.manager.SigningKeyAlgorithm
import app.universal.patch.manager.domain.manager.KeystoreManager
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.ui.component.AppScaffold
import app.universal.patch.manager.ui.component.AppTopBar
import app.universal.patch.manager.ui.component.ExportSavedApkFileNameDialog
import app.universal.patch.manager.ui.component.PasswordField
import app.universal.patch.manager.ui.component.haptics.HapticExtendedFloatingActionButton
import app.universal.patch.manager.ui.component.patches.PathSelectorDialog
import app.universal.patch.manager.ui.component.RememberedCreateDocument
import app.universal.patch.manager.ui.component.toPickerDirectoryUri
import app.universal.patch.manager.util.toast
import app.universal.patch.manager.R
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeystoreCreatorScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keystoreManager: KeystoreManager = koinInject()
    val prefs: PreferencesManager = koinInject()
    val fs: Filesystem = koinInject()
    val useCustomFilePicker by prefs.useCustomFilePicker.getAsState()
    val createdKeystoreExportDirectory by prefs.createdKeystoreExportLastDirectory.getAsState()
    val roots = remember { fs.storageRoots() }
    val (permissionContract, permissionName) = remember { fs.permissionContract() }

    var alias by rememberSaveable { mutableStateOf("") }
    var storePassword by rememberSaveable { mutableStateOf("") }
    var keyPassword by rememberSaveable { mutableStateOf("") }
    var algorithm by rememberSaveable { mutableStateOf(SigningKeyAlgorithm.RSA) }
    var format by rememberSaveable { mutableStateOf(KeystoreManager.KeystoreFormat.BKS) }

    var creating by rememberSaveable { mutableStateOf(false) }
    var createdKeystore by remember { mutableStateOf<KeystoreManager.KeystoreBinary?>(null) }
    var errorText by rememberSaveable { mutableStateOf<String?>(null) }

    var showOutputPicker by rememberSaveable { mutableStateOf(false) }
    var outputDialogState by remember { mutableStateOf<OutputSaveDialogState?>(null) }
    var pendingOutputPermission by rememberSaveable { mutableStateOf(false) }

    fun defaultOutputName(): String {
        val name = sanitizeFileName(alias.ifBlank { "manager-keystore" })
        return "$name.${format.extension}"
    }

    val permissionLauncher = rememberLauncherForActivityResult(permissionContract) { granted ->
        if (granted && pendingOutputPermission) showOutputPicker = true
        pendingOutputPermission = false
    }

    val saveDocumentLauncher = rememberLauncherForActivityResult(
        RememberedCreateDocument("application/octet-stream") {
            createdKeystoreExportDirectory.takeIf(String::isNotBlank)?.let(Uri::parse)
        }
    ) { uri ->
        val keystore = createdKeystore ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            prefs.createdKeystoreExportLastDirectory.update(uri.toPickerDirectoryUri().toString())
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(keystore.bytes)
                    } ?: throw IOException("Unable to open destination.")
                }
            }.onSuccess {
                context.toast(context.getString(R.string.tools_keystore_creator_saved))
            }.onFailure {
                errorText = it.message ?: context.getString(R.string.tools_keystore_creator_save_failed)
            }
        }
    }

    fun requestSave() {
        if (createdKeystore == null) return
        if (useCustomFilePicker) {
            if (fs.hasStoragePermission()) {
                showOutputPicker = true
            } else {
                pendingOutputPermission = true
                permissionLauncher.launch(permissionName)
            }
        } else {
            saveDocumentLauncher.launch(defaultOutputName())
        }
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
                outputDialogState = OutputSaveDialogState(
                    directory = directory,
                    fileName = defaultOutputName()
                )
            },
            lastDirectoryPreference = prefs.createdKeystoreExportLastDirectory
        )
    }

    outputDialogState?.let { state ->
        ExportSavedApkFileNameDialog(
            initialName = state.fileName,
            onDismiss = { outputDialogState = null },
            onConfirm = { enteredName ->
                val bytes = createdKeystore?.bytes ?: return@ExportSavedApkFileNameDialog
                val finalName = enteredName.trim().ifBlank { state.fileName }
                outputDialogState = null
                showOutputPicker = false
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            Files.write(state.directory.resolve(finalName), bytes)
                        }
                    }.onSuccess {
                        context.toast(context.getString(R.string.tools_keystore_creator_saved))
                    }.onFailure {
                        errorText = it.message ?: context.getString(R.string.tools_keystore_creator_save_failed)
                    }
                }
            }
        )
    }

    AppScaffold(
        topBar = { scrollBehavior ->
            AppTopBar(
                title = stringResource(R.string.tools_keystore_creator_title),
                scrollBehavior = scrollBehavior,
                onBackClick = onBackClick
            )
        },
        floatingActionButton = {
            AnimatedVisibility(visible = createdKeystore != null && !creating) {
                HapticExtendedFloatingActionButton(
                    text = { Text(stringResource(R.string.save)) },
                    icon = { androidx.compose.material3.Icon(Icons.Outlined.Save, null) },
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
                            text = stringResource(R.string.tools_keystore_creator_info),
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 14.sp)
                        )
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = alias,
                    onValueChange = { if (!creating) { alias = it; createdKeystore = null } },
                    label = { Text(stringResource(R.string.import_keystore_dialog_alias_field)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
            item {
                PasswordField(
                    modifier = Modifier.fillMaxWidth(),
                    value = storePassword,
                    onValueChange = { if (!creating) { storePassword = it; createdKeystore = null } },
                    label = { Text(stringResource(R.string.import_keystore_dialog_password_field)) }
                )
            }
            item {
                PasswordField(
                    modifier = Modifier.fillMaxWidth(),
                    value = keyPassword,
                    onValueChange = { if (!creating) { keyPassword = it; createdKeystore = null } },
                    label = { Text(stringResource(R.string.import_keystore_dialog_key_password_field)) }
                )
            }
            item {
                Text(stringResource(R.string.tools_keystore_creator_algorithm), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SigningKeyAlgorithm.entries.forEach { candidate ->
                        FilterChip(
                            onClick = { if (!creating) { algorithm = candidate; createdKeystore = null } },
                            selected = algorithm == candidate,
                            label = { Text(candidate.label) },
                            enabled = !creating
                        )
                    }
                }
            }
            item {
                Text(
                    text = stringResource(R.string.tools_keystore_creator_format_title),
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
                            onClick = { if (!creating) { format = candidate; createdKeystore = null } },
                            enabled = !creating,
                            selected = format == candidate,
                            label = { Text(candidate.label) }
                        )
                    }
                }
            }
            item {
                androidx.compose.material3.Button(
                    onClick = {
                        creating = true
                        errorText = null
                        createdKeystore = null
                        scope.launch {
                            runCatching {
                                keystoreManager.createKeystore(
                                    alias = alias,
                                    storePass = storePassword,
                                    keyPass = keyPassword,
                                    format = format,
                                    algorithm = algorithm
                                )
                            }.onSuccess {
                                createdKeystore = it
                                context.toast(context.getString(R.string.tools_keystore_creator_created))
                            }.onFailure {
                                errorText = it.message ?: context.getString(R.string.tools_keystore_creator_failed)
                            }
                            creating = false
                        }
                    },
                    enabled = !creating && alias.isNotBlank() && storePassword.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (creating) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(vertical = 2.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(stringResource(R.string.tools_keystore_creator_action))
                    }
                }
            }
            createdKeystore?.let { keystore ->
                item {
                    Text(
                        text = stringResource(
                            R.string.tools_keystore_creator_ready,
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
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }
    }
}

private data class OutputSaveDialogState(
    val directory: Path,
    val fileName: String
)

private fun sanitizeFileName(value: String): String {
    val sanitized = value.trim().replace(Regex("[^A-Za-z0-9._-]"), "_")
    return sanitized.ifBlank { "keystore" }
}
