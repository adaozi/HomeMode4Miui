package com.galaxyrio.gracelauncher.data

import android.content.Context
import android.content.res.Resources
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec

/** The system accent is a seed, not a finished scheme, so every style works in dynamic mode too. */
internal fun launcherThemeSeed(resources: Resources, dynamic: Boolean, fallback: Int): Int =
    if (dynamic && Build.VERSION.SDK_INT >= 31) resources.getColor(android.R.color.system_accent1_500, null)
    else fallback

/**
 * WallpaperColors 主色 → ARGB Int（模块侧根因修复的共享底座）。
 * 反射取值：API 37 起 getPrimaryColor() 返回类型从 int 变为 android.graphics.Color
 * （同名不同描述符）——按新签名直编，装机在旧 framework 上 NoSuchMethodError。
 * 反射按名调用同时兼容旧 int（Number）与新 Color（toArgb）两种返回。
 */
internal fun wallpaperPrimaryArgb(colors: android.app.WallpaperColors?): Int? {
    if (colors == null) return null
    return runCatching {
        val raw = colors.javaClass.getMethod("getPrimaryColor").invoke(colors)
        when (raw) {
            is Number -> raw.toInt()
            else -> raw?.javaClass?.getMethod("toArgb")?.invoke(raw) as? Int
        }
    }.getOrNull()
}

/**
 * 壁纸动态取色种子（非组合层：ItemIconStore 光栅化等 VM 侧消费）。
 * 优先 WallpaperColors 主色 —— 与 ROM 的 Material You 实现无关，任何 ROM（含
 * MIUI/HyperOS）都能跟随壁纸；system_accent1_500 仅在系统未回壁纸颜色时兜底，
 * 与组合层 rememberWallpaperSeedColor 保持同一条降级链。
 */
internal fun launcherWallpaperSeed(context: Context, dynamic: Boolean, fallback: Int): Int {
    if (!dynamic || Build.VERSION.SDK_INT < 31) return fallback
    val fromWallpaper = runCatching {
        wallpaperPrimaryArgb(
            android.app.WallpaperManager.getInstance(context)
                .getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM))
    }.getOrNull()
    return fromWallpaper
        ?: runCatching { context.resources.getColor(android.R.color.system_accent1_500, null) }.getOrDefault(fallback)
}

/** UI, previews and rasterized icons must use the same palette algorithm and spec. */
internal fun launcherColorScheme(seed: Int, dark: Boolean, style: PaletteStyle) = dynamicColorScheme(
    seedColor = Color(seed), isDark = dark, style = style, specVersion = ColorSpec.SpecVersion.SPEC_2025,
)

/** A tray and the symbol on it are a paired Material role, not two copies of the seed. */
internal fun launcherIconColors(seed: Int, dark: Boolean, style: PaletteStyle): Pair<Int, Int> =
    launcherColorScheme(seed, dark, style).let { it.primaryContainer.toArgb() to it.onPrimaryContainer.toArgb() }
