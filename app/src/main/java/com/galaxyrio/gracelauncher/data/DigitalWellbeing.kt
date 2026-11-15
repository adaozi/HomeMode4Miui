package com.galaxyrio.gracelauncher.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

enum class UsageDisplay { Day, Week, Month, Percentage }
enum class UsageRefresh { Daily, Weekly, Never }

internal fun singleUsageDisplay(display: Set<UsageDisplay>): Set<UsageDisplay> = buildSet {
    // Older selections may contain multiple periods; retain the first in day/week/month order.
    UsageDisplay.entries.firstOrNull { it != UsageDisplay.Percentage && it in display }?.let { add(it) }
    if (UsageDisplay.Percentage in display) add(UsageDisplay.Percentage)
}

data class DigitalWellbeingSettings(
    val enabled: Boolean = false,
    val showUsage: Boolean = true,
    val sortApps: Boolean = true,
    val display: Set<UsageDisplay> = setOf(UsageDisplay.Day),
    val refresh: UsageRefresh = UsageRefresh.Daily,
    val hideZeroValues: Boolean = true,
) {
    fun encode(): String = JSONObject().put("enabled", enabled).put("showUsage", showUsage)
        .put("sortApps", sortApps).put("display", JSONArray(singleUsageDisplay(display).map { it.name }))
        .put("refresh", refresh.name).put("hideZeroValues", hideZeroValues).toString()

    companion object {
        fun decode(value: String?): DigitalWellbeingSettings = runCatching {
            val json = JSONObject(value ?: "{}")
            val selected = json.optJSONArray("display")
            DigitalWellbeingSettings(json.optBoolean("enabled", false), json.optBoolean("showUsage", true),
                json.optBoolean("sortApps", true), if (selected == null) setOf(UsageDisplay.Day) else
                    singleUsageDisplay((0 until selected.length()).mapNotNull { i -> UsageDisplay.entries.firstOrNull { it.name == selected.optString(i) } }.toSet()),
                UsageRefresh.entries.firstOrNull { it.name == json.optString("refresh") } ?: UsageRefresh.Daily,
                json.optBoolean("hideZeroValues", true))
        }.getOrDefault(DigitalWellbeingSettings())
    }
}

data class UsageSnapshot(
    val hasAccess: Boolean = false,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val updatedAt: Long = 0,
    val day: Map<String, Long> = emptyMap(),
    val week: Map<String, Long> = emptyMap(),
    val month: Map<String, Long> = emptyMap(),
    /** Saved opening counts, not durations. Replaced only when the sorting snapshot is due. */
    val ranking: Map<String, Long> = emptyMap(),
    val rankedAt: Long = 0,
    /** Latest seven-local-day opening counts; independent of the saved drawer ordering. */
    val openCounts: Map<String, Long> = emptyMap(),
) {
    val totalDay: Long = day.values.sum()
}

internal data class UsageReadPlan(val labels: Boolean, val opens: Boolean, val ranking: Boolean)

/** Category counts still require Digital Wellbeing, independently of labels and drawer sorting. */
internal fun usageReadPlan(settings: DigitalWellbeingSettings, needOpenCounts: Boolean, forceRanking: Boolean,
    rankedAt: Long, now: Long, zone: ZoneId): UsageReadPlan {
    val ranking = settings.enabled && (forceRanking ||
        (settings.sortApps && usageRefreshDue(rankedAt, now, settings.refresh, zone)))
    return UsageReadPlan(
        labels = settings.enabled && settings.showUsage && settings.display.isNotEmpty(),
        opens = settings.enabled && (needOpenCounts || settings.sortApps || ranking),
        ranking = ranking,
    )
}

internal fun usageRefreshDue(previous: Long, now: Long, frequency: UsageRefresh, zone: ZoneId): Boolean {
    if (previous <= 0) return true
    if (frequency == UsageRefresh.Never) return false
    if (now < previous) return true
    val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val old = Instant.ofEpochMilli(previous).atZone(zone).toLocalDate()
    return when (frequency) {
        UsageRefresh.Daily -> old != date
        UsageRefresh.Weekly -> old.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) !=
            date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        UsageRefresh.Never -> false
    }
}

/** Pure event reducer: count foreground activities, not background services or screen-off time. */
internal class ForegroundUsage(private val start: Long, private val end: Long) {
    private val active = mutableMapOf<String, MutableSet<String>>()
    private val since = mutableMapOf<String, Long>()
    private val totals = mutableMapOf<String, Long>()

    fun resume(pkg: String, activity: String, time: Long) {
        val activities = active.getOrPut(pkg) { mutableSetOf() }
        if (activities.isEmpty()) since[pkg] = time
        activities.add(activity)
    }
    fun pause(pkg: String, activity: String, time: Long) {
        val activities = active[pkg] ?: return
        activities.remove(activity)
        if (activities.isEmpty()) close(pkg, time)
    }
    fun stopAll(time: Long) {
        since.keys.toList().forEach { close(it, time) }
        active.clear()
    }
    private fun close(pkg: String, time: Long) {
        val from = since.remove(pkg) ?: return
        val duration = (minOf(time, end) - maxOf(from, start)).coerceAtLeast(0)
        totals[pkg] = (totals[pkg] ?: 0) + duration
    }
    fun finish(): Map<String, Long> { stopAll(end); return totals.toMap() }
}
