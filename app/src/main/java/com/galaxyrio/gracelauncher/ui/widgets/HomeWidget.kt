/*
 * 移植自 GraceLauncher v1.1.4（GPL-3.0，https://github.com/Galaxy-rio/GraceLauncher）
 * 原路径：app/src/main/java/com/galaxyrio/gracelauncher/ui/widgets/HomeWidget.kt
 * P4 移植：逐字拷贝，未改动。WidgetHostState/LocalWidgetHost/rememberWidgetHost/
 *   rememberHostedWidget/HomeWidget。生命周期观察走自托管 LifecycleOwner
 *   （GraceHomeView，ON_START→startListening / ON_STOP→stopListening 原语义成立）。
 *   R import 指向 com.homemode4miui.R（模块 namespace）。
 */
package com.galaxyrio.gracelauncher.ui.widgets

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.homemode4miui.R // P4：模块 namespace，R 类随之（原版为 com.galaxyrio.gracelauncher.R）
import com.galaxyrio.gracelauncher.data.HomeLayout
import com.galaxyrio.gracelauncher.platform.HomeWidgetHost
import com.galaxyrio.gracelauncher.platform.HomeWidgetHostView
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.roundToInt

internal data class HostedWidget(
    val host: HomeWidgetHost,
    val info: AppWidgetProviderInfo?,
    val defaultHeight: Int,
    val minHeight: Int,
    val maxHeight: Int,
)

@Stable
internal class WidgetHostState(val host: HomeWidgetHost) {
    var revision by mutableIntStateOf(0)
}

internal val LocalWidgetHost = staticCompositionLocalOf<WidgetHostState?> { null }

/** Only one listener per Activity/host ID, shared by HOME and pop-up widgets. */
@Composable
internal fun rememberWidgetHost(): WidgetHostState {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state = remember(context) { WidgetHostState(HomeWidgetHost(context)) }
    val host = state.host
    DisposableEffect(host, lifecycle) {
        fun start() { state.revision++; runCatching { host.startListening() } }
        host.onProvidersUpdated = { state.revision++ }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> start()
                Lifecycle.Event.ON_STOP -> runCatching { host.stopListening() }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        onDispose { lifecycle.removeObserver(observer); host.onProvidersUpdated = null; runCatching { host.stopListening() } }
    }
    return state
}

@Composable
internal fun rememberHostedWidget(layout: HomeLayout): HostedWidget {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val state = LocalWidgetHost.current ?: rememberWidgetHost()
    val host = state.host
    val info = remember(layout.widgetId, layout.widgetProvider, state.revision) {
        runCatching { AppWidgetManager.getInstance(context).getAppWidgetInfo(layout.widgetId) }
            .getOrNull()?.takeIf { it.provider.flattenToString() == layout.widgetProvider }
    }
    return remember(host, info, density) {
        val padding = info?.let { AppWidgetHostView.getDefaultPaddingForWidget(context, it.provider, null) }
        val extra = (padding?.top ?: 0) + (padding?.bottom ?: 0)
        val default = info?.let { ceil((it.minHeight + extra) / density).toInt().coerceIn(48, 1600) } ?: 160
        val min = info?.takeIf { it.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0 && it.minResizeHeight > 0 }
            ?.let { ceil((it.minResizeHeight + extra) / density).toInt().coerceIn(48, default) } ?: default
        val max = if (Build.VERSION.SDK_INT >= 31 && info != null && info.maxResizeHeight > 0) {
            ceil((info.maxResizeHeight + extra) / density).toInt().coerceIn(default, 1600)
        } else 1600
        HostedWidget(host, info, default, min, max)
    }
}

