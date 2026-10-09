/* C 组（C2）：由 P6 裁剪版恢复为 v1.1.4 原版全量（GPL-3.0）—— selectedIcon/matchingIconName/designLayers* 随图标设计器回归 */
package com.galaxyrio.gracelauncher.data.icons

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache
import android.util.Xml
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import java.text.Collator
import java.time.LocalDate
import com.galaxyrio.gracelauncher.data.ItemIcon
import org.xmlpull.v1.XmlPullParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class IconPackInfo(val packageName: String, val label: String, val icon: ImageBitmap?)
enum class IconPackStatus { System, Ready, Unavailable }

class IconPackRepository(private val context: Context) {
    private val pm = context.packageManager
    private val cacheMutex = Mutex()
    private val cachedPacks = object : LinkedHashMap<String, Pair<String, LoadedIconPack>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, LoadedIconPack>>?): Boolean = size > 12
    }

    suspend fun iconNames(packageName: String): List<String> = withContext(Dispatchers.IO) {
        val pack = load(packageName) ?: return@withContext emptyList()
        val names = linkedSetOf<String>()
        // drawable.xml contains alternates which do not have an appfilter mapping.
        val resources = safely { pm.getResourcesForApplication(packageName) } ?: return@withContext emptyList()
        fun read(parser: XmlPullParser) {
            var nodes = 0
            while (parser.eventType != XmlPullParser.END_DOCUMENT && ++nodes < 200_000) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "item") {
                    parser.getAttributeValue(null, "drawable")?.takeIf { it.isNotBlank() }?.let(names::add)
                }
                parser.next()
            }
        }
        @SuppressLint("DiscouragedApi") val xml = resources.getIdentifier("drawable", "xml", packageName)
        if (xml != 0) safely { resources.getXml(xml).use(::read) }
        if (names.isEmpty()) safely {
            resources.assets.open("drawable.xml").use { stream -> read(Xml.newPullParser().apply { setInput(stream, null) }) }
        }
        names.addAll(pack.definition.icons.values)
        pack.definition.calendars.values.forEach { prefix -> (1..31).forEach { names.add("$prefix$it") } }
        names.toList()
    }

    internal suspend fun selectedIcon(choice: ItemIcon): PackIcon? = withContext(Dispatchers.IO) {
        if (choice.kind != "pack") null else load(choice.source)?.namedIcon(choice.name)
    }

    internal suspend fun matchingIconName(packageName: String, component: ComponentName): String? = withContext(Dispatchers.IO) {
        val pack = load(packageName) ?: return@withContext null
        pack.definition.candidates(component.flattenToString(), LocalDate.now().dayOfMonth)
            .firstOrNull { pack.namedIcon(it) != null }
    }

    suspend fun installedPacks(): List<IconPackInfo> = withContext(Dispatchers.IO) {
        val packages = linkedSetOf<String>()
        discoveryIntents().forEach { intent ->
            currentCoroutineContext().ensureActive()
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0).forEach { result ->
                result.activityInfo?.applicationInfo?.takeIf { it.enabled }?.packageName?.let(packages::add)
            }
        }
        val collator = Collator.getInstance()
        packages.mapNotNull { pkg ->
            safely {
                val app = pm.getApplicationInfo(pkg, 0)
                IconPackInfo(pkg, pm.getApplicationLabel(app).toString(),
                    safely { renderIcon(pm.getApplicationIcon(app)).asImageBitmap() })
            }
        }.sortedWith { left, right -> collator.compare(left.label, right.label) }
    }

    internal suspend fun load(packageName: String?): LoadedIconPack? = withContext(Dispatchers.IO) {
        if (packageName == null) return@withContext null
        cacheMutex.withLock {
            safely {
                val info = pm.getPackageInfo(packageName, 0)
                val config = context.resources.configuration
                val key = "$packageName:${info.lastUpdateTime}:${info.longVersionCode}:${config.densityDpi}:${config.uiMode}:${config.locales}"
                cachedPacks[packageName]?.takeIf { it.first == key }?.let { return@safely it.second }
                val resources = pm.getResourcesForApplication(packageName)
                val coroutine = currentCoroutineContext()
                val definition = readDefinition(resources, packageName) { coroutine.ensureActive() }
                    ?: return@safely null
                val themed = pm.queryIntentActivities(Intent(ThemedIconAction).setPackage(packageName), 0).isNotEmpty()
                LoadedIconPack(packageName, resources, definition, themed).also {
                    cachedPacks[packageName] = key to it
                }
            }
        }
    }

    @SuppressLint("DiscouragedApi") // Resource names belong to an installed pack, not our generated R class.
    private fun readDefinition(resources: Resources, pkg: String, checkCancelled: () -> Unit): IconPackDefinition? {
        // Android's resource manager includes installed density/language splits.
        // Try alternate locations if a pack ships an invalid/stale compiled XML.
        val xmlId = resources.getIdentifier("appfilter", "xml", pkg)
        if (xmlId != 0) safely { resources.getXml(xmlId).use { parseIconPack(it, checkCancelled) } }?.let { return it }
        val rawId = resources.getIdentifier("appfilter", "raw", pkg)
        if (rawId != 0) safely {
            resources.openRawResource(rawId).use { stream ->
                parseIconPack(Xml.newPullParser().apply { setInput(stream, null) }, checkCancelled)
            }
        }?.let { return it }
        return safely {
            resources.assets.open("appfilter.xml").use { stream ->
                parseIconPack(Xml.newPullParser().apply { setInput(stream, null) }, checkCancelled)
            }
        }
    }

    private fun discoveryIntents() = listOf(
        "org.adw.launcher.THEMES", "org.adw.ActivityStarter.THEMES", "com.novalauncher.THEME",
        "com.gau.go.launcherex.theme", "ch.deletescape.lawnchair.ICONPACK",
        "com.sonymobile.home.ICON_PACK", ThemedIconAction,
    ).map(::Intent) + listOf(
        "com.teslacoilsw.launcher.THEME", "com.anddoes.launcher.THEME", "com.fede.launcher.THEME_ICONPACK",
    ).map { Intent(Intent.ACTION_MAIN).addCategory(it) }

    companion object { private const val ThemedIconAction = "app.lawnchair.icons.THEMED_ICON" }
}

