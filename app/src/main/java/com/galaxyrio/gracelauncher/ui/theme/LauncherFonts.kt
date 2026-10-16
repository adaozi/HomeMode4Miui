/*
 * 移植自 GraceLauncher v1.1.8（GPL-3.0，https://github.com/Galaxy-rio/GraceLauncher）
 * 原路径：app/src/main/java/com/galaxyrio/gracelauncher/ui/theme/LauncherFonts.kt
 *
 * 上游同步 v1.1.8 S5 全量对齐（hotfix font-mirror，替换 P1 的 v1.1.4 版语义）：
 *   - 字体选择真身 = FontLibrary.selected 库链（字体库管理页首位 = 全局 UI 字体），
 *     主题 rememberAppFontFamily(null, fontLibrary) 无 null 短路；listAppearance.fontId
 *     仅做列表行覆盖（listLabelStyle）；appFontId 为 legacy 列仅作删除清理。
 *   - SelectedFont 异步逐权重（100..900）链式解析，SelectedTypefaceApi29 兜底链。
 * 模块架构适配（宿主进程）：
 *   ① preset 字体走模块 assets/fonts/（v2.2.1 hotfix 语义，R.font 依赖保留删除）；
 *   ② 导入字体双路解析：本进程私有目录 + FontCrossProcess 宿主镜像副本（root 拷贝）；
 *   ③ rememberAppFontFamily 增加 context 参数（桌面须传模块 Context，见 GraceHomeRoot），
 *      整体 runCatching 兜底（字体解析异常绝不逃出重组，桌面零崩溃铁律）+ 失败日志按 id 去重；
 *   ④ minSdk 33：上游 API<29 的 primaryTypeface 分支不搬运，直接回落 launcherTypeface。
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
import com.galaxyrio.gracelauncher.data.FontLibrary
import com.galaxyrio.gracelauncher.data.ClockPresetFont
import com.homemode4miui.util.ModLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.graphics.fonts.Font as PlatformFont
import android.graphics.fonts.FontFamily as PlatformFontFamily
import android.graphics.fonts.FontStyle as PlatformFontStyle
import android.util.LruCache

/**
 * Josefin Sans first, with bundled Noto fallbacks on Android 10+ and system CJK fonts.
 * Separate weight descriptors preserve real variable-font weights: wrapping a single
 * Android Typeface in Compose FontFamily would ignore subsequent TextStyle weights.
 */
val LauncherFontFamily: FontFamily = FontFamily(
    (100..700 step 100).map { weight -> LauncherFont(null, FontWeight(weight)) },
)

// The app and clock share the same bundled font families, not separate font buffers.
// hotfix：assets 加载 + 进程级缓存（createFromAssets 每次解码，必须 memoize）。
private val presetTypefaces = mutableMapOf<ClockPresetFont, Typeface>()

internal val ClockPresetFont.assetFile: String
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

// 上游同步 v1.1.8 S5：字体库 CompositionLocal（上游在 Theme 内提供）。
internal val LocalFontLibrary = androidx.compose.runtime.staticCompositionLocalOf {
    com.galaxyrio.gracelauncher.data.FontLibrary()
}

// hotfix（appFont 跨进程）：失败日志按 id 去重 —— 每个应用行独立 remember 槽，
// 不可解析 id 会每行刷一条（装机 5 分钟日志 1.4 万行实锤）。
private val loggedFontFailures: MutableSet<String> =
    java.util.Collections.synchronizedSet(mutableSetOf<String>())

