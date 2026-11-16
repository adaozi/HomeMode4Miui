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
    val displayColor: Int? = null,
    val calendarName: String? = null,
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
    deprioritizeAllDay: Boolean = true,
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
    if (deprioritizeAllDay) {
        val withinDayOrder = compareBy<ScheduleEvent> { it.isAllDay }
            .thenBy { it.localBoundary(it.startsAt, zone) }
        days.values.forEach { it.sortWith(withinDayOrder) }
    }
    return days
}

/** Ongoing and upcoming occurrences, with zero meaning no display limit. */
fun visibleAgendaEvents(
    events: List<ScheduleEvent>,
    now: Instant,
    limit: Int,
    zone: ZoneId = ZoneId.systemDefault(),
    deprioritizeAllDay: Boolean = true,
): List<ScheduleEvent> {
    val chronological = compareBy<ScheduleEvent> { it.localBoundary(it.startsAt, zone) }
    val order = if (deprioritizeAllDay) {
        val today = now.atZone(zone).toLocalDate()
        // Ongoing occurrences belong to today for priority, even when they
        // began earlier. Future dates still follow all of today's events.
        compareBy<ScheduleEvent> {
            maxOf(today, it.localBoundary(it.startsAt, zone).atZone(zone).toLocalDate())
        }.thenBy { it.isAllDay }.then(chronological)
    } else chronological
    return events.asSequence()
        .filter { it.localBoundary(it.endsAt, zone).isAfter(now) }
        .sortedWith(order)
        .distinctBy { it.id to it.startsAt }
        .let { if (limit > 0) it.take(limit) else it }
        .toList()
}

fun nextVisibleEvent(
    events: List<ScheduleEvent>,
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
    deprioritizeAllDay: Boolean = true,
): ScheduleEvent? = visibleAgendaEvents(events, now, limit = 1, zone = zone,
    deprioritizeAllDay = deprioritizeAllDay).firstOrNull()

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