internal data class PackIcon(val bitmap: ImageBitmap, val monochrome: ImageBitmap? = null, val monochromeScale: Float = 1.4f)

/** Parsed once per package version. Drawable bitmaps are bounded and reused across refreshes. */
internal class LoadedIconPack(
    val packageName: String,
    private val resources: Resources,
    internal val definition: IconPackDefinition,
    private val themed: Boolean,
) {
    private val bitmaps = LruCache<String, Bitmap>(96)

    @Synchronized
    fun namedIcon(name: String): PackIcon? {
        val drawable = drawable(name) ?: return null
        val bitmap = bitmap(name) ?: return null
        val mono = if (drawable is AdaptiveIconDrawable && Build.VERSION.SDK_INT >= 33) drawable.monochrome else null
        val themedLayer = mono ?: if (themed) (drawable as? AdaptiveIconDrawable)?.foreground ?: drawable else null
        return PackIcon(bitmap.asImageBitmap(), safely { themedLayer?.let(::renderIcon)?.asImageBitmap() },
            if (drawable is AdaptiveIconDrawable) 1.4f else 0.76f)
    }

    @Synchronized
    fun designDrawable(name: String): Drawable? = drawable(name)

    @Synchronized
    fun designDrawableFor(component: ComponentName): Drawable? =
        definition.candidates(component.flattenToString(), LocalDate.now().dayOfMonth).firstNotNullOfOrNull(::drawable)

    @Synchronized
    fun designLayers(name: String, size: Int): IconLayers? = drawable(name)?.let { iconLayers(it, size, themed) }

    @Synchronized
    fun designLayersFor(component: ComponentName, size: Int): IconLayers? =
        designDrawableFor(component)?.let { iconLayers(it, size, themed) }

    @Synchronized
    fun iconFor(component: ComponentName, original: Drawable?, day: Int = LocalDate.now().dayOfMonth): PackIcon? {
        definition.candidates(component.flattenToString(), day).forEach { name ->
            val drawable = drawable(name) ?: return@forEach
            val bitmap = bitmap(name) ?: return@forEach
            val mono = if (drawable is AdaptiveIconDrawable && Build.VERSION.SDK_INT >= 33) drawable.monochrome else null
            val themedLayer = mono ?: if (themed) (drawable as? AdaptiveIconDrawable)?.foreground ?: drawable else null
            return PackIcon(bitmap.asImageBitmap(), safely { themedLayer?.let(::renderIcon)?.asImageBitmap() },
                if (drawable is AdaptiveIconDrawable) 1.4f else 0.76f)
        }
        if (original == null) return null
        val key = component.flattenToString()
        fun choose(names: List<String>): Bitmap? = names.takeIf { it.isNotEmpty() }
            ?.let { bitmap(it[Math.floorMod(key.hashCode(), it.size)]) }
        val back = choose(definition.backgrounds)
        val mask = choose(definition.masks)
        val upon = choose(definition.overlays)
        if (back == null && mask == null && upon == null) return null
        // ADW masks erase the opaque part of the mask from the scaled original,
        // not the background. Stable selection prevents colors changing on reload.
        return safely {
            PackIcon(composeFallbackIcon(renderIcon(original), back, mask, upon, definition.scale).asImageBitmap())
        }
    }

    @SuppressLint("DiscouragedApi") // Names come from the external pack's appfilter.xml.
    private fun drawable(name: String): Drawable? = safely {
        val clean = name.removePrefix("@drawable/").removePrefix("@mipmap/")
        if (!clean.matches(Regex("[a-zA-Z0-9_]+"))) return@safely null
        val id = resources.getIdentifier(clean, "drawable", packageName).takeIf { it != 0 }
            ?: resources.getIdentifier(clean, "mipmap", packageName)
        if (id == 0) null else resources.getDrawable(id, null).mutate()
    }

    private fun bitmap(name: String): Bitmap? = bitmaps[name] ?: safely {
        drawable(name)?.let(::renderIcon)?.also { bitmaps.put(name, it) }
    }
}

