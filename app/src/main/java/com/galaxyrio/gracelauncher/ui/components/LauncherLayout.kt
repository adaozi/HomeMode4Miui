/*
 * 移植自 GraceLauncher v1.1.8（GPL-3.0，https://github.com/Galaxy-rio/GraceLauncher）
 * 上游同步 2026-10-11（PORTING_GraceLauncher.md §9）：按上游 v1.1.8 逐字更新。
 */
package com.galaxyrio.gracelauncher.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.galaxyrio.gracelauncher.data.ListAppearance
import com.galaxyrio.gracelauncher.ui.theme.LocalLauncherAppearance
import com.galaxyrio.gracelauncher.ui.theme.rememberAppFontFamily

internal val LocalListAppearance = staticCompositionLocalOf { ListAppearance() }

// Keep the original 20dp rail clearance on the right; the control moves both edges together.
internal val ListAppearance.startPadding: Dp get() = (sidePadding - 8).coerceAtLeast(0).dp
internal val ListAppearance.endPadding: Dp get() = (sidePadding + 12).dp

@Composable
internal fun listLabelStyle(onWallpaper: Boolean = true): TextStyle {
    val list = LocalListAppearance.current
    val wallpaper = LocalLauncherAppearance.current
    val color = list.fontColor?.resolve(wallpaper.text.toArgb(), MaterialTheme.colorScheme.primary.toArgb())?.let(::Color)
        ?: if (onWallpaper) wallpaper.text else MaterialTheme.colorScheme.onSurface
    return MaterialTheme.typography.bodyLarge.copy(fontFamily = rememberAppFontFamily(list.fontId),
        fontSize = list.fontSize.sp, lineHeight = (list.fontSize * 1.5f).sp, fontWeight = FontWeight.Normal,
        color = color, letterSpacing = 0.2.sp, shadow = if (onWallpaper) wallpaper.textShadow else null)
}

/** Shared desktop columns, including the otherwise invisible touch surfaces. */
internal object LauncherLayout {
    val Start = 36.dp
    val End = 56.dp
    val ContentInset = 8.dp
    val IconLabelGap = 20.dp
    val RowMinHeight = 56.dp
    val RowShape = RoundedCornerShape(18.dp)
    // Roughly two status bars tall; keep the fade independent of inset variations.
    val TopFadeHeight = 56.dp
}

/** Reserve one stable top safe area, whether the status bar is currently shown or not. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun stableStatusBarInset(): Dp {
    val density = LocalDensity.current
    return with(density) {
        maxOf(WindowInsets.statusBarsIgnoringVisibility.getTop(density), WindowInsets.displayCutout.getTop(density)).toDp()
    }
}

/** Fade only foreground content, never paint a status-bar-colored strip over the wallpaper. */
internal fun Modifier.statusBarContentFade(height: Dp = LauncherLayout.TopFadeHeight): Modifier =
    if (height <= 0.dp) this else graphicsLayer {
        compositingStrategy = CompositingStrategy.Offscreen
    }.drawWithCache {
        val fadeHeight = height.toPx().coerceAtMost(size.height)
        val mask = Brush.verticalGradient(
            colors = listOf(Color.Transparent, Color.Black),
            startY = 0f,
            endY = fadeHeight,
        )
        onDrawWithContent {
            drawContent()
            drawRect(mask, size = Size(size.width, fadeHeight), blendMode = BlendMode.DstIn)
        }
    }
