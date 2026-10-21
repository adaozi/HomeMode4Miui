/*
 * 移植自 GraceLauncher v1.1.4（GPL-3.0，https://github.com/Galaxy-rio/GraceLauncher）
 * 原路径：app/src/main/java/com/galaxyrio/gracelauncher/ui/theme/LauncherAppearance.kt
 * P0 移植：逐字拷贝；唯一依赖缺口 WallpaperTextMode 以独立枚举文件承载（见 data/WallpaperTextMode.kt）。
 */
package com.galaxyrio.gracelauncher.ui.theme

import android.app.Activity
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.core.content.ContextCompat
import com.galaxyrio.gracelauncher.data.IconDisplaySettings
import com.galaxyrio.gracelauncher.data.LauncherApp
import com.galaxyrio.gracelauncher.data.WallpaperTextMode
import com.galaxyrio.gracelauncher.data.IconColor
import com.galaxyrio.gracelauncher.data.wallpaperPrimaryArgb
import com.materialkolor.palettes.TonalPalette
import java.util.function.Consumer

data class LauncherAppearance(val darkText: Boolean = false, val themedIcons: Boolean = false, val iconSize: Int = 100,
    val iconDisplay: IconDisplaySettings = IconDisplaySettings()) {
    val text: Color get() = if (darkText) Color(0xFF202025) else Color(0xFFFAF9FE)
    val textShadow: Shadow get() = if (darkText) Shadow.None else Shadow(Color.Black.copy(alpha = 0.32f), blurRadius = 4f)
}

val LocalLauncherAppearance = staticCompositionLocalOf { LauncherAppearance() }

/** 上游同步 v1.1.8：Dim Wallpaper 自定义遮罩色解析（v1.1.7 特性；S7 宿主消费接线随后）。 */
internal fun LauncherAppearance.wallpaperTint(primary: Color, choice: IconColor? = null): Color {
    // Adjust primary's tone, not a black scrim. Dark text gets a matching light tint.
    // Change tone within the already selected theme palette; do not generate a
    // second Tonal Spot scheme that discards the user's chosen style/chroma.
    val automatic = Color(TonalPalette.fromInt(primary.toArgb()).tone(if (darkText) 95 else 10))
    return choice?.resolve(primary.toArgb())?.let(::Color) ?: automatic
}

/** One resolver for every row: a per-app override wins, else the shared setting. */
fun LauncherApp.effectiveDisplay(appearance: LauncherAppearance): IconDisplaySettings =
    iconDisplay ?: appearance.iconDisplay

@Composable
fun rememberLauncherAppearance(mode: WallpaperTextMode, themedIcons: Boolean, iconSize: Int = 100,
    iconDisplay: IconDisplaySettings = IconDisplaySettings()): LauncherAppearance {
    val context = LocalContext.current
    val manager = remember(context) { WallpaperManager.getInstance(context) }
    fun prefersDark(colors: WallpaperColors?): Boolean = Build.VERSION.SDK_INT >= 31 &&
        colors != null && (colors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0
    var dark by remember(manager) {
        mutableStateOf(runCatching { prefersDark(manager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)) }.getOrDefault(false))
    }
    DisposableEffect(manager) {
        val listener = WallpaperManager.OnColorsChangedListener { colors, which ->
            if (which and WallpaperManager.FLAG_SYSTEM != 0) dark = prefersDark(colors)
        }
        manager.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper()))
        onDispose { manager.removeOnColorsChangedListener(listener) }
    }
    return LauncherAppearance(
        darkText = when (mode) {
            WallpaperTextMode.Auto -> dark
            WallpaperTextMode.Light -> false
            WallpaperTextMode.Dark -> true
        },
        themedIcons = themedIcons,
        iconSize = iconSize.coerceIn(80, 150),
        iconDisplay = iconDisplay,
    )
}

/**
 * 壁纸变化信号（wallpaper-reactive，模块侧根因修复）：
 * 原版 dynamicColor 只在组合期读一次 system_accent1_500 —— MIUI/HyperOS 的该资源是
 * 静态值（无完整 Material You 壁纸取色），且壁纸更换后没有任何重组失效源，主题色
 * 永远停在进程启动时的状态。此 Composable 提供统一的壁纸版本号，双通道去盲区：
 *   - OnColorsChangedListener：AOSP 会做 WallpaperColors 去重（同色系新壁纸不回调）；
 *   - ACTION_WALLPAPER_CHANGED 广播：每次设壁纸必发，补齐同色系盲区。
 * 双通道对同一次换壁纸可能各 bump 一次（广播即时、colors 回调延迟）→ 两次重组/两次
 * 重载，无正确性问题，仅一次冗余重载，可接受。
 */
