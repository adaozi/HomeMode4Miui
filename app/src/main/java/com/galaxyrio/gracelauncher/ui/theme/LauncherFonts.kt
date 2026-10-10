/*
 * P1 完整版 —— 移植自 GraceLauncher v1.1.4（GPL-3.0，https://github.com/Galaxy-rio/GraceLauncher）
 * 原路径：app/src/main/java/com/galaxyrio/gracelauncher/ui/theme/LauncherFonts.kt
 *
 * P0 裁剪说明（历史）：曾统一回落系统 sans-serif。
 * P1（PORTING_GraceLauncher.md B1.2）恢复完整实现，差异两处：
 *   ① fallbackAssets 裁去 noto_sans_arabic / noto_sans_hebrew / noto_sans_devanagari /
 *      noto_sans_thai（对中文用户无用的 1.7MB 资产；CJK 本就由系统 sans-serif 兜底）。
 *   ② hotfix（v2.2.1，装机长按收藏行崩溃同根因预防）：宿主进程内 Compose 的
 *      Font(R.font.x) 经宿主 Resources 解析模块 ID 必抛 NotFoundException——
 *      preset 字体迁移至 assets/fonts/，经模块 Context.assets 以
 *      Typeface.createFromAssets 加载（R.font 依赖全删）。
 * 其余逐字拷贝。
 */
package com.galaxyrio.gracelauncher.ui.theme

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.galaxyrio.gracelauncher.data.AppFont
import com.galaxyrio.gracelauncher.data.ClockFontStore
import com.galaxyrio.gracelauncher.data.ClockPresetFont
import com.homemode4miui.util.ModLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.graphics.fonts.Font as PlatformFont
import android.graphics.fonts.FontFamily as PlatformFontFamily
import android.graphics.fonts.FontStyle as PlatformFontStyle

/**
 * Josefin Sans first, with bundled Noto fallbacks on Android 10+ and system CJK fonts.
 * Separate weight descriptors preserve real variable-font weights: wrapping a single
 * Android Typeface in Compose FontFamily would ignore subsequent TextStyle weights.
 */
val LauncherFontFamily: FontFamily = FontFamily(
    (100..700 step 100).map { weight -> LauncherFont(FontWeight(weight)) },
)

// The app and clock share the same bundled font families, not separate font buffers.
// hotfix：assets 加载 + 进程级缓存（createFromAssets 每次解码，必须 memoize）。
private val presetTypefaces = mutableMapOf<ClockPresetFont, Typeface>()

private val ClockPresetFont.assetFile: String
    get() = when (this) {
        ClockPresetFont.Sacramento -> "sacramento"
        ClockPresetFont.Bokor -> "bokor"
        ClockPresetFont.Plaster -> "plaster"
        ClockPresetFont.Monoton -> "monoton"
        ClockPresetFont.LuckiestGuy -> "luckiest_guy"
    }

/** context 必须是模块 Context（GraceHomeRoot 已覆盖 LocalContext），否则 assets 不可达。 */
internal fun ClockPresetFont.family(context: Context): FontFamily {
    val key: ClockPresetFont = this
    val typeface: Typeface = synchronized(presetTypefaces) {
        val cached = presetTypefaces[key]
        if (cached != null) return@synchronized cached
        val loaded: Typeface = try {
            Typeface.createFromAsset(context.assets, "fonts/" + key.assetFile + ".ttf")
        } catch (t: Throwable) {
            ModLog.e("preset font load failed, fallback to Josefin: " + key.name, t)
            launcherTypeface(context, 400) // 资产缺失/损坏回落 Josefin，绝不让桌面进程死亡
        }
        presetTypefaces[key] = loaded
        loaded
    }
    return FontFamily(typeface)
}

// 上游同步 v1.1.8（S2/S5 前置）：字体库 CompositionLocal。上游在 LauncherAppTheme 以
// settings.fontLibrary 提供值；本模块 S5 字体管理器批次前保持空默认值 ——
// rememberAppFontFamily(id) 单参语义不变，新设置页（Alphabet/ListAppearance 预览）
// 对 LocalFontLibrary 的引用可编译。
internal val LocalFontLibrary = androidx.compose.runtime.staticCompositionLocalOf {
    com.galaxyrio.gracelauncher.data.FontLibrary()
}

@Composable
internal fun rememberAppFontFamily(id: String?): FontFamily = remember(id) {
    when (id) {
        null -> LauncherFontFamily
        AppFont.System.id -> FontFamily.Default
        else -> LauncherFontFamily
    }
}

/** Async per-weight faces keep imported variable fonts responsive without losing bold roles. */
private data class SelectedFont(val id: String, override val weight: FontWeight) : AndroidFont(
    loadingStrategy = FontLoadingStrategy.Async,
    typefaceLoader = SelectedTypefaceLoader,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
) {
    override val style: FontStyle = FontStyle.Normal
}

