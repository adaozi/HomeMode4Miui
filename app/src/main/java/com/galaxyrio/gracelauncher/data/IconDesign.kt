package com.galaxyrio.gracelauncher.data

import org.json.JSONObject

enum class IconShape { None, Circle, Pebble, Square, Gem, Cookie }

/** Dynamic colors remain dynamic after saving, including wallpaper changes. */
data class IconColor(val argb: Int, val dynamic: Boolean = false, val theme: Boolean = false) {
    internal fun json() = JSONObject().put("argb", argb).put("dynamic", dynamic).put("theme", theme)
    fun resolve(dynamicColor: Int, themeColor: Int): Int = when {
        theme -> themeColor
        dynamic -> dynamicColor
        else -> argb
    }
    companion object {
        val Theme = IconColor(0, theme = true)
        internal fun decode(value: JSONObject?) = value?.let { IconColor(it.optInt("argb"), it.optBoolean("dynamic"), it.optBoolean("theme")) }
    }
}

/** Non-destructive parameters. Position is a percentage of the tray's width/height. */
data class IconDesign(
    val shape: IconShape = IconShape.None,
    val cookieSides: Int = 4,
    /** 0 is a boxier superellipse; 100 approaches a circle. */
    val pebbleRoundness: Int = DefaultPebbleRoundness,
    /** Radius as a percentage of half the icon width; 0 keeps sharp corners. */
    val squareCornerRadius: Int = 0,
    val background: IconColor? = null,
    val foreground: IconColor? = null,
    val addTray: Boolean = false,
    val x: Float = 0f,
    val y: Float = 0f,
    /** Foreground size relative to the tray; iconSize controls the launcher display size. */
    val size: Int = 100,
    /** Clockwise symbol rotation in degrees, independent of the tray. */
    val rotation: Float = 0f,
    val iconSize: Int = 100,
    val themeIcons: Boolean = false,
    val themeUnsupportedIcons: Boolean = false,
    /** Flat images use dark ink by default; layered/monochrome sources ignore inversion. */
    val invertBackgroundDetection: Boolean = false,
    /** Old saved designs lack theming switches; resolve them against the current shared defaults. */
    val inheritThemeDefaults: Boolean = false,
) {
    fun withThemeDefaults(defaults: IconDesign): IconDesign = if (!inheritThemeDefaults) this else copy(
        background = background ?: defaults.background,
        foreground = foreground ?: defaults.foreground,
        themeIcons = themeIcons || defaults.themeIcons,
        themeUnsupportedIcons = themeUnsupportedIcons || defaults.themeUnsupportedIcons,
        invertBackgroundDetection = invertBackgroundDetection || defaults.invertBackgroundDetection,
        inheritThemeDefaults = false,
    )

    fun normalized() = copy(
        cookieSides = cookieSides.takeIf { it in CookieSides } ?: 4,
        pebbleRoundness = pebbleRoundness.coerceIn(0, 100),
        squareCornerRadius = squareCornerRadius.coerceIn(0, 100),
        x = x.takeIf { it.isFinite() }?.coerceIn(-50f, 50f) ?: 0f,
        y = y.takeIf { it.isFinite() }?.coerceIn(-50f, 50f) ?: 0f,
        size = size.coerceIn(25, 200),
        rotation = rotation.takeIf { it.isFinite() }?.coerceIn(-180f, 180f) ?: 0f,
        iconSize = iconSize.coerceIn(80, 150),
    )
    internal fun json(): JSONObject = normalized().let { value ->
        JSONObject().put("shape", value.shape.name).put("cookieSides", value.cookieSides)
            .put("pebbleRoundness", value.pebbleRoundness).put("squareCornerRadius", value.squareCornerRadius)
            .put("x", value.x).put("y", value.y).put("size", value.size).put("addTray", value.addTray)
            .put("rotation", value.rotation)
            .put("iconSize", value.iconSize).put("themeIcons", value.themeIcons)
            .put("themeUnsupportedIcons", value.themeUnsupportedIcons)
            .put("invertBackgroundDetection", value.invertBackgroundDetection)
            .put("inheritThemeDefaults", value.inheritThemeDefaults).apply {
                value.background?.let { put("background", it.json()) }
                value.foreground?.let { put("foreground", it.json()) }
            }
    }
    companion object {
        const val DefaultPebbleRoundness = 85
        val CookieSides = listOf(4, 6, 7, 9, 12)
        fun defaults(themedIcons: Boolean = false) = IconDesign(
            background = IconColor.Theme, foreground = IconColor.Theme, themeIcons = themedIcons,
        )
        internal fun decode(value: JSONObject?) = value?.let {
            IconDesign(
                shape = IconShape.entries.firstOrNull { shape -> shape.name == it.optString("shape") } ?: IconShape.None,
                cookieSides = it.optInt("cookieSides", 4),
                pebbleRoundness = it.optInt("pebbleRoundness", DefaultPebbleRoundness),
                squareCornerRadius = it.optInt("squareCornerRadius", 0),
                background = IconColor.decode(if (it.optBoolean("addTray") && it.has("trayColor"))
                    it.optJSONObject("trayColor") else it.optJSONObject("background")),
                foreground = IconColor.decode(it.optJSONObject("foreground")),
                addTray = it.optBoolean("addTray"),
                x = it.optDouble("x", 0.0).toFloat(), y = it.optDouble("y", 0.0).toFloat(),
                size = it.optInt("size", 100),
                rotation = it.optDouble("rotation", 0.0).toFloat(),
                iconSize = it.optInt("iconSize", 100),
                // Preserve colors from designs saved before the theming switches existed.
                themeIcons = it.optBoolean("themeIcons", it.has("background") || it.has("foreground")),
                themeUnsupportedIcons = it.optBoolean("themeUnsupportedIcons", it.has("background") || it.has("foreground")),
                invertBackgroundDetection = it.optBoolean("invertBackgroundDetection"),
                inheritThemeDefaults = it.optBoolean("inheritThemeDefaults", !it.has("themeIcons")),
            ).normalized()
        }
    }
}
