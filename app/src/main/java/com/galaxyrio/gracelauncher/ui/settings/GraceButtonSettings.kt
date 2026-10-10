/*
 * 上游同步 v1.1.8 S3（2026-10-11）—— 移植自 GraceLauncher v1.1.8 原版（GPL-3.0）。
 */
package com.galaxyrio.gracelauncher.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.GraceButtonAction
import com.galaxyrio.gracelauncher.data.GraceButtonGesture
import com.galaxyrio.gracelauncher.data.GraceButtonTarget
import com.galaxyrio.gracelauncher.platform.GraceSystemActions
import com.galaxyrio.gracelauncher.ui.LauncherActions
import com.galaxyrio.gracelauncher.ui.LauncherUiState
import com.galaxyrio.gracelauncher.ui.components.GraceSystemAccessDialog
import com.galaxyrio.gracelauncher.ui.components.labelRes

@Composable
internal fun GraceButtonSettingsScreen(uiState: LauncherUiState, actions: LauncherActions, onBack: () -> Unit,
    home: Boolean = false, onEditIcon: () -> Unit = {}, onChooseGesture: (GraceButtonGesture) -> Unit) {
    val settings = uiState.settings.gestureSettings(home)
    val gestures = if (home) listOf(GraceButtonGesture.SwipeUp, GraceButtonGesture.SwipeDown, GraceButtonGesture.DoubleTap) else GraceButtonGesture.entries
    SettingsScaffold(stringResource(if (home) R.string.settings_gestures else R.string.settings_grace_button),
        if (home) "settings_home_gestures" else "settings_grace_button_page", onBack) { padding ->
        SettingsList(padding) {
            item { SettingsFeatureBanner(stringResource(if (home) R.string.home_gestures_enable else R.string.grace_button_enable), settings.enabled, "gestures_enabled") { value ->
                actions.updateSettings { it.withGestureSettings(home) { settings -> settings.copy(enabled = value) } }
            } }
            if (!home) {
                item {
                    SettingsActionItem(stringResource(R.string.edit_icon), null, 0, 1,
                        "grace_button_icon", onClick = onEditIcon)
                    Spacer(Modifier.height(16.dp))
                }
            }
            gestures.forEachIndexed { index, gesture ->
                item {
                    val target = settings.target(gesture)
                    val title = stringResource(if (home && gesture == GraceButtonGesture.DoubleTap) R.string.home_gesture_double_tap else gesture.labelRes)
                    SettingsActionItem(title, if (target.active) target.summary(uiState) else stringResource(R.string.grace_button_disabled),
                        index, gestures.size, "grace_gesture:${gesture.name}",
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                VerticalDivider(Modifier.height(32.dp))
                                Switch(target.active, onCheckedChange = { enabled ->
                                    actions.updateSettings { current -> current.withGestureSettings(home) { it.withEnabled(gesture, enabled) } }
                                }, enabled = LocalSettingsStorageState.current.canEdit,
                                    modifier = Modifier.padding(start = 16.dp).testTag("grace_gesture_switch:${gesture.name}")
                                        .semantics { contentDescription = title })
                            }
                        }) { onChooseGesture(gesture) }
                }
            }
        }
    }
}

