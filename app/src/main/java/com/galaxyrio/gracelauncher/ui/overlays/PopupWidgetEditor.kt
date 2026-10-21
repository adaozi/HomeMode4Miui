package com.galaxyrio.gracelauncher.ui.overlays

import android.appwidget.AppWidgetHostView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.HomeLayout
import com.galaxyrio.gracelauncher.ui.components.LauncherIcon
import com.galaxyrio.gracelauncher.ui.components.LauncherSymbol
import com.galaxyrio.gracelauncher.ui.widgets.HomeWidget
import com.galaxyrio.gracelauncher.ui.widgets.rememberHostedWidget
import kotlin.math.roundToInt

@Composable
internal fun PopupWidgetControls(
    layout: HomeLayout, onChange: (HomeLayout, Boolean) -> Unit, onDone: () -> Unit,
) {
    val done = stringResource(R.string.done)
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("popup_widget_controls")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(layout.popupFullWidth, onClick = { onChange(layout.copy(popupFullWidth = !layout.popupFullWidth), true) },
                label = { Text(stringResource(R.string.popup_widget_full_width), maxLines = 1) },
                modifier = Modifier.weight(1f).testTag("popup_widget_full_width"))
            FilterChip(layout.popupBackground, onClick = { onChange(layout.copy(popupBackground = !layout.popupBackground), true) },
                label = { Text(stringResource(R.string.popup_widget_background), maxLines = 1) },
                modifier = Modifier.weight(1f).testTag("popup_widget_background"))
            IconButton(onClick = onDone, modifier = Modifier.size(40.dp).testTag("popup_widget_done")
                .semantics { contentDescription = done }) { LauncherIcon(LauncherSymbol.Check) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.popup_widget_content_scale, layout.popupContentScale),
                modifier = Modifier.widthIn(max = 112.dp), style = MaterialTheme.typography.labelMedium)
            Slider(value = layout.popupContentScale.toFloat(),
                onValueChange = { onChange(layout.copy(popupContentScale = it.roundToInt()), false) },
                onValueChangeFinished = { onChange(layout, true) }, valueRange = 50f..150f,
                modifier = Modifier.weight(1f).testTag("popup_widget_scale"))
        }
    }
}

