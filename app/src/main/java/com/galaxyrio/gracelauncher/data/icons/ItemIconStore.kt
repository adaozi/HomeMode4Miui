package com.galaxyrio.gracelauncher.data.icons

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.Drawable
import android.content.pm.LauncherApps
import androidx.core.content.ContextCompat
import com.homemode4miui.R
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Log
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.galaxyrio.gracelauncher.data.ItemIcon
import com.galaxyrio.gracelauncher.data.IconDesign
import com.galaxyrio.gracelauncher.data.LauncherApp
import com.galaxyrio.gracelauncher.data.PrivateSpaceFolderId
import com.galaxyrio.gracelauncher.data.LauncherSettings
import com.galaxyrio.gracelauncher.data.ThemeMode
import com.galaxyrio.gracelauncher.data.icons.isGraceButton
import com.galaxyrio.gracelauncher.data.launcherIconColors
import com.galaxyrio.gracelauncher.data.launcherWallpaperSeed
import com.materialkolor.PaletteStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Copy a bounded image into private storage; no persistent gallery/storage permission needed. */
class ItemIconStore(private val context: Context, private val packs: IconPackRepository) {
    private val directory get() = File(context.filesDir, "item_icons")
    private val images = LruCache<String, Bitmap>(12)
    private val themePalettes = LruCache<Triple<Int, Boolean, PaletteStyle>, Pair<Int, Int>>(8)
    private val appPalettes = LruCache<Triple<Int, Boolean, PaletteStyle>, Pair<Int, Int>>(256)

    private fun imageBitmap(name: String, size: Int): Bitmap? = runCatching {
        val key = "$name:$size"
        images[key] ?: ImageDecoder.decodeBitmap(ImageDecoder.createSource(imageFile(name))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSize(size, size)
        }.also { images.put(key, it) }
    }.getOrNull()

