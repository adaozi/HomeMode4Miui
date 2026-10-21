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
    // 上游同步 1.2.0（widget 可用性/尺寸）：弹层内 widget 的呈现参数 ——
    // 桌面网格自己有尺寸概念，这里的只作用于应用/文件夹弹层里的 widget 槽。
    val popupWidthPercent: Int = 100,
    val popupFullWidth: Boolean = false,
    val popupBackground: Boolean = false,
    val popupContentScale: Int = 100,
    // 上游同步 1.2.0（S6 主页排序）：主页区块顺序（clock/baseline/calendar/widget/media/收藏…）。
    // null = 沿用旧的固定排布，直到用户第一次调整顺序。
    val homeOrder: List<String>? = null,
    val baselineEnabled: Boolean = true,
    /** 旧主页原点偏移，首次编辑时捕获一次以保持既有视觉位置。 */
    val baselineOffsetDp: Float? = null,
) {
    val hasWidget: Boolean get() = widgetId >= 0

    fun withoutWidget() = HomeLayout(topOffsetDp = topOffsetDp, homeOrder = homeOrder,
        baselineEnabled = baselineEnabled, baselineOffsetDp = baselineOffsetDp)

    fun encode(): String = JSONObject().apply {
        put("topOffsetDp", topOffsetDp.takeIf { it.isFinite() }?.coerceIn(-1200f, 1200f) ?: 0f)
        put("widgetId", widgetId)
        put("widgetProvider", widgetProvider ?: JSONObject.NULL)
        put("widgetLabel", widgetLabel ?: JSONObject.NULL)
        put("widgetHeightDp", widgetHeightDp.coerceIn(0, 1600))
        put("popupWidthPercent", popupWidthPercent.coerceIn(20, 100))
        put("popupFullWidth", popupFullWidth)
        put("popupBackground", popupBackground)
        put("popupContentScale", popupContentScale.coerceIn(50, 150))
        homeOrder?.let { put("homeOrder", org.json.JSONArray(it.distinct())) }
        put("baselineEnabled", baselineEnabled)
        baselineOffsetDp?.takeIf { it.isFinite() }?.let { put("baselineOffsetDp", it.coerceIn(0f, 1600f)) }
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
                popupWidthPercent = data.optInt("popupWidthPercent", 100).coerceIn(20, 100),
                popupFullWidth = data.optBoolean("popupFullWidth", false),
                popupBackground = data.optBoolean("popupBackground", false),
                popupContentScale = data.optInt("popupContentScale", 100).coerceIn(50, 150),
                homeOrder = data.optJSONArray("homeOrder")?.let { array ->
                    (0 until array.length()).mapNotNull { (array.opt(it) as? String)?.takeIf(String::isNotBlank) }.distinct()
                },
                baselineEnabled = data.optBoolean("baselineEnabled", true),
                baselineOffsetDp = data.optDouble("baselineOffsetDp", Double.NaN).toFloat()
                    .takeIf { it.isFinite() }?.coerceIn(0f, 1600f),
            )
        }.getOrDefault(HomeLayout())
    }
}
