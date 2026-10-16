package com.galaxyrio.gracelauncher.ui.overlays

import android.appwidget.AppWidgetManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.*
import com.galaxyrio.gracelauncher.ui.LauncherActions
import com.galaxyrio.gracelauncher.ui.LauncherUiState
import com.galaxyrio.gracelauncher.ui.components.AppIcon
import com.galaxyrio.gracelauncher.ui.components.AppSelectionList
import com.galaxyrio.gracelauncher.ui.components.AppSelectionRow
import com.galaxyrio.gracelauncher.ui.components.LauncherIcon
import com.galaxyrio.gracelauncher.ui.components.LauncherSymbol
import com.galaxyrio.gracelauncher.ui.components.ShortcutPickerScreen
import com.galaxyrio.gracelauncher.ui.settings.FavoriteFoldersScreen
import com.galaxyrio.gracelauncher.ui.settings.SettingsScaffold
import com.galaxyrio.gracelauncher.ui.widgets.WidgetPreview
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private data class SelectionEntry(val key: String, val label: String, val app: LauncherApp? = null, val widget: HomeLayout? = null)

@Composable
internal fun PrivateSpaceEditorScreen(owner: LauncherApp, uiState: LauncherUiState, actions: LauncherActions,
    onBack: () -> Unit, header: @Composable () -> Unit) {
    ItemSelectionScreen(
        title = stringResource(R.string.edit_app_popup, owner.label), tag = "private_space_editor",
        selected = uiState.privateSpaceApps.map { SelectionEntry(it.key, it.label, it) },
        apps = emptyList(), enabled = !uiState.privateAppsLoading && !uiState.privateAppsFailed,
        onToggle = {}, onRemove = {}, onReorder = actions.reorderPrivateApps, onDone = onBack,
        header = header, reorderOnly = true, loading = uiState.privateAppsLoading,
    )
}

/** 上游同步 v1.1.8 S8b：工作资料应用编辑器（ProfileEditorScreen，PrivateSpaceEditorScreen 的 work 泛化）。 */
@Composable
internal fun ProfileEditorScreen(owner: LauncherApp, uiState: LauncherUiState, actions: LauncherActions,
    onBack: () -> Unit, work: Boolean = false, header: @Composable () -> Unit) {
    val loading = if (work) uiState.isLoadingApps else uiState.privateAppsLoading
    val failed = if (work) uiState.appLoadFailed else uiState.privateAppsFailed
    ItemSelectionScreen(
        title = stringResource(R.string.edit_app_popup, owner.label), tag = "work_profile_editor",
        selected = (if (work) uiState.workProfileApps else uiState.privateSpaceApps).map { SelectionEntry(it.key, it.label, it) },
        apps = emptyList(), enabled = !loading && !failed,
        onToggle = {}, onRemove = {}, onReorder = if (work) actions.reorderWorkApps else actions.reorderPrivateApps, onDone = onBack,
        header = header, reorderOnly = true, loading = loading,
    )
}

@Composable
internal fun FavoritesScreen(uiState: LauncherUiState, actions: LauncherActions, onToggle: (LauncherApp) -> Unit, onReorder: (List<String>) -> Unit, onDone: () -> Unit,
    // hotfix8（宿主 shim 适配）：false = 隐藏收藏文件夹入口（宿主 folders 渲染载体随 folders
    // 阶段，空实现会让"新建文件夹"静默失败）。默认 true 与原版行为一致。
    foldersEnabled: Boolean = true) {
    var choosingShortcut by rememberSaveable { mutableStateOf(false) }
    var choosingFolders by rememberSaveable { mutableStateOf(false) }
    if (choosingFolders) {
        FavoriteFoldersScreen(uiState, actions) { choosingFolders = false }
        return
    }
    if (choosingShortcut) {
        ShortcutPickerScreen(uiState, actions, uiState.favoriteKeys, { choosingShortcut = false }, onToggle)
        return
    }
    ItemSelectionScreen(
        title = stringResource(R.string.edit_favorites), tag = "favorites",
        selected = uiState.favoriteItems.map { SelectionEntry(it.key, it.label, it) },
        apps = uiState.allApps, enabled = !uiState.isLoadingSettings && !uiState.settingsLoadFailed,
        onToggle = onToggle, onRemove = { key -> uiState.findItem(key)?.let(onToggle) }, onReorder = onReorder, onDone = onDone,
        onAddShortcut = { choosingShortcut = true },
        onAddFolder = if (foldersEnabled) ({ choosingFolders = true }) else null,
    )
}

