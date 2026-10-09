@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.galaxyrio.gracelauncher.ui.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.LauncherApp
import com.galaxyrio.gracelauncher.data.PrivateSpaceDisplay
import com.galaxyrio.gracelauncher.ui.LauncherActions
import com.galaxyrio.gracelauncher.ui.LauncherUiState
import com.galaxyrio.gracelauncher.ui.components.AppIcon
import com.galaxyrio.gracelauncher.ui.components.LauncherIcon
import com.galaxyrio.gracelauncher.ui.components.LauncherSymbol
import com.galaxyrio.gracelauncher.ui.overlays.PrivateSpaceEditorScreen
import com.galaxyrio.gracelauncher.ui.overlays.TextEntryDialog
import kotlinx.coroutines.launch

@Composable
internal fun PrivateSpaceSettings(uiState: LauncherUiState, actions: LauncherActions, onBack: () -> Unit, onEdit: () -> Unit) {
    val settings = uiState.settings.privateSpace
    val normalApps = settings.display == PrivateSpaceDisplay.NormalApp
    val enabled = settings.enabled && uiState.privateSpace.supported
    val canEnable = uiState.privateSpace.supported && LocalSettingsStorageState.current.canEdit
    var displayDialog by rememberSaveable { mutableStateOf(false) }
    SettingsScaffold(stringResource(R.string.private_space_title), "settings_private_space", onBack) { padding ->
        SettingsList(padding) {
            item {
                Spacer(Modifier.height(24.dp))
                Surface(onClick = { actions.updateSettings { it.copy(privateSpace = it.privateSpace.copy(enabled = !it.privateSpace.enabled)) } },
                    enabled = canEnable,
                    modifier = Modifier.fillMaxWidth().testTag("private_space_enabled"), shape = CircleShape,
                    color = if (settings.enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceBright,
                    contentColor = if (settings.enabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface) {
                    Row(Modifier.heightIn(min = 72.dp).padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.private_space_enable), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(16.dp))
                        Switch(settings.enabled, null, enabled = canEnable)
                    }
                }
            }
            if (!uiState.privateSpace.supported) item {
                Text(stringResource(R.string.private_space_unsupported), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (uiState.privateSpace.supported && uiState.isDefaultHome == false) item {
                SettingsActionItem(stringResource(R.string.set_default_launcher), stringResource(R.string.private_space_default_home),
                    0, 1, "private_space_default_home", leading = { LauncherIcon(LauncherSymbol.Home) }, onClick = actions.requestDefaultHome)
                Spacer(Modifier.height(12.dp))
            }
            item { SettingsHeading(stringResource(R.string.private_space_display_heading)) }
            item {
                SettingsActionItem(stringResource(R.string.private_space_display), settings.display.label(), 0, 3, "private_space_display",
                    enabled = enabled) { displayDialog = true }
            }
            item {
                SettingsToggleItem(stringResource(R.string.private_space_indicator), stringResource(R.string.private_space_indicator_summary),
                    settings.showIndicator, 1, 3, "private_space_indicator", enabled = enabled) { value ->
                    actions.updateSettings { it.copy(privateSpace = it.privateSpace.copy(showIndicator = value)) }
                }
            }
            item {
                SettingsActionItem(stringResource(R.string.private_space_edit), stringResource(R.string.private_space_edit_summary),
                    2, 3, "private_space_edit", enabled = enabled && uiState.privateSpace.user != null && !uiState.privateSpace.authenticating) {
                    actions.requestPrivateSpace(true, onEdit)
                }
            }
            item { SettingsHeading(stringResource(R.string.private_space_security_heading)) }
            item {
                SettingsToggleItem(stringResource(R.string.private_space_password), stringResource(R.string.private_space_password_summary),
                    settings.passwordProtected, 0, 2, "private_space_password", enabled = enabled && !normalApps) { value ->
                    actions.updateSettings { it.copy(privateSpace = it.privateSpace.copy(passwordProtected = value)) }
                }
            }
            item {
                SettingsToggleItem(stringResource(R.string.private_space_lock_immediately),
                    stringResource(if (settings.lockImmediately) R.string.private_space_lock_on_exit else R.string.private_space_lock_on_screen),
                    settings.lockImmediately, 1, 2, "private_space_lock_immediately", enabled = enabled && !normalApps) { value ->
                    actions.updateSettings { it.copy(privateSpace = it.privateSpace.copy(lockImmediately = value)) }
                }
            }
            if (uiState.privateSpace.supported) item {
                Spacer(Modifier.height(16.dp))
                SettingsActionItem(stringResource(R.string.private_space_setup), stringResource(R.string.private_space_setup_summary),
                    0, 1, "private_space_system_settings", onClick = actions.openPrivateSpaceSettings)
            }
        }
    }
    if (displayDialog) AlertDialog(
        onDismissRequest = { displayDialog = false }, shape = RoundedCornerShape(40.dp),
        title = { Text(stringResource(R.string.private_space_display)) },
        text = {
            Column {
                listOf(PrivateSpaceDisplay.Folder, PrivateSpaceDisplay.List, PrivateSpaceDisplay.NormalApp).forEach { display ->
                    Row(Modifier.fillMaxWidth().selectable(display == settings.display, role = Role.RadioButton, onClick = {
                        actions.updateSettings { it.copy(privateSpace = it.privateSpace.copy(display = display)) }
                        displayDialog = false
                    }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(display == settings.display, null)
                        Spacer(Modifier.width(12.dp))
                        Text(display.label())
                    }
                }
            }
        }, confirmButton = {},
    )
}

@Composable
internal fun PrivateSpaceEditorSettings(uiState: LauncherUiState, actions: LauncherActions, onBack: () -> Unit,
    onEditIcon: (LauncherApp) -> Unit) {
    if (!uiState.privateContentVisible) {
        SettingsScaffold(stringResource(R.string.private_space_edit), "private_space_editor_locked", onBack) { padding ->
            SettingsList(padding) {
                item { Spacer(Modifier.height(24.dp)) }
                item {
                    SettingsActionItem(stringResource(R.string.private_space_unlock_edit), null, 0, 1, "private_space_editor_unlock",
                        enabled = uiState.settings.privateSpace.enabled && uiState.privateSpace.user != null && !uiState.privateSpace.authenticating,
                        leading = { LauncherIcon(LauncherSymbol.Lock) }) { actions.requestPrivateSpace(true) {} }
                }
            }
        }
        return
    }
    val folder = uiState.privateFolder
    val app = uiState.folderItem(folder)
    var rename by rememberSaveable { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    PrivateSpaceEditorScreen(app, uiState, actions, onBack) {
        Column(Modifier.padding(top = 16.dp)) {
            SettingsActionItem(folder.name, stringResource(R.string.settings_folder_name), 0, 1, "private_space_name",
                leading = { AppIcon(app, Modifier.clickable(onClickLabel = stringResource(R.string.icon_designer_title)) { onEditIcon(app) }, size = 36.dp) },
                trailing = {
                    TextButton(enabled = !resetting, onClick = {
                        resetting = true
                        scope.launch {
                            if (!actions.resetPrivateSpaceAppearance()) Toast.makeText(context, R.string.settings_storage_save_error, Toast.LENGTH_SHORT).show()
                            resetting = false
                        }
                    }, modifier = Modifier.testTag("private_space_reset")) { Text(stringResource(R.string.reset)) }
                }) { rename = true }
            if (uiState.privateAppsFailed) TextButton(onClick = actions.refreshApps) { Text(stringResource(R.string.retry)) }
        }
    }
    if (rename) TextEntryDialog(stringResource(R.string.rename_folder), folder.name, { rename = false }, {
        actions.rename(app, it)
        rename = false
    })
}

@Composable
private fun PrivateSpaceDisplay.label(): String = stringResource(
    when (this) {
        PrivateSpaceDisplay.List -> R.string.private_space_display_list
        PrivateSpaceDisplay.Folder -> R.string.private_space_display_folder
        PrivateSpaceDisplay.NormalApp -> R.string.private_space_display_normal
    },
)