/** 上游 v1.1.8 语义：ids = (显式 id + 字体库 selected)；首位系统 = Default，纯 Josefin = 内置，其余库链。 */
@Composable
internal fun rememberAppFontFamily(id: String?, library: FontLibrary = LocalFontLibrary.current,
    context: Context? = null): FontFamily {
    // hotfix（appFont 桌面接线补漏）：桌面须传模块 Context（资产解析/导入文件解析），
    // 见 GraceHomeRoot。整体 runCatching：字体解析任何异常绝不逃出重组。
    val ambient = context ?: androidx.compose.ui.platform.LocalContext.current
    return remember(id, library, ambient) {
        runCatching {
            val ids = (listOfNotNull(id) + library.selected).distinct()
            when {
                ids.isEmpty() || ids.first() == AppFont.System.id -> FontFamily.Default
                // hotfix（resolver context 修复）：Josefin 内置链同样自带 context ——
                // AndroidFont 的加载 context 来自 FontFamilyResolver（宿主 Composition
                // 根构造，assets 无模块字体，装机 FileNotFoundException 实锤），
                // 只有随字体对象携带的 context 才进加载链。
                ids == listOf(FontLibrary.Josefin) ->
                    FontFamily((100..700 step 100).map { LauncherFont(ambient, FontWeight(it)) })
                else -> FontFamily((100..900 step 100).map { SelectedFont(ids, ambient, FontWeight(it)) })
            }
        }.getOrElse { error ->
            if (loggedFontFailures.add(id ?: "null:" + library.hashCode())) {
                ModLog.e("app font family failed, fallback Josefin (id=${id?.take(32)})", error)
            }
            LauncherFontFamily
        }
    }
}

/** Async per-weight faces keep imported variable fonts responsive without losing bold roles. */
private data class SelectedFont(
    val ids: List<String>,
    /** 创建点捕获的解析 context（桌面 = moduleContext）。AndroidFont 加载 context 来自
     *  FontFamilyResolver（宿主根），不可信 —— loader 优先用本字段。 */
    val fontContext: Context?,
    override val weight: FontWeight,
) : AndroidFont(
    loadingStrategy = FontLoadingStrategy.Async,
    typefaceLoader = SelectedTypefaceLoader,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
) {
    override val style: FontStyle = FontStyle.Normal
}

private object SelectedTypefaceLoader : AndroidFont.TypefaceLoader {
    private val faces = LruCache<SelectedFont, Typeface>(12)

    override fun loadBlocking(context: Context, font: AndroidFont): Typeface? = null

    override suspend fun awaitLoad(context: Context, font: AndroidFont): Typeface = withContext(Dispatchers.IO) {
        val choice = font as SelectedFont
        val weight = choice.weight.weight
        // hotfix（resolver context 修复）：resolver 的 context 是宿主根 context（assets
        // 无模块字体），优先用字体对象携带的创建点 context（桌面 = moduleContext）。
        val effective = choice.fontContext ?: context
        synchronized(faces) {
            faces.get(choice) ?: run {
                // minSdk 33：上游 API<29 的 primaryTypeface 分支不搬运（Josefin 权重上限 700）。
                val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    SelectedTypefaceApi29.build(effective, choice.ids, weight)
                } else {
                    runCatching { launcherTypeface(context, weight.coerceIn(100, 700)) }
                        .getOrNull() ?: Typeface.create(Typeface.DEFAULT, weight, false)
                }
                faces.put(choice, result)
                result
            }
        }
    }
}

