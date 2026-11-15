package com.galaxyrio.gracelauncher.data

import org.json.JSONArray
import org.json.JSONObject

data class FontColorEntry(val id: String, val name: String, val color: IconColor)

enum class WallpaperBrightness { Auto, Dark, Light }

/** Stable IDs let component overrides follow edits to a shared font color. */
data class FontColors(
    val entries: List<FontColorEntry> = defaults(),
    val wallpaperBrightness: WallpaperBrightness = WallpaperBrightness.Auto,
    val wallpaperColorAccess: Boolean = false,
) {
    fun color(id: String): IconColor? = if (id == WallpaperId) defaultColorValue
        else entries.firstOrNull { it.id == id }?.color

    val defaultColor: IconColor get() = color(DefaultId) ?: defaultColorValue
    val themeColor: IconColor get() = color(ThemeId) ?: themeColorValue

    fun normalized(): FontColors {
        val valid = entries.mapNotNull { entry ->
            val id = entry.id.trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
            if (id == WallpaperId) return@mapNotNull null
            val name = entry.name.trim()
            if (id != DefaultId && id != ThemeId && name.isEmpty()) return@mapNotNull null
            // Palette values are colors, not links to other entries; this prevents cycles.
            entry.copy(id = id, name = if (id == DefaultId || id == ThemeId) "" else name,
                color = entry.color.copy(fontColorId = null).normalized())
        }.distinctBy { it.id }
        val defaults = defaults()
        return copy(entries = listOf(
            valid.firstOrNull { it.id == DefaultId } ?: defaults[0],
            valid.firstOrNull { it.id == ThemeId } ?: defaults[1],
            defaults[2],
        ) + valid.filter { it.id != DefaultId && it.id != ThemeId })
    }

    fun encode(): String = normalized().let { value ->
        JSONObject().put("version", 1).put("entries", JSONArray().apply {
            value.entries.forEach { entry ->
                put(JSONObject().put("id", entry.id).put("name", entry.name).put("color", entry.color.json()))
            }
        }).put("wallpaperBrightness", value.wallpaperBrightness.name)
            .put("wallpaperColorAccess", value.wallpaperColorAccess).toString()
    }

    companion object {
        const val DefaultId = "default"
        const val ThemeId = "theme"
        const val WallpaperId = "wallpaper"
        const val PrimaryId = "primary"
        const val SecondaryId = "secondary"
        const val TertiaryId = "tertiary"
        private val defaultColorValue = IconColor(0, materialRole = MaterialColorRole.OnSurface)
        private val themeColorValue = IconColor(0, materialRole = MaterialColorRole.OnPrimary)

        fun defaults(): List<FontColorEntry> = listOf(
            FontColorEntry(DefaultId, "", defaultColorValue),
            FontColorEntry(ThemeId, "", themeColorValue),
            FontColorEntry(WallpaperId, "", defaultColorValue),
        ) + themeDefaults()

        private fun themeDefaults(): List<FontColorEntry> = listOf(
            FontColorEntry(PrimaryId, "Primary", IconColor(0, materialRole = MaterialColorRole.Primary)),
            FontColorEntry(SecondaryId, "Secondary", IconColor(0, materialRole = MaterialColorRole.Secondary)),
            FontColorEntry(TertiaryId, "Tertiary", IconColor(0, materialRole = MaterialColorRole.Tertiary)),
        )

        fun decode(json: String?): FontColors = runCatching {
            if (json == null) return@runCatching FontColors()
            val objectValue = if (json.trimStart().startsWith("[")) null else JSONObject(json)
            val array = objectValue?.optJSONArray("entries") ?: if (objectValue == null) JSONArray(json) else JSONArray()
            val brightness = objectValue?.opt("wallpaperBrightness") as? String
            val savedEntries = (0 until array.length()).mapNotNull { index ->
                val entry = array.optJSONObject(index) ?: return@mapNotNull null
                val id = entry.opt("id") as? String ?: return@mapNotNull null
                val color = IconColor.decode(entry.optJSONObject("color")) ?: return@mapNotNull null
                FontColorEntry(id, entry.opt("name") as? String ?: "", color)
            }
            // Seed older palettes once; the marker keeps subsequent deletions intentional.
            val entries = if ((objectValue?.optInt("version", 0) ?: 0) < 1) {
                val ids = savedEntries.map { it.id.trim() }.toSet()
                savedEntries + themeDefaults().filterNot { it.id in ids }
            } else savedEntries
            FontColors(entries, wallpaperBrightness = WallpaperBrightness.entries.firstOrNull {
                it.name == brightness
            } ?: WallpaperBrightness.Auto,
                wallpaperColorAccess = objectValue?.optBoolean("wallpaperColorAccess", false) ?: false).normalized()
        }.getOrElse { FontColors() }
    }
}
