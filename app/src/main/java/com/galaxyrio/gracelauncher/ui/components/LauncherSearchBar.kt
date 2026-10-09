@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.galaxyrio.gracelauncher.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.homemode4miui.R // P2：模块 namespace 为 com.homemode4miui，R 类随之（原版为 com.galaxyrio.gracelauncher.R）

/** An inline M3 search field; the page already owns and displays its results. */
@Composable
internal fun LauncherSearchBar(
    textFieldState: TextFieldState,
    placeholder: String,
    tag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    autoFocus: Boolean = false,
    onBack: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    // Keep the native input in its editable/results-visible state. There is no
    // expanding SearchBar or dialog; the collapsed SearchBar wrapper would
    // suppress the keyboard because it expects a separate expanded view.
    val state = rememberSearchBarState(SearchBarValue.Expanded)
    val focusRequester = remember { FocusRequester() }
    val colors = SearchBarDefaults.colors()
    LaunchedEffect(autoFocus, enabled) {
        if (autoFocus && enabled) focusRequester.requestFocus()
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = SearchBarDefaults.inputFieldShape,
        color = colors.containerColor,
        tonalElevation = SearchBarDefaults.TonalElevation,
        shadowElevation = SearchBarDefaults.ShadowElevation,
    ) {
        val keyboard = LocalSoftwareKeyboardController.current
        SearchBarDefaults.InputField(
            textFieldState = textFieldState,
            searchBarState = state,
            onSearch = { keyboard?.hide(); onSearch?.invoke() },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).testTag(tag),
            colors = colors.inputFieldColors,
            placeholder = { Text(placeholder) },
            leadingIcon = {
                if (onBack != null) IconButton(onClick = { keyboard?.hide(); onBack() }) {
                    Icon(painterResource(R.drawable.ms_arrow_back), stringResource(R.string.settings_back))
                } else Icon(painterResource(R.drawable.ms_search), null)
            },
            trailingIcon = {
                if (trailingIcon != null) trailingIcon()
                else if (textFieldState.text.isNotEmpty()) IconButton(onClick = { textFieldState.clearText() }, enabled = enabled,
                    modifier = Modifier.testTag("${tag}_clear")) {
                    Icon(painterResource(R.drawable.ms_close), stringResource(R.string.search_clear_query))
                }
            },
        )
    }
}
