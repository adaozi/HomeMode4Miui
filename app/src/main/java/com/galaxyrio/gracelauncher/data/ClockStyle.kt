/*
 * 移植自 GraceLauncher v1.1.4（GPL-3.0，https://github.com/Galaxy-rio/GraceLauncher）
 * 原路径：app/src/main/java/com/galaxyrio/gracelauncher/data/ClockStyle.kt
 * P1 移植：逐字拷贝，未改动。（encode/decode 的 JSON 序列化被 P1 的 SP 偏好
 *   落地直接复用；P3 引 Room 后 LauncherSettingsRepository 沿用同一格式。）
 */
package com.galaxyrio.gracelauncher.data

import org.json.JSONObject

enum class ClockPresetFont { Sacramento, Bokor, Plaster, Monoton, LuckiestGuy }

enum class ClockLayout(
    val stacked: Boolean = false,
    val week: Boolean = false,
    val presetFont: ClockPresetFont? = null,
) {
    SingleLine,
    TwoLines(stacked = true),
    Week(week = true),
    Sacramento(presetFont = ClockPresetFont.Sacramento),
    Bokor(presetFont = ClockPresetFont.Bokor),
    Plaster(presetFont = ClockPresetFont.Plaster),
    DoublePlaster(stacked = true, presetFont = ClockPresetFont.Plaster),
    Monoton(presetFont = ClockPresetFont.Monoton),
    Lucky(presetFont = ClockPresetFont.LuckiestGuy),
    SacramentoWeek(week = true, presetFont = ClockPresetFont.Sacramento);

    val allowsCustomFont: Boolean get() = presetFont == null
    val baseLayout: ClockLayout get() = when {
        week -> Week
        stacked -> TwoLines
        else -> SingleLine
    }
}

data class ClockVisibility(
    val showClock: Boolean = true,
    val showWeekday: Boolean = true,
    val showDate: Boolean = true,
    val showBattery: Boolean = true,
    val showWeather: Boolean = true,
)

enum class ClockColorPart {
    HourTens, HourOnes, MinuteTens, MinuteOnes, Weekday, Month, Day, Battery, Weather, Agenda, WeatherIcon, BatteryIcon,
    SecondTens, SecondOnes, Task,
}

private const val ClockColorVersion = 2

data class ClockFaceStyle(
    val fontId: String? = null,
    val weight: Int = 300,
    val size: Int = 72,
    val letterSpacing: Int = -3,
    val showColon: Boolean = false,
    val separateDigits: Boolean = false,
    val hourMinuteSpacing: Int = 12,
    val fontShadow: Int = 0,
    val showLeadingZero: Boolean = true,
    val showSeconds: Boolean = false,
    val equalWidthDigits: Boolean = false,
    val centerDigits: Boolean = false,
    val useLatinLetters: Boolean = false,
) {
    fun maxWeight(layout: ClockLayout) = if (layout.presetFont == null && fontId == null) 700 else 900

    fun normalized(layout: ClockLayout = ClockLayout.SingleLine) = copy(
        fontId = fontId.takeIf { layout.allowsCustomFont },
        weight = weight.coerceIn(100, maxWeight(layout)),
        size = size.coerceIn(32, 144),
        letterSpacing = letterSpacing.coerceIn(-16, 16),
        hourMinuteSpacing = hourMinuteSpacing.coerceIn(-16, 64),
        fontShadow = fontShadow.coerceIn(0, 24),
    )
}