@Composable
internal fun PopupEditorScreen(owner: LauncherApp, uiState: LauncherUiState, actions: LauncherActions, onBack: () -> Unit,
    header: (@Composable () -> Unit)? = null) {
    val isFolder = owner.folderId != null
    var retry by remember { mutableIntStateOf(0) }
    val result by produceState(if (isFolder) ShortcutResult(ShortcutStatus.Ready) else actions.cachedShortcuts(owner) ?: ShortcutResult(ShortcutStatus.Loading),
        owner.key, uiState.hasShortcutAccess, retry, uiState.itemRevision) { if (!isFolder) value = actions.shortcuts(owner) }
    val shortcuts = result.shortcuts.map { uiState.findItem(it.key) ?: it.asApp(owner) }
    val entries = uiState.popupItems(owner, shortcuts)
    val missing = stringResource(R.string.popup_item_unavailable)
    val selected = entries.map { item ->
        val app = uiState.findItem(item.key) ?: shortcuts.firstOrNull { it.key == item.key }
        SelectionEntry(item.key, item.widget?.widgetLabel ?: app?.label ?: missing, app, item.widget)
    }
    val ready = !uiState.isLoadingSettings && !uiState.settingsLoadFailed &&
        (uiState.popups.containsKey(owner.key) || result.status == ShortcutStatus.Ready || result.status == ShortcutStatus.DefaultLauncherRequired)
    var choosingShortcut by rememberSaveable(owner.key) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (choosingShortcut) {
        ShortcutPickerScreen(uiState, actions, entries.map { it.key }.toSet(), { choosingShortcut = false }, busy = saving, onSelect = { app ->
            saving = true
            scope.launch {
                try {
                    if (actions.rememberShortcut(app)) actions.updatePopup(owner, shortcuts) { current ->
                        if (current.any { it.key == app.key }) current.filterNot { it.key == app.key } else current + PopupItem(app.key)
                    }
                } finally { saving = false }
            }
        })
        return
    }
    ItemSelectionScreen(
        title = stringResource(R.string.edit_app_popup, owner.label), tag = "popup_editor", selected = selected,
        apps = uiState.allApps.filterNot { it.key == owner.key }, enabled = ready,
        shortcuts = shortcuts.takeUnless { isFolder }, shortcutTitle = stringResource(R.string.popup_app_shortcuts, owner.label),
        header = header,
        shortcutStatus = result.status, onRetry = { retry++ },
        onAddWidget = { actions.addPopupWidget(owner, shortcuts) },
        onAddShortcut = { choosingShortcut = true },
        onToggle = { app -> actions.updatePopup(owner, shortcuts) { current ->
            if (current.any { it.key == app.key }) current.filterNot { it.key == app.key } else current + PopupItem(app.key)
        } },
        onRemove = { key -> actions.updatePopup(owner, shortcuts) { it.filterNot { item -> item.key == key } } },
        onReorder = { keys -> actions.updatePopup(owner, shortcuts) { current ->
            val byKey = current.associateBy { it.key }
            keys.mapNotNull(byKey::get) + current.filterNot { it.key in keys }
        } }, onDone = onBack,
    )
}

