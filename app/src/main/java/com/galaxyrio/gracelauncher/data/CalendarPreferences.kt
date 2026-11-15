package com.galaxyrio.gracelauncher.data

import org.json.JSONArray
import org.json.JSONObject
import com.galaxyrio.gracelauncher.data.tasks.TasksPreferences

enum class CalendarRefreshMode(val intervalMillis: Long?) {
    EveryMinute(60_000L),
    Every15Minutes(900_000L),
    Hourly(3_600_000L),
    OnHomeEntry(null),
    Manual(null),
}

data class CalendarPreferences(
    val daysAhead: Int = 14,
    val refreshMode: CalendarRefreshMode = CalendarRefreshMode.EveryMinute,
    val deprioritizeAllDay: Boolean = true,
    /** null follows system-visible calendars; an empty set explicitly selects none. */
    val selectedCalendarIds: Set<Long>? = null,
    /** null lets Android resolve the event with the user's system default. */
    val appPackage: String? = null,
    val tasksOrg: TasksPreferences = TasksPreferences(),
) {
    val query: CalendarQuery get() = CalendarQuery(daysAhead, selectedCalendarIds)

    fun normalized() = copy(
        daysAhead = daysAhead.coerceIn(DaysAheadRange),
        selectedCalendarIds = selectedCalendarIds?.filterTo(linkedSetOf()) { it >= 0L },
        appPackage = appPackage?.trim()?.takeIf(String::isNotEmpty),
        tasksOrg = tasksOrg.normalized(),
    )

    fun encode(): String = normalized().let { value ->
        JSONObject().put("daysAhead", value.daysAhead).put("refreshMode", value.refreshMode.name)
            .put("deprioritizeAllDay", value.deprioritizeAllDay)
            .put("selectedCalendarIds", value.selectedCalendarIds?.let { JSONArray(it.sorted()) } ?: JSONObject.NULL)
            .put("appPackage", value.appPackage ?: JSONObject.NULL)
            .put("tasksOrg", value.tasksOrg.json()).toString()
    }

    companion object {
        val DaysAheadOptions = listOf(1, 3, 7, 14, 30, 90)
        val DaysAheadRange = DaysAheadOptions.first()..DaysAheadOptions.last()

        fun decode(value: String?): CalendarPreferences = runCatching {
            val json = JSONObject(value ?: "{}")
            val defaults = CalendarPreferences()
            CalendarPreferences(
                daysAhead = json.optInt("daysAhead", defaults.daysAhead),
                refreshMode = CalendarRefreshMode.entries.firstOrNull { it.name == json.optString("refreshMode") }
                    ?: defaults.refreshMode,
                deprioritizeAllDay = json.optBoolean("deprioritizeAllDay", defaults.deprioritizeAllDay),
                selectedCalendarIds = json.optJSONArray("selectedCalendarIds")?.let { ids ->
                    buildSet {
                        for (index in 0 until ids.length()) {
                            val id = ids.optLong(index, -1L)
                            if (id >= 0L) add(id)
                        }
                    }
                },
                appPackage = if (json.isNull("appPackage")) null else json.optString("appPackage"),
                tasksOrg = TasksPreferences.decode(json.optJSONObject("tasksOrg")),
            ).normalized()
        }.getOrDefault(CalendarPreferences())
    }
}

/** Only provider query inputs participate in event-cache and in-flight read matching. */
data class CalendarQuery(val daysAhead: Int, val selectedCalendarIds: Set<Long>?)
