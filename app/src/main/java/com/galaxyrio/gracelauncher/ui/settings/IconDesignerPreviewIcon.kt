package com.galaxyrio.gracelauncher.ui.settings

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.InsetDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.IconDesign
import com.galaxyrio.gracelauncher.data.ItemIcon
import com.galaxyrio.gracelauncher.data.LauncherApp
import com.galaxyrio.gracelauncher.data.icons.IconLayers
import com.galaxyrio.gracelauncher.data.icons.ItemIconStore
import com.galaxyrio.gracelauncher.data.icons.iconLayers
import com.galaxyrio.gracelauncher.data.icons.renderDesignedIcon
import com.galaxyrio.gracelauncher.ui.LauncherUiState
import com.galaxyrio.gracelauncher.ui.components.AppIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
internal fun DesignerPreviewIcon(app: LauncherApp, layers: IconLayers?, design: IconDesign, dynamicColors: Pair<Int, Int>,
    size: Dp, modifier: Modifier = Modifier, draggable: Boolean = false, themeColors: Pair<Int, Int> = dynamicColors,
    onChange: (IconDesign) -> Unit = {}) {
    val bitmap by produceState<ImageBitmap?>(layers?.original?.asImageBitmap(), layers, design, dynamicColors, themeColors) {
        value = layers?.let { withContext(Dispatchers.Default) { renderDesignedIcon(it, design, dynamicColors.first, dynamicColors.second,
            themeColors.first, themeColors.second).asImageBitmap() } }
    }
    val currentDesign by rememberUpdatedState(design)
    val changeDesign by rememberUpdatedState(onChange)
    val description = if (draggable) stringResource(R.string.icon_designer_drag_symbol, app.label) else app.label
    Box(modifier.size(size).semantics { contentDescription = description }
        .then(if (!draggable || layers == null) Modifier else Modifier.pointerInput(layers) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // Keep fractional scale and the latest pose even before recomposition.
                var style = currentDesign.normalized()
                var symbolSize = style.size.toFloat()
                val symbolScale = symbolSize / 100f * if (style.addTray && !layers.layered) .8f else 1f
                val symbol = if (style.themeIcons && style.foreground != null) layers.monochrome ?: layers.foreground else layers.foreground
                val point = Offset(down.position.x / this.size.width - .5f - style.x / 100f,
                    down.position.y / this.size.height - .5f - style.y / 100f).rotated(-style.rotation) / symbolScale + Offset(.5f, .5f)
                var canTransform = point.x in 0f..<1f && point.y in 0f..<1f &&
                    AndroidColor.alpha(symbol.getPixel((point.x * symbol.width).toInt(), (point.y * symbol.height).toInt())) > 24
                var pastSlop = false
                var zoomMotion = 1f
                var rotationMotion = 0f
                var panMotion = Offset.Zero
                do {
                    val event = awaitPointerEvent()
                    if (event.changes.any { it.isConsumed }) break
                    // Pinching thin symbols need not start with both fingers on ink.
                    canTransform = canTransform || event.changes.count { it.pressed } > 1
                    val zoom = event.calculateZoom()
                    val rotation = event.calculateRotation()
                    val pan = event.calculatePan()
                    if (canTransform && !pastSlop) {
                        zoomMotion *= zoom
                        rotationMotion += rotation
                        panMotion += pan
                        val radius = event.calculateCentroidSize(useCurrent = false)
                        pastSlop = abs(1f - zoomMotion) * radius > viewConfiguration.touchSlop ||
                            abs(rotationMotion) * Math.PI.toFloat() / 180f * radius > viewConfiguration.touchSlop ||
                            panMotion.getDistance() > viewConfiguration.touchSlop
                    }
                    if (pastSlop) {
                        val nextSize = (symbolSize * zoom).coerceIn(25f, 200f)
                        val centroid = event.calculateCentroid(useCurrent = false)
                        if (centroid != Offset.Unspecified) {
                            val center = Offset(this.size.width * (.5f + style.x / 100f), this.size.height * (.5f + style.y / 100f))
                            val moved = centroid + (center - centroid).rotated(rotation) * (nextSize / symbolSize) + pan
                            style = style.copy(
                                x = (moved.x / this.size.width - .5f) * 100f,
                                y = (moved.y / this.size.height - .5f) * 100f,
                                size = nextSize.roundToInt(),
                                rotation = ((style.rotation + rotation + 180f) % 360f + 360f) % 360f - 180f,
                            ).normalized()
                            symbolSize = nextSize
                            changeDesign(style)
                        }
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            }
        }), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!, null, Modifier.fillMaxSize()) else AppIcon(app, size = size, applyDisplaySize = false)
    }
}

private fun Offset.rotated(degrees: Float): Offset {
    val radians = degrees * Math.PI.toFloat() / 180f
    val cosine = cos(radians)
    val sine = sin(radians)
    return Offset(x * cosine - y * sine, x * sine + y * cosine)
}

@Composable
internal fun DesignerBulkPreviewIcon(app: LauncherApp, uiState: LauncherUiState, choice: ItemIcon,
    store: ItemIconStore, dynamicColors: Pair<Int, Int>, themeColors: Pair<Int, Int>, size: Dp) {
    val single = uiState.itemIcons[app.key]
    val source = single ?: ItemIcon.Theme
    val shared = choice.design ?: IconDesign.defaults(uiState.themedIcons)
    val design = single?.design?.withThemeDefaults(shared) ?: shared
    val layers by produceState<IconLayers?>(null, app.key, source.kind, source.source, source.name,
        uiState.settings.enabledIconPackPackages) {
        value = store.layers(app, source, uiState.settings)
    }
    DesignerPreviewIcon(app, layers, design, dynamicColors, size, themeColors = themeColors)
}

@Composable
internal fun DesignerSamplePreviewIcon(row: Int, choice: ItemIcon,
    dynamicColors: Pair<Int, Int>, themeColors: Pair<Int, Int>, size: Dp) {
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    val original = remember(resources, configuration, row, dynamicColors) {
        val symbols = listOf(R.drawable.ms_schedule, R.drawable.ms_sunny, R.drawable.ms_hourglass_empty)
        val glyph = requireNotNull(resources.getDrawable(symbols[row], null)).mutate()
        glyph.setTint(if (row == 0) dynamicColors.second else 0xFF24354C.toInt())
        val background = if (row == 0) dynamicColors.first else 0xFFDBEBFA.toInt()
        if (row < 2) iconLayers(AdaptiveIconDrawable(ColorDrawable(background), InsetDrawable(glyph, .30f)), 384, themed = true)
        else {
            val bitmap = Bitmap.createBitmap(384, 384, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawRoundRect(0f, 0f, 384f, 384f, 48f, 48f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background })
            glyph.setBounds(76, 76, 308, 308); glyph.draw(canvas)
            IconLayers(bitmap)
        }
    }
    val app = remember(original, row) {
        LauncherApp(ComponentName("designer.sample$row", "Preview"), "", original.original.asImageBitmap())
    }
    DesignerPreviewIcon(app, original, choice.design ?: IconDesign.defaults(), dynamicColors, size, themeColors = themeColors)
}
