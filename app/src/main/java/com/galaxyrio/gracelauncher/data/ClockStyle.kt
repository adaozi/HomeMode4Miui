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

    // 模块定制（v2.9.68-clock-font-override，装机反馈"时钟不能自定义字体"）：放开上游
    // "preset 布局=固定字体设计"的约束 —— 所有布局都允许自定义字体；fontId 为空时
    // preset 布局仍回落各自 preset 字体（上游默认观感不变）。
    val allowsCustomFont: Boolean get() = true
}

data class ClockFaceStyle(
    val fontId: String? = null,
    val weight: Int = 300,
    val size: Int = 72,
    val letterSpacing: Int = -3,
    val showColon: Boolean = false,
    val separateDigits: Boolean = false,
    val hourMinuteSpacing: Int = 12,
    val fontShadow: Int = 0,
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
) {
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

    fun encode(): String = JSONObject().apply {
        put("layout", layout.name)
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
            ClockLayout.Week -> ClockFaceStyle(letterSpacing = 3)
            ClockLayout.Sacramento -> ClockFaceStyle(size = 96, letterSpacing = -6, separateDigits = true)
            ClockLayout.SacramentoWeek -> ClockFaceStyle(size = 96, letterSpacing = 0, separateDigits = true)
            ClockLayout.DoublePlaster -> ClockFaceStyle(size = 88, letterSpacing = 0)
            ClockLayout.Bokor, ClockLayout.Plaster, ClockLayout.Monoton -> ClockFaceStyle(letterSpacing = 0)
            ClockLayout.Lucky -> ClockFaceStyle(letterSpacing = -6, separateDigits = true)
        }

        fun decode(value: String?): ClockStyle = runCatching {
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
                ).normalized(layout)
            }
            val presets = json.optJSONObject("presets")
            ClockStyle(
                layout = ClockLayout.entries.firstOrNull { it.name == json.optString("layout") } ?: ClockLayout.SingleLine,
                singleLine = face(ClockLayout.SingleLine, json.optJSONObject("singleLine")),
                twoLines = face(ClockLayout.TwoLines, json.optJSONObject("twoLines")),
                presets = ClockLayout.entries.filter { presets?.has(it.name) == true }
                    .associateWith { face(it, presets?.optJSONObject(it.name)) },
            )
        }.getOrDefault(ClockStyle())
    }
}
