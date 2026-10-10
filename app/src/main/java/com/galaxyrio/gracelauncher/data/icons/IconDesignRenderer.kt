@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.galaxyrio.gracelauncher.data.icons

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.material3.MaterialShapes
import androidx.core.graphics.createBitmap
import androidx.graphics.shapes.toPath
import com.galaxyrio.gracelauncher.data.IconDesign
import com.galaxyrio.gracelauncher.data.IconShape
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** The same original layers and renderer are used by the live draft and saved launcher icons. */
internal class IconLayers(
    val original: Bitmap,
    val background: Bitmap? = null,
    val foreground: Bitmap = original,
    val monochrome: Bitmap? = null,
    val originalMask: Path? = null,
) {
    val layered: Boolean get() = background != null
    private var tintedKey: Triple<Int, Boolean, Boolean>? = null
    private var tinted: Bitmap? = null
    private val normalizedMonochrome by lazy { normalizedMonochrome(foreground) }
    private val invertedMonochrome by lazy { normalizedMonochrome(foreground, invert = true) }

    @Synchronized
    fun symbol(foregroundColor: Int?, themeUnsupported: Boolean, invertBackgroundDetection: Boolean = false): Bitmap {
        if (foregroundColor == null || (monochrome == null && !themeUnsupported)) return foreground
        // Adaptive artwork already supplies the symbol's coverage. Luminance
        // extraction (and inversion) is only needed for flattened images.
        val invert = !layered && monochrome == null && invertBackgroundDetection
        val key = Triple(foregroundColor, themeUnsupported, invert)
        if (tintedKey == key) tinted?.let { return it }
        val result = createBitmap(original.width, original.height).also {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = PorterDuffColorFilter(foregroundColor, PorterDuff.Mode.SRC_IN)
            }
            val mask = monochrome ?: when {
                layered -> foreground
                invert -> invertedMonochrome
                else -> normalizedMonochrome
            }
            Canvas(it).drawBitmap(mask, 0f, 0f, paint)
        }
        tintedKey = key
        tinted = result
        return result
    }
}

internal fun iconLayers(drawable: Drawable, size: Int, themed: Boolean = false): IconLayers {
    val original = renderIcon(drawable, size)
    if (drawable !is AdaptiveIconDrawable) return IconLayers(original, monochrome = original.takeIf { themed })
    // Platform/OEM wrappers may be adaptive drawables with only one populated layer.
    // Keep their complete artwork instead of treating a missing layer as a drawable.
    val foregroundDrawable = drawable.foreground ?: return IconLayers(original)
    val backgroundDrawable = drawable.background ?: return IconLayers(original)
    val previous = Rect(drawable.bounds)
    try {
        drawable.setBounds(0, 0, size, size)
        // AdaptiveIconDrawable sets expanded child bounds, preserving Android's safe zone.
        fun layer(child: Drawable): Bitmap = createBitmap(size, size).also { child.draw(Canvas(it)) }
        val mono = if (Build.VERSION.SDK_INT >= 33) drawable.monochrome?.let {
            it.bounds = foregroundDrawable.bounds
            layer(it).takeIf(::hasVisibleSymbol)
        } else null
        val foreground = layer(foregroundDrawable)
        return IconLayers(original, layer(backgroundDrawable), foreground, mono ?: foreground.takeIf { themed }, Path(drawable.iconMask))
    } finally { drawable.bounds = previous }
}

internal fun renderDesignedIcon(layers: IconLayers, design: IconDesign, dynamicBackground: Int, dynamicForeground: Int,
    themeBackground: Int = dynamicBackground, themeForeground: Int = dynamicForeground): Bitmap {
    val style = design.normalized()
    if (style == IconDesign()) return layers.original
    val side = layers.original.width
    val output = createBitmap(side, side)
    val canvas = Canvas(output)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val mask = if (style.shape == IconShape.None) layers.originalMask ?: if (style.addTray)
        iconShapePath(IconShape.Circle, size = side.toFloat()) else null
        else iconShapePath(style.shape, style.cookieSides, side.toFloat(), style.pebbleRoundness, style.squareCornerRadius)
    mask?.let(canvas::clipPath)
    val canTheme = style.themeIcons && (layers.monochrome != null || style.themeUnsupportedIcons)
    val background = style.background?.resolve(dynamicBackground, themeBackground)
    val foreground = style.foreground?.resolve(dynamicForeground, themeForeground)?.takeIf { canTheme }
    if (layers.layered) {
        if (canTheme && background != null) canvas.drawColor(background)
        else layers.background?.let { canvas.drawBitmap(it, 0f, 0f, paint) }
    } else if (style.addTray) {
        background?.let(canvas::drawColor)
    } else if (canTheme && layers.monochrome != null && background != null) {
        // A flat monochrome pack still has a themeable symbol and needs its theme tray.
        if (style.shape == IconShape.None) canvas.clipPath(iconShapePath(IconShape.Circle, size = side.toFloat()))
        canvas.drawColor(background)
    }
    val scale = style.size / 100f * if (style.addTray && !layers.layered) .8f else 1f
    val centerX = side * (0.5f + style.x / 100f)
    val centerY = side * (0.5f + style.y / 100f)
    val half = side * scale / 2f
    canvas.save()
    canvas.rotate(style.rotation, centerX, centerY)
    canvas.drawBitmap(layers.symbol(foreground, style.themeUnsupportedIcons, style.invertBackgroundDetection), null,
        RectF(centerX - half, centerY - half, centerX + half, centerY + half), paint)
    canvas.restore()
    return output
}

