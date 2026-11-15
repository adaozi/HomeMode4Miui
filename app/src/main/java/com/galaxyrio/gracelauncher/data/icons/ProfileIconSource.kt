package com.galaxyrio.gracelauncher.data.icons

import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap

/**
 * Retain the unbadged source obtained from LauncherActivityInfo, not a styled
 * bitmap. A profile may be locked by the time the designer needs its layers.
 * Rasterize at launcher resolution: retaining full-size provider drawables for
 * every installed app can retain large bitmaps/resources. This object also
 * identifies an unchanged source across inventory refreshes.
 */
class ProfileIconSource private constructor(private val source: IconLayers?) {
    // Render-cache signatures must not keep a second strong reference to the source bitmaps.
    internal val cacheKey = Any()
    internal fun layers(): IconLayers? = source

    companion object {
        internal fun fromDrawable(drawable: Drawable) = ProfileIconSource(iconLayers(drawable, 144))
        internal fun fromCache(icon: ImageBitmap?, monochrome: ImageBitmap?) = ProfileIconSource(
            icon?.let { IconLayers(it.asAndroidBitmap(), monochrome = monochrome?.asAndroidBitmap()) })
    }
}
