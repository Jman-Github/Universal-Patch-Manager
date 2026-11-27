package app.universal.patch.manager.ui.component.bundle

import app.universal.patch.manager.util.withHapticFeedback
import android.view.HapticFeedbackConstants
import app.universal.patch.manager.ui.component.design.ListItem
import app.universal.patch.manager.ui.component.design.ListItemRadioButton
import app.universal.patch.manager.ui.component.design.IconButton
import app.universal.patch.manager.ui.component.design.TextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Topic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.universal.patch.manager.R
import app.universal.patch.manager.ui.component.AlertDialogExtended
import app.universal.patch.manager.ui.component.TextHorizontalPadding
import app.universal.patch.manager.ui.component.haptics.HapticCheckbox
import app.universal.patch.manager.util.transparentListItemColors

private enum class BundleType {
    Local,
    Remote
}

@Composable
fun ImportPatchBundleDialog(
    onDismiss: () -> Unit,
    onRemoteSubmit: (String, Boolean, Boolean) -> Unit,
    onLocalSubmit: (String) -> Unit,
    onLocalPick: () -> Unit,
    selectedLocalPath: String?,
    initialAutoUpdate: Boolean,
    initialSearchUpdate: Boolean,
    onAutoUpdateChange: (Boolean) -> Unit,
    onSearchUpdateChange: (Boolean) -> Unit,
    initialRemoteUrl: String = ""
) {
    val normalizedInitialRemoteUrl = remember(initialRemoteUrl) { initialRemoteUrl.trim() }
    var currentStep by rememberSaveable(normalizedInitialRemoteUrl) {
        mutableIntStateOf(if (normalizedInitialRemoteUrl.isNotBlank()) 1 else 0)
    }
    var bundleType by rememberSaveable(normalizedInitialRemoteUrl) { mutableStateOf(BundleType.Remote) }
    var remoteUrl by rememberSaveable(normalizedInitialRemoteUrl) { mutableStateOf(normalizedInitialRemoteUrl) }
    var autoUpdate by rememberSaveable { mutableStateOf(initialAutoUpdate) }
    var searchUpdate by rememberSaveable { mutableStateOf(initialSearchUpdate) }

    val steps = listOf<@Composable () -> Unit>(
        {
            SelectBundleTypeStep(bundleType) { selectedType ->
                bundleType = selectedType
            }
        },
        {
            ImportBundleStep(
                shouldAutofocusRemoteUrl = currentStep == 1,
                bundleType,
                selectedLocalPath,
                remoteUrl,
                autoUpdate,
                searchUpdate,
                onLocalPick,
                { remoteUrl = it },
                {
                    autoUpdate = it
                    onAutoUpdateChange(it)
                },
                {
                    searchUpdate = it
                    onSearchUpdateChange(it)
                }
            )
        }
    )

    val inputsAreValid by remember(bundleType, selectedLocalPath, remoteUrl) {
        derivedStateOf {
            (bundleType == BundleType.Local && !selectedLocalPath.isNullOrBlank()) ||
                (bundleType == BundleType.Remote && remoteUrl.isNotBlank())
        }
    }

    AlertDialogExtended(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (currentStep == 0) R.string.select else R.string.add_patches))
        },
        text = {
            steps[currentStep]()
        },
        confirmButton = {
            if (currentStep == steps.lastIndex) {
                TextButton(
                    enabled = inputsAreValid,
                    onClick = {
                        when (bundleType) {
                            BundleType.Local -> selectedLocalPath?.let(onLocalSubmit)
                            BundleType.Remote -> onRemoteSubmit(remoteUrl, autoUpdate, searchUpdate)
                        }
                    }
                ) {
                    Text(stringResource(R.string.add))
                }
            } else {
                TextButton(onClick = { currentStep++ }) {
                    Text(stringResource(R.string.next))
                }
            }
        },
        dismissButton = {
            if (currentStep > 0) {
                TextButton(onClick = { currentStep-- }) {
                    Text(stringResource(R.string.back))
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
        textHorizontalPadding = PaddingValues(0.dp)
    )
}

@Composable
private fun SelectBundleTypeStep(
    bundleType: BundleType,
    onBundleTypeSelected: (BundleType) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 24.dp),
            text = stringResource(R.string.select_patches_type_dialog_description)
        )
        Column {
            ListItem(
                selected = bundleType == BundleType.Remote,
                onClick = { onBundleTypeSelected(BundleType.Remote) },
                headlineContent = { Text(stringResource(R.string.enter_url)) },
                overlineContent = { Text(stringResource(R.string.recommended)) },
                supportingContent = { Text(stringResource(R.string.remote_patches_description)) },
                leadingContent = {
                    ListItemRadioButton(selected = bundleType == BundleType.Remote)
                },
                colors = transparentListItemColors
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            ListItem(
                selected = bundleType == BundleType.Local,
                onClick = { onBundleTypeSelected(BundleType.Local) },
                headlineContent = { Text(stringResource(R.string.select_from_storage)) },
                supportingContent = { Text(stringResource(R.string.local_patches_description)) },
                overlineContent = { },
                leadingContent = {
                    ListItemRadioButton(selected = bundleType == BundleType.Local)
                },
                colors = transparentListItemColors
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportBundleStep(
    shouldAutofocusRemoteUrl: Boolean,
    bundleType: BundleType,
    patchBundlePath: String?,
    remoteUrl: String,
    autoUpdate: Boolean,
    searchUpdate: Boolean,
    launchPatchActivity: () -> Unit,
    onRemoteUrlChange: (String) -> Unit,
    onAutoUpdateChange: (Boolean) -> Unit,
    onSearchUpdateChange: (Boolean) -> Unit
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var remoteUrlFieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(remoteUrl, selection = TextRange(remoteUrl.length)))
    }

    LaunchedEffect(remoteUrl) {
        if (remoteUrl != remoteUrlFieldValue.text) {
            remoteUrlFieldValue = TextFieldValue(remoteUrl, selection = TextRange(remoteUrl.length))
        }
    }

    LaunchedEffect(shouldAutofocusRemoteUrl, bundleType) {
        if (shouldAutofocusRemoteUrl && bundleType == BundleType.Remote) {
            withFrameNanos { }
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    Column {
        when (bundleType) {
            BundleType.Local -> {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    ListItem(
                        headlineContent = {
                            Text(stringResource(R.string.patch_bundle))
                        },
                        supportingContent = {
                            Text(text = patchBundlePath ?: stringResource(R.string.file_field_not_set))
                        },
                        trailingContent = {
                            IconButton(onClick = launchPatchActivity) {
                                Icon(imageVector = Icons.Default.Topic, contentDescription = null)
                            }
                        },
                        onClick = { launchPatchActivity() },
                        colors = transparentListItemColors
                    )
                }
            }

            BundleType.Remote -> {
                Column(
                    modifier = Modifier.padding(TextHorizontalPadding)
                ) {
                    OutlinedTextField(
                        modifier = Modifier.focusRequester(focusRequester),
                        value = remoteUrlFieldValue,
                        onValueChange = {
                            remoteUrlFieldValue = it
                            onRemoteUrlChange(it.text)
                        },
                        label = { Text(stringResource(R.string.patches_url)) }
                    )
                }
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                ) {
                    ListItem(
                        checked = autoUpdate,
                        onCheckedChange = onAutoUpdateChange.withHapticFeedback(HapticFeedbackConstants.CLOCK_TICK),
                        headlineContent = { Text(stringResource(R.string.auto_update)) },
                        leadingContent = {
                            HapticCheckbox(checked = autoUpdate, onCheckedChange = null)
                        },
                        colors = transparentListItemColors
                    )
                    ListItem(
                        checked = searchUpdate,
                        onCheckedChange = onSearchUpdateChange.withHapticFeedback(HapticFeedbackConstants.CLOCK_TICK),
                        headlineContent = { Text(stringResource(R.string.bundle_search_update)) },
                        supportingContent = { Text(stringResource(R.string.bundle_search_update_description)) },
                        leadingContent = {
                            HapticCheckbox(checked = searchUpdate, onCheckedChange = null)
                        },
                        colors = transparentListItemColors
                    )
                }
            }
        }
    }
}