/** Each layout keeps its own draft and saved typography. */
data class ClockStyle(
    val layout: ClockLayout = ClockLayout.SingleLine,
    val singleLine: ClockFaceStyle = defaults(ClockLayout.SingleLine),
    val twoLines: ClockFaceStyle = defaults(ClockLayout.TwoLines),
    val presets: Map<ClockLayout, ClockFaceStyle> = emptyMap(),
    val visibility: ClockVisibility = ClockVisibility(),
    val colors: Map<ClockColorPart, IconColor> = emptyMap(),
    val regions: ClockRegions? = null,
) {
    val effectiveVisibility: ClockVisibility get() = if (layout.presetFont != null) ClockVisibility() else visibility

    val face: ClockFaceStyle get() = when (layout) {
        ClockLayout.SingleLine -> singleLine
        ClockLayout.TwoLines -> twoLines
        else -> presets[layout] ?: defaults(layout)
    }.normalized(layout)

    fun withFace(face: ClockFaceStyle): ClockStyle {
        val safe = face.normalized(layout)
        return when (layout) {
            ClockLayout.SingleLine -> copy(singleLine = safe)
            ClockLayout.TwoLines -> copy(twoLines = safe)
            else -> copy(presets = presets + (layout to safe))
        }
    }

    fun withoutFonts(ids: Set<String>): ClockStyle {
        fun ClockFaceStyle.clean() = if (fontId in ids) copy(fontId = null) else this
        return copy(singleLine = singleLine.clean(), twoLines = twoLines.clean(), presets = presets.mapValues { it.value.clean() },
            regions = regions?.withoutFonts(ids))
    }

    fun resolvedRegions(calendarAboveClock: Boolean = false): ClockRegions =
        regions?.normalized() ?: ClockRegions.fromLegacy(this, calendarAboveClock)

    fun withRegions(regions: ClockRegions = resolvedRegions()): ClockStyle = copy(regions = regions.normalized())

    /** Selecting a preset replaces the regions; merely opening the editor never does. */
    fun selectPreset(layout: ClockLayout): ClockStyle {
        val preset = copy(layout = layout, regions = null)
        return preset.applyPresetToBase().withRegions(ClockRegions.fromPreset(preset))
    }

    /** Copy the active preset into its editable base slot, keeping every preset draft. */
    fun applyPresetToBase(applyVisibility: Boolean = true): ClockStyle {
        val fontId = when (layout.presetFont ?: return this) {
            ClockPresetFont.Sacramento -> AppFont.Sacramento.id
            ClockPresetFont.Bokor -> AppFont.Bokor.id
            ClockPresetFont.Plaster -> AppFont.Plaster.id
            ClockPresetFont.Monoton -> AppFont.Monoton.id
            ClockPresetFont.LuckiestGuy -> AppFont.LuckiestGuy.id
        }
        return copy(layout = layout.baseLayout,
            visibility = if (applyVisibility) effectiveVisibility else visibility)
            .withFace(face.copy(fontId = fontId))
    }

    fun encode(): String = JSONObject().apply {
        put("layout", layout.name)
        put("visibility", JSONObject().apply {
            put("showClock", visibility.showClock)
            put("showWeekday", visibility.showWeekday)
            put("showDate", visibility.showDate)
            put("showBattery", visibility.showBattery)
            put("showWeather", visibility.showWeather)
        })
        put("colorVersion", ClockColorVersion)
        put("colors", JSONObject().apply {
            colors.forEach { (part, color) -> put(part.name, color.json()) }
        })
        regions?.let { put("regions", it.json()) }
        fun encodeFace(face: ClockFaceStyle, layout: ClockLayout) = JSONObject().apply {
            val safe = face.normalized(layout)
            put("fontId", safe.fontId ?: JSONObject.NULL)
            put("weight", safe.weight)
            put("size", safe.size)
            put("letterSpacing", safe.letterSpacing)
            put("hourMinuteSpacing", safe.hourMinuteSpacing)
            put("fontShadow", safe.fontShadow)
            put("showColon", safe.showColon)
            put("separateDigits", safe.separateDigits)
            put("showLeadingZero", safe.showLeadingZero)
            put("showSeconds", safe.showSeconds)
            put("equalWidthDigits", safe.equalWidthDigits)
            put("centerDigits", safe.centerDigits)
            put("useLatinLetters", safe.useLatinLetters)
        }
        // Preserve the original keys so existing clocks keep their saved appearance.
        put("singleLine", encodeFace(singleLine, ClockLayout.SingleLine))
        put("twoLines", encodeFace(twoLines, ClockLayout.TwoLines))
        put("presets", JSONObject().apply {
            presets.forEach { (layout, face) -> put(layout.name, encodeFace(face, layout)) }
        })
    }.toString()

    companion object {
        fun defaults(layout: ClockLayout) = when (layout) {
            ClockLayout.SingleLine -> ClockFaceStyle(letterSpacing = 3)
            ClockLayout.TwoLines -> ClockFaceStyle(size = 88, letterSpacing = 3)
            ClockLayout.Week -> ClockFaceStyle(letterSpacing = 3, useLatinLetters = true)
            ClockLayout.Sacramento -> ClockFaceStyle(size = 96, letterSpacing = -6, separateDigits = true)
            ClockLayout.SacramentoWeek -> ClockFaceStyle(size = 96, letterSpacing = 0, separateDigits = true, useLatinLetters = true)
            ClockLayout.DoublePlaster -> ClockFaceStyle(size = 88, letterSpacing = 0)
            ClockLayout.Bokor, ClockLayout.Plaster, ClockLayout.Monoton -> ClockFaceStyle(letterSpacing = 0)
            ClockLayout.Lucky -> ClockFaceStyle(letterSpacing = -6, separateDigits = true)
        }

        fun decode(value: String?, legacyShowBattery: Boolean = true): ClockStyle = runCatching {
            val json = JSONObject(value ?: "{}")
            fun face(layout: ClockLayout, data: JSONObject?): ClockFaceStyle {
                val defaults = defaults(layout)
                if (data == null) return defaults
                return ClockFaceStyle(
                    fontId = data.optString("fontId").takeIf { it.isNotBlank() && it != "null" },
                    weight = data.optInt("weight", defaults.weight),
                    size = data.optInt("size", defaults.size),
                    letterSpacing = data.optInt("letterSpacing", defaults.letterSpacing),
                    hourMinuteSpacing = data.optInt("hourMinuteSpacing", defaults.hourMinuteSpacing),
                    fontShadow = data.optInt("fontShadow", defaults.fontShadow),
                    showColon = data.optBoolean("showColon", defaults.showColon),
                    separateDigits = data.optBoolean("separateDigits", defaults.separateDigits),
                    showLeadingZero = data.optBoolean("showLeadingZero", defaults.showLeadingZero),
                    showSeconds = data.optBoolean("showSeconds", defaults.showSeconds),
                    equalWidthDigits = data.optBoolean("equalWidthDigits", defaults.equalWidthDigits),
                    centerDigits = data.optBoolean("centerDigits", defaults.centerDigits),
                    useLatinLetters = data.optBoolean("useLatinLetters", defaults.useLatinLetters),
                ).normalized(layout)
            }
            val presets = json.optJSONObject("presets")
            val visibility = json.optJSONObject("visibility")
            val colors = json.optJSONObject("colors")
            val decodedColors = ClockColorPart.entries.mapNotNull { part ->
                IconColor.decode(colors?.optJSONObject(part.name))?.let { part to it }
            }.toMap().toMutableMap()
            // Old battery icons shared the percentage color. Migrate that choice once;
            // new saves may deliberately omit BatteryIcon to use the automatic color.
            if (json.optInt("colorVersion", 1) < ClockColorVersion && colors?.has(ClockColorPart.BatteryIcon.name) != true) {
                decodedColors[ClockColorPart.Battery]?.let { decodedColors[ClockColorPart.BatteryIcon] = it }
            }
            ClockStyle(
                layout = ClockLayout.entries.firstOrNull { it.name == json.optString("layout") } ?: ClockLayout.SingleLine,
                singleLine = face(ClockLayout.SingleLine, json.optJSONObject("singleLine")),
                twoLines = face(ClockLayout.TwoLines, json.optJSONObject("twoLines")),
                presets = ClockLayout.entries.filter { presets?.has(it.name) == true }
                    .associateWith { face(it, presets?.optJSONObject(it.name)) },
                visibility = if (visibility == null) ClockVisibility(showBattery = legacyShowBattery) else ClockVisibility(
                    showClock = visibility.optBoolean("showClock", true),
                    showWeekday = visibility.optBoolean("showWeekday", true),
                    showDate = visibility.optBoolean("showDate", true),
                    showBattery = visibility.optBoolean("showBattery", true),
                    showWeather = visibility.optBoolean("showWeather", true),
                ),
                colors = decodedColors,
                regions = ClockRegions.decode(json.optJSONObject("regions")),
            )
        }.getOrDefault(ClockStyle(visibility = ClockVisibility(showBattery = legacyShowBattery)))
    }
}
