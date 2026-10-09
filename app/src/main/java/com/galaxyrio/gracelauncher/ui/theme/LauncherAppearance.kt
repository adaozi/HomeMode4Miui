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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.core.content.ContextCompat
import com.galaxyrio.gracelauncher.data.WallpaperTextMode
import java.util.function.Consumer

data class LauncherAppearance(val darkText: Boolean = false, val themedIcons: Boolean = true, val iconSize: Int = 100) {
    val text: Color get() = if (darkText) Color(0xFF202025) else Color(0xFFFAF9FE)
    val textShadow: Shadow get() = if (darkText) Shadow.None else Shadow(Color.Black.copy(alpha = 0.32f), blurRadius = 4f)
}

val LocalLauncherAppearance = staticCompositionLocalOf { LauncherAppearance() }

@Composable
fun rememberLauncherAppearance(mode: WallpaperTextMode, themedIcons: Boolean, iconSize: Int = 100): LauncherAppearance {
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
    )
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
