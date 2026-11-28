package app.universal.patch.manager.ui.component

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExpandedFullScreenSearchBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.material3.SearchBarState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchBar(
    textFieldState: TextFieldState,
    searchBarState: SearchBarState,
    onSearch: (String) -> Unit,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = SearchBarDefaults.colors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        dividerColor = MaterialTheme.colorScheme.outline
    )
    val expandedFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(searchBarState.currentValue) {
        if (searchBarState.currentValue == SearchBarValue.Expanded) {
            withFrameNanos { }
            textFieldState.edit {
                selection = TextRange(length)
            }
            expandedFocusRequester.requestFocus()
            keyboardController?.show()
        } else {
            keyboardController?.hide()
            focusManager.clearFocus(force = true)
        }
    }
    val collapsedInputField = @Composable {
        SearchBarDefaults.InputField(
            modifier = Modifier.fillMaxWidth(),
            textFieldState = textFieldState,
            searchBarState = searchBarState,
            onSearch = { query ->
                keyboardController?.hide()
                onSearch(query)
            },
            placeholder = placeholder,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            colors = colors.inputFieldColors
        )
    }
    val expandedInputField = @Composable {
        SearchBarDefaults.InputField(
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(expandedFocusRequester),
            textFieldState = textFieldState,
            searchBarState = searchBarState,
            onSearch = { query ->
                keyboardController?.hide()
                onSearch(query)
            },
            placeholder = placeholder,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            colors = colors.inputFieldColors
        )
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        SearchBar(
            modifier = Modifier
                .align(Alignment.Center)
                .statusBarsPadding()
                // The pill keeps clear of the screen edges instead of running into them.
                .padding(horizontal = 12.dp),
            state = searchBarState,
            inputField = collapsedInputField,
            colors = colors
        )
        ExpandedFullScreenSearchBar(
            state = searchBarState,
            inputField = expandedInputField,
            colors = colors,
            content = content
        )
    }
}