private object SelectedTypefaceLoader : AndroidFont.TypefaceLoader {
    private val notoFaces = mutableMapOf<Int, Typeface>()

    override fun loadBlocking(context: Context, font: AndroidFont): Typeface? = null

    override suspend fun awaitLoad(context: Context, font: AndroidFont): Typeface = withContext(Dispatchers.IO) {
        val choice = font as SelectedFont
        val weight = choice.weight.weight
        if (choice.id == AppFont.NotoSans.id) synchronized(notoFaces) {
            notoFaces.getOrPut(weight) {
                Typeface.Builder(context.assets, "fonts/noto_sans.ttf")
                    .setFontVariationSettings("'wght' $weight").setWeight(weight)
                    .setItalic(false).setFallback("sans-serif").build()
                    ?: launcherTypeface(context, weight.coerceIn(100, 700))
            }
        } else ClockFontStore(context).typeface(choice.id, weight)
            ?: launcherTypeface(context, weight.coerceIn(100, 700))
    }
}

private data class LauncherFont(
    override val weight: FontWeight,
) : AndroidFont(
    loadingStrategy = FontLoadingStrategy.Blocking,
    typefaceLoader = LauncherTypefaceLoader,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
) {
    override val style: FontStyle = FontStyle.Normal
}

private object LauncherTypefaceLoader : AndroidFont.TypefaceLoader {
    override fun loadBlocking(context: Context, font: AndroidFont): Typeface =
        launcherTypeface(context, font.weight.weight)

    override suspend fun awaitLoad(context: Context, font: AndroidFont): Typeface =
        loadBlocking(context, font)
}

private const val PRIMARY_FONT_ASSET = "fonts/josefin_sans.ttf"

private val typefaces = mutableMapOf<Int, Typeface>()

/**
 * Returns a cached real weight, shared by every launcher text surface.
 *
 * API 28 has no public custom per-glyph fallback-chain builder. It keeps the same
 * Josefin Sans variable weight and the device's sans-serif glyph fallback, which is not
 * guaranteed to be our bundled Noto files on OEM devices. No hidden APIs are used.
 *
 * Chinese, Japanese and Korean use Android's system sans-serif fallback.
 * Keep the text locale supplied by Compose so Android selects regional glyphs.
 */
internal fun launcherTypeface(context: Context, weight: Int): Typeface {
    require(weight in 100..700) { "Josefin Sans supports weights between 100 and 700" }
    return synchronized(typefaces) {
        typefaces.getOrPut(weight) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                NotoFallbackApi29.build(context, weight)
            } else {
                checkNotNull(
                    Typeface.Builder(context.assets, PRIMARY_FONT_ASSET)
                        .setFontVariationSettings("'wght' $weight")
                        .setWeight(weight)
                        .setItalic(false)
                        .setFallback("sans-serif")
                        .build(),
                )
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
private object NotoFallbackApi29 {
    // P1 裁剪：仅保留 noto_sans.ttf（拉丁扩展）；阿拉伯/希伯来/天城文/泰文 fallback
    // 已裁去（对中文用户无用，省 1.7MB 资产）。CJK 一律走系统 sans-serif。
    private val fallbackAssets = listOf(
        "fonts/noto_sans.ttf",
    )

    // Retain each bundled font's native backing buffer once. All weight
    // instances reuse that buffer instead of re-reading files.
    // Access is serialized by the outer typeface cache lock.
    private val baseFonts = mutableMapOf<String, PlatformFont>()

    private fun family(context: Context, asset: String, weight: Int): PlatformFontFamily {
        val base = baseFonts.getOrPut(asset) {
            PlatformFont.Builder(context.assets, asset).build()
        }
        // The Font copy-builder needs API 31. The public buffer constructor also
        // shares font data and works on every API level supporting custom fallback.
        val font = PlatformFont.Builder(base.buffer)
            .setTtcIndex(base.ttcIndex)
            .setFontVariationSettings("'wght' $weight")
            .setWeight(weight)
            .setSlant(PlatformFontStyle.FONT_SLANT_UPRIGHT)
            .build()
        return PlatformFontFamily.Builder(font).build()
    }

    fun build(context: Context, weight: Int): Typeface {
        val builder = Typeface.CustomFallbackBuilder(family(context, PRIMARY_FONT_ASSET, weight))
        fallbackAssets.forEach { asset ->
            builder.addCustomFallback(family(context, asset, weight))
        }
        return builder
            .setStyle(PlatformFontStyle(weight, PlatformFontStyle.FONT_SLANT_UPRIGHT))
            // CJK, emoji and scripts beyond the packaged Noto families use the OS.
            .setSystemFallback("sans-serif")
            .build()
    }
}
