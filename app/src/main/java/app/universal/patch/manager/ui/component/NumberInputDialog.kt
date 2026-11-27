package app.universal.patch.manager.ui.component

import app.universal.patch.manager.ui.component.design.TextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisallowComposableCalls
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
private inline fun <T> NumberInputDialog(
    current: T?,
    name: String,
    crossinline onSubmit: (T?) -> Unit,
    noinline validator: @DisallowComposableCalls (T) -> Boolean,
    crossinline toNumberOrNull: @DisallowComposableCalls String.() -> T?,
    neutralButtonLabel: String? = null,
    noinline neutralValueProvider: (() -> T?)? = null,
) {
    val initialValue = current?.toString().orEmpty()
    var fieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialValue, selection = TextRange(initialValue.length)))
    }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val numberFieldValue by remember {
        derivedStateOf { fieldValue.text.toNumberOrNull() }
    }
    var validatorFailed by remember { mutableStateOf(false) }
    val validatorRef by rememberUpdatedState(validator)
    LaunchedEffect(numberFieldValue) {
        val failed = numberFieldValue?.let { value ->
            withContext(Dispatchers.Default) {
                runCatching { !validatorRef(value) }.getOrDefault(true)
            }
        } ?: false
        validatorFailed = failed
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    AlertDialog(
        modifier = Modifier.imePadding(),
        onDismissRequest = { onSubmit(null) },
        title = { CenteredDialogTitle(name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    value = fieldValue,
                    onValueChange = { fieldValue = it },
                    placeholder = {
                        Text(stringResource(R.string.dialog_input_placeholder))
                    },
                    singleLine = true,
                    isError = validatorFailed,
                    supportingText = {
                        if (validatorFailed) {
                            Text(
                                stringResource(R.string.input_dialog_value_invalid),
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { numberFieldValue?.let(onSubmit) },
                enabled = numberFieldValue != null && !validatorFailed,
            ) {
                Text(
                    text = stringResource(R.string.save),
                    maxLines = 1,
                    softWrap = false
                )
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val label = neutralButtonLabel
                val provider = neutralValueProvider
                if (label != null && provider != null) {
                    TextButton(
                        onClick = {
                            provider()?.let { value ->
                                val text = value.toString()
                                fieldValue = TextFieldValue(text, selection = TextRange(text.length))
                            }
                        }
                    ) {
                        Text(
                            text = label,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
                TextButton(
                    onClick = { onSubmit(null) }
                ) {
                    Text(
                        text = stringResource(R.string.cancel),
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        },
    )
}

@Composable
fun IntInputDialog(
    current: Int?,
    name: String,
    validator: (Int) -> Boolean = { true },
    onSubmit: (Int?) -> Unit,
    neutralButtonLabel: String? = null,
    neutralValueProvider: (() -> Int?)? = null
) = NumberInputDialog(current, name, onSubmit, validator, String::toIntOrNull, neutralButtonLabel, neutralValueProvider)

@Composable
fun LongInputDialog(
    current: Long?,
    name: String,
    validator: (Long) -> Boolean = { true },
    onSubmit: (Long?) -> Unit
) = NumberInputDialog(current, name, onSubmit, validator, String::toLongOrNull)

@Composable
fun FloatInputDialog(
    current: Float?,
    name: String,
    validator: (Float) -> Boolean = { true },
    onSubmit: (Float?) -> Unit
) = NumberInputDialog(current, name, onSubmit, validator, String::toFloatOrNull)

@Composable
fun DoubleInputDialog(
    current: Double?,
    name: String,
    validator: (Double) -> Boolean = { true },
    onSubmit: (Double?) -> Unit
) = NumberInputDialog(current, name, onSubmit, validator, String::toDoubleOrNull)