/** Keep API 29-only types and their caches out of the loader used on Android 9. */
@RequiresApi(Build.VERSION_CODES.Q)
private object SelectedTypefaceApi29 {
    private val buffers = object : LruCache<String, PlatformFont>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: PlatformFont) = value.buffer.capacity().coerceAtLeast(1)
    }

    // hotfix（appFont 桌面不生效排障）：解析链打点 —— 每个 id/来源/失败原因去重记录，
    // 静默兜底（.getOrNull() 吞异常）此前零可见性，装机"桌面不生效"无从定位。
    private val logged = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private fun logOnce(key: String, message: String) {
        if (logged.add(key)) ModLog.i("SelectedTypeface $message")
    }

    // Access is serialized by SelectedTypefaceLoader's face-cache lock.
    fun build(context: Context, ids: List<String>, weight: Int): Typeface {
        val families = ids.takeWhile { it != AppFont.System.id }.mapNotNull { id -> runCatching {
            val base = buffers.get(id) ?: when (id) {
                FontLibrary.Josefin -> PlatformFont.Builder(context.assets, PRIMARY_FONT_ASSET).build()
                AppFont.NotoSans.id -> PlatformFont.Builder(context.assets, "fonts/noto_sans.ttf").build()
                // 模块适配：preset 字体走模块 assets/fonts/（v2.2.1 hotfix 语义；上游 R.font
                // 经宿主 Resources 解析模块 ID 必抛 NotFoundException）。assetFile 值与 AppFont id 一致。
                else -> ClockPresetFont.entries.firstOrNull { it.assetFile == id }
                    ?.let { PlatformFont.Builder(context.assets, "fonts/" + it.assetFile + ".ttf").build() }
                    // 模块适配：导入字体双路解析（本进程私有目录 + 宿主镜像副本）。
                    ?: run {
                        val store = runCatching { ClockFontStore(context).file(id) }.getOrNull()
                        val mirror = com.homemode4miui.bridge.FontCrossProcess.mirroredFile(id)
                        // 排障打点：宿主进程亲自枚举两个候选目录 —— mirror install "ok" 但
                        // mirror=false（装机 23:00:19 实锤），需要看清 root cp 的落点与
                        // 宿主进程视角的目录内容是否一致（/data/data 符号链接 / 双开命名空间）。
                        val hostDirList = runCatching {
                            java.io.File(com.homemode4miui.bridge.FontCrossProcess.HOST_DIR_PUBLIC)
                                .list()?.joinToString(",")
                        }.getOrNull()
                        val user0DirList = runCatching {
                            java.io.File("/data/user/0/com.miui.home/files/clock_fonts")
                                .list()?.joinToString(",")
                        }.getOrNull()
                        logOnce("src:$id", "id=$id store=${store != null} mirror=${mirror != null} " +
                            "ctx=${context.javaClass.simpleName} filesDir=${runCatching { context.filesDir }.getOrNull()} " +
                            "hostDir=[$hostDirList] user0Dir=[$user0DirList]")
                        (store ?: mirror)?.let { PlatformFont.Builder(it).build() }
                    }
            }?.also { buffers.put(id, it) }
            base?.let {
                val safeWeight = if (id == FontLibrary.Josefin) weight.coerceAtMost(700) else weight
                // Font.Builder(Font) is API 31; the shared-buffer constructor works on 29.
                // hotfix（appFont 桌面不生效根因修复）：静态（非可变）字体上
                // PlatformFont.Builder.setFontVariationSettings 抛 IllegalArgumentException，
                // 被 runCatching 吞掉 → 全源失败静默回落。时钟路径走 framework
                // Typeface.Builder（静态字体忽略 variation）所以时钟生效、应用名/字母条
                // 不生效 —— 同一文件两条链行为分裂（装机实锤 2026-10-16）。
                // 降级：去掉 variation settings 重建，setWeight 仍生效（就近匹配/合成粗细）。
                val font = try {
                    PlatformFont.Builder(it.buffer).setTtcIndex(it.ttcIndex).setWeight(safeWeight)
                        .setSlant(PlatformFontStyle.FONT_SLANT_UPRIGHT)
                        .setFontVariationSettings("'wght' $safeWeight").build()
                } catch (variationError: Throwable) {
                    logOnce("novar:$id:$weight", "id=$id w=$weight static font (variation rejected), " +
                        "retry without variation: $variationError")
                    PlatformFont.Builder(it.buffer).setTtcIndex(it.ttcIndex).setWeight(safeWeight)
                        .setSlant(PlatformFontStyle.FONT_SLANT_UPRIGHT).build()
                }
                PlatformFontFamily.Builder(font).build()
            }
        }.onFailure { error ->
            if (logged.add("err:$id:$weight")) ModLog.e("SelectedTypeface id=$id w=$weight resolve failed", error)
        }.getOrNull() }
        if (families.isEmpty()) {
            logOnce("fallback:$ids:$weight", "ALL SOURCES FAILED -> default fallback ids=$ids w=$weight")
            return Typeface.create(Typeface.DEFAULT, weight, false)
        }
        logOnce("ok:$ids", "resolved ids=$ids families=${families.size} w=$weight")
        val builder = Typeface.CustomFallbackBuilder(families.first())
        families.drop(1).take(Typeface.CustomFallbackBuilder.getMaxCustomFallbackCount() - 1).forEach(builder::addCustomFallback)
        return builder.setStyle(PlatformFontStyle(weight, PlatformFontStyle.FONT_SLANT_UPRIGHT))
            .setSystemFallback("sans-serif").build()
    }
}

private data class LauncherFont(
    /** 创建点捕获的解析 context（桌面 = moduleContext）；null = 退回 resolver context。 */
    val fontContext: Context?,
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
        launcherTypeface((font as LauncherFont).fontContext ?: context, font.weight.weight)

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