/** A pop-up is a vertical list, so width is centered and height changes the panel's measured size. */
@Composable
internal fun PopupWidgetItem(
    layout: HomeLayout, selected: Boolean, enabled: Boolean, haptics: Boolean,
    maxHeight: Int, onChange: (HomeLayout, Boolean) -> Unit,
    onDragStart: () -> Unit, onDrag: (Float) -> Unit, onDragEnd: (Boolean) -> Unit,
    onResizing: (Boolean) -> Unit,
    horizontalInset: Dp = 18.dp,
) {
    val hosted = rememberHostedWidget(layout)
    val density = LocalDensity.current.density
    val context = LocalContext.current
    val currentLayout by rememberUpdatedState(layout)
    val currentChange by rememberUpdatedState(onChange)
    val currentStart by rememberUpdatedState(onDragStart)
    val currentDrag by rememberUpdatedState(onDrag)
    val currentEnd by rememberUpdatedState(onDragEnd)
    val currentResizing by rememberUpdatedState(onResizing)
    val label = stringResource(R.string.widget_resize)
    val color = MaterialTheme.colorScheme.primary
    val padding = remember(hosted.info, context) {
        hosted.info?.let { AppWidgetHostView.getDefaultPaddingForWidget(context, it.provider, null) }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 8.dp),
        contentAlignment = Alignment.Center) {
        val available = (maxWidth.value - horizontalInset.value * 2).coerceAtLeast(1f)
        val rawMinWidth = hosted.info?.let {
            ((it.minResizeWidth.takeIf { n -> n > 0 } ?: it.minWidth) +
                (padding?.left ?: 0) + (padding?.right ?: 0)) / density
        } ?: 64f
        // 模块差异（S5b hotfix3）：provider 的最小宽度常常已经很接近可用宽度（固定尺寸
        // widget 尤其如此），直接 coerceIn(minWidth, available) 会把宽度区间压成 0 →
        // 左右手柄拖了没反应（用户实锤）。这里把下限封顶到可用宽度的一半，保证至少能缩一半。
        val minWidth = rawMinWidth.coerceIn(
            64f.coerceAtMost(available),
            (available * 0.5f).coerceAtLeast(64f.coerceAtMost(available)))
        val width = if (layout.popupFullWidth) maxWidth.value else
            (available * layout.popupWidthPercent.coerceIn(20, 100) / 100f).coerceIn(minWidth, available)
        // 模块差异（S5b hotfix）：容器尺寸与 provider 的 resizeMode/maxHeight 解耦 ——
        // 本模块调整的是"弹层里的 widget 槽"，不是向 provider 申请 resize；固定尺寸 provider
        // 若按 maxHeight 卡死会导致四边手柄拖不动（装机实锤）。内容适配交给 contentScale。
        val limitHeight = maxHeight.coerceIn(48, 1600)
        val minHeight = 64.coerceAtMost(limitHeight)
        val height = (layout.widgetHeightDp.takeIf { it > 0 } ?: hosted.defaultHeight).coerceIn(minHeight, limitHeight)
        val currentWidth by rememberUpdatedState(width)
        val currentHeight by rememberUpdatedState(height)
        var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
        Box(Modifier.width(width.dp).height(height.dp).testTag("popup_widget:${layout.widgetId}")
            .onGloballyPositioned { coordinates = it }) {
            Box(Modifier.matchParentSize().clip(RoundedCornerShape(16.dp))
                .background(if (layout.popupBackground) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)) {
                HomeWidget(layout, hosted, height, editing = selected, enabled = enabled,
                    hapticsEnabled = haptics, onLongPress = {},
                    onDragStart = { _, _ -> currentStart() },
                    onDrag = { _, y -> currentDrag(y) }, onDragEnd = { currentEnd(it) },
                    contentScale = layout.popupContentScale / 100f, removeHorizontalPadding = layout.popupFullWidth)
            }
            if (selected) {
                // The native child is disabled while selected. Only this widget's
                // body gets a held-drag recognizer; app rows retain their details action.
                Box(Modifier.matchParentSize().pointerInput(enabled) {
                    if (enabled) {
                        var previous = Offset.Zero
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                previous = coordinates?.localToRoot(it) ?: it
                                currentStart()
                            },
                            onDragEnd = { currentEnd(false) }, onDragCancel = { currentEnd(true) },
                        ) { change, _ ->
                            val root = coordinates?.localToRoot(change.position) ?: change.position
                            change.consume()
                            currentDrag(root.y - previous.y)
                            previous = root
                        }
                    }
                })
                Box(Modifier.matchParentSize().border(2.dp, color, RoundedCornerShape(16.dp)))
                // 模块差异（S5b hotfix）：纵向恒可调；横向只看是否全宽（原判定依赖
                // provider resizeMode，固定尺寸的 widget 会一个手柄都不给，用户无法调整）。
                val horizontal = !layout.popupFullWidth
                val vertical = true
                val edges = buildList {
                    if (horizontal) { add("left" to Alignment.CenterStart); add("right" to Alignment.CenterEnd) }
                    if (vertical) { add("top" to Alignment.TopCenter); add("bottom" to Alignment.BottomCenter) }
                }
                for ((edge, alignment) in edges) key(edge) {
                    var handle by remember { mutableStateOf<LayoutCoordinates?>(null) }
                    Box(Modifier.align(alignment).size(40.dp)
                        .onGloballyPositioned { handle = it }
                        .testTag("popup_widget_resize:$edge").semantics { contentDescription = label }
                        .pointerInput(enabled, minWidth, available, minHeight, limitHeight) {
                            if (enabled) awaitEachGesture {
                                // 模块差异（S5b hotfix2）：改走 Main pass（子→父）。
                                // 原上游用 Initial pass（父→子），在本模块的弹层容器
                                // （SwipeRevealPanel + LazyColumn）里事件会被父级先行消费，
                                // 手柄收不到移动 → 装机实锤"拖不动"。
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                com.homemode4miui.util.ModLog.i("popup resize handle down: $edge")
                                val original = currentLayout
                                var draft = original
                                var w = currentWidth
                                var h = currentHeight.toFloat()
                                var previous = handle?.localToRoot(down.position) ?: down.position
                                var complete = false
                                currentResizing(true)
                                try {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (event.changes.count { it.pressed } > 1) break
                                        val root = handle?.localToRoot(change.position) ?: change.position
                                        val delta = (root - previous) / density
                                        previous = root
                                        if (edge == "left" || edge == "right") {
                                            // Centered width: both edges travel half the total size change.
                                            w = (w + delta.x * if (edge == "left") -2f else 2f).coerceIn(minWidth, available)
                                            draft = draft.copy(popupWidthPercent = (w / available * 100).roundToInt().coerceIn(20, 100))
                                        } else {
                                            h = (h + delta.y * if (edge == "top") -1f else 1f).coerceIn(minHeight.toFloat(), limitHeight.toFloat())
                                            draft = draft.copy(widgetHeightDp = h.roundToInt())
                                        }
                                        change.consume()
                                        currentChange(draft, false)
                                        if (!change.pressed) { complete = true; break }
                                    }
                                } finally {
                                    currentChange(if (complete) draft else original, complete)
                                    currentResizing(false)
                                }
                            }
                        }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size(20.dp).background(color, CircleShape)
                            .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape))
                    }
                }
            }
        }
    }
}
