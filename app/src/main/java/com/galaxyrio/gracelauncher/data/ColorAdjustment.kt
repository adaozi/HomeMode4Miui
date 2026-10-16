package com.galaxyrio.gracelauncher.data

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Adjust the current source, never the previous output. Hue wraps around the
 * wheel; saturation and HSV brightness add percentage points and clamp to 0–1.
 * Pure arithmetic keeps live previews cheap and makes the persisted behavior
 * independent of Android's Color implementation. Alpha is left untouched.
 */
internal fun adjustHsb(argb: Int, hueShift: Int, saturationShift: Int, brightnessShift: Int): Int {
    if (hueShift == 0 && saturationShift == 0 && brightnessShift == 0) return argb
    val r = (argb ushr 16 and 255) / 255f
    val g = (argb ushr 8 and 255) / 255f
    val b = (argb and 255) / 255f
    val max = maxOf(r, g, b)
    val span = max - minOf(r, g, b)
    val sourceHue = when {
        span == 0f -> 0f
        max == r -> 60f * ((g - b) / span)
        max == g -> 60f * ((b - r) / span + 2f)
        else -> 60f * ((r - g) / span + 4f)
    }
    val h = ((sourceHue + hueShift.coerceIn(-180, 180)) % 360f + 360f) % 360f / 60f
    val s = ((if (max == 0f) 0f else span / max) + saturationShift.coerceIn(-100, 100) / 100f).coerceIn(0f, 1f)
    val v = (max + brightnessShift.coerceIn(-100, 100) / 100f).coerceIn(0f, 1f)
    val chroma = v * s
    val x = chroma * (1f - abs(h % 2f - 1f))
    val minimum = v - chroma
    fun channel(component: Float) = ((component + minimum) * 255f).roundToInt().coerceIn(0, 255)
    val rgb = when (h.toInt()) {
        0 -> (channel(chroma) shl 16) or (channel(x) shl 8) or channel(0f)
        1 -> (channel(x) shl 16) or (channel(chroma) shl 8) or channel(0f)
        2 -> (channel(0f) shl 16) or (channel(chroma) shl 8) or channel(x)
        3 -> (channel(0f) shl 16) or (channel(x) shl 8) or channel(chroma)
        4 -> (channel(x) shl 16) or (channel(0f) shl 8) or channel(chroma)
        else -> (channel(chroma) shl 16) or (channel(0f) shl 8) or channel(x)
    }
    return (argb and 0xff000000.toInt()) or rgb
}