private fun hasVisibleSymbol(bitmap: Bitmap): Boolean {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    return pixels.count { Color.alpha(it) > 32 } >= pixels.size / 100
}

/** Extract dark ink from a flat image; invert selects light ink instead. */
internal fun normalizedMonochrome(bitmap: Bitmap, invert: Boolean = false): Bitmap {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    return Bitmap.createBitmap(monochromeMaskPixels(pixels, invert), bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
}

/** ARGB-in/alpha-mask-out, kept independent of Bitmap for pixel-level regression tests. */
internal fun monochromeMaskPixels(pixels: IntArray, invert: Boolean = false): IntArray {
    val peakAlpha = pixels.maxOfOrNull { it ushr 24 } ?: 0
    if (peakAlpha == 0) return IntArray(pixels.size)
    fun luminance(color: Int): Float = .2126f * ((color ushr 16) and 255) +
        .7152f * ((color ushr 8) and 255) + .0722f * (color and 255)
    var low = 255f
    var high = 0f
    // Unpremultiplying almost-transparent edge pixels can produce extreme RGB
    // values. Using them as endpoints leaves the real background partly opaque.
    // Most-opaque samples provide stable endpoints, also for translucent images.
    for (color in pixels) if (color ushr 24 == peakAlpha) {
        val gray = luminance(color)
        low = minOf(low, gray); high = maxOf(high, gray)
    }
    val span = high - low
    return IntArray(pixels.size) { index ->
        val color = pixels[index]
        // With no contrast to separate, preserve the source silhouette. Keep
        // continuous coverage for antialiasing; do not threshold the symbol edge.
        val coverage = if (span <= .001f) 1f else {
            val light = ((luminance(color) - low) / span).coerceIn(0f, 1f)
            if (invert) light else 1f - light
        }
        val alpha = ((color ushr 24) * coverage).roundToInt().coerceIn(0, 255)
        if (alpha == 0) 0 else (alpha shl 24) or 0x00ffffff
    }
}

/** The superellipse exponent moves from 6 (boxy) to 2 (circular). */
internal fun pebbleShapePower(roundness: Int): Double = 2.0 / (6.0 - roundness.coerceIn(0, 100) / 25.0)

/** One parameterized mask for rendered icons, picker swatches and the Grace button surface. */
internal fun iconShapePath(shape: IconShape, sides: Int = 4, size: Float = 1f,
    pebbleRoundness: Int = IconDesign.DefaultPebbleRoundness, squareCornerRadius: Int = 0): Path {
    val path = when (shape) {
        IconShape.Gem -> MaterialShapes.Gem.toPath()
        IconShape.Cookie -> when (sides) {
            6 -> MaterialShapes.Cookie6Sided; 7 -> MaterialShapes.Cookie7Sided
            9 -> MaterialShapes.Cookie9Sided; 12 -> MaterialShapes.Cookie12Sided
            else -> MaterialShapes.Cookie4Sided
        }.toPath()
        IconShape.Pebble -> Path().apply {
            val power = pebbleShapePower(pebbleRoundness)
            repeat(128) { index ->
                val angle = index * Math.PI * 2 / 128
                fun squircle(value: Double) = (abs(value).pow(power) * if (value < 0) -1 else 1).toFloat()
                val x = .5f + .5f * squircle(cos(angle))
                val y = .5f + .5f * squircle(sin(angle))
                if (index == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        IconShape.Square -> Path().apply {
            val radius = squareCornerRadius.coerceIn(0, 100) / 200f
            addRoundRect(0f, 0f, 1f, 1f, radius, radius, Path.Direction.CW)
        }
        IconShape.None, IconShape.Circle -> Path().apply { addCircle(.5f, .5f, .5f, Path.Direction.CW) }
    }
    path.transform(Matrix().apply { setScale(size, size) })
    return path
}