/** One full-screen editor for favorites and mixed pop-up contents. Search only filters All apps. */
@Composable
private fun ItemSelectionScreen(
    title: String, tag: String, selected: List<SelectionEntry>, apps: List<LauncherApp>, enabled: Boolean,
    onToggle: (LauncherApp) -> Unit, onRemove: (String) -> Unit, onReorder: (List<String>) -> Unit, onDone: () -> Unit,
    shortcuts: List<LauncherApp>? = null, shortcutTitle: String = "", shortcutStatus: ShortcutStatus = ShortcutStatus.Ready,
    onRetry: () -> Unit = {}, onAddWidget: (() -> Unit)? = null,
    onAddShortcut: (() -> Unit)? = null,
    onAddFolder: (() -> Unit)? = null,
    header: (@Composable () -> Unit)? = null,
    reorderOnly: Boolean = false,
    loading: Boolean = false,
) {
    val selectedKeys = selected.map { it.key }
    val selectedEntries = selected.associateBy { it.key }
    val list = rememberLazyListState()
    val query = rememberTextFieldState()
    val commit by rememberUpdatedState(onReorder)
    val haptics by rememberUpdatedState(LocalHapticFeedback.current)
    val reorder = remember(list) { FavoritesReorderState(list, selectedKeys, { commit(it) }) {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    } }
    LaunchedEffect(selectedKeys) { reorder.synchronize(selectedKeys) }
    val density = LocalDensity.current
    LaunchedEffect(reorder.draggingKey, density) {
        if (reorder.draggingKey == null) return@LaunchedEffect
        val edge = with(density) { 64.dp.toPx() }
        val speed = with(density) { 640.dp.toPx() }
        var previous = withFrameNanos { it }
        while (isActive && reorder.draggingKey != null) {
            val frame = withFrameNanos { it }
            val seconds = ((frame - previous) / 1_000_000_000f).coerceIn(0f, 0.032f)
            previous = frame
            reorder.autoScroll(edge, speed * seconds)
        }
    }
    SettingsScaffold(title, "${tag}_screen", onDone, fixedCollapsed = true, actions = {
        TextButton(onClick = onDone, modifier = Modifier.testTag("${tag}_done")) { Text(stringResource(R.string.done)) }
    }) { padding ->
        AppSelectionList(
            apps = apps.filter { it.shortcut == null }, selectedKeys = selectedKeys.toSet(), query = query, onSelect = onToggle,
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            state = list, listTag = "${tag}_list", searchTag = "${tag}_search", itemTagPrefix = "favorite_all",
            enabled = enabled && reorder.draggingKey == null, userScrollEnabled = reorder.draggingKey == null,
            showApps = !reorderOnly,
        ) {
            if (header != null) item(key = "editor_header", contentType = "header") { header() }
            item(key = "selected_header", contentType = "header") { SelectionHeading(stringResource(R.string.favorites_selected), "${tag}_selected") }
            if (loading) item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth().padding(8.dp)) }
            if (!loading && reorder.keys.isEmpty()) item(key = "empty") {
                Text(stringResource(if (reorderOnly) R.string.private_space_empty else if (onAddWidget == null) R.string.favorites_empty else R.string.popup_empty), Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(reorder.keys.mapNotNull(selectedEntries::get), key = { FavoritesReorderState.itemKey(it.key) }, contentType = { if (it.widget == null) "selected" else "widget" }) { entry ->
                val dragging = reorder.draggingKey == entry.key
                val offset by animateFloatAsState(if (dragging) reorder.translation else 0f,
                    animationSpec = if (dragging) snap() else spring(), label = "selectionDrop")
                val background by animateColorAsState(if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
                    label = "selectionLift")
                val index = reorder.keys.indexOf(entry.key)
                val moveUp = stringResource(R.string.favorites_move_up)
                val moveDown = stringResource(R.string.favorites_move_down)
                Column(Modifier.then(if (dragging) Modifier else Modifier.animateItem())
                    .zIndex(if (dragging || offset != 0f) 1f else 0f)
                    .graphicsLayer { translationY = if (dragging) reorder.translation else offset }
                    .clip(RoundedCornerShape(16.dp)).background(background)
                    .semantics {
                        customActions = buildList {
                            if (index > 0) add(CustomAccessibilityAction(moveUp) { reorder.moveBy(entry.key, -1) })
                            if (index < reorder.keys.lastIndex) add(CustomAccessibilityAction(moveDown) { reorder.moveBy(entry.key, 1) })
                        }
                    }) {
                    SelectionChoice(entry, true, "favorite:${entry.key}", enabled && reorder.draggingKey == null, { onRemove(entry.key) },
                        selectable = !reorderOnly) {
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menu = true }, enabled = enabled,
                                modifier = Modifier.testTag("favorite_drag:${entry.key}").pointerInput(reorder, entry.key, enabled) {
                                    if (enabled) detectDragGestures(
                                        onDragStart = { reorder.start(entry.key) }, onDragEnd = { reorder.finish(false) },
                                        onDragCancel = { reorder.finish(true) },
                                    ) { change, amount -> change.consume(); reorder.drag(amount.y) }
                                }) {
                                Icon(painterResource(R.drawable.ms_drag_indicator), stringResource(R.string.favorites_reorder, entry.label), Modifier.size(24.dp))
                            }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text(moveUp) }, enabled = index > 0, onClick = { reorder.moveBy(entry.key, -1); menu = false })
                                DropdownMenuItem(text = { Text(moveDown) }, enabled = index < reorder.keys.lastIndex, onClick = { reorder.moveBy(entry.key, 1); menu = false })
                            }
                        }
                    }
                    entry.widget?.let { SelectedWidgetPreview(it) }
                }
            }
            if (shortcuts != null) {
                item(key = "shortcuts_header", contentType = "header") { SelectionHeading(shortcutTitle, "popup_shortcuts") }
                when (shortcutStatus) {
                    ShortcutStatus.Loading -> item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(8.dp)) }
                    ShortcutStatus.DefaultLauncherRequired -> item {
                        // 模块 hook 宿主桌面（本身即默认启动器），此状态仅中继降级时出现；
                        // 无"设为默认"动作可给，只提示。
                        Text(stringResource(R.string.shortcut_permission), Modifier.padding(8.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                    ShortcutStatus.Error -> item { TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) } }
                    ShortcutStatus.Ready -> if (shortcuts.isEmpty()) item {
                        Text(stringResource(R.string.no_shortcuts), Modifier.padding(8.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                items(shortcuts, key = { "shortcut:${it.key}" }, contentType = { "app" }) { app ->
                    SelectionChoice(SelectionEntry(app.key, app.label, app), app.key in selectedKeys, "popup_choice:${app.key}", enabled,
                        { onToggle(app) })
                }
            }
            if (onAddWidget != null) {
                item(key = "widgets_header", contentType = "header") {
                    SelectionHeading(stringResource(R.string.popup_custom_widget), "popup_widgets")
                }
                item(key = "add_widget") {
                    AddSelectionContent("popup_add_widget", enabled, onAddWidget)
                }
            }
            if (onAddShortcut != null) {
                item(key = "add_shortcuts_header", contentType = "header") {
                    SelectionHeading(stringResource(R.string.shortcuts_title), "${tag}_shortcuts")
                }
                item(key = "add_shortcut") { AddSelectionContent("${tag}_add_shortcut", enabled, onAddShortcut) }
            }
            if (onAddFolder != null) {
                item(key = "folders_header", contentType = "header") {
                    SelectionHeading(stringResource(R.string.settings_folders), "${tag}_folders")
                }
                item(key = "add_folder") { AddSelectionContent("${tag}_add_folder", enabled, onAddFolder) }
            }
            if (!reorderOnly) item(key = "all_header", contentType = "header") {
                SelectionHeading(stringResource(R.string.favorites_all_apps), "${tag}_all_apps")
            }
        }
    }
}

