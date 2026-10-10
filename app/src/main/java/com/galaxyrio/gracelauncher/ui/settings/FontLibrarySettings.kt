/*
 * 上游同步 v1.1.8（S2 依赖 / S5 主体）—— 移植自 GraceLauncher v1.1.8 原版
 * ui/settings/FontSettings.kt（字体库选择器 FontPickerDialog + Fonts 管理页）。
 * appFontLabel 仍由本仓 FontSettings.kt 承载（v1.1.4 同名实现），故此处不重复定义。
 */
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.galaxyrio.gracelauncher.ui.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.*
import com.galaxyrio.gracelauncher.ui.LauncherActions
import com.galaxyrio.gracelauncher.ui.LauncherUiState
import com.galaxyrio.gracelauncher.ui.components.LauncherSearchBar
import com.galaxyrio.gracelauncher.ui.components.SelectionScrollbar
import com.galaxyrio.gracelauncher.ui.overlays.FavoritesReorderState
import com.galaxyrio.gracelauncher.ui.theme.rememberAppFontFamily
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Component pickers only expose enabled fonts; adding/managing always uses the same page. */
@Composable
internal fun FontPickerDialog(library: FontLibrary, selected: String?, onSelect: (String?) -> Unit,
    onManage: () -> Unit, onDismiss: () -> Unit, tag: String = "font_picker") {
    val context = LocalContext.current
    val store = remember(context) { ClockFontStore(context) }
    val files by produceState(emptyList<ClockFontFile>(), store) { value = store.list() }
    val available = FontLibrary.builtIns + files.map { it.id }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.settings_font)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp).selectableGroup().testTag(tag)) {
                item("default") { FontOption(null, selected == null, onSelect) }
                items(library.selected.filter { it in available }, key = { it }) { id -> FontOption(id, selected == id, onSelect) }
            }
        },
        confirmButton = { TextButton(onClick = onManage) { Text(stringResource(R.string.popup_add_new)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun FontOption(id: String?, checked: Boolean, onSelect: (String?) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(MaterialTheme.shapes.medium)
        .selectable(checked, role = Role.RadioButton, onClick = { onSelect(id) }).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        RadioButton(checked, null)
        Text(appFontLabel(id), fontFamily = rememberAppFontFamily(id), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun FontSettings(uiState: LauncherUiState, actions: LauncherActions, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { ClockFontStore(context) }
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    val files by produceState(emptyList<ClockFontFile>(), store, revision) { value = store.list(); loaded = true }
    val imported = files.map { it.id }.toSet()
    val available = FontLibrary.builtIns + files.map { it.id }
    val labels = available.associateWith { appFontLabel(it) }
    val library = uiState.settings.fontLibrary
    val ordered = library.ordered(available)
    val query = rememberTextFieldState()
    val list = rememberLazyListState()
    var deleting by rememberSaveable { mutableStateOf(false) }
    var deletion by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val enabled = LocalSettingsStorageState.current.canEdit && !busy && loaded
    val commit by rememberUpdatedState<(List<String>) -> Unit>({ order ->
        actions.updateSettings { it.copy(fontLibrary = it.fontLibrary.reorder(order)) }
    })
    val haptics by rememberUpdatedState(LocalHapticFeedback.current)
    val checkedFonts by rememberUpdatedState(library.selected)
    val reorder = remember(list) { FavoritesReorderState(list, ordered, { commit(it) },
        canMove = { from, to -> (from in checkedFonts) == (to in checkedFonts) }) {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    } }
    LaunchedEffect(ordered) { reorder.synchronize(ordered) }
    val density = LocalDensity.current
    LaunchedEffect(reorder.draggingKey, density) {
        if (reorder.draggingKey == null) return@LaunchedEffect
        val edge = with(density) { 64.dp.toPx() }
        val speed = with(density) { 640.dp.toPx() }
        var previous = withFrameNanos { it }
        while (isActive && reorder.draggingKey != null) {
            val frame = withFrameNanos { it }
            reorder.autoScroll(edge, speed * ((frame - previous) / 1_000_000_000f).coerceIn(0f, .032f))
            previous = frame
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val font = store.import(uri, requireClockDigits = false)
                actions.updateSettings { it.copy(fontLibrary = it.fontLibrary.copy(selected = it.fontLibrary.selected + font.id)) }
                revision++
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Toast.makeText(context, R.string.font_import_error, Toast.LENGTH_LONG).show()
            } finally { busy = false }
        }
    }
    fun cancelDeletion() { deleting = false; deletion = emptyList(); confirmDelete = false }
    BackHandler(deleting && !busy) { cancelDeletion() }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { if (!busy) confirmDelete = false },
        title = { Text(stringResource(R.string.font_delete_title)) },
        text = { Text(stringResource(R.string.font_delete_summary, deletion.size)) },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try {
                    if (actions.deleteFonts(deletion.toSet())) cancelDeletion()
                    else Toast.makeText(context, R.string.settings_storage_save_error, Toast.LENGTH_LONG).show()
                    revision++
                } finally { busy = false }
            }
        }) { Text(stringResource(R.string.font_delete)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } })
    val filtered = reorder.keys.filter { labels[it].orEmpty().contains(query.text.toString().trim(), ignoreCase = true) }
    SettingsScaffold(stringResource(R.string.settings_font), "font_manager", { if (deleting) cancelDeletion() else onBack() },
        fixedCollapsed = true, actions = {
            if (deleting) {
                TextButton(enabled = enabled && deletion.isNotEmpty(), onClick = { confirmDelete = true }) { Text(stringResource(R.string.font_delete)) }
                TextButton(enabled = !busy, onClick = { cancelDeletion() }) { Text(stringResource(R.string.cancel)) }
            }
        }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            LazyColumn(Modifier.fillMaxSize().testTag("font_manager_list"), state = list,
                contentPadding = PaddingValues(start = 16.dp, end = 24.dp, bottom = 24.dp + LocalSettingsBottomInset.current),
                userScrollEnabled = reorder.draggingKey == null) {
                item("settings") {
                    SettingsToggleItem(stringResource(R.string.font_apply_settings), stringResource(R.string.font_apply_settings_summary),
                        uiState.settings.applyFontToSettings, 0, 1, "apply_font_to_settings", enabled = enabled) { value ->
                        actions.updateSettings { it.copy(applyFontToSettings = value) }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                item("add") {
                    SettingsActionItem(stringResource(R.string.font_add), null, 0, 1, "font_add", enabled = enabled,
                        onClick = { picker.launch(arrayOf("*/*")) })
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                item("heading") { SettingsHeading(stringResource(R.string.font_all)) }
                item("search") { LauncherSearchBar(query, stringResource(R.string.font_search), "font_query"); Spacer(Modifier.height(12.dp)) }
                items(filtered, key = { FavoritesReorderState.itemKey(it) }) { id ->
                    val dragging = reorder.draggingKey == id
                    val offset by animateFloatAsState(if (dragging) reorder.translation else 0f,
                        animationSpec = if (dragging) snap() else spring(), label = "fontDrop")
                    val checked = if (deleting) id in deletion else id in library.selected
                    val canSelect = enabled && (!deleting || id in imported)
                    val up = stringResource(R.string.favorites_move_up)
                    val down = stringResource(R.string.favorites_move_down)
                    val label = labels[id].orEmpty()
                    fun toggle() {
                        if (deleting) deletion = if (id in deletion) deletion - id else deletion + id
                        else actions.updateSettings { it.copy(fontLibrary = it.fontLibrary.toggle(id, available)) }
                    }
                    Row(Modifier.then(if (dragging) Modifier else Modifier.animateItem())
                        .zIndex(if (dragging || offset != 0f) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) reorder.translation else offset }
                        .fillMaxWidth().heightIn(min = 64.dp).clip(MaterialTheme.shapes.large)
                        .background(if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer)
                        .combinedClickable(enabled = canSelect, role = Role.Checkbox, onClick = { toggle() },
                            onLongClick = if (id in imported) ({ deleting = true; deletion = listOf(id) }) else null)
                        .semantics { if (!deleting) customActions = listOf(
                            CustomAccessibilityAction(up) { reorder.moveBy(id, -1) }, CustomAccessibilityAction(down) { reorder.moveBy(id, 1) }) }
                        .testTag("font:$id").padding(start = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked, null, Modifier.size(24.dp), enabled = canSelect)
                        Spacer(Modifier.width(16.dp))
                        Text(label, Modifier.weight(1f), fontFamily = rememberAppFontFamily(id), maxLines = 2,
                            overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (canSelect) 1f else .38f))
                        if (!deleting) {
                            var menu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { menu = true }, enabled = enabled && query.text.isEmpty(),
                                    modifier = Modifier.pointerInput(reorder, id, enabled, query.text.isEmpty()) {
                                        if (enabled && query.text.isEmpty()) detectDragGestures(
                                            onDragStart = { reorder.start(id) }, onDragEnd = { reorder.finish(false) },
                                            onDragCancel = { reorder.finish(true) }) { change, amount -> change.consume(); reorder.drag(amount.y) }
                                    }) { Icon(painterResource(R.drawable.ms_drag_indicator), stringResource(R.string.favorites_reorder, label)) }
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem(text = { Text(up) }, onClick = { reorder.moveBy(id, -1); menu = false })
                                    DropdownMenuItem(text = { Text(down) }, onClick = { reorder.moveBy(id, 1); menu = false })
                                }
                            }
                        } else Spacer(Modifier.width(16.dp))
                    }
                }
            }
            SelectionScrollbar(list, filtered.map { labels[it].orEmpty().take(1).uppercase() }, enabled && reorder.draggingKey == null,
                Modifier.align(Alignment.CenterEnd).padding(bottom = LocalSettingsBottomInset.current), "font_scrollbar", keyPrefix = "selected:")
        }
    }
}
