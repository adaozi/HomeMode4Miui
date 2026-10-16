@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.galaxyrio.gracelauncher.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.icons.IconPackInfo
import com.galaxyrio.gracelauncher.ui.LauncherActions
import com.galaxyrio.gracelauncher.ui.LauncherUiState
import com.galaxyrio.gracelauncher.ui.components.LauncherIcon
import com.galaxyrio.gracelauncher.ui.components.LauncherSearchBar
import com.galaxyrio.gracelauncher.ui.components.LauncherSymbol
import com.galaxyrio.gracelauncher.ui.overlays.FavoritesReorderState
import com.galaxyrio.gracelauncher.ui.overlays.rememberReorderTranslation
import kotlinx.coroutines.isActive

@Composable
internal fun IconPackSettings(uiState: LauncherUiState, actions: LauncherActions, onBack: () -> Unit, onDesigner: () -> Unit) {
    val selected = uiState.settings.enabledIconPackPackages
    val installed = uiState.iconPacks.associateBy { it.packageName }
    val list = rememberLazyListState()
    val query = rememberTextFieldState()
    val search = query.text.toString().trim()
    val filtered = remember(uiState.iconPacks, search) {
        uiState.iconPacks.filter { search.isEmpty() || it.label.contains(search, true) || it.packageName.contains(search, true) }
    }
    val currentActions by rememberUpdatedState(actions)
    val haptics by rememberUpdatedState(LocalHapticFeedback.current)
    val hapticsEnabled by rememberUpdatedState(uiState.settings.allowHapticFeedback)
    val reorder = remember(list) {
        FavoritesReorderState(list, selected, { order ->
            currentActions.updateSettings { settings ->
                settings.withIconPacks(order + settings.enabledIconPackPackages.filterNot { it in order })
            }
        }) { if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    }
    LaunchedEffect(selected) { reorder.synchronize(selected) }
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
    val enabled = LocalSettingsStorageState.current.canEdit && reorder.draggingKey == null
    fun toggle(packageName: String) = actions.updateSettings { settings ->
        val order = settings.enabledIconPackPackages
        settings.withIconPacks(if (packageName in order) order - packageName else order + packageName)
    }
    SettingsScaffold(stringResource(R.string.settings_icons), "icon_pack_settings", onBack, fixedCollapsed = true) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
                .padding(horizontal = 16.dp).testTag("icon_pack_list"),
            state = list, contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp + LocalSettingsBottomInset.current), userScrollEnabled = reorder.draggingKey == null,
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            item(key = "designer") {
                Surface(
                    onClick = onDesigner, shape = CircleShape,
                    modifier = Modifier.fillMaxWidth().testTag("icon_pack_designer").semantics { this.selected = true },
                    color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Row(Modifier.heightIn(min = 72.dp).padding(horizontal = 24.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        LauncherIcon(LauncherSymbol.DesignServices)
                        Text(stringResource(R.string.icon_designer_title), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            item(key = "selected_heading") { SettingsHeading(stringResource(R.string.icon_pack_enabled)) }
            items(reorder.keys, key = { FavoritesReorderState.itemKey(it) }, contentType = { "selected" }) { packageName ->
                val pack = installed[packageName]
                val dragging = reorder.draggingKey == packageName
                val offset by rememberReorderTranslation(reorder, packageName)
                val background by animateColorAsState(
                    if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceBright,
                    label = "iconPackLift",
                )
                val index = reorder.keys.indexOf(packageName)
                val moveUp = stringResource(R.string.favorites_move_up)
                val moveDown = stringResource(R.string.favorites_move_down)
                val label = pack?.label ?: packageName
                PackChoice(pack, label, checked = true, tag = "icon_pack_selected:$packageName", enabled = enabled,
                    index = index, count = reorder.keys.size + 1, container = background,
                    summary = if (pack == null && !uiState.isLoadingIconPacks && !uiState.iconPacksLoadFailed)
                        stringResource(R.string.icon_pack_missing, label) else null,
                    onToggle = { toggle(packageName) },
                    modifier = Modifier.then(if (dragging) Modifier else Modifier.animateItem())
                        .zIndex(if (dragging || reorder.drop?.key == packageName) 1f else 0f)
                        .graphicsLayer { translationY = offset }
                        .semantics {
                            customActions = buildList {
                                if (index > 0) add(CustomAccessibilityAction(moveUp) { reorder.moveBy(packageName, -1) })
                                if (index < reorder.keys.lastIndex) add(CustomAccessibilityAction(moveDown) { reorder.moveBy(packageName, 1) })
                            }
                        },
                ) {
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }, enabled = LocalSettingsStorageState.current.canEdit,
                            modifier = Modifier.testTag("icon_pack_drag:$packageName").pointerInput(reorder, packageName) {
                                detectDragGestures(
                                    onDragStart = { reorder.start(packageName) }, onDragEnd = { reorder.finish(false) },
                                    onDragCancel = { reorder.finish(true) },
                                ) { change, amount -> change.consume(); reorder.drag(amount.y) }
                            }) {
                            Icon(painterResource(R.drawable.ms_drag_indicator), stringResource(R.string.favorites_reorder, label), Modifier.size(24.dp))
                        }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text(moveUp) }, enabled = index > 0,
                                onClick = { reorder.moveBy(packageName, -1); menu = false })
                            DropdownMenuItem(text = { Text(moveDown) }, enabled = index < reorder.keys.lastIndex,
                                onClick = { reorder.moveBy(packageName, 1); menu = false })
                        }
                    }
                }
            }
            item(key = "system") {
                PackChoice(null, stringResource(R.string.icon_pack_system), checked = true, tag = "icon_pack:system",
                    enabled = false, onToggle = {}, symbol = LauncherSymbol.Apps, index = reorder.keys.size, count = reorder.keys.size + 1)
            }
            item(key = "all_heading") { SettingsHeading(stringResource(R.string.icon_pack_all)) }
            if (uiState.iconPacks.isNotEmpty()) item(key = "search") {
                LauncherSearchBar(query, stringResource(R.string.icon_pack_search), "icon_pack_query")
                Spacer(Modifier.height(12.dp))
            }
            items(filtered, key = { "all:${it.packageName}" }, contentType = { "all" }) { pack ->
                PackChoice(pack, pack.label, pack.packageName in selected, "icon_pack:${pack.packageName}", enabled,
                    index = filtered.indexOf(pack), count = filtered.size,
                    onToggle = { toggle(pack.packageName) }, modifier = Modifier.animateItem())
            }
            if (uiState.isLoadingIconPacks) item(key = "loading") {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 16.dp).testTag("icon_pack_loading"))
            }
            if (uiState.iconPacksLoadFailed) item(key = "failure") {
                Text(stringResource(R.string.icon_pack_load_failed), Modifier.padding(8.dp), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = actions.refreshIconPacks) { Text(stringResource(R.string.retry)) }
            } else if (!uiState.isLoadingIconPacks && filtered.isEmpty()) item(key = "empty") {
                Text(stringResource(if (uiState.iconPacks.isEmpty()) R.string.icon_pack_empty else R.string.search_no_results),
                    Modifier.padding(8.dp).testTag("icon_pack_empty"), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PackChoice(
    pack: IconPackInfo?, label: String, checked: Boolean, tag: String, enabled: Boolean,
    onToggle: () -> Unit, modifier: Modifier = Modifier, summary: String? = null,
    index: Int, count: Int, container: Color = MaterialTheme.colorScheme.surfaceBright,
    symbol: LauncherSymbol = LauncherSymbol.Palette, trailing: @Composable () -> Unit = {},
) {
    val shapes = ListItemDefaults.segmentedShapes(index, count)
    val colors = ListItemDefaults.segmentedColors(containerColor = container)
    SegmentedListItem(
        checked = checked, onCheckedChange = { onToggle() }, enabled = enabled,
        modifier = modifier.testTag(tag),
        shapes = shapes.copy(selectedShape = shapes.shape, pressedShape = shapes.shape),
        colors = colors.copy(selectedContainerColor = colors.containerColor, selectedContentColor = colors.contentColor,
            selectedLeadingContentColor = colors.leadingContentColor, selectedTrailingContentColor = colors.trailingContentColor,
            selectedSupportingContentColor = colors.supportingContentColor),
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Checkbox(checked, null, Modifier.size(24.dp), enabled = enabled)
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    if (pack?.icon != null) Image(pack.icon, null, Modifier.fillMaxSize()) else LauncherIcon(symbol)
                }
            }
        },
        content = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = summary?.let { { Text(it) } }, trailingContent = trailing,
    )
}
