@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.galaxyrio.gracelauncher.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.galaxyrio.gracelauncher.ui.LauncherUiState
import com.galaxyrio.gracelauncher.ui.theme.rememberLauncherAppearance
import com.galaxyrio.gracelauncher.ui.theme.wallpaperTint

internal const val SettingsPreviewSurfaceAlpha = .8f

/** Clear the retained launcher once for the whole editor, including the system bars. */
@Composable
internal fun SettingsPreviewScaffold(
    uiState: LauncherUiState,
    title: String,
    tag: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    previewEnabled: Boolean = true,
    wallpaperNavigationBar: Boolean = false,
    controlsAbovePreview: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        if (previewEnabled) {
            val settings = uiState.settings
            val appearance = rememberLauncherAppearance(uiState.textMode, uiState.themedIcons)
            val colors = MaterialTheme.colorScheme
            val tint = remember(colors, appearance.darkText, settings.listAppearance.wallpaperDimColor) {
                appearance.wallpaperTint(colors.primary, settings.listAppearance.wallpaperDimColor, colors)
            }
            val dimAlpha = if (settings.dimWallpaper) settings.wallpaperDimAmount.coerceIn(0, 100) / 100f else 0f
            Box(Modifier.matchParentSize().drawBehind {
                drawRect(Color.Transparent, blendMode = BlendMode.Clear)
                drawRect(tint.copy(alpha = dimAlpha))
            })
        }
        SettingsScaffold(title, tag, onBack, fixedCollapsed = true,
            wallpaperBackground = previewEnabled, wallpaperNavigationBar = wallpaperNavigationBar,
            controlsAbovePreview = controlsAbovePreview, actions = actions, content = content)
    }
}

/** Apply before the controls' horizontal padding so the backdrop covers the side gutters too. */
@Composable
internal fun Modifier.settingsPreviewScrim(previewBelow: Boolean = false): Modifier {
    val tint = MaterialTheme.colorScheme.surfaceContainer
    return drawWithCache {
        val fadeHeight = 48.dp.toPx().coerceAtMost(size.height).coerceAtLeast(1f)
        val clear = tint.copy(alpha = 0f)
        val shade = tint.copy(alpha = SettingsPreviewSurfaceAlpha)
        val backdrop = Brush.verticalGradient(
            colors = if (previewBelow) listOf(shade, clear) else listOf(clear, shade),
            startY = if (previewBelow) (size.height - fadeHeight).coerceAtLeast(0f) else 0f,
            endY = if (previewBelow) size.height.coerceAtLeast(1f) else fadeHeight,
        )
        onDrawBehind { drawRect(backdrop) }
    }
}

/** A stationary scrolling boundary with the same top corners as a segmented list. */
@Composable
internal fun settingsPreviewControlsShape(): Shape {
    val shape = ListItemDefaults.segmentedShapes(0, 1).shape
    return remember(shape) {
        (shape as? CornerBasedShape)?.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)) ?: shape
    }
}
