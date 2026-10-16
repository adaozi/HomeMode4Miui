/*
 * 移植自 GraceLauncher v1.1.4（GPL-3.0，https://github.com/Galaxy-rio/GraceLauncher）
 * 原路径：app/src/main/java/com/galaxyrio/gracelauncher/ui/theme/Theme.kt
 * P0 移植：逐字拷贝，未改动。
 */
package com.galaxyrio.gracelauncher.ui.theme

import android.os.Build
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.LocalTonalElevationEnabled
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.galaxyrio.gracelauncher.data.FontLibrary
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.rememberDynamicColorScheme

val Ink = Color(0xFF071A19)
val DeepTeal = Color(0xFF123B37)
val Mint = Color(0xFF8BE0CA)
val MintContainer = Color(0xFF1D514A)
val Coral = Color(0xFFFFB3A7)
val Moon = Color(0xFFF2FAF7)
val Mist = Color(0xFFB7CBC5)

val Paper = Color(0xFFF4FBF8)
val Forest = Color(0xFF0A6257)
val PaleMint = Color(0xFFA8F2DE)
val Rose = Color(0xFF8E4A43)

private val DarkColorScheme = darkColorScheme(
    primary = Mint,
    onPrimary = Ink,
    primaryContainer = MintContainer,
    onPrimaryContainer = Moon,
    secondary = PaleMint,
    onSecondary = Ink,
    tertiary = Coral,
    background = Ink,
    onBackground = Moon,
    surface = Ink,
    onSurface = Moon,
    surfaceVariant = DeepTeal,
    onSurfaceVariant = Mist,
)

private val LightColorScheme = lightColorScheme(
    primary = Forest,
    onPrimary = Paper,
    primaryContainer = PaleMint,
    onPrimaryContainer = Ink,
    secondary = DeepTeal,
    tertiary = Rose,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GraceLauncherTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = true,
    seedColor: Color? = null,
    /** 上游同步 v1.1.8 S5：全局 UI 字体 = 字体库 selected 链首位（字体库管理页语义）。 */
    fontLibrary: FontLibrary = FontLibrary(),
    /** 模块架构定制（appFont 桌面接线）：字体资产/导入文件解析用 Context。桌面宿主进程必须传模块 Context（宿主 assets 无模块字体资产）。 */
    fontContext: android.content.Context? = null,
    amoledMode: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        seedColor != null -> rememberDynamicColorScheme(
            seedColor = seedColor,
            isDark = darkTheme,
            style = PaletteStyle.TonalSpot,
            specVersion = ColorSpec.SpecVersion.SPEC_2025,
        )
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val trueBlack = darkTheme && amoledMode
    // Keep raised cards and accent colors distinct while making page and sheet
    // backgrounds truly black, for both wallpaper-derived and custom palettes.
    val colors = if (trueBlack) colorScheme.copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceDim = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color.Black,
        surfaceContainer = Color.Black,
    ) else colorScheme

    // 上游同步 v1.1.8 S5：全局字体 = 字体库链（id=null 走 library.selected 首位优先）；
    // 主题内部 provide LocalFontLibrary（上游同构，listLabelStyle 等库链消费方读取）。
    val family = rememberAppFontFamily(null, fontLibrary, fontContext)
    val typography = remember(family) { launcherTypography(family) }
    CompositionLocalProvider(
        LocalFontLibrary provides fontLibrary,
        LocalLauncherTypography provides typography,
        // Elevation tint must not turn the black sheets back into tinted gray.
        LocalTonalElevationEnabled provides (LocalTonalElevationEnabled.current && !trueBlack),
    ) {
        MaterialExpressiveTheme(
            colorScheme = colors,
            typography = typography,
            content = content,
        )
    }
}