    suspend fun importImage(uri: Uri): ItemIcon = withContext(Dispatchers.IO) {
        val image = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val scale = minOf(1f, 512f / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
        }
        val side = minOf(image.width, image.height)
        val cropped = Bitmap.createBitmap(image, (image.width - side) / 2, (image.height - side) / 2, side, side)
        directory.mkdirs()
        val file = File(directory, "${UUID.randomUUID()}.png")
        try {
            file.outputStream().use { check(cropped.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            ItemIcon("image", file.name)
        } catch (error: Exception) { file.delete(); throw error }
        finally { if (cropped !== image) cropped.recycle(); image.recycle() }
    }

    private fun isDark(settings: LauncherSettings): Boolean = when (settings.darkMode) {
        ThemeMode.Dark -> true; ThemeMode.Light -> false
        ThemeMode.System -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    internal fun themeColors(settings: LauncherSettings): Pair<Int, Int> {
        // 模块侧根因修复：launcherThemeSeed 读静态 system_accent1_500 —— MIUI 上恒定
        // 且不随壁纸变。改 launcherWallpaperSeed（WallpaperColors 主色优先，降级链一致），
        // Grace 按钮 / themed 图标的光栅化配色与桌面主题种子同源。
        val seed = launcherWallpaperSeed(context, settings.useDynamicColors, settings.themeColor)
        return paletteColors(seed, isDark(settings), settings.paletteStyle)
    }

    private fun paletteColors(seed: Int, dark: Boolean, style: PaletteStyle): Pair<Int, Int> {
        val key = Triple(seed, dark, style)
        return themePalettes[key] ?: launcherIconColors(seed, dark, style).also { themePalettes.put(key, it) }
    }

    /** Cache base roles only: moving HSB sliders must not regenerate a scheme. */
    internal fun appColors(seed: Int?, settings: LauncherSettings): Pair<Int, Int>? {
        if (seed == null) return null
        val key = Triple(seed, isDark(settings), settings.paletteStyle)
        return appPalettes[key] ?: launcherIconColors(key.first, key.second, key.third).also { appPalettes.put(key, it) }
    }

    internal fun dynamicColors(settings: LauncherSettings): Pair<Int, Int> {
        val seed = launcherWallpaperSeed(context, true, settings.themeColor)
        return paletteColors(seed, isDark(settings), settings.paletteStyle)
    }

    internal suspend fun layers(app: LauncherApp, choice: ItemIcon, settings: LauncherSettings, size: Int = 384): IconLayers? = safelyRender(app, null) {
        withContext(Dispatchers.IO) {
            // 上游同步 v1.1.8 S8b（ItemIconStore.kt:99-101）：Grace 按钮虚拟项不走
            // 系统图标解析——system/theme/symbol 全部由 GraceButtonIcon 直接渲染
            // （Suggestion glyph + 托盘色 + 圆形 mask），否则落 app.icon=null 链路。
            if (app.isGraceButton && choice.kind in setOf("system", "theme", "symbol")) {
                return@withContext GraceButtonIcon.layers(context, choice, size, themeColors(settings))
            }
            if (app.folderId != null && choice.kind in setOf("system", "theme")) {
                val colors = themeColors(settings)
                val glyph = ContextCompat.getDrawable(context, if (app.folderId == PrivateSpaceFolderId) R.drawable.ms_lock
                    else if (app.isAppStack) R.drawable.ms_steppers else R.drawable.ms_folder)
                    ?.mutate() ?: return@withContext null
                glyph.setTint(colors.second)
                return@withContext iconLayers(AdaptiveIconDrawable(ColorDrawable(colors.first), InsetDrawable(glyph, 0.22f)), size, themed = true)
            }
            val packed = when (choice.kind) {
                "theme" -> if (app.shortcut == null) settings.enabledIconPackPackages.firstNotNullOfOrNull {
                    packs.load(it)?.designLayersFor(app.componentName, size)
                } else null
                "pack" -> packs.load(choice.source)?.let {
                    if (choice.name.isEmpty()) it.designLayersFor(app.componentName, size) else it.designLayers(choice.name, size)
                }
                else -> null
            }
            if (packed != null) return@withContext packed
            // 上游同步 v1.1.8 S8b（ItemIconStore.kt:123-125）：按钮 + 图标包来源回退
            // 默认渲染（图标包形状不适合 FAB；pack 分支不落入系统图标解析）。
            if (app.isGraceButton && choice.kind == "pack") {
                return@withContext GraceButtonIcon.layers(context, ItemIcon.System, size, themeColors(settings))
            }
            val drawable = if (app.shortcut == null && choice.kind in setOf("theme", "system", "pack")) systemDrawable(app) else null
            if (drawable != null) return@withContext iconLayers(drawable, size)
            val bitmap = if (choice.kind == "image") imageBitmap(choice.source, size) else null
            (bitmap ?: (app.shortcut?.icon ?: app.icon)?.asAndroidBitmap())?.let { IconLayers(it) }
        }
    }

    // 上游同步 1.2.0（S8 性能）：渲染结果缓存。签名 = app.key + 图标选择 + 全部设置 + 日期，
    // 用 settings.hashCode() 覆盖所有影响渲染的设置字段（宁可少命中，也不返回陈旧图标）。
    private val renderedApps = IconSnapshotCache<String, LauncherApp>(capacity = 256,
        maxBytes = 16 * 1024 * 1024, sizeOf = { iconBitmapBytes(it.icon, it.monochromeIcon) })

    suspend fun apply(app: LauncherApp, choice: ItemIcon?, settings: LauncherSettings = LauncherSettings()): LauncherApp = withContext(Dispatchers.IO) {
        val renderKey = "${app.key}|${choice?.hashCode()}|${settings.hashCode()}|${java.time.LocalDate.now()}"
        renderedApps.get(renderKey, renderKey)?.let { return@withContext it }
        val result = safelyRender(app, app) {
            if (choice == null) return@safelyRender app
            choice.design?.let { design ->
                // 上游同步 v1.1.8 S8b（ItemIconStore.kt:137）：按钮位图 256px（54dp FAB 高密度）。
                val original = layers(app, choice, settings, if (app.isGraceButton) 256 else 144) ?: return@safelyRender app
                val colors = dynamicColors(settings)
                val theme = themeColors(settings)
                val fromApp = if (design.background?.fromApp == true || design.foreground?.fromApp == true)
                    appColors(app.originalIconColor, settings) else null
                return@safelyRender app.copy(icon = renderDesignedIcon(original, design, colors.first, colors.second,
                    theme.first, theme.second, fromApp).asImageBitmap(),
                    monochromeIcon = null, iconPackPackage = when (choice.kind) {
                        "theme" -> app.themeIconPackPackage
                        "pack" -> choice.source
                        else -> null
                    })
            }
            val icon = when (choice.kind) {
                "pack" -> packs.selectedIcon(choice)
                "image" -> imageBitmap(choice.source, 144)?.let { PackIcon(it.asImageBitmap()) }
                "system" -> if (app.folderId != null) null else if (app.shortcut != null) app.shortcut.icon?.let { PackIcon(it) } else runCatching {
                    val drawable = systemDrawable(app) ?: return@safelyRender app
                    val mono = if (Build.VERSION.SDK_INT >= 33) (drawable as? AdaptiveIconDrawable)?.monochrome else null
                    PackIcon(renderIcon(drawable).asImageBitmap(), mono?.let { renderIcon(it).asImageBitmap() })
                }.getOrNull()
                else -> null
            }
            if (icon == null) app else app.copy(icon = icon.bitmap, monochromeIcon = icon.monochrome,
                monochromeScale = icon.monochromeScale, iconPackPackage = choice.source.takeIf { choice.kind == "pack" })
        }.copy(iconDisplay = choice?.display)
        renderedApps.put(renderKey, renderKey, result)
        result
    }

    /** One invalid third-party drawable must not abort the launcher or the live preview. */
    private inline fun <T> safelyRender(app: LauncherApp, fallback: T, render: () -> T): T = try {
        render()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w("ItemIconStore", "Unable to render icon for ${app.key}; keeping the existing icon", error)
        fallback
    }

    private fun systemDrawable(app: LauncherApp): Drawable? = runCatching {
        if (app.user == null) context.packageManager.getActivityIcon(app.componentName)
        else context.getSystemService(LauncherApps::class.java).getActivityList(app.packageName, app.user)
            .firstOrNull { it.componentName == app.componentName }?.getIcon(0)
    }.getOrNull()

    /** Sources come from the enabled packs; single designs override the shared parameters. */
    suspend fun applyDesign(app: LauncherApp, special: ItemIcon?, bulk: ItemIcon?, settings: LauncherSettings,
        themedIcons: Boolean = true): LauncherApp {
        val defaults = IconDesign.defaults(themedIcons)
        val shared = bulk?.design?.withThemeDefaults(defaults) ?: defaults
        val choice = (special ?: ItemIcon.Theme).let {
            it.copy(design = (it.design?.withThemeDefaults(shared) ?: shared).copy(iconSize = shared.iconSize))
        }
        return apply(app, choice, settings)
    }

    suspend fun deleteImage(choice: ItemIcon?) = withContext(Dispatchers.IO) {
        if (choice?.kind == "image") runCatching {
            images.snapshot().keys.filter { it.startsWith("${choice.source}:") }.forEach(images::remove)
            imageFile(choice.source).delete()
        }
        // 图源已删，渲染缓存必须失效
        renderedApps.clear()
        Unit
    }

    private fun imageFile(name: String): File {
        require(name.matches(Regex("[a-fA-F0-9-]{36}\\.png")))
        return File(directory, name)
    }
}
