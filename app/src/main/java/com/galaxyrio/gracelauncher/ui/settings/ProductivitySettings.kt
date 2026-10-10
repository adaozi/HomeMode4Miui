@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.galaxyrio.gracelauncher.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.FolderPlacement
import com.galaxyrio.gracelauncher.data.LauncherApp
import com.galaxyrio.gracelauncher.data.LauncherFolder
import com.galaxyrio.gracelauncher.ui.LauncherActions
import com.galaxyrio.gracelauncher.ui.LauncherUiState
import com.galaxyrio.gracelauncher.ui.components.AppIcon
import com.galaxyrio.gracelauncher.ui.components.AppSelectionList
import com.galaxyrio.gracelauncher.ui.components.LauncherIcon
import com.galaxyrio.gracelauncher.ui.components.LauncherSymbol
import com.galaxyrio.gracelauncher.ui.overlays.PopupEditorScreen
import com.galaxyrio.gracelauncher.ui.overlays.TextEntryDialog
import com.galaxyrio.gracelauncher.ui.overlays.FolderPlacementDialog
import com.galaxyrio.gracelauncher.ui.overlays.DeleteFolderDialog
import com.galaxyrio.gracelauncher.ui.theme.LocalLauncherTypography
import com.galaxyrio.gracelauncher.ui.theme.SystemLauncherTypography
import java.util.UUID
import kotlinx.coroutines.launch

