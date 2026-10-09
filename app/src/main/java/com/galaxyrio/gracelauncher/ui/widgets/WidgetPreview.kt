/*
 * 移植自 GraceLauncher v1.1.4（GPL-3.0，https://github.com/Galaxy-rio/GraceLauncher）
 * 逐字拷贝（P4），原路径 ui/widgets/WidgetPreview.kt；widget 预览渲染（无 id 分配/无 bind）。
 */

package com.galaxyrio.gracelauncher.ui.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import com.homemode4miui.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private data class WidgetPreviewData(val layouts: List<RemoteViews>, val image: ImageBitmap?)

/** Preview-only rendering: no widget ID allocation, binding permission, or provider actions. */
@Composable
internal fun WidgetPreview(info: AppWidgetProviderInfo, appIcon: ImageBitmap?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val preview by produceState<WidgetPreviewData?>(null, info, configuration) {
        value = withContext(Dispatchers.IO) { loadPreview(context, info) }
    }
    var layoutFailed by remember(preview) { mutableStateOf(false) }
    Box(modifier, contentAlignment = Alignment.Center) {
        val data = preview
        when {
            data == null -> CircularProgressIndicator(Modifier.size(28.dp))
            data.layouts.isNotEmpty() && !layoutFailed -> key(data) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { PreviewFrame(it, info).apply { if (!show(data.layouts)) layoutFailed = true } },
                    onRelease = { it.removeAllViews() },
                )
            }
            data.image != null -> Image(data.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                WidgetAppIcon(appIcon, Modifier.size(48.dp))
                Text(stringResource(R.string.widget_preview_unavailable), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun loadPreview(context: Context, info: AppWidgetProviderInfo): WidgetPreviewData {
    val layouts = buildList {
        if (Build.VERSION.SDK_INT >= 35) {
            runCatching {
                AppWidgetManager.getInstance(context).getWidgetPreview(info.provider, info.profile, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)
            }.getOrNull()?.let(::add)
        }
        if (Build.VERSION.SDK_INT >= 31 && info.previewLayout != 0) {
            add(RemoteViews(info.provider.packageName, info.previewLayout))
        }
    }
    val image = runCatching {
        info.loadPreviewImage(context, context.resources.displayMetrics.densityDpi)?.let { drawable ->
            val width = drawable.intrinsicWidth.coerceAtLeast(1)
            val height = drawable.intrinsicHeight.coerceAtLeast(1)
            val scale = minOf(1f, 1024f / maxOf(width, height))
            drawable.toBitmap((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1)).asImageBitmap()
        }
    }.getOrNull()
    return WidgetPreviewData(layouts, image)
}

/** Measure at widget dimensions, then fit into the card without cropping or stretching. */
private class PreviewFrame(context: Context, info: AppWidgetProviderInfo) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val naturalWidth = info.minWidth.coerceIn((120 * density).toInt(), (600 * density).toInt())
    private val naturalHeight = info.minHeight.coerceIn((64 * density).toInt(), (800 * density).toInt())

    init {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        clipChildren = false
        clipToPadding = false
    }

    fun show(candidates: List<RemoteViews>): Boolean {
        for (remoteViews in candidates) {
            val child = runCatching { remoteViews.apply(context, this) }.getOrNull() ?: continue
            addView(child)
            return true
        }
        return false
    }

    // The enclosing Compose card owns the tap. Preview buttons must never launch apps.
    override fun dispatchTouchEvent(event: MotionEvent): Boolean = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        getChildAt(0)?.measure(MeasureSpec.makeMeasureSpec(naturalWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(naturalHeight, MeasureSpec.EXACTLY))
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val child = getChildAt(0) ?: return
        val scale = minOf(width.toFloat() / naturalWidth, height.toFloat() / naturalHeight)
        child.layout(0, 0, naturalWidth, naturalHeight)
        child.pivotX = 0f
        child.pivotY = 0f
        child.scaleX = scale
        child.scaleY = scale
        child.translationX = (width - naturalWidth * scale) / 2f
        child.translationY = (height - naturalHeight * scale) / 2f
    }
}