@Composable
internal fun HomeWidget(
    layout: HomeLayout, widget: HostedWidget, height: Int, editing: Boolean,
    enabled: Boolean, hapticsEnabled: Boolean, onLongPress: () -> Unit,
    // 上游同步 1.2.0（widget 尺寸/拖拽）：不传则完全保持旧行为（长按弹菜单）
    onDrag: ((Float, Float) -> Unit)? = null, onDragEnd: ((Boolean) -> Unit)? = null,
    onDragStart: ((Float, Float) -> Unit)? = null,
    contentScale: Float = 1f,
    removeHorizontalPadding: Boolean = false,
) {
    val density = LocalDensity.current.density
    val latestLongPress by rememberUpdatedState(onLongPress)
    val latestDragStart by rememberUpdatedState(onDragStart)
    val latestDrag = rememberUpdatedState(onDrag)
    val latestDragEnd by rememberUpdatedState(onDragEnd)
    var size by remember { mutableStateOf(IntSize.Zero) }
    var view by remember(layout.widgetId) { mutableStateOf<HomeWidgetHostView?>(null) }
    var creationFailed by remember(layout.widgetId, widget.info) { mutableStateOf(false) }
    val scale = contentScale.coerceIn(.5f, 1.5f)
    // 槽位尺寸不变：RemoteViews 按逆缩放测量后整体缩放绘制（含触摸坐标），
    // scale=1 时与旧的 fillMaxWidth+height 等价。
    val modifier = Modifier.fillMaxWidth().height(height.dp).clipToBounds()
        .layout { measurable, constraints ->
            val width = constraints.maxWidth
            val outerHeight = constraints.maxHeight
            val child = measurable.measure(Constraints.fixed(
                (width / scale).roundToInt().coerceAtLeast(1),
                (outerHeight / scale).roundToInt().coerceAtLeast(1)))
            layout(width, outerHeight) {
                child.placeWithLayer(0, 0) {
                    scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0f)
                }
            }
        }.onSizeChanged { size = it }
    if (widget.info == null || creationFailed) {
        Surface(modifier.combinedClickable(enabled = enabled && !editing, onClick = onLongPress, onLongClick = onLongPress),
            shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
            Text(stringResource(R.string.widget_unavailable), Modifier.padding(20.dp))
        }
    } else key(layout.widgetId, widget.info.provider) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                // A provider may be removed/updated between lookup and creation.
                runCatching { widget.host.createView(context, layout.widgetId, widget.info) as HomeWidgetHostView }
                    .getOrElse { creationFailed = true; HomeWidgetHostView(context) }
                    .also { view = it }
            },
            update = {
                it.onWidgetLongPress = { latestLongPress() }
                // 只有调用方给了拖拽槽才接管长按后的触摸流；否则保持"长按弹菜单"
                val dragAware = onDrag != null
                it.onWidgetDragStart = if (dragAware) { x, y -> latestDragStart?.invoke(x, y) } else null
                it.onWidgetDrag = if (dragAware) { dx, dy -> latestDrag.value?.invoke(dx, dy) } else null
                it.onWidgetDragEnd = if (dragAware) { cancelled -> latestDragEnd?.invoke(cancelled) } else null
                it.interactionEnabled = enabled && !editing
                it.hapticsEnabled = hapticsEnabled
                // 上游同步 1.2.0：全宽呈现时去掉 providers 默认左右内边距
                val padding = AppWidgetHostView.getDefaultPaddingForWidget(it.context, widget.info.provider, null)
                if (removeHorizontalPadding) { padding.left = 0; padding.right = 0 }
                if (it.paddingLeft != padding.left || it.paddingTop != padding.top ||
                    it.paddingRight != padding.right || it.paddingBottom != padding.bottom) {
                    it.setPadding(padding.left, padding.top, padding.right, padding.bottom)
                }
            },
            onRelease = {
                it.onWidgetLongPress = null
                it.onWidgetDragStart = null; it.onWidgetDrag = null; it.onWidgetDragEnd = null
                if (view === it) view = null
            },
        )
    }
    LaunchedEffect(view, size, editing, density) {
        if (size.width == 0 || size.height == 0) return@LaunchedEffect
        // Avoid flooding providers with RemoteViews rebuilds on every drag frame.
        if (editing) delay(100)
        val widthDp = size.width / density
        val heightDp = size.height / density
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) view?.updateAppWidgetSize(Bundle(), listOf(SizeF(widthDp, heightDp)))
            else {
                @Suppress("DEPRECATION")
                view?.updateAppWidgetSize(Bundle(), widthDp.toInt(), heightDp.toInt(), widthDp.toInt(), heightDp.toInt())
            }
        }
    }
}
