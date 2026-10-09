package com.galaxyrio.gracelauncher.ui.weather

import com.galaxyrio.gracelauncher.data.ScheduleEvent
import com.galaxyrio.gracelauncher.data.agendaDays
import java.time.LocalDate
import java.time.ZoneId

/** Weather fills gaps between calendar dates without shortening the calendar's horizon. */
internal fun weatherAgendaDays(
    events: List<ScheduleEvent>,
    forecastDates: List<LocalDate>,
    today: LocalDate,
    forecastDayCount: Int,
    zone: ZoneId = ZoneId.systemDefault(),
): Map<LocalDate, List<ScheduleEvent>> {
    val days = agendaDays(events, today, zone).toSortedMap()
    val weatherEnd = today.plusDays(forecastDayCount.coerceIn(1, 14).toLong())
    forecastDates.filter { it >= today && it < weatherEnd }.forEach { date ->
        days.putIfAbsent(date, emptyList())
    }
    return days
}
