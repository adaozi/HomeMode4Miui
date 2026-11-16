/*
 * 上游同步 P3（2026-11-16）：主页内容模型对齐 1.2.4 —— Clock + Calendar 合并为 ClockCalendar
 * 单一区块（日历/日期/天气/电量/任务都是它的内部区域，由 ClockStyle.regions 驱动）。
 * 模块差异：owner key 包名用模块自己的 com.homemode4miui（历史数据兼容）。
 */
package com.galaxyrio.gracelauncher.data

import android.content.ComponentName

const val HomeContentKey = "com.homemode4miui/home-content"
fun homeContentOwner() = LauncherApp(requireNotNull(ComponentName.unflattenFromString(HomeContentKey)), "Home", null)

enum class GraceWidget(val key: String) {
    ClockCalendar("grace:clock-calendar"), Media("grace:media"), Baseline("grace:baseline");

    fun enabled(settings: LauncherSettings): Boolean = when (this) {
        ClockCalendar -> settings.homeLayout.clockCalendarEnabled &&
            (settings.clockEnabled || settings.calendarAgenda || settings.weatherEnabled || settings.calendar.tasksOrg.enabled)
        Media -> settings.mediaPlayer
        Baseline -> settings.homeLayout.baselineEnabled
    }

    fun setEnabled(settings: LauncherSettings, value: Boolean): LauncherSettings = when (this) {
        ClockCalendar -> {
            val restoreParts = value && !settings.clockEnabled && !settings.calendarAgenda && !settings.weatherEnabled && !settings.calendar.tasksOrg.enabled
            settings.copy(
                homeLayout = settings.homeLayout.copy(clockCalendarEnabled = value),
                clockEnabled = settings.clockEnabled || restoreParts,
                calendarAgenda = settings.calendarAgenda || restoreParts,
            )
        }
        Media -> settings.copy(mediaPlayer = value)
        Baseline -> settings.copy(homeLayout = settings.homeLayout.copy(baselineEnabled = value))
    }

    companion object {
        fun fromKey(key: String) = entries.firstOrNull { it.key == normalizedGraceWidgetKey(key) }
    }
}

private fun normalizedGraceWidgetKey(key: String): String = when (key) {
    "grace:clock", "grace:calendar" -> GraceWidget.ClockCalendar.key
    else -> key
}

/** Collapse the old separate clock/calendar slots at their first position. */
internal fun normalizeHomeOrder(keys: List<String>): List<String> = keys.map(::normalizedGraceWidgetKey).distinct()

/** Keep the legacy widget/favorites arrangement; calendar order is now internal to the combined widget. */
internal fun legacyHomeOrder(settings: LauncherSettings): List<String> = buildList {
    add(GraceWidget.ClockCalendar.key)
    add(GraceWidget.Baseline.key)
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
    return normalizeHomeOrder((settings.homeLayout.homeOrder ?: legacyHomeOrder(settings)) + contents.keys)
        .mapNotNull(contents::get)
}

/** Both feature banners and the homepage editor use this same activation rule. */
internal fun synchronizeGraceWidgetOrder(previous: LauncherSettings, next: LauncherSettings): LauncherSettings {
    val partActivated = (!previous.clockEnabled && next.clockEnabled) ||
        (!previous.calendarAgenda && next.calendarAgenda) || (!previous.weatherEnabled && next.weatherEnabled) ||
        (!previous.calendar.tasksOrg.enabled && next.calendar.tasksOrg.enabled)
    val activated = if (partActivated) next.copy(homeLayout = next.homeLayout.copy(clockCalendarEnabled = true)) else next
    val added = GraceWidget.entries.filter { !it.enabled(previous) && it.enabled(activated) }.map { it.key }
    val order = if (added.isEmpty()) activated.homeLayout.homeOrder?.let(::normalizeHomeOrder)
        else normalizeHomeOrder(added + (activated.homeLayout.homeOrder ?: legacyHomeOrder(previous)))
    return activated.copy(homeLayout = activated.homeLayout.copy(homeOrder = order))
}

/** Keep temporarily unavailable favorites in place when reordering the visible mixed list. */
internal fun mergeHomeOrder(requested: List<String>, previous: List<String>): List<String> {
    val order = normalizeHomeOrder(requested)
    val moving = order.toSet()
    val replacements = order.iterator()
    return (normalizeHomeOrder(previous).map { if (it in moving && replacements.hasNext()) replacements.next() else it } +
        replacements.asSequence().toList()).distinct()
}
