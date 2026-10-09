/*
 * 移植自 GraceLauncher v1.1.4（GPL-3.0，https://github.com/Galaxy-rio/GraceLauncher）
 * 原路径：app/src/main/java/com/galaxyrio/gracelauncher/ui/theme/Type.kt
 * P0 移植：逐字拷贝，未改动（字体家族回落见 P0 裁剪版 LauncherFonts.kt）。
 */
package com.galaxyrio.gracelauncher.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val DefaultMaterialTypography = Typography()

val Typography = launcherTypography(LauncherFontFamily)
internal val SystemLauncherTypography = launcherTypography(FontFamily.Default)

/** Kept outside the settings-only override so wallpaper previews match the desktop. */
internal val LocalLauncherTypography = staticCompositionLocalOf { Typography }

internal fun launcherTypography(family: FontFamily) = Typography(
    // Includes Expressive emphasized roles used by the flexible app bar.
    fontFamily = family,
    displayLarge = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Light,
        fontSize = 58.sp,
        lineHeight = 62.sp,
        letterSpacing = (-1.5).sp,
    ),
    displayMedium = DefaultMaterialTypography.displayMedium.copy(fontFamily = family),
    displaySmall = DefaultMaterialTypography.displaySmall.copy(fontFamily = family),
    headlineLarge = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineMedium = DefaultMaterialTypography.headlineMedium.copy(fontFamily = family),
    headlineSmall = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 27.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp,
    ),
    titleSmall = DefaultMaterialTypography.titleSmall.copy(fontFamily = family),
    bodyLarge = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 23.sp,
        letterSpacing = 0.15.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.15.sp,
    ),
    bodySmall = DefaultMaterialTypography.bodySmall.copy(fontFamily = family),
    labelLarge = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp,
    ),
    labelSmall = DefaultMaterialTypography.labelSmall.copy(fontFamily = family),
)