@Composable
private fun AddSelectionContent(tag: String, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag(tag).clip(RoundedCornerShape(16.dp))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { LauncherIcon(LauncherSymbol.Plus) }
        }
        Spacer(Modifier.width(16.dp))
        Text(stringResource(R.string.popup_add_new))
    }
}

@Composable
private fun SelectionHeading(text: String, tag: String) {
    Text(text, Modifier.fillMaxWidth().testTag(tag).semantics { heading() }.padding(horizontal = 8.dp, vertical = 16.dp),
        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SelectionChoice(entry: SelectionEntry, checked: Boolean, tag: String, enabled: Boolean, onToggle: () -> Unit,
    modifier: Modifier = Modifier, selectable: Boolean = true, trailing: @Composable () -> Unit = {}) {
    AppSelectionRow(entry.label, checked, onToggle, modifier.testTag(tag), enabled = enabled,
        selectable = selectable, trailing = trailing, icon = {
            if (entry.app != null) AppIcon(entry.app, size = 36.dp)
            else LauncherIcon(LauncherSymbol.Apps)
        })
}

@Composable
private fun SelectedWidgetPreview(layout: HomeLayout) {
    val context = LocalContext.current
    val info = remember(layout.widgetId) { runCatching { AppWidgetManager.getInstance(context).getAppWidgetInfo(layout.widgetId) }.getOrNull() }
    if (info != null) WidgetPreview(info, null, Modifier.fillMaxWidth().height(160.dp).padding(start = 48.dp, end = 16.dp, bottom = 12.dp))
    else Text(stringResource(R.string.widget_preview_unavailable), Modifier.padding(start = 48.dp, bottom = 16.dp), style = MaterialTheme.typography.bodySmall)
}
