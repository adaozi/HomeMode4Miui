package com.galaxyrio.gracelauncher.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.LauncherApp
import kotlin.math.roundToInt

private data class AppScrollMetrics(
    val firstAppIndex: Int,
    val rowHeight: Float,
    val scrollRange: Float,
    val visibleFraction: Float,
    val progress: Float,
    val ready: Boolean,
)

/** Estimates use app rows only, never the selected items, widget previews or headings above them. */
private fun LazyListState.appScrollMetrics(count: Int, keyPrefix: String): AppScrollMetrics? {
    val info = layoutInfo
    if (count == 0 || info.totalItemsCount < count + 1) return null
    val first = info.totalItemsCount - count
    val rows = info.visibleItemsInfo.filter { it.index >= first && (it.key as? String)?.startsWith(keyPrefix) == true }
    if (rows.isEmpty()) return null
    val rowHeight = rows.sumOf { it.size }.toFloat() / rows.size
    if (rowHeight <= 0f) return null
    val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
    val range = (count * rowHeight + info.afterContentPadding - viewport).coerceAtLeast(0f)
    val firstVisible = rows.first()
    val offset = (firstVisible.index - first) * rowHeight - (firstVisible.offset - info.viewportStartOffset)
    return AppScrollMetrics(
        first, rowHeight, range, (viewport / (count * rowHeight)).coerceIn(0f, 1f),
        if (range > 0f) (offset / range).coerceIn(0f, 1f) else 0f,
        // The search field immediately precedes the app rows. Wait until it reaches the top.
        ready = firstVisibleItemIndex >= first - 1 && range > 0f,
    )
}

@Composable
internal fun AppSelectionScrollbar(
    state: LazyListState,
    apps: List<LauncherApp>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    tag: String,
) = SelectionScrollbar(state, apps.map { it.section }, enabled, modifier, tag)

/** The same bounded list scrubber also serves the font manager. */
@Composable
internal fun SelectionScrollbar(
    state: LazyListState,
    labels: List<String>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    tag: String,
    keyPrefix: String = "all:",
) {
    val metrics by remember(state, labels.size, keyPrefix) { derivedStateOf { state.appScrollMetrics(labels.size, keyPrefix) } }
    val currentMetrics by rememberUpdatedState(metrics)
    var dragProgress by remember(labels) { mutableStateOf<Float?>(null) }
    var dragLetter by remember(labels) { mutableStateOf("") }
    var trackHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val minThumb = with(density) { 36.dp.toPx() }
    val bubbleSize = with(density) { 48.dp.toPx() }
    val thumbHeight = (trackHeight * (metrics?.visibleFraction ?: 1f)).coerceIn(minThumb.coerceAtMost(trackHeight.toFloat()), trackHeight.toFloat())
    val thumbTop = (trackHeight - thumbHeight) * (dragProgress ?: metrics?.progress ?: 0f)
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val thumbColor = MaterialTheme.colorScheme.primary
    val description = stringResource(R.string.app_selection_fast_scroll)

    fun scrollTo(progress: Float, range: AppScrollMetrics) {
        val pixels = progress.coerceIn(0f, 1f) * range.scrollRange
        // Always reach the last app, even if large text makes some rows taller than others.
        val index = if (progress >= 1f) labels.lastIndex else (pixels / range.rowHeight).toInt().coerceIn(0, labels.lastIndex)
        dragLetter = labels[index]
        // Synchronous requests coalesce in the next layout, without queuing scroll coroutines.
        state.requestScrollToItem(range.firstAppIndex + index, if (progress >= 1f) 0 else (pixels - index * range.rowHeight).roundToInt())
    }

    AnimatedVisibility(
        visible = enabled && metrics?.ready == true,
        modifier = modifier.fillMaxHeight(),
        enter = fadeIn() + slideInHorizontally { it },
        exit = fadeOut() + slideOutHorizontally { it },
    ) {
        Box(Modifier.fillMaxHeight().width(88.dp).padding(vertical = 12.dp)) {
            Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(32.dp)
                .onSizeChanged { trackHeight = it.height }
                .testTag(tag)
                .semantics {
                    contentDescription = description
                    progressBarRangeInfo = ProgressBarRangeInfo(dragProgress ?: metrics?.progress ?: 0f, 0f..1f)
                    setProgress { value ->
                        val range = currentMetrics
                        if (range == null || !range.ready) false else { scrollTo(value, range); true }
                    }
                }
                .pointerInput(labels, state) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val range = currentMetrics ?: return@awaitEachGesture
                        val height = size.height.toFloat()
                        val thumb = (height * range.visibleFraction).coerceIn(minThumb.coerceAtMost(height), height)
                        val travel = height - thumb
                        if (travel <= 0f) return@awaitEachGesture
                        val top = travel * range.progress
                        val onThumb = down.position.y in top..(top + thumb)
                        val grab = if (onThumb) down.position.y - top else thumb / 2f
                        fun drag(y: Float) {
                            val progress = ((y - grab) / travel).coerceIn(0f, 1f)
                            dragProgress = progress
                            scrollTo(progress, range)
                        }
                        down.consume()
                        if (onThumb) {
                            dragProgress = range.progress
                            dragLetter = labels[(range.progress * range.scrollRange / range.rowHeight).toInt().coerceIn(0, labels.lastIndex)]
                        } else drag(down.position.y)
                        try {
                            do {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                if (!change.pressed) break
                                drag(change.position.y)
                            } while (true)
                        } finally {
                            dragProgress = null
                        }
                    }
                }) {
                Canvas(Modifier.fillMaxSize()) {
                    drawRoundRect(trackColor, Offset(center.x - 2.dp.toPx(), 0f), Size(4.dp.toPx(), size.height), CornerRadius(2.dp.toPx()))
                    drawRoundRect(thumbColor, Offset(center.x - 3.dp.toPx(), thumbTop), Size(6.dp.toPx(), thumbHeight), CornerRadius(3.dp.toPx()))
                }
            }
            AnimatedVisibility(
                visible = dragProgress != null,
                modifier = Modifier.align(Alignment.TopStart).offset {
                    IntOffset(0, (thumbTop + (thumbHeight - bubbleSize) / 2f).roundToInt().coerceIn(0, (trackHeight - bubbleSize).roundToInt().coerceAtLeast(0)))
                },
                enter = fadeIn(), exit = fadeOut(),
            ) {
                Surface(shape = MaterialTheme.shapes.large, color = thumbColor, contentColor = MaterialTheme.colorScheme.onPrimary) {
                    Box(Modifier.size(48.dp).testTag("${tag}_letter"), contentAlignment = Alignment.Center) {
                        Text(dragLetter, style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
    }
}
