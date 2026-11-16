@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.galaxyrio.gracelauncher.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.ClockRegion
import com.galaxyrio.gracelauncher.ui.overlays.FavoritesReorderState
import com.galaxyrio.gracelauncher.ui.overlays.rememberReorderTranslation

/** All regions fit in this list; use the same handles and drop behavior as app sorting. */
@Composable
internal fun ClockRegionOrder(
    order: List<ClockRegion>,
    enabled: Boolean,
    hapticsEnabled: Boolean,
    onChange: (List<ClockRegion>) -> Unit,
) {
    val list = rememberLazyListState()
    val commit by rememberUpdatedState(onChange)
    val haptics by rememberUpdatedState(LocalHapticFeedback.current)
    val feedbackEnabled by rememberUpdatedState(hapticsEnabled)
    val keys = order.map { it.name }
    val reorder = remember(list) {
        FavoritesReorderState(list, keys, { saved -> commit(saved.map { ClockRegion.valueOf(it) }) }) {
            if (feedbackEnabled) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
    LaunchedEffect(keys) { reorder.synchronize(keys) }
    LazyColumn(
        modifier = Modifier.fillMaxWidth().height(64.dp * order.size + 2.dp * (order.size - 1).coerceAtLeast(0))
            .testTag("clock_region_order_list"),
        state = list,
        userScrollEnabled = false,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(reorder.keys, key = { FavoritesReorderState.itemKey(it) }) { key ->
            val region = ClockRegion.valueOf(key)
            val index = reorder.keys.indexOf(key)
            val label = region.label()
            val up = stringResource(R.string.favorites_move_up)
            val down = stringResource(R.string.favorites_move_down)
            val dragging = reorder.draggingKey == key
            val offset by rememberReorderTranslation(reorder, key)
            val background by animateColorAsState(
                if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
                label = "clockRegionLift",
            )
            Row(
                modifier = Modifier.then(if (dragging) Modifier else Modifier.animateItem())
                    .zIndex(if (dragging || reorder.drop?.key == key) 1f else 0f)
                    .graphicsLayer { translationY = offset }
                    .fillMaxWidth().height(64.dp)
                    .clip(ListItemDefaults.segmentedShapes(index, reorder.keys.size).shape).background(background)
                    .testTag("clock_region_order:$key")
                    .semantics {
                        customActions = buildList {
                            if (enabled && index > 0) add(CustomAccessibilityAction(up) { reorder.moveBy(key, -1) })
                            if (enabled && index < reorder.keys.lastIndex) add(CustomAccessibilityAction(down) { reorder.moveBy(key, 1) })
                        }
                    }.padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .38f))
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(
                        onClick = { menu = true },
                        enabled = enabled,
                        modifier = Modifier.testTag("clock_region_drag:$key")
                            .pointerInput(reorder, key, enabled) {
                                if (enabled) detectDragGestures(
                                    onDragStart = { reorder.start(key) },
                                    onDragEnd = { reorder.finish(false) },
                                    onDragCancel = { reorder.finish(true) },
                                ) { change, amount -> change.consume(); reorder.drag(amount.y) }
                            },
                    ) {
                        Icon(painterResource(R.drawable.ms_drag_indicator),
                            stringResource(R.string.favorites_reorder, label), Modifier.size(24.dp))
                    }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(up) }, enabled = enabled && index > 0,
                            onClick = { reorder.moveBy(key, -1); menu = false })
                        DropdownMenuItem(text = { Text(down) }, enabled = enabled && index < reorder.keys.lastIndex,
                            onClick = { reorder.moveBy(key, 1); menu = false })
                    }
                }
            }
        }
    }
}
