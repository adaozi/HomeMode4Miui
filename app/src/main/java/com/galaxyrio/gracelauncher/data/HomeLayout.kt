/*
 * 移植自 GraceLauncher v1.1.4（GPL-3.0，https://github.com/Galaxy-rio/GraceLauncher）
 * 原路径：app/src/main/java/com/galaxyrio/gracelauncher/data/HomeLayout.kt
 * P3 移植：逐字拷贝，未改动。launcher_settings.homeLayoutJson 的编解码模型，
 *   P4 widget 布局（widgetId/provider/height）复用同一 JSON 格式。
 */
package com.galaxyrio.gracelauncher.data

import org.json.JSONObject

/** One hosted widget. Zero height means the provider's default; zero offset means home default. */
data class HomeLayout(
    val topOffsetDp: Float = 0f,
    val widgetId: Int = -1,
    val widgetProvider: String? = null,
    val widgetLabel: String? = null,
    val widgetHeightDp: Int = 0,
) {
    val hasWidget: Boolean get() = widgetId >= 0

    fun withoutWidget() = copy(widgetId = -1, widgetProvider = null, widgetLabel = null, widgetHeightDp = 0)

    fun encode(): String = JSONObject().apply {
        put("topOffsetDp", topOffsetDp.takeIf { it.isFinite() }?.coerceIn(-1200f, 1200f) ?: 0f)
        put("widgetId", widgetId)
        put("widgetProvider", widgetProvider ?: JSONObject.NULL)
        put("widgetLabel", widgetLabel ?: JSONObject.NULL)
        put("widgetHeightDp", widgetHeightDp.coerceIn(0, 1600))
    }.toString()

    companion object {
        fun decode(value: String?): HomeLayout = runCatching {
            val data = JSONObject(value ?: "{}")
            HomeLayout(
                topOffsetDp = data.optDouble("topOffsetDp", 0.0).toFloat().takeIf { it.isFinite() }?.coerceIn(-1200f, 1200f) ?: 0f,
                widgetId = data.optInt("widgetId", -1),
                widgetProvider = data.optString("widgetProvider").takeUnless { it.isBlank() || it == "null" },
                widgetLabel = data.optString("widgetLabel").takeUnless { it.isBlank() || it == "null" },
                widgetHeightDp = data.optInt("widgetHeightDp", 0).coerceIn(0, 1600),
            )
        }.getOrDefault(HomeLayout())
    }
}
