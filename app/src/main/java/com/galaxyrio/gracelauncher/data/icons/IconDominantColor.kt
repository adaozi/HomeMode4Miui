package com.galaxyrio.gracelauncher.data.icons

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * Most-used color of an icon's visible pixels, bucketed at 4 bits per channel.
 * White-squircle icons (Gmail, Chrome…) dominate on neutral pixels, so a neutral
 * winner yields to the most populous saturated bucket; fully neutral icons
 * return null and the caller falls back to the theme color.
 */
internal object IconDominantColor {
    private const val SampleSize = 24

    fun of(icon: Bitmap): Int? {
        val sampled = if (icon.width > SampleSize || icon.height > SampleSize)
            Bitmap.createScaledBitmap(icon, SampleSize, SampleSize, true) else icon
        val pixels = IntArray(sampled.width * sampled.height)
        try { sampled.getPixels(pixels, 0, sampled.width, 0, 0, sampled.width, sampled.height) }
        finally { if (sampled !== icon) sampled.recycle() }
        return fromPixels(pixels)
    }

    /** Pure pixel reduction, shared with the host-side color regression tests. */
    internal fun fromPixels(pixels: IntArray): Int? {
        val counts = IntArray(1 shl 12)
        for (pixel in pixels) {
            if (pixel ushr 24 < 128) continue
            counts[((pixel ushr 20 and 15) shl 8) or ((pixel ushr 12 and 15) shl 4) or (pixel ushr 4 and 15)]++
        }
        var dominant = -1
        var saturated = -1
        for (bucket in counts.indices) {
            if (counts[bucket] == 0) continue
            if (dominant < 0 || counts[bucket] > counts[dominant]) dominant = bucket
            if (!isNeutral(bucket) && (saturated < 0 || counts[bucket] > counts[saturated])) saturated = bucket
        }
        return when {
            dominant < 0 -> null
            !isNeutral(dominant) -> bucketColor(dominant)
            saturated >= 0 -> bucketColor(saturated)
            else -> null
        }
    }

    private fun isNeutral(bucket: Int): Boolean {
        val red = (bucket shr 8 and 15) / 15f
        val green = (bucket shr 4 and 15) / 15f
        val blue = (bucket and 15) / 15f
        val high = maxOf(red, green, blue)
        val low = minOf(red, green, blue)
        val lightness = (high + low) / 2f
        // The same HSL saturation/lightness thresholds, without Android framework
        // calls in the reducer. Keep all scratch local for concurrent app loading.
        val saturation = if (high == low) 0f else (high - low) / (1 - abs(2 * lightness - 1))
        return saturation < 0.15f || lightness !in 0.12f..0.88f
    }

    private fun bucketColor(bucket: Int): Int =
        (0xFF shl 24) or ((((bucket shr 8) and 15) * 17) shl 16) or
            ((((bucket shr 4) and 15) * 17) shl 8) or ((bucket and 15) * 17)
}
