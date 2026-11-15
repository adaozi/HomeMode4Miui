package com.galaxyrio.gracelauncher.data

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb
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
    val materialRole: MaterialColorRole? = null,
    val fontColorId: String? = null,
) {
    val source: IconColorSource get() = when {
        theme || materialRole != null || fontColorId != null -> IconColorSource.Theme
        fromApp -> IconColorSource.FromApp
        else -> IconColorSource.Fixed
    }
    fun normalized(): IconColor = copy(
        argb = if (materialRole != null || fontColorId != null) 0 else argb,
        theme = theme && materialRole == null && fontColorId == null,
        fromApp = fromApp && materialRole == null && fontColorId == null,
        materialRole = materialRole.takeIf { fontColorId == null },
        hueShift = if (source == IconColorSource.Fixed) 0 else hueShift.coerceIn(-180, 180),
        saturationShift = if (source == IconColorSource.Fixed) 0 else saturationShift.coerceIn(-100, 100),
        brightnessShift = if (source == IconColorSource.Fixed) 0 else brightnessShift.coerceIn(-100, 100),
    )
    internal fun json() = normalized().let {
        JSONObject().put("argb", it.argb).put("theme", it.theme).put("fromApp", it.fromApp)
            .put("hueShift", it.hueShift).put("saturationShift", it.saturationShift).put("brightnessShift", it.brightnessShift)
            .put("materialRole", it.materialRole?.name).put("fontColorId", it.fontColorId)
    }
    fun resolve(themeColor: Int, appColor: Int? = null, colorScheme: ColorScheme? = null,
        fontColors: FontColors? = null, wallpaperColor: Int? = null): Int = when {
        fontColorId != null -> adjustHsb(
            if (fontColorId == FontColors.WallpaperId && wallpaperColor != null) wallpaperColor
            else fontColors?.color(fontColorId)?.copy(fontColorId = null)?.resolve(themeColor, appColor, colorScheme) ?: themeColor,
            hueShift, saturationShift, brightnessShift)
        materialRole != null -> adjustHsb(colorScheme?.let { materialRole.color(it).toArgb() } ?: themeColor,
            hueShift, saturationShift, brightnessShift)
        theme -> adjustHsb(themeColor, hueShift, saturationShift, brightnessShift)
        fromApp -> adjustHsb(appColor ?: themeColor, hueShift, saturationShift, brightnessShift)
        else -> argb
    }
    companion object {
        val Theme = IconColor(0, theme = true)
        val FromApp = IconColor(0, fromApp = true)
        internal fun fromStored(argb: Int, theme: Boolean = false, dynamic: Boolean = false, fromApp: Boolean = false,
            hueShift: Int = 0, saturationShift: Int = 0, brightnessShift: Int = 0,
            materialRole: MaterialColorRole? = null, fontColorId: String? = null): IconColor = (when {
            !fontColorId.isNullOrBlank() -> IconColor(0, fontColorId = fontColorId)
            materialRole != null -> IconColor(0, materialRole = materialRole)
            // Retire the old dynamic preset without requiring a database migration.
            theme || dynamic -> Theme
            fromApp -> FromApp
            else -> IconColor(argb)
        }).copy(hueShift = hueShift, saturationShift = saturationShift, brightnessShift = brightnessShift).normalized()
        internal fun decode(value: JSONObject?) = value?.let {
            fromStored(it.optInt("argb"), it.optBoolean("theme"), it.optBoolean("dynamic"), it.optBoolean("fromApp"),
                it.optInt("hueShift"), it.optInt("saturationShift"), it.optInt("brightnessShift"),
                MaterialColorRole.entries.firstOrNull { role -> role.name == it.optString("materialRole") },
                it.optString("fontColorId").takeUnless { id -> id.isBlank() || id == "null" })
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
    /** Shared policy only; individual shortcut designs can still override their own properties. */
    val applyToShortcuts: Boolean = false,
    /** Flat images use dark ink by default; layered/monochrome sources ignore inversion. */
    val invertBackgroundDetection: Boolean = false,
    /** Old saved designs lack theming switches; resolve them against the current shared defaults. */
    val inheritThemeDefaults: Boolean = false,
    /** Null is a full (or legacy) design. A set fixes only these properties against All. */
    val overriddenFields: Set<String>? = null,
) {
    fun appliesTo(isShortcut: Boolean): Boolean = !isShortcut || applyToShortcuts

    fun itemDefaults(isShortcut: Boolean): IconDesign = if (appliesTo(isShortcut)) this
        else defaults().copy(iconSize = iconSize)

    fun withThemeDefaults(defaults: IconDesign): IconDesign {
        overriddenFields?.let { fields ->
            fun <T> pick(key: String, value: T, inherited: T): T = if (key in fields) value else inherited
            return defaults.copy(
                shape = pick("shape", shape, defaults.shape),
                cookieSides = pick("cookieSides", cookieSides, defaults.cookieSides),
                pebbleRoundness = pick("pebbleRoundness", pebbleRoundness, defaults.pebbleRoundness),
                squareCornerRadius = pick("squareCornerRadius", squareCornerRadius, defaults.squareCornerRadius),
                squareAspectRatio = pick("squareAspectRatio", squareAspectRatio, defaults.squareAspectRatio),
                background = pick("background", background, defaults.background),
                foreground = pick("foreground", foreground, defaults.foreground),
                addTray = pick("addTray", addTray, defaults.addTray),
                x = pick("x", x, defaults.x), y = pick("y", y, defaults.y),
                size = pick("size", size, defaults.size), rotation = pick("rotation", rotation, defaults.rotation),
                themeIcons = pick("themeIcons", themeIcons, defaults.themeIcons),
                themeUnsupportedIcons = pick("themeUnsupportedIcons", themeUnsupportedIcons, defaults.themeUnsupportedIcons),
                invertBackgroundDetection = pick("invertBackgroundDetection", invertBackgroundDetection, defaults.invertBackgroundDetection),
                // Display size is always shared, never a per-item override.
                inheritThemeDefaults = false, overriddenFields = null,
            )
        }
        return if (!inheritThemeDefaults) this else copy(
            background = background ?: defaults.background,
            foreground = foreground ?: defaults.foreground,
            themeIcons = themeIcons || defaults.themeIcons,
            themeUnsupportedIcons = themeUnsupportedIcons || defaults.themeUnsupportedIcons,
            invertBackgroundDetection = invertBackgroundDetection || defaults.invertBackgroundDetection,
            inheritThemeDefaults = false,
        )
    }

    /** Called on Save, not on slider ticks. Matching properties resume following All. */
    fun overridesFrom(defaults: IconDesign): IconDesign? {
        val value = withThemeDefaults(defaults).normalized()
        val base = defaults.normalized()
        val fields = buildSet {
            if (value.shape != base.shape) add("shape")
            if (value.cookieSides != base.cookieSides) add("cookieSides")
            if (value.pebbleRoundness != base.pebbleRoundness) add("pebbleRoundness")
            if (value.squareCornerRadius != base.squareCornerRadius) add("squareCornerRadius")
            if (value.squareAspectRatio != base.squareAspectRatio) add("squareAspectRatio")
            if (value.background != base.background) add("background")
            if (value.foreground != base.foreground) add("foreground")
            if (value.addTray != base.addTray) add("addTray")
            if (value.x != base.x) add("x")
            if (value.y != base.y) add("y")
            if (value.size != base.size) add("size")
            if (value.rotation != base.rotation) add("rotation")
            if (value.themeIcons != base.themeIcons) add("themeIcons")
            if (value.themeUnsupportedIcons != base.themeUnsupportedIcons) add("themeUnsupportedIcons")
            if (value.invertBackgroundDetection != base.invertBackgroundDetection) add("invertBackgroundDetection")
        }
        if (fields.isEmpty()) return null
        // Canonicalize the unused values so an encode/decode round trip is equal.
        return value.copy(overriddenFields = fields).withThemeDefaults(IconDesign()).copy(overriddenFields = fields)
    }

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
        background = background?.normalized(), foreground = foreground?.normalized(),
    )
    internal fun json(): JSONObject = normalized().let { value ->
        JSONObject().put("shape", value.shape.name).put("cookieSides", value.cookieSides)
            .put("pebbleRoundness", value.pebbleRoundness).put("squareCornerRadius", value.squareCornerRadius)
            .put("squareAspectRatio", value.squareAspectRatio)
            .put("x", value.x).put("y", value.y).put("size", value.size).put("addTray", value.addTray)
            .put("rotation", value.rotation)
            .put("iconSize", value.iconSize).put("themeIcons", value.themeIcons)
            .put("themeUnsupportedIcons", value.themeUnsupportedIcons)
            .put("applyToShortcuts", value.applyToShortcuts)
            .put("invertBackgroundDetection", value.invertBackgroundDetection)
            .put("inheritThemeDefaults", value.inheritThemeDefaults).apply {
                value.background?.let { put("background", it.json()) }
                value.foreground?.let { put("foreground", it.json()) }
            }.onlyIconOverrides(value.overriddenFields)
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
                themeIcons = it.optBoolean("themeIcons", !it.optBoolean("overrides") && (it.has("background") || it.has("foreground"))),
                themeUnsupportedIcons = it.optBoolean("themeUnsupportedIcons", !it.optBoolean("overrides") && (it.has("background") || it.has("foreground"))),
                applyToShortcuts = it.optBoolean("applyToShortcuts"),
                invertBackgroundDetection = it.optBoolean("invertBackgroundDetection"),
                inheritThemeDefaults = !it.optBoolean("overrides") && it.optBoolean("inheritThemeDefaults", !it.has("themeIcons")),
                overriddenFields = it.iconOverrideFields(),
            ).normalized()
        }
    }
}

/** Explicit nulls matter: restoring original artwork is different from inheriting a color. */
internal fun JSONObject.onlyIconOverrides(fields: Set<String>?): JSONObject = if (fields == null) this else
    JSONObject().put("overrides", true).also { result ->
        fields.sorted().forEach { field -> result.put(field, opt(field) ?: JSONObject.NULL) }
    }

internal fun JSONObject.iconOverrideFields(): Set<String>? = if (optBoolean("overrides"))
    keys().asSequence().filter { it != "overrides" }.toSet() else null
