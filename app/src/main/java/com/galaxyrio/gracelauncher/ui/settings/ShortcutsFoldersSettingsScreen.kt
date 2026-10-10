/*
 * 上游同步 v1.1.8（S2/S7）—— 移植自 GraceLauncher v1.1.8 原版
 * ui/settings/ShortcutsFoldersSettings.kt（弹层/文件夹设置 + 通知显示策略）。
 */
package com.galaxyrio.gracelauncher.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.NotificationDisplay
import com.galaxyrio.gracelauncher.data.ShortcutsFoldersSettings
import com.galaxyrio.gracelauncher.ui.LauncherActions
import com.galaxyrio.gracelauncher.ui.LauncherUiState
import com.galaxyrio.gracelauncher.ui.components.LauncherIcon
import com.galaxyrio.gracelauncher.ui.components.LauncherSymbol

@Composable
internal fun ShortcutsFoldersSettingsScreen(uiState: LauncherUiState, actions: LauncherActions, onBack: () -> Unit) {
    val settings = uiState.settings.shortcutsFolders
    var requestAccess by rememberSaveable { mutableStateOf(false) }
    fun update(change: (ShortcutsFoldersSettings) -> ShortcutsFoldersSettings) =
        actions.updateSettings { it.copy(shortcutsFolders = change(it.shortcutsFolders)) }

    SettingsScaffold(stringResource(R.string.shortcuts_folders_title), "shortcuts_folders_settings", onBack) { padding ->
        SettingsList(padding) {
            item {
                SettingsFeatureBanner(stringResource(R.string.shortcuts_folders_enable), settings.enabled, "shortcuts_folders_enabled") { value ->
                    update { it.copy(enabled = value) }
                }
            }
            if (!uiState.media.hasAccess) item {
                SettingsActionItem(stringResource(R.string.notification_allow), stringResource(R.string.media_access_required),
                    0, 1, "notifications_access", leading = { LauncherIcon(LauncherSymbol.Notifications) }) { requestAccess = true }
                Spacer(Modifier.height(12.dp))
            }
            item {
                SettingsToggleItem(stringResource(R.string.popup_swipe_left_first), stringResource(R.string.popup_swipe_left_first_summary),
                    settings.swipeLeftToOpenFirst, 0, 3, "popup_swipe_left_first") { value ->
                    update { it.copy(swipeLeftToOpenFirst = value) }
                }
            }
            item {
                NotificationDisplayItem(stringResource(R.string.popup_silent_notifications),
                    settings.silentNotifications, settings.enabled, 1, "popup_silent") { mode ->
                    update { it.copy(silentNotifications = mode) }
                }
            }
            item {
                NotificationDisplayItem(stringResource(R.string.popup_normal_notifications),
                    settings.normalNotifications, settings.enabled, 2, "popup_normal") { mode ->
                    update { it.copy(normalNotifications = mode) }
                }
            }
        }
    }
    if (requestAccess) AlertDialog(
        onDismissRequest = { requestAccess = false },
        title = { Text(stringResource(R.string.notification_allow)) },
        text = { Text(stringResource(R.string.media_access_explanation)) },
        confirmButton = {
            TextButton(onClick = { requestAccess = false; actions.requestMediaAccess() }, modifier = Modifier.testTag("media_access_continue")) {
                Text(stringResource(R.string.media_open_settings))
            }
        },
        dismissButton = {
            TextButton(onClick = { requestAccess = false }) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun NotificationDisplayItem(title: String, selected: NotificationDisplay, enabled: Boolean,
    index: Int, tag: String, onSelect: (NotificationDisplay) -> Unit) {
    val canEdit = enabled && LocalSettingsStorageState.current.canEdit
    SegmentedListItem(
        shapes = ListItemDefaults.segmentedShapes(index, 3),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        enabled = canEdit,
        modifier = Modifier.testTag(tag),
        content = {
            Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title)
                // M3 connected buttons have asymmetric end shapes and animate to
                // the checked shape, rather than sharing a segmented outline.
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                ) {
                    NotificationDisplay.entries.forEachIndexed { buttonIndex, mode ->
                        ToggleButton(
                            checked = selected == mode,
                            onCheckedChange = { onSelect(mode) },
                            enabled = canEdit,
                            shapes = when (buttonIndex) {
                                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                NotificationDisplay.entries.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                            },
                            modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp)
                                .testTag("$tag:${mode.id}").semantics { role = Role.RadioButton },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                        ) {
                            Text(stringResource(when (mode) {
                                NotificationDisplay.Normal -> R.string.popup_notification_normal
                                NotificationDisplay.ExpandedOnly -> R.string.popup_notification_expanded
                                NotificationDisplay.Hidden -> R.string.popup_notification_hidden
                            }), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        },
    )
}