@Composable
internal fun ProductivitySettings(
    uiState: LauncherUiState,
    actions: LauncherActions,
    onBack: () -> Unit,
    navigate: (SettingsPage) -> Unit,
) {
    val settings = uiState.settings
    SettingsScaffold(stringResource(R.string.settings_productivity), "settings_productivity", onBack) { padding ->
        SettingsList(padding) {
            item { SettingsHeading(stringResource(R.string.settings_instant_access)) }
            item {
                SettingsActionItem(
                    stringResource(R.string.settings_clock), clockAppSummary(uiState),
                    0, 8, "settings_clock",
                    leading = { LauncherIcon(LauncherSymbol.Clock) },
                ) { navigate(SettingsPage.Clock) }
            }
            item {
                SettingsActionItem(
                    stringResource(R.string.settings_calendar_agenda), stringResource(R.string.settings_calendar_agenda_summary),
                    1, 8, "calendar_agenda",
                    leading = { LauncherIcon(LauncherSymbol.Calendar) },
                ) { navigate(SettingsPage.Calendar) }
            }
            item {
                SettingsActionItem(
                    stringResource(R.string.settings_weather),
                    stringResource(if (settings.weatherEnabled) R.string.weather_settings_enabled_summary else R.string.weather_settings_disabled_summary),
                    2, 8, "settings_weather",
                    leading = { LauncherIcon(LauncherSymbol.Weather) },
                ) { navigate(SettingsPage.Weather) }
            }
            item {
                SettingsActionItem(
                    stringResource(R.string.settings_media_player), stringResource(R.string.media_player_summary),
                    3, 8, "settings_media_player",
                    leading = { LauncherIcon(LauncherSymbol.Music) },
                ) { navigate(SettingsPage.MediaPlayer) }
            }
            item {
                SettingsActionItem(stringResource(R.string.settings_search), stringResource(R.string.search_settings_summary),
                    4, 8, "settings_open_search", leading = { LauncherIcon(LauncherSymbol.Search) }) { navigate(SettingsPage.Search) }
            }
            item {
                SettingsActionItem(stringResource(R.string.settings_grace_button), null,
                    5, 8, "settings_grace_button", leading = {
                        // Remove the adaptive icon's transparent safe-zone inset;
                        // keep the same 24dp leading slot as Material Symbols.
                        Icon(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null,
                            modifier = Modifier.size(24.dp).graphicsLayer { scaleX = 2.25f; scaleY = 2.25f })
                    }) { navigate(SettingsPage.GraceButton) }
            }
            item {
                SettingsActionItem(stringResource(R.string.shortcuts_folders_title), null, 6, 8, "settings_shortcuts_folders",
                    leading = { LauncherIcon(LauncherSymbol.Outbound) }) { navigate(SettingsPage.ShortcutsFolders) }
            }
            item {
                SettingsActionItem(stringResource(R.string.widget_add), null, 7, 8, "settings_add_widget",
                    leading = { LauncherIcon(LauncherSymbol.Widgets) }, onClick = actions.addWidget)
            }
            item {
                Spacer(Modifier.height(12.dp))
                SettingsActionItem(stringResource(R.string.settings_move_widget), null, 0, 1, "settings_move_widget",
                    leading = { LauncherIcon(LauncherSymbol.Move) }, onClick = actions.moveWidget)
            }
            item { SettingsHeading(stringResource(R.string.settings_app_organization)) }
            item {
                SettingsActionItem(
                    stringResource(R.string.settings_hide_apps), pluralStringResource(R.plurals.settings_hidden_count, uiState.hiddenAppKeys.size, uiState.hiddenAppKeys.size),
                    0, 3, "settings_open_hidden_apps",
                    leading = { LauncherIcon(LauncherSymbol.VisibilityOff) },
                ) { navigate(SettingsPage.HiddenApps) }
            }
            item {
                SettingsActionItem(
                    stringResource(R.string.settings_folders), pluralStringResource(R.plurals.settings_folder_count, uiState.folders.size, uiState.folders.size),
                    1, 3, "settings_open_folders",
                    leading = { LauncherIcon(LauncherSymbol.Folder) },
                ) { navigate(SettingsPage.Folders) }
            }
            // 上游同步裁剪：Work Profile 行随 S8 批次恢复（ProfileSettings 页 + 宿主 workFolder 载体）
            item {
                SettingsActionItem(stringResource(R.string.private_space_title), stringResource(R.string.private_space_summary),
                    2, 3, "settings_open_private_space", leading = { LauncherIcon(LauncherSymbol.Lock) }) { navigate(SettingsPage.PrivateSpace) }
            }
            item { SettingsHeading(stringResource(R.string.settings_advanced)) }
            // 上游同步 v1.1.8 S3：Gestures 行恢复（桌面手势目标编辑，GraceButtonSettingsScreen home 面）
            item {
                SettingsActionItem(stringResource(R.string.settings_gestures), null, 0, 2, "settings_gestures",
                    leading = { LauncherIcon(LauncherSymbol.Gesture) }) { navigate(SettingsPage.Gestures) }
            }
            item {
                SettingsToggleItem(
                    stringResource(R.string.settings_allow_haptics), stringResource(R.string.settings_allow_haptics_summary),
                    settings.allowHapticFeedback, 1, 2, "allow_haptics",
                    leading = { LauncherIcon(LauncherSymbol.Vibration) },
                ) { value -> actions.updateSettings { current -> current.copy(allowHapticFeedback = value) } }
            }
        }
    }
}