@Composable
internal fun rememberWallpaperVersion(): Int {
    val context = LocalContext.current
    var version by remember(context) { mutableStateOf(0) }
    DisposableEffect(context) {
        val manager = runCatching { WallpaperManager.getInstance(context) }.getOrNull()
        val colorsListener = WallpaperManager.OnColorsChangedListener { _, _ -> version++ }
        runCatching { manager?.addOnColorsChangedListener(colorsListener, Handler(Looper.getMainLooper())) }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_WALLPAPER_CHANGED) version++
            }
        }
        runCatching {
            ContextCompat.registerReceiver(
                context, receiver, IntentFilter(Intent.ACTION_WALLPAPER_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        onDispose {
            runCatching { manager?.removeOnColorsChangedListener(colorsListener) }
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
    return version
}

/**
 * 壁纸动态取色种子（reactive）。优先 WallpaperColors 主色 —— 与 ROM 的
 * Material You 实现无关，任何 ROM（含 MIUI/HyperOS）都能跟随壁纸；system_accent1_500
 * 仅在系统未回壁纸颜色时兜底（保持与上游 launcherThemeSeed 一致的降级链）。
 * 主色提取走 data 层共享的 wallpaperPrimaryArgb（反射兼容 API 37 签名变化，见
 * ThemePalette.kt）——与 ItemIconStore 光栅化配色同源。
 * rememberLauncherAppearance 的 darkText 已有自己的颜色监听，二者互不干扰。
 */
@Composable
internal fun rememberWallpaperSeedColor(fallback: Int): Int {
    val context = LocalContext.current
    val manager = remember(context) { runCatching { WallpaperManager.getInstance(context) }.getOrNull() }
    var seed: Int by remember(manager) {
        val initial: Int = runCatching {
            wallpaperPrimaryArgb(manager?.getWallpaperColors(WallpaperManager.FLAG_SYSTEM))
                ?: context.resources.getColor(android.R.color.system_accent1_500, null)
        }.getOrDefault(fallback)
        mutableStateOf(initial)
    }
    DisposableEffect(manager) {
        val listener = WallpaperManager.OnColorsChangedListener { colors, which ->
            if (which and WallpaperManager.FLAG_SYSTEM != 0) {
                val resolved: Int? = runCatching {
                    wallpaperPrimaryArgb(colors)
                        ?: wallpaperPrimaryArgb(manager?.getWallpaperColors(WallpaperManager.FLAG_SYSTEM))
                        ?: context.resources.getColor(android.R.color.system_accent1_500, null)
                }.getOrNull()
                resolved?.let { seed = it }
            }
        }
        runCatching { manager?.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper())) }
        onDispose { runCatching { manager?.removeOnColorsChangedListener(listener) } }
    }
    return seed
}

@Composable
fun rememberBatteryPercent(): Int? {
    val context = LocalContext.current
    var percent by remember { mutableStateOf<Int?>(null) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                percent = if (level >= 0 && scale > 0) (100 * level / scale) else null
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return percent
}

/** The system may disable cross-window blur at runtime (e.g. battery saver). */
@Composable
internal fun rememberWallpaperBlurAvailable(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(WindowManager::class.java) }
    var available by remember(manager) {
        mutableStateOf(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && manager?.isCrossWindowBlurEnabled == true)
    }
    DisposableEffect(manager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && manager != null) {
            val listener = Consumer<Boolean> { available = it }
            manager.addCrossWindowBlurEnabledListener(context.mainExecutor, listener)
            onDispose { manager.removeCrossWindowBlurEnabledListener(listener) }
        } else onDispose { }
    }
    return available
}

/** Blur the wallpaper through HOME's transparent window, not its foreground content. */
@Composable
internal fun WallpaperBlur(radius: Dp) {
    val context = LocalContext.current
    val view = LocalView.current
    val window = (context as? Activity)?.window
    if (window == null || view.isInEditMode || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val radiusPx = with(LocalDensity.current) { radius.roundToPx() }.coerceIn(0, 150)
    // Background blur is part of DecorView, unlike FLAG_BLUR_BEHIND's separate
    // zero-alpha DimLayer, which can stay invisible after returning via HOME.
    // Android owns surface attachment and blur availability; the UI owns only
    // the radius (0 on HOME, animated with the app list's visibility otherwise).
    SideEffect { window.setBackgroundBlurRadius(radiusPx) }
    DisposableEffect(window) {
        onDispose { window.setBackgroundBlurRadius(0) }
    }
}
