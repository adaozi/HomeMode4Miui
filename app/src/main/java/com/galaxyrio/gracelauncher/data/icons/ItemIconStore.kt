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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import com.galaxyrio.gracelauncher.data.ItemIcon
import com.galaxyrio.gracelauncher.data.IconDesign
import com.galaxyrio.gracelauncher.data.LauncherApp
import com.galaxyrio.gracelauncher.data.PrivateSpaceFolderId
import com.galaxyrio.gracelauncher.data.LauncherSettings
import com.galaxyrio.gracelauncher.data.ThemeMode
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Copy a bounded image into private storage; no persistent gallery/storage permission needed. */
class ItemIconStore(private val context: Context, private val packs: IconPackRepository) {
    private val directory get() = File(context.filesDir, "item_icons")
    private val images = LruCache<String, Bitmap>(12)
    private val themePalettes = LruCache<Pair<Int, Boolean>, Pair<Int, Int>>(4)

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
        if (settings.useDynamicColors && Build.VERSION.SDK_INT >= 31) return dynamicColors(settings)
        return seedColors(settings)
    }

    private fun seedColors(settings: LauncherSettings): Pair<Int, Int> {
        val key = settings.themeColor to isDark(settings)
        return themePalettes[key] ?: dynamicColorScheme(seedColor = Color(key.first), isDark = key.second,
            style = PaletteStyle.TonalSpot, specVersion = ColorSpec.SpecVersion.SPEC_2025).let {
            it.primaryContainer.toArgb() to it.onPrimaryContainer.toArgb()
        }.also { themePalettes.put(key, it) }
    }

    internal fun dynamicColors(settings: LauncherSettings): Pair<Int, Int> {
        val dark = isDark(settings)
        if (Build.VERSION.SDK_INT >= 31) {
            val scheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            return scheme.primaryContainer.toArgb() to scheme.onPrimaryContainer.toArgb()
        }
        return seedColors(settings)
    }

    internal suspend fun layers(app: LauncherApp, choice: ItemIcon, settings: LauncherSettings, size: Int = 384): IconLayers? = safelyRender(app, null) {
        withContext(Dispatchers.IO) {
            if (app.folderId != null && choice.kind in setOf("system", "theme")) {
                val colors = themeColors(settings)
                val glyph = ContextCompat.getDrawable(context, if (app.folderId == PrivateSpaceFolderId) R.drawable.ms_lock else R.drawable.ms_folder)
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
            val drawable = if (app.shortcut == null && choice.kind in setOf("theme", "system", "pack")) systemDrawable(app) else null
            if (drawable != null) return@withContext iconLayers(drawable, size)
            val bitmap = if (choice.kind == "image") imageBitmap(choice.source, size) else null
            (bitmap ?: (app.shortcut?.icon ?: app.icon)?.asAndroidBitmap())?.let { IconLayers(it) }
        }
    }

    suspend fun apply(app: LauncherApp, choice: ItemIcon?, settings: LauncherSettings = LauncherSettings()): LauncherApp = withContext(Dispatchers.IO) {
        safelyRender(app, app) {
            if (choice == null) return@withContext app
            choice.design?.let { design ->
                val original = layers(app, choice, settings, 144) ?: return@withContext app
                val colors = dynamicColors(settings)
                val theme = themeColors(settings)
                return@withContext app.copy(icon = renderDesignedIcon(original, design, colors.first, colors.second, theme.first, theme.second).asImageBitmap(),
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
                    val drawable = systemDrawable(app) ?: return@withContext app
                    val mono = if (Build.VERSION.SDK_INT >= 33) (drawable as? AdaptiveIconDrawable)?.monochrome else null
                    PackIcon(renderIcon(drawable).asImageBitmap(), mono?.let { renderIcon(it).asImageBitmap() })
                }.getOrNull()
                else -> null
            }
            if (icon == null) app else app.copy(icon = icon.bitmap, monochromeIcon = icon.monochrome,
                monochromeScale = icon.monochromeScale, iconPackPackage = choice.source.takeIf { choice.kind == "pack" })
        }
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
        Unit
    }

    private fun imageFile(name: String): File {
        require(name.matches(Regex("[a-fA-F0-9-]{36}\\.png")))
        return File(directory, name)
    }
}
