/*
 * 移植自 GraceLauncher v1.1.4（GPL-3.0，https://github.com/Galaxy-rio/GraceLauncher）
 * 原路径：app/src/main/java/com/galaxyrio/gracelauncher/ui/components/LauncherLayout.kt
 * P1 移植：逐字拷贝，未改动。
 */
package com.galaxyrio.gracelauncher.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.runtime.Composable
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