internal fun renderIcon(drawable: Drawable, size: Int = 144): Bitmap {
    val output = createBitmap(size, size)
    val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: size
    val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: size
    val scale = size.toFloat() / maxOf(width, height)
    val targetWidth = (width * scale).toInt().coerceAtLeast(1)
    val targetHeight = (height * scale).toInt().coerceAtLeast(1)
    val bounds = Rect(drawable.bounds)
    try {
        drawable.setBounds((size - targetWidth) / 2, (size - targetHeight) / 2,
            (size + targetWidth) / 2, (size + targetHeight) / 2)
        drawable.draw(Canvas(output))
    } finally { drawable.bounds = bounds }
    return output
}

internal fun composeFallbackIcon(original: Bitmap, back: Bitmap?, mask: Bitmap?, upon: Bitmap?, scale: Float): Bitmap {
    val size = original.width
    val output = createBitmap(size, size)
    val canvas = Canvas(output)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val full = Rect(0, 0, size, size)
    back?.let { canvas.drawBitmap(it, null, full, paint) }
    val layer = canvas.saveLayer(0f, 0f, size.toFloat(), size.toFloat(), null)
    val inset = (1f - scale) * size / 2f
    canvas.drawBitmap(original, null, RectF(inset, inset, size - inset, size - inset), paint)
    if (mask != null) {
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        canvas.drawBitmap(mask, null, full, paint)
        paint.xfermode = null
    }
    canvas.restoreToCount(layer)
    upon?.let { canvas.drawBitmap(it, null, full, paint) }
    return output
}

private inline fun <T> safely(block: () -> T): T? = try {
    block()
} catch (cancelled: CancellationException) { throw cancelled }
catch (_: Exception) { null }