@Composable
internal fun GraceButtonActionSettings(gesture: GraceButtonGesture, uiState: LauncherUiState, actions: LauncherActions,
    onBack: () -> Unit, home: Boolean = false, onChooseTarget: (GraceButtonAction) -> Unit) {
    val target = uiState.settings.gestureSettings(home).target(gesture)
    var websiteDialog by rememberSaveable { mutableStateOf(false) }
    var accessDialog by rememberSaveable { mutableStateOf(false) }
    val options = GraceButtonAction.entries.filterNot { it == GraceButtonAction.Disabled }
    fun select(choice: GraceButtonTarget) {
        actions.updateSettings { it.withGestureSettings(home) { settings -> settings.withTarget(gesture, choice) } }
        if (choice.action.requiresAccessibility && !GraceSystemActions.hasAccessibility) accessDialog = true
    }
    SettingsScaffold(stringResource(if (home && gesture == GraceButtonGesture.DoubleTap) R.string.home_gesture_double_tap else gesture.labelRes), "grace_action_page", onBack) { padding ->
        SettingsList(padding) {
            item { Spacer(Modifier.height(24.dp)) }
            options.forEachIndexed { index, action ->
                item {
                    val checked = target.action == action
                    SettingsActionItem(stringResource(action.labelRes),
                        if (checked && action in listOf(GraceButtonAction.App, GraceButtonAction.Shortcut, GraceButtonAction.Folder, GraceButtonAction.Website))
                            target.summary(uiState) else null,
                        index, options.size, "grace_action:${action.name}",
                        leading = { RadioButton(checked, onClick = null) },
                        modifier = Modifier.semantics { role = Role.RadioButton; selected = checked },
                    ) {
                        when (action) {
                            GraceButtonAction.App, GraceButtonAction.Shortcut, GraceButtonAction.Folder -> onChooseTarget(action)
                            GraceButtonAction.Website -> websiteDialog = true
                            else -> select(GraceButtonTarget(action))
                        }
                    }
                }
            }
            if (target.action.requiresAccessibility) item {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.grace_accessibility_required), Modifier.padding(4.dp), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { accessDialog = true }) { Text(stringResource(R.string.media_open_settings)) }
            }
        }
    }
    if (websiteDialog) GraceWebsiteDialog(target.url.orEmpty(), { websiteDialog = false }) { url ->
        select(GraceButtonTarget(GraceButtonAction.Website, url = url))
        websiteDialog = false
    }
    if (accessDialog) GraceSystemAccessDialog { accessDialog = false }
}

@Composable
private fun GraceWebsiteDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val uri = remember(text) { GraceSystemActions.websiteUri(text) }
    val editable = LocalSettingsStorageState.current.canEdit
    fun save() { if (editable && uri != null) onSave(uri.toString()) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.grace_button_open_website)) },
        text = {
            OutlinedTextField(text, onValueChange = { text = it.take(2048) }, enabled = editable, singleLine = true,
                label = { Text(stringResource(R.string.grace_button_url)) },
                supportingText = { Text(stringResource(if (text.isNotBlank() && uri == null) R.string.grace_button_url_error else R.string.grace_button_url_hint)) },
                isError = text.isNotBlank() && uri == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.fillMaxWidth().testTag("grace_website_url"))
        },
        confirmButton = { TextButton(onClick = ::save, enabled = editable && uri != null) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun GraceButtonTarget.summary(uiState: LauncherUiState): String = when (action) {
    GraceButtonAction.App, GraceButtonAction.Shortcut -> itemKey?.let { uiState.findItem(it)?.label }
        ?: stringResource(R.string.popup_item_unavailable)
    GraceButtonAction.Folder -> uiState.folders.firstOrNull { it.key == itemKey }?.name
        ?: stringResource(R.string.folder_unavailable)
    GraceButtonAction.Website -> url.orEmpty()
    else -> stringResource(action.labelRes)
}

private val GraceButtonAction.labelRes: Int get() = when (this) {
    GraceButtonAction.App -> R.string.grace_button_open_app
    GraceButtonAction.Shortcut -> R.string.grace_button_open_shortcut
    GraceButtonAction.Folder -> R.string.open_folder
    GraceButtonAction.Settings -> R.string.grace_settings
    GraceButtonAction.Search -> R.string.settings_search
    GraceButtonAction.Website -> R.string.grace_button_open_website
    GraceButtonAction.LockScreen -> R.string.grace_button_lock_screen
    GraceButtonAction.AppList -> R.string.grace_button_open_app_list
    GraceButtonAction.Notifications -> R.string.grace_button_notifications
    GraceButtonAction.QuickSettings -> R.string.grace_button_quick_settings
    GraceButtonAction.Assistant -> R.string.grace_button_assistant
    GraceButtonAction.Agenda -> R.string.grace_button_agenda
    GraceButtonAction.Disabled -> R.string.grace_button_disabled
}