@Composable
internal fun HiddenAppsSettings(uiState: LauncherUiState, actions: LauncherActions, onBack: () -> Unit) {
    var selectedKeys by rememberSaveable { mutableStateOf<List<String>>(uiState.hiddenAppKeys.toList()) }
    val queryState = rememberTextFieldState()
    val enabled = LocalSettingsStorageState.current.canEdit
    SettingsScaffold(
        stringResource(R.string.settings_hide_apps), "settings_hidden_apps", onBack,
        actions = {
            SettingsAppBarAction(
                text = stringResource(R.string.settings_save),
                onClick = { actions.setHiddenApps(selectedKeys.toSet()); onBack() },
                enabled = LocalSettingsStorageState.current.canEdit,
                modifier = Modifier.testTag("hidden_apps_save"),
            )
        },
    ) { padding ->
        AppSelectionList(
            apps = uiState.allApps, selectedKeys = selectedKeys.toSet(), query = queryState,
            onSelect = { app -> selectedKeys = toggledKeys(selectedKeys, app.key) },
            modifier = Modifier.padding(padding).consumeWindowInsets(padding).imePadding(),
            enabled = enabled, itemTagPrefix = "hidden_app",
        ) {
            item {
                Text(stringResource(R.string.settings_hide_apps_description), Modifier.padding(horizontal = 8.dp, vertical = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Text(pluralStringResource(R.plurals.settings_select_count, selectedKeys.size, selectedKeys.size),
                    Modifier.padding(horizontal = 8.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun FavoriteFoldersScreen(uiState: LauncherUiState, actions: LauncherActions, onBack: () -> Unit) {
    // Only keep pending changes here. Folder placement remains the source of truth,
    // including folders created/edited through the normal settings page below.
    var selection by rememberSaveable { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (editingId != null) {
        LauncherSettingsScreen(uiState, actions, onBack = { editingId = null }, initialFolderId = editingId,
            closePrivateSpaceOnDispose = false)
        return
    }
    val selectedIds = uiState.folders.filter { selection[it.id] ?: it.placement.inFavorites }
        .map { it.id }.toSet()
    val confirm: () -> Unit = {
        if (!saving) {
            if (selection.isEmpty()) onBack() else {
                saving = true
                val pending = selection.toMap()
                scope.launch {
                    try {
                        if (actions.setFolderFavorites(pending)) onBack()
                    } finally { saving = false }
                }
            }
        }
    }
    BackHandler(onBack = confirm)
    MaterialTheme(typography = if (uiState.settings.applyFontToSettings) LocalLauncherTypography.current else SystemLauncherTypography) {
        CompositionLocalProvider(LocalSettingsStorageState provides SettingsStorageState(
            uiState.isLoadingSettings, uiState.settingsLoadFailed, uiState.settingsSaveFailed,
        )) {
            FolderSettings(uiState, actions, confirm, selectedIds = selectedIds, enabled = !saving,
                onToggle = { id -> selection = selection + (id to (id !in selectedIds)) },
                onEdit = { editingId = it })
        }
    }
}

@Composable
internal fun FolderSettings(
    uiState: LauncherUiState, actions: LauncherActions, onBack: () -> Unit,
    selectedIds: Set<String>? = null, onToggle: (String) -> Unit = {}, enabled: Boolean = true,
    singleChoice: Boolean = false,
    onEdit: (String) -> Unit,
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var openingId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(openingId, uiState.folders, uiState.settingsSaveFailed) {
        val id = openingId ?: return@LaunchedEffect
        if (uiState.folders.any { it.id == id }) {
            openingId = null
            onEdit(id)
        } else if (uiState.settingsSaveFailed) openingId = null
    }
    SettingsScaffold(stringResource(R.string.settings_folders), "settings_folders", onBack) { padding ->
        SettingsList(padding) {
            item { Spacer(Modifier.height(24.dp)) }
            item {
                SettingsActionItem(
                    stringResource(R.string.settings_folder_create), null, 0, 1, "folder_create",
                    enabled = enabled && openingId == null,
                    leading = { LauncherIcon(LauncherSymbol.Plus) },
                ) { creating = true }
            }
            if (uiState.folders.isEmpty()) {
                item { Text(stringResource(R.string.settings_folder_empty), Modifier.padding(horizontal = 4.dp, vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                item { SettingsHeading(stringResource(R.string.settings_folders)) }
                itemsIndexed(uiState.folders, key = { _, folder -> folder.id }) { index, folder ->
                    val count = uiState.popups[folder.key]?.size ?: folder.appKeys.size
                    val checked = selectedIds?.contains(folder.id)
                    // An action selects a target, not whether the folder belongs in favorites.
                    val placement = if (checked == null || singleChoice) folder.placement else folder.placement.withFavorites(checked)
                    SettingsActionItem(
                        folder.name,
                        pluralStringResource(R.plurals.settings_folder_summary, count, count, placement.label()),
                        index, uiState.folders.size, "folder:${folder.id}",
                        enabled = enabled,
                        modifier = Modifier.semantics {
                            if (checked != null) {
                                if (singleChoice) {
                                    role = Role.RadioButton
                                    selected = checked
                                } else {
                                    role = Role.Checkbox
                                    toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
                                }
                            }
                        },
                        leading = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                if (checked != null) {
                                    if (singleChoice) RadioButton(checked, onClick = null,
                                        enabled = enabled && LocalSettingsStorageState.current.canEdit)
                                    else Checkbox(checked, onCheckedChange = null,
                                        enabled = enabled && LocalSettingsStorageState.current.canEdit)
                                }
                                AppIcon(uiState.folderItem(folder), size = 32.dp)
                            }
                        },
                    ) { if (selectedIds == null) onEdit(folder.id) else onToggle(folder.id) }
                }
            }
        }
    }
    if (creating) TextEntryDialog(stringResource(R.string.settings_folder_create), "", { creating = false }, { name ->
        val id = UUID.randomUUID().toString()
        actions.saveFolder(LauncherFolder(id, name, emptyList(), FolderPlacement.Favorites))
        creating = false
        openingId = id
    })
}

@Composable
internal fun FolderEditorSettings(folderId: String?, uiState: LauncherUiState, actions: LauncherActions, onBack: () -> Unit,
    onEditIcon: (LauncherApp) -> Unit = {}) {
    val folder = uiState.folders.firstOrNull { it.id == folderId }
    if (folder == null) {
        SettingsScaffold(stringResource(R.string.settings_folder_edit), "settings_folder_editor", onBack) { padding ->
            Box(Modifier.padding(padding)) {
                Text(stringResource(R.string.folder_unavailable), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    val app = uiState.folderItem(folder)
    var rename by rememberSaveable(folder.id) { mutableStateOf(false) }
    var placement by rememberSaveable(folder.id) { mutableStateOf(false) }
    var confirmDelete by rememberSaveable(folder.id) { mutableStateOf(false) }
    PopupEditorScreen(app, uiState, actions, onBack, header = {
        Column(Modifier.padding(top = 16.dp)) {
            SettingsActionItem(folder.name, stringResource(R.string.settings_folder_name), 0, 2, "folder_name",
                leading = { AppIcon(app, Modifier.clickable(onClickLabel = stringResource(R.string.icon_designer_title)) { onEditIcon(app) }, size = 36.dp) }) { rename = true }
            Spacer(Modifier.height(ListItemDefaults.SegmentedGap))
            SettingsActionItem(stringResource(R.string.settings_folder_placement), folder.placement.label(), 1, 2, "folder_placement",
                leading = { LauncherIcon(LauncherSymbol.Folder) }) { placement = true }
            TextButton(onClick = { confirmDelete = true }, enabled = LocalSettingsStorageState.current.canEdit, modifier = Modifier.testTag("folder_delete")) {
                Text(stringResource(R.string.settings_folder_delete), color = MaterialTheme.colorScheme.error)
            }
        }
    })
    if (rename) TextEntryDialog(stringResource(R.string.rename_folder), folder.name, { rename = false }, {
        actions.rename(app, it); rename = false
    })
    if (placement) FolderPlacementDialog(folder.placement, folder.appListAtBottom, { placement = false }) { selected, atBottom ->
        actions.updateFolder(folder.id, null, selected, atBottom); placement = false
    }
    if (confirmDelete) DeleteFolderDialog(folder.name, { confirmDelete = false }) {
        actions.deleteFolder(folder.id); onBack()
    }
}

@Composable
private fun FolderPlacement.label(): String = stringResource(
    when (this) {
        FolderPlacement.Favorites -> R.string.settings_folder_favorites
        FolderPlacement.AppList -> R.string.settings_folder_app_list
        FolderPlacement.Both -> R.string.settings_folder_both
        FolderPlacement.None -> R.string.settings_folder_nowhere
    },
)

private fun toggledKeys(keys: List<String>, key: String): List<String> =
    if (key in keys) keys.filterNot { it == key } else keys + key
