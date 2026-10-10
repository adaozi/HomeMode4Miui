package com.galaxyrio.gracelauncher.data

import org.json.JSONObject

/** Desktop rail settings, stored alongside the rest of the list appearance. */
data class AlphabetAppearance(
    val fontId: String? = null,
    val fontColor: IconColor? = null,
    val fontSize: Int = 14,
    val indicatorShape: IconShape = IconShape.Circle,
    val pebbleRoundness: Int = IconDesign.DefaultPebbleRoundness,
    val squareCornerRadius: Int = 0,
    val cookieSides: Int = 4,
    /** null anchors the bottom and uses the original 18dp step, irrespective of letter count. */
    val topPercent: Int? = null,
    val bottomPercent: Int = 85,
    val freeMovement: Boolean = false,
) {
    fun normalized(): AlphabetAppearance {
        val top = topPercent?.coerceIn(0, 99)
        return copy(fontSize = fontSize.coerceIn(10, 28), topPercent = top,
            bottomPercent = bottomPercent.coerceIn((top ?: 0) + 1, 100),
            pebbleRoundness = pebbleRoundness.coerceIn(0, 100),
            squareCornerRadius = squareCornerRadius.coerceIn(0, 100),
            cookieSides = cookieSides.takeIf { it in IconDesign.CookieSides } ?: 4)
    }

    /** The caller includes the home and utility markers in entryCount. */
    internal fun layout(viewportHeightDp: Float, entryCount: Int): AlphabetRailLayout {
        if (!viewportHeightDp.isFinite() || viewportHeightDp <= 0f || entryCount <= 0) return AlphabetRailLayout(0f, 0f)
        val safe = normalized()
        val bottom = viewportHeightDp * safe.bottomPercent / 100f
        val height = safe.topPercent?.let { bottom - viewportHeightDp * it / 100f }
            // Only compress when the entries physically cannot fit above the chosen bottom.
            ?: (entryCount * DefaultStepDp).coerceAtMost(bottom)
        return AlphabetRailLayout(bottom - height, height)
    }

    internal fun json(): JSONObject = normalized().let { value -> JSONObject().apply {
        put("fontId", value.fontId ?: JSONObject.NULL); put("fontColor", value.fontColor?.json())
        put("fontSize", value.fontSize); put("indicatorShape", value.indicatorShape.name)
        put("pebbleRoundness", value.pebbleRoundness); put("squareCornerRadius", value.squareCornerRadius)
        put("cookieSides", value.cookieSides); put("topPercent", value.topPercent ?: JSONObject.NULL)
        put("bottomPercent", value.bottomPercent); put("freeMovement", value.freeMovement)
    } }

    companion object {
        internal const val DefaultStepDp = 18f
        internal fun decode(value: JSONObject?): AlphabetAppearance {
            val defaults = AlphabetAppearance()
            return value?.let {
                val bottom = it.optInt("bottomPercent", defaults.bottomPercent)
                // Existing numeric ranges, including the old defaults, stay unchanged.
                val top = if (!it.has("topPercent") || it.isNull("topPercent")) null else it.optInt("topPercent", 35)
                AlphabetAppearance(
                    fontId = it.optString("fontId").takeIf { id -> id.isNotBlank() && id != "null" },
                    fontColor = IconColor.decode(it.optJSONObject("fontColor")),
                    fontSize = it.optInt("fontSize", defaults.fontSize),
                    indicatorShape = IconShape.entries.firstOrNull { shape -> shape.name == it.optString("indicatorShape") }
                        ?: defaults.indicatorShape,
                    pebbleRoundness = it.optInt("pebbleRoundness", defaults.pebbleRoundness),
                    squareCornerRadius = it.optInt("squareCornerRadius", defaults.squareCornerRadius),
                    cookieSides = it.optInt("cookieSides", defaults.cookieSides),
                    topPercent = top,
                    bottomPercent = bottom,
                    freeMovement = it.optBoolean("freeMovement", defaults.freeMovement),
                ).normalized()
            } ?: defaults
        }
    }
}

internal data class AlphabetRailLayout(val topDp: Float, val heightDp: Float)
