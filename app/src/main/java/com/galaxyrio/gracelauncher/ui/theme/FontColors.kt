package com.galaxyrio.gracelauncher.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.galaxyrio.gracelauncher.data.FontColors
import com.galaxyrio.gracelauncher.data.IconColor

val LocalFontColors = staticCompositionLocalOf { FontColors() }

/** Resolve component overrides at display time so palette edits and theme changes stay live. */
@Composable
internal fun resolvedFontColor(choice: IconColor? = null, themed: Boolean = false, onWallpaper: Boolean = false): Color {
    val colors = LocalFontColors.current
    val scheme = MaterialTheme.colorScheme
    val inherited = when {
        onWallpaper -> IconColor(0, fontColorId = FontColors.WallpaperId)
        themed -> colors.themeColor
        else -> colors.defaultColor
    }
    val selected = choice?.takeUnless { it.fontColorId != null && colors.color(it.fontColorId) == null } ?: inherited
    return Color(selected.resolve(scheme.primary.toArgb(), colorScheme = scheme, fontColors = colors,
        wallpaperColor = LocalWallpaperFontColor.current.toArgb()))
}
