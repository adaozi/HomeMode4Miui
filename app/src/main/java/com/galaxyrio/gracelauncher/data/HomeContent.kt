package com.galaxyrio.gracelauncher.data

import android.content.ComponentName

/*
 * 上游同步 1.2.0（S6 主页排序）：主页内容模型与顺序工具。
 * 模块化差异：owner key 的包名用模块自己的 com.homemode4miui；
 * 本模块主页只承载单个 widget（homeLayout），故 orderedHomeItems 的 widgets 参数通常为空。
 */

const val HomeContentKey = "com.homemode4miui/home-content"
fun homeContentOwner() = LauncherApp(requireNotNull(ComponentName.unflattenFromString(HomeContentKey)), "Home", null)

enum class GraceWidget(val key: String) {
    Clock("grace:clock"), Calendar("grace:calendar"), Media("grace:media"), Baseline("grace:baseline");

    fun enabled(settings: LauncherSettings): Boolean = when (this) {
        Clock -> settings.clockEnabled
        Calendar -> settings.calendarAgenda
        Media -> settings.mediaPlayer
        Baseline -> settings.homeLayout.baselineEnabled
    }

    fun setEnabled(settings: LauncherSettings, value: Boolean): LauncherSettings = when (this) {
        Clock -> settings.copy(clockEnabled = value)
        Calendar -> settings.copy(calendarAgenda = value)
        Media -> settings.copy(mediaPlayer = value)
        Baseline -> settings.copy(homeLayout = settings.homeLayout.copy(baselineEnabled = value))
    }

    companion object {
        fun fromKey(key: String) = entries.firstOrNull { it.key == key }
    }
}

/** Old data is interpreted without rewriting IDs, replacing widgets, or resetting favorites. */
internal fun legacyHomeOrder(settings: LauncherSettings): List<String> = buildList {
    if (settings.calendarAboveClock) { add(GraceWidget.Calendar.key); add(GraceWidget.Baseline.key); add(GraceWidget.Clock.key) }
    else { add(GraceWidget.Clock.key); add(GraceWidget.Baseline.key); add(GraceWidget.Calendar.key) }
    if (settings.homeLayout.hasWidget) add("widget:${settings.homeLayout.widgetId}")
    add(GraceWidget.Media.key)
}

internal fun orderedHomeItems(settings: LauncherSettings, favorites: List<String>, widgets: List<PopupItem>): List<PopupItem> {
    val legacy = settings.homeLayout.takeIf { it.hasWidget }?.let { PopupItem("widget:${it.widgetId}", it) }
    val contents = buildList {
        GraceWidget.entries.filter { it.enabled(settings) }.forEach { add(PopupItem(it.key)) }
        if (legacy != null) add(legacy)
        addAll(widgets.filter { it.widget != null })
        addAll(favorites.map { PopupItem(it) })
    }.associateBy { it.key }
    return ((settings.homeLayout.homeOrder ?: legacyHomeOrder(settings)) + contents.keys)
        .distinct().mapNotNull(contents::get)
}

/** Both feature banners and the favorites picker use this same activation rule. */
internal fun synchronizeGraceWidgetOrder(previous: LauncherSettings, next: LauncherSettings): LauncherSettings {
    val added = GraceWidget.entries.filter { !it.enabled(previous) && it.enabled(next) }.map { it.key }
    if (added.isEmpty()) return next
    val order = (added + (next.homeLayout.homeOrder ?: legacyHomeOrder(previous))).distinct()
    return next.copy(homeLayout = next.homeLayout.copy(homeOrder = order))
}

/** Keep temporarily unavailable favorites in place when reordering the visible mixed list. */
internal fun mergeHomeOrder(requested: List<String>, previous: List<String>): List<String> {
    val order = requested.distinct()
    val moving = order.toSet()
    val replacements = order.iterator()
    return (previous.distinct().map { if (it in moving && replacements.hasNext()) replacements.next() else it } +
        replacements.asSequence().toList()).distinct()
}
