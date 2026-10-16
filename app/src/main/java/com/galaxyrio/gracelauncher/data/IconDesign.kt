package com.galaxyrio.gracelauncher.data

import org.json.JSONObject

enum class IconShape { None, Circle, Pebble, Square, Gem, Cookie }

enum class IconColorSource { Theme, FromApp, Fixed }

/** Store a source, not a snapshot, for theme and original-app colors. */
data class IconColor(
    val argb: Int,
    val theme: Boolean = false,
    val fromApp: Boolean = false,
    /** Relative HSB offsets: degrees for hue, percentage points for S/B. */
    val hueShift: Int = 0,
    val saturationShift: Int = 0,
    val brightnessShift: Int = 0,
) {
    val source: IconColorSource get() = when {
        theme -> IconColorSource.Theme
        fromApp -> IconColorSource.FromApp
        else -> IconColorSource.Fixed
    }
    fun normalized(): IconColor = copy(
        hueShift = if (source == IconColorSource.Fixed) 0 else hueShift.coerceIn(-180, 180),
        saturationShift = if (source == IconColorSource.Fixed) 0 else saturationShift.coerceIn(-100, 100),
        brightnessShift = if (source == IconColorSource.Fixed) 0 else brightnessShift.coerceIn(-100, 100),
    )
    internal fun json() = normalized().let {
        JSONObject().put("argb", it.argb).put("theme", it.theme).put("fromApp", it.fromApp)
            .put("hueShift", it.hueShift).put("saturationShift", it.saturationShift).put("brightnessShift", it.brightnessShift)
    }
    fun resolve(themeColor: Int, appColor: Int? = null): Int = when {
        theme -> adjustHsb(themeColor, hueShift, saturationShift, brightnessShift)
        fromApp -> adjustHsb(appColor ?: themeColor, hueShift, saturationShift, brightnessShift)
        else -> argb
    }
    companion object {
        val Theme = IconColor(0, theme = true)
        val FromApp = IconColor(0, fromApp = true)
        internal fun fromStored(argb: Int, theme: Boolean = false, dynamic: Boolean = false, fromApp: Boolean = false,
            hueShift: Int = 0, saturationShift: Int = 0, brightnessShift: Int = 0): IconColor = (when {
            // Retire the old dynamic preset without requiring a database migration.
            theme || dynamic -> Theme
            fromApp -> FromApp
            else -> IconColor(argb)
        }).copy(hueShift = hueShift, saturationShift = saturationShift, brightnessShift = brightnessShift).normalized()
        internal fun decode(value: JSONObject?) = value?.let {
            fromStored(it.optInt("argb"), it.optBoolean("theme"), it.optBoolean("dynamic"), it.optBoolean("fromApp"),
                it.optInt("hueShift"), it.optInt("saturationShift"), it.optInt("brightnessShift"))
        }
    }
}

/** Non-destructive parameters. Position is a percentage of the tray's width/height. */
data class IconDesign(
    val shape: IconShape = IconShape.None,
    val cookieSides: Int = 4,
    /** 0 is a boxier superellipse; 100 approaches a circle. */
    val pebbleRoundness: Int = DefaultPebbleRoundness,
    /** Radius as a percentage of half the rectangle's shorter side; 0 keeps sharp corners. */
    val squareCornerRadius: Int = 0,
    /** Width / height, fitted inside the existing square icon slot without stretching the symbol. */
    val squareAspectRatio: Float = 1f,
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
        squareAspectRatio = normalizeSquareAspectRatio(squareAspectRatio),
        x = x.takeIf { it.isFinite() }?.coerceIn(-50f, 50f) ?: 0f,
        y = y.takeIf { it.isFinite() }?.coerceIn(-50f, 50f) ?: 0f,
        size = size.coerceIn(25, 200),
        rotation = rotation.takeIf { it.isFinite() }?.coerceIn(-180f, 180f) ?: 0f,
        iconSize = iconSize.coerceIn(80, 150),
    )
    internal fun json(): JSONObject = normalized().let { value ->
        JSONObject().put("shape", value.shape.name).put("cookieSides", value.cookieSides)
            .put("pebbleRoundness", value.pebbleRoundness).put("squareCornerRadius", value.squareCornerRadius)
            .put("squareAspectRatio", value.squareAspectRatio)
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
                squareAspectRatio = it.optDouble("squareAspectRatio", 1.0).toFloat(),
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
