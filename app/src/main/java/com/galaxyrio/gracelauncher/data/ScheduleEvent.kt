package com.galaxyrio.gracelauncher.data

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

data class ScheduleEvent(
    val id: Long,
    val title: String,
    val startsAt: Instant,
    val endsAt: Instant,
    val isAllDay: Boolean,
    val location: String?,
    val calendarColor: Int?,
)

enum class CountdownUnit { LessThanMinute, Minutes, Hours, Days, Ongoing, AllDay }

data class EventCountdown(val unit: CountdownUnit, val value: Int = 0, val minutes: Int = 0)

// Calendar Provider stores all-day boundaries as UTC dates, not local timestamps.
internal fun ScheduleEvent.localBoundary(instant: Instant, zone: ZoneId): Instant =
    if (isAllDay) instant.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(zone).toInstant() else instant

/** Expand multi-day occurrences without treating the exclusive all-day end as another day. */
fun agendaDays(
    events: List<ScheduleEvent>,
    today: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
    dayCount: Long = 14,
): Map<LocalDate, List<ScheduleEvent>> {
    val days = sortedMapOf<LocalDate, MutableList<ScheduleEvent>>()
    days[today] = mutableListOf()
    val horizon = today.plusDays(dayCount)
    events.sortedBy { it.localBoundary(it.startsAt, zone) }.forEach { event ->
        var day = maxOf(today, event.localBoundary(event.startsAt, zone).atZone(zone).toLocalDate())
        val lastDay = event.localBoundary(event.endsAt, zone).minusNanos(1).atZone(zone).toLocalDate()
        while (day <= lastDay && day < horizon) {
            days.getOrPut(day) { mutableListOf() }.add(event)
            day = day.plusDays(1)
        }
    }
    return days
}

fun nextVisibleEvent(
    events: List<ScheduleEvent>,
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
): ScheduleEvent? = events
    .filter { it.localBoundary(it.endsAt, zone).isAfter(now) }
    .minByOrNull { it.localBoundary(it.startsAt, zone) }

fun eventCountdown(
    event: ScheduleEvent,
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
): EventCountdown {
    val seconds = Duration.between(now, event.localBoundary(event.startsAt, zone)).seconds
    if (seconds <= 0) {
        return EventCountdown(if (event.isAllDay) CountdownUnit.AllDay else CountdownUnit.Ongoing)
    }
    if (seconds < 60) return EventCountdown(CountdownUnit.LessThanMinute)
    val minutes = ((seconds + 59) / 60).toInt()
    return when {
        minutes < 60 -> EventCountdown(CountdownUnit.Minutes, value = minutes)
        minutes < 24 * 60 -> EventCountdown(CountdownUnit.Hours, value = minutes / 60, minutes = minutes % 60)
        else -> EventCountdown(CountdownUnit.Days, value = minutes / (24 * 60))
    }
}
