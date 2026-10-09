package com.galaxyrio.gracelauncher.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.SearchContacts
import com.galaxyrio.gracelauncher.data.SearchEngine
import com.galaxyrio.gracelauncher.data.SearchSettings
import com.galaxyrio.gracelauncher.platform.SearchLauncher
import com.galaxyrio.gracelauncher.ui.LauncherActions
import com.galaxyrio.gracelauncher.ui.LauncherUiState

@Composable
internal fun SearchSettingsScreen(uiState: LauncherUiState, actions: LauncherActions, onBack: () -> Unit) {
    val settings = uiState.settings.search
    val context = LocalContext.current
    val contacts = remember(context) { SearchContacts(context) }
    var hasAccess by remember { mutableStateOf(contacts.hasAccess()) }
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    var engineDialog by rememberSaveable { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, contacts) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) hasAccess = contacts.hasAccess() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    fun update(change: (SearchSettings) -> SearchSettings) = actions.updateSettings { it.copy(search = change(it.search)) }
    fun permissionResult(granted: Boolean) {
        hasAccess = granted
        update { it.copy(contacts = granted) }
        if (!granted) Toast.makeText(context, R.string.search_contacts_permission, Toast.LENGTH_LONG).show()
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), ::permissionResult)
    val systemSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        permissionResult(contacts.hasAccess())
    }
    SettingsScaffold(stringResource(R.string.settings_search), "settings_search", onBack) { padding ->
        SettingsList(padding) {
            item {
                SettingsFeatureBanner(stringResource(R.string.search_enable), settings.enabled, "search_enabled") { value ->
                    update { it.copy(enabled = value) }
                }
            }
            item {
                SettingsToggleItem(stringResource(R.string.search_suggestions), stringResource(R.string.search_suggestions_summary),
                    settings.suggestions, 0, 6, "search_suggestions", enabled = settings.enabled) { value -> update { it.copy(suggestions = value) } }
            }
            item {
                SettingsToggleItem(stringResource(R.string.search_contacts), stringResource(R.string.search_contacts_summary),
                    settings.contacts && hasAccess, 1, 6, "search_contacts", enabled = settings.enabled) { value ->
                    if (!value || contacts.hasAccess()) update { it.copy(contacts = value) }
                    else if (permissionRequested && (context as? Activity)?.let {
                        ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.READ_CONTACTS)
                    } == false) {
                        systemSettings.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                    } else {
                        permissionRequested = true
                        permission.launch(Manifest.permission.READ_CONTACTS)
                    }
                }
            }
            item {
                SettingsActionItem(stringResource(R.string.search_files), stringResource(R.string.settings_coming_soon),
                    2, 6, "search_files", enabled = false, onClick = {})
            }
            item {
                SettingsToggleItem(stringResource(R.string.search_fuzzy), stringResource(R.string.search_fuzzy_summary),
                    settings.fuzzy, 3, 6, "search_fuzzy", enabled = settings.enabled) { value -> update { it.copy(fuzzy = value) } }
            }
            item {
                val title = stringResource(R.string.search_internet_setting)
                val engineLabel = if (settings.engine == SearchEngine.Custom && settings.customEngineName.isNotBlank()) {
                    settings.customEngineName
                } else settings.engine.label()
                SettingsActionItem(title, engineLabel, 4, 6, "search_internet", enabled = settings.enabled,
                    onClick = { engineDialog = "select" },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            VerticalDivider(Modifier.height(32.dp))
                            Switch(settings.internet, onCheckedChange = { value -> update { it.copy(internet = value) } },
                                enabled = settings.enabled && LocalSettingsStorageState.current.canEdit,
                                modifier = Modifier.padding(start = 16.dp).testTag("search_internet_switch")
                                    .semantics { contentDescription = title })
                        }
                    })
            }
            item {
                SettingsToggleItem(stringResource(R.string.search_hidden_apps), stringResource(R.string.search_hidden_apps_summary),
                    settings.hiddenApps, 5, 6, "search_hidden_apps", enabled = settings.enabled) { value -> update { it.copy(hiddenApps = value) } }
            }
        }
    }
    when (engineDialog) {
        "select" -> SettingsSelectionDialog(
            title = stringResource(R.string.search_engine), items = SearchEngine.entries,
            selectedItem = settings.engine, tag = "search_engine", itemKey = { it.id }, itemLabel = { it.label() },
            onSelect = { engine ->
                if (engine == SearchEngine.Custom) engineDialog = "custom"
                else {
                    update { it.copy(engine = engine) }
                    engineDialog = null
                }
            },
            onDismiss = { engineDialog = null },
        )
        "custom" -> CustomSearchEngineDialog(settings,
            onSave = { name, url ->
                update { it.copy(engine = SearchEngine.Custom, customEngineName = name, customEngineUrl = url) }
                engineDialog = null
            },
            onDismiss = { engineDialog = "select" },
        )
    }
}

@Composable
private fun SearchEngine.label(): String = stringResource(when (this) {
    SearchEngine.Google -> R.string.search_engine_google
    SearchEngine.Bing -> R.string.search_engine_bing
    SearchEngine.Sogou -> R.string.search_engine_sogou
    SearchEngine.DuckDuckGo -> R.string.search_engine_duckduckgo
    SearchEngine.Qwant -> R.string.search_engine_qwant
    SearchEngine.Startpage -> R.string.search_engine_startpage
    SearchEngine.Custom -> R.string.search_engine_custom
})

@Composable
private fun CustomSearchEngineDialog(settings: SearchSettings, onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(settings.customEngineName) }
    var url by rememberSaveable { mutableStateOf(settings.customEngineUrl) }
    val canEdit = LocalSettingsStorageState.current.canEdit
    val validUrl = remember(url) { SearchLauncher.isValidSearchUrl(url) }
    fun save() { if (canEdit && validUrl) onSave(name.trim(), url.trim()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.search_engine_custom)) },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(name, onValueChange = { name = it.take(80) }, enabled = canEdit, singleLine = true,
                    label = { Text(stringResource(R.string.search_engine_name)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().testTag("search_engine_custom_name"))
                OutlinedTextField(url, onValueChange = { url = it.take(2048) }, enabled = canEdit, singleLine = true,
                    label = { Text(stringResource(R.string.search_engine_url)) },
                    placeholder = { Text(stringResource(R.string.search_engine_url_example)) },
                    supportingText = { Text(stringResource(if (url.isNotBlank() && !validUrl) R.string.search_engine_url_error else R.string.search_engine_url_hint)) },
                    isError = url.isNotBlank() && !validUrl,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier.fillMaxWidth().testTag("search_engine_custom_url"))
            }
        },
        confirmButton = {
            TextButton(onClick = ::save, enabled = canEdit && validUrl, modifier = Modifier.testTag("search_engine_custom_save")) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}
