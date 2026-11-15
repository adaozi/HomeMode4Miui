package com.galaxyrio.gracelauncher.ui.theme

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.galaxyrio.gracelauncher.data.WallpaperBrightness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val LocalWallpaperFontColor = staticCompositionLocalOf { Color(0xFFFAF9FE) }

/** Missing metadata favors light text; older Android versions need a clearly light primary color. */
internal fun wallpaperIsDark(supportsDarkText: Boolean?, primaryLuminance: Float?): Boolean =
    supportsDarkText?.not() ?: (primaryLuminance == null || !primaryLuminance.isFinite() || primaryLuminance < 0.8f)

/** Only the user's explicit metadata access choice enables wallpaper service calls. */
@Composable
internal fun rememberWallpaperIsDark(brightness: WallpaperBrightness, access: Boolean): Boolean {
    if (brightness != WallpaperBrightness.Auto) return brightness == WallpaperBrightness.Dark
    if (!access) return true

    val context = LocalContext.current
    val manager = remember(context) { runCatching { WallpaperManager.getInstance(context) }.getOrNull() } ?: return true
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var dark by remember(manager) { mutableStateOf(true) }
    var revision by remember(manager) { mutableIntStateOf(0) }
    DisposableEffect(manager, lifecycle) {
        val listener = WallpaperManager.OnColorsChangedListener { _, which ->
            if (which and WallpaperManager.FLAG_SYSTEM != 0) revision++
        }
        val listening = runCatching {
            manager.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper()))
        }.isSuccess
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) revision++
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (listening) runCatching { manager.removeOnColorsChangedListener(listener) }
        }
    }
    LaunchedEffect(manager, revision) {
        dark = withContext(Dispatchers.IO) {
            runCatching {
                val colors = manager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
                wallpaperIsDark(
                    supportsDarkText = if (Build.VERSION.SDK_INT >= 31 && colors != null)
                        colors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT != 0 else null,
                    primaryLuminance = colors?.primaryColor?.toArgb()?.let { Color(it).luminance() },
                )
            }.getOrDefault(true)
        }
    }
    return dark
}
